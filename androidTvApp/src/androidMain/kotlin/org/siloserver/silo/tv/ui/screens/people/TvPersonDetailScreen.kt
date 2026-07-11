package org.siloserver.silo.tv.ui.screens.people

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.Person
import org.siloserver.silo.model.catalog.personMetadataBadges
import org.siloserver.silo.model.catalog.personWorksCountLabel
import org.siloserver.silo.tv.ui.components.TvCatalogEmptyState
import org.siloserver.silo.tv.ui.components.TvCatalogGrid
import org.siloserver.silo.tv.ui.components.TvErrorScreen
import org.siloserver.silo.tv.ui.components.TvLoadingScreen
import org.siloserver.silo.tv.ui.theme.DarkSurfaceElevated
import org.siloserver.silo.tv.ui.theme.Spacing
import java.time.LocalDate
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * Android TV person detail surface — the cast/crew profile plus their
 * filmography. Mirrors the phone `PersonDetailScreen`: a header (large portrait
 * + name + birth/death/birthplace badges + bio) over a media-type filter row
 * and a poster grid.
 *
 * TV idioms follow the tvOS person screen: the identity, filters, and
 * filmography form one continuous scrolling surface. `onOpenItemDetail` routes
 * a poster to its detail page.
 */
@Composable
fun TvPersonDetailScreen(
    personId: Long,
    onOpenItemDetail: (contentId: String) -> Unit,
    onBack: () -> Unit,
    viewModel: TvPersonDetailViewModel = koinViewModel(
        key = "person-$personId",
        parameters = { parametersOf(personId) },
    ),
) {
    val state by viewModel.uiState.collectAsState()

    BackHandler(enabled = true) { onBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when {
            state.isLoading && state.person == null -> TvLoadingScreen(
                modifier = Modifier.background(MaterialTheme.colorScheme.background),
            )
            state.error != null && state.person == null -> TvErrorScreen(
                message = state.error!!,
                onRetry = viewModel::reload,
                modifier = Modifier.background(MaterialTheme.colorScheme.background),
            )
            state.person != null -> TvPersonDetailContent(
                person = state.person!!,
                state = state,
                onFilterSelected = viewModel::applyFilter,
                onLoadMore = viewModel::loadMoreIfNeeded,
                onRetryItems = viewModel::retryItems,
                onOpenItemDetail = onOpenItemDetail,
            )
        }
    }
}

@Composable
private fun TvPersonDetailContent(
    person: Person,
    state: TvPersonDetailUiState,
    onFilterSelected: (TvPersonMediaFilter) -> Unit,
    onLoadMore: () -> Unit,
    onRetryItems: () -> Unit,
    onOpenItemDetail: (contentId: String) -> Unit,
) {
    val firstFilterFocusRequester = remember { FocusRequester() }
    var initialFocusRequested by remember { mutableStateOf(false) }

    // Enter on the filter row so the full identity header remains visible.
    // Moving down into the posters then scrolls the whole header away naturally.
    LaunchedEffect(state.availableFilters.isNotEmpty()) {
        if (initialFocusRequested || state.availableFilters.isEmpty()) return@LaunchedEffect
        kotlinx.coroutines.delay(120)
        runCatching { firstFilterFocusRequester.requestFocus() }
        initialFocusRequested = true
    }

    TvCatalogGrid(
        items = state.items,
        isLoading = state.isLoadingItems,
        hasMore = state.hasMore,
        onItemClick = onOpenItemDetail,
        onLoadMore = onLoadMore,
        modifier = Modifier.fillMaxSize(),
        fixedColumnCount = PersonGridColumns,
        contentPadding = PaddingValues(
            start = Spacing.safeArea,
            top = 22.dp,
            end = Spacing.safeArea,
            bottom = Spacing.xxl,
        ),
        horizontalSpacing = PersonGridItemSpacing,
        verticalSpacing = Spacing.sectionSpacing,
        artworkAspectRatioForItem = ::personWorkCardAspectRatio,
        header = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PersonHeader(person = person)
                FilmographyHeader(
                    selected = state.selectedFilter,
                    availableFilters = state.availableFilters,
                    totalLoaded = state.items.size,
                    totalItems = state.totalItems,
                    hasMore = state.hasMore,
                    firstFilterFocusRequester = firstFilterFocusRequester,
                    onSelect = onFilterSelected,
                )
                state.pagingError?.let { error ->
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 14.sp,
                            lineHeight = 17.sp,
                        ),
                        color = Color.White.copy(alpha = 0.62f),
                    )
                    // A failed page-0 load leaves the grid with nothing
                    // focusable below the chips. Keep retry in the scrolling
                    // header instead of dead-ending on the empty state.
                    if (state.items.isEmpty()) {
                        Button(
                            onClick = onRetryItems,
                            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 12.dp),
                        ) {
                            Text("Retry", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
        },
        emptyState = {
            TvCatalogEmptyState(message = "No titles found.")
        },
    )
}

