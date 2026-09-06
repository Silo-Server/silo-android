package org.siloserver.silo.common.network

import android.os.SystemClock
import org.siloserver.silo.network.apiv2.ApiV2ProbeResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class ServerReachabilityStatus {
    Unknown,
    Reachable,
    Unreachable,
}

data class ServerReachabilityState(
    val status: ServerReachabilityStatus = ServerReachabilityStatus.Unknown,
    val lastCheckedAtMs: Long? = null,
    val message: String? = null,
) {
    val canUseServer: Boolean
        get() = status != ServerReachabilityStatus.Unreachable
}

/** The configured server and transition generation, never a discovered server identity. */
data class ReachabilityTarget(val serverId: String, val url: String, val generation: Long)

/** Foreground-only fresh public discovery. Health and playback recovery remain separate. */
class ServerReachabilityMonitor(
    private val probe: suspend (String) -> ApiV2ProbeResult,
    private val scope: CoroutineScope,
    private val captureTarget: () -> ReachabilityTarget?,
    targetChanges: kotlinx.coroutines.flow.Flow<*> = kotlinx.coroutines.flow.emptyFlow<Unit>(),
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val onServerReconnected: suspend () -> Unit = {},
) {
    private val _state = MutableStateFlow(ServerReachabilityState())
    val state: StateFlow<ServerReachabilityState> = _state.asStateFlow()
    private val probeLock = Mutex()
    private var foregroundJob: Job? = null
    private val run = java.util.concurrent.atomic.AtomicLong()
    private val epoch = java.util.concurrent.atomic.AtomicLong()
    private var stateTarget: ReachabilityTarget? = null

    init {
        scope.launch {
            targetChanges.collect {
                if (captureTarget() != stateTarget) reset()
            }
        }
    }

    private fun reset() {
        run.incrementAndGet()
        stateTarget = null
        _state.value = ServerReachabilityState()
    }

    fun startForeground() {
        if (foregroundJob?.isActive == true) return
        epoch.incrementAndGet()
        foregroundJob = scope.launch {
            while (isActive) {
                val probed = retryNow()
                delay(if (probed.status == ServerReachabilityStatus.Unreachable)
                    UNREACHABLE_PROBE_INTERVAL_MS else REACHABLE_PROBE_INTERVAL_MS)
            }
        }
    }

    fun stopForeground() {
        epoch.incrementAndGet()
        foregroundJob?.cancel()
        foregroundJob = null
        reset()
    }

    suspend fun retryNow(): ServerReachabilityState {
        val target = captureTarget()
        val requestRun = run.incrementAndGet()
        val requestEpoch = epoch.get()
        fun current() = run.get() == requestRun && epoch.get() == requestEpoch && captureTarget() == target
        return probeLock.withLock {
            currentCoroutineContext().ensureActive()
            if (!current()) return@withLock _state.value
            if (stateTarget != target) {
                stateTarget = target
                _state.value = ServerReachabilityState()
            }
            if (target == null) return@withLock _state.value
            val wasUnreachable = _state.value.status == ServerReachabilityStatus.Unreachable
            val result = probe(target.url)
            currentCoroutineContext().ensureActive()
            if (!current()) return@withLock _state.value
            val next = when (result) {
                is ApiV2ProbeResult.V2 -> ServerReachabilityState(ServerReachabilityStatus.Reachable, nowMs())
                ApiV2ProbeResult.UpdateServer -> ServerReachabilityState(ServerReachabilityStatus.Unreachable, nowMs(), "Server update required")
                is ApiV2ProbeResult.Failure -> ServerReachabilityState(ServerReachabilityStatus.Unreachable, nowMs(), "Server discovery unavailable: "+result.kind.name.lowercase())
            }
            _state.value = next
            if (wasUnreachable && next.status == ServerReachabilityStatus.Reachable && current()) onServerReconnected()
            next
        }
    }

    companion object {
        const val REACHABLE_PROBE_INTERVAL_MS: Long = 20_000L
        const val UNREACHABLE_PROBE_INTERVAL_MS: Long = 8_000L
    }
}
