package org.siloserver.silo.common.player

import org.siloserver.silo.common.diagnostics.DiagnosticsPlaybackLogger
import android.os.SystemClock
import android.util.Log
import org.siloserver.silo.model.playback.ClientCodecCapabilities
import org.siloserver.silo.model.playback.ClientPlaybackContext
import org.siloserver.silo.model.playback.PlaybackStartRequestV3
import org.siloserver.silo.model.playback.PlaybackV3Validation
import org.siloserver.silo.model.playback.SubtitleFidelityPreference
import org.siloserver.silo.model.playback.validateForMedia3
import org.siloserver.silo.model.playback.PlaybackFailureV3
import org.siloserver.silo.model.playback.PlaybackPlanV3
import org.siloserver.silo.model.playback.PlaybackOutputContext
import org.siloserver.silo.model.playback.PlaybackReplanRequestV3
import org.siloserver.silo.model.playback.ProgressPersistenceV3
import org.siloserver.silo.model.playback.PlaybackRouteEventV3
import org.siloserver.silo.model.playback.SelectedPlaybackTracksV3
import org.siloserver.silo.model.playback.PlaybackTrackIdentityV3
import org.siloserver.silo.model.playback.PlaybackSubtitleModeV3
import org.siloserver.silo.model.playback.FAILURE_RECOVERY_V3_OPERATION
import org.siloserver.silo.model.playback.INTENT_V3_OPERATIONS
import org.siloserver.silo.model.playback.QUALITY_CHANGE_V3_OPERATION
import org.siloserver.silo.model.playback.SEEK_FAILURE_RECOVERY_V3_OPERATION
import org.siloserver.silo.model.playback.SEEK_REANCHOR_V3_FEATURE
import org.siloserver.silo.model.playback.SEEK_REANCHOR_V3_OPERATION
import org.siloserver.silo.model.playback.TRACK_CHANGE_V3_OPERATION
import org.siloserver.silo.model.playback.playbackClientFeaturesV3
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.repository.PlaybackRepository
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
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
import org.siloserver.silo.common.player.audio.PassthroughSuppressionScope

data class StagedVideoReplan(
    val basePlaybackAttemptId: String,
    val baseSessionId: String,
    val basePlanAttemptId: String,
    val candidate: VideoSessionStartV3.Ready,
    val candidateSessionId: String,
    /**
     * The opaque output context the candidate was planned against. Route events
     * emitted while the stage is in flight carry it so server-side diagnostics
     * can tell a stale route apart from a current one.
     */
    val outputContextId: String?,
)

/**
 * Whether two output snapshots can produce different playback recipes.
 *
 * The opaque context id is provenance, not a capability. Spatializer state is
 * also excluded because the server does not route on it and Android may toggle
 * it as a consequence of remounting the player. A callback that changes only
 * those fields must not erase the failed-plan history and reopen a fallback
 * route that just failed.
 */
