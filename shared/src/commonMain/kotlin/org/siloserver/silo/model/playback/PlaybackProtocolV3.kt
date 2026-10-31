package org.siloserver.silo.model.playback

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.decodeFromJsonElement

const val PLAYBACK_PROTOCOL_V3 = 3
const val PLAYBACK_PLAN_V3_FEATURE = "playback_plan_v3"
const val NEUTRAL_PLAYBACK_V3_CONTRACT_FEATURE = "neutral_playback_v3_contract_v1"
const val LAYOUT_AWARE_PASSTHROUGH_FEATURE = "layout_aware_passthrough"
const val CLIENT_VIDEO_TRANSFORMATIONS_FEATURE = "client_video_transformations_v1"
const val DEVICE_QUIRKS_V3_FEATURE = "device_quirks_v1"
const val SEEK_REANCHOR_V3_FEATURE = "seek_reanchor_v1"
const val DIRECT_STREAM_RESUME_V1_FEATURE = "direct_stream_resume_v1"
const val EMBEDDED_SUBTITLES_V1_FEATURE = "embedded_subtitles_v1"
const val NATIVE_HLS_PLAYBACK_V1_FEATURE = "native_hls_playback_v1"

/**
 * A `server_transcode_hls` plan may carry its embedded text subtitle as a
 * WebVTT `SUBTITLES` rendition of the plan's own HLS stream
 * ([PlaybackSubtitleDecisionV3.hlsRendition]) instead of a sidecar artifact.
 * `/api/v2` only, and attempt-sticky on the server.
 */
const val HLS_SUBTITLE_RENDITIONS_V1_FEATURE = "hls_subtitle_renditions_v1"

/**
 * Extends [HLS_SUBTITLE_RENDITIONS_V1_FEATURE], which it requires, to
 * `server_remux_hls`: a copy remux then carries its embedded text track as a
 * rendition instead of a whole-file sidecar extraction. Sent only to a server
 * that advertises it ([gatedForHlsSubtitleRenditions]). `/api/v2` only,
 * and attempt-sticky on the server.
 */
const val HLS_REMUX_SUBTITLE_RENDITIONS_V1_FEATURE = "hls_remux_subtitle_renditions_v1"

/**
 * Extends [HLS_SUBTITLE_RENDITIONS_V1_FEATURE], which it requires, to every
 * embedded text track the source's index can read: each becomes a rendition of
 * the HLS plan's stream (a copy remux also needs
 * [HLS_REMUX_SUBTITLE_RENDITIONS_V1_FEATURE]), its inventory row carries
 * [PlaybackSubtitleInventoryItemV3.hlsRendition], and a switch among them, or
 * to Off, keeps the running stream. Sent only to a server that advertises it
 * ([gatedForHlsSubtitleRenditions]). `/api/v2` only, and attempt-sticky.
 */
const val HLS_SUBTITLE_RENDITIONS_ALL_V1_FEATURE = "hls_subtitle_renditions_all_v1"

/**
 * The decision's plan reports [PlaybackPlanV3.activeQuality], so an absent
 * value means the plan delivers no menu entry. Without it, an absent value
 * says nothing and the client infers the entry. `/api/v2` only.
 */
const val PLAN_ACTIVE_QUALITY_V1_FEATURE = "plan_active_quality_v1"

/**
 * The server keeps learned bandwidth limits and accepts
 * `bandwidth_estimate_source` and `learned_limit` on start and replan. The
 * server advertises it in the `/api/v2` capability `features`; only then does
 * the client echo it in `client_features` and send those fields, because the
 * server rejects unknown fields. Spec: the server repo's
 * `docs/design/2026-10-17-buffering-quality.md`, *Wire contract*.
 */
const val BUFFERING_QUALITY_V1_FEATURE = "buffering_quality_v1"

/** The server accepts the `plan_summary` route event. */
const val PLAYBACK_PLAN_SUMMARY_V1_FEATURE = "playback_plan_summary_v1"

/** The server serves a bandwidth probe on the media path and takes probe results. */
const val CONNECTION_SPEED_V1_FEATURE = "connection_speed_v1"

/** `bandwidth_estimate_source` values. Valid only alongside `bandwidth_estimate_kbps`. */
const val BANDWIDTH_ESTIMATE_SOURCE_PROBE = "probe"
const val BANDWIDTH_ESTIMATE_SOURCE_OBSERVED_REBUFFER = "observed_rebuffer"

/** `learned_limit` values. Absent applies the stored limit. */
const val LEARNED_LIMIT_IGNORE = "ignore"
const val LEARNED_LIMIT_CLEAR = "clear"

/** The degradation warning Auto returns when nothing lower exists. */
const val QUALITY_REDUCTION_UNAVAILABLE_WARNING = "quality_reduction_unavailable"

/**
 * Failure classifications for a server subtitle decision the player selects
 * from its own media and could not mount. Either one asks `failure_recovery`
 * for the sidecar plan; neither may tear down healthy video.
 */
const val SUBTITLE_EMBEDDED_FAILED_CLASSIFICATION = "subtitle_embedded_failed"
const val SUBTITLE_HLS_RENDITION_FAILED_CLASSIFICATION = "subtitle_hls_rendition_failed"
val SUBTITLE_SELECTION_FAILURE_CLASSIFICATIONS = setOf(
    SUBTITLE_EMBEDDED_FAILED_CLASSIFICATION,
    SUBTITLE_HLS_RENDITION_FAILED_CLASSIFICATION,
)

/**
 * Delivery-scoped proof that the original-file player maps a server-selected
 * source audio ordinal onto the mounted Media3 track inventory. A client that
 * advertises this must report a typed playback failure when the mapping cannot
 * be mounted or confirmed so the server can choose a packaged fallback.
 */
const val CLIENT_SELECTED_AUDIO_TRACK_V1_CLAIM = "client_selected_audio_track_v1"

/**
 * Delivery-scoped claim that the original-file player decodes a single-layer
 * Dolby Vision Profile 8 stream through an ordinary HEVC decoder and presents
 * its standards-compatible base layer (HDR10, HLG, or SDR by `dv_bl_compat_id`)
 * when the active output lacks native Dolby Vision. The bytes are untouched;
 * the plan's `effective_recipe.dynamic_range` names the base range and the
 * plan never claims Dolby Vision output. Not a transformation and not the
 * broad `client_managed_dynamic_range_v1` promise.
 */
const val CLIENT_DV8_BASE_LAYER_FALLBACK_V1_CLAIM = "client_dv8_base_layer_fallback_v1"

/** Server decision reason for a plan that used [CLIENT_DV8_BASE_LAYER_FALLBACK_V1_CLAIM]. */
const val DECISION_REASON_CLIENT_DV8_BASE_LAYER = "client_dv8_base_layer"

/**
 * How a capability list was obtained. The server validates strictly against
 * [CAPABILITY_EVIDENCE_EXACT] and only grants audio passthrough at that tier;
 * weaker tiers exist so a platform that cannot enumerate decoders does not have
 * to fabricate profile/level tuples to be understood.
 */
