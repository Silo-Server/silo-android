package org.siloserver.silo.common.player.backend

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import org.siloserver.silo.common.player.VideoPlayerMediaSpec
import org.siloserver.silo.common.player.video.VideoPlayerTrackEntry
import org.siloserver.silo.model.playback.AudioPassthroughCapabilities
import org.siloserver.silo.model.playback.HdrCapabilities
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SubtitleIdentity

@UnstableApi
interface VideoPlaybackBackend {
    val kind: VideoPlaybackBackendKind
    val capabilities: VideoBackendCapabilities
    val player: Player


    fun mount(
        spec: VideoPlayerMediaSpec,
        startPositionMs: Long = spec.startPositionMs,
        playWhenReady: Boolean = true,
    )

    fun refresh(spec: VideoPlayerMediaSpec)

    /**
     * Emits the decoder name when the mounted plan promised a Dolby Vision
     * Profile 8 base-layer route and the engine opened a decoder that cannot
     * honour it. Null while the promise holds or when the backend has no such
     * check. Reset on every mount.
     */
    val baseLayerDecoderMismatch: kotlinx.coroutines.flow.StateFlow<String?>
        get() = kotlinx.coroutines.flow.MutableStateFlow(null)

    fun selectSubtitle(track: VideoPlayerTrackEntry?): Boolean

    fun selectMountedSubtitle(
        identity: SubtitleIdentity,
    ): Boolean

    /** Compatibility bridge until every platform adapter publishes typed identity. */
    fun selectMountedSubtitle(
        subtitles: List<PlayerSubtitleInfo>,
        selectedIndex: Int,
    ): Boolean

    fun selectAudioTrack(track: VideoPlayerTrackEntry)

    /** Returns whether presets were actually assigned; false = skipped. */
    fun applyTrackSelection(
        audioCaps: AudioPassthroughCapabilities,
        displayHdr: HdrCapabilities = HdrCapabilities(),
        preferredAudioLanguage: String? = null,
        preferredTextLanguage: String? = null,
        hdrEnabled: Boolean = true,
    ): Boolean

    fun release()
}
