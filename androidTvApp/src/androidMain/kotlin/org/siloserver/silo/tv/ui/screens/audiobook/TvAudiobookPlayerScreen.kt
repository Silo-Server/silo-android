@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.siloserver.silo.tv.ui.screens.audiobook

import android.content.ComponentName
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import org.siloserver.silo.common.player.AudiobookPlayerViewModel
import org.siloserver.silo.common.player.SiloPlaybackService
import org.siloserver.silo.model.catalog.VersionChapter
import org.siloserver.silo.tv.ui.components.TvErrorScreen
import org.siloserver.silo.tv.ui.components.TvPoster
import org.siloserver.silo.tv.ui.focus.TvFocusAcquisitionBudgetMillis
import org.siloserver.silo.tv.ui.focus.TvObservedFocusResult
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.tv.ui.focus.tvFocusSuppressed
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay
import org.koin.compose.viewmodel.koinViewModel
import kotlin.math.max

private enum class AudiobookPanel { None, Chapters, Speed, Sleep, More, Skip, Bookmarks, About }

private const val TvAudiobookTransportFocusRetryDelayMillis = 60L

private val TvAudiobookTransportFocusMaxAttempts =
    (TvFocusAcquisitionBudgetMillis / TvAudiobookTransportFocusRetryDelayMillis).toInt()

/**
 * 10-foot, D-pad audiobook player for Android TV. A thin focus/layout view over
 * the SHARED [AudiobookPlayerViewModel] (android-shared) — no chapter / sleep /
 * speed / playback logic is duplicated here. Binds a Media3 [MediaController] to
 * the shared [SiloPlaybackService] exactly like the phone screen and the
 * video [org.siloserver.silo.tv.ui.screens.player.TvPlayerScreen].
 */
