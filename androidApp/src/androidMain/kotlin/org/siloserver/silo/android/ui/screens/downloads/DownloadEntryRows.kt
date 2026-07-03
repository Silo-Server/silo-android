package org.siloserver.silo.android.ui.screens.downloads

import org.siloserver.silo.android.ui.util.formatBytes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.download.DownloadMediaType
import org.siloserver.silo.model.download.DownloadStatus
import org.siloserver.silo.model.ebook.ebookFormatKey
import org.siloserver.silo.model.ebook.isInAppReadableEbookFormat

internal enum class DownloadRowAction {
    None,
    ReadInApp,
    OpenExternally,
    OpenInAppPlayer,
}

internal fun downloadRowAction(item: DownloadItem): DownloadRowAction {
    if (!item.isComplete || item.localUri.isNullOrBlank()) return DownloadRowAction.None
    if (item.mediaType == DownloadMediaType.Ebook) {
        val formatKey = ebookFormatKey(container = item.container, fileName = item.displayName)
        return if (formatKey.orEmpty().isInAppReadableEbookFormat()) {
            DownloadRowAction.ReadInApp
        } else {
            DownloadRowAction.OpenExternally
        }
    }
    return when (item.mediaType) {
        DownloadMediaType.Movie,
        DownloadMediaType.TvShow,
        DownloadMediaType.Audiobook -> DownloadRowAction.OpenInAppPlayer
        DownloadMediaType.Ebook,
        DownloadMediaType.Unknown -> DownloadRowAction.OpenExternally
    }
}

internal fun downloadExternalOpenAvailable(item: DownloadItem): Boolean =
    item.isComplete && !item.localUri.isNullOrBlank()

private fun downloadRowActionLabel(action: DownloadRowAction): String =
    when (action) {
        DownloadRowAction.ReadInApp -> "Read"
        DownloadRowAction.OpenInAppPlayer -> "Play"
        DownloadRowAction.OpenExternally -> "Open"
        DownloadRowAction.None -> ""
    }

/**
 * Renders one full media-type section (Movies / TV / Audiobooks /
 * eBooks / Other) into a LazyColumn. Header row carries the section
 * title + aggregate size + a delete-all button; the body recursively
 * lays out the entries underneath.
 *
 * Delete handlers fan out:
 *   - leaf-level (Single)  → [onDeleteSingle]
 *   - middle (Season)      → [onDeleteEntry]
 *   - top (Series)         → [onDeleteEntry]
 *   - whole section        → [onDeleteSection]
 *
 * Storage size is shown at every level — section header sums the whole
 * section; series sums its seasons; season sums its episodes; episode
 * shows its own bytes.
 */
internal fun LazyListScope.renderSection(
    section: DownloadTypeSection,
    onItemClick: (DownloadItem) -> Unit,
    onReadEbook: (DownloadItem) -> Unit,
    onOpenExternalDownload: (DownloadItem) -> Unit,
    onDeleteSingle: (DownloadItem) -> Unit,
    onDeleteEntry: (DownloadEntry) -> Unit,
    onDeleteSection: (DownloadTypeSection) -> Unit,
) {
    item(
        key = "section_header_${section.mediaType.wire}",
        contentType = "download-section-header",
    ) {
        SectionHeaderRow(section = section, onDeleteSection = { onDeleteSection(section) })
    }
    section.entries.forEach { entry ->
        item(
            key = "section_${section.mediaType.wire}_entry_${entry.id}",
            contentType = "download-entry",
        ) {
            renderEntry(
                entry = entry,
                depth = 0,
                onItemClick = onItemClick,
                onReadEbook = onReadEbook,
                onOpenExternalDownload = onOpenExternalDownload,
                onDeleteSingle = onDeleteSingle,
                onDeleteEntry = onDeleteEntry,
            )
        }
    }
}

/**
 * Recursive entry renderer. [depth] drives the left-inset so nested
 * children visually align under their parent.
 */
