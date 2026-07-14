package org.siloserver.silo.tv.ui.shell

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
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.rememberCoroutineScope
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
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.common.ui.components.isImageAvatar
import org.siloserver.silo.tv.ui.theme.SiloOnSurface
import org.siloserver.silo.tv.ui.theme.DarkBackground
import org.siloserver.silo.common.network.ServerReachabilityMonitor
import org.siloserver.silo.common.network.ServerReachabilityStatus
import org.siloserver.silo.common.ui.components.profileAvatarDisplayText
import org.siloserver.silo.common.ui.components.rememberProfileServerUrl
import org.siloserver.silo.common.ui.components.resolveAvatarUrl
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.admin.shouldShowClientAdminSurface
import org.siloserver.silo.model.auth.isActingAdmin
import org.siloserver.silo.model.feature.RequestsFeatureStore
import org.siloserver.silo.model.personal.UserLibrary
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
import org.siloserver.silo.tv.ui.screens.library.TvLibraryDetailScreen
import org.siloserver.silo.tv.ui.screens.library.TvLibraryTab
import org.siloserver.silo.tv.ui.screens.admin.TvAdminHubScreen
import org.siloserver.silo.tv.ui.screens.admin.TvAdminLogsScreen
import org.siloserver.silo.tv.ui.screens.admin.TvAdminScansScreen
import org.siloserver.silo.tv.ui.screens.admin.TvAdminScreen
import org.siloserver.silo.tv.ui.screens.admin.TvAdminSessionsScreen
import org.siloserver.silo.tv.ui.screens.admin.TvAdminUserEditScreen
import org.siloserver.silo.tv.ui.screens.admin.TvAdminUsersScreen
import org.siloserver.silo.tv.ui.screens.browse.TvBrowseScreen
import org.siloserver.silo.tv.ui.screens.calendar.TvCalendarScreen
import org.siloserver.silo.tv.ui.screens.collections.TvCollectionsScreen
import org.siloserver.silo.tv.ui.screens.home.TvHomeScreen
import org.siloserver.silo.tv.ui.screens.libraries.TvLibrariesScreen
import org.siloserver.silo.tv.ui.screens.personal.TvFavoritesScreen
import org.siloserver.silo.tv.ui.screens.personal.TvHistoryScreen
import org.siloserver.silo.tv.ui.screens.personal.TvWatchlistScreen
import org.siloserver.silo.tv.ui.screens.recommendations.TvRecommendationsScreen
import org.siloserver.silo.tv.ui.screens.requests.TvMyRequestsScreen
import org.siloserver.silo.tv.ui.screens.requests.TvRequestDetailScreen
import org.siloserver.silo.tv.ui.screens.requests.TvRequestsScreen
import org.siloserver.silo.tv.ui.screens.search.TvSearchScreen
import org.siloserver.silo.tv.ui.screens.settings.TvManageSessionsScreen
import org.siloserver.silo.tv.ui.screens.settings.TvSettingsScreen
import org.siloserver.silo.tv.ui.theme.TvSkyline
import org.siloserver.silo.tv.ui.util.visibleOnTv
import org.koin.compose.koinInject

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
    onOpenItemDetail: (contentId: String) -> Unit,
    onOpenLibraryCollectionDetail: (libraryId: Int, collectionId: String, title: String) -> Unit,
    onOpenCollectionDetail: (collectionId: String, title: String) -> Unit,
    onSignedOut: () -> Unit,
    onSwitchProfile: () -> Unit,
    onSwitchServer: () -> Unit,
    onPairDevice: () -> Unit,
    onPlayItem: (contentId: String, type: String?, resumePositionSeconds: Double?) -> Unit,
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
    val serverUrl = rememberProfileServerUrl()

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
    val pillSelections: SnapshotStateMap<TvLibraryTabType, TvLibraryPill> = remember { mutableStateMapOf() }
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
    val searchInputFocusRequester = remember { FocusRequester() }
    var searchInputHasFocus by remember { mutableStateOf(false) }
    var searchBackToInputRequest by remember { mutableIntStateOf(0) }
    // Opening an outer item-detail route pauses/removes this shell. Remember the
    // pending hand-back in the Main back-stack entry so it survives either form,
    // then re-enter the existing content focusRestorer when Main resumes.
    var restoreHomeContentAfterDetail by rememberSaveable { mutableStateOf(false) }
    var suppressHomeRefreshAfterDetail by rememberSaveable { mutableStateOf(false) }
    var homeDetailReturnFocusRequest by remember { mutableIntStateOf(0) }
    var homeDetailReturnNeedsRetry by remember { mutableStateOf(false) }
    // Attached (by the Home feed) to the exact card a detail page was launched
    // from, while that return is pending. Used as the content restorer's enter
    // fallback during the return resume so the synchronous claim below lands
    // straight on the launch card — the restorer's saved child node does not
    // survive the shell being removed for the outer detail route, and its
    // default enter could land a row below the launch card for a few frames.
    val homeDetailReturnCardFocusRequester = remember { FocusRequester() }
    // Whether focus currently sits anywhere inside the content group. Gates
    // the detail-return resume claim below: the Home feed's early restore
    // ladder usually re-focuses the launch card during the pop transition, and
    // re-requesting the content group after that re-runs its enter search and
    // yanks focus to a different card for a frame.
    var contentHasFocus by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        if (restoreHomeContentAfterDetail) {
            // Claim the content group synchronously during ON_RESUME, before
            // Compose's default search can briefly settle on the Home tab —
            // but only when the feed hasn't already claimed it. Claim BEFORE
            // clearing the flag so the restorer fallback still points at the
            // launch card for this claim.
            homeDetailReturnNeedsRetry = if (contentHasFocus) {
                false
            } else {
                runCatching { !contentFocusRequester.requestFocus() }.getOrDefault(true)
            }
            restoreHomeContentAfterDetail = false
            homeDetailReturnFocusRequest++
        }
        onPauseOrDispose { }
    }
    LaunchedEffect(homeDetailReturnFocusRequest) {
        if (homeDetailReturnFocusRequest == 0) return@LaunchedEffect
        // One-frame fallback for the disposed/recreated case where the Home row
        // requester was not attached during the synchronous resume claim.
        withFrameNanos { }
        if (homeDetailReturnNeedsRetry) {
            runCatching { contentFocusRequester.requestFocus() }
        }
        // The detail-return ON_RESUME event has now passed and Home is stable;
        // future real resumes (playback/background) should refresh normally.
        suppressHomeRefreshAfterDetail = false
    }
    val openHomeItemDetail: (String) -> Unit = { contentId ->
        restoreHomeContentAfterDetail = true
        suppressHomeRefreshAfterDetail = true
        onOpenItemDetail(contentId)
    }
    var contentUpFallback by remember { mutableStateOf<(() -> Boolean)?>(null) }
    // Feeds that registered the up-fallback slot, were superseded by a newer
    // feed, and are still awaiting their (now-stale) onDispose. Tracking them
    // lets us ignore that late dispose instead of nulling the entering feed's
    // registration.
    val supersededContentUpFallbacks = remember { mutableSetOf<() -> Boolean>() }
    // Register/relinquish the single D-pad-Up fallback slot BY IDENTITY. A
    // NavHost composes the ENTERING feed (which registers its own lambda) before
    // it disposes the EXITING one, so a blind null-on-dispose would drop the new
    // screen's registration and lose the "Up scrolls into the previous row"
    // fallback after every feed↔feed tab switch. Each feed passes its own stable
    // lambda on both register and dispose; we only relinquish the slot for the
    // feed that still owns it, ignore a superseded feed's stale dispose, and let
    // a newly-entering feed take the slot (retiring the previous owner).
    val onContentUpFallback: ((() -> Boolean)?) -> Unit = remember {
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
            // previous snapshot instead of blanking it — otherwise the Admin
            // row and account header flicker out on every hiccup.
            return@produceState
        }
        val user = userResult.data
        val activeProfile = profileRepository.getActiveProfile()
        // Subtitle mirrors tvOS §5.8: role when known, falling back to username.
        val subtitle = user?.role?.takeIf { it.isNotBlank() }
            ?.replaceFirstChar { it.uppercase() }
            ?: user?.username.orEmpty()
        val avatarUrl = activeProfile?.avatar
            ?.takeIf(::isImageAvatar)
            ?.let { resolveAvatarUrl(activeServerEntry?.url.orEmpty(), it) }
        value = TvAccountState(
            displayName = activeProfile?.name ?: user?.username ?: "Profile",
            avatar = activeProfile?.avatar,
            avatarUrl = avatarUrl,
            subtitle = subtitle,
            serverName = activeServerEntry?.displayName.orEmpty(),
            // Gate via the shared client-admin policy (same as the Settings
            // admin entry), not raw isActingAdmin — so the Admin row honors
            // CLIENT_ADMIN_SURFACE_ENABLED and stays consistent with the rest
            // of the TV client.
            isAdmin = shouldShowClientAdminSurface(isActingAdmin(user, activeProfile)),
        )
    }

    val selectedRoot by remember(currentRoute) {
        derivedStateOf { mapRouteToRoot(currentRoute) }
    }

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
            nestedNav.navigate(route) {
                popUpTo(nestedNav.graph.startDestinationId) { saveState = true }
                launchSingleTop = true
                restoreState = true
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

    // Parameterized form routes (e.g. AdminUserEdit) must NOT restore a saved
    // entry: all query variants share one destination id, so restoreState could
    // resurrect a stale entry (and its idempotent-loaded ViewModel) with the
    // wrong userId. Always start a fresh entry for these.
    val navigateToForm: (String) -> Unit = { route ->
        nestedNav.navigate(route) {
            launchSingleTop = false
            restoreState = false
        }
    }

    val moveFocusToContent: (String) -> Unit = { route ->
        focusState.closeProfileMenuForContent()
        if (route == TvMainRoute.Search.route) {
            runCatching { searchInputFocusRequester.requestFocus() }
        } else if (route == TvMainRoute.Home.route || route == TvMainRoute.Video.route) {
            panelScope.launch {
                runCatching { contentFocusRequester.requestFocus() }
                runCatching { homeFirstRowContainerFocusRequester.requestFocus() }
            }
        } else {
            // Just request focus on the content group. The Box's
            // .focusRestorer() restores to the user's last-focused card
            // (e.g., card 7 of row 3) instead of slamming back to card 0.
            // The previous behavior also bumped `contentFocusRequest++`,
            // which fired LaunchedEffects in each screen that imperatively
            // re-focused index 0 — defeating the restorer. Initial focus
            // when a screen first loads is still handled by each screen's
            // own LaunchedEffect on its first data emission.
            runCatching { contentFocusRequester.requestFocus() }
        }
    }

    val onSelectRoot: (TvRootDestination) -> Unit = { dest ->
        val route = dest.toRoute()
        if (dest == TvRootDestination.Home) {
            // Detail return deliberately preserves the card that opened the
            // detail page, but that protection must end when the user
            // explicitly selects Home from the bar. Otherwise its nonzero
            // token keeps suppressing Home's normal first-card focus request
            // for the rest of the shell session.
            homeDetailReturnFocusRequest = 0
            homeDetailReturnNeedsRetry = false
        }
        if (dest == TvRootDestination.Calendar) {
            calendarFocusHandoffPending = true
        }
        // A dwell preview can still be open when Center commits For You (or a
        // library root). Close it without returning focus to the bar before the
        // content handoff, otherwise the overlay lingers and races page focus.
        focusState.closePanel(false)
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
        focusState.closePanel(false)
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // Shell-level Back/Escape. Placed on the outer Box (an ancestor of
            // BOTH the content layer and the top menu bar) so it fires no
            // matter which has focus. When the menu is focused, Back returns to
            // content instead of falling through to the activity and exiting.
            .onPreviewKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyUp &&
                    (ev.key == Key.Back || ev.key == Key.Escape)
                ) {
                    // Settings owns a two-stage Back model (detail pane →
                    // selected rail category → Home). Let its BackHandler see
                    // the event instead of applying the shell-wide routing.
                    if (currentRoute == TvMainRoute.Settings.route) {
                        return@onPreviewKeyEvent false
                    }
                    // Centralized shell Back. The 4-way priority (panel >
                    // profile menu > focused bar > nav) is decided by
                    // [TvShellFocusState.onBack], which also applies the state
                    // half (close panel / dropdown); we run only the side effect
                    // each action needs. Keeping it here — not in the selector or
                    // the bar — means Back can never be double-handled.
                    when (focusState.onBack(onTabRoot = selectedRoot != null)) {
                        // Panel/dropdown already closed by onBack(): just consume.
                        TvShellBackAction.ClosePanel,
                        TvShellBackAction.CloseProfileMenu -> true
                        // Content on a tab root: onBack() already routed focus
                        // to the bar's selected tab — just consume.
                        TvShellBackAction.MoveFocusToMenu -> true
                        // Bar focused: Home exits the app (fall through to the
                        // activity), any other section goes Home with the bar
                        // still focused (now on the Home tab).
                        TvShellBackAction.MenuBack -> {
                            if (selectedRoot == TvRootDestination.Home) {
                                false
                            } else {
                                navigateToRoute(firstTvRoute())
                                focusState.requestMenuFocus()
                                true
                            }
                        }
                        // Secondary screens (Settings, Search, admin, …): pop
                        // the flat inner NavHost when there's history; otherwise
                        // fall through so the activity finishes the app.
                        TvShellBackAction.DelegateToNav -> {
                            if (currentRoute == TvMainRoute.Search.route && !searchInputHasFocus) {
                                searchBackToInputRequest += 1
                                true
                            } else if (nestedNav.previousBackStackEntry != null) {
                                // Focus restoration after the pop is owned by the
                                // restored screen itself (the section feed re-targets
                                // its last-focused card via its recreation ladder).
                                nestedNav.popBackStack()
                                true
                            } else {
                                false
                            }
                        }
                    }
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
                // gone (the shell left composition), so fall back to the Home
                // feed's launch-card requester; Default otherwise.
                .focusRestorer(
                    if (restoreHomeContentAfterDetail) {
                        homeDetailReturnCardFocusRequester
                    } else {
                        FocusRequester.Default
                    },
                )
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
                            val contentHandledUp = contentUpFallback?.invoke()
                            if (contentHandledUp != null) {
                                if (!contentHandledUp) {
                                    focusState.requestMenuFocus()
                                }
                            } else {
                                // Try to move focus up inside content; if that
                                // fails (we're already on the top row), hand
                                // focus to the menu bar.
                                val moved = focusManager.moveFocus(FocusDirection.Up)
                                if (!moved) {
                                    focusState.requestMenuFocus()
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
                composable(TvMainRoute.Video.route) {
                    TvHomeScreen(
                        onItemClick = openHomeItemDetail,
                        onPlayItem = onPlayItem,
                        onSeeAll = {
                            navigateToSecondary(TvMainRoute.Browse.route)
                            moveFocusToContent(TvMainRoute.Browse.route)
                        },
                        onOpenForYou = {
                            navigateToSecondary(TvMainRoute.ForYou.route)
                            moveFocusToContent(TvMainRoute.ForYou.route)
                        },
                        onInitialContentFocus = { focusState.closeProfileMenuForContent() },
                        focusRequest = contentFocusRequest,
                        detailReturnFocusRequest = homeDetailReturnFocusRequest,
                        detailReturnCardFocusRequester = homeDetailReturnCardFocusRequester,
                        firstRowFocusRequester = homeFirstItemFocusRequester,
                        firstRowContainerFocusRequester = homeFirstRowContainerFocusRequester,
                        shouldRefreshOnResume = { !suppressHomeRefreshAfterDetail },
                        onContentUpFallbackChanged = onContentUpFallback,
                    )
                }
                composable(TvMainRoute.Home.route) {
                    TvHomeScreen(
                        onItemClick = openHomeItemDetail,
                        onPlayItem = onPlayItem,
                        onSeeAll = {
                            navigateToSecondary(TvMainRoute.Browse.route)
                            moveFocusToContent(TvMainRoute.Browse.route)
                        },
                        onOpenForYou = {
                            navigateToSecondary(TvMainRoute.ForYou.route)
                            moveFocusToContent(TvMainRoute.ForYou.route)
                        },
                        onInitialContentFocus = { focusState.closeProfileMenuForContent() },
                        focusRequest = contentFocusRequest,
                        detailReturnFocusRequest = homeDetailReturnFocusRequest,
                        detailReturnCardFocusRequester = homeDetailReturnCardFocusRequester,
                        firstRowFocusRequester = homeFirstItemFocusRequester,
                        firstRowContainerFocusRequester = homeFirstRowContainerFocusRequester,
                        shouldRefreshOnResume = { !suppressHomeRefreshAfterDetail },
                        onContentUpFallbackChanged = onContentUpFallback,
                    )
                }
                composable(TvMainRoute.Search.route) {
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
                        onSearchFieldFocusChanged = { searchInputHasFocus = it },
                    )
                }
                composable(TvMainRoute.Audio.route) {
                    TvLibrariesScreen(
                        onItemClick = onOpenItemDetail,
                        onLibraryCollectionClick = onOpenLibraryCollectionDetail,
                        onInitialContentFocus = { focusState.closeProfileMenuForContent() },
                    )
                }
                composable(TvMainRoute.Libraries.route) {
                    TvLibrariesScreen(
                        onItemClick = onOpenItemDetail,
                        onLibraryCollectionClick = onOpenLibraryCollectionDetail,
                        onInitialContentFocus = { focusState.closeProfileMenuForContent() },
                    )
                }
                // Content-type tabs (Skyline §3.1). Each renders the library
                // content scoped to that type's active library. The full-screen
                // picker stays the switch mechanism this stage (TvLibrariesScreen
                // still hosts it for the legacy Libraries route); the cascade
                // selector arrives in Stage 4.
                composable(TvMainRoute.Movies.route) {
                    TvLibraryTypeContent(
                        type = TvLibraryTabType.Movies,
                        library = activeLibrary(TvLibraryTabType.Movies),
                        emptyConfirmed = librariesLoaded && libraries.none { TvLibraryTabType.Movies.matches(it) },
                        selectedPill = pillSelections[TvLibraryTabType.Movies] ?: TvLibraryPill.Recommended,
                        sectionRequestNonce = sectionRequestNonces[TvLibraryTabType.Movies] ?: 0,
                        onItemClick = onOpenItemDetail,
                        onLibraryCollectionClick = onOpenLibraryCollectionDetail,
                        onInitialContentFocus = { focusState.closeProfileMenuForContent() },
                        onContentUpFallbackChanged = onContentUpFallback,
                    )
                }
                composable(TvMainRoute.Series.route) {
                    TvLibraryTypeContent(
                        type = TvLibraryTabType.Series,
                        library = activeLibrary(TvLibraryTabType.Series),
                        emptyConfirmed = librariesLoaded && libraries.none { TvLibraryTabType.Series.matches(it) },
                        selectedPill = pillSelections[TvLibraryTabType.Series] ?: TvLibraryPill.Recommended,
                        sectionRequestNonce = sectionRequestNonces[TvLibraryTabType.Series] ?: 0,
                        onItemClick = onOpenItemDetail,
                        onLibraryCollectionClick = onOpenLibraryCollectionDetail,
                        onInitialContentFocus = { focusState.closeProfileMenuForContent() },
                        onContentUpFallbackChanged = onContentUpFallback,
                    )
                }
                composable(TvMainRoute.Music.route) {
                    TvLibraryTypeContent(
                        type = TvLibraryTabType.Music,
                        library = activeLibrary(TvLibraryTabType.Music),
                        emptyConfirmed = librariesLoaded && libraries.none { TvLibraryTabType.Music.matches(it) },
                        selectedPill = pillSelections[TvLibraryTabType.Music] ?: TvLibraryPill.Recommended,
                        sectionRequestNonce = sectionRequestNonces[TvLibraryTabType.Music] ?: 0,
                        onItemClick = onOpenItemDetail,
                        onLibraryCollectionClick = onOpenLibraryCollectionDetail,
                        onInitialContentFocus = { focusState.closeProfileMenuForContent() },
                        onContentUpFallbackChanged = onContentUpFallback,
                    )
                }
                composable(TvMainRoute.Audiobooks.route) {
                    TvLibraryTypeContent(
                        type = TvLibraryTabType.Audiobooks,
                        library = activeLibrary(TvLibraryTabType.Audiobooks),
                        emptyConfirmed = librariesLoaded && libraries.none { TvLibraryTabType.Audiobooks.matches(it) },
                        selectedPill = pillSelections[TvLibraryTabType.Audiobooks] ?: TvLibraryPill.Recommended,
                        sectionRequestNonce = sectionRequestNonces[TvLibraryTabType.Audiobooks] ?: 0,
                        onItemClick = onOpenItemDetail,
                        onLibraryCollectionClick = onOpenLibraryCollectionDetail,
                        onInitialContentFocus = { focusState.closeProfileMenuForContent() },
                        onContentUpFallbackChanged = onContentUpFallback,
                    )
                }
                composable(TvMainRoute.ForYou.route) {
                    TvRecommendationsScreen(
                        onItemClick = onOpenItemDetail,
                        onInitialContentFocus = { focusState.closeProfileMenuForContent() },
                        focusRequest = contentFocusRequest,
                    )
                }
                composable(TvMainRoute.Requests.route) {
                    TvRequestsScreen(
                        onOpenLibraryItem = onOpenItemDetail,
                        onOpenMyRequests = { navigateToSecondary(TvMainRoute.MyRequests.route) },
                        onOpenRequestDetail = { mt, id ->
                            navigateToSecondary(TvMainRoute.RequestDetail(mt, id).route)
                        },
                        onInitialContentFocus = { focusState.closeProfileMenuForContent() },
                    )
                }
                composable(TvMainRoute.MyRequests.route) {
                    TvMyRequestsScreen(
                        onOpenLibraryItem = onOpenItemDetail,
                        onOpenRequestDetail = { mt, id ->
                            navigateToSecondary(TvMainRoute.RequestDetail(mt, id).route)
                        },
                        onInitialContentFocus = { focusState.closeProfileMenuForContent() },
                    )
                }
                composable(
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
                composable(TvMainRoute.Collections.route) {
                    TvCollectionsScreen(
                        onCollectionClick = onOpenCollectionDetail,
                        onInitialContentFocus = { focusState.closeProfileMenuForContent() },
                    )
                }
                composable(TvMainRoute.Watchlist.route) {
                    TvWatchlistScreen(
                        onItemClick = onOpenItemDetail,
                        onInitialContentFocus = { focusState.closeProfileMenuForContent() },
                    )
                }
                composable(TvMainRoute.Favorites.route) {
                    TvFavoritesScreen(
                        onItemClick = onOpenItemDetail,
                        onInitialContentFocus = { focusState.closeProfileMenuForContent() },
                    )
                }
                composable(TvMainRoute.History.route) {
                    TvHistoryScreen(
                        onItemClick = onOpenItemDetail,
                        onInitialContentFocus = { focusState.closeProfileMenuForContent() },
                    )
                }
                composable(TvMainRoute.Settings.route) {
                    TvSettingsScreen(
                        onNavigateToAdmin = {
                            // Apple parity: the stats dashboard is the whole
                            // admin surface. The hub (users/sessions/logs/
                            // scans) stays compiled but unlinked.
                            navigateToSecondary(TvMainRoute.AdminDashboard.route)
                            moveFocusToContent(TvMainRoute.AdminDashboard.route)
                        },
                        onManageServers = onManageServers,
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
                composable(TvMainRoute.ManageSessions.route) {
                    TvManageSessionsScreen(onBack = { if (nestedNav.previousBackStackEntry != null) nestedNav.popBackStack() })
                }
                composable(TvMainRoute.Calendar.route) {
                    TvCalendarScreen(
                        onOpenItemDetail = onOpenItemDetail,
                        onInitialContentFocus = {
                            focusState.closeProfileMenuForContent()
                            calendarFocusHandoffPending = false
                        },
                        focusRequest = contentFocusRequest,
                    )
                }
                composable(TvMainRoute.Browse.route) {
                    TvBrowseScreen(
                        onOpenItemDetail = onOpenItemDetail,
                        onInitialContentFocus = { focusState.closeProfileMenuForContent() },
                    )
                }
                composable(TvMainRoute.AdminHub.route) {
                    TvAdminHubScreen(
                        onOpenDashboard = { navigateToSecondary(TvMainRoute.AdminDashboard.route) },
                        onOpenUsers = { navigateToSecondary(TvMainRoute.AdminUsers.route) },
                        onOpenSessions = { navigateToSecondary(TvMainRoute.AdminSessions.route) },
                        onOpenScans = { navigateToSecondary(TvMainRoute.AdminScans.route) },
                        onOpenLogs = { navigateToSecondary(TvMainRoute.AdminLogs.route) },
                        onBack = { if (nestedNav.previousBackStackEntry != null) nestedNav.popBackStack() },
                    )
                }
                composable(TvMainRoute.AdminDashboard.route) {
                    TvAdminScreen(onBack = { if (nestedNav.previousBackStackEntry != null) nestedNav.popBackStack() })
                }
                composable(TvMainRoute.AdminUsers.route) {
                    TvAdminUsersScreen(
                        onBack = { if (nestedNav.previousBackStackEntry != null) nestedNav.popBackStack() },
                        onCreateUser = { navigateToForm(TvMainRoute.AdminUserEdit().route) },
                        onEditUser = { id -> navigateToForm(TvMainRoute.AdminUserEdit(id).route) },
                    )
                }
                composable(
                    route = TvMainRoute.AdminUserEdit.ROUTE,
                    arguments = listOf(
                        navArgument(TvMainRoute.AdminUserEdit.ARG_USER_ID) {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                    ),
                ) { entry ->
                    val userId = entry.arguments
                        ?.getString(TvMainRoute.AdminUserEdit.ARG_USER_ID)
                        ?.toIntOrNull()
                    TvAdminUserEditScreen(
                        userId = userId,
                        onBack = { if (nestedNav.previousBackStackEntry != null) nestedNav.popBackStack() },
                        onSaved = { if (nestedNav.previousBackStackEntry != null) nestedNav.popBackStack() },
                    )
                }
                composable(TvMainRoute.AdminSessions.route) {
                    TvAdminSessionsScreen(onBack = { if (nestedNav.previousBackStackEntry != null) nestedNav.popBackStack() })
                }
                composable(TvMainRoute.AdminScans.route) {
                    TvAdminScansScreen(onBack = { if (nestedNav.previousBackStackEntry != null) nestedNav.popBackStack() })
                }
                composable(TvMainRoute.AdminLogs.route) {
                    TvAdminLogsScreen(onBack = { if (nestedNav.previousBackStackEntry != null) nestedNav.popBackStack() })
                }
            }
        }

        // Menu overlay — content remains visible behind the transparent bar,
        // matching tvOS without a heavy top-edge shadow.
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
                            focusState.closePanel(false)
                            navigateToSecondary(TvMainRoute.Watchlist.route)
                            moveFocusToContent(TvMainRoute.Watchlist.route)
                        },
                        onFavorites = {
                            focusState.closePanel(false)
                            navigateToSecondary(TvMainRoute.Favorites.route)
                            moveFocusToContent(TvMainRoute.Favorites.route)
                        },
                        onRecommendations = {
                            focusState.closePanel(false)
                            navigateToSecondary(TvMainRoute.ForYou.route)
                            moveFocusToContent(TvMainRoute.ForYou.route)
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
                        onPanelFocusChanged = { /* optional bar-dim tracking */ },
                        onClose = { focusState.closePanel(true) },
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
    onLibraryCollectionClick: (libraryId: Int, collectionId: String, title: String) -> Unit,
    onInitialContentFocus: () -> Unit,
    onContentUpFallbackChanged: (((() -> Boolean)?) -> Unit)? = null,
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
            onCollectionClick = { collectionId, title ->
                onLibraryCollectionClick(library.id, collectionId, title)
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
 * History · Requests (feature-gated) · Settings · Switch Server · Sign Out.
 * Calendar is a top-level tab.
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
            if (accountState.avatarUrl != null) {
                ThumbhashImage(
                    url = accountState.avatarUrl,
                    thumbhash = null,
                    contentDescription = accountState.displayName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    transparent = true,
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
