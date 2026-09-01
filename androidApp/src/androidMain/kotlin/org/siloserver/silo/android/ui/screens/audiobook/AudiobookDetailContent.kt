package org.siloserver.silo.android.ui.screens.audiobook

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.ui.screens.detail.CircleActionButton
import org.siloserver.silo.audiobook.buildAudiobookTimeline
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.audiobook.AudiobookNarration
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.catalog.VersionChapter
import org.siloserver.silo.model.ebook.MediaRelatedContent
import org.siloserver.silo.model.ebook.MediaRelatedItem
import org.siloserver.silo.model.ebook.MediaSeriesGroup
import androidx.compose.foundation.lazy.rememberLazyListState
import org.siloserver.silo.common.ui.components.DeferImagePresentationWhileScrolling

/**
 * Phone audiobook detail. Cover + author + narrator above, then the
 * chapter list. Tapping the play button or any chapter opens the
 * dedicated audiobook player.
 *
 * Differs from MovieDetailContent in shape: square cover (not 2:3
 * poster), chapter list as the primary affordance (not "play" pill).
 */
private const val AudiobookCoverSizeDp = 220

@Composable
fun AudiobookDetailContent(
    detail: ItemDetail,
    isFavorite: Boolean,
    isInWatchlist: Boolean,
    selectedFileId: Int? = null,
    isDownloaded: Boolean = false,
    downloadProgress: Float? = null,
    // Primary play/resume: launches with the BOOK-GLOBAL resume position (the
    // player VM resolves which part contains it) — never paired with a part's
    // fileId. Mirrors Apple `audioStore.play(contentId:restart:false)`.
    onPlayClick: () -> Unit,
    // Restart the whole book from 0:00 (Apple `play(restart: true)`).
    onPlayFromStartClick: () -> Unit = {},
    // Play from a whole-book (global) offset — a part's start offset or a
    // chapter's global start (Apple `play(startPosition:)`).
    onPlayFromPositionClick: (startPositionSeconds: Double) -> Unit = {},
    onChapterClick: (VersionChapter) -> Unit,
    onFavoriteClick: () -> Unit,
    onWatchlistClick: () -> Unit,
    onDownloadClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val meta = detail.audiobook
    val authorText = meta?.authorNames
    val narratorText = meta?.narratorNames
    val playableVersion = selectedFileId
        ?.let { fileId -> detail.versions.firstOrNull { it.fileId == fileId } }
        ?: detail.versions.firstOrNull()
    // Whole-book timeline stitched from the item's audiobook-part files — the
    // same math the player VM uses. Drives the Parts section and the stitched
    // whole-book chapter list. Null when there are no audio parts.
    val timeline = remember(detail) {
        buildAudiobookTimeline(
            versions = detail.versions,
            serverTotalSeconds = meta?.totalDurationSeconds?.toDouble(),
        )
    }
    val durationSeconds = timeline?.totalSeconds
        ?: meta?.totalDurationSeconds?.toDouble()
        ?: playableVersion?.duration
    // Resume/finished gating mirrors Apple's AudiobookDetailContent: Resume only
    // when >30s in and not effectively finished; a finished book routes to "Play
    // Again" (restart) instead of resuming near the end.
    val positionSeconds = (detail.userData?.positionSeconds ?: 0.0).coerceAtLeast(0.0)
    val totalForGating = durationSeconds ?: 0.0
    val isFinished = detail.userData?.played == true ||
        (totalForGating > 0.0 && positionSeconds > 0.0 && positionSeconds >= totalForGating - 5.0)
    val resumeSeconds = positionSeconds.takeIf { it > 30.0 && !isFinished }
    // Multi-part books get the stitched whole-book chapters (each jumps to its
    // global start); single-part / no-timeline books keep the single file's own
    // chapters unchanged.
    val chapters: List<VersionChapter> = if (timeline != null && !timeline.isSingle) {
        timeline.chapters.map { ch ->
            VersionChapter(
                index = ch.index,
                title = ch.title.orEmpty(),
                startSeconds = ch.startSeconds,
                endSeconds = ch.endSeconds ?: ch.startSeconds,
            )
        }
    } else {
        playableVersion?.chapters.orEmpty()
    }
    val displayableNarrations = meta?.otherNarrations.orEmpty()
        .filter { it.title.isNotBlank() }
    val relatedLines = meta?.related?.displayLines().orEmpty()
    // Match the iOS detail surface: chapters are visible immediately, while
    // the header still lets readers collapse very long chapter lists.
    var chaptersExpanded by remember { mutableStateOf(true) }
    // Clear the status bar / camera cutout so the cover isn't tucked under it.
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val feedState = rememberLazyListState()
    DeferImagePresentationWhileScrolling(feedState) {
    LazyColumn(
        state = feedState,
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            bottom = 16.dp,
            top = 16.dp + topInset,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(contentType = "audiobook-hero") {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ThumbhashImage(
                    url = detail.posterUrl,
                    thumbhash = detail.posterThumbhash,
                    contentDescription = detail.title,
                    modifier = Modifier
                        .size(AudiobookCoverSizeDp.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
                Text(
                    text = detail.title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                authorText?.takeIf { it.isNotBlank() }?.let { author ->
                    Text(
                        text = author,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                narratorText?.takeIf { it.isNotBlank() }?.let { narrator ->
                    Text(
                        text = "Narrated by $narrator",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                durationSeconds?.let { dur ->
                    Text(
                        text = formatDuration(dur),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // Big play pill — mirrors the movie/series hero action.
        item(contentType = "audiobook-actions") {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (playableVersion != null) {
                    // Primary label mirrors Apple: Resume (>30s, unfinished) →
                    // "Resume · h:mm:ss"; finished → "Play Again"; else "Play".
                    val primaryLabel = when {
                        resumeSeconds != null -> "Resume · ${formatClock(resumeSeconds)}"
                        isFinished -> "Play Again"
                        else -> "Play"
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = {
                                // Finished with no resume → restart; otherwise resume
                                // from the stored whole-book position (VM picks the part).
                                if (isFinished && resumeSeconds == null) onPlayFromStartClick()
                                else onPlayClick()
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(primaryLabel)
                        }
                        OutlinedButton(
                            onClick = { onPlayFromStartClick() },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Start Over")
                        }
                    }
                } else {
                    Button(
                        onClick = { onPlayClick() },
                        enabled = false,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Unavailable")
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircleActionButton(
                        icon = Icons.Filled.FavoriteBorder,
                        activeIcon = Icons.Filled.Favorite,
                        isActive = isFavorite,
                        contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                        onClick = onFavoriteClick,
                        activeTint = Color(0xFFEF5350),
                        label = "Favorite",
                    )
                    CircleActionButton(
                        icon = Icons.Filled.BookmarkBorder,
                        activeIcon = Icons.Filled.Bookmark,
                        isActive = isInWatchlist,
                        contentDescription = if (isInWatchlist) "Remove from watchlist" else "Add to watchlist",
                        onClick = onWatchlistClick,
                        label = "Watchlist",
                    )
                }
                OutlinedButton(
                    onClick = { onDownloadClick?.invoke() },
                    enabled = onDownloadClick != null && !isDownloaded,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = if (isDownloaded) Icons.Filled.Check else Icons.Filled.Download,
                        contentDescription = null,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        when {
                            isDownloaded -> "Downloaded"
                            downloadProgress != null -> "Cancel Download"
                            onDownloadClick != null -> "Download"
                            else -> "Download Unavailable"
                        },
                    )
                }
                downloadProgress?.let { progress ->
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        detail.overview?.takeIf { it.isNotBlank() }?.let { overview ->
            item(contentType = "audiobook-overview") {
                Text(
                    text = overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        meta?.publisher?.takeIf { it.isNotBlank() }?.let { publisher ->
            item(contentType = "audiobook-publisher") {
                AudiobookInfoLine(label = "Publisher", value = publisher)
            }
        }

        meta?.series?.takeIf { it.hasDisplayableContent() }?.let { series ->
            item(contentType = "audiobook-series") {
                AudiobookSeriesSection(series = series)
            }
        }

        if (displayableNarrations.isNotEmpty()) {
            item(contentType = "audiobook-narrations-header") {
                Text(
                    text = "Other Narrations",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            items(displayableNarrations, contentType = { "audiobook-narration" }) { narration ->
                OtherNarrationRow(narration = narration)
            }
        }

        if (relatedLines.isNotEmpty()) {
            item(contentType = "audiobook-related") {
                AudiobookRelatedSection(lines = relatedLines)
            }
        }

        // Parts — shown only for multi-part books (>1 audiobook_part version).
        // Tapping a part plays from its whole-book start offset (Apple parity).
        val parts = timeline?.tracks.orEmpty()
        if (timeline != null && !timeline.isSingle && parts.isNotEmpty()) {
            item(contentType = "audiobook-parts-header") {
                Text(
                    text = "Parts (${parts.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            items(parts, contentType = { "audiobook-part" }) { track ->
                val version = detail.versions.firstOrNull { it.fileId == track.fileId }
                PartRow(
                    title = partTitle(version, track.index),
                    runtimeSeconds = track.durationSeconds,
                    onClick = { onPlayFromPositionClick(track.startOffsetSeconds) },
                )
            }
        }

        if (chapters.isNotEmpty()) {
            item(contentType = "audiobook-chapters-header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { chaptersExpanded = !chaptersExpanded },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Chapters (${chapters.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = if (chaptersExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (chaptersExpanded) "Collapse chapters" else "Expand chapters",
                    )
                }
            }
            if (chaptersExpanded) {
                items(chapters, contentType = { "audiobook-chapter" }) { chapter ->
                    ChapterRow(chapter = chapter, onClick = { onChapterClick(chapter) })
                }
            }
        }
    }
    }
}

@Composable
private fun AudiobookInfoLine(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AudiobookSeriesSection(series: MediaSeriesGroup) {
    val title = series.name.takeIf { it.isNotBlank() }
    val entryLines = series.entries
        .mapNotNull { entry ->
            listOfNotNull(
                entry.seriesIndex?.let { "Book ${formatSeriesIndex(it)}" },
                entry.title.takeIf { it.isNotBlank() },
            ).joinToString(" · ").takeIf { it.isNotBlank() }
        }
        .take(3)
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Series",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(6.dp))
        title?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        entryLines.forEach { entryLine ->
            Text(
                text = entryLine,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun OtherNarrationRow(narration: AudiobookNarration) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Text(
            text = narration.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val subtitle = listOfNotNull(
            narration.year?.toString(),
            narration.narrators
                .filter { it.isNotBlank() }
                .takeIf { it.isNotEmpty() }
                ?.joinToString(", "),
        ).joinToString(" · ")
        if (subtitle.isNotBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AudiobookRelatedSection(lines: List<String>) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Related",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(6.dp))
        lines.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun MediaRelatedContent.displayLines(): List<String> = listOfNotNull(
    alsoByAuthor.joinToRelatedTitles()
        .takeIf { it.isNotBlank() }
        ?.let { "Also by author: $it" },
    similar.joinToRelatedTitles()
        .takeIf { it.isNotBlank() }
        ?.let { "Similar: $it" },
)

private fun List<MediaRelatedItem>.joinToRelatedTitles(): String =
    mapNotNull { it.title.takeIf { title -> title.isNotBlank() } }
        .take(3)
        .joinToString(", ")

private fun MediaSeriesGroup.hasDisplayableContent(): Boolean =
    name.isNotBlank() || entries.any { it.title.isNotBlank() || it.seriesIndex != null }

private fun formatSeriesIndex(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

@Composable
private fun ChapterRow(chapter: VersionChapter, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${chapter.index + 1}.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(36.dp),
        )
        Text(
            text = chapter.title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = formatDuration(chapter.endSeconds - chapter.startSeconds),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PartRow(title: String, runtimeSeconds: Double, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = formatDuration(runtimeSeconds),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Part row label: the file name when present, else "Part {index}" — mirrors
 *  Apple `partTitle`, using the version's `presentationPartIndex` (falling back
 *  to the 0-based [position], displayed 1-based). */
private fun partTitle(version: FileVersion?, position: Int): String {
    version?.fileName?.takeIf { it.isNotBlank() }?.let { return it }
    val rawIndex = version?.presentationPartIndex ?: position
    val displayIndex = if (rawIndex <= 0) rawIndex + 1 else rawIndex
    return "Part $displayIndex"
}

private fun formatDuration(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}

/** Clock-style position label (h:mm:ss / m:ss) for the Resume button. */
private fun formatClock(seconds: Double): String {
    val total = seconds.toLong().coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
