package io.github.masgzy.nekoport.runtime

import android.content.Context
import io.github.masgzy.nekoport.core.AppLog
import io.github.masgzy.nekoport.core.MariaConfig
import java.io.File

/**
 * Lifecycle helper for the bundled MariaDB server. Unlike the HTTP engines,
 * MariaDB needs a one-time datadir bootstrap (system tables), performed with
 * the upstream `mariadb-install-db` script shipped in the Termux package.
 *
 * Root authentication contract (KSWEB-compatible): PHP apps connect over TCP
 * to 127.0.0.1 with user `root` and an EMPTY password. Termux-initialized
 * datadirs instead give root@localhost the `unix_socket` plugin, whose
 * upstream implementation (plugin/auth_socket/auth_socket.c) compares the
 * connecting process's OS username (`getpwuid_r` of the peer uid) with the
 * MySQL username. On Android the app uid resolves to `u0_aXXX`, never
 * `root`, so every connection fails with ERROR 1698 - including TCP, because
 * the plugin rejects connections that carry no unix-socket peer credentials.
 * `--protocol=socket` alone (the alpha.4 attempt) cannot fix that.
 *
 * Two-layer fix:
 *  1. fresh datadirs are bootstrapped with
 *     `--auth-root-authentication-method=normal`, which creates root@localhost
 *     with mysql_native_password + empty password from the start;
 *  2. datadirs created by older builds are repaired once at start: a
 *     temporary `--skip-grant-tables --skip-networking` instance (socket-only,
 *     never exposed to the network) first INSPECTS root@localhost's auth
 *     plugin. Only the legacy unix_socket plugin is flipped back to an empty
 *     native password - a password the user set themselves (non-empty
 *     authentication_string) is respected and never touched, and recorded in
 *     a marker file so the contract probe is skipped on future starts.
 */
object MariaDbController {

    private const val TAG = "MariaDb"
    private const val KEY = "maria"
    private const val REPAIR_KEY = "maria-repair"

    /** The currently running `mariadb-install-db` bootstrap, so [stop] can abort it. */
    @Volatile
    private var activeBootstrap: Process? = null

    /** Root auth state discovered while inspecting the grant tables. */
    private enum class RootAuthState {
        /** Legacy unix_socket plugin - the ERROR-1698 datadir; safe to reset. */
        LEGACY_UNIX_SOCKET,
        /** A user-set password (non-empty authentication_string) - DO NOT touch. */
        PASSWORD_SET,
        /** Native password plugin with an empty hash - the KSWEB contract holds. */
        EMPTY_NATIVE,
        /** Unrecognized plugin / unreadable output. */
        UNKNOWN,
    }

    /** Session cache for the user-managed-password state. */
    @Volatile
    private var rootPasswordCustom = false

    /**
     * Marker file next to the MariaDB datadir, created when root's password
     * is user-managed (set by the user or detected as non-empty). Survives
     * app restarts and "uninstall components" (the datadir is kept too), so
     * startup never boots a temporary skip-grant-tables instance again just
     * to discover the same password.
     */
    private fun pwMarkerFile(context: Context): File =
        File(File(TermuxRuntime.runtimeRoot(context), "mariadb"), ".root-pw-custom")

