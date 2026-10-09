package org.siloserver.silo.tv.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Shapes
import androidx.tv.material3.darkColorScheme
import org.siloserver.silo.common.ui.components.LocalDefaultArtworkMarkMaxSize

private val SiloTvDarkColorScheme = darkColorScheme(
    primary = SiloPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkSurfaceElevated,
    onPrimaryContainer = SiloOnSurface,
    secondary = SiloOnSurface,
    onSecondary = DarkOnPrimary,
    secondaryContainer = DarkSurfaceVariant,
    onSecondaryContainer = SiloOnSurface,
    tertiary = SiloSecondaryText,
    onTertiary = DarkOnPrimary,
    tertiaryContainer = DarkSurfaceVariant,
    onTertiaryContainer = SiloSecondaryText,
    error = ErrorRed,
    onError = DarkOnSurface,
    errorContainer = ErrorRed,
    onErrorContainer = DarkOnSurface,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    border = DarkOutline,
    borderVariant = DarkOutlineVariant,
    scrim = Scrim,
)

/**
 * Dedicated radius for the squared control kit (pills + toggles). Mirrors the
 * Apple tvOS `SiloTheme.smallCornerRadius` (8pt) mapped to the 960x540 DP
 * canvas (× 0.5 = 4dp). Intentionally distinct from the [Shapes.small] (12.dp)
 * token — these controls render tighter corners.
 */
val TvControlCorner = 4.dp

// tvOS corner radii: 8 / 12 / 18.
private val SiloTvShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private const val TvUiFontScale = 0.86f

/** Missing-artwork marks may grow larger on TV posters than on phone. */
private val TvDefaultArtworkMarkMaxSize = 64.dp

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SiloTvTheme(
    content: @Composable () -> Unit,
) {
    val deviceDensity = LocalDensity.current
    // Skyline geometry tokens are already mapped from the 1920x1080 tvOS
    // canvas to Android TV's 960x540dp layout. Keep dp geometry at the device
    // density and apply the tvOS calibration only to text.
    val tvDensity = Density(
        density = deviceDensity.density,
        fontScale = deviceDensity.fontScale * TvUiFontScale,
    )

    MaterialTheme(
        colorScheme = SiloTvDarkColorScheme,
        typography = SiloTvTypography,
        shapes = SiloTvShapes,
    ) {
        CompositionLocalProvider(
            LocalDensity provides tvDensity,
            LocalDefaultArtworkMarkMaxSize provides TvDefaultArtworkMarkMaxSize,
        ) {
            content()
        }
    }
}
