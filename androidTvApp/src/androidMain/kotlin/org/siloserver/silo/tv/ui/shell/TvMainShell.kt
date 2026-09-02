package org.siloserver.silo.tv.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import androidx.navigation.NamedNavArgument
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.siloserver.silo.common.diagnostics.DiagnosticsFocusLogger
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.common.ui.components.ProfileAvatarRef
import org.siloserver.silo.tv.ui.theme.SiloOnSurface
import org.siloserver.silo.tv.ui.theme.DarkBackground
import org.siloserver.silo.common.network.ServerReachabilityMonitor
import org.siloserver.silo.common.network.ServerReachabilityStatus
import org.siloserver.silo.common.ui.components.profileAvatarDisplayText
import org.siloserver.silo.common.ui.components.avatarRef
import org.siloserver.silo.common.ui.components.rememberProfileAvatarImage
import org.siloserver.silo.common.ui.components.rememberProfileServerUrl
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.feature.CLIENT_WATCH_TOGETHER_SURFACE_ENABLED
import org.siloserver.silo.model.feature.RequestsFeatureStore
import org.siloserver.silo.model.personal.UserLibrary
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.repository.AuthRepository
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.repository.ProfileRepository
import org.siloserver.silo.tv.data.preferences.TvLibraryScopeStore
import org.siloserver.silo.tv.ui.components.TvCascadeSelector
import org.siloserver.silo.tv.ui.components.CascadeLibraryColumnWidth
import org.siloserver.silo.tv.ui.components.TvCascadeSelectorMaxPanelWidth
import org.siloserver.silo.tv.ui.components.TvForYouSelector
import org.siloserver.silo.tv.ui.components.TvCatalogEmptyState
import org.siloserver.silo.tv.ui.components.tvSkylinePanelChrome
import org.siloserver.silo.tv.ui.navigation.TvMainRoute
import org.siloserver.silo.tv.ui.navigation.TvRemovedMainRoutes
import org.siloserver.silo.tv.ui.screens.library.TvLibraryDetailScreen
import org.siloserver.silo.tv.ui.screens.library.TvLibraryTab
import org.siloserver.silo.tv.ui.screens.browse.TvBrowseScreen
import org.siloserver.silo.tv.ui.screens.calendar.TvCalendarScreen
import org.siloserver.silo.tv.ui.screens.collections.TvCollectionsScreen
import org.siloserver.silo.tv.ui.screens.home.TvHomeScreen
import org.siloserver.silo.tv.ui.screens.libraries.TvLibrariesScreen
import org.siloserver.silo.tv.ui.screens.personal.TvFavoritesScreen
import org.siloserver.silo.tv.ui.screens.personal.TvHistoryScreen
import org.siloserver.silo.tv.ui.screens.personal.TvWatchlistScreen
import org.siloserver.silo.tv.ui.screens.recommendations.TvRecommendationsScreen
import org.siloserver.silo.tv.ui.screens.recommendations.SavedListSelection
import org.siloserver.silo.tv.ui.screens.recommendations.TvForYouEntryRequest
import org.siloserver.silo.tv.ui.screens.recommendations.TvForYouEntryRequestSaver
import org.siloserver.silo.tv.ui.screens.requests.TvMyRequestsScreen
import org.siloserver.silo.tv.ui.screens.requests.TvRequestDetailScreen
import org.siloserver.silo.tv.ui.screens.requests.TvRequestsScreen
import org.siloserver.silo.tv.ui.screens.search.TvSearchScreen
import org.siloserver.silo.tv.ui.screens.settings.TvSettingsScreen
import org.siloserver.silo.tv.ui.screens.watchtogether.TvJoinCodeDialog
import org.siloserver.silo.tv.ui.screens.watchtogether.TvWatchTogetherMenuEntryDialog
import org.siloserver.silo.tv.ui.screens.watchtogether.TvWatchTogetherViewModel
import org.siloserver.silo.tv.ui.theme.TvSkyline
import org.siloserver.silo.tv.ui.util.visibleOnTv
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.tv.ui.focus.TvObservedFocusResult
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.viewmodel.HomeViewModel

/**
 * Frames the shell will wait for content to actually take focus after it
 * dismantles the previous owner.
 *
 * Three: the synchronous claim, then two more frames. On a 2 GB Amlogic box the
 * content group is routinely still composing when the claim arrives, and one
 * extra frame was already known to be too few for the Home row. Beyond this the
 * screen genuinely has nothing focusable and retrying cannot help.
 */
private const val CONTENT_HANDOFF_ATTEMPTS = 3

