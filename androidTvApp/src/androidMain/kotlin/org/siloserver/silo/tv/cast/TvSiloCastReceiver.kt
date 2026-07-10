package org.siloserver.silo.tv.cast

import android.util.Log
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.siloserver.silo.cast.SiloCastError
import org.siloserver.silo.cast.SiloCastHello
import org.siloserver.silo.cast.SiloCastHandoffCancel
import org.siloserver.silo.cast.SiloCastLaunchRequest
import org.siloserver.silo.cast.SiloCastMessage
import org.siloserver.silo.cast.SiloCastPeerRole
import org.siloserver.silo.cast.SiloCastPlaybackState
import org.siloserver.silo.cast.SiloCastProtocol
import org.siloserver.silo.common.cast.SiloCastFrame
import org.siloserver.silo.common.cast.SiloCastFrameBuffer
import org.siloserver.silo.common.cast.SiloCastNsdAdvertiser
import org.siloserver.silo.common.lan.SiloCastTls
import org.siloserver.silo.common.lan.SiloCastTlsSession
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager

/**
 * TV-side SiloCast receiver, session-semantics-compatible with silo-apple's
 * TVControlReceiver (Apple clients are already shipped and cannot change):
 *
 * - Transport is TLS-PSK ([SiloCastTls]) over the advertised `_silocast._tcp`
 *   port; newest controller wins the single session slot.
 * - The TV sends its `hello` + a state snapshot immediately after the TLS
 *   handshake. The controller must reply with a `hello` whose serverId
 *   matches the TV's active server within [AUTH_GRACE_MS] — that check is
 *   the trust anchor (the PSK is fixed and non-secret). Mismatch → `error
 *   server_mismatch` + graceful close; silence → close.
 * - `launch`/`control` before authorization → `error unauthorized`.
 * - Heartbeat: the TV pings every [HEARTBEAT_INTERVAL_MS]; only a `pong`
 *   proves the controller can still receive, so only `pong` resets the
 *   missed counter. More than [MAX_MISSED_HEARTBEATS] misses → drop.
 * - State pushes every [STATE_INTERVAL_MS] (Apple's 500ms cadence) while a
 *   player is registered, an idle "Ready" state otherwise, and one push
 *   right after every accepted control command.
 * - A deliberate teardown sends a `close` frame ahead of the FIN so the
 *   controller can tell "disconnected on purpose" from a dropped link.
 */
