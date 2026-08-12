package org.siloserver.silo.common.player

import android.util.Log
import org.siloserver.silo.common.diagnostics.DiagnosticsPlaybackLogger
import org.siloserver.silo.common.diagnostics.DiagnosticsPlaybackSessionRecording
import org.siloserver.silo.common.diagnostics.DiagnosticsPlaybackSessionRecorder
import org.siloserver.silo.model.personal.SyncProgressItem
import org.siloserver.silo.model.playback.ClientCodecCapabilities
import org.siloserver.silo.model.playback.ClientPlaybackContext
import org.siloserver.silo.model.playback.PlaybackSessionResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.HealthApi
import org.siloserver.silo.repository.PersonalDataRepository
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
 *   adoptActiveSession(params, session) -> Active(session)
 *   reportPosition(...) -> debounced 10s flush via `sessionManager`
 *     - 404 session_not_found  -> sync snapshot, emit [missingSessionEvents]
 *     - NetworkError           -> Reconnecting + health-probe loop
 *   stop() -> Idle (also flushes one final progress snapshot)
 *
 * This class does not start sessions. Under protocol v3 a session is planned
 * by [PlaybackSessionManager.startVideoSessionV3] — which owns the attempt key,
 * the staged-replan machinery, and the publication handshake — and handed here
 * already started, so a second start entry point could only produce a session
 * the manager does not know it owns.
 */
