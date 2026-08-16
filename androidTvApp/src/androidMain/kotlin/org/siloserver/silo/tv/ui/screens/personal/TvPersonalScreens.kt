package org.siloserver.silo.tv.ui.screens.personal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import org.siloserver.silo.tv.ui.focus.rememberTvFlatReturnRestoration
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.tv.ui.components.TvCatalogGrid
import org.siloserver.silo.tv.ui.components.TvErrorScreen
import org.siloserver.silo.tv.ui.components.TvLoadingScreen
import org.siloserver.silo.tv.ui.theme.SiloBlue
import org.siloserver.silo.tv.ui.theme.Spacing
import org.siloserver.silo.tv.ui.theme.sectionEyebrow
import org.siloserver.silo.tv.ui.theme.tvPageContentPadding
import org.siloserver.silo.tv.ui.theme.tvPageStartPadding
import org.siloserver.silo.viewmodel.FavoritesViewModel
import org.siloserver.silo.viewmodel.HistoryViewModel
import org.siloserver.silo.tv.ui.shell.TvTopMenuLayout
import org.siloserver.silo.viewmodel.PersonalListUiState
import org.siloserver.silo.viewmodel.PersonalListViewModel
import org.siloserver.silo.viewmodel.WatchlistViewModel
import org.koin.compose.viewmodel.koinViewModel

