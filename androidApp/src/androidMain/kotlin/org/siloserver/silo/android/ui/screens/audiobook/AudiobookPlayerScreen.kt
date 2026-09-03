@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.siloserver.silo.android.ui.screens.audiobook

import android.content.ComponentName
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import org.siloserver.silo.android.ui.util.rememberDominantColor
import org.siloserver.silo.common.player.AudiobookPlayerViewModel
import org.siloserver.silo.common.player.SiloPlaybackService
import org.siloserver.silo.common.ui.components.ThumbhashImage
import org.siloserver.silo.android.ui.theme.SiloBackground
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel

/**
 * Dedicated audiobook player UI. Big square cover up top, then title /
 * author / narrator, then the position slider, then transport row
 * (±30s, big play/pause), then speed + sleep timer chips.
 *
 * Media3 wiring happens here (not in the ViewModel) so the controller
 * lifecycle is tied to the Compose tree — same pattern as the video
 * PlayerScreen. The controller binds to the shared playback service and
 * plays either an offline original file URI or a server playback stream.
 */
@Composable
fun AudiobookPlayerScreen(
    onBackClick: () -> Unit,
    viewModel: AudiobookPlayerViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val currentChapterIndex by viewModel.currentChapterIndex.collectAsState()
    val chapterCountLabel by viewModel.chapterCountLabel.collectAsState()
    val context = LocalContext.current
    var exitRequested by remember { mutableStateOf(false) }

    // MediaController binds asynchronously to the shared playback service;
    // the same one the video player uses, so a single MediaSession owns
    // foreground audio at any time (system controls / notifications stay
    // consistent).
    var controller by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, SiloPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener(
            {
                if (future.isDone && !future.isCancelled) {
                    controller = runCatching { future.get() }.getOrNull()
                }
            },
            MoreExecutors.directExecutor(),
        )
        onDispose {
            controller?.let { c ->
                runCatching { c.pause(); c.stop(); c.clearMediaItems() }
                c.release()
            }
            controller = null
            if (!future.isDone) future.cancel(true)
        }
    }

    // Wire the stream URL into Media3 once both the controller and the
    // metadata have resolved. We deliberately rebuild the MediaItem when
    // streamUrl changes so a navigation from one audiobook to another
    // doesn't leave the previous bytes playing. Resume-on-open is
    // applied here too: if the VM resolved a saved position, seek to
    // it before play.
    var preparedUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(controller, state.streamUrl) {
        if (exitRequested) return@LaunchedEffect
        val c = controller ?: return@LaunchedEffect
        val url = state.streamUrl ?: return@LaunchedEffect
        // Prepare each distinct stream exactly once. The effect can re-run with
        // the same url (e.g. the controller rebinding); without this guard the
        // second pass — after the resume position was already consumed — would
        // setMediaItem with startMs=0 and reset the book back to 0:00.
        if (url == preparedUrl) return@LaunchedEffect
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(state.title)
                    .setArtist(state.author ?: state.narrator)
                    .also { mb ->
                        state.coverUrl?.takeIf { it.isNotBlank() }
                            ?.let { mb.setArtworkUri(android.net.Uri.parse(it)) }
                    }
                    .build(),
            )
            .build()
        // Apply the resume position as the media item's start position rather
        // than a post-prepare seekTo: a seek issued before the timeline window
        // is known gets dropped, leaving the book at 0:00. setMediaItem with a
        // start position is honored once the source prepares.
        val resume = viewModel.resumePositionSeconds.value
        val startMs = ((resume ?: 0.0).coerceAtLeast(0.0) * 1000).toLong()
        c.setMediaItem(mediaItem, startMs)
        c.prepare()
        if (resume != null && resume > 0) viewModel.consumeResumePosition()
        c.playWhenReady = true
        preparedUrl = url
    }

    // Flush position on every meaningful transition so a process kill
    // loses at most a couple of seconds. Periodic save inside the VM
    // covers the in-flight case.
    LaunchedEffect(controller, state.isPaused) {
        if (state.isPaused) viewModel.flushPosition()
    }
    DisposableEffect(Unit) {
        onDispose {
            viewModel.flushPosition()
            viewModel.stopPlaybackSession()
        }
    }

    // Listener bridge — Player state changes go back into the VM so the UI
    // is always a function of Media3's truth.
    DisposableEffect(controller) {
        val c = controller
        if (c == null) {
            onDispose { }
        } else {
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    viewModel.onPlayingChanged(isPlaying)
                }

                // Pause intent — distinct from transient buffering. Without
                // this a seek's rebuffer would latch the book paused.
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    viewModel.onPauseStateChanged(!playWhenReady)
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    viewModel.onPlayerError(error)
                }
            }
            c.addListener(listener)
            onDispose { runCatching { c.removeListener(listener) } }
        }
    }

    // Position poller — MediaController doesn't push position updates;
    // sample at 4Hz which is smooth enough for an audiobook slider. The
    // current stream URI rides along so the VM can tie cross-part settle
    // to stream identity, not just engine time.
    LaunchedEffect(controller) {
        while (true) {
            controller?.let { c ->
                viewModel.onPositionChanged(
                    c.currentPosition / 1000.0,
                    c.currentMediaItem?.localConfiguration?.uri?.toString(),
                )
            }
            delay(250)
        }
    }

    // Mirror play/pause toggle into the controller.
    LaunchedEffect(controller, state.isPaused) {
        controller?.playWhenReady = !state.isPaused
    }

    // Apply speed when the user cycles.
    LaunchedEffect(controller, state.playbackSpeed) {
        controller?.playbackParameters = PlaybackParameters(state.playbackSpeed)
    }

    // Apply seeks requested by the VM (chapter jumps, ±30s).
    val pendingSeek by viewModel.pendingSeekToSeconds.collectAsState()
    LaunchedEffect(controller, pendingSeek) {
        val seconds = pendingSeek ?: return@LaunchedEffect
        controller?.seekTo((seconds * 1000).toLong())
        viewModel.consumePendingSeek()
    }

    val playerTint by rememberDominantColor(state.coverUrl, fallback = MaterialTheme.colorScheme.primary)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                audiobookPlayerBackgroundBrush(
                    tint = playerTint,
                    // The player stays on black like the video player and the
                    // iOS AudioPlayerBackground; only page canvases moved to
                    // the charcoal Material background.
                    background = SiloBackground,
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            // Top bar mirrors iOS: chevron-down to minimize (left), 48dp hit
            // targets (Android minimum), secondary tint, and the bookmark action
            // on the trailing side.
            var showBookmarksSheet by remember { mutableStateOf(false) }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        exitRequested = true
                        viewModel.flushPosition()
                        viewModel.stopPlaybackSession()
                        onBackClick()
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowDown,
                        contentDescription = "Minimize Player",
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
                        contentDescription = "Bookmarks",
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
                narrator = state.narrator,
                coverUrl = state.coverUrl,
                coverThumbhash = state.coverThumbhash,
            )

            Spacer(modifier = Modifier.height(24.dp))

            ChapterProgressBar(
                chapters = state.chapters,
                chapterCountLabel = chapterCountLabel,
                positionSeconds = state.positionSeconds,
                durationSeconds = state.durationSeconds,
                onSeek = { viewModel.seekTo(it) },
                modifier = Modifier.padding(horizontal = 8.dp),
            )

            Spacer(modifier = Modifier.height(20.dp))

            AudiobookTransport(
                // Drive the icon from pause intent so it stays "pause" through the
                // transient rebuffer a seek causes (isPlaying briefly flips false).
                isPlaying = !state.isPaused,
                enabled = state.streamUrl != null,
                hasChapters = state.chapters.isNotEmpty(),
                skipBackSeconds = state.skipBackSeconds,
                skipForwardSeconds = state.skipForwardSeconds,
                onPrevChapter = { viewModel.skipToPreviousChapter() },
                onSkipBack = { viewModel.skipBack() },
                onTogglePlay = { viewModel.togglePlay() },
                onSkipForward = { viewModel.skipForward() },
                onNextChapter = { viewModel.skipToNextChapter() },
            )

            Spacer(modifier = Modifier.height(26.dp))

            // Secondary controls: speed / skip / sleep / chapters.
            var showSpeedSheet by remember { mutableStateOf(false) }
            var showSleepSheet by remember { mutableStateOf(false) }
            var showSkipSheet by remember { mutableStateOf(false) }
            var showChaptersSheet by remember { mutableStateOf(false) }
            AudiobookSecondaryBar(
                speedLabel = "${formatSpeed(state.playbackSpeed)}x",
                sleepLabel = state.sleepTimerMinutesLeft?.let { "$it min" } ?: "Sleep",
                skipLabel = "${state.skipBackSeconds}s",
                onSpeedClick = { showSpeedSheet = true },
                onSleepClick = { showSleepSheet = true },
                onSkipClick = { showSkipSheet = true },
                onChaptersClick = { showChaptersSheet = true },
                showChapters = state.chapters.isNotEmpty(),
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
                    onDismiss = { showSkipSheet = false },
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
                )
            }

            // About / description — shown in the player itself so it's available
            // offline too, where there's no detail screen to read it from.
            state.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                Spacer(modifier = Modifier.height(28.dp))
                Text(
                    text = "About",
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
                    Text("Loading audiobook…")
                }
            }
            state.error?.let { err ->
                Spacer(modifier = Modifier.height(16.dp))
                Text(text = err, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

private fun audiobookPlayerBackgroundBrush(tint: Color, background: Color): Brush =
    Brush.verticalGradient(
        0.00f to tint.copy(alpha = 0.28f),
        0.34f to background.copy(alpha = 0.96f),
        1.00f to background,
    )
