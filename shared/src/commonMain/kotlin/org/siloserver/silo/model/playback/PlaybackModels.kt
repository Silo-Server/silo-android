package org.siloserver.silo.model.playback

import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.plugins.logging.Logger
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull

@Serializable
enum class PlayMethod {
    @SerialName("direct") DIRECT,
    @SerialName("remux") REMUX,
    @SerialName("transcode") TRANSCODE
}

@Serializable
data class PlaybackSessionResponse(
    @SerialName("session_id") val sessionId: String,
    @SerialName("user_id") val userId: Int,
    @SerialName("profile_id") val profileId: String? = null,
    @SerialName("media_file_id") val mediaFileId: Int,
    @SerialName("play_method") val playMethod: PlayMethod,
    val position: Double = 0.0,
    @SerialName("is_paused") val isPaused: Boolean = false,
    @SerialName("stream_url") val streamUrl: String,
    @SerialName("audio_track_index") val audioTrackIndex: Int = 0,
    @SerialName("duration_seconds") val durationSeconds: Double? = null,
    @SerialName("subtitle_urls") val subtitleUrls: List<PlayerSubtitleInfo>? = null,
    @SerialName("playback_info") val playbackInfo: PlaybackInfo? = null,
    @SerialName("playback_plan")
    @Serializable(with = TolerantPlaybackPlanSerializer::class)
    val playbackPlan: PlaybackExecutionPlan? = null,
)

@Serializable
data class PlaybackInfo(
    @SerialName("stream_type") val streamType: String? = null,
    @SerialName("transcode_audio") val transcodeAudio: Boolean = false,
    @SerialName("video_codec") val videoCodec: String? = null,
    @SerialName("audio_codec") val audioCodec: String? = null
)

@Serializable
data class PlayerSubtitleInfo(
    val index: Int,
    val language: String? = null,
    val codec: String? = null,
    val label: String? = null,
    val source: String? = null,
    val forced: Boolean? = null,
    val url: String
)

/**
 * Granular HDR support advertised by the client. Optional; absent means the
 * server uses the legacy [ClientCodecCapabilities.hdr] boolean for SDR-vs-HDR
 * version selection.
 *
 * Dolby Vision profile numbers map to MediaCodec constants:
 *   - 4 = `DolbyVisionProfileDvheDtr`
 *   - 5 = `DolbyVisionProfileDvheStn`
 *   - 6 = `DolbyVisionProfileDvheDth`
 *   - 7 = `DolbyVisionProfileDvheDtb` (BL+EL; requires DV and HEVC concurrency)
 *   - 8 = `DolbyVisionProfileDvheSt`
 */
@Serializable
data class HdrCapabilities(
    val hdr10: Boolean = false,
    @SerialName("hdr10_plus") val hdr10Plus: Boolean = false,
    val hlg: Boolean = false,
    @SerialName("dolby_vision_profiles") val dolbyVisionProfiles: List<Int> = emptyList(),
)

/**
 * Audio passthrough support advertised by the client — what the connected sink
 * (HDMI receiver, soundbar, headphones) can decode bit-exact. Distinct from
 * [ClientCodecCapabilities.codecsAudio], which describes what the client can
 * decode in software/hardware. Passthrough capability comes from
 * `AudioCapabilities.getCapabilities` / `AudioCapabilitiesReceiver`.
 */
@Serializable
data class AudioPassthroughEntry(
    val codec: String,
    @SerialName("channel_counts") val channelCounts: List<Int> = emptyList(),
    val layouts: List<String> = emptyList(),
)

@Serializable
data class AudioPassthroughCapabilities(
    @SerialName("passthrough_codecs") val passthroughCodecs: List<String> = emptyList(),
    @SerialName("spatializer_enabled") val spatializerEnabled: Boolean = false,
    @SerialName("max_channels") val maxChannels: Int = 2,
    /** Exact encoded-audio layouts verified against the current output route. */
    val entries: List<AudioPassthroughEntry> = emptyList(),
)

