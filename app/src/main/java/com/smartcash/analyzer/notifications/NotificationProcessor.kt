package com.smartcash.analyzer.notifications

import android.content.Context
import com.smartcash.analyzer.ai.AiExtractor
import com.smartcash.analyzer.data.DedupPolicy
import com.smartcash.analyzer.data.SaveResult
import com.smartcash.analyzer.data.TransactionDraft
import com.smartcash.analyzer.data.TransactionRepository
import com.smartcash.analyzer.data.db.AppDatabase
import com.smartcash.analyzer.data.db.NotificationLogEntity
import com.smartcash.analyzer.data.settings.SettingsRepository
import com.smartcash.analyzer.domain.AmountParser
import com.smartcash.analyzer.domain.ArabicText
import com.smartcash.analyzer.domain.Money
import com.smartcash.analyzer.domain.NotificationParser
import com.smartcash.analyzer.domain.OperationKind
import com.smartcash.analyzer.domain.TxSource
import com.smartcash.analyzer.domain.TxType
import com.smartcash.analyzer.telegram.BotEngine
import java.security.MessageDigest

/**
 * Turns Sham Cash notifications into transactions. Each notification is processed once (a log row with a
 * unique key), parsed locally or by Grok, and saved with duplicate detection — so importing the PDF of the
 * same transaction later merges into it instead of adding it twice.
 */
class NotificationProcessor(
    private val context: Context,
    private val settings: SettingsRepository,
    private val repo: TransactionRepository,
    private val db: AppDatabase,
    private val extractor: AiExtractor,
    private val bot: () -> BotEngine,
) {
    private val logDao = db.notifications()

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).take(10).joinToString("") { b -> (b.toInt() and 0xff).toString(16).padStart(2, '0') }

    fun looksLikeMoney(text: String): Boolean {
        val t = ArabicText.normalizeDigits(text)
        if (t.none { it.isDigit() }) return false
        return AmountParser.detectCurrency(t) != null ||
            listOf("مبلغ", "حوالة", "تحويل", "استلام", "استلمت", "إرسال", "ارسال", "أرسلت", "رصيد", "received", "sent", "transfer")
                .any { t.contains(it, ignoreCase = true) }
    }

    suspend fun onNotification(pkg: String, key: String, eventTime: Long, title: String?, text: String?) {
        if (pkg == context.packageName) return
        val combined = listOfNotNull(title, text).joinToString("\n").trim()
        if (combined.isEmpty()) return
        val s = settings.current()
        if (!s.isShamCashPackage(pkg)) {
            // remember apps that post money-like notifications so the user can pick the right one in settings
            if (looksLikeMoney(combined)) settings.addRecentNotifSource(pkg)
            return
        }
        if (!s.notifAutoAdd) return
        val dedupKey = "$pkg|$key|${hash(combined)}|${eventTime / 60_000}"
        val logId = logDao.insertIgnore(
            NotificationLogEntity(dedupKey = dedupKey, packageName = pkg, title = title, text = text, postedAt = eventTime, status = "PENDING")
        )
        if (logId <= 0) return // already processed
        process(logId)
    }

    /** Parses and saves the notification behind log row [logId]. Also used by "إعادة المعالجة" in the log screen. */
    suspend fun process(logId: Long) {
        val log = logDao.get(logId) ?: return
        val s = settings.current()
        val local = NotificationParser.parse(log.title, log.text)
        var kind = local?.kind ?: OperationKind.UNKNOWN
        var amount = local?.amount
        var currency = local?.currency
        var counterparty = local?.counterparty
        var reference = local?.reference
        var balanceAfter = local?.balanceAfter
        var usedAi = false

        if ((local == null || !local.isConfident || counterparty == null) && s.aiConfigured) {
            val aiResult = runCatching { extractor.readNotification(log.title, log.text) }.getOrNull()
            if (aiResult != null) {
                usedAi = true
                if (!aiResult.isTransaction && local == null) {
                    logDao.update(log.copy(status = "IGNORED", message = "ليست معاملة مالية"))
                    return
                }
                if (aiResult.kind != OperationKind.UNKNOWN) kind = aiResult.kind
                amount = aiResult.amount ?: amount
                currency = aiResult.currency ?: currency
                counterparty = aiResult.counterparty ?: counterparty
                reference = aiResult.reference ?: reference
                balanceAfter = aiResult.balanceAfter ?: balanceAfter
            }
        }
        if (amount == null || amount <= 0) {
            logDao.update(log.copy(status = "IGNORED", message = "لم يتم العثور على مبلغ"))
            return
        }
        val unknownDirection = kind == OperationKind.UNKNOWN
        val type = if (kind == OperationKind.RECEIVE) TxType.RECEIVED else TxType.SENT
        val owner = s.ownerNames.firstOrNull()
        val reason = listOfNotNull(
            "لم يتضح من الإشعار إن كانت إرسالاً أم استلاماً".takeIf { unknownDirection },
            "اسم الشخص غير موجود في الإشعار".takeIf { counterparty == null },
        ).joinToString("، ").ifEmpty { null }

        val draft = TransactionDraft(
            type = type,
            amount = amount,
            currency = currency ?: s.primaryCurrency,
            counterpartyName = counterparty,
            senderName = if (type == TxType.SENT) owner else counterparty,
            receiverName = if (type == TxType.SENT) counterparty else owner,
            reference = reference,
            timestamp = log.postedAt,
            hasTime = true,
            source = TxSource.NOTIFICATION,
            rawText = listOfNotNull(log.title, log.text).joinToString("\n"),
            needsReview = reason != null,
            reviewReason = reason,
            balanceAfter = balanceAfter,
        )
        val result = repo.save(draft, DedupPolicy.AUTO_MERGE)
        val (status, msg) = when (result) {
            is SaveResult.Added -> "ADDED" to "تمت الإضافة" + if (usedAi) " (بمساعدة Grok)" else ""
            is SaveResult.Merged -> "MERGED" to "مدمجة مع معاملة موجودة: ${result.reason}"
            is SaveResult.Duplicate -> "DUPLICATE" to "مكررة: ${result.reason}"
            is SaveResult.PossibleDuplicate -> "DUPLICATE" to result.reason
        }
        logDao.update(log.copy(status = status, transactionId = result.id, message = msg))

        if (result is SaveResult.Added) {
            val tx = repo.get(result.id) ?: return
            val sign = Money.signed(tx.amount, tx.currency, tx.type)
            val who = if (tx.type == TxType.SENT) "إلى ${tx.displayName}" else "من ${tx.displayName}"
            AppNotifier.transactionAdded(context, tx.id, "تمت إضافة معاملة تلقائياً", "${ArabicText.ltr(sign)} $who")
            bot().notifyAutoAdded(tx, "🔔 معاملة جديدة من إشعار شام كاش")
        }
    }
}
