package org.siloserver.silo.tv.ui.screens.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.apiv2.CatalogContinuationV2
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.tv.ui.util.visibleOnTv
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Sort options for the global Browse surface. Mirrors the phone Browse defaults
 * (`added_at` desc) while exposing the same wire values the catalog API accepts.
 */
enum class TvBrowseSortOption(val label: String, val wireValue: String) {
    DateAdded("Date Added", "added_at"),
    Title("Title", "title"),
    // Server sorts release date by "year" (matches the phone + the person
    // filmography endpoint); "release_date" was unsupported and silently
    // fell back to the default order.
    ReleaseDate("Release Year", "year"),
    Rating("Rating", "rating_imdb");

    companion object {
        fun fromWire(value: String): TvBrowseSortOption =
            entries.firstOrNull { it.wireValue == value } ?: DateAdded
    }
}

/**
 * Active filter selections for the global Browse surface. Unlike the library
 * detail browse ([org.siloserver.silo.tv.ui.screens.library.TvLibraryBrowseFilter])
 * this is cross-library and adds a content-rating dimension, matching the phone
 * `BrowseFilters`.
 */
data class TvBrowseFilter(
    val genre: String? = null,
    val contentRating: String? = null,
    val sort: String = TvBrowseSortOption.DateAdded.wireValue,
    val order: String = "desc",
)

/**
 * ViewModel for the TV global Browse surface.
 *
 * Cross-library catalog browse with genre + content-rating filters, sort, and
 * snapshot-stable infinite-scroll pagination. Logic mirrors the phone
 * [org.siloserver.silo.android.ui.screens.browse.BrowseViewModel] (no `libraryId`
 * scope) and the pagination/snapshot/generation pattern from
 * [org.siloserver.silo.tv.ui.screens.library.TvLibraryDetailViewModel]. Items are
 * filtered through [visibleOnTv] so non-video media (e.g. audiobooks) never
 * appear in the grid.
 */
class TvBrowseViewModel(
    private val catalogRepository: CatalogRepository,
) : ViewModel() {

    data class UiState(
        val items: List<BrowseItem> = emptyList(),
        val hasMore: Boolean = false,
        val loading: Boolean = true,
        val loadingMore: Boolean = false,
        val error: String? = null,
        val genres: List<String> = emptyList(),
        val contentRatings: List<String> = emptyList(),
        val filtersLoading: Boolean = true,
        val filter: TvBrowseFilter = TvBrowseFilter(),
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val pageSize = 100
    private var generation = 0
    private var continuation: CatalogContinuationV2? = null

    init {
        loadFilters()
        load(reset = true)
    }

    fun onGenreChanged(genre: String?) {
        updateFilter(_uiState.value.filter.copy(genre = genre))
    }

    fun onContentRatingChanged(contentRating: String?) {
        updateFilter(_uiState.value.filter.copy(contentRating = contentRating))
    }

    fun onSortChanged(sort: TvBrowseSortOption) {
        updateFilter(_uiState.value.filter.copy(sort = sort.wireValue))
    }

    /** Sort direction — "desc" or "asc" (phone parity). */
    fun onOrderChanged(order: String) {
        updateFilter(_uiState.value.filter.copy(order = order))
    }

    fun resetFilters() {
        updateFilter(TvBrowseFilter())
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.error != null || state.loading || state.loadingMore || !state.hasMore) return
        load(reset = false)
    }

    fun retry() {
        load(reset = true)
    }

    private fun updateFilter(filter: TvBrowseFilter) {
        if (_uiState.value.filter == filter) return
        _uiState.update { it.copy(filter = filter) }
        load(reset = true)
    }

    private fun loadFilters() {
        viewModelScope.launch {
            _uiState.update { it.copy(filtersLoading = true) }
            when (val filters = catalogRepository.getFilters(libraryId = null)) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(
                        genres = filters.data.genres.sorted(),
                        contentRatings = filters.data.contentRatings,
                        filtersLoading = false,
                    )
                }
                else -> _uiState.update { it.copy(filtersLoading = false) }
            }
        }
    }

    private fun load(reset: Boolean) {
        val gen = if (reset) {
            generation += 1
            generation
        } else {
            generation
        }

        if (reset) continuation = null

        viewModelScope.launch {
            val state = _uiState.value
            val filter = state.filter

            _uiState.update {
                if (reset) {
                    it.copy(
                        items = emptyList(),
                        hasMore = false,
                        loading = true,
                        loadingMore = false,
                        error = null,
                    )
                } else {
                    it.copy(loadingMore = true)
                }
            }

            val result = catalogRepository.browse(
                source = "query",
                libraryId = null,
                genre = filter.genre,
                contentRating = filter.contentRating,
                sort = filter.sort,
                order = filter.order,
                continuation = if (reset) null else continuation,
                limit = pageSize,
            )

            if (gen != generation) return@launch

            when (result) {
                is ApiResult.Success -> {
                    val response = result.data
                    continuation = response.continuation
                    _uiState.update {
                        val visibleItems = response.items.visibleOnTv()
                        it.copy(
                            items = if (reset) visibleItems else it.items + visibleItems,
                            hasMore = response.hasMore,
                            loading = false,
                            loadingMore = false,
                            error = null,
                        )
                    }
                }
                is ApiResult.Error -> _uiState.update {
                    it.copy(
                        loading = false,
                        loadingMore = false,
                        error = result.message.ifBlank { "Failed to load catalog" },
                    )
                }
                is ApiResult.NetworkError -> _uiState.update {
                    it.copy(
                        loading = false,
                        loadingMore = false,
                        error = "Network error: ${result.exception.message ?: "unknown"}",
                    )
                }
            }
        }
    }
}
