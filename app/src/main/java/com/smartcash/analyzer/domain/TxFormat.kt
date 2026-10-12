package com.smartcash.analyzer.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Arabic (Levantine) date formatting with Latin digits, as used in Syria. */
object TxFormat {
    private val months = listOf(
        "كانون الثاني", "شباط", "آذار", "نيسان", "أيار", "حزيران",
        "تموز", "آب", "أيلول", "تشرين الأول", "تشرين الثاني", "كانون الأول",
    )
    private val days = mapOf(
        1 to "الإثنين", 2 to "الثلاثاء", 3 to "الأربعاء", 4 to "الخميس", 5 to "الجمعة", 6 to "السبت", 7 to "الأحد",
    )

    /** Zero-padded with Latin digits regardless of the device locale. */
    private fun two(n: Int): String = if (n < 10) "0$n" else n.toString()

    fun monthName(m: Int): String = months[(m - 1).coerceIn(0, 11)]

    fun time(ts: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val t = Instant.ofEpochMilli(ts).atZone(zone).toLocalTime()
        return "${two(t.hour)}:${two(t.minute)}"
    }

    fun date(ts: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val d = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()
        return "${d.dayOfMonth} ${monthName(d.monthValue)} ${d.year}"
    }

    fun isoDate(ts: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        Instant.ofEpochMilli(ts).atZone(zone).toLocalDate().toString()

    fun full(ts: Long, hasTime: Boolean = true, zone: ZoneId = ZoneId.systemDefault()): String {
        val d = Instant.ofEpochMilli(ts).atZone(zone)
        val day = days[d.dayOfWeek.value].orEmpty()
        val base = "$day ${date(ts, zone)}"
        return if (hasTime) "$base - ${two(d.hour)}:${two(d.minute)}:${two(d.second)}" else base
    }

    /** "اليوم 15:14", "أمس 09:20", "25 تموز 2026" */
    fun relative(ts: Long, hasTime: Boolean = true, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        val d = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val t = if (hasTime) " ${time(ts, zone)}" else ""
        return when (d) {
            today -> "اليوم$t"
            today.minusDays(1) -> "أمس$t"
            else -> if (d.year == today.year) "${d.dayOfMonth} ${monthName(d.monthValue)}$t" else date(ts, zone) + t
        }
    }

    fun dayHeader(date: LocalDate, today: LocalDate): String = when (date) {
        today -> "اليوم"
        today.minusDays(1) -> "أمس"
        else -> "${days[date.dayOfWeek.value]} ${date.dayOfMonth} ${monthName(date.monthValue)} ${date.year}"
    }

    fun typeVerb(type: TxType): String = if (type == TxType.SENT) "إرسال إلى" else "استلام من"
    fun typeLabel(type: TxType): String = if (type == TxType.SENT) "إرسال" else "استلام"
}
