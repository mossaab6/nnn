package com.smartcash.analyzer.ui.settings

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.smartcash.analyzer.BuildConfig
import com.smartcash.analyzer.data.settings.AppSettings
import com.smartcash.analyzer.data.settings.RelayMode
import com.smartcash.analyzer.data.settings.ThemeAccent
import com.smartcash.analyzer.data.settings.ThemeMode
import com.smartcash.analyzer.domain.Money
import com.smartcash.analyzer.notifications.ShamCashNotificationListener
import com.smartcash.analyzer.telegram.BotService
import com.smartcash.analyzer.telegram.RelayClient
import com.smartcash.analyzer.ui.LocalContainer
import com.smartcash.analyzer.ui.Routes
import com.smartcash.analyzer.ui.components.ConfirmDialog
import com.smartcash.analyzer.ui.home.BalanceDialog
import com.smartcash.analyzer.ui.theme.accentPreview
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(nav: NavHostController) {
    val c = LocalContainer.current
    val s by c.settings.flow.collectAsStateWithLifecycle(initialValue = null)
    val snackbar = remember { SnackbarHostState() }
    Scaffold(topBar = { TopAppBar(title = { Text("الإعدادات") }) }, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        val settings = s ?: return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AppearanceSection(settings)
            AccountSection(settings)
            GrokSection(settings, snackbar)
            TelegramSection(settings, snackbar)
            RelaySection(settings, snackbar)
            NotificationsSection(settings, nav)
            DataSection(snackbar)
            Text("محلل شام كاش · الإصدار ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 32.dp))
        }
    }
}

@Composable
private fun Section(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            content()
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChange(!checked) }) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SecretField(label: String, value: String, onChange: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { IconButton(onClick = { visible = !visible }) { Icon(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, null) } },
        textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr),
    )
}

@Composable
private fun BusyButton(text: String, busy: Boolean, modifier: Modifier = Modifier, outlined: Boolean = false, onClick: () -> Unit) {
    val content: @Composable () -> Unit = { if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(text) }
    if (outlined) OutlinedButton(onClick = onClick, enabled = !busy, modifier = modifier) { content() }
    else Button(onClick = onClick, enabled = !busy, modifier = modifier) { content() }
}

// ---------------------------------------------------------------- appearance

@Composable
private fun AppearanceSection(s: AppSettings) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    Section("المظهر", "اختر لون التطبيق ووضع الإضاءة") {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ThemeAccent.entries.forEach { a ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(accentPreview(a))
                            .border(3.dp, if (s.accent == a) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                            .clickable { scope.launch { c.settings.setTheme(accent = a) } },
                        contentAlignment = Alignment.Center,
                    ) { if (s.accent == a) Icon(Icons.Rounded.Check, null, tint = Color.Black) }
                    Text(a.label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            ThemeMode.entries.forEachIndexed { i, m ->
                SegmentedButton(selected = s.themeMode == m, onClick = { scope.launch { c.settings.setTheme(mode = m) } }, shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size)) { Text(m.label) }
            }
        }
        SwitchRow("خلفية سوداء بالكامل", "أفضل لشاشات AMOLED في الوضع الداكن", s.pureBlack) { v -> scope.launch { c.settings.setTheme(pureBlack = v) } }
    }
}

// ---------------------------------------------------------------- account

