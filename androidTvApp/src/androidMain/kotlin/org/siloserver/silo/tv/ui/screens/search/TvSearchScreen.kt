package org.siloserver.silo.tv.ui.screens.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon as M3Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshotFlow
import org.siloserver.silo.tv.ui.focus.TvReturnTarget
import org.siloserver.silo.tv.ui.focus.TvReturnTargetSaver
import org.siloserver.silo.tv.ui.focus.TvReturnRelocation
import org.siloserver.silo.tv.ui.focus.TvReturnResolution
import org.siloserver.silo.tv.ui.focus.resolveTvReturnTarget
import org.siloserver.silo.tv.ui.focus.TvFocusAcquisitionBudgetMillis
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.tv.ui.focus.claimFocusOrReport
import org.siloserver.silo.tv.ui.focus.TvFrameRelocationMaxAttempts
import org.siloserver.silo.tv.ui.focus.TvContentInitialFocusMaxAttempts
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.feature.RequestsFeatureStore
import org.siloserver.silo.model.request.RequestMediaResult
import org.siloserver.silo.model.request.RequestMediaType
import org.siloserver.silo.tv.ui.components.TvHideStockImeOnDispose
import org.siloserver.silo.tv.ui.components.TvCatalogGrid
import org.siloserver.silo.tv.ui.components.TvFilterChip
import org.siloserver.silo.tv.ui.components.TvSectionHeader
import org.siloserver.silo.tv.ui.components.tvOutlinedTextFieldColors
import org.siloserver.silo.tv.ui.screens.requests.TvRequestCard
import org.siloserver.silo.tv.ui.screens.requests.canOpenLibraryDetail
import org.siloserver.silo.tv.ui.screens.requests.filterTvRequestResults
import org.siloserver.silo.tv.ui.shell.TvTopMenuLayout
import org.siloserver.silo.tv.ui.theme.ElevatedSurface
import org.siloserver.silo.tv.ui.theme.Spacing
import org.siloserver.silo.viewmodel.RequestSearchViewModel
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

internal fun shouldFocusSearchField(
    hasEnteredSearch: Boolean,
    hasResults: Boolean,
    explicitFieldRequest: Boolean,
): Boolean = explicitFieldRequest || (!hasEnteredSearch && !hasResults)

