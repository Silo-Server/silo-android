package org.siloserver.silo.android.ui.screens.collections

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.android.ui.components.EmptyStateView
import org.siloserver.silo.android.ui.components.ErrorView
import org.siloserver.silo.android.ui.components.LoadingIndicator
import org.siloserver.silo.android.ui.components.MediaGridDefaults
import org.siloserver.silo.android.ui.components.SiloTopBar
import org.siloserver.silo.android.ui.screens.personal.MediaGridItem
import org.siloserver.silo.common.ui.components.DeferImagePresentationWhileScrolling

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionDetailScreen(
    collectionId: String,
    onBackClick: () -> Unit,
    onItemClick: (String) -> Unit,
    viewModel: CollectionDetailViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val gridState = rememberLazyGridState()

    LaunchedEffect(collectionId) {
        viewModel.initialize(collectionId)
    }

    val shouldLoadMore by remember {
        derivedStateOf {
            val layoutInfo = gridState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= layoutInfo.totalItemsCount - 8
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && state.hasMore && !state.isLoadingMore && !state.isLoading) {
            viewModel.loadMore()
        }
    }

    Scaffold(
        topBar = {
            SiloTopBar(
                title = state.title,
                onBackClick = onBackClick,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when {
            state.isLoading -> {
                LoadingIndicator(modifier = Modifier.padding(padding))
            }
            state.error != null && state.items.isEmpty() -> {
                ErrorView(
                    message = state.error ?: "Unknown error",
                    onRetry = viewModel::refresh,
                    modifier = Modifier.padding(padding),
                )
            }
            state.items.isEmpty() && !state.isLoading -> {
                EmptyStateView(
                    title = "Collection is empty",
                    subtitle = "This collection does not have any items yet.",
                    icon = Icons.Outlined.CollectionsBookmark,
                    modifier = Modifier.padding(padding),
                )
            }
            else -> {
                androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = viewModel::refresh,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    DeferImagePresentationWhileScrolling(gridState) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(MediaGridDefaults.scaledPosterGridMinWidth),
                        state = gridState,
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(MediaGridDefaults.PosterGridHorizontalSpacing),
                        verticalArrangement = Arrangement.spacedBy(MediaGridDefaults.PosterGridVerticalSpacing),
                    ) {
                        items(
                            items = state.items,
                            key = { it.contentId },
                        ) { item ->
                            MediaGridItem(
                                item = item,
                                onClick = { onItemClick(item.contentId) },
                            )
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
}
