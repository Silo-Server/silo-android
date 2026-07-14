@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.siloserver.silo.tv.ui.screens.player

import android.app.Activity
import android.content.ComponentName
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.siloserver.silo.common.player.ActivePlayerHolder
import org.siloserver.silo.common.player.AudioCapabilityManager
import org.siloserver.silo.common.player.SiloPlaybackService
import org.siloserver.silo.common.player.DisplayHdrProbe
import org.siloserver.silo.common.player.HdrDisplayController
import org.siloserver.silo.common.player.PlaybackCapabilityDetector
import org.siloserver.silo.common.player.PlaybackPreflightListener
import org.siloserver.silo.common.player.SessionState
import org.siloserver.silo.common.player.SleepTimerState
import org.siloserver.silo.common.player.SubtitleManager
import org.siloserver.silo.common.player.VideoPlayerMediaSpec
import org.siloserver.silo.common.pip.SiloPictureInPictureCoordinator
import org.siloserver.silo.common.pip.SiloPictureInPicturePlaybackState
import org.siloserver.silo.common.pip.SiloPictureInPictureSurface
import org.siloserver.silo.common.player.backend.PlaybackEngineCommand
import org.siloserver.silo.common.player.backend.VideoPlaybackBackendFactory
import org.siloserver.silo.common.player.backend.VideoPlaybackBackendRequest
import org.siloserver.silo.common.player.backend.VideoPlaybackFormFactor
import org.siloserver.silo.common.player.backend.isLikelyAdaptiveHlsStreamUrl
import org.siloserver.silo.common.player.mpv.MpvVideoScaleController
import org.siloserver.silo.common.player.mpv.MpvVideoScaleMode
import org.siloserver.silo.common.player.video.PlaybackStartupStallDetector
import org.siloserver.silo.common.player.video.VideoPlayerTrackEntry
import org.siloserver.silo.common.player.video.isMpvPreferredOriginalPlaybackContainer
import org.siloserver.silo.cast.SiloCastPlaybackState
import org.siloserver.silo.cast.SiloCastQualityOption
import org.siloserver.silo.cast.SiloCastTrack
import org.siloserver.silo.domain.player.IntroAutoSkipState
import org.siloserver.silo.model.playback.PlaybackExecutionPlan
import org.siloserver.silo.model.playback.PlaybackSourceMetadata
import org.siloserver.silo.model.settings.SubtitleAppearance
import org.siloserver.silo.model.settings.SubtitlePositionPreset
import org.siloserver.silo.model.settings.legacyPosition
import org.siloserver.silo.model.watchtogether.RoomPlaybackState
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.player.DolbyVisionDetection
import org.siloserver.silo.player.formatSubtitleTrackDisplayLabel
import org.siloserver.silo.tv.R
import org.siloserver.silo.tv.cast.TvSiloCastPlayerAdapter
import org.siloserver.silo.tv.cast.TvSiloCastReceiver
import org.siloserver.silo.tv.ui.components.TvErrorScreen
import org.siloserver.silo.tv.ui.components.TvLoadingScreen
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.coroutines.resume
import kotlin.math.roundToInt

private const val CONTROLS_AUTO_HIDE_MS = 5_000L
// Cap on how long the client waits for a SET_ENGINE round-trip before treating
// the switch as failed. The service builds the engine (MPV native init can be
// slow) under NonCancellable while holding engineSwitchMutex, so this must be
// long enough not to abort a legitimately slow init, yet short enough to
// recover from a wedged init instead of stranding a permanent black screen and
// blocking every later switch behind the held mutex. 25s splits that range.
private const val ENGINE_SWITCH_TIMEOUT_MS = 25_000L
// Skip back is 10s; skip forward is 30s, matching tvOS (gobackward.10 /
// goforward.30).
private const val SKIP_BACK_MS = 10_000L
// How long the transient skip indicator stays up after the last D-pad skip.
private const val SKIP_FEEDBACK_HIDE_MS = 1_200L
private const val SKIP_FORWARD_MS = 30_000L

/**
 * Full-screen TV player. The ExoPlayer itself lives in [SiloPlaybackService];
 * we drive it via a [MediaController]. The Compose overlay ([TvPlayerControls])
 * replaces the default [PlayerView] controller so we own focus, skip buttons,
 * and the subtitle / audio menus.
 */
