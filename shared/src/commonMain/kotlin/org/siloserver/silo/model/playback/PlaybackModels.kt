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
    val url: String,
    /** Catalog-facing identity retained when a mounted artifact has a generic runtime label. */
    @SerialName("catalog_label") val catalogLabel: String? = null,
    /** Catalog provenance retained separately from the runtime artifact source. */
    @SerialName("catalog_source") val catalogSource: String? = null,
    /** Catalog default marker; playback-session artifact rows do not carry it themselves. */
    @SerialName("default") val isDefault: Boolean? = null,
    /** Persistent provider download identity; distinct from the mutable combined artifact [index]. */
    @SerialName("download_id") val downloadId: Int? = null,
    /** Optional exact Media3 Format.id retained by client-created/local rows. */
    @SerialName("media_track_id") val mediaTrackId: String? = null,
    /** Exact protocol-v3 server identity from the authoritative subtitle inventory. */
    @SerialName("server_track_id") val serverTrackId: String? = null,
    /** Protocol-v3 representation: `sidecar` or `burn_in_only`. */
    @SerialName("server_delivery") val serverDelivery: String? = null,
)

/**
 * Whether this row represents a subtitle artifact downloaded onto this Android
 * device, rather than a server-managed provider download in the v3 inventory.
 */
fun PlayerSubtitleInfo.isLocalDownloadedSubtitle(): Boolean =
    serverTrackId == null && serverDelivery == null &&
        (downloadId != null ||
            source.equals("downloaded", ignoreCase = true) ||
            catalogSource.equals("downloaded", ignoreCase = true))

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
    /**
     * How the video capability list was obtained. Android probes
     * `MediaCodecList` for concrete profile/level/bit-depth tuples, so it
     * advertises [CAPABILITY_EVIDENCE_EXACT] — the only tier the server will
     * strictly validate against, and the only one that earns audio passthrough.
     */
    @SerialName("video_evidence") val videoEvidence: String = CAPABILITY_EVIDENCE_EXACT,
    /** Evidence tier for the audio lists, on the same scale as [videoEvidence]. */
    @SerialName("audio_evidence") val audioEvidence: String = CAPABILITY_EVIDENCE_EXACT,
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
enum class PlaybackRouteFamily {
    @SerialName("platform_native") PLATFORM_NATIVE,
    @SerialName("compatibility_direct") COMPATIBILITY_DIRECT,
    @SerialName("server_adaptive") SERVER_ADAPTIVE,
    @SerialName("client_normalized") CLIENT_NORMALIZED,
}

/**
 * The player-facing projection of a [PlaybackPlanV3], built by
 * `PlaybackV3Session.toSessionResponse`. It is a UI view of the plan, not a wire
 * type of its own: the server's neutral contract has no notion of a client
 * engine or route family, so those are derived here from the plan's delivery.
 */
@Serializable
data class PlaybackExecutionPlan(
    @SerialName("plan_id") val planId: String,
    @SerialName("protocol_version") val protocolVersion: Int = PLAYBACK_PROTOCOL_V3,
    val delivery: PlaybackDelivery,
    @SerialName("route_family") val routeFamily: PlaybackRouteFamily,
    val stream: PlaybackStreamRequest = PlaybackStreamRequest(),
    val timeline: PlaybackTimeline = PlaybackTimeline(),
    @SerialName("selected_tracks") val selectedTracks: SelectedPlaybackTracks = SelectedPlaybackTracks(),
    val source: PlaybackSourceMetadata = PlaybackSourceMetadata(),
    val claims: PlaybackValidationClaims = PlaybackValidationClaims(),
    val transformations: List<PlaybackTransformationV3> = emptyList(),
    @SerialName("applied_quirks") val appliedQuirks: List<PlaybackAppliedQuirkV3> = emptyList(),
    @SerialName("runtime_corrections") val runtimeCorrections: List<String> = emptyList(),
    @SerialName("available_qualities") val availableQualities: List<PlaybackAvailableQualityV3> = emptyList(),
    @SerialName("degradation_warnings") val degradationWarnings: List<PlaybackDegradationWarning> = emptyList(),
    @SerialName("decision_trace") val decisionTrace: List<String> = emptyList(),
    @SerialName("requested_media_file_id") val requestedMediaFileId: Int? = null,
    @SerialName("effective_media_file_id") val effectiveMediaFileId: Int? = null,
)

