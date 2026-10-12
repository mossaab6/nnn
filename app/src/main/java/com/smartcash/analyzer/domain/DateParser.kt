package com.smartcash.analyzer.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Extracts a date and an optional time from receipt / notification text.
 * Handles "2026-07-25 - 15:14:05", "15:14:05 - 2026-07-25", "06/08/2026 - 04:45 PM", Arabic digits and ص/م.
 */
object DateParser {

    data class Result(val dateTime: LocalDateTime, val hasTime: Boolean) {
        fun toMillis(zone: ZoneId = ZoneId.systemDefault()): Long =
            dateTime.atZone(zone).toInstant().toEpochMilli()
    }

    private val ymd = Regex("(?<!\\d)(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})(?!\\d)")
    private val dmy = Regex("(?<!\\d)(\\d{1,2})[-/.](\\d{1,2})[-/.](\\d{4})(?!\\d)")
    private val time = Regex(
        "(?<!\\d)(\\d{1,2}):(\\d{2})(?::(\\d{2}))?(?!\\d)" +
            "(?:\\s*(AM|PM|am|pm|A\\.M\\.|P\\.M\\.|صباحاً|مساءً|صباحا|مساء|ص|م)(?![\\p{L}\\u064B-\\u065F]))?"
    )
    private val pmBefore = Regex("(?:^|\\s)(PM|pm|P\\.M\\.|مساءً|مساء|م)\\s*$")
    private val amBefore = Regex("(?:^|\\s)(AM|am|A\\.M\\.|صباحاً|صباحا|ص)\\s*$")

    fun parse(text: String?): Result? {
        if (text.isNullOrBlank()) return null
        val t = ArabicText.normalizeDigits(ArabicText.stripInvisible(text))
        val date = parseDate(t) ?: return null
        val tm = parseTime(t)
        return Result(LocalDateTime.of(date, tm ?: LocalTime.NOON), tm != null)
    }

    fun parseDate(t: String): LocalDate? {
        ymd.find(t)?.let { m ->
            val (y, mo, d) = m.destructured
            return safeDate(y.toInt(), mo.toInt(), d.toInt())
        }
        dmy.find(t)?.let { m ->
            val (d, mo, y) = m.destructured
            // Day-first is the norm in Syria (06/08/2026 = 6 August). Fall back to month-first if impossible.
            return safeDate(y.toInt(), mo.toInt(), d.toInt()) ?: safeDate(y.toInt(), d.toInt(), mo.toInt())
        }
        return null
    }

    fun parseTime(t: String): LocalTime? {
        val m = time.find(t) ?: return null
        var h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        val sec = m.groupValues[3].toIntOrNull() ?: 0
        val suffix = m.groupValues[4]
        // OCR of right-to-left lines sometimes puts "PM" before the time ("PM 04:45").
        val before = t.substring(0, m.range.first)
        val isPm = if (suffix.isNotEmpty()) pmBefore.containsMatchIn(" $suffix") else pmBefore.containsMatchIn(before.takeLast(10))
        val isAm = if (suffix.isNotEmpty()) amBefore.containsMatchIn(" $suffix") else amBefore.containsMatchIn(before.takeLast(10))
        if (isPm && h in 1..11) h += 12
        if (isAm && h == 12) h = 0
        if (h !in 0..23 || min !in 0..59 || sec !in 0..59) return null
        return LocalTime.of(h, min, sec)
    }

    private fun safeDate(y: Int, m: Int, d: Int): LocalDate? =
        if (y in 2000..2100 && m in 1..12 && d in 1..31) runCatching { LocalDate.of(y, m, d) }.getOrNull() else null
}
