package org.siloserver.silo.common.audiobook

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.siloserver.silo.common.ui.marquee.rememberReduceMotion

/**
 * The audiobook player's ambient backdrop, after Apple's
 * `AudioPlayerBackground`: cover-sampled colors pinned to the corners, a
 * whole-cover glow and edge blends that drift slowly while audio plays, and
 * a scrim that keeps the controls legible. Compose has no mesh gradient, so
 * overlapping radial glows stand in for Apple's 3×3 mesh. The drift stops
 * while paused (resuming from the frame it stopped on) and for Reduce Motion.
 */
@Composable
fun AudioPlayerBackground(
    palette: AudioCoverPalette,
    isPlaying: Boolean,
    baseColor: Color,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    // Seconds of drift: advances only while playing, so a resumed drift
    // continues where it paused instead of jumping ahead.
    var phase by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(isPlaying, reduceMotion) {
        if (!isPlaying || reduceMotion) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val delta = (now - last) / 1_000_000_000.0
            // About 30 fps is plenty for a drift this slow.
            if (delta >= 1.0 / 30.0) {
                phase += delta.coerceAtMost(0.25)
                last = now
            }
        }
    }
    Box(modifier = modifier.background(baseColor)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawMesh(palette, phase)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.00f to Color.Black.copy(alpha = 0.20f),
                        0.35f to Color.Black.copy(alpha = 0.05f),
                        1.00f to Color.Black.copy(alpha = 0.55f),
                    ),
                ),
        )
    }
}

private fun DrawScope.drawMesh(palette: AudioCoverPalette, phase: Double) {
    // Two incommensurate periods so the drift never visibly loops.
    val a = sin(phase / 11.0).toFloat()
    val b = cos(phase / 17.0).toFloat()
    val w = size.width
    val h = size.height
    val reach = hypot(w, h)
    val c = palette.corners

    fun glow(color: Color, x: Float, y: Float, radius: Float, strength: Float = 1f) {
        val center = Offset(x * w, y * h)
        drawRect(
            brush = Brush.radialGradient(
                0.0f to color.copy(alpha = strength),
                0.55f to color.copy(alpha = strength * 0.55f),
                1.0f to color.copy(alpha = 0f),
                center = center,
                radius = radius,
            ),
        )
    }

    // The middle first, then the corners over it, the way the mesh's
    // interior blends toward its pinned corner colors.
    glow(palette.center, 0.5f + 0.12f * b, 0.5f - 0.12f * a, reach * 0.55f)
    glow(c[0], 0f, 0f, reach * 0.62f)
    glow(c[1], 1f, 0f, reach * 0.62f)
    glow(c[2], 0f, 1f, reach * 0.62f)
    glow(c[3], 1f, 1f, reach * 0.62f)
    // Edge midpoints drift along their edges, as the mesh's border points do.
    glow(lerp(c[0], c[1], 0.5f), 0.5f + 0.10f * a, 0f, reach * 0.34f, 0.7f)
    glow(lerp(c[2], c[3], 0.5f), 0.5f - 0.10f * b, 1f, reach * 0.34f, 0.7f)
    glow(lerp(c[0], c[2], 0.5f), 0f, 0.5f + 0.08f * b, reach * 0.30f, 0.6f)
    glow(lerp(c[1], c[3], 0.5f), 1f, 0.5f - 0.08f * a, reach * 0.30f, 0.6f)
}