@OptIn(ExperimentalTvMaterial3Api::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Composable
fun TvSearchScreen(
    onResultClick: (BrowseItem) -> Unit,
    onOpenRequestDetail: (mediaType: String, tmdbId: Int) -> Unit,
    onOpenLibraryItem: (contentId: String) -> Unit,
    searchFieldFocusRequester: FocusRequester? = null,
    backToSearchFieldRequest: Int = 0,
    onSearchFieldFocusChanged: (Boolean) -> Unit = {},
    viewModel: TvSearchViewModel = koinViewModel(),
    requestSearchViewModel: RequestSearchViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val requestState by requestSearchViewModel.uiState.collectAsState()
    val requestsFeatureStore: RequestsFeatureStore = koinInject()
    val requestsEnabled by requestsFeatureStore.isEnabled.collectAsState()
    val firstResultFocusRequester = remember { FocusRequester() }
    val firstRequestResultFocusRequester = remember { FocusRequester() }
    // One requester per section, addressed by index rather than pinned to the
    // first card. The two sections are separate focus containers, so a single
    // shared requester could not name a position in both.
    val restoreCatalogFocusRequester = remember { FocusRequester() }
    val restoreRequestFocusRequester = remember { FocusRequester() }

    // Search is a root tab, like Calendar: "a target exists" cannot mean
    // "returning", because browsing a result and re-selecting Search would look
    // identical. The click is the signal, the lifecycle says when.
    var returnTarget by rememberSaveable(stateSaver = TvReturnTargetSaver) {
        mutableStateOf<TvReturnTarget?>(null)
    }
    var returnPending by rememberSaveable { mutableStateOf(false) }
    // One refresh per return, and a hard ceiling on how many times a return may
    // stand down waiting for content. Both exist because this effect is keyed
    // on state its own body changes.
    var returnRefreshed by rememberSaveable { mutableStateOf(false) }
    var returnStandDowns by remember { mutableIntStateOf(0) }
    var resumeGeneration by remember { mutableIntStateOf(0) }
    TvSearchResumeSignal { resumeGeneration++ }
    var focusedReturnItemId by remember { mutableStateOf<String?>(null) }
    var restoreCatalogIndex by remember { mutableIntStateOf(-1) }
    var restoreRequestIndex by remember { mutableIntStateOf(-1) }

    var pendingSearchFocus by remember { mutableStateOf(false) }
    var scrollHeaderIntoView by remember { mutableIntStateOf(0) }


    val recordReturn: (String, String, Int, Int) -> Unit = { sectionId, itemId, sectionIndex, itemIndex ->
        returnPending = true
        returnRefreshed = false
        returnStandDowns = 0
        // Consumed, not deferred. Merely holding the submit handoff back until
        // the return finishes means it becomes eligible the moment restoration
        // clears returnPending — and then steals focus off the card that was
        // just restored. Opening something ends that handoff's claim outright.
        pendingSearchFocus = false
        returnTarget = TvReturnTarget(
            sectionId = sectionId,
            itemId = itemId,
            sectionIndex = sectionIndex,
            itemIndex = itemIndex,
        )
    }
    val feedbackActionFocusRequester = remember { FocusRequester() }
    val firstFilterChipFocusRequester = remember { FocusRequester() }
    val internalSearchFieldFocusRequester = remember { FocusRequester() }
    val searchGridState = rememberLazyGridState()
    val activeSearchFieldFocusRequester = searchFieldFocusRequester ?: internalSearchFieldFocusRequester
    val keyboardController = LocalSoftwareKeyboardController.current
    var hasEnteredSearch by rememberSaveable { mutableStateOf(false) }
    var isKeyboardOpen by remember { mutableStateOf(false) }

    // Back closes the keyboard rather than leaving the screen.
    //
    // Without this, Back from a raised keyboard fell through to the shell and
    // popped Search entirely — so the only way to put the keyboard away was
    // also the way out, and anyone reaching for the mic lost the screen instead.
    BackHandler(enabled = isKeyboardOpen) {
        isKeyboardOpen = false
        keyboardController?.hide()
        // Back from a raised keyboard must answer synchronously, so this is the
        // single-shot claim; losing it silently would strand the viewer with
        // the keyboard gone and nothing focused.
        activeSearchFieldFocusRequester.claimFocusOrReport(
            target = "search_field",
            action = "keyboard_dismissed",
        )
    }
    // Which REGION holds focus. A screen-wide flag cannot answer the question
    // these claims actually ask. requestFocusUntilObserved tests isFocused()
    // before it ever calls requestFocus, so "something on the search screen has
    // focus" made every claim below a no-op the moment the screen owned focus
    // at all — which is always, once you are on it. Back from a result never
    // returned to the field, and a submitted search never handed you its
    // results, because in both cases the flag was already true.
    var focusedRegion by remember { mutableStateOf<TvSearchFocusRegion?>(null) }
    val setFocusedRegion: (TvSearchFocusRegion, Boolean) -> Unit = { region, focused ->
        if (focused) {
            focusedRegion = region
        } else if (focusedRegion == region) {
            focusedRegion = null
        }
    }
    val requestMediaType = state.mediaType.toRequestMediaType()
    val visibleRequestResults = requestState.results
        .filterTvRequestResults()
        .filter { state.mediaType.allowsRequestResult(it) }
        .filter { state.showAudiobooks || it.mediaType != RequestMediaType.Audiobook }
    val canSearchRequests = requestsEnabled && state.query.trim().length >= 2
    val shouldShowRequestSection = canSearchRequests &&
        (visibleRequestResults.isNotEmpty() ||
            requestState.isLoading ||
            requestState.error != null ||
            requestState.hasSubmittedQuery)
    val requestSearchSettled = !canSearchRequests || !requestState.isLoading
    // Same precedence as the post-search handoff below: results, then the
    // error's "Try again", then the request row.
    val firstContentFocusRequester = when {
        state.items.isNotEmpty() -> firstResultFocusRequester
        state.error != null -> feedbackActionFocusRequester
        visibleRequestResults.isNotEmpty() -> firstRequestResultFocusRequester
        else -> firstFilterChipFocusRequester
    }
    val hasContentFocusTarget = state.items.isNotEmpty() ||
        visibleRequestResults.isNotEmpty() ||
        state.error != null

    LaunchedEffect(requestsEnabled, state.query, requestMediaType) {
        val query = state.query.trim()
        if (!requestsEnabled || query.length < 2) {
            requestSearchViewModel.onQueryChanged("")
            return@LaunchedEffect
        }
        // Same query as the view model already answered. This effect re-runs on
        // every re-entry, a Back out of a request detail included — and there
        // the results are BOTH still on screen and genuinely stale, because
        // creating a request in the detail changes the status these cards show.
        //
        // So refresh rather than skip, and refresh in place rather than through
        // the ordinary path, which blanks the row before refetching: a viewer
        // would watch it empty and refill, and a return restoration would lose
        // the card it was aiming at partway through.
        val alreadyAnswered = requestState.submittedQuery == query &&
            requestState.mediaType == requestMediaType &&
            !requestState.isLoading &&
            requestState.error == null
        // Nothing to do: the answer on screen is for this exact query. Staleness
        // after a return is handled by the restoration effect, which is the only
        // place that knows a return happened — this effect is keyed on the query
        // and cannot tell a re-entry from a recomposition.
        if (alreadyAnswered) return@LaunchedEffect
        delay(300)
        requestSearchViewModel.onMediaTypeChanged(requestMediaType)
        requestSearchViewModel.onQueryChanged(query)
        requestSearchViewModel.search()
    }

    LaunchedEffect(activeSearchFieldFocusRequester) {
        val hasResults = state.items.isNotEmpty() || visibleRequestResults.isNotEmpty()
        if (shouldFocusSearchField(hasEnteredSearch, hasResults, explicitFieldRequest = false)) {
            requestFocusUntilObserved(
                maxAttempts = TvContentInitialFocusMaxAttempts,
                awaitAttempt = { withFrameNanos { } },
                requestFocus = activeSearchFieldFocusRequester::requestFocus,
                isFocused = { focusedRegion == TvSearchFocusRegion.Field },
            )
        }
        hasEnteredSearch = true
    }
    // Return restoration. Deliberately separate from the pendingSearchFocus
    // handoff above: that one belongs to an explicit search submission, and
    // this screen goes out of its way NOT to jump focus when results merely
    // appear, because doing so yanks the viewer out of text entry mid-query.
    // A return is the one case where moving focus onto a result is what was
    // asked for.
    LaunchedEffect(
        resumeGeneration,
        state.isLoading,
        state.isLoadingMore,
        state.items,
        visibleRequestResults,
        requestSearchSettled,
    ) {
        // Deliberately NOT gated on requestSearchSettled. A catalog return has
        // no reason to wait for the request row, and a request return is held
        // by the section's own completeness below — which is what makes that
        // flag mean something instead of being unreachable.
        if (!returnPending || state.isLoading) return@LaunchedEffect

        // Opening a request detail can create a request, which changes the
        // status these cards show — so a return is exactly when this row is
        // stale, and it is stale whether or not the screen stayed composed.
        // In place, so the cards a restoration is aiming at stay put.
        //
        // ONCE per return. Refreshing flips requestSearchSettled, which is a
        // key of this very effect, so an unguarded call relaunches the effect
        // and refreshes again — forever, whenever resolution does not finish
        // on the first pass.
        //
        // Skipped while an error is showing: there the query-keyed effect owns
        // recovery and runs a full search, and two refetches racing would have
        // one cancel the other and blank the row underneath the restoration.
        if (!returnRefreshed &&
            canSearchRequests &&
            requestState.hasSubmittedQuery &&
            !requestState.isLoading &&
            requestState.error == null
        ) {
            returnRefreshed = true
            requestSearchViewModel.refreshInPlace()
            // A request card must be resolved against the REFRESHED row.
            // Preserved results still contain it, so it would otherwise match
            // Exact, take focus and disarm before the response lands — and if
            // that response drops the card, focus goes with it. Incomplete
            // only yields Pending when the target is ABSENT, so completeness
            // alone does not hold this back. A catalog target is unaffected by
            // the request row and carries on immediately.
            if (returnTarget?.sectionId == TvSearchRequestSectionId) return@LaunchedEffect
        }

        val sections = tvSearchReturnSections(
            catalogItems = state.items,
            requestResults = visibleRequestResults,
            // More pages can still arrive, so a target beyond the loaded
            // results is not yet absent.
            catalogComplete = !state.hasMore,
            requestsComplete = requestSearchSettled,
        )
        fun resolve(final: Boolean) = resolveTvReturnTarget(
            target = returnTarget,
            sections = sections,
            // A library item and a requestable title are different things even
            // when they are the same film, and namespacing already means an id
            // cannot appear in the other section. Following would only buy
            // pointless waits.
            relocation = TvReturnRelocation.SameSectionOnly,
            treatAbsenceAsFinal = final,
        )

        var resolution = resolve(final = false)
        if (resolution is TvReturnResolution.Pending) {
            // Not loaded yet is not the same as not there, and consuming the
            // target on the difference loses a return that was about to become
            // possible. Search does not page TOWARD a target the way the flat
            // surfaces do, but work already in flight deserves the wait.
            //
            // If content arrives first this coroutine is cancelled and the
            // effect re-resolves against it, which is the outcome we want; the
            // delay only elapses when nothing came.
            delay(TvSearchReturnPendingBudgetMillis)
            // Still fetching. Standing down WITHOUT consuming is the safe move:
            // a timer expiring is not evidence that nothing is coming, and no
            // latency figure would make it one. This effect is keyed on the
            // very signals that change when the fetch lands, so it re-runs and
            // resolves properly then.
            //
            // Bounded all the same. A fetch that never completes would
            // otherwise leave the screen armed for good, and an armed return
            // keeps suppressing the explicit-submit handoff — so the failure
            // would outlive the return and quietly break ordinary searching.
            if (requestState.isLoading || state.isLoadingMore) {
                val settled = withTimeoutOrNull(TvSearchReturnInFlightBudgetMillis) {
                    snapshotFlow { requestState.isLoading || state.isLoadingMore }
                        .first { !it }
                } != null
                returnStandDowns++
                // Absolute, not per-attempt. A timeout that restarts with the
                // effect bounds one stuck fetch and nothing else — a sequence
                // of quick successful ones would keep re-arming it while the
                // return never resolved and went on suppressing the ordinary
                // submit handoff.
                if (!settled || returnStandDowns >= TvSearchReturnMaxStandDowns) {
                    returnTarget = null
                    returnPending = false
                }
                return@LaunchedEffect
            }
            resolution = resolve(final = true)
        }
        val located = resolution as? TvReturnResolution.Located

        if (located == null) {
            returnTarget = null
            returnPending = false
            return@LaunchedEffect
        }

        // Observed on the CARD, not the region. A return is a claim on one
        // specific item, so "some result has focus" is the same too-coarse test
        // this file just removed everywhere else: any already-focused card in
        // the region satisfied it and the saved card was never requested, which
        // is precisely the case a return exists to serve.
        when (located.sectionId) {
            TvSearchCatalogSectionId -> {
                restoreCatalogIndex = located.itemIndex
                searchGridState.scrollToItem(located.itemIndex)
                requestFocusUntilObserved(
                    maxAttempts = TvFrameRelocationMaxAttempts,
                    awaitAttempt = { androidx.compose.runtime.withFrameNanos { } },
                    requestFocus = restoreCatalogFocusRequester::requestFocus,
                    isFocused = { focusedReturnItemId == located.itemId },
                )
            }
            TvSearchRequestSectionId -> {
                restoreRequestIndex = located.itemIndex
                requestFocusUntilObserved(
                    maxAttempts = TvFrameRelocationMaxAttempts,
                    awaitAttempt = { androidx.compose.runtime.withFrameNanos { } },
                    requestFocus = restoreRequestFocusRequester::requestFocus,
                    isFocused = { focusedReturnItemId == located.itemId },
                )
            }
        }

        // Confirmed by watching focus hold the card, not by having asked.
        withTimeoutOrNull(TvFocusAcquisitionBudgetMillis) {
            snapshotFlow { focusedReturnItemId }
                .transformLatest { id ->
                    if (id == located.itemId) {
                        delay(TvSearchReturnSettleMillis)
                        emit(Unit)
                    }
                }
                .first()
        }

        returnTarget = null
        returnPending = false
        restoreCatalogIndex = -1
        restoreRequestIndex = -1
    }

    // The search field auto-shows the soft keyboard on focus; leaving Search
    // without dismissing it left the system IME floating over the next screen
    // (e.g. over the video when starting playback from a result).
    TvHideStockImeOnDispose()
    LaunchedEffect(scrollHeaderIntoView) {
        if (scrollHeaderIntoView > 0) searchGridState.animateScrollToItem(0)
    }
    LaunchedEffect(backToSearchFieldRequest) {
        if (backToSearchFieldRequest <= 0) return@LaunchedEffect
        searchGridState.animateScrollToItem(0)
        requestFocusUntilObserved(
            maxAttempts = TvFrameRelocationMaxAttempts,
            awaitAttempt = { androidx.compose.runtime.withFrameNanos { } },
            requestFocus = activeSearchFieldFocusRequester::requestFocus,
            isFocused = { focusedRegion == TvSearchFocusRegion.Field },
        )
    }
    LaunchedEffect(
        pendingSearchFocus,
        returnPending,
        state.isLoading,
        requestSearchSettled,
        state.items.size,
        visibleRequestResults.size,
    ) {
        if (!pendingSearchFocus || state.isLoading) return@LaunchedEffect
        // Only wait on the request lookup when it is the thing focus would
        // land on. Library results are the primary target and arrive first;
        // holding them hostage to a slow TMDB round-trip left the user parked
        // on the search field with results visibly sitting there.
        if (state.items.isEmpty() && !requestSearchSettled) return@LaunchedEffect
        // A return outranks a stale submit. Submitting, walking down to a card
        // that the reset had not yet cleared, and opening it leaves this armed
        // on a retained composition — and on the way back both effects would
        // otherwise be eligible, one aiming at the restored card and the other
        // at the first result.
        if (returnPending) return@LaunchedEffect
        // A failed library search lands on "Try again", even when the request
        // row has something to show. Landing on a request card instead scrolled
        // the field, chips and the error itself up under the top menu — the
        // screen looked broken rather than merely failed, and the recovery
        // action was the one thing not on screen.
        val postSearchTarget = when {
            state.items.isNotEmpty() -> firstResultFocusRequester
            state.error != null -> feedbackActionFocusRequester
            visibleRequestResults.isNotEmpty() -> firstRequestResultFocusRequester
            else -> firstFilterChipFocusRequester
        }
        val postSearchRegion = when {
            state.items.isNotEmpty() -> TvSearchFocusRegion.CatalogResults
            state.error != null -> TvSearchFocusRegion.Feedback
            visibleRequestResults.isNotEmpty() -> TvSearchFocusRegion.RequestResults
            else -> TvSearchFocusRegion.Chips
        }
        requestFocusUntilObserved(
            maxAttempts = TvContentInitialFocusMaxAttempts,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = postSearchTarget::requestFocus,
            isFocused = { focusedRegion == postSearchRegion },
        )
        // Consumed AFTER the attempt, not before. This effect is keyed on the
        // flag, so clearing it first relaunched the effect and cancelled the
        // request at its first frame await — the handoff never actually ran.
        // While it is in flight a key change (a page landing) simply retries;
        // recordReturn still clears it outright when a card is opened.
        pendingSearchFocus = false
    }
    // Note: we deliberately do NOT auto-jump focus to the first result when
    // it appears. Doing so during the debounced as-you-type search yanks
    // focus out of text entry mid-keystroke. Instead, the explicit
    // focusProperties wired below give the user three reliable handoffs:
    //   • search field —DOWN→ first chip
    //   • first chip   —DOWN→ first card (when results exist)
    //   • first card   —UP→   first chip
    // The IME Search action submits the query without stealing focus.

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        TvCatalogGrid(
            items = state.items,
            // Reset searches keep the existing grid stable and report progress
            // in the pinned status line. Only pagination owns a grid spinner.
            isLoading = state.isLoadingMore,
            hasMore = state.hasMore,
            onItemClick = { },
            onBrowseItemClick = { item ->
                recordReturn(
                    TvSearchCatalogSectionId,
                    tvSearchCatalogItemId(item.contentId),
                    0,
                    state.items.indexOfFirst { it.contentId == item.contentId },
                )
                onResultClick(item)
            },
            onLoadMore = viewModel::loadMore,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            gridState = searchGridState,
            minCellWidth = 132.dp,
            contentPadding = PaddingValues(
                start = Spacing.safeArea,
                top = Spacing.sm,
                bottom = Spacing.xxxl,
                end = 24.dp,
            ),
            horizontalSpacing = 14.dp,
            verticalSpacing = 20.dp,
            firstItemFocusRequester = firstResultFocusRequester,
            restoreItemIndex = restoreCatalogIndex,
            restoreItemFocusRequester = restoreCatalogFocusRequester,
            onItemFocusedAtIndex = { item, _, focused ->
                // Guarded on item IDENTITY, not just the region. These callbacks
                // are per card, and Compose can deliver the newly focused card
                // before the outgoing one reports false — a bare region flag
                // would then be cleared by the card that just lost focus, right
                // after the new one set it. Recycling an outgoing item has the
                // same shape. A stale false whose id is no longer the focused
                // one is ignored here.
                val id = tvSearchCatalogItemId(item.contentId)
                if (focused) {
                    focusedReturnItemId = id
                    focusedRegion = TvSearchFocusRegion.CatalogResults
                } else if (focusedReturnItemId == id) {
                    focusedReturnItemId = null
                    if (focusedRegion == TvSearchFocusRegion.CatalogResults) {
                        focusedRegion = null
                    }
                }
            },
            // UP from the first card always lands back on the filter chip rail.
            // Without this Compose's spatial focus search can prefer the wider
            // search field above and skip over the smaller chip row.
            firstItemCardModifier = Modifier.focusProperties {
                up = firstFilterChipFocusRequester
            },
            header = {
                SearchStage(
                    query = state.query,
                    mediaType = state.mediaType,
                    availableMediaTypes = state.availableMediaTypes,
                    resultStatus = searchStatusText(
                        query = state.query,
                        total = state.total,
                        isSearching = state.isLoading,
                        error = state.error,
                        isPartialCount = state.mediaType == TvSearchMediaType.All && state.hasMore,
                    ),
                    hasContentFocusTarget = hasContentFocusTarget,
                    searchFieldFocusRequester = activeSearchFieldFocusRequester,
                    firstFilterChipFocusRequester = firstFilterChipFocusRequester,
                    firstContentFocusRequester = firstContentFocusRequester,
                    onSearchFieldFocusChanged = { focused ->
                        setFocusedRegion(TvSearchFocusRegion.Field, focused)
                        onSearchFieldFocusChanged(focused)
                    },
                    onChipsFocusChanged = { focused ->
                        setFocusedRegion(TvSearchFocusRegion.Chips, focused)
                    },
                    onQueryChanged = viewModel::onQueryChanged,
                    onSearch = {
                        pendingSearchFocus = true
                        val query = state.query.trim()
                        if (requestsEnabled && query.length >= 2) {
                            requestSearchViewModel.onMediaTypeChanged(requestMediaType)
                            requestSearchViewModel.onQueryChanged(query)
                            requestSearchViewModel.search()
                        }
                        viewModel.submitSearch()
                    },
                    onMediaTypeChanged = viewModel::onMediaTypeChanged,
                    isKeyboardOpen = isKeyboardOpen,
                    onKeyboardOpenChanged = { isKeyboardOpen = it },
                )
            },
            footer = {
                TvRequestSearchSection(
                    query = state.query,
                    requestsEnabled = requestsEnabled,
                    isLoading = requestState.isLoading,
                    error = requestState.error,
                    hasSubmittedQuery = requestState.hasSubmittedQuery,
                    results = visibleRequestResults,
                    shouldShow = shouldShowRequestSection,
                    firstItemFocusRequester = firstRequestResultFocusRequester,
                    restoreItemIndex = restoreRequestIndex,
                    restoreItemFocusRequester = restoreRequestFocusRequester,
                    onItemFocusChanged = { item, _, focused ->
                        // Identity-guarded for the same reason as the catalog
                        // cards above: a late false from the card that just
                        // lost focus must not clear the region the new one set.
                        val id = tvSearchRequestItemId(item.mediaType, item.tmdbId)
                        if (focused) {
                            focusedReturnItemId = id
                            focusedRegion = TvSearchFocusRegion.RequestResults
                        } else if (focusedReturnItemId == id) {
                            focusedReturnItemId = null
                            if (focusedRegion == TvSearchFocusRegion.RequestResults) {
                                focusedRegion = null
                            }
                        }
                    },
                    onItemClicked = { item, index ->
                        recordReturn(
                            TvSearchRequestSectionId,
                            tvSearchRequestItemId(item.mediaType, item.tmdbId),
                            1,
                            index,
                        )
                    },
                    // Same precedence as the handoff: UP from the request row
                    // goes to the results, else the error's "Try again", else
                    // the chips. Spatial search alone skipped the button and
                    // landed on the chips or the field.
                    cardModifier = Modifier.focusProperties {
                        up = when {
                            state.items.isNotEmpty() -> firstResultFocusRequester
                            state.error != null -> feedbackActionFocusRequester
                            else -> firstFilterChipFocusRequester
                        }
                    },
                    onOpenRequestDetail = onOpenRequestDetail,
                    onOpenLibraryItem = onOpenLibraryItem,
                )
            },
            emptyState = {
                when {
                    state.query.isBlank() -> SearchFeedbackMessage(
                        title = "Search your library",
                        body = availableMediaDescription(state.availableMediaTypes),
                    )
                    state.isLoading -> Box(modifier = Modifier.height(64.dp))
                    state.error != null -> SearchFeedbackMessage(
                        title = "Search is unavailable",
                        body = state.error!!,
                        actionLabel = "Try again",
                        actionFocusRequester = feedbackActionFocusRequester,
                        actionUpFocusRequester = firstFilterChipFocusRequester,
                        onActionFocusChanged = { focused ->
                            setFocusedRegion(TvSearchFocusRegion.Feedback, focused)
                            // Coming back UP from the request row, the button
                            // is only just on screen and the field, chips and
                            // error title are still under the top menu. The
                            // header is item zero; bring the whole thing back.
                            if (focused) scrollHeaderIntoView++
                        },
                        onAction = viewModel::submitSearch,
                    )
                    else -> SearchFeedbackMessage(
                        title = "No matches for “${state.query}”",
                        body = "Try a shorter title or a different filter.",
                    )
                }
            },
        )
    }
}

