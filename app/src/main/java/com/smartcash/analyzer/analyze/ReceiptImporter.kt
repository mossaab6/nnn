package com.smartcash.analyzer.analyze

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.smartcash.analyzer.data.DedupPolicy
import com.smartcash.analyzer.data.SaveResult
import com.smartcash.analyzer.data.TransactionDraft
import com.smartcash.analyzer.data.TransactionRepository
import com.smartcash.analyzer.data.db.TransactionEntity
import com.smartcash.analyzer.data.settings.SettingsRepository
import com.smartcash.analyzer.domain.ArabicText
import com.smartcash.analyzer.domain.DirectionResolver
import com.smartcash.analyzer.domain.Money
import com.smartcash.analyzer.domain.OperationKind
import com.smartcash.analyzer.domain.TxFormat
import com.smartcash.analyzer.domain.TxSource
import com.smartcash.analyzer.domain.TxType
import com.smartcash.analyzer.ocr.PageImages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Full pipeline for one receipt file: de-duplicate by file hash → keep a copy → render → OCR + Grok →
 * work out sent/received → save (merging with the same transaction if it already exists).
 */
class ReceiptImporter(
    private val context: Context,
    private val analyzer: ReceiptAnalyzer,
    private val repo: TransactionRepository,
    private val settings: SettingsRepository,
) {

    data class Outcome(
        val ok: Boolean,
        val result: SaveResult?,
        val message: String,
        val analysis: ReceiptAnalyzer.Analysis? = null,
        /** A name on the receipt that is probably the owner: offer "is this you?" */
        val ownerCandidate: String? = null,
    ) {
        val transactionId: Long? get() = result?.id
    }

    fun receiptsDir(): File = File(context.filesDir, "receipts").apply { mkdirs() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { b -> (b.toInt() and 0xff).toString(16).padStart(2, '0') }

    suspend fun importUri(uri: Uri, source: TxSource, onProgress: (String) -> Unit = {}): Outcome {
        val bytes = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } ?: return Outcome(false, null, "تعذر فتح الملف")
        val name = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        return import(bytes, name, source, onProgress)
    }

    suspend fun import(bytes: ByteArray, fileName: String?, source: TxSource, onProgress: (String) -> Unit = {}): Outcome {
        if (bytes.isEmpty()) return Outcome(false, null, "الملف فارغ")
        if (bytes.size > 25 * 1024 * 1024) return Outcome(false, null, "الملف كبير جداً")
        val hash = sha256(bytes)
        repo.byFileHash(hash)?.let { existing ->
            return Outcome(true, SaveResult.Duplicate(existing.id, "نفس الملف"), "♻️ هذا الملف تمت إضافته سابقاً، لم تتم إضافته مرة أخرى.\n" + describe(existing))
        }

        val isPdf = PageImages.isPdf(bytes)
        val file = withContext(Dispatchers.IO) {
            File(receiptsDir(), hash.take(24) + if (isPdf) ".pdf" else ".jpg").apply { if (!exists()) writeBytes(bytes) }
        }

        onProgress("تجهيز الصفحات…")
        val pages = try {
            withContext(Dispatchers.IO) { PageImages.load(file) }
        } catch (e: Exception) {
            return Outcome(false, null, "تعذر قراءة الملف: ${e.message ?: "ملف PDF غير صالح"}")
        }
        if (pages.isEmpty()) return Outcome(false, null, "الملف لا يحتوي صفحات أو صوراً قابلة للقراءة")

        val analysis = try {
            analyzer.analyze(pages, onProgress)
        } finally {
            pages.forEach { it.recycle() }
        }
        val p = analysis.parsed
        if (!analysis.isReceipt || p.amount == null) {
            val why = analysis.aiError?.let { "\nملاحظة: $it" }.orEmpty()
            return Outcome(false, null, "لم أتمكن من العثور على معاملة في هذا الملف.$why", analysis)
        }

        val s = settings.current()
        val identity = DirectionResolver.Identity(s.ownerNames, s.ownerAccounts)
        val resolution = DirectionResolver.resolve(p, identity)
        val directionDoubt = when {
            resolution.certain -> null
            p.operationKind == OperationKind.UNKNOWN -> "لم يتضح إن كانت إرسالاً أم استلاماً"
            !identity.isEmpty -> "اسمك المسجل في الإعدادات غير موجود في الإيصال — تأكد من نوع العملية"
            else -> null
        }
        val reviewReason = listOfNotNull(analysis.reviewReason, directionDoubt).joinToString("\n").ifEmpty { null }

        val draft = TransactionDraft(
            type = resolution.type,
            amount = p.amount,
            currency = p.currency ?: s.primaryCurrency,
            counterpartyName = resolution.counterpartyName,
            counterpartyAccount = resolution.counterpartyAccount,
            senderName = p.senderName,
            senderAccount = p.senderAccount,
            receiverName = p.receiverName,
            receiverAccount = p.receiverAccount,
            reference = p.reference,
            timestamp = p.dateTimeMillis ?: System.currentTimeMillis(),
            hasTime = p.dateTimeMillis != null && p.hasTime,
            operationLabel = p.operationLabel,
            fee = p.fee,
            note = p.note,
            source = if (isPdf) source else if (source == TxSource.PDF) TxSource.IMAGE else source,
            rawText = p.rawText ?: analysis.ocrText,
            filePath = file.absolutePath,
            fileHash = hash,
            needsReview = reviewReason != null,
            reviewReason = reviewReason,
            alternativesJson = analysis.alternativesJson,
        )
        onProgress("حفظ المعاملة…")
        val result = repo.save(draft, DedupPolicy.AUTO_MERGE)
        val saved = repo.get(result.id)
        val head = when (result) {
            is SaveResult.Added -> "✅ تمت إضافة معاملة جديدة"
            is SaveResult.Merged -> "🔗 هذه المعاملة موجودة مسبقاً (${result.reason})، تم تحديث تفاصيلها بدون تكرار"
            is SaveResult.Duplicate -> "♻️ هذه المعاملة موجودة مسبقاً (${result.reason})، لم تتم إضافتها مرة أخرى"
            is SaveResult.PossibleDuplicate -> "⚠️ قد تكون مكررة"
        }
        val body = saved?.let { describe(it) }.orEmpty()
        val review = if (saved?.needsReview == true) "\n⚠️ تحتاج مراجعة: ${saved.reviewReason.orEmpty()}" else ""
        val ownerCandidate = if (identity.isEmpty) resolution.ownerCandidate else null
        return Outcome(true, result, "$head\n$body$review", analysis, ownerCandidate)
    }

    companion object {
        /** Multi-line Arabic summary used in dialogs and Telegram replies. */
        fun describe(t: TransactionEntity): String = buildString {
            val arrow = if (t.type == TxType.SENT) "↗️" else "↙️"
            append(arrow).append(' ').append(TxFormat.typeLabel(t.type)).append(' ')
            append(ArabicText.ltr(Money.signed(t.amount, t.currency, t.type)))
            append(if (t.type == TxType.SENT) " إلى " else " من ").append(t.displayName)
            append("\n📅 ").append(TxFormat.full(t.timestamp, t.hasTime))
            t.senderName?.let { append("\n👤 المرسل: ").append(it) }
            t.receiverName?.let { append("\n👤 المستلم: ").append(it) }
            t.reference?.let { append("\n🔢 رقم العملية: ").append(it) }
            t.fee?.takeIf { it > 0 }?.let { append("\n💸 العمولة: ").append(Money.withCurrency(it, t.currency)) }
            t.note?.let { append("\n📝 ").append(it) }
        }
    }
}
