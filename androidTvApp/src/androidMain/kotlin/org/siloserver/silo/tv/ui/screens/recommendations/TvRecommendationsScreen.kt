package org.siloserver.silo.tv.ui.screens.recommendations

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.koin.compose.viewmodel.koinViewModel
import org.siloserver.silo.tv.ui.components.TvErrorScreen
import org.siloserver.silo.tv.ui.components.TvLoadingScreen
import org.siloserver.silo.tv.ui.components.TvRowStyle
import org.siloserver.silo.tv.ui.components.TvSkylineSectionFeed
import org.siloserver.silo.tv.ui.focus.TvContentInitialFocusMaxAttempts
import org.siloserver.silo.tv.ui.focus.TvObservedFocusResult
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.tv.ui.screens.personal.TvFavoritesInline
import org.siloserver.silo.tv.ui.screens.personal.TvWatchlistInline
import org.siloserver.silo.tv.ui.shell.TvTopMenuLayout
import org.siloserver.silo.tv.ui.theme.Spacing
import org.siloserver.silo.tv.ui.util.visibleOnTv
import org.siloserver.silo.viewmodel.RecommendationsViewModel

/** Saved-list grids arrive a page at a time; pace the claim to that, not to frames. */
private const val SavedListFocusRetryDelayMillis = 60L

/** Room for the fallback caption above the saved-list grid, when it is showing. */
private val SavedListCaptionInset = 34.dp