    /** True when root's password is user-managed and must never be auto-reset. */
    fun isRootPasswordCustom(context: Context): Boolean =
        rootPasswordCustom || pwMarkerFile(context).exists()

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
        // "normal" = mysql_native_password + empty password for root@localhost,
        // instead of the 10.4+ default unix_socket plugin (ERROR 1698 on Android).
        val normalAuthArgs = listOf("--auth-root-authentication-method=normal")
        val baseArgs = listOf(
            script.canonicalPath,
            "--datadir=${datadir.canonicalPath}",
            "--skip-name-resolve",
        )
        val interpreter = when {
            File(prefix, "bin/bash").exists() -> File(prefix, "bin/bash").canonicalPath
            File(prefix, "bin/sh").exists() -> File(prefix, "bin/sh").canonicalPath
            else -> "/system/bin/sh"
        }
        AppLog.i(TAG, "bootstrapping datadir: $interpreter ${baseArgs.joinToString(" ")}")
        return runCatching {
            val first = runBootstrap(context, interpreter, baseArgs + normalAuthArgs)
            if (first.isFailure) {
                val message = first.exceptionOrNull()?.message ?: ""
                // Very old MariaDB versions do not know the flag; fall back to
                // the plain bootstrap and let the startup repair fix auth.
                val unknownOption = message.contains("auth-root-authentication-method", ignoreCase = true) &&
                    (message.contains("unknown", ignoreCase = true) || message.contains("unrecognized", ignoreCase = true))
                if (!unknownOption) {
                    first.getOrThrow()
                }
                AppLog.w(TAG, "installer rejected $normalAuthArgs; retrying without it")
                runBootstrap(context, interpreter, baseArgs).getOrThrow()
            }
        }
    }

    /** Runs mariadb-install-db once and validates the produced datadir. */
    private fun runBootstrap(context: Context, interpreter: String, scriptArgs: List<String>): Result<Unit> {
        val datadir = dataDir(context)
        val cmd = listOf(interpreter) + scriptArgs
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
    fun start(context: Context, maria: MariaConfig, localOnly: Boolean = false): String? {
        ConfigWriter.writeMariaCnf(context, maria, dataDir(context), localOnly)
        val cnf = ConfigWriter.effectiveConf(ConfigWriter.mariaCnfAuto(context))
        val log = File(TermuxRuntime.logsDir(context), "mariadb-launch.log")
        ProcessController.launch(
            context, KEY, ProcessController.binary(context, RuntimePackage.MARIADB.binary),
            listOf("--defaults-file=${cnf.canonicalPath}"),
            log, TermuxRuntime.runtimeRoot(context),
        )
        if (!ProcessController.awaitPort(maria.port, timeoutMs = 20000) || !ProcessController.isRunning(KEY)) {
            val tail = ProcessController.logTail(log)
            AppLog.e(TAG, "MariaDB did not open port ${maria.port}" + if (tail.isBlank()) "; no launch output" else ":\n$tail")
            return tail.ifBlank { null }
        }
        AppLog.i(TAG, "MariaDB up on port ${maria.port}")
        // KSWEB-compatible behavior: root@localhost reachable over TCP with an
        // empty password so mysqli/PDO from PHP (and LAN clients when allowed)
        // can log in. Repairs legacy unix_socket datadirs automatically; a
        // password the user set themselves is respected, never reset.
        ensureRootTcpEmptyPassword(context, maria, cnf)?.let { return it }
        return null
    }

    fun stop(context: Context) {
        // Also abort an in-flight datadir bootstrap: without this, tapping
        // the switch off during the first start left "starting database..."
        // on screen until the whole 120 s bootstrap timed out.
        runCatching { activeBootstrap?.takeIf { it.isAlive }?.destroyForcibly() }
        ProcessController.stop(REPAIR_KEY, timeoutMs = 5000)
        ProcessController.stop(KEY, timeoutMs = 10000)
    }

    fun isRunning(context: Context): Boolean = ProcessController.isRunning(KEY)

    /**
     * Sets (or clears, with an empty [newPassword]) the root@localhost
     * password via the bundled mariadb client. Requires the server to be
     * running; [currentPassword] may be empty when root currently has none.
     * After a successful change the user-managed marker is kept in sync so
     * startup never resets the credential.
     */
    fun setRootPassword(context: Context, maria: MariaConfig, currentPassword: String, newPassword: String): Result<Unit> {
        if (newPassword.length > 64) {
            return Result.failure(IllegalArgumentException("password too long (max 64)"))
        }
        if (!ProcessController.isRunning(KEY)) {
            return Result.failure(IllegalStateException("MariaDB is not running"))
        }
        val client = File(TermuxRuntime.prefixDir(context), "bin/mariadb")
        if (!client.exists()) {
            return Result.failure(IllegalStateException("mariadb client not installed"))
        }
        // SQL string literal escaping for the new password.
        val escaped = newPassword.replace("\\", "\\\\").replace("'", "\\'")
        val sql = "ALTER USER 'root'@'localhost' IDENTIFIED BY '$escaped'; FLUSH PRIVILEGES;"
        val args = mutableListOf(
            "--protocol=tcp", "-h", "127.0.0.1", "-P", maria.port.toString(), "-u", "root",
        )
        args.addAll(listOf("-e", sql))
        // The password travels via the MYSQL_PWD environment variable, never
        // via argv: every process of the app uid (php-fpm pools, cron shells,
        // a compromised WordPress) can read /proc/<pid>/cmdline of its
        // siblings, and "-p<secret>" there would hand out the root password
        // to exactly the code open_basedir is supposed to contain.
        val (out, rc) = runClient(context, client, args, env = mapOf("MYSQL_PWD" to currentPassword))
        return if (rc != 0) {
            val reason = out.trim().take(200).ifEmpty { "mariadb client rc=$rc" }
            Result.failure(IllegalStateException(reason))
        } else {
            rootPasswordCustom = newPassword.isNotEmpty()
            runCatching {
                val marker = pwMarkerFile(context)
                marker.parentFile?.mkdirs()
                if (newPassword.isEmpty()) marker.delete() else marker.writeText("root password set via NekoPort\n")
            }
            AppLog.i(TAG, if (newPassword.isEmpty()) "root password cleared" else "root password updated")
            Result.success(Unit)
        }
    }

    /** null = the empty-password TCP contract holds (or root is user-managed); otherwise the failure reason. */
    private fun ensureRootTcpEmptyPassword(context: Context, maria: MariaConfig, cnf: File): String? {
        // A user-managed password means the empty-password probe would fail
        // forever; never inspect/reset it again (marker survives restarts).
        if (isRootPasswordCustom(context)) {
            rootPasswordCustom = true
            AppLog.i(TAG, "root password is user-managed; skipping empty-password contract check")
            return null
        }
        val (probe, probeRc) = rootTcpLogin(context, maria.port)
        if (probeRc == 0) {
            AppLog.i(TAG, "root@localhost accepts TCP logins with an empty password")
            return null
        }
        AppLog.w(TAG, "TCP root probe failed rc=$probeRc: ${probe.trim().take(200)}; inspecting auth plugin")
        val state = repairRootAuth(context, maria, cnf).getOrElse {
            return "root auth check failed: ${it.message}"
        }
        when (state) {
            RootAuthState.PASSWORD_SET -> {
                // The user set a root password themselves. Remember it and
                // never reset it (previously every start silently cleared it).
                rootPasswordCustom = true
                runCatching {
                    pwMarkerFile(context).parentFile?.mkdirs()
                    pwMarkerFile(context).writeText("root password set by user/external client\n")
                }
                AppLog.i(TAG, "root@localhost has a user-set password; left untouched")
                return null
            }
            RootAuthState.EMPTY_NATIVE -> {
                // Grant tables say empty native password: the contract holds
                // and the TCP probe hit a transient issue. Do not "repair".
                AppLog.i(TAG, "root@localhost is mysql_native_password with an empty password; no repair needed")
                return null
            }
            RootAuthState.UNKNOWN ->
                return "root authentication state unknown (see app log)"
            RootAuthState.LEGACY_UNIX_SOCKET -> {
                // ALTER USER already applied inside repairRootAuth; fall
                // through to the verification probe below.
            }
        }
        val (out, rc) = rootTcpLogin(context, maria.port)
        return if (rc == 0) {
            AppLog.i(TAG, "root@localhost now accepts TCP logins with an empty password")
            null
        } else {
            AppLog.e(TAG, "root auth repair did not take effect: ${out.trim().take(200)}")
            "root authentication repair did not take effect (see app log)"
        }
    }

    /**
     * Boots a temporary skip-grant-tables instance and inspects root@localhost:
     *  - unix_socket plugin       -> flip to mysql_native_password + empty
     *    password (the legacy datadir repair), returns LEGACY_UNIX_SOCKET;
     *  - non-empty password hash  -> untouched, returns PASSWORD_SET;
     *  - empty native password    -> untouched, returns EMPTY_NATIVE.
     *
     * The temporary server is socket-only (`--skip-networking`), so the
     * unauthenticated window never touches the network; it listens on a
     * separate socket in the app-private run directory. The normal instance
     * is relaunched afterwards regardless of the outcome.
     */
    private fun repairRootAuth(context: Context, maria: MariaConfig, cnf: File): Result<RootAuthState> {
        AppLog.w(TAG, "inspecting root@localhost auth via temporary skip-grant-tables instance")
        // The normal instance holds the datadir (InnoDB lock); stop it first.
        ProcessController.stop(KEY, timeoutMs = 15000)
        val repairSock = File(TermuxRuntime.runDir(context), "mariadb-repair.sock")
        runCatching { repairSock.delete() }
        val outcome = runCatching {
            ProcessController.launch(
                context, REPAIR_KEY, ProcessController.binary(context, RuntimePackage.MARIADB.binary),
                listOf(
                    "--defaults-file=${cnf.canonicalPath}",
                    "--skip-grant-tables",
                    "--skip-networking",
                    "--socket=${repairSock.canonicalPath}",
                ),
                File(TermuxRuntime.logsDir(context), "mariadb-repair.log"),
                TermuxRuntime.runtimeRoot(context),
            )
            val deadline = System.currentTimeMillis() + 20000
            while (!repairSock.exists() && System.currentTimeMillis() < deadline) Thread.sleep(200)
            if (!repairSock.exists()) {
                val tail = ProcessController.logTail(File(TermuxRuntime.logsDir(context), "mariadb-repair.log"))
                throw IllegalStateException("repair instance did not create its socket${if (tail.isBlank()) "" else ": $tail"}")
            }
            val client = File(TermuxRuntime.prefixDir(context), "bin/mariadb")
            // -N: skip column names, -B: tab-separated. With
            // --skip-grant-tables any username is accepted.
            val (qOut, qRc) = runClient(
                context, client,
                listOf(
                    "--protocol=socket", "--socket=${repairSock.canonicalPath}", "-u", "root", "-N", "-B", "-e",
                    "SELECT plugin, IF(authentication_string IS NULL OR authentication_string='', 'empty', 'set') " +
                        "FROM mysql.user WHERE user='root' AND host='localhost';",
                ),
            )
            if (qRc != 0) {
                throw IllegalStateException("auth inspection failed rc=$qRc: ${qOut.trim().take(200)}")
            }
            val line = qOut.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: ""
            val plugin = line.substringBefore('\t').trim().lowercase(java.util.Locale.US)
            val hashState = line.substringAfter('\t', "").trim().lowercase(java.util.Locale.US)
            AppLog.i(TAG, "root@localhost plugin='$plugin' password=$hashState")
            val state = when {
                plugin.contains("socket") -> RootAuthState.LEGACY_UNIX_SOCKET
                hashState == "set" -> RootAuthState.PASSWORD_SET
                plugin.contains("native") && hashState == "empty" -> RootAuthState.EMPTY_NATIVE
                else -> RootAuthState.UNKNOWN
            }
            if (state == RootAuthState.LEGACY_UNIX_SOCKET) {
                val (out, rc) = runClient(
                    context, client,
                    listOf(
                        "--protocol=socket", "--socket=${repairSock.canonicalPath}", "-u", "root", "-e",
                        // skip-grant-tables refuses ALTER USER until the grant
                        // tables are reloaded; FLUSH PRIVILEGES re-enables it.
                        "FLUSH PRIVILEGES; " +
                            "ALTER USER 'root'@'localhost' IDENTIFIED VIA mysql_native_password USING PASSWORD(''); " +
                            "FLUSH PRIVILEGES;",
                    ),
                )
                if (rc != 0) {
                    throw IllegalStateException("ALTER USER failed rc=$rc: ${out.trim().take(200)}")
                }
                AppLog.i(TAG, "root@localhost switched from unix_socket to mysql_native_password (empty password)")
            }
            state
        }
        // Tear the temporary instance down and bring the real one back,
        // whatever the inspection outcome was.
        ProcessController.stop(REPAIR_KEY, timeoutMs = 10000)
        runCatching { repairSock.delete() }
        val log = File(TermuxRuntime.logsDir(context), "mariadb-launch.log")
        ProcessController.launch(
            context, KEY, ProcessController.binary(context, RuntimePackage.MARIADB.binary),
            listOf("--defaults-file=${cnf.canonicalPath}"),
            log, TermuxRuntime.runtimeRoot(context),
        )
        if (!ProcessController.awaitPort(maria.port, timeoutMs = 20000)) {
            AppLog.e(TAG, "MariaDB did not come back after the auth inspection")
        }
        return outcome
    }

    /** Probes a TCP login as root with no password. Returns (output, exitCode). */
    private fun rootTcpLogin(context: Context, port: Int): Pair<String, Int> {
        val client = File(TermuxRuntime.prefixDir(context), "bin/mariadb")
        if (!client.exists()) return "mariadb client not installed" to -1
        return runClient(
            context, client,
            listOf("--protocol=tcp", "-h", "127.0.0.1", "-P", port.toString(), "-u", "root", "-e", "SELECT 1;"),
        )
    }

    /** Runs the bundled mariadb client with a hard timeout. Returns (output, exitCode). */
    private fun runClient(context: Context, client: File, args: List<String>, env: Map<String, String> = emptyMap()): Pair<String, Int> {
        val pb = ProcessBuilder(listOf(client.canonicalPath) + args)
        pb.environment().putAll(ProcessController.environment(context))
        // Extra variables (e.g. MYSQL_PWD) never land in argv, so sibling
        // processes of the same uid cannot scrape them from /proc.
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)
        return runCatching {
            val proc = pb.start()
            val out = StringBuilder()
            val pump = Thread {
                runCatching {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(out) { out.appendLine(line) }
                    }
                }
            }.apply { isDaemon = true; start() }
            if (!proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                pump.join(1000)
                synchronized(out) { out.appendLine("[nekoport] mariadb client timed out") }
                return synchronized(out) { out.toString() } to -1
            }
            pump.join(1000)
            synchronized(out) { out.toString() } to proc.exitValue()
        }.getOrElse { (it.message ?: "spawn failed") to -1 }
    }
}
