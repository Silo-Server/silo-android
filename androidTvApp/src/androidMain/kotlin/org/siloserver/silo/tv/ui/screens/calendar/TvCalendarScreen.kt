package org.siloserver.silo.tv.ui.screens.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import org.siloserver.silo.tv.ui.theme.TvSmoothBringIntoViewSpec
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.calendar.CalendarBadge
import org.siloserver.silo.model.calendar.CalendarFilter
import org.siloserver.silo.model.calendar.CalendarItem
import org.siloserver.silo.model.calendar.CalendarItemType
import org.siloserver.silo.tv.ui.components.LocalAmbientBackdropTint
import org.siloserver.silo.tv.ui.components.TvLoadingScreen
import org.siloserver.silo.tv.ui.components.TvRootHeroBackdrop
import org.siloserver.silo.tv.ui.components.rememberAmbientBackdropTintState
import org.siloserver.silo.tv.ui.shell.TvTopMenuLayout
import org.siloserver.silo.tv.ui.theme.DarkOnPrimary
import org.siloserver.silo.tv.ui.theme.FocusedContainer
import org.siloserver.silo.tv.ui.theme.FocusedContent
import org.siloserver.silo.tv.ui.theme.Spacing
import org.siloserver.silo.viewmodel.CalendarViewModel
import kotlinx.coroutines.launch
import org.koin.compose.viewmodel.koinViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * TV calendar / upcoming screen. Reuses the shared [CalendarViewModel] that
 * drives the phone Calendar screen (week-bucketed releases from
 * `GET /api/v1/calendar`). Rebuilt to match the silo-apple tvOS Calendar:
 *
 *  - A single segmented Following / Trending / All capsule (one container, the
 *    selected segment filled), matching tvOS without an Android-only rail.
 *  - A focusable week strip: prev/next chevrons around seven day cells (weekday
 *    + day number + an event-dot when the day has releases), a selected-day
 *    highlight independent of "today", a "Today" jump, and a trailing
 *    "June 2026" month-year label. Selecting a day scrolls the day list to that
 *    shelf and hands focus to its first card.
 *  - A vertical stack of per-day shelves: each day is a heading ("Today" /
 *    "Monday, June 9") over a HORIZONTAL row (LazyRow) of portrait poster
 *    cards. Event-less days render a "Nothing scheduled" stub so the week keeps
 *    its shape and every day is a scroll target.
 *  - Whole-screen empty state with a focusable action ("Show Everything" when
 *    filtered, else "Refresh") and a filter-aware title.
 *
 * Mirrors [org.siloserver.silo.tv.ui.screens.recommendations.TvRecommendationsScreen]
 * for the koinViewModel + initial-focus-once pattern.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvCalendarScreen(
    onOpenItemDetail: (contentId: String) -> Unit,
    onInitialContentFocus: () -> Unit = {},
    viewModel: CalendarViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    // First focusable element is the segmented filter capsule so the D-pad
    // lands there when the Calendar tab swaps in; gate the jump so a silent
    // re-emission doesn't yank focus back after the user has navigated.
    val filterFocusRequester = remember { FocusRequester() }
    var initialFocusRequested by remember { mutableStateOf(false) }
    LaunchedEffect(state.weekStart) {
        if (initialFocusRequested) return@LaunchedEffect
        runCatching { filterFocusRequester.requestFocus() }
        onInitialContentFocus()
        initialFocusRequested = true
    }

    // Focus hand-off for day selection: picking a day in the week strip scrolls
    // to that day's shelf and kicks focus onto its first card. Only the most
    // recently selected day sees a changing, non-zero token, so exactly one
    // shelf claims focus.
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var shelfFocusDay by remember { mutableStateOf<String?>(null) }
    var shelfFocusRequest by remember { mutableIntStateOf(0) }
    val tintState = rememberAmbientBackdropTintState()
    val initialTintItem = state.weekDates
        .asSequence()
        .mapNotNull { date ->
            state.itemsFor(date).firstOrNull { !it.posterUrl.isNullOrBlank() }
        }
        .firstOrNull()
    LaunchedEffect(initialTintItem?.contentId, initialTintItem?.posterUrl) {
        tintState.set(null, initialTintItem?.posterUrl)
    }

    CompositionLocalProvider(LocalAmbientBackdropTint provides tintState) {
        Box(
            modifier = Modifier.fillMaxSize(),
        ) {
            TvRootHeroBackdrop(
                content = null,
                emptyWashColor = Color(0xFF14365A),
                modifier = Modifier.fillMaxSize(),
            )

            Column(
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(
                    modifier = Modifier.padding(
                        start = Spacing.safeArea,
                        end = Spacing.safeArea,
                        top = TvTopMenuLayout.contentTopInset,
                        bottom = Spacing.sm,
                    ),
                    verticalArrangement = Arrangement.spacedBy(Spacing.lg),
                ) {
                    CalendarControlRow(
                        selectedFilter = state.filter,
                        onSelectFilter = viewModel::setFilter,
                        firstSegmentFocusRequester = filterFocusRequester,
                    )

                    WeekStrip(
                        weekDates = state.weekDates,
                        today = state.today,
                        selectedDay = state.selectedDay,
                        isCurrentWeek = state.isCurrentWeek,
                        hasEvents = { date -> state.itemsFor(date).isNotEmpty() },
                        onSelectDay = { date ->
                            viewModel.selectDay(date)
                            val index = state.weekDates.indexOf(date)
                            if (index >= 0) {
                                scope.launch { listState.animateScrollToItem(index) }
                            }
                            if (state.itemsFor(date).isNotEmpty()) {
                                shelfFocusDay = date
                                shelfFocusRequest += 1
                            }
                        },
                        onPrevWeek = viewModel::prevWeek,
                        onNextWeek = viewModel::nextWeek,
                        onToday = viewModel::goToToday,
                    )
                }

                when {
                    // Unconditional (phone parity): the shared VM keeps the old week's
                    // days during a new week/filter load, so gating these on
                    // !hasAnyItems would show stale content + suppress errors on a
                    // week change.
                    state.isLoading -> TvLoadingScreen()
                    state.error != null -> CalendarMessage(
                        title = state.error ?: "Failed to load calendar",
                        subtitle = "Press the week arrows to try another week.",
                        action = CalendarAction("Refresh", viewModel::refresh),
                    )
                    !state.hasAnyItems -> CalendarMessage(
                        title = emptyTitle(state.filter),
                        subtitle = emptyCopy(state.filter),
                        action = if (state.filter != CalendarFilter.All) {
                            CalendarAction("Show Everything") { viewModel.setFilter(CalendarFilter.All) }
                        } else {
                            CalendarAction("Refresh", viewModel::refresh)
                        },
                    )
                    else -> DayList(
                        state = state,
                        listState = listState,
                        shelfFocusDay = shelfFocusDay,
                        shelfFocusRequest = shelfFocusRequest,
                        // Consume the token once a shelf has applied it so a later
                        // dispose/recompose of that shelf (LazyColumn recycle or
                        // prefetch) can't replay the still-non-zero token and yank
                        // the row back to item 0, stealing focus.
                        onShelfFocusConsumed = {
                            shelfFocusDay = null
                            shelfFocusRequest = 0
                        },
                        onItemFocused = { item -> tintState.set(null, item.posterUrl) },
                        onOpenItemDetail = onOpenItemDetail,
                    )
                }
            }
        }
    }
}

