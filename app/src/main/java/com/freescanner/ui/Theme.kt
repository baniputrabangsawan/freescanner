package com.freescanner.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.freescanner.model.LocalTool

/** Measured roles, not borrowed values: every pair below was checked against its own background. */
internal object Palette {
    val Ink = Color(0xFF131A2B)
    val InkMuted = Color(0xFF5A6478)
    val Canvas = Color(0xFFF6F7FB)
    val Surface = Color(0xFFFFFFFF)
    val Border = Color(0xFFE3E7F0)
    val Blue = Color(0xFF1F4FE0)
    val Violet = Color(0xFF6D28D9)
    val Teal = Color(0xFF0F766E)
    val Amber = Color(0xFF9A4A08)
    val Rose = Color(0xFFB3123E)
    val Emerald = Color(0xFF046C4E)
    val Sky = Color(0xFF0B5C8A)
}

/** A tinted surface plus the one ink that keeps at least 4.5:1 on it. */
internal data class Tone(val tint: Color, val strong: Color)

internal object Tones {
    val Blue = Tone(Color(0xFFE7EDFF), Palette.Blue)
    val Teal = Tone(Color(0xFFE2F6F3), Palette.Teal)
    val Violet = Tone(Color(0xFFF1E9FE), Palette.Violet)
    val Amber = Tone(Color(0xFFFEF1E2), Palette.Amber)
    val Rose = Tone(Color(0xFFFDE8EE), Palette.Rose)
    val Emerald = Tone(Color(0xFFE4F6EA), Palette.Emerald)
    val Sky = Tone(Color(0xFFE3F2FE), Palette.Sky)
    val Neutral = Tone(Color(0xFFEFF1F7), Palette.InkMuted)
}

internal fun toolTone(tool: LocalTool): Tone = when (tool) {
    LocalTool.SCAN, LocalTool.PDF, LocalTool.SPLIT -> Tones.Blue
    LocalTool.IMPORT_IMAGES, LocalTool.LONG_IMAGE -> Tones.Teal
    LocalTool.IMPORT_PDF, LocalTool.QR -> Tones.Sky
    LocalTool.OCR, LocalTool.TEXT, LocalTool.WATERMARK -> Tones.Amber
    LocalTool.TXT, LocalTool.PROTECT -> Tones.Emerald
    LocalTool.DOCX, LocalTool.EXTRACT -> Tones.Violet
    LocalTool.ID_CARD, LocalTool.SIGN, LocalTool.HIGHLIGHT -> Tones.Rose
    LocalTool.PEN, LocalTool.COMPRESS -> Tones.Teal
    LocalTool.PRINT -> Tones.Blue
}

/** White body copy keeps at least 6.4:1 across both stops of this gradient. */
internal val HeroBrush = Brush.linearGradient(listOf(Palette.Blue, Palette.Violet))

internal fun freeScannerColorScheme() = lightColorScheme(
    primary = Palette.Blue,
    onPrimary = Color.White,
    primaryContainer = Tones.Blue.tint,
    onPrimaryContainer = Palette.Blue,
    inversePrimary = Color(0xFFB8C6FF),
    secondary = Palette.Violet,
    onSecondary = Color.White,
    secondaryContainer = Tones.Violet.tint,
    onSecondaryContainer = Palette.Violet,
    tertiary = Palette.Teal,
    onTertiary = Color.White,
    tertiaryContainer = Tones.Teal.tint,
    onTertiaryContainer = Palette.Teal,
    background = Palette.Canvas,
    onBackground = Palette.Ink,
    surface = Palette.Surface,
    onSurface = Palette.Ink,
    surfaceVariant = Color(0xFFEDF0F7),
    onSurfaceVariant = Palette.InkMuted,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBFCFE),
    surfaceContainer = Palette.Canvas,
    surfaceContainerHigh = Color(0xFFEFF2F8),
    surfaceContainerHighest = Color(0xFFE9EDF5),
    outline = Palette.Border,
    outlineVariant = Color(0xFFEDF0F6),
    error = Palette.Rose,
    onError = Color.White,
    errorContainer = Tones.Rose.tint,
    onErrorContainer = Palette.Rose,
    scrim = Color(0xFF0B1020),
)

private val FreeScannerTypography = Typography(
    displaySmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 23.sp, lineHeight = 29.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp),
)

private val FreeScannerShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
internal fun FreeScannerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = freeScannerColorScheme(),
        typography = FreeScannerTypography,
        shapes = FreeScannerShapes,
        content = content,
    )
}
