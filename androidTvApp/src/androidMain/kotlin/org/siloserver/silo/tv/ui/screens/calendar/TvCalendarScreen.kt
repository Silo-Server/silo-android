package org.siloserver.silo.tv.ui.screens.calendar

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import org.siloserver.silo.common.calendar.localDisplayAirTime
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
    onMoveUpToMenu: () -> Unit = {},
    focusRequest: Int = 0,
    onContentUpFallbackChanged: ((((Boolean) -> Boolean)?) -> Unit)? = null,
    viewModel: CalendarViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    // First focusable element is the segmented filter capsule so the D-pad
    // lands there when the Calendar tab swaps in; gate the jump so a silent
    // re-emission doesn't yank focus back after the user has navigated.
    val filterFocusRequesters = remember {
        mapOf(
            CalendarFilter.Following to FocusRequester(),
            CalendarFilter.Trending to FocusRequester(),
            CalendarFilter.All to FocusRequester(),
        )
    }
    val filterFocusRequester = filterFocusRequesters.getValue(CalendarFilter.Following)
    val selectedDayFocusRequester = remember { FocusRequester() }
    var lastAppliedFocusRequest by remember { mutableIntStateOf(-1) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Explicit shell-to-screen handoff. The shell bumps this token whenever
    // Calendar is selected, including re-selection after a restored route.
    // Target the active segment directly instead of asking the parent content
    // group to guess a descendant (which falls back to Home while navigating).
    LaunchedEffect(focusRequest, state.isLoading) {
        if (focusRequest == lastAppliedFocusRequest) return@LaunchedEffect
        if (state.isLoading) return@LaunchedEffect
        val layoutInfo = listState.layoutInfo
        val itemExtent = (layoutInfo.visibleItemsInfo.firstOrNull()?.size ?: 0) +
            layoutInfo.mainAxisItemSpacing
        val distance = listState.firstVisibleItemIndex.toFloat() * itemExtent +
            listState.firstVisibleItemScrollOffset
        if (distance > 0f) {
            val durationMs = (distance / 4f).toInt().coerceIn(250, 900)
            listState.animateScrollBy(
                -distance,
                tween(durationMs, easing = FastOutSlowInEasing),
            )
        }
        listState.scrollToItem(0)
        // Let the active loading/content branch attach its focus nodes before
        // applying the handoff. A request in the same frame as NavHost restore
        // is overwritten by Android's initial focus search.
        androidx.compose.runtime.withFrameNanos { }
        androidx.compose.runtime.withFrameNanos { }
        val requester = filterFocusRequesters[state.filter] ?: filterFocusRequester
        val applied = runCatching { requester.requestFocus() }.getOrDefault(false)
        if (applied) {
            // Keep the shell bar suppressed through Android's delayed initial
            // focus pass. Reconfirm the filter after that pass, then release
            // suppression on the following frame.
            kotlinx.coroutines.delay(120)
            runCatching { requester.requestFocus() }
            androidx.compose.runtime.withFrameNanos { }
            lastAppliedFocusRequest = focusRequest
            onInitialContentFocus()
        }
    }
    // Focus hand-off for day selection: picking a day in the week strip scrolls
    // to that day's shelf and kicks focus onto its first card. Only the most
    // recently selected day sees a changing, non-zero token, so exactly one
    // shelf claims focus.
    val snapControlsToInitialPosition: () -> Unit = {
        scope.launch { listState.animateScrollToItem(0) }
    }
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

            val controls: @Composable () -> Unit = {
                CalendarControls(
                    selectedFilter = state.filter,
                    weekDates = state.weekDates,
                    today = state.today,
                    selectedDay = state.selectedDay,
                    isCurrentWeek = state.isCurrentWeek,
                    hasEvents = { date -> state.itemsFor(date).isNotEmpty() },
                    firstSegmentFocusRequester = filterFocusRequester,
                    segmentFocusRequesters = filterFocusRequesters,
                    selectedDayFocusRequester = selectedDayFocusRequester,
                    onControlFocused = snapControlsToInitialPosition,
                    includeTopInset = false,
                    onSelectFilter = viewModel::setFilter,
                    onSelectDay = { date ->
                        viewModel.selectDay(date)
                        val index = state.weekDates.indexOf(date)
                        if (index >= 0) {
                            scope.launch { listState.animateScrollToItem(index + 1) }
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

            // One persistent vertical surface, matching tvOS: the filters and
            // week strip are the first list item, so focusing a poster shelf
            // naturally pushes both controls upward instead of pinning them.
            CalendarList(
                onContentUpFallbackChanged = onContentUpFallbackChanged,
                onMoveUpToMenu = onMoveUpToMenu,
                state = state,
                listState = listState,
                controls = controls,
                onRefresh = viewModel::refresh,
                onShowEverything = { viewModel.setFilter(CalendarFilter.All) },
                selectedDayFocusRequester = selectedDayFocusRequester,
                shelfFocusDay = shelfFocusDay,
                shelfFocusRequest = shelfFocusRequest,
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

@Composable
private fun CalendarControls(
    selectedFilter: String,
    weekDates: List<String>,
    today: String,
    selectedDay: String,
    isCurrentWeek: Boolean,
    hasEvents: (String) -> Boolean,
    firstSegmentFocusRequester: FocusRequester,
    segmentFocusRequesters: Map<String, FocusRequester>,
    selectedDayFocusRequester: FocusRequester,
    onControlFocused: () -> Unit,
    includeTopInset: Boolean,
    onSelectFilter: (String) -> Unit,
    onSelectDay: (String) -> Unit,
    onPrevWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onToday: () -> Unit,
) {
    Column(
        modifier = Modifier
            // The controls live in a recyclable LazyColumn item. Give that
            // item a stable minimum measure (filter capsule + spacing + 48dp
            // week strip + bottom inset), otherwise restoring item zero after
            // browsing shelves can reuse a clipped one-line measurement.
            .heightIn(min = 108.dp)
            .padding(
                start = Spacing.safeArea,
                end = Spacing.safeArea,
                top = if (includeTopInset) TvTopMenuLayout.contentTopInset else 0.dp,
                bottom = 0.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        CalendarControlRow(
            selectedFilter = selectedFilter,
            onSelectFilter = onSelectFilter,
            firstSegmentFocusRequester = firstSegmentFocusRequester,
            segmentFocusRequesters = segmentFocusRequesters,
            onControlFocused = onControlFocused,
        )

        WeekStrip(
            weekDates = weekDates,
            today = today,
            selectedDay = selectedDay,
            isCurrentWeek = isCurrentWeek,
            hasEvents = hasEvents,
            selectedDayFocusRequester = selectedDayFocusRequester,
            onControlFocused = onControlFocused,
            onSelectDay = onSelectDay,
            onPrevWeek = onPrevWeek,
            onNextWeek = onNextWeek,
            onToday = onToday,
        )
    }
}

// MARK: - Filter bar (segmented capsule)

@Composable
private fun CalendarControlRow(
    selectedFilter: String,
    onSelectFilter: (String) -> Unit,
    firstSegmentFocusRequester: FocusRequester,
    segmentFocusRequesters: Map<String, FocusRequester>,
    onControlFocused: () -> Unit,
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
            segmentFocusRequesters = segmentFocusRequesters,
            onControlFocused = onControlFocused,
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
    segmentFocusRequesters: Map<String, FocusRequester>,
    onControlFocused: () -> Unit,
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
                focusRequester = segmentFocusRequesters[value]
                    ?: if (index == 0) firstSegmentFocusRequester else null,
                onFocused = onControlFocused,
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
    onFocused: () -> Unit,
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
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { if (it.isFocused) onFocused() },
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
    selectedDayFocusRequester: FocusRequester,
    onControlFocused: () -> Unit,
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
                    focusRequester = if (date == selectedDay) selectedDayFocusRequester else null,
                    onFocused = onControlFocused,
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
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = 15.5.sp,
                lineHeight = 18.5.sp,
            ),
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
    focusRequester: FocusRequester?,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    val localDate = remember(date) { LocalDate.parse(date) }
    // tvOS CalendarDayButton is 84x96 pt with 18/26 pt type and an 8 pt
    // event dot. Type is floored at 14/16sp for 10-ft legibility, and the
    // cell grows past half scale (52x60) to hold it — audit 2026-07-20.
    val shape = RoundedCornerShape(6.dp)
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
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .size(width = 52.dp, height = 60.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
        ) {
            Text(
                text = localDate.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())),
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                ),
                color = if (inverted) {
                    (if (isFocused) FocusedContent else DarkOnPrimary).copy(alpha = 0.7f)
                } else {
                    Color.White.copy(alpha = 0.75f)
                },
            )
            Text(
                text = localDate.dayOfMonth.toString(),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
                ),
                fontWeight = FontWeight.Bold,
            )
            Box(
                modifier = Modifier
                    .size(4.dp)
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

internal fun shouldReturnCalendarFocusToControls(
    focusedShelfIndex: Int?,
    firstFocusableShelfIndex: Int,
    isReturningToControls: Boolean,
): Boolean = focusedShelfIndex != null &&
    focusedShelfIndex == firstFocusableShelfIndex &&
    !isReturningToControls

internal enum class CalendarUpFallbackAction {
    EnterMenu,
    ReturnToControls,
    StayInContent,
    MoveWithinContent,
}

internal fun calendarUpFallbackAction(
    focusedShelfIndex: Int?,
    firstFocusableShelfIndex: Int,
    isReturningToControls: Boolean,
    isRepeat: Boolean = false,
): CalendarUpFallbackAction = when {
    shouldReturnCalendarFocusToControls(
        focusedShelfIndex = focusedShelfIndex,
        firstFocusableShelfIndex = firstFocusableShelfIndex,
        isReturningToControls = isReturningToControls,
    ) -> if (isRepeat) CalendarUpFallbackAction.StayInContent else CalendarUpFallbackAction.ReturnToControls
    focusedShelfIndex == null && isRepeat -> CalendarUpFallbackAction.StayInContent
    focusedShelfIndex == null -> CalendarUpFallbackAction.EnterMenu
    else -> CalendarUpFallbackAction.MoveWithinContent
}

// MARK: - Day list

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, ExperimentalTvMaterial3Api::class)
@Composable
private fun CalendarList(
    onContentUpFallbackChanged: ((((Boolean) -> Boolean)?) -> Unit)? = null,
    onMoveUpToMenu: () -> Unit = {},
    state: org.siloserver.silo.viewmodel.CalendarUiState,
    listState: LazyListState,
    controls: @Composable () -> Unit,
    onRefresh: () -> Unit,
    onShowEverything: () -> Unit,
    selectedDayFocusRequester: FocusRequester,
    shelfFocusDay: String?,
    shelfFocusRequest: Int,
    onShelfFocusConsumed: () -> Unit,
    onItemFocused: (CalendarItem) -> Unit,
    onOpenItemDetail: (contentId: String) -> Unit,
) {
    val snapScope = rememberCoroutineScope()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var isReturningToControls by remember { mutableStateOf(false) }
    val firstFocusableDayIndex = state.weekDates.indexOfFirst { state.itemsFor(it).isNotEmpty() }
    val onShelfFocused: (Int) -> Unit = { index ->
        // Item zero is the filter/week control shell.
        if (!isReturningToControls) {
            snapScope.launch { listState.animateScrollToItem(index + 1) }
        }
    }
    val onMoveUpToControls: () -> Unit = {
        if (!isReturningToControls) {
            isReturningToControls = true
            snapScope.launch {
                // Claim the date; its on-focus callback owns the sole vertical
                // animation. Keeping one scroll authority avoids the small
                // hitch caused by focus bring-into-view and two list animations
                // all racing toward item zero.
                var claimed = runCatching {
                    selectedDayFocusRequester.requestFocus()
                }.getOrDefault(false)
                repeat(6) {
                    if (claimed) return@repeat
                    androidx.compose.runtime.withFrameNanos { }
                    claimed = runCatching { selectedDayFocusRequester.requestFocus() }.getOrDefault(false)
                    if (!claimed) kotlinx.coroutines.delay(40)
                }
                kotlinx.coroutines.delay(80)
                isReturningToControls = false
            }
        }
    }
    // Shell Up-fallback: the shell's content Box consumes DirectionUp in its
    // preview pass (ancestors tunnel first), so this list's own key handlers
    // can never see Up. Registering here routes Up from a focused day shelf
    // into the week-strip choreography instead of skipping to the menu bar;
    // when focus is already in the controls item, mirror the shell's default
    // (moveFocus within content; false -> shell hands off to the menu bar).
    var focusedShelfIndex by remember { mutableStateOf<Int?>(null) }
    val currentCalendarUpFallback = rememberUpdatedState<(Boolean) -> Boolean> { isRepeat ->
            when (calendarUpFallbackAction(
                    focusedShelfIndex = focusedShelfIndex,
                    firstFocusableShelfIndex = firstFocusableDayIndex,
                    isReturningToControls = isReturningToControls,
                    isRepeat = isRepeat,
                )
            ) {
                CalendarUpFallbackAction.EnterMenu -> {
                    onMoveUpToMenu()
                    true
                }
                CalendarUpFallbackAction.ReturnToControls -> {
                    onMoveUpToControls()
                    true
                }
                CalendarUpFallbackAction.StayInContent -> true
                CalendarUpFallbackAction.MoveWithinContent -> {
                    focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Up)
                }
            }
    }
    // Keep the registered identity stable while its implementation reads the
    // current loaded-day state. A new lambda keyed by firstFocusableDayIndex
    // would otherwise leave the shell holding the pre-load callback until this
    // screen disposes.
    val calendarUpFallbackRegistration: (Boolean) -> Boolean =
        remember { { isRepeat -> currentCalendarUpFallback.value(isRepeat) } }
    DisposableEffect(onContentUpFallbackChanged, calendarUpFallbackRegistration) {
        onContentUpFallbackChanged?.invoke(calendarUpFallbackRegistration)
        onDispose { onContentUpFallbackChanged?.invoke(calendarUpFallbackRegistration) }
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
            .padding(top = TvTopMenuLayout.contentTopInset)
            .focusGroup(),
        contentPadding = PaddingValues(
            top = Spacing.sm,
            bottom = 28.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "calendar-controls") {
            controls()
        }

        // Keep the control item in this same LazyColumn for every data state.
        // Loading/filter changes therefore never remove its focus subtree.
        when {
            state.isLoading -> item(key = "calendar-loading") {
                Box(modifier = Modifier.fillParentMaxHeight()) { TvLoadingScreen() }
            }
            state.error != null -> item(key = "calendar-error") {
                Box(modifier = Modifier.fillParentMaxHeight()) {
                    CalendarMessage(
                        title = state.error ?: "Failed to load calendar",
                        subtitle = "Press the week arrows to try another week.",
                        action = CalendarAction("Refresh", onRefresh),
                    )
                }
            }
            !state.hasAnyItems -> item(key = "calendar-empty") {
                Box(modifier = Modifier.fillParentMaxHeight()) {
                    CalendarMessage(
                        title = emptyTitle(state.filter),
                        subtitle = emptyCopy(state.filter),
                        action = if (state.filter != CalendarFilter.All) {
                            CalendarAction("Show Everything", onShowEverything)
                        } else {
                            CalendarAction("Refresh", onRefresh)
                        },
                        topAligned = true,
                    )
                }
            }
            else -> {
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
                onShelfFocusChanged = { focused ->
                    if (focused) {
                        focusedShelfIndex = index
                    } else if (focusedShelfIndex == index) {
                        focusedShelfIndex = null
                    }
                },
                onOpenItemDetail = onOpenItemDetail,
            )
        }
            }
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
    onShelfFocusChanged: (Boolean) -> Unit = {},
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
            // NOTE: DirectionUp is handled by the shell's content Up-fallback
            // (registered in CalendarList) — an ancestor's onPreviewKeyEvent
            // tunnels before any handler here could fire, so a local preview
            // handler for Up is dead code (see TvAlphabetRail's protocol note).
            .onFocusChanged { focusState ->
                val nowFocused = focusState.hasFocus
                if (nowFocused && !shelfHasFocus) onShelfFocused()
                if (nowFocused != shelfHasFocus) onShelfFocusChanged(nowFocused)
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
                    bottom = 4.dp,
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

private val posterWidth = 124.dp
private val posterHeight = 186.dp
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
                item.localDisplayAirTime()?.let { airTime ->
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
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 14.sp,
                                lineHeight = 18.sp,
                            ),
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                    }
                }
            }
        }

        // Caption below the poster: titles may wrap to two lines, but a
        // one-line title does not reserve an empty second line before detail.
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 17.5.sp,
                    lineHeight = 20.5.sp,
                ),
                fontWeight = FontWeight.SemiBold,
                color = if (isFocused) Color.White else Color.White.copy(alpha = 0.85f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            cardSubtitle(item)?.let { subtitle ->
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                    ),
                    color = Color.White.copy(alpha = 0.75f),
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
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 14.sp,
                lineHeight = 18.sp,
            ),
            fontWeight = FontWeight.Bold,
            color = Color.Black,
            maxLines = 1,
        )
    }
}

// MARK: - Empty / message state

private data class CalendarAction(val label: String, val onClick: () -> Unit)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CalendarMessage(
    title: String,
    subtitle: String,
    action: CalendarAction,
    topAligned: Boolean = false,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.safeArea),
        contentAlignment = if (topAligned) Alignment.TopCenter else Alignment.Center,
    ) {
        Column(
            modifier = if (topAligned) Modifier.padding(top = 32.dp) else Modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.CalendarMonth,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.30f),
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontSize = 15.5.sp,
                    lineHeight = 18.5.sp,
                ),
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 14.5.sp,
                    lineHeight = 17.5.sp,
                ),
                color = Color.White.copy(alpha = 0.62f),
            )
            Spacer(modifier = Modifier.height(2.dp))
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
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 15.5.sp,
                            lineHeight = 18.5.sp,
                        ),
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
