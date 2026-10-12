package com.smartcash.analyzer.data

import androidx.room.withTransaction
import com.smartcash.analyzer.data.db.AppDatabase
import com.smartcash.analyzer.data.db.PersonEntity
import com.smartcash.analyzer.data.db.TransactionEntity
import com.smartcash.analyzer.domain.ArabicText
import com.smartcash.analyzer.domain.DuplicateDetector
import com.smartcash.analyzer.domain.TxSource
import com.smartcash.analyzer.domain.TxType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** A transaction about to be saved (from a PDF, a notification, Telegram, the assistant or the user). */
data class TransactionDraft(
    val type: TxType,
    val amount: Double,
    val currency: String,
    val counterpartyName: String?,
    val counterpartyAccount: String? = null,
    val senderName: String? = null,
    val senderAccount: String? = null,
    val receiverName: String? = null,
    val receiverAccount: String? = null,
    val reference: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val hasTime: Boolean = true,
    val operationLabel: String? = null,
    val fee: Double? = null,
    val note: String? = null,
    val source: TxSource,
    val rawText: String? = null,
    val filePath: String? = null,
    val fileHash: String? = null,
    val needsReview: Boolean = false,
    val reviewReason: String? = null,
    val alternativesJson: String? = null,
    val balanceAfter: Double? = null,
)

sealed class SaveResult {
    abstract val id: Long

    /** New transaction stored. */
    data class Added(override val id: Long) : SaveResult()

    /** Same transaction already existed; missing details were filled in from the new source. */
    data class Merged(override val id: Long, val reason: String) : SaveResult()

    /** Same transaction already existed with the same details; nothing changed. */
    data class Duplicate(override val id: Long, val reason: String) : SaveResult()

    /** Looks like an existing transaction; the user should decide (manual entry). */
    data class PossibleDuplicate(override val id: Long, val reason: String) : SaveResult()
}

enum class DedupPolicy { AUTO_MERGE, ASK, FORCE_NEW }

data class PersonSummary(
    val person: PersonEntity,
    /** currency -> (sent, received) */
    val totals: Map<String, Pair<Double, Double>>,
    val count: Int,
    val lastTimestamp: Long?,
) {
    fun sent(currency: String) = totals[currency]?.first ?: 0.0
    fun received(currency: String) = totals[currency]?.second ?: 0.0
}

class TransactionRepository(private val db: AppDatabase) {

    private val txDao = db.transactions()
    private val personDao = db.people()

    fun observeAll(): Flow<List<TransactionEntity>> = txDao.observeAll()
    fun observe(id: Long): Flow<TransactionEntity?> = txDao.observe(id)
    fun observeByPerson(key: String): Flow<List<TransactionEntity>> = txDao.observeByPerson(key)
    fun observeReviewCount(): Flow<Int> = txDao.observeReviewCount()
    suspend fun get(id: Long): TransactionEntity? = txDao.get(id)
    suspend fun all(): List<TransactionEntity> = txDao.all()
    suspend fun byFileHash(hash: String): TransactionEntity? = txDao.byFileHash(hash)

    private fun fingerprint(t: TransactionEntity) = DuplicateDetector.Fingerprint(
        t.id, t.type, t.amount, t.currency, t.timestamp, t.hasTime, t.reference,
        t.counterpartyName.ifBlank { null }, t.fileHash, t.source,
    )

    private fun fingerprint(d: TransactionDraft) = DuplicateDetector.Fingerprint(
        0, d.type, d.amount, d.currency, d.timestamp, d.hasTime, d.reference,
        d.counterpartyName?.ifBlank { null }, d.fileHash, d.source,
    )

    /** Finds an existing transaction that is the same as [draft], if any. */
    suspend fun findDuplicate(draft: TransactionDraft, excludeId: Long = 0): DuplicateDetector.Match? {
        val day = 26 * 60 * 60 * 1000L
        val ref = ArabicText.digits(draft.reference).ifEmpty { null }
        val candidates = txDao.dedupCandidates(ref, draft.fileHash, draft.timestamp - day, draft.timestamp + day)
            .filter { it.id != excludeId }
        return DuplicateDetector.findDuplicate(fingerprint(draft), candidates.map { fingerprint(it) })
    }

