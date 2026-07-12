package org.siloserver.silo.common.player.backend

object VideoPlaybackBackendSelector {
    /** Release A invariant: every in-app video route executes on Media3. */
    fun select(@Suppress("UNUSED_PARAMETER") request: VideoPlaybackBackendRequest): VideoPlaybackBackendKind =
        VideoPlaybackBackendKind.Media3
}
