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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    private val cronScheduler = CronScheduler(context, this)

    init {
        cronScheduler.apply(configStore.load().cron)
        publish()
    }

    val sitesRoot: File
        get() = File(context.filesDir, "sites").apply { mkdirs() }

    fun siteDir(name: String): File = File(sitesRoot, name)

    // ---------- config ----------

    fun saveConfig(config: GlobalConfig) {
        configStore.save(config)
        _config.value = config
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

    // ---------- runtime ----------

    fun refreshRuntimeStatus(busy: Boolean = false) {
        val installed = TermuxRuntime.isInstalled(context)
        val versions = TermuxRuntime.status(context)
        _state.value = _state.value.copy(
            runtime = RuntimeStatus(installed, versions, busy),
        )
    }

    // ---------- lifecycle ----------

    fun startAll() {
        if (!TermuxRuntime.isInstalled(context)) {
            AppLog.e("Manager", "runtime not installed; install server components first")
            refreshRuntimeStatus()
            return
        }
        for (site in _config.value.sites) {
            if (site.enabled) startSite(site)
        }
        if (_config.value.ftp.enabled) startFtp()
        if (_config.value.maria.enabled) startMaria()
        publish()
    }

    fun stopAll() {
        ProcessController.stopAll()
        phpFpmSignature = ""
        publish()
        AppLog.i("Manager", "all services stopped")
    }

    fun startSite(site: SiteConfig) {
        if (!TermuxRuntime.isInstalled(context)) {
            setSiteStatus(site.id, context.getString(R.string.site_status_no_runtime))
            return
        }
        stopSiteQuiet(site.id)
        val dir = siteDir(site.name).apply { mkdirs() }
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
                AppLog.i("Manager", "site '${site.name}' up on ${site.engine.displayName} :${site.port}")
            } else {
                AppLog.e("Manager", "site '${site.name}' did not open port ${site.port}; see logs")
            }
        } catch (e: Exception) {
            AppLog.e("Manager", "cannot start site '${site.name}' on port ${site.port}: ${e.message}")
            setSiteStatus(site.id, e.message ?: "error")
        }
        publish()
    }

    fun stopSite(siteId: Long) {
        val site = _config.value.sites.firstOrNull { it.id == siteId }
        stopSiteQuiet(siteId)
        if (site != null) ProcessController.stop("site-$siteId-${site.engine.id}")
        publish()
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
        val conf = ConfigWriter.effectiveConf(ConfigWriter.writePhpFpmConf(context, _config.value.sites, sitesRoot))
        ProcessController.launch(
            context, "php-fpm", ProcessController.binary(context, "php-fpm"),
            listOf("--fpm-config", conf.canonicalPath),
            File(TermuxRuntime.logsDir(context), "php-fpm-launch.log"), sitesRoot,
        )
        phpFpmSignature = signature
        AppLog.i("Manager", "php-fpm (re)started with pools for PHP sites")
    }

    // ---------- FTP ----------

    fun startFtp() {
        if (!TermuxRuntime.isInstalled(context)) {
            _state.value = _state.value.copy(
                ftpRunning = false,
                ftpStatusText = context.getString(R.string.site_status_no_runtime),
            )
            return
        }
        stopFtpQuiet()
        val ftp = _config.value.ftp
        try {
            val (passwdFile, pdbFile) = ConfigWriter.pureDbFiles(context)
            setUpFtpUser(ftp, passwdFile, pdbFile)
            val args = ConfigWriter.pureFtpdArgs(context, ftp.port, ftp.passivePortStart, pdbFile)
            ProcessController.launch(
                context, "ftp", ProcessController.binary(context, "pure-ftpd"), args,
                File(TermuxRuntime.logsDir(context), "ftp.log"), sitesRoot,
            )
            if (ProcessController.awaitPort(ftp.port)) {
                AppLog.i("Manager", "FTP up on port ${ftp.port} (user '${ftp.username}')")
            } else {
                AppLog.e("Manager", "FTP did not open port ${ftp.port}; see logs")
            }
        } catch (e: Exception) {
            AppLog.e("Manager", "cannot start FTP on port ${ftp.port}: ${e.message}")
            _state.value = _state.value.copy(ftpStatusText = e.message)
        }
        publish()
    }

    /**
     * Creates/updates the pure-ftpd virtual user with the configured
     * credentials. The virtual user maps to the app sandbox uid, so the FTP
     * sandbox is exactly the app sandbox (no privilege escalation).
     */
    private fun setUpFtpUser(ftp: FtpConfig, passwdFile: File, pdbFile: File) {
        val uid = Process.myUid().toString()
        val ftpRoot = sitesRoot.canonicalPath
        val purePw = ProcessController.binary(context, "pure-pw")
        val name = ftp.username.filter { it.isLetterOrDigit() || it in "._-" }.ifEmpty { "neko" }

        runCatching {
            val pb = ProcessBuilder(
                listOf(
                    purePw.canonicalPath, "useradd", name,
                    "-u", uid, "-g", uid, "-d", ftpRoot,
                    "-f", passwdFile.canonicalPath,
                ),
            )
            pb.redirectErrorStream(true)
            val p = pb.start()
            p.outputStream.use { out ->
                out.write((ftp.password + "\n" + ftp.password + "\n").toByteArray())
                out.flush()
            }
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor()
            if (p.exitValue() != 0) {
                AppLog.w("Manager", "pure-pw useradd rc=${p.exitValue()}: $out")
                // useradd fails if the user exists; try passwd update, then mkdb.
                val pb2 = ProcessBuilder(
                    listOf(
                        purePw.canonicalPath, "passwd", name,
                        "-f", passwdFile.canonicalPath,
                    ),
                )
                pb2.redirectErrorStream(true)
                val p2 = pb2.start()
                p2.outputStream.use { o ->
                    o.write((ftp.password + "\n" + ftp.password + "\n").toByteArray())
                    o.flush()
                }
                val out2 = p2.inputStream.bufferedReader().readText()
                p2.waitFor()
                if (p2.exitValue() != 0) throw Exception("pure-pw passwd rc=${p2.exitValue()}: $out2")
            }
            val mkdb = ProcessBuilder(
                listOf(
                    purePw.canonicalPath, "mkdb", pdbFile.canonicalPath,
                    "-f", passwdFile.canonicalPath,
                ),
            ).redirectErrorStream(true).start()
            val mkdbOut = mkdb.inputStream.bufferedReader().readText()
            mkdb.waitFor()
            if (mkdb.exitValue() != 0) throw Exception("pure-pw mkdb rc=${mkdb.exitValue()}: $mkdbOut")
        }.onFailure {
            AppLog.e("Manager", "FTP user setup failed: ${it.message}")
            throw it
        }
    }

    fun stopFtp() {
        stopFtpQuiet()
        publish()
    }

    private fun stopFtpQuiet() {
        ProcessController.stop("ftp")
    }

    // ---------- MariaDB (MySQL) ----------

    fun startMaria() {
        if (!TermuxRuntime.isMariaDbInstalled(context)) {
            _state.value = _state.value.copy(
                mariaRunning = false,
                mariaStatusText = context.getString(R.string.maria_not_installed),
            )
            return
        }
        ProcessController.stop("maria", timeoutMs = 10000)
        try {
            if (!MariaDbController.isInitialized(context)) {
                val result = MariaDbController.initialize(context)
                result.fold(
                    onSuccess = {},
                    onFailure = {
                        AppLog.e("Manager", "MariaDB init failed: ${it.message}")
                        _state.value = _state.value.copy(
                            mariaRunning = false,
                            mariaStatusText = it.message,
                        )
                        publish()
                        return
                    },
                )
            }
            MariaDbController.start(context, _config.value.maria)
        } catch (e: Exception) {
            AppLog.e("Manager", "cannot start MariaDB on port ${_config.value.maria.port}: ${e.message}")
            _state.value = _state.value.copy(mariaStatusText = e.message)
        }
        publish()
    }

    fun stopMaria() {
        MariaDbController.stop(context)
        publish()
    }

    // ---------- state ----------

    private fun setSiteStatus(siteId: Long, text: String) {
        _state.value = _state.value.copy(
            sites = _state.value.sites.map {
                if (it.site.id == siteId) it.copy(running = false, statusText = text) else it
            },
        )
    }

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

    /** Forces a state refresh (used for periodic UI updates). */
    fun refresh() = publish()
}
