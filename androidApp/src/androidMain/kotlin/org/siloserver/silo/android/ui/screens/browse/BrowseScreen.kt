package org.siloserver.silo.android.ui.screens.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.components.PosterGridSkeleton
import org.siloserver.silo.android.ui.components.rememberShimmerProgress
import org.siloserver.silo.android.ui.theme.SiloSurfaceElevated
import org.siloserver.silo.android.ui.util.formatCardDate

/**
 * The catalog/browse screen with filter chips and an infinite-scroll grid.
 *
 * Displays a top app bar with the library name (or "Browse"), filter chips
 * for quick genre/rating access, and a [CatalogGrid] of media cards.
 * A [FilterSheet] bottom sheet provides full filter and sort controls.
 *
 * @param onItemClick Callback with content ID when a card is tapped.
 * @param onBackClick Callback when the back button is pressed.
 * @param viewModel The browse ViewModel (provided by Koin).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(
    onItemClick: (String) -> Unit,
    onBackClick: () -> Unit,
    viewModel: BrowseViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    var showFilterSheet by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.headlineSmall,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    // iOS control bar: sort menu first, then the filter button
                    // with an active-facet count badge.
                    var sortMenuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = { sortMenuOpen = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Sort,
                            contentDescription = "Sort",
                        )
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = sortMenuOpen,
                        onDismissRequest = { sortMenuOpen = false },
                    ) {
                        browseSortOptions(state.mediaType).forEach { (field, label) ->
                            val active = state.filterState.sort == field
                            androidx.compose.material3.DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (active) {
                                            "$label  ${if (state.filterState.order == "desc") "↓" else "↑"}"
                                        } else {
                                            label
                                        },
                                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                    )
                                },
                                onClick = {
                                    viewModel.selectSort(field)
                                    sortMenuOpen = false
                                },
                            )
                        }
                    }
                    androidx.compose.material3.BadgedBox(
                        badge = {
                            if (state.filterState.activeFacetCount > 0) {
                                androidx.compose.material3.Badge {
                                    Text("${state.filterState.activeFacetCount}")
                                }
                            }
                        },
                    ) {
                        IconButton(onClick = { showFilterSheet = true }) {
                            Icon(
                                imageVector = Icons.Default.FilterList,
                                contentDescription = "Filters",
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.94f),
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        modifier = modifier,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            // Active filter chips — iOS phone: horizontal scroll of removable
            // capsule chips; each chip removes exactly one facet value.
            if (state.filterState.hasActiveFilters) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    org.siloserver.silo.catalog.filter.CatalogFacet.available(state.mediaType).forEach { facet ->
                        state.filterState.valuesFor(facet).sorted().forEach { value ->
                            ActiveFilterChip(
                                label = facetValueLabel(facet, value),
                                onRemove = {
                                    viewModel.applyFilterState(state.filterState.toggle(facet, value))
                                },
                            )
                        }
                    }
                }
            }

            // Content area
            when {
                state.isLoading && state.items.isEmpty() -> {
                    PosterGridSkeleton(progress = rememberShimmerProgress())
                }

                state.error != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(48.dp),
                            )
                            Text(
                                text = state.error ?: "Something went wrong",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = { viewModel.refresh() }) {
                                Text("Retry")
                            }
                        }
                    }
                }

                state.items.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Movie,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(64.dp),
                            )
                            Text(
                                text = "No items found",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                text = "Try adjusting your filters",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }

                else -> {
                    // iOS phone Browse shows the grid directly with no count header.
                    CatalogGrid(
                        items = state.items,
                        isLoadingMore = state.isLoadingMore,
                        hasMore = state.hasMore,
                        onItemClick = onItemClick,
                        onLoadMore = { viewModel.loadMore() },
                        // Date sorts surface the sorted-by date under each card.
                        cardSubtitle = when (state.filterState.sort) {
                            "added_at" -> { item -> formatCardDate(item.addedAt) }
                            "release_date" -> { item -> formatCardDate(item.releaseDate) }
                            else -> null
                        },
                        selectedNamePrefix = state.selectedNamePrefix,
                        onNamePrefixSelected = viewModel::selectNamePrefix,
                        viewDensity = state.catalogDensity,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    // Filter bottom sheet — edits a draft and commits on dismiss (iOS: no
    // Apply button; FilterView commits via onDisappear).
    if (showFilterSheet) {
        FilterSheet(
            viewDensity = state.catalogDensity,
            onSelectDensity = { viewModel.selectViewDensity(it) },
            currentFilters = state.filterState,
            availableFilters = state.availableFilters,
            mediaType = state.mediaType,
            preserveFilters = state.preserveFilters,
            onCommit = { viewModel.applyFilterState(it) },
            onSetPreserve = { viewModel.setPreserveFilters(it) },
            onDismiss = { showFilterSheet = false },
        )
    }
}

/** Sort keys per media type (iOS CatalogSortKey.available). */
internal fun browseSortOptions(
    mediaType: org.siloserver.silo.catalog.filter.BrowseFacetMediaType,
): List<Pair<String, String>> = when (mediaType) {
    org.siloserver.silo.catalog.filter.BrowseFacetMediaType.Video -> listOf(
        "title" to "Title",
        "added_at" to "Date Added",
        "year" to "Release Year",
        "rating_imdb" to "IMDb Rating",
        "runtime" to "Runtime",
        "resolution" to "Resolution",
    )
    org.siloserver.silo.catalog.filter.BrowseFacetMediaType.Audiobook -> listOf(
        "title" to "Title",
        "author" to "Author",
        "narrator" to "Narrator",
        "series" to "Series",
        "added_at" to "Date Added",
        "runtime" to "Runtime",
    )
}

/** Chip label for a facet value (decades and watch statuses have labels
 *  distinct from their wire values). */
internal fun facetValueLabel(
    facet: org.siloserver.silo.catalog.filter.CatalogFacet,
    value: String,
): String = when (facet) {
    org.siloserver.silo.catalog.filter.CatalogFacet.Decade -> "${value}s"
    org.siloserver.silo.catalog.filter.CatalogFacet.WatchStatus ->
        org.siloserver.silo.catalog.filter.CatalogFacet.WATCH_STATUS_OPTIONS
            .firstOrNull { it.first == value }?.second ?: value
    org.siloserver.silo.catalog.filter.CatalogFacet.DynamicRange ->
        org.siloserver.silo.catalog.filter.CatalogFacet.DYNAMIC_RANGE_OPTIONS
            .firstOrNull { it.first == value }?.second ?: value
    else -> value
}

/**
 * Removable active-filter capsule chip matching the iOS phone Browse view:
 * surfaceElevated capsule, 12sp label in onSurface, an xmark.circle.fill-style
 * close glyph (14pt) in secondary text, 4dp inner spacing, 10/5 padding.
 */
@Composable
private fun ActiveFilterChip(
    label: String,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(SiloSurfaceElevated)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Icon(
            imageVector = Icons.Filled.Cancel,
            contentDescription = "Remove filter",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(14.dp)
                .clickable(onClick = onRemove),
        )
    }
}
