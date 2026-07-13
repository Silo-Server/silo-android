package org.siloserver.silo.common.player

import android.content.Context
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
import org.siloserver.silo.model.playback.EngineCapabilityEnvelope
import org.siloserver.silo.model.playback.EngineSubtitleCapabilities
import org.siloserver.silo.model.playback.DETAILED_DECODE_CAPABILITIES_FEATURE
import org.siloserver.silo.model.playback.LAYOUT_AWARE_PASSTHROUGH_FEATURE
import org.siloserver.silo.model.playback.CLIENT_VIDEO_TRANSFORMATIONS_FEATURE
import org.siloserver.silo.model.playback.DEVICE_QUIRKS_V3_FEATURE
import org.siloserver.silo.model.playback.CLIENT_DV8_HDR10_PLUS_SANITIZER
import org.siloserver.silo.model.playback.CLIENT_POST_RESUME_VIDEO_RECOVERY
import org.siloserver.silo.model.playback.CLIENT_SURFACE_RECOVERY
import org.siloserver.silo.model.playback.CLIENT_DV7_TO_DV81
import org.siloserver.silo.model.playback.CLIENT_DV7_TO_HDR10
import org.siloserver.silo.model.playback.CLIENT_DV_TRANSFORM_RECIPE_VERSION
import org.siloserver.silo.model.playback.MEDIA3_ONLY_FEATURE
import org.siloserver.silo.model.playback.PLAYBACK_PLAN_V3_FEATURE
import org.siloserver.silo.model.playback.SEEK_REANCHOR_V3_FEATURE
import org.siloserver.silo.model.playback.PlaybackDeviceContext
import org.siloserver.silo.model.playback.PlaybackEngineKind
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
 * - Intersect codec-level HDR profiles with the panel's advertised HDR types.
 * - Populate `audioPassthrough` from the live [AudioCapabilityManager] state.
 */