// MARK: - Filter bar (segmented capsule)

@Composable
private fun CalendarControlRow(
    selectedFilter: String,
    onSelectFilter: (String) -> Unit,
    firstSegmentFocusRequester: FocusRequester,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusGroup(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterBar(
            selected = selectedFilter,
            onSelect = onSelectFilter,
            firstSegmentFocusRequester = firstSegmentFocusRequester,
        )

        Spacer(modifier = Modifier.weight(1f))
    }
}

/**
 * Single segmented Following / Trending / All capsule — one container wrapping
 * the three segments, the selected segment filled. Matches tvOS
 * `CalendarFilterBar` rather than three free-standing chips.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FilterBar(
    selected: String,
    onSelect: (String) -> Unit,
    firstSegmentFocusRequester: FocusRequester,
) {
    val presets = listOf(
        CalendarFilter.Following to "Following",
        CalendarFilter.Trending to "Trending",
        CalendarFilter.All to "All",
    )
    Row(
        modifier = Modifier
            .focusGroup()
            .clip(RoundedCornerShape(100.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        presets.forEachIndexed { index, (value, label) ->
            FilterSegment(
                label = label,
                selected = selected == value,
                onClick = { onSelect(value) },
                focusRequester = if (index == 0) firstSegmentFocusRequester else null,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun FilterSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    focusRequester: FocusRequester?,
) {
    val shape = RoundedCornerShape(100.dp)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) Color.White.copy(alpha = 0.88f) else Color.Transparent,
            contentColor = if (selected) DarkOnPrimary else Color.White.copy(alpha = 0.70f),
            focusedContainerColor = FocusedContainer,
            focusedContentColor = FocusedContent,
            pressedContainerColor = FocusedContainer,
            pressedContentColor = FocusedContent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        border = ClickableSurfaceDefaults.border(
            border = Border.None,
            focusedBorder = Border.None,
        ),
        modifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 14.sp,
                    lineHeight = 17.sp,
                ),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// MARK: - Week strip

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun WeekStrip(
    weekDates: List<String>,
    today: String,
    selectedDay: String,
    isCurrentWeek: Boolean,
    hasEvents: (String) -> Boolean,
    onSelectDay: (String) -> Unit,
    onPrevWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onToday: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        ChevronButton(arrow = NavArrow.Prev, onClick = onPrevWeek)
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            weekDates.forEach { date ->
                DayCell(
                    date = date,
                    isSelected = date == selectedDay,
                    isToday = date == today,
                    hasEvents = hasEvents(date),
                    onClick = { onSelectDay(date) },
                )
            }
        }
        ChevronButton(arrow = NavArrow.Next, onClick = onNextWeek)
        if (!isCurrentWeek) {
            TodayButton(onClick = onToday)
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = monthYearLabel(weekDates),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Color.White.copy(alpha = 0.60f),
        )
    }
}

private enum class NavArrow { Prev, Next }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ChevronButton(arrow: NavArrow, onClick: () -> Unit) {
    val shape = CircleShape
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.08f),
            contentColor = Color.White.copy(alpha = 0.70f),
            focusedContainerColor = FocusedContainer,
            focusedContentColor = FocusedContent,
            pressedContainerColor = FocusedContainer,
            pressedContentColor = FocusedContent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        modifier = Modifier.size(28.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Icon(
                imageVector = when (arrow) {
                    NavArrow.Prev -> Icons.AutoMirrored.Filled.KeyboardArrowLeft
                    NavArrow.Next -> Icons.AutoMirrored.Filled.KeyboardArrowRight
                },
                contentDescription = when (arrow) {
                    NavArrow.Prev -> "Previous week"
                    NavArrow.Next -> "Next week"
                },
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TodayButton(onClick: () -> Unit) {
    val shape = RoundedCornerShape(100.dp)
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.08f),
            contentColor = Color.White.copy(alpha = 0.82f),
            focusedContainerColor = FocusedContainer,
            focusedContentColor = FocusedContent,
            pressedContainerColor = FocusedContainer,
            pressedContentColor = FocusedContent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Today",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DayCell(
    date: String,
    isSelected: Boolean,
    isToday: Boolean,
    hasEvents: Boolean,
    onClick: () -> Unit,
) {
    val localDate = remember(date) { LocalDate.parse(date) }
    val shape = RoundedCornerShape(12.dp)
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val inverted = isSelected || isFocused

    Surface(
        onClick = onClick,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) Color.White.copy(alpha = 0.88f) else Color.White.copy(alpha = 0.05f),
            contentColor = if (isSelected) DarkOnPrimary else Color.White,
            focusedContainerColor = FocusedContainer,
            focusedContentColor = FocusedContent,
            pressedContainerColor = FocusedContainer,
            pressedContentColor = FocusedContent,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(
                    width = if (isToday && !isSelected) 1.dp else 0.dp,
                    color = if (isToday && !isSelected) Color.White.copy(alpha = 0.35f) else Color.Transparent,
                ),
                shape = shape,
            ),
            focusedBorder = Border.None,
        ),
        modifier = Modifier.size(width = 39.dp, height = 46.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = localDate.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())),
                style = MaterialTheme.typography.labelMedium,
                color = if (inverted) {
                    (if (isFocused) FocusedContent else DarkOnPrimary).copy(alpha = 0.7f)
                } else {
                    Color.White.copy(alpha = 0.6f)
                },
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = localDate.dayOfMonth.toString(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(
                        if (hasEvents) {
                            if (inverted) {
                                (if (isFocused) FocusedContent else DarkOnPrimary)
                            } else {
                                Color.White.copy(alpha = 0.8f)
                            }
                        } else {
                            Color.Transparent
                        },
                    ),
            )
        }
    }
}

/** "June 2026" — anchored on the Thursday so a cross-month week shows the dominant month. */
private fun monthYearLabel(weekDates: List<String>): String {
    if (weekDates.isEmpty()) return ""
    val anchor = LocalDate.parse(weekDates.getOrElse(3) { weekDates.first() })
    return anchor.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
}

