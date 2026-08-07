package org.siloserver.silo.android.ui.screens.player

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.siloserver.silo.android.ui.util.formatClockTime
import org.siloserver.silo.model.catalog.VersionChapter

/** Adaptive chapter picker shared by regular phones and foldable tabletop mode. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChaptersSheet(
    isVisible: Boolean,
    chapters: List<VersionChapter>,
    onSelect: (chapterIndex: Int) -> Unit,
    onDismiss: () -> Unit,
    position: Double = 0.0,
    tabletopPaneHeight: Dp? = null,
) {
    if (!isVisible) return

    val currentChapterIndex = chapters.indexOfLast { it.startSeconds <= position }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val dismissSheet = {
        scope.launch { sheetState.hide() }
        onDismiss()
    }

    LaunchedEffect(isVisible) {
        if (isVisible) sheetState.show()
    }

    PlayerModalBottomSheet(
        onDismissRequest = dismissSheet,
        sheetState = sheetState,
        tabletopPaneHeight = tabletopPaneHeight,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .playerSheetContent(tabletopPaneHeight)
                .nestedScroll(PlayerSheetFlingGuard),
        ) {
            PlayerSheetHeader(
                title = "Chapters",
                subtitle = when (chapters.size) {
                    1 -> "1 chapter"
                    else -> "${chapters.size} chapters"
                },
                onDismiss = dismissSheet,
            )

            if (chapters.isEmpty()) {
                PlayerSheetCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = playerSheetHorizontalPadding(tabletopPaneHeight),
                            vertical = 8.dp,
                        ),
                ) {
                    Text(
                        text = "No chapters are available for this title.",
                        color = Color.White.copy(alpha = 0.62f),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(20.dp),
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(if (tabletopPaneHeight == null) 1 else 2),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (tabletopPaneHeight == null) Modifier else Modifier.weight(1f),
                        ),
                    contentPadding = PaddingValues(
                        start = playerSheetHorizontalPadding(tabletopPaneHeight),
                        end = playerSheetHorizontalPadding(tabletopPaneHeight),
                        top = 8.dp,
                        bottom = 24.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(
                        chapters,
                        key = { _, chapter -> chapter.index },
                        contentType = { _, _ -> "chapter-card" },
                    ) { index, chapter ->
                        ChapterCard(
                            chapter = chapter,
                            isCurrent = index == currentChapterIndex,
                            onClick = {
                                onSelect(index)
                                dismissSheet()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterCard(
    chapter: VersionChapter,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Surface(
        color = if (isCurrent) PlayerSheetSelectedColor else PlayerSheetCardColor,
        shape = shape,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .then(
                if (isCurrent) {
                    Modifier.border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                        shape = shape,
                    )
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = if (isCurrent) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                } else {
                    Color.White.copy(alpha = 0.07f)
                },
                modifier = Modifier.size(38.dp),
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    if (isCurrent) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Now playing",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    } else {
                        Text(
                            text = (chapter.index + 1).toString(),
                            color = Color.White.copy(alpha = 0.72f),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = chapter.title.ifBlank { "Chapter ${chapter.index + 1}" },
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatClockTime(chapter.startSeconds),
                    color = Color.White.copy(alpha = 0.52f),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}
