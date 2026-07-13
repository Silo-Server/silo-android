package org.siloserver.silo.common.player

import android.app.ActivityManager
import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import org.siloserver.silo.common.BuildConfig
import org.siloserver.silo.common.player.audio.DelayAudioProcessor
import org.siloserver.silo.common.player.audio.PassthroughSuppressingAudioSink
import org.siloserver.silo.common.player.subtitle.OffsetSubtitleParserFactory
import org.siloserver.silo.common.player.subtitle.SubtitleOffsetHolder
import org.siloserver.silo.model.playback.AudioPassthroughCapabilities
import org.siloserver.silo.model.playback.HdrCapabilities
import org.siloserver.silo.model.playback.PlayMethod
import org.siloserver.silo.model.playback.PlaybackDelivery
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.network.TokenManager

/**
 * Factory for ExoPlayer instances. Each factory owns exactly one
 * [AuthenticatedDataSourceFactory], which in turn reuses the pooled
 * media [okhttp3.OkHttpClient] injected via Koin — so TLS sessions and
 * HTTP/2 connections survive across playback sessions.
 *
 * Form-factor (TV vs. phone) is resolved lazily from the [Context] via
 * [TvModeDetector] — callers never pass an `isTv` flag.
 *
 * Track-selection presets are applied via [applyTrackSelectionPresets]
 * so callers can re-apply them on capability changes (HDMI hot-plug,
 * Bluetooth pair, profile language change) without rebuilding the player.
 */