class TvSiloCastReceiver(
    private val advertiser: SiloCastNsdAdvertiser,
    private val serverRegistry: ServerRegistry,
    private val tokenManager: TokenManager,
    private val identityManager: RemotePlaybackIdentityManager,
    private val deviceNameProvider: () -> String,
    private val deviceIdProvider: () -> String,
) {
    private val json = Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    private var scope: CoroutineScope? = null
    private var serverSocket: ServerSocket? = null
    private var activeSession: ControllerSession? = null

    /** Bumped whenever the active-controller slot is force-cleared (new accept,
     *  server switch, stop). Handshakes started before the bump must not
     *  register — they belong to the previous epoch. */
    private var sessionEpoch: Long = 0
    private var activePlayer: ActivePlayer? = null
    private var pendingPlayerGeneration: String? = null
    private var launchHandler: ((SiloCastLaunchRequest) -> Unit)? = null

    @Synchronized
    fun start() {
        if (scope != null) return
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        newScope.launch {
            val socket = ServerSocket(0).also { serverSocket = it }
            advertiser.start(
                port = socket.localPort,
                serverId = identityManager.effectiveServerId,
                serverName = identityManager.effectiveServerName,
                playing = activePlayer != null,
            )
            Log.i(TAG, "SiloCast listening on ${socket.localPort} for ${SiloCastProtocol.serviceType}")
            acceptLoop(socket)
        }
        // Keep the Bonjour TXT record in sync with the active server while the
        // receiver runs (it stays up across server switches until onStop), and
        // drop the live controller session — its hello was authorized against
        // the previous server, so keeping it would let an old-server remote
        // drive playback on the new one.
        newScope.launch {
            serverRegistry.activeEntry.drop(1).collect { entry ->
                if (identityManager.activeIdentity != null) return@collect
                closePreviousController()
                val port = synchronized(this@TvSiloCastReceiver) { serverSocket?.localPort }
                if (port != null) {
                    advertiser.start(
                        port = port,
                        serverId = entry?.id,
                        serverName = entry?.displayName,
                        playing = activePlayer != null,
                    )
                }
            }
        }
        newScope.launch {
            tokenManager.temporarySessionExpired.collect {
                handleTemporarySessionExpired()
            }
        }
    }

    @Synchronized
    fun stop() {
        advertiser.stop()
        // Close the session directly (not via closePreviousController, which
        // launches the goodbye on `scope` — the scope we cancel a line later,
        // which would kill the goodbye before it writes). A direct close()
        // sends the FIN; the goodbye frame is best-effort and the socket
        // teardown below guarantees the peer disconnects regardless.
        sessionEpoch += 1
        activeSession?.close()
        activeSession = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope?.cancel()
        scope = null
    }

    @Synchronized
    fun setLaunchHandler(handler: ((SiloCastLaunchRequest) -> Unit)?) {
        launchHandler = handler
    }

    @Synchronized
    fun registerPlayer(
        adapter: TvSiloCastPlayerAdapter,
        stateProvider: () -> SiloCastPlaybackState,
    ): Closeable {
        val player = ActivePlayer(
            adapter = adapter,
            stateProvider = stateProvider,
            handoffGeneration = pendingPlayerGeneration,
        )
        pendingPlayerGeneration = null
        activePlayer = player
        activeSession?.readyTimeoutJob?.cancel()
        activeSession?.readyTimeoutJob = null
        advertiser.updatePlaying(true)
        return Closeable {
            synchronized(this) {
                if (activePlayer === player) {
                    activePlayer = null
                    advertiser.updatePlaying(false)
                    if (player.handoffGeneration != null &&
                        identityManager.activeIdentity?.generationId == player.handoffGeneration
                    ) {
                        scope?.launch {
                            identityManager.end()
                            refreshAdvertisement()
                        }
                    }
                }
            }
        }
    }

    @Synchronized
    fun closePreviousController() {
        // Bump the epoch unconditionally — a handshake in flight (not yet
        // registered, so activeSession is still null) during a server switch
        // must also be invalidated, else it registers into the new epoch.
        sessionEpoch += 1
        val session = activeSession ?: return
        activeSession = null
        val owner = scope
        if (owner != null) {
            // Goodbye + teardown off the monitor — a blocking write to a
            // half-open peer must not stall registerPlayer/stop/accept.
            owner.launch { session.goodbyeAndClose() }
        } else {
            session.close()
        }
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (true) {
            val client = try {
                withContext(Dispatchers.IO) { socket.accept() }
            } catch (_: Throwable) {
                Log.i(TAG, "SiloCast listener stopped")
                return
            }
            closePreviousController()
            val ownerScope = scope ?: run {
                runCatching { client.close() }
                return
            }
            val sessionJob = ownerScope.launch {
                runControllerSession(client)
            }
            // runControllerSession registers the ControllerSession itself once
            // the TLS handshake succeeds; a handshake failure just ends the job.
            sessionJob.invokeOnCompletion { }
        }
    }

    private suspend fun runControllerSession(client: Socket) {
        val epochAtAccept = synchronized(this) { sessionEpoch }
        val tls = try {
            withContext(Dispatchers.IO) { SiloCastTls.accept(client) }
        } catch (t: Throwable) {
            Log.w(TAG, "SiloCast TLS handshake failed", t)
            runCatching { client.close() }
            return
        }
        val session = ControllerSession(socket = client, tls = tls, json = json)
        val registered = synchronized(this) {
            if (sessionEpoch != epochAtAccept) {
                // A server switch / newer controller / stop() happened while
                // this handshake was in flight — this session lost.
                false
            } else {
                // Newest wins: a session that finished handshaking after us in
                // the same epoch would have replaced us here; close any loser.
                activeSession?.close()
                activeSession = session
                true
            }
        }
        if (!registered) {
            session.close()
            return
        }
        try {
            coroutineScope {
                session.job = coroutineContext[Job]

                // TV speaks first. State is withheld until the controller is
                // authorized; a cross-server v2 peer gets only the handoff lane.
                session.send(SiloCastMessage.Hello(makeHello()))

                val stateJob = launch {
                    while (isActive) {
                        if (session.isAuthorized) {
                            session.send(SiloCastMessage.State(currentState()))
                        }
                        delay(STATE_INTERVAL_MS)
                    }
                }
                val heartbeatJob = launch {
                    while (isActive) {
                        delay(HEARTBEAT_INTERVAL_MS)
                        session.missedHeartbeats += 1
                        if (session.missedHeartbeats > MAX_MISSED_HEARTBEATS) {
                            Log.i(TAG, "SiloCast controller heartbeat timed out")
                            session.close()
                            return@launch
                        }
                        session.send(SiloCastMessage.Ping())
                    }
                }
                val authWatchdog = launch {
                    delay(AUTH_GRACE_MS)
                    if (!session.didReceiveHello) {
                        Log.i(TAG, "SiloCast controller never completed hello; closing")
                        session.goodbyeAndClose()
                    }
                }

                readLoop(tls) { message ->
                    handleMessage(session, message)
                }
                stateJob.cancel()
                heartbeatJob.cancel()
                authWatchdog.cancel()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "SiloCast controller session ended", t)
        } finally {
            synchronized(this) {
                if (activeSession === session) {
                    activeSession = null
                }
            }
            session.close()
        }
    }

    /** @return false to end the session's read loop. */
    private suspend fun handleMessage(session: ControllerSession, message: SiloCastMessage): Boolean {
        when (message) {
            is SiloCastMessage.Hello -> {
                val version = SiloCastProtocol.negotiatedVersion(message.hello.supportedVersions)
                if (message.hello.role != SiloCastPeerRole.Phone || version == null) {
                    session.send(
                        SiloCastMessage.Error(
                            SiloCastError(
                                code = "version_unsupported",
                                message = "Update Silo on both devices to continue.",
                            ),
                        ),
                    )
                    session.goodbyeAndClose()
                    return false
                }
                session.didReceiveHello = true
                session.negotiatedVersion = version
                session.controllerDeviceId = message.hello.deviceId
                session.controllerDeviceName = message.hello.deviceName
                session.controllerServerId = message.hello.serverId
                val activeServerId = identityManager.effectiveServerId
                val offered = message.hello.serverId
                if (!offered.isNullOrEmpty() && activeServerId != null && offered == activeServerId) {
                    session.isAuthorized = true
                    session.send(SiloCastMessage.State(currentState()))
                } else if (version < 2) {
                    session.send(
                        SiloCastMessage.Error(
                            SiloCastError(
                                code = "server_mismatch",
                                message = "This TV is connected to a different Silo server.",
                            ),
                        ),
                    )
                    session.goodbyeAndClose()
                    return false
                }
            }
            is SiloCastMessage.HandoffOffer -> {
                val controllerDeviceId = session.controllerDeviceId
                if (session.negotiatedVersion != 2 || controllerDeviceId.isNullOrBlank()) {
                    session.send(
                        SiloCastMessage.HandoffCancel(
                            SiloCastHandoffCancel(
                                requestId = message.handoffOffer.requestId,
                                reason = "version_unsupported",
                                message = "Update Silo on both devices to continue.",
                            ),
                        ),
                    )
                    return true
                }
                beginHandoff(session, message.handoffOffer, controllerDeviceId)
            }
            is SiloCastMessage.HandoffCancel -> {
                if (message.handoffCancel.requestId == session.pendingHandoffRequestId) {
                    session.handoffJob?.cancel()
                    session.handoffJob = null
                    session.pendingHandoffRequestId = null
                }
            }
            is SiloCastMessage.Launch -> {
                if (!requireAuthorized(session)) return true
                if (session.negotiatedVersion == 2 &&
                    (!session.remoteIdentityReady || identityManager.activeIdentity == null)
                ) {
                    session.send(
                        SiloCastMessage.Error(
                            SiloCastError(
                                code = "handoff_required",
                                message = "Prepare the phone profile before playing.",
                            ),
                        ),
                    )
                    return true
                }
                if (message.launch.serverId != identityManager.effectiveServerId) {
                    session.send(
                        SiloCastMessage.Error(
                            SiloCastError(
                                code = "server_mismatch",
                                message = "This TV is connected to a different Silo server.",
                            ),
                        ),
                    )
                    return true
                }
                val handler = launchHandler
                if (handler != null) {
                    pendingPlayerGeneration = identityManager.activeIdentity?.generationId
                    handler.invoke(message.launch)
                } else {
                    // No launch handler is wired (setLaunchHandler is never
                    // called yet), so an authorized, server-matched launch
                    // would otherwise be silently dropped and the controller
                    // would hang waiting for state that never changes. Return
                    // a definitive error so it can surface a failure instead.
                    session.send(
                        SiloCastMessage.Error(
                            SiloCastError(
                                code = "launch_unsupported",
                                message = "This TV cannot start playback from the remote yet.",
                            ),
                        ),
                    )
                }
            }
            is SiloCastMessage.Control -> {
                if (!requireAuthorized(session)) return true
                val player = activePlayer
                if (player == null) {
                    session.send(
                        SiloCastMessage.Error(
                            SiloCastError(code = "player_not_ready", message = "The TV player is not ready yet."),
                        ),
                    )
                    return true
                }
                player.adapter.handle(message.control)
                session.send(SiloCastMessage.State(currentState()))
            }
            is SiloCastMessage.Ping -> session.send(SiloCastMessage.Pong())
            is SiloCastMessage.Pong -> session.missedHeartbeats = 0
            is SiloCastMessage.Close -> return false
            is SiloCastMessage.State,
            is SiloCastMessage.Error,
            is SiloCastMessage.HandoffChallenge,
            is SiloCastMessage.HandoffReady,
            -> Unit
        }
        return true
    }

    private fun beginHandoff(
        session: ControllerSession,
        offer: org.siloserver.silo.cast.SiloCastHandoffOffer,
        controllerDeviceId: String,
    ) {
        session.handoffJob?.cancel()
        session.pendingHandoffRequestId = offer.requestId
        session.remoteIdentityReady = false
        val owner = scope ?: return
        session.handoffJob = owner.launch {
            runCatching {
                val ready = identityManager.prepare(
                    offer,
                    controllerDeviceId,
                    session.controllerDeviceName,
                ) { challenge ->
                    if (activeSession !== session || session.pendingHandoffRequestId != offer.requestId) {
                        throw CancellationException("Stale handoff")
                    }
                    session.send(SiloCastMessage.HandoffChallenge(challenge))
                }
                if (activeSession !== session || session.pendingHandoffRequestId != offer.requestId) {
                    if (!ready.reused) identityManager.end()
                    return@launch
                }
                session.pendingHandoffRequestId = null
                session.handoffJob = null
                session.isAuthorized = true
                session.remoteIdentityReady = true
                refreshAdvertisement()
                session.send(SiloCastMessage.HandoffReady(ready))
                armReadyTimeout(session)
            }.onFailure { error ->
                if (error is CancellationException || activeSession !== session) return@onFailure
                runCatching {
                    session.send(
                        SiloCastMessage.HandoffCancel(
                            SiloCastHandoffCancel(
                                requestId = offer.requestId,
                                reason = "handoff_failed",
                                message = error.message ?: "Profile handoff failed.",
                            ),
                        ),
                    )
                }
                session.pendingHandoffRequestId = null
                session.handoffJob = null
            }
        }
    }

    private fun armReadyTimeout(session: ControllerSession) {
        session.readyTimeoutJob?.cancel()
        session.readyTimeoutJob = scope?.launch {
            delay(READY_TIMEOUT_MS)
            if (activeSession === session && session.remoteIdentityReady && activePlayer == null) {
                identityManager.end()
                session.remoteIdentityReady = false
                pendingPlayerGeneration = null
                session.isAuthorized = session.controllerServerId == identityManager.effectiveServerId
                refreshAdvertisement()
                runCatching {
                    session.send(
                        SiloCastMessage.Error(
                            SiloCastError(
                                code = "launch_timeout",
                                message = "No content was launched, so the TV profile was restored.",
                            ),
                        ),
                    )
                }
                session.goodbyeAndClose()
            }
        }
    }

    private fun refreshAdvertisement() {
        val port = synchronized(this) { serverSocket?.localPort } ?: return
        advertiser.start(
            port = port,
            serverId = identityManager.effectiveServerId,
            serverName = identityManager.effectiveServerName,
            playing = activePlayer != null,
        )
    }

    private suspend fun handleTemporarySessionExpired() {
        synchronized(this) { activePlayer?.adapter }?.handle(
            org.siloserver.silo.cast.SiloCastControlCommand.stop(),
        )
        identityManager.end()
        refreshAdvertisement()
        activeSession?.let { session ->
            runCatching {
                session.send(
                    SiloCastMessage.Error(
                        SiloCastError(
                            code = "temporary_session_expired",
                            message = "The phone profile session expired.",
                        ),
                    ),
                )
            }
        }
    }

    private suspend fun requireAuthorized(session: ControllerSession): Boolean {
        if (session.isAuthorized) return true
        session.send(
            SiloCastMessage.Error(
                SiloCastError(code = "unauthorized", message = "Connect with a matching Silo account first."),
            ),
        )
        return false
    }

    private suspend fun readLoop(
        tls: SiloCastTlsSession,
        onMessage: suspend (SiloCastMessage) -> Boolean,
    ) {
        val buffer = SiloCastFrameBuffer()
        val chunk = ByteArray(8 * 1024)
        while (true) {
            val read = withContext(Dispatchers.IO) { tls.input.read(chunk) }
            if (read < 0) return
            val payloads = buffer.append(chunk.copyOf(read))
            for (payload in payloads) {
                val text = payload.decodeToString()
                val message = json.decodeFromString(SiloCastMessage.serializer(), text)
                if (!onMessage(message)) return
            }
        }
    }

    private fun makeHello(): SiloCastHello {
        return SiloCastHello(
            role = SiloCastPeerRole.Tv,
            deviceName = deviceNameProvider(),
            deviceId = deviceIdProvider(),
            serverId = identityManager.effectiveServerId,
            serverName = identityManager.effectiveServerName,
            supportedVersions = SiloCastProtocol.supportedVersions,
        )
    }

    private fun currentState(): SiloCastPlaybackState =
        activePlayer?.stateProvider?.invoke() ?: idleState()

    private fun idleState(): SiloCastPlaybackState = SiloCastPlaybackState(
        contentId = null,
        sessionId = null,
        title = "Ready",
        subtitle = null,
        isPlaying = false,
        isLoading = false,
        isBuffering = false,
        currentTime = 0.0,
        duration = 0.0,
        audioTracks = emptyList(),
        subtitleTracks = emptyList(),
        selectedAudioTrackId = null,
        selectedSubtitleTrackId = null,
        qualityOptions = emptyList(),
        activeQualityId = "auto",
        isQualitySwitching = false,
        playbackSpeed = 1.0,
        videoGravity = "fit",
        hdrEnabled = false,
        supportsVideoGravity = false,
        supportsHDRToggle = false,
        volume = 1.0,
        isMuted = false,
        hasNextEpisode = false,
        nextEpisodeTitle = null,
        error = null,
    )

    private class ControllerSession(
        val socket: Socket,
        val tls: SiloCastTlsSession,
        private val json: Json,
    ) {
        val writeMutex = Mutex()

        @Volatile
        var isAuthorized: Boolean = false

        @Volatile
        var didReceiveHello: Boolean = false

        @Volatile
        var negotiatedVersion: Int? = null

        @Volatile
        var controllerDeviceId: String? = null

        @Volatile
        var controllerDeviceName: String? = null

        @Volatile
        var controllerServerId: String? = null

        @Volatile
        var pendingHandoffRequestId: String? = null

        @Volatile
        var remoteIdentityReady: Boolean = false

        @Volatile
        var missedHeartbeats: Int = 0
        var job: Job? = null
        var handoffJob: Job? = null
        var readyTimeoutJob: Job? = null

        suspend fun send(message: SiloCastMessage) {
            val payload = json.encodeToString(SiloCastMessage.serializer(), message).encodeToByteArray()
            val frame = SiloCastFrame.encode(payload)
            writeMutex.withLock {
                withContext(Dispatchers.IO) {
                    tls.output.write(frame)
                    tls.output.flush()
                }
            }
        }

        /**
         * Best-effort `close` goodbye ahead of the FIN, then teardown. The
         * goodbye lets the peer distinguish a deliberate disconnect from a
         * dropped link (Apple auto-reconnects on bare EOF). The write goes
         * through the same mutex as every other frame so a mid-flight state
         * push can't interleave with it; the whole thing runs suspending so
         * callers holding the receiver monitor don't block on a dead peer's
         * TCP buffers.
         */
        suspend fun goodbyeAndClose() {
            runCatching {
                kotlinx.coroutines.withTimeout(GOODBYE_TIMEOUT_MS) {
                    send(SiloCastMessage.Close())
                }
            }
            close()
        }

        fun close() {
            handoffJob?.cancel()
            readyTimeoutJob?.cancel()
            tls.close()
            runCatching { socket.close() }
            job?.cancel()
        }
    }

    private data class ActivePlayer(
        val adapter: TvSiloCastPlayerAdapter,
        val stateProvider: () -> SiloCastPlaybackState,
        val handoffGeneration: String?,
    )

    private companion object {
        const val TAG = "TvSiloCastReceiver"

        // Apple TVControlReceiver constants — keep in lockstep.
        const val STATE_INTERVAL_MS = 500L
        const val HEARTBEAT_INTERVAL_MS = 3_000L
        const val MAX_MISSED_HEARTBEATS = 3
        const val AUTH_GRACE_MS = 5_000L
        const val GOODBYE_TIMEOUT_MS = 1_000L
        const val READY_TIMEOUT_MS = 60_000L
    }
}
