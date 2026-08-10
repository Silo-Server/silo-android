package org.siloserver.silo.common.player

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import org.siloserver.silo.player.DolbyVisionPolicy
import org.siloserver.silo.common.player.video.media3OriginalPlaybackContainers
import org.siloserver.silo.model.playback.ClientPlaybackContext
import org.siloserver.silo.model.playback.ClientCodecCapabilities
import org.siloserver.silo.model.playback.CAPABILITY_EVIDENCE_EXACT
import org.siloserver.silo.model.playback.CAPABILITY_EVIDENCE_PLATFORM_ATTESTED
import org.siloserver.silo.model.playback.DELIVERY_CLASS_HLS
import org.siloserver.silo.model.playback.DELIVERY_CLASS_ORIGINAL_HTTP
import org.siloserver.silo.model.playback.DELIVERY_CLASS_PROGRESSIVE
import org.siloserver.silo.model.playback.DeliveryCapability
import org.siloserver.silo.model.playback.DeliverySubtitleCapabilities
import org.siloserver.silo.model.playback.CLIENT_DV8_HDR10_PLUS_SANITIZER
import org.siloserver.silo.model.playback.CLIENT_POST_RESUME_VIDEO_RECOVERY
import org.siloserver.silo.model.playback.CLIENT_SURFACE_RECOVERY
import org.siloserver.silo.model.playback.CLIENT_DV7_TO_DV81
import org.siloserver.silo.model.playback.CLIENT_DV7_TO_HDR10
import org.siloserver.silo.model.playback.CLIENT_DV_TRANSFORM_RECIPE_VERSION
import org.siloserver.silo.model.playback.PlaybackDeviceContext
import org.siloserver.silo.model.playback.PlaybackTransformationExecutor
import org.siloserver.silo.model.playback.PlaybackTransformationV3
import org.siloserver.silo.model.playback.PlaybackOutputContext
import kotlinx.coroutines.flow.StateFlow
import org.siloserver.silo.libass.LibassBridge

/**
 * Orchestrates the three probes — [MediaCodecCapabilitiesProbe] (video + HDR),
 * [DisplayHdrProbe] (panel), and [AudioCapabilityManager] (audio sink) — into
 * a single [ClientCodecCapabilities] payload for the server's playback
 * resolver.
 *
 * Responsibilities:
 * - Enumerate software-decodable audio codecs from `MediaCodecList` so the
 *   server still picks a compatible track when no passthrough sink is
 *   attached (phone speaker, headphones, Bluetooth).
 * - Reconcile codec-level HDR profiles with the panel's advertised HDR types
 *   and narrowly scoped device output quirks.
 * - Populate `audioPassthrough` from the live [AudioCapabilityManager] state.
 */
