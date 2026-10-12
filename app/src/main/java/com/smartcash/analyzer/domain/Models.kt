package com.smartcash.analyzer.domain

/** Direction of a transaction from the point of view of the app owner. */
enum class TxType { SENT, RECEIVED }

/** Where a transaction came from. Higher [priority] means more trustworthy details. */
enum class TxSource(val priority: Int, val label: String) {
    NOTIFICATION(1, "إشعار"),
    ASSISTANT(2, "المساعد الذكي"),
    MANUAL(3, "إدخال يدوي"),
    IMAGE(4, "صورة إيصال"),
    TELEGRAM(5, "تيليجرام"),
    PDF(6, "ملف PDF"),
}

/** The operation word printed on a receipt (from the perspective of whoever generated it). */
enum class OperationKind { SEND, RECEIVE, UNKNOWN }

enum class Period(val label: String) {
    TODAY("اليوم"),
    WEEK("هذا الأسبوع"),
    MONTH("هذا الشهر"),
    ALL("دائماً"),
}

/** Raw fields read from a receipt, copied as printed (no translation, no spelling fixes). */
data class ParsedReceipt(
    val operationLabel: String? = null,
    val operationKind: OperationKind = OperationKind.UNKNOWN,
    val reference: String? = null,
    val dateTimeMillis: Long? = null,
    val hasTime: Boolean = false,
    val senderName: String? = null,
    val senderAccount: String? = null,
    val receiverName: String? = null,
    val receiverAccount: String? = null,
    val amount: Double? = null,
    val currency: String? = null,
    val fee: Double? = null,
    val note: String? = null,
    val rawText: String? = null,
) {
    /** True when the essential fields needed to record a transaction are present. */
    val isComplete: Boolean
        get() = amount != null && amount > 0 && (senderName != null || receiverName != null)

    fun filledFieldCount(): Int = listOf(
        operationLabel, reference, dateTimeMillis, senderName, senderAccount,
        receiverName, receiverAccount, amount, currency, note
    ).count { it != null }
}
