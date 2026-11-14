package io.github.masgzy.nekoport.core

import android.content.Context
import android.os.Process
import io.github.masgzy.nekoport.R
import io.github.masgzy.nekoport.core.Engine
import io.github.masgzy.nekoport.core.MariaConfig
import io.github.masgzy.nekoport.core.SiteConfig
import io.github.masgzy.nekoport.core.CronConfig
import io.github.masgzy.nekoport.core.CronScheduler
import io.github.masgzy.nekoport.runtime.ConfigWriter
import io.github.masgzy.nekoport.runtime.MariaDbController
import io.github.masgzy.nekoport.runtime.ProcessController
import io.github.masgzy.nekoport.runtime.RuntimePackage
import io.github.masgzy.nekoport.runtime.TermuxRuntime
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class SiteRuntime(
    val site: SiteConfig,
    val running: Boolean,
    val statusText: String? = null,
)

data class RuntimeStatus(
    val installed: Boolean,
    val versions: Map<RuntimePackage, String> = emptyMap(),
    val busy: Boolean = false,
)

data class ManagerState(
    val running: Boolean = false,
    val sites: List<SiteRuntime> = emptyList(),
    val ftpRunning: Boolean = false,
    val ftpStatusText: String? = null,
    val phpFpmRunning: Boolean = false,
    val mariaRunning: Boolean = false,
    val mariaStatusText: String? = null,
    val cronRunning: Boolean = false,
    val runtime: RuntimeStatus = RuntimeStatus(false),
)

/**
 * Single source of truth for the server lifecycle. Every service is a real
 * upstream binary (lighttpd / nginx / Apache / php-fpm / mariadbd / pure-ftpd)
 * managed as an external process; NekoPort only generates their configurations.
 *
 * Threading: every lifecycle method is a `suspend` function that shifts work
 * to [Dispatchers.IO]. Process launches, `waitFor`, port polling and config
 * writes are blocking operations and must never run on the Android main
 * thread (that caused the ANR/crash family fixed in 0.1.0-alpha.2). UI code
 * calls these from a coroutine scope; [ServerService] launches them on its
 * own IO scope. A per-component [Mutex] guarantees that the same component is
 * never started twice concurrently, and lightweight config signatures make
 * repeated starts no-ops.
 */
class ServerManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: ServerManager? = null

        fun get(context: Context): ServerManager =
            instance ?: synchronized(this) {
                instance ?: ServerManager(context.applicationContext).also { instance = it }
            }
    }

    private val configStore = ConfigStore(context)

    private val _state = MutableStateFlow(ManagerState())
    val state: StateFlow<ManagerState> = _state

    private val _config = MutableStateFlow(configStore.load())
    val config: StateFlow<GlobalConfig> = _config

    private var phpFpmSignature: String = ""
    private var ftpSignature: String = ""
    private val siteSignatures = HashMap<Long, String>()

    private val cronScheduler = CronScheduler(context, this)

    /** Serializes blocking lifecycle work; every public suspend method locks its own mutex. */
    private val startAllMutex = Mutex()
    private val siteMutex = Mutex()
    private val ftpMutex = Mutex()
    private val mariaMutex = Mutex()
    private val configWriteMutex = Mutex()

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> AppLog.e("Manager", "uncaught: ${e.message}") },
    )

    init {
        cronScheduler.apply(configStore.load().cron)
        publish()
        startGlobalPoller()
        // Version probing spawns one subprocess per component; do it once in
        // the background instead of on every UI poll tick.
        scope.launch { refreshRuntimeStatus() }
    }

    val sitesRoot: File
        get() = File(context.filesDir, "sites").apply { mkdirs() }

    fun siteDir(name: String): File = File(sitesRoot, name)

    /** Effective document root of [site]: custom absolute path or the default private dir. */
    fun siteDocRoot(site: SiteConfig): File {
        val custom = site.docRoot.trim()
        return if (custom.isEmpty()) File(sitesRoot, site.name) else File(custom)
    }

    // ---------- config ----------

    fun saveConfig(config: GlobalConfig) {
        _config.value = config
        // Disk write happens off the main thread; serialized to keep writes ordered.
        scope.launch { configWriteMutex.withLock { configStore.save(config) } }
    }

    /** Synchronous save used only from background threads (install flows, boot). */
    fun saveConfigBlocking(config: GlobalConfig) {
        _config.value = config
        runBlocking { configWriteMutex.withLock { configStore.save(config) } }
    }

    fun updateSites(sites: List<SiteConfig>) = saveConfig(_config.value.copy(sites = sites))

    fun updateFtp(ftp: FtpConfig) = saveConfig(_config.value.copy(ftp = ftp))

    fun updateMaria(maria: MariaConfig) = saveConfig(_config.value.copy(maria = maria))

    fun updateSettings(settings: AppSettings) = saveConfig(_config.value.copy(settings = settings))

    fun updateCron(cron: CronConfig) {
        saveConfig(_config.value.copy(cron = cron))
        cronScheduler.apply(cron)
        publish()
    }

    val cronActive: Boolean
        get() = cronScheduler.isRunning()

    fun nextSiteId(): Long = (_config.value.sites.maxOfOrNull { it.id } ?: 0L) + 1L

    // ---------- runtime status ----------

    /**
     * Re-probes install state and component versions. This spawns one
     * subprocess per component, so it is only called after installation /
     * uninstallation or from the background init - never from UI polling.
     */
    suspend fun refreshRuntimeStatus(busy: Boolean = false) = withContext(Dispatchers.IO) {
        val installed = TermuxRuntime.isInstalled(context)
        val versions = TermuxRuntime.status(context)
        _state.value = _state.value.copy(
            runtime = RuntimeStatus(installed, versions, busy),
        )
    }

    // ---------- lifecycle ----------

    fun startAllAsync() {
        scope.launch { runCatching { startAll() } }
    }

    suspend fun startAll() = startAllMutex.withLock {
        if (!TermuxRuntime.isInstalled(context)) {
            AppLog.e("Manager", "runtime not installed; install server components first")
            withContext(Dispatchers.IO) { refreshRuntimeStatus() }
            return@withLock
        }
        for (site in _config.value.sites) {
            if (site.enabled) startSiteInternal(site)
        }
        if (_config.value.ftp.enabled) startFtpInternal()
        if (_config.value.maria.enabled) startMariaInternal()
        publish()
    }

    suspend fun stopAll() {
        startAllMutex.withLock {
            withContext(Dispatchers.IO) {
                ProcessController.stopAll()
                phpFpmSignature = ""
                ftpSignature = ""
                siteSignatures.clear()
                publish()
                AppLog.i("Manager", "all services stopped")
            }
        }
    }

    /** Fire-and-forget start used by UI switches; errors are logged, never thrown. */
    fun startSiteAsync(site: SiteConfig) {
        scope.launch { runCatching { startSite(site) } }
    }

    fun stopSiteAsync(siteId: Long) {
        scope.launch { runCatching { stopSite(siteId) } }
    }

    suspend fun startSite(site: SiteConfig) = siteMutex.withLock {
        startSiteInternal(site)
    }

    private suspend fun startSiteInternal(site: SiteConfig) = withContext(Dispatchers.IO) {
        if (!TermuxRuntime.isInstalled(context)) {
            setSiteStatus(site.id, context.getString(R.string.site_status_no_runtime))
            return@withContext
        }
        val signature = siteSignature(site)
        if (ProcessController.isRunning("site-${site.id}-${site.engine.id}") &&
            siteSignatures[site.id] == signature
        ) {
            return@withContext // already up with the same settings
        }
        setSiteStatus(site.id, context.getString(R.string.site_status_starting))
        stopSiteQuiet(site.id)
        val dir = siteDocRoot(site).apply { mkdirs() }
        try {
            if (site.enablePhp) ensurePhpFpm()
            val conf: File = when (site.engine) {
                Engine.NGINX -> ConfigWriter.writeNginxConf(context, site, dir)
                Engine.APACHE -> ConfigWriter.writeHttpdConf(context, site, dir)
                Engine.LIGHTTPD -> ConfigWriter.writeLighttpdConf(context, site, dir)
            }
            val key = "site-${site.id}-${site.engine.id}"
            val launchLog = File(TermuxRuntime.logsDir(context), "$key-launch.log")
            when (site.engine) {
                Engine.NGINX -> ProcessController.launch(
                    context, key, ProcessController.binary(context, "nginx"),
                    listOf("-p", context.filesDir.canonicalPath + "/", "-c", conf.canonicalPath),
                    launchLog, sitesRoot,
                )
                Engine.APACHE -> ProcessController.launch(
                    context, key, ProcessController.binary(context, "httpd"),
                    listOf("-DFOREGROUND", "-f", conf.canonicalPath),
                    launchLog, sitesRoot,
                )
                Engine.LIGHTTPD -> ProcessController.launch(
                    context, key, ProcessController.binary(context, "lighttpd"),
                    listOf("-D", "-f", conf.canonicalPath, "-m", File(TermuxRuntime.prefixDir(context), "lib/lighttpd").canonicalPath),
                    launchLog, sitesRoot,
                )
            }
            if (ProcessController.awaitPort(site.port)) {
                siteSignatures[site.id] = signature
                setSiteStatus(site.id, null)
                AppLog.i("Manager", "site '${site.name}' up on ${site.engine.displayName} :${site.port}")
            } else {
                siteSignatures.remove(site.id)
                AppLog.e("Manager", "site '${site.name}' did not open port ${site.port}; see logs")
                setSiteStatus(
                    site.id,
                    context.getString(R.string.site_status_port_failed, site.port),
                )
            }
        } catch (e: Exception) {
            siteSignatures.remove(site.id)
            AppLog.e("Manager", "cannot start site '${site.name}' on port ${site.port}: ${e.message}")
            setSiteStatus(site.id, e.message ?: "error")
        }
        publish()
    }

    suspend fun stopSite(siteId: Long) = siteMutex.withLock {
        withContext(Dispatchers.IO) {
            val site = _config.value.sites.firstOrNull { it.id == siteId }
            stopSiteQuiet(siteId)
            if (site != null) ProcessController.stop("site-$siteId-${site.engine.id}")
            siteSignatures.remove(siteId)
            publish()
        }
    }

    private fun stopSiteQuiet(siteId: Long) {
        for (engine in Engine.entries) {
            ProcessController.stop("site-$siteId-${engine.id}", timeoutMs = 6000)
        }
    }

    /** Ensures a php-fpm master is running with pools matching the current config. */
    private fun ensurePhpFpm() {
        val signature = ConfigWriter.phpFpmSignature(_config.value.sites)
        if (ProcessController.isRunning("php-fpm") && signature == phpFpmSignature) return
        ProcessController.stop("php-fpm", timeoutMs = 8000)
        ConfigWriter.writePhpIni(context)
        // PHP looks up the literal name "php.ini" in PHPRC: seed the editable
        // file from the generated one unless the user already created it.
        runCatching {
            val auto = ConfigWriter.phpIniAuto(context)
            val ini = ConfigWriter.phpIni(context)
            if (!ini.exists() && auto.exists()) auto.copyTo(ini, overwrite = true)
        }
        val conf = ConfigWriter.effectiveConf(ConfigWriter.writePhpFpmConf(context, _config.value.sites))
        ProcessController.launch(
            context, "php-fpm", ProcessController.binary(context, "php-fpm"),
            listOf("--fpm-config", conf.canonicalPath),
            File(TermuxRuntime.logsDir(context), "php-fpm-launch.log"), sitesRoot,
        )
        phpFpmSignature = signature
        AppLog.i("Manager", "php-fpm (re)started with pools for PHP sites")
    }

    // ---------- FTP ----------

    fun startFtpAsync() {
        scope.launch { runCatching { startFtp() } }
    }

    fun stopFtpAsync() {
        scope.launch { runCatching { stopFtp() } }
    }

    suspend fun startFtp() = ftpMutex.withLock {
        startFtpInternal()
    }

    private suspend fun startFtpInternal() = withContext(Dispatchers.IO) {
        if (!TermuxRuntime.isInstalled(context)) {
            _state.value = _state.value.copy(
                ftpRunning = false,
                ftpStatusText = context.getString(R.string.site_status_no_runtime),
            )
            return@withContext
        }
        val ftp = _config.value.ftp
        val signature = ftpSignature(ftp)
        if (ProcessController.isRunning("ftp") && ftpSignature == signature) return@withContext
        _state.value = _state.value.copy(
            ftpRunning = false,
            ftpStatusText = context.getString(R.string.ftp_status_starting),
        )
        stopFtpQuiet()
        try {
            val (passwdFile, pdbFile) = ConfigWriter.pureDbFiles(context)
            setUpFtpUser(ftp, passwdFile, pdbFile)
            val args = ConfigWriter.pureFtpdArgs(context, ftp.port, ftp.passivePortStart, pdbFile)
            ProcessController.launch(
                context, "ftp", ProcessController.binary(context, "pure-ftpd"), args,
                File(TermuxRuntime.logsDir(context), "ftp.log"), sitesRoot,
            )
            if (ProcessController.awaitPort(ftp.port)) {
                ftpSignature = signature
                _state.value = _state.value.copy(ftpRunning = true, ftpStatusText = null)
                AppLog.i("Manager", "FTP up on port ${ftp.port} (user '${ftp.username}')")
            } else {
                ftpSignature = ""
                _state.value = _state.value.copy(
                    ftpStatusText = context.getString(R.string.site_status_port_failed, ftp.port),
                )
                AppLog.e("Manager", "FTP did not open port ${ftp.port}; see logs")
            }
        } catch (e: Exception) {
            ftpSignature = ""
            AppLog.e("Manager", "cannot start FTP on port ${ftp.port}: ${e.message}")
            _state.value = _state.value.copy(ftpStatusText = e.message)
        }
        publish()
    }

    /**
     * Creates/updates every configured pure-ftpd virtual account with its
     * credentials. Virtual users map to the app sandbox uid, so the FTP
     * sandbox is exactly the app sandbox (no privilege escalation). The
     * primary (legacy) account shares the sites root; extra accounts may
     * chroot to their own directory.
     *
     * Every `pure-pw` invocation gets a hard timeout: the process asks for
     * the password on stdin, and if it ever waits for input again the old
     * infinite `waitFor()` froze the whole app.
     */
    private fun setUpFtpUser(ftp: FtpConfig, passwdFile: File, pdbFile: File) {
        val uid = Process.myUid().toString()
        val ftpRoot = sitesRoot.canonicalPath
        val purePw = ProcessController.binary(context, "pure-pw")
        val accounts = ftp.effectiveAccounts()

        runCatching {
            for (account in accounts) {
                val name = account.username.filter { it.isLetterOrDigit() || it in "._-" }.ifEmpty { "neko" }
                val home = account.root.trim().ifEmpty { ftpRoot }
                val homeDir = File(home).apply { mkdirs() }
                val (out, rc) = runPurePw(
                    purePw, listOf(
                        "useradd", name,
                        "-u", uid, "-g", uid, "-d", homeDir.canonicalPath,
                        "-f", passwdFile.canonicalPath,
                    ),
                    account.password,
                )
                if (rc != 0) {
                    AppLog.w("Manager", "pure-pw useradd $name rc=$rc: $out")
                    // useradd fails if the user exists; try passwd update, then mkdb.
                    val (out2, rc2) = runPurePw(
                        purePw, listOf("passwd", name, "-f", passwdFile.canonicalPath),
                        account.password,
                    )
                    if (rc2 != 0) throw Exception("pure-pw passwd $name rc=$rc2: $out2")
                }
            }
            val (mkdbOut, mkdbRc) = runPurePw(
                purePw, listOf("mkdb", pdbFile.canonicalPath, "-f", passwdFile.canonicalPath),
                null,
            )
            if (mkdbRc != 0) throw Exception("pure-pw mkdb rc=$mkdbRc: $mkdbOut")
        }.onFailure {
            AppLog.e("Manager", "FTP user setup failed: ${it.message}")
            throw it
        }
    }

    /** Runs pure-pw with the password piped to stdin and a hard timeout. */
    private fun runPurePw(purePw: File, args: List<String>, password: String?): Pair<String, Int> {
        val pb = ProcessBuilder(listOf(purePw.canonicalPath) + args)
        pb.redirectErrorStream(true)
        val p = pb.start()
        password?.let {
            p.outputStream.use { out ->
                out.write((it + "\n" + it + "\n").toByteArray())
                out.flush()
            }
        }
        val output = StringBuilder()
        val reader = p.inputStream.bufferedReader()
        val pump = Thread {
            runCatching {
                reader.forEachLine { line ->
                    synchronized(output) { output.appendLine(line) }
                }
            }
        }.apply { isDaemon = true; start() }
        val finished = p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            p.destroyForcibly()
            pump.join(1000)
            AppLog.w("Manager", "pure-pw ${args.firstOrNull()} timed out; killed")
            throw Exception("pure-pw ${args.firstOrNull()} timed out")
        }
        pump.join(1000)
        val text = synchronized(output) { output.toString() }
        AppLog.i("Manager", "pure-pw ${args.firstOrNull()} rc=${p.exitValue()}${if (text.isNotBlank()) ": ${text.trim().take(400)}" else ""}")
        return text to p.exitValue()
    }

    suspend fun stopFtp() = ftpMutex.withLock {
        withContext(Dispatchers.IO) {
            stopFtpQuiet()
            ftpSignature = ""
            publish()
        }
    }

    private fun stopFtpQuiet() {
        ProcessController.stop("ftp")
    }

    // ---------- MariaDB (MySQL) ----------

    fun startMariaAsync() {
        scope.launch { runCatching { startMaria() } }
    }

    fun stopMariaAsync() {
        scope.launch { runCatching { stopMaria() } }
    }

    suspend fun startMaria() = mariaMutex.withLock {
        startMariaInternal()
    }

    private suspend fun startMariaInternal() = withContext(Dispatchers.IO) {
        if (!TermuxRuntime.isMariaDbInstalled(context)) {
            _state.value = _state.value.copy(
                mariaRunning = false,
                mariaStatusText = context.getString(R.string.maria_not_installed),
            )
            publish()
            return@withContext
        }
        if (ProcessController.isRunning("maria")) {
            publish()
            return@withContext
        }
        _state.value = _state.value.copy(
            mariaRunning = false,
            mariaStatusText = context.getString(R.string.maria_status_initializing),
        )
        ProcessController.stop("maria", timeoutMs = 10000)
        try {
            if (!MariaDbController.isInitialized(context)) {
                val result = MariaDbController.initialize(context)
                if (result.isFailure) {
                    val failure = result.exceptionOrNull()
                    AppLog.e("Manager", "MariaDB init failed: ${failure?.message}")
                    _state.value = _state.value.copy(
                        mariaRunning = false,
                        mariaStatusText = failure?.message,
                    )
                    publish()
                    return@withContext
                }
            }
            _state.value = _state.value.copy(
                mariaStatusText = context.getString(R.string.maria_status_starting),
            )
            MariaDbController.start(context, _config.value.maria)
        } catch (e: Exception) {
            AppLog.e("Manager", "cannot start MariaDB on port ${_config.value.maria.port}: ${e.message}")
            _state.value = _state.value.copy(mariaStatusText = e.message)
        }
        publish()
    }

    suspend fun stopMaria() = mariaMutex.withLock {
        withContext(Dispatchers.IO) {
            MariaDbController.stop(context)
            publish()
        }
    }

    // ---------- state ----------

    private fun setSiteStatus(siteId: Long, text: String?) {
        _state.value = _state.value.copy(
            sites = _state.value.sites.map {
                if (it.site.id == siteId) it.copy(running = false, statusText = text) else it
            },
        )
    }

    private fun siteSignature(site: SiteConfig): String =
        listOf(
            site.port, site.engine.id, site.enablePhp, site.directoryListing,
            site.indexFiles.joinToString(","), site.phpDenyDirs.joinToString(","),
            site.phpExtensions.joinToString(","), site.docRoot,
        ).joinToString("|")

    private fun ftpSignature(ftp: FtpConfig): String =
        (ftp.effectiveAccounts().joinToString(";") { "${it.username}|${it.password}|${it.root}" } +
            "|${ftp.port}|${ftp.passivePortStart}")

    private fun publish() {
        val cfg = _config.value
        val sites = cfg.sites.map { site ->
            SiteRuntime(
                site = site,
                running = ProcessController.isRunning("site-${site.id}-${site.engine.id}"),
            )
        }
        val runtimeInstalled = TermuxRuntime.isInstalled(context)
        _state.value = _state.value.copy(
            running = sites.any { it.running } || ProcessController.isRunning("ftp") || ProcessController.isRunning("maria"),
            sites = sites,
            ftpRunning = ProcessController.isRunning("ftp"),
            phpFpmRunning = ProcessController.isRunning("php-fpm"),
            mariaRunning = ProcessController.isRunning("maria"),
            cronRunning = cronScheduler.isRunning(),
            runtime = _state.value.runtime.copy(installed = runtimeInstalled),
        )
    }

    /**
     * Single global poller replacing the per-page 4 s loops. Only in-memory
     * queries (`Process.isAlive`) run here, on IO - no subprocesses, no disk.
     */
    private fun startGlobalPoller() {
        scope.launch {
            while (isActive) {
                delay(4000)
                runCatching { publish() }
            }
        }
    }

    /** Forces a state refresh (used after config-driven changes). */
    fun refresh() = publish()
}