@Composable
private fun AccountSection(s: AppSettings) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var newName by remember { mutableStateOf("") }
    var accounts by remember(s.ownerAccounts) { mutableStateOf(s.ownerAccounts.joinToString("، ")) }
    var editCurrency by remember { mutableStateOf<String?>(null) }
    Section("حسابي في شام كاش", "اسمك كما يظهر في الإيصالات يساعد التطبيق على معرفة إن كانت العملية إرسالاً أم استلاماً.") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            s.ownerNames.forEach { n ->
                InputChip(selected = false, onClick = {}, label = { Text(n) }, trailingIcon = {
                    Icon(Icons.Rounded.Close, "حذف", Modifier.size(16.dp).clickable { scope.launch { c.settings.setOwnerNames(s.ownerNames - n) } })
                })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(newName, { newName = it }, label = { Text("أضف اسمك (مثل abu_ahmad)") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = { scope.launch { c.settings.addOwnerName(newName); newName = "" } }, enabled = newName.isNotBlank()) { Text("إضافة") }
        }
        OutlinedTextField(
            accounts, { accounts = it }, label = { Text("آخر 4 أرقام من حسابك (اختياري)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            trailingIcon = { TextButton(onClick = { scope.launch { c.settings.setOwnerAccounts(accounts.split(',', '،', ' ').filter { it.isNotBlank() }) } }) { Text("حفظ") } },
        )
        HorizontalDivider()
        Text("الرصيد", style = MaterialTheme.typography.titleSmall)
        val currencies = (listOf(s.primaryCurrency) + s.balances.keys).distinct()
        currencies.forEach { cur ->
            val b = s.balances[cur]
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = s.primaryCurrency == cur, onClick = { scope.launch { c.settings.setPrimaryCurrency(cur) } })
                Column(Modifier.weight(1f)) {
                    Text(Money.displayName(cur))
                    Text(if (b == null) "لم يحدد رصيد" else "رصيد البداية: ${Money.withCurrency(b.amount, cur)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { editCurrency = cur }) { Text("تعديل الرصيد الآن") }
            }
        }
        val missing = Money.supportedCurrencies - currencies.toSet()
        if (missing.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                missing.forEach { cur -> SuggestionChip(onClick = { editCurrency = cur }, label = { Text("+ ${Money.displayName(cur)}") }) }
            }
        }
        Text("العملة المحددة بالدائرة هي العملة الرئيسية في الشاشة الرئيسية.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    editCurrency?.let { cur ->
        BalanceDialog(cur, null, onSave = { amount -> scope.launch { c.settings.setBalance(cur, amount) } }, onDismiss = { editCurrency = null })
    }
}

// ---------------------------------------------------------------- Grok

@Composable
private fun GrokSection(s: AppSettings, snackbar: SnackbarHostState) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var key by remember(s.grokApiKey) { mutableStateOf(s.grokApiKey) }
    var model by remember(s.grokModel) { mutableStateOf(s.grokModel) }
    var busy by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    Section("الذكاء الاصطناعي Grok (xAI)", "يُستخدم لقراءة الإيصالات بدقة، الإشعارات، والمحادثة. احصل على المفتاح من console.x.ai") {
        SecretField("مفتاح Grok API", key) { key = it }
        OutlinedTextField(model, { model = it }, label = { Text("النموذج") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr))
        if (models.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                models.forEach { m -> SuggestionChip(onClick = { model = m }, label = { Text(m) }) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BusyButton("حفظ واختبار", busy, Modifier.weight(1f)) {
                busy = true
                scope.launch {
                    c.settings.setGrok(key, model)
                    val r = runCatching { c.ai.test(key.trim(), model.trim()) }
                    r.getOrNull()?.let { used -> if (used != model.trim()) { c.settings.setGrokModel(used); model = used } }
                    busy = false
                    snackbar.showSnackbar(r.fold({ "✅ Grok يعمل (النموذج: $it)" }, { "❌ ${it.message}" }))
                }
            }
            BusyButton("جلب النماذج", busy, Modifier.weight(1f), outlined = true) {
                busy = true
                scope.launch {
                    val r = runCatching { c.ai.models(key.trim()) }
                    busy = false
                    r.onSuccess { list -> models = list.filter { it.id.startsWith("grok") }.map { it.id } }
                    if (r.isFailure) snackbar.showSnackbar("❌ ${r.exceptionOrNull()?.message}")
                }
            }
        }
        SwitchRow(
            "التحقق المزدوج للأسماء",
            "يقرأ الإيصال بالـ OCR على الجهاز وبـ Grok ويقارن الأسماء والمبالغ؛ إذا اختلفت تُعلَّم المعاملة للمراجعة",
            s.aiCrossCheck,
        ) { v -> scope.launch { c.settings.setAiCrossCheck(v) } }
    }
}

// ---------------------------------------------------------------- Telegram

@Composable
private fun TelegramSection(s: AppSettings, snackbar: SnackbarHostState) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember(s.telegramToken) { mutableStateOf(s.telegramToken) }
    var chatId by remember(s.telegramChatId) { mutableStateOf(s.telegramChatId) }
    var busy by remember { mutableStateOf(false) }
    var chats by remember { mutableStateOf<List<Pair<Long, String>>?>(null) }
    val status by c.botEngine.status.collectAsStateWithLifecycle()

    Section("بوت تيليجرام", "أنشئ بوتاً من @BotFather وضع التوكن هنا، ثم أرسل /start للبوت واضغط «اكتشاف» لمعرفة معرف المحادثة.") {
        SecretField("توكن البوت (Bot Token)", token) { token = it }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(chatId, { chatId = it }, label = { Text("معرف المحادثة (Chat ID)") }, singleLine = true, modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr))
            TextButton(enabled = token.isNotBlank() && !busy, onClick = {
                busy = true
                scope.launch {
                    val r = runCatching { c.telegram.recentChats(token.trim()) }
                    busy = false
                    r.onSuccess { list -> if (list.isEmpty()) { snackbar.showSnackbar("أرسل /start للبوت من تيليجرام ثم أعد المحاولة") } else { chats = list } }
                        .onFailure { snackbar.showSnackbar("❌ ${it.message}") }
                }
            }) { Text("اكتشاف") }
        }
        BusyButton("حفظ واختبار", busy, Modifier.fillMaxWidth()) {
            busy = true
            scope.launch {
                c.settings.setTelegram(token, chatId)
                val r = runCatching {
                    val name = c.telegram.getMe(token.trim())
                    if (chatId.isNotBlank()) {
                        c.telegram.sendMessage(token.trim(), chatId.trim(), "✅ تم ربط البوت بتطبيق محلل شام كاش بنجاح!\nأرسل لي إيصال PDF أو اسألني عن معاملاتك.")
                        c.telegram.setCommands(token.trim())
                    }
                    name
                }
                busy = false
                snackbar.showSnackbar(r.fold({ "✅ البوت @$it يعمل" + if (chatId.isBlank()) " — أضف معرف المحادثة" else "" }, { "❌ ${it.message}" }))
            }
        }
        SwitchRow(
            "تشغيل البوت باستمرار",
            "يرد فوراً على الرسائل والإيصالات (يظهر إشعار ثابت صغير). بدونه يتحقق التطبيق كل 15 دقيقة.",
            s.botServiceEnabled,
        ) { v ->
            scope.launch {
                c.settings.setBotServiceEnabled(v)
                if (v) runCatching { BotService.start(context) } else BotService.stop(context)
            }
        }
        SwitchRow("أبلغني في تيليجرام عند كل معاملة تلقائية", "عندما تُضاف معاملة من إشعار شام كاش", s.notifyTelegramOnAuto) { v ->
            scope.launch { c.settings.setNotifyTelegramOnAuto(v) }
        }
        Text(
            buildString {
                append(if (status.serviceRunning) "🟢 البوت يعمل في الخلفية" else "⚪ البوت غير مشغّل باستمرار")
                status.lastSyncAt?.let { append(" · آخر مزامنة ${com.smartcash.analyzer.domain.TxFormat.relative(it)}") }
                status.lastError?.let { append("\n⚠️ $it") }
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    chats?.let { list ->
        AlertDialog(
            onDismissRequest = { chats = null },
            title = { Text("اختر محادثتك") },
            text = {
                Column {
                    list.forEach { (id, name) ->
                        TextButton(onClick = { chatId = id.toString(); chats = null }) { Text("$name  ($id)") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { chats = null }) { Text("إغلاق") } },
        )
    }
}

// ---------------------------------------------------------------- cloud relay

@Composable
private fun RelaySection(s: AppSettings, snackbar: SnackbarHostState) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember(s.relayMode) { mutableStateOf(s.relayMode) }
    var url by remember(s.relayUrl) { mutableStateOf(s.relayUrl) }
    var secret by remember(s.relaySecret) { mutableStateOf(s.relaySecret) }
    var busy by remember { mutableStateOf(false) }
    Section(
        "الخدمة السحابية لنقل الملفات",
        "أفضل خيار مجاني وغير محدود هو تيليجرام نفسه: الملفات تبقى على خوادم تيليجرام ويحمّلها التطبيق مباشرة (حتى 20MB للملف). " +
            "الخيار الثاني Cloudflare Worker مجاني ويحفظ الرسائل حتى لو بقي الهاتف مطفأً أكثر من 24 ساعة.",
    ) {
        RelayMode.entries.forEach { m ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { mode = m }) {
                RadioButton(selected = mode == m, onClick = { mode = m })
                Text(m.label)
            }
        }
        if (mode == RelayMode.RELAY) {
            OutlinedTextField(url, { url = it }, label = { Text("رابط الـ Worker (https://….workers.dev)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr))
            SecretField("مفتاح RELAY_SECRET", secret) { secret = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { secret = RelayClient.generateSecret() }, modifier = Modifier.weight(1f)) { Text("توليد مفتاح") }
                OutlinedButton(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("secret", secret))
                    scope.launch { snackbar.showSnackbar("تم نسخ المفتاح — ضعه في Cloudflare باسم RELAY_SECRET") }
                }, modifier = Modifier.weight(1f)) { Text("نسخ المفتاح") }
            }
        }
        BusyButton(if (mode == RelayMode.RELAY) "حفظ وربط Webhook" else "حفظ (وضع مباشر)", busy, Modifier.fillMaxWidth()) {
            busy = true
            scope.launch {
                val r = runCatching {
                    val token = c.settings.current().telegramToken
                    if (mode == RelayMode.RELAY) {
                        require(url.startsWith("https://")) { "الرابط يجب أن يبدأ بـ https://" }
                        require(RelayClient.isValidSecret(secret)) { "المفتاح يجب أن يكون 16 حرفاً على الأقل (حروف إنجليزية وأرقام فقط)" }
                        val pending = c.relay.health(url.trim(), secret.trim())
                        c.settings.setRelay(mode, url, secret)
                        if (token.isNotBlank()) c.telegram.setWebhook(token, url.trim().trimEnd('/') + "/telegram", secret.trim())
                        "✅ تم الربط مع Cloudflare (رسائل بالانتظار: $pending)"
                    } else {
                        c.settings.setRelay(mode, url, secret)
                        if (token.isNotBlank()) c.telegram.deleteWebhook(token)
                        "✅ الوضع المباشر مفعّل"
                    }
                }
                busy = false
                snackbar.showSnackbar(r.fold({ it }, { "❌ ${it.message}" }))
            }
        }
    }
}

// ---------------------------------------------------------------- notifications

@SuppressLint("BatteryLife")
@Composable
private fun NotificationsSection(s: AppSettings, nav: NavHostController) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(ShamCashNotificationListener.isEnabled(context)) }
    var ignoringBattery by remember { mutableStateOf(isIgnoringBattery(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        enabled = ShamCashNotificationListener.isEnabled(context)
        ignoringBattery = isIgnoringBattery(context)
    }
    var pkg by remember { mutableStateOf("") }
    Section("قراءة إشعارات شام كاش", "عند وصول إشعار استلام أو إرسال من شام كاش يضيف التطبيق المعاملة تلقائياً (بدون تكرار مع ملفات PDF).") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (enabled) "🟢 إذن قراءة الإشعارات مفعّل" else "🔴 إذن قراءة الإشعارات غير مفعّل", modifier = Modifier.weight(1f))
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text(if (enabled) "الإعدادات" else "تفعيل") }
        }
        if (!ignoringBattery) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("يُنصح باستثناء التطبيق من توفير البطارية ليعمل في الخلفية", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
                    }
                }) { Text("استثناء") }
            }
        }
        SwitchRow("إضافة المعاملات تلقائياً من الإشعارات", null, s.notifAutoAdd) { v -> scope.launch { c.settings.setNotifAutoAdd(v) } }
        Text("يتم التعرف تلقائياً على أي تطبيق اسمه يحتوي shamcash. إذا كان اسم حزمة شام كاش مختلفاً أضفه هنا:", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            s.notifPackages.forEach { p ->
                InputChip(selected = true, onClick = {}, label = { Text(p) }, trailingIcon = {
                    Icon(Icons.Rounded.Close, "حذف", Modifier.size(16.dp).clickable { scope.launch { c.settings.setNotifPackages(s.notifPackages - p) } })
                })
            }
        }
        val suggestions = s.recentNotifSources.filter { it !in s.notifPackages }
        if (suggestions.isNotEmpty()) {
            Text("تطبيقات أرسلت إشعارات مالية مؤخراً (اضغط للإضافة):", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                suggestions.forEach { p -> SuggestionChip(onClick = { scope.launch { c.settings.setNotifPackages(s.notifPackages + p) } }, label = { Text(p) }) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(pkg, { pkg = it }, label = { Text("اسم الحزمة") }, singleLine = true, modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Ltr))
            TextButton(enabled = pkg.isNotBlank(), onClick = { scope.launch { c.settings.setNotifPackages(s.notifPackages + pkg.trim()); pkg = "" } }) { Text("إضافة") }
        }
        OutlinedButton(onClick = { nav.navigate(Routes.NOTIF_LOG) }, modifier = Modifier.fillMaxWidth()) { Text("سجل الإشعارات المقروءة") }
    }
}

