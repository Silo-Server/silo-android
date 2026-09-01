package org.siloserver.silo.android.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.siloserver.silo.model.catalog.EpisodeListItem
import org.siloserver.silo.model.catalog.Season
import kotlin.math.absoluteValue

/**
 * Shared season selector and episode pager used by series and episode details.
 * Loaded season pages come from the route-scoped ViewModel cache, so swiping
 * back to a season does not issue another episode request.
 */
@Composable
internal fun SeasonEpisodePager(
    seasons: List<Season>,
    selectedSeasonNumber: Int,
    episodes: List<EpisodeListItem>,
    episodesBySeason: Map<Int, List<EpisodeListItem>>,
    isLoadingEpisodes: Boolean,
    onSeasonSelected: (Int) -> Unit,
    onEpisodePlayClick: ((String, Double?) -> Unit)?,
    onEpisodeDetailClick: (String) -> Unit,
    onEpisodeWatchedChange: ((String, Boolean) -> Unit)? = null,
    highlightContentId: String? = null,
    showsSeasonSelector: Boolean = true,
    selectsCenteredEpisode: Boolean = false,
    allowsSeasonPaging: Boolean = true,
    /** Tablet/fold rail cards show title, date/runtime, and overview. */
    showsEpisodeDetails: Boolean = false,
    /** Tablet/fold rail selection follows taps rather than centre position. */
    tapToFocusEpisode: Boolean = false,
) {
    if (seasons.size <= 1 || !allowsSeasonPaging) {
        SeasonEpisodePage(
            episodes = episodes,
            isLoading = isLoadingEpisodes,
            onEpisodePlayClick = onEpisodePlayClick,
            onEpisodeDetailClick = onEpisodeDetailClick,
            onEpisodeWatchedChange = onEpisodeWatchedChange,
            highlightContentId = highlightContentId,
            selectsCenteredEpisode = selectsCenteredEpisode,
            showsEpisodeDetails = showsEpisodeDetails,
            tapToFocusEpisode = tapToFocusEpisode,
        )
        return
    }

    val initialPage = seasons.indexOfFirst { it.seasonNumber == selectedSeasonNumber }
        .coerceAtLeast(0)
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { seasons.size },
    )
    val scope = rememberCoroutineScope()
    val currentSelectedSeasonNumber = rememberUpdatedState(selectedSeasonNumber)
    val currentOnSeasonSelected = rememberUpdatedState(onSeasonSelected)

    // A completed finger swipe becomes the shared season selection. Waiting
    // for settledPage avoids loading a season when a partial drag snaps back.
    // Keep this collector alive when a chip optimistically changes the shared
    // selection: restarting it would immediately emit the still-old page and
    // undo the chip tap before the pager animation can begin.
    LaunchedEffect(pagerState, seasons) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                seasons.getOrNull(page)
                    ?.takeIf { it.seasonNumber != currentSelectedSeasonNumber.value }
                    ?.let { currentOnSeasonSelected.value(it.seasonNumber) }
            }
    }

    // Chip taps and ViewModel failure rollbacks drive the pager in the other
    // direction. targetPage guards against cancelling an in-flight animation.
    LaunchedEffect(selectedSeasonNumber, seasons) {
        val selectedPage = seasons.indexOfFirst { it.seasonNumber == selectedSeasonNumber }
        if (selectedPage >= 0 && pagerState.targetPage != selectedPage) {
            pagerState.animateScrollToPage(selectedPage)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (showsSeasonSelector) {
            SeasonChips(
                seasons = seasons,
                selectedSeasonNumber = selectedSeasonNumber,
                onSeasonSelected = { seasonNumber ->
                    val page = seasons.indexOfFirst { it.seasonNumber == seasonNumber }
                    onSeasonSelected(seasonNumber)
                    if (page >= 0 && pagerState.targetPage != page) {
                        scope.launch { pagerState.animateScrollToPage(page) }
                    }
                },
            )
        }

        // A pager with an unconstrained height sizes itself to the TALLEST page
        // it has composed — with the neighbours kept alive, a long season next
        // to a short one left the short season floating over empty space.
        // Measure each page's real content height (unbounded, so a page taller
        // than the pager still reports its full size) and size the pager to
        // the current page, animated so season switches slide rather than jump.
        val density = LocalDensity.current
        val pageHeightsPx = remember(seasons) { mutableStateMapOf<Int, Int>() }
        val currentPageHeightPx = pageHeightsPx[pagerState.currentPage]
        val pagerHeight by animateDpAsState(
            targetValue = with(density) { (currentPageHeightPx ?: 0).toDp() },
            animationSpec = tween(durationMillis = 260),
            label = "seasonPagerHeight",
        )

        HorizontalPager(
            state = pagerState,
            key = { page -> seasons[page].contentId },
            beyondViewportPageCount = 1,
            verticalAlignment = Alignment.Top,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (currentPageHeightPx != null) Modifier.height(pagerHeight) else Modifier),
        ) { page ->
            val season = seasons[page]
            val cachedEpisodes = episodesBySeason[season.seasonNumber]
                ?: episodes.takeIf { season.seasonNumber == selectedSeasonNumber }
            val pageOffset = (
                (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                ).absoluteValue.coerceIn(0f, 1f)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(align = Alignment.Top, unbounded = true),
            ) {
                SeasonEpisodePage(
                    episodes = cachedEpisodes.orEmpty(),
                    isLoading = cachedEpisodes == null &&
                        (season.seasonNumber != selectedSeasonNumber || isLoadingEpisodes),
                    onEpisodePlayClick = onEpisodePlayClick,
                    onEpisodeDetailClick = onEpisodeDetailClick,
                    onEpisodeWatchedChange = onEpisodeWatchedChange,
                    highlightContentId = highlightContentId,
                    selectsCenteredEpisode = selectsCenteredEpisode,
                    showsEpisodeDetails = showsEpisodeDetails,
                    tapToFocusEpisode = tapToFocusEpisode,
                    modifier = Modifier
                        .onSizeChanged { pageHeightsPx[page] = it.height }
                        .graphicsLayer {
                            alpha = 1f - (pageOffset * 0.18f)
                            scaleX = 1f - (pageOffset * 0.015f)
                            scaleY = 1f - (pageOffset * 0.015f)
                        },
                )
            }
        }
    }
}