const val CAPABILITY_EVIDENCE_EXACT = "exact"
const val CAPABILITY_EVIDENCE_PLATFORM_ATTESTED = "platform_attested"
const val CAPABILITY_EVIDENCE_DECLARED = "declared"

/** Transport classes a client negotiates over; the unit of [DeliveryCapability]. */
const val DELIVERY_CLASS_ORIGINAL_HTTP = "original_http"
const val DELIVERY_CLASS_PROGRESSIVE = "progressive"
const val DELIVERY_CLASS_HLS = "hls"

/** Subtitle delivery values understood by this client. */
const val SUBTITLE_DELIVERY_SIDECAR = "sidecar"
const val SUBTITLE_DELIVERY_BURN_IN_ONLY = "burn_in_only"

/** Replan operations. Omitted means [FAILURE_RECOVERY_V3_OPERATION]. */
const val FAILURE_RECOVERY_V3_OPERATION = "failure_recovery"
const val SEEK_REANCHOR_V3_OPERATION = "seek_reanchor"
const val SEEK_FAILURE_RECOVERY_V3_OPERATION = "seek_failure_recovery"
const val TRACK_CHANGE_V3_OPERATION = "track_change"
const val QUALITY_CHANGE_V3_OPERATION = "quality_change"

/**
 * Operations that describe a user-initiated change rather than a failure, and so
 * carry no `failure.classification`.
 */
val INTENT_V3_OPERATIONS = setOf(TRACK_CHANGE_V3_OPERATION, QUALITY_CHANGE_V3_OPERATION)

/**
 * The label of the source entry older servers list, and the audiobook player's
 * quality preference. Video requests send Auto instead
 * ([videoQualityPreference]): Auto plays the source whenever the device and
 * connection can.
 */
const val QUALITY_ORIGINAL_V3 = "original"

const val CLIENT_DV7_TO_DV81 = "client_dv7_to_dv81"
const val CLIENT_DV7_TO_HDR10 = "client_dv7_to_hdr10"
const val CLIENT_DV_TRANSFORM_RECIPE_VERSION = "1"
const val CLIENT_DV8_HDR10_PLUS_SANITIZER = "client_dv8_hdr10plus_sanitizer_v1"
const val CLIENT_POST_RESUME_VIDEO_RECOVERY = "client_post_resume_video_recovery_v1"
const val CLIENT_SURFACE_RECOVERY = "client_surface_recovery_v1"

/** Features the client advertises unconditionally on every v3 request. */
val PLAYBACK_START_CLIENT_FEATURES_V3 = listOf(
    PLAYBACK_PLAN_V3_FEATURE,
    CLIENT_VIDEO_TRANSFORMATIONS_FEATURE,
    DEVICE_QUIRKS_V3_FEATURE,
    SEEK_REANCHOR_V3_FEATURE,
    DIRECT_STREAM_RESUME_V1_FEATURE,
)

/**
 * The features to advertise for a given output context.
 *
 * `client_features` lives only at the top level of a start/replan request — the
 * neutral contract deliberately has no second features list inside the playback
 * context — so anything conditional on the device's current output has to be
 * folded in here. [LAYOUT_AWARE_PASSTHROUGH_FEATURE] is exactly that: the
 * server grants a validated passthrough claim only when the client both
 * advertises the feature and enumerates real per-codec channel layouts, so
 * claiming it with an empty entry list would be a claim we cannot back.
 */
fun playbackClientFeaturesV3(context: ClientPlaybackContext): List<String> = buildList {
    addAll(PLAYBACK_START_CLIENT_FEATURES_V3)
    if (!context.deliveries[DELIVERY_CLASS_ORIGINAL_HTTP]?.subtitles?.nativeEmbedded.isNullOrEmpty()) {
        add(EMBEDDED_SUBTITLES_V1_FEATURE)
    }
    if (!context.output.audioPassthrough?.entries.isNullOrEmpty()) {
        add(LAYOUT_AWARE_PASSTHROUGH_FEATURE)
    }
    // Only Media3's own HLS pipeline selects a rendition from the multivariant
    // playlist. A Cast receiver's HLS delivery does not carry that marker and
    // keeps its sidecars.
    if (NATIVE_HLS_PLAYBACK_V1_FEATURE in context.deliveries[DELIVERY_CLASS_HLS]?.features.orEmpty()) {
        add(HLS_SUBTITLE_RENDITIONS_V1_FEATURE)
    }
}

/**
 * The start request to send to a server advertising [serverFeatures]: it lists
 * [HLS_REMUX_SUBTITLE_RENDITIONS_V1_FEATURE] and
 * [HLS_SUBTITLE_RENDITIONS_ALL_V1_FEATURE] only when the server advertises
 * them and the request already lists [HLS_SUBTITLE_RENDITIONS_V1_FEATURE],
 * which both require. Pure and idempotent.
 */
fun PlaybackStartRequestV3.gatedForHlsSubtitleRenditions(
    serverFeatures: Collection<String>,
): PlaybackStartRequestV3 = copy(clientFeatures = clientFeatures.gatedForHlsSubtitleRenditions(serverFeatures))

/**
 * The replan request to send to a server advertising [serverFeatures]. The
 * server pins both features to what the start negotiated, so this only keeps
 * the replan's list consistent with it.
 */
fun PlaybackReplanRequestV3.gatedForHlsSubtitleRenditions(
    serverFeatures: Collection<String>,
): PlaybackReplanRequestV3 = copy(clientFeatures = clientFeatures.gatedForHlsSubtitleRenditions(serverFeatures))

private val HLS_SUBTITLE_RENDITION_EXTENSIONS = listOf(
    HLS_REMUX_SUBTITLE_RENDITIONS_V1_FEATURE,
    HLS_SUBTITLE_RENDITIONS_ALL_V1_FEATURE,
)

private fun List<String>.gatedForHlsSubtitleRenditions(serverFeatures: Collection<String>): List<String> {
    val renditions = HLS_SUBTITLE_RENDITIONS_V1_FEATURE in this
    val kept = filterNot { it in HLS_SUBTITLE_RENDITION_EXTENSIONS }
    val wanted = HLS_SUBTITLE_RENDITION_EXTENSIONS.filter { renditions && it in serverFeatures }
    val gated = kept + wanted
    return if (gated == this) this else gated
}

@Serializable
enum class PlaybackDecisionOutcome {
    @SerialName("playable") PLAYABLE,
    @SerialName("adaptation_unavailable") ADAPTATION_UNAVAILABLE,
}

@Serializable
enum class PlaybackStreamProtocol {
    @SerialName("http_progressive") HTTP_PROGRESSIVE,
    @SerialName("hls") HLS,
}

@Serializable
enum class PlaybackHeaderRefreshMode {
    @SerialName("none") NONE,
    @SerialName("session") SESSION,
    @SerialName("refresh_endpoint") REFRESH_ENDPOINT,
}

@Serializable
enum class PlaybackSubtitleModeV3 {
    @SerialName("off") OFF,
    @SerialName("render") RENDER,
    @SerialName("convert") CONVERT,
    @SerialName("burn_in") BURN_IN,
}

@Serializable
enum class SubtitleFidelityPreference {
    @SerialName("preserve") PRESERVE,
    @SerialName("compatible") COMPATIBLE,
}

