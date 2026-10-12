package com.smartcash.analyzer.domain

import java.time.ZoneId

/**
 * Parses the text of a Sham Cash receipt (from OCR, a PDF text layer or AI transcription) into fields.
 *
 * The receipt is a list of "label: value" rows, e.g.
 * ```
 * العملية إرسال - رقم 512903771
 * تاريخ العملية: 2026-07-25 - 15:14:05
 * اسم المرسل: ...
 * المبلغ: $ 193
 * ```
 * Labels are matched fuzzily so small OCR mistakes ("المبلع", "العرسل") are tolerated.
 * Values are kept exactly as read (only invisible bidi marks and extra spaces are removed).
 */
object ReceiptTextParser {

    enum class Field {
        OPERATION, REFERENCE, DATE, SENDER_NAME, SENDER_ACCOUNT, RECEIVER_NAME, RECEIVER_ACCOUNT,
        AMOUNT, FEE, NOTE, BALANCE, FOOTER
    }

    private val labels: List<Pair<Field, String>> = listOf(
        Field.OPERATION to "العملية",
        Field.OPERATION to "نوع العملية",
        Field.OPERATION to "Operation",
        Field.OPERATION to "Transaction",
        Field.OPERATION to "Transaction Type",
        Field.REFERENCE to "رقم العملية",
        Field.REFERENCE to "رقم المرجع",
        Field.REFERENCE to "المرجع",
        Field.REFERENCE to "رقم الحوالة",
        Field.REFERENCE to "Reference",
        Field.REFERENCE to "Transaction ID",
        Field.REFERENCE to "Ref",
        Field.DATE to "تاريخ العملية",
        Field.DATE to "التاريخ",
        Field.DATE to "تاريخ",
        Field.DATE to "Date",
        Field.DATE to "Transaction Date",
        Field.SENDER_NAME to "اسم المرسل",
        Field.SENDER_NAME to "المرسل",
        Field.SENDER_NAME to "Sender Name",
        Field.SENDER_NAME to "Sender",
        Field.SENDER_NAME to "From",
        Field.SENDER_ACCOUNT to "حساب المرسل",
        Field.SENDER_ACCOUNT to "رقم حساب المرسل",
        Field.SENDER_ACCOUNT to "Sender Account",
        Field.RECEIVER_NAME to "اسم المستلم",
        Field.RECEIVER_NAME to "المستلم",
        Field.RECEIVER_NAME to "اسم المستفيد",
        Field.RECEIVER_NAME to "المستفيد",
        Field.RECEIVER_NAME to "Receiver Name",
        Field.RECEIVER_NAME to "Recipient Name",
        Field.RECEIVER_NAME to "Receiver",
        Field.RECEIVER_NAME to "Recipient",
        Field.RECEIVER_NAME to "Beneficiary",
        Field.RECEIVER_NAME to "To",
        Field.RECEIVER_ACCOUNT to "حساب المستلم",
        Field.RECEIVER_ACCOUNT to "رقم حساب المستلم",
        Field.RECEIVER_ACCOUNT to "حساب المستفيد",
        Field.RECEIVER_ACCOUNT to "Receiver Account",
        Field.RECEIVER_ACCOUNT to "Recipient Account",
        Field.AMOUNT to "المبلغ",
        Field.AMOUNT to "القيمة",
        Field.AMOUNT to "Amount",
        Field.FEE to "العمولة",
        Field.FEE to "الرسوم",
        Field.FEE to "رسوم التحويل",
        Field.FEE to "Fee",
        Field.FEE to "Fees",
        Field.FEE to "Commission",
        Field.NOTE to "الملاحظة",
        Field.NOTE to "ملاحظة",
        Field.NOTE to "الملاحظات",
        Field.NOTE to "البيان",
        Field.NOTE to "Note",
        Field.NOTE to "Notes",
        Field.NOTE to "Description",
        Field.BALANCE to "الرصيد",
        Field.BALANCE to "الرصيد بعد العملية",
        Field.BALANCE to "Balance",
    )

