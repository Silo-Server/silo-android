package org.siloserver.silo.android.ui.screens.player

import android.os.SystemClock
import org.siloserver.silo.RoomSyncEngine
import org.siloserver.silo.SyncDecision
import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.RoomPlaybackState
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.TransportAction
import org.siloserver.silo.repository.PongSample
import org.siloserver.silo.repository.ScheduledTransportCommand
import org.siloserver.silo.repository.WatchTogetherRepository
import org.siloserver.silo.watchtogether.RoomTransportIntent
import org.siloserver.silo.watchtogether.RoomSession
import org.siloserver.silo.watchtogether.RoomCommandDispatcher
import org.siloserver.silo.watchtogether.RoomControllerLifetime
import org.siloserver.silo.watchtogether.RoomPendingExecutions
import org.siloserver.silo.watchtogether.externalPlayPauseDecision
import org.siloserver.silo.watchtogether.roomTransportAuthorized
import org.siloserver.silo.watchtogether.scheduledCommandStillCurrent
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Binds a Watch Together room to the mobile [PlayerViewModel] for the lifetime
 * of a synced-player screen. Active ONLY when the player route carries a roomId.
 *
 * The controller OWNS a [RoomSyncEngine] (the shared engine is a pure class that
 * lives in the binding, not the repo). It:
 *  - adopts the application-scoped room session and attaches the player's
 *    playback session id once it resolves;
 *  - drives a ping loop so pongs flow, stamps `clientReceivedMs` on each pong and
 *    feeds [RoomSyncEngine.recordPongSample] to keep the clock offset fresh;
 *  - feeds each [ScheduledTransportCommand] into [RoomSyncEngine.decide] and
 *    applies the resulting [SyncDecision] (seek / setPlaying) at the engine-
 *    scheduled local delay, auto-emitting `ready` on the waiting barrier;
 *  - emits `state_report` on a ~1.5s cadence, suppressed around a pending execute
 *    (the only unit-tested decision — see [shouldEmitStateReport]);
 *  - emits `ready`/`buffering` on buffer transitions while the room is waiting;
 *  - routes user play/pause/seek through `transport_request`, gated on
 *    `self_can_control_transport`;
 *  - surfaces `room_closed` so the screen exits.
 *
 * ## Clock domains (critical)
 *  - WALL CLOCK ([System.currentTimeMillis]) is used for everything the engine
 *    sees: the ping `client_sent_at` RFC3339 string, the `clientReceivedMs`
 *    pong stamp, and `nowLocalMs` passed to [RoomSyncEngine.decide]. The engine
 *    derives its offset as `server - clientWallClock`, and the server timestamps
 *    are wall-clock epoch millis, so these MUST share the same clock.
 *  - MONOTONIC ([SystemClock.elapsedRealtime]) is used only for the local
 *    state-report cadence/suppression gate — pure local timing that must not
 *    jump if the wall clock is adjusted.
 */
