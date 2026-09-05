package org.siloserver.silo.tv.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.CatalogEffectiveSort
import org.siloserver.silo.model.catalog.CatalogFiltersResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.apiv2.CatalogContinuationV2
import org.siloserver.silo.network.errorMessage
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.SectionRepository
import org.siloserver.silo.tv.ui.util.visibleOnTv
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TvLibraryCollectionDetailViewModel(
    private val sectionRepository: SectionRepository,
    private val catalogRepository: CatalogRepository,
    private val libraryId: Int,
    private val collectionId: String,
    val title: String,
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = true,
        val isLoadingMore: Boolean = false,
        val items: List<BrowseItem> = emptyList(),
        val hasMore: Boolean = false,
        val error: String? = null,
        /** Empty = send no sort, i.e. keep the collection's own order. */
        val sort: String = TvLibrarySortOption.CollectionOrder.wireValue,
        val order: String = "desc",
        val facetSelection: TvCatalogFacetSelection = TvCatalogFacetSelection(),
        val facetOptions: CatalogFiltersResponse? = null,
        /** What the server says it sorted by (see [CatalogEffectiveSort]). */
        val effectiveSort: CatalogEffectiveSort? = null,
    ) {
        val sortOption: TvLibrarySortOption
            get() = TvLibrarySortOption.entries.firstOrNull { it.wireValue == sort }
                ?: TvLibrarySortOption.CollectionOrder
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // Bumped on every reload-from-zero so a slow in-flight page from the
    // previous sort/filter cannot land on top of the new one.
    private var continuation: CatalogContinuationV2? = null
    private var loadGeneration = 0

    init {
        load()
        loadFacetOptions()
    }

    fun retry() {
        load()
    }

    /**
     * Sort panel behavior, matching Browse ([TvLibraryDetailViewModel.onSortKeySelected]):
     * re-picking the active key flips direction, a new key arrives at its
     * natural order. Collection order has no direction, so re-picking it is a
     * no-op rather than a flip.
     */
    fun onSortSelected(option: TvLibrarySortOption) {
        val state = _uiState.value
        val isCurrent = state.sort == option.wireValue
        if (isCurrent && option == TvLibrarySortOption.CollectionOrder) return
        val nextOrder = if (isCurrent) {
            if (state.order == "asc") "desc" else "asc"
        } else {
            option.defaultOrder
        }
        _uiState.update { it.copy(sort = option.wireValue, order = nextOrder) }
        load()
    }

    fun onFacetSelectionApplied(selection: TvCatalogFacetSelection) {
        if (_uiState.value.facetSelection == selection) return
        _uiState.update { it.copy(facetSelection = selection) }
        load()
    }

    fun clearFilters() {
        onFacetSelectionApplied(TvCatalogFacetSelection())
    }

    /**
     * Facet vocabulary scoped to this collection, so the panel only offers
     * values its members actually have. Non-fatal: without it the filter
     * panel simply reports that no filters are available.
     */
    private fun loadFacetOptions() {
        viewModelScope.launch {
            val result = catalogRepository.getFilters(
                includeTechnical = true,
                source = "library_collection",
                collectionId = collectionId,
            )
            if (result is ApiResult.Success) {
                _uiState.update { it.copy(facetOptions = result.data) }
            }
        }
    }

    private fun load() {
        loadGeneration += 1
        val generation = loadGeneration
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    isLoadingMore = false,
                    items = emptyList(),
                    hasMore = false,
                    error = null,
                )
            }
            val result = fetchVisiblePage(fromCursor = null)
            if (generation != loadGeneration) return@launch
            // Only the request that still owns the screen may move the cursor;
            // see [fetchVisiblePage].
            continuation = (result as? ApiResult.Success)?.data?.continuation
            when (result) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        items = result.data.items,
                        hasMore = result.data.hasMore,
                        effectiveSort = result.data.effectiveSort,
                        error = null,
                    )
                }
                is ApiResult.Error -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = result.message.ifBlank { "Failed to load collection" },
                    )
                }
                is ApiResult.NetworkError -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Network error: ${result.exception.message ?: "unknown"}",
                    )
                }
            }
        }
    }

    private data class VisiblePage(
        val items: List<BrowseItem>,
        val hasMore: Boolean,
        val continuation: CatalogContinuationV2?,
        val effectiveSort: CatalogEffectiveSort?,
    )

    /**
     * Advance server cursors across pages containing only TV-hidden books.
     * Return the continuation to the caller so only the current generation can
     * publish it alongside the visible cards.
     */
    private suspend fun fetchVisiblePage(fromCursor: CatalogContinuationV2?): ApiResult<VisiblePage> {
        val state = _uiState.value
        val facetGroups = state.facetSelection.toQueryGroups()
        // Describes the whole result set, so it comes from the first response
        // of the drain, not whichever page happened to be visible.
        var effectiveSort: CatalogEffectiveSort? = null
        var isFirstResponse = true
        var cursor = fromCursor
        while (true) {
            when (val result = sectionRepository.getLibraryCollectionItems(
                collectionId,
                continuation = cursor,
                limit = PAGE_SIZE,
                sort = state.sort.ifBlank { null },
                order = state.order,
                queryGroups = facetGroups,
                match = if (facetGroups.isNotEmpty()) {
                    if (state.facetSelection.matchAll) "all" else "any"
                } else {
                    null
                },
            )) {
                is ApiResult.Success -> {
                    if (isFirstResponse) {
                        isFirstResponse = false
                        effectiveSort = result.data.effectiveSort
                    }
                    cursor = result.data.continuation
                    val visible = result.data.items.visibleOnTv()
                    val hasMore = result.data.hasMore && result.data.items.isNotEmpty()
                    if (visible.isNotEmpty() || !hasMore) {
                        return ApiResult.Success(
                            VisiblePage(
                                items = visible,
                                hasMore = hasMore,
                                continuation = cursor,
                                effectiveSort = effectiveSort,
                            ),
                        )
                    }
                }
                is ApiResult.Error -> return ApiResult.Error(result.code, result.error, result.message)
                is ApiResult.NetworkError -> return ApiResult.NetworkError(result.exception)
            }
        }
    }

    fun loadMore() {
        val current = _uiState.value
        if (current.error != null || current.isLoading || current.isLoadingMore || !current.hasMore) return
        val generation = loadGeneration
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            val result = fetchVisiblePage(fromCursor = continuation)
            if (generation != loadGeneration) return@launch
            when (result) {
                is ApiResult.Success -> {
                    continuation = result.data.continuation
                    _uiState.update {
                        it.copy(
                            isLoadingMore = false,
                            items = (it.items + result.data.items)
                                .distinctBy { item -> item.contentId },
                            hasMore = result.data.hasMore,
                        )
                    }
                }
                is ApiResult.Error, is ApiResult.NetworkError -> {
                    _uiState.update { it.copy(isLoadingMore = false, error = result.errorMessage("Could not load more. Reload to try again.")) }
                }
            }
        }
    }

    private companion object {
        const val PAGE_SIZE = 60
    }
}
