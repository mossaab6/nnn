package com.smartcash.analyzer.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.smartcash.analyzer.analyze.ReceiptImporter
import com.smartcash.analyzer.data.DedupPolicy
import com.smartcash.analyzer.data.SaveResult
import com.smartcash.analyzer.data.TransactionDraft
import com.smartcash.analyzer.data.db.TransactionEntity
import com.smartcash.analyzer.domain.AmountParser
import com.smartcash.analyzer.domain.ArabicText
import com.smartcash.analyzer.domain.Money
import com.smartcash.analyzer.domain.TxFormat
import com.smartcash.analyzer.domain.TxSource
import com.smartcash.analyzer.domain.TxType
import com.smartcash.analyzer.ui.LocalContainer
import com.smartcash.analyzer.ui.Routes
import com.smartcash.analyzer.ui.components.BackButton
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Manual add / edit form. Required: type, amount, the other person's name. Everything else is optional. */
@Composable
fun EditTransactionScreen(nav: NavHostController, id: Long, presetPerson: String, presetType: String) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val zone = ZoneId.systemDefault()
    val settings by c.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    val people by remember { c.repo.observePeople() }.collectAsStateWithLifecycle(initialValue = emptyList())

    var existing by remember { mutableStateOf<TransactionEntity?>(null) }
    var loaded by remember { mutableStateOf(id == 0L) }
    var type by remember { mutableStateOf(if (presetType == "received") TxType.RECEIVED else TxType.SENT) }
    var amount by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("USD") }
    var sender by remember { mutableStateOf("") }
    var receiver by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now(zone)) }
    var time by remember { mutableStateOf(LocalTime.now(zone).withSecond(0).withNano(0)) }
    var reference by remember { mutableStateOf("") }
    var senderAcc by remember { mutableStateOf("") }
    var receiverAcc by remember { mutableStateOf("") }
    var fee by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var duplicate by remember { mutableStateOf<Pair<TransactionDraft, TransactionEntity>?>(null) }
    var smartText by remember { mutableStateOf("") }
    var smartBusy by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }

    // initial values
    LaunchedEffect(id, settings != null) {
        val s = settings ?: return@LaunchedEffect
        if (id != 0L && existing == null) {
            val t = c.repo.get(id) ?: return@LaunchedEffect
            existing = t
            type = t.type; amount = Money.format(t.amount).replace(",", ""); currency = t.currency
            sender = t.senderName ?: if (t.type == TxType.RECEIVED) t.counterpartyName else ""
            receiver = t.receiverName ?: if (t.type == TxType.SENT) t.counterpartyName else ""
            val dt = Instant.ofEpochMilli(t.timestamp).atZone(zone)
            date = dt.toLocalDate(); time = dt.toLocalTime().withNano(0)
            reference = t.reference.orEmpty(); senderAcc = t.senderAccount.orEmpty(); receiverAcc = t.receiverAccount.orEmpty()
            fee = t.fee?.let { Money.format(it) }.orEmpty(); note = t.note.orEmpty()
            loaded = true
        } else if (id == 0L && amount.isEmpty() && sender.isEmpty() && receiver.isEmpty()) {
            currency = s.primaryCurrency
            val me = s.ownerNames.firstOrNull().orEmpty()
            if (type == TxType.SENT) { sender = me; receiver = presetPerson } else { receiver = me; sender = presetPerson }
        }
    }

    fun buildDraft(): TransactionDraft? {
        val amt = AmountParser.parseNumber(amount)
        if (amt == null || amt <= 0) { error = "أدخل المبلغ"; return null }
        val counterparty = if (type == TxType.SENT) receiver else sender
        if (counterparty.isBlank()) { error = if (type == TxType.SENT) "أدخل اسم المستلم" else "أدخل اسم المرسل"; return null }
        error = null
        return TransactionDraft(
            type = type, amount = amt, currency = currency,
            counterpartyName = counterparty.trim(),
            counterpartyAccount = (if (type == TxType.SENT) receiverAcc else senderAcc).ifBlank { null },
            senderName = sender.trim().ifBlank { null }, senderAccount = senderAcc.ifBlank { null },
            receiverName = receiver.trim().ifBlank { null }, receiverAccount = receiverAcc.ifBlank { null },
            reference = reference.ifBlank { null },
            timestamp = LocalDateTime.of(date, time).atZone(zone).toInstant().toEpochMilli(),
            hasTime = true,
            fee = AmountParser.parseNumber(fee),
            note = note.trim().ifBlank { null },
            source = TxSource.MANUAL,
        )
    }

    fun save(force: Boolean) {
        val d = buildDraft() ?: return
        scope.launch {
            val old = existing
            if (old != null) {
                c.repo.update(
                    old.copy(
                        type = d.type, amount = d.amount, currency = d.currency, fee = d.fee,
                        counterpartyName = d.counterpartyName ?: "", counterpartyAccount = d.counterpartyAccount,
                        senderName = d.senderName, senderAccount = d.senderAccount, receiverName = d.receiverName, receiverAccount = d.receiverAccount,
                        reference = ArabicText.digits(d.reference).ifEmpty { null }, timestamp = d.timestamp, hasTime = true, note = d.note,
                        needsReview = false, reviewReason = null,
                    )
                )
                nav.popBackStack()
                return@launch
            }
            when (val r = c.repo.save(d, if (force) DedupPolicy.FORCE_NEW else DedupPolicy.ASK)) {
                is SaveResult.PossibleDuplicate -> c.repo.get(r.id)?.let { duplicate = d to it }
                else -> {
                    nav.popBackStack()
                    nav.navigate(Routes.tx(r.id))
                }
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(if (id == 0L) "إضافة معاملة" else "تعديل المعاملة") }, navigationIcon = { BackButton { nav.popBackStack() } }) },
    ) { padding ->
        if (!loaded) return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (id == 0L && settings?.aiConfigured == true) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                            Text("  تعبئة ذكية بالذكاء الاصطناعي", style = MaterialTheme.typography.titleSmall)
                        }
                        OutlinedTextField(
                            value = smartText, onValueChange = { smartText = it }, modifier = Modifier.fillMaxWidth(), minLines = 2,
                            placeholder = { Text("مثال: أرسلت 50 دولار لـ Ahmad.77 أمس الساعة 3 ملاحظة إيجار") },
                        )
                        OutlinedButton(enabled = smartText.isNotBlank() && !smartBusy, onClick = {
                            smartBusy = true
                            scope.launch {
                                val s = c.settings.current()
                                val r = runCatching { c.extractor.readFreeText(smartText, s.ownerNames.firstOrNull(), LocalDateTime.now(zone).withNano(0).toString()) }
                                smartBusy = false
                                val f = r.getOrNull()
                                if (f == null) { error = "تعذرت التعبئة: ${r.exceptionOrNull()?.message ?: "لم أفهم النص"}"; return@launch }
                                f.type?.let { type = if (it == "received") TxType.RECEIVED else TxType.SENT }
                                f.amount?.let { amount = Money.format(it).replace(",", "") }
                                f.currency?.let { currency = it }
                                val me = s.ownerNames.firstOrNull().orEmpty()
                                sender = f.senderName ?: if (type == TxType.SENT) me else sender
                                receiver = f.receiverName ?: if (type == TxType.RECEIVED) me else receiver
                                f.dateTimeMillis?.let { ms ->
                                    val dt = Instant.ofEpochMilli(ms).atZone(zone)
                                    date = dt.toLocalDate(); if (f.hasTime) time = dt.toLocalTime().withNano(0)
                                }
                                f.reference?.let { reference = it }
                                f.note?.let { note = it }
                            }
                        }) {
                            if (smartBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("املأ الحقول تلقائياً")
                        }
                    }
                }
            }

            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = type == TxType.SENT, onClick = {
                    if (type != TxType.SENT) { type = TxType.SENT; val t = sender; sender = receiver; receiver = t }
                }, shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text("↗ إرسال") }
                SegmentedButton(selected = type == TxType.RECEIVED, onClick = {
                    if (type != TxType.RECEIVED) { type = TxType.RECEIVED; val t = sender; sender = receiver; receiver = t }
                }, shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text("↙ استلام") }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it }, label = { Text("المبلغ *") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f),
                )
                CurrencyPicker(currency, { currency = it }, Modifier.weight(0.7f))
            }

            NameField("اسم المرسل" + if (type == TxType.RECEIVED) " *" else "", sender, { sender = it }, people.map { it.person.displayName })
            NameField("اسم المستلم" + if (type == TxType.SENT) " *" else "", receiver, { receiver = it }, people.map { it.person.displayName })

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showDate = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Rounded.CalendarMonth, null); Text("  ${date.dayOfMonth} ${TxFormat.monthName(date.monthValue)} ${date.year}")
                }
                OutlinedButton(onClick = { showTime = true }, modifier = Modifier.weight(0.7f)) {
                    Icon(Icons.Rounded.Schedule, null); Text("  ${"%02d:%02d".format(java.util.Locale.US, time.hour, time.minute)}")
                }
            }

            Text("حقول اختيارية", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(reference, { reference = it }, label = { Text("رقم العملية (اختياري)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(senderAcc, { senderAcc = it }, label = { Text("حساب المرسل (اختياري)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(receiverAcc, { receiverAcc = it }, label = { Text("حساب المستلم (اختياري)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(fee, { fee = it }, label = { Text("العمولة (اختياري)") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            OutlinedTextField(note, { note = it }, label = { Text("ملاحظة (اختياري)") }, modifier = Modifier.fillMaxWidth(), minLines = 2)

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { save(force = false) }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("حفظ") }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    showDate = false
                }) { Text("تم") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("إلغاء") } },
        ) { DatePicker(state) }
    }
    if (showTime) {
        val state = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = { TextButton(onClick = { time = LocalTime.of(state.hour, state.minute); showTime = false }) { Text("تم") } },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("إلغاء") } },
            text = { TimePicker(state) },
        )
    }
    duplicate?.let { (_, existingTx) ->
        AlertDialog(
            onDismissRequest = { duplicate = null },
            title = { Text("يبدو أنها مكررة") },
            text = { Text("توجد معاملة مشابهة مسجلة مسبقاً:\n\n" + ReceiptImporter.describe(existingTx) + "\n\nهل تريد حفظها كمعاملة جديدة على أي حال؟") },
            confirmButton = { TextButton(onClick = { duplicate = null; save(force = true) }) { Text("حفظ على أي حال") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { duplicate = null; nav.navigate(Routes.tx(existingTx.id)) }) { Text("عرض الموجودة") }
                    TextButton(onClick = { duplicate = null }) { Text("إلغاء") }
                }
            },
        )
    }
}

@Composable
private fun CurrencyPicker(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = modifier) {
        OutlinedTextField(
            value = "$value ${Money.symbol(value)}", onValueChange = {}, readOnly = true, label = { Text("العملة") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Money.supportedCurrencies.forEach { cur ->
                DropdownMenuItem(text = { Text("${Money.displayName(cur)} ($cur)") }, onClick = { onChange(cur); open = false })
            }
        }
    }
}

/** Text field with suggestions from known people (names are kept exactly as typed or picked). */
@Composable
private fun NameField(label: String, value: String, onChange: (String) -> Unit, suggestions: List<String>) {
    var open by remember { mutableStateOf(false) }
    val key = ArabicText.nameKey(value)
    val matches = if (key.length < 1) emptyList() else suggestions.filter { ArabicText.nameKey(it).contains(key) && it != value }.take(6)
    ExposedDropdownMenuBox(expanded = open && matches.isNotEmpty(), onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = value, onValueChange = { onChange(it); open = true }, label = { Text(label) }, singleLine = true,
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = open && matches.isNotEmpty(), onDismissRequest = { open = false }) {
            matches.forEach { m -> DropdownMenuItem(text = { Text(m) }, onClick = { onChange(m); open = false }) }
        }
    }
}
