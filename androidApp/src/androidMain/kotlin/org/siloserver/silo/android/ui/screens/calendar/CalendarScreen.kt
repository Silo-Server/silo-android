package org.siloserver.silo.android.ui.screens.calendar

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.components.SiloTopBar
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.common.calendar.localDisplayAirTime
import org.siloserver.silo.model.calendar.CalendarBadge
import org.siloserver.silo.model.calendar.CalendarFilter
import org.siloserver.silo.model.calendar.CalendarItem
import org.siloserver.silo.model.personal.UserLibrary
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.viewmodel.CalendarViewModel
import org.siloserver.silo.android.ui.navigation.LocalBottomChromeInset
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// iOS phone token mirror (SiloTheme.swift, !os(tvOS) branch):
//   cornerRadius = 8, smallCornerRadius = 6
//   spacing = 12, padding = 16, smallPadding = 8, largePadding = 24, safePadding = 16
//   posterCardWidth = 120, posterCardHeight = 198
private val CornerRadius = 8.dp
private val Spacing = 12.dp
private val Padding = 16.dp
private val SmallPadding = 8.dp
private val LargePadding = 24.dp
private val SafePadding = 16.dp
private val PosterCardWidth = 120.dp
private val PosterCardHeight = 198.dp

/**
 * Calendar / upcoming screen. Phone-for-phone parity with the silo-apple
 * iOS CalendarView: a header row, a pinned filter + week-strip header, and
 * a vertical list of per-day shelves (one row per day, even empty days),
 * each a horizontal scroller of poster cards. Tapping a day chip selects it
 * and scrolls its shelf to the top.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CalendarScreen(
    onBackClick: () -> Unit,
    onItemClick: (String) -> Unit,
    viewModel: CalendarViewModel = koinViewModel(),
    showTopBar: Boolean = true,
    contentTopPadding: Dp = 0.dp,
) {
    val state by viewModel.uiState.collectAsState()

    // Library list for the dropdown — same source MainScreen uses for
    // media-mode capabilities (PersonalDataRepository.listUserLibraries).
    val personalDataRepository: PersonalDataRepository = koinInject()
    val libraries by produceState(initialValue = emptyList<UserLibrary>()) {
        value = when (val result = personalDataRepository.listUserLibraries()) {
            is ApiResult.Success -> result.data
            else -> emptyList()
        }
    }

    Scaffold(
        topBar = {
            if (showTopBar) {
                SiloTopBar(
                    title = "Calendar",
                    onBackClick = onBackClick,
                    actions = {
                        if (libraries.size > 1) {
                            LibraryDropdown(
                                libraries = libraries,
                                selectedLibraryId = state.libraryId,
                                onSelect = viewModel::setLibrary,
                            )
                        }
                    },
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background,
        // Embedded in the tab shell (showTopBar = false) the shared floating
        // header already accounts for the status bar via contentTopPadding;
        // letting Scaffold add its own status-bar inset doubles the top gap.
        contentWindowInsets = if (showTopBar) ScaffoldDefaults.contentWindowInsets else WindowInsets(0),
    ) { padding ->
        val listState = rememberLazyListState()

        // iOS scrolls the selected day's shelf to the top via ScrollViewReader.
        // Mirror with a keyed scroll-to-index against the day-header item keys.
        LaunchedEffect(state.selectedDay, state.weekStart) {
            if (state.selectedDay.isBlank()) return@LaunchedEffect
            val index = state.weekDates.indexOf(state.selectedDay)
            if (index >= 0) {
                // Header item index: each day owns one header item plus its shelf.
                listState.animateScrollToItem(if (index == 0) 0 else index + 1)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(top = contentTopPadding),
        ) {
            when {
                state.isLoading -> {
                    PinnedHeader(state, viewModel)
                    Box(Modifier.fillMaxSize())
                }

                state.error != null -> {
                    PinnedHeader(state, viewModel)
                    ErrorRow(
                        message = state.error ?: "Something went wrong",
                        onRetry = viewModel::load,
                    )
                }

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = LargePadding + LocalBottomChromeInset.current),
                ) {
                    // iOS pins this header (LazyVStack pinnedViews:[.sectionHeaders]).
                    stickyHeader(key = "calendar-header") {
                        PinnedHeader(state, viewModel)
                    }

                    if (!state.hasAnyItems) {
                        item(key = "empty") {
                            EmptyState(filter = state.filter)
                        }
                    } else {
                        state.weekDates.forEach { date ->
                            val dayItems = state.itemsFor(date)
                            item(key = "header-$date") {
                                DayShelf(
                                    heading = sectionHeading(date, today = state.today),
                                    items = dayItems,
                                    onItemClick = onItemClick,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Pinned filter bar + week strip block. iOS: smallPadding vertical, background fill. */
