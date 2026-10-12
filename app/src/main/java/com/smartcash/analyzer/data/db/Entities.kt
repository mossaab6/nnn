package com.smartcash.analyzer.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.smartcash.analyzer.domain.TxLite
import com.smartcash.analyzer.domain.TxSource
import com.smartcash.analyzer.domain.TxType

@Entity(
    tableName = "transactions",
    indices = [Index("reference"), Index("timestamp"), Index("counterpartyKey"), Index("fileHash")],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: TxType,
    val amount: Double,
    val currency: String,
    val fee: Double? = null,
    /** The other party (receiver when I sent, sender when I received). Empty when unknown. */
    val counterpartyName: String,
    val counterpartyKey: String,
    val counterpartyAccount: String? = null,
    val senderName: String? = null,
    val senderAccount: String? = null,
    val receiverName: String? = null,
    val receiverAccount: String? = null,
    /** Sham Cash operation number printed on the receipt. */
    val reference: String? = null,
    val timestamp: Long,
    val hasTime: Boolean = true,
    val operationLabel: String? = null,
    val note: String? = null,
    val source: TxSource,
    /** All sources merged into this record, comma separated (e.g. "NOTIFICATION,PDF"). */
    val sources: String = source.name,
    val rawText: String? = null,
    val filePath: String? = null,
    val fileHash: String? = null,
    val needsReview: Boolean = false,
    val reviewReason: String? = null,
    /** JSON with alternative readings (OCR vs AI) when they disagree. */
    val alternativesJson: String? = null,
    val balanceAfter: Double? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toLite() = TxLite(type, amount, currency, fee, timestamp, counterpartyKey)
    val displayName: String get() = counterpartyName.ifBlank { "غير معروف" }
}

@Entity(tableName = "people", indices = [Index(value = ["key"], unique = true)])
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,
    val displayName: String,
    val account: String? = null,
    val phone: String? = null,
    val note: String? = null,
    val pinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "chat_messages", indices = [Index("channel")])
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "app" or "telegram" */
    val channel: String,
    /** "user", "assistant" or "event" (system notes such as "تم الحذف") */
    val role: String,
    val content: String,
    val actionId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "pending_actions")
data class PendingActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channel: String,
    val kind: String,
    val payloadJson: String,
    /** Arabic sentence shown on the confirmation card. */
    val description: String,
    /** PENDING, DONE, CANCELLED, FAILED */
    val status: String = "PENDING",
    val resultMessage: String? = null,
    val telegramMessageId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "notification_log", indices = [Index(value = ["dedupKey"], unique = true)])
data class NotificationLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dedupKey: String,
    val packageName: String,
    val title: String?,
    val text: String?,
    val postedAt: Long,
    /** PENDING, ADDED, MERGED, DUPLICATE, IGNORED, FAILED */
    val status: String,
    val transactionId: Long? = null,
    val message: String? = null,
)