/**
 * Main authenticated TV shell. Mirrors `TVMainTabView` on tvOS: a content
 * `NavHost` with a `TvTopMenuBar` overlay that hosts Search / Home / Libraries
 * / For You + the profile dropdown. Settings, Collections, Favorites,
 * Watchlist, and History are not first-class menu items — they're reachable
 * from the Settings screen (opened via the profile menu) and remain navigable
 * by route inside the same NavHost so deep links keep working.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TvMainShell(
    returnToManageServers: Boolean = false,
    onManageServersReturnFocusConsumed: () -> Unit = {},
    onManageServers: () -> Unit,
    onOpenDiagnosticsReport: (reportId: String) -> Unit,
    onOpenItemDetail: (contentId: String) -> Unit,
    onOpenItemDetailSelection: (
        contentId: String,
        seasonNumber: Int?,
        episodeContentId: String?,
    ) -> Unit,
    onOpenLibraryCollectionDetail: (
        libraryId: Int,
        collectionId: String,
        title: String,
        libraryType: String,
    ) -> Unit,
    onOpenCollectionDetail: (collectionId: String, title: String) -> Unit,
    onSignedOut: () -> Unit,
    onSwitchProfile: () -> Unit,
    onSwitchServer: () -> Unit,
    onPairDevice: () -> Unit,
    onPlayItem: (contentId: String, type: String?, resumePositionSeconds: Double?) -> Unit,
    onOpenWatchTogether: (RoomSnapshot) -> Unit,
    onOpenPersonDetail: (personId: Long) -> Unit,
) {
    val nestedNav = rememberNavController()
    val currentEntry by nestedNav.currentBackStackEntryAsState()

    val authRepository: AuthRepository = koinInject()
    val sectionRepository: org.siloserver.silo.repository.SectionRepository = koinInject()
    val personalDataRepository: PersonalDataRepository = koinInject()
    val profileRepository: ProfileRepository = koinInject()
    val reachabilityMonitor: ServerReachabilityMonitor = koinInject()
    val requestsFeatureStore: RequestsFeatureStore = koinInject()
    val metadataAiFeatureStore: org.siloserver.silo.model.feature.MetadataAiFeatureStore = koinInject()
    val serverRegistry: ServerRegistry = koinInject()
    val reachabilityState by reachabilityMonitor.state.collectAsState()
    val requestsEnabled by requestsFeatureStore.isEnabled.collectAsState()
    val activeServerEntry by serverRegistry.activeEntry.collectAsState()
    val tvLibraryScopeStore: TvLibraryScopeStore = koinInject()
    // One shell-scoped Home owner feeds both the Home screen and General's
    // Home Sections editor. A Settings-scoped second instance could be empty
    // while the already-visible Home instance held the populated rows.
    val homeViewModel: HomeViewModel = koinViewModel(key = "tv-main-home")
    val serverUrl = rememberProfileServerUrl()
    val watchTogetherViewModel = koinViewModel<TvWatchTogetherViewModel>()
    val watchTogetherState by watchTogetherViewModel.uiState.collectAsState()
    val currentWatchTogetherRoom by watchTogetherViewModel.currentRoom.collectAsState()
    var watchTogetherEntryOpen by rememberSaveable { mutableStateOf(false) }
    var watchTogetherJoinOpen by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(watchTogetherState.result) {
        val room = watchTogetherState.result ?: return@LaunchedEffect
        watchTogetherViewModel.consumeResult()
        watchTogetherEntryOpen = false
        watchTogetherJoinOpen = false
        onOpenWatchTogether(room)
    }

    // The raw list of libraries visible to this profile on TV, sorted by the
    // server's sort order (ebook-like libraries filtered out by visibleOnTv).
    // This drives both `visibleRoots` and per-type scope resolution.
    // Gates the snap-to-Home redirect below: while libraries are still loading
    // `visibleRoots` is only Home + Calendar, so a restored/deep-linked
    // `main/movies` route must NOT be treated as "type has no libraries" yet.
    var librariesLoaded by remember { mutableStateOf(false) }
    val libraries by produceState(
        initialValue = emptyList<UserLibrary>(),
        personalDataRepository,
    ) {
        when (val result = personalDataRepository.listUserLibraries()) {
            is ApiResult.Success ->
                value = result.data.visibleOnTv().sortedBy { it.sortOrder }
            is ApiResult.Error,
            is ApiResult.NetworkError -> Unit
        }
        // Mark loaded even on error (we've attempted) so the redirect can run;
        // an empty list then legitimately means "no libraries for this profile".
        librariesLoaded = true
    }

    // Skyline tab set (§3.1): Home + present library-type tabs + Calendar.
    // tvOS parity: the Audiobooks tab is opt-in via Settings > General
    // (hidden by default); the store is device+profile local.
    var showAudiobooksTab by remember { mutableStateOf(false) }
    // Gates the snap-to-Home redirect below (alongside librariesLoaded) so a
    // restored/deep-linked main/audiobooks route isn't ejected before this
    // async DataStore read has landed.
    var showAudiobooksTabResolved by remember { mutableStateOf(false) }
    // The store exposes no Flow and Settings is an in-shell NavHost route (so no
    // ON_RESUME fires when the user backs out of it), which is why a one-shot
    // read left the tab bar stale after toggling the setting. Re-read on every
    // shell route change — a cheap DataStore lookup — so toggling Settings >
    // "Show Audiobooks tab" takes effect on return, without recreating the shell.
    LaunchedEffect(libraries, currentEntry?.destination?.route) {
        showAudiobooksTab = runCatching { tvLibraryScopeStore.getShowAudiobooksTab() }.getOrDefault(false)
        showAudiobooksTabResolved = true
    }
    val visibleRoots = remember(libraries, showAudiobooksTab) {
        visibleTvRoots(libraries, showAudiobooks = showAudiobooksTab)
    }

    // In-session scope/pill selections per library type. Scope selections are
    // also persisted via TvLibraryScopeStore; pill selections are session-only
    // (Stage 4 wires the cascade into these). Persistently composed.
    val scopeSelections: SnapshotStateMap<TvLibraryTabType, Int> = remember { mutableStateMapOf() }
    // Saveable, not merely remembered: opening a collection (or any outer
    // route) takes the whole shell out of composition, and the nested nav
    // restores the Movies/Series tab on Back but a plain remember has lost the
    // pill — so Collections landed back on Recommended, reading as "Back went
    // Home". Scope selections survive via TvLibraryScopeStore already.
    val pillSelections: SnapshotStateMap<TvLibraryTabType, TvLibraryPill> = rememberSaveable(
        saver = listSaver(
            save = { map -> map.entries.map { listOf(it.key.name, it.value.name) } },
            restore = { saved ->
                mutableStateMapOf<TvLibraryTabType, TvLibraryPill>().apply {
                    saved.forEach { (type, pill) ->
                        runCatching { put(TvLibraryTabType.valueOf(type), TvLibraryPill.valueOf(pill)) }
                    }
                }
            },
        ),
    ) { mutableStateMapOf() }
    // Monotonic per-type "section request" nonce, bumped on every commitScope so
    // re-committing the same section pill still re-applies (see TvLibraryDetailScreen).
    val sectionRequestNonces: SnapshotStateMap<TvLibraryTabType, Int> = remember { mutableStateMapOf() }

    // Resolved active library per type. resolvedLibrary is suspend, so resolve
    // it off-composition in a LaunchedEffect keyed on (libraries, scopeSelections)
    // and publish into this state map. Composition only ever reads the map.
    val resolvedLibraries: SnapshotStateMap<TvLibraryTabType, UserLibrary> =
        remember { mutableStateMapOf() }
    LaunchedEffect(libraries, scopeSelections.toMap()) {
        TvLibraryTabType.entries.forEach { type ->
            val ofType = libraries.filter { type.matches(it) }
            val selectedId = scopeSelections[type]
            val resolved = selectedId?.let { id -> ofType.firstOrNull { it.id == id } }
                ?: tvLibraryScopeStore.resolvedLibrary(type, libraries)
            if (resolved != null) {
                resolvedLibraries[type] = resolved
            } else {
                resolvedLibraries.remove(type)
            }
        }
    }
    val activeLibrary: (TvLibraryTabType) -> UserLibrary? = { type -> resolvedLibraries[type] }

    val currentRoute = currentEntry?.destination?.route ?: firstTvRoute()
    var calendarFocusHandoffPending by remember(currentRoute) {
        mutableStateOf(currentRoute == TvMainRoute.Calendar.route)
    }

    LaunchedEffect(currentRoute) {
        if (currentRoute != TvMainRoute.Calendar.route) {
            calendarFocusHandoffPending = false
        }
    }

    LaunchedEffect(activeServerEntry?.id, activeServerEntry?.profileId) {
        requestsFeatureStore.reset()
        requestsFeatureStore.refresh()
        metadataAiFeatureStore.reset()
        metadataAiFeatureStore.refresh()
    }

    val focusManager = LocalFocusManager.current
    val contentFocusRequester = remember { FocusRequester() }
    val homeFirstItemFocusRequester = remember { FocusRequester() }
    val homeFirstRowContainerFocusRequester = remember { FocusRequester() }
    // For You renders the same Skyline feed as Home, so it needs its own pair:
    // one feed's requesters cannot be attached in two compositions at once.
    val forYouFirstItemFocusRequester = remember { FocusRequester() }
    val forYouFirstRowContainerFocusRequester = remember { FocusRequester() }
    val searchInputFocusRequester = remember { FocusRequester() }
    var searchInputHasFocus by remember { mutableStateOf(false) }
    var searchBackToInputRequest by remember { mutableIntStateOf(0) }
    // Same failure as the bar handoff: Back asks the search field to take
    // focus and consumes the press. If the field never reports focus, every
    // Back repeats that forever and Search cannot be left. Records the attempt
    // so the next Back falls through to navigation instead.
    var searchBackToInputAttempted by remember { mutableStateOf(false) }
    // Opening an outer item-detail route pauses/removes this shell. Remember the
    // pending hand-back in the Main back-stack entry so it survives either form,
    // then re-enter the existing content focusRestorer when Main resumes.
    // `restoreContentAfterDetail` says a detail return is pending for ANY root
    // and gates the resume claim below so focus lands back inside content
    // instead of Compose's default search picking the top bar.
    // `detailReturnRoot` names which root it was, for the two decisions that
    // differ per root: the restorer's enter fallback and Home's retry ladder.
    // Stored as the route string because rememberSaveable takes primitives.
    var restoreContentAfterDetail by rememberSaveable { mutableStateOf(false) }
    var detailReturnRoot by rememberSaveable { mutableStateOf<String?>(null) }
    val restoreHomeContentAfterDetail = detailReturnRoot == TvMainRoute.Home.route
    val restoreForYouContentAfterDetail = detailReturnRoot == TvMainRoute.ForYou.route
    var suppressHomeRefreshAfterDetail by rememberSaveable { mutableStateOf(false) }
    var homeDetailReturnFocusState by remember { mutableStateOf(TvDetailReturnFocusState()) }
    var forYouDetailReturnFocusState by remember { mutableStateOf(TvDetailReturnFocusState()) }
    var detailReturnFocusRequest by remember { mutableIntStateOf(0) }
    var detailReturnNeedsRetry by remember { mutableStateOf(false) }
    // Attached (by the Home feed) to the exact card a detail page was launched
    // from, while that return is pending. Used as the content restorer's enter
    // fallback during the return resume so the synchronous claim below lands
    // straight on the launch card — the restorer's saved child node does not
    // survive the shell being removed for the outer detail route, and its
    // default enter could land a row below the launch card for a few frames.
    val homeDetailReturnCardFocusRequester = remember { FocusRequester() }
    val forYouDetailReturnCardFocusRequester = remember { FocusRequester() }
    // Skyline feeds only. The feed arms its launch-card requester at click time
    // (`detailReturnPending` in TvSkylineSectionFeed), so the node is attached
    // for the whole round trip and is a valid restorer target during the
    // synchronous resume claim below. Roots that render something else keep
    // Default enter, which lands inside content — all the claim owes.
    val detailReturnFallback = when {
        restoreHomeContentAfterDetail || homeDetailReturnFocusState.fallbackPending ->
            homeDetailReturnCardFocusRequester
        restoreForYouContentAfterDetail || forYouDetailReturnFocusState.fallbackPending ->
            forYouDetailReturnCardFocusRequester
        else -> FocusRequester.Default
    }
    // Whether focus currently sits anywhere inside the content group. Gates
    // the detail-return resume claim below: the Home feed's early restore
    // ladder usually re-focuses the launch card during the pop transition, and
    // re-requesting the content group after that re-runs its enter search and
    // yanks focus to a different card for a frame.
    var contentHasFocus by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        if (restoreContentAfterDetail) {
            // Claim the content group synchronously during ON_RESUME, before
            // Compose's default search can briefly settle on the Home tab —
            // but only when the feed hasn't already claimed it. Claim BEFORE
            // clearing the flag so the restorer fallback still points at the
            // launch card for this claim.
            detailReturnNeedsRetry = if (contentHasFocus) {
                false
            } else {
                runCatching { !contentFocusRequester.requestFocus() }.getOrDefault(true)
            }
            detailReturnFocusRequest++
            homeDetailReturnFocusState = beginTvDetailReturnRetryIfRoot(
                previousState = homeDetailReturnFocusState,
                isDetailReturnForRoot = restoreHomeContentAfterDetail,
                needsRetry = detailReturnNeedsRetry,
            )
            forYouDetailReturnFocusState = beginTvDetailReturnRetryIfRoot(
                previousState = forYouDetailReturnFocusState,
                isDetailReturnForRoot = restoreForYouContentAfterDetail,
                needsRetry = detailReturnNeedsRetry,
            )
            restoreContentAfterDetail = false
            detailReturnRoot = null
        }
        onPauseOrDispose { }
    }
    LaunchedEffect(detailReturnFocusRequest) {
        if (detailReturnFocusRequest == 0) return@LaunchedEffect
        // One-frame fallback for the disposed/recreated case where the Home row
        // requester was not attached during the synchronous resume claim.
        withFrameNanos { }
        if (detailReturnNeedsRetry) {
            runCatching { contentFocusRequester.requestFocus() }
        }
        homeDetailReturnFocusState = completeTvDetailReturnRetry(homeDetailReturnFocusState)
        forYouDetailReturnFocusState = completeTvDetailReturnRetry(forYouDetailReturnFocusState)
        // The detail-return ON_RESUME event has now passed and Home is stable;
        // future real resumes (playback/background) should refresh normally.
        suppressHomeRefreshAfterDetail = false
    }
    val openHomeItemDetail: (String) -> Unit = { contentId ->
        restoreContentAfterDetail = true
        detailReturnRoot = TvMainRoute.Home.route
        suppressHomeRefreshAfterDetail = true
        onOpenItemDetail(contentId)
    }
    val openHomeItemDetailSelection: (String, Int?, String?) -> Unit = { contentId, seasonNumber, episodeContentId ->
        restoreContentAfterDetail = true
        detailReturnRoot = TvMainRoute.Home.route
        suppressHomeRefreshAfterDetail = true
        onOpenItemDetailSelection(contentId, seasonNumber, episodeContentId)
    }
    val openForYouItemDetail: (String) -> Unit = { contentId ->
        restoreContentAfterDetail = true
        detailReturnRoot = TvMainRoute.ForYou.route
        onOpenItemDetail(contentId)
    }
    // Same generic hand-back for roots that render inside the shell but do not
    // attach a launch-card requester. Without this the shell never claims
    // content focus on the return resume, so focus settles wherever Compose's
    // default search lands — in practice the top bar — and the D-pad no longer
    // drives the rows the viewer was just in.
    val openContentItemDetail: (String) -> Unit = { contentId ->
        restoreContentAfterDetail = true
        // No launch-card requester for this root — clear any root left over
        // from an earlier return so the restorer does not reuse Home's.
        detailReturnRoot = null
        onOpenItemDetail(contentId)
    }
    // Collections open outer routes too, so they need the same hand-back:
    // without it the return resume left focus to Compose's default search
    // (the top bar), which then visibly hopped to the grid a beat later.
    val openLibraryCollectionDetail: (Int, String, String, String) -> Unit =
        { libraryId, collectionId, title, libraryType ->
            restoreContentAfterDetail = true
            detailReturnRoot = null
            onOpenLibraryCollectionDetail(libraryId, collectionId, title, libraryType)
        }
    val openCollectionDetail: (String, String) -> Unit = { collectionId, title ->
        restoreContentAfterDetail = true
        detailReturnRoot = null
        onOpenCollectionDetail(collectionId, title)
    }
    var contentUpFallback by remember { mutableStateOf<((Boolean) -> Boolean)?>(null) }
    // Feeds that registered the up-fallback slot, were superseded by a newer
    // feed, and are still awaiting their (now-stale) onDispose. Tracking them
    // lets us ignore that late dispose instead of nulling the entering feed's
    // registration.
    val supersededContentUpFallbacks = remember { mutableSetOf<(Boolean) -> Boolean>() }
    // Register/relinquish the single D-pad-Up fallback slot BY IDENTITY. A
    // NavHost composes the ENTERING feed (which registers its own lambda) before
    // it disposes the EXITING one, so a blind null-on-dispose would drop the new
    // screen's registration and lose the "Up scrolls into the previous row"
    // fallback after every feed↔feed tab switch. Each feed passes its own stable
    // lambda on both register and dispose; we only relinquish the slot for the
    // feed that still owns it, ignore a superseded feed's stale dispose, and let
    // a newly-entering feed take the slot (retiring the previous owner).
    val onContentUpFallback: (((Boolean) -> Boolean)?) -> Unit = remember {
        { incoming ->
            if (incoming != null) {
                when {
                    // The active owner re-announcing == its own onDispose: relinquish.
                    contentUpFallback === incoming -> contentUpFallback = null
                    // A superseded feed's late onDispose: it no longer owns the
                    // slot, so drop it from the watch set and leave the slot alone.
                    supersededContentUpFallbacks.remove(incoming) -> Unit
                    // A newly-entering feed: it takes the slot; the previous owner
                    // becomes superseded until its own dispose arrives.
                    else -> {
                        contentUpFallback?.let { supersededContentUpFallbacks.add(it) }
                        contentUpFallback = incoming
                    }
                }
            }
        }
    }

    // All shell focus/overlay state — the menu-refocus / profile-refocus /
    // panel-entry nudge counters, the menu-focused / profile-open / panel flags —
    // lives in ONE holder with named transitions and a derived `mode`, instead of
    // the loose bag of counters + booleans this file used to mutate from a dozen
    // sites (the source of a long "fix focus" tail). See [TvShellFocusState].
    val focusState = rememberTvShellFocusState()
    // Bumped when the user SELECTS a tab from the nav bar: the QA back-stack
    // model wants Select-from-menu to land on the top-left item (scrolled to
    // top), while ordinary content re-entry keeps the focusRestorer()'s
    // last-focused card.
    var contentFocusRequest by remember { mutableIntStateOf(0) }
    // Saveable, because the For You screen guards against replaying an entry
    // request with a SAVED "last applied sequence". A shell recreated with a
    // plain remember restarted the counter at 0 while the screen still held
    // the old high-water mark, so every dropdown pick after that (Watchlist,
    // Favorites, Recommendations) was silently ignored as already applied.
    var forYouEntryRequest by rememberSaveable(stateSaver = TvForYouEntryRequestSaver) {
        mutableStateOf(TvForYouEntryRequest())
    }

    // --- Skyline cascade panel host (Stage 4) ----------------------------------
    // Mirrors tvOS `TVMainTabView.persistentPanels`. The cascade overlays are
    // ALWAYS composed (one per visible library-type tab) and toggled by alpha +
    // focus-block; we never add/remove them reactively (Compose-for-TV focus
    // graph thrash). `focusState.openPanel` selects the active one;
    // `focusState.panelEntersFocus` flips true only once the user commits to
    // entering (d-pad-down or dwell+down) so a mere preview doesn't steal focus;
    // `focusState.panelFocusEntryToken` re-fires the selector's focus-entry
    // effect. `tabAnchors` carries each tab's measured coordinates.
    val tabAnchors = remember { mutableStateMapOf<TvTopMenuPanel, LayoutCoordinates>() }
    val panelScope = rememberCoroutineScope()

    val accountSnapshot by produceState(
        initialValue = TvAccountState(),
        authRepository,
        profileRepository,
        activeServerEntry,
    ) {
        val userResult = authRepository.getCurrentUser()
        if (userResult !is ApiResult.Success) {
            // Transient /me failure (offline blip, server restart): keep the
            // previous snapshot instead of blanking it — otherwise the account
            // header flickers out on every hiccup.
            return@produceState
        }
        val user = userResult.data
        val activeProfile = profileRepository.getActiveProfile()
        // The role belongs to the ACCOUNT, but this header shows a PROFILE's
        // name — so rendering it under a non-owner profile reads as "laura is
        // an admin" when laura is a household profile on an admin account.
        // That is the caption a viewer actually sees and the reason this was
        // reported. Show the role only where it is exercisable, which is the
        // primary profile. A non-owner profile gets NOTHING here — falling back
        // to the account username just captions laura's profile with the
        // owner's name, which conflates the two all over again. Profile name
        // and server is all a household profile needs to see.
        //
        // Nothing hangs on it — it is a caption, not a permission — but it is
        // the part that misleads.
        val subtitle = if (activeProfile?.isPrimary == true) {
            user?.role?.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
                ?: user?.username.orEmpty()
        } else {
            ""
        }
        value = TvAccountState(
            displayName = activeProfile?.name ?: user?.username ?: "Profile",
            // Ref + presigned URL travel together; the shell re-fetches this on
            // every profile switch / server change, which is also what hands the
            // avatar a freshly signed URL.
            avatar = activeProfile?.avatarRef() ?: ProfileAvatarRef.None,
            subtitle = subtitle,
            serverName = activeServerEntry?.displayName.orEmpty(),
        )
    }

    val selectedRoot by remember(currentRoute) {
        derivedStateOf { mapRouteToRoot(currentRoute) }
    }
    // Where an Up out of content lands on the bar. The selected root when there
    // is one; For You's dropdown children (Watchlist / Favorites) map to the
    // For You tab they were opened from — they are not tab roots (no highlight,
    // Back still pops), but Up from them must land on their tab, not on
    // whatever the geometric search picks (the Search icon, from the left edge).
    val selectedMenuFocusTarget = (selectedRoot ?: menuFocusRootForRoute(currentRoute))
        ?.let(TvTopMenuPanel::Root)

    // Which libraries actually HAVE collections — gates the cascade's
    // Collections pill so an empty library doesn't offer a dead-end section
    // (QA 2026-07-08: Movies → Collections → black 'No collections' page).
    // null = unknown (still loading) → pill stays visible.
    var librariesWithCollections by remember { mutableStateOf<Set<Int>?>(null) }
    LaunchedEffect(libraries) {
        if (libraries.isEmpty()) return@LaunchedEffect
        val ids = mutableSetOf<Int>()
        libraries.forEach { lib ->
            val result = runCatching { sectionRepository.getLibraryCollections(lib.id) }.getOrNull()
            if (result is ApiResult.Success && result.data.isNotEmpty()) ids += lib.id
        }
        librariesWithCollections = ids
    }

    val navigateToRoute: (String) -> Unit = { route ->
        if (route != currentRoute) {
            val startRoute = nestedNav.graph.startDestinationRoute
            if (route == startRoute && nestedNav.popBackStack(route, inclusive = false, saveState = true)) {
                // Home is the graph root, so "go Home" is a pop, never a push.
                // The bottom-nav idiom below (popUpTo(start){saveState} +
                // restoreState) is unsafe for the root itself: NavController
                // maps the state it just popped onto the popUpTo destination
                // when that destination has no saved-state key yet, and the
                // restoreState step then re-pushes exactly what was popped —
                // Home from a dropdown-opened For You (navigateToSecondary,
                // which never seeds Home's key) landed straight back on the
                // saved list. Tab→Home only worked because the earlier
                // navigate() to the tab had seeded Home's key with null.
                // saveState stays on so the popped tab/secondary route keeps
                // its scroll state for a later restoreState re-entry.
            } else {
                nestedNav.navigate(route) {
                    popUpTo(nestedNav.graph.startDestinationId) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
    }

    // Secondary routes (reached FROM another screen — Settings -> Favorites/
    // Watchlist/History/Collections/Requests, Requests -> MyRequests) push onto
    // the current route instead of flattening to the tab root,
    // so Back returns to the parent screen (e.g. Settings) rather than Home.
    val navigateToSecondary: (String) -> Unit = { route ->
        if (route != currentRoute) {
            nestedNav.navigate(route) {
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    // True when a requester actually took focus. `requestFocus()` throws rather
    // than returning false when its node has not composed yet, so each call has
    // to be guarded — and that guard is what used to swallow the failure whole.
    val claimContentFocus: (String) -> Boolean = { route ->
        when {
            route == TvMainRoute.Search.route ->
                runCatching { searchInputFocusRequester.requestFocus() }.getOrDefault(false)

            route == TvMainRoute.Home.route || route == TvMainRoute.Video.route -> {
                val content =
                    runCatching { contentFocusRequester.requestFocus() }.getOrDefault(false)
                val firstRow = runCatching {
                    homeFirstRowContainerFocusRequester.requestFocus()
                }.getOrDefault(false)
                content || firstRow
            }

            // Just request focus on the content group. The Box's
            // .focusRestorer() restores to the user's last-focused card
            // (e.g., card 7 of row 3) instead of slamming back to card 0.
            // The previous behavior also bumped `contentFocusRequest++`,
            // which fired LaunchedEffects in each screen that imperatively
            // re-focused index 0 — defeating the restorer. Initial focus
            // when a screen first loads is still handled by each screen's
            // own LaunchedEffect on its first data emission.
            else -> runCatching { contentFocusRequester.requestFocus() }.getOrDefault(false)
        }
    }

    val moveFocusToContent: (String) -> Unit = { route ->
        focusState.closeProfileMenuForContent()
        val homeLike = route == TvMainRoute.Home.route || route == TvMainRoute.Video.route
        // Home/Video keep claiming from the scope; every other route still
        // claims inline first, so the timing of a successful claim is exactly
        // what it was. What is new is the second chance: a claim that finds a
        // not-yet-composed requester now gets one more frame before it is given
        // up on — the same allowance the detail-return path already makes for
        // "the Home row requester was not attached during the synchronous
        // claim". On a 2 GB Amlogic box the content group is routinely still
        // composing when Down arrives from the menu bar, and the first claim
        // lands on nothing.
        //
        // The claim's return value is NOT proof that focus arrived — that is
        // the whole premise of the silent-focus-claim ratchet. It is only worth
        // skipping the observed pass when focus is demonstrably in content
        // already.
        if (!homeLike) claimContentFocus(route)
        panelScope.launch {
            val result = requestFocusUntilObserved(
                maxAttempts = CONTENT_HANDOFF_ATTEMPTS,
                awaitAttempt = { withFrameNanos { } },
                requestFocus = { claimContentFocus(route) },
                isFocused = { contentHasFocus },
            )
            // One more frame before giving up. The last attempt inspects focus
            // in the same frame it requested it, so a claim that WAS accepted
            // but reports asynchronously would otherwise look like a failure —
            // and the fallback below would yank focus off content that had just
            // taken it.
            if (result != TvObservedFocusResult.Focused) withFrameNanos { }
            if (result != TvObservedFocusResult.Focused && !contentHasFocus) {
                // Content has nothing focusable — a loading or empty rail, which
                // should not have to invent a focusable control just to satisfy
                // shell navigation. The shell dismantled the old focus owner, so
                // the shell owes a real successor: put it back on the bar rather
                // than leaving focus nowhere and the D-pad apparently dead.
                DiagnosticsFocusLogger.contentEntryFailed(route)
                // With a target, so dwell suppression actually applies: without
                // one the suppressed-button is null and the tab we just focused
                // reopens its preview a moment later.
                focusState.requestMenuFocus(
                    target = selectedMenuFocusTarget,
                    suppressDwellPreview = true,
                )
            }
        }
    }
    val openForYou: (SavedListSelection?) -> Unit = { selection ->
        // A dropdown pick is an explicit selection just like the tab itself:
        // end any detail-return protection, or its nonzero token makes the
        // Skyline feed swallow the entry focus bump and focus falls to the bar.
        forYouDetailReturnFocusState = resetTvDetailReturnFocus()
        forYouEntryRequest = forYouEntryRequest.next(selection)
        focusState.closePanel()
        navigateToSecondary(TvMainRoute.ForYou.route)
        moveFocusToContent(TvMainRoute.ForYou.route)
    }

    val onSelectRoot: (TvRootDestination) -> Unit = { dest ->
        val route = dest.toRoute()
        if (dest == TvRootDestination.ForYou) {
            // Same reason as Home below: an explicit tab selection ends the
            // detail-return protection instead of letting its nonzero token
            // keep suppressing the feed's first-card focus request.
            forYouDetailReturnFocusState = resetTvDetailReturnFocus()
            forYouEntryRequest = forYouEntryRequest.nextForTopLevelForYou()
        }
        if (dest == TvRootDestination.Home) {
            // Detail return deliberately preserves the card that opened the
            // detail page, but that protection must end when the user
            // explicitly selects Home from the bar. Otherwise its nonzero
            // token keeps suppressing Home's normal first-card focus request
            // for the rest of the shell session.
            homeDetailReturnFocusState = resetTvDetailReturnFocus()
        }
        if (dest == TvRootDestination.Calendar) {
            calendarFocusHandoffPending = true
        }
        // A dwell preview can still be open when Center commits For You (or a
        // library root). Close it without returning focus to the bar before the
        // content handoff, otherwise the overlay lingers and races page focus.
        focusState.closePanel()
        if (route != currentRoute) {
            navigateToRoute(route)
        }
        // Select-from-menu focuses the TOP-LEFT item (QA back-stack model),
        // overriding the restorer's remembered card: Home listens to
        // contentFocusRequest; library-type screens listen to their section
        // nonce (which re-applies the section AND refocuses the first row).
        contentFocusRequest++
        if (dest == TvRootDestination.Home) {
            // A focus request made from the bar toward a card is cancelled by
            // the content Box's focusRestorer (its `enter` restores the
            // remembered card and rolls the transaction back). Enter content
            // through the restorer here — the known-good hop — then the
            // feed's focusRequest effect walks focus to row 0 / card 0 one
            // scope per frame and scrolls the band to the top.
            runCatching { contentFocusRequester.requestFocus() }
        }
        if (dest is TvRootDestination.LibraryType) {
            sectionRequestNonces[dest.type] = (sectionRequestNonces[dest.type] ?: 0) + 1
        }
        if (dest == TvRootDestination.Calendar) {
            // Calendar owns an explicit shell-token -> active-filter focus
            // handoff. Requesting the parent content group here races that
            // handoff and restores Home's last descendant during the route
            // transition. Leave focus on the Calendar tab until its screen is
            // composed, then let TvCalendarScreen target the filter directly.
            focusState.closeProfileMenuForContent()
        } else if (dest == TvRootDestination.Home) {
            // Home's contentFocusRequest targets row 0 / card 0 directly.
            // Re-entering the parent content focusRestorer here would restore
            // the previously focused Home card and race that explicit reset.
            focusState.closeProfileMenuForContent()
        } else {
            moveFocusToContent(route)
        }
    }

    // Cascade panel choreography (tvOS openPanelPreview / openPanelAndEnter /
    // closePanel) now lives on [focusState]: `previewPanel` shows a panel without
    // taking focus, `enterPanel` flips focus into it, `closePanel` returns focus
    // to the originating bar tab.

    // Commit a scope (and optionally a section pill) from the cascade: persist
    // the library scope, record the session pill, navigate to that type's route,
    // close the panel, and move focus into the freshly-scoped content.
    val commitScope: (TvLibraryTabType, UserLibrary, TvLibraryPill) -> Unit = { type, library, pill ->
        scopeSelections[type] = library.id
        pillSelections[type] = pill
        // Bump the per-type section nonce so re-committing the SAME pill still
        // re-applies the section in TvLibraryDetailScreen (its LaunchedEffect
        // keys on the nonce, not just the section value).
        sectionRequestNonces[type] = (sectionRequestNonces[type] ?: 0) + 1
        panelScope.launch { tvLibraryScopeStore.setSelectedLibraryId(library.id, type) }
        val route = TvRootDestination.LibraryType(type).toRoute()
        if (route != currentRoute) {
            navigateToRoute(route)
        }
        // Close WITHOUT returning focus to the bar; commit wants content focus.
        focusState.closePanel()
        moveFocusToContent(route)
    }

    // Search is no longer a root tab — it's a trailing icon button. Navigate to
    // the (still-defined) Search route and drop focus into the search field.
    val onSearchPressed: () -> Unit = {
        if (TvMainRoute.Search.route != currentRoute) {
            navigateToRoute(TvMainRoute.Search.route)
        }
        moveFocusToContent(TvMainRoute.Search.route)
    }

    fun closeMenuAnd(action: () -> Unit): () -> Unit = {
        focusState.closeProfileMenuForContent()
        action()
    }

    // Scroll-driven visibility for the top menu bar. Mirrors Apple's
    // `TVTopMenuBar` hide-on-scroll behavior (spec A.1): scrolling content
    // down fades/translates the menu out; scrolling up restores it. The
    // animation lives entirely in `graphicsLayer` so layout doesn't reflow
    // beneath the menu while it transitions.
    // tvOS parity (QA 2026-07-08): the top bar NEVER hides — it floats over
    // every page and dims to 70% while focus is down in content
    // (TVTopMenuBar draws no band and has no scroll-hide). The old
    // scroll-away fade let content slide under the wordmark and left users
    // with an invisible bar to navigate back to.
    val menuVisibility = remember { Animatable(1f) }
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {}
    }

    // When focus is handed back to the top menu (Up at the top content row, or
    // closing the profile panel), the scroll-driven fade may have slid the menu
    // off-screen (visibility 0). Snap it back to fully visible first so we don't
    // focus an invisible bar. Guarded on >0 so it never runs on first compose.
    LaunchedEffect(focusState.menuFocusRequest) {
        if (focusState.menuFocusRequest > 0 && menuVisibility.value < 1f) {
            menuVisibility.animateTo(1f)
        }
    }

    // Belt-and-braces for the same bug via ANY focus path (geometric D-pad Up,
    // Back-to-menu, panel close): a bar that owns focus must be visible —
    // QA 2026-07-08 reported the calendar's scroll-fade leaving an invisible,
    // focused menu until the user scrolled all the way back up.
    LaunchedEffect(focusState.isMenuFocused) {
        if (focusState.isMenuFocused && menuVisibility.value < 1f) {
            menuVisibility.animateTo(1f)
        }
    }

    LaunchedEffect(currentRoute, visibleRoots, librariesLoaded, showAudiobooksTabResolved) {
        // Wait until libraries have actually loaded — before that `visibleRoots`
        // is just Home + Calendar, and a restored/deep-linked `main/movies` route
        // would be wrongly ejected even though that type exists. Likewise wait for
        // the async Audiobooks-tab read to resolve: until it lands `visibleRoots`
        // omits Audiobooks, so a restored `main/audiobooks` route would be ejected
        // to Home before the DataStore read completes.
        if (!librariesLoaded || !showAudiobooksTabResolved) return@LaunchedEffect
        // Only media-root tabs are eligible for the "tab no longer visible"
        // redirect. Non-tab routes (Settings, Favorites, Search, …) map
        // to null and must be left alone — otherwise navigating to Settings
        // would silently eject the user back to Home. If the selected root is a
        // LibraryType whose type has no libraries, snap to Home.
        val selected = mapRouteToRoot(currentRoute) ?: return@LaunchedEffect
        if (!selected.isVisibleIn(visibleRoots)) {
            navigateToRoute(firstTvRoute())
        }
    }

    fun handleShellBack(): Boolean {
        // Settings owns a two-stage Back model (detail pane -> selected rail
        // category -> Home), so its nested BackHandler must remain in charge.
        if (currentRoute == TvMainRoute.Settings.route) return false

        return when (focusState.onBack(
            onTabRoot = selectedRoot != null,
            menuFocusTarget = selectedMenuFocusTarget,
            onHome = selectedRoot == TvRootDestination.Home,
        )) {
            // Panel/dropdown already closed by onBack(): just consume.
            // onBack() closed the panel without claiming focus; put the viewer
            // back where they came from in the same press.
            TvShellBackAction.ClosePanel -> {
                // onBack() already put focus on the anchor tab with dwell
                // suppressed. Claiming content here fought that and lost —
                // focus ended up on the bar anyway, just without suppression.
                true
            }
            // Preview only: focus never left the bar, so dismissing it must not
            // move the viewer anywhere.
            TvShellBackAction.ClosePanelPreview -> true
            TvShellBackAction.CloseProfileMenu -> true
            // Content on a tab root: onBack() already routed focus to the bar's
            // selected tab -- just consume.
            TvShellBackAction.MoveFocusToMenu -> true
            // Bar focused: Home exits the app (fall through to the activity),
            // any other section goes Home with the bar still focused.
            TvShellBackAction.MenuBack -> {
                if (selectedRoot == TvRootDestination.Home) {
                    false
                } else {
                    navigateToRoute(firstTvRoute())
                    focusState.requestMenuFocus()
                    true
                }
            }
            // Secondary screens: pop the flat inner NavHost when possible;
            // otherwise let the activity-level callback finish the app.
            TvShellBackAction.DelegateToNav -> {
                if (currentRoute == TvMainRoute.Search.route &&
                    !searchInputHasFocus &&
                    !searchBackToInputAttempted
                ) {
                    searchBackToInputAttempted = true
                    searchBackToInputRequest += 1
                    true
                } else if (nestedNav.previousBackStackEntry != null) {
                    nestedNav.popBackStack()
                    true
                } else {
                    false
                }
            }
        }
    }

    // Android 16 no longer dispatches KEYCODE_BACK to apps targeting API 36.
    // Register the shell's stateful routing through the supported callback and
    // enable it only when this layer can consume the press, so child callbacks
    // and the activity fallback retain their existing priority.
    val pendingShellBackAction = tvShellBackAction(
        panelOpen = focusState.openPanel != null,
        profileMenuOpen = focusState.profileMenuOpen,
        menuFocused = focusState.isMenuFocused,
        onTabRoot = selectedRoot != null,
        // Must match what onBack() will decide, or the shell would decline the
        // press and let navigation take it while handleShellBack expected it.
        panelEntered = focusState.panelHasFocus,
        // Must match what onBack() will decide, or the shell declines a press
        // it would then have handled.
        barHandoffAttempted = focusState.barHandoffAttempted,
        onHome = selectedRoot == TvRootDestination.Home,
    )
    val shellHandlesBack = currentRoute != TvMainRoute.Settings.route && when (pendingShellBackAction) {
        TvShellBackAction.ClosePanel,
        TvShellBackAction.ClosePanelPreview,
        TvShellBackAction.CloseProfileMenu,
        TvShellBackAction.MoveFocusToMenu -> true
        TvShellBackAction.MenuBack -> selectedRoot != TvRootDestination.Home
        TvShellBackAction.DelegateToNav ->
            (
                currentRoute == TvMainRoute.Search.route &&
                    !searchInputHasFocus &&
                    !searchBackToInputAttempted
                ) ||
                nestedNav.previousBackStackEntry != null
    }
    // NavHost installs its own predictive-back callback before composing the
    // active destination. Put the shell callback inside each destination so
    // it registers after Navigation, but before screen-level dialogs and
    // overlays. That preserves the intended priority: screen > shell > nav.
    val latestShellHandlesBack = rememberUpdatedState(shellHandlesBack)
    val latestHandleShellBack = rememberUpdatedState<() -> Unit>({ handleShellBack() })
    fun NavGraphBuilder.shellComposable(
        route: String,
        arguments: List<NamedNavArgument> = emptyList(),
        content: @Composable (NavBackStackEntry) -> Unit,
    ) {
        composable(route = route, arguments = arguments) { entry ->
            BackHandler(enabled = latestShellHandlesBack.value) {
                latestHandleShellBack.value()
            }
            content(entry)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Keep the key-event path as a pre-Android-16 remote/keyboard
            // fallback. API 36 Back presses arrive through BackHandler above.
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyUp &&
                    (ev.key == Key.Back || ev.key == Key.Escape)
                ) {
                    handleShellBack()
                } else {
                    false
                }
            },
    ) {
        // Content layer — full-bleed, no left rail reserve. Up-arrow inside the
        // content's preview key handler routes focus to the menu when the user
        // is at the top row.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(nestedScrollConnection)
                .onFocusChanged { contentHasFocus = it.hasFocus }
                .focusRequester(contentFocusRequester)
                // During a detail-return resume the restorer's saved child is
                // gone (the shell left composition), so fall back to the
                // active feed's launch-card requester; Default otherwise.
                .focusRestorer(detailReturnFallback)
                // Block any GEOMETRIC focus escape upward out of the content
                // group. Without this, moveFocus(Up) from the top content row
                // does a 2D search into the sibling top bar and lands on the
                // nearest button (the trailing profile avatar) — bypassing the
                // bar's `enter`/selected-tab routing. Cancelling the exit makes
                // the manual moveFocus(Up) below return false at the top row, so
                // the `!moved` branch routes to the bar's SELECTED tab via
                // menuFocusRequest++. Intra-content row moves don't hit `exit`.
                .focusProperties {
                    exit = { direction ->
                        if (direction == FocusDirection.Up) {
                            FocusRequester.Cancel
                        } else {
                            FocusRequester.Default
                        }
                    }
                }
                .focusGroup()
                .onPreviewKeyEvent { ev ->
                    when {
                        ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionUp -> {
                            val isRepeat = ev.nativeKeyEvent.repeatCount > 0
                            val contentHandledUp = contentUpFallback?.invoke(isRepeat)
                            if (contentHandledUp != null) {
                                if (shouldRequestMenuAfterContentUp(contentHandledUp, isRepeat)) {
                                    focusState.requestMenuFocusIfAvailable(
                                        selectedMenuFocusTarget,
                                        allowNullTarget = currentRoute == TvMainRoute.Search.route,
                                    )
                                }
                            } else {
                                // Try to move focus up inside content; if that
                                // fails (we're already on the top row), hand
                                // focus to the menu bar.
                                val moved = focusManager.moveFocus(FocusDirection.Up)
                                // `exit = Cancel` above only guards the level of
                                // the search that owns the focused row; from a
                                // control that sits directly in the screen (e.g.
                                // the Calendar day shelf) the 2D search still
                                // escapes into the bar and lands on whatever is
                                // geometrically nearest — the Search icon from
                                // the left edge. A move that left content is
                                // therefore treated exactly like a failed move:
                                // route to the selected tab.
                                val escapedContent = moved && !contentHasFocus
                                if (shouldRequestMenuAfterContentUp(moved && !escapedContent, isRepeat)) {
                                    focusState.requestMenuFocusIfAvailable(
                                        selectedMenuFocusTarget,
                                        allowNullTarget = currentRoute == TvMainRoute.Search.route,
                                    )
                                }
                            }
                            // Always consume: we performed the move (or routed
                            // to the menu) ourselves in the preview phase.
                            // Returning !moved let the default focus system run
                            // a second moveFocus(Up), skipping a row.
                            true
                        }
                        // Back/Escape is handled on the OUTER shell Box (below)
                        // so it fires regardless of whether focus is on content
                        // or on the top menu bar (a sibling of this content Box,
                        // not a descendant — so a handler here never sees Back
                        // while the menu is focused).
                        else -> false
                    }
                },
        ) {
            NavHost(
                navController = nestedNav,
                startDestination = firstTvRoute(),
                modifier = Modifier.fillMaxSize(),
                // Middle ground between the 700ms default and phone-snappy 200ms
                // for tab-content switches (matches TvPageFadeDurationMs = 500).
                enterTransition = { fadeIn(tween(500)) },
                exitTransition = { fadeOut(tween(500)) },
                popEnterTransition = { fadeIn(tween(500)) },
                popExitTransition = { fadeOut(tween(500)) },
            ) {
                shellComposable(TvMainRoute.Video.route) {
                    TvHomeScreen(
                        viewModel = homeViewModel,
                        onItemClick = openHomeItemDetail,
                        onItemDetailSelection = openHomeItemDetailSelection,
                        onPlayItem = onPlayItem,
                        onSeeAll = {
                            navigateToSecondary(TvMainRoute.Browse.route)
                            moveFocusToContent(TvMainRoute.Browse.route)
                        },
                        onOpenForYou = {
                            openForYou(null)
                        },
                        onOpenSettings = {
                            navigateToRoute(TvMainRoute.Settings.route)
                            moveFocusToContent(TvMainRoute.Settings.route)
                        },
                        onInitialContentFocus = { focusState.closeProfileMenuForContent() },
                        focusRequest = contentFocusRequest,
                        detailReturnFocusRequest = homeDetailReturnFocusState.requestId,
                        detailReturnCardFocusRequester = homeDetailReturnCardFocusRequester,
                        firstRowFocusRequester = homeFirstItemFocusRequester,
                        firstRowContainerFocusRequester = homeFirstRowContainerFocusRequester,
                        shouldRefreshOnResume = { !suppressHomeRefreshAfterDetail },
                        onContentUpFallbackChanged = onContentUpFallback,
                    )
                }
                shellComposable(TvMainRoute.Home.route) {
                    TvHomeScreen(
                        viewModel = homeViewModel,
                        onItemClick = openHomeItemDetail,
                        onItemDetailSelection = openHomeItemDetailSelection,
                        onPlayItem = onPlayItem,
                        onSeeAll = {
                            navigateToSecondary(TvMainRoute.Browse.route)
                            moveFocusToContent(TvMainRoute.Browse.route)
                        },
                        onOpenForYou = {
                            openForYou(null)
                        },
                        onOpenSettings = {
                            navigateToRoute(TvMainRoute.Settings.route)
                            moveFocusToContent(TvMainRoute.Settings.route)
                        },
                        onInitialContentFocus = { focusState.closeProfileMenuForContent() },
                        focusRequest = contentFocusRequest,
                        detailReturnFocusRequest = homeDetailReturnFocusState.requestId,
                        detailReturnCardFocusRequester = homeDetailReturnCardFocusRequester,
                        firstRowFocusRequester = homeFirstItemFocusRequester,
                        firstRowContainerFocusRequester = homeFirstRowContainerFocusRequester,
                        shouldRefreshOnResume = { !suppressHomeRefreshAfterDetail },
                        onContentUpFallbackChanged = onContentUpFallback,
                    )
                }
                shellComposable(TvMainRoute.Search.route) {
                    TvSearchScreen(
                        onResultClick = { item ->
                            openBrowseItem(
                                item = item,
                                onOpenItemDetail = onOpenItemDetail,
                                onOpenPersonDetail = onOpenPersonDetail,
                            )
                        },
                        onOpenRequestDetail = { mediaType, tmdbId ->
                            navigateToSecondary(TvMainRoute.RequestDetail(mediaType, tmdbId).route)
                        },
                        onOpenLibraryItem = onOpenItemDetail,
                        searchFieldFocusRequester = searchInputFocusRequester,
                        backToSearchFieldRequest = searchBackToInputRequest,
                        onSearchFieldFocusChanged = {
                            searchInputHasFocus = it
                            // The field answered; the outstanding attempt is settled.
                            if (it) searchBackToInputAttempted = false
                        },
                    )
                }
                shellComposable(TvMainRoute.Audio.route) {
                    TvLibrariesScreen(
                        onItemClick = openContentItemDetail,
                        onLibraryCollectionClick = openLibraryCollectionDetail,
                        onUserCollectionClick = openCollectionDetail,
                        onInitialContentFocus = { focusState.closeProfileMenuForContent() },
                    )
                }
                shellComposable(TvMainRoute.Libraries.route) {
                    TvLibrariesScreen(
                        onItemClick = openContentItemDetail,
                        onLibraryCollectionClick = openLibraryCollectionDetail,
                        onUserCollectionClick = openCollectionDetail,
                        onInitialContentFocus = { focusState.closeProfileMenuForContent() },
                    )
                }
                // Content-type tabs (Skyline §3.1). Each renders the library
                // content scoped to that type's active library. The full-screen
                // picker stays the switch mechanism this stage (TvLibrariesScreen
                // still hosts it for the legacy Libraries route); the cascade
                // selector arrives in Stage 4.
                shellComposable(TvMainRoute.Movies.route) {
                    TvLibraryTypeContent(
                        type = TvLibraryTabType.Movies,
                        library = activeLibrary(TvLibraryTabType.Movies),
                        emptyConfirmed = librariesLoaded && libraries.none { TvLibraryTabType.Movies.matches(it) },
                        selectedPill = pillSelections[TvLibraryTabType.Movies] ?: TvLibraryPill.Recommended,
                        sectionRequestNonce = sectionRequestNonces[TvLibraryTabType.Movies] ?: 0,
                        onItemClick = openContentItemDetail,
                        onLibraryCollectionClick = openLibraryCollectionDetail,
                        onUserCollectionClick = openCollectionDetail,
                        onInitialContentFocus = { focusState.closeProfileMenuForContent() },
                        onContentUpFallbackChanged = onContentUpFallback,
                    )
                }
                shellComposable(TvMainRoute.Series.route) {
                    TvLibraryTypeContent(
                        type = TvLibraryTabType.Series,
                        library = activeLibrary(TvLibraryTabType.Series),
                        emptyConfirmed = librariesLoaded && libraries.none { TvLibraryTabType.Series.matches(it) },
                        selectedPill = pillSelections[TvLibraryTabType.Series] ?: TvLibraryPill.Recommended,
                        sectionRequestNonce = sectionRequestNonces[TvLibraryTabType.Series] ?: 0,
                        onItemClick = openContentItemDetail,
                        onLibraryCollectionClick = openLibraryCollectionDetail,
                        onUserCollectionClick = openCollectionDetail,
                        onInitialContentFocus = { focusState.closeProfileMenuForContent() },
                        onContentUpFallbackChanged = onContentUpFallback,
                    )
                }
                shellComposable(TvMainRoute.Music.route) {
                    TvLibraryTypeContent(
                        type = TvLibraryTabType.Music,
                        library = activeLibrary(TvLibraryTabType.Music),
                        emptyConfirmed = librariesLoaded && libraries.none { TvLibraryTabType.Music.matches(it) },
                        selectedPill = pillSelections[TvLibraryTabType.Music] ?: TvLibraryPill.Recommended,
                        sectionRequestNonce = sectionRequestNonces[TvLibraryTabType.Music] ?: 0,
                        onItemClick = openContentItemDetail,
                        onLibraryCollectionClick = openLibraryCollectionDetail,
                        onUserCollectionClick = openCollectionDetail,
                        onInitialContentFocus = { focusState.closeProfileMenuForContent() },
                        onContentUpFallbackChanged = onContentUpFallback,
                    )
                }
                shellComposable(TvMainRoute.Audiobooks.route) {
                    TvLibraryTypeContent(
                        type = TvLibraryTabType.Audiobooks,
                        library = activeLibrary(TvLibraryTabType.Audiobooks),
                        emptyConfirmed = librariesLoaded && libraries.none { TvLibraryTabType.Audiobooks.matches(it) },
                        selectedPill = pillSelections[TvLibraryTabType.Audiobooks] ?: TvLibraryPill.Recommended,
                        sectionRequestNonce = sectionRequestNonces[TvLibraryTabType.Audiobooks] ?: 0,
                        onItemClick = openContentItemDetail,
                        onLibraryCollectionClick = openLibraryCollectionDetail,
                        onUserCollectionClick = openCollectionDetail,
                        onInitialContentFocus = { focusState.closeProfileMenuForContent() },
                        onContentUpFallbackChanged = onContentUpFallback,
                    )
                }
                shellComposable(TvMainRoute.ForYou.route) {
                    TvRecommendationsScreen(
                        onSavedListItemClick = openContentItemDetail,
                        onRecommendationItemClick = openForYouItemDetail,
                        onInitialContentFocus = { focusState.closeProfileMenuForContent() },
                        focusRequest = contentFocusRequest,
                        detailReturnFocusRequest = forYouDetailReturnFocusState.requestId,
                        detailReturnCardFocusRequester = forYouDetailReturnCardFocusRequester,
                        firstRowFocusRequester = forYouFirstItemFocusRequester,
                        firstRowContainerFocusRequester = forYouFirstRowContainerFocusRequester,
                        onContentUpFallbackChanged = onContentUpFallback,
                        entryRequest = forYouEntryRequest,
                    )
                }
                shellComposable(TvMainRoute.Requests.route) {
                    TvRequestsScreen(
                        onOpenLibraryItem = onOpenItemDetail,
                        onOpenMyRequests = { navigateToSecondary(TvMainRoute.MyRequests.route) },
                        onOpenRequestDetail = { mt, id ->
                            navigateToSecondary(TvMainRoute.RequestDetail(mt, id).route)
                        },
                        onInitialContentFocus = { focusState.closeProfileMenuForContent() },
                    )
                }
                shellComposable(TvMainRoute.MyRequests.route) {
                    TvMyRequestsScreen(
                        onOpenLibraryItem = onOpenItemDetail,
                        onOpenRequestDetail = { mt, id ->
                            navigateToSecondary(TvMainRoute.RequestDetail(mt, id).route)
                        },
                        onInitialContentFocus = { focusState.closeProfileMenuForContent() },
                    )
                }
                shellComposable(
                    route = TvMainRoute.RequestDetail.ROUTE,
                    arguments = listOf(
                        navArgument(TvMainRoute.RequestDetail.ARG_MEDIA_TYPE) { type = NavType.StringType },
                        navArgument(TvMainRoute.RequestDetail.ARG_TMDB_ID) { type = NavType.IntType },
                    ),
                ) { entry ->
                    TvRequestDetailScreen(
                        mediaType = entry.arguments?.getString(TvMainRoute.RequestDetail.ARG_MEDIA_TYPE).orEmpty(),
                        tmdbId = entry.arguments?.getInt(TvMainRoute.RequestDetail.ARG_TMDB_ID) ?: 0,
                        onBack = { if (nestedNav.previousBackStackEntry != null) nestedNav.popBackStack() },
                    )
                }
                shellComposable(TvMainRoute.Collections.route) {
                    TvCollectionsScreen(
                        onCollectionClick = openCollectionDetail,
                        onInitialContentFocus = { focusState.closeProfileMenuForContent() },
                    )
                }
                shellComposable(TvMainRoute.Watchlist.route) {
                    TvWatchlistScreen(
                        onItemClick = openContentItemDetail,
                        onInitialContentFocus = { focusState.closeProfileMenuForContent() },
                    )
                }
                shellComposable(TvMainRoute.Favorites.route) {
                    TvFavoritesScreen(
                        onItemClick = openContentItemDetail,
                        onInitialContentFocus = { focusState.closeProfileMenuForContent() },
                    )
                }
                shellComposable(TvMainRoute.History.route) {
                    TvHistoryScreen(
                        onItemClick = openContentItemDetail,
                        onInitialContentFocus = { focusState.closeProfileMenuForContent() },
                    )
                }
                shellComposable(TvMainRoute.Settings.route) {
                    TvSettingsScreen(
                        homeSectionsViewModel = homeViewModel,
                        onManageServers = onManageServers,
                        onOpenDiagnosticsReport = onOpenDiagnosticsReport,
                        initialManageServersFocus = returnToManageServers,
                        onManageServersReturnFocusConsumed = onManageServersReturnFocusConsumed,
                        onSignedOut = onSignedOut,
                        onSwitchProfile = onSwitchProfile,
                        onNavigateHome = {
                            navigateToRoute(firstTvRoute())
                        },
                        onInitialContentFocus = { focusState.closeProfileMenuForContent() },
                    )
                }
                shellComposable(TvMainRoute.Calendar.route) {
                    TvCalendarScreen(
                        onOpenItemDetailSelection = onOpenItemDetailSelection,
                        onInitialContentFocus = {
                            focusState.closeProfileMenuForContent()
                            calendarFocusHandoffPending = false
                        },
                        onMoveUpToMenu = {
                            focusState.requestMenuFocus(
                                TvTopMenuPanel.Root(TvRootDestination.Calendar),
                            )
                        },
                        focusRequest = contentFocusRequest,
                        onContentUpFallbackChanged = onContentUpFallback,
                    )
                }
                shellComposable(TvMainRoute.Browse.route) {
                    TvBrowseScreen(
                        onOpenItemDetail = openContentItemDetail,
                        onInitialContentFocus = { focusState.closeProfileMenuForContent() },
                    )
                }
                // ---- Removed route aliases (defensive) ---- see
                // [TvRemovedMainRoutes]. Registered, never rendered: each one
                // redirects into Settings so a back stack saved by a build that
                // still had the admin/session screens can be restored.
                for (removedRoute in TvRemovedMainRoutes) {
                    composable(
                        route = removedRoute,
                        // A pattern carrying a placeholder cannot be registered
                        // without the matching argument declared.
                        arguments = if ("{userId}" in removedRoute) {
                            listOf(
                                navArgument("userId") {
                                    type = NavType.StringType
                                    nullable = true
                                    defaultValue = null
                                },
                            )
                        } else {
                            emptyList()
                        },
                    ) {
                        LaunchedEffect(Unit) {
                            nestedNav.navigate(TvMainRoute.Settings.route) {
                                popUpTo(removedRoute) { inclusive = true }
                                launchSingleTop = true
                            }
                        }
                    }
                }
            }
        }

        // Menu overlay — content remains visible behind the transparent bar.
        TvTopMenuBar(
            selectedRoot = selectedRoot,
            destinations = visibleRoots,
            accountState = accountSnapshot,
            onSelectRoot = onSelectRoot,
            onSelectTab = { type ->
                // Enter/Select on a library-type tab jumps straight to that
                // type's Recommended content. Prefer a full scope commit (so it
                // lands on Recommended and closes any open preview panel); fall
                // back to a plain route nav if the active library hasn't
                // resolved yet.
                val activeLib = activeLibrary(type)
                if (activeLib != null) {
                    commitScope(type, activeLib, TvLibraryPill.Recommended)
                } else {
                    onSelectRoot(TvRootDestination.LibraryType(type))
                }
            },
            onSearchClick = onSearchPressed,
            onProfileClick = focusState::enterProfileMenu,
            onProfileDwell = focusState::previewProfileMenu,
            onProfileBlur = focusState::closeProfilePreview,
            onEnterProfileMenu = focusState::enterProfileMenu,
            onMoveDown = { moveFocusToContent(currentRoute) },
            isMenuFocused = focusState.isMenuFocused,
            // setMenuFocused(true) also clears any stale "entered" flag: focus on
            // a bar button means we're NOT inside a panel, so a geometric d-pad-Up
            // escape out of an entered panel can't leave it stuck (which would
            // freeze dwell preview-switching under the previously-entered tab).
            onMenuFocusChange = focusState::updateMenuFocused,
            // Settings is a full-screen surface (tvOS parity): the bar is
            // hidden AND unfocusable there, so Up at the top of the settings
            // rail simply holds instead of focusing an invisible menu. Back
            // still pops the route via DelegateToNav.
            isFocusSuppressed = focusState.isMenuFocusSuppressed ||
                calendarFocusHandoffPending ||
                currentRoute == TvMainRoute.Settings.route,
            focusRequest = focusState.menuFocusRequest,
            focusRequestTarget = focusState.menuFocusTarget,
            focusRequestSuppressesDwell = focusState.menuFocusSuppressesDwell,
            // Lets Back move focus to the anchor tab while the cascade is still
            // composed — removing it afterwards then has no focus to recover.
            onInstallAnchorFocus = { hook -> focusState.focusBarAnchorNow = hook },
            profileFocusRequest = focusState.profileFocusRequest,
            isSearchActive = currentRoute == TvMainRoute.Search.route,
            visibility = if (currentRoute == TvMainRoute.Settings.route) 0f else menuVisibility.value,
            openPanel = focusState.openPanel,
            onDwell = focusState::previewPanel,
            onEnterPanel = focusState::enterPanel,
            onTabAnchor = { panel, coords -> tabAnchors[panel] = coords },
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .zIndex(1f),
        )

        if (reachabilityState.status == ServerReachabilityStatus.Unreachable) {
            TvServerOfflinePill(
                onRetry = { panelScope.launch { reachabilityMonitor.retryNow() } },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 92.dp)
                    .zIndex(1.5f),
            )
        }

        // Persistent cascade overlays (tvOS `persistentPanels`): one Box per
        // visible library-type tab, ALWAYS in the tree. Inactive panels are
        // alpha-0 and focus-blocked; the active one fades in and accepts focus.
        // Positioned under their tab anchor, clamped to the safe-area X.
        val density = LocalDensity.current
        // The panel wraps its content (library column, plus the sections flyout
        // once revealed) up to this cap, and is left-anchored under its tab — so
        // a collapsed panel is just the library list and it grows rightward when
        // the flyout opens, instead of a fixed slab with dead space.
        val maxPanelWidthDp = TvCascadeSelectorMaxPanelWidth
        visibleRoots.forEach { dest ->
            if (dest is TvRootDestination.ForYou) {
                val panel = TvTopMenuPanel.Root(dest)
                val active = focusState.openPanel == panel
                val anchor = tabAnchors[panel]
                val panelAlpha by animateFloatAsState(
                    targetValue = if (active) 1f else 0f,
                    animationSpec = tween(durationMillis = if (active) 90 else 70),
                    label = "tvTopMenuForYouPanelAlpha",
                )
                Box(
                    modifier = Modifier
                        .absoluteOffset {
                            cascadePanelOffset(
                                anchor = anchor,
                                level1WidthPx = with(density) { CascadeLibraryColumnWidth.toPx() },
                                totalPanelWidthPx = with(density) { CascadeLibraryColumnWidth.toPx() },
                                safeAreaXPx = with(density) { TvSkyline.safeAreaX.toPx() },
                                panelTopPx = with(density) { TvSkyline.dropdownTopInset.toPx() },
                            )
                        }
                        .alpha(panelAlpha)
                        .focusProperties { canFocus = active }
                        .zIndex(2f),
                ) {
                    TvForYouSelector(
                        entersPanel = active && focusState.panelEntersFocus,
                        focusEntryToken = focusState.panelFocusEntryToken,
                        onWatchlist = {
                            openForYou(SavedListSelection.Watchlist)
                        },
                        onFavorites = {
                            openForYou(SavedListSelection.Favorites)
                        },
                        onRecommendations = {
                            openForYou(null)
                        },
                    )
                }
            }
            if (dest is TvRootDestination.LibraryType) {
                val panel = TvTopMenuPanel.Root(dest)
                val active = focusState.openPanel == panel
                val anchor = tabAnchors[panel]
                val panelAlpha by animateFloatAsState(
                    targetValue = if (active) 1f else 0f,
                    animationSpec = tween(durationMillis = if (active) 90 else 70),
                    label = "tvTopMenuCascadePanelAlpha",
                )
                Box(
                    modifier = Modifier
                        .absoluteOffset {
                            cascadePanelOffset(
                                anchor = anchor,
                                level1WidthPx = with(density) { CascadeLibraryColumnWidth.toPx() },
                                totalPanelWidthPx = with(density) { TvCascadeSelectorMaxPanelWidth.toPx() },
                                safeAreaXPx = with(density) { TvSkyline.safeAreaX.toPx() },
                                panelTopPx = with(density) { TvSkyline.dropdownTopInset.toPx() },
                            )
                        }
                        .widthIn(max = maxPanelWidthDp)
                        .alpha(panelAlpha)
                        .focusProperties { canFocus = active }
                        .zIndex(2f),
                ) {
                    TvCascadeSelector(
                        type = dest.type,
                        libraries = libraries.filter { dest.type.matches(it) },
                        currentScopeId = activeLibrary(dest.type)?.id,
                        libraryHasCollections = { id ->
                            librariesWithCollections?.contains(id) ?: true
                        },
                        selectedPill = pillSelections[dest.type] ?: TvLibraryPill.Recommended,
                        entersPanel = active && focusState.panelEntersFocus,
                        focusEntryToken = focusState.panelFocusEntryToken,
                        onCommitLibrary = { lib -> commitScope(dest.type, lib, TvLibraryPill.Recommended) },
                        onCommitSection = { lib, pill -> commitScope(dest.type, lib, pill) },
                        // Where focus actually is, which is what Back routing
                        // needs — the entry flag only records intent.
                        onPanelFocusChanged = { focusState.onPanelFocusChanged(it) },
                        modifier = Modifier,
                    )
                }
            }
        }

        if (focusState.profileMenuOpen) {
            TvProfileDropdown(
                accountState = accountSnapshot,
                entersMenu = focusState.profileMenuEntered,
                focusEntryToken = focusState.profileMenuFocusEntryToken,
                onSwitchProfile = closeMenuAnd(onSwitchProfile),
                onWatchlist = closeMenuAnd {
                    navigateToSecondary(TvMainRoute.Watchlist.route)
                    moveFocusToContent(TvMainRoute.Watchlist.route)
                },
                onFavorites = closeMenuAnd {
                    navigateToSecondary(TvMainRoute.Favorites.route)
                    moveFocusToContent(TvMainRoute.Favorites.route)
                },
                onHistory = closeMenuAnd {
                    navigateToSecondary(TvMainRoute.History.route)
                    moveFocusToContent(TvMainRoute.History.route)
                },
                showRequests = requestsEnabled,
                onRequests = closeMenuAnd {
                    navigateToSecondary(TvMainRoute.Requests.route)
                    moveFocusToContent(TvMainRoute.Requests.route)
                },
                showWatchTogether = CLIENT_WATCH_TOGETHER_SURFACE_ENABLED,
                onWatchTogether = {
                    focusState.closeProfileMenuForContent()
                    watchTogetherViewModel.clearError()
                    watchTogetherEntryOpen = true
                },
                onSettings = {
                    // Keep focus on the dropdown row through the route fade.
                    // TvSettingsScreen closes the menu only after its General
                    // rail requester is composed and ready, avoiding a flash of
                    // the Home row restored by the shared content container.
                    navigateToRoute(TvMainRoute.Settings.route)
                },
                onSwitchServer = closeMenuAnd(onSwitchServer),
                onSignOut = closeMenuAnd(onSignedOut),
                onDismiss = { focusState.dismissProfileMenu() },
                modifier = Modifier
                    // The profile avatar now leads the *trailing* cluster, so the
                    // dropdown anchors at the bar's end edge, under the avatar.
                    .align(Alignment.TopEnd)
                    .padding(
                        top = TvTopMenuLayout.profileMenuTopInset,
                        end = TvTopMenuLayout.trailingInset,
                    )
                    .zIndex(2f),
            )
        }

        if (watchTogetherEntryOpen) {
            if (watchTogetherJoinOpen) {
                TvJoinCodeDialog(
                    isBusy = watchTogetherState.isBusy,
                    error = watchTogetherState.error,
                    onJoin = watchTogetherViewModel::joinRoom,
                    onDismiss = {
                        watchTogetherViewModel.clearError()
                        watchTogetherJoinOpen = false
                    },
                )
            } else {
                TvWatchTogetherMenuEntryDialog(
                    canResume = currentWatchTogetherRoom != null,
                    isBusy = watchTogetherState.isBusy,
                    error = watchTogetherState.error,
                    onResume = { watchTogetherViewModel.resumeCurrentRoom() },
                    onHost = { watchTogetherViewModel.createEmptyVoteRoom() },
                    onJoin = {
                        watchTogetherViewModel.clearError()
                        watchTogetherJoinOpen = true
                    },
                    onDismiss = {
                        watchTogetherViewModel.clearError()
                        watchTogetherEntryOpen = false
                        watchTogetherJoinOpen = false
                        focusState.dismissProfileMenu()
                    },
                )
            }
        }
    }
}

/**
 * Renders the library content for a content-type tab, scoped to that type's
 * currently-active [library]. Reuses [TvLibraryDetailScreen] as-is (the same
 * surface the Libraries tab shows for a single library). When no active library
 * has resolved yet (libraries still loading, or the type genuinely has none) we
 * show a quiet empty state rather than crashing. The Stage 4 cascade selector
 * will replace the in-screen full-screen picker as the switch mechanism.
 */
