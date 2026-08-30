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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
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
import org.siloserver.silo.tv.ui.theme.cardScaled
import org.siloserver.silo.tv.ui.theme.rememberTvGridBringIntoViewSpec
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
    /**
     * Return-restoration plumbing, for surfaces that restore focus to the card
     * a detail page was opened from.
     *
     * The requester goes on [restoreItemIndex], and that card reports back
     * which identity it is bound to — a card can be laid out while its modifier
     * still carries the previous binding, so nothing else can say. Callers
     * that do not restore leave these alone and keep the first-item behaviour.
     */
    restoreItemIndex: Int = -1,
    restoreItemFocusRequester: FocusRequester? = null,
    // Two caveats this component cannot remove on its own.
    //
    // The restorer's fallback is chosen during composition while attachment is
    // only known after it, so there is a one-recomposition window either way:
    // a just-detached requester still named, or a just-attached one not yet.
    // Tolerable, because that failure is a call returning false followed by
    // normal entry rather than focus landing somewhere wrong.
    //
    // And restoreItemIndex is a POSITION. If the list reorders after the caller
    // resolved it, the requester follows the index onto a different item.
    // Callers whose data only appends are unaffected; one that re-sorts in
    // place must re-resolve rather than trust a frozen index.
    onRestoreRequesterAttached: (String?) -> Unit = {},
    /**
     * Both focus edges, not just the gain. A caller confirming a restoration
     * has to know what holds focus NOW: reporting only arrivals makes its
     * record sticky, so a card passed over on the way somewhere else still
     * looks like the current position long after focus has moved on.
     */
    onItemFocusedAtIndex: (item: BrowseItem, index: Int, focused: Boolean) -> Unit = { _, _, _ -> },
    artworkAspectRatioForItem: (BrowseItem) -> Float? = { item ->
        tvArtworkAspectRatioForMediaType(item.type)
    },
    onBrowseItemClick: ((BrowseItem) -> Unit)? = null,
    header: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    emptyState: (@Composable () -> Unit)? = null,
) {
    val resolvedGridState = gridState ?: rememberLazyGridState()
    // Keyed lazy lists throw on a repeated key, which is fatal. Paging can
    // hand the same item back across pages, so the grid guarantees uniqueness
    // itself rather than trusting every caller to.
    val uniqueItems = remember(items) { items.distinctBy { it.contentId } }
    // The caller speaks positions in the list it handed us; we render the
    // deduplicated one. Translate on both edges — the incoming restore index
    // through contentId into our list, and outgoing focus reports back into
    // the caller's list — so a duplicate earlier in the feed cannot shift
    // either side's arithmetic. distinctBy keeps first occurrences, so the
    // first raw index of a rendered item is the item itself.
    val resolvedRestoreItemIndex = remember(items, uniqueItems, restoreItemIndex) {
        items.getOrNull(restoreItemIndex)?.contentId
            ?.let { id -> uniqueItems.indexOfFirst { it.contentId == id } }
            ?: -1
    }
    val rawIndexByContentId = remember(items) {
        buildMap {
            items.forEachIndexed { rawIndex, item ->
                putIfAbsent(item.contentId, rawIndex)
            }
        }
    }

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
    val shouldLoadMore by remember(uniqueItems.size, hasMore, isLoading) {
        derivedStateOf {
            if (!hasMore || isLoading || uniqueItems.isEmpty()) return@derivedStateOf false
            if (uniqueItems.size == loadMoreRequestedSize) return@derivedStateOf false
            val lastVisible = resolvedGridState.layoutInfo.visibleItemsInfo
                .lastOrNull()?.index ?: return@derivedStateOf false
            lastVisible >= uniqueItems.size - loadMoreThreshold
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            loadMoreRequestedSize = uniqueItems.size
            onLoadMore()
        }
    }

    // A filter change / refresh / reset replaces the list, which can land on the
    // same item count we last requested at. Drop the latch on any list-identity
    // change — a new first item OR a size change (a refresh can shrink a paged
    // list back to page size while keeping the same first item) — so a fresh
    // list is never mistaken for a stalled page. A failed load-more changes
    // neither key, so the gate correctly holds until the retry footer is used.
    LaunchedEffect(uniqueItems.firstOrNull()?.contentId, uniqueItems.size) {
        loadMoreRequestedSize = -1
    }

    // A page we requested has settled (not loading) without adding items while
    // the server still claims more — treat as a stalled/failed load-more and
    // offer an explicit, focusable retry instead of silently re-firing.
    // WHICH card is holding the restore requester, not merely whether one is.
    // A Boolean cannot express ownership, and pagination moves the restore
    // index while both the old and new cards are briefly composed: if the new
    // card attaches before the old one disposes, the old disposal erases a live
    // attachment and the fallback silently reverts.
    var attachedRestoreItemId by remember { mutableStateOf<String?>(null) }
    // Tracked the same way for the first item, so the fallback below names a
    // requester some card is actually holding.
    //
    // Worth being accurate about the size of this. An UNATTACHED requester is
    // benign: it returns false and Compose carries on with normal entry, which
    // is where Default would have arrived anyway. What is not benign is a
    // requester attached to the WRONG card, and that is what the identity
    // ownership above prevents. Gating the fallback on attachment only avoids
    // a pointless failed call.
    var attachedFirstItemId by remember { mutableStateOf<String?>(null) }

    val loadMoreStalled = hasMore &&
        !isLoading &&
        uniqueItems.isNotEmpty() &&
        loadMoreRequestedSize == uniqueItems.size

    CompositionLocalProvider(
        LocalBringIntoViewSpec provides rememberTvGridBringIntoViewSpec(
            contentPadding.calculateTopPadding(),
        ),
    ) {
    LazyVerticalGrid(
        state = resolvedGridState,
        // Adaptive grids honor the card-presentation poster size by scaling
        // the min cell width (smaller min → more columns); every caller passes
        // an unscaled base. Fixed callers shift their column count instead
        // (tvPresetGridColumns) before passing it in.
        columns = fixedColumnCount?.let { GridCells.Fixed(it) }
            ?: GridCells.Adaptive(minSize = minCellWidth.cardScaled()),
        horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
        contentPadding = contentPadding,
        // focusRestorer remembers the last-focused card in the grid. Returning
        // to the grid via the menu/header restores focus to that card instead
        // of slamming the user back to position 0. Falls back to the
        // explicit first-item requester (or Compose's default first-focusable
        // search) the very first time, before anything has been remembered.
        modifier = modifier.focusRestorer(
            // The restore requester only once a card is genuinely holding it.
            // It displaces the first-item requester on its card, so naming that
            // as the fallback would point the restorer at a requester nothing
            // is attached to — and an index alone does not prove attachment,
            // since a deep target sits outside lazy composition until scrolled
            // to. Compose calls this fallback directly when restoration fails,
            // and an unattached requester returns false, dropping the whole
            // thing into an ordinary focus search.
            restoreItemFocusRequester?.takeIf { attachedRestoreItemId != null }
                ?: firstItemFocusRequester?.takeIf { attachedFirstItemId != null }
                ?: FocusRequester.Default,
        ),
    ) {
        if (header != null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                header()
            }
        }

        if (uniqueItems.isEmpty() && !isLoading && emptyState != null) {
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
                // See TvMediaRow: a repeated contentId is fatal to a keyed
                // lazy list, and paging can hand the same item back twice.
                items = uniqueItems,
                key = { _, item -> item.contentId },
                contentType = { _, item -> item.type },
            ) { index, item ->
                val (actions, userState) = rememberTvBrowseItemCardActions(item)
                val isRestoreTarget =
                    restoreItemFocusRequester != null && index == resolvedRestoreItemIndex
                if (isRestoreTarget) {
                    // Keyed on the callback as well as the item: an owner
                    // change while the same card survives has to re-announce
                    // the existing attachment, or the new owner only ever hears
                    // about it when it goes away.
                    DisposableEffect(item.contentId, onRestoreRequesterAttached) {
                        attachedRestoreItemId = item.contentId
                        onRestoreRequesterAttached(item.contentId)
                        onDispose {
                            // Only if this effect still owns the attachment. A
                            // successor that attached first must not be undone
                            // by its predecessor's teardown.
                            if (attachedRestoreItemId == item.contentId) {
                                attachedRestoreItemId = null
                                onRestoreRequesterAttached(null)
                            }
                        }
                    }
                }
                if (firstItemFocusRequester != null && index == 0 && !isRestoreTarget) {
                    DisposableEffect(item.contentId) {
                        attachedFirstItemId = item.contentId
                        onDispose {
                            if (attachedFirstItemId == item.contentId) attachedFirstItemId = null
                        }
                    }
                }
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
                    focusRequester = if (isRestoreTarget) {
                        restoreItemFocusRequester
                    } else {
                        firstItemFocusRequester.takeIf { index == 0 }
                    },
                    cardModifier = if (index == 0) firstItemCardModifier else Modifier,
                    modifier = Modifier
                        .fillMaxWidth()
                        // hasFocus, not isFocused: this modifier lands on the
                        // card's outer Column while the Material Card inside it
                        // owns focus, so isFocused is never true here and the
                        // helper would never see a card take focus at all.
                        .onFocusChanged {
                            onItemFocusedAtIndex(
                                item,
                                rawIndexByContentId[item.contentId] ?: index,
                                it.hasFocus,
                            )
                        },
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
