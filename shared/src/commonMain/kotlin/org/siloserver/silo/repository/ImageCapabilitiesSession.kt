package org.siloserver.silo.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionPhase
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.api.ImageCapabilities
import org.siloserver.silo.network.api.ImageCapabilitiesApi
import org.siloserver.silo.network.getOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Best-effort discovery per active session. Artwork URLs never wait for this
 * probe; requests that carry per-slot widths wait briefly for it ([settled]).
 */
class ImageCapabilitiesSession(
    private val api: ImageCapabilitiesApi,
    private val tokens: TokenManager,
    private val barrier: IdentityTransitionBarrier,
) {
    /** A finished probe and the server it ran against. */
    class Probe internal constructor(val serverId: String, val capabilities: ImageCapabilities?)

    private val _capabilities = MutableStateFlow<ImageCapabilities?>(null)
    val capabilities = _capabilities.asStateFlow()

    // The active server's finished probe; null while it runs or while signed out.
    private val probe = MutableStateFlow<Probe?>(null)

    /** Whether the active identity's probe has answered or failed. */
    val hasSettled: Boolean get() = probe.value != null

    /**
     * The probe for the active server, once it answers or fails. A probe of
     * another server is never returned, so a server switch can't carry one
     * server's capabilities to the next. A request waits at most [timeout] and
     * then goes without them, as it would to an older server.
     */
    suspend fun settled(timeout: Duration = SETTLE_TIMEOUT): Probe? {
        val serverId = tokens.snapshotCurrentScope()?.serverId ?: return null
        return withTimeoutOrNull(timeout) { probe.first { it?.serverId == serverId } }
    }

    fun start(scope: CoroutineScope): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        barrier.transitions.filter { it.affectsCurrentIdentity }
            .map { it.phase == IdentityTransitionPhase.DID_CHANGE }
            .onStart { emit(true) }
            .collectLatest { shouldProbe ->
                _capabilities.value = null
                probe.value = null
                if (!shouldProbe) return@collectLatest
                val owner = tokens.snapshotCurrentScope() ?: return@collectLatest
                if (tokens.getAccessTokenForScope(owner).isNullOrBlank()) {
                    probe.value = Probe(owner.serverId, null)
                    return@collectLatest
                }
                var retry = 0
                while (true) {
                    val result = api.get(owner)
                    // A token refresh during the probe keeps the server; a switch drops the answer.
                    if (owner.serverId != tokens.snapshotCurrentScope()?.serverId) return@collectLatest
                    _capabilities.value = result.getOrNull()
                    probe.value = Probe(owner.serverId, result.getOrNull())
                    // A server that answered keeps its answer; an unreachable one
                    // may come back, such as a TV that starts before its network.
                    val transient = result is ApiResult.NetworkError || result is ApiResult.Error && result.code >= 500
                    if (!transient) return@collectLatest
                    delay(RETRY_DELAYS[minOf(retry++, RETRY_DELAYS.lastIndex)])
                }
            }
    }

    private companion object {
        val SETTLE_TIMEOUT = 2.seconds
        val RETRY_DELAYS = listOf(5.seconds, 30.seconds, 2.minutes, 10.minutes)
    }
}
