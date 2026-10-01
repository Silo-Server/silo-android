package org.siloserver.silo.repository

import org.siloserver.silo.model.request.AdminRequestAction
import org.siloserver.silo.model.request.AdminRequestCapabilities
import org.siloserver.silo.model.request.CreateMediaRequest
import org.siloserver.silo.model.request.MediaRequest
import org.siloserver.silo.model.request.RequestMediaDetail
import org.siloserver.silo.model.request.RequestMediaPage
import org.siloserver.silo.model.request.RequestsDiscoverResponse
import org.siloserver.silo.model.request.RequestsFeatureStatus
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.RequestsApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * One request mutation, broadcast so every mounted request surface can patch
 * its own items in place. [sequence] makes a repeat of the same record a new event.
 */
data class RequestEvent(val record: MediaRequest, val sequence: Long)

class RequestsRepository(
    private val api: RequestsApi,
    val cache: RequestDetailCache = RequestDetailCache(),
) {

    private val _mine = MutableStateFlow<List<MediaRequest>>(emptyList())
    val mine: StateFlow<List<MediaRequest>> = _mine.asStateFlow()

    private var eventSequence = 0L

    private val _lastUpdate = MutableStateFlow<RequestEvent?>(null)
    /** The most recent create or cancel of the signed-in user's own request. */
    val lastUpdate: StateFlow<RequestEvent?> = _lastUpdate.asStateFlow()

    private val _lastModeration = MutableStateFlow<RequestEvent?>(null)
    /**
     * The most recent admin decision (approve, decline, retry) on anyone's
     * request. Kept apart from [lastUpdate], whose consumers treat every record
     * as the signed-in user's own.
     */
    val lastModeration: StateFlow<RequestEvent?> = _lastModeration.asStateFlow()

    suspend fun status(): ApiResult<RequestsFeatureStatus> = api.status()

    suspend fun discover(): ApiResult<RequestsDiscoverResponse> = api.discover()

    suspend fun discoverSection(section: String, page: Int = 1): ApiResult<RequestMediaPage> =
        api.discoverSection(section, page)

    suspend fun search(
        query: String,
        mediaType: String? = null,
        page: Int = 1,
    ): ApiResult<RequestMediaPage> = api.search(query, mediaType, page)

    suspend fun detail(mediaType: String, tmdbId: Int): ApiResult<RequestMediaDetail> =
        api.detail(mediaType, tmdbId).also { if (it is ApiResult.Success) cache.store(it.data) }

    suspend fun get(id: String): ApiResult<MediaRequest> = api.get(id)

    suspend fun refreshMine(
        status: String? = null,
        outcome: String? = null,
        limit: Int? = null,
        offset: Int? = null,
    ): ApiResult<Unit> = when (val result = api.mine(status, outcome, limit, offset)) {
        is ApiResult.Success -> {
            _mine.value = result.data.requests
            if (status == null && outcome == null) cache.storeOwnRecords(result.data.requests)
            ApiResult.Success(Unit)
        }
        is ApiResult.Error -> ApiResult.Error(result.code, result.error, result.message)
        is ApiResult.NetworkError -> ApiResult.NetworkError(result.exception)
    }

    suspend fun create(request: CreateMediaRequest): ApiResult<MediaRequest> =
        api.create(request).also { if (it is ApiResult.Success) publish(it.data) }

    suspend fun cancel(id: String): ApiResult<MediaRequest> =
        api.cancel(id).also { if (it is ApiResult.Success) publish(it.data) }

    suspend fun adminCapabilities(): ApiResult<AdminRequestCapabilities> = api.adminCapabilities()

    suspend fun adminRequests(
        status: String? = null,
        outcome: String? = null,
        mediaType: String? = null,
        tmdbId: Int? = null,
    ): ApiResult<List<MediaRequest>> = when (val result = api.adminRequests(status, outcome, mediaType, tmdbId)) {
        is ApiResult.Success -> ApiResult.Success(result.data.requests)
        is ApiResult.Error -> result
        is ApiResult.NetworkError -> result
    }

    suspend fun adminAction(id: String, action: AdminRequestAction, reason: String? = null): ApiResult<MediaRequest> =
        api.adminAction(id, action, reason).also { if (it is ApiResult.Success) publishModeration(it.data) }

    /** Warms the first rows' details so opening them lands on a finished page. */
    fun prefetch(records: List<MediaRequest>) {
        cache.prefetch(records) { key -> api.detail(key.mediaType, key.tmdbId) }
    }

    fun reset() {
        _mine.value = emptyList()
        _lastUpdate.value = null
        _lastModeration.value = null
        cache.clear()
    }

    private fun publish(request: MediaRequest) {
        upsertMine(request)
        cache.storeOwnRecord(request)
        _lastUpdate.value = RequestEvent(request, ++eventSequence)
    }

    private fun publishModeration(request: MediaRequest) {
        cache.unpinModeration(request)
        // An admin deciding on their own request: their own lists update too.
        if (cache.ownRecord(request.cacheKey())?.id == request.id) publish(request)
        _lastModeration.value = RequestEvent(request, ++eventSequence)
    }

    private fun upsertMine(request: MediaRequest) {
        _mine.update { list ->
            val replaced = list.map { if (it.id == request.id) request else it }
            if (replaced.any { it.id == request.id }) replaced else replaced + request
        }
    }
}