@Composable
fun TvAudiobookPlayerScreen(
    onExit: () -> Unit,
    viewModel: AudiobookPlayerViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val currentChapterIndex by viewModel.currentChapterIndex.collectAsState()
    val chapterCountLabel by viewModel.chapterCountLabel.collectAsState()
    val sleepChoice by viewModel.sleepTimerChoice.collectAsState()
    val context = LocalContext.current

    var controller by remember { mutableStateOf<MediaController?>(null) }
    var activePanel by remember { mutableStateOf(AudiobookPanel.None) }
    var exitRequested by remember { mutableStateOf(false) }
    val playPauseFocus = remember { FocusRequester() }
    val speedChipFocus = remember { FocusRequester() }

    // MediaController binds async to the shared service — the same one the video
    // player uses, so a single MediaSession owns foreground audio.
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

    // Wire the stream URL into Media3 once the controller + URL resolve; rebuild
    // on URL change. Resume-on-open seeks to the saved position before play.
    var preparedUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(controller, state.streamUrl) {
        if (exitRequested) return@LaunchedEffect
        val c = controller ?: return@LaunchedEffect
        val url = state.streamUrl ?: return@LaunchedEffect
        // Prepare each distinct stream once; a re-run with the same url (e.g.
        // controller rebind) must not reset the resume position to 0:00.
        if (url == preparedUrl) return@LaunchedEffect
        val mediaItem = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(state.title)
                    .setArtist(state.author ?: state.narrator)
                    .also { mb ->
                        state.coverUrl?.takeIf { it.isNotBlank() }?.let { mb.setArtworkUri(Uri.parse(it)) }
                    }
                    .build(),
            )
            .build()
        // Resume position goes on the media item's start position, not a
        // post-prepare seekTo (which is dropped before the timeline window is
        // known, leaving the book at 0:00).
        val resume = viewModel.resumePositionSeconds.value
        val startMs = ((resume ?: 0.0).coerceAtLeast(0.0) * 1000).toLong()
        c.setMediaItem(mediaItem, startMs)
        c.prepare()
        if (resume != null && resume > 0) viewModel.consumeResumePosition()
        c.playWhenReady = true
        preparedUrl = url
    }

    LaunchedEffect(controller, state.isPaused) {
        if (state.isPaused) viewModel.flushPosition()
    }
    DisposableEffect(Unit) {
        onDispose {
            viewModel.flushPosition()
            viewModel.stopPlaybackSession()
        }
    }

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

    // 4 Hz position poll (MediaController doesn't push position updates). The
    // current stream URI rides along so the VM can tie cross-part settle to
    // stream identity, not just engine time.
    LaunchedEffect(controller) {
        while (true) {
            controller?.let {
                viewModel.onPositionChanged(
                    it.currentPosition / 1000.0,
                    it.currentMediaItem?.localConfiguration?.uri?.toString(),
                )
            }
            delay(250)
        }
    }
    LaunchedEffect(controller, state.isPaused) { controller?.playWhenReady = !state.isPaused }
    LaunchedEffect(controller, state.playbackSpeed) {
        controller?.playbackParameters = PlaybackParameters(state.playbackSpeed)
    }
    val pendingSeek by viewModel.pendingSeekToSeconds.collectAsState()
    LaunchedEffect(controller, pendingSeek) {
        val seconds = pendingSeek ?: return@LaunchedEffect
        controller?.seekTo((seconds * 1000).toLong())
        viewModel.consumePendingSeek()
    }

    // Initial / restored focus lands on play/pause when no panel is open.
    //
    // Observed, because both moments this covers request focus at a target that
    // may not be focusable yet: on first load the transport is composed in the
    // same pass, and on panel close the panel is still being torn down. A
    // one-shot request that lands early is silently dropped, and with the
    // covered controls suppressed while a panel is open there is nothing else
    // holding focus to fall back on — the screen would simply go dead.
    var transportHasFocus by remember { mutableStateOf(false) }
    // Set when an open panel reports that it could not take focus. Suppressing
    // the player behind a panel that then holds no focus is worse than the
    // escape this whole change is about, so that case hands the player back.
    var panelFocusFailed by remember(activePanel) { mutableStateOf(false) }
    LaunchedEffect(state.isLoading, state.error != null, activePanel, panelFocusFailed) {
        // The error branch replaces the transport entirely, so there is nothing
        // to acquire and retrying just burns the budget. Keyed, not just
        // guarded, so clearing an error re-runs acquisition.
        if (state.isLoading || state.error != null) return@LaunchedEffect
        // A panel that could not take focus has to CLOSE, not merely hand the
        // player back. Unsuppressing the transport while the panel and its
        // scrim are still drawn puts focus on controls the viewer cannot see —
        // D-pad and Select land behind the modal — and the escalation below
        // never rescues that, because it only fires when the transport also
        // fails to take focus. Closing first re-keys this effect against an
        // unobstructed player, which then acquires normally.
        if (activePanel != AudiobookPanel.None && panelFocusFailed) {
            activePanel = AudiobookPanel.None
            return@LaunchedEffect
        }
        if (activePanel != AudiobookPanel.None) return@LaunchedEffect
        if (transportHasFocus) return@LaunchedEffect
        val result = requestFocusUntilObserved(
            maxAttempts = TvAudiobookTransportFocusMaxAttempts,
            awaitAttempt = { delay(TvAudiobookTransportFocusRetryDelayMillis) },
            requestFocus = playPauseFocus::requestFocus,
            isFocused = { transportHasFocus },
        )
        // Last rung of the ladder. Handing the player back is only useful if
        // the player can actually take focus, and by here it has not: the panel
        // reported failure and the transport did not answer either. (Strictly
        // that says these two places lack focus, not that nothing anywhere has
        // it — but a panel that never took focus with the player suppressed
        // behind it leaves nothing else plausible.) Another request is not a
        // new idea at that point. The one remaining move that changes the tree
        // rather than re-asking the same question is dropping the overlay: it
        // removes the panel's containment and the scrim, and re-keys this
        // effect against an unobstructed player. That is also the terminating
        // step — with no panel open the branch cannot fire again, so this
        // escalates at most once.
        if (result != TvObservedFocusResult.Focused &&
            !transportHasFocus &&
            activePanel != AudiobookPanel.None
        ) {
            activePanel = AudiobookPanel.None
        }
    }

    BackHandler(enabled = true) {
        if (activePanel != AudiobookPanel.None) {
            activePanel = AudiobookPanel.None
        } else {
            exitRequested = true
            viewModel.flushPosition()
            viewModel.stopPlaybackSession()
            onExit()
        }
    }

    // A runtime failure can set `error` after playback is already up. That swaps
    // the whole content branch for TvErrorScreen, which has no actions in it —
    // so an open panel would be left floating over a screen with nothing to
    // return focus to once it closes. Close the panel with the content it
    // belonged to.
    LaunchedEffect(state.error != null) {
        if (state.error != null) activePanel = AudiobookPanel.None
    }

    // The panels are in-window overlays, so the player stays composed and
    // focusable behind them. Containment inside a panel cannot stop Select
    // reaching a covered transport button when focus never entered the panel,
    // and it cannot stop a panel row's Down walking into the pills below the
    // scrim — the covered controls have to leave the focus graph outright.
    val panelIsOpen = activePanel != AudiobookPanel.None
    val playerFocusSuppressed = panelIsOpen && !panelFocusFailed

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val metrics = tvAudiobookPlayerMetrics(maxWidth.value.toInt(), maxHeight.value.toInt())

        Box(modifier = Modifier.fillMaxSize()) {
            TvAudiobookPlayerBackdrop()
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
                state.error != null -> TvErrorScreen(message = state.error!!)
                else -> {
                    val hasChapters = state.chapters.size > 1
                    val currentChapterLabel = tvAudiobookCurrentChapterLabel(
                        chapters = state.chapters,
                        currentChapterIndex = currentChapterIndex,
                        fallback = chapterCountLabel,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                start = metrics.horizontalPaddingDp.dp,
                                top = metrics.verticalPaddingDp.dp,
                                end = metrics.horizontalPaddingDp.dp,
                                bottom = metrics.verticalPaddingDp.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TvPoster(
                            imageUrl = state.coverUrl,
                            contentDescription = state.title,
                            modifier = Modifier
                                .size(metrics.coverSizeDp.dp)
                                .shadow(metrics.coverShadowDp.dp, RoundedCornerShape(metrics.coverCornerRadiusDp.dp)),
                            cornerRadius = metrics.coverCornerRadiusDp.dp,
                        )
                        Spacer(Modifier.width(metrics.coverGapDp.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxHeight()
                                .weight(1f),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = "AUDIOBOOK",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White.copy(alpha = 0.58f),
                                letterSpacing = 2.sp,
                            )
                            Spacer(Modifier.height(metrics.eyebrowGapDp.dp))
                            Text(
                                state.title,
                                style = if (metrics.compact) {
                                    MaterialTheme.typography.headlineMedium
                                } else {
                                    MaterialTheme.typography.headlineLarge
                                },
                                color = Color.White,
                                maxLines = 2,
                            )
                            (state.author ?: state.narrator)?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    it,
                                    style = if (metrics.compact) {
                                        MaterialTheme.typography.titleLarge
                                    } else {
                                        MaterialTheme.typography.headlineSmall
                                    },
                                    color = Color.White.copy(alpha = 0.62f),
                                    maxLines = 1,
                                )
                            }
                            if (currentChapterLabel.isNotBlank()) {
                                Spacer(Modifier.height(metrics.chapterGapDp.dp))
                                Text(
                                    text = currentChapterLabel,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TvAudiobookAccent,
                                    maxLines = 1,
                                )
                            }
                            Spacer(Modifier.height(metrics.progressTopGapDp.dp))
                            TvAudiobookProgressBar(
                                fraction = (state.positionSeconds / state.durationSeconds.coerceAtLeast(1.0)).toFloat(),
                                positionLabel = formatAudiobookTime(state.positionSeconds),
                                remainingLabel = "-" + formatAudiobookTime(
                                    max(0.0, state.durationSeconds - state.positionSeconds),
                                ),
                                chapterMarkers = state.chapters,
                                durationSeconds = state.durationSeconds,
                            )
                            Spacer(Modifier.height(metrics.transportTopGapDp.dp))
                            TvAudiobookTransportRow(
                                modifier = Modifier
                                    .focusProperties { down = speedChipFocus }
                                    .onFocusChanged { transportHasFocus = it.hasFocus },
                                focusSuppressed = playerFocusSuppressed,
                                // Pause intent, not transient isPlaying, so the icon
                                // stays stable through a seek's rebuffer.
                                isPlaying = !state.isPaused,
                                chaptersEnabled = hasChapters,
                                skipBackSeconds = state.skipBackSeconds,
                                skipForwardSeconds = state.skipForwardSeconds,
                                onPrevChapter = { viewModel.skipToPreviousChapter() },
                                onSkipBack = { viewModel.skipBack() },
                                onPlayPause = { viewModel.togglePlay() },
                                onSkipForward = { viewModel.skipForward() },
                                onNextChapter = { viewModel.skipToNextChapter() },
                                playPauseFocus = playPauseFocus,
                                buttonSize = metrics.transportButtonSizeDp.dp,
                                primaryButtonWidth = metrics.transportPrimaryButtonWidthDp.dp,
                                primaryButtonHeight = metrics.transportPrimaryButtonHeightDp.dp,
                                buttonSpacing = metrics.transportButtonSpacingDp.dp,
                            )
                            Spacer(Modifier.height(metrics.utilityTopGapDp.dp))
                            TvAudiobookSecondaryControls(
                                modifier = Modifier.focusProperties { up = playPauseFocus },
                                focusSuppressed = playerFocusSuppressed,
                                speedLabel = tvAudiobookSpeedLabel(state.playbackSpeed),
                                sleepLabel = tvAudiobookSleepLabel(
                                    minutesLeft = state.sleepTimerMinutesLeft,
                                ),
                                showChapters = hasChapters,
                                focusRequester = speedChipFocus,
                                buttonHeight = metrics.utilityButtonHeightDp.dp,
                                buttonSpacing = metrics.utilityButtonSpacingDp.dp,
                                onSpeed = { activePanel = AudiobookPanel.Speed },
                                onSleep = { activePanel = AudiobookPanel.Sleep },
                                onChapters = { activePanel = AudiobookPanel.Chapters },
                                onMore = { activePanel = AudiobookPanel.More },
                                onStop = {
                                    exitRequested = true
                                    viewModel.flushPosition()
                                    viewModel.stopPlaybackSession()
                                    onExit()
                                },
                            )
                        }
                    }
                }
            }

            when (activePanel) {
                AudiobookPanel.Chapters -> TvAudiobookChaptersPanel(
                    onFocusAcquisitionFailed = { panelFocusFailed = true },
                    chapters = state.chapters,
                    currentChapterIndex = currentChapterIndex,
                    onSelectChapter = { idx ->
                        state.chapters.getOrNull(idx)?.let { viewModel.jumpToChapter(it) }
                        activePanel = AudiobookPanel.None
                    },
                )
                AudiobookPanel.Speed -> TvAudiobookSpeedPanel(
                    onFocusAcquisitionFailed = { panelFocusFailed = true },
                    currentSpeed = state.playbackSpeed,
                    // Fine-adjust / presets apply live and stay open so the user
                    // can keep tuning; "Set as default" persists and closes.
                    onSelectSpeed = { viewModel.setSpeed(it) },
                    onSetDefault = { viewModel.setDefaultSpeed(it); activePanel = AudiobookPanel.None },
                )
                AudiobookPanel.Skip -> TvAudiobookSkipIntervalPanel(
                    onFocusAcquisitionFailed = { panelFocusFailed = true },
                    skipBackSeconds = state.skipBackSeconds,
                    skipForwardSeconds = state.skipForwardSeconds,
                    onSelectSkipBack = { viewModel.setSkipBackSeconds(it) },
                    onSelectSkipForward = { viewModel.setSkipForwardSeconds(it) },
                )
                AudiobookPanel.Sleep -> TvAudiobookSleepPanel(
                    onFocusAcquisitionFailed = { panelFocusFailed = true },
                    currentChoice = sleepChoice,
                    onSelectSleep = { viewModel.applySleepTimer(it); activePanel = AudiobookPanel.None },
                )
                AudiobookPanel.More -> TvAudiobookMorePanel(
                    onFocusAcquisitionFailed = { panelFocusFailed = true },
                    skipLabel = tvAudiobookSkipLabel(
                        skipBackSeconds = state.skipBackSeconds,
                        skipForwardSeconds = state.skipForwardSeconds,
                    ),
                    showAbout = !state.overview.isNullOrBlank(),
                    onSkip = { activePanel = AudiobookPanel.Skip },
                    onBookmarks = { activePanel = AudiobookPanel.Bookmarks },
                    onAbout = { activePanel = AudiobookPanel.About },
                )
                AudiobookPanel.Bookmarks -> {
                    val bookmarks by viewModel.bookmarks.collectAsState()
                    TvAudiobookBookmarksPanel(
                        onFocusAcquisitionFailed = { panelFocusFailed = true },
                        bookmarks = bookmarks,
                        onAddCurrent = { viewModel.addBookmark() },
                        onJumpTo = { bookmark ->
                            viewModel.jumpToBookmark(bookmark)
                            activePanel = AudiobookPanel.None
                        },
                        onDelete = { viewModel.removeBookmark(it.id) },
                    )
                }
                AudiobookPanel.About -> TvAudiobookAboutPanel(
                    onFocusAcquisitionFailed = { panelFocusFailed = true },
                    overview = state.overview.orEmpty(),
                    // Same destination as Back from any panel, so the two ways
                    // out of About do not disagree.
                    onClose = { activePanel = AudiobookPanel.None },
                )
                AudiobookPanel.None -> Unit
            }
        }
    }
}

