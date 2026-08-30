package org.siloserver.silo.tv.ui.screens.people

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import org.siloserver.silo.tv.ui.focus.TvContentInitialFocusMaxAttempts
import org.siloserver.silo.tv.ui.focus.TvFrameRelocationMaxAttempts
import org.siloserver.silo.tv.ui.focus.rememberTvFlatReturnRestoration
import org.siloserver.silo.tv.ui.focus.claimFocusOrReport
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.Person
import org.siloserver.silo.model.catalog.personMetadataBadges
import org.siloserver.silo.tv.ui.components.TvCatalogEmptyState
import org.siloserver.silo.tv.ui.components.TvCatalogGrid
import org.siloserver.silo.tv.ui.components.TvErrorScreen
import org.siloserver.silo.tv.ui.components.TvLoadingScreen
import org.siloserver.silo.tv.ui.theme.DarkSurfaceElevated
import org.siloserver.silo.tv.ui.theme.Spacing
import org.siloserver.silo.tv.ui.theme.tvPresetGridColumns
import java.time.LocalDate
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlinx.coroutines.launch

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
    val selectedFilterFocusRequester = remember { FocusRequester() }
    var filterRowHasFocus by remember { mutableStateOf(false) }
    var lastRefocusedFilter by remember { mutableStateOf(state.selectedFilter) }
    val bioFocusRequester = remember { FocusRequester() }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    var initialFocusRequested by remember { mutableStateOf(false) }

    val restoreHeaderTop = {
        scope.launch { gridState.animateScrollToItem(0) }
        Unit
    }
    // Up from the filter chips walks into the bio (a focusable, expandable
    // stop like the hero synopsis on item detail pages) when one exists;
    // otherwise it just re-anchors the header like before.
    val hasBio = remember(person.bio) { cleanPersonBio(person.bio) != null }
    val focusBio = {
        bioFocusRequester.claimFocusOrReport(target = "person_bio", action = "focus_bio")
        scope.launch { gridState.animateScrollToItem(0) }
        Unit
    }

    val restoreItemFocusRequester = remember { FocusRequester() }
    // Returns land on the poster the viewer opened. Entry does not: this page
    // opens on the filter row so the identity header stays visible, so on a
    // fresh arrival the restoration stands down and the effect below is
    // untouched.
    val restoration = rememberTvFlatReturnRestoration(
        itemIds = state.items.map { it.contentId },
        hasMore = state.hasMore,
        isLoadingMore = state.isLoadingItems,
        errorMessage = state.pagingError,
        surfaceKey = "person-${person.id}-${state.selectedFilter.name}",
        onLoadMore = onLoadMore,
        // The header is one full-span slot ahead of the posters, so the grid's
        // own index runs one past the item index.
        scrollToItem = { itemIndex -> gridState.scrollToItem(itemIndex + PersonGridHeaderSlots) },
        requestFocus = restoreItemFocusRequester::requestFocus,
        onRestored = {},
        focusFirstItemWithoutTarget = false,
    )

    // Enter on the filter row so the full identity header remains visible.
    // Moving down into the posters then scrolls the whole header away naturally.
    // A return owns entry instead, so this stands aside for one.
    LaunchedEffect(state.availableFilters.isNotEmpty()) {
        if (initialFocusRequested || state.availableFilters.isEmpty()) return@LaunchedEffect
        if (restoration.isReturning) return@LaunchedEffect
        kotlinx.coroutines.delay(120)
        requestFocusUntilObserved(
            maxAttempts = TvContentInitialFocusMaxAttempts,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = selectedFilterFocusRequester::requestFocus,
            isFocused = { filterRowHasFocus },
        )
        initialFocusRequested = true
    }

    // key() below disposes the whole grid on a filter change, filter row and
    // all — including the chip the viewer just pressed. Nothing else would put
    // focus back on it.
    //
    // Every change refocuses, whether a press caused it or an async gate fell
    // back to All on its own. Distinguishing the two was the earlier design and
    // it was wrong: it existed to avoid yanking focus off a poster the viewer
    // was reading, but the key change has already destroyed that poster by the
    // time this runs. There is no focus left to preserve — only focus to lose.
    //
    // The first composition is not a change. Seeding from the current value is
    // what makes that true even on a return, where entry belongs to the
    // restoration rather than to the chips.
    LaunchedEffect(state.selectedFilter) {
        if (state.selectedFilter == lastRefocusedFilter) return@LaunchedEffect
        lastRefocusedFilter = state.selectedFilter
        // key() disposes the whole grid on a filter change, including the chip
        // the viewer just pressed, so this is a relocation onto a node being
        // recreated — short budget, and observed rather than assumed.
        requestFocusUntilObserved(
            maxAttempts = TvFrameRelocationMaxAttempts,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = selectedFilterFocusRequester::requestFocus,
            isFocused = { filterRowHasFocus },
        )
    }

    // The surface key includes the filter, and the helper requires item
    // content to be recreated when it changes. applyFilter usually empties
    // the list first, which disposes the cards anyway — but not on every
    // path: an asynchronous filter gate can fall back to All without
    // clearing. Keying it here makes the precondition hold by construction
    // rather than by timing.
    key(state.selectedFilter) {
        TvCatalogGrid(
            items = state.items,
            isLoading = state.isLoadingItems,
            hasMore = state.hasMore,
            onItemClick = { contentId ->
                restoration.onItemClicked(
                    itemId = contentId,
                    index = state.items.indexOfFirst { it.contentId == contentId },
                )
                onOpenItemDetail(contentId)
            },
            onLoadMore = onLoadMore,
            restoreItemIndex = restoration.requesterItemIndex,
            restoreItemFocusRequester = restoreItemFocusRequester,
            onRestoreRequesterAttached = restoration::onRequesterAttached,
            onItemFocusedAtIndex = { item, index, focused ->
                if (focused) {
                    restoration.onItemFocused(item.contentId, index)
                } else {
                    restoration.onItemFocusLost(item.contentId)
                }
            },
            modifier = Modifier.fillMaxSize(),
            gridState = gridState,
            fixedColumnCount = tvPresetGridColumns(PersonGridColumns),
            // tvOS `TVPersonDetailContent`: 48pt page top, 72pt bottom, 40pt grid
            // column spacing, 48pt header → filmography gap (all halved to dp).
            contentPadding = PaddingValues(
                start = Spacing.safeArea,
                top = 24.dp,
                end = Spacing.safeArea,
                bottom = 36.dp,
            ),
            horizontalSpacing = PersonGridItemSpacing,
            verticalSpacing = Spacing.sectionSpacing,
            artworkAspectRatioForItem = ::personWorkCardAspectRatio,
            header = {
                // The chips live in this header and the flag belongs to the
                // screen, so observe here: "focus is in the header" is the
                // criterion the retries above are actually protecting.
                Column(
                    modifier = Modifier.onFocusChanged { filterRowHasFocus = it.hasFocus },
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    PersonHeader(person = person, bioFocusRequester = bioFocusRequester)
                    FilmographyHeader(
                        selected = state.selectedFilter,
                        availableFilters = state.availableFilters,
                        totalLoaded = state.items.size,
                        totalItems = state.totalItems,
                        hasMore = state.hasMore,
                        selectedFilterFocusRequester = selectedFilterFocusRequester,
                        onMoveUp = if (hasBio) focusBio else restoreHeaderTop,
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
}

// ============================================================================
// Header (portrait + name + metadata badges + bio)
// ============================================================================

// tvOS `TVPersonDetailContent.header` at half scale: 300pt portrait, 48pt
// portrait↔text gap, 72pt bold name, 22pt column spacing, 10pt column top
// inset, secondary-gray bio capped at 7 lines / 920pt width.
@Composable
private fun PersonHeader(person: Person, bioFocusRequester: FocusRequester) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.Top,
    ) {
        PersonPortrait(person = person)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 5.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) {
            Text(
                text = person.name,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 36.sp,
                lineHeight = 40.sp,
                letterSpacing = 0.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val badges = personMetadataBadges(person, todayIso = LocalDate.now().toString())
            if (badges.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    badges.forEach { badge -> MetadataBadge(text = badge) }
                }
            }
            val bio = cleanPersonBio(person.bio)
            if (bio != null) {
                TvExpandablePersonBio(bio = bio, focusRequester = bioFocusRequester)
            } else {
                Text(
                    text = "No biography or personal details are available yet.",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp),
                    color = Color.White.copy(alpha = 0.48f),
                    modifier = Modifier.widthIn(max = 530.dp),
                )
            }
        }
    }
}