@Composable
private fun TvRequestSearchSection(
    query: String,
    requestsEnabled: Boolean,
    isLoading: Boolean,
    error: String?,
    hasSubmittedQuery: Boolean,
    results: List<RequestMediaResult>,
    shouldShow: Boolean,
    firstItemFocusRequester: FocusRequester,
    /** Applied to every card, so UP is routed the same from any position in the row. */
    cardModifier: Modifier,
    restoreItemIndex: Int = -1,
    restoreItemFocusRequester: FocusRequester? = null,
    onItemFocusChanged: (RequestMediaResult, Int, Boolean) -> Unit = { _, _, _ -> },
    onItemClicked: (RequestMediaResult, Int) -> Unit = { _, _ -> },
    onOpenRequestDetail: (mediaType: String, tmdbId: Int) -> Unit,
    onOpenLibraryItem: (contentId: String) -> Unit,
) {
    if (!requestsEnabled || query.trim().length < 2 || !shouldShow) return

    // This section is a full-span footer item inside TvCatalogGrid, so the
    // grid's own contentPadding already supplies the safe-area gutter. Adding
    // it again here is what pushed this header out of line with the result
    // grid above it.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.md, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TvSectionHeader(title = "Available to request")
        when {
            results.isNotEmpty() -> {
                LazyRow(
                    // A LazyRow clips along its scroll axis, so a focused card
                    // scaling up at index zero lost its left edge against the
                    // row's bounds. Let the row bleed into the grid gutter and
                    // pad the content back by the same amount, the way the
                    // home rails span the full width — the first card then has
                    // room to grow without being cut off.
                    modifier = Modifier
                        .bleedStart(Spacing.safeArea)
                        .focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                    contentPadding = PaddingValues(
                        start = Spacing.safeArea,
                        end = Spacing.safeArea,
                        top = 12.dp,
                        bottom = 12.dp,
                    ),
                ) {
                    itemsIndexed(
                        results,
                        key = { _, item -> "${item.mediaType}-${item.tmdbId}" },
                        contentType = { _, _ -> "request-search-result" },
                    ) { index, item ->
                        val isRestoreTarget = restoreItemFocusRequester != null &&
                            index == restoreItemIndex
                        TvRequestCard(
                            result = item,
                            onClick = {
                                onItemClicked(item, index)
                                if (item.canOpenLibraryDetail()) {
                                    onOpenLibraryItem(item.libraryContentId.orEmpty())
                                } else {
                                    onOpenRequestDetail(item.mediaType, item.tmdbId)
                                }
                            },
                            // The restore target wins the slot when it is this
                            // card: index zero can be both, and two requesters
                            // on one node is one requester too many.
                            focusRequester = if (isRestoreTarget) {
                                restoreItemFocusRequester
                            } else {
                                firstItemFocusRequester.takeIf { index == 0 }
                            },
                            cardModifier = cardModifier
                                .onFocusChanged { onItemFocusChanged(item, index, it.hasFocus) },
                        )
                    }
                }
            }
            isLoading -> RequestSearchFeedbackRow("Checking requestable titles...", showProgress = true)
            error != null -> RequestSearchFeedbackRow(error)
            hasSubmittedQuery -> RequestSearchFeedbackRow("No requestable matches found.")
        }
    }
}

