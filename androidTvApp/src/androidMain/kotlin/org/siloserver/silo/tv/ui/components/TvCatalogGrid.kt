package org.siloserver.silo.tv.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.overlays.OverlayDataExtractor
import org.siloserver.silo.tv.ui.theme.Spacing
import org.siloserver.silo.tv.ui.theme.TvSmoothBringIntoViewSpec
import org.siloserver.silo.tv.ui.util.tvArtworkAspectRatioForMediaType

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
    gridState: LazyGridState? = null,
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
    footer: (@Composable () -> Unit)? = null,
    emptyState: (@Composable () -> Unit)? = null,
) {
    val resolvedGridState = gridState ?: rememberLazyGridState()

    // Backoff gate against an endless load-more retry storm. When a load-more
    // completes without growing the list while the server still reports more
    // pages, the request effectively failed (the consuming ViewModel clears its
    // loading flag but keeps `hasMore` true, and surfaces the error only when the
    // list is empty). Without a gate the derived trigger re-arms one doomed
    // request per round-trip forever. We latch the item count we last asked for
    // so the trigger will not re-fire until the list actually grows — the user
    // re-attempts through the focusable retry footer below instead.
    var loadMoreRequestedSize by remember { mutableStateOf(-1) }

    // Trigger pagination when the user is within 6 items of the end. The
    // `loadMoreRequestedSize` guard is read inside the derived state so a failed
    // page (size unchanged) stays gated until a retry or a successful growth.
    val shouldLoadMore by remember(items.size, hasMore, isLoading) {
        derivedStateOf {
            if (!hasMore || isLoading || items.isEmpty()) return@derivedStateOf false
            if (items.size == loadMoreRequestedSize) return@derivedStateOf false
            val lastVisible = resolvedGridState.layoutInfo.visibleItemsInfo
                .lastOrNull()?.index ?: return@derivedStateOf false
            lastVisible >= items.size - loadMoreThreshold
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            loadMoreRequestedSize = items.size
            onLoadMore()
        }
    }

    // A filter change / refresh / reset replaces the list, which can land on the
    // same item count we last requested at. Drop the latch on any list-identity
    // change — a new first item OR a size change (a refresh can shrink a paged
    // list back to page size while keeping the same first item) — so a fresh
    // list is never mistaken for a stalled page. A failed load-more changes
    // neither key, so the gate correctly holds until the retry footer is used.
    LaunchedEffect(items.firstOrNull()?.contentId, items.size) {
        loadMoreRequestedSize = -1
    }

    // A page we requested has settled (not loading) without adding items while
    // the server still claims more — treat as a stalled/failed load-more and
    // offer an explicit, focusable retry instead of silently re-firing.
    val loadMoreStalled = hasMore &&
        !isLoading &&
        items.isNotEmpty() &&
        loadMoreRequestedSize == items.size

    CompositionLocalProvider(LocalBringIntoViewSpec provides TvSmoothBringIntoViewSpec) {
    LazyVerticalGrid(
        state = resolvedGridState,
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

        if (footer != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                footer()
            }
        }

        if (loadMoreStalled) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                // Retrying clears the gate implicitly: onLoadMore re-arms the
                // ViewModel, and a successful page grows the list so the derived
                // trigger resumes automatic paging.
                TvLoadMoreRetryFooter(onRetry = onLoadMore)
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

/**
 * Focusable "load more failed, tap to retry" footer. Rendered full-span at the
 * tail of the grid when a load-more page stalls (see [loadMoreStalled] in
 * [TvCatalogGrid]) so the paging error is recoverable with the D-pad instead of
 * spinning forever or dead-ending the grid.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvLoadMoreRetryFooter(onRetry: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Couldn't load more",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onRetry,
                contentPadding = PaddingValues(horizontal = 32.dp, vertical = 12.dp),
            ) {
                Text("Retry", style = MaterialTheme.typography.labelLarge)
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
