package org.siloserver.silo.android.ui.screens.calendar

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import org.siloserver.silo.android.ui.components.ErrorView
import org.siloserver.silo.android.ui.navigation.LocalBottomChromeInset
import org.siloserver.silo.common.calendar.localDisplayAirTime
import org.siloserver.silo.common.cards.LocalCardPresentation
import org.siloserver.silo.common.ui.components.DeferImagePresentationWhileScrolling
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.calendar.CalendarBadge
import org.siloserver.silo.model.calendar.CalendarFilter
import org.siloserver.silo.model.calendar.CalendarItem
import org.siloserver.silo.viewmodel.CalendarUiState
import org.siloserver.silo.viewmodel.CalendarViewModel
import org.koin.compose.viewmodel.koinViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// iOS SiloTheme tokens (phone).
private val CornerRadius = 8.dp
private val Spacing = 12.dp
private val Padding = 16.dp
private val SmallPadding = 8.dp
private val LargePadding = 24.dp
private val SafePadding = 16.dp
private val PosterCardWidth = 120.dp
private val PosterCardHeight = 198.dp

// Header card (iOS CalendarView.phoneWeekStrip).
private val CardCornerRadius = 26.dp
private val CardHorizontalPadding = 14.dp
private val CardVerticalPadding = 12.dp
private val CardInnerSpacing = 12.dp

private val CalendarSpring = spring<Dp>(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)

/**
 * Phone Calendar tab. Mirrors iOS `CalendarView` (phone):
 *
 * - One floating glass card is the only pinned element — month label, a
 *   "Today" pill when off the current week, the shared search/profile
 *   actions ([headerActions]), and the week strip. There is no separate
 *   title row; the card *is* the header.
 * - Everything else scrolls under the card: the Following / Trending / All
 *   filter bar first, then one shelf per day of the week (empty days too).
 * - Day taps and "Today" scroll that day's shelf up to the card; opening the
 *   tab does not auto-scroll.
 * - Pull to refresh.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onItemClick: (String) -> Unit,
    headerActions: @Composable RowScope.() -> Unit = {},
    viewModel: CalendarViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()
    val density = LocalDensity.current

    // The card floats over the agenda; its measured height is the top inset
    // the list scrolls under. Local blur source so the card can be glass.
    val haze = rememberHazeState()
    var cardHeightPx by remember { mutableIntStateOf(0) }
    val cardHeight = with(density) { cardHeightPx.toDp() }

    // Explicit scroll requests only (day tap / Today), never on first
    // composition — iOS opens at the top of the week. Keyed on weekDates and
    // on whether the shelves exist yet, so a request made while the week is
    // still loading is honoured once its content arrives.
    var scrollTarget by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(scrollTarget, state.weekDates, state.hasAnyItems) {
        val target = scrollTarget ?: return@LaunchedEffect
        val index = state.weekDates.indexOf(target)
        if (index >= 0 && state.hasAnyItems) {
            // Item 0 is the filter bar; the top content padding keeps the
            // shelf below the card.
            listState.animateScrollToItem(index + 1)
            scrollTarget = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val pullState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            state = pullState,
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(haze)
                .background(MaterialTheme.colorScheme.background),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pullState,
                    isRefreshing = state.isRefreshing,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = cardHeight),
                )
            },
        ) {
            if (cardHeightPx > 0) {
                DeferImagePresentationWhileScrolling(listState) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        top = cardHeight,
                        bottom = LargePadding + LocalBottomChromeInset.current,
                    ),
                ) {
                    // iOS: filter bar scrolls with the content, above the shelves.
                    item(key = "filter") {
                        CalendarFilterBar(
                            selected = state.filter,
                            onSelect = viewModel::setFilter,
                            modifier = Modifier.padding(
                                start = SafePadding,
                                end = SafePadding,
                                top = SmallPadding,
                                bottom = Padding,
                            ),
                        )
                    }
                    when {
                        state.error != null && !state.hasAnyItems -> item(key = "error") {
                            ErrorView(
                                message = state.error ?: "Something went wrong",
                                onRetry = viewModel::load,
                                modifier = Modifier.fillMaxWidth().padding(vertical = LargePadding),
                            )
                        }
                        state.isLoading && !state.hasAnyItems -> item(key = "loading") {
                            // iOS: deliberately blank while loading, no spinner.
                            Spacer(modifier = Modifier.height(320.dp))
                        }
                        !state.hasAnyItems -> item(key = "empty") {
                            EmptyState(
                                filter = state.filter,
                                onShowEverything = { viewModel.setFilter(CalendarFilter.Everything) },
                            )
                        }
                        else -> items(state.weekDates, key = { "day-$it" }) { date ->
                            DayShelf(
                                heading = sectionHeading(date, today = state.today),
                                items = state.itemsFor(date),
                                onItemClick = onItemClick,
                            )
                        }
                    }
                }
                }
            }
        }

        CalendarHeaderCard(
            state = state,
            hazeModifier = Modifier.hazeEffect(state = haze) {
                blurRadius = 20.dp
                noiseFactor = 0f
                // iOS Glass.regular on a dark canvas reads as a lifted grey;
                // a light wash over the blur gives the same lift here.
                tints = listOf(HazeTint(Color.White.copy(alpha = 0.06f)))
                fallbackTint = HazeTint(Color(0xFF161616).copy(alpha = 0.96f))
            },
            headerActions = headerActions,
            onSelectDay = { day ->
                viewModel.selectDay(day)
                scrollTarget = day
            },
            onPrevWeek = viewModel::prevWeek,
            onNextWeek = viewModel::nextWeek,
            onToday = {
                viewModel.goToToday()
                scrollTarget = state.today
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .onSizeChanged { cardHeightPx = it.height },
        )
    }
}

// MARK: - Header card

/**
 * The floating glass card: month label · Today · actions on the first row,
 * the week strip on the second. iOS: `siloGlass(in: RoundedRectangle(26))`
 * with a `white 0.08` hairline, h14/v12 inner padding, 12 spacing, and 16/8
 * outer margins under the status bar.
 */
