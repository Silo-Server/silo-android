package org.siloserver.silo.android.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.imageLoader
import coil3.request.ImageRequest
import org.siloserver.silo.android.ui.components.SiloWordmark
import org.siloserver.silo.android.ui.components.TabTopBarActions
import org.siloserver.silo.android.ui.components.TopBarIconButton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.viewmodel.HomeViewModel
import org.siloserver.silo.android.ui.navigation.LocalBottomChromeInset
import org.siloserver.silo.android.ui.navigation.LocalHeroSourceHandoff
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.CatalogRepository
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

private data class ContinueWatchingWarmTarget(
    val seriesId: String,
    val seasonNumber: Int?,
    val episodeContentId: String,
)

private data class WarmedSeriesArtwork(
    val url: String?,
    val thumbhash: String?,
)

/**
 * Phone Home screen.
 *
 * Mirrors iOS `HomeView.swift` (phone) 1:1: the fixed charcoal page canvas (no hero —
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
    onContinueWatchingItemClick: (SectionItem) -> Unit,
    onPlayClick: (String, Double?) -> Unit,
    onBottomNavMinimizedChange: (Boolean) -> Unit = {},
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
    val sectionPreferences: HomeSectionPreferencesStore = koinInject()
    val preferenceRevision by sectionPreferences.revision.collectAsState()
    val serverRegistry: ServerRegistry = koinInject()
    val activeServerId by serverRegistry.activeServerId.collectAsState()
    val activeEntry by serverRegistry.activeEntry.collectAsState()
    val companionPairingViewModel = koinViewModel<CompanionPairingViewModel>()
    val companionTargets by companionPairingViewModel.targets.collectAsState()
    val companionStatus by companionPairingViewModel.status.collectAsState()
    val companionApproval by companionPairingViewModel.pendingApproval.collectAsState()
    val companionServerChoices by companionPairingViewModel.serverChoices.collectAsState()
    var presentedPairingTarget by remember { mutableStateOf<CompanionPairingTarget?>(null) }
    var dismissedPairingSessions by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val sections = state.sections
    val profileScopeId = activeEntry?.profileId ?: activeProfile?.id
    val populatedServerSections = remember(sections) { sections.filter { it.items.isNotEmpty() } }
    // No hero billboard on phone (matches iOS): a `featured` section is just
    // another row, rendered in the order the server configured it.
    val regularSections = remember(
        sections,
        preferenceRevision,
        activeServerId,
        profileScopeId,
    ) {
        sectionPreferences.arrangedSections(sections, profileId = profileScopeId)
    }
    val context = LocalContext.current
    val catalogRepository: CatalogRepository = koinInject()
    val heroHandoff = LocalHeroSourceHandoff.current
    val continueWatchingWarmTargets = remember(regularSections) {
        regularSections
            .asSequence()
            .filter { section ->
                section.sectionType == "continue_watching" ||
                    section.sectionType == "in_progress"
            }
            .flatMap { it.items.asSequence() }
            .mapNotNull { item ->
                val seriesId = item.seriesId?.takeIf { it.isNotBlank() }
                if (!item.type.equals("episode", ignoreCase = true) || seriesId == null) {
                    null
                } else {
                    ContinueWatchingWarmTarget(
                        seriesId = seriesId,
                        seasonNumber = item.seasonNumber,
                        episodeContentId = item.contentId,
                    )
                }
            }
            .distinctBy { it.seriesId }
            // Warming the visible runway covers the row without turning Home
            // into an unbounded metadata crawl for very large histories.
            .take(4)
            .toList()
    }
    var warmedSeriesArtwork by remember(activeServerId, profileScopeId) {
        mutableStateOf<Map<String, WarmedSeriesArtwork>>(emptyMap())
    }
    LaunchedEffect(activeServerId, profileScopeId, continueWatchingWarmTargets) {
        // Warm each Continue Watching destination in row order. Within one
        // title, its parent detail, exact episode and selector data run in
        // parallel. CatalogRepository keeps these calls alive/single-flight if
        // the user taps while this effect is still awaiting them.
        for (target in continueWatchingWarmTargets) {
            coroutineScope {
                val parentDetail = async {
                    catalogRepository.warmItemDetail(target.seriesId)
                }
                val seasons = async {
                    catalogRepository.warmSeasons(target.seriesId)
                }
                val episodes = async {
                    target.seasonNumber?.let { seasonNumber ->
                        catalogRepository.warmEpisodes(target.seriesId, seasonNumber)
                    }
                }
                val episodeDetail = async {
                    catalogRepository.warmItemDetail(target.episodeContentId)
                }

                val resolvedParent = when (val result = parentDetail.await()) {
                    is ApiResult.Success -> result.data
                    else -> null
                }
                if (resolvedParent != null) {
                    val artwork = WarmedSeriesArtwork(
                        url = resolvedParent.backdropUrl ?: resolvedParent.posterUrl,
                        thumbhash = resolvedParent.backdropThumbhash
                            ?: resolvedParent.posterThumbhash,
                    )
                    warmedSeriesArtwork = warmedSeriesArtwork + (target.seriesId to artwork)
                    artwork.url?.takeIf { it.isNotBlank() }?.let { artworkUrl ->
                        context.imageLoader.enqueue(
                            ImageRequest.Builder(context)
                                .data(artworkUrl)
                                .size(
                                    context.resources.displayMetrics.widthPixels.coerceAtLeast(1),
                                    context.resources.displayMetrics.heightPixels.coerceAtLeast(1),
                                )
                                .build(),
                        )
                    }
                }
                seasons.await()
                episodes.await()
                episodeDetail.await()
            }
        }
    }
    // iOS Home no longer samples the centered Continue Watching artwork; it
    // sits on the fixed page canvas so scrolling the row never recolors the
    // page (silo-apple PR #222).
    val homeSurface = MaterialTheme.colorScheme.background
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
    val currentBottomNavCallback by rememberUpdatedState(onBottomNavMinimizedChange)
    LaunchedEffect(listState) {
        var previousIndex = listState.firstVisibleItemIndex
        var previousOffset = listState.firstVisibleItemScrollOffset
        snapshotFlow {
            Triple(
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
                listState.isScrollInProgress,
            )
        }.collect { (index, offset, isScrolling) ->
            val isAtTop = index == 0 && offset == 0
            if (isAtTop) {
                currentBottomNavCallback(false)
            } else if (isScrolling) {
                val movingDown = index > previousIndex ||
                    (index == previousIndex && offset > previousOffset)
                if (movingDown) currentBottomNavCallback(true)
            }
            previousIndex = index
            previousOffset = offset
        }
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
    // Home can show the same item in several rows at once. Each poster placement
    // now carries a unique hero key (see MediaCard) so duplicates never collide
    // in the shared-transition layout — no per-screen claim registry needed.
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(homeSurface),
    ) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawBehind {
                    drawRect(
                        brush = Brush.radialGradient(
                            colorStops = arrayOf(
                                0.00f to Color.White.copy(alpha = 0.10f),
                                0.26f to Color.White.copy(alpha = 0.055f),
                                0.58f to Color.White.copy(alpha = 0.018f),
                                1.00f to Color.Transparent,
                            ),
                            center = Offset(size.width * 0.46f, size.height * 0.48f),
                            radius = 470.dp.toPx(),
                        ),
                    )
                    drawRect(
                        brush = Brush.verticalGradient(
                            colorStops = arrayOf(
                                0.00f to Color.White.copy(alpha = 0.025f),
                                0.24f to Color.Transparent,
                                0.48f to Color.White.copy(alpha = 0.025f),
                                0.82f to Color.Transparent,
                                1.00f to Color.White.copy(alpha = 0.012f),
                            ),
                        ),
                    )
                },
        )
        when {
            state.isLoading && regularSections.isEmpty() -> HomeLoadingSkeleton()
            state.error != null && regularSections.isEmpty() -> ErrorView(
                message = state.error ?: "Something went wrong",
                onRetry = { viewModel.loadSections() },
            )
            regularSections.isEmpty() -> {
                if (populatedServerSections.isNotEmpty()) {
                    EmptyStateView(
                        title = "Home sections are hidden",
                        subtitle = "Choose which rows appear in Settings → Interface → Home Sections.",
                    )
                } else {
                    EmptyStateView(
                        title = "Nothing to watch yet",
                        subtitle = "Add media to your libraries or start watching to see it here.",
                    )
                }
            }
            else -> PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = { viewModel.refresh() },
                // Background sits inside the source so the glass captures an
                // opaque scene; a transparent capture composites the blur over
                // the sharp content beneath instead of replacing it.
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Transparent),
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
                        item(key = "homeIdentity") {
                            Row(
                                modifier = Modifier
                                    .windowInsetsPadding(WindowInsets.statusBars)
                                    .fillMaxWidth()
                                    .height(64.dp)
                                    .padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                SiloWordmark()
                            }
                        }

                        items(
                            items = regularSections,
                            key = { it.id },
                            contentType = { "section-row" },
                        ) { section ->
                            val opensUnifiedSeriesDetail =
                                section.sectionType == "continue_watching" ||
                                    section.sectionType == "in_progress"
                            HomeSectionRow(
                                section = section,
                                onItemClick = { contentId ->
                                    val item = section.items.firstOrNull { it.contentId == contentId }
                                    if (opensUnifiedSeriesDetail && item != null) {
                                        item.seriesId
                                            ?.takeIf {
                                                item.type.equals("episode", ignoreCase = true) &&
                                                    it.isNotBlank()
                                            }
                                            ?.let { seriesId ->
                                                val knownParent = regularSections
                                                    .asSequence()
                                                    .flatMap { it.items.asSequence() }
                                                    .firstOrNull { it.contentId == seriesId }
                                                val artwork = warmedSeriesArtwork[seriesId]
                                                    ?: knownParent?.let {
                                                        WarmedSeriesArtwork(
                                                            url = it.backdropUrl ?: it.posterUrl,
                                                            thumbhash = it.backdropThumbhash
                                                                ?: it.posterThumbhash,
                                                        )
                                                    }
                                                // MediaRow initially hands off the episode
                                                // still. Replace it with the actual parent
                                                // series artwork before navigation so the
                                                // loading frame matches a direct series tap.
                                                artwork?.let {
                                                    heroHandoff?.pendingArtworkUrl = it.url
                                                    heroHandoff?.pendingArtworkThumbhash = it.thumbhash
                                                }
                                                heroHandoff?.pendingBrowseContentIds = listOf(seriesId)
                                            }
                                        onContinueWatchingItemClick(item)
                                    } else {
                                        onItemClick(contentId)
                                    }
                                },
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
    Box(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .padding(top = statusBarPadding.calculateTopPadding())
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                .fillMaxWidth(),
        ) {
            // The SILO identity scrolls with the feed; only utilities remain pinned.
            TabTopBarActions(
                modifier = Modifier.align(Alignment.CenterEnd),
                opaque = true,
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
                            opaque = true,
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
