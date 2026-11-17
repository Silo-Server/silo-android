package org.siloserver.silo.android.ui.screens.audiobook

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.siloserver.silo.android.ui.util.formatClockTime
import org.siloserver.silo.android.ui.util.rememberDominantColor
import org.siloserver.silo.audiobook.audiobookChapterLabel
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.i18n.stringResourceWithNames
import org.siloserver.silo.common.i18n.text
import org.siloserver.silo.common.player.AudiobookPlayerUiState
import org.siloserver.silo.common.player.AudiobookPlayerViewModel
import org.siloserver.silo.common.ui.components.ThumbhashImage

/**
 * Now-playing bar for the audiobook session, shown above the tab bar while
 * the full player is minimized. Mirrors silo-apple's `AudioMiniPlayerView`:
 * cover, title, a subtitle (the current chapter, else the author, else the
 * elapsed time), one play/pause button, and a hairline of whole-book progress
 * along the bottom edge. Tapping anywhere else reopens the full player; the
 * session is stopped from there, not here. TalkBack reads the bar as
 * "Now Playing: <title>" with "open the full player" as its action.
 *
 * Placement follows [org.siloserver.silo.android.ui.screens.cast.SiloCastMiniBar],
 * which shares the nav bar's accessory slot: a card above the tabs, or with
 * [inline], the compact capsule beside the minimized tab control. The tab bar
 * draws the card and moves it between the two; this draws what goes inside.
 */
@Composable
fun AudiobookMiniPlayer(
    player: AudiobookPlayerViewModel,
    onOpen: () -> Unit,
    inline: Boolean = false,
) {
    // The state ticks four times a second with the playhead. Only the
    // progress line reads the position each tick (at draw time); everything
    // else reads derived values and recomposes when those change.
    val uiState = player.uiState.collectAsState()
    val coverUrl by remember(uiState) { derivedStateOf { uiState.value.coverUrl } }
    val coverThumbhash by remember(uiState) { derivedStateOf { uiState.value.coverThumbhash } }
    val tint by rememberDominantColor(
        imageUrl = coverUrl,
        fallback = MaterialTheme.colorScheme.primary,
        thumbhash = coverThumbhash,
    )

    // TalkBack reads the bar as "Now Playing: <title>, double-tap to open the
    // full player" (iOS label and hint); play/pause stays its own control.
    val bookTitle by remember(uiState) { derivedStateOf { uiState.value.title } }
    val title = bookTitle.ifBlank { stringResource(CommonR.string.audiobook_player_loading) }
    val openLabel = stringResource(CommonR.string.audiobook_miniPlayer_openAction)
    val nowPlaying = stringResourceWithNames(CommonR.string.audiobook_miniPlayer_nowPlaying, title)

    // The tab bar draws and moves the card; this fills it.
    Box(
        modifier = Modifier
            .then(if (inline) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
            .clickable(onClickLabel = openLabel, role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = nowPlaying },
    ) {
        Row(
            modifier = if (inline) {
                Modifier.fillMaxSize().padding(start = 12.dp, end = 4.dp)
            } else {
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (inline) 10.dp else 12.dp),
        ) {
            MiniPlayerCover(
                coverUrl = coverUrl,
                thumbhash = coverThumbhash,
                size = if (inline) 36.dp else 46.dp,
            )
            MiniPlayerText(
                title = title,
                player = player,
                uiState = uiState,
                showSubtitle = !inline,
                // The bar's own label names the book; reading the
                // visible lines too would say the title twice.
                modifier = Modifier.weight(1f).clearAndSetSemantics {},
            )
            MiniPlayerPlayPause(player = player, uiState = uiState)
        }
        MiniPlayerProgressLine(
            uiState = uiState,
            color = tint,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // Inset so the line stays inside the rounded ends.
                .padding(horizontal = if (inline) 24.dp else 16.dp)
                .padding(bottom = if (inline) 5.dp else 0.dp)
                .height(2.dp),
        )
    }
}

@Composable
private fun MiniPlayerCover(coverUrl: String?, thumbhash: String?, size: Dp) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        if (!coverUrl.isNullOrBlank()) {
            ThumbhashImage(
                url = coverUrl,
                thumbhash = thumbhash,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        } else {
            Icon(
                Icons.Outlined.Headphones,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun MiniPlayerText(
    title: String,
    player: AudiobookPlayerViewModel,
    uiState: State<AudiobookPlayerUiState>,
    showSubtitle: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (showSubtitle) {
            val chapterIndex by player.currentChapterIndex.collectAsState()
            // The chapter, else the author, else the elapsed time (iOS order).
            val chapter by remember(uiState) {
                derivedStateOf {
                    uiState.value.chapters.getOrNull(chapterIndex)?.let { audiobookChapterLabel(chapterIndex, it.title) }
                }
            }
            val byline by remember(uiState) {
                derivedStateOf { uiState.value.author ?: uiState.value.narrator }
            }
            val subtitle = chapter?.text()
                ?: byline?.takeIf { it.isNotBlank() }
                ?: ElapsedText(uiState)
            Text(
                text = subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Elapsed time, recomposing once a second rather than on every tick. */
@Composable
private fun ElapsedText(uiState: State<AudiobookPlayerUiState>): String {
    val elapsed by remember(uiState) { derivedStateOf { formatClockTime(uiState.value.positionSeconds) } }
    return elapsed
}

@Composable
private fun MiniPlayerPlayPause(player: AudiobookPlayerViewModel, uiState: State<AudiobookPlayerUiState>) {
    // Drive the icon from pause intent, like the full player's transport, so
    // it stays "pause" through the rebuffer a seek causes.
    val paused by remember(uiState) { derivedStateOf { uiState.value.isPaused } }
    // After a failure Play stays live: it starts playback again.
    val enabled by remember(uiState) {
        derivedStateOf { uiState.value.streamUrl != null || uiState.value.canRetryPlayback }
    }
    IconButton(onClick = player::togglePlay, enabled = enabled) {
        Icon(
            if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
            contentDescription = stringResource(
                if (paused) CommonR.string.player_audio_play else CommonR.string.player_audio_pause,
            ),
        )
    }
}

/**
 * Whole-book progress. The position is read while drawing, so a tick redraws
 * this line without recomposing the bar. It always fills left to right, like
 * the full player's timeline, in every layout direction.
 */
@Composable
private fun MiniPlayerProgressLine(
    uiState: State<AudiobookPlayerUiState>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val state = uiState.value
        val fraction = if (state.durationSeconds > 0) {
            (state.positionSeconds / state.durationSeconds).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }
        if (fraction <= 0f) return@Canvas
        val y = size.height / 2
        drawLine(
            color = color,
            start = Offset(0f, y),
            end = Offset(size.width * fraction, y),
            strokeWidth = size.height,
            cap = StrokeCap.Round,
        )
    }
}
