package org.siloserver.silo.tv.ui.screens.people

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.Person
import org.siloserver.silo.model.catalog.isAudiobookItemType
import org.siloserver.silo.model.catalog.personWorksFiltersForTv
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.tv.ui.util.visibleOnTv
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TvPersonWorksPageSize = 60

/** Filmography media-type filter for ten-foot surfaces, mirroring the phone filter where applicable. */
enum class TvPersonMediaFilter(val key: String, val title: String, val mediaType: String?) {
    All("all", "All", null),
    Movies("movie", "Movies", "movie"),
    Series("series", "Series", "series"),
    Audiobooks("audiobook", "Audiobooks", "audiobook"),
    Music("music", "Music", "music");

    companion object {
        fun fromKey(key: String): TvPersonMediaFilter? =
            entries.firstOrNull { it.key == key }
    }
}

private val TvPersonMediaFilters: List<TvPersonMediaFilter> =
    personWorksFiltersForTv().mapNotNull { TvPersonMediaFilter.fromKey(it.key) }

data class TvPersonDetailUiState(
    val isLoading: Boolean = true,
    val person: Person? = null,
    val items: List<BrowseItem> = emptyList(),
    val isLoadingItems: Boolean = false,
    val selectedFilter: TvPersonMediaFilter = TvPersonMediaFilter.All,
    val availableFilters: List<TvPersonMediaFilter> =
        TvPersonMediaFilters.filterNot { it == TvPersonMediaFilter.Audiobooks },
    val totalItems: Int = 0,
    val hasMore: Boolean = false,
    val pagingError: String? = null,
    val error: String? = null,
)

/**
 * Drives the Android TV person detail screen — the cast/crew profile plus their
 * filmography. Replicates the phone-only `PersonDetailViewModel` (which lives in
 * `androidApp`) against the same shared [CatalogRepository], loading the person
 * from `/api/v1/people/{id}` and the filmography from
 * `/api/v1/catalog?source=person&person_id=…`.
 *
 * Receives `personId` via Koin `parametersOf()` (see
 * [org.siloserver.silo.tv.di.androidTvModule]), matching the [TvItemDetailViewModel]
 * wiring pattern rather than the phone's `SavedStateHandle` injection.
 */
