package org.siloserver.silo.android.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SettingsRemote
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.ui.components.SiloWordmark
import org.siloserver.silo.android.ui.components.TabTopBarActions
import org.siloserver.silo.android.ui.components.TopBarIconButton
import org.siloserver.silo.android.ui.components.topBarGlass
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.flow.distinctUntilChanged
import org.siloserver.silo.android.ui.components.EmptyStateView
import org.siloserver.silo.android.ui.components.ErrorView
import org.siloserver.silo.android.ui.components.MediaRowSkeleton
import org.siloserver.silo.android.ui.components.ProfileMenu
import org.siloserver.silo.android.ui.components.rememberShimmerProgress
import org.siloserver.silo.android.ui.screens.pairing.CompanionPairingViewModel
import org.siloserver.silo.android.ui.screens.pairing.CompanionPairingBottomOverlay
import org.siloserver.silo.android.ui.screens.profiles.ProfileAvatar
import org.siloserver.silo.common.diagnostics.DiagnosticsHomeContentState
import org.siloserver.silo.common.diagnostics.DiagnosticsHomeLogger
import org.siloserver.silo.common.diagnostics.DiagnosticsHomeScrollRegion
import org.siloserver.silo.common.diagnostics.DiagnosticsListLogger
import org.siloserver.silo.common.diagnostics.DiagnosticsListSnapshot
import org.siloserver.silo.common.diagnostics.DiagnosticsListSurface
import org.siloserver.silo.common.pairing.CompanionPairingStatus
import org.siloserver.silo.common.pairing.CompanionPairingTarget
import org.siloserver.silo.common.ui.components.DeferImagePresentationWhileScrolling
import org.siloserver.silo.common.ui.components.avatarRef
import org.siloserver.silo.model.catalog.isAudiobookItemType
import org.siloserver.silo.model.profile.Profile
import org.siloserver.silo.viewmodel.HomeViewModel
import org.siloserver.silo.android.ui.navigation.LocalBottomChromeInset
import org.koin.compose.viewmodel.koinViewModel

private const val ChromeFadeDistanceDp = 72f

