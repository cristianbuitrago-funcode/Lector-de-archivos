package com.funcodestudiocb.analyzer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Tokens de la identidad visual de Funcode Studio CB. */
object FuncodeColors {
    val Black = Color(0xFF05070D)
    val Navy = Color(0xFF0A1A33)
    val Navy2 = Color(0xFF10284D)
    val NavyDeep = Color(0xFF070F1F)
    val Outline = Color(0xFF1F3A66)
    val Electric = Color(0xFF1E90FF)
    val ElectricSoft = Color(0xFF7CC0FF)
    val Gold = Color(0xFFD4AF37)
    val TextPrimary = Color(0xFFE8EEF8)
    val TextSecondary = Color(0xFF9FB0C8)
    val Success = Color(0xFF2ECC71)

    // Severidades (se usarán desde la Fase 6).
    val Critical = Color(0xFFFF3B5C)
    val High = Color(0xFFFF8A3D)
    val Medium = Color(0xFFF5C542)
    val Low = Color(0xFF4FC3F7)
    val Info = Color(0xFF8A9BB5)
}

private val FuncodeScheme = darkColorScheme(
    primary = FuncodeColors.Electric,
    onPrimary = Color.White,
    primaryContainer = FuncodeColors.Navy2,
    onPrimaryContainer = FuncodeColors.TextPrimary,
    secondary = FuncodeColors.Gold,
    onSecondary = FuncodeColors.Black,
    secondaryContainer = Color(0xFF2A2410),
    onSecondaryContainer = FuncodeColors.Gold,
    tertiary = FuncodeColors.ElectricSoft,
    background = FuncodeColors.Black,
    onBackground = FuncodeColors.TextPrimary,
    surface = FuncodeColors.NavyDeep,
    onSurface = FuncodeColors.TextPrimary,
    surfaceVariant = FuncodeColors.Navy2,
    onSurfaceVariant = FuncodeColors.TextSecondary,
    surfaceContainerLowest = FuncodeColors.Black,
    surfaceContainerLow = FuncodeColors.NavyDeep,
    surfaceContainer = FuncodeColors.Navy,
    surfaceContainerHigh = Color(0xFF0E2242),
    surfaceContainerHighest = FuncodeColors.Navy2,
    outline = FuncodeColors.Outline,
    outlineVariant = Color(0xFF152B4D),
    error = FuncodeColors.Critical,
    onError = Color.White,
)

private val FuncodeTypography = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp),
    )
}

/**
 * Tema de la app. Siempre oscuro (identidad de marca). Usa la fuente del sistema: no se descargan
 * fuentes de Internet.
 */
@Composable
fun FuncodeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = FuncodeScheme, typography = FuncodeTypography, content = content)
}
