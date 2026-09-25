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
    val maxLeadMs: Long = 5_000L,
    val guestSeekToleranceSeconds: Double = 1.0,
    val hostSeekToleranceSeconds: Double = 15.0,
    val stallWindowMs: Long = 5 * 60_000L,
)

/** Something the viewer should be told. */
sealed interface RoomPlaybackNotice {
    /** Only the host can seek; guests may play and pause only when the host allows it. */
    data class Denied(val intent: RoomTransportIntent) : RoomPlaybackNotice
    /** The room socket is reconnecting; the action was not sent. */
    data object Reconnecting : RoomPlaybackNotice
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
 * schedules accepted commands on the server clock and advances a late Play;
 * acknowledges readiness for the exact waiting command until a fresh snapshot
 * confirms it; reports buffering after a 2 s stall; acknowledges recovery
 * while catching up; and converges small drift by a temporary rate with media
 * reloads paced by [RoomReloadBudget].
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

    // ---- loop state (touched only by the event loop) ----
    private var attachedKey: RoomDeliveryKey? = null
    private var lastAttachAttemptMs = NEVER_MS
    private var lastReportMs = NEVER_MS
    private var lastSeenCommandId: String? = null
    private var pending: PendingCommand? = null
    private var nextToken = 0L
    private var applied: TransportCommand? = null
    private var appliedAtMs = 0L
    private var readiness: Readiness? = null
    private var lastCatchUpAckMs = NEVER_MS
    private var stallStartedAtMs: Long? = null
    private var stallReported = false
    private val sustainedStalls = ArrayDeque<Long>()
    private var convergence: Convergence? = null
    private var rateApplied: Double? = null
    private val reloads = RoomReloadBudget()
    private var wasSuspended = false
    private var lastRevision = NEVER_MS
    private var lastContinuityCheckMs = NEVER_MS

    private data class PendingCommand(
        val token: Long,
        val scheduled: ScheduledTransportCommand,
        val executeAtServerMs: Long,
    )

    private data class Readiness(
        val commandId: String?,
        val seekTarget: Double?,
        val armedWith: RoomSnapshot?,
        var lastSentMs: Long = NEVER_MS,
    )

    private data class Convergence(val targetSeconds: Double, val executeAtMonotonicMs: Long)

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

    /** Acknowledge that the viewer changed quality: the offer and reload pacing start over. */
    fun onQualityChanged() {
        _offerLowerQuality.value = false
        sustainedStalls.clear()
        events.trySend(Event.Changed)
        reloadsResetRequested = true
    }

    @kotlin.concurrent.Volatile
    private var reloadsResetRequested = false

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
        return request(RoomTransportIntent.Seek, TransportAction.Seek, sourceSeconds, paused)
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
        if (reloadsResetRequested) {
            reloadsResetRequested = false
            reloads.reset()
        }
        if (snapshot == null || snapshot.phase != RoomPhase.Playing) {
            resetEpoch()
            return
        }
        if (snapshot.selectionRevision != lastRevision) {
            lastRevision = snapshot.selectionRevision
            resetEpoch()
        }
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
        if (!serverAttached || sessionId == null) return

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

        // Rate convergence and reload pacing.
        converge(now, obs)

