package org.siloserver.silo.tv.ui.screens.audiobook

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.tv.ui.focus.claimFocusOrReport
import org.siloserver.silo.tv.ui.focus.TvObservedFocusResult
import org.siloserver.silo.tv.ui.focus.TvContentInitialFocusMaxAttempts
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import org.siloserver.silo.model.audiobook.AudiobookBookmark

/**
 * TV audiobook Bookmarks overlay. Mirrors the phone's bookmarks sheet over the
 * shared AudiobookPlayerViewModel: an "Add at current position" action, the
 * saved bookmark list (OK jumps to a bookmark; long-press OK deletes it). Built
 * on the same [TvAudiobookOverlayScaffold] as the chapters/speed panels.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvAudiobookBookmarksPanel(
    bookmarks: List<AudiobookBookmark>,
    onAddCurrent: () -> Unit,
    onJumpTo: (AudiobookBookmark) -> Unit,
    onDelete: (AudiobookBookmark) -> Unit,
    modifier: Modifier = Modifier,
    onFocusAcquisitionFailed: () -> Unit = {},
) {
    val addFocus = remember { FocusRequester() }

    TvAudiobookOverlayScaffold(
        title = "Bookmarks",
        initialFocus = addFocus,
        modifier = modifier,
        onAcquisitionFailed = onFocusAcquisitionFailed,
    ) {
        BookmarkActionRow(
            label = "Bookmark here",
            focusRequester = addFocus,
            onSelect = onAddCurrent,
        )

        Spacer(Modifier.height(8.dp))

        if (bookmarks.isEmpty()) {
            Text(
                text = "No bookmarks yet. Use \"Bookmark here\" to save your place.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.6f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        } else {
            Text(
                text = "OK jumps · hold OK to delete",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(bookmarks, key = { it.id }) { bookmark ->
                    BookmarkRow(
                        label = bookmarkLabel(bookmark),
                        trailing = formatAudiobookTime(bookmark.positionSeconds),
                        onSelect = { onJumpTo(bookmark) },
                        onDelete = {
                            onDelete(bookmark)
                            // The deleted row (keyed by id) leaves composition, so
                            // move focus back to a stable anchor instead of losing
                            // it. A click handler has no suspend point, so this is
                            // single-shot and reported rather than swallowed.
                            addFocus.claimFocusOrReport(
                                target = "audiobook_bookmark_add",
                                action = "bookmark_deleted",
                            )
                        },
                    )
                }
            }
        }
    }
}

private fun bookmarkLabel(bookmark: AudiobookBookmark): String =
    bookmark.note?.takeIf { it.isNotBlank() }
        ?: bookmark.chapterTitle?.takeIf { it.isNotBlank() }
        ?: "Bookmark"

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookmarkActionRow(
    label: String,
    focusRequester: FocusRequester,
    onSelect: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (isFocused) Color.White else Color.White.copy(alpha = 0.12f))
            .focusRequester(focusRequester)
            .focusable(interactionSource = interactionSource)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onSelect,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "+  $label",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (isFocused) Color.Black else Color.White,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun BookmarkRow(
    label: String,
    trailing: String,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val fg = if (isFocused) Color.Black else Color.White
    // TV Surface (same pattern as TvMediaCard's Card): foundation
    // combinedClickable's onLongClick is unreliable for hardware D-pad
    // long-press, and delete is the only removal path for a bookmark.
    Surface(
        onClick = onSelect,
        onLongClick = onDelete,
        interactionSource = interactionSource,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White,
            pressedContentColor = Color.Black,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = trailing,
                style = MaterialTheme.typography.bodyMedium,
                color = fg.copy(alpha = 0.7f),
            )
        }
    }
}
