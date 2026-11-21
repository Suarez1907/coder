package com.suarez.coder.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Semantic colours that Material's scheme has no slot for. */
@Immutable
data class CoderColors(
    val architect: Color,
    val editor: Color,
    val cost: Color,
    val cacheGood: Color,
    val cacheMid: Color,
    val warn: Color,
    val border: Color,
    val muted: Color,
    val codeBg: Color,
    val borderHighlight: Color = border,
    val surfaceElevated: Color = codeBg,
    val userBubble: Color = codeBg,
    val userBubbleBorder: Color = border,
    val codeHeader: Color = codeBg,
    val accentSoft: Color = codeBg,
)

private val DarkCoder = CoderColors(
    architect = Color(0xFF8B9CFF), editor = Color(0xFF2DD4BF), cost = Color(0xFFFBBF24),
    cacheGood = Color(0xFF4ADE80), cacheMid = Color(0xFFFACC15), warn = Color(0xFFF87171),
    border = Color(0xFF232B3E), muted = Color(0xFF7C8599), codeBg = Color(0xFF07090F),
    borderHighlight = Color(0xFF33405C), surfaceElevated = Color(0xFF161C2A),
    userBubble = Color(0xFF19223A), userBubbleBorder = Color(0xFF2E3D66),
    codeHeader = Color(0xFF0E131F), accentSoft = Color(0xFF1B2238),
)

private val OledCoder = DarkCoder.copy(
    border = Color(0xFF1B2233), codeBg = Color(0xFF05060A),
    surfaceElevated = Color(0xFF0E121B), borderHighlight = Color(0xFF29344D),
)

private val LightCoder = CoderColors(
    architect = Color(0xFF4F46E5), editor = Color(0xFF059669), cost = Color(0xFFD97706),
    cacheGood = Color(0xFF15803D), cacheMid = Color(0xFFA16207), warn = Color(0xFFDC2626),
    border = Color(0xFFE2E8F0), muted = Color(0xFF6B7280), codeBg = Color(0xFFF1F5F9),
    borderHighlight = Color(0xFFCBD5E1), surfaceElevated = Color(0xFFFFFFFF),
    userBubble = Color(0xFFEEF2FF), userBubbleBorder = Color(0xFFC7D2FE),
    codeHeader = Color(0xFFE8EEF5), accentSoft = Color(0xFFEEF2FF),
)

val LocalCoderColors = staticCompositionLocalOf { DarkCoder }

/** Shortcut: `coder.architect`, `coder.cost`, … */
val coder: CoderColors @Composable get() = LocalCoderColors.current

/** Monospace styles for costs, percentages, paths, tool names and code. */
object Mono {
    val body = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)
    val small = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
    val tiny = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
    val micro = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 14.sp)
}

/** Studio type scale: tighter tracking on titles, calmer line heights on body copy. */
val CoderTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleSmall = TextStyle(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    bodyLarge = TextStyle(lineHeight = 21.sp),
    bodyMedium = TextStyle(lineHeight = 21.sp),
)

/** Studio corner radii: softly rounded cards and sheets. */
val CoderShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

private fun ideDark(oled: Boolean): ColorScheme {
    val bg = if (oled) Color(0xFF000000) else Color(0xFF07090F)
    val surface = Color(0xFF0E121B)
    val raised = if (oled) Color(0xFF0E121B) else Color(0xFF161C2A)
    val border = if (oled) Color(0xFF1B2233) else Color(0xFF232B3E)
    return darkColorScheme(
        primary = Color(0xFF8B9CFF), onPrimary = Color(0xFF0B1030),
        primaryContainer = Color(0xFF232A4D), onPrimaryContainer = Color(0xFFD6DCFF),
        secondary = Color(0xFF2DD4BF), onSecondary = Color(0xFF03201A),
        secondaryContainer = Color(0xFF113328), onSecondaryContainer = Color(0xFFB4F5DA),
        tertiary = Color(0xFFFBBF24), onTertiary = Color(0xFF2A1A00),
        error = Color(0xFFF87171), onError = Color(0xFF2B0808),
        background = bg, onBackground = Color(0xFFE6E9F2),
        surface = surface, onSurface = Color(0xFFE6E9F2),
        surfaceVariant = raised, onSurfaceVariant = Color(0xFF9AA3B8),
        surfaceContainerLowest = bg, surfaceContainerLow = surface, surfaceContainer = surface,
        surfaceContainerHigh = raised, surfaceContainerHighest = raised,
        outline = border, outlineVariant = border,
        inverseSurface = Color(0xFFE6E9F2), inverseOnSurface = Color(0xFF0E121B),
    )
}