@Composable
fun TvPlayerScreen(
    contentId: String,
    onExit: () -> Unit,
    preferredFileId: Int? = null,
    preferredQuality: String? = null,
    // Watch Together room binding. When non-null, a [TvRoomSyncController]
    // binds this player to the synced room for the lifetime of the screen.
    roomId: String? = null,
    resumePositionOverride: Double? = null,
    // Pre-playback track selections chosen on the detail screen (null = auto;
    // subtitle -1 = Off). Audio goes to the server session start; subtitle is
    // applied client-side once the player's tracks land.
    initialAudioTrackIndex: Int? = null,
    initialSubtitleTrackIndex: Int? = null,
    // Consecutive auto-advance count (pass-out protection); 0 = manual start.
    autoAdvanceCount: Int = 0,
    // Navigate to the next episode (auto-advance / "Continue"), carrying the
    // updated streak count.
    onPlayNext: (contentId: String, autoAdvanceCount: Int, preferredQuality: String?) -> Unit = { _, _, _ -> },
    // Scope the ViewModel key by fileId too so switching 4K <-> 1080p on
    // the detail screen and replaying actually spins up a fresh player
    // session instead of reusing the cached one bound to the first fileId.
    viewModel: TvPlayerViewModel = koinViewModel(
        key = "tv-player-$contentId-${preferredFileId ?: "auto"}-${preferredQuality ?: "quality-auto"}-${roomId ?: "solo"}-${resumePositionOverride ?: "server"}-${initialAudioTrackIndex ?: "a"}-${initialSubtitleTrackIndex ?: "s"}",
        parameters = {
            parametersOf(
                TvPlayerLaunchArgs(
                    contentId = contentId,
                    preferredFileId = preferredFileId,
                    preferredQuality = preferredQuality,
                    roomId = roomId,
                    resumePositionOverride = resumePositionOverride,
                    initialAudioTrackIndex = initialAudioTrackIndex,
                    initialSubtitleTrackIndex = initialSubtitleTrackIndex,
                    autoAdvanceCount = autoAdvanceCount,
                ),
            )
        },
    ),
    backendFactory: VideoPlaybackBackendFactory = koinInject(),
    subtitleManager: SubtitleManager = koinInject(),
    audioCapabilityManager: AudioCapabilityManager = koinInject(),
    capabilityDetector: PlaybackCapabilityDetector = koinInject(),
    activePlayerHolder: ActivePlayerHolder = koinInject(),
    pictureInPictureCoordinator: SiloPictureInPictureCoordinator = koinInject(),
    siloCastReceiver: TvSiloCastReceiver = koinInject(),
) {
    val state by viewModel.uiState.collectAsState()
    val isInPictureInPictureMode by pictureInPictureCoordinator.isInPictureInPictureMode.collectAsState()
    // The real session player (ExoPlayer/MpvPlayer) the service publishes. The
    // PlayerView surface must bind to THIS, not the MediaController, so the
    // surface gets correct lifecycle callbacks (esp. MPV) and re-binds on engine
    // swap. Mirrors phone PlayerScreen. The MediaController is kept for transport.
    val sessionPlayer by activePlayerHolder.player.collectAsState()
    val notice by viewModel.notice.collectAsState()
    val remoteMessage by viewModel.remoteMessage.collectAsState()
    LaunchedEffect(remoteMessage?.id) {
        if (remoteMessage != null) {
            kotlinx.coroutines.delay(5_000)
            viewModel.clearRemoteMessage()
        }
    }
    val sessionState by viewModel.sessionState.collectAsState()
    val introSkipState by viewModel.introSkipState.collectAsState()
    val subtitleAppearance by viewModel.subtitleAppearance.collectAsState()
    val playbackSpeed by viewModel.playbackSpeed.collectAsState()
    val sleepTimerState by viewModel.sleepTimerState.collectAsState()
    val autoSkipIntroEnabled by viewModel.autoSkipIntroEnabled.collectAsState()
    val autoPlayNextEnabled by viewModel.autoPlayNextEnabled.collectAsState()
    val audioDelayMs by viewModel.audioDelayMs.collectAsState()
    val subtitleDelayMs by viewModel.subtitleDelayMs.collectAsState()
    val hdrEnabled by viewModel.hdrEnabled.collectAsState()
    val dolbyVisionEnabled by viewModel.dolbyVisionEnabled.collectAsState()
    val subtitleSearch by viewModel.subtitleSearch.collectAsState()
    val aiTranslate by viewModel.aiTranslate.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnExit by rememberUpdatedState(onExit)
    val latestSiloCastState by rememberUpdatedState(state)
    val latestSiloCastPlaybackSpeed by rememberUpdatedState(playbackSpeed)
    val latestSiloCastSubtitleDelayMs by rememberUpdatedState(subtitleDelayMs)
    val latestSiloCastHdrEnabled by rememberUpdatedState(hdrEnabled)
    val latestSiloCastSubtitleAppearance by rememberUpdatedState(subtitleAppearance)
    val context = LocalContext.current
    val hdrDisplayController = remember { HdrDisplayController() }
    val displayHdr = remember { DisplayHdrProbe.probe(context) }
    val audioCaps by audioCapabilityManager.capabilities.collectAsState()
    val rootFocus = remember { FocusRequester() }
    val exitScope = rememberCoroutineScope()
    var exitRequested by remember { mutableStateOf(false) }
    var requestedHudTab by remember { mutableStateOf(HudTab.Info) }
    var showQuickSubtitlePicker by remember { mutableStateOf(false) }
    // Mirrors the HUD's internal active-picker slot so the screen-level
    // BackHandler can defer to an open picker (Back closes only the picker).
    var hudPickerOpen by remember { mutableStateOf(false) }
    // Clear the mirror whenever the HUD itself is gone, so a stale "picker open"
    // can never wedge the screen BackHandler off.
    LaunchedEffect(state.hudOpen) { if (!state.hudOpen) hudPickerOpen = false }
    // Captured PlayerView reference so subtitleManager.applyAppearance can hit
    // the inflated subtitleView after the AndroidView factory runs. Mirrors
    // the phone PlayerScreen's `playerViewRef` pattern.
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    var transportFocusRequest by remember { mutableStateOf(0) }
    // Hidden-controls D-pad skip feedback: a transient chip + progress line so
    // the seek isn't invisible. Kept OUTSIDE showControls on purpose — showing
    // the transport would flip Left/Right from discrete skips into scrubber
    // nudges mid-sequence (dpadHorizontalSeek gates on !showControls).
    var skipSeekFeedback by remember { mutableStateOf<SkipSeekFeedback?>(null) }
    LaunchedEffect(skipSeekFeedback?.nonce) {
        if (skipSeekFeedback != null) {
            kotlinx.coroutines.delay(SKIP_FEEDBACK_HIDE_MS)
            skipSeekFeedback = null
        }
    }
    val startupStallDetector = remember { PlaybackStartupStallDetector() }
    var pictureInPictureVideoWidth by remember { mutableStateOf(16) }
    var pictureInPictureVideoHeight by remember { mutableStateOf(9) }
    var pictureInPictureSourceRect by remember { mutableStateOf<Rect?>(null) }

    // Watch Together binding. Built once per roomId; null for solo playback.
    // The controller owns the room WS connection + RoomSyncEngine for the
    // lifetime of this screen and tears them down on explicit leave.
    val watchTogetherRepository: org.siloserver.silo.repository.WatchTogetherRepository = koinInject()
    val roomScope = rememberCoroutineScope()
    val roomController = remember(roomId) {
        roomId?.takeIf { it.isNotBlank() }?.let { id ->
            TvRoomSyncController(
                roomId = id,
                repository = watchTogetherRepository,
                viewModel = viewModel,
                scope = roomScope,
            )
        }
    }
    DisposableEffect(roomController) {
        roomController?.start()
        // Repo teardown happens on explicit leave (Leave affordance) or
        // room_closed; the connect scope dies with roomScope on dispose.
        onDispose { }
    }
    val roomSnapshot by (roomController?.room ?: kotlinx.coroutines.flow.MutableStateFlow(null))
        .collectAsState()
    val roomClosedReason by (roomController?.closedReason ?: kotlinx.coroutines.flow.MutableStateFlow(null))
        .collectAsState()
    var showLeaveDialog by remember { mutableStateOf(false) }

    // Per-session playback control socket (admin remote control). Bound for the
    // lifetime of a sessionId; reconnects on its own and never interrupts
    // playback. Separate from the Watch Together socket above.
    val playbackRealtimeClient: org.siloserver.silo.network.PlaybackRealtimeClient = koinInject()
    LaunchedEffect(state.sessionId) {
        val id = state.sessionId ?: return@LaunchedEffect
        TvPlaybackRealtimeController(
            sessionId = id,
            client = playbackRealtimeClient,
            viewModel = viewModel,
            scope = this, // cancelled when sessionId changes / screen leaves
        ).start()
    }

    // Connect a MediaController to the SiloPlaybackService. Async —
    // downstream effects gate on a non-null controller.
    var mediaController by remember { mutableStateOf<MediaController?>(null) }
    val videoBackend = remember(
        sessionPlayer,
        mediaController,
        backendFactory,
        contentId,
        preferredFileId,
        state.playMethod,
        state.playbackPlan,
        state.delivery,
        state.container,
        state.streamUrl,
    ) {
        val plan = state.playbackPlan
        val delivery = plan?.delivery ?: state.delivery
        (sessionPlayer ?: mediaController)?.let { player ->
            backendFactory.create(
                player = player,
                request = VideoPlaybackBackendRequest(
                    contentId = contentId,
                    fileId = preferredFileId,
                    playMethod = state.playMethod,
                    delivery = delivery,
                    plannedEngine = plan?.engine,
                    routeFamily = plan?.routeFamily,
                    formFactor = VideoPlaybackFormFactor.Tv,
                    hasHardContainer = state.playMethod == org.siloserver.silo.model.playback.PlayMethod.DIRECT &&
                        isHardPlaybackContainer(state.container),
                    hasStyledSubtitles = state.subtitleUrls.any { it.isStyledSubtitle() },
                    hasSoftwareOnlyVideoCodec = state.softwareOnlyVideoCodec,
                    hasDolbyVisionVideo = hasDolbyVisionSource(plan?.source),
                    isAdaptiveHlsStream = isLikelyAdaptiveHlsStreamUrl(state.streamUrl),
                ),
            )
        }
    }
    LaunchedEffect(videoBackend) {
        videoBackend?.let { backend ->
            viewModel.onBackendCapabilities(backend.capabilities)
        }
    }
    val latestSiloCastMediaController by rememberUpdatedState(mediaController)
    val latestSiloCastSessionPlayer by rememberUpdatedState(sessionPlayer)
    DisposableEffect(siloCastReceiver, viewModel, contentId) {
        var lastAudibleRemoteVolume = latestSiloCastMediaController
            ?.volume
            ?.takeIf { it > 0.001f }
            ?: 1f
        val adapter = TvSiloCastPlayerAdapter(
            play = {
                // Watch Together is authoritative for transport: suppress
                // SiloCast transport while in a room so a caster can't desync
                // members, mirroring the realtime remote path's
                // remoteTransportSuppressed gate. Non-room casting is unchanged.
                if (!viewModel.remoteTransportSuppressed) {
                    viewModel.setPaused(false)
                    latestSiloCastMediaController?.play()
                }
            },
            pause = {
                if (!viewModel.remoteTransportSuppressed) {
                    viewModel.setPaused(true)
                    latestSiloCastMediaController?.pause()
                }
            },
            playPause = { if (!viewModel.remoteTransportSuppressed) viewModel.onPlayPause() },
            seek = { seconds ->
                if (!viewModel.remoteTransportSuppressed) {
                    viewModel.seekImmediate(seconds)
                    latestSiloCastMediaController?.seekTo((seconds * 1_000).toLong())
                }
            },
            stop = { if (!viewModel.remoteTransportSuppressed) viewModel.remoteStop() },
            selectAudio = { index -> viewModel.remoteSelectAudio(index.toInt()) },
            selectSubtitle = { index -> viewModel.remoteSelectSubtitle(index?.toInt() ?: -1) },
            setPlaybackSpeed = { speed ->
                viewModel.onSetPlaybackSpeed(speed)
                latestSiloCastMediaController?.playbackParameters = PlaybackParameters(speed.toFloat())
            },
            setQuality = { qualityId ->
                val player = latestSiloCastMediaController ?: latestSiloCastSessionPlayer
                if (player != null && selectVideoQuality(player, qualityId)) {
                    val resolution = latestSiloCastState.videoQualities
                        .firstOrNull { it.id == qualityId }
                        ?.resolution
                    viewModel.onVideoQualitySelectionApplied(resolution)
                }
            },
            setVideoGravity = { value ->
                viewModel.onVideoFillModeChanged(value.toSiloCastVideoFillMode())
            },
            setHdrEnabled = viewModel::onSetHdrEnabled,
            setSubtitleSyncMs = viewModel::onSubtitleDelayChanged,
            setSubtitlePosition = { value ->
                viewModel.onSetSubtitleAppearance(
                    latestSiloCastSubtitleAppearance.copy(position = value.toSiloCastSubtitlePosition()),
                )
            },
            setVolume = { volume ->
                val next = volume.toFloat().coerceIn(0f, 1f)
                if (next > 0.001f) lastAudibleRemoteVolume = next
                latestSiloCastMediaController?.volume = next
            },
            setMuted = { muted ->
                val controller = latestSiloCastMediaController
                if (muted) {
                    controller?.volume?.takeIf { it > 0.001f }?.let { lastAudibleRemoteVolume = it }
                    controller?.volume = 0f
                } else {
                    controller?.volume = lastAudibleRemoteVolume
                }
            },
            playNext = viewModel::playNextEpisodeNow,
        )
        val registration = siloCastReceiver.registerPlayer(adapter) {
            latestSiloCastState.toSiloCastPlaybackState(
                contentId = contentId,
                playbackSpeed = latestSiloCastPlaybackSpeed,
                hdrEnabled = latestSiloCastHdrEnabled,
                subtitleDelayMs = latestSiloCastSubtitleDelayMs,
                subtitleAppearance = latestSiloCastSubtitleAppearance,
                volume = latestSiloCastMediaController?.volume?.toDouble() ?: 1.0,
            )
        }
        onDispose { registration.close() }
    }
    val stopPlaybackAndExit = {
        if (!exitRequested) {
            exitRequested = true
            mediaController?.let { controller ->
                viewModel.onPositionChanged(
                    controller.currentPosition,
                    controller.duration.coerceAtLeast(0L),
                )
                controller.pause()
                controller.stop()
                controller.clearMediaItems()
            }
            exitScope.launch {
                viewModel.stopSessionForExit()
                latestOnExit()
            }
        }
    }
    // A remote "stop"/"terminate" command tears the screen down like a Back press.
    LaunchedEffect(Unit) {
        viewModel.remoteStopRequests.collect { stopPlaybackAndExit() }
    }
    // F2: auto-advance / Continue navigates to the next episode's player,
    // carrying the updated pass-out streak count. popUpTo the current player so
    // Back doesn't walk back through a chain of auto-played episodes.
    LaunchedEffect(Unit) {
        viewModel.playNextRequests.collect { req ->
            exitRequested = true
            mediaController?.let { c -> c.pause(); c.stop(); c.clearMediaItems() }
            // AWAIT the old session stop before navigating: the lifecycle is a
            // singleton, so a late stop() could clobber the next episode's freshly
            // adopted session, and popUpTo would otherwise cancel it mid-flight.
            viewModel.stopSessionForExit()
            onPlayNext(req.contentId, req.autoAdvanceCount, req.preferredQuality)
        }
    }
    val applyTvSubtitleSelection: (Int, Boolean) -> Unit = selection@{ idx, dismiss ->
        val selectedTrack = state.subtitleTracks
            .firstOrNull { it.index == idx }
            ?.toVideoTrackEntry()
        if (idx >= 0 && selectedTrack == null) {
            Log.w(TAG, "Subtitle selection ignored: index=$idx not found")
            return@selection
        }
        val backend = videoBackend
        if (backend == null) {
            Log.w(TAG, "Subtitle selection deferred or failed for index=$idx: backend unavailable")
            return@selection
        }
        if (backend.selectSubtitle(selectedTrack)) {
            viewModel.onManualSubtitleSelectionIntent()
            viewModel.onSubtitleSelectionApplied(idx)
            if (dismiss) viewModel.closeSubtitleMenu()
            viewModel.persistSubtitleSelection(idx)
        } else {
            Log.w(TAG, "Subtitle selection deferred or failed for index=$idx")
        }
    }
    fun handleSkipIntroNow(): Boolean {
        val target = state.intro?.end ?: return false
        if (roomController != null) {
            if (tvRoomTransportGate(roomSnapshot, TvTransportIntent.Seek) != TransportGate.Send) {
                return true
            }
            viewModel.onSkipIntroNow() ?: return false
            roomController.onUserSeek(target)
        } else {
            val soloTarget = viewModel.onSkipIntroNow() ?: return false
            mediaController?.seekTo((soloTarget * 1000).toLong())
        }
        return true
    }

    fun performRelativeSeek(
        deltaMs: Long,
        snapshot: RoomSnapshot?,
        revealControls: Boolean,
    ): Boolean {
        val controller = mediaController ?: return true
        if (roomController != null &&
            tvRoomTransportGate(snapshot, TvTransportIntent.Seek) != TransportGate.Send
        ) {
            return true
        }
        val duration = controller.duration
        val upperBound = if (duration > 0L) duration else Long.MAX_VALUE
        val target = (controller.currentPosition + deltaMs)
            .coerceAtLeast(0L)
            .coerceAtMost(upperBound)
        if (roomController != null) {
            roomController.onUserSeek(target / 1000.0)
        } else {
            // seekImmediate pre-writes uiState.position (like room seeks) so the
            // credits-crossing check treats this as a local seek, not natural
            // playback reaching the credits point. The seekRequests collector
            // applies it to the controller.
            viewModel.seekImmediate(target / 1000.0)
        }
        if (revealControls) {
            viewModel.setControlsVisible(true)
        } else {
            // Silent seek: surface the transient skip indicator instead.
            skipSeekFeedback = SkipSeekFeedback(
                deltaSeconds = (deltaMs / 1000).toInt(),
                targetSec = target / 1000.0,
                durationSec = if (duration > 0L) duration / 1000.0 else 0.0,
                nonce = (skipSeekFeedback?.nonce ?: 0) + 1,
            )
        }
        return true
    }

    DisposableEffect(context) {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, SiloPlaybackService::class.java),
        )
        val future = MediaController.Builder(context, sessionToken).buildAsync()
        future.addListener(
            {
                if (future.isDone && !future.isCancelled) {
                    mediaController = runCatching { future.get() }.getOrNull()
                }
            },
            MoreExecutors.directExecutor(),
        )
        onDispose {
            // Stop + clear the service player before releasing the controller.
            // Releasing alone only disconnects this client; the session player in
            // SiloPlaybackService keeps running (background playback after
            // leaving the screen). MainTvActivity declares configChanges, so this
            // dispose only fires on a real screen exit, not a config change.
            // Mirrors phone PlayerScreen's dispose.
            mediaController?.let { controller ->
                runCatching {
                    controller.pause()
                    controller.stop()
                    controller.clearMediaItems()
                }
                controller.release()
            }
            mediaController = null
            if (!future.isDone) future.cancel(true)
        }
    }

    // While a HUD picker dialog is open, the HUD owns Back: its onPreviewKeyEvent
    // closes only the active picker and consumes the event, so the screen-level
    // BackHandler must defer (disabled) rather than tearing down the whole HUD.
    BackHandler(enabled = !(state.hudOpen && hudPickerOpen)) {
        when {
            showQuickSubtitlePicker -> showQuickSubtitlePicker = false
            state.showSubtitleStyleDialog -> viewModel.closeSubtitleStyleDialog()
            state.showSubtitleMenu -> viewModel.closeSubtitleMenu()
            // On the Up-Next overlay, Back exits the player (matches tvOS, where
            // the Up-Next "Back" button dismisses the whole player).
            state.showNextUp -> stopPlaybackAndExit()
            state.hudOpen -> viewModel.closeHUD()
            showLeaveDialog -> showLeaveDialog = false
            // While PLAYING, Back steps controls -> hidden before exiting.
            // While PAUSED, hiding controls would just strand a frozen frame,
            // so Back falls through to the exit (or room-leave) flow instead —
            // Apple parity (silo-apple f12a928).
            state.showControls && !state.isPaused -> viewModel.setControlsVisible(false)
            // In a room: Back surfaces the Leave affordance. Host gets a
            // close-confirm dialog (closing tears down the room for everyone);
            // a guest leaves immediately.
            roomController != null && roomSnapshot?.isHost == true -> showLeaveDialog = true
            roomController != null -> {
                roomController.leave(closeRoom = false)
                stopPlaybackAndExit()
            }
            else -> {
                stopPlaybackAndExit()
            }
        }
    }

    val latestPlayerState by rememberUpdatedState(state)
    val latestIntroSkipState by rememberUpdatedState(introSkipState)
    val latestRoomSnapshot by rememberUpdatedState(roomSnapshot)
    val latestShowLeaveDialog by rememberUpdatedState(showLeaveDialog)
    val latestShowQuickSubtitlePicker by rememberUpdatedState(showQuickSubtitlePicker)
    DisposableEffect(viewModel, roomController) {
        val handler: (KeyEvent) -> Boolean = handler@{ event ->
            val playerState = latestPlayerState
            if (playerState.streamUrl == null || playerState.isLoading || playerState.error != null) {
                return@handler false
            }
            val action = tvPlayerRemoteKeyAction(
                keyCode = event.keyCode,
                action = event.action,
                repeatCount = event.repeatCount,
                // With the transport overlay or Up Next on screen, Left/Right
                // belong to Compose focus navigation, not seeking.
                dpadHorizontalSeek = !playerState.showControls && !playerState.showNextUp,
            )
            // Apple parity (TVPlayerControls.rearmAutoHideOnFocusMove): any key
            // activity while the overlay is up re-arms the 5s auto-hide so the
            // menu can't vanish out from under the user mid-navigation. Bumps
            // the visibility nonce only — the event still falls through.
            if (event.action == KeyEvent.ACTION_DOWN && playerState.showControls) {
                viewModel.setControlsVisible(true)
            }
            if (latestShowQuickSubtitlePicker ||
                playerState.hudOpen || playerState.showSubtitleMenu ||
                playerState.showSubtitleStyleDialog || latestShowLeaveDialog ||
                // The Up-Next overlay is a focus-trapping Compose surface that
                // owns its own remote input (Play Now / Keep Watching / Back) —
                // don't let the transport bridge toggle play/pause underneath it.
                playerState.showNextUp
            ) {
                // While the Up-Next overlay is visible, swallow media Play/Pause
                // keys here so they can't reach Media3 / the system media-key
                // fallback and toggle playback underneath the countdown. The
                // overlay's own buttons (Play Now / Keep Watching) drive the
                // transition. D-pad and other keys still fall through (false) so
                // the overlay's focused buttons keep receiving navigation input.
                if (playerState.showNextUp &&
                    (action == TvPlayerRemoteKeyAction.PlayPause ||
                        action == TvPlayerRemoteKeyAction.ConsumeOnly)
                ) {
                    return@handler true
                }
                return@handler false
            }

            if (event.action == KeyEvent.ACTION_DOWN &&
                event.repeatCount == 0 &&
                latestIntroSkipState is IntroAutoSkipState.ShowingButton &&
                event.keyCode in setOf(
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                )
            ) {
                return@handler handleSkipIntroNow()
            }

            // Back while PLAYING with the transport overlay up: hide the
            // overlay HERE, at the key-dispatch bridge, before Compose's
            // focus system can eat the press as a button focus-deselection
            // (QA 2026-07-08: Back on a focused control deselected the button
            // instead of dismissing the overlay). Paused/room/dialog cases
            // stay with the BackHandler's stepping logic.
            if (event.keyCode == KeyEvent.KEYCODE_BACK &&
                playerState.showControls &&
                !playerState.isPaused &&
                !playerState.hudOpen &&
                !playerState.showNextUp &&
                !playerState.showSubtitleMenu &&
                !playerState.showSubtitleStyleDialog &&
                !latestShowQuickSubtitlePicker &&
                !latestShowLeaveDialog
            ) {
                if (event.action == KeyEvent.ACTION_UP) {
                    // While scrubbing, Back cancels the in-flight scrub (drop the
                    // preview, keep playing) rather than hiding the whole overlay.
                    if (playerState.isScrubbing) {
                        viewModel.cancelScrub()
                    } else {
                        viewModel.setControlsVisible(false)
                    }
                }
                return@handler true
            }

            when (action) {
                TvPlayerRemoteKeyAction.PlayPause -> {
                    val canPlayPauseInRoom = roomController == null ||
                        tvRoomTransportGate(
                            latestRoomSnapshot,
                            TvTransportIntent.PlayPause,
                        ) == TransportGate.Send
                    if (canPlayPauseInRoom) {
                        if (roomController != null) {
                            roomController.onUserPlayPause()
                        } else {
                            viewModel.onPlayPause()
                        }
                    }
                    viewModel.setControlsVisible(true)
                    transportFocusRequest++
                    true
                }
                TvPlayerRemoteKeyAction.FocusTransport -> {
                    viewModel.setControlsVisible(true)
                    transportFocusRequest++
                    true
                }
                TvPlayerRemoteKeyAction.SkipBack ->
                    performRelativeSeek(-SKIP_BACK_MS, latestRoomSnapshot, revealControls = false)
                TvPlayerRemoteKeyAction.SkipForward ->
                    performRelativeSeek(SKIP_FORWARD_MS, latestRoomSnapshot, revealControls = false)
                TvPlayerRemoteKeyAction.OpenHud -> {
                    requestedHudTab = HudTab.Info
                    viewModel.openHUD()
                    true
                }
                TvPlayerRemoteKeyAction.ConsumeOnly -> true
                null -> {
                    if (
                        event.action == KeyEvent.ACTION_DOWN &&
                        event.keyCode != KeyEvent.KEYCODE_BACK &&
                        !playerState.showControls
                    ) {
                        viewModel.setControlsVisible(true)
                        transportFocusRequest++
                        true
                    } else {
                        false
                    }
                }
            }
        }
        TvPlayerRemoteKeyBridge.install(handler)
        onDispose { TvPlayerRemoteKeyBridge.clear(handler) }
    }

    // room_closed (TERMINAL only — host left / explicit close) → stop + exit
    // back to detail. Transient server `error` frames never reach here (they
    // flow on the repo's errors stream and do NOT eject the user).
    LaunchedEffect(roomClosedReason) {
        if (roomClosedReason != null && roomController != null) {
            stopPlaybackAndExit()
        }
    }

    // Surface transient Watch Together server rejections (e.g. a guest seek the
    // server refuses) as a brief Toast. These flow on the repo errors stream and
    // do NOT eject the user. Only collected while bound to a room.
    LaunchedEffect(roomController) {
        if (roomController != null) {
            watchTogetherRepository.errors.collect { message ->
                if (message.isNotBlank()) {
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // Apply capability-aware track selection presets. Re-runs on HDMI
    // hot-plug / AVR power cycle so Atmos and DV stay preferred as the sink
    // reports them. Also re-runs when the user flips the HDR toggle in the
    // HUD so the new preference takes effect on the already-mounted player
    // (A.3d-hdr).
    LaunchedEffect(
        mediaController,
        videoBackend,
        audioCaps,
        state.preferredAudioLanguage,
        state.preferredTextLanguage,
        hdrEnabled,
        dolbyVisionEnabled,
    ) {
        val backend = videoBackend ?: return@LaunchedEffect
        // With Dolby Vision off, drop DV profiles (except 5 — no watchable
        // base layer) so the DV MIME preference is not added and multi-track
        // content selects the HEVC/HDR10 variant. DolbyVisionPolicy is the
        // single decision source (Apple parity, silo-apple e9bd775).
        val effectiveDisplayHdr = displayHdr.copy(
            dolbyVisionProfiles = org.siloserver.silo.player.DolbyVisionPolicy.advertisableProfiles(
                displayHdr.dolbyVisionProfiles,
                org.siloserver.silo.player.DolbyVisionPolicy.Snapshot(dolbyVisionEnabled = dolbyVisionEnabled),
            ),
        )
        backend.applyTrackSelection(
            audioCaps = audioCaps,
            displayHdr = effectiveDisplayHdr,
            preferredAudioLanguage = state.preferredAudioLanguage,
            preferredTextLanguage = state.preferredTextLanguage,
            hdrEnabled = hdrEnabled,
        )
    }

    // HDR display-mode switching: attach the controller to the activity window
    // so we can drive `preferredDisplayModeId` when video size / frame rate
    // becomes known. Released on composition dispose.
    DisposableEffect(context) {
        (context as? Activity)?.let { hdrDisplayController.attach(it) }
        onDispose { hdrDisplayController.restore() }
    }

    DisposableEffect(context) {
        val window = (context as? Activity)?.window
        if (window != null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            if (window != null) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

    // Lifecycle pausing — send pause to the service when we're backgrounded.
    DisposableEffect(lifecycleOwner, mediaController, isInPictureInPictureMode) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> if (!isInPictureInPictureMode) mediaController?.pause()
                Lifecycle.Event.ON_STOP -> if (!isInPictureInPictureMode) mediaController?.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Preflight listener — falls back to a transcoded stream if the selected
    // Tracks can't be direct-played (DV P7, TrueHD without passthrough, …).
    DisposableEffect(mediaController) {
        val controller = mediaController
        if (controller == null) {
            onDispose { }
        } else {
            val preflight = PlaybackPreflightListener(
                detector = capabilityDetector,
                onUnsupported = { verdict -> viewModel.onUnsupportedPlayback(verdict) },
                onError = { error -> viewModel.onPlayerError(error) },
            )
            controller.addListener(preflight)
            onDispose { controller.removeListener(preflight) }
        }
    }

    // Player listener → ViewModel. Pushes play/pause state, refreshes the
    // track menu state on track changes, and drives HDMI display-mode
    // switching on video size changes.
    DisposableEffect(mediaController) {
        val controller = mediaController
        if (controller == null) {
            onDispose { }
        } else {
            val listener = object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    viewModel.onPlayingChanged(isPlaying)
                }
                override fun onRenderedFirstFrame() {
                    viewModel.onFirstVideoFrameRendered()
                }
                override fun onPlaybackStateChanged(playbackState: Int) {
                    // Buffering during normal playback flips the centered
                    // spinner. This complements the lifecycle's Reconnecting
                    // state which the player can't observe (server-outage
                    // probe loop runs out-of-band).
                    viewModel.onBufferingChanged(playbackState == Player.STATE_BUFFERING)
                    // F2 fallback: if the stream ends without a credits marker
                    // having fired the trigger, auto-advance / prompt now.
                    // A deliberate exit also stops the controller, which can
                    // report STATE_ENDED before navigation completes; ignore
                    // that teardown signal so the Up-Next surface does not
                    // briefly flash over the leaving player.
                    if (playbackState == Player.STATE_ENDED && !exitRequested) {
                        viewModel.onApproachingEnd(videoEnded = true)
                    }
                }
                override fun onTracksChanged(tracks: Tracks) {
                    val audio = extractTrackEntries(tracks, C.TRACK_TYPE_AUDIO)
                    val subtitle = extractTrackEntries(tracks, C.TRACK_TYPE_TEXT)
                    val video = extractTrackEntries(tracks, C.TRACK_TYPE_VIDEO)
                    // Quality is a server-transcode ladder built by the VM at
                    // session load (tvOS parity), not the adaptive variants.
                    viewModel.onTracksChanged(audio, subtitle, video)
                }
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    // MediaController doesn't expose ExoPlayer's `videoFormat`
                    // accessor, so read the frame rate off the currently
                    // selected video track in `currentTracks`. That's the
                    // same signal — `Format.frameRate` flows through both.
                    val frameRate = controller.currentTracks.groups
                        .firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
                        ?.let { g ->
                            val mg = g.mediaTrackGroup
                            if (mg.length > 0) mg.getFormat(0).frameRate else 0f
                        } ?: 0f
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        pictureInPictureVideoWidth = videoSize.width
                        pictureInPictureVideoHeight = videoSize.height
                        // Gated like Apple TV's "Match Content" (default off):
                        // the HDMI mode switch black-screens briefly, so it is
                        // the viewer's choice (QA 2026-07-08).
                        if (viewModel.matchContentFrameRate.value) {
                            hdrDisplayController.applyForMedia(
                                videoWidth = videoSize.width,
                                videoHeight = videoSize.height,
                                frameRateHz = frameRate,
                            )
                        }
                    }
                }
            }
            controller.addListener(listener)
            onDispose { controller.removeListener(listener) }
        }
    }

    // Position polling — lifecycle-bounded so it doesn't outlive the screen.
    LaunchedEffect(mediaController, state.sessionId, lifecycleOwner) {
        val controller = mediaController ?: return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive && state.sessionId != null) {
                viewModel.onPositionChanged(
                    controller.currentPosition,
                    controller.duration.coerceAtLeast(0L),
                )
                delay(500)
            }
        }
    }

    LaunchedEffect(mediaController, state.sessionId, state.streamUrl, state.playMethod) {
        val controller = mediaController ?: return@LaunchedEffect
        val sessionId = state.sessionId ?: return@LaunchedEffect
        val streamUrl = state.streamUrl ?: return@LaunchedEffect
        val sessionKey = "$sessionId:$streamUrl"
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                val reason = startupStallDetector.sample(
                    sessionKey = sessionKey,
                    nowMs = SystemClock.elapsedRealtime(),
                    playWhenReady = controller.playWhenReady,
                    isPlaying = controller.isPlaying,
                    isBuffering = controller.playbackState == Player.STATE_BUFFERING,
                    currentPositionMs = controller.currentPosition.coerceAtLeast(0L),
                    bufferedPositionMs = controller.bufferedPosition.coerceAtLeast(0L),
                )
                if (reason != null) {
                    Log.i(TAG, "Startup stall fallback: $reason")
                    viewModel.onUnsupportedPlayback(reason)
                    return@repeatOnLifecycle
                }
                delay(1_000)
            }
        }
    }

    // Prepare the player when a stream URL becomes available.
    LaunchedEffect(
        videoBackend,
        state.sessionId,
        state.streamUrl,
        state.playMethod,
        state.playbackPlan,
        state.delivery,
        state.startPosition,
    ) {
        if (exitRequested) return@LaunchedEffect
        val backend = videoBackend ?: return@LaunchedEffect
        val url = state.streamUrl ?: return@LaunchedEffect
        val method = state.playMethod ?: return@LaunchedEffect
        val plan = state.playbackPlan
        val delivery = plan?.delivery ?: state.delivery
        val mediaSpec = VideoPlayerMediaSpec(
            streamUrl = url,
            playMethod = method,
            delivery = delivery,
            serverUrl = state.serverUrl,
            container = state.container,
            subtitles = state.subtitleUrls,
            title = state.title.ifBlank { null },
            artworkUrl = state.artworkUrl,
            startPositionSeconds = state.startPosition,
            durationSeconds = state.duration,
            audioPassthroughCodecs = plan.validatedPassthroughCodecs(),
        )
        state.sessionId?.let { sessionId ->
            startupStallDetector.onMounted(
                sessionKey = "$sessionId:$url",
                playMethod = method,
                startPositionMs = mediaSpec.startPositionMs,
                nowMs = SystemClock.elapsedRealtime(),
            )
        }
        // Tell the playback service which engine to own for this media (Track A
        // Task 0). ASS/SSA subtitles route to MPV (libass fidelity); the service
        // resolves device-floor + route intent and rebinds + transfers state.
        val controller = mediaController ?: return@LaunchedEffect
        val engineRequest = VideoPlaybackBackendRequest(
            contentId = contentId,
            fileId = preferredFileId,
            playMethod = method,
            delivery = delivery,
            plannedEngine = plan?.engine,
            routeFamily = plan?.routeFamily,
            formFactor = VideoPlaybackFormFactor.Tv,
            hasHardContainer = method == org.siloserver.silo.model.playback.PlayMethod.DIRECT &&
                isHardPlaybackContainer(state.container),
            hasStyledSubtitles = state.subtitleUrls.any { it.isStyledSubtitle() },
            hasSoftwareOnlyVideoCodec = state.softwareOnlyVideoCodec,
            hasDolbyVisionVideo = hasDolbyVisionSource(plan?.source),
            isAdaptiveHlsStream = isLikelyAdaptiveHlsStreamUrl(url),
        )
        // Bound the await: a wedged engine build would otherwise suspend here
        // forever (never firing onEngineSwitchFailed), leaving a black screen.
        // On timeout, awaitEngineSwitch's invokeOnCancellation cancels the
        // pending SET_ENGINE future so nothing leaks; treat it as a failure so
        // the recovery ladder runs.
        val switchResult = try {
            withTimeout(ENGINE_SWITCH_TIMEOUT_MS) {
                controller.awaitEngineSwitch(engineRequest)
            }
        } catch (_: TimeoutCancellationException) {
            EngineSwitchResult(success = false, swapped = false)
        }
        if (!switchResult.success) {
            viewModel.onEngineSwitchFailed("Playback engine could not be prepared for this route.")
            return@LaunchedEffect
        }
        if (switchResult.swapped) {
            return@LaunchedEffect
        }
        backend.mount(mediaSpec)
    }

    // Subtitle refresh (search download / AI completion): Media3 cannot add
    // SubtitleConfigurations to a live item, so rebuild the SAME MediaItem —
    // identical stream URL + playback session — with the merged sidecar list
    // and resume at the captured position. Keyed on the refresh nonce so the
    // initial prepare effect above remains the only session-start path.
    LaunchedEffect(videoBackend, state.subtitleRefreshNonce) {
        if (exitRequested) return@LaunchedEffect
        if (state.subtitleRefreshNonce == 0) return@LaunchedEffect
        val backend = videoBackend ?: return@LaunchedEffect
        val url = state.streamUrl ?: return@LaunchedEffect
        val method = state.playMethod ?: return@LaunchedEffect
        val plan = state.playbackPlan
        val delivery = plan?.delivery ?: state.delivery
        val mediaSpec = VideoPlayerMediaSpec(
            streamUrl = url,
            playMethod = method,
            delivery = delivery,
            serverUrl = state.serverUrl,
            container = state.container,
            subtitles = state.subtitleUrls,
            title = state.title.ifBlank { null },
            artworkUrl = state.artworkUrl,
            startPositionSeconds = state.startPosition,
            durationSeconds = state.duration,
            audioPassthroughCodecs = plan.validatedPassthroughCodecs(),
        )
        val controller = mediaController ?: return@LaunchedEffect
        val engineRequest = VideoPlaybackBackendRequest(
            contentId = contentId,
            fileId = preferredFileId,
            playMethod = method,
            delivery = delivery,
            plannedEngine = plan?.engine,
            routeFamily = plan?.routeFamily,
            formFactor = VideoPlaybackFormFactor.Tv,
            hasHardContainer = method == org.siloserver.silo.model.playback.PlayMethod.DIRECT &&
                isHardPlaybackContainer(state.container),
            hasStyledSubtitles = state.subtitleUrls.any { it.isStyledSubtitle() },
            hasSoftwareOnlyVideoCodec = state.softwareOnlyVideoCodec,
            hasDolbyVisionVideo = hasDolbyVisionSource(plan?.source),
            isAdaptiveHlsStream = isLikelyAdaptiveHlsStreamUrl(url),
        )
        // Same bounded await as the initial prepare effect: a wedged engine
        // build must not strand a black screen. On timeout the pending future is
        // cancelled (no leak) and we fall through to the failure path.
        val switchResult = try {
            withTimeout(ENGINE_SWITCH_TIMEOUT_MS) {
                controller.awaitEngineSwitch(engineRequest)
            }
        } catch (_: TimeoutCancellationException) {
            EngineSwitchResult(success = false, swapped = false)
        }
        if (!switchResult.success) {
            viewModel.onEngineSwitchFailed("Playback engine could not be prepared for this route.")
            return@LaunchedEffect
        }
        if (switchResult.swapped) {
            return@LaunchedEffect
        }
        backend.refresh(mediaSpec)
    }

    // Auto-select a freshly downloaded/translated subtitle track once the
    // rebuilt item's tracks land (the VM matches by label in onTracksChanged
    // and emits the ordinal text-group index). Mirrors the seekRequests idiom.
    LaunchedEffect(videoBackend) {
        val backend = videoBackend ?: return@LaunchedEffect
        viewModel.subtitleSelectRequests.collect { idx ->
            if (idx == -1) {
                if (backend.selectSubtitle(null)) viewModel.onSubtitleSelectionApplied(idx)
                return@collect
            }
            val selectedTrack = viewModel.uiState.value.subtitleTracks
                .firstOrNull { it.index == idx }
                ?.toVideoTrackEntry()
            if (selectedTrack != null && backend.selectSubtitle(selectedTrack)) {
                viewModel.onSubtitleSelectionApplied(idx)
            }
        }
    }

    // Remote set_audio_track / set_subtitle_track. These latch in the VM (a
    // remote command can arrive before the backend attaches OR before Media3
    // reports its tracks via onTracksChanged), so we combine the latched index
    // with the live track list: while tracks are empty the latch is held; once
    // they're reported the index applies if it matches, or is dropped if not.
    // A non-empty audioTracks is the "tracks have loaded" signal (Media3 reports
    // all groups together).
    LaunchedEffect(videoBackend) {
        val backend = videoBackend ?: return@LaunchedEffect
        combine(
            viewModel.pendingRemoteAudioIndex,
            viewModel.uiState.map { it.audioTracks }.distinctUntilChanged(),
        ) { idx, tracks -> idx to tracks }.collect { (idx, tracks) ->
            if (idx == null) return@collect
            if (tracks.isEmpty()) return@collect // not reported yet — keep latched
            tracks.firstOrNull { it.index == idx }
                ?.toVideoTrackEntry()
                ?.let { backend.selectAudioTrack(it) }
            viewModel.clearPendingRemoteAudio(idx) // applied or no-match → consume
        }
    }
    LaunchedEffect(videoBackend) {
        val backend = videoBackend ?: return@LaunchedEffect
        combine(
            viewModel.pendingRemoteSubtitleIndex,
            viewModel.uiState.map { it.audioTracks.isNotEmpty() }.distinctUntilChanged(),
        ) { idx, tracksReady -> idx to tracksReady }.collect { (idx, tracksReady) ->
            if (idx == null) return@collect
            if (idx == -1) {
                // Disable doesn't depend on the track list — apply immediately.
                backend.selectSubtitle(null)
                viewModel.onSubtitleSelectionApplied(-1)
                viewModel.clearPendingRemoteSubtitle(idx)
                return@collect
            }
            if (!tracksReady) return@collect // wait for tracks to be reported
            viewModel.uiState.value.subtitleTracks
                .firstOrNull { it.index == idx }
                ?.toVideoTrackEntry()
                ?.let { if (backend.selectSubtitle(it)) viewModel.onSubtitleSelectionApplied(idx) }
            viewModel.clearPendingRemoteSubtitle(idx) // applied or no-match → consume
        }
    }

    // Mirror user-intent pause state into the player. Kept separate from the
    // onPlayingChanged listener so a transient buffering stall can't flip the
    // pause icon or cancel the auto-hide timer.
    LaunchedEffect(mediaController, state.isPaused) {
        mediaController?.playWhenReady = !state.isPaused
    }

    LaunchedEffect(mediaController) {
        val controller = mediaController ?: return@LaunchedEffect
        viewModel.seekRequests.collect { targetSec ->
            controller.seekTo((targetSec * 1000).toLong())
        }
    }

    // Apply per-profile playback speed to the MediaController. Uses
    // PlaybackParameters because MediaController doesn't expose a direct
    // setPlaybackSpeed setter that respects pitch correction defaults.
    LaunchedEffect(mediaController, playbackSpeed) {
        val controller = mediaController ?: return@LaunchedEffect
        controller.playbackParameters = PlaybackParameters(playbackSpeed.toFloat())
    }

    // Apply user subtitle styling whenever the PlayerView mounts or the
    // appearance flow emits a new value. Mirrors the phone PlayerScreen.
    // Also keyed on the selected subtitle track: MPV decides native-ASS vs
    // forced styling from the ACTIVE track's codec, so an ASS<->SRT switch
    // must re-evaluate (else SRT keeps ass-override=no, or force clobbers
    // authored ASS).
    LaunchedEffect(
        playerViewRef,
        subtitleAppearance,
        sessionPlayer,
        state.videoFillMode,
        state.subtitleTracks.firstOrNull { it.isSelected }?.index,
    ) {
        // MPV renders subtitles itself (libass on its own surface) — push the
        // same appearance into it; authored ASS stays intact.
        (sessionPlayer as? org.siloserver.silo.common.player.mpv.MpvSubtitleStyleController)
            ?.applySubtitleAppearance(subtitleAppearance)
        val pv = playerViewRef ?: return@LaunchedEffect
        subtitleManager.applyAppearance(pv, subtitleAppearance)
    }

    LaunchedEffect(sessionPlayer, state.videoFillMode) {
        applyMpvVideoScaleMode(sessionPlayer, state.videoFillMode)
    }

    LaunchedEffect(
        context,
        mediaController,
        state.streamUrl,
        state.isLoading,
        state.error,
        state.isPlaying,
        state.isPaused,
        pictureInPictureVideoWidth,
        pictureInPictureVideoHeight,
        pictureInPictureSourceRect,
    ) {
        pictureInPictureCoordinator.updatePlaybackState(
            activity = context as? Activity,
            surface = SiloPictureInPictureSurface.Tv,
            state = SiloPictureInPicturePlaybackState(
                enabled = false,
                videoActive = state.streamUrl != null && !state.isLoading && state.error == null,
                isPlaying = state.isPlaying && !state.isPaused,
                videoWidth = pictureInPictureVideoWidth,
                videoHeight = pictureInPictureVideoHeight,
                sourceRectHint = pictureInPictureSourceRect,
            ),
        )
    }

    DisposableEffect(context) {
        onDispose {
            pictureInPictureCoordinator.clearPlaybackState(context as? Activity, SiloPictureInPictureSurface.Tv)
        }
    }

    // Ensure the outer Box owns focus when the overlay is hidden so the first
    // remote key press can reach onPreviewKeyEvent.
    LaunchedEffect(state.showControls) {
        if (!state.showControls) {
            runCatching { rootFocus.requestFocus() }
        }
    }
    // Auto-hide the Compose overlay after CONTROLS_AUTO_HIDE_MS.
    LaunchedEffect(
        state.showControls,
        state.controlsVisibilityNonce,
        state.isPaused,
        state.hudOpen,
        state.showSubtitleMenu,
        state.showSubtitleStyleDialog,
        state.isScrubbing,
    ) {
        // Never auto-hide mid-scrub: hiding the scrubber would tear down the
        // in-flight preview under the user. The timer re-arms once the scrub
        // commits or cancels (isScrubbing flips back to false).
        if (state.showControls && !state.isPaused && !state.hudOpen &&
            !state.showSubtitleMenu && !state.showSubtitleStyleDialog &&
            !state.isScrubbing
        ) {
            delay(CONTROLS_AUTO_HIDE_MS)
            viewModel.setControlsVisible(false)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                // Up Next is a focus-owning child of this Box; previewing its
                // Left/Right here would seek underneath the overlay's buttons.
                if (!state.showControls && !state.showNextUp) {
                    when (
                        tvPlayerRemoteKeyAction(
                            keyCode = event.nativeKeyEvent.keyCode,
                            action = event.nativeKeyEvent.action,
                            repeatCount = event.nativeKeyEvent.repeatCount,
                        )
                    ) {
                        TvPlayerRemoteKeyAction.SkipBack ->
                            return@onPreviewKeyEvent performRelativeSeek(
                                -SKIP_BACK_MS,
                                roomSnapshot,
                                revealControls = false,
                            )
                        TvPlayerRemoteKeyAction.SkipForward ->
                            return@onPreviewKeyEvent performRelativeSeek(
                                SKIP_FORWARD_MS,
                                roomSnapshot,
                                revealControls = false,
                            )
                        TvPlayerRemoteKeyAction.ConsumeOnly -> return@onPreviewKeyEvent true
                        else -> Unit
                    }
                }
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN &&
                    event.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_BACK &&
                    event.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_DPAD_LEFT &&
                    event.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_DPAD_RIGHT &&
                    !state.showControls
                ) {
                    viewModel.setControlsVisible(true)
                }
                false
            },
    ) {
        when {
            state.isLoading -> TvLoadingScreen()
            state.error != null -> TvErrorScreen(
                message = state.error!!,
                onRetry = { viewModel.retry() },
            )
            state.streamUrl != null -> {
                val controller = mediaController
                if (controller != null) {
                    AndroidView(
                        modifier = Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { coordinates ->
                                val bounds = coordinates.boundsInWindow()
                                val next = Rect(
                                    bounds.left.roundToInt(),
                                    bounds.top.roundToInt(),
                                    bounds.right.roundToInt(),
                                    bounds.bottom.roundToInt(),
                                )
                                if (pictureInPictureSourceRect != next) {
                                    pictureInPictureSourceRect = next
                                }
                            },
                        factory = { ctx ->
                            val parent = FrameLayout(ctx)
                            (LayoutInflater.from(ctx).inflate(
                                R.layout.tv_player_view,
                                parent,
                                false,
                            ) as PlayerView).apply {
                                useController = false
                                isFocusable = false
                                isFocusableInTouchMode = false
                                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                                // Capture the inflated view so the subtitle
                                // appearance LaunchedEffect can target it.
                                playerViewRef = this
                            }
                        },
                        update = { view ->
                            // Bind the surface to the real session player (re-binds
                            // when sessionPlayer changes on engine swap); transport
                            // still flows through the MediaController.
                            view.player = sessionPlayer
                            applyPlayerViewVideoFillMode(view, state.videoFillMode)
                            applyMpvVideoScaleMode(sessionPlayer, state.videoFillMode)
                            subtitleManager.syncSubtitleVideoBounds(view)
                        },
                    )
                }

                if (!isInPictureInPictureMode && state.showControls && !state.hudOpen && !state.showNextUp) {
                    // In a room, transport authority gates what the local
                    // member may drive: a guest who can't seek gets a disabled
                    // scrubber + skip; play/pause only under guest_play_pause.
                    val canSeekInRoom = roomController == null ||
                        tvRoomTransportGate(roomSnapshot, TvTransportIntent.Seek) == TransportGate.Send
                    val canPlayPauseInRoom = roomController == null ||
                        tvRoomTransportGate(roomSnapshot, TvTransportIntent.PlayPause) == TransportGate.Send
                    val bufferedAheadSec = (
                        (mediaController?.bufferedPosition ?: 0L) -
                            (mediaController?.currentPosition ?: 0L)
                    ).coerceAtLeast(0L) / 1000.0
                    TvPlayerIdleOverlay(
                        title = state.title,
                        episodeTag = state.seasonNumber?.let { season ->
                            state.episodeNumber?.let { ep -> "S$season·E$ep" }
                        },
                        positionSec = state.position,
                        durationSec = state.duration,
                        isPaused = state.isPaused,
                        isScrubbing = state.isScrubbing,
                        scrubPreviewSec = state.scrubPreviewSec,
                        bufferedAheadSec = bufferedAheadSec,
                        chapters = state.chapters,
                        introRange = state.intro,
                        isBuffering = state.isBuffering,
                        sleepTimerState = sleepTimerState,
                        // In a room, skip/scrub/seek are routed through the
                        // controller (transport_request → server → broadcast
                        // command → engine applies the seek locally). Solo
                        // playback seeks the MediaController directly.
                        transportEnabled = canSeekInRoom,
                        playPauseEnabled = canPlayPauseInRoom,
                        onSkipBack = {
                            if (canSeekInRoom) {
                                performRelativeSeek(
                                    -SKIP_BACK_MS,
                                    roomSnapshot,
                                    revealControls = true,
                                )
                            }
                        },
                        onSkipForward = {
                            if (canSeekInRoom) {
                                performRelativeSeek(
                                    SKIP_FORWARD_MS,
                                    roomSnapshot,
                                    revealControls = true,
                                )
                            }
                        },
                        onBeginScrub = { viewModel.beginScrub() },
                        onUpdateScrub = { sec -> viewModel.updateScrubPreview(sec) },
                        onCommitScrub = {
                            val targetSec = viewModel.commitScrub()
                            if (!canSeekInRoom) return@TvPlayerIdleOverlay
                            if (roomController != null) {
                                roomController.onUserSeek(targetSec)
                            } else {
                                // seekImmediate pre-writes position so a scrub
                                // committed into the credits region isn't mistaken
                                // for natural playback crossing the credits point.
                                viewModel.seekImmediate(targetSec)
                            }
                            viewModel.setControlsVisible(true)
                        },
                        onCancelScrub = { viewModel.cancelScrub() },
                        transportFocusRequest = transportFocusRequest,
                        onPlayPause = {
                            if (!canPlayPauseInRoom) return@TvPlayerIdleOverlay
                            if (roomController != null) {
                                roomController.onUserPlayPause()
                            } else {
                                viewModel.onPlayPause()
                            }
                            viewModel.setControlsVisible(true)
                        },
                        onOpenHUD = {
                            requestedHudTab = HudTab.Info
                            viewModel.openHUD()
                        },
                        onOpenQuickSubtitles = {
                            showQuickSubtitlePicker = true
                            viewModel.setControlsVisible(true)
                        },
                        onClose = {
                            when {
                                roomController != null && roomSnapshot?.isHost == true ->
                                    showLeaveDialog = true
                                roomController != null -> {
                                    roomController.leave(closeRoom = false)
                                    stopPlaybackAndExit()
                                }
                                else -> stopPlaybackAndExit()
                            }
                        },
                    )
                }

                if (!isInPictureInPictureMode && state.hudOpen) {
                    // Floating top-center card — no full-screen scrim so video
                    // stays visible behind it. Mirrors tvOS TVPlayerInfoHUD.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 56.dp),
                        contentAlignment = androidx.compose.ui.Alignment.TopCenter,
                    ) {
                        TvPlayerHud(
                            title = state.title,
                            positionSec = state.position,
                            durationSec = state.duration,
                            seasonNumber = state.seasonNumber,
                            episodeNumber = state.episodeNumber,
                            audioTracks = state.audioTracks,
                            videoQualities = state.videoQualities,
                            fileVersions = state.fileVersions,
                            selectedFileId = state.selectedFileId ?: state.mediaFileId,
                            onSelectFileVersion = viewModel::onSelectFileVersion,
                            subtitleTracks = state.subtitleTracks,
                            stats = state.stats,
                            playbackPlan = state.playbackPlan,
                            videoFillMode = state.videoFillMode,
                            onSelectAudio = { idx ->
                                val selectedTrack = state.audioTracks
                                    .firstOrNull { it.index == idx }
                                    ?.toVideoTrackEntry()
                                if (selectedTrack != null) {
                                    videoBackend?.selectAudioTrack(selectedTrack)
                                    viewModel.onAudioSelectionApplied(idx)
                                }
                            },
                            onSelectVideoQuality = { id ->
                                // Server-transcode quality ladder (tvOS parity):
                                // re-request the session at the chosen rung.
                                viewModel.switchQuality(id)
                            },
                            onSelectSubtitle = { idx -> applyTvSubtitleSelection(idx, false) },
                            onVideoFillModeChanged = viewModel::onVideoFillModeChanged,
                            playbackSpeed = playbackSpeed,
                            onPlaybackSpeedChanged = viewModel::onSetPlaybackSpeed,
                            sleepTimerState = sleepTimerState,
                            onStartSleepTimer = viewModel::onStartSleepTimer,
                            onCancelSleepTimer = viewModel::onCancelSleepTimer,
                            autoSkipIntro = autoSkipIntroEnabled,
                            onAutoSkipIntroChanged = viewModel::onSetAutoSkipIntro,
                            autoPlayNext = autoPlayNextEnabled,
                            onAutoPlayNextChanged = viewModel::onSetAutoPlayNext,
                            audioDelayMs = audioDelayMs,
                            onAudioDelayChanged = viewModel::onAudioDelayChanged,
                            subtitleDelayMs = subtitleDelayMs,
                            onSubtitleDelayChanged = viewModel::onSubtitleDelayChanged,
                            subtitleAppearance = subtitleAppearance,
                            onSubtitleAppearanceChanged = viewModel::onSetSubtitleAppearance,
                            onSubtitlesPaneShown = viewModel::onSubtitlesPaneShown,
                            onSearchSubtitles = if (state.mediaFileId != null) {
                                {
                                    viewModel.closeHUD()
                                    viewModel.openSubtitleSearchDialog()
                                }
                            } else {
                                null
                            },
                            onTranslateWithAi = if (
                                state.mediaFileId != null &&
                                (aiTranslate.status.enabled || aiTranslate.status.transcribeEnabled)
                            ) {
                                {
                                    viewModel.closeHUD()
                                    viewModel.openAiTranslateDialog()
                                }
                            } else {
                                null
                            },
                            hdrEnabled = hdrEnabled,
                            onHdrEnabledChanged = viewModel::onSetHdrEnabled,
                            dolbyVisionEnabled = dolbyVisionEnabled,
                            onDolbyVisionEnabledChanged = viewModel::onSetDolbyVisionEnabled,
                            chapters = state.chapters,
                            onSelectChapter = { idx ->
                                viewModel.onSeekToChapter(idx)?.let { sec ->
                                    if (roomController != null) {
                                        // In a room, route through the same gated
                                        // path as scrub-commit / performRelativeSeek:
                                        // transport authority decides (a guest is a
                                        // no-op) and a permitted seek broadcasts.
                                        if (tvRoomTransportGate(roomSnapshot, TvTransportIntent.Seek) == TransportGate.Send) {
                                            roomController.onUserSeek(sec)
                                        }
                                    } else {
                                        // Solo: seekImmediate pre-writes position so a
                                        // chapter jump into credits isn't mistaken for
                                        // natural playback crossing the credits point.
                                        viewModel.seekImmediate(sec)
                                    }
                                }
                            },
                            onDismiss = { viewModel.closeHUD() },
                            initialTab = requestedHudTab,
                            onPickerOpenChanged = { hudPickerOpen = it },
                        )
                    }
                }

                // Transient skip feedback for hidden-controls D-pad seeks.
                // Suppressed while the transport, HUD, or Up Next own the
                // screen (they provide their own position feedback) and in PiP.
                if (!isInPictureInPictureMode && !state.showControls &&
                    !state.hudOpen && !state.showNextUp
                ) {
                    // Align the transient line with the REAL scrubber track's
                    // position inside the idle overlay, which stacks (bottom-up):
                    // 40dp overlay padding + 33dp transport cluster + 16dp gap +
                    // 8dp spacer + 16dp gap = 113dp to the scrubber COLUMN's
                    // bottom — plus ~6dp because the 3.5dp track is centered in
                    // the column's lower box (41dp minus label row), not flush
                    // with its bottom. Horizontal 80dp matches the track width.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 80.dp, end = 80.dp, bottom = 119.dp),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        TvSkipSeekIndicator(feedback = skipSeekFeedback)
                    }
                }

                if (!isInPictureInPictureMode && showQuickSubtitlePicker) {
                    TvQuickSubtitlePicker(
                        tracks = state.subtitleTracks,
                        onSelect = { idx ->
                            applyTvSubtitleSelection(idx, false)
                            showQuickSubtitlePicker = false
                            viewModel.setControlsVisible(true)
                        },
                        onDismiss = { showQuickSubtitlePicker = false },
                    )
                }

                if (!isInPictureInPictureMode && state.showSubtitleSearchDialog) {
                    TvSubtitleSearchDialog(
                        state = subtitleSearch,
                        onLanguageChanged = viewModel::setSubtitleSearchLanguage,
                        onSearch = viewModel::searchSubtitles,
                        onDownload = viewModel::downloadSubtitle,
                        onDismiss = viewModel::closeSubtitleSearchDialog,
                    )
                }

                if (!isInPictureInPictureMode && state.showAiTranslateDialog) {
                    // Translate sources = the session's sidecar subtitle list,
                    // filtered with mobile/web parity (isTranslatableSource):
                    // embedded → any non-bitmap codec (ffmpeg-extractable);
                    // external/downloaded → only server-parseable text formats
                    // (external ASS is rejected by the server). source_index for
                    // the server is PlayerSubtitleInfo.index (the session's
                    // combined subtitle index).
                    val translatableSubtitleSources = remember(state.subtitleUrls) {
                        state.subtitleUrls.filter { isTranslatableSource(it) }
                    }
                    TvAiTranslateDialog(
                        aiState = aiTranslate,
                        subtitleSources = translatableSubtitleSources,
                        audioSources = state.audioTracks,
                        defaultTargetLanguage = state.preferredTextLanguage
                            ?.takeIf { it.isNotBlank() } ?: "en",
                        onSubmit = viewModel::submitAiTranslate,
                        onCancelJob = viewModel::cancelAiTranslateJob,
                        onClearError = viewModel::clearAiTranslateError,
                        onDismiss = viewModel::closeAiTranslateDialog,
                    )
                }
            }
        }

        // Lifecycle-driven notice toast (top-start). Slides in for outage
        // recovery, fades out when the lifecycle clears the notice.
        if (!isInPictureInPictureMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 32.dp, start = 32.dp),
                contentAlignment = Alignment.TopStart,
            ) {
                TvPlayerNoticeOverlay(notice = notice)
            }
        }

        // Remote-control "display_message" toast (top-center), shown a few
        // seconds regardless of controls visibility.
        if (!isInPictureInPictureMode) remoteMessage?.let { message ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 48.dp)
                    .zIndex(10f),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    modifier = Modifier
                        .background(
                            color = Color.Black.copy(alpha = 0.82f),
                            shape = RoundedCornerShape(12.dp),
                        )
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                ) {
                    Text(
                        text = message.text,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }

        // Watch Together room indicator (top-end so it doesn't collide with
        // the top-start lifecycle notice). Member count, a "Waiting for
        // members…" pill while the room is on the wait barrier, and the join
        // code for the host. Only shown while the idle overlay is up.
        val snapshot = roomSnapshot
        if (!isInPictureInPictureMode && roomController != null && snapshot != null && state.showControls && !state.hudOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 32.dp, end = 32.dp),
                contentAlignment = Alignment.TopEnd,
            ) {
                TvRoomIndicator(
                    memberCount = snapshot.memberCount,
                    waiting = snapshot.playbackState == RoomPlaybackState.Waiting,
                    joinCode = snapshot.code.takeIf { snapshot.selfCanManageRoom && it.isNotBlank() },
                )
            }
        }

        // Host close-confirm dialog. Closing tears the room down for everyone
        // (server emits room_closed → every member exits). Cancel resumes.
        if (!isInPictureInPictureMode && showLeaveDialog && roomController != null) {
            TvRoomCloseConfirmDialog(
                onClose = {
                    showLeaveDialog = false
                    roomController.leave(closeRoom = true)
                    stopPlaybackAndExit()
                },
                onCancel = { showLeaveDialog = false },
            )
        }

        // F2 / Up-Next end-of-playback surface. Replaces the old "Still
        // watching?" dialog: a 16:9 mini-player (the still-playing video,
        // visible behind a bordered frame) beside a next-episode panel with
        // Play Now / Keep Watching / Back and an auto-play countdown ring.
        if (!isInPictureInPictureMode) {
            if (state.showNextUp) {
                TvPlayerNextUpOverlay(
                    nextEpisode = state.nextEpisode,
                    videoEnded = state.nextUpVideoEnded,
                    countdownSeconds = state.nextUpCountdownSeconds,
                    countdownTotalSeconds = state.nextUpCountdownTotalSeconds,
                    autoPlayEnabled = autoPlayNextEnabled,
                    onPlayNow = viewModel::playNextEpisodeNow,
                    onKeepWatching = viewModel::dismissNextUp,
                    onToggleAutoPlay = { viewModel.onSetAutoPlayNext(!autoPlayNextEnabled) },
                    onBack = { stopPlaybackAndExit() },
                )
            }
        }

        // Intro auto-skip banner (bottom-end, above the transport cluster).
        // It must remain visible even when transport controls auto-hide; D-pad
        // Center routes directly to [handleSkipIntroNow] while the manual prompt
        // is active, so the viewer does not need a first click just to reveal UI.
        // Bottom inset (200dp) clears the transport cluster + scrubber column.
        if (!isInPictureInPictureMode) {
            if (!state.hudOpen && !state.showNextUp) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 200.dp, end = 32.dp),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    TvIntroAutoSkipBanner(
                        state = introSkipState,
                        onSkipNow = { handleSkipIntroNow() },
                        onCancelCountdown = viewModel::onCancelIntroAutoSkip,
                    )
                }
            }
        }

        // Outage spinner. Native ExoPlayer buffering now surfaces as the
        // top-right Buffering capsule inside the idle overlay's statusColumn
        // (mirroring tvOS), so the centered full-screen spinner is reserved for
        // the lifecycle Reconnecting state (the server-outage probe loop, which
        // the player itself can't observe) — and only when the idle overlay
        // isn't already showing the chip. The Up-Next overlay owns its own
        // loading state, so no spinner there either.
        val showSpinner = sessionState is SessionState.Reconnecting && !state.showNextUp
        if (showSpinner) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 4.dp,
                    modifier = Modifier.size(64.dp),
                )
            }
        }
    }
}