    suspend fun save(draft: TransactionDraft, policy: DedupPolicy = DedupPolicy.AUTO_MERGE): SaveResult =
        db.withTransaction {
            val match = if (policy == DedupPolicy.FORCE_NEW) null else findDuplicate(draft)
            if (match == null) {
                val entity = draft.toEntity()
                val id = txDao.insert(entity)
                ensurePerson(entity.counterpartyName, entity.counterpartyAccount)
                return@withTransaction SaveResult.Added(id)
            }
            if (policy == DedupPolicy.ASK) return@withTransaction SaveResult.PossibleDuplicate(match.existingId, match.reason)

            val existing = txDao.get(match.existingId)!!
            val merged = merge(existing, draft, match.certainty == DuplicateDetector.Certainty.PROBABLE, match.reason)
            if (merged.copy(updatedAt = existing.updatedAt) == existing) {
                SaveResult.Duplicate(existing.id, match.reason)
            } else {
                txDao.update(merged)
                ensurePerson(merged.counterpartyName, merged.counterpartyAccount)
                SaveResult.Merged(existing.id, match.reason)
            }
        }

    private fun TransactionDraft.toEntity(): TransactionEntity {
        val name = ArabicText.cleanName(counterpartyName) ?: ""
        return TransactionEntity(
            type = type,
            amount = amount,
            currency = currency,
            fee = fee,
            counterpartyName = name,
            counterpartyKey = ArabicText.nameKey(name),
            counterpartyAccount = counterpartyAccount,
            senderName = senderName,
            senderAccount = senderAccount,
            receiverName = receiverName,
            receiverAccount = receiverAccount,
            reference = ArabicText.digits(reference).ifEmpty { null },
            timestamp = timestamp,
            hasTime = hasTime,
            operationLabel = operationLabel,
            note = note,
            source = source,
            sources = source.name,
            rawText = rawText,
            filePath = filePath,
            fileHash = fileHash,
            needsReview = needsReview,
            reviewReason = reviewReason,
            alternativesJson = alternativesJson,
            balanceAfter = balanceAfter,
        )
    }

    /**
     * Combines two readings of the same transaction. The more reliable source (PDF > Telegram > image >
     * manual > assistant > notification) wins for fields both have; the other only fills in blanks.
     */
    private fun merge(e: TransactionEntity, d: TransactionDraft, probable: Boolean, reason: String): TransactionEntity {
        val n = d.toEntity()
        val newWins = n.source.priority > e.source.priority
        fun <T> pick(old: T?, new: T?): T? = if (newWins) new ?: old else old ?: new
        fun pickStr(old: String?, new: String?): String? =
            if (newWins) new?.ifBlank { null } ?: old else old?.ifBlank { null } ?: new

        val counterparty = pickStr(e.counterpartyName, n.counterpartyName) ?: ""
        val useNewTime = (newWins && n.hasTime) || (!e.hasTime && n.hasTime)
        val sources = (e.sources.split(',') + n.source.name).filter { it.isNotBlank() }.distinct().joinToString(",")
        val needsReview = when {
            probable -> true
            newWins -> n.needsReview
            else -> e.needsReview
        }
        return e.copy(
            type = if (newWins && !n.needsReview) n.type else e.type,
            amount = if (newWins && !n.needsReview) n.amount else e.amount,
            fee = pick(e.fee, n.fee),
            counterpartyName = counterparty,
            counterpartyKey = ArabicText.nameKey(counterparty),
            counterpartyAccount = pickStr(e.counterpartyAccount, n.counterpartyAccount),
            senderName = pickStr(e.senderName, n.senderName),
            senderAccount = pickStr(e.senderAccount, n.senderAccount),
            receiverName = pickStr(e.receiverName, n.receiverName),
            receiverAccount = pickStr(e.receiverAccount, n.receiverAccount),
            reference = pickStr(e.reference, n.reference),
            timestamp = if (useNewTime) n.timestamp else e.timestamp,
            hasTime = e.hasTime || n.hasTime,
            operationLabel = pickStr(e.operationLabel, n.operationLabel),
            note = pickStr(e.note, n.note),
            source = if (newWins) n.source else e.source,
            sources = sources,
            rawText = pickStr(e.rawText, n.rawText),
            filePath = pickStr(e.filePath, n.filePath),
            fileHash = pickStr(e.fileHash, n.fileHash),
            needsReview = needsReview,
            reviewReason = if (probable) "تم دمجها مع معاملة مشابهة ($reason) — تأكد أنها نفس العملية" else pickStr(e.reviewReason, n.reviewReason).takeIf { needsReview },
            alternativesJson = pickStr(e.alternativesJson, n.alternativesJson),
            balanceAfter = pick(e.balanceAfter, n.balanceAfter),
            updatedAt = System.currentTimeMillis(),
        )
    }

    suspend fun update(t: TransactionEntity) = db.withTransaction {
        val name = ArabicText.cleanName(t.counterpartyName) ?: ""
        val fixed = t.copy(counterpartyName = name, counterpartyKey = ArabicText.nameKey(name), updatedAt = System.currentTimeMillis())
        txDao.update(fixed)
        ensurePerson(fixed.counterpartyName, fixed.counterpartyAccount)
    }

    suspend fun delete(id: Long) = txDao.delete(id)