@Serializable
data class VideoDecodeCapability(
    val codec: String,
    @SerialName("decoder_name") val decoderName: String? = null,
    val profiles: List<String> = emptyList(),
    val levels: List<Int> = emptyList(),
    @SerialName("bit_depths") val bitDepths: List<Int> = emptyList(),
    @SerialName("max_width") val maxWidth: Int? = null,
    @SerialName("max_height") val maxHeight: Int? = null,
    @SerialName("max_frame_rate") val maxFrameRate: Double? = null,
    @SerialName("max_bitrate_kbps") val maxBitrateKbps: Int? = null,
    val hardware: Boolean,
)

@Serializable
data class ClientCodecCapabilities(
    @SerialName("codecs_video") val codecsVideo: List<String> = emptyList(),
    // Hardware-decodable subset. In the Media3-only protocol this currently
    // equals codecsVideo; it remains on the wire for older server readers.
    @SerialName("codecs_video_hardware") val codecsVideoHardware: List<String> = emptyList(),
    @SerialName("codecs_audio") val codecsAudio: List<String> = emptyList(),
    val containers: List<String> = emptyList(),
    @SerialName("max_resolution") val maxResolution: String? = null,
    val hdr: Boolean = false,
    @SerialName("hdr_details") val hdrDetails: HdrCapabilities? = null,
    @SerialName("audio_passthrough") val audioPassthrough: AudioPassthroughCapabilities? = null,
    @SerialName("video_decode") val videoDecode: List<VideoDecodeCapability> = emptyList(),
)

@Serializable
enum class PlaybackDelivery {
    @SerialName("original_http") ORIGINAL_HTTP,
    @SerialName("server_remux_hls") SERVER_REMUX_HLS,
    @SerialName("server_remux_progressive") SERVER_REMUX_PROGRESSIVE,
    @SerialName("server_transcode_hls") SERVER_TRANSCODE_HLS,
    @SerialName("client_local_normalization") CLIENT_LOCAL_NORMALIZATION,
}

@Serializable
enum class PlaybackEngineKind {
    @SerialName("media3_direct") MEDIA3_DIRECT,
    @SerialName("mpv_direct") MPV_DIRECT,
    @SerialName("media3_progressive_remux") MEDIA3_PROGRESSIVE_REMUX,
    @SerialName("media3_hls") MEDIA3_HLS,
    @SerialName("client_local_loopback") CLIENT_LOCAL_LOOPBACK,
    @SerialName("external_player") EXTERNAL_PLAYER,
}

@Serializable
enum class PlaybackRouteFamily {
    @SerialName("platform_native") PLATFORM_NATIVE,
    @SerialName("compatibility_direct") COMPATIBILITY_DIRECT,
    @SerialName("server_adaptive") SERVER_ADAPTIVE,
    @SerialName("client_normalized") CLIENT_NORMALIZED,
}

@Serializable
data class PlaybackExecutionPlan(
    @SerialName("plan_id") val planId: String,
    @SerialName("protocol_version") val protocolVersion: Int = 2,
    val delivery: PlaybackDelivery,
    val engine: PlaybackEngineKind,
    @SerialName("route_family") val routeFamily: PlaybackRouteFamily,
    val stream: PlaybackStreamRequest = PlaybackStreamRequest(),
    val timeline: PlaybackTimeline = PlaybackTimeline(),
    @SerialName("selected_tracks") val selectedTracks: SelectedPlaybackTracks = SelectedPlaybackTracks(),
    val source: PlaybackSourceMetadata = PlaybackSourceMetadata(),
    val capabilities: RouteCapabilitySnapshot = RouteCapabilitySnapshot(),
    val requirements: RouteRequirements = RouteRequirements(),
    val claims: PlaybackValidationClaims = PlaybackValidationClaims(),
    val transformations: List<PlaybackTransformationV3> = emptyList(),
    @SerialName("applied_quirks") val appliedQuirks: List<PlaybackAppliedQuirkV3> = emptyList(),
    @SerialName("runtime_corrections") val runtimeCorrections: List<String> = emptyList(),
    val fallbacks: List<PlaybackFallbackCandidate> = emptyList(),
    @SerialName("degradation_warnings") val degradationWarnings: List<PlaybackDegradationWarning> = emptyList(),
    @SerialName("decision_trace") val decisionTrace: List<String> = emptyList(),
    @SerialName("requested_media_file_id") val requestedMediaFileId: Int? = null,
    @SerialName("effective_media_file_id") val effectiveMediaFileId: Int? = null,
)

