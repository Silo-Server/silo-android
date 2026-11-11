package org.siloserver.silo.common.overlays

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import org.siloserver.silo.overlays.CardOverlayPrefs
import org.siloserver.silo.overlays.EDGE_GAP
import org.siloserver.silo.overlays.EDGE_INSET
import org.siloserver.silo.overlays.MAX_BADGES_PER_CORNER
import org.siloserver.silo.overlays.MAX_BADGES_PER_RAIL
import org.siloserver.silo.overlays.OverlayData
import org.siloserver.silo.overlays.OverlayLayout
import org.siloserver.silo.overlays.OverlayPosition
import org.siloserver.silo.overlays.OverlayPreset
import org.siloserver.silo.overlays.OverlayPresets
import org.siloserver.silo.overlays.OverlayRegistry
import org.siloserver.silo.overlays.POSTER_REFERENCE_WIDTH
import org.siloserver.silo.overlays.PresetId
import org.siloserver.silo.overlays.WATCH_STATUS_RESERVE
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Card layout variant: how the badge layer is sized and inset. */
enum class CardOverlayVariant {
    /**
     * A poster card. Every length scales with the card's width against the
     * 185-unit reference, and the top row keeps the top-right corner clear
     * for [CardWatchStatusBadge].
     */
    Poster,

    /** Backdrop card (continue watching, episode stills): `scale`d reference lengths, extra bottom room. */
    Wide,

    /** Large backdrop (detail-page hero, featured carousel). */
    Hero,
}

/**
 * The enabled overlay badges for an item where the profile's prefs place
 * them: the top-left corner, a rail centred on each side edge, and the two
 * bottom corners. Fills the artwork box it is placed in, over the image and
 * under any focus chrome, and never takes touches or focus. Draws nothing,
 * and costs no layout, when no badge has a value.
 *
 * The rules are the web's (`web/src/components/overlays/CardOverlays.tsx`,
 * `docs/architecture/card-overlays.md` in silo-server):
 *
 * - A corner draws at most three badges and a rail four, counted after
 *   badges without a value are skipped; the rest are dropped.
 * - The top-right corner belongs to [CardWatchStatusBadge]: on poster cards
 *   the top row stops short of it, and a long badge truncates instead.
 * - A filled badge on a rail is a tab, flush against the card edge.
 * - The bottom corners share the bottom edge row by row ([OverlayLayoutMath.bottomRows]).
 * - The band preset draws each edge's badges as one strip of marks over a
 *   scrim ([OverlayLayoutMath.bandVisibleCount]).
 * - Positions are places on the artwork, so the layer lays out left to
 *   right in every language.
 *
 * Presentation only: the caller decides whether to show it (gate on
 * [CardOverlayUiState.enabled]) and supplies [data] and [prefs]. Uploaded
 * images resolve through [LocalCardOverlayUiState].
 *
 * @param scale reference-unit multiplier for [CardOverlayVariant.Wide] and
 *   [CardOverlayVariant.Hero]; posters measure their width instead.
 * @param bottomInset overrides a wide or hero card's bottom inset (before
 *   [scale]) for cards that draw only a thin progress bar along the bottom.
 * @param hasProgressBar set by hosts that draw a watch-progress bar along
 *   the artwork's bottom edge: the bottom badges rise one edge step clear
 *   of it.
 * @param cardWidth the poster artwork box's width, when the host knows it
 *   exactly. Posters then scale from it without measuring first (a
 *   subcomposition per card); left null, the layer measures itself. Pass it
 *   only when the layout cannot give the box another width, or the badges
 *   are drawn at the wrong scale.
 */
