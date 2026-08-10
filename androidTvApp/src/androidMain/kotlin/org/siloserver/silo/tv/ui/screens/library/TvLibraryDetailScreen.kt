package org.siloserver.silo.tv.ui.screens.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import org.siloserver.silo.tv.ui.focus.rememberTvFlatReturnRestoration
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.tv.ui.focus.TvObservedFocusResult
import org.siloserver.silo.tv.ui.focus.TvContentInitialFocusMaxAttempts
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.catalog.AudiobookGroup
import org.siloserver.silo.model.section.LibraryCollection
import org.siloserver.silo.tv.ui.components.TvAlphabetRail
import org.siloserver.silo.tv.ui.components.TvCardWidth
import org.siloserver.silo.tv.ui.components.TvCatalogEmptyState
import org.siloserver.silo.tv.ui.components.TvErrorScreen
import org.siloserver.silo.tv.ui.components.TvFilterChip
import org.siloserver.silo.tv.ui.components.TvMediaCard
import org.siloserver.silo.tv.ui.components.TvSkylineSectionFeed
import org.siloserver.silo.tv.ui.shell.TvTopMenuLayout
import org.siloserver.silo.tv.ui.theme.Spacing
import org.siloserver.silo.tv.ui.theme.SubtleSurface
import org.siloserver.silo.tv.ui.theme.TvSmoothBringIntoViewSpec
import org.siloserver.silo.tv.ui.theme.monoGroupHeader
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
    onCollectionClick: (collectionId: String, title: String, isUserCollection: Boolean) -> Unit,
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
    onContentUpFallbackChanged: ((((Boolean) -> Boolean)?) -> Unit)? = null,
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
                surfaceKey = "library-$libraryId",
                state = state,
                onItemClick = onItemClick,
                onRetry = viewModel::retryRecommended,
                onInitialContentFocus = onInitialContentFocus,
                focusRequest = sectionRequestNonce,
                onContentUpFallbackChanged = onContentUpFallbackChanged,
            )
            // Browse is the tvOS `TVLibraryGridView` embed: Sort/Filter
            // control row over the grid, with the A–Z rail always on.
            TvLibraryTab.Browse -> LibraryTab(
                state = state,
                onItemClick = onItemClick,
                onNamePrefixChanged = viewModel::onNamePrefixChanged,
                onGenreChanged = viewModel::onGenreChanged,
                onLoadMore = viewModel::loadMoreBrowse,
                onRetry = viewModel::retryBrowse,
                onInitialContentFocus = onInitialContentFocus,
                showAlphabetRail = true,
                showBrowseControls = true,
                onSortKeySelected = viewModel::onSortKeySelected,
                onFacetSelectionApplied = viewModel::onFacetSelectionApplied,
                onContentUpFallbackChanged = onContentUpFallbackChanged,
            )
            TvLibraryTab.Genres -> LibraryTab(
                state = state,
                onItemClick = onItemClick,
                onNamePrefixChanged = viewModel::onNamePrefixChanged,
                onGenreChanged = viewModel::onGenreChanged,
                onLoadMore = viewModel::loadMoreBrowse,
                onRetry = viewModel::retryBrowse,
                onInitialContentFocus = onInitialContentFocus,
                showGenreChips = true,
            )
            TvLibraryTab.Alphabet -> LibraryTab(
                state = state,
                onItemClick = onItemClick,
                onNamePrefixChanged = viewModel::onNamePrefixChanged,
                onGenreChanged = viewModel::onGenreChanged,
                onLoadMore = viewModel::loadMoreBrowse,
                onRetry = viewModel::retryBrowse,
                onInitialContentFocus = onInitialContentFocus,
                showAlphabetRail = true,
                onContentUpFallbackChanged = onContentUpFallbackChanged,
            )
            TvLibraryTab.RecentlyAdded -> LibraryTab(
                state = state,
                onItemClick = onItemClick,
                onNamePrefixChanged = viewModel::onNamePrefixChanged,
                onGenreChanged = viewModel::onGenreChanged,
                onLoadMore = viewModel::loadMoreBrowse,
                onRetry = viewModel::retryBrowse,
                onInitialContentFocus = onInitialContentFocus,
            )
            TvLibraryTab.Authors -> {
                if (state.selectedAudiobookGroup == null) {
                    AudiobookGroupsTab(
                        state = state,
                        groupLabel = "authors",
                        onGroupClick = viewModel::onAudiobookGroupSelected,
                        onLoadMore = viewModel::loadMoreAudiobookGroups,
                        onRetry = viewModel::retryAudiobookGroups,
                        onInitialContentFocus = onInitialContentFocus,
                    )
                } else {
                    LibraryTab(
                        state = state,
                        onItemClick = onItemClick,
                        onNamePrefixChanged = viewModel::onNamePrefixChanged,
                        onGenreChanged = viewModel::onGenreChanged,
                        onLoadMore = viewModel::loadMoreBrowse,
                        onRetry = viewModel::retryBrowse,
                        onInitialContentFocus = onInitialContentFocus,
                        onClearAudiobookGroup = viewModel::onAudiobookGroupCleared,
                    )
                }
            }
            TvLibraryTab.Series -> {
                if (state.selectedAudiobookGroup == null) {
                    AudiobookGroupsTab(
                        state = state,
                        groupLabel = "series",
                        onGroupClick = viewModel::onAudiobookGroupSelected,
                        onLoadMore = viewModel::loadMoreAudiobookGroups,
                        onRetry = viewModel::retryAudiobookGroups,
                        onInitialContentFocus = onInitialContentFocus,
                    )
                } else {
                    LibraryTab(
                        state = state,
                        onItemClick = onItemClick,
                        onNamePrefixChanged = viewModel::onNamePrefixChanged,
                        onGenreChanged = viewModel::onGenreChanged,
                        onLoadMore = viewModel::loadMoreBrowse,
                        onRetry = viewModel::retryBrowse,
                        onInitialContentFocus = onInitialContentFocus,
                        onClearAudiobookGroup = viewModel::onAudiobookGroupCleared,
                    )
                }
            }
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
    /** Distinguishes this feed's saveable slots from other surfaces'. */
    surfaceKey: String,
    state: TvLibraryDetailViewModel.UiState,
    onItemClick: (String) -> Unit,
    onRetry: () -> Unit,
    onInitialContentFocus: () -> Unit,
    focusRequest: Int,
    onContentUpFallbackChanged: ((((Boolean) -> Boolean)?) -> Unit)?,
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
                surfaceKey = surfaceKey,
                sections = rows,
                onItemClick = onItemClick,
                focusRequest = focusRequest,
                onInitialContentFocus = onInitialContentFocus,
                onContentUpFallbackChanged = onContentUpFallbackChanged,
            )
        }
    }
}

