package com.smartcash.analyzer.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Insert
    suspend fun insert(t: TransactionEntity): Long

    @Insert
    suspend fun insertAll(t: List<TransactionEntity>)

    @Update
    suspend fun update(t: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM transactions")
    suspend fun deleteAll()

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun get(id: Long): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE id = :id")
    fun observe(id: Long): Flow<TransactionEntity?>

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC, id DESC")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC, id DESC")
    suspend fun all(): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE counterpartyKey = :key ORDER BY timestamp DESC, id DESC")
    fun observeByPerson(key: String): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE counterpartyKey = :key ORDER BY timestamp DESC, id DESC")
    suspend fun byPerson(key: String): List<TransactionEntity>

    @Query("SELECT COUNT(*) FROM transactions WHERE counterpartyKey = :key")
    suspend fun countByPerson(key: String): Int

    @Query("DELETE FROM transactions WHERE counterpartyKey = :key")
    suspend fun deleteByPerson(key: String): Int

    @Query("UPDATE transactions SET counterpartyKey = :newKey, counterpartyName = :newName, updatedAt = :now WHERE counterpartyKey = :oldKey")
    suspend fun moveCounterparty(oldKey: String, newKey: String, newName: String, now: Long = System.currentTimeMillis())

    @Query(
        """SELECT * FROM transactions
           WHERE (:ref IS NOT NULL AND reference = :ref)
              OR (:hash IS NOT NULL AND fileHash = :hash)
              OR (timestamp BETWEEN :from AND :to)"""
    )
    suspend fun dedupCandidates(ref: String?, hash: String?, from: Long, to: Long): List<TransactionEntity>

    @Query("SELECT * FROM transactions WHERE fileHash = :hash LIMIT 1")
    suspend fun byFileHash(hash: String): TransactionEntity?

    @Query("SELECT COUNT(*) FROM transactions WHERE needsReview = 1")
    fun observeReviewCount(): Flow<Int>
}

@Dao
interface PersonDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(p: PersonEntity): Long

    @Update
    suspend fun update(p: PersonEntity)

    @Query("SELECT * FROM people ORDER BY pinned DESC, displayName COLLATE NOCASE")
    fun observeAll(): Flow<List<PersonEntity>>

    @Query("SELECT * FROM people")
    suspend fun all(): List<PersonEntity>

    @Query("SELECT * FROM people WHERE `key` = :key LIMIT 1")
    suspend fun byKey(key: String): PersonEntity?

    @Query("SELECT * FROM people WHERE `key` = :key LIMIT 1")
    fun observeByKey(key: String): Flow<PersonEntity?>

    @Query("UPDATE people SET pinned = :pinned WHERE `key` = :key")
    suspend fun setPinned(key: String, pinned: Boolean)

    @Query("DELETE FROM people WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM people")
    suspend fun deleteAll()
}

@Dao
interface ChatDao {
    @Insert
    suspend fun insert(m: ChatMessageEntity): Long

    @Query("SELECT * FROM chat_messages WHERE channel = :channel ORDER BY id ASC")
    fun observe(channel: String): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM (SELECT * FROM chat_messages WHERE channel = :channel ORDER BY id DESC LIMIT :limit) ORDER BY id ASC")
    suspend fun recent(channel: String, limit: Int): List<ChatMessageEntity>

    @Query("DELETE FROM chat_messages WHERE channel = :channel")
    suspend fun clear(channel: String)
}

@Dao
interface PendingActionDao {
    @Insert
    suspend fun insert(a: PendingActionEntity): Long

    @Update
    suspend fun update(a: PendingActionEntity)

    @Query("SELECT * FROM pending_actions WHERE id = :id")
    suspend fun get(id: Long): PendingActionEntity?

    @Query("SELECT * FROM pending_actions")
    fun observeAll(): Flow<List<PendingActionEntity>>
}

@Dao
interface NotificationLogDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(n: NotificationLogEntity): Long

    @Update
    suspend fun update(n: NotificationLogEntity)

    @Query("SELECT * FROM notification_log WHERE id = :id")
    suspend fun get(id: Long): NotificationLogEntity?

    @Query("SELECT * FROM notification_log ORDER BY postedAt DESC LIMIT 200")
    fun observeRecent(): Flow<List<NotificationLogEntity>>

    @Query("DELETE FROM notification_log")
    suspend fun clear()
}