/**
 * Lets a scrolling row extend [amount] to the left of the slot it was given,
 * so content padded back in by the same amount can overflow (scale, glow)
 * into that space without being clipped by the row's own bounds.
 */
private fun Modifier.bleedStart(amount: Dp): Modifier = layout { measurable, constraints ->
    val extra = amount.roundToPx()
    val widened = constraints.copy(
        minWidth = if (constraints.hasBoundedWidth) constraints.minWidth + extra else constraints.minWidth,
        maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else constraints.maxWidth,
    )
    val placeable = measurable.measure(widened)
    layout((placeable.width - extra).coerceAtLeast(0), placeable.height) {
        placeable.place(-extra, 0)
    }
}

@Composable
private fun RequestSearchFeedbackRow(
    message: String,
    showProgress: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showProgress) {
            CircularProgressIndicator(modifier = Modifier.size(22.dp))
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.68f),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
/**
 * The focus regions a claim on this screen can aim at.
 *
 * Each claim is observed on the region it actually asked for, so moving focus
 * WITHIN the screen — field to results, results back to field — is a state the
 * arrival test can distinguish. A single screen-wide hasFocus could not.
 */
private enum class TvSearchFocusRegion { Field, Chips, CatalogResults, RequestResults, Feedback }

@Composable
private fun SearchStage(
    query: String,
    mediaType: TvSearchMediaType,
    availableMediaTypes: List<TvSearchMediaType>,
    resultStatus: String?,
    hasContentFocusTarget: Boolean,
    searchFieldFocusRequester: FocusRequester,
    firstFilterChipFocusRequester: FocusRequester,
    firstContentFocusRequester: FocusRequester,
    onSearchFieldFocusChanged: (Boolean) -> Unit,
    onChipsFocusChanged: (Boolean) -> Unit,
    onQueryChanged: (String) -> Unit,
    onSearch: () -> Unit,
    onMediaTypeChanged: (TvSearchMediaType) -> Unit,
    isKeyboardOpen: Boolean,
    onKeyboardOpenChanged: (Boolean) -> Unit,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val mediaTypes = availableMediaTypes
    val fieldShape = RoundedCornerShape(14.dp)

    // Rendered as the grid's header item, so the grid's contentPadding already
    // provides the horizontal gutters. Insetting again here put the field and
    // chips a full gutter to the right of the result cards beneath them.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = TvTopMenuLayout.contentTopInset - 12.dp,
                bottom = Spacing.sm,
            ),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        // Voice is the system keyboard's job: Gboard on Android TV puts a mic
        // key on the keyboard itself, which listens through the remote. A
        // separate on-screen mic only added a second route to the same thing
        // and a sentence of copy to explain it.
        OutlinedTextField(
            value = query,
            onValueChange = { onQueryChanged(it.take(TV_SEARCH_QUERY_MAX_LENGTH)) },
            // Read-only until Select. This is what actually keeps the keyboard
            // down — not withholding a show() call, which was the earlier
            // mistake: Compose raises the IME itself whenever an editable field
            // takes focus, so the only way to hold a focused field without a
            // keyboard is for it not to be editable yet.
            //
            // Focus can then rest here harmlessly, the D-pad still belongs to
            // the screen, and everything beside the field stays reachable.
            readOnly = !isKeyboardOpen,
            singleLine = true,
            placeholder = {
                Text(
                    text = searchFieldPrompt(mediaTypes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.56f),
                )
            },
            leadingIcon = {
                M3Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier.size(20.dp),
                )
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Search,
            ),
            keyboardActions = KeyboardActions(
                onSearch = {
                    // Submitting is the end of typing. Put the IME away here,
                    // explicitly: focus later moving to a result does not
                    // reliably dismiss it on TV, and a keyboard left standing
                    // over the grid was the most-reported oddity of this
                    // screen. Flip the open flag too so the field is read-only
                    // again and Back/D-pad go to the screen, not the IME.
                    onKeyboardOpenChanged(false)
                    keyboardController?.hide()
                    onSearch()
                },
            ),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = Color.White),
            shape = fieldShape,
            modifier = Modifier
                .width(520.dp)
                .height(52.dp)
                // Pin DOWN to the chip rail so the user can always step from
                // the search field onto the All/Movies/Series filters,
                // regardless of whether result cards are also rendered below.
                .focusRequester(searchFieldFocusRequester)
                .onFocusChanged { state ->
                    onSearchFieldFocusChanged(state.isFocused)
                    // Leaving the field puts it back to read-only, so returning
                    // to it later does not silently raise the keyboard again.
                    if (!state.isFocused && isKeyboardOpen) onKeyboardOpenChanged(false)
                }
                // Select opens the keyboard; focus alone does not.
                //
                // Raising it on focus is what made everything beside this field
                // unreachable: the IME is a separate window that owns the
                // D-pad, so with it up no key ever reaches this app. With the
                // keyboard closed the D-pad belongs to the screen again. Typing
                // costs one Select first, which is the trade, and it is the one
                // the Wholphin client makes.
                .onPreviewKeyEvent { event ->
                    val opensKeyboard = event.key == Key.DirectionCenter || event.key == Key.Enter
                    when {
                        event.type == KeyEventType.KeyUp && opensKeyboard && !isKeyboardOpen -> {
                            onKeyboardOpenChanged(true)
                            keyboardController?.show()
                            true
                        }
                        // DOWN has to be taken from the field. With the
                        // keyboard closed and a query in the field, the
                        // read-only text field still swallows Down
                        // (caret-to-end), so `focusProperties { down = … }`
                        // never gets a chance and the user is stuck on the
                        // field after a search — nothing below is reachable.
                        // Taking it in the preview phase fixes that; UP already
                        // belongs to the shell.
                        event.type == KeyEventType.KeyDown &&
                            event.key == Key.DirectionDown &&
                            !isKeyboardOpen -> {
                            firstFilterChipFocusRequester.claimFocusOrReport(
                                target = "search_first_filter_chip",
                                action = "field_down",
                            )
                        }
                        else -> false
                    }
                }
                .focusProperties { down = firstFilterChipFocusRequester },
            colors = tvOutlinedTextFieldColors(
                focusedContainerColor = ElevatedSurface,
                unfocusedContainerColor = Color.White.copy(alpha = 0.055f),
                focusedBorderColor = Color.White.copy(alpha = 0.94f),
                unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
            ),
        )

        LazyRow(
            modifier = Modifier
                .onFocusChanged { onChipsFocusChanged(it.hasFocus) }
                .focusRestorer(firstFilterChipFocusRequester),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(end = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(
                mediaTypes,
                contentType = { _, _ -> "media-type-chip" },
            ) { index, type ->
                val chipModifier = Modifier
                    // UP returns to the search field — every chip, no
                    // exceptions. The shell claims DirectionUp in its own
                    // preview handler above this row, and when the move it
                    // performs fails it hands focus to the top menu, so every
                    // chip needs an explicit target or Up leaves the screen.
                    .focusProperties { up = searchFieldFocusRequester }
                    .then(
                        if (index == 0) {
                            Modifier.focusRequester(firstFilterChipFocusRequester)
                        } else {
                            Modifier
                        },
                    )
                    .then(
                        // Only redirect DOWN when there's actually a card to
                        // land on — pointing at an unattached FocusRequester
                        // makes the key event a no-op and traps the user.
                        if (hasContentFocusTarget) {
                            Modifier.focusProperties { down = firstContentFocusRequester }
                        } else {
                            Modifier
                        },
                    )
                TvFilterChip(
                    text = type.label,
                    selected = mediaType == type,
                    onClick = { onMediaTypeChanged(type) },
                    modifier = chipModifier,
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 8.dp),
                    textStyle = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                    ),
                )
            }
        }

        // An empty minimum line keeps the header stable while allowing the
        // actual typography to grow at accessibility text scales.
        Text(
            text = resultStatus.orEmpty(),
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.62f),
            minLines = 1,
        )
    }
}