@Serializable
enum class ProgressPersistenceV3 {
    @SerialName("server") SERVER,
    @SerialName("client") CLIENT,
}

@Serializable
data class PlaybackDecisionResponseV3(
    @SerialName("protocol_version") val protocolVersion: Int? = null,
    @SerialName("server_features") val serverFeatures: List<String> = emptyList(),
    val outcome: PlaybackDecisionOutcome? = null,
    @SerialName("session_id") val sessionId: String? = null,
    @Serializable(with = TolerantPlaybackPlanV3Serializer::class)
    @SerialName("playback_plan") val playbackPlan: PlaybackPlanV3? = null,
    val terminal: PlaybackTerminalV3? = null,
)

/**
 * Keeps protocol negotiation readable when an older server returns its legacy
 * playback_plan shape from the shared start endpoint. The response-level
 * protocol/feature gate must decide compatibility before a malformed or old
 * plan can turn an HTTP 200 into a transport error.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal object TolerantPlaybackPlanV3Serializer : KSerializer<PlaybackPlanV3?> {
    private val delegate = PlaybackPlanV3.serializer()
    override val descriptor: SerialDescriptor = delegate.descriptor.nullable

    override fun deserialize(decoder: Decoder): PlaybackPlanV3? {
        val jsonDecoder = decoder as? JsonDecoder
            ?: return runCatching { delegate.deserialize(decoder) }.getOrNull()
        val element = jsonDecoder.decodeJsonElement()
        if (element is JsonNull) return null
        return try {
            jsonDecoder.json.decodeFromJsonElement(delegate, element)
        } catch (_: SerializationException) {
            null
        }
    }

    override fun serialize(encoder: Encoder, value: PlaybackPlanV3?) {
        if (value == null) encoder.encodeNull() else delegate.serialize(encoder, value)
    }
}

@Serializable
data class PlaybackPlanV3(
    @SerialName("protocol_version") val protocolVersion: Int = PLAYBACK_PROTOCOL_V3,
    @SerialName("plan_id") val planId: String,
    /**
     * Server-minted opaque loop-prevention token for this plan. The client
     * stores it and echoes the keys of everything it has already attempted in
     * `attempted_plan_keys`; it never computes a key itself.
     */
    @SerialName("plan_attempt_key") val planAttemptKey: String = "",
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    val delivery: PlaybackDelivery,
    val stream: PlaybackStreamV3,
    val timeline: PlaybackTimelineV3 = PlaybackTimelineV3(),
    @SerialName("selected_tracks") val selectedTracks: SelectedPlaybackTracksV3 = SelectedPlaybackTracksV3(),
    @SerialName("effective_recipe") val effectiveRecipe: PlaybackEffectiveRecipeV3 = PlaybackEffectiveRecipeV3(),
    val claims: PlaybackValidationClaims = PlaybackValidationClaims(),
    val subtitle: PlaybackSubtitleDecisionV3 = PlaybackSubtitleDecisionV3(),
    val transformations: List<PlaybackTransformationV3> = emptyList(),
    @SerialName("applied_quirks") val appliedQuirks: List<PlaybackAppliedQuirkV3> = emptyList(),
    @SerialName("runtime_corrections") val runtimeCorrections: List<String> = emptyList(),
    /**
     * The quality rungs the server will honor for this source, in the order it
     * wants them shown. The client renders this menu; it never derives rungs
     * from the source resolution itself.
     */
    @SerialName("available_qualities") val availableQualities: List<PlaybackAvailableQualityV3> = emptyList(),
    /**
     * The [availableQualities] label this plan delivers, or null when it
     * delivers none (the source plays unchanged, or an encode falls between
     * two entries). Meaningful only when [activeQualityReported].
     */
    @SerialName("active_quality") val activeQuality: String? = null,
    /**
     * Whether the decision carrying this plan listed
     * [PLAN_ACTIVE_QUALITY_V1_FEATURE]. Not on the wire: [validateForMedia3]
     * sets it from the response's `server_features`.
     */
    @Transient val activeQualityReported: Boolean = false,
    @SerialName("degradation_warnings") val degradationWarnings: List<PlaybackDegradationWarning> = emptyList(),
    @SerialName("decision_reason") val decisionReason: String,
    @SerialName("requested_media_file_id") val requestedMediaFileId: Int? = null,
    @SerialName("effective_media_file_id") val effectiveMediaFileId: Int? = null,
    val source: PlaybackSourceDescriptorV3 = PlaybackSourceDescriptorV3(),
    @SerialName("subtitle_fidelity_policy") val subtitleFidelityPolicy: String? = null,
    /** Present only when a learned limit lowered this plan. */
    @SerialName("bandwidth_limit") val bandwidthLimit: PlaybackBandwidthLimitV3? = null,
)

/** A learned bandwidth limit that lowered Auto for this plan. */
@Serializable
data class PlaybackBandwidthLimitV3(
    val kbps: Int,
    val source: String = BANDWIDTH_ESTIMATE_SOURCE_OBSERVED_REBUFFER,
    @SerialName("expires_at") val expiresAt: String? = null,
)

/** One selectable rung of [PlaybackPlanV3.availableQualities]. */
@Serializable
data class PlaybackAvailableQualityV3(
    val label: String,
    val height: Int = 0,
    @SerialName("bitrate_kbps") val bitrateKbps: Int = 0,
    @SerialName("preserves_source") val preservesSource: Boolean = false,
    @SerialName("display_name") val displayName: String? = null,
)

/**
 * Facts about the media file the plan resolved to, as opposed to the transport
 * carrying it. Defaulted throughout so a server that predates the descriptor
 * still decodes.
 */
@Serializable
data class PlaybackSourceDescriptorV3(
    @SerialName("media_file_id") val mediaFileId: Int? = null,
    /**
     * Full runtime of the source, or null when the server does not know it.
     *
     * Null must survive as null: `SiloJson` sets `coerceInputValues`, so a
     * non-nullable `Double` here would silently become 0.0 — the very value
     * this field exists to stop the player inventing.
     *
     * This is the whole file, never `total - sourceStartSeconds`, and it is
     * never adjusted by `timelineOffsetSeconds`. Do not substitute the
     * engine's reported duration for it: on an HLS copy remux the engine
     * reports the window produced so far, not the runtime.
     */
    @SerialName("duration_seconds") val durationSeconds: Double? = null,
    val container: String? = null,
    @SerialName("video_codec") val videoCodec: String? = null,
    @SerialName("color_range") val colorRange: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    /** The source file's bitrate; 0 when the server did not report it. */
    @SerialName("bitrate_kbps") val bitrateKbps: Int = 0,
    @SerialName("dynamic_range") val dynamicRange: String? = null,
    @SerialName("audio_codec") val audioCodec: String? = null,
    @SerialName("letterbox_top_fraction") val letterboxTopFraction: Double = 0.0,
    @SerialName("letterbox_bottom_fraction") val letterboxBottomFraction: Double = 0.0,
)

@Serializable
data class PlaybackAppliedQuirkV3(
    val id: String,
    @SerialName("registry_revision") val registryRevision: String,
    val action: String,
    val reason: String? = null,
)

