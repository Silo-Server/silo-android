package org.siloserver.silo.model.playback

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
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
const val NATIVE_HLS_PLAYBACK_V1_FEATURE = "native_hls_playback_v1"

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

/** The quality rung that asks the server to preserve the source as-is. */
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
    if (!context.output.audioPassthrough?.entries.isNullOrEmpty()) {
        add(LAYOUT_AWARE_PASSTHROUGH_FEATURE)
    }
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
    @SerialName("degradation_warnings") val degradationWarnings: List<PlaybackDegradationWarning> = emptyList(),
    @SerialName("decision_reason") val decisionReason: String,
    @SerialName("requested_media_file_id") val requestedMediaFileId: Int? = null,
    @SerialName("effective_media_file_id") val effectiveMediaFileId: Int? = null,
    val source: PlaybackSourceDescriptorV3 = PlaybackSourceDescriptorV3(),
    @SerialName("subtitle_fidelity_policy") val subtitleFidelityPolicy: String? = null,
)

/** One selectable rung of [PlaybackPlanV3.availableQualities]. */
@Serializable
data class PlaybackAvailableQualityV3(
    val label: String,
    val height: Int = 0,
    @SerialName("bitrate_kbps") val bitrateKbps: Int = 0,
    @SerialName("preserves_source") val preservesSource: Boolean = false,
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
)

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
data class PlaybackSubtitleDecisionV3(
    val mode: PlaybackSubtitleModeV3 = PlaybackSubtitleModeV3.OFF,
    @SerialName("track_id") val trackId: String? = null,
    val artifact: PlaybackSubtitleArtifactV3? = null,
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
    val metered: Boolean = false,
    @SerialName("bandwidth_estimate_kbps") val bandwidthEstimateKbps: Int? = null,
    @SerialName("bandwidth_cap_kbps") val bandwidthCapKbps: Int? = null,
    @SerialName("client_capabilities") val capabilities: ClientCodecCapabilities,
    @SerialName("client_playback_context") val clientPlaybackContext: ClientPlaybackContext,
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
    val metered: Boolean = false,
    @SerialName("bandwidth_estimate_kbps") val bandwidthEstimateKbps: Int? = null,
    @SerialName("bandwidth_cap_kbps") val bandwidthCapKbps: Int? = null,
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
    if (subtitle.artifact != null && selected == null) return false
    if (selected == null) return true
    return subtitle.inventory.any {
        it.trackId == selected.id &&
            (selected.index == null || it.combinedIndex == selected.index)
    }
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
