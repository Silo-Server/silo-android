package org.siloserver.silo.android.ui.screens.people

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.Person
import org.siloserver.silo.model.catalog.isReadingMediaType
import org.siloserver.silo.model.catalog.personWorksFiltersForMobile
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.TokenManager
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import org.siloserver.silo.network.apiv2.CatalogContinuationV2
import org.siloserver.silo.repository.CatalogRepository
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
    /** True while the server-side metadata refresh poll is running (iOS
     *  isRefreshingMetadata — drives the "Loading metadata" pill). */
    val isRefreshingMetadata: Boolean = false,
)

/**
 * Loads viewer person detail and their paginated works.
 */
class PersonDetailViewModel(
    private val catalogRepository: CatalogRepository,
    savedStateHandle: SavedStateHandle,
    private val tokenManager: TokenManager? = null,
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

    private var metadataRefreshJob: kotlinx.coroutines.Job? = null
    private var detailJob: kotlinx.coroutines.Job? = null
    private var detailGeneration = 0L
    private var refreshRequestedForPerson = false

    init { if (personId > 0L) reload() }

    private suspend fun current(run: Long, owner: AuthScopeSnapshot?): Boolean {
        val now = tokenManager?.snapshotCurrentScope()
        // Check the run after the suspendable authority lookup, immediately before publication.
        return run == detailGeneration && currentCoroutineContext().isActive &&
            (if (tokenManager == null) owner == null else owner != null && owner.isSameIdentityAs(now) &&
                owner.serverUrl == now?.serverUrl && owner.profileId == now?.profileId &&
                owner.profileToken == now?.profileToken && owner.credentialGenerationId == now?.credentialGenerationId)
    }

    fun reload() {
        val run = ++detailGeneration
        detailJob?.cancel()
        metadataRefreshJob?.cancel()
        _uiState.update { it.copy(isLoading = true, isRefreshingMetadata = false, error = null) }
        detailJob = viewModelScope.launch {
            val owner = tokenManager?.snapshotCurrentScope()
            if (!current(run, owner)) return@launch
            val result = if (owner != null) catalogRepository.getPerson(personId, owner) else catalogRepository.getPerson(personId)
            if (!current(run, owner)) return@launch
            when (result) {
                is ApiResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            person = result.data,
                            error = null,
                        )
                    }
                    loadItems(_uiState.value.selectedFilter, reset = true)
                    scheduleMetadataRefreshIfNeeded(result.data, run, owner)
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
    private var continuation: CatalogContinuationV2? = null

    fun retryItems() = loadItems(_uiState.value.selectedFilter, reset = true)

    fun loadMoreIfNeeded() {
        val state = _uiState.value
        if (state.pagingError != null || !state.hasMore || state.isLoadingItems) return
        loadItems(state.selectedFilter, reset = false)
    }

    private fun resetPaging() {
        continuation = null
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
                continuation = continuation,
                limit = PersonWorksPageSize,
            )
            // Drop a stale response from a superseded filter selection so a
            // slower earlier load can't overwrite the newer one's results.
            if (gen != itemsGeneration) return@launch
            when (result) {
                is ApiResult.Success -> {
                    continuation = result.data.continuation
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

    /** One POST decision per view, followed only by bounded authorized detail reads. */
    private fun scheduleMetadataRefreshIfNeeded(person: Person, run: Long, owner: AuthScopeSnapshot?) {
        if (owner == null || !person.isMetadataIncomplete() || refreshRequestedForPerson) return
        refreshRequestedForPerson = true // Consumed even if dispatch/receipt is cancelled or uncertain.
        _uiState.update { it.copy(isRefreshingMetadata = true) }
        metadataRefreshJob = viewModelScope.launch {
            try {
                if (!current(run, owner)) return@launch
                if (catalogRepository.refreshPerson(personId, owner) !is ApiResult.Success) return@launch
                if (!current(run, owner)) return@launch
                var unchangedPolls = 0
                var previous = person
                val deadline = kotlin.time.TimeSource.Monotonic.markNow() + kotlin.time.Duration.parse("120s")
                // Keep both the elapsed window and a finite read count; HTTP has its own timeout.
                repeat(40) {
                    if (deadline.hasPassedNow()) return@launch
                    kotlinx.coroutines.delay(3_000)
                    if (deadline.hasPassedNow() || !current(run, owner)) return@launch
                    val result = catalogRepository.getPerson(personId, owner)
                    if (!current(run, owner)) return@launch
                    val updated = (result as? ApiResult.Success)?.data ?: return@launch
                    if (updated == previous) {
                        if (++unchangedPolls >= 5) return@launch // UI bound, not proof of queue completion.
                    } else {
                        unchangedPolls = 0
                        previous = updated
                        _uiState.update { it.copy(person = updated) }
                        if (!updated.isMetadataIncomplete()) return@launch
                    }
                }
            } finally {
                if (current(run, owner)) _uiState.update { it.copy(isRefreshingMetadata = false) }
            }
        }
    }

    private fun Person.isMetadataIncomplete(): Boolean =
        bio.isNullOrBlank() || photoUrl.isNullOrBlank() || birthDate.isNullOrBlank()
}
