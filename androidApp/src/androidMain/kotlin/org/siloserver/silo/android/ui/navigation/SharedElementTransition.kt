package org.siloserver.silo.android.ui.navigation

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.SharedTransitionScope.ResizeMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Plumbing for Compose shared-element ("container transform") hero transitions
 * on phone/tablet. [AppNavigation] wraps its NavHost in a `SharedTransitionLayout`
 * and publishes the [SharedTransitionScope] here; each animated destination
 * publishes its own [AnimatedVisibilityScope]. A poster card (source) and the
 * detail backdrop hero (target) then opt into a shared-bounds morph — so the
 * thing you tapped visibly carries you into the detail page — without threading
 * either scope through every composable signature.
 *
 * Both locals are null wherever no `SharedTransitionLayout` / destination scope is
 * in play (previews, tests, screens not yet wired). Call sites MUST treat null as
 * "no shared transition" and fall back to a plain modifier — [heroSource] and
 * [heroTarget] do exactly that, so they are always safe to call.
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
 * Base prefix for a poster's hero key. Each poster PLACEMENT appends a unique
 * per-instance token (see [MediaCard]) so the same content id appearing in two
 * rows at once — Continue Watching + a genre row — never resolves to the same
 * shared key. Two live nodes under one key is exactly what made posters flicker
 * and draw into the transition overlay while scrolling; unique per-placement
 * keys make that collision impossible by construction (mirrors iOS's per-card
 * `zoomInstanceID`).
 */
fun heroSharedKeyPrefix(contentId: String): String = "hero-$contentId"

/**
 * Hand-off channel from the tapped poster to the detail hero target. The source
 * card writes its own unique placement key here on tap, and the detail backdrop
 * reads it to pair with THAT specific card rather than any duplicate of the same
 * content id in another row. Mirrors iOS `router.pendingZoomSourceID`.
 *
 * Snapshot-backed so the detail target recomposes if it changes; in practice it
 * is set once (synchronously, before navigation) and read once when the detail
 * screen composes. A navigation that does not originate from a poster (deep
 * link, search) leaves a stale or null key — harmless, because an unmatched key
 * simply renders in place with no morph.
 */
class HeroSourceHandoff {
    var pendingKey: String? by mutableStateOf(null)
    var pendingBrowseContentIds: List<String>? by mutableStateOf(null)
    var pendingBrowseOrigin: String? by mutableStateOf(null)
    // Artwork already known by the tapped card. The detail loading state uses
    // it immediately, starting the full backdrop request before metadata lands.
    var pendingArtworkUrl: String? by mutableStateOf(null)
    var pendingArtworkThumbhash: String? by mutableStateOf(null)
}

val LocalHeroSourceHandoff = compositionLocalOf<HeroSourceHandoff?> { null }

/**
 * Tags this node as the hero shared-element SOURCE under [key] (a unique
 * per-placement key). When the shared-transition scope and a destination
 * visibility scope are both available, this source and the detail target that
 * pairs with the same key animate their bounds into one another. Null [key] (no
 * hero) or a missing scope makes this a no-op, so it is always safe to call.
 *
 * `sharedBounds` renders into the scope overlay during the transition, so any
 * clipping/shaping MUST be applied AFTER this in the chain — callers do
 * `.heroSource(key).clip(shape)`.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.heroSource(key: String?): Modifier {
    if (key.isNullOrBlank()) return this
    val sharedScope = LocalSharedTransitionScope.current ?: return this
    val visibilityScope = LocalNavAnimatedVisibilityScope.current ?: return this
    return with(sharedScope) {
        this@heroSource.sharedBounds(
            sharedContentState = rememberSharedContentState(key = key),
            animatedVisibilityScope = visibilityScope,
            // Images respond well to being remeasured to the animated bounds, so the
            // artwork crop-fills the morphing rectangle (poster → wide hero) every
            // frame instead of scaling a single stable layout.
            resizeMode = ResizeMode.RemeasureToBounds,
        )
    }
}

/**
 * Tags this node as the hero shared-element TARGET (the detail backdrop). It
 * pairs with whichever poster placement the user last tapped, read from
 * [LocalHeroSourceHandoff]. With no recorded source (opened via deep link,
 * search, etc.) or a missing scope this is a no-op — the backdrop just renders
 * normally with no morph.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.heroTarget(): Modifier {
    val handoff = LocalHeroSourceHandoff.current
    // Consume the key once for THIS target, then clear the app-wide hand-off so
    // a later navigation that doesn't set a fresh key (back → search → open
    // another item, with the source screen still composed in the back stack)
    // can't reuse this stale key and morph the new hero from the wrong card.
    // `remember` keeps our captured key for this target's lifetime, so clearing
    // the hand-off doesn't disturb the current morph.
    val key = remember(handoff) { handoff?.pendingKey }
    LaunchedEffect(handoff) { handoff?.pendingKey = null }
    if (key.isNullOrBlank()) return this
    val sharedScope = LocalSharedTransitionScope.current ?: return this
    val visibilityScope = LocalNavAnimatedVisibilityScope.current ?: return this
    return with(sharedScope) {
        this@heroTarget.sharedBounds(
            sharedContentState = rememberSharedContentState(key = key),
            animatedVisibilityScope = visibilityScope,
            resizeMode = ResizeMode.RemeasureToBounds,
        )
    }
}