@Serializable
data class PlaybackStreamV3(
    val url: String,
    val protocol: PlaybackStreamProtocol,
    val container: String? = null,
    @SerialName("mime_type") val mimeType: String? = null,
    val headers: Map<String, String> = emptyMap(),
    @SerialName("header_refresh") val headerRefresh: PlaybackHeaderRefreshMode = PlaybackHeaderRefreshMode.SESSION,
    @SerialName("header_refresh_url") val headerRefreshUrl: String? = null,
    @kotlinx.serialization.Transient val auxiliaryRequestHeaders: org.siloserver.silo.network.apiv2.ProxyAuxiliaryRequestHeaders? = null,
) {
    val effectiveRequestHeaders: Map<String, String> get() = auxiliaryRequestHeaders ?: headers
}

@Serializable
data class PlaybackTimelineV3(
    @SerialName("source_start_seconds") val sourceStartSeconds: Double = 0.0,
    @SerialName("stream_origin_seconds") val streamOriginSeconds: Double = 0.0,
    @SerialName("player_start_seconds") val playerStartSeconds: Double = 0.0,
    @SerialName("timeline_offset_seconds") val timelineOffsetSeconds: Double = 0.0,
    @SerialName("seek_window_start_seconds") val seekWindowStartSeconds: Double? = null,
    @SerialName("seek_window_end_seconds") val seekWindowEndSeconds: Double? = null,
    @SerialName("can_seek_anywhere") val canSeekAnywhere: Boolean = true,
    @SerialName("seek_restoration") val seekRestoration: String = "player_position",
)

@Serializable
data class PlaybackTrackIdentityV3(
    val id: String,
    val index: Int? = null,
)

@Serializable
data class SelectedPlaybackTracksV3(
    val audio: PlaybackTrackIdentityV3? = null,
    val subtitle: PlaybackTrackIdentityV3? = null,
)

@Serializable
data class PlaybackEffectiveRecipeV3(
    @SerialName("video_codec") val videoCodec: String? = null,
    @SerialName("audio_codec") val audioCodec: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    @SerialName("frame_rate") val frameRate: Double? = null,
    @SerialName("bitrate_kbps") val bitrateKbps: Int? = null,
    @SerialName("dynamic_range") val dynamicRange: String? = null,
    @SerialName("audio_channels") val audioChannels: Int? = null,
    @SerialName("audio_layout") val audioLayout: String? = null,
)

@Serializable
data class PlaybackSubtitleArtifactV3(
    val url: String,
    @SerialName("mime_type") val mimeType: String,
    val format: String,
    @SerialName("timing_origin_seconds") val timingOriginSeconds: Double = 0.0,
)

@Serializable
data class PlaybackEmbeddedSubtitleV3(
    @SerialName("stream_index") val streamIndex: Int,
    @SerialName("container_track_id") val containerTrackId: String? = null,
)

/**
 * The selected track as a `SUBTITLES` rendition of the plan's HLS stream: the
 * stream URL is then a multivariant playlist with one `EXT-X-MEDIA` in
 * [groupId] named [name]. The player selects it there and fetches no sidecar.
 */
@Serializable
data class PlaybackSubtitleHlsRenditionV3(
    @SerialName("group_id") val groupId: String,
    val name: String,
    val format: String,
    @SerialName("mime_type") val mimeType: String,
    /**
     * 1-based place in the group, set when the group lists every embedded
     * text track ([HLS_SUBTITLE_RENDITIONS_ALL_V1_FEATURE]); equal to the
     * selected row's [PlaybackSubtitleInventoryHlsRenditionV3.index].
     */
    val index: Int? = null,
)

/**
 * An inventory row's track among the HLS stream's subtitle renditions, under
 * [HLS_SUBTITLE_RENDITIONS_ALL_V1_FEATURE]: the `EXT-X-MEDIA` of [groupId]
 * named [name], at 1-based [index] in the group.
 */
@Serializable
data class PlaybackSubtitleInventoryHlsRenditionV3(
    @SerialName("group_id") val groupId: String,
    val name: String,
    val index: Int,
)

@Serializable
data class PlaybackSubtitleDecisionV3(
    val mode: PlaybackSubtitleModeV3 = PlaybackSubtitleModeV3.OFF,
    @SerialName("track_id") val trackId: String? = null,
    val artifact: PlaybackSubtitleArtifactV3? = null,
    val embedded: PlaybackEmbeddedSubtitleV3? = null,
    @SerialName("hls_rendition") val hlsRendition: PlaybackSubtitleHlsRenditionV3? = null,
    /**
     * The complete, gap-free combined-ordinal subtitle list for the effective
     * source. Authoritative: select a track by echoing an entry's
     * [PlaybackSubtitleInventoryItemV3.trackId] or
     * [PlaybackSubtitleInventoryItemV3.combinedIndex], never by counting tracks
     * or taking `max(index) + 1`.
     */
    val inventory: List<PlaybackSubtitleInventoryItemV3> = emptyList(),
)

/** One selectable subtitle track at its frozen combined ordinal. */
@Serializable
data class PlaybackSubtitleInventoryItemV3(
    @SerialName("track_id") val trackId: String,
    @SerialName("combined_index") val combinedIndex: Int,
    val source: String,
    val codec: String? = null,
    val language: String? = null,
    val label: String? = null,
    val forced: Boolean = false,
    @SerialName("default") val isDefault: Boolean = false,
    @SerialName("hearing_impaired") val hearingImpaired: Boolean = false,
    /** `sidecar` or `burn_in_only`; the last carries no [url]. */
    val delivery: String = "",
    val url: String? = null,
    @SerialName("font_bundle_url") val fontBundleUrl: String? = null,
    /**
     * Opaque name of this track for the subtitle sync operations, present on
     * external and downloaded tracks whose timing the server can correct.
     */
    @SerialName("sync_key") val syncKey: String? = null,
    /**
     * Embedded rows only: the absolute probed stream index. A server that
     * sends it also sends [nativeSelectable] whenever that is true.
     */
    @SerialName("stream_index") val streamIndex: Int? = null,
    /** Embedded rows only: the Matroska TrackNumber or MP4 track ID, when known. */
    @SerialName("container_track_id") val containerTrackId: String? = null,
    /**
     * Selecting this embedded row under the plan's delivery, capabilities and
     * audio would give the plan `subtitle.embedded`, so the player may switch
     * to it on the stream it already plays. Omitted (null) when false.
     */
    @SerialName("native_selectable") val nativeSelectable: Boolean? = null,
    /** The row's track among the HLS stream's own subtitle renditions. */
    @SerialName("hls_rendition") val hlsRendition: PlaybackSubtitleInventoryHlsRenditionV3? = null,
)

/** Resolves the optional ordinal from the stable server track identity. */
fun PlaybackPlanV3.resolvedSelectedSubtitleIndex(): Int? =
    selectedTracks.subtitle?.let { selected ->
        selected.index ?: subtitle.inventory
            .firstOrNull { it.trackId == selected.id }
            ?.combinedIndex
    }

