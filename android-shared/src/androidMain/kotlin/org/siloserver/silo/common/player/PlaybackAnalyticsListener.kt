package org.siloserver.silo.common.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.siloserver.silo.common.diagnostics.DiagnosticsCaptureDetailState
import org.siloserver.silo.common.diagnostics.DiagnosticsPlaybackLogger
import org.siloserver.silo.common.diagnostics.DiagnosticsStatsCadence

/**
 * `AnalyticsListener` that logs the handful of signals we actually triage
 * playback issues with — decoder init names, dropped-frame counts, audio
 * underruns, load errors, and bandwidth estimates — and re-emits them to an
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
    private var diagnosticsStats = PlayerStatsSnapshot()
    private val diagnosticsCadence = DiagnosticsStatsCadence()
    private var playWhenReady = true

    override fun onPlayWhenReadyChanged(
        eventTime: AnalyticsListener.EventTime,
        playWhenReady: Boolean,
        reason: Int,
    ) {
        this.playWhenReady = playWhenReady
    }

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
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
        if (reason == Player.DISCONTINUITY_REASON_SEEK) emit(Event.SeekStarted(eventTime.realtimeMs))
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
        DiagnosticsPlaybackLogger.playerError()
        emit(Event.PlayerError(error))
    }

    override fun onLoadError(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: java.io.IOException,
        wasCanceled: Boolean,
    ) {
        DiagnosticsPlaybackLogger.loadError()
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