/**
 * Deserializes a [PlaybackExecutionPlan] but yields `null` when the server sends
 * a present-but-incomplete/malformed plan (a missing required field such as
 * `plan_id`/`delivery`/`engine`/`route_family`, a malformed `fallbacks[]` /
 * `degradation_warnings[]` entry, or an unknown enum value). Without this, a
 * single missing field throws [SerializationException] and fails the decode of
 * the ENTIRE session-start response — turning an HTTP-200 into a NetworkError so
 * playback never starts. A null plan instead makes the client fall back to the
 * legacy V1 routing, which is the safe degrade.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object TolerantPlaybackPlanSerializer : KSerializer<PlaybackExecutionPlan?> {
    private val delegate = PlaybackExecutionPlan.serializer()
    private val logger = Logger.DEFAULT
    override val descriptor: SerialDescriptor = delegate.descriptor

    override fun deserialize(decoder: Decoder): PlaybackExecutionPlan? {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return runCatching { delegate.deserialize(decoder) }.getOrNull()
        val element = jsonDecoder.decodeJsonElement()
        if (element is JsonNull) return null
        return try {
            jsonDecoder.json.decodeFromJsonElement(delegate, element)
        } catch (e: SerializationException) {
            logger.log("Dropping malformed legacy playback_plan: ${e.message}")
            null
        }
    }

    override fun serialize(encoder: Encoder, value: PlaybackExecutionPlan?) {
        if (value == null) encoder.encodeNull() else delegate.serialize(encoder, value)
    }
}

@Serializable
data class PlaybackStreamRequest(
    val url: String? = null,
    @SerialName("stream_type") val streamType: String? = null,
    @SerialName("play_method") val playMethod: PlayMethod? = null,
)

@Serializable
data class PlaybackTimeline(
    @SerialName("player_start_seconds") val playerStartSeconds: Double = 0.0,
    @SerialName("stream_origin_seconds") val streamOriginSeconds: Double = 0.0,
    @SerialName("timeline_offset_seconds") val timelineOffsetSeconds: Double = 0.0,
    @SerialName("can_seek_anywhere") val canSeekAnywhere: Boolean = true,
    @SerialName("source_start_seconds") val sourceStartSeconds: Double = 0.0,
    @SerialName("seek_window_start_seconds") val seekWindowStartSeconds: Double? = null,
    @SerialName("seek_window_end_seconds") val seekWindowEndSeconds: Double? = null,
    @SerialName("seek_restoration") val seekRestoration: String = "player_position",
)

@Serializable
data class SelectedPlaybackTracks(
    @SerialName("audio_index") val audioIndex: Int? = null,
    @SerialName("subtitle_index") val subtitleIndex: Int? = null,
)

@Serializable
data class PlaybackSourceMetadata(
    @SerialName("media_file_id") val mediaFileId: Int? = null,
    val container: String? = null,
    @SerialName("video_codec") val videoCodec: String? = null,
    @SerialName("audio_codec") val audioCodec: String? = null,
    val resolution: String? = null,
    @SerialName("hdr_format") val hdrFormat: String? = null,
    @SerialName("dolby_vision_profile") val dolbyVisionProfile: Int? = null,
    @SerialName("subtitle_codec") val subtitleCodec: String? = null,
)

@Serializable
data class RouteCapabilitySnapshot(
    @SerialName("engine_available") val engineAvailable: Boolean = true,
    @SerialName("validated_claims") val validatedClaims: List<String> = emptyList(),
    val blockers: List<String> = emptyList(),
)

@Serializable
data class RouteRequirements(
    @SerialName("requires_hdr_preservation") val requiresHdrPreservation: Boolean = false,
    @SerialName("requires_dolby_vision_preservation") val requiresDolbyVisionPreservation: Boolean = false,
    @SerialName("requires_audio_passthrough") val requiresAudioPassthrough: Boolean = false,
    @SerialName("requires_ass_fidelity") val requiresAssFidelity: Boolean = false,
    @SerialName("requires_bitmap_subtitles") val requiresBitmapSubtitles: Boolean = false,
)

@Serializable
data class PlaybackValidationClaims(
    val video: VideoValidationClaims = VideoValidationClaims(),
    val audio: AudioValidationClaims = AudioValidationClaims(),
    val subtitles: SubtitleValidationClaims = SubtitleValidationClaims(),
)

@Serializable
data class VideoValidationClaims(
    @SerialName("hdr10") val hdr10: Boolean = false,
    @SerialName("hdr10_plus") val hdr10Plus: Boolean = false,
    val hlg: Boolean = false,
    @SerialName("dolby_vision") val dolbyVision: Boolean = false,
    @SerialName("dolby_vision_reason") val dolbyVisionReason: String? = null,
)

@Serializable
data class AudioValidationClaims(
    val codec: String? = null,
    val passthrough: Boolean = false,
    @SerialName("atmos_preserved") val atmosPreserved: Boolean = false,
    @SerialName("dts_variant") val dtsVariant: String? = null,
    val reason: String? = null,
)

@Serializable
data class SubtitleValidationClaims(
    @SerialName("ass_styling_preserved") val assStylingPreserved: Boolean = false,
    @SerialName("bitmap_overlay") val bitmapOverlay: Boolean = false,
    @SerialName("bitmap_sidecar") val bitmapSidecar: Boolean = false,
    val reason: String? = null,
)

@Serializable
data class PlaybackFallbackCandidate(
    val delivery: PlaybackDelivery,
    val engine: PlaybackEngineKind,
    val reason: String,
)

@Serializable
data class PlaybackDegradationWarning(
    val code: String,
    val message: String,
)

@Serializable
data class ClientPlaybackContext(
    @SerialName("protocol_version") val protocolVersion: Int = PLAYBACK_PROTOCOL_V3,
    val features: List<String> = listOf(
        PLAYBACK_PLAN_V3_FEATURE,
        MEDIA3_ONLY_FEATURE,
        DETAILED_DECODE_CAPABILITIES_FEATURE,
        DEVICE_QUIRKS_V3_FEATURE,
        SEEK_REANCHOR_V3_FEATURE,
    ),
    val platform: String = "android",
    @SerialName("form_factor") val formFactor: String,
    @SerialName("app_version") val appVersion: String,
    val device: PlaybackDeviceContext = PlaybackDeviceContext(),
    val output: PlaybackOutputContext = PlaybackOutputContext(),
    val engines: Map<PlaybackEngineKind, EngineCapabilityEnvelope> = emptyMap(),
)

@Serializable
data class PlaybackDeviceContext(
    val manufacturer: String? = null,
    val model: String? = null,
    val brand: String? = null,
    val device: String? = null,
    val product: String? = null,
    @SerialName("soc_manufacturer") val socManufacturer: String? = null,
    @SerialName("soc_model") val socModel: String? = null,
    @SerialName("build_id") val buildId: String? = null,
    @SerialName("build_display") val buildDisplay: String? = null,
    @SerialName("security_patch") val securityPatch: String? = null,
    @SerialName("sdk_int") val sdkInt: Int? = null,
    val abis: List<String> = emptyList(),
)

@Serializable
data class PlaybackOutputContext(
    @SerialName("hdr_details") val hdrDetails: HdrCapabilities? = null,
    @SerialName("audio_passthrough") val audioPassthrough: AudioPassthroughCapabilities? = null,
    @SerialName("current_sink") val currentSink: String? = null,
    @SerialName("sink_type") val sinkType: String? = null,
    @SerialName("output_route_generation") val outputRouteGeneration: Long = 0,
)

@Serializable
data class EngineCapabilityEnvelope(
    val enabled: Boolean = true,
    @SerialName("supported_on_device") val supportedOnDevice: Boolean = true,
    @SerialName("failure_reason") val failureReason: String? = null,
    val containers: List<String> = emptyList(),
    @SerialName("video_codecs") val videoCodecs: List<String> = emptyList(),
    @SerialName("audio_decode_codecs") val audioDecodeCodecs: List<String> = emptyList(),
    @SerialName("audio_passthrough_codecs") val audioPassthroughCodecs: List<String> = emptyList(),
    @SerialName("max_channels") val maxChannels: Int? = null,
    @SerialName("hdr_details") val hdrDetails: HdrCapabilities? = null,
    val subtitles: EngineSubtitleCapabilities = EngineSubtitleCapabilities(),
    val features: List<String> = emptyList(),
    val transformations: List<PlaybackTransformationV3> = emptyList(),
    @SerialName("auth_header_refresh") val authHeaderRefresh: Boolean = false,
    @SerialName("validated_claims") val validatedClaims: List<String> = emptyList(),
)

@Serializable
data class EngineSubtitleCapabilities(
    @SerialName("embedded_text") val embeddedText: Boolean = true,
    @SerialName("sidecar_text") val sidecarText: Boolean = true,
    @SerialName("ass_styling") val assStyling: Boolean = false,
    @SerialName("embedded_bitmap") val embeddedBitmap: Boolean = false,
    @SerialName("sidecar_bitmap") val sidecarBitmap: Boolean = false,
    @SerialName("font_attachments") val fontAttachments: Boolean = false,
)

/**
 * Body for `POST /api/v1/playback/start`.
 *
 * The server expects codec/container/HDR fields **flat at the top level** —
 * see `Silo/internal/api/handlers/playback.go::startPlaybackRequest`. A
 * previous version of this class nested them under `client_capabilities`,
 * which the Go JSON decoder silently ignored; the server then saw empty codec
 * lists and force-transcoded every stream. Keep this flat.
 */
