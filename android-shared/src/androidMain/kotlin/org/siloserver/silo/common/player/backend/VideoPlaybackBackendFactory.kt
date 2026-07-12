package org.siloserver.silo.common.player.backend

import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import org.siloserver.silo.common.player.AudioTrackManager
import org.siloserver.silo.common.player.SiloPlayerFactory
import org.siloserver.silo.common.player.SubtitleManager
import org.siloserver.silo.common.player.video.VideoTrackSelectionCoordinator

class VideoPlaybackBackendFactory(
    private val playerFactory: SiloPlayerFactory,
    private val audioTrackManager: AudioTrackManager,
    private val subtitleManager: SubtitleManager,
) {
    @OptIn(UnstableApi::class)
    fun create(
        player: Player,
        request: VideoPlaybackBackendRequest = VideoPlaybackBackendRequest(),
    ): VideoPlaybackBackend {
        check(VideoPlaybackBackendSelector.select(request) == VideoPlaybackBackendKind.Media3)
        return Media3VideoPlaybackBackend(
            playerFactory = playerFactory,
            audioTrackManager = audioTrackManager,
            trackSelectionCoordinator = VideoTrackSelectionCoordinator(subtitleManager),
            player = player,
        )
    }
}
