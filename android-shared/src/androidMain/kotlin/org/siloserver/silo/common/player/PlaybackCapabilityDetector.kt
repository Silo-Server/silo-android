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
import org.siloserver.silo.common.network.SiloClientBuildIdentity
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
import org.siloserver.silo.model.playback.NATIVE_HLS_PLAYBACK_V1_FEATURE
import org.siloserver.silo.model.playback.PlaybackDeviceContext
import org.siloserver.silo.model.playback.PlaybackTransformationExecutor
import org.siloserver.silo.model.playback.PlaybackTransformationV3
import org.siloserver.silo.model.playback.PlaybackOutputContext
import org.siloserver.silo.model.playback.AudioPassthroughCapabilities
import org.siloserver.silo.model.playback.AudioPassthroughEntry
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
    /**
     * Public so the Cast path can report the same build and channel this
     * detector puts on a local session — `CastPrepareRequest` describes the
     * phone driving the cast, not the receiver.
     */
    val buildIdentity: SiloClientBuildIdentity,
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
            // ONE snapshot: read three times, a route change mid-check could
            // mix one snapshot's codec list with another's channel limits.
            val routeCaps = audioCapabilityManager.capabilities.value
            val maxChannels = routeCaps.maxChannels

            val rendererCanDecode = canDecodeAudio(
                mime = mime,
                channelCount = channels,
                platformDecoders = detectPlatformSoftwareAudioCodecs().decoders,
                ffmpegAvailable = FfmpegAudioSupport.isAvailable(),
            )
            // A sink that carries the encoded stream bypasses the decoder
            // entirely, so its channel limit is irrelevant. AC-3/E-AC-3/JOC were
            // missing here: harmless while the decoder was assumed able to take
            // anything, but a false refusal the moment that assumption is
            // dropped — an E-AC-3-capable receiver plays 5.1 fine behind a
            // stereo-only decoder.
            // Asked per codec AND per layout: the sink carrying this codec says
            // nothing about it carrying this many channels of it. JOC tries its
            // own entry first and then plain E-AC-3 — picking by codec presence
            // alone refused a JOC stream whose layout only the E-AC-3 entry
            // covered, which Android explicitly permits.
            val passthroughCandidates = when (mime) {
                MimeTypes.AUDIO_TRUEHD -> listOf("truehd")
                MimeTypes.AUDIO_DTS_HD -> listOf("dts_hd")
                MimeTypes.AUDIO_DTS -> listOf("dts")
                MimeTypes.AUDIO_AC4 -> listOf("ac4")
                MimeTypes.AUDIO_AC3 -> listOf("ac3")
                MimeTypes.AUDIO_E_AC3 -> listOf("eac3")
                MimeTypes.AUDIO_E_AC3_JOC -> listOf("eac3_joc", "eac3")
                else -> emptyList()
            }
            val sinkCanPassthrough = passthroughCandidates.any {
                sinkCanPassthrough(it, channels, routeCaps)
            }

            // Absent codec and unusable channel layout are different verdicts:
            // the first tells the viewer their device cannot play this format at
            // all, the second that it cannot play this LAYOUT — and the server
            // picks a different fallback for each. Deciding on the
            // channel-aware answer alone reported every Pixel channel failure
            // as an unsupported encoding.
            val codecKnownAtAll = platformCanDecodeAudio(
                mime = mime,
                channelCount = 0,
                platformDecoders = detectPlatformSoftwareAudioCodecs().decoders,
            ) || (FfmpegAudioSupport.isAvailable() && mime in FfmpegAudioSupport.mimeTypes)

            if (!codecKnownAtAll && !sinkCanPassthrough) {
                return Playability.UnsupportedAudioCodec(mime)
            }
            if (!rendererCanDecode && !sinkCanPassthrough) {
                return Playability.UnsupportedChannelCount(mime, channels)
            }
            // rendererCanDecode is now channel-aware, so a decoder that exists
            // but cannot take this many channels no longer excuses the sink
            // from having to carry the track. That was the bug: a 5.1 E-AC3
            // track was declared playable by a two-channel decoder and failed
            // at the codec once playback had already begun.
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
            // Taken from the injected identity rather than a per-caller
            // argument, so the shared audiobook player reports the same build
            // and channel as the two video players instead of omitting them.
            appBuild = buildIdentity.reportedBuildNumber,
            appChannel = buildIdentity.reportedChannel,
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
                        // This delivery is consumed by Media3's native HLS
                        // pipeline, not a MediaSource implementation such as
                        // hls.js. The server can therefore use the hvc1/dvh1
                        // sample-entry recipes that Media3 accepts.
                        add(NATIVE_HLS_PLAYBACK_V1_FEATURE)
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

    /**
     * Returns codecs backed by an Android platform [MediaCodec] decoder,
     * together with how many channels each decoder will actually accept.
     *
     * The channel limit is the point. A device can advertise an E-AC3 decoder
     * that only takes two channels, and asking it to decode a 5.1 track fails
     * at the codec with ERROR_CODE_DECODING_FAILED — after playback has already
     * started. MIME presence alone cannot answer "can this device play this
     * track", so it is not collected alone.
     */
    private fun detectPlatformSoftwareAudioCodecs(): PlatformSoftwareAudioProbe {
        cachedPlatformSoftwareAudioProbe?.let { return it }
        val probe = runCatching {
            val decoders = mutableListOf<PlatformAudioDecodeCapability>()
            for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
                if (info.isEncoder) continue
                for (type in info.supportedTypes) {
                    val codec = platformAudioCodecName(type) ?: continue
                    // An unreadable limit is recorded as unknown, distinct from
                    // a stated one. The preflight then treats unknown as
                    // permissive (see canDecodeAudio) — a filter that refused
                    // every device which will not state a limit would reject a
                    // great deal that plays.
                    val maxChannels = runCatching {
                        info.getCapabilitiesForType(type).audioCapabilities?.maxInputChannelCount
                    }.getOrNull()?.takeIf { it > 0 }
                    decoders += PlatformAudioDecodeCapability(
                        mimeType = type,
                        codec = codec,
                        decoderName = info.name.orEmpty(),
                        maxInputChannelCount = maxChannels,
                    )
                }
            }
            PlatformSoftwareAudioProbe(decoders = decoders, exact = true)
        }.getOrElse {
            // A failed probe is a guess, and a guess must never be described as
            // exact evidence — the server grants passthrough on that word.
            PlatformSoftwareAudioProbe(
                decoders = listOf(
                    PlatformAudioDecodeCapability(MimeTypes.AUDIO_AAC, "aac", "", null),
                    PlatformAudioDecodeCapability(MimeTypes.AUDIO_MPEG, "mp3", "", null),
                ),
                exact = false,
            )
        }
        cachedPlatformSoftwareAudioProbe = probe
        return probe
    }

    private data class PlatformSoftwareAudioProbe(
        val decoders: List<PlatformAudioDecodeCapability>,
        val exact: Boolean,
    ) {
        val codecs: List<String> get() = decoders.map { it.codec }.distinct()
    }

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

