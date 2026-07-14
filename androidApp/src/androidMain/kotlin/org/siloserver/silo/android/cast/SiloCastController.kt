package org.siloserver.silo.android.cast

import android.util.Log
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import org.siloserver.silo.cast.SiloCastControlCommand
import org.siloserver.silo.cast.SiloCastHello
import org.siloserver.silo.cast.SiloCastHandoffCancel
import org.siloserver.silo.cast.SiloCastHandoffChallenge
import org.siloserver.silo.cast.SiloCastHandoffOffer
import org.siloserver.silo.cast.SiloCastHandoffReady
import org.siloserver.silo.cast.SiloCastLaunchRequest
import org.siloserver.silo.cast.SiloCastMessage
import org.siloserver.silo.cast.SiloCastPeerRole
import org.siloserver.silo.cast.SiloCastPlaybackClock
import org.siloserver.silo.cast.SiloCastPlaybackRequest
import org.siloserver.silo.cast.SiloCastPlaybackState
import org.siloserver.silo.cast.SiloCastProtocol
import org.siloserver.silo.common.cast.SiloCastFrame
import org.siloserver.silo.common.cast.SiloCastFrameBuffer
import org.siloserver.silo.common.cast.SiloCastNsdBrowser
import org.siloserver.silo.common.cast.SiloCastTarget
import org.siloserver.silo.common.lan.SiloCastTls
import org.siloserver.silo.common.lan.SiloCastTlsClientSession
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.api.DeviceLoginApi

data class SiloCastControllerState(
    val targets: List<SiloCastTarget> = emptyList(),
    val connectedTarget: SiloCastTarget? = null,
    val playbackState: SiloCastPlaybackState? = null,
    val isConnecting: Boolean = false,
    /** Which target a connect attempt is aimed at — [connectedTarget] stays
     *  null until the hello is on the wire, so the picker's per-row spinner
     *  needs its own field. */
    val connectingDeviceId: String? = null,
    /** Transport dropped and the controller is retrying with backoff. The
     *  target/playback fields stay populated so the UI shows continuity
     *  ("Reconnecting… to <TV>") instead of vanishing and popping back. */
    val isReconnecting: Boolean = false,
    /** A silent foreground re-attach probe is in flight or unconfirmed —
     *  the mini bar stays hidden until the TV confirms it is playing. */
    val isAutoResuming: Boolean = false,
    /** A launch (connect + profile handoff + launch frame) is in flight. The
     *  TV keeps pushing idle state until its player registers, so without
     *  this the remote would show "Pick something from your library…" for
     *  seconds right after the user picked something. */
    val isLaunching: Boolean = false,
    val error: String? = null,
) {
    val isConnected: Boolean get() = connectedTarget != null && !isReconnecting
    val hasActiveSession: Boolean get() = connectedTarget != null || isReconnecting
}

/**
 * Phone-side SiloCast controller. Wire-compatible with silo-apple's
 * SiloControlClient/TVControlReceiver: TLS-PSK transport ([SiloCastTls]),
 * a `hello` carrying role=phone and the active serverId (the receiver
 * authorizes only a matching server), pong replies to receiver pings, and a
 * `close` goodbye on deliberate disconnect so the receiver doesn't treat it
 * as a dropped link.
 *
 * Session-lifecycle behavior mirrors the Apple client:
 * - heartbeat ping every 3 s; liveness resets only on `pong`, and 3 missed
 *   beats tear the transport down (a half-open socket can't stay pinned);
 * - a dropped transport auto-reconnects to the same target with 1–4 s
 *   backoff (5 attempts) while the UI shows "Reconnecting…";
 * - a deliberate close — ours or the TV's — never reconnects and forgets
 *   the persisted target;
 * - on app foreground with no session, the last-controlled TV is silently
 *   re-attached *only* while its Bonjour `playing` flag is set; idle TVs
 *   are never touched (a bare connection flips them into standby takeover).
 */
