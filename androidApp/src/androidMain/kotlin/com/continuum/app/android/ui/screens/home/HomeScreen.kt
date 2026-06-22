package com.continuum.app.android.ui.screens.home

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import com.continuum.app.android.ui.components.ContinuumWordmark
import com.continuum.app.android.ui.components.EmptyStateView
import com.continuum.app.android.ui.components.ErrorView
import com.continuum.app.android.ui.components.MediaRowSkeleton
import com.continuum.app.android.ui.components.rememberShimmerProgress
import com.continuum.app.android.ui.screens.profiles.ProfileAvatar
import com.continuum.app.model.profile.Profile
import com.continuum.app.model.section.splitFeatured
import com.continuum.app.viewmodel.HomeViewModel

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
    onSeeAllClick: (String) -> Unit,
    viewModel: HomeViewModel,
    activeProfile: Profile?,
    onSearchClick: () -> Unit,
    onPersonalListsClick: () -> Unit,
    onCalendarClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSwitchProfileClick: () -> Unit,
    onSwitchServerClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val sections = state.sections
    // iOS Home excludes `featured` sections entirely (HomeViewModel.regularSections)
    // — Home renders only the configured rows, never a hero billboard.
    val regularSections = remember(sections) {
        sections.splitFeatured().rest.filter { it.items.isNotEmpty() }
    }

    val listState = rememberLazyListState()
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
                    // iOS `sectionSpacing` = ContinuumTheme.largePadding (24).
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
                            onSeeAllClick = { onSeeAllClick(section.id) },
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

                    // iOS bottom padding = ContinuumTheme.largePadding (24).
                    item(key = "bottomPad") {
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }
            }
        }

        // Floating top chrome — fades in a glass surface as content scrolls under.
        HomeFloatingChrome(
            scrollProgress = scrollProgress,
            activeProfile = activeProfile,
            onSearchClick = onSearchClick,
            onPersonalListsClick = onPersonalListsClick,
            onCalendarClick = onCalendarClick,
            onSettingsClick = onSettingsClick,
            onSwitchProfileClick = onSwitchProfileClick,
            onSwitchServerClick = onSwitchServerClick,
        )
    }
}

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
    onPersonalListsClick: () -> Unit,
    onCalendarClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSwitchProfileClick: () -> Unit,
    onSwitchServerClick: () -> Unit,
) {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    // iOS chrome: translucent glass fill plus a bottom hairline that strengthens
    // as it fades in (white 0.06 → 0.10, 0.75pt). headerTopReclaim(16) pulls the
    // row up beside the status-bar glyphs; horizontal = ContinuumTheme.padding(16),
    // bottom = ContinuumTheme.smallPadding(8).
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
            ContinuumWordmark(
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
                HomeChromeButton(onClick = onSearchClick) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = "Search",
                    )
                }

                HomeProfileMenu(
                    activeProfile = activeProfile,
                    onPersonalListsClick = onPersonalListsClick,
                    onCalendarClick = onCalendarClick,
                    onSettingsClick = onSettingsClick,
                    onSwitchProfileClick = onSwitchProfileClick,
                    onSwitchServerClick = onSwitchServerClick,
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
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    // iOS top-bar icon buttons are bare 40x40 tap targets (no chip background).
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
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
    onPersonalListsClick: () -> Unit,
    onCalendarClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSwitchProfileClick: () -> Unit,
    onSwitchServerClick: () -> Unit,
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
            DropdownMenuItem(
                text = { Text("Favorites & Watchlist") },
                onClick = {
                    menuExpanded = false
                    onPersonalListsClick()
                },
            )
            DropdownMenuItem(
                text = { Text("Calendar") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.CalendarMonth,
                        contentDescription = null,
                    )
                },
                onClick = {
                    menuExpanded = false
                    onCalendarClick()
                },
            )
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
        }
    }
}
