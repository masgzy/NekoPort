package io.github.masgzy.nekoport.core

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 * In-memory ring buffer of server logs, observable by the UI.
 */
object AppLog {

    private const val MAX_ENTRIES = 3000
    private val entries = ArrayDeque<LogEntry>(MAX_ENTRIES)
    private val _flow = MutableStateFlow<List<LogEntry>>(emptyList())
    val flow: StateFlow<List<LogEntry>> = _flow

    var accessLogEnabled: Boolean = true

    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String) = log(LogLevel.WARN, tag, message)
    fun e(tag: String, message: String) = log(LogLevel.ERROR, tag, message)

    fun access(tag: String, message: String) {
        if (accessLogEnabled) log(LogLevel.ACCESS, tag, message)
    }

    @Synchronized
    private fun log(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(level = level, tag = tag, message = message)
        if (entries.size >= MAX_ENTRIES) entries.removeFirst()
        entries.addLast(entry)
        _flow.value = entries.toList()
        when (level) {
            LogLevel.ERROR -> Log.e(tag, message)
            LogLevel.WARN -> Log.w(tag, message)
            else -> Log.i(tag, message)
        }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        _flow.value = emptyList()
    }
}