        // Ordinary reports: only positions that are decisions.
        val readinessPending = readiness != null || (catchingUp && snapshot.selfMember?.isReady != true)
        val quiet = pending?.let { abs(expectedLocalExecuteMs(it) - now) <= timing.commandQuietMs } ?: false
        if (!obs.suspended && !stalled && !obs.seekPending && !(readinessPending && !playable) && !quiet &&
            now - lastReportMs >= timing.reportIntervalMs
        ) {
            lastReportMs = now
            room.stateReport(sessionId, obs.sourcePositionSeconds, paused, null, isReady = false)
        }
    }

    private fun resetEpoch() {
        attachedKey = null
        pending = null
        applied = null
        readiness = null
        stallStartedAtMs = null
        stallReported = false
        _catchingUp.value = false
        if (convergence != null || rateApplied != null) {
            convergence = null
            setRate(null)
        }
    }

    // ---- commands -------------------------------------------------------------

    private fun accept(scheduled: ScheduledTransportCommand) {
        val command = scheduled.command
        if (command.commandId.isBlank() || command.commandId == lastSeenCommandId) return
        lastSeenCommandId = command.commandId
        val connection = room.connectionState.value
        if (scheduled.connection.generation != connection.generation || scheduled.connection.epoch != connection.epoch) return
        stopCorrections(monotonicNowMs())
        val token = ++nextToken
        val executeAt = scheduled.executeAtMs ?: serverNowMs() ?: wallNowMs()
        pending = PendingCommand(token, scheduled, executeAt)
        readiness = null
        scope.launch {
            // Without a clock sample, wait briefly for one rather than assume
            // a zero offset.
            val waitStarted = monotonicNowMs()
            while (room.clock.value.offsetMs == null && monotonicNowMs() - waitStarted < timing.clockWaitMs) {
                delay(timing.tickMs)
            }
            val serverNow = serverNowMs() ?: wallNowMs()
            delay((executeAt - serverNow).coerceIn(0L, timing.maxLeadMs))
            events.send(Event.Execute(token))
        }
    }

    private fun execute(token: Long) {
        val scheduled = pending?.takeIf { it.token == token } ?: return
        pending = null
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
            scheduled.scheduled.connection.generation != connection.generation ||
            scheduled.scheduled.connection.epoch != connection.epoch
        ) {
            return
        }
        val now = monotonicNowMs()
        val advancing = command.action != TransportAction.Pause && command.playbackState == RoomPlaybackState.Playing
        val lateMs = ((serverNowMs() ?: wallNowMs()) - scheduled.executeAtServerMs).coerceAtLeast(0L)
        val target = command.positionSeconds + if (advancing) lateMs / 1000.0 else 0.0
        val playing = when (command.action) {
            TransportAction.Play -> command.playbackState != RoomPlaybackState.Waiting
            TransportAction.Pause -> false
            else -> command.playbackState == RoomPlaybackState.Playing
        }
        when (val decision = RoomCatchup.decide(
            action = command.action,
            targetSeconds = target,
            localSeconds = obs.sourcePositionSeconds,
            targetReachableLocally = player.isBuffered(target),
        )) {
            RoomCatchup.Decision.Seek -> {
                if (command.action == TransportAction.Seek || player.isBuffered(target)) {
                    player.seekTo(target)
                } else if (reloads.allowed(now)) {
                    // A Play correction that must load new media is paced.
                    val aimed = reloads.begin(target, now, obs.durationSeconds.takeIf { it > 0.0 })
                    player.seekTo(aimed)
                    reloads.noteLoading()
                }
            }
            is RoomCatchup.Decision.Rate -> {
                // [target] already includes the late start, so it advances from now.
                convergence = Convergence(target, now)
                setRate(decision.rate)
            }
            RoomCatchup.Decision.None -> if (command.action == TransportAction.Play) reloads.settle()
        }
        player.setPlaying(playing)
        applied = command
        appliedAtMs = now
        if (command.playbackState == RoomPlaybackState.Waiting) {
            readiness = Readiness(
                commandId = command.commandId,
                seekTarget = target.takeIf { command.action == TransportAction.Seek },
                armedWith = snapshot,
            )
        }
    }

    private fun converge(now: Long, obs: RoomPlayerObservation) {
        if (reloads.inFlight && obs.isPlaying && reloads.landed(obs.sourcePositionSeconds)) reloads.land(now)
        val target = convergence ?: return
        if (obs.seekPending || !obs.isPlaying || obs.suspended) {
            stopCorrections(now)
            return
        }
        val expected = RoomCatchup.expectedPosition(target.targetSeconds, target.executeAtMonotonicMs, now)
        val drift = expected - obs.sourcePositionSeconds
        if (abs(drift) <= RoomCatchup.DEADBAND_SECONDS || abs(drift) > RoomCatchup.BAND_SECONDS) {
            convergence = null
            setRate(null)
            if (abs(drift) <= RoomCatchup.DEADBAND_SECONDS) reloads.settle()
        } else {
            setRate(RoomCatchup.rateFor(drift))
        }
    }

    private fun stopCorrections(now: Long) {
        if (convergence != null) {
            convergence = null
            setRate(null)
        }
        if (reloads.inFlight) reloads.abandon(now)
    }

    private fun setRate(rate: Double?) {
        val normalized = rate?.takeIf { abs(it - 1.0) > 0.001 }
        if (normalized == rateApplied) return
        rateApplied = normalized
        player.setCorrectionRate(normalized)
    }

    private fun alignPausedToAnchor(snapshot: RoomSnapshot, obs: RoomPlayerObservation) {
        val anchor = snapshot.anchorPositionSeconds
        if (abs(anchor - obs.sourcePositionSeconds) > RoomCatchup.DEADBAND_SECONDS && player.isBuffered(anchor)) {
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
    }

    private fun expectedLocalExecuteMs(pending: PendingCommand): Long {
        val serverNow = serverNowMs() ?: return monotonicNowMs()
        return monotonicNowMs() + (pending.executeAtServerMs - serverNow)
    }
}
