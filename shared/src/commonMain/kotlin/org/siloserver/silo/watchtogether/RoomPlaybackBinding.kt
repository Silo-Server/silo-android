package org.siloserver.silo.watchtogether

import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.RoomPlaybackState
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.TransportAction
import org.siloserver.silo.model.watchtogether.TransportCommand
import org.siloserver.silo.repository.RoomTransportAuthorization
import org.siloserver.silo.repository.ScheduledTransportCommand
import org.siloserver.silo.repository.WatchTogetherConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/** The room side of the playback binding. [org.siloserver.silo.repository.WatchTogetherRepository] implements it. */
interface RoomPlaybackRoom {
    val roomSnapshot: StateFlow<RoomSnapshot?>
    val connectionState: StateFlow<WatchTogetherConnectionState>
    val roomDeliveryEcho: StateFlow<RoomDeliveryEcho?>
    val latestTransportCommand: StateFlow<ScheduledTransportCommand?>
    val clock: StateFlow<RoomClockEstimate>
    suspend fun attachSession(sessionId: String): Boolean
    suspend fun stateReport(
        sessionId: String,
        positionSeconds: Double,
        isPaused: Boolean,
        commandId: String? = null,
        isReady: Boolean = false,
    ): Boolean
    suspend fun ready(sessionId: String, positionSeconds: Double, isPaused: Boolean, commandId: String? = null): Boolean
    suspend fun buffering(sessionId: String, positionSeconds: Double, isPaused: Boolean): Boolean
    suspend fun checkClockContinuity(): RoomClockEstimate
    fun currentTransportAuthorization(): RoomTransportAuthorization?
    suspend fun transportRequestForAuthorization(
        authorization: RoomTransportAuthorization,
        intent: RoomTransportIntent,
        action: String,
        positionSeconds: Double?,
        isPaused: Boolean,
    ): Boolean
}

enum class RoomPlayerState { Idle, Buffering, Ready, Ended }

/**
 * What the player is actually doing. [sourcePositionSeconds] is the engine
 * position mapped to the source timeline, never an optimistic seek target.
 */
data class RoomPlayerObservation(
    /** The committed playback session, or null while none is committed. */
    val sessionId: String? = null,
    /** The room selection revision this player was prepared for. */
    val selectionRevision: Long = -1L,
    val sourcePositionSeconds: Double = 0.0,
    val durationSeconds: Double = 0.0,
    val playWhenReady: Boolean = false,
    val isPlaying: Boolean = false,
    val state: RoomPlayerState = RoomPlayerState.Idle,
    /** A seek, replan, or media mount is in flight; the position is not a decision. */
    val seekPending: Boolean = false,
    /**
     * Playback is held for a local reason (audio focus, background, sleep
     * timer, subtitle preparation). Nothing is reported while it lasts.
     */
    val suspended: Boolean = false,
    /**
     * When [sourcePositionSeconds] was read from the engine, on the binding's
     * monotonic clock, or null when unknown. A playing position is projected
     * forward from it so decisions use where the player is now.
     */
    val sampledAtMs: Long? = null,
    /**
     * The speed the engine reports it is actually playing at, or null when
     * unknown. A tunneled or passthrough audio path ignores a requested rate
     * and keeps reporting 1x.
     */
    val playbackSpeed: Double? = null,
    /**
     * Identifies the media currently mounted in the engine, or null when
     * unknown. It changes only when new media replaces the item (a load,
     * re-anchor, or seek recovery), never for a native seek within it.
     */
    val mountGeneration: Long? = null,
)

/** The player side of the binding, implemented by each platform's player. */
interface RoomPlayerPort {
    val observations: StateFlow<RoomPlayerObservation>

    /** Apply a room seek. Maps or re-anchors as the current stream requires. */
    fun seekTo(sourceSeconds: Double)

    /** Apply the room's play/pause state. Never echoed back as user intent. */
    fun setPlaying(playing: Boolean)

    /** A temporary correction rate, or null for exactly 1x. Never saved or shown. */
    fun setCorrectionRate(rate: Double?)

    /** Whether [sourceSeconds] is already buffered, so reaching it loads no new media. */
    fun isBuffered(sourceSeconds: Double): Boolean
}

/** Client timing choices from the web reference client. */
data class RoomPlaybackTiming(
    val reportIntervalMs: Long = 1_500L,
    val readyRetryMs: Long = 500L,
    val bufferingGraceMs: Long = 2_000L,
    val tickMs: Long = 250L,
    val clockWaitMs: Long = 3_000L,
    val commandQuietMs: Long = 250L,
    val attachEchoTimeoutMs: Long = 2_000L,
    val mountDeferMs: Long = 5_000L,
    val maxLeadMs: Long = 5_000L,
    /**
     * How close a guest must land to a waiting seek before sending `ready`
     * (with that seek's `command_id`). This is only the client's gate: the
     * server applies the per-command tolerance, accepting a stalled guest's
     * own catch-up seek within 5 s and a room seek only within 1 s. A room
     * seek that lands 1-5 s off is acknowledged and simply keeps waiting
     * server-side. Android has no pre-roll, so a rebuilt stream that lands a
     * few seconds short must still be able to acknowledge.
     */
    val guestSeekToleranceSeconds: Double = 5.0,
    /** The host is the authority on position; the server re-anchors to it within this distance. */
    val hostSeekToleranceSeconds: Double = 15.0,
    val stallWindowMs: Long = 5 * 60_000L,
    /** A requested rate the engine still reports as 1x this long later is unsupported on the mount. */
    val rateConfirmMs: Long = 1_000L,
    /** Reports held after a command resume after this long even if the player never shows its state. */
    val reportHoldMaxMs: Long = 3_000L,
    /** A seek request the room has not answered within this long no longer holds the timeline. */
    val pendingSeekTimeoutMs: Long = 5_000L,
)