class PlaybackCapabilityDetector(
    private val context: Context,
    private val audioCapabilityManager: AudioCapabilityManager,
    private val libassBridge: LibassBridge,
) {
    val outputRouteGeneration: StateFlow<Long> = audioCapabilityManager.outputRouteGeneration
    private val planningSnapshots = PlaybackPlanningSnapshotRegistry(
        maxSize = MAX_RETAINED_PLANNING_SNAPSHOTS,
    )
    // Platform software-audio decoders are static for the process; cache the
    // MediaCodecList enumeration for callers that need a fresh snapshot later.
    @Volatile
    private var cachedPlatformSoftwareAudioProbe: PlatformSoftwareAudioProbe? = null
    /**
     * Inspect the resolved [Tracks] object (emitted by `Player.Listener.onTracksChanged`)
     * and declare whether direct play can proceed. Looks at the selected video
     * format's `codecs` string for the DV profile number, and the selected
     * audio format's MIME + channel count against the current audio sink.
     *
     * Only the *selected* track matters — ExoPlayer's track selector has
     * already honored [TrackSelectionPresets], so if the selector chose a
     * track the renderer can't actually feed, we need to fall back. Unselected
     * tracks can be ignored.
     */
    @UnstableApi
    fun evaluateTracks(tracks: Tracks): Playability {
        // Video — look for DV profile claims in Format.codecs.
        val selectedVideo = tracks.groups.firstOrNull {
            it.type == C.TRACK_TYPE_VIDEO && it.isSelected
        }?.selectedFormat()
        if (selectedVideo != null) {
            val codec = selectedVideo.codecs.orEmpty().lowercase()
            // DV codec strings:
            //   P5 — `dvhe.05.x` (single-layer DV, no HDR10/SDR fallback)
            //   P7 — `dvhe.07.x` (dual-layer; needs multi-instance HEVC)
            //   P8 — `dvhe.08.x` / `dvh1.08.x` (single-layer DV with an
            //        HDR10 (8.1) / SDR (8.2) / HLG (8.4) base — any
            //        HEVC Main10/HDR10 decoder can render the base layer
            //        when no DV decoder is present, which is how Plex and
            //        AVPlayer direct-play P8 on devices that don't ship a
            //        Dolby Vision decoder. Bouncing P8 to a transcoded
            //        stream is wrong on a server that blocks 4K transcodes
            //        — and unnecessary on hardware that can already
            //        produce HDR10 frames from the base layer.)
            val dvMatch = Regex("""dv(he|h1|av|a1)\.(\d{2})""").find(codec)
            if (dvMatch != null) {
                val profile = dvMatch.groupValues[2].toIntOrNull()
                if (profile != null) {
                    val codecProbe = MediaCodecCapabilitiesProbe.probe()
                    val displayHdr = DisplayHdrProbe.probe(context)
                    val supportedHdr = TvPlaybackOutputPolicy.effectiveHdrCapabilities(
                        codec = codecProbe.hdr,
                        display = displayHdr,
                    )
                    val supported = isDirectPlayableDolbyVisionProfile(profile, supportedHdr)
                    if (!supported) return Playability.UnsupportedDvProfile(profile)
                }
            }
        }

        // Audio — check MIME type and channel count against the passthrough
        // caps. The renderer can software-decode AAC / AC3 / E-AC3 / etc. on
        // any API-26+ device, but TrueHD / DTS-HD have no AOSP software
        // decoder — if the sink can't passthrough them, we can't play them.
        val selectedAudio = tracks.groups.firstOrNull {
            it.type == C.TRACK_TYPE_AUDIO && it.isSelected
        }?.selectedFormat()
        if (selectedAudio != null) {
            val mime = selectedAudio.sampleMimeType.orEmpty()
            val channels = selectedAudio.channelCount
            val passthroughCodecs = audioCapabilityManager.capabilities.value.passthroughCodecs.toSet()
            val maxChannels = audioCapabilityManager.capabilities.value.maxChannels

            val rendererCanDecode = isSoftwareDecodableAudioMime(
                mime = mime,
                ffmpegAvailable = FfmpegAudioSupport.isAvailable(),
            )
            val sinkCanPassthrough = when (mime) {
                MimeTypes.AUDIO_TRUEHD -> "truehd" in passthroughCodecs
                MimeTypes.AUDIO_DTS_HD -> "dts_hd" in passthroughCodecs
                MimeTypes.AUDIO_DTS -> "dts" in passthroughCodecs
                MimeTypes.AUDIO_AC4 -> "ac4" in passthroughCodecs
                else -> false
            }

            if (!rendererCanDecode && !sinkCanPassthrough) {
                return Playability.UnsupportedAudioCodec(mime)
            }
            if (channels > 0 && channels > maxChannels && !rendererCanDecode) {
                return Playability.UnsupportedChannelCount(mime, channels)
            }
        }

        return Playability.Supported
    }

    /**
     * @param ffmpegAvailable overridable for tests; production callers omit
     * this to let [FfmpegAudioSupport.isAvailable] probe the real classpath.
     * On phones, FFmpeg codecs are appended to
     * [ClientCodecCapabilities.codecsAudio]. TV planning advertises platform
     * decoders plus the active sink's separate passthrough capabilities so
     * extension-only PCM fallback cannot preempt synchronized audio adaptation.
     */
    fun detect(
        ffmpegAvailable: Boolean = FfmpegAudioSupport.isAvailable(),
        dolbyVision: DolbyVisionPolicy.Snapshot = DolbyVisionPolicy.Snapshot(),
    ): ClientCodecCapabilities {
        val audioRoute = audioCapabilityManager.playbackRouteSnapshot()
        val codecProbe = MediaCodecCapabilitiesProbe.probe()
        val displayHdr = DisplayHdrProbe.probe(context)
        // With Dolby Vision off, stop advertising DV profiles (except 5,
        // which has no watchable base layer) so the server plans base-layer /
        // HDR10 delivery and local direct-play checks agree. Single decision
        // source: DolbyVisionPolicy (Apple parity, silo-apple e9bd775).
        val intersectedHdr = TvPlaybackOutputPolicy.effectiveHdrCapabilities(
            codec = codecProbe.hdr,
            display = displayHdr,
        ).let { hdr ->
            hdr.copy(
                dolbyVisionProfiles = DolbyVisionPolicy.advertisableProfiles(
                    hdr.dolbyVisionProfiles,
                    dolbyVision,
                ),
            )
        }

        val platformAudio = detectPlatformSoftwareAudioCodecs()
        val softwareAudio = advertisedAudioDecodeCodecs(
            platformCodecs = platformAudio.codecs,
            ffmpegAvailable = ffmpegAvailable,
            isTv = TvModeDetector.isTv(context),
        )
        val passthrough = audioRoute.capabilities
        val hasAnyHdr = intersectedHdr.hdr10 ||
            intersectedHdr.hdr10Plus ||
            intersectedHdr.hlg ||
            intersectedHdr.dolbyVisionProfiles.isNotEmpty()

        val detected = ClientCodecCapabilities(
            // Stated rather than defaulted: both lists below come from a
            // MediaCodecList probe of the concrete profile/level/bit-depth
            // tuples this device reports, which is what "exact" claims. If a
            // future path ever fabricates part of them, the tier has to drop
            // here — the server strictly validates plans against exact
            // evidence, and only exact evidence earns audio passthrough.
            videoEvidence = CAPABILITY_EVIDENCE_EXACT,
            audioEvidence = if (platformAudio.exact) {
                CAPABILITY_EVIDENCE_EXACT
            } else {
                CAPABILITY_EVIDENCE_PLATFORM_ATTESTED
            },
            codecsVideo = codecProbe.videoCodecs.toList(),
            codecsVideoHardware = codecProbe.videoCodecs.toList(),
            // This list is decode-only. Encoded formats accepted by the
            // current HDMI/USB route belong exclusively in audioPassthrough;
            // mixing the two prevents the V3 server from proving passthrough.
            codecsAudio = softwareAudio,
            containers = media3OriginalPlaybackContainers,
            maxResolution = codecProbe.maxResolution,
            hdr = hasAnyHdr,
            hdrDetails = intersectedHdr,
            audioPassthrough = passthrough,
            videoDecode = codecProbe.videoDecodeCapabilities,
        )
        planningSnapshots.remember(detected, audioRoute)
        return detected
    }

    /**
     * The form factor implied by the current UI mode, for callers that live in
     * `android-shared` and so cannot see either app's `BuildConfig`. The app
     * modules pass their own literal ("mobile" / "tv") because they know it
     * statically; shared players (the audiobook one) call this instead of
     * guessing.
     */
    fun detectedFormFactor(): String = androidFormFactor(context)

    /** The installed version name, for the same shared callers. */
    fun detectedAppVersion(): String = androidAppVersion(context)

    fun detectPlaybackContext(
        formFactor: String = detectedFormFactor(),
        appVersion: String = detectedAppVersion(),
        ffmpegAvailable: Boolean = FfmpegAudioSupport.isAvailable(),
        dolbyVision: DolbyVisionPolicy.Snapshot = DolbyVisionPolicy.Snapshot(),
        capabilities: ClientCodecCapabilities? = null,
    ): ClientPlaybackContext {
        val caps = capabilities ?: detect(ffmpegAvailable, dolbyVision)
        val audioRoute = planningSnapshots.resolve(
            capabilities = caps,
            currentRoute = audioCapabilityManager.playbackRouteSnapshot(),
        )
        val passthrough = caps.audioPassthrough
        val decodeAudio = caps.codecsAudio
        val libassRendering = libassBridge.isRenderingSupported
        val libassEmbeddedFonts = libassBridge.isEmbeddedFontsSupported
        val libassDirectFidelity = libassRendering && libassEmbeddedFonts
        val clientVideoTransformations = advertisedClientDolbyVisionTransformations(
            hdrDetails = caps.hdrDetails,
            nativeRpuConverterAvailable = NativeDolbyVisionRpuConverter.isAvailable,
        )
        return ClientPlaybackContext(
            formFactor = formFactor,
            appVersion = appVersion,
            device = PlaybackDeviceContext(
                platform = "android",
                osVersion = Build.VERSION.RELEASE,
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                // Everything below is Android-shaped detail the neutral
                // contract does not model. It exists for device quirks and
                // support diagnostics, so it goes in the free-form bag rather
                // than growing platform-specific fields on the wire type.
                platformDetails = androidPlatformDetails(),
            ),
            output = PlaybackOutputContext(
                hdrDetails = caps.hdrDetails,
                audioPassthrough = passthrough,
                currentSink = if (passthrough?.passthroughCodecs?.isNotEmpty() == true) "passthrough_sink" else "local_output",
                sinkType = audioRoute.sinkType,
                // Opaque to the server, which only ever compares it for
                // equality. Android's route generation counter is exactly that:
                // it changes when the audio route changes and nothing else.
                outputContextId = audioRoute.routeGeneration.toString(),
            ),
            deliveries = mapOf(
                DELIVERY_CLASS_ORIGINAL_HTTP to DeliveryCapability(
                    enabled = true,
                    supportedOnDevice = true,
                    containers = media3OriginalPlaybackContainers,
                    videoCodecs = caps.codecsVideo,
                    audioDecodeCodecs = decodeAudio,
                    audioPassthroughCodecs = passthrough?.passthroughCodecs.orEmpty(),
                    maxChannels = passthrough?.maxChannels,
                    hdrDetails = caps.hdrDetails,
                    subtitles = DeliverySubtitleCapabilities(
                        embeddedText = true,
                        sidecarText = true,
                        assStyling = libassDirectFidelity,
                        // Media3's DefaultSubtitleParserFactory decodes the
                        // three embedded bitmap families carried by our
                        // direct-play containers: PGS, VobSub/DVD, and DVB.
                        // Sidecar bitmap is on too: SubtitleManager mounts the
                        // server's raw `.sup` extract as a MediaItem sidecar,
                        // so a bitmap track no longer has to be burned in.
                        embeddedBitmap = true,
                        sidecarBitmap = true,
                        fontAttachments = libassEmbeddedFonts,
                    ),
                    features = buildList {
                        addAll(listOf("track_switching", "audio_delay", "subtitle_delay", "buffer_reporting"))
                        add(CLIENT_DV8_HDR10_PLUS_SANITIZER)
                        add(CLIENT_POST_RESUME_VIDEO_RECOVERY)
                        add(CLIENT_SURFACE_RECOVERY)
                        if (libassDirectFidelity) add("libass_subtitles")
                    },
                    transformations = clientVideoTransformations,
                    authHeaderRefresh = true,
                    validatedClaims = emptyList(),
                ),
                DELIVERY_CLASS_PROGRESSIVE to DeliveryCapability(
                    enabled = false,
                    supportedOnDevice = false,
                    failureReason = "disabled_pending_seekable_transport",
                    containers = listOf("mp4", "m4v", "webm", "mkv", "matroska"),
                    videoCodecs = caps.codecsVideo,
                    audioDecodeCodecs = decodeAudio,
                    audioPassthroughCodecs = passthrough?.passthroughCodecs.orEmpty(),
                    maxChannels = passthrough?.maxChannels,
                    hdrDetails = caps.hdrDetails,
                    subtitles = DeliverySubtitleCapabilities(
                        embeddedText = true,
                        sidecarText = true,
                    ),
                    features = listOf(
                        "progressive",
                        "track_switching",
                        "buffer_reporting",
                        CLIENT_DV8_HDR10_PLUS_SANITIZER,
                        CLIENT_POST_RESUME_VIDEO_RECOVERY,
                        CLIENT_SURFACE_RECOVERY,
                    ),
                    authHeaderRefresh = true,
                    validatedClaims = emptyList(),
                ),
                DELIVERY_CLASS_HLS to DeliveryCapability(
                    enabled = true,
                    supportedOnDevice = true,
                    containers = listOf("m3u8", "hls"),
                    videoCodecs = caps.codecsVideo,
                    audioDecodeCodecs = decodeAudio,
                    audioPassthroughCodecs = passthrough?.passthroughCodecs.orEmpty(),
                    maxChannels = passthrough?.maxChannels,
                    hdrDetails = caps.hdrDetails,
                    subtitles = DeliverySubtitleCapabilities(
                        embeddedText = true,
                        sidecarText = true,
                        assStyling = libassRendering,
                        // The transport carries no subtitle track, but the
                        // server raw-serves the embedded PGS as a `.sup`
                        // sidecar and Media3 parses it — so bitmap subtitles
                        // render here without a burn-in transcode.
                        embeddedBitmap = true,
                        sidecarBitmap = true,
                    ),
                    features = buildList {
                        addAll(listOf("hls", "track_switching", "buffer_reporting"))
                        add(CLIENT_DV8_HDR10_PLUS_SANITIZER)
                        add(CLIENT_POST_RESUME_VIDEO_RECOVERY)
                        add(CLIENT_SURFACE_RECOVERY)
                        if (libassRendering) add("libass_subtitles")
                    },
                    authHeaderRefresh = true,
                    validatedClaims = emptyList(),
                ),
            ),
        )
    }

    /**
     * The Android-specific half of the device description, as a flat string map.
     *
     * The server bounds this at 16 entries with keys and values under 128
     * characters, so keep it to the fields device quirks actually match on.
     */
    private fun androidPlatformDetails(): Map<String, String> = buildMap {
        fun putBounded(key: String, value: String) {
            put(key, value.take(MAX_PLATFORM_DETAIL_CHARS))
        }

        Build.BRAND?.let { putBounded("brand", it) }
        Build.DEVICE?.let { putBounded("device", it) }
        Build.PRODUCT?.let { putBounded("product", it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MANUFACTURER?.let { putBounded("soc_manufacturer", it) }
            Build.SOC_MODEL?.let { putBounded("soc_model", it) }
        }
        Build.ID?.let { putBounded("build_id", it) }
        Build.DISPLAY?.let { putBounded("build_display", it) }
        Build.VERSION.SECURITY_PATCH?.let { putBounded("security_patch", it) }
        putBounded("sdk_int", Build.VERSION.SDK_INT.toString())
        Build.SUPPORTED_ABIS?.toList()?.takeIf { it.isNotEmpty() }
            ?.let { putBounded("abis", it.joinToString(",")) }
    }

    /**
     * Derives the form factor from the current UI mode. Mirrors the diagnostics
     * collector's classification so a device reports the same shape to the
     * playback contract and to support bundles.
     */
    private fun androidFormFactor(context: Context): String {
        val uiMode = (context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)?.currentModeType
        return when {
            uiMode == Configuration.UI_MODE_TYPE_TELEVISION -> "tv"
            uiMode == Configuration.UI_MODE_TYPE_WATCH -> "watch"
            uiMode == Configuration.UI_MODE_TYPE_CAR -> "automotive"
            context.resources.configuration.smallestScreenWidthDp >= 600 -> "tablet"
            else -> "mobile"
        }
    }

    private fun androidAppVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: "unknown"

    /** Returns codecs backed by an Android platform [MediaCodec] decoder. */
    private fun detectPlatformSoftwareAudioCodecs(): PlatformSoftwareAudioProbe {
        cachedPlatformSoftwareAudioProbe?.let { return it }
        val probe = runCatching {
            val result = mutableSetOf<String>()
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (info.isEncoder) continue
                for (type in info.supportedTypes) {
                    when {
                        type.equals(MediaFormat.MIMETYPE_AUDIO_AAC, ignoreCase = true) -> result += "aac"
                        type.equals(MediaFormat.MIMETYPE_AUDIO_AC3, ignoreCase = true) -> result += "ac3"
                        type.equals(MediaFormat.MIMETYPE_AUDIO_EAC3, ignoreCase = true) -> result += "eac3"
                        type.equals(MediaFormat.MIMETYPE_AUDIO_EAC3_JOC, ignoreCase = true) -> result += "eac3_joc"
                        type.equals(MediaFormat.MIMETYPE_AUDIO_FLAC, ignoreCase = true) -> result += "flac"
                        type.equals(MediaFormat.MIMETYPE_AUDIO_OPUS, ignoreCase = true) -> result += "opus"
                        type.equals(MediaFormat.MIMETYPE_AUDIO_VORBIS, ignoreCase = true) -> result += "vorbis"
                        type.equals(MediaFormat.MIMETYPE_AUDIO_MPEG, ignoreCase = true) -> result += "mp3"
                    }
                }
            }
            PlatformSoftwareAudioProbe(codecs = result.toList(), exact = true)
        }.getOrElse {
            PlatformSoftwareAudioProbe(codecs = listOf("aac", "mp3"), exact = false)
        }
        cachedPlatformSoftwareAudioProbe = probe
        return probe
    }

    private data class PlatformSoftwareAudioProbe(
        val codecs: List<String>,
        val exact: Boolean,
    )

    private companion object {
        const val MAX_PLATFORM_DETAIL_CHARS = 128
        const val MAX_RETAINED_PLANNING_SNAPSHOTS = 32
    }
}

