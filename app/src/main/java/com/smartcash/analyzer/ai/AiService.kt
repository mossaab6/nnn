package com.smartcash.analyzer.ai

import com.smartcash.analyzer.data.settings.SettingsRepository
import com.smartcash.analyzer.domain.AmountParser
import com.smartcash.analyzer.domain.ArabicText
import com.smartcash.analyzer.domain.DateParser
import com.smartcash.analyzer.domain.OperationKind
import com.smartcash.analyzer.domain.ParsedReceipt
import com.smartcash.analyzer.domain.ReceiptTextParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.ZoneId

class AiNotConfiguredException : IllegalStateException("لم يتم إدخال مفتاح Grok في الإعدادات")

/** Grok calls with the key/model from settings and automatic recovery when a model is retired. */
class AiService(private val grok: GrokClient, private val settings: SettingsRepository) {

    suspend fun isConfigured(): Boolean = settings.current().aiConfigured

    suspend fun chat(
        messages: List<ChatMsg>,
        tools: JsonArray? = null,
        responseSchema: JsonObject? = null,
        temperature: Double? = null,
    ): ChatReply {
        val s = settings.current()
        if (s.grokApiKey.isBlank()) throw AiNotConfiguredException()
        return try {
            grok.chat(s.grokApiKey, s.grokModel, messages, tools, responseSchema, temperature)
        } catch (e: GrokException) {
            if (!e.isModelProblem) throw e
            val best = runCatching { grok.pickBestModel(grok.listModels(s.grokApiKey)) }.getOrNull()
            if (best == null || best == s.grokModel) throw e
            settings.setGrokModel(best)
            grok.chat(s.grokApiKey, best, messages, tools, responseSchema, temperature)
        }
    }

    /** Verifies the key and model with a tiny request. Returns the model actually used. */
    suspend fun test(apiKey: String, model: String): String {
        return try {
            grok.chat(apiKey, model, listOf(ChatMsg("user", "Reply with the single word: OK")))
            model
        } catch (e: GrokException) {
            if (!e.isModelProblem) throw e
            val best = grok.pickBestModel(grok.listModels(apiKey)) ?: throw e
            grok.chat(apiKey, best, listOf(ChatMsg("user", "Reply with the single word: OK")))
            best
        }
    }

    suspend fun models(apiKey: String): List<ModelInfo> = grok.listModels(apiKey)
}

