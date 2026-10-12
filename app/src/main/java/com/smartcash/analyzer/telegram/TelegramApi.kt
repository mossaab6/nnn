package com.smartcash.analyzer.telegram

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class TelegramException(val code: Int, message: String) : IOException(message)

data class TgDocument(val fileId: String, val fileName: String?, val mimeType: String?, val fileSize: Long?)

data class TgMessage(
    val messageId: Long,
    val chatId: Long,
    val chatTitle: String?,
    val fromName: String?,
    val text: String?,
    val caption: String?,
    val document: TgDocument?,
    /** largest photo size */
    val photoFileId: String?,
)

data class TgCallback(val id: String, val data: String?, val chatId: Long?, val messageId: Long?, val messageText: String?)

data class TgUpdate(val updateId: Long, val message: TgMessage?, val callback: TgCallback?) {
    val chatId: Long? get() = message?.chatId ?: callback?.chatId
}

data class InlineButton(val text: String, val data: String)

/** Small Telegram Bot API client (https://core.telegram.org/bots/api). */
class TelegramApi(baseHttp: OkHttpClient) {

    private val http = baseHttp.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(70, TimeUnit.SECONDS) // long polling waits up to 50 s
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun url(token: String, method: String) = "https://api.telegram.org/bot$token/$method"

    private suspend fun call(token: String, method: String, body: JsonObject = JsonObject(emptyMap())): JsonElement =
        withContext(Dispatchers.IO) {
            if (token.isBlank()) throw TelegramException(0, "لم يتم إدخال توكن البوت")
            val req = Request.Builder().url(url(token, method)).post(body.toString().toRequestBody(jsonType)).build()
            http.newCall(req).execute().use { res ->
                val text = res.body?.string().orEmpty()
                val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
                val ok = obj?.get("ok")?.jsonPrimitive?.booleanOrNull == true
                if (!ok) {
                    val desc = obj?.get("description")?.jsonPrimitive?.contentOrNull ?: "HTTP ${res.code}"
                    val code = obj?.get("error_code")?.jsonPrimitive?.longOrNull?.toInt() ?: res.code
                    throw TelegramException(code, friendly(code, desc))
                }
                obj!!["result"] ?: JsonObject(emptyMap())
            }
        }

    private fun friendly(code: Int, desc: String) = when (code) {
        401 -> "توكن البوت غير صحيح ($desc)"
        400 -> if (desc.contains("chat not found")) "معرف المحادثة غير صحيح أو لم تبدأ محادثة مع البوت بعد ($desc)" else desc
        403 -> "البوت لا يستطيع مراسلة هذه المحادثة — أرسل /start للبوت أولاً ($desc)"
        409 -> "تعارض: يوجد Webhook مفعّل أو نسخة أخرى تقرأ التحديثات ($desc)"
        429 -> "طلبات كثيرة، انتظر قليلاً ($desc)"
        else -> desc
    }

    suspend fun getMe(token: String): String {
        val r = call(token, "getMe").jsonObject
        return r["username"]?.jsonPrimitive?.contentOrNull ?: r["first_name"]?.jsonPrimitive?.contentOrNull ?: "bot"
    }

    suspend fun getUpdates(token: String, offset: Long?, timeoutSec: Int): List<TgUpdate> {
        val body = buildJsonObject {
            offset?.let { put("offset", it) }
            put("timeout", timeoutSec)
            put("allowed_updates", buildJsonArray { add(JsonPrimitive("message")); add(JsonPrimitive("callback_query")) })
        }
        return (call(token, "getUpdates", body) as? JsonArray).orEmpty().mapNotNull { parseUpdate(it.jsonObject) }
    }

    fun parseUpdate(o: JsonObject): TgUpdate? {
        val id = o["update_id"]?.jsonPrimitive?.longOrNull ?: return null
        val message = (o["message"] ?: o["edited_message"])?.jsonObject?.let { parseMessage(it) }
        val cb = o["callback_query"]?.jsonObject?.let { c ->
            val m = c["message"]?.jsonObject
            TgCallback(
                id = c["id"]?.jsonPrimitive?.contentOrNull ?: return@let null,
                data = c["data"]?.jsonPrimitive?.contentOrNull,
                chatId = m?.get("chat")?.jsonObject?.get("id")?.jsonPrimitive?.longOrNull,
                messageId = m?.get("message_id")?.jsonPrimitive?.longOrNull,
                messageText = m?.get("text")?.jsonPrimitive?.contentOrNull,
            )
        }
        return TgUpdate(id, message, cb)
    }

    private fun parseMessage(m: JsonObject): TgMessage? {
        val chat = m["chat"]?.jsonObject ?: return null
        val from = m["from"]?.jsonObject
        val doc = m["document"]?.jsonObject?.let { d ->
            TgDocument(
                fileId = d["file_id"]?.jsonPrimitive?.contentOrNull ?: return@let null,
                fileName = d["file_name"]?.jsonPrimitive?.contentOrNull,
                mimeType = d["mime_type"]?.jsonPrimitive?.contentOrNull,
                fileSize = d["file_size"]?.jsonPrimitive?.longOrNull,
            )
        }
        val photo = (m["photo"] as? JsonArray)?.lastOrNull()?.jsonObject?.get("file_id")?.jsonPrimitive?.contentOrNull
        return TgMessage(
            messageId = m["message_id"]?.jsonPrimitive?.longOrNull ?: 0,
            chatId = chat["id"]?.jsonPrimitive?.longOrNull ?: return null,
            chatTitle = chat["title"]?.jsonPrimitive?.contentOrNull ?: chat["first_name"]?.jsonPrimitive?.contentOrNull,
            fromName = listOfNotNull(from?.get("first_name")?.jsonPrimitive?.contentOrNull, from?.get("last_name")?.jsonPrimitive?.contentOrNull).joinToString(" ").ifBlank { null },
            text = m["text"]?.jsonPrimitive?.contentOrNull,
            caption = m["caption"]?.jsonPrimitive?.contentOrNull,
            document = doc,
            photoFileId = photo,
        )
    }

