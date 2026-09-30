package org.siloserver.silo.common.overlays

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.siloserver.silo.overlays.OverlayIconId

/**
 * Android port of Apple's `OverlayIcon`
 * (iosApp/Overlays/OverlayIcon.swift). The generic glyph set (star,
 * clock, tv, …) is drawn with [Canvas] so this stays free of any
 * material-icons dependency — android-shared only links the core Compose
 * runtime/foundation/ui artifacts. Brand marks (HDR10, DV, Atmos, AV1,
 * Tomato) render as short text tokens via [overlayBrandToken]; the badge
 * composable lays those out as text rather than calling [OverlayIconGlyph].
 */

/**
 * For brand-mark icons that have no clean vector at 10pt, returns the
 * short text token to render in place of a glyph (e.g. "DV", "HDR10").
 * Returns `null` for icons that should draw a [OverlayIconGlyph] instead.
 */
internal fun overlayBrandToken(iconId: OverlayIconId): String? =
    when (iconId) {
        OverlayIconId.Hdr10 -> "HDR10"
        OverlayIconId.Hdr -> "HDR"
        OverlayIconId.DolbyVision -> "DV"
        OverlayIconId.Atmos -> "ATMOS"
        OverlayIconId.Av1 -> "AV1"
        else -> null
    }

/**
 * The Tomato brand mark keeps an official-ish red when the preset does
 * not force a tint; the badge passes this through so a "minimal" preset
 * still paints it in the accent/foreground color.
 */
internal val OverlayTomatoColor = Color(red = 0.98f, green = 0.20f, blue = 0.04f)

/**
 * Draws a generic overlay glyph at [size], tinted [tint]. Brand-mark ids
 * that resolve to a text token via [overlayBrandToken] should NOT be
 * routed here (the badge renders them as text); if one slips through it
 * falls back to the question-mark glyph, matching Apple's default arm.
 */
@Composable
internal fun OverlayIconGlyph(
    iconId: OverlayIconId,
    size: Dp,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = Stroke(width = (w * 0.085f).coerceAtLeast(1f))
        when (iconId) {
            OverlayIconId.Star, OverlayIconId.Award, OverlayIconId.Ribbon ->
                drawPath(starPath(w, h), tint, style = Fill)

            OverlayIconId.Tomato ->
                drawCircle(tint, radius = w * 0.42f, center = Offset(w / 2f, h / 2f))

            OverlayIconId.Clock, OverlayIconId.Calendar -> {
                drawCircle(tint, radius = w * 0.42f, center = Offset(w / 2f, h / 2f), style = stroke)
                // Clock hands.
                drawLine(tint, Offset(w / 2f, h / 2f), Offset(w / 2f, h * 0.28f), stroke.width)
                drawLine(tint, Offset(w / 2f, h / 2f), Offset(w * 0.68f, h / 2f), stroke.width)
            }

            OverlayIconId.Globe, OverlayIconId.Languages -> {
                val r = w * 0.42f
                val c = Offset(w / 2f, h / 2f)
                drawCircle(tint, radius = r, center = c, style = stroke)
                drawLine(tint, Offset(w / 2f, h / 2f - r), Offset(w / 2f, h / 2f + r), stroke.width)
                drawLine(tint, Offset(w / 2f - r, h / 2f), Offset(w / 2f + r, h / 2f), stroke.width)
            }

            OverlayIconId.Shield ->
                drawPath(shieldPath(w, h), tint, style = Fill)

            OverlayIconId.Tv, OverlayIconId.Monitor, OverlayIconId.Layout, OverlayIconId.Film -> {
                // Rounded rectangle frame.
                val inset = w * 0.12f
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(inset, h * 0.2f),
                    size = Size(w - inset * 2, h * 0.55f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f),
                    style = stroke,
                )
                if (iconId == OverlayIconId.Tv) {
                    // Stand legs.
                    drawLine(tint, Offset(w * 0.35f, h * 0.85f), Offset(w * 0.65f, h * 0.85f), stroke.width)
                }
            }

            OverlayIconId.Volume -> {
                // Speaker cone + a wave arc.
                val p = Path().apply {
                    moveTo(w * 0.2f, h * 0.4f)
                    lineTo(w * 0.35f, h * 0.4f)
                    lineTo(w * 0.5f, h * 0.25f)
                    lineTo(w * 0.5f, h * 0.75f)
                    lineTo(w * 0.35f, h * 0.6f)
                    lineTo(w * 0.2f, h * 0.6f)
                    close()
                }
                drawPath(p, tint, style = Fill)
                drawCircle(tint, radius = w * 0.12f, center = Offset(w * 0.72f, h / 2f), style = stroke)
            }

            OverlayIconId.Building -> {
                val inset = w * 0.22f
                drawRect(
                    tint,
                    topLeft = Offset(inset, h * 0.2f),
                    size = Size(w - inset * 2, h * 0.6f),
                    style = stroke,
                )
            }

            OverlayIconId.Subtitles -> {
                val inset = w * 0.12f
                drawRoundRect(
                    color = tint,
                    topLeft = Offset(inset, h * 0.25f),
                    size = Size(w - inset * 2, h * 0.5f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * 0.08f),
                    style = stroke,
                )
                drawLine(tint, Offset(w * 0.28f, h * 0.6f), Offset(w * 0.45f, h * 0.6f), stroke.width)
                drawLine(tint, Offset(w * 0.55f, h * 0.6f), Offset(w * 0.72f, h * 0.6f), stroke.width)
            }

            // Brand marks render as text tokens; if routed here, draw a dot.
            else -> drawCircle(tint, radius = w * 0.18f, center = Offset(w / 2f, h / 2f))
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLine(
    color: Color,
    start: Offset,
    end: Offset,
    width: Float,
) {
    drawLine(color = color, start = start, end = end, strokeWidth = width)
}

private fun starPath(w: Float, h: Float): Path {
    val cx = w / 2f
    val cy = h / 2f
    val outer = w * 0.46f
    val inner = w * 0.2f
    return Path().apply {
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) outer else inner
            val angle = Math.PI / 2 * -1 + i * (Math.PI / 5)
            val x = cx + (r * kotlin.math.cos(angle)).toFloat()
            val y = cy + (r * kotlin.math.sin(angle)).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}

private fun shieldPath(w: Float, h: Float): Path =
    Path().apply {
        moveTo(w / 2f, h * 0.12f)
        lineTo(w * 0.85f, h * 0.28f)
        lineTo(w * 0.85f, h * 0.55f)
        // Bottom point.
        quadraticTo(w * 0.85f, h * 0.85f, w / 2f, h * 0.9f)
        quadraticTo(w * 0.15f, h * 0.85f, w * 0.15f, h * 0.55f)
        lineTo(w * 0.15f, h * 0.28f)
        close()
    }
