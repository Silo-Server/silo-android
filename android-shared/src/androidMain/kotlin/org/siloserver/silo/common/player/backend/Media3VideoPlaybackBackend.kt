package org.siloserver.silo.common.player.backend

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import org.siloserver.silo.common.player.AudioTrackManager
import org.siloserver.silo.common.player.SiloPlayerFactory
import org.siloserver.silo.common.player.VideoPlayerMediaSpec
import org.siloserver.silo.common.player.mountVideoMedia
import org.siloserver.silo.common.player.refreshMountedVideoMedia
import org.siloserver.silo.common.player.video.VideoPlayerTrackEntry
import org.siloserver.silo.common.player.video.VideoTrackSelectionCoordinator
import org.siloserver.silo.model.playback.AudioPassthroughCapabilities
import org.siloserver.silo.model.playback.HdrCapabilities
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SubtitleIdentity

@UnstableApi
class Media3VideoPlaybackBackend(
    private val playerFactory: SiloPlayerFactory,
    private val audioTrackManager: AudioTrackManager,
    private val trackSelectionCoordinator: VideoTrackSelectionCoordinator,
    override val player: Player,
) : VideoPlaybackBackend {
    override val kind: VideoPlaybackBackendKind = VideoPlaybackBackendKind.Media3
    override val capabilities: VideoBackendCapabilities = VideoBackendCapabilities.media3()

    private var mountedSpec: VideoPlayerMediaSpec? = null

    override val baseLayerDecoderMismatch: kotlinx.coroutines.flow.StateFlow<String?>
        get() = playerFactory.baseLayerDecoderMismatch

    override fun mount(
        spec: VideoPlayerMediaSpec,
        startPositionMs: Long,
        playWhenReady: Boolean,
    ) {
        mountedSpec = spec
        mountVideoMedia(
            player = player,
            playerFactory = playerFactory,
            spec = spec,
            startPositionMs = startPositionMs,
            playWhenReady = playWhenReady,
        )
    }

    override fun refresh(spec: VideoPlayerMediaSpec) {
        mountedSpec = spec
        refreshMountedVideoMedia(
            player = player,
            playerFactory = playerFactory,
            spec = spec,
        )
    }

    override fun selectSubtitle(track: VideoPlayerTrackEntry?): Boolean {
        if (track?.subtitle != null && mountedSpec == null) return false
        return trackSelectionCoordinator.selectSubtitle(
            player = player,
            playerFactory = playerFactory,
            mediaSpec = mountedSpec,
            selectedTrack = track,
        )
    }

    override fun selectMountedSubtitle(
        identity: SubtitleIdentity,
    ): Boolean = trackSelectionCoordinator.selectMountedSubtitle(
        player = player,
        identity = identity,
    )

    override fun selectMountedSubtitle(
        subtitles: List<PlayerSubtitleInfo>,
        selectedIndex: Int,
    ): Boolean = trackSelectionCoordinator.selectMountedSubtitle(
        player = player,
        subtitles = subtitles,
        selectedIndex = selectedIndex,
    )

    override fun selectAudioTrack(track: VideoPlayerTrackEntry) {
        trackSelectionCoordinator.selectAudioTrack(
            player = player,
            audioTrackManager = audioTrackManager,
            selectedTrack = track,
        )
    }

    override fun applyTrackSelection(
        audioCaps: AudioPassthroughCapabilities,
        displayHdr: HdrCapabilities,
        preferredAudioLanguage: String?,
        preferredTextLanguage: String?,
        hdrEnabled: Boolean,
    ): Boolean =
        playerFactory.applyTrackSelectionPresets(
            player = player,
            audioCaps = audioCaps,
            displayHdr = displayHdr,
            preferredAudioLanguage = preferredAudioLanguage,
            preferredTextLanguage = preferredTextLanguage,
            hdrEnabled = hdrEnabled,
        )

    override fun release() {
        playerFactory.releasePlayer(player)
    }

}