    private val labelKeys = labels.map { (f, l) -> Triple(f, l, ArabicText.nameKey(l)) }
        .sortedByDescending { it.third.length }

    private val footerMarkers = listOf("تم إنشاء", "تم انشاء", "إنشاء الملف", "انشاء الملف", "Generated", "Created via", "Created by")
    private val colon = Regex("[:：؛]")
    private val referenceRegex = Regex("(?i)(?:رقم|No\\.?|Number|Ref(?:erence)?|ID|#)\\s*[:：]?\\s*(\\d{4,})")
    private val longDigits = Regex("(?<!\\d)(\\d{6,})(?!\\d)")

    private val sendWords = listOf("إرسال", "ارسال", "أرسال", "تحويل صادر", "تحويل", "دفع", "سحب", "Send", "Sent", "Transfer", "Payment", "Withdraw")
    private val receiveWords = listOf("استلام", "إستلام", "استقبال", "إستقبال", "إيداع", "ايداع", "وارد", "واردة", "تحويل وارد", "Receive", "Received", "Deposit", "Incoming")

    fun operationKindOf(text: String?): OperationKind {
        if (text.isNullOrBlank()) return OperationKind.UNKNOWN
        val k = ArabicText.nameKey(text)
        // check receive first: "تحويل وارد" contains "تحويل"
        if (receiveWords.any { k.contains(ArabicText.nameKey(it)) }) return OperationKind.RECEIVE
        if (sendWords.any { k.contains(ArabicText.nameKey(it)) }) return OperationKind.SEND
        return OperationKind.UNKNOWN
    }

    /** Returns the field a label text refers to, tolerating small OCR errors. */
    fun matchLabel(text: String, minSimilarity: Double = 0.75): Field? {
        val key = ArabicText.nameKey(text.replace(colon, " "))
        if (key.isEmpty() || key.length > 30) return null
        var best: Field? = null
        var bestScore = 0.0
        for ((field, _, lk) in labelKeys) {
            if (lk.isEmpty()) continue
            val score = when {
                key == lk -> 1.0
                lk.length <= 3 -> 0.0 // very short labels ("To", "ID") must match exactly
                else -> ArabicText.similarity(key, lk)
            }
            if (score > bestScore) {
                bestScore = score
                best = field
            }
        }
        return if (bestScore >= minSimilarity) best else null
    }

    private fun isFooter(line: String): Boolean = footerMarkers.any { line.contains(it, ignoreCase = true) }

    /** Splits a line into (field, value) if it starts with a known label. */
    fun splitLine(rawLine: String): Pair<Field, String>? {
        val line = ArabicText.clean(ArabicText.normalizeDigits(rawLine)) ?: return null
        val c = colon.find(line)
        if (c != null) {
            val left = line.substring(0, c.range.first)
            val right = line.substring(c.range.last + 1)
            matchLabel(left)?.let { return it to right.trim() }
            // value printed before the label (visual order)
            matchLabel(right)?.let { return it to left.trim() }
        }
        // no colon (or unknown text before it): try the first 1..3 words as the label
        val words = line.split(' ').filter { it.isNotBlank() }
        for (k in minOf(3, words.size) downTo 1) {
            val candidate = words.take(k).joinToString(" ")
            val field = matchLabel(candidate, minSimilarity = 0.8) ?: continue
            return field to words.drop(k).joinToString(" ")
        }
        return null
    }

    fun parse(text: String, zone: ZoneId = ZoneId.systemDefault()): ParsedReceipt =
        parseLines(text.lines(), zone)

