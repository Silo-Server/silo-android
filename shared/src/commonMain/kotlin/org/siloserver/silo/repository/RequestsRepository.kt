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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * One request mutation, broadcast so every mounted request surface can patch
 * its own items in place. [sequence] makes a repeat of the same record a new event.
 */
data class RequestEvent(val record: MediaRequest, val sequence: Long)

/**
 * Profile-scoped request state: the user's own list, the mutation broadcasts,
 * and [cache]. [reset] starts a new session; a read or mutation that began
 * before it answers without writing anything back, so the previous profile's
 * records never seed the next one's pages.
 *
 * Main-thread confined, like [RequestDetailCache].
 */
class RequestsRepository(
    private val api: RequestsApi,
    val cache: RequestDetailCache = RequestDetailCache(),
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {

    private val _mine = MutableStateFlow<List<MediaRequest>>(emptyList())
    val mine: StateFlow<List<MediaRequest>> = _mine.asStateFlow()

    private var eventSequence = 0L

    /** Bumped by each of this client's own creates and cancels as they land. */
    private var ownMutations = 0L

    /** Bumped by [reset]: anything still running from the previous session drops its result. */
    @kotlin.concurrent.Volatile
    private var generation = 0

    /** When [mine] last held a complete, unfiltered read; null until one lands in this session. */
    private var mineReadAt: TimeMark? = null

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
        read({ api.detail(mediaType, tmdbId) }) { cache.store(it) }

    suspend fun get(id: String): ApiResult<MediaRequest> = api.get(id)

    suspend fun refreshMine(
        status: String? = null,
        outcome: String? = null,
        limit: Int? = null,
        offset: Int? = null,
    ): ApiResult<Unit> {
        val mutationsBefore = ownMutations
        val result = read({ api.mine(status, outcome, limit, offset) }) { response ->
            // A create or cancel that landed while this read was out isn't in
            // it: keep the patched list, and let the next caller read again.
            if (ownMutations != mutationsBefore) {
                mineReadAt = null
                return@read
            }
            _mine.value = response.requests
            if (status == null && outcome == null) {
                cache.storeOwnRecords(response.requests)
                mineReadAt = timeSource.markNow()
            } else {
                mineReadAt = null
            }
        }
        return when (result) {
            is ApiResult.Success -> ApiResult.Success(Unit)
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }

    /**
     * [mine] as of no more than [maxAge] ago, reading only when it's older.
     * This client's own creates and cancels patch [mine] as they land, so a
     * recent read stays current for them; anything that must see another
     * device's change reads with a zero [maxAge].
     */
    suspend fun ensureMine(maxAge: Duration = MineFreshness): ApiResult<Unit> {
        val readAt = mineReadAt
        if (readAt != null && readAt.elapsedNow() < maxAge) return ApiResult.Success(Unit)
        return refreshMine()
    }

    suspend fun create(request: CreateMediaRequest): ApiResult<MediaRequest> =
        mutate({ api.create(request) }, ::publish)

    suspend fun cancel(id: String): ApiResult<MediaRequest> =
        mutate({ api.cancel(id) }, ::publish)

    suspend fun adminCapabilities(): ApiResult<AdminRequestCapabilities> = api.adminCapabilities()

    suspend fun adminRequests(
        status: String? = null,
        outcome: String? = null,
        mediaType: String? = null,
        tmdbId: Int? = null,
    ): ApiResult<List<MediaRequest>> = when (val result = read({ api.adminRequests(status, outcome, mediaType, tmdbId) })) {
        is ApiResult.Success -> ApiResult.Success(result.data.requests)
        is ApiResult.Error -> result
        is ApiResult.NetworkError -> result
    }

    /** How many requests match, up to one page, and whether more follow. */
    suspend fun adminRequestCount(status: String? = null, outcome: String? = null): ApiResult<AdminRequestCount> =
        when (val result = read({ api.adminRequestsFirstPage(status, outcome) })) {
            is ApiResult.Success -> ApiResult.Success(AdminRequestCount(result.data.requests.size, result.data.page.hasMore))
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }

    suspend fun adminAction(id: String, action: AdminRequestAction, reason: String? = null): ApiResult<MediaRequest> =
        mutate({ api.adminAction(id, action, reason) }, ::publishModeration)

    /** Warms the first rows' details so opening them lands on a finished page. */
    fun prefetch(records: List<MediaRequest>) {
        cache.prefetch(records) { key -> api.detail(key.mediaType, key.tmdbId) }
    }

    fun reset() {
        generation++
        mineReadAt = null
        _mine.value = emptyList()
        _lastUpdate.value = null
        _lastModeration.value = null
        cache.clear()
    }

    /**
     * A read for the current session. When a [reset] lands while it runs, the
     * answer belongs to the previous profile or server: nothing is stored and
     * the caller gets a failure instead of the stale data.
     */
    private suspend fun <T> read(call: suspend () -> ApiResult<T>, store: (T) -> Unit = {}): ApiResult<T> {
        val session = generation
        val result = call()
        if (session != generation) return SessionChanged
        if (result is ApiResult.Success) store(result.data)
        return result
    }

    /**
     * A mutation for the current session. The server acted either way, so the
     * caller gets the real result, but one that finishes after a [reset] isn't
     * broadcast into the next session.
     */
    private suspend fun <T> mutate(call: suspend () -> ApiResult<T>, publish: (T) -> Unit): ApiResult<T> {
        val session = generation
        val result = call()
        if (session == generation && result is ApiResult.Success) publish(result.data)
        return result
    }

    private fun publish(request: MediaRequest) {
        ownMutations++
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

    companion object {
        /** How long a complete read of [mine] answers for a page that only needs one title's record. */
        val MineFreshness: Duration = 30.seconds

        private val SessionChanged = ApiResult.Error(0, "identity_changed", "The acting account or profile changed.")
    }
}

/** A count of admin requests, read one page deep: [hasMore] means there are more than [count]. */
data class AdminRequestCount(val count: Int, val hasMore: Boolean)
