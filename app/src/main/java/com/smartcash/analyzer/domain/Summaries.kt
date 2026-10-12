package com.smartcash.analyzer.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Lightweight view of a transaction used for totals. */
data class TxLite(
    val type: TxType,
    val amount: Double,
    val currency: String,
    val fee: Double?,
    val timestamp: Long,
    val counterpartyKey: String = "",
)

/** The balance the user entered and the moment it was true; only later transactions change it. */
data class BalanceAnchor(val amount: Double, val anchorTime: Long)

data class Summary(
    val currency: String,
    val balance: Double?,
    val received: Double,
    val sent: Double,
    val fees: Double,
    val receivedCount: Int,
    val sentCount: Int,
)

object Periods {
    /** Weeks start on Saturday (Syrian calendar). */
    val weekStart: DayOfWeek = DayOfWeek.SATURDAY

    fun startOf(period: Period, now: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val start: LocalDate = when (period) {
            Period.TODAY -> today
            Period.WEEK -> today.with(TemporalAdjusters.previousOrSame(weekStart))
            Period.MONTH -> today.withDayOfMonth(1)
            Period.ALL -> return Long.MIN_VALUE
        }
        return start.atStartOfDay(zone).toInstant().toEpochMilli()
    }
}

object SummaryCalculator {

    fun balance(txs: List<TxLite>, currency: String, anchor: BalanceAnchor?): Double? {
        anchor ?: return null
        var b = anchor.amount
        for (t in txs) {
            if (t.currency != currency || t.timestamp <= anchor.anchorTime) continue
            b += if (t.type == TxType.RECEIVED) t.amount else -(t.amount + (t.fee ?: 0.0))
        }
        return Money.round2(b)
    }

    fun summarize(
        txs: List<TxLite>,
        currency: String,
        period: Period,
        anchor: BalanceAnchor?,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Summary {
        val from = Periods.startOf(period, now, zone)
        var received = 0.0
        var sent = 0.0
        var fees = 0.0
        var rc = 0
        var sc = 0
        for (t in txs) {
            if (t.currency != currency || t.timestamp < from) continue
            if (t.type == TxType.RECEIVED) {
                received += t.amount; rc++
            } else {
                sent += t.amount; sc++
                fees += t.fee ?: 0.0
            }
        }
        return Summary(
            currency = currency,
            balance = balance(txs, currency, anchor),
            received = Money.round2(received),
            sent = Money.round2(sent),
            fees = Money.round2(fees),
            receivedCount = rc,
            sentCount = sc,
        )
    }

    /** Daily totals for the last [days] days (oldest first): pairs of (received, sent). */
    fun daily(
        txs: List<TxLite>,
        currency: String,
        days: Int,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<Triple<LocalDate, Double, Double>> {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val start = today.minusDays((days - 1).toLong())
        val rec = DoubleArray(days)
        val snt = DoubleArray(days)
        for (t in txs) {
            if (t.currency != currency) continue
            val d = Instant.ofEpochMilli(t.timestamp).atZone(zone).toLocalDate()
            val idx = (d.toEpochDay() - start.toEpochDay()).toInt()
            if (idx !in 0 until days) continue
            if (t.type == TxType.RECEIVED) rec[idx] += t.amount else snt[idx] += t.amount
        }
        return (0 until days).map { Triple(start.plusDays(it.toLong()), rec[it], snt[it]) }
    }
}