/**
 * Idle controls overlay — bottom-anchored gradient scrim with title + time +
 * progress bar above the transport cluster. Mirrors the tvOS idle overlay
 * pattern (spec §4.1) without yet wiring the full interactive scrubber, which
 * needs an ExoPlayer-backed scrub state machine that's a separate concern.
 */
@Composable
private fun TvPlayerIdleOverlay(
    title: String,
    episodeTag: String?,
    positionSec: Double,
    durationSec: Double,
    isPaused: Boolean,
    isScrubbing: Boolean,
    scrubPreviewSec: Double,
    bufferedAheadSec: Double,
    chapters: List<org.siloserver.silo.model.catalog.VersionChapter>,
    introRange: org.siloserver.silo.model.catalog.TimeRange?,
    isBuffering: Boolean,
    sleepTimerState: SleepTimerState,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onBeginScrub: () -> Unit,
    onUpdateScrub: (Double) -> Unit,
    onCommitScrub: () -> Unit,
    onCancelScrub: () -> Unit,
    transportFocusRequest: Int,
    onOpenHUD: () -> Unit,
    onOpenQuickSubtitles: () -> Unit,
    onClose: () -> Unit,
    // Watch Together transport authority. Solo playback leaves both true.
    // A guest who can't seek gets a no-op scrubber/skip; a guest who can't
    // play/pause (host_only policy) gets a no-op play/pause.
    transportEnabled: Boolean = true,
    playPauseEnabled: Boolean = true,
) {
    val scrubberFocus = remember { FocusRequester() }
    val playPauseFocus = remember { FocusRequester() }
    var currentRate by remember { mutableStateOf(0) }
    LaunchedEffect(transportFocusRequest) { runCatching { playPauseFocus.requestFocus() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                when (
                    tvPlayerIdleOverlayRemoteKeyAction(
                        keyCode = event.nativeKeyEvent.keyCode,
                        action = event.nativeKeyEvent.action,
                        repeatCount = event.nativeKeyEvent.repeatCount,
                    )
                ) {
                    TvPlayerRemoteKeyAction.PlayPause -> {
                        onPlayPause()
                        true
                    }
                    TvPlayerRemoteKeyAction.FocusTransport -> {
                        runCatching { playPauseFocus.requestFocus() }
                        true
                    }
                    TvPlayerRemoteKeyAction.SkipBack -> {
                        onSkipBack()
                        true
                    }
                    TvPlayerRemoteKeyAction.SkipForward -> {
                        onSkipForward()
                        true
                    }
                    TvPlayerRemoteKeyAction.OpenHud -> {
                        onOpenHUD()
                        true
                    }
                    TvPlayerRemoteKeyAction.ConsumeOnly -> true
                    null -> false
                }
            },
    ) {
        // Bottom gradient scrim — 240dp tall, ~0.55 black at the bottom edge,
        // fading to transparent so video content above stays visible.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        0.00f to Color.Transparent,
                        0.40f to Color.Black.copy(alpha = 0.30f),
                        1.00f to Color.Black.copy(alpha = 0.55f),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(horizontal = 80.dp, vertical = 40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Interactive scrubber — capsule track with chapter ticks, ±10s
            // skip, hold-to-auto-seek, and Select to commit. tvOS spec §4.1.
            TvPlayerScrubber(
                positionSec = positionSec,
                durationSec = durationSec,
                bufferedAheadSec = bufferedAheadSec,
                isScrubbing = isScrubbing,
                scrubPreviewSec = scrubPreviewSec,
                chapters = chapters.map {
                    ChapterInfo(
                        timeSec = it.startSeconds,
                        title = it.title.ifBlank { null },
                    )
                },
                introRangeSec = introRange
                    ?.takeIf { it.end > it.start }
                    ?.let { it.start..it.end },
                cancelOnBlur = false,
                onSkipBack = onSkipBack,
                onSkipForward = onSkipForward,
                onBeginScrub = onBeginScrub,
                onUpdateScrub = onUpdateScrub,
                onCommitScrub = onCommitScrub,
                onCancelScrub = onCancelScrub,
                onRequestFocus = scrubberFocus,
                onMoveDownToTransport = {
                    runCatching { playPauseFocus.requestFocus() }
                },
                onExitWhenIdle = onClose,
                onRateChanged = { currentRate = it },
            )

            Spacer(modifier = Modifier.height(8.dp))

            TvPlayerTransportCluster(
                isPlaying = !isPaused,
                onSkipBack = onSkipBack,
                onPlayPause = onPlayPause,
                onSkipForward = onSkipForward,
                onOpenQuickSubtitles = onOpenQuickSubtitles,
                onOpenHUD = onOpenHUD,
                onClose = onClose,
                playPauseFocus = playPauseFocus,
                onMoveUpToScrubber = {
                    runCatching { scrubberFocus.requestFocus() }
                },
            )
        }

        // Top-right status chips — buffering capsule (spinner + "Buffering")
        // and a sleep-timer countdown chip — mirroring tvOS statusColumn.
        // Replaces the full-screen buffering spinner during playback.
        val sleepRemaining = (sleepTimerState as? SleepTimerState.Active)?.remainingSeconds
        if (isBuffering || sleepRemaining != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 64.dp, end = 80.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (isBuffering) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(percent = 50))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                        )
                        androidx.tv.material3.Text(
                            text = "Buffering",
                            color = Color.White.copy(alpha = 0.85f),
                            style = androidx.tv.material3.MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                if (sleepRemaining != null) {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(percent = 50))
                            .background(Color.Black.copy(alpha = 0.55f))
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.tv.material3.Icon(
                            imageVector = Icons.Filled.Bedtime,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(16.dp),
                        )
                        androidx.tv.material3.Text(
                            text = formatSleepCountdown(sleepRemaining),
                            color = Color.White.copy(alpha = 0.85f),
                            style = androidx.tv.material3.MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
        }

        // Quiet bottom-left title footer above the scrubber column (tvOS
        // titleFooter idiom) — series / title / episode tag, shadowed, no box,
        // no "Playing" literal. Sits above the transport stack's top padding.
        if (title.isNotBlank()) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 80.dp, bottom = 196.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.tv.material3.Text(
                        text = title,
                        color = Color.White,
                        style = androidx.tv.material3.MaterialTheme.typography.titleMedium.copy(
                            shadow = androidx.compose.ui.graphics.Shadow(
                                color = Color.Black.copy(alpha = 0.55f),
                                offset = androidx.compose.ui.geometry.Offset(0f, 1f),
                                blurRadius = 6f,
                            ),
                        ),
                    )
                    if (episodeTag != null) {
                        androidx.tv.material3.Text(
                            text = episodeTag,
                            color = Color.White.copy(alpha = 0.62f),
                            style = androidx.tv.material3.MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 80.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            TvHoldSeekIndicator(
                isVisible = currentRate != 0,
                rate = currentRate,
                previewTimeSec = scrubPreviewSec,
                durationSec = durationSec,
            )
        }
    }
}

private fun formatSleepCountdown(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    val m = s / 60
    val sec = s % 60
    return if (m > 0) "${m}m ${sec}s" else "${sec}s"
}

@Composable
private fun TvQuickSubtitlePicker(
    tracks: List<PlayerTrackEntry>,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val selectedTrack = tracks.firstOrNull { it.isSelected }
    val options = buildList {
        add(HudPickerOption(id = "-1", label = "Off"))
        tracks.forEachIndexed { idx, track ->
            add(
                HudPickerOption(
                    id = track.index.toString(),
                    label = track.displayLabel.ifBlank { "Track ${idx + 1}" },
                ),
            )
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.42f)),
            contentAlignment = Alignment.Center,
        ) {
            HudPickerDialog(
                presentation = HudPickerPresentation(
                    title = "Subtitles",
                    options = options,
                    selectedId = (selectedTrack?.index ?: -1).toString(),
                    onSelect = { id -> onSelect(id.toIntOrNull() ?: -1) },
                ),
                onClose = onDismiss,
            )
        }
    }
}

