package org.siloserver.silo.tv.ui.screens.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.tv.ui.components.TvCatalogEmptyState
import org.siloserver.silo.tv.ui.components.TvCatalogGrid
import org.siloserver.silo.tv.ui.components.TvErrorScreen
import org.siloserver.silo.tv.ui.components.TvLoadingScreen
import org.siloserver.silo.tv.ui.focus.rememberTvFlatReturnRestoration
import org.siloserver.silo.tv.ui.theme.Spacing
import org.siloserver.silo.tv.ui.theme.tvPresetGridColumns
import org.siloserver.silo.viewmodel.SectionBrowseArgs
import org.siloserver.silo.viewmodel.SectionBrowseViewModel

/**
 * A Home or library row's full page, opened from the row's "Explore all"
 * card: the row's titles in a paged poster grid under the row's title.
 */
@Composable
fun TvSectionBrowseScreen(
    args: SectionBrowseArgs,
    onItemClick: (BrowseItem) -> Unit,
    onBack: () -> Unit,
    viewModel: SectionBrowseViewModel = koinViewModel(
        key = sectionBrowseKey(args),
        parameters = { parametersOf(args) },
    ),
) {
    val state by viewModel.uiState.collectAsState()
    val gridState = rememberLazyGridState()

    BackHandler(onBack = onBack)

    // First entry lands on the first card, so the D-pad responds without a
    // hunt; a return from a title's detail lands on the card the viewer
    // opened, paging back to it if needed. A later page never pulls focus.
    val restoreItemFocusRequester = remember { FocusRequester() }
    val restoration = rememberTvFlatReturnRestoration(
        itemIds = state.items.map { it.contentId },
        hasMore = state.hasMore,
        isLoadingMore = state.isLoadingMore,
        // Retry reloads the row from its first page.
        isReplacingContent = state.isLoading,
        errorMessage = state.error ?: state.loadMoreError,
        surfaceKey = sectionBrowseKey(args),
        onLoadMore = viewModel::loadMore,
        // The title is a spanning grid item ahead of the cards.
        scrollToItem = { itemIndex -> gridState.scrollToItem(itemIndex + 1) },
        requestFocus = restoreItemFocusRequester::requestFocus,
        onRestored = {},
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when {
            state.unavailable -> PageMessage(
                title = args.title,
                message = "This row is no longer available.",
            )
            state.isLoading && state.items.isEmpty() -> TvLoadingScreen()
            state.error != null -> TvErrorScreen(message = state.error!!, onRetry = viewModel::retry)
            else -> TvCatalogGrid(
                items = state.items,
                isLoading = state.isLoadingMore,
                hasMore = state.hasMore,
                onItemClick = { contentId ->
                    val index = state.items.indexOfFirst { it.contentId == contentId }
                    if (index >= 0) {
                        restoration.onItemClicked(itemId = contentId, index = index)
                        onItemClick(state.items[index])
                    }
                },
                onLoadMore = viewModel::loadMore,
                fixedColumnCount = tvPresetGridColumns(6),
                contentPadding = PaddingValues(
                    start = Spacing.safeArea,
                    top = Spacing.xxl,
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
                header = { PageTitle(args.title) },
                emptyState = { TvCatalogEmptyState(message = "This row has no titles right now.") },
            )
        }
    }
}

private fun sectionBrowseKey(args: SectionBrowseArgs) =
    "section-${args.libraryId ?: "home"}-${args.sectionId}-${args.mediaScope.orEmpty()}"

@Composable
private fun PageTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.displaySmall,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Title over a centered message, for a page with nothing to focus. */
@Composable
private fun PageMessage(title: String, message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = Spacing.safeArea, top = Spacing.xxl, end = Spacing.safeArea),
    ) {
        PageTitle(title)
        TvCatalogEmptyState(message = message)
    }
}
