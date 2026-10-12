package com.smartcash.analyzer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.smartcash.analyzer.R
import com.smartcash.analyzer.data.settings.ThemeAccent
import com.smartcash.analyzer.data.settings.ThemeMode

val Tajawal = FontFamily(
    Font(R.font.tajawal_regular, FontWeight.Normal),
    Font(R.font.tajawal_medium, FontWeight.Medium),
    Font(R.font.tajawal_bold, FontWeight.Bold),
    Font(R.font.tajawal_extrabold, FontWeight.ExtraBold),
)

/** Colors with a fixed meaning: green = money in, red = money out. */
@Immutable
data class MoneyColors(
    val received: Color,
    val sent: Color,
    val warning: Color,
    val receivedContainer: Color,
    val sentContainer: Color,
    val cardGradientStart: Color,
    val cardGradientEnd: Color,
    val onCard: Color,
)

val LocalMoneyColors = staticCompositionLocalOf {
    MoneyColors(
        Color(0xFF22C55E), Color(0xFFF04438), Color(0xFFF5A524),
        Color(0x2622C55E), Color(0x26F04438), Color(0xFF0B3D25), Color(0xFF041A10), Color.White,
    )
}

private data class Accent(val dark: Color, val light: Color, val deep: Color, val darkest: Color)

private fun accentOf(a: ThemeAccent) = when (a) {
    ThemeAccent.GREEN -> Accent(Color(0xFF10C46A), Color(0xFF0A8F4C), Color(0xFF0B3D25), Color(0xFF03140B))
    ThemeAccent.BLUE -> Accent(Color(0xFF3B82F6), Color(0xFF1D4ED8), Color(0xFF0E2A5C), Color(0xFF050D1F))
    ThemeAccent.PURPLE -> Accent(Color(0xFF8B5CF6), Color(0xFF6D28D9), Color(0xFF2E1660), Color(0xFF0E0620))
    ThemeAccent.GOLD -> Accent(Color(0xFFF5B301), Color(0xFFB45309), Color(0xFF4A3300), Color(0xFF161000))
}

fun accentPreview(a: ThemeAccent): Color = accentOf(a).dark

private fun darkScheme(acc: Accent, pureBlack: Boolean): ColorScheme {
    val bg = if (pureBlack) Color(0xFF000000) else Color(0xFF0B0F0D)
    return darkColorScheme(
        primary = acc.dark,
        onPrimary = Color(0xFF00110A),
        primaryContainer = acc.deep,
        onPrimaryContainer = Color(0xFFE6FFF2),
        secondary = acc.dark.copy(alpha = 0.85f),
        onSecondary = Color.Black,
        secondaryContainer = Color(0xFF1A2420),
        onSecondaryContainer = Color(0xFFDDE7E2),
        background = bg,
        onBackground = Color(0xFFE9EFEC),
        surface = bg,
        onSurface = Color(0xFFE9EFEC),
        surfaceVariant = Color(0xFF17201C),
        onSurfaceVariant = Color(0xFF9DAAA3),
        surfaceContainerLowest = bg,
        surfaceContainerLow = Color(0xFF0C1210),
        surfaceContainer = Color(0xFF111815),
        surfaceContainerHigh = Color(0xFF161E1A),
        surfaceContainerHighest = Color(0xFF1C2520),
        outline = Color(0xFF2E3833),
        outlineVariant = Color(0xFF222B27),
        error = Color(0xFFF04438),
    )
}

private fun lightScheme(acc: Accent): ColorScheme = lightColorScheme(
    primary = acc.light,
    onPrimary = Color.White,
    primaryContainer = acc.light.copy(alpha = 0.14f),
    onPrimaryContainer = acc.deep,
    secondaryContainer = Color(0xFFE7EEEA),
    background = Color(0xFFF5F7F6),
    onBackground = Color(0xFF101614),
    surface = Color(0xFFF5F7F6),
    onSurface = Color(0xFF101614),
    surfaceVariant = Color(0xFFE6ECE9),
    onSurfaceVariant = Color(0xFF55615B),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBFCFB),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFF0F3F1),
    surfaceContainerHighest = Color(0xFFE8EDEA),
    outline = Color(0xFFCBD5D0),
    outlineVariant = Color(0xFFE0E6E3),
    error = Color(0xFFDC2626),
)

private fun typography(): Typography {
    val base = Typography()
    fun TextStyle.t() = copy(fontFamily = Tajawal)
    return Typography(
        displayLarge = base.displayLarge.t(),
        displayMedium = base.displayMedium.t(),
        displaySmall = base.displaySmall.t(),
        headlineLarge = base.headlineLarge.t().copy(fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.t().copy(fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.t().copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.t().copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.t().copy(fontWeight = FontWeight.Bold),
        titleSmall = base.titleSmall.t().copy(fontWeight = FontWeight.Medium),
        bodyLarge = base.bodyLarge.t().copy(fontSize = 16.sp),
        bodyMedium = base.bodyMedium.t(),
        bodySmall = base.bodySmall.t(),
        labelLarge = base.labelLarge.t().copy(fontWeight = FontWeight.Bold),
        labelMedium = base.labelMedium.t(),
        labelSmall = base.labelSmall.t(),
    )
}

@Composable
fun AppTheme(accent: ThemeAccent, mode: ThemeMode, pureBlack: Boolean, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val acc = accentOf(accent)
    val scheme = if (dark) darkScheme(acc, pureBlack) else lightScheme(acc)
    val money = if (dark) {
        MoneyColors(
            received = Color(0xFF2FD47A), sent = Color(0xFFFF5A4E), warning = Color(0xFFF5A524),
            receivedContainer = Color(0x262FD47A), sentContainer = Color(0x26FF5A4E),
            cardGradientStart = acc.deep, cardGradientEnd = acc.darkest, onCard = Color.White,
        )
    } else {
        MoneyColors(
            received = Color(0xFF15803D), sent = Color(0xFFDC2626), warning = Color(0xFFB45309),
            receivedContainer = Color(0x1F15803D), sentContainer = Color(0x1FDC2626),
            cardGradientStart = acc.light, cardGradientEnd = acc.deep, onCard = Color.White,
        )
    }
    MaterialTheme(colorScheme = scheme, typography = typography()) {
        // The UI is Arabic: always lay it out right-to-left, whatever the phone language is.
        CompositionLocalProvider(
            LocalLayoutDirection provides LayoutDirection.Rtl,
            LocalMoneyColors provides money,
            content = content,
        )
    }
}
