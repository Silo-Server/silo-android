package org.siloserver.silo.tv.ui.screens.boxset

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.siloserver.silo.common.ui.OnViewerAccessChanged
import org.siloserver.silo.domain.MediaActionsCoordinator
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.CatalogBoxSetDetail
import org.siloserver.silo.model.catalog.boxSetNextMovie
import org.siloserver.silo.model.catalog.boxSetResumePosition
import org.siloserver.silo.model.catalog.heroFacts
import org.siloserver.silo.model.catalog.playLabel
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.network.AccessChangeSignals
import org.siloserver.silo.tv.ui.components.TvCatalogEmptyState
import org.siloserver.silo.tv.ui.components.TvErrorScreen
import org.siloserver.silo.tv.ui.components.TvHeroFactToken
import org.siloserver.silo.tv.ui.components.TvLoadingScreen
import org.siloserver.silo.tv.ui.components.TvMediaRow
import org.siloserver.silo.tv.ui.components.TvPrimaryPillButton
import org.siloserver.silo.tv.ui.components.rememberAmbientBackdropTintState
import org.siloserver.silo.tv.ui.components.tvCardActions
import org.siloserver.silo.tv.ui.components.withMembershipChanges
import org.siloserver.silo.tv.ui.focus.TvContentInitialFocusMaxAttempts
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.tv.ui.screens.detail.TvDetailHero
import org.siloserver.silo.tv.ui.screens.watchparty.TvWatchPartyPlayGuardDialog
import org.siloserver.silo.tv.ui.screens.watchparty.rememberTvWatchPartyPlayGuard
import org.siloserver.silo.tv.ui.screens.detail.TvDetailHorizontalInset
import org.siloserver.silo.tv.ui.screens.detail.TvDetailPageBottomPadding
import org.siloserver.silo.tv.ui.screens.detail.TvDetailSectionHeader
import org.siloserver.silo.tv.ui.screens.detail.tvDetailPageSurfaceColor
import org.siloserver.silo.viewmodel.BoxSetViewModel

/**
 * One movie box set on TV: the movie detail hero (backdrop, title, facts,
 * synopsis) with Play on the next movie to watch, then the box set's movies
 * in release order on one row. Focus starts on Play; Down reaches the row
 * and Up returns to Play. Back from a movie lands on the card it opened.
 */
@Composable
fun TvBoxSetScreen(
    boxSetId: String,
    libraryId: String,
    onBack: () -> Unit,
    onPlayMovie: (contentId: String, resumePositionSeconds: Double?) -> Unit,
    onOpenMovie: (contentId: String) -> Unit,
    viewModel: BoxSetViewModel = koinViewModel(
        key = "box-set-$libraryId-$boxSetId",
        parameters = { parametersOf(boxSetId, libraryId) },
    ),
) {
    BackHandler(enabled = true) { onBack() }
    // D5: while this TV is in a Watch Party, Play first asks to leave the
    // party; leaving is never hidden behind a solo start.
    val watchPartyPlayGuard = rememberTvWatchPartyPlayGuard()
    val guardedPlayMovie: (String, Double?) -> Unit = { contentId, resumePositionSeconds ->
        watchPartyPlayGuard.requestPlay { onPlayMovie(contentId, resumePositionSeconds) }
    }
    TvWatchPartyPlayGuardDialog(watchPartyPlayGuard)
    val state by viewModel.uiState.collectAsState()
    val detail = state.detail
    when {
        detail != null -> BoxSetContent(detail, viewModel, guardedPlayMovie, onOpenMovie)
        state.unavailable -> TvCatalogEmptyState(
            message = "This box set isn't available. Box sets may be turned off for this library, " +
                "or too few of its movies are here.",
            modifier = Modifier.fillMaxSize(),
        )
        state.error != null -> TvErrorScreen(message = state.error.orEmpty(), onRetry = viewModel::retry)
        else -> TvLoadingScreen()
    }
}

