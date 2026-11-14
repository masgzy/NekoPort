package io.github.masgzy.nekoport.runtime

import android.content.Context
import io.github.masgzy.nekoport.core.AppLog
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns every external server process (nginx / httpd / lighttpd / php-fpm /
 * pure-ftpd). Each process writes its combined stdout+stderr into a log file
 * under `runtime/logs/`; a watchdog thread appends the output so crashes stay
 * diagnosable.
 */
object ProcessController {

    private const val TAG = "Proc"

    private val processes = ConcurrentHashMap<String, Process>()
    private val stopping = AtomicBoolean(false)

    /** Environment shared by every bundled binary (Termux layout). */
    fun environment(context: Context): Map<String, String> {
        val prefix = TermuxRuntime.prefixDir(context)
        val root = TermuxRuntime.runtimeRoot(context)
        val home = File(root, "home").apply { mkdirs() }
        val tmp = TermuxRuntime.tmpDir(context)
        val bin = File(prefix, "bin")
        return mapOf(
            "PATH" to "${bin.canonicalPath}:${System.getenv("PATH") ?: "/system/bin"}",
            "LD_LIBRARY_PATH" to File(prefix, "lib").canonicalPath,
            "HOME" to home.canonicalPath,
            "TMPDIR" to tmp.canonicalPath,
            "TMP" to tmp.canonicalPath,
            "TEMP" to tmp.canonicalPath,
            "LANG" to "C.UTF-8",
            "PHPRC" to TermuxRuntime.etcDir(context).canonicalPath,
        )
    }

    fun binary(context: Context, name: String): File {
        val exe = File(TermuxRuntime.prefixDir(context), "bin/$name")
        if (!exe.exists()) {
            throw IllegalStateException("binary '$name' not installed")
        }
        exe.setExecutable(true, false)
        return exe
    }

    /**
     * Launches a long-running server process in the foreground.
     * The caller owns the lifecycle via [stop] / [isRunning].
     */
    fun launch(
        context: Context,
        key: String,
        executable: File,
        args: List<String>,
        logFile: File,
        cwd: File,
    ): Process {
        stop(key)
        val env = environment(context)
        val pb = ProcessBuilder(listOf(executable.canonicalPath) + args)
        pb.environment().putAll(env)
        pb.directory(cwd.apply { mkdirs() })
        pb.redirectErrorStream(true)
        logFile.parentFile?.mkdirs()
        if (logFile.length() > 4 * 1024 * 1024) logFile.delete()
        val process = pb.start()
        processes[key] = process

        // Pump the process output into its log file (keeps pipes from filling).
        Thread({
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    logFile.appendText("") // touch
                    logFile.outputStream().use { out ->
                        lines.forEach { line ->
                            out.write((line + "\n").toByteArray())
                            out.flush()
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }, "nekoport-log-$key").apply { isDaemon = true }.start()

        AppLog.i(TAG, "started [$key] exe=${executable.name} args=${args.joinToString(" ")}")
        return process
    }

    fun isRunning(key: String): Boolean {
        val p = processes[key] ?: return false
        return runCatching { p.isAlive }.getOrDefault(false)
    }

    /** Terminates a process gracefully (SIGTERM), then forcibly if needed. */
    fun stop(key: String, timeoutMs: Long = 8000) {
        val p = processes.remove(key) ?: return
        if (!runCatching { p.isAlive }.getOrDefault(false)) return
        runCatching { p.destroy() }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (runCatching { p.isAlive }.getOrDefault(false) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        if (runCatching { p.isAlive }.getOrDefault(false)) {
            AppLog.w(TAG, "[$key] did not exit in time, killing")
            runCatching { p.destroyForcibly() }
        }
        AppLog.i(TAG, "stopped [$key]")
    }

    fun stopAll() {
        if (!stopping.compareAndSet(false, true)) return
        try {
            for (key in processes.keys.toList()) stop(key)
        } finally {
            stopping.set(false)
        }
    }

    fun runningKeys(): Set<String> = processes.keys.toSet()

    /** Last lines of [logFile], used to surface the real failure reason
     *  (linker errors, config errors, port conflicts) in the UI instead of
     *  a bare "did not open port". */
    fun logTail(logFile: File, maxLines: Int = 6, maxChars: Int = 400): String =
        runCatching {
            if (!logFile.exists()) return@runCatching ""
            logFile.readLines()
                .filter { it.isNotBlank() }
                .takeLast(maxLines)
                .joinToString("\n")
                .take(maxChars)
        }.getOrDefault("")

    /** Blocks until a local TCP port accepts connections, or returns false. */
    fun awaitPort(port: Int, timeoutMs: Long = 10000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            runCatching {
                Socket().use { s ->
                    s.connect(InetSocketAddress("127.0.0.1", port), 500)
                }
                return true
            }
            Thread.sleep(200)
        }
        return false
    }
}
