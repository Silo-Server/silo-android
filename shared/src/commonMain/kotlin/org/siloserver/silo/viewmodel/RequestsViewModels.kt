package org.siloserver.silo.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.model.request.MediaRequest
import org.siloserver.silo.model.request.MyRequestsBucket
import org.siloserver.silo.model.request.RequestActionCopy
import org.siloserver.silo.model.request.RequestActionHold
import org.siloserver.silo.model.request.RequestDiscoverySection
import org.siloserver.silo.model.request.RequestDisplayState
import org.siloserver.silo.model.request.RequestMediaResult
import org.siloserver.silo.model.request.RequestMediaType
import org.siloserver.silo.model.request.RequestMutationFailure
import org.siloserver.silo.model.request.RequestOutcome
import org.siloserver.silo.model.request.applyingFollowUpdate
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.errorMessage
import org.siloserver.silo.model.feature.RequestsFeatureStore
import org.siloserver.silo.repository.RequestEvent
import org.siloserver.silo.repository.RequestsRepository
import org.siloserver.silo.repository.applyingRequestEvent
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RequestsUiState(
    /** The first load, with nothing to show yet. */
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val hasLoaded: Boolean = false,
    val sections: List<RequestDiscoverySection> = emptyList(),
    /** The sections are the server's own Discover rows, each with a See all page. */
    val rowsBrowsable: Boolean = false,
    /** The signed-in user's requests, needs-attention first, newest first within each group. */
    val myRequests: List<MediaRequest> = emptyList(),
    val error: String? = null,
    val query: String = "",
    val searchResults: List<RequestMediaResult> = emptyList(),
    /** TMDB's total for the query, for the "N results" line. */
    val searchTotal: Int = 0,
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
) {
    /** True while the hub shows discover content (no active query). */
    val isShowingDiscover: Boolean get() = query.isBlank()

    /** Requests still moving, for the summary card. */
    val inProgressCount: Int
        get() = myRequests.count { MyRequestsBucket.of(RequestDisplayState.of(it)) == MyRequestsBucket.InMotion }

    /** Requests that need the user, for the summary card. */
    val needsAttentionCount: Int
        get() = myRequests.count { MyRequestsBucket.of(RequestDisplayState.of(it)) == MyRequestsBucket.NeedsAttention }

    val onTheWayCount: Int get() = myRequests.count { RequestDisplayState.of(it) == RequestDisplayState.OnTheWay }
    val pendingCount: Int get() = myRequests.count { RequestDisplayState.of(it) == RequestDisplayState.Pending }
}

/**
 * The Requests hub: TMDB search, the user's own requests strip, a status
 * summary, and the discover carousels. Fetches fresh on every visit: request
 * state changes server-side, and a stale "Pending" is worse than a placeholder.
 */