@Composable
private fun SearchFeedbackMessage(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    actionFocusRequester: FocusRequester? = null,
    actionUpFocusRequester: FocusRequester? = null,
    onActionFocusChanged: (Boolean) -> Unit = {},
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        M3Icon(
            imageVector = Icons.Filled.Search,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.34f),
            modifier = Modifier
                .padding(top = 2.dp)
                .size(28.dp),
        )

        Column(
            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.62f),
            )

            if (actionLabel != null && onAction != null) {
                Button(
                    onClick = onAction,
                    modifier = Modifier
                        .padding(top = Spacing.sm)
                        .onFocusChanged { onActionFocusChanged(it.isFocused) }
                        .then(
                            if (actionFocusRequester != null) {
                                Modifier.focusRequester(actionFocusRequester)
                            } else {
                                Modifier
                            },
                        )
                        .then(
                            if (actionUpFocusRequester != null) {
                                Modifier.focusProperties { up = actionUpFocusRequester }
                            } else {
                                Modifier
                            },
                        ),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
                ) {
                    Text(actionLabel, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

private fun searchFieldPrompt(mediaTypes: List<TvSearchMediaType>): String =
    "Search ${availableMediaNames(mediaTypes)}"

private fun availableMediaDescription(mediaTypes: List<TvSearchMediaType>): String =
    "Find ${availableMediaNames(mediaTypes)} in one place."

private fun availableMediaNames(mediaTypes: List<TvSearchMediaType>): String {
    val names = mediaTypes
        .filterNot { it == TvSearchMediaType.All }
        .map { it.label.lowercase() }

    return when (names.size) {
        0 -> "your library"
        1 -> names.single()
        2 -> names.joinToString(" and ")
        else -> names.dropLast(1).joinToString(", ") + ", and ${names.last()}"
    }
}

private fun searchStatusText(
    query: String,
    total: Int,
    isSearching: Boolean,
    error: String?,
    isPartialCount: Boolean,
): String? = when {
    query.isBlank() -> null
    isSearching -> "Searching…"
    error != null -> "Couldn't update results"
    total == 0 -> "No results"
    total == 1 -> "1 result"
    isPartialCount -> "$total+ results"
    else -> "$total results"
}

/*
 * Search stays a live discovery surface: the field and filters are pinned,
 * while the grid below is the only scrolling region. This keeps the user's
 * query visible and avoids racing the platform IME with scroll corrections.
 *
 * TV_SEARCH_QUERY_MAX_LENGTH lives in TvSearchInputRules.kt (shared with
 * TvRequestsScreen); a private duplicate here collided with it.
 */

private fun TvSearchMediaType.toRequestMediaType(): String = when (this) {
    TvSearchMediaType.All -> RequestMediaType.All
    TvSearchMediaType.Movies -> RequestMediaType.Movie
    TvSearchMediaType.Series -> RequestMediaType.Series
    TvSearchMediaType.Audiobooks -> RequestMediaType.Audiobook
}

private fun TvSearchMediaType.allowsRequestResult(item: RequestMediaResult): Boolean =
    when (this) {
        TvSearchMediaType.All -> true
        TvSearchMediaType.Movies -> item.mediaType == RequestMediaType.Movie
        TvSearchMediaType.Series -> item.mediaType == RequestMediaType.Series
        TvSearchMediaType.Audiobooks -> item.mediaType == RequestMediaType.Audiobook
    }

/**
 * Fires when this destination resumes — a Back out of a result, and also an
 * app foregrounding. Composition identity cannot answer this: a Back during
 * the outgoing transition can leave the screen composed.
 */
@Composable
private fun TvSearchResumeSignal(onResume: () -> Unit) {
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

/** How long a not-yet-loaded target waits on work already in flight. */
private const val TvSearchReturnPendingBudgetMillis: Long = 1_200L

/**
 * How long a return waits on a fetch that is genuinely still running before it
 * gives up and disarms. Long, because the wait itself is harmless and the only
 * thing it guards against is a request that never returns at all.
 */
private const val TvSearchReturnInFlightBudgetMillis: Long = 10_000L

/** How many times a return may stand down before it gives up for good. */
private const val TvSearchReturnMaxStandDowns: Int = 4

/** Focus must hold the card this long to count as arrived rather than passing. */
private const val TvSearchReturnSettleMillis: Long = 120L