    fun parseLines(lines: List<String>, zone: ZoneId = ZoneId.systemDefault()): ParsedReceipt {
        val values = LinkedHashMap<Field, String>()
        var lastField: Field? = null
        var footerReached = false
        val cleanLines = lines.mapNotNull { ArabicText.clean(it) }

        for (line in cleanLines) {
            if (isFooter(line)) {
                footerReached = true
                lastField = null
                continue
            }
            if (footerReached) continue
            val split = splitLine(line)
            if (split != null && lastField == Field.NOTE && values.containsKey(split.first)) {
                // a note line that happens to start with a label word ("المبلغ المتبقي ...")
                values[Field.NOTE] = listOfNotNull(values[Field.NOTE], line).joinToString(" ").trim()
            } else if (split != null) {
                val (field, value) = split
                if (!values.containsKey(field) || values[field].isNullOrBlank()) values[field] = value
                lastField = field
            } else if (lastField == Field.NOTE) {
                // notes wrap onto several lines
                values[Field.NOTE] = listOfNotNull(values[Field.NOTE], line).joinToString(" ").trim()
            }
        }

        var operationLabel: String? = null
        var reference: String? = values[Field.REFERENCE]?.let { ArabicText.digits(it).ifEmpty { null } }
        values[Field.OPERATION]?.let { op ->
            val opText = ArabicText.normalizeDigits(op)
            if (reference == null) {
                reference = referenceRegex.find(opText)?.groupValues?.get(1) ?: longDigits.find(opText)?.groupValues?.get(1)
            }
            val beforeNumber = opText.split(Regex("[-–—]|رقم|No\\.?|#", RegexOption.IGNORE_CASE)).firstOrNull()
            operationLabel = ArabicText.cleanValue(beforeNumber)
        }
        if (reference == null) {
            // reference printed somewhere else in the header
            val header = cleanLines.takeWhile { !isFooter(it) }.joinToString("\n")
            reference = referenceRegex.find(ArabicText.normalizeDigits(header))?.groupValues?.get(1)
        }

        val date = values[Field.DATE]?.let { DateParser.parse(it) }
            ?: cleanLines.takeWhile { !isFooter(it) }.firstNotNullOfOrNull { DateParser.parse(it) }
        val amount = values[Field.AMOUNT]?.let { AmountParser.parse(it) }
        val fee = values[Field.FEE]?.let { AmountParser.parse(it) }

        return ParsedReceipt(
            operationLabel = operationLabel,
            operationKind = operationKindOf(operationLabel ?: values[Field.OPERATION]),
            reference = reference,
            dateTimeMillis = date?.toMillis(zone),
            hasTime = date?.hasTime ?: false,
            senderName = values[Field.SENDER_NAME]?.let { validName(it) },
            senderAccount = values[Field.SENDER_ACCOUNT]?.let { cleanAccount(it) },
            receiverName = values[Field.RECEIVER_NAME]?.let { validName(it) },
            receiverAccount = values[Field.RECEIVER_ACCOUNT]?.let { cleanAccount(it) },
            amount = amount?.value?.takeIf { it > 0 },
            currency = amount?.currency ?: fee?.currency,
            fee = fee?.value,
            note = values[Field.NOTE]?.let { ArabicText.cleanValue(it) },
            rawText = cleanLines.joinToString("\n"),
        )
    }

    /** A name must contain at least one letter; returns it unchanged otherwise. */
    fun validName(raw: String): String? {
        val n = ArabicText.cleanName(raw) ?: return null
        return n.takeIf { s -> s.count { it.isLetter() } >= 2 }
    }

    /** Masked accounts look like "************3496"; keep digits and mask characters only. */
    fun cleanAccount(raw: String): String? {
        val t = ArabicText.normalizeDigits(ArabicText.stripInvisible(raw))
        val kept = t.filter { it.isDigit() || it == '*' || it == '•' || it == 'x' || it == 'X' }
            .map { if (it == '•' || it == 'x' || it == 'X') '*' else it }.joinToString("")
        return kept.takeIf { s -> s.count { it.isDigit() } >= 3 }
    }
}
