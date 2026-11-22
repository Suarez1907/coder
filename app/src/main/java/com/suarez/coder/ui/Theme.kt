package com.suarez.coder.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
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
)

private val DarkCoder = CoderColors(
    architect = Color(0xFF8B9CFF), editor = Color(0xFF34D399), cost = Color(0xFFF5B451),
    cacheGood = Color(0xFF4ADE80), cacheMid = Color(0xFFFACC15), warn = Color(0xFFF87171),
    border = Color(0xFF262C3D), muted = Color(0xFF7C8599), codeBg = Color(0xFF0D1017),
)

private val OledCoder = DarkCoder.copy(border = Color(0xFF1F2433), codeBg = Color(0xFF07080C))

private val LightCoder = CoderColors(
    architect = Color(0xFF4F5BD5), editor = Color(0xFF0F9D74), cost = Color(0xFFB7791F),
    cacheGood = Color(0xFF15803D), cacheMid = Color(0xFFA16207), warn = Color(0xFFDC2626),
    border = Color(0xFFDDE1EA), muted = Color(0xFF6B7280), codeBg = Color(0xFFF3F4F8),
)

val LocalCoderColors = staticCompositionLocalOf { DarkCoder }

/** Shortcut: `coder.architect`, `coder.cost`, … */
val coder: CoderColors @Composable get() = LocalCoderColors.current

/** Monospace styles for costs, percentages, paths, tool names and code. */
object Mono {
    val body = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp)
    val small = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
    val tiny = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)
}

private fun ideDark(oled: Boolean): ColorScheme {
    val bg = if (oled) Color(0xFF000000) else Color(0xFF090B10)
    val surface = if (oled) Color(0xFF0A0C12) else Color(0xFF12151E)
    val raised = if (oled) Color(0xFF141822) else Color(0xFF1A1F2C)
    val border = if (oled) Color(0xFF1F2433) else Color(0xFF262C3D)
    return darkColorScheme(
        primary = Color(0xFF8B9CFF), onPrimary = Color(0xFF0B1030),
        primaryContainer = Color(0xFF232A4D), onPrimaryContainer = Color(0xFFD6DCFF),
        secondary = Color(0xFF34D399), onSecondary = Color(0xFF03201A),
        secondaryContainer = Color(0xFF113328), onSecondaryContainer = Color(0xFFB4F5DA),
        tertiary = Color(0xFFF5B451), onTertiary = Color(0xFF2A1A00),
        error = Color(0xFFF87171), onError = Color(0xFF2B0808),
        background = bg, onBackground = Color(0xFFE6E9F2),
        surface = surface, onSurface = Color(0xFFE6E9F2),
        surfaceVariant = raised, onSurfaceVariant = Color(0xFF9AA3B8),
        surfaceContainerLowest = bg, surfaceContainerLow = surface, surfaceContainer = surface,
        surfaceContainerHigh = raised, surfaceContainerHighest = raised,
        outline = border, outlineVariant = border,
        inverseSurface = Color(0xFFE6E9F2), inverseOnSurface = Color(0xFF12151E),
    )
}

private val ideLight = lightColorScheme(
    primary = Color(0xFF4F5BD5), onPrimary = Color.White,
    primaryContainer = Color(0xFFE0E3FF), onPrimaryContainer = Color(0xFF151C5C),
    secondary = Color(0xFF0F9D74), onSecondary = Color.White,
    secondaryContainer = Color(0xFFD3F5E8), onSecondaryContainer = Color(0xFF053D2D),
    tertiary = Color(0xFFB7791F),
    background = Color(0xFFF7F8FB), surface = Color.White, surfaceVariant = Color(0xFFF0F2F7),
    outline = Color(0xFFDDE1EA), outlineVariant = Color(0xFFDDE1EA),
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
    // Status/navigation bar icons follow the APP theme, not the phone's: dark theme on a light-mode phone
    // otherwise gets dark icons on a black bar (unreadable).
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            (view.context as? android.app.Activity)?.window?.let { w ->
                androidx.core.view.WindowCompat.getInsetsController(w, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }
    CompositionLocalProvider(LocalCoderColors provides colors) {
        MaterialTheme(colorScheme = scheme, content = content)
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