@Serializable
data class PlaybackTransformationV3(
    val name: String,
    val executor: PlaybackTransformationExecutor = PlaybackTransformationExecutor.SERVER,
    @SerialName("recipe_version") val recipeVersion: String = "1",
    @SerialName("validated_claims") val validatedClaims: List<String> = emptyList(),
)

@Serializable
enum class PlaybackTransformationExecutor {
    @SerialName("client") CLIENT,
    @SerialName("server") SERVER,
}

@Serializable
data class PlaybackTerminalV3(
    val reason: String,
    val message: String,
    val retryable: Boolean = false,
)

@Serializable
data class PlaybackStartRequestV3(
    @SerialName("protocol_version") val protocolVersion: Int = PLAYBACK_PROTOCOL_V3,
    @SerialName("client_features") val clientFeatures: List<String>,
    @SerialName("file_id") val fileId: Int,
    @SerialName("profile_id") val profileId: String,
    @SerialName("playback_attempt_id") val playbackAttemptId: String,
    @SerialName("quality_preference") val qualityPreference: String = "auto",
    @SerialName("subtitle_fidelity_preference") val subtitleFidelityPreference: SubtitleFidelityPreference,
    @SerialName("start_position") val startPosition: Double? = null,
    @SerialName("progress_persistence") val progressPersistence: ProgressPersistenceV3 = ProgressPersistenceV3.SERVER,
    @SerialName("audio_track_id") val audioTrackId: String? = null,
    @SerialName("audio_track_index") val audioTrackIndex: Int? = null,
    @SerialName("subtitle_track_id") val subtitleTrackId: String? = null,
    @SerialName("subtitle_track_index") val subtitleTrackIndex: Int? = null,
    /**
     * Always false, here and on replans. The server ignores it; older servers
     * require it on `/api/v2` and drop Auto to their metered limit when it is
     * true. The phone applies its own cellular quality pair instead.
     */
    val metered: Boolean = false,
    @SerialName("bandwidth_cap_kbps") val bandwidthCapKbps: Int? = null,
    /**
     * The highest current reading for this server and network, a speed test
     * or a playback sample: a hint Auto uses to rule out a source the link
     * clearly cannot carry. Absent without one, and on metered or cellular
     * networks. Media3's bandwidth meter is never sent: it times
     * loads that stall on a full buffer or wait on a live transcode, so its
     * figure can understate the link and make Auto convert.
     */
    @SerialName("bandwidth_estimate_kbps") val bandwidthEstimateKbps: Int? = null,
    /**
     * [BANDWIDTH_ESTIMATE_SOURCE_PROBE] alongside [bandwidthEstimateKbps];
     * needs [BUFFERING_QUALITY_V1_FEATURE].
     */
    @SerialName("bandwidth_estimate_source") val bandwidthEstimateSource: String? = null,
    /**
     * `ignore` in `never` mode, so a limit learned by another profile on this
     * device does not reach this one, and on every Cast request. Sent only to
     * a server advertising [BUFFERING_QUALITY_V1_FEATURE].
     */
    @SerialName("learned_limit") val learnedLimit: String? = null,
    @SerialName("client_capabilities") val capabilities: ClientCodecCapabilities,
    @SerialName("client_playback_context") val clientPlaybackContext: ClientPlaybackContext,
    /**
     * False keeps this attempt on [fileId] through every replan: the server
     * refuses rather than substitutes another version. Watch Party starts send
     * false so every viewer stays on the room's file. Null omits the field and
     * keeps the server default for solo playback.
     */
    @SerialName("allow_alternate_versions") val allowAlternateVersions: Boolean? = null,
)

@Serializable
data class PlaybackFailureV3(
    val classification: String,
    val message: String? = null,
    @SerialName("decoder_name") val decoderName: String? = null,
)

@Serializable
data class PlaybackReplanRequestV3(
    @SerialName("protocol_version") val protocolVersion: Int = PLAYBACK_PROTOCOL_V3,
    @SerialName("client_features") val clientFeatures: List<String>,
    /** Omitted means [FAILURE_RECOVERY_V3_OPERATION]. */
    val operation: String? = null,
    @SerialName("playback_attempt_id") val playbackAttemptId: String,
    @SerialName("replan_request_id") val replanRequestId: String,
    @SerialName("failed_plan_id") val failedPlanId: String,
    @SerialName("plan_attempt_id") val planAttemptId: String,
    @SerialName("plan_attempt_key") val planAttemptKey: String,
    @SerialName("attempted_plan_keys") val attemptedPlanKeys: List<String>,
    /**
     * Client-applied mutations the server should fold into the next attempt
     * key, so two plans that differ only by something the client did locally do
     * not collide. The client reports the mutations; the server does the
     * hashing.
     */
    @SerialName("local_mutations") val localMutations: List<String> = emptyList(),
    @SerialName("attempt_count") val attemptCount: Int,
    @SerialName("quality_preference") val qualityPreference: String = "auto",
    @SerialName("position_seconds") val positionSeconds: Double,
    /** Always false; see [PlaybackStartRequestV3.metered]. */
    val metered: Boolean = false,
    @SerialName("bandwidth_cap_kbps") val bandwidthCapKbps: Int? = null,
    /**
     * The buffering-quality step-down estimate: on the `quality_change` replan
     * that lowers Auto after repeated rebuffering, then without a source on
     * later Auto replans the client sends for its own reasons. Media3's
     * bandwidth meter is never sent.
     */
    @SerialName("bandwidth_estimate_kbps") val bandwidthEstimateKbps: Int? = null,
    /** Valid only alongside [bandwidthEstimateKbps]; needs [BUFFERING_QUALITY_V1_FEATURE]. */
    @SerialName("bandwidth_estimate_source") val bandwidthEstimateSource: String? = null,
    /**
     * The playing plan's throughput so far, measured as `plan_summary`'s
     * `throughput_kbps`. Valid only on a lowering, with
     * [BANDWIDTH_ESTIMATE_SOURCE_OBSERVED_REBUFFER]; the server plans with the
     * lower of this and [bandwidthEstimateKbps]. Needs [BUFFERING_QUALITY_V1_FEATURE].
     */
    @SerialName("observed_throughput_kbps") val observedThroughputKbps: Int? = null,
    /** `ignore` or `clear`; needs [BUFFERING_QUALITY_V1_FEATURE]. */
    @SerialName("learned_limit") val learnedLimit: String? = null,
    @SerialName("selected_tracks") val selectedTracks: SelectedPlaybackTracksV3,
    /**
     * Absent for intent operations ([INTENT_V3_OPERATIONS]), which describe a
     * user's choice, and for [SEEK_REANCHOR_V3_OPERATION], which requests a
     * different transport anchor rather than reporting a route failure.
     */
    val failure: PlaybackFailureV3? = null,
    @SerialName("client_capabilities") val capabilities: ClientCodecCapabilities,
    @SerialName("client_playback_context") val clientPlaybackContext: ClientPlaybackContext,
)

