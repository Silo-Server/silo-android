package org.siloserver.silo.repository

import org.siloserver.silo.model.request.CreateMediaRequest
import org.siloserver.silo.model.request.MediaRequest
import org.siloserver.silo.model.request.RequestFollowEvent
import org.siloserver.silo.model.request.RequestMediaDetail
import org.siloserver.silo.model.request.RequestMediaPage
import org.siloserver.silo.model.request.RequestMediaResult
import org.siloserver.silo.model.request.RequestState
import org.siloserver.silo.model.request.RequestsDiscoverResponse
import org.siloserver.silo.model.request.RequestsFeatureStatus
import org.siloserver.silo.model.request.applyingFollow
import org.siloserver.silo.model.request.applyingFollowUpdate
import org.siloserver.silo.model.request.applyingRemoval
import org.siloserver.silo.model.request.applyingRequestUpdate
import org.siloserver.silo.model.request.RequestDiscoverySection
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.RequestsApi
import org.siloserver.silo.network.map
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * One request mutation, broadcast so every mounted request surface can patch
 * its own items in place. [sequence] makes a repeat of the same record a new event.
 * [removed] means the server deleted [record]: it leaves every list.
 */
data class RequestEvent(val record: MediaRequest, val sequence: Long, val removed: Boolean = false)

/** These results after [event]: a removed request leaves its title requestable again. */
fun List<RequestMediaResult>.applyingRequestEvent(event: RequestEvent): List<RequestMediaResult> =
    if (event.removed) applyingRemoval(event.record) else applyingRequestUpdate(event.record)

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

    /** Bumped by each of this client's own creates, cancels and removals as they land. */
    private var ownMutations = 0L

    /** Bumped by [reset]: anything still running from the previous session drops its result. */
    @kotlin.concurrent.Volatile
    private var generation = 0

    /** When [mine] last held a complete, unfiltered read; null until one lands in this session. */
    private var mineReadAt: TimeMark? = null

    private val _lastUpdate = MutableStateFlow<RequestEvent?>(null)
    /** The most recent create, cancel or removal of the signed-in user's own request. */
    val lastUpdate: StateFlow<RequestEvent?> = _lastUpdate.asStateFlow()

    private val _lastFollow = MutableStateFlow<RequestFollowEvent?>(null)
    /** The most recent follow or unfollow, so cards and pages showing the title agree. */
    val lastFollow: StateFlow<RequestFollowEvent?> = _lastFollow.asStateFlow()

    /** Each title's latest follow or unfollow this session, for reads that were out when it landed. */
    private val follows = mutableMapOf<RequestDetailCache.Key, RequestFollowEvent>()

    private val _capabilityRefusals = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /**
     * Emits when the server refuses a requests call because requests are off
     * for this profile ([RefusedAsRequestsOff]), so the capability can be read
     * again. Never emitted for [status], the probe itself.
     */
    val capabilityRefusals: SharedFlow<Unit> = _capabilityRefusals.asSharedFlow()

    /** The capability probe. Not watched for refusals: it is what answers them. */
    suspend fun status(): ApiResult<RequestsFeatureStatus> = api.status()

    suspend fun discover(): ApiResult<RequestsDiscoverResponse> = watched { api.discover() }

    suspend fun discoverRow(rowKey: String, page: Int = 1): ApiResult<RequestDiscoverySection> =
        watched { api.discoverRow(rowKey, page) }

    suspend fun search(
        query: String,
        mediaType: String? = null,
        page: Int = 1,
    ): ApiResult<RequestMediaPage> = watched { api.search(query, mediaType, page) }

    suspend fun detail(mediaType: String, tmdbId: Int): ApiResult<RequestMediaDetail> =
        read({ fetchDetail(mediaType, tmdbId) }) { cache.store(it) }

    suspend fun get(id: String): ApiResult<MediaRequest> = watched { api.get(id) }

    suspend fun refreshMine(
        status: String? = null,
        outcome: String? = null,
        limit: Int? = null,
        offset: Int? = null,
    ): ApiResult<Unit> {
        val mutationsBefore = ownMutations
        val result = read({ watched { api.mine(status, outcome, limit, offset) } }) { response ->
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
        mutate({ watched { api.create(request) } }, ::publish)

    suspend fun cancel(id: String): ApiResult<MediaRequest> =
        mutate({ watched { api.cancel(id) } }, ::publish)

    /**
     * Deletes one of the user's failed requests that delivered nothing
     * ([MediaRequest.isRemovableFailure]). A 404 means it is already gone
     * (removed on another device since the list loaded), so it leaves the lists too.
     */
    suspend fun removeFailed(record: MediaRequest): ApiResult<Unit> {
        val session = generation
        val result = watched { api.removeFailed(record.id) }
        val gone = result is ApiResult.Success || (result is ApiResult.Error && result.code == 404)
        if (session == generation && gone) publishRemoval(record)
        return result
    }

    /** Get notified when a title someone else requested becomes available. */
    suspend fun follow(mediaType: String, tmdbId: Int): ApiResult<RequestState> =
        mutate({ watched { api.follow(mediaType, tmdbId) } }) { publishFollow(mediaType, tmdbId, following = true, state = it) }

    suspend fun unfollow(mediaType: String, tmdbId: Int): ApiResult<Unit> =
        mutate({ watched { api.unfollow(mediaType, tmdbId) } }) { publishFollow(mediaType, tmdbId, following = false, state = null) }

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

    private fun publishRemoval(record: MediaRequest) {
        ownMutations++
        _mine.update { list -> list.filterNot { it.id == record.id } }
        cache.removeOwnRecord(record)
        _lastUpdate.value = RequestEvent(record, ++eventSequence, removed = true)
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
        return watched { api.detail(mediaType, tmdbId) }.map { it.withFollowsAfter(since) }
    }

    /**
     * Runs one requests call and reports, through [capabilityRefusals], a
     * server refusal that means requests are off for this profile. The caller
     * still gets the result unchanged. A refusal that answers after a [reset]
     * belongs to the previous session and isn't reported.
     */
    private inline fun <T> watched(call: () -> ApiResult<T>): ApiResult<T> {
        val session = generation
        val result = call()
        if (session == generation && isRefusedAsRequestsOff(result)) _capabilityRefusals.tryEmit(Unit)
        return result
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

        /**
         * Problem codes the server answers a requests call with when requests
         * are off for this profile: turned off or not set up on the server, or
         * the account blocked from requesting. Matches the Apple clients.
         */
        internal val RefusedAsRequestsOff = setOf(
            "capability_disabled",
            "capability_not_configured",
            "capability_unsupported",
            "permission_denied",
        )

        /**
         * A server answer only (status 0 is a client-side stand-in, such as the
         * API's default for a call this build can't make, or a session change).
         */
        internal fun isRefusedAsRequestsOff(result: ApiResult<*>): Boolean =
            result is ApiResult.Error && result.code != 0 && result.error in RefusedAsRequestsOff
    }
}