@Composable
private fun TvLibraryTypeContent(
    type: TvLibraryTabType,
    library: UserLibrary?,
    emptyConfirmed: Boolean,
    selectedPill: TvLibraryPill,
    sectionRequestNonce: Int,
    onItemClick: (contentId: String) -> Unit,
    onLibraryCollectionClick: (
        libraryId: Int,
        collectionId: String,
        title: String,
        libraryType: String,
    ) -> Unit,
    onUserCollectionClick: (collectionId: String, title: String) -> Unit,
    onInitialContentFocus: () -> Unit,
    onContentUpFallbackChanged: ((((Boolean) -> Boolean)?) -> Unit)? = null,
) {
    if (library == null) {
        // Only assert "no libraries" once loading has settled AND this type
        // genuinely has none ([emptyConfirmed]). While libraries are still
        // loading/resolving — e.g. the brief window when the shell re-enters
        // after backing out of detail/player — show a quiet background instead
        // of flashing the empty-state message.
        if (emptyConfirmed) {
            TvCatalogEmptyState(
                message = "No ${type.title} libraries available for this profile.",
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            )
        }
        return
    }
    // Key on the library id so switching the active library rebuilds the
    // detail screen (and its keyed ViewModel) cleanly instead of reusing stale
    // state from the previous library.
    key(library.id) {
        TvLibraryDetailScreen(
            libraryId = library.id,
            libraryTitle = library.name,
            libraryType = library.type,
            onItemClick = onItemClick,
            onCollectionClick = { collectionId, title, isUserCollection ->
                if (isUserCollection) {
                    onUserCollectionClick(collectionId, title)
                } else {
                    onLibraryCollectionClick(library.id, collectionId, title, library.type)
                }
            },
            onInitialContentFocus = onInitialContentFocus,
            initialSection = selectedPill.toLibraryTab(),
            sectionRequestNonce = sectionRequestNonce,
            onContentUpFallbackChanged = onContentUpFallbackChanged,
        )
    }
}

