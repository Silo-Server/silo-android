package org.siloserver.silo.android.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.android.ui.theme.SiloBackground
import org.siloserver.silo.android.ui.util.rememberDominantColor
import org.siloserver.silo.model.catalog.EpisodeListItem
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.Season
import androidx.compose.foundation.lazy.rememberLazyListState
import org.siloserver.silo.common.ui.components.DeferImagePresentationWhileScrolling

/**
 * Phone series detail. Cinematic backdrop hero up top, then a scrollable
 * body of season chips + episode list, cast, and the details list.
 */
@Composable
fun SeriesDetailContent(
    detail: ItemDetail,
    similarItems: List<ItemDetail> = emptyList(),
    seasons: List<Season>,
    selectedSeasonNumber: Int,
    episodes: List<EpisodeListItem>,
    episodesBySeason: Map<Int, List<EpisodeListItem>>,
    isLoadingEpisodes: Boolean,
    isFavorite: Boolean,
    isInWatchlist: Boolean,
    nextEpisodeLabel: String?,
    selectedEpisodeContentId: String?,
    selectedEpisodeDetail: ItemDetail?,
    isLoadingSelectedEpisodeDetail: Boolean,
    selectedVersionIndex: Int,
    isAutoVersion: Boolean,
    selectedAudioIndex: Int?,
    selectedSubtitleIndex: Int?,
    onVersionSelected: (Int?) -> Unit,
    onAudioSelected: (Int?) -> Unit,
    onSubtitleSelected: (Int?) -> Unit,
    onPlayClick: () -> Unit,
    onPlayFromBeginning: (() -> Unit)? = null,
    resumeStoppedAtLabel: String? = null,
    onEpisodePlayClick: (String, Double?) -> Unit,
    onEpisodeDetailClick: (String) -> Unit,
    onEpisodeWatchedChange: (String, Boolean) -> Unit,
    onSeasonSelected: (Int) -> Unit,
    onFavoriteClick: () -> Unit,
    onWatchlistClick: () -> Unit,
    onToggleWatched: () -> Unit,
    onPersonClick: (String) -> Unit,
    onItemDetailClick: (String) -> Unit,
    onSeriesDownloadClick: (() -> Unit)? = null,
    /** Series-level roll-up across ALL seasons: isDownloaded when every episode
     *  is downloaded, progress = downloaded/total fraction while partial. */
    seriesDownloadState: DetailDownloadState = DetailDownloadState(),
    playOnDeviceLabel: String = "Play on device",
    onPlayOnDevice: (() -> Unit)? = null,
    onWatchTogether: (() -> Unit)? = null,
    onSuggestToRoom: (() -> Unit)? = null,
    translation: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val dominantColor by rememberDominantColor(
        imageUrl = detail.backdropUrl,
        fallback = SiloBackground,
        thumbhash = detail.backdropThumbhash,
    )
    var showVersionPicker by remember { mutableStateOf(false) }
    var showAudioPicker by remember { mutableStateOf(false) }
    var showSubtitlePicker by remember { mutableStateOf(false) }

    val eyebrow = HeroMetadata.seriesEyebrow(detail)
    val sourceTokens = HeroMetadata.seriesSourceTokens(detail)
    val factsLine = HeroMetadata.seriesFactsLine(detail)

    val selectedSeason = seasons.firstOrNull { it.seasonNumber == selectedSeasonNumber }
    val selectedEpisode = episodes.firstOrNull { it.contentId == selectedEpisodeContentId }
    val loadedSelectedEpisodeDetail = selectedEpisodeDetail
        ?.takeIf { it.contentId == selectedEpisodeContentId }
    val usesEpisodeEditorial = selectedEpisode != null || selectedEpisodeContentId != null
    val selectedEpisodeOverview = loadedSelectedEpisodeDetail?.overview
        ?.takeIf { it.isNotBlank() }
        ?: selectedEpisode?.overview?.takeIf { it.isNotBlank() }
    // iOS keeps the series cast credit stable while the selected episode's
    // overview and playback options change. Reusing the series credit avoids
    // replacing it with a skeleton (and repainting different names) on every
    // horizontal episode selection.
    val fixedSeriesCredit = remember(detail.contentId, detail.cast) { seriesStarringCredit(detail) }
    val episodeCountSubtitle = selectedSeason?.episodeCount?.takeIf { it > 0 }?.let { count ->
        "$count episode${if (count == 1) "" else "s"}"
    }

    val playbackSelector: @Composable () -> Unit = {
        if (selectedEpisodeContentId != null) {
            when {
                isLoadingSelectedEpisodeDetail || loadedSelectedEpisodeDetail == null ->
                    PlaybackSelectorSkeleton()
                else -> {
                    val selectedVersion = loadedSelectedEpisodeDetail.versions.getOrNull(selectedVersionIndex)
                    val audioTracks = selectedVersion?.audioTracks.orEmpty()
                    val subtitleTracks = selectedVersion?.subtitleTracks.orEmpty()
                    PlaybackSelectorCard {
                        TrackSelectorRow(
                            icon = Icons.Outlined.HighQuality,
                            label = "Version",
                            value = formatVersionValueLabel(selectedVersion, isAutoVersion),
                            onClick = { showVersionPicker = true },
                            interactive = loadedSelectedEpisodeDetail.versions.size > 1,
                        )
                        PlaybackSelectorDivider()
                        TrackSelectorRow(
                            icon = Icons.Outlined.AudioFile,
                            label = "Audio",
                            value = if (audioTracks.isEmpty()) "Unavailable" else formatAudioValueLabel(
                                audioTracks,
                                selectedAudioIndex,
                                selectedVersion?.effectiveAudioTrackIndex,
                            ),
                            onClick = { showAudioPicker = true },
                            interactive = audioTracks.size > 1,
                        )
                        PlaybackSelectorDivider()
                        TrackSelectorRow(
                            icon = Icons.Outlined.ClosedCaption,
                            label = "Subtitles",
                            value = if (subtitleTracks.isEmpty()) "Unavailable" else formatSubtitleValueLabel(
                                subtitleTracks,
                                selectedSubtitleIndex,
                            ),
                            onClick = { showSubtitlePicker = true },
                            interactive = subtitleTracks.size > 1,
                        )
                    }
                }
            }
        }
    }

    val episodeSection: @Composable (Boolean) -> Unit = { showsEpisodeDetails ->
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (seasons.isNotEmpty()) {
                SeasonChips(
                    seasons = seasons,
                    selectedSeasonNumber = selectedSeasonNumber,
                    onSeasonSelected = onSeasonSelected,
                )
            }
            if (showsEpisodeDetails) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    SectionHeader(
                        title = seriesSeasonSectionTitle(selectedSeason),
                        trailingText = episodeCountSubtitle,
                    )
                    Text(
                        text = "Tap to focus",
                        fontSize = 11.sp,
                        color = DetailTertiaryText,
                        modifier = Modifier.padding(horizontal = SafePadding),
                    )
                }
            } else {
                SectionHeader(
                    title = seriesSeasonSectionTitle(selectedSeason),
                    trailingText = episodeCountSubtitle,
                )
            }
            SeasonEpisodePager(
                seasons = seasons,
                selectedSeasonNumber = selectedSeasonNumber,
                episodes = episodes,
                episodesBySeason = episodesBySeason,
                isLoadingEpisodes = isLoadingEpisodes,
                onSeasonSelected = onSeasonSelected,
                onEpisodePlayClick = onEpisodePlayClick,
                onEpisodeDetailClick = onEpisodeDetailClick,
                onEpisodeWatchedChange = onEpisodeWatchedChange,
                highlightContentId = selectedEpisodeContentId,
                showsSeasonSelector = false,
                selectsCenteredEpisode = !showsEpisodeDetails,
                allowsSeasonPaging = false,
                showsEpisodeDetails = showsEpisodeDetails,
                tapToFocusEpisode = showsEpisodeDetails,
            )
        }
    }

    // iOS below-fold section spacing is 36 (hero→first section 32). Use 36
    // uniformly — the closest single-value match to the iOS column rhythm.
    val feedState = rememberLazyListState()
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    val isExpandedDetailLayout = maxWidth >= ExpandedDetailBreakpoint
    DeferImagePresentationWhileScrolling(feedState) {
    LazyColumn(
        state = feedState,
        modifier = Modifier
            .fillMaxSize()
            .background(SiloBackground)
            .background(detailScreenBackgroundBrush(dominantColor)),
        verticalArrangement = Arrangement.spacedBy(36.dp),
    ) {
        item(contentType = "detail-hero") {
            AdaptiveDetailHero(
                detail = detail,
                eyebrow = if (isExpandedDetailLayout) null else eyebrow,
                sourceTokens = sourceTokens,
                factsLine = factsLine,
                dominantColor = dominantColor,
                overviewText = if (isExpandedDetailLayout) {
                    detail.overview
                } else if (usesEpisodeEditorial) {
                    selectedEpisodeOverview
                } else {
                    detail.overview
                },
                reserveOverviewSpace = !isExpandedDetailLayout && usesEpisodeEditorial,
                directorText = fixedSeriesCredit,
                isCreditLoading = false,
                reserveCreditSpace = !isExpandedDetailLayout && usesEpisodeEditorial,
                translation = if (isExpandedDetailLayout || !usesEpisodeEditorial) translation else null,
                belowOverview = if (isExpandedDetailLayout) null else playbackSelector,
                expandedBelowOverview = {
                    episodeSection(true)
                    playbackSelector()
                },
            ) {
                HeroActionStack(
                    primaryLabel = selectedEpisode?.let { "Play ${episodeNumberText(it)}" }
                        ?: computePlayLabel(detail, nextEpisodeLabel),
                    onPlay = onPlayClick,
                    onPlayFromBeginning = onPlayFromBeginning,
                    resumeStoppedAtLabel = resumeStoppedAtLabel,
                    isFavorite = isFavorite,
                    isInWatchlist = isInWatchlist,
                    isWatched = detail.userData?.played == true,
                    onToggleFavorite = onFavoriteClick,
                    onToggleWatchlist = onWatchlistClick,
                    onToggleWatched = onToggleWatched,
                    overflow = if (
                        onWatchTogether != null || onPlayOnDevice != null ||
                        onSuggestToRoom != null
                    ) {
                        { dismiss ->
                            if (onPlayOnDevice != null) {
                                DropdownMenuItem(
                                    text = { Text(playOnDeviceLabel) },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Cast, contentDescription = null)
                                    },
                                    onClick = {
                                        dismiss()
                                        onPlayOnDevice()
                                    },
                                )
                            }
                            if (onSuggestToRoom != null) {
                                DropdownMenuItem(
                                    text = { Text("Suggest to Watch Together") },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Groups, contentDescription = null)
                                    },
                                    onClick = {
                                        dismiss()
                                        onSuggestToRoom()
                                    },
                                )
                            }
                            if (onWatchTogether != null) {
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

        if (!isExpandedDetailLayout) {
            item(contentType = "detail-episodes") {
                episodeSection(false)
            }
        }

        if (detail.cast.isNotEmpty()) {
            item(contentType = "detail-cast") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionHeader(title = "Cast & Crew")
                    CastCrewSection(
                        cast = detail.cast,
                        crew = detail.crew,
                        onPersonClick = onPersonClick,
                    )
                }
            }
        }

        item(contentType = "detail-facts") {
            // Header renders inside DetailFactsList, gated on having facts.
            DetailFactsList(detail = detail)
        }

        item(contentType = "detail-similar") {
            SimilarRail(
                items = similarItems,
                onSelect = onItemDetailClick,
            )
        }

        item(contentType = "detail-spacer") {
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
    }
    }

    loadedSelectedEpisodeDetail?.let { episodeDetail ->
        val version = episodeDetail.versions.getOrNull(selectedVersionIndex)
        if (showVersionPicker) {
            VersionPickerSheet(
                versions = episodeDetail.versions,
                selectedIndex = selectedVersionIndex.takeUnless { isAutoVersion },
                onSelect = { onVersionSelected(it); showVersionPicker = false },
                onDismiss = { showVersionPicker = false },
            )
        }
        if (showAudioPicker) {
            AudioPickerSheet(
                tracks = version?.audioTracks.orEmpty(),
                selectedIndex = selectedAudioIndex,
                onSelect = { onAudioSelected(it); showAudioPicker = false },
                onDismiss = { showAudioPicker = false },
            )
        }
        if (showSubtitlePicker) {
            SubtitlePickerSheet(
                tracks = version?.subtitleTracks.orEmpty(),
                selectedIndex = selectedSubtitleIndex,
                onSelect = { onSubtitleSelected(it); showSubtitlePicker = false },
                onDismiss = { showSubtitlePicker = false },
            )
        }
    }
}

internal fun seriesSeasonSectionTitle(season: Season?): String =
    season?.let { "${phoneSeasonLabel(it)} Episodes" } ?: "Episodes"

// Retained for formatter tests and non-visual accessibility copy even though
// the iOS-aligned Episodes header no longer renders a season download button.
internal fun seriesSeasonSectionLabel(season: Season?): String =
    season?.let(::phoneSeasonLabel) ?: "Episodes"

internal fun seasonDownloadContentDescription(
    season: Season,
    isDownloaded: Boolean,
): String {
    val label = phoneSeasonLabel(season)
    return if (isDownloaded) {
        "$label downloaded"
    } else {
        "Download ${label.replaceFirstChar(Char::lowercase)}"
    }
}

private fun seriesStarringCredit(detail: ItemDetail): String? =
    detail.cast.take(3)
        .map { it.name }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(prefix = "Starring ", separator = ", ")
