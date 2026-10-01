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
 * can reuse stale capability state. The feature counts only when the server
 * says this profile is `allowed` and the state is `available`.
 *
 * [canModerate] says whether the signed-in user can approve, decline, and retry
 * everyone's requests: an admin acting as the primary profile on a server with
 * the request integration configured. It is probed only once requests are
 * enabled, and is false on any answer other than `available: true`.
 */
class RequestsFeatureStore(
    private val repository: RequestsRepository,
) {
    private val _isEnabled = MutableStateFlow(false)
    val isEnabled: StateFlow<Boolean> = _isEnabled.asStateFlow()

    private val _canModerate = MutableStateFlow(false)
    val canModerate: StateFlow<Boolean> = _canModerate.asStateFlow()

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
        val moderates = if (!enabled) {
            false
        } else {
            when (val result = repository.adminCapabilities()) {
                is ApiResult.Success -> result.data.available
                // The server answered: not an admin, not the primary profile,
                // or no integration configured.
                is ApiResult.Error -> false
                // Transport trouble: keep the previous value.
                is ApiResult.NetworkError -> _canModerate.value
            }
        }
        if (refreshGeneration != generation) return
        // Moderation first: a screen that appears when Requests does already
        // knows whether to load the approval queue.
        _canModerate.value = moderates
        _isEnabled.value = enabled
        _isResolved.value = true
    }

    fun reset() {
        generation += 1
        _isEnabled.value = false
        _canModerate.value = false
        _isResolved.value = false
    }
}
