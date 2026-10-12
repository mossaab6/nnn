package com.smartcash.analyzer.telegram

import android.util.Log
import com.smartcash.analyzer.ai.ActionExecutor
import com.smartcash.analyzer.ai.AiNotConfiguredException
import com.smartcash.analyzer.ai.AssistantEngine
import com.smartcash.analyzer.analyze.ReceiptImporter
import com.smartcash.analyzer.data.TransactionRepository
import com.smartcash.analyzer.data.db.TransactionEntity
import com.smartcash.analyzer.data.settings.AppSettings
import com.smartcash.analyzer.data.settings.RelayMode
import com.smartcash.analyzer.data.settings.SettingsRepository
import com.smartcash.analyzer.domain.ArabicText
import com.smartcash.analyzer.domain.Money
import com.smartcash.analyzer.domain.Period
import com.smartcash.analyzer.domain.Periods
import com.smartcash.analyzer.domain.SummaryCalculator
import com.smartcash.analyzer.domain.TxFormat
import com.smartcash.analyzer.domain.TxSource
import com.smartcash.analyzer.domain.TxType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class BotStatus(
    val lastSyncAt: Long? = null,
    val lastError: String? = null,
    val processed: Int = 0,
    val serviceRunning: Boolean = false,
)

/**
 * Processes Telegram updates for the owner's chat: receipts (PDF/photos) are analysed and saved,
 * text goes to the AI assistant, and inline buttons confirm or cancel proposed actions.
 * Updates come either straight from Telegram (getUpdates) or from the optional Cloudflare relay.
 */
