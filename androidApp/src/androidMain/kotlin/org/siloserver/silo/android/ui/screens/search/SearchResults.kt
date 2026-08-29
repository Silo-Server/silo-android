package org.siloserver.silo.android.ui.screens.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.ui.components.MediaCard
import org.siloserver.silo.android.ui.components.MediaGridDefaults
import org.siloserver.silo.android.ui.components.rememberBrowseItemCardActions
import org.siloserver.silo.common.ui.components.DeferImagePresentationWhileScrolling
import org.siloserver.silo.model.catalog.BrowseItem

/**
 * Displays search results in a vertical grid of media cards.
 *
 * Supports infinite scroll to load additional results.
 *
 * @param results The search result items to display.
 * @param total Total number of matching results.
 * @param isSearching Whether a search request is in flight.
 * @param hasMore Whether more results are available.
 * @param onItemClick Callback with content ID when a result card is tapped.
 * @param onLoadMore Callback to load the next page of results.
 * @param modifier Compose modifier.
 */
@Composable
fun SearchResults(
    results: List<BrowseItem>,
    total: Int,
    isSearching: Boolean,
    hasMore: Boolean,
    onItemClick: (String) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
    footer: (@Composable () -> Unit)? = null,
) {
    val gridState = rememberLazyGridState()
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    // Scrolling the results is a clear signal the user is done typing: get the
    // keyboard out of the way so more of the grid is visible.
    LaunchedEffect(gridState.isScrollInProgress) {
        if (gridState.isScrollInProgress) {
            keyboardController?.hide()
            focusManager.clearFocus()
        }
    }

    // Trigger load more when scrolled near bottom. Keyed on the flags: a
    // keyless remember would freeze their first-composition values inside the
    // derived lambda (they are plain params, not snapshot state).
    val shouldLoadMore by remember(hasMore, isSearching) {
        derivedStateOf {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = gridState.layoutInfo.totalItemsCount
            hasMore && !isSearching && lastVisible >= totalItems - 6
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMore()
    }

    val gridCellMinWidth = MediaGridDefaults.scaledPosterGridMinWidth
    DeferImagePresentationWhileScrolling(gridState) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(gridCellMinWidth),
        state = gridState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(MediaGridDefaults.PosterGridHorizontalSpacing),
        verticalArrangement = Arrangement.spacedBy(MediaGridDefaults.PosterGridVerticalSpacing),
        modifier = modifier,
    ) {
        // Result count header
        item(span = { GridItemSpan(maxLineSpan) }, contentType = "search-result-count") {
            Text(
                text = "$total result${if (total == 1) "" else "s"}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                // The grid's own contentPadding supplies the 16.dp gutters, so
                // the header only needs to clear the first row of cards.
                modifier = Modifier.padding(horizontal = 2.dp, vertical = 4.dp),
            )
        }

        items(
            items = results,
            key = { it.contentId },
            contentType = { item -> item.type },
        ) { item ->
            val (actions, userState) = rememberBrowseItemCardActions(item)
            MediaCard(
                title = item.title,
                posterUrl = item.posterUrl,
                posterThumbhash = item.posterThumbhash,
                year = item.year,
                type = item.type,
                userState = userState,
                onClick = { onItemClick(item.contentId) },
                width = gridCellMinWidth,
                overlay = org.siloserver.silo.overlays.OverlayDataExtractor.fromBrowseItem(item),
                actions = actions,
            )
        }

        if (footer != null) {
            item(span = { GridItemSpan(maxLineSpan) }, contentType = "search-footer") {
                footer()
            }
        }

        // Loading indicator
        if (isSearching && results.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }, contentType = "search-loading") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
    }
}
