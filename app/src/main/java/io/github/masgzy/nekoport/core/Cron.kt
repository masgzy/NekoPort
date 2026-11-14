package io.github.masgzy.nekoport.core

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Standard 5-field cron expression parser (minute hour day-of-month month
 * day-of-week). Supports `*`, lists (`1,2,3`), ranges (`1-5`) and steps
 * (star-slash-5 style, `1-30` slash 3). Day-of-week accepts 0-7 with both 0
 * and 7 meaning Sunday, matching vixie-cron semantics (when both day fields
 * are restricted, either one may fire).
 */
object CronParser {

    data class Schedule(
        val minutes: Set<Int>,
        val hours: Set<Int>,
        val daysOfMonth: Set<Int>,
        val months: Set<Int>,
        val daysOfWeek: Set<Int>,
        val domWildcard: Boolean,
        val dowWildcard: Boolean,
    )

    private const val MIN_DAY = 1
    private val MONTH_NAMES = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
    )
    private val DOW_NAMES = mapOf(
        "sun" to 0, "mon" to 1, "tue" to 2, "wed" to 3, "thu" to 4, "fri" to 5, "sat" to 6,
    )

    fun parse(expr: String): Schedule? {
        val fields = expr.trim().lowercase(Locale.US).split(Regex("\\s+"))
        if (fields.size != 5) return null
        val minutes = parseField(fields[0], 0, 59, null) ?: return null
        val hours = parseField(fields[1], 0, 23, null) ?: return null
        val dom = parseField(fields[2], 1, 31, null) ?: return null
        val months = parseField(fields[3], 1, 12, MONTH_NAMES) ?: return null
        val dow = parseField(fields[4], 0, 7, DOW_NAMES) ?: return null
        val dowNormalized = dow.map { it % 7 }.toSet()
        return Schedule(
            minutes = minutes,
            hours = hours,
            daysOfMonth = dom,
            months = months,
            daysOfWeek = dowNormalized,
            domWildcard = fields[2] == "*",
            dowWildcard = fields[4] == "*",
        )
    }

    fun isValid(expr: String): Boolean = parse(expr) != null

    /** Next minute boundary (epoch millis) at which [expr] fires, strictly after [after]. */
    fun nextFire(expr: String, after: Long, timeZone: TimeZone = TimeZone.getDefault()): Long? {
        val schedule = parse(expr) ?: return null
        val cal = Calendar.getInstance(timeZone, Locale.US)
        cal.timeInMillis = after
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        // Strictly after `after`: advance to the next minute first (a result
        // at or before `after` would make the scheduler fire twice per minute).
        cal.add(Calendar.MINUTE, 1)
        // Walk day by day (fast: never-firing expressions used to burn a whole
        // year of minutes here, freezing the editor's "check" button), then
        // pick the earliest matching minute inside each candidate day.
        var daysChecked = 0
        val maxDays = 366 * 4 + 2 // ~4 years covers all Feb-29 combos
        while (daysChecked <= maxDays) {
            if (dayMatches(schedule, cal)) {
                val curHour = cal.get(Calendar.HOUR_OF_DAY)
                val curMinute = cal.get(Calendar.MINUTE)
                for (h in schedule.hours.sorted()) {
                    if (h < curHour) continue
                    for (m in schedule.minutes.sorted()) {
                        if (h == curHour && m < curMinute) continue
                        val hit = cal.clone() as Calendar
                        hit.set(Calendar.HOUR_OF_DAY, h)
                        hit.set(Calendar.MINUTE, m)
                        return hit.timeInMillis
                    }
                }
            }
            cal.add(Calendar.DAY_OF_MONTH, 1)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            daysChecked++
        }
        return null
    }

    /** Day-level gate: month + the dom/dow vixie-cron OR rule. */
    private fun dayMatches(s: Schedule, cal: Calendar): Boolean {
        val month = cal.get(Calendar.MONTH) + 1
        if (month !in s.months) return false
        val domOk = cal.get(Calendar.DAY_OF_MONTH) in s.daysOfMonth
        val dowOk = (cal.get(Calendar.DAY_OF_WEEK) - 1) in s.daysOfWeek
        return when {
            s.domWildcard && s.dowWildcard -> true
            s.domWildcard -> dowOk
            s.dowWildcard -> domOk
            else -> domOk || dowOk
        }
    }

    /** True when the schedule fires at the wall-clock minute containing [atMillis]. */
    fun matches(expr: String, atMillis: Long, timeZone: TimeZone = TimeZone.getDefault()): Boolean {
        val schedule = parse(expr) ?: return false
        val cal = Calendar.getInstance(timeZone, Locale.US)
        cal.timeInMillis = atMillis
        return matches(schedule, cal)
    }

    private fun matches(s: Schedule, cal: Calendar): Boolean {
        val minute = cal.get(Calendar.MINUTE)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val dom = cal.get(Calendar.DAY_OF_MONTH)
        val month = cal.get(Calendar.MONTH) + 1
        val dow = cal.get(Calendar.DAY_OF_WEEK) - 1 // 0 = Sunday
        if (minute !in s.minutes || hour !in s.hours || month !in s.months) return false
        val domOk = dom in s.daysOfMonth
        val dowOk = dow in s.daysOfWeek
        return when {
            s.domWildcard && s.dowWildcard -> true
            s.domWildcard -> dowOk
            s.dowWildcard -> domOk
            else -> domOk || dowOk
        }
    }

    private fun parseField(field: String, min: Int, max: Int, names: Map<String, Int>?): Set<Int>? {
        val values = mutableSetOf<Int>()
        for (part in field.split(',')) {
            if (part.isEmpty()) return null
            val (rangePart, step) = if (part.contains('/')) {
                val idx = part.indexOf('/')
                val stepText = part.substring(idx + 1)
                val step = stepText.toIntOrNull() ?: return null
                if (step <= 0) return null
                part.substring(0, idx) to step
            } else {
                part to 1
            }
            val resolved = if (rangePart == "*") {
                min to max
            } else if (rangePart.contains('-')) {
                val pieces = rangePart.split('-')
                if (pieces.size != 2) return null
                (resolveValue(pieces[0], min, max, names) ?: return null) to
                    (resolveValue(pieces[1], min, max, names) ?: return null)
            } else {
                val v = resolveValue(rangePart, min, max, names) ?: return null
                // A single value with a step means "start at v, step to max"
                // (vixie-cron semantics): "0/30" fires at :00 and :30, not
                // only at :00. Previously this produced just {v}.
                v to max
            }
            var v = resolved.first
            if (resolved.first > resolved.second) return null
            while (v <= resolved.second) {
                if (v < min || v > max) return null
                values.add(v)
                v += step
            }
        }
        if (values.isEmpty()) return null
        return values
    }

    private fun resolveValue(text: String, min: Int, max: Int, names: Map<String, Int>?): Int? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        names?.get(trimmed)?.let { return it }
        return trimmed.toIntOrNull()?.takeIf { it in min..max }
    }
}