// MARK: - Day list

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalTvMaterial3Api::class)
@Composable
private fun DayList(
    state: org.siloserver.silo.viewmodel.CalendarUiState,
    listState: LazyListState,
    shelfFocusDay: String?,
    shelfFocusRequest: Int,
    onShelfFocusConsumed: () -> Unit,
    onItemFocused: (CalendarItem) -> Unit,
    onOpenItemDetail: (contentId: String) -> Unit,
) {
    val snapScope = rememberCoroutineScope()
    val onShelfFocused: (Int) -> Unit = { index ->
        snapScope.launch { listState.animateScrollToItem(index) }
    }
    // The day-snap is the ONLY vertical scroller: with the default spec the
    // focused card's own bring-into-view fought the snap (it re-scrolled to
    // give itself a gutter, dragging the previous day's caption tail back
    // under the week strip and pushing this shelf's captions past the fold —
    // QA 2026-07-08 screenshots). Horizontal rows re-enable the smooth spec
    // inside the shelf.
    CompositionLocalProvider(LocalBringIntoViewSpec provides NoVerticalBringIntoViewSpec) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .focusGroup(),
        contentPadding = PaddingValues(
            top = Spacing.sm,
            bottom = 28.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Render EVERY weekday so the week keeps its shape; event-less days get
        // a "Nothing scheduled" stub instead of being skipped.
        itemsIndexed(state.weekDates, key = { _, date -> "day-$date" }) { index, date ->
            DayShelf(
                date = date,
                isToday = date == state.today,
                items = state.itemsFor(date),
                focusRequest = if (date == shelfFocusDay) shelfFocusRequest else 0,
                onFocusApplied = onShelfFocusConsumed,
                onItemFocused = onItemFocused,
                // Snap the day whose shelf owns focus to the top of the list
                // (QA 2026-07-08: default bring-into-view revealed only the
                // focused CARD, stranding the previous day's caption strip
                // above and clipping the focused row at the fold).
                onShelfFocused = { onShelfFocused(index) },
                onOpenItemDetail = onOpenItemDetail,
            )
        }
    }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DayShelf(
    date: String,
    isToday: Boolean,
    items: List<CalendarItem>,
    focusRequest: Int,
    onFocusApplied: () -> Unit = {},
    onItemFocused: (CalendarItem) -> Unit,
    onShelfFocused: () -> Unit = {},
    onOpenItemDetail: (contentId: String) -> Unit,
) {
    val firstCardFocusRequester = remember { FocusRequester() }
    val rowState = rememberLazyListState()

    // A changing, non-zero focus token (from a week-strip day selection) kicks
    // focus to this shelf's first card, then reports back so the screen can
    // clear the token — otherwise a dispose/recompose of this shelf (LazyColumn
    // recycle/prefetch) restarts this effect with the still-non-zero token and
    // replays the focus grab, yanking the row to item 0. Scroll the row back to
    // item 0 first so the first card is composed and its FocusRequester attached
    // — otherwise, after the shelf has been scrolled horizontally, the first
    // card may be off-screen and the request is dropped.
    LaunchedEffect(focusRequest) {
        if (focusRequest > 0 && items.isNotEmpty()) {
            rowState.scrollToItem(0)
            runCatching { firstCardFocusRequester.requestFocus() }
            onFocusApplied()
        }
    }

    var shelfHasFocus by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { focusState ->
                val nowFocused = focusState.hasFocus
                if (nowFocused && !shelfHasFocus) onShelfFocused()
                shelfHasFocus = nowFocused
            },
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        DayHeader(date = date, isToday = isToday, muted = items.isEmpty())
        if (items.isEmpty()) {
            NothingScheduledRow()
        } else {
            CompositionLocalProvider(LocalBringIntoViewSpec provides TvSmoothBringIntoViewSpec) {
            LazyRow(
                state = rowState,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusGroup(),
                contentPadding = PaddingValues(
                    start = Spacing.safeArea,
                    end = Spacing.safeArea,
                    top = 8.dp,
                    bottom = 8.dp,
                ),
                horizontalArrangement = Arrangement.spacedBy(CalendarCardSpacing),
            ) {
                items(items, key = { "$date-${it.contentId}" }) { item ->
                    CalendarEventCard(
                        item = item,
                        // First card holds the focus requester so a week-strip
                        // day selection can hand focus down to this shelf.
                        focusRequester = if (item == items.first()) firstCardFocusRequester else null,
                        onFocused = { onItemFocused(item) },
                        onClick = { onOpenItemDetail(item.detailContentId) },
                    )
                }
            }
            }
        }
    }
}

