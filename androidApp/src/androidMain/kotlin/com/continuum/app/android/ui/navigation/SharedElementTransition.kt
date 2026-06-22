package com.continuum.app.android.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SharedTransitionScope.ResizeMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier

/**
 * Plumbing for Compose shared-element ("container transform") hero transitions
 * on phone/tablet. [AppNavigation] wraps its NavHost in a `SharedTransitionLayout`
 * and publishes the [SharedTransitionScope] here; each animated destination
 * publishes its own [AnimatedVisibilityScope]. A poster card (source) and the
 * detail backdrop hero (target) then opt into a shared-bounds morph keyed on the
 * content id — so the thing you tapped visibly carries you into the detail page —
 * without threading either scope through every composable signature.
 *
 * Both locals are null wherever no `SharedTransitionLayout` / destination scope is
 * in play (previews, tests, screens not yet wired). Call sites MUST treat null as
 * "no shared transition" and fall back to a plain modifier — [heroSharedBounds]
 * does exactly that, so it is always safe to call.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/**
 * The [AnimatedVisibilityScope] of the current NavHost destination, published by
 * the destination's `composable { }` lambda — its receiver is an
 * `AnimatedContentScope`, which is an [AnimatedVisibilityScope].
 */
val LocalNavAnimatedVisibilityScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/**
 * Shared-element key for an item's hero artwork. The list poster and the detail
 * backdrop for the same content id MUST resolve to the same key to morph into one
 * another, so both ends route through this single helper.
 */
fun heroSharedKey(contentId: String): String = "hero-$contentId"

/**
 * Tags this node as the hero shared element for [contentId]. When both the
 * shared-transition scope and a destination visibility scope are available, a
 * source (poster) and target (detail backdrop) carrying the same id animate their
 * bounds into one another, crossfading the differing artwork. Otherwise this is a
 * no-op, so it is safe to call unconditionally.
 *
 * `sharedBounds` renders into the scope overlay during the transition, so any
 * clipping/shaping MUST be applied AFTER this in the chain —
 * callers do `.heroSharedBounds(id).clip(shape)`.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.heroSharedBounds(contentId: String?): Modifier {
    if (contentId.isNullOrBlank()) return this
    val sharedScope = LocalSharedTransitionScope.current ?: return this
    val visibilityScope = LocalNavAnimatedVisibilityScope.current ?: return this
    return with(sharedScope) {
        this@heroSharedBounds.sharedBounds(
            sharedContentState = rememberSharedContentState(key = heroSharedKey(contentId)),
            animatedVisibilityScope = visibilityScope,
            // Images respond well to being remeasured to the animated bounds, so the
            // artwork crop-fills the morphing rectangle (poster → wide hero) every
            // frame instead of scaling a single stable layout.
            resizeMode = ResizeMode.RemeasureToBounds,
        )
    }
}
