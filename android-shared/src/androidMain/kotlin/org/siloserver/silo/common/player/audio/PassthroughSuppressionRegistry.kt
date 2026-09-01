package org.siloserver.silo.common.player.audio

import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

data class PassthroughSuppressionSnapshot(
    val suppressedFormats: List<String>,
    val retryUsed: Boolean,
)

/**
 * The write side of passthrough suppression, as a session manager sees it.
 *
 * It exists so a manager that does not drive a local audio sink can be handed
 * [None] instead of the process-global registry. Cast preparation runs its own
 * throwaway [org.siloserver.silo.common.player.PlaybackSessionManager], and its
 * plan keys would otherwise reset the suppression set belonging to the phone's
 * still-playing local session.
 */
interface PassthroughSuppressionScope {
    fun beginAttempt(key: String)

    fun suppressForSinglePcmRetry(mime: String, channels: Int): Boolean

    /** Accepts and discards; for sessions whose audio never reaches a local sink. */
    object None : PassthroughSuppressionScope {
        override fun beginAttempt(key: String) = Unit

        override fun suppressForSinglePcmRetry(mime: String, channels: Int): Boolean = false
    }
}

/**
 * Attempt-scoped suppression for a passthrough encoding and channel layout.
 * A failed direct sink configuration gets one same-plan retry through a local
 * decoder/PCM renderer. New server plans clear the suppression set.
 */
object PassthroughSuppressionRegistry : PassthroughSuppressionScope {
    private data class Key(val mime: String, val channels: Int)

    private var attemptKey: String? = null
    private val blocked = mutableSetOf<Key>()
    private var retryUsed = false

    @Synchronized
    override fun beginAttempt(key: String) {
        if (attemptKey == key) return
        attemptKey = key
        blocked.clear()
        retryUsed = false
    }

    @Synchronized
    override fun suppressForSinglePcmRetry(mime: String, channels: Int): Boolean {
        if (attemptKey == null || retryUsed || mime.isBlank()) return false
        retryUsed = true
        blocked += Key(mime.lowercase(), channels.coerceAtLeast(0))
        return true
    }

    @Synchronized
    fun isSuppressed(format: Format): Boolean {
        val mime = format.sampleMimeType?.lowercase() ?: return false
        val channels = format.channelCount.coerceAtLeast(0)
        return blocked.any { it.mime == mime && (it.channels == 0 || channels == 0 || it.channels == channels) }
    }

    /** Excludes the attempt key because it can contain playback-session identifiers. */
    @Synchronized
    fun diagnosticsSnapshot(): PassthroughSuppressionSnapshot = PassthroughSuppressionSnapshot(
        suppressedFormats = blocked.map { "${it.mime}:${it.channels}" }.sorted(),
        retryUsed = retryUsed,
    )
}

/**
 * Extends Media3's [ForwardingAudioSink] rather than using Kotlin interface
 * delegation. `by delegate` forwards only abstract members; Media3 1.11
 * made both `configure` overloads default methods that call each other, so a
 * delegating wrapper never reached the real sink and recursed until
 * StackOverflowError on the first audio format change.
 */
@UnstableApi
class PassthroughSuppressingAudioSink(
    private val delegate: AudioSink,
) : ForwardingAudioSink(delegate) {
    override fun supportsFormat(format: Format): Boolean =
        getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatSupport(format: Format): Int =
        if (PassthroughSuppressionRegistry.isSuppressed(format)) {
            // Returning SUPPORTED_WITH_TRANSCODING still makes
            // MediaCodecAudioRenderer treat the encoded format as sink-
            // playable and select bypass/passthrough again. Mark only the
            // failed encoded layout unsupported; the renderer then decodes
            // it and queries the sink again with PCM.
            AudioSink.SINK_FORMAT_UNSUPPORTED
        } else {
            delegate.getFormatSupport(format)
        }
}