/** Something the viewer should be told. */
sealed interface RoomPlaybackNotice {
    /** Only the host can seek; guests may play and pause only when the host allows it. */
    data class Denied(val intent: RoomTransportIntent) : RoomPlaybackNotice
    /** The room socket is reconnecting; the action was not sent. */
    data object Reconnecting : RoomPlaybackNotice
    /** Playback is paused until a usable server clock sample arrives. */
    data object ClockUnavailable : RoomPlaybackNotice
    /** The request could not be delivered. */
    data object Undelivered : RoomPlaybackNotice
}

/** What the binding is doing, for debug tooling and harness runs. Never contains credentials. */
data class RoomPlaybackDebug(
    val attachedSessionId: String? = null,
    val serverAttached: Boolean = false,
    val pendingCommandId: String? = null,
    val appliedCommandId: String? = null,
    val readinessCommandId: String? = null,
    val catchingUp: Boolean = false,
    val correctionRate: Double? = null,
    val reloadInFlight: Boolean = false,
    val stallReported: Boolean = false,
    val suspended: Boolean = false,
)

/** Outcome of a user transport intent. */
enum class RoomTransportResult { Sent, Denied, Reconnecting, NotInRoom, Ignored }

/**
 * Binds one room engagement to one player for the current playback epoch.
 *
 * It attaches the committed playback session on every socket epoch and waits
 * for the server's echo before reporting; reports what the player is actually
 * doing every 1.5 s and stays quiet while stalled, seeking, or suspended;
 * schedules accepted commands on the server clock and applies them only when
 * they execute, projected to that moment; acknowledges readiness for the exact
 * waiting command until a fresh snapshot confirms it; reports buffering after a
 * 2 s stall; acknowledges recovery while catching up; and keeps a playing
 * member on the room reference (the latest applied command) with a small
 * temporary rate, leaving large drift to seeks and reloads paced by
 * [RoomReloadBudget]. See [RoomCatchup] for the shared constants.
 *
 * Everything runs on one event loop, so no state is shared between coroutines.
 */