private fun openBrowseItem(
    item: BrowseItem,
    onOpenItemDetail: (contentId: String) -> Unit,
    onOpenPersonDetail: (personId: Long) -> Unit,
) {
    if (item.type.equals("person", ignoreCase = true)) {
        item.contentId.toLongOrNull()?.let(onOpenPersonDetail) ?: onOpenItemDetail(item.contentId)
    } else {
        onOpenItemDetail(item.contentId)
    }
}

/**
 * Maps an in-app route string to the corresponding top-menu destination, or
 * `null` when the route is not one of the media-root tabs. Non-tab routes
 * (Settings, Collections, Favorites, Watchlist, History, ForYou, …) are
 * legitimately navigable destinations reached from the profile menu / detail
 * flows; they must not be treated as the Video tab. Returning `null` keeps the
 * top bar from highlighting any tab and tells the redirect effect to leave the
 * user where they are instead of ejecting them to the first visible tab.
 */
private fun mapRouteToRoot(route: String): TvRootDestination? = when (route) {
    // Video/Audio/Libraries are legacy aliases kept harmless during the nav
    // alignment; Video maps to Home and the others to no specific tab now that
    // content is reached via the per-type tabs.
    TvMainRoute.Video.route,
    TvMainRoute.Home.route -> TvRootDestination.Home
    TvMainRoute.Movies.route -> TvRootDestination.LibraryType(TvLibraryTabType.Movies)
    TvMainRoute.Series.route -> TvRootDestination.LibraryType(TvLibraryTabType.Series)
    TvMainRoute.Music.route -> TvRootDestination.LibraryType(TvLibraryTabType.Music)
    TvMainRoute.Audiobooks.route -> TvRootDestination.LibraryType(TvLibraryTabType.Audiobooks)
    TvMainRoute.Calendar.route -> TvRootDestination.Calendar
    TvMainRoute.ForYou.route -> TvRootDestination.ForYou
    // Search maps to null so no top tab is highlighted (trailing icon).
    // Requests/MyRequests/Settings/Audio/Libraries are likewise non-tab.
    else -> null
}

