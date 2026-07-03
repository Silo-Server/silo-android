package org.siloserver.silo.tv.control

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.siloserver.silo.common.control.TlsPskControlTransport
import org.siloserver.silo.common.control.TvControlAdvertiser
import org.siloserver.silo.common.pairing.PairingDeviceId
import org.siloserver.silo.common.player.ActivePlayerHolder
import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.control.SiloControlCommand
import org.siloserver.silo.control.SiloControlCommandName
import org.siloserver.silo.control.SiloControlErrorMessage
import org.siloserver.silo.control.SiloControlHello
import org.siloserver.silo.control.SiloControlMessage
import org.siloserver.silo.control.SiloControlPeerRole
import org.siloserver.silo.control.SiloControlPlaybackState
import org.siloserver.silo.control.SiloControlProtocol
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.tv.ui.screens.player.TvPlayerViewModel
import org.siloserver.silo.tv.ui.screens.player.VIDEO_QUALITY_AUTO_ID

/**
 * TV-side SiloControl receiver: advertises `_silocast._tcp` while a server is
 * active, accepts one TLS-PSK controller connection at a time (newest wins),
 * executes remote-control commands against the registered [TvPlayerViewModel],
 * and pushes playback state every 500 ms. Ports silo-apple's
 * `TVControlReceiver` semantics.
 *
 * Receiver only (SiloControl part 1 of 3): `launch` (play-on) is answered with
 * an `unsupported` error and never navigates.
 *
 * All session state is confined to [scope]'s main dispatcher; only the actual
 * socket writes hop to IO through each session's ordered outbound channel.
 */