class RoomSyncController(
    private val roomId: String,
    private val repository: WatchTogetherRepository,
    private val roomSession: RoomSession,
    private val viewModel: PlayerViewModel,
    private val scope: CoroutineScope,
    private val engine: RoomSyncEngine = RoomSyncEngine(),
) {
    companion object {
        private const val REPORT_CADENCE_MS = 1_500L
        private const val SUPPRESS_WINDOW_MS = 250L
        private const val REPORT_TICK_MS = 250L
        private const val PING_INTERVAL_MS = 15_000L
    }

    /** Live room snapshot for the overlay. */
    val room: StateFlow<RoomSnapshot?> get() = repository.roomSnapshot

    /**
     * Non-null once the server CLOSES the room (host left / explicit close) — the
     * screen observes and exits. Transient server `error` frames do NOT set this
     * (they would wrongly eject the user); they flow on [WatchTogetherRepository.errors].
     */
    val closedReason: StateFlow<String?> get() = repository.roomClosedReason

    // Monotonic-domain state-report cadence bookkeeping.
    @Volatile private var lastReportMs = 0L
    private val lifetime = RoomControllerLifetime(scope)
    private val controllerScope = lifetime.scope
    private val deliveryLatch = repository.roomDeliveryLatch
    private val commandDispatcher = RoomCommandDispatcher(controllerScope)
    private val pendingExecutions = RoomPendingExecutions()

    fun start() {
        // Install every inbound/outbound collector before adopting the socket.
        // UNDISTPATCHED closes the initial-snapshot race when adopt opens
        // synchronously on this same UI turn.
        controllerScope.launch(start = CoroutineStart.UNDISPATCHED) {
            combine(
                viewModel.uiState.map { it.sessionId }.distinctUntilChanged(),
                repository.connectionState,
            ) { sessionId, connection -> deliveryLatch.keyOrNull(connection, sessionId) }
                .distinctUntilChanged()
                .collectLatest { key ->
                    key ?: return@collectLatest
                    while (isActive && deliveryLatch.needsAttach(key)) {
                        val delivered = repository.attachSession(key.playbackSessionId)
                        deliveryLatch.recordAttach(key, delivered)
                        if (!delivered) {
                            delay(500)
                        }
                    }
                }
        }

        // Clock-sync: drive pings (once on start + every PING_INTERVAL_MS) and
        // fold pongs into the engine's offset estimate.
        controllerScope.launch(start = CoroutineStart.UNDISPATCHED) {
            combine(
                viewModel.uiState.map { it.sessionId }.distinctUntilChanged(),
                repository.connectionState,
            ) { sessionId, connection ->
                deliveryLatch.keyOrNull(connection, sessionId) to connection.writable
            }
                .distinctUntilChanged()
                .collectLatest { (key, writable) ->
                    if (!writable || key == null) return@collectLatest
                    while (!deliveryLatch.isAttached(key)) delay(10)
                    while (isActive) {
                        repository.ping(clientSentAt = nowWallClockRfc3339())
                        delay(PING_INTERVAL_MS)
                    }
                }
        }
        controllerScope.launch(start = CoroutineStart.UNDISPATCHED) {
            repository.pongs.collect { pong -> recordPong(pong) }
        }

        // Apply engine decisions for each scheduled transport command.
        controllerScope.launch(start = CoroutineStart.UNDISPATCHED) {
            repository.transportCommands.collect { scheduled -> handleCommand(scheduled) }
        }

        // Drift reporting loop (suppressed around a pending execute).
        controllerScope.launch {
            while (isActive) {
                val state = viewModel.uiState.value
                val sessionId = state.sessionId
                val deliveryKey = deliveryLatch.keyOrNull(
                    repository.connectionState.value,
                    sessionId,
                )
                val now = monotonicMs()
                if (deliveryKey != null &&
                    deliveryLatch.isServerAttached(
                        deliveryKey,
                        repository.roomDeliveryEcho.value,
                    ) &&
                    shouldEmitStateReport(
                        now,
                        lastReportMs,
                        REPORT_CADENCE_MS,
                        pendingExecutions.nearestTo(now),
                        SUPPRESS_WINDOW_MS,
                    )
                ) {
                    lastReportMs = now
                    repository.stateReport(
                        sessionId = deliveryKey.playbackSessionId,
                        positionSeconds = state.position,
                        isPaused = state.isPaused,
                    )
                }
                delay(REPORT_TICK_MS)
            }
        }

        // ready / buffering during the waiting barrier.
        controllerScope.launch(start = CoroutineStart.UNDISPATCHED) {
            combine(
                viewModel.uiState
                    .map { it.sessionId to it.isBuffering }
                    .distinctUntilChanged(),
                repository.roomSnapshot
                    .map { it?.playbackState }
                    .distinctUntilChanged(),
                repository.connectionState,
            ) { readiness, playbackState, connection -> Triple(readiness, playbackState, connection) }
                .collectLatest { (readiness, playbackState, connection) ->
                    val (sessionId, buffering) = readiness
                    val key = deliveryLatch.keyOrNull(connection, sessionId)
                    if (playbackState != RoomPlaybackState.Waiting || key == null) {
                        return@collectLatest
                    }
                    while (
                        !deliveryLatch.isServerAttached(
                            key,
                            repository.roomDeliveryEcho.value,
                        )
                    ) {
                        delay(10)
                    }
                    while (isActive && deliveryLatch.needsReadiness(key, buffering)) {
                        val currentState = viewModel.uiState.value
                        val delivered = if (buffering) {
                            repository.buffering(
                                key.playbackSessionId,
                                currentState.position,
                                currentState.isPaused,
                            )
                        } else {
                            repository.ready(
                                key.playbackSessionId,
                                currentState.position,
                                currentState.isPaused,
                            )
                        }
                        deliveryLatch.recordReadiness(key, buffering, delivered)
                        if (!delivered) {
                            delay(500)
                        }
                    }
                }
        }

        // Lobby and player adopt the same application-scoped connection; route
        // disposal does not tear down or duplicate the socket.
        roomSession.adopt(roomId)
    }

    /**
     * Stamp `clientReceivedMs` (wall clock) at collection time and feed the NTP
     * sample to the engine. Null guards: a sample with any missing server
     * timestamp can't update the offset, so we drop it.
     */
    private fun recordPong(pong: PongSample) {
        val clientSent = pong.clientSentMs ?: return
        val serverReceived = pong.serverReceivedMs ?: return
        val serverSent = pong.serverSentMs ?: return
        engine.recordPongSample(
            clientSentMs = clientSent,
            serverReceivedMs = serverReceived,
            serverSentMs = serverSent,
            clientReceivedMs = System.currentTimeMillis(),
        )
    }

    private fun handleCommand(scheduled: ScheduledTransportCommand) {
        if (!scheduled.connection.writable ||
            scheduled.connection != repository.connectionState.value
        ) {
            return
        }
        val command = scheduled.command
        val state = viewModel.uiState.value
        val snapshot = repository.roomSnapshot.value
        // executeAtMs null (malformed wire timestamp) → apply immediately; pass
        // nowWallClock so the engine computes a 0 delay.
        val nowWall = System.currentTimeMillis()
        val serverExecuteAtMs = scheduled.executeAtMs ?: nowWall
        val decision = engine.decide(
            command = command,
            serverExecuteAtMs = serverExecuteAtMs,
            currentLocalPositionMs = (state.position * 1000.0).toLong(),
            currentIsPlaying = !state.isPaused,
            nowLocalMs = nowWall,
            currentRevision = snapshot?.selectionRevision ?: command.selectionRevision,
            attachedSessionId = snapshot?.attachedSessionId ?: state.sessionId,
        ) ?: return // duplicate / revision / session gate → ignore

        commandDispatcher.dispatch {
            applyDecision(
                decision = decision,
                sessionId = state.sessionId,
                command = command,
                acceptedConnection = scheduled.connection,
            )
        }
    }

    private suspend fun applyDecision(
        decision: SyncDecision,
        sessionId: String?,
        command: org.siloserver.silo.model.watchtogether.TransportCommand,
        acceptedConnection: org.siloserver.silo.repository.WatchTogetherConnectionState,
    ) {
        val delayMs = decision.localExecuteDelayMs.coerceAtLeast(0L)
        // Record the pending execute against the MONOTONIC clock for the
        // state-report suppression gate, then wait the engine-computed delay.
        val pendingToken = pendingExecutions.record(monotonicMs() + delayMs)
        try {
            if (delayMs > 0) delay(delayMs)
            if (
                !scheduledCommandStillCurrent(
                    command = command,
                    acceptedPlaybackSessionId = sessionId,
                    acceptedRoomId = roomId,
                    acceptedConnection = acceptedConnection,
                    currentPlaybackSessionId = viewModel.uiState.value.sessionId,
                    currentRoom = repository.roomSnapshot.value,
                    currentConnection = repository.connectionState.value,
                )
            ) {
                return
            }

            // Use the deadband-free immediate-seek path: room corrective seeks can be
            // as small as the engine's 0.35s DRIFT_THRESHOLD_MS, well under the 2.0s
            // position-mirror deadband in PlayerScreen, so onSeek() alone would never
            // reach the MediaController.
            decision.seekToMs?.let { ms -> viewModel.seekImmediate(ms / 1000.0) }
            // onPlayPause() is a TOGGLE — only flip when the desired state differs
            // from current intent.
            if (viewModel.uiState.value.isPaused == decision.setPlaying) {
                viewModel.onPlayPause()
            }
        } finally {
            pendingExecutions.finish(pendingToken)
        }

        // The epoch/session-keyed readiness collector owns ready delivery and
        // retries. Do not advance an untracked one-shot latch here.
    }

    // ---- User-initiated transport: route through the room instead of local apply ----

    fun onUserPlayPause() {
        val authorization = repository.currentTransportAuthorization() ?: return
        val snapshot = authorization.snapshot
        if (!roomTransportAuthorized(snapshot, RoomTransportIntent.PlayPause)) return
        val state = viewModel.uiState.value
        // isPaused is current intent; tapping toggles it. The request carries the
        // target action and the new paused state.
        val willPause = !state.isPaused
        controllerScope.launch {
            val delivered = repository.transportRequestForAuthorization(
                authorization = authorization,
                intent = RoomTransportIntent.PlayPause,
                action = if (willPause) TransportAction.Pause.wire else TransportAction.Play.wire,
                positionSeconds = state.position,
                isPaused = willPause,
            )
            if (!delivered) repository.reportDeliveryFailure("room_transport_unavailable")
        }
    }

    fun onUserSeek(positionSeconds: Double) {
        // Seek is host-only (the server rejects guest seeks regardless of policy).
        val authorization = repository.currentTransportAuthorization() ?: return
        val snapshot = authorization.snapshot
        if (!roomTransportAuthorized(snapshot, RoomTransportIntent.Seek)) return
        val state = viewModel.uiState.value
        controllerScope.launch {
            val delivered = repository.transportRequestForAuthorization(
                authorization = authorization,
                intent = RoomTransportIntent.Seek,
                action = TransportAction.Seek.wire,
                positionSeconds = positionSeconds,
                isPaused = state.isPaused,
            )
            if (!delivered) repository.reportDeliveryFailure("room_transport_unavailable")
        }
    }

    /**
     * Reconcile a direct MediaSession play/pause change with room authority.
     *
     * Returns the authoritative `playWhenReady` value when the caller must
     * immediately undo a local-only change. Authorized attempts are also sent
     * through the room and take effect later via the scheduled room command.
     */
    fun onExternalPlayWhenReadyChanged(localPlayWhenReady: Boolean): Boolean? {
        val authorization = repository.currentTransportAuthorization()
        val snapshot = authorization?.snapshot
        val decision = externalPlayPauseDecision(
            snapshot = snapshot,
            localPlayWhenReady = localPlayWhenReady,
        ) ?: return null
        if (authorization == null) return decision.restorePlayWhenReady
        val requestIsPaused = decision.requestIsPaused
        if (requestIsPaused != null) {
            val state = viewModel.uiState.value
            controllerScope.launch {
                val delivered = repository.transportRequestForAuthorization(
                    authorization = authorization,
                    intent = RoomTransportIntent.PlayPause,
                    action = if (requestIsPaused) {
                        TransportAction.Pause.wire
                    } else {
                        TransportAction.Play.wire
                    },
                    positionSeconds = state.position,
                    isPaused = requestIsPaused,
                )
                if (!delivered) repository.reportDeliveryFailure("room_transport_unavailable")
            }
        }
        return decision.restorePlayWhenReady
    }

    /** Leave the room. Host close tears the room down for everyone; both reset local state. */
    fun leave(closeRoom: Boolean) {
        engine.reset()
        roomSession.depart(closeRoom)
    }

    /** Cancel this screen binding without ending the process-owned room lease. */
    fun dispose() {
        engine.reset()
        lifetime.dispose()
    }

    /** Wall-clock RFC3339 string for the ping `client_sent_at`. */
    private fun nowWallClockRfc3339(): String = Instant.ofEpochMilli(System.currentTimeMillis()).toString()

    /** Monotonic clock for local-only cadence/scheduling. */
    private fun monotonicMs(): Long = SystemClock.elapsedRealtime()
}

/** Convenience: is the local member the room host? */
val RoomSnapshot.isHost: Boolean get() = selfRole == MemberRole.Host