/** Bar tab that owns a non-root route for content→bar Up (see selectedMenuFocusTarget). */
private fun menuFocusRootForRoute(route: String): TvRootDestination? = when (route) {
    TvMainRoute.Watchlist.route,
    TvMainRoute.Favorites.route -> TvRootDestination.ForYou
    else -> null
}

private fun TvRootDestination.toRoute(): String = when (this) {
    TvRootDestination.Home -> TvMainRoute.Home.route
    TvRootDestination.ForYou -> TvMainRoute.ForYou.route
    TvRootDestination.Calendar -> TvMainRoute.Calendar.route
    is TvRootDestination.LibraryType -> when (type) {
        TvLibraryTabType.Movies -> TvMainRoute.Movies.route
        TvLibraryTabType.Series -> TvMainRoute.Series.route
        TvLibraryTabType.Music -> TvMainRoute.Music.route
        TvLibraryTabType.Audiobooks -> TvMainRoute.Audiobooks.route
    }
}

/**
 * Maps a committed cascade [TvLibraryPill] to the library detail screen's
 * section tab. Browse variants stay distinct so the detail ViewModel can apply
 * their catalog request presets.
 */
private fun TvLibraryPill.toLibraryTab(): TvLibraryTab = when (this) {
    TvLibraryPill.Recommended -> TvLibraryTab.Recommended
    TvLibraryPill.Browse -> TvLibraryTab.Browse
    TvLibraryPill.Collections -> TvLibraryTab.Collections
    TvLibraryPill.Genres -> TvLibraryTab.Genres
    TvLibraryPill.Alphabet -> TvLibraryTab.Alphabet
    TvLibraryPill.RecentlyAdded -> TvLibraryTab.RecentlyAdded
    TvLibraryPill.Authors -> TvLibraryTab.Authors
    TvLibraryPill.Series -> TvLibraryTab.Series
}

