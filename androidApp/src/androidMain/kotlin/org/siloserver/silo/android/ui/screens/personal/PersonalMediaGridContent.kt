package org.siloserver.silo.android.ui.screens.personal

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.components.EmptyStateView
import org.siloserver.silo.android.ui.components.ErrorView
import org.siloserver.silo.android.ui.components.PosterGridSkeleton
import org.siloserver.silo.android.ui.components.rememberShimmerProgress
import org.siloserver.silo.android.ui.components.MediaCardContextMenu
import org.siloserver.silo.android.ui.components.MediaGridDefaults
import org.siloserver.silo.android.ui.components.WatchedBadge
import org.siloserver.silo.android.ui.components.rememberBrowseItemCardActions
import org.siloserver.silo.common.cards.LocalCardPresentation
import org.siloserver.silo.common.ui.components.DeferImagePresentationWhileScrolling
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.common.overlays.CardOverlayVariant
import org.siloserver.silo.common.overlays.CardOverlays
import org.siloserver.silo.common.overlays.LocalCardOverlayUiState
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.overlays.OverlayDataExtractor
import org.siloserver.silo.viewmodel.FavoritesViewModel
import org.siloserver.silo.viewmodel.HistoryViewModel
import org.siloserver.silo.viewmodel.PersonalListQuery
import org.siloserver.silo.viewmodel.PersonalListUiState
import org.siloserver.silo.viewmodel.WatchlistViewModel
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun FavoritesGridContent(
    onItemClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    header: (@Composable (PersonalListUiState) -> Unit)? = null,
    /** Sort/filter to fetch with; null keeps whatever the ViewModel has. */
    query: PersonalListQuery? = null,
    viewModel: FavoritesViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    if (query != null) {
        LaunchedEffect(query) { viewModel.applyQuery(query) }
    }

    PersonalMediaGridContent(
        state = state,
        modifier = modifier,
        contentPadding = contentPadding,
        header = header,
        emptyTitle = "No favorites",
        emptySubtitle = "Tap the heart icon on any item to add it here",
        emptyIcon = Icons.Outlined.FavoriteBorder,
        onRetry = viewModel::retry,
        onRefresh = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        itemContent = { item ->
            MediaGridItem(
                item = item,
                onClick = { onItemClick(item.contentId) },
                onFavoriteToggle = { viewModel.toggleFavorite(item.contentId) },
                isFavorite = true,
            )
        },
    )
}

@Composable
fun WatchlistGridContent(
    onItemClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    header: (@Composable (PersonalListUiState) -> Unit)? = null,
    /** Sort/filter to fetch with; null keeps whatever the ViewModel has. */
    query: PersonalListQuery? = null,
    viewModel: WatchlistViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    if (query != null) {
        LaunchedEffect(query) { viewModel.applyQuery(query) }
    }

    PersonalMediaGridContent(
        state = state,
        modifier = modifier,
        contentPadding = contentPadding,
        header = header,
        emptyTitle = "Watchlist is empty",
        emptySubtitle = "Tap the bookmark icon on any item to add it here",
        emptyIcon = Icons.Outlined.BookmarkBorder,
        onRetry = viewModel::retry,
        onRefresh = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        itemContent = { item ->
            MediaGridItem(
                item = item,
                onClick = { onItemClick(item.contentId) },
                onWatchlistToggle = { viewModel.removeFromWatchlist(item.contentId) },
                isInWatchlist = true,
            )
        },
    )
}

