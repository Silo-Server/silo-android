package com.continuum.app.tv.ui.screens.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.continuum.app.common.ui.components.ThumbhashImage
import com.continuum.app.model.section.LibraryCollection
import com.continuum.app.tv.ui.components.TvAlphabetRail
import com.continuum.app.tv.ui.components.TvCardWidth
import com.continuum.app.tv.ui.components.TvCatalogEmptyState
import com.continuum.app.tv.ui.components.TvErrorScreen
import com.continuum.app.tv.ui.components.TvMediaCard
import com.continuum.app.tv.ui.components.TvSkylineSectionFeed
import com.continuum.app.tv.ui.shell.TvTopMenuLayout
import com.continuum.app.tv.ui.theme.Spacing
import com.continuum.app.tv.ui.theme.SubtleSurface
import com.continuum.app.tv.ui.theme.TvSmoothBringIntoViewSpec
import com.continuum.app.tv.ui.theme.monoGroupHeader
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Android TV library detail surface, with tvOS as the master.
 *
 * The top-bar cascade owns library switching and section selection. This body
 * only renders the committed sub-destination: Recommended uses the same
 * Skyline feed as Home, Collections renders grouped collection cards, and
 * Browse renders the catalog grid plus the right-edge A-Z rail. There is no
 * in-page library title, switcher pill, or tab slider.
 */
@Composable
fun TvLibraryDetailScreen(
    libraryId: Int,
    libraryTitle: String,
    libraryType: String,
    onItemClick: (contentId: String) -> Unit,
    onCollectionClick: (collectionId: String, title: String) -> Unit,
    onInitialContentFocus: () -> Unit = {},
    // When the screen is opened from the Skyline cascade with a committed
    // section pill, this drives the initial tab (Recommended / Library /
    // Collections). Null leaves the ViewModel's default (Recommended) and any
    // user-driven tab changes alone.
    initialSection: TvLibraryTab? = null,
    // Monotonic nonce bumped by the host on every cascade commit. Keying the
    // section-apply effect on it (not just initialSection) makes re-committing
    // the SAME pill re-apply the section instead of being a silent no-op.
    sectionRequestNonce: Int = 0,
    onContentUpFallbackChanged: (((() -> Boolean)?) -> Unit)? = null,
    viewModel: TvLibraryDetailViewModel = koinViewModel(
        key = "library-$libraryId",
        parameters = { parametersOf(libraryId, libraryTitle, libraryType) },
    ),
) {
    val state by viewModel.uiState.collectAsState()

    // Apply the committed cascade section on entry / whenever the commit
    // changes it. Keyed on sectionRequestNonce (bumped on every commit) AND the
    // section value, so re-committing the SAME pill re-applies the section
    // rather than being a silent no-op, while a non-commit recomposition leaves
    // manual in-screen tab moves untouched.
    LaunchedEffect(sectionRequestNonce, initialSection) {
        initialSection?.let(viewModel::onTabSelected)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when (state.selectedTab) {
            TvLibraryTab.Recommended -> RecommendedTab(
                state = state,
                onItemClick = onItemClick,
                onRetry = viewModel::retryRecommended,
                onInitialContentFocus = onInitialContentFocus,
                focusRequest = sectionRequestNonce,
                onContentUpFallbackChanged = onContentUpFallbackChanged,
            )
            TvLibraryTab.Browse -> LibraryTab(
                state = state,
                onItemClick = onItemClick,
                onNamePrefixChanged = viewModel::onNamePrefixChanged,
                onLoadMore = viewModel::loadMoreBrowse,
                onRetry = viewModel::retryBrowse,
                onInitialContentFocus = onInitialContentFocus,
            )
            TvLibraryTab.Collections -> CollectionsTab(
                state = state,
                onCollectionClick = onCollectionClick,
                onRetry = viewModel::retryCollections,
                onInitialContentFocus = onInitialContentFocus,
            )
        }
    }
}

// ============================================================================
// Tab content
// ============================================================================

@Composable
private fun RecommendedTab(
    state: TvLibraryDetailViewModel.UiState,
    onItemClick: (String) -> Unit,
    onRetry: () -> Unit,
    onInitialContentFocus: () -> Unit,
    focusRequest: Int,
    onContentUpFallbackChanged: (((() -> Boolean)?) -> Unit)?,
) {
    val rows = remember(state.sections) {
        state.sections.filter { !it.featured && it.items.isNotEmpty() }
    }

    when {
        state.recommendedLoading && state.sections.isEmpty() -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                InlineLoadingState()
            }
        }
        state.recommendedError != null && state.sections.isEmpty() -> {
            TvErrorScreen(
                message = state.recommendedError,
                onRetry = onRetry,
                modifier = Modifier.padding(
                    start = Spacing.safeArea,
                    top = TvTopMenuLayout.contentTopInset,
                    end = Spacing.safeArea,
                ),
            )
        }
        rows.isEmpty() -> {
            TvCatalogEmptyState(
                message = "${state.title} is empty.",
                modifier = Modifier.fillMaxSize(),
            )
        }
        else -> {
            TvSkylineSectionFeed(
                sections = rows,
                onItemClick = onItemClick,
                focusRequest = focusRequest,
                onInitialContentFocus = onInitialContentFocus,
                onContentUpFallbackChanged = onContentUpFallbackChanged,
            )
        }
    }
}