@Composable
fun CardOverlays(
    data: OverlayData,
    prefs: CardOverlayPrefs,
    modifier: Modifier = Modifier,
    variant: CardOverlayVariant = CardOverlayVariant.Poster,
    scale: Float = 1f,
    bottomInset: Dp? = null,
    hasProgressBar: Boolean = false,
    cardWidth: Dp? = null,
) {
    val uiState = LocalCardOverlayUiState.current
    val preset = remember(prefs.preset, prefs.style) { OverlayPresets.resolved(prefs) }
    // Resolved once per item and prefs: this runs for every card in grids
    // and TV rails, which recompose often.
    val badges = remember(data, prefs, preset, uiState) {
        ResolvedOverlays.resolve(data, prefs, preset, uiState::imageUrl)
    }
    if (badges.isEmpty) return

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        val density = LocalDensity.current
        if (variant == CardOverlayVariant.Poster && cardWidth != null && cardWidth.isSpecified && cardWidth > 0.dp) {
            Box(modifier = modifier.fillMaxSize()) {
                val unit = with(density) { cardWidth.toPx() } / POSTER_REFERENCE_WIDTH.toFloat()
                OverlayLayer(badges, preset, unit, density, variant, scale, bottomInset, hasProgressBar)
            }
        } else if (variant == CardOverlayVariant.Poster) {
            BoxWithConstraints(modifier = modifier.fillMaxSize()) {
                val unit = if (constraints.hasBoundedWidth && constraints.maxWidth > 0) {
                    constraints.maxWidth / POSTER_REFERENCE_WIDTH.toFloat()
                } else {
                    scale.coerceAtLeast(MIN_SCALE) * density.density
                }
                OverlayLayer(badges, preset, unit, density, variant, scale, bottomInset, hasProgressBar)
            }
        } else {
            // Wide and hero cards use fixed reference lengths, so nothing
            // depends on the measured width at composition time.
            Box(modifier = modifier.fillMaxSize()) {
                val unit = scale.coerceAtLeast(MIN_SCALE) * density.density
                OverlayLayer(badges, preset, unit, density, variant, scale, bottomInset, hasProgressBar)
            }
        }
    }
}

private const val MIN_SCALE = 0.1f

/** The share of the card's width a rail's badges may take (web `max-w-[60%]`). */
private const val RAIL_MAX_WIDTH = 0.6f

/** The band scrim: rgb(0 0 0 / 0.85), rgb(0 0 0 / 0.6) at 55%, transparent. */
private val BandScrimStops = arrayOf(
    0f to Color.Black.copy(alpha = 0.85f),
    0.55f to Color.Black.copy(alpha = 0.6f),
    1f to Color.Transparent,
)

/** The divider between band marks: 1px wide, white at 40%. */
private val BandDividerColor = Color.White.copy(alpha = 0.4f)

/** Each position's badges, capped, in the viewer's order. */
@Immutable
internal class ResolvedOverlays(
    val topLeft: List<ResolvedBadge>,
    val bottomLeft: List<ResolvedBadge>,
    val bottomRight: List<ResolvedBadge>,
    val leftRail: List<ResolvedBadge>,
    val rightRail: List<ResolvedBadge>,
) {
    val isEmpty: Boolean
        get() = topLeft.isEmpty() && bottomLeft.isEmpty() && bottomRight.isEmpty() &&
            leftRail.isEmpty() && rightRail.isEmpty()

    companion object {
        fun resolve(
            data: OverlayData,
            prefs: CardOverlayPrefs,
            preset: OverlayPreset,
            imageUrl: (String) -> String?,
        ): ResolvedOverlays {
            fun at(position: OverlayPosition, limit: Int): List<ResolvedBadge> {
                val defs = OverlayRegistry.enabled(position, prefs)
                if (defs.isEmpty()) return emptyList()
                val out = ArrayList<ResolvedBadge>(min(defs.size, limit))
                for (def in defs) {
                    ResolvedBadge.resolve(def, data, prefs, preset, imageUrl)?.let(out::add)
                    if (out.size == limit) break
                }
                return out
            }
            return ResolvedOverlays(
                topLeft = at(OverlayPosition.TopLeft, MAX_BADGES_PER_CORNER),
                bottomLeft = at(OverlayPosition.BottomLeft, MAX_BADGES_PER_CORNER),
                bottomRight = at(OverlayPosition.BottomRight, MAX_BADGES_PER_CORNER),
                leftRail = at(OverlayPosition.LeftEdge, MAX_BADGES_PER_RAIL),
                rightRail = at(OverlayPosition.RightEdge, MAX_BADGES_PER_RAIL),
            )
        }
    }
}

