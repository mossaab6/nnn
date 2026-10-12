package com.smartcash.analyzer.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.Receipt
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.SouthWest
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import com.smartcash.analyzer.AppContainer
import com.smartcash.analyzer.data.db.TransactionEntity
import com.smartcash.analyzer.domain.AmountParser
import com.smartcash.analyzer.domain.Money
import com.smartcash.analyzer.domain.Period
import com.smartcash.analyzer.domain.Periods
import com.smartcash.analyzer.domain.Summary
import com.smartcash.analyzer.domain.SummaryCalculator
import com.smartcash.analyzer.domain.TxSource
import com.smartcash.analyzer.ui.LocalContainer
import com.smartcash.analyzer.ui.Routes
import com.smartcash.analyzer.ui.components.EmptyState
import com.smartcash.analyzer.ui.components.SectionHeader
import com.smartcash.analyzer.ui.components.TransactionRow
import com.smartcash.analyzer.ui.components.WeekChart
import com.smartcash.analyzer.ui.containerViewModel
import com.smartcash.analyzer.ui.navigateTab
import com.smartcash.analyzer.ui.theme.LocalMoneyColors
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    data class UiState(
        val summary: Summary,
        val currencies: List<String>,
        val currency: String,
        val period: Period,
        val recent: List<TransactionEntity>,
        val week: List<Triple<LocalDate, Double, Double>>,
        val reviewCount: Int,
        val ownerName: String?,
        val telegramConfigured: Boolean,
    )

    val period = MutableStateFlow(Period.ALL)
    private val currency = MutableStateFlow<String?>(null)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    val state: StateFlow<UiState?> = combine(c.repo.observeAll(), c.settings.flow, period, currency) { txs, s, p, cur ->
        val currencies = (listOf(s.primaryCurrency) + s.balances.keys + txs.map { it.currency }).distinct()
        val selected = cur?.takeIf { it in currencies } ?: s.primaryCurrency
        val lites = txs.map { it.toLite() }
        val from = Periods.startOf(p, System.currentTimeMillis())
        UiState(
            summary = SummaryCalculator.summarize(lites, selected, p, s.balances[selected]),
            currencies = currencies,
            currency = selected,
            period = p,
            recent = txs.filter { it.timestamp >= from }.take(40),
            week = SummaryCalculator.daily(lites, selected, 7),
            reviewCount = txs.count { it.needsReview },
            ownerName = s.ownerNames.firstOrNull(),
            telegramConfigured = s.telegramConfigured && s.telegramChatId.isNotBlank(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setPeriod(p: Period) { period.value = p }
    fun setCurrency(cur: String) { currency.value = cur }

    fun setBalance(currency: String, amount: Double) = viewModelScope.launch {
        c.settings.setBalance(currency, amount)
        messages.emit("تم ضبط الرصيد الحالي")
    }

    fun syncTelegram() = viewModelScope.launch {
        val n = runCatching { c.botEngine.syncOnce() }
        messages.emit(
            n.fold(
                onSuccess = { if (it < 0) "المزامنة تعمل بالفعل" else if (it == 0) "لا توجد رسائل جديدة في تيليجرام" else "تمت معالجة $it رسالة من تيليجرام" },
                onFailure = { "تعذر الاتصال بتيليجرام: ${it.message}" },
            )
        )
    }
}

@Composable
fun HomeScreen(nav: NavHostController) {
    val vm = containerViewModel { HomeViewModel(it) }
    val c = LocalContainer.current
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editBalance by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) c.importController.import(uris, TxSource.PDF)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("محلل شام كاش", style = MaterialTheme.typography.titleLarge)
                        state?.ownerName?.let { Text("أهلاً $it 👋", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                },
                actions = {
                    val review = state?.reviewCount ?: 0
                    if (review > 0) {
                        IconButton(onClick = { nav.navigate(Routes.transactions("review")) }) {
                            BadgedBox(badge = { Badge { Text("$review") } }) { Icon(Icons.Rounded.WarningAmber, "تحتاج مراجعة") }
                        }
                    }
                    if (state?.telegramConfigured == true) {
                        IconButton(onClick = { vm.syncTelegram() }) { Icon(Icons.Rounded.Sync, "مزامنة تيليجرام") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.navigate(Routes.edit()) },
                icon = { Icon(Icons.Rounded.Add, null) },
                text = { Text("إضافة") },
                containerColor = MaterialTheme.colorScheme.primary,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val s = state ?: return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 96.dp)) {
            item {
                BalanceCard(s, onPeriod = vm::setPeriod, onCurrency = vm::setCurrency, onBalanceClick = { editBalance = true })
            }
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    QuickAction(Icons.Rounded.UploadFile, "استيراد PDF") { picker.launch(arrayOf("application/pdf", "image/*")) }
                    QuickAction(Icons.Rounded.EditNote, "إضافة يدوية") { nav.navigate(Routes.edit()) }
                    QuickAction(Icons.Rounded.DocumentScanner, "مسح ضوئي OCR") { nav.navigate(Routes.OCR) }
                    QuickAction(Icons.Rounded.AutoAwesome, "اسأل المساعد") { nav.navigateTab(Routes.ASSISTANT) }
                }
            }
            if (s.reviewCount > 0) {
                item {
                    val money = LocalMoneyColors.current
                    Surface(
                        color = money.warning.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                            .clip(RoundedCornerShape(16.dp)).clickable { nav.navigate(Routes.transactions("review")) },
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.WarningAmber, null, tint = money.warning)
                            Spacer(Modifier.width(10.dp))
                            Text("${s.reviewCount} معاملة تحتاج مراجعة (أسماء أو نوع غير مؤكد)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("آخر 7 أيام (${Money.symbol(s.currency)})", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(12.dp))
                        WeekChart(s.week)
                    }
                }
            }
            item {
                SectionHeader("المعاملات · ${s.period.label}", action = "عرض الكل", onAction = { nav.navigateTab(Routes.TRANSACTIONS) })
            }
            if (s.recent.isEmpty()) {
                item {
                    EmptyState(
                        Icons.Rounded.Receipt,
                        "لا توجد معاملات ${if (s.period == Period.ALL) "بعد" else s.period.label}",
                        "استورد إيصال PDF من شام كاش، أرسله لبوت تيليجرام، أو فعّل قراءة الإشعارات.",
                    )
                }
            } else {
                items(s.recent, key = { it.id }) { tx ->
                    TransactionRow(tx, onClick = { nav.navigate(Routes.tx(tx.id)) }, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
        }
    }

    if (editBalance) {
        state?.let { s -> BalanceDialog(s.currency, s.summary.balance, onSave = { vm.setBalance(s.currency, it) }, onDismiss = { editBalance = false }) }
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.width(80.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2)
    }
}

@Composable
private fun BalanceCard(s: HomeViewModel.UiState, onPeriod: (Period) -> Unit, onCurrency: (String) -> Unit, onBalanceClick: () -> Unit) {
    val money = LocalMoneyColors.current
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).clip(RoundedCornerShape(28.dp))
            .background(Brush.linearGradient(listOf(money.cardGradientStart, money.cardGradientEnd))),
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("الرصيد الحالي", color = money.onCard.copy(alpha = 0.8f), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (s.currencies.size > 1) {
                    s.currencies.forEach { cur ->
                        val sel = cur == s.currency
                        Text(
                            cur,
                            color = if (sel) Color.Black else money.onCard,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(start = 4.dp).clip(CircleShape)
                                .background(if (sel) Color.White else Color.White.copy(alpha = 0.12f))
                                .clickable { onCurrency(cur) }.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                s.summary.balance?.let { Money.withCurrency(it, s.currency) } ?: "اضغط لتحديد رصيدك",
                color = money.onCard,
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.ExtraBold, textDirection = TextDirection.Ltr),
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onBalanceClick),
            )
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth().clip(CircleShape).background(Color.White.copy(alpha = 0.1f)).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                listOf(Period.TODAY, Period.WEEK, Period.MONTH, Period.ALL).forEach { p ->
                    val sel = p == s.period
                    Text(
                        p.label,
                        color = if (sel) Color.Black else money.onCard,
                        style = MaterialTheme.typography.labelMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f).clip(CircleShape)
                            .background(if (sel) Color.White else Color.Transparent)
                            .clickable { onPeriod(p) }.padding(vertical = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatBox(Modifier.weight(1f), Icons.Rounded.SouthWest, "المستلم", "+" + Money.withCurrency(s.summary.received, s.currency), "${s.summary.receivedCount} عملية", money.received)
                StatBox(Modifier.weight(1f), Icons.Rounded.NorthEast, "المرسل", "-" + Money.withCurrency(s.summary.sent, s.currency), "${s.summary.sentCount} عملية", money.sent)
            }
        }
    }
}

@Composable
private fun StatBox(modifier: Modifier, icon: ImageVector, label: String, value: String, sub: String, color: Color) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(Color.Black.copy(alpha = 0.25f)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(color.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(label, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(6.dp))
        Text(value, color = color, style = MaterialTheme.typography.titleMedium.copy(textDirection = TextDirection.Ltr, fontWeight = FontWeight.ExtraBold), maxLines = 1)
        Text(sub, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun BalanceDialog(currency: String, current: Double?, onSave: (Double) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current?.let { Money.format(it).replace(",", "") } ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("رصيدك الآن (${Money.displayName(currency)})") },
        text = {
            Column {
                Text("أدخل الرصيد الظاهر الآن في تطبيق شام كاش. المعاملات الجديدة بعد هذه اللحظة ستُحسب عليه تلقائياً.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    suffix = { Text(Money.symbol(currency)) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                AmountParser.parseNumber(text)?.let { onSave(it); onDismiss() }
            }) { Text("حفظ") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}
