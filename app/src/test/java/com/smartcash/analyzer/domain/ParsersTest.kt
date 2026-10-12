package com.smartcash.analyzer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ParsersTest {

    @Test
    fun amounts() {
        assertEquals(AmountParser.Amount(193.0, "USD"), AmountParser.parse("$ 193"))
        assertEquals(AmountParser.Amount(193.0, "USD"), AmountParser.parse("193$"))
        assertEquals(AmountParser.Amount(1250.5, "USD"), AmountParser.parse("1,250.50 USD"))
        assertEquals(AmountParser.Amount(500.0, "SYP"), AmountParser.parse("٥٠٠ ل.س"))
        assertEquals(AmountParser.Amount(12.5, "EUR"), AmountParser.parse("12,5 €"))
        assertEquals(AmountParser.Amount(1250.75, null), AmountParser.parse("1.250,75"))
        assertEquals(AmountParser.Amount(300.0, "TRY"), AmountParser.parse("300 ليرة تركية"))
        assertNull(AmountParser.parse("لا يوجد رقم"))
        assertEquals("USD", AmountParser.normalizeCurrency("usd"))
        assertEquals("SYP", AmountParser.normalizeCurrency("ليرة سورية"))
    }

    @Test
    fun dates() {
        assertEquals(LocalDateTime.of(2026, 7, 25, 15, 14, 5), DateParser.parse("2026-07-25 - 15:14:05")!!.dateTime)
        assertEquals(LocalDateTime.of(2026, 7, 25, 15, 14, 5), DateParser.parse("15:14:05 - 2026-07-25")!!.dateTime)
        assertEquals(LocalDateTime.of(2026, 8, 6, 16, 45, 0), DateParser.parse("06/08/2026 - 04:45 PM")!!.dateTime)
        assertEquals(LocalDateTime.of(2026, 8, 6, 16, 45, 0), DateParser.parse("PM 04:45 - 06/08/2026")!!.dateTime)
        assertEquals(LocalDateTime.of(2026, 7, 25, 9, 5, 0), DateParser.parse("٢٠٢٦-٠٧-٢٥ ٠٩:٠٥")!!.dateTime)
        assertEquals(LocalDateTime.of(2026, 8, 6, 21, 10, 0), DateParser.parse("06/08/2026 9:10 م")!!.dateTime)
        // "م" that is the end of a word must not be read as PM
        assertEquals(LocalDateTime.of(2026, 8, 6, 4, 45, 0), DateParser.parse("2026-08-06 استلام 04:45")!!.dateTime)
        assertEquals(LocalDateTime.of(2026, 8, 6, 4, 45, 0), DateParser.parse("2026-08-06 04:45 من أحمد")!!.dateTime)
        val dateOnly = DateParser.parse("2026-01-02")!!
        assertFalse(dateOnly.hasTime)
        assertNull(DateParser.parse("بدون تاريخ"))
    }

    @Test
    fun names() {
        assertEquals("Ahmad.77", ArabicText.cleanName("‎ Ahmad.77 ‏"))
        assertEquals("Ahmad.77", ArabicText.cleanName(": Ahmad.77"))
        assertTrue(ArabicText.sameName("Ahmad.77", "ahmad.77"))
        assertTrue(ArabicText.sameName("أحمد الحلبي", "احمد الحلبى"))
        assertTrue(ArabicText.sameName("محمّد", "محمد"))
        assertFalse(ArabicText.sameName("محمد", "أحمد"))
        assertFalse(ArabicText.sameName("Ali.1", "Omar.2"))
        assertEquals("احمد الحلبي", ArabicText.nameKey("  أحمد   الحلبى "))
        // presentation forms (from some PDFs) fold back to normal letters
        assertEquals("محمد", ArabicText.clean("ﻣﺤﻤﺪ"))
    }

    @Test
    fun notificationReceive() {
        val r = NotificationParser.parse("شام كاش", "تم استلام مبلغ 100 USD من Ahmad.77 بنجاح. رصيدك الحالي 350 USD")
        assertNotNull(r)
        assertEquals(OperationKind.RECEIVE, r!!.kind)
        assertEquals(100.0, r.amount, 0.0)
        assertEquals("USD", r.currency)
        assertEquals("Ahmad.77", r.counterparty)
        assertEquals(350.0, r.balanceAfter!!, 0.0)
    }

    @Test
    fun notificationSend() {
        val r = NotificationParser.parse("شام كاش", "لقد أرسلت 50$ إلى أحمد الحلبي، رقم العملية 512903771")
        assertNotNull(r)
        assertEquals(OperationKind.SEND, r!!.kind)
        assertEquals(50.0, r.amount, 0.0)
        assertEquals("أحمد الحلبي", r.counterparty)
        assertEquals("512903771", r.reference)
    }

    @Test
    fun notificationSentYouIsIncoming() {
        val r = NotificationParser.parse("Sham Cash", "Ali.K sent you 25 USD")
        assertEquals(OperationKind.RECEIVE, r!!.kind)
        assertEquals(25.0, r.amount, 0.0)
    }

    @Test
    fun notificationEnglishFrom() {
        val r = NotificationParser.parse("Sham Cash", "You received 25.5 USD from Omar.K on 2026-08-01")
        assertEquals(OperationKind.RECEIVE, r!!.kind)
        assertEquals(25.5, r.amount, 0.0)
        assertEquals("Omar.K", r.counterparty)
    }

    @Test
    fun nonMoneyNotificationIgnored() {
        assertNull(NotificationParser.parse("شام كاش", "مرحباً بك! حدّث التطبيق الآن"))
        assertNull(NotificationParser.parse("شام كاش", "رمز التحقق الخاص بك"))
    }

    @Test
    fun directionUsesOwnerIdentity() {
        val receipt = ParsedReceipt(
            operationKind = OperationKind.SEND,
            senderName = "Ahmad.77", senderAccount = "************9968",
            receiverName = "khaled_s", receiverAccount = "************3496",
            amount = 20.0,
        )
        // the friend's "send" receipt forwarded to me is a RECEIVE for me
        val me = DirectionResolver.Identity(listOf("khaled_s"), emptyList())
        val r = DirectionResolver.resolve(receipt, me)
        assertEquals(TxType.RECEIVED, r.type)
        assertEquals("Ahmad.77", r.counterpartyName)
        assertTrue(r.certain)

        val byAccount = DirectionResolver.resolve(receipt, DirectionResolver.Identity(emptyList(), listOf("9968")))
        assertEquals(TxType.SENT, byAccount.type)
        assertEquals("khaled_s", byAccount.counterpartyName)

        val unknown = DirectionResolver.resolve(receipt, DirectionResolver.Identity(emptyList(), emptyList()))
        assertEquals(TxType.SENT, unknown.type)
        assertFalse(unknown.certain)
        assertEquals("Ahmad.77", unknown.ownerCandidate)
    }

    @Test
    fun moneyFormatting() {
        assertEquals("-193 $", Money.signed(193.0, "USD", TxType.SENT))
        assertEquals("+1,250.5 $", Money.signed(1250.5, "USD", TxType.RECEIVED))
        assertEquals("500,000 ل.س", Money.withCurrency(500000.0, "SYP"))
    }
}