    /** Downloads a file sent to the bot (Telegram allows bots to download files up to 20 MB). */
    suspend fun downloadFile(token: String, fileId: String): ByteArray {
        val r = call(token, "getFile", buildJsonObject { put("file_id", fileId) }).jsonObject
        val path = r["file_path"]?.jsonPrimitive?.contentOrNull ?: throw TelegramException(0, "تعذر الحصول على الملف")
        return withContext(Dispatchers.IO) {
            val req = Request.Builder().url("https://api.telegram.org/file/bot$token/$path").get().build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) throw TelegramException(res.code, "فشل تنزيل الملف (${res.code})")
                res.body?.bytes() ?: ByteArray(0)
            }
        }
    }

    private fun keyboard(rows: List<List<InlineButton>>) = buildJsonObject {
        put("inline_keyboard", buildJsonArray {
            for (row in rows) add(buildJsonArray {
                for (b in row) add(buildJsonObject { put("text", b.text); put("callback_data", b.data) })
            })
        })
    }

    /** Sends a plain-text message (split if longer than Telegram's 4096 characters). Returns the last message id. */
    suspend fun sendMessage(token: String, chatId: String, text: String, buttons: List<List<InlineButton>>? = null, replyTo: Long? = null): Long {
        var lastId = 0L
        val parts = text.chunked(3900).ifEmpty { listOf("…") }
        parts.forEachIndexed { i, part ->
            val body = buildJsonObject {
                put("chat_id", chatId)
                put("text", part)
                put("disable_web_page_preview", true)
                if (i == parts.lastIndex && buttons != null) put("reply_markup", keyboard(buttons))
                if (i == 0 && replyTo != null) put("reply_parameters", buildJsonObject { put("message_id", replyTo); put("allow_sending_without_reply", true) })
            }
            lastId = call(token, "sendMessage", body).jsonObject["message_id"]?.jsonPrimitive?.longOrNull ?: 0L
        }
        return lastId
    }

    suspend fun editMessage(token: String, chatId: String, messageId: Long, text: String, buttons: List<List<InlineButton>>? = null) {
        val body = buildJsonObject {
            put("chat_id", chatId)
            put("message_id", messageId)
            put("text", text.take(4000))
            put("disable_web_page_preview", true)
            put("reply_markup", keyboard(buttons.orEmpty()))
        }
        runCatching { call(token, "editMessageText", body) }
    }

    suspend fun answerCallback(token: String, callbackId: String, text: String? = null) {
        runCatching {
            call(token, "answerCallbackQuery", buildJsonObject { put("callback_query_id", callbackId); text?.let { put("text", it.take(190)) } })
        }
    }

    suspend fun sendTyping(token: String, chatId: String) {
        runCatching { call(token, "sendChatAction", buildJsonObject { put("chat_id", chatId); put("action", "typing") }) }
    }

    suspend fun setWebhook(token: String, url: String, secret: String) {
        call(token, "setWebhook", buildJsonObject {
            put("url", url)
            put("secret_token", secret)
            put("allowed_updates", buildJsonArray { add(JsonPrimitive("message")); add(JsonPrimitive("callback_query")) })
            put("drop_pending_updates", false)
        })
    }

    suspend fun deleteWebhook(token: String) {
        call(token, "deleteWebhook", buildJsonObject { put("drop_pending_updates", false) })
    }

    suspend fun webhookUrl(token: String): String? =
        call(token, "getWebhookInfo").jsonObject["url"]?.jsonPrimitive?.contentOrNull?.ifBlank { null }

    suspend fun setCommands(token: String) {
        val cmds = listOf(
            "balance" to "الرصيد والإجماليات",
            "today" to "معاملات اليوم",
            "week" to "معاملات هذا الأسبوع",
            "last" to "آخر 10 معاملات",
            "people" to "الأشخاص وكم أرسلت/استلمت",
            "review" to "معاملات تحتاج مراجعة",
            "help" to "المساعدة",
        )
        runCatching {
            call(token, "setMyCommands", buildJsonObject {
                put("commands", buildJsonArray {
                    cmds.forEach { (c, d) -> add(buildJsonObject { put("command", c); put("description", d) }) }
                })
            })
        }
    }

    /** Recent chats that wrote to the bot (used to detect the chat id). Does not consume updates. */
    suspend fun recentChats(token: String): List<Pair<Long, String>> {
        val updates = (call(token, "getUpdates", buildJsonObject { put("timeout", 0) }) as? JsonArray).orEmpty()
        return updates.mapNotNull { parseUpdate(it.jsonObject)?.message }
            .map { it.chatId to (it.chatTitle ?: it.fromName ?: it.chatId.toString()) }
            .distinctBy { it.first }
            .reversed()
    }
}
