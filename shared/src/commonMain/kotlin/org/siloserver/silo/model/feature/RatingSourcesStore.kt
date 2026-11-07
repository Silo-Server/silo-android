package org.siloserver.silo.model.feature

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.RatingsCapabilityApi

/**
 * The rating sources the server shows, from `/api/v2/capabilities/ratings`.
 *
 * Sort lists offer a rating sort only while its source is shown, as the web
 * client does. The set starts empty, so a rating the server may hide is not
 * offered before the answer arrives; transient failures keep the previous
 * value, and [reset] clears it before a server or profile switch.
 */
class RatingSourcesStore(
    private val api: RatingsCapabilityApi,
) {
    private val _shown = MutableStateFlow<Set<String>>(emptySet())
    val shown: StateFlow<Set<String>> = _shown.asStateFlow()

    // Written on the UI dispatcher (reset) and read after IO resumption
    // (refresh); Volatile gives the stale-response guard a happens-before.
    @kotlin.concurrent.Volatile
    private var generation: Int = 0

    suspend fun refresh() {
        val refreshGeneration = generation
        when (val result = api.shownSources()) {
            is ApiResult.Success -> if (refreshGeneration == generation) _shown.value = result.data
            is ApiResult.Error,
            is ApiResult.NetworkError,
            -> Unit
        }
    }

    fun reset() {
        generation += 1
        _shown.value = emptySet()
    }
}