/**
 * Top-end Watch Together status pill. Shows the live member count, a "Waiting
 * for members…" line while the room sits on the wait barrier, and the join
 * code for a member who can manage the room (host).
 */
@Composable
private fun TvRoomIndicator(
    memberCount: Int,
    waiting: Boolean,
    joinCode: String?,
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        androidx.tv.material3.Text(
            text = "Watch Together · $memberCount in room",
            color = Color.White,
            style = androidx.tv.material3.MaterialTheme.typography.labelLarge,
        )
        if (joinCode != null) {
            androidx.tv.material3.Text(
                text = "Code $joinCode",
                color = Color.White.copy(alpha = 0.80f),
                style = androidx.tv.material3.MaterialTheme.typography.labelMedium,
            )
        }
        if (waiting) {
            androidx.tv.material3.Text(
                text = "Waiting for members…",
                color = Color.White.copy(alpha = 0.80f),
                style = androidx.tv.material3.MaterialTheme.typography.labelMedium,
            )
        }
    }
}

/**
 * End-of-playback Up-Next overlay (mirrors tvOS `PlayerNextUpScreen`). A 16:9
 * mini-player pane on the left — the still-playing video shows through a
 * lighter scrim in that region, framed with a rounded border — beside a
 * next-episode panel on the right: an "Up Next" / "Playing Next" eyebrow,
 * series-context-free episode metadata ("S·E · title" + overview), a Play Now
 * primary button, a Keep Watching dismiss button, a Back button, an auto-play
 * countdown ring (counts to zero then plays the next episode), and finished /
 * loading states when no next episode is available.
 *
 * Replaces the old "Still watching?" dialog as the sole end-of-playback
 * surface; the pass-out gate now manifests as the overlay appearing WITHOUT a
 * countdown ring (the user must explicitly choose).
 */
