package org.siloserver.silo.common.player.backend

import org.siloserver.silo.common.player.route.PlaybackRoute

data class VideoBackendCapabilities(
    val backendKind: VideoPlaybackBackendKind,
    val route: PlaybackRoute,
    val subtitleRendering: SubtitleRendering,
    val supportsHardContainers: Boolean,
    val displayName: String,
) {
    companion object {
        fun media3(
            route: PlaybackRoute = PlaybackRoute.SiloPlayer,
        ): VideoBackendCapabilities = VideoBackendCapabilities(
            backendKind = VideoPlaybackBackendKind.Media3,
            route = route,
            subtitleRendering = SubtitleRendering.Media3Text,
            supportsHardContainers = false,
            displayName = "SiloPlayer",
        )
    }
}