/** The layer's insets and gaps in pixels. */
@Immutable
private data class OverlayInsets(
    val side: Int,
    val top: Int,
    /** From the bottom edge, including the progress-bar lift. */
    val bottom: Int,
    /** From the right edge to the end of the top row. */
    val topRight: Int,
    /** Web `EDGE_INSET` at this card's scale, for the band strips' inner padding. */
    val edge: Int,
    val edgeGap: Int,
)

private fun overlayInsets(
    unit: Float,
    density: Density,
    variant: CardOverlayVariant,
    scale: Float,
    bottomInset: Dp?,
    hasProgressBar: Boolean,
): OverlayInsets {
    val edge = (EDGE_INSET * unit).roundToInt()
    val lift = if (hasProgressBar) edge else 0
    if (variant == CardOverlayVariant.Poster) {
        return OverlayInsets(
            side = edge,
            top = edge,
            bottom = edge + lift,
            topRight = ((EDGE_INSET + WATCH_STATUS_RESERVE) * unit).roundToInt(),
            edge = edge,
            edgeGap = (EDGE_GAP * unit).roundToInt(),
        )
    }
    // Wide and hero cards keep their existing insets: a title block or
    // progress treatment usually sits along the bottom of the image. They
    // never draw the status corner, so the top row runs to the inset.
    val safeScale = scale.coerceAtLeast(MIN_SCALE)
    fun Dp.px(): Int = with(density) { (this@px * safeScale).roundToPx() }
    val side = (if (variant == CardOverlayVariant.Hero) 16.dp else 8.dp).px()
    val bottom = (bottomInset ?: if (variant == CardOverlayVariant.Hero) 16.dp else 24.dp).px()
    return OverlayInsets(
        side = side,
        top = side,
        bottom = bottom + lift,
        topRight = side,
        edge = side,
        edgeGap = (EDGE_GAP * unit).roundToInt(),
    )
}