@Composable
private fun SeasonEpisodePage(
    episodes: List<EpisodeListItem>,
    isLoading: Boolean,
    onEpisodePlayClick: ((String, Double?) -> Unit)?,
    onEpisodeDetailClick: (String) -> Unit,
    onEpisodeWatchedChange: ((String, Boolean) -> Unit)?,
    highlightContentId: String?,
    selectsCenteredEpisode: Boolean,
    showsEpisodeDetails: Boolean,
    tapToFocusEpisode: Boolean,
    modifier: Modifier = Modifier,
) {
    when {
        isLoading -> {
            EpisodeListSkeleton(
                showsEpisodeDetails = showsEpisodeDetails,
                modifier = modifier,
            )
        }
        episodes.isEmpty() -> {
            Text(
                text = "No episodes available",
                style = MaterialTheme.typography.bodySmall,
                color = DetailTertiaryText,
                modifier = modifier.padding(horizontal = SafePadding),
            )
        }
        else -> {
            EpisodeList(
                episodes = episodes,
                onEpisodePlayClick = onEpisodePlayClick,
                onEpisodeDetailClick = onEpisodeDetailClick,
                onEpisodeWatchedChange = onEpisodeWatchedChange,
                highlightContentId = highlightContentId,
                selectsCenteredEpisode = selectsCenteredEpisode,
                showsEpisodeDetails = showsEpisodeDetails,
                tapToFocusEpisode = tapToFocusEpisode,
                modifier = modifier,
            )
        }
    }
}
