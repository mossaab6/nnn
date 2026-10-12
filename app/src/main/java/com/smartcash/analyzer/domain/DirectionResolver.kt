package com.smartcash.analyzer.domain

/**
 * Works out whether a receipt is money sent or received *by the app owner*.
 *
 * A receipt's operation word ("إرسال") is written from the perspective of whoever generated it; a
 * friend may forward their own "send" receipt to you, which for you is a receive. So the owner's
 * Sham Cash name / account digits (from settings) decide first, and the printed word is only a fallback.
 */
object DirectionResolver {

    data class Identity(val names: List<String>, val accountDigits: List<String>) {
        val isEmpty: Boolean get() = names.isEmpty() && accountDigits.isEmpty()
    }

    data class Resolution(
        val type: TxType,
        val counterpartyName: String?,
        val counterpartyAccount: String?,
        /** true when the owner was recognised on the receipt */
        val certain: Boolean,
        /** the name on the receipt that is probably the owner (to offer "is this you?") */
        val ownerCandidate: String?,
    )

    fun isMe(name: String?, account: String?, id: Identity): Boolean {
        val acc = ArabicText.digits(account)
        if (acc.length >= 3) {
            for (d in id.accountDigits.map { ArabicText.digits(it) }.filter { it.length >= 3 }) {
                if (acc == d || acc.endsWith(d) || acc.startsWith(d) || d.endsWith(acc) || d.startsWith(acc)) return true
            }
        }
        if (!name.isNullOrBlank()) {
            val k = ArabicText.looseKey(name)
            for (n in id.names) {
                val mk = ArabicText.looseKey(n)
                if (mk.isNotEmpty() && (mk == k || ArabicText.similarity(mk, k) >= 0.9)) return true
            }
        }
        return false
    }

    fun resolve(r: ParsedReceipt, id: Identity): Resolution {
        val senderIsMe = isMe(r.senderName, r.senderAccount, id)
        val receiverIsMe = isMe(r.receiverName, r.receiverAccount, id)
        if (senderIsMe && !receiverIsMe) {
            return Resolution(TxType.SENT, r.receiverName, r.receiverAccount, true, null)
        }
        if (receiverIsMe && !senderIsMe) {
            return Resolution(TxType.RECEIVED, r.senderName, r.senderAccount, true, null)
        }
        return when (r.operationKind) {
            OperationKind.RECEIVE ->
                Resolution(TxType.RECEIVED, r.senderName, r.senderAccount, false, r.receiverName)
            OperationKind.SEND, OperationKind.UNKNOWN ->
                Resolution(TxType.SENT, r.receiverName, r.receiverAccount, false, r.senderName)
        }
    }
}
