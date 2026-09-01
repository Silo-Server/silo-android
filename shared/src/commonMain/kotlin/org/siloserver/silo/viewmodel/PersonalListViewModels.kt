package org.siloserver.silo.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.CatalogEffectiveSort
import org.siloserver.silo.model.catalog.CatalogQueryGroup
import org.siloserver.silo.model.catalog.CatalogResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.PersonalDataRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Sort + filter applied to a personal list.
 *
 * The default — no sort, no facet groups — is the server's stored list order
 * (most recently saved first). Favorites and watchlist fetch through
 * `/catalog?source=…`, the only route that accepts sort and facets.
 */
data class PersonalListQuery(
    /** Optional top-level catalog type (for example `movie` or `series`). */
    val mediaType: String? = null,
    /** null = send no sort, i.e. keep the server's stored list order. */
    val sort: String? = null,
    val order: String? = null,
    val queryGroups: List<CatalogQueryGroup> = emptyList(),
    /** "all" | "any"; only meaningful when [queryGroups] is non-empty. */
    val match: String? = null,
) {
    val isDefault: Boolean get() = mediaType == null && sort == null && queryGroups.isEmpty()
}

/**
 * Shared UI state for paginated personal lists (favorites, watchlist, history).
 */
data class PersonalListUiState(
    val items: List<BrowseItem> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val hasMore: Boolean = false,
    val total: Int = 0,
    val query: PersonalListQuery = PersonalListQuery(),
    /** What the server says it sorted by, when it reports one. */
    val effectiveSort: CatalogEffectiveSort? = null,
)

/**
 * Abstract base for personal-data list ViewModels. Subclasses provide the
 * repository fetch function; everything else (pagination, refresh, state
 * management) is shared.
 */