@Serializable
data class PlaybackRouteEventV3(
    @SerialName("protocol_version") val protocolVersion: Int = PLAYBACK_PROTOCOL_V3,
    @SerialName("playback_attempt_id") val playbackAttemptId: String,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("plan_id") val planId: String? = null,
    @SerialName("plan_attempt_id") val planAttemptId: String? = null,
    @SerialName("plan_attempt_key") val planAttemptKey: String? = null,
    val event: String,
    @SerialName("failure_classification") val failureClassification: String? = null,
    @SerialName("fallback_reason") val fallbackReason: String? = null,
    @SerialName("applied_quirk_ids") val appliedQuirkIds: List<String> = emptyList(),
    @SerialName("quirk_registry_revision") val quirkRegistryRevision: String? = null,
    @SerialName("output_context_id") val outputContextId: String? = null,
    val diagnostics: Map<String, String> = emptyMap(),
)

sealed interface PlaybackV3Validation {
    data class Playable(val plan: PlaybackPlanV3, val sessionId: String) : PlaybackV3Validation
    data class Terminal(val reason: String, val message: String, val retryable: Boolean) : PlaybackV3Validation
    data class Incompatible(val allocatedSessionId: String?) : PlaybackV3Validation
    data class ReplanRequired(val reason: String, val plan: PlaybackPlanV3, val sessionId: String) : PlaybackV3Validation
}

/**
 * Subtitle codecs, by source container, that Android selects natively from an
 * original-HTTP stream by `container_track_id`. MP4 `mov_text` maps to the
 * `tkhd` track ID and MKV SubRip/ASS to the Matroska TrackNumber, both of which
 * Media3 sets as `Format.id`. The capability detector advertises these pairs
 * and [validateForMedia3] accepts them, so the two share one container/codec
 * list. Both also assume every entry is identified by `container_track_id`:
 * only add a pair whose Media3 track ID is the container's own track ID.
 */
val NATIVE_EMBEDDED_SUBTITLE_CODECS: Map<String, List<String>> = mapOf(
    "mp4" to listOf("mov_text"),
    "mkv" to listOf("subrip", "ass"),
)

/**
 * Normalizes a subtitle codec name the way the server does when it matches a
 * native capability, so a plan it issues for an alias is not rejected here.
 */
private fun nativeEmbeddedSubtitleCodec(codec: String?): String? =
    when (val normalized = codec?.trim()?.lowercase()) {
        "srt" -> "subrip"
        "tx3g" -> "mov_text"
        else -> normalized
    }

private fun isNativeEmbeddedSubtitlePair(container: String?, codec: String?): Boolean {
    val codecs = NATIVE_EMBEDDED_SUBTITLE_CODECS[container?.trim()?.lowercase()] ?: return false
    return nativeEmbeddedSubtitleCodec(codec) in codecs
}

/**
 * Whether this delivery's HLS stream can carry the selected embedded text
 * track as a `SUBTITLES` rendition: a transcode, and a copy remux once the
 * client lists [HLS_REMUX_SUBTITLE_RENDITIONS_V1_FEATURE].
 */
fun PlaybackDelivery.carriesHlsSubtitleRendition(): Boolean =
    this == PlaybackDelivery.SERVER_TRANSCODE_HLS || this == PlaybackDelivery.SERVER_REMUX_HLS

fun PlaybackDecisionResponseV3.validateForMedia3(): PlaybackV3Validation {
    if (protocolVersion != PLAYBACK_PROTOCOL_V3 ||
        PLAYBACK_PLAN_V3_FEATURE !in serverFeatures ||
        NEUTRAL_PLAYBACK_V3_CONTRACT_FEATURE !in serverFeatures
    ) {
        return PlaybackV3Validation.Incompatible(sessionId)
    }
    if (outcome == PlaybackDecisionOutcome.ADAPTATION_UNAVAILABLE) {
        val value = terminal ?: return PlaybackV3Validation.Terminal(
            reason = "invalid_terminal_response",
            message = "The server returned an incomplete playback terminal response.",
            retryable = false,
        )
        return PlaybackV3Validation.Terminal(value.reason, value.message, value.retryable)
    }
    if (outcome != PlaybackDecisionOutcome.PLAYABLE) {
        return PlaybackV3Validation.Terminal(
            "invalid_playback_outcome",
            "The server returned no recognized playback outcome.",
            false,
        )
    }
    val plan = playbackPlan
        ?.copy(activeQualityReported = PLAN_ACTIVE_QUALITY_V1_FEATURE in serverFeatures)
        ?: return PlaybackV3Validation.Terminal("invalid_playback_plan", "The server returned no executable playback plan.", false)
    val resolvedSessionId = plan.sessionId ?: sessionId
        ?: return PlaybackV3Validation.Terminal("invalid_playback_plan", "The server returned no playback session identity.", false)
    if (plan.protocolVersion != PLAYBACK_PROTOCOL_V3) {
        return PlaybackV3Validation.Terminal("invalid_playback_plan", "The server returned an unsupported plan version.", false)
    }
    if (plan.planAttemptKey.isBlank()) {
        return PlaybackV3Validation.Terminal(
            "invalid_playback_plan",
            "The server returned no plan-attempt identity.",
            false,
        )
    }
    if (!plan.hasValidSubtitleInventory()) {
        return PlaybackV3Validation.Terminal(
            "invalid_playback_plan",
            "The server returned an invalid subtitle inventory.",
            false,
        )
    }
    val selectedSubtitle = plan.selectedTracks.subtitle?.let { selected ->
        plan.subtitle.inventory.firstOrNull {
            it.trackId == selected.id &&
                (selected.index == null || it.combinedIndex == selected.index)
        }
    }
    if (selectedSubtitle != null &&
        selectedSubtitle.delivery !in setOf(
            SUBTITLE_DELIVERY_SIDECAR,
            SUBTITLE_DELIVERY_BURN_IN_ONLY,
        )
    ) {
        return PlaybackV3Validation.ReplanRequired(
            "unsupported_subtitle_delivery:${selectedSubtitle.delivery}",
            plan,
            resolvedSessionId,
        )
    }
    plan.subtitle.embedded?.let { native ->
        if (plan.delivery != PlaybackDelivery.ORIGINAL_HTTP || plan.subtitle.mode != PlaybackSubtitleModeV3.RENDER ||
            plan.subtitle.artifact != null || native.streamIndex < 0 ||
            !isCanonicalContainerTrackId(native.containerTrackId) ||
            selectedSubtitle?.source != "embedded" ||
            !isNativeEmbeddedSubtitlePair(plan.source.container, selectedSubtitle.codec) ||
            plan.subtitle.trackId != plan.selectedTracks.subtitle?.id
        ) return PlaybackV3Validation.ReplanRequired(SUBTITLE_EMBEDDED_FAILED_CLASSIFICATION, plan, resolvedSessionId)
    }
    plan.subtitle.hlsRendition?.let { rendition ->
        if (!plan.delivery.carriesHlsSubtitleRendition() || plan.stream.protocol != PlaybackStreamProtocol.HLS ||
            (plan.subtitle.mode != PlaybackSubtitleModeV3.RENDER && plan.subtitle.mode != PlaybackSubtitleModeV3.CONVERT) ||
            plan.subtitle.artifact != null || plan.subtitle.embedded != null ||
            rendition.groupId.isBlank() || rendition.name.isBlank() ||
            selectedSubtitle == null || plan.subtitle.trackId != plan.selectedTracks.subtitle?.id
        ) return PlaybackV3Validation.ReplanRequired(SUBTITLE_HLS_RENDITION_FAILED_CLASSIFICATION, plan, resolvedSessionId)
    }
    if (plan.stream.url.isBlank()) {
        return PlaybackV3Validation.Terminal("invalid_playback_plan", "The server returned an empty stream URL.", false)
    }
    if (plan.stream.headerRefresh == PlaybackHeaderRefreshMode.REFRESH_ENDPOINT) {
        return PlaybackV3Validation.Terminal(
            "unsupported_header_refresh",
            "This playback plan requires a header refresh mode the Android client cannot execute.",
            false,
        )
    }
    val unsupportedRuntimeCorrection = plan.runtimeCorrections.firstOrNull {
        it !in setOf(
            CLIENT_DV8_HDR10_PLUS_SANITIZER,
            CLIENT_POST_RESUME_VIDEO_RECOVERY,
            CLIENT_SURFACE_RECOVERY,
        )
    }
    if (unsupportedRuntimeCorrection != null) {
        return PlaybackV3Validation.ReplanRequired(
            "unsupported_client_runtime_correction:$unsupportedRuntimeCorrection",
            plan,
            resolvedSessionId,
        )
    }
    val unsupportedClientTransform = plan.transformations.firstOrNull {
        it.executor == PlaybackTransformationExecutor.CLIENT &&
            (it.recipeVersion != CLIENT_DV_TRANSFORM_RECIPE_VERSION ||
                it.name !in setOf(CLIENT_DV7_TO_DV81, CLIENT_DV7_TO_HDR10))
    }
    if (unsupportedClientTransform != null) {
        return PlaybackV3Validation.ReplanRequired(
            "unsupported_client_transformation:${unsupportedClientTransform.name}:${unsupportedClientTransform.recipeVersion}",
            plan,
            resolvedSessionId,
        )
    }
    val requestedClientVideoTransforms = plan.transformations.filter {
        it.executor == PlaybackTransformationExecutor.CLIENT &&
            it.name in setOf(CLIENT_DV7_TO_DV81, CLIENT_DV7_TO_HDR10)
    }
    if (requestedClientVideoTransforms.size > 1) {
        return PlaybackV3Validation.ReplanRequired(
            "conflicting_client_video_transformations",
            plan,
            resolvedSessionId,
        )
    }
    // A client-side Dolby Vision rewrite edits the elementary stream on its way
    // into the decoder, which the client only owns when it is playing the
    // original file. On any server-produced delivery the server already made
    // the dynamic-range decision and there is nothing left to rewrite.
    if (plan.transformations.any { it.executor == PlaybackTransformationExecutor.CLIENT } &&
        plan.delivery != PlaybackDelivery.ORIGINAL_HTTP
    ) {
        return PlaybackV3Validation.ReplanRequired(
            "client_transformation_requires_original_delivery",
            plan,
            resolvedSessionId,
        )
    }
    if (plan.delivery == PlaybackDelivery.ORIGINAL_HTTP &&
        plan.transformations.any { it.executor == PlaybackTransformationExecutor.SERVER }
    ) {
        return PlaybackV3Validation.ReplanRequired("invalid_original_server_transformation", plan, resolvedSessionId)
    }
    return PlaybackV3Validation.Playable(plan, resolvedSessionId)
}

