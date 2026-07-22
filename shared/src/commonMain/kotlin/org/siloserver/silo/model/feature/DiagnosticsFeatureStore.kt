package org.siloserver.silo.model.feature

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.siloserver.silo.model.diagnostics.DiagnosticsAvailability
import org.siloserver.silo.model.diagnostics.DiagnosticsStatusResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.DiagnosticsRepository

/**
 * Server-gated diagnostics capability, mirroring [RequestsFeatureStore]'s
 * generation-guarded refresh/reset idiom.
 *
 * Unlike the requests gate, the Diagnostics settings surface stays visible in
 * every state for non-child profiles — the store distinguishes
 * available / disabled-by-server / storage-unavailable / offline so the UI can
 * say why uploads aren't possible. On refresh failure the previous status
 * response (limits, server instance id) is retained under [DiagnosticsAvailability.OFFLINE].
 */
class DiagnosticsFeatureStore(
    private val repository: DiagnosticsRepository,
) {
    data class State(
        val availability: DiagnosticsAvailability = DiagnosticsAvailability.UNKNOWN,
        val response: DiagnosticsStatusResponse? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    // Written on the UI dispatcher (reset) and read after IO resumption
    // (refresh); Volatile gives the stale-response guard a happens-before.
    @kotlin.concurrent.Volatile
    private var generation: Int = 0

    suspend fun refresh() {
        val refreshGeneration = generation
        when (val result = repository.status()) {
            is ApiResult.Success -> {
                if (refreshGeneration == generation) {
                    _state.value = State(result.data.availability, result.data)
                }
            }
            is ApiResult.Error,
            is ApiResult.NetworkError,
            -> {
                if (refreshGeneration == generation) {
                    _state.value = State(DiagnosticsAvailability.OFFLINE, _state.value.response)
                }
            }
        }
    }

    fun reset() {
        generation += 1
        _state.value = State()
    }
}