/**
 * Phone Home screen.
 *
 * Mirrors iOS `HomeView.swift` (phone) 1:1: a flat OLED background (no hero —
 * a `featured` section renders as an ordinary row in its server order; the
 * phone apps have no hero surface at all), a runway spacer that
 * reserves room under the floating chrome, the resume-first section rows, and
 * a floating top chrome (wordmark + search + profile menu) that fades in a
 * subtle glass surface as content scrolls underneath it. The screen owns its
 * own top inset so the chrome floats over the status bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onItemClick: (String) -> Unit,
    onPlayClick: (String, Double?) -> Unit,
    scrollToTopTick: Int = 0,
    viewModel: HomeViewModel,
    activeProfile: Profile?,
    onSearchClick: () -> Unit,
    onRemoteControlClick: () -> Unit,
    onRemoteChooseTvClick: () -> Unit,
    onRemoteDisconnectClick: () -> Unit,
    isRemoteControlActive: Boolean,
    onRequestsClick: (() -> Unit)?,
    onWatchTogetherClick: (() -> Unit)?,
    onSettingsClick: () -> Unit,
    onSwitchProfileClick: () -> Unit,
    onSwitchServerClick: () -> Unit,
    onSignOutClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val companionPairingViewModel = koinViewModel<CompanionPairingViewModel>()
    val companionTargets by companionPairingViewModel.targets.collectAsState()
    val companionStatus by companionPairingViewModel.status.collectAsState()
    val companionApproval by companionPairingViewModel.pendingApproval.collectAsState()
    val companionServerChoices by companionPairingViewModel.serverChoices.collectAsState()
    var presentedPairingTarget by remember { mutableStateOf<CompanionPairingTarget?>(null) }
    var dismissedPairingSessions by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val sections = state.sections
    // No hero billboard on phone (matches iOS): a `featured` section is just
    // another row, rendered in the order the server configured it.
    val regularSections = remember(sections) {
        sections.filter { it.items.isNotEmpty() }
    }
    val diagnosticsContentState = when {
        state.isLoading && regularSections.isEmpty() -> DiagnosticsHomeContentState.LOADING
        state.error != null && regularSections.isEmpty() -> DiagnosticsHomeContentState.ERROR
        regularSections.isEmpty() -> DiagnosticsHomeContentState.EMPTY
        else -> DiagnosticsHomeContentState.READY
    }
    LaunchedEffect(diagnosticsContentState) {
        DiagnosticsHomeLogger.content(diagnosticsContentState)
    }
    val diagnosticsListSnapshot = remember(regularSections, state.sectionsFullyResolved) {
        DiagnosticsListSnapshot.fromKeys(
            keys = regularSections.map { it.id },
            rowKeys = regularSections.map { section -> section.items.map { it.contentId } },
            fullyResolved = state.sectionsFullyResolved,
        )
    }
    LaunchedEffect(diagnosticsListSnapshot, diagnosticsContentState) {
        if (diagnosticsContentState == DiagnosticsHomeContentState.READY) {
            DiagnosticsListLogger.snapshot(DiagnosticsListSurface.PHONE_HOME, diagnosticsListSnapshot)
        }
    }

    val listState = rememberLazyListState()
    val currentDiagnosticsSections by rememberUpdatedState(regularSections)
    LaunchedEffect(scrollToTopTick) {
        if (scrollToTopTick > 0) listState.animateScrollToItem(0)
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                val currentSections = currentDiagnosticsSections
                val sectionCount = currentSections.size
                val firstVisibleItem = listState.firstVisibleItemIndex
                val visibleRowOrdinal = (firstVisibleItem - 1).takeIf(currentSections.indices::contains)
                val region = when {
                    sectionCount == 0 -> DiagnosticsHomeScrollRegion.UNKNOWN
                    firstVisibleItem <= 1 -> DiagnosticsHomeScrollRegion.TOP
                    firstVisibleItem >= sectionCount -> DiagnosticsHomeScrollRegion.END
                    else -> DiagnosticsHomeScrollRegion.CONTENT
                }
                DiagnosticsHomeLogger.scroll(
                    scrolling = scrolling,
                    region = region,
                    visibleRowOrdinal = visibleRowOrdinal,
                    rawSectionType = visibleRowOrdinal?.let { currentSections[it].sectionType },
                )
            }
    }
    LaunchedEffect(companionTargets, companionStatus, dismissedPairingSessions) {
        if (companionStatus is CompanionPairingStatus.Idle) {
            val presented = presentedPairingTarget
            if (presented == null) {
                presentedPairingTarget = companionTargets.firstOrNull {
                    it.dismissalKey !in dismissedPairingSessions
                }
            } else {
                // Android NSD can resolve the same TV again with a new listener port after
                // its setup screen restarts. Keep the visible card, but always pair with the
                // freshest endpoint instead of the target object originally latched by UI.
                presentedPairingTarget = companionTargets
                    .firstOrNull { it.deviceId == presented.deviceId }
                    ?: companionTargets.firstOrNull { it.dismissalKey !in dismissedPairingSessions }
            }
        }
    }
    val density = LocalDensity.current
    val chromeFadePx = remember(density) {
        with(density) { ChromeFadeDistanceDp.dp.toPx() }
    }
    val scrollProgress = remember(chromeFadePx) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) {
                1f
            } else {
                (listState.firstVisibleItemScrollOffset / chromeFadePx).coerceIn(0f, 1f)
            }
        }
    }

    // Home's own blur source: the floating chrome blurs the rows scrolling
    // beneath it. Local rather than the shell's tab-wide source because the
    // chrome sits inside that source and an effect must not read a source
    // that contains it.
    val chromeHaze = rememberHazeState()

    // Home can show the same item in several rows at once. Each poster placement
    // now carries a unique hero key (see MediaCard) so duplicates never collide
    // in the shared-transition layout — no per-screen claim registry needed.
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when {
            state.isLoading && regularSections.isEmpty() -> HomeLoadingSkeleton()
            state.error != null && regularSections.isEmpty() -> ErrorView(
                message = state.error ?: "Something went wrong",
                onRetry = { viewModel.loadSections() },
            )
            regularSections.isEmpty() -> EmptyStateView(
                title = "Nothing to watch yet",
                subtitle = "Add media to your libraries or start watching to see it here.",
            )
            else -> PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = { viewModel.refresh() },
                // Background sits inside the source so the glass captures an
                // opaque scene; a transparent capture composites the blur over
                // the sharp content beneath instead of replacing it.
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(chromeHaze)
                    .background(MaterialTheme.colorScheme.background),
            ) {
                // Feed-scoped: unloaded images hold their thumbhash until the
                // vertical fling settles (rows add their own horizontal gate).
                DeferImagePresentationWhileScrolling(listState) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        // iOS `sectionSpacing` = SiloTheme.largePadding (24).
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        // Reserve runway under the floating header so the first row
                        // doesn't slide under the status-bar chrome. iOS runway =
                        // topInset + 40 + smallPadding(8) + largePadding(24) +
                        // smallPadding(8) - headerTopReclaim(16) = topInset + 64.
                        item(key = "topRunway") {
                            Spacer(
                                modifier = Modifier
                                    .windowInsetsPadding(WindowInsets.statusBars)
                                    .height(64.dp),
                            )
                        }

                        items(
                            items = regularSections,
                            key = { it.id },
                            contentType = { "section-row" },
                        ) { section ->
                            HomeSectionRow(
                                section = section,
                                onItemClick = onItemClick,
                                onItemPlay = { item ->
                                    // Continue Watching can include audiobooks; the play
                                    // glyph must not drop them into the video player. Home
                                    // has no callback reaching Route.AudiobookPlayer (that
                                    // route needs a fileId SectionItem doesn't carry), so
                                    // send audiobooks to their detail page, which dispatches
                                    // audiobook playback correctly.
                                    if (isAudiobookItemType(item.type)) {
                                        onItemClick(item.contentId)
                                    } else {
                                        onPlayClick(item.contentId, item.positionSeconds)
                                    }
                                },
                                onSetWatched = viewModel::setWatched,
                                onToggleFavorite = viewModel::toggleFavorite,
                                onToggleWatchlist = viewModel::toggleWatchlist,
                                onDismissContinueWatching = { item ->
                                    item.progressUpdatedAt?.let { ts ->
                                        viewModel.dismissContinueWatching(item.contentId, ts)
                                    }
                                },
                            )
                        }

                        // iOS bottom padding = SiloTheme.largePadding (24), plus the
                        // translucent bottom chrome the content scrolls beneath.
                        item(key = "bottomPad") {
                            Spacer(modifier = Modifier.height(24.dp + LocalBottomChromeInset.current))
                        }
                    }
                }
            }
        }

        // Floating top chrome — fades in a glass surface as content scrolls under.
        HomeFloatingChrome(
            scrollProgress = scrollProgress,
            hazeState = chromeHaze,
            activeProfile = activeProfile,
            onSearchClick = onSearchClick,
            onRemoteControlClick = onRemoteControlClick,
            onRemoteChooseTvClick = onRemoteChooseTvClick,
            onRemoteDisconnectClick = onRemoteDisconnectClick,
            isRemoteControlActive = isRemoteControlActive,
            onRequestsClick = onRequestsClick,
            onWatchTogetherClick = onWatchTogetherClick,
            onSettingsClick = onSettingsClick,
            onSwitchProfileClick = onSwitchProfileClick,
            onSwitchServerClick = onSwitchServerClick,
            onSignOutClick = onSignOutClick,
        )

        CompanionPairingBottomOverlay(
            target = presentedPairingTarget,
            status = companionStatus,
            approval = companionApproval,
            serverChoices = companionServerChoices,
            onPair = companionPairingViewModel::pair,
            onServersSelected = companionPairingViewModel::continueWithServers,
            onApprove = companionPairingViewModel::approveMatchCode,
            onDecline = companionPairingViewModel::cancelMatchCode,
            onDismiss = {
                presentedPairingTarget?.let { target ->
                    dismissedPairingSessions = dismissedPairingSessions + target.dismissalKey
                }
                companionPairingViewModel.dismissPairing()
                presentedPairingTarget = null
            },
        )
    }
}

private val CompanionPairingTarget.dismissalKey: String
    get() = "$deviceId:${sessionId ?: serviceName}"

@Composable
private fun HomeLoadingSkeleton() {
    val shimmer = rememberShimmerProgress()
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Spacer(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(64.dp),
        )
        repeat(4) {
            MediaRowSkeleton(progress = shimmer)
        }
    }
}

@Composable
private fun HomeFloatingChrome(
    scrollProgress: State<Float>,
    hazeState: HazeState,
    activeProfile: Profile?,
    onSearchClick: () -> Unit,
    onRemoteControlClick: () -> Unit,
    onRemoteChooseTvClick: () -> Unit,
    onRemoteDisconnectClick: () -> Unit,
    isRemoteControlActive: Boolean,
    onRequestsClick: (() -> Unit)?,
    onWatchTogetherClick: (() -> Unit)?,
    onSettingsClick: () -> Unit,
    onSwitchProfileClick: () -> Unit,
    onSwitchServerClick: () -> Unit,
    onSignOutClick: () -> Unit,
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    // iOS chrome: progressive glass that fades in as rows scroll under and
    // feathers out along its bottom edge (same recipe as Libraries), so rows
    // dissolve into the header rather than meeting a hard line. The glass
    // extends past the action row so the feather has room on a short bar.
    // headerTopReclaim(16) pulls the row up beside the status-bar glyphs;
    // horizontal = SiloTheme.padding(16), bottom = SiloTheme.smallPadding(8).
    Box(modifier = Modifier.fillMaxWidth()) {
        // Glass fades in with scroll; alpha lives on a graphics layer so the
        // buttons above stay fully visible at rest. It matches the whole
        // chrome, i.e. the action row plus the feather runway below it.
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { alpha = scrollProgress.value }
                .topBarGlass(hazeState, progressive = true),
        )
        Box(
            modifier = Modifier
                .padding(top = statusBarPadding.calculateTopPadding())
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp + HomeChromeFeatherExtension)
                .fillMaxWidth(),
        ) {
            // Leading: Silo wordmark (iOS SiloWordmarkView width: 72).
            SiloWordmark(
                modifier = Modifier
                    .align(Alignment.CenterStart),
                width = 72.dp,
            )

            // Trailing: remote-control + search + profile menu cluster.
            TabTopBarActions(
                modifier = Modifier.align(Alignment.CenterEnd),
                activeProfile = activeProfile,
                onSearchClick = onSearchClick,
                onRequestsClick = onRequestsClick,
                onWatchTogetherClick = onWatchTogetherClick,
                onSettingsClick = onSettingsClick,
                onSwitchProfileClick = onSwitchProfileClick,
                onSwitchServerClick = onSwitchServerClick,
                onSignOutClick = onSignOutClick,
                leadingActions = {
                    // Mirrors Apple's SiloControlModeButton: chrome-free at rest,
                    // filled disc while controlling a TV; the active state opens a
                    // menu instead of jumping straight to the remote.
                    Box {
                        var remoteMenuExpanded by remember { mutableStateOf(false) }
                        TopBarIconButton(
                            onClick = {
                                if (isRemoteControlActive) {
                                    remoteMenuExpanded = true
                                } else {
                                    onRemoteControlClick()
                                }
                            },
                            isActive = isRemoteControlActive,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.SettingsRemote,
                                contentDescription = "Remote Control",
                            )
                        }
                        DropdownMenu(
                            expanded = remoteMenuExpanded,
                            onDismissRequest = { remoteMenuExpanded = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Remote Control") },
                                onClick = {
                                    remoteMenuExpanded = false
                                    onRemoteControlClick()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Choose TV") },
                                onClick = {
                                    remoteMenuExpanded = false
                                    onRemoteChooseTvClick()
                                },
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Turn Off Control Mode",
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                                onClick = {
                                    remoteMenuExpanded = false
                                    onRemoteDisconnectClick()
                                },
                            )
                        }
                    }
                },
            )
        }
    }
}

/** How far the Home chrome's glass runs past its action row to feather out. */
private val HomeChromeFeatherExtension = 40.dp