// ============================================================================
// Header (portrait + name + metadata badges + bio)
// ============================================================================

@Composable
private fun PersonHeader(person: Person) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.Top,
    ) {
        PersonPortrait(person = person)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = person.name,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 30.sp,
                lineHeight = 34.sp,
                letterSpacing = 0.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val badges = personMetadataBadges(person, todayIso = LocalDate.now().toString())
            if (badges.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    badges.forEach { badge -> MetadataBadge(text = badge) }
                }
            }
            val bio = person.bio?.trim()?.takeIf { it.isNotBlank() }
            if (bio != null) {
                Text(
                    text = bio,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp),
                    color = Color.White.copy(alpha = 0.78f),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(0.67f),
                )
            } else {
                Text(
                    text = "No biography or personal details are available yet.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 18.sp),
                    color = Color.White.copy(alpha = 0.48f),
                    modifier = Modifier.fillMaxWidth(0.67f),
                )
            }
        }
    }
}

@Composable
private fun PersonPortrait(person: Person) {
    val width = 96.dp
    val height = (width.value * 1.5f).dp
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .clip(RoundedCornerShape(16.dp))
            .background(DarkSurfaceElevated),
        contentAlignment = Alignment.Center,
    ) {
        if (!person.photoUrl.isNullOrBlank()) {
            ThumbhashImage(
                url = person.photoUrl,
                thumbhash = person.photoThumbhash,
                contentDescription = person.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.size(width * 0.4f),
            )
        }
    }
}

@Composable
private fun MetadataBadge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 12.sp,
                lineHeight = 14.sp,
            ),
            color = Color.White.copy(alpha = 0.85f),
            fontWeight = FontWeight.Medium,
        )
    }
}

// ============================================================================
// Filmography header (title + count + media-type filter chips)
// ============================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilmographyHeader(
    selected: TvPersonMediaFilter,
    availableFilters: List<TvPersonMediaFilter>,
    totalLoaded: Int,
    totalItems: Int,
    hasMore: Boolean,
    firstFilterFocusRequester: FocusRequester,
    onSelect: (TvPersonMediaFilter) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(9.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "FILMOGRAPHY",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = 24.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                ),
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            personWorksCountLabel(total = totalItems, loaded = totalLoaded, hasMore = hasMore)?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            availableFilters.forEachIndexed { index, filter ->
                FilterChoiceChip(
                    label = filter.title,
                    selected = filter == selected,
                    onClick = { onSelect(filter) },
                    modifier = if (index == 0) {
                        Modifier.focusRequester(firstFilterFocusRequester)
                    } else {
                        Modifier
                    },
                )
            }
        }
    }
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
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
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
                shape = RoundedCornerShape(999.dp),
            ),
            focusedBorder = Border(
                border = BorderStroke(0.dp, Color.Transparent),
                shape = RoundedCornerShape(999.dp),
            ),
        ),
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 13.sp,
                lineHeight = 15.sp,
            ),
            color = foreground,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

// Matches BrowseGridColumns — the person grid used 7 columns, so its cards
// rendered smaller than every other grid in the app (QA 2026-07-08).
private const val PersonGridColumns = 6
private val PersonGridItemSpacing = 16.dp

private fun personWorkCardAspectRatio(item: BrowseItem): Float? =
    if (item.type == "audiobook") {
        1f
    } else {
        null
    }
