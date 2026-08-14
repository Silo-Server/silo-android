package org.siloserver.silo.android.ui.screens.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
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
    onEpisodePlayClick: (String, Double?) -> Unit,
    onEpisodeDetailClick: (String) -> Unit,
    onEpisodeDownloadClick: ((EpisodeListItem) -> Unit)?,
    episodeDownloadState: (EpisodeListItem) -> DetailDownloadState,
    highlightContentId: String? = null,
) {
    if (seasons.size <= 1) {
        SeasonEpisodePage(
            episodes = episodes,
            isLoading = isLoadingEpisodes,
            onEpisodePlayClick = onEpisodePlayClick,
            onEpisodeDetailClick = onEpisodeDetailClick,
            onEpisodeDownloadClick = onEpisodeDownloadClick,
            episodeDownloadState = episodeDownloadState,
            highlightContentId = highlightContentId,
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

        HorizontalPager(
            state = pagerState,
            key = { page -> seasons[page].contentId },
            beyondViewportPageCount = 1,
            verticalAlignment = Alignment.Top,
            modifier = Modifier.fillMaxWidth(),
        ) { page ->
            val season = seasons[page]
            val cachedEpisodes = episodesBySeason[season.seasonNumber]
                ?: episodes.takeIf { season.seasonNumber == selectedSeasonNumber }
            val pageOffset = (
                (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                ).absoluteValue.coerceIn(0f, 1f)

            SeasonEpisodePage(
                episodes = cachedEpisodes.orEmpty(),
                isLoading = cachedEpisodes == null &&
                    (season.seasonNumber != selectedSeasonNumber || isLoadingEpisodes),
                onEpisodePlayClick = onEpisodePlayClick,
                onEpisodeDetailClick = onEpisodeDetailClick,
                onEpisodeDownloadClick = onEpisodeDownloadClick,
                episodeDownloadState = episodeDownloadState,
                highlightContentId = highlightContentId,
                modifier = Modifier.graphicsLayer {
                    alpha = 1f - (pageOffset * 0.18f)
                    scaleX = 1f - (pageOffset * 0.015f)
                    scaleY = 1f - (pageOffset * 0.015f)
                },
            )
        }
    }
}

@Composable
private fun SeasonEpisodePage(
    episodes: List<EpisodeListItem>,
    isLoading: Boolean,
    onEpisodePlayClick: (String, Double?) -> Unit,
    onEpisodeDetailClick: (String) -> Unit,
    onEpisodeDownloadClick: ((EpisodeListItem) -> Unit)?,
    episodeDownloadState: (EpisodeListItem) -> DetailDownloadState,
    highlightContentId: String?,
    modifier: Modifier = Modifier,
) {
    when {
        isLoading -> {
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
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
                onEpisodeDownloadClick = onEpisodeDownloadClick,
                episodeDownloadState = episodeDownloadState,
                highlightContentId = highlightContentId,
                modifier = modifier,
            )
        }
    }
}
