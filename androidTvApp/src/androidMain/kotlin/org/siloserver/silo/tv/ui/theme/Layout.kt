package org.siloserver.silo.tv.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.ui.unit.Dp

/** Content now lives beside a persistent rail, so page padding is local only. */
@Composable
fun tvPageStartPadding(
    expandedGap: Dp = Spacing.md,
): Dp = expandedGap

/**
 * `BringIntoViewSpec` tuned for 10-ft D-pad navigation. Compose's default
 * spec uses an under-damped spring that settles quickly. On TV that reads
 * as a snap, especially between tall row items where the travel distance
 * is large. tvOS paces its detail focus scrolls with
 * `easeInOut(duration: 0.45)` (`TVDetailFocusScroll.swift`) — the symmetric
 * ease-in matters more than the duration: `FastOutSlowIn` launches at full
 * velocity on frame one and reads as a snap on long hero→rail jumps, while
 * ease-in-out accelerates gently from rest. Slightly longer than Apple's
 * 450ms since this spec also covers the longest travel distances. The
 * scroll distance keeps a modest viewport gutter around focused content so
 * TV rows do not settle half clipped against the screen edge.
 */
@OptIn(ExperimentalFoundationApi::class)
val TvSmoothBringIntoViewSpec: BringIntoViewSpec = object : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> = tween(
        durationMillis = 620,
        easing = EaseInOut,
    )

    override fun calculateScrollDistance(
        offset: Float,
        size: Float,
        containerSize: Float,
    ): Float {
        val leadingGutter = containerSize * 0.12f
        val trailingGutter = containerSize * 0.22f
        val visibleStart = leadingGutter
        val visibleEnd = containerSize - trailingGutter
        return when {
            offset < visibleStart -> offset - visibleStart
            offset + size > visibleEnd -> offset + size - visibleEnd
            else -> 0f
        }
    }
}

/**
 * [TvSmoothBringIntoViewSpec] for a vertical grid that scrolls under the top
 * bar: the leading gutter is at least [topInset] (the grid's top content
 * padding), so a row revealed by scrolling back UP parks below the bar instead
 * of at 12% of the viewport — which on a 1080p canvas is ~65dp, under the
 * 94dp bar, leaving the first row's posters cut off at the top. Scrolling
 * down is unchanged (trailing gutter as before).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberTvGridBringIntoViewSpec(topInset: Dp): BringIntoViewSpec {
    val topInsetPx = with(LocalDensity.current) { topInset.toPx() }
    return remember(topInsetPx) {
        object : BringIntoViewSpec {
            override val scrollAnimationSpec: AnimationSpec<Float> = TvSmoothBringIntoViewSpec.scrollAnimationSpec

            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val leadingGutter = maxOf(containerSize * 0.12f, topInsetPx)
                val trailingGutter = containerSize * 0.22f
                val visibleStart = leadingGutter
                val visibleEnd = containerSize - trailingGutter
                return when {
                    offset < visibleStart -> offset - visibleStart
                    offset + size > visibleEnd -> offset + size - visibleEnd
                    else -> 0f
                }
            }
        }
    }
}

/**
 * Horizontal rail scroll behaviour, shared by every card carousel.
 *
 * The focused card is PINNED: its leading edge slides to the row's start
 * padding — the tvOS / Netflix rail model — so every Right or Left is one
 * uniform card-sized glide and the focused card always sits in the same place
 * on screen. Row ends still clamp naturally.
 *
 * Two pieces, deliberately split:
 *
 * - [Modifier.tvRailPinOnFocus] performs the pin as a ONE-SHOT clamped
 *   `animateScrollBy` when a card gains focus (mirroring the detail page's
 *   section anchors). It is the ONLY horizontal scroll a focus change
 *   triggers, and it starts on the focus frame itself, so the highlight and
 *   the glide begin together and read as one motion.
 * - [TvRailScrollBehavior] tells the LazyRow's automatic bring-into-view to
 *   stay out of it horizontally (distance 0 — the request is satisfied at
 *   once, nothing loops). Before this, the auto request started a "minimal
 *   reveal" animation on the focus frame and the pin cancelled and restarted
 *   it a frame later from zero velocity: the highlight visibly landed on the
 *   right, paused, then the row re-launched leftward — the "jumpy" rail.
 *   Vertical requests keep bubbling to the enclosing column's own spec.
 *
 * The pin must NOT be expressed as the BringIntoViewSpec's scroll distance:
 * Compose keeps re-launching the bring-into-view animation on every layout
 * pass while the spec still reports a non-zero distance, and a pinned position
 * is unreachable whenever the row is clamped at either end — so during any
 * concurrent animation (a vertical row scroll re-lays the rail out each frame)
 * it spun a new scroll job per frame. Measured on the Shield as p90 121ms and
 * near-frozen vertical navigation.
 *
 * Fast-out/slow-in at 480ms (tuned on the Shield): quick to start so rapid
 * presses feel connected, long enough to settle that a card-step reads as a
 * glide; a chain of presses retargets the running animation from its current
 * position, so it never stutters.
 */
