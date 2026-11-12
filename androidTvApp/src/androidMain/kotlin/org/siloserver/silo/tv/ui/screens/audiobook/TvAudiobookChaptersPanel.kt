package org.siloserver.silo.tv.ui.screens.audiobook

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import org.siloserver.silo.audiobook.audiobookChapterLabel
import org.siloserver.silo.model.catalog.VersionChapter
import androidx.compose.ui.res.stringResource
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.i18n.text

/**
 * Full-screen, focusable chapters overlay. Auto-scrolls to + highlights the
 * current chapter, and grabs focus on it so the D-pad can select immediately;
 * Select jumps. Back is handled by the host screen. Replaces the phone
 * ChaptersSheet (spec §4.9).
 */
@Composable
fun TvAudiobookChaptersPanel(
    chapters: List<VersionChapter>,
    currentChapterIndex: Int,
    onSelectChapter: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onFocusAcquisitionFailed: () -> Unit = {},
) {
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val focusIndex = currentChapterIndex.coerceIn(0, (chapters.size - 1).coerceAtLeast(0))

    // Scroll only. Focus acquisition is the scaffold's, because the current
    // chapter can sit hundreds of rows down: it is not laid out until the scroll
    // lands, and a fixed sleep-then-request-once raced that every time.
    LaunchedEffect(Unit) {
        if (focusIndex in chapters.indices) {
            runCatching { listState.scrollToItem(focusIndex) }
        }
    }

    TvAudiobookOverlayScaffold(
        title = stringResource(CommonR.string.options_chapters),
        initialFocus = focusRequester,
        modifier = modifier,
        onAcquisitionFailed = onFocusAcquisitionFailed,
    ) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(chapters) { index, chapter ->
                TvAudiobookOverlayRow(
                    label = audiobookChapterLabel(index, chapter.title).text(),
                    trailing = formatAudiobookTime(chapter.startSeconds),
                    isCurrent = index == currentChapterIndex,
                    focusRequester = if (index == focusIndex) focusRequester else null,
                    onSelect = { onSelectChapter(index) },
                )
            }
        }
    }
}
