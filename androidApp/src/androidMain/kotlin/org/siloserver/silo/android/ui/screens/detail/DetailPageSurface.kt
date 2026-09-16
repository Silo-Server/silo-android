package org.siloserver.silo.android.ui.screens.detail

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.lerp
import org.siloserver.silo.common.ui.components.ThumbhashImage

/**
 * The artwork-matched canvas behind a movie/series detail page, matching iOS
 * `PhoneDetailPageSurface` (silo-apple `Screens/Detail/Phone/PhoneDetailHero.swift`).
 *
 * A heavily softened, saturated copy of the hero sits fixed behind the
 * scrolling content and supplies the colour field that stays visible as the
 * sharp artwork drifts away. Android previously painted a single flat
 * `lerp(black, tint, 0.42)` — which is iOS's *fallback* branch, the one it uses
 * only when the effect is unavailable.
 *
 * The fallback is kept for API 30 and below, where `Modifier.blur` does
 * nothing: an unblurred, scaled-up backdrop would be far worse than the flat
 * wash. That mirrors iOS dropping to the same flat tint under Reduce
 * Transparency.
 */
@Composable
fun DetailPageSurface(
    backdropUrl: String?,
    backdropThumbhash: String?,
    tint: Color,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val usesArtworkGlass = canBlur && !backdropUrl.isNullOrBlank()

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (usesArtworkGlass) {
            ThumbhashImage(
                url = backdropUrl,
                thumbhash = backdropThumbhash,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // Decoding a full-size backdrop only to blur it 46dp is waste;
                // the detail is destroyed either way.
                decodeSizePx = BackdropDecodeSizePx,
                crossfadeMillis = 0,
                colorFilter = ColorFilter.colorMatrix(SaturateAndDim),
                modifier = Modifier
                    .fillMaxSize()
                    // Scaled past the edges so the blur has real pixels to
                    // sample instead of smearing the border inward.
                    .graphicsLayer {
                        scaleX = BackdropScale
                        scaleY = BackdropScale
                    }
                    .blur(BackdropBlurRadius, BlurredEdgeTreatment.Rectangle),
            )
            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)))
            Box(modifier = Modifier.fillMaxSize().background(tint.copy(alpha = 0.10f)))
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(lerp(Color.Black, tint, 0.42f)),
            )
        }
        content()
    }
}

private const val BackdropScale = 1.18f
private val BackdropBlurRadius = 46.dp
private const val BackdropDecodeSizePx = 256

/** iOS `.saturation(1.28).brightness(-0.10)`. */
private val SaturateAndDim = ColorMatrix().apply {
    setToSaturation(1.28f)
    // brightness(-0.10) in SwiftUI's 0..1 space is a flat -25.5 per channel.
    this[0, 4] = -25.5f
    this[1, 4] = -25.5f
    this[2, 4] = -25.5f
}
