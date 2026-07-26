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
const val MEDIA3_ONLY_FEATURE = "media3_only"
const val DETAILED_DECODE_CAPABILITIES_FEATURE = "detailed_decode_capabilities"
const val LAYOUT_AWARE_PASSTHROUGH_FEATURE = "layout_aware_passthrough"
const val CLIENT_VIDEO_TRANSFORMATIONS_FEATURE = "client_video_transformations_v1"
const val DEVICE_QUIRKS_V3_FEATURE = "device_quirks_v1"
const val SEEK_REANCHOR_V3_FEATURE = "seek_reanchor_v1"
const val DIRECT_STREAM_RESUME_V1_FEATURE = "direct_stream_resume_v1"
const val SEEK_REANCHOR_V3_OPERATION = "seek_reanchor"
const val SEEK_FAILURE_RECOVERY_V3_OPERATION = "seek_failure_recovery"
const val CLIENT_DV7_TO_DV81 = "client_dv7_to_dv81"
const val CLIENT_DV7_TO_HDR10 = "client_dv7_to_hdr10"
const val CLIENT_DV_TRANSFORM_RECIPE_VERSION = "1"
const val CLIENT_DV8_HDR10_PLUS_SANITIZER = "client_dv8_hdr10plus_sanitizer_v1"
const val CLIENT_POST_RESUME_VIDEO_RECOVERY = "client_post_resume_video_recovery_v1"
const val CLIENT_SURFACE_RECOVERY = "client_surface_recovery_v1"

/** Features the client advertises on `POST /api/v1/playback/start`. */
val PLAYBACK_START_CLIENT_FEATURES_V3 = listOf(
    PLAYBACK_PLAN_V3_FEATURE,
    MEDIA3_ONLY_FEATURE,
    DETAILED_DECODE_CAPABILITIES_FEATURE,
    CLIENT_VIDEO_TRANSFORMATIONS_FEATURE,
    DEVICE_QUIRKS_V3_FEATURE,
    SEEK_REANCHOR_V3_FEATURE,
    DIRECT_STREAM_RESUME_V1_FEATURE,
)

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
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    val delivery: PlaybackDelivery,
    val engine: PlaybackEngineKind,
    val stream: PlaybackStreamV3,
    val timeline: PlaybackTimelineV3 = PlaybackTimelineV3(),
    @SerialName("selected_tracks") val selectedTracks: SelectedPlaybackTracksV3 = SelectedPlaybackTracksV3(),
    @SerialName("effective_recipe") val effectiveRecipe: PlaybackEffectiveRecipeV3 = PlaybackEffectiveRecipeV3(),
    val claims: PlaybackValidationClaims = PlaybackValidationClaims(),
    val subtitle: PlaybackSubtitleDecisionV3 = PlaybackSubtitleDecisionV3(),
    val transformations: List<PlaybackTransformationV3> = emptyList(),
    @SerialName("applied_quirks") val appliedQuirks: List<PlaybackAppliedQuirkV3> = emptyList(),
    @SerialName("runtime_corrections") val runtimeCorrections: List<String> = emptyList(),
    @SerialName("degradation_warnings") val degradationWarnings: List<PlaybackDegradationWarning> = emptyList(),
    @SerialName("decision_reason") val decisionReason: String,
    @SerialName("requested_media_file_id") val requestedMediaFileId: Int? = null,
    @SerialName("effective_media_file_id") val effectiveMediaFileId: Int? = null,
    val source: PlaybackSourceDescriptorV3 = PlaybackSourceDescriptorV3(),
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
)

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
    @SerialName("client_features") val clientFeatures: List<String> = PLAYBACK_START_CLIENT_FEATURES_V3,
    @SerialName("file_id") val fileId: Int,
    @SerialName("profile_id") val profileId: String,
    @SerialName("playback_attempt_id") val playbackAttemptId: String,
    @SerialName("quality_preference") val qualityPreference: String = "auto",
    @SerialName("subtitle_fidelity_preference") val subtitleFidelityPreference: SubtitleFidelityPreference,
    @SerialName("start_position") val startPosition: Double? = null,
    @SerialName("audio_track_id") val audioTrackId: String? = null,
    @SerialName("audio_track_index") val audioTrackIndex: Int? = null,
    @SerialName("subtitle_track_id") val subtitleTrackId: String? = null,
    @SerialName("subtitle_track_index") val subtitleTrackIndex: Int? = null,
    @SerialName("output_route_generation") val outputRouteGeneration: Long,
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
    /**
     * Omitted requests retain the protocol-v3 failure-recovery behavior.
     * Explicit operations are negotiated independently through client/server
     * features so an older server never has to infer new semantics.
     */
    val operation: String? = null,
    @SerialName("playback_attempt_id") val playbackAttemptId: String,
    @SerialName("replan_request_id") val replanRequestId: String,
    @SerialName("failed_plan_id") val failedPlanId: String,
    @SerialName("plan_attempt_id") val planAttemptId: String,
    @SerialName("plan_attempt_key") val planAttemptKey: String,
    @SerialName("attempted_plan_keys") val attemptedPlanKeys: List<String>,
    @SerialName("attempt_count") val attemptCount: Int,
    @SerialName("quality_preference") val qualityPreference: String = "auto",
    @SerialName("position_seconds") val positionSeconds: Double,
    @SerialName("output_route_generation") val outputRouteGeneration: Long,
    val metered: Boolean = false,
    @SerialName("bandwidth_estimate_kbps") val bandwidthEstimateKbps: Int? = null,
    @SerialName("bandwidth_cap_kbps") val bandwidthCapKbps: Int? = null,
    @SerialName("selected_tracks") val selectedTracks: SelectedPlaybackTracksV3,
    val failure: PlaybackFailureV3,
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
    @SerialName("output_route_generation") val outputRouteGeneration: Long,
    val diagnostics: Map<String, String> = emptyMap(),
)

