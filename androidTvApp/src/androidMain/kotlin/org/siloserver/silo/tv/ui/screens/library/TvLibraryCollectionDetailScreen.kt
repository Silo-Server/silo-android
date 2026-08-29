package org.siloserver.silo.tv.ui.screens.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.tv.ui.focus.TvContentInitialFocusMaxAttempts
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.tv.ui.components.TvCatalogEmptyState
import org.siloserver.silo.tv.ui.components.TvCatalogGrid
import org.siloserver.silo.tv.ui.components.TvErrorScreen
import org.siloserver.silo.tv.ui.theme.Spacing
import org.siloserver.silo.tv.ui.theme.tvPresetGridColumns
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Which overlay panel is open over the collection grid (mirrors Browse). */
private enum class TvCollectionPanel { Sort, Filter }

@Composable
fun TvLibraryCollectionDetailScreen(
    libraryId: Int,
    collectionId: String,
    title: String,
    libraryType: String,
    onItemClick: (contentId: String) -> Unit,
    onBack: () -> Unit,
    viewModel: TvLibraryCollectionDetailViewModel = koinViewModel(
        key = "library-collection-$libraryId-$collectionId",
        parameters = { parametersOf(libraryId, collectionId, title) },
    ),
) {
    val state by viewModel.uiState.collectAsState()

    BackHandler(onBack = onBack)

    var openPanel by remember { mutableStateOf<TvCollectionPanel?>(null) }

    // Without an explicit focus target, the user lands on this screen with
    // nothing focused and has to mash D-pad before anything responds. This
    // fires once per visit: a sort/filter reload replaces the items, and
    // re-requesting then would yank focus off whatever pill the user is on.
    //
    // The landing check watches the first CARD, not the page: the Sort pill is
    // the grid's first focusable (header row), so Compose's default entry
    // parks there before the card is composed, and a page-level hasFocus
    // would report that as success and leave the user on the pill.
    val firstItemFocusRequester = remember { FocusRequester() }
    var firstCardHasFocus by remember { mutableStateOf(false) }
    var initialFocusRequested by remember { mutableStateOf(false) }
    LaunchedEffect(state.items.isNotEmpty()) {
        if (initialFocusRequested || state.items.isEmpty()) return@LaunchedEffect
        requestFocusUntilObserved(
            maxAttempts = TvContentInitialFocusMaxAttempts,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = firstItemFocusRequester::requestFocus,
            isFocused = { firstCardHasFocus },
        )
        initialFocusRequested = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // The title, pills, and count live INSIDE the grid as its header
        // row rather than above it. A header stacked over the grid shrinks
        // the grid's viewport, so bringing row 2 into view scrolled row 1
        // half under the pills — the "cards cut off" look. As a grid row
        // the header scrolls away with the content, and rows leaving the
        // top go under the screen edge like any scrolling list. The grid
        // also stays mounted across sort/filter reloads (spinner row), so
        // the header never blinks out.
        //
        // Failures render in the grid's empty slot rather than replacing
        // the whole surface: load() clears the items before a sort/filter
        // reload, so a whole-surface error would take the Sort/Filter/Clear
        // pills away exactly when the viewer needs them to undo the query
        // that is failing — Retry only repeats it (Codex).
        TvCatalogGrid(
            items = state.items,
            isLoading = state.isLoading || state.isLoadingMore,
            hasMore = state.hasMore,
            onItemClick = onItemClick,
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
            firstItemFocusRequester = firstItemFocusRequester,
            firstItemCardModifier = Modifier.onFocusChanged { firstCardHasFocus = it.isFocused },
            header = {
                CollectionHeader(
                    title = viewModel.title.ifBlank { title },
                    state = state,
                    onSort = { openPanel = TvCollectionPanel.Sort },
                    onFilter = { openPanel = TvCollectionPanel.Filter },
                    onClearFilters = viewModel::clearFilters,
                )
            },
            emptyState = {
                val error = state.error
                if (error != null) {
                    TvErrorScreen(message = error, onRetry = viewModel::retry)
                } else {
                    TvCatalogEmptyState(
                        message = if (state.facetSelection.hasActiveFilters) {
                            "No titles match the current filters."
                        } else {
                            "This collection is empty."
                        },
                    )
                }
            },
        )
    }

    when (openPanel) {
        TvCollectionPanel.Sort -> TvBrowseSortPanel(
            options = TvLibrarySortOption.availableForCollection(libraryType),
            currentSort = state.sort,
            order = state.order,
            onSelect = { option ->
                viewModel.onSortSelected(option)
                openPanel = null
            },
            onClose = { openPanel = null },
        )
        TvCollectionPanel.Filter -> TvBrowseFilterPanel(
            libraryType = libraryType,
            facetOptions = state.facetOptions,
            initial = state.facetSelection,
            onApply = viewModel::onFacetSelectionApplied,
            onClose = { openPanel = null },
        )
        null -> Unit
    }
}

/** Title, Sort/Filter pills, and item count — the grid's spanning header row. */
@Composable
private fun CollectionHeader(
    title: String,
    state: TvLibraryCollectionDetailViewModel.UiState,
    onSort: () -> Unit,
    onFilter: () -> Unit,
    onClearFilters: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val sortOption = state.sortOption
            TvBrowseControlRow(
                sortLabel = sortOption.label,
                sortDirection = sortOption.directionLabel(state.order),
                filterCount = state.facetSelection.activeFacetCount,
                onSort = onSort,
                onFilter = onFilter,
                onClearFilters = onClearFilters,
            )
            Spacer(modifier = Modifier.weight(1f))
            itemCountLabel(state)?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                )
            }
        }
    }
}

/**
 * "24 items" beside the controls, counted from the cards this client actually
 * shows. The server's collection total includes reading items TV hides
 * (`visibleOnTv`), so reporting it would claim a count the grid can never
 * reach — and would leak the excluded ebook membership. Hidden until paging
 * has exhausted, which is the first moment a TV-visible count is knowable.
 */
private fun itemCountLabel(state: TvLibraryCollectionDetailViewModel.UiState): String? {
    if (state.isLoading || state.isLoadingMore || state.hasMore) return null
    val total = state.items.size
    if (total == 0) return null
    return if (total == 1) "1 item" else "$total items"
}
