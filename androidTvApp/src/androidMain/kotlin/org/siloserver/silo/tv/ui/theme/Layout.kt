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
 * Horizontal rail scroll behaviour, shared by every card carousel.
 *
 * Instead of nudging the row only once the focused card reaches the trailing
 * gutter (a different distance on every press, and nothing at all for the
 * first few cards), the focused card's leading edge is pinned at the row's
 * start padding — the tvOS / Netflix rail model. Every Right or Left is then
 * one uniform card-sized slide, the focused card always sits in the same
 * place on screen, and the eye never has to hunt for it. The row's ends still
 * clamp naturally, so the first cards and the last screenful move focus
 * across the screen without scrolling.
 *
 * Fast-out/slow-in at 480ms (tuned on the Shield): quick enough to start
 * that rapid presses feel connected, long enough to settle that a card-step
 * reads as a glide rather than a flick; a chain of presses retargets the
 * running animation from its current position, so it never stutters.
 */
@OptIn(ExperimentalFoundationApi::class)
fun tvRailBringIntoViewSpec(leadingPx: Float): BringIntoViewSpec = object : BringIntoViewSpec {
    override val scrollAnimationSpec: AnimationSpec<Float> = tween(
        durationMillis = 480,
        easing = FastOutSlowInEasing,
    )

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        // Content wider than the viewport can't be pinned; fall back to
        // minimal reveal so an oversized card is never scrolled off.
        if (size >= containerSize) return TvSmoothBringIntoViewSpec.calculateScrollDistance(offset, size, containerSize)
        val distance = offset - leadingPx
        // Ignore sub-pixel drift so an in-place focus change doesn't jitter.
        return if (kotlin.math.abs(distance) < 1f) 0f else distance
    }
}

/**
 * Wrap a `LazyRow` so its own horizontal bring-into-view uses
 * [tvRailBringIntoViewSpec] pinned at [leading] (the row's start content
 * padding). Vertical requests keep bubbling to the enclosing column's spec.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvRailScrollBehavior(leading: Dp, content: @Composable () -> Unit) {
    val leadingPx = with(LocalDensity.current) { leading.toPx() }
    val spec = remember(leadingPx) { tvRailBringIntoViewSpec(leadingPx) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
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
