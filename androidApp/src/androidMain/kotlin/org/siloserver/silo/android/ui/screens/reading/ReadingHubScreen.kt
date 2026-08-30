package org.siloserver.silo.android.ui.screens.reading

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.ui.components.EmptyStateView
import org.siloserver.silo.android.ui.components.ErrorView
import org.siloserver.silo.android.ui.screens.browse.CatalogGrid
import org.siloserver.silo.android.ui.screens.browse.CatalogViewDensity
import org.siloserver.silo.android.ui.screens.home.HomeSectionRow
import org.siloserver.silo.android.ui.screens.libraries.LibrariesSubtab
import org.siloserver.silo.android.ui.screens.libraries.LibraryBrowseSort
import org.siloserver.silo.common.cards.LocalCardPresentation
import org.siloserver.silo.common.ui.components.DeferImagePresentationWhileScrolling
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.navigation.ReadingFormatFilter
import org.siloserver.silo.model.profile.Profile
import org.siloserver.silo.model.section.LibraryCollection
import org.siloserver.silo.model.section.ResolvedSection
import org.koin.compose.viewmodel.koinViewModel

private val ReadingContentTopPadding = 8.dp

@Composable
fun ReadingHubScreen(
    onItemClick: (String) -> Unit,
    onCollectionClick: (String, Int) -> Unit,
    activeProfile: Profile?,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSwitchProfileClick: () -> Unit,
    onSwitchServerClick: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ReadingHubViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val selectedLibrary = state.libraries.firstOrNull { it.id == state.selectedLibraryId }

    Scaffold(
        topBar = {
            ReadingHubTopBar(
                state = state,
                selectedLibraryName = selectedLibrary?.name,
                activeProfile = activeProfile,
                onLibrarySelected = viewModel::selectLibrary,
                onFormatSelected = viewModel::selectFormat,
                onSearchClick = onSearchClick,
                onSettingsClick = onSettingsClick,
                onSwitchProfileClick = onSwitchProfileClick,
                onSwitchServerClick = onSwitchServerClick,
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        modifier = modifier,
    ) { padding ->
        ReadingHubContent(
            state = state,
            contentPadding = padding,
            onTabSelected = viewModel::selectTab,
            onItemClick = onItemClick,
            onCollectionClick = { collectionId ->
                state.selectedLibraryId?.let { libraryId -> onCollectionClick(collectionId, libraryId) }
            },
            onRetry = viewModel::retryCurrentTab,
            onRefreshLibraries = viewModel::refresh,
            onLoadMore = viewModel::loadMoreCatalog,
            onGenreChanged = viewModel::selectBrowseGenre,
            onSortChanged = viewModel::selectBrowseSort,
            onNamePrefixChanged = viewModel::selectNamePrefix,
            onDensityChanged = viewModel::selectViewDensity,
        )
    }
}

@Composable
private fun ReadingHubTopBar(
    state: ReadingHubUiState,
    selectedLibraryName: String?,
    activeProfile: Profile?,
    onLibrarySelected: (Int) -> Unit,
    onFormatSelected: (ReadingFormatFilter) -> Unit,
    onSearchClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onSwitchProfileClick: () -> Unit,
    onSwitchServerClick: () -> Unit,
) {
    var libraryMenuExpanded by remember { mutableStateOf(false) }
    var profileMenuExpanded by remember { mutableStateOf(false) }
    val visibleLibraries = state.filteredLibraries

    Surface(
        color = MaterialTheme.colorScheme.background.copy(alpha = 0.96f),
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.MenuBook,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp),
                ) {
                    Text(
                        text = "Reading",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (selectedLibraryName != null) {
                        Row(
                            modifier = Modifier.clickable(
                                enabled = visibleLibraries.size > 1,
                                onClick = { libraryMenuExpanded = true },
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = selectedLibraryName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (visibleLibraries.size > 1) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
                IconButton(onClick = onSearchClick) {
                    Icon(Icons.Outlined.Search, contentDescription = "Search")
                }
                IconButton(onClick = onSettingsClick) {
                    Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                }
                Box {
                    Text(
                        text = activeProfile?.name ?: "Profile",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clickable { profileMenuExpanded = true }
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                    )
                    DropdownMenu(
                        expanded = profileMenuExpanded,
                        onDismissRequest = { profileMenuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Switch Profile") },
                            onClick = {
                                profileMenuExpanded = false
                                onSwitchProfileClick()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Switch Server") },
                            onClick = {
                                profileMenuExpanded = false
                                onSwitchServerClick()
                            },
                        )
                    }
                }
            }

            DropdownMenu(
                expanded = libraryMenuExpanded,
                onDismissRequest = { libraryMenuExpanded = false },
            ) {
                visibleLibraries.forEach { library ->
                    DropdownMenuItem(
                        text = { Text(library.name) },
                        onClick = {
                            libraryMenuExpanded = false
                            onLibrarySelected(library.id)
                        },
                    )
                }
            }

            if (state.availableFormats.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.availableFormats.forEach { format ->
                        FilterChip(
                            selected = state.selectedFormat == format,
                            onClick = { onFormatSelected(format) },
                            label = { Text(format.label) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadingHubContent(
    state: ReadingHubUiState,
    contentPadding: PaddingValues,
    onTabSelected: (LibrariesSubtab) -> Unit,
    onItemClick: (String) -> Unit,
    onCollectionClick: (String) -> Unit,
    onRetry: () -> Unit,
    onRefreshLibraries: () -> Unit,
    onLoadMore: () -> Unit,
    onGenreChanged: (String?) -> Unit,
    onSortChanged: (LibraryBrowseSort) -> Unit,
    onNamePrefixChanged: (String?) -> Unit,
    onDensityChanged: (CatalogViewDensity) -> Unit,
) {
    when {
        state.isLoadingLibraries && state.libraries.isEmpty() -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
        state.librariesError != null && state.libraries.isEmpty() -> {
            ErrorView(
                message = state.librariesError ?: "Failed to load reading libraries",
                onRetry = onRefreshLibraries,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            )
        }
        state.selectedLibraryId == null -> {
            EmptyStateView(
                title = state.selectedFormat.emptyTitle(),
                subtitle = state.selectedFormat.emptySubtitle(),
                icon = Icons.AutoMirrored.Filled.MenuBook,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            )
        }
        else -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                ReadingTabs(
                    selectedTab = state.selectedTab,
                    onTabSelected = onTabSelected,
                )
                when (state.selectedTab) {
                    LibrariesSubtab.Recommended -> ReadingRecommendedTab(
                        state = state,
                        onItemClick = onItemClick,
                        onRetry = onRetry,
                    )
                    LibrariesSubtab.Browse -> ReadingBrowseTab(
                        state = state,
                        onItemClick = onItemClick,
                        onRetry = onRetry,
                        onLoadMore = onLoadMore,
                        onGenreChanged = onGenreChanged,
                        onSortChanged = onSortChanged,
                        onNamePrefixChanged = onNamePrefixChanged,
                        onDensityChanged = onDensityChanged,
                    )
                    LibrariesSubtab.Collections -> ReadingCollectionsTab(
                        state = state,
                        onCollectionClick = onCollectionClick,
                        onRetry = onRetry,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReadingTabs(
    selectedTab: LibrariesSubtab,
    onTabSelected: (LibrariesSubtab) -> Unit,
) {
    val tabs = LibrariesSubtab.entries
    TabRow(selectedTabIndex = tabs.indexOf(selectedTab)) {
        tabs.forEach { tab ->
            Tab(
                selected = selectedTab == tab,
                onClick = { onTabSelected(tab) },
                text = { Text(tab.name) },
            )
        }
    }
}

@Composable
private fun ReadingRecommendedTab(
    state: ReadingHubUiState,
    onItemClick: (String) -> Unit,
    onRetry: () -> Unit,
) {
    when {
        state.isLoadingSections && state.sections.isEmpty() -> {
            LoadingBox()
        }
        state.sectionsError != null && state.sections.isEmpty() -> {
            ErrorView(
                message = state.sectionsError ?: "Failed to load recommendations",
                onRetry = onRetry,
                modifier = Modifier.fillMaxSize(),
            )
        }
        state.sections.isEmpty() -> {
            EmptyStateView(
                title = "No recommendations yet",
                subtitle = "Try browsing the full reading catalog",
                icon = Icons.AutoMirrored.Filled.MenuBook,
                modifier = Modifier.fillMaxSize(),
            )
        }
        else -> {
            val listState = rememberLazyListState()
            DeferImagePresentationWhileScrolling(listState) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(top = ReadingContentTopPadding, bottom = 96.dp),
            ) {
                items(
                    items = state.sections,
                    key = { section -> section.id },
                    contentType = { "reading-section-row" },
                ) { section ->
                    // No "See All" — iOS parity (H3, Jim 2026-07-10).
                    HomeSectionRow(
                        section = section.readingFriendlySection(),
                        onItemClick = onItemClick,
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun ReadingBrowseTab(
    state: ReadingHubUiState,
    onItemClick: (String) -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onGenreChanged: (String?) -> Unit,
    onSortChanged: (LibraryBrowseSort) -> Unit,
    onNamePrefixChanged: (String?) -> Unit,
    onDensityChanged: (CatalogViewDensity) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (state.browseGenres.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.selectedBrowseGenre == null,
                    onClick = { onGenreChanged(null) },
                    label = { Text("All") },
                )
                state.browseGenres.forEach { genre ->
                    FilterChip(
                        selected = state.selectedBrowseGenre == genre,
                        onClick = { onGenreChanged(genre) },
                        label = { Text(genre) },
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LibraryBrowseSort.entries.forEach { sort ->
                FilterChip(
                    selected = state.browseSort == sort,
                    onClick = { onSortChanged(sort) },
                    label = { Text(sort.label) },
                )
            }
            CatalogViewDensity.entries.forEach { density ->
                FilterChip(
                    selected = state.catalogDensity == density,
                    onClick = { onDensityChanged(density) },
                    label = { Text(density.label) },
                )
            }
        }

        when {
            state.isLoadingCatalog && state.catalogItems.isEmpty() -> {
                LoadingBox()
            }
            state.catalogError != null && state.catalogItems.isEmpty() -> {
                ErrorView(
                    message = state.catalogError ?: "Failed to load catalog",
                    onRetry = onRetry,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            state.catalogItems.isEmpty() -> {
                EmptyStateView(
                    title = "No items found",
                    subtitle = "Try adjusting the sort or switching reading libraries",
                    icon = Icons.AutoMirrored.Filled.MenuBook,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            else -> {
                Text(
                    text = buildString {
                        append(state.catalogTotal)
                        append(if (state.catalogTotal == 1) " item" else " items")
                        if (state.availableFormats.size > 1) {
                            append(" in ")
                            append(state.selectedFormat.label.lowercase())
                        }
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                CatalogGrid(
                    items = state.catalogItems,
                    isLoadingMore = state.isLoadingMoreCatalog,
                    hasMore = state.catalogHasMore,
                    onItemClick = onItemClick,
                    onLoadMore = onLoadMore,
                    selectedNamePrefix = state.selectedNamePrefix,
                    onNamePrefixSelected = onNamePrefixChanged,
                    viewDensity = state.catalogDensity,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun ReadingCollectionsTab(
    state: ReadingHubUiState,
    onCollectionClick: (String) -> Unit,
    onRetry: () -> Unit,
) {
    when {
        state.isLoadingCollections && state.collections.isEmpty() -> {
            LoadingBox()
        }
        state.collectionsError != null && state.collections.isEmpty() -> {
            ErrorView(
                message = state.collectionsError ?: "Failed to load collections",
                onRetry = onRetry,
                modifier = Modifier.fillMaxSize(),
            )
        }
        state.collections.isEmpty() -> {
            EmptyStateView(
                title = "No collections found",
                subtitle = "This reading library does not have any collections yet",
                icon = Icons.AutoMirrored.Filled.MenuBook,
                modifier = Modifier.fillMaxSize(),
            )
        }
        else -> {
            val gridState = rememberLazyGridState()
            DeferImagePresentationWhileScrolling(gridState) {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(
                    minSize = 140.dp * LocalCardPresentation.current.posterSize.posterScale,
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
            ) {
                items(
                    items = state.collections,
                    key = { it.id },
                    contentType = { "reading-collection" },
                ) { collection ->
                    ReadingCollectionCard(
                        collection = collection,
                        onClick = { onCollectionClick(collection.id) },
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun ReadingCollectionCard(
    collection: LibraryCollection,
    onClick: () -> Unit,
) {
    Column(modifier = Modifier.clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.62f))
                .aspectRatio(2f / 3f),
        ) {
            ThumbhashImage(
                url = collection.posterUrl,
                thumbhash = collection.posterThumbhash,
                contentDescription = collection.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            text = collection.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            text = collection.itemCount?.let { "$it items" } ?: "Collection",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LoadingBox() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

private fun ResolvedSection.readingFriendlySection(): ResolvedSection {
    val haystack = "$title $sectionType".lowercase()
    val isContinueSection = "continue" in haystack || "progress" in haystack
    return if (isContinueSection) {
        copy(title = "Continue Reading & Listening")
    } else {
        this
    }
}

private fun ReadingFormatFilter.emptyTitle(): String = when (this) {
    ReadingFormatFilter.All -> "No reading libraries"
    ReadingFormatFilter.Ebooks -> "No ebooks in this profile"
    ReadingFormatFilter.Audiobooks -> "No audiobooks in this profile"
}

private fun ReadingFormatFilter.emptySubtitle(): String = when (this) {
    ReadingFormatFilter.All -> "Ebooks and audiobooks visible to this profile will show up here"
    ReadingFormatFilter.Ebooks -> "Ebook, comic, and manga libraries visible to this profile will show up here"
    ReadingFormatFilter.Audiobooks -> "Audiobook libraries visible to this profile will show up here"
}
