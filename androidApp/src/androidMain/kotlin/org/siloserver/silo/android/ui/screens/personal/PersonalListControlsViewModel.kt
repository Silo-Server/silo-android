package org.siloserver.silo.android.ui.screens.personal

import androidx.annotation.StringRes
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
import org.siloserver.silo.common.R as CommonR

/**
 * Sort keys for the saved lists (Watchlist / Favorites). Mirrors the TV
 * `TvLibrarySortOption.availableForPersonalList()` set: the default is the
 * server's stored list order, which is "no sort" on the wire, so [value] is
 * null there and it has no direction. "Recently Added" is the explicit
 * added_at sort (newest first by default).
 */
enum class PersonalListSort(
    val value: String?,
    @StringRes val label: Int,
    val defaultOrder: String,
    /** Direction labels; 0 for [ListOrder], which has no direction. */
    @StringRes private val ascendingLabel: Int,
    @StringRes private val descendingLabel: Int,
) {
    ListOrder(null, CommonR.string.catalog_sort_listOrder, "desc", 0, 0),
    Title("title", CommonR.string.catalog_sort_title, "asc", CommonR.string.library_direction_az, CommonR.string.library_direction_za),
    RecentlyAdded("added_at", CommonR.string.catalog_sort_recentlyAdded, "desc", CommonR.string.catalog_sort_direction_oldestFirst, CommonR.string.catalog_sort_direction_newestFirst),
    Year("year", CommonR.string.catalog_sort_year, "desc", CommonR.string.catalog_sort_direction_oldestFirst, CommonR.string.catalog_sort_direction_newestFirst),
    Rating("rating_imdb", CommonR.string.catalog_sort_rating, "desc", CommonR.string.catalog_sort_direction_lowestFirst, CommonR.string.catalog_sort_direction_highestFirst),
    Runtime("runtime", CommonR.string.catalog_sort_runtime, "asc", CommonR.string.catalog_sort_direction_shortestFirst, CommonR.string.catalog_sort_direction_longestFirst),
    ;

    val hasDirection: Boolean get() = value != null

    /** Only meaningful when [hasDirection]. */
    @StringRes
    fun directionLabel(order: String): Int =
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
