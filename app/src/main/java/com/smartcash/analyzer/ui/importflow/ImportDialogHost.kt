package com.smartcash.analyzer.ui.importflow

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartcash.analyzer.analyze.ImportController
import com.smartcash.analyzer.domain.TxType
import com.smartcash.analyzer.ui.LocalContainer
import kotlinx.coroutines.launch

/** Shows import progress and results (for files picked in the app or shared from other apps). */
@Composable
fun ImportDialogHost() {
    val c = LocalContainer.current
    val state by c.importController.state.collectAsStateWithLifecycle()
    when (val s = state) {
        is ImportController.State.Idle -> Unit
        is ImportController.State.Running -> AlertDialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
            title = { Text(if (s.total > 1) "تحليل الملف ${s.index} من ${s.total}" else "تحليل الإيصال") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.width(16.dp))
                    Text(s.step, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {},
        )
        is ImportController.State.Done -> {
            val answered = remember(s) { mutableStateMapOf<Int, String>() }
            AlertDialog(
                onDismissRequest = { c.importController.dismiss() },
                title = { Text(if (s.outcomes.all { it.ok }) "نتيجة التحليل" else "انتهى التحليل") },
                text = {
                    Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        s.outcomes.forEachIndexed { i, o ->
                            if (i > 0) HorizontalDivider()
                            Text(o.message, style = MaterialTheme.typography.bodyMedium)
                            val candidate = o.ownerCandidate
                            val txId = o.transactionId
                            if (candidate != null && txId != null) {
                                val reply = answered[i]
                                if (reply != null) {
                                    Text(reply, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                                } else {
                                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)) {
                                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("هل اسمك في شام كاش هو «$candidate»؟", style = MaterialTheme.typography.titleSmall)
                                            Text("نحفظه مرة واحدة ليعرف التطبيق تلقائياً إن كانت العملية إرسالاً أم استلاماً.", style = MaterialTheme.typography.bodySmall)
                                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                Button(onClick = {
                                                    c.importController.confirmOwner(candidate)
                                                    answered[i] = "✅ تم حفظ «$candidate» كاسمك"
                                                }) { Text("نعم، هذا أنا") }
                                                OutlinedButton(onClick = {
                                                    c.scope.launch {
                                                        val tx = c.repo.get(txId) ?: return@launch
                                                        // I am the other party: flip the direction and remember my name
                                                        val me = tx.counterpartyName
                                                        c.repo.setDirection(txId, if (tx.type == TxType.SENT) TxType.RECEIVED else TxType.SENT)
                                                        if (me.isNotBlank()) c.settings.addOwnerName(me)
                                                    }
                                                    answered[i] = "✅ تم تصحيح نوع العملية"
                                                }) { Text("لا") }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { c.importController.dismiss() }) { Text("تم") } },
            )
        }
    }
}
