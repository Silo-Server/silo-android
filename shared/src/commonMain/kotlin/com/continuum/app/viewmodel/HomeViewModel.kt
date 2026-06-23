package com.continuum.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.continuum.app.domain.MediaActionsCoordinator
import com.continuum.app.model.catalog.MediaItemUserState
import com.continuum.app.model.section.ResolvedSection
import com.continuum.app.model.section.SectionItem
import com.continuum.app.network.ApiResult
import com.continuum.app.repository.SectionRepository
import com.continuum.app.repository.port.HomeCachePort
import com.continuum.app.repository.port.NoOpHomeCachePort
import com.continuum.app.repository.port.LocalContentState
import com.continuum.app.repository.port.NoOpUserItemStatePort
import com.continuum.app.repository.port.UserItemStatePort
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val sections: List<ResolvedSection> = emptyList(),
    val error: String? = null,
)

/**
 * Shared ViewModel for the home screen.
 *
 * Fetches the home section layout, then concurrently resolves each section's
 * items. Used by both Android and Android TV home screens.
 */
class HomeViewModel(
    private val sectionRepository: SectionRepository,
    private val mediaActions: MediaActionsCoordinator,
    // Track B: offline home cache. Defaults to no-op so commonMain/tests stay
    // network-only; the Android platform module binds a Room-backed cache.
    private val homeCache: HomeCachePort = NoOpHomeCachePort,
    // Track B: local optimistic user-state, overlaid onto cards so an offline
    // mark-watched/favorite shows immediately instead of a stale cached badge.
    private val userItemState: UserItemStatePort = NoOpUserItemStatePort,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadSections()
    }

    fun loadSections() {
        viewModelScope.launch {
            // Stale-while-revalidate: serve the cached home instantly (offline-
            // capable), then refresh from the network below.
            val cached = homeCache.getCachedHome()
            if (cached != null && cached.sections.isNotEmpty()) {
                val overlaid = overlayLocalState(cached.sections)
                _uiState.update { it.copy(isLoading = false, sections = overlaid, error = null) }
            } else {
                _uiState.update { it.copy(isLoading = true, error = null) }
            }
            fetchSections()
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }
            fetchSections()
            _uiState.update { it.copy(isRefreshing = false) }
        }
    }

    /**
     * Overlay local optimistic watched/favorite onto card user_state (local non-null
     * wins; watchlist stays server — no local projection). Makes an offline mutation
     * show immediately instead of the stale server snapshot baked into a cached card.
     */
    private suspend fun overlayLocalState(sections: List<ResolvedSection>): List<ResolvedSection> {
        val ids = sections.flatMap { section -> section.items.map { it.contentId } }.distinct()
        if (ids.isEmpty()) return sections
        val local: Map<String, LocalContentState> = userItemState.localContentStates(ids)
        if (local.isEmpty()) return sections
        return sections.map { section ->
            section.copy(
                items = section.items.map { item ->
                    val ls = local[item.contentId] ?: return@map item
                    val base = item.userState ?: MediaItemUserState()
                    item.copy(
                        userState = base.copy(
                            played = ls.watched ?: base.played,
                            isFavorite = ls.favorite ?: base.isFavorite,
                        ),
                    )
                },
            )
        }
    }

    private suspend fun fetchSections() {
        // Whether we already have something to show (cached or prior fetch) — if a
        // refresh fails we keep it rather than replacing it with a blocking error.
        val hadSections = _uiState.value.sections.isNotEmpty()
        when (val result = sectionRepository.getHomeSections()) {
            is ApiResult.Success -> {
                val sections = result.data.sections
                // `/home/sections` already returns each section with its items
                // hydrated inline — identical to the per-section `/items` payload
                // (progress + user_state included). Use them directly instead of
                // re-fetching every section: the previous fan-out was an N+1
                // re-downloading data already in hand. Defensive fallback resolves
                // only sections the server left un-inlined (older deployments / a
                // section type that reports a non-zero total but ships no items).
                val needsFetch = sections.filter { it.items.isEmpty() && it.totalCount > 0 }
                val resolvedPairs: List<Pair<ResolvedSection, Boolean>> = if (needsFetch.isEmpty()) {
                    sections.map { it to true }
                } else {
                    val byId = needsFetch.map { section ->
                        viewModelScope.async {
                            section.id to when (val itemsResult = sectionRepository.getHomeSectionItems(section.id)) {
                                is ApiResult.Success -> {
                                    // The response carries items either nested under
                                    // `section` or as a sibling top-level `items` list.
                                    // Honor both — using only `.section` silently drops
                                    // a successful refetch that returned items at the top
                                    // level, leaving the section empty and filtered out.
                                    val data = itemsResult.data
                                    val responseSection = data.section
                                    val hydrated = when {
                                        responseSection != null && responseSection.items.isNotEmpty() ->
                                            responseSection
                                        responseSection != null && responseSection.totalCount == 0 ->
                                            responseSection
                                        responseSection != null && data.items.isNotEmpty() ->
                                            responseSection.copy(items = data.items)
                                        data.items.isNotEmpty() ->
                                            section.copy(items = data.items)
                                        else -> null
                                    }
                                    if (hydrated != null) hydrated to true else section to false
                                }
                                else -> section to false
                            }
                        }
                    }.awaitAll().toMap()
                    sections.map { section -> byId[section.id] ?: (section to true) }
                }
                val resolved = resolvedPairs.map { it.first }.filter { it.items.isNotEmpty() }
                // Don't persist a partially-resolved home over a good cached one.
                val fullyResolved = resolvedPairs.all { it.second }

                // Cache the RAW server sections (snapshot), but display with the
                // local optimistic overlay applied.
                if (fullyResolved) {
                    homeCache.cacheHome(resolved)
                }
                val overlaid = overlayLocalState(resolved)
                _uiState.update {
                    // Only replace what's shown when the fetch fully resolved (or there
                    // was nothing yet) — a partial refresh must not clobber a good Home.
                    if (fullyResolved || !hadSections) {
                        it.copy(isLoading = false, sections = overlaid, error = null)
                    } else {
                        it.copy(isLoading = false, error = null)
                    }
                }
            }
            is ApiResult.Error -> {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        // Keep cached/prior sections on a failed refresh; only block
                        // with an error when there's nothing to show.
                        error = if (hadSections) null else result.message.ifBlank { "Failed to load home sections" },
                    )
                }
            }
            is ApiResult.NetworkError -> {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = if (hadSections) null else "Network error. Check your connection.",
                    )
                }
            }
        }
    }

    // -- Card context-menu actions --

    /**
     * Toggle watched state for an item, optimistically updating user state on
     * the matching [SectionItem]s. On failure the optimistic update is rolled
     * back. Continue Watching / In Progress sections are refreshed on success
     * so the server-side resolution (e.g. marking a series clears its CW row)
     * reflects in the UI.
     */
    fun setWatched(itemId: String, watched: Boolean) {
        val previous = _uiState.value.sections
        _uiState.update { state -> state.copy(sections = state.sections.mapItem(itemId) { it.withPlayed(watched) }) }
        viewModelScope.launch {
            when (mediaActions.setWatched(itemId, watched)) {
                is ApiResult.Success -> refresh()
                else -> _uiState.update { it.copy(sections = previous) }
            }
        }
    }

    fun toggleFavorite(itemId: String, favorite: Boolean) {
        val previous = _uiState.value.sections
        _uiState.update { state -> state.copy(sections = state.sections.mapItem(itemId) { it.withFavorite(favorite) }) }
        viewModelScope.launch {
            if (mediaActions.toggleFavorite(itemId, favorite) !is ApiResult.Success) {
                _uiState.update { it.copy(sections = previous) }
            }
        }
    }

    fun toggleWatchlist(itemId: String, inWatchlist: Boolean) {
        val previous = _uiState.value.sections
        _uiState.update { state -> state.copy(sections = state.sections.mapItem(itemId) { it.withWatchlist(inWatchlist) }) }
        viewModelScope.launch {
            if (mediaActions.toggleWatchlist(itemId, inWatchlist) !is ApiResult.Success) {
                _uiState.update { it.copy(sections = previous) }
            }
        }
    }

    /**
     * Removes an item from the home Continue Watching row. Optimistically
     * removes it from any continue-watching / in-progress section and rolls
     * back on failure.
     */
    fun dismissContinueWatching(itemId: String, progressUpdatedAt: String) {
        val previous = _uiState.value.sections
        _uiState.update { state ->
            state.copy(
                sections = state.sections.map { section ->
                    if (section.sectionType == "continue_watching" || section.sectionType == "in_progress") {
                        section.copy(items = section.items.filterNot { it.contentId == itemId })
                    } else {
                        section
                    }
                }.filter { it.items.isNotEmpty() }
            )
        }
        viewModelScope.launch {
            if (mediaActions.dismissContinueWatching(itemId, progressUpdatedAt) !is ApiResult.Success) {
                _uiState.update { it.copy(sections = previous) }
            }
        }
    }
}

private fun List<ResolvedSection>.mapItem(
    itemId: String,
    transform: (SectionItem) -> SectionItem,
): List<ResolvedSection> = map { section ->
    if (section.items.none { it.contentId == itemId }) section
    else section.copy(items = section.items.map { if (it.contentId == itemId) transform(it) else it })
}

private fun SectionItem.withPlayed(played: Boolean): SectionItem =
    copy(userState = (userState ?: MediaItemUserState()).copy(played = played))

private fun SectionItem.withFavorite(favorite: Boolean): SectionItem =
    copy(userState = (userState ?: MediaItemUserState()).copy(isFavorite = favorite))

private fun SectionItem.withWatchlist(inWatchlist: Boolean): SectionItem =
    copy(userState = (userState ?: MediaItemUserState()).copy(inWatchlist = inWatchlist))
