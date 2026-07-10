package org.siloserver.silo.android.cast

import android.util.Log
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
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
import org.siloserver.silo.network.getOrThrow
import org.siloserver.silo.network.api.DeviceLoginApi
import org.siloserver.silo.repository.ProfileRepository

data class SiloCastControllerState(
    val targets: List<SiloCastTarget> = emptyList(),
    val connectedTarget: SiloCastTarget? = null,
    val playbackState: SiloCastPlaybackState? = null,
    val isConnecting: Boolean = false,
    val isPreparingIdentity: Boolean = false,
    val error: String? = null,
) {
    val isConnected: Boolean get() = connectedTarget != null
}

/**
 * Phone-side SiloCast controller. Wire-compatible with silo-apple's
 * SiloControlClient/TVControlReceiver: TLS-PSK transport ([SiloCastTls]),
 * a `hello` carrying role=phone and the active serverId (the receiver
 * authorizes only a matching server), pong replies to receiver pings, and a
 * `close` goodbye on deliberate disconnect so the receiver doesn't treat it
 * as a dropped link.
 */
class SiloCastController(
    private val browser: SiloCastNsdBrowser,
    private val serverRegistry: ServerRegistry,
    private val tokenManager: TokenManager,
    private val deviceLoginApi: DeviceLoginApi,
    private val profileRepository: ProfileRepository,
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
    private val launchMutex = Mutex()
    private val clock = SiloCastPlaybackClock()

    private val _state = MutableStateFlow(SiloCastControllerState())
    val state: StateFlow<SiloCastControllerState> = _state.asStateFlow()

    private var session: SiloCastTlsClientSession? = null
    private var output: OutputStream? = null
    private var readJob: Job? = null
    private var negotiatedVersion: Int? = null
    private var helloDeferred: CompletableDeferred<Int>? = null
    private var pendingHandoffRequestId: String? = null
    private var challengeDeferred: CompletableDeferred<SiloCastHandoffChallenge>? = null
    private var readyDeferred: CompletableDeferred<SiloCastHandoffReady>? = null

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
        scope.launch {
            runCatching {
                launchMutex.withLock {
                    launchWithPhoneIdentity(target, request)
                }
            }.onFailure { error ->
                if (error !is CancellationException) {
                    _state.update {
                        it.copy(
                            isConnecting = false,
                            isPreparingIdentity = false,
                            error = error.message ?: "Unable to cast.",
                        )
                    }
                }
            }
        }
    }

    private suspend fun launchWithPhoneIdentity(
        target: SiloCastTarget,
        request: SiloCastLaunchRequest,
    ) {
        require(target.version >= 2) { "Update Silo on this TV to use your profile." }
        val captured = tokenManager.snapshotCurrentScope()
            ?: error("Choose a signed-in Silo server before playing on TV.")
        val profileId = captured.profileId
            ?.takeIf { it.isNotBlank() }
            ?: error("Choose a profile before playing on TV.")
        val profileName = profileRepository.getActiveProfile()?.name
        require(request.serverId == captured.serverId) { "The active server changed. Try again." }

        val version = ensureConnected(target)
        require(version >= 2) { "Update Silo on this TV to use your profile." }

        val requestId = UUID.randomUUID().toString()
        pendingHandoffRequestId = requestId
        challengeDeferred = CompletableDeferred()
        readyDeferred = CompletableDeferred()
        _state.update { it.copy(isPreparingIdentity = true, error = null) }
        var userCode: String? = null

        try {
            send(
                SiloCastMessage.HandoffOffer(
                    SiloCastHandoffOffer(
                        requestId = requestId,
                        serverId = captured.serverId,
                        serverURL = captured.serverUrl,
                        serverName = serverRegistry.activeEntry.value?.displayName,
                        profileId = profileId,
                        profileName = profileName,
                    ),
                ),
            )
            val challenge = withTimeout(HANDOFF_TIMEOUT_MS) {
                challengeDeferred?.await() ?: error("Profile handoff was cancelled.")
            }
            userCode = challenge.userCode
            val lookup = deviceLoginApi.lookupRemotePlayback(challenge.userCode, captured).getOrThrow()
            require(lookup.clientPurpose == "remote_playback" && lookup.temporary == true) {
                "The TV requested an unsupported sign-in."
            }
            require(lookup.matchCode == challenge.matchCode) {
                "The TV profile verification code did not match."
            }
            requireCurrentScope(captured.serverId, profileId)
            deviceLoginApi.approveRemotePlayback(
                token = null,
                code = challenge.userCode,
                scope = captured,
            ).getOrThrow()

            val ready = withTimeout(HANDOFF_TIMEOUT_MS) {
                readyDeferred?.await() ?: error("Profile handoff was cancelled.")
            }
            require(
                ready.requestId == requestId &&
                    ready.serverId == captured.serverId &&
                    ready.profileId == profileId,
            ) { "The TV activated a different profile." }
            requireCurrentScope(captured.serverId, profileId)
            send(SiloCastMessage.Launch(request))
            _state.update { it.copy(isPreparingIdentity = false, error = null) }
        } catch (t: Throwable) {
            userCode?.let { code ->
                runCatching { deviceLoginApi.denyRemotePlayback(code, captured) }
            }
            runCatching {
                send(
                    SiloCastMessage.HandoffCancel(
                        SiloCastHandoffCancel(
                            requestId = requestId,
                            reason = "controller_cancelled",
                            message = null,
                        ),
                    ),
                )
            }
            throw t
        } finally {
            pendingHandoffRequestId = null
            challengeDeferred = null
            readyDeferred = null
        }
    }

    private suspend fun requireCurrentScope(serverId: String, profileId: String) {
        val current = tokenManager.snapshotCurrentScope()
        require(current?.serverId == serverId && current.profileId == profileId) {
            "The active server or profile changed. Try again."
        }
    }

    fun disconnect() {
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

    fun displayTime(): Double = clock.displayTime(nowMs())

    fun isPlaying(): Boolean = clock.isPlaying(nowMs())

    private fun sendControl(command: SiloCastControlCommand) {
        scope.launch {
            runCatching { send(SiloCastMessage.Control(command)) }
                .onFailure { error -> _state.update { it.copy(error = error.message) } }
        }
    }

    private suspend fun ensureConnected(target: SiloCastTarget): Int = connectionMutex.withLock {
        if (_state.value.connectedTarget?.deviceId == target.deviceId && session?.isConnected == true) {
            return@withLock negotiatedVersion ?: error("The TV has not finished connecting.")
        }
        closeConnectionLocked()
        _state.update { it.copy(isConnecting = true, error = null) }
        val newSession = withContext(Dispatchers.IO) {
            SiloCastTls.connect(target.host, target.port, CONNECT_TIMEOUT_MS)
        }
        session = newSession
        output = newSession.output
        val hello = CompletableDeferred<Int>()
        helloDeferred = hello
        _state.update { it.copy(connectedTarget = target, isConnecting = false, error = null) }
        readJob = scope.launch { readLoop(newSession) }
        send(SiloCastMessage.Hello(makeHello()))
        withTimeout(HELLO_TIMEOUT_MS) { hello.await() }
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
            Log.w(TAG, "SiloCast read loop ended", t)
            // Guard like closeConnection() below: the old session's read loop
            // can outlive a reconnect and must not clobber the fresh
            // session's state with its stale error.
            if (session === activeSession) {
                _state.update { it.copy(error = t.message) }
            }
        } finally {
            if (session === activeSession) {
                closeConnection()
            }
        }
    }

    private suspend fun handleMessage(message: SiloCastMessage) {
        when (message) {
            is SiloCastMessage.Hello -> {
                val version = SiloCastProtocol.negotiatedVersion(message.hello.supportedVersions)
                    ?: error("Update Silo on both devices to continue.")
                negotiatedVersion = version
                helloDeferred?.complete(version)
            }
            is SiloCastMessage.HandoffChallenge -> {
                if (message.handoffChallenge.requestId == pendingHandoffRequestId) {
                    challengeDeferred?.complete(message.handoffChallenge)
                }
            }
            is SiloCastMessage.HandoffReady -> {
                if (message.handoffReady.requestId == pendingHandoffRequestId) {
                    readyDeferred?.complete(message.handoffReady)
                }
            }
            is SiloCastMessage.HandoffCancel -> {
                if (message.handoffCancel.requestId == pendingHandoffRequestId) {
                    val error = IllegalStateException(
                        message.handoffCancel.message ?: "Profile handoff was cancelled.",
                    )
                    challengeDeferred?.completeExceptionally(error)
                    readyDeferred?.completeExceptionally(error)
                }
            }
            is SiloCastMessage.State -> {
                clock.ingest(message.state, nowMs())
                _state.update { it.copy(playbackState = message.state, error = null) }
            }
            is SiloCastMessage.Error -> _state.update { it.copy(error = message.error.message) }
            is SiloCastMessage.Ping -> send(SiloCastMessage.Pong())
            is SiloCastMessage.Pong -> Unit
            is SiloCastMessage.Close -> closeConnection()
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

    private fun closeConnectionLocked() {
        runCatching { session?.close() }
        session = null
        output = null
        val closed = IllegalStateException("The TV disconnected during profile handoff.")
        helloDeferred?.completeExceptionally(closed)
        challengeDeferred?.completeExceptionally(closed)
        readyDeferred?.completeExceptionally(closed)
        helloDeferred = null
        negotiatedVersion = null
        readJob?.cancel()
        readJob = null
        _state.update {
            it.copy(
                connectedTarget = null,
                playbackState = null,
                isConnecting = false,
                isPreparingIdentity = false,
            )
        }
    }

    fun close() {
        browser.stop()
        closeConnectionLocked()
        scope.cancel()
    }

    private fun nowMs(): Long = android.os.SystemClock.elapsedRealtime()

    private companion object {
        const val TAG = "SiloCastController"
        const val CONNECT_TIMEOUT_MS = 5_000
        const val HELLO_TIMEOUT_MS = 5_000L
        const val HANDOFF_TIMEOUT_MS = 90_000L
    }
}
