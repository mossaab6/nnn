package com.smartcash.analyzer.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Fixtures mirror real Tesseract output of a Sham Cash receipt (names and numbers anonymised). */
class ReceiptTextParserTest {

    private val zone = ZoneId.of("Asia/Damascus")

    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int) =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(zone).toInstant().toEpochMilli()

    // Full-page OCR output (note the LRM/RLM marks and garbled masked accounts, as Tesseract produces them)
    private val fullPageOcr = """
        > شام كاش
        العملية إرسال - رقم 512903771
        تاريخ العملية: 2026-07-25 - 15:14:05
        اسم المرسل: ‎khaled_s‏
        ‏حساب المرسل: مي 349
        اسم المستلم: ‎Ahmad.77‏
        ‎ulus‏ المستلم: ‎eT‏
        ‏المبلغ: $193
        الملاحظة: باقي ثمن الغسالة
        أم سامر
        تم إنشاء الملف عبر شام كاش 06/08/2026 - 04:45 ‎PM‏
    """.trimIndent()

    @Test
    fun parsesFullPageOcr() {
        val r = ReceiptTextParser.parse(fullPageOcr, zone)
        assertEquals(OperationKind.SEND, r.operationKind)
        assertEquals("إرسال", r.operationLabel)
        assertEquals("512903771", r.reference)
        assertEquals(millis(2026, 7, 25, 15, 14, 5), r.dateTimeMillis)
        assertTrue(r.hasTime)
        assertEquals("khaled_s", r.senderName)
        assertEquals("Ahmad.77", r.receiverName)
        assertEquals(193.0, r.amount!!, 0.0001)
        assertEquals("USD", r.currency)
        assertEquals("باقي ثمن الغسالة أم سامر", r.note)
        assertTrue(r.isComplete)
    }

    @Test
    fun parsesTemplateRowsWithAccounts() {
        val rows = listOf(
            "العملية: إرسال - رقم 512903771",
            "تاريخ العملية: 15:14:05 - 2026-07-25",
            "اسم المرسل: khaled_s",
            "حساب المرسل: ************3496",
            "اسم المستلم: Ahmad.77",
            "حساب المستلم: ************9968",
            "المبلغ: $ 193",
            "الملاحظة: باقي ثمن الغسالة",
            "أم سامر",
        )
        val r = ReceiptTextParser.parseLines(rows, zone)
        assertEquals("************3496", r.senderAccount)
        assertEquals("************9968", r.receiverAccount)
        assertEquals(millis(2026, 7, 25, 15, 14, 5), r.dateTimeMillis)
        assertEquals("باقي ثمن الغسالة أم سامر", r.note)
    }

    @Test
    fun toleratesOcrMistakesInLabels() {
        val text = """
            العملية استلام - رقم 77001234
            تاريخ العملية: ٢٠٢٦-٠٨-٠١ - ٠٩:٠٥:٠٠
            اسم العرسل: محمد عبد الله
            اسم المستلم: سارة.م
            المبلع: 1,250.50 USD
        """.trimIndent()
        val r = ReceiptTextParser.parse(text, zone)
        assertEquals(OperationKind.RECEIVE, r.operationKind)
        assertEquals("77001234", r.reference)
        assertEquals("محمد عبد الله", r.senderName)
        assertEquals("سارة.م", r.receiverName)
        assertEquals(1250.5, r.amount!!, 0.0001)
        assertEquals(millis(2026, 8, 1, 9, 5, 0), r.dateTimeMillis)
    }

    @Test
    fun parsesEnglishReceipt() {
        val text = """
            Operation: Receive - No. 99887766
            Date: 2026-09-10 - 20:01:00
            Sender Name: Omar.K
            Receiver Name: khaled_s
            Amount: 50 USD
            Note: rent
        """.trimIndent()
        val r = ReceiptTextParser.parse(text, zone)
        assertEquals(OperationKind.RECEIVE, r.operationKind)
        assertEquals("99887766", r.reference)
        assertEquals("Omar.K", r.senderName)
        assertEquals("khaled_s", r.receiverName)
        assertEquals(50.0, r.amount!!, 0.0001)
        assertEquals("rent", r.note)
    }

    @Test
    fun footerDateIsNotUsedAsTransactionDate() {
        val text = """
            اسم المرسل: khaled_s
            اسم المستلم: Ahmad.77
            المبلغ: 10 $
            تم إنشاء الملف عبر شام كاش 06/08/2026 - 04:45 PM
        """.trimIndent()
        val r = ReceiptTextParser.parse(text, zone)
        assertNull(r.dateTimeMillis)
    }

    @Test
    fun syrianPoundAmount() {
        val r = ReceiptTextParser.parse("المبلغ: ٥٠٠٬٠٠٠ ل.س\nاسم المرسل: أحمد", zone)
        assertEquals(500000.0, r.amount!!, 0.0001)
        assertEquals("SYP", r.currency)
    }
}
