package com.continuum.app.android.ui.screens.personal

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.continuum.app.android.ui.components.EmptyStateView
import com.continuum.app.android.ui.components.ErrorView
import com.continuum.app.android.ui.components.LoadingIndicator
import com.continuum.app.android.ui.components.MediaCardContextMenu
import com.continuum.app.android.ui.components.MediaGridDefaults
import com.continuum.app.android.ui.components.WatchedBadge
import com.continuum.app.android.ui.components.rememberBrowseItemCardActions
import com.continuum.app.common.ui.components.ThumbhashImage
import com.continuum.app.model.catalog.BrowseItem
import com.continuum.app.viewmodel.FavoritesViewModel
import com.continuum.app.viewmodel.HistoryViewModel
import com.continuum.app.viewmodel.PersonalListUiState
import com.continuum.app.viewmodel.WatchlistViewModel
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun FavoritesGridContent(
    onItemClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    viewModel: FavoritesViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    PersonalMediaGridContent(
        state = state,
        modifier = modifier,
        contentPadding = contentPadding,
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
    viewModel: WatchlistViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    PersonalMediaGridContent(
        state = state,
        modifier = modifier,
        contentPadding = contentPadding,
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
    viewModel: HistoryViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    PersonalMediaGridContent(
        state = state,
        modifier = modifier,
        contentPadding = contentPadding,
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
) {
    val gridState = rememberLazyGridState()

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
            LoadingIndicator(modifier = modifier.padding(contentPadding))
        }
        state.error != null && state.items.isEmpty() -> {
            ErrorView(
                message = state.error ?: "Unknown error",
                onRetry = onRetry,
                modifier = modifier.padding(contentPadding),
            )
        }
        state.items.isEmpty() -> {
            EmptyStateView(
                title = emptyTitle,
                subtitle = emptySubtitle,
                icon = emptyIcon,
                modifier = modifier.padding(contentPadding),
            )
        }
        else -> {
            PullToRefreshBox(
                isRefreshing = state.isRefreshing,
                onRefresh = onRefresh,
                modifier = modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(MediaGridDefaults.PosterGridMinWidth),
                    state = gridState,
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(MediaGridDefaults.PosterGridHorizontalSpacing),
                    verticalArrangement = Arrangement.spacedBy(MediaGridDefaults.PosterGridVerticalSpacing),
                ) {
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
    var menuExpanded by remember { mutableStateOf(false) }

    androidx.compose.foundation.layout.Column(
        modifier = modifier.combinedClickable(
            onClick = onClick,
            onLongClick = { menuExpanded = true },
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box {
            ThumbhashImage(
                url = item.posterUrl,
                thumbhash = item.posterThumbhash,
                contentDescription = item.title,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2f / 3.3f)
                    .clip(RoundedCornerShape(8.dp)),
            )

            if (userState.played) {
                WatchedBadge(modifier = Modifier.align(Alignment.TopEnd))
            }
        }

        androidx.compose.material3.Text(
            text = item.title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        if (item.year > 0) {
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
