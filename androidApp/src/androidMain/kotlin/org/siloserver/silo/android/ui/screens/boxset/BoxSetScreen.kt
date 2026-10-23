package org.siloserver.silo.android.ui.screens.boxset

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.siloserver.silo.android.ui.components.EmptyStateView
import org.siloserver.silo.android.ui.components.ErrorView
import org.siloserver.silo.android.ui.components.MediaCard
import org.siloserver.silo.android.ui.components.MediaGridDefaults
import org.siloserver.silo.android.ui.components.rememberBrowseItemCardActions
import org.siloserver.silo.android.ui.screens.detail.AdaptiveDetailHero
import org.siloserver.silo.android.ui.screens.detail.DetailLoadingSkeleton
import org.siloserver.silo.android.ui.screens.detail.DetailPageSurface
import org.siloserver.silo.android.ui.screens.detail.DetailPortraitArtwork
import org.siloserver.silo.android.ui.screens.detail.DetailScrollState
import org.siloserver.silo.android.ui.screens.detail.DetailTopChrome
import org.siloserver.silo.android.ui.screens.detail.HeaderSettledDp
import org.siloserver.silo.android.ui.screens.detail.PrimaryPillButton
import org.siloserver.silo.android.ui.screens.detail.SectionHeader
import org.siloserver.silo.android.ui.screens.watchparty.WatchPartySoloGuardDialog
import org.siloserver.silo.android.ui.screens.watchparty.rememberWatchPartySoloGuard
import org.siloserver.silo.android.ui.theme.SiloBackground
import org.siloserver.silo.android.ui.util.rememberDominantColor
import org.siloserver.silo.common.ui.components.DeferImagePresentationWhileScrolling
import org.siloserver.silo.model.catalog.BOX_SET_TYPE
import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.CatalogBoxSetDetail
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.boxSetNextMovie
import org.siloserver.silo.model.catalog.boxSetResumePosition
import org.siloserver.silo.model.catalog.heroFacts
import org.siloserver.silo.model.catalog.playLabel
import org.siloserver.silo.overlays.OverlayDataExtractor
import org.siloserver.silo.viewmodel.BoxSetViewModel

/**
 * One movie box set: the movie detail hero (backdrop, poster, title, facts,
 * overview) with a Play button for the next movie to watch, then the box
 * set's movies in release order.
 */
@Composable
fun BoxSetScreen(
    viewModel: BoxSetViewModel,
    onBackClick: () -> Unit,
    onPlayMovie: (contentId: String, resumePositionSeconds: Double?) -> Unit,
    onMovieClick: (contentId: String) -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    // D5: while this device is in a Watch Party, Play asks to leave the party
    // first (the player is shared), as on a movie's own page.
    val soloGuard = rememberWatchPartySoloGuard()
    val guardedPlayMovie: (String, Double?) -> Unit = { contentId, resumePositionSeconds ->
        soloGuard.run { onPlayMovie(contentId, resumePositionSeconds) }
    }
    // Coming back from a movie re-reads watched state and the play target.
    LifecycleResumeEffect(viewModel) {
        viewModel.onScreenResumed()
        onPauseOrDispose {}
    }
    val detailScroll = remember { DetailScrollState() }
    val detail = state.detail
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        when {
            detail != null -> BoxSetContent(
                detail = detail,
                detailScroll = detailScroll,
                onPlayMovie = guardedPlayMovie,
                onMovieClick = onMovieClick,
                onMovieWatchedChanged = viewModel::onMovieWatchedChanged,
                onMovieWatchedWriteFailed = viewModel::onMovieWatchedWriteFailed,
            )
            state.unavailable -> EmptyStateView(
                title = "This box set isn't available",
                subtitle = "Box sets may be turned off for this library, or too few of its movies are here.",
                icon = Icons.Outlined.VideoLibrary,
                modifier = Modifier.fillMaxSize(),
            )
            state.error != null -> ErrorView(
                message = state.error.orEmpty(),
                onRetry = viewModel::retry,
                modifier = Modifier.fillMaxSize(),
            )
            else -> DetailLoadingSkeleton(
                modifier = Modifier.fillMaxSize(),
                compactArtworkHeightFactor = BoxSetArtworkHeightFactor,
            )
        }
        DetailTopChrome(
            title = detail?.boxSet?.title.orEmpty(),
            scroll = detailScroll,
            onBackClick = onBackClick,
        )
    }
    WatchPartySoloGuardDialog(soloGuard)
}