@Composable
private fun TvPlayerNextUpOverlay(
    nextEpisode: NextEpisodeState?,
    videoEnded: Boolean,
    countdownSeconds: Int?,
    countdownTotalSeconds: Int,
    autoPlayEnabled: Boolean,
    onPlayNow: () -> Unit,
    onKeepWatching: () -> Unit,
    onToggleAutoPlay: () -> Unit,
    onBack: () -> Unit,
) {
    val primaryFocus = remember { FocusRequester() }
    LaunchedEffect(nextEpisode?.contentId, videoEnded) {
        runCatching { primaryFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.horizontalGradient(
                    0.00f to Color.Black.copy(alpha = 0.30f),
                    0.42f to Color.Black.copy(alpha = 0.66f),
                    1.00f to Color.Black.copy(alpha = 0.92f),
                ),
            ),
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 80.dp),
            horizontalArrangement = Arrangement.spacedBy(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 16:9 mini-player frame. The live video plays behind the lighter
            // left edge of the scrim; this is just the bordered frame over it.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black.copy(alpha = 0.10f))
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.16f),
                        shape = RoundedCornerShape(8.dp),
                    ),
            )

            // Next-episode panel.
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                val eyebrow = when {
                    nextEpisode == null -> if (videoEnded) "Finished" else "More To Watch"
                    videoEnded -> "Playing Next"
                    else -> "Up Next"
                }
                androidx.tv.material3.Text(
                    text = eyebrow.uppercase(),
                    color = Color.White.copy(alpha = 0.52f),
                    style = androidx.tv.material3.MaterialTheme.typography.labelLarge,
                )

                if (nextEpisode != null) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            androidx.tv.material3.Text(
                                text = "S${nextEpisode.seasonNumber}·E${nextEpisode.episodeNumber}",
                                color = Color.White.copy(alpha = 0.62f),
                                style = androidx.tv.material3.MaterialTheme.typography.titleMedium,
                            )
                            androidx.tv.material3.Text(
                                text = nextEpisode.title ?: "Next Episode",
                                color = Color.White,
                                style = androidx.tv.material3.MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                            )
                        }
                        nextEpisode.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                            androidx.tv.material3.Text(
                                text = overview,
                                color = Color.White.copy(alpha = 0.58f),
                                style = androidx.tv.material3.MaterialTheme.typography.bodyMedium,
                                maxLines = 3,
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TvDialogActionRow(
                            title = "Play Now",
                            onClick = onPlayNow,
                            modifier = Modifier
                                .width(220.dp)
                                .focusRequester(primaryFocus),
                        )
                        if (countdownSeconds != null) {
                            TvCountdownRing(
                                seconds = countdownSeconds,
                                totalSeconds = countdownTotalSeconds,
                            )
                        }
                    }

                    if (!videoEnded) {
                        TvDialogActionRow(
                            title = "Keep Watching",
                            onClick = onKeepWatching,
                            modifier = Modifier.width(260.dp),
                        )
                    }
                    TvDialogActionRow(
                        title = "Back",
                        onClick = onBack,
                        modifier = Modifier.width(160.dp),
                    )
                    androidx.tv.material3.Text(
                        text = "Auto-play is ${if (autoPlayEnabled) "On" else "Off"}",
                        color = Color.White.copy(alpha = 0.54f),
                        style = androidx.tv.material3.MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .focusable()
                            .clip(RoundedCornerShape(percent = 50)),
                    )
                } else {
                    // Finished / no-next-episode state.
                    androidx.tv.material3.Text(
                        text = if (videoEnded) "End of playback" else "Almost finished",
                        color = Color.White,
                        style = androidx.tv.material3.MaterialTheme.typography.headlineSmall,
                    )
                    androidx.tv.material3.Text(
                        text = "No next episode is available.",
                        color = Color.White.copy(alpha = 0.62f),
                        style = androidx.tv.material3.MaterialTheme.typography.bodyMedium,
                    )
                    if (!videoEnded) {
                        TvDialogActionRow(
                            title = "Keep Watching",
                            onClick = onKeepWatching,
                            modifier = Modifier
                                .width(260.dp)
                                .focusRequester(primaryFocus),
                        )
                        TvDialogActionRow(
                            title = "Back",
                            onClick = onBack,
                            modifier = Modifier.width(160.dp),
                        )
                    } else {
                        TvDialogActionRow(
                            title = "Back",
                            onClick = onBack,
                            modifier = Modifier
                                .width(160.dp)
                                .focusRequester(primaryFocus),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Auto-play countdown ring (tvOS CountdownRing). A circular track with a white
 * progress arc draining as the countdown runs, the remaining seconds centered.
 */
@Composable
private fun TvCountdownRing(seconds: Int, totalSeconds: Int) {
    val progress = if (totalSeconds > 0) {
        (seconds.toFloat() / totalSeconds.toFloat()).coerceIn(0f, 1f)
    } else 0f
    Box(
        modifier = Modifier.size(58.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 3.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.12f),
                style = stroke,
            )
            drawArc(
                color = Color.White,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                style = stroke,
            )
        }
        androidx.tv.material3.Text(
            text = "$seconds",
            color = Color.White,
            style = androidx.tv.material3.MaterialTheme.typography.titleMedium,
        )
    }
}


@Composable
private fun TvRoomCloseConfirmDialog(
    onClose: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(Color.Black.copy(alpha = 0.92f))
                .padding(40.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            androidx.tv.material3.Text(
                text = "Close this room?",
                color = Color.White,
                style = androidx.tv.material3.MaterialTheme.typography.titleLarge,
            )
            androidx.tv.material3.Text(
                text = "Closing ends Watch Together for everyone in the room.",
                color = Color.White.copy(alpha = 0.80f),
                style = androidx.tv.material3.MaterialTheme.typography.bodyMedium,
            )
            TvDialogActionRow(
                title = "Close room for everyone",
                onClick = onClose,
                modifier = Modifier.width(360.dp),
            )
            TvDialogActionRow(
                title = "Keep watching",
                onClick = onCancel,
                modifier = Modifier.width(360.dp),
            )
        }
    }
}