/** Which browse overlay panel is open over the grid (tvOS `TVBrowsePanel`). */
private enum class TvBrowsePanel { Sort, Filter }

/**
 * The LazyGrid position of the [itemIndex]th card.
 *
 * Headers occupy full-span slots ahead of the cards, so the grid's own index
 * runs ahead of the item index by however many are showing.
 */
internal fun libraryLazyGridIndex(itemIndex: Int, headerCount: Int): Int =
    itemIndex.coerceAtLeast(0) + headerCount.coerceAtLeast(0)

@Composable
private fun LibraryTab(
    state: TvLibraryDetailViewModel.UiState,
    onItemClick: (String) -> Unit,
    onNamePrefixChanged: (String?) -> Unit,
    onGenreChanged: (String?) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onInitialContentFocus: () -> Unit,
    showAlphabetRail: Boolean = false,
    showGenreChips: Boolean = false,
    showBrowseControls: Boolean = false,
    onSortKeySelected: (TvLibrarySortOption) -> Unit = {},
    onFacetSelectionApplied: (TvCatalogFacetSelection) -> Unit = {},
    /** Shell hook for overriding D-pad Up while the A–Z rail holds focus. */
    onContentUpFallbackChanged: ((((Boolean) -> Boolean)?) -> Unit)? = null,
    onClearAudiobookGroup: (() -> Unit)? = null,
) {
    val restoredGridItemFocusRequester = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    var openPanel by remember { mutableStateOf<TvBrowsePanel?>(null) }
    val gridHeaderCount = listOf(
        showBrowseControls,
        showGenreChips,
        state.selectedAudiobookGroup != null && onClearAudiobookGroup != null,
    ).count { it }

    val restoration = rememberTvFlatReturnRestoration(
        itemIds = state.browseItems.map { it.contentId },
        hasMore = state.browseHasMore,
        isLoadingMore = state.browseLoadingMore,
        errorMessage = state.browseError,
        surfaceKey = state.selectedTab.name,
        onLoadMore = onLoadMore,
        // Headers occupy full-span slots ahead of the cards, so the grid's own
        // index runs ahead of the item index by however many are showing.
        scrollToItem = { itemIndex ->
            gridState.scrollToItem(
                libraryLazyGridIndex(itemIndex = itemIndex, headerCount = gridHeaderCount),
            )
        },
        requestFocus = restoredGridItemFocusRequester::requestFocus,
        onRestored = onInitialContentFocus,
    )

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
                onItemClick = { contentId ->
                    restoration.onItemClicked(
                        itemId = contentId,
                        index = state.browseItems.indexOfFirst { it.contentId == contentId },
                    )
                    onItemClick(contentId)
                },
                onLoadMore = onLoadMore,
                gridState = gridState,
                restoredItemFocusRequester = restoredGridItemFocusRequester,
                restoredItemIndex = restoration.requesterItemIndex,
                onRestoreRequesterAttached = restoration::onRequesterAttached,
                onItemFocused = { index, focused ->
                    state.browseItems.getOrNull(index)?.let { item ->
                        if (focused) {
                            restoration.onItemFocused(item.contentId, index)
                        } else {
                            restoration.onItemFocusLost(item.contentId)
                        }
                    }
                },
                showGenreChips = showGenreChips,
                onGenreChanged = onGenreChanged,
                onClearAudiobookGroup = onClearAudiobookGroup,
                showBrowseControls = showBrowseControls,
                onOpenSortPanel = { openPanel = TvBrowsePanel.Sort },
                onOpenFilterPanel = { openPanel = TvBrowsePanel.Filter },
                onClearFilters = { onFacetSelectionApplied(TvCatalogFacetSelection()) },
            )
        }
        // The A–Z jump rail only makes sense for title-sorted browsing (the
        // tvOS `showsAlphabetRail` contract); any other sort hides it so the
        // right edge stays plain up/down grid navigation.
        if (showAlphabetRail && state.browseFilter.sort == TvLibrarySortOption.Title.wireValue) {
            TvAlphabetRail(
                selected = state.browseFilter.namePrefix,
                onSelect = onNamePrefixChanged,
                onUpFallbackChanged = onContentUpFallbackChanged,
                modifier = Modifier.padding(end = Spacing.md),
            )
        }
    }

    when (openPanel) {
        TvBrowsePanel.Sort -> TvBrowseSortPanel(
            options = TvLibrarySortOption.availableFor(state.libraryType),
            currentSort = state.browseFilter.sort,
            order = state.browseFilter.order,
            onSelect = { option ->
                onSortKeySelected(option)
                openPanel = null
            },
            onClose = { openPanel = null },
        )
        TvBrowsePanel.Filter -> TvBrowseFilterPanel(
            libraryType = state.libraryType,
            facetOptions = state.facetOptions,
            initial = state.browseFilter.facetSelection,
            onApply = onFacetSelectionApplied,
            onClose = { openPanel = null },
        )
        null -> Unit
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LibraryGrid(
    state: TvLibraryDetailViewModel.UiState,
    onItemClick: (String) -> Unit,
    onLoadMore: () -> Unit,
    gridState: LazyGridState,
    restoredItemFocusRequester: FocusRequester,
    restoredItemIndex: Int?,
    onRestoreRequesterAttached: (String?) -> Unit,
    /**
     * Both edges. Gain alone makes the caller's record of what holds focus
     * sticky, and the restoration reads that record as CURRENT focus.
     */
    onItemFocused: (index: Int, focused: Boolean) -> Unit,
    showGenreChips: Boolean,
    onGenreChanged: (String?) -> Unit,
    onClearAudiobookGroup: (() -> Unit)?,
    showBrowseControls: Boolean = false,
    onOpenSortPanel: () -> Unit = {},
    onOpenFilterPanel: () -> Unit = {},
    onClearFilters: () -> Unit = {},
) {
    var attachedRestoreItemId by remember { mutableStateOf<String?>(null) }
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
                // The control-row embed uses the taller tvOS library inset
                // (`ContinuumTheme.Skyline.libraryContentTopInset`, 216pt → 108dp).
                top = if (showBrowseControls) LibraryBrowseContentTopInset else TvTopMenuLayout.contentTopInset,
                end = Spacing.md,
                bottom = Spacing.xxxl,
            ),
        ) {
            if (showBrowseControls) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "browse-controls") {
                    val sortOption = TvLibrarySortOption.fromWire(state.browseFilter.sort)
                    var controlsFocused by remember { mutableStateOf(false) }
                    // Scrolling back up lands the pills via minimal
                    // bring-into-view (pinned at the viewport top edge);
                    // re-anchor to offset 0 so the row sits under the same
                    // tvOS headroom as on entry. The effect loops while the
                    // row holds focus because the focus-driven bring-into-view
                    // scroll runs AFTER the focus event and would cancel a
                    // single immediate animateScrollToItem on the shared
                    // scrollable state.
                    LaunchedEffect(controlsFocused) {
                        while (controlsFocused &&
                            (gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0)
                        ) {
                            kotlinx.coroutines.delay(80)
                            if (!controlsFocused) break
                            runCatching { gridState.animateScrollToItem(0) }
                        }
                    }
                    TvBrowseControlRow(
                        sortLabel = sortOption.label,
                        sortDirection = sortOption.directionLabel(state.browseFilter.order),
                        filterCount = state.browseFilter.facetSelection.activeFacetCount,
                        onSort = onOpenSortPanel,
                        onFilter = onOpenFilterPanel,
                        onClearFilters = onClearFilters,
                        modifier = Modifier.onFocusChanged { controlsFocused = it.hasFocus },
                    )
                }
            }

            if (showGenreChips) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "genres") {
                    GenreChipCloud(
                        genres = state.genres,
                        selectedGenre = state.browseFilter.genre,
                        loading = state.filtersLoading,
                        onGenreChanged = onGenreChanged,
                    )
                }
            }

            if (state.selectedAudiobookGroup != null && onClearAudiobookGroup != null) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "audiobook-group-header") {
                    AudiobookGroupDrillInHeader(
                        tab = state.selectedTab,
                        group = state.selectedAudiobookGroup,
                        onClear = onClearAudiobookGroup,
                    )
                }
            }

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
                    val (actions, userState) = org.siloserver.silo.tv.ui.components.rememberTvBrowseItemCardActions(item)
                    if (index == restoredItemIndex) {
                        // Report which identity the restore requester is
                        // actually bound to, once composition has applied.
                        // "The slot is visible" does not prove that: a card can
                        // be laid out while the modifier still carries the
                        // previous binding, so a restoration gated on layout
                        // alone can request focus at the wrong card.
                        DisposableEffect(item.contentId) {
                            attachedRestoreItemId = item.contentId
                            onRestoreRequesterAttached(item.contentId)
                            onDispose {
                                // Only when this card is still the owner. When
                                // the requester moves, the new card attaches
                                // before the old one disposes, so an
                                // unconditional clear wipes the live attachment
                                // and the restoration is reported NotReady
                                // against a requester that is in fact bound.
                                if (attachedRestoreItemId == item.contentId) {
                                    attachedRestoreItemId = null
                                    onRestoreRequesterAttached(null)
                                }
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
                        width = TvCardWidth,
                        fillWidth = true,
                        onClick = { onItemClick(item.contentId) },
                        focusRequester = restoredItemFocusRequester.takeIf { index == restoredItemIndex },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { onItemFocused(index, it.hasFocus) },
                        overlay = org.siloserver.silo.overlays.OverlayDataExtractor.fromBrowseItem(item),
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
private fun AudiobookGroupsTab(
    state: TvLibraryDetailViewModel.UiState,
    groupLabel: String,
    onGroupClick: (AudiobookGroup) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    onInitialContentFocus: () -> Unit,
) {
    val gridState: LazyGridState = rememberLazyGridState()
    val firstGroupFocusRequester = remember { FocusRequester() }
    var groupGridHasFocus by remember { mutableStateOf(false) }
    var initialFocusRequested by remember { mutableStateOf(false) }

    val nearEnd by remember(
        gridState,
        state.audiobookGroupsHasMore,
        state.audiobookGroups.size,
        state.audiobookGroupsLoading,
        state.audiobookGroupsLoadingMore,
    ) {
        derivedStateOf {
            if (!state.audiobookGroupsHasMore || state.audiobookGroupsLoading || state.audiobookGroupsLoadingMore) {
                false
            } else {
                val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()
                state.audiobookGroups.isNotEmpty() &&
                    lastVisible != null &&
                    lastVisible.index >= state.audiobookGroups.size -
                        (LibraryGridLoadMoreRowsThreshold * LibraryGridColumns)
            }
        }
    }

    LaunchedEffect(nearEnd) {
        if (nearEnd) onLoadMore()
    }

    LaunchedEffect(state.selectedTab, state.audiobookGroups.isNotEmpty()) {
        if (initialFocusRequested || state.audiobookGroups.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.delay(120)
        // onInitialContentFocus() hands content focus over to the shell. Firing
        // it after an unobserved claim tells the shell focus landed when it may
        // not have, which is how a screen ends up with no focus owner at all —
        // so it now fires only on observed acquisition.
        val landed = requestFocusUntilObserved(
            maxAttempts = TvContentInitialFocusMaxAttempts,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = firstGroupFocusRequester::requestFocus,
            isFocused = { groupGridHasFocus },
        )
        if (landed == TvObservedFocusResult.Focused) onInitialContentFocus()
        initialFocusRequested = true
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides TvSmoothBringIntoViewSpec) {
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(LibraryGridColumns),
            modifier = Modifier
                .fillMaxSize()
                .onFocusChanged { groupGridHasFocus = it.hasFocus },
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
                state.audiobookGroupsLoading && state.audiobookGroups.isEmpty() -> {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "loading") {
                        InlineLoadingState()
                    }
                }
                state.audiobookGroupsError != null && state.audiobookGroups.isEmpty() -> {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "error") {
                        TvErrorScreen(message = state.audiobookGroupsError, onRetry = onRetry)
                    }
                }
                state.audiobookGroups.isEmpty() -> {
                    item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                        TvCatalogEmptyState(message = "No audiobook $groupLabel found.")
                    }
                }
                else -> {
                    itemsIndexed(
                        state.audiobookGroups,
                        key = { _, group -> group.name },
                        contentType = { _, _ -> "audiobook-group" },
                    ) { index, group ->
                        TvAudiobookGroupCard(
                            group = group,
                            onClick = { onGroupClick(group) },
                            focusRequester = firstGroupFocusRequester.takeIf { index == 0 },
                        )
                    }
                }
            }

            if (state.audiobookGroupsLoadingMore) {
                item(span = { GridItemSpan(maxLineSpan) }, key = "loading-more") {
                    InlineLoadingState(verticalPadding = 24.dp)
                }
            }
        }
    }
}