class RequestsViewModel(
    private val repository: RequestsRepository,
    /** Loads on creation; the TV page loads when it composes. */
    loadOnInit: Boolean = true,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RequestsUiState())
    val uiState: StateFlow<RequestsUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var loadGeneration = 0

    init {
        if (loadOnInit) load()
        viewModelScope.launch {
            repository.lastFollow.drop(1).filterNotNull().collect { event ->
                _uiState.update { state ->
                    state.copy(
                        searchResults = state.searchResults.applyingFollowUpdate(event),
                        sections = state.sections.map { it.copy(results = it.results.applyingFollowUpdate(event)) },
                    )
                }
            }
        }
        viewModelScope.launch {
            repository.lastUpdate.drop(1).filterNotNull().collect { applyRequestUpdate(it) }
        }
    }

    fun load() {
        viewModelScope.launch { fetch() }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            fetch()
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    /** Loads discover and the user's requests; returns whether both read. */
    suspend fun fetch(): Boolean {
        val generation = ++loadGeneration
        _uiState.update { it.copy(isLoading = it.sections.isEmpty() && it.myRequests.isEmpty(), error = null) }
        val discover = viewModelScope.async { repository.discover() }
        val mine = repository.refreshMine()
        val sections = discover.await()
        if (generation != loadGeneration) return false
        val succeeded = sections is ApiResult.Success && mine is ApiResult.Success
        if (succeeded) {
            val records = repository.mine.value
            val ordered = MyRequestsBucket.bucket(records).flatMap { it.second }
            val layout = (sections as ApiResult.Success).data
            _uiState.update {
                it.copy(
                    isLoading = false,
                    hasLoaded = true,
                    sections = discoverCarousels(layout),
                    rowsBrowsable = layout.rowOrder.isNotEmpty(),
                    myRequests = ordered,
                    error = null,
                )
            }
            repository.prefetch(ordered)
        } else {
            val failure: ApiResult<*> = if (sections is ApiResult.Success) mine else sections
            _uiState.update {
                // Keep prior content on a transient failure; only surface the
                // error when there is nothing to show instead.
                it.copy(
                    isLoading = false,
                    hasLoaded = true,
                    error = failure.errorMessage("Failed to load requests")
                        .takeIf { _ -> it.sections.isEmpty() && it.myRequests.isEmpty() },
                )
            }
        }
        return succeeded
    }

    /** Debounced TMDB search (300 ms), as the Apple hub does. */
    fun onQueryChanged(value: String) {
        searchJob?.cancel()
        _uiState.update { it.copy(query = value) }
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            _uiState.update { it.copy(searchResults = emptyList(), searchTotal = 0, isSearching = false, hasSearched = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            search(trimmed)
        }
    }

    /** Runs the current query now (the keyboard's search action). */
    fun submitSearch() {
        val trimmed = _uiState.value.query.trim()
        if (trimmed.isEmpty()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch { search(trimmed) }
    }

    private suspend fun search(query: String) {
        _uiState.update { it.copy(isSearching = true) }
        val result = repository.search(query = query)
        // A replacement search owns the state now.
        if (_uiState.value.query.trim() != query) return
        _uiState.update { state ->
            when (result) {
                is ApiResult.Success -> {
                    val results = result.data.results.filter { it.mediaType.isRequestableVideo() }
                    state.copy(
                        isSearching = false,
                        hasSearched = true,
                        searchResults = results,
                        searchTotal = maxOf(result.data.totalResults, results.size),
                    )
                }
                else -> state.copy(isSearching = false, hasSearched = true, searchResults = emptyList(), searchTotal = 0)
            }
        }
    }

    /** Patches every visible surface in place after a mutation anywhere in the app. */
    private fun applyRequestUpdate(event: RequestEvent) {
        val record = event.record
        _uiState.update { state ->
            val index = state.myRequests.indexOfFirst { it.id == record.id }
            val mine = when {
                event.removed || record.outcome == RequestOutcome.Cancelled -> state.myRequests.filterNot { it.id == record.id }
                index >= 0 -> state.myRequests.toMutableList().also { it[index] = record }
                record.outcome == RequestOutcome.Active -> listOf(record) + state.myRequests
                else -> state.myRequests
            }
            state.copy(
                searchResults = state.searchResults.applyingRequestEvent(event),
                sections = state.sections.map { it.copy(results = it.results.applyingRequestEvent(event)) },
                myRequests = mine,
            )
        }
    }

    override fun onCleared() {
        searchJob?.cancel()
        super.onCleared()
    }
}

private fun String.isRequestableVideo(): Boolean = this == RequestMediaType.Movie || this == RequestMediaType.Series

data class RequestSearchUiState(
    val query: String = "",
    val submittedQuery: String = "",
    val mediaType: String? = RequestMediaType.All,
    val isLoading: Boolean = false,
    val results: List<RequestMediaResult> = emptyList(),
    val page: Int = 1,
    val totalPages: Int = 1,
    val totalResults: Int = 0,
    val error: String? = null,
) {
    val hasSubmittedQuery: Boolean
        get() = submittedQuery.isNotBlank() && query.trim() == submittedQuery
}

/** The "Available to request" section of the app's global search. */
class RequestSearchViewModel(
    private val repository: RequestsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RequestSearchUiState())
    val uiState: StateFlow<RequestSearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            repository.lastUpdate.drop(1).filterNotNull().collect { event ->
                _uiState.update { it.copy(results = it.results.applyingRequestEvent(event)) }
            }
        }
        viewModelScope.launch {
            repository.lastFollow.drop(1).filterNotNull().collect { event ->
                _uiState.update { it.copy(results = it.results.applyingFollowUpdate(event)) }
            }
        }
    }

    fun onQueryChanged(value: String) {
        _uiState.update { current ->
            val trimmed = value.trim()
            current.copy(
                query = value,
                submittedQuery = current.submittedQuery.takeIf { it == trimmed }.orEmpty(),
                results = if (current.submittedQuery == trimmed) current.results else emptyList(),
                totalResults = if (current.submittedQuery == trimmed) current.totalResults else 0,
                totalPages = if (current.submittedQuery == trimmed) current.totalPages else 1,
                page = if (current.submittedQuery == trimmed) current.page else 1,
                error = null,
            )
        }
    }

    fun onMediaTypeChanged(value: String?) {
        _uiState.update { it.copy(mediaType = value, error = null) }
    }

    /**
     * Re-run the current search WITHOUT clearing what is on screen.
     *
     * [search] blanks results before refetching, which is right for a new query
     * and wrong for a refresh: a viewer returning from a request detail would
     * watch the row empty and refill, and anything relying on those cards
     * staying put — a focus restoration, most obviously — loses its target for
     * the duration. Returning is also exactly when the results ARE stale,
     * because creating a request in the detail changes the status this row
     * shows, so skipping the refresh is not an option either.
     */
    fun refreshInPlace() {
        if (_uiState.value.submittedQuery.isBlank()) return
        search(page = _uiState.value.page, preserveResults = true)
    }

    fun search(page: Int = 1, preserveResults: Boolean = false) {
        val submittedState = _uiState.value
        val query = submittedState.query.trim()
        val mediaType = submittedState.mediaType?.takeUnless { it == RequestMediaType.All }
        if (query.isBlank()) {
            searchJob?.cancel()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    submittedQuery = "",
                    results = emptyList(),
                    page = 1,
                    totalPages = 1,
                    totalResults = 0,
                    error = "Enter a search term.",
                )
            }
            return
        }

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    submittedQuery = query,
                    results = if (preserveResults) it.results else emptyList(),
                    page = page,
                    totalPages = if (preserveResults) it.totalPages else 1,
                    totalResults = if (preserveResults) it.totalResults else 0,
                    error = null,
                )
            }
            when (val result = repository.search(query = query, mediaType = mediaType, page = page)) {
                is ApiResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            submittedQuery = query,
                            results = result.data.results,
                            page = result.data.page,
                            totalPages = result.data.totalPages,
                            totalResults = result.data.totalResults,
                            error = null,
                        )
                    }
                }
                is ApiResult.Error, is ApiResult.NetworkError -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            submittedQuery = query,
                            error = result.errorMessage("Failed to search requests"),
                        )
                    }
                }
            }
        }
    }

    override fun onCleared() {
        searchJob?.cancel()
        super.onCleared()
    }
}

