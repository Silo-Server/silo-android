package org.siloserver.silo.tv.cast

import android.util.Log
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.siloserver.silo.cast.SiloCastControlCommand
import org.siloserver.silo.cast.SiloCastError
import org.siloserver.silo.cast.SiloCastHello
import org.siloserver.silo.cast.SiloCastHandoffCancel
import org.siloserver.silo.cast.SiloCastLaunchRequest
import org.siloserver.silo.cast.SiloCastMessage
import org.siloserver.silo.cast.SiloCastPeerRole
import org.siloserver.silo.cast.SiloCastPlaybackState
import org.siloserver.silo.cast.SiloCastProtocol
import org.siloserver.silo.common.cast.SiloCastFrame
import org.siloserver.silo.common.diagnostics.DiagnosticsCastLogger
import org.siloserver.silo.common.cast.SiloCastFrameBuffer
import org.siloserver.silo.common.cast.SiloCastNsdAdvertiser
import org.siloserver.silo.common.lan.SiloCastTls
import org.siloserver.silo.common.lan.SiloCastTlsSession
import org.siloserver.silo.network.AndroidServerRegistry
import org.siloserver.silo.network.ServerRegistry

/**
 * TV-side SiloCast receiver for the cross-platform v2 protocol shared with
 * silo-apple's TVControlReceiver:
 *
 * - Transport is TLS-PSK ([SiloCastTls]) over the advertised `_silocast._tcp`
 *   port. A connection only takes the single session slot once its hello
 *   arrives (newest wins), so a bare socket or a stalled handshake can't evict
 *   the phone in use, and a `resume` hello never displaces a different phone.
 * - The TV sends `hello` immediately after the TLS handshake. The controller
 *   must reply within [AUTH_GRACE_MS]. A same-server controller is authorized
 *   directly; a different-server launch must first complete the temporary
 *   remote-playback handoff. Playback state is never sent before authorization.
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
    private val identityManager: RemotePlaybackIdentityManager,
    private val deviceNameProvider: () -> String,
    private val deviceIdProvider: () -> String,
    /** Waits (bounded) for the last player's final stop to reach the server. */
    private val awaitPlaybackTeardown: suspend () -> Unit = {},
) {
    data class StandbyState(
        val controllerName: String?,
        val serverName: String?,
    ) {
        val controllerLabel: String get() = controllerName?.takeIf { it.isNotBlank() } ?: "phone"
    }

    private val json = Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    private var scope: CoroutineScope? = null
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var activeSession: ControllerSession? = null
    private val _standbyState = MutableStateFlow<StandbyState?>(null)
    val standbyState: StateFlow<StandbyState?> = _standbyState.asStateFlow()

    /** Bumped whenever the active-controller slot is force-cleared (server
     *  switch, disconnect, stop). Connections accepted before the bump must not
     *  be promoted — they belong to the previous epoch. */
    private var sessionEpoch: Long = 0
    private var activePlayer: ActivePlayer? = null
    private val volumeTracker = SiloCastVolumeTracker()
    private val launchRequestChannel = Channel<SiloCastLaunchRequest>(capacity = 1)
    val launchRequests: Flow<SiloCastLaunchRequest> = launchRequestChannel.receiveAsFlow()
    private var pendingPlayerIdentityGeneration: String? = null
    /** A launched title whose player hasn't registered yet; reported as loading (tvOS does the same). */
    @Volatile
    private var pendingLaunchContentId: String? = null
    private var identityEndJob: Job? = null
    /** Rejected generations already handled, so a repeat emission can't act twice. */
    private val expiredGenerations = mutableSetOf<String>()
    // stop() cancels the receiver scope, so cleanup for a ready-but-unconsumed
    // temporary identity must have an owner that survives that cancellation.
    private val identityCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Synchronized
    fun start() {
        if (scope != null) return
        DiagnosticsCastLogger.event("TV cast receiver started")
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        newScope.launch {
            val socket = ServerSocket(0).also { serverSocket = it }
            val server = serverRegistry.activeEntry.value
            val remote = identityManager.activeIdentity
            advertiser.start(
                port = socket.localPort,
                serverId = remote?.serverId ?: server?.id,
                serverName = remote?.serverName ?: server?.displayName,
                playing = activePlayer != null,
            )
            Log.i(TAG, "SiloCast listening on ${socket.localPort} for ${SiloCastProtocol.serviceType}")
            acceptLoop(socket)
        }
        // The server ended the phone's temporary session (its refresh was
        // rejected): stop what it was playing and restore the TV's own profile.
        newScope.launch {
            identityManager.rejectedGenerations.collect { rejected ->
                val generation = identityManager.activeIdentity?.generationId ?: return@collect
                if (generation in rejected) endExpiredIdentity(generation)
            }
        }
        // Keep the Bonjour TXT record in sync with the active server while the
        // receiver runs (it stays up across server switches until onStop). A
        // real switch also drops the live controller session — its hello was
        // authorized against the previous server, so keeping it would let an
        // old-server remote drive playback on the new one. Other entry updates
        // (a refreshed name, say) only refresh the record, as on tvOS.
        newScope.launch {
            var serverId = serverRegistry.activeEntry.value?.id
            serverRegistry.activeEntry.drop(1).collect { entry ->
                if (entry?.id != serverId) {
                    serverId = entry?.id
                    closePreviousController()
                    identityManager.end()
                }
                refreshAdvertisement()
            }
        }
    }

    @Synchronized
    fun stop() {
        DiagnosticsCastLogger.event("TV cast receiver stopped")
        advertiser.stop()
        val identityGeneration = identityManager.activeIdentity?.generationId
        pendingPlayerIdentityGeneration = null
        pendingLaunchContentId = null
        identityEndJob = null
        // Close the session directly (not via closePreviousController, which
        // launches the goodbye on `scope` — the scope we cancel a line later,
        // which would kill the goodbye before it writes). A direct close()
        // sends the FIN; the goodbye frame is best-effort and the socket
        // teardown below guarantees the peer disconnects regardless.
        sessionEpoch += 1
        activeSession?.close()
        activeSession = null
        _standbyState.value = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope?.cancel()
        scope = null
        if (identityGeneration != null) {
            identityCleanupScope.launch {
                // A player closing with the app (Home) is still sending its
                // final stop under this identity.
                awaitPlaybackTeardown()
                // Exact-generation guard: a rapid stop/start/new handoff must
                // not let the old shutdown clean up the replacement identity.
                identityManager.end(expectedGenerationId = identityGeneration)
            }
        }
    }

    @Synchronized
    fun registerPlayer(
        adapter: TvSiloCastPlayerAdapter,
        stateProvider: () -> SiloCastPlaybackState,
    ): Closeable {
        DiagnosticsCastLogger.event("TV cast player registered")
        identityEndJob?.cancel()
        identityEndJob = null
        // The launched title arrived: the ready-without-launch timeout and
        // the loading placeholder have done their job.
        activeSession?.let { session ->
            session.remoteLaunchReady = false
            session.readyTimeoutJob?.cancel()
            session.readyTimeoutJob = null
        }
        pendingLaunchContentId = null
        val identityGeneration = pendingPlayerIdentityGeneration
            ?: identityManager.activeIdentity?.generationId
        pendingPlayerIdentityGeneration = null
        val player = ActivePlayer(
            adapter = adapter,
            stateProvider = stateProvider,
            identityGeneration = identityGeneration,
        )
        activePlayer = player
        _standbyState.value = null
        advertiser.updatePlaying(true)
        return Closeable {
            synchronized(this) {
                if (activePlayer === player) {
                    DiagnosticsCastLogger.event("TV cast player unregistered")
                    activePlayer = null
                    advertiser.updatePlaying(false)
                    player.identityGeneration?.let { scheduleIdentityEnd(it) }
                    refreshStandbyState()
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
        DiagnosticsCastLogger.event("TV cast controller disconnected")
        activeSession = null
        _standbyState.value = null
        val owner = scope
        if (owner != null) {
            // Goodbye + teardown off the monitor — a blocking write to a
            // half-open peer must not stall registerPlayer/stop/accept.
            owner.launch { session.goodbyeAndClose() }
        } else {
            session.close()
        }
    }

    fun disconnectRemoteControl() {
        closePreviousController()
    }

    internal fun recordPlayerVolume(volume: Double) {
        volumeTracker.recordVolume(volume)
    }

    internal fun recordPlayerMuted(isMuted: Boolean, currentVolume: Double?) {
        volumeTracker.recordMuted(isMuted, currentVolume)
    }

    internal fun retainedPlayerVolume(): Double = volumeTracker.retainedAudibleVolume()

    internal fun resolvePlayerVolume(currentVolume: Double?): SiloCastVolumeState =
        volumeTracker.resolve(currentVolume)

    private suspend fun acceptLoop(socket: ServerSocket) {
        while (true) {
            val client = try {
                withContext(Dispatchers.IO) { socket.accept() }
            } catch (_: Throwable) {
                Log.i(TAG, "SiloCast listener stopped")
                return
            }
            val ownerScope = scope ?: run {
                runCatching { client.close() }
                return
            }
            // The phone in use keeps the session until this connection's hello
            // arrives; runControllerSession promotes it then.
            ownerScope.launch { runControllerSession(client) }
        }
    }

    private suspend fun runControllerSession(client: Socket) {
        val epochAtAccept = synchronized(this) { sessionEpoch }
        val tls = try {
            withContext(Dispatchers.IO) { SiloCastTls.accept(client) }
        } catch (t: Throwable) {
            Log.w(TAG, "SiloCast TLS handshake failed", t)
            DiagnosticsCastLogger.warning("TV cast handshake failed")
            runCatching { client.close() }
            return
        }
        val session = ControllerSession(socket = client, tls = tls, json = json, epoch = epochAtAccept)
        DiagnosticsCastLogger.event("TV cast controller connected")
        try {
            coroutineScope {
                session.job = coroutineContext[Job]

                // TV speaks first with identity only. Playback state is private
                // until the controller's hello has been authorized, and the
                // connection stays pending (not the active session) until then.
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
                            DiagnosticsCastLogger.warning("TV cast controller timed out")
                            session.close()
                            return@launch
                        }
                        session.send(SiloCastMessage.Ping())
                    }
                }
                val authWatchdog = launch {
                    delay(AUTH_GRACE_MS)
                    if (!session.didReceiveHello) {
                        Log.i(TAG, "SiloCast controller never authorized; closing")
                        DiagnosticsCastLogger.warning("TV cast authorization timed out")
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
            val orphanedReadyIdentity = if (session.remoteLaunchReady && activePlayer == null) {
                identityManager.activeIdentity?.generationId
            } else {
                null
            }
            synchronized(this) {
                if (activeSession === session) {
                    activeSession = null
                    _standbyState.value = null
                }
            }
            session.close()
            if (orphanedReadyIdentity != null) {
                scheduleIdentityEnd(orphanedReadyIdentity, READY_TIMEOUT_MS)
            }
        }
    }

    /** @return false to end the session's read loop. */
    private suspend fun handleMessage(session: ControllerSession, message: SiloCastMessage): Boolean {
        // Until its hello promotes it, a connection may only introduce itself
        // and keep the link alive; a replaced one is already on its way out.
        if (activeSession !== session &&
            message !is SiloCastMessage.Hello &&
            message !is SiloCastMessage.Ping &&
            message !is SiloCastMessage.Pong &&
            message !is SiloCastMessage.Close
        ) {
            return true
        }
        when (message) {
            is SiloCastMessage.Hello -> {
                if (session.didReceiveHello) return true
                val negotiated = SiloCastProtocol.negotiatedVersion(message.hello.supportedVersions)
                if (message.hello.role != SiloCastPeerRole.Phone || negotiated != SiloCastProtocol.version) {
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
                session.negotiatedVersion = negotiated
                session.controllerDeviceId = message.hello.deviceId
                session.controllerDeviceName = message.hello.deviceName
                session.controllerServerId = message.hello.serverId
                when (val promotion = promote(session, message.hello)) {
                    Promotion.Promoted -> Unit
                    Promotion.Stale -> {
                        session.goodbyeAndClose()
                        return false
                    }
                    is Promotion.Busy -> {
                        DiagnosticsCastLogger.event("TV cast resume refused: another controller is active")
                        session.send(
                            SiloCastMessage.Error(
                                SiloCastError(
                                    code = CONTROLLER_ACTIVE,
                                    message = "${promotion.controllerName ?: "Another phone"} is using this TV.",
                                ),
                            ),
                        )
                        session.goodbyeAndClose()
                        return false
                    }
                }
                val activeServerId = identityManager.activeIdentity?.serverId
                    ?: serverRegistry.activeServerId.value
                val offered = message.hello.serverId
                session.isAuthorized = AndroidServerRegistry.serverIdsMatch(offered, activeServerId)
                DiagnosticsCastLogger.event(
                    if (session.isAuthorized) "TV cast controller authorized" else "TV cast handoff required",
                )
                refreshStandbyState()
                if (session.isAuthorized) {
                    session.send(SiloCastMessage.State(currentState()))
                }
            }
            is SiloCastMessage.HandoffOffer -> {
                val offer = message.handoffOffer
                val controllerId = session.controllerDeviceId
                if (session.negotiatedVersion != SiloCastProtocol.version || controllerId == null) {
                    session.send(
                        SiloCastMessage.HandoffCancel(
                            SiloCastHandoffCancel(
                                requestId = offer.requestId,
                                reason = "version_unsupported",
                                message = "Update Silo on both devices to continue.",
                            ),
                        ),
                    )
                    return true
                }
                session.handoffJob?.cancel()
                identityEndJob?.cancel()
                identityEndJob = null
                session.handoffJob = scope?.launch {
                    try {
                        val outgoing = identityManager.activeIdentity?.generationId
                        // A rejected identity is replaced even for the same
                        // phone, so its title has to go first as well.
                        val outgoingRejected = outgoing in identityManager.rejectedGenerations.value
                        if (outgoing != null && (outgoingRejected || !identityManager.matches(offer, controllerId))) {
                            // Another phone or profile: finish the title playing
                            // under the current identity first, so its final
                            // stop still authenticates (tvOS does the same).
                            // This handoff ends that identity itself; the
                            // player's own delayed end would otherwise land
                            // mid-handoff and drop the new phone.
                            synchronized(this@TvSiloCastReceiver) {
                                activePlayer?.identityGeneration = null
                                if (pendingPlayerIdentityGeneration == outgoing) pendingPlayerIdentityGeneration = null
                            }
                            // Not cancellable: a phone leaving mid-handoff must
                            // not leave the previous phone's profile installed,
                            // and the player no longer ends it on its own.
                            withContext(NonCancellable) {
                                stopActivePlayer()
                                awaitPlaybackTeardown()
                                identityManager.end(expectedGenerationId = outgoing)
                                refreshAdvertisement()
                            }
                        }
                        val ready = identityManager.prepare(
                            offer = offer,
                            controllerDeviceId = controllerId,
                            controllerDeviceName = session.controllerDeviceName,
                        ) { challenge ->
                            if (activeSession === session) {
                                session.send(SiloCastMessage.HandoffChallenge(challenge))
                            }
                        }
                        if (activeSession !== session) {
                            identityManager.activeIdentity?.generationId?.let { generation ->
                                if (activePlayer == null) {
                                    withContext(NonCancellable) { identityManager.end(expectedGenerationId = generation) }
                                }
                            }
                            return@launch
                        }
                        session.isAuthorized = true
                        DiagnosticsCastLogger.event("TV cast handoff authorized")
                        session.remoteLaunchReady = true
                        refreshStandbyState()
                        refreshAdvertisement()
                        val readyGeneration = identityManager.activeIdentity?.generationId
                        session.send(SiloCastMessage.HandoffReady(ready))
                        session.send(SiloCastMessage.State(currentState()))
                        session.readyTimeoutJob?.cancel()
                        // Stays armed through launch until a player registers,
                        // so a launch whose player never opens can't leave the
                        // phone's profile installed.
                        session.readyTimeoutJob = launch {
                            delay(READY_TIMEOUT_MS)
                            // Ready-without-launch only abandons an UNUSED
                            // identity: with a player active (a reused-identity
                            // offer mid-playback), ending it would rip the
                            // token overlay out from under the live stream.
                            if (activeSession === session && session.remoteLaunchReady) {
                                session.remoteLaunchReady = false
                                if (activePlayer == null) {
                                    pendingLaunchContentId = null
                                    pendingPlayerIdentityGeneration = null
                                    identityManager.end(expectedGenerationId = readyGeneration)
                                    refreshAdvertisement()
                                    session.send(
                                        SiloCastMessage.Error(
                                            SiloCastError(
                                                code = "launch_timeout",
                                                message = "Playback didn't start, so the TV restored its own profile.",
                                            ),
                                        ),
                                    )
                                    session.goodbyeAndClose()
                                }
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        if (session.remoteLaunchReady && activePlayer == null) {
                            identityManager.activeIdentity?.generationId?.let {
                                identityManager.end(expectedGenerationId = it)
                            }
                            session.remoteLaunchReady = false
                            refreshAdvertisement()
                        }
                        if (activeSession === session) {
                            session.send(
                                SiloCastMessage.HandoffCancel(
                                    SiloCastHandoffCancel(
                                        requestId = offer.requestId,
                                        reason = "handoff_failed",
                                        message = t.message ?: "Remote playback handoff failed.",
                                    ),
                                ),
                            )
                        }
                    }
                }
            }
            is SiloCastMessage.HandoffCancel -> {
                session.handoffJob?.cancel()
                session.handoffJob = null
                // Disarm the ready-without-launch watchdog: the offer it was
                // guarding is dead, and with a player active (identity in use
                // by live playback) letting it fire would end the session.
                session.readyTimeoutJob?.cancel()
                session.readyTimeoutJob = null
                if (session.remoteLaunchReady && activePlayer == null) {
                    val generation = identityManager.activeIdentity?.generationId
                    if (generation != null) scheduleIdentityEnd(generation, 0L)
                }
                session.remoteLaunchReady = false
            }
            is SiloCastMessage.Launch -> {
                if (!requireAuthorized(session)) return true
                if (!session.remoteLaunchReady || identityManager.activeIdentity == null) {
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
                if (!AndroidServerRegistry.serverIdsMatch(
                        message.launch.serverId,
                        identityManager.activeIdentity?.serverId,
                    )
                ) {
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
                val generation = identityManager.activeIdentity?.generationId
                pendingPlayerIdentityGeneration = generation
                // Set before handing the launch over: its player may register
                // (and clear this) before trySend even returns.
                pendingLaunchContentId = message.launch.playback.contentId
                if (generation == null || !launchRequestChannel.trySend(message.launch).isSuccess) {
                    pendingPlayerIdentityGeneration = null
                    pendingLaunchContentId = null
                    session.send(
                        SiloCastMessage.Error(
                            SiloCastError(
                                code = "launch_failed",
                                message = "The TV could not open remote playback.",
                            ),
                        ),
                    )
                    return true
                }
                // The ready state and its timeout stay armed until the player
                // registers. Until then the title reports as loading, not idle.
                _standbyState.value = null
                session.send(SiloCastMessage.State(currentState()))
            }
            is SiloCastMessage.Control -> {
                if (!requireAuthorized(session)) return true
                if (message.control.name !in SiloCastControlCommand.knownNames) {
                    // A command from a newer (or retired, like set_hdr_enabled)
                    // remote. Ignored without an error, as tvOS does: the phone
                    // shows errors as banners, and nothing happened anyway.
                    Log.i(TAG, "SiloCast ignoring unsupported command ${message.control.name}")
                    return true
                }
                val player = activePlayer
                if (player == null) {
                    session.send(
                        SiloCastMessage.Error(
                            SiloCastError(code = "player_not_ready", message = "The TV player is not ready yet."),
                        ),
                    )
                    return true
                }
                // Media3 controllers and the Compose-backed state provider are
                // application-thread confined. The receiver reads the socket
                // on IO, so cross that boundary before touching the player.
                withContext(Dispatchers.Main.immediate) {
                    player.adapter.handle(message.control)
                }
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

    private suspend fun requireAuthorized(session: ControllerSession): Boolean {
        if (session.isAuthorized) return true
        session.send(
            SiloCastMessage.Error(
                SiloCastError(code = "unauthorized", message = "Connect with a matching Silo account first."),
            ),
        )
        return false
    }

    private fun scheduleIdentityEnd(
        generationId: String,
        delayMs: Long = IDENTITY_END_GRACE_MS,
    ) {
        identityEndJob?.cancel()
        val owner = scope ?: return
        identityEndJob = owner.launch {
            delay(delayMs)
            if (identityManager.activeIdentity?.generationId != generationId) return@launch
            // The outgoing player's final stop authenticates as this identity.
            awaitPlaybackTeardown()
            // A replacement title may have claimed the identity meanwhile.
            if (activePlayer != null || pendingPlayerIdentityGeneration == generationId) return@launch
            identityManager.end(expectedGenerationId = generationId)
            pendingPlayerIdentityGeneration = null
            // A launch whose player never opened is over too.
            if (activePlayer == null) pendingLaunchContentId = null
            refreshAdvertisement()
            reconcileAuthorizationAfterRestore()
        }
    }

    /** The TV's own profile is back: only a phone on the same server stays in control. */
    private suspend fun reconcileAuthorizationAfterRestore() {
        val session = activeSession ?: return
        session.remoteLaunchReady = false
        session.isAuthorized = AndroidServerRegistry.serverIdsMatch(
            session.controllerServerId,
            serverRegistry.activeServerId.value,
        )
        if (session.isAuthorized) {
            session.send(SiloCastMessage.State(currentState()))
            refreshStandbyState()
        } else {
            closePreviousController()
        }
    }

    /**
     * The server ended the phone's temporary session. Mirrors tvOS
     * `temporaryAuthExpired`: tell the phone, close the player, and restore the
     * TV's own profile without a logout call the dead credentials would fail.
     */
    private suspend fun endExpiredIdentity(generation: String) {
        if (!synchronized(expiredGenerations) { expiredGenerations.add(generation) }) return
        Log.i(TAG, "SiloCast temporary session expired")
        DiagnosticsCastLogger.warning("TV cast temporary session expired")
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
        identityEndJob?.cancel()
        identityEndJob = null
        activeSession?.readyTimeoutJob?.cancel()
        stopActivePlayer()
        pendingLaunchContentId = null
        pendingPlayerIdentityGeneration = null
        identityManager.end(expectedGenerationId = generation, notifyServer = false)
        refreshAdvertisement()
        reconcileAuthorizationAfterRestore()
    }

    /** Closes the player, if any, and waits (bounded) for it to unregister. */
    private suspend fun stopActivePlayer() {
        val player = synchronized(this) { activePlayer } ?: return
        withContext(Dispatchers.Main.immediate) {
            player.adapter.handle(SiloCastControlCommand.stop())
        }
        withTimeoutOrNull(PLAYER_EXIT_TIMEOUT_MS) {
            while (synchronized(this@TvSiloCastReceiver) { activePlayer === player }) delay(50)
        }
    }

    private sealed interface Promotion {
        data object Promoted : Promotion
        data object Stale : Promotion
        data class Busy(val controllerName: String?) : Promotion
    }

    /**
     * Makes [session] the active controller now that its hello names it. A
     * `resume` hello (a phone reconnecting or silently resuming, not a person
     * picking this TV) is refused while a different phone holds the session.
     */
    private fun promote(session: ControllerSession, hello: SiloCastHello): Promotion {
        val displaced = synchronized(this) {
            // A server switch, disconnect, or stop() since this connection
            // arrived: it belongs to the previous epoch.
            if (sessionEpoch != session.epoch) return Promotion.Stale
            val current = activeSession
            if (current === session) return Promotion.Promoted
            if (hello.resume == true && current != null && current.controllerDeviceId != hello.deviceId) {
                return Promotion.Busy(current.controllerDeviceName)
            }
            activeSession = session
            if (current != null) _standbyState.value = null
            current
        }
        if (displaced != null) {
            DiagnosticsCastLogger.event("TV cast controller replaced")
            // Goodbye off the monitor, so the phone doesn't reconnect.
            scope?.launch { displaced.goodbyeAndClose() } ?: displaced.close()
        }
        return Promotion.Promoted
    }

    private fun refreshStandbyState() {
        val session = activeSession
        _standbyState.value = if (
            session != null && session.isAuthorized && activePlayer == null && pendingLaunchContentId == null
        ) {
            StandbyState(
                controllerName = session.controllerDeviceName,
                serverName = identityManager.activeIdentity?.serverName
                    ?: serverRegistry.activeEntry.value?.displayName,
            )
        } else {
            null
        }
    }

    @Synchronized
    private fun refreshAdvertisement() {
        val port = serverSocket?.localPort ?: return
        val remote = identityManager.activeIdentity
        val saved = serverRegistry.activeEntry.value
        advertiser.start(
            port = port,
            serverId = remote?.serverId ?: saved?.id,
            serverName = remote?.serverName ?: saved?.displayName,
            playing = activePlayer != null,
        )
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
                // A kind added after this build is skipped, not fatal.
                val message = SiloCastMessage.decodeOrNull(json, payload.decodeToString()) ?: continue
                if (!onMessage(message)) return
            }
        }
    }

    private fun makeHello(): SiloCastHello {
        val server = serverRegistry.activeEntry.value
        val remote = identityManager.activeIdentity
        return SiloCastHello(
            role = SiloCastPeerRole.Tv,
            deviceName = deviceNameProvider(),
            deviceId = deviceIdProvider(),
            serverId = remote?.serverId ?: server?.id,
            serverName = remote?.serverName ?: server?.displayName,
            supportedVersions = SiloCastProtocol.supportedVersions,
        )
    }

    private suspend fun currentState(): SiloCastPlaybackState =
        withContext(Dispatchers.Main.immediate) {
            activePlayer?.stateProvider?.invoke()
                ?: pendingLaunchContentId?.let { idleState().copy(contentId = it, title = "Loading", isLoading = true) }
                ?: idleState()
        }

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
        /** [sessionEpoch] when accepted; promotion requires it to be current. */
        val epoch: Long,
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
        var remoteLaunchReady: Boolean = false

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
        var identityGeneration: String?,
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
        const val IDENTITY_END_GRACE_MS = 2_000L
        const val PLAYER_EXIT_TIMEOUT_MS = 5_000L

        /** Refusal of a `resume` hello while another phone holds the TV. */
        const val CONTROLLER_ACTIVE = "controller_active"
    }
}
