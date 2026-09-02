package org.siloserver.silo.tv.ui.screens.calendar

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
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
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.common.calendar.localDisplayAirTime
import org.siloserver.silo.common.cards.LocalCardPresentation
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.calendar.CalendarBadge
import org.siloserver.silo.model.calendar.CalendarFilter
import org.siloserver.silo.model.calendar.CalendarItem
import org.siloserver.silo.model.calendar.CalendarItemType
import org.siloserver.silo.tv.ui.components.LocalAmbientBackdropTint
import org.siloserver.silo.tv.ui.components.TvLoadingScreen
import org.siloserver.silo.tv.ui.components.TvRootHeroBackdrop
import org.siloserver.silo.tv.ui.components.rememberAmbientBackdropTintState
import org.siloserver.silo.tv.ui.focus.TvContentInitialFocusMaxAttempts
import org.siloserver.silo.tv.ui.focus.TvFocusAcquisitionBudgetMillis
import org.siloserver.silo.tv.ui.focus.TvFrameRelocationMaxAttempts
import org.siloserver.silo.tv.ui.focus.TvObservedFocusResult
import org.siloserver.silo.tv.ui.focus.TvReturnRelocation
import org.siloserver.silo.tv.ui.focus.TvReturnResolution
import org.siloserver.silo.tv.ui.focus.TvReturnSection
import org.siloserver.silo.tv.ui.focus.TvReturnTarget
import org.siloserver.silo.tv.ui.focus.TvReturnTargetSaver
import org.siloserver.silo.tv.ui.focus.claimFocusOrReport
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.tv.ui.focus.resolveTvReturnTarget
import org.siloserver.silo.tv.ui.shell.TvTopMenuLayout
import org.siloserver.silo.tv.ui.theme.DarkOnPrimary
import org.siloserver.silo.tv.ui.theme.FocusedContainer
import org.siloserver.silo.tv.ui.theme.FocusedContent
import org.siloserver.silo.tv.ui.theme.Spacing
import org.siloserver.silo.tv.ui.theme.TvSmoothBringIntoViewSpec
import org.siloserver.silo.tv.ui.theme.cardScaled
import org.siloserver.silo.viewmodel.CalendarViewModel

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
internal data class TvCalendarDetailTarget(
    val contentId: String,
    val seasonNumber: Int? = null,
    val episodeContentId: String? = null,
)

/** Carries an aired episode into the exact mode of the combined Series page. */
internal fun tvCalendarDetailTarget(item: CalendarItem): TvCalendarDetailTarget {
    val seriesContentId = item.seriesId?.trim()?.takeIf { it.isNotEmpty() }
    if (item.isEpisode && seriesContentId != null && item.seasonNumber != null) {
        return TvCalendarDetailTarget(
            contentId = seriesContentId,
            seasonNumber = item.seasonNumber,
            episodeContentId = item.contentId,
        )
    }

    return TvCalendarDetailTarget(contentId = item.contentId)
}

