package org.siloserver.silo.common.player.video

import android.content.Context
import android.os.Handler
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import org.siloserver.silo.model.playback.CLIENT_DV8_BASE_LAYER_FALLBACK_V1_CLAIM
import org.siloserver.silo.model.playback.CLIENT_DV8_HDR10_PLUS_SANITIZER

/** Media3 video renderer with narrowly gated, independently tested fixes. */
@UnstableApi
internal class SiloMediaCodecVideoRenderer(
    private val context: Context,
    codecAdapterFactory: MediaCodecAdapter.Factory,
    mediaCodecSelector: MediaCodecSelector,
    allowedJoiningTimeMs: Long,
    enableDecoderFallback: Boolean,
    eventHandler: Handler,
    eventListener: VideoRendererEventListener,
    runtimeCorrectionEnabled: (String) -> Boolean,
    /**
     * Invoked with the decoder name when a plan that promised the Profile 8
     * base-layer route ended up on a decoder that cannot honour it. The
     * player turns this into a typed `dv8_base_layer_decoder_unavailable`
     * replan instead of playing with an unverified presentation.
     */
    private val onBaseLayerDecoderMismatch: (String) -> Unit = {},
) : MediaCodecVideoRenderer(
    MediaCodecVideoRenderer.Builder(context)
        .setCodecAdapterFactory(codecAdapterFactory)
        .setMediaCodecSelector(mediaCodecSelector)
        .setAllowedJoiningTimeMs(allowedJoiningTimeMs)
        .setEnableDecoderFallback(enableDecoderFallback)
        .setEventHandler(eventHandler)
        .setEventListener(eventListener)
        .setMaxDroppedFramesToNotify(MAX_DROPPED_FRAMES_TO_NOTIFY),
) {
    private val runtimeCorrectionEnabled = runtimeCorrectionEnabled
    private var nativeDolbyVisionDecoder = false
    private var profile8Input = false

    private val baseLayerRouteActive: Boolean
        get() = runtimeCorrectionEnabled(CLIENT_DV8_BASE_LAYER_FALLBACK_V1_CLAIM)

    /**
     * Media3 only swaps a Dolby Vision format onto an ordinary HEVC/AVC
     * decoder when the *default* display lacks Dolby Vision. That private
     * decision is what the server's base-layer plan depends on, so when the
     * plan named that route the renderer makes the choice explicitly: for a
     * single-layer Profile 8 stream the alternative (base-layer) decoders are
     * offered first, and the Dolby Vision decoder is not offered at all. The
     * bytes are untouched; only the decoder that reads them changes.
     */
    override fun getDecoderInfos(
        mediaCodecSelector: MediaCodecSelector,
        format: Format,
        requiresSecureDecoder: Boolean,
    ): List<MediaCodecInfo> {
        if (baseLayerRouteActive && isDolbyVisionProfile8(format)) {
            val alternatives = MediaCodecUtil.getAlternativeDecoderInfos(
                mediaCodecSelector,
                format,
                requiresSecureDecoder,
                /* requiresTunnelingDecoder = */ false,
            ).filter { it.hardwareAccelerated }
            if (alternatives.isNotEmpty()) {
                return MediaCodecUtil.getDecoderInfosSortedByFormatSupport(context, alternatives, format)
            }
        }
        return super.getDecoderInfos(mediaCodecSelector, format, requiresSecureDecoder)
    }

    override fun onCodecInitialized(
        name: String,
        configuration: MediaCodecAdapter.Configuration,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        nativeDolbyVisionDecoder = configuration.codecInfo.codecMimeType.equals(
            MimeTypes.VIDEO_DOLBY_VISION,
            ignoreCase = true,
        ) && configuration.codecInfo.hardwareAccelerated
        profile8Input = isDolbyVisionProfile8(configuration.format)
        if (baseLayerRouteActive && profile8Input) {
            val honoursBaseLayer = configuration.codecInfo.codecMimeType.equals(
                MimeTypes.VIDEO_H265,
                ignoreCase = true,
            ) && configuration.codecInfo.hardwareAccelerated
            if (!honoursBaseLayer) onBaseLayerDecoderMismatch(name)
        }
        super.onCodecInitialized(name, configuration, initializedTimestampMs, initializationDurationMs)
    }

    override fun onInputFormatChanged(formatHolder: FormatHolder): DecoderReuseEvaluation? {
        val result = super.onInputFormatChanged(formatHolder)
        profile8Input = formatHolder.format?.let(::isDolbyVisionProfile8) == true
        return result
    }

    override fun onQueueInputBuffer(buffer: DecoderInputBuffer) {
        val data = buffer.data
        if (runtimeCorrectionEnabled(CLIENT_DV8_HDR10_PLUS_SANITIZER) &&
            nativeDolbyVisionDecoder && profile8Input && !buffer.isEncrypted && data != null &&
            DolbyVisionHdr10PlusSanitizer.sanitize(data)
        ) {
            PlaybackRuntimeCorrectionMetrics.recordDolbyVisionHdr10PlusSample()
        }
        super.onQueueInputBuffer(buffer)
    }

    override fun onCodecReleased(name: String) {
        nativeDolbyVisionDecoder = false
        profile8Input = false
        super.onCodecReleased(name)
    }

    private fun isDolbyVisionProfile8(format: Format): Boolean {
        if (!format.sampleMimeType.equals(MimeTypes.VIDEO_DOLBY_VISION, ignoreCase = true)) return false
        return DOLBY_VISION_PROFILE_8.containsMatchIn(format.codecs.orEmpty())
    }

    private companion object {
        const val MAX_DROPPED_FRAMES_TO_NOTIFY = 50
        val DOLBY_VISION_PROFILE_8 = Regex("""(?:dvhe|dvh1)\.08(?:\.|$)""", RegexOption.IGNORE_CASE)
    }
}
