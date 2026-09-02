package org.siloserver.silo.common.player

import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi

/**
 * `Player.Listener` that runs a client-side preflight check every time the
 * track selection resolves. If the selected combination isn't actually
 * playable on this device (DV Profile 7 with a single-instance HEVC decoder,
 * TrueHD without a passthrough-capable sink, …), it fires [onUnsupported]
 * so the ViewModel can fall back to a transcoded stream without the player
 * crashing first.
 *
 * Also surfaces raw playback errors — we want the same fallback path for a
 * decoder-init failure as for a preflight-detected incompatibility.
 */
@UnstableApi
class PlaybackPreflightListener(
    private val detector: PlaybackCapabilityDetector,
    private val onUnsupported: (Playability) -> Unit,
    private val onError: (PlaybackException) -> Unit = {},
    /**
     * The video route the active plan promised. Read on every track change so
     * a replan that swaps a native Dolby Vision plan for a base-layer plan is
     * validated against the new promise, not the old one.
     */
    private val plannedRoute: () -> PlannedVideoRoute = { PlannedVideoRoute.Unspecified },
) : Player.Listener {

    private var lastSignaled: Playability? = null

    override fun onTracksChanged(tracks: Tracks) {
        val verdict = detector.evaluateTracks(tracks, plannedRoute())
        if (verdict != Playability.Supported && verdict != lastSignaled) {
            lastSignaled = verdict
            onUnsupported(verdict)
        } else if (verdict == Playability.Supported) {
            lastSignaled = null
        }
    }

    override fun onPlayerError(error: PlaybackException) {
        onError(error)
    }
}
