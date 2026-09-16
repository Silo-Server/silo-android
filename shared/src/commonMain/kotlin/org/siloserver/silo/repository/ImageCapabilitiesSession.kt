package org.siloserver.silo.repository

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionPhase
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.api.ImageCapabilities
import org.siloserver.silo.network.api.ImageCapabilitiesApi
import org.siloserver.silo.network.getOrNull

/** Best-effort discovery per active session. Artwork resolution never waits for this probe. */
class ImageCapabilitiesSession(
    private val api: ImageCapabilitiesApi,
    private val tokens: TokenManager,
    private val barrier: IdentityTransitionBarrier,
) {
    private val _capabilities = MutableStateFlow<ImageCapabilities?>(null)
    val capabilities = _capabilities.asStateFlow()

    fun start(scope: CoroutineScope): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        barrier.transitions.filter { it.affectsCurrentIdentity }
            .map { it.phase == IdentityTransitionPhase.DID_CHANGE }
            .onStart { emit(true) }
            .collectLatest { shouldProbe ->
                _capabilities.value = null
                if (!shouldProbe) return@collectLatest
                val owner = tokens.snapshotCurrentScope() ?: return@collectLatest
                if (tokens.getAccessTokenForScope(owner).isNullOrBlank()) return@collectLatest
                val result = api.get(owner)
                if (owner.isSameIdentityAs(tokens.snapshotCurrentScope())) {
                    _capabilities.value = result.getOrNull()
                }
            }
    }
}
