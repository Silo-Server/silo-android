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
 * can reuse stale capability state. Reset also clears [RequestsRepository]'s
 * session state, since both shells call it on sign-in, sign-out, and every
 * server or profile switch. The feature counts only when the server
 * says this profile is `allowed` and the state is `available`.
 *
 * Request administration (approve, decline, retry, limits) stays in web admin:
 * the native clients never probe or expose it.
 */
class RequestsFeatureStore(
    private val repository: RequestsRepository,
) {
    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _isResolved = MutableStateFlow(false)
    /** A probe has answered (or failed) since the last [reset]; until then [isEnabled] is only a default. */
    val isResolved: StateFlow<Boolean> = _isResolved.asStateFlow()

    // Written on the UI dispatcher (reset) and read after IO resumption
    // (refresh); Volatile gives the stale-response guard a happens-before.
    @kotlin.concurrent.Volatile
    private var generation: Int = 0

    suspend fun refresh() {
        val refreshGeneration = generation
        val enabled = when (val result = repository.status()) {
            is ApiResult.Success -> result.data.isAvailable
            // A transient failure shouldn't yank an already-visible entry point.
            is ApiResult.Error,
            is ApiResult.NetworkError,
            -> _isEnabled.value
        }
        if (refreshGeneration != generation) return
        _isEnabled.value = enabled
        _isResolved.value = true
    }

    /**
     * Re-probe when the app returns to the foreground, so an admin turning
     * requests off (or on) while the app was in the background shows up
     * without a restart, as on Apple. Skipped until the shell's own probe for
     * this session has answered: before that, nobody is signed in or a server
     * or profile switch is in flight, and the shell's probe will answer.
     */
    suspend fun refreshAfterReturnToForeground() {
        if (!_isResolved.value) return
        refresh()
    }

    fun reset() {
        generation += 1
        repository.reset()
        _isEnabled.value = false
        _isResolved.value = false
    }
}