@Composable
private fun AudiobookGroupDrillInHeader(
    tab: TvLibraryTab,
    group: AudiobookGroup,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvFilterChip(
            text = "All ${tab.label}",
            selected = false,
            onClick = onClear,
        )
        Column {
            Text(
                text = group.name,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White.copy(alpha = 0.94f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            audiobookGroupSubtitle(group)?.let { subtitle ->
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.62f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TvAudiobookGroupCard(
    group: AudiobookGroup,
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
                .aspectRatio(1f),
        ) {
            val posterUrl = group.posterUrls.firstOrNull { it.isNotBlank() }
            if (posterUrl != null) {
                ThumbhashImage(
                    url = posterUrl,
                    thumbhash = null,
                    contentDescription = group.name,
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
                        imageVector = Icons.Filled.Headphones,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.8f),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = group.name,
            style = MaterialTheme.typography.titleSmall,
            color = Color.White.copy(alpha = 0.92f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        audiobookGroupSubtitle(group)?.let { subtitle ->
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.68f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GenreChipCloud(
    genres: List<String>,
    selectedGenre: String?,
    loading: Boolean,
    onGenreChanged: (String?) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Text(
            text = if (loading) "Loading genres" else "Genres",
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.74f),
        )
        // One scrollable line, not a 3-row wall — the FlowRow cloud consumed
        // half the viewport and read as clutter (QA 2026-07-08).
        androidx.compose.foundation.lazy.LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            item(key = "genre-all") {
                TvFilterChip(
                    text = "All",
                    selected = selectedGenre == null,
                    onClick = { onGenreChanged(null) },
                )
            }
            // Server genre lists can contain duplicates — a repeated string
            // would collide as a Lazy key and crash (CodeRabbit PR#44).
            items(genres.distinct(), key = { it }) { genre ->
                TvFilterChip(
                    text = genre,
                    selected = selectedGenre == genre,
                    onClick = { onGenreChanged(genre) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CollectionsTab(
    state: TvLibraryDetailViewModel.UiState,
    onCollectionClick: (String, String, Boolean) -> Unit,
    onRetry: () -> Unit,
    onInitialContentFocus: () -> Unit,
) {
    val firstCollectionFocusRequester = remember { FocusRequester() }
    var collectionGridHasFocus by remember { mutableStateOf(false) }
    var initialFocusRequested by remember { mutableStateOf(false) }

    // First collection of the first non-empty group claims initial focus.
    val firstCollectionId = state.collectionSections
        .firstOrNull { it.collections.isNotEmpty() }
        ?.collections?.firstOrNull()?.id

    LaunchedEffect(firstCollectionId) {
        if (initialFocusRequested || firstCollectionId == null) return@LaunchedEffect
        kotlinx.coroutines.delay(120)
        val landed = requestFocusUntilObserved(
            maxAttempts = TvContentInitialFocusMaxAttempts,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = firstCollectionFocusRequester::requestFocus,
            isFocused = { collectionGridHasFocus },
        )
        if (landed == TvObservedFocusResult.Focused) onInitialContentFocus()
        initialFocusRequested = true
    }

    CompositionLocalProvider(LocalBringIntoViewSpec provides TvSmoothBringIntoViewSpec) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(LibraryGridColumns),
            modifier = Modifier
                .fillMaxSize()
                .onFocusChanged { collectionGridHasFocus = it.hasFocus },
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
                            onClick = {
                                onCollectionClick(
                                    collection.id,
                                    collection.name,
                                    section.kind == "user_collections",
                                )
                            },
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

private fun audiobookGroupSubtitle(group: AudiobookGroup): String? {
    val parts = mutableListOf<String>()
    if (group.itemCount > 0) {
        parts += "${group.itemCount} ${if (group.itemCount == 1) "book" else "books"}"
    }
    group.totalDurationSeconds
        ?.takeIf { it > 0 }
        ?.let(::formatAudiobookGroupDuration)
        ?.let { parts += it }
    if (group.inProgressCount > 0) {
        parts += "${group.inProgressCount} in progress"
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" • ")
}

private fun formatAudiobookGroupDuration(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
        hours > 0 -> "${hours}h"
        minutes > 0 -> "${minutes}m"
        else -> "<1m"
    }
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

// Catalog grid metrics, 1:1 with tvOS `TVCatalogGrid`: 6 columns (tvOS keeps
// all 6 beside the A–Z rail — the rail collapses to an edge peek), 40pt→20dp
// column spacing, 60pt→30dp row spacing.
private const val LibraryGridColumns = 6
private const val LibraryBrowseGridColumns = 6
private val LibraryGridColumnSpacing = 20.dp
private val LibraryGridRowSpacing = 30.dp
private const val LibraryGridLoadMoreRowsThreshold = 8

// tvOS `ContinuumTheme.Skyline.libraryContentTopInset` (216pt) — the taller
// clearance for the Browse pill's control-row embed.
private val LibraryBrowseContentTopInset = 108.dp
