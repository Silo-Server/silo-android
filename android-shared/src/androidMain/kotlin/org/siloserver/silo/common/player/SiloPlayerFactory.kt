package org.siloserver.silo.common.player

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.DefaultHlsExtractorFactory
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleExtractor
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import org.siloserver.silo.common.player.audio.DelayAudioProcessor
import org.siloserver.silo.common.player.audio.PassthroughSuppressingAudioSink
import org.siloserver.silo.common.player.subtitle.OffsetSubtitleParserFactory
import org.siloserver.silo.common.player.subtitle.PgsSupExtractor
import org.siloserver.silo.common.player.subtitle.SidecarPlaybackFloor
import org.siloserver.silo.common.player.subtitle.SidecarSubtitleMediaSource
import org.siloserver.silo.common.player.subtitle.SubtitleOffsetHolder
import org.siloserver.silo.common.player.subtitle.StreamingWebvttExtractor
import org.siloserver.silo.common.player.video.SiloMediaCodecVideoRenderer
import org.siloserver.silo.common.player.video.PlaybackRuntimeCorrectionState
import org.siloserver.silo.libass.LibassBridge
import org.siloserver.silo.model.playback.AudioPassthroughCapabilities
import org.siloserver.silo.model.playback.HdrCapabilities
import org.siloserver.silo.model.playback.PlayMethod
import org.siloserver.silo.model.playback.PlaybackDelivery
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.network.TokenManager
import java.util.ArrayList