@OptIn(ExperimentalTvMaterial3Api::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
fun TvCalendarScreen(
    onOpenItemDetailSelection: (
        contentId: String,
        seasonNumber: Int?,
        episodeContentId: String?,
    ) -> Unit,
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
    var calendarFilterHasFocus by remember { mutableStateOf(false) }
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

    var shelfFocusDay by remember { mutableStateOf<String?>(null) }
    var shelfFocusRequest by remember { mutableIntStateOf(0) }
    var shelfFocusItemIndex by remember { mutableIntStateOf(0) }

    // Where the viewer was when they opened something. The day is the section
    // and the item's own contentId is its identity — NOT detailContentId, which
    // several episodes of one show share and which would therefore send focus
    // to whichever of them the week happens to list first.
    var returnTarget by rememberSaveable(stateSaver = TvReturnTargetSaver) {
        mutableStateOf<TvReturnTarget?>(null)
    }
    // Recorded by a CLICK and by nothing else, which is what makes a target
    // mean "I opened something and I am coming back".
    //
    // The pending flag is saveable and is the actual return signal. Inferring
    // it from "a target survived this composition" required Calendar to have
    // been disposed and recreated, which a fast Back during the outgoing
    // transition does not do — the target would then sit unconsumed and make
    // some later, ordinary tab re-selection look like a return instead.
    //
    // The flat surfaces also record on focus, and that is right for them: they
    // are pushed routes where Back is the only way in, so a saved target can
    // only have come from a trip. Calendar is a root tab. Browsing a card,
    // switching to Home, and selecting Calendar again would look identical to a
    // detail return, and restoring there would quietly defeat the shell's
    // documented reset to the controls. The cost is that process death while
    // merely browsing forgets the position — which is the lesser of the two.
    var returnPending by rememberSaveable { mutableStateOf(false) }
    // Bumped when the destination resumes, whether or not it was recreated.
    var resumeGeneration by remember { mutableIntStateOf(0) }
    CalendarResumeSignal { resumeGeneration++ }
    // What the restoration is waiting to see take focus, and what last did.
    // Confirmation is by identity: having called requestFocus() is not evidence
    // that anything received it.
    var focusedItemId by remember { mutableStateOf<String?>(null) }

    val recordReturnTarget: (String, CalendarItem, Int) -> Unit = { date, item, index ->
        returnPending = true
        returnTarget = TvReturnTarget(
            sectionId = date,
            itemId = item.contentId,
            sectionIndex = state.weekDates.indexOf(date).coerceAtLeast(0),
            itemIndex = index,
        )
    }

    // Explicit shell-to-screen handoff. The shell bumps this token whenever
    // Calendar is selected, including re-selection after a restored route.
    // Target the active segment directly instead of asking the parent content
    // group to guess a descendant (which falls back to Home while navigating).
    // A return owns entry. The shell bumps its token on every Calendar
    // selection, a Back out of item detail included, so without this the shell
    // handoff would scroll to the top and claim the controls while the
    // restoration was still working — two claimants, and the later one wins by
    // accident rather than by decision.
    // Keyed on state.days, not just the week's dates. A refresh keeps the same
    // seven dates while replacing everything inside them, so weekDates alone
    // could not tell a completed refresh from no change at all — and the effect
    // would never re-resolve.
    LaunchedEffect(resumeGeneration, state.isLoading, state.isRefreshing, state.days) {
        // isRefreshing as well as isLoading. Resolving mid-refresh answers
        // against cards the refresh is about to replace, and both
        // FollowAcrossSections and treatAbsenceAsFinal are claims about a FINAL
        // snapshot — applying them to a provisional one consumes the target on
        // an answer that was never authoritative.
        if (!returnPending || state.isLoading || state.isRefreshing) return@LaunchedEffect
        val sections = state.weekDates.map { date ->
            TvReturnSection(id = date, itemIds = state.itemsFor(date).map { it.contentId })
        }
        val located = resolveTvReturnTarget(
            target = returnTarget,
            sections = sections,
            // A calendar item can legitimately change day when an air date is
            // corrected. The viewer went to see that item, not that slot, so
            // following it is what they meant.
            relocation = TvReturnRelocation.FollowAcrossSections,
            // The week is fixed and nothing pages, so absence is already final
            // — there is no later arrival to wait for.
            treatAbsenceAsFinal = true,
        ) as? TvReturnResolution.Located
        if (located == null) {
            // A week that renders no cards has nothing to restore to, and the
            // shell handoff is the right answer for it — waiting for a later
            // load would hold entry open on a screen already showing the viewer
            // its empty or error state.
            returnTarget = null
            returnPending = false
            // Release the claim, exactly as the timeout path does. Waking the
            // shell effect is no use if it then finds the token already
            // applied and stands down again, which in the retained case leaves
            // the screen with nothing focused.
            lastAppliedFocusRequest = -1
            return@LaunchedEffect
        }
        // Claim the shell handoff before driving, so the effect below treats it
        // as already applied rather than racing this one.
        lastAppliedFocusRequest = focusRequest
        listState.scrollToItem(located.sectionIndex + CalendarShelfIndexOffset)
        // Let the shelf compose and attach before the token names it.
        androidx.compose.runtime.withFrameNanos { }
        shelfFocusItemIndex = located.itemIndex
        shelfFocusDay = located.sectionId
        shelfFocusRequest += 1

        // Wait to SEE the card take focus. requestFocus() can be dropped — an
        // unattached requester, a shelf still composing, a focus transaction
        // that rolls back — and every one of those returns without telling us.
        val landed = withTimeoutOrNull(TvFocusAcquisitionBudgetMillis) {
            // Settled on the target, not merely seen there. focusedItemId now
            // tracks CURRENT focus, but a snapshot of it is still only a moment
            // — focus traversal can pass through the target on its way
            // somewhere else, and a bare first { } would call that a landing.
            // Holding the value across a short window is what distinguishes
            // arriving from passing by.
            snapshotFlow { focusedItemId }
                .transformLatest { id ->
                    if (id == located.itemId) {
                        delay(TvCalendarReturnSettleMillis)
                        emit(Unit)
                    }
                }
                .first()
        } != null

        returnTarget = null
        returnPending = false
        if (landed) {
            // Only now. Reporting the handoff on having ASKED would leave the
            // shell believing content owns focus while nothing does, and the
            // top menu suppressed behind it.
            onInitialContentFocus()
        } else {
            // Give entry back. Releasing the claim re-arms the shell effect,
            // which returnPending has just re-keyed.
            lastAppliedFocusRequest = -1
        }
    }

    // returnPending is a key, not just a condition. Standing aside is only safe
    // if standing down wakes this again: a return that resolves to nothing —
    // the week failed to load, or came back empty — would otherwise leave the
    // screen with no claimant at all, because this effect had already run and
    // returned early. When the restoration does drive, it claims the token
    // first, so the re-run stops at the line above instead.
    LaunchedEffect(focusRequest, state.isLoading, returnPending) {
        if (focusRequest == lastAppliedFocusRequest) return@LaunchedEffect
        if (state.isLoading) return@LaunchedEffect
        if (returnPending) return@LaunchedEffect
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
        val applied = requestFocusUntilObserved(
            maxAttempts = TvFrameRelocationMaxAttempts,
            awaitAttempt = { androidx.compose.runtime.withFrameNanos { } },
            requestFocus = requester::requestFocus,
            isFocused = { calendarFilterHasFocus },
        ) == TvObservedFocusResult.Focused
        if (applied) {
            // Keep the shell bar suppressed through Android's delayed initial
            // focus pass. Reconfirm the filter after that pass, then release
            // suppression on the following frame. The reconfirm is a second
            // claim against the same target, so it is observed too.
            kotlinx.coroutines.delay(120)
            requestFocusUntilObserved(
                maxAttempts = TvFrameRelocationMaxAttempts,
                awaitAttempt = { androidx.compose.runtime.withFrameNanos { } },
                requestFocus = requester::requestFocus,
                isFocused = { calendarFilterHasFocus },
            )
            androidx.compose.runtime.withFrameNanos { }
            if (lastAppliedFocusRequest != focusRequest) {
                lastAppliedFocusRequest = focusRequest
                onInitialContentFocus()
            }
        }
    }
    // Focus hand-off for day selection: picking a day in the week strip scrolls
    // to that day's shelf and kicks focus onto its first card. Only the most
    // recently selected day sees a changing, non-zero token, so exactly one
    // shelf claims focus.
    val snapControlsToInitialPosition: () -> Unit = {
        scope.launch { listState.animateScrollToItem(0) }
    }
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
            modifier = Modifier
                .fillMaxSize()
                // The zone callbacks only fire when a calendar control or shelf
                // GAINS focus, so moving Up from the filter into the top menu
                // left the flag true with focus outside the screen entirely.
                // A later shell handoff then read that stale true, skipped both
                // claims, and still reported initial content focus — and only
                // another calendar zone gaining focus could clear it, which the
                // skipped handoff could never cause.
                .onFocusChanged { if (!it.hasFocus) calendarFilterHasFocus = false },
        ) {
            TvRootHeroBackdrop(
                content = null,
                emptyWashColor = Color(0xFF14365A),
                modifier = Modifier.fillMaxSize(),
            )

            val controls: @Composable ((CalendarControlFocusZone) -> Unit) -> Unit = { onControlFocused ->
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
                    onControlFocused = onControlFocused,
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
                            // Explicitly zero. Relying on the consume callback
                            // to have reset it makes this depend on the
                            // previous request having run to completion — and a
                            // shelf effect cancelled while its scroll suspends
                            // never reaches that reset, leaving a day selection
                            // to inherit the restoration's card index.
                            shelfFocusItemIndex = 0
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
                activeFilterFocusRequester = filterFocusRequesters[state.filter] ?: filterFocusRequester,
                onControlFocused = { zone ->
                    // The flag this screen's filter claim is observed on. It was
                    // declared and never assigned, so it read false forever: the
                    // claim burned every attempt and reported Exhausted even when
                    // focus had landed, which meant the reconfirm after Android's
                    // delayed focus pass, the bar-suppression release and
                    // onInitialContentFocus() never ran.
                    calendarFilterHasFocus = zone == CalendarControlFocusZone.Filter
                    if (zone != null) snapControlsToInitialPosition()
                },
                onFocusRequestAcknowledged = {
                    if (lastAppliedFocusRequest != focusRequest) {
                        lastAppliedFocusRequest = focusRequest
                        onInitialContentFocus()
                    }
                },
                onRefresh = viewModel::refresh,
                onShowEverything = { viewModel.setFilter(CalendarFilter.All) },
                selectedDayFocusRequester = selectedDayFocusRequester,
                shelfFocusDay = shelfFocusDay,
                shelfFocusRequest = shelfFocusRequest,
                shelfFocusItemIndex = shelfFocusItemIndex,
                onShelfFocusConsumed = {
                    shelfFocusDay = null
                    shelfFocusRequest = 0
                    shelfFocusItemIndex = 0
                },
                onItemFocused = { _, item, _, focused ->
                    if (focused) {
                        tintState.set(null, item.posterUrl)
                        focusedItemId = item.contentId
                    } else if (focusedItemId == item.contentId) {
                        // Only if this card is still the one on record. A gain
                        // elsewhere lands before this loss arrives, and an
                        // unconditional clear would wipe the new position.
                        focusedItemId = null
                    }
                },
                onItemClicked = { date, item, index ->
                    recordReturnTarget(date, item, index)
                },
                onOpenItemDetailSelection = onOpenItemDetailSelection,
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
    onControlFocused: (CalendarControlFocusZone) -> Unit,
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
    onControlFocused: (CalendarControlFocusZone) -> Unit,
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
    onControlFocused: (CalendarControlFocusZone) -> Unit,
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
                onFocused = { onControlFocused(CalendarControlFocusZone.Filter) },
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
    onControlFocused: (CalendarControlFocusZone) -> Unit,
    onSelectDay: (String) -> Unit,
    onPrevWeek: () -> Unit,
    onNextWeek: () -> Unit,
    onToday: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val monthYearFormatter = remember(locale) {
        DateTimeFormatter.ofPattern("MMMM yyyy", locale)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusGroup(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        ChevronButton(
            arrow = NavArrow.Prev,
            onClick = onPrevWeek,
            onFocused = { onControlFocused(CalendarControlFocusZone.WeekStrip) },
        )
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
                    onFocused = { onControlFocused(CalendarControlFocusZone.WeekStrip) },
                    onClick = { onSelectDay(date) },
                )
            }
        }
        ChevronButton(
            arrow = NavArrow.Next,
            onClick = onNextWeek,
            onFocused = { onControlFocused(CalendarControlFocusZone.WeekStrip) },
        )
        if (!isCurrentWeek) {
            TodayButton(
                onClick = onToday,
                onFocused = { onControlFocused(CalendarControlFocusZone.WeekStrip) },
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        Text(
            text = monthYearLabel(weekDates, monthYearFormatter),
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
private fun ChevronButton(
    arrow: NavArrow,
    onClick: () -> Unit,
    onFocused: () -> Unit,
) {
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
        modifier = Modifier
            .onFocusChanged { if (it.isFocused) onFocused() }
            .size(28.dp),
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
private fun TodayButton(onClick: () -> Unit, onFocused: () -> Unit) {
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
        modifier = Modifier.onFocusChanged { if (it.isFocused) onFocused() },
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
    val locale = LocalConfiguration.current.locales[0]
    val weekdayFormatter = remember(locale) {
        DateTimeFormatter.ofPattern("EEE", locale)
    }
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
                text = localDate.format(weekdayFormatter),
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
private fun monthYearLabel(
    weekDates: List<String>,
    formatter: DateTimeFormatter,
): String {
    if (weekDates.isEmpty()) return ""
    val anchor = LocalDate.parse(weekDates.getOrElse(3) { weekDates.first() })
    return anchor.format(formatter)
}

internal fun shouldReturnCalendarFocusToControls(
    focusedShelfIndex: Int?,
    firstFocusableShelfIndex: Int,
    isReturningToControls: Boolean,
): Boolean = focusedShelfIndex != null &&
    focusedShelfIndex == firstFocusableShelfIndex &&
    !isReturningToControls

internal enum class CalendarControlFocusZone { Filter, WeekStrip }

internal enum class CalendarUpFallbackAction {
    EnterMenu,
    FocusFilter,
    ReturnToControls,
    StayInContent,
    MoveWithinContent,
}

internal fun calendarUpFallbackAction(
    focusedShelfIndex: Int?,
    firstFocusableShelfIndex: Int,
    isReturningToControls: Boolean,
    focusedControlZone: CalendarControlFocusZone?,
    isRepeat: Boolean = false,
): CalendarUpFallbackAction = when {
    // A return to the controls is already in flight. It stays in flight until
    // the controls actually take focus, and for those frames the shelf still
    // reports its own index — so neither the null-index check below nor
    // shouldReturnCalendarFocusToControls (which goes false the instant the
    // return starts) can hold a held key. Without this, the same press that
    // began the handoff leaks straight into geometric movement.
    isRepeat && isReturningToControls -> CalendarUpFallbackAction.StayInContent
    shouldReturnCalendarFocusToControls(
        focusedShelfIndex = focusedShelfIndex,
        firstFocusableShelfIndex = firstFocusableShelfIndex,
        isReturningToControls = isReturningToControls,
    ) -> if (isRepeat) CalendarUpFallbackAction.StayInContent else CalendarUpFallbackAction.ReturnToControls
    focusedShelfIndex == null && isRepeat -> CalendarUpFallbackAction.StayInContent
    focusedControlZone == CalendarControlFocusZone.WeekStrip -> CalendarUpFallbackAction.FocusFilter
    focusedControlZone == CalendarControlFocusZone.Filter -> CalendarUpFallbackAction.EnterMenu
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
    controls: @Composable ((CalendarControlFocusZone) -> Unit) -> Unit,
    activeFilterFocusRequester: FocusRequester,
    /**
     * Which control zone holds focus, or null when the controls lose it.
     *
     * The zone matters: the screen's filter claim is observed on the filter row
     * specifically, and a caller that only hears "some control took focus"
     * cannot tell that apart from the week strip.
     */
    onControlFocused: (CalendarControlFocusZone?) -> Unit,
    onFocusRequestAcknowledged: () -> Unit,
    onRefresh: () -> Unit,
    onShowEverything: () -> Unit,
    selectedDayFocusRequester: FocusRequester,
    shelfFocusDay: String?,
    shelfFocusRequest: Int,
    shelfFocusItemIndex: Int,
    onShelfFocusConsumed: () -> Unit,
    onItemFocused: (date: String, item: CalendarItem, index: Int, focused: Boolean) -> Unit,
    onItemClicked: (date: String, item: CalendarItem, index: Int) -> Unit,
    onOpenItemDetailSelection: (
        contentId: String,
        seasonNumber: Int?,
        episodeContentId: String?,
    ) -> Unit,
) {
    val snapScope = rememberCoroutineScope()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    var selectedDayHasFocus by remember { mutableStateOf(false) }
    var isReturningToControls by remember { mutableStateOf(false) }
    var focusedControlZone by remember { mutableStateOf<CalendarControlFocusZone?>(null) }
    val clearControlFocusZone: () -> Unit = {
        focusedControlZone = null
        onControlFocused(null)
    }
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
                // The controls row is list item zero, and focusing a shelf
                // snaps that shelf to the top — which scrolls item zero out of
                // the composed window. A requester on an un-composed row is
                // not attached, so every claim below was refused and Up from
                // the first shelf looked dead. Bring it back first; the date's
                // on-focus scroll then finds nothing left to move.
                if (listState.layoutInfo.visibleItemsInfo.none { it.index == 0 }) {
                    listState.animateScrollToItem(0)
                    withFrameNanos { }
                }
                // Claim the date; its on-focus callback owns the sole vertical
                // animation. Keeping one scroll authority avoids the small
                // hitch caused by focus bring-into-view and two list animations
                // all racing toward item zero.
                // Was a hand-rolled six-attempt loop keyed on requestFocus()
                // returning true — acceptance, not arrival. The shared policy
                // does the same pacing and judges it on observed focus.
                requestFocusUntilObserved(
                    maxAttempts = TvContentInitialFocusMaxAttempts,
                    awaitAttempt = { withFrameNanos { } },
                    requestFocus = selectedDayFocusRequester::requestFocus,
                    isFocused = { selectedDayHasFocus },
                )
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
    val onCalendarControlFocused: (CalendarControlFocusZone) -> Unit = { zone ->
        focusedControlZone = zone
        onControlFocused(zone)
        onFocusRequestAcknowledged()
    }
    val currentCalendarUpFallback = rememberUpdatedState<(Boolean) -> Boolean> { isRepeat ->
            when (calendarUpFallbackAction(
                    focusedShelfIndex = focusedShelfIndex,
                    firstFocusableShelfIndex = firstFocusableDayIndex,
                    isReturningToControls = isReturningToControls,
                    focusedControlZone = focusedControlZone,
                    isRepeat = isRepeat,
                )
            ) {
                CalendarUpFallbackAction.EnterMenu -> {
                    onMoveUpToMenu()
                    true
                }
                CalendarUpFallbackAction.FocusFilter -> {
                    // Key handlers answer synchronously whether they consumed
                    // the press, so this cannot await arrival — but a claim that
                    // is refused must not vanish silently either.
                    activeFilterFocusRequester.claimFocusOrReport(
                        target = "calendar_filter",
                        action = "up_fallback",
                    )
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
            // Arrival for the shelf→controls Up hand-off is "the CONTROLS row
            // holds focus". This used to be observed on the whole list, which
            // is already true while a shelf card is focused — so the claim
            // returned "focused" without ever requesting, and Up from the first
            // shelf was a silent no-op that re-armed 80ms later.
            Box(modifier = Modifier.onFocusChanged { selectedDayHasFocus = it.hasFocus }) {
                controls(onCalendarControlFocused)
            }
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
                        onActionFocused = clearControlFocusZone,
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
                        onActionFocused = clearControlFocusZone,
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
                focusItemIndex = if (date == shelfFocusDay) shelfFocusItemIndex else 0,
                onFocusApplied = onShelfFocusConsumed,
                onItemFocusChanged = { item, itemIndex, focused ->
                    onItemFocused(date, item, itemIndex, focused)
                },
                onItemClicked = { item, itemIndex -> onItemClicked(date, item, itemIndex) },
                // Snap the day whose shelf owns focus to the top of the list
                // (QA 2026-07-08: default bring-into-view revealed only the
                // focused CARD, stranding the previous day's caption strip
                // above and clipping the focused row at the fold).
                onShelfFocused = { onShelfFocused(index) },
                onShelfFocusChanged = { focused ->
                    if (focused) {
                        focusedShelfIndex = index
                        clearControlFocusZone()
                    } else if (focusedShelfIndex == index) {
                        focusedShelfIndex = null
                    }
                },
                onOpenItemDetailSelection = onOpenItemDetailSelection,
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
    /**
     * Which card the token should land on. Zero for a week-strip day
     * selection, which means "this day" and nothing finer; the card actually
     * left behind when a return is being restored.
     */
    focusItemIndex: Int = 0,
    onFocusApplied: () -> Unit = {},
    onItemFocusChanged: (item: CalendarItem, index: Int, focused: Boolean) -> Unit,
    onItemClicked: (item: CalendarItem, index: Int) -> Unit = { _, _ -> },
    onShelfFocused: () -> Unit = {},
    onShelfFocusChanged: (Boolean) -> Unit = {},
    onOpenItemDetailSelection: (
        contentId: String,
        seasonNumber: Int?,
        episodeContentId: String?,
    ) -> Unit,
) {
    val targetCardFocusRequester = remember { FocusRequester() }
    val rowState = rememberLazyListState()

    // A changing, non-zero focus token (from a week-strip day selection) kicks
    // focus to this shelf's first card, then reports back so the screen can
    // clear the token — otherwise a dispose/recompose of this shelf (LazyColumn
    // recycle/prefetch) restarts this effect with the still-non-zero token and
    // replays the focus grab, yanking the row to item 0. Scroll the row back to
    // item 0 first so the first card is composed and its FocusRequester attached
    // — otherwise, after the shelf has been scrolled horizontally, the first
    // card may be off-screen and the request is dropped.
    val targetCardIndex = focusItemIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    var shelfHasFocus by remember { mutableStateOf(false) }

    LaunchedEffect(focusRequest) {
        if (focusRequest > 0 && items.isNotEmpty()) {
            rowState.scrollToItem(targetCardIndex)
            // onFocusApplied() retires the pending request. Retiring it after a
            // claim that was dropped loses the request entirely — nothing
            // focused and nothing left to retry it — so it now fires only on
            // observed acquisition. The shelf already reports its own focus.
            val landed = requestFocusUntilObserved(
                maxAttempts = TvContentInitialFocusMaxAttempts,
                awaitAttempt = { withFrameNanos { } },
                requestFocus = targetCardFocusRequester::requestFocus,
                isFocused = { shelfHasFocus },
            )
            if (landed == TvObservedFocusResult.Focused) onFocusApplied()
        }
    }
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
                itemsIndexed(items, key = { _, it -> "$date-${it.contentId}" }) { index, item ->
                    CalendarEventCard(
                        item = item,
                        // The card the pending token names holds the requester,
                        // so one mechanism serves both a week-strip day
                        // selection and a restored return.
                        focusRequester = targetCardFocusRequester.takeIf { index == targetCardIndex },
                        onFocusChanged = { focused -> onItemFocusChanged(item, index, focused) },
                        onClick = {
                            onItemClicked(item, index)
                            // Identity still comes from the event contentId,
                            // while the target carries its parent Series plus
                            // the exact season/episode mode to restore.
                            val target = tvCalendarDetailTarget(item)
                            onOpenItemDetailSelection(
                                target.contentId,
                                target.seasonNumber,
                                target.episodeContentId,
                            )
                        },
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
    val locale = LocalConfiguration.current.locales[0]
    val fullDateFormatter = remember(locale) {
        DateTimeFormatter.ofPattern("EEEE, MMMM d", locale)
    }
    Text(
        text = if (isToday) {
            "Today"
        } else {
            localDate.format(fullDateFormatter)
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

private val CalendarPosterWidth = 124.dp
private val CalendarPosterHeight = 186.dp
private val CalendarCardSpacing = 18.dp
private val posterShape = RoundedCornerShape(10.dp)

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CalendarEventCard(
    item: CalendarItem,
    focusRequester: FocusRequester?,
    onFocusChanged: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val posterWidth = CalendarPosterWidth.cardScaled()
    val posterHeight = CalendarPosterHeight.cardScaled()
    val caption = LocalCardPresentation.current.caption

    // Both edges. Gain alone made the screen's record of "what has focus"
    // sticky, so a card focus passed over on the way somewhere else still
    // looked like the current position long after focus had moved on.
    LaunchedEffect(isFocused, item.contentId) {
        onFocusChanged(isFocused)
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
        // Artwork-only drops the whole block; the outer spacedBy only applies
        // between children, so the poster keeps no trailing gap.
        if (caption.showsTitle) {
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
                if (caption.showsMetadata) {
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
    onActionFocused: () -> Unit,
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
                modifier = Modifier.onFocusChanged { if (it.isFocused) onActionFocused() },
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

/** The filter/week control shell occupies list slot zero, ahead of every day. */
private const val CalendarShelfIndexOffset: Int = 1

/**
 * Fires whenever this destination resumes — coming back from a detail route,
 * and also from the app being foregrounded.
 *
 * The signal has to be the lifecycle rather than composition identity: a Back
 * pressed during the outgoing transition can leave the destination composed,
 * and then "was I recreated?" answers a question nobody asked.
 */
@Composable
private fun CalendarResumeSignal(onResume: () -> Unit) {
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val currentOnResume by rememberUpdatedState(onResume)
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) currentOnResume()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/** Focus must hold the target this long to count as arrived rather than passing. */
private const val TvCalendarReturnSettleMillis: Long = 120L