@Composable
private fun LibraryTab(
    state: TvLibraryDetailViewModel.UiState,
    onItemClick: (String) -> Unit,
    onNamePrefixChanged: (String?) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onInitialContentFocus: () -> Unit,
) {
    val firstGridItemFocusRequester = remember { FocusRequester() }
    var initialFocusRequested by remember { mutableStateOf(false) }

    LaunchedEffect(state.browseItems.isNotEmpty()) {
        if (initialFocusRequested || state.browseItems.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.delay(120)
        runCatching { firstGridItemFocusRequester.requestFocus() }
        onInitialContentFocus()
        initialFocusRequested = true
    }

    if (state.browseError != null && state.browseItems.isEmpty()) {
        TvErrorScreen(
            message = state.browseError,
            onRetry = onRetry,
            modifier = Modifier.padding(
                start = Spacing.safeArea,
                top = TvTopMenuLayout.contentTopInset,
                end = Spacing.safeArea,
            ),
        )
        return
    }

    // Grid + right-edge alphabet rail (tvOS `TVLibraryGridView`): the rail
    // sits to the right of the grid and jumps the browse name-prefix filter.
    Row(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            LibraryGrid(
                state = state,
                onItemClick = onItemClick,
                onLoadMore = onLoadMore,
                firstItemFocusRequester = firstGridItemFocusRequester,
            )
        }
        TvAlphabetRail(
            selected = state.browseFilter.namePrefix,
            onSelect = onNamePrefixChanged,
            modifier = Modifier.padding(end = Spacing.md),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryGrid(
    state: TvLibraryDetailViewModel.UiState,
    onItemClick: (String) -> Unit,
    onLoadMore: () -> Unit,
    firstItemFocusRequester: FocusRequester,
) {
    val gridState: LazyGridState = rememberLazyGridState()

    val nearEnd by remember(
        gridState,
        state.browseHasMore,
        state.browseItems.size,
        state.browseLoading,
        state.browseLoadingMore,
    ) {
        derivedStateOf {
            if (!state.browseHasMore || state.browseLoading || state.browseLoadingMore) {
                false
            } else {
                val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()
                state.browseItems.isNotEmpty() &&
                    lastVisible != null &&
                    lastVisible.index >= state.browseItems.size -
                        (LibraryGridLoadMoreRowsThreshold * LibraryBrowseGridColumns)
            }
        }
    }

    LaunchedEffect(nearEnd) {
        if (nearEnd) onLoadMore()
    }

    // Jump to the top of the result set whenever the A–Z prefix changes, so an
    // alphabet-rail letter-jump actually lands at the start of that prefix's
    // results instead of keeping a deep scroll position from the old set.
    LaunchedEffect(state.browseFilter.namePrefix) {
        gridState.scrollToItem(0)
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides TvSmoothBringIntoViewSpec) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(LibraryBrowseGridColumns),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(LibraryGridColumnSpacing),
            verticalArrangement = Arrangement.spacedBy(LibraryGridRowSpacing),
            contentPadding = PaddingValues(
                start = Spacing.safeArea,
                top = TvTopMenuLayout.contentTopInset,
                end = Spacing.md,
                bottom = Spacing.xxxl,
            ),
        ) {
            if (state.browseLoading && state.browseItems.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "loading") {
                    InlineLoadingState()
                }
            } else if (state.browseItems.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                    TvCatalogEmptyState(message = "No titles match the current filters.")
                }
            } else {
                itemsIndexed(
                    state.browseItems,
                    key = { _, item -> item.contentId },
                    contentType = { _, item -> item.type },
                ) { index, item ->
                    val (actions, userState) = com.continuum.app.tv.ui.components.rememberTvBrowseItemCardActions(item)
                    TvMediaCard(
                        title = item.title,
                        posterUrl = item.posterUrl,
                        posterThumbhash = item.posterThumbhash,
                        year = item.year.takeIf { it > 0 },
                        userState = userState,
                        mediaType = item.type,
                        width = TvCardWidth,
                        fillWidth = true,
                        onClick = { onItemClick(item.contentId) },
                        focusRequester = firstItemFocusRequester.takeIf { index == 0 },
                        modifier = Modifier.fillMaxWidth(),
                        overlay = com.continuum.app.overlays.OverlayDataExtractor.fromBrowseItem(item),
                        actions = actions,
                    )
                }
            }

            if (state.browseLoadingMore) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "loading-more") {
                    InlineLoadingState(verticalPadding = 24.dp)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CollectionsTab(
    state: TvLibraryDetailViewModel.UiState,
    onCollectionClick: (String, String) -> Unit,
    onRetry: () -> Unit,
    onInitialContentFocus: () -> Unit,
) {
    val firstCollectionFocusRequester = remember { FocusRequester() }
    var initialFocusRequested by remember { mutableStateOf(false) }

    // First collection of the first non-empty group claims initial focus.
    val firstCollectionId = state.collectionSections
        .firstOrNull { it.collections.isNotEmpty() }
        ?.collections?.firstOrNull()?.id

    LaunchedEffect(firstCollectionId) {
        if (initialFocusRequested || firstCollectionId == null) return@LaunchedEffect
        kotlinx.coroutines.delay(120)
        runCatching { firstCollectionFocusRequester.requestFocus() }
        onInitialContentFocus()
        initialFocusRequested = true
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides TvSmoothBringIntoViewSpec) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(LibraryGridColumns),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(LibraryGridColumnSpacing),
            verticalArrangement = Arrangement.spacedBy(LibraryGridRowSpacing),
            contentPadding = PaddingValues(
                start = Spacing.safeArea,
                top = TvTopMenuLayout.contentTopInset,
                end = Spacing.safeArea,
                bottom = Spacing.xxxl,
            ),
        ) {
            when {
                state.collectionsLoading && state.collections.isEmpty() -> {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "loading") {
                        InlineLoadingState()
                    }
                }
                state.collectionsError != null && state.collections.isEmpty() -> {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "error") {
                        TvErrorScreen(message = state.collectionsError, onRetry = onRetry)
                    }
                }
                state.collections.isEmpty() -> {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                        TvCatalogEmptyState(message = "No collections in this library.")
                    }
                }
                // Grouped collections (tvOS `TVLibraryCollectionsView`): a mono
                // uppercase group header, then a grid of 2:3 poster cards. A
                // section with an empty name (flat / ungrouped bucket) renders no
                // header.
                else -> state.collectionSections.forEachIndexed { sectionIndex, section ->
                    if (section.collections.isEmpty()) return@forEachIndexed
                    if (section.name.isNotEmpty()) {
                        item(
                            span = { GridItemSpan(maxLineSpan) },
                            key = "group-header:$sectionIndex:${section.name}",
                        ) {
                            CollectionsGroupHeader(name = section.name)
                        }
                    }
                    itemsIndexed(
                        section.collections,
                        key = { _, collection -> "$sectionIndex:${collection.id}" },
                        contentType = { _, collection -> "collection" },
                    ) { _, collection ->
                        TvCollectionCard(
                            collection = collection,
                            onClick = { onCollectionClick(collection.id, collection.name) },
                            focusRequester = firstCollectionFocusRequester
                                .takeIf { collection.id == firstCollectionId },
                        )
                    }
                }
            }
        }
    }
}

