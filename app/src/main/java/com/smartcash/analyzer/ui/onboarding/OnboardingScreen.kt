package com.smartcash.analyzer.ui.onboarding

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.SouthWest
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.smartcash.analyzer.domain.AmountParser
import com.smartcash.analyzer.notifications.ShamCashNotificationListener
import com.smartcash.analyzer.ui.LocalContainer
import com.smartcash.analyzer.ui.theme.LocalMoneyColors
import kotlinx.coroutines.launch

/** First launch: welcome → current balance → my Sham Cash name → permissions. */
@Composable
fun OnboardingScreen() {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var step by remember { mutableIntStateOf(0) }
    var usd by remember { mutableStateOf("") }
    var syp by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(24.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(4) { i ->
                Box(Modifier.weight(1f).height(5.dp).clip(CircleShape).background(if (i <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant))
            }
        }
        Spacer(Modifier.height(24.dp))
        AnimatedContent(targetState = step, modifier = Modifier.weight(1f), label = "onboarding") { st ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (st) {
                    0 -> Welcome()
                    1 -> {
                        Text("كم رصيدك الآن؟", style = MaterialTheme.typography.headlineMedium)
                        Text("اكتب الرصيد الظاهر الآن في تطبيق شام كاش. بعدها كل استلام يزيده وكل إرسال ينقصه تلقائياً.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(usd, { usd = it }, label = { Text("الرصيد بالدولار ($)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        OutlinedTextField(syp, { syp = it }, label = { Text("الرصيد بالليرة السورية (اختياري)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                    2 -> {
                        Text("ما اسمك في شام كاش؟", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "اكتبه كما يظهر في الإيصالات تماماً (مثلاً: abu_ahmad أو أحمد.م). هكذا يعرف التطبيق إن كان الإيصال إرسالاً منك أو استلاماً لك. يمكنك تخطي هذه الخطوة وسيسألك التطبيق لاحقاً.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(name, { name = it }, label = { Text("اسمك في شام كاش (اختياري)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(account, { account = it }, label = { Text("آخر 4 أرقام من رقم حسابك (اختياري)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    }
                    else -> Permissions()
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (step > 0) TextButton(onClick = { step-- }) { Text("رجوع") }
            Spacer(Modifier.weight(1f))
            Button(onClick = {
                when (step) {
                    1 -> {
                        val u = if (usd.isBlank()) 0.0 else AmountParser.parseNumber(usd)
                        if (u == null) { error = "أدخل رقماً صحيحاً"; return@Button }
                        error = null; step++
                    }
                    3 -> scope.launch {
                        val balances = LinkedHashMap<String, Double>()
                        balances["USD"] = AmountParser.parseNumber(usd) ?: 0.0
                        AmountParser.parseNumber(syp)?.let { balances["SYP"] = it }
                        c.settings.completeOnboarding(balances, name, account)
                    }
                    else -> step++
                }
            }, modifier = Modifier.height(52.dp).width(160.dp)) { Text(if (step == 3) "ابدأ الآن" else "التالي") }
        }
    }
}

@Composable
private fun Welcome() {
    val money = LocalMoneyColors.current
    Box(
        Modifier.size(96.dp).clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(listOf(money.cardGradientStart, money.cardGradientEnd))),
        contentAlignment = Alignment.Center,
    ) {
        Row {
            Icon(Icons.Rounded.NorthEast, null, tint = money.received, modifier = Modifier.size(36.dp))
            Icon(Icons.Rounded.SouthWest, null, tint = money.onCard, modifier = Modifier.size(36.dp))
        }
    }
    Text("محلل شام كاش", style = MaterialTheme.typography.headlineLarge)
    Text("كل معاملاتك في شام كاش، محللة بالذكاء الاصطناعي.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    Feature(Icons.Rounded.DocumentScanner, "يقرأ إيصالات PDF الممسوحة", "تحويل النص الممسوح إلى بيانات: الأسماء والمبلغ والتاريخ بدقة ومن دون تغيير")
    Feature(Icons.Rounded.NotificationsActive, "يقرأ إشعارات شام كاش", "يضيف ما تستلمه وترسله تلقائياً ولا يكرر أي معاملة")
    Feature(Icons.AutoMirrored.Rounded.Send, "بوت تيليجرام", "أرسل الإيصال للبوت أو اسأله عن معاملاتك من أي مكان")
    Feature(Icons.Rounded.AutoAwesome, "مساعد ذكي Grok", "اسأل: كم أرسلت هذا الأسبوع؟ ومن أكثر شخص استلمت منه؟")
}

@Composable
private fun Feature(icon: ImageVector, title: String, sub: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Permissions() {
    val context = LocalContext.current
    var listener by remember { mutableStateOf(ShamCashNotificationListener.isEnabled(context)) }
    var posted by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { listener = ShamCashNotificationListener.isEnabled(context) }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { posted = it }

    Text("الأذونات", style = MaterialTheme.typography.headlineMedium)
    Text(
        "يحتاج التطبيق إذن «الوصول إلى الإشعارات» ليقرأ إشعارات شام كاش عند الاستلام أو الإرسال ويضيفها تلقائياً. لا يتم حفظ إشعارات أي تطبيق آخر.",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (listener) {
        Text("🟢 تم تفعيل قراءة الإشعارات", color = LocalMoneyColors.current.received)
    } else {
        Button(onClick = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }, modifier = Modifier.fillMaxWidth()) {
            Text("تفعيل قراءة الإشعارات")
        }
        Text("في الشاشة التالية اختر «محلل شام كاش» وفعّل الإذن ثم ارجع.", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Start)
    }
    if (Build.VERSION.SDK_INT >= 33 && !posted) {
        OutlinedButton(onClick = { notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.fillMaxWidth()) {
            Text("السماح للتطبيق بإظهار التنبيهات")
        }
    }
    Text("يمكنك إضافة توكن تيليجرام ومفتاح Grok لاحقاً من الإعدادات.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
