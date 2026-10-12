package com.smartcash.analyzer.domain

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/**
 * Decides whether a new transaction is the same one already stored, coming from another source
 * (e.g. the notification was read an hour ago and now the user imports the PDF receipt of it).
 */
object DuplicateDetector {

    data class Fingerprint(
        val id: Long,
        val type: TxType,
        val amount: Double,
        val currency: String,
        val timestamp: Long,
        val hasTime: Boolean,
        val reference: String?,
        val counterpartyName: String?,
        val fileHash: String?,
        val source: TxSource,
    )

    enum class Certainty { CERTAIN, PROBABLE }

    data class Match(val existingId: Long, val certainty: Certainty, val reason: String, val score: Int)

    /** Maximum time difference for two records without reference numbers to be the same transaction. */
    const val WINDOW_MS = 20 * 60 * 1000L
    private const val CLOSE_MS = 3 * 60 * 1000L

    fun findDuplicate(
        candidate: Fingerprint,
        existing: List<Fingerprint>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Match? {
        var best: Match? = null
        for (e in existing) {
            if (e.id == candidate.id) continue
            val m = compare(candidate, e, zone) ?: continue
            if (best == null || m.score > best.score) best = m
        }
        return best
    }

    fun compare(c: Fingerprint, e: Fingerprint, zone: ZoneId = ZoneId.systemDefault()): Match? {
        if (!c.fileHash.isNullOrBlank() && c.fileHash == e.fileHash) {
            return Match(e.id, Certainty.CERTAIN, "نفس الملف تمت إضافته سابقاً", 1000)
        }
        val refC = ArabicText.digits(c.reference)
        val refE = ArabicText.digits(e.reference)
        if (refC.length >= 4 && refE.length >= 4) {
            return if (refC == refE) Match(e.id, Certainty.CERTAIN, "نفس رقم العملية $refC", 900) else null
        }

        // Two notifications are always two separate events (exact re-posts are filtered earlier),
        // and manual entries are confirmed by the user, so only compare across different sources.
        if (c.source == e.source && (c.source == TxSource.NOTIFICATION || c.source == TxSource.MANUAL)) return null

        if (c.type != e.type) return null
        if (c.currency != e.currency) return null
        if (abs(c.amount - e.amount) > 0.009) return null

        val sameName = ArabicText.sameName(c.counterpartyName, e.counterpartyName)
        val oneNameMissing = c.counterpartyName.isNullOrBlank() || e.counterpartyName.isNullOrBlank()
        if (!sameName && !oneNameMissing) return null

        val diff = abs(c.timestamp - e.timestamp)
        val timeOk = if (c.hasTime && e.hasTime) {
            diff <= WINDOW_MS
        } else {
            sameDay(c.timestamp, e.timestamp, zone)
        }
        if (!timeOk) return null

        val close = c.hasTime && e.hasTime && diff <= CLOSE_MS
        val certainty = if (close && sameName) Certainty.CERTAIN else Certainty.PROBABLE
        val score = 500 + (if (sameName) 200 else 0) + (if (close) 100 else 0) - (diff / 60000L).toInt().coerceAtMost(99)
        val reason = buildString {
            append("نفس المبلغ والنوع")
            if (sameName) append(" ونفس الشخص")
            append(if (c.hasTime && e.hasTime) " وبفارق ${diff / 60000} دقيقة" else " وفي نفس اليوم")
        }
        return Match(e.id, certainty, reason, score)
    }

    private fun sameDay(a: Long, b: Long, zone: ZoneId): Boolean =
        Instant.ofEpochMilli(a).atZone(zone).toLocalDate() == Instant.ofEpochMilli(b).atZone(zone).toLocalDate()
}