/**
 * The person bio as a focus stop: reachable by pressing Up from the filter
 * chips, clamped to 7 lines at rest with the [TvExpandableSynopsis]-style
 * focus fill. OK/Select opens the full bio in a scrollable modal overlay
 * ([TvPersonBioDialog]) instead of expanding in place — long bios (which
 * push the whole page down when inlined) scroll inside the modal.
 */
@Composable
private fun TvExpandablePersonBio(
    bio: String,
    focusRequester: FocusRequester,
) {
    var showFullBio by remember(bio) { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val shape = RoundedCornerShape(4.dp)

    Column(
        modifier = Modifier
            .widthIn(max = 530.dp)
            .then(
                if (isFocused) {
                    Modifier.background(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        shape = shape,
                    )
                } else {
                    Modifier
                },
            )
            .focusRequester(focusRequester)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
            ) { showFullBio = true }
            .padding(vertical = 4.dp),
    ) {
        Text(
            text = bio,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 20.sp),
            color = PersonSecondaryText,
            maxLines = 7,
            overflow = TextOverflow.Ellipsis,
        )
    }

    if (showFullBio) {
        TvPersonBioDialog(
            bio = bio,
            onDismiss = { showFullBio = false },
        )
        // Dismissing the focusable Popup drops window focus back on the page
        // with no saved target; put it back on the bio the user launched from.
        DisposableEffect(Unit) {
            onDispose {
                // Teardown: no scope left to retry in, but a dropped claim here
                // leaves the page with nothing focused after the popup closes.
                focusRequester.claimFocusOrReport(
                    target = "person_bio",
                    action = "popup_dismissed",
                )
            }
        }
    }
}