    suspend fun markReviewed(id: Long) {
        val t = txDao.get(id) ?: return
        txDao.update(t.copy(needsReview = false, reviewReason = null, updatedAt = System.currentTimeMillis()))
    }

    /** Flips sent/received and swaps the counterparty accordingly (used when the receipt direction was guessed). */
    suspend fun setDirection(id: Long, type: TxType) {
        val t = txDao.get(id) ?: return
        if (t.type == type) {
            markReviewed(id); return
        }
        val other = if (type == TxType.SENT) t.receiverName else t.senderName
        val otherAcc = if (type == TxType.SENT) t.receiverAccount else t.senderAccount
        update(
            t.copy(
                type = type,
                counterpartyName = other ?: t.counterpartyName,
                counterpartyAccount = otherAcc ?: t.counterpartyAccount,
                needsReview = false,
                reviewReason = null,
            )
        )
    }

    // ---------------------------------------------------------------- people

    private suspend fun ensurePerson(name: String, account: String?) {
        val key = ArabicText.nameKey(name)
        if (key.isEmpty()) return
        personDao.insertIgnore(PersonEntity(key = key, displayName = name, account = account))
    }

    fun observePeople(): Flow<List<PersonSummary>> =
        combine(personDao.observeAll(), txDao.observeAll()) { people, txs -> summarizePeople(people, txs) }

    fun summarizePeople(people: List<PersonEntity>, txs: List<TransactionEntity>): List<PersonSummary> {
        val byKey = txs.groupBy { it.counterpartyKey }
        return people.map { p ->
            val list = byKey[p.key].orEmpty()
            val totals = list.groupBy { it.currency }.mapValues { (_, l) ->
                l.filter { it.type == TxType.SENT }.sumOf { it.amount } to l.filter { it.type == TxType.RECEIVED }.sumOf { it.amount }
            }
            PersonSummary(p, totals, list.size, list.maxOfOrNull { it.timestamp })
        }
    }

    fun observePerson(key: String): Flow<PersonEntity?> = personDao.observeByKey(key)
    suspend fun person(key: String): PersonEntity? = personDao.byKey(key)
    suspend fun allPeople(): List<PersonEntity> = personDao.all()

    /** Finds a person by (approximate) name. */
    suspend fun findPerson(name: String): PersonEntity? {
        val key = ArabicText.nameKey(name)
        personDao.byKey(key)?.let { return it }
        val people = personDao.all()
        return people.firstOrNull { ArabicText.sameName(it.displayName, name) }
            ?: people.firstOrNull { ArabicText.nameKey(it.displayName).contains(key) && key.length >= 3 }
    }

    suspend fun addPerson(name: String, account: String?, phone: String?, note: String?): PersonEntity? {
        val clean = ArabicText.cleanName(name) ?: return null
        val key = ArabicText.nameKey(clean)
        personDao.byKey(key)?.let { existing ->
            val updated = existing.copy(
                account = account?.ifBlank { null } ?: existing.account,
                phone = phone?.ifBlank { null } ?: existing.phone,
                note = note?.ifBlank { null } ?: existing.note,
            )
            personDao.update(updated)
            return updated
        }
        personDao.insertIgnore(PersonEntity(key = key, displayName = clean, account = account?.ifBlank { null }, phone = phone?.ifBlank { null }, note = note?.ifBlank { null }))
        return personDao.byKey(key)
    }

    suspend fun updatePerson(p: PersonEntity) = personDao.update(p)

    suspend fun setPinned(key: String, pinned: Boolean) = personDao.setPinned(key, pinned)

    suspend fun transactionCount(key: String): Int = txDao.countByPerson(key)

    /** Deletes a person together with all of their transactions. Returns the number of transactions removed. */
    suspend fun deletePerson(key: String): Int = db.withTransaction {
        val n = txDao.deleteByPerson(key)
        personDao.delete(key)
        n
    }

    /** Renames a person; if the new name belongs to someone else, the two are merged. */
    suspend fun renamePerson(oldKey: String, newName: String): Boolean = db.withTransaction {
        val clean = ArabicText.cleanName(newName) ?: return@withTransaction false
        val newKey = ArabicText.nameKey(clean)
        val old = personDao.byKey(oldKey) ?: return@withTransaction false
        txDao.moveCounterparty(oldKey, newKey, clean)
        val target = personDao.byKey(newKey)
        if (target != null && target.id != old.id) {
            personDao.update(target.copy(pinned = target.pinned || old.pinned, account = target.account ?: old.account, note = target.note ?: old.note))
            personDao.delete(oldKey)
        } else {
            personDao.update(old.copy(key = newKey, displayName = clean))
        }
        true
    }

    suspend fun deleteAll() = db.withTransaction {
        txDao.deleteAll()
        personDao.deleteAll()
    }
}
