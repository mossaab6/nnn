package com.smartcash.analyzer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.SouthWest
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.smartcash.analyzer.data.db.TransactionEntity
import com.smartcash.analyzer.domain.Money
import com.smartcash.analyzer.domain.TxFormat
import com.smartcash.analyzer.domain.TxType
import com.smartcash.analyzer.ui.theme.LocalMoneyColors
import java.time.LocalDate
import kotlin.math.abs

/** Amount such as "-193 $" (red) or "+100 $" (green), always laid out left-to-right. */
@Composable
fun AmountText(
    amount: Double,
    currency: String,
    type: TxType?,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    modifier: Modifier = Modifier,
    color: Color? = null,
) {
    val money = LocalMoneyColors.current
    val text = if (type == null) Money.withCurrency(amount, currency) else Money.signed(amount, currency, type)
    val c = color ?: when (type) {
        TxType.SENT -> money.sent
        TxType.RECEIVED -> money.received
        null -> MaterialTheme.colorScheme.onSurface
    }
    Text(
        text = text,
        style = style.copy(textDirection = TextDirection.Ltr, fontWeight = FontWeight.Bold),
        color = c,
        modifier = modifier,
        maxLines = 1,
    )
}

/** Round icon with a diagonal arrow: ↗ red for sent, ↙ green for received. */
@Composable
fun DirectionIcon(type: TxType, size: Dp = 44.dp) {
    val money = LocalMoneyColors.current
    val sent = type == TxType.SENT
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (sent) money.sentContainer else money.receivedContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (sent) Icons.Rounded.NorthEast else Icons.Rounded.SouthWest,
            contentDescription = if (sent) "إرسال" else "استلام",
            tint = if (sent) money.sent else money.received,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

@Composable
fun TransactionRow(tx: TransactionEntity, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val money = LocalMoneyColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DirectionIcon(tx.type)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tx.displayName,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (tx.needsReview) {
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(8.dp).clip(CircleShape).background(money.warning))
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                "${TxFormat.typeVerb(tx.type)} · ${TxFormat.relative(tx.timestamp, tx.hasTime)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        AmountText(tx.amount, tx.currency, tx.type)
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, subtitle: String? = null, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        action?.invoke()
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmText: String = "تأكيد",
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirmText, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } },
    )
}

private val avatarColors = listOf(
    Color(0xFF10B981), Color(0xFF3B82F6), Color(0xFF8B5CF6), Color(0xFFF59E0B),
    Color(0xFFEC4899), Color(0xFF14B8A6), Color(0xFFEF4444), Color(0xFF6366F1),
)

@Composable
fun PersonAvatar(name: String, size: Dp = 44.dp) {
    val color = avatarColors[abs(name.hashCode()) % avatarColors.size]
    val letter = name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "؟"
    Box(
        Modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, color = color, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "رجوع") }
}

@Composable
fun InfoRow(label: String, value: String?, modifier: Modifier = Modifier, ltrValue: Boolean = false) {
    if (value.isNullOrBlank()) return
    Row(modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(120.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyLarge.copy(textDirection = if (ltrValue) TextDirection.Ltr else TextDirection.Content),
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(color = color.copy(alpha = 0.15f), shape = RoundedCornerShape(50), modifier = modifier) {
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}

/** Seven-day bar chart: green bars = received, red bars = sent. */
@Composable
fun WeekChart(days: List<Triple<LocalDate, Double, Double>>, modifier: Modifier = Modifier) {
    val money = LocalMoneyColors.current
    val max = days.maxOfOrNull { maxOf(it.second, it.third) }?.takeIf { it > 0 } ?: 1.0
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val weekDays = mapOf(1 to "ن", 2 to "ث", 3 to "ر", 4 to "خ", 5 to "ج", 6 to "س", 7 to "ح")
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val n = days.size.coerceAtLeast(1)
            val slot = size.width / n
            val barW = (slot / 4f).coerceAtMost(14.dp.toPx())
            days.forEachIndexed { i, (_, rec, snt) ->
                // RTL: oldest day on the right
                val cx = size.width - slot * i - slot / 2f
                val hr = (rec / max * size.height).toFloat()
                val hs = (snt / max * size.height).toFloat()
                drawRoundRect(money.received, Offset(cx - barW - 2f, size.height - hr), Size(barW, hr.coerceAtLeast(2f)), CornerRadius(6f, 6f))
                drawRoundRect(money.sent, Offset(cx + 2f, size.height - hs), Size(barW, hs.coerceAtLeast(2f)), CornerRadius(6f, 6f))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            days.forEach { (d, _, _) ->
                Text(weekDays[d.dayOfWeek.value].orEmpty(), modifier = Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = labelColor)
            }
        }
    }
}
