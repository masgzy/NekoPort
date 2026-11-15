package io.github.masgzy.nekoport.core

import android.content.Context
import io.github.masgzy.nekoport.runtime.ConfigWriter
import io.github.masgzy.nekoport.runtime.ProcessController
import io.github.masgzy.nekoport.runtime.TermuxRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * In-app cron scheduler. Tasks are defined with standard 5-field cron
 * expressions and are evaluated while the NekoPort process is alive; runs
 * missed while the app was not running are skipped.
 *
 * Task kinds mirror KSWEB: run a console command, restart all services, or
 * keep the services running (watchdog).
 */
class CronScheduler(
    private val context: Context,
    private val manager: ServerManager,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Cached next-fire time per task id, keyed together with the expression
     *  it was computed from - re-arming a task with a new expression used to
     *  keep the OLD schedule until the stale time passed. */
    private val nextFire = ConcurrentHashMap<Long, Pair<String, Long>>()
    private val running = ConcurrentHashMap<Long, Boolean>()
    /** Set while a "missed run" warning has been logged for a still-running task (logged once per run). */
    private val missedLogged = ConcurrentHashMap<Long, Boolean>()

    /**
     * Single-threaded writer for the cron log: concurrent tasks used to
     * interleave half-written lines and race the delete() rotation.
     */
    private val logExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "nekoport-cron-log").apply { isDaemon = true }
    }

    /** (Re)arms the scheduler according to [config]. */
    @Synchronized
    fun apply(config: CronConfig) {
        val shouldRun = config.enabled
        if (shouldRun && (job == null || job?.isActive != true)) {
            nextFire.clear()
            job = scope.launch { scope.loop() }
            AppLog.i(TAG, "scheduler started with ${config.tasks.size} task(s)")
        } else if (!shouldRun && job != null) {
            job?.cancel()
            job = null
            AppLog.i(TAG, "scheduler stopped")
        }
    }

    fun isRunning(): Boolean = job?.isActive == true

    private suspend fun CoroutineScope.loop() {
        while (isActive) {
            try {
                tick()
            } catch (e: Exception) {
                AppLog.e(TAG, "tick failed: ${e.message}")
            }
            delay(15000)
        }
    }

    private suspend fun tick() {
        val config = manager.config.value.cron
        if (!config.enabled) return
        val now = System.currentTimeMillis()
        for (task in config.tasks) {
            if (!task.enabled) continue
            val cached = nextFire[task.id]
            val next = if (cached != null && cached.first == task.expr) {
                cached.second
            } else {
                // Recompute strictly after `now`: a just-created task whose
                // expression matches the current minute fires at its NEXT
                // occurrence, not immediately (the old now-60_000 base made
                // it execute once right away).
                val fresh = CronParser.nextFire(task.expr, now) ?: continue
                nextFire[task.id] = task.expr to fresh
                fresh
            }
            if (now >= next) {
                // Avoid double-firing on rapid ticks: schedule strictly after `now`.
                nextFire[task.id] = task.expr to (CronParser.nextFire(task.expr, now) ?: Long.MAX_VALUE)
                if (running.putIfAbsent(task.id, true) == null) {
                    missedLogged.remove(task.id)
                    scope.launch {
                        try {
                            execute(task)
                        } finally {
                            running.remove(task.id)
                        }
                    }
                } else if (missedLogged.putIfAbsent(task.id, true) == null) {
                    // A `*/1 * * * *` task whose previous run is still going
                    // used to skip every trigger silently; leave one trace.
                    AppLog.w(TAG, "task '${task.name}' skipped a trigger (previous run still active)")
                }
            }
        }
    }

    private suspend fun execute(task: CronTask) {
        val start = System.currentTimeMillis()
        var output: String? = null
        var ok = true
        when (task.action) {
            CronAction.RESTART_SERVICES -> {
                if (manager.userStoppedServices) {
                    // The user pressed the master switch off; a scheduled
                    // restart must not silently undo it (it used to yank
                    // everything back up). Stay stopped and say so.
                    AppLog.i(TAG, "task '${task.name}': restart skipped, user stopped the services")
                } else {
                    AppLog.i(TAG, "task '${task.name}': restarting all services")
                    manager.stopAll()
                    manager.startAll()
                }
            }
            CronAction.KEEP_ALIVE -> {
                // Per-component health check: restarts only what is enabled
                // and down (crashed single site, dead php-fpm behind alive
                // static sites), and respects userStoppedServices / per-site
                // stops inside the manager.
                manager.keepAliveTick()
            }
            CronAction.COMMAND -> {
                if (task.command.isBlank()) {
                    ok = false
                    output = "empty command"
                } else {
                    val result = runCommand(task.command)
                    ok = result.exitCode == 0
                    output = result.output
                }
            }
        }
        val elapsed = System.currentTimeMillis() - start
        AppLog.i(
            TAG,
            "task '${task.name}' finished rc=${if (ok) 0 else 1} in ${elapsed}ms",
        )
        if (task.logTask || task.logOutput) {
            writeLog(task, ok, elapsed, output?.takeIf { task.logOutput })
        }
    }

    data class CommandResult(val exitCode: Int, val output: String)

    /** Shell commands run inside the runtime environment when it is installed. */
    fun runCommand(command: String): CommandResult {
        return runCatching {
            val pb = ProcessBuilder(listOf("/system/bin/sh", "-c", command))
            pb.environment().putAll(ProcessController.environment(context))
            pb.redirectErrorStream(true)
            pb.directory(TermuxRuntime.runtimeRoot(context))
            val proc = pb.start()
            // Pump the whole output on a side thread: closing the stream after
            // N lines (the old take(200)) SIGPIPE-killed verbose commands with
            // rc=141 and left the "task failed" symptom unexplained.
            val out = StringBuilder()
            val pump = Thread {
                runCatching {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        synchronized(out) {
                            out.appendLine(line)
                            // Bound the memory IN the pump: keeping the whole
                            // output and truncating afterwards still OOMs on
                            // a `logcat`-style command. Head+tail are kept,
                            // the middle is replaced by an omission marker.
                            if (out.length > MAX_OUTPUT_BYTES) {
                                val head = out.substring(0, OUTPUT_KEEP_BYTES)
                                val tail = out.substring(out.length - OUTPUT_KEEP_BYTES)
                                val omitted = out.length - OUTPUT_KEEP_BYTES * 2
                                out.setLength(0)
                                out.append(head)
                                    .append("\n[nekoport] ... ").append(omitted)
                                    .append(" chars omitted ...\n")
                                    .append(tail)
                            }
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            val finished = proc.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                // destroyForcibly() only SIGKILLs the `sh` itself; the real
                // workers it spawned (mysqldump, rsync, `sleep 3600 &`)
                // survived and piled up across runs. Kill the whole tree.
                // NOTE: proc.pid()/toHandle() are API 26+, but the Kotlin
                // toolchain resolves java.lang.Process against JDK-8-era
                // signatures (inputReader/pid/toHandle all fail to compile),
                // so the pid is fetched reflectively; ProcessHandle itself
                // resolves fine and is available since minSdk 26.
                runCatching {
                    val pid = java.lang.Process::class.java
                        .getMethod("pid").invoke(proc) as? Long
                    if (pid != null) {
                        java.lang.ProcessHandle.of(pid).ifPresent { root ->
                            root.descendants().forEach { it.destroyForcibly() }
                            root.destroyForcibly()
                        }
                    }
                }
                pump.join(1000)
                synchronized(out) { out.appendLine("[nekoport] command timed out after 120 s") }
                CommandResult(-1, synchronized(out) { out.toString().trim() })
            } else {
                pump.join(1000)
                CommandResult(proc.exitValue(), synchronized(out) { out.toString().trim() })
            }
        }.getOrElse { CommandResult(-1, it.message ?: "spawn failed") }
    }

    private fun writeLog(task: CronTask, ok: Boolean, elapsedMs: Long, output: String?) {
        if (!manager.config.value.cron.logEnabled) return
        // Snapshot everything on the caller thread, then serialize the disk
        // write through a single executor: concurrent tasks used to
        // interleave half lines and race the rotation between the two
        // appendText calls.
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val header = "[$stamp] task=${task.name} expr=${task.expr} rc=${if (ok) 0 else 1} ${elapsedMs}ms\n"
        val body = output?.takeIf { it.isNotBlank() }?.let { it.take(8 * 1024) + "\n" } ?: ""
        logExecutor.execute {
            runCatching {
                val log = ConfigWriter.cronLog(context)
                log.parentFile?.mkdirs()
                if (log.length() > 2 * 1024 * 1024) {
                    // rotate: keep the old content as .1 instead of deleting
                    // it between an append of another task
                    runCatching {
                        val rotated = File(log.parentFile, log.name + ".1")
                        rotated.delete()
                        if (!log.renameTo(rotated)) log.delete()
                    }.onFailure { log.delete() }
                }
                log.appendText(header + body)
            }
        }
    }

    companion object {
        private const val TAG = "Cron"

        /**
         * Output cap for scheduled commands. The pump used to append EVERY
         * line of stdout into an unbounded StringBuilder - one `logcat` or
         * backup command could eat hundreds of MB of heap and OOM-kill the
         * whole app (including the running servers). Beyond MAX_OUTPUT_BYTES
         * the middle is replaced by an omission marker; head and tail kept.
         */
        private const val MAX_OUTPUT_BYTES = 512 * 1024
        private const val OUTPUT_KEEP_BYTES = 128 * 1024
    }
}