fun PlaybackPlanV3.planAttemptKey(
    outputRouteGeneration: Long,
    localMutations: List<String> = emptyList(),
): String {
    val canonical = buildString {
        append(planId)
        append('|').append(delivery.name)
        append('|').append(stream.protocol.name)
        append('|').append(stream.container.orEmpty().lowercase())
        append('|').append(effectiveRecipe.videoCodec.orEmpty().lowercase())
        append('|').append(effectiveRecipe.audioCodec.orEmpty().lowercase())
        append('|').append(effectiveRecipe.width ?: 0)
        append('x').append(effectiveRecipe.height ?: 0)
        append('|').append(effectiveRecipe.bitrateKbps ?: 0)
        append('|').append(effectiveRecipe.dynamicRange.orEmpty().lowercase())
        append('|').append(subtitle.mode.name)
        append('|').append(transformations.map {
            "${it.executor.name.lowercase()}:${it.name}:${it.recipeVersion}"
        }.sorted().joinToString(",") {
            it
        })
        if (appliedQuirks.isNotEmpty() || runtimeCorrections.isNotEmpty()) {
            append('|').append(appliedQuirks.map {
                "${it.registryRevision}:${it.id}"
            }.sorted().joinToString(","))
            append('|').append(runtimeCorrections.sorted().joinToString(","))
        }
        append('|').append(outputRouteGeneration)
        append('|').append(localMutations.sorted().joinToString(","))
    }
    var hash = 0xcbf29ce484222325uL
    canonical.encodeToByteArray().forEach { byte ->
        hash = hash xor byte.toUByte().toULong()
        hash *= 0x100000001b3uL
    }
    return "v3:${hash.toString(16).padStart(16, '0')}"
}

sealed interface PlaybackV3Validation {
    data class Playable(val plan: PlaybackPlanV3, val sessionId: String) : PlaybackV3Validation
    data class Terminal(val reason: String, val message: String, val retryable: Boolean) : PlaybackV3Validation
    data class Incompatible(val allocatedSessionId: String?) : PlaybackV3Validation
    data class ReplanRequired(val reason: String, val plan: PlaybackPlanV3, val sessionId: String) : PlaybackV3Validation
}

fun PlaybackDecisionResponseV3.validateForMedia3(): PlaybackV3Validation {
    if (protocolVersion != PLAYBACK_PROTOCOL_V3 || PLAYBACK_PLAN_V3_FEATURE !in serverFeatures) {
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
    if (plan.engine == PlaybackEngineKind.MPV_DIRECT ||
        plan.engine == PlaybackEngineKind.CLIENT_LOCAL_LOOPBACK ||
        plan.engine == PlaybackEngineKind.EXTERNAL_PLAYER
    ) {
        return PlaybackV3Validation.ReplanRequired("unsupported_legacy_engine", plan, resolvedSessionId)
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
    if (plan.transformations.any { it.executor == PlaybackTransformationExecutor.CLIENT } &&
        plan.engine != PlaybackEngineKind.MEDIA3_DIRECT
    ) {
        return PlaybackV3Validation.ReplanRequired(
            "client_transformation_requires_media3_direct",
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