class BotEngine(
    private val settings: SettingsRepository,
    private val api: TelegramApi,
    private val relay: RelayClient,
    private val importer: ReceiptImporter,
    private val assistant: AssistantEngine,
    private val actions: ActionExecutor,
    private val repo: TransactionRepository,
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow(BotStatus())
    val status: StateFlow<BotStatus> = _status

    fun setServiceRunning(running: Boolean) {
        _status.value = _status.value.copy(serviceRunning = running)
    }

    /**
     * Fetches and handles pending updates once. [longPollSeconds] > 0 keeps the request open until a
     * message arrives (used by the background service). Returns the number of updates handled, or -1
     * if another sync is already running.
     */
    suspend fun syncOnce(longPollSeconds: Int = 0): Int {
        if (!mutex.tryLock()) return -1
        try {
            val s = settings.current()
            if (!s.telegramConfigured || s.telegramChatId.isBlank()) return 0
            val n = if (s.relayMode == RelayMode.RELAY && s.relayConfigured) syncRelay(s) else syncDirect(s, longPollSeconds)
            _status.value = _status.value.copy(lastSyncAt = System.currentTimeMillis(), lastError = null, processed = _status.value.processed + n)
            return n
        } catch (e: Exception) {
            _status.value = _status.value.copy(lastSyncAt = System.currentTimeMillis(), lastError = e.message ?: e.javaClass.simpleName)
            throw e
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun syncDirect(s: AppSettings, longPollSeconds: Int): Int {
        val updates = try {
            api.getUpdates(s.telegramToken, s.telegramOffset.takeIf { it > 0 }, longPollSeconds)
        } catch (e: TelegramException) {
            if (e.code == 409) {
                // a webhook (relay) is still registered: direct mode needs it removed
                api.deleteWebhook(s.telegramToken)
                return 0
            }
            throw e
        }
        for (u in updates) {
            runCatching { handle(u, s) }.onFailure { Log.w(TAG, "update ${u.updateId} failed", it) }
            settings.setTelegramOffset(u.updateId + 1)
        }
        return updates.size
    }

    private suspend fun syncRelay(s: AppSettings): Int {
        val pulled = relay.pull(s.relayUrl, s.relaySecret, s.relayCursor, api)
        for (p in pulled) {
            if (p.update.updateId >= 0) runCatching { handle(p.update, s) }.onFailure { Log.w(TAG, "relay update failed", it) }
            settings.setRelayCursor(p.id)
        }
        if (pulled.isNotEmpty()) runCatching { relay.ack(s.relayUrl, s.relaySecret, pulled.last().id) }
        return pulled.size
    }

    private suspend fun handle(u: TgUpdate, s: AppSettings) {
        // Only the owner's chat may use the bot; everyone else is ignored.
        if (u.chatId?.toString() != s.telegramChatId.trim()) return
        val token = s.telegramToken
        val chat = s.telegramChatId
        u.callback?.let { handleCallback(token, chat, it); return }
        val m = u.message ?: return
        when {
            m.document != null -> handleFile(token, chat, m, m.document.fileId, m.document.fileName, m.document.fileSize)
            m.photoFileId != null -> handleFile(token, chat, m, m.photoFileId, "photo.jpg", null)
            !m.text.isNullOrBlank() -> handleText(token, chat, m, m.text)
        }
    }

    // ------------------------------------------------------------------ files

    private suspend fun handleFile(token: String, chat: String, m: TgMessage, fileId: String, fileName: String?, size: Long?) {
        if (size != null && size > 20L * 1024 * 1024) {
            api.sendMessage(token, chat, "⚠️ الملف أكبر من 20 ميغابايت، لا يسمح تيليجرام للبوت بتنزيله.", replyTo = m.messageId)
            return
        }
        val progressId = api.sendMessage(token, chat, "⏳ جاري تحليل الإيصال…", replyTo = m.messageId)
        val outcome = try {
            val bytes = api.downloadFile(token, fileId)
            importer.import(bytes, fileName, TxSource.TELEGRAM)
        } catch (e: Exception) {
            api.editMessage(token, chat, progressId, "❌ تعذر تحليل الملف: ${e.message ?: e.javaClass.simpleName}")
            return
        }
        val tx = outcome.transactionId?.let { repo.get(it) }
        api.editMessage(token, chat, progressId, outcome.message, buttonsFor(tx, outcome.ownerCandidate))
    }

    private suspend fun buttonsFor(tx: TransactionEntity?, ownerCandidate: String?): List<List<InlineButton>> {
        val rows = ArrayList<List<InlineButton>>()
        if (tx != null && tx.needsReview) {
            rows += listOf(
                InlineButton("↗️ إرسال", "d:${tx.id}:s"),
                InlineButton("↙️ استلام", "d:${tx.id}:r"),
                InlineButton("✔️ صحيحة", "v:${tx.id}"),
            )
        }
        if (ownerCandidate != null) {
            val a = actions.propose(
                AssistantEngine.CHANNEL_TELEGRAM, ActionExecutor.Kind.SET_OWNER_NAME,
                buildJsonObject { put("name", ownerCandidate) }, "حفظ «$ownerCandidate» كاسمك في شام كاش",
            )
            rows += listOf(InlineButton("👤 نعم، أنا ${ownerCandidate.take(24)}", "a:${a.id}:y"))
        }
        return rows
    }

    // ------------------------------------------------------------------ callbacks

    private suspend fun handleCallback(token: String, chat: String, cb: TgCallback) {
        val data = cb.data.orEmpty()
        val parts = data.split(':')
        val original = cb.messageText.orEmpty()
        val result: String = when (parts.getOrNull(0)) {
            "a" -> {
                val id = parts.getOrNull(1)?.toLongOrNull() ?: return
                if (parts.getOrNull(2) == "y") actions.confirm(id) else actions.cancel(id)
            }
            "d" -> {
                val id = parts.getOrNull(1)?.toLongOrNull() ?: return
                val type = if (parts.getOrNull(2) == "r") TxType.RECEIVED else TxType.SENT
                repo.setDirection(id, type)
                val t = repo.get(id)
                if (t != null) {
                    cb.messageId?.let { api.editMessage(token, chat, it, "✅ تم التعديل\n" + ReceiptImporter.describe(t)) }
                    api.answerCallback(token, cb.id, "تم")
                    return
                }
                "المعاملة غير موجودة"
            }
            "v" -> {
                val id = parts.getOrNull(1)?.toLongOrNull() ?: return
                repo.markReviewed(id)
                "✔️ تم تأكيد صحة المعاملة"
            }
            else -> return
        }
        cb.messageId?.let { api.editMessage(token, chat, it, "$original\n\n$result") }
        api.answerCallback(token, cb.id, result)
    }

    // ------------------------------------------------------------------ text

    private suspend fun handleText(token: String, chat: String, m: TgMessage, text: String) {
        val cmd = text.trim().takeIf { it.startsWith("/") }?.substringBefore(' ')?.substringBefore('@')?.lowercase()
        when (cmd) {
            "/start", "/help" -> api.sendMessage(token, chat, HELP)
            "/balance" -> api.sendMessage(token, chat, balanceText())
            "/today" -> api.sendMessage(token, chat, periodText(Period.TODAY))
            "/week" -> api.sendMessage(token, chat, periodText(Period.WEEK))
            "/last" -> api.sendMessage(token, chat, lastText())
            "/people" -> api.sendMessage(token, chat, peopleText())
            "/review" -> reviewList(token, chat)
            else -> askAssistant(token, chat, m, text)
        }
    }

    private suspend fun askAssistant(token: String, chat: String, m: TgMessage, text: String) {
        api.sendTyping(token, chat)
        val reply = try {
            assistant.ask(AssistantEngine.CHANNEL_TELEGRAM, text)
        } catch (e: AiNotConfiguredException) {
            api.sendMessage(token, chat, "🤖 لتفعيل المحادثة الذكية أضف مفتاح Grok من إعدادات التطبيق.\n\n$HELP", replyTo = m.messageId)
            return
        } catch (e: Exception) {
            api.sendMessage(token, chat, "❌ ${e.message ?: "حدث خطأ"}", replyTo = m.messageId)
            return
        }
        api.sendMessage(token, chat, reply.text, replyTo = m.messageId)
        for (id in reply.actionIds) {
            val a = actions.get(id) ?: continue
            val msgId = api.sendMessage(
                token, chat, "⚠️ يحتاج تأكيدك:\n${a.description}",
                listOf(listOf(InlineButton("✅ تأكيد", "a:$id:y"), InlineButton("❌ إلغاء", "a:$id:n"))),
            )
            actions.setTelegramMessage(id, msgId)
        }
    }

    private suspend fun balanceText(): String {
        val s = settings.current()
        val txs = repo.all()
        val lites = txs.map { it.toLite() }
        val currencies = (txs.map { it.currency } + s.balances.keys + s.primaryCurrency).distinct()
        return buildString {
            append("💰 ملخص المحفظة\n")
            for (c in currencies) {
                val all = SummaryCalculator.summarize(lites, c, Period.ALL, s.balances[c])
                val today = SummaryCalculator.summarize(lites, c, Period.TODAY, s.balances[c])
                val week = SummaryCalculator.summarize(lites, c, Period.WEEK, s.balances[c])
                append("\n— ${Money.displayName(c)} —\n")
                append("الرصيد الحالي: ").append(all.balance?.let { ArabicText.ltr(Money.withCurrency(it, c)) } ?: "غير محدد").append('\n')
                append("اليوم: ↙️ ${ArabicText.ltr(Money.withCurrency(today.received, c))}  ↗️ ${ArabicText.ltr(Money.withCurrency(today.sent, c))}\n")
                append("الأسبوع: ↙️ ${ArabicText.ltr(Money.withCurrency(week.received, c))}  ↗️ ${ArabicText.ltr(Money.withCurrency(week.sent, c))}\n")
                append("دائماً: ↙️ ${ArabicText.ltr(Money.withCurrency(all.received, c))}  ↗️ ${ArabicText.ltr(Money.withCurrency(all.sent, c))}\n")
            }
        }
    }

    private fun line(t: TransactionEntity): String {
        val arrow = if (t.type == TxType.SENT) "↗️" else "↙️"
        return "$arrow ${ArabicText.ltr(Money.signed(t.amount, t.currency, t.type))} ${t.displayName} · ${TxFormat.relative(t.timestamp, t.hasTime)}"
    }

    private suspend fun periodText(p: Period): String {
        val from = Periods.startOf(p, System.currentTimeMillis())
        val list = repo.all().filter { it.timestamp >= from }
        if (list.isEmpty()) return "لا توجد معاملات ${p.label}."
        return buildString {
            append("📋 معاملات ${p.label} (${list.size})\n\n")
            list.take(25).forEach { append(line(it)).append('\n') }
            for ((c, l) in list.groupBy { it.currency }) {
                append("\nالمجموع ${Money.symbol(c)}: ↙️ ${ArabicText.ltr(Money.withCurrency(l.filter { it.type == TxType.RECEIVED }.sumOf { it.amount }, c))}")
                append("  ↗️ ${ArabicText.ltr(Money.withCurrency(l.filter { it.type == TxType.SENT }.sumOf { it.amount }, c))}")
            }
        }
    }

    private suspend fun lastText(): String {
        val list = repo.all().take(10)
        if (list.isEmpty()) return "لا توجد معاملات بعد. أرسل لي ملف PDF لإيصال شام كاش."
        return "🕒 آخر المعاملات\n\n" + list.joinToString("\n") { line(it) }
    }

    private suspend fun peopleText(): String {
        val s = settings.current()
        val people = repo.summarizePeople(repo.allPeople(), repo.all())
            .sortedWith(compareByDescending<com.smartcash.analyzer.data.PersonSummary> { it.person.pinned }.thenByDescending { it.lastTimestamp ?: 0L })
        if (people.isEmpty()) return "لا يوجد أشخاص بعد."
        return buildString {
            append("👥 الأشخاص (${people.size})\n\n")
            people.take(30).forEach { p ->
                if (p.person.pinned) append("📌 ")
                append(p.person.displayName).append('\n')
                val totals = p.totals.ifEmpty { mapOf(s.primaryCurrency to (0.0 to 0.0)) }
                totals.forEach { (c, v) ->
                    append("   ↗️ أرسلت: ${ArabicText.ltr(Money.withCurrency(v.first, c))}   ↙️ استلمت: ${ArabicText.ltr(Money.withCurrency(v.second, c))}\n")
                }
            }
        }
    }

    private suspend fun reviewList(token: String, chat: String) {
        val list = repo.all().filter { it.needsReview }
        if (list.isEmpty()) {
            api.sendMessage(token, chat, "✅ لا توجد معاملات تحتاج مراجعة.")
            return
        }
        for (t in list.take(10)) {
            api.sendMessage(token, chat, "⚠️ ${t.reviewReason.orEmpty()}\n" + ReceiptImporter.describe(t), buttonsFor(t, null))
        }
    }

    /** Tells the owner on Telegram that a transaction was added automatically from a notification. */
    suspend fun notifyAutoAdded(tx: TransactionEntity, headline: String) {
        val s = settings.current()
        if (!s.notifyTelegramOnAuto || !s.telegramConfigured || s.telegramChatId.isBlank()) return
        runCatching { api.sendMessage(s.telegramToken, s.telegramChatId, "$headline\n" + ReceiptImporter.describe(tx), buttonsFor(tx, null)) }
    }

    companion object {
        private const val TAG = "BotEngine"
        val HELP = """
👋 أهلاً! أنا بوت محلل شام كاش.

📄 أرسل لي ملف PDF (أو صورة) لإيصال شام كاش وسأقرؤه بالذكاء الاصطناعي وأضيفه للتطبيق تلقائياً — ولا أكرر أي معاملة موجودة.
💬 اسألني أي شيء: «كم أرسلت هذا الأسبوع؟»، «من أكثر شخص استلمت منه؟»، «كم أرسلت لـ Ahmad.77؟»
🗑️ يمكنني حذف أو تثبيت أشخاص، لكن بعد أن تضغط زر التأكيد فقط.

الأوامر:
/balance الرصيد والإجماليات
/today معاملات اليوم
/week معاملات الأسبوع
/last آخر 10 معاملات
/people الأشخاص
/review معاملات تحتاج مراجعة
""".trim()
    }
}
