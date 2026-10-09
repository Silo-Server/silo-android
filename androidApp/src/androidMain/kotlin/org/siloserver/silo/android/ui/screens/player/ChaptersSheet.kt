package org.siloserver.silo.android.ui.screens.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.ui.util.formatClockTime
import org.siloserver.silo.model.catalog.VersionChapter

/**
 * Chapter picker. Number, title, start and length line up in columns; the
 * chapter that is playing is marked and the list opens scrolled to it.
 */
@Composable
fun ChaptersSheet(
    isVisible: Boolean,
    chapters: List<VersionChapter>,
    onSelect: (chapterIndex: Int) -> Unit,
    onDismiss: () -> Unit,
    position: Double = 0.0,
    /** The title's length: the last chapter's end when the server gives none. */
    duration: Double = 0.0,
    tabletopPaneHeight: Dp? = null,
) {
    if (!isVisible) return

    val currentChapterIndex = chapters.indexOfLast { it.startSeconds <= position }
    val controller = rememberPlayerMenuController(onDismiss)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (currentChapterIndex - 2).coerceAtLeast(0),
    )

    PlayerMenu(controller = controller, tabletopPaneHeight = tabletopPaneHeight) {
        PlayerPanelHeader(title = "Chapters", onClose = { controller.dismiss() })
        val current = chapters.getOrNull(currentChapterIndex)
        val countLabel = if (chapters.size == 1) "1 chapter" else "${chapters.size} chapters"
        Text(
            text = if (current != null) {
                val left = (chapterEnd(chapters, currentChapterIndex, duration) - position).coerceAtLeast(0.0)
                "$countLabel · ${formatClockTime(left)} left in this chapter"
            } else {
                countLabel
            },
            style = PlayerType.RowDetail.copy(fontFeatureSettings = "tnum"),
            modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 6.dp),
        )
        if (chapters.isEmpty()) {
            PlayerFootnote("No chapters are available for this title.")
            return@PlayerMenu
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .playerFadingEdges(listState)
                .padding(horizontal = 8.dp),
        ) {
            itemsIndexed(chapters, key = { _, chapter -> chapter.index }) { index, chapter ->
                val length = chapterEnd(chapters, index, duration) - chapter.startSeconds
                ChapterRow(
                    number = index + 1,
                    title = chapter.title.ifBlank { "Chapter ${chapter.index + 1}" },
                    start = chapter.startSeconds,
                    length = length,
                    progress = if (index == currentChapterIndex) {
                        if (length > 0) ((position - chapter.startSeconds) / length).toFloat() else 0f
                    } else {
                        null
                    },
                    onClick = {
                        onSelect(index)
                        controller.dismiss()
                    },
                )
            }
        }
    }
}

private fun chapterEnd(chapters: List<VersionChapter>, index: Int, duration: Double): Double {
    val chapter = chapters[index]
    return when {
        chapter.endSeconds > chapter.startSeconds -> chapter.endSeconds
        index + 1 < chapters.size -> chapters[index + 1].startSeconds
        duration > chapter.startSeconds -> duration
        else -> chapter.startSeconds
    }
}

@Composable
private fun ChapterRow(
    number: Int,
    title: String,
    start: Double,
    length: Double,
    progress: Float?,
    onClick: () -> Unit,
) {
    val isCurrent = progress != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isCurrent) PlayerChrome.Raised else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(role = Role.Button, onClickLabel = "Play from $title", onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 10.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 10.dp)) {
            Box(modifier = Modifier.width(34.dp)) {
                if (isCurrent) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Now playing",
                        tint = PlayerChrome.Paper,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    Text(
                        text = number.toString().padStart(2, '0'),
                        style = PlayerType.Value.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }
            Text(
                text = title,
                style = PlayerType.RowTitle.copy(
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = formatClockTime(start),
                style = PlayerType.Value,
                textAlign = TextAlign.End,
                modifier = Modifier.width(64.dp),
            )
            Text(
                text = formatClockTime(length.coerceAtLeast(0.0)),
                style = PlayerType.Value,
                textAlign = TextAlign.End,
                modifier = Modifier.width(56.dp),
            )
        }
        if (progress != null) {
            Box(
                modifier = Modifier
                    .padding(start = 34.dp, end = 120.dp, bottom = 6.dp)
                    .fillMaxWidth()
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.14f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(2.dp)
                        .background(PlayerChrome.Paper),
                )
            }
        }
    }
}
