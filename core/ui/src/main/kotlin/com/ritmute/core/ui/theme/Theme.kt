package com.ritmute.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Colours that carry meaning rather than decoration.
 *
 * These exist because the app has to say three things over and over — this is running,
 * this is scheduled but idle, this is broken — and saying them with the accent colour
 * alone would make them invisible under dynamic colour, which the user controls and we
 * do not. They are always paired with an icon and text, never used as the sole signal,
 * so the app stays legible to colour-blind users and under TalkBack.
 */
data class SemanticColors(
    val active: Color,
    val onActive: Color,
    val activeContainer: Color,
    val onActiveContainer: Color,
    val scheduled: Color,
    val paused: Color,
    val onPausedContainer: Color,
    val pausedContainer: Color,
    val degraded: Color,
    val degradedContainer: Color,
    val onDegradedContainer: Color,
)

private val LightSemantics = SemanticColors(
    active = Color(0xFF1F6E43),
    onActive = Color(0xFFFFFFFF),
    activeContainer = Color(0xFFB8F0CD),
    onActiveContainer = Color(0xFF002110),
    scheduled = Color(0xFF4A6FA5),
    paused = Color(0xFF7A5800),
    pausedContainer = Color(0xFFFFDEA6),
    onPausedContainer = Color(0xFF271900),
    degraded = Color(0xFFA4322A),
    degradedContainer = Color(0xFFFFDAD5),
    onDegradedContainer = Color(0xFF410100),
)

private val DarkSemantics = SemanticColors(
    active = Color(0xFF7ED9A5),
    onActive = Color(0xFF00391D),
    activeContainer = Color(0xFF00522C),
    onActiveContainer = Color(0xFFB8F0CD),
    scheduled = Color(0xFFAAC7FF),
    paused = Color(0xFFF2BE48),
    pausedContainer = Color(0xFF5C4200),
    onPausedContainer = Color(0xFFFFDEA6),
    degraded = Color(0xFFFFB4A8),
    degradedContainer = Color(0xFF841F16),
    onDegradedContainer = Color(0xFFFFDAD5),
)

val LocalSemanticColors = staticCompositionLocalOf { LightSemantics }

private val LightScheme = lightColorScheme(
    primary = Color(0xFF3F5F91),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD7E3FF),
    onPrimaryContainer = Color(0xFF001B3D),
    secondary = Color(0xFF565E71),
    secondaryContainer = Color(0xFFDAE2F9),
    tertiary = Color(0xFF715573),
    tertiaryContainer = Color(0xFFFCD7FB),
    surface = Color(0xFFFAF9FD),
    onSurface = Color(0xFF1A1B20),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF44464F),
    outline = Color(0xFF74777F),
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFA8C8FF),
    onPrimary = Color(0xFF06305F),
    primaryContainer = Color(0xFF264777),
    onPrimaryContainer = Color(0xFFD7E3FF),
    secondary = Color(0xFFBEC6DC),
    secondaryContainer = Color(0xFF3E4759),
    tertiary = Color(0xFFDEBCDE),
    tertiaryContainer = Color(0xFF583E5B),
    surface = Color(0xFF121318),
    onSurface = Color(0xFFE3E2E8),
    surfaceVariant = Color(0xFF44464F),
    onSurfaceVariant = Color(0xFFC4C6D0),
    outline = Color(0xFF8E9099),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF93000A),
)

@Composable
fun RitMuteTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkScheme
        else -> LightScheme
    }

    CompositionLocalProvider(
        LocalSemanticColors provides if (darkTheme) DarkSemantics else LightSemantics,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = RitMuteTypography,
            content = content,
        )
    }
}

/** Shorthand so screens read `RitMuteTheme.semantic.active` instead of a local lookup. */
object RitMuteTheme {
    val semantic: SemanticColors
        @Composable get() = LocalSemanticColors.current
}
