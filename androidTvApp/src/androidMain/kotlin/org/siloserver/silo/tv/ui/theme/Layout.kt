package org.siloserver.silo.tv.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
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
