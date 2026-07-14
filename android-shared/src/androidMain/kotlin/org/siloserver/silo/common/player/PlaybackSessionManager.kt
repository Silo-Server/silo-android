package org.siloserver.silo.common.player

import android.os.SystemClock
import android.util.Log
import org.siloserver.silo.model.playback.ClientCodecCapabilities
import org.siloserver.silo.model.playback.ClientPlaybackContext
import org.siloserver.silo.model.playback.PlayMethod
import org.siloserver.silo.model.playback.PlaybackDelivery
import org.siloserver.silo.model.playback.PlaybackEngineKind
import org.siloserver.silo.model.playback.PlaybackRouteFamily
import org.siloserver.silo.model.playback.PlaybackStreamRequest
import org.siloserver.silo.model.playback.PlaybackSessionResponse
import org.siloserver.silo.model.playback.PlaybackTimeline
import org.siloserver.silo.model.playback.TranscodeStartRequest
import org.siloserver.silo.model.playback.TranscodeStartResponse
import org.siloserver.silo.model.playback.PlaybackStartRequestV3
import org.siloserver.silo.model.playback.PlaybackV3Validation
import org.siloserver.silo.model.playback.SubtitleFidelityPreference
import org.siloserver.silo.model.playback.planAttemptKey
import org.siloserver.silo.model.playback.validateForMedia3
import org.siloserver.silo.model.playback.PlaybackFailureV3
import org.siloserver.silo.model.playback.PlaybackPlanV3
import org.siloserver.silo.model.playback.PlaybackReplanRequestV3
import org.siloserver.silo.model.playback.PlaybackRouteEventV3
import org.siloserver.silo.model.playback.SelectedPlaybackTracksV3
import org.siloserver.silo.model.playback.PlaybackTrackIdentityV3
import org.siloserver.silo.model.playback.SEEK_FAILURE_RECOVERY_V3_OPERATION
import org.siloserver.silo.model.playback.SEEK_REANCHOR_V3_FEATURE
import org.siloserver.silo.model.playback.SEEK_REANCHOR_V3_OPERATION
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.repository.PlaybackRepository
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.siloserver.silo.common.player.audio.PassthroughSuppressionRegistry

/**
 * Manages the playback session lifecycle: creation, progress reporting,
 * audio track switching, transcoding, and teardown.
 *
 * Wraps [PlaybackRepository] and adds token/server-URL resolution via [TokenManager].
 */
