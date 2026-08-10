package org.siloserver.silo.tv.ui.screens.recommendations

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.siloserver.silo.tv.ui.focus.claimFocusOrReport
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.tv.ui.focus.TvObservedFocusResult
import org.siloserver.silo.tv.ui.focus.TvContentInitialFocusMaxAttempts
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.tv.ui.components.TvErrorScreen
import org.siloserver.silo.tv.ui.components.TvLoadingScreen
import org.siloserver.silo.tv.ui.components.TvMediaRow
import org.siloserver.silo.tv.ui.components.TvRowStyle
import org.siloserver.silo.tv.ui.components.TvHeroActionPill
import org.siloserver.silo.tv.ui.components.TvPillVariant
import org.siloserver.silo.tv.ui.screens.personal.TvFavoritesInline
import org.siloserver.silo.tv.ui.screens.personal.TvWatchlistInline
import org.siloserver.silo.tv.ui.shell.TvTopMenuLayout
import org.siloserver.silo.tv.ui.theme.Spacing
import org.siloserver.silo.tv.ui.focus.TvFrameRelocationMaxAttempts
import org.siloserver.silo.tv.ui.theme.TvSmoothBringIntoViewSpec
import org.siloserver.silo.tv.ui.util.visibleOnTv
import org.siloserver.silo.viewmodel.RecommendationsViewModel
import org.koin.compose.viewmodel.koinViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private const val TvForYouFocusTag = "TvForYouFocus"

private val RecommendationsFilterBandHeight = 52.dp

internal data class ForYouListPosition(
    val firstVisibleItemIndex: Int,
    val firstVisibleItemScrollOffset: Int,
) {
    val isAtTop: Boolean
        get() = firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0
}

internal suspend fun maintainForYouTopAnchor(
    positionEvents: Flow<ForYouListPosition>,
    isFirstRowFocused: () -> Boolean,
    awaitRelocation: suspend () -> Unit,
    currentPosition: () -> ForYouListPosition,
    scrollToTop: suspend () -> Unit,
) {
    positionEvents.collect { observed ->
        if (!isFirstRowFocused() || observed.isAtTop) return@collect
        awaitRelocation()
        if (isFirstRowFocused() && !currentPosition().isAtTop) scrollToTop()
    }
}

