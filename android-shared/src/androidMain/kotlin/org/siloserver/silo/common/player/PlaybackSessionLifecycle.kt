package org.siloserver.silo.common.player

import android.util.Log
import org.siloserver.silo.common.diagnostics.DiagnosticsPlaybackLogger
import org.siloserver.silo.common.diagnostics.DiagnosticsPlaybackSessionRecording
import org.siloserver.silo.common.diagnostics.DiagnosticsPlaybackSessionRecorder
import org.siloserver.silo.model.personal.SyncProgressItem
import org.siloserver.silo.model.playback.ClientCodecCapabilities
import org.siloserver.silo.model.playback.ClientPlaybackContext
import org.siloserver.silo.model.playback.PlayMethod
import org.siloserver.silo.model.playback.PlaybackSessionResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.HealthApi
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.repository.ProfileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Wraps [PlaybackSessionManager] with a unified state machine that handles
 * both 404-session-missing recovery (consolidated from duplicate VM code)
 * and server-outage recovery via `/api/v1/health` probes with exponential
 * backoff (1s -> 8s, 90s timeout — mirrors iOS `PlayerViewModel`).
 *
 * Phone and TV ViewModels were each open-coding the same 404 recovery flow
 * in `recoverMissingPlaybackSession` / `syncProgressSnapshot`. Both now
 * collapse to a single observer of [state] and [notice].
 *
 * Lifecycle:
 *   start(params) -> Loading -> Active(session) | Failed(message)
 *   reportPosition(...) -> debounced 10s flush via `sessionManager`
 *     - 404 session_not_found  -> sync snapshot, re-invoke start with override
 *     - NetworkError           -> Reconnecting + health-probe loop
 *   stop() -> Idle (also flushes one final progress snapshot)
 */
