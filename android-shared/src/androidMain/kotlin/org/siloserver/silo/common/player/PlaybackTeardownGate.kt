package org.siloserver.silo.common.player

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serialises one player screen's teardown of the process-scoped
 * [PlaybackSessionLifecycle] so it happens exactly once.
 *
 * A screen has several exit routes — an ordered stop awaited before navigation,
 * an async Back/remote-stop, and a `ViewModel.onCleared()` fallback — and more
 * than one can fire for the same exit. That is not merely redundant: every
 * [PlaybackSessionLifecycle.stop] bumps `stopEpoch`, and a stop issued *after*
 * the next screen has captured its ownership epoch causes that screen's
 * adoption to be rejected ("Playback start was superseded"). On Android TV the
 * deferred `onCleared()` stop landed exactly there and broke auto-advance on
 * every episode transition.
 *
 * The gate is deliberately one-shot per screen. Once any route has taken
 * ownership of teardown, the others must not touch the singleton lifecycle.
 *
 * Residual, accepted: if the underlying stop *and* its one handoff both fail
 * unexpectedly, the claim stays taken and teardown is incomplete, leaving the
 * server session to expire on its own timeout. That is the pre-existing
 * behaviour for a failed stop and is not an adoption hazard — every route here
 * names the session it is ending, and once the next session has been adopted a
 * late stop for the old id returns at the ownership guard *before* touching
 * `stopEpoch`, so it cannot supersede anyone.
 */
class PlaybackTeardownGate(private val lifecycle: PlaybackSessionLifecycle) {

    private val claimed = AtomicBoolean(false)

    /** True once some route has taken ownership of this screen's teardown. */
    val isClaimed: Boolean get() = claimed.get()

    /**
     * Ordered teardown, awaited before navigating to the next item.
     *
     * If the stop fails or is cancelled the claim is *not* released: releasing
     * it would leave teardown unowned whenever [stopDetached] has already run
     * and skipped, because a flag reset neither notifies nor reschedules it.
     * Ownership is handed to the lifecycle-owned tracked job instead, which
     * outlives the screen and which a later start awaits through
     * [PlaybackSessionLifecycle.acquireOwnershipEpoch].
     */
    suspend fun stopOrdered(expectedSessionId: String?): Boolean {
        if (!claimed.compareAndSet(false, true)) return false
        try {
            return lifecycle.stop(expectedSessionId = expectedSessionId)
        } catch (t: Throwable) {
            lifecycle.stopAsync(expectedSessionId = expectedSessionId)
            throw t
        }
    }

    /**
     * Detached teardown: the Back/remote-stop route, and the `onCleared()`
     * fallback for a screen that went away without any exit route running.
     * Does nothing once teardown is already owned.
     *
     * Both go through [PlaybackSessionLifecycle.stopAsync] rather than a direct
     * suspend stop. Nothing awaits either caller — `onCleared`'s settlement
     * callback even wraps its body in `runCatching` — so a direct stop that
     * threw would be swallowed with the claim already consumed and no owner
     * left to retry. The tracked job cannot be abandoned that way, and it has
     * the side benefit that a later start awaits it through
     * [PlaybackSessionLifecycle.acquireOwnershipEpoch].
     */
    fun stopDetached(expectedSessionId: String?) {
        if (claimed.compareAndSet(false, true)) {
            lifecycle.stopAsync(expectedSessionId = expectedSessionId)
        }
    }
}
