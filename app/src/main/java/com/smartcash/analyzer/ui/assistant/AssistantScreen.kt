package com.smartcash.analyzer.ui.assistant

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.smartcash.analyzer.ai.AiNotConfiguredException
import com.smartcash.analyzer.ai.AssistantEngine
import com.smartcash.analyzer.data.db.ChatMessageEntity
import com.smartcash.analyzer.data.db.PendingActionEntity
import com.smartcash.analyzer.domain.TxSource
import com.smartcash.analyzer.ui.LocalContainer
import com.smartcash.analyzer.ui.Routes
import com.smartcash.analyzer.ui.components.ConfirmDialog
import com.smartcash.analyzer.ui.navigateTab
import com.smartcash.analyzer.ui.theme.LocalMoneyColors
import kotlinx.coroutines.launch

private val suggestions = listOf(
    "كم رصيدي الآن؟",
    "كم أرسلت هذا الأسبوع؟",
    "من أكثر شخص أرسلت له؟",
    "كم استلمت اليوم؟",
    "اعرض آخر 5 معاملات",
    "ما المعاملات التي تحتاج مراجعة؟",
)

@Composable
fun AssistantScreen(nav: NavHostController) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val channel = AssistantEngine.CHANNEL_APP
    val messages by remember { c.assistant.observe(channel) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val actions by remember { c.db.actions().observeAll() }.collectAsStateWithLifecycle(initialValue = emptyList())
    val settings by c.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val actionMap = actions.associateBy { it.id }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) c.importController.import(uris, TxSource.PDF)
    }

    fun send(text: String) {
        if (text.isBlank() || busy) return
        input = ""
        busy = true
        error = null
        scope.launch {
            try {
                c.assistant.ask(channel, text.trim())
            } catch (e: AiNotConfiguredException) {
                error = e.message
            } catch (e: Exception) {
                error = e.message ?: "حدث خطأ"
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(messages.size, busy) {
        val count = messages.size + if (busy) 1 else 0
        if (count > 0) listState.animateScrollToItem(count - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                        Text("  المساعد الذكي (Grok)")
                    }
                },
                actions = { if (messages.isNotEmpty()) IconButton(onClick = { confirmClear = true }) { Icon(Icons.Rounded.DeleteSweep, "مسح المحادثة") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (settings?.aiConfigured == false) {
                Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Key, null)
                        Text("  أضف مفتاح Grok من الإعدادات لتفعيل المساعد", modifier = Modifier.weight(1f))
                        OutlinedButton(onClick = { nav.navigateTab(Routes.SETTINGS) }) { Text("الإعدادات") }
                    }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (messages.isEmpty()) {
                    item {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                            Spacer(Modifier.size(8.dp))
                            Text("اسألني عن معاملاتك", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "أعرف رصيدك ومن أرسلت لهم ومن استلمت منهم. أستطيع أيضاً حذف أو تثبيت أشخاص وإضافة معاملات — بعد تأكيدك فقط.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                items(messages, key = { it.id }) { m -> MessageItem(m, m.actionId?.let { actionMap[it] }) }
                if (busy) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("  يفكر…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
            if (messages.isEmpty()) {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(suggestions) { s -> AssistChip(onClick = { send(s) }, label = { Text(s) }) }
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { picker.launch(arrayOf("application/pdf", "image/*")) }) { Icon(Icons.Rounded.AttachFile, "إرفاق إيصال") }
                OutlinedTextField(
                    value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text("اكتب سؤالك…") }, maxLines = 4, shape = RoundedCornerShape(24.dp),
                )
                Spacer(Modifier.size(6.dp))
                FilledIconButton(onClick = { send(input) }, enabled = input.isNotBlank() && !busy) { Icon(Icons.AutoMirrored.Rounded.Send, "إرسال") }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog("مسح المحادثة؟", "سيتم حذف كل رسائل المحادثة (لا يؤثر على المعاملات).", "مسح", true,
            onConfirm = { scope.launch { c.assistant.clear(channel) } }, onDismiss = { confirmClear = false })
    }
}

@Composable
private fun MessageItem(m: ChatMessageEntity, action: PendingActionEntity?) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val money = LocalMoneyColors.current
    when (m.role) {
        "user" -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
            Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp), modifier = Modifier.widthIn(max = 320.dp)) {
                Text(m.content, color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
        "action" -> {
            Surface(color = money.warning.copy(alpha = 0.12f), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text("⚠️ يحتاج تأكيدك", fontWeight = FontWeight.Bold)
                    Text(m.content, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.size(8.dp))
                    if (action != null) when (action.status) {
                        "PENDING" -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = {
                                scope.launch {
                                    val r = c.actions.confirm(action.id)
                                    c.assistant.addEvent(AssistantEngine.CHANNEL_APP, r)
                                }
                            }) { Text("تأكيد") }
                            OutlinedButton(onClick = {
                                scope.launch {
                                    val r = c.actions.cancel(action.id)
                                    c.assistant.addEvent(AssistantEngine.CHANNEL_APP, r)
                                }
                            }) { Text("إلغاء") }
                        }
                        "DONE" -> Text("✅ تم التنفيذ", color = money.received)
                        "CANCELLED" -> Text("❌ تم الإلغاء", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        "FAILED" -> Text("فشل: ${action.resultMessage.orEmpty()}", color = MaterialTheme.colorScheme.error)
                        else -> Unit
                    }
                }
            }
        }
        "event" -> Text(m.content, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp))
        else -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp), modifier = Modifier.widthIn(max = 340.dp)) {
                SelectionContainer { Text(m.content, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) }
            }
        }
    }
}
