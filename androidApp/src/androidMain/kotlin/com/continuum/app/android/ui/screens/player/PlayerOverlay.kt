package com.continuum.app.android.ui.screens.player

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.continuum.app.android.ui.util.LanguageNames
import com.continuum.app.common.player.SessionState
import com.continuum.app.common.player.SleepTimerState
import com.continuum.app.model.watchtogether.MemberRole
import com.continuum.app.model.watchtogether.RoomPlaybackState
import com.continuum.app.model.watchtogether.RoomSnapshot
import com.continuum.app.watchtogether.RoomTransportIntent
import com.continuum.app.watchtogether.roomTransportAuthorized

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
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Double) -> Unit,
    onToggleControls: () -> Unit,
    onNextEpisode: () -> Unit,
    onSelectSubtitle: (Int) -> Unit,
    onSelectAudio: (Int) -> Unit,
    onSelectVersion: (Int) -> Unit,
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
    val gatedPlayPause: () -> Unit = { if (playPauseEnabled) onPlayPause() }

    // Orientation lock — toggled from the top-bar lock icon (iOS parity).
    // Default false: respect system rotation lock (PlayerScreen sets the
    // initial requestedOrientation to USER). When the user taps the lock,
    // we override to LANDSCAPE; tapping again returns control to USER.
    var isOrientationLocked by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val activity = context as? Activity

    val introSkipState by viewModel.introSkipState.collectAsState()
    val sleepTimerState by viewModel.sleepTimerState.collectAsState()
    val sleepTimerDefault by viewModel.sleepTimerDefaultMinutes.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val sessionState by viewModel.sessionState.collectAsState()
    val subtitleTools by viewModel.subtitleTools.collectAsState()
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
        if (!state.showControls) {
            PlayerGestureHandler(
                position = state.position,
                duration = state.duration,
                onToggleControls = onToggleControls,
                onSeek = gatedSeek,
                onSkipForward = { gatedSeek((state.position + 10.0).coerceAtMost(state.duration)) },
                onSkipBackward = { gatedSeek((state.position - 10.0).coerceAtLeast(0.0)) },
                modifier = Modifier.zIndex(0f),
            )
        }

        // Buffering indicator. Shown during ExoPlayer buffering AND during outage
        // recovery — the lifecycle's Reconnecting state isn't visible to the player,
        // so we surface the spinner ourselves so the screen doesn't appear frozen.
        if (state.isBuffering || sessionState is SessionState.Reconnecting) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(56.dp)
                    .align(Alignment.Center),
                color = Color.White,
                strokeWidth = 3.dp,
            )
        }

        // Notice overlay (top-left). Driven by PlaybackSessionLifecycle.notice — surfaces
        // server-reconnecting / suspend warnings as a transient toast. Stacks above the
        // buffering spinner; fine to obscure briefly during Reconnecting (the spinner is
        // a redundant signal at that point).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 16.dp, start = 16.dp),
            contentAlignment = Alignment.TopStart,
        ) {
            PlayerNoticeOverlay(notice = notice)
        }

        // F2: pass-out "Still watching?" prompt — shown instead of auto-advancing
        // once the consecutive-auto-advance streak hits the threshold.
        if (state.stillWatchingPrompt) {
            StillWatchingPrompt(
                nextEpisodeLabel = state.nextEpisodeLabel,
                onContinue = viewModel::onStillWatchingContinue,
                onStop = viewModel::onStillWatchingStop,
            )
        }

        // Remote-control "display_message" toast (top-center), shown for a few
        // seconds regardless of controls visibility. zIndex above the controls
        // layer + WT badge so it's never obscured.
        remoteMessage?.let { message ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
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
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }

        // Transport controls (shown/hidden with animation)
        AnimatedVisibility(
            visible = state.showControls,
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
                hasChapters = state.chapters.isNotEmpty(),
                hasTracks = state.subtitleTracks.isNotEmpty() || state.audioTracks.isNotEmpty(),
                isOrientationLocked = isOrientationLocked,
                seekEnabled = seekEnabled,
                playPauseEnabled = playPauseEnabled,
                onBack = handleBack,
                onPlayPause = gatedPlayPause,
                onSeek = gatedSeek,
                onSkipForward = { gatedSeek((state.position + 10.0).coerceAtMost(state.duration)) },
                onSkipBackward = { gatedSeek((state.position - 10.0).coerceAtLeast(0.0)) },
                onToggleOrientationLock = {
                    val nextLocked = !isOrientationLocked
                    isOrientationLocked = nextLocked
                    activity?.requestedOrientation = if (nextLocked) {
                        ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                    } else {
                        ActivityInfo.SCREEN_ORIENTATION_USER
                    }
                },
                onOpenChapters = { chaptersSheetVisible = true },
                onOpenTracks = { tracksSheetVisible = true },
                onOpenSettings = { settingsSheetVisible = true },
            )
        }

        // Intro auto-skip banner (Hidden / ShowingButton / CountingDown)
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 120.dp, end = 24.dp)
                .zIndex(2f),
            contentAlignment = Alignment.BottomEnd,
        ) {
            IntroAutoSkipBanner(
                state = introSkipState,
                onSkipNow = viewModel::onSkipIntroNow,
                onCancelCountdown = viewModel::onCancelIntroAutoSkip,
            )
        }

        // Next Episode overlay
        AnimatedVisibility(
            visible = state.showNextEpisode,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = 120.dp, end = 24.dp)
                .zIndex(2f),
        ) {
            Button(
                onClick = onNextEpisode,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("Next Episode")
            }
        }

        // Sleep timer chip — top-right, fades in only while a timer is active.
        // The chip stays visible regardless of `state.showControls` so users
        // know a sleep timer is still running even when the controls have
        // auto-hidden.
        AnimatedVisibility(
            visible = sleepTimerState is SleepTimerState.Active,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 16.dp, end = 16.dp)
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
        )
    }

    if (showQualitySelector) {
        QualitySelector(
            versions = state.versions,
            selectedIndex = state.selectedVersionIndex,
            onSelect = onSelectVersion,
            onDismiss = { showQualitySelector = false },
        )
    }

    // Glass-style playback settings sheet (speed / aspect / HDR / auto-skip / auto-play)
    PlayerSettingsSheet(
        isVisible = settingsSheetVisible,
        onDismiss = { settingsSheetVisible = false },
        playbackSpeed = viewModel.playbackSpeed.collectAsState().value,
        onSetPlaybackSpeed = viewModel::onSetPlaybackSpeed,
        videoGravity = viewModel.videoGravity.collectAsState().value,
        onSetVideoGravity = viewModel::onSetVideoGravity,
        autoSkipIntroEnabled = viewModel.autoSkipIntroEnabled.collectAsState().value,
        onSetAutoSkipIntro = viewModel::onSetAutoSkipIntro,
        autoPlayNextEnabled = viewModel.autoPlayNextEnabled.collectAsState().value,
        onSetAutoPlayNext = viewModel::onSetAutoPlayNext,
        hdrEnabled = viewModel.hdrEnabled.collectAsState().value,
        onSetHdrEnabled = viewModel::onSetHdrEnabled,
        onOpenSubtitleStyle = {
            settingsSheetVisible = false
            subtitleStyleVisible = true
        },
        onOpenSleepTimer = {
            settingsSheetVisible = false
            sleepTimerVisible = true
        },
        onOpenChapters = {
            settingsSheetVisible = false
            chaptersSheetVisible = true
        },
        hasChapters = state.chapters.isNotEmpty(),
        onOpenQuality = {
            settingsSheetVisible = false
            showQualitySelector = true
        },
        hasMultipleVersions = state.versions.size > 1,
        audioDelayMs = viewModel.audioDelayMs.collectAsState().value,
        onSetAudioDelay = viewModel::onSetAudioDelay,
        subtitleDelayMs = viewModel.subtitleDelayMs.collectAsState().value,
        onSetSubtitleDelay = viewModel::onSetSubtitleDelay,
        sleepTimerState = sleepTimerState,
    )

    // Chapters picker — opened from the "Chapters" row in PlayerSettingsSheet.
    // Selecting a row seeks the player to the chapter's startSeconds. Hidden
    // entirely (and the parent row hidden) when the active version has no
    // embedded chapters.
    ChaptersSheet(
        isVisible = chaptersSheetVisible,
        chapters = state.chapters,
        position = state.position,
        onSelect = { idx ->
            viewModel.onSeekToChapter(idx)?.let { sec -> viewModel.onSeek(sec) }
        },
        onDismiss = { chaptersSheetVisible = false },
    )

    // Subtitle styling sheet — opened from the "Subtitle Style" row in
    // PlayerSettingsSheet. Material 3 sheets can't nest, so the parent sheet
    // dismisses itself before we open this one.
    SubtitleStyleSheet(
        isVisible = subtitleStyleVisible,
        appearance = viewModel.subtitleAppearance.collectAsState().value,
        onUpdate = viewModel::onSetSubtitleAppearance,
        onDismiss = { subtitleStyleVisible = false },
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

@Composable
private fun StillWatchingPrompt(
    nextEpisodeLabel: String?,
    onContinue: () -> Unit,
    onStop: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .zIndex(11f),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Still watching?", style = MaterialTheme.typography.titleLarge)
                nextEpisodeLabel?.let { label ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Up next: $label",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = onStop) { Text("Stop") }
                    Button(onClick = onContinue) { Text("Continue") }
                }
            }
        }
    }
}
