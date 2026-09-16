package org.siloserver.silo.android.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * The signed-in page canvas, matching iOS `SiloPageBackdrop`
 * (silo-apple `iosApp/iosApp/Extensions/ViewExtensions.swift`).
 *
 * [SiloPageBackground] already carried iOS's #111111 base, but Android painted
 * it flat while iOS lays two static washes over it: a broad off-centre
 * highlight and a top-to-bottom shade. Both are deliberately near-invisible in
 * isolation — together they stop a full-screen charcoal fill from reading as a
 * dead slab. Fixed values, never sampled from page artwork, exactly as on iOS.
 */
private val PageBackdropHighlightRadius = 520.dp

@Composable
fun Modifier.siloPageBackdrop(): Modifier {
    val highlightRadiusPx = with(LocalDensity.current) { PageBackdropHighlightRadius.toPx() }
    return this.drawBehind {
        drawRect(color = SiloPageBackground)
        drawRect(
            brush = Brush.radialGradient(
                0.00f to Color.White.copy(alpha = 0.035f),
                0.36f to Color.White.copy(alpha = 0.018f),
                1.00f to Color.Transparent,
                center = Offset(size.width * 0.46f, size.height * 0.42f),
                radius = highlightRadiusPx,
            ),
        )
        drawRect(
            brush = Brush.verticalGradient(
                0.00f to Color.White.copy(alpha = 0.012f),
                0.45f to Color.Transparent,
                1.00f to Color.Black.copy(alpha = 0.045f),
            ),
        )
    }
}