/**
 * Favorites / Watchlist / History screens all share the same `6-column grid
 * with pagination` layout — the only thing that differs is the header icon,
 * the title, and the ViewModel they read from. This file has one composable
 * per screen that forwards to a shared [PersonalGrid] helper.
 *
 * Navigated to from Settings → Library shortcuts (Phase F). None of the
 * three appears directly on the navigation rail, matching tvOS.
 */

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvFavoritesScreen(
    onItemClick: (contentId: String) -> Unit,
    onInitialContentFocus: () -> Unit = {},
    viewModel: FavoritesViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    PersonalListResumeRefresh(viewModel)
    PersonalGrid(
        title = "Favorites",
        surfaceKey = "personal-favorites",
        icon = Icons.Filled.Favorite,
        emptyMessage = "No favorites yet",
        state = state,
        onItemClick = onItemClick,
        onLoadMore = viewModel::loadMore,
        onRetry = viewModel::retry,
        onInitialContentFocus = onInitialContentFocus,
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvWatchlistScreen(
    onItemClick: (contentId: String) -> Unit,
    onInitialContentFocus: () -> Unit = {},
    viewModel: WatchlistViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    PersonalListResumeRefresh(viewModel)
    PersonalGrid(
        title = "Watchlist",
        surfaceKey = "personal-watchlist",
        icon = Icons.Outlined.BookmarkBorder,
        emptyMessage = "Your watchlist is empty",
        state = state,
        onItemClick = onItemClick,
        onLoadMore = viewModel::loadMore,
        onRetry = viewModel::retry,
        onInitialContentFocus = onInitialContentFocus,
    )
}

/** Saved-list content embedded in the For You page, without secondary-page chrome. */
@Composable
fun TvFavoritesInline(
    onItemClick: (contentId: String) -> Unit,
    modifier: Modifier = Modifier,
    firstItemFocusRequester: FocusRequester? = null,
    viewModel: FavoritesViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    PersonalListResumeRefresh(viewModel)
    PersonalInlineGrid(
        state = state,
        emptyMessage = "No favorites yet",
        emptyIcon = Icons.Filled.Favorite,
        onItemClick = onItemClick,
        onLoadMore = viewModel::loadMore,
        onRetry = viewModel::retry,
        firstItemFocusRequester = firstItemFocusRequester,
        modifier = modifier,
    )
}

/** Saved-list content embedded in the For You page, without secondary-page chrome. */
@Composable
fun TvWatchlistInline(
    onItemClick: (contentId: String) -> Unit,
    modifier: Modifier = Modifier,
    firstItemFocusRequester: FocusRequester? = null,
    viewModel: WatchlistViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    PersonalListResumeRefresh(viewModel)
    PersonalInlineGrid(
        state = state,
        emptyMessage = "Your watchlist is empty",
        emptyIcon = Icons.Outlined.BookmarkBorder,
        onItemClick = onItemClick,
        onLoadMore = viewModel::loadMore,
        onRetry = viewModel::retry,
        firstItemFocusRequester = firstItemFocusRequester,
        modifier = modifier,
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvHistoryScreen(
    onItemClick: (contentId: String) -> Unit,
    onInitialContentFocus: () -> Unit = {},
    viewModel: HistoryViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    PersonalListResumeRefresh(viewModel)
    PersonalGrid(
        title = "Watch History",
        surfaceKey = "personal-history",
        icon = Icons.Filled.History,
        emptyMessage = "No watch history yet",
        state = state,
        onItemClick = onItemClick,
        onLoadMore = viewModel::loadMore,
        onRetry = viewModel::retry,
        onInitialContentFocus = onInitialContentFocus,
    )
}

/**
 * Re-pull a personal list when the screen returns to the foreground. TV has no
 * pull-to-refresh, and these lists load once in `init` and never re-fetch on
 * their own. Card long-press toggles (favorite/watchlist/watched) write only to
 * per-card optimistic state, so an item removed here — or on any other surface —
 * would otherwise linger as a ghost entry until this back-stack entry is popped.
 * ON_RESUME re-fetch is the least-invasive self-heal (mirrors the other TV
 * screens). Gating on [PersonalListViewModel.hasLoadedOnce] (VM-scoped, not
 * composition-scoped) keeps the first-entry replay suppressed naturally: the
 * `init` load's isLoading=true covers the very first resume, and re-entering
 * the composition can't reset the gate the way a remembered flag did.
 */
@Composable
private fun PersonalListResumeRefresh(viewModel: PersonalListViewModel) {
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val current = viewModel.uiState.value
                if (viewModel.hasLoadedOnce && !current.isLoading && !current.isRefreshing) {
                    viewModel.refresh()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PersonalGrid(
    title: String,
    icon: ImageVector,
    emptyMessage: String,
    surfaceKey: String,
    state: PersonalListUiState,
    onItemClick: (contentId: String) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onInitialContentFocus: () -> Unit,
) {
    val startPadding = tvPageStartPadding()
    val gridState = rememberLazyGridState()
    val restoreItemFocusRequester = remember { FocusRequester() }

    val restoration = rememberTvFlatReturnRestoration(
        itemIds = state.items.map { it.contentId },
        hasMore = state.hasMore,
        isLoadingMore = state.isLoadingMore,
        // These lists refresh on every resume — exactly when a viewer comes
        // back from a detail page — and a refresh REPLACES the items with page
        // one rather than appending. Folding it into isLoadingMore was not
        // enough: a stale multi-page list still contains the target, so it
        // resolves before that flag is ever consulted.
        isReplacingContent = state.isRefreshing,
        errorMessage = state.error,
        surfaceKey = surfaceKey,
        onLoadMore = onLoadMore,
        scrollToItem = { itemIndex -> gridState.scrollToItem(itemIndex) },
        requestFocus = restoreItemFocusRequester::requestFocus,
        onRestored = onInitialContentFocus,
    )

    // No separate first-entry path. On a fresh arrival the restoration already
    // targets index zero, so the grid gives that slot to the restore requester
    // and this one was never attached — it requested focus on nothing and then
    // told the shell content had taken focus. One claimant, reporting only
    // once focus is confirmed.

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier.padding(
                start = startPadding,
                end = Spacing.safeArea,
                // Clear the floating top bar — Spacing.xxl left the header
                // underneath the Silo wordmark (QA 2026-07-08).
                top = TvTopMenuLayout.contentTopInset,
                bottom = Spacing.sm,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(
                text = "YOUR LIBRARY",
                style = sectionEyebrow,
                color = SiloBlue.copy(alpha = 0.92f),
            )
            androidx.compose.foundation.layout.Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = SiloBlue,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        when {
            state.isLoading && state.items.isEmpty() -> TvLoadingScreen()
            state.error != null && state.items.isEmpty() -> TvErrorScreen(
                message = state.error ?: "",
                onRetry = onRetry,
            )
            state.items.isEmpty() -> EmptyState(
                message = emptyMessage,
                icon = icon,
            )
            else -> TvCatalogGrid(
                items = state.items,
                // A restored deep scroll position sits at the paging threshold,
                // so the grid would ask for the next page the moment it lands.
                // During a refresh that page is fetched at an offset the
                // refresh is about to invalidate — it either gets discarded or
                // lands after page one and leaves a hole.
                isLoading = state.isLoadingMore || state.isRefreshing,
                hasMore = state.hasMore,
                onItemClick = { contentId ->
                    restoration.onItemClicked(
                        itemId = contentId,
                        index = state.items.indexOfFirst { it.contentId == contentId },
                    )
                    onItemClick(contentId)
                },
                onLoadMore = onLoadMore,
                contentPadding = tvPageContentPadding(top = Spacing.lg),
                // Match every other catalog grid (browse/person/collections):
                // the adaptive default rendered ~5 oversized columns here
                // (QA 2026-07-08).
                fixedColumnCount = 6,
                gridState = gridState,
                restoreItemIndex = restoration.requesterItemIndex,
                restoreItemFocusRequester = restoreItemFocusRequester,
                onRestoreRequesterAttached = restoration::onRequesterAttached,
                onItemFocusedAtIndex = { item, index, focused ->
                    if (focused) {
                        restoration.onItemFocused(item.contentId, index)
                    } else {
                        restoration.onItemFocusLost(item.contentId)
                    }
                },
            )
        }
    }
}

@Composable
private fun PersonalInlineGrid(
    state: PersonalListUiState,
    emptyMessage: String,
    emptyIcon: ImageVector,
    onItemClick: (contentId: String) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    firstItemFocusRequester: FocusRequester? = null,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when {
            state.isLoading && state.items.isEmpty() -> TvLoadingScreen()
            state.error != null && state.items.isEmpty() -> TvErrorScreen(
                message = state.error ?: "",
                onRetry = onRetry,
            )
            // For You hands this grid the page's focus claim, so the empty
            // state has to be able to hold it: with nothing focusable here the
            // shell's handover fails and focus falls back to the menu bar.
            state.items.isEmpty() -> EmptyState(
                message = emptyMessage,
                icon = emptyIcon,
                focusRequester = firstItemFocusRequester,
            )
            else -> TvCatalogGrid(
                items = state.items,
                // A restored deep scroll position sits at the paging threshold,
                // so the grid would ask for the next page the moment it lands.
                // During a refresh that page is fetched at an offset the
                // refresh is about to invalidate — it either gets discarded or
                // lands after page one and leaves a hole.
                isLoading = state.isLoadingMore || state.isRefreshing,
                hasMore = state.hasMore,
                onItemClick = onItemClick,
                onLoadMore = onLoadMore,
                contentPadding = PaddingValues(
                    // For You's saved-list grid sits directly beneath its
                    // selector pills; share their exact leading edge.
                    start = Spacing.safeArea,
                    end = Spacing.safeArea,
                    top = Spacing.md,
                    bottom = Spacing.xl,
                ),
                fixedColumnCount = 6,
                firstItemFocusRequester = firstItemFocusRequester,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun EmptyState(
    message: String,
    icon: ImageVector,
    focusRequester: FocusRequester? = null,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (focusRequester != null) {
                    Modifier.focusRequester(focusRequester).focusable()
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(48.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// Unused import suppressor (Movie icon retained for future use).
@Suppress("unused")
private val _unused = Icons.Filled.Movie
