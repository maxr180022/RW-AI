package com.rwai.agent.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Единственное место, где задаются фирменные цвета Руся AI.
 * Чтобы подогнать стиль под другой бренд, достаточно поменять значения здесь.
 */
object RwPalette {
    // Градиент фирменной шапки и логотипа
    val GradientStart = Color(0xFF2B6BFF)
    val GradientEnd = Color(0xFFFF2D87)
    val Blue = Color(0xFF2B6BFF)
    val Violet = Color(0xFF8A4DFF)
    val Pink = Color(0xFFFF2D87)

    // Тёмная тема
    val DarkBackground = Color(0xFF060914)
    val DarkSurface = Color(0xFF0E1426)
    val DarkSurfaceHigh = Color(0xFF151D36)
    val DarkPrimary = Color(0xFF6C9BFF)
    val DarkOnPrimary = Color(0xFF05102A)
    val DarkAccent = Color(0xFFFFC857)
    val DarkText = Color(0xFFE8EEFB)
    val DarkTextMuted = Color(0xFF9AA8C4)

    // Светлая тема
    val LightBackground = Color(0xFFF3F6FC)
    val LightSurface = Color(0xFFFFFFFF)
    val LightSurfaceHigh = Color(0xFFE8EEFA)
    val LightPrimary = Color(0xFF1560E0)
    val LightOnPrimary = Color(0xFFFFFFFF)
    val LightAccent = Color(0xFFB27A00)
    val LightText = Color(0xFF0E1A33)
    val LightTextMuted = Color(0xFF55627F)

    val Success = Color(0xFF2FBF8F)
    val Danger = Color(0xFFFF6B6B)
}

private val DarkScheme = darkColorScheme(
    primary = RwPalette.DarkPrimary,
    onPrimary = RwPalette.DarkOnPrimary,
    primaryContainer = Color(0xFF1B2D63),
    onPrimaryContainer = RwPalette.DarkText,
    secondary = RwPalette.DarkAccent,
    onSecondary = Color(0xFF2A1D00),
    secondaryContainer = Color(0xFF1F3156),
    onSecondaryContainer = RwPalette.DarkText,
    tertiary = RwPalette.Success,
    tertiaryContainer = Color(0xFF123B33),
    onTertiaryContainer = RwPalette.DarkText,
    background = RwPalette.DarkBackground,
    onBackground = RwPalette.DarkText,
    surface = RwPalette.DarkSurface,
    onSurface = RwPalette.DarkText,
    surfaceVariant = RwPalette.DarkSurfaceHigh,
    onSurfaceVariant = RwPalette.DarkTextMuted,
    surfaceContainer = RwPalette.DarkSurface,
    surfaceContainerHigh = RwPalette.DarkSurfaceHigh,
    surfaceContainerHighest = RwPalette.DarkSurfaceHigh,
    outline = Color(0xFF39466E),
    outlineVariant = Color(0xFF222C4B),
    error = RwPalette.Danger,
    errorContainer = Color(0xFF4A1F27),
    onErrorContainer = Color(0xFFFFDAD9),
)

private val LightScheme = lightColorScheme(
    primary = RwPalette.LightPrimary,
    onPrimary = RwPalette.LightOnPrimary,
    primaryContainer = Color(0xFFD8E6FF),
    onPrimaryContainer = RwPalette.LightText,
    secondary = RwPalette.LightAccent,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3EBFA),
    onSecondaryContainer = RwPalette.LightText,
    tertiary = Color(0xFF138A66),
    tertiaryContainer = Color(0xFFD5F3E8),
    onTertiaryContainer = RwPalette.LightText,
    background = RwPalette.LightBackground,
    onBackground = RwPalette.LightText,
    surface = RwPalette.LightSurface,
    onSurface = RwPalette.LightText,
    surfaceVariant = RwPalette.LightSurfaceHigh,
    onSurfaceVariant = RwPalette.LightTextMuted,
    surfaceContainer = RwPalette.LightSurface,
    surfaceContainerHigh = RwPalette.LightSurfaceHigh,
    surfaceContainerHighest = RwPalette.LightSurfaceHigh,
    outline = Color(0xFFB3BED6),
    outlineVariant = Color(0xFFD5DCEC),
    error = Color(0xFFD93B3B),
    errorContainer = Color(0xFFFFE0DF),
    onErrorContainer = Color(0xFF5A0F12),
)

private val RwShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(34.dp),
)

private val RwTypography = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
    labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.3.sp),
)

fun brandBrush(): Brush = Brush.linearGradient(listOf(RwPalette.Blue, RwPalette.Violet, RwPalette.Pink))

/** Градиент пузыря пользователя и активных элементов: сине‑фиолетовый. */
fun accentBrush(): Brush = Brush.linearGradient(listOf(RwPalette.Blue, RwPalette.Violet))

/** Тонкая светящаяся рамка «стекла». */
fun glowBorder(): Brush = Brush.linearGradient(listOf(RwPalette.Blue.copy(alpha = 0.55f), RwPalette.Violet.copy(alpha = 0.25f), RwPalette.Pink.copy(alpha = 0.45f)))

@Composable
fun RwTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        shapes = RwShapes,
        typography = RwTypography,
        content = content,
    )
}
