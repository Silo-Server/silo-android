package org.siloserver.silo.common.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.progressSemantics
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/** Bar heights for the two apps' cards. */
object WatchProgressBarDefaults {
    val PhoneHeight: Dp = 3.dp
    val TvHeight: Dp = 4.dp

    /**
     * How far above the artwork's bottom edge a corner badge sits to clear
     * the bar on either app: the bottom inset, the TV bar's height, and a
     * small gap for the keyline.
     */
    val BadgeClearance: Dp = BottomInset + TvHeight + 2.dp
}

private val BottomInset = 8.dp

/** Under a bottom-corner label (a box set's film count), as on the web. */
private val BelowCornerLabelInset = 4.dp

/**
 * Watch-progress pill drawn over card artwork, the same design as the web
 * client's `WatchProgressBar`. The dark track carries a keyline (outline
 * plus a soft drop shadow) and the fill a hairline edge, so the bar stays
 * legible on bright artwork where a light fill on a translucent track would
 * vanish. Keep it in step with the web and Apple clients.
 *
 * It places itself at the artwork's bottom edge, inset 10dp from the sides
 * and 8dp from the bottom (4dp when [belowCornerLabel], to clear a box set's
 * film-count chip), fills from the start edge so it mirrors in right-to-left
 * layouts, and draws nothing when [fraction] is 0 or less.
 */
@Composable
fun BoxScope.WatchProgressBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = WatchProgressBarDefaults.PhoneHeight,
    belowCornerLabel: Boolean = false,
) {
    if (!(fraction > 0f)) return
    val clamped = fraction.coerceAtMost(1f)
    val bottomInset = if (belowCornerLabel) BelowCornerLabelInset else BottomInset
    Spacer(
        modifier = modifier
            .align(Alignment.BottomCenter)
            .padding(start = 10.dp, end = 10.dp, bottom = bottomInset)
            .fillMaxWidth()
            .height(height)
            .progressSemantics(clamped)
            .drawWithCache {
                // Built once per size, not on every draw of a scrolling rail.
                val radius = size.height / 2f
                val trackPath = Path().apply {
                    addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius)))
                }
                val keyline = Stroke(width = 1.dp.toPx())
                val hairline = Stroke(width = 0.5.dp.toPx())
                onDrawBehind {
                    // The bar is symmetric apart from the fill, so mirroring the
                    // whole drawing starts the fill at the right edge in RTL.
                    scale(scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f, scaleY = 1f) {
                        drawTrackKeyline(trackPath, keyline)
                        drawRoundRect(color = TrackColor, cornerRadius = CornerRadius(radius))
                        drawFill(size.width * clamped, hairline)
                    }
                }
            },
    )
}

/**
 * The track's outline and drop shadow, both outside the track only (as a
 * CSS box-shadow is), so they never darken the translucent track itself. The
 * shadow is a few stacked capsules rather than a blur: blur mask filters and
 * shadow layers on shapes are not drawn by the hardware renderer before API 28.
 */
private fun DrawScope.drawTrackKeyline(track: Path, keyline: Stroke) {
    val radius = size.height / 2f
    clipPath(track, clipOp = ClipOp.Difference) {
        val shadowOffsetY = 1.dp.toPx()
        for (i in ShadowLayers.indices) {
            val (spreadDp, alpha) = ShadowLayers[i]
            val spread = spreadDp.toPx()
            drawRoundRect(
                color = Color.Black.copy(alpha = alpha),
                topLeft = Offset(-spread, -spread + shadowOffsetY),
                size = Size(size.width + spread * 2f, size.height + spread * 2f),
                cornerRadius = CornerRadius(radius + spread),
            )
        }
        drawOutsideStroke(KeylineColor, keyline, size.width)
    }
}

private fun DrawScope.drawFill(width: Float, hairline: Stroke) {
    drawRoundRect(
        color = FillColor,
        size = Size(width, size.height),
        cornerRadius = CornerRadius(size.height / 2f),
    )
    drawOutsideStroke(FillHairlineColor, hairline, width)
}

/** A capsule stroke lying just outside a capsule [width] wide and as tall as the bar. */
private fun DrawScope.drawOutsideStroke(color: Color, stroke: Stroke, width: Float) {
    val strokeWidth = stroke.width
    val half = strokeWidth / 2f
    drawRoundRect(
        color = color,
        topLeft = Offset(-half, -half),
        size = Size(width + strokeWidth, size.height + strokeWidth),
        cornerRadius = CornerRadius(size.height / 2f + half),
        style = stroke,
    )
}

private val TrackColor = Color.Black.copy(alpha = 0.55f)
private val KeylineColor = Color.Black.copy(alpha = 0.35f)
private val FillColor = Color(0xFFEDEDED)
private val FillHairlineColor = Color.Black.copy(alpha = 0.4f)

// Spread and alpha of each shadow capsule. Stacked, they fall off from about
// 0.23 at the track's edge to nothing 3dp out, close to a 3dp blur at 0.45.
private val ShadowLayers = listOf(1.dp to 0.12f, 2.dp to 0.08f, 3.dp to 0.05f)