private fun PlaybackPlanV3.hasValidSubtitleInventory(): Boolean {
    if (subtitle.inventory.map { it.combinedIndex }.sorted() != subtitle.inventory.indices.toList()) {
        return false
    }
    if (subtitle.inventory.any { it.trackId.isBlank() } ||
        subtitle.inventory.map { it.trackId }.distinct().size != subtitle.inventory.size
    ) {
        return false
    }
    if (subtitle.inventory.any { item ->
            when (item.delivery) {
                SUBTITLE_DELIVERY_SIDECAR -> item.url.isNullOrBlank()
                SUBTITLE_DELIVERY_BURN_IN_ONLY -> !item.url.isNullOrBlank()
                else -> true
            }
        }
    ) {
        return false
    }
    val selected = selectedTracks.subtitle
    if ((subtitle.artifact != null || subtitle.hlsRendition != null) && selected == null) return false
    if (selected == null) return true
    return subtitle.inventory.any {
        it.trackId == selected.id &&
            (selected.index == null || it.combinedIndex == selected.index)
    }
}

/** A positive 32-bit track number written the way the server writes it ("4", never "04"). */
private fun isCanonicalContainerTrackId(id: String?): Boolean {
    val number = id?.toLongOrNull() ?: return false
    return number in 1..Int.MAX_VALUE.toLong() && id == number.toString()
}

/**
 * The container track the player may switch [row] to on the `original_http`
 * stream it already plays, before the server answers, or null.
 *
 * The server says so with `native_selectable`, which it computes with the
 * planner's own rules for this plan's capabilities, delivery and audio. A
 * server that sends `stream_index` omits `native_selectable` exactly when it is
 * false. Without either field the row comes from a server that predates them,
 * and the client evaluates the row itself with the rules [validateForMedia3]
 * applies to a native plan. That needs the row's `container_track_id`, so in
 * practice only a row a newer server describes is ever switched locally. ASS is
 * left out of that fallback: whether it plays natively also depends on the
 * fidelity preference and on libass, which only the server's answer reflects.
 * Whether Media3 actually lists the track is the caller's check.
 */
fun PlaybackPlanV3.nativeSwitchTrackId(row: PlaybackSubtitleInventoryItemV3): String? {
    if (delivery != PlaybackDelivery.ORIGINAL_HTTP || subtitle.mode == PlaybackSubtitleModeV3.BURN_IN) return null
    if (row.source != "embedded") return null
    val trackId = row.containerTrackId?.takeIf(::isCanonicalContainerTrackId) ?: return null
    if (subtitle.inventory.count { it.containerTrackId == trackId } != 1) return null
    val selectable = when {
        row.nativeSelectable != null -> row.nativeSelectable
        row.streamIndex != null -> false
        else -> isNativeEmbeddedSubtitlePair(source.container, row.codec) &&
            nativeEmbeddedSubtitleCodec(row.codec) != "ass"
    }
    return trackId.takeIf { selectable }
}

/**
 * [row]'s rendition among the HLS stream's subtitle renditions, which the
 * player may switch to on the stream it already plays, or null. Under
 * [HLS_SUBTITLE_RENDITIONS_ALL_V1_FEATURE] every listed embedded text track has
 * one; a malformed or duplicated entry is ignored, so that row keeps its
 * sidecar.
 */
fun PlaybackPlanV3.switchableHlsRendition(row: PlaybackSubtitleInventoryItemV3): PlaybackSubtitleInventoryHlsRenditionV3? {
    if (!delivery.carriesHlsSubtitleRendition() || stream.protocol != PlaybackStreamProtocol.HLS) return null
    if (subtitle.mode == PlaybackSubtitleModeV3.BURN_IN) return null
    val rendition = row.hlsRendition ?: return null
    if (rendition.groupId.isBlank() || rendition.name.isBlank() || rendition.index < 1) return null
    val sameName = subtitle.inventory.count {
        it.hlsRendition?.groupId == rendition.groupId && it.hlsRendition.name == rendition.name
    }
    return rendition.takeIf { sameName == 1 }
}

