package org.siloserver.silo.android.ui.screens.player

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import org.siloserver.silo.android.ui.util.LanguageNames
import org.siloserver.silo.common.player.SessionState
import org.siloserver.silo.common.player.SleepTimerState
import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.RoomPlaybackState
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.watchtogether.RoomTransportIntent
import org.siloserver.silo.watchtogether.roomTransportAuthorized

/**
 * Full-screen overlay composable that layers gesture handling, transport controls,
 * and contextual buttons (skip intro, next episode) on top of the video surface.
 *
 * Also manages bottom sheet display for subtitle, audio, and quality selection.
 */
@Composable
fun PlayerOverlay(
    state: PlayerViewModel.PlayerUiState,
    viewModel: PlayerViewModel,
    roomSnapshot: RoomSnapshot? = null,
    isFastForwardHoldActive: Boolean = false,
    orientationLockSupported: Boolean = true,
    alwaysShowControls: Boolean = false,
    tabletopMode: Boolean = false,
    tabletopPaneHeight: Dp? = null,
    brightnessFraction: Float,
    onSetBrightness: (Float) -> Unit,
    showBufferingIndicator: Boolean = true,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Double) -> Unit,
    onToggleControls: () -> Unit,
    onFastForwardHold: (Boolean) -> Unit = {},
    onSelectSubtitle: (Int) -> Unit,
    onSelectAudio: (Int) -> Unit,
    onSelectVersion: (Int) -> Unit,
    // Google Cast (Chromecast) button rendered in the transport top bar.
    castSlot: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Sheet visibility — one bool per sheet. iOS uses a sealed `activeSheet`
    // enum, but Compose Material 3 needs each ModalBottomSheet to own its
    // `rememberModalBottomSheetState`, so per-sheet bools are the natural
    // fit (and the sheets can't be nested anyway).
    var tracksSheetVisible by remember { mutableStateOf(false) }
    var showQualitySelector by remember { mutableStateOf(false) }
    var settingsSheetVisible by remember { mutableStateOf(false) }
    var subtitleStyleVisible by remember { mutableStateOf(false) }
    var sleepTimerVisible by remember { mutableStateOf(false) }
    var chaptersSheetVisible by remember { mutableStateOf(false) }
    var statsSheetVisible by remember { mutableStateOf(false) }
    var subtitleSearchVisible by remember { mutableStateOf(false) }
    var aiTranslateVisible by remember { mutableStateOf(false) }
    // Host close-room confirm dialog (Watch Together): the host backing out of
    // the player tears the room down for everyone, so confirm first.
    var showCloseConfirm by remember { mutableStateOf(false) }

    // Watch Together transport gating. Seek is host-only (the server rejects
    // guest seeks regardless of policy), so the scrubber / skip affordance is
    // disabled for ALL guests — even one under guest_play_pause, who keeps the
    // play/pause affordance. Solo playback (no room) enables both.
    val inRoom = roomSnapshot != null
    val seekEnabled = !inRoom || roomTransportAuthorized(roomSnapshot, RoomTransportIntent.Seek)
    val playPauseEnabled = !inRoom || roomTransportAuthorized(roomSnapshot, RoomTransportIntent.PlayPause)
    val isRoomHost = roomSnapshot?.selfRole == MemberRole.Host

    // Back intercept: a host in a room confirms the room close; everyone else
    // (guest, or solo playback) backs out immediately.
    val handleBack: () -> Unit = {
        if (inRoom && isRoomHost) showCloseConfirm = true else onBack()
    }
    val gatedSeek: (Double) -> Unit = { pos -> if (seekEnabled) onSeek(pos) }
    val gatedSkipForward: () -> Unit = {
        if (seekEnabled) {
            if (inRoom) {
                val forward = state.position + 10.0
                gatedSeek(if (state.duration > 0.0) forward.coerceAtMost(state.duration) else forward)
            } else {
                viewModel.onSkipBy(10.0)
            }
        }
    }
    val gatedSkipBackward: () -> Unit = {
        if (seekEnabled) {
            if (inRoom) gatedSeek((state.position - 10.0).coerceAtLeast(0.0))
            else viewModel.onSkipBy(-10.0)
        }
    }
    val gatedPlayPause: () -> Unit = { if (playPauseEnabled) onPlayPause() }
    val gatedFastForwardHold: (Boolean) -> Unit = if (!inRoom) onFastForwardHold else { _: Boolean -> }

    // Orientation lock — toggled from the top-bar lock icon (iOS parity).
    // Persisted via the orientation-mode setting (default landscape-locked,
    // like iOS's PlayerOrientationCoordinator); PlayerScreen applies the
    // matching requestedOrientation whenever the setting changes. Android 16
    // large screens keep the preference but disable this no-op affordance.
    val isOrientationLocked by viewModel.orientationLocked.collectAsState()
    val context = LocalContext.current

    val introSkipState by viewModel.introSkipState.collectAsState()
    val sleepTimerState by viewModel.sleepTimerState.collectAsState()
    val sleepTimerDefault by viewModel.sleepTimerDefaultMinutes.collectAsState()
    val videoGravity by viewModel.videoGravity.collectAsState()
    val playbackSpeed by viewModel.playbackSpeed.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val sessionState by viewModel.sessionState.collectAsState()
    val subtitleTools by viewModel.subtitleTools.collectAsState()
    // Pinch-to-scale (iOS parity): pinch-out steps Fit -> Fill -> Stretch,
    // pinch-in steps back, clamped at both ends. No-op steps (already at an
    // end) skip the toast so a clamped pinch stays quiet.
    val stepVideoGravity: (Boolean) -> Unit = { expand ->
        val nextGravity = if (expand) {
            nextMobileVideoGravity(videoGravity)
        } else {
            previousMobileVideoGravity(videoGravity)
        }
        if (nextGravity != videoGravity) {
            viewModel.onSetVideoGravity(nextGravity)
            Toast.makeText(context, mobileVideoGravityLabel(nextGravity), Toast.LENGTH_SHORT).show()
        }
    }
    // Remote "display_message" from the control socket — show transiently.
    val remoteMessage by viewModel.remoteMessage.collectAsState()
    LaunchedEffect(remoteMessage?.id) {
        if (remoteMessage != null) {
            kotlinx.coroutines.delay(5_000)
            viewModel.clearRemoteMessage()
        }
    }

    // Lazy one-shot AI status probe on first TracksSheet open (web parity).
    LaunchedEffect(tracksSheetVisible) {
        if (tracksSheetVisible) viewModel.onTracksSheetOpened()
    }

    // Subtitle tooling needs a live server session (media_file_id + session
    // stream URLs); hidden for offline/local playback.
    val subtitleToolsAvailable = state.sessionId != null && state.mediaFileId != null

    Box(modifier = modifier.fillMaxSize()) {
        // Gesture layer stays out of the tree while controls are visible so
        // full-screen pointer handlers cannot consume taps meant for buttons.
        if (!alwaysShowControls && !state.showControls && !state.showUpNext) {
            PlayerGestureHandler(
                onToggleControls = onToggleControls,
                onSkipForward = gatedSkipForward,
                onSkipBackward = gatedSkipBackward,
                seekEnabled = seekEnabled,
                onFastForwardHold = gatedFastForwardHold,
                onPinchVideoGravity = stepVideoGravity,
                onDismiss = handleBack,
                // Only allow swipe-down-to-dismiss once playback is established —
                // during the initial load a stray volume swipe must not close the
                // player.
                dismissEnabled = state.isPlaying || state.isPaused,
                modifier = Modifier.zIndex(0f),
            )
        }

        // Buffering indicator. Shown during ExoPlayer buffering AND during outage
        // recovery — the lifecycle's Reconnecting state isn't visible to the player,
        // so we surface the spinner ourselves so the screen doesn't appear frozen.
        if (showBufferingIndicator &&
            (state.isBuffering || sessionState is SessionState.Reconnecting)
        ) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(56.dp)
                    .align(Alignment.Center),
                color = Color.White,
                strokeWidth = 3.dp,
            )
        }

        AnimatedVisibility(
            visible = isFastForwardHoldActive,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = 64.dp)
                .zIndex(3f),
        ) {
            Surface(
                color = Color.Black.copy(alpha = 0.72f),
                shape = RoundedCornerShape(999.dp),
            ) {
                Text(
                    text = "2x",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                )
            }
        }

        // Notice overlay (top-left). Driven by PlaybackSessionLifecycle.notice — surfaces
        // server-reconnecting / suspend warnings as a transient toast. Stacks above the
        // buffering spinner; fine to obscure briefly during Reconnecting (the spinner is
        // a redundant signal at that point).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = 16.dp, start = 16.dp),
            contentAlignment = Alignment.TopStart,
        ) {
            PlayerNoticeOverlay(notice = notice)
        }

        // Remote-control "display_message" toast (top-center), shown for a few
        // seconds regardless of controls visibility. zIndex above the controls
        // layer + WT badge so it's never obscured.
        remoteMessage?.let { message ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 64.dp)
                    .zIndex(10f),
                contentAlignment = Alignment.TopCenter,
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.78f),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        text = message.text,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }

        // Watch Together room indicator (top-center). Member count + host-offline
        // / waiting-barrier state, and the invite code for the host. Stays
        // visible regardless of controls visibility so members always know the
        // room status.
        if (roomSnapshot != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 16.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                Row(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(20.dp))
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Group,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    val label = when {
                        roomSnapshot.playbackState == RoomPlaybackState.Waiting -> "Waiting for members…"
                        !roomSnapshot.hostConnected -> "${roomSnapshot.memberCount} · host offline"
                        else -> "${roomSnapshot.memberCount} watching"
                    }
                    Text(text = label, color = Color.White, fontSize = 13.sp)
                    if (isRoomHost && roomSnapshot.code.isNotBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Code ${roomSnapshot.code}",
                            color = Color.White,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }

        // Transport controls (shown/hidden with animation)
        AnimatedVisibility(
            visible = (alwaysShowControls || state.showControls) && !state.showUpNext,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .fillMaxSize()
                .zIndex(1f),
        ) {
            PlayerControls(
                title = state.title,
                subtitle = state.subtitle,
                isPlaying = state.isPlaying,
                isPaused = state.isPaused,
                position = state.position,
                duration = state.duration,
                bufferedPosition = state.bufferedPosition,
                chapters = state.chapters,
                intro = state.intro,
                credits = state.credits,
                recap = state.recap,
                preview = state.preview,
                hasChapters = state.chapters.isNotEmpty(),
                hasTracks = state.subtitleTracks.isNotEmpty() || state.audioTracks.isNotEmpty(),
                hasMultipleVersions = state.versions.size > 1,
                isOrientationLocked = isOrientationLocked,
                orientationLockSupported = orientationLockSupported,
                tabletopMode = tabletopMode,
                playbackSpeed = playbackSpeed,
                nextEpisode = state.nextEpisode.takeUnless { inRoom },
                brightnessFraction = brightnessFraction,
                seekEnabled = seekEnabled,
                playPauseEnabled = playPauseEnabled,
                onBack = handleBack,
                onPlayPause = gatedPlayPause,
                onSeek = gatedSeek,
                onSkipForward = gatedSkipForward,
                onSkipBackward = gatedSkipBackward,
                onToggleOrientationLock = {
                    if (orientationLockSupported) {
                        viewModel.onSetOrientationLocked(!isOrientationLocked)
                    }
                },
                onOpenChapters = { chaptersSheetVisible = true },
                onOpenTracks = { tracksSheetVisible = true },
                onOpenQuality = { showQualitySelector = true },
                onOpenSettings = { settingsSheetVisible = true },
                onSetPlaybackSpeed = viewModel::onSetPlaybackSpeed,
                onPlayNextEpisode = viewModel::playUpNextNow,
                onSetBrightness = onSetBrightness,
                castSlot = castSlot,
            )
        }

        val bottomEndSlotModifier = Modifier
            .align(Alignment.BottomEnd)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(bottom = 120.dp, end = 24.dp)
            .zIndex(2f)

        // Intro auto-skip banner (Hidden / ShowingButton / CountingDown).
        // Shares the bottom-end slot with the Up Next card; intro and credits
        // never overlap in practice, but the card wins the slot if both could show.
        if (!state.showUpNext) {
            Box(
                modifier = bottomEndSlotModifier,
                contentAlignment = Alignment.BottomEnd,
            ) {
                IntroAutoSkipBanner(
                    state = introSkipState,
                    onSkipNow = viewModel::onSkipIntroNow,
                    onCancelCountdown = viewModel::onCancelIntroAutoSkip,
                )
            }
        }

        var retainedUpNextInfo by remember { mutableStateOf<PlayerViewModel.NextEpisodeInfo?>(null) }
        LaunchedEffect(state.showUpNext, state.nextEpisode) {
            state.nextEpisode?.let { retainedUpNextInfo = it }
            if (!state.showUpNext) {
                kotlinx.coroutines.delay(220)
                retainedUpNextInfo = null
            }
        }

        // Full-screen Next-Up screen (iOS PlayerNextUpScreen parity — replaced
        // the old compact corner card). The countdown ring's total is the
        // largest countdown value seen since the screen appeared (the pre-end
        // countdown tracks real remaining time, so there is no fixed total).
        var upNextCountdownTotal by remember { mutableStateOf(0) }
        LaunchedEffect(state.showUpNext, state.upNextCountdownSeconds) {
            if (!state.showUpNext) {
                upNextCountdownTotal = 0
            } else {
                state.upNextCountdownSeconds?.let { seconds ->
                    if (seconds > upNextCountdownTotal) upNextCountdownTotal = seconds
                }
            }
        }
        AnimatedVisibility(
            visible = state.showUpNext,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.zIndex(3f),
        ) {
            PlayerNextUpScreen(
                nextEpisode = retainedUpNextInfo,
                onDeckItems = state.onDeckItems,
                videoEnded = state.upNextVideoEnded,
                countdownSeconds = state.upNextCountdownSeconds,
                countdownTotalSeconds = upNextCountdownTotal.coerceAtLeast(1),
                autoPlayEnabled = viewModel.autoPlayNextEnabled.collectAsState().value,
                onPlayNow = viewModel::playUpNextNow,
                onKeepWatching = viewModel::dismissUpNext,
                onToggleAutoPlay = {
                    viewModel.onSetAutoPlayNext(!viewModel.autoPlayNextEnabled.value)
                },
                onPlayOnDeckItem = viewModel::playOnDeckItemNow,
                onBack = handleBack,
                compactTabletop = tabletopMode,
            )
        }

        // Sleep timer chip — top-right, fades in only while a timer is active.
        // The chip stays visible regardless of `state.showControls` so users
        // know a sleep timer is still running even when the controls have
        // auto-hidden. When the HUD is visible, move it below the 48dp toolbar
        // controls (16dp edge padding + 48dp target + 8dp gap) so it cannot
        // cover Cast or playback settings in landscape.
        val controlsVisible = (alwaysShowControls || state.showControls) && !state.showUpNext
        val sleepTimerTopPadding = if (controlsVisible) 72.dp else 16.dp
        AnimatedVisibility(
            visible = sleepTimerState is SleepTimerState.Active,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = sleepTimerTopPadding, end = 16.dp)
                .zIndex(2f),
        ) {
            val active = sleepTimerState as? SleepTimerState.Active
            if (active != null) {
                SleepTimerChip(remainingSeconds = active.remainingSeconds)
            }
        }
    }

    // Host close-room confirmation (Watch Together). Closing tears the room
    // down for everyone; the actual close + teardown happens in onBack (the
    // RoomSyncController.leave(closeRoom = true) path in PlayerScreen).
    if (showCloseConfirm) {
        AlertDialog(
            onDismissRequest = { showCloseConfirm = false },
            title = { Text("Close room for everyone?") },
            text = { Text("Leaving as host ends the Watch Together room for all members.") },
            confirmButton = {
                TextButton(onClick = {
                    showCloseConfirm = false
                    onBack()
                }) { Text("Close room") }
            },
            dismissButton = {
                TextButton(onClick = { showCloseConfirm = false }) { Text("Cancel") }
            },
        )
    }

    // Combined audio + subtitle picker — opened from the top-bar
    // captions.bubble icon (iOS parity). Replaces the previous side-popup
    // "SettingsPanel" + individual SubtitleSelector / AudioTrackSelector
    // sheets with a single sectioned ModalBottomSheet.
    TracksSheet(
        isVisible = tracksSheetVisible,
        audioTracks = state.audioTracks,
        selectedAudioIndex = state.selectedAudioIndex,
        subtitles = state.subtitleTracks,
        selectedSubtitleIndex = state.selectedSubtitleIndex,
        onSelectAudio = onSelectAudio,
        onSelectSubtitle = onSelectSubtitle,
        onDismiss = { tracksSheetVisible = false },
        showSearchAction = subtitleToolsAvailable,
        showTranslateAction = subtitleToolsAvailable &&
            subtitleTools.aiStatus?.let { it.enabled || it.transcribeEnabled } == true,
        onSearchSubtitles = {
            tracksSheetVisible = false
            subtitleSearchVisible = true
        },
        onTranslateWithAi = {
            tracksSheetVisible = false
            aiTranslateVisible = true
        },
        tabletopPaneHeight = tabletopPaneHeight,
    )

    if (subtitleSearchVisible) {
        SubtitleSearchSheet(
            tools = subtitleTools,
            defaultLanguage = LanguageNames.searchCode(state.preferredTextLanguage),
            onSearch = viewModel::searchSubtitles,
            onDownload = viewModel::downloadSubtitle,
            onDismiss = {
                subtitleSearchVisible = false
                viewModel.onSearchSheetClosed()
            },
            onBack = {
                subtitleSearchVisible = false
                tracksSheetVisible = true
                viewModel.onSearchSheetClosed()
            },
            tabletopPaneHeight = tabletopPaneHeight,
        )
    }

    if (aiTranslateVisible) {
        AiTranslateSheet(
            tools = subtitleTools,
            subtitleTracks = state.subtitleTracks,
            audioTracks = state.audioTracks,
            defaultTargetLanguage = LanguageNames.searchCode(state.preferredTextLanguage),
            onRefreshQuota = viewModel::refreshAiQuota,
            onSubmit = viewModel::startAiJob,
            onCancelJob = viewModel::cancelAiJob,
            onDismiss = {
                aiTranslateVisible = false
                viewModel.onTranslateSheetClosed()
            },
            onBack = {
                aiTranslateVisible = false
                tracksSheetVisible = true
                viewModel.onTranslateSheetClosed()
            },
            tabletopPaneHeight = tabletopPaneHeight,
        )
    }

    if (showQualitySelector) {
        QualitySelector(
            versions = state.versions,
            selectedIndex = state.selectedVersionIndex,
            onSelect = onSelectVersion,
            onDismiss = { showQualitySelector = false },
            tabletopPaneHeight = tabletopPaneHeight,
        )
    }

    // Glass-style playback settings sheet (speed / aspect / HDR / auto-skip / auto-play)
    PlayerSettingsSheet(
        isVisible = settingsSheetVisible,
        onDismiss = { settingsSheetVisible = false },
        playbackSpeed = playbackSpeed,
        onSetPlaybackSpeed = viewModel::onSetPlaybackSpeed,
        videoGravity = videoGravity,
        onSetVideoGravity = viewModel::onSetVideoGravity,
        autoSkipIntroEnabled = viewModel.autoSkipIntroEnabled.collectAsState().value,
        onSetAutoSkipIntro = viewModel::onSetAutoSkipIntro,
        autoPlayNextEnabled = viewModel.autoPlayNextEnabled.collectAsState().value,
        onSetAutoPlayNext = viewModel::onSetAutoPlayNext,
        hdrEnabled = viewModel.hdrEnabled.collectAsState().value,
        onSetHdrEnabled = viewModel::onSetHdrEnabled,
        dolbyVisionEnabled = viewModel.dolbyVisionEnabled.collectAsState().value,
        onSetDolbyVisionEnabled = viewModel::onSetDolbyVisionEnabled,
        onOpenSubtitleStyle = {
            settingsSheetVisible = false
            subtitleStyleVisible = true
        },
        onOpenSleepTimer = {
            settingsSheetVisible = false
            sleepTimerVisible = true
        },
        stats = state.stats,
        onOpenPlaybackStats = {
            settingsSheetVisible = false
            statsSheetVisible = true
        },
        audioDelayMs = viewModel.audioDelayMs.collectAsState().value,
        audioDelayEnabled = state.playbackPlan?.claims?.audio?.passthrough != true,
        onSetAudioDelay = viewModel::onSetAudioDelay,
        subtitleDelayMs = viewModel.subtitleDelayMs.collectAsState().value,
        onSetSubtitleDelay = viewModel::onSetSubtitleDelay,
        sleepTimerState = sleepTimerState,
        tabletopPaneHeight = tabletopPaneHeight,
    )

    PlaybackStatsSheet(
        isVisible = statsSheetVisible,
        stats = state.stats,
        onDismiss = { statsSheetVisible = false },
        onBack = {
            statsSheetVisible = false
            settingsSheetVisible = true
        },
        tabletopPaneHeight = tabletopPaneHeight,
    )

    // Chapters picker — opened from the HUD chapters button (HUD product
    // decision: chapters + tracks + quality; no longer reachable from the
    // gear sheet). Selecting a row seeks the player to the chapter's
    // startSeconds. The HUD button hides when the active version has no
    // embedded chapters.
    ChaptersSheet(
        isVisible = chaptersSheetVisible,
        chapters = state.chapters,
        position = state.position,
        onSelect = { idx ->
            viewModel.onSeekToChapter(idx)?.let { sec -> viewModel.onSeek(sec) }
        },
        onDismiss = { chaptersSheetVisible = false },
        tabletopPaneHeight = tabletopPaneHeight,
    )

    // Subtitle styling sheet — opened from the "Subtitle Style" row in
    // PlayerSettingsSheet. Material 3 sheets can't nest, so the parent sheet
    // dismisses itself before we open this one.
    SubtitleStyleSheet(
        isVisible = subtitleStyleVisible,
        appearance = viewModel.subtitleAppearance.collectAsState().value,
        onUpdate = viewModel::onSetSubtitleAppearance,
        onDismiss = { subtitleStyleVisible = false },
        onBack = {
            subtitleStyleVisible = false
            settingsSheetVisible = true
        },
        tabletopPaneHeight = tabletopPaneHeight,
    )

    // Sleep timer picker — opened from the "Sleep Timer" row in
    // PlayerSettingsSheet. Same nested-sheet caveat as Subtitle Style above.
    SleepTimerSheet(
        isVisible = sleepTimerVisible,
        activeState = sleepTimerState,
        defaultMinutes = sleepTimerDefault,
        onStart = viewModel::onStartSleepTimer,
        onCancel = viewModel::onCancelSleepTimer,
        onDismiss = { sleepTimerVisible = false },
        onBack = {
            sleepTimerVisible = false
            settingsSheetVisible = true
        },
        tabletopPaneHeight = tabletopPaneHeight,
    )
}