/**
 * Whether the connected sink will carry [codec] as an encoded stream at
 * [channelCount] channels.
 *
 * Uses the exact per-codec entries when the route probe produced them, because
 * the aggregate `maxChannels` is a maximum across ALL codecs: a receiver taking
 * eight-channel TrueHD but only six-channel E-AC-3 reports eight, which would
 * wave through an eight-channel E-AC-3 track its own E-AC-3 entry excludes.
 *
 * Falls back to the aggregate where no entries exist — pre-API-29 routes cannot
 * be probed per format, and refusing everything there would be worse than the
 * imprecision.
 */
internal fun sinkCanPassthrough(
    codec: String,
    channelCount: Int,
    capabilities: AudioPassthroughCapabilities,
): Boolean {
    if (codec !in capabilities.passthroughCodecs) return false
    if (channelCount <= 0) return true
    val exactCounts = capabilities.entries
        .firstOrNull { it.codec == codec }
        ?.channelCounts
        ?.takeIf { it.isNotEmpty() }
        ?: return channelCount <= capabilities.maxChannels

    if (channelCount in exactCounts) return true
    // An entry lists what was PROBED, not everything the sink accepts: only
    // 2/6/8 are ever tried. Absence is a refusal for those three — they were
    // asked and said no. Any other layout was never put to the sink, so it is
    // judged against THIS codec's highest known-good count.
    //
    // Deliberately not the aggregate maxChannels: that is a maximum across all
    // codecs, so a receiver doing 8-channel TrueHD would have vouched for
    // 7-channel E-AC-3 on a sink whose own E-AC-3 probe stopped at 6. Erring
    // toward refusal here costs a transcode; erring the other way costs broken
    // audio after playback has started.
    if (channelCount in PROBED_PASSTHROUGH_CHANNEL_COUNTS) return false
    return channelCount <= exactCounts.max()
}

/**
 * Channel counts [AudioCapabilityManager] actually probes per encoded format.
 * Only for these does an entry's silence mean "no"; anything else was never
 * asked.
 *
 * DERIVED from the probe list rather than restated, so reader and writer cannot
 * drift. The previous version asserted the match with a check() in the probe
 * itself — which would have thrown inside capability detection on any API 29+
 * device if the two ever disagreed. A structural invariant is not worth
 * crashing playback setup over.
 */