class PlaybackCapabilityDetector(
    private val context: Context,
    private val audioCapabilityManager: AudioCapabilityManager,
    private val libassBridge: LibassBridge,
) {
    val outputRouteGeneration: StateFlow<Long> = audioCapabilityManager.outputRouteGeneration
    // Platform software-audio decoders are static for the process; cache the
    // MediaCodecList enumeration so back-to-back detect()/detectPlaybackContext()
    // calls per playback start don't re-run it.
    @Volatile
    private var cachedPlatformSoftwareAudioCodecs: List<String>? = null
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
                    val supportedHdr = DisplayHdrProbe.intersect(codecProbe.hdr, displayHdr)
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
     * When the FFmpeg audio extension is on the classpath, its decodable
     * codecs are appended to [ClientCodecCapabilities.codecsAudio] so the
     * server picks DIRECT/REMUX instead of TRANSCODE for streams the
     * client can now decode locally.
     */
    fun detect(
        ffmpegAvailable: Boolean = FfmpegAudioSupport.isAvailable(),
        dolbyVision: DolbyVisionPolicy.Snapshot = DolbyVisionPolicy.Snapshot(),
    ): ClientCodecCapabilities {
        val codecProbe = MediaCodecCapabilitiesProbe.probe()
        val displayHdr = DisplayHdrProbe.probe(context)
        // With Dolby Vision off, stop advertising DV profiles (except 5,
        // which has no watchable base layer) so the server plans base-layer /
        // HDR10 delivery and local direct-play checks agree. Single decision
        // source: DolbyVisionPolicy (Apple parity, silo-apple e9bd775).
        val intersectedHdr = DisplayHdrProbe.intersect(codecProbe.hdr, displayHdr).let { hdr ->
            hdr.copy(
                dolbyVisionProfiles = DolbyVisionPolicy.advertisableProfiles(
                    hdr.dolbyVisionProfiles,
                    dolbyVision,
                ),
            )
        }

        val softwareAudio = detectSoftwareAudioCodecs(ffmpegAvailable)
        val passthrough = audioCapabilityManager.capabilities.value
        val hasAnyHdr = intersectedHdr.hdr10 ||
            intersectedHdr.hdr10Plus ||
            intersectedHdr.hlg ||
            intersectedHdr.dolbyVisionProfiles.isNotEmpty()

        return ClientCodecCapabilities(
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
    }

    fun detectPlaybackContext(
        formFactor: String,
        appVersion: String = "unknown",
        ffmpegAvailable: Boolean = FfmpegAudioSupport.isAvailable(),
        dolbyVision: DolbyVisionPolicy.Snapshot = DolbyVisionPolicy.Snapshot(),
    ): ClientPlaybackContext {
        val caps = detect(ffmpegAvailable, dolbyVision)
        val supportedAbis = Build.SUPPORTED_ABIS?.toList().orEmpty()
        val passthrough = caps.audioPassthrough
        val decodeAudio = detectSoftwareAudioCodecs(ffmpegAvailable)
        val media3Audio = decodeAudio
        val libassRendering = libassBridge.isRenderingSupported
        val libassEmbeddedFonts = libassBridge.isEmbeddedFontsSupported
        val libassDirectFidelity = libassRendering && libassEmbeddedFonts
        val contextFeatures = buildList {
            add(PLAYBACK_PLAN_V3_FEATURE)
            add(SEEK_REANCHOR_V3_FEATURE)
            add(MEDIA3_ONLY_FEATURE)
            add(DETAILED_DECODE_CAPABILITIES_FEATURE)
            if (!passthrough?.entries.isNullOrEmpty()) add(LAYOUT_AWARE_PASSTHROUGH_FEATURE)
            add(CLIENT_VIDEO_TRANSFORMATIONS_FEATURE)
            add(DEVICE_QUIRKS_V3_FEATURE)
        }
        val clientVideoTransformations = buildList {
            if (8 in caps.hdrDetails?.dolbyVisionProfiles.orEmpty() && NativeDolbyVisionRpuConverter.isAvailable) {
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
            if (caps.hdrDetails?.hdr10 == true) {
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
        return ClientPlaybackContext(
            features = contextFeatures,
            formFactor = formFactor,
            appVersion = appVersion,
            device = PlaybackDeviceContext(
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
                brand = Build.BRAND,
                device = Build.DEVICE,
                product = Build.PRODUCT,
                socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Build.SOC_MANUFACTURER
                } else null,
                socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null,
                buildId = Build.ID,
                buildDisplay = Build.DISPLAY,
                securityPatch = Build.VERSION.SECURITY_PATCH,
                sdkInt = Build.VERSION.SDK_INT,
                abis = supportedAbis,
            ),
            output = PlaybackOutputContext(
                hdrDetails = caps.hdrDetails,
                audioPassthrough = passthrough,
                currentSink = if (passthrough?.passthroughCodecs?.isNotEmpty() == true) "passthrough_sink" else "local_output",
                sinkType = audioCapabilityManager.currentSinkType(),
                outputRouteGeneration = audioCapabilityManager.outputRouteGeneration.value,
            ),
            engines = mapOf(
                PlaybackEngineKind.MEDIA3_DIRECT to EngineCapabilityEnvelope(
                    enabled = true,
                    supportedOnDevice = true,
                    containers = media3OriginalPlaybackContainers,
                    videoCodecs = caps.codecsVideo,
                    audioDecodeCodecs = media3Audio,
                    audioPassthroughCodecs = passthrough?.passthroughCodecs.orEmpty(),
                    maxChannels = passthrough?.maxChannels,
                    hdrDetails = caps.hdrDetails,
                    subtitles = EngineSubtitleCapabilities(
                        embeddedText = true,
                        sidecarText = true,
                        assStyling = libassDirectFidelity,
                        // Media3's DefaultSubtitleParserFactory decodes the
                        // three embedded bitmap families carried by our
                        // direct-play containers: PGS, VobSub/DVD, and DVB.
                        // Keep sidecar bitmap disabled because Silo does not
                        // mount raw bitmap sidecars into the MediaItem.
                        embeddedBitmap = true,
                        sidecarBitmap = false,
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
                PlaybackEngineKind.MEDIA3_PROGRESSIVE_REMUX to EngineCapabilityEnvelope(
                    enabled = false,
                    supportedOnDevice = false,
                    failureReason = "disabled_pending_seekable_transport",
                    containers = listOf("mp4", "m4v", "webm", "mkv", "matroska"),
                    videoCodecs = caps.codecsVideo,
                    audioDecodeCodecs = media3Audio,
                    audioPassthroughCodecs = passthrough?.passthroughCodecs.orEmpty(),
                    maxChannels = passthrough?.maxChannels,
                    hdrDetails = caps.hdrDetails,
                    subtitles = EngineSubtitleCapabilities(
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
                PlaybackEngineKind.MEDIA3_HLS to EngineCapabilityEnvelope(
                    enabled = true,
                    supportedOnDevice = true,
                    containers = listOf("m3u8", "hls"),
                    videoCodecs = caps.codecsVideo,
                    audioDecodeCodecs = media3Audio,
                    audioPassthroughCodecs = passthrough?.passthroughCodecs.orEmpty(),
                    maxChannels = passthrough?.maxChannels,
                    hdrDetails = caps.hdrDetails,
                    subtitles = EngineSubtitleCapabilities(
                        embeddedText = true,
                        sidecarText = true,
                        assStyling = libassRendering,
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
     * Returns the audio codecs the device can decode itself (for internal
     * speaker / headphones / Bluetooth). Passthrough codecs come from the
     * [AudioCapabilityManager]; this covers the complement — platform
     * [MediaCodec] decoders plus, when the Media3 FFmpeg audio extension
     * is on the classpath, FFmpeg-reachable codecs.
     *
     * The FFmpeg list overlaps with platform decoders on codecs every
     * modern Android device already handles (e.g., AAC). `distinct()`
     * in [detect] collapses duplicates.
     */
    private fun detectSoftwareAudioCodecs(ffmpegAvailable: Boolean): List<String> {
        val platform = detectPlatformSoftwareAudioCodecs()
        val ffmpeg = if (ffmpegAvailable) FfmpegAudioSupport.codecShortCodes else emptyList()
        return (platform + ffmpeg).distinct()
    }

    private fun detectPlatformSoftwareAudioCodecs(): List<String> {
        cachedPlatformSoftwareAudioCodecs?.let { return it }
        val result = mutableSetOf<String>()
        val list = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS) }.getOrNull()
            ?: return listOf("aac", "mp3")
        for (info in list.codecInfos) {
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
        return result.toList().also { cachedPlatformSoftwareAudioCodecs = it }
    }
}

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
