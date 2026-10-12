package com.smartcash.analyzer.domain

/**
 * Best-effort local parser for money notifications (Arabic or English), e.g.
 *  - "تم استلام مبلغ 100 USD من Ahmad.77"
 *  - "لقد أرسلت 50$ إلى أحمد بنجاح. رصيدك الحالي 120$"
 *  - "You received 25 USD from Ali"
 * When this parser is not sure, the app asks Grok to read the notification instead.
 */
object NotificationParser {

    data class Result(
        val kind: OperationKind,
        val amount: Double,
        val currency: String?,
        val counterparty: String?,
        val reference: String?,
        val balanceAfter: Double?,
    ) {
        val isConfident: Boolean get() = kind != OperationKind.UNKNOWN && amount > 0
    }

    // "sent you" style phrases describe money coming IN even though they contain "send" words.
    private val incomingPhrases = listOf(
        "ارسل لك", "أرسل لك", "ارسل اليك", "أرسل إليك", "حول لك", "حوّل لك", "قام بتحويل", "قام بارسال", "قام بإرسال",
        "sent you", "transferred to you",
    )
    private val receiveWords = listOf(
        "استلمت", "استلام", "إستلام", "تم استلام", "وصلك", "وصلتك", "وصلت إليك", "تم إيداع", "تم ايداع", "إيداع", "ايداع",
        "واردة", "وارد", "استقبال", "استقبلت", "received", "credited", "deposit", "incoming",
    )
    private val sendWords = listOf(
        "أرسلت", "ارسلت", "تم إرسال", "تم ارسال", "إرسال", "ارسال", "قمت بتحويل", "حولت", "تم تحويل", "تم خصم", "خصم",
        "صادرة", "صادر", "دفعت", "تم الدفع", "sent", "debited", "transferred", "payment", "paid",
    )

    private val balanceClause = Regex("(?i)(رصيدك(?:\\s+الحالي|\\s+المتاح|\\s+الجديد)?|الرصيد(?:\\s+الحالي|\\s+المتاح|\\s+الجديد)?|(?:new\\s+|available\\s+)?balance)\\s*(?:هو|is)?\\s*[:：]?\\s*([^\\n،,]*\\d[\\d,.]*[^\\n،,]{0,12})")
    private val referenceRegex = Regex("(?i)(?:رقم\\s+العملية|رقم\\s+الحوالة|رقم\\s+المرجع|رقم|ref(?:erence)?|transaction\\s+id|id|#)\\s*[:：]?\\s*(\\d{5,})")

    // name terminators: Arabic comma, comma, newline, " - ", common following words, or a period followed by space/end
    private const val NAME_END = "(?=\\s*(?:،|,|\\n|\\s-\\s|\\sبنجاح|\\sبتاريخ|\\sفي\\s|\\sعلى\\s|\\sرقم|\\sالرصيد|\\sرصيدك|\\sبمبلغ|\\sمبلغ|\\son\\s|\\sat\\s|\\swith|\\sref|\\sbalance|\\.(?:\\s|$)|!|$))"
    // Java's \\b is ASCII-only, so Arabic words use explicit letter look-arounds.
    private val fromRegex = Regex("(?i)(?:(?<!\\p{L})من(?!\\p{L})|\\bfrom\\b)\\s*[:：]?\\s*(.{2,40}?)$NAME_END")
    private val toRegex = Regex("(?i)(?:(?<!\\p{L})(?:إلى|الى|لـ)(?!\\p{L})|\\bto\\b)\\s*[:：]?\\s*(.{2,40}?)$NAME_END")

    private fun has(text: String, words: List<String>): Boolean {
        val k = ArabicText.nameKey(text)
        return words.any { k.contains(ArabicText.nameKey(it)) }
    }

    fun parse(title: String?, text: String?): Result? {
        val full = listOfNotNull(title, text).joinToString("\n")
        if (full.isBlank()) return null
        val t = ArabicText.normalizeDigits(ArabicText.stripInvisible(full))

        // separate the "balance after" amount so it is not taken as the transaction amount
        var balanceAfter: Double? = null
        var body = t
        balanceClause.find(t)?.let { m ->
            balanceAfter = AmountParser.parse(m.groupValues[2])?.value
            body = t.removeRange(m.range)
        }

        val reference = referenceRegex.find(body)?.groupValues?.get(1)
        val bodyNoRef = if (reference != null) body.replace(reference, " ") else body
        val amounts = AmountParser.findAll(bodyNoRef).filter { (_, v) -> v > 0 }
        // skip numbers that are part of a time "15:14" or a date
        val amount = amounts.firstOrNull { (range, _) ->
            val after = bodyNoRef.getOrNull(range.last + 1)
            val before = bodyNoRef.getOrNull(range.first - 1)
            after != ':' && before != ':' && after != '/' && before != '/' && after != '-' && before != '-'
        } ?: return null
        val currency = AmountParser.detectCurrency(bodyNoRef)
        val mentionsMoney = currency != null || has(t, listOf("مبلغ", "حوالة", "تحويل", "رصيد", "amount", "transfer"))
        if (!mentionsMoney) return null

        val kind = when {
            has(t, incomingPhrases) -> OperationKind.RECEIVE
            has(t, receiveWords) && !has(t, listOf("تم إرسال", "تم ارسال", "أرسلت", "ارسلت")) -> OperationKind.RECEIVE
            has(t, sendWords) -> OperationKind.SEND
            else -> OperationKind.UNKNOWN
        }

        val counterparty = when (kind) {
            OperationKind.RECEIVE -> fromRegex.find(body)?.groupValues?.get(1)
            OperationKind.SEND -> toRegex.find(body)?.groupValues?.get(1)
            OperationKind.UNKNOWN -> fromRegex.find(body)?.groupValues?.get(1) ?: toRegex.find(body)?.groupValues?.get(1)
        }?.let { cleanCounterparty(it) }

        return Result(kind, amount.second, currency, counterparty, reference, balanceAfter)
    }

    private fun cleanCounterparty(raw: String): String? {
        var s = ArabicText.cleanName(raw) ?: return null
        // remove a leading "حساب"/"المستخدم" word and trailing currency/amount fragments
        s = s.replace(Regex("^(?:حساب|المستخدم|الحساب|السيد|السيدة|user|account)\\s+", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("\\s*(?:\\d[\\d,.]*\\s*)?(?:USD|SYP|\\$|ل\\.?س|دولار|ليرة)\\s*$", RegexOption.IGNORE_CASE), "")
        s = s.trim()
        if (s.count { it.isLetter() } < 2) return null
        return s
    }
}