/**
 * Retains the route evidence captured with a capability object so planning
 * context cannot combine that object with a route change that happened later.
 * Capability equality is intentionally insufficient: two routes may expose
 * identical codecs while still requiring distinct output context identities.
 */
internal class PlaybackPlanningSnapshotRegistry(
    private val maxSize: Int,
) {
    private val snapshots = ArrayDeque<Pair<ClientCodecCapabilities, AudioPlaybackRouteSnapshot>>()

    init {
        require(maxSize > 0)
    }

    @Synchronized
    fun remember(
        capabilities: ClientCodecCapabilities,
        route: AudioPlaybackRouteSnapshot,
    ) {
        snapshots.addLast(capabilities to route)
        while (snapshots.size > maxSize) {
            snapshots.removeFirst()
        }
    }

    @Synchronized
    fun resolve(
        capabilities: ClientCodecCapabilities,
        currentRoute: AudioPlaybackRouteSnapshot,
    ): AudioPlaybackRouteSnapshot =
        snapshots.lastOrNull { (planned, _) -> planned === capabilities }?.second ?: currentRoute
}

/**
 * Audio decoders safe to advertise to the server's route planner.
 *
 * Media3's FFmpeg [androidx.media3.exoplayer.audio.DecoderAudioRenderer] emits
 * PCM and does not report tunneling support. On TV, advertising extension-only
 * codecs such as TrueHD therefore makes the server send original audio into a
 * software-timed HDMI path even when it could copy the video and adapt only the
 * audio to a platform-synchronized format. Keep FFmpeg available as a runtime
 * fallback, but advertise only platform decoders on TV; encoded formats the
 * active sink can carry remain represented separately by `audioPassthrough`.
 */