@Composable
private fun PinnedHeader(
    state: org.siloserver.silo.viewmodel.CalendarUiState,
    viewModel: CalendarViewModel,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            // Only bottom padding: the floating top bar already provides the top
            // breathing room, so a top gap here just stacked whitespace above the
            // filter capsule in portrait (Jim QA 2026-07-09).
            .padding(bottom = SmallPadding),
        verticalArrangement = Arrangement.spacedBy(SmallPadding),
    ) {
        CalendarFilterBar(
            selected = state.filter,
            onSelect = viewModel::setFilter,
            modifier = Modifier.padding(horizontal = SafePadding),
        )
        // Left-aligned month/year header line above the day strip, matching iOS
        // CalendarView (CalendarView.swift:105-113). Previously this lived inside
        // the week-strip Row where portrait squeezed it to a vertical stack.
        Text(
            text = monthLabel(state.weekDates),
            fontSize = 17.sp, // iOS monthLabel = 17 semibold
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = SafePadding),
        )
        CalendarWeekStrip(
            weekDates = state.weekDates,
            today = state.today,
            selectedDay = state.selectedDay,
            isCurrentWeek = state.isCurrentWeek,
            hasEvents = { state.itemsFor(it).isNotEmpty() },
            onSelectDay = viewModel::selectDay,
            onPrevWeek = viewModel::prevWeek,
            onNextWeek = viewModel::nextWeek,
            onToday = viewModel::goToToday,
        )
    }
}

// MARK: - Filter bar (segmented capsule control)

/**
 * Following / Trending / All capsule segmented control. Mirrors
 * CalendarFilterBar.swift (iOS phone): a translucent capsule container with
 * a hairline white stroke, each segment a capsule that fills near-opaque
 * onSurface when selected with inverted (background-colored) text.
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
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), CircleShape)
            .padding(3.dp), // iOS containerPadding (phone) = 3
        horizontalArrangement = Arrangement.spacedBy(2.dp), // iOS segmentSpacing = 2
    ) {
        presets.forEach { (value, label) ->
            val isSelected = value == selected ||
                (value == CalendarFilter.Everything &&
                    (selected == CalendarFilter.All || selected == CalendarFilter.Everything))
            Box(
                modifier = Modifier
                    .height(28.dp) // iOS segmentHeight = 28
                    .clip(CircleShape)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f)
                        else Color.Transparent,
                    )
                    .clickable { onSelect(value) }
                    .padding(horizontal = 14.dp), // iOS segmentHorizontalPadding = 14
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp, // iOS segmentFont = 13 semibold
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    color = if (isSelected) MaterialTheme.colorScheme.background
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
        }
    }
}

// MARK: - Week strip

/**
 * Prev/next chevrons around seven day buttons, a Today shortcut when off the
 * current week, and a right-aligned month label. Mirrors CalendarWeekStrip.swift
 * (iOS phone metrics).
 */