/**
 * Whether the player can apply this plan to the media [current] is already
 * playing, without remounting it: the server kept the transport (session,
 * stream URL and headers, effective file, recipe, timeline offset, audio) and
 * changed only the subtitle decision, to one the player selects in that media.
 *
 * - On `original_http`: Off, or a native `subtitle.embedded` track. The server
 *   answers a switch to a `native_selectable` row this way.
 * - On an HLS transcode or copy remux under
 *   [HLS_SUBTITLE_RENDITIONS_ALL_V1_FEATURE]: Off, or one of the renditions
 *   the playing stream already lists, when both plans list the same set. The
 *   server then keeps the running stream and its generation.
 *
 * Anything else, including a sidecar artifact, needs a remount. The plan ids
 * differ either way: the selected subtitle is part of the server's plan
 * identity.
 */
fun PlaybackPlanV3.appliesInPlaceOver(current: PlaybackPlanV3, sessionId: String?, currentSessionId: String?): Boolean {
    if (sessionId == null || sessionId != currentSessionId) return false
    if (delivery != current.delivery) return false
    if (stream.url != current.stream.url ||
        stream.protocol != current.stream.protocol ||
        stream.container != current.stream.container ||
        stream.mimeType != current.stream.mimeType ||
        stream.headers != current.stream.headers ||
        stream.headerRefresh != current.stream.headerRefresh ||
        stream.headerRefreshUrl != current.stream.headerRefreshUrl
    ) return false
    if ((effectiveMediaFileId ?: requestedMediaFileId) != (current.effectiveMediaFileId ?: current.requestedMediaFileId)) {
        return false
    }
    if (source.container != current.source.container || effectiveRecipe != current.effectiveRecipe) return false
    if (timeline.timelineOffsetSeconds != current.timeline.timelineOffsetSeconds ||
        timeline.streamOriginSeconds != current.timeline.streamOriginSeconds
    ) return false
    if (selectedTracks.audio != current.selectedTracks.audio) return false
    if (claims.video != current.claims.video || claims.audio != current.claims.audio) return false
    if (transformations.toSet() != current.transformations.toSet() ||
        runtimeCorrections.toSet() != current.runtimeCorrections.toSet() ||
        appliedQuirks.toSet() != current.appliedQuirks.toSet() ||
        activeOriginalHttpClaims() != current.activeOriginalHttpClaims()
    ) return false
    if (subtitle.artifact != null ||
        subtitle.mode == PlaybackSubtitleModeV3.BURN_IN ||
        current.subtitle.mode == PlaybackSubtitleModeV3.BURN_IN
    ) return false
    return when {
        delivery == PlaybackDelivery.ORIGINAL_HTTP -> subtitle.hlsRendition == null && when (subtitle.mode) {
            PlaybackSubtitleModeV3.OFF -> subtitle.embedded == null
            PlaybackSubtitleModeV3.RENDER -> isCanonicalContainerTrackId(subtitle.embedded?.containerTrackId)
            else -> false
        }
        delivery.carriesHlsSubtitleRendition() -> {
            val renditions = subtitle.inventory.mapNotNull { row ->
                switchableHlsRendition(row)?.let { row.combinedIndex to it }
            }
            val playing = current.subtitle.inventory.mapNotNull { row ->
                current.switchableHlsRendition(row)?.let { row.combinedIndex to it }
            }
            val selected = subtitle.hlsRendition
            renditions.isNotEmpty() && renditions == playing && subtitle.embedded == null && when (subtitle.mode) {
                PlaybackSubtitleModeV3.OFF -> selected == null
                PlaybackSubtitleModeV3.RENDER,
                PlaybackSubtitleModeV3.CONVERT,
                -> selected != null && renditions.any { (_, it) -> it.groupId == selected.groupId && it.name == selected.name }
                else -> false
            }
        }
        else -> false
    }
}

/**
 * This plan as the player renders it after an audio switch it made on the
 * mounted file alone, with no replan. On `original_http` every audio track is
 * in the file, and a subtitle pick's replan carries the audio the viewer has,
 * the plan's or one switched that way. When [requestedAudio] is not this
 * plan's audio and [answer] selects it, the player already renders [answer]'s
 * audio, so its track, claims and recipe stand in for this plan's and
 * [appliesInPlaceOver] compares what is actually playing.
 */
fun PlaybackPlanV3.withPlayerSwitchedAudio(requestedAudio: PlaybackTrackIdentityV3?, answer: PlaybackPlanV3): PlaybackPlanV3 {
    val requested = requestedAudio?.index ?: return this
    if (delivery != PlaybackDelivery.ORIGINAL_HTTP || answer.delivery != PlaybackDelivery.ORIGINAL_HTTP) return this
    if (selectedTracks.audio?.index == requested || answer.selectedTracks.audio?.index != requested) return this
    return copy(
        selectedTracks = selectedTracks.copy(audio = answer.selectedTracks.audio),
        claims = claims.copy(audio = answer.claims.audio),
        effectiveRecipe = effectiveRecipe.copy(
            audioCodec = answer.effectiveRecipe.audioCodec,
            audioChannels = answer.effectiveRecipe.audioChannels,
            audioLayout = answer.effectiveRecipe.audioLayout,
        ),
    )
}

fun PlaybackPlanV3.executableMedia3ClientTransformations(): List<String> =
    transformations.executableMedia3ClientTransformations()

fun PlaybackExecutionPlan.executableMedia3ClientTransformations(): List<String> =
    transformations.executableMedia3ClientTransformations()

private fun List<PlaybackTransformationV3>.executableMedia3ClientTransformations(): List<String> = this
    .filter {
        it.executor == PlaybackTransformationExecutor.CLIENT &&
            it.recipeVersion == CLIENT_DV_TRANSFORM_RECIPE_VERSION &&
            it.name in setOf(CLIENT_DV7_TO_DV81, CLIENT_DV7_TO_HDR10)
    }
    .map { it.name }

/**
 * Delivery-scoped claims the plan's decision relies on, derived from the
 * decision reason. The server does not echo claims back; the decision reason
 * is the contract for which claim an original_http plan exercised.
 */
fun PlaybackExecutionPlan.activeOriginalHttpClaims(): List<String> =
    activeOriginalHttpClaimsFor(delivery, decisionTrace.firstOrNull())

fun PlaybackPlanV3.activeOriginalHttpClaims(): List<String> =
    activeOriginalHttpClaimsFor(delivery, decisionReason)

private fun activeOriginalHttpClaimsFor(delivery: PlaybackDelivery, decisionReason: String?): List<String> =
    if (delivery == PlaybackDelivery.ORIGINAL_HTTP && decisionReason == DECISION_REASON_CLIENT_DV8_BASE_LAYER) {
        listOf(CLIENT_DV8_BASE_LAYER_FALLBACK_V1_CLAIM)
    } else {
        emptyList()
    }
