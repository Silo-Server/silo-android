package org.siloserver.silo.android.ui.screens.browse

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.catalog.filter.BrowseFacetMediaType
import org.siloserver.silo.catalog.filter.CatalogFilterQueryBuilder
import org.siloserver.silo.catalog.filter.CatalogFilterState
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.CatalogFiltersResponse
import org.siloserver.silo.model.catalog.MediaItemUserState
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.apiv2.CatalogContinuationV2
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.port.LocalContentState
import org.siloserver.silo.repository.port.NoOpUserItemStatePort
import org.siloserver.silo.repository.port.UserItemStatePort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive

/**
 * UI state for the browse / catalog screen.
 */
data class BrowseUiState(
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val items: List<BrowseItem> = emptyList(),
    val hasMore: Boolean = false,
    val total: Int = 0,
    val filterState: CatalogFilterState = CatalogFilterState(),
    val mediaType: BrowseFacetMediaType = BrowseFacetMediaType.Video,
    val preserveFilters: Boolean = true,
    val selectedNamePrefix: String? = null,
    val catalogDensity: CatalogViewDensity = CatalogViewDensity.Normal,
    val availableFilters: CatalogFiltersResponse? = null,
    val libraryId: Int? = null,
    val title: String = "Browse",
    val error: String? = null,
)

/**
 * ViewModel for the catalog/browse screen.
 *
 * Manages catalog browsing with filtering, sorting, and infinite-scroll
 * pagination. Loads available filters on first launch.
 */