@Composable
private fun BoxSetContent(
    detail: CatalogBoxSetDetail,
    detailScroll: DetailScrollState,
    onPlayMovie: (contentId: String, resumePositionSeconds: Double?) -> Unit,
    onMovieClick: (contentId: String) -> Unit,
    onMovieWatchedChanged: (contentId: String, watched: Boolean) -> Unit,
    onMovieWatchedWriteFailed: (contentId: String) -> Unit,
) {
    val boxSet = detail.boxSet
    // The movie hero reads an item detail; a box set fills the fields it draws.
    val hero = remember(boxSet) {
        ItemDetail(
            contentId = "$BOX_SET_TYPE:${boxSet.id}",
            type = "movie",
            title = boxSet.title,
            overview = boxSet.overview,
            posterUrl = boxSet.posterUrl,
            posterThumbhash = boxSet.posterThumbhash,
            backdropUrl = boxSet.backdropUrl,
            backdropThumbhash = boxSet.backdropThumbhash,
        )
    }
    val next = boxSetNextMovie(detail.items)
    val dominantColor by rememberDominantColor(
        imageUrl = boxSet.backdropUrl,
        fallback = SiloBackground,
        thumbhash = boxSet.backdropThumbhash,
    )
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    LaunchedEffect(listState, detailScroll, density) {
        snapshotFlow {
            if (listState.firstVisibleItemIndex > 0) {
                HeaderSettledDp
            } else {
                with(density) { listState.firstVisibleItemScrollOffset.toDp().value }
            }
        }.collect { detailScroll.update(it) }
    }
    DetailPageSurface(
        backdropUrl = boxSet.backdropUrl,
        backdropThumbhash = boxSet.backdropThumbhash,
        tint = dominantColor,
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val columns = memberGridColumns(maxWidth, MediaGridDefaults.scaledPosterGridMinWidth)
            val cellWidth = (maxWidth - GridGutter * 2 - MediaGridDefaults.PosterGridHorizontalSpacing * (columns - 1)) / columns
            val rows = remember(detail.items, columns) { detail.items.chunked(columns) }
            DeferImagePresentationWhileScrolling(listState) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    item(contentType = "detail-hero") {
                        AdaptiveDetailHero(
                            detail = hero,
                            eyebrow = "Box set",
                            sourceTokens = emptyList(),
                            factsLine = boxSet.heroFacts(),
                            portraitArtwork = DetailPortraitArtwork(url = boxSet.posterUrl, thumbhash = boxSet.posterThumbhash),
                            dominantColor = dominantColor,
                            overviewText = boxSet.overview,
                            compactEyebrow = true,
                            compactArtworkHeightFactor = BoxSetArtworkHeightFactor,
                        ) {
                            if (next != null) {
                                PrimaryPillButton(
                                    icon = Icons.Filled.PlayArrow,
                                    label = boxSet.playLabel(next),
                                    onClick = { onPlayMovie(next.contentId, next.boxSetResumePosition()) },
                                )
                            }
                        }
                    }
                    item(contentType = "box-set-movies-header") {
                        SectionHeader(
                            title = "Movies",
                            modifier = Modifier.padding(top = 20.dp, bottom = 14.dp),
                        )
                    }
                    items(rows, contentType = { "box-set-movies-row" }) { row ->
                        MemberRow(row, columns, cellWidth, onMovieClick, onMovieWatchedChanged, onMovieWatchedWriteFailed)
                    }
                    item(contentType = "detail-spacer") {
                        Spacer(modifier = Modifier.height(40.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun MemberRow(
    row: List<BrowseItem>,
    columns: Int,
    cellWidth: Dp,
    onMovieClick: (contentId: String) -> Unit,
    onMovieWatchedChanged: (contentId: String, watched: Boolean) -> Unit,
    onMovieWatchedWriteFailed: (contentId: String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = GridGutter)
            .padding(bottom = MediaGridDefaults.PosterGridVerticalSpacing),
        horizontalArrangement = Arrangement.spacedBy(MediaGridDefaults.PosterGridHorizontalSpacing),
    ) {
        row.forEach { item ->
            // A failed write puts the movie back as it was, resume position
            // included, in the view model the hero and this card both follow.
            val (cardActions, userState, _) = rememberBrowseItemCardActions(
                item,
                onWatchedWriteFailed = { onMovieWatchedWriteFailed(item.contentId) },
            )
            // Marking a movie watched here moves the hero's count and play target too.
            val actions = cardActions.copy(
                onSetWatched = cardActions.onSetWatched?.let { setWatched ->
                    { watched: Boolean ->
                        setWatched(watched)
                        onMovieWatchedChanged(item.contentId, watched)
                    }
                },
            )
            MediaCard(
                title = item.title,
                posterUrl = item.posterUrl,
                posterThumbhash = item.posterThumbhash,
                year = item.year,
                type = item.type,
                userState = userState,
                progress = movieProgress(item),
                onClick = { onMovieClick(item.contentId) },
                width = cellWidth,
                overlay = OverlayDataExtractor.fromBrowseItem(item),
                actions = actions,
                detailBackdropUrl = item.backdropUrl,
                detailBackdropThumbhash = item.backdropThumbhash,
            )
        }
        repeat(columns - row.size) { Spacer(Modifier.width(cellWidth)) }
    }
}

private val GridGutter = 16.dp

/**
 * A shorter backdrop than a title's on a compact phone, so the box set's
 * movies start on the first screen. Matches the Apple box set screen.
 */
private const val BoxSetArtworkHeightFactor = 0.62f

/** As many columns of at least [minCell] as fit, like the adaptive catalog grid. */
private fun memberGridColumns(width: Dp, minCell: Dp): Int {
    val available = width - GridGutter * 2 + MediaGridDefaults.PosterGridHorizontalSpacing
    return (available / (minCell + MediaGridDefaults.PosterGridHorizontalSpacing)).toInt().coerceAtLeast(1)
}

private fun movieProgress(item: BrowseItem): Float? {
    val position = item.boxSetResumePosition() ?: return null
    val duration = item.durationSeconds?.takeIf { it > 0 } ?: return null
    return (position / duration).toFloat()
}