/**
 * Factory for ExoPlayer instances. Each factory owns exactly one
 * [AuthenticatedDataSourceFactory], which in turn reuses the pooled HTTP
 * backend injected via Koin — so TLS sessions and HTTP/2 connections survive
 * across playback sessions while the backend remains replaceable.
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
    httpDataSourceFactory: HttpDataSource.Factory,
    mediaAuthSession: MediaAuthSession,
    private val bandwidthMeter: DefaultBandwidthMeter,
    private val delayProcessor: DelayAudioProcessor,
    private val subtitleOffsetHolder: SubtitleOffsetHolder,
    private val libassBridge: LibassBridge,
) {
    val isTv: Boolean = TvModeDetector.isTv(context)

    private var serverUrl: String = ""
    private data class RequestHeaderScope(
        val streamUri: android.net.Uri,
        val headers: Map<String, String>,
    )

    @Volatile private var requestHeaderScope: RequestHeaderScope? = null
    @Volatile private var resumableDirectPlayUri: android.net.Uri? = null
    private val runtimeCorrectionState = PlaybackRuntimeCorrectionState()

    /**
     * Name of the decoder the renderer opened for a plan that promised the
     * Profile 8 base-layer route but which cannot produce it (a native Dolby
     * Vision decoder, or a non-HEVC decoder). Null while the promise holds.
     * Observed by the player screens, which turn it into a typed replan.
     */
    val baseLayerDecoderMismatch = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    private val dataSourceFactory = AuthenticatedDataSourceFactory(
        context = context,
        httpDataSourceFactory = httpDataSourceFactory,
        authSession = mediaAuthSession,
        serverUrlProvider = { serverUrl },
        requestHeadersProvider = ::requestHeadersFor,
        isResumableDirectPlayUri = ::isResumableDirectPlayUri,
    )

    private val embeddedSubtitleParserFactory = OffsetSubtitleParserFactory(
        offsetUsProvider = subtitleOffsetHolder::getUserOffsetUs,
        delegate = libassBridge.parserFactory,
    )

    private val sidecarSubtitleParserFactory = OffsetSubtitleParserFactory(
        offsetUsProvider = subtitleOffsetHolder::getOffsetUs,
        delegate = BitmapAwareSubtitleParserFactory(libassBridge.parserFactory),
    )

    private class BitmapAwareSubtitleParserFactory(
        private val delegate: SubtitleParser.Factory,
        private val media3: SubtitleParser.Factory = DefaultSubtitleParserFactory(),
    ) : SubtitleParser.Factory {
        private fun factoryFor(format: androidx.media3.common.Format): SubtitleParser.Factory =
            when (format.sampleMimeType) {
                MimeTypes.APPLICATION_PGS,
                MimeTypes.APPLICATION_VOBSUB,
                MimeTypes.APPLICATION_DVBSUBS,
                -> media3
                else -> delegate
            }

        override fun supportsFormat(format: androidx.media3.common.Format): Boolean =
            factoryFor(format).supportsFormat(format)

        override fun getCueReplacementBehavior(format: androidx.media3.common.Format): Int =
            factoryFor(format).getCueReplacementBehavior(format)

        override fun create(format: androidx.media3.common.Format): SubtitleParser =
            factoryFor(format).create(format)
    }

    private fun configuredExtractorsFactory() = libassBridge.wrapExtractors(
        DefaultExtractorsFactory()
            // Media3 1.10 expects parsed cue samples by default. Forcing raw
            // subtitle payloads here makes SRT/ASS tracks crash the text renderer
            // on Android TV with "Legacy decoding is disabled". The offset wrapper
            // delegates to libass for ASS/SSA and Media3 for other text formats.
            .setSubtitleParserFactory(embeddedSubtitleParserFactory)
            // HDMV DTS streams (Blu-ray-sourced M2TS) use a stream type the TS
            // payload reader skips by default, so DTS tracks vanish from
            // Blu-ray-remux transport streams without this flag.
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
            // The default PCR search window is too small for high-bitrate 4K
            // remux/transcode TS segments with sparse PTS — startup then fails
            // with "timestamp not found" (androidx/media #8571-class failures).
            // 1500 packets matches what battle-tested players ship.
            .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE),
        embeddedSubtitleParserFactory,
    )

    private val hlsExtractorFactory = DefaultHlsExtractorFactory(
        // HLS uses a separate extractor path from progressive TS. Keep the DTS
        // payload-reader flag in both places so Blu-ray-sourced HLS remuxes do
        // not lose DTS tracks. Media3 does not expose the TS timestamp-search
        // byte window on DefaultHlsExtractorFactory, so that tuning remains
        // progressive-TS only.
        DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS,
        /* exposeCea608WhenMissingDeclarations = */ true,
    ).setSubtitleParserFactory(embeddedSubtitleParserFactory)

    fun createPlayer(
        preferFfmpegAudio: Boolean = FfmpegAudioSupport.isAvailable(),
    ): ExoPlayer {
        // When the build flag is on and the current ABI's JNI library loads,
        // extension renderers (FFmpeg audio) follow the platform renderers and
        // fill only codec gaps. This keeps native passthrough/MediaCodec paths
        // preferred while retaining a last-resort local decoder for
        // forced-original and recovery cases.
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

        val media3RenderersFactory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink = audioSink

            override fun buildVideoRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                eventHandler: Handler,
                eventListener: VideoRendererEventListener,
                allowedVideoJoiningTimeMs: Long,
                out: ArrayList<Renderer>,
            ) {
                val firstNewRenderer = out.size
                super.buildVideoRenderers(
                    context,
                    extensionRendererMode,
                    mediaCodecSelector,
                    enableDecoderFallback,
                    eventHandler,
                    eventListener,
                    allowedVideoJoiningTimeMs,
                    out,
                )
                val platformRenderer = (firstNewRenderer until out.size)
                    .firstOrNull { out[it] is MediaCodecVideoRenderer }
                    ?: return
                out[platformRenderer] = SiloMediaCodecVideoRenderer(
                    context = context,
                    codecAdapterFactory = codecAdapterFactory,
                    mediaCodecSelector = mediaCodecSelector,
                    allowedJoiningTimeMs = allowedVideoJoiningTimeMs,
                    enableDecoderFallback = enableDecoderFallback,
                    eventHandler = eventHandler,
                    eventListener = eventListener,
                    runtimeCorrectionEnabled = runtimeCorrectionState::isEnabled,
                    onBaseLayerDecoderMismatch = { decoderName ->
                        baseLayerDecoderMismatch.value = decoderName
                    },
                )
            }
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
        val renderersFactory = libassBridge.wrapRenderers(
            media3RenderersFactory,
            subtitleOffsetHolder::getOffsetUs,
        )

        val trackSelector = DefaultTrackSelector(context).apply {
            parameters = buildUponParameters()
                .setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)
                .build()
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val mediaLoadErrorHandlingPolicy = SiloMediaLoadErrorHandlingPolicy(
            isResumableProgressiveDirectPlay = ::isResumableDirectPlayUri,
        )
        fun correctedMediaSourceFactory(
            mode: DolbyVisionTransformMode,
            expectedDynamicRange: String? = null,
            expectedColorRange: String? = null,
            dolbyVisionBaseLayerRoute: Boolean = false,
        ) =
            DefaultMediaSourceFactory(
                context,
                DolbyVisionColorInfoExtractorsFactory(
                    configuredExtractorsFactory(),
                    mode,
                    expectedDynamicRange = expectedDynamicRange,
                    expectedColorRange = expectedColorRange,
                    dolbyVisionBaseLayerRoute = dolbyVisionBaseLayerRoute,
                ),
            )
            .setDataSourceFactory(dataSourceFactory)
            .setSubtitleParserFactory(embeddedSubtitleParserFactory)
            .setLoadErrorHandlingPolicy(mediaLoadErrorHandlingPolicy)
        val hlsMediaSourceFactory = HlsMediaSource.Factory(dataSourceFactory)
            .setExtractorFactory(hlsExtractorFactory)
            .setSubtitleParserFactory(embeddedSubtitleParserFactory)
            .setLoadErrorHandlingPolicy(mediaLoadErrorHandlingPolicy)
        val mediaSourceFactory = SiloMediaSourceFactory(
            defaultFactory = correctedMediaSourceFactory(DolbyVisionTransformMode.DISABLED),
            correctedFactory = ::correctedMediaSourceFactory,
            hlsFactory = hlsMediaSourceFactory,
            dataSourceFactory = dataSourceFactory,
            subtitleParserFactory = sidecarSubtitleParserFactory,
            subtitleOffsetProvider = subtitleOffsetHolder::getOffsetUs,
            loadErrorHandlingPolicy = mediaLoadErrorHandlingPolicy,
        )

        // Start on a small cushion and keep filling in the background. Depth is
        // bounded by the device's memory budget in SiloLoadControl; the gap
        // between min and max is fixed so the connection is never idle long
        // enough for an upstream proxy to close it.
        val bufferPolicy = PlaybackBufferPolicy.forConditions(playbackBufferDeviceProfile())
        val loadControl = SiloLoadControl(bufferPolicy)

        val builder = ExoPlayer.Builder(context, renderersFactory)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setBandwidthMeter(bandwidthMeter)
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

        return builder.build().also(libassBridge::initialize)
    }

    /** Releases the player and the libass handler/font graph adopted for it. */
    fun releasePlayer(player: Player) {
        (player as? ExoPlayer)?.let(libassBridge::releasePlayer)
        player.release()
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
     *
     * Returns whether parameters were actually assigned. A skip — teardown
     * guard or no-op parameters — must be visible to callers that track
     * "presets have been applied once", or a skipped first run silently
     * reclassifies the real first application as a later change.
     */
    fun applyTrackSelectionPresets(
        player: Player,
        audioCaps: AudioPassthroughCapabilities,
        displayHdr: HdrCapabilities = HdrCapabilities(),
        preferredAudioLanguage: String? = null,
        preferredTextLanguage: String? = null,
        hdrEnabled: Boolean = true,
    ): Boolean {
        // ExoPlayer resolves a track reselection by seeking the current media
        // period. With no media period — idle, empty timeline, or torn down
        // while this was in flight — that path dereferences a null holder and
        // kills playback outright:
        //
        //   NullPointerException: MediaPeriodHolder.info
        //     at ExoPlayerImplInternal.seekToCurrentPosition
        //     at ExoPlayerImplInternal.reselectTracksInternalAndSeek
        //
        // Capability changes are exactly what lands here at the wrong moment:
        // an HDMI route drop fires this while the screen is being left, so the
        // player is already past the point of having anything to reselect.
        // Presets are re-applied on the next construction anyway, so skipping
        // costs nothing.
        if (shouldSkipTrackReselection(player.playbackState, player.currentTimeline.isEmpty)) return false

        val base = player.trackSelectionParameters
        val next = if (isTv) {
            // preferredTextLanguage is deliberately NOT forwarded on TV: the
            // subtitle transaction adapter is the only authority that may
            // enable a text track there (see TrackSelectionPresets.buildTvParameters).
            TrackSelectionPresets.buildTvParameters(
                context = context,
                base = base,
                audioCaps = audioCaps,
                displayHdr = displayHdr,
                preferredAudioLanguage = preferredAudioLanguage,
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
        return true
    }

    /**
     * Build a [MediaItem] the player can consume directly via `setMediaItem`.
     * The MIME type hint on the item is what lets [DefaultMediaSourceFactory]
     * pick HLS vs. progressive without requiring the stream URL to carry the
     * right extension — Silo's transcode URLs don't always end in .m3u8.
     *
     * Sidecar subtitles are attached via `setSubtitleConfigurations`; the
     * selected media source factory wires them through a merging source.
     */
    fun buildMediaItem(
        contentId: String? = null,
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
        timelineOffsetSeconds: Double = 0.0,
        requestHeaders: Map<String, String> = emptyMap(),
        expectedDynamicRange: String? = null,
        expectedColorRange: String? = null,
        transformations: List<String> = emptyList(),
        runtimeCorrections: List<String> = emptyList(),
        activeClaims: List<String> = emptyList(),
    ): MediaItem {
        this.serverUrl = serverUrl
        // Runtime corrections and plan-scoped claims share one activation
        // set: both are per-mount switches the renderer consults by name.
        runtimeCorrectionState.activate(runtimeCorrections + activeClaims)
        baseLayerDecoderMismatch.value = null
        subtitleOffsetHolder.setTimelineOffsetSeconds(timelineOffsetSeconds)
        val absoluteUrl = buildAbsoluteUrl(serverUrl, streamUrl)
        resumableDirectPlayUri = if (delivery == PlaybackDelivery.ORIGINAL_HTTP) {
            android.net.Uri.parse(absoluteUrl)
        } else {
            null
        }
        requestHeaderScope = requestHeaders.takeIf { it.isNotEmpty() }?.let {
            RequestHeaderScope(android.net.Uri.parse(absoluteUrl), it)
        }
        val subtitleConfigurations =
            subtitleManager.buildSubtitleConfigurations(subtitles, serverUrl)

        val builder = MediaItem.Builder()
            .setUri(absoluteUrl)
            .apply { contentId?.takeIf(String::isNotBlank)?.let(::setMediaId) }
            .setSubtitleConfigurations(subtitleConfigurations)
            .setTag(
                SiloMediaTransformTag(
                    dolbyVisionMode = when {
                        org.siloserver.silo.model.playback.CLIENT_DV7_TO_DV81 in transformations ->
                            DolbyVisionTransformMode.PROFILE7_TO_PROFILE81
                        org.siloserver.silo.model.playback.CLIENT_DV7_TO_HDR10 in transformations ->
                            DolbyVisionTransformMode.PROFILE7_TO_HDR10
                        else -> DolbyVisionTransformMode.DISABLED
                    },
                    expectedDynamicRange = expectedDynamicRange,
                    expectedColorRange = expectedColorRange,
                    dolbyVisionBaseLayerRoute =
                        org.siloserver.silo.model.playback.CLIENT_DV8_BASE_LAYER_FALLBACK_V1_CLAIM in activeClaims,
                ),
            )

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

    private fun isResumableDirectPlayUri(uri: android.net.Uri): Boolean =
        uri == resumableDirectPlayUri

    private fun buildAbsoluteUrl(serverUrl: String, streamUrl: String): String =
        resolvePlaybackStreamUrl(serverUrl, streamUrl)

    private class SiloMediaSourceFactory(
        private val defaultFactory: MediaSource.Factory,
        private val correctedFactory: (
            DolbyVisionTransformMode,
            String?,
            String?,
            Boolean,
        ) -> MediaSource.Factory,
        private val hlsFactory: MediaSource.Factory,
        private val dataSourceFactory: DataSource.Factory,
        private val subtitleParserFactory: SubtitleParser.Factory,
        private val subtitleOffsetProvider: () -> Long,
        private var loadErrorHandlingPolicy: LoadErrorHandlingPolicy,
    ) : MediaSource.Factory {
        private var drmSessionManagerProvider: DrmSessionManagerProvider? = null

        override fun setDrmSessionManagerProvider(
            drmSessionManagerProvider: DrmSessionManagerProvider,
        ): MediaSource.Factory {
            this.drmSessionManagerProvider = drmSessionManagerProvider
            defaultFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
            hlsFactory.setDrmSessionManagerProvider(drmSessionManagerProvider)
            return this
        }

        override fun setLoadErrorHandlingPolicy(
            loadErrorHandlingPolicy: LoadErrorHandlingPolicy,
        ): MediaSource.Factory {
            this.loadErrorHandlingPolicy = loadErrorHandlingPolicy
            defaultFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            hlsFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            return this
        }

        override fun getSupportedTypes(): IntArray = defaultFactory.supportedTypes

        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            val localConfiguration = mediaItem.localConfiguration
                ?: return defaultFactory.createMediaSource(mediaItem)
            val subtitleConfigurations = localConfiguration.subtitleConfigurations
            val contentItem = if (subtitleConfigurations.isEmpty()) {
                mediaItem
            } else {
                mediaItem.buildUpon()
                    .setSubtitleConfigurations(emptyList())
                    .build()
            }
            val contentType = Util.inferContentTypeForUriAndMimeType(
                localConfiguration.uri,
                localConfiguration.mimeType,
            )
            val contentSource = if (contentType == C.CONTENT_TYPE_HLS) {
                hlsFactory.createMediaSource(contentItem)
            } else {
                val tag = localConfiguration.tag as? SiloMediaTransformTag
                mediaSourceFactory(tag).createMediaSource(contentItem)
            }
            if (subtitleConfigurations.isEmpty()) return contentSource

            // Sidecars carry source-timeline cue times, while server-reanchored
            // content and its embedded tracks use a local player timeline.
            // Split every sidecar explicitly so only it receives that delta.
            val sources = mutableListOf(contentSource)
            subtitleConfigurations.mapTo(sources, ::createSubtitleMediaSource)
            return MergingMediaSource(*sources.toTypedArray())
        }

        private fun mediaSourceFactory(tag: SiloMediaTransformTag?): MediaSource.Factory =
            correctedFactory(
                tag?.dolbyVisionMode ?: DolbyVisionTransformMode.DISABLED,
                tag?.expectedDynamicRange,
                tag?.expectedColorRange,
                tag?.dolbyVisionBaseLayerRoute ?: false,
            ).also { factory ->
                drmSessionManagerProvider?.let(factory::setDrmSessionManagerProvider)
                factory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
            }

        private fun createSubtitleMediaSource(
            configuration: MediaItem.SubtitleConfiguration,
        ): MediaSource {
            val baseFormat = androidx.media3.common.Format.Builder()
                .setId(configuration.id)
                .setSampleMimeType(configuration.mimeType)
                .setLanguage(configuration.language)
                .setSelectionFlags(configuration.selectionFlags)
                .setRoleFlags(configuration.roleFlags)
                .setLabel(configuration.label)
                .build()
            if (!subtitleParserFactory.supportsFormat(baseFormat)) {
                return SingleSampleMediaSource.Factory(dataSourceFactory)
                    .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
                    .createMediaSource(configuration, C.TIME_UNSET)
            }
            val outputFormat = baseFormat.buildUpon()
                .setCueReplacementBehavior(
                    subtitleParserFactory.getCueReplacementBehavior(baseFormat),
                )
                .build()
            // Shared with the non-gating wrapper below: it publishes the live
            // position, the extractor treats anything before it as history.
            val playbackFloor = SidecarPlaybackFloor()
            val extractorsFactory = when (configuration.mimeType) {
                MimeTypes.APPLICATION_PGS -> ExtractorsFactory {
                    arrayOf(
                        PgsSupExtractor(
                            subtitleParserFactory,
                            subtitleOffsetProvider,
                            outputFormat,
                            playbackFloor::get,
                        ),
                    )
                }
                MimeTypes.TEXT_VTT -> ExtractorsFactory {
                    arrayOf(
                        StreamingWebvttExtractor(
                            subtitleParserFactory.create(outputFormat),
                            outputFormat,
                            MAX_SUBTITLE_BYTES,
                        ),
                    )
                }
                else -> ExtractorsFactory {
                    arrayOf(
                        SubtitleExtractor(
                            subtitleParserFactory.create(outputFormat),
                            outputFormat,
                        ),
                    )
                }
            }
            val subtitleDataSourceFactory = if (configuration.mimeType in replayableTextSubtitleMimeTypes) {
                ReplayableSubtitleDataSourceFactory(dataSourceFactory)
            } else {
                dataSourceFactory
            }
            val progressive = ProgressiveMediaSource.Factory(subtitleDataSourceFactory, extractorsFactory)
                .setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
                .createMediaSource(
                    MediaItem.Builder()
                        .setUri(configuration.uri)
                        .setMimeType(configuration.mimeType)
                        .build(),
                )
            // A sidecar must not decide when playback starts or what loads
            // next — left as a plain merged child it starves the video until
            // its own download reaches the resume point. See the wrapper.
            return SidecarSubtitleMediaSource(progressive, playbackFloor)
        }
    }

    private companion object {
        val replayableTextSubtitleMimeTypes = setOf(
            MimeTypes.TEXT_SSA,
            MimeTypes.APPLICATION_SUBRIP,
            MimeTypes.APPLICATION_TTML,
        )
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

/**
 * Whether a track reselection must be withheld from the player.
 *
 * ExoPlayer applies a reselection by seeking the current media period. With no
 * media period — idle, or an empty timeline — that seek dereferences a null
 * holder and ends playback with an ExoPlaybackException rather than being a
 * no-op. Capability changes (HDMI hot-plug, audio route loss) can fire while a
 * player is being torn down, which is exactly that window.
 *
 * Kept separate from the player so the rule can be tested without a Context.
 */
internal fun shouldSkipTrackReselection(playbackState: Int, timelineEmpty: Boolean): Boolean =
    playbackState == Player.STATE_IDLE || timelineEmpty