class BrowseViewModel(
    private val catalogRepository: CatalogRepository,
    savedStateHandle: SavedStateHandle,
    private val userItemState: UserItemStatePort = NoOpUserItemStatePort,
    private val browsePrefs: BrowsePrefsStore? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(BrowseUiState())
    val uiState: StateFlow<BrowseUiState> = _uiState.asStateFlow()

    private var continuation: CatalogContinuationV2? = null
    private var pagingJob: kotlinx.coroutines.Job? = null
    private val pageSize = 40

    init {
        val libraryId = savedStateHandle.get<String>("libraryId")?.toIntOrNull()
        // Restore the persisted filter/sort state before the first load so a
        // preserved selection doesn't flash the unfiltered grid (iOS parity).
        val restored = browsePrefs?.savedState(libraryId)
        _uiState.update {
            it.copy(
                libraryId = libraryId,
                filterState = restored ?: it.filterState,
                preserveFilters = browsePrefs?.preserveEnabled(libraryId) ?: true,
            )
        }
        loadFilters()
        loadItems(reset = true)
    }

    /**
     * Applies a new filter/sort state, persists it (when preserve is on),
     * and reloads the catalog from the beginning.
     */
    fun applyFilterState(state: CatalogFilterState) {
        if (state == _uiState.value.filterState) return
        _uiState.update { it.copy(filterState = state) }
        browsePrefs?.saveState(_uiState.value.libraryId, state)
        loadItems(reset = true)
    }

    /** Clears facets + match mode but keeps sort/order (iOS resetFilters). */
    fun resetFilters() {
        applyFilterState(_uiState.value.filterState.resetFilters())
    }

    /** iOS sort menu semantics: tapping the active key flips direction,
     *  another key selects it with its default order. */
    fun selectSort(field: String, defaultOrder: String = "desc") {
        val current = _uiState.value.filterState
        val next = if (current.sort == field) {
            current.copy(order = if (current.order == "desc") "asc" else "desc")
        } else {
            current.copy(sort = field, order = defaultOrder)
        }
        applyFilterState(next)
    }

    fun setPreserveFilters(enabled: Boolean) {
        val libraryId = _uiState.value.libraryId
        browsePrefs?.setPreserveEnabled(libraryId, enabled)
        _uiState.update { it.copy(preserveFilters = enabled) }
        if (enabled) browsePrefs?.saveState(libraryId, _uiState.value.filterState)
    }

    fun selectNamePrefix(prefix: String?) {
        val normalizedPrefix = normalizeCatalogNamePrefix(prefix)
        if (_uiState.value.selectedNamePrefix == normalizedPrefix) return
        _uiState.update {
            it.copy(
                selectedNamePrefix = normalizedPrefix,
                items = emptyList(),
                total = 0,
                hasMore = false,
            )
        }
        loadItems(reset = true)
    }

    fun selectViewDensity(density: CatalogViewDensity) {
        if (_uiState.value.catalogDensity == density) return
        _uiState.update { it.copy(catalogDensity = density) }
    }

    /**
     * Loads the next page of items for infinite scroll.
     */
    fun loadMore() {
        val current = _uiState.value
        if (current.error != null || current.isLoading || current.isLoadingMore || !current.hasMore) return
        loadItems(reset = false)
    }

    /**
     * Full refresh: reloads filters and items.
     */
    fun refresh() {
        loadFilters()
        loadItems(reset = true)
    }

    private fun loadFilters() {
        viewModelScope.launch {
            when (
                val result = catalogRepository.getFilters(
                    _uiState.value.libraryId,
                    // Resolution + audio/subtitle language vocabularies are
                    // technical facets, only sent on request (iOS parity).
                    includeTechnical = true,
                )
            ) {
                is ApiResult.Success -> {
                    _uiState.update { it.copy(availableFilters = result.data) }
                }
                else -> { /* Filters are non-critical; ignore errors */ }
            }
        }
    }

    /**
     * Overlay local optimistic watched/favorite onto the grid items (local non-null
     * wins), so a mutation made offline — or just-made online before the next
     * refresh — shows immediately instead of the stale server snapshot. Mirrors
     * [org.siloserver.silo.viewmodel.HomeViewModel]. No-op on the default port.
     */
    private suspend fun overlayLocalState(items: List<BrowseItem>): List<BrowseItem> {
        val ids = items.map { it.contentId }.distinct()
        if (ids.isEmpty()) return items
        val local: Map<String, LocalContentState> = userItemState.localContentStates(ids)
        if (local.isEmpty()) return items
        return items.map { item ->
            val ls = local[item.contentId] ?: return@map item
            val base = item.userState ?: MediaItemUserState()
            item.copy(
                userState = base.copy(
                    played = ls.watched ?: base.played,
                    isFavorite = ls.favorite ?: base.isFavorite,
                ),
            )
        }
    }

    private fun loadItems(reset: Boolean) {
        pagingJob?.cancel()
        pagingJob = viewModelScope.launch {
            val currentState = _uiState.value
            val cursor = if (reset) null else continuation
            _uiState.update {
                if (reset) it.copy(isLoading = true, error = null)
                else it.copy(isLoadingMore = true, error = null)
            }

            val filters = currentState.filterState
            val result = catalogRepository.browse(
                libraryId = currentState.libraryId,
                sort = filters.sort,
                order = filters.order,
                continuation = cursor,
                limit = pageSize,
                namePrefix = currentState.selectedNamePrefix,
                queryGroups = CatalogFilterQueryBuilder.buildGroups(filters),
                match = CatalogFilterQueryBuilder.matchParam(filters)
                    .takeIf { filters.hasActiveFilters },
            )

            when (result) {
                is ApiResult.Success -> {
                    val response = result.data
                    // Overlay local optimistic watched/favorite so an offline mutation
                    // shows immediately on the cached grid (mirrors Home).
                    val overlaid = overlayLocalState(response.items)
                    kotlin.coroutines.coroutineContext.ensureActive()
                    continuation = response.continuation
                    // Audiobook libraries surface book-native facets
                    // (author/narrator/series) — detected from the first item
                    // like iOS BrowseMediaType.from.
                    val mediaType = overlaid.firstOrNull()?.let { first ->
                        if (org.siloserver.silo.model.catalog.isAudiobookItemType(first.type)) {
                            BrowseFacetMediaType.Audiobook
                        } else {
                            BrowseFacetMediaType.Video
                        }
                    }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            items = if (reset) overlaid else it.items + overlaid,
                            hasMore = response.hasMore,
                            total = response.total,
                            title = response.title ?: "Browse",
                            mediaType = mediaType ?: it.mediaType,
                            error = null,
                        )
                    }
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            error = result.message.ifBlank { "Failed to load catalog" },
                        )
                    }
                }
                is ApiResult.NetworkError -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            error = "Network error. Check your connection.",
                        )
                    }
                }
            }
        }
    }
}
