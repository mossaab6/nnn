package com.smartcash.analyzer.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.googlecode.tesseract.android.TessBaseAPI
import com.smartcash.analyzer.domain.ParsedReceipt
import com.smartcash.analyzer.domain.ReceiptTextParser
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min

/**
 * Reads a Sham Cash receipt offline.
 *
 * Mixed Arabic/Latin rows confuse OCR when read as a whole (a name like "Ahmad.77" next to an Arabic
 * label, or "$ 193" next to "المبلغ:"). The receipt is a two-column table (labels on the right, values on
 * the left), so after a first full-page pass this reader OCRs the value column with English-first models
 * and the label column with Arabic, then re-pairs them row by row using their vertical positions.
 */
class ReceiptOcrReader(private val ocr: TesseractOcr) {

    data class Reading(
        val parsed: ParsedReceipt,
        /** Plain text of the whole page (shown in the OCR screen and sent to the AI as a hint). */
        val fullText: String,
        /** Reconstructed "label: value" rows. */
        val rows: List<String>,
        val meanConfidence: Int,
        /** OCR confidence (0-100) per field name. */
        val fieldConfidence: Map<String, Float>,
    )

    private val labelWords = setOf("اسم", "حساب", "المرسل", "المستلم", "المبلغ", "الملاحظة", "تاريخ", "Sender", "Receiver", "Amount", "Date", "Note")
    private val footerWords = listOf("إنشاء", "انشاء", "Generated", "Created")

    suspend fun read(pages: List<Bitmap>, zone: ZoneId = ZoneId.systemDefault()): Reading {
        val texts = ArrayList<String>()
        val rows = ArrayList<String>()
        var confSum = 0
        val fieldConf = HashMap<String, Float>()
        for (page in pages) {
            val r = readPage(page, fieldConf)
            texts += r.first
            rows += r.second
            confSum += r.third
        }
        val fullText = texts.joinToString("\n")
        val fromRows = ReceiptTextParser.parseLines(rows, zone)
        val fromFull = ReceiptTextParser.parse(fullText, zone)
        // column-based reading is more accurate; the full-page reading fills anything it missed
        val parsed = fromRows.copy(
            operationLabel = fromRows.operationLabel ?: fromFull.operationLabel,
            operationKind = if (fromRows.operationKind != com.smartcash.analyzer.domain.OperationKind.UNKNOWN) fromRows.operationKind else fromFull.operationKind,
            reference = fromRows.reference ?: fromFull.reference,
            dateTimeMillis = fromRows.dateTimeMillis ?: fromFull.dateTimeMillis,
            hasTime = if (fromRows.dateTimeMillis != null) fromRows.hasTime else fromFull.hasTime,
            senderName = fromRows.senderName ?: fromFull.senderName,
            senderAccount = fromRows.senderAccount ?: fromFull.senderAccount,
            receiverName = fromRows.receiverName ?: fromFull.receiverName,
            receiverAccount = fromRows.receiverAccount ?: fromFull.receiverAccount,
            amount = fromRows.amount ?: fromFull.amount,
            currency = fromRows.currency ?: fromFull.currency,
            fee = fromRows.fee ?: fromFull.fee,
            note = fromRows.note ?: fromFull.note,
            rawText = fullText,
        )
        return Reading(parsed, fullText, rows, if (pages.isEmpty()) 0 else confSum / pages.size, fieldConf)
    }

    /** Returns (full text, rows, mean confidence) for one page. */
    private suspend fun readPage(page: Bitmap, fieldConf: MutableMap<String, Float>): Triple<String, List<String>, Int> {
        val w = page.width
        val h = page.height
        val full = ocr.recognize(page, "ara+eng", TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK, withWords = true)

        // rows that carry a known label tell us where the table starts
        val labelled = full.lines.filter { ReceiptTextParser.splitLine(it.text) != null }
        if (labelled.size < 2) return Triple(full.text, emptyList(), full.meanConfidence)

        val lineHeight = labelled.map { it.box.height() }.sorted()[labelled.size / 2].coerceAtLeast(10)
        val top = max(0, labelled.minOf { it.box.top } - lineHeight / 2)
        val footer = full.lines.firstOrNull { l -> l.box.top > top && footerWords.any { l.text.contains(it, ignoreCase = true) } }
        val bottom = min(h, footer?.box?.top ?: h)
        if (bottom - top < lineHeight * 2) return Triple(full.text, emptyList(), full.meanConfidence)

        // column boundary: just left of the label words (labels are right-aligned in the Arabic layout)
        val labelLefts = full.words.filter { word -> labelWords.any { word.text.trim(':', '：').equals(it, ignoreCase = true) } && word.box.top >= top }
            .map { it.box.left }
        val boundary = (if (labelLefts.isNotEmpty()) labelLefts.min() - (w * 0.03f).toInt() else (w * 0.585f).toInt())
            .coerceIn((w * 0.55f).toInt(), (w * 0.72f).toInt())

        val valueCrop = Bitmap.createBitmap(page, 0, top, boundary, bottom - top)
        val labelCrop = Bitmap.createBitmap(page, boundary, top, w - boundary, bottom - top)
        val values = try {
            ocr.recognize(valueCrop, "eng+ara", TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK).lines
        } finally {
            valueCrop.recycle()
        }
        val labels = try {
            ocr.recognize(labelCrop, "ara", TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK).lines
        } finally {
            labelCrop.recycle()
        }

        // row anchors: label lines from the label column (fallback: labelled full-page lines)
        data class Row(val label: String, val box: Rect, val values: MutableList<TesseractOcr.Line> = ArrayList())
        val rowList = labels.filter { ReceiptTextParser.matchLabel(it.text.substringBefore(':').substringBefore('：')) != null || ReceiptTextParser.splitLine(it.text) != null }
            .map { Row(it.text, Rect(it.box).apply { offset(0, top) }) }
            .ifEmpty { labelled.map { Row(it.text.substringBefore(':'), it.box) } }
            .sortedBy { it.box.top }
            .toMutableList()
        if (rowList.isEmpty()) return Triple(full.text, emptyList(), full.meanConfidence)

        for (v in values) {
            val vTop = v.box.top + top
            val vBottom = v.box.bottom + top
            var best: Row? = null
            var bestOverlap = 0
            for (r in rowList) {
                val overlap = min(vBottom, r.box.bottom) - max(vTop, r.box.top)
                if (overlap > bestOverlap) {
                    bestOverlap = overlap; best = r
                }
            }
            // wrapped continuation lines (e.g. the second line of a note) belong to the row above
            val target = best ?: rowList.lastOrNull { it.box.top <= vTop } ?: continue
            target.values += v
        }

        val rows = rowList.map { r ->
            val valueText = r.values.joinToString(" ") { it.text }
            val label = r.label.trim()
            val field = ReceiptTextParser.splitLine(label)?.first ?: ReceiptTextParser.matchLabel(label.substringBefore(':'))
            if (field != null && r.values.isNotEmpty()) {
                fieldConf[field.name] = r.values.map { it.confidence }.average().toFloat()
            }
            if (field == ReceiptTextParser.Field.OPERATION) {
                // header row: "العملية إرسال - رقم" (label column) + "512903771" (value column)
                "$label $valueText"
            } else {
                "${label.substringBefore(':').substringBefore('：').trim()}: $valueText"
            }
        }
        return Triple(full.text, rows, full.meanConfidence)
    }
}