@Serializable
data class StartPlaybackRequest(
    @SerialName("file_id") val fileId: Int,
    @SerialName("profile_id") val profileId: String? = null,
    @SerialName("play_method") val playMethod: String? = null,
    @SerialName("start_position") val startPosition: Double? = null,
    @SerialName("audio_track_index") val audioTrackIndex: Int? = null,
    @SerialName("subtitle_track_index") val subtitleTrackIndex: Int? = null,
    @SerialName("quality_preference") val qualityPreference: String? = null,
    @SerialName("preserve_direct_audio_selection") val preserveDirectAudioSelection: Boolean = false,
    @SerialName("codecs_video") val codecsVideo: List<String> = emptyList(),
    @SerialName("codecs_audio") val codecsAudio: List<String> = emptyList(),
    val containers: List<String> = emptyList(),
    @SerialName("max_resolution") val maxResolution: String? = null,
    val hdr: Boolean = false,
    @SerialName("hdr_details") val hdrDetails: HdrCapabilities? = null,
    @SerialName("audio_passthrough") val audioPassthrough: AudioPassthroughCapabilities? = null,
    @SerialName("client_playback_context") val clientPlaybackContext: ClientPlaybackContext? = null,
    @SerialName("disable_progress_persistence") val disableProgressPersistence: Boolean = false,
)

