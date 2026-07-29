package org.siloserver.silo.common.player

import org.siloserver.silo.common.diagnostics.DiagnosticsPlaybackLogger
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
import org.siloserver.silo.model.playback.PlaybackSubtitleModeV3
import org.siloserver.silo.model.playback.SEEK_FAILURE_RECOVERY_V3_OPERATION
import org.siloserver.silo.model.playback.SEEK_REANCHOR_V3_FEATURE
import org.siloserver.silo.model.playback.SEEK_REANCHOR_V3_OPERATION
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.repository.PlaybackRepository
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.siloserver.silo.common.player.audio.PassthroughSuppressionRegistry

data class StagedVideoReplan(
    val basePlaybackAttemptId: String,
    val baseSessionId: String,
    val basePlanAttemptId: String,
    val candidate: VideoSessionStartV3.Ready,
    val candidateSessionId: String,
    val outputRouteGeneration: Long,
)

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
    /**
     * Owns asynchronous predecessor cleanup after a committed publication.
     * Production uses the manager's long-lived IO scope. Tests may inject their
     * structured scope so cleanup is observable and cannot outlive the test.
     */
    private val committedSessionCleanupScope: CoroutineScope? = null,
    /**
     * How long a content reset waits for a deferred publication before rolling
     * it back itself. Injectable because it is a wall-clock safety net, and
     * `runTest` advances virtual time whenever the scheduler idles — a fixed
     * value would fire inside tests that are legitimately waiting for a
     * settlement, making them order-dependent. Tests asserting the wait pass
     * [NEVER_SELF_HEAL]; the test that asserts self-healing passes a real value.
     */
    private val pendingPublicationSettleTimeoutMs: Long? = PENDING_PUBLICATION_SETTLE_TIMEOUT_MS,
) {
    private data class ActiveVideoAttempt(
        val fileId: Int,
        val profileId: String,
        val capabilities: ClientCodecCapabilities,
        val context: ClientPlaybackContext,
        val playbackAttemptId: String,
        val qualityPreference: String,
        /**
         * The bandwidth cap this attempt started under. Carried on the attempt
         * so every replan re-sends it: the cap is a delivery ceiling the server
         * applies per request, so omitting it on recovery would silently lift
         * the limit for the rest of the session.
         */
        val bandwidthCapKbps: Int?,
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
        /**
         * The plan the SERVER currently holds for this session.
         *
         * POST /replan is a commit: once it returns 200 the server has moved on,
         * and for an in-place replan (same session id) there is nothing to undo.
         * `plan` describes what is actually rendering and must still revert on
         * rollback, but the cursor must not — reverting it retires a planId the
         * server has already superseded, after which every later replan is
         * rejected 409 "The failed plan is no longer current" for the rest of
         * the session. Null until this attempt has replanned at least once.
         */
        val serverPlanCursor: ServerPlanCursor? = null,
    )

    /**
     * The plan the server currently holds, falling back to the rendered plan.
     *
     * Every replan/recovery request must address the server by THIS, not by
     * `plan`: after a rollback the two differ, and sending the rendered plan
     * retires a planId the server has already superseded — after which every
     * later request is rejected 409 for the rest of the session.
     */
    private val ActiveVideoAttempt.serverPlanId: String
        get() = serverPlanCursor?.planId ?: plan.planId

    /** Identity of the plan the server last acknowledged for a session. */
    private data class ServerPlanCursor(
        val planId: String,
        val planAttemptId: String,
        val planAttemptKey: String,
        val attemptedPlanKeys: List<String>,
        val attemptCount: Int,
    )

    private data class PreparedStagedVideoReplan(
        val nextAttempt: ActiveVideoAttempt,
        val fallbackReason: String,
    )

    private data class PendingVideoPublication(
        val replacement: ActiveVideoAttempt,
        val predecessor: ActiveVideoAttempt?,
        val settled: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private data class PendingPublicationRollback(
        val replacementSessionId: String,
        val predecessorSessionId: String?,
        val candidateSessionIds: List<String>,
        val settled: CompletableDeferred<Unit>,
    )

    private sealed interface PreparedVideoReplan {
        data class Staged(val value: StagedVideoReplan) : PreparedVideoReplan
        data class ImmediateOutcome(val value: VideoSessionStartV3) : PreparedVideoReplan
    }

    // Suspendable plan operations stay serialized, while synchronous Media3
    // reporter callbacks use CAS below so they never block the playback thread
    // or overwrite a newer plan published by one of those operations.
    private val videoAttemptMutex = Mutex()
    private val contentStartMutex = Mutex()
    private val immediateVideoReplanMutex = Mutex()
    private val telemetryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessionCleanupScope = committedSessionCleanupScope ?: telemetryScope
    private val activeVideoAttempt = AtomicReference<ActiveVideoAttempt?>()
    private val stagedVideoReplans =
        IdentityHashMap<StagedVideoReplan, PreparedStagedVideoReplan>()
    private val orphanedSessionIds = mutableSetOf<String>()
    private var pendingVideoPublication: PendingVideoPublication? = null
    private var contentResetInProgress = false

    private suspend fun <T> withSettledVideoAttempt(
        block: suspend () -> T,
    ): T {
        while (true) {
            videoAttemptMutex.lock()
            val pending = pendingVideoPublication
            if (pending == null) {
                try {
                    return block()
                } finally {
                    videoAttemptMutex.unlock()
                }
            }
            videoAttemptMutex.unlock()
            pending.settled.await()
        }
    }

    suspend fun startVideoSessionV3(
        fileId: Int,
        profileId: String,
        capabilities: ClientCodecCapabilities,
        clientPlaybackContext: ClientPlaybackContext,
        audioTrackIndex: Int?,
        subtitleTrackIndex: Int?,
        qualityPreference: String?,
        startPosition: Double?,
        /**
         * The bandwidth half of the user's quality choice
         * (`playback.max_bitrate_kbps`); null is uncapped.
         *
         * Quality is two axes, and the server applies the cap only from what
         * the client sends — nothing on the playback path reads the stored
         * setting. Sending the resolution alone means a capped preset like
         * "1080p Low" delivers 1080p at whatever bitrate the ladder picks,
         * which is the bandwidth the user explicitly declined.
         */
        maxBitrateKbps: Int? = null,
        subtitleFidelityPreference: SubtitleFidelityPreference = SubtitleFidelityPreference.PRESERVE,
        deferPublication: Boolean = false,
    ): ApiResult<VideoSessionStartV3> = contentStartMutex.withLock {
        try {
            beginContentReset()
            val predecessorForPublication = videoAttemptMutex.withLock {
                activeVideoAttempt.get()
            }
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
                // -1 is the client's "subtitles off" marker, but the server
                // validates subtitle_track_index as 0..10_000 and rejects the
                // whole start with 400 "subtitle_track_index is invalid"
                // (validateTrackPairV3). Omitting the field is how V3 expresses
                // off: ResolveSubtitlePolicyV3 defaults the index to -1 when it
                // is absent and maps index < 0 to SubtitleOffV3, so the plan is
                // identical without tripping the validator. The replan path and
                // the track id above already filter negatives the same way.
                subtitleTrackIndex = subtitleTrackIndex?.takeIf { it >= 0 },
                outputRouteGeneration = clientPlaybackContext.output.outputRouteGeneration,
                metered = network.metered,
                bandwidthEstimateKbps = network.bandwidthEstimateKbps,
                bandwidthCapKbps = maxBitrateKbps?.takeIf { it > 0 },
                capabilities = capabilities,
                clientPlaybackContext = clientPlaybackContext,
            )
            return@withLock when (val result = playbackRepository.startPlaybackV3(request)) {
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
                        videoAttemptMutex.withLock {
                            installActiveVideoAttemptLocked(
                                replacement = active,
                                predecessor = predecessorForPublication,
                                deferPublication = deferPublication,
                            )
                        }
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
                        if (!deferPublication) {
                            videoAttemptMutex.withLock {
                                activeVideoAttempt.set(null)
                            }
                        }
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
                        if (!deferPublication) {
                            videoAttemptMutex.withLock {
                                activeVideoAttempt.set(null)
                            }
                        }
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
                        finishContentReset()
                        val replanResult = replanActiveVideoSession(
                            classification = validated.reason,
                            message = "The server returned a legacy player route.",
                            positionSeconds = startPosition ?: 0.0,
                            audioTrackIndex = audioTrackIndex,
                            subtitleTrackIndex = subtitleTrackIndex,
                        )
                        if (deferPublication) {
                            var abandonedSessionId: String? = null
                            videoAttemptMutex.withLock {
                                val replacement = activeVideoAttempt.get()
                                val ready = replanResult is ApiResult.Success &&
                                    replanResult.data is VideoSessionStartV3.Ready
                                if (ready && replacement != null) {
                                    pendingVideoPublication = PendingVideoPublication(
                                        replacement = replacement,
                                        predecessor = predecessorForPublication,
                                    )
                                } else {
                                    if (replacement?.sessionId == active.sessionId) {
                                        abandonedSessionId = active.sessionId
                                    }
                                    revertRenderedPlanKeepingCursor(predecessorForPublication)
                                    predecessorForPublication?.let {
                                        PassthroughSuppressionRegistry.beginAttempt(it.planAttemptKey)
                                    }
                                }
                            }
                            abandonedSessionId?.let { playbackRepository.stopPlayback(it) }
                        } else if (
                            replanResult is ApiResult.Error ||
                            replanResult is ApiResult.NetworkError
                        ) {
                            val cleared = videoAttemptMutex.withLock {
                                activeVideoAttempt.compareAndSet(active, null)
                            }
                            if (cleared) {
                                playbackRepository.stopPlayback(validated.sessionId)
                            }
                        }
                        replanResult
                    }
                }
                is ApiResult.Error -> result
                is ApiResult.NetworkError -> result
            }
        } finally {
            finishContentReset()
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
            bandwidthCapKbps = request.bandwidthCapKbps,
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

    private fun installActiveVideoAttemptLocked(
        replacement: ActiveVideoAttempt,
        predecessor: ActiveVideoAttempt?,
        deferPublication: Boolean,
    ) {
        activeVideoAttempt.set(replacement)
        pendingVideoPublication = if (deferPublication) {
            PendingVideoPublication(
                replacement = replacement,
                predecessor = predecessor?.takeIf {
                    it.sessionId != replacement.sessionId
                },
            )
        } else {
            null
        }
    }

    private suspend fun beginContentReset() {
        val callerContext = currentCoroutineContext()
        var resetState: Pair<List<String>, String?>? = null
        while (resetState == null) {
            videoAttemptMutex.lock()
            val pending = pendingVideoPublication
            if (pending != null) {
                videoAttemptMutex.unlock()
                // Bounded, because settlement is owned by a *different* object.
                // The manager's pending publication is created inside
                // startVideoSessionV3, while the lifecycle's counterpart is
                // installed by the caller afterwards; a cancellation between the
                // two leaves this one with nobody to settle it. Waiting forever
                // then wedged every future start — an unrecoverable spinner —
                // because the lifecycle-side recovery hatch reports success when
                // its own pending is absent and never consults this one.
                val settled = if (pendingPublicationSettleTimeoutMs == null) {
                    pending.settled.await()
                } else {
                    withTimeoutOrNull(pendingPublicationSettleTimeoutMs) {
                        pending.settled.await()
                    }
                }
                if (settled == null) {
                    Log.w(
                        TAG,
                        "pending publication ${pending.replacement.sessionId} never settled; rolling it back",
                    )
                    rollbackUnpublishedVideoSession(pending.replacement.sessionId)
                    // Guarantees progress even if the rollback found nothing to
                    // do: an unsettled deferred publication must not outlive the
                    // start that is waiting on it.
                    pending.settled.complete(Unit)
                    videoAttemptMutex.withLock {
                        if (pendingVideoPublication === pending) pendingVideoPublication = null
                    }
                }
                continue
            }
            try {
                // Publication settlement was observed while holding the same
                // mutex used to fence replans. Marking the reset in progress
                // here prevents a new pending publication from appearing
                // between the check above and staged-candidate drainage.
                contentResetInProgress = true
                val activeSessionId = activeVideoAttempt.get()?.sessionId
                resetState = (
                    drainStagedCandidateSessionsLocked(
                        protectedSessionIds = setOfNotNull(activeSessionId),
                    )
                ).distinct().filterNot { it == activeSessionId } to activeSessionId
            } finally {
                videoAttemptMutex.unlock()
            }
        }
        val (candidateSessionIds, protectedSessionId) = checkNotNull(resetState)
        withContext(NonCancellable) {
            try {
                stopSessionsRetainingFailures(candidateSessionIds)
            } finally {
                drainOrphanedSessions(protectedSessionIds = setOfNotNull(protectedSessionId))
            }
        }
        callerContext.ensureActive()
    }

    private suspend fun finishContentReset() {
        withContext(NonCancellable) {
            videoAttemptMutex.withLock {
                contentResetInProgress = false
            }
        }
    }

    private fun drainStagedCandidateSessionsLocked(
        protectedSessionIds: Set<String>,
    ): List<String> = stagedVideoReplans.keys
        .map { it.candidateSessionId }
        .distinct()
        .filter { it !in protectedSessionIds }
        .also { stagedVideoReplans.clear() }

    private fun drainStagedCandidateSessionsForBaseLocked(
        baseSessionId: String,
        protectedSessionIds: Set<String>,
    ): List<String> {
        val matchingHandles = stagedVideoReplans.keys
            .filter { it.baseSessionId == baseSessionId }
        matchingHandles.forEach { stagedVideoReplans.remove(it) }
        val remainingCandidateSessionIds = stagedVideoReplans.keys
            .mapTo(mutableSetOf()) { it.candidateSessionId }
        return matchingHandles
            .map { it.candidateSessionId }
            .distinct()
            .filter { it !in protectedSessionIds && it !in remainingCandidateSessionIds }
    }

    internal fun activeSessionIdForTest(): String? = activeVideoAttempt.get()?.sessionId

    internal suspend fun orphanedSessionIdsForTest(): Set<String> =
        videoAttemptMutex.withLock { orphanedSessionIds.toSet() }

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
    ): ApiResult<VideoSessionStartV3> = immediateVideoReplanMutex.withLock {
        when (
            val prepared = prepareActiveVideoSessionReplan(
                classification = classification,
                message = message,
                positionSeconds = positionSeconds,
                audioTrackIndex = audioTrackIndex,
                subtitleTrackIndex = subtitleTrackIndex,
                decoderName = decoderName,
                diagnostics = diagnostics,
                qualityPreference = qualityPreference,
                capabilities = capabilities,
                clientPlaybackContext = clientPlaybackContext,
                preserveImmediateOutcomes = true,
            )
        ) {
            is ApiResult.Success -> when (val value = prepared.data) {
                is PreparedVideoReplan.Staged -> commitStagedVideoReplan(value.value)
                is PreparedVideoReplan.ImmediateOutcome -> ApiResult.Success(value.value)
            }
            is ApiResult.Error -> prepared
            is ApiResult.NetworkError -> prepared
        }
    }

    suspend fun stageActiveVideoSessionReplan(
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
    ): ApiResult<StagedVideoReplan> = when (
        val prepared = prepareActiveVideoSessionReplan(
            classification = classification,
            message = message,
            positionSeconds = positionSeconds,
            audioTrackIndex = audioTrackIndex,
            subtitleTrackIndex = subtitleTrackIndex,
            decoderName = decoderName,
            diagnostics = diagnostics,
            qualityPreference = qualityPreference,
            capabilities = capabilities,
            clientPlaybackContext = clientPlaybackContext,
            preserveImmediateOutcomes = false,
        )
    ) {
        is ApiResult.Success -> when (val value = prepared.data) {
            is PreparedVideoReplan.Staged -> ApiResult.Success(value.value)
            is PreparedVideoReplan.ImmediateOutcome -> ApiResult.Error(
                code = 500,
                error = "invalid_staged_replan_state",
                message = "A staged replan unexpectedly produced an immediate playback outcome.",
            )
        }
        is ApiResult.Error -> prepared
        is ApiResult.NetworkError -> prepared
    }

    private suspend fun prepareActiveVideoSessionReplan(
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
        preserveImmediateOutcomes: Boolean,
    ): ApiResult<PreparedVideoReplan> = withSettledVideoAttempt {
        if (contentResetInProgress) {
            return@withSettledVideoAttempt ApiResult.Error(
                code = 409,
                error = "content_reset_in_progress",
                message = "A replacement playback content session is still being installed.",
            )
        }
        val active = activeVideoAttempt.get() ?: return@withSettledVideoAttempt ApiResult.Error(
            code = 409,
            error = "playback_attempt_not_active",
            message = "No protocol-v3 playback attempt is active.",
        )
        if (classification == SEEK_REANCHOR_V3_OPERATION ||
            classification == SEEK_FAILURE_RECOVERY_V3_OPERATION
        ) {
            return@withSettledVideoAttempt ApiResult.Error(
                code = 400,
                error = "reserved_playback_operation",
                message = "Seek operations must use the dedicated playback session methods.",
            )
        }
        val currentCapabilities = capabilities ?: active.capabilities
        val currentContext = clientPlaybackContext ?: active.context
        val network = networkEvidenceProvider.snapshot()
        val failedKey = active.planAttemptKey
        val invalidation = classification in USER_INVALIDATION_CLASSIFICATIONS
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
        // Address the server by the plan IT holds, not the one we are rendering.
        // After a rollback those differ, and using the rendered plan sends a
        // retired failedPlanId that the server rejects with 409.
        val cursor = active.serverPlanCursor
        val request = PlaybackReplanRequestV3(
            playbackAttemptId = active.playbackAttemptId,
            replanRequestId = UUID.randomUUID().toString(),
            failedPlanId = active.serverPlanId,
            planAttemptId = cursor?.planAttemptId ?: active.planAttemptId,
            planAttemptKey = failedKey,
            attemptedPlanKeys = attemptedKeys,
            attemptCount = requestAttemptCount,
            qualityPreference = qualityPreference?.lowercase() ?: active.qualityPreference,
            positionSeconds = positionSeconds,
            outputRouteGeneration = currentContext.output.outputRouteGeneration,
            metered = network.metered,
            bandwidthEstimateKbps = network.bandwidthEstimateKbps,
            // The cap is a per-request delivery ceiling: omitting it on a
            // replan would silently lift the user's bandwidth limit for the
            // rest of the session.
            bandwidthCapKbps = active.bandwidthCapKbps,
            selectedTracks = SelectedPlaybackTracksV3(
                audio = selectedTrackIdentity(active, "audio", audioTrackIndex, active.plan.selectedTracks.audio),
                subtitle = subtitleTrackIndex?.takeIf { it >= 0 }
                    ?.let { selectedTrackIdentity(active, "subtitle", it, active.plan.selectedTracks.subtitle) },
            ),
            failure = PlaybackFailureV3(classification, message, decoderName),
            capabilities = currentCapabilities,
            clientPlaybackContext = currentContext,
        )
        val result = playbackRepository.replanPlaybackV3(active.sessionId, request)
        if (result is ApiResult.Success) {
            // The server has committed this plan. Record it before any
            // validation branch: several of those return early (loop detected,
            // invalid candidate, discard) and every one of them would otherwise
            // leave the cursor addressing a plan the server has already retired.
            result.data.playbackPlan?.let { committedPlan ->
                val committedKey = committedPlan.planAttemptKey(
                    currentContext.output.outputRouteGeneration,
                )
                // Compare-and-set: a supersession may already have swapped the
                // attempt while this response was in flight, and a plain
                // get()/set() would silently restore the superseded one.
                activeVideoAttempt.get()
                    ?.takeIf { it.sessionId == active.sessionId }
                    ?.let { live ->
                        activeVideoAttempt.compareAndSet(
                            live,
                            live.copy(
                                serverPlanCursor = ServerPlanCursor(
                                    planId = committedPlan.planId,
                                    // planAttemptId is client-generated per
                                    // attempt; the server keys currency off
                                    // planId, so carry ours forward unchanged.
                                    planAttemptId = live.planAttemptId,
                                    planAttemptKey = committedKey,
                                    attemptedPlanKeys = (attemptedKeys + committedKey).distinct(),
                                    attemptCount = requestAttemptCount,
                                ),
                            ),
                        )
                    }
            }
        }
        when (result) {
            is ApiResult.Success -> when (val validated = result.data.validateForMedia3()) {
                is PlaybackV3Validation.Playable -> {
                    val nextKey = validated.plan.planAttemptKey(currentContext.output.outputRouteGeneration)
                    if (nextKey in attemptedKeys) {
                        stopCandidateSessionIfUnowned(active.sessionId, validated.sessionId)
                        if (preserveImmediateOutcomes) {
                            activeVideoAttempt.set(null)
                            return@withSettledVideoAttempt ApiResult.Success(
                                PreparedVideoReplan.ImmediateOutcome(
                                    VideoSessionStartV3.Terminal(
                                        "replan_loop_detected",
                                        "The server returned a playback plan that already failed on this output route.",
                                        false,
                                    ),
                                ),
                            )
                        }
                        return@withSettledVideoAttempt ApiResult.Error(
                            code = 409,
                            error = "replan_loop_detected",
                            message = "The server returned a playback plan that already failed on this output route.",
                        )
                    }
                    val subtitleMismatch = subtitleCandidateMismatch(
                        requested = request.selectedTracks.subtitle,
                        candidate = validated.plan,
                    )
                    if (subtitleMismatch != null) {
                        stopCandidateSessionIfUnowned(active.sessionId, validated.sessionId)
                        return@withSettledVideoAttempt ApiResult.Error(
                            code = 502,
                            error = "invalid_subtitle_replan_candidate",
                            message = subtitleMismatch,
                        )
                    }
                    val nextAttemptId = UUID.randomUUID().toString()
                    val next = active.copy(
                        sessionId = validated.sessionId,
                        plan = validated.plan,
                        // A committed replan makes the rendered plan the server
                        // plan, so no cursor is needed — `cursor ?: plan.planId`
                        // then addresses correctly. Clearing it also matters:
                        // this copy is taken from the PRE-request snapshot, so
                        // carrying `active`'s cursor forward would reinstate a
                        // retired planId, and hoisting the CAS-written one is
                        // wrong too because its planAttemptId belongs to the
                        // previous attempt, not to nextAttemptId below.
                        serverPlanCursor = null,
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
                    val ready = VideoSessionStartV3.Ready(
                        session = validated.plan.toSessionResponse(
                            validated.sessionId,
                            active.profileId,
                            active.fileId,
                        ),
                        plan = validated.plan,
                        playbackAttemptId = active.playbackAttemptId,
                        planAttemptId = nextAttemptId,
                        planAttemptKey = nextKey,
                    )
                    val staged = StagedVideoReplan(
                        basePlaybackAttemptId = active.playbackAttemptId,
                        baseSessionId = active.sessionId,
                        basePlanAttemptId = active.planAttemptId,
                        candidate = ready,
                        candidateSessionId = validated.sessionId,
                        outputRouteGeneration = next.context.output.outputRouteGeneration,
                    )
                    stagedVideoReplans[staged] = PreparedStagedVideoReplan(
                        nextAttempt = next,
                        fallbackReason = classification,
                    )
                    ApiResult.Success(PreparedVideoReplan.Staged(staged))
                }
                is PlaybackV3Validation.Terminal -> {
                    if (preserveImmediateOutcomes) {
                        reportActiveVideoEvent(
                            event = "terminal",
                            diagnostics = mapOf("reason" to validated.reason),
                        )
                        activeVideoAttempt.set(null)
                        stopImmediateFailureSessions(
                            activeSessionId = active.sessionId,
                            candidateSessionIds = listOf(result.data.sessionId),
                            stopActiveSession = true,
                        )
                        ApiResult.Success(
                            PreparedVideoReplan.ImmediateOutcome(
                                VideoSessionStartV3.Terminal(
                                    validated.reason,
                                    validated.message,
                                    validated.retryable,
                                ),
                            ),
                        )
                    } else {
                        stopCandidateSessionsIfUnowned(
                            active.sessionId,
                            result.data.sessionId,
                            result.data.playbackPlan?.sessionId,
                        )
                        ApiResult.Error(
                            code = 409,
                            error = validated.reason,
                            message = validated.message,
                        )
                    }
                }
                is PlaybackV3Validation.Incompatible -> {
                    if (preserveImmediateOutcomes) {
                        activeVideoAttempt.set(null)
                        stopCandidateSessionIfUnowned(
                            activeSessionId = null,
                            candidateSessionId = validated.allocatedSessionId,
                        )
                        ApiResult.Success(
                            PreparedVideoReplan.ImmediateOutcome(
                                VideoSessionStartV3.ServerUpgradeRequired,
                            ),
                        )
                    } else {
                        stopCandidateSessionIfUnowned(active.sessionId, validated.allocatedSessionId)
                        ApiResult.Error(
                            code = 502,
                            error = "playback_server_upgrade_required",
                            message = "The server returned an incompatible playback replan.",
                        )
                    }
                }
                is PlaybackV3Validation.ReplanRequired -> {
                    if (preserveImmediateOutcomes) {
                        activeVideoAttempt.set(null)
                        stopImmediateFailureSessions(
                            activeSessionId = active.sessionId,
                            candidateSessionIds = listOf(validated.sessionId),
                            stopActiveSession = true,
                        )
                        ApiResult.Success(
                            PreparedVideoReplan.ImmediateOutcome(
                                VideoSessionStartV3.Terminal(
                                    "unsupported_legacy_engine",
                                    "The server could not provide a Media3 playback route.",
                                    false,
                                ),
                            ),
                        )
                    } else {
                        stopCandidateSessionIfUnowned(active.sessionId, validated.sessionId)
                        ApiResult.Error(
                            code = 502,
                            error = "unsupported_legacy_engine",
                            message = "The server could not provide a Media3 playback route.",
                        )
                    }
                }
            }
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }

    suspend fun commitStagedVideoReplan(
        staged: StagedVideoReplan,
        deferPublication: Boolean = false,
    ): ApiResult<VideoSessionStartV3.Ready> = videoAttemptMutex.withLock {
        val prepared = stagedVideoReplans.remove(staged)
            ?: return@withLock stagedVideoReplanUnavailable()
        val active = activeVideoAttempt.get()
        if (active == null ||
            active.playbackAttemptId != staged.basePlaybackAttemptId ||
            active.sessionId != staged.baseSessionId ||
            active.planAttemptId != staged.basePlanAttemptId
        ) {
            stopCandidateSessionIfUnowned(active?.sessionId, staged.candidateSessionId)
            return@withLock stagedVideoReplanUnavailable()
        }

        val next = prepared.nextAttempt
        PassthroughSuppressionRegistry.beginAttempt(next.planAttemptKey)
        val routeEvent = PlaybackRouteEventV3(
            playbackAttemptId = next.playbackAttemptId,
            sessionId = next.sessionId,
            planId = next.plan.planId,
            planAttemptId = next.planAttemptId,
            planAttemptKey = next.planAttemptKey,
            event = "plan_selected",
            fallbackReason = prepared.fallbackReason,
            appliedQuirkIds = next.plan.appliedQuirks.map { it.id },
            quirkRegistryRevision = next.plan.appliedQuirks.firstOrNull()?.registryRevision,
            outputRouteGeneration = next.context.output.outputRouteGeneration,
        )

        // This is the commit point. Everything after it is best-effort,
        // non-blocking bookkeeping: callers must always receive the committed
        // candidate once manager ownership has moved to [next].
        activeVideoAttempt.set(next)
        if (deferPublication) {
            pendingVideoPublication = PendingVideoPublication(
                replacement = next,
                predecessor = active,
            )
        }
        runCatching { emitRouteEvent(routeEvent) }
        if (!deferPublication) {
            scheduleCommittedSessionCleanup(
                oldSessionId = active.sessionId,
                activeSessionId = next.sessionId,
            )
        }
        ApiResult.Success(staged.candidate)
    }

    suspend fun confirmVideoSessionPublication(sessionId: String): Boolean {
        val confirmation = videoAttemptMutex.withLock {
            val pending = pendingVideoPublication
                ?.takeIf { it.replacement.sessionId == sessionId }
                ?: return@withLock null
            if (activeVideoAttempt.get()?.sessionId != sessionId) return@withLock null
            pendingVideoPublication = null
            val predecessorSessionId = pending.predecessor?.sessionId
                ?.takeIf { it != sessionId }
            predecessorSessionId?.let { orphanedSessionIds += it }
            pending.settled.complete(Unit)
            true to predecessorSessionId
        } ?: return false

        val predecessorSessionId = confirmation.second
        if (predecessorSessionId != null) {
            scheduleRegisteredCommittedSessionCleanup(
                oldSessionId = predecessorSessionId,
                activeSessionId = sessionId,
            )
        }
        return true
    }

    /**
     * Rolls back whatever deferred publication this manager still holds.
     *
     * The lifecycle's `rollbackCurrentPendingPublication` can only settle a
     * publication the *lifecycle* knows about, and reports success when it has
     * none — but the manager's is created first, so a cancellation between the
     * two leaves this side pending with no owner. Callers about to start fresh
     * content should clear both.
     *
     * Returns true when nothing is pending or the rollback succeeded.
     */
    suspend fun rollbackCurrentPendingVideoPublication(): Boolean {
        val pendingSessionId = videoAttemptMutex.withLock {
            pendingVideoPublication?.replacement?.sessionId
        } ?: return true
        return rollbackUnpublishedVideoSession(pendingSessionId)
    }

    /**
     * Drops manager ownership of [sessionId] and stops it.
     *
     * For a non-deferred commit there is no publication to roll back: ownership
     * has already moved to the new attempt and the predecessor's session is
     * being cleaned up, so a caller that must abandon the result cannot revert
     * to anything. Stopping the session while leaving it installed as the active
     * attempt left every later replan and progress report aimed at a session the
     * server had already torn down.
     */
    suspend fun abandonActiveVideoSession(sessionId: String): Boolean {
        val disowned = videoAttemptMutex.withLock {
            val active = activeVideoAttempt.get()
            if (active?.sessionId != sessionId) false
            else activeVideoAttempt.compareAndSet(active, null)
        }
        stopSession(sessionId)
        return disowned
    }

    suspend fun rollbackUnpublishedVideoSession(sessionId: String): Boolean {
        val rollback = videoAttemptMutex.withLock {
            rollbackPendingPublicationLocked(sessionId)
        } ?: return false
        return try {
            try {
                if (rollback.replacementSessionId == rollback.predecessorSessionId) {
                    // In-place replan: the server reused the session id, so the
                    // "replacement" we would stop is the session still playing.
                    // Ownership already reverted to it; only candidates go.
                    stopSessionAfterOwnershipCleared(
                        sessionId = null,
                        candidateSessionIds = rollback.candidateSessionIds,
                    )
                } else {
                    stopSessionAfterOwnershipCleared(
                        sessionId = rollback.replacementSessionId,
                        candidateSessionIds = rollback.candidateSessionIds,
                    )
                }
            } catch (_: Throwable) {
                // Ownership already converged to the predecessor under
                // videoAttemptMutex. The replacement is orphan-tracked before
                // its best-effort stop, so cleanup failure or caller
                // cancellation must not veto the lifecycle half of rollback.
                // A cancelled caller remains cancelled and will observe that
                // at its next cancellation check after joint convergence.
            }
            true
        } finally {
            rollback.settled.complete(Unit)
        }
    }


    /**
     * Reverts what is rendering while KEEPING the server-side plan cursor.
     *
     * A replan the server has acknowledged cannot be un-acknowledged — for an
     * in-place replan there is not even a second session to discard. Restoring
     * the predecessor wholesale also restores its plan identity, which the
     * server has already retired, so every subsequent replan is rejected 409
     * "The failed plan is no longer current" until playback restarts.
     */
    private fun revertRenderedPlanKeepingCursor(predecessor: ActiveVideoAttempt?) {
        // The predecessor's own cursor wins: it describes the plan the server
        // holds for ITS session. Only borrow the live attempt's cursor when the
        // predecessor has none AND the two are the same session — otherwise a
        // failed start of a different item would graft its cursor onto the item
        // still playing, permanently retiring a plan the server never issued
        // for it and disabling every later replan on that session.
        val live = activeVideoAttempt.get()
        val carried = live
            ?.takeIf { it.sessionId == predecessor?.sessionId }
            ?.serverPlanCursor
        activeVideoAttempt.set(
            predecessor?.copy(serverPlanCursor = predecessor.serverPlanCursor ?: carried),
        )
    }

    private fun rollbackPendingPublicationLocked(
        sessionId: String,
    ): PendingPublicationRollback? {
        val pending = pendingVideoPublication
            ?.takeIf { it.replacement.sessionId == sessionId }
            ?: return null
        if (activeVideoAttempt.get()?.sessionId != sessionId) return null

        pendingVideoPublication = null
        revertRenderedPlanKeepingCursor(pending.predecessor)
        pending.predecessor?.let {
            PassthroughSuppressionRegistry.beginAttempt(it.planAttemptKey)
        }
        val protectedSessionIds = setOfNotNull(
            sessionId,
            pending.predecessor?.sessionId,
        )
        val candidates = drainStagedCandidateSessionsForBaseLocked(
            baseSessionId = sessionId,
            protectedSessionIds = protectedSessionIds,
        )
        return PendingPublicationRollback(
            replacementSessionId = sessionId,
            predecessorSessionId = pending.predecessor?.sessionId,
            candidateSessionIds = candidates,
            settled = pending.settled,
        )
    }

    private fun scheduleCommittedSessionCleanup(
        oldSessionId: String,
        activeSessionId: String,
    ) {
        if (oldSessionId == activeSessionId) return
        orphanedSessionIds += oldSessionId
        scheduleRegisteredCommittedSessionCleanup(
            oldSessionId = oldSessionId,
            activeSessionId = activeSessionId,
        )
    }

    private fun scheduleRegisteredCommittedSessionCleanup(
        oldSessionId: String,
        activeSessionId: String,
    ) {
        if (oldSessionId == activeSessionId) return
        runCatching {
            sessionCleanupScope.launch(start = CoroutineStart.UNDISPATCHED) {
                var stopped = false
                for (attempt in 0 until COMMITTED_SESSION_CLEANUP_ATTEMPTS) {
                    val result = try {
                        playbackRepository.stopPlayback(oldSessionId)
                    } catch (_: CancellationException) {
                        return@launch
                    } catch (_: Throwable) {
                        null
                    }
                    if (result is ApiResult.Success) {
                        stopped = true
                        break
                    }
                }
                if (stopped) {
                    videoAttemptMutex.withLock {
                        orphanedSessionIds -= oldSessionId
                    }
                }
            }
        }
    }

    private suspend fun drainOrphanedSessions(protectedSessionIds: Set<String>) {
        val orphanIds = videoAttemptMutex.withLock {
            val live = setOfNotNull(activeVideoAttempt.get()?.sessionId)
            orphanedSessionIds.filterNot { it in protectedSessionIds || it in live }
        }
        orphanIds.forEach { sessionId ->
            val result = try {
                playbackRepository.stopPlayback(sessionId)
            } catch (_: CancellationException) {
                return@forEach
            } catch (_: Throwable) {
                null
            }
            if (result is ApiResult.Success) {
                videoAttemptMutex.withLock {
                    orphanedSessionIds -= sessionId
                }
            }
        }
    }

    private suspend fun stopSessionsRetainingFailures(sessionIds: Collection<String>) {
        val uniqueSessionIds = sessionIds.distinct()
        if (uniqueSessionIds.isEmpty()) return
        videoAttemptMutex.withLock {
            orphanedSessionIds += uniqueSessionIds
        }
        uniqueSessionIds.forEach { sessionId ->
            val result = try {
                playbackRepository.stopPlayback(sessionId)
            } catch (_: Throwable) {
                null
            }
            if (result is ApiResult.Success) {
                videoAttemptMutex.withLock {
                    orphanedSessionIds -= sessionId
                }
            }
        }
    }

    suspend fun discardStagedVideoReplan(staged: StagedVideoReplan) {
        val candidateSessionId = videoAttemptMutex.withLock {
            if (stagedVideoReplans.remove(staged) == null) return
            staged.candidateSessionId?.takeIf { candidateSessionId ->
                // The server may replan in place and hand back the SAME session
                // id it was given. Such a candidate is not a disposable session:
                // stopping it kills the playback the user is still watching.
                candidateSessionId != staged.baseSessionId &&
                    candidateSessionId != activeVideoAttempt.get()?.sessionId &&
                    stagedVideoReplans.keys.none {
                        it.candidateSessionId == candidateSessionId
                    }
            }?.also { orphanedSessionIds += it }
        }
        if (candidateSessionId == null) return

        withContext(NonCancellable) {
            val result = try {
                playbackRepository.stopPlayback(candidateSessionId)
            } catch (_: Throwable) {
                null
            }
            if (result is ApiResult.Success) {
                videoAttemptMutex.withLock {
                    orphanedSessionIds -= candidateSessionId
                }
            }
        }
    }

    private fun stagedVideoReplanUnavailable(): ApiResult.Error = ApiResult.Error(
        code = 409,
        error = "staged_video_replan_unavailable",
        message = "The staged playback replan was already consumed or no longer matches the active content.",
    )

    private suspend fun stopCandidateSessionIfUnowned(
        activeSessionId: String?,
        candidateSessionId: String?,
    ) {
        if (candidateSessionId == null ||
            candidateSessionId == activeSessionId ||
            candidateSessionId == activeVideoAttempt.get()?.sessionId ||
            stagedVideoReplans.keys.any { it.candidateSessionId == candidateSessionId }
        ) {
            return
        }
        playbackRepository.stopPlayback(candidateSessionId)
    }

    private suspend fun stopCandidateSessionsIfUnowned(
        activeSessionId: String?,
        vararg candidateSessionIds: String?,
    ) {
        candidateSessionIds.filterNotNull().distinct()
            .forEach { stopCandidateSessionIfUnowned(activeSessionId, it) }
    }

    private suspend fun stopImmediateFailureSessions(
        activeSessionId: String,
        candidateSessionIds: List<String?>,
        stopActiveSession: Boolean,
    ) {
        if (stopActiveSession) {
            playbackRepository.stopPlayback(activeSessionId)
        }
        candidateSessionIds.filterNotNull().distinct()
            .filter { it != activeSessionId }
            .forEach { stopCandidateSessionIfUnowned(activeSessionId = null, it) }
    }

    private fun subtitleCandidateMismatch(
        requested: PlaybackTrackIdentityV3?,
        candidate: PlaybackPlanV3,
    ): String? {
        val selected = candidate.selectedTracks.subtitle
        val subtitle = candidate.subtitle
        if (requested == null) {
            return if (selected == null &&
                subtitle.mode == PlaybackSubtitleModeV3.OFF &&
                subtitle.trackId == null &&
                subtitle.artifact == null
            ) {
                null
            } else {
                "The candidate did not keep subtitles off."
            }
        }
        if (selected?.id != requested.id ||
            selected?.index != requested.index ||
            subtitle.trackId != requested.id
        ) {
            return "The candidate did not select the exact requested subtitle track."
        }
        return when (subtitle.mode) {
            PlaybackSubtitleModeV3.BURN_IN -> null
            PlaybackSubtitleModeV3.CONVERT,
            PlaybackSubtitleModeV3.RENDER,
            -> {
                val artifact = subtitle.artifact
                if (artifact == null ||
                    artifact.url.isBlank() ||
                    artifact.mimeType.isBlank() ||
                    artifact.format.isBlank()
                ) {
                    "The candidate omitted the exact requested subtitle artifact."
                } else {
                    null
                }
            }
            PlaybackSubtitleModeV3.OFF ->
                "The candidate disabled the requested subtitle track."
        }
    }

    /** Reopens the active V3 transport at a new source-time origin. */
    suspend fun reanchorActiveVideoSession(
        positionSeconds: Double,
        diagnostics: Map<String, String> = emptyMap(),
    ): ApiResult<VideoSessionStartV3> = withSettledVideoAttempt {
        if (contentResetInProgress) {
            return@withSettledVideoAttempt ApiResult.Error(
                code = 409,
                error = "content_reset_in_progress",
                message = "A replacement playback content session is still being installed.",
            )
        }
        val active = activeVideoAttempt.get() ?: return@withSettledVideoAttempt ApiResult.Error(
            code = 409,
            error = "playback_attempt_not_active",
            message = "No protocol-v3 playback attempt is active.",
        )
        if (SEEK_REANCHOR_V3_FEATURE !in active.serverFeatures) {
            return@withSettledVideoAttempt ApiResult.Error(
                code = 409,
                error = "seek_reanchor_not_supported",
                message = "The active playback server did not negotiate seek re-anchoring.",
            )
        }
        if (!positionSeconds.isFinite() || positionSeconds < 0.0) {
            return@withSettledVideoAttempt ApiResult.Error(
                code = 400,
                error = "invalid_seek_position",
                message = "Seek position must be a finite, non-negative source timestamp.",
            )
        }

        // Re-anchor deliberately addresses the RENDERED plan below, because
        // seekReanchorMismatch requires the response to come back on it. Once a
        // rollback has left the rendered plan behind the server's, that request
        // can only 409 — decline so the caller falls through to seek recovery,
        // which addresses the server's plan.
        if (active.serverPlanCursor?.planId?.let { it != active.plan.planId } == true) {
            return@withSettledVideoAttempt ApiResult.Error(
                code = 409,
                error = "seek_reanchor_plan_superseded",
                message = "The rendered plan is behind the server's; recover instead.",
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
            bandwidthCapKbps = active.bandwidthCapKbps,
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
                    return@withSettledVideoAttempt invalidSeekReanchorResponse(
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
                            return@withSettledVideoAttempt invalidSeekReanchorResponse(mismatch)
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
                            return@withSettledVideoAttempt ApiResult.Error(
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
            // The server accepted and now holds this plan, so rendered and
            // server plans are back in step and the cursor is spent. Leaving a
            // stale one here re-opens the 409-forever bug via the seek path.
            serverPlanCursor = null,
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
    ): ApiResult<VideoSessionStartV3> = withSettledVideoAttempt {
        if (contentResetInProgress) {
            return@withSettledVideoAttempt ApiResult.Error(
                code = 409,
                error = "content_reset_in_progress",
                message = "A replacement playback content session is still being installed.",
            )
        }
        val active = activeVideoAttempt.get() ?: return@withSettledVideoAttempt ApiResult.Error(
            code = 409,
            error = "playback_attempt_not_active",
            message = "No protocol-v3 playback attempt is active.",
        )
        if (SEEK_REANCHOR_V3_FEATURE !in active.serverFeatures) {
            return@withSettledVideoAttempt ApiResult.Error(
                code = 409,
                error = "seek_reanchor_not_supported",
                message = "The active playback server did not negotiate seek recovery.",
            )
        }
        if (!positionSeconds.isFinite() || positionSeconds < 0.0) {
            return@withSettledVideoAttempt ApiResult.Error(
                code = 400,
                error = "invalid_seek_position",
                message = "Seek position must be a finite, non-negative source timestamp.",
            )
        }
        if (classification.isBlank()) {
            return@withSettledVideoAttempt ApiResult.Error(
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
            failedPlanId = active.serverPlanId,
            planAttemptId = active.planAttemptId,
            planAttemptKey = active.planAttemptKey,
            attemptedPlanKeys = attemptedKeys,
            attemptCount = active.attemptCount,
            qualityPreference = active.qualityPreference,
            positionSeconds = positionSeconds,
            outputRouteGeneration = active.context.output.outputRouteGeneration,
            // Fresh snapshot, matching replanActiveVideoSession: this path lets
            // the server pick a different route, so session-start network
            // evidence would misinform that decision.
            metered = network.metered,
            bandwidthEstimateKbps = network.bandwidthEstimateKbps,
            bandwidthCapKbps = active.bandwidthCapKbps,
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
                    return@withSettledVideoAttempt invalidSeekRecoveryResponse(
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
                            return@withSettledVideoAttempt invalidSeekRecoveryResponse(mismatch)
                        }
                        val nextKey = validated.plan.planAttemptKey(
                            active.context.output.outputRouteGeneration,
                        )
                        if (nextKey in attemptedKeys) {
                            return@withSettledVideoAttempt ApiResult.Success(
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
                            return@withSettledVideoAttempt ApiResult.Error(
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
            // The server accepted and now holds this plan, so rendered and
            // server plans are back in step and the cursor is spent. Leaving a
            // stale one here re-opens the 409-forever bug via the seek path.
            serverPlanCursor = null,
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
        DiagnosticsPlaybackLogger.firstFrame(firstFrameMs)
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
        seekableStreamsOnly: Boolean = false,
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
        seekableStreamsOnly = seekableStreamsOnly,
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
        seekableStreamsOnly: Boolean = false,
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
            seekableStreamsOnly = seekableStreamsOnly,
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
        private const val COMMITTED_SESSION_CLEANUP_ATTEMPTS = 2

        /**
         * How long a content reset waits for a deferred publication to settle
         * before rolling it back itself. Comfortably above the 30s local-mount
         * wait a legitimate subtitle commit can take, so this only fires for a
         * publication whose owner is gone.
         */
        internal const val PENDING_PUBLICATION_SETTLE_TIMEOUT_MS = 45_000L

        /**
         * Pass `null` as the timeout to wait forever.
         *
         * NOT `Long.MAX_VALUE`: `runTest`'s virtual scheduler fires such a
         * timeout immediately, which silently turned the self-heal on inside
         * every test that meant to assert the wait.
         */
        internal val NEVER_SELF_HEAL: Long? = null

        /**
         * Replan classifications that mean a user-initiated track/quality/route
         * change rather than a playback failure. For these the previous route
         * stays mounted and valid while the replan is negotiated, so callers
         * must not treat a failed replan request as fatal to playback.
         */
        val USER_INVALIDATION_CLASSIFICATIONS = setOf(
            "audio_track_changed",
            "subtitle_track_changed",
            "quality_changed",
            "output_route_changed",
        )
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
    open suspend fun stopSession(sessionId: String): ApiResult<Unit> {
        val pendingPublicationStop = videoAttemptMutex.withLock {
            val pending = pendingVideoPublication
            when {
                pending?.replacement?.sessionId == sessionId -> {
                    rollbackPendingPublicationLocked(sessionId)?.let {
                        Triple(
                            it.replacementSessionId,
                            it.candidateSessionIds,
                            it.settled,
                        )
                    }
                }
                pending?.predecessor?.sessionId == sessionId -> {
                    pendingVideoPublication = null
                    activeVideoAttempt.set(null)
                    val candidates = (
                        drainStagedCandidateSessionsLocked(
                            protectedSessionIds = setOf(sessionId),
                        ) + pending.replacement.sessionId
                    ).distinct().filterNot { it == sessionId }
                    Triple(sessionId, candidates, pending.settled)
                }
                else -> null
            }
        }
        if (pendingPublicationStop != null) {
            return try {
                stopSessionAfterOwnershipCleared(
                    sessionId = pendingPublicationStop.first,
                    candidateSessionIds = pendingPublicationStop.second,
                )
            } finally {
                pendingPublicationStop.third.complete(Unit)
            }
        }

        val candidateSessionIds = videoAttemptMutex.withLock {
            var stoppedActiveSession = false
            while (true) {
                val active = activeVideoAttempt.get()
                if (active?.sessionId != sessionId) break
                if (activeVideoAttempt.compareAndSet(active, null)) {
                    emitActiveVideoEvent(active, "stopped")
                    stoppedActiveSession = true
                    break
                }
            }
            if (stoppedActiveSession) {
                drainStagedCandidateSessionsLocked(protectedSessionIds = setOf(sessionId))
            } else {
                drainStagedCandidateSessionsForBaseLocked(
                    baseSessionId = sessionId,
                    protectedSessionIds = setOfNotNull(
                        sessionId,
                        activeVideoAttempt.get()?.sessionId,
                    ),
                )
            }
        }
        return stopSessionAfterOwnershipCleared(
            sessionId = sessionId,
            candidateSessionIds = candidateSessionIds,
        )
    }

    /**
     * Stops [sessionId] once ownership no longer points at it. A null
     * [sessionId] cleans up only the candidates — used when the server replanned
     * in place and the "replacement" is the session still playing.
     */
    private suspend fun stopSessionAfterOwnershipCleared(
        sessionId: String?,
        candidateSessionIds: List<String>,
    ): ApiResult<Unit> {
        val callerContext = currentCoroutineContext()
        var result: ApiResult<Unit>? = null
        var requestedFailure: Throwable? = null
        withContext(NonCancellable) {
            try {
                stopSessionsRetainingFailures(candidateSessionIds)
                if (sessionId != null) {
                    videoAttemptMutex.withLock {
                        orphanedSessionIds += sessionId
                    }
                    try {
                        result = playbackRepository.stopPlayback(sessionId)
                        if (result is ApiResult.Success) {
                            videoAttemptMutex.withLock {
                                orphanedSessionIds -= sessionId
                            }
                        }
                    } catch (failure: Throwable) {
                        requestedFailure = failure
                    }
                } else {
                    result = ApiResult.Success(Unit)
                }
            } finally {
                drainOrphanedSessions(
                    protectedSessionIds = setOfNotNull(activeVideoAttempt.get()?.sessionId),
                )
            }
        }
        callerContext.ensureActive()
        requestedFailure?.let { throw it }
        return requireNotNull(result)
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
