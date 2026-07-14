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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.ui.components.SiloWordmark
import org.siloserver.silo.android.ui.components.EmptyStateView
import org.siloserver.silo.android.ui.components.ErrorView
import org.siloserver.silo.android.ui.components.MediaRowSkeleton
import org.siloserver.silo.android.ui.components.rememberShimmerProgress
import org.siloserver.silo.android.ui.screens.pairing.CompanionPairingViewModel
import org.siloserver.silo.android.ui.screens.pairing.CompanionPairingBottomOverlay
import org.siloserver.silo.android.ui.screens.profiles.ProfileAvatar
import org.siloserver.silo.common.pairing.CompanionPairingStatus
import org.siloserver.silo.common.pairing.CompanionPairingTarget
import org.siloserver.silo.model.catalog.isAudiobookItemType
import org.siloserver.silo.model.profile.Profile
import org.siloserver.silo.model.section.splitFeatured
import org.siloserver.silo.viewmodel.HomeViewModel
import org.siloserver.silo.android.ui.navigation.LocalBottomChromeInset
import org.koin.compose.viewmodel.koinViewModel

private const val ChromeFadeDistanceDp = 72f

/**
 * Phone Home screen.
 *
 * Mirrors iOS `HomeView.swift` (phone) 1:1: a flat OLED background (no hero —
 * iOS deliberately excludes `featured` sections from Home so the configured
 * Home rows render without a separate hero surface), a runway spacer that
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
    // iOS Home excludes `featured` sections entirely (HomeViewModel.regularSections)
    // — Home renders only the configured rows, never a hero billboard.
    val regularSections = remember(sections) {
        sections.splitFeatured().rest.filter { it.items.isNotEmpty() }
    }

    val listState = rememberLazyListState()
    LaunchedEffect(scrollToTopTick) {
        if (scrollToTopTick > 0) listState.animateScrollToItem(0)
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
    val scrollProgress by remember(chromeFadePx) {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) {
                1f
            } else {
                (listState.firstVisibleItemScrollOffset / chromeFadePx).coerceIn(0f, 1f)
            }
        }
    }

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
                modifier = Modifier.fillMaxSize(),
            ) {
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

        // Floating top chrome — fades in a glass surface as content scrolls under.
        HomeFloatingChrome(
            scrollProgress = scrollProgress,
            activeProfile = activeProfile,
            onSearchClick = onSearchClick,
            onRemoteControlClick = onRemoteControlClick,
            onRemoteChooseTvClick = onRemoteChooseTvClick,
            onRemoteDisconnectClick = onRemoteDisconnectClick,
            isRemoteControlActive = isRemoteControlActive,
            onRequestsClick = onRequestsClick,
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
    scrollProgress: Float,
    activeProfile: Profile?,
    onSearchClick: () -> Unit,
    onRemoteControlClick: () -> Unit,
    onRemoteChooseTvClick: () -> Unit,
    onRemoteDisconnectClick: () -> Unit,
    isRemoteControlActive: Boolean,
    onRequestsClick: (() -> Unit)?,
    onSettingsClick: () -> Unit,
    onSwitchProfileClick: () -> Unit,
    onSwitchServerClick: () -> Unit,
    onSignOutClick: () -> Unit,
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    // iOS chrome: translucent glass fill plus a bottom hairline that strengthens
    // as it fades in (white 0.06 → 0.10, 0.75pt). headerTopReclaim(16) pulls the
    // row up beside the status-bar glyphs; horizontal = SiloTheme.padding(16),
    // bottom = SiloTheme.smallPadding(8).
    val hairlineAlpha = 0.06f + 0.04f * scrollProgress
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.32f * scrollProgress),
            ),
    ) {
        Box(
            modifier = Modifier
                .padding(top = statusBarPadding.calculateTopPadding())
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                .fillMaxWidth(),
        ) {
            // Leading: Silo wordmark (iOS SiloWordmarkView width: 72).
            SiloWordmark(
                modifier = Modifier
                    .align(Alignment.CenterStart),
                width = 72.dp,
            )

            // Trailing: search + profile menu cluster.
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Mirrors Apple's SiloControlModeButton: chrome-free at rest,
                // filled disc while controlling a TV; the active state opens a
                // menu instead of jumping straight to the remote.
                Box {
                    var remoteMenuExpanded by remember { mutableStateOf(false) }
                    HomeChromeButton(
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

                HomeChromeButton(onClick = onSearchClick) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = "Search",
                    )
                }

                HomeProfileMenu(
                    activeProfile = activeProfile,
                    onRequestsClick = onRequestsClick,
                    onSettingsClick = onSettingsClick,
                    onSwitchProfileClick = onSwitchProfileClick,
                    onSwitchServerClick = onSwitchServerClick,
                    onSignOutClick = onSignOutClick,
                )
            }
        }

        // Bottom hairline border (iOS 0.75pt, white 0.06–0.10).
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(0.75.dp)
                .background(Color.White.copy(alpha = hairlineAlpha)),
        )
    }
}

@Composable
private fun HomeChromeButton(
    onClick: () -> Unit,
    isActive: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    // iOS top-bar icon buttons are bare 40x40 tap targets (no chip background).
    Surface(
        onClick = onClick,
        color = if (isActive) MaterialTheme.colorScheme.onSurface else Color.Transparent,
        contentColor = if (isActive) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface,
        shape = CircleShape,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Box(
            modifier = Modifier.size(40.dp),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

@Composable
private fun HomeProfileMenu(
    activeProfile: Profile?,
    onRequestsClick: (() -> Unit)?,
    onSettingsClick: () -> Unit,
    onSwitchProfileClick: () -> Unit,
    onSwitchServerClick: () -> Unit,
    onSignOutClick: () -> Unit,
) {
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    Box {
        HomeChromeButton(onClick = { menuExpanded = true }) {
            if (activeProfile != null) {
                // iOS ProfileAvatarView size: 36.
                ProfileAvatar(
                    avatar = activeProfile.avatar,
                    name = activeProfile.name,
                    size = 36.dp,
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            if (onRequestsClick != null) {
                DropdownMenuItem(
                    text = { Text("Requests") },
                    onClick = {
                        menuExpanded = false
                        onRequestsClick()
                    },
                )
                HorizontalDivider()
            }
            DropdownMenuItem(
                text = { Text("Settings") },
                onClick = {
                    menuExpanded = false
                    onSettingsClick()
                },
            )
            DropdownMenuItem(
                text = { Text("Switch Profile") },
                onClick = {
                    menuExpanded = false
                    onSwitchProfileClick()
                },
            )
            DropdownMenuItem(
                text = { Text("Switch Server") },
                onClick = {
                    menuExpanded = false
                    onSwitchServerClick()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = "Sign Out",
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    menuExpanded = false
                    onSignOutClick()
                },
            )
        }
    }
}
