package com.smartcash.analyzer.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.smartcash.analyzer.domain.BalanceAnchor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

enum class ThemeAccent(val label: String) {
    GREEN("أخضر"),
    BLUE("أزرق"),
    PURPLE("بنفسجي"),
    GOLD("ذهبي"),
}

enum class ThemeMode(val label: String) {
    DARK("داكن"),
    LIGHT("فاتح"),
    SYSTEM("حسب النظام"),
}

/** How PDFs and messages travel from Telegram to the app. */
enum class RelayMode(val label: String) {
    DIRECT("مباشر عبر Telegram (موصى به)"),
    RELAY("Cloudflare Worker (اختياري)"),
}

data class AppSettings(
    val onboarded: Boolean = false,
    val balances: Map<String, BalanceAnchor> = emptyMap(),
    val primaryCurrency: String = "USD",
    val ownerNames: List<String> = emptyList(),
    val ownerAccounts: List<String> = emptyList(),
    val accent: ThemeAccent = ThemeAccent.GREEN,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val pureBlack: Boolean = true,
    val telegramToken: String = "",
    val telegramChatId: String = "",
    val telegramOffset: Long = 0,
    val botServiceEnabled: Boolean = false,
    val notifyTelegramOnAuto: Boolean = true,
    val grokApiKey: String = "",
    val grokModel: String = DEFAULT_GROK_MODEL,
    val relayMode: RelayMode = RelayMode.DIRECT,
    val relayUrl: String = "",
    val relaySecret: String = "",
    val relayCursor: Long = 0,
    val notifPackages: List<String> = emptyList(),
    val notifAutoAdd: Boolean = true,
    val recentNotifSources: List<String> = emptyList(),
    val aiCrossCheck: Boolean = true,
) {
    val telegramConfigured: Boolean get() = telegramToken.isNotBlank()
    val aiConfigured: Boolean get() = grokApiKey.isNotBlank()
    val relayConfigured: Boolean get() = relayMode == RelayMode.RELAY && relayUrl.isNotBlank() && relaySecret.isNotBlank()

    fun isShamCashPackage(pkg: String): Boolean {
        val p = pkg.lowercase()
        return pkg in notifPackages || p.contains("shamcash") || p.contains("sham.cash") || p.contains("sham_cash")
    }

    companion object {
        const val DEFAULT_GROK_MODEL = "grok-4.7"
    }
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Serializable
private data class StoredAnchor(val amount: Double, val anchor: Long)

class SettingsRepository(private val context: Context) {

    private object K {
        val onboarded = booleanPreferencesKey("onboarded")
        val balances = stringPreferencesKey("balances")
        val primaryCurrency = stringPreferencesKey("primary_currency")
        val ownerNames = stringPreferencesKey("owner_names")
        val ownerAccounts = stringPreferencesKey("owner_accounts")
        val accent = stringPreferencesKey("accent")
        val themeMode = stringPreferencesKey("theme_mode")
        val pureBlack = booleanPreferencesKey("pure_black")
        val telegramToken = stringPreferencesKey("telegram_token")
        val telegramChatId = stringPreferencesKey("telegram_chat_id")
        val telegramOffset = longPreferencesKey("telegram_offset")
        val botService = booleanPreferencesKey("bot_service")
        val notifyTelegramOnAuto = booleanPreferencesKey("notify_telegram_on_auto")
        val grokKey = stringPreferencesKey("grok_key")
        val grokModel = stringPreferencesKey("grok_model")
        val relayMode = stringPreferencesKey("relay_mode")
        val relayUrl = stringPreferencesKey("relay_url")
        val relaySecret = stringPreferencesKey("relay_secret")
        val relayCursor = longPreferencesKey("relay_cursor")
        val notifPackages = stringPreferencesKey("notif_packages")
        val notifAutoAdd = booleanPreferencesKey("notif_auto_add")
        val recentNotifSources = stringPreferencesKey("recent_notif_sources")
        val aiCrossCheck = booleanPreferencesKey("ai_cross_check")
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val listSer = ListSerializer(String.serializer())
    private val anchorsSer = MapSerializer(String.serializer(), StoredAnchor.serializer())

    private fun list(raw: String?): List<String> =
        raw?.let { runCatching { json.decodeFromString(listSer, it) }.getOrNull() } ?: emptyList()

    private fun listJson(v: List<String>) = json.encodeToString(listSer, v)

    val flow: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            onboarded = p[K.onboarded] ?: false,
            balances = p[K.balances]?.let { raw ->
                runCatching { json.decodeFromString(anchorsSer, raw) }.getOrNull()
                    ?.mapValues { BalanceAnchor(it.value.amount, it.value.anchor) }
            } ?: emptyMap(),
            primaryCurrency = p[K.primaryCurrency] ?: "USD",
            ownerNames = list(p[K.ownerNames]),
            ownerAccounts = list(p[K.ownerAccounts]),
            accent = p[K.accent]?.let { runCatching { ThemeAccent.valueOf(it) }.getOrNull() } ?: ThemeAccent.GREEN,
            themeMode = p[K.themeMode]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.DARK,
            pureBlack = p[K.pureBlack] ?: true,
            telegramToken = SecretCipher.decrypt(p[K.telegramToken]),
            telegramChatId = p[K.telegramChatId] ?: "",
            telegramOffset = p[K.telegramOffset] ?: 0L,
            botServiceEnabled = p[K.botService] ?: false,
            notifyTelegramOnAuto = p[K.notifyTelegramOnAuto] ?: true,
            grokApiKey = SecretCipher.decrypt(p[K.grokKey]),
            grokModel = p[K.grokModel]?.takeIf { it.isNotBlank() } ?: AppSettings.DEFAULT_GROK_MODEL,
            relayMode = p[K.relayMode]?.let { runCatching { RelayMode.valueOf(it) }.getOrNull() } ?: RelayMode.DIRECT,
            relayUrl = p[K.relayUrl] ?: "",
            relaySecret = SecretCipher.decrypt(p[K.relaySecret]),
            relayCursor = p[K.relayCursor] ?: 0L,
            notifPackages = list(p[K.notifPackages]),
            notifAutoAdd = p[K.notifAutoAdd] ?: true,
            recentNotifSources = list(p[K.recentNotifSources]),
            aiCrossCheck = p[K.aiCrossCheck] ?: true,
        )
    }.distinctUntilChanged()

    suspend fun current(): AppSettings = flow.first()

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }

    suspend fun completeOnboarding(balances: Map<String, Double>, ownerName: String?, ownerAccount: String?) {
        val now = System.currentTimeMillis()
        edit { p ->
            p[K.balances] = json.encodeToString(anchorsSer, balances.mapValues { StoredAnchor(it.value, now) })
            balances.keys.firstOrNull()?.let { p[K.primaryCurrency] = it }
            ownerName?.trim()?.takeIf { it.isNotEmpty() }?.let { p[K.ownerNames] = listJson(listOf(it)) }
            ownerAccount?.trim()?.takeIf { it.isNotEmpty() }?.let { p[K.ownerAccounts] = listJson(listOf(it)) }
            p[K.onboarded] = true
        }
    }

    /** Sets the current balance of [currency]; transactions after now will move it. */
    suspend fun setBalance(currency: String, amount: Double, anchorTime: Long = System.currentTimeMillis()) {
        val cur = current().balances.mapValues { StoredAnchor(it.value.amount, it.value.anchorTime) }.toMutableMap()
        cur[currency] = StoredAnchor(amount, anchorTime)
        edit { it[K.balances] = json.encodeToString(anchorsSer, cur) }
    }

    suspend fun removeBalance(currency: String) {
        val cur = current().balances.mapValues { StoredAnchor(it.value.amount, it.value.anchorTime) }.toMutableMap()
        cur.remove(currency)
        edit { it[K.balances] = json.encodeToString(anchorsSer, cur) }
    }

    suspend fun setPrimaryCurrency(c: String) = edit { it[K.primaryCurrency] = c }

    suspend fun setOwnerNames(names: List<String>) =
        edit { it[K.ownerNames] = listJson(names.map { n -> n.trim() }.filter { n -> n.isNotEmpty() }.distinct()) }

    suspend fun addOwnerName(name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        val cur = current().ownerNames
        if (cur.none { it.equals(n, ignoreCase = true) }) setOwnerNames(cur + n)
    }

    suspend fun setOwnerAccounts(accounts: List<String>) =
        edit { it[K.ownerAccounts] = listJson(accounts.map { a -> a.trim() }.filter { a -> a.isNotEmpty() }.distinct()) }

    suspend fun setTheme(accent: ThemeAccent? = null, mode: ThemeMode? = null, pureBlack: Boolean? = null) = edit { p ->
        accent?.let { p[K.accent] = it.name }
        mode?.let { p[K.themeMode] = it.name }
        pureBlack?.let { p[K.pureBlack] = it }
    }

    suspend fun setTelegram(token: String, chatId: String) = edit { p ->
        val old = SecretCipher.decrypt(p[K.telegramToken])
        p[K.telegramToken] = SecretCipher.encrypt(token.trim())
        p[K.telegramChatId] = chatId.trim()
        if (old != token.trim()) p[K.telegramOffset] = 0L
    }

    suspend fun setTelegramChatId(chatId: String) = edit { it[K.telegramChatId] = chatId.trim() }
    suspend fun setTelegramOffset(offset: Long) = edit { it[K.telegramOffset] = offset }
    suspend fun setBotServiceEnabled(v: Boolean) = edit { it[K.botService] = v }
    suspend fun setNotifyTelegramOnAuto(v: Boolean) = edit { it[K.notifyTelegramOnAuto] = v }

    suspend fun setGrok(key: String, model: String) = edit { p ->
        p[K.grokKey] = SecretCipher.encrypt(key.trim())
        p[K.grokModel] = model.trim().ifEmpty { AppSettings.DEFAULT_GROK_MODEL }
    }

    suspend fun setGrokModel(model: String) = edit { it[K.grokModel] = model.trim() }

    suspend fun setRelay(mode: RelayMode, url: String, secret: String) = edit { p ->
        p[K.relayMode] = mode.name
        p[K.relayUrl] = url.trim().trimEnd('/')
        p[K.relaySecret] = SecretCipher.encrypt(secret.trim())
    }

    suspend fun setRelayCursor(c: Long) = edit { it[K.relayCursor] = c }

    suspend fun setNotifPackages(pkgs: List<String>) =
        edit { it[K.notifPackages] = listJson(pkgs.map { p -> p.trim() }.filter { p -> p.isNotEmpty() }.distinct()) }

    suspend fun setNotifAutoAdd(v: Boolean) = edit { it[K.notifAutoAdd] = v }
    suspend fun setAiCrossCheck(v: Boolean) = edit { it[K.aiCrossCheck] = v }

    suspend fun addRecentNotifSource(pkg: String) {
        val cur = current().recentNotifSources
        if (pkg in cur) return
        edit { it[K.recentNotifSources] = listJson((listOf(pkg) + cur).take(15)) }
    }

    suspend fun resetAll() = edit { it.clear() }
}
