package com.continuum.app.tv.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.continuum.app.model.catalog.BrowseItem
import com.continuum.app.overlays.OverlayDataExtractor
import com.continuum.app.tv.ui.theme.Spacing
import com.continuum.app.tv.ui.theme.TvSmoothBringIntoViewSpec
import com.continuum.app.tv.ui.util.tvArtworkAspectRatioForMediaType

/**
 * Poster grid with automatic pagination. Fed by a [List] of
 * [BrowseItem] from `CatalogRepository.browse()` (or equivalent). When the
 * user scrolls within 6 items of the end of the list, [onLoadMore] is called —
 * the caller is responsible for appending results and toggling the load state.
 *
 * Rendered with [TvMediaCard] so each cell gets native TV focus behavior
 * without the card floating inside a wider adaptive grid cell.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
fun TvCatalogGrid(
    items: List<BrowseItem>,
    isLoading: Boolean,
    hasMore: Boolean,
    onItemClick: (contentId: String) -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
    // Adaptive keeps the grid responsive across TV resolutions, but the page
    // gutter stays fixed so column counts do not change when the rail expands.
    minCellWidth: Dp = 180.dp,
    fixedColumnCount: Int? = null,
    loadMoreThreshold: Int = 6,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = Spacing.safeArea,
        vertical = Spacing.lg,
    ),
    horizontalSpacing: Dp = 20.dp,
    verticalSpacing: Dp = 32.dp,
    firstItemFocusRequester: FocusRequester? = null,
    firstItemCardModifier: Modifier = Modifier,
    artworkAspectRatioForItem: (BrowseItem) -> Float? = { item ->
        tvArtworkAspectRatioForMediaType(item.type)
    },
    onBrowseItemClick: ((BrowseItem) -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
    emptyState: (@Composable () -> Unit)? = null,
) {
    val gridState: LazyGridState = rememberLazyGridState()

    // Trigger pagination when the user is within 6 items of the end.
    val shouldLoadMore by remember(items.size, hasMore, isLoading) {
        derivedStateOf {
            if (!hasMore || isLoading || items.isEmpty()) return@derivedStateOf false
            val lastVisible = gridState.layoutInfo.visibleItemsInfo
                .lastOrNull()?.index ?: return@derivedStateOf false
            lastVisible >= items.size - loadMoreThreshold
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMore()
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides TvSmoothBringIntoViewSpec) {
    LazyVerticalGrid(
        state = gridState,
        columns = fixedColumnCount?.let { GridCells.Fixed(it) }
            ?: GridCells.Adaptive(minSize = minCellWidth),
        horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
        contentPadding = contentPadding,
        // focusRestorer remembers the last-focused card in the grid. Returning
        // to the grid via the menu/header restores focus to that card instead
        // of slamming the user back to position 0. Falls back to the
        // explicit first-item requester (or Compose's default first-focusable
        // search) the very first time, before anything has been remembered.
        modifier = modifier.focusRestorer(
            firstItemFocusRequester ?: FocusRequester.Default,
        ),
    ) {
        if (header != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                header()
            }
        }

        if (items.isEmpty() && !isLoading && emptyState != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    emptyState()
                }
            }
        } else {
            itemsIndexed(
                items = items,
                key = { _, item -> item.contentId },
                contentType = { _, item -> item.type },
            ) { index, item ->
                val (actions, userState) = rememberTvBrowseItemCardActions(item)
                TvMediaCard(
                    title = item.title,
                    posterUrl = item.posterUrl,
                    posterThumbhash = item.posterThumbhash,
                    year = item.year.takeIf { it > 0 },
                    userState = userState,
                    mediaType = item.type,
                    onClick = { onBrowseItemClick?.invoke(item) ?: onItemClick(item.contentId) },
                    fillWidth = true,
                    artworkAspectRatio = artworkAspectRatioForItem(item),
                    focusRequester = firstItemFocusRequester.takeIf { index == 0 },
                    cardModifier = if (index == 0) firstItemCardModifier else Modifier,
                    modifier = Modifier.fillMaxWidth(),
                    overlay = OverlayDataExtractor.fromBrowseItem(item),
                    actions = actions,
                )
            }
        }

        if (isLoading) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
        }
    }
    }
}

/** Simple centered text for an empty grid state. */
@Composable
fun TvCatalogEmptyState(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
