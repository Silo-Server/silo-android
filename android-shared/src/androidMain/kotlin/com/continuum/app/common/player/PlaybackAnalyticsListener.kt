package com.continuum.app.common.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * `AnalyticsListener` that logs the handful of signals we actually triage
 * playback issues with — decoder init names, dropped-frame counts, audio
 * underruns, load errors, and bandwidth estimates — and re-emits them to an
 * in-process [SharedFlow] so the debug overlay (or a future server-side
 * telemetry POST) can subscribe without another listener registration.
 *
 * Output is `Log.i` on [TAG] only; no network I/O. Server-side telemetry
 * ingestion is deferred to a follow-up — the flow hook here is the seam.
 */
@UnstableApi
class PlaybackAnalyticsListener : AnalyticsListener {

    companion object {
        private const val TAG = "Media3Analytics"
    }

    sealed class Event {
        data class VideoDecoderInitialized(val decoderName: String) : Event()
        data class AudioDecoderInitialized(val decoderName: String) : Event()
        data class AudioTrackInitialized(
            val encoding: Int,
            val passthrough: Boolean,
            val tunneling: Boolean,
            val offload: Boolean,
        ) : Event()
        data class VideoFormatChanged(val format: Format) : Event()
        data class AudioFormatChanged(val format: Format) : Event()
        data class DroppedFrames(val count: Int, val elapsedMs: Long) : Event()
        object AudioUnderrun : Event()
        data class LoadError(val throwable: Throwable) : Event()
        data class PlayerError(val error: PlaybackException) : Event()
        data class BandwidthEstimate(val bitrateBps: Long) : Event()
        data class TrackSnapshot(val description: String) : Event()
    }

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 32)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    // Tracks whether a software/hardware audio DECODER was bound for the current
    // audio format. When true the stream is decoded to PCM; when false the
    // AudioSink is fed the encoded bitstream directly (passthrough / bitstream,
    // e.g. E-AC3 JOC Atmos, TrueHD, DTS-HD). Reset on every input-format change
    // and set in onAudioDecoderInitialized, both of which fire per format.
    @Volatile
    private var audioDecoderBound: Boolean = false

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        Log.i(TAG, "Video decoder: $decoderName (init ${initializationDurationMs}ms)")
        _events.tryEmit(Event.VideoDecoderInitialized(decoderName))
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        audioDecoderBound = true
        Log.i(TAG, "Audio decoder: $decoderName (init ${initializationDurationMs}ms) -> decoded to PCM")
        _events.tryEmit(Event.AudioDecoderInitialized(decoderName))
    }

    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
    ) {
        Log.i(TAG, "Video format: ${format.sampleMimeType} ${format.width}x${format.height}@${format.frameRate} codecs=${format.codecs}")
        _events.tryEmit(Event.VideoFormatChanged(format))
    }

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
    ) {
        // New input format → a fresh renderer/AudioTrack binding decision is
        // about to be made. Clear the decoder flag; whichever of
        // onAudioDecoderInitialized (PCM) or onAudioTrackInitialized (sink)
        // fires next reveals passthrough vs decoded routing.
        audioDecoderBound = false
        Log.i(
            TAG,
            "Audio format: ${format.sampleMimeType} ch=${format.channelCount} sr=${format.sampleRate} codecs=${format.codecs}",
        )
        _events.tryEmit(Event.AudioFormatChanged(format))
    }

    override fun onAudioTrackInitialized(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioSink.AudioTrackConfig,
    ) {
        // The bound AudioTrack reveals how audio reaches the sink. A non-PCM
        // encoding with no decoder bound means the encoded bitstream is being
        // passed through to the AVR/TV (E-AC3 JOC = Atmos, TrueHD, DTS-HD);
        // ENCODING_PCM_* means the renderer decoded to PCM first. This is the
        // single signal that settles the Atmos renderer-mode question.
        val encoding = audioTrackConfig.encoding
        val isPcm = isPcmEncoding(encoding)
        val passthrough = !isPcm && !audioDecoderBound
        val routing = when {
            audioTrackConfig.offload -> "offload"
            passthrough -> "passthrough/bitstream"
            isPcm -> "decoded-to-PCM"
            else -> "decoded-to-PCM"
        }
        Log.i(
            TAG,
            "AudioTrack: encoding=${encodingName(encoding)}($encoding) routing=$routing " +
                "sr=${audioTrackConfig.sampleRate} tunneling=${audioTrackConfig.tunneling} " +
                "offload=${audioTrackConfig.offload} decoderBound=$audioDecoderBound",
        )
        _events.tryEmit(
            Event.AudioTrackInitialized(
                encoding = encoding,
                passthrough = passthrough,
                tunneling = audioTrackConfig.tunneling,
                offload = audioTrackConfig.offload,
            ),
        )
    }

    override fun onAudioTrackReleased(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioSink.AudioTrackConfig,
    ) {
        Log.i(TAG, "AudioTrack released: encoding=${encodingName(audioTrackConfig.encoding)}")
    }

    override fun onTracksChanged(
        eventTime: AnalyticsListener.EventTime,
        tracks: Tracks,
    ) {
        val description = tracks.describeForLog()
        Log.i(TAG, "Track snapshot: $description")
        _events.tryEmit(Event.TrackSnapshot(description))
    }

    override fun onDroppedVideoFrames(
        eventTime: AnalyticsListener.EventTime,
        droppedFrames: Int,
        elapsedRealtimeMs: Long,
    ) {
        if (droppedFrames > 0) {
            Log.w(TAG, "Dropped $droppedFrames video frame(s) in ${elapsedRealtimeMs}ms")
        }
        _events.tryEmit(Event.DroppedFrames(droppedFrames, elapsedRealtimeMs))
    }

    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long,
    ) {
        Log.w(TAG, "Audio underrun (buffer=${bufferSizeMs}ms, gap=${elapsedSinceLastFeedMs}ms)")
        _events.tryEmit(Event.AudioUnderrun)
    }

    override fun onPlayerError(
        eventTime: AnalyticsListener.EventTime,
        error: PlaybackException,
    ) {
        Log.e(TAG, "Player error ${error.errorCodeName}: ${error.message}", error)
        _events.tryEmit(Event.PlayerError(error))
    }

    override fun onLoadError(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: java.io.IOException,
        wasCanceled: Boolean,
    ) {
        Log.w(TAG, "Load error (${mediaLoadData.dataType}): ${error.message}")
        _events.tryEmit(Event.LoadError(error))
    }

    override fun onBandwidthEstimate(
        eventTime: AnalyticsListener.EventTime,
        totalLoadTimeMs: Int,
        totalBytesLoaded: Long,
        bitrateEstimate: Long,
    ) {
        _events.tryEmit(Event.BandwidthEstimate(bitrateEstimate))
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

@UnstableApi
private fun isPcmEncoding(encoding: Int): Boolean = when (encoding) {
    C.ENCODING_PCM_8BIT,
    C.ENCODING_PCM_16BIT,
    C.ENCODING_PCM_16BIT_BIG_ENDIAN,
    C.ENCODING_PCM_24BIT,
    C.ENCODING_PCM_24BIT_BIG_ENDIAN,
    C.ENCODING_PCM_32BIT,
    C.ENCODING_PCM_32BIT_BIG_ENDIAN,
    C.ENCODING_PCM_FLOAT,
    C.ENCODING_PCM_DOUBLE,
    -> true
    else -> false
}

@UnstableApi
private fun encodingName(encoding: Int): String = when (encoding) {
    C.ENCODING_INVALID -> "INVALID"
    C.ENCODING_PCM_8BIT -> "PCM_8BIT"
    C.ENCODING_PCM_16BIT -> "PCM_16BIT"
    C.ENCODING_PCM_16BIT_BIG_ENDIAN -> "PCM_16BIT_BE"
    C.ENCODING_PCM_24BIT -> "PCM_24BIT"
    C.ENCODING_PCM_32BIT -> "PCM_32BIT"
    C.ENCODING_PCM_FLOAT -> "PCM_FLOAT"
    C.ENCODING_AC3 -> "AC3"
    C.ENCODING_E_AC3 -> "E_AC3"
    C.ENCODING_E_AC3_JOC -> "E_AC3_JOC(Atmos)"
    C.ENCODING_AC4 -> "AC4"
    C.ENCODING_DTS -> "DTS"
    C.ENCODING_DTS_HD -> "DTS_HD"
    C.ENCODING_DTS_UHD_P2 -> "DTS_UHD_P2"
    C.ENCODING_DOLBY_TRUEHD -> "DOLBY_TRUEHD"
    else -> "enc-$encoding"
}
