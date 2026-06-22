package com.continuum.app.common.player.backend

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.continuum.app.common.player.AudioTrackManager
import com.continuum.app.common.player.ContinuumPlayerFactory
import com.continuum.app.common.player.VideoPlayerMediaSpec
import com.continuum.app.common.player.mountVideoMedia
import com.continuum.app.common.player.mpv.MpvPlayer
import com.continuum.app.common.player.refreshMountedVideoMedia
import com.continuum.app.common.player.video.VideoPlayerTrackEntry
import com.continuum.app.common.player.video.VideoTrackSelectionCoordinator
import com.continuum.app.model.playback.AudioPassthroughCapabilities
import com.continuum.app.model.playback.HdrCapabilities
import com.continuum.app.model.playback.PlayerSubtitleInfo

@UnstableApi
class MpvVideoPlaybackBackend(
    private val playerFactory: ContinuumPlayerFactory,
    private val audioTrackManager: AudioTrackManager,
    private val trackSelectionCoordinator: VideoTrackSelectionCoordinator,
    override val player: Player,
) : VideoPlaybackBackend {
    override val kind: VideoPlaybackBackendKind = VideoPlaybackBackendKind.Mpv
    override val capabilities: VideoBackendCapabilities = VideoBackendCapabilities.mpv()

    private var mountedSpec: VideoPlayerMediaSpec? = null

    override fun mount(
        spec: VideoPlayerMediaSpec,
        startPositionMs: Long,
        playWhenReady: Boolean,
    ) {
        mountedSpec = spec
        (player as? MpvPlayer)?.setAudioPassthroughCodecs(spec.audioPassthroughCodecs)
        mountVideoMedia(
            player = player,
            playerFactory = playerFactory,
            spec = withoutEagerSubtitles(spec),
            startPositionMs = startPositionMs,
            playWhenReady = playWhenReady,
        )
    }

    override fun refresh(spec: VideoPlayerMediaSpec) {
        mountedSpec = spec
        (player as? MpvPlayer)?.setAudioPassthroughCodecs(spec.audioPassthroughCodecs)
        refreshMountedVideoMedia(
            player = player,
            playerFactory = playerFactory,
            spec = spec,
        )
    }

    override fun selectSubtitle(track: VideoPlayerTrackEntry?): Boolean =
        trackSelectionCoordinator.selectSubtitle(
            player = player,
            playerFactory = playerFactory,
            mediaSpec = requireMediaSpecForExternalSubtitle(track),
            selectedTrack = track,
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
    ) {
        playerFactory.applyTrackSelectionPresets(
            player = player,
            audioCaps = audioCaps,
            displayHdr = displayHdr,
            preferredAudioLanguage = preferredAudioLanguage,
            preferredTextLanguage = preferredTextLanguage,
            hdrEnabled = hdrEnabled,
        )
    }

    override fun release() {
        player.release()
    }

    private fun requireMediaSpecForExternalSubtitle(track: VideoPlayerTrackEntry?): VideoPlayerMediaSpec {
        val spec = mountedSpec
        if (spec != null) return spec
        if (track?.subtitle == null) {
            return VideoPlayerMediaSpec(
                streamUrl = "",
                playMethod = com.continuum.app.model.playback.PlayMethod.DIRECT,
                serverUrl = "",
            )
        }
        error("Cannot select an external subtitle before video media has been mounted.")
    }

    private fun withoutEagerSubtitles(spec: VideoPlayerMediaSpec): VideoPlayerMediaSpec =
        if (spec.subtitles.isEmpty()) spec else spec.copy(subtitles = emptyList())
}
