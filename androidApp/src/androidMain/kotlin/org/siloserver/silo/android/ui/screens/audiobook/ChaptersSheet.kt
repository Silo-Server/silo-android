package org.siloserver.silo.android.ui.screens.audiobook

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.ui.util.formatClockTime
import org.siloserver.silo.audiobook.audiobookChapterLabel
import org.siloserver.silo.model.catalog.VersionChapter
import androidx.compose.ui.res.stringResource
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.i18n.text

/**
 * Chapters bottom sheet. Replaces the always-expanded inline list. Highlights
 * the current chapter and auto-scrolls to it once when the sheet opens so the
 * listener lands on "where they are" even at chapter 90 of 111.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChaptersSheet(
    chapters: List<VersionChapter>,
    currentChapterIndex: Int,
    onJumpTo: (VersionChapter) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()

    // Auto-scroll to the current chapter once on open; keep it near the top
    // (offset by a couple rows) so context above is visible.
    LaunchedEffect(Unit) {
        val target = (currentChapterIndex - 2).coerceAtLeast(0)
        if (currentChapterIndex in chapters.indices) {
            listState.scrollToItem(target)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp).fillMaxWidth()) {
            Text(
                text = stringResource(CommonR.string.options_chapters),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(12.dp))
            LazyColumn(
                state = listState,
                modifier = Modifier.heightIn(max = 480.dp),
            ) {
                itemsIndexed(chapters, key = { _, c -> c.index }) { idx, chapter ->
                    val isCurrent = idx == currentChapterIndex
                    // iOS chapterRow: HStack(spacing: 14), a 24pt leading slot
                    // holding either the accent waveform (current) or the
                    // monospaced chapter number, then the title, then the start
                    // time. Rows are plain (no fill) and use accent text for the
                    // current chapter.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onJumpTo(chapter)
                                onDismiss()
                            }
                            .padding(vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (isCurrent) {
                            Icon(
                                imageVector = Icons.Filled.GraphicEq,
                                contentDescription = stringResource(CommonR.string.player_chapters_nowPlaying),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.width(24.dp).height(16.dp),
                            )
                        } else {
                            Text(
                                text = "${idx + 1}", // i18n-ignore
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(24.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = audiobookChapterLabel(idx, chapter.title).text(),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = formatClockTime(chapter.startSeconds),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
