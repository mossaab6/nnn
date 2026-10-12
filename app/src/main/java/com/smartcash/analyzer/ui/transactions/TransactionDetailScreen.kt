package com.smartcash.analyzer.ui.transactions

import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.smartcash.analyzer.analyze.ReceiptImporter
import com.smartcash.analyzer.data.db.TransactionEntity
import com.smartcash.analyzer.domain.ArabicText
import com.smartcash.analyzer.domain.Money
import com.smartcash.analyzer.domain.TxFormat
import com.smartcash.analyzer.domain.TxSource
import com.smartcash.analyzer.domain.TxType
import com.smartcash.analyzer.ui.LocalContainer
import com.smartcash.analyzer.ui.Routes
import com.smartcash.analyzer.ui.components.AmountText
import com.smartcash.analyzer.ui.components.BackButton
import com.smartcash.analyzer.ui.components.ConfirmDialog
import com.smartcash.analyzer.ui.components.DirectionIcon
import com.smartcash.analyzer.ui.components.InfoRow
import com.smartcash.analyzer.ui.components.Pill
import com.smartcash.analyzer.ui.theme.LocalMoneyColors
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

@Composable
fun TransactionDetailScreen(nav: NavHostController, id: Long) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tx by remember(id) { c.repo.observe(id) }.collectAsStateWithLifecycle(initialValue = null)
    var confirmDelete by remember { mutableStateOf(false) }
    var showRaw by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("تفاصيل المعاملة") },
                navigationIcon = { BackButton { nav.popBackStack() } },
                actions = {
                    tx?.let { t ->
                        IconButton(onClick = {
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, ReceiptImporter.describe(t))
                            context.startActivity(Intent.createChooser(send, "مشاركة"))
                        }) { Icon(Icons.Rounded.Share, "مشاركة") }
                        IconButton(onClick = { nav.navigate(Routes.edit(t.id)) }) { Icon(Icons.Rounded.Edit, "تعديل") }
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, "حذف", tint = MaterialTheme.colorScheme.error) }
                    }
                },
            )
        },
    ) { padding ->
        val t = tx ?: return@Scaffold
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // header
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    DirectionIcon(t.type, 64.dp)
                    Spacer(Modifier.height(10.dp))
                    AmountText(t.amount, t.currency, t.type, style = MaterialTheme.typography.headlineMedium)
                    Text("${TxFormat.typeVerb(t.type)} ${t.displayName}", style = MaterialTheme.typography.titleMedium)
                    Text(TxFormat.full(t.timestamp, t.hasTime), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        t.sources.split(',').filter { it.isNotBlank() }.forEach { src ->
                            val label = runCatching { TxSource.valueOf(src).label }.getOrDefault(src)
                            Pill(label, MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }

            if (t.needsReview) ReviewCard(t)

            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(20.dp)) {
                SelectionContainer {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        InfoRow("نوع العملية", (t.operationLabel?.let { "$it · " } ?: "") + TxFormat.typeLabel(t.type))
                        InfoRow("رقم العملية", t.reference, ltrValue = true)
                        InfoRow("التاريخ", TxFormat.full(t.timestamp, t.hasTime))
                        InfoRow("اسم المرسل", t.senderName)
                        InfoRow("حساب المرسل", t.senderAccount, ltrValue = true)
                        InfoRow("اسم المستلم", t.receiverName)
                        InfoRow("حساب المستلم", t.receiverAccount, ltrValue = true)
                        InfoRow("المبلغ", Money.withCurrency(t.amount, t.currency), ltrValue = true)
                        InfoRow("العمولة", t.fee?.takeIf { it > 0 }?.let { Money.withCurrency(it, t.currency) }, ltrValue = true)
                        InfoRow("الرصيد بعدها", t.balanceAfter?.let { Money.withCurrency(it, t.currency) }, ltrValue = true)
                        InfoRow("الملاحظة", t.note)
                        InfoRow("أضيفت في", TxFormat.full(t.createdAt))
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (t.counterpartyKey.isNotBlank()) {
                    OutlinedButton(onClick = { nav.navigate(Routes.person(t.counterpartyKey)) }, modifier = Modifier.weight(1f)) { Text("كل معاملات الشخص") }
                }
                val path = t.filePath
                if (path != null && File(path).exists()) {
                    Button(onClick = {
                        val file = File(path)
                        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                        val mime = if (file.name.endsWith(".pdf")) "application/pdf" else "image/*"
                        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        try {
                            context.startActivity(Intent.createChooser(view, "فتح الإيصال"))
                        } catch (e: ActivityNotFoundException) {
                            Toast.makeText(context, "لا يوجد تطبيق لفتح الملف", Toast.LENGTH_SHORT).show()
                        }
                    }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.PictureAsPdf, null)
                        Text(" فتح الإيصال")
                    }
                }
            }

            if (!t.rawText.isNullOrBlank()) {
                TextButton(onClick = { showRaw = !showRaw }) { Text(if (showRaw) "إخفاء النص المقروء" else "عرض النص المقروء (OCR)") }
                if (showRaw) {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        SelectionContainer { Text(t.rawText, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        tx?.let { t ->
            ConfirmDialog(
                title = "حذف المعاملة؟",
                text = "سيتم حذف ${TxFormat.typeLabel(t.type)} ${Money.withCurrency(t.amount, t.currency)} - ${t.displayName}. لا يمكن التراجع.",
                confirmText = "حذف",
                destructive = true,
                onConfirm = { scope.launch { c.repo.delete(t.id); nav.popBackStack() } },
                onDismiss = { confirmDelete = false },
            )
        }
    }
}

@Composable
private fun ReviewCard(t: TransactionEntity) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val money = LocalMoneyColors.current
    val alternatives = remember(t.alternativesJson) {
        runCatching {
            Json.parseToJsonElement(t.alternativesJson ?: "{}").jsonObject.map { (field, v) ->
                val o = v.jsonObject
                Triple(field, o["label"]?.jsonPrimitive?.contentOrNull ?: field, o["ocr"]?.jsonPrimitive?.contentOrNull to o["ai"]?.jsonPrimitive?.contentOrNull)
            }
        }.getOrDefault(emptyList())
    }
    Surface(shape = RoundedCornerShape(20.dp), color = money.warning.copy(alpha = 0.12f)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.WarningAmber, null, tint = money.warning)
                Text("  تحتاج مراجعة", fontWeight = FontWeight.Bold)
            }
            t.reviewReason?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            alternatives.forEach { (field, label, pair) ->
                HorizontalDivider()
                Text(label, style = MaterialTheme.typography.labelLarge)
                val (ocr, ai) = pair
                if (field == "senderName" || field == "receiverName") {
                    listOfNotNull(ai?.let { "قراءة Grok" to it }, ocr?.let { "قراءة OCR" to it }).forEach { (src, value) ->
                        OutlinedButton(onClick = {
                            scope.launch {
                                val updated = if (field == "senderName") t.copy(senderName = value) else t.copy(receiverName = value)
                                // the counterparty is the other side of the transaction
                                val isCounterparty = (field == "receiverName" && t.type == TxType.SENT) || (field == "senderName" && t.type == TxType.RECEIVED)
                                c.repo.update(if (isCounterparty) updated.copy(counterpartyName = value) else updated)
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("$src: ${ArabicText.ltr(value)}") }
                    }
                } else {
                    Text("Grok: ${ai ?: "-"}   ·   OCR: ${ocr ?: "-"}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("هل نوع العملية صحيح؟", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { scope.launch { c.repo.setDirection(t.id, TxType.SENT) } }, modifier = Modifier.weight(1f)) { Text("↗ إرسال") }
                OutlinedButton(onClick = { scope.launch { c.repo.setDirection(t.id, TxType.RECEIVED) } }, modifier = Modifier.weight(1f)) { Text("↙ استلام") }
            }
            Button(onClick = { scope.launch { c.repo.markReviewed(t.id) } }, modifier = Modifier.fillMaxWidth()) { Text("البيانات صحيحة ✔") }
        }
    }
}
