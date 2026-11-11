package org.siloserver.silo.common.overlays

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.i18n.LocaleFormat
import org.siloserver.silo.common.i18n.currentNumberLocale
import org.siloserver.silo.overlays.CardWatchStatus
import org.siloserver.silo.overlays.EDGE_INSET
import org.siloserver.silo.overlays.POSTER_REFERENCE_WIDTH
import org.siloserver.silo.overlays.WATCH_STATUS_FONT_SIZE
import org.siloserver.silo.overlays.WATCH_STATUS_SIZE
import kotlin.math.max

/**
 * The watched check's colours. Which look Android uses is an open product
 * decision: these follow web (a green disc with a white check); Android drew
 * a light disc with a dark check before. Change it here.
 */
object CardWatchedCheckColors {
    /** The disc: Tailwind `emerald-500`. */
    val Disc = Color(0xFF00BC7D)

    /** The check mark. */
    val Check = Color.White
}

private val WatchedBorder = Color.White.copy(alpha = 0.35f)
private val CountFill = Color.Black.copy(alpha = 0.65f)
private val CountBorder = Color.White.copy(alpha = 0.2f)

/** Tailwind `shadow-sm`: `0 1px 3px 0 rgb(0 0 0 / 0.1), 0 1px 2px -1px rgb(0 0 0 / 0.1)`. */
private val ShadowColor = Color.Black.copy(alpha = 0.1f).toArgb()

/** The check's share of the disc (web: Lucide `Check` at 62%). */
private const val CHECK_SHARE = 0.62f

/** Lucide's `Check` stroke, on its 24-unit grid. */
private const val CHECK_STROKE = 3.25f

/** The count's horizontal padding, in `em`. */
private const val COUNT_PADDING_EM = 0.4f

/**
 * A poster's top-right corner (web `CardWatchStatus`): a check on a watched
 * title, or the episodes a started series or season has left. Fills the
 * artwork box it is placed in, measures its width, and draws [EDGE_INSET]
 * from the top and the physical right edge in every language, sized against
 * the 185-unit reference width like the badges. Draws nothing for
 * [CardWatchStatus.None]. It is not a setting: poster hosts draw it whether
 * or not badges are on, and wide cards never do.
 */
@Composable
fun CardWatchStatusBadge(status: CardWatchStatus, modifier: Modifier = Modifier) {
    when (status) {
        CardWatchStatus.None -> Unit
        CardWatchStatus.Watched -> WatchedCheck(modifier)
        is CardWatchStatus.EpisodesLeft -> EpisodesLeftCount(status.count, modifier)
    }
}

@Composable
private fun WatchedCheck(modifier: Modifier) {
    val description = stringResource(CommonR.string.shell_card_watched)
    Spacer(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = description }
            .drawWithCache {
                val unit = size.width / POSTER_REFERENCE_WIDTH.toFloat()
                val diameter = (WATCH_STATUS_SIZE * unit).toFloat()
                val inset = (EDGE_INSET * unit).toFloat()
                val topLeft = Offset(size.width - inset - diameter, inset)
                val border = density
                val box = diameter * CHECK_SHARE
                val grid = box / 24f
                // Lucide Check: M20 6 9 17l-5-5.
                val check = Path().apply {
                    moveTo(20f * grid, 6f * grid)
                    lineTo(9f * grid, 17f * grid)
                    lineTo(4f * grid, 12f * grid)
                }
                val checkStroke = Stroke(
                    width = CHECK_STROKE * grid,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                )
                val radius = diameter / 2f
                val center = topLeft + Offset(radius, radius)
                val shadows = shadowPaints()
                onDrawBehind {
                    drawShadows(shadows, topLeft, Size(diameter, diameter), radius)
                    drawCircle(CardWatchedCheckColors.Disc, radius, center)
                    drawCircle(WatchedBorder, radius - border / 2f, center, style = Stroke(border))
                    translate(center.x - box / 2f, center.y - box / 2f) {
                        drawPath(check, CardWatchedCheckColors.Check, style = checkStroke)
                    }
                }
            },
    )
}

@Composable
private fun EpisodesLeftCount(count: Int, modifier: Modifier) {
    // No string says "N episodes left" yet, so the count carries no
    // description of its own.
    val text = LocaleFormat.integer(count, currentNumberLocale())
    val measurer = rememberTextMeasurer(cacheSize = 1)
    val baseStyle = remember {
        TextStyle(
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontFeatureSettings = "tnum",
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            platformStyle = PlatformTextStyle(includeFontPadding = false),
        )
    }
    Spacer(
        modifier = modifier
            .fillMaxSize()
            .drawWithCache {
                val unit = size.width / POSTER_REFERENCE_WIDTH.toFloat()
                val height = (WATCH_STATUS_SIZE * unit).toFloat()
                val inset = (EDGE_INSET * unit).toFloat()
                // Web sizes this text from the card alone, without the text ratio.
                val fontPx = (WATCH_STATUS_FONT_SIZE * unit).toFloat()
                val fontSize = (fontPx / density / fontScale).sp
                val layout = measurer.measure(text, baseStyle.copy(fontSize = fontSize, lineHeight = fontSize))
                val border = density
                val width = max(height, layout.size.width + fontPx * COUNT_PADDING_EM * 2f + border * 2f)
                val topLeft = Offset(size.width - inset - width, inset)
                val capsule = Size(width, height)
                val radius = height / 2f
                val textOffset = topLeft + Offset(
                    (width - layout.size.width) / 2f,
                    (height - layout.size.height) / 2f,
                )
                val shadows = shadowPaints()
                onDrawBehind {
                    drawShadows(shadows, topLeft, capsule, radius)
                    drawRoundRect(CountFill, topLeft, capsule, CornerRadius(radius))
                    drawRoundRect(
                        CountBorder,
                        topLeft + Offset(border / 2f, border / 2f),
                        Size(width - border, height - border),
                        CornerRadius(radius - border / 2f),
                        style = Stroke(border),
                    )
                    drawText(layout, topLeft = textOffset)
                }
            },
    )
}

private fun androidx.compose.ui.draw.CacheDrawScope.shadowPaints(): Array<android.graphics.Paint> {
    val one = density
    return arrayOf(
        shadowPaint(radius = cssBlurToShadowRadius(3f * one), dy = one),
        // The second layer's -1px spread is left out.
        shadowPaint(radius = cssBlurToShadowRadius(2f * one), dy = one),
    )
}

private fun shadowPaint(radius: Float, dy: Float) =
    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        setShadowLayer(max(radius, Float.MIN_VALUE), 0f, dy, ShadowColor)
    }

/** The `shadow-sm` layers around a rounded box, outside the box only, as CSS draws them. */
private fun DrawScope.drawShadows(paints: Array<android.graphics.Paint>, topLeft: Offset, size: Size, radius: Float) {
    val box = RoundRect(Rect(topLeft, size), CornerRadius(radius))
    val clip = Path().apply { addRoundRect(box) }
    drawIntoCanvas { canvas ->
        canvas.save()
        canvas.clipPath(clip, ClipOp.Difference)
        for (paint in paints) {
            canvas.nativeCanvas.drawRoundRect(box.left, box.top, box.right, box.bottom, radius, radius, paint)
        }
        canvas.restore()
    }
}
