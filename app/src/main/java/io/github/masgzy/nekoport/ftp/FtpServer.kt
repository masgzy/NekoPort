package io.github.masgzy.nekoport.ftp

import io.github.masgzy.nekoport.core.AppLog
import io.github.masgzy.nekoport.core.FtpConfig
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * A small, self-contained FTP server (RFC 959 subset):
 * passive-mode data transfer, single account auth, jailed to one root
 * directory with canonical-path checks against traversal.
 */
class FtpServer(
    private val config: FtpConfig,
    private val root: File,
) {
    @Volatile
    private var controlSocket: ServerSocket? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("ftp"))

    @Volatile
    var isRunning = false
        private set

    private val pasvCounter = AtomicInteger(0)
    private val sessions = AtomicInteger(0)

    val activeSessions: Int
        get() = sessions.get()

    private val pasvPortStart = config.passivePortStart.coerceIn(1024, 65000)

    fun start() {
        if (isRunning) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(config.port), 16)
        controlSocket = ss
        isRunning = true
        root.mkdirs()
        AppLog.i(TAG, "FTP listening on port ${config.port}, root=$root")
        scope.launch {
            while (isActive && isRunning) {
                val sock = try {
                    ss.accept()
                } catch (_: Exception) {
                    break
                }
                scope.launch {
                    val session = FtpSession(sock, config, root, pasvPortStart, pasvCounter)
                    try {
                        sessions.incrementAndGet()
                        session.run()
                    } finally {
                        sessions.decrementAndGet()
                    }
                }
            }
        }
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        runCatching { controlSocket?.close() }
        controlSocket = null
        scope.cancel()
        AppLog.i(TAG, "FTP stopped")
    }

    companion object {
        private const val TAG = "FtpServer"
        fun localIpForPasv(): InetAddress? = InetAddress.getLocalHost()
    }
}