data class MyRequestsUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val hasLoaded: Boolean = false,
    /** Ordered, non-empty buckets, newest first within each. */
    val buckets: List<Pair<MyRequestsBucket, List<MediaRequest>>> = emptyList(),
    /** The request being cancelled; its row dims and takes no taps. */
    val cancellingId: String? = null,
    /** Requests whose cancel was sent without a usable answer; held until the request changes, or the hold lapses. */
    val unconfirmedCancelIds: Set<String> = emptySet(),
    /** Inline message for a failed cancel or removal; cleared on the next action. */
    val actionErrorMessage: String? = null,
    /** The server takes removals of failed requests (`remove_failed_supported`). */
    val removeFailedSupported: Boolean = false,
    /** The request being removed; its row dims and takes no taps. */
    val removingId: String? = null,
    /** A confirmation to show once, such as "Request removed". */
    val notice: RequestNotice? = null,
    val error: String? = null,
) {
    val requests: List<MediaRequest> get() = buckets.flatMap { it.second }
    val isEmpty: Boolean get() = hasLoaded && buckets.isEmpty()

    /** Whether [record] may offer Cancel: pending, and no cancel of it held. */
    fun canCancel(record: MediaRequest): Boolean =
        RequestDisplayState.of(record).isCancelable && record.id !in unconfirmedCancelIds && cancellingId == null

    /** Whether [record] may offer Remove: a failed request that delivered nothing, on a server that takes it. */
    fun canRemove(record: MediaRequest): Boolean =
        removeFailedSupported && record.isRemovableFailure && removingId == null
}