@Composable
private fun BoxSetContent(
    detail: CatalogBoxSetDetail,
    viewModel: BoxSetViewModel,
    onPlayMovie: (contentId: String, resumePositionSeconds: Double?) -> Unit,
    onOpenMovie: (contentId: String) -> Unit,
) {
    val boxSet = detail.boxSet
    val next = boxSetNextMovie(detail.items)
    // Each movie's card menu marks it watched or adds it to a list, as on the
    // library grid; the view model moves the hero's count and play target.
    val coordinator: MediaActionsCoordinator = koinInject()
    val context = LocalContext.current.applicationContext
    val membershipActions by coordinator.memberships.actions.collectAsState()
    val movies = remember(detail.items, membershipActions) {
        detail.items.map { movie ->
            movie.toSectionItem().copy(
                userState = coordinator.withMembershipChanges(movie.contentId, movie.userState, membershipActions),
            )
        }
    }
    val cardActions = remember(coordinator, context, viewModel) {
        { movie: SectionItem ->
            coordinator.tvCardActions(
                contentId = movie.contentId,
                context = context,
                // The view model follows the write, so it runs as long as the view model does.
                scope = viewModel.viewModelScope,
                onWatchedChanged = { watched -> viewModel.onMovieWatchedChanged(movie.contentId, watched) },
                onWatchedWriteFailed = { viewModel.onMovieWatchedWriteFailed(movie.contentId) },
                onWatchedWriteSucceeded = { viewModel.onMovieWatchedWriteSucceeded(movie.contentId) },
            )
        }
    }
    val playFocus = remember { FocusRequester() }
    val movieReturnFocus = remember { FocusRequester() }
    val firstMovieFocus = remember { FocusRequester() }
    // The card a movie page was opened from, so Back lands on it again.
    var returnIndex by rememberSaveable { mutableIntStateOf(-1) }
    var restoreRequest by remember { mutableIntStateOf(0) }
    var playFocusRequest by remember { mutableIntStateOf(0) }
    var screenHasFocus by remember { mutableStateOf(false) }
    var playFocused by remember { mutableStateOf(false) }
    var focusedMovieIndex by remember { mutableIntStateOf(-1) }

    // A box set the viewer may no longer see shows as unavailable.
    OnViewerAccessChanged(koinInject<AccessChangeSignals>(), viewModel.accessChanges) { viewModel.refreshAfterAccessChange() }
    LifecycleResumeEffect(viewModel) {
        // Coming back from a movie re-reads watched state and the play target.
        viewModel.onScreenResumed()
        // Back from a movie's page lands on its card.
        if (returnIndex >= 0) restoreRequest += 1 else playFocusRequest += 1
        onPauseOrDispose {}
    }
    // Opening the box set, or coming back to it from the player, lands on
    // Play. Back from a movie's page lands on that movie's card instead.
    LaunchedEffect(Unit) {
        if (returnIndex >= 0 || next == null) return@LaunchedEffect
        requestFocusUntilObserved(
            maxAttempts = TvContentInitialFocusMaxAttempts,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = playFocus::requestFocus,
            isFocused = { playFocused },
        )
    }
    LaunchedEffect(playFocusRequest) {
        // A later resume puts focus on Play only if the page lost it; one that
        // finds focus on the page (back from the background, say) leaves it
        // where the viewer put it.
        if (playFocusRequest <= 1 || returnIndex >= 0 || next == null || screenHasFocus) return@LaunchedEffect
        requestFocusUntilObserved(
            maxAttempts = TvContentInitialFocusMaxAttempts,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = playFocus::requestFocus,
            isFocused = { playFocused },
        )
    }
    LaunchedEffect(restoreRequest) {
        if (restoreRequest == 0 || returnIndex < 0) return@LaunchedEffect
        val target = returnIndex
        // Retries while the row composes the target card.
        requestFocusUntilObserved(
            maxAttempts = TvContentInitialFocusMaxAttempts,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = movieReturnFocus::requestFocus,
            isFocused = { focusedMovieIndex == target },
        )
        returnIndex = -1
    }

    val tint = rememberAmbientBackdropTintState()
    LaunchedEffect(boxSet.backdropUrl) { tint.set(item = null, url = boxSet.backdropUrl) }
    val listState = rememberLazyListState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onFocusChanged { screenHasFocus = it.hasFocus }
            .background(tvDetailPageSurfaceColor(tint.accent)),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(bottom = TvDetailPageBottomPadding),
        ) {
            item(key = "hero", contentType = "detail-hero") {
                TvDetailHero(
                    title = boxSet.title,
                    seriesTitle = null,
                    logoUrl = null,
                    backdropUrl = boxSet.backdropUrl,
                    backdropThumbhash = boxSet.backdropThumbhash,
                    sourceTokens = emptyList(),
                    ratingChip = null,
                    overview = boxSet.overview,
                    tagline = null,
                    factsLine = (listOf("Box set") + boxSet.heroFacts()).map { TvHeroFactToken.TextToken(it) },
                    directorText = null,
                    actions = {
                        if (next != null) {
                            TvPrimaryPillButton(
                                stableHero = true,
                                icon = Icons.Filled.PlayArrow,
                                title = boxSet.playLabel(next),
                                onClick = { onPlayMovie(next.contentId, next.boxSetResumePosition()) },
                                focusRequester = playFocus,
                                modifier = Modifier
                                    .onFocusChanged { playFocused = it.hasFocus }
                                    // Down starts the row at its first movie, not
                                    // whichever card sits under the button.
                                    .focusProperties { down = firstMovieFocus },
                            )
                        }
                    },
                )
            }
            item(key = "movies", contentType = "box-set-movies") {
                Column(verticalArrangement = Arrangement.spacedBy(TvBoxSetRowHeaderGap)) {
                    TvDetailSectionHeader(
                        title = "Movies",
                        modifier = Modifier.padding(horizontal = TvDetailHorizontalInset),
                    )
                    TvMediaRow(
                        title = "Movies",
                        showHeader = false,
                        items = movies,
                        showProgress = true,
                        cardActions = cardActions,
                        onItemClick = { contentId ->
                            returnIndex = movies.indexOfFirst { it.contentId == contentId }
                            onOpenMovie(contentId)
                        },
                        upFocusRequester = playFocus.takeIf { next != null },
                        firstItemFocusRequester = firstMovieFocus,
                        restoreFocusIndex = returnIndex,
                        restoreFocusRequester = movieReturnFocus.takeIf { returnIndex >= 0 },
                        restoreFocusRequest = restoreRequest.takeIf { returnIndex >= 0 } ?: 0,
                        onItemFocusedAtIndex = { _, index -> focusedMovieIndex = index },
                        onRowFocusChanged = { focused -> if (!focused) focusedMovieIndex = -1 },
                    )
                }
            }
        }
    }
}

private val TvBoxSetRowHeaderGap = 20.dp

private fun BrowseItem.toSectionItem(): SectionItem = SectionItem(
    contentId = contentId,
    type = type,
    title = title,
    year = year,
    genres = genres,
    status = status,
    ratingImdb = ratingImdb,
    ratingTmdb = ratingTmdb,
    contentRating = contentRating,
    overlaySummary = overlaySummary,
    overview = overview,
    posterUrl = posterUrl,
    posterThumbhash = posterThumbhash,
    backdropUrl = backdropUrl,
    backdropThumbhash = backdropThumbhash,
    userState = userState,
    positionSeconds = positionSeconds,
    durationSeconds = durationSeconds,
)
