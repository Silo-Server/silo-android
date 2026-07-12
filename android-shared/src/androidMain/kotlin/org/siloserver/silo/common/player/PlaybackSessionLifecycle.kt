package org.siloserver.silo.common.player

import android.util.Log
import org.siloserver.silo.model.personal.SyncProgressItem
import org.siloserver.silo.model.playback.ClientCodecCapabilities
import org.siloserver.silo.model.playback.ClientPlaybackContext
import org.siloserver.silo.model.playback.PlayMethod
import org.siloserver.silo.model.playback.PlaybackSessionResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.HealthApi
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.repository.ProfileRepository
import kotlinx.coroutines.CoroutineScope
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    private var reporterJob: Job? = null
    private var recoveryJob: Job? = null
    private var outageJob: Job? = null

    // ---- Public API ---------------------------------------------------------

    /**
     * Starts a new playback session. Resolves to [SessionState.Active] on
     * success or [SessionState.Failed] on profile-id absence or session API
     * failure (Error or NetworkError).
     */
    suspend fun start(params: StartParams): SessionState {
        // New start cancels any in-flight recovery / outage probing, by design:
        // this is the explicit "user/code wants a fresh session now" path.
        cancelRecoveryJobs()
        return startInternal(params)
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
    ) {
        mutex.withLock {
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
            _state.value = SessionState.Active(session)
            if (manageProgress) {
                startProgressReporter()
            }
        }
    }

    private suspend fun startInternal(params: StartParams): SessionState {
        _notice.value = null
        _state.value = SessionState.Loading
        lastStartParams = params
        flushProgressOnStop = true
        stopActiveSessionOnStop = true
        renewMissingSessionWithLegacyStart = true

        val profileId = profileRepository.getActiveProfileId()
        if (profileId == null) {
            val failed = SessionState.Failed("No active profile selected.")
            _state.value = failed
            return failed
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
            is ApiResult.Success -> {
                val active = SessionState.Active(result.data)
                _state.value = active
                lastReportedPosition = params.startPosition ?: result.data.position
                // Clear the missing-session debounce — fresh session id.
                recoveringFromMissingSession = null
                startProgressReporter()
                active
            }
            is ApiResult.Error -> {
                Log.w(TAG, "start session error: ${result.code} ${result.error} ${result.message}")
                val failed = SessionState.Failed(result.message.ifBlank { "Failed to start playback." })
                _state.value = failed
                failed
            }
            is ApiResult.NetworkError -> {
                Log.w(TAG, "start session network error: ${result.exception}")
                val failed = SessionState.Failed("Network error starting playback.")
                _state.value = failed
                failed
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
    suspend fun stop() {
        val current = _state.value
        cancelRecoveryJobs()
        reporterJob?.cancel()
        reporterJob = null

        val sessionId = (current as? SessionState.Active)?.session?.sessionId
        // Fire the final snapshot regardless — even during Reconnecting we
        // want to durably record where the user was so a fresh login resumes
        // there.
        if (flushProgressOnStop) {
            flushFinalProgress()
        }

        if (sessionId != null && stopActiveSessionOnStop) {
            when (val r = sessionManager.stopSession(sessionId)) {
                is ApiResult.Error -> Log.w(TAG, "stopSession error: ${r.code} ${r.message}")
                is ApiResult.NetworkError -> Log.w(TAG, "stopSession network error: ${r.exception}")
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
        _notice.value = null
        _state.value = SessionState.Idle
    }

    /**
     * Fire-and-forget [stop] for teardown paths that must not block. [stop]
     * performs up to two HTTP round-trips (final progress sync + stopSession),
     * so awaiting it with `runBlocking` from `ViewModel.onCleared` freezes the
     * main thread for the full network timeout — an ANR on a slow link. The
     * lifecycle's own singleton scope outlives any ViewModel, and
     * [NonCancellable] keeps the stop running even if that scope is torn down.
     */
    fun stopAsync() {
        scope.launch(NonCancellable + Dispatchers.IO) { stop() }
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
                startInternal(params.copy(startPosition = resumePos))
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
        _notice.value = PlayerNotice(
            message = OUTAGE_RECONNECT_MESSAGE,
            tone = NoticeTone.Warning,
            expiresAtEpochMs = deadline,
        )

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
                if (!isActive || elapsed >= OUTAGE_TIMEOUT_MS) break
                val probe = healthApi.checkHealth()
                if (probe is ApiResult.Success) {
                    // Only a decoded health payload is authoritative. Reverse
                    // proxies/tunnels can still produce HTTP errors, or even
                    // an HTML 200 page, while the Silo origin is down.
                    Log.i(TAG, "Health probe succeeded; resuming playback session")
                    _state.value = SessionState.Active(currentSession)
                    _notice.value = null
                    return@launch
                }
                // Error or NetworkError — back off and try again.
                delayMs = (delayMs * 2).coerceAtMost(OUTAGE_MAX_DELAY_MS)
            }
            // Timed out before the server came back.
            Log.w(TAG, "Outage recovery exhausted for playback session")
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
