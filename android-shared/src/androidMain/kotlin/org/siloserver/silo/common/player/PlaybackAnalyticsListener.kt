package org.siloserver.silo.common.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.siloserver.silo.common.diagnostics.DiagnosticsCaptureDetailState
import org.siloserver.silo.common.diagnostics.DiagnosticsPlaybackLogger
import org.siloserver.silo.common.diagnostics.DiagnosticsPlayerState
import org.siloserver.silo.common.diagnostics.DiagnosticsStatsCadence

/**
 * `AnalyticsListener` that logs the handful of signals we actually triage
 * playback issues with — decoder init names, dropped-frame counts, audio
 * underruns, load errors, seeks, player-state and play/pause changes, and
 * bandwidth estimates — and re-emits them to an
 * in-process [SharedFlow] so the debug overlay (or a future server-side
 * telemetry POST) can subscribe without another listener registration.
 *
 * Output goes through the local, redacting [DiagnosticsPlaybackLogger]; there
 * is no network I/O. The in-process flow remains available to debug UI.
 */
@UnstableApi
class PlaybackAnalyticsListener : AnalyticsListener {

    companion object {
        private const val TAG = "Media3Analytics"
    }

    sealed class Event {
        data class VideoDecoderInitialized(
            val decoderName: String,
            val initializationDurationMs: Long? = null,
        ) : Event()
        data class AudioDecoderInitialized(val decoderName: String) : Event()
        data class VideoFormatChanged(val format: Format) : Event()
        data class VideoOutputFormatChanged(val format: Format, val decoderMimeType: String?) : Event()
        data class AudioFormatChanged(val format: Format) : Event()
        data class DroppedFrames(val count: Int, val elapsedMs: Long) : Event()
        object AudioUnderrun : Event()
        data class LoadError(val throwable: Throwable) : Event()
        data class PlayerError(val error: PlaybackException) : Event()
        data class BandwidthEstimate(val bitrateBps: Long) : Event()
        data class TrackSnapshot(val description: String) : Event()
        data class PlaybackStateChanged(
            val state: Int,
            val realtimeMs: Long,
            val totalBufferedDurationMs: Long,
            val playWhenReady: Boolean,
        ) : Event()
        data class FirstFrameRendered(val realtimeMs: Long) : Event()
        data class SeekStarted(val realtimeMs: Long) : Event()
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 32)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    // Held as state rather than sent through [events]: that flow has no
    // replay and drops on overflow, and a compatible track switch reuses the
    // AudioTrack without a second callback, so a missed event would leave the
    // UI on the server plan's guess for the rest of playback.
    private val _audioOutputPath = MutableStateFlow<AudioOutputPath?>(null)

    /**
     * How the current AudioTrack actually carries audio, as Media3 configured
     * it from the live sink capabilities. Null until a track is created, and
     * again once audio is disabled or the player is released.
     */
    val audioOutputPath: StateFlow<AudioOutputPath?> = _audioOutputPath.asStateFlow()
    private var diagnosticsStats = PlayerStatsSnapshot()
    private val diagnosticsCadence = DiagnosticsStatsCadence()
    private var playWhenReady = true

    override fun onPlayWhenReadyChanged(
        eventTime: AnalyticsListener.EventTime,
        playWhenReady: Boolean,
        reason: Int,
    ) {
        this.playWhenReady = playWhenReady
        DiagnosticsPlaybackLogger.playWhenReadyChanged(playWhenReady, playWhenReadyReasonName(reason))
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        diagnosticsPlayerState(state)?.let(DiagnosticsPlaybackLogger::playerState)
        emit(Event.PlaybackStateChanged(state, eventTime.realtimeMs, eventTime.totalBufferedDurationMs, playWhenReady))
        if (state == Player.STATE_ENDED || state == Player.STATE_IDLE) {
            val finished = finishPlayerStats(diagnosticsStats, DiagnosticsCaptureDetailState.isEnabled())
            finished.finalSnapshot?.let(DiagnosticsPlaybackLogger::finalStats)
            diagnosticsStats = finished.next
        }
    }