abstract class PersonalListViewModel(
    private val pageSize: Int = 40,
) : ViewModel() {

    protected val _uiState = MutableStateFlow(PersonalListUiState())
    val uiState: StateFlow<PersonalListUiState> = _uiState.asStateFlow()

    /**
     * True once the `init` load has settled with content on screen (a successful
     * page, or a failure while items are already showing). Screens use this to
     * gate their ON_RESUME re-fetch on "the initial load actually completed"
     * instead of counting resume events in composition-scoped state, which is
     * recreated (and so mis-counts) on every re-entry to the composition.
     */
    var hasLoadedOnce: Boolean = false
        private set

    /**
     * The sort/filter every fetch runs under. Mirrored into the UI state so
     * screens can render the controls from one source of truth.
     */
    protected var query: PersonalListQuery = PersonalListQuery()
        private set

    protected abstract suspend fun fetchPage(
        offset: Int,
        limit: Int,
        query: PersonalListQuery,
    ): ApiResult<CatalogResponse>

    protected fun loadInitial() {
        load(reset = true)
    }

    /**
     * Swap the sort/filter and reload from zero. The reset bumps the content
     * generation, so any page still in flight under the previous query is
     * dropped when it lands rather than mixed into the new list.
     */
    fun applyQuery(newQuery: PersonalListQuery) {
        if (newQuery == query) return
        query = newQuery
        // The old query's rows must not stand in for the new one — not while
        // it loads, and not if it fails (the grid would silently keep showing
        // cards that do not match the selected sort/filters).
        _uiState.update { it.copy(query = newQuery, items = emptyList(), total = 0, hasMore = false) }
        load(reset = true)
    }

    fun loadMore() {
        val state = _uiState.value
        // isRefreshing too: refresh reloads from offset zero, so a page fetched
        // alongside it uses an offset the replacement invalidates.
        if (state.isLoading || state.isLoadingMore || state.isRefreshing || !state.hasMore) return
        load(reset = false)
    }

    /**
     * Bumped by every load that REPLACES the list — a reset or a refresh.
     *
     * Gating the triggers is not enough on its own. A page can already be in
     * flight when a refresh starts, and refresh has no way to cancel it; when
     * that page lands it appends items fetched at `offset = N` on top of a list
     * that is now page one, leaving a hole where the middle used to be. Checking
     * the generation on the way OUT is what makes a superseded page harmless,
     * whichever order the two requests finish in.
     */
    private var contentGeneration = 0

    /**
     * Which request currently owns each loading flag.
     *
     * Generation alone cannot answer this. A reset owns isLoading and a refresh
     * owns isRefreshing, so when one supersedes the other the newer request
     * clears a DIFFERENT flag from the one the superseded request set — and the
     * superseded one, told that "the newer replacement owns those flags",
     * cleared nothing. A reset overtaken by a refresh therefore left isLoading
     * true forever, and the surface spinning.
     *
     * Every request releases exactly the flag it claimed, and only while it is
     * still the claimant.
     */
    private var requestSequence = 0
    private var loadingOwner = 0
    private var refreshingOwner = 0
    private var loadingMoreOwner = 0

    fun retry() = load(reset = true)

    fun refresh() {
        // Claimed synchronously, for the same reason as load().
        val generation = ++contentGeneration
        val requestId = ++requestSequence
        refreshingOwner = requestId
        _uiState.update { it.copy(isRefreshing = true, error = null) }
        viewModelScope.launch {
            val offset = 0
            val result = fetchPage(offset, pageSize, query)
            // A newer replacement started while this refresh was in flight.
            // Release isRefreshing unless a newer REFRESH has re-claimed it —
            // a superseding reset owns isLoading instead and would not clear
            // this one on its way past.
            if (generation != contentGeneration) {
                if (refreshingOwner == requestId) {
                    _uiState.update { it.copy(isRefreshing = false) }
                }
                return@launch
            }
            when (val r = result) {
                is ApiResult.Success -> {
                    // A refresh that publishes content has loaded once, whatever
                    // the initial load did. Screens gate their resume re-fetch
                    // on this flag, so leaving it false when a refresh overtakes
                    // that load disables the resume refresh for the whole life
                    // of the view model.
                    hasLoadedOnce = true
                    _uiState.update {
                    it.copy(
                        items = r.data.items,
                        hasMore = r.data.hasMore,
                        total = r.data.total,
                        effectiveSort = r.data.effectiveSort,
                        isRefreshing = false,
                        error = null,
                    )
                    }
                }
                is ApiResult.Error, is ApiResult.NetworkError -> {
                    _uiState.update { it.copy(isRefreshing = false) }
                }
            }
        }
    }

    private fun load(reset: Boolean) {
        // Offset, generation and loading flag are all claimed SYNCHRONOUSLY,
        // before the coroutine is launched. Doing it inside the launch left a
        // window where loadMore() could see an idle list, queue itself, and
        // have refresh() run first — the paging coroutine would then capture
        // the refresh's generation, look current, and append its old-offset
        // page anyway. Claiming here also makes the guard in loadMore() mean
        // something: the flag is set by the time a second call can read it.
        val state = _uiState.value
        val offset = if (reset) 0 else state.items.size
        val generation = if (reset) ++contentGeneration else contentGeneration
        val requestId = ++requestSequence
        if (reset) loadingOwner = requestId else loadingMoreOwner = requestId
        _uiState.update {
            if (reset) it.copy(isLoading = true, error = null)
            else it.copy(isLoadingMore = true)
        }
        // Captured with the offset: a query swap mid-flight must not make this
        // page's items describe a different list from the one it asked for.
        val requestQuery = query
        viewModelScope.launch {
            val result = fetchPage(offset, pageSize, requestQuery)
            // Superseded WHILE IN FLIGHT: something replaced the list, so this
            // page's offset no longer describes anything. Checked here rather
            // than before the fetch — before it, there is nothing to be stale
            // about. Dropping it silently is right: the replacement already
            // published a coherent list, and applying this one's items or its
            // error on top would only undo that. The loading flag still has to
            // be released, because this request really has finished.
            if (generation != contentGeneration) {
                // Release this request's own flag, and only while it still owns
                // it. A later request of the same kind has already re-claimed
                // it and will clear it itself.
                _uiState.update {
                    when {
                        reset && loadingOwner == requestId -> it.copy(isLoading = false)
                        !reset && loadingMoreOwner == requestId -> it.copy(isLoadingMore = false)
                        else -> it
                    }
                }
                return@launch
            }
            when (val r = result) {
                is ApiResult.Success -> {
                    hasLoadedOnce = true
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            items = if (reset) r.data.items else it.items + r.data.items,
                            hasMore = r.data.hasMore,
                            total = r.data.total,
                            effectiveSort = r.data.effectiveSort ?: it.effectiveSort,
                            error = null,
                        )
                    }
                }
                is ApiResult.Error -> {
                    if (_uiState.value.items.isNotEmpty()) hasLoadedOnce = true
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            error = r.message.ifBlank { "Failed to load" },
                        )
                    }
                }
                is ApiResult.NetworkError -> {
                    if (_uiState.value.items.isNotEmpty()) hasLoadedOnce = true
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isLoadingMore = false,
                            error = "Network error: ${r.exception.message ?: "unknown"}",
                        )
                    }
                }
            }
        }
    }
}

