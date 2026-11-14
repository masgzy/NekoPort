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
        AppLog.i("NekoApp", "NekoPort started")
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