@Composable
private fun renderEntry(
    entry: DownloadEntry,
    depth: Int,
    onItemClick: (DownloadItem) -> Unit,
    onReadEbook: (DownloadItem) -> Unit,
    onOpenExternalDownload: (DownloadItem) -> Unit,
    onDeleteSingle: (DownloadItem) -> Unit,
    onDeleteEntry: (DownloadEntry) -> Unit,
) {
    val leftInset = (depth * 12).dp
    when (entry) {
        is DownloadEntry.Single -> SingleRow(
            item = entry.item,
            modifier = Modifier.padding(start = leftInset),
            onItemClick = { onItemClick(entry.item) },
            onReadEbook = { onReadEbook(entry.item) },
            onOpenExternalDownload = { onOpenExternalDownload(entry.item) },
            onDeleteClick = { onDeleteSingle(entry.item) },
        )

        is DownloadEntry.Series -> ExpandableAggregateRow(
            entry = entry,
            // Single-season case: skip the season level — go straight
            // to episode rows under the series. Reduces visual clutter
            // for the common 1-season scenario.
            children = if (entry.seasons.size == 1) entry.seasons.first().episodes else entry.seasons,
            modifier = Modifier.padding(start = leftInset),
            onItemClick = onItemClick,
            onReadEbook = onReadEbook,
            onOpenExternalDownload = onOpenExternalDownload,
            onDeleteSingle = onDeleteSingle,
            onDeleteEntry = onDeleteEntry,
            depth = depth,
        )

        is DownloadEntry.Season -> ExpandableAggregateRow(
            entry = entry,
            children = entry.episodes,
            modifier = Modifier.padding(start = leftInset),
            onItemClick = onItemClick,
            onReadEbook = onReadEbook,
            onOpenExternalDownload = onOpenExternalDownload,
            onDeleteSingle = onDeleteSingle,
            onDeleteEntry = onDeleteEntry,
            depth = depth,
        )

        is DownloadEntry.Author -> ExpandableAggregateRow(
            entry = entry,
            children = entry.books,
            modifier = Modifier.padding(start = leftInset),
            onItemClick = onItemClick,
            onReadEbook = onReadEbook,
            onOpenExternalDownload = onOpenExternalDownload,
            onDeleteSingle = onDeleteSingle,
            onDeleteEntry = onDeleteEntry,
            depth = depth,
        )
    }
}

