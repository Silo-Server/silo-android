package com.continuum.app.android.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.continuum.app.android.ui.theme.ContinuumBackground
import com.continuum.app.android.ui.util.rememberDominantColor
import com.continuum.app.model.catalog.EpisodeListItem
import com.continuum.app.model.catalog.ItemDetail
import com.continuum.app.model.catalog.Season

/**
 * Phone series detail. Cinematic backdrop hero up top, then a scrollable
 * body of season chips + episode list, cast, and the details list.
 */
@Composable
fun SeriesDetailContent(
    detail: ItemDetail,
    seasons: List<Season>,
    selectedSeasonNumber: Int,
    episodes: List<EpisodeListItem>,
    isLoadingEpisodes: Boolean,
    isFavorite: Boolean,
    isInWatchlist: Boolean,
    nextEpisodeLabel: String?,
    onPlayClick: () -> Unit,
    onEpisodePlayClick: (String, Double?) -> Unit,
    onEpisodeDetailClick: (String) -> Unit,
    onSeasonSelected: (Int) -> Unit,
    onFavoriteClick: () -> Unit,
    onWatchlistClick: () -> Unit,
    onToggleWatched: () -> Unit,
    userRating: Int? = null,
    onSetRating: (Int) -> Unit = {},
    onClearRating: () -> Unit = {},
    onPersonClick: (String) -> Unit,
    onItemDetailClick: (String) -> Unit,
    onSeriesDownloadClick: (() -> Unit)? = null,
    onSeasonDownloadClick: ((Int) -> Unit)? = null,
    onEpisodeDownloadClick: ((EpisodeListItem) -> Unit)? = null,
    episodeDownloadState: (EpisodeListItem) -> DetailDownloadState = { DetailDownloadState() },
    /** Series-level roll-up across ALL seasons: isDownloaded when every episode
     *  is downloaded, progress = downloaded/total fraction while partial. */
    seriesDownloadState: DetailDownloadState = DetailDownloadState(),
    onWatchTogether: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val dominantColor by rememberDominantColor(detail.backdropUrl, fallback = ContinuumBackground)
    var showRatingSheet by remember { mutableStateOf(false) }

    val eyebrow = HeroMetadata.seriesEyebrow(detail)
    val sourceTokens = HeroMetadata.seriesSourceTokens(detail)
    val factsLine = HeroMetadata.seriesFactsLine(detail)

    val selectedSeason = seasons.firstOrNull { it.seasonNumber == selectedSeasonNumber }
    val episodeCountSubtitle = selectedSeason?.episodeCount?.takeIf { it > 0 }?.let { count ->
        "$count episode${if (count == 1) "" else "s"}"
    }

    // iOS below-fold section spacing is 36 (hero→first section 32). Use 36
    // uniformly — the closest single-value match to the iOS column rhythm.
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(ContinuumBackground)
            .background(detailScreenBackgroundBrush(dominantColor)),
        verticalArrangement = Arrangement.spacedBy(36.dp),
    ) {
        item(contentType = "detail-hero") {
            DetailHero(
                detail = detail,
                eyebrow = eyebrow,
                sourceTokens = sourceTokens,
                factsLine = factsLine,
                dominantColor = dominantColor,
            ) {
                HeroActionStack(
                    primaryLabel = computePlayLabel(detail, nextEpisodeLabel),
                    onPlay = onPlayClick,
                    isFavorite = isFavorite,
                    isInWatchlist = isInWatchlist,
                    isWatched = detail.userData?.played == true,
                    onToggleFavorite = onFavoriteClick,
                    onToggleWatchlist = onWatchlistClick,
                    onToggleWatched = onToggleWatched,
                    userRating = userRating,
                    onRateClick = { showRatingSheet = true },
                    overflow = if (onWatchTogether != null) {
                        { dismiss ->
                            DropdownMenuItem(
                                text = { Text("Watch Together") },
                                leadingIcon = {
                                    Icon(Icons.Outlined.Groups, contentDescription = null)
                                },
                                onClick = {
                                    dismiss()
                                    onWatchTogether()
                                },
                            )
                        }
                    } else {
                        null
                    },
                    downloadSlot = onSeriesDownloadClick?.let { click ->
                        {
                            DownloadCircleButton(
                                isDownloaded = seriesDownloadState.isDownloaded,
                                progress = seriesDownloadState.progress,
                                onClick = click,
                            )
                        }
                    },
                )
            }
        }

        item(contentType = "detail-episodes") {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    SectionHeader(
                        label = selectedSeason?.let { "Season ${it.seasonNumber}" } ?: "Episodes",
                        title = "Episodes",
                        trailingText = episodeCountSubtitle,
                        modifier = Modifier.weight(1f),
                    )
                    // "Download season N" — only when a season is selected
                    // AND the parent screen wired the callback.
                    val seasonNumberForDownload = selectedSeason?.seasonNumber
                    if (seasonNumberForDownload != null && onSeasonDownloadClick != null && episodes.isNotEmpty()) {
                        // Roll up ONLY the episodes of the selected season (the
                        // loaded `episodes` can briefly be the previous season's
                        // during a season switch — don't claim ✓ off stale data).
                        val seasonEpisodes = episodes.filter { it.seasonNumber == seasonNumberForDownload }
                        val states = seasonEpisodes.map { episodeDownloadState(it) }
                        val allDownloaded = seasonEpisodes.isNotEmpty() && states.all { it.isDownloaded }
                        val anyInFlight = states.any { it.progress != null }
                        IconButton(
                            onClick = { onSeasonDownloadClick(seasonNumberForDownload) },
                            modifier = Modifier
                                .padding(end = SafePadding)
                                .size(40.dp),
                        ) {
                            when {
                                allDownloaded -> Icon(
                                    imageVector = Icons.Filled.DownloadDone,
                                    contentDescription = "Season $seasonNumberForDownload downloaded",
                                    tint = DetailPrimaryText,
                                    modifier = Modifier.size(24.dp),
                                )
                                anyInFlight -> CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    strokeWidth = 2.dp,
                                    color = DetailPrimaryText,
                                )
                                else -> Icon(
                                    imageVector = Icons.Outlined.FileDownload,
                                    contentDescription = "Download season $seasonNumberForDownload",
                                    tint = DetailPrimaryText,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                    }
                }
                if (seasons.size > 1) {
                    SeasonChips(
                        seasons = seasons,
                        selectedSeasonNumber = selectedSeasonNumber,
                        onSeasonSelected = onSeasonSelected,
                    )
                }
                when {
                    isLoadingEpisodes -> {
                        Box(
                            modifier = Modifier
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
                            modifier = Modifier.padding(horizontal = SafePadding),
                        )
                    }
                    else -> {
                        EpisodeList(
                            episodes = episodes,
                            onEpisodePlayClick = onEpisodePlayClick,
                            onEpisodeDetailClick = onEpisodeDetailClick,
                            onEpisodeDownloadClick = onEpisodeDownloadClick,
                            episodeDownloadState = episodeDownloadState,
                        )
                    }
                }
            }
        }

        if (detail.cast.isNotEmpty()) {
            item(contentType = "detail-cast") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionHeader(label = "Cast", title = "& Crew")
                    CastCrewSection(
                        cast = detail.cast,
                        crew = detail.crew,
                        onPersonClick = onPersonClick,
                    )
                }
            }
        }

        if (detail.genres.isNotEmpty()) {
            item(contentType = "detail-genres") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionHeader(label = "Tags", title = "Genres")
                    GenrePillRow(genres = detail.genres)
                }
            }
        }

        item(contentType = "detail-facts") {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SectionHeader(label = "Info", title = "Details")
                DetailFactsList(detail = detail)
            }
        }

        item(contentType = "detail-similar") {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SectionHeader(label = "Recommended", title = "More Like This")
                SimilarRail(
                    contentId = detail.contentId,
                    onSelect = onItemDetailClick,
                )
            }
        }

        item(contentType = "detail-spacer") {
            Spacer(modifier = Modifier.height(40.dp))
        }
    }

    if (showRatingSheet) {
        RatingSheet(
            currentRating = userRating,
            onSetRating = { stars ->
                onSetRating(stars)
                showRatingSheet = false
            },
            onClearRating = {
                onClearRating()
                showRatingSheet = false
            },
            onDismiss = { showRatingSheet = false },
        )
    }
}