class TvPersonDetailViewModel(
    private val catalogRepository: CatalogRepository,
    private val personId: Long,
    private val personalDataRepository: org.siloserver.silo.repository.PersonalDataRepository? = null,
    private val showAudiobooksProvider: suspend () -> Boolean = { true },
) : ViewModel() {

    private val _uiState = MutableStateFlow(TvPersonDetailUiState())
    val uiState: StateFlow<TvPersonDetailUiState> = _uiState.asStateFlow()

    // The chip row is the intersection of two independently-loaded gates;
    // either may resolve first, so each stores its result and recomputes.
    private val showAudiobooksDeferred = viewModelScope.async { showAudiobooksProvider() }
    private var libraryGatedFilters: List<TvPersonMediaFilter> =
        TvPersonMediaFilters.filterNot { it == TvPersonMediaFilter.Audiobooks }
    private var filtersWithContent: Set<TvPersonMediaFilter>? = null

    init {
        // Gate the filmography filter chips by the libraries this profile can
        // actually see (QA 2026-07-08): a server with no audiobook/music
        // libraries shouldn't offer Audiobooks/Music filters. All/Movies/TV
        // always show; failures keep the full set (graceful degrade).
        viewModelScope.launch {
            val allowAudiobooks = showAudiobooksDeferred.await()
            val result = personalDataRepository?.listUserLibraries()
            val types = (result as? ApiResult.Success)?.data
                ?.map { it.type.lowercase() }
                ?.toSet()
            libraryGatedFilters = TvPersonMediaFilters.filter { filter ->
                when (filter) {
                    TvPersonMediaFilter.Audiobooks ->
                        allowAudiobooks && (
                            types == null ||
                                types.any { org.siloserver.silo.model.navigation.isAudiobookLikeLibraryType(it) }
                            )
                    TvPersonMediaFilter.Music ->
                        types == null || types.any { it == "music" || it == "audio" }
                    else -> true
                }
            }
            recomputeAvailableFilters()
        }
    }

    init {
        // Hide media-type chips the person has no works for: probe each
        // type's server-side total with limit=1. A failed probe keeps its
        // chip (graceful degrade, same policy as the library gate).
        viewModelScope.launch {
            if (personId <= 0L) return@launch
            val allowAudiobooks = showAudiobooksDeferred.await()
            val probed = TvPersonMediaFilters
                .filter { it.mediaType != null && (allowAudiobooks || it != TvPersonMediaFilter.Audiobooks) }
                .map { filter ->
                    async {
                        val result = catalogRepository.getPersonItems(
                            personId = personId,
                            mediaType = filter.mediaType,
                            offset = 0,
                            limit = 1,
                            snapshotAt = null,
                        )
                        filter to (result as? ApiResult.Success)?.data?.total
                    }
                }
                .awaitAll()
            filtersWithContent = probed
                .filter { (_, total) -> total == null || total > 0 }
                .map { (filter, _) -> filter }
                .toSet()
            recomputeAvailableFilters()
        }
    }

    private fun recomputeAvailableFilters() {
        val withContent = filtersWithContent
        val gated = libraryGatedFilters.filter { filter ->
            filter == TvPersonMediaFilter.All || withContent == null || filter in withContent
        }
        _uiState.update { state ->
            state.copy(
                availableFilters = gated,
                // The probes land before the user can reach the chips, but if
                // the active filter does vanish, fall back to All rather than
                // stranding the grid on a filter with no chip.
                selectedFilter = if (state.selectedFilter in gated) state.selectedFilter else TvPersonMediaFilter.All,
            )
        }
    }

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
                is ApiResult.Error -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = result.message.ifBlank { "Failed to load person" },
                    )
                }
                is ApiResult.NetworkError -> _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Network error. Check your connection.",
                    )
                }
            }
        }
    }

    fun applyFilter(filter: TvPersonMediaFilter) {
        // Re-selecting the active filter is a no-op — unless its load failed, in
        // which case the chip doubles as a retry so the grid can't dead-end.
        val state = _uiState.value
        if (filter == state.selectedFilter && state.pagingError == null) return
        _uiState.update { it.copy(selectedFilter = filter, items = emptyList()) }
        loadItems(filter, reset = true)
    }

    /** Re-runs the failed page-0 load for the active filter (retry button). */
    fun retryItems() {
        loadItems(_uiState.value.selectedFilter, reset = true)
    }

    private var itemsGeneration = 0
    private var nextRawOffset = 0
    private var snapshotAt: String? = null

    fun loadMoreIfNeeded() {
        val state = _uiState.value
        if (!state.hasMore || state.isLoadingItems) return
        loadItems(state.selectedFilter, reset = false)
    }

    private fun resetPaging() {
        nextRawOffset = 0
        snapshotAt = null
    }

    private fun loadItems(filter: TvPersonMediaFilter, reset: Boolean) {
        val gen = if (reset) ++itemsGeneration else itemsGeneration
        if (reset) resetPaging()
        viewModelScope.launch {
            val allowAudiobooks = showAudiobooksDeferred.await()
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
                offset = nextRawOffset,
                limit = TvPersonWorksPageSize,
                snapshotAt = snapshotAt,
            )
            // Drop a stale response from a superseded filter selection.
            if (gen != itemsGeneration) return@launch
            when (result) {
                is ApiResult.Success -> {
                    if (snapshotAt == null) snapshotAt = result.data.snapshot
                    nextRawOffset += result.data.items.size
                    val visibleItems = result.data.items
                        .visibleOnTv()
                        .filter { allowAudiobooks || !isAudiobookItemType(it.type) }
                    _uiState.update {
                        it.copy(
                            isLoadingItems = false,
                            // TV hides ebook/comic/etc. media types — keep the
                            // works grid consistent with the rest of the TV catalog.
                            items = if (reset) visibleItems else it.items + visibleItems,
                            totalItems = result.data.total,
                            hasMore = result.data.hasMore,
                            pagingError = null,
                        )
                    }
                }
                is ApiResult.Error -> _uiState.update {
                    it.copy(
                        isLoadingItems = false,
                        pagingError = result.message.ifBlank { "Failed to load works" },
                        // A failed reset-load left hasMore=false, which permanently
                        // disables loadMoreIfNeeded and dead-ends the grid on an
                        // empty "No titles found." with no way back. Keep the paging
                        // path alive so the load can recover (T22b).
                        hasMore = if (reset) true else it.hasMore,
                    )
                }
                is ApiResult.NetworkError -> _uiState.update {
                    it.copy(
                        isLoadingItems = false,
                        pagingError = "Network error. Check your connection.",
                        hasMore = if (reset) true else it.hasMore,
                    )
                }
            }
        }
    }
}