/**
 * "For You" tab. Reuses the shared [RecommendationsViewModel] that drives the
 * phone `/recommendations/discover` feed, and renders it through the same
 * `TvSkylineSectionFeed` as Home — focus marquee, ambient backdrop, row band —
 * so the two landing surfaces stay identical.
 *
 * The list switch (For You / Watchlist / Favorites) lives in the top-menu
 * dropdown, mirroring tvOS `.recommendations`; this screen only renders the
 * selection it is handed through [entryRequest].
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvRecommendationsScreen(
    onSavedListItemClick: (contentId: String) -> Unit,
    onRecommendationItemClick: (contentId: String) -> Unit,
    onInitialContentFocus: () -> Unit = {},
    focusRequest: Int = 0,
    detailReturnFocusRequest: Int = 0,
    detailReturnCardFocusRequester: FocusRequester? = null,
    firstRowFocusRequester: FocusRequester? = null,
    firstRowContainerFocusRequester: FocusRequester? = null,
    onContentUpFallbackChanged: ((((Boolean) -> Boolean)?) -> Unit)? = null,
    entryRequest: TvForYouEntryRequest = TvForYouEntryRequest(),
    viewModel: RecommendationsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val visibleSections = remember(state.sections) { state.sections.visibleOnTv() }
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
    var savedListSelection by rememberSaveable { mutableStateOf(entryRequest.selection) }
    // True only when the saved list is showing because recommendations came
    // back empty (the auto-fallback below), not because the user picked
    // Favorites/Watchlist from the dropdown. The explanatory caption is keyed
    // on this rather than on "no visible sections", which is also true while
    // the feed is still loading on first open.
    var savedListIsFallback by rememberSaveable { mutableStateOf(false) }
    var lastAppliedEntrySequence by rememberSaveable { mutableIntStateOf(0) }
    val savedListFocusRequester = remember { FocusRequester() }
    var forYouContentHasFocus by remember { mutableStateOf(false) }
    // The Skyline feed already owns the menu→content entry move (band scrolled
    // to the top, focus on row 0 / card 0). Picking "For You" in the dropdown
    // while a saved list is showing is that same move, so add our own bumps to
    // the shell's token rather than hand-rolling a second row-container hop.
    var feedEntryFocusRequest by rememberSaveable { mutableIntStateOf(0) }

    suspend fun claimSavedListFocus() {
        val landed = requestFocusUntilObserved(
            maxAttempts = TvContentInitialFocusMaxAttempts,
            awaitAttempt = { delay(SavedListFocusRetryDelayMillis) },
            requestFocus = savedListFocusRequester::requestFocus,
            isFocused = { forYouContentHasFocus },
        )
        // Only report the handover once focus is confirmed: telling the shell
        // content owns focus after a dropped claim leaves nothing focused.
        if (landed == TvObservedFocusResult.Focused) onInitialContentFocus()
    }

    LaunchedEffect(entryRequest.sequence) {
        val applied = applyForYouEntryRequest(
            currentSelection = savedListSelection,
            lastAppliedSequence = lastAppliedEntrySequence,
            request = entryRequest,
        )
        savedListSelection = applied.selection
        lastAppliedEntrySequence = applied.lastAppliedSequence
        if (!applied.appliedRequest) return@LaunchedEffect
        savedListIsFallback = false
        if (applied.selection == null) {
            feedEntryFocusRequest++
        } else {
            claimSavedListFocus()
        }
    }

    // Match tvOS: recommendations remain the landing content when available;
    // an empty successful response defaults to the inline Watchlist fallback.
    LaunchedEffect(state.isLoading, state.error, visibleSections) {
        if (!state.isLoading && state.error == null && visibleSections.isEmpty() && savedListSelection == null) {
            savedListSelection = SavedListSelection.Watchlist
            savedListIsFallback = true
        } else if (visibleSections.isNotEmpty()) {
            savedListIsFallback = false
        }
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    // Menu→content handover for the saved lists only; the feed answers the same
    // token itself. Guarded so a later recomposition (or the return from a
    // detail page, where the shell's restorer owns focus) cannot replay it.
    var lastAppliedFocusRequest by rememberSaveable { mutableIntStateOf(-1) }
    LaunchedEffect(focusRequest, savedListSelection) {
        if (savedListSelection == null) return@LaunchedEffect
        if (focusRequest == lastAppliedFocusRequest) return@LaunchedEffect
        lastAppliedFocusRequest = focusRequest
        // The shell bumps its token for EVERY menu selection, and during the
        // route crossfade this exiting screen is still composed — without this
        // gate, selecting Home from a Watchlist/Favorites view let the saved
        // list claim focus (its first card or Sort/Filter pill) instead of
        // Home's first row. Same gate as TvSkylineSectionFeed: exiting nav
        // entries fall to STARTED and never resume, so they park here until
        // disposal with the token already consumed.
        lifecycleOwner.lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        claimSavedListFocus()
    }

    // TV has no pull-to-refresh, so ON_RESUME is the only quiet self-heal path.
    // The shared VM loads once in init and retains its state across tab swaps, so
    // an empty discover response otherwise leaves this tab a permanent dead end
    // until a profile switch/restart. If the feed is still the empty fallback
    // when the user returns (e.g. after watching and rating content), re-check.
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

    val showFallbackCaption = savedListSelection != null && savedListIsFallback
    val savedListTopInset = TvTopMenuLayout.contentTopInset +
        if (showFallbackCaption) SavedListCaptionInset else 0.dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onFocusChanged { forYouContentHasFocus = it.hasFocus },
    ) {
        when {
            savedListSelection == SavedListSelection.Watchlist -> TvWatchlistInline(
                onItemClick = onSavedListItemClick,
                firstItemFocusRequester = savedListFocusRequester,
                modifier = Modifier.padding(top = savedListTopInset),
            )
            savedListSelection == SavedListSelection.Favorites -> TvFavoritesInline(
                onItemClick = onSavedListItemClick,
                firstItemFocusRequester = savedListFocusRequester,
                modifier = Modifier.padding(top = savedListTopInset),
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
            else -> TvSkylineSectionFeed(
                surfaceKey = "for_you",
                sections = visibleSections,
                onItemClick = onRecommendationItemClick,
                // Both tokens are monotonic, so their sum is too — which is all
                // the feed's "did this request already apply" guard needs.
                focusRequest = focusRequest + feedEntryFocusRequest,
                detailReturnFocusRequest = detailReturnFocusRequest,
                detailReturnCardFocusRequester = detailReturnCardFocusRequester,
                firstRowFocusRequester = firstRowFocusRequester,
                firstRowContainerRequester = firstRowContainerFocusRequester,
                onInitialContentFocus = onInitialContentFocus,
                onContentUpFallbackChanged = onContentUpFallbackChanged,
                // Discover returns plain section rows: posters throughout, no
                // progress bars, and the VM exposes no watched/favorite toggles.
                styleForSection = { TvRowStyle.Poster },
                showProgressForSection = { false },
            )
        }

        if (showFallbackCaption) {
            Text(
                text = "No recommendations yet — showing your saved titles.",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 16.sp,
                    lineHeight = 20.sp,
                ),
                color = Color.White.copy(alpha = 0.75f),
                modifier = Modifier.padding(
                    start = Spacing.safeArea,
                    top = TvTopMenuLayout.contentTopInset,
                ),
            )
        }
    }
}