/**
 * Deserializes a [PlaybackExecutionPlan] but yields `null` when the value is
 * present-but-malformed (a missing required field such as
 * `plan_id`/`delivery`/`route_family`, a malformed `degradation_warnings[]`
 * entry, or an unknown enum value). Without this, a single missing field throws
 * [SerializationException] and fails the decode of the ENTIRE session-start
 * response — turning an HTTP-200 into a NetworkError so playback never starts.
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
    @SerialName("color_range") val colorRange: String? = null,
    @SerialName("dolby_vision_profile") val dolbyVisionProfile: Int? = null,
    @SerialName("subtitle_codec") val subtitleCodec: String? = null,
    @SerialName("letterbox_top_fraction") val letterboxTopFraction: Double = 0.0,
    @SerialName("letterbox_bottom_fraction") val letterboxBottomFraction: Double = 0.0,
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
data class PlaybackDegradationWarning(
    val code: String,
    val message: String,
)

/**
 * Everything the server needs to know about *this device and its current
 * output route*, as opposed to the codec lists in [ClientCodecCapabilities].
 *
 * There is deliberately no `platform` field and no second `features` list here:
 * feature advertisement lives exclusively in the request's top-level
 * `client_features`, and the platform is inferred by the server from the
 * capability evidence and delivery classes it is given.
 */
@Serializable
data class ClientPlaybackContext(
    @SerialName("protocol_version") val protocolVersion: Int = PLAYBACK_PROTOCOL_V3,
    @SerialName("form_factor") val formFactor: String,
    @SerialName("app_version") val appVersion: String,
    /**
     * CI's per-marketing-version build counter behind [appVersion], so two
     * builds sharing a version name are still distinguishable in the server's
     * session/activity views. Null where the platform has no such value.
     */
    @SerialName("app_build") val appBuild: String? = null,
    /** How this build was distributed — "release" / "beta" / "sideload" / "dev". */
    @SerialName("app_channel") val appChannel: String? = null,
    val device: PlaybackDeviceContext = PlaybackDeviceContext(),
    val output: PlaybackOutputContext = PlaybackOutputContext(),
    /**
     * What this client is willing and able to play, keyed by delivery class
     * ([DELIVERY_CLASS_ORIGINAL_HTTP], [DELIVERY_CLASS_PROGRESSIVE],
     * [DELIVERY_CLASS_HLS]). Replaces the old engine self-description: the
     * server negotiates against transports, not against a client's internal
     * player component names.
     */
    val deliveries: Map<String, DeliveryCapability> = emptyMap(),
)

/**
 * Neutral device identity. Everything platform-specific — SoC, build
 * fingerprint, SDK level, ABIs — goes in [platformDetails] as opaque
 * string pairs so the server can key device quirks on it without the contract
 * growing an Android-shaped hole.
 */
@Serializable
data class PlaybackDeviceContext(
    val platform: String? = null,
    @SerialName("os_version") val osVersion: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    @SerialName("platform_details") val platformDetails: Map<String, String> = emptyMap(),
)

@Serializable
data class PlaybackOutputContext(
    @SerialName("hdr_details") val hdrDetails: HdrCapabilities? = null,
    @SerialName("audio_passthrough") val audioPassthrough: AudioPassthroughCapabilities? = null,
    @SerialName("current_sink") val currentSink: String? = null,
    @SerialName("sink_type") val sinkType: String? = null,
    /**
     * Opaque token identifying the current output route. The server only ever
     * compares it for equality — Android supplies its route generation
     * stringified, Apple a synthetic sink hash, web omits it.
     */
    @SerialName("output_context_id") val outputContextId: String? = null,
)

/** What the client can do with one delivery class. */
@Serializable
data class DeliveryCapability(
    /** Whether the client is *willing* to be routed here. */
    val enabled: Boolean = true,
    /** Whether the client is *able* to play it at all on this device. */
    @SerialName("supported_on_device") val supportedOnDevice: Boolean = true,
    /** Diagnostics only; the server never routes on this string. */
    @SerialName("failure_reason") val failureReason: String? = null,
    val containers: List<String> = emptyList(),
    @SerialName("video_codecs") val videoCodecs: List<String> = emptyList(),
    @SerialName("audio_decode_codecs") val audioDecodeCodecs: List<String> = emptyList(),
    @SerialName("audio_passthrough_codecs") val audioPassthroughCodecs: List<String> = emptyList(),
    @SerialName("max_channels") val maxChannels: Int? = null,
    @SerialName("hdr_details") val hdrDetails: HdrCapabilities? = null,
    val subtitles: DeliverySubtitleCapabilities = DeliverySubtitleCapabilities(),
    val features: List<String> = emptyList(),
    val transformations: List<PlaybackTransformationV3> = emptyList(),
    @SerialName("auth_header_refresh") val authHeaderRefresh: Boolean = false,
    @SerialName("validated_claims") val validatedClaims: List<String> = emptyList(),
)

@Serializable
data class DeliverySubtitleCapabilities(
    @SerialName("embedded_text") val embeddedText: Boolean = true,
    @SerialName("sidecar_text") val sidecarText: Boolean = true,
    @SerialName("ass_styling") val assStyling: Boolean = false,
    @SerialName("embedded_bitmap") val embeddedBitmap: Boolean = false,
    @SerialName("sidecar_bitmap") val sidecarBitmap: Boolean = false,
    @SerialName("font_attachments") val fontAttachments: Boolean = false,
)

@Serializable
data class ProgressRequest(
    val position: Double,
    @SerialName("is_paused") val isPaused: Boolean
)
