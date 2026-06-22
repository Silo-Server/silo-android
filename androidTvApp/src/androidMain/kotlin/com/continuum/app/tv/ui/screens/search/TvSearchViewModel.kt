package com.continuum.app.tv.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.continuum.app.model.catalog.BrowseItem
import com.continuum.app.model.navigation.isAudiobookLikeLibraryType
import com.continuum.app.model.navigation.tvMediaModeCapabilities
import com.continuum.app.network.ApiResult
import com.continuum.app.repository.CatalogRepository
import com.continuum.app.repository.PersonalDataRepository
import com.continuum.app.tv.ui.util.visibleOnTv
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
) : ViewModel() {

    data class UiState(
        val query: String = "",
        val mediaType: TvSearchMediaType = TvSearchMediaType.All,
        /** Media-type chips to show, derived from the user's libraries. */
        val availableMediaTypes: List<TvSearchMediaType> = TvSearchMediaType.entries.toList(),
        val items: List<BrowseItem> = emptyList(),
        val total: Int = 0,
        val hasMore: Boolean = false,
        val isLoading: Boolean = false,
        val isLoadingMore: Boolean = false,
        val error: String? = null,
        val rawResultCount: Int = 0,
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
                if (hasAudiobooks) add(TvSearchMediaType.Audiobooks)
            }
            val current = _uiState.value
            val nextMediaType = if (current.mediaType in types) current.mediaType else TvSearchMediaType.All
            _uiState.update { it.copy(availableMediaTypes = types, mediaType = nextMediaType) }
            // If the active filter was forced to change while a query is live,
            // re-run so results aren't left stale under the old filter.
            if (nextMediaType != current.mediaType && current.query.isNotBlank()) {
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

    fun onQueryChanged(query: String) {
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
        _uiState.update { it.copy(mediaType = mediaType) }
        searchJob?.cancel()
        loadMoreJob?.cancel()
        if (_uiState.value.query.isNotBlank()) {
            searchJob = viewModelScope.launch { runSearchInternal(reset = true) }
        }
    }

    fun submitSearch() {
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
        loadMoreJob = viewModelScope.launch { runSearchInternal(reset = false) }
    }

    private suspend fun runSearchInternal(reset: Boolean) {
        val state = _uiState.value
        val requestedQuery = state.query
        val requestedMediaType = state.mediaType
        val offset = if (reset) 0 else state.rawResultCount
        _uiState.update {
            if (reset) it.copy(isLoading = true, error = null)
            else it.copy(isLoadingMore = true)
        }

        val result = catalogRepository.browse(
            source = "query",
            query = requestedQuery,
            mediaType = requestedMediaType.wire,
            offset = offset,
            limit = pageSize,
        )

        val current = _uiState.value
        if (current.query != requestedQuery || current.mediaType != requestedMediaType) {
            // The query/filter moved on while this request was in flight. A
            // fresher run owns the spinner, but clear our own loading flags so a
            // cancelled-then-superseded request can't leave a stuck indicator.
            _uiState.update { it.copy(isLoading = false, isLoadingMore = false) }
            return
        }

        when (result) {
            is ApiResult.Success -> {
                val response = result.data
                val visibleItems = response.items.visibleOnTv()
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
                        hasMore = response.hasMore,
                        error = null,
                        rawResultCount = if (reset) {
                            response.items.size
                        } else {
                            it.rawResultCount + response.items.size
                        },
                    )
                }
            }
            is ApiResult.Error -> _uiState.update {
                it.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    error = result.message.ifBlank { "Search failed" },
                )
            }
            is ApiResult.NetworkError -> _uiState.update {
                it.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    error = "Network error: ${result.exception.message ?: "unknown"}",
                )
            }
        }
    }
}