open class PlaybackSessionManager(
    private val playbackRepository: PlaybackRepository,
    private val tokenManager: TokenManager,
    private val networkEvidenceProvider: PlaybackNetworkEvidenceProvider = PlaybackNetworkEvidenceProvider.None,
) {
    private data class ActiveVideoAttempt(
        val fileId: Int,
        val profileId: String,
        val capabilities: ClientCodecCapabilities,
        val context: ClientPlaybackContext,
        val playbackAttemptId: String,
        val qualityPreference: String,
        val networkEvidence: PlaybackNetworkSnapshot,
        val sessionId: String,
        val plan: PlaybackPlanV3,
        val serverFeatures: Set<String>,
        val planAttemptId: String,
        val planAttemptKey: String,
        val localMutations: List<String>,
        val attemptedPlanKeys: List<String>,
        val attemptCount: Int,
        val startedAtElapsedRealtimeMs: Long,
        val firstFrameReported: Boolean,
    )

    // Suspendable plan operations stay serialized, while synchronous Media3
    // reporter callbacks use CAS below so they never block the playback thread
    // or overwrite a newer plan published by one of those operations.
    private val videoAttemptMutex = Mutex()
    private val telemetryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeVideoAttempt = AtomicReference<ActiveVideoAttempt?>()

    suspend fun startVideoSessionV3(
        fileId: Int,
        profileId: String,
        capabilities: ClientCodecCapabilities,
        clientPlaybackContext: ClientPlaybackContext,
        audioTrackIndex: Int?,
        subtitleTrackIndex: Int?,
        qualityPreference: String?,
        startPosition: Double?,
        subtitleFidelityPreference: SubtitleFidelityPreference = SubtitleFidelityPreference.PRESERVE,
    ): ApiResult<VideoSessionStartV3> {
        val playbackAttemptId = UUID.randomUUID().toString()
        val network = networkEvidenceProvider.snapshot()
        val request = PlaybackStartRequestV3(
            fileId = fileId,
            profileId = profileId,
            playbackAttemptId = playbackAttemptId,
            qualityPreference = qualityPreference?.lowercase() ?: "auto",
            subtitleFidelityPreference = subtitleFidelityPreference,
            startPosition = startPosition,
            audioTrackId = audioTrackIndex?.let { stableTrackId(fileId, "audio", it) },
            audioTrackIndex = audioTrackIndex,
            subtitleTrackId = subtitleTrackIndex?.takeIf { it >= 0 }
                ?.let { stableTrackId(fileId, "subtitle", it) },
            subtitleTrackIndex = subtitleTrackIndex,
            outputRouteGeneration = clientPlaybackContext.output.outputRouteGeneration,
            metered = network.metered,
            bandwidthEstimateKbps = network.bandwidthEstimateKbps,
            capabilities = capabilities,
            clientPlaybackContext = clientPlaybackContext,
        )
        return when (val result = playbackRepository.startPlaybackV3(request)) {
            is ApiResult.Success -> when (val validated = result.data.validateForMedia3()) {
                is PlaybackV3Validation.Playable -> {
                    val planAttemptId = UUID.randomUUID().toString()
                    val active = newActiveAttempt(
                        request = request,
                        network = network,
                        sessionId = validated.sessionId,
                        plan = validated.plan,
                        serverFeatures = result.data.serverFeatures.toSet(),
                        planAttemptId = planAttemptId,
                    )
                    videoAttemptMutex.withLock { activeVideoAttempt.set(active) }
                    PassthroughSuppressionRegistry.beginAttempt(active.planAttemptKey)
                    reportActiveVideoEvent("plan_selected", network.asRouteDiagnostics())
                    ApiResult.Success(
                        VideoSessionStartV3.Ready(
                            session = validated.plan.toSessionResponse(validated.sessionId, profileId, fileId),
                            plan = validated.plan,
                            playbackAttemptId = playbackAttemptId,
                            planAttemptId = planAttemptId,
                            planAttemptKey = active.planAttemptKey,
                        ),
                    )
                }
                is PlaybackV3Validation.Terminal -> {
                    videoAttemptMutex.withLock { activeVideoAttempt.set(null) }
                    emitRouteEvent(
                        PlaybackRouteEventV3(
                            playbackAttemptId = playbackAttemptId,
                            sessionId = result.data.sessionId,
                            event = "terminal",
                            fallbackReason = validated.reason,
                            outputRouteGeneration = request.outputRouteGeneration,
                        ),
                    )
                    (result.data.playbackPlan?.sessionId ?: result.data.sessionId)
                        ?.let { playbackRepository.stopPlayback(it) }
                    ApiResult.Success(
                        VideoSessionStartV3.Terminal(validated.reason, validated.message, validated.retryable),
                    )
                }
                is PlaybackV3Validation.Incompatible -> {
                    videoAttemptMutex.withLock { activeVideoAttempt.set(null) }
                    validated.allocatedSessionId?.let { playbackRepository.stopPlayback(it) }
                    ApiResult.Success(VideoSessionStartV3.ServerUpgradeRequired)
                }
                is PlaybackV3Validation.ReplanRequired -> {
                    // Decode stale engine enums, but never execute them. Preserve
                    // the allocated session and give the v3 planner exactly one
                    // opportunity to replace the route with a Media3 plan.
                    val planAttemptId = UUID.randomUUID().toString()
                    val active = newActiveAttempt(
                        request = request,
                        network = network,
                        sessionId = validated.sessionId,
                        plan = validated.plan,
                        serverFeatures = result.data.serverFeatures.toSet(),
                        planAttemptId = planAttemptId,
                    )
                    videoAttemptMutex.withLock { activeVideoAttempt.set(active) }
                    PassthroughSuppressionRegistry.beginAttempt(active.planAttemptKey)
                    val replanResult = replanActiveVideoSession(
                        classification = validated.reason,
                        message = "The server returned a legacy player route.",
                        positionSeconds = startPosition ?: 0.0,
                        audioTrackIndex = audioTrackIndex,
                        subtitleTrackIndex = subtitleTrackIndex,
                    )
                    if (replanResult is ApiResult.Error || replanResult is ApiResult.NetworkError) {
                        val cleared = videoAttemptMutex.withLock {
                            activeVideoAttempt.compareAndSet(active, null)
                        }
                        if (cleared) playbackRepository.stopPlayback(validated.sessionId)
                    }
                    replanResult
                }
            }
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }

    private fun newActiveAttempt(
        request: PlaybackStartRequestV3,
        network: PlaybackNetworkSnapshot,
        sessionId: String,
        plan: PlaybackPlanV3,
        serverFeatures: Set<String>,
        planAttemptId: String,
    ): ActiveVideoAttempt {
        val planAttemptKey = plan.planAttemptKey(request.outputRouteGeneration)
        return ActiveVideoAttempt(
            fileId = request.fileId,
            profileId = request.profileId,
            capabilities = request.capabilities,
            context = request.clientPlaybackContext,
            playbackAttemptId = request.playbackAttemptId,
            qualityPreference = request.qualityPreference,
            networkEvidence = network,
            sessionId = sessionId,
            plan = plan,
            serverFeatures = serverFeatures,
            planAttemptId = planAttemptId,
            planAttemptKey = planAttemptKey,
            localMutations = emptyList(),
            attemptedPlanKeys = listOf(planAttemptKey),
            attemptCount = 1,
            startedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            firstFrameReported = false,
        )
    }

    suspend fun replanActiveVideoSession(
        classification: String,
        message: String? = null,
        positionSeconds: Double,
        audioTrackIndex: Int?,
        subtitleTrackIndex: Int?,
        decoderName: String? = null,
        diagnostics: Map<String, String> = emptyMap(),
        qualityPreference: String? = null,
        capabilities: ClientCodecCapabilities? = null,
        clientPlaybackContext: ClientPlaybackContext? = null,
    ): ApiResult<VideoSessionStartV3> = videoAttemptMutex.withLock {
        val active = activeVideoAttempt.get() ?: return@withLock ApiResult.Error(
            code = 409,
            error = "playback_attempt_not_active",
            message = "No protocol-v3 playback attempt is active.",
        )
        if (classification == SEEK_REANCHOR_V3_OPERATION ||
            classification == SEEK_FAILURE_RECOVERY_V3_OPERATION
        ) {
            return@withLock ApiResult.Error(
                code = 400,
                error = "reserved_playback_operation",
                message = "Seek operations must use the dedicated playback session methods.",
            )
        }
        val currentCapabilities = capabilities ?: active.capabilities
        val currentContext = clientPlaybackContext ?: active.context
        val network = networkEvidenceProvider.snapshot()
        val failedKey = active.planAttemptKey
        val invalidation = classification in setOf(
            "audio_track_changed",
            "subtitle_track_changed",
            "quality_changed",
            "output_route_changed",
        )
        val attemptedKeys = if (invalidation) {
            emptyList()
        } else {
            (active.attemptedPlanKeys + failedKey).distinct()
        }
        val requestAttemptCount = if (invalidation) 1 else active.attemptCount
        emitRouteEvent(
            PlaybackRouteEventV3(
                playbackAttemptId = active.playbackAttemptId,
                sessionId = active.sessionId,
                planId = active.plan.planId,
                planAttemptId = active.planAttemptId,
                planAttemptKey = failedKey,
                event = if (invalidation) "plan_invalidated" else "plan_failed",
                failureClassification = classification,
                appliedQuirkIds = active.plan.appliedQuirks.map { it.id },
                quirkRegistryRevision = active.plan.appliedQuirks.firstOrNull()?.registryRevision,
                outputRouteGeneration = currentContext.output.outputRouteGeneration,
                diagnostics = diagnostics + mapOfNotNull("decoder_name" to decoderName) +
                    network.asRouteDiagnostics(),
            ),
        )
        val request = PlaybackReplanRequestV3(
            playbackAttemptId = active.playbackAttemptId,
            replanRequestId = UUID.randomUUID().toString(),
            failedPlanId = active.plan.planId,
            planAttemptId = active.planAttemptId,
            planAttemptKey = failedKey,
            attemptedPlanKeys = attemptedKeys,
            attemptCount = requestAttemptCount,
            qualityPreference = qualityPreference?.lowercase() ?: active.qualityPreference,
            positionSeconds = positionSeconds,
            outputRouteGeneration = currentContext.output.outputRouteGeneration,
            metered = network.metered,
            bandwidthEstimateKbps = network.bandwidthEstimateKbps,
            selectedTracks = SelectedPlaybackTracksV3(
                audio = selectedTrackIdentity(active, "audio", audioTrackIndex, active.plan.selectedTracks.audio),
                subtitle = subtitleTrackIndex?.takeIf { it >= 0 }
                    ?.let { selectedTrackIdentity(active, "subtitle", it, active.plan.selectedTracks.subtitle) },
            ),
            failure = PlaybackFailureV3(classification, message, decoderName),
            capabilities = currentCapabilities,
            clientPlaybackContext = currentContext,
        )
        when (val result = playbackRepository.replanPlaybackV3(active.sessionId, request)) {
            is ApiResult.Success -> when (val validated = result.data.validateForMedia3()) {
                is PlaybackV3Validation.Playable -> {
                    val nextKey = validated.plan.planAttemptKey(currentContext.output.outputRouteGeneration)
                    if (nextKey in attemptedKeys) {
                        if (validated.sessionId != active.sessionId) {
                            playbackRepository.stopPlayback(validated.sessionId)
                        }
                        activeVideoAttempt.set(null)
                        return@withLock ApiResult.Success(
                            VideoSessionStartV3.Terminal(
                                "replan_loop_detected",
                                "The server returned a playback plan that already failed on this output route.",
                                false,
                            ),
                        )
                    }
                    val nextAttemptId = UUID.randomUUID().toString()
                    val next = active.copy(
                        sessionId = validated.sessionId,
                        plan = validated.plan,
                        serverFeatures = result.data.serverFeatures.toSet(),
                        planAttemptId = nextAttemptId,
                        planAttemptKey = nextKey,
                        localMutations = emptyList(),
                        attemptedPlanKeys = attemptedKeys + nextKey,
                        attemptCount = if (invalidation) 1 else active.attemptCount + 1,
                        qualityPreference = request.qualityPreference,
                        networkEvidence = network,
                        capabilities = currentCapabilities,
                        context = currentContext,
                        startedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                        firstFrameReported = false,
                    )
                    activeVideoAttempt.set(next)
                    PassthroughSuppressionRegistry.beginAttempt(nextKey)
                    if (validated.sessionId != active.sessionId) playbackRepository.stopPlayback(active.sessionId)
                    emitRouteEvent(
                        PlaybackRouteEventV3(
                            playbackAttemptId = active.playbackAttemptId,
                            sessionId = validated.sessionId,
                            planId = validated.plan.planId,
                            planAttemptId = nextAttemptId,
                            planAttemptKey = nextKey,
                            event = "plan_selected",
                            fallbackReason = classification,
                            appliedQuirkIds = validated.plan.appliedQuirks.map { it.id },
                            quirkRegistryRevision = validated.plan.appliedQuirks.firstOrNull()?.registryRevision,
                            outputRouteGeneration = currentContext.output.outputRouteGeneration,
                        ),
                    )
                    ApiResult.Success(
                        VideoSessionStartV3.Ready(
                            session = validated.plan.toSessionResponse(validated.sessionId, active.profileId, active.fileId),
                            plan = validated.plan,
                            playbackAttemptId = active.playbackAttemptId,
                            planAttemptId = nextAttemptId,
                            planAttemptKey = nextKey,
                        ),
                    )
                }
                is PlaybackV3Validation.Terminal -> {
                    reportActiveVideoEvent(
                        event = "terminal",
                        diagnostics = mapOf("reason" to validated.reason),
                    )
                    activeVideoAttempt.set(null)
                    listOfNotNull(active.sessionId, result.data.sessionId).distinct()
                        .forEach { playbackRepository.stopPlayback(it) }
                    ApiResult.Success(VideoSessionStartV3.Terminal(validated.reason, validated.message, validated.retryable))
                }
                is PlaybackV3Validation.Incompatible -> {
                    activeVideoAttempt.set(null)
                    validated.allocatedSessionId?.let { playbackRepository.stopPlayback(it) }
                    ApiResult.Success(VideoSessionStartV3.ServerUpgradeRequired)
                }
                is PlaybackV3Validation.ReplanRequired -> {
                    activeVideoAttempt.set(null)
                    listOf(active.sessionId, validated.sessionId).distinct()
                        .forEach { playbackRepository.stopPlayback(it) }
                    ApiResult.Success(
                        VideoSessionStartV3.Terminal(
                            "unsupported_legacy_engine",
                            "The server could not provide a Media3 playback route.",
                            false,
                        ),
                    )
                }
            }
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }

    /** Reopens the active V3 transport at a new source-time origin. */
    suspend fun reanchorActiveVideoSession(
        positionSeconds: Double,
        diagnostics: Map<String, String> = emptyMap(),
    ): ApiResult<VideoSessionStartV3> = videoAttemptMutex.withLock {
        val active = activeVideoAttempt.get() ?: return@withLock ApiResult.Error(
            code = 409,
            error = "playback_attempt_not_active",
            message = "No protocol-v3 playback attempt is active.",
        )
        if (SEEK_REANCHOR_V3_FEATURE !in active.serverFeatures) {
            return@withLock ApiResult.Error(
                code = 409,
                error = "seek_reanchor_not_supported",
                message = "The active playback server did not negotiate seek re-anchoring.",
            )
        }
        if (!positionSeconds.isFinite() || positionSeconds < 0.0) {
            return@withLock ApiResult.Error(
                code = 400,
                error = "invalid_seek_position",
                message = "Seek position must be a finite, non-negative source timestamp.",
            )
        }

        val network = networkEvidenceProvider.snapshot()
        val request = PlaybackReplanRequestV3(
            operation = SEEK_REANCHOR_V3_OPERATION,
            playbackAttemptId = active.playbackAttemptId,
            replanRequestId = UUID.randomUUID().toString(),
            failedPlanId = active.plan.planId,
            planAttemptId = active.planAttemptId,
            planAttemptKey = active.planAttemptKey,
            attemptedPlanKeys = active.attemptedPlanKeys,
            attemptCount = active.attemptCount,
            qualityPreference = active.qualityPreference,
            positionSeconds = positionSeconds,
            outputRouteGeneration = active.context.output.outputRouteGeneration,
            metered = active.networkEvidence.metered,
            bandwidthEstimateKbps = active.networkEvidence.bandwidthEstimateKbps,
            selectedTracks = active.plan.selectedTracks,
            failure = PlaybackFailureV3(
                classification = SEEK_REANCHOR_V3_OPERATION,
                message = "Reanchor the active stream at the requested source position.",
            ),
            capabilities = active.capabilities,
            clientPlaybackContext = active.context,
        )
        emitRouteEvent(
            PlaybackRouteEventV3(
                playbackAttemptId = active.playbackAttemptId,
                sessionId = active.sessionId,
                planId = active.plan.planId,
                planAttemptId = active.planAttemptId,
                planAttemptKey = active.planAttemptKey,
                event = "seek_reanchor_requested",
                appliedQuirkIds = active.plan.appliedQuirks.map { it.id },
                quirkRegistryRevision = active.plan.appliedQuirks.firstOrNull()?.registryRevision,
                outputRouteGeneration = active.context.output.outputRouteGeneration,
                diagnostics = diagnostics + network.asRouteDiagnostics() +
                    ("target_source_position_seconds" to positionSeconds.toString()),
            ),
        )

        when (val result = playbackRepository.replanPlaybackV3(active.sessionId, request)) {
            is ApiResult.Success -> {
                if (SEEK_REANCHOR_V3_FEATURE !in result.data.serverFeatures) {
                    return@withLock invalidSeekReanchorResponse(
                        "The server omitted the negotiated seek re-anchor feature from its response.",
                    )
                }
                when (val validated = result.data.validateForMedia3()) {
                    is PlaybackV3Validation.Playable -> {
                        val mismatch = seekReanchorMismatch(
                            active = active,
                            responseSessionId = result.data.sessionId,
                            resolvedSessionId = validated.sessionId,
                            candidate = validated.plan,
                        )
                        if (mismatch != null) {
                            return@withLock invalidSeekReanchorResponse(mismatch)
                        }
                        // Synchronous local recovery mutations do not acquire the
                        // suspend operation mutex. Re-read the active record at
                        // commit time so any such mutation is retained rather
                        // than overwritten by the pre-request snapshot.
                        val next = adoptSeekReanchoredPlan(
                            expected = active,
                            plan = validated.plan,
                            serverFeatures = result.data.serverFeatures,
                        )
                        if (next == null) {
                            return@withLock ApiResult.Error(
                                code = 409,
                                error = "playback_attempt_changed",
                                message = "The active playback attempt changed while seek re-anchoring.",
                            )
                        }
                        emitRouteEvent(
                            PlaybackRouteEventV3(
                                playbackAttemptId = next.playbackAttemptId,
                                sessionId = next.sessionId,
                                planId = next.plan.planId,
                                planAttemptId = next.planAttemptId,
                                planAttemptKey = next.planAttemptKey,
                                event = "seek_reanchored",
                                appliedQuirkIds = next.plan.appliedQuirks.map { it.id },
                                quirkRegistryRevision = next.plan.appliedQuirks.firstOrNull()?.registryRevision,
                                outputRouteGeneration = next.context.output.outputRouteGeneration,
                                diagnostics = diagnostics +
                                    ("target_source_position_seconds" to positionSeconds.toString()),
                            ),
                        )
                        val effectivePlan = next.plan.applyLocalPlaybackMutations(next.localMutations)
                        ApiResult.Success(
                            VideoSessionStartV3.Ready(
                                session = effectivePlan.toSessionResponse(next.sessionId, next.profileId, next.fileId),
                                plan = effectivePlan,
                                playbackAttemptId = next.playbackAttemptId,
                                planAttemptId = next.planAttemptId,
                                planAttemptKey = next.planAttemptKey,
                            ),
                        )
                    }
                    is PlaybackV3Validation.Terminal -> invalidSeekReanchorResponse(
                        "The server rejected seek re-anchoring: ${validated.reason}.",
                    )
                    is PlaybackV3Validation.Incompatible -> invalidSeekReanchorResponse(
                        "The server returned an incompatible seek re-anchor response.",
                    )
                    is PlaybackV3Validation.ReplanRequired -> invalidSeekReanchorResponse(
                        "The server changed the player engine during seek re-anchoring.",
                    )
                }
            }
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }

    private fun seekReanchorMismatch(
        active: ActiveVideoAttempt,
        responseSessionId: String?,
        resolvedSessionId: String,
        candidate: PlaybackPlanV3,
    ): String? {
        if (resolvedSessionId != active.sessionId ||
            responseSessionId?.let { it != active.sessionId } == true ||
            candidate.sessionId?.let { it != active.sessionId } == true
        ) {
            return "The server changed the playback session during seek re-anchoring."
        }
        if (candidate.planId != active.plan.planId) {
            return "The server changed the playback plan during seek re-anchoring."
        }
        val requestedFileId = active.plan.requestedMediaFileId ?: active.fileId
        val effectiveFileId = active.plan.effectiveMediaFileId ?: requestedFileId
        val candidateRequestedFileId = candidate.requestedMediaFileId ?: requestedFileId
        val candidateEffectiveFileId = candidate.effectiveMediaFileId ?: candidateRequestedFileId
        if (candidateRequestedFileId != requestedFileId ||
            candidateEffectiveFileId != effectiveFileId
        ) {
            return "The server changed the media file during seek re-anchoring."
        }
        if (candidate.selectedTracks != active.plan.selectedTracks) {
            return "The server changed the selected tracks during seek re-anchoring."
        }
        if (!candidate.hasSameSeekReanchorBaseRoute(active.plan, active.context.output.outputRouteGeneration)) {
            return "The server changed the playback route during seek re-anchoring."
        }
        return null
    }

    private fun PlaybackPlanV3.hasSameSeekReanchorBaseRoute(
        current: PlaybackPlanV3,
        outputRouteGeneration: Long,
    ): Boolean =
        planAttemptKey(outputRouteGeneration) == current.planAttemptKey(outputRouteGeneration) &&
            engine == current.engine &&
            stream.mimeType == current.stream.mimeType &&
            stream.headerRefresh == current.stream.headerRefresh &&
            effectiveRecipe == current.effectiveRecipe &&
            claims == current.claims &&
            subtitle.mode == current.subtitle.mode &&
            subtitle.trackId == current.subtitle.trackId &&
            subtitle.artifact?.mimeType == current.subtitle.artifact?.mimeType &&
            subtitle.artifact?.format == current.subtitle.artifact?.format &&
            transformations.toSet() == current.transformations.toSet() &&
            appliedQuirks.toSet() == current.appliedQuirks.toSet() &&
            runtimeCorrections.toSet() == current.runtimeCorrections.toSet()

    private fun invalidSeekReanchorResponse(message: String): ApiResult.Error = ApiResult.Error(
        code = 502,
        error = "invalid_seek_reanchor_response",
        message = message,
    )

    /** Reapply device-local fixes that are intentionally invisible to the server recipe. */
    private fun PlaybackPlanV3.applyLocalPlaybackMutations(
        localMutations: List<String>,
    ): PlaybackPlanV3 {
        if (localMutations.none { it.startsWith("pcm:") }) return this
        return copy(
            claims = claims.copy(
                audio = claims.audio.copy(
                    passthrough = false,
                    reason = "client_pcm_retry",
                ),
            ),
        )
    }

    private fun adoptSeekReanchoredPlan(
        expected: ActiveVideoAttempt,
        plan: PlaybackPlanV3,
        serverFeatures: List<String>,
    ): ActiveVideoAttempt? {
        val current = activeVideoAttempt.get() ?: return null
        if (current.playbackAttemptId != expected.playbackAttemptId ||
            current.sessionId != expected.sessionId ||
            current.plan.planId != expected.plan.planId
        ) {
            return null
        }
        val next = current.copy(
            plan = plan,
            serverFeatures = serverFeatures.toSet(),
            startedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            firstFrameReported = false,
        )
        return next.takeIf { activeVideoAttempt.compareAndSet(current, next) }
    }

    /**
     * Falls back to another route after a seek-scoped playback failure without
     * allowing the planner to change editions, quality intent, or tracks.
     */
    suspend fun recoverActiveVideoSessionAfterSeek(
        positionSeconds: Double,
        classification: String,
        message: String? = null,
        decoderName: String? = null,
        diagnostics: Map<String, String> = emptyMap(),
    ): ApiResult<VideoSessionStartV3> = videoAttemptMutex.withLock {
        val active = activeVideoAttempt.get() ?: return@withLock ApiResult.Error(
            code = 409,
            error = "playback_attempt_not_active",
            message = "No protocol-v3 playback attempt is active.",
        )
        if (SEEK_REANCHOR_V3_FEATURE !in active.serverFeatures) {
            return@withLock ApiResult.Error(
                code = 409,
                error = "seek_reanchor_not_supported",
                message = "The active playback server did not negotiate seek recovery.",
            )
        }
        if (!positionSeconds.isFinite() || positionSeconds < 0.0) {
            return@withLock ApiResult.Error(
                code = 400,
                error = "invalid_seek_position",
                message = "Seek position must be a finite, non-negative source timestamp.",
            )
        }
        if (classification.isBlank()) {
            return@withLock ApiResult.Error(
                code = 400,
                error = "invalid_failure_classification",
                message = "Seek recovery requires a failure classification.",
            )
        }

        val network = networkEvidenceProvider.snapshot()
        val attemptedKeys = (active.attemptedPlanKeys + active.planAttemptKey).distinct()
        val request = PlaybackReplanRequestV3(
            operation = SEEK_FAILURE_RECOVERY_V3_OPERATION,
            playbackAttemptId = active.playbackAttemptId,
            replanRequestId = UUID.randomUUID().toString(),
            failedPlanId = active.plan.planId,
            planAttemptId = active.planAttemptId,
            planAttemptKey = active.planAttemptKey,
            attemptedPlanKeys = attemptedKeys,
            attemptCount = active.attemptCount,
            qualityPreference = active.qualityPreference,
            positionSeconds = positionSeconds,
            outputRouteGeneration = active.context.output.outputRouteGeneration,
            metered = active.networkEvidence.metered,
            bandwidthEstimateKbps = active.networkEvidence.bandwidthEstimateKbps,
            selectedTracks = active.plan.selectedTracks,
            failure = PlaybackFailureV3(
                classification = classification,
                message = message,
                decoderName = decoderName,
            ),
            capabilities = active.capabilities,
            clientPlaybackContext = active.context,
        )
        emitRouteEvent(
            PlaybackRouteEventV3(
                playbackAttemptId = active.playbackAttemptId,
                sessionId = active.sessionId,
                planId = active.plan.planId,
                planAttemptId = active.planAttemptId,
                planAttemptKey = active.planAttemptKey,
                event = "plan_failed",
                failureClassification = classification,
                appliedQuirkIds = active.plan.appliedQuirks.map { it.id },
                quirkRegistryRevision = active.plan.appliedQuirks.firstOrNull()?.registryRevision,
                outputRouteGeneration = active.context.output.outputRouteGeneration,
                diagnostics = diagnostics + mapOfNotNull("decoder_name" to decoderName) +
                    network.asRouteDiagnostics() +
                    ("seek_recovery_position_seconds" to positionSeconds.toString()),
            ),
        )

        when (val result = playbackRepository.replanPlaybackV3(active.sessionId, request)) {
            is ApiResult.Success -> {
                if (SEEK_REANCHOR_V3_FEATURE !in result.data.serverFeatures) {
                    return@withLock invalidSeekRecoveryResponse(
                        "The server omitted the negotiated seek recovery feature from its response.",
                    )
                }
                when (val validated = result.data.validateForMedia3()) {
                    is PlaybackV3Validation.Playable -> {
                        val mismatch = seekRecoveryIdentityMismatch(
                            active = active,
                            responseSessionId = result.data.sessionId,
                            resolvedSessionId = validated.sessionId,
                            candidate = validated.plan,
                        )
                        if (mismatch != null) {
                            return@withLock invalidSeekRecoveryResponse(mismatch)
                        }
                        val nextKey = validated.plan.planAttemptKey(
                            active.context.output.outputRouteGeneration,
                        )
                        if (nextKey in attemptedKeys) {
                            return@withLock ApiResult.Success(
                                VideoSessionStartV3.Terminal(
                                    reason = "replan_loop_detected",
                                    message = "The server returned a seek-recovery route that already failed.",
                                    retryable = false,
                                ),
                            )
                        }
                        val nextAttemptId = UUID.randomUUID().toString()
                        val next = adoptSeekRecoveryPlan(
                            expected = active,
                            plan = validated.plan,
                            serverFeatures = result.data.serverFeatures,
                            planAttemptId = nextAttemptId,
                            planAttemptKey = nextKey,
                            attemptedPlanKeys = attemptedKeys,
                        )
                        if (next == null) {
                            return@withLock ApiResult.Error(
                                code = 409,
                                error = "playback_attempt_changed",
                                message = "The active playback attempt changed during seek recovery.",
                            )
                        }
                        PassthroughSuppressionRegistry.beginAttempt(nextKey)
                        emitRouteEvent(
                            PlaybackRouteEventV3(
                                playbackAttemptId = next.playbackAttemptId,
                                sessionId = next.sessionId,
                                planId = next.plan.planId,
                                planAttemptId = next.planAttemptId,
                                planAttemptKey = next.planAttemptKey,
                                event = "plan_selected",
                                fallbackReason = classification,
                                appliedQuirkIds = next.plan.appliedQuirks.map { it.id },
                                quirkRegistryRevision = next.plan.appliedQuirks.firstOrNull()?.registryRevision,
                                outputRouteGeneration = next.context.output.outputRouteGeneration,
                            ),
                        )
                        ApiResult.Success(
                            VideoSessionStartV3.Ready(
                                session = next.plan.toSessionResponse(next.sessionId, next.profileId, next.fileId),
                                plan = next.plan,
                                playbackAttemptId = next.playbackAttemptId,
                                planAttemptId = next.planAttemptId,
                                planAttemptKey = next.planAttemptKey,
                            ),
                        )
                    }
                    is PlaybackV3Validation.Terminal -> ApiResult.Success(
                        VideoSessionStartV3.Terminal(
                            validated.reason,
                            validated.message,
                            validated.retryable,
                        ),
                    )
                    is PlaybackV3Validation.Incompatible -> invalidSeekRecoveryResponse(
                        "The server returned an incompatible seek recovery response.",
                    )
                    is PlaybackV3Validation.ReplanRequired -> invalidSeekRecoveryResponse(
                        "The server returned an unsupported player engine during seek recovery.",
                    )
                }
            }
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }

    private fun seekRecoveryIdentityMismatch(
        active: ActiveVideoAttempt,
        responseSessionId: String?,
        resolvedSessionId: String,
        candidate: PlaybackPlanV3,
    ): String? {
        if (resolvedSessionId != active.sessionId ||
            responseSessionId?.let { it != active.sessionId } == true ||
            candidate.sessionId?.let { it != active.sessionId } == true
        ) {
            return "The server changed the playback session during seek recovery."
        }
        val requestedFileId = active.plan.requestedMediaFileId ?: active.fileId
        val effectiveFileId = active.plan.effectiveMediaFileId ?: requestedFileId
        val candidateRequestedFileId = candidate.requestedMediaFileId ?: requestedFileId
        val candidateEffectiveFileId = candidate.effectiveMediaFileId ?: candidateRequestedFileId
        if (candidateRequestedFileId != requestedFileId ||
            candidateEffectiveFileId != effectiveFileId
        ) {
            return "The server changed the media file during seek recovery."
        }
        if (candidate.selectedTracks != active.plan.selectedTracks) {
            return "The server changed the selected tracks during seek recovery."
        }
        return null
    }

    private fun invalidSeekRecoveryResponse(message: String): ApiResult.Error = ApiResult.Error(
        code = 502,
        error = "invalid_seek_recovery_response",
        message = message,
    )

    private fun adoptSeekRecoveryPlan(
        expected: ActiveVideoAttempt,
        plan: PlaybackPlanV3,
        serverFeatures: List<String>,
        planAttemptId: String,
        planAttemptKey: String,
        attemptedPlanKeys: List<String>,
    ): ActiveVideoAttempt? {
        val current = activeVideoAttempt.get() ?: return null
        if (current.playbackAttemptId != expected.playbackAttemptId ||
            current.sessionId != expected.sessionId ||
            current.plan.planId != expected.plan.planId
        ) {
            return null
        }
        val next = current.copy(
            plan = plan,
            serverFeatures = serverFeatures.toSet(),
            planAttemptId = planAttemptId,
            planAttemptKey = planAttemptKey,
            localMutations = emptyList(),
            attemptedPlanKeys = (current.attemptedPlanKeys + attemptedPlanKeys + planAttemptKey).distinct(),
            attemptCount = current.attemptCount + 1,
            startedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            firstFrameReported = false,
        )
        return next.takeIf { activeVideoAttempt.compareAndSet(current, next) }
    }

    private fun mapOfNotNull(vararg values: Pair<String, String?>): Map<String, String> =
        values.mapNotNull { (key, value) -> value?.let { key to it } }.toMap()

    private fun PlaybackNetworkSnapshot.asRouteDiagnostics(): Map<String, String> = buildMap {
        put("network_transport", transport)
        put("network_metered", metered.toString())
        put("network_validated", validated.toString())
        bandwidthEstimateKbps?.let { put("bandwidth_estimate_kbps", it.toString()) }
        linkDownstreamKbps?.let { put("link_downstream_kbps", it.toString()) }
    }

    private fun emitRouteEvent(event: PlaybackRouteEventV3) {
        telemetryScope.launch {
            playbackRepository.reportRouteEventV3(event)
        }
    }

    fun reportActiveVideoEvent(
        event: String,
        diagnostics: Map<String, String> = emptyMap(),
    ) {
        val active = activeVideoAttempt.get() ?: return
        emitActiveVideoEvent(active, event, diagnostics)
    }

    private fun emitActiveVideoEvent(
        active: ActiveVideoAttempt,
        event: String,
        diagnostics: Map<String, String> = emptyMap(),
    ) {
        emitRouteEvent(
            PlaybackRouteEventV3(
                playbackAttemptId = active.playbackAttemptId,
                sessionId = active.sessionId,
                planId = active.plan.planId,
                planAttemptId = active.planAttemptId,
                planAttemptKey = active.planAttemptKey,
                event = event,
                appliedQuirkIds = active.plan.appliedQuirks.map { it.id },
                quirkRegistryRevision = active.plan.appliedQuirks.firstOrNull()?.registryRevision,
                outputRouteGeneration = active.context.output.outputRouteGeneration,
                diagnostics = diagnostics,
            ),
        )
    }

    fun reportFirstVideoFrame(stats: PlayerStatsSnapshot) {
        val active = activeVideoAttempt.get() ?: return
        if (active.firstFrameReported) return
        val reported = active.copy(firstFrameReported = true)
        if (!activeVideoAttempt.compareAndSet(active, reported)) return
        val firstFrameMs = SystemClock.elapsedRealtime() - active.startedAtElapsedRealtimeMs
        emitRouteEvent(
            PlaybackRouteEventV3(
                playbackAttemptId = active.playbackAttemptId,
                sessionId = active.sessionId,
                planId = active.plan.planId,
                planAttemptId = active.planAttemptId,
                planAttemptKey = active.planAttemptKey,
                event = "first_frame",
                appliedQuirkIds = active.plan.appliedQuirks.map { it.id },
                quirkRegistryRevision = active.plan.appliedQuirks.firstOrNull()?.registryRevision,
                outputRouteGeneration = active.context.output.outputRouteGeneration,
                diagnostics = stats.firstFrameDiagnostics(firstFrameMs),
            ),
        )
    }

    private fun stableTrackId(fileId: Int, kind: String, index: Int): String =
        "file:$fileId:$kind:$index"

    private fun selectedTrackIdentity(
        active: ActiveVideoAttempt,
        kind: String,
        index: Int?,
        selected: PlaybackTrackIdentityV3?,
    ): PlaybackTrackIdentityV3? {
        if (index == null) return null
        val effectiveFileId = active.plan.effectiveMediaFileId ?: active.fileId
        return selected?.takeIf { it.index == index }
            ?: PlaybackTrackIdentityV3(stableTrackId(effectiveFileId, kind, index), index)
    }

    fun trySingleLocalPcmRetry(mime: String, channels: Int): Boolean {
        val active = activeVideoAttempt.get() ?: return false
        val mutation = "pcm:${mime.lowercase()}:${channels.coerceAtLeast(0)}"
        if (active.localMutations.any { it.startsWith("pcm:") }) return false
        val mutations = active.localMutations + mutation
        val key = active.plan.planAttemptKey(active.context.output.outputRouteGeneration, mutations)
        val next = active.copy(
            planAttemptKey = key,
            localMutations = mutations,
            attemptedPlanKeys = (active.attemptedPlanKeys + key).distinct(),
        )
        if (!activeVideoAttempt.compareAndSet(active, next)) return false
        PassthroughSuppressionRegistry.beginAttempt(key)
        return PassthroughSuppressionRegistry.suppressForSinglePcmRetry(mime, channels)
    }

    fun recordTransportReopen(): Boolean {
        val active = activeVideoAttempt.get() ?: return false
        val mutation = "transport_reopen"
        if (mutation in active.localMutations) return false
        val mutations = active.localMutations + mutation
        val key = active.plan.planAttemptKey(active.context.output.outputRouteGeneration, mutations)
        val next = active.copy(
            planAttemptKey = key,
            localMutations = mutations,
            attemptedPlanKeys = (active.attemptedPlanKeys + key).distinct(),
        )
        if (!activeVideoAttempt.compareAndSet(active, next)) return false
        PassthroughSuppressionRegistry.beginAttempt(key)
        return true
    }

    /**
     * Starts a new playback session for the given file.
     * The server decides the play method (direct, remux, transcode).
     */
    open suspend fun startSession(
        fileId: Int,
        profileId: String,
        capabilities: ClientCodecCapabilities,
        audioTrackIndex: Int? = null,
        qualityPreference: String? = null,
        startPosition: Double? = null,
        disableProgressPersistence: Boolean = false,
    ): ApiResult<PlaybackSessionResponse> = startSessionInternal(
        fileId = fileId,
        profileId = profileId,
        capabilities = capabilities,
        audioTrackIndex = audioTrackIndex,
        subtitleTrackIndex = null,
        qualityPreference = qualityPreference,
        startPosition = startPosition,
        clientPlaybackContext = null,
        preserveDirectAudioSelection = false,
        playMethod = null,
        disableProgressPersistence = disableProgressPersistence,
    )

    suspend fun startSessionV2(
        fileId: Int,
        profileId: String,
        capabilities: ClientCodecCapabilities,
        audioTrackIndex: Int? = null,
        subtitleTrackIndex: Int? = null,
        qualityPreference: String? = null,
        startPosition: Double? = null,
        clientPlaybackContext: ClientPlaybackContext? = null,
        preserveDirectAudioSelection: Boolean = false,
        playMethod: PlayMethod? = null,
    ): ApiResult<PlaybackSessionResponse> = startSessionInternal(
        fileId = fileId,
        profileId = profileId,
        capabilities = capabilities,
        audioTrackIndex = audioTrackIndex,
        subtitleTrackIndex = subtitleTrackIndex,
        qualityPreference = qualityPreference,
        startPosition = startPosition,
        clientPlaybackContext = clientPlaybackContext,
        preserveDirectAudioSelection = preserveDirectAudioSelection,
        playMethod = playMethod,
        disableProgressPersistence = false,
    )

    private suspend fun startSessionInternal(
        fileId: Int,
        profileId: String,
        capabilities: ClientCodecCapabilities,
        audioTrackIndex: Int?,
        subtitleTrackIndex: Int?,
        qualityPreference: String?,
        startPosition: Double?,
        clientPlaybackContext: ClientPlaybackContext?,
        preserveDirectAudioSelection: Boolean,
        playMethod: PlayMethod?,
        disableProgressPersistence: Boolean,
    ): ApiResult<PlaybackSessionResponse> {
        Log.i(
            TAG,
            "startSession fileId=$fileId profileId=$profileId " +
                "video=${capabilities.codecsVideo} audio=${capabilities.codecsAudio} " +
                "containers=${capabilities.containers} max=${capabilities.maxResolution} " +
                "hdr=${capabilities.hdr} hdrDetails=${capabilities.hdrDetails} " +
                "passthrough=${capabilities.audioPassthrough} " +
                "qualityPreference=$qualityPreference " +
                "preserveDirectAudioSelection=$preserveDirectAudioSelection " +
                "requestedPlayMethod=$playMethod",
        )
        val result = playbackRepository.startPlayback(
            fileId = fileId,
            profileId = profileId,
            audioTrackIndex = audioTrackIndex,
            subtitleTrackIndex = subtitleTrackIndex,
            qualityPreference = qualityPreference,
            startPosition = startPosition,
            capabilities = capabilities,
            clientPlaybackContext = clientPlaybackContext,
            preserveDirectAudioSelection = preserveDirectAudioSelection,
            playMethod = playMethod,
            disableProgressPersistence = disableProgressPersistence,
        )
        when (result) {
            is ApiResult.Success -> Log.i(
                TAG,
                "startSession -> playMethod=${result.data.playMethod} " +
                    "playbackInfo=${result.data.playbackInfo} " +
                    "plan=${result.data.playbackPlan?.planId}:${result.data.playbackPlan?.engine}",
            )
            is ApiResult.Error -> Log.w(TAG, "startSession error: ${result.code} ${result.message}")
            is ApiResult.NetworkError -> Log.w(TAG, "startSession network error: ${result.exception}")
        }
        return result
    }

    companion object {
        private const val TAG = "PlaybackSessionMgr"
    }

    /**
     * Reports the current playback position to the server.
     * Called periodically (every ~10 seconds) during active playback.
     */
    open suspend fun reportProgress(
        sessionId: String,
        position: Double,
        isPaused: Boolean,
    ): ApiResult<Unit> =
        playbackRepository.updateProgress(sessionId, position, isPaused)

    /**
     * Stops an active playback session.
     * Must be called when exiting the player or when playback completes.
     */
    open suspend fun stopSession(sessionId: String): ApiResult<Unit> = videoAttemptMutex.withLock {
        while (true) {
            val active = activeVideoAttempt.get()
            if (active?.sessionId != sessionId) break
            if (activeVideoAttempt.compareAndSet(active, null)) {
                emitActiveVideoEvent(active, "stopped")
                break
            }
        }
        playbackRepository.stopPlayback(sessionId)
    }

    /**
     * Requests transcoding with specific parameters.
     * Used when switching quality mid-playback or when the server chose transcode
     * and the encoding needs to be started explicitly.
     */
    suspend fun startTranscode(request: TranscodeStartRequest): ApiResult<TranscodeStartResponse> =
        playbackRepository.startTranscode(request)

    /** Returns the current access token for stream authentication. */
    suspend fun getAccessToken(): String? = tokenManager.getAccessToken()

    /** Returns the server base URL for resolving relative stream URLs. */
    suspend fun getServerUrl(): String = tokenManager.getServerUrl()

    enum class TranscodeMode { REMUX, FULL }

    /**
     * Issue a `TranscodeStartRequest` for a fallback path — either because the
     * server chose REMUX / TRANSCODE up front (`handleSessionStarted`) or
     * because client-side preflight determined direct play was impossible
     * ([PlaybackPreflightListener] in PR 8). Folds the resulting HLS URL back
     * into a [PlaybackSessionResponse] so both VMs can treat the result like
     * any other session start.
     *
     * Does **not** stop the caller's current session — ViewModels handle that
     * alongside their state cleanup, which is the point they also tear down
     * progress reporting.
     */
    suspend fun startTranscodeFallback(
        session: PlaybackSessionResponse,
        seekSeconds: Double,
        resolution: String,
        mode: TranscodeMode,
        audioTrackIndex: Int? = null,
        subtitleTrackIndex: Int? = null,
    ): ApiResult<PlaybackSessionResponse> {
        val isRemux = mode == TranscodeMode.REMUX
        val request = TranscodeStartRequest(
            sessionId = session.sessionId,
            seekSeconds = seekSeconds,
            targetResolution = if (isRemux) "" else resolution,
            targetCodecVideo = if (isRemux) "copy" else "h264",
            // REMUX copies audio to preserve passthrough codecs
            // (EAC3/TrueHD/DTS). Forcing AAC clobbers the play-method
            // decision.
            targetCodecAudio = if (isRemux) "copy" else "aac",
            targetBitrateKbps = if (isRemux) 0 else 8000,
            segmentDuration = 2,
            audioTrackIndex = audioTrackIndex,
            subtitleTrackIndex = subtitleTrackIndex,
            subtitleBurnIn = shouldBurnStyledSubtitle(
                isRemux = isRemux,
                subtitleTrackIndex = subtitleTrackIndex,
                subtitleCodec = session.playbackPlan?.source?.subtitleCodec,
            ),
        )
        Log.i(
            TAG,
            "startTranscodeFallback session=${session.sessionId} mode=$mode seekSeconds=$seekSeconds " +
                "targetResolution=${request.targetResolution} " +
                "targetCodecVideo=${request.targetCodecVideo} " +
                "targetCodecAudio=${request.targetCodecAudio} " +
                "targetBitrateKbps=${request.targetBitrateKbps} " +
                "audioTrackIndex=$audioTrackIndex subtitleTrackIndex=$subtitleTrackIndex",
        )
        return when (val r = playbackRepository.startTranscode(request)) {
            is ApiResult.Success -> {
                val tc = r.data
                ApiResult.Success(
                    session.copy(
                        sessionId = tc.sessionId,
                        playMethod = if (isRemux) {
                            org.siloserver.silo.model.playback.PlayMethod.REMUX
                        } else {
                            org.siloserver.silo.model.playback.PlayMethod.TRANSCODE
                        },
                        streamUrl = tc.manifestUrl,
                        durationSeconds = tc.durationSeconds ?: session.durationSeconds,
                        position = tc.playerStartSeconds,
                        playbackPlan = session.playbackPlan?.let { plan ->
                            plan.copy(
                                delivery = if (isRemux) {
                                    PlaybackDelivery.SERVER_REMUX_HLS
                                } else {
                                    PlaybackDelivery.SERVER_TRANSCODE_HLS
                                },
                                engine = PlaybackEngineKind.MEDIA3_HLS,
                                routeFamily = PlaybackRouteFamily.SERVER_ADAPTIVE,
                                stream = PlaybackStreamRequest(
                                    url = tc.manifestUrl,
                                    streamType = "hls",
                                    playMethod = if (isRemux) {
                                        org.siloserver.silo.model.playback.PlayMethod.REMUX
                                    } else {
                                        org.siloserver.silo.model.playback.PlayMethod.TRANSCODE
                                    },
                                ),
                                timeline = PlaybackTimeline(
                                    playerStartSeconds = tc.playerStartSeconds,
                                    streamOriginSeconds = tc.streamOriginSeconds,
                                    timelineOffsetSeconds = tc.timelineOffsetSeconds,
                                    canSeekAnywhere = tc.canSeekAnywhere,
                                ),
                                degradationWarnings = plan.degradationWarnings +
                                    org.siloserver.silo.model.playback.PlaybackDegradationWarning(
                                        code = if (isRemux) {
                                            "server_remux_fallback"
                                        } else {
                                            "server_transcode_fallback"
                                        },
                                        message = if (isRemux) {
                                            "Playback fell back to server remux."
                                        } else {
                                            "Playback fell back to server transcode."
                                        },
                                    ),
                            )
                        },
                    ),
                )
            }
            is ApiResult.Error -> r
            is ApiResult.NetworkError -> r
        }
    }

    suspend fun startTranscodeFallbackRecoveringMissingSession(
        session: PlaybackSessionResponse,
        seekSeconds: Double,
        resolution: String,
        mode: TranscodeMode,
        audioTrackIndex: Int? = null,
        subtitleTrackIndex: Int? = null,
        renewSession: suspend () -> ApiResult<PlaybackSessionResponse>,
    ): ApiResult<PlaybackSessionResponse> {
        val first = startTranscodeFallback(
            session = session,
            seekSeconds = seekSeconds,
            resolution = resolution,
            mode = mode,
            audioTrackIndex = audioTrackIndex,
            subtitleTrackIndex = subtitleTrackIndex,
        )
        if (!first.isPlaybackSessionMissingError()) return first

        Log.w(TAG, "Fallback session missing; renewing playback session before retry")
        return when (val renewed = renewSession()) {
            is ApiResult.Success -> {
                val retry = startTranscodeFallback(
                    session = renewed.data,
                    seekSeconds = seekSeconds,
                    resolution = resolution,
                    mode = mode,
                    audioTrackIndex = audioTrackIndex,
                    subtitleTrackIndex = subtitleTrackIndex,
                )
                if (retry !is ApiResult.Success) {
                    stopSession(renewed.data.sessionId)
                }
                retry
            }
            is ApiResult.Error -> renewed
            is ApiResult.NetworkError -> renewed
        }
    }
}

internal fun ApiResult<*>.isPlaybackSessionMissingError(): Boolean {
    val error = this as? ApiResult.Error ?: return false
    return error.code == 404 &&
        (error.error == "playback_session_not_found" || error.message == "Playback session not found")
}

/**
 * A styled subtitle selected for a full server transcode is burned in. Remux
 * has no video encode surface, and plain text stays client-rendered.
 */
internal fun shouldBurnStyledSubtitle(
    isRemux: Boolean,
    subtitleTrackIndex: Int?,
    subtitleCodec: String?,
): Boolean =
    !isRemux &&
        subtitleTrackIndex != null &&
        subtitleCodec?.trim()?.lowercase() in setOf("ass", "ssa")
