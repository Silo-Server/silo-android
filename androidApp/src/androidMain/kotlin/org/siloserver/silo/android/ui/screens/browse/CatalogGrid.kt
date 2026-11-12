package org.siloserver.silo.android.ui.screens.browse

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.siloserver.silo.android.ui.components.MediaCard
import org.siloserver.silo.android.ui.components.MediaCardPlaceholder
import org.siloserver.silo.android.ui.components.MediaGridDefaults
import org.siloserver.silo.android.ui.components.ReportTabHeaderScrim
import org.siloserver.silo.android.ui.components.rememberBrowseItemCardActions
import org.siloserver.silo.android.ui.navigation.LocalHeroSourceHandoff
import org.siloserver.silo.common.cards.LocalCardPresentation
import org.siloserver.silo.common.cards.LocalEpisodeSpoilerPrefs
import org.siloserver.silo.common.diagnostics.DiagnosticsKeyAnomalyLogger
import org.siloserver.silo.common.diagnostics.DiagnosticsKeyCollection
import org.siloserver.silo.common.diagnostics.DiagnosticsListSnapshot
import org.siloserver.silo.common.ui.components.DeferImagePresentationWhileScrolling
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.settings.EpisodeSpoilers
import org.siloserver.silo.overlays.OverlayDataExtractor

/**
 * A vertical grid of media cards with infinite-scroll support.
 *
 * Uses the shared iOS-style adaptive poster grid with automatic load-more
 * triggering when the user scrolls near the bottom. [header] is a spanning
 * row that scrolls with the grid (sort/filter controls); [topContentInset]
 * lets the grid start below floating chrome and scroll under it.
 *
 * When [letterJump] is given (a title-ordered list whose server has a letter
 * index), the grid shows the whole list from the start, one slot per title,
 * reads titles by position as they come into view, and puts an A–Z rail on
 * the trailing edge that jumps to a letter's first title. Titles not read yet
 * show as card-shaped placeholders of the same height, so rows never shift
 * when they fill in.
 */
