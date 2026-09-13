package org.siloserver.silo.model.feature

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.RequestsRepository

/**
 * Server-gated media requests capability.
 *
 * Matches the Apple clients: entry points start hidden, a successful
 * `/api/v2/requests/status` probe controls visibility, transient failures keep
 * the previous value, and reset hides the surface before a server/profile switch
 * can reuse stale capability state.
 */
class RequestsFeatureStore(
    private val repository: RequestsRepository,
) {
    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    // Written on the UI dispatcher (reset) and read after IO resumption
    // (refresh); Volatile gives the stale-response guard a happens-before.
    @kotlin.concurrent.Volatile
    private var generation: Int = 0

    suspend fun refresh() {
        val refreshGeneration = generation
        when (val result = repository.status()) {
            is ApiResult.Success -> {
                if (refreshGeneration == generation) {
                    _isEnabled.value = result.data.requestsEnabled
                }
            }
            is ApiResult.Error,
            is ApiResult.NetworkError,
            -> Unit
        }
    }

    fun reset() {
        generation += 1
        _isEnabled.value = false
    }
}
