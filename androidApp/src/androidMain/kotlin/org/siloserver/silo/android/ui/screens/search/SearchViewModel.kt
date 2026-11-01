package org.siloserver.silo.android.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.CatalogBoxSet
import org.siloserver.silo.model.catalog.Person
import org.siloserver.silo.model.navigation.MediaMode
import org.siloserver.silo.model.navigation.mobileMediaModeForLibraryType
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.apiv2.CatalogContinuationV2
import org.siloserver.silo.network.errorMessage
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.searchPeopleForQuery
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import org.siloserver.silo.repository.BOX_SET_SEARCH_GRACE_MILLIS
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Media type filter for mobile search. Video intentionally maps to null
 * until the server supports a single movie-or-series query filter.
 */
enum class MobileSearchMediaType(val label: String, val wire: String?) {
    All("All", null),
    Video("Video", null),
    Audio("Audio", "audio"),
    Reading("Reading", null);

    companion object {
        fun fromRouteValue(value: String?): MobileSearchMediaType? =
            when (value?.trim()?.lowercase()) {
                "video" -> Video
                "audio" -> Audio
                "reading" -> Reading
                "all" -> All
                else -> null
            }
    }
}

internal fun MobileSearchMediaType.filterResults(items: List<BrowseItem>): List<BrowseItem> =
    when (this) {
        MobileSearchMediaType.Video -> items.filter {
            mobileMediaModeForLibraryType(it.type) == MediaMode.Video
        }
        MobileSearchMediaType.Audio -> items.filter {
            mobileMediaModeForLibraryType(it.type) == MediaMode.Audio
        }
        MobileSearchMediaType.Reading -> items.filter {
            mobileMediaModeForLibraryType(it.type) == MediaMode.Reading
        }
        else -> items
    }

/**
 * People-search scopes for each filter. Audio people are audiobook authors and
 * narrators; Reading covers both ebooks and manga, which the server scopes
 * separately.
 */
internal val MobileSearchMediaType.peopleMediaScopes: List<String?>
    get() = when (this) {
        MobileSearchMediaType.All -> listOf(null)
        MobileSearchMediaType.Video -> listOf("video")
        MobileSearchMediaType.Audio -> listOf("audiobook")
        MobileSearchMediaType.Reading -> listOf("ebook", "manga")
    }

private val MobileSearchMediaType.isClientFiltered: Boolean
    get() = this != MobileSearchMediaType.All

/** Box sets are movies; filters without movies leave them out. */
internal val MobileSearchMediaType.includesBoxSets: Boolean
    get() = this == MobileSearchMediaType.All || this == MobileSearchMediaType.Video

internal const val MAX_FILTERED_SEARCH_PAGES = 5

/**
 * Decides whether a client-filtered search should silently fetch the next
 * page because no visible results have accumulated yet. Capped at
 * [MAX_FILTERED_SEARCH_PAGES] pages per search so a query matching only
 * non-literary items cannot serially crawl the entire catalog; `hasMore`
 * stays true in the UI state, so [SearchViewModel.loadMore] continues from
 * [SearchUiState.nextOffset] on the next scroll-to-end.
 */
internal fun shouldAutoAdvanceFilteredSearchPage(
    isClientFiltered: Boolean,
    visibleItemCount: Int,
    hasMore: Boolean,
    pagesFetched: Int,
): Boolean =
    isClientFiltered &&
        visibleItemCount == 0 &&
        hasMore &&
        pagesFetched < MAX_FILTERED_SEARCH_PAGES

/**
 * UI state for the search screen.
 */
data class SearchUiState(
    val query: String = "",
    val mediaType: MobileSearchMediaType = MobileSearchMediaType.All,
    val availableMediaTypes: List<MobileSearchMediaType> = MobileSearchMediaType.entries.toList(),
    val isSearching: Boolean = false,
    val results: List<BrowseItem> = emptyList(),
    /** Box sets matching the query, shown first, ahead of the movies themselves. */
    val boxSets: List<CatalogBoxSet> = emptyList(),
    /** Cast and crew matching the query, shown above the title results. */
    val people: List<Person> = emptyList(),
    /** True until the current query's people lookup answers. */
    val isLoadingPeople: Boolean = false,
    val hasMore: Boolean = false,
    val total: Int = 0,
    val totalExact: Boolean = false,
    val searchDiagnostics: org.siloserver.silo.network.apiv2.CatalogSearchDiagnosticsV2? = null,
    val error: String? = null,
    val hasSearched: Boolean = false,
    val nextOffset: Int = 0,
)

/**
 * ViewModel for the search screen.
 *
 * Performs real-time search with 300ms debounce as the user types.
 * Results are loaded in a grid with the same [BrowseItem] model as browse.
 */