@Composable
fun CatalogGrid(
    items: List<BrowseItem>,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    onItemClick: (String) -> Unit,
    /** Per-card caption overriding the year (e.g. date for date sorts). */
    cardSubtitle: ((BrowseItem) -> String?)? = null,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
    letterJump: CatalogLetterJumpState? = null,
    /**
     * Names the list shown, such as its filter state. A change starts the
     * grid at its top, even while the old items stay up as the new list loads.
     */
    listKey: Any? = null,
    /** The finger is on a letter in the rail; read where a jump would land. */
    onPrepareLetterJump: (String) -> Unit = {},
    /** List position of a letter's first title, once its titles are read or briefly awaited. */
    letterJumpPosition: suspend (String) -> Int? = { null },
    viewDensity: CatalogViewDensity = CatalogViewDensity.Normal,
    bottomContentInset: Dp = 0.dp,
    topContentInset: Dp = 0.dp,
    header: (@Composable () -> Unit)? = null,
) {
    val gridState = rememberLazyGridState()
    // Feeds the shared header's glass when this grid fills For You.
    ReportTabHeaderScrim(gridState)
    val uniqueItems = remember(items) { items.distinctBy { it.contentId } }
    // The session density picks the base cell; the server-driven poster-size
    // preference multiplies it, shifting the adaptive column count.
    val cardWidth = viewDensity.minCardWidth * LocalCardPresentation.current.posterSize.posterScale
    val diagnosticsKeySnapshot = remember(items) {
        DiagnosticsListSnapshot.fromKeys(items.map { it.contentId })
    }
    LaunchedEffect(diagnosticsKeySnapshot) {
        DiagnosticsKeyAnomalyLogger.snapshot(
            DiagnosticsKeyCollection.PHONE_CATALOG_GRID,
            diagnosticsKeySnapshot,
        )
    }

    // Trigger load more when scrolled near bottom. Keyed on the flags: a
    // keyless remember would freeze their first-composition values inside the
    // derived lambda (they are plain params, not snapshot state).
    val shouldLoadMore by remember(hasMore, isLoadingMore) {
        derivedStateOf {
            val lastVisibleItem = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = gridState.layoutInfo.totalItemsCount
            hasMore && !isLoadingMore && lastVisibleItem >= totalItems - 6
        }
    }

    // Keyed on the pager too: when the letter index goes away, cursor paging
    // must resume even if the grid was already near its end.
    LaunchedEffect(shouldLoadMore, letterJump == null) {
        if (shouldLoadMore && letterJump == null) {
            onLoadMore()
        }
    }

    val headerCount = if (header != null) 1 else 0
    val pager = letterJump?.pager
    val pages by remember(pager) {
        pager?.pages ?: MutableStateFlow(emptyMap<Int, List<BrowseItem>>())
    }.collectAsState()
    // Tell the pager which positions are on screen; it reads those and the
    // pages beside them.
    if (pager != null) {
        LaunchedEffect(pager, headerCount) {
            snapshotFlow {
                val visible = gridState.layoutInfo.visibleItemsInfo
                val first = visible.firstOrNull { it.index >= headerCount }?.index
                val last = visible.lastOrNull()?.index
                if (first == null || last == null) IntRange.EMPTY else (first - headerCount)..(last - headerCount)
            }
                .distinctUntilChanged()
                .collect { pager.show(it) }
        }
    }
    // The letter last jumped to stays softly marked until the grid is scrolled
    // by hand or the list changes.
    var jumpedLetter by remember(letterJump?.query) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val jumpJob = remember { arrayOfNulls<Job>(1) }
    // Keyed on the list too, so a drag clears the current list's marker. A
    // drag also cancels a jump still waiting for its titles, so the jump
    // never overrides the user's own scroll.
    LaunchedEffect(gridState, letterJump?.query) {
        gridState.interactionSource.interactions.collect {
            if (it is DragInteraction.Start) {
                jumpedLetter = null
                jumpJob[0]?.cancel()
            }
        }
    }
    // Another list in the same grid starts at its top; the old scroll offset
    // means nothing in it. Keyed on the list, not on the letter index, so the
    // index arriving later for the list already shown leaves the scroll alone.
    val shownList = remember { arrayOfNulls<Any>(1) }
    LaunchedEffect(listKey) {
        val previous = shownList[0]
        shownList[0] = listKey
        if (previous != null && previous != listKey) gridState.scrollToItem(0)
    }

    Box(modifier = modifier) {
        DeferImagePresentationWhileScrolling(gridState) {
        LazyVerticalGrid(
            // iOS phone: adaptive poster grid, 110pt minimum card width, 12pt
            // column spacing, 16pt row spacing, 16pt horizontal page padding.
            columns = GridCells.Adaptive(cardWidth),
            state = gridState,
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 8.dp + topContentInset,
                // The rail takes the trailing edge.
                end = if (letterJump != null) LetterRailReservedWidth + 4.dp else 16.dp,
                bottom = 8.dp + bottomContentInset,
            ),
            horizontalArrangement = Arrangement.spacedBy(MediaGridDefaults.PosterGridHorizontalSpacing),
            verticalArrangement = Arrangement.spacedBy(MediaGridDefaults.PosterGridVerticalSpacing),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (header != null) {
                item(key = "grid-header", span = { GridItemSpan(maxLineSpan) }) {
                    header()
                }
            }

            if (pager != null) {
                items(
                    count = pager.total,
                    // A slot keeps its key while its title loads, so nothing moves.
                    key = { position -> position },
                    contentType = { CatalogPositionContentType },
                ) { position ->
                    val item = pager.item(position, pages)
                    if (item != null) {
                        CatalogGridCard(item, cardWidth, cardSubtitle, onItemClick, reserveTextLines = true)
                    } else {
                        MediaCardPlaceholder(width = cardWidth)
                    }
                }
            } else {
                items(
                    items = uniqueItems,
                    key = { it.contentId },
                    contentType = { item -> item.type },
                ) { item ->
                    CatalogGridCard(item, cardWidth, cardSubtitle, onItemClick)
                }
            }

            // Loading indicator at bottom
            if (isLoadingMore && pager == null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }
        }
        }

        if (letterJump != null) {
            CatalogLetterRail(
                available = letterJump.availableLetters,
                jumped = jumpedLetter,
                onPreview = onPrepareLetterJump,
                onJump = { letter ->
                    jumpedLetter = letter
                    jumpJob[0]?.cancel()
                    jumpJob[0] = scope.launch {
                        val position = letterJumpPosition(letter) ?: return@launch
                        gridState.scrollToItem(position + headerCount)
                    }
                },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .padding(top = topContentInset + 8.dp, bottom = bottomContentInset + 8.dp),
            )
        }
    }
}

private const val CatalogPositionContentType = "catalog-position"

@Composable
private fun CatalogGridCard(
    item: BrowseItem,
    cardWidth: Dp,
    cardSubtitle: ((BrowseItem) -> String?)?,
    onItemClick: (String) -> Unit,
    reserveTextLines: Boolean = false,
) {
    val heroHandoff = LocalHeroSourceHandoff.current
    val (actions, userState, positionSeconds) = rememberBrowseItemCardActions(item)
    val spoilerPrefs = LocalEpisodeSpoilerPrefs.current
    val hideArtwork = EpisodeSpoilers.hidesBrowseArtwork(item, spoilerPrefs, userState.played, positionSeconds = positionSeconds)
    val hideDetailArtwork = EpisodeSpoilers.hidesBrowseArtwork(item, spoilerPrefs, userState.played, selectBackdrop = true, positionSeconds = positionSeconds)
    MediaCard(
        hideArtwork = hideArtwork,
        hideDetailArtwork = hideDetailArtwork,
        title = item.title,
        posterUrl = item.posterUrl,
        posterThumbhash = item.posterThumbhash,
        year = item.year,
        subtitle = cardSubtitle?.invoke(item),
        type = item.type,
        userState = userState,
        onClick = {
            heroHandoff?.pendingArtworkUrl = (item.backdropUrl ?: item.posterUrl).takeUnless { hideDetailArtwork }
            heroHandoff?.pendingArtworkThumbhash = if (hideDetailArtwork) null else item.backdropThumbhash ?: item.posterThumbhash
            onItemClick(item.contentId)
        },
        width = cardWidth,
        overlay = OverlayDataExtractor.fromBrowseItem(item),
        actions = actions,
        sharedContentId = item.contentId,
        reserveTextLines = reserveTextLines,
    )
}