/**
 * Sleep-timer status pill anchored top-right. Shows clock icon + "23m 17s"
 * remaining countdown. Non-interactive in v1 — cancel via the bottom sheet.
 */
@Composable
private fun SleepTimerChip(remainingSeconds: Int) {
    Row(
        modifier = Modifier
            .background(
                color = Color.Black.copy(alpha = 0.55f),
                shape = RoundedCornerShape(20.dp),
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Bedtime,
            contentDescription = "Sleep timer active",
            tint = Color.White,
            modifier = Modifier.size(14.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = formatRemaining(remainingSeconds),
            color = Color.White,
            fontSize = 13.sp,
        )
    }
}

// Directional gravity steps, clamped at both ends (iOS nextVideoGravity /
// previousVideoGravity in MobilePlayerGestureLayer — no wrap-around).
internal fun nextMobileVideoGravity(current: String): String = when (current) {
    "fit" -> "fill"
    "fill" -> "stretch"
    "stretch" -> "stretch"
    else -> "fill"
}

internal fun previousMobileVideoGravity(current: String): String = when (current) {
    "stretch" -> "fill"
    "fill" -> "fit"
    else -> "fit"
}

internal fun mobileVideoGravityLabel(value: String): String = when (value) {
    "fill" -> "Fill"
    "stretch" -> "Stretch"
    else -> "Fit"
}
