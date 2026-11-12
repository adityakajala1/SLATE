package app.slate.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/** SLATE brand palette (from the design spec). */
@Immutable
data class SlateColors(
    val background: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val text: Color,
    val textSecondary: Color,
    val accent: Color,
    val border: Color,
    val isDark: Boolean,
    // Quiet per-kind tints for file badges (desaturated so the UI stays monochrome-first).
    val pdf: Color, val word: Color, val sheet: Color, val slides: Color,
)

val LightSlate = SlateColors(
    background = Color(0xFFF7F7F5), surface = Color(0xFFFFFFFF), surfaceAlt = Color(0xFFF0F0EE),
    text = Color(0xFF171717), textSecondary = Color(0xFF777777), accent = Color(0xFF454545),
    border = Color(0xFFE8E8E8), isDark = false,
    pdf = Color(0xFFB4473B), word = Color(0xFF3B5E9A), sheet = Color(0xFF2F7A52), slides = Color(0xFFB5652A),
)

val DarkSlate = SlateColors(
    background = Color(0xFF000000), surface = Color(0xFF121212), surfaceAlt = Color(0xFF1E1E1E),
    text = Color(0xFFF2F2F2), textSecondary = Color(0xFFA5A5A5), accent = Color(0xFFE0E0E0),
    border = Color(0xFF262626), isDark = true,
    pdf = Color(0xFFE07A6E), word = Color(0xFF86A6DD), sheet = Color(0xFF7CC39C), slides = Color(0xFFE5A26F),
)

val LocalSlate = compositionLocalOf { LightSlate }

object Slate {
    val colors: SlateColors @Composable get() = LocalSlate.current
}

private fun scheme(c: SlateColors): ColorScheme {
    val base = if (c.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.accent,
        onPrimary = c.background,
        primaryContainer = c.surfaceAlt,
        onPrimaryContainer = c.text,
        secondary = c.accent,
        onSecondary = c.background,
        secondaryContainer = c.surfaceAlt,
        onSecondaryContainer = c.text,
        tertiary = c.accent,
        background = c.background,
        onBackground = c.text,
        surface = c.surface,
        onSurface = c.text,
        surfaceVariant = c.surfaceAlt,
        onSurfaceVariant = c.textSecondary,
        surfaceContainerLowest = c.surface,
        surfaceContainerLow = c.surface,
        surfaceContainer = c.surface,
        surfaceContainerHigh = c.surface,
        surfaceContainerHighest = c.surfaceAlt,
        surfaceTint = Color.Transparent,
        outline = c.border,
        outlineVariant = c.border,
        inverseSurface = c.text,
        inverseOnSurface = c.background,
        error = if (c.isDark) Color(0xFFFF8A80) else Color(0xFFB3261E),
    )
}

private val font = FontFamily.Default
private val SlateType = Typography(
    displaySmall = TextStyle(fontFamily = font, fontWeight = FontWeight.SemiBold, fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontFamily = font, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.6).sp),
    headlineSmall = TextStyle(fontFamily = font, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontFamily = font, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = font, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
    titleSmall = TextStyle(fontFamily = font, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
    bodyLarge = TextStyle(fontFamily = font, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.1.sp),
    bodyMedium = TextStyle(fontFamily = font, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodySmall = TextStyle(fontFamily = font, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.2.sp),
    labelLarge = TextStyle(fontFamily = font, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = font, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = font, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.4.sp),
)

private val SlateShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

@Composable
fun SlateTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.SYSTEM -> isSystemInDarkTheme(); ThemeMode.LIGHT -> false; ThemeMode.DARK -> true }
    val colors = if (dark) DarkSlate else LightSlate
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val ctl = WindowCompat.getInsetsController(window, view)
            ctl.isAppearanceLightStatusBars = !dark
            ctl.isAppearanceLightNavigationBars = !dark
        }
    }
    CompositionLocalProvider(LocalSlate provides colors) {
        MaterialTheme(colorScheme = scheme(colors), typography = SlateType, shapes = SlateShapes, content = content)
    }
}
