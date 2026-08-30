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
import kotlin.time.TimeSource

data class HomeUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val sections: List<ResolvedSection> = emptyList(),
    /**
     * Whether [sections] is the whole picture, or rows are still arriving.
     *
     * Surfaces that restore focus by identity need this: while hydration is
     * still filling rows, a launch row can simply be absent, and "absent" has
     * to mean "not here YET" rather than "gone" — otherwise focus is driven to
     * the nearest survivor, which is a card the viewer never opened.
     *
     * Defaults true because a caller that does not know is describing a
     * finished list; only a partial publish sets it false.
     */
    val sectionsFullyResolved: Boolean = true,
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
    private val diagnostics: HomeDiagnosticsObserver = HomeDiagnosticsObserver.None,
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
     * Bumped by every fetch, checked before any of them publishes.
     *
     * loadSections(), refresh() and refreshFromRealtime() can all be in flight
     * at once — a resume observer fires while an initial load is still
     * running — and each captures hadSections BEFORE its network call. Without
     * ordering, an older partial response lands after a newer complete one,
     * replaces good sections and marks them not fully resolved, which now also
     * tells the TV's focus restoration to keep waiting for rows that already
     * arrived.
     */
    private var fetchGeneration = 0

    /**
     * Debounced realtime refetch: quiet (no spinner) and single-flight —
     * an in-flight realtime or manual refresh already delivers the fresh
     * sections, so overlapping signals are dropped rather than raced.
     */
    fun refreshFromRealtime() {
        if (realtimeRefreshInFlight || _uiState.value.isRefreshing) {
            diagnostics.completed(
                HomeLoadObservation(
                    trigger = HomeLoadTrigger.REALTIME,
                    source = HomeLoadSource.NETWORK,
                    outcome = HomeLoadOutcome.SKIPPED,
                    durationMs = 0,
                    sectionCount = _uiState.value.sections.size,
                    duplicateSectionKeyCount = _uiState.value.sections.duplicateSectionKeyCount(),
                    duplicateItemRowCount = _uiState.value.sections.duplicateItemRowCount(),
                ),
            )
            return
        }
        realtimeRefreshInFlight = true
        viewModelScope.launch {
            try {
                fetchSections(HomeLoadTrigger.REALTIME)
            } finally {
                realtimeRefreshInFlight = false
            }
        }
    }

    fun loadSections() {
        viewModelScope.launch {
            // Stale-while-revalidate: serve the cached home instantly (offline-
            // capable), then refresh from the network below.
            // Captured BEFORE the cached read, which suspends: a refresh can
            // start and publish fresh sections while we are in there, and
            // overlaying the cache on top would put stale rows back on screen.
            val bootstrapGeneration = fetchGeneration
            val cacheStarted = TimeSource.Monotonic.markNow()
            val cached = homeCache.getCachedHome()
            if (fetchGeneration != bootstrapGeneration) {
                diagnostics.completed(
                    HomeLoadObservation(
                        trigger = HomeLoadTrigger.INITIAL,
                        source = HomeLoadSource.CACHE,
                        outcome = HomeLoadOutcome.SUPERSEDED,
                        durationMs = cacheStarted.elapsedNow().inWholeMilliseconds,
                        sectionCount = cached?.sections?.size ?: 0,
                        duplicateSectionKeyCount = cached?.sections?.duplicateSectionKeyCount() ?: 0,
                        duplicateItemRowCount = cached?.sections?.duplicateItemRowCount() ?: 0,
                    ),
                )
                fetchSections(HomeLoadTrigger.INITIAL)
                return@launch
            }
            diagnostics.completed(
                HomeLoadObservation(
                    trigger = HomeLoadTrigger.INITIAL,
                    source = HomeLoadSource.CACHE,
                    outcome = if (cached != null && cached.sections.isNotEmpty()) {
                        HomeLoadOutcome.HIT
                    } else {
                        HomeLoadOutcome.MISS
                    },
                    durationMs = cacheStarted.elapsedNow().inWholeMilliseconds,
                    sectionCount = cached?.sections?.size ?: 0,
                    duplicateSectionKeyCount = cached?.sections?.duplicateSectionKeyCount() ?: 0,
                    duplicateItemRowCount = cached?.sections?.duplicateItemRowCount() ?: 0,
                ),
            )
            if (cached != null && cached.sections.isNotEmpty()) {
                val overlaid = overlayLocalState(cached.sections)
                _uiState.update { it.copy(isLoading = false, sections = overlaid, error = null) }
            } else {
                _uiState.update { it.copy(isLoading = true, error = null) }
            }
            fetchSections(HomeLoadTrigger.INITIAL)
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }
            val generation = fetchSections(HomeLoadTrigger.MANUAL_REFRESH)
            // Only the newest fetch may clear the flag. A superseded refresh
            // clearing it hides the spinner while a newer fetch is still
            // running, and re-opens refreshFromRealtime's single-flight gate so
            // it fires a redundant request.
            if (generation == fetchGeneration) {
                _uiState.update { it.copy(isRefreshing = false) }
            }
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

    /**
     * Runs one home fetch and returns the generation it ran as, so callers can
     * tell whether their own work is still the newest before acting on it.
     */
    private suspend fun fetchSections(trigger: HomeLoadTrigger): Int {
        val requestIdentityGeneration = identityTransitions.generation.value
        val cacheWriteLease = HomeCacheWriteLease(requestIdentityGeneration)
        // Whether we already have something to show (cached or prior fetch) — if a
        // refresh fails we keep it rather than replacing it with a blocking error.
        val generation = ++fetchGeneration
        val hadSections = _uiState.value.sections.isNotEmpty()
        val networkStarted = TimeSource.Monotonic.markNow()
        var observationReported = false
        fun report(outcome: HomeLoadOutcome, sections: List<ResolvedSection> = emptyList()) {
            if (observationReported) return
            observationReported = true
            diagnostics.completed(
                HomeLoadObservation(
                    trigger = trigger,
                    source = HomeLoadSource.NETWORK,
                    outcome = outcome,
                    durationMs = networkStarted.elapsedNow().inWholeMilliseconds,
                    sectionCount = sections.size,
                    duplicateSectionKeyCount = sections.duplicateSectionKeyCount(),
                    duplicateItemRowCount = sections.duplicateItemRowCount(),
                ),
            )
        }
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
                // Superseded while in flight: a newer fetch has already
                // answered, so this reply describes a home nobody is looking at.
                if (generation != fetchGeneration) {
                    report(HomeLoadOutcome.SUPERSEDED, sections)
                    return generation
                }
                val resolved = hydration.sections
                // Don't persist a partially-resolved home over a good cached one.
                val fullyResolved = hydration.fullyResolved

                // Cache the RAW server sections (snapshot), but display with the
                // local optimistic overlay applied.
                if (
                    fullyResolved &&
                    // A superseded fetch must not write its sections to the
                    // cache either: the next cold start would serve them.
                    generation == fetchGeneration &&
                    requestIdentityGeneration == identityTransitions.generation.value
                ) {
                    homeCache.cacheHome(resolved, cacheWriteLease)
                }
                val overlaid = overlayLocalState(resolved)
                // Checked AGAIN, after the cache write and the overlay. Both
                // suspend, and a newer fetch can complete and publish during
                // either — so a check taken before them proves only that this
                // reply was current when it arrived, not that it still is when
                // it finally writes.
                if (generation != fetchGeneration) {
                    report(HomeLoadOutcome.SUPERSEDED, resolved)
                    return generation
                }
                report(
                    outcome = if (fullyResolved) HomeLoadOutcome.SUCCESS else HomeLoadOutcome.PARTIAL,
                    sections = resolved,
                )
                _uiState.update {
                    // Only replace what's shown when the fetch fully resolved (or there
                    // was nothing yet) — a partial refresh must not clobber a good Home.
                    if (fullyResolved || !hadSections) {
                        it.copy(
                            isLoading = false,
                            sections = overlaid,
                            error = null,
                            sectionsFullyResolved = fullyResolved,
                        )
                    } else {
                        // The partial result is discarded and the previous, good
                        // sections stay on screen — so the flag keeps describing
                        // THOSE, which were complete when they were published.
                        it.copy(isLoading = false, error = null)
                    }
                }
            }
            is ApiResult.Error -> {
                // A superseded fetch's failure is not this home's failure.
                if (generation != fetchGeneration) {
                    report(HomeLoadOutcome.SUPERSEDED)
                    return generation
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        // Keep cached/prior sections on a failed refresh; only block
                        // with an error when there's nothing to show.
                        error = if (hadSections) null else result.message.ifBlank { "Failed to load home sections" },
                    )
                }
                report(HomeLoadOutcome.API_ERROR)
            }
            is ApiResult.NetworkError -> {
                if (generation != fetchGeneration) {
                    report(HomeLoadOutcome.SUPERSEDED)
                    return generation
                }
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = if (hadSections) null else "Network error. Check your connection.",
                    )
                }
                report(HomeLoadOutcome.NETWORK_ERROR)
            }
        }
        return generation
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

private fun List<ResolvedSection>.duplicateSectionKeyCount(): Int =
    size - distinctBy(ResolvedSection::id).size

private fun List<ResolvedSection>.duplicateItemRowCount(): Int =
    count { section -> section.items.size != section.items.distinctBy(SectionItem::contentId).size }

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