/** Mono uppercase group header for the grouped collections grid (tvOS §6.3). */
@Composable
private fun CollectionsGroupHeader(name: String) {
    Text(
        text = name.uppercase(),
        style = monoGroupHeader,
        color = Color.White.copy(alpha = 0.38f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

// ============================================================================
// Collection card (renders inside the Collections grid)
// ============================================================================

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvCollectionCard(
    collection: LibraryCollection,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Card(
            onClick = onClick,
            shape = CardDefaults.shape(shape = RoundedCornerShape(8.dp)),
            modifier = Modifier
                .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
                .fillMaxWidth()
                .aspectRatio(2f / 3f),
        ) {
            if (!collection.posterUrl.isNullOrBlank()) {
                ThumbhashImage(
                    url = collection.posterUrl,
                    thumbhash = collection.posterThumbhash,
                    contentDescription = collection.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(SubtleSurface),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.VideoLibrary,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.8f),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Centered caption with a caps count noun ("12 MOVIES"), matching
        // tvOS `TVCollectionPosterCard`.
        Text(
            text = collection.name,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White.copy(alpha = 0.92f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        collectionCountText(collection)?.let { countText ->
            Text(
                text = countText,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f),
                maxLines = 1,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** `12 MOVIES`-style caps count, deriving the noun from the collection type. */
private fun collectionCountText(collection: LibraryCollection): String? {
    val count = collection.itemCount ?: return null
    if (count <= 0) return null
    val plural = count != 1
    val noun = when (collection.collectionType?.lowercase()) {
        "movie", "movies" -> if (plural) "movies" else "movie"
        "series", "show", "shows", "tvshows" -> if (plural) "shows" else "show"
        "album", "albums" -> if (plural) "albums" else "album"
        "audiobook", "audiobooks", "book", "books" -> if (plural) "books" else "book"
        else -> if (plural) "items" else "item"
    }
    return "$count $noun".uppercase()
}

// ============================================================================
// Helpers
// ============================================================================

@Composable
private fun InlineLoadingState(verticalPadding: androidx.compose.ui.unit.Dp = 48.dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = verticalPadding),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = Color.White)
    }
}

// Catalog grid metrics, 1:1 with tvOS `TVCatalogGrid`: 6 columns, 40dp column
// spacing, 60dp row spacing. The Browse grid drops to 5 columns to clear the
// right-edge alphabet rail (tvOS shrinks the same way).
private const val LibraryGridColumns = 6
private const val LibraryBrowseGridColumns = 5
private val LibraryGridColumnSpacing = 20.dp
private val LibraryGridRowSpacing = 30.dp
private const val LibraryGridLoadMoreRowsThreshold = 8
