package org.siloserver.silo.android.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.navigation.MediaMode
import org.siloserver.silo.model.navigation.mobileMediaModeForLibraryType
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.errorMessage
import org.siloserver.silo.repository.CatalogRepository
import kotlinx.coroutines.FlowPreview
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

private val MobileSearchMediaType.isClientFiltered: Boolean
    get() = this != MobileSearchMediaType.All

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
    val hasMore: Boolean = false,
    val total: Int = 0,
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

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val _queryFlow = MutableStateFlow("")

    private val pageSize = 60

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
                        _uiState.update {
                            it.copy(
                                query = query,
                                results = emptyList(),
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
        _uiState.update {
            it.copy(
                query = "",
                isSearching = false,
                results = emptyList(),
                hasMore = false,
                total = 0,
                error = null,
                hasSearched = false,
                nextOffset = 0,
            )
        }
        _queryFlow.value = ""
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
        if (current.isSearching || !current.hasMore || current.query.isBlank()) return
        viewModelScope.launch {
            performSearch(current.query, reset = false)
        }
    }

    private suspend fun performSearch(query: String, reset: Boolean) {
        val currentState = _uiState.value
        val requestedMediaType = currentState.mediaType
        var offset = if (reset) 0 else currentState.nextOffset
        var pagesFetched = 0
        val visibleItems = mutableListOf<BrowseItem>()
        var hasMore = false
        var total = 0

        _uiState.update { it.copy(isSearching = true, error = null) }

        while (true) {
            val result = catalogRepository.browse(
                source = "query",
                query = query,
                mediaType = requestedMediaType.wire,
                offset = offset,
                limit = pageSize,
            )
            val latest = _uiState.value
            if (latest.query != query || latest.mediaType != requestedMediaType) return

            when (result) {
                is ApiResult.Success -> {
                    val response = result.data
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

                    _uiState.update {
                        val nextResults = if (reset) visibleItems else it.results + visibleItems
                        it.copy(
                            isSearching = false,
                            results = nextResults,
                            hasMore = hasMore,
                            total = if (requestedMediaType.isClientFiltered) nextResults.size else total,
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
    }
}