@Composable
private fun CalendarWeekStrip(
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
            .padding(horizontal = SafePadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp), // iOS stripSpacing = 8
    ) {
        ChevronButton(
            icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            contentDescription = "Previous week",
            onClick = onPrevWeek,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { // iOS dayButtonSpacing = 4
            weekDates.forEach { date ->
                DayButton(
                    date = date,
                    isSelected = date == selectedDay,
                    isToday = date == today,
                    hasEvents = hasEvents(date),
                    onClick = { onSelectDay(date) },
                )
            }
        }

        ChevronButton(
            icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "Next week",
            onClick = onNextWeek,
        )
        // Month label intentionally NOT here — it lives as a left-aligned
        // header line above the strip (see PinnedHeader), matching iOS
        // CalendarView. Kept in this Row it was squeezed to ~0 width in
        // portrait (chevrons + 7 day cells overflow) and stacked vertically.

        if (!isCurrentWeek) {
            Box(
                modifier = Modifier
                    .height(32.dp) // iOS chevronHeight = 32
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.08f))
                    .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                    .clickable(onClick = onToday)
                    .padding(horizontal = 12.dp), // iOS todayHorizontalPadding = 12
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Today",
                    fontSize = 13.sp, // iOS todayFont = 13 semibold
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

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
            .size(32.dp) // iOS chevronWidth/Height = 32
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.08f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun DayButton(
    date: String,
    isSelected: Boolean,
    isToday: Boolean,
    hasEvents: Boolean,
    onClick: () -> Unit,
) {
    val localDate = remember(date) { LocalDate.parse(date) }
    // iOS CalendarDayButton: inverted == selected (focus is tvOS-only).
    val inverted = isSelected
    val backgroundFill = when {
        isSelected -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.88f)
        else -> Color.White.copy(alpha = 0.05f)
    }
    val primaryColor =
        if (inverted) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface
    val secondaryColor =
        if (inverted) MaterialTheme.colorScheme.background.copy(alpha = 0.7f)
        else MaterialTheme.colorScheme.onSurfaceVariant
    val dotColor =
        if (inverted) MaterialTheme.colorScheme.background
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
    val showTodayStroke = isToday && !isSelected

    Column(
        modifier = Modifier
            .width(42.dp) // iOS buttonWidth = 42
            .height(56.dp) // iOS buttonHeight = 56
            .clip(RoundedCornerShape(CornerRadius)) // iOS cornerRadius = 8
            .background(backgroundFill)
            .then(
                if (showTodayStroke) {
                    Modifier.border(
                        1.dp,
                        Color.White.copy(alpha = 0.35f),
                        RoundedCornerShape(CornerRadius),
                    )
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = localDate.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())),
            fontSize = 10.sp, // iOS weekdayFont = 10 semibold
            fontWeight = FontWeight.SemiBold,
            color = secondaryColor,
        )
        Spacer(Modifier.height(3.dp)) // iOS labelSpacing = 3
        Text(
            text = localDate.dayOfMonth.toString(),
            fontSize = 15.sp, // iOS numberFont = 15 bold
            fontWeight = FontWeight.Bold,
            color = primaryColor,
        )
        Spacer(Modifier.height(3.dp))
        Box(
            modifier = Modifier
                .size(4.dp) // iOS dotSize = 4
                .clip(CircleShape)
                .background(if (hasEvents) dotColor else Color.Transparent),
        )
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
                    imageVector = androidx.compose.material.icons.Icons.Filled.Star,
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
            LazyRow(
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
    Column(
        modifier = Modifier
            .width(PosterCardWidth)
            .clickable(onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp), // iOS VStack spacing = 4
    ) {
        Box(
            modifier = Modifier
                .width(PosterCardWidth)
                .height(PosterCardHeight)
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
                        fontSize = 10.sp, // iOS timeFontSize = 10 semibold
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                }
            }
        }

        // Caption: two-line title reserved + context subtitle.
        Text(
            text = item.title,
            fontSize = 14.sp, // iOS siloSubheadline = 14 bold
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
            minLines = 2, // iOS lineLimit(2, reservesSpace: true)
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
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

/** Monochrome editorial badge pill — onSurface fill, background-colored text. */
@Composable
private fun BadgePill(label: String) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.92f))
            .padding(horizontal = 6.dp, vertical = 2.dp), // iOS badge pill padding (phone)
    ) {
        Text(
            text = label,
            fontSize = 8.sp, // iOS badge fontSize = 8 bold
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp, // iOS tracking 0.8
            maxLines = 1,
            color = MaterialTheme.colorScheme.background,
        )
    }
}

// MARK: - Empty state

@Composable
private fun EmptyState(filter: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = LargePadding, bottom = LargePadding)
            .padding(horizontal = LargePadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = androidx.compose.material.icons.Icons.Filled.DateRange,
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
            fontSize = 14.sp, // iOS siloSubheadline
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = emptySubtitle(filter),
            fontSize = 12.sp, // iOS siloCaption
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun ErrorRow(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(LargePadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = message,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}

// MARK: - Library dropdown

@Composable
private fun LibraryDropdown(
    libraries: List<UserLibrary>,
    selectedLibraryId: Int?,
    onSelect: (Int?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(libraries.firstOrNull { it.id == selectedLibraryId }?.name ?: "All libraries")
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("All libraries") },
                onClick = {
                    expanded = false
                    onSelect(null)
                },
            )
            libraries.forEach { library ->
                DropdownMenuItem(
                    text = { Text(library.name) },
                    onClick = {
                        expanded = false
                        onSelect(library.id)
                    },
                )
            }
        }
    }
}

// MARK: - Helpers

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