/**
 * Full-bio modal for TV, following the [TvMediaInfoDialog] idiom: a
 * window-level focusable Popup over a dimmed scrim, D-pad Up/Down scrolls
 * the text, Back or OK dismisses.
 */
@Composable
private fun TvPersonBioDialog(
    bio: String,
    onDismiss: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    var bioModalHasFocus by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()
    val scrollScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(50)
        requestFocusUntilObserved(
            maxAttempts = TvContentInitialFocusMaxAttempts,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = focus::requestFocus,
            isFocused = { bioModalHasFocus },
        )
    }
    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true, dismissOnBackPress = true),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.62f)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.56f)
                    .fillMaxHeight(0.76f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.98f))
                    .border(0.6.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(18.dp))
                    .onPreviewKeyEvent { ev ->
                        when {
                            ev.type == KeyEventType.KeyUp &&
                                (ev.key == Key.Back || ev.key == Key.Escape ||
                                    ev.key == Key.DirectionCenter || ev.key == Key.Enter) -> {
                                onDismiss()
                                true
                            }
                            ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionDown -> {
                                scrollScope.launch {
                                    scrollState.animateScrollTo(
                                        (scrollState.value + 180).coerceAtMost(scrollState.maxValue),
                                    )
                                }
                                true
                            }
                            ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionUp -> {
                                scrollScope.launch {
                                    scrollState.animateScrollTo((scrollState.value - 180).coerceAtLeast(0))
                                }
                                true
                            }
                            else -> false
                        }
                    },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .focusRequester(focus)
                        .onFocusChanged { bioModalHasFocus = it.hasFocus }
                        .focusable()
                        .padding(horizontal = 32.dp, vertical = 28.dp),
                ) {
                    Text(
                        text = bio,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontSize = 16.sp,
                            lineHeight = 23.sp,
                        ),
                        color = Color.White.copy(alpha = 0.82f),
                    )
                }
            }
        }
    }
}

// tvOS `PersonPortrait(width: 300)` at half scale: 150×225dp, 12pt corner
// radius → 6dp, hairline white stroke, initials fallback at width × 0.28.
@Composable
private fun PersonPortrait(person: Person) {
    val width = 150.dp
    val height = (width.value * 1.5f).dp
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .clip(shape)
            .background(DarkSurfaceElevated)
            .border(1.dp, Color.White.copy(alpha = 0.10f), shape),
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
            Text(
                text = person.initials(),
                fontSize = 42.sp,
                fontWeight = FontWeight.SemiBold,
                color = PersonSecondaryText,
            )
        }
    }
}

private fun Person.initials(): String {
    val letters = name
        .split(" ")
        .filter { it.isNotBlank() }
        .take(2)
        .mapNotNull { it.firstOrNull() }
        .joinToString("")
        .uppercase()
    return letters.ifEmpty { "?" }
}