class PlaybackSessionLifecycle(
    private val sessionManager: PlaybackSessionManager,
    private val healthApi: HealthApi,
    private val personalDataRepository: PersonalDataRepository,
    private val scope: CoroutineScope,
    private val playbackSessions: DiagnosticsPlaybackSessionRecorder = DiagnosticsPlaybackSessionRecorder.None,
) {

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _notice = MutableStateFlow<PlayerNotice?>(null)
    val notice: StateFlow<PlayerNotice?> = _notice.asStateFlow()

    private val _missingSessionEvents = MutableSharedFlow<MissingSessionRenewal>(extraBufferCapacity = 1)
    val missingSessionEvents: SharedFlow<MissingSessionRenewal> = _missingSessionEvents.asSharedFlow()

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
    /** Durable content-time coordinate; differs from session time for multipart audio. */
    @Volatile private var lastPersistencePosition: Double? = null
    @Volatile private var lastPersistenceDuration: Double = 0.0
    @Volatile private var recoveringFromMissingSession: String? = null
    @Volatile private var flushProgressOnStop: Boolean = true
    @Volatile private var stopActiveSessionOnStop: Boolean = true
    @Volatile private var diagnosticsRecording: DiagnosticsPlaybackSessionRecording =
        DiagnosticsPlaybackSessionRecording.None

    private var reporterJob: Job? = null
    private val recoveryJobLock = Any()
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
        /**
         * The ownership token as it stood, captured rather than derived.
         *
         * [SessionState] carries a session id only while Active, but
         * Reconnecting and Failed deliberately keep owning theirs — so
         * reconstructing the token from the restored state alone erases
         * ownership exactly for the states that exist to survive an outage.
         */
        val lastAdoptedSessionId: String?,
        val notice: PlayerNotice?,
        val lastStartParams: StartParams?,
        val lastReportedPosition: Double?,
        val lastReportedDuration: Double,
        val lastPersistencePosition: Double?,
        val lastPersistenceDuration: Double,
        val lastIsPaused: Boolean,
        val recoveringFromMissingSession: String?,
        val flushProgressOnStop: Boolean,
        val stopActiveSessionOnStop: Boolean,
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
     * reads state alone is blind exactly when it matters. During Reconnecting or
     * Failed a stale deferred stop finds no id, falls through, and cancels the
     * reconnect for a session it has no business touching — the banner vanishes
     * with nothing replacing it and progress reporting for that episode is dead
     * for the rest of playback.
     */
    @Volatile
    private var lastAdoptedSessionId: String? = null

    /**
     * Bumped by every [stop] that actually tears down.
     *
     * The owner plans a session before handing it here, and that planning runs
     * outside this mutex — so between [acquireOwnershipEpoch] and adoption
     * there is a window where [lastAdoptedSessionId] is still null and the
     * ownership guard in [stop] has no id to compare. Compare this instead: an
     * epoch unchanged at adoption time proves no stop ran while the plan was in
     * flight.
     */
    @Volatile
    private var stopEpoch: Long = 0L

    // ---- Public API ---------------------------------------------------------

    /**
     * Hands the lifecycle a session the caller already started. The lifecycle
     * then owns progress reporting, recovery, the final progress flush, and
     * stop.
     */
    suspend fun adoptActiveSession(
        params: StartParams,
        session: PlaybackSessionResponse,
        manageProgress: Boolean = true,
        stopSessionOnStop: Boolean = true,
        deferPublication: Boolean = false,
    ) {
        awaitPendingStop()
        adoptActiveSessionIfCurrent(
            params = params,
            session = session,
            manageProgress = manageProgress,
            stopSessionOnStop = stopSessionOnStop,
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
            // A protocol-v3 replan keeps the same server session id. Keep its
            // reporter alive as well: cancelling an in-flight Ktor POST can
            // leave the server with a truncated JSON body, and the network
            // wrapper turns that local cancellation into a NetworkError. That
            // briefly pushed a healthy subtitle replan through outage recovery.
            val reuseProgressReporter =
                manageProgress &&
                    lastAdoptedSessionId == session.sessionId &&
                    reporterJob?.isActive == true
            cancelRecoveryJobs()
            if (!reuseProgressReporter) {
                reporterJob?.cancel()
                reporterJob = null
            }
            _notice.value = null
            lastStartParams = params
            lastReportedPosition = params.startPosition ?: session.position
            lastReportedDuration = session.durationSeconds ?: 0.0
            lastPersistencePosition = params.startPosition ?: session.position
            lastPersistenceDuration = session.durationSeconds ?: 0.0
            lastIsPaused = session.isPaused
            recoveringFromMissingSession = null
            flushProgressOnStop = manageProgress
            stopActiveSessionOnStop = stopSessionOnStop
            this.diagnosticsRecording = diagnosticsRecording
            diagnosticsRecording.record(session.sessionId)
            lastAdoptedSessionId = session.sessionId
            _state.value = SessionState.Active(session)
            if (manageProgress && !reuseProgressReporter) {
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
        deferPublication: Boolean = false,
        expectedOwnershipEpoch: Long,
    ): Boolean = try {
        currentCoroutineContext().ensureActive()
        val adopted = adoptActiveSessionIfCurrent(
            params = params,
            session = session,
            manageProgress = manageProgress,
            stopSessionOnStop = stopSessionOnStop,
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
            lastAdoptedSessionId = lastAdoptedSessionId,
            notice = _notice.value,
            lastStartParams = lastStartParams,
            lastReportedPosition = lastReportedPosition,
            lastReportedDuration = lastReportedDuration,
            lastPersistencePosition = lastPersistencePosition,
            lastPersistenceDuration = lastPersistenceDuration,
            lastIsPaused = lastIsPaused,
            recoveringFromMissingSession = recoveringFromMissingSession,
            flushProgressOnStop = flushProgressOnStop,
            stopActiveSessionOnStop = stopActiveSessionOnStop,
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
        lastPersistencePosition = snapshot.lastPersistencePosition
        lastPersistenceDuration = snapshot.lastPersistenceDuration
        lastIsPaused = snapshot.lastIsPaused
        recoveringFromMissingSession = snapshot.recoveringFromMissingSession
        flushProgressOnStop = snapshot.flushProgressOnStop
        stopActiveSessionOnStop = snapshot.stopActiveSessionOnStop
        diagnosticsRecording = snapshot.diagnosticsRecording
        _notice.value = snapshot.notice
        // Restore the token the snapshot captured, rather than deriving it from
        // the restored state. Deriving gets both ends wrong: reading it only
        // from Active leaves a rolled-back first deferred adoption naming the
        // discarded replacement, while clearing everything that is not Active
        // erases ownership for Reconnecting and Failed — which hold a session
        // precisely so an outage does not lose it. A predecessor restored as
        // Reconnecting would then have no id for stop() to name, and its
        // transcode would run until the server expired it.
        lastAdoptedSessionId = snapshot.lastAdoptedSessionId
        _state.value = snapshot.state
        if (
            restartReporter &&
            snapshot.reporterWasActive &&
            snapshot.state is SessionState.Active
        ) {
            startProgressReporter()
        }
    }

    /**
     * Push a position update from the player. Non-suspend — the actual server
     * report happens on the internal 10s debounce loop (see [PROGRESS_REPORT_INTERVAL_MS]).
     */
    fun reportPosition(
        positionSec: Double,
        durationSec: Double,
        isPaused: Boolean,
        /**
         * The session the caller believes produced this sample; null when the
         * caller owns none, as downloaded and local playback do not.
         *
         * These fields are process-global and the reporter loop pairs them with
         * whichever session is current when it next fires — so a final callback
         * from an outgoing player, arriving after the next screen has adopted,
         * would otherwise flush the previous episode's position under the new
         * episode's id. That is the "resume jumped to the last episode's time"
         * shape.
         *
         * Note that null is NOT "skip the check": both exit paths clear the UI
         * session id while player callbacks are still draining, so treating null
         * as permission is exactly the hole this closes. A caller with no
         * session may only write these fields while the lifecycle owns none
         * either.
         */
        expectedSessionId: String?,
        /** Content-level coordinate used by durable resume persistence. */
        persistencePositionSec: Double = positionSec,
        /** Content-level duration paired with [persistencePositionSec]. */
        persistenceDurationSec: Double = durationSec,
    ) {
        if (expectedSessionId != lastAdoptedSessionId) return
        if (positionSec.isFinite() && positionSec >= 0) {
            lastReportedPosition = positionSec
        }
        if (durationSec.isFinite() && durationSec > 0) {
            lastReportedDuration = durationSec
        }
        if (persistencePositionSec.isFinite() && persistencePositionSec >= 0) {
            lastPersistencePosition = persistencePositionSec
        }
        if (persistenceDurationSec.isFinite() && persistenceDurationSec > 0) {
            lastPersistenceDuration = persistenceDurationSec
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

            // The adopted id, not the published state's. Reconnecting and
            // Failed carry no session id, so reading it from Active alone meant
            // leaving during an outage — or after the outage timeout gave up —
            // never told the server to stop. The transcode then ran on until it
            // timed out, holding a stream slot the viewer had already walked
            // away from.
            val sessionId = (_state.value as? SessionState.Active)?.session?.sessionId
                ?: lastAdoptedSessionId
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
            lastPersistencePosition = null
            lastPersistenceDuration = 0.0
            recoveringFromMissingSession = null
            flushProgressOnStop = true
            stopActiveSessionOnStop = true
            pendingActiveSessionPublication = null
            _notice.value = null
            lastAdoptedSessionId = null
            _state.value = SessionState.Idle
        }
        DiagnosticsPlaybackLogger.sessionEvent("session stopped")
    }

    /**
     * Retires a terminal playback attempt, then rechecks screen ownership.
     *
     * [stop] cancels the reporter without joining it, so a report may still be
     * in flight here. It clears `lastAdoptedSessionId` under [mutex] first, and
     * [ownsProgressReply] then discards any late reply. Phone and TV must share
     * this terminal-first ordering so a stale progress tick cannot renew the
     * retired server session.
     */
    suspend fun stopTerminalSessionIfCurrent(
        expectedSessionId: String,
        isCurrent: () -> Boolean,
    ): Boolean {
        stop(expectedSessionId = expectedSessionId)
        currentCoroutineContext().ensureActive()
        return isCurrent()
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
                    // This scope has a SupervisorJob but no
                    // CoroutineExceptionHandler, and nothing joins this job for
                    // its result, so an unexpected throw from stop() would
                    // escape as an uncaught coroutine exception and take the
                    // process down. Teardown failing is not worth a crash: the
                    // server session expires on its own timeout.
                    try {
                        stop(expectedSessionId)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (t: Throwable) {
                        Log.w(TAG, "async stop failed for $expectedSessionId", t)
                    }
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
                // The API wrapper represents CancellationException as a
                // NetworkError. Never interpret cancellation of this reporter
                // itself as evidence that the server is offline.
                if (!currentCoroutineContext().isActive) continue
                // Re-check ownership AFTER the call. Cancelling this job is not
                // enough to stop what follows: the network wrapper catches
                // cancellation and hands back a NetworkError, so a reporter
                // belonging to the previous episode carries on and acts on a
                // reply about a session nobody is watching any more.
                //
                // Everything below reacts to that reply by rewriting shared
                // state — publishing Reconnecting, restoring Active, or
                // starting a replacement session from the CURRENT start
                // params. Left unguarded, a late answer about episode A does
                // all of that to episode B.
                if (!ownsProgressReply(sess.sessionId)) continue
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

    /**
     * Whether a progress reply still concerns the session on screen.
     *
     * Compared against the adopted id rather than the published state, because
     * the states this guards against — Reconnecting and Failed — carry no
     * session id of their own, and a reply arriving during one of them is
     * exactly the case that must not act.
     */
    private fun ownsProgressReply(sessionId: String): Boolean =
        lastAdoptedSessionId == sessionId

    // ---- Internal: 404 session-missing recovery -----------------------------

    /**
     * The session vanished server-side (404). Renewal is the owner's job: only
     * the ViewModel that planned this session can replan it through
     * [PlaybackSessionManager.startVideoSessionV3] and re-adopt the result, so
     * the lifecycle persists the resume position and hands it over.
     *
     * The snapshot is written before the event because the owner's replan can
     * fail — and if it does, this write is all that stands between the user and
     * losing their place.
     */
    private fun handleSessionMissing(staleSessionId: String) {
        // Debounce: a flurry of 404s should only trigger one renewal.
        val params = lastStartParams ?: return
        val resumePosition = lastReportedPosition ?: params.startPosition ?: 0.0
        val persistencePosition = lastPersistencePosition ?: resumePosition
        val persistenceDuration = lastPersistenceDuration.takeIf { it > 0.0 }
            ?: lastReportedDuration
        val job = synchronized(recoveryJobLock) {
            if (recoveringFromMissingSession == staleSessionId) return
            recoveringFromMissingSession = staleSessionId
            recoveryJob?.cancel()
            scope.launch(start = CoroutineStart.LAZY) {
                if (!ownsProgressReply(staleSessionId)) return@launch
                syncProgressSnapshot(
                    contentId = params.contentId,
                    position = persistencePosition,
                    duration = persistenceDuration,
                )
                if (!ownsProgressReply(staleSessionId)) return@launch
                _missingSessionEvents.emit(
                    MissingSessionRenewal(
                        staleSessionId = staleSessionId,
                        positionSeconds = resumePosition,
                        startParams = params,
                    ),
                )
            }.also { recoveryJob = it }
        }
        DiagnosticsPlaybackLogger.sessionEvent("session missing")
        job.invokeOnCompletion {
            synchronized(recoveryJobLock) {
                if (recoveryJob === job) recoveryJob = null
            }
        }
        job.start()
    }

    // ---- Internal: server-outage recovery -----------------------------------

    private fun beginOutageRecovery(currentSession: PlaybackSessionResponse) {
        val job = synchronized(recoveryJobLock) {
            if (outageJob?.isActive == true) return
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
            scope.launch(start = CoroutineStart.LAZY) {
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
            }.also { outageJob = it }
        }
        job.invokeOnCompletion {
            synchronized(recoveryJobLock) {
                if (outageJob === job) outageJob = null
            }
        }
        job.start()
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
            position = lastPersistencePosition ?: lastReportedPosition,
            duration = lastPersistenceDuration.takeIf { it > 0.0 }
                ?: lastReportedDuration,
        )
    }

    private fun cancelRecoveryJobs() {
        synchronized(recoveryJobLock) {
            recoveryJob?.cancel()
            recoveryJob = null
            outageJob?.cancel()
            outageJob = null
        }
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
            "Reconnecting. Playback will resume automatically."
        const val OUTAGE_TIMEOUT_MESSAGE: String =
            "The server did not come back online in time."
    }
}

internal fun Int.isGatewayOrTunnelFailureStatus(): Boolean =
    this == 502 || this == 503 || this == 504 || this in 520..527 || this == 530

// ---- Public types ----------------------------------------------------------

/**
 * State of the playback session lifecycle.
 *
 * There is no Loading state: this lifecycle is handed sessions that are already
 * planned and started, so it is never the thing waiting on the server.
 */
sealed interface SessionState {
    data object Idle : SessionState
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
 * Durable inputs for renewing a server-side session that disappeared.
 *
 * Media3 may publish an empty track snapshot while it is failing, so renewal
 * must not reconstruct the viewer's audio/subtitle choices from live player
 * tracks. [PlaybackSessionLifecycle] captures these parameters at adoption and
 * returns the exact snapshot with the last reported source position.
 */
data class MissingSessionRenewal(
    val staleSessionId: String,
    val positionSeconds: Double,
    val startParams: StartParams,
)

/**
 * The shape of the session [PlaybackSessionLifecycle] is presenting. Captured
 * on adoption so a 404-session-missing event can hand its owner back the exact
 * content, version, route and track intent to renew.
 */
data class StartParams(
    val contentId: String,
    val fileId: Int,
    val capabilities: ClientCodecCapabilities,
    val audioTrackIndex: Int? = null,
    val subtitleTrackIndex: Int? = null,
    val qualityPreference: String? = null,
    val startPosition: Double? = null,
    val clientPlaybackContext: ClientPlaybackContext,
)
