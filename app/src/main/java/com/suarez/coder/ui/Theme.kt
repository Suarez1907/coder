package com.suarez.coder.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

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
    architect = Color(0xFF5E9EFF), editor = Color(0xFFA1A1AA), cost = Color(0xFFE4E4E7),
    cacheGood = Color(0xFF34D399), cacheMid = Color(0xFFFBBF24), warn = Color(0xFFF87171),
    border = Color.White.copy(alpha = 0.07f), muted = Color(0xFF71717A), codeBg = Color(0xFF0E0E11),
)

private val OledCoder = DarkCoder.copy(
    border = Color.White.copy(alpha = 0.08f), codeBg = Color(0xFF060608),
)

private val LightCoder = CoderColors(
    architect = Color(0xFF2563EB), editor = Color(0xFF52525B), cost = Color(0xFF18181B),
    cacheGood = Color(0xFF15803D), cacheMid = Color(0xFFA16207), warn = Color(0xFFDC2626),
    border = Color.Black.copy(alpha = 0.08f), muted = Color(0xFF71717A), codeBg = Color(0xFFF4F4F5),
)

val LocalCoderColors = staticCompositionLocalOf { DarkCoder }
val LocalIsDark = staticCompositionLocalOf { true }

/** Shortcut: `coder.architect`, `coder.cost`, … */
val coder: CoderColors @Composable get() = LocalCoderColors.current

/** Monospace styles for costs, percentages, paths, tool names and code. */
object Mono {
    val body = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 18.sp, fontFeatureSettings = "tnum")
    val small = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp, fontFeatureSettings = "tnum")
    val tiny = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp, fontFeatureSettings = "tnum")
    val micro = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 14.sp, fontFeatureSettings = "tnum")
}

private fun ideDark(oled: Boolean): ColorScheme {
    val bg = if (oled) Color(0xFF000000) else Color(0xFF0A0A0C)
    val surface = if (oled) Color(0xFF0B0B0E) else Color(0xFF121215)
    val raised = if (oled) Color(0xFF16161A) else Color(0xFF1B1B1F)
    val border = if (oled) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.07f)
    return darkColorScheme(
        primary = Color(0xFF5E9EFF), onPrimary = Color(0xFF0B1030),
        primaryContainer = Color(0xFF1B1B1F), onPrimaryContainer = Color(0xFFF4F4F5),
        secondary = Color(0xFFA1A1AA), onSecondary = Color(0xFF18181B),
        secondaryContainer = Color(0xFF1B1B1F), onSecondaryContainer = Color(0xFFE4E4E7),
        tertiary = Color(0xFFE4E4E7), onTertiary = Color(0xFF18181B),
        error = Color(0xFFF87171), onError = Color(0xFF2B0808),
        background = bg, onBackground = Color(0xFFF4F4F5),
        surface = surface, onSurface = Color(0xFFF4F4F5),
        surfaceVariant = raised, onSurfaceVariant = Color(0xFFA1A1AA),
        surfaceContainerLowest = bg, surfaceContainerLow = surface, surfaceContainer = surface,
        surfaceContainerHigh = raised, surfaceContainerHighest = raised,
        outline = border, outlineVariant = border,
        inverseSurface = Color(0xFFF4F4F5), inverseOnSurface = Color(0xFF121215),
    )
}

private val ideLight = lightColorScheme(
    primary = Color(0xFF2563EB), onPrimary = Color.White,
    primaryContainer = Color(0xFFE4E4E7), onPrimaryContainer = Color(0xFF18181B),
    secondary = Color(0xFF52525B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF4F4F5), onSecondaryContainer = Color(0xFF18181B),
    tertiary = Color(0xFF18181B),
    background = Color(0xFFFAFAFA), surface = Color.White, surfaceVariant = Color(0xFFF4F4F5),
    outline = Color.Black.copy(alpha = 0.08f), outlineVariant = Color.Black.copy(alpha = 0.08f),
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
    CompositionLocalProvider(LocalCoderColors provides colors, LocalIsDark provides dark) {
        SystemBarsEffect()
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** Force system bar icon contrast (white on dark, dark on light) for Activity, Dialog and Sheet windows. */
@Composable
fun SystemBarsEffect() {
    val view = LocalView.current
    if (view.isInEditMode) return
    val isDark = LocalIsDark.current
    SideEffect {
        val windows = buildList {
            (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.let { add(it) }
            view.context.findActivity()?.window?.let { add(it) }
        }.distinct()
        for (window in windows) {
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !isDark
            controller.isAppearanceLightNavigationBars = !isDark
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
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
