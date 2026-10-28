package org.siloserver.silo.repository

import org.siloserver.silo.model.request.CreateMediaRequest
import org.siloserver.silo.model.request.MediaRequest
import org.siloserver.silo.model.request.RequestFollowEvent
import org.siloserver.silo.model.request.RequestMediaDetail
import org.siloserver.silo.model.request.RequestMediaPage
import org.siloserver.silo.model.request.RequestState
import org.siloserver.silo.model.request.RequestsDiscoverResponse
import org.siloserver.silo.model.request.RequestsFeatureStatus
import org.siloserver.silo.model.request.applyingFollow
import org.siloserver.silo.model.request.applyingFollowUpdate
import org.siloserver.silo.model.request.RequestDiscoverySection
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.RequestsApi
import org.siloserver.silo.network.map
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

    private val _lastFollow = MutableStateFlow<RequestFollowEvent?>(null)
    /** The most recent follow or unfollow, so cards and pages showing the title agree. */
    val lastFollow: StateFlow<RequestFollowEvent?> = _lastFollow.asStateFlow()

    /** Each title's latest follow or unfollow this session, for reads that were out when it landed. */
    private val follows = mutableMapOf<RequestDetailCache.Key, RequestFollowEvent>()

    suspend fun status(): ApiResult<RequestsFeatureStatus> = api.status()

    suspend fun discover(): ApiResult<RequestsDiscoverResponse> = api.discover()

    suspend fun discoverRow(rowKey: String, page: Int = 1): ApiResult<RequestDiscoverySection> =
        api.discoverRow(rowKey, page)

    suspend fun search(
        query: String,
        mediaType: String? = null,
        page: Int = 1,
    ): ApiResult<RequestMediaPage> = api.search(query, mediaType, page)

    suspend fun detail(mediaType: String, tmdbId: Int): ApiResult<RequestMediaDetail> =
        read({ fetchDetail(mediaType, tmdbId) }) { cache.store(it) }

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

    /** Get notified when a title someone else requested becomes available. */
    suspend fun follow(mediaType: String, tmdbId: Int): ApiResult<RequestState> =
        mutate({ api.follow(mediaType, tmdbId) }) { publishFollow(mediaType, tmdbId, following = true, state = it) }

    suspend fun unfollow(mediaType: String, tmdbId: Int): ApiResult<Unit> =
        mutate({ api.unfollow(mediaType, tmdbId) }) { publishFollow(mediaType, tmdbId, following = false, state = null) }

    /** Warms the first rows' details so opening them lands on a finished page. */
    fun prefetch(records: List<MediaRequest>) {
        cache.prefetch(records) { key -> fetchDetail(key.mediaType, key.tmdbId) }
    }

    fun reset() {
        generation++
        mineReadAt = null
        _mine.value = emptyList()
        _lastUpdate.value = null
        _lastFollow.value = null
        follows.clear()
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

    private fun publishFollow(mediaType: String, tmdbId: Int, following: Boolean, state: RequestState?) {
        val key = RequestDetailCache.Key(mediaType, tmdbId)
        val event = RequestFollowEvent(mediaType, tmdbId, following, state, ++eventSequence)
        follows[key] = event
        cache.patchRequestState(key) { it.applyingFollow(event) }
        _lastFollow.value = event
    }

    /**
     * The title's detail with the follows and unfollows that landed while the
     * read was out: one sent before a follow can answer after it, and must not
     * bring back the state the follow replaced.
     */
    private suspend fun fetchDetail(mediaType: String, tmdbId: Int): ApiResult<RequestMediaDetail> {
        val since = eventSequence
        return api.detail(mediaType, tmdbId).map { it.withFollowsAfter(since) }
    }

    private fun RequestMediaDetail.withFollowsAfter(since: Long): RequestMediaDetail =
        follows.values.filter { it.sequence > since }.fold(this) { detail, event ->
            val own = event.mediaType == detail.mediaType && event.tmdbId == detail.tmdbId
            detail.copy(
                request = if (own) detail.request.applyingFollow(event) else detail.request,
                recommendations = detail.recommendations.applyingFollowUpdate(event),
            )
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