// tvOS metadata badge (20pt small font, 16×8 capsule padding), sized up from
// the strict halving for readability alongside the bio and filter pills.
@Composable
private fun MetadataBadge(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 14.sp,
                lineHeight = 18.sp,
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
    /**
     * Attaches to the SELECTED chip — the entry point on a fresh arrival, and
     * the chip to restore after a filter change recreates this row.
     */
    selectedFilterFocusRequester: FocusRequester,
    onMoveUp: () -> Unit,
    onSelect: (TvPersonMediaFilter) -> Unit,
) {
    // tvOS `filmographyHeader` at half scale: title-case 36pt semibold
    // headline with the count label sitting beside it at the baseline,
    // 20pt gap down to the filter chips.
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Filmography",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontSize = 18.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.sp,
                ),
                color = Color.White,
            )
            tvPersonFilmographyCountLabel(total = totalItems, loaded = totalLoaded, hasMore = hasMore)?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                    ),
                    color = PersonSecondaryText,
                    // Optical baseline alignment against the headline.
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .onPreviewKeyEvent { event ->
                    if (event.key == Key.DirectionUp) {
                        if (event.type == KeyEventType.KeyDown) onMoveUp()
                        // The identity header is intentionally non-focusable;
                        // consume both phases so Compose's geometric search
                        // cannot dead-end or jump outside the page.
                        true
                    } else {
                        false
                    }
                },
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            availableFilters.forEachIndexed { index, filter ->
                FilterChoiceChip(
                    label = filter.title,
                    selected = filter == selected,
                    onClick = { onSelect(filter) },
                    // Falls back to the first chip when the selection is not
                    // among the available filters, so the requester is never
                    // left unattached.
                    modifier = if (
                        filter == selected ||
                        (index == 0 && selected !in availableFilters)
                    ) {
                        Modifier.focusRequester(selectedFilterFocusRequester)
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
            // Sized up from the strict tvOS halving (22pt caption → 11sp)
            // for readability, padding scaled with it.
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = 14.sp,
                lineHeight = 18.sp,
            ),
            color = foreground,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 6.dp),
        )
    }
}

// Matches BrowseGridColumns — the person grid used 7 columns, so its cards
// rendered smaller than every other grid in the app (QA 2026-07-08). tvOS
// `TVCatalogGrid` runs 6 columns with 40pt column spacing.
private const val PersonGridColumns = 6
private val PersonGridItemSpacing = 20.dp

/** tvOS `continuumSecondaryText` (#99EDEDED). */
private val PersonSecondaryText = Color(0x99EDEDED)

// Server bios often arrive with a dangling "..."/"…" truncation marker —
// on its own line (any Unicode line break) or stacked straight after the
// sentence period ("career...."). Strip the whole trailing dot/whitespace
// run and restore a single period, so the bio ends cleanly; genuinely long
// bios still get Compose's own overflow ellipsis at the 7-line cap.
private fun cleanPersonBio(raw: String?): String? {
    var text = raw?.trim().orEmpty()
    // TMDB bios sourced from Wikipedia carry a trailing attribution
    // paragraph ("Description above from the Wikipedia article …, licensed
    // under CC-BY-SA, full list of contributors on Wikipedia."). The 7-line
    // clamp truncated at the BLANK separator line before it, rendering a
    // lone "…" on its own line. Drop the paragraph — the same net content
    // tvOS displays after its own line clamp, minus the artifact.
    val attributionIndex = text.indexOf(
        "Description above from the Wikipedia",
        ignoreCase = true,
    )
    if (attributionIndex > 0) {
        text = text.substring(0, attributionIndex).trim()
    }
    if (text.endsWith("…") || text.endsWith("...")) {
        text = text.trimEnd { it == '.' || it == '…' || it.isWhitespace() }
        if (text.isNotEmpty() && !text.last().isLetterOrDigit()) {
            // Ends in other punctuation ("?!,\"") — leave it as-is.
        } else if (text.isNotEmpty()) {
            text += "."
        }
    }
    return text.takeIf { it.isNotBlank() }
}

// tvOS `personFilmographyCountLabel`: the known total wins ("72 titles"),
// otherwise the loaded count with a "+" while more pages remain.
private fun tvPersonFilmographyCountLabel(total: Int, loaded: Int, hasMore: Boolean): String? = when {
    total > 0 -> if (total == 1) "1 title" else "$total titles"
    loaded <= 0 -> null
    hasMore -> "$loaded+ titles"
    loaded == 1 -> "1 title"
    else -> "$loaded titles"
}

private fun personWorkCardAspectRatio(item: BrowseItem): Float? =
    if (item.type == "audiobook") {
        1f
    } else {
        null
    }

/** The person header is a single full-span slot ahead of the poster grid. */
private const val PersonGridHeaderSlots: Int = 1