@Composable
private fun DayHeader(date: String, isToday: Boolean, muted: Boolean) {
    val localDate = remember(date) { LocalDate.parse(date) }
    Text(
        text = if (isToday) {
            "Today"
        } else {
            localDate.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()))
        },
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.Bold,
        color = if (muted) Color.White.copy(alpha = 0.45f) else MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(start = Spacing.safeArea),
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NothingScheduledRow() {
    Row(
        modifier = Modifier.padding(start = Spacing.safeArea),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.CalendarMonth,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.32f),
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = "Nothing scheduled",
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.50f),
        )
    }
}

// MARK: - Event card

// tvOS CalendarEventCard parity: PORTRAIT poster with the caption below,
// badges/watched/time overlaid ON the poster. Sized down from the previous
// RowDimens tokens so a full day shelf (header + poster + 2-line caption)
// fits between the week strip and the fold (QA 2026-07-08).
/** Suppresses focus-driven vertical scrolling — the calendar's day-snap owns it. */
private val NoVerticalBringIntoViewSpec: BringIntoViewSpec = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

private val posterWidth = 120.dp
private val posterHeight = 180.dp
private val CalendarCardSpacing = 18.dp
private val posterShape = RoundedCornerShape(10.dp)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CalendarEventCard(
    item: CalendarItem,
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    LaunchedEffect(isFocused, item.contentId) {
        if (isFocused) onFocused()
    }

    // tvOS FocusableCalendarCard: the poster alone is the focus-lifted
    // button; the caption sits OUTSIDE it and brightens on focus.
    Column(
        modifier = Modifier
            .width(posterWidth)
            .alpha(if (item.watched) 0.65f else 1f),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Card(
            onClick = onClick,
            interactionSource = interactionSource,
            shape = CardDefaults.shape(shape = posterShape),
            scale = CardDefaults.scale(focusedScale = 1.06f),
            border = CardDefaults.border(
                focusedBorder = Border(BorderStroke(2.5.dp, Color.White), shape = posterShape),
            ),
            colors = CardDefaults.colors(containerColor = Color.White.copy(alpha = 0.06f)),
            modifier = Modifier
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .size(posterWidth, posterHeight),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                ThumbhashImage(
                    url = item.posterUrl,
                    thumbhash = item.posterThumbhash,
                    contentDescription = item.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )

                // Badge pills (top-leading) — tvOS CalendarBadgePill.
                val badges = item.badges.mapNotNull(::badgeLabel)
                if (badges.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        badges.forEach { label -> BadgePill(text = label) }
                    }
                }

                // Watched check (top-trailing).
                if (item.watched) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .size(17.dp)
                            .background(Color.White, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Watched",
                            tint = Color.Black,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                }

                // Air-time capsule (bottom-trailing) — only for REAL broadcast
                // times; date-only entries report midnight, which rendered as
                // a meaningless "00:00" on every card (QA 2026-07-08).
                item.airTime?.takeIf { it.isNotBlank() && it != "00:00" }?.let { airTime ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(100.dp))
                            .background(Color.Black.copy(alpha = 0.62f))
                            .padding(horizontal = 9.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = airTime,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                    }
                }
            }
        }

        // Caption below the poster (tvOS CalendarCardCaption): 2-line reserved
        // title + single subtitle line.
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.labelLarge,
                color = if (isFocused) Color.White else Color.White.copy(alpha = 0.85f),
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            cardSubtitle(item)?.let { subtitle ->
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.60f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun BadgePill(text: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(100.dp))
            .background(Color.White.copy(alpha = 0.92f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = Color.Black,
        )
    }
}

