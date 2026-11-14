package io.github.masgzy.nekoport.runtime

import android.content.Context
import io.github.masgzy.nekoport.core.AppLog
import io.github.masgzy.nekoport.core.MariaConfig
import java.io.File

/**
 * Lifecycle helper for the bundled MariaDB server. Unlike the HTTP engines,
 * MariaDB needs a one-time datadir bootstrap (system tables), performed with
 * the upstream `mariadb-install-db` script shipped in the Termux package.
 */
object MariaDbController {

    private const val TAG = "MariaDb"
    private const val KEY = "maria"

    /** The currently running `mariadb-install-db` bootstrap, so [stop] can abort it. */
    @Volatile
    private var activeBootstrap: Process? = null

    fun dataDir(context: Context): File = ConfigWriter.mariaDataDir(context)

    /** True when the system tables have been bootstrapped already. */
    fun isInitialized(context: Context): Boolean =
        File(dataDir(context), "mysql").isDirectory

    /**
     * Bootstraps the data directory once. The upstream script needs a POSIX
     * shell: we prefer our own bash, then our bundled bin/sh (dash, installed
     * with the runtime since alpha.3), and only fall back to Android's
     * /system/bin/sh (mksh). Executing the script itself used to fail with
     * error=2 when neither bundled interpreter existed, because the patched
     * shebang pointed at a non-existent bin/sh. The bootstrap can take tens
     * of seconds on first run, so a hard timeout guards against hangs.
     * Must be called from a background thread.
     */
    fun initialize(context: Context): Result<Unit> {
        val prefix = TermuxRuntime.prefixDir(context)
        val script = sequenceOf("bin/mariadb-install-db", "bin/mysql_install_db")
            .map { File(prefix, it) }
            .firstOrNull { it.exists() }
            ?: return Result.failure(IllegalStateException("mariadb-install-db not found"))

        val datadir = dataDir(context).apply { mkdirs() }
        val scriptArgs = listOf(
            script.canonicalPath,
            "--datadir=${datadir.canonicalPath}",
            "--skip-name-resolve",
        )
        val cmd = when {
            File(prefix, "bin/bash").exists() ->
                listOf(File(prefix, "bin/bash").canonicalPath) + scriptArgs
            File(prefix, "bin/sh").exists() ->
                listOf(File(prefix, "bin/sh").canonicalPath) + scriptArgs
            else ->
                listOf("/system/bin/sh") + scriptArgs
        }
        AppLog.i(TAG, "bootstrapping datadir: ${cmd.joinToString(" ")}")
        return runCatching {
            val pb = ProcessBuilder(cmd)
            pb.environment().putAll(ProcessController.environment(context))
            pb.redirectErrorStream(true)
            pb.directory(TermuxRuntime.runtimeRoot(context))
            val proc = pb.start()
            activeBootstrap = proc
            // Pump output while waiting so the pipe never fills up and the
            // process never blocks on a full stdout buffer.
            val output = StringBuilder()
            val pump = Thread {
                runCatching {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(output) { output.appendLine(line) }
                    }
                }
            }.apply { isDaemon = true; start() }
            val finished = proc.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                pump.join(1000)
                throw IllegalStateException("datadir bootstrap timed out after 120 s")
            }
            pump.join(1000)
            val text = synchronized(output) { output.toString() }
            val rc = proc.exitValue()
            val logFile = File(TermuxRuntime.logsDir(context), "mariadb-init.log")
            runCatching { logFile.writeText(text.takeLast(256 * 1024)) }
            if (rc != 0) {
                throw IllegalStateException(
                    "mariadb-install-db exited with $rc: ${text.lineSequence().lastOrNull { it.isNotBlank() } ?: ""}".take(300),
                )
            }
            if (!File(datadir, "mysql").isDirectory) {
                throw IllegalStateException("datadir bootstrap produced no system tables")
            }
            AppLog.i(TAG, "datadir bootstrap complete")
        }.also { activeBootstrap = null }
    }

    /**
     * Starts mariadbd with the effective (user-editable when present) my.cnf.
     * `--defaults-file` must be the first argument. Returns null on success,
     * otherwise a readable failure reason (launch-log tail) for the UI.
     */
    fun start(context: Context, maria: MariaConfig): String? {
        ConfigWriter.writeMariaCnf(context, maria, dataDir(context))
        val cnf = ConfigWriter.effectiveConf(ConfigWriter.mariaCnfAuto(context))
        val log = File(TermuxRuntime.logsDir(context), "mariadb-launch.log")
        ProcessController.launch(
            context, KEY, ProcessController.binary(context, RuntimePackage.MARIADB.binary),
            listOf("--defaults-file=${cnf.canonicalPath}"),
            log, TermuxRuntime.runtimeRoot(context),
        )
        return if (ProcessController.awaitPort(maria.port, timeoutMs = 20000)) {
            AppLog.i(TAG, "MariaDB up on port ${maria.port}")
            // Termux initializes root@localhost with unix_socket auth; switch
            // it to the classic empty password so PHP apps can connect over
            // TCP from the same sandbox uid (KSWEB-compatible behavior).
            setRootEmptyPassword(context)
            null
        } else {
            val tail = ProcessController.logTail(log)
            AppLog.e(TAG, "MariaDB did not open port ${maria.port}" + if (tail.isBlank()) "; no launch output" else ":\n$tail")
            tail.ifBlank { null }
        }
    }

    fun stop(context: Context) {
        // Also abort an in-flight datadir bootstrap: without this, tapping
        // the switch off during the first start left "starting database..."
        // on screen until the whole 120 s bootstrap timed out.
        runCatching { activeBootstrap?.takeIf { it.isAlive }?.destroyForcibly() }
        ProcessController.stop(KEY, timeoutMs = 10000)
    }

    fun isRunning(context: Context): Boolean = ProcessController.isRunning(KEY)

    /** Flips root@localhost from unix_socket auth to an empty native password. */
    private fun setRootEmptyPassword(context: Context) {
        runCatching {
            val client = File(TermuxRuntime.prefixDir(context), "bin/mariadb")
            if (!client.exists()) return
            val pb = ProcessBuilder(
                listOf(
                    client.canonicalPath,
                    "--protocol=socket",
                    "--socket=${File(TermuxRuntime.runDir(context), "mariadb.sock").canonicalPath}",
                    "-u", "root",
                    "-e", "ALTER USER 'root'@'localhost' IDENTIFIED BY ''; FLUSH PRIVILEGES;",
                ),
            )
            pb.environment().putAll(ProcessController.environment(context))
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            if (proc.exitValue() == 0) {
                AppLog.i(TAG, "root@localhost set to empty password (localhost only)")
            } else {
                AppLog.w(TAG, "could not set root password: $out")
            }
        }.onFailure {
            AppLog.w(TAG, "root password setup skipped: ${it.message}")
        }
    }
}
