package org.siloserver.silo.tv.ui.screens.personal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.siloserver.silo.model.catalog.CatalogFiltersResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.tv.ui.screens.library.TvCatalogFacetSelection
import org.siloserver.silo.tv.ui.screens.library.TvLibrarySortOption
import org.siloserver.silo.tv.ui.screens.library.defaultOrder
import org.siloserver.silo.viewmodel.PersonalListQuery

/**
 * Sort/filter state for a TV personal list (favorites or watchlist).
 *
 * Kept out of the shared [org.siloserver.silo.viewmodel.PersonalListViewModel]
 * deliberately: the phone clients have no such controls, and the sort keys and
 * facet vocabulary are TV Browse concepts. Holding it in a Koin-scoped
 * ViewModel rather than composition state is what lets a viewer leave the For
 * You saved list and come back to the same sort — the same reason the shared
 * list ViewModels are scoped that way.
 *
 * [source] is the catalog source ("favorites" / "watchlist") and is also the
 * Koin key, so the two lists keep independent selections.
 */
class TvPersonalListControlsViewModel(
    private val catalogRepository: CatalogRepository,
    private val source: String,
) : ViewModel() {

    data class UiState(
        /** [TvLibrarySortOption.ListOrder] = stored list order, i.e. no sort. */
        val sort: String = TvLibrarySortOption.ListOrder.wireValue,
        val order: String = "desc",
        val facetSelection: TvCatalogFacetSelection = TvCatalogFacetSelection(),
        val facetOptions: CatalogFiltersResponse? = null,
    ) {
        val sortOption: TvLibrarySortOption
            get() = TvLibrarySortOption.entries.firstOrNull { it.wireValue == sort }
                ?: TvLibrarySortOption.ListOrder

        /** What the shared list ViewModel should fetch under. */
        val query: PersonalListQuery
            get() {
                val groups = facetSelection.toQueryGroups()
                val sorted = sortOption != TvLibrarySortOption.ListOrder
                return PersonalListQuery(
                    sort = sort.takeIf { sorted },
                    order = order.takeIf { sorted },
                    queryGroups = groups,
                    match = if (groups.isEmpty()) {
                        null
                    } else if (facetSelection.matchAll) {
                        "all"
                    } else {
                        "any"
                    },
                )
            }
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        loadFacetOptions()
    }

    /**
     * Matches Browse and the collection page: re-picking the active key flips
     * direction, a new key arrives at its natural order, and the source-order
     * entry has no direction to flip.
     */
    fun onSortSelected(option: TvLibrarySortOption) {
        val state = _uiState.value
        val isCurrent = state.sort == option.wireValue
        if (isCurrent && !option.hasDirection) return
        val nextOrder = if (isCurrent) {
            if (state.order == "asc") "desc" else "asc"
        } else {
            option.defaultOrder
        }
        _uiState.update { it.copy(sort = option.wireValue, order = nextOrder) }
    }

    fun onFacetSelectionApplied(selection: TvCatalogFacetSelection) {
        _uiState.update { it.copy(facetSelection = selection) }
    }

    fun clearFilters() {
        onFacetSelectionApplied(TvCatalogFacetSelection())
    }

    /**
     * Facet vocabulary scoped to this list, so the panel only offers values the
     * saved titles actually have. Non-fatal: without it the panel simply
     * reports that no filters are available.
     */
    private fun loadFacetOptions() {
        viewModelScope.launch {
            val result = catalogRepository.getFilters(
                includeTechnical = true,
                source = source,
            )
            if (result is ApiResult.Success) {
                _uiState.update { it.copy(facetOptions = result.data) }
            }
        }
    }
}