@UnstableApi
internal val PROBED_PASSTHROUGH_CHANNEL_COUNTS: Set<Int> =
    PASSTHROUGH_LAYOUT_PROBES.mapTo(mutableSetOf()) { it.channelCount }

/** One platform decoder's claim about one MIME type. */
internal data class PlatformAudioDecodeCapability(
    val mimeType: String,
    val codec: String,
    val decoderName: String,
    /** Null when the device would not say — recorded as unknown, not as a limit. */
    val maxInputChannelCount: Int?,
)

/**
 * Whether this device can decode [mime] at [channelCount] channels.
 *
 * Replaces a hardcoded MIME list that always claimed E-AC3 and E-AC3 JOC were
 * decodable regardless of the device or the track. A Pixel whose E-AC3 decoder
 * accepts two channels reported a 5.1 track as playable, and the failure only
 * surfaced as ERROR_CODE_DECODING_FAILED after playback started — then the
 * recovery replan made the same claim and chose the same route again.
 *
 * Any matching decoder is enough: several can expose the same MIME with
 * different limits, and the widest one is the one that would be used. A limit
 * is never borrowed from a different MIME, and JOC stays a separate claim from
 * plain E-AC3 unless the device actually advertises it.
 *
 * An unknown [channelCount] (non-positive) asks only whether the codec exists —
 * there is nothing to compare against, and refusing on that basis would reject
 * tracks that play fine.
 */
internal fun canDecodeAudio(
    mime: String,
    channelCount: Int,
    platformDecoders: List<PlatformAudioDecodeCapability>,
    ffmpegAvailable: Boolean,
): Boolean {
    if (platformCanDecodeAudio(mime, channelCount, platformDecoders)) return true
    // FFmpeg genuinely rescues a format the platform decoder cannot take.
    // EXTENSION_RENDERER_MODE_ON puts the platform renderer FIRST, but order is
    // only the tie-break: MappingTrackSelector picks the renderer reporting the
    // greatest format support, and MediaCodecAudioRenderer answers
    // FORMAT_EXCEEDS_CAPABILITIES for a channel count its decoder will not take
    // while FfmpegAudioRenderer answers FORMAT_HANDLED. So the extension is not
    // limited to codecs the platform lacks entirely.
    return ffmpegAvailable && mime in FfmpegAudioSupport.mimeTypes
}

/**
 * Whether a platform decoder alone can take [mime] at [channelCount].
 *
 * Separate from [canDecodeAudio] so a caller can tell "this device has no
 * decoder for this codec at all" from "it has one that will not take this many
 * channels" — those are different answers for the viewer and different
 * fallbacks for the server.
 *
 * Media3 soft-matches E-AC3 JOC onto a plain E-AC3 decoder
 * (`MediaCodecUtil.getAlternativeCodecMimeType`), so a JOC track is accepted by
 * an E-AC3 decoder here too; refusing it would reject content Media3 plays.
 */
internal fun platformCanDecodeAudio(
    mime: String,
    channelCount: Int,
    platformDecoders: List<PlatformAudioDecodeCapability>,
): Boolean {
    val acceptable = buildSet {
        add(mime.lowercase())
        if (mime.equals(MimeTypes.AUDIO_E_AC3_JOC, ignoreCase = true)) {
            add(MimeTypes.AUDIO_E_AC3.lowercase())
        }
    }
    return platformDecoders.any { decoder ->
        decoder.mimeType.lowercase() in acceptable &&
            when {
                channelCount <= 0 -> true
                // The device would not state a limit. Not a claim of an
                // unlimited one, but refusing every such decoder would reject a
                // great deal that plays; the preflight is a filter, and the
                // decoder-init failure path still exists behind it.
                decoder.maxInputChannelCount == null -> true
                else -> decoder.maxInputChannelCount >= channelCount
            }
    }
}

/** The wire name this project uses for a platform audio MIME, if it tracks one. */
internal fun platformAudioCodecName(mimeType: String): String? = when {
    mimeType.equals(MimeTypes.AUDIO_AAC, ignoreCase = true) -> "aac"
    mimeType.equals(MimeTypes.AUDIO_AC3, ignoreCase = true) -> "ac3"
    mimeType.equals(MimeTypes.AUDIO_E_AC3, ignoreCase = true) -> "eac3"
    mimeType.equals(MimeTypes.AUDIO_E_AC3_JOC, ignoreCase = true) -> "eac3_joc"
    mimeType.equals(MimeTypes.AUDIO_FLAC, ignoreCase = true) -> "flac"
    mimeType.equals(MimeTypes.AUDIO_OPUS, ignoreCase = true) -> "opus"
    mimeType.equals(MimeTypes.AUDIO_VORBIS, ignoreCase = true) -> "vorbis"
    mimeType.equals(MimeTypes.AUDIO_MPEG, ignoreCase = true) -> "mp3"
    else -> null
}