@Serializable
data class ProgressRequest(
    val position: Double,
    @SerialName("is_paused") val isPaused: Boolean
)

@Serializable
data class TranscodeStartRequest(
    @SerialName("session_id") val sessionId: String,
    @SerialName("seek_seconds") val seekSeconds: Double,
    @SerialName("target_resolution") val targetResolution: String? = null,
    @SerialName("target_codec_video") val targetCodecVideo: String? = null,
    @SerialName("target_codec_audio") val targetCodecAudio: String? = null,
    @SerialName("target_bitrate_kbps") val targetBitrateKbps: Int,
    @SerialName("segment_duration") val segmentDuration: Int,
    @SerialName("audio_track_index") val audioTrackIndex: Int? = null,
    @SerialName("subtitle_track_index") val subtitleTrackIndex: Int? = null,
    @SerialName("subtitle_burn_in") val subtitleBurnIn: Boolean
)

@Serializable
data class TranscodeStartResponse(
    @SerialName("session_id") val sessionId: String,
    val status: String,
    @SerialName("switched_file_id") val switchedFileId: Int? = null,
    @SerialName("manifest_url") val manifestUrl: String,
    @SerialName("duration_seconds") val durationSeconds: Double? = null,
    @SerialName("player_start_seconds") val playerStartSeconds: Double = 0.0,
    @SerialName("stream_origin_seconds") val streamOriginSeconds: Double = 0.0,
    @SerialName("timeline_offset_seconds") val timelineOffsetSeconds: Double = 0.0,
    @SerialName("can_seek_anywhere") val canSeekAnywhere: Boolean = false
)
