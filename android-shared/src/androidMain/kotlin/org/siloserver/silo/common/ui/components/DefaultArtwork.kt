package org.siloserver.silo.common.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import org.siloserver.silo.common.ui.marquee.MarqueeColors
import kotlin.math.max

private val DefaultArtworkBase = Color(0xFF0B0C10)
private val DefaultArtworkScrim = Color(0xFF08090C).copy(alpha = 0.35f)

/**
 * What a poster, cover or still shows when the title has no artwork: the
 * Silo brand colours as a soft glow, the same for every title. The Apple and
 * web clients draw the same glow, so keep these stops in step with them.
 *
 * [contentDescription] stands in for the missing image's own, so a card whose
 * caption is hidden keeps an accessible name.
 */
@Composable
fun DefaultArtwork(modifier: Modifier = Modifier, contentDescription: String? = null) {
    val described = if (contentDescription != null) {
        Modifier.semantics {
            this.contentDescription = contentDescription
            role = Role.Image
        }
    } else {
        Modifier
    }
    Box(
        modifier = modifier.then(described).drawBehind {
            drawRect(DefaultArtworkBase)
            // Sized from the longer side so a poster and a still get the same
            // glow, only cropped differently.
            val side = max(size.width, size.height)
            if (side > 0f) {
                glow(MarqueeColors.BrandBlue, alpha = 0.55f, center = Offset.Zero, radius = side * 0.75f)
                glow(MarqueeColors.BrandOrange, alpha = 0.45f, center = Offset(size.width, size.height), radius = side * 0.6f)
                glow(MarqueeColors.BrandRed, alpha = 0.35f, center = Offset(size.width * 0.7f, size.height * 0.3f), radius = side * 0.5f)
            }
            drawRect(DefaultArtworkScrim)
        },
    )
}

private fun DrawScope.glow(color: Color, alpha: Float, center: Offset, radius: Float) {
    drawRect(
        Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)),
            center = center,
            radius = radius,
        ),
    )
}