internal data class TvAudiobookPlayerMetrics(
    val compact: Boolean,
    val horizontalPaddingDp: Int,
    val verticalPaddingDp: Int,
    val coverSizeDp: Int,
    val coverGapDp: Int,
    val coverCornerRadiusDp: Int,
    val coverShadowDp: Int,
    val eyebrowGapDp: Int,
    val chapterGapDp: Int,
    val progressTopGapDp: Int,
    val transportTopGapDp: Int,
    val utilityTopGapDp: Int,
    val utilityButtonHeightDp: Int,
    val utilityButtonSpacingDp: Int,
    val transportButtonSizeDp: Int,
    val transportPrimaryButtonWidthDp: Int,
    val transportPrimaryButtonHeightDp: Int,
    val transportButtonSpacingDp: Int,
) {
    val transportRowWidthDp: Int =
        (transportButtonSizeDp * 4) + transportPrimaryButtonWidthDp + (transportButtonSpacingDp * 4)
}

internal fun tvAudiobookPlayerMetrics(
    maxWidthDp: Int,
    maxHeightDp: Int,
): TvAudiobookPlayerMetrics {
    val compact = maxWidthDp <= 1100 || maxHeightDp <= 640
    return if (compact) {
        TvAudiobookPlayerMetrics(
            compact = true,
            coverSizeDp = 218,
            horizontalPaddingDp = 44,
            verticalPaddingDp = 38,
            coverGapDp = 36,
            coverCornerRadiusDp = 20,
            coverShadowDp = 22,
            eyebrowGapDp = 5,
            chapterGapDp = 10,
            progressTopGapDp = 12,
            transportTopGapDp = 12,
            utilityTopGapDp = 12,
            utilityButtonHeightDp = 42,
            utilityButtonSpacingDp = 10,
            transportButtonSizeDp = 68,
            transportPrimaryButtonWidthDp = 112,
            transportPrimaryButtonHeightDp = 58,
            transportButtonSpacingDp = 16,
        )
    } else {
        TvAudiobookPlayerMetrics(
            compact = false,
            coverSizeDp = 312,
            horizontalPaddingDp = 80,
            verticalPaddingDp = 96,
            coverGapDp = 46,
            coverCornerRadiusDp = 24,
            coverShadowDp = 34,
            eyebrowGapDp = 10,
            chapterGapDp = 18,
            progressTopGapDp = 26,
            transportTopGapDp = 26,
            utilityTopGapDp = 24,
            utilityButtonHeightDp = 58,
            utilityButtonSpacingDp = 16,
            transportButtonSizeDp = 94,
            transportPrimaryButtonWidthDp = 136,
            transportPrimaryButtonHeightDp = 72,
            transportButtonSpacingDp = 24,
        )
    }
}