@UnstableApi
class SiloPlayerFactory(
    private val context: Context,
    private val tokenManager: TokenManager,
    private val subtitleManager: SubtitleManager,
    okHttpClient: okhttp3.OkHttpClient,
    private val delayProcessor: DelayAudioProcessor,
    private val subtitleOffsetHolder: SubtitleOffsetHolder,
) {
    val isTv: Boolean = TvModeDetector.isTv(context)

    private var serverUrl: String = ""
    private data class RequestHeaderScope(
        val streamUri: android.net.Uri,
        val headers: Map<String, String>,
    )

    @Volatile private var requestHeaderScope: RequestHeaderScope? = null

    private val dataSourceFactory = AuthenticatedDataSourceFactory(
        context = context,
        okHttpClient = okHttpClient,
        tokenManager = tokenManager,
        serverUrlProvider = { serverUrl },
        requestHeadersProvider = ::requestHeadersFor,
    )

    private val subtitleParserFactory = OffsetSubtitleParserFactory(subtitleOffsetHolder)

    private val extractorsFactory = DolbyVisionColorInfoExtractorsFactory(DefaultExtractorsFactory()
        // Media3 1.10 expects parsed cue samples by default. Forcing raw
        // subtitle payloads here makes SRT/ASS tracks crash the text renderer
        // on Android TV with "Legacy decoding is disabled". The offset wrapper
        // delegates to DefaultSubtitleParserFactory and shifts cue start times
        // by the per-profile subtitle-sync value (A.3f).
        .setSubtitleParserFactory(subtitleParserFactory)
        // HDMV DTS streams (Blu-ray-sourced M2TS) use a stream type the TS
        // payload reader skips by default, so DTS tracks vanish from
        // Blu-ray-remux transport streams without this flag.
        .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
        // The default PCR search window is too small for high-bitrate 4K
        // remux/transcode TS segments with sparse PTS — startup then fails
        // with "timestamp not found" (androidx/media #8571-class failures).
        // 1500 packets matches what battle-tested players ship.
        .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE))

    private val hlsExtractorFactory = DefaultHlsExtractorFactory(
        // HLS uses a separate extractor path from progressive TS. Keep the DTS
        // payload-reader flag in both places so Blu-ray-sourced HLS remuxes do
        // not lose DTS tracks. Media3 does not expose the TS timestamp-search
        // byte window on DefaultHlsExtractorFactory, so that tuning remains
        // progressive-TS only.
        DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS,
        /* exposeCea608WhenMissingDeclarations = */ true,
    ).setSubtitleParserFactory(subtitleParserFactory)

    fun createPlayer(
        preferFfmpegAudio: Boolean = BuildConfig.FFMPEG_AUDIO_ENABLED,
    ): ExoPlayer {
        // When the flag is on (default), extension renderers (FFmpeg audio)
        // beat platform renderers for any MIME both can handle, which means
        // on devices without native E-AC-3/TrueHD/DTS decoders the audio
        // flows through FFmpeg instead of silently failing back to server
        // transcode.
        //
        // When the flag is off (compile-time bisect), we set _MODE_OFF
        // rather than _MODE_ON so extension renderers are *not even
        // instantiated* — that's the clean kill switch. With _MODE_ON,
        // FFmpeg would still fill gaps where the platform has no decoder,
        // making it impossible to cleanly bisect an FFmpeg-specific
        // regression against a pre-FFmpeg baseline.
        val extensionMode = if (preferFfmpegAudio) {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        } else {
            DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
        }

        // Build a single-processor chain that hosts the per-profile audio
        // delay processor. The chain is owned by the factory and shared
        // across players because Koin gives us a single DelayAudioProcessor
        // instance; in practice SiloPlaybackService creates exactly
        // one ExoPlayer per process so there's no contention.
        val audioSink: AudioSink = PassthroughSuppressingAudioSink(DefaultAudioSink.Builder(context)
            .setAudioProcessorChain(
                DefaultAudioSink.DefaultAudioProcessorChain(delayProcessor),
            )
            .build())

        val renderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = audioSink
        }.apply {
            setExtensionRendererMode(extensionMode)
            setEnableDecoderFallback(true)
            // Force the HARDWARE HEVC decoder even when it reports "NoSupport" for
            // an unusual profile/level. Some encodes (e.g. an odd-resolution 4K
            // Main10 title) are rejected by the capability check yet decode fine in
            // hardware — an NVIDIA Shield plays them, and so does the Pixel's Tensor
            // decoder (`c2.google.hevc.decoder`, hardware-accelerated) once it's
            // actually handed the stream. Without this, Media3 avoids the hardware
            // decoder and falls to Android's software HEVC path, whose 10-bit HDR
            // output is undisplayable (black). Handing the video renderer only the
            // hardware-accelerated HEVC decoders makes it use that instead;
            // decoder fallback stays on, and if no hardware decoder exists we hand
            // back the full list so nothing regresses.
            setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                val infos = MediaCodecSelector.DEFAULT
                    .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                if (mimeType == MimeTypes.VIDEO_H265) {
                    infos.filter { it.hardwareAccelerated }.ifEmpty { infos }
                } else {
                    infos
                }
            }
        }

        val trackSelector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)
                .build()
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val mediaLoadErrorHandlingPolicy = SiloMediaLoadErrorHandlingPolicy()
        val defaultMediaSourceFactory = DefaultMediaSourceFactory(context, extractorsFactory)
            .setDataSourceFactory(dataSourceFactory)
            .setSubtitleParserFactory(subtitleParserFactory)
            .setLoadErrorHandlingPolicy(mediaLoadErrorHandlingPolicy)
        val hlsMediaSourceFactory = HlsMediaSource.Factory(dataSourceFactory)
            .setExtractorFactory(hlsExtractorFactory)
            .setSubtitleParserFactory(subtitleParserFactory)
            .setLoadErrorHandlingPolicy(mediaLoadErrorHandlingPolicy)
        val mediaSourceFactory = SiloMediaSourceFactory(
            defaultFactory = defaultMediaSourceFactory,
            hlsFactory = hlsMediaSourceFactory,
        )

        // Staged buffer: start once a modest cushion is ready, wait longer
        // after an actual stall, and let playback grow a deeper forward
        // buffer in the background. A finite byte cap lets low-bitrate
        // streams grow toward the time limit while preventing high-bitrate
        // remuxes from filling the app heap on memory-constrained TVs.
        val bufferPolicy = PlaybackBufferPolicy.forMode(
            PlaybackBufferMode.QuickStart,
            playbackBufferDeviceProfile(),
        )
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ bufferPolicy.minBufferMs,
                /* maxBufferMs = */ bufferPolicy.maxBufferMs,
                /* bufferForPlaybackMs = */ bufferPolicy.bufferForPlaybackMs,
                /* bufferForPlaybackAfterRebufferMs = */ bufferPolicy.bufferForPlaybackAfterRebufferMs,
            )
            .setTargetBufferBytes(bufferPolicy.targetBufferBytes)
            .setPrioritizeTimeOverSizeThresholds(bufferPolicy.prioritizeTimeOverSizeThresholds)
            .build()

        val builder = ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)

        // Form-factor-gated output guards. On phones/tablets these are the
        // right defaults, but both misfire on Android TV during HDMI/eARC
        // audio-route renegotiation (e.g. a soundbar power-cycle):
        //  - handleAudioBecomingNoisy pauses on ACTION_AUDIO_BECOMING_NOISY,
        //    which is really the phone "headphones unplugged" behavior applied
        //    to the wrong form factor — a transient ARC route change would
        //    such handling, so gating it also keeps the two engines aligned).
        //  - suppressPlaybackOnUnsuitableOutput is a Media3 no-op on TV below
        //    API 35 (Wear-gated), but on API 35+ TVs a transient "no suitable
        //    output" during the same renegotiation could suppress playback.
        // Keep both on phone/tablet; skip both on TV.
        if (!isTv) {
            builder
                .setHandleAudioBecomingNoisy(true)
                .setSuppressPlaybackOnUnsuitableOutput(true)
        }

        // Always prefer seamless frame-rate changes. Non-seamless mode
        // switches cause a short black frame and are jarring on both TVs
        // (HDMI renegotiation) and phones (panel reset); seamless-only
        // stays safe everywhere and is a strict subset of the previous
        // default.
        builder.setVideoChangeFrameRateStrategy(
            C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS,
        )

        return builder.build()
    }

    private fun playbackBufferDeviceProfile(): PlaybackBufferDeviceProfile {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        return PlaybackBufferDeviceProfile(
            memoryClassMb = activityManager?.memoryClass ?: 0,
            isLowRamDevice = activityManager?.isLowRamDevice == true,
        )
    }

    /**
     * Apply capability-aware track selection presets to [player]. Call at
     * player construction and again whenever [AudioPassthroughCapabilities]
     * changes (HDMI hot-plug, BT pair, user-toggled "force Atmos" setting).
     */
    fun applyTrackSelectionPresets(
        player: Player,
        audioCaps: AudioPassthroughCapabilities,
        displayHdr: HdrCapabilities = HdrCapabilities(),
        preferredAudioLanguage: String? = null,
        preferredTextLanguage: String? = null,
        hdrEnabled: Boolean = true,
    ) {
        val base = player.trackSelectionParameters
        val next = if (isTv) {
            TrackSelectionPresets.buildTvParameters(
                context = context,
                base = base,
                audioCaps = audioCaps,
                displayHdr = displayHdr,
                preferredAudioLanguage = preferredAudioLanguage,
                preferredTextLanguage = preferredTextLanguage,
                allowHdr = hdrEnabled,
            )
        } else {
            TrackSelectionPresets.buildPhoneParameters(
                context = context,
                base = base,
                audioCaps = audioCaps,
                spatializerOn = audioCaps.spatializerEnabled,
                preferredAudioLanguage = preferredAudioLanguage,
                preferredTextLanguage = preferredTextLanguage,
            )
        }
        player.trackSelectionParameters = next
    }

    /**
     * Build a [MediaItem] the player can consume directly via `setMediaItem`.
     * The MIME type hint on the item is what lets [DefaultMediaSourceFactory]
     * pick HLS vs. progressive without requiring the stream URL to carry the
     * right extension — Silo's transcode URLs don't always end in .m3u8.
     *
     * Sidecar subtitles are attached via `setSubtitleConfigurations`; the
     * default media source factory then wires them through the merging source
     * automatically.
     */
    fun buildMediaItem(
        streamUrl: String,
        playMethod: PlayMethod,
        delivery: PlaybackDelivery? = null,
        serverUrl: String,
        container: String? = null,
        subtitles: List<PlayerSubtitleInfo> = emptyList(),
        title: String? = null,
        subtitle: String? = null,
        artworkUrl: String? = null,
        durationMs: Long? = null,
        requestHeaders: Map<String, String> = emptyMap(),
    ): MediaItem {
        this.serverUrl = serverUrl
        val absoluteUrl = buildAbsoluteUrl(serverUrl, streamUrl)
        requestHeaderScope = requestHeaders.takeIf { it.isNotEmpty() }?.let {
            RequestHeaderScope(android.net.Uri.parse(absoluteUrl), it)
        }
        val subtitleConfigurations =
            subtitleManager.buildSubtitleConfigurations(subtitles, serverUrl)

        val builder = MediaItem.Builder()
            .setUri(absoluteUrl)
            .setSubtitleConfigurations(subtitleConfigurations)

        // Now Playing metadata — drives the system media notification + lock
        // screen via MediaSession. Title/subtitle/artwork are all optional so
        // existing callers compile unchanged; when provided, the OS surfaces
        // them on lock screen, Bluetooth devices, and Wear watchfaces. Mirrors
        // iOS's NowPlayingController (iosApp/Screens/Player/NowPlayingController.swift).
        if (title != null || subtitle != null || artworkUrl != null || durationMs != null) {
            val metadataBuilder = androidx.media3.common.MediaMetadata.Builder()
            title?.let { metadataBuilder.setTitle(it) }
            subtitle?.let {
                metadataBuilder.setSubtitle(it)
                metadataBuilder.setArtist(it)
            }
            artworkUrl?.takeIf { it.isNotBlank() }
                ?.let { metadataBuilder.setArtworkUri(android.net.Uri.parse(it)) }
            durationMs?.let { metadataBuilder.setDurationMs(it) }
            builder.setMediaMetadata(metadataBuilder.build())
        }

        mediaItemMimeType(playMethod, container, delivery)?.let { builder.setMimeType(it) }

        return builder.build()
    }

    /**
     * Plan headers are scoped to the issued stream/session path. The factory is
     * process-wide and also serves audiobook/offline MediaItems, so blindly
     * retaining the last video's headers would leak them into unrelated media.
     * HLS child playlists and segments are allowed within the same directory.
     */
    private fun requestHeadersFor(uri: android.net.Uri): Map<String, String> {
        val scope = requestHeaderScope ?: return emptyMap()
        val issued = scope.streamUri
        if (!uri.scheme.equals(issued.scheme, ignoreCase = true) ||
            !uri.host.equals(issued.host, ignoreCase = true) ||
            uri.port != issued.port
        ) return emptyMap()
        val issuedPath = issued.path.orEmpty()
        val issuedDirectory = issuedPath.substringBeforeLast('/', missingDelimiterValue = issuedPath)
            .trimEnd('/') + "/"
        val candidatePath = uri.path.orEmpty()
        return if (candidatePath == issuedPath || candidatePath.startsWith(issuedDirectory)) {
            scope.headers
        } else {
            emptyMap()
        }
    }

    private fun buildAbsoluteUrl(serverUrl: String, streamUrl: String): String =
        resolvePlaybackStreamUrl(serverUrl, streamUrl)

    private class SiloMediaSourceFactory(
        private val defaultFactory: MediaSource.Factory,
        private val hlsFactory: MediaSource.Factory,
    ) : MediaSource.Factory {
        override fun setDrmSessionManagerProvider(
            drmSessionManagerProvider: DrmSessionManagerProvider,
        ): MediaSource.Factory {
            defaultFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
            hlsFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
            return this
        }

        override fun setLoadErrorHandlingPolicy(
            loadErrorHandlingPolicy: LoadErrorHandlingPolicy,
        ): MediaSource.Factory {
            defaultFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            hlsFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            return this
        }

        override fun getSupportedTypes(): IntArray = defaultFactory.supportedTypes

        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            val localConfiguration = mediaItem.localConfiguration
                ?: return defaultFactory.createMediaSource(mediaItem)
            val contentType = Util.inferContentTypeForUriAndMimeType(
                localConfiguration.uri,
                localConfiguration.mimeType,
            )
            val hasExternalSubtitleSidecars = localConfiguration.subtitleConfigurations.isNotEmpty()
            // DefaultMediaSourceFactory is still the only public Media3 path here
            // that merges MediaItem sidecar subtitles. Keep that route when
            // sidecars are present so subtitle delay/normalization do not regress.
            return if (contentType == C.CONTENT_TYPE_HLS && !hasExternalSubtitleSidecars) {
                hlsFactory.createMediaSource(mediaItem)
            } else {
                defaultFactory.createMediaSource(mediaItem)
            }
        }
    }
}