@Composable
private fun BoxScope.OverlayLayer(
    badges: ResolvedOverlays,
    preset: OverlayPreset,
    unit: Float,
    density: Density,
    variant: CardOverlayVariant,
    scale: Float,
    bottomInset: Dp?,
    hasProgressBar: Boolean,
) {
    val metrics = remember(preset, unit, density) { OverlayMetrics(preset, unit, density) }
    val insets = remember(unit, density, variant, scale, bottomInset, hasProgressBar) {
        overlayInsets(unit, density, variant, scale, bottomInset, hasProgressBar)
    }
    val band = preset.layout == OverlayLayout.Band
    // A filled badge on a rail sits flush against the edge as a tab; a
    // transparent one (minimal, band) keeps the corner inset.
    val flushRails = !band && preset.id != PresetId.Minimal
    val railInset = if (flushRails) 0 else insets.side

    if (badges.topLeft.isNotEmpty()) {
        if (band) {
            BandStrip(
                marks = badges.topLeft,
                metrics = metrics,
                top = true,
                paddingLeft = insets.side,
                paddingRight = insets.topRight,
                paddingTop = insets.top,
                paddingBottom = insets.edge * 3,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        } else {
            EdgeStack(badges.topLeft, metrics, StackPlacement.TopLeft, insets.side, insets.top, insets.topRight, rail = null)
        }
    }
    if (badges.leftRail.isNotEmpty()) {
        EdgeStack(
            badges.leftRail, metrics, StackPlacement.LeftRail, railInset, 0, 0,
            rail = if (flushRails) OverlayRail.Left else null,
        )
    }
    if (badges.rightRail.isNotEmpty()) {
        EdgeStack(
            badges.rightRail, metrics, StackPlacement.RightRail, railInset, 0, 0,
            rail = if (flushRails) OverlayRail.Right else null,
        )
    }
    if (badges.bottomLeft.isNotEmpty() || badges.bottomRight.isNotEmpty()) {
        if (band) {
            // One centred strip for the whole bottom edge, left corner first.
            val marks = remember(badges) { badges.bottomLeft + badges.bottomRight }
            BandStrip(
                marks = marks,
                metrics = metrics,
                top = false,
                paddingLeft = insets.side,
                paddingRight = insets.side,
                paddingTop = insets.edge * 3,
                paddingBottom = insets.bottom,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        } else {
            BottomRows(badges.bottomLeft, badges.bottomRight, metrics, insets)
        }
    }
}

private enum class StackPlacement { TopLeft, LeftRail, RightRail }

/**
 * One position's badges, stacked `stackGap` apart (web `BadgeStack`), placed
 * on the layer: the top-left corner between [start] and [end] from the left
 * and right edges, or a rail centred vertically, [start] from its edge and
 * at most 60% of the card wide.
 */
@Composable
private fun EdgeStack(
    badges: List<ResolvedBadge>,
    metrics: OverlayMetrics,
    placement: StackPlacement,
    start: Int,
    top: Int,
    end: Int,
    rail: OverlayRail?,
) {
    Layout(
        content = {
            for (badge in badges) key(badge.id) { OverlayBadge(badge, metrics, rail) }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val available = when (placement) {
            StackPlacement.TopLeft -> width - start - end
            else -> min((width * RAIL_MAX_WIDTH).toInt(), width - start)
        }.coerceAtLeast(0)
        val childConstraints = Constraints(maxWidth = available)
        val placeables = arrayOfNulls<Placeable>(measurables.size)
        var stackHeight = 0
        for (index in measurables.indices) {
            val placeable = measurables[index].measure(childConstraints)
            placeables[index] = placeable
            stackHeight += placeable.height + if (index > 0) metrics.stackGap else 0
        }
        layout(width, height) {
            var y = if (placement == StackPlacement.TopLeft) top else (height - stackHeight) / 2
            for (placeable in placeables) {
                placeable ?: continue
                val x = if (placement == StackPlacement.RightRail) width - start - placeable.width else start
                placeable.place(x, y)
                y += placeable.height + metrics.stackGap
            }
        }
    }
}

/**
 * The two bottom corners, one row per level from the bottom (web
 * `BottomRows`); see [OverlayLayoutMath.bottomRows] for the wrap rule.
 */
@Composable
private fun BottomRows(
    left: List<ResolvedBadge>,
    right: List<ResolvedBadge>,
    metrics: OverlayMetrics,
    insets: OverlayInsets,
) {
    Layout(
        content = {
            for (badge in left) key(badge.id) { OverlayBadge(badge, metrics) }
            for (badge in right) key(badge.id) { OverlayBadge(badge, metrics) }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val available = (width - insets.side * 2).coerceAtLeast(0)
        val childConstraints = Constraints(maxWidth = available)
        val leftCount = left.size
        val rightCount = right.size
        val placeables = Array(measurables.size) { measurables[it].measure(childConstraints) }
        val leftWidths = IntArray(leftCount) { placeables[it].width }
        val leftHeights = IntArray(leftCount) { placeables[it].height }
        val rightWidths = IntArray(rightCount) { placeables[leftCount + it].width }
        val rightHeights = IntArray(rightCount) { placeables[leftCount + it].height }
        val leftX = IntArray(leftCount)
        val leftY = IntArray(leftCount)
        val rightX = IntArray(rightCount)
        val rightY = IntArray(rightCount)
        val blockHeight = OverlayLayoutMath.bottomRows(
            leftWidths, leftHeights, rightWidths, rightHeights,
            available = available,
            gap = insets.edgeGap,
            stackGap = metrics.stackGap,
            leftX = leftX, leftY = leftY, rightX = rightX, rightY = rightY,
        )
        val top = height - insets.bottom - blockHeight
        layout(width, height) {
            for (i in 0 until leftCount) placeables[i].place(insets.side + leftX[i], top + leftY[i])
            for (i in 0 until rightCount) placeables[leftCount + i].place(insets.side + rightX[i], top + rightY[i])
        }
    }
}

/**
 * An edge's badges as one row of marks over a scrim, the full card width
 * (web band layout and `BandMarks`). The top strip starts its row at the
 * left padding; the bottom strip centres it. Marks are `stackGap` apart with
 * a 1px divider before every mark but the first; the row shows the marks
 * that fit whole and drops the rest, and only a first mark too wide on its
 * own truncates.
 */
@Composable
private fun BandStrip(
    marks: List<ResolvedBadge>,
    metrics: OverlayMetrics,
    top: Boolean,
    paddingLeft: Int,
    paddingRight: Int,
    paddingTop: Int,
    paddingBottom: Int,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val dividerWidth = with(density) { 1.dp.roundToPx() }.coerceAtLeast(1)
    // `0.9em` of the badge font size before the text ratio, as web sizes it.
    val dividerHeight = (metrics.fontPx * 0.9f).roundToInt()
    Layout(
        content = {
            marks.forEachIndexed { index, badge ->
                key(badge.id) {
                    if (index > 0) Box(Modifier.background(BandDividerColor))
                    OverlayBadge(badge, metrics)
                }
            }
        },
        modifier = modifier.drawWithCache {
            val brush = if (top) {
                Brush.verticalGradient(*BandScrimStops)
            } else {
                Brush.verticalGradient(*BandScrimStops, startY = size.height, endY = 0f)
            }
            onDrawBehind { drawRect(brush) }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val available = (width - paddingLeft - paddingRight).coerceAtLeast(0)
        val gap = metrics.stackGap
        // Children: mark 0, then a divider and a mark for each further mark.
        val markConstraints = Constraints(maxWidth = available)
        val markPlaceables = arrayOfNulls<Placeable>(marks.size)
        val markWidths = IntArray(marks.size)
        for (index in marks.indices) {
            val childIndex = if (index == 0) 0 else index * 2
            val placeable = measurables[childIndex].measure(markConstraints)
            markPlaceables[index] = placeable
            markWidths[index] = placeable.width
        }
        val count = OverlayLayoutMath.bandVisibleCount(markWidths, available, gap, dividerWidth)
        val dividerConstraints = Constraints.fixed(dividerWidth, dividerHeight)
        val dividers = arrayOfNulls<Placeable>(count)
        var rowWidth = 0
        var rowHeight = 0
        for (index in 0 until count) {
            val mark = markPlaceables[index]!!
            if (index > 0) {
                val divider = measurables[index * 2 - 1].measure(dividerConstraints)
                dividers[index] = divider
                rowWidth += gap + divider.width + gap
                rowHeight = max(rowHeight, divider.height)
            }
            rowWidth += mark.width
            rowHeight = max(rowHeight, mark.height)
        }
        val stripHeight = paddingTop + rowHeight + paddingBottom
        val startX = if (top) paddingLeft else paddingLeft + max(0, (available - rowWidth) / 2)
        layout(width, stripHeight) {
            var x = startX
            for (index in 0 until count) {
                dividers[index]?.let { divider ->
                    x += gap
                    divider.place(x, paddingTop + (rowHeight - divider.height) / 2)
                    x += divider.width + gap
                }
                val mark = markPlaceables[index]!!
                mark.place(x, paddingTop + (rowHeight - mark.height) / 2)
                x += mark.width
            }
        }
    }
}