/** A one-time confirmation; [sequence] makes a repeat of the same text a new notice. */
data class RequestNotice(val message: String, val sequence: Long)

class MyRequestsViewModel(
    private val repository: RequestsRepository,
    /** Loads on creation; the TV page only uses this model to cancel. */
    loadOnInit: Boolean = true,
    private val holdLifetime: Duration = RequestActionHold.Lifetime,
    private val timeSource: TimeSource = TimeSource.Monotonic,
    /** Gates Remove on `remove_failed_supported`; without it Remove is never offered. */
    private val featureStore: RequestsFeatureStore? = null,
) : ViewModel() {

    private var loadGeneration = 0
    private var noticeSequence = 0L
    private val cancelHolds = mutableMapOf<String, RequestActionHold>()
    private val _uiState = MutableStateFlow(MyRequestsUiState())
    val uiState: StateFlow<MyRequestsUiState> = _uiState.asStateFlow()
    private var reloadJob: Job? = null

    init {
        if (loadOnInit) load()
        featureStore?.let { store ->
            viewModelScope.launch {
                store.removeFailedSupported.collect { supported -> _uiState.update { it.copy(removeFailedSupported = supported) } }
            }
        }
        viewModelScope.launch {
            // A mutation elsewhere (detail submit) while this list is mounted:
            // the list is short, so a full refetch is the simplest correct answer.
            repository.lastUpdate.drop(1).filterNotNull().collect {
                val state = _uiState.value
                // This model's own cancel or removal re-reads when it finishes.
                if (!state.hasLoaded || state.cancellingId != null || state.removingId != null) return@collect
                reloadJob?.cancel()
                reloadJob = viewModelScope.launch { fetch() }
            }
        }
    }

    fun load() {
        viewModelScope.launch { fetch() }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true) }
            fetch()
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    /** Reads the list; returns whether the read succeeded. */
    suspend fun fetch(): Boolean {
        val generation = ++loadGeneration
        _uiState.update { it.copy(isLoading = it.buckets.isEmpty(), error = null) }
        val result = repository.refreshMine()
        if (generation != loadGeneration) return false
        return when (result) {
            is ApiResult.Success -> {
                val buckets = MyRequestsBucket.bucket(repository.mine.value)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        hasLoaded = true,
                        buckets = buckets,
                        error = null,
                    )
                }
                settleCancelHolds()
                repository.prefetch(buckets.flatMap { it.second })
                true
            }
            is ApiResult.Error, is ApiResult.NetworkError -> {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = result.errorMessage("Failed to load your requests").takeIf { _ -> it.buckets.isEmpty() },
                    )
                }
                false
            }
        }
    }

    /**
     * Cancels one of the user's requests. [refresh] re-reads the list that shows
     * the row when it isn't this model's (the TV hub) and returns whether the
     * read succeeded; it runs only to settle an uncertain cancel.
     */
    fun cancel(record: MediaRequest, refresh: (suspend () -> Boolean)? = null) {
        val state = _uiState.value
        if (state.cancellingId != null || record.id in state.unconfirmedCancelIds) return
        _uiState.update { it.copy(cancellingId = record.id, actionErrorMessage = null) }
        viewModelScope.launch {
            val result = repository.cancel(record.id)
            when {
                result is ApiResult.Success -> {
                    _uiState.update { it.copy(cancellingId = null) }
                    if (refresh == null) fetch()
                }
                RequestMutationFailure.isUncertain(result) -> {
                    // Never resend: hold Cancel until the request changes, or the hold lapses.
                    val reread: suspend () -> Unit = {
                        if (refresh == null) {
                            fetch()
                        } else if (refresh()) {
                            settleCancelHolds()
                        }
                    }
                    holdCancel(record, reread)
                    _uiState.update { it.copy(cancellingId = null, actionErrorMessage = RequestActionCopy.UnconfirmedCancel) }
                    reread()
                }
                else -> _uiState.update {
                    it.copy(cancellingId = null, actionErrorMessage = RequestActionCopy.failure(result, "Couldn't cancel the request"))
                }
            }
        }
    }

    /**
     * Removes one of the user's failed requests, after the screen's
     * confirmation. This model's list is read again whatever the answer, so
     * the row shows what happened: gone, or retried or completed since it
     * loaded (409). [refresh] re-reads the list that shows the row when it
     * isn't this model's (the TV hub), and runs only when the removal isn't
     * confirmed: a confirmed one has already left every list the repository
     * feeds. Nothing is held after an uncertain answer: a resend of a removal
     * that landed answers 404 and changes nothing.
     */
    fun removeFailed(record: MediaRequest, refresh: (suspend () -> Boolean)? = null) {
        if (!_uiState.value.canRemove(record)) return
        _uiState.update { it.copy(removingId = record.id, actionErrorMessage = null) }
        viewModelScope.launch {
            val result = repository.removeFailed(record)
            // A confirmed removal has already left every list the repository
            // feeds (the TV hub included); anything else re-reads to show what happened.
            if (refresh == null) fetch() else if (result !is ApiResult.Success) refresh()
            val gone = repository.mine.value.none { it.id == record.id }
            _uiState.update { current ->
                val settled = current.copy(removingId = null)
                when {
                    // The repository has dropped it already; a failed re-read must not bring the row back.
                    result is ApiResult.Success -> settled.copy(
                        buckets = MyRequestsBucket.bucket(repository.mine.value),
                        notice = RequestNotice(RequestActionCopy.Removed, ++noticeSequence),
                    )
                    // Removed elsewhere since the list loaded: the re-read has dropped it.
                    result is ApiResult.Error && result.code == 404 -> settled
                    // Requests turned off also answers 409, as capability_disabled.
                    result is ApiResult.Error && result.code == 409 && result.error == "conflict" ->
                        settled.copy(actionErrorMessage = RequestActionCopy.RemoveConflict)
                    RequestMutationFailure.isUncertain(result) && gone ->
                        settled.copy(notice = RequestNotice(RequestActionCopy.Removed, ++noticeSequence))
                    RequestMutationFailure.isUncertain(result) -> settled.copy(actionErrorMessage = RequestActionCopy.UnconfirmedRemove)
                    else -> settled.copy(actionErrorMessage = RequestActionCopy.failure(result, "Couldn't remove the request"))
                }
            }
            if (RequestActionCopy.isCapabilityOff(result)) featureStore?.refresh()
        }
    }

    /**
     * Holds Cancel on [record], and ends the hold when its lifetime runs out
     * even if no read settles it. A newer hold for the same request keeps its own clock.
     */
    private fun holdCancel(record: MediaRequest, reread: suspend () -> Unit) {
        val hold = RequestActionHold(record, timeSource, holdLifetime)
        cancelHolds[record.id] = hold
        _uiState.update { it.copy(unconfirmedCancelIds = cancelHolds.keys.toSet()) }
        viewModelScope.launch {
            delay(holdLifetime)
            if (cancelHolds[record.id] !== hold) return@launch
            cancelHolds.remove(record.id)
            publishCancelHolds()
            reread()
        }
    }

    /** Releases the holds the last read of the user's requests settles; an unchanged request keeps its hold. */
    private fun settleCancelHolds() {
        if (cancelHolds.isEmpty()) return
        val records = repository.mine.value
        cancelHolds.entries.removeAll { (id, hold) -> hold.isSettled(records.firstOrNull { it.id == id }) }
        publishCancelHolds()
    }

    private fun publishCancelHolds() {
        _uiState.update {
            it.copy(
                unconfirmedCancelIds = cancelHolds.keys.toSet(),
                actionErrorMessage = it.actionErrorMessage.takeUnless { m ->
                    cancelHolds.isEmpty() && m == RequestActionCopy.UnconfirmedCancel
                },
            )
        }
    }
}
