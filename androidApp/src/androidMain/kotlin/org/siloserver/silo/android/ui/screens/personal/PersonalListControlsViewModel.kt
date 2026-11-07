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
import org.siloserver.silo.catalog.sort.CatalogSortField
import org.siloserver.silo.catalog.sort.CatalogSortRelevance
import org.siloserver.silo.model.catalog.CatalogFiltersResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.viewmodel.PersonalListQuery

/**
 * The saved lists' (Watchlist / Favorites) default order: the server's stored
 * list order, which is "no sort" on the wire, so it has no direction.
 */
internal const val PersonalListOrderLabel = "List Order"

/**
 * Sort fields for the saved lists, after [PersonalListOrderLabel]. Matches the
 * web client's favorites and watchlist pages: every field except the
 * per-viewer ones (Progress, Date Viewed, Plays).
 */
internal fun personalListSortOptions(
    shownRatingSources: Set<String>,
    current: CatalogSortField?,
): List<CatalogSortField> = CatalogSortField.options(
    relevance = CatalogSortRelevance.Any,
    includePersonalized = false,
    shownRatingSources = shownRatingSources,
    keep = current,
)

data class PersonalListControlsState(
    /** Null keeps the stored list order. */
    val sort: CatalogSortField? = null,
    val order: String = "desc",
    /** Facet selections + match mode; its sort/order fields are unused here. */
    val filters: CatalogFilterState = CatalogFilterState(),
    /** Vocabularies scoped to this list (`/catalog/filters?source=…`). */
    val availableFilters: CatalogFiltersResponse? = null,
) {
    val activeFacetCount: Int get() = filters.activeFacetCount

    /** Anything to reset — a non-default sort or any facet. */
    val isCustomised: Boolean
        get() = sort != null || filters.hasActiveFilters

    /** What the shared list ViewModel should fetch with. */
    val query: PersonalListQuery
        get() = PersonalListQuery(
            sort = sort?.wireValue,
            order = if (sort != null) order else null,
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
    fun selectSort(sort: CatalogSortField?) {
        _uiState.update { state ->
            when {
                sort == null -> state.copy(sort = null, order = "desc")
                sort == state.sort -> state.copy(order = if (state.order == "asc") "desc" else "asc")
                else -> state.copy(sort = sort, order = sort.defaultOrder)
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
                sort = null,
                order = "desc",
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
