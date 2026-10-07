package org.siloserver.silo.android.ui.screens.player

import org.siloserver.silo.model.catalog.editionLabel
import android.graphics.Rect
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import org.siloserver.silo.common.ui.LanguageNames
import org.siloserver.silo.common.player.SessionState
import org.siloserver.silo.common.player.SleepTimerState
import org.siloserver.silo.model.playback.PlaybackQualityOption
import org.siloserver.silo.model.playback.playbackQualityMenu
import org.siloserver.silo.model.watchtogether.MemberRole
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.watchtogether.RoomTransportIntent
import org.siloserver.silo.watchtogether.roomTransportAuthorized
import org.siloserver.silo.playback.statusLabelFor
import org.siloserver.silo.playback.timingActionsFor

/**
 * Full-screen overlay composable that layers gesture handling, transport controls,
 * and contextual buttons (skip intro, next episode) on top of the video surface.
 *
 * Also manages bottom sheet display for subtitle, audio, quality, and version selection.
 */
@Composable
fun PlayerOverlay(
    state: PlayerViewModel.PlayerUiState,
    viewModel: PlayerViewModel,
    roomSnapshot: RoomSnapshot? = null,
    // True for the whole life of a Watch Party player, even while the room
    // snapshot is briefly unavailable, so its gates never fall back to solo.
    inRoom: Boolean = roomSnapshot != null,
    // Playback is held locally (audio focus, sleep timer, background): Play
    // stays available so the viewer can resume this device.
    roomSuspended: Boolean = false,
    // The party's current status line (waiting, catching up, host away,
    // reconnecting); null shows the member count.
    roomStatus: String? = null,
    isFastForwardHoldActive: Boolean = false,
    orientationLockSupported: Boolean = true,
    alwaysShowControls: Boolean = false,
    tabletopMode: Boolean = false,
    tabletopPaneHeight: Dp? = null,
    onNextUpVideoBoundsChanged: (Rect) -> Unit,
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
    // Google Cast (Chromecast) button rendered in the transport top bar; the
    // modifier dresses it as a player disc.
    castSlot: @Composable (Modifier) -> Unit = {},
    pictureInPictureAvailable: Boolean = false,
    onEnterPictureInPicture: () -> Unit = {},
    // Left edge (root px) of an open docked menu, null when none is open, so
    // the subtitle canvas can re-center in the visible part of the picture.
    onDockedMenuEdgeChanged: (Float?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Sheet visibility — one bool per sheet. iOS uses a sealed `activeSheet`
    // enum, but Compose Material 3 needs each ModalBottomSheet to own its
    // `rememberModalBottomSheetState`, so per-sheet bools are the natural
    // fit (and the sheets can't be nested anyway).
    var tracksSheetVisible by remember { mutableStateOf(false) }
    var tracksInitialTab by remember { mutableStateOf(TracksTab.Subtitles) }
    var qualitySheetVisible by remember { mutableStateOf(false) }
    var versionSheetVisible by remember { mutableStateOf(false) }
    var settingsSheetVisible by remember { mutableStateOf(false) }
    var subtitleStyleVisible by remember { mutableStateOf(false) }
    var sleepTimerVisible by remember { mutableStateOf(false) }
    var chaptersSheetVisible by remember { mutableStateOf(false) }
    var statsSheetVisible by remember { mutableStateOf(false) }
    var subtitleSearchVisible by remember { mutableStateOf(false) }
    var aiTranslateVisible by remember { mutableStateOf(false) }

    // Watch Party transport gating. Seek is host-only (the server rejects
    // guest seeks regardless of policy), so the scrubber, skips, chapters, and
    // the intro pill are disabled for ALL guests — even one under
    // guest_play_pause, who keeps play/pause. Solo playback enables both.
    val seekEnabled = !inRoom || roomTransportAuthorized(roomSnapshot, RoomTransportIntent.Seek)
    val playPauseEnabled = !inRoom ||
        roomSuspended ||
        roomTransportAuthorized(roomSnapshot, RoomTransportIntent.PlayPause)
    val isRoomHost = roomSnapshot?.selfRole == MemberRole.Host
    // In a party an intro never skips on its own; members who may seek get
    // the Skip pill as a room seek, and nobody else sees it (D8).
    val introPillAllowed = !inRoom || seekEnabled

    // The plan's Quality menu, shown whenever there is a plan (a single entry
    // still lists Auto + Original). A download playing offline has none.
    val qualityOptions = remember(state.playbackPlan) {
        playbackQualityMenu(state.playbackPlan?.availableQualities.orEmpty())
    }
    val activeQualityId = state.activeQualityId(qualityOptions)
    val hasQualityMenu = qualityOptions.isNotEmpty()
    // A party plays exactly the room's file: no version picker.
    val hasVersionMenu = state.versions.size > 1 && !inRoom

    // In a party, Back opens the party panel (PlayerScreen); solo backs out.
    val handleBack: () -> Unit = onBack
    val gatedSeek: (Double) -> Unit = { pos -> if (seekEnabled) onSeek(pos) }
    // Resolved profile-wide video intervals; read at press time so a change
    // made in settings applies to the next skip without restarting playback.
    val seekIntervals by viewModel.seekIntervals.collectAsState()
    val gatedSkipForward: () -> Unit = {
        if (seekEnabled) {
            val step = viewModel.seekIntervals.value.forwardSeconds.toDouble()
            if (inRoom) {
                val forward = state.position + step
                gatedSeek(if (state.duration > 0.0) forward.coerceAtMost(state.duration) else forward)
            } else {
                viewModel.onSkipBy(step)
            }
        }
    }
    val gatedSkipBackward: () -> Unit = {
        if (seekEnabled) {
            val step = viewModel.seekIntervals.value.backSeconds.toDouble()
            if (inRoom) gatedSeek((state.position - step).coerceAtLeast(0.0))
            else viewModel.onSkipBy(-step)
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
    val introSkipCountdownRun by viewModel.introSkipCountdownRun.collectAsState()
    val introSkipTimerRunning by viewModel.introSkipTimerRunning.collectAsState()
    // Back while the pill is up dismisses it and is consumed; a second Back
    // behaves normally, because by then no pill is showing and this handler is
    // disabled. The player has no other BackHandler of its own — sheets live in
    // their own dialog windows, so an open sheet's Back never reaches here.
    BackHandler(enabled = introSkipState.isVisible && introPillAllowed) { viewModel.onDismissIntroPrompt() }
    val sleepTimerState by viewModel.sleepTimerState.collectAsState()
    val sleepTimerDefault by viewModel.sleepTimerDefaultMinutes.collectAsState()
    val videoGravity by viewModel.videoGravity.collectAsState()
    val playbackSpeed by viewModel.playbackSpeed.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val sessionState by viewModel.sessionState.collectAsState()
    val subtitleTools by viewModel.subtitleTools.collectAsState()
    val subtitleSync by viewModel.subtitleSyncState.collectAsState()
    val subtitleSyncNotice by viewModel.subtitleSyncNotice.collectAsState()
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

    val menuHandoff = remember { PlayerMenuHandoff() }
    // A menu that is not drawn (no plan, or Version in a party) must not hide the controls.
    val anyMenuOpen = tracksSheetVisible || (qualitySheetVisible && hasQualityMenu) ||
        (versionSheetVisible && hasVersionMenu) || settingsSheetVisible ||
        subtitleStyleVisible || sleepTimerVisible || chaptersSheetVisible || statsSheetVisible ||
        subtitleSearchVisible || aiTranslateVisible

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val menuPresentation = playerMenuPresentationFor(
            widthDp = maxWidth.value,
            heightDp = maxHeight.value,
            tabletopMode = tabletopMode || tabletopPaneHeight != null,
        )
        // A docked menu replaces the controls; the picture beside it stays clear.
        val menuDocked = anyMenuOpen && menuPresentation == PlayerMenuPresentation.Docked
        CompositionLocalProvider(
            LocalPlayerMenuPresentation provides menuPresentation,
            LocalPlayerMenuHandoff provides menuHandoff,
            LocalPlayerMenuDockReporter provides onDockedMenuEdgeChanged,
        ) {
        // Gesture layer stays out of the tree while controls are visible so
        // full-screen pointer handlers cannot consume taps meant for buttons.
        if (!alwaysShowControls && !state.showControls && !state.showUpNext) {
            PlayerGestureHandler(
                onToggleControls = onToggleControls,
                onSkipForward = gatedSkipForward,
                onSkipBackward = gatedSkipBackward,
                seekEnabled = seekEnabled,
                skipBackSeconds = seekIntervals.backSeconds,
                skipForwardSeconds = seekIntervals.forwardSeconds,
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
                color = PlayerChrome.Paper,
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
            PlayerStatusChip {
                Text(text = "2×", style = PlayerType.PillLabel.copy(fontSize = 15.sp))
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

        // Subtitle sync card (top-right, below the top bar's actions): follows
        // a sync this viewer started, whether or not the controls show.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(top = 72.dp, end = 16.dp)
                .zIndex(9f),
            contentAlignment = Alignment.TopEnd,
        ) {
            SubtitleSyncCard(notice = subtitleSyncNotice, onDismiss = viewModel::dismissSubtitleSyncNotice)
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
                Text(
                    text = message.text,
                    style = PlayerType.RowTitle,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(PlayerChrome.Smoke)
                        .border(1.dp, PlayerChrome.PanelStroke, RoundedCornerShape(16.dp))
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }

        // Watch Party status (top-center): the room's status line, or the
        // member count, and the code for the host. Stays visible regardless of
        // controls visibility so members always know the room status.
        if (roomSnapshot != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(top = 16.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                PlayerStatusChip {
                    Icon(
                        imageVector = Icons.Rounded.Groups,
                        contentDescription = null,
                        tint = PlayerChrome.Paper,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    val label = roomStatus ?: "${roomSnapshot.memberCount} watching"
                    Text(
                        text = label,
                        style = PlayerType.PillLabel.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                        maxLines = 2,
                        modifier = Modifier.widthIn(max = 360.dp),
                    )
                    if (isRoomHost && roomSnapshot.code.isNotBlank()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = "Code ${roomSnapshot.code}", style = PlayerType.Time.copy(color = PlayerChrome.Graphite))
                    }
                }
            }
        }

        // Transport controls (shown/hidden with animation)
        AnimatedVisibility(
            visible = (alwaysShowControls || state.showControls) && !state.showUpNext && !menuDocked,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .fillMaxSize()
                .zIndex(1f),
        ) {
            PlayerControls(
                title = state.title,
                subtitle = state.subtitle,
                eyebrow = playerEyebrow(state),
                tracksValue = playerTracksValue(state),
                chapterValue = chapterTitleAt(state.chapters, state.position),
                qualityValue = playerQualityLabel(qualityOptions, activeQualityId),
                pictureInPictureAvailable = pictureInPictureAvailable,
                onEnterPictureInPicture = onEnterPictureInPicture,
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
                hasQualityMenu = hasQualityMenu,
                isOrientationLocked = isOrientationLocked,
                orientationLockSupported = orientationLockSupported,
                tabletopMode = tabletopMode,
                playbackSpeed = playbackSpeed,
                // A party plays at 1x; the saved speed is never changed from it.
                playbackSpeedEnabled = !inRoom,
                // A shuffle replaces the series order: no sequential next episode.
                nextEpisode = state.nextEpisode.takeUnless { inRoom || state.shuffle != null },
                brightnessFraction = brightnessFraction,
                seekEnabled = seekEnabled,
                playPauseEnabled = playPauseEnabled,
                onBack = handleBack,
                onPlayPause = gatedPlayPause,
                onSeek = gatedSeek,
                skipBackSeconds = seekIntervals.backSeconds,
                skipForwardSeconds = seekIntervals.forwardSeconds,
                onSkipForward = gatedSkipForward,
                onSkipBackward = gatedSkipBackward,
                onToggleOrientationLock = {
                    if (orientationLockSupported) {
                        viewModel.onSetOrientationLocked(!isOrientationLocked)
                    }
                },
                onOpenChapters = { chaptersSheetVisible = true },
                onOpenTracks = {
                    tracksInitialTab = TracksTab.Subtitles
                    tracksSheetVisible = true
                },
                onOpenQuality = { qualitySheetVisible = true },
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

        // Intro skip pill (Hidden / Asking / Skipped).
        // Shares the bottom-end slot with the Up Next card; intro and credits
        // never overlap in practice, but the card wins the slot if both could show.
        if (!state.showUpNext && introPillAllowed) {
            Box(
                modifier = bottomEndSlotModifier,
                contentAlignment = Alignment.BottomEnd,
            ) {
                IntroAutoSkipBanner(
                    state = introSkipState,
                    onSelect = if (inRoom) {
                        {
                            val target = viewModel.selectIntroPromptTarget()
                            if (target != null) gatedSeek(target)
                        }
                    } else {
                        viewModel::onSelectIntroPrompt
                    },
                    totalSeconds = viewModel.introSkipTotalSeconds,
                    countdownRun = introSkipCountdownRun,
                    timerRunning = introSkipTimerRunning,
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
                // The retained card covers the fade-out only; a shuffle that
                // can no longer play anything shows Finished while open.
                nextEpisode = state.nextEpisode
                    ?: retainedUpNextInfo.takeUnless { state.showUpNext && state.shuffle != null },
                onVideoBoundsChanged = onNextUpVideoBoundsChanged,
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
                shuffle = state.shuffle,
                onPickAnother = viewModel::pickAnotherShuffle,
                onStopShuffling = {
                    // Stop shuffling leaves the player, back to where the shuffle started.
                    viewModel.stopShuffling()
                    handleBack()
                },
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

        // Player menus. In landscape they dock beside the picture (see
        // PlayerMenu); the controls step aside while one is open.
        // Audio & Subtitles — opened from its action pill or a settings row.
        TracksSheet(
            isVisible = tracksSheetVisible,
            audioTracks = state.audioTracks,
            selectedAudioIndex = state.selectedAudioIndex,
            subtitles = state.subtitleTracks,
            selectedSubtitleIndex = state.selectedSubtitleIndex,
            onSelectAudio = onSelectAudio,
            onSelectSubtitle = onSelectSubtitle,
            onDismiss = { tracksSheetVisible = false },
            initialTab = tracksInitialTab,
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
            subtitleStatus = subtitleSync::statusLabelFor,
            timingActions = subtitleSync.timingActionsFor(
                state.subtitleTracks.getOrNull(state.selectedSubtitleIndex)?.syncKey,
            ),
            onSyncSubtitle = viewModel::requestSubtitleSync,
            onResetTiming = viewModel::resetSubtitleTiming,
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

        if (qualitySheetVisible && hasQualityMenu) {
            PlaybackQualitySheet(
                options = qualityOptions,
                activeId = activeQualityId,
                inRoom = inRoom,
                onSelect = viewModel::onSelectQuality,
                onDismiss = { qualitySheetVisible = false },
                tabletopPaneHeight = tabletopPaneHeight,
            )
        }

        if (versionSheetVisible && hasVersionMenu) {
            VersionSelector(
                versions = state.versions,
                selectedIndex = state.selectedVersionIndex,
                onSelect = onSelectVersion,
                onDismiss = { versionSheetVisible = false },
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
            letterboxExpansion = viewModel.letterboxExpansion.collectAsState().value,
            onSetLetterboxExpansion = viewModel::onSetLetterboxExpansion,
            introSkipMode = viewModel.introSkipMode.collectAsState().value,
            onSetIntroSkipMode = viewModel::onSetIntroSkipMode,
            autoPlayNextEnabled = viewModel.autoPlayNextEnabled.collectAsState().value,
            onSetAutoPlayNext = viewModel::onSetAutoPlayNext,
            hdrEnabled = viewModel.hdrEnabled.collectAsState().value,
            onSetHdrEnabled = viewModel::onSetHdrEnabled,
            dolbyVisionEnabled = viewModel.dolbyVisionEnabled.collectAsState().value,
            onSetDolbyVisionEnabled = viewModel::onSetDolbyVisionEnabled,
            // A party hides speed (session-only 1x) and the version picker.
            showPlaybackSpeed = !inRoom,
            showQuality = hasQualityMenu,
            qualityLabel = playerQualityLabel(qualityOptions, activeQualityId),
            onOpenQuality = {
                settingsSheetVisible = false
                qualitySheetVisible = true
            },
            showVersion = hasVersionMenu,
            versionLabel = playerVersionLabel(state.versions, state.selectedVersionIndex),
            onOpenVersion = {
                settingsSheetVisible = false
                versionSheetVisible = true
            },
            audioLabel = playerAudioLabel(state.audioTracks, state.selectedAudioIndex),
            subtitleLabel = playerSubtitleLabel(state.subtitleTracks, state.selectedSubtitleIndex),
            subtitleStyleLabel = subtitleStyleSummary(viewModel.subtitleAppearance.collectAsState().value),
            onOpenTracks = { tab ->
                settingsSheetVisible = false
                tracksInitialTab = tab
                tracksSheetVisible = true
            },
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
            duration = state.duration,
            // A room seek in a party (a guest is told only the host can seek).
            onSelect = { idx ->
                viewModel.onSeekToChapter(idx)?.let(onSeek)
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
            showTextOpacity = viewModel.subtitleTextOpacitySupported.collectAsState().value,
            onUpdate = viewModel::onEditSubtitleAppearance,
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
    }
}

/**
 * Sleep-timer status pill anchored top-right. Shows clock icon + "23m 17s"
 * remaining countdown. Non-interactive in v1 — cancel via the bottom sheet.
 */
@Composable
private fun SleepTimerChip(remainingSeconds: Int) {
    PlayerStatusChip {
        Icon(
            imageVector = Icons.Rounded.Bedtime,
            contentDescription = "Sleep timer active",
            tint = PlayerChrome.Paper,
            modifier = Modifier.size(14.dp),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = formatRemaining(remainingSeconds), style = PlayerType.Time)
    }
}

/** A small smoked capsule for status that sits on the picture. */
@Composable
private fun PlayerStatusChip(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .border(1.dp, PlayerChrome.DiscStroke, RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * The small line above the title: "MOVIE · 2026", or "SEVERANCE · S2:E4" for
 * an episode, whose title is then the episode's own.
 */
internal fun playerEyebrow(state: PlayerViewModel.PlayerUiState): String {
    val series = state.seriesTitle?.takeIf { it.isNotBlank() }
    if (series != null) {
        val code = if (state.seasonNumber != null && state.episodeNumber != null) {
            "S${state.seasonNumber}:E${state.episodeNumber}"
        } else {
            null
        }
        return listOfNotNull(series, code).joinToString(" · ")
    }
    val kind = when {
        state.contentType == "movie" -> "Movie"
        else -> null
    }
    return listOfNotNull(kind, state.subtitle.takeIf { it.isNotBlank() }).joinToString(" · ")
}

/** The value on the Audio & Subtitles pill: the subtitle language when one is on. */
internal fun playerTracksValue(state: PlayerViewModel.PlayerUiState): String? =
    if (state.selectedSubtitleIndex >= 0) {
        subtitleTrackShortLabel(state.subtitleTracks.getOrNull(state.selectedSubtitleIndex), state.selectedSubtitleIndex)
    } else {
        null
    }

/** "Large · White · Box" for the settings menu's Subtitle style row. */
internal fun subtitleStyleSummary(appearance: org.siloserver.silo.model.settings.SubtitleAppearance): String {
    val size = when (appearance.fontSize) {
        org.siloserver.silo.model.settings.SubtitleFontSizePreset.Small -> "Small"
        org.siloserver.silo.model.settings.SubtitleFontSizePreset.Medium -> "Medium"
        org.siloserver.silo.model.settings.SubtitleFontSizePreset.Large -> "Large"
        org.siloserver.silo.model.settings.SubtitleFontSizePreset.XLarge -> "Extra large"
        else -> "Largest"
    }
    val background = when (appearance.backgroundStyle) {
        org.siloserver.silo.model.settings.SubtitleBackgroundStylePreset.Box -> "Box"
        org.siloserver.silo.model.settings.SubtitleBackgroundStylePreset.Shadow -> "Shadow"
        org.siloserver.silo.model.settings.SubtitleBackgroundStylePreset.Outline -> "Outline"
        else -> "No background"
    }
    return "$size · $background"
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

/**
 * Root-list values for the gear menu. These mirror what the quality, version,
 * and tracks sheets show when opened, so the menu can state the current pick
 * without the user having to open anything.
 */
internal fun playerQualityLabel(options: List<PlaybackQualityOption>, activeId: String?): String =
    options.firstOrNull { it.id == activeId }?.name ?: "Auto"

internal fun playerVersionLabel(
    versions: List<org.siloserver.silo.model.catalog.FileVersion>,
    selectedIndex: Int,
): String {
    val version = versions.getOrNull(selectedIndex) ?: return "Auto"
    return buildString {
        version.editionLabel?.let { append(it).append(" · ") }
        append(version.resolution ?: "Unknown")
        if (version.hdr) append(" HDR")
    }
}

internal fun playerAudioLabel(
    tracks: List<org.siloserver.silo.model.catalog.AudioTrack>,
    selectedIndex: Int,
): String {
    val track = tracks.getOrNull(selectedIndex) ?: return "Default"
    // Language first, not title: a container's audio title is often the full
    // codec string ("ATSC A/52B (AC-3, E-AC-3)") and swamps the row.
    val presentation = audioTrackPresentation(track, selectedIndex)
    return listOfNotNull(presentation.title, audioChannelsDisplayName(track.channels)).joinToString(" · ")
}

internal fun playerSubtitleLabel(
    tracks: List<org.siloserver.silo.model.playback.PlayerSubtitleInfo>,
    selectedIndex: Int,
): String {
    if (selectedIndex < 0) return "Off"
    return subtitleTrackShortLabel(tracks.getOrNull(selectedIndex), selectedIndex) ?: "Off"
}
