package com.smartcash.analyzer.analyze

import android.graphics.Bitmap
import com.smartcash.analyzer.ai.AiExtractor
import com.smartcash.analyzer.ai.AiNotConfiguredException
import com.smartcash.analyzer.data.settings.SettingsRepository
import com.smartcash.analyzer.domain.ArabicText
import com.smartcash.analyzer.domain.OperationKind
import com.smartcash.analyzer.domain.ParsedReceipt
import com.smartcash.analyzer.ocr.PageImages
import com.smartcash.analyzer.ocr.ReceiptOcrReader
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs

/**
 * Reads a receipt twice — offline OCR (Tesseract) and Grok vision — and compares the two readings.
 * Fields that agree are trusted; when names, amount, number or date disagree the transaction is
 * flagged for review and both readings are kept so the user can pick the right one.
 */
class ReceiptAnalyzer(
    private val ocrReader: ReceiptOcrReader,
    private val ai: AiExtractor,
    private val settings: SettingsRepository,
) {

    data class Disagreement(val field: String, val label: String, val ocr: String?, val ai: String?)

    data class Analysis(
        val parsed: ParsedReceipt,
        val isReceipt: Boolean,
        /** "OCR + Grok", "Grok" or "OCR" */
        val method: String,
        val ocrText: String?,
        val aiText: String?,
        val disagreements: List<Disagreement>,
        val uncertainFields: List<String>,
        val needsReview: Boolean,
        val reviewReason: String?,
        val aiError: String?,
    ) {
        val alternativesJson: String?
            get() = if (disagreements.isEmpty()) null else JsonObject(
                disagreements.associate { d ->
                    d.field to JsonObject(mapOf("label" to JsonPrimitive(d.label), "ocr" to JsonPrimitive(d.ocr), "ai" to JsonPrimitive(d.ai)))
                }
            ).toString()
    }

    private val fieldLabels = mapOf(
        "senderName" to "اسم المرسل",
        "receiverName" to "اسم المستلم",
        "amount" to "المبلغ",
        "reference" to "رقم العملية",
        "date" to "التاريخ",
    )

    suspend fun analyze(pages: List<Bitmap>, onProgress: (String) -> Unit = {}): Analysis {
        val s = settings.current()

        onProgress("قراءة النص من الصورة (OCR)…")
        val ocr = runCatching { ocrReader.read(pages) }.getOrNull()

        var aiReceipt: AiExtractor.AiReceipt? = null
        var aiError: String? = null
        if (s.aiConfigured) {
            onProgress("تحليل الإيصال بالذكاء الاصطناعي Grok…")
            try {
                aiReceipt = ai.readReceipt(pages.take(2).map { PageImages.toJpeg(it) })
            } catch (e: AiNotConfiguredException) {
                aiError = e.message
            } catch (e: Exception) {
                aiError = e.message ?: e.javaClass.simpleName
            }
        }

        val ocrParsed = ocr?.parsed
        val aiR = aiReceipt
        val aiParsed = if (aiR != null && (aiR.isReceipt || aiR.parsed.isComplete)) aiR.parsed else null

        if (aiParsed == null) {
            val p = ocrParsed ?: ParsedReceipt()
            val lowConfidenceNames = listOf("SENDER_NAME", "RECEIVER_NAME").any { (ocr?.fieldConfidence?.get(it) ?: 100f) < 70f }
            val missing = buildList {
                if (p.amount == null) add("المبلغ")
                if (p.senderName == null && p.receiverName == null) add("الأسماء")
                if (p.reference == null) add("رقم العملية")
                if (p.dateTimeMillis == null) add("التاريخ")
            }
            val reason = when {
                missing.isNotEmpty() -> "لم تتم قراءة: ${missing.joinToString("، ")}"
                lowConfidenceNames -> "قراءة الأسماء غير مؤكدة (OCR فقط)"
                else -> null
            }
            return Analysis(
                parsed = p,
                isReceipt = p.isComplete,
                method = "OCR",
                ocrText = ocr?.fullText,
                aiText = null,
                disagreements = emptyList(),
                uncertainFields = emptyList(),
                needsReview = reason != null,
                reviewReason = reason,
                aiError = aiError,
            )
        }

        if (ocrParsed == null || !s.aiCrossCheck) {
            val uncertain = aiR?.uncertainFields.orEmpty()
            return Analysis(
                parsed = aiParsed.copy(rawText = aiR?.fullText ?: ocr?.fullText),
                isReceipt = true,
                method = "Grok",
                ocrText = ocr?.fullText,
                aiText = aiR?.fullText,
                disagreements = emptyList(),
                uncertainFields = uncertain,
                needsReview = uncertain.any { it.contains("name") || it.contains("amount") },
                reviewReason = if (uncertain.isNotEmpty()) "Grok غير متأكد من: ${uncertain.joinToString("، ")}" else null,
                aiError = aiError,
            )
        }

        val (merged, disagreements) = crossCheck(ocrParsed, aiParsed)
        val uncertain = aiR?.uncertainFields.orEmpty()
        val important = disagreements.filter { it.field != "date" || abs((ocrParsed.dateTimeMillis ?: 0) - (aiParsed.dateTimeMillis ?: 0)) > 60_000 }
        val reason = when {
            important.isNotEmpty() -> "اختلاف بين قراءة OCR وقراءة Grok في: ${important.joinToString("، ") { it.label }}"
            uncertain.any { it.contains("name") } -> "Grok غير متأكد من بعض الأسماء"
            else -> null
        }
        return Analysis(
            parsed = merged.copy(rawText = aiR?.fullText ?: ocr?.fullText),
            isReceipt = true,
            method = "OCR + Grok",
            ocrText = ocr?.fullText,
            aiText = aiR?.fullText,
            disagreements = important,
            uncertainFields = uncertain,
            needsReview = reason != null,
            reviewReason = reason,
            aiError = aiError,
        )
    }

    /**
     * Grok is the primary reading; OCR fills gaps. For Latin-only names where both readings are the same
     * person, OCR's exact characters are kept (Tesseract copies Latin text literally).
     */
    fun crossCheck(ocr: ParsedReceipt, ai: ParsedReceipt): Pair<ParsedReceipt, List<Disagreement>> {
        val diffs = ArrayList<Disagreement>()

        fun name(field: String, o: String?, a: String?): String? {
            if (o == null) return a
            if (a == null) return o
            if (o == a) return a
            if (ArabicText.looseKey(o) == ArabicText.looseKey(a)) {
                // same letters, different punctuation/case: prefer OCR for Latin names, Grok for Arabic
                return if (!ArabicText.containsArabic(o) && !ArabicText.containsArabic(a)) o else a
            }
            diffs += Disagreement(field, fieldLabels.getValue(field), o, a)
            return a
        }

        val sender = name("senderName", ocr.senderName, ai.senderName)
        val receiver = name("receiverName", ocr.receiverName, ai.receiverName)

        if (ocr.amount != null && ai.amount != null && abs(ocr.amount - ai.amount) > 0.009) {
            diffs += Disagreement("amount", fieldLabels.getValue("amount"), ocr.amount.toString(), ai.amount.toString())
        }
        if (ocr.reference != null && ai.reference != null && ocr.reference != ai.reference) {
            diffs += Disagreement("reference", fieldLabels.getValue("reference"), ocr.reference, ai.reference)
        }
        if (ocr.dateTimeMillis != null && ai.dateTimeMillis != null && ocr.dateTimeMillis != ai.dateTimeMillis) {
            diffs += Disagreement("date", fieldLabels.getValue("date"), ocr.dateTimeMillis.toString(), ai.dateTimeMillis.toString())
        }

        val merged = ai.copy(
            operationLabel = ai.operationLabel ?: ocr.operationLabel,
            operationKind = if (ai.operationKind != OperationKind.UNKNOWN) ai.operationKind else ocr.operationKind,
            reference = ai.reference ?: ocr.reference,
            dateTimeMillis = ai.dateTimeMillis ?: ocr.dateTimeMillis,
            hasTime = if (ai.dateTimeMillis != null) ai.hasTime else ocr.hasTime,
            senderName = sender,
            senderAccount = ai.senderAccount ?: ocr.senderAccount,
            receiverName = receiver,
            receiverAccount = ai.receiverAccount ?: ocr.receiverAccount,
            amount = ai.amount ?: ocr.amount,
            currency = ai.currency ?: ocr.currency,
            fee = ai.fee ?: ocr.fee,
            note = ai.note ?: ocr.note,
        )
        return merged to diffs
    }
}
