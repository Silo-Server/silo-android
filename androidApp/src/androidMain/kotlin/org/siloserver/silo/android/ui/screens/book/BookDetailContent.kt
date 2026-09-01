package org.siloserver.silo.android.ui.screens.book

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.ui.screens.detail.CircleActionButton
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.model.book.BookFormat
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.ItemDetail
import org.siloserver.silo.model.ebook.bookFormatFromEbookVersion
import org.siloserver.silo.model.ebook.ebookFormatDisplayName
import org.siloserver.silo.model.ebook.ebookFormatSupport
import org.siloserver.silo.model.ebook.isSupportedEbookVersion
import androidx.compose.foundation.lazy.rememberLazyListState
import org.siloserver.silo.common.ui.components.DeferImagePresentationWhileScrolling

/**
 * Phone book detail. Tall cover on the left (2:3 like a movie poster
 * since print covers share that ratio), author + format + page count
 * on the right, then a Read button + overview.
 */
internal enum class BookDetailPrimaryAction {
    None,
    ReadInApp,
    OpenExternally,
}

private const val BookCoverWidthDp = 168

internal fun bookDetailPrimaryAction(
    selectedVersion: FileVersion?,
    canReadSelectedVersion: Boolean,
    isDownloaded: Boolean,
    canOpenExternal: Boolean,
): BookDetailPrimaryAction = when {
    selectedVersion == null -> BookDetailPrimaryAction.None
    canReadSelectedVersion -> BookDetailPrimaryAction.ReadInApp
    isDownloaded &&
        canOpenExternal &&
        selectedVersion.isSupportedEbookVersion() -> BookDetailPrimaryAction.OpenExternally
    else -> BookDetailPrimaryAction.None
}

private fun bookDetailPrimaryActionLabel(
    selectedVersion: FileVersion?,
    canReadSelectedVersion: Boolean,
    isDownloaded: Boolean,
    canOpenExternal: Boolean,
): String = when (
    bookDetailPrimaryAction(
        selectedVersion = selectedVersion,
        canReadSelectedVersion = canReadSelectedVersion,
        isDownloaded = isDownloaded,
        canOpenExternal = canOpenExternal,
    )
) {
    BookDetailPrimaryAction.ReadInApp -> "Read"
    BookDetailPrimaryAction.OpenExternally -> "Open in Reader"
    BookDetailPrimaryAction.None -> when {
        selectedVersion == null -> "Unavailable"
        !canReadSelectedVersion && !isDownloaded -> "Download to Open"
        else -> "Open from Downloads"
    }
}

@Composable
fun BookDetailContent(
    detail: ItemDetail,
    isFavorite: Boolean,
    isInWatchlist: Boolean,
    selectedVersionIndex: Int,
    onVersionSelected: (Int) -> Unit,
    canReadSelectedVersion: Boolean,
    isDownloaded: Boolean = false,
    downloadProgress: Float? = null,
    onReadClick: (fileId: Int?) -> Unit,
    onFavoriteClick: () -> Unit,
    onWatchlistClick: () -> Unit,
    onDownloadClick: (() -> Unit)? = null,
    onOpenExternalClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val meta = detail.book
    val ebook = detail.ebook
    val selectedVersion = detail.versions.getOrNull(selectedVersionIndex)
    val canDownloadSelectedVersion = selectedVersion?.isSupportedEbookVersion() == true && onDownloadClick != null
    val selectedFormatSupport = selectedVersion?.ebookFormatSupport()
    val primaryAction = bookDetailPrimaryAction(
        selectedVersion = selectedVersion,
        canReadSelectedVersion = canReadSelectedVersion,
        isDownloaded = isDownloaded,
        canOpenExternal = onOpenExternalClick != null,
    )
    val format = selectedVersion?.bookFormatFromEbookVersion()
        ?: meta?.formatEnum()
        ?: BookFormat.Unknown
    val author = ebook?.authorNames ?: meta?.author
    val publisher = ebook?.publisher ?: meta?.publisher
    // Clear the status bar / camera cutout so the cover + title aren't tucked
    // under it (matches the audiobook detail).
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
            top = 32.dp + topInset,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ThumbhashImage(
                    url = detail.posterUrl,
                    thumbhash = detail.posterThumbhash,
                    contentDescription = detail.title,
                    modifier = Modifier
                        .width(BookCoverWidthDp.dp)
                        .aspectRatio(2f / 3f)
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
                author?.takeIf { it.isNotBlank() }?.let { authorName ->
                    Text(
                        text = "by $authorName",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    FormatBadge(format)
                    meta?.pageCount?.let { Text("· $it pages", style = MaterialTheme.typography.labelMedium) }
                }
                publisher?.takeIf { it.isNotBlank() }?.let { publisherName ->
                    Text(
                        text = publisherName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                ebook?.series?.name?.takeIf { it.isNotBlank() }?.let { seriesName ->
                    Text(
                        text = "Series: $seriesName",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        when (primaryAction) {
                            BookDetailPrimaryAction.ReadInApp -> selectedVersion?.let { onReadClick(it.fileId) }
                            BookDetailPrimaryAction.OpenExternally -> onOpenExternalClick?.invoke()
                            BookDetailPrimaryAction.None -> Unit
                        }
                    },
                    enabled = primaryAction != BookDetailPrimaryAction.None,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        bookDetailPrimaryActionLabel(
                            selectedVersion = selectedVersion,
                            canReadSelectedVersion = canReadSelectedVersion,
                            isDownloaded = isDownloaded,
                            canOpenExternal = onOpenExternalClick != null,
                        ),
                    )
                }
                OutlinedButton(
                    onClick = { onDownloadClick?.invoke() },
                    enabled = canDownloadSelectedVersion && !isDownloaded,
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
                            canDownloadSelectedVersion -> "Download"
                            else -> "Download Unavailable"
                        },
                    )
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
                downloadProgress?.let { progress ->
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (selectedVersion != null && !canReadSelectedVersion) {
                    Text(
                        text = selectedFormatSupport?.reason
                            ?: "This format can be downloaded in its original file format and opened from Downloads or another reader.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // Only surface the Versions picker when there's an actual choice to
        // make; a single-format book already shows its format via the header
        // badge, so listing "Versions: EPUB" again is redundant.
        if (detail.versions.size > 1) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Versions",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    detail.versions.forEachIndexed { index, version ->
                        val label = version.ebookFormatDisplayName()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (index == selectedVersionIndex) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                    },
                                )
                                .clickable { onVersionSelected(index) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "file ${version.fileId}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        detail.overview?.takeIf { it.isNotBlank() }?.let { overview ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "About",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = overview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        ebook?.related?.alsoByAuthor
            ?.filter { it.contentId.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }
            ?.let { related ->
            item {
                Text(
                    text = "Also by this author",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            items(related, key = { it.contentId }) { item ->
                Text(
                    text = listOfNotNull(item.title, item.year?.toString()).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
    }
}

@Composable
private fun FormatBadge(format: BookFormat) {
    Text(
        text = format.displayName,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}
