package org.siloserver.silo.android.ui.screens.people

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.components.EmptyStateView
import org.siloserver.silo.android.ui.components.ErrorView
import org.siloserver.silo.android.ui.components.LoadingIndicator
import org.siloserver.silo.android.ui.components.MediaCard
import org.siloserver.silo.android.ui.components.rememberBrowseItemCardActions
import org.siloserver.silo.android.ui.theme.SiloOnSurface
import org.siloserver.silo.android.ui.theme.SiloSecondaryText
import org.siloserver.silo.android.ui.theme.SiloSurfaceElevated
import org.siloserver.silo.android.ui.theme.SiloSurfaceVariant
import org.siloserver.silo.android.ui.theme.PillShape
import org.siloserver.silo.common.cards.LocalCardPresentation
import org.siloserver.silo.common.ui.components.DeferImagePresentationWhileScrolling
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.Person
import org.siloserver.silo.model.catalog.personInitials
import org.siloserver.silo.model.catalog.personMetadataBadges
import org.siloserver.silo.model.catalog.personWorksCountLabel
import java.time.LocalDate

private val SafePadding = 16.dp
private val LargePadding = 24.dp

/**
 * Person detail screen — actor / creator profile.
 *
 * Mirrors the iOS `PersonDetailView` phone layout:
 *   - top header: 132dp portrait + name + metadata badges + bio
 *   - filmography: filter row (All / Movies / Series) → poster grid
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonDetailScreen(
    onBackClick: () -> Unit,
    onItemClick: (String) -> Unit,
    viewModel: PersonDetailViewModel,
) {
    val state by viewModel.uiState.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when {
            state.isLoading && state.person == null -> {
                LoadingIndicator()
            }
            state.error != null && state.person == null -> {
                ErrorView(
                    message = state.error ?: "Something went wrong",
                    onRetry = { viewModel.reload() },
                )
            }
            state.person != null -> {
                PullToRefreshBox(
                    isRefreshing = state.isLoading,
                    onRefresh = { viewModel.reload() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                PersonDetailContent(
                    person = state.person!!,
                    isRefreshingMetadata = state.isRefreshingMetadata,
                    items = state.items,
                    isLoadingItems = state.isLoadingItems,
                    selectedFilter = state.selectedFilter,
                    availableFilters = state.availableFilters,
                    totalItems = state.totalItems,
                    hasMore = state.hasMore,
                    pagingError = state.pagingError,
                    onFilterSelected = { viewModel.applyFilter(it) },
                    onLoadMore = viewModel::loadMoreIfNeeded,
                    onItemClick = onItemClick,
                )
                }
            }
            else -> Unit
        }

        IconButton(
            onClick = onBackClick,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(8.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f)),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
            )
        }
    }
}

@Composable
private fun PersonDetailContent(
    person: Person,
    isRefreshingMetadata: Boolean,
    items: List<BrowseItem>,
    isLoadingItems: Boolean,
    selectedFilter: PersonMediaFilter,
    availableFilters: List<PersonMediaFilter>,
    totalItems: Int,
    hasMore: Boolean,
    pagingError: String?,
    onFilterSelected: (PersonMediaFilter) -> Unit,
    onLoadMore: () -> Unit,
    onItemClick: (String) -> Unit,
) {
    val gridState = rememberLazyGridState()
    DeferImagePresentationWhileScrolling(gridState) {
    LazyVerticalGrid(
        // Filmography cards only — the person portrait keeps its fixed size.
        columns = GridCells.Adaptive(
            minSize = 110.dp * LocalCardPresentation.current.posterSize.posterScale,
        ),
        state = gridState,
        contentPadding = PaddingValues(
            start = SafePadding,
            end = SafePadding,
            top = 0.dp,
            bottom = LargePadding,
        ),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(top = 56.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                PersonHeader(person = person, isRefreshingMetadata = isRefreshingMetadata)
                FilmographyHeader(
                    selected = selectedFilter,
                    availableFilters = availableFilters,
                    totalLoaded = items.size,
                    totalItems = totalItems,
                    hasMore = hasMore,
                    onSelect = onFilterSelected,
                )
            }
        }

        if (items.isEmpty() && isLoadingItems) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        color = SiloOnSurface,
                    )
                }
            }
        } else if (items.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    EmptyStateView(
                        title = "No titles found",
                        subtitle = "There are no works linked to this person yet.",
                        icon = Icons.Outlined.Movie,
                    )
                }
            }
        } else {
            items(items, key = { it.contentId }) { item ->
                val (actions, userState) = rememberBrowseItemCardActions(item)
                MediaCard(
                    title = item.title,
                    posterUrl = item.posterUrl,
                    posterThumbhash = item.posterThumbhash,
                    year = item.year.takeIf { it > 0 },
                    type = item.type,
                    userState = userState,
                    onClick = { onItemClick(item.contentId) },
                    modifier = Modifier.fillMaxWidth(),
                    artworkAspectRatio = personWorkCardAspectRatio(item),
                    overlay = org.siloserver.silo.overlays.OverlayDataExtractor.fromBrowseItem(item),
                    actions = actions,
                )
            }

            if (hasMore || isLoadingItems || pagingError != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    LaunchedEffect(items.size, hasMore) {
                        if (hasMore && !isLoadingItems) onLoadMore()
                    }
                    PagingFooter(
                        isLoading = isLoadingItems,
                        error = pagingError,
                        onRetry = onLoadMore,
                    )
                }
            }
        }
    }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonHeader(person: Person, isRefreshingMetadata: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalAlignment = Alignment.Top,
    ) {
        PersonPortrait(person = person)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = person.name,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = SiloOnSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            val badges = personMetadataBadges(person, todayIso = LocalDate.now().toString())
            if (badges.isNotEmpty() || isRefreshingMetadata) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // iOS PersonMetadataRefreshIndicator: a spinner pill while
                    // the server metadata refresh poll runs.
                    if (isRefreshingMetadata) {
                        Surface(shape = PillShape, color = SiloSurfaceVariant) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            ) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    strokeWidth = 1.5.dp,
                                    modifier = Modifier.size(11.dp),
                                )
                                Text(
                                    text = "Loading metadata",
                                    fontSize = 12.sp,
                                    color = SiloOnSurface,
                                )
                            }
                        }
                    }
                    badges.forEach { badge ->
                        Surface(
                            shape = PillShape,
                            color = SiloSurfaceVariant,
                        ) {
                            Text(
                                text = badge,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Normal,
                                color = SiloOnSurface,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
            }
            val bio = person.bio?.trim()?.takeIf { it.isNotBlank() }
            if (bio != null) {
                Text(
                    text = bio,
                    fontSize = 14.sp,
                    color = SiloSecondaryText,
                    maxLines = 8,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ExternalProfileSection(person = person)
        }
    }
}

@Composable
private fun PersonPortrait(person: Person) {
    val width = 132.dp
    val height = (width.value * 1.5f).dp
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .clip(shape)
            .background(SiloSurfaceElevated)
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (!person.photoUrl.isNullOrBlank()) {
            ThumbhashImage(
                url = person.photoUrl,
                thumbhash = person.photoThumbhash,
                contentDescription = person.name,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = personInitials(person.name),
                fontSize = (width.value * 0.28f).sp,
                fontWeight = FontWeight.SemiBold,
                color = SiloSecondaryText,
            )
        }
    }
}

@Composable
private fun FilmographyHeader(
    selected: PersonMediaFilter,
    availableFilters: List<PersonMediaFilter>,
    totalLoaded: Int,
    totalItems: Int,
    hasMore: Boolean,
    onSelect: (PersonMediaFilter) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "Filmography",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = SiloOnSurface,
                modifier = Modifier.weight(1f),
            )
            personWorksCountLabel(total = totalItems, loaded = totalLoaded, hasMore = hasMore)?.let { label ->
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Normal,
                    color = SiloSecondaryText,
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        ) {
            availableFilters.forEach { filter ->
                FilterChip(
                    title = filter.title,
                    isSelected = filter == selected,
                    onClick = { onSelect(filter) },
                )
            }
        }
    }
}

@Composable
private fun FilterChip(
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = PillShape,
        color = if (isSelected) SiloSurfaceVariant else SiloSurfaceElevated.copy(alpha = 0.55f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            Color.White.copy(alpha = if (isSelected) 0.16f else 0.08f),
        ),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Normal,
            color = if (isSelected) SiloOnSurface else SiloSecondaryText,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun ExternalProfileSection(person: Person) {
    // Provider ids (TMDB/IMDb/TVDB/Plex) are internal bookkeeping; no other
    // client surfaces them. Only the homepage is user-facing.
    val rows = buildList {
        person.homepage?.trim()?.takeIf { it.isNotBlank() }?.let { add("Homepage" to it) }
    }
    if (rows.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        rows.forEach { (label, value) ->
            Surface(
                shape = PillShape,
                color = SiloSurfaceElevated.copy(alpha = 0.7f),
            ) {
                Text(
                    text = "$label: $value",
                    fontSize = 12.sp,
                    color = SiloSecondaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                )
            }
        }
    }
}

@Composable
private fun PagingFooter(
    isLoading: Boolean,
    error: String?,
    onRetry: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            isLoading -> CircularProgressIndicator(color = SiloOnSurface)
            error != null -> Text(
                text = error,
                color = SiloSecondaryText,
                modifier = Modifier.clickable(onClick = onRetry),
            )
        }
    }
}

private fun personWorkCardAspectRatio(item: BrowseItem): Float =
    if (item.type == "audiobook") {
        1f
    } else {
        2f / 3.3f
    }
