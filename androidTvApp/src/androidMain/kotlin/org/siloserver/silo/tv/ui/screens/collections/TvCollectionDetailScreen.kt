package org.siloserver.silo.tv.ui.screens.collections

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.focus.FocusRequester
import org.siloserver.silo.tv.ui.focus.rememberTvFlatReturnRestoration
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.siloserver.silo.tv.ui.components.TvCatalogEmptyState
import org.siloserver.silo.tv.ui.components.TvCatalogGrid
import org.siloserver.silo.tv.ui.components.TvErrorScreen
import org.siloserver.silo.tv.ui.components.TvLoadingScreen
import org.siloserver.silo.tv.ui.theme.Spacing
import org.siloserver.silo.tv.ui.theme.tvPresetGridColumns

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvCollectionDetailScreen(
    collectionId: String,
    title: String,
    onItemClick: (contentId: String) -> Unit,
    onBack: () -> Unit,
    viewModel: TvCollectionDetailViewModel = koinViewModel(
        key = "collection-$collectionId",
        parameters = { parametersOf(collectionId, title) },
    ),
) {
    val state by viewModel.uiState.collectAsState()
    val gridState = rememberLazyGridState()

    BackHandler(enabled = true) { onBack() }

    val restoreItemFocusRequester = remember { FocusRequester() }
    // Returns land on the card the viewer opened, not the top of the
    // collection. This also covers first entry, where no target is recorded and
    // the resolution is the first item — the same place the plain initial focus
    // put it.
    //
    // Not quite the same lifecycle, though: the old adapter re-armed whenever
    // the first item's identity changed, whereas this runs once. Harmless here,
    // because loading only appends pages so the first item does not move, but a
    // surface that replaces its contents in place would need the difference
    // thought about rather than assumed.
    val restoration = rememberTvFlatReturnRestoration(
        itemIds = state.items.map { it.contentId },
        hasMore = state.hasMore,
        isLoadingMore = state.isLoadingMore,
        errorMessage = state.error,
        surfaceKey = collectionId,
        onLoadMore = viewModel::loadMore,
        scrollToItem = { itemIndex -> gridState.scrollToItem(itemIndex) },
        requestFocus = restoreItemFocusRequester::requestFocus,
        onRestored = {},
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Text(
            text = state.name.ifBlank { title },
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
        )

        when {
            state.isLoading && state.items.isEmpty() -> TvLoadingScreen()
            state.error != null -> TvErrorScreen(
                message = state.error!!,
                onRetry = viewModel::retry,
            )
            else -> TvCatalogGrid(
                items = state.items,
                isLoading = state.isLoadingMore,
                hasMore = state.hasMore,
                onItemClick = { contentId ->
                    restoration.onItemClicked(
                        itemId = contentId,
                        index = state.items.indexOfFirst { it.contentId == contentId },
                    )
                    onItemClick(contentId)
                },
                onLoadMore = viewModel::loadMore,
                // Same fixed 6-column metrics as the library collection page
                // (and Browse). The adaptive default packed only 4 columns on a
                // 1080p TV, so user-collection posters read far larger than
                // server-collection ones opened from the same Collections tab.
                fixedColumnCount = tvPresetGridColumns(6),
                contentPadding = PaddingValues(
                    start = Spacing.safeArea,
                    top = Spacing.lg,
                    end = Spacing.safeArea,
                    bottom = Spacing.xxxl,
                ),
                horizontalSpacing = 20.dp,
                verticalSpacing = 30.dp,
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
                emptyState = {
                    TvCatalogEmptyState(message = "This collection is empty.")
                },
            )
        }
    }
}