internal fun advertisedAudioDecodeCodecs(
    platformCodecs: List<String>,
    ffmpegAvailable: Boolean,
    isTv: Boolean,
): List<String> {
    val ffmpegCodecs = if (ffmpegAvailable && !isTv) {
        FfmpegAudioSupport.codecShortCodes
    } else {
        emptyList()
    }
    return (platformCodecs + ffmpegCodecs).distinct()
}

/**
 * Client-side Dolby Vision transformations safe to expose to the v3 planner.
 *
 * A packaged converter and a compatible output range are prerequisites, not
 * end-to-end evidence. In particular, the SM-F976U1 can decode HDR10 and run
 * the packaged RPU bridge, yet a transformed Profile 7 stream renders one
 * frame and then makes no forward progress. Advertising the transformation in
 * that state makes every fresh session select the same unusable route before
 * runtime recovery can ask the server for its validated transformation.
 *
 * Keep the default evidence set empty. A transformation may be added only
 * after the playback fixture matrix validates the complete extractor,
 * transformation, decoder, and display path for the Android device class.
 */
internal fun advertisedClientDolbyVisionTransformations(
    hdrDetails: org.siloserver.silo.model.playback.HdrCapabilities?,
    nativeRpuConverterAvailable: Boolean,
    fixtureValidatedTransformations: Set<String> = emptySet(),
): List<PlaybackTransformationV3> = buildList {
    if (
        CLIENT_DV7_TO_DV81 in fixtureValidatedTransformations &&
        8 in hdrDetails?.dolbyVisionProfiles.orEmpty() &&
        nativeRpuConverterAvailable
    ) {
        add(
            PlaybackTransformationV3(
                name = CLIENT_DV7_TO_DV81,
                executor = PlaybackTransformationExecutor.CLIENT,
                recipeVersion = CLIENT_DV_TRANSFORM_RECIPE_VERSION,
                validatedClaims = listOf(
                    "profile7_rpu_converted_to_profile81",
                    "hdr10_base_layer_preserved",
                    "enhancement_layer_discarded",
                ),
            ),
        )
    }
    if (
        CLIENT_DV7_TO_HDR10 in fixtureValidatedTransformations &&
        hdrDetails?.hdr10 == true
    ) {
        add(
            PlaybackTransformationV3(
                name = CLIENT_DV7_TO_HDR10,
                executor = PlaybackTransformationExecutor.CLIENT,
                recipeVersion = CLIENT_DV_TRANSFORM_RECIPE_VERSION,
                validatedClaims = listOf(
                    "dolby_vision_metadata_removed",
                    "hdr10_base_layer_preserved",
                    "enhancement_layer_discarded",
                ),
            ),
        )
    }
}

