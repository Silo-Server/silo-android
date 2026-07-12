package org.siloserver.silo.common.player.audio

import androidx.media3.common.Format
import androidx.media3.exoplayer.audio.AudioSink

/**
 * Attempt-scoped suppression for a passthrough encoding and channel layout.
 * A failed direct sink configuration gets one same-plan retry through a local
 * decoder/PCM renderer. New server plans clear the suppression set.
 */
object PassthroughSuppressionRegistry {
    private data class Key(val mime: String, val channels: Int)

    private var attemptKey: String? = null
    private val blocked = mutableSetOf<Key>()
    private var retryUsed = false

    @Synchronized
    fun beginAttempt(key: String) {
        if (attemptKey == key) return
        attemptKey = key
        blocked.clear()
        retryUsed = false
    }

    @Synchronized
    fun suppressForSinglePcmRetry(mime: String, channels: Int): Boolean {
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
}

class PassthroughSuppressingAudioSink(
    private val delegate: AudioSink,
) : AudioSink by delegate {
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
