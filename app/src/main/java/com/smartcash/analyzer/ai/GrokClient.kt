package com.smartcash.analyzer.ai

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class GrokException(val code: Int, message: String) : IOException(message) {
    val isModelProblem: Boolean
        get() = code == 404 || (code == 400 && message?.contains("model", ignoreCase = true) == true)
    val isAuthProblem: Boolean get() = code == 401 || code == 403
}

/** One message in an OpenAI-compatible chat. */
data class ChatMsg(
    val role: String,
    val content: String? = null,
    val images: List<ByteArray> = emptyList(),
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
)

data class ToolCall(val id: String, val name: String, val arguments: String)

data class ChatReply(val content: String?, val toolCalls: List<ToolCall>)

data class ModelInfo(val id: String, val supportsImages: Boolean)

/**
 * Minimal client for the xAI (Grok) API, which is OpenAI-compatible:
 * POST https://api.x.ai/v1/chat/completions with "Authorization: Bearer <key>".
 */
class GrokClient(baseHttp: OkHttpClient) {

    companion object {
        const val BASE_URL = "https://api.x.ai/v1"
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val http = baseHttp.newBuilder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun messageJson(m: ChatMsg): JsonObject = buildJsonObject {
        put("role", m.role)
        if (m.images.isNotEmpty()) {
            put("content", buildJsonArray {
                if (!m.content.isNullOrEmpty()) add(buildJsonObject { put("type", "text"); put("text", m.content) })
                for (img in m.images) {
                    val b64 = Base64.encodeToString(img, Base64.NO_WRAP)
                    add(buildJsonObject {
                        put("type", "image_url")
                        put("image_url", buildJsonObject {
                            put("url", "data:image/jpeg;base64,$b64")
                            put("detail", "high")
                        })
                    })
                }
            })
        } else if (m.content != null) {
            put("content", m.content)
        } else if (m.toolCalls.isNotEmpty()) {
            put("content", "")
        }
        if (m.toolCalls.isNotEmpty()) {
            put("tool_calls", buildJsonArray {
                for (tc in m.toolCalls) add(buildJsonObject {
                    put("id", tc.id)
                    put("type", "function")
                    put("function", buildJsonObject { put("name", tc.name); put("arguments", tc.arguments) })
                })
            })
        }
        m.toolCallId?.let { put("tool_call_id", it) }
    }

    /**
     * Sends a chat completion. [responseSchema] requests structured JSON output; [tools] enables function calling.
     * If the API rejects optional parameters (older/newer models differ), the call is retried without them.
     */
    suspend fun chat(
        apiKey: String,
        model: String,
        messages: List<ChatMsg>,
        tools: JsonArray? = null,
        responseSchema: JsonObject? = null,
        temperature: Double? = null,
    ): ChatReply = withContext(Dispatchers.IO) {
        fun body(minimal: Boolean): JsonObject = buildJsonObject {
            put("model", model)
            put("messages", JsonArray(messages.map { messageJson(it) }))
            if (tools != null && tools.isNotEmpty()) {
                put("tools", tools)
                put("tool_choice", "auto")
            }
            if (!minimal) {
                temperature?.let { put("temperature", it) }
                responseSchema?.let { schema ->
                    put("response_format", buildJsonObject {
                        put("type", "json_schema")
                        put("json_schema", buildJsonObject {
                            put("name", "result")
                            put("schema", schema)
                        })
                    })
                }
            }
        }
        val first = runCatching { post(apiKey, "/chat/completions", body(false)) }
        val response = first.getOrElse { e ->
            if (e is GrokException && e.code == 400 && (temperature != null || responseSchema != null) && !e.isModelProblem) {
                post(apiKey, "/chat/completions", body(true))
            } else throw e
        }
        parseReply(response)
    }

    private fun parseReply(obj: JsonObject): ChatReply {
        val msg = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            ?: throw GrokException(500, "رد غير متوقع من Grok")
        val content = when (val c = msg["content"]) {
            is JsonPrimitive -> c.contentOrNull
            is JsonArray -> c.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }.joinToString("")
            else -> null
        }
        val calls = msg["tool_calls"]?.let { el ->
            (el as? JsonArray)?.mapNotNull { tc ->
                val o = tc.jsonObject
                val fn = o["function"]?.jsonObject ?: return@mapNotNull null
                ToolCall(
                    id = o["id"]?.jsonPrimitive?.contentOrNull ?: "call_${System.nanoTime()}",
                    name = fn["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                    arguments = when (val a = fn["arguments"]) {
                        is JsonPrimitive -> a.contentOrNull ?: "{}"
                        is JsonObject -> a.toString()
                        else -> "{}"
                    },
                )
            }
        }.orEmpty()
        return ChatReply(content, calls)
    }

    private fun post(apiKey: String, path: String, body: JsonElement): JsonObject {
        val req = Request.Builder()
            .url(BASE_URL + path)
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw GrokException(res.code, errorMessage(res.code, text))
            return json.parseToJsonElement(text).jsonObject
        }
    }

    private fun get(apiKey: String, path: String): JsonObject {
        val req = Request.Builder().url(BASE_URL + path).header("Authorization", "Bearer $apiKey").get().build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw GrokException(res.code, errorMessage(res.code, text))
            return json.parseToJsonElement(text).jsonObject
        }
    }

    private fun errorMessage(code: Int, body: String): String {
        val detail = runCatching {
            val o = json.parseToJsonElement(body).jsonObject
            (o["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                ?: o["error"]?.jsonPrimitive?.contentOrNull
                ?: o["message"]?.jsonPrimitive?.contentOrNull
        }.getOrNull() ?: body.take(300)
        val hint = when (code) {
            401, 403 -> "مفتاح Grok غير صحيح أو لا يملك صلاحية"
            404 -> "النموذج غير موجود"
            429 -> "تم تجاوز حد الاستخدام أو نفد الرصيد في حساب xAI"
            in 500..599 -> "خطأ مؤقت في خوادم xAI"
            else -> "خطأ من Grok"
        }
        return "$hint ($code): $detail"
    }

    /** Lists models available to the key; prefers /language-models which also reports image support. */
    suspend fun listModels(apiKey: String): List<ModelInfo> = withContext(Dispatchers.IO) {
        runCatching {
            get(apiKey, "/language-models")["models"]?.jsonArray?.mapNotNull { el ->
                val o = el.jsonObject
                val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val inputs = (o["input_modalities"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                ModelInfo(id, "image" in inputs)
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: run {
            get(apiKey, "/models")["data"]?.jsonArray?.mapNotNull { el ->
                el.jsonObject["id"]?.jsonPrimitive?.contentOrNull?.let { ModelInfo(it, supportsImages = !it.contains("mini")) }
            }.orEmpty()
        }
    }

    /** Picks the newest general Grok chat model, preferring ones that accept images. */
    fun pickBestModel(models: List<ModelInfo>): String? {
        val version = Regex("grok-(\\d+)(?:[.-](\\d+))?")
        return models
            .filter { it.id.startsWith("grok") && listOf("imagine", "image-gen", "video", "tts", "embed").none { bad -> it.id.contains(bad) } }
            .sortedWith(
                compareByDescending<ModelInfo> { it.supportsImages }
                    .thenByDescending { m ->
                        version.find(m.id)?.let { (it.groupValues[1].toIntOrNull() ?: 0) * 100 + (it.groupValues[2].toIntOrNull() ?: 0) } ?: 0
                    }
                    .thenBy { m -> if (m.id.contains("fast") || m.id.contains("mini")) 1 else 0 }
                    .thenBy { it.id.length }
            )
            .firstOrNull()?.id
    }
}
