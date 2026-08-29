package org.siloserver.silo.android.ui.screens.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.android.ui.components.SiloTopBar
import org.siloserver.silo.android.ui.components.EmptyStateView
import org.siloserver.silo.android.ui.components.ErrorView
import org.siloserver.silo.android.ui.components.LoadingIndicator
import org.siloserver.silo.android.ui.components.MediaGridDefaults
import org.siloserver.silo.common.cards.LocalCardPresentation
import org.siloserver.silo.common.ui.components.DeferImagePresentationWhileScrolling
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.section.LibraryCollection
import org.siloserver.silo.model.section.LibraryCollectionsResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.SectionRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel

/**
 * One named or anonymous section as it should appear on screen, in the
 * order computed from `sort_order`. Mirrors `buildRenderOrder` on the web.
 */
data class LibraryCollectionSection(
    /** Display name. Empty for the anonymous Ungrouped bucket. */
    val name: String,
    val collections: List<LibraryCollection>,
)

data class LibraryCollectionsUiState(
    val sections: List<LibraryCollectionSection> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
) {
    val isEmpty: Boolean get() = sections.all { it.collections.isEmpty() }
}

class LibraryCollectionsViewModel(
    private val sectionRepository: SectionRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val libraryId: Int? = savedStateHandle.get<String>("libraryId")?.toIntOrNull()
    private val _uiState = MutableStateFlow(LibraryCollectionsUiState())
    val uiState: StateFlow<LibraryCollectionsUiState> = _uiState.asStateFlow()

    init {
        loadCollections()
    }

    fun loadCollections() {
        val currentLibraryId = libraryId ?: run {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isRefreshing = false,
                    error = "Missing library selection",
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    isRefreshing = false,
                    error = null,
                )
            }
            applyResult(sectionRepository.getLibraryCollectionsGrouped(currentLibraryId))
        }
    }

    fun refresh() {
        val currentLibraryId = libraryId ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, error = null) }
            applyResult(sectionRepository.getLibraryCollectionsGrouped(currentLibraryId))
        }
    }

    private fun applyResult(result: ApiResult<LibraryCollectionsResponse>) {
        when (result) {
            is ApiResult.Success -> _uiState.update {
                it.copy(
                    sections = buildSections(result.data),
                    isLoading = false,
                    isRefreshing = false,
                    error = null,
                )
            }
            is ApiResult.Error -> _uiState.update {
                it.copy(
                    isLoading = false,
                    isRefreshing = false,
                    error = result.message.ifBlank { "Failed to load collections" },
                )
            }
            is ApiResult.NetworkError -> _uiState.update {
                it.copy(
                    isLoading = false,
                    isRefreshing = false,
                    error = "Network error: ${result.exception.message ?: "unknown"}",
                )
            }
        }
    }

    /**
     * Builds the ordered render list: each group becomes a section in
     * `sort_order` order, and the ungrouped bucket is woven in at its own
     * `sort_order` slot. When the response is flat (no groups), a single
     * anonymous section is produced.
     */
    private fun buildSections(response: LibraryCollectionsResponse): List<LibraryCollectionSection> {
        val groups = response.groups
        val ungrouped = response.ungrouped

        if (groups.isEmpty() && ungrouped == null) {
            return if (response.collections.isEmpty()) {
                emptyList()
            } else {
                listOf(LibraryCollectionSection(name = "", collections = response.collections))
            }
        }

        data class Slot(val order: Int, val section: LibraryCollectionSection)
        val slots = mutableListOf<Slot>()
        for (group in groups) {
            if (group.collections.isEmpty()) continue
            slots += Slot(
                order = group.sortOrder,
                section = LibraryCollectionSection(
                    name = group.name,
                    collections = group.collections,
                ),
            )
        }
        if (ungrouped != null && ungrouped.collections.isNotEmpty()) {
            slots += Slot(
                order = ungrouped.sortOrder,
                section = LibraryCollectionSection(
                    name = "",
                    collections = ungrouped.collections,
                ),
            )
        }
        return slots.sortedBy { it.order }.map { it.section }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryCollectionsScreen(
    onBackClick: () -> Unit,
    onCollectionClick: (String) -> Unit,
    viewModel: LibraryCollectionsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    androidx.compose.material3.Scaffold(
        topBar = {
            SiloTopBar(
                title = "Collections",
                onBackClick = onBackClick,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        when {
            state.isLoading -> {
                LoadingIndicator(modifier = Modifier.padding(padding))
            }
            state.error != null && state.isEmpty -> {
                ErrorView(
                    message = state.error ?: "Unknown error",
                    onRetry = viewModel::loadCollections,
                    modifier = Modifier.padding(padding),
                )
            }
            state.isEmpty -> {
                EmptyStateView(
                    title = "No collections yet",
                    subtitle = "Create library collections in the web app to feature curated shelves here.",
                    icon = Icons.Outlined.CollectionsBookmark,
                    modifier = Modifier.padding(padding),
                )
            }
            else -> {
                PullToRefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = viewModel::refresh,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                ) {
                    val gridState = rememberLazyGridState()
                    DeferImagePresentationWhileScrolling(gridState) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(MediaGridDefaults.scaledPosterGridMinWidth),
                        state = gridState,
                        contentPadding = PaddingValues(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(MediaGridDefaults.PosterGridHorizontalSpacing),
                        verticalArrangement = Arrangement.spacedBy(MediaGridDefaults.PosterGridVerticalSpacing),
                    ) {
                        state.sections.forEachIndexed { index, section ->
                            if (section.name.isNotEmpty()) {
                                item(
                                    key = "header:$index",
                                    span = { GridItemSpan(maxLineSpan) },
                                ) {
                                    Text(
                                        text = section.name,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.padding(top = if (index == 0) 0.dp else 8.dp),
                                    )
                                }
                            }
                            items(
                                items = section.collections,
                                key = { c -> "${section.name}:${c.id}" },
                            ) { collection ->
                                LibraryCollectionCard(
                                    collection = collection,
                                    onClick = { onCollectionClick(collection.id) },
                                )
                            }
                        }
                    }
                    }
                }
            }
        }
    }
}

@Composable
private fun LibraryCollectionCard(
    collection: LibraryCollection,
    onClick: () -> Unit,
) {
    // Caption gating mirrors CollectionsView.swift: the name line follows
    // showsTitle, the type line showsMetadata. The count badge sits on the
    // artwork, so it stays either way.
    val caption = LocalCardPresentation.current.caption
    val countLabel = collection.itemCount?.takeIf { it > 0 }?.toString() ?: "Smart"
    val typeLabel = when {
        collection.kind == "user_collections" -> "User collection"
        else -> collection.collectionType
            ?.replaceFirstChar { it.uppercase() }
            ?: "Collection"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3.3f)
                .clip(MaterialTheme.shapes.small),
            contentAlignment = Alignment.BottomEnd,
        ) {
            if (!collection.posterUrl.isNullOrEmpty()) {
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
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CollectionsBookmark,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            Text(
                text = countLabel,
                fontSize = 12.sp,
                color = Color.White,
                modifier = Modifier
                    .padding(8.dp)
                    .clip(RoundedCornerShape(100))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            )
        }

        if (caption.showsTitle) {
            Text(
                text = collection.name,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (caption.showsMetadata) {
            Text(
                text = typeLabel,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
