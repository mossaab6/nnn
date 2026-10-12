package com.smartcash.analyzer.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.smartcash.analyzer.data.db.AppDatabase
import com.smartcash.analyzer.data.settings.SettingsRepository
import com.smartcash.analyzer.domain.TxSource
import com.smartcash.analyzer.domain.TxType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Exports / imports all transactions and people as a JSON file (API keys and tokens are never exported). */
class BackupManager(
    private val context: Context,
    private val repo: TransactionRepository,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
) {
    @Serializable
    data class TxDto(
        val type: String, val amount: Double, val currency: String, val fee: Double? = null,
        val counterpartyName: String, val counterpartyAccount: String? = null,
        val senderName: String? = null, val senderAccount: String? = null,
        val receiverName: String? = null, val receiverAccount: String? = null,
        val reference: String? = null, val timestamp: Long, val hasTime: Boolean = true,
        val operationLabel: String? = null, val note: String? = null, val source: String,
        val needsReview: Boolean = false, val reviewReason: String? = null,
    )

    @Serializable
    data class PersonDto(val name: String, val account: String? = null, val phone: String? = null, val note: String? = null, val pinned: Boolean = false)

    @Serializable
    data class BalanceDto(val currency: String, val amount: Double, val anchor: Long)

    @Serializable
    data class Backup(
        val version: Int = 1,
        val exportedAt: Long,
        val ownerNames: List<String>,
        val ownerAccounts: List<String>,
        val primaryCurrency: String,
        val balances: List<BalanceDto>,
        val people: List<PersonDto>,
        val transactions: List<TxDto>,
    )

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    suspend fun export(uri: Uri): Int = withContext(Dispatchers.IO) {
        val s = settings.current()
        val txs = repo.all()
        val backup = Backup(
            exportedAt = System.currentTimeMillis(),
            ownerNames = s.ownerNames,
            ownerAccounts = s.ownerAccounts,
            primaryCurrency = s.primaryCurrency,
            balances = s.balances.map { BalanceDto(it.key, it.value.amount, it.value.anchorTime) },
            people = repo.allPeople().map { PersonDto(it.displayName, it.account, it.phone, it.note, it.pinned) },
            transactions = txs.map {
                TxDto(
                    it.type.name, it.amount, it.currency, it.fee, it.counterpartyName, it.counterpartyAccount,
                    it.senderName, it.senderAccount, it.receiverName, it.receiverAccount, it.reference, it.timestamp,
                    it.hasTime, it.operationLabel, it.note, it.source.name, it.needsReview, it.reviewReason,
                )
            },
        )
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.encodeToString(Backup.serializer(), backup).toByteArray()) }
            ?: error("تعذر الكتابة في الملف")
        txs.size
    }

    /** Imports a backup, skipping transactions that already exist. Returns (added, skipped). */
    suspend fun import(uri: Uri): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("تعذر قراءة الملف")
        val backup = json.decodeFromString(Backup.serializer(), text)
        var added = 0
        var skipped = 0
        for (t in backup.transactions) {
            val draft = TransactionDraft(
                type = runCatching { TxType.valueOf(t.type) }.getOrDefault(TxType.SENT),
                amount = t.amount, currency = t.currency, fee = t.fee,
                counterpartyName = t.counterpartyName, counterpartyAccount = t.counterpartyAccount,
                senderName = t.senderName, senderAccount = t.senderAccount,
                receiverName = t.receiverName, receiverAccount = t.receiverAccount,
                reference = t.reference, timestamp = t.timestamp, hasTime = t.hasTime,
                operationLabel = t.operationLabel, note = t.note,
                source = runCatching { TxSource.valueOf(t.source) }.getOrDefault(TxSource.MANUAL),
                needsReview = t.needsReview, reviewReason = t.reviewReason,
            )
            when (repo.save(draft, DedupPolicy.AUTO_MERGE)) {
                is SaveResult.Added -> added++
                else -> skipped++
            }
        }
        db.withTransaction {
            for (p in backup.people) {
                val person = repo.addPerson(p.name, p.account, p.phone, p.note)
                if (person != null && p.pinned) repo.setPinned(person.key, true)
            }
        }
        val s = settings.current()
        if (s.ownerNames.isEmpty() && backup.ownerNames.isNotEmpty()) settings.setOwnerNames(backup.ownerNames)
        if (s.ownerAccounts.isEmpty() && backup.ownerAccounts.isNotEmpty()) settings.setOwnerAccounts(backup.ownerAccounts)
        if (s.balances.isEmpty()) backup.balances.forEach { settings.setBalance(it.currency, it.amount, it.anchor) }
        added to skipped
    }
}
