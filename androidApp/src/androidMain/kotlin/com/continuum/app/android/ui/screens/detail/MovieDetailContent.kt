package com.continuum.app.android.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
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
import com.continuum.app.android.ui.theme.ContinuumBackground
import com.continuum.app.android.ui.util.rememberDominantColor
import com.continuum.app.model.catalog.ItemDetail

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
    isFavorite: Boolean,
    isInWatchlist: Boolean,
    selectedVersionIndex: Int,
    selectedAudioIndex: Int,
    selectedSubtitleIndex: Int,
    onPlayClick: () -> Unit,
    onFavoriteClick: () -> Unit,
    onWatchlistClick: () -> Unit,
    onToggleWatched: () -> Unit,
    userRating: Int? = null,
    onSetRating: (Int) -> Unit = {},
    onClearRating: () -> Unit = {},
    onVersionSelected: (Int) -> Unit,
    onAudioSelected: (Int) -> Unit,
    onSubtitleSelected: (Int) -> Unit,
    onPersonClick: (String) -> Unit,
    onItemDetailClick: (String) -> Unit,
    onSeriesClick: (() -> Unit)? = null,
    onSeasonClick: (() -> Unit)? = null,
    isDownloaded: Boolean = false,
    downloadProgress: Float? = null,
    onDownloadTapped: (() -> Unit)? = null,
    onWatchTogether: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var showMediaInfo by remember { mutableStateOf(false) }
    var showVersionPicker by remember { mutableStateOf(false) }
    var showAudioPicker by remember { mutableStateOf(false) }
    var showSubtitlePicker by remember { mutableStateOf(false) }
    var showRatingSheet by remember { mutableStateOf(false) }

    val dominantColor by rememberDominantColor(detail.backdropUrl, fallback = ContinuumBackground)

    val selectedVersion = detail.versions.getOrNull(selectedVersionIndex)
    val audioTracks = selectedVersion?.audioTracks.orEmpty()
    val subtitleTracks = selectedVersion?.subtitleTracks.orEmpty()
    val hasMultipleVersions = detail.versions.size > 1
    val hasAudioOptions = audioTracks.size > 1
    val hasSubtitleOptions = subtitleTracks.isNotEmpty()
    val hasMediaInfo = detail.versions.isNotEmpty()
    val hasOverflow = hasAudioOptions || hasSubtitleOptions || hasMediaInfo ||
        onSeriesClick != null || onSeasonClick != null || onWatchTogether != null

    val eyebrow = if (detail.type == "episode") {
        HeroMetadata.episodeEyebrow(detail)
    } else {
        HeroMetadata.movieEyebrow(detail)
    }
    val sourceTokens = HeroMetadata.movieSourceTokens(detail)
    val factsLine = HeroMetadata.movieFactsLine(detail)

    val versionLabel = if (hasMultipleVersions && selectedVersion != null) {
        formatVersionMenuLabel(selectedVersion)
    } else {
        null
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
                    primaryLabel = computePlayLabel(detail),
                    onPlay = onPlayClick,
                    isFavorite = isFavorite,
                    isInWatchlist = isInWatchlist,
                    isWatched = detail.userData?.played == true,
                    onToggleFavorite = onFavoriteClick,
                    onToggleWatchlist = onWatchlistClick,
                    onToggleWatched = onToggleWatched,
                    userRating = userRating,
                    onRateClick = { showRatingSheet = true },
                    versionLabel = versionLabel,
                    onVersionClick = if (hasMultipleVersions) {
                        { showVersionPicker = true }
                    } else {
                        null
                    },
                    overflow = if (hasOverflow) {
                        { dismiss ->
                            if (hasAudioOptions) {
                                DropdownMenuItem(
                                    text = { Text("Audio") },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.AudioFile, contentDescription = null)
                                    },
                                    trailingIcon = {
                                        Text(formatAudioLabel(audioTracks.getOrNull(selectedAudioIndex)))
                                    },
                                    onClick = {
                                        dismiss()
                                        showAudioPicker = true
                                    },
                                )
                            }
                            if (hasSubtitleOptions) {
                                DropdownMenuItem(
                                    text = { Text("Subtitles") },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.ClosedCaption, contentDescription = null)
                                    },
                                    trailingIcon = {
                                        Text(formatSubtitleLabel(subtitleTracks.getOrNull(selectedSubtitleIndex)))
                                    },
                                    onClick = {
                                        dismiss()
                                        showSubtitlePicker = true
                                    },
                                )
                            }
                            if (hasMediaInfo) {
                                DropdownMenuItem(
                                    text = { Text("Media Info") },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Info, contentDescription = null)
                                    },
                                    onClick = {
                                        dismiss()
                                        showMediaInfo = true
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

        // Hide the similar rail on episode pages — viewers usually want
        // the next episode, not a tangentially related title.
        if (detail.type != "episode") {
            item(contentType = "detail-similar") {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionHeader(label = "Recommended", title = "More Like This")
                    SimilarRail(
                        contentId = detail.contentId,
                        onSelect = onItemDetailClick,
                    )
                }
            }
        }

        item(contentType = "detail-spacer") {
            Spacer(modifier = Modifier.height(40.dp))
        }
    }

    if (showVersionPicker) {
        VersionPickerSheet(
            versions = detail.versions,
            selectedIndex = selectedVersionIndex,
            onSelect = { index ->
                onVersionSelected(index)
                showVersionPicker = false
            },
            onDismiss = { showVersionPicker = false },
        )
    }

    if (showAudioPicker && hasAudioOptions) {
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

    if (showSubtitlePicker && hasSubtitleOptions) {
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

    if (showMediaInfo && hasMediaInfo) {
        MediaInfoSheet(
            versions = detail.versions,
            onDismiss = { showMediaInfo = false },
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