@Composable
private fun CalendarHeaderCard(
    state: CalendarUiState,
    hazeModifier: Modifier,
    headerActions: @Composable RowScope.() -> Unit,
    onSelectDay: (String) -> Unit,
    onPrevWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onToday: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = SafePadding, vertical = SmallPadding),
    ) {
        val shape = RoundedCornerShape(CardCornerRadius)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .then(hazeModifier)
                .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
                .padding(horizontal = CardHorizontalPadding, vertical = CardVerticalPadding),
            verticalArrangement = Arrangement.spacedBy(CardInnerSpacing),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = monthLabel(state.weekDates),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!state.isCurrentWeek) {
                    TodayPill(onClick = onToday)
                }
                Spacer(modifier = Modifier.weight(1f))
                headerActions()
            }
            CalendarWeekStrip(
                weekDates = state.weekDates,
                today = state.today,
                selectedDay = state.selectedDay,
                eventCount = { state.itemsFor(it).size },
                onSelectDay = onSelectDay,
                onPrevWeek = onPrevWeek,
                onNextWeek = onNextWeek,
            )
        }
    }
}

/** iOS: 13 semibold, height 30, h-pad 12, glass capsule; a11y "Jump to today". */
@Composable
private fun TodayPill(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(30.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.10f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Today",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

// MARK: - Filter bar

/**
 * Following / Trending / All contained segmented control. iOS
 * `CalendarFilterBar` (phone): capsule container `white 0.07` + `white 0.10`
 * stroke, padding 4, spacing 4; segments 13 semibold, height 30, h-pad 16;
 * the selected capsule (`onSurface`, inverted text) slides between segments
 * with a spring.
 */
@Composable
private fun CalendarFilterBar(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val presets = listOf(
        CalendarFilter.Following to "Following",
        CalendarFilter.Trending to "Trending",
        CalendarFilter.Everything to "All",
    )
    val selectedIndex = presets.indexOfFirst { (value, _) ->
        value == selected ||
            (value == CalendarFilter.Everything &&
                (selected == CalendarFilter.All || selected == CalendarFilter.Everything))
    }.coerceAtLeast(0)

    // Segment geometry, measured so the pill can slide to the selected one.
    val density = LocalDensity.current
    val segmentX = remember { mutableStateOf(List(presets.size) { 0.dp }) }
    val segmentW = remember { mutableStateOf(List(presets.size) { 0.dp }) }
    val pillX by animateDpAsState(segmentX.value[selectedIndex], CalendarSpring, label = "filterPillX")
    val pillW by animateDpAsState(segmentW.value[selectedIndex], CalendarSpring, label = "filterPillW")

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape)
            .padding(4.dp),
    ) {
        if (pillW > 0.dp) {
            Box(
                modifier = Modifier
                    .offset(x = pillX)
                    .width(pillW)
                    .height(30.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            presets.forEachIndexed { index, (value, label) ->
                val isSelected = index == selectedIndex
                Box(
                    modifier = Modifier
                        .height(30.dp)
                        .clip(CircleShape)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(value) }
                        .onGloballyPositioned { coords ->
                            val x = with(density) { coords.positionInParent().x.toDp() }
                            val w = with(density) { coords.size.width.toDp() }
                            if (segmentX.value[index] != x) {
                                segmentX.value = segmentX.value.toMutableList().also { it[index] = x }
                            }
                            if (segmentW.value[index] != w) {
                                segmentW.value = segmentW.value.toMutableList().also { it[index] = w }
                            }
                        }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        color = if (isSelected) MaterialTheme.colorScheme.background
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

// MARK: - Week strip

/**
 * Prev/next chevrons around seven equal-width day cells that fill the card.
 * iOS `CalendarWeekStrip` (phone): HStack spacing 6, 30pt bordered chevron
 * discs, `CalendarRichDayCell`s with no background.
 */
@Composable
private fun CalendarWeekStrip(
    weekDates: List<String>,
    today: String,
    selectedDay: String,
    eventCount: (String) -> Int,
    onSelectDay: (String) -> Unit,
    onPrevWeek: () -> Unit,
    onNextWeek: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ChevronButton(
            icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = "Previous week",
            onClick = onPrevWeek,
        )
        Row(modifier = Modifier.weight(1f)) {
            weekDates.forEach { date ->
                DayCell(
                    date = date,
                    isSelected = date == selectedDay,
                    isToday = date == today,
                    eventCount = eventCount(date),
                    onClick = { onSelectDay(date) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        ChevronButton(
            icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "Next week",
            onClick = onNextWeek,
        )
    }
}

@Composable
private fun ChevronButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            modifier = Modifier.size(18.dp),
        )
    }
}

/**
 * iOS `CalendarRichDayCell`: weekday (11 semibold, secondary) over the day
 * number (15 bold) in a 34pt radius-11 box — filled `onSurface` when
 * selected, ringed `onSurface 0.45` @ 1.5 when today — over an event-count
 * capsule (10 bold, `white 0.10`) or a matching blank so rows stay aligned.
 */
@Composable
private fun DayCell(
    date: String,
    isSelected: Boolean,
    isToday: Boolean,
    eventCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val localDate = remember(date) { LocalDate.parse(date) }
    val numberShape = RoundedCornerShape(11.dp)
    Column(
        modifier = modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text(
            text = localDate.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(numberShape)
                .background(if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent)
                .then(
                    if (isToday && !isSelected) {
                        Modifier.border(1.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f), numberShape)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = localDate.dayOfMonth.toString(),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = if (isSelected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface,
            )
        }
        Box(
            modifier = Modifier
                .height(16.dp)
                .clip(CircleShape)
                .background(if (eventCount > 0) Color.White.copy(alpha = 0.10f) else Color.Transparent)
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (eventCount > 0) {
                Text(
                    text = eventCount.toString(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                    maxLines = 1,
                    // Android's default font padding drops the glyph below the
                    // optical centre of a 16dp capsule; trim it so the digit
                    // sits centred like the iOS text.
                    style = LocalTextStyle.current.copy(
                        lineHeight = 10.sp,
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                        lineHeightStyle = LineHeightStyle(
                            alignment = LineHeightStyle.Alignment.Center,
                            trim = LineHeightStyle.Trim.Both,
                        ),
                    ),
                )
            }
        }
    }
}

// MARK: - Day shelf

/**
 * One day's heading + horizontal poster shelf. Mirrors CalendarDayShelf.swift:
 * heading uses siloHeadline (16 semibold), dims to secondary text on empty
 * days; the shelf scrolls horizontally with `spacing`-gap poster cards; empty
 * days show a "Nothing scheduled" stub with a moon icon.
 */
@Composable
private fun DayShelf(
    heading: String,
    items: List<CalendarItem>,
    onItemClick: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = Padding), // iOS shelfBottomPadding (phone) = padding
        verticalArrangement = Arrangement.spacedBy(SmallPadding), // iOS rowVerticalSpacing = smallPadding
    ) {
        Text(
            text = heading,
            fontSize = 16.sp, // iOS siloHeadline = 16 semibold
            fontWeight = FontWeight.SemiBold,
            color = if (items.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = SafePadding),
        )

        if (items.isEmpty()) {
            Row(
                modifier = Modifier.padding(horizontal = SafePadding),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // iOS uses SF Symbol "moon.stars"; nearest Material equivalent.
                Icon(
                    imageVector = Icons.Outlined.Bedtime,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "Nothing scheduled",
                    fontSize = 12.sp, // iOS siloCaption = 12
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val rowState = rememberLazyListState()
            DeferImagePresentationWhileScrolling(rowState) {
            LazyRow(
                state = rowState,
                horizontalArrangement = Arrangement.spacedBy(Spacing), // iOS cardSpacing = spacing
                contentPadding = PaddingValues(horizontal = SafePadding),
            ) {
                items(items, key = { it.contentId }) { item ->
                    CalendarEventCard(item = item, onClick = { onItemClick(item.detailContentId) })
                }
            }
            }
        }
    }
}

// MARK: - Event card

/**
 * Poster card for a single calendar event. Mirrors CalendarEventCard.swift
 * (iOS phone): a 120x198 poster with a corner-radius clip, badge pills
 * (top-leading), watched check (top-trailing), and an air-time pill
 * (bottom-trailing); a two-line title + context subtitle caption below.
 */
@Composable
private fun CalendarEventCard(
    item: CalendarItem,
    onClick: () -> Unit,
) {
    // Poster-size preference scales the whole card; height keeps the 120:198
    // base ratio. The caption preference gates the title + subtitle block the
    // same way CalendarEventCard.swift does (showsTitle / showsMetadata).
    val cardPresentation = LocalCardPresentation.current
    val posterScale = cardPresentation.posterSize.posterScale
    Column(
        modifier = Modifier
            .width(PosterCardWidth * posterScale)
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp), // iOS VStack spacing = 4
    ) {
        Box(
            modifier = Modifier
                .width(PosterCardWidth * posterScale)
                .height(PosterCardHeight * posterScale)
                .clip(RoundedCornerShape(CornerRadius)),
        ) {
            ThumbhashImage(
                url = item.posterUrl,
                thumbhash = item.posterThumbhash,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
            )

            // Badge pills, top-leading. overlayPadding (phone) = 6.
            val badges = item.badges.mapNotNull(::badgeLabel)
            if (badges.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp), // iOS badgeSpacing = 4
                ) {
                    badges.forEach { BadgePill(it) }
                }
            }

            // Watched check, top-trailing.
            if (item.watched) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(20.dp) // iOS checkBadgeSize = 20
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Filled.Check,
                        contentDescription = "Watched",
                        tint = MaterialTheme.colorScheme.background,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }

            // Air-time pill, bottom-trailing.
            displayAirTime(item)?.let { time ->
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.62f))
                        .padding(horizontal = 6.dp, vertical = 2.dp), // iOS time pill padding
                ) {
                    Text(
                        text = time,
                        fontSize = 12.sp, // iOS timeFontSize = 10 semibold; 12sp phone readability floor
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                }
            }
        }

        // Caption: two-line title reserved + context subtitle.
        if (cardPresentation.caption.showsTitle) {
            Text(
                text = item.title,
                fontSize = 14.sp, // iOS siloSubheadline = 14 bold
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                minLines = 2, // iOS lineLimit(2, reservesSpace: true)
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (cardPresentation.caption.showsMetadata) {
                cardSubtitle(item)?.let { subtitle ->
                    Text(
                        text = subtitle,
                        fontSize = 12.sp, // iOS siloCaption = 12
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Monochrome editorial badge pill — onSurface fill, background-colored text. */
@Composable
private fun BadgePill(label: String) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f))
            .padding(horizontal = 7.dp, vertical = 3.dp), // iOS badge pill padding (phone), grown for 11sp text
    ) {
        Text(
            text = label,
            fontSize = 11.sp, // iOS badge fontSize = 8 bold; 11sp phone badge floor
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp, // iOS tracking 0.8
            maxLines = 1,
            color = MaterialTheme.colorScheme.background,
        )
    }
}

// MARK: - Empty state

/**
 * iOS empty state: 44pt calendar glyph at `onSurface 0.3`, subheadline title,
 * caption body, and a 220pt "Show Everything" primary button whenever the
 * filter is narrower than Everything.
 */
@Composable
private fun EmptyState(filter: String, onShowEverything: () -> Unit) {
    val isEverything = filter == CalendarFilter.Everything || filter == CalendarFilter.All
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = LargePadding, bottom = LargePadding)
            .padding(horizontal = LargePadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.EventBusy,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
            modifier = Modifier.size(44.dp),
        )
        Text(
            text = if (filter == CalendarFilter.Following) {
                "Nothing from shows you follow"
            } else {
                "Nothing scheduled this week"
            },
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = emptySubtitle(filter),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        if (!isEverything) {
            Button(
                onClick = onShowEverything,
                modifier = Modifier.width(220.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onSurface,
                    contentColor = MaterialTheme.colorScheme.background,
                ),
            ) {
                Text("Show Everything", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun badgeLabel(badge: String): String? = when (badge) {
    // iOS CalendarBadge labels (uppercased editorial).
    CalendarBadge.SeriesPremiere -> "SERIES PREMIERE"
    CalendarBadge.SeasonPremiere -> "NEW SEASON"
    CalendarBadge.Finale -> "FINALE"
    else -> null
}

/** iOS CalendarViewModel.sectionHeading: Today / Tomorrow / "Monday, June 9". */
private fun sectionHeading(date: String, today: String): String {
    val localDate = LocalDate.parse(date)
    if (today.isNotBlank()) {
        val todayDate = LocalDate.parse(today)
        if (localDate == todayDate) return "Today"
        if (localDate == todayDate.plusDays(1)) return "Tomorrow"
    }
    return localDate.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault()))
}

/** iOS monthLabel: month + year of the week's Thursday (startDate + 3). */
private fun monthLabel(weekDates: List<String>): String {
    val anchorStr = weekDates.getOrNull(3) ?: weekDates.firstOrNull() ?: return ""
    val anchor = LocalDate.parse(anchorStr)
    return anchor.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))
}

/** Local viewer time, preferring the server's absolute RFC3339 instant. */
private fun displayAirTime(item: CalendarItem): String? = item.localDisplayAirTime()

/**
 * iOS CalendarCardCaption.subtitle: "S5 · E14 · Title" for episodes,
 * "Season 2" for season premieres, "Movie" for movies, joined with " · ".
 */
private fun cardSubtitle(item: CalendarItem): String? {
    val parts = mutableListOf<String>()
    when (item.type) {
        "episode" -> {
            val season = item.seasonNumber
            val episode = item.episodeNumber
            if (season != null && episode != null) parts.add("S$season · E$episode")
            item.episodeTitle?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        }
        "season_premiere" -> {
            item.seasonNumber?.let { parts.add("Season $it") }
        }
        "movie" -> parts.add("Movie")
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}

private fun emptySubtitle(filter: String): String = when (filter) {
    CalendarFilter.Following ->
        "No upcoming releases this week from shows you watch, favorite, or watchlist."
    else -> "No movie releases or episode airings in this week."
}
