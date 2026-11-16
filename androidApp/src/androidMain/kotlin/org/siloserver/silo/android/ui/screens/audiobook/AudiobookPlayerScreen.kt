package org.siloserver.silo.android.ui.screens.audiobook

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.siloserver.silo.common.audiobook.AudioPlayerBackground
import org.siloserver.silo.common.audiobook.rememberAudioCoverPalette
import org.siloserver.silo.common.i18n.LocaleFormat
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.player.AudiobookPlayerViewModel
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.android.ui.theme.SiloBackground
import org.siloserver.silo.common.player.errorText
import org.siloserver.silo.common.player.skipIntervalErrorText

/**
 * Dedicated audiobook player UI. Big square cover up top, then title /
 * author / narrator, then the position slider, then transport row
 * (±30s, big play/pause), then speed + sleep timer chips.
 *
 * The screen only shows [viewModel]'s session. Playback runs in the app-wide
 * [AudiobookPlaybackBridge], so [onMinimize] (the chevron, or Back) leaves the
 * book playing under the mini player; [onStop] ends the session.
 */
@Composable
fun AudiobookPlayerScreen(
    viewModel: AudiobookPlayerViewModel,
    onMinimize: () -> Unit,
    onStop: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val currentChapterIndex by viewModel.currentChapterIndex.collectAsState()

    // Apple's AudioCoverPalette: the cover's quadrant colors tint the
    // backdrop and its most vivid one becomes the controls' accent.
    val palette = rememberAudioCoverPalette(state.coverUrl, fallbackAccent = MaterialTheme.colorScheme.primary)
    val accent = palette.accent
    val isPlaying = !state.isPaused && state.streamUrl != null
    val chapterTitle = state.chapters.getOrNull(currentChapterIndex)
        ?.let { currentChapterIndex to it }
        ?.let { (index, chapter) ->
            chapter.title.takeIf { it.isNotBlank() }
                ?: stringResource(CommonR.string.detail_audiobook_chapter, LocaleFormat.integer(index + 1))
        }

    Box(modifier = Modifier.fillMaxSize()) {
        // The player stays on black like the video player; the cover mesh
        // drifts over it while the book plays.
        AudioPlayerBackground(
            palette = palette,
            isPlaying = isPlaying,
            baseColor = SiloBackground,
            modifier = Modifier.fillMaxSize(),
        )
        // Apple caps the player column at 560pt, centered, so a tablet or an
        // open foldable keeps the phone's proportions instead of stretching.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .widthIn(max = 560.dp)
                .fillMaxSize()
                // The background runs under the system bars; the controls
                // stay clear of them so the minimize and stop buttons take taps.
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            // Top bar mirrors iOS: chevron-down to minimize (left), 48dp hit
            // targets (Android minimum), secondary tint, and the bookmark and
            // stop actions on the trailing side.
            var showBookmarksSheet by remember { mutableStateOf(false) }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onMinimize,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = stringResource(CommonR.string.audiobook_player_minimize),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { showBookmarksSheet = true },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Filled.Bookmark,
                        contentDescription = stringResource(CommonR.string.audiobook_bookmarks_open),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Ends the session; the chevron only minimizes (iOS xmark).
                IconButton(
                    onClick = onStop,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(CommonR.string.audiobook_player_stop),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (showBookmarksSheet) {
                val bookmarks by viewModel.bookmarks.collectAsState()
                AudiobookBookmarksSheet(
                    bookmarks = bookmarks,
                    onJumpTo = { viewModel.jumpToBookmark(it); showBookmarksSheet = false },
                    onDelete = { viewModel.removeBookmark(it.id) },
                    onAddCurrent = { viewModel.addBookmark() },
                    onDismiss = { showBookmarksSheet = false },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            AudiobookCoverHeader(
                title = state.title,
                author = state.author,
                chapterTitle = chapterTitle,
                coverUrl = state.coverUrl,
                coverThumbhash = state.coverThumbhash,
                isPlaying = isPlaying,
                accent = accent,
            )

            Spacer(modifier = Modifier.height(24.dp))

            ChapterProgressBar(
                chapters = state.chapters,
                positionSeconds = state.positionSeconds,
                durationSeconds = state.durationSeconds,
                accent = accent,
                skipBackSeconds = state.skipBackSeconds,
                skipForwardSeconds = state.skipForwardSeconds,
                onSeek = { viewModel.seekTo(it) },
                modifier = Modifier.padding(horizontal = 8.dp),
            )

            Spacer(modifier = Modifier.height(20.dp))

            AudiobookTransport(
                // Drive the icon from pause intent so it stays "pause" through the
                // transient rebuffer a seek causes (isPlaying briefly flips false).
                isPlaying = !state.isPaused,
                // After a failure Play stays live: it starts playback again.
                enabled = state.streamUrl != null || state.canRetryPlayback,
                hasChapters = state.chapters.isNotEmpty(),
                skipBackSeconds = state.skipBackSeconds,
                skipForwardSeconds = state.skipForwardSeconds,
                onPrevChapter = { viewModel.skipToPreviousChapter() },
                onSkipBack = { viewModel.skipBack() },
                onTogglePlay = { viewModel.togglePlay() },
                onSkipForward = { viewModel.skipForward() },
                onNextChapter = { viewModel.skipToNextChapter() },
                accent = accent,
            )

            Spacer(modifier = Modifier.height(26.dp))

            // Secondary controls: speed / skip / sleep / chapters.
            var showSpeedSheet by remember { mutableStateOf(false) }
            var showSleepSheet by remember { mutableStateOf(false) }
            var showSkipSheet by remember { mutableStateOf(false) }
            var showChaptersSheet by remember { mutableStateOf(false) }
            AudiobookSecondaryBar(
                speedLabel = stringResource(CommonR.string.audiobook_speed_value, formatSpeed(state.playbackSpeed)),
                sleepLabel = state.sleepTimerMinutesLeft
                    ?.let { pluralStringResource(CommonR.plurals.audiobook_sleepTimer_minutesShort, it, it) }
                    ?: stringResource(CommonR.string.audiobook_sleepTimer_button),
                skipLabel = stringResource(CommonR.string.audiobook_skip_secondsShort, state.skipBackSeconds),
                onSpeedClick = { showSpeedSheet = true },
                onSleepClick = { showSleepSheet = true },
                onSkipClick = { showSkipSheet = true },
                onChaptersClick = { showChaptersSheet = true },
                showChapters = state.chapters.isNotEmpty(),
                speedActive = state.playbackSpeed != 1.0f,
                sleepActive = state.sleepTimerMinutesLeft != null,
                accent = accent,
            )

            if (showSpeedSheet) {
                AudiobookSpeedSheet(
                    currentSpeed = state.playbackSpeed,
                    onSpeedChanged = viewModel::setSpeed,
                    onSetDefault = viewModel::setDefaultSpeed,
                    onDismiss = { showSpeedSheet = false },
                )
            }
            if (showSkipSheet) {
                AudiobookSkipIntervalSheet(
                    skipBackSeconds = state.skipBackSeconds,
                    skipForwardSeconds = state.skipForwardSeconds,
                    onSkipBackSelected = viewModel::setSkipBackSeconds,
                    onSkipForwardSelected = viewModel::setSkipForwardSeconds,
                    onDismiss = {
                        showSkipSheet = false
                        viewModel.clearSkipIntervalError()
                    },
                    choices = state.skipIntervalChoices,
                    profileWide = state.skipIntervalsProfileWide,
                    editable = state.skipIntervalsEditable,
                    errorMessage = state.skipIntervalErrorText(),
                )
            }
            if (showSleepSheet) {
                val choice by viewModel.sleepTimerChoice.collectAsState()
                AudiobookSleepTimerSheet(
                    currentChoice = choice,
                    minutesLeft = state.sleepTimerMinutesLeft,
                    onChoiceSelected = viewModel::applySleepTimer,
                    onDismiss = { showSleepSheet = false },
                )
            }

            if (showChaptersSheet) {
                ChaptersSheet(
                    chapters = state.chapters,
                    currentChapterIndex = currentChapterIndex,
                    onJumpTo = { viewModel.jumpToChapter(it) },
                    onDismiss = { showChaptersSheet = false },
                    accent = accent,
                )
            }

            // About / description — shown in the player itself so it's available
            // offline too, where there's no detail screen to read it from.
            state.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                Spacer(modifier = Modifier.height(28.dp))
                Text(
                    text = stringResource(CommonR.string.audiobook_about_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Loading / error states.
            if (state.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(CommonR.string.audiobook_player_loading))
                }
            }
            state.errorText()?.let { err ->
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = err, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
