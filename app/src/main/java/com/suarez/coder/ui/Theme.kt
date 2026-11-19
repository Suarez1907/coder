package com.suarez.coder.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.suarez.coder.data.ThemeMode

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD1FF),
    onPrimary = Color(0xFF00344F),
    primaryContainer = Color(0xFF004C70),
    onPrimaryContainer = Color(0xFFC7E7FF),
    secondary = Color(0xFFB4F08C),
    onSecondary = Color(0xFF203600),
    secondaryContainer = Color(0xFF334E0F),
    onSecondaryContainer = Color(0xFFCFFFA7),
    tertiary = Color(0xFFFFB3B3),
    onTertiary = Color(0xFF561D22),
    tertiaryContainer = Color(0xFF733337),
    onTertiaryContainer = Color(0xFFFFDAD9),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE0E3E8),
    surface = Color(0xFF101418),
    onSurface = Color(0xFFE0E3E8),
    surfaceVariant = Color(0xFF40484E),
    onSurfaceVariant = Color(0xFFC0C7CE),
    surfaceContainerLow = Color(0xFF181C20),
    surfaceContainer = Color(0xFF1D2024),
    surfaceContainerHigh = Color(0xFF272A2E),
    surfaceContainerHighest = Color(0xFF32353A),
    outline = Color(0xFF8A9297),
    outlineVariant = Color(0xFF40484E),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B6BA8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFC7E7FF),
    onPrimaryContainer = Color(0xFF001E31),
    secondary = Color(0xFF3B7A12),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCFFFA7),
    onSecondaryContainer = Color(0xFF0E2000),
    tertiary = Color(0xFF8F4C50),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDAD9),
    onTertiaryContainer = Color(0xFF3B080E),
    background = Color(0xFFF7F9FD),
    onBackground = Color(0xFF181C20),
    surface = Color(0xFFF7F9FD),
    onSurface = Color(0xFF181C20),
    surfaceVariant = Color(0xFFDCE3EA),
    onSurfaceVariant = Color(0xFF40484E),
    surfaceContainerLow = Color(0xFFF1F4F9),
    surfaceContainer = Color(0xFFEBEEF3),
    surfaceContainerHigh = Color(0xFFE5E8ED),
    surfaceContainerHighest = Color(0xFFDFE2E8),
    outline = Color(0xFF71787E),
    outlineVariant = Color(0xFFC0C7CE),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

val CoderShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun CoderTheme(
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = CoderShapes,
        content = content,
    )
}