private fun isIgnoringBattery(context: Context): Boolean =
    (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)

// ---------------------------------------------------------------- data

@Composable
private fun DataSection(snackbar: SnackbarHostState) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var confirmWipe by remember { mutableStateOf(false) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch { snackbar.showSnackbar(runCatching { c.backup.export(uri) }.fold({ "✅ تم تصدير $it معاملة" }, { "❌ ${it.message}" })) }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            snackbar.showSnackbar(runCatching { c.backup.import(uri) }.fold({ "✅ تمت إضافة ${it.first} معاملة (تم تخطي ${it.second} مكررة)" }, { "❌ ${it.message}" }))
        }
    }
    Section("البيانات", "النسخة الاحتياطية لا تحتوي المفاتيح والتوكنات.") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { exporter.launch("shamcash-backup.json") }, modifier = Modifier.weight(1f)) { Text("تصدير نسخة") }
            OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "*/*")) }, modifier = Modifier.weight(1f)) { Text("استيراد نسخة") }
        }
        TextButton(onClick = { confirmWipe = true }) { Text("حذف كل المعاملات والأشخاص", color = MaterialTheme.colorScheme.error) }
    }
    if (confirmWipe) {
        ConfirmDialog(
            "حذف كل البيانات؟", "سيتم حذف كل المعاملات والأشخاص نهائياً (تبقى الإعدادات والمفاتيح). لا يمكن التراجع.", "حذف الكل", true,
            onConfirm = { scope.launch { c.repo.deleteAll(); snackbar.showSnackbar("تم الحذف") } },
            onDismiss = { confirmWipe = false },
        )
    }
    Spacer(Modifier.height(4.dp))
}

