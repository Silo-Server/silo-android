package org.siloserver.silo.tv.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.CatalogEffectiveSort
import org.siloserver.silo.model.catalog.CatalogFiltersResponse
import org.siloserver.silo.network.ApiResult
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
            val result = fetchVisiblePage(fromOffset = 0)
            if (generation != loadGeneration) return@launch
            // Only the request that still owns the screen may move the cursor;
            // see [fetchVisiblePage].
            fetchedCount = if (result is ApiResult.Success) result.data.fetchedCount else 0
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
        /** RAW offset this request drained to; see [fetchedCount]. */
        val fetchedCount: Int,
        val effectiveSort: CatalogEffectiveSort?,
    )

    /**
     * Fetches pages starting at [fromOffset] until one yields at least one
     * TV-visible item or the collection is exhausted. Book-type entries
     * are filtered out per page ([visibleOnTv]); without draining, a page
     * that filters to empty with `hasMore=true` would strand the grid —
     * TvCatalogGrid skips pagination while its list is empty, so a
     * book-fronted collection would wrongly render as empty (Codex).
     *
     * The raw cursor it drained to is RETURNED rather than written to
     * [fetchedCount]: a request superseded by a sort/filter reload must not
     * move the live query's paging offset, and only the caller — after its
     * generation check — knows whether this request still owns the screen
     * (Codex).
     */
    private suspend fun fetchVisiblePage(fromOffset: Int): ApiResult<VisiblePage> {
        val state = _uiState.value
        val facetGroups = state.facetSelection.toQueryGroups()
        // Describes the whole result set, so it comes from the first response
        // of the drain, not whichever page happened to be visible.
        var effectiveSort: CatalogEffectiveSort? = null
        var isFirstResponse = true
        var offset = fromOffset
        while (true) {
            when (val result = sectionRepository.getLibraryCollectionItems(
                collectionId,
                offset = offset,
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
                    val drainedTo = offset + result.data.items.size
                    val visible = result.data.items.visibleOnTv()
                    val hasMore = result.data.hasMore && result.data.items.isNotEmpty()
                    if (visible.isNotEmpty() || !hasMore) {
                        return ApiResult.Success(
                            VisiblePage(
                                items = visible,
                                hasMore = hasMore,
                                fetchedCount = drainedTo,
                                effectiveSort = effectiveSort,
                            ),
                        )
                    }
                    offset = drainedTo
                }
                is ApiResult.Error -> return ApiResult.Error(result.code, result.error, result.message)
                is ApiResult.NetworkError -> return ApiResult.NetworkError(result.exception)
            }
        }
    }

    /**
     * Offsets track RAW fetched count, not the rendered list size — the TV
     * grid filters reading items out via [visibleOnTv], so paging by
     * `items.size` would re-fetch overlapping windows on book-heavy
     * collections.
     */
    private var fetchedCount = 0

    fun loadMore() {
        val current = _uiState.value
        if (current.isLoading || current.isLoadingMore || !current.hasMore) return
        val generation = loadGeneration
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true) }
            val result = fetchVisiblePage(fromOffset = fetchedCount)
            if (generation != loadGeneration) return@launch
            when (result) {
                is ApiResult.Success -> {
                    fetchedCount = result.data.fetchedCount
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
                    _uiState.update { it.copy(isLoadingMore = false) }
                }
            }
        }
    }

    private companion object {
        const val PAGE_SIZE = 60
    }
}