/**
 * Top-left offset (in px) for a cascade panel: centered horizontally under its
 * tab [anchor] and clamped so neither edge crosses the safe-area X; vertically
 * just below the bar. Returns an offscreen offset when the anchor hasn't been
 * measured yet (the panel is alpha-0 in that case anyway).
 */
private fun cascadePanelOffset(
    anchor: LayoutCoordinates?,
    level1WidthPx: Float,
    totalPanelWidthPx: Float,
    safeAreaXPx: Float,
    panelTopPx: Float,
): IntOffset {
    if (anchor == null || !anchor.isAttached) {
        return IntOffset(-100_000, 0)
    }
    val rootWidthPx = anchor.findRootCoordinates().size.width.toFloat()
    // Center the level-1 library column under the tab, then clamp the entire
    // two-level cascade so the flyout stays inside the safe area.
    val anchorCenterX = anchor.positionInRoot().x + anchor.size.width / 2f
    val centeredX = anchorCenterX - level1WidthPx / 2f
    val maxX = (rootWidthPx - safeAreaXPx - totalPanelWidthPx).coerceAtLeast(safeAreaXPx)
    val clampedX = centeredX.coerceIn(safeAreaXPx, maxX)
    return IntOffset(clampedX.roundToInt(), panelTopPx.roundToInt())
}

