package org.siloserver.silo.model.feature

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.siloserver.silo.model.metadata.MetadataAiStatus
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.MetadataAiRepository

/**
 * Server-gated metadata AI (description translation) capability.
 *
 * Matches the Apple clients' AICapabilities metadata surface: entry points
 * start hidden, a successful `/api/v2/capabilities/metadata-ai` probe controls
 * visibility, transient failures keep the previous value, and reset hides
 * the surface before a server/profile switch can reuse stale state.
 */
class MetadataAiFeatureStore(
    private val repository: MetadataAiRepository,
) {
    private val _status = MutableStateFlow(MetadataAiStatus())
    val status: StateFlow<MetadataAiStatus> = _status.asStateFlow()

    // Written on the UI dispatcher (reset) and read after IO resumption
    // (refresh); Volatile gives the stale-response guard a happens-before.
    @kotlin.concurrent.Volatile
    private var generation: Int = 0

    suspend fun refresh() {
        val refreshGeneration = generation
        when (val result = repository.status()) {
            is ApiResult.Success -> {
                if (refreshGeneration == generation) {
                    _status.value = result.data
                }
            }
            is ApiResult.Error,
            is ApiResult.NetworkError,
            -> Unit
        }
    }

    fun reset() {
        generation += 1
        _status.value = MetadataAiStatus()
    }
}