@OptIn(FlowPreview::class)
class SearchViewModel(
    private val catalogRepository: CatalogRepository,
) : ViewModel() {
    /** Access changes this ViewModel has applied, kept while its screen is away. */
    val accessChanges = org.siloserver.silo.network.AccessChangeCursor()

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val _queryFlow = MutableStateFlow("")

    private var continuation: CatalogContinuationV2? = null
    private var peopleJob: Job? = null
    private var searchGeneration = 0L
    private val pageSize = 60

    /**
     * Some library this profile sees has box sets on. Servers without box
     * sets report none, so search never asks them for any.
     */
    private var boxSetsAvailable = false

    /** The query the newest search ran for, so [setBoxSetsAvailable] re-runs only a search already made. */
    private var lastSearchedQuery: String? = null

    /**
     * A voice query that [onVoiceQuery] already searched for immediately. The
     * debounced collector consumes and skips it once so the same query is not
     * requested twice.
     */
    private var pendingVoiceQuery: String? = null

    init {
        // Debounce search queries
        viewModelScope.launch {
            _queryFlow
                .debounce(300)
                .distinctUntilChanged()
                .collectLatest { query ->
                    if (query.isNotBlank() && query == pendingVoiceQuery) {
                        pendingVoiceQuery = null
                        return@collectLatest
                    }
                    if (query.isBlank()) {
                        peopleJob?.cancel()
                        _uiState.update {
                            it.copy(
                                query = query,
                                results = emptyList(),
                                boxSets = emptyList(),
                                people = emptyList(),
                                isLoadingPeople = false,
                                hasMore = false,
                                total = 0,
                                isSearching = false,
                                error = null,
                                hasSearched = false,
                                nextOffset = 0,
                            )
                        }
                    } else {
                        performSearch(query, reset = true)
                    }
                }
        }
    }

    /**
     * Called as the user types in the search field.
     */
    fun onQueryChanged(query: String) {
        // Typing supersedes any voice query still waiting to be skipped by the
        // debounce, so a later identical keystroke run is searched normally.
        pendingVoiceQuery = null
        _uiState.update { it.copy(query = query) }
        _queryFlow.value = query
    }

    /**
     * Accepts a query dictated through speech recognition. Unlike typing, the
     * user has already committed to the phrase, so this searches immediately
     * instead of waiting out the debounce window.
     */
    fun onVoiceQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return
        pendingVoiceQuery = trimmed
        _uiState.update { it.copy(query = trimmed) }
        _queryFlow.value = trimmed
        viewModelScope.launch { performSearch(trimmed, reset = true) }
    }

    /**
     * Re-runs the current query after a failure.
     */
    fun retry() {
        val query = _uiState.value.query
        if (query.isBlank()) return
        viewModelScope.launch { performSearch(query, reset = true) }
    }

    /**
     * Clears the search query and results.
     */
    fun clearSearch() {
        pendingVoiceQuery = null
        peopleJob?.cancel()
        _uiState.update {
            it.copy(
                query = "",
                isSearching = false,
                results = emptyList(),
                boxSets = emptyList(),
                people = emptyList(),
                isLoadingPeople = false,
                hasMore = false,
                total = 0,
                error = null,
                hasSearched = false,
                nextOffset = 0,
            )
        }
        _queryFlow.value = ""
    }

    /** Whether any library this profile sees has box sets on; see [boxSetsAvailable]. */
    fun setBoxSetsAvailable(available: Boolean) {
        if (available == boxSetsAvailable) return
        boxSetsAvailable = available
        // A query still waiting out the debounce gets box sets when it runs.
        val query = _uiState.value.query
        if (query.isNotBlank() && query == lastSearchedQuery) viewModelScope.launch { performSearch(query, reset = true) }
    }

    fun setAvailableModes(modes: List<MediaMode>) {
        val types = buildList {
            add(MobileSearchMediaType.All)
            if (MediaMode.Video in modes) add(MobileSearchMediaType.Video)
            if (MediaMode.Audio in modes) add(MobileSearchMediaType.Audio)
            if (MediaMode.Reading in modes) add(MobileSearchMediaType.Reading)
        }
        val current = _uiState.value
        val nextMediaType = current.mediaType.takeIf { it in types } ?: MobileSearchMediaType.All
        _uiState.update {
            it.copy(
                availableMediaTypes = types,
                mediaType = nextMediaType,
            )
        }
        if (nextMediaType != current.mediaType && current.query.isNotBlank()) {
            viewModelScope.launch { performSearch(current.query, reset = true) }
        }
    }

    fun onMediaTypeChanged(mediaType: MobileSearchMediaType) {
        val current = _uiState.value
        if (mediaType == current.mediaType || mediaType !in current.availableMediaTypes) return
        _uiState.update { it.copy(mediaType = mediaType) }
        if (current.query.isNotBlank()) {
            viewModelScope.launch { performSearch(current.query, reset = true) }
        }
    }

    fun selectInitialMediaType(mediaType: MobileSearchMediaType?) {
        if (mediaType == null) return
        val current = _uiState.value
        if (current.query.isNotBlank()) return
        if (mediaType !in current.availableMediaTypes) return
        _uiState.update { it.copy(mediaType = mediaType) }
    }

    /**
     * Loads more results for the current query.
     */
    fun loadMore() {
        val current = _uiState.value
        if (current.error != null || current.isSearching || !current.hasMore || current.query.isBlank()) return
        viewModelScope.launch {
            performSearch(current.query, reset = false)
        }
    }

    /**
     * Looks up people alongside the title search. It runs on its own so a slow
     * or failed people lookup never holds back or fails the title results.
     * The previous query's people are cleared first so they never sit beside
     * the new query's titles.
     */
    private fun searchPeople(query: String, mediaType: MobileSearchMediaType) {
        peopleJob?.cancel()
        _uiState.update { it.copy(people = emptyList(), isLoadingPeople = true) }
        peopleJob = viewModelScope.launch {
            val people = catalogRepository.searchPeopleForQuery(query, mediaType.peopleMediaScopes)
            // Cancellation surfaces as an empty answer, not an exception.
            if (!isActive) return@launch
            val latest = _uiState.value
            if (latest.query != query || latest.mediaType != mediaType) return@launch
            _uiState.update { it.copy(people = people, isLoadingPeople = false) }
        }
    }

    private suspend fun performSearch(query: String, reset: Boolean) {
        // A new search makes every earlier one's answer obsolete, even for the
        // same query: a re-run after an access change must not be overwritten
        // by the older request's results. Loading more continues the newest.
        val generation = if (reset) ++searchGeneration else searchGeneration
        if (reset) lastSearchedQuery = query
        val currentState = _uiState.value
        val requestedMediaType = currentState.mediaType
        var offset = if (reset) 0 else currentState.nextOffset
        var cursor = if (reset) null else continuation
        var pagesFetched = 0
        val visibleItems = mutableListOf<BrowseItem>()
        var hasMore = false
        var total = 0

        _uiState.update { it.copy(isSearching = true, error = null) }
        if (reset) searchPeople(query, requestedMediaType)
        // Box sets answer beside the first page and land with it, so the grid
        // does not shift under the viewer when they arrive.
        val boxSetsRequest = if (reset && boxSetsAvailable && requestedMediaType.includesBoxSets) {
            viewModelScope.async { catalogRepository.searchBoxSets(query) }
        } else {
            null
        }

        // The box set request ends with the search, whether a newer query or a
        // cleared field replaces it, it fails, or it answers.
        try {
            while (true) {
                val result = catalogRepository.browse(
                    source = "query",
                    query = query,
                    mediaType = requestedMediaType.wire,
                    continuation = cursor,
                    limit = pageSize,
                )
                val latest = _uiState.value
                if (generation != searchGeneration || latest.query != query || latest.mediaType != requestedMediaType) return

                when (result) {
                    is ApiResult.Success -> {
                        val response = result.data
                        cursor = response.continuation
                        val rawCount = response.items.size
                        val pageVisibleItems = requestedMediaType.filterResults(response.items)

                        pagesFetched += 1
                        visibleItems += pageVisibleItems
                        offset += rawCount
                        hasMore = response.hasMore && rawCount > 0
                        total = response.total

                        val shouldAdvanceFilteredPage = shouldAutoAdvanceFilteredSearchPage(
                            isClientFiltered = requestedMediaType.isClientFiltered,
                            visibleItemCount = visibleItems.size,
                            hasMore = hasMore,
                            pagesFetched = pagesFetched,
                        )
                        if (shouldAdvanceFilteredPage) continue

                        // Box sets only add to the titles, so a slow answer never holds them
                        // back for long: past the grace period this query shows none.
                        val boxSets = withTimeoutOrNull(BOX_SET_SEARCH_GRACE_MILLIS) { boxSetsRequest?.await() }.orEmpty()
                        val current = _uiState.value
                        if (generation != searchGeneration || current.query != query || current.mediaType != requestedMediaType) return
                        continuation = cursor
                        _uiState.update {
                            val nextResults = if (reset) visibleItems else it.results + visibleItems
                            it.copy(
                                isSearching = false,
                                results = nextResults,
                                boxSets = if (reset) boxSets else it.boxSets,
                                hasMore = hasMore,
                                total = if (requestedMediaType.isClientFiltered) nextResults.size else total,
                                totalExact = if (requestedMediaType.isClientFiltered) !hasMore else response.totalExact == true,
                                searchDiagnostics = response.searchDiagnostics,
                                error = null,
                                hasSearched = true,
                                nextOffset = offset,
                            )
                        }
                        return
                    }
                    is ApiResult.Error -> {
                        _uiState.update {
                            it.copy(
                                isSearching = false,
                                error = result.message.ifBlank { "Search failed" },
                                hasSearched = true,
                            )
                        }
                        return
                    }
                    is ApiResult.NetworkError -> {
                        _uiState.update {
                            it.copy(
                                isSearching = false,
                                error = result.errorMessage("Search failed"),
                                hasSearched = true,
                            )
                        }
                        return
                    }
                }
            }
        } finally {
            boxSetsRequest?.cancel()
        }
    }
}