class TvControlReceiver(
    private val context: Context,
    private val serverRegistry: ServerRegistry,
    private val activePlayerHolder: ActivePlayerHolder,
    private val playerSettingsStore: PlayerSettingsStore,
    private val advertiser: TvControlAdvertiser,
) {
    private companion object {
        private const val TAG = "TvControlReceiver"
        private const val HEARTBEAT_INTERVAL_MS = 3_000L
        private const val MAX_MISSED_HEARTBEATS = 3 // ~9-12s of silence ⇒ dead
        private const val AUTH_GRACE_PERIOD_MS = 5_000L
        private const val STATE_PUSH_INTERVAL_MS = 500L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var serverObserveJob: Job? = null
    private var advertisedServerId: String? = null

    private var activeSession: ControlSession? = null
    private var readJob: Job? = null
    private var stateJob: Job? = null
    private var heartbeatJob: Job? = null
    private var authWatchdogJob: Job? = null
    private var missedHeartbeats = 0
    private var isAuthorized = false

    private var playerViewModel: TvPlayerViewModel? = null
    private var playerContentId: String? = null

    // Idle-state settings mirrors (the registered player's VM exposes its own).
    private val playbackSpeedSetting = playerSettingsStore.playbackSpeedFlow
        .stateIn(scope, SharingStarted.Eagerly, 1.0)
    private val hdrEnabledSetting = playerSettingsStore.hdrEnabledFlow
        .stateIn(scope, SharingStarted.Eagerly, true)
    private val videoGravitySetting = playerSettingsStore.videoGravityFlow
        .stateIn(scope, SharingStarted.Eagerly, "fit")

    /**
     * Begin advertising whenever a server is active. Observes
     * [ServerRegistry.activeEntry]: restarts the advertiser on server switch
     * (closing any connected controller — it authorized against the old
     * server) and stops it entirely when no server is active. Idempotent.
     */
    fun start() {
        if (serverObserveJob != null) return
        serverObserveJob = scope.launch {
            serverRegistry.activeEntry.collect { entry ->
                when {
                    entry == null -> {
                        advertisedServerId = null
                        advertiser.stop()
                        closeActiveSession(sendClose = true)
                    }
                    entry.id != advertisedServerId -> {
                        closeActiveSession(sendClose = true)
                        advertisedServerId = entry.id
                        advertiser.start(entry.id, entry.displayName, ::onSessionAccepted)
                        Log.i(TAG, "advertising for server ${entry.id}")
                    }
                }
            }
        }
    }

    /** Stop advertising and tear down any connected controller. */
    fun stop() {
        serverObserveJob?.cancel()
        serverObserveJob = null
        advertisedServerId = null
        advertiser.stop()
        closeActiveSession(sendClose = false)
    }

    /**
     * Called by the player screen when playback mounts. Starts the 500 ms
     * state-push loop and flips the advertised `playing` TXT flag so phones
     * can target TVs that are actually playing.
     */
    fun registerPlayer(viewModel: TvPlayerViewModel, contentId: String) {
        playerViewModel = viewModel
        playerContentId = contentId
        startStateUpdates()
        sendState()
        advertiser.setPlaying(true)
    }

    fun unregisterPlayer(viewModel: TvPlayerViewModel) {
        if (playerViewModel !== viewModel) return
        playerViewModel = null
        playerContentId = null
        stateJob?.cancel()
        stateJob = null
        sendState()
        advertiser.setPlaying(false)
    }

    // ---- Session lifecycle -------------------------------------------------------

    private fun onSessionAccepted(transport: TlsPskControlTransport) {
        // Called from the advertiser's IO accept scope — hop to the main scope.
        scope.launch { accept(transport) }
    }

    private fun accept(transport: TlsPskControlTransport) {
        if (activeSession != null) {
            // Newest controller wins (matches AirPlay/Cast); frees the old slot.
            closeActiveSession(sendClose = true)
        }
        val session = ControlSession(transport, scope)
        activeSession = session
        isAuthorized = false
        session.enqueue(makeHello())
        sendState()
        startReadLoop(session)
        if (playerViewModel != null) startStateUpdates()
        startHeartbeat(session)
        startAuthWatchdog(session)
    }

    private fun startReadLoop(session: ControlSession) {
        readJob?.cancel()
        readJob = scope.launch {
            try {
                session.transport.incoming.collect { handle(it, session) }
                handleConnectionClosed(session)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                sendError("connection_failed", t.message ?: "Connection failed.")
                handleConnectionClosed(session)
            }
        }
    }

    private fun handle(message: SiloControlMessage, session: ControlSession) {
        if (activeSession !== session) return
        // NOTE: liveness is reset only on Pong (below), not on every inbound
        // message — a Pong is the controller's reply to our ping, so it's the
        // only message that proves the controller can still RECEIVE from us.
        // Resetting on any inbound would let a half-open connection keep the
        // session pinned open forever. Mirrors the tvOS receiver.
        when (message) {
            is SiloControlMessage.Hello -> handleHello(message.hello)
            is SiloControlMessage.Launch -> {
                if (!isAuthorized) {
                    sendError("unauthorized", "Connect with a matching Silo account first.")
                    return
                }
                // Play-on is SiloControl part 3 — not implemented on Android TV yet.
                sendError("unsupported", "Launching playback is not supported on this TV yet.")
            }
            is SiloControlMessage.Control -> {
                if (!isAuthorized) {
                    sendError("unauthorized", "Connect with a matching Silo account first.")
                    return
                }
                handleControl(message.command)
            }
            is SiloControlMessage.Ping -> session.enqueue(SiloControlMessage.Pong)
            is SiloControlMessage.Pong -> missedHeartbeats = 0
            is SiloControlMessage.State, is SiloControlMessage.Error -> Unit
            is SiloControlMessage.Close -> closeActiveSession(sendClose = false)
        }
    }

    private fun handleHello(hello: SiloControlHello) {
        val activeServerId = serverRegistry.activeServerId.value
        val serverId = hello.serverId
        if (serverId.isNullOrEmpty() || activeServerId == null || serverId != activeServerId) {
            sendError("server_mismatch", "This TV is connected to a different Silo server.")
            closeActiveSession(sendClose = true)
            return
        }
        isAuthorized = true
        authWatchdogJob?.cancel()
        authWatchdogJob = null
        Log.i(TAG, "controller authorized: ${hello.deviceName}")
    }

    private fun handleControl(command: SiloControlCommand) {
        if (command.name == SiloControlCommandName.Stop) {
            stopRemotePlayback()
            return
        }
        val viewModel = playerViewModel
        if (viewModel == null) {
            sendError("player_not_ready", "The TV player is not ready yet.")
            return
        }
        try {
            viewModel.applySiloControlCommand(command, activePlayerHolder.player.value)
            sendState()
        } catch (t: Throwable) {
            sendError("command_failed", t.message ?: "Command failed.")
        }
    }

    private fun stopRemotePlayback() {
        playerViewModel?.remoteStop()
        playerViewModel = null
        playerContentId = null
        stateJob?.cancel()
        stateJob = null
        sendState()
        advertiser.setPlaying(false)
    }

    private fun handleConnectionClosed(session: ControlSession) {
        if (activeSession !== session) return
        activeSession = null
        readJob = null
        resetSessionJobs()
        session.transport.close()
    }

    private fun closeActiveSession(sendClose: Boolean) {
        val session = activeSession
        val read = readJob
        activeSession = null
        readJob = null
        resetSessionJobs()
        if (session == null) {
            read?.cancel()
            return
        }
        Log.i(TAG, "closing control session sendClose=$sendClose")
        // Send the goodbye BEFORE cancelling the read loop — cancelling the
        // consumer tears the connection down and races ahead of the Close, so
        // the peer sees a bare EOF and instantly auto-reconnects (the
        // "Disconnect Remote loops right back" bug on tvOS). Stray inbound
        // messages during the goodbye are dropped by the activeSession guard.
        scope.launch(Dispatchers.IO) {
            session.shutdown(sendGoodbye = sendClose)
            read?.cancel()
        }
    }

    private fun resetSessionJobs() {
        stateJob?.cancel()
        stateJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        authWatchdogJob?.cancel()
        authWatchdogJob = null
        missedHeartbeats = 0
        isAuthorized = false
    }

    // ---- Heartbeat + auth watchdog -------------------------------------------------

    private fun startHeartbeat(session: ControlSession) {
        heartbeatJob?.cancel()
        missedHeartbeats = 0
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (activeSession !== session) return@launch
                missedHeartbeats += 1
                if (missedHeartbeats > MAX_MISSED_HEARTBEATS) {
                    Log.i(TAG, "controller heartbeat timed out; closing session")
                    closeActiveSession(sendClose = false)
                    return@launch
                }
                session.enqueue(SiloControlMessage.Ping)
            }
        }
    }

    private fun startAuthWatchdog(session: ControlSession) {
        authWatchdogJob?.cancel()
        authWatchdogJob = scope.launch {
            delay(AUTH_GRACE_PERIOD_MS)
            if (activeSession !== session || isAuthorized) return@launch
            Log.i(TAG, "controller never authorized; closing session")
            closeActiveSession(sendClose = true)
        }
    }

    // ---- State push ---------------------------------------------------------------

    private fun startStateUpdates() {
        stateJob?.cancel()
        if (activeSession == null) return
        stateJob = scope.launch {
            while (isActive) {
                delay(STATE_PUSH_INTERVAL_MS)
                sendState()
            }
        }
    }

    private fun sendState() {
        val session = activeSession ?: return
        val state = playerViewModel
            ?.makeSiloControlPlaybackState(playerContentId, activePlayerHolder.player.value)
            ?: idleState()
        session.enqueue(SiloControlMessage.State(state))
    }

    private fun sendError(code: String, message: String) {
        activeSession?.enqueue(
            SiloControlMessage.Error(SiloControlErrorMessage(code = code, message = message)),
        )
    }

    private fun makeHello(): SiloControlMessage {
        val entry = serverRegistry.activeEntry.value
        return SiloControlMessage.Hello(
            SiloControlHello(
                role = SiloControlPeerRole.Tv,
                deviceName = Build.MODEL?.trim()?.ifBlank { null } ?: "Android TV",
                deviceId = PairingDeviceId.stable(context),
                serverId = entry?.id,
                serverName = entry?.displayName,
                supportedVersions = listOf(SiloControlProtocol.VERSION),
            ),
        )
    }

    private fun idleState(): SiloControlPlaybackState = SiloControlPlaybackState(
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
        activeQualityId = VIDEO_QUALITY_AUTO_ID,
        isQualitySwitching = false,
        playbackSpeed = playbackSpeedSetting.value,
        videoGravity = videoGravitySetting.value,
        hdrEnabled = hdrEnabledSetting.value,
        supportsVideoGravity = false,
        supportsHDRToggle = false,
        volume = 1.0,
        isMuted = false,
        hasNextEpisode = false,
        nextEpisodeTitle = null,
        error = null,
    )
}

/**
 * One accepted controller connection. Outbound messages flow through an
 * ordered channel drained by a single IO writer, so the TV hello always
 * precedes the first state push and a graceful [shutdown] can flush the
 * goodbye before the socket closes.
 */
private class ControlSession(
    val transport: TlsPskControlTransport,
    scope: CoroutineScope,
) {
    private val outbound = Channel<SiloControlMessage>(capacity = 64)
    private val writer = scope.launch(Dispatchers.IO) {
        try {
            for (message in outbound) {
                transport.send(message)
            }
        } catch (_: Throwable) {
            // Socket died mid-write; the read loop surfaces the failure.
        }
    }

    fun enqueue(message: SiloControlMessage) {
        outbound.trySend(message)
    }

    /** Flush queued messages (plus the goodbye when [sendGoodbye]) and close. */
    suspend fun shutdown(sendGoodbye: Boolean) {
        if (sendGoodbye) outbound.trySend(SiloControlMessage.Close)
        outbound.close()
        withTimeoutOrNull(1_000L) { writer.join() }
        transport.close()
    }
}