/**
 * Anchored profile dropdown — fires from the avatar button on the top menu.
 * Faithful Compose-for-TV port of tvOS `TVProfileDropdown` (§5.8): the shared
 * Skyline panel chrome ([tvSkylinePanelChrome]) floats under the avatar on its
 * own shadow — NO full-screen page scrim. The panel is a focus group that
 * consumes input and focuses its first row on open; Back/Menu closes it and
 * returns focus to the avatar via [onDismiss].
 *
 * Row set + order mirrors tvOS: Switch Profile · Watchlist · Favorites ·
 * History · Requests (server-gated) · Watch Together (client-policy-gated) ·
 * Settings · Switch Server · Sign Out. Calendar is a top-level tab.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TvProfileDropdown(
    accountState: TvAccountState,
    entersMenu: Boolean,
    focusEntryToken: Int,
    onSwitchProfile: () -> Unit,
    onWatchlist: () -> Unit,
    onFavorites: () -> Unit,
    onHistory: () -> Unit,
    showRequests: Boolean,
    onRequests: () -> Unit,
    showWatchTogether: Boolean,
    onWatchTogether: () -> Unit,
    onSettings: () -> Unit,
    onSwitchServer: () -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(entersMenu, focusEntryToken) {
        if (entersMenu && focusEntryToken > 0) {
            runCatching { firstFocus.requestFocus() }
        }
    }

    Column(
        modifier = modifier
            .width(TvSkyline.profileMenuWidth)
            .tvSkylinePanelChrome()
            .padding(vertical = TvSkyline.profileMenuPanelVerticalPadding)
            .focusGroup()
            // No page scrim, so trap directional focus inside the dropdown —
            // arrows can't leak into the bar/content behind it; only Back closes.
            .focusProperties { exit = { FocusRequester.Cancel } }
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyUp &&
                    (ev.key == Key.Back || ev.key == Key.Escape)
                ) {
                    onDismiss()
                    true
                } else false
            },
        verticalArrangement = Arrangement.spacedBy(TvSkyline.profileMenuItemSpacing),
    ) {
        ProfileDropdownHeader(accountState)

        ProfileDropdownDivider()

        ProfileDropdownRow(
            label = "Switch Profile",
            icon = Icons.Filled.People,
            focusRequester = firstFocus,
            onClick = onSwitchProfile,
        )
        ProfileDropdownRow(label = "Watchlist", icon = Icons.Filled.Bookmark, onClick = onWatchlist)
        ProfileDropdownRow(label = "Favorites", icon = Icons.Filled.Favorite, onClick = onFavorites)
        ProfileDropdownRow(label = "History", icon = Icons.Filled.History, onClick = onHistory)
        if (showRequests) {
            ProfileDropdownRow(
                label = "Requests",
                icon = Icons.Filled.AutoAwesome,
                onClick = onRequests,
            )
        }
        if (showWatchTogether) {
            ProfileDropdownRow(
                label = "Watch Together",
                icon = Icons.Filled.People,
                onClick = onWatchTogether,
            )
        }

        ProfileDropdownDivider()

        ProfileDropdownRow(label = "Settings", icon = Icons.Filled.Settings, onClick = onSettings)
        ProfileDropdownRow(label = "Switch Server", icon = Icons.Filled.Dns, onClick = onSwitchServer)
        ProfileDropdownRow(
            label = "Sign Out",
            icon = Icons.AutoMirrored.Filled.Logout,
            onClick = onSignOut,
        )
    }
}

/** Avatar + display name + subtitle + server name header (tvOS §5.8). */
@Composable
private fun ProfileDropdownHeader(accountState: TvAccountState) {
    val avatarText = remember(accountState.avatar, accountState.displayName) {
        profileAvatarDisplayText(accountState.avatar, accountState.displayName)
    }
    val avatarImage = rememberProfileAvatarImage(accountState.avatar)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = TvSkyline.profileMenuHeaderHorizontalPadding,
                vertical = TvSkyline.profileMenuHeaderVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TvSkyline.profileMenuHeaderGap),
    ) {
        Box(
            modifier = Modifier
                .size(TvSkyline.profileMenuAvatarSize)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) {
            if (avatarImage != null) {
                ThumbhashImage(
                    url = avatarImage.url,
                    thumbhash = null,
                    contentDescription = accountState.displayName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    transparent = true,
                    cacheKey = avatarImage.cacheKey,
                    onError = avatarImage.onLoadFailed,
                )
            } else {
                Text(
                    text = avatarText,
                    color = SiloOnSurface,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = accountState.displayName,
                color = SiloOnSurface,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = TvSkyline.profileMenuHeaderTitleSize,
                    lineHeight = TvSkyline.profileMenuHeaderTitleLineHeight,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = listOf(accountState.subtitle, accountState.serverName)
                .filter { it.isNotBlank() }
                .joinToString("  ·  ")
            if (subtitle.isNotEmpty()) {
                Text(
                    text = subtitle.uppercase(),
                    color = SiloOnSurface.copy(alpha = 0.38f),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = TvSkyline.profileMenuHeaderSubtitleSize,
                        lineHeight = TvSkyline.profileMenuHeaderSubtitleLineHeight,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ProfileDropdownDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = TvSkyline.profileMenuDividerHorizontalPadding,
                vertical = TvSkyline.profileMenuDividerVerticalPadding,
            )
            .height(1.dp)
            .background(Color.White.copy(alpha = 0.10f)),
    )
}

@Composable
private fun TvServerOfflinePill(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pillShape = RoundedCornerShape(999.dp)
    Surface(
        modifier = modifier.widthIn(max = 460.dp),
        onClick = onRetry,
        shape = ClickableSurfaceDefaults.shape(pillShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF3A1F22).copy(alpha = 0.94f),
            contentColor = Color.White,
            focusedContainerColor = SiloOnSurface,
            focusedContentColor = DarkBackground,
            pressedContainerColor = SiloOnSurface,
            pressedContentColor = DarkBackground,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)), shape = pillShape),
            focusedBorder = Border(border = BorderStroke(1.dp, SiloOnSurface), shape = pillShape),
        ),
    ) {
        Text(
            text = "Offline mode - select to retry",
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Inverted-capsule dropdown row (tvOS §5.8 / cascade grammar): a leading icon
 * and label that invert to a solid [SiloOnSurface] fill with
 * [DarkBackground] content on focus, bare at rest.
 */
@Composable
private fun ProfileDropdownRow(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val contentColor = if (isFocused) DarkBackground else SiloOnSurface.copy(alpha = 0.9f)
    val rowShape = RoundedCornerShape(TvSkyline.profileMenuRowCornerRadius)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = TvSkyline.profileMenuRowOuterHorizontalPadding)
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it },
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(rowShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = SiloOnSurface,
            focusedContainerColor = SiloOnSurface,
            focusedContentColor = DarkBackground,
            pressedContainerColor = SiloOnSurface,
            pressedContentColor = DarkBackground,
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(border = BorderStroke(0.dp, Color.Transparent), shape = rowShape),
            focusedBorder = Border(border = BorderStroke(0.dp, Color.Transparent), shape = rowShape),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = TvSkyline.profileMenuRowContentHorizontalPadding,
                    vertical = TvSkyline.profileMenuRowContentVerticalPadding,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TvSkyline.profileMenuRowGap),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(TvSkyline.profileMenuRowIconSize),
            )
            Text(
                text = label,
                color = contentColor,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontSize = TvSkyline.profileMenuRowTextSize,
                    lineHeight = TvSkyline.profileMenuRowLineHeight,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