class FavoritesViewModel(
    private val personalDataRepository: PersonalDataRepository,
    private val catalogRepository: CatalogRepository,
) : PersonalListViewModel() {

    init {
        loadInitial()
    }

    // Always the catalog resolver, even for the default query: it returns the
    // same stored list order as the legacy `/favorites` route but also reports
    // `total`, so an item count is available before any sort is applied.
    override suspend fun fetchPage(offset: Int, limit: Int, query: PersonalListQuery) =
        catalogRepository.browse(
            source = "favorites",
            mediaType = query.mediaType,
            sort = query.sort,
            order = query.order,
            offset = offset,
            limit = limit,
            queryGroups = query.queryGroups,
            match = query.match,
        )

    fun toggleFavorite(itemId: String) {
        viewModelScope.launch {
            personalDataRepository.toggleFavorite(itemId, false)
            // Optimistically remove from list
            _uiState.update { state ->
                state.copy(
                    items = state.items.filter { it.contentId != itemId },
                    total = (state.total - 1).coerceAtLeast(0),
                )
            }
        }
    }
}

class WatchlistViewModel(
    private val personalDataRepository: PersonalDataRepository,
    private val catalogRepository: CatalogRepository,
) : PersonalListViewModel() {

    init {
        loadInitial()
    }

    // Always the catalog resolver, even for the default query: it returns the
    // same stored list order as the legacy `/watchlist` route but also reports
    // `total`, so an item count is available before any sort is applied.
    override suspend fun fetchPage(offset: Int, limit: Int, query: PersonalListQuery) =
        catalogRepository.browse(
            source = "watchlist",
            mediaType = query.mediaType,
            sort = query.sort,
            order = query.order,
            offset = offset,
            limit = limit,
            queryGroups = query.queryGroups,
            match = query.match,
        )

    fun removeFromWatchlist(itemId: String) {
        viewModelScope.launch {
            personalDataRepository.toggleWatchlist(itemId, false)
            _uiState.update { state ->
                state.copy(
                    items = state.items.filter { it.contentId != itemId },
                    total = (state.total - 1).coerceAtLeast(0),
                )
            }
        }
    }
}

class HistoryViewModel(
    private val personalDataRepository: PersonalDataRepository,
) : PersonalListViewModel() {

    init {
        loadInitial()
    }

    // History has no sort/filter surface, so the query is always the default.
    override suspend fun fetchPage(offset: Int, limit: Int, query: PersonalListQuery) =
        personalDataRepository.listHistory(offset = offset, limit = limit)
}