/** Reads receipts, notifications and free text with Grok, returning exact-as-printed fields. */
class AiExtractor(private val ai: AiService) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class AiReceipt(
        val isReceipt: Boolean,
        val parsed: ParsedReceipt,
        val uncertainFields: List<String>,
        val fullText: String?,
    )

    private val receiptSystemPrompt = """
You are a meticulous OCR and data-extraction engine for receipts of "Sham Cash" (شام كاش), a Syrian mobile wallet.
The receipt is a two-column table in Arabic: labels on the right, values on the left. Typical rows:
"العملية <type> - رقم <number>", "تاريخ العملية", "اسم المرسل", "حساب المرسل", "اسم المستلم", "حساب المستلم", "المبلغ", "الملاحظة".
A footer line "تم إنشاء الملف عبر شام كاش <date>" is the file creation date — never use it as the transaction date.

STRICT RULES FOR NAMES (sender_name, receiver_name) and the note:
- Copy them EXACTLY character by character as printed. Names can be Arabic, English, or usernames with dots,
  underscores, digits or symbols (e.g. "Ahmad.77", "abu_ali", "م.أحمد").
- Keep the original script: Arabic stays Arabic, Latin stays Latin. Never translate, transliterate, correct spelling,
  change letter case, add or remove dots/spaces/hamza (أ/إ/ا), taa marbuta (ة/ه) or alef maqsura (ى/ي).
- If a character is unclear, give your best reading and add the field name to "uncertain_fields".
Other fields:
- operation_label: the operation word exactly as printed (e.g. "إرسال"). operation_type: "send" for sending/transfer out
  (إرسال، تحويل، دفع), "receive" for receiving (استلام، استقبال، إيداع), otherwise "unknown".
- reference: the operation number digits only.
- date: YYYY-MM-DD and time: HH:MM:SS (24h) of the transaction ("تاريخ العملية").
- amount: number only; currency: ISO code ("$" or دولار -> USD, "ل.س" or ليرة سورية -> SYP, € -> EUR, ₺ -> TRY).
- sender_account / receiver_account: the masked account exactly as shown (digits and *).
- note: the note text exactly; join wrapped lines with a single space.
- full_text: a complete verbatim transcription of the receipt, one line per row.
If the image is not a money-transfer receipt, set is_receipt to false.
Reply with JSON only.
""".trimIndent()

    private fun nullable(type: String) = buildJsonObject { put("type", buildJsonArray { add(JsonPrimitive(type)); add(JsonPrimitive("null")) }) }

    private val receiptSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            put("is_receipt", buildJsonObject { put("type", "boolean") })
            put("operation_label", nullable("string"))
            put("operation_type", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray { add(JsonPrimitive("send")); add(JsonPrimitive("receive")); add(JsonPrimitive("unknown")) })
            })
            put("reference", nullable("string"))
            put("date", nullable("string"))
            put("time", nullable("string"))
            put("sender_name", nullable("string"))
            put("sender_account", nullable("string"))
            put("receiver_name", nullable("string"))
            put("receiver_account", nullable("string"))
            put("amount", nullable("number"))
            put("currency", nullable("string"))
            put("fee", nullable("number"))
            put("note", nullable("string"))
            put("uncertain_fields", buildJsonObject { put("type", "array"); putJsonObject("items") { put("type", "string") } })
            put("full_text", buildJsonObject { put("type", "string") })
        }
        put("required", buildJsonArray {
            listOf(
                "is_receipt", "operation_label", "operation_type", "reference", "date", "time", "sender_name",
                "sender_account", "receiver_name", "receiver_account", "amount", "currency", "fee", "note",
                "uncertain_fields", "full_text",
            ).forEach { add(JsonPrimitive(it)) }
        })
    }

    /** Extracts the first JSON object from a model reply (tolerates ```json fences or extra words). */
    fun parseJsonObject(text: String?): JsonObject? {
        if (text.isNullOrBlank()) return null
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return runCatching { json.parseToJsonElement(text.substring(start, end + 1)).jsonObject }.getOrNull()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.trim()?.ifEmpty { null }
        ?.takeUnless { it.equals("null", ignoreCase = true) }

    private fun JsonObject.num(key: String): Double? {
        val p = this[key] as? JsonPrimitive ?: return null
        if (p is JsonNull) return null
        return p.doubleOrNull ?: p.contentOrNull?.let { AmountParser.parse(it)?.value }
    }

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonElement?.strings(): List<String> =
        (this as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

    suspend fun readReceipt(images: List<ByteArray>, zone: ZoneId = ZoneId.systemDefault()): AiReceipt? {
        val reply = ai.chat(
            messages = listOf(
                ChatMsg("system", receiptSystemPrompt),
                ChatMsg("user", "اقرأ هذا الإيصال واستخرج الحقول بدقة تامة.", images = images),
            ),
            responseSchema = receiptSchema,
            temperature = 0.0,
        )
        val o = parseJsonObject(reply.content) ?: return null
        return toAiReceipt(o, zone)
    }

    /** For PDFs with real text, or text pasted by the user. */
    suspend fun readReceiptText(text: String, zone: ZoneId = ZoneId.systemDefault()): AiReceipt? {
        val reply = ai.chat(
            messages = listOf(
                ChatMsg("system", receiptSystemPrompt),
                ChatMsg("user", "هذا نص إيصال (قد يحتوي أخطاء OCR بسيطة). استخرج الحقول وانسخ الأسماء كما هي:\n\n$text"),
            ),
            responseSchema = receiptSchema,
            temperature = 0.0,
        )
        val o = parseJsonObject(reply.content) ?: return null
        return toAiReceipt(o, zone)
    }

    private fun toAiReceipt(o: JsonObject, zone: ZoneId): AiReceipt {
        val date = o.str("date")
        val time = o.str("time")
        val dt = DateParser.parse(listOfNotNull(date, time).joinToString(" "))
        val kind = when (o.str("operation_type")?.lowercase()) {
            "send" -> OperationKind.SEND
            "receive" -> OperationKind.RECEIVE
            else -> ReceiptTextParser.operationKindOf(o.str("operation_label"))
        }
        val parsed = ParsedReceipt(
            operationLabel = ArabicText.cleanValue(o.str("operation_label")),
            operationKind = kind,
            reference = ArabicText.digits(o.str("reference")).ifEmpty { null },
            dateTimeMillis = dt?.toMillis(zone),
            hasTime = dt?.hasTime ?: false,
            senderName = ArabicText.cleanName(o.str("sender_name")),
            senderAccount = o.str("sender_account")?.let { ReceiptTextParser.cleanAccount(it) },
            receiverName = ArabicText.cleanName(o.str("receiver_name")),
            receiverAccount = o.str("receiver_account")?.let { ReceiptTextParser.cleanAccount(it) },
            amount = o.num("amount")?.takeIf { it > 0 },
            currency = AmountParser.normalizeCurrency(o.str("currency")),
            fee = o.num("fee"),
            note = ArabicText.cleanValue(o.str("note")),
            rawText = o.str("full_text"),
        )
        return AiReceipt(
            isReceipt = o.bool("is_receipt") ?: parsed.isComplete,
            parsed = parsed,
            uncertainFields = o["uncertain_fields"].strings(),
            fullText = o.str("full_text"),
        )
    }

    data class AiNotification(
        val isTransaction: Boolean,
        val kind: OperationKind,
        val amount: Double?,
        val currency: String?,
        val counterparty: String?,
        val reference: String?,
        val balanceAfter: Double?,
    )

    suspend fun readNotification(title: String?, text: String?): AiNotification? {
        val prompt = """
Read this mobile notification from the Sham Cash wallet app and decide if it reports a money transaction of the phone owner.
Return JSON: {"is_transaction": bool, "direction": "incoming"|"outgoing"|"unknown", "amount": number|null,
"currency": "USD"|"SYP"|"EUR"|"TRY"|null, "counterparty_name": string|null, "reference": string|null, "balance_after": number|null}.
"incoming" = the owner received money; "outgoing" = the owner sent/paid money.
counterparty_name = the OTHER person's name copied EXACTLY as written (keep dots, digits, Arabic/Latin script; never translate).
Do not treat the remaining balance as the amount.

Title: ${title.orEmpty()}
Text: ${text.orEmpty()}
""".trim()
        val reply = ai.chat(listOf(ChatMsg("user", prompt)), temperature = 0.0)
        val o = parseJsonObject(reply.content) ?: return null
        return AiNotification(
            isTransaction = o.bool("is_transaction") ?: false,
            kind = when (o.str("direction")) {
                "incoming" -> OperationKind.RECEIVE
                "outgoing" -> OperationKind.SEND
                else -> OperationKind.UNKNOWN
            },
            amount = o.num("amount"),
            currency = AmountParser.normalizeCurrency(o.str("currency")),
            counterparty = ArabicText.cleanName(o.str("counterparty_name")),
            reference = ArabicText.digits(o.str("reference")).ifEmpty { null },
            balanceAfter = o.num("balance_after"),
        )
    }

    data class FreeTextTx(
        val type: String?,
        val amount: Double?,
        val currency: String?,
        val senderName: String?,
        val receiverName: String?,
        val dateTimeMillis: Long?,
        val hasTime: Boolean,
        val reference: String?,
        val note: String?,
    )

    /** "أرسلت 50 دولار لأحمد اليوم الساعة 3" -> fields for the manual-entry form. */
    suspend fun readFreeText(text: String, ownerName: String?, nowIso: String, zone: ZoneId = ZoneId.systemDefault()): FreeTextTx? {
        val prompt = """
Extract one money transaction from the user's text (Arabic or English). Today's date/time is $nowIso.
The phone owner is "${ownerName ?: "me"}". Return JSON:
{"type": "sent"|"received"|null, "amount": number|null, "currency": "USD"|"SYP"|"EUR"|"TRY"|null,
 "sender_name": string|null, "receiver_name": string|null, "date": "YYYY-MM-DD"|null, "time": "HH:MM"|null,
 "reference": string|null, "note": string|null}
Copy person names exactly as written (no translation or spelling changes). Resolve relative dates like "أمس" or "اليوم".

Text: $text
""".trim()
        val reply = ai.chat(listOf(ChatMsg("user", prompt)), temperature = 0.0)
        val o = parseJsonObject(reply.content) ?: return null
        val dt = DateParser.parse(listOfNotNull(o.str("date"), o.str("time")).joinToString(" "))
        return FreeTextTx(
            type = o.str("type"),
            amount = o.num("amount"),
            currency = AmountParser.normalizeCurrency(o.str("currency")),
            senderName = ArabicText.cleanName(o.str("sender_name")),
            receiverName = ArabicText.cleanName(o.str("receiver_name")),
            dateTimeMillis = dt?.toMillis(zone),
            hasTime = dt?.hasTime ?: false,
            reference = ArabicText.digits(o.str("reference")).ifEmpty { null },
            note = ArabicText.cleanValue(o.str("note")),
        )
    }
}
