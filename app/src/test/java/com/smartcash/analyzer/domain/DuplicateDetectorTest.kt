package com.smartcash.analyzer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class DuplicateDetectorTest {

    private val zone = ZoneId.of("Asia/Damascus")
    private val t0 = 1_785_000_000_000L
    private val minute = 60_000L

    private fun fp(
        id: Long,
        source: TxSource,
        amount: Double = 193.0,
        time: Long = t0,
        ref: String? = null,
        name: String? = "Ahmad.77",
        type: TxType = TxType.SENT,
        hash: String? = null,
        hasTime: Boolean = true,
    ) = DuplicateDetector.Fingerprint(id, type, amount, "USD", time, hasTime, ref, name, hash, source)

    @Test
    fun notificationThenPdfIsSameTransaction() {
        val fromNotification = fp(1, TxSource.NOTIFICATION, ref = null, name = null, time = t0 + 40_000)
        val pdf = fp(0, TxSource.PDF, ref = "512903771")
        val m = DuplicateDetector.findDuplicate(pdf, listOf(fromNotification), zone)
        assertNotNull(m)
        assertEquals(1L, m!!.existingId)
    }

    @Test
    fun sameReferenceIsCertain() {
        val m = DuplicateDetector.compare(fp(0, TxSource.TELEGRAM, ref = "512903771", amount = 190.0), fp(5, TxSource.PDF, ref = "512903771"), zone)
        assertEquals(DuplicateDetector.Certainty.CERTAIN, m!!.certainty)
    }

    @Test
    fun differentReferencesAreDifferentTransactions() {
        assertNull(DuplicateDetector.compare(fp(0, TxSource.PDF, ref = "111111"), fp(5, TxSource.PDF, ref = "222222"), zone))
    }

    @Test
    fun sameFileHash() {
        val m = DuplicateDetector.compare(fp(0, TxSource.PDF, hash = "abc"), fp(9, TxSource.TELEGRAM, hash = "abc", amount = 1.0), zone)
        assertEquals(DuplicateDetector.Certainty.CERTAIN, m!!.certainty)
    }

    @Test
    fun twoNotificationsAreNeverMerged() {
        assertNull(DuplicateDetector.compare(fp(0, TxSource.NOTIFICATION), fp(1, TxSource.NOTIFICATION), zone))
    }

    @Test
    fun outsideTimeWindow() {
        assertNull(DuplicateDetector.compare(fp(0, TxSource.PDF), fp(1, TxSource.NOTIFICATION, time = t0 + 45 * minute), zone))
    }

    @Test
    fun differentAmountOrPerson() {
        assertNull(DuplicateDetector.compare(fp(0, TxSource.PDF), fp(1, TxSource.NOTIFICATION, amount = 192.0), zone))
        assertNull(DuplicateDetector.compare(fp(0, TxSource.PDF), fp(1, TxSource.NOTIFICATION, name = "Omar.K"), zone))
        assertNull(DuplicateDetector.compare(fp(0, TxSource.PDF), fp(1, TxSource.NOTIFICATION, type = TxType.RECEIVED), zone))
    }

    @Test
    fun manualDateOnlyMatchesSameDay() {
        val m = DuplicateDetector.compare(fp(0, TxSource.PDF), fp(1, TxSource.MANUAL, hasTime = false, time = t0 + 3 * 60 * minute), zone)
        assertNotNull(m)
        assertEquals(DuplicateDetector.Certainty.PROBABLE, m!!.certainty)
    }
}