internal fun PlaybackOutputContext.hasSamePlanningRouteAs(other: PlaybackOutputContext): Boolean =
    copy(
        outputContextId = null,
        audioPassthrough = audioPassthrough?.copy(spatializerEnabled = false),
    ) == other.copy(
        outputContextId = null,
        audioPassthrough = other.audioPassthrough?.copy(spatializerEnabled = false),
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
    /**
     * Where this manager scopes passthrough suppression. The registry is
     * process-global by necessity — the audio sink that reads it is constructed
     * deep inside Media3 with no route back to a session — so a manager whose
     * audio never reaches a local sink must pass
     * [PassthroughSuppressionScope.None] rather than reset the suppression set
     * belonging to whatever is actually playing. Cast preparation is the case
     * that matters: its plans are for a receiver across the room.
     */
    private val passthroughSuppression: PassthroughSuppressionScope = PassthroughSuppressionRegistry,
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

    /** Atomic identity tuple used by control requests after a local rollback. */
    private val ActiveVideoAttempt.serverControlIdentity: Triple<String, String, String>
        get() = serverPlanCursor?.let { cursor ->
            Triple(cursor.planId, cursor.planAttemptId, cursor.planAttemptKey)
        } ?: Triple(plan.planId, planAttemptId, planAttemptKey)

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
    // Insertion-ordered so the bound in [rememberOrphanedSessionLocked] evicts
    // the oldest unconfirmed session rather than an arbitrary one.
    private val orphanedSessionIds = LinkedHashSet<String>()

    /**
     * Sessions registered as orphans under [videoAttemptMutex] whose stop still
     * has to be issued, each tagged with the release claim of the lock holder
     * that queued it. Guarded by that same mutex; drained immediately after it
     * is released. See [stopRetainingFailureLocked].
     */
    private val pendingOwnershipReleases = mutableListOf<Pair<Long, String>>()

    /**
     * Sessions whose stop is in flight via the queued-release or orphan-drain
     * paths, counted rather than flagged.
     *
     * Two callers can legitimately be releasing the same id — a queued release
     * and an orphan drain that selected it before the queue existed. The count
     * keeps the marker honest for that overlap, so the first to finish cannot
     * clear protection while the second is still running. It does NOT prevent
     * the duplicate request itself: the second stop still goes out, which is
     * tolerable only because stopping an already-stopped session is harmless.
     *
     * Not a register of every stop in the manager: committed-session cleanup and
     * the direct retaining-stop helpers issue their own unmarked stops, so this
     * excludes duplicates between the two paths that consult it, not globally.
     * Guarded by [videoAttemptMutex].
     */
    private val releasesInFlight = mutableMapOf<String, Int>()

    private val releaseClaims = AtomicLong()

    /**
     * The claim of whoever currently holds [videoAttemptMutex]. Only read and
     * written under that lock, which is what makes a plain field safe here —
     * exactly one coroutine can be inside the lock at a time.
     */
    private var currentReleaseClaim = 0L
    private var pendingVideoPublication: PendingVideoPublication? = null
    private var contentResetInProgress = false

    private suspend fun <T> withSettledVideoAttempt(
        block: suspend () -> T,
    ): T {
        while (true) {
            videoAttemptMutex.lock()
            val pending = pendingVideoPublication
            if (pending == null) {
                val claim = releaseClaims.incrementAndGet()
                currentReleaseClaim = claim
                try {
                    return block()
                } finally {
                    videoAttemptMutex.unlock()
                    // After the unlock, deliberately: the block may have queued
                    // stops for sessions it discarded, and issuing them under
                    // the lock would hold every other caller behind network I/O.
                    drainPendingOwnershipReleases(claim)
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
        progressPersistence: ProgressPersistenceV3 = ProgressPersistenceV3.SERVER,
        deferPublication: Boolean = false,
    ): ApiResult<VideoSessionStartV3> = contentStartMutex.withLock {
        /**
         * The session this call is currently answerable for.
         *
         * Once the server responds it has allocated a session, but every branch
         * below still suspends — acquiring [videoAttemptMutex], emitting a route
         * event, issuing its own stop — before that id is either published into
         * [activeVideoAttempt] or stopped. A cancellation in that window leaves
         * the id owned by nobody: the manager never published it, and the
         * callers never learn it, because they only see an id when this function
         * returns. The transcode then runs on until the server's own expiry,
         * holding a stream slot; a retry can produce a second session for the
         * same screen, or fail outright as "too many streams".
         *
         * So: arm this the moment the response decodes, and clear it only where
         * responsibility genuinely moves — to the manager on publication, or to
         * a stop the *server acknowledged*. A branch that takes ownership back
         * (the replan error path) re-arms it. The finally releases whatever is
         * still held, uncancellably.
         *
         * Scope: this covers ids allocated by *this* call. The internal replan
         * reached from the ReplanRequired branch allocates its own candidates
         * and clears `activeVideoAttempt` before its own suspending cleanup;
         * those windows are held by [stopRetainingFailureLocked] instead, which
         * is the same register-before-stop discipline expressed against the
         * manager's orphan set because those paths already hold
         * [videoAttemptMutex].
         */
        var leasedSessionId: String? = null
        try {
            if (progressPersistence == ProgressPersistenceV3.CLIENT && startPosition == null) {
                return@withLock ApiResult.Error(
                    code = 400,
                    error = "client_progress_requires_start_position",
                    message = "Client-owned progress requires an explicit file-local start position.",
                )
            }
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
                progressPersistence = progressPersistence,
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
                clientFeatures = playbackClientFeaturesV3(clientPlaybackContext),
                metered = network.metered,
                bandwidthEstimateKbps = network.bandwidthEstimateKbps,
                bandwidthCapKbps = maxBitrateKbps?.takeIf { it > 0 },
                capabilities = capabilities,
                clientPlaybackContext = clientPlaybackContext,
            )
            return@withLock when (val result = playbackRepository.startPlaybackV3(request)) {
                is ApiResult.Success -> when (val validated = result.data.validateForMedia3()) {
                    is PlaybackV3Validation.Playable -> {
                        leasedSessionId = validated.sessionId
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
                        // Published: the manager owns this id now, so teardown
                        // is its problem rather than this call's.
                        leasedSessionId = null
                        passthroughSuppression.beginAttempt(active.planAttemptKey)
                        reportActiveVideoEvent("plan_selected", network.asRouteDiagnostics())
                        ApiResult.Success(
                            VideoSessionStartV3.Ready(
                                session = validated.plan.toSessionResponse(validated.sessionId, profileId, fileId),
                                plan = validated.plan,
                                playbackAttemptId = playbackAttemptId,
                                planAttemptId = planAttemptId,
                                planAttemptKey = active.planAttemptKey,
                                capabilities = request.capabilities,
                                clientPlaybackContext = request.clientPlaybackContext,
                            ),
                        )
                    }
                    is PlaybackV3Validation.Terminal -> {
                        leasedSessionId =
                            result.data.playbackPlan?.sessionId ?: result.data.sessionId
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
                                outputContextId = request.clientPlaybackContext.output.outputContextId,
                            ),
                        )
                        // Only a stop the server acknowledged discharges the
                        // lease. An Error/NetworkError does not throw, so
                        // clearing on the call alone would drop the session on
                        // exactly the failure the lease exists to survive.
                        val stopped = (result.data.playbackPlan?.sessionId ?: result.data.sessionId)
                            ?.let { playbackRepository.stopPlayback(it) }
                        if (stopped.isStopDischarged()) leasedSessionId = null
                        ApiResult.Success(
                            VideoSessionStartV3.Terminal(validated.reason, validated.message, validated.retryable),
                        )
                    }
                    is PlaybackV3Validation.Incompatible -> {
                        leasedSessionId = validated.allocatedSessionId
                        if (!deferPublication) {
                            videoAttemptMutex.withLock {
                                activeVideoAttempt.set(null)
                            }
                        }
                        val stopped = validated.allocatedSessionId
                            ?.let { playbackRepository.stopPlayback(it) }
                        if (stopped.isStopDischarged()) leasedSessionId = null
                        ApiResult.Success(VideoSessionStartV3.ServerUpgradeRequired)
                    }
                    is PlaybackV3Validation.ReplanRequired -> {
                        leasedSessionId = validated.sessionId
                        // The plan is well-formed but names a client-side
                        // correction or transformation this build cannot
                        // execute. Preserve the allocated session and give the
                        // planner exactly one chance to route around it.
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
                        // The lease STAYS ARMED across the nested replan. Being
                        // installed as the active attempt is not the same as
                        // being findable: nothing outside this call has the id
                        // yet, and the replan below suspends — on
                        // finishContentReset, then on its own mutex — before it
                        // reaches any cancellation-safe cleanup of its own. A
                        // cancellation in that window used to leave the session
                        // installed, unknown to every caller, and running until
                        // the server expired it. The branches after the replan
                        // clear or re-arm it once its fate is decided.
                        passthroughSuppression.beginAttempt(active.planAttemptKey)
                        finishContentReset()
                        val replanResult = replanActiveVideoSession(
                            classification = validated.reason,
                            message = UNEXECUTABLE_ROUTE_MESSAGE,
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
                                        passthroughSuppression.beginAttempt(it.planAttemptKey)
                                    }
                                }
                            }
                            // The lock above decided this id's fate. Either it
                            // was abandoned — in which case the lease names it
                            // until the stop is acknowledged — or it is the
                            // published replacement and the manager owns it.
                            leasedSessionId = abandonedSessionId
                            val stopped = abandonedSessionId
                                ?.let { playbackRepository.stopPlayback(it) }
                            if (stopped.isStopDischarged()) leasedSessionId = null
                        } else if (
                            replanResult is ApiResult.Error ||
                            replanResult is ApiResult.NetworkError
                        ) {
                            val cleared = videoAttemptMutex.withLock {
                                activeVideoAttempt.compareAndSet(active, null)
                            }
                            if (cleared) {
                                // Same reasoning as the deferred branch: the CAS
                                // above removed the manager's only reference.
                                leasedSessionId = validated.sessionId
                                val stopped =
                                    playbackRepository.stopPlayback(validated.sessionId)
                                if (stopped.isStopDischarged()) leasedSessionId = null
                            }
                        } else {
                            // Replan succeeded and published through the manager.
                            // The base id is either the committed attempt or was
                            // stopped by the replan itself; either way this call
                            // is no longer answerable for it, and leaving the
                            // lease armed would have the finally stop a session
                            // that is playing.
                            leasedSessionId = null
                        }
                        replanResult
                    }
                }
                is ApiResult.Error -> result
                is ApiResult.NetworkError -> result
            }
        } finally {
            finishContentReset()
            // NonCancellable because this runs precisely when the surrounding
            // work was cancelled. Failures stay queued in orphanedSessionIds so
            // the next content reset drains them.
            leasedSessionId?.let { orphan ->
                withContext(NonCancellable) {
                    stopSessionsRetainingFailures(listOf(orphan))
                }
            }
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
        // Server-minted and opaque: the client stores it and echoes it back, it
        // never derives one.
        val planAttemptKey = plan.planAttemptKey
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
                operation = replanOperationForClassification(classification),
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
            operation = replanOperationForClassification(classification),
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
        operation: String,
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
        if (operation == SEEK_REANCHOR_V3_OPERATION || operation == SEEK_FAILURE_RECOVERY_V3_OPERATION) {
            return@withSettledVideoAttempt ApiResult.Error(
                code = 400,
                error = "reserved_playback_operation",
                message = "Seek operations must use the dedicated playback session methods.",
            )
        }
        val intent = operation in INTENT_V3_OPERATIONS
        val effectiveQuality = qualityPreference?.lowercase() ?: active.qualityPreference
        // Mirror the server's own validator so a malformed operation is caught
        // before it costs a round trip: failure recovery must name what failed,
        // and a quality change must name the rung it wants — an empty
        // preference would silently mean "auto", a different user intent than
        // the menu selection this operation models.
        if (!intent && classification.isBlank()) {
            return@withSettledVideoAttempt ApiResult.Error(
                code = 400,
                error = "invalid_replan_operation",
                message = "Failure recovery requires a failure classification.",
            )
        }
        if (operation == QUALITY_CHANGE_V3_OPERATION && effectiveQuality.isBlank()) {
            return@withSettledVideoAttempt ApiResult.Error(
                code = 400,
                error = "invalid_replan_operation",
                message = "A quality change requires a quality preference.",
            )
        }
        val currentCapabilities = capabilities ?: active.capabilities
        val currentContext = clientPlaybackContext ?: active.context
        val network = networkEvidenceProvider.snapshot()
        // A rollback can leave the player rendering its predecessor after the
        // server has committed the candidate. In that state the cursor is one
        // atomic server-facing identity tuple; mixing its plan id with the
        // rendered plan's key/history produces a request that never existed.
        val cursor = active.serverPlanCursor
        val (serverPlanId, serverPlanAttemptId, failedKey) = active.serverControlIdentity
        val priorAttemptedKeys = cursor?.attemptedPlanKeys ?: active.attemptedPlanKeys
        // An intent operation is a user's choice, not a failure: the previous
        // route stays eligible, so no attempt history is sent and the attempt
        // counter restarts. `output_route_changed` is still failure-shaped —
        // the route the client was using genuinely stopped working — but its
        // history restarts only when planning-relevant output capabilities
        // changed. Android may advance an opaque context generation during a
        // player remount; reopening failed plans for that callback creates an
        // endless direct/remux/transcode cycle.
        val materiallyChangedOutputRoute = classification == "output_route_changed" &&
            !active.context.output.hasSamePlanningRouteAs(currentContext.output)
        val invalidation = intent || (
            classification in USER_INVALIDATION_CLASSIFICATIONS &&
                (classification != "output_route_changed" || materiallyChangedOutputRoute)
        )
        val attemptedKeys = if (invalidation) {
            emptyList()
        } else {
            (priorAttemptedKeys + failedKey).distinct()
        }
        val requestAttemptCount = if (invalidation) {
            1
        } else {
            cursor?.attemptCount ?: active.attemptCount
        }
        val candidateAttemptCount = if (invalidation) 1 else requestAttemptCount + 1
        emitRouteEvent(
            PlaybackRouteEventV3(
                playbackAttemptId = active.playbackAttemptId,
                sessionId = active.sessionId,
                planId = active.plan.planId,
                planAttemptId = active.planAttemptId,
                planAttemptKey = active.planAttemptKey,
                event = if (invalidation) "plan_invalidated" else "plan_failed",
                failureClassification = classification.takeIf { it.isNotBlank() },
                appliedQuirkIds = active.plan.appliedQuirks.map { it.id },
                quirkRegistryRevision = active.plan.appliedQuirks.firstOrNull()?.registryRevision,
                outputContextId = currentContext.output.outputContextId,
                diagnostics = diagnostics + mapOfNotNull("decoder_name" to decoderName) +
                    network.asRouteDiagnostics(),
            ),
        )
        // Address the server by the plan IT holds, not the one we are rendering.
        // After a rollback those differ, and using the rendered plan sends a
        // retired failedPlanId that the server rejects with 409.
        val request = PlaybackReplanRequestV3(
            clientFeatures = playbackClientFeaturesV3(currentContext),
            operation = operation,
            playbackAttemptId = active.playbackAttemptId,
            replanRequestId = UUID.randomUUID().toString(),
            failedPlanId = serverPlanId,
            planAttemptId = serverPlanAttemptId,
            planAttemptKey = failedKey,
            attemptedPlanKeys = attemptedKeys,
            // Route changes the client made to the server's recipe on its own.
            // The server folds them into the keys it excludes; the client never
            // hashes anything itself.
            localMutations = active.localMutations,
            attemptCount = requestAttemptCount,
            qualityPreference = effectiveQuality,
            positionSeconds = positionSeconds,
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
            // Intent operations describe a user's choice, so they carry no
            // failure block at all.
            failure = if (intent) null else PlaybackFailureV3(classification, message, decoderName),
            capabilities = currentCapabilities,
            clientPlaybackContext = currentContext,
        )
        val result = playbackRepository.replanPlaybackV3(active.sessionId, request)
        var committedPlanAttemptId: String? = null
        if (result is ApiResult.Success) {
            // The server has committed this plan. Record it before any
            // validation branch: several of those return early (loop detected,
            // invalid candidate, discard) and every one of them would otherwise
            // leave the cursor addressing a plan the server has already retired.
            result.data.playbackPlan?.let { committedPlan ->
                val committedKey = committedPlan.planAttemptKey
                val nextAttemptId = UUID.randomUUID().toString()
                committedPlanAttemptId = nextAttemptId
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
                                    planAttemptId = nextAttemptId,
                                    planAttemptKey = committedKey,
                                    attemptedPlanKeys = (
                                        attemptedKeys + listOfNotNull(committedKey.takeIf(String::isNotBlank))
                                    ).distinct(),
                                    attemptCount = candidateAttemptCount,
                                ),
                            ),
                        )
                    }
            }
        }
        when (result) {
            is ApiResult.Success -> when (val validated = result.data.validateForMedia3()) {
                is PlaybackV3Validation.Playable -> {
                    val nextKey = validated.plan.planAttemptKey
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
                        currentEffectiveFileId = active.plan.effectiveMediaFileId ?: active.fileId,
                    )
                    if (subtitleMismatch != null) {
                        stopCandidateSessionIfUnowned(active.sessionId, validated.sessionId)
                        return@withSettledVideoAttempt ApiResult.Error(
                            code = 502,
                            error = "invalid_subtitle_replan_candidate",
                            message = subtitleMismatch,
                        )
                    }
                    val nextAttemptId = checkNotNull(committedPlanAttemptId)
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
                        attemptCount = candidateAttemptCount,
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
                        capabilities = currentCapabilities,
                        clientPlaybackContext = currentContext,
                    )
                    val staged = StagedVideoReplan(
                        basePlaybackAttemptId = active.playbackAttemptId,
                        baseSessionId = active.sessionId,
                        basePlanAttemptId = active.planAttemptId,
                        candidate = ready,
                        candidateSessionId = validated.sessionId,
                        outputContextId = next.context.output.outputContextId,
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
                                    UNEXECUTABLE_ROUTE_REASON,
                                    UNEXECUTABLE_ROUTE_MESSAGE,
                                    false,
                                ),
                            ),
                        )
                    } else {
                        stopCandidateSessionIfUnowned(active.sessionId, validated.sessionId)
                        ApiResult.Error(
                            code = 502,
                            error = UNEXECUTABLE_ROUTE_REASON,
                            message = UNEXECUTABLE_ROUTE_MESSAGE,
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
    ): ApiResult<VideoSessionStartV3.Ready> {
        val claim = releaseClaims.incrementAndGet()
        return try {
            commitStagedVideoReplanLocked(staged, deferPublication, claim)
        } finally {
            // Same contract as withSettledVideoAttempt: stops queued while the
            // lock was held are issued once it is released, and this awaits only
            // the ones this call queued.
            drainPendingOwnershipReleases(claim)
        }
    }

    private suspend fun commitStagedVideoReplanLocked(
        staged: StagedVideoReplan,
        deferPublication: Boolean,
        claim: Long,
    ): ApiResult<VideoSessionStartV3.Ready> = videoAttemptMutex.withLock {
        currentReleaseClaim = claim
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
        passthroughSuppression.beginAttempt(next.planAttemptKey)
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
            outputContextId = next.context.output.outputContextId,
        )

        // This is the commit point. Everything after it is best-effort,
        // non-blocking bookkeeping: callers must always receive the committed
        // candidate once manager ownership has moved to [next]. A commit that
        // reaches here has queued no release, so the caller's drain finds
        // nothing of its own and returns without waiting.
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
            predecessorSessionId?.let { rememberOrphanedSessionLocked(it) }
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

    /**
     * Fire-and-forget [abandonActiveVideoSession] on the manager's own scope.
     *
     * Callers reach this exactly when their own scope is being torn down, which
     * rules out doing the work inline. `viewModelScope.launch(NonCancellable)`
     * looks like the answer and does run, but it severs the parent link to
     * produce an untracked coroutine nothing can await or observe failures from
     * — the pattern the coroutines documentation warns against. The manager's
     * cleanup scope already outlives any screen and is what the committed-session
     * cleanup path uses, so ownership of a release belongs there rather than in
     * a ViewModel that is on its way out.
     */
    fun abandonActiveVideoSessionAsync(sessionId: String) {
        sessionCleanupScope.launch {
            runCatching { abandonActiveVideoSessionIfCurrent(sessionId) }
        }
    }

    /**
     * [abandonActiveVideoSession], but only while this session is still the one
     * the manager holds.
     *
     * The unconditional variant stops the session even when it failed to disown
     * it, and [stopSession]'s predecessor branch then clears a *newer* pending
     * publication and stops its replacement. Running abandonment on a dispatched
     * scope widens that window enough to matter: a stale result scheduled for
     * release can land after a newer deferred publication has installed itself
     * with this id as its predecessor, and tear the new one down.
     *
     * When ownership has already moved on, the id is recorded as an orphan
     * instead. The drain stops it with a plain repository call that cannot
     * disturb whoever owns playback now.
     */
    suspend fun abandonActiveVideoSessionIfCurrent(sessionId: String): Boolean {
        val disowned = videoAttemptMutex.withLock {
            val active = activeVideoAttempt.get()
            if (active?.sessionId != sessionId) {
                rememberOrphanedSessionLocked(sessionId)
                false
            } else {
                activeVideoAttempt.compareAndSet(active, null)
            }
        }
        if (!disowned) return false
        stopSession(sessionId)
        return true
    }

    /**
     * Drops an unpublished candidate only while the manager still owns that
     * exact server plan. This is stricter than session-id ownership because an
     * in-place replan legitimately reuses the same session id; a late UI
     * transaction must never tear down the newer plan that superseded it.
     */
    suspend fun abandonActiveVideoPlanIfCurrent(sessionId: String, planId: String): Boolean {
        val disowned = videoAttemptMutex.withLock {
            val active = activeVideoAttempt.get()
            if (active?.sessionId != sessionId || active.plan.planId != planId) {
                false
            } else {
                activeVideoAttempt.compareAndSet(active, null)
            }
        }
        if (!disowned) return false
        stopSession(sessionId)
        return true
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
            passthroughSuppression.beginAttempt(it.planAttemptKey)
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
        rememberOrphanedSessionLocked(oldSessionId)
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
                    if (result.isStopDischarged()) {
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
        // Claim the ids under the lock, marking them in flight in the same
        // critical section. Filtering alone only holds for the instant of the
        // snapshot — the stops below run unlocked, and without a marker another
        // drain could select the same session and stop it concurrently.
        val orphanIds = videoAttemptMutex.withLock {
            val live = setOfNotNull(activeVideoAttempt.get()?.sessionId)
            orphanedSessionIds.filterNot {
                it in protectedSessionIds ||
                    it in live ||
                    // A queued release already owns this one, and its own drain
                    // will remove it on discharge.
                    it in releasesInFlight ||
                    pendingOwnershipReleases.any { pending -> pending.second == it }
            }.onEach { markReleaseInFlightLocked(it) }
        }
        orphanIds.forEach { sessionId ->
            try {
                val result = try {
                    playbackRepository.stopPlayback(sessionId)
                } catch (_: CancellationException) {
                    return@forEach
                } catch (_: Throwable) {
                    null
                }
                if (result.isStopDischarged()) {
                    videoAttemptMutex.withLock {
                        orphanedSessionIds -= sessionId
                    }
                }
            } finally {
                // Including the cancellation return above: a marker left behind
                // would hide this session from every future drain.
                withContext(NonCancellable) {
                    videoAttemptMutex.withLock {
                        clearReleaseInFlightLocked(sessionId)
                        // Re-trim here too. Trimming skips in-flight ids, so
                        // whichever release path clears the last marker has to
                        // re-apply the cap — otherwise a burst drained from here
                        // leaves the ledger over its bound until some unrelated
                        // future orphan happens to trigger a trim.
                        trimOrphanedSessionsLocked()
                    }
                }
            }
        }
    }

    private suspend fun stopSessionsRetainingFailures(sessionIds: Collection<String>) {
        val uniqueSessionIds = sessionIds.distinct()
        if (uniqueSessionIds.isEmpty()) return
        videoAttemptMutex.withLock {
            uniqueSessionIds.forEach { rememberOrphanedSessionLocked(it) }
        }
        uniqueSessionIds.forEach { sessionId ->
            val result = try {
                playbackRepository.stopPlayback(sessionId)
            } catch (_: Throwable) {
                null
            }
            if (result.isStopDischarged()) {
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
            }?.also { rememberOrphanedSessionLocked(it) }
        }
        if (candidateSessionId == null) return

        withContext(NonCancellable) {
            val result = try {
                playbackRepository.stopPlayback(candidateSessionId)
            } catch (_: Throwable) {
                null
            }
            if (result.isStopDischarged()) {
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

    /**
     * Stops a session this caller is discarding, keeping a record of it until
     * the server confirms it is gone. Caller must hold [videoAttemptMutex].
     *
     * Some callers reach here having already cleared `activeVideoAttempt` or
     * removed the staged handle, and for those the id exists nowhere else in the
     * process from that moment until the server replies. A bare suspending stop
     * there is cancellable — and these run from ViewModel recovery jobs that
     * exit, content replacement and teardown all cancel — so the id would simply
     * be lost and the transcode would hold its stream slot until the server's
     * own expiry. Registering first makes the worst case a retry on the next
     * content reset rather than an orphan nobody remembers. Callers that have
     * not given up ownership (a rejected validation candidate, say) are
     * registered on the same path because it costs nothing.
     */
    private fun stopRetainingFailureLocked(sessionId: String) {
        // The stop is queued rather than issued. Requests time out at 60s, and
        // awaiting one while holding videoAttemptMutex would serialise every
        // start, replan, content reset and staged commit behind a dying
        // session's teardown. [drainPendingOwnershipReleases] runs it once the
        // lock is released, still awaited by the caller that queued it.
        //
        // Tagged with the claim of the lock holder that queued it. Without that
        // tag one shared queue lets any concurrent drain take another caller's
        // work: the queuing caller then returns before its own stop ran, while
        // an unrelated caller — which queued nothing — blocks for a full network
        // timeout on someone else's teardown.
        //
        // Queued BEFORE registering, because rememberOrphanedSessionLocked
        // trims and trimming protects only ids already queued or in flight —
        // so with a full ledger of protected entries this session could be the
        // one evictable entry and get dropped before its stop had even started.
        pendingOwnershipReleases += currentReleaseClaim to sessionId
        // Registration is what makes the id survivable: if the queued stop
        // fails, this is the record the next content reset retries from.
        rememberOrphanedSessionLocked(sessionId)
    }

    /**
     * Issues the stops [stopRetainingFailureLocked] queued under [claim]. Must
     * be called with [videoAttemptMutex] NOT held.
     *
     * NonCancellable throughout: these sessions are already registered as
     * orphans and unreferenced anywhere else, and the callers reaching here are
     * frequently being cancelled. Anything that fails stays registered for the
     * next content reset to drain — unless the ledger is at its cap and the
     * entry has already been evicted, in which case that session falls back to
     * the server's own expiry.
     */
    private suspend fun drainPendingOwnershipReleases(claim: Long) {
        withContext(NonCancellable) {
            while (true) {
                val sessionId = videoAttemptMutex.withLock {
                    val index = pendingOwnershipReleases.indexOfFirst { it.first == claim }
                    if (index < 0) {
                        null
                    } else {
                        pendingOwnershipReleases.removeAt(index).second.also {
                            // Visible to drainOrphanedSessions for as long as the
                            // stop is in flight, so a concurrent content reset
                            // does not issue a second stop for the same session.
                            markReleaseInFlightLocked(it)
                        }
                    }
                } ?: return@withContext
                val result = try {
                    playbackRepository.stopPlayback(sessionId)
                } catch (_: Throwable) {
                    null
                }
                videoAttemptMutex.withLock {
                    clearReleaseInFlightLocked(sessionId)
                    if (result.isStopDischarged()) {
                        orphanedSessionIds -= sessionId
                    }
                    // The cap can only skip entries that were mid-release, so
                    // re-apply it once one finishes; otherwise a burst of
                    // concurrent releases leaves the ledger permanently over
                    // its bound with nothing to bring it back down.
                    trimOrphanedSessionsLocked()
                }
            }
        }
    }

    /**
     * Records a session whose stop has not been confirmed, oldest evicted first.
     *
     * The ledger has to be bounded. Only a discharged stop removes an entry, so
     * a server that keeps failing this call — while playback keeps producing new
     * sessions — would otherwise grow it without limit and make every later
     * content reset retry an ever-larger collection. Dropping the oldest entry
     * costs that session its explicit stop and falls back to the server's own
     * expiry, which is exactly what happens today when a stop never succeeds.
     */
    private fun markReleaseInFlightLocked(sessionId: String) {
        releasesInFlight[sessionId] = (releasesInFlight[sessionId] ?: 0) + 1
    }

    private fun clearReleaseInFlightLocked(sessionId: String) {
        val remaining = (releasesInFlight[sessionId] ?: 0) - 1
        if (remaining > 0) releasesInFlight[sessionId] = remaining else releasesInFlight -= sessionId
    }

    private fun rememberOrphanedSessionLocked(sessionId: String) {
        orphanedSessionIds += sessionId
        trimOrphanedSessionsLocked()
    }

    private fun trimOrphanedSessionsLocked() {
        while (orphanedSessionIds.size > MAX_RETAINED_ORPHANED_SESSIONS) {
            // Never evict an id whose release this manager is tracking — the
            // queued and in-flight sets. Committed-session cleanup and the
            // direct retaining-stop helpers issue unmarked stops, so this is not
            // protection against every release in flight, only the ones the
            // queue knows about.
            // When everything over the cap is mid-release there is nothing
            // safe to drop, so the set stays over its bound until one of those
            // releases completes and re-runs this.
            val oldest = orphanedSessionIds.firstOrNull {
                it !in releasesInFlight &&
                    pendingOwnershipReleases.none { pending -> pending.second == it }
            } ?: break
            orphanedSessionIds -= oldest
        }
    }

    /**
     * True once the server owes us nothing more for this session.
     *
     * A typed session-missing 404 counts: the session is already gone, and
     * treating that as a failure would keep the id in [orphanedSessionIds]
     * forever and retry it on every single drain. A bare 404 does not — routing,
     * proxy and compatibility 404s prove nothing about the session, so this uses
     * the same predicate the rest of the manager uses for absence.
     */
    private fun ApiResult<Unit>?.isStopDischarged(): Boolean =
        this is ApiResult.Success || this?.isPlaybackSessionMissingError() == true

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
        stopRetainingFailureLocked(candidateSessionId)
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
            // Ownership was cleared immediately above, so this is the same
            // register-before-stop case as the candidates below.
            stopRetainingFailureLocked(activeSessionId)
        }
        candidateSessionIds.filterNotNull().distinct()
            .filter { it != activeSessionId }
            .forEach { stopCandidateSessionIfUnowned(activeSessionId = null, it) }
    }

    private fun subtitleCandidateMismatch(
        requested: PlaybackTrackIdentityV3?,
        candidate: PlaybackPlanV3,
        currentEffectiveFileId: Int,
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
        val candidateEffectiveFileId = candidate.effectiveMediaFileId
            ?: candidate.requestedMediaFileId
            ?: currentEffectiveFileId
        if (candidateEffectiveFileId == currentEffectiveFileId) {
            if (selected?.id != requested.id ||
                (selected.index != null && selected.index != requested.index) ||
                subtitle.trackId != requested.id
            ) {
                return "The candidate did not select the exact requested subtitle track."
            }
        } else if (selected == null || subtitle.trackId != selected.id) {
            // Edition adaptation may remap both the stable id and ordinal. The
            // candidate inventory has already been validated, so require only
            // that its own selected identity and subtitle decision agree.
            return "The adapted candidate did not preserve a selected subtitle identity."
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
            clientFeatures = playbackClientFeaturesV3(active.context),
            operation = SEEK_REANCHOR_V3_OPERATION,
            playbackAttemptId = active.playbackAttemptId,
            replanRequestId = UUID.randomUUID().toString(),
            failedPlanId = active.plan.planId,
            planAttemptId = active.planAttemptId,
            planAttemptKey = active.planAttemptKey,
            attemptedPlanKeys = active.attemptedPlanKeys,
            localMutations = active.localMutations,
            attemptCount = active.attemptCount,
            qualityPreference = active.qualityPreference,
            positionSeconds = positionSeconds,
            metered = active.networkEvidence.metered,
            bandwidthEstimateKbps = active.networkEvidence.bandwidthEstimateKbps,
            bandwidthCapKbps = active.bandwidthCapKbps,
            selectedTracks = active.plan.selectedTracks,
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
                outputContextId = active.context.output.outputContextId,
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
                                outputContextId = next.context.output.outputContextId,
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
                                capabilities = next.capabilities,
                                clientPlaybackContext = next.context,
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
                        // Re-anchoring is not allowed to change the route, so a
                        // plan this client cannot execute means the server moved
                        // it off the route already playing.
                        "$UNEXECUTABLE_ROUTE_MESSAGE Re-anchoring may not change the playback route.",
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
        if (!candidate.hasSameSeekReanchorBaseRoute(active.plan)) {
            return "The server changed the playback route during seek re-anchoring."
        }
        return null
    }

    /**
     * Mirrors the server's own re-anchor validator: a re-anchored plan may move
     * the timeline, rotate signed URLs, and refresh the transport, but it may
     * not change the route it describes.
     *
     * The attempt keys compared here are server-minted, so an unchanged pair is
     * the server's own assertion that the recipe survived. The field comparison
     * that follows is the client's independent check of what it actually
     * renders, and the delivery class is what the two sides negotiate over —
     * there is no engine name in the neutral contract to compare.
     */
    private fun PlaybackPlanV3.hasSameSeekReanchorBaseRoute(current: PlaybackPlanV3): Boolean =
        planAttemptKey == current.planAttemptKey &&
            delivery == current.delivery &&
            stream.mimeType == current.stream.mimeType &&
            stream.headerRefresh == current.stream.headerRefresh &&
            effectiveRecipe == current.effectiveRecipe &&
            claims == current.claims &&
            subtitle.mode == current.subtitle.mode &&
            subtitle.trackId == current.subtitle.trackId &&
            subtitle.artifact?.mimeType == current.subtitle.artifact?.mimeType &&
            subtitle.artifact?.format == current.subtitle.artifact?.format &&
            subtitleFidelityPolicy == current.subtitleFidelityPolicy &&
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
        val cursor = active.serverPlanCursor
        val (serverPlanId, serverPlanAttemptId, failedKey) = active.serverControlIdentity
        val requestAttemptCount = cursor?.attemptCount ?: active.attemptCount
        val attemptedKeys = (
            (cursor?.attemptedPlanKeys ?: active.attemptedPlanKeys) + failedKey
        ).distinct()
        val nextAttemptCount = requestAttemptCount + 1
        val nextAttemptId = UUID.randomUUID().toString()
        val request = PlaybackReplanRequestV3(
            clientFeatures = playbackClientFeaturesV3(active.context),
            operation = SEEK_FAILURE_RECOVERY_V3_OPERATION,
            playbackAttemptId = active.playbackAttemptId,
            replanRequestId = UUID.randomUUID().toString(),
            failedPlanId = serverPlanId,
            planAttemptId = serverPlanAttemptId,
            planAttemptKey = failedKey,
            attemptedPlanKeys = attemptedKeys,
            localMutations = active.localMutations,
            attemptCount = requestAttemptCount,
            qualityPreference = active.qualityPreference,
            positionSeconds = positionSeconds,
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
                outputContextId = active.context.output.outputContextId,
                diagnostics = diagnostics + mapOfNotNull("decoder_name" to decoderName) +
                    network.asRouteDiagnostics() +
                    ("seek_recovery_position_seconds" to positionSeconds.toString()),
            ),
        )

        when (val result = playbackRepository.replanPlaybackV3(active.sessionId, request)) {
            is ApiResult.Success -> {
                result.data.playbackPlan?.let { committedPlan ->
                    val committedKey = committedPlan.planAttemptKey
                    activeVideoAttempt.get()
                        ?.takeIf { it.sessionId == active.sessionId }
                        ?.let { live ->
                            activeVideoAttempt.compareAndSet(
                                live,
                                live.copy(
                                    serverPlanCursor = ServerPlanCursor(
                                        planId = committedPlan.planId,
                                        planAttemptId = nextAttemptId,
                                        planAttemptKey = committedKey,
                                        attemptedPlanKeys = (
                                            attemptedKeys +
                                                listOfNotNull(committedKey.takeIf(String::isNotBlank))
                                        ).distinct(),
                                        attemptCount = nextAttemptCount,
                                    ),
                                ),
                            )
                        }
                }
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
                        val nextKey = validated.plan.planAttemptKey
                        if (nextKey in attemptedKeys) {
                            return@withSettledVideoAttempt ApiResult.Success(
                                VideoSessionStartV3.Terminal(
                                    reason = "replan_loop_detected",
                                    message = "The server returned a seek-recovery route that already failed.",
                                    retryable = false,
                                ),
                            )
                        }
                        val next = adoptSeekRecoveryPlan(
                            expected = active,
                            plan = validated.plan,
                            serverFeatures = result.data.serverFeatures,
                            planAttemptId = nextAttemptId,
                            planAttemptKey = nextKey,
                            attemptedPlanKeys = attemptedKeys,
                            attemptCount = nextAttemptCount,
                        )
                        if (next == null) {
                            return@withSettledVideoAttempt ApiResult.Error(
                                code = 409,
                                error = "playback_attempt_changed",
                                message = "The active playback attempt changed during seek recovery.",
                            )
                        }
                        passthroughSuppression.beginAttempt(nextKey)
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
                                outputContextId = next.context.output.outputContextId,
                            ),
                        )
                        ApiResult.Success(
                            VideoSessionStartV3.Ready(
                                session = next.plan.toSessionResponse(next.sessionId, next.profileId, next.fileId),
                                plan = next.plan,
                                playbackAttemptId = next.playbackAttemptId,
                                planAttemptId = next.planAttemptId,
                                planAttemptKey = next.planAttemptKey,
                                capabilities = next.capabilities,
                                clientPlaybackContext = next.context,
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
                        UNEXECUTABLE_ROUTE_MESSAGE,
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
        attemptCount: Int,
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
            attemptedPlanKeys = (attemptedPlanKeys + planAttemptKey).distinct(),
            attemptCount = attemptCount,
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
                outputContextId = active.context.output.outputContextId,
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
                outputContextId = active.context.output.outputContextId,
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
            ?: active.plan.subtitle.inventory
                .takeIf { kind == "subtitle" }
                ?.singleOrNull { it.combinedIndex == index }
                ?.let { PlaybackTrackIdentityV3(it.trackId, index) }
            ?: PlaybackTrackIdentityV3(stableTrackId(effectiveFileId, kind, index), index)
    }

    /**
     * Records a client-applied route mutation on the active attempt.
     *
     * The mutation is NOT hashed here. Attempt keys are server-minted under the
     * neutral v3 contract, so the client records what it changed locally and
     * echoes it in `local_mutations` on the next replan; the server folds it
     * into the keys it excludes. Returns the new attempt, or null when there is
     * no active attempt, the mutation is already recorded, or another thread
     * won the CAS.
     */
    private fun recordLocalMutation(
        mutation: String,
        refreshPassthroughSuppression: Boolean,
        alreadyRecorded: (List<String>) -> Boolean,
    ): ActiveVideoAttempt? {
        val active = activeVideoAttempt.get() ?: return null
        if (alreadyRecorded(active.localMutations)) return null
        val next = active.copy(localMutations = active.localMutations + mutation)
        if (!activeVideoAttempt.compareAndSet(active, next)) return null
        // The suppression registry only equality-compares an opaque scope token,
        // so a locally-derived one is sufficient — and necessary, because the
        // server-minted key does not change when the client mutates its own
        // route.
        if (refreshPassthroughSuppression) {
            passthroughSuppression.beginAttempt(
                "${next.planAttemptKey}#${next.localMutations.joinToString("|")}",
            )
        }
        return next
    }

    fun trySingleLocalPcmRetry(mime: String, channels: Int): Boolean {
        val mutation = "pcm:${mime.lowercase()}:${channels.coerceAtLeast(0)}"
        recordLocalMutation(mutation, refreshPassthroughSuppression = true) { mutations ->
            mutations.any { it.startsWith("pcm:") }
        }
            ?: return false
        return passthroughSuppression.suppressForSinglePcmRetry(mime, channels)
    }

    fun recordTransportReopen(): Boolean =
        recordLocalMutation("transport_reopen", refreshPassthroughSuppression = false) { mutations ->
            "transport_reopen" in mutations
        } != null

    companion object {
        private const val TAG = "PlaybackSessionMgr"
        private const val COMMITTED_SESSION_CLEANUP_ATTEMPTS = 2

        /**
         * Ceiling on unconfirmed orphaned sessions kept for retry.
         *
         * Generous relative to how many sessions one viewing session produces,
         * so it only bites when stops are persistently failing — the case where
         * retrying an unbounded backlog on every content reset is pure cost.
         */
        private const val MAX_RETAINED_ORPHANED_SESSIONS = 64

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
            "subtitle_inventory_changed",
        )

        /**
         * The v3 replan operation a classification means.
         *
         * Track and quality changes are user intents, not failures, and the
         * contract now has operations that say so — so the classification the
         * player already computes selects the operation instead of every call
         * site having to name both. `output_route_changed` deliberately stays
         * failure recovery: the route the client was using genuinely stopped
         * working, and the server should exclude it.
         */
        private fun replanOperationForClassification(classification: String): String = when (classification) {
            "audio_track_changed", "subtitle_track_changed", "subtitle_inventory_changed" ->
                TRACK_CHANGE_V3_OPERATION
            "quality_changed" -> QUALITY_CHANGE_V3_OPERATION
            else -> FAILURE_RECOVERY_V3_OPERATION
        }

        /**
         * The server returned a structurally valid plan that names a client-side
         * runtime correction or transformation this build cannot execute.
         *
         * Not a protocol mismatch: the neutral v3 contract has no engine field
         * for the server to get wrong, so the only way a plan is unexecutable
         * here is a capability this client does not have.
         */
        internal const val UNEXECUTABLE_ROUTE_REASON = "unexecutable_client_route"
        internal const val UNEXECUTABLE_ROUTE_MESSAGE =
            "The server returned a playback route this client cannot execute."
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
                        rememberOrphanedSessionLocked(sessionId)
                    }
                    try {
                        result = playbackRepository.stopPlayback(sessionId)
                        if (result.isStopDischarged()) {
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

    /** Returns the current access token for stream authentication. */
    suspend fun getAccessToken(): String? = tokenManager.getAccessToken()

    /** Returns the server base URL for resolving relative stream URLs. */
    suspend fun getServerUrl(): String = tokenManager.getServerUrl()

}

internal fun ApiResult<*>.isPlaybackSessionMissingError(): Boolean {
    val error = this as? ApiResult.Error ?: return false
    return error.code == 404 &&
        (error.error == "playback_session_not_found" || error.message == "Playback session not found")
}