class PlaybackSessionLifecycle(
    private val sessionManager: PlaybackSessionManager,
    private val profileRepository: ProfileRepository,
    private val healthApi: HealthApi,
    private val personalDataRepository: PersonalDataRepository,
    private val scope: CoroutineScope,
    private val playbackSessions: DiagnosticsPlaybackSessionRecorder = DiagnosticsPlaybackSessionRecorder.None,
) {

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _notice = MutableStateFlow<PlayerNotice?>(null)
    val notice: StateFlow<PlayerNotice?> = _notice.asStateFlow()

    private val _missingSessionEvents = MutableSharedFlow<Double>(extraBufferCapacity = 1)
    val missingSessionEvents: SharedFlow<Double> = _missingSessionEvents.asSharedFlow()

    /**
     * Mutex protects the small set of mutable transitions we make from
     * different coroutine paths (start, recovery, outage). State transitions
     * still publish through StateFlow which is itself thread-safe — the lock
     * just keeps `lastReportedPosition` and `lastStartParams` and the various
     * `Job` references in agreement.
     */
    private val mutex = Mutex()

    @Volatile private var lastStartParams: StartParams? = null
    @Volatile private var lastReportedPosition: Double? = null
    @Volatile private var lastReportedDuration: Double = 0.0
    @Volatile private var recoveringFromMissingSession: String? = null
    @Volatile private var flushProgressOnStop: Boolean = true
    @Volatile private var stopActiveSessionOnStop: Boolean = true
    @Volatile private var renewMissingSessionWithLegacyStart: Boolean = true
    @Volatile private var diagnosticsRecording: DiagnosticsPlaybackSessionRecording =
        DiagnosticsPlaybackSessionRecording.None

    private var reporterJob: Job? = null
    private var recoveryJob: Job? = null
    private var outageJob: Job? = null
    private val pendingStopLock = Any()
    private var pendingStopJob: Job? = null

    /** Session [pendingStopJob] is stopping; guarded by `pendingStopLock`. */
    private var pendingStopSessionId: String? = null
    private val externalFinalizationLock = Any()
    private val pendingExternalFinalizations = mutableMapOf<String, Job>()

    private data class ActiveSessionSnapshot(
        val state: SessionState,
        val notice: PlayerNotice?,
        val lastStartParams: StartParams?,
        val lastReportedPosition: Double?,
        val lastReportedDuration: Double,
        val lastIsPaused: Boolean,
        val recoveringFromMissingSession: String?,
        val flushProgressOnStop: Boolean,
        val stopActiveSessionOnStop: Boolean,
        val renewMissingSessionWithLegacyStart: Boolean,
        val diagnosticsRecording: DiagnosticsPlaybackSessionRecording,
        val reporterWasActive: Boolean,
    )

    private data class PendingActiveSessionPublication(
        val replacementSessionId: String,
        val predecessor: ActiveSessionSnapshot,
    )

    private var pendingActiveSessionPublication: PendingActiveSessionPublication? = null

    /**
     * The session this lifecycle owns, independent of what it is presenting.
     *
     * [SessionState] carries a session id only while Active, so any guard that
     * reads state alone is blind exactly when it matters. During Reconnecting,
     * Loading or Failed a stale deferred stop finds no id, falls through, and
     * cancels the reconnect for a session it has no business touching — the
     * banner vanishes with nothing replacing it and progress reporting for that
     * episode is dead for the rest of playback.
     */
    @Volatile
    private var lastAdoptedSessionId: String? = null

    /**
     * Bumped by every [stop] that actually tears down.
     *
     * `start()` runs its API call outside the mutex, and during that window
     * `_state` is Loading and [lastAdoptedSessionId] is null — so the ownership
     * guard in [stop] finds no id to compare and tears down regardless. Compare
     * this instead: an unchanged value at publication time proves no stop ran
     * while the start was in flight.
     */
    @Volatile
    private var stopEpoch: Long = 0L

    // ---- Public API ---------------------------------------------------------

    /**
     * Starts a new playback session. Resolves to [SessionState.Active] on
     * success or [SessionState.Failed] on profile-id absence or session API
     * failure (Error or NetworkError).
     */
    suspend fun start(params: StartParams): SessionState {
        awaitPendingStop()
        DiagnosticsPlaybackLogger.sessionEvent("session start requested")
        // New start cancels any in-flight recovery / outage probing, by design:
        // this is the explicit "user/code wants a fresh session now" path.
        cancelRecoveryJobs()
        mutex.withLock {
            pendingActiveSessionPublication = null
        }
        val recording = playbackSessions.recording()
        diagnosticsRecording = recording
        return startInternal(params, recording)
    }

    /**
     * Hands the lifecycle a session that the caller already started. By
     * default, the lifecycle also owns progress reporting, recovery, final
     * progress flush, and stop. Callers that have not migrated those paths yet
     * can adopt passively without creating a second playback session.
     */
    suspend fun adoptActiveSession(
        params: StartParams,
        session: PlaybackSessionResponse,
        manageProgress: Boolean = true,
        stopSessionOnStop: Boolean = true,
        renewMissingSessionWithLegacyStart: Boolean = true,
        deferPublication: Boolean = false,
    ) {
        awaitPendingStop()
        adoptActiveSessionIfCurrent(
            params = params,
            session = session,
            manageProgress = manageProgress,
            stopSessionOnStop = stopSessionOnStop,
            renewMissingSessionWithLegacyStart = renewMissingSessionWithLegacyStart,
            deferPublication = deferPublication,
            isCurrent = { true },
        )
    }

    /**
     * Waits for an older screen's queued teardown before granting ownership to
     * a new external start, then snapshots the epoch under the lifecycle lock.
     */
    suspend fun acquireOwnershipEpoch(): Long {
        awaitPendingStop()
        return mutex.withLock { stopEpoch }
    }

    /**
     * Atomically adopts an already-started session only while its caller still
     * owns the surrounding transaction. The predicate is evaluated inside the
     * lifecycle mutex immediately before any lifecycle state is changed.
     */
    suspend fun adoptActiveSessionIfCurrent(
        params: StartParams,
        session: PlaybackSessionResponse,
        manageProgress: Boolean = true,
        stopSessionOnStop: Boolean = true,
        renewMissingSessionWithLegacyStart: Boolean = true,
        deferPublication: Boolean = false,
        isCurrent: () -> Boolean,
    ): Boolean {
        val diagnosticsRecording = playbackSessions.recording()
        return mutex.withLock {
            if (!isCurrent()) return@withLock false
            val predecessor = if (deferPublication) {
                pendingActiveSessionPublication?.predecessor
                    ?: captureActiveSessionSnapshot()
            } else {
                null
            }
            cancelRecoveryJobs()
            reporterJob?.cancel()
            reporterJob = null
            _notice.value = null
            lastStartParams = params
            lastReportedPosition = params.startPosition ?: session.position
            lastReportedDuration = session.durationSeconds ?: 0.0
            lastIsPaused = session.isPaused
            recoveringFromMissingSession = null
            flushProgressOnStop = manageProgress
            stopActiveSessionOnStop = stopSessionOnStop
            this.renewMissingSessionWithLegacyStart = renewMissingSessionWithLegacyStart
            this.diagnosticsRecording = diagnosticsRecording
            diagnosticsRecording.record(session.sessionId)
            lastAdoptedSessionId = session.sessionId
            _state.value = SessionState.Active(session)
            if (manageProgress) {
                startProgressReporter()
            }
            pendingActiveSessionPublication = predecessor?.let {
                PendingActiveSessionPublication(
                    replacementSessionId = session.sessionId,
                    predecessor = it,
                )
            }
            true
        }
    }

    /**
     * Adopts an externally-started session only if no teardown has happened
     * since [expectedOwnershipEpoch] was captured. Rejected or canceled
     * candidates are closed here so the server stream cannot be orphaned.
     */
    suspend fun adoptActiveSessionIfCurrent(
        params: StartParams,
        session: PlaybackSessionResponse,
        manageProgress: Boolean = true,
        stopSessionOnStop: Boolean = true,
        renewMissingSessionWithLegacyStart: Boolean = true,
        deferPublication: Boolean = false,
        expectedOwnershipEpoch: Long,
    ): Boolean = try {
        currentCoroutineContext().ensureActive()
        val adopted = adoptActiveSessionIfCurrent(
            params = params,
            session = session,
            manageProgress = manageProgress,
            stopSessionOnStop = stopSessionOnStop,
            renewMissingSessionWithLegacyStart = renewMissingSessionWithLegacyStart,
            deferPublication = deferPublication,
            isCurrent = { stopEpoch == expectedOwnershipEpoch },
        )
        if (!adopted) {
            sessionManager.stopSession(session.sessionId)
        }
        adopted
    } catch (cancellation: CancellationException) {
        withContext(NonCancellable) {
            sessionManager.stopSession(session.sessionId)
        }
        throw cancellation
    }

    /**
     * Settles manager and lifecycle publication as one lifecycle-locked
     * transition. The manager callback runs only after exact replacement
     * ownership has been verified, and no lifecycle reset/stop/adoption can
     * enter between manager settlement and the matching lifecycle transition.
     *
     * A false callback result leaves the pending lifecycle publication intact
     * so the caller can retry or choose the opposite settlement.
     */
    suspend fun settlePendingPublicationIfCurrent(
        sessionId: String,
        confirm: Boolean,
        settleManager: suspend () -> Boolean,
    ): Boolean = mutex.withLock {
        val pending = pendingActiveSessionPublication
            ?.takeIf { it.replacementSessionId == sessionId }
            ?: return@withLock false
        if ((_state.value as? SessionState.Active)?.session?.sessionId != sessionId) {
            return@withLock false
        }
        if (!settleManager()) return@withLock false

        pendingActiveSessionPublication = null
        if (!confirm) {
            cancelRecoveryJobs()
            reporterJob?.cancel()
            reporterJob = null
            restoreActiveSessionSnapshot(pending.predecessor)
        }
        true
    }

    /**
     * Rolls back whichever deferred replacement is currently pending without
     * requiring the caller to first observe its session id. Exact ownership is
     * resolved under the lifecycle mutex and supplied to [settleManager], so a
     * fresh content load cannot race a stale, caller-cached replacement id.
     *
     * No pending publication is already settled and therefore succeeds. A
     * manager failure leaves the replacement and its predecessor snapshot
     * intact for an exact retry.
     */
    suspend fun rollbackCurrentPendingPublication(
        settleManager: suspend (sessionId: String) -> Boolean,
    ): Boolean = mutex.withLock {
        val pending = pendingActiveSessionPublication ?: return@withLock true
        val sessionId = pending.replacementSessionId
        if ((_state.value as? SessionState.Active)?.session?.sessionId != sessionId) {
            return@withLock false
        }
        if (!settleManager(sessionId)) return@withLock false

        pendingActiveSessionPublication = null
        cancelRecoveryJobs()
        reporterJob?.cancel()
        reporterJob = null
        restoreActiveSessionSnapshot(pending.predecessor)
        true
    }

    suspend fun confirmActiveSessionPublication(sessionId: String): Boolean =
        settlePendingPublicationIfCurrent(
            sessionId = sessionId,
            confirm = true,
            settleManager = { true },
        )

    suspend fun rollbackUnpublishedActiveSession(sessionId: String): Boolean =
        settlePendingPublicationIfCurrent(
            sessionId = sessionId,
            confirm = false,
            settleManager = { true },
        )

    private fun captureActiveSessionSnapshot(): ActiveSessionSnapshot =
        ActiveSessionSnapshot(
            state = _state.value,
            notice = _notice.value,
            lastStartParams = lastStartParams,
            lastReportedPosition = lastReportedPosition,
            lastReportedDuration = lastReportedDuration,
            lastIsPaused = lastIsPaused,
            recoveringFromMissingSession = recoveringFromMissingSession,
            flushProgressOnStop = flushProgressOnStop,
            stopActiveSessionOnStop = stopActiveSessionOnStop,
            renewMissingSessionWithLegacyStart = renewMissingSessionWithLegacyStart,
            diagnosticsRecording = diagnosticsRecording,
            reporterWasActive = reporterJob?.isActive == true,
        )

    private fun restoreActiveSessionSnapshot(
        snapshot: ActiveSessionSnapshot,
        restartReporter: Boolean = true,
    ) {
        lastStartParams = snapshot.lastStartParams
        lastReportedPosition = snapshot.lastReportedPosition
        lastReportedDuration = snapshot.lastReportedDuration
        lastIsPaused = snapshot.lastIsPaused
        recoveringFromMissingSession = snapshot.recoveringFromMissingSession
        flushProgressOnStop = snapshot.flushProgressOnStop
        stopActiveSessionOnStop = snapshot.stopActiveSessionOnStop
        renewMissingSessionWithLegacyStart = snapshot.renewMissingSessionWithLegacyStart
        diagnosticsRecording = snapshot.diagnosticsRecording
        _notice.value = snapshot.notice
        // A rollback to the predecessor hands ownership back to that session.
        (snapshot.state as? SessionState.Active)?.let { lastAdoptedSessionId = it.session.sessionId }
        _state.value = snapshot.state
        if (
            restartReporter &&
            snapshot.reporterWasActive &&
            snapshot.state is SessionState.Active
        ) {
            startProgressReporter()
        }
    }

    private suspend fun startInternal(
        params: StartParams,
        diagnosticsRecording: DiagnosticsPlaybackSessionRecording,
        alreadyLocked: Boolean = false,
    ): SessionState {
        // `handleSessionMissing` calls this from inside the lifecycle mutex, and
        // Mutex is not reentrant, so locking is the caller's choice.
        suspend fun <T> guarded(block: suspend () -> T): T =
            if (alreadyLocked) block() else mutex.withLock { block() }

        // Read under the lock together with the state we are about to publish:
        // `stop()` bumps this, so an unchanged value at publication time proves
        // no teardown ran while the start API call was in flight.
        val epochAtStart = guarded {
            _notice.value = null
            // Starting fresh: the previous session is no longer ours. start() has
            // already awaited any pending stop, so nothing is left to guard.
            lastAdoptedSessionId = null
            _state.value = SessionState.Loading
            lastStartParams = params
            flushProgressOnStop = true
            stopActiveSessionOnStop = true
            renewMissingSessionWithLegacyStart = true
            stopEpoch
        }
        suspend fun publishFailureUnlessStopped(message: String): SessionState =
            guarded {
                if (stopEpoch != epochAtStart) {
                    SessionState.Idle.also { _state.value = it }
                } else {
                    SessionState.Failed(message).also { _state.value = it }
                }
            }

        val profileId = profileRepository.getActiveProfileId()
        if (profileId == null) {
            return publishFailureUnlessStopped("No active profile selected.")
        }

        val result = if (
            params.clientPlaybackContext != null ||
            params.subtitleTrackIndex != null ||
            params.preserveDirectAudioSelection ||
            params.playMethod != null
        ) {
            sessionManager.startSessionV2(
                fileId = params.fileId,
                profileId = profileId,
                capabilities = params.capabilities,
                audioTrackIndex = params.audioTrackIndex,
                subtitleTrackIndex = params.subtitleTrackIndex,
                qualityPreference = params.qualityPreference,
                startPosition = params.startPosition,
                clientPlaybackContext = params.clientPlaybackContext,
                preserveDirectAudioSelection = params.preserveDirectAudioSelection,
                playMethod = params.playMethod,
            )
        } else {
            sessionManager.startSession(
                fileId = params.fileId,
                profileId = profileId,
                capabilities = params.capabilities,
                audioTrackIndex = params.audioTrackIndex,
                qualityPreference = params.qualityPreference,
                startPosition = params.startPosition,
            )
        }
        return when (result) {
            is ApiResult.Success -> guarded {
                // A stop that landed while this start was in flight means the
                // user left. Publishing anyway would resurrect a screen they
                // dismissed, and — because that stop has already run its
                // teardown and never saw this id — would strand the session on
                // the server, where it keeps counting against the account's
                // concurrent-stream cap until it times out.
                if (stopEpoch != epochAtStart) {
                    DiagnosticsPlaybackLogger.sessionEvent("session abandoned, stopped during start")
                    Log.w(TAG, "stop landed during start; stopping session ${result.data.sessionId}")
                    when (val stopResult = sessionManager.stopSession(result.data.sessionId)) {
                        is ApiResult.Error ->
                            Log.w(TAG, "abandon stopSession error: ${stopResult.code} ${stopResult.message}")
                        is ApiResult.NetworkError ->
                            Log.w(TAG, "abandon stopSession network error: ${stopResult.exception}")
                        else -> {}
                    }
                    _state.value = SessionState.Idle
                    return@guarded SessionState.Idle
                }
                DiagnosticsPlaybackLogger.sessionEvent("session active")
                diagnosticsRecording.record(result.data.sessionId)
                val active = SessionState.Active(result.data)
                lastAdoptedSessionId = result.data.sessionId
                _state.value = active
                // Re-assert: a concurrent stop clears these, and without them
                // 404-session recovery and the final progress flush both no-op,
                // which silently loses the user's resume position on exit.
                lastStartParams = params
                lastReportedPosition = params.startPosition ?: result.data.position
                // Clear the missing-session debounce — fresh session id.
                recoveringFromMissingSession = null
                startProgressReporter()
                active
            }
            is ApiResult.Error -> {
                DiagnosticsPlaybackLogger.sessionEvent("session start failed")
                Log.w(TAG, "start session error: ${result.code} ${result.error} ${result.message}")
                publishFailureUnlessStopped(
                    result.message.ifBlank { "Failed to start playback." },
                )
            }
            is ApiResult.NetworkError -> {
                DiagnosticsPlaybackLogger.sessionEvent("session start network failure")
                Log.w(TAG, "start session network error: ${result.exception}")
                publishFailureUnlessStopped("Network error starting playback.")
            }
        }
    }

    /**
     * Push a position update from the player. Non-suspend — the actual server
     * report happens on the internal 10s debounce loop (see [PROGRESS_REPORT_INTERVAL_MS]).
     */
    fun reportPosition(positionSec: Double, durationSec: Double, isPaused: Boolean) {
        if (positionSec.isFinite() && positionSec >= 0) {
            lastReportedPosition = positionSec
        }
        if (durationSec.isFinite() && durationSec > 0) {
            lastReportedDuration = durationSec
        }
        lastIsPaused = isPaused
    }

    @Volatile private var lastIsPaused: Boolean = false

    /**
     * Tear down. Cancels reporter and recovery jobs, flushes a final progress
     * snapshot to PersonalData so position survives a server-side reset, and
     * stops the active session.
     */
    /**
     * True while [sessionId] is still the session this lifecycle is presenting.
     *
     * A recovery run that resumes after cancellation, or after a newer session
     * has been adopted, would otherwise republish stale state — a pre-outage
     * Active over a freshly adopted session, or a terminal Failed over content
     * that is playing fine.
     */
    private fun ownsRecoveredSession(sessionId: String): Boolean =
        when (val current = _state.value) {
            is SessionState.Active -> current.session.sessionId == sessionId
            // Still recovering the same session: no newer one has been adopted.
            //
            // A pending publication for *this* session is not evidence that
            // ownership moved — a subtitle commit defers publication for up to
            // MAX_LOCAL_MOUNT_WAIT_MS (30s), which outlasts the 10s progress
            // interval, so a progress-report NetworkError inside that window
            // enters Reconnecting with a pending publication of our own. Reading
            // that as "someone else owns this now" left the outage banner up
            // forever: beginOutageRecovery's Reconnecting guard blocks every
            // later attempt, and settlePendingPublicationIfCurrent requires
            // Active, so nothing could ever clear it again.
            else -> lastStartParams != null &&
                (pendingActiveSessionPublication?.replacementSessionId ?: sessionId) == sessionId
        }

    /**
     * Stops the session this caller believes is playing.
     *
     * This lifecycle is a process-scoped singleton, and teardown is deferred
     * behind settlement work, so a dying screen's stop can land after the next
     * screen has already started and adopted its own session — killing the
     * episode the user just started. Passing the id the caller was playing makes
     * the stop a no-op once ownership has moved on.
     */
    suspend fun stop(expectedSessionId: String? = null) {
        DiagnosticsPlaybackLogger.sessionEvent("session stop requested")
        mutex.withLock {
            if (expectedSessionId != null) {
                // Read the ownership token, not the presented state: a session
                // being reconnected or restarted is still owned, and answering
                // "no id" there let a stale stop cancel a live recovery.
                val activeSessionId =
                    (_state.value as? SessionState.Active)?.session?.sessionId ?: lastAdoptedSessionId
                if (activeSessionId != null && activeSessionId != expectedSessionId) {
                    DiagnosticsPlaybackLogger.sessionEvent("session stop skipped, ownership moved")
                    return
                }
            }
            // Past the ownership guard: this stop is going to tear down, so any
            // start currently in flight must not publish over it.
            stopEpoch++
            cancelRecoveryJobs()
            reporterJob?.cancel()
            reporterJob = null

            val pending = pendingActiveSessionPublication
            val pendingSessionId =
                (_state.value as? SessionState.Active)?.session?.sessionId
            if (
                pending != null &&
                pendingSessionId != null &&
                pending.replacementSessionId == pendingSessionId &&
                sessionManager.rollbackUnpublishedVideoSession(pendingSessionId)
            ) {
                pendingActiveSessionPublication = null
                restoreActiveSessionSnapshot(
                    snapshot = pending.predecessor,
                    restartReporter = false,
                )
            }

            val sessionId = (_state.value as? SessionState.Active)?.session?.sessionId
            // Fire the final snapshot regardless — even during Reconnecting we
            // want to durably record where the user was so a fresh login resumes
            // there.
            if (flushProgressOnStop) {
                flushFinalProgress()
            }

            if (sessionId != null && stopActiveSessionOnStop) {
                when (val r = sessionManager.stopSession(sessionId)) {
                    is ApiResult.Error -> Log.w(TAG, "stopSession error: ${r.code} ${r.message}")
                    is ApiResult.NetworkError ->
                        Log.w(TAG, "stopSession network error: ${r.exception}")
                    else -> {}
                }
            }
            lastStartParams = null
            lastReportedPosition = null
            lastReportedDuration = 0.0
            recoveringFromMissingSession = null
            flushProgressOnStop = true
            stopActiveSessionOnStop = true
            renewMissingSessionWithLegacyStart = true
            pendingActiveSessionPublication = null
            _notice.value = null
            lastAdoptedSessionId = null
            _state.value = SessionState.Idle
        }
        DiagnosticsPlaybackLogger.sessionEvent("session stopped")
    }

    /**
     * Fire-and-forget [stop] for teardown paths that must not block. [stop]
     * performs up to two HTTP round-trips (final progress sync + stopSession),
     * so awaiting it with `runBlocking` from `ViewModel.onCleared` freezes the
     * main thread for the full network timeout — an ANR on a slow link. The
     * lifecycle's own singleton scope outlives any ViewModel, and
     * [NonCancellable] keeps the stop running even if that scope is torn down.
     */
    fun stopAsync(expectedSessionId: String? = null) {
        val job = synchronized(pendingStopLock) {
            // Coalesce onto an in-flight stop only when it targets the same
            // session. Across different sessions the older job carries the older
            // id and no-ops once ownership has moved, so reusing it would
            // silently drop the newer stop and leave that session running.
            pendingStopJob
                ?.takeUnless { it.isCompleted }
                ?.takeIf { pendingStopSessionId == expectedSessionId }
                ?: scope.launch(
                    context = NonCancellable + Dispatchers.IO,
                    start = CoroutineStart.LAZY,
                ) {
                    stop(expectedSessionId)
                }.also {
                    pendingStopJob = it
                    pendingStopSessionId = expectedSessionId
                }
        }
        job.invokeOnCompletion {
            synchronized(pendingStopLock) {
                if (pendingStopJob === job) {
                    pendingStopJob = null
                    pendingStopSessionId = null
                }
            }
        }
        job.start()
    }

    /**
     * Reports and stops a session that remains externally owned.
     *
     * This does not adopt the session or mutate lifecycle-owned playback state.
     * The application scope outlives the external owner, while [NonCancellable]
     * and IO dispatch keep its final network writes off teardown callers.
     */
    fun reportAndStopExternalSessionAsync(
        sessionId: String,
        positionSeconds: Double,
        isPaused: Boolean,
    ) {
        val job = synchronized(externalFinalizationLock) {
            pendingExternalFinalizations[sessionId]
                ?.takeUnless { it.isCompleted }
                ?: scope.launch(
                    context = NonCancellable + Dispatchers.IO,
                    start = CoroutineStart.LAZY,
                ) {
                    runCatching {
                        sessionManager.reportProgress(
                            sessionId = sessionId,
                            position = positionSeconds,
                            isPaused = isPaused,
                        )
                    }
                    runCatching { sessionManager.stopSession(sessionId) }
                }.also {
                    pendingExternalFinalizations[sessionId] = it
                }
        }
        job.invokeOnCompletion {
            synchronized(externalFinalizationLock) {
                if (pendingExternalFinalizations[sessionId] === job) {
                    pendingExternalFinalizations.remove(sessionId)
                }
            }
        }
        job.start()
    }

    private suspend fun awaitPendingStop() {
        val job = synchronized(pendingStopLock) { pendingStopJob } ?: return
        job.join()
    }

    // ---- Internal: progress reporter ----------------------------------------

    private fun startProgressReporter() {
        reporterJob?.cancel()
        reporterJob = scope.launch {
            while (isActive) {
                delay(PROGRESS_REPORT_INTERVAL_MS)
                val sess = (_state.value as? SessionState.Active)?.session ?: continue
                val pos = lastReportedPosition ?: continue
                val result = sessionManager.reportProgress(
                    sessionId = sess.sessionId,
                    position = pos,
                    isPaused = lastIsPaused,
                )
                when {
                    isPlaybackSessionMissing(result) -> handleSessionMissing(sess.sessionId)
                    result is ApiResult.NetworkError -> {
                        Log.w(TAG, "reportProgress network error: ${result.exception}")
                        beginOutageRecovery(sess)
                    }
                    result is ApiResult.Error -> {
                        Log.w(TAG, "reportProgress error: ${result.code} ${result.message}")
                        if (result.code.isGatewayOrTunnelFailureStatus()) {
                            beginOutageRecovery(sess)
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    // ---- Internal: 404 session-missing recovery -----------------------------

    private fun handleSessionMissing(staleSessionId: String) {
        // Debounce: a flurry of 404s should only trigger one renewal.
        if (recoveringFromMissingSession == staleSessionId) return
        val params = lastStartParams ?: return

        recoveringFromMissingSession = staleSessionId
        DiagnosticsPlaybackLogger.sessionEvent("session missing")
        if (!renewMissingSessionWithLegacyStart) {
            _missingSessionEvents.tryEmit(lastReportedPosition ?: params.startPosition ?: 0.0)
            return
        }
        recoveryJob?.cancel()
        recoveryJob = scope.launch {
            mutex.withLock {
                Log.w(TAG, "Playback session missing; renewing")
                val resumePos = lastReportedPosition ?: params.startPosition
                syncProgressSnapshot(
                    contentId = params.contentId,
                    position = resumePos,
                    duration = lastReportedDuration,
                )
                // Re-invoke the start flow with the latest position without
                // cancelling this recovery coroutine out from under itself.
                startInternal(
                    params.copy(startPosition = resumePos),
                    diagnosticsRecording,
                    alreadyLocked = true,
                )
                recoveryJob = null
            }
        }
    }

    // ---- Internal: server-outage recovery -----------------------------------

    private fun beginOutageRecovery(currentSession: PlaybackSessionResponse) {
        if (outageJob?.isActive == true) return  // already probing
        if (_state.value is SessionState.Reconnecting) return

        val deadline = nowMs() + OUTAGE_TIMEOUT_MS
        _state.value = SessionState.Reconnecting(deadlineEpochMs = deadline, tone = NoticeTone.Warning)
        DiagnosticsPlaybackLogger.sessionEvent("session reconnecting")
        _notice.value = PlayerNotice(
            message = OUTAGE_RECONNECT_MESSAGE,
            tone = NoticeTone.Warning,
            expiresAtEpochMs = deadline,
        )

        val diagnosticsRecording = this.diagnosticsRecording
        // Ownership token for this recovery run. The probe cannot be aborted
        // mid-flight, so the loop can resume after cancellation and after a new
        // session has been adopted; every publication below is gated on this
        // still being the session we set out to recover.
        val recoveredSessionId = currentSession.sessionId
        outageJob = scope.launch {
            // Track elapsed via accumulating delay sums. We can't rely on
            // System.currentTimeMillis() here because tests run with a virtual
            // clock — `delay()` advances virtual time but the wall clock does
            // not. Counting our own delays is correct in both regimes.
            var elapsed = 0L
            var delayMs = OUTAGE_INITIAL_DELAY_MS
            while (isActive && elapsed < OUTAGE_TIMEOUT_MS) {
                val step = delayMs.coerceAtMost(OUTAGE_TIMEOUT_MS - elapsed)
                delay(step)
                elapsed += step
                if (elapsed >= OUTAGE_TIMEOUT_MS) break
                // Leave via return, not break: falling out of the loop reaches
                // the terminal Failed publication below, which a cancelled
                // recovery must never perform.
                if (!isActive) return@launch
                val probe = healthApi.checkHealth()
                // A probe that completed after we were cancelled must not
                // publish anything.
                currentCoroutineContext().ensureActive()
                if (probe is ApiResult.Success) {
                    // Only a decoded health payload is authoritative. Reverse
                    // proxies/tunnels can still produce HTTP errors, or even
                    // an HTML 200 page, while the Silo origin is down.
                    if (!ownsRecoveredSession(recoveredSessionId)) return@launch
                    Log.i(TAG, "Health probe succeeded; resuming playback session")
                    DiagnosticsPlaybackLogger.sessionEvent("session reconnected")
                    diagnosticsRecording.record(currentSession.sessionId)
                    lastAdoptedSessionId = currentSession.sessionId
                    _state.value = SessionState.Active(currentSession)
                    _notice.value = null
                    return@launch
                }
                // Error or NetworkError — back off and try again.
                delayMs = (delayMs * 2).coerceAtMost(OUTAGE_MAX_DELAY_MS)
            }
            // Timed out before the server came back.
            currentCoroutineContext().ensureActive()
            if (!ownsRecoveredSession(recoveredSessionId)) return@launch
            Log.w(TAG, "Outage recovery exhausted for playback session")
            DiagnosticsPlaybackLogger.sessionEvent("session reconnect failed")
            _state.value = SessionState.Failed(OUTAGE_TIMEOUT_MESSAGE)
            _notice.value = PlayerNotice(
                message = OUTAGE_TIMEOUT_MESSAGE,
                tone = NoticeTone.Warning,
                expiresAtEpochMs = null,
            )
        }
    }

    // ---- Internal: snapshot & helpers ---------------------------------------

    private suspend fun syncProgressSnapshot(
        contentId: String,
        position: Double?,
        duration: Double,
    ) {
        if (contentId.isBlank() || position == null || !position.isFinite() || position < 0) return
        val safeDuration = if (duration.isFinite() && duration > 0) duration else 0.0
        val result = personalDataRepository.syncProgress(
            listOf(
                SyncProgressItem(
                    mediaItemId = contentId,
                    position = position,
                    duration = safeDuration,
                    forceOverwrite = true,
                ),
            ),
        )
        when (result) {
            is ApiResult.Success -> Unit
            is ApiResult.Error -> Log.w(TAG, "syncProgress failed: ${result.code} ${result.message}")
            is ApiResult.NetworkError -> Log.w(TAG, "syncProgress network error: ${result.exception}")
        }
    }

    private suspend fun flushFinalProgress() {
        val params = lastStartParams ?: return
        syncProgressSnapshot(
            contentId = params.contentId,
            position = lastReportedPosition,
            duration = lastReportedDuration,
        )
    }

    private fun cancelRecoveryJobs() {
        recoveryJob?.cancel()
        recoveryJob = null
        outageJob?.cancel()
        outageJob = null
    }

    private fun isPlaybackSessionMissing(result: ApiResult<*>): Boolean {
        val error = result as? ApiResult.Error ?: return false
        return error.code == 404 &&
            (error.error == "playback_session_not_found" || error.message == "Playback session not found")
    }

    private fun nowMs(): Long = System.currentTimeMillis()

    companion object {
        private const val TAG = "PlaybackSessionLifecycle"

        // Mirrors PROGRESS_REPORT_INTERVAL_MS in PlayerViewModel / TvPlayerViewModel.
        const val PROGRESS_REPORT_INTERVAL_MS: Long = 10_000L

        // Mirrors iOS `serverOutageRecovery*` constants in PlayerViewModel.swift.
        const val OUTAGE_INITIAL_DELAY_MS: Long = 1_000L
        const val OUTAGE_MAX_DELAY_MS: Long = 8_000L
        const val OUTAGE_TIMEOUT_MS: Long = 90_000L

        const val OUTAGE_RECONNECT_MESSAGE: String =
            "Reconnecting — The server is updating. Playback will resume when it is ready."
        const val OUTAGE_TIMEOUT_MESSAGE: String =
            "The server did not come back online in time."
    }
}

internal fun Int.isGatewayOrTunnelFailureStatus(): Boolean =
    this == 502 || this == 503 || this == 504 || this in 520..527 || this == 530

// ---- Public types ----------------------------------------------------------

/** State of the playback session lifecycle. */
sealed interface SessionState {
    data object Idle : SessionState
    data object Loading : SessionState
    data class Active(val session: PlaybackSessionResponse) : SessionState
    data class Reconnecting(
        val deadlineEpochMs: Long,
        val tone: NoticeTone = NoticeTone.Warning,
    ) : SessionState
    data class Failed(val message: String) : SessionState
}

/** Severity / styling tone for a [PlayerNotice]. */
enum class NoticeTone { Info, Warning }

/**
 * UI surface for transient player banners. `null` from the [PlaybackSessionLifecycle.notice]
 * StateFlow means "show nothing".
 */
data class PlayerNotice(
    val message: String,
    val tone: NoticeTone,
    val expiresAtEpochMs: Long? = null,
)

/**
 * Parameters for [PlaybackSessionLifecycle.start]. Captured on every call so
 * 404-session-missing recovery can re-invoke `start()` with the same shape
 * plus an updated `startPosition`.
 */
data class StartParams(
    val contentId: String,
    val fileId: Int,
    val capabilities: ClientCodecCapabilities,
    val audioTrackIndex: Int? = null,
    val subtitleTrackIndex: Int? = null,
    val qualityPreference: String? = null,
    val startPosition: Double? = null,
    val clientPlaybackContext: ClientPlaybackContext? = null,
    val preserveDirectAudioSelection: Boolean = false,
    val playMethod: PlayMethod? = null,
)