// ---------------------------------------------------------------- notification log

@Composable
fun NotificationLogScreen(nav: NavHostController) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val logs by remember { c.db.notifications().observeRecent() }.collectAsStateWithLifecycle(initialValue = emptyList())
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("سجل الإشعارات") },
            navigationIcon = { com.smartcash.analyzer.ui.components.BackButton { nav.popBackStack() } },
            actions = { TextButton(onClick = { scope.launch { c.db.notifications().clear() } }) { Text("مسح") } },
        )
    }) { padding ->
        if (logs.isEmpty()) {
            com.smartcash.analyzer.ui.components.EmptyState(
                Icons.Rounded.Check, "لا توجد إشعارات بعد",
                "ستظهر هنا إشعارات شام كاش التي قرأها التطبيق. تأكد من تفعيل إذن قراءة الإشعارات.",
                Modifier.padding(padding),
            )
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            logs.forEach { l ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("${l.title.orEmpty()}  ·  ${com.smartcash.analyzer.domain.TxFormat.relative(l.postedAt)}", style = MaterialTheme.typography.labelMedium)
                        Text(l.text.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                        Text("${statusLabel(l.status)}${l.message?.let { " — $it" }.orEmpty()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        Row {
                            l.transactionId?.let { id -> TextButton(onClick = { nav.navigate(Routes.tx(id)) }) { Text("عرض المعاملة") } }
                            if (l.status == "IGNORED" || l.status == "FAILED") {
                                TextButton(onClick = { scope.launch { c.notificationProcessor.process(l.id) } }) { Text("إعادة المعالجة") }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun statusLabel(s: String) = when (s) {
    "ADDED" -> "✅ أضيفت"
    "MERGED" -> "🔗 دُمجت"
    "DUPLICATE" -> "♻️ مكررة"
    "IGNORED" -> "⏭️ تم تجاهلها"
    "FAILED" -> "❌ فشل"
    else -> "⏳ قيد المعالجة"
}