class SiloCastController(
    private val browser: SiloCastNsdBrowser,
    private val serverRegistry: ServerRegistry,
    private val tokenManager: TokenManager,
    private val deviceLoginApi: DeviceLoginApi,
    private val lastTargetStore: SiloCastLastTargetStore,
    private val deviceNameProvider: () -> String,
    private val deviceIdProvider: () -> String,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
    }
    private val sendMutex = Mutex()

    // Serializes ensureConnected/closeConnection: rapid taps on different
    // targets otherwise interleave connect/teardown across IO coroutines and
    // tear the session vars.
    private val connectionMutex = Mutex()
    // A handoff mutates connection-level request state. Newest launch wins,
    // while this mutex lets the cancelled launch finish its HandoffCancel
    // cleanup before its replacement begins.
    private val launchMutex = Mutex()
    private val launchJobLock = Any()
    private var launchJob: Job? = null
    private val clock = SiloCastPlaybackClock()

    private val _state = MutableStateFlow(SiloCastControllerState())
    val state: StateFlow<SiloCastControllerState> = _state.asStateFlow()

    private var session: SiloCastTlsClientSession? = null
    private var output: OutputStream? = null
    private var readJob: Job? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var autoResumeJob: Job? = null

    @Volatile
    private var missedHeartbeats = 0

    // The read loop's finally block decides between reconnect and teardown;
    // set before any deliberate close (ours or the TV's) so an intentional
    // goodbye is never mistaken for a dropped link.
    @Volatile
    private var suppressReconnect = false

    // A silently auto-resumed session the user never engaged with. Holding
    // one open against an idle TV would flip the TV into the standby
    // takeover screen, so it lets go quietly instead.
    @Volatile
    private var sessionIsAutoResumed = false

    @Volatile
    private var remoteScreenVisible = false

    private var negotiatedVersion = CompletableDeferred<Int>()
    @Volatile
    private var pendingHandoff: PendingHandoff? = null

    init {
        scope.launch {
            browser.targets.collect { targets ->
                _state.update { it.copy(targets = targets) }
            }
        }
    }

    fun startBrowsing() {
        browser.start()
    }

    fun stopBrowsing() {
        browser.stop()
    }

    fun launchOnTarget(target: SiloCastTarget, request: SiloCastLaunchRequest) {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val self = currentCoroutineContext()[Job] ?: return@launch
            try {
                launchMutex.withLock {
                    ensureConnected(target)
                    // AFTER ensureConnected: its teardown of any previous session
                    // resets the flag, so setting it earlier would be undone.
                    _state.update { it.copy(isLaunching = true) }
                    prepareRemoteIdentity(request)
                    send(SiloCastMessage.Launch(request))
                    // A cross-server handoff changes the TV's advertised
                    // server after the socket was opened. Persist the actual
                    // remote session scope, not the target's stale discovery
                    // value, so foreground auto-resume can reattach.
                    lastTargetStore.save(
                        SiloCastPersistedTarget(
                            deviceId = target.deviceId,
                            name = target.name,
                            serverId = request.serverId,
                        ),
                    )
                }
            } catch (error: CancellationException) {
                // Transport teardown cancels the handoff deferreds. Clear the
                // spinner when this is still the active launch, but do not let a
                // superseded launch overwrite the replacement's state.
                if (isCurrentLaunch(self)) {
                    _state.update { it.copy(isConnecting = false, connectingDeviceId = null, isLaunching = false) }
                }
                throw error
            } catch (error: Throwable) {
                if (isCurrentLaunch(self)) {
                    _state.update {
                        it.copy(
                            isConnecting = false,
                            connectingDeviceId = null,
                            isLaunching = false,
                            error = error.message ?: "Unable to cast.",
                        )
                    }
                }
            } finally {
                synchronized(launchJobLock) {
                    if (launchJob === self) launchJob = null
                }
            }
        }
        val previous = synchronized(launchJobLock) {
            val old = launchJob
            launchJob = job
            old
        }
        previous?.cancel(CancellationException("Superseded by a newer remote launch."))
        job.start()
    }

    private fun isCurrentLaunch(job: Job): Boolean = synchronized(launchJobLock) { launchJob === job }

    /**
     * Makes an active Remote Control session the destination for an ordinary
     * Play action. Returns false only when no TV is connected, allowing the UI
     * to fall back to local playback without racing a separate state read.
     */
    fun launchOnConnectedTarget(playback: SiloCastPlaybackRequest): Boolean {
        val target = _state.value.connectedTarget ?: return false
        val server = serverRegistry.activeEntry.value
        if (server == null) {
            _state.update { it.copy(error = "Choose a server before controlling a TV.") }
            return true
        }
        launchOnTarget(
            target = target,
            request = SiloCastLaunchRequest(serverId = server.id, playback = playback),
        )
        return true
    }

    fun connect(target: SiloCastTarget) {
        scope.launch {
            runCatching { ensureConnected(target) }
                .onFailure { error ->
                    if (error !is CancellationException) {
                        _state.update {
                            it.copy(isConnecting = false, connectingDeviceId = null, error = error.message ?: "Unable to connect.")
                        }
                    }
                }
        }
    }

    /**
     * User-initiated disconnect: also forgets the persisted target so a later
     * foreground probe doesn't silently reattach to a TV the user let go of.
     */
    fun disconnect() {
        lastTargetStore.clear()
        cancelReconnect()
        cancelAutoResumeProbe()
        suppressReconnect = true
        scope.launch {
            runCatching { send(SiloCastMessage.Close()) }
            closeConnection()
        }
    }

    fun playPause() {
        sendControl(SiloCastControlCommand.playPause())
        clock.setOptimisticPlaying(!isPlaying(), nowMs())
    }

    fun seek(seconds: Double) {
        sendControl(SiloCastControlCommand.seek(seconds))
        clock.setOptimisticTime(seconds, nowMs())
    }

    fun selectAudioTrack(trackId: Long) {
        sendControl(SiloCastControlCommand.selectAudioTrack(trackId))
    }

    fun selectSubtitleTrack(trackId: Long?) {
        sendControl(SiloCastControlCommand.selectSubtitleTrack(trackId))
    }

    fun selectQuality(qualityId: String) {
        sendControl(SiloCastControlCommand.setQuality(qualityId))
    }

    fun setPlaybackSpeed(speed: Double) {
        sendControl(SiloCastControlCommand.setPlaybackSpeed(speed))
    }

    fun playNext() {
        sendControl(SiloCastControlCommand.playNext())
    }

    fun stopPlayback() {
        sendControl(SiloCastControlCommand.stop())
    }

    fun setVolume(volume: Double) {
        sendControl(SiloCastControlCommand.setVolume(volume.coerceIn(0.0, 1.0)))
    }

    fun setMuted(muted: Boolean) {
        sendControl(SiloCastControlCommand.setMuted(muted))
    }

    fun setVideoGravity(value: String) {
        sendControl(SiloCastControlCommand.setVideoGravity(value))
    }

    fun setHdrEnabled(enabled: Boolean) {
        sendControl(SiloCastControlCommand.setHdrEnabled(enabled))
    }

    fun setSubtitleSyncMs(milliseconds: Int) {
        sendControl(SiloCastControlCommand.setSubtitleSyncMs(milliseconds))
    }

    fun setSubtitlePosition(value: String) {
        sendControl(SiloCastControlCommand.setSubtitlePosition(value))
    }

    fun displayTime(): Double = clock.displayTime(nowMs())

    fun isPlaying(): Boolean = clock.isPlaying(nowMs())

    /** The full remote is on screen — counts as user engagement, so an
     *  auto-resumed session won't let go quietly while it's visible. */
    fun setRemoteScreenVisible(visible: Boolean) {
        remoteScreenVisible = visible
        if (visible) sessionIsAutoResumed = false
    }

    fun onAppForeground() {
        if (session != null) {
            // Validate liveness immediately rather than waiting out the
            // heartbeat interval on a socket that died while backgrounded.
            scope.launch { runCatching { send(SiloCastMessage.Ping()) } }
            return
        }
        if (reconnectJob != null || _state.value.isReconnecting) return
        attemptAutoResumeIfIdle()
    }

    fun onAppBackground() {
        // A half-finished probe can't complete while suspended; an unengaged
        // auto-resumed session shouldn't outlive the app being visible.
        cancelAutoResumeProbe()
        if (sessionIsAutoResumed) {
            quietDisconnect()
        }
    }

    /**
     * Silently reattaches to the last-controlled TV when the app comes to the
     * foreground with no session, *if* that TV is currently playing per its
     * Bonjour `playing` TXT flag. Idle TVs are never touched — a bare
     * connection would flip them into the standby takeover screen.
     */
    fun attemptAutoResumeIfIdle() {
        if (session != null || reconnectJob != null || autoResumeJob != null) return
        val persisted = lastTargetStore.load() ?: return
        if (persisted.serverId == null || persisted.serverId != serverRegistry.activeEntry.value?.id) return

        autoResumeJob = scope.launch {
            try {
                browser.start()
                var match: SiloCastTarget? = null
                var rounds = 0
                while (match == null && rounds < AUTO_RESUME_SCAN_ROUNDS) {
                    delay(AUTO_RESUME_SCAN_STEP_MS)
                    if (session != null) return@launch
                    match = _state.value.targets.firstOrNull { it.deviceId == persisted.deviceId && it.isPlaying }
                    rounds++
                }
                val found = match ?: return@launch
                Log.i(TAG, "SiloCast auto-resume probe found playing TV ${found.name}")
                runCatching { ensureConnected(found) }.onFailure {
                    _state.update { state -> state.copy(isAutoResuming = false, isConnecting = false) }
                    return@launch
                }
                // Flag AFTER connecting: ensureConnected's teardown of the
                // previous session resets both auto-resume markers, so setting
                // them earlier would be silently undone mid-connect.
                sessionIsAutoResumed = true
                _state.update { it.copy(isAutoResuming = true) }
                // Safety net: the TV sends state right after hello; if nothing
                // confirms playback shortly, let go quietly.
                delay(AUTO_RESUME_CONFIRM_TIMEOUT_MS)
                if (_state.value.isAutoResuming) {
                    quietDisconnect()
                }
            } finally {
                autoResumeJob = null
            }
        }
    }

    private fun cancelAutoResumeProbe() {
        autoResumeJob?.cancel()
        autoResumeJob = null
        if (_state.value.isAutoResuming) {
            _state.update { it.copy(isAutoResuming = false) }
        }
    }

    private fun sendControl(command: SiloCastControlCommand) {
        // Any outbound command counts as user engagement — the session is no
        // longer a passive auto-resume attachment after this.
        sessionIsAutoResumed = false
        scope.launch {
            runCatching { send(SiloCastMessage.Control(command)) }
                .onFailure { error -> _state.update { it.copy(error = error.message) } }
        }
    }

    private suspend fun ensureConnected(target: SiloCastTarget) = connectionMutex.withLock {
        if (_state.value.connectedTarget?.deviceId == target.deviceId && session?.isConnected == true) return@withLock
        reconnectJob?.cancel()
        reconnectJob = null
        closeConnectionLocked()
        suppressReconnect = false
        _state.update {
            it.copy(
                isConnecting = true,
                connectingDeviceId = target.deviceId,
                isReconnecting = false,
                error = null,
            )
        }
        openSessionLocked(target)
        lastTargetStore.save(
            SiloCastPersistedTarget(deviceId = target.deviceId, name = target.name, serverId = target.serverId),
        )
        Unit
    }

    /** Opens the transport and starts the read/heartbeat loops. Caller must
     *  hold [connectionMutex] and have torn the previous transport down.
     *  State only flips to connected after the hello is on the wire, so a
     *  half-open failure never leaves a zombie session behind. */
    private suspend fun openSessionLocked(target: SiloCastTarget) {
        // Captured from inside the withContext block: if this coroutine is
        // cancelled while the blocking connect is in flight, withContext
        // discards the block's result and throws — the catch below is then
        // the only reference that can close the already-opened socket.
        var created: SiloCastTlsClientSession? = null
        try {
            withContext(Dispatchers.IO) {
                created = SiloCastTls.connect(target.host, target.port, CONNECT_TIMEOUT_MS)
            }
            val newSession = created ?: error("SiloCast connection failed.")
            session = newSession
            output = newSession.output
            negotiatedVersion = CompletableDeferred()
            missedHeartbeats = 0
            send(SiloCastMessage.Hello(makeHello()))
            _state.update {
                it.copy(
                    connectedTarget = target,
                    connectingDeviceId = null,
                    isConnecting = false,
                    isReconnecting = false,
                    error = null,
                )
            }
            readJob = scope.launch { readLoop(newSession) }
            heartbeatJob = scope.launch { heartbeatLoop(newSession) }
        } catch (t: Throwable) {
            runCatching { created?.close() }
            if (session === created) {
                session = null
                output = null
            }
            throw t
        }
    }

    private fun makeHello(): SiloCastHello {
        val server = serverRegistry.activeEntry.value
        return SiloCastHello(
            role = SiloCastPeerRole.Phone,
            deviceName = deviceNameProvider(),
            deviceId = deviceIdProvider(),
            serverId = server?.id,
            serverName = server?.displayName,
            supportedVersions = SiloCastProtocol.supportedVersions,
        )
    }

    private suspend fun prepareRemoteIdentity(request: SiloCastLaunchRequest) {
        val version = awaitHandoffStep(VERSION_TIMEOUT_MS, "The TV did not respond.") { negotiatedVersion.await() }
        require(version == SiloCastProtocol.version) { "Update Silo on both devices to use Remote Control." }

        val server = serverRegistry.activeEntry.value
            ?: error("Choose a server before controlling a TV.")
        require(server.id == request.serverId) { "The active server changed before playback started." }
        val profileId = tokenManager.getProfileId()?.takeIf { it.isNotBlank() }
            ?: error("Choose a profile before controlling a TV.")
        val scope = tokenManager.snapshotCurrentScope()
            ?: error("The active Silo account is unavailable.")
        require(scope.serverId == server.id) { "The active server changed before playback started." }

        val handoff = PendingHandoff(requestId = UUID.randomUUID().toString())
        pendingHandoff = handoff
        var challenge: SiloCastHandoffChallenge? = null
        try {
            send(
                SiloCastMessage.HandoffOffer(
                    SiloCastHandoffOffer(
                        requestId = handoff.requestId,
                        serverId = server.id,
                        serverURL = server.url,
                        serverName = server.displayName,
                        profileId = profileId,
                        profileName = null,
                    ),
                ),
            )
            // When the TV is reusing an already-active identity for this
            // controller/server/profile (e.g. switching items mid-playback),
            // it skips the challenge and replies handoff_ready directly —
            // waiting only on the challenge would deadlock into a timeout.
            val readyOrChallenge = awaitHandoffStep(CHALLENGE_TIMEOUT_MS, "The TV did not start the profile handshake.") {
                select<Any> {
                    handoff.handoffReady.onAwait { it }
                    handoff.handoffChallenge.onAwait { it }
                }
            }
            val ready: SiloCastHandoffReady = when (readyOrChallenge) {
                is SiloCastHandoffReady -> readyOrChallenge
                is SiloCastHandoffChallenge -> {
                    challenge = readyOrChallenge
                    val lookup = deviceLoginApi
                        .lookupDeviceLoginForScope(scope.withProfile(profileId), readyOrChallenge.userCode)
                        .successOrThrow()
                    require(
                        lookup.matchCode == readyOrChallenge.matchCode &&
                            lookup.clientPurpose == "remote_playback" &&
                            lookup.temporary == true,
                    ) { "The TV's remote playback code could not be verified." }

                    deviceLoginApi
                        .approveRemotePlaybackForScope(scope.withProfile(profileId), readyOrChallenge.userCode)
                        .successOrThrow()
                    awaitHandoffStep(READY_TIMEOUT_MS, "The TV did not activate the profile in time.") {
                        handoff.handoffReady.await()
                    }
                }
                else -> error("The TV sent an unexpected handoff reply.")
            }
            require(ready.serverId == server.id && ready.profileId == profileId) {
                "The TV activated a different remote playback profile."
            }
        } catch (t: Throwable) {
            // Superseding a launch cancels this coroutine. Finish the old
            // handoff's server/TV cleanup before releasing launchMutex so the
            // replacement cannot overlap the abandoned credential exchange.
            withContext(NonCancellable) {
                runCatching {
                    challenge?.let {
                        deviceLoginApi.denyDeviceLoginForScope(scope.withProfile(profileId), it.userCode)
                    }
                }
                runCatching {
                    send(
                        SiloCastMessage.HandoffCancel(
                            SiloCastHandoffCancel(
                                requestId = handoff.requestId,
                                reason = "controller_cancelled",
                                message = t.message,
                            ),
                        ),
                    )
                }
            }
            throw t
        } finally {
            if (pendingHandoff === handoff) pendingHandoff = null
        }
    }

    private suspend fun readLoop(activeSession: SiloCastTlsClientSession) {
        val frameBuffer = SiloCastFrameBuffer()
        val input = activeSession.input
        val chunk = ByteArray(8 * 1024)
        try {
            while (true) {
                val read = withContext(Dispatchers.IO) { input.read(chunk) }
                if (read < 0) break
                frameBuffer.append(chunk.copyOf(read)).forEach { payload ->
                    handleMessage(json.decodeFromString(SiloCastMessage.serializer(), payload.decodeToString()))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // A deliberate teardown (ours or the TV's goodbye) surfaces here
            // as "Socket closed" — expected, so no alarming stack trace.
            if (suppressReconnect || session !== activeSession) {
                Log.i(TAG, "SiloCast read loop ended after deliberate close: ${t.message}")
            } else {
                Log.w(TAG, "SiloCast read loop ended", t)
            }
        } finally {
            // Guard: the old session's read loop can outlive a reconnect and
            // must not clobber the fresh session's state.
            if (session === activeSession) {
                if (suppressReconnect) {
                    closeConnection()
                } else {
                    beginReconnect(activeSession, "Lost connection to the TV.")
                }
            }
        }
    }

    /**
     * Pings every 3 s; only a `pong` resets the miss counter (any other
     * inbound traffic can't mask a broken receive path). Three misses tear
     * the transport down, which routes into the reconnect path.
     */
    private suspend fun heartbeatLoop(activeSession: SiloCastTlsClientSession) {
        while (session === activeSession) {
            delay(HEARTBEAT_INTERVAL_MS)
            if (session !== activeSession) return
            missedHeartbeats += 1
            if (missedHeartbeats > MAX_MISSED_HEARTBEATS) {
                Log.w(TAG, "SiloCast heartbeat: TV stopped answering")
                // Closing the socket wakes the read loop, whose finally block
                // owns the reconnect decision.
                runCatching { activeSession.close() }
                return
            }
            runCatching { send(SiloCastMessage.Ping()) }
        }
    }

    /**
     * Transport dropped without a goodbye: keep the target and retry with
     * 1–4 s backoff (up to 5 attempts) while the UI shows "Reconnecting…".
     */
    private fun beginReconnect(deadSession: SiloCastTlsClientSession, reason: String) {
        val target = _state.value.connectedTarget
        if (target == null || suppressReconnect) {
            scope.launch { closeConnection() }
            return
        }
        scope.launch {
            connectionMutex.withLock {
                // Re-check under the mutex: a user-driven ensureConnected can
                // race the dying read loop and already own a fresh session —
                // tearing that down here would kill the connection they just
                // opened.
                if (session !== deadSession || suppressReconnect || reconnectJob != null) return@launch
                teardownTransportLocked()
                _state.update { it.copy(isReconnecting = true, isConnecting = false, isLaunching = false) }
                reconnectJob = scope.launch {
                    try {
                        for (attempt in 1..MAX_RECONNECT_ATTEMPTS) {
                            delay((attempt.coerceAtMost(4)) * 1_000L)
                            if (suppressReconnect) return@launch
                            val reconnected = connectionMutex.withLock {
                                if (session != null) return@launch
                                try {
                                    openSessionLocked(target)
                                    true
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (t: Throwable) {
                                    Log.w(TAG, "SiloCast reconnect attempt $attempt failed", t)
                                    false
                                }
                            }
                            if (reconnected) {
                                Log.i(TAG, "SiloCast reconnected to ${target.name} (attempt $attempt)")
                                _state.update { it.copy(isReconnecting = false) }
                                return@launch
                            }
                        }
                        Log.w(TAG, "SiloCast reconnect exhausted: $reason")
                        closeConnection()
                        _state.update { it.copy(error = reason) }
                    } finally {
                        // A stale job's cleanup must not null out a newer
                        // job's handle.
                        if (reconnectJob === kotlin.coroutines.coroutineContext[Job]) reconnectJob = null
                    }
                }
            }
        }
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
        if (_state.value.isReconnecting) {
            _state.update { it.copy(isReconnecting = false) }
        }
    }

    /** Lets go of an auto-resumed session without forgetting the persisted
     *  target, so a later foreground probe may still resume. */
    private fun quietDisconnect() {
        suppressReconnect = true
        sessionIsAutoResumed = false
        scope.launch {
            runCatching { send(SiloCastMessage.Close()) }
            closeConnection()
        }
    }

    private suspend fun handleMessage(message: SiloCastMessage) {
        when (message) {
            is SiloCastMessage.Hello -> {
                val version = SiloCastProtocol.negotiatedVersion(message.hello.supportedVersions)
                    ?: error("Remote Control protocol is not compatible.")
                negotiatedVersion.complete(version)
            }
            is SiloCastMessage.HandoffChallenge -> {
                pendingHandoff?.takeIf { message.handoffChallenge.requestId == it.requestId }?.let {
                    it.handoffChallenge.complete(message.handoffChallenge)
                }
            }
            is SiloCastMessage.HandoffReady -> {
                pendingHandoff?.takeIf { message.handoffReady.requestId == it.requestId }?.let {
                    it.handoffReady.complete(message.handoffReady)
                }
            }
            is SiloCastMessage.HandoffCancel -> {
                pendingHandoff?.takeIf { message.handoffCancel.requestId == it.requestId }?.let {
                    val error = IllegalStateException(
                        message.handoffCancel.message ?: "The TV cancelled profile setup.",
                    )
                    it.handoffChallenge.completeExceptionally(error)
                    it.handoffReady.completeExceptionally(error)
                }
            }
            is SiloCastMessage.State -> {
                clock.ingest(message.state, nowMs())
                val isIdle = message.state.contentId.isNullOrEmpty()
                if (sessionIsAutoResumed && isIdle && !remoteScreenVisible) {
                    // The user never engaged with this silently-resumed
                    // session; holding it open against an idle TV would flip
                    // the TV into standby takeover — let go quietly.
                    quietDisconnect()
                    return
                }
                _state.update {
                    it.copy(
                        playbackState = message.state,
                        error = null,
                        isAutoResuming = if (!isIdle) false else it.isAutoResuming,
                        isLaunching = if (!isIdle) false else it.isLaunching,
                    )
                }
            }
            is SiloCastMessage.Error -> {
                if (sessionIsAutoResumed && !remoteScreenVisible) {
                    quietDisconnect()
                } else {
                    _state.update { it.copy(error = message.error.message, isLaunching = false) }
                }
            }
            is SiloCastMessage.Ping -> send(SiloCastMessage.Pong())
            is SiloCastMessage.Pong -> missedHeartbeats = 0
            is SiloCastMessage.Close -> {
                // The TV ended the session deliberately (Disconnect Remote, or
                // another controller took over). Respect that intent: no
                // reconnect, and forget the persisted target so no later
                // foreground probe silently reattaches.
                suppressReconnect = true
                lastTargetStore.clear()
                closeConnection()
            }
            else -> Unit
        }
    }

    private suspend fun send(message: SiloCastMessage) {
        val out = output ?: error("SiloCast is not connected.")
        val frame = SiloCastFrame.encode(json.encodeToString(SiloCastMessage.serializer(), message).encodeToByteArray())
        sendMutex.withLock {
            withContext(Dispatchers.IO) {
                out.write(frame)
                out.flush()
            }
        }
    }

    private suspend fun closeConnection() = connectionMutex.withLock { closeConnectionLocked() }

    /** Tears the transport down but keeps the connected-target/playback state
     *  fields, so a reconnect renders continuity instead of a blank remote. */
    private fun teardownTransportLocked() {
        runCatching { session?.close() }
        session = null
        output = null
        readJob?.cancel()
        readJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        pendingHandoff?.cancel()
        pendingHandoff = null
        negotiatedVersion.cancel()
    }

    private fun closeConnectionLocked() {
        teardownTransportLocked()
        sessionIsAutoResumed = false
        _state.update {
            it.copy(
                connectedTarget = null,
                playbackState = null,
                isConnecting = false,
                connectingDeviceId = null,
                isReconnecting = false,
                isAutoResuming = false,
                isLaunching = false,
            )
        }
    }

    fun close() {
        browser.stop()
        cancelReconnect()
        cancelAutoResumeProbe()
        closeConnectionLocked()
        scope.cancel()
    }

    /**
     * Convert a timeout into an ordinary failure so the UI gets a useful error;
     * genuine cancellation remains cancellation and is handled by the launch
     * owner without being mistaken for a timeout.
     */
    private suspend fun <T> awaitHandoffStep(timeoutMs: Long, timeoutMessage: String, block: suspend () -> T): T =
        try {
            withTimeout(timeoutMs) { block() }
        } catch (e: TimeoutCancellationException) {
            error(timeoutMessage)
        }

    private fun nowMs(): Long = android.os.SystemClock.elapsedRealtime()

    private data class PendingHandoff(
        val requestId: String,
        val handoffChallenge: CompletableDeferred<SiloCastHandoffChallenge> = CompletableDeferred(),
        val handoffReady: CompletableDeferred<SiloCastHandoffReady> = CompletableDeferred(),
    ) {
        fun cancel() {
            handoffChallenge.cancel()
            handoffReady.cancel()
        }
    }

    private fun AuthScopeSnapshot.withProfile(profileId: String) = copy(profileId = profileId)

    private fun <T> ApiResult<T>.successOrThrow(): T = when (this) {
        is ApiResult.Success -> data
        is ApiResult.Error -> error(message.ifBlank { "Server returned HTTP $code." })
        is ApiResult.NetworkError -> throw exception
    }

    private companion object {
        const val TAG = "SiloCastController"
        const val CONNECT_TIMEOUT_MS = 5_000
        const val VERSION_TIMEOUT_MS = 5_000L
        const val CHALLENGE_TIMEOUT_MS = 15_000L
        const val READY_TIMEOUT_MS = 35_000L
        const val HEARTBEAT_INTERVAL_MS = 3_000L
        const val MAX_MISSED_HEARTBEATS = 3
        const val MAX_RECONNECT_ATTEMPTS = 5
        const val AUTO_RESUME_SCAN_ROUNDS = 8
        const val AUTO_RESUME_SCAN_STEP_MS = 500L
        const val AUTO_RESUME_CONFIRM_TIMEOUT_MS = 6_000L
    }
}
