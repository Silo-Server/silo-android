package org.siloserver.silo.tv.ui.screens.browse

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import org.siloserver.silo.tv.ui.focus.claimFocusOrReport
import org.siloserver.silo.tv.ui.focus.TvObservedFocusResult
import org.siloserver.silo.tv.ui.focus.TvContentInitialFocusMaxAttempts
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import org.siloserver.silo.tv.ui.focus.TvRestoreFocusOnModalDismiss
import org.siloserver.silo.tv.ui.components.TvCatalogEmptyState
import org.siloserver.silo.tv.ui.components.TvCatalogGrid
import org.siloserver.silo.tv.ui.components.TvErrorScreen
import org.siloserver.silo.tv.ui.components.TvFilterSheet
import org.siloserver.silo.tv.ui.shell.TvTopMenuLayout
import org.siloserver.silo.tv.ui.theme.Spacing
import org.siloserver.silo.tv.ui.theme.tvPresetGridColumns
import org.koin.compose.viewmodel.koinViewModel

/**
 * Android TV global Browse surface — the cross-library counterpart to
 * `BrowseScreen` on the phone. Unlike the per-library
 * [org.siloserver.silo.tv.ui.screens.library.TvLibraryDetailScreen] Library tab,
 * this browses the entire catalog and adds a content-rating filter dimension.
 *
 * The screen mirrors the library Library-tab grid closely: a big-letter header,
 * a filter entry row of active-filter pills, a 6-column poster grid with
 * snapshot-stable pagination (load-more within 8 rows of the end), and a
 * [TvFilterSheet] containing Genre / Content Rating / Sort chip groups.
 *
 * Reached as a secondary destination inside the top-menu shell (opened from
 * Settings, like Collections / Favorites). [onOpenItemDetail] navigates to the
 * immersive item detail screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TvBrowseScreen(
    onOpenItemDetail: (contentId: String) -> Unit,
    onInitialContentFocus: () -> Unit = {},
    viewModel: TvBrowseViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var showFilterSheet by remember { mutableStateOf(false) }
    // The sheet is a modal focus owner, so something has to hand focus back
    // when it closes. Left to the focus system, the successor is picked
    // geometrically and lands on whatever the dimmed page happened to have.
    val filterOpenerFocus = remember { FocusRequester() }
    var filterOpenerFocused by remember { mutableStateOf(false) }
    val filterOpenerModifier = Modifier
        .focusRequester(filterOpenerFocus)
        .onFocusChanged { filterOpenerFocused = it.isFocused }
    TvRestoreFocusOnModalDismiss(
        visible = showFilterSheet,
        opener = filterOpenerFocus,
        isOpenerFocused = { filterOpenerFocused },
    )

    val firstItemFocusRequester = remember { FocusRequester() }
    var browseGridHasFocus by remember { mutableStateOf(false) }
    var initialFocusRequested by remember { mutableStateOf(false) }

    LaunchedEffect(state.items.isNotEmpty()) {
        onInitialContentFocus()
        // Only consume the one-shot focus once there's a real target — otherwise
        // a slow first load (empty here) would permanently skip grid focus.
        if (initialFocusRequested || state.items.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.delay(120)
        requestFocusUntilObserved(
            maxAttempts = TvContentInitialFocusMaxAttempts,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = firstItemFocusRequester::requestFocus,
            isFocused = { browseGridHasFocus },
        )
        initialFocusRequested = true
    }

    val sortLabel = TvBrowseSortOption.fromWire(state.filter.sort).label

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onFocusChanged { browseGridHasFocus = it.hasFocus }
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (state.error != null && state.items.isEmpty()) {
                BrowseHeader(
                    subtitle = browseSubtitle(state),
                    sortLabel = sortLabel,
                    filter = state.filter,
                    onOpenFilters = { showFilterSheet = true },
                    filterOpenerModifier = filterOpenerModifier,
                    modifier = Modifier.padding(
                        top = TvTopMenuLayout.contentTopInset,
                        start = Spacing.safeArea,
                        end = Spacing.safeArea,
                    ),
                )
                TvErrorScreen(
                    message = state.error.orEmpty(),
                    onRetry = viewModel::retry,
                    modifier = Modifier.padding(
                        start = Spacing.safeArea,
                        end = Spacing.safeArea,
                    ),
                )
            } else if (state.loading && state.items.isEmpty()) {
                // Initial / post-filter load with nothing yet: keep the header
                // visible and show a centered spinner (handled here rather than
                // inside the grid so the grid never flashes its empty state).
                BrowseHeader(
                    subtitle = browseSubtitle(state),
                    sortLabel = sortLabel,
                    filter = state.filter,
                    onOpenFilters = { showFilterSheet = true },
                    filterOpenerModifier = filterOpenerModifier,
                    modifier = Modifier.padding(
                        top = TvTopMenuLayout.contentTopInset,
                        start = Spacing.safeArea,
                        end = Spacing.safeArea,
                    ),
                )
                InlineLoadingState()
            } else {
                BrowseGrid(
                    state = state,
                    sortLabel = sortLabel,
                    onItemClick = onOpenItemDetail,
                    onLoadMore = viewModel::loadMore,
                    onOpenFilters = { showFilterSheet = true },
                    filterOpenerModifier = filterOpenerModifier,
                    firstItemFocusRequester = firstItemFocusRequester,
                )
            }
        }

        TvFilterSheet(
            visible = showFilterSheet,
            onDismiss = { showFilterSheet = false },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg),
            ) {
                // --- Genre ---
                FilterSectionHeader("Genre")
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    FilterChoiceChip(
                        label = "All",
                        selected = state.filter.genre == null,
                        onClick = { viewModel.onGenreChanged(null) },
                    )
                    state.genres.forEach { genre ->
                        FilterChoiceChip(
                            label = genre,
                            selected = state.filter.genre == genre,
                            onClick = { viewModel.onGenreChanged(genre) },
                        )
                    }
                }

                // --- Content rating (the dimension the library browse lacks) ---
                if (state.contentRatings.isNotEmpty()) {
                    FilterSectionHeader("Content Rating")
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        FilterChoiceChip(
                            label = "All",
                            selected = state.filter.contentRating == null,
                            onClick = { viewModel.onContentRatingChanged(null) },
                        )
                        state.contentRatings.forEach { rating ->
                            FilterChoiceChip(
                                label = rating,
                                selected = state.filter.contentRating == rating,
                                onClick = { viewModel.onContentRatingChanged(rating) },
                            )
                        }
                    }
                }

                // --- Sort ---
                FilterSectionHeader("Sort")
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    TvBrowseSortOption.entries.forEach { option ->
                        FilterChoiceChip(
                            label = option.label,
                            selected = state.filter.sort == option.wireValue,
                            onClick = { viewModel.onSortChanged(option) },
                        )
                    }
                }

                // --- Order (phone parity) ---
                FilterSectionHeader("Order")
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    FilterChoiceChip(
                        label = "Descending",
                        selected = state.filter.order == "desc",
                        onClick = { viewModel.onOrderChanged("desc") },
                    )
                    FilterChoiceChip(
                        label = "Ascending",
                        selected = state.filter.order == "asc",
                        onClick = { viewModel.onOrderChanged("asc") },
                    )
                }

                // --- Reset (phone parity) ---
                // Filters apply reactively as chips are picked, so there's no
                // Apply; Reset clears back to defaults in one action.
                FilterSectionHeader("Reset")
                FilterChoiceChip(
                    label = "Reset filters",
                    selected = false,
                    onClick = { viewModel.resetFilters() },
                )
            }
        }
    }
}

@Composable
private fun BrowseGrid(
    state: TvBrowseViewModel.UiState,
    sortLabel: String,
    onItemClick: (String) -> Unit,
    onLoadMore: () -> Unit,
    onOpenFilters: () -> Unit,
    filterOpenerModifier: Modifier,
    firstItemFocusRequester: FocusRequester,
) {
    // Shared catalog grid: pagination, focus-restorer, header + empty state.
    // Initial/post-filter loading is handled by the caller (centered spinner),
    // so here isLoading only drives the load-more footer. loadMoreThreshold is
    // expressed in items (rows-from-end * column count) to preserve the prior
    // trigger distance.
    val browseColumns = tvPresetGridColumns(BrowseGridColumns)
    TvCatalogGrid(
        items = state.items,
        isLoading = state.loadingMore,
        hasMore = state.hasMore,
        onItemClick = onItemClick,
        onLoadMore = onLoadMore,
        modifier = Modifier.fillMaxSize(),
        fixedColumnCount = browseColumns,
        loadMoreThreshold = BrowseGridLoadMoreRowsThreshold * browseColumns,
        contentPadding = PaddingValues(
            start = Spacing.safeArea,
            top = TvTopMenuLayout.contentTopInset,
            end = Spacing.md,
            bottom = Spacing.xxxl,
        ),
        horizontalSpacing = BrowseGridItemSpacing,
        verticalSpacing = Spacing.sectionSpacing,
        firstItemFocusRequester = firstItemFocusRequester,
        header = {
            BrowseHeader(
                subtitle = browseSubtitle(state),
                sortLabel = sortLabel,
                filter = state.filter,
                onOpenFilters = onOpenFilters,
                filterOpenerModifier = filterOpenerModifier,
            )
        },
        emptyState = {
            TvCatalogEmptyState(message = "No titles match the current filters.")
        },
    )
}

// ============================================================================
// Header
// ============================================================================

@Composable
private fun BrowseHeader(
    subtitle: String,
    sortLabel: String,
    filter: TvBrowseFilter,
    onOpenFilters: () -> Unit,
    filterOpenerModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "Browse",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 21.sp,
                lineHeight = 23.sp,
                letterSpacing = 0.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        FilterRow(
            filter = filter,
            sortLabel = sortLabel,
            onOpenFilters = onOpenFilters,
            filterOpenerModifier = filterOpenerModifier,
        )
    }
}

// ============================================================================
// Filter row
// ============================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterRow(
    filter: TvBrowseFilter,
    sortLabel: String,
    onOpenFilters: () -> Unit,
    filterOpenerModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        FilterEntryButton(
            label = "Filter",
            onClick = onOpenFilters,
            modifier = filterOpenerModifier,
        )

        if (filter.genre != null) {
            ActiveFilterPill(label = "Genre: ${filter.genre}")
        }
        if (filter.contentRating != null) {
            ActiveFilterPill(label = "Rating: ${filter.contentRating}")
        }
        ActiveFilterPill(label = "Sort: $sortLabel")
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FilterEntryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val container = if (isFocused) Color.White.copy(alpha = 0.94f) else Color.White.copy(alpha = 0.08f)
    val foreground = if (isFocused) Color.Black else Color.White

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            contentColor = foreground,
            focusedContainerColor = Color.White.copy(alpha = 0.94f),
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White.copy(alpha = 0.94f),
            pressedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.025f),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
                shape = RoundedCornerShape(12.dp),
            ),
            focusedBorder = Border(
                border = BorderStroke(0.dp, Color.Transparent),
                shape = RoundedCornerShape(12.dp),
            ),
        ),
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = foreground,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun ActiveFilterPill(
    label: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(
                color = Color.White.copy(alpha = 0.08f),
                shape = RoundedCornerShape(999.dp),
            )
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.06f),
                shape = RoundedCornerShape(999.dp),
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White.copy(alpha = 0.85f),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FilterSectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        color = Color.White.copy(alpha = 0.7f),
        fontWeight = FontWeight.SemiBold,
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FilterChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val container = when {
        isFocused -> Color.White.copy(alpha = 0.94f)
        selected -> Color.White.copy(alpha = 0.22f)
        else -> Color.White.copy(alpha = 0.08f)
    }
    val foreground = when {
        isFocused -> Color.Black
        selected -> Color.White
        else -> Color.White.copy(alpha = 0.85f)
    }

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = container,
            contentColor = foreground,
            focusedContainerColor = Color.White.copy(alpha = 0.94f),
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White.copy(alpha = 0.94f),
            pressedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(
                    width = 1.dp,
                    color = if (selected) Color.White.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.06f),
                ),
                shape = RoundedCornerShape(12.dp),
            ),
            focusedBorder = Border(
                border = BorderStroke(0.dp, Color.Transparent),
                shape = RoundedCornerShape(12.dp),
            ),
        ),
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = foreground,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
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

private fun browseSubtitle(state: TvBrowseViewModel.UiState): String {
    val parts = buildList {
        state.filter.genre?.let { add(it) }
        state.filter.contentRating?.let { add(it) }
        if (state.items.isNotEmpty()) {
            add("${state.items.size}${if (state.hasMore) "+" else ""} titles")
        }
    }
    return parts.joinToString(" · ").ifBlank { "Everything across your libraries" }
}

private const val BrowseGridColumns = 6
private val BrowseGridItemSpacing = 20.dp
private const val BrowseGridLoadMoreRowsThreshold = 8