private val TvAudiobookAccent = Color(0xFF23A8F2)
private val TvAudiobookPanel = Color(0xFF173137)

@Composable
private fun TvAudiobookPlayerBackdrop() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF123B42),
                        Color(0xFF071A1F),
                        Color(0xFF020708),
                    ),
                ),
            )
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.06f),
                        Color.Black.copy(alpha = 0.52f),
                    ),
                ),
            ),
    )
}

@Composable
private fun TvAudiobookProgressBar(
    fraction: Float,
    positionLabel: String,
    remainingLabel: String,
    chapterMarkers: List<VersionChapter>,
    durationSeconds: Double,
) {
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Color.White.copy(alpha = 0.23f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(5.dp))
                    .background(TvAudiobookAccent),
            )
            TvAudiobookChapterTicks(
                chapters = chapterMarkers,
                durationSeconds = durationSeconds,
                modifier = Modifier.matchParentSize(),
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(positionLabel, style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.62f))
            Text(remainingLabel, style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.62f))
        }
    }
}

@Composable
private fun TvAudiobookChapterTicks(
    chapters: List<VersionChapter>,
    durationSeconds: Double,
    modifier: Modifier = Modifier,
) {
    if (durationSeconds <= 0.0 || chapters.size <= 1) return

    Canvas(modifier = modifier) {
        chapters.drop(1).forEach { chapter ->
            val x = (size.width * (chapter.startSeconds / durationSeconds).coerceIn(0.0, 1.0)).toFloat()
            drawLine(
                color = Color.Black.copy(alpha = 0.45f),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 2f,
                cap = StrokeCap.Round,
            )
        }
    }
}

