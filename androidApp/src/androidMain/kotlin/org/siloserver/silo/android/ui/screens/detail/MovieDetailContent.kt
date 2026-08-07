package org.siloserver.silo.android.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.HighQuality
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.ui.theme.SiloBackground
import org.siloserver.silo.android.ui.util.rememberDominantColor
import org.siloserver.silo.common.ui.movieDirectorCredit
import org.siloserver.silo.model.catalog.EpisodeListItem
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.Season

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
    userRating: Int? = null,
    onSetRating: (Int) -> Unit = {},
    onClearRating: () -> Unit = {},
    onVersionSelected: (Int?) -> Unit,
    onAudioSelected: (Int?) -> Unit,
    onSubtitleSelected: (Int?) -> Unit,
    onPersonClick: (String) -> Unit,
    onItemDetailClick: (String) -> Unit,
    onSeriesClick: (() -> Unit)? = null,
    onSeasonClick: (() -> Unit)? = null,
    // Episode pages only: the parent series' seasons + the selected
    // season's siblings, for the in-page season/episode selector.
    seasons: List<Season> = emptyList(),
    selectedSeasonNumber: Int = 1,
    episodes: List<EpisodeListItem> = emptyList(),
    episodesBySeason: Map<Int, List<EpisodeListItem>> = emptyMap(),
    isLoadingEpisodes: Boolean = false,
    onSeasonSelected: (Int) -> Unit = {},
    onEpisodePlayClick: (String, Double?) -> Unit = { _, _ -> },
    onEpisodeDetailClick: (String) -> Unit = {},
    onEpisodeDownloadClick: ((EpisodeListItem) -> Unit)? = null,
    episodeDownloadState: (EpisodeListItem) -> DetailDownloadState = { DetailDownloadState() },
    isDownloaded: Boolean = false,
    downloadProgress: Float? = null,
    playOnDeviceLabel: String = "Play on device",
    onDownloadTapped: (() -> Unit)? = null,
    onPlayOnDevice: (() -> Unit)? = null,
    onWatchTogether: (() -> Unit)? = null,
    onSuggestToRoom: (() -> Unit)? = null,
    translation: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var showVersionPicker by remember { mutableStateOf(false) }
    var showAudioPicker by remember { mutableStateOf(false) }
    var showSubtitlePicker by remember { mutableStateOf(false) }
    var showRatingSheet by remember { mutableStateOf(false) }

    val dominantColor by rememberDominantColor(detail.backdropUrl, fallback = SiloBackground)

    val selectedVersion = detail.versions.getOrNull(selectedVersionIndex)
    val audioTracks = selectedVersion?.audioTracks.orEmpty()
    val subtitleTracks = selectedVersion?.subtitleTracks.orEmpty()
    val hasTrackSelectors = detail.versions.isNotEmpty()
    val hasOverflow = onPlayOnDevice != null ||
        onSeriesClick != null || onSeasonClick != null || onWatchTogether != null ||
        onSuggestToRoom != null

    val eyebrow = if (detail.type == "episode") {
        HeroMetadata.episodeEyebrow(detail)
    } else {
        HeroMetadata.movieEyebrow(detail)
    }
    val sourceTokens = HeroMetadata.movieSourceTokens(detail)
    val factsLine = HeroMetadata.movieFactsLine(detail)

    // iOS below-fold section spacing is 36 (hero→first section 32). Use 36
    // uniformly — the closest single-value match to the iOS column rhythm.
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(SiloBackground)
            .background(detailScreenBackgroundBrush(dominantColor)),
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
                    userRating = userRating,
                    onRateClick = { showRatingSheet = true },
                    overflow = if (hasOverflow) {
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
                            if (onSeasonClick != null) {
                                DropdownMenuItem(
                                    text = { Text("Go to Season") },
                                    onClick = {
                                        dismiss()
                                        onSeasonClick()
                                    },
                                )
                            }
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
                // Downloaded confirmation under the action row — the circle
                // button's filled state alone is easy to miss (QA 2026-07-08);
                // iOS pairs its green check with an accessible "Downloaded".
                if (isDownloaded) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DownloadDone,
                            contentDescription = null,
                            tint = Color(0xFF30D158),
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = "Downloaded",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.85f),
                        )
                    }
                }
                // Box-style track group list (Video / Audio / Subtitles) —
                // TV & Apple parity; Auto rows preview the resolved track.
                if (hasTrackSelectors) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TrackSelectorRow(
                            icon = Icons.Outlined.HighQuality,
                            label = "Video",
                            value = formatVersionValueLabel(selectedVersion, isAutoVersion),
                            onClick = { showVersionPicker = true },
                        )
                        if (audioTracks.isNotEmpty()) {
                            TrackSelectorRow(
                                icon = Icons.Outlined.AudioFile,
                                label = "Audio",
                                value = formatAudioValueLabel(audioTracks, selectedAudioIndex, selectedVersion?.effectiveAudioTrackIndex),
                                onClick = { showAudioPicker = true },
                            )
                        }
                        if (subtitleTracks.isNotEmpty()) {
                            TrackSelectorRow(
                                icon = Icons.Outlined.ClosedCaption,
                                label = "Subtitles",
                                value = formatSubtitleValueLabel(subtitleTracks, selectedSubtitleIndex),
                                onClick = { showSubtitlePicker = true },
                            )
                        }
                    }
                }
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
                    SectionHeader(
                        label = if (selectedSeasonNumber == 0) "Specials" else "Season $selectedSeasonNumber",
                        title = "Episodes",
                    )
                    SeasonEpisodePager(
                        seasons = seasons,
                        selectedSeasonNumber = selectedSeasonNumber,
                        episodes = episodes,
                        episodesBySeason = episodesBySeason,
                        isLoadingEpisodes = isLoadingEpisodes,
                        onSeasonSelected = onSeasonSelected,
                        onEpisodePlayClick = onEpisodePlayClick,
                        onEpisodeDetailClick = onEpisodeDetailClick,
                        onEpisodeDownloadClick = onEpisodeDownloadClick,
                        episodeDownloadState = episodeDownloadState,
                        highlightContentId = detail.contentId,
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

        // Hide the similar rail on episode pages — viewers usually want
        // the next episode, not a tangentially related title.
        if (detail.type != "episode") {
            item(contentType = "detail-similar") {
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

    if (showVersionPicker) {
        VersionPickerSheet(
            versions = detail.versions,
            selectedIndex = selectedVersionIndex.takeUnless { isAutoVersion },
            onSelect = { index ->
                onVersionSelected(index)
                showVersionPicker = false
            },
            onDismiss = { showVersionPicker = false },
        )
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

/**
 * 44dp circle download button styled to sit alongside [CircleActionButton]
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
) {
    val isInFlight = progress != null && !isDownloaded
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(
                if (isDownloaded) Color.White
                else Color.White.copy(alpha = 0.10f)
            )
            .border(
                width = 1.dp,
                color = if (isDownloaded) Color.White
                else Color.White.copy(alpha = 0.25f),
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
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
            tint = if (isDownloaded) Color.Black else Color.White,
            modifier = Modifier.size(if (isDownloaded) 22.dp else 18.dp),
        )
    }
}
