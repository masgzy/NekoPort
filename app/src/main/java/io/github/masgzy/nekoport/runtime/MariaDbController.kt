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

    fun dataDir(context: Context): File = ConfigWriter.mariaDataDir(context)

    /** True when the system tables have been bootstrapped already. */
    fun isInitialized(context: Context): Boolean =
        File(dataDir(context), "mysql").isDirectory

    /**
     * Bootstraps the data directory once. The install-db script is executed
     * through `/system/bin/sh` because Termux ships it with a Termux-style
     * shebang; its baked-in prefix is rewritten at install time.
     */
    fun initialize(context: Context): Result<Unit> {
        val prefix = TermuxRuntime.prefixDir(context)
        val script = sequenceOf("bin/mariadb-install-db", "bin/mysql_install_db")
            .map { File(prefix, it) }
            .firstOrNull { it.exists() }
            ?: return Result.failure(IllegalStateException("mariadb-install-db not found"))

        val datadir = dataDir(context).apply { mkdirs() }
        val cmd = listOf(
            "/system/bin/sh",
            script.canonicalPath,
            "--datadir=${datadir.canonicalPath}",
            "--skip-name-resolve",
        )
        AppLog.i(TAG, "bootstrapping datadir: ${cmd.joinToString(" ")}")
        return runCatching {
            val pb = ProcessBuilder(cmd)
            pb.environment().putAll(ProcessController.environment(context))
            pb.redirectErrorStream(true)
            pb.directory(TermuxRuntime.runtimeRoot(context))
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            val rc = proc.waitFor()
            val logFile = File(TermuxRuntime.logsDir(context), "mariadb-init.log")
            runCatching { logFile.writeText(output.takeLast(256 * 1024)) }
            if (rc != 0) {
                throw IllegalStateException("mariadb-install-db exited with $rc: ${output.lineSequence().lastOrNull() ?: ""}")
            }
            if (!File(datadir, "mysql").isDirectory) {
                throw IllegalStateException("datadir bootstrap produced no system tables")
            }
            AppLog.i(TAG, "datadir bootstrap complete")
        }
    }

    /**
     * Starts mariadbd with the effective (user-editable when present) my.cnf.
     * `--defaults-file` must be the first argument.
     */
    fun start(context: Context, maria: MariaConfig) {
        ConfigWriter.writeMariaCnf(context, maria, dataDir(context))
        val cnf = ConfigWriter.effectiveConf(ConfigWriter.mariaCnfAuto(context))
        val log = File(TermuxRuntime.logsDir(context), "mariadb-launch.log")
        ProcessController.launch(
            context, KEY, ProcessController.binary(context, RuntimePackage.MARIADB.binary),
            listOf("--defaults-file=${cnf.canonicalPath}"),
            log, TermuxRuntime.runtimeRoot(context),
        )
        if (ProcessController.awaitPort(maria.port, timeoutMs = 20000)) {
            AppLog.i(TAG, "MariaDB up on port ${maria.port}")
            // Termux initializes root@localhost with unix_socket auth; switch
            // it to the classic empty password so PHP apps can connect over
            // TCP from the same sandbox uid (KSWEB-compatible behavior).
            setRootEmptyPassword(context)
        } else {
            AppLog.e(TAG, "MariaDB did not open port ${maria.port}; see logs")
        }
    }

    fun stop(context: Context) {
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
                    client.canonicalPath, "-u", "root",
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
