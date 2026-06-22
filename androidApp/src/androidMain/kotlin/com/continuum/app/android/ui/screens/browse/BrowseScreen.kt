package com.continuum.app.android.ui.screens.browse

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
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.continuum.app.android.ui.components.PosterGridSkeleton
import com.continuum.app.android.ui.components.rememberShimmerProgress
import com.continuum.app.android.ui.theme.ContinuumSurfaceElevated

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
                    IconButton(onClick = { showFilterSheet = true }) {
                        Icon(
                            imageVector = Icons.Default.FilterList,
                            contentDescription = "Filters",
                        )
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
            // capsule chips (surfaceElevated bg, caption text, xmark.circle.fill).
            val activeFilterCount = state.filters.genres.size + state.filters.contentRatings.size
            if (activeFilterCount > 0 || state.filters.sort != "added_at") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.filters.genres.forEach { genre ->
                        ActiveFilterChip(
                            label = genre,
                            onRemove = {
                                viewModel.applyFilters(
                                    state.filters.copy(genres = state.filters.genres - genre)
                                )
                            },
                        )
                    }
                    state.filters.contentRatings.forEach { rating ->
                        ActiveFilterChip(
                            label = rating,
                            onRemove = {
                                viewModel.applyFilters(
                                    state.filters.copy(contentRatings = state.filters.contentRatings - rating)
                                )
                            },
                        )
                    }
                }
            }

            // Content area
            when {
                state.isLoading && state.items.isEmpty() -> {
                    PosterGridSkeleton(progress = rememberShimmerProgress())
                }

                state.error != null && state.items.isEmpty() -> {
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
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    // Filter bottom sheet
    if (showFilterSheet) {
        FilterSheet(
            currentFilters = state.filters,
            availableFilters = state.availableFilters,
            onApply = { viewModel.applyFilters(it) },
            onReset = { viewModel.resetFilters() },
            onDismiss = { showFilterSheet = false },
        )
    }
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
            .background(ContinuumSurfaceElevated)
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