@Composable
fun HistoryGridContent(
    onItemClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    header: (@Composable (PersonalListUiState) -> Unit)? = null,
    viewModel: HistoryViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    PersonalMediaGridContent(
        state = state,
        modifier = modifier,
        contentPadding = contentPadding,
        header = header,
        emptyTitle = "No watch history",
        emptySubtitle = "Items you watch will appear here",
        emptyIcon = Icons.Outlined.History,
        onRetry = viewModel::retry,
        onRefresh = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        itemContent = { item ->
            MediaGridItem(
                item = item,
                onClick = { onItemClick(item.contentId) },
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonalMediaGridContent(
    state: PersonalListUiState,
    emptyTitle: String,
    emptySubtitle: String,
    emptyIcon: ImageVector,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    itemContent: @Composable (BrowseItem) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    // Optional full-width row that scrolls with the grid (For You's saved-list
    // pills, the sort/filter controls). It also renders above the loading /
    // empty / error views so the controls stay reachable when the list has
    // nothing to show. Receives the state so it can show the item count.
    header: (@Composable (PersonalListUiState) -> Unit)? = null,
) {
    val gridState = rememberLazyGridState()
    val layoutDirection = LocalLayoutDirection.current

    val shouldLoadMore by remember {
        derivedStateOf {
            val layoutInfo = gridState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= layoutInfo.totalItemsCount - 8
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && state.hasMore && !state.isLoadingMore && !state.isLoading) {
            onLoadMore()
        }
    }

    when {
        state.isLoading -> {
            // Poster-grid skeleton (not a spinner) so the list keeps its shape
            // while it loads; the header's controls stay reachable above it.
            Column(modifier = modifier.padding(contentPadding)) {
                header?.let { Box(modifier = Modifier.padding(16.dp)) { it(state) } }
                PosterGridSkeleton(
                    progress = rememberShimmerProgress(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
        state.error != null && state.items.isEmpty() -> {
            Column(modifier = modifier.padding(contentPadding)) {
                header?.let { Box(modifier = Modifier.padding(16.dp)) { it(state) } }
                ErrorView(
                    message = state.error ?: "Unknown error",
                    onRetry = onRetry,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        state.items.isEmpty() -> {
            Column(modifier = modifier.padding(contentPadding)) {
                header?.let { Box(modifier = Modifier.padding(16.dp)) { it(state) } }
                // A narrowed query with no hits is not an empty list — say so,
                // and keep the header's controls reachable to widen it (TV parity).
                val filtered = !state.query.isDefault
                EmptyStateView(
                    title = if (filtered) "No matches" else emptyTitle,
                    subtitle = if (filtered) "No titles match the current filters." else emptySubtitle,
                    icon = emptyIcon,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        else -> {
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = onRefresh,
                modifier = modifier.fillMaxSize(),
            ) {
                // contentPadding goes inside the grid so items scroll edge to
                // edge under any chrome the caller reserved space for.
                DeferImagePresentationWhileScrolling(gridState) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(MediaGridDefaults.scaledPosterGridMinWidth),
                    state = gridState,
                    contentPadding = PaddingValues(
                        start = 16.dp + contentPadding.calculateStartPadding(layoutDirection),
                        top = 16.dp + contentPadding.calculateTopPadding(),
                        end = 16.dp + contentPadding.calculateEndPadding(layoutDirection),
                        bottom = 16.dp + contentPadding.calculateBottomPadding(),
                    ),
                    horizontalArrangement = Arrangement.spacedBy(MediaGridDefaults.PosterGridHorizontalSpacing),
                    verticalArrangement = Arrangement.spacedBy(MediaGridDefaults.PosterGridVerticalSpacing),
                ) {
                    if (header != null) {
                        item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                            header(state)
                        }
                    }
                    items(
                        items = state.items,
                        key = { it.contentId },
                        contentType = { item -> item.type },
                    ) { item ->
                        Box(modifier = Modifier.animateItem()) {
                            itemContent(item)
                        }
                    }

                    if (state.isLoadingMore) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                    }
                }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaGridItem(
    item: BrowseItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onFavoriteToggle: (() -> Unit)? = null,
    isFavorite: Boolean = false,
    onWatchlistToggle: (() -> Unit)? = null,
    isInWatchlist: Boolean = false,
) {
    val (actions, userState) = rememberBrowseItemCardActions(item)
    val overlayState = LocalCardOverlayUiState.current
    var menuExpanded by remember { mutableStateOf(false) }

    androidx.compose.foundation.layout.Column(
        modifier = modifier.combinedClickable(
            onClick = onClick,
            onLongClick = { menuExpanded = true },
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3.3f)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            ThumbhashImage(
                url = item.posterUrl,
                thumbhash = item.posterThumbhash,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
            )

            if (overlayState.enabled) {
                CardOverlays(
                    data = OverlayDataExtractor.fromBrowseItem(item),
                    prefs = overlayState.prefs,
                    variant = CardOverlayVariant.Poster,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (userState.played) {
                WatchedBadge(modifier = Modifier.align(Alignment.TopEnd))
            }
        }

        val cardCaption = LocalCardPresentation.current.caption
        if (cardCaption.showsTitle) {
            androidx.compose.material3.Text(
                text = item.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (cardCaption.showsMetadata && item.year > 0) {
            androidx.compose.material3.Text(
                text = item.year.toString(),
                fontSize = 12.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        MediaCardContextMenu(
            expanded = menuExpanded,
            onDismiss = { menuExpanded = false },
            actions = actions,
            isPlayed = userState.played,
            isFavorite = userState.isFavorite,
            isInWatchlist = userState.inWatchlist,
        )
    }
}