@Composable
private fun TvAudiobookSecondaryControls(
    speedLabel: String,
    sleepLabel: String,
    showChapters: Boolean,
    onSpeed: () -> Unit,
    onSleep: () -> Unit,
    onChapters: () -> Unit,
    onMore: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    focusSuppressed: Boolean = false,
    buttonHeight: Dp = 58.dp,
    buttonSpacing: Dp = 16.dp,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(buttonSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvAudiobookPillButton(
            label = speedLabel,
            icon = Icons.Filled.Speed,
            focusRequester = focusRequester,
            focusSuppressed = focusSuppressed,
            height = buttonHeight,
            onClick = onSpeed,
        )
        TvAudiobookPillButton(
            label = sleepLabel,
            icon = Icons.Filled.Bedtime,
            focusSuppressed = focusSuppressed,
            height = buttonHeight,
            onClick = onSleep,
        )
        if (showChapters) {
            TvAudiobookPillButton(
                label = "Chapters",
                icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                focusSuppressed = focusSuppressed,
                height = buttonHeight,
                onClick = onChapters,
            )
        }
        TvAudiobookPillButton(
            label = "More",
            icon = Icons.Filled.MoreHoriz,
            focusSuppressed = focusSuppressed,
            height = buttonHeight,
            onClick = onMore,
        )
        TvAudiobookPillButton(
            label = "Stop",
            icon = Icons.Filled.Close,
            focusSuppressed = focusSuppressed,
            height = buttonHeight,
            onClick = onStop,
        )
    }
}