@Composable
private fun SectionHeaderRow(
    section: DownloadTypeSection,
    onDeleteSection: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = section.displayName.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
            )
            Text(
                text = "${section.itemCount} item${if (section.itemCount == 1) "" else "s"} · ${formatBytes(section.totalBytesUsed)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDeleteSection) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete all in ${section.displayName}",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SingleRow(
    item: DownloadItem,
    modifier: Modifier = Modifier,
    onItemClick: () -> Unit,
    onReadEbook: () -> Unit,
    onOpenExternalDownload: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    val ebookDetail = if (item.mediaType == DownloadMediaType.Ebook) {
        listOfNotNull(item.displayName, item.container?.uppercase()).joinToString(" · ")
    } else {
        item.subtitle
    }
    val rowAction = downloadRowAction(item)
    val canOpenExternally = downloadExternalOpenAvailable(item)
    val showPrimaryAction = rowAction != DownloadRowAction.None
    val showExternalAction = canOpenExternally && rowAction != DownloadRowAction.OpenExternally

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = rowAction != DownloadRowAction.None) {
                when (rowAction) {
                    DownloadRowAction.ReadInApp -> onReadEbook()
                    DownloadRowAction.OpenExternally -> onOpenExternalDownload()
                    DownloadRowAction.OpenInAppPlayer -> onItemClick()
                    DownloadRowAction.None -> Unit
                }
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThumbhashImage(
            url = item.posterUrl,
            thumbhash = item.posterThumbhash,
            contentDescription = item.title,
            modifier = Modifier
                .width(56.dp)
                .aspectRatio(2f / 3f)
                .clip(MaterialTheme.shapes.small),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            ebookDetail?.takeIf { it.isNotBlank() }?.let { sub ->
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatBytes(item.fileSizeBytes) + downloadQualitySuffix(item.quality),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(8.dp))
                val isProblemStatus = item.status.isProblemStatus(item.isMissingLocal)
                Text(
                    text = downloadStatusLabel(
                        status = item.status,
                        progress = item.progress,
                        isMissingLocal = item.isMissingLocal,
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isProblemStatus) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = if (isProblemStatus) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
            if (!item.isComplete && !item.isFailed && item.progress > 0f) {
                Spacer(modifier = Modifier.height(4.dp))
                org.siloserver.silo.android.ui.components.ProgressIndicator(
                    progress = item.progress,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (showPrimaryAction || showExternalAction) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (showPrimaryAction) {
                        TextButton(
                            onClick = {
                                when (rowAction) {
                                    DownloadRowAction.ReadInApp -> onReadEbook()
                                    DownloadRowAction.OpenExternally -> onOpenExternalDownload()
                                    DownloadRowAction.OpenInAppPlayer -> onItemClick()
                                    DownloadRowAction.None -> Unit
                                }
                            },
                        ) {
                            Text(downloadRowActionLabel(rowAction))
                        }
                    }
                    if (showExternalAction) {
                        TextButton(onClick = onOpenExternalDownload) {
                            Text("Open")
                        }
                    }
                }
            }
        }
        IconButton(onClick = onDeleteClick) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Remove download",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Expandable aggregate row shared by Series and Season entries: an
 * [AggregateRow] header with a remembered expand state, plus the
 * recursively rendered [children] while expanded. Child selection
 * (seasons vs. flattened episodes) is decided at the call site.
 */
@Composable
private fun ExpandableAggregateRow(
    entry: DownloadEntry,
    children: List<DownloadEntry>,
    modifier: Modifier = Modifier,
    onItemClick: (DownloadItem) -> Unit,
    onReadEbook: (DownloadItem) -> Unit,
    onOpenExternalDownload: (DownloadItem) -> Unit,
    onDeleteSingle: (DownloadItem) -> Unit,
    onDeleteEntry: (DownloadEntry) -> Unit,
    depth: Int,
) {
    var expanded by remember(entry.id) { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        AggregateRow(
            title = entry.title,
            subtitle = entry.subtitle ?: "",
            totalBytesUsed = entry.totalBytesUsed,
            progress = entry.progress,
            isComplete = entry.isComplete,
            posterUrl = entry.posterUrl,
            posterThumbhash = entry.posterThumbhash,
            expanded = expanded,
            onToggleExpand = { expanded = !expanded },
            onDelete = { onDeleteEntry(entry) },
        )
        AnimatedVisibility(visible = expanded) {
            Column {
                children.forEach { child ->
                    renderEntry(
                        entry = child,
                        depth = depth + 1,
                        onItemClick = onItemClick,
                        onReadEbook = onReadEbook,
                        onOpenExternalDownload = onOpenExternalDownload,
                        onDeleteSingle = onDeleteSingle,
                        onDeleteEntry = onDeleteEntry,
                    )
                }
            }
        }
    }
}

/**
 * Common chrome for the Series + Season aggregate rows: poster, title,
 * "X of Y" subtitle, total bytes, expand chevron, delete button.
 */
@Composable
private fun AggregateRow(
    title: String,
    subtitle: String,
    totalBytesUsed: Long,
    progress: Float,
    isComplete: Boolean,
    posterUrl: String?,
    posterThumbhash: String?,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpand)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ThumbhashImage(
            url = posterUrl,
            thumbhash = posterThumbhash,
            contentDescription = title,
            modifier = Modifier
                .width(56.dp)
                .aspectRatio(2f / 3f)
                .clip(MaterialTheme.shapes.small),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = formatBytes(totalBytesUsed),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!isComplete && progress > 0f) {
                Spacer(modifier = Modifier.height(4.dp))
                org.siloserver.silo.android.ui.components.ProgressIndicator(
                    progress = progress,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (expanded) {
                    Icons.Default.KeyboardArrowDown
                } else {
                    Icons.AutoMirrored.Filled.KeyboardArrowRight
                },
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Remove all under $title",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** " · 5 Mbps"-style suffix for the size line; empty for original / unset. */
internal fun downloadQualitySuffix(quality: String?): String =
    quality
        ?.takeIf { it.isNotBlank() && it != org.siloserver.silo.model.download.DownloadQuality.Original.wire }
        ?.let { " · " + (org.siloserver.silo.model.download.DownloadQuality.fromWire(it)?.displayName ?: it) }
        .orEmpty()

internal fun downloadStatusLabel(
    status: DownloadStatus,
    progress: Float,
    isMissingLocal: Boolean = false,
): String =
    if (isMissingLocal) {
        "Missing file"
    } else when (status) {
        DownloadStatus.Queued -> "Queued"
        DownloadStatus.Preparing -> "Preparing"
        // Server-side "ready" = artifact serveable, device GET not started —
        // still queued from the user's perspective ("Ready" is reserved for
        // Completed rows with local bytes).
        DownloadStatus.Ready -> "Queued"
        DownloadStatus.Downloading -> {
            val percent = (progress.coerceIn(0f, 1f) * 100).toInt()
            if (percent > 0) "Downloading · $percent%" else "Downloading"
        }
        DownloadStatus.Completed -> "Ready"
        DownloadStatus.Failed -> "Failed"
        DownloadStatus.Cancelled -> "Cancelled"
        DownloadStatus.Unknown -> "Needs attention"
    }

private fun DownloadStatus.isProblemStatus(isMissingLocal: Boolean = false): Boolean =
    isMissingLocal ||
        this == DownloadStatus.Failed ||
        this == DownloadStatus.Cancelled ||
        this == DownloadStatus.Unknown
