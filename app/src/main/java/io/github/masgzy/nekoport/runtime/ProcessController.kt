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

    /** Launch-log size cap within one process session (a runaway engine used
     *  to be able to write GBs into runtime/logs until the disk filled up). */
    private const val LAUNCH_LOG_CAP_BYTES = 8L * 1024 * 1024

    /** Binaries whose orphaned instances may be reaped at app start. */
    private val SERVER_BINARIES = setOf("mariadbd", "php-fpm", "nginx", "lighttpd", "httpd", "pure-ftpd")

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
        // A per-session byte cap stops a chatty/crash-looping engine from
        // growing the log without bound (the >4 MB trim above only runs on
        // the NEXT launch).
        Thread({
            try {
                var written = 0L
                process.inputStream.bufferedReader().useLines { lines ->
                    logFile.appendText("") // touch
                    logFile.outputStream().use { out ->
                        lines.forEach { line ->
                            if (written > LAUNCH_LOG_CAP_BYTES) return@useLines
                            val bytes = (line + "\n").toByteArray()
                            out.write(bytes)
                            written += bytes.size
                            out.flush()
                            if (written > LAUNCH_LOG_CAP_BYTES) {
                                out.write("\n[nekoport] log truncated: launch output exceeded ${LAUNCH_LOG_CAP_BYTES / (1024 * 1024)} MB in this session\n".toByteArray())
                                out.flush()
                            }
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

    /**
     * Kills server processes orphaned by a previous app death (LMK kill,
     * crash). The daemons are plain ProcessBuilder children without
     * PDEATHSIG, so they survive the app being killed: the next start then
     * found "Address already in use" while the UI claimed everything was
     * stopped (the process table is in-memory only).
     *
     * Matches only processes whose argv[0] lives inside the NekoPort runtime
     * tree AND whose binary name is a known server; /proc entries of other
     * uids are unreadable and therefore skipped. Must run once at app process
     * start, before anything is launched (the in-memory process table is
     * empty then, so tracked live processes cannot be killed by accident).
     */
    fun reapOrphanProcesses(context: Context): Int {
        val runtimePath = runCatching { TermuxRuntime.runtimeRoot(context).canonicalPath }.getOrDefault("")
        if (runtimePath.isEmpty()) return 0
        val runtimePrefix = "$runtimePath/"
        val myPid = android.os.Process.myPid()
        var killed = 0
        val pids = File("/proc").listFiles()?.filter { it.name.toIntOrNull() != null } ?: return 0
        for (entry in pids) {
            val pid = entry.name.toIntOrNull() ?: continue
            if (pid == myPid) continue
            val argv0 = runCatching {
                java.io.FileInputStream(File(entry, "cmdline")).use { input ->
                    val bytes = input.readBytes().toString(Charsets.UTF_8)
                    bytes.substringBefore('\u0000').trim()
                }
            }.getOrElse { continue } // unreadable: other uid or already gone
            if (argv0.isEmpty() || !argv0.startsWith(runtimePrefix)) continue
            if (argv0.substringAfterLast('/') !in SERVER_BINARIES) continue
            // killProcess returns void, not a Boolean: success means "did not
            // throw" (kill(2) failures surface as SecurityException etc.).
            if (runCatching { android.os.Process.killProcess(pid) }.isSuccess) {
                killed++
                AppLog.w(TAG, "reaped orphan server process pid=$pid (${argv0.substringAfterLast('/')})")
            }
        }
        // Stale pid files (nginx/php-fpm/mariadbd rewrite theirs on start;
        // lighttpd's server.pid-file would keep pointing at a dead pid).
        runCatching {
            TermuxRuntime.runDir(context).listFiles { f -> f.name.endsWith(".pid") }?.forEach { pf ->
                val dead = pf.readText().trim().toIntOrNull()
                    ?.let { it != myPid && !File("/proc/$it").exists() } ?: true
                if (dead) pf.delete()
            }
        }
        if (killed > 0) AppLog.i(TAG, "orphan reaping done: $killed process(es) killed")
        return killed
    }
}
