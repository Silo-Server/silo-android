package org.siloserver.silo.tv.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.isAudiobookItemType
import org.siloserver.silo.model.navigation.isAudiobookLikeLibraryType
import org.siloserver.silo.model.navigation.tvMediaModeCapabilities
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.tv.ui.util.visibleOnTv
import org.siloserver.silo.tv.data.preferences.TvLibraryScopeStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Media type filter in the search header. */
enum class TvSearchMediaType(val label: String, val wire: String?) {
    All("All", null),
    Movies("Movies", "movie"),
    Series("Series", "series"),
    Audiobooks("Audiobooks", "audiobook"),
}

@OptIn(FlowPreview::class)
class TvSearchViewModel(
    private val catalogRepository: CatalogRepository,
    private val personalDataRepository: PersonalDataRepository,
    private val libraryScopeStore: TvLibraryScopeStore,
) : ViewModel() {

    data class UiState(
        val query: String = "",
        val mediaType: TvSearchMediaType = TvSearchMediaType.All,
        /** Media-type chips to show, derived from the user's libraries. */
        val availableMediaTypes: List<TvSearchMediaType> =
            TvSearchMediaType.entries.filterNot { it == TvSearchMediaType.Audiobooks },
        val items: List<BrowseItem> = emptyList(),
        val total: Int = 0,
        val hasMore: Boolean = false,
        val isLoading: Boolean = false,
        val isLoadingMore: Boolean = false,
        val error: String? = null,
        val rawResultCount: Int = 0,
        val showAudiobooks: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init { loadAvailableMediaTypes() }

    /**
     * Derive the visible media-type chips from the user's libraries (parity with
     * the phone search): Movies/Series need a video library, Audiobooks need an
     * audio library. "All" is always present. If the active filter becomes
     * unavailable it falls back to All.
     */
    private fun loadAvailableMediaTypes() {
        viewModelScope.launch {
            val libraries = when (val result = personalDataRepository.listUserLibraries()) {
                is ApiResult.Success -> result.data
                else -> return@launch
            }
            val caps = libraries.tvMediaModeCapabilities()
            val showAudiobooks = libraryScopeStore.getShowAudiobooksTab()
            // The "Audiobooks" chip sends type=audiobook, so gate it on an
            // actual audiobook-like library — hasAudio also covers music, which
            // wouldn't match the audiobook filter.
            val hasAudiobooks = libraries.any { isAudiobookLikeLibraryType(it.type) }
            val types = buildList {
                add(TvSearchMediaType.All)
                if (caps.hasVideo) {
                    add(TvSearchMediaType.Movies)
                    add(TvSearchMediaType.Series)
                }
                if (showAudiobooks && hasAudiobooks) add(TvSearchMediaType.Audiobooks)
            }
            val current = _uiState.value
            val nextMediaType = if (current.mediaType in types) current.mediaType else TvSearchMediaType.All
            _uiState.update {
                it.copy(
                    availableMediaTypes = types,
                    mediaType = nextMediaType,
                    showAudiobooks = showAudiobooks,
                )
            }
            // If the active filter was forced to change while a query is live,
            // re-run so results aren't left stale under the old filter.
            if (
                (nextMediaType != current.mediaType || showAudiobooks != current.showAudiobooks) &&
                current.query.isNotBlank()
            ) {
                searchGeneration += 1
                searchJob?.cancel()
                loadMoreJob?.cancel()
                searchJob = viewModelScope.launch { runSearchInternal(reset = true) }
            }
        }
    }

    private var searchJob: Job? = null
    private var loadMoreJob: Job? = null
    private val pageSize = 40
    private val debounceMs = 300L

    // Search-generation token. Every query/filter edit bumps [searchGeneration];
    // a reset search stamps the current generation onto the results it produces
    // ([resultsGeneration]). A load-more only runs — and only appends — while the
    // accumulated results still belong to the current generation, so a page
    // fetched for a query the user has already changed can never mix into the new
    // query's results (it sent the new query at the old query's offset otherwise).
    private var searchGeneration = 0
    private var resultsGeneration = 0

    fun onQueryChanged(query: String) {
        searchGeneration += 1
        _uiState.update { it.copy(query = query) }
        searchJob?.cancel()
        loadMoreJob?.cancel()
        if (query.isBlank()) {
            _uiState.update {
                it.copy(
                    items = emptyList(),
                    total = 0,
                    hasMore = false,
                    isLoading = false,
                    isLoadingMore = false,
                    error = null,
                    rawResultCount = 0,
                )
            }
            return
        }
        searchJob = viewModelScope.launch {
            delay(debounceMs)
            runSearchInternal(reset = true)
        }
    }

    fun onMediaTypeChanged(mediaType: TvSearchMediaType) {
        if (mediaType !in _uiState.value.availableMediaTypes) return
        searchGeneration += 1
        _uiState.update { it.copy(mediaType = mediaType) }
        searchJob?.cancel()
        loadMoreJob?.cancel()
        if (_uiState.value.query.isNotBlank()) {
            searchJob = viewModelScope.launch { runSearchInternal(reset = true) }
        }
    }

    fun submitSearch() {
        // Bump the generation (mirroring onQueryChanged) so a cancelled in-flight
        // load-more's response fails the stale-generation guard instead of
        // writing over the submitted search's results.
        searchGeneration += 1
        searchJob?.cancel()
        loadMoreJob?.cancel()
        val query = _uiState.value.query
        if (query.isBlank()) {
            _uiState.update {
                it.copy(
                    items = emptyList(),
                    total = 0,
                    hasMore = false,
                    isLoading = false,
                    isLoadingMore = false,
                    error = null,
                    rawResultCount = 0,
                )
            }
            return
        }
        searchJob = viewModelScope.launch { runSearchInternal(reset = true) }
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || !state.hasMore || state.query.isBlank()) return
        if (loadMoreJob?.isActive == true) return
        loadMoreJob = viewModelScope.launch {
            try {
                runSearchInternal(reset = false)
            } finally {
                // A load-more cancelled/superseded mid-flight (new keystroke)
                // must not leak isLoadingMore=true — that would wedge paging and
                // hide the grid's retry footer. The update is non-suspending, so
                // it still runs when this coroutine is cancelled.
                if (_uiState.value.isLoadingMore) {
                    _uiState.update { it.copy(isLoadingMore = false) }
                }
            }
        }
    }

    private suspend fun runSearchInternal(reset: Boolean) {
        val generation = searchGeneration
        val state = _uiState.value
        val requestedQuery = state.query
        val requestedMediaType = state.mediaType

        // A load-more whose accumulated results predate the current generation is
        // paging a stale offset for a superseded query — abort before firing.
        if (!reset && resultsGeneration != generation) {
            _uiState.update { it.copy(isLoadingMore = false) }
            return
        }

        var offset = if (reset) 0 else state.rawResultCount
        var fetchedRawCount = 0
        _uiState.update {
            if (reset) it.copy(isLoading = true, isLoadingMore = false, error = null)
            else it.copy(isLoadingMore = true)
        }

        while (true) {
            val result = catalogRepository.browse(
                source = "query",
                query = requestedQuery,
                mediaType = requestedMediaType.wire,
                offset = offset,
                limit = pageSize,
            )

            // safeApiCall upstream swallows CancellationException into NetworkError —
            // rethrow so a cancelled coroutine can't write any state from beyond the grave.
            if (result is ApiResult.NetworkError && result.exception is CancellationException) {
                throw result.exception
            }

            // Drop a page fetched for a query/filter the user has already moved past,
            // so it can neither replace nor append onto the current generation.
            if (generation != searchGeneration) {
                if (!reset) _uiState.update { it.copy(isLoadingMore = false) }
                return
            }

            when (result) {
                is ApiResult.Success -> {
                    // The results now belong to this generation; load-more may page.
                    if (reset) resultsGeneration = generation
                    val response = result.data
                    fetchedRawCount += response.items.size
                    val visibleItems = response.items
                        .visibleOnTv()
                        .filter { _uiState.value.showAudiobooks || !isAudiobookItemType(it.type) }

                    // A raw page can contain only hidden audiobook/reading
                    // results. The grid cannot request another page when it has
                    // no rendered cards, so transparently drain hidden-only
                    // pages until something visible is found or paging ends.
                    if (visibleItems.isEmpty() && response.hasMore && response.items.isNotEmpty()) {
                        offset += response.items.size
                        continue
                    }

                    _uiState.update {
                        val accumulatedItems = if (reset) visibleItems else it.items + visibleItems
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            items = accumulatedItems,
                            total = if (requestedMediaType == TvSearchMediaType.All) {
                                accumulatedItems.size
                            } else {
                                response.total
                            },
                            hasMore = response.hasMore && response.items.isNotEmpty(),
                            error = null,
                            rawResultCount = if (reset) fetchedRawCount else it.rawResultCount + fetchedRawCount,
                        )
                    }
                    return
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            error = result.message.ifBlank { "Search failed" },
                        )
                    }
                    return
                }
                is ApiResult.NetworkError -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            error = "Network error: ${result.exception.message ?: "unknown"}",
                        )
                    }
                    return
                }
            }
        }
    }
}