/**
 * The book description. Its only action is leaving, but it still needs one:
 * with the covered player suppressed, a panel holding no focusable at all
 * leaves the D-pad dead until Back, and nothing on screen says so.
 */
@Composable
private fun TvAudiobookAboutPanel(
    overview: String,
    onClose: () -> Unit,
    onFocusAcquisitionFailed: () -> Unit,
) {
    val closeFocus = remember { FocusRequester() }
    TvAudiobookOverlayScaffold(
        title = "About",
        initialFocus = closeFocus,
        onAcquisitionFailed = onFocusAcquisitionFailed,
    ) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = overview,
            style = MaterialTheme.typography.bodyLarge,
            color = Color.White.copy(alpha = 0.82f),
            // Generous cap: a description fits the full-height panel; TV
            // can't D-pad-scroll an inner text box.
            maxLines = 30,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(16.dp))
        TvAudiobookOverlayRow(
            label = "Close",
            isCurrent = false,
            onSelect = onClose,
            focusRequester = closeFocus,
        )
    }
}

@Composable
private fun TvAudiobookMorePanel(
    skipLabel: String,
    showAbout: Boolean,
    onSkip: () -> Unit,
    onBookmarks: () -> Unit,
    onAbout: () -> Unit,
    onFocusAcquisitionFailed: () -> Unit,
) {
    val firstRowFocus = remember { FocusRequester() }
    TvAudiobookOverlayScaffold(
        title = "More",
        initialFocus = firstRowFocus,
        onAcquisitionFailed = onFocusAcquisitionFailed,
    ) {
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TvAudiobookMoreRow(
                icon = Icons.Filled.Tune,
                title = "Skip interval",
                subtitle = skipLabel,
                focusRequester = firstRowFocus,
                onClick = onSkip,
            )
            TvAudiobookMoreRow(
                icon = Icons.Filled.Bookmark,
                title = "Bookmarks",
                subtitle = "Add or jump to saved moments",
                onClick = onBookmarks,
            )
            if (showAbout) {
                TvAudiobookMoreRow(
                    icon = Icons.Filled.Info,
                    title = "About",
                    subtitle = "Book description",
                    onClick = onAbout,
                )
            }
        }
    }
}