val TvRailScrollSpec: AnimationSpec<Float> = tween(
    durationMillis = 480,
    easing = FastOutSlowInEasing,
)

@OptIn(ExperimentalFoundationApi::class)
private val TvRailBringIntoViewSpec: BringIntoViewSpec = object : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> = TvRailScrollSpec

    // Never scroll horizontally on the row's own account: the pin
    // (tvRailPinOnFocus) owns every focus-driven horizontal move, and a
    // second animation racing it is exactly the hitch this avoids.
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

/**
 * Wrap a `LazyRow` so its automatic horizontal bring-into-view defers to the
 * pin. Vertical requests keep bubbling to the enclosing column's spec.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvRailScrollBehavior(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalBringIntoViewSpec provides TvRailBringIntoViewSpec, content = content)
}

/**
 * Slide the item at [index] so its leading edge sits [leadingPx] from the
 * viewport start, clamped by the list's own bounds.
 *
 * `LazyListItemInfo.offset` is measured from the CONTENT start (after the
 * start padding), so the target in item coordinates is
 * `leadingPx + viewportStartOffset` — zero when [leadingPx] equals the row's
 * start padding, which is how every rail calls it. (Subtracting [leadingPx]
 * from the raw offset, as this once did, parked composed cards a full padding
 * width past the pin while a deep-restored card landed on it.)
 *
 * A card that is not yet in `visibleItemsInfo` — the neighbour just past the
 * viewport edge that D-pad focus reaches through beyond-bounds layout — is
 * extrapolated from the nearest visible card, since rail cards share a width
 * and spacing; the list clamps the result. Only a genuinely far target (deep
 * restore) falls back to `animateScrollToItem`.
 */
suspend fun LazyListState.tvRailPinItem(index: Int, leadingPx: Float) {
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    val target = leadingPx + info.viewportStartOffset
    val item = visible.firstOrNull { it.index == index }
    val distance = when {
        item != null -> item.offset - target
        visible.isEmpty() -> null
        index > visible.last().index && index - visible.last().index <= NEAR_EDGE_ITEMS -> {
            val last = visible.last()
            val stride = last.size + info.mainAxisItemSpacing
            last.offset + (index - last.index) * stride - target
        }
        index < visible.first().index && visible.first().index - index <= NEAR_EDGE_ITEMS -> {
            val first = visible.first()
            val stride = first.size + info.mainAxisItemSpacing
            first.offset - (first.index - index) * stride - target
        }
        else -> null
    }
    if (distance != null) {
        if (abs(distance) >= 1f) animateScrollBy(distance, TvRailScrollSpec)
    } else {
        // Far away (deep restore): the stock jump lands it at the content
        // start, i.e. exactly at the start padding.
        animateScrollToItem(index)
    }
}

/** How many cards past the viewport edge [tvRailPinItem] extrapolates from a visible neighbour. */
private const val NEAR_EDGE_ITEMS = 2

/**
 * Pin the item at [index] (see [tvRailPinItem]) whenever it gains focus.
 * [leading] is the row's start content padding.
 */
@Composable
fun Modifier.tvRailPinOnFocus(state: LazyListState, index: Int, leading: Dp): Modifier {
    val leadingPx = with(LocalDensity.current) { leading.toPx() }
    val scope = rememberCoroutineScope()
    return onFocusChanged { focusState ->
        if (focusState.isFocused) scope.launch { state.tvRailPinItem(index, leadingPx) }
    }
}

@Composable
fun tvPageContentPadding(
    top: Dp = Spacing.xxl,
    bottom: Dp = Spacing.xxxl,
    end: Dp = Spacing.safeArea,
    expandedGap: Dp = Spacing.md,
): PaddingValues = PaddingValues(
    start = tvPageStartPadding(
        expandedGap = expandedGap,
    ),
    top = top,
    end = end,
    bottom = bottom,
)
