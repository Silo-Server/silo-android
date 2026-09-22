package org.siloserver.silo.android.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.ui.theme.SiloBackground
import org.siloserver.silo.android.ui.theme.SiloDetailActionControl
import org.siloserver.silo.android.ui.theme.SiloDetailActionControlActive
import org.siloserver.silo.android.ui.util.rememberDominantColor
import org.siloserver.silo.common.ui.movieDirectorCredit
import org.siloserver.silo.model.catalog.EpisodeListItem
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.Season
import org.siloserver.silo.model.catalog.selectedMediaRuntimeMinutes
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalDensity
import org.siloserver.silo.common.ui.components.DeferImagePresentationWhileScrolling

/**
 * Phone movie / episode detail. Cinematic backdrop hero up top, then a
 * scrollable body of cast, details, and (for episodes) a series shortcut.
 *
 * Mirrors `MovieDetailContent.swift` semantically — same hero metadata,
 * same primary play + circle action row, same single consolidated
 * version selector — sized for touch.
 */
@Composable
fun MovieDetailContent(
    detail: ItemDetail,
    portraitArtwork: DetailPortraitArtwork = DetailPortraitArtwork(
        url = detail.posterUrl,
        thumbhash = detail.posterThumbhash,
    ),
    similarItems: List<org.siloserver.silo.model.catalog.BrowseItem> = emptyList(),
    isFavorite: Boolean,
    isInWatchlist: Boolean,
    selectedVersionIndex: Int,
    isAutoVersion: Boolean,
    selectedAudioIndex: Int?,
    selectedSubtitleIndex: Int?,
    onPlayClick: () -> Unit,
    onPlayFromBeginning: (() -> Unit)? = null,
    resumeStoppedAtLabel: String? = null,
    onFavoriteClick: () -> Unit,
    onWatchlistClick: () -> Unit,
    onToggleWatched: () -> Unit,
    onVersionSelected: (Int?) -> Unit,
    onAudioSelected: (Int?) -> Unit,
    onSubtitleSelected: (Int?) -> Unit,
    onPersonClick: (String) -> Unit,
    onItemDetailClick: (String) -> Unit,
    onSeriesClick: (() -> Unit)? = null,
    // Episode pages only: the parent series' seasons + the selected
    // season's siblings, for the in-page season/episode selector.
    seasons: List<Season> = emptyList(),
    selectedSeasonNumber: Int = 1,
    episodes: List<EpisodeListItem> = emptyList(),
    episodesBySeason: Map<Int, List<EpisodeListItem>> = emptyMap(),
    isLoadingEpisodes: Boolean = false,
    onSeasonSelected: (Int) -> Unit = {},
    onEpisodeDetailClick: (String) -> Unit = {},
    onEpisodeWatchedChange: (String, Boolean) -> Unit = { _, _ -> },
    isDownloaded: Boolean = false,
    downloadProgress: Float? = null,
    onDownloadTapped: (() -> Unit)? = null,
    onWatchTogether: (() -> Unit)? = null,
    onSuggestToRoom: (() -> Unit)? = null,
    translation: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var showAudioPicker by remember { mutableStateOf(false) }
    var showSubtitlePicker by remember { mutableStateOf(false) }

    val dominantColor by rememberDominantColor(
        imageUrl = detail.backdropUrl,
        fallback = SiloBackground,
        thumbhash = detail.backdropThumbhash,
    )

    val selectedVersion = detail.versions.getOrNull(selectedVersionIndex)
    val audioTracks = selectedVersion?.audioTracks.orEmpty()
    val subtitleTracks = selectedVersion?.subtitleTracks.orEmpty()
    val hasTrackSelectors = detail.versions.isNotEmpty()
    val hasOverflow = onSeriesClick != null || onWatchTogether != null ||
        onSuggestToRoom != null

    val eyebrow = if (detail.type == "episode") {
        HeroMetadata.episodeEyebrow(detail)
    } else {
        null
    }
    val sourceTokens = HeroMetadata.movieSourceTokens(detail)
    val selectedRuntimeMinutes = selectedMediaRuntimeMinutes(detail, selectedVersion)
    val factsLine = HeroMetadata.movieFactsLine(detail, selectedRuntimeMinutes)

    // iOS below-fold section spacing is 36 (hero→first section 32). Use 36
    // uniformly — the closest single-value match to the iOS column rhythm.
    val feedState = rememberLazyListState()
    // Feed the pinned header. Only the first item (the hero) matters: it is
    // taller than the fade range, so once it has scrolled away the header is
    // already fully settled.
    val detailScroll = LocalDetailScrollState.current
    if (detailScroll != null) {
        val density = LocalDensity.current
        LaunchedEffect(feedState, detailScroll, density) {
            snapshotFlow {
                if (feedState.firstVisibleItemIndex > 0) {
                    HeaderSettledDp
                } else {
                    with(density) { feedState.firstVisibleItemScrollOffset.toDp().value }
                }
            }.collect { detailScroll.update(it) }
        }
    }
    DetailPageSurface(
        backdropUrl = detail.backdropUrl,
        backdropThumbhash = detail.backdropThumbhash,
        tint = dominantColor,
    ) {
    DeferImagePresentationWhileScrolling(feedState) {
    LazyColumn(
        state = feedState,
        modifier = modifier
            .fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(36.dp),
    ) {
        item(contentType = "detail-hero") {
            AdaptiveDetailHero(
                detail = detail,
                eyebrow = eyebrow,
                sourceTokens = sourceTokens,
                factsLine = factsLine,
                portraitArtwork = portraitArtwork,
                dominantColor = dominantColor,
                directorText = movieDirectorCredit(detail),
                translation = translation,
                belowOverview = {
                    // PR #212 places the grouped playback card after overview,
                    // credits, and translation—not inside the action stack.
                    if (hasTrackSelectors) {
                        PlaybackSelectorCard {
                            EditionVersionSelectorRows(
                                detail = detail,
                                selectedVersionIndex = selectedVersionIndex,
                                isAutoVersion = isAutoVersion,
                                onVersionSelected = onVersionSelected,
                            )
                            if (audioTracks.isNotEmpty()) {
                                PlaybackSelectorDivider()
                                TrackSelectorRow(
                                    icon = Icons.Outlined.AudioFile,
                                    label = "Audio",
                                    value = formatAudioValueLabel(
                                        audioTracks,
                                        selectedAudioIndex,
                                        selectedVersion?.effectiveAudioTrackIndex,
                                    ),
                                    onClick = { showAudioPicker = true },
                                    interactive = audioTracks.size > 1,
                                )
                            }
                            if (subtitleTracks.isNotEmpty()) {
                                PlaybackSelectorDivider()
                                TrackSelectorRow(
                                    icon = Icons.Outlined.ClosedCaption,
                                    label = "Subtitles",
                                    value = formatSubtitleValueLabel(subtitleTracks, selectedSubtitleIndex),
                                    onClick = { showSubtitlePicker = true },
                                    interactive = subtitleTracks.size > 1,
                                )
                            }
                        }
                    }
                },
            ) {
                HeroActionStack(
                    primaryLabel = computePlayLabel(detail),
                    onPlay = onPlayClick,
                    onPlayFromBeginning = onPlayFromBeginning,
                    resumeStoppedAtLabel = resumeStoppedAtLabel,
                    isFavorite = isFavorite,
                    isInWatchlist = isInWatchlist,
                    isWatched = detail.userData?.played == true,
                    onToggleFavorite = onFavoriteClick,
                    onToggleWatchlist = onWatchlistClick,
                    onToggleWatched = onToggleWatched,
                    overflow = if (hasOverflow) {
                        { dismiss ->
                            if (onSeriesClick != null) {
                                DropdownMenuItem(
                                    text = { Text("Go to Series") },
                                    onClick = {
                                        dismiss()
                                        onSeriesClick()
                                    },
                                )
                            }
                            if (onSuggestToRoom != null) {
                                DropdownMenuItem(
                                    text = { Text("Suggest to Watch Together") },
                                    leadingIcon = {
                                        Icon(Icons.Filled.Add, contentDescription = null)
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
                    downloadSlot = if (onDownloadTapped != null) {
                        {
                            DownloadCircleButton(
                                isDownloaded = isDownloaded,
                                progress = downloadProgress,
                                onClick = onDownloadTapped,
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }

        // Episode pages: season chips + this season's episodes as the first
        // below-fold section, mirroring iOS's episode detail. Tapping a
        // sibling navigates to its own detail page.
        // Keep the section mounted whenever the parent series has seasons —
        // an empty (or failed) season must still show the chips so the user
        // can switch back, mirroring SeriesDetailContent. Gating on episodes
        // alone stranded the user with no way out of an empty season.
        if (detail.type == "episode" && (seasons.isNotEmpty() || episodes.isNotEmpty() || isLoadingEpisodes)) {
            item(contentType = "detail-episodes") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (seasons.isNotEmpty()) {
                        SeasonChips(
                            seasons = seasons,
                            selectedSeasonNumber = selectedSeasonNumber,
                            onSeasonSelected = onSeasonSelected,
                        )
                    }
                    val selectedSeason = seasons.firstOrNull {
                        it.seasonNumber == selectedSeasonNumber
                    }
                    SectionHeader(
                        title = selectedSeason?.let(::seriesSeasonSectionTitle) ?: if (
                            selectedSeasonNumber == 0
                        ) {
                            "Specials Episodes"
                        } else {
                            "Season $selectedSeasonNumber Episodes"
                        },
                    )
                    SeasonEpisodePager(
                        seasons = seasons,
                        selectedSeasonNumber = selectedSeasonNumber,
                        episodes = episodes,
                        episodesBySeason = episodesBySeason,
                        isLoadingEpisodes = isLoadingEpisodes,
                        onSeasonSelected = onSeasonSelected,
                        onEpisodePlayClick = null,
                        onEpisodeDetailClick = onEpisodeDetailClick,
                        onEpisodeWatchedChange = onEpisodeWatchedChange,
                        highlightContentId = detail.contentId,
                        showsSeasonSelector = false,
                        allowsSeasonPaging = false,
                    )
                }
            }
        }

        if (detail.cast.isNotEmpty()) {
            item(contentType = "detail-cast") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionHeader(title = "Cast & Crew")
                    CastCrewSection(
                        cast = detail.cast,
                        onPersonClick = onPersonClick,
                    )
                }
            }
        }

        item(contentType = "detail-facts") {
            // Header renders inside DetailFactsList, gated on having facts.
            DetailFactsList(detail = detail, runtimeMinutes = selectedRuntimeMinutes)
        }

        // Hide the similar rail on episode pages — viewers usually want
        // the next episode, not a tangentially related title.
        if (detail.type != "episode") {
            item(contentType = "detail-similar") {
                SimilarRail(
                    items = similarItems,
                    onSelect = onItemDetailClick,
                )
            }
        }

        item(contentType = "detail-spacer") {
            Spacer(modifier = Modifier.height(40.dp))
        }
    }
    }

    if (showAudioPicker && audioTracks.isNotEmpty()) {
        AudioPickerSheet(
            tracks = audioTracks,
            selectedIndex = selectedAudioIndex,
            onSelect = { index ->
                onAudioSelected(index)
                showAudioPicker = false
            },
            onDismiss = { showAudioPicker = false },
        )
    }

    if (showSubtitlePicker) {
        SubtitlePickerSheet(
            tracks = subtitleTracks,
            selectedIndex = selectedSubtitleIndex,
            onSelect = { index ->
                onSubtitleSelected(index)
                showSubtitlePicker = false
            },
            onDismiss = { showSubtitlePicker = false },
        )
    }
    }

}

/**
 * 42dp circle download button styled to sit alongside [CircleActionButton]
 * (favorite / watchlist / watched) in [HeroActionStack]. Three visual states:
 *
 *   - Not downloaded:  white download icon over a ghost circle (matches the
 *                      idle treatment of the sibling toggle buttons).
 *   - Downloading / queued: white circular progress + faint download icon.
 *   - Downloaded (complete): SOLID white-filled circle with a black filled
 *                            check — distinctly heavier than the "active"
 *                            translucent treatment of favorite/bookmark so
 *                            the user can tell at a glance that the bytes
 *                            are on disk.
 */
@Composable
internal fun DownloadCircleButton(
    isDownloaded: Boolean,
    progress: Float?,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val isInFlight = progress != null && !isDownloaded
    Box(
        modifier = Modifier
            .size(42.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(CircleShape)
            .background(
                if (isDownloaded) SiloDetailActionControlActive
                else SiloDetailActionControl
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (isInFlight) {
            CircularProgressIndicator(
                progress = { progress!! },
                modifier = Modifier.size(28.dp),
                color = Color.White,
                trackColor = Color.White.copy(alpha = 0.2f),
                strokeWidth = 2.dp,
            )
        }
        Icon(
            imageVector = if (isDownloaded) Icons.Filled.Check else Icons.Filled.Download,
            contentDescription = if (isDownloaded) "Downloaded" else "Download",
            tint = Color.White,
            modifier = Modifier.size(if (isDownloaded) 22.dp else 18.dp),
        )
    }
}