@Composable
private fun TvAudiobookMoreRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val bg = if (isFocused) Color.White else Color.White.copy(alpha = 0.08f)
    val fg = if (isFocused) Color.Black else Color.White
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .let { mod -> if (focusRequester != null) mod.focusRequester(focusRequester) else mod }
            .focusable(interactionSource = interactionSource)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        onClick()
                        true
                    }
                    else -> false
                }
            }
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = fg, modifier = Modifier.size(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = fg)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = fg.copy(alpha = 0.68f), maxLines = 1)
        }
    }
}

@Composable
private fun TvAudiobookPillButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    focusSuppressed: Boolean = false,
    height: Dp = 58.dp,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val bg = if (isFocused) Color(0xFFE8F5F7) else TvAudiobookPanel.copy(alpha = 0.92f)
    val fg = if (isFocused) Color.Black else TvAudiobookAccent
    Row(
        modifier = modifier
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(bg)
            .let { mod -> if (focusRequester != null) mod.focusRequester(focusRequester) else mod }
            // Ahead of the focusable, so it binds to this pill's own focus target.
            .tvFocusSuppressed(focusSuppressed)
            .focusable(interactionSource = interactionSource)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { onClick(); true }
                    else -> false
                }
            }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

private fun tvAudiobookCurrentChapterLabel(
    chapters: List<VersionChapter>,
    currentChapterIndex: Int,
    fallback: String,
): String {
    val chapter = chapters.getOrNull(currentChapterIndex)
    val title = chapter?.title?.trim().orEmpty()
    if (title.isNotBlank()) return title
    if (chapter != null) return "Chapter ${chapter.index + 1}"
    return fallback.substringBefore(" of ").takeIf { it.isNotBlank() }.orEmpty()
}

private fun tvAudiobookSpeedLabel(speed: Float): String {
    val rounded = (speed * 100).toInt()
    return if (rounded % 100 == 0) {
        "${rounded / 100}x"
    } else {
        "${"%.2f".format(speed).trimEnd('0').trimEnd('.')}x"
    }
}

private fun tvAudiobookSleepLabel(
    minutesLeft: Int?,
): String = if (minutesLeft != null) "${minutesLeft}m" else "Sleep"

private fun tvAudiobookSkipLabel(
    skipBackSeconds: Int,
    skipForwardSeconds: Int,
): String {
    return if (skipBackSeconds == skipForwardSeconds) {
        "Skip ${skipBackSeconds}s"
    } else {
        "${skipBackSeconds}s/${skipForwardSeconds}s"
    }
}
