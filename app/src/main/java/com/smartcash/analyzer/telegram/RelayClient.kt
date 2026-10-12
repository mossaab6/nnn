package com.smartcash.analyzer.telegram

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Optional Cloudflare Worker relay (see /cloud-relay). Telegram pushes every update to the worker via a
 * webhook, the worker keeps it in a free D1 database until the app pulls it — so nothing is lost even if
 * the phone stays offline for days (Telegram itself only keeps undelivered updates for 24 hours).
 */
class RelayClient(baseHttp: OkHttpClient) {

    data class Pulled(val id: Long, val update: TgUpdate)

    private val http = baseHttp.newBuilder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun req(base: String, path: String, secret: String) =
        Request.Builder().url(base.trimEnd('/') + path).header("Authorization", "Bearer $secret")

    suspend fun pull(base: String, secret: String, after: Long, api: TelegramApi): List<Pulled> = withContext(Dispatchers.IO) {
        http.newCall(req(base, "/pull?after=$after&limit=50", secret).get().build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IOException(errorText(res.code, text))
            val arr = json.parseToJsonElement(text).jsonObject["updates"] as? JsonArray ?: return@use emptyList<Pulled>()
            arr.mapNotNull { el ->
                val o = el.jsonObject
                val id = o["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                val upd = (o["update"] as? JsonObject)?.let { api.parseUpdate(it) } ?: return@mapNotNull Pulled(id, TgUpdate(-1, null, null))
                Pulled(id, upd)
            }
        }
    }

    suspend fun ack(base: String, secret: String, upTo: Long) = withContext(Dispatchers.IO) {
        val body = buildJsonObject { put("upTo", upTo) }.toString().toRequestBody(jsonType)
        http.newCall(req(base, "/ack", secret).post(body).build()).execute().use { res ->
            if (!res.isSuccessful) throw IOException(errorText(res.code, res.body?.string().orEmpty()))
        }
    }

    /** Returns the number of updates waiting in the relay. */
    suspend fun health(base: String, secret: String): Long = withContext(Dispatchers.IO) {
        http.newCall(req(base, "/health", secret).get().build()).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IOException(errorText(res.code, text))
            json.parseToJsonElement(text).jsonObject["pending"]?.jsonPrimitive?.longOrNull ?: 0L
        }
    }

    private fun errorText(code: Int, body: String): String {
        val msg = runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        return when (code) {
            401, 403 -> "مفتاح Relay غير صحيح (يجب أن يطابق RELAY_SECRET في Cloudflare)"
            404 -> "رابط Relay غير صحيح"
            else -> "خطأ من Relay ($code): ${msg ?: body.take(200)}"
        }
    }

    companion object {
        /** Random secret using only characters Telegram accepts in webhook secret tokens. */
        fun generateSecret(length: Int = 40): String {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            val rnd = SecureRandom()
            return (1..length).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
        }

        fun isValidSecret(s: String) = s.length in 16..256 && s.all { it.isLetterOrDigit() && it.code < 128 || it == '_' || it == '-' }
    }
}
