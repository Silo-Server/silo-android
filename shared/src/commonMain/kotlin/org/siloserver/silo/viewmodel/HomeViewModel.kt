package org.siloserver.silo.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.domain.MediaActionsCoordinator
import org.siloserver.silo.model.catalog.MediaItemUserState
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.repository.SectionRepository
import org.siloserver.silo.repository.port.HomeCachePort
import org.siloserver.silo.repository.port.HomeCacheWriteLease
import org.siloserver.silo.repository.port.NoOpHomeCachePort
import org.siloserver.silo.repository.port.NoOpUserItemStatePort
import org.siloserver.silo.repository.port.UserItemStatePort
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
    // Live-home accelerator (Apple realtime-updates spec). Null keeps
    // commonMain/tests network-only; the apps inject the shared coordinator.
    private val homeRealtime: org.siloserver.silo.repository.HomeRealtimeCoordinator? = null,
    private val identityTransitions: IdentityTransitionBarrier = DefaultIdentityTransitionBarrier(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadSections()
        homeRealtime?.let { coordinator ->
            viewModelScope.launch {
                coordinator.refreshSignals.collect { refreshFromRealtime() }
            }
        }
    }

    private var realtimeRefreshInFlight = false

    /**
     * Debounced realtime refetch: quiet (no spinner) and single-flight —
     * an in-flight realtime or manual refresh already delivers the fresh
     * sections, so overlapping signals are dropped rather than raced.
     */
    fun refreshFromRealtime() {
        if (realtimeRefreshInFlight || _uiState.value.isRefreshing) return
        realtimeRefreshInFlight = true
        viewModelScope.launch {
            try {
                fetchSections()
            } finally {
                realtimeRefreshInFlight = false
            }
        }
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
     * Overlay local optimistic watched/favorite plus queued playback progress onto
     * cards. Local progress wins only when it is ahead of the server snapshot, so
     * an offline replay never makes the visible resume point move backwards.
     */
    private suspend fun overlayLocalState(sections: List<ResolvedSection>): List<ResolvedSection> {
        val ids = sections.flatMap { section -> section.items.map { it.contentId } }.distinct()
        if (ids.isEmpty()) return sections
        return applyLocalStateOverlay(
            sections = sections,
            contentStates = userItemState.localContentStates(ids),
            progressStates = userItemState.localPlaybackProgressForContent(ids),
        )
    }

    private suspend fun fetchSections() {
        val requestIdentityGeneration = identityTransitions.generation.value
        val cacheWriteLease = HomeCacheWriteLease(requestIdentityGeneration)
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
                val hydration = hydrateHomeSections(sections) { sectionId ->
                    sectionRepository.getHomeSectionItems(sectionId)
                }
                val resolved = hydration.sections
                // Don't persist a partially-resolved home over a good cached one.
                val fullyResolved = hydration.fullyResolved

                // Cache the RAW server sections (snapshot), but display with the
                // local optimistic overlay applied.
                if (
                    fullyResolved &&
                    requestIdentityGeneration == identityTransitions.generation.value
                ) {
                    homeCache.cacheHome(resolved, cacheWriteLease)
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
        dismissHomeProgressItem(itemId) {
            mediaActions.dismissContinueWatching(itemId, progressUpdatedAt)
        }
    }

    fun dismissNextUp(itemId: String, seriesId: String) {
        dismissHomeProgressItem(itemId) {
            mediaActions.dismissNextUp(itemId, seriesId)
        }
    }

    private fun dismissHomeProgressItem(
        itemId: String,
        dismiss: suspend () -> ApiResult<Unit>,
    ) {
        val previous = _uiState.value.sections
        _uiState.update { state ->
            state.copy(
                sections = state.sections.map { section ->
                    if (
                        section.sectionType == "continue_watching" ||
                        section.sectionType == "in_progress" ||
                        section.sectionType == "next_up" ||
                        section.sectionType == "up_next"
                    ) {
                        section.copy(items = section.items.filterNot { it.contentId == itemId })
                    } else {
                        section
                    }
                }.filter { it.items.isNotEmpty() }
            )
        }
        viewModelScope.launch {
            if (dismiss() !is ApiResult.Success) {
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