// MARK: - Empty / message state

private data class CalendarAction(val label: String, val onClick: () -> Unit)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CalendarMessage(title: String, subtitle: String, action: CalendarAction) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.safeArea),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.CalendarMonth,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.62f),
                modifier = Modifier.size(28.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.62f),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                onClick = action.onClick,
                shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(100.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.White.copy(alpha = 0.10f),
                    contentColor = Color.White,
                    focusedContainerColor = FocusedContainer,
                    focusedContentColor = FocusedContent,
                    pressedContainerColor = FocusedContainer,
                    pressedContentColor = FocusedContent,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
            ) {
                Box(
                    modifier = Modifier
                        .width(140.dp)
                        .padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = action.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

// MARK: - Formatting helpers

private fun badgeLabel(badge: String): String? = when (badge) {
    CalendarBadge.SeriesPremiere -> "SERIES PREMIERE"
    CalendarBadge.SeasonPremiere -> "NEW SEASON"
    CalendarBadge.Finale -> "FINALE"
    else -> null
}

/** "S5 · E14 · Title" for episodes, "Movie" for films — mirrors tvOS card caption. */
private fun cardSubtitle(item: CalendarItem): String? {
    val parts = mutableListOf<String>()
    if (item.isEpisode) {
        val season = item.seasonNumber
        val episode = item.episodeNumber
        if (season != null && episode != null) {
            parts.add("S$season · E$episode")
        }
        item.episodeTitle?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
    }
    if (item.type == CalendarItemType.Movie) {
        parts.add("Movie")
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun emptyTitle(filter: String): String = when (filter) {
    CalendarFilter.Following -> "Nothing from shows you follow"
    else -> "Nothing scheduled this week"
}

private fun emptyCopy(filter: String): String = when (filter) {
    CalendarFilter.Following -> "No upcoming releases this week from shows you watch, favorite, or watchlist."
    CalendarFilter.Trending -> "No trending releases this week."
    else -> "No movie releases or episode airings in this week."
}
