package io.github.masgzy.nekoport.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel { INFO, ACCESS, WARN, ERROR }

data class LogEntry(
    val time: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val tag: String,
    val message: String,
) {
    fun format(): String {
        val ts = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(time))
        return "$ts [${level.name}] $tag: $message"
    }
}

/**
 * Ring buffer of application logs, observable by the UI and additionally
 * appended to `runtime/logs/app.log` so history survives process restarts.
 * Emission is O(1): a [MutableSharedFlow] with overflow drop-oldest replaces
 * the previous full-list copy per log line.
 */
object AppLog {

    private const val MAX_ENTRIES = 2000
    private val allEntries = ArrayList<LogEntry>(MAX_ENTRIES)
    private val _flow = MutableSharedFlow<List<LogEntry>>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val flow: SharedFlow<List<LogEntry>> = _flow

    private val writerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var logFile: File? = null

    /** Wall clock of the last [clear]; queued disk writes older than this are dropped. */
    @Volatile
    private var clearedAt = 0L

    var accessLogEnabled: Boolean = true

    /** Wires disk persistence; called once from [NekoApp.onCreate]. */
    fun attach(context: Context) {
        logFile = File(File(context.filesDir, "runtime/logs"), "app.log")
    }

    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String) = log(LogLevel.WARN, tag, message)
    fun e(tag: String, message: String) = log(LogLevel.ERROR, tag, message)

    fun access(tag: String, message: String) {
        if (accessLogEnabled) log(LogLevel.ACCESS, tag, message)
    }

    @Synchronized
    private fun log(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(level = level, tag = tag, message = message)
        allEntries.add(entry)
        if (allEntries.size > MAX_ENTRIES) {
            allEntries.subList(0, allEntries.size - MAX_ENTRIES).clear()
        }
        _flow.tryEmit(allEntries.toList())
        val file = logFile
        if (file != null) {
            writerScope.launch { appendToDisk(file, entry) }
        }
        when (level) {
            LogLevel.ERROR -> Log.e(tag, message)
            LogLevel.WARN -> Log.w(tag, message)
            else -> Log.i(tag, message)
        }
    }

    private fun appendToDisk(file: File, entry: LogEntry) {
        runCatching {
            // The entry may have been queued before a clear(); writing it
            // after the delete would resurrect the log file and make the
            // "clear logs" button look broken after an app restart.
            if (entry.time <= clearedAt) return
            file.parentFile?.mkdirs()
            if (file.length() > 2 * 1024 * 1024) file.delete()
            file.appendText(entry.format() + "\n")
        }
    }

    @Synchronized
    fun clear() {
        allEntries.clear()
        clearedAt = System.currentTimeMillis()
        _flow.tryEmit(emptyList())
        runCatching { logFile?.delete() }
    }

    /** Reads the persisted history (used to seed the UI after a restart). */
    @Synchronized
    fun loadPersisted(): List<LogEntry> {
        val file = logFile ?: return emptyList()
        return runCatching {
            if (!file.exists()) return emptyList()
            file.readLines().takeLast(MAX_ENTRIES).mapNotNull { line -> parseLine(line) }
        }.getOrDefault(emptyList())
    }

    private fun parseLine(line: String): LogEntry? {
        // Format: MM-dd HH:mm:ss.SSS [LEVEL] tag: message
        val m = Regex("""^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) \[(\w+)\] (\S+?): (.*)$""").find(line)
            ?: return null
        val (ts, level, tag, message) = m.destructured
        val time = runCatching {
            SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
                .parse(ts)?.time
        }.getOrNull() ?: return null
        val lvl = runCatching { LogLevel.valueOf(level) }.getOrNull() ?: return null
        return LogEntry(time = time, level = lvl, tag = tag, message = message)
    }
}
