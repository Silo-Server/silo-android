package org.siloserver.silo.tv.cast

import android.util.Log
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.time.Instant
import java.util.UUID
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
import org.siloserver.silo.cast.SiloCastCloseReason
import org.siloserver.silo.cast.SiloCastControlCommand
import org.siloserver.silo.cast.SiloCastError
import org.siloserver.silo.cast.SiloCastFeature
import org.siloserver.silo.cast.SiloCastHello
import org.siloserver.silo.cast.SiloCastHandoffCancel
import org.siloserver.silo.cast.SiloCastHandoffCancelReason
import org.siloserver.silo.cast.SiloCastHandoffOffer
import org.siloserver.silo.cast.SiloCastHandoffReady
import org.siloserver.silo.cast.SiloCastLaunchRequest
import org.siloserver.silo.cast.SiloCastMessage
import org.siloserver.silo.cast.SiloCastNowPlayingSummary
import org.siloserver.silo.cast.SiloCastPeerRole
import org.siloserver.silo.cast.SiloCastPlaybackState
import org.siloserver.silo.cast.SiloCastProtocol
import org.siloserver.silo.common.cast.SiloCastFrame
import org.siloserver.silo.common.diagnostics.DiagnosticsCastLogger
import org.siloserver.silo.common.cast.SiloCastFrameBuffer
import org.siloserver.silo.common.cast.SiloCastNsdAdvertiser
import org.siloserver.silo.common.lan.SiloCastTls
import org.siloserver.silo.common.lan.SiloCastTlsSession
import org.siloserver.silo.model.server.ServerEntry
import org.siloserver.silo.network.AndroidServerRegistry
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionPhase
import org.siloserver.silo.network.TokenManager

/**
 * TV-side SiloCast receiver for the cross-platform v2 protocol shared with
 * silo-apple's TVControlReceiver:
 *
 * - Transport is TLS-PSK ([SiloCastTls]) over the advertised `_silocast._tcp`
 *   port. Up to [TvSiloCastReceiverPolicy.MAX_CONTROLLERS] controllers stay
 *   connected at once (phones, tablets, integrations), each with its own
 *   heartbeat, authorization and command budget. The player, the borrowed
 *   phone identity, the one in-flight handoff and the standby screen are
 *   shared by all of them.
 * - The TV sends `hello` immediately after the TLS handshake. The controller
 *   must reply within [AUTH_GRACE_MS]. A same-server controller is authorized
 *   directly; a different-server launch must first complete the temporary
 *   remote-playback handoff. Playback state is never sent before authorization.
 * - `launch`/`control` before authorization → `error unauthorized`.
 * - Heartbeat: the TV pings every [HEARTBEAT_INTERVAL_MS]; only a `pong`
 *   proves the controller can still receive, so only `pong` resets the
 *   missed counter. More than [MAX_MISSED_HEARTBEATS] misses → drop.
 * - One state tick every [STATE_INTERVAL_MS] (Apple's 500ms cadence) goes to
 *   every authorized controller, plus one after every accepted command.
 *   Errors go only to the controller that caused them.
 * - A deliberate teardown sends a `close` frame ahead of the FIN so the
 *   controller can tell "disconnected on purpose" from a dropped link; peers
 *   that list `close_reason` also learn why.
 * - A same-server phone offering the profile this TV is already signed in
 *   with gets `handoff_ready` at once and plays on the TV's own identity.
 *   A borrowed profile stays installed while its phone is connected, so the
 *   next title reuses it, and is handed back when that phone leaves, another
 *   controller takes over, or after [IDENTITY_IDLE_MS] with nothing playing.
 *   Reusing it needs the connection that completed the handoff or the resume
 *   token issued to it, since device IDs are self-reported.
 * - A handoff that would replace a title another profile is watching asks
 *   first (`replace_confirmation_required`) when the controller can ask.
 */