class RoomPlaybackBinding(
    private val room: RoomPlaybackRoom,
    private val player: RoomPlayerPort,
    parentScope: CoroutineScope,
    private val monotonicNowMs: () -> Long,
    private val wallNowMs: () -> Long,
    private val timing: RoomPlaybackTiming = RoomPlaybackTiming(),
) {
    private sealed interface Event {
        data object Tick : Event
        data object Changed : Event
        data class Command(val scheduled: ScheduledTransportCommand) : Event
        data class Execute(val token: Long) : Event
        data object QualityChanged : Event
    }

    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job)
    private val events = Channel<Event>(Channel.UNLIMITED)

    private val _notices = MutableSharedFlow<RoomPlaybackNotice>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val notices: SharedFlow<RoomPlaybackNotice> = _notices.asSharedFlow()

    private val _catchingUp = MutableStateFlow(false)

    /** This viewer is catching up on its own while the room keeps going. */
    val catchingUp: StateFlow<Boolean> = _catchingUp.asStateFlow()

    private val _debug = MutableStateFlow(RoomPlaybackDebug())

    /** The binding's state for debug tooling. */
    val debug: StateFlow<RoomPlaybackDebug> = _debug.asStateFlow()

    private val _offerLowerQuality = MutableStateFlow(false)

    /** Two sustained stalls within five minutes: offer one step down the same file's ladder. */
    val offerLowerQuality: StateFlow<Boolean> = _offerLowerQuality.asStateFlow()

    private val _pendingSeekSeconds = MutableStateFlow<Double?>(null)

    @kotlin.concurrent.Volatile
    private var pendingSeekRequestedAtMs = NEVER_MS

    /**
     * The target of this member's last seek request that the room has not
     * answered yet, or null. Until the room's command executes here the
     * player still shows the old position, so relative skips start from this
     * ([relativeSeekOrigin]) and the timeline holds on it instead of snapping
     * back. Cleared when the room's seek to that target executes, on a new
     * epoch or a disconnect, and after [RoomPlaybackTiming.pendingSeekTimeoutMs].
     * An earlier request's seek does not clear a later target.
     */
    val pendingSeekSeconds: StateFlow<Double?> = _pendingSeekSeconds.asStateFlow()

    /** Where a relative skip starts: the pending seek target, else [liveSeconds]. */
    fun relativeSeekOrigin(liveSeconds: Double): Double = _pendingSeekSeconds.value ?: liveSeconds

    // ---- loop state (touched only by the event loop) ----
    private var attachedKey: RoomDeliveryKey? = null
    private var lastAttachAttemptMs = NEVER_MS
    private var lastReportMs = NEVER_MS
    private var lastSeenCommandId: String? = null
    private var lastCommandConnection: Pair<Long, Long>? = null
    private var pending: PendingCommand? = null
    private var executionJob: Job? = null
    private var nextToken = 0L
    private var applied: TransportCommand? = null
    private var appliedAtMs = NEVER_MS

    // The room position this member follows: the latest applied command.
    private var reference: RoomReference? = null
    private var lastLoopMs = NEVER_MS

    // Last moment the player was seeking or mounting; the drift loop waits
    // for a command's seek to finish, not only for the command itself.
    private var lastBusyMs = NEVER_MS

    // This mount plays on at 1x whatever rate is asked (tunneling,
    // passthrough audio). Sticky until the next mount, epoch, quality change,
    // or reload; the loop then corrects with in-place seeks instead.
    private var rateUnsupported = false
    private var rateRequestedAtMs = NEVER_MS
    private var lastLoopSeekMs = NEVER_MS

    // Rate-incapable correction by seeks. A seek takes time to land while
    // the room keeps playing, so each aims ahead by the shortfall the last
    // one left, and seeks that fail to converge back off.
    private var fallbackLeadSeconds = 0.0
    private var fallbackIntervalMs = RoomCatchup.LOOP_SETTLE_MS
    private var fallbackSeekToMeasure = false

    // Reports after a command: none until the player shows the command's
    // play state, so a stale sample never speaks for the new command.
    private var reportHold: ReportHold? = null
    private var reportedCommandId: String? = null
    private var mountSessionId: String? = null
    private var mountGeneration: Long? = null
    private var readiness: Readiness? = null
    private var lastCatchUpAckMs = NEVER_MS
    private var stallStartedAtMs: Long? = null
    private var stallReported = false
    private val sustainedStalls = ArrayDeque<Long>()

    // A Play correction that needs new media while the reload budget is
    // spacing reloads. It runs once the budget allows, unless the viewer
    // comes within reach of the room first or a newer command replaces it.
    private var reloadDeferred = false
    private var rateApplied: Double? = null
    private val reloads = RoomReloadBudget()
    private var wasSuspended = false
    private var lastRevision = NEVER_MS
    private var lastContinuityCheckMs = NEVER_MS

    private data class PendingCommand(
        val token: Long,
        val scheduled: ScheduledTransportCommand,
        val executeAtServerMs: Long?,
    ) {
        /** When execution was first held for a media mount in flight. */
        var deferredSinceMs: Long? = null
    }

    private data class ReportHold(val commandId: String, val playing: Boolean, val sinceMs: Long) {
        /** Past the deadline: reports flow again, but the command is named only once the player shows it. */
        var released: Boolean = false
    }

    private data class Readiness(
        val commandId: String?,
        val seekTarget: Double?,
        val armedWith: RoomSnapshot?,
        var lastSentMs: Long = NEVER_MS,
    )

    fun start() {
        scope.launch { loop() }
        scope.launch { room.roomSnapshot.collect { events.send(Event.Changed) } }
        scope.launch { room.connectionState.collect { events.send(Event.Changed) } }
        scope.launch { room.roomDeliveryEcho.collect { events.send(Event.Changed) } }
        scope.launch { room.clock.collect { events.send(Event.Changed) } }
        scope.launch { player.observations.collect { events.send(Event.Changed) } }
        scope.launch { room.latestTransportCommand.collect { it?.let { command -> events.send(Event.Command(command)) } } }
        scope.launch {
            while (true) {
                delay(timing.tickMs)
                events.send(Event.Tick)
            }
        }
    }

    /** Stop this binding. The room engagement continues; the correction rate returns to 1x. */
    fun dispose() {
        job.cancel()
        if (rateApplied != null) player.setCorrectionRate(null)
        rateApplied = null
    }

    /**
     * Acknowledge that the viewer changed quality: the offer and reload pacing
     * start over. The offer hides at once; the loop clears its own state.
     */
    fun onQualityChanged() {
        _offerLowerQuality.value = false
        events.trySend(Event.QualityChanged)
    }

    // ---- user intents ---------------------------------------------------------

    /** Ask the room to play or pause. The player changes only when the room's command arrives. */
    fun requestPlayPause(pause: Boolean): RoomTransportResult {
        if (!pause && player.observations.value.state == RoomPlayerState.Ended) return RoomTransportResult.Ignored
        return request(RoomTransportIntent.PlayPause, if (pause) TransportAction.Pause else TransportAction.Play, null, pause)
    }

    /** Ask the room to seek (host only). */
    fun requestSeek(sourceSeconds: Double): RoomTransportResult {
        if (!sourceSeconds.isFinite() || sourceSeconds < 0.0) return RoomTransportResult.Ignored
        val paused = room.roomSnapshot.value?.isPaused ?: true
        val result = request(RoomTransportIntent.Seek, TransportAction.Seek, sourceSeconds, paused)
        if (result == RoomTransportResult.Sent) {
            pendingSeekRequestedAtMs = monotonicNowMs()
            _pendingSeekSeconds.value = sourceSeconds
        }
        return result
    }

    /** Drop the pending seek target when it predates [beforeMs] (any target when null). */
    private fun clearPendingSeek(beforeMs: Long? = null) {
        val target = _pendingSeekSeconds.value ?: return
        if (beforeMs != null && pendingSeekRequestedAtMs > beforeMs) return
        _pendingSeekSeconds.compareAndSet(target, null)
    }

    /** Drop the pending seek target when [command] is the room's seek to it. */
    private fun answerPendingSeek(command: TransportCommand) {
        val target = _pendingSeekSeconds.value ?: return
        if (command.action != TransportAction.Seek || abs(command.positionSeconds - target) > PENDING_SEEK_MATCH_SECONDS) return
        _pendingSeekSeconds.compareAndSet(target, null)
    }

    private fun request(
        intent: RoomTransportIntent,
        action: TransportAction,
        positionSeconds: Double?,
        isPaused: Boolean,
    ): RoomTransportResult {
        val snapshot = room.roomSnapshot.value ?: return RoomTransportResult.NotInRoom
        if (snapshot.phase != RoomPhase.Playing) return RoomTransportResult.NotInRoom
        if (!roomTransportAuthorized(snapshot, intent)) {
            _notices.tryEmit(RoomPlaybackNotice.Denied(intent))
            return RoomTransportResult.Denied
        }
        val authorization = room.currentTransportAuthorization()
        if (authorization == null || !room.connectionState.value.writable) {
            _notices.tryEmit(RoomPlaybackNotice.Reconnecting)
            return RoomTransportResult.Reconnecting
        }
        val position = positionSeconds ?: player.observations.value.sourcePositionSeconds
        scope.launch {
            val delivered = room.transportRequestForAuthorization(authorization, intent, action.wire, position, isPaused)
            if (!delivered) _notices.tryEmit(RoomPlaybackNotice.Undelivered)
        }
        return RoomTransportResult.Sent
    }

    // ---- event loop -----------------------------------------------------------

    private suspend fun loop() {
        for (event in events) {
            when (event) {
                is Event.Command -> accept(event.scheduled)
                is Event.Execute -> execute(event.token)
                Event.QualityChanged -> {
                    sustainedStalls.clear()
                    reloads.reset()
                    clearRateUnsupported()
                    _offerLowerQuality.value = false
                }
                Event.Tick, Event.Changed -> Unit
            }
            step()
            publishDebug()
        }
    }

    private fun publishDebug() {
        val obs = player.observations.value
        val echo = room.roomDeliveryEcho.value
        _debug.value = RoomPlaybackDebug(
            attachedSessionId = attachedKey?.playbackSessionId,
            serverAttached = attachedKey != null && echo?.playbackSessionId == attachedKey?.playbackSessionId &&
                echo?.connectionEpoch == attachedKey?.connectionEpoch,
            pendingCommandId = pending?.scheduled?.command?.commandId,
            appliedCommandId = applied?.commandId,
            readinessCommandId = readiness?.commandId,
            catchingUp = _catchingUp.value,
            correctionRate = rateApplied,
            reloadInFlight = reloads.inFlight,
            stallReported = stallReported,
            suspended = obs.suspended,
        )
    }

    private suspend fun step() {
        val now = monotonicNowMs()
        val snapshot = room.roomSnapshot.value
        val connection = room.connectionState.value
        val obs = player.observations.value
        if (snapshot == null || snapshot.phase != RoomPhase.Playing) {
            resetEpoch()
            return
        }
        if (snapshot.selectionRevision != lastRevision) {
            lastRevision = snapshot.selectionRevision
            resetEpoch()
            // A new title is a new stream: the old one's reload backoff and
            // stall history say nothing about it.
            reloads.reset()
            sustainedStalls.clear()
            _offerLowerQuality.value = false
        }
        // A seek request the room never answered (dropped, refused
        // server-side) stops holding the timeline.
        if (now - pendingSeekRequestedAtMs >= timing.pendingSeekTimeoutMs) clearPendingSeek(beforeMs = now - timing.pendingSeekTimeoutMs)
        if (now - lastContinuityCheckMs >= 1_000L) {
            lastContinuityCheckMs = now
            room.checkClockContinuity()
        }

        val sessionId = obs.sessionId?.takeIf { obs.selectionRevision == snapshot.selectionRevision }
        val key = sessionId?.takeIf { connection.writable }?.let {
            RoomDeliveryKey(connection.generation, connection.epoch, it)
        }

        // A local suspension ends: resynchronize before reporting again. While
        // the room plays, re-attaching makes the server send a command at the
        // room position; a paused room sends nothing, so align locally.
        if (wasSuspended && !obs.suspended) {
            attachedKey = null
            if (snapshot.isPaused) alignPausedToAnchor(snapshot, obs)
        }
        if (obs.suspended && !wasSuspended) stopCorrections(now)
        wasSuspended = obs.suspended

        val echo = room.roomDeliveryEcho.value
        val echoed = key != null && echo != null &&
            echo.connectionGeneration == key.connectionGeneration &&
            echo.connectionEpoch == key.connectionEpoch &&
            echo.playbackSessionId == key.playbackSessionId
        // Attach on every socket epoch and session change; resend if the
        // server never echoes it.
        val attachDue = attachedKey != key ||
            (!echoed && now - lastAttachAttemptMs >= timing.attachEchoTimeoutMs)
        if (key != null && attachDue && now - lastAttachAttemptMs >= timing.readyRetryMs) {
            lastAttachAttemptMs = now
            if (room.attachSession(key.playbackSessionId)) attachedKey = key
        }
        val serverAttached = key != null && attachedKey == key && echoed
        if (!serverAttached || sessionId == null) {
            // No room to converge toward while detached or reconnecting; a
            // correction rate left running would carry this viewer past it.
            clearRate()
            if (!connection.writable) clearPendingSeek()
            return
        }

        val playable = !obs.seekPending && (obs.state == RoomPlayerState.Ready || obs.state == RoomPlayerState.Ended)
        val executedLatest = pending == null
        val stalled = obs.state == RoomPlayerState.Buffering && obs.playWhenReady && !obs.seekPending
        val positionOk = { readiness: Readiness ->
            val target = readiness.seekTarget
            val tolerance = if (snapshot.selfRole == MemberRole.Host) {
                timing.hostSeekToleranceSeconds
            } else {
                timing.guestSeekToleranceSeconds
            }
            target == null || obs.state == RoomPlayerState.Ended || abs(obs.sourcePositionSeconds - target) <= tolerance
        }
        val paused = obs.state == RoomPlayerState.Ended || !obs.playWhenReady

        // Readiness for the waiting command.
        val waiting = readiness
        if (waiting != null) {
            val settled = snapshot.playbackState != RoomPlaybackState.Waiting ||
                (snapshot !== waiting.armedWith && snapshot.selfMember?.isReady == true)
            if (settled) {
                readiness = null
            } else if (executedLatest && playable && !obs.suspended && positionOk(waiting) &&
                now - waiting.lastSentMs >= timing.readyRetryMs
            ) {
                waiting.lastSentMs = now
                room.ready(sessionId, obs.sourcePositionSeconds, paused, waiting.commandId)
                room.stateReport(sessionId, obs.sourcePositionSeconds, paused, waiting.commandId, isReady = true)
                lastReportMs = now
            }
        }

        // Catching up after the room stopped waiting for this viewer.
        val catchingUp = snapshot.selfCatchingUp
        _catchingUp.value = catchingUp
        if (catchingUp && readiness == null && snapshot.selfMember?.isReady != true && executedLatest && playable &&
            !obs.suspended && now - lastCatchUpAckMs >= timing.readyRetryMs
        ) {
            lastCatchUpAckMs = now
            room.ready(sessionId, obs.sourcePositionSeconds, paused, lastSeenCommandId)
        }

        // Buffering: stalls under the grace stay local; a longer one is reported once.
        if (stalled && snapshot.playbackState == RoomPlaybackState.Playing && !obs.suspended) {
            val started = stallStartedAtMs ?: now.also { stallStartedAtMs = it }
            if (!stallReported && now - started >= timing.bufferingGraceMs) {
                stallReported = true
                room.buffering(sessionId, obs.sourcePositionSeconds, false)
                recordSustainedStall(now)
            }
        } else if (!stalled) {
            stallStartedAtMs = null
            stallReported = false
        }

        // Reload pacing and the continuous drift loop. New media (a new
        // session, or a same-session remount such as a server seek recovery)
        // may honour a rate again, so rate support is measured afresh. A
        // native seek within the mounted item, including the fallback's own
        // in-place seeks, changes nothing.
        if (obs.seekPending) lastBusyMs = now
        if (obs.sessionId != mountSessionId || obs.mountGeneration != mountGeneration) {
            mountSessionId = obs.sessionId
            mountGeneration = obs.mountGeneration
            clearRateUnsupported()
        }
        if (reloads.inFlight && obs.isPlaying && reloads.landed(obs.sourcePositionSeconds)) reloads.land(now)
        // A reload that never lands must not hold corrections off for good,
        // nor delay its replacement.
        if (reloads.stale(now)) reloads.expire(now)
        checkRateHonoured(now, obs)
        retryDeferredReload(now, obs)
        driftLoop(now, snapshot, obs, stalled)

        // Ordinary reports: only positions that are decisions.
        val readinessPending = readiness != null || (catchingUp && snapshot.selfMember?.isReady != true)
        val quiet = pending?.let { abs(expectedLocalExecuteMs(it) - now) <= timing.commandQuietMs } ?: false
        if (!obs.suspended && !stalled && !obs.seekPending && !(readinessPending && !playable) && !quiet &&
            !holdingReportsFor(now, obs, paused) && now - lastReportMs >= timing.reportIntervalMs
        ) {
            lastReportMs = now
            room.stateReport(sessionId, obs.sourcePositionSeconds, paused, reportedCommandId, isReady = false)
        }
    }

    private fun resetEpoch() {
        attachedKey = null
        pending = null
        executionJob?.cancel()
        applied = null
        reference = null
        readiness = null
        reloadDeferred = false
        clearRateUnsupported()
        reportHold = null
        reportedCommandId = null
        stallStartedAtMs = null
        stallReported = false
        _catchingUp.value = false
        clearPendingSeek()
        clearRate()
    }

    // ---- commands -------------------------------------------------------------

    private fun accept(scheduled: ScheduledTransportCommand) {
        val command = scheduled.command
        val connection = room.connectionState.value
        if (scheduled.connection.generation != connection.generation || scheduled.connection.epoch != connection.epoch) return
        val commandConnection = connection.generation to connection.epoch
        if (command.commandId.isBlank() ||
            (command.commandId == lastSeenCommandId && commandConnection == lastCommandConnection)
        ) return
        lastSeenCommandId = command.commandId
        lastCommandConnection = commandConnection
        // Rate and play state change only when the command executes. A held
        // or running reload is superseded by the newer room position.
        reloadDeferred = false
        if (reloads.inFlight) reloads.abandon(monotonicNowMs())
        val token = ++nextToken
        val queued = PendingCommand(token, scheduled, scheduled.executeAtMs)
        pending = queued
        readiness = null
        scheduleExecution(queued)
    }

    private fun scheduleExecution(command: PendingCommand) {
        executionJob?.cancel()
        if (room.clock.value.offsetMs == null) player.setPlaying(false)
        executionJob = scope.launch {
            val waitStarted = monotonicNowMs()
            var notified = false
            // Socket pings keep trying. Retain the command until one supplies
            // the server clock; a device clock is never a usable substitute.
            while (room.clock.value.offsetMs == null) {
                if (!notified && monotonicNowMs() - waitStarted >= timing.clockWaitMs) {
                    _notices.tryEmit(RoomPlaybackNotice.ClockUnavailable)
                    notified = true
                }
                delay(timing.tickMs)
            }
            val serverNow = serverNowMs() ?: return@launch
            delay(((command.executeAtServerMs ?: serverNow) - serverNow).coerceIn(0L, timing.maxLeadMs))
            events.send(Event.Execute(command.token))
        }
    }

    private fun execute(token: Long) {
        val scheduled = pending?.takeIf { it.token == token } ?: return
        // A command that lands while media is mounting or seeking would act
        // on media that is being replaced; hold it briefly until it settles.
        // A late Play still advances by the full elapsed server time.
        if (player.observations.value.seekPending) {
            val now = monotonicNowMs()
            val since = scheduled.deferredSinceMs ?: now.also { scheduled.deferredSinceMs = it }
            if (now - since < timing.mountDeferMs) {
                scope.launch {
                    delay(timing.tickMs)
                    events.send(Event.Execute(token))
                }
                return
            }
        }
        val command = scheduled.scheduled.command
        val snapshot = room.roomSnapshot.value ?: return
        val connection = room.connectionState.value
        val obs = player.observations.value
        val sessionId = obs.sessionId
        if (
            snapshot.phase != RoomPhase.Playing ||
            command.selectionRevision != snapshot.selectionRevision ||
            obs.selectionRevision != snapshot.selectionRevision ||
            (command.sessionId.isNotBlank() && command.sessionId != sessionId) ||
            // A command accepted on a socket that has since been replaced is
            // dropped: the server answers this member's re-attach on the new
            // socket with a targeted sync (its waiting command again, or a
            // play/pause at the room position), and running both would apply
            // the command twice (a second seek rebuilds a copy-remux stream).
            scheduled.scheduled.connection.generation != connection.generation ||
            scheduled.scheduled.connection.epoch != connection.epoch
        ) {
            pending = null
            return
        }
        val serverNow = serverNowMs()
        if (serverNow == null) {
            scheduleExecution(scheduled)
            return
        }
        pending = null
        val now = monotonicNowMs()
        val commandPlays = when (command.action) {
            TransportAction.Play -> command.playbackState != RoomPlaybackState.Waiting
            TransportAction.Pause -> false
            else -> command.playbackState == RoomPlaybackState.Playing
        }
        val next = RoomReference(
            positionSeconds = command.positionSeconds,
            executeAtServerMs = scheduled.executeAtServerMs ?: serverNow,
            playing = command.action != TransportAction.Pause && command.playbackState == RoomPlaybackState.Playing,
        )
        // Late execution projects forward: the room kept playing meanwhile.
        val target = next.positionAt(serverNow)
        val memberPlaying = obs.isPlaying && !obs.seekPending && !obs.suspended
        val drift = target - localPositionNow(obs, now)
        val decision = RoomCatchup.decideCommand(
            action = command.action,
            driftSeconds = drift,
            memberPlaying = memberPlaying,
            commandPlays = commandPlays,
            targetReachableInPlace = command.action != TransportAction.Seek && player.isBuffered(target),
            memberIntendsToPlay = obs.playWhenReady && !obs.suspended,
            rateCapable = !rateUnsupported,
        )
        // Only a playing member keeps or starts a nudge; everything else runs
        // at exactly 1x. A pause drops the rate while still playing.
        val keepsRate = decision is RoomCatchup.Decision.Nudge ||
            (decision == RoomCatchup.Decision.None && memberPlaying && commandPlays)
        if (!keepsRate) setRate(null)
        if (!commandPlays) player.setPlaying(false)
        when (decision) {
            RoomCatchup.Decision.Seek -> {
                // A playing member's in-place correction on a rate-incapable
                // mount lands late by the seek time; aim ahead by it. Room
                // seeks are barriers and land exactly.
                val fallback = rateUnsupported && memberPlaying && commandPlays && next.playing &&
                    command.action != TransportAction.Seek
                if (fallback) fallbackSeek(next.positionAt(serverNow), now) else player.seekTo(target)
            }
            RoomCatchup.Decision.SeekOrReload -> when {
                player.isBuffered(target) -> player.seekTo(target)
                // A correction that must load new media is paced.
                reloads.allowed(now) -> beginReload(target, now, obs)
                // Held until the budget allows, so the viewer is not left
                // playing at the wrong position with nothing to fix it.
                else -> reloadDeferred = true
            }
            is RoomCatchup.Decision.Nudge -> setRate(decision.rate)
            RoomCatchup.Decision.None -> if (memberPlaying && abs(drift) <= RoomCatchup.DEADBAND_SECONDS) reloads.settle()
        }
        // A local suspension holds playback: the viewer resumes on their own and
        // the binding re-attaches, so the server resends the room position.
        if (commandPlays && !obs.suspended) player.setPlaying(true)
        applied = command
        appliedAtMs = now
        reference = next
        // The room's seek to this member's latest target answers it; the
        // player now shows that position. An earlier skip in the same burst
        // lands first and leaves the newer target in place.
        answerPendingSeek(command)
        reportHold = ReportHold(command.commandId, playing = commandPlays && !obs.suspended, sinceMs = now)
        if (command.playbackState == RoomPlaybackState.Waiting) {
            readiness = Readiness(
                commandId = command.commandId,
                seekTarget = target.takeIf { command.action == TransportAction.Seek },
                armedWith = snapshot,
            )
        }
    }

    /**
     * Keep a playing member on the room reference with a small temporary
     * rate. It runs every [RoomCatchup.LOOP_INTERVAL_MS] once the last command
     * (or its own last seek) has settled and returns the rate to exactly 1x
     * whenever its conditions stop holding. Drift beyond
     * [RoomCatchup.HARD_SEEK_SECONDS] is still nudged; the server's correction
     * command fixes it properly. It seeks only on a mount that ignores rate,
     * and then only to a buffered target.
     */
    private fun driftLoop(now: Long, snapshot: RoomSnapshot, obs: RoomPlayerObservation, stalled: Boolean) {
        // A pending command owns the next change; until it executes the
        // current rate stands, even if a snapshot already describes it.
        if (pending != null) return
        val ref = reference
        val serverNow = serverNowMs()
        val reloading = reloads.inFlight && !reloads.stale(now)
        val holds = snapshot.playbackState == RoomPlaybackState.Playing &&
            obs.isPlaying && obs.state == RoomPlayerState.Ready &&
            !obs.seekPending && !obs.suspended && !stalled &&
            !reloading && !reloadDeferred
        if (ref == null || !ref.playing || serverNow == null || !holds) {
            setRate(null)
            return
        }
        if (now - maxOf(appliedAtMs, lastBusyMs, lastLoopSeekMs) < RoomCatchup.LOOP_SETTLE_MS) return
        if (now - lastLoopMs < RoomCatchup.LOOP_INTERVAL_MS) return
        lastLoopMs = now
        val target = ref.positionAt(serverNow)
        val drift = target - localPositionNow(obs, now)
        if (abs(drift) <= RoomCatchup.DEADBAND_SECONDS) reloads.settle()
        if (rateUnsupported && fallbackSeekToMeasure) {
            // The last fallback seek has landed and settled; at 1x its
            // shortfall stays put, so measure it now.
            fallbackSeekToMeasure = false
            val step = RoomCatchup.fallbackAfterSeek(fallbackLeadSeconds, fallbackIntervalMs, drift)
            fallbackLeadSeconds = step.leadSeconds
            fallbackIntervalMs = step.intervalMs
        }
        val decision = RoomCatchup.loopDecision(
            driftSeconds = drift,
            nudging = rateApplied != null,
            rateCapable = !rateUnsupported,
            // Reachable if either the led or the plain target is; the seek
            // itself takes the led one only when that is reachable too.
            targetReachableInPlace = rateUnsupported &&
                (player.isBuffered(target) || (fallbackLeadSeconds > 0.0 && player.isBuffered(target + fallbackLeadSeconds))),
        )
        when (decision) {
            is RoomCatchup.Decision.Nudge -> setRate(decision.rate)
            RoomCatchup.Decision.Seek -> {
                if (now - maxOf(lastBusyMs, lastLoopSeekMs) >= fallbackIntervalMs) fallbackSeek(target, now)
            }
            else -> setRate(null)
        }
    }

    /**
     * Seek a rate-incapable mount to the room, aimed ahead by the last
     * measured shortfall when that point is itself reachable in place. A led
     * target outside the mounted range would rebuild the stream outside the
     * reload budget, so the plain room position (which the caller found
     * reachable) is taken instead.
     */
    private fun fallbackSeek(roomSeconds: Double, now: Long) {
        setRate(null)
        lastLoopSeekMs = now
        fallbackSeekToMeasure = true
        val led = roomSeconds + fallbackLeadSeconds
        player.seekTo(if (fallbackLeadSeconds > 0.0 && player.isBuffered(led)) led else roomSeconds)
    }

    private fun clearRateUnsupported() {
        rateUnsupported = false
        fallbackLeadSeconds = 0.0
        fallbackIntervalMs = RoomCatchup.LOOP_SETTLE_MS
        fallbackSeekToMeasure = false
    }

    /**
     * Whether ordinary reports wait after a command. The command executed,
     * but the player's sample may still show the state it replaced; a report
     * naming the command with that state would let the server take the old
     * state for the new decision (a host's own pause undone by its stale
     * "playing" report). Reports resume once [RoomPlaybackTiming.commandQuietMs]
     * has passed and the player shows the command's play state with no seek
     * or mount in flight, and from then on name the command. A player that
     * is slower than [RoomPlaybackTiming.reportHoldMaxMs] reports again from
     * then on, naming only the last command it was seen to follow, until it
     * does show the command's state.
     */
    private fun holdingReportsFor(now: Long, obs: RoomPlayerObservation, paused: Boolean): Boolean {
        val hold = reportHold ?: return false
        val settled = now - hold.sinceMs >= timing.commandQuietMs && !obs.seekPending && hold.playing == !paused
        if (settled) {
            reportedCommandId = hold.commandId
            reportHold = null
            return false
        }
        if (now - hold.sinceMs >= timing.reportHoldMaxMs) hold.released = true
        return !hold.released
    }

    /**
     * A nudge the engine still reports as 1x after [RoomPlaybackTiming.rateConfirmMs]
     * of ready playback means this mount cannot change rate (Media3 applies
     * speed only to PCM output that is not tunneled). Stop asking.
     */
    private fun checkRateHonoured(now: Long, obs: RoomPlayerObservation) {
        // A pending command owns the next change; its lead must not reset the rate.
        if (pending != null) return
        val requested = rateApplied ?: return
        val speed = obs.playbackSpeed ?: return
        if (!obs.isPlaying || obs.state != RoomPlayerState.Ready || obs.seekPending) return
        if ((obs.sampledAtMs ?: now) - rateRequestedAtMs < timing.rateConfirmMs) return
        if (abs(speed - requested) > RATE_TOLERANCE && abs(speed - 1.0) <= RATE_TOLERANCE) {
            rateUnsupported = true
            setRate(null)
        }
    }

    /**
     * Run a held Play correction once the reload budget allows, aimed at where
     * the room is now. Drift that shrank within reach of a nudge is left to
     * the drift loop; a target that became buffered is a plain seek.
     */
    private fun retryDeferredReload(now: Long, obs: RoomPlayerObservation) {
        if (!reloadDeferred) return
        if (obs.seekPending || !obs.isPlaying || obs.suspended) return
        val ref = reference
        val serverNow = serverNowMs() ?: return
        if (ref == null || !ref.playing) {
            reloadDeferred = false
            return
        }
        val expected = ref.positionAt(serverNow)
        val drift = expected - localPositionNow(obs, now)
        when {
            abs(drift) <= RoomCatchup.HARD_SEEK_SECONDS -> {
                reloadDeferred = false
                if (abs(drift) <= RoomCatchup.DEADBAND_SECONDS) reloads.settle()
            }
            player.isBuffered(expected) -> {
                reloadDeferred = false
                player.seekTo(expected)
            }
            reloads.allowed(now) -> {
                reloadDeferred = false
                beginReload(expected, now, obs)
            }
        }
    }

    private fun beginReload(target: Double, now: Long, obs: RoomPlayerObservation) {
        // A reload mounts new media, which may honour a rate again.
        clearRateUnsupported()
        val aimed = reloads.begin(target, now, obs.durationSeconds.takeIf { it > 0.0 })
        player.seekTo(aimed)
        reloads.noteLoading()
    }

    private fun stopCorrections(now: Long) {
        clearRate()
        reloadDeferred = false
        if (reloads.inFlight) reloads.abandon(now)
    }

    private fun clearRate() = setRate(null)

    /**
     * The local position now. A playing engine sample is projected forward
     * by the time since it was read, at the current rate, so a decision does
     * not use a position up to one sample interval old.
     */
    private fun localPositionNow(obs: RoomPlayerObservation, now: Long): Double {
        val sampledAt = obs.sampledAtMs
        if (sampledAt == null || !obs.isPlaying || obs.seekPending || obs.state != RoomPlayerState.Ready) {
            return obs.sourcePositionSeconds
        }
        val elapsedMs = (now - sampledAt).coerceIn(0L, MAX_SAMPLE_PROJECTION_MS)
        return obs.sourcePositionSeconds + elapsedMs / 1000.0 * (rateApplied ?: 1.0)
    }

    private fun setRate(rate: Double?) {
        val normalized = rate?.takeIf { abs(it - 1.0) > 0.001 }
        if (normalized == rateApplied) return
        // The confirmation window starts when a nudge starts; adjusting a
        // running nudge must not keep restarting it.
        if (normalized != null && rateApplied == null) rateRequestedAtMs = monotonicNowMs()
        rateApplied = normalized
        player.setCorrectionRate(normalized)
    }

    private fun alignPausedToAnchor(snapshot: RoomSnapshot, obs: RoomPlayerObservation) {
        // The applied command is the room reference; the snapshot anchor is
        // only a fallback when this epoch has applied none.
        val anchor = reference?.takeIf { !it.playing }?.positionSeconds ?: snapshot.anchorPositionSeconds
        if (abs(anchor - obs.sourcePositionSeconds) > RoomCatchup.PAUSED_SEEK_SECONDS && player.isBuffered(anchor)) {
            player.seekTo(anchor)
        }
        player.setPlaying(false)
    }

    private fun recordSustainedStall(now: Long) {
        sustainedStalls.addLast(now)
        while (sustainedStalls.isNotEmpty() && now - sustainedStalls.first() > timing.stallWindowMs) {
            sustainedStalls.removeFirst()
        }
        if (sustainedStalls.size >= 2) _offerLowerQuality.value = true
    }

    private fun serverNowMs(): Long? = room.clock.value.offsetMs?.let { wallNowMs() + it }

    private companion object {
        /** A "last sent" time far enough in the past that any interval has elapsed, without overflow. */
        const val NEVER_MS = -1_000_000_000_000L

        /** A sample older than this is not projected further; the engine sampler has stalled. */
        const val MAX_SAMPLE_PROJECTION_MS = 1_000L

        /** Speeds closer than this are the same rate. */
        const val RATE_TOLERANCE = 0.001

        /**
         * A room seek this close to the pending target answers it. The server
         * folds a repeat seek this close to its waiting seek into that seek.
         */
        const val PENDING_SEEK_MATCH_SECONDS = 1.0
    }

    private fun expectedLocalExecuteMs(pending: PendingCommand): Long {
        val serverNow = serverNowMs() ?: return monotonicNowMs()
        return monotonicNowMs() + ((pending.executeAtServerMs ?: serverNow) - serverNow)
    }
}