/**
 * "For You" tab. Reuses the shared [RecommendationsViewModel] that drives
 * the phone `/recommendations/discover` feed. Layout mirrors [TvHomeScreen]
 * (rows down the page) minus the featured hero — the discover API returns
 * section-style rows, not a hero card.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalTvMaterial3Api::class)
@Composable
fun TvRecommendationsScreen(
    onSavedListItemClick: (contentId: String) -> Unit,
    onRecommendationItemClick: (contentId: String) -> Unit,
    detailReturnFocusRequest: Int,
    detailReturnFocusPending: Boolean,
    detailReturnCardFocusRequester: FocusRequester,
    onDetailReturnFocusConsumed: (Int) -> Unit,
    onSolidTopBarChanged: (Boolean) -> Unit,
    onInitialContentFocus: () -> Unit = {},
    focusRequest: Int = 0,
    entryRequest: TvForYouEntryRequest = TvForYouEntryRequest(),
    viewModel: RecommendationsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val visibleSections = remember(state.sections) { state.sections.visibleOnTv() }
    val forYouFocusRequester = remember { FocusRequester() }
    val watchlistFocusRequester = remember { FocusRequester() }
    val favoritesFocusRequester = remember { FocusRequester() }
    val firstRecommendationRowFocusRequester = remember { FocusRequester() }
    val firstRecommendationCardFocusRequester = remember { FocusRequester() }
    val detailReturnRowFocusRequester = remember { FocusRequester() }
    val focusBridgeScope = rememberCoroutineScope()
    // rememberSaveable, not remember: opening an item disposes this screen's
    // composition, and a plain remember would re-initialise from
    // entryRequest.selection on the way back. Top-level For You entry carries
    // selection = null, so returning from a Watchlist item did not merely
    // forget the list — it actively reselected the recommendations feed.
    //
    // lastAppliedEntrySequence must survive with it. Resetting it to 0 makes
    // the LaunchedEffect below treat the unchanged entry request as new and
    // re-apply its selection, which reintroduces the same jump even once the
    // selection itself is saved.
    val recommendationsListState = rememberLazyListState()
    var savedListSelection by rememberSaveable { mutableStateOf(entryRequest.selection) }
    var lastAppliedEntrySequence by rememberSaveable { mutableIntStateOf(0) }
    // This is the card that launched the pending detail route, not a rolling
    // "currently focused" value. Keeping the launch snapshot separate prevents
    // the row-container hop from retargeting restoration to whichever composed
    // card temporarily receives focus while the exact card is being prepared.
    var detailReturnSectionId by rememberSaveable { mutableStateOf("") }
    var detailReturnContentId by rememberSaveable { mutableStateOf("") }
    var detailReturnRowIndex by rememberSaveable { mutableIntStateOf(0) }
    var detailReturnCardIndex by rememberSaveable { mutableIntStateOf(0) }
    val detailReturnLaunchTarget = if (
        detailReturnSectionId.isNotBlank() && detailReturnContentId.isNotBlank()
    ) {
        ForYouFocusTarget(
            sectionId = detailReturnSectionId,
            contentId = detailReturnContentId,
            rowIndex = detailReturnRowIndex,
            cardIndex = detailReturnCardIndex,
        )
    } else {
        null
    }
    val returnRows = remember(visibleSections) {
        visibleSections.map { section ->
            ForYouFocusRow(section.id, section.items.map { it.contentId })
        }
    }
    val detailReturnState = ForYouDetailReturnState(
        requestId = detailReturnFocusRequest,
        pending = detailReturnFocusPending,
    )
    val pendingReturnLocation = resolvePendingForYouReturnLocation(
        state = detailReturnState,
        launchTarget = detailReturnLaunchTarget,
        rows = returnRows,
    )
    // Whether the exact return card is currently composed and placed. A LazyRow
    // disposes items on ordinary viewport recycling, not only on genuine
    // removal, so disposal CLEARS this latch rather than setting a terminal
    // "gone" one — a card scrolled out mid-restore is retried, not abandoned.
    // Genuine removal is already handled upstream: the content id drops out of
    // `returnRows`, so `pendingReturnLocation` resolves elsewhere or to null.
    var attachedReturnLocation by remember { mutableStateOf<ForYouReturnFocusLocation?>(null) }
    val latestOnDetailReturnFocusConsumed by rememberUpdatedState(onDetailReturnFocusConsumed)
    var firstRecommendationRowFocused by remember { mutableStateOf(false) }
    // The first row still owns the established filter-to-feed bridge. When it
    // is also the detail return row, use the return requester so the LazyRow
    // has only one row-container requester attached at a time.
    val firstRowContainerFocusRequester = if (pendingReturnLocation?.rowIndex == 0) {
        detailReturnRowFocusRequester
    } else {
        firstRecommendationRowFocusRequester
    }
    val moveIntoRecommendations: () -> Boolean = {
        if (
            !shouldBridgeRecommendationsDown(
                showingRecommendations = savedListSelection == null,
                hasVisibleRecommendations = visibleSections.isNotEmpty(),
            )
        ) {
            false
        } else {
            focusBridgeScope.launch {
                requestRecommendationRowFocus(
                    requestRowContainer = {
                        firstRowContainerFocusRequester.claimFocusOrReport(
                            target = "recommendations_row",
                            action = "entry_container",
                        )
                    },
                    awaitFrame = { withFrameNanos { } },
                    requestFirstCard = {
                        firstRecommendationCardFocusRequester.claimFocusOrReport(
                            target = "recommendations_card",
                            action = "entry_first_card",
                        )
                    },
                )
            }
            true
        }
    }

    DisposableEffect(savedListSelection, onSolidTopBarChanged) {
        onSolidTopBarChanged(savedListSelection == null)
        onDispose { onSolidTopBarChanged(false) }
    }

    DisposableEffect(detailReturnFocusRequest, detailReturnFocusPending) {
        onDispose {
            if (detailReturnFocusPending) {
                latestOnDetailReturnFocusConsumed(detailReturnFocusRequest)
            }
        }
    }

    LaunchedEffect(entryRequest.sequence) {
        val applied = applyForYouEntryRequest(
            currentSelection = savedListSelection,
            lastAppliedSequence = lastAppliedEntrySequence,
            request = entryRequest,
        )
        savedListSelection = applied.selection
        lastAppliedEntrySequence = applied.lastAppliedSequence
        if (applied.appliedRequest) {
            requestForYouEntryFocus(
                selection = applied.selection,
                awaitFrame = { withFrameNanos { } },
                requestForYou = {
                    forYouFocusRequester.claimFocusOrReport(
                        target = "for_you_tab",
                        action = "entry",
                    )
                },
                requestWatchlist = {
                    watchlistFocusRequester.claimFocusOrReport(
                        target = "watchlist_tab",
                        action = "entry",
                    )
                },
                requestFavorites = {
                    favoritesFocusRequester.claimFocusOrReport(
                        target = "favorites_tab",
                        action = "entry",
                    )
                },
            )
        }
    }

    LaunchedEffect(
        detailReturnFocusRequest,
        detailReturnFocusPending,
        pendingReturnLocation,
        state.isLoading,
    ) {
        if (!detailReturnFocusPending || detailReturnFocusRequest == 0 || state.isLoading) {
            return@LaunchedEffect
        }
        val target = pendingReturnLocation
        if (target == null) {
            repeat(TvFrameRelocationMaxAttempts) {
                withFrameNanos { }
                when (requestFocusSafely { forYouFocusRequester.requestFocus() }) {
                    FocusRequestOutcome.Handled,
                    FocusRequestOutcome.Disposed -> {
                        latestOnDetailReturnFocusConsumed(detailReturnFocusRequest)
                        return@LaunchedEffect
                    }
                    FocusRequestOutcome.Rejected -> Unit
                }
            }
            latestOnDetailReturnFocusConsumed(detailReturnFocusRequest)
            return@LaunchedEffect
        }
        val rowVisible = recommendationsListState.layoutInfo.visibleItemsInfo
            .any { it.index == target.rowIndex }
        if (!rowVisible) recommendationsListState.scrollToItem(target.rowIndex)
        val result = requestPendingForYouReturnFocus(
            maxAttempts = TvFrameRelocationMaxAttempts,
            awaitFrame = { withFrameNanos { } },
            targetState = {
                if (attachedReturnLocation == target) {
                    ForYouReturnTargetState.Attached
                } else {
                    ForYouReturnTargetState.NotAttached
                }
            },
            requestRowContainer = {
                requestFocusSafely { detailReturnRowFocusRequester.requestFocus() }
            },
            awaitRowFrame = { withFrameNanos { } },
            requestCard = {
                requestFocusSafely { detailReturnCardFocusRequester.requestFocus() }
            },
        )
        if (result == ForYouReturnFocusResult.Exhausted) {
            // The card could not be reached, so focus has no owner at this
            // point. A single unchecked request here was the whole recovery,
            // and when it came back Rejected or Disposed nothing retried and
            // nothing logged - the screen simply stopped answering the D-pad.
            // requestFocusSafely converts the "not initialized" throw into a
            // value, so the failure was silent as well as unhandled.
            //
            // Retry across frames the way the no-target path above does, then
            // fall back through the filter row. Those pills are composed for
            // the lifetime of the screen, so one of them can always take focus
            // even when the feed has not settled.
            if (!claimForYouFallbackFocus(
                    attempts = TvFrameRelocationMaxAttempts,
                    awaitFrame = { withFrameNanos { } },
                    candidates = listOf(
                        { forYouFocusRequester.requestFocus() },
                        { watchlistFocusRequester.requestFocus() },
                        { favoritesFocusRequester.requestFocus() },
                    ),
                )
            ) {
                Log.w(TvForYouFocusTag, "detail return left For You without focus")
            }
        }
        latestOnDetailReturnFocusConsumed(detailReturnFocusRequest)
    }

    // Match tvOS: recommendations remain the landing content when available;
    // an empty successful response defaults to the inline Watchlist fallback.
    LaunchedEffect(state.isLoading, state.error, visibleSections) {
        if (!state.isLoading && state.error == null && visibleSections.isEmpty() && savedListSelection == null) {
            savedListSelection = SavedListSelection.Watchlist
        }
    }

    LaunchedEffect(firstRecommendationRowFocused) {
        if (!firstRecommendationRowFocused) return@LaunchedEffect
        fun currentPosition() = ForYouListPosition(
            firstVisibleItemIndex = recommendationsListState.firstVisibleItemIndex,
            firstVisibleItemScrollOffset = recommendationsListState.firstVisibleItemScrollOffset,
        )
        // Stay suspended while the list is correctly anchored. A delayed
        // focus relocation can still move it after an initially-top sample;
        // snapshotFlow observes that later displacement without polling.
        maintainForYouTopAnchor(
            positionEvents = snapshotFlow { currentPosition() }.distinctUntilChanged(),
            isFirstRowFocused = { firstRecommendationRowFocused },
            awaitRelocation = { kotlinx.coroutines.delay(80) },
            currentPosition = ::currentPosition,
            scrollToTop = { recommendationsListState.animateScrollToItem(0) },
        )
    }

    // The saved-list shortcuts are the stable first row in every state. Focus
    // Watchlist once per entry, matching tvOS, without letting later refreshes
    // pull focus away from the user's current position.
    // rememberSaveable for the same reason as the selection above: these guard
    // a once-per-entry focus grab, and as plain `remember` they reset when an
    // item detail disposes this composition. The effect then re-fires on the
    // way back and slams focus onto the Watchlist pill while the feed is still
    // scrolled where the viewer left it — which is the shell's documented
    // anti-pattern ("fired LaunchedEffects in each screen that imperatively
    // re-focused index 0 — defeating the restorer"). Saved, the grab stays a
    // genuine once-per-entry action and the shell's content restorer is left
    // to put focus back where it was.
    var forYouContentHasFocus by remember { mutableStateOf(false) }
    var initialFocusRequested by rememberSaveable { mutableStateOf(false) }
    var lastAppliedFocusRequest by rememberSaveable { mutableStateOf(-1) }
    LaunchedEffect(focusRequest) {
        if (initialFocusRequested && focusRequest == lastAppliedFocusRequest) return@LaunchedEffect
        // The fifth site where a shell handover was reported regardless of
        // whether the claim landed. onInitialContentFocus() tells the shell
        // content owns focus; saying so after a dropped claim leaves nothing
        // focused and the shell believing otherwise.
        val landed = requestFocusUntilObserved(
            maxAttempts = TvContentInitialFocusMaxAttempts,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = watchlistFocusRequester::requestFocus,
            isFocused = { forYouContentHasFocus },
        )
        if (landed == TvObservedFocusResult.Focused) onInitialContentFocus()
        initialFocusRequested = true
        lastAppliedFocusRequest = focusRequest
    }

    // TV has no pull-to-refresh, so ON_RESUME is the only quiet self-heal path.
    // The shared VM loads once in init and retains its state across tab swaps, so
    // an empty discover response otherwise leaves this tab a permanent dead end
    // until a profile switch/restart. If the feed is still the empty fallback
    // when the user returns (e.g. after watching and rating content), re-check.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val current = viewModel.uiState.value
                if (!current.isLoading && current.sections.visibleOnTv().isEmpty()) {
                    viewModel.refresh()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onFocusChanged { forYouContentHasFocus = it.hasFocus },
    ) {
        when {
            savedListSelection == SavedListSelection.Watchlist -> TvWatchlistInline(
                onItemClick = onSavedListItemClick,
                modifier = Modifier.padding(
                    top = TvTopMenuLayout.contentTopInset + RecommendationsFilterBandHeight,
                ),
            )
            savedListSelection == SavedListSelection.Favorites -> TvFavoritesInline(
                onItemClick = onSavedListItemClick,
                modifier = Modifier.padding(
                    top = TvTopMenuLayout.contentTopInset + RecommendationsFilterBandHeight,
                ),
            )
            state.isLoading && state.sections.isEmpty() -> TvLoadingScreen(
                modifier = Modifier.background(MaterialTheme.colorScheme.background),
            )
            state.error != null && state.sections.isEmpty() -> TvErrorScreen(
                message = state.error ?: "Failed to load recommendations",
                onRetry = viewModel::loadRecommendations,
                modifier = Modifier.background(MaterialTheme.colorScheme.background),
            )
            visibleSections.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(horizontal = Spacing.safeArea),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.72f),
                            modifier = Modifier.size(28.dp),
                        )
                        Text(
                            text = "Not enough data yet",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White,
                        )
                        Text(
                            text = "Watch and rate more content to unlock personalized recommendations.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = viewModel::loadRecommendations,
                            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 12.dp),
                        ) {
                            Text("Check again", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            else -> {
                CompositionLocalProvider(
                    LocalBringIntoViewSpec provides TvSmoothBringIntoViewSpec,
                ) {
                    LazyColumn(
                        // Hoisted above the `when` so it is not discarded when a
                        // refresh briefly flips this branch to loading/empty and
                        // back — that is what dropped the reader at the top of the
                        // feed after opening an item.
                        state = recommendationsListState,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                        contentPadding = PaddingValues(
                            top = TvTopMenuLayout.contentTopInset + RecommendationsFilterBandHeight,
                            bottom = 24.dp,
                        ),
                    ) {
                        itemsIndexed(
                            items = visibleSections,
                            key = { _, section -> section.id },
                            contentType = { _, _ -> "recommendation-section-row" },
                        ) { index, section ->
                            val rowReturnLocation = pendingReturnLocation
                                ?.takeIf { it.rowIndex == index }
                            TvMediaRow(
                                title = section.title,
                                items = section.items,
                                onItemClick = { contentId ->
                                    detailReturnSectionId = section.id
                                    detailReturnContentId = contentId
                                    detailReturnRowIndex = index
                                    detailReturnCardIndex = section.items.indexOfFirst {
                                        it.contentId == contentId
                                    }.coerceAtLeast(0)
                                    onRecommendationItemClick(contentId)
                                },
                                style = TvRowStyle.Poster,
                                firstItemFocusRequester = firstRecommendationCardFocusRequester
                                    .takeIf { index == 0 },
                                rowContainerFocusRequester = when {
                                    rowReturnLocation != null -> detailReturnRowFocusRequester
                                    index == 0 -> firstRecommendationRowFocusRequester
                                    else -> null
                                },
                                restoreFocusIndex = rowReturnLocation?.cardIndex ?: -1,
                                restoreFocusRequester = detailReturnCardFocusRequester
                                    .takeIf { rowReturnLocation != null },
                                restoreFocusRequest = detailReturnFocusRequest
                                    .takeIf {
                                        detailReturnFocusPending && rowReturnLocation != null
                                    } ?: 0,
                                onRestoreFocusTargetPlaced = if (rowReturnLocation != null) {
                                    { requestId, cardIndex ->
                                        if (
                                            requestId == rowReturnLocation.requestId &&
                                            cardIndex == rowReturnLocation.cardIndex
                                        ) {
                                            attachedReturnLocation = rowReturnLocation
                                        }
                                    }
                                } else {
                                    null
                                },
                                onRestoreFocusTargetDisposed = if (rowReturnLocation != null) {
                                    { requestId, cardIndex ->
                                        if (
                                            requestId == rowReturnLocation.requestId &&
                                            cardIndex == rowReturnLocation.cardIndex
                                        ) {
                                            if (attachedReturnLocation == rowReturnLocation) {
                                                attachedReturnLocation = null
                                            }
                                        }
                                    }
                                } else {
                                    null
                                },
                                onDirectionUp = if (index == 0) {
                                    {
                                        forYouFocusRequester.claimFocusOrReport(
                                            target = "for_you_tab",
                                            action = "row_direction_up",
                                        )
                                    }
                                } else {
                                    null
                                },
                                onRowFocusChanged = if (index == 0) {
                                    { focused -> firstRecommendationRowFocused = focused }
                                } else {
                                    null
                                },
                            )
                        }
                        item { Spacer(modifier = Modifier.height(8.dp)) }
                    }
                }
            }
        }

        if (savedListSelection != null && visibleSections.isEmpty()) {
            Text(
                text = "No recommendations yet — showing your saved titles.",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
                ),
                color = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.padding(
                    start = Spacing.safeArea,
                    top = TvTopMenuLayout.contentTopInset + 40.dp,
                ),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = TvTopMenuLayout.contentTopInset)
                .height(RecommendationsFilterBandHeight)
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = Spacing.safeArea),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TvHeroActionPill(
                label = "For You",
                icon = Icons.Outlined.AutoAwesome,
                variant = TvPillVariant.Hollow,
                selected = savedListSelection == null,
                focusRequester = forYouFocusRequester,
                onDirectionDown = moveIntoRecommendations,
                heightOverride = 32.dp,
                horizontalPaddingOverride = 13.dp,
                iconSizeOverride = 10.dp,
                iconLabelSpacingOverride = 5.dp,
                restBorderWidthOverride = 0.75.dp,
                focusedBorderWidthOverride = 1.5.dp,
                focusedScaleOverride = 1.045f,
                focusedGlowElevationOverride = 9.dp,
                labelStyle = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                onClick = { savedListSelection = null },
            )
            TvHeroActionPill(
                label = "Watchlist",
                icon = Icons.Filled.Bookmark,
                variant = TvPillVariant.Hollow,
                selected = savedListSelection == SavedListSelection.Watchlist,
                focusRequester = watchlistFocusRequester,
                onDirectionDown = moveIntoRecommendations,
                heightOverride = 32.dp,
                horizontalPaddingOverride = 13.dp,
                iconSizeOverride = 10.dp,
                iconLabelSpacingOverride = 5.dp,
                restBorderWidthOverride = 0.75.dp,
                focusedBorderWidthOverride = 1.5.dp,
                focusedScaleOverride = 1.045f,
                focusedGlowElevationOverride = 9.dp,
                labelStyle = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                onClick = { savedListSelection = SavedListSelection.Watchlist },
            )
            TvHeroActionPill(
                label = "Favorites",
                icon = Icons.Filled.Favorite,
                variant = TvPillVariant.Hollow,
                selected = savedListSelection == SavedListSelection.Favorites,
                focusRequester = favoritesFocusRequester,
                onDirectionDown = moveIntoRecommendations,
                heightOverride = 32.dp,
                horizontalPaddingOverride = 13.dp,
                iconSizeOverride = 10.dp,
                iconLabelSpacingOverride = 5.dp,
                restBorderWidthOverride = 0.75.dp,
                focusedBorderWidthOverride = 1.5.dp,
                focusedScaleOverride = 1.045f,
                focusedGlowElevationOverride = 9.dp,
                labelStyle = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                onClick = { savedListSelection = SavedListSelection.Favorites },
            )
        }
    }
}
