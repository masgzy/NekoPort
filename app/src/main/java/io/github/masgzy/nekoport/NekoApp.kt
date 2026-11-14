package io.github.masgzy.nekoport

import android.app.Application
import io.github.masgzy.nekoport.core.AppLog
import io.github.masgzy.nekoport.core.ServerManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NekoApp : Application() {

    lateinit var serverManager: ServerManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        AppLog.attach(this)
        installCrashHandler()
        serverManager = ServerManager.get(this)
        // Surface the previous session's log history in the UI.
        val persisted = AppLog.loadPersisted()
        if (persisted.isNotEmpty()) {
            AppLog.i("NekoApp", "restored ${persisted.size} log lines from previous session")
        }
        // The daemons survive the app being killed (no PDEATHSIG): reap them
        // before anything launches again, or the next startAll hits
        // "Address already in use" while the UI claims everything is stopped.
        Thread {
            runCatching { io.github.masgzy.nekoport.runtime.ProcessController.reapOrphanProcesses(this@NekoApp) }
        }.apply { isDaemon = true }.start()
        rotateDefaultFtpPassword()
        AppLog.i("NekoApp", "NekoPort started")
    }

    /**
     * The factory FTP password is a placeholder; replace it once with a
     * random one so no device ships with a known credential.
     */
    private fun rotateDefaultFtpPassword() {
        val config = serverManager.config.value
        if (config.ftp.password != "changeme-neko") return
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
        val random = java.security.SecureRandom()
        val password = (1..20).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
        serverManager.updateFtp(config.ftp.copy(password = password))
        AppLog.i("NekoApp", "generated a random FTP password (visible in Manage → FTP)")
    }

    /**
     * Any uncaught exception (including one escaping a coroutine) is written
     * to `runtime/logs/crash.log` before the process dies, so "the app
     * crashed" always leaves a readable trace behind.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val dir = File(filesDir, "runtime/logs").apply { mkdirs() }
                val file = File(dir, "crash.log")
                if (file.length() > 1024 * 1024) file.delete()
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                file.appendText(
                    "==== $stamp thread=${thread.name} ====\n" +
                        throwable.stackTraceToString() + "\n",
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        @Volatile
        private var instance: NekoApp? = null

        fun get(): NekoApp = instance!!

        val crashLogFile: File
            get() = File(File(get().filesDir, "runtime/logs"), "crash.log")
    }
}
