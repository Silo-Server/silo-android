package org.siloserver.silo.viewmodel

import kotlinx.coroutines.flow.stateIn

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
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.repository.port.NoOpHomeCachePort
import org.siloserver.silo.repository.port.NoOpUserItemStatePort
import org.siloserver.silo.repository.port.UserItemStatePort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

data class HomeUiState(
    val membershipReadWitnesses: Set<org.siloserver.silo.repository.MembershipActions.Intent> = emptySet(),
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
    // mark-watched shows immediately instead of a stale cached badge.
    private val userItemState: UserItemStatePort = NoOpUserItemStatePort,
    // Live-home accelerator (Apple realtime-updates spec). Null keeps
    // commonMain/tests network-only; the apps inject the shared coordinator.
    private val homeRealtime: org.siloserver.silo.repository.HomeRealtimeCoordinator? = null,
    private val identityTransitions: IdentityTransitionBarrier = DefaultIdentityTransitionBarrier(),
    private val diagnostics: HomeDiagnosticsObserver = HomeDiagnosticsObserver.None,
    private val catalogLanguage: org.siloserver.silo.domain.settings.CatalogLanguageRevision? = null,
    /**
     * Whether Home starts on screen. Android TV keeps one Home for the whole
     * shell, which can come back on another tab, so it starts hidden and the
     * screens that show its rows report [onShown] and [onHidden]; the phone
     * never reports and stays shown.
     */
    startShown: Boolean = true,
    /** Home layout saves from the Home Sections editor; each one re-reads Home in the new order. */
    private val homeLayoutChanges: org.siloserver.silo.repository.HomeLayoutChanges? = null,
) : ViewModel() {

    private var displayedOwner: AuthScopeSnapshot? = null

    /**
     * Realtime and language refreshes run only while Home is on screen; one
     * that comes while it is hidden waits in [realtimeRefreshPending]. A full
     * read of a large Home takes tens of megabytes, and on Android TV the
     * player covering Home holds up to half the heap.
     */
    private var shown = startShown

    private suspend fun mayPublish(owner: AuthScopeSnapshot, generation: Int): Boolean {
        val valid = sectionRepository.isHomeAuthorityCurrent(owner)
        if (generation != fetchGeneration || !currentCoroutineContext().isActive) return false
        if (!valid) {
            displayedOwner = null
            _uiState.update { it.copy(sections = emptyList(), membershipReadWitnesses = emptySet(), isLoading = false, isRefreshing = false) }
        }
        return valid
    }

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = kotlinx.coroutines.flow.combine(_uiState, mediaActions.memberships.actions) { state, actions ->
        var sections = state.sections
        actions.values.filter { it.baseline != null && it.baseline !in state.membershipReadWitnesses && mediaActions.memberships.current(it.intent) }.forEach { action ->
            sections = sections.mapItem(action.intent.key.itemId) {
                if (action.intent.key.kind == org.siloserver.silo.repository.port.MembershipPort.Kind.FAVORITE)
                    it.withFavorite(action.baseline!!.present) else it.withWatchlist(action.baseline!!.present)
            }
        }
        state.copy(sections = sections)
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, HomeUiState())

    init {
        loadSections()
        catalogLanguage?.let { language ->
            viewModelScope.launch {
                // A metadata-language change re-localizes every card: re-read
                // quietly. Unlike refreshFromRealtime this doesn't wait for a
                // fetch already in flight, which still asked for the old
                // language. A hidden Home re-reads when it is shown; that
                // change is held back before collectLatest, so it never
                // cancels a read that may be the one filling Home.
                language.revision.drop(1).filter {
                    if (!shown) realtimeRefreshPending = true
                    shown
                }.collectLatest {
                    val generation = fetchSections(HomeLoadTrigger.REALTIME)
                    if (generation == fetchGeneration) {
                        _uiState.update { it.copy(isRefreshing = false) }
                        runPendingRealtimeRefresh()
                    }
                }
            }
        }
        homeRealtime?.let { coordinator ->
            viewModelScope.launch {
                coordinator.refreshSignals.collect { refreshFromRealtime() }
            }
        }
        homeLayoutChanges?.let { changes ->
            viewModelScope.launch {
                // A saved row order or visibility re-reads Home quietly, as a
                // realtime update does: at once while Home is shown, else when
                // it is shown again.
                changes.revision.drop(1).collect { refreshFromRealtime() }
            }
        }
    }

    /**
     * Home's rows are on screen. A refresh that waited while they were hidden
     * runs on [runPendingRealtimeRefresh] or the next [refreshFromRealtime].
     */
    fun onShown() {
        shown = true
    }

    /**
     * Home's rows left the screen. Realtime and language refreshes wait for
     * [onShown], and a realtime refresh still reading is dropped and waits too,
     * unless Home is still empty: that read may be the one that fills it.
     */
    fun onHidden() {
        shown = false
        if (realtimeRefreshInFlight && !_uiState.value.isLoading) {
            realtimeRefreshJob?.cancel()
            realtimeRefreshInFlight = false
            realtimeRefreshPending = true
        }
    }

    private var realtimeRefreshInFlight = false
    private var realtimeRefreshJob: Job? = null

    /** Bumped per realtime refresh, so a dropped one cannot clear its successor's in-flight flag. */
    private var realtimeRefreshRun = 0

    /**
     * A realtime signal arrived while a fetch was running. That fetch may have
     * been answered before the event behind the signal (an access change made
     * after the request went out), so one more realtime refresh runs when it
     * finishes. Also set by a realtime or language refresh that came while
     * Home was hidden.
     */
    private var realtimeRefreshPending = false

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
     * Debounced realtime refetch: quiet (no spinner) and single-flight.
     * Signals that arrive while a realtime or manual refresh is running are
     * not raced against it; they collapse into one follow-up refresh after it.
     * While Home is hidden they wait for it to be shown.
     */
    fun refreshFromRealtime() {
        if (!shown || realtimeRefreshInFlight || _uiState.value.isRefreshing) {
            realtimeRefreshPending = true
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
        // This read starts after every signal that is waiting.
        realtimeRefreshPending = false
        val run = ++realtimeRefreshRun
        realtimeRefreshJob = viewModelScope.launch {
            try {
                fetchSections(HomeLoadTrigger.REALTIME)
            } finally {
                if (run == realtimeRefreshRun) realtimeRefreshInFlight = false
            }
            runPendingRealtimeRefresh()
        }
    }

    /**
     * Runs the realtime refresh that is waiting, if any. While the first load
     * is still filling an empty Home it keeps waiting: that load runs it when
     * it ends, instead of a second full read beside it.
     */
    fun runPendingRealtimeRefresh() {
        if (!realtimeRefreshPending || _uiState.value.isLoading) return
        realtimeRefreshPending = false
        refreshFromRealtime()
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
            val owner = sectionRepository.captureHomeAuthority()
            if (owner == null) {
                if (fetchGeneration == bootstrapGeneration) fetchSections(HomeLoadTrigger.INITIAL)
                runPendingRealtimeRefresh()
                return@launch
            }
            if (!mayPublish(owner, bootstrapGeneration)) return@launch
            val cached = homeCache.getCachedHomeV2(owner)
            if (!mayPublish(owner, bootstrapGeneration)) {
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
                if (!mayPublish(owner, bootstrapGeneration)) return@launch
                displayedOwner = owner
                _uiState.update { it.copy(isLoading = false, sections = overlaid, error = null) }
            } else {
                _uiState.update { it.copy(isLoading = true, error = null) }
            }
            fetchSections(HomeLoadTrigger.INITIAL)
            // A refresh that waited during the first load runs after it, not beside it.
            runPendingRealtimeRefresh()
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
                runPendingRealtimeRefresh()
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
        val generation = ++fetchGeneration
        val owner = sectionRepository.captureHomeAuthority()
        if (generation != fetchGeneration || !currentCoroutineContext().isActive) return generation
        if (owner == null) {
            displayedOwner = null
            _uiState.update { it.copy(sections = emptyList(), membershipReadWitnesses = emptySet(), isLoading = false, isRefreshing = false, error = "Sign in to load Home.") }
            return generation
        }
        if (!mayPublish(owner, generation)) return generation
        if (displayedOwner != owner) {
            _uiState.update { it.copy(sections = emptyList(), membershipReadWitnesses = emptySet()) }
            displayedOwner = owner
        }
        val membershipWitnesses = mediaActions.memberships.readWitnesses()
        if (!mayPublish(owner, generation)) return generation
        val requestIdentityGeneration = identityTransitions.generation.value
        val artworkFingerprint = homeCache.artworkFingerprint()
        // Whether we already have something to show (cached or prior fetch) — if a
        // refresh fails we keep it rather than replacing it with a blocking error.
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
        // The first load may join the startup warmup's read; refreshes always read anew.
        // Only with no acknowledged membership change: a joined read may predate one, and
        // must not vouch for it through the witnesses captured above.
        val read = if (trigger == HomeLoadTrigger.INITIAL && membershipWitnesses.isEmpty()) {
            sectionRepository.getHomeSectionsJoiningRead(owner)
        } else {
            sectionRepository.getHomeSections(owner)
        }
        when (val result = read) {
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
                    if (!mayPublish(owner, generation)) ApiResult.Error(0, "superseded", "Home request changed.")
                    else sectionRepository.getHomeSectionItems(sectionId, owner)
                }
                // Superseded while in flight: a newer fetch has already
                // answered, so this reply describes a home nobody is looking at.
                if (!mayPublish(owner, generation)) {
                    report(HomeLoadOutcome.SUPERSEDED, sections)
                    return generation
                }
                val resolved = hydration.sections
                // Don't persist a partially-resolved home over a good cached one.
                val fullyResolved = hydration.fullyResolved

                val overlaid = overlayLocalState(resolved)
                // Checked AGAIN, after the overlay. It suspends, and a newer
                // fetch can complete and publish meanwhile — so a check taken
                // before it proves only that this reply was current when it
                // arrived, not that it still is when it finally writes.
                if (!mayPublish(owner, generation)) {
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
                            membershipReadWitnesses = membershipWitnesses,
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
                // Cache the RAW server sections (snapshot); the screen shows them
                // with the local overlay. Written after publishing: with 100-item
                // rows the snapshot is about 1 MB, and encoding it would hold the
                // rows back from the screen.
                if (
                    fullyResolved &&
                    // A superseded fetch must not write its sections to the
                    // cache either: the next cold start would serve them.
                    generation == fetchGeneration &&
                    requestIdentityGeneration == identityTransitions.generation.value
                ) {
                    homeCache.cacheHomeV2(resolved, owner, artworkFingerprint) {
                        generation == fetchGeneration && displayedOwner == owner
                    }
                }
            }
            is ApiResult.Error -> {
                // A superseded fetch's failure is not this home's failure.
                if (!mayPublish(owner, generation)) {
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
                if (!mayPublish(owner, generation)) {
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
        val writeIntent = mediaActions.beginWatched(itemId, watched)
        val previous = _uiState.value.sections
        _uiState.update { state -> state.copy(sections = state.sections.mapItem(itemId) { it.withPlayed(watched) }) }
        viewModelScope.launch {
            val result = mediaActions.performPersonalWrite(writeIntent)
            if (!mediaActions.isCurrent(writeIntent)) return@launch
            when (result) {
                // A hidden Home re-reads when it is shown, not beside the player.
                is ApiResult.Success -> if (shown) refresh() else realtimeRefreshPending = true
                else -> _uiState.update { it.copy(sections = previous) }
            }
        }
    }

    fun toggleFavorite(itemId: String, favorite: Boolean) {
        val intent = mediaActions.memberships.begin(itemId, org.siloserver.silo.repository.port.MembershipPort.Kind.FAVORITE, favorite)
        viewModelScope.launch { mediaActions.memberships.perform(intent) }
    }

    fun toggleWatchlist(itemId: String, inWatchlist: Boolean) {
        val intent = mediaActions.memberships.begin(itemId, org.siloserver.silo.repository.port.MembershipPort.Kind.WATCHLIST, inWatchlist)
        viewModelScope.launch { mediaActions.memberships.perform(intent) }
    }

    private val pendingDismissals = mutableSetOf<String>()

    /** Serializes the cached-Home rewrites, so one dismissal cannot write back a card another removed. */
    private val dismissalCacheWrites = Mutex()

    /** A dismissal succeeded and Home has not re-read since. */
    private var reloadAfterDismissals = false

    /** Removes a Continue Watching or Next Up card on the list [HomeDismissal] picks for it. */
    fun dismissFromHome(item: SectionItem) {
        val dismissal = HomeDismissal.of(item) ?: return
        dismissHomeProgressItem(dismissal.surface, item.contentId, dismissal.anchor)
    }

    fun dismissContinueWatching(itemId: String, progressUpdatedAt: String) =
        dismissHomeProgressItem("continue_watching", itemId, progressUpdatedAt)

    fun dismissNextUp(itemId: String, seriesId: String) =
        dismissHomeProgressItem("next_up", itemId, seriesId)

    private fun dismissHomeProgressItem(surface: String, itemId: String, anchor: String) {
        val owner = displayedOwner ?: return
        val generation = fetchGeneration
        val observed = _uiState.value.sections
        fun SectionItem.matches() = contentId == itemId &&
            (if (surface == "continue_watching") progressUpdatedAt == anchor else seriesId == anchor)
        fun List<ResolvedSection>.targetRows() = filter { it.isProgressRow() }.flatMap { section ->
            section.items.filter { it.contentId == itemId }.map { section.id to it }
        }
        val targets = observed.targetRows()
        val dismissed = targets.firstOrNull { it.second.matches() }?.second
        if (anchor.isBlank() || dismissed == null) return
        if (!pendingDismissals.add(itemId)) return
        // On /api/v2, dismissing an episode or a series drops the whole show,
        // so every card of that series leaves the progress rows with it.
        val droppedSeries = dismissed.droppedSeriesId()
        fun SectionItem.removed() = matches() ||
            (droppedSeries != null && (contentId == droppedSeries || seriesId == droppedSeries))
        fun observationCurrent() = generation == fetchGeneration && displayedOwner == owner &&
            _uiState.value.sections.targetRows() == targets
        viewModelScope.launch {
            try {
                if (!mayPublish(owner, generation) || !observationCurrent()) return@launch
                val result = sectionRepository.dismissHomeItem(surface, itemId, anchor, owner)
                // The server dropped the show even if this screen moved on while
                // the request ran, so re-read either way, for the same profile.
                if (result is ApiResult.Success && displayedOwner == owner && sectionRepository.isHomeAuthorityCurrent(owner)) {
                    reloadAfterDismissals = true
                }
                if (!mayPublish(owner, generation) || !observationCurrent()) return@launch
                if (result is ApiResult.Success) {
                    _uiState.update { state -> state.copy(sections = state.sections.removingFromProgressRows { it.removed() }) }
                    // The next cold start shows the cached Home before it re-reads;
                    // without this the dismissed cards come back until then.
                    dismissalCacheWrites.withLock {
                        val artworkFingerprint = homeCache.artworkFingerprint()
                        val cached = homeCache.getCachedHomeV2(owner) ?: return@withLock
                        homeCache.cacheHomeV2(cached.sections.removingFromProgressRows { it.removed() }, owner, artworkFingerprint) {
                            generation == fetchGeneration && displayedOwner == owner
                        }
                    }
                }
            } finally {
                pendingDismissals.remove(itemId)
                // Then re-read Home from the server, as Mark as Watched does; a
                // failed read keeps the local removal. It waits for the last
                // dismissal in flight, because a read that starts sooner would
                // make that one's observation stale and skip its removal.
                if (pendingDismissals.isEmpty() && reloadAfterDismissals) {
                    reloadAfterDismissals = false
                    if (shown) refresh() else realtimeRefreshPending = true
                }
            }
        }
    }

}

/** Rows built from playback state. Next Up appears as its own row and merged into Continue Watching. */
private fun ResolvedSection.isProgressRow() = sectionType in setOf("continue_watching", "in_progress", "next_up", "up_next")

/** Drops [isRemoved] cards from the progress rows; a row left empty goes too. */
private fun List<ResolvedSection>.removingFromProgressRows(isRemoved: (SectionItem) -> Boolean): List<ResolvedSection> =
    map { section ->
        if (section.isProgressRow()) section.copy(items = section.items.filterNot(isRemoved)) else section
    }.filter { it.items.isNotEmpty() }

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

/** Watched and unwatched both reset resume progress, as the server does. */
private fun SectionItem.withPlayed(played: Boolean): SectionItem =
    copy(userState = (userState ?: MediaItemUserState()).copy(played = played), positionSeconds = 0.0)

private fun SectionItem.withFavorite(favorite: Boolean): SectionItem =
    copy(userState = (userState ?: MediaItemUserState()).copy(isFavorite = favorite))

private fun SectionItem.withWatchlist(inWatchlist: Boolean): SectionItem =
    copy(userState = (userState ?: MediaItemUserState()).copy(inWatchlist = inWatchlist))
