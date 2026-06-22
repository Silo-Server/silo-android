package com.continuum.app.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// iOS pins .preferredColorScheme(.dark) in ContentView.swift, so Android matches by
// always emitting the Plezy OLED scheme regardless of system or preference toggles.
private val ContinuumDarkColorScheme = darkColorScheme(
    primary = ContinuumPrimary,
    onPrimary = ContinuumBackground,
    primaryContainer = ContinuumSurfaceElevated,
    onPrimaryContainer = ContinuumOnSurface,
    secondary = ContinuumOnSurface,
    onSecondary = ContinuumBackground,
    secondaryContainer = ContinuumSurfaceVariant,
    onSecondaryContainer = ContinuumOnSurface,
    tertiary = ContinuumSecondaryText,
    onTertiary = ContinuumBackground,
    tertiaryContainer = ContinuumSurfaceVariant,
    onTertiaryContainer = ContinuumSecondaryText,
    error = ContinuumError,
    onError = ContinuumOnError,
    errorContainer = ErrorRedContainer,
    onErrorContainer = OnErrorContainer,
    background = ContinuumBackground,
    onBackground = ContinuumOnSurface,
    surface = ContinuumSurface,
    onSurface = ContinuumOnSurface,
    surfaceVariant = ContinuumSurfaceVariant,
    onSurfaceVariant = ContinuumSecondaryText,
    outline = ContinuumOutline,
    outlineVariant = ContinuumOutline,
    inverseSurface = ContinuumOnSurface,
    inverseOnSurface = ContinuumBackground,
    inversePrimary = ContinuumBackground,
    scrim = ContinuumOverlay,
    surfaceTint = Color.Transparent,
)

@Composable
fun ContinuumTheme(
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = ContinuumDarkColorScheme,
        typography = ContinuumTypography,
        shapes = ContinuumShapes,
        content = content,
    )
}
