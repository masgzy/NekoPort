package io.github.masgzy.nekoport.core

import android.content.Context
import io.github.masgzy.nekoport.ftp.FtpServer
import io.github.masgzy.nekoport.server.HttpServer
import io.github.masgzy.nekoport.server.PhpCgiManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap

data class SiteRuntime(
    val site: SiteConfig,
    val running: Boolean,
)

data class ManagerState(
    val running: Boolean = false,
    val sites: List<SiteRuntime> = emptyList(),
    val ftpRunning: Boolean = false,
    val activeFtpSessions: Int = 0,
    val totalRequests: Long = 0,
)

/**
 * Single source of truth for the server lifecycle: owns all [HttpServer] and
 * [FtpServer] instances, the [PhpCgiManager] and the persisted [GlobalConfig].
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
    private val phpCgi = PhpCgiManager(context)
    private val httpServers = ConcurrentHashMap<Long, HttpServer>()

    @Volatile
    private var ftpServer: FtpServer? = null

    private val _state = MutableStateFlow(ManagerState())
    val state: StateFlow<ManagerState> = _state

    private val _config = MutableStateFlow(configStore.load())
    val config: StateFlow<GlobalConfig> = _config

    val phpManager: PhpCgiManager get() = phpCgi

    val sitesRoot: File
        get() = File(context.filesDir, "sites").apply { mkdirs() }

    val phpRoot: File
        get() = File(context.filesDir, "php")

    fun siteDir(name: String): File = File(sitesRoot, name)

    // ---------- config ----------

    fun saveConfig(config: GlobalConfig) {
        configStore.save(config)
        _config.value = config
        AppLog.accessLogEnabled = config.settings.accessLogEnabled
    }

    fun updateSites(sites: List<SiteConfig>) = saveConfig(_config.value.copy(sites = sites))

    fun updateFtp(ftp: FtpConfig) = saveConfig(_config.value.copy(ftp = ftp))

    fun updateSettings(settings: AppSettings) = saveConfig(_config.value.copy(settings = settings))

    fun nextSiteId(): Long = (_config.value.sites.maxOfOrNull { it.id } ?: 0L) + 1L

    // ---------- lifecycle ----------

    fun startAll() {
        if (_state.value.running) return
        AppLog.accessLogEnabled = _config.value.settings.accessLogEnabled
        for (site in _config.value.sites) {
            if (site.enabled) startSite(site)
        }
        if (_config.value.ftp.enabled) startFtp()
        publish()
    }

    fun stopAll() {
        httpServers.values.forEach { it.stop() }
        httpServers.clear()
        ftpServer?.stop()
        ftpServer = null
        phpCgi.stopAll()
        publish()
        AppLog.i("Manager", "all services stopped")
    }

    fun startSite(site: SiteConfig) {
        stopSiteQuiet(site.id)
        val dir = siteDir(site.name).apply { mkdirs() }
        val server = HttpServer(site, dir, phpCgi)
        try {
            server.start()
            httpServers[site.id] = server
            if (site.enablePhp) phpCgi.ensureStarted(site, dir)
        } catch (e: Exception) {
            AppLog.e("Manager", "cannot start site '${site.name}' on port ${site.port}: ${e.message}")
        }
        publish()
    }

    fun stopSite(siteId: Long) {
        stopSiteQuiet(siteId)
        publish()
    }

    private fun stopSiteQuiet(siteId: Long) {
        httpServers.remove(siteId)?.stop()
        phpCgi.stopSite(siteId)
    }

    fun startFtp() {
        stopFtpQuiet()
        val ftp = FtpServer(_config.value.ftp, sitesRoot)
        try {
            ftp.start()
            ftpServer = ftp
        } catch (e: Exception) {
            AppLog.e("Manager", "cannot start FTP on port ${_config.value.ftp.port}: ${e.message}")
        }
        publish()
    }

    fun stopFtp() {
        stopFtpQuiet()
        publish()
    }

    private fun stopFtpQuiet() {
        ftpServer?.stop()
        ftpServer = null
    }

    private fun publish() {
        val cfg = _config.value
        val sites = cfg.sites.map { SiteRuntime(it, running = httpServers.containsKey(it.id)) }
        val total = httpServers.values.sumOf { it.requestCount.get() }
        _state.value = ManagerState(
            running = httpServers.isNotEmpty() || ftpServer != null,
            sites = sites,
            ftpRunning = ftpServer != null,
            activeFtpSessions = ftpServer?.activeSessions ?: 0,
            totalRequests = total,
        )
    }

    /** Forces a state refresh (used for periodic UI updates). */
    fun refresh() = publish()

    fun isSiteRunning(siteId: Long): Boolean = httpServers.containsKey(siteId)

    fun uninstallPhp() {
        phpCgi.stopAll()
        io.github.masgzy.nekoport.php.PhpEnvironment.uninstall(context)
        saveConfig(_config.value.copy(settings = _config.value.settings.copy(installedPhpVersion = null)))
    }

    fun setPhpVersion(version: String?) {
        saveConfig(_config.value.copy(settings = _config.value.settings.copy(installedPhpVersion = version)))
    }
}