    override fun onPositionDiscontinuity(
        eventTime: AnalyticsListener.EventTime,
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK) {
            DiagnosticsPlaybackLogger.seekStarted(newPosition.positionMs)
            emit(Event.SeekStarted(eventTime.realtimeMs))
        }
    }

    override fun onRenderedFirstFrame(
        eventTime: AnalyticsListener.EventTime,
        output: Any,
        renderTimeMs: Long,
    ) {
        emit(Event.FirstFrameRendered(eventTime.realtimeMs))
    }

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        DiagnosticsPlaybackLogger.videoDecoderInitialized(decoderName)
        emit(Event.VideoDecoderInitialized(decoderName, initializationDurationMs))
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        DiagnosticsPlaybackLogger.audioDecoderInitialized(decoderName)
        emit(Event.AudioDecoderInitialized(decoderName))
    }

    override fun onAudioTrackInitialized(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioSink.AudioTrackConfig,
    ) {
        _audioOutputPath.value = audioOutputPathOf(audioTrackConfig)
    }

    // onAudioTrackReleased is deliberately ignored: DefaultAudioSink releases
    // the old track asynchronously, so the release can land after the
    // replacement track's initialization and would clear a current value.
    override fun onAudioDisabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) {
        _audioOutputPath.value = null
    }

    override fun onPlayerReleased(eventTime: AnalyticsListener.EventTime) {
        _audioOutputPath.value = null
    }

    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
    ) {
        DiagnosticsPlaybackLogger.videoFormatChanged(
            format = format.sampleMimeType,
            width = format.width,
            height = format.height,
            hdrMode = null,
        )
        emit(Event.VideoFormatChanged(format))
    }

    fun onVideoOutputFormatChanged(format: Format, decoderMimeType: String?) {
        emit(Event.VideoOutputFormatChanged(format, decoderMimeType))
    }

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
    ) {
        DiagnosticsPlaybackLogger.audioFormatChanged(format.sampleMimeType)
        emit(Event.AudioFormatChanged(format))
    }

    override fun onTracksChanged(
        eventTime: AnalyticsListener.EventTime,
        tracks: Tracks,
    ) {
        val description = tracks.describeForLog()
        Log.i(TAG, "Track snapshot: $description")
        DiagnosticsPlaybackLogger.tracksChanged()
        emit(Event.TrackSnapshot(description))
    }

    override fun onDroppedVideoFrames(
        eventTime: AnalyticsListener.EventTime,
        droppedFrames: Int,
        elapsedRealtimeMs: Long,
    ) {
        if (droppedFrames > 0) {
            DiagnosticsPlaybackLogger.droppedFrames(droppedFrames)
        }
        emit(Event.DroppedFrames(droppedFrames, elapsedRealtimeMs))
    }

    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long,
    ) {
        DiagnosticsPlaybackLogger.audioUnderrun()
        emit(Event.AudioUnderrun)
    }

    override fun onPlayerError(
        eventTime: AnalyticsListener.EventTime,
        error: PlaybackException,
    ) {
        Log.e(TAG, "Player error ${error.errorCodeName}: ${error.message}", error)
        DiagnosticsPlaybackLogger.playerError(error.toDiagnosticsDetail())
        emit(Event.PlayerError(error))
    }

    override fun onLoadError(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: java.io.IOException,
        wasCanceled: Boolean,
    ) {
        DiagnosticsPlaybackLogger.loadError(mediaLoadErrorDiagnosticsDetail(mediaLoadData, error, wasCanceled))
        emit(Event.LoadError(error))
    }

    override fun onBandwidthEstimate(
        eventTime: AnalyticsListener.EventTime,
        totalLoadTimeMs: Int,
        totalBytesLoaded: Long,
        bitrateEstimate: Long,
    ) {
        emit(Event.BandwidthEstimate(bitrateEstimate))
    }

    @Synchronized
    private fun emit(event: Event) {
        _events.tryEmit(event)
        diagnosticsStats = reducePlayerStats(diagnosticsStats, event)
        if (diagnosticsCadence.shouldEmit(DiagnosticsCaptureDetailState.isEnabled(), android.os.SystemClock.elapsedRealtime())) {
            DiagnosticsPlaybackLogger.statsSnapshot(diagnosticsStats)
        }
    }
}

/** The three ways an AudioTrack can carry audio; see [PlaybackAnalyticsListener.audioOutputPath]. */
enum class AudioOutputPath {
    /** Decoded in the app and processed as PCM, so audio delay applies. */
    Pcm,

    /** Encoded bitstream handed to the HDMI/eARC sink untouched. */
    Passthrough,

    /** Encoded audio decoded by the platform's DSP; app processing is bypassed. */
    Offload,
}

@UnstableApi
private fun audioOutputPathOf(config: AudioSink.AudioTrackConfig): AudioOutputPath = when {
    config.offload -> AudioOutputPath.Offload
    Util.isEncodingLinearPcm(config.encoding) -> AudioOutputPath.Pcm
    else -> AudioOutputPath.Passthrough
}

/**
 * Whether the audio delay processor can act on the current output. The sink's
 * observed path wins; before the first AudioTrack exists, fall back to the
 * server plan's passthrough claim.
 */
fun AudioOutputPath?.allowsAudioDelay(planClaimsPassthrough: Boolean?): Boolean =
    if (this != null) this == AudioOutputPath.Pcm else planClaimsPassthrough != true

private fun diagnosticsPlayerState(state: Int): DiagnosticsPlayerState? = when (state) {
    Player.STATE_IDLE -> DiagnosticsPlayerState.Idle
    Player.STATE_BUFFERING -> DiagnosticsPlayerState.Buffering
    Player.STATE_READY -> DiagnosticsPlayerState.Ready
    Player.STATE_ENDED -> DiagnosticsPlayerState.Ended
    else -> null
}

private fun playWhenReadyReasonName(reason: Int): String = when (reason) {
    Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST -> "user_request"
    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> "audio_focus_loss"
    Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> "audio_becoming_noisy"
    Player.PLAY_WHEN_READY_CHANGE_REASON_REMOTE -> "remote"
    Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM -> "end_of_media_item"
    Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG -> "suppressed_too_long"
    else -> "other"
}

private fun Tracks.describeForLog(): String {
    if (groups.isEmpty()) return "[]"
    return groups.mapIndexed { groupIndex, group ->
        val tracks = (0 until group.length).joinToString(prefix = "[", postfix = "]") { trackIndex ->
            val format = group.getTrackFormat(trackIndex)
            val selected = group.isTrackSelected(trackIndex)
            val supported = group.isTrackSupported(trackIndex)
            val sampleMimeType = format.sampleMimeType ?: "?"
            val codecs = format.codecs ?: "?"
            val language = format.language ?: "?"
            val label = format.label ?: "?"
            "$trackIndex{selected=$selected supported=$supported " +
                "sampleMimeType=$sampleMimeType codecs=$codecs language=$language label=$label}"
        }
        "$groupIndex:${group.type.trackTypeName()}$tracks"
    }.joinToString(prefix = "[", postfix = "]")
}

private fun Int.trackTypeName(): String = when (this) {
    C.TRACK_TYPE_VIDEO -> "video"
    C.TRACK_TYPE_AUDIO -> "audio"
    C.TRACK_TYPE_TEXT -> "text"
    C.TRACK_TYPE_METADATA -> "metadata"
    C.TRACK_TYPE_IMAGE -> "image"
    else -> "type-$this"
}
