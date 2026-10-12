package com.smartcash.analyzer.ai

import com.smartcash.analyzer.data.DedupPolicy
import com.smartcash.analyzer.data.SaveResult
import com.smartcash.analyzer.data.TransactionDraft
import com.smartcash.analyzer.data.TransactionRepository
import com.smartcash.analyzer.data.db.AppDatabase
import com.smartcash.analyzer.data.db.PendingActionEntity
import com.smartcash.analyzer.data.settings.SettingsRepository
import com.smartcash.analyzer.domain.AmountParser
import com.smartcash.analyzer.domain.DateParser
import com.smartcash.analyzer.domain.Money
import com.smartcash.analyzer.domain.TxFormat
import com.smartcash.analyzer.domain.TxSource
import com.smartcash.analyzer.domain.TxType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Actions the assistant may *propose*. Nothing changes until the user presses "تأكيد"
 * (in the app or on the Telegram inline button); then [confirm] performs it.
 */
class ActionExecutor(
    private val db: AppDatabase,
    private val repo: TransactionRepository,
    private val settings: SettingsRepository,
) {
    object Kind {
        const val DELETE_TRANSACTION = "DELETE_TRANSACTION"
        const val DELETE_PERSON = "DELETE_PERSON"
        const val PIN_PERSON = "PIN_PERSON"
        const val ADD_TRANSACTION = "ADD_TRANSACTION"
        const val ADD_PERSON = "ADD_PERSON"
        const val RENAME_PERSON = "RENAME_PERSON"
        const val SET_BALANCE = "SET_BALANCE"
        const val SET_DIRECTION = "SET_DIRECTION"
        const val SET_OWNER_NAME = "SET_OWNER_NAME"
    }

    private val dao = db.actions()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun propose(channel: String, kind: String, payload: JsonObject, description: String): PendingActionEntity {
        val id = dao.insert(PendingActionEntity(channel = channel, kind = kind, payloadJson = payload.toString(), description = description))
        return dao.get(id)!!
    }

    suspend fun get(id: Long): PendingActionEntity? = dao.get(id)

    suspend fun setTelegramMessage(id: Long, messageId: Long) {
        dao.get(id)?.let { dao.update(it.copy(telegramMessageId = messageId)) }
    }

    suspend fun cancel(id: Long): String {
        val a = dao.get(id) ?: return "الإجراء غير موجود"
        if (a.status != "PENDING") return statusText(a)
        dao.update(a.copy(status = "CANCELLED", resultMessage = "تم الإلغاء"))
        return "❌ تم إلغاء: ${a.description}"
    }

    private fun statusText(a: PendingActionEntity) = when (a.status) {
        "DONE" -> "تم تنفيذ هذا الإجراء مسبقاً"
        "CANCELLED" -> "تم إلغاء هذا الإجراء مسبقاً"
        "FAILED" -> "فشل هذا الإجراء: ${a.resultMessage.orEmpty()}"
        else -> a.status
    }

    private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.d(k: String): Double? = (this[k] as? JsonPrimitive)?.let { it.doubleOrNull ?: it.contentOrNull?.let { c -> AmountParser.parse(c)?.value } }
    private fun JsonObject.l(k: String): Long? = (this[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }
    private fun JsonObject.b(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull

    /** Executes a confirmed action and returns an Arabic result message. */
    suspend fun confirm(id: Long): String {
        val a = dao.get(id) ?: return "الإجراء غير موجود"
        if (a.status != "PENDING") return statusText(a)
        val p = runCatching { json.parseToJsonElement(a.payloadJson).jsonObject }.getOrElse { JsonObject(emptyMap()) }
        val result = runCatching { perform(a.kind, p) }
        val (status, msg) = result.fold(
            onSuccess = { "DONE" to it },
            onFailure = { "FAILED" to "تعذر التنفيذ: ${it.message ?: it.javaClass.simpleName}" },
        )
        dao.update(a.copy(status = status, resultMessage = msg))
        return msg
    }

    private suspend fun perform(kind: String, p: JsonObject): String = when (kind) {
        Kind.DELETE_TRANSACTION -> {
            val id = p.l("id") ?: error("رقم المعاملة مفقود")
            val t = repo.get(id) ?: error("المعاملة غير موجودة")
            repo.delete(id)
            "✅ تم حذف المعاملة: ${TxFormat.typeLabel(t.type)} ${Money.withCurrency(t.amount, t.currency)} - ${t.displayName}"
        }
        Kind.DELETE_PERSON -> {
            val key = p.s("key") ?: error("الشخص غير محدد")
            val name = p.s("name") ?: key
            val n = repo.deletePerson(key)
            "✅ تم حذف «$name» مع $n معاملة"
        }
        Kind.PIN_PERSON -> {
            val key = p.s("key") ?: error("الشخص غير محدد")
            val pinned = p.b("pinned") ?: true
            repo.setPinned(key, pinned)
            if (pinned) "📌 تم تثبيت «${p.s("name") ?: key}»" else "تم إلغاء تثبيت «${p.s("name") ?: key}»"
        }
        Kind.ADD_PERSON -> {
            val person = repo.addPerson(p.s("name") ?: error("الاسم مفقود"), p.s("account"), null, p.s("note"))
                ?: error("اسم غير صالح")
            "✅ تمت إضافة «${person.displayName}»"
        }
        Kind.RENAME_PERSON -> {
            val key = p.s("key") ?: error("الشخص غير محدد")
            val newName = p.s("new_name") ?: error("الاسم الجديد مفقود")
            if (!repo.renamePerson(key, newName)) error("تعذر تغيير الاسم")
            "✅ تم تغيير الاسم إلى «$newName»"
        }
        Kind.SET_BALANCE -> {
            val currency = AmountParser.normalizeCurrency(p.s("currency")) ?: settings.current().primaryCurrency
            val amount = p.d("amount") ?: error("المبلغ مفقود")
            settings.setBalance(currency, amount)
            "✅ تم ضبط الرصيد الحالي إلى ${Money.withCurrency(amount, currency)}"
        }
        Kind.SET_DIRECTION -> {
            val id = p.l("id") ?: error("رقم المعاملة مفقود")
            val type = if (p.s("type") == "received") TxType.RECEIVED else TxType.SENT
            repo.setDirection(id, type)
            "✅ تم تعديل نوع العملية إلى ${TxFormat.typeLabel(type)}"
        }
        Kind.SET_OWNER_NAME -> {
            val name = p.s("name") ?: error("الاسم مفقود")
            settings.addOwnerName(name)
            "✅ تم حفظ «$name» كاسمك في شام كاش. سيتم التعرف على اتجاه العمليات تلقائياً."
        }
        Kind.ADD_TRANSACTION -> {
            val type = if (p.s("type") == "received") TxType.RECEIVED else TxType.SENT
            val amount = p.d("amount") ?: error("المبلغ مفقود")
            val currency = AmountParser.normalizeCurrency(p.s("currency")) ?: settings.current().primaryCurrency
            val name = p.s("counterparty_name") ?: error("اسم الشخص مفقود")
            val owner = settings.current().ownerNames.firstOrNull()
            val dt = DateParser.parse(listOfNotNull(p.s("date"), p.s("time")).joinToString(" "))
            val draft = TransactionDraft(
                type = type,
                amount = amount,
                currency = currency,
                counterpartyName = name,
                senderName = if (type == TxType.SENT) owner else name,
                receiverName = if (type == TxType.SENT) name else owner,
                reference = p.s("reference"),
                timestamp = dt?.toMillis() ?: System.currentTimeMillis(),
                hasTime = dt?.hasTime ?: true,
                note = p.s("note"),
                source = TxSource.ASSISTANT,
            )
            when (val r = repo.save(draft, DedupPolicy.AUTO_MERGE)) {
                is SaveResult.Added -> "✅ تمت إضافة المعاملة"
                is SaveResult.Merged -> "🔗 المعاملة موجودة مسبقاً، تم تحديثها بدون تكرار"
                is SaveResult.Duplicate -> "♻️ المعاملة موجودة مسبقاً، لم تتم إضافتها مرة أخرى"
                is SaveResult.PossibleDuplicate -> "⚠️ ${r.reason}"
            }
        }
        else -> error("إجراء غير معروف")
    }
}
