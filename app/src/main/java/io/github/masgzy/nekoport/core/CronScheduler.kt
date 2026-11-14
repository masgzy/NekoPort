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
                    scope.launch {
                        try {
                            execute(task)
                        } finally {
                            running.remove(task.id)
                        }
                    }
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
                AppLog.i(TAG, "task '${task.name}': restarting all services")
                manager.stopAll()
                manager.startAll()
            }
            CronAction.KEEP_ALIVE -> {
                val anyRunning = manager.state.value.running || manager.state.value.mariaRunning
                if (!anyRunning && !manager.userStoppedServices) {
                    AppLog.i(TAG, "task '${task.name}': services down, starting")
                    manager.startAll()
                } else if (!anyRunning) {
                    // The user turned the master switch off; a watchdog that
                    // yanks everything back up within 15 s makes the switch
                    // look broken. Stay quiet until the user starts again.
                    AppLog.i(TAG, "task '${task.name}': services down but user stopped them; skipping")
                }
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
                        synchronized(out) { out.appendLine(line) }
                    }
                }
            }.apply { isDaemon = true; start() }
            val finished = proc.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
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
        val log = ConfigWriter.cronLog(context)
        if (!manager.config.value.cron.logEnabled) return
        runCatching {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            log.parentFile?.mkdirs()
            if (log.length() > 2 * 1024 * 1024) log.delete()
            log.appendText("[$stamp] task=${task.name} expr=${task.expr} rc=${if (ok) 0 else 1} ${elapsedMs}ms\n")
            if (!output.isNullOrBlank()) {
                log.appendText(output.take(8 * 1024) + "\n")
            }
        }
    }

    companion object {
        private const val TAG = "Cron"
    }
}