private val ideLight = lightColorScheme(
    primary = Color(0xFF4F46E5), onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E3FF), onPrimaryContainer = Color(0xFF151C5C),
    secondary = Color(0xFF059669), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3F5E8), onSecondaryContainer = Color(0xFF053D2D),
    tertiary = Color(0xFFD97706),
    background = Color(0xFFF6F8FC), surface = Color.White, surfaceVariant = Color(0xFFF1F5F9),
    outline = Color(0xFFE2E8F0), outlineVariant = Color(0xFFE2E8F0),
)

/** [mode]: "dark" (default IDE theme), "oled" (pure black), "light" or "system". */
@Composable
fun CoderTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) {
        "light" -> false
        "system" -> isSystemInDarkTheme()
        else -> true
    }
    val oled = mode == "oled"
    val scheme = if (dark) ideDark(oled) else ideLight
    val colors = when {
        !dark -> LightCoder
        oled -> OledCoder
        else -> DarkCoder
    }
    CompositionLocalProvider(LocalCoderColors provides colors) {
        MaterialTheme(
            colorScheme = scheme,
            typography = CoderTypography,
            shapes = CoderShapes,
            content = content,
        )
    }
}

/** Studio surface: subtle vertical gradient fill, crisp hairline border, optional accent rail. */
@Composable
fun StudioCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    elevated: Boolean = false,
    shape: Shape = MaterialTheme.shapes.medium,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val base = if (elevated) coder.surfaceElevated else MaterialTheme.colorScheme.surface
    val stroke = BorderStroke(1.dp, Brush.verticalGradient(listOf(coder.borderHighlight, coder.border)))
    var m = modifier
        .clip(shape)
        .background(Brush.verticalGradient(listOf(base, coder.surfaceElevated)))
        .border(stroke, shape)
    if (accent != null) {
        m = m.drawBehind {
            val w = 3.dp.toPx()
            drawRect(color = accent, topLeft = Offset.Zero, size = Size(width = w, height = size.height))
        }
    }
    if (onClick != null) m = m.clickable { onClick() }
    Column(modifier = m, content = content)
}

/** Crisp status dot with a soft translucent halo — no animation. */
@Composable
fun StatusDot(color: Color, size: Dp = 7.dp, glow: Boolean = true) {
    Canvas(modifier = Modifier.size(size + if (glow) 10.dp else 0.dp)) {
        val r = size.toPx() / 2f
        val c = center
        if (glow) drawCircle(color = color.copy(alpha = 0.18f), radius = r * 2.4f, center = c)
        drawCircle(color = color, radius = r, center = c)
    }
}

/** Tracked uppercase section label with an accent rail and optional trailing slot. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(width = 3.dp, height = 12.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(coder.architect.copy(alpha = 0.85f))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
            color = coder.muted,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) trailing()
    }
}

/** Short model names for the compact control bar: gemini-4-hydrogen → H4, gemini-3.8-flash → 3.8F. */
fun shortModel(model: String): String {
    val m = model.lowercase().substringAfterLast('/') // OpenRouter ids: vendor/model
    Regex("^gemini-4-([a-z])").find(m)?.let { return it.groupValues[1].uppercase() + "4" }
    Regex("^gemini-(\\d+(?:\\.\\d+)?)-(flash-lite|flash|pro)").find(m)?.let {
        val suffix = when (it.groupValues[2]) { "flash-lite" -> "FL"; "flash" -> "F"; else -> "P" }
        return it.groupValues[1] + suffix
    }
    if (m.startsWith("deepseek")) return "DS-" + m.removePrefix("deepseek-").take(6)
    return model.take(10)
}