private fun formatPlayerTime(seconds: Double): String {
    if (seconds <= 0 || seconds.isNaN()) return "0:00"
    val total = seconds.toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun PlayerTrackEntry.toVideoTrackEntry(): VideoPlayerTrackEntry =
    VideoPlayerTrackEntry(
        index = index,
        label = label,
        language = language,
        isSelected = isSelected,
    )

internal fun org.siloserver.silo.model.playback.PlayerSubtitleInfo.isStyledSubtitle(): Boolean {
    val normalizedCodec = codec
        ?.trim()
        ?.lowercase()
        ?.replace('_', '-')
    val normalizedUrl = url.substringBefore('?').substringBefore('#').lowercase()
    return normalizedCodec in setOf("ass", "ssa", "text/x-ssa") ||
        normalizedUrl.endsWith(".ass") ||
        normalizedUrl.endsWith(".ssa")
}

private fun PlaybackExecutionPlan?.validatedPassthroughCodecs(): List<String> {
    val plan = this ?: return emptyList()
    return plan.source.audioCodec
        ?.takeIf { plan.claims.audio.passthrough }
        ?.let { listOf(it) }
        .orEmpty()
}

internal fun isHardPlaybackContainer(container: String?): Boolean =
    isMpvPreferredOriginalPlaybackContainer(container)

/**
 * True when the planned source carries Dolby Vision. Feeds
 * [VideoPlaybackBackendRequest.hasDolbyVisionVideo] so Auto routes DV to
 * MPV — Media3 has no reliable TV-side DV handling and can play
 * audio+subtitles over a permanently black video surface. Plain
 * HDR10/HDR10+/HLG stays on Media3, which plays it correctly; MPV's
 * slurp-then-idle streaming makes it the worse default for those. Routes
 * through the shared [DolbyVisionDetection] predicate so the TV, phone, and
 * routing layers recognize the same signals (structured profile plus the
 * HDR-format tokens, including a bare `dv`) and never diverge from Apple.
 */
private fun hasDolbyVisionSource(source: PlaybackSourceMetadata?): Boolean =
    DolbyVisionDetection.isDolbyVision(
        dolbyVisionProfile = source?.dolbyVisionProfile,
        hdrFormat = source?.hdrFormat,
        videoCodec = source?.videoCodec,
    )

private const val TAG = "TvPlayerScreen"

/**
 * Flatten an ExoPlayer [Tracks] object into TV-facing entries. Audio/video
 * keep the legacy group-level mapping. Text tracks flatten every format inside
 * each Media3 group because sidecar subtitles can share one group; their
 * `index` is the flat text-track ordinal expected by [SubtitleManager].
 */
internal fun extractTrackEntries(tracks: Tracks, type: Int): List<PlayerTrackEntry> {
    val result = mutableListOf<PlayerTrackEntry>()
    var groupIndex = 0
    var flatTextIndex = 0
    for (group in tracks.groups) {
        if (group.type != type) continue
        val mediaGroup = group.mediaTrackGroup
        if (type == C.TRACK_TYPE_TEXT) {
            for (trackIndex in 0 until group.length) {
                val format = group.getTrackFormat(trackIndex)
                val media3FlatTextIndex = flatTextIndex
                flatTextIndex++
                val label = format.label.orEmpty().ifBlank { format.language?.uppercase() ?: "" }
                val codecOrMime = format.subtitleCodecOrMime()
                val forced = format.selectionFlags and C.SELECTION_FLAG_FORCED != 0
                val hearingImpaired = label.indicatesHearingImpairedSubtitle()
                result.add(
                    PlayerTrackEntry(
                        index = media3FlatTextIndex,
                        label = label,
                        language = format.language,
                        isSelected = group.isTrackSelected(trackIndex),
                        codecOrMime = codecOrMime,
                        isForced = forced,
                        isHearingImpaired = hearingImpaired,
                        displayLabel = formatSubtitleTrackDisplayLabel(
                            rawLabel = label,
                            language = format.language,
                            codecOrMime = codecOrMime,
                            isForced = forced,
                            index = flatTextIndex,
                        ),
                    ),
                )
            }
            continue
        }
        val format = if (mediaGroup.length > 0) mediaGroup.getFormat(0) else null
        val label = format?.label.orEmpty().ifBlank { format?.language?.uppercase() ?: "" }
        val language = format?.language
        val selected = group.isSelected
        result.add(
            PlayerTrackEntry(
                index = groupIndex,
                label = label,
                language = language,
                isSelected = selected,
                displayLabel = label,
            ),
        )
        groupIndex++
    }
    return result
}

private fun Format.subtitleCodecOrMime(): String? =
    if (sampleMimeType == "application/x-media3-cues") {
        codecs ?: sampleMimeType
    } else {
        sampleMimeType ?: codecs
    }

/**
 * A selectable video quality variant. Unlike [extractTrackEntries] (which
 * collapses every video group to a single group-level entry), this flattens the
 * individual formats *inside* the video group(s) — the real resolution / bitrate
 * variants of the stream — so the HUD Quality picker can surface genuine
 * options. [id] encodes `"<groupOrdinal>:<trackIndex>"`; the synthetic `"-1"`
 * id means Auto (adaptive — clears any override).
 */
data class VideoQualityOption(
    val id: String,
    val label: String,
    val isSelected: Boolean,
    val resolution: String? = null,
)

internal const val VIDEO_QUALITY_AUTO_ID = "-1"

/**
 * Flatten the current [Tracks] video group(s) into per-format quality options.
 * Each format becomes a resolution/bitrate-labelled option. "Auto" is prepended
 * and is selected whenever no single format override is active (adaptive).
 */
internal fun extractVideoQualityOptions(tracks: Tracks): List<VideoQualityOption> {
    val variants = mutableListOf<VideoQualityOption>()
    val selectedFlags = mutableListOf<Boolean>()
    var videoGroupOrdinal = 0
    for (group in tracks.groups) {
        if (group.type != C.TRACK_TYPE_VIDEO) continue
        val mediaGroup = group.mediaTrackGroup
        for (trackIndex in 0 until mediaGroup.length) {
            val format = mediaGroup.getFormat(trackIndex)
            selectedFlags.add(group.isTrackSelected(trackIndex))
            variants.add(
                VideoQualityOption(
                    id = "$videoGroupOrdinal:$trackIndex",
                    label = formatVideoQualityLabel(format, trackIndex),
                    isSelected = false,
                    resolution = format.height.takeIf { it > 0 }?.let { "${it}p" },
                ),
            )
        }
        videoGroupOrdinal++
    }
    if (variants.isEmpty()) return emptyList()

    // An explicit single-variant override is in effect only when EXACTLY one
    // variant is selected among multiple. Several selected (or none) = adaptive,
    // so Auto is the active option. (A single-variant group is trivially "Auto"
    // — there is nothing to switch.)
    val hasMultipleVariants = variants.size > 1
    val selectedCount = selectedFlags.count { it }
    val overrideActive = hasMultipleVariants && selectedCount == 1
    val selectedVariantIndex = if (overrideActive) selectedFlags.indexOfFirst { it } else -1

    val resolved = variants.mapIndexed { idx, v ->
        v.copy(isSelected = idx == selectedVariantIndex)
    }
    return buildList {
        add(
            VideoQualityOption(
                id = VIDEO_QUALITY_AUTO_ID,
                label = "Auto",
                isSelected = !overrideActive,
            ),
        )
        addAll(resolved)
    }
}

private fun formatVideoQualityLabel(format: Format, trackIndex: Int): String {
    val height = format.height.takeIf { it > 0 }
    val resolution = when {
        height != null -> "${height}p"
        else -> null
    }
    val bitrate = format.bitrate.takeIf { it > 0 }?.let { bps ->
        when {
            bps >= 1_000_000 -> "%.1f Mbps".format(bps / 1_000_000.0)
            else -> "%.0f Kbps".format(bps / 1_000.0)
        }
    }
    val parts = listOfNotNull(resolution, bitrate)
    return if (parts.isEmpty()) "Variant ${trackIndex + 1}" else parts.joinToString(" · ")
}

internal fun resizeModeForVideoFillMode(mode: VideoFillMode): Int = when (mode) {
    VideoFillMode.Fit -> AspectRatioFrameLayout.RESIZE_MODE_FIT
    VideoFillMode.Zoom -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    VideoFillMode.Stretch -> AspectRatioFrameLayout.RESIZE_MODE_FILL
}

private const val FitSurfaceScale = 1.0f

internal fun applyPlayerViewVideoFillMode(view: PlayerView, mode: VideoFillMode) {
    view.resizeMode = resizeModeForVideoFillMode(mode)
    val surface = view.getVideoSurfaceView()
    surface?.let { videoSurface ->
        fun applyScale() {
            videoSurface.pivotX = videoSurface.width / 2f
            videoSurface.pivotY = videoSurface.height / 2f
            videoSurface.scaleX = FitSurfaceScale
            videoSurface.scaleY = FitSurfaceScale
        }
        applyScale()
        if (videoSurface.width == 0 || videoSurface.height == 0) {
            videoSurface.post { applyScale() }
        }
    }
    Log.i(
        TAG,
        "TV aspect mode=$mode resize=${view.resizeMode} surfaceScale=$FitSurfaceScale " +
            "surface=${surface?.javaClass?.simpleName ?: "none"}",
    )
}

internal fun applyMpvVideoScaleMode(player: Player?, mode: VideoFillMode) {
    val mpvPlayer = player as? MpvVideoScaleController ?: return
    val mpvMode = when (mode) {
        VideoFillMode.Fit -> MpvVideoScaleMode.Fit
        VideoFillMode.Zoom -> MpvVideoScaleMode.Zoom
        VideoFillMode.Stretch -> MpvVideoScaleMode.Stretch
    }
    mpvPlayer.setVideoScaleMode(mpvMode)
}

private fun TvPlayerViewModel.UiState.toSiloCastPlaybackState(
    contentId: String,
    playbackSpeed: Double,
    hdrEnabled: Boolean,
    subtitleDelayMs: Int,
    subtitleAppearance: SubtitleAppearance,
    volume: Double,
): SiloCastPlaybackState {
    val activeQualityId = videoQualities.firstOrNull { it.isSelected }?.id ?: VIDEO_QUALITY_AUTO_ID
    return SiloCastPlaybackState(
        contentId = contentId,
        sessionId = sessionId,
        title = title,
        subtitle = listOfNotNull(
            seasonNumber?.let { "S$it" },
            episodeNumber?.let { "E$it" },
        ).joinToString(" ").ifBlank { null },
        isPlaying = isPlaying && !isPaused,
        isLoading = isLoading,
        isBuffering = isBuffering,
        currentTime = position,
        duration = duration,
        audioTracks = audioTracks.map { it.toSiloCastTrack(kind = "audio") },
        subtitleTracks = subtitleTracks.map { it.toSiloCastTrack(kind = "subtitle") },
        selectedAudioTrackId = audioTracks.firstOrNull { it.isSelected }?.index?.toLong(),
        selectedSubtitleTrackId = subtitleTracks.firstOrNull { it.isSelected }?.index?.toLong(),
        qualityOptions = videoQualities.map { it.toSiloCastQualityOption() },
        activeQualityId = activeQualityId,
        isQualitySwitching = false,
        playbackSpeed = playbackSpeed,
        videoGravity = videoFillMode.name.lowercase(),
        hdrEnabled = hdrEnabled,
        supportsVideoGravity = true,
        supportsHDRToggle = true,
        subtitleSyncMs = subtitleDelayMs,
        subtitlePosition = subtitleAppearance.position.toSiloCastPositionValue(),
        supportsSubtitleDelay = true,
        supportsSubtitlePosition = true,
        volume = volume.coerceIn(0.0, 1.0),
        isMuted = volume <= 0.001,
        hasNextEpisode = nextEpisode != null,
        nextEpisodeTitle = nextEpisode?.title,
        error = error,
    )
}

private fun PlayerTrackEntry.toSiloCastTrack(kind: String): SiloCastTrack =
    SiloCastTrack(
        kind = kind,
        trackId = index.toLong(),
        title = displayLabel.ifBlank { label },
        detail = language,
    )

private fun VideoQualityOption.toSiloCastQualityOption(): SiloCastQualityOption =
    SiloCastQualityOption(
        id = id,
        label = label,
        detail = resolution,
    )

// Apple SubtitlePositionPreset raw values: "bottom", "lower-third", "top".
private fun SubtitlePositionPreset.toSiloCastPositionValue(): String = when (this) {
    SubtitlePositionPreset.Top -> "top"
    SubtitlePositionPreset.LowerThird -> "lower-third"
    SubtitlePositionPreset.Bottom -> "bottom"
}

private fun String.toSiloCastVideoFillMode(): VideoFillMode =
    when (trim().lowercase()) {
        "zoom", "crop", "fill" -> VideoFillMode.Zoom
        "stretch" -> VideoFillMode.Stretch
        else -> VideoFillMode.Fit
    }

private fun String.toSiloCastSubtitlePosition(): SubtitlePositionPreset {
    when (trim().lowercase()) {
        "top" -> return SubtitlePositionPreset.Top
        "lower-third", "lower_third", "lowerthird" -> return SubtitlePositionPreset.LowerThird
        "bottom" -> return SubtitlePositionPreset.Bottom
    }
    val numeric = toDoubleOrNull() ?: return SubtitlePositionPreset.Bottom
    return if (numeric <= 1.0) {
        when {
            numeric <= 0.33 -> SubtitlePositionPreset.Top
            numeric <= 0.75 -> SubtitlePositionPreset.LowerThird
            else -> SubtitlePositionPreset.Bottom
        }
    } else {
        when {
            numeric <= 33.0 -> SubtitlePositionPreset.Top
            numeric <= 80.0 -> SubtitlePositionPreset.LowerThird
            else -> SubtitlePositionPreset.Bottom
        }
    }
}

private data class EngineSwitchResult(
    val success: Boolean,
    val swapped: Boolean,
)

private suspend fun MediaController.awaitEngineSwitch(request: VideoPlaybackBackendRequest): EngineSwitchResult =
    suspendCancellableCoroutine { continuation ->
        val future = sendCustomCommand(
            SessionCommand(PlaybackEngineCommand.SET_ENGINE, Bundle.EMPTY),
            Bundle().apply {
                putString(PlaybackEngineCommand.ARG_REQUEST_JSON, PlaybackEngineCommand.encode(request))
            },
        )
        future.addListener(
            {
                val switchResult = runCatching {
                    val result = future.get()
                    EngineSwitchResult(
                        success = result.resultCode == SessionResult.RESULT_SUCCESS,
                        swapped = result.extras.getBoolean(PlaybackEngineCommand.RESULT_SWAPPED, false),
                    )
                }.getOrDefault(EngineSwitchResult(success = false, swapped = false))
                if (continuation.isActive) {
                    continuation.resume(switchResult)
                }
            },
            MoreExecutors.directExecutor(),
        )
        continuation.invokeOnCancellation { future.cancel(false) }
    }

/**
 * Apply (or clear, for [VIDEO_QUALITY_AUTO_ID]) a video quality override on the
 * player. Mirrors [AudioTrackManager]'s override approach but targets a specific
 * format *within* the video group. This is a real Media3 track switch.
 */
internal fun selectVideoQuality(player: Player, id: String): Boolean {
    if (id == VIDEO_QUALITY_AUTO_ID) {
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
            .build()
        return true
    }
    val parts = id.split(":")
    val groupOrdinal = parts.getOrNull(0)?.toIntOrNull() ?: return false
    val trackIndex = parts.getOrNull(1)?.toIntOrNull() ?: return false
    var ordinal = 0
    for (group in player.currentTracks.groups) {
        if (group.type != C.TRACK_TYPE_VIDEO) continue
        if (ordinal == groupOrdinal) {
            val mediaGroup = group.mediaTrackGroup
            if (trackIndex !in 0 until mediaGroup.length) return false
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setOverrideForType(
                    androidx.media3.common.TrackSelectionOverride(mediaGroup, trackIndex),
                )
                .build()
            return true
        }
        ordinal++
    }
    return false
}
