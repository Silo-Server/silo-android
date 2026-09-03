package org.siloserver.silo.android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// iOS pins .preferredColorScheme(.dark) in ContentView.swift, so Android matches by
// always emitting the Plezy OLED scheme regardless of system or preference toggles.
private val SiloDarkColorScheme = darkColorScheme(
    primary = SiloPrimary,
    onPrimary = SiloBackground,
    primaryContainer = SiloSurfaceElevated,
    onPrimaryContainer = SiloOnSurface,
    secondary = SiloOnSurface,
    onSecondary = SiloBackground,
    secondaryContainer = SiloSurfaceVariant,
    onSecondaryContainer = SiloOnSurface,
    tertiary = SiloSecondaryText,
    onTertiary = SiloBackground,
    tertiaryContainer = SiloSurfaceVariant,
    onTertiaryContainer = SiloSecondaryText,
    error = SiloError,
    onError = SiloOnError,
    errorContainer = ErrorRedContainer,
    onErrorContainer = OnErrorContainer,
    background = SiloPageBackground,
    onBackground = SiloOnSurface,
    surface = SiloSurface,
    onSurface = SiloOnSurface,
    surfaceVariant = SiloSurfaceVariant,
    onSurfaceVariant = SiloSecondaryText,
    // `darkColorScheme` leaves the container ladder on M3's purple-tinted
    // baseline, so anything reaching for `surfaceContainer*` used to land off
    // the Silo palette entirely (the cast bars did, and the settings cards
    // avoided the roles by borrowing `primaryContainer`). Populated so the
    // roles mean what they say.
    surfaceContainerLowest = SiloSurfaceContainerLowest,
    surfaceContainerLow = SiloSurfaceContainerLow,
    surfaceContainer = SiloSurfaceContainer,
    surfaceContainerHigh = SiloSurfaceContainerHigh,
    surfaceContainerHighest = SiloSurfaceContainerHighest,
    surfaceDim = SiloSurfaceDim,
    surfaceBright = SiloSurfaceBright,
    outline = SiloOutline,
    outlineVariant = SiloOutline,
    inverseSurface = SiloOnSurface,
    inverseOnSurface = SiloBackground,
    inversePrimary = SiloBackground,
    scrim = SiloOverlay,
    surfaceTint = Color.Transparent,
)

@Composable
fun SiloTheme(
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
        colorScheme = SiloDarkColorScheme,
        typography = SiloTypography,
        shapes = SiloShapes,
        content = content,
    )
}
