package org.siloserver.silo.tv.cast

import android.util.Log
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlinx.coroutines.flow.distinctUntilChangedBy
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
import org.siloserver.silo.cast.SiloCastHandoffOffer
import org.siloserver.silo.cast.SiloCastHandoffReady
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
import org.siloserver.silo.network.TokenManager

/**
 * TV-side SiloCast receiver for the cross-platform v2 protocol shared with
 * silo-apple's TVControlReceiver:
 *
 * - Transport is TLS-PSK ([SiloCastTls]) over the advertised `_silocast._tcp`
 *   port; newest controller wins the single session slot.
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
 * - A same-server phone offering the profile this TV is already signed in
 *   with gets `handoff_ready` at once and plays on the TV's own identity.
 *   A borrowed profile stays installed while its phone is connected, so the
 *   next title reuses it, and is handed back when that phone leaves, another
 *   controller takes over, or after [IDENTITY_IDLE_MS] with nothing playing.
 */
class TvSiloCastReceiver(
    private val advertiser: SiloCastNsdAdvertiser,
    private val serverRegistry: ServerRegistry,
    private val tokenManager: TokenManager,
    private val identityManager: RemotePlaybackIdentityManager,
    private val deviceNameProvider: () -> String,
    private val deviceIdProvider: () -> String,
    /** Suspends until the player's queued session teardown has finished. */
    private val awaitPlaybackTeardown: suspend () -> Unit,
    /** True while this TV is in a Watch Party, whose membership an identity swap would end. */
    private val inWatchParty: () -> Boolean = { false },
) {
    data class StandbyState(
        val controllerName: String?,
        val serverName: String?,
        /** Set while a full profile handoff prepares a title the phone is about to play. */
        val preparing: Preparing? = null,
    ) {
        val controllerLabel: String get() = controllerName?.takeIf { it.isNotBlank() } ?: "phone"

        data class Preparing(val title: String?)
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

    /** Bumped whenever the active-controller slot is force-cleared (new accept,
     *  server switch, stop). Handshakes started before the bump must not
     *  register — they belong to the previous epoch. */
    private var sessionEpoch: Long = 0
    private var activePlayer: ActivePlayer? = null
    private val volumeTracker = SiloCastVolumeTracker()
    private val launchRequestChannel = Channel<SiloCastLaunchRequest>(capacity = 1)
    val launchRequests: Flow<SiloCastLaunchRequest> = launchRequestChannel.receiveAsFlow()
    private var pendingPlayerIdentityGeneration: String? = null
    private var identityEndJob: Job? = null
    // A scheduled end that has committed to ending this generation. It and
    // launch admission decide under the receiver lock, so a launch is never
    // admitted onto an identity whose end has started.
    private var endingGenerationId: String? = null
    // stop() cancels the receiver scope, so cleanup for a ready-but-unconsumed
    // temporary identity must have an owner that survives that cancellation.
    private val identityCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Bumped by every start(). Handoffs tag the identity with the run that
    // claimed it, so stop()'s deferred cleanup can tell a returning phone's
    // claim from one made by the session it is tearing down.
    @Volatile
    private var receiverRun: Long = 0

    @Synchronized
    fun start() {
        if (scope != null) return
        receiverRun += 1
        DiagnosticsCastLogger.event("TV cast receiver started")
        val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = newScope
        newScope.launch {
            val socket = ServerSocket(0)
            // stop() can run while the socket is being created, and scope
            // cancellation can't interrupt that. Publish and advertise under the
            // lock only if this scope is still current; otherwise the stopped
            // receiver would keep listening and stay listed in Bonjour.
            val published = synchronized(this@TvSiloCastReceiver) {
                if (scope !== newScope) return@synchronized false
                serverSocket = socket
                refreshAdvertisement()
                true
            }
            if (!published) {
                runCatching { socket.close() }
                return@launch
            }
            Log.i(TAG, "SiloCast listening on ${socket.localPort} for ${SiloCastProtocol.serviceType}")
            acceptLoop(socket)
        }
        // Keep the Bonjour TXT record in sync with the active server while the
        // receiver runs (it stays up across server switches until onStop), and
        // drop the live controller session — its hello was authorized against
        // the previous server, so keeping it would let an old-server remote
        // drive playback on the new one. Only a different server or advertised
        // name counts: profile and last-used updates rewrite the entry too, and
        // re-registering on a profile clear raced the stop() that follows it,
        // losing the Bonjour goodbye so the TV stayed listed after sign-out.
        newScope.launch {
            serverRegistry.activeEntry
                .distinctUntilChangedBy { it?.id to it?.displayName }
                .drop(1)
                .collect {
                    closePreviousController()
                    identityManager.end()
                    // Re-advertises under the lock, and is a no-op once stop()
                    // has cleared the socket.
                    refreshAdvertisement()
                }
        }
    }

    @Synchronized
    fun stop() {
        DiagnosticsCastLogger.event("TV cast receiver stopped")
        advertiser.stop()
        val stoppedRun = receiverRun
        pendingPlayerIdentityGeneration = null
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
        // Always scheduled, even with no identity yet: a handoff from the
        // session being torn down can still install or reuse one after this.
        identityCleanupScope.launch {
            // The player's ON_STOP observer runs before this (Activity
            // onStop) and has already queued its final progress report and
            // stopSession, which ride on this temporary identity. Ending it
            // first revokes the session under them (401). The player's
            // unregister path cannot own this: its composition is not
            // disposed until the activity starts again. Wait for the queued
            // teardown (bounded, so the identity is always ended), then end it.
            withTimeoutOrNull(PLAYBACK_TEARDOWN_TIMEOUT_MS) { awaitPlaybackTeardown() }
            // Run guard: only a handoff from a later start() protects the
            // identity, whether it reused this generation or replaced it.
            // Claims from the stopped run's own sessions do not, since
            // nothing else is left to end them.
            identityManager.endIfNotClaimedSince(stoppedRun)
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
        val identityGeneration = pendingPlayerIdentityGeneration
            ?: identityManager.activeIdentity?.generationId
        pendingPlayerIdentityGeneration = null
        val player = ActivePlayer(
            adapter = adapter,
            stateProvider = stateProvider,
            identityGeneration = identityGeneration,
        )
        activePlayer = player
        activeSession?.let { clearPreparing(it, refresh = false) }
        _standbyState.value = null
        advertiser.updatePlaying(true)
        return Closeable {
            synchronized(this) {
                if (activePlayer === player) {
                    DiagnosticsCastLogger.event("TV cast player unregistered")
                    activePlayer = null
                    advertiser.updatePlaying(false)
                    val generation = player.identityGeneration
                    if (generation != null) {
                        // The phone that lent the profile is still here: keep
                        // it so that phone's next title starts without a new
                        // handoff, until it leaves or the TV sits idle.
                        if (isLenderConnected(generation)) {
                            scheduleIdentityEnd(generation, IDENTITY_IDLE_MS)
                        } else {
                            scheduleIdentityEnd(generation, keepIfLenderReturns = true)
                        }
                    }
                    refreshStandbyState()
                }
            }
            // After the slot is cleared: a handoff waiting on this checks it next.
            player.unregistered.complete(Unit)
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
            DiagnosticsCastLogger.warning("TV cast handshake failed")
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
        DiagnosticsCastLogger.event("TV cast controller connected")
        try {
            coroutineScope {
                session.job = coroutineContext[Job]

                // TV speaks first with identity only. Playback state is private
                // until the controller's hello has been authorized.
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
            synchronized(this) {
                if (activeSession === session) {
                    activeSession = null
                    _standbyState.value = null
                }
            }
            session.close()
            // The phone that lent the profile left (disconnected, dropped, was
            // disconnected from the TV, or lost the slot to another phone).
            // Hand the profile back unless a title is playing on it or on its
            // way to the player; playback outlives a lost socket.
            // A phone that reconnects within the grace keeps it.
            val lent = identityManager.activeIdentity
                ?.takeIf { it.controllerDeviceId == session.controllerDeviceId }
            if (lent != null && isIdentityIdle()) {
                scheduleIdentityEnd(lent.generationId, keepIfLenderReturns = true)
            }
        }
    }

    /** @return false to end the session's read loop. */
    private suspend fun handleMessage(session: ControllerSession, message: SiloCastMessage): Boolean {
        when (message) {
            is SiloCastMessage.Hello -> {
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
                // A handoff swaps in the phone's profile, and any identity
                // change leaves the Watch Party this TV is in.
                if (inWatchParty()) {
                    session.send(
                        SiloCastMessage.HandoffCancel(
                            SiloCastHandoffCancel(
                                requestId = offer.requestId,
                                reason = "watch_party_active",
                                message = "Leave the Watch Party on the TV first.",
                            ),
                        ),
                    )
                    return true
                }
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
                // A reused identity must survive a pending cleanup. One this
                // offer would replace stays until approval, so its cleanup still
                // runs if the handoff fails; after a replacement it finds a newer
                // generation and does nothing.
                if (identityManager.matches(offer, controllerId)) {
                    identityEndJob?.cancel()
                    identityEndJob = null
                }
                session.remoteLaunchReady = false
                session.ownIdentityProfileId = null
                val run = receiverRun
                session.handoffJob = scope?.launch {
                    try {
                        // The hello may have been judged against a phone's
                        // identity that is gone now; with none installed, the
                        // TV's own server decides.
                        if (identityManager.activeIdentity == null) {
                            session.isAuthorized = AndroidServerRegistry.serverIdsMatch(
                                session.controllerServerId,
                                serverRegistry.activeServerId.value,
                            )
                            refreshStandbyState()
                        }
                        ownIdentityReady(session, offer)?.let { ready ->
                            clearPreparing(session)
                            session.ownIdentityProfileId = offer.profileId
                            DiagnosticsCastLogger.event("TV cast handoff skipped on own profile")
                            session.send(SiloCastMessage.HandoffReady(ready))
                            session.send(SiloCastMessage.State(currentState()))
                            return@launch
                        }
                        var fullHandoff = false
                        val ready = identityManager.prepare(
                            offer = offer,
                            controllerDeviceId = controllerId,
                            controllerDeviceName = session.controllerDeviceName,
                            receiverRun = run,
                            onFullHandoff = {
                                fullHandoff = true
                                showPreparing(session, offer.title)
                            },
                            beforeActivation = ::stopPlayerBeforeIdentitySwap,
                        ) { challenge ->
                            if (activeSession === session) {
                                session.send(SiloCastMessage.HandoffChallenge(challenge))
                            }
                        }
                        // A reuse is instant; drop a cancelled offer's title.
                        if (!fullHandoff) clearPreparing(session)
                        if (activeSession !== session) {
                            identityManager.end()
                            return@launch
                        }
                        session.isAuthorized = true
                        DiagnosticsCastLogger.event("TV cast handoff authorized")
                        session.remoteLaunchReady = true
                        refreshStandbyState()
                        refreshAdvertisement()
                        session.send(SiloCastMessage.HandoffReady(ready))
                        session.send(SiloCastMessage.State(currentState()))
                        // Replaces the old ready-without-launch timeout: with
                        // nothing playing, the profile goes back after the
                        // idle limit. A player cancels this when it opens.
                        if (activePlayer == null) {
                            identityManager.activeIdentity?.let {
                                scheduleIdentityEnd(it.generationId, IDENTITY_IDLE_MS)
                            }
                        }
                        if (fullHandoff) {
                            session.preparingClearJob?.cancel()
                            session.preparingClearJob = scope?.launch {
                                delay(PREPARING_AFTER_READY_MS)
                                clearPreparing(session)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        clearPreparing(session)
                        if (session.remoteLaunchReady && activePlayer == null) {
                            identityManager.end()
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
                session.remoteLaunchReady = false
                session.ownIdentityProfileId = null
                clearPreparing(session)
                // The phone is still here, so a profile it lent stays for its
                // next title, as on tvOS. Make sure the idle limit covers one
                // a cancelled handoff installed without arming it.
                identityManager.activeIdentity
                    ?.takeIf {
                        it.controllerDeviceId == session.controllerDeviceId &&
                            isIdentityIdle() &&
                            identityEndJob?.isActive != true
                    }
                    ?.let { scheduleIdentityEnd(it.generationId, IDENTITY_IDLE_MS) }
            }
            is SiloCastMessage.Launch -> {
                if (!requireAuthorized(session)) return true
                // A Watch Party player is never silently replaced by a cast.
                activePlayer?.adapter?.launchRefusal?.invoke()?.let { refusal ->
                    session.send(
                        SiloCastMessage.Error(
                            SiloCastError(code = "watch_party_active", message = refusal),
                        ),
                    )
                    return true
                }
                // In a party lobby no party player is registered yet, but a
                // solo launch would still take the shared player from the room.
                if (inWatchParty()) {
                    session.send(
                        SiloCastMessage.Error(
                            SiloCastError(code = "watch_party_active", message = "Leave the Watch Party on the TV first."),
                        ),
                    )
                    return true
                }
                val ownProfileId = session.ownIdentityProfileId
                // Launch-ready on the TV's own identity: still signed in as
                // that profile with no phone's identity installed since.
                val onOwnIdentity = ownProfileId != null &&
                    identityManager.activeIdentity == null &&
                    !tokenManager.hasTemporaryScope() &&
                    tokenManager.getProfileId() == ownProfileId
                // activeIdentity is unpublished as soon as an end starts, so a
                // launch never lands on an identity being handed back.
                val borrowed = identityManager.activeIdentity?.takeIf { session.remoteLaunchReady }
                if (!onOwnIdentity && borrowed == null) {
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
                val launchServerId = if (onOwnIdentity) serverRegistry.activeServerId.value else borrowed?.serverId
                if (!AndroidServerRegistry.serverIdsMatch(message.launch.serverId, launchServerId)) {
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
                val generation = if (onOwnIdentity) null else borrowed?.generationId
                val admitted = synchronized(this) {
                    val stillBorrowable = generation == null ||
                        (identityManager.activeIdentity?.generationId == generation && endingGenerationId != generation)
                    if (stillBorrowable) pendingPlayerIdentityGeneration = generation
                    stillBorrowable
                }
                if (!admitted) {
                    session.remoteLaunchReady = false
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
                if (!launchRequestChannel.trySend(message.launch).isSuccess) {
                    pendingPlayerIdentityGeneration = null
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
                session.remoteLaunchReady = false
                session.ownIdentityProfileId = null
                // The TV is opening the player now; the preparing view is done.
                clearPreparing(session, refresh = false)
                _standbyState.value = null
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

    /**
     * Stops the registered player and waits for its server session to close.
     * An approved handoff runs this just before it installs the phone's
     * identity, including for a title the TV started under its own account:
     * the player's stop rides on the identity it started under, so after the
     * swap it is refused and the session lingers beside the phone's. Runs under
     * the identity manager's lock, which also holds off the stopped player's
     * own identity cleanup until the swap is done.
     */
    private suspend fun stopPlayerBeforeIdentitySwap() {
        val player = activePlayer ?: return
        withContext(Dispatchers.Main.immediate) {
            player.adapter.handle(SiloCastControlCommand(name = SiloCastControlCommand.Stop))
        }
        val tornDown = withTimeoutOrNull(PLAYBACK_TEARDOWN_TIMEOUT_MS) {
            // The exit queues the session stop before the route unregisters.
            player.unregistered.await()
            awaitPlaybackTeardown()
        } != null
        // The player or its session stop outlived the teardown limit, or
        // something started playing meanwhile; swapping the identity now would
        // strand that session.
        check(tornDown && activePlayer == null) { "The TV is still playing. Try again." }
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

    /**
     * Ends [generationId] after [delayMs]. With [keepIfLenderReturns] (the
     * lending phone left), a phone back in the slot by then keeps it under
     * the idle limit instead: a dropped link reconnects within seconds.
     */
    private fun scheduleIdentityEnd(
        generationId: String,
        delayMs: Long = IDENTITY_END_GRACE_MS,
        keepIfLenderReturns: Boolean = false,
    ) {
        identityEndJob?.cancel()
        val owner = scope ?: return
        identityEndJob = owner.launch {
            delay(delayMs)
            if (keepIfLenderReturns && isLenderConnected(generationId) && isIdentityIdle()) {
                scheduleIdentityEnd(generationId, IDENTITY_IDLE_MS)
                return@launch
            }
            // Commit under the lock launch admission uses: a launch admitted
            // first keeps the identity (its player schedules the next end);
            // once committed, a later launch is refused.
            val commit = synchronized(this@TvSiloCastReceiver) {
                if (isIdentityIdle()) {
                    endingGenerationId = generationId
                    true
                } else {
                    false
                }
            }
            if (!commit) return@launch
            // Cancellable only while waiting: a new offer may still keep the
            // identity, but once the logout starts it runs to the end, and
            // that offer's prepare() waits for it and takes the full path.
            val ended = try {
                withContext(NonCancellable) {
                    identityManager.end(generationId).also { done ->
                        if (done) {
                            // Only this generation's stale launch: a newer one
                            // admitted meanwhile keeps its protection.
                            synchronized(this@TvSiloCastReceiver) {
                                if (pendingPlayerIdentityGeneration == generationId) {
                                    pendingPlayerIdentityGeneration = null
                                }
                            }
                            refreshAdvertisement()
                        }
                    }
                }
            } finally {
                synchronized(this@TvSiloCastReceiver) {
                    if (endingGenerationId == generationId) endingGenerationId = null
                }
            }
            if (!ended) return@launch
            // A newer offer cancelled this mid-logout and now owns the
            // session's authorization; judging it here could drop a phone
            // that is still handing off.
            if (!isActive) return@launch
            val session = activeSession
            if (session != null) {
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
        }
    }

    /** No title is playing on, or on its way to, a phone's identity. */
    private fun isIdentityIdle(): Boolean = activePlayer == null && pendingPlayerIdentityGeneration == null

    /** Whether the phone that lent [generationId] holds the controller slot. */
    private fun isLenderConnected(generationId: String): Boolean {
        val identity = identityManager.activeIdentity ?: return false
        val session = activeSession ?: return false
        return identity.generationId == generationId &&
            session.isAuthorized &&
            session.controllerDeviceId == identity.controllerDeviceId
    }

    /**
     * The `handoff_ready` for an offer the TV can play on its own identity
     * (see [RemotePlaybackOwnIdentityPolicy]), or null when the offer needs
     * the full handoff.
     */
    private suspend fun ownIdentityReady(
        session: ControllerSession,
        offer: SiloCastHandoffOffer,
    ): SiloCastHandoffReady? {
        val ownServerId = serverRegistry.activeServerId.value
        val ownScope = tokenManager.snapshotCurrentScope()
        val accepts = RemotePlaybackOwnIdentityPolicy.accepts(
            authorizedAtHello = session.isAuthorized &&
                AndroidServerRegistry.serverIdsMatch(session.controllerServerId, ownServerId),
            holdsTemporaryIdentity = identityManager.activeIdentity != null || tokenManager.hasTemporaryScope(),
            signedIn = !tokenManager.getAccessToken().isNullOrBlank(),
            ownServerId = ownScope?.serverId?.takeIf { AndroidServerRegistry.serverIdsMatch(it, ownServerId) },
            ownProfileId = ownScope?.profileId,
            offerServerId = offer.serverId,
            offerProfileId = offer.profileId,
        )
        if (!accepts) return null
        return SiloCastHandoffReady(
            requestId = offer.requestId,
            serverId = offer.serverId,
            profileId = offer.profileId,
            // Required on the wire, though neither phone reads it. The TV's
            // own sign-in has no session deadline to report, so this uses
            // the same one-day fallback as a handoff whose server omits one.
            sessionExpiresAt = Instant.ofEpochMilli(System.currentTimeMillis() + OWN_IDENTITY_SESSION_MS).toString(),
            reused = true,
        )
    }

    private fun showPreparing(session: ControllerSession, title: String?) {
        session.preparingClearJob?.cancel()
        session.preparingClearJob = null
        session.preparing = StandbyState.Preparing(
            title = title?.trim()?.take(MAX_PREPARING_TITLE_LENGTH)?.ifBlank { null },
        )
        refreshStandbyState()
    }

    private fun clearPreparing(session: ControllerSession, refresh: Boolean = true) {
        session.preparingClearJob?.cancel()
        session.preparingClearJob = null
        if (session.preparing == null) return
        session.preparing = null
        if (refresh) refreshStandbyState()
    }

    private fun refreshStandbyState() {
        val session = activeSession
        val preparing = session?.preparing
        // A cross-server phone is not authorized until its handoff finishes,
        // but the TV still shows what it is preparing for that phone.
        _standbyState.value = if (session != null && activePlayer == null && (session.isAuthorized || preparing != null)) {
            StandbyState(
                controllerName = session.controllerDeviceName,
                serverName = identityManager.activeIdentity?.serverName
                    ?: serverRegistry.activeEntry.value?.displayName,
                preparing = preparing,
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
            activePlayer?.stateProvider?.invoke() ?: idleState()
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

        /** Launch-ready on the TV's own identity for this profile (no handoff ran). */
        @Volatile
        var ownIdentityProfileId: String? = null

        @Volatile
        var preparing: StandbyState.Preparing? = null

        @Volatile
        var missedHeartbeats: Int = 0
        var job: Job? = null
        var handoffJob: Job? = null
        var preparingClearJob: Job? = null

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
            preparingClearJob?.cancel()
            tls.close()
            runCatching { socket.close() }
            job?.cancel()
        }
    }

    private data class ActivePlayer(
        val adapter: TvSiloCastPlayerAdapter,
        val stateProvider: () -> SiloCastPlaybackState,
        val identityGeneration: String?,
        val unregistered: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    private companion object {
        const val TAG = "TvSiloCastReceiver"

        // Apple TVControlReceiver constants — keep in lockstep.
        const val STATE_INTERVAL_MS = 500L
        const val HEARTBEAT_INTERVAL_MS = 3_000L
        const val MAX_MISSED_HEARTBEATS = 3
        const val AUTH_GRACE_MS = 5_000L
        const val GOODBYE_TIMEOUT_MS = 1_000L
        const val IDENTITY_END_GRACE_MS = 2_000L
        const val PLAYBACK_TEARDOWN_TIMEOUT_MS = 15_000L
        const val IDENTITY_IDLE_MS = 10 * 60_000L
        const val PREPARING_AFTER_READY_MS = 20_000L
        const val OWN_IDENTITY_SESSION_MS = 24 * 60 * 60 * 1_000L
        const val MAX_PREPARING_TITLE_LENGTH = 120
    }
}