/**
 * Resolves a server stream URL to an absolute, loadable URL.
 *
 * Already-absolute URLs (`http`/`https`) and local offline URIs
 * (`file`/`content`) are returned unchanged. API-relative URLs are only
 * prefixed with the server base URL; stream-relative paths are prefixed with
 * the server base URL and the `/api/v1` mount.
 *
 * Shared by [SiloPlayerFactory] (video) and the audiobook player so both
 * resolve identically. Players that hand a relative URI straight to Media3 hit
 * OkHttp's "Malformed URL" because [RoutedDataSource] only prefixes the base
 * when the factory's serverUrl is set — which the audiobook path never did.
 */
fun resolvePlaybackStreamUrl(serverUrl: String, streamUrl: String): String {
    val base = serverUrl.trimEnd('/')
    return when {
        streamUrl.startsWith("http://") ||
            streamUrl.startsWith("https://") ||
            streamUrl.startsWith("file://") ||
            streamUrl.startsWith("content://") -> streamUrl // Already absolute / local offline: nothing to prefix.
        streamUrl.startsWith("/api/") -> "$base$streamUrl"
        else -> "$base/api/v1$streamUrl"
    }
}

internal fun videoContainerMimeType(container: String?): String? {
    val normalized = container
        ?.trim()
        ?.trimStart('.')
        ?.lowercase()
        .orEmpty()
    return when (normalized) {
        "mkv", "matroska" -> "video/x-matroska"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "mov", "qt" -> "video/quicktime"
        "ts", "mpegts", "mpeg-ts", "m2ts", "mts" -> "video/mp2t"
        "avi" -> "video/x-msvideo"
        else -> null
    }
}

internal fun mediaItemMimeType(
    playMethod: PlayMethod,
    container: String?,
    delivery: PlaybackDelivery? = null,
): String? {
    if (delivery != null) {
        return when (delivery) {
            PlaybackDelivery.ORIGINAL_HTTP,
            PlaybackDelivery.CLIENT_LOCAL_NORMALIZATION,
            -> videoContainerMimeType(container)
            // The server's progressive remux path currently remuxes into MP4
            // even when the source container was MKV/AVI/etc.
            PlaybackDelivery.SERVER_REMUX_PROGRESSIVE -> MimeTypes.VIDEO_MP4
            PlaybackDelivery.SERVER_REMUX_HLS,
            PlaybackDelivery.SERVER_TRANSCODE_HLS,
            -> MimeTypes.APPLICATION_M3U8
        }
    }
    return when (playMethod) {
        PlayMethod.TRANSCODE,
        PlayMethod.REMUX,
        -> MimeTypes.APPLICATION_M3U8
        PlayMethod.DIRECT -> videoContainerMimeType(container)
    }
}
