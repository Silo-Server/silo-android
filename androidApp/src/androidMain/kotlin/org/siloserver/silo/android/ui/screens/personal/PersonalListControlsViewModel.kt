package org.siloserver.silo.android.ui.screens.personal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.siloserver.silo.catalog.filter.CatalogFilterQueryBuilder
import org.siloserver.silo.catalog.filter.CatalogFilterState
import org.siloserver.silo.model.catalog.CatalogFiltersResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.viewmodel.PersonalListQuery

/**
 * Sort keys for the saved lists (Watchlist / Favorites). Mirrors the TV
 * `TvLibrarySortOption.availableForPersonalList()` set: the default is the
 * server's stored list order, which is "no sort" on the wire, so [value] is
 * null there and it has no direction. "Recently Added" is the explicit
 * added_at sort (newest first by default).
 */
enum class PersonalListSort(
    val value: String?,
    val label: String,
    val defaultOrder: String,
    private val ascendingLabel: String,
    private val descendingLabel: String,
) {
    ListOrder(null, "List Order", "desc", "", ""),
    Title("title", "Title", "asc", "A–Z", "Z–A"),
    RecentlyAdded("added_at", "Recently Added", "desc", "Oldest first", "Newest first"),
    Year("year", "Year", "desc", "Oldest first", "Newest first"),
    Rating("rating_imdb", "Rating", "desc", "Lowest first", "Highest first"),
    Runtime("runtime", "Runtime", "asc", "Shortest first", "Longest first"),
    ;

    val hasDirection: Boolean get() = value != null

    fun directionLabel(order: String): String =
        if (order == "asc") ascendingLabel else descendingLabel
}

data class PersonalListControlsState(
    val sort: PersonalListSort = PersonalListSort.ListOrder,
    val order: String = PersonalListSort.ListOrder.defaultOrder,
    /** Facet selections + match mode; its sort/order fields are unused here. */
    val filters: CatalogFilterState = CatalogFilterState(),
    /** Vocabularies scoped to this list (`/catalog/filters?source=…`). */
    val availableFilters: CatalogFiltersResponse? = null,
) {
    val activeFacetCount: Int get() = filters.activeFacetCount

    /** Anything to reset — a non-default sort or any facet. */
    val isCustomised: Boolean
        get() = sort != PersonalListSort.ListOrder || filters.hasActiveFilters

    /** What the shared list ViewModel should fetch with. */
    val query: PersonalListQuery
        get() = PersonalListQuery(
            sort = sort.value,
            order = if (sort.hasDirection) order else null,
            queryGroups = CatalogFilterQueryBuilder.buildGroups(filters),
            match = CatalogFilterQueryBuilder.matchParam(filters).takeIf { filters.hasActiveFilters },
        )
}

/**
 * Sort + filter selection for one saved list, applied server-side through
 * `PersonalListViewModel.applyQuery`. Session-only and shared between the
 * For You inline grid and the standalone screen (the caller scopes it to the
 * Activity, keyed by [source]) — the same shape as the TV app's
 * `TvPersonalListControlsViewModel`.
 */
class PersonalListControlsViewModel(
    private val source: String,
    private val catalogRepository: CatalogRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PersonalListControlsState())
    val uiState: StateFlow<PersonalListControlsState> = _uiState.asStateFlow()

    init {
        loadFilters()
    }

    /** Re-picking the active key flips its direction; a new key starts at its default. */
    fun selectSort(sort: PersonalListSort) {
        _uiState.update { state ->
            if (sort == state.sort && sort.hasDirection) {
                state.copy(order = if (state.order == "asc") "desc" else "asc")
            } else {
                state.copy(sort = sort, order = sort.defaultOrder)
            }
        }
    }

    fun applyFilters(filters: CatalogFilterState) {
        _uiState.update { it.copy(filters = filters) }
    }

    fun resetFilters() {
        _uiState.update { it.copy(filters = it.filters.resetFilters()) }
    }

    /** Back to the defaults: list order, no facets. */
    fun resetAll() {
        _uiState.update {
            it.copy(
                sort = PersonalListSort.ListOrder,
                order = PersonalListSort.ListOrder.defaultOrder,
                filters = it.filters.resetFilters(),
            )
        }
    }

    private fun loadFilters() {
        viewModelScope.launch {
            val result = catalogRepository.getFilters(includeTechnical = true, source = source)
            if (result is ApiResult.Success) {
                _uiState.update { it.copy(availableFilters = result.data) }
            }
            // Vocabularies are a convenience; without them the sheet simply
            // offers its fixed facets.
        }
    }
}