@UnstableApi
private fun Tracks.Group.selectedFormat() =
    (0 until length)
        .firstOrNull { isTrackSelected(it) }
        ?.let { getTrackFormat(it) }
        ?: if (mediaTrackGroup.length > 0) mediaTrackGroup.getFormat(0) else null

internal fun isDirectPlayableDolbyVisionProfile(
    profile: Int,
    supportedHdr: org.siloserver.silo.model.playback.HdrCapabilities,
): Boolean = supportedHdr.dolbyVisionProfiles.contains(profile)

internal fun isSoftwareDecodableAudioMime(
    mime: String,
    ffmpegAvailable: Boolean,
): Boolean =
    mime in platformSoftwareDecodableAudioMimes ||
        (ffmpegAvailable && mime in FfmpegAudioSupport.mimeTypes)

private val platformSoftwareDecodableAudioMimes = setOf(
    MimeTypes.AUDIO_AAC,
    MimeTypes.AUDIO_AC3,
    MimeTypes.AUDIO_E_AC3,
    MimeTypes.AUDIO_E_AC3_JOC,
    MimeTypes.AUDIO_FLAC,
    MimeTypes.AUDIO_OPUS,
    MimeTypes.AUDIO_VORBIS,
    MimeTypes.AUDIO_MPEG,
)