class TvSiloCastReceiver(
    private val advertiser: SiloCastNsdAdvertiser,
    private val serverRegistry: ServerRegistry,
    private val tokenManager: TokenManager,
    /** Reports sign-in, profile, server and borrowed-identity changes. */
    private val identityTransitions: IdentityTransitionBarrier,
    private val identityManager: RemotePlaybackIdentityManager,
    private val deviceNameProvider: () -> String,
    private val deviceIdProvider: () -> String,
    /** Suspends until the player's queued session teardown has finished. */
    private val awaitPlaybackTeardown: suspend () -> Unit,
    /** True while this TV is in a Watch Party, whose membership an identity swap would end. */
    private val inWatchParty: () -> Boolean = { false },
    /** The TV's own active profile name, for the replace prompt on a title it plays on its own identity. */
    private val ownProfileNameProvider: () -> String? = { null },
    private val nowMs: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    /** Poster and synopsis for a content ID; see [SiloCastNowPlayingMetadataResolver]. */
    nowPlayingMetadataLoader: suspend (contentId: String, allowCached: Boolean) -> SiloCastNowPlayingMetadata? =
        { _, _ -> null },
    /**
     * Probes a saved server's deployment identity and records it on the entry,
     * returning it, or null when the server can't be reached or predates the
     * contract. See
     * [org.siloserver.silo.repository.ServerIdentityRepository.identityOf].
     */
    private val recordServerIdentity: suspend (ServerEntry) -> String? = { null },
    wallClockMs: () -> Long = System::currentTimeMillis,
) {
    data class StandbyState(
        val controllerName: String?,
        val serverName: String?,
        /** Set while a full profile handoff prepares a title the phone is about to play. */
        val preparing: Preparing? = null,
        /** Other non-passive controllers waiting besides [controllerName]. */
        val otherControllerCount: Int = 0,
    ) {
        val controllerLabel: String
            get() {
                val name = controllerName?.takeIf { it.isNotBlank() } ?: "phone"
                return when {
                    otherControllerCount <= 0 -> name
                    otherControllerCount == 1 -> "$name and 1 other device"
                    else -> "$name and $otherControllerCount other devices"
                }
            }

        data class Preparing(val title: String?)
    }

    private val json = Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
    }

    private var scope: CoroutineScope? = null
    private var serverSocket: ServerSocket? = null

    // Every connected controller by connection id, in acceptance order.
    // Guarded by the receiver lock.
    private val controllers = LinkedHashMap<String, ControllerSession>()
    private var controllerSequence: Long = 0

    // The one in-flight handoff. A newer offer from another controller
    // cancels it with `superseded`. Guarded by the receiver lock.
    private var pendingHandoff: PendingHandoff? = null

    // Who may reuse the active borrowed identity without a new approval: the
    // connection whose handoff installed it, or whoever presents the resume
    // token issued to that connection. Guarded by the receiver lock.
    private var issuedIdentity: IssuedIdentity? = null

    private val _standbyState = MutableStateFlow<StandbyState?>(null)
    val standbyState: StateFlow<StandbyState?> = _standbyState.asStateFlow()

    /** Bumped whenever every controller is force-closed (server switch, stop).
     *  Handshakes started before the bump must not register — they belong to
     *  the previous epoch. */
    private var receiverEpoch: Long = 0
    @Volatile
    private var activePlayer: ActivePlayer? = null
    private val volumeTracker = SiloCastVolumeTracker()
    private val launchRequestChannel = Channel<Launch>(capacity = 1)
    val launchRequests: Flow<Launch> = launchRequestChannel.receiveAsFlow()
    // The admitted launch on its way to the player, until its player registers
    // or the launch expires. Non-null holds off other launches and handoffs;
    // cleanup clears it only for its own launch.
    private var pendingLaunch: AdmittedLaunch? = null
    // The launch whose player registered last. Its player registers again when
    // its screen is recreated (an activity restart), which is allowed only
    // while the identity it was admitted on is unchanged.
    private var registeredLaunch: AdmittedLaunch? = null
    // Counts sign-in, profile, server and borrowed-identity changes that affect
    // the identity in use. A launch stays current only while it is unchanged;
    // removing some other saved server does not count.
    private var identityRevision: Long = 0

    init {
        identityTransitions.installGate { transition ->
            if (transition.phase == IdentityTransitionPhase.WILL_CHANGE && transition.affectsCurrentIdentity) {
                synchronized(this@TvSiloCastReceiver) { identityRevision += 1 }
            }
        }
    }
    private var identityEndJob: Job? = null
    // A scheduled end that has committed to ending this generation. It and
    // launch admission decide under the receiver lock, so a launch is never
    // admitted onto an identity whose end has started.
    private var endingGenerationId: String? = null
    // stop() cancels the receiver scope, so cleanup for a ready-but-unconsumed
    // temporary identity must have an owner that survives that cancellation.
    private val identityCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Poster and synopsis for the playing title, sent with its state. */
    private val metadataScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nowPlayingMetadata = SiloCastNowPlayingMetadataResolver(
        scope = metadataScope,
        load = { contentId, allowCached ->
            runCatching { nowPlayingMetadataLoader(contentId, allowCached) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
        },
        nowEpochMs = wallClockMs,
        onChange = { metadataScope.launch { runRecovering("metadata state") { broadcastState() } } },
    )
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
        // drop the live controllers — their hellos were authorized against the
        // previous server, so keeping them would let an old-server remote drive
        // playback on the new one. Only a different server or advertised name
        // counts: profile and last-used updates rewrite the entry too, and
        // re-registering on a profile clear raced the stop() that follows it,
        // losing the Bonjour goodbye so the TV stayed listed after sign-out.
        newScope.launch {
            serverRegistry.activeEntry
                .distinctUntilChangedBy { it?.id to it?.displayName }
                .drop(1)
                .collect {
                    closeAllControllers(SiloCastCloseReason.Unauthorized)
                    identityManager.end()
                    // Re-advertises under the lock, and is a no-op once stop()
                    // has cleared the socket.
                    refreshAdvertisement()
                }
        }
        // Phones on another address of this server find the TV by its
        // deployment identity. Release builds record that identity only when
        // a phone pairs the TV, so a TV signed in any other way advertised
        // none and those phones didn't list it. Record it once per server
        // while the receiver runs; an unreachable server is tried again on
        // the next start.
        newScope.launch {
            serverRegistry.activeEntry
                .distinctUntilChangedBy { it?.id }
                .collect { entry ->
                    if (entry == null || !entry.verifiedServerId.isNullOrBlank()) return@collect
                    runRecovering("server identity check") {
                        if (recordServerIdentity(entry) != null) refreshAdvertisedIdentity()
                    }
                }
        }
        // One state tick for every controller, built once. These jobs are
        // roots of a SupervisorJob scope with no handler, so a throw would
        // crash the app; a failed tick or expiry is logged and skipped.
        newScope.launch {
            while (isActive) {
                runRecovering("state tick") { broadcastState() }
                delay(STATE_INTERVAL_MS)
            }
        }
        // A borrowed profile whose session the server refused to refresh.
        newScope.launch {
            tokenManager.temporarySessionExpired.collect { generation ->
                runRecovering("temporary session expiry") { onTemporarySessionExpired(generation) }
            }
        }
    }

    /** Runs [block], logging instead of throwing: a root job's exception would crash the app. */
    private suspend fun runRecovering(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "SiloCast $what failed", t)
        }
    }

    @Synchronized
    fun stop() {
        DiagnosticsCastLogger.event("TV cast receiver stopped")
        advertiser.stop()
        nowPlayingMetadata.track(null)
        val stoppedRun = receiverRun
        pendingLaunch = null
        // A launch navigation has not taken yet belongs to the session being
        // torn down; it must not open when the TV app comes back.
        launchRequestChannel.tryReceive()
        identityEndJob = null
        // Close the sessions directly (not via a goodbye launched on `scope` —
        // the scope we cancel a line later, which would kill the goodbye before
        // it writes). A direct close() sends the FIN; the socket teardown below
        // guarantees the peers disconnect regardless.
        receiverEpoch += 1
        pendingHandoff = null
        issuedIdentity = null
        controllers.values.toList().forEach { it.close() }
        controllers.clear()
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

    /**
     * Registers the player on screen. [launchId] is the [Launch.id] the player
     * was opened for, if a phone launched it. Navigation already refuses a stale
     * launch; if one goes stale in the moment before its player registers
     * (expired, or its identity changed), nothing is registered and
     * [onStaleLaunch] runs so the screen closes instead of playing on.
     */
    @Synchronized
    fun registerPlayer(
        adapter: TvSiloCastPlayerAdapter,
        launchId: String? = null,
        onStaleLaunch: () -> Unit = {},
        stateProvider: () -> SiloCastPlaybackState,
    ): Closeable {
        if (launchId != null && !confirmLaunchLocked(launchId)) {
            DiagnosticsCastLogger.warning("TV cast launch expired before its player opened")
            onStaleLaunch()
            return Closeable {}
        }
        DiagnosticsCastLogger.event("TV cast player registered")
        identityEndJob?.cancel()
        identityEndJob = null
        // Only the launched player takes the launch's generation; another
        // player leaves the pending launch to its own player.
        val launched = pendingLaunch?.takeIf { launchId != null && it.id == launchId }
        val identityGeneration = launched?.generation ?: identityManager.activeIdentity?.generationId
        if (launched != null) {
            pendingLaunch = null
            registeredLaunch = launched
        }
        val player = ActivePlayer(
            adapter = adapter,
            stateProvider = stateProvider,
            identityGeneration = identityGeneration,
        )
        activePlayer = player
        controllers.values.forEach { clearPreparing(it, refresh = false) }
        _standbyState.value = null
        advertiser.updatePlaying(true)
        return Closeable {
            synchronized(this) {
                if (activePlayer === player) {
                    DiagnosticsCastLogger.event("TV cast player unregistered")
                    activePlayer = null
                    nowPlayingMetadata.track(null)
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

    /**
     * The TV's own Disconnect (standby screen). Closes every controller that
     * put the standby screen up; passive ones (integrations, silent
     * auto-resume) never did and stay connected.
     */
    fun disconnectRemoteControl() {
        val closing = synchronized(this) {
            controllers.values.filter { !it.passive }.onEach { removeControllerLocked(it) }
        }
        if (closing.isNotEmpty()) DiagnosticsCastLogger.event("TV cast controllers disconnected on the TV")
        closing.forEach { goodbye(it, SiloCastCloseReason.TvDisconnected) }
        refreshStandbyState()
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

    /** Closes every controller; the epoch bump also stops handshakes in flight. */
    private fun closeAllControllers(reason: String) {
        val closing = synchronized(this) {
            receiverEpoch += 1
            controllers.values.toList().onEach { removeControllerLocked(it) }
        }
        if (closing.isNotEmpty()) DiagnosticsCastLogger.event("TV cast controllers disconnected")
        closing.forEach { goodbye(it, reason) }
        refreshStandbyState()
    }

    /**
     * Removes [session] from the set and lets go of what it owned. Caller
     * holds the receiver lock. Never touches playback, the identity or the
     * other controllers.
     */
    private fun removeControllerLocked(session: ControllerSession) {
        if (controllers[session.id] !== session) return
        controllers.remove(session.id)
        pendingHandoff?.takeIf { it.controllerId == session.id }?.let {
            pendingHandoff = null
            it.job?.cancel()
        }
        // Reuse from here on needs the resume token.
        issuedIdentity?.takeIf { it.connectionId == session.id }?.let {
            issuedIdentity = it.copy(connectionId = null)
        }
    }

    /** Goodbye + teardown off the monitor — a blocking write to a half-open peer must not stall the receiver. */
    private fun goodbye(session: ControllerSession, reason: String?) {
        val owner = scope
        if (owner != null) {
            owner.launch { session.goodbyeAndClose(reason) }
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
            val ownerScope = scope ?: run {
                runCatching { client.close() }
                return
            }
            // A new connection never evicts a working controller here:
            // admission decides once its TLS handshake is done.
            ownerScope.launch { runControllerSession(client) }
        }
    }

    private suspend fun runControllerSession(client: Socket) {
        val epochAtAccept = synchronized(this) { receiverEpoch }
        val tls = try {
            withContext(Dispatchers.IO) { SiloCastTls.accept(client) }
        } catch (t: Throwable) {
            Log.w(TAG, "SiloCast TLS handshake failed", t)
            DiagnosticsCastLogger.warning("TV cast handshake failed")
            runCatching { client.close() }
            return
        }
        val session = ControllerSession(id = UUID.randomUUID().toString(), socket = client, tls = tls, json = json)
        when (val admitted = admit(session, epochAtAccept)) {
            Admitted.Stale -> {
                // A server switch or stop() happened while this handshake was
                // in flight — this session belongs to the previous epoch.
                session.close()
                return
            }
            Admitted.Refused -> {
                Log.i(TAG, "SiloCast at controller limit; refusing a new controller")
                DiagnosticsCastLogger.warning("TV cast refused a controller at the limit")
                runCatching {
                    withTimeoutOrNull(GOODBYE_TIMEOUT_MS) {
                        session.send(SiloCastMessage.Hello(makeHello()))
                        session.send(
                            SiloCastMessage.Error(
                                SiloCastError(
                                    code = "too_many_controllers",
                                    message = "Too many devices are controlling this TV. Disconnect one and try again.",
                                ),
                            ),
                        )
                        // The peer's features aren't known yet, but every
                        // shipped decoder ignores the `close` payload. Without
                        // the reason, a phone that saw `multi_controller` in the
                        // hello would forget this TV.
                        session.send(SiloCastMessage.Close.withReason(SiloCastCloseReason.TooManyControllers))
                    }
                }
                session.close()
                return
            }
            is Admitted.Registered -> admitted.evicted?.let {
                Log.i(TAG, "SiloCast at controller limit; closing an idle controller")
                goodbye(it, SiloCastCloseReason.TooManyControllers)
            }
        }
        DiagnosticsCastLogger.event("TV cast controller connected")
        try {
            coroutineScope {
                session.job = coroutineContext[Job]

                // TV speaks first with identity only. Playback state is private
                // until the controller's hello has been authorized.
                session.send(SiloCastMessage.Hello(makeHello()))

                // The latest state only: a slow peer never holds up the others,
                // and never receives a stale frame after a newer one.
                // Checked again once the write is admitted: a state read
                // under another profile, or queued before this controller lost
                // access, is dropped instead of sent.
                val stateWriter = launch {
                    for (published in session.stateUpdates) {
                        session.sendIf(SiloCastMessage.State(published.state)) {
                            synchronized(this@TvSiloCastReceiver) {
                                session.isAuthorized && identityManager.activeIdentity?.generationId == published.generation
                            }
                        }
                    }
                }
                val heartbeatJob = launch {
                    while (isActive) {
                        delay(HEARTBEAT_INTERVAL_MS)
                        session.missedHeartbeats += 1
                        if (session.missedHeartbeats > MAX_MISSED_HEARTBEATS) {
                            Log.i(TAG, "SiloCast controller heartbeat timed out")
                            DiagnosticsCastLogger.warning("TV cast controller timed out")
                            // No goodbye: the peer stopped answering.
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
                        session.goodbyeAndClose(SiloCastCloseReason.Timeout)
                    }
                }

                readLoop(tls) { message ->
                    handleMessage(session, message)
                }
                stateWriter.cancel()
                heartbeatJob.cancel()
                authWatchdog.cancel()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "SiloCast controller session ended", t)
        } finally {
            synchronized(this) { removeControllerLocked(session) }
            refreshStandbyState()
            session.close()
            // The phone that lent the profile left (disconnected, dropped, was
            // disconnected from the TV, or was replaced). Hand the profile back
            // unless a title is playing on it or on its way to the player, or
            // another connection from that phone is still here; playback
            // outlives a lost socket. A phone that reconnects within the grace
            // keeps it.
            val lent = identityManager.activeIdentity
                ?.takeIf { it.controllerDeviceId == session.controllerDeviceId }
            if (lent != null && isIdentityIdle() && !isLenderConnected(lent.generationId)) {
                scheduleIdentityEnd(lent.generationId, keepIfLenderReturns = true)
            }
        }
    }

    private sealed interface Admitted {
        data object Stale : Admitted

        data object Refused : Admitted

        data class Registered(val evicted: ControllerSession?) : Admitted
    }

    /** Adds [session] to the set if it fits (see [TvSiloCastReceiverPolicy.admission]). */
    @Synchronized
    private fun admit(session: ControllerSession, epochAtAccept: Long): Admitted {
        if (receiverEpoch != epochAtAccept) return Admitted.Stale
        val existing = controllers.values.map { it.summary(isHandingOff = pendingHandoff?.controllerId == it.id) }
        val evicted = when (val admission = TvSiloCastReceiverPolicy.admission(existing)) {
            TvSiloCastReceiverPolicy.Admission.Admit -> null
            is TvSiloCastReceiverPolicy.Admission.AdmitEvicting ->
                controllers[admission.id]?.also { removeControllerLocked(it) }
            TvSiloCastReceiverPolicy.Admission.Refuse -> return Admitted.Refused
        }
        controllerSequence += 1
        session.sequence = controllerSequence
        controllers[session.id] = session
        return Admitted.Registered(evicted)
    }

    /** @return false to end the session's read loop. */
    private suspend fun handleMessage(session: ControllerSession, message: SiloCastMessage): Boolean {
        when (message) {
            is SiloCastMessage.Hello -> return handleHello(session, message.hello)
            is SiloCastMessage.HandoffOffer -> handleOffer(session, message.handoffOffer)
            is SiloCastMessage.HandoffCancel -> {
                val cancel = message.handoffCancel
                val owned = synchronized(this) {
                    pendingHandoff?.takeIf { it.controllerId == session.id && it.requestId == cancel.requestId }
                        ?.also { pendingHandoff = null }
                }
                owned?.job?.cancel()
                session.readyGeneration = null
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
            is SiloCastMessage.Launch -> handleLaunch(session, message.launch)
            is SiloCastMessage.Control -> {
                if (!requireAuthorized(session)) return true
                if (!session.commandBudget.allow(nowMs())) {
                    noteRateLimited(session)
                    return true
                }
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
                // Everyone sees the change, not only the controller that made it.
                broadcastState()
            }
            is SiloCastMessage.Presence -> {
                if (session.didReceiveHello) {
                    session.passive = message.presence.passive
                    refreshStandbyState()
                }
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

    private suspend fun handleHello(session: ControllerSession, hello: SiloCastHello): Boolean {
        if (session.didReceiveHello) return true
        // Read first, so even a refusal below can carry a close reason.
        session.features = hello.features.orEmpty().toSet()
        val negotiated = SiloCastProtocol.negotiatedVersion(hello.supportedVersions)
        if (hello.role != SiloCastPeerRole.Phone || negotiated != SiloCastProtocol.version) {
            session.send(
                SiloCastMessage.Error(
                    SiloCastError(
                        code = "version_unsupported",
                        message = "Update Silo on both devices to continue.",
                    ),
                ),
            )
            synchronized(this) { removeControllerLocked(session) }
            session.goodbyeAndClose(SiloCastCloseReason.Unauthorized)
            return false
        }
        session.didReceiveHello = true
        session.negotiatedVersion = negotiated
        session.controllerDeviceId = hello.deviceId
        session.controllerDeviceName = hello.deviceName
        session.controllerServerId = hello.serverId
        session.controllerServerIdentity = hello.serverIdentity?.trim()?.takeIf { it.isNotEmpty() }
        session.passive = hello.passive == true
        session.clientKind = hello.clientKind

        // The device's older sockets are ones it has abandoned (a Wi-Fi change,
        // a return from background). A v2 cross-server controller is kept to
        // complete its handoff, so this hello always stays.
        val replaced = synchronized(this) {
            TvSiloCastReceiverPolicy.connectionsReplaced(
                deviceId = hello.deviceId,
                connection = session.id,
                existing = controllers.values.map { it.id to it.controllerDeviceId },
            ).mapNotNull { id -> controllers[id]?.also { removeControllerLocked(it) } }
        }
        replaced.forEach { goodbye(it, SiloCastCloseReason.Replaced) }

        session.isAuthorized = matchesEffectiveServer(session.controllerServerId, session.controllerServerIdentity)
        DiagnosticsCastLogger.event(
            if (session.isAuthorized) "TV cast controller authorized" else "TV cast handoff required",
        )
        refreshStandbyState()
        if (session.isAuthorized) session.offerState(snapshotState())
        return true
    }

    private suspend fun handleOffer(session: ControllerSession, offer: SiloCastHandoffOffer) {
        // A handoff swaps in the phone's profile, and any identity change
        // leaves the Watch Party this TV is in.
        if (inWatchParty()) {
            sendHandoffCancel(
                session,
                offer.requestId,
                SiloCastHandoffCancelReason.WatchPartyActive,
                "Leave the Watch Party on the TV first.",
            )
            return
        }
        val controllerId = session.controllerDeviceId
        if (session.negotiatedVersion != SiloCastProtocol.version || controllerId == null) {
            sendHandoffCancel(
                session,
                offer.requestId,
                SiloCastHandoffCancelReason.VersionUnsupported,
                "Update Silo on both devices to continue.",
            )
            return
        }
        // A launch on its way to the player would land on whatever identity
        // this handoff installs. The phone tries again once the player opens,
        // or the pending launch expires.
        if (synchronized(this) { pendingLaunch != null }) {
            sendHandoffCancel(session, offer.requestId, SiloCastHandoffCancelReason.LaunchPending, LAUNCH_PENDING_MESSAGE)
            return
        }
        // A handoff stops what is on screen before the swap, so consent is
        // asked here, not at launch, and checked again before the stop.
        titleNeedingConsent(session, offer)?.let { nowPlaying ->
            sendReplaceConfirmationRequired(session, offer.requestId, nowPlaying)
            return
        }

        val pending = PendingHandoff(controllerId = session.id, requestId = offer.requestId)
        val superseded = synchronized(this) {
            pendingHandoff.also { pendingHandoff = pending }
        }
        superseded?.let { previous ->
            previous.job?.cancel()
            synchronized(this) { controllers[previous.controllerId] }?.takeIf { it !== session }?.let { other ->
                clearPreparing(other)
                // Off this read loop: a stalled peer must not hold up the new offer.
                scope?.launch {
                    sendHandoffCancel(
                        other,
                        previous.requestId,
                        SiloCastHandoffCancelReason.Superseded,
                        "${session.controllerDeviceName ?: "Another device"} started playing on this TV.",
                    )
                }
            }
        }

        val allowReuse = synchronized(this) {
            val active = identityManager.activeIdentity
            val issued = issuedIdentity?.takeIf { active != null && it.generation == active.generationId }
            TvSiloCastReceiverPolicy.mayReuseIdentity(
                offerMatchesIdentity = identityManager.matches(offer, controllerId),
                identityConnection = issued?.connectionId,
                connection = session.id,
                issuedResumeToken = issued?.resumeToken,
                offeredResumeToken = offer.resumeToken,
            )
        }
        // A reused identity must survive a pending cleanup. One this offer
        // would replace stays until approval, so its cleanup still runs if the
        // handoff fails; after a replacement it finds a newer generation and
        // does nothing.
        if (allowReuse) {
            synchronized(this) {
                identityEndJob?.cancel()
                identityEndJob = null
            }
        }
        session.readyGeneration = null
        session.ownIdentityProfileId = null
        val run = receiverRun
        pending.job = scope?.launch {
            // The generation this attempt's completeHandoff installed, if it got that far.
            var grantedGeneration: String? = null
            try {
                // The hello may have been judged against a phone's identity that
                // is gone now; with none installed, the TV's own server decides.
                if (identityManager.activeIdentity == null) {
                    session.isAuthorized = matchesEffectiveServer(session.controllerServerId, session.controllerServerIdentity)
                    refreshStandbyState()
                }
                ownIdentityReady(session, offer)?.let { ready ->
                    clearPreparing(session)
                    if (!finishPendingHandoff(pending)) return@launch
                    session.ownIdentityProfileId = offer.profileId
                    DiagnosticsCastLogger.event("TV cast handoff skipped on own profile")
                    session.send(SiloCastMessage.HandoffReady(ready))
                    session.offerState(snapshotState())
                    return@launch
                }
                var fullHandoff = false
                var preparedGeneration: String? = null
                val ready = identityManager.prepare(
                    offer = offer,
                    controllerDeviceId = controllerId,
                    controllerDeviceName = session.controllerDeviceName,
                    receiverRun = run,
                    allowReuse = allowReuse,
                    onFullHandoff = {
                        fullHandoff = true
                        showPreparing(session, offer.title)
                    },
                    beforeActivation = {
                        // Another controller may have started a title while the
                        // server approved this one.
                        titleNeedingConsent(session, offer)?.let { throw ReplaceConfirmationRequired(it) }
                        stopPlayerBeforeIdentitySwap()
                    },
                    onPrepared = { preparedGeneration = it },
                ) { challenge ->
                    if (!isCurrentHandoff(pending)) throw CancellationException("The handoff was superseded.")
                    session.send(SiloCastMessage.HandoffChallenge(challenge))
                }
                // A reuse is instant; drop a cancelled offer's title.
                if (!fullHandoff) clearPreparing(session)
                if (!finishPendingHandoff(pending)) {
                    // The controller left or was superseded after approval. A
                    // fresh identity nobody can launch under is handed back; a
                    // reused one may still be playing.
                    if (!ready.reused) {
                        preparedGeneration?.let { identityManager.end(it) }
                        refreshAdvertisement()
                        reconcileAuthorization()
                    }
                    return@launch
                }
                completeHandoff(session, checkNotNull(preparedGeneration), ready, fullHandoff) { grantedGeneration = it }
            } catch (e: CancellationException) {
                throw e
            } catch (refusal: ReplaceConfirmationRequired) {
                clearPreparing(session)
                finishPendingHandoff(pending)
                if (synchronized(this@TvSiloCastReceiver) { controllers[session.id] === session }) {
                    runCatching { sendReplaceConfirmationRequired(session, offer.requestId, refusal.nowPlaying) }
                }
            } catch (t: Throwable) {
                clearPreparing(session)
                // Readiness was granted but `handoff_ready` never reached the
                // phone: hand back the generation this attempt granted (a
                // newer handoff from the same phone may own the session's
                // readiness by now), and re-check the controllers it judged.
                val granted = grantedGeneration
                if (granted != null && activePlayer == null) {
                    synchronized(this@TvSiloCastReceiver) {
                        if (session.readyGeneration == granted) session.readyGeneration = null
                    }
                    runRecovering("handoff rollback") {
                        identityManager.end(granted)
                        refreshAdvertisement()
                        reconcileAuthorization()
                    }
                }
                finishPendingHandoff(pending)
                if (synchronized(this@TvSiloCastReceiver) { controllers[session.id] === session }) {
                    sendHandoffCancel(
                        session,
                        offer.requestId,
                        SiloCastHandoffCancelReason.HandoffFailed,
                        t.message ?: "Remote playback handoff failed.",
                    )
                }
            }
        }
    }

    /**
     * Readiness is this controller's alone: an earlier controller's launch
     * must never play under this profile. Issues the resume token and tells
     * the others the TV now plays as someone else.
     */
    /**
     * Makes [session] the only controller ready for [generation] and issues
     * its resume token. Caller holds the receiver monitor.
     */
    private fun grantReadiness(session: ControllerSession, generation: String): ResumeTokenGrant {
        controllers.values.filter { it !== session }.forEach {
            it.readyGeneration = null
            it.ownIdentityProfileId = null
        }
        session.readyGeneration = generation
        session.isAuthorized = true
        val token = if (session.supports(SiloCastFeature.ResumeToken)) {
            issuedIdentity?.takeIf { it.generation == generation }?.resumeToken
                ?: TvSiloCastReceiverPolicy.makeResumeToken()
        } else {
            null
        }
        issuedIdentity = IssuedIdentity(generation = generation, connectionId = session.id, resumeToken = token)
        return ResumeTokenGrant(token)
    }

    /** A granted readiness; [token] is null for a controller that can't use one. */
    private data class ResumeTokenGrant(val token: String?)

    private suspend fun completeHandoff(
        session: ControllerSession,
        generation: String,
        ready: SiloCastHandoffReady,
        fullHandoff: Boolean,
        onGranted: (generation: String) -> Unit,
    ) {
        // Readiness is granted only while the generation this offer prepared
        // is still active, under the identity manager's lock: if it was handed
        // back (idle end, expiry, server switch) or replaced in the moment
        // after prepare() returned, the handoff fails and the phone hears it.
        val resumeToken = identityManager.whileActive(generation) {
            synchronized(this@TvSiloCastReceiver) { grantReadiness(session, generation) }
        } ?: error("The TV switched to another profile. Try again.")
        onGranted(generation)
        DiagnosticsCastLogger.event("TV cast handoff authorized")
        refreshAdvertisement()
        // A cross-server identity changes the effective server under the
        // other controllers.
        reconcileAuthorization()
        session.send(SiloCastMessage.HandoffReady(ready.copy(resumeToken = resumeToken.token)))
        session.offerState(snapshotState())
        // Replaces the old ready-without-launch timeout: with nothing playing,
        // the profile goes back after the idle limit. A player cancels this
        // when it opens.
        if (activePlayer == null) scheduleIdentityEnd(generation, IDENTITY_IDLE_MS)
        if (fullHandoff) {
            session.preparingClearJob?.cancel()
            session.preparingClearJob = scope?.launch {
                delay(PREPARING_AFTER_READY_MS)
                clearPreparing(session)
            }
        }
    }

    private fun isCurrentHandoff(pending: PendingHandoff): Boolean =
        synchronized(this) { pendingHandoff === pending && controllers.containsKey(pending.controllerId) }

    /** Clears [pending] if it is still the in-flight handoff; false when it was superseded or its controller left. */
    private fun finishPendingHandoff(pending: PendingHandoff): Boolean = synchronized(this) {
        val current = pendingHandoff === pending && controllers.containsKey(pending.controllerId)
        if (pendingHandoff === pending) pendingHandoff = null
        current
    }

    private suspend fun handleLaunch(session: ControllerSession, launch: SiloCastLaunchRequest) {
        if (!requireAuthorized(session)) return
        // A Watch Party player is never silently replaced by a cast.
        activePlayer?.adapter?.launchRefusal?.invoke()?.let { refusal ->
            session.send(SiloCastMessage.Error(SiloCastError(code = "watch_party_active", message = refusal)))
            return
        }
        // In a party lobby no party player is registered yet, but a solo
        // launch would still take the shared player from the room.
        if (inWatchParty()) {
            session.send(
                SiloCastMessage.Error(
                    SiloCastError(code = "watch_party_active", message = "Leave the Watch Party on the TV first."),
                ),
            )
            return
        }
        // A sign-in, profile or borrowed-identity change after this makes the
        // launch stale (see confirmLaunch).
        val identity = settledLaunchIdentity(session)?.takeIf { it.onOwnIdentity || it.borrowed != null }
        if (identity == null) {
            session.send(
                SiloCastMessage.Error(
                    SiloCastError(code = "handoff_required", message = "Prepare the phone profile before playing."),
                ),
            )
            return
        }
        val onOwnIdentity = identity.onOwnIdentity
        val borrowed = identity.borrowed
        // The launch names the phone's registry key; the identity learned from
        // its hello lets a same-deployment phone on another address launch.
        val launchIdentity = session.controllerServerIdentity
            ?.takeIf { AndroidServerRegistry.serverIdsMatch(launch.serverId, session.controllerServerId) }
        if (!AndroidServerRegistry.serversMatch(launch.serverId, launchIdentity, identity.serverId, identity.serverIdentity)) {
            session.send(
                SiloCastMessage.Error(
                    SiloCastError(code = "server_mismatch", message = "This TV is connected to a different Silo server."),
                ),
            )
            return
        }
        val generation = if (onOwnIdentity) null else borrowed?.generationId
        val launchOwner = UUID.randomUUID().toString()
        val admitted = AdmittedLaunch(launchOwner, generation, identity.revision)
        val refusal = synchronized(this) {
            when {
                // The controller left while this was in flight.
                controllers[session.id] !== session -> return
                pendingLaunch != null -> SiloCastError(code = "launch_pending", message = LAUNCH_PENDING_MESSAGE)
                // The identity changed since the checks above, or its hand-back
                // has committed.
                !isAdmittedIdentityCurrentLocked(admitted) -> SiloCastError(
                    code = "handoff_required",
                    message = "Prepare the phone profile before playing.",
                )
                else -> {
                    pendingLaunch = admitted
                    null
                }
            }
        }
        if (refusal != null) {
            if (refusal.code == "handoff_required") session.readyGeneration = null
            session.send(SiloCastMessage.Error(refusal))
            return
        }
        if (!launchRequestChannel.trySend(Launch(launch, launchOwner)).isSuccess) {
            clearPendingLaunch(launchOwner)
            session.send(
                SiloCastMessage.Error(
                    SiloCastError(code = "launch_failed", message = "The TV could not open remote playback."),
                ),
            )
            return
        }
        // A launch whose player never opens must not hold off every later
        // handoff.
        scope?.launch {
            delay(PENDING_LAUNCH_TIMEOUT_MS)
            clearPendingLaunch(launchOwner, dropQueued = true)
        }
        session.readyGeneration = null
        session.ownIdentityProfileId = null
        // The TV is opening the player now; the preparing view is done.
        clearPreparing(session, refresh = false)
        _standbyState.value = null
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

    /** The handoff refused just before it would stop what plays; see [titleNeedingConsent]. */
    private class ReplaceConfirmationRequired(val nowPlaying: SiloCastNowPlayingSummary) : Exception()

    /**
     * What [offer] would replace without its user's consent, or null when the
     * handoff may go ahead (see [TvSiloCastReceiverPolicy.needsReplaceConfirmation]).
     */
    private suspend fun titleNeedingConsent(session: ControllerSession, offer: SiloCastHandoffOffer): SiloCastNowPlayingSummary? {
        val titleOnScreen = synchronized(this) { activePlayer != null || pendingLaunch != null }
        if (!titleOnScreen || !session.supports(SiloCastFeature.ReplaceConfirmation)) return null
        val nowPlaying = nowPlayingSummary()
        val needsConsent = TvSiloCastReceiverPolicy.needsReplaceConfirmation(
            titleOnScreen = true,
            playingUnderOfferedProfile = isPlayingUnderProfile(offer),
            replaceConfirmed = TvSiloCastReceiverPolicy.confirmationCovers(
                onScreenContentId = nowPlaying.contentId,
                replaceConfirmed = offer.replaceConfirmed == true,
                replacingContentId = offer.replacingContentId,
            ),
            controllerCanConfirm = true,
        )
        return nowPlaying.takeIf { needsConsent }
    }

    /**
     * Whether what is on screen plays under the profile [offer] names. The TV
     * plays under the borrowed identity when one is active, else under its own
     * signed-in profile.
     */
    private suspend fun isPlayingUnderProfile(offer: SiloCastHandoffOffer): Boolean {
        identityManager.activeIdentity?.let { identity ->
            return identity.profileId == offer.profileId &&
                AndroidServerRegistry.serversMatch(identity.serverId, identity.serverIdentity, offer.serverId, offer.serverIdentity)
        }
        val server = serverRegistry.activeEntry.value ?: return false
        return tokenManager.getProfileId() == offer.profileId &&
            AndroidServerRegistry.serversMatch(server.id, server.verifiedServerId, offer.serverId, offer.serverIdentity)
    }

    private suspend fun nowPlayingSummary(): SiloCastNowPlayingSummary {
        val state = withContext(Dispatchers.Main.immediate) { activePlayer?.stateProvider?.invoke() }
        val identity = identityManager.activeIdentity
        val startedByPhone = identity != null && activePlayer?.identityGeneration == identity.generationId
        return SiloCastNowPlayingSummary(
            contentId = state?.contentId?.takeIf { it.isNotEmpty() },
            title = state?.title?.takeIf { it.isNotBlank() } ?: "a title",
            subtitle = state?.subtitle?.takeIf { it.isNotBlank() },
            // Under a borrowed identity only its own name is right; a phone
            // that sent none leaves it null ("Someone"), never the TV's profile.
            profileName = if (identity != null) identity.profileName else ownProfileNameProvider(),
            controllerName = if (startedByPhone) identity?.controllerDeviceName else null,
        )
    }

    private suspend fun sendReplaceConfirmationRequired(
        session: ControllerSession,
        requestId: String,
        nowPlaying: SiloCastNowPlayingSummary,
    ) {
        session.send(
            SiloCastMessage.HandoffCancel(
                SiloCastHandoffCancel(
                    requestId = requestId,
                    reason = SiloCastHandoffCancelReason.ReplaceConfirmationRequired,
                    message = "${nowPlaying.profileName ?: "Someone"} is watching ${nowPlaying.title} on this TV.",
                    nowPlaying = nowPlaying,
                ),
            ),
        )
    }

    private suspend fun sendHandoffCancel(session: ControllerSession, requestId: String, reason: String, message: String) {
        runCatching {
            session.send(SiloCastMessage.HandoffCancel(SiloCastHandoffCancel(requestId = requestId, reason = reason, message = message)))
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

    /**
     * Tells a controller over its command budget once a second at most;
     * replying to every dropped command would only add to the flood.
     */
    private suspend fun noteRateLimited(session: ControllerSession) {
        val now = nowMs()
        val last = session.rateLimitNoticeAtMs
        if (last != null && now - last < RATE_LIMIT_NOTICE_MS) return
        session.rateLimitNoticeAtMs = now
        Log.i(TAG, "SiloCast rate-limiting a controller")
        session.send(
            SiloCastMessage.Error(SiloCastError(code = "rate_limited", message = "Too many commands. Slow down and try again.")),
        )
    }

    /**
     * The server refused to refresh the borrowed profile: everyone watching
     * loses the title, not only the phone that lent it. The overlay is already
     * gone, so the identity ends without a server logout.
     */
    private suspend fun onTemporarySessionExpired(generationId: String) {
        if (identityManager.activeIdentity?.generationId != generationId) return
        DiagnosticsCastLogger.warning("TV cast borrowed profile session expired")
        val error = SiloCastMessage.Error(
            SiloCastError(code = "temporary_session_expired", message = "The phone profile session expired."),
        )
        // Notices are sent after teardown and off this coroutine: a controller
        // that stopped reading must not hold up the stop or the hand-back.
        // They go out only if this expiry ended its own profile, and each is
        // dropped if a new handoff completed before its write.
        val notified = authorizedControllers()
        activePlayer?.takeIf { it.identityGeneration == generationId }?.let { player ->
            withContext(Dispatchers.Main.immediate) {
                player.adapter.handle(SiloCastControlCommand(name = SiloCastControlCommand.Stop))
            }
        }
        // A handoff that replaced the profile while playback stopped owns the
        // TV now; nobody hears about the old profile.
        if (!identityManager.end(generationId)) return
        refreshAdvertisement()
        reconcileAuthorization()
        notified.forEach { session ->
            scope?.launch {
                runCatching { session.sendIf(error) { identityManager.activeIdentity == null } }
            }
        }
    }

    /**
     * Re-checks every controller against the effective server after the
     * borrowed identity changed (handoff, end, expiry). Readiness survives
     * only for the identity still active.
     */
    private suspend fun reconcileAuthorization() {
        val activeGeneration = identityManager.activeIdentity?.generationId
        val changes = synchronized(this) {
            controllers.values.filter { it.didReceiveHello }.mapNotNull { controller ->
                val wasAuthorized = controller.isAuthorized
                val decision = TvSiloCastReceiverPolicy.reauthorize(
                    wasAuthorized = wasAuthorized,
                    matchesEffectiveServer = matchesEffectiveServer(controller.controllerServerId, controller.controllerServerIdentity),
                    readyGeneration = controller.readyGeneration,
                    activeGeneration = activeGeneration,
                )
                if (decision.clearsReadiness) controller.readyGeneration = null
                controller.isAuthorized = decision.isAuthorized
                when {
                    decision.isAuthorized && !wasAuthorized -> controller to true
                    decision.notifyServerMismatch -> controller to false
                    else -> null
                }
            }
        }
        refreshStandbyState()
        if (changes.isEmpty()) return
        val state = snapshotState()
        changes.forEach { (controller, gained) ->
            if (gained) {
                controller.offerState(state)
            } else {
                runCatching {
                    controller.send(
                        SiloCastMessage.Error(
                            SiloCastError(
                                code = "server_mismatch",
                                message = "This TV is now playing from a different Silo server.",
                            ),
                        ),
                    )
                }
            }
        }
    }

    /**
     * Whether a controller's server matches the TV's effective one: the
     * borrowed identity's while one is active, else the TV's own.
     */
    private fun matchesEffectiveServer(serverId: String?, serverIdentity: String?): Boolean {
        val borrowed = identityManager.activeIdentity
        if (borrowed != null) {
            return AndroidServerRegistry.serversMatch(serverId, serverIdentity, borrowed.serverId, borrowed.serverIdentity)
        }
        val own = serverRegistry.activeEntry.value
        return AndroidServerRegistry.serversMatch(
            serverId,
            serverIdentity,
            own?.id ?: serverRegistry.activeServerId.value,
            own?.verifiedServerId,
        )
    }

    private fun authorizedControllers(): List<ControllerSession> =
        synchronized(this) { controllers.values.filter { it.isAuthorized } }

    /**
     * Built once and handed to every authorized controller's latest-state
     * slot. The recipients are chosen after the state is read, so a handoff
     * during the read can't send the new profile's state to a controller it
     * de-authorized.
     */
    private suspend fun broadcastState() {
        if (authorizedControllers().isEmpty()) {
            // Nobody to send artwork to, so stop keeping its URL signed.
            nowPlayingMetadata.track(null)
            return
        }
        val state = snapshotState()
        authorizedControllers().forEach { it.offerState(state) }
    }

    /** The playback state with the profile generation it was read under, checked again at the write. */
    private suspend fun snapshotState(): PublishedState {
        val generation = identityManager.activeIdentity?.generationId
        return PublishedState(currentState(), generation)
    }

    private data class PublishedState(val state: SiloCastPlaybackState, val generation: String?)

    /**
     * Ends [generationId] after [delayMs]. With [keepIfLenderReturns] (the
     * lending phone left), a phone back by then keeps it under the idle limit
     * instead: a dropped link reconnects within seconds.
     */
    private fun scheduleIdentityEnd(
        generationId: String,
        delayMs: Long = IDENTITY_END_GRACE_MS,
        keepIfLenderReturns: Boolean = false,
    ): Unit = synchronized(this) {
        identityEndJob?.cancel()
        val owner = scope ?: return@synchronized
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
                                if (pendingLaunch?.generation == generationId) pendingLaunch = null
                            }
                            refreshAdvertisement()
                            refreshStandbyState()
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
            // controllers' authorization; judging them here could drop a phone
            // that is still handing off.
            if (!isActive) return@launch
            // Cross-server controllers stay connected, unauthorized, and can
            // hand off again; same-server ones keep controlling the TV.
            runRecovering("authorization after an identity end") { reconcileAuthorization() }
        }
    }

    /** No title is playing on, or on its way to, a phone's identity. */
    private fun isIdentityIdle(): Boolean = activePlayer == null && pendingLaunch == null

    /**
     * Whether launch [id] may still open its player: it is the pending launch,
     * or the launch whose player registered last, and the identity it was
     * admitted on is unchanged. Navigation checks this before it opens the
     * player and before it creates the player screen; registration checks it
     * again. A stale launch gives up its place, so the next launch is not
     * refused while it waits out its expiry.
     */
    @Synchronized
    fun confirmLaunch(id: String): Boolean = confirmLaunchLocked(id)

    /** Caller holds the receiver lock. */
    private fun confirmLaunchLocked(id: String): Boolean {
        pendingLaunch?.takeIf { it.id == id }?.let { pending ->
            if (isAdmittedIdentityCurrentLocked(pending)) return true
            pendingLaunch = null
            refreshStandbyState()
            return false
        }
        registeredLaunch?.takeIf { it.id == id }?.let { registered ->
            if (isAdmittedIdentityCurrentLocked(registered)) return true
            registeredLaunch = null
        }
        return false
    }

    /**
     * Reads the identity [session]'s launch would play on. The barrier holds
     * identity changes off while this runs, so the checks agree with the
     * revision read alongside them: a change already under way is waited out,
     * never half-observed. Null if changes kept landing meanwhile.
     */
    private suspend fun settledLaunchIdentity(session: ControllerSession): LaunchIdentity? {
        repeat(SETTLE_ATTEMPTS) {
            val observed = identityTransitions.generation.value
            identityTransitions.withCurrentGeneration(observed) {
                val ownProfileId = session.ownIdentityProfileId
                // Launch-ready on the TV's own identity: still signed in as
                // that profile with no phone's identity installed since.
                val onOwnIdentity = ownProfileId != null &&
                    identityManager.activeIdentity == null &&
                    !tokenManager.hasTemporaryScope() &&
                    tokenManager.getProfileId() == ownProfileId
                // activeIdentity is unpublished as soon as an end starts, so a
                // launch never lands on an identity being handed back. Only the
                // controller whose handoff produced it may launch under it.
                val borrowed = identityManager.activeIdentity?.takeIf {
                    TvSiloCastReceiverPolicy.canLaunchBorrowed(session.readyGeneration, it.generationId)
                }
                val own = serverRegistry.activeEntry.value
                LaunchIdentity(
                    revision = synchronized(this@TvSiloCastReceiver) { identityRevision },
                    onOwnIdentity = onOwnIdentity,
                    borrowed = borrowed,
                    serverId = if (onOwnIdentity) serverRegistry.activeServerId.value else borrowed?.serverId,
                    serverIdentity = if (onOwnIdentity) own?.verifiedServerId else borrowed?.serverIdentity,
                )
            }?.let { return it }
        }
        return null
    }

    /**
     * Caller holds the receiver lock. No sign-in, profile, server or borrowed
     * identity change since [launch] was admitted, and a borrowed identity is
     * not being ended.
     */
    private fun isAdmittedIdentityCurrentLocked(launch: AdmittedLaunch): Boolean {
        if (identityRevision != launch.identityRevision) return false
        val generation = launch.generation ?: return true
        return identityManager.activeIdentity?.generationId == generation && endingGenerationId != generation
    }

    /**
     * Clears the pending launch only if [owner] still holds it. With
     * [dropQueued], also drops its request if navigation has not taken it yet,
     * so an expired launch never opens later under another identity. Admission
     * holds every other launch off while one is pending, so a request still
     * queued is this one.
     */
    private fun clearPendingLaunch(owner: String, dropQueued: Boolean = false) {
        synchronized(this) {
            if (pendingLaunch?.id == owner) {
                pendingLaunch = null
                if (dropQueued) launchRequestChannel.tryReceive()
                // A launch whose player never opened: a waiting controller's
                // standby comes back.
                refreshStandbyState()
            }
        }
    }

    /**
     * Whether a connection from the phone that lent [generationId] is here.
     * Keeping the profile alive needs only that; using it needs the issuing
     * connection or its resume token.
     */
    private fun isLenderConnected(generationId: String): Boolean {
        val identity = identityManager.activeIdentity ?: return false
        if (identity.generationId != generationId) return false
        return synchronized(this) {
            controllers.values.any { it.isAuthorized && it.controllerDeviceId == identity.controllerDeviceId }
        }
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

    /**
     * The standby screen shows while nothing is on screen and an authorized,
     * non-passive controller waits; it names the newest and counts the rest.
     * A cross-server phone is not authorized until its handoff finishes, but
     * the TV still shows what it is preparing for that phone.
     */
    @Synchronized
    private fun refreshStandbyState() {
        if (activePlayer != null || pendingLaunch != null) {
            _standbyState.value = null
            return
        }
        val serverName = identityManager.activeIdentity?.serverName ?: serverRegistry.activeEntry.value?.displayName
        val preparing = controllers.values.lastOrNull { it.preparing != null }
        if (preparing != null) {
            _standbyState.value = StandbyState(
                controllerName = preparing.controllerDeviceName,
                serverName = serverName,
                preparing = preparing.preparing,
            )
            return
        }
        val pick = TvSiloCastReceiverPolicy.standbyPick(controllers.values.map { it.summary(isHandingOff = false) })
        _standbyState.value = pick?.let {
            StandbyState(
                controllerName = controllers[it.controllerId]?.controllerDeviceName,
                serverName = serverName,
                otherControllerCount = it.otherControllerCount,
            )
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
            serverIdentity = advertisedServerIdentity(),
        )
    }

    /** Re-advertises only when the presented identity changed: every registration re-registers with NSD. */
    @Synchronized
    private fun refreshAdvertisedIdentity() {
        val advertised = advertiser.currentAdvertisement()?.third?.get("serverIdentity")
        if (advertised != advertisedServerIdentity()) refreshAdvertisement()
    }

    /** The deployment identity the TV presents: the borrowed identity's while one is active, else its own server's. */
    private fun advertisedServerIdentity(): String? {
        val remote = identityManager.activeIdentity
        return if (remote != null) remote.serverIdentity else serverRegistry.activeEntry.value?.verifiedServerId?.trim()?.ifEmpty { null }
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
            serverIdentity = advertisedServerIdentity(),
            features = SiloCastFeature.receiverFeatures,
        )
    }

    private suspend fun currentState(): SiloCastPlaybackState =
        withContext(Dispatchers.Main.immediate) {
            val state = activePlayer?.stateProvider?.invoke()
            // Follows the live content ID, so an autoplayed next episode drops
            // the previous title's poster on its first tick.
            nowPlayingMetadata.track(state?.contentId)
            val metadata = nowPlayingMetadata.metadata(state?.contentId)
            state?.copy(posterUrl = metadata?.posterUrl, overview = metadata?.overview) ?: idleState()
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
        /** Connection id; a device that reconnects gets a new one. */
        val id: String,
        val socket: Socket,
        val tls: SiloCastTlsSession,
        private val json: Json,
    ) {
        val writeMutex = Mutex()

        /** Order of acceptance; lower is older. Set at admission. */
        @Volatile
        var sequence: Long = 0

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
        var controllerServerIdentity: String? = null

        /** [SiloCastFeature] names from the controller's hello. */
        @Volatile
        var features: Set<String> = emptySet()

        /** Watches state only (integration, silent auto-resume); never puts up standby. */
        @Volatile
        var passive: Boolean = false

        @Volatile
        var clientKind: String? = null

        /** The borrowed identity generation this controller's handoff produced; a launch needs it active. */
        @Volatile
        var readyGeneration: String? = null

        /** Launch-ready on the TV's own identity for this profile (no handoff ran). */
        @Volatile
        var ownIdentityProfileId: String? = null

        @Volatile
        var preparing: StandbyState.Preparing? = null

        @Volatile
        var missedHeartbeats: Int = 0

        @Volatile
        var rateLimitNoticeAtMs: Long? = null
        val commandBudget = TvSiloCastReceiverPolicy.CommandBudget()

        /** The latest state for this controller's writer; older frames are dropped. */
        val stateUpdates = Channel<PublishedState>(Channel.CONFLATED)
        var job: Job? = null
        var preparingClearJob: Job? = null

        fun supports(feature: String): Boolean = feature in features

        fun summary(isHandingOff: Boolean) = TvSiloCastReceiverPolicy.ControllerSummary(
            id = id,
            sequence = sequence,
            didReceiveHello = didReceiveHello,
            passive = passive,
            isAuthorized = isAuthorized,
            isHandingOff = isHandingOff,
        )

        fun offerState(state: PublishedState) {
            stateUpdates.trySend(state)
        }

        /** Writes [message] only if [admit] still holds once this connection's write lock is taken. */
        suspend fun sendIf(message: SiloCastMessage, admit: () -> Boolean) {
            val frame = SiloCastFrame.encode(
                json.encodeToString(SiloCastMessage.serializer(), message).encodeToByteArray(),
            )
            writeMutex.withLock {
                if (!admit()) return@withLock
                withContext(Dispatchers.IO) {
                    tls.output.write(frame)
                    tls.output.flush()
                }
            }
        }

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
         * dropped link (Apple auto-reconnects on bare EOF); a peer that lists
         * `close_reason` also learns why. The write goes through the same mutex
         * as every other frame so a mid-flight state push can't interleave
         * with it; the whole thing runs suspending so callers holding the
         * receiver monitor don't block on a dead peer's TCP buffers.
         */
        suspend fun goodbyeAndClose(reason: String? = null) {
            runCatching {
                kotlinx.coroutines.withTimeout(GOODBYE_TIMEOUT_MS) {
                    send(SiloCastMessage.Close.withReason(reason?.takeIf { supports(SiloCastFeature.CloseReason) }))
                }
            }
            close()
        }

        fun close() {
            preparingClearJob?.cancel()
            stateUpdates.close()
            tls.close()
            runCatching { socket.close() }
            job?.cancel()
        }
    }

    /** The one in-flight handoff and the controller that offered it. */
    private class PendingHandoff(val controllerId: String, val requestId: String) {
        @Volatile
        var job: Job? = null
    }

    /** Who may reuse borrowed identity [generation] without a new approval. */
    private data class IssuedIdentity(val generation: String, val connectionId: String?, val resumeToken: String?)

    /** A phone's launch on its way to the player, with the [id] it was admitted under. */
    data class Launch(val request: SiloCastLaunchRequest, val id: String)

    /**
     * A launch as admitted: the phone's borrowed identity [generation] (null on
     * the TV's own identity) and the receiver's [identityRevision] then.
     */
    private data class AdmittedLaunch(val id: String, val generation: String?, val identityRevision: Long)

    /** What a launch would play on: the TV's own profile or a [borrowed] one, as of [revision]. */
    private data class LaunchIdentity(
        val revision: Long,
        val onOwnIdentity: Boolean,
        val borrowed: RemotePlaybackIdentityManager.ActiveIdentity?,
        val serverId: String?,
        val serverIdentity: String?,
    )

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
        const val RATE_LIMIT_NOTICE_MS = 1_000L
        const val IDENTITY_END_GRACE_MS = 2_000L
        const val PLAYBACK_TEARDOWN_TIMEOUT_MS = 15_000L
        const val PENDING_LAUNCH_TIMEOUT_MS = 30_000L
        // Identity changes rarely land back to back; past this, refuse the launch.
        const val SETTLE_ATTEMPTS = 3
        const val LAUNCH_PENDING_MESSAGE = "The TV is still starting the last title. Try again in a moment."
        const val IDENTITY_IDLE_MS = 10 * 60_000L
        const val PREPARING_AFTER_READY_MS = 20_000L
        const val OWN_IDENTITY_SESSION_MS = 24 * 60 * 60 * 1_000L
        const val MAX_PREPARING_TITLE_LENGTH = 120
    }
}
