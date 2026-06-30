package com.continuum.app.android.ui.screens.people

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.continuum.app.model.catalog.BrowseItem
import com.continuum.app.model.catalog.Person
import com.continuum.app.model.catalog.isReadingMediaType
import com.continuum.app.model.catalog.personWorksFiltersForMobile
import com.continuum.app.network.ApiResult
import com.continuum.app.repository.CatalogRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val PersonWorksPageSize = 60

enum class PersonMediaFilter(
    val key: String,
    val title: String,
    val mediaType: String?,
    val clientPredicate: (BrowseItem) -> Boolean = { true },
) {
    All("all", "All", null),
    Movies("movie", "Movies", "movie"),
    Series("series", "TV", "series"),
    Audiobooks("audiobook", "Audiobooks", "audiobook"),
    Music("music", "Music", "music"),
    Reading("reading", "Reading", null, { isReadingMediaType(it.type) });

    companion object {
        fun fromKey(key: String): PersonMediaFilter? =
            values().firstOrNull { it.key == key }
    }
}

private val MobilePersonMediaFilters: List<PersonMediaFilter> =
    personWorksFiltersForMobile().mapNotNull { PersonMediaFilter.fromKey(it.key) }

data class PersonDetailUiState(
    val isLoading: Boolean = true,
    val person: Person? = null,
    val items: List<BrowseItem> = emptyList(),
    val isLoadingItems: Boolean = false,
    val selectedFilter: PersonMediaFilter = PersonMediaFilter.All,
    val availableFilters: List<PersonMediaFilter> = MobilePersonMediaFilters,
    val totalItems: Int = 0,
    val hasMore: Boolean = false,
    val pagingError: String? = null,
    val error: String? = null,
)

/**
 * Person detail viewmodel. Loads the person profile and their
 * filmography from `/api/v1/catalog?source=person&person_id=…`,
 * mirroring iOS's `PersonDetailViewModel`.
 */
class PersonDetailViewModel(
    private val catalogRepository: CatalogRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val personId: Long =
        when (val raw = savedStateHandle.get<Any?>("personId")) {
            is Long -> raw
            is Int -> raw.toLong()
            is String -> raw.toLongOrNull() ?: 0L
            else -> 0L
        }

    private val _uiState = MutableStateFlow(PersonDetailUiState())
    val uiState: StateFlow<PersonDetailUiState> = _uiState.asStateFlow()

    init {
        if (personId > 0L) reload()
    }

    fun reload() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            when (val result = catalogRepository.getPerson(personId)) {
                is ApiResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            person = result.data,
                            error = null,
                        )
                    }
                    loadItems(_uiState.value.selectedFilter, reset = true)
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = result.message.ifBlank { "Failed to load person" },
                        )
                    }
                }
                is ApiResult.NetworkError -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Network error. Check your connection.",
                        )
                    }
                }
            }
        }
    }

    fun applyFilter(filter: PersonMediaFilter) {
        if (filter == _uiState.value.selectedFilter) return
        _uiState.update { it.copy(selectedFilter = filter, items = emptyList()) }
        loadItems(filter, reset = true)
    }

    private var itemsGeneration = 0
    private var nextOffset = 0
    private var snapshotAt: String? = null

    fun loadMoreIfNeeded() {
        val state = _uiState.value
        if (!state.hasMore || state.isLoadingItems) return
        loadItems(state.selectedFilter, reset = false)
    }

    private fun resetPaging() {
        nextOffset = 0
        snapshotAt = null
    }

    private fun loadItems(filter: PersonMediaFilter, reset: Boolean) {
        val gen = if (reset) ++itemsGeneration else itemsGeneration
        if (reset) resetPaging()
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoadingItems = true,
                    pagingError = null,
                    items = if (reset) emptyList() else it.items,
                    totalItems = if (reset) 0 else it.totalItems,
                    hasMore = if (reset) false else it.hasMore,
                )
            }
            val result = catalogRepository.getPersonItems(
                personId = personId,
                mediaType = filter.mediaType,
                offset = nextOffset,
                limit = PersonWorksPageSize,
                snapshotAt = snapshotAt,
            )
            // Drop a stale response from a superseded filter selection so a
            // slower earlier load can't overwrite the newer one's results.
            if (gen != itemsGeneration) return@launch
            when (result) {
                is ApiResult.Success -> {
                    if (snapshotAt == null) snapshotAt = result.data.snapshot
                    nextOffset += result.data.items.size
                    val visibleItems = result.data.items.filter(filter.clientPredicate)
                    _uiState.update {
                        it.copy(
                            isLoadingItems = false,
                            items = if (reset) visibleItems else it.items + visibleItems,
                            totalItems = result.data.total,
                            hasMore = result.data.hasMore,
                            pagingError = null,
                        )
                    }
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoadingItems = false,
                            pagingError = result.message.ifBlank { "Failed to load works" },
                        )
                    }
                }
                is ApiResult.NetworkError -> {
                    _uiState.update {
                        it.copy(
                            isLoadingItems = false,
                            pagingError = "Network error. Check your connection.",
                        )
                    }
                }
            }
        }
    }
}
