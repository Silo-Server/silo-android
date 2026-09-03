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
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.Timeline
import androidx.media3.common.VideoSize
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.google.common.util.concurrent.MoreExecutors
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.siloserver.silo.cast.SiloCastPlaybackState
import org.siloserver.silo.cast.SiloCastQualityOption
import org.siloserver.silo.cast.SiloCastTrack
import org.siloserver.silo.common.pip.SiloPictureInPictureCoordinator
import org.siloserver.silo.common.pip.SiloPictureInPicturePlaybackState
import org.siloserver.silo.common.pip.SiloPictureInPictureSurface
import org.siloserver.silo.common.player.ActivePlayerHolder
import org.siloserver.silo.common.player.AudioCapabilityManager
import org.siloserver.silo.common.player.DisplayHdrProbe
import org.siloserver.silo.common.player.playbackDisplayId
import org.siloserver.silo.common.player.HdrDisplayController
import org.siloserver.silo.common.player.LetterboxInsets
import org.siloserver.silo.common.player.PlayWhenReadyReconciliationGate
import org.siloserver.silo.common.player.PlaybackCapabilityDetector
import org.siloserver.silo.common.player.PlaybackPreflightListener
import org.siloserver.silo.common.player.PlayerNotice
import org.siloserver.silo.common.player.SessionState
import org.siloserver.silo.common.player.SiloPlaybackService
import org.siloserver.silo.common.player.SleepTimerState
import org.siloserver.silo.common.player.SubtitleManager
import org.siloserver.silo.common.player.VideoPlayerMediaSpec
import org.siloserver.silo.common.player.subtitlesForVideoMediaMount
import org.siloserver.silo.common.player.backend.VideoPlaybackBackendFactory
import org.siloserver.silo.common.player.backend.VideoPlaybackBackendRequest
import org.siloserver.silo.common.player.validatedColorRangeFallback
import org.siloserver.silo.common.player.video.PlaybackRuntimeCorrectionMetrics
import org.siloserver.silo.common.player.video.PlaybackStartupStallDetector
import org.siloserver.silo.common.player.video.PostResumeVideoStallDetector
import org.siloserver.silo.common.player.video.VideoPlayerTrackEntry
import org.siloserver.silo.playback.subtitleLabelIndicatesHearingImpaired
import org.siloserver.silo.domain.player.IntroAutoSkipState
import org.siloserver.silo.model.playback.PlaybackExecutionPlan
import org.siloserver.silo.model.playback.PlaybackSourceMetadata
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.executableMedia3ClientTransformations
import org.siloserver.silo.model.playback.activeOriginalHttpClaims
import org.siloserver.silo.model.settings.SubtitleAppearance
import org.siloserver.silo.model.settings.SubtitlePositionPreset
import org.siloserver.silo.model.settings.legacyPosition
import org.siloserver.silo.model.watchtogether.RoomPlaybackState
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.player.DolbyVisionDetection
import org.siloserver.silo.player.formatSubtitleTrackDisplayLabel
import org.siloserver.silo.tv.R
import org.siloserver.silo.tv.cast.SiloCastVolumeState
import org.siloserver.silo.tv.cast.TvSiloCastPlayerAdapter
import org.siloserver.silo.tv.cast.TvSiloCastReceiver
import org.siloserver.silo.tv.ui.components.TvErrorScreen
import org.siloserver.silo.tv.ui.components.TvLoadingScreen
import org.siloserver.silo.tv.ui.components.rememberTvDialogInitialFocus
import org.siloserver.silo.tv.ui.focus.TvContentInitialFocusMaxAttempts
import org.siloserver.silo.tv.ui.focus.TvFocusLog
import org.siloserver.silo.tv.ui.focus.claimFocusOrReport
import org.siloserver.silo.tv.ui.focus.requestFocusUntilObserved
import org.siloserver.silo.watchtogether.shouldNavigateToLocalNext

private const val CONTROLS_AUTO_HIDE_MS = 5_000L
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
private const val CLEAN_SEEK_HOLD_THRESHOLD_MS = 300L
private const val CLEAN_QUICK_SKIP_CAPTURE_MS = 200L

private enum class TvIdleOverlayFocusTarget {
    Scrubber,
    Transport,
}

private data class TvIdleOverlayFocusRequest(
    val target: TvIdleOverlayFocusTarget = TvIdleOverlayFocusTarget.Transport,
    val nonce: Int = 0,
)

/**
 * Manual rate step for a hidden-controls hold-seek.
 *
 * Delegates to [TvSeekRateLadder] so this control and the focused scrubber's
 * hold-seek walk one ladder. They previously kept separate ones, and the
 * hidden path's was both dishonest about its multiples (see
 * [advanceCleanPlaybackSeekPreview]) and flipped direction when stepped below
 * 1× — so "slower" eventually meant "backwards".
 */
internal fun adjustedCleanPlaybackSeekRate(
    currentRate: Int,
    adjustment: Int,
    durationSec: Double,
): Int {
    if (adjustment == 0) return currentRate
    return TvSeekRateLadder.bumped(currentRate, adjustment, durationSec)
}

/**
 * The HUD tab a Down press from clean playback should land on.
 *
 * Mirrors tvOS `preferredPlaybackHUDTab`: that press is nearly always reaching
 * for an audio or subtitle track, so route straight there rather than making
 * the viewer traverse from Info every time. Falls back to Video, which — like
 * Info and Subtitles — is always present in [visibleHudTabs], so this can
 * never name a tab the HUD would reject.
 */
internal fun preferredPlaybackHudTab(
    hasAudioTracks: Boolean,
    hasSubtitleTracks: Boolean,
): HudTab = when {
    hasAudioTracks -> HudTab.Audio
    hasSubtitleTracks -> HudTab.Subtitles
    else -> HudTab.Video
}

internal fun shouldEnterCleanPlaybackSeekHold(
    allowsHold: Boolean,
    pressDurationMs: Long,
): Boolean = allowsHold && pressDurationMs >= CLEAN_SEEK_HOLD_THRESHOLD_MS

internal fun isCleanPlaybackSeekAdjustmentTap(
    repeated: Boolean,
    pressDurationMs: Long,
): Boolean = !repeated && pressDurationMs < CLEAN_SEEK_HOLD_THRESHOLD_MS

/**
 * One hold-seek tick for the hidden-controls scan.
 *
 * Advances by [TvSeekRateLadder.tickSeconds], which is what makes the rate
 * chip mean what it says: rate × tick seconds per tick is exactly rate × real
 * time. This previously advanced a flat 2s per 100ms tick at 1×, so every
 * multiple on screen was a twentieth of the truth — a chip reading "8×" moved
 * at 160×, and the same gesture ran 20× faster with the chrome hidden than the
 * focused scrubber's hold-seek, which had already been corrected.
 */
internal fun advanceCleanPlaybackSeekPreview(
    previewSec: Double,
    durationSec: Double,
    rate: Int,
): Double {
    val safePreview = previewSec.takeIf { it.isFinite() }?.coerceAtLeast(0.0) ?: 0.0
    val next = (safePreview + TvSeekRateLadder.tickSeconds(rate)).coerceAtLeast(0.0)
    return if (durationSec.isFinite() && durationSec > 0.0) {
        next.coerceAtMost(durationSec)
    } else {
        next
    }
}

internal fun shouldShowReconnectSpinner(
    isReconnecting: Boolean,
    showNextUp: Boolean,
    isInPictureInPictureMode: Boolean,
): Boolean = isReconnecting && !showNextUp && !isInPictureInPictureMode

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
    initialAudioPickedThisSession: Boolean = false,
    initialSubtitleTrackIndex: Int? = null,
    // True when the carried subtitle index is the detail row's Auto preview
    // rather than the viewer's own pick (it still decides what starts).
    initialSubtitleAutoResolved: Boolean = false,
    // Consecutive auto-advance count (pass-out protection); 0 = manual start.
    autoAdvanceCount: Int = 0,
    episodeSelectionHandoff: org.siloserver.silo.common.player.video.EpisodeSelectionHandoff? = null,
    // Navigate to the next episode (auto-advance / "Continue"), carrying the
    // updated streak count.
    onPlayNext: (
        contentId: String,
        autoAdvanceCount: Int,
        episodeSelectionHandoff: org.siloserver.silo.common.player.video.EpisodeSelectionHandoff,
    ) -> Unit = { _, _, _ -> },
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
                    initialAudioPickedThisSession = initialAudioPickedThisSession,
                    initialSubtitleTrackIndex = initialSubtitleTrackIndex,
                    initialSubtitleAutoResolved = initialSubtitleAutoResolved,
                    autoAdvanceCount = autoAdvanceCount,
                    episodeSelectionHandoff = episodeSelectionHandoff,
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
    // The player never takes text input, so any soft keyboard visible here
    // leaked in from a prior screen (e.g. starting playback from a search with
    // the IME up). Dismiss it on entry — belt-and-braces over the source fixes
    // in TvSearchScreen / TvTextInputDialog.
    val keyboardController = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { runCatching { keyboardController?.hide() } }
    val state by viewModel.presentationState.collectAsState()
    val isInPictureInPictureMode by pictureInPictureCoordinator.isInPictureInPictureMode.collectAsState()
    // PlayerView surface must bind to THIS, not the MediaController, so the
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
    val introSkipCountdownRun by viewModel.introSkipCountdownRun.collectAsState()
    val introSkipTimerRunning by viewModel.introSkipTimerRunning.collectAsState()
    val subtitleAppearance by viewModel.subtitleAppearance.collectAsState()
    val playbackSpeed by viewModel.playbackSpeed.collectAsState()
    val sleepTimerState by viewModel.sleepTimerState.collectAsState()
    val introSkipMode by viewModel.introSkipMode.collectAsState()
    val autoPlayNextEnabled by viewModel.autoPlayNextEnabled.collectAsState()
    val audioDelayMs by viewModel.audioDelayMs.collectAsState()
    val subtitleDelayMs by viewModel.subtitleDelayMs.collectAsState()
    val hdrEnabled by viewModel.hdrEnabled.collectAsState()
    val dolbyVisionEnabled by viewModel.dolbyVisionEnabled.collectAsState()
    val dolbyVisionSwitchInFlight by viewModel.dolbyVisionSwitchInFlight.collectAsState()
    val subtitleSearch by viewModel.subtitleSearch.collectAsState()
    val aiTranslate by viewModel.aiTranslate.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnExit by rememberUpdatedState(onExit)
    val latestSiloCastPlaybackSpeed by rememberUpdatedState(playbackSpeed)
    val latestSiloCastSubtitleDelayMs by rememberUpdatedState(subtitleDelayMs)
    val latestSiloCastHdrEnabled by rememberUpdatedState(hdrEnabled)
    val latestSiloCastSubtitleAppearance by rememberUpdatedState(subtitleAppearance)
    val context = LocalContext.current
    val hdrDisplayController = remember { HdrDisplayController() }
    // Bind the playback display before anything plans: the ViewModel's
    // initializer starts loading as soon as it exists, so the binding has to
    // happen during composition, not in a later effect.
    // The binding is owned: during a player-to-player transition the incoming
    // screen binds while the outgoing one is still composed, and the outgoing
    // screen's release only clears its own claim.
    // Keyed on the display id itself so an Activity that moves to another
    // display rebinds and the next plan describes the new panel. The
    // binding is a RememberObserver: Compose releases it when this player
    // leaves, when the key changes, and when a composition is abandoned
    // before it commits, and the owned release never clears a newer claim.
    val currentPlaybackDisplayId = context.playbackDisplayId()
    remember(currentPlaybackDisplayId, capabilityDetector) {
        capabilityDetector.bindPlaybackDisplay(currentPlaybackDisplayId)
    }
    // Re-probe whenever the output route generation moves, so track
    // selection sees the same display facts as capability detection.
    val outputRouteGeneration by audioCapabilityManager.outputRouteGeneration.collectAsState()
    val displayHdr = remember(outputRouteGeneration) {
        DisplayHdrProbe.probe(context, capabilityDetector.playbackDisplayId)
    }
    val audioCaps by audioCapabilityManager.capabilities.collectAsState()
    val rootFocus = remember { FocusRequester() }
    var playerRootHasFocus by remember { mutableStateOf(false) }
    var exitRequested by remember { mutableStateOf(false) }
    var requestedHudTab by remember { mutableStateOf(HudTab.Info) }
    var showQuickSubtitlePicker by remember { mutableStateOf(false) }
    var subtitleFocusedStableId by remember { mutableStateOf<String?>(null) }
    // Captured PlayerView reference so subtitleManager.applyAppearance can hit
    // the inflated subtitleView after the AndroidView factory runs. Mirrors
    // the phone PlayerScreen's `playerViewRef` pattern.
    var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
    var idleOverlayFocusRequest by remember { mutableStateOf(TvIdleOverlayFocusRequest()) }
    val cleanPlaybackSeekScope = rememberCoroutineScope()
    var pendingCleanSeekDirection by remember { mutableStateOf(0) }
    var pendingCleanSeekGeneration by remember { mutableStateOf(0L) }
    var pendingCleanSeekBecameHold by remember { mutableStateOf(false) }
    var pendingCleanSeekAllowsHold by remember { mutableStateOf(false) }
    var cleanSeekHoldJob by remember { mutableStateOf<Job?>(null) }
    var cleanSeekTickJob by remember { mutableStateOf<Job?>(null) }
    var cleanSeekRampJob by remember { mutableStateOf<Job?>(null) }
    var cleanSeekRate by remember { mutableStateOf(0) }
    var cleanSeekPreviewSec by remember { mutableStateOf(0.0) }
    var cleanSeekAdjustmentDirection by remember { mutableStateOf(0) }
    var cleanSeekAdjustmentRepeated by remember { mutableStateOf(false) }
    var quickSkipCaptureGeneration by remember { mutableStateOf(0L) }
    var quickSkipCaptureActive by remember { mutableStateOf(false) }
    var quickSkipCaptureJob by remember { mutableStateOf<Job?>(null) }
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
    val postResumeStallDetector = remember { PostResumeVideoStallDetector() }
    var dvSanitizerReported by remember { mutableStateOf(false) }
    var pictureInPictureVideoWidth by remember { mutableStateOf(16) }
    var pictureInPictureVideoHeight by remember { mutableStateOf(9) }
    var pictureInPictureSourceRect by remember { mutableStateOf<Rect?>(null) }

    // Watch Together binding. Built once per roomId; null for solo playback.
    // The process RoomSession owns the WS; this controller owns only the
    // screen's RoomSyncEngine and requests durable teardown on explicit leave.
    val watchTogetherRepository: org.siloserver.silo.repository.WatchTogetherRepository = koinInject()
    val roomSession: org.siloserver.silo.watchtogether.RoomSession = koinInject()
    val roomScope = rememberCoroutineScope()
    val roomController = remember(roomId) {
        roomId?.takeIf { it.isNotBlank() }?.let { id ->
            TvRoomSyncController(
                roomId = id,
                repository = watchTogetherRepository,
                roomSession = roomSession,
                viewModel = viewModel,
                scope = roomScope,
            )
        }
    }
    DisposableEffect(roomController) {
        roomController?.start()
        // Repo teardown happens on explicit leave (Leave affordance) or
        // room_closed; only this replaceable controller's child jobs are
        // canceled on disposal.
        onDispose { roomController?.dispose() }
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
    val playWhenReadyReconciliationGate = remember(mediaController, roomId) {
        PlayWhenReadyReconciliationGate()
    }
    val backendPlayer = sessionPlayer ?: mediaController
    val videoBackend = remember(backendPlayer, backendFactory) {
        backendPlayer?.let { player ->
            backendFactory.create(
                player = player,
                request = VideoPlaybackBackendRequest(),
            )
        }
    }
    // False until presets have been applied once for the current backend, so
    // only later capability changes wait for the route to settle.
    var trackPresetsApplied by remember(videoBackend) { mutableStateOf(false) }
    LaunchedEffect(videoBackend) {
        videoBackend?.let { backend ->
            viewModel.onBackendCapabilities(backend.capabilities)
        }
    }
    // A plan that promised the Dolby Vision Profile 8 base-layer route is
    // only valid while an ordinary HEVC decoder is reading the stream. The
    // renderer reports the decoder it actually opened; anything else becomes
    // a typed replan rather than an unverified presentation.
    LaunchedEffect(videoBackend) {
        val backend = videoBackend ?: return@LaunchedEffect
        backend.baseLayerDecoderMismatch.collect { decoderName ->
            if (decoderName == null) return@collect
            val plan = viewModel.uiState.value.playbackPlan
            viewModel.onUnsupportedPlayback(
                org.siloserver.silo.common.player.Playability.DvBaseLayerDecoderUnavailable(
                    decoderName = decoderName,
                    baseRange = plan?.source?.hdrFormat.orEmpty(),
                ),
            )
        }
    }
    val latestSiloCastMediaController by rememberUpdatedState(mediaController)
    val latestSiloCastSessionPlayer by rememberUpdatedState(sessionPlayer)
    DisposableEffect(siloCastReceiver, viewModel, contentId) {
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
                    val resolution = viewModel.uiState.value.videoQualities
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
                latestSiloCastMediaController?.let { controller ->
                    val next = volume.toFloat().coerceIn(0f, 1f)
                    siloCastReceiver.recordPlayerVolume(next.toDouble())
                    controller.volume = next
                }
            },
            setMuted = { muted ->
                latestSiloCastMediaController?.let { controller ->
                    siloCastReceiver.recordPlayerMuted(muted, controller.volume.toDouble())
                    controller.volume = if (muted) 0f else siloCastReceiver.retainedPlayerVolume().toFloat()
                }
            },
            playNext = viewModel::playNextEpisodeNow,
        )
        val registration = siloCastReceiver.registerPlayer(adapter) {
            val volumeState = siloCastReceiver.resolvePlayerVolume(
                currentVolume = latestSiloCastMediaController?.volume?.toDouble(),
            )
            viewModel.uiState.value.toSiloCastPlaybackState(
                contentId = contentId,
                playbackSpeed = latestSiloCastPlaybackSpeed,
                hdrEnabled = latestSiloCastHdrEnabled,
                subtitleDelayMs = latestSiloCastSubtitleDelayMs,
                subtitleAppearance = latestSiloCastSubtitleAppearance,
                volumeState = volumeState,
            )
        }
        onDispose { registration.close() }
    }
    val stopPlaybackAndExit = {
        if (!exitRequested) {
            exitRequested = true
            // Every terminal player exit must release the process-owned room
            // session. Explicit host-close paths enqueue close first, then this
            // idempotent local departure follows behind it.
            roomController?.leave(closeRoom = false)
            mediaController?.let { controller ->
                viewModel.stopSessionForExitAsync(
                    positionMs = controller.currentPosition,
                    durationMs = controller.duration.coerceAtLeast(0L),
                )
                controller.pause()
                controller.stop()
                controller.clearMediaItems()
            } ?: viewModel.stopSessionForExitAsync()
            latestOnExit()
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
            if (!shouldNavigateToLocalNext(roomController != null)) {
                return@collect
            }
            exitRequested = true
            mediaController?.let { c -> c.pause(); c.stop(); c.clearMediaItems() }
            // AWAIT the old session stop before navigating: the lifecycle is a
            // singleton, so a late stop() could clobber the next episode's freshly
            // adopted session, and popUpTo would otherwise cancel it mid-flight.
            viewModel.stopSessionForExit()
            onPlayNext(req.contentId, req.autoAdvanceCount, req.episodeSelectionHandoff)
        }
    }
    val latestIntroSkipState by rememberUpdatedState(introSkipState)
    val latestRoomSnapshot by rememberUpdatedState(roomSnapshot)
    val latestShowLeaveDialog by rememberUpdatedState(showLeaveDialog)
    val latestShowQuickSubtitlePicker by rememberUpdatedState(showQuickSubtitlePicker)
    val selectTvSubtitle: (SubtitleIdentity) -> Unit = { identity ->
        subtitleFocusedStableId = tvSubtitleOptionStableId(identity)
        viewModel.selectSubtitleOption(identity)
    }
    fun applyQuickSubtitlePickerExit(exit: TvQuickSubtitlePickerExit) {
        val chrome = tvQuickSubtitlePickerChromeState(exit)
        showQuickSubtitlePicker = chrome.pickerVisible
        viewModel.setControlsVisible(chrome.controlsVisible)
    }
    val subtitlePresentation = buildTvSubtitleHudPresentation(
        options = buildTvSubtitleHudOptions(
            subtitleUrls = state.subtitleUrls,
            subtitleTracks = state.subtitleTracks,
        ),
        committedIdentity = state.committedSubtitleIdentity,
        pendingIdentity = state.pendingSubtitleIdentity,
        hudOpen = state.hudOpen || showQuickSubtitlePicker,
        focusedStableId = subtitleFocusedStableId,
        onSelect = selectTvSubtitle,
        onFocused = { stableId -> subtitleFocusedStableId = stableId },
    )

    fun requestIdleOverlayFocus(target: TvIdleOverlayFocusTarget) {
        idleOverlayFocusRequest = TvIdleOverlayFocusRequest(
            target = target,
            nonce = idleOverlayFocusRequest.nonce + 1,
        )
    }

    fun handleIntroPromptSelect(): Boolean {
        val playerState = viewModel.uiState.value
        if (!latestIntroSkipState.isVisible) return false
        // The controller decides where Select goes — the intro's end for the
        // `ask` offer, its start for `always`'s undo — and resolves the intro.
        // In a room the gate is checked BEFORE asking, so a guest's refused
        // press leaves the pill (and the intro) exactly as it was.
        if (roomController != null) {
            if (tvRoomTransportGate(latestRoomSnapshot, TvTransportIntent.Seek) != TransportGate.Send) {
                return true
            }
            val target = viewModel.onSelectIntroPrompt() ?: return false
            roomController.onUserSeek(target)
        } else {
            val soloTarget = viewModel.onSelectIntroPrompt() ?: return false
            viewModel.seekImmediate(soloTarget)
        }
        // The pill unmounts with the intro state, taking its focus with it, so
        // aim at the scrubber (where Down from the pill goes). Only with the
        // controls up: otherwise the overlay owning the scrubber isn't composed.
        if (playerState.showControls) {
            requestIdleOverlayFocus(TvIdleOverlayFocusTarget.Scrubber)
        }
        return true
    }

    fun armQuickSkipCapture() {
        quickSkipCaptureJob?.cancel()
        quickSkipCaptureGeneration = if (quickSkipCaptureGeneration == Long.MAX_VALUE) {
            1L
        } else {
            quickSkipCaptureGeneration + 1L
        }
        val generation = quickSkipCaptureGeneration
        quickSkipCaptureActive = true
        quickSkipCaptureJob = cleanPlaybackSeekScope.launch {
            delay(CLEAN_QUICK_SKIP_CAPTURE_MS)
            if (quickSkipCaptureGeneration == generation) {
                quickSkipCaptureActive = false
                quickSkipCaptureJob = null
            }
        }
    }

    fun performRelativeSeek(
        deltaMs: Long,
        snapshot: RoomSnapshot?,
        revealControls: Boolean,
        captureQuickSkipBurst: Boolean = false,
    ): Boolean {
        val controller = mediaController ?: return true
        val playerState = viewModel.uiState.value
        if (roomController != null &&
            tvRoomTransportGate(snapshot, TvTransportIntent.Seek) != TransportGate.Send
        ) {
            return true
        }
        val duration = playerState.duration.takeIf { it > 0.0 }
            ?: if (playerState.playbackPlan == null) controller.duration / 1000.0 else 0.0
        val targetSec = if (roomController == null) {
            viewModel.onSkipBy(deltaMs / 1000.0)
        } else {
            (playerState.position + deltaMs / 1000.0)
                .coerceAtLeast(0.0)
                .let { if (duration > 0.0) it.coerceAtMost(duration) else it }
        }
        if (roomController != null) {
            roomController.onUserSeek(targetSec)
        }
        if (revealControls) {
            if (!playerState.showControls) {
                requestIdleOverlayFocus(TvIdleOverlayFocusTarget.Scrubber)
            }
            viewModel.setControlsVisible(true)
        }
        // The chip runs on BOTH paths. Revealing the transport shows where the
        // playhead landed but not that it moved, nor by how much — a solitary
        // press reads as the bar twitching. Room seeks commit per press with no
        // accumulator, so there the per-press delta IS the total.
        val burstDeltaSec = viewModel.quickSkipBurstOriginSec
            ?.takeIf { roomController == null }
            ?.let { targetSec - it }
            ?: (deltaMs / 1000.0)
        skipSeekFeedback = SkipSeekFeedback(
            deltaSeconds = burstDeltaSec.roundToInt(),
            targetSec = targetSec,
            durationSec = duration.coerceAtLeast(0.0),
            nonce = (skipSeekFeedback?.nonce ?: 0) + 1,
        )
        if (captureQuickSkipBurst && roomController == null) {
            armQuickSkipCapture()
        }
        return true
    }

    fun clearPendingCleanSeekPress() {
        cleanSeekHoldJob?.cancel()
        cleanSeekHoldJob = null
        pendingCleanSeekDirection = 0
        pendingCleanSeekBecameHold = false
        pendingCleanSeekAllowsHold = false
    }

    fun stopCleanPlaybackSeek() {
        cleanSeekTickJob?.cancel()
        cleanSeekTickJob = null
        cleanSeekRampJob?.cancel()
        cleanSeekRampJob = null
        cleanSeekRate = 0
        cleanSeekAdjustmentDirection = 0
        cleanSeekAdjustmentRepeated = false
        clearPendingCleanSeekPress()
    }

    fun beginCleanPlaybackSeek(direction: Int, snapshot: RoomSnapshot?) {
        pendingCleanSeekBecameHold = true
        if (cleanSeekRate != 0 || mediaController == null) return
        if (roomController != null &&
            tvRoomTransportGate(snapshot, TvTransportIntent.Seek) != TransportGate.Send
        ) {
            return
        }

        skipSeekFeedback = null
        quickSkipCaptureJob?.cancel()
        quickSkipCaptureJob = null
        quickSkipCaptureActive = false
        cleanSeekPreviewSec = viewModel.uiState.value.position.coerceAtLeast(0.0)
        val sign = if (direction < 0) -1 else 1
        cleanSeekRate = TvSeekRateLadder.BASE_RATE * sign

        cleanSeekTickJob?.cancel()
        cleanSeekTickJob = cleanPlaybackSeekScope.launch {
            while (isActive && cleanSeekRate != 0) {
                cleanSeekPreviewSec = advanceCleanPlaybackSeekPreview(
                    previewSec = cleanSeekPreviewSec,
                    durationSec = viewModel.uiState.value.duration,
                    rate = cleanSeekRate,
                )
                delay(TvSeekRateLadder.TICK_MILLIS)
            }
        }

        // Ramp on the shared ladder rather than a fixed 2→4→8. Now that a tick
        // covers rate × real time, a fixed ceiling cannot serve both ends: 8×
        // would take over twenty minutes to cross a film. The ladder derives
        // its top from the runtime so "hold until it arrives" costs about the
        // same whatever you're watching.
        cleanSeekRampJob?.cancel()
        cleanSeekRampJob = cleanPlaybackSeekScope.launch {
            val durationSec = viewModel.uiState.value.duration
            var previous = TvSeekRateLadder.BASE_RATE * sign
            repeat(TvSeekRateLadder.rampSteps(durationSec)) { step ->
                delay(TvSeekRateLadder.RAMP_STEP_MILLIS)
                // Only continue while the viewer is still holding at the rate
                // the previous step left, so a release and a fresh press the
                // other way isn't overwritten by this hold's timer.
                if (cleanSeekRate != previous) return@launch
                val next = TvSeekRateLadder.sustainedRate(step, sign, durationSec)
                if (next == previous) return@launch
                cleanSeekRate = next
                previous = next
            }
        }
    }

    fun beginCleanSeekPress(direction: Int, allowsHold: Boolean) {
        cleanSeekHoldJob?.cancel()
        pendingCleanSeekGeneration = if (pendingCleanSeekGeneration == Long.MAX_VALUE) {
            1L
        } else {
            pendingCleanSeekGeneration + 1L
        }
        val generation = pendingCleanSeekGeneration
        pendingCleanSeekDirection = direction
        pendingCleanSeekBecameHold = false
        pendingCleanSeekAllowsHold = allowsHold
        cleanSeekHoldJob = if (allowsHold) {
            cleanPlaybackSeekScope.launch {
                delay(CLEAN_SEEK_HOLD_THRESHOLD_MS)
                if (pendingCleanSeekGeneration == generation &&
                    pendingCleanSeekDirection == direction &&
                    !viewModel.uiState.value.showControls &&
                    !viewModel.uiState.value.showNextUp
                ) {
                    beginCleanPlaybackSeek(direction, latestRoomSnapshot)
                }
            }
        } else {
            null
        }
    }

    fun finishCleanSeekPress(
        direction: Int,
        pressDurationMs: Long,
        snapshot: RoomSnapshot?,
    ): Boolean {
        if (pendingCleanSeekDirection != direction) return true
        if (!pendingCleanSeekBecameHold &&
            shouldEnterCleanPlaybackSeekHold(pendingCleanSeekAllowsHold, pressDurationMs)
        ) {
            // Coroutine dispatch can be delayed under UI load. Classify from
            // the event timestamps as a fallback so a real 300ms hold never
            // degrades into an accidental quick skip.
            beginCleanPlaybackSeek(direction, snapshot)
        }
        val becameHold = pendingCleanSeekBecameHold
        clearPendingCleanSeekPress()
        if (!becameHold) {
            performRelativeSeek(
                deltaMs = if (direction < 0) -SKIP_BACK_MS else SKIP_FORWARD_MS,
                snapshot = snapshot,
                revealControls = true,
                captureQuickSkipBurst = true,
            )
        }
        return true
    }

    fun adjustCleanPlaybackSeek(adjustment: Int) {
        cleanSeekRampJob?.cancel()
        cleanSeekRampJob = null
        cleanSeekRate = adjustedCleanPlaybackSeekRate(
            currentRate = cleanSeekRate,
            adjustment = adjustment,
            durationSec = viewModel.uiState.value.duration,
        )
    }

    fun commitCleanPlaybackSeek(snapshot: RoomSnapshot?) {
        val targetSec = cleanSeekPreviewSec
        stopCleanPlaybackSeek()
        if (roomController != null) {
            if (tvRoomTransportGate(snapshot, TvTransportIntent.Seek) == TransportGate.Send) {
                roomController.onUserSeek(targetSec)
            }
        } else {
            viewModel.seekImmediate(targetSec)
        }
        requestIdleOverlayFocus(TvIdleOverlayFocusTarget.Scrubber)
        viewModel.setControlsVisible(true)
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

    // More-specific overlays register their own BackHandlers later in the
    // composition and therefore run first. This screen callback owns the
    // remaining player-state ladder on Android 16, where KEYCODE_BACK is no
    // longer dispatched to apps targeting API 36.
    BackHandler {
        TvFocusLog.d {
            "player BackHandler hudOpen=${state.hudOpen} showControls=${state.showControls} " +
                "scrubbing=${state.isScrubbing} quickSubs=$showQuickSubtitlePicker " +
                "cleanSeek=$cleanSeekRate paused=${state.isPaused}"
        }
        when {
            cleanSeekRate != 0 -> stopCleanPlaybackSeek()
            state.isScrubbing -> viewModel.cancelScrub()
            // Below the seek/scrub entries deliberately: a Back during a scrub
            // belongs to the scrub. Above the overlays because the countdown is
            // the most transient thing on screen. Handled HERE and not only in
            // the legacy key bridge — on API 36 Back never reaches
            // dispatchKeyEvent, so a countdown Back would otherwise fall
            // through to hiding the controls or exiting the player.
            latestIntroSkipState.isVisible -> viewModel.onDismissIntroPrompt()
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

    DisposableEffect(viewModel, roomController) {
        val handler: (KeyEvent) -> Boolean = handler@{ event ->
            val playerState = viewModel.uiState.value
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                TvFocusLog.d {
                    "player bridge BACK action=${event.action} hudOpen=${playerState.hudOpen} " +
                        "showControls=${playerState.showControls} paused=${playerState.isPaused} " +
                        "quickSubs=$latestShowQuickSubtitlePicker cleanSeek=$cleanSeekRate"
                }
            }
            if (playerState.streamUrl == null || playerState.isLoading || playerState.error != null) {
                return@handler false
            }
            val horizontalDirection = when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> -1
                KeyEvent.KEYCODE_DPAD_RIGHT -> 1
                else -> 0
            }

            // Clean-playback seek owns the remote until Select commits or Back
            // cancels. The initial arrow's KeyUp only ends the physical hold;
            // it deliberately does not stop the persistent scan or its ramp.
            if (cleanSeekRate != 0) {
                if (pendingCleanSeekDirection != 0 && horizontalDirection != 0) {
                    if (event.action == KeyEvent.ACTION_UP &&
                        horizontalDirection == pendingCleanSeekDirection
                    ) {
                        clearPendingCleanSeekPress()
                    }
                    return@handler true
                }
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT,
                    KeyEvent.KEYCODE_DPAD_RIGHT,
                    -> {
                        when (event.action) {
                            KeyEvent.ACTION_DOWN -> {
                                if (event.repeatCount == 0) {
                                    cleanSeekAdjustmentDirection = horizontalDirection
                                    cleanSeekAdjustmentRepeated = false
                                } else if (cleanSeekAdjustmentDirection == horizontalDirection) {
                                    cleanSeekAdjustmentRepeated = true
                                }
                            }
                            KeyEvent.ACTION_UP -> {
                                val pressDurationMs =
                                    (event.eventTime - event.downTime).coerceAtLeast(0L)
                                val wasTap = cleanSeekAdjustmentDirection == horizontalDirection &&
                                    isCleanPlaybackSeekAdjustmentTap(
                                        repeated = cleanSeekAdjustmentRepeated,
                                        pressDurationMs = pressDurationMs,
                                    )
                                cleanSeekAdjustmentDirection = 0
                                cleanSeekAdjustmentRepeated = false
                                if (wasTap) adjustCleanPlaybackSeek(horizontalDirection)
                            }
                        }
                        return@handler true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                    -> {
                        if (event.action == KeyEvent.ACTION_UP) {
                            commitCleanPlaybackSeek(latestRoomSnapshot)
                        }
                        return@handler true
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        if (event.action == KeyEvent.ACTION_UP) {
                            stopCleanPlaybackSeek()
                        }
                        return@handler true
                    }
                    // Match Apple's focus sink: Up/Down do nothing during a
                    // persistent seek instead of moving focus underneath it.
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    -> return@handler true
                }
            }

            // A Down/Up pair that began while controls were hidden remains ours
            // until it is classified. Repeats are consumed; release before
            // 300ms becomes the normal -10/+30 quick-skip path.
            if (pendingCleanSeekDirection != 0 && horizontalDirection != 0) {
                return@handler if (event.action == KeyEvent.ACTION_UP) {
                    finishCleanSeekPress(
                        direction = horizontalDirection,
                        pressDurationMs = (event.eventTime - event.downTime).coerceAtLeast(0L),
                        snapshot = latestRoomSnapshot,
                    )
                } else {
                    true
                }
            }

            // Keep ownership briefly after revealing controls so another rapid
            // Down/Up pair still reaches TvPlayerViewModel.onSkipBy and extends
            // its 200ms trailing-edge accumulator. Once this window expires,
            // Left/Right falls through to the focused scrubber as normal.
            if (quickSkipCaptureActive && horizontalDirection != 0) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    beginCleanSeekPress(direction = horizontalDirection, allowsHold = false)
                }
                return@handler true
            }

            val action = tvPlayerRemoteKeyAction(
                keyCode = event.keyCode,
                action = event.action,
                repeatCount = event.repeatCount,
                // With the transport overlay or Up Next on screen, Left/Right
                // belong to Compose focus navigation, not seeking.
                dpadHorizontalSeek = !playerState.showControls && !playerState.showNextUp,
                // Same condition, different job: Down opens the HUD only from
                // clean playback. The HUD-open and modal cases never reach the
                // dispatch below — the guard beneath this call returns first.
                dpadDownOpensHud = !playerState.showControls && !playerState.showNextUp,
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

            // Back takes the pill down and resolves the intro. Consumed so the
            // press cannot also exit playback; afterwards no pill is showing,
            // so a second Back behaves normally. Mirrors the BackHandler
            // ladder's priority: a scrub or clean seek owns Back first, so the
            // pill must not swallow it here on older Android and leave the
            // scrub running.
            if (latestIntroSkipState.isVisible &&
                event.keyCode == KeyEvent.KEYCODE_BACK &&
                cleanSeekRate == 0 &&
                !state.isScrubbing
            ) {
                // Both phases are consumed: a leaked ACTION_UP would reach the
                // activity's back dispatcher.
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    viewModel.onDismissIntroPrompt()
                }
                return@handler true
            }

            // D-pad directions deliberately do NOT touch the pill: the contract
            // says focus moves as normal and the timer keeps running, so the
            // viewer can look at the transport without losing the offer.

            if (!playerState.showControls && !playerState.showNextUp && horizontalDirection != 0) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    beginCleanSeekPress(direction = horizontalDirection, allowsHold = true)
                }
                return@handler true
            }

            if (event.action == KeyEvent.ACTION_DOWN &&
                event.repeatCount == 0 &&
                latestIntroSkipState.isVisible &&
                // Only while the transport overlay is hidden: with controls up
                // a focused button owns Select — hijacking it here made every
                // OK press skip the intro for the whole intro window.
                !playerState.showControls &&
                event.keyCode in setOf(
                    KeyEvent.KEYCODE_DPAD_CENTER,
                    KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                )
            ) {
                return@handler handleIntroPromptSelect()
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
                    requestIdleOverlayFocus(TvIdleOverlayFocusTarget.Transport)
                    true
                }
                TvPlayerRemoteKeyAction.FocusTransport -> {
                    viewModel.setControlsVisible(true)
                    requestIdleOverlayFocus(TvIdleOverlayFocusTarget.Transport)
                    true
                }
                TvPlayerRemoteKeyAction.SkipBack ->
                    performRelativeSeek(-SKIP_BACK_MS, latestRoomSnapshot, revealControls = true)
                TvPlayerRemoteKeyAction.SkipForward ->
                    performRelativeSeek(SKIP_FORWARD_MS, latestRoomSnapshot, revealControls = true)
                TvPlayerRemoteKeyAction.OpenSettingsHud -> {
                    requestedHudTab = HudTab.Video
                    viewModel.openHUD()
                    true
                }
                TvPlayerRemoteKeyAction.OpenPlaybackHud -> {
                    requestedHudTab = preferredPlaybackHudTab(
                        hasAudioTracks = playerState.audioTracks.isNotEmpty(),
                        hasSubtitleTracks = playerState.subtitleTracks.isNotEmpty(),
                    )
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
                        requestIdleOverlayFocus(TvIdleOverlayFocusTarget.Transport)
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

    // A subtitle or audio change that failed has to say so. Stage, validation,
    // commit, rollback and mount failures all populated subtitleFailureMessage
    // and nothing ever read it: "Applying…" simply vanished and the tick
    // returned to the previous track, which is indistinguishable from the
    // viewer having imagined pressing it. Audio replans share this adapter, so
    // they were equally silent.
    LaunchedEffect(state.subtitleFailureId) {
        val message = state.subtitleFailureMessage ?: return@LaunchedEffect
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        viewModel.onSubtitleFailureShown(state.subtitleFailureId)
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
        // Let the audio route settle before asking media3 to reselect.
        //
        // A reselection is resolved by seeking the current media period, and
        // while an audio sink is being torn down and rebuilt there is no such
        // period — the seek then dereferences a null holder and kills playback
        // outright (MediaPeriodHolder.info in seekToCurrentPosition). A KVM
        // switching inputs produces exactly that: HDMI drops or returns, the
        // sink is rebuilt, and capabilities are re-reported mid-rebuild.
        //
        // Guarding by state cannot see this window — the player looks healthy
        // from here throughout, which is why two previous attempts (#182, #186)
        // did not help. Waiting does: this effect restarts on every capability
        // report, so route churn coalesces into a single application once the
        // reports stop. Only capability *changes* wait; the first application
        // for a backend still runs immediately, because startup track selection
        // must not be deferred.
        if (trackPresetsApplied) delay(TrackSelectionSettleMs)
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
        // Only a REAL application counts. The factory skips silently while the
        // player is idle or unmounted; letting that skip flip the flag would
        // reclassify the true first application as a "later capability change"
        // and defer startup track selection by the settle delay.
        if (backend.applyTrackSelection(
                audioCaps = audioCaps,
                displayHdr = effectiveDisplayHdr,
                preferredAudioLanguage = state.preferredAudioLanguage,
                preferredTextLanguage = state.preferredTextLanguage,
                hdrEnabled = hdrEnabled,
            )
        ) {
            trackPresetsApplied = true
        }
    }

    // HDR display-mode switching: attach the controller to the activity window
    // so we can drive `preferredDisplayModeId` when video size / frame rate
    // becomes known. Released on composition dispose.
    DisposableEffect(context) {
        (context as? Activity)?.let { hdrDisplayController.attach(it) }
        onDispose { hdrDisplayController.restore() }
    }

    // Hold the screen awake only while playback is actually advancing. The
    // flag used to be held for the life of the screen, which on a TV meant a
    // paused player suppressed the system screensaver indefinitely — a static
    // image parked on the panel for hours is exactly what burn-in protection
    // exists to prevent. Mirrors the phone player's gate (same user-visible
    // rule: pause long enough and the screensaver takes over, resume and the
    // screen is held again). Buffering counts as playing so a rebuffer at a
    // scene boundary cannot blank the screen mid-watch.
    val keepScreenAwake = !state.isPaused && (state.isPlaying || state.isBuffering)
    DisposableEffect(context, keepScreenAwake) {
        val window = (context as? Activity)?.window
        if (keepScreenAwake) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    val latestLifecycleRoomSnapshot by rememberUpdatedState(roomSnapshot)

    // Lifecycle pausing — send pause to the service when we're backgrounded.
    DisposableEffect(
        lifecycleOwner,
        mediaController,
        isInPictureInPictureMode,
        playWhenReadyReconciliationGate,
    ) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP -> if (!isInPictureInPictureMode) {
                    mediaController?.let { controller ->
                        if (controller.playWhenReady) {
                            if (
                                playWhenReadyReconciliationGate
                                    .requestProgrammaticChange(false)
                            ) {
                                controller.pause()
                            }
                        }
                    }
                }
                Lifecycle.Event.ON_RESUME -> if (roomController != null) {
                    val desired = latestLifecycleRoomSnapshot?.isPaused?.not()
                    mediaController?.let { controller ->
                        if (
                            desired != null &&
                            (controller.playWhenReady != desired ||
                                playWhenReadyReconciliationGate.hasPendingChanges)
                        ) {
                            if (
                                playWhenReadyReconciliationGate
                                    .requestProgrammaticChange(desired)
                            ) {
                                controller.playWhenReady = desired
                            }
                        }
                    }
                }
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
    // The preflight listener is keyed on the controller and outlives engine
    // swaps; read the service player at error time, not at registration.
    val latestServicePlayerForErrors = rememberUpdatedState(sessionPlayer)
    DisposableEffect(mediaController) {
        val controller = mediaController
        if (controller == null) {
            onDispose { }
        } else {
            val preflight = PlaybackPreflightListener(
                detector = capabilityDetector,
                onUnsupported = { verdict -> viewModel.onUnsupportedPlayback(verdict) },
                onError = { error -> viewModel.onPlayerError(error, servicePlayer = latestServicePlayerForErrors.value) },
                plannedRoute = {
                    val plan = viewModel.uiState.value.playbackPlan
                    org.siloserver.silo.common.player.plannedVideoRouteFor(
                        decisionReason = plan?.decisionTrace?.firstOrNull(),
                        effectiveDynamicRange = plan?.source?.hdrFormat,
                        clientTransformations = plan?.executableMedia3ClientTransformations().orEmpty(),
                    )
                },
            )
            controller.addListener(preflight)
            onDispose { controller.removeListener(preflight) }
        }
    }

    // Player listener → ViewModel. Pushes play/pause state, refreshes the
    // track menu state on track changes, and drives HDMI display-mode
    // switching on video size changes.
    DisposableEffect(
        mediaController,
        state.effectiveFrameRate,
        playWhenReadyReconciliationGate,
    ) {
        val controller = mediaController
        if (controller == null) {
            onDispose { }
        } else {
            val listener = object : Player.Listener {
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    val provenance = playWhenReadyReconciliationGate
                        .onPlayWhenReadyChanged(playWhenReady, reason)
                    provenance.followUpProgrammaticValue?.let { controller.playWhenReady = it }
                    if (!provenance.shouldReconcile) return
                    roomController
                        ?.onExternalPlayWhenReadyChanged(playWhenReady)
                        ?.let { authoritative ->
                            if (controller.playWhenReady != authoritative) {
                                if (
                                    playWhenReadyReconciliationGate
                                        .requestProgrammaticChange(authoritative)
                                ) {
                                    controller.playWhenReady = authoritative
                                }
                            }
                        }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    viewModel.onPlayingChanged(isPlaying)
                    val live = viewModel.uiState.value
                    val key = live.sessionId?.let { sessionId ->
                        "$sessionId:${live.streamUrl}:${live.playbackPlan?.planId.orEmpty()}:" +
                            "${live.playbackPlan?.decisionTrace?.size ?: 0}:${live.transportMountNonce}"
                    }
                    if (key != null) {
                        val rendered = (activePlayerHolder.player.value as? androidx.media3.exoplayer.ExoPlayer)
                            ?.videoDecoderCounters?.renderedOutputBufferCount
                        postResumeStallDetector.onIsPlayingChanged(
                            sessionKey = key,
                            isPlaying = isPlaying,
                            nowMs = SystemClock.elapsedRealtime(),
                            currentPositionMs = controller.currentPosition.coerceAtLeast(0L),
                            renderedOutputBufferCount = rendered,
                        )
                    }
                }
                override fun onRenderedFirstFrame() {
                    startupStallDetector.onFirstFrameRendered()
                    postResumeStallDetector.onFirstFrameRendered()
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
                    val mediaFrameRate = controller.currentTracks.groups
                        .firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.isSelected }
                        ?.let { g ->
                            val mg = g.mediaTrackGroup
                            if (mg.length > 0) mg.getFormat(0).frameRate else 0f
                        } ?: 0f
                    val frameRate = mediaFrameRate
                        .takeIf { it.isFinite() && it > 0f }
                        ?: state.effectiveFrameRate
                        ?: 0f
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
        val timelineWindow = Timeline.Window()
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive && state.sessionId != null) {
                viewModel.onPositionChanged(
                    controller.currentPosition,
                    controller.duration.coerceAtLeast(0L),
                )
                // Mounted-transport extent for the VM's native-first seek
                // decision (see TvPlayerViewModel.mountedSeekableSourceRange).
                // A seekable window with a known length can serve any target
                // it spans without a server reanchor.
                if (!controller.currentTimeline.isEmpty) {
                    controller.currentTimeline.getWindow(
                        controller.currentMediaItemIndex,
                        timelineWindow,
                    )
                    viewModel.onPlayerWindowChanged(
                        isSeekable = timelineWindow.isSeekable,
                        windowEndPlayerMs = if (timelineWindow.durationUs != C.TIME_UNSET) {
                            timelineWindow.durationUs / 1000
                        } else {
                            -1L
                        },
                    )
                }
                delay(500)
            }
        }
    }
    LaunchedEffect(
        mediaController,
        state.sessionId,
        state.streamUrl,
        state.playMethod,
        state.playbackPlan?.planId,
        state.playbackPlan?.decisionTrace?.size,
        state.transportMountNonce,
    ) {
        val controller = mediaController ?: return@LaunchedEffect
        val sessionId = state.sessionId ?: return@LaunchedEffect
        val streamUrl = state.streamUrl ?: return@LaunchedEffect
        val sessionKey = "$sessionId:$streamUrl:${state.playbackPlan?.planId.orEmpty()}:" +
            "${state.playbackPlan?.decisionTrace?.size ?: 0}:${state.transportMountNonce}"
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                val decoderCounters = (sessionPlayer as? androidx.media3.exoplayer.ExoPlayer)?.videoDecoderCounters
                val reason = startupStallDetector.sample(
                    sessionKey = sessionKey,
                    nowMs = SystemClock.elapsedRealtime(),
                    playWhenReady = controller.playWhenReady,
                    isPlaying = controller.isPlaying,
                    isBuffering = controller.playbackState == Player.STATE_BUFFERING,
                    currentPositionMs = controller.currentPosition.coerceAtLeast(0L),
                    bufferedPositionMs = controller.bufferedPosition.coerceAtLeast(0L),
                    decoderInputBufferCount = decoderCounters?.queuedInputBufferCount ?: 0,
                    decoderRenderedOutputBufferCount = decoderCounters?.renderedOutputBufferCount ?: 0,
                    decoderSkippedOutputBufferCount = decoderCounters?.skippedOutputBufferCount ?: 0,
                    decoderDroppedBufferCount = decoderCounters?.droppedBufferCount ?: 0,
                )
                if (reason != null) {
                    Log.i(TAG, "Startup stall fallback: $reason")
                    viewModel.onUnsupportedPlayback(reason)
                    return@repeatOnLifecycle
                }
                val sanitizedSamples = PlaybackRuntimeCorrectionMetrics.consumeDolbyVisionHdr10PlusSamples()
                if (sanitizedSamples > 0 && !dvSanitizerReported) {
                    dvSanitizerReported = true
                    viewModel.onRuntimeCorrection(
                        event = "runtime_correction_applied",
                        correctionId = "client_dv8_hdr10plus_sanitizer_v1",
                        stage = "sample_sanitized",
                        details = mapOf("sample_count" to sanitizedSamples.toString()),
                    )
                }
                when (val recovery = postResumeStallDetector.sample(
                    sessionKey = sessionKey,
                    nowMs = SystemClock.elapsedRealtime(),
                    playWhenReady = controller.playWhenReady,
                    isPlaying = controller.isPlaying,
                    isReady = controller.playbackState == Player.STATE_READY,
                    currentPositionMs = controller.currentPosition.coerceAtLeast(0L),
                    durationMs = controller.duration.coerceAtLeast(0L),
                    renderedOutputBufferCount = decoderCounters?.renderedOutputBufferCount,
                )) {
                    PostResumeVideoStallDetector.Signal.SeekBack -> {
                        controller.seekTo(
                            (controller.currentPosition - PostResumeVideoStallDetector.SEEK_BACK_MS)
                                .coerceAtLeast(0L),
                        )
                        viewModel.onRuntimeCorrection(
                            "runtime_correction_applied",
                            "client_post_resume_video_recovery_v1",
                            "nonzero_seek",
                        )
                    }
                    PostResumeVideoStallDetector.Signal.Reprepare -> {
                        val position = controller.currentPosition.coerceAtLeast(0L)
                        val resume = controller.playWhenReady
                        controller.stop()
                        controller.prepare()
                        controller.seekTo(position)
                        if (resume) controller.play()
                        viewModel.onRuntimeCorrection(
                            "runtime_correction_applied",
                            "client_surface_recovery_v1",
                            "codec_surface_reprepare",
                        )
                    }
                    is PostResumeVideoStallDetector.Signal.Recovered -> viewModel.onRuntimeCorrection(
                        "runtime_correction_succeeded",
                        recovery.correctionId,
                        "rendered_frame_progress",
                    )
                    is PostResumeVideoStallDetector.Signal.Failed -> {
                        viewModel.onRuntimeCorrection(
                            "runtime_correction_failed",
                            recovery.correctionId,
                            "bounded_recovery_exhausted",
                        )
                        // Tell the viewer too. This signal fires once and never
                        // again, so a frozen picture with running audio would
                        // otherwise sit there indefinitely, recorded in
                        // telemetry and invisible on screen.
                        viewModel.onPlaybackRecoveryExhausted()
                    }
                    null -> Unit
                }
                delay(1_000)
            }
        }
    }

    // Prepare the player when a stream URL becomes available.
    // Applies a local audio switch: the track is already in the mounted stream,
    // so it only needs selecting on the player. The ViewModel does not commit
    // on the strength of this call -- AudioTrackManager returns Unit and does
    // nothing silently if the group is gone -- it waits for onTracksChanged to
    // show the target selected.
    LaunchedEffect(videoBackend) {
        val backend = videoBackend ?: return@LaunchedEffect
        viewModel.pendingLocalAudioSelection.collect { request ->
            request ?: return@collect
            backend.selectAudioTrack(
                VideoPlayerTrackEntry(
                    index = request.targetOrdinal,
                    label = "",
                    language = null,
                    isSelected = true,
                ),
            )
        }
    }

    LaunchedEffect(
        videoBackend,
        state.sessionId,
        state.streamUrl,
        state.playMethod,
        state.playbackPlan,
        state.delivery,
        state.startPosition,
        state.transportMountNonce,
    ) {
        if (exitRequested) return@LaunchedEffect
        val backend = videoBackend ?: return@LaunchedEffect
        val url = state.streamUrl ?: return@LaunchedEffect
        val method = state.playMethod ?: return@LaunchedEffect
        val plan = state.playbackPlan
        val delivery = plan?.delivery ?: state.delivery
        val mediaSpec = VideoPlayerMediaSpec(
            contentId = contentId,
            streamUrl = url,
            playMethod = method,
            delivery = delivery,
            serverUrl = state.serverUrl,
            container = state.container,
            subtitles = subtitlesForVideoMediaMount(
                subtitles = state.subtitleUrls,
                playbackPlan = plan,
                subtitleIdentity = state.pendingSubtitleIdentity
                    ?: state.committedSubtitleIdentity,
                preferMuxedTracks = true,
            ),
            title = state.title.ifBlank { null },
            artworkUrl = state.artworkUrl,
            startPositionSeconds = state.startPosition,
            timelineOffsetSeconds = plan?.timeline?.timelineOffsetSeconds ?: 0.0,
            durationSeconds = viewModel.uiState.value.duration.takeIf { it > 0.0 }
                ?: if (plan == null) {
                    mediaController?.duration
                        ?.takeIf { it > 0L }
                        ?.div(1000.0)
                } else {
                    null
                }
                ?: 0.0,
            audioPassthroughCodecs = plan.validatedPassthroughCodecs(),
            requestHeaders = state.requestHeaders,
            expectedDynamicRange = plan?.source?.hdrFormat,
            expectedColorRange = plan.validatedColorRangeFallback(),
            transformations = plan?.executableMedia3ClientTransformations().orEmpty(),
            runtimeCorrections = plan?.runtimeCorrections.orEmpty(),
            activeClaims = plan?.activeOriginalHttpClaims().orEmpty(),
        )
        state.sessionId?.let { sessionId ->
            PlaybackRuntimeCorrectionMetrics.reset()
            dvSanitizerReported = false
            startupStallDetector.onMounted(
                sessionKey = "$sessionId:$url:${plan?.planId.orEmpty()}:" +
                    "${plan?.decisionTrace?.size ?: 0}:${state.transportMountNonce}",
                playMethod = method,
                startPositionMs = mediaSpec.startPositionMs,
                nowMs = SystemClock.elapsedRealtime(),
                clientTransformations = mediaSpec.transformations,
            )
            postResumeStallDetector.onMounted(
                "$sessionId:$url:${plan?.planId.orEmpty()}:" +
                    "${plan?.decisionTrace?.size ?: 0}:${state.transportMountNonce}",
            )
        }
        backend.mount(mediaSpec, playWhenReady = !viewModel.uiState.value.isPaused)
        viewModel.onTransportMountApplied(state.transportMountNonce)
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
            contentId = contentId,
            streamUrl = url,
            playMethod = method,
            delivery = delivery,
            serverUrl = state.serverUrl,
            container = state.container,
            subtitles = subtitlesForVideoMediaMount(
                subtitles = state.subtitleUrls,
                playbackPlan = plan,
                subtitleIdentity = state.pendingSubtitleIdentity
                    ?: state.committedSubtitleIdentity,
                preferMuxedTracks = true,
            ),
            title = state.title.ifBlank { null },
            artworkUrl = state.artworkUrl,
            startPositionSeconds = state.startPosition,
            timelineOffsetSeconds = plan?.timeline?.timelineOffsetSeconds ?: 0.0,
            durationSeconds = viewModel.uiState.value.duration.takeIf { it > 0.0 }
                ?: if (plan == null) {
                    mediaController?.duration
                        ?.takeIf { it > 0L }
                        ?.div(1000.0)
                } else {
                    null
                }
                ?: 0.0,
            audioPassthroughCodecs = plan.validatedPassthroughCodecs(),
            requestHeaders = state.requestHeaders,
            expectedDynamicRange = plan?.source?.hdrFormat,
            expectedColorRange = plan.validatedColorRangeFallback(),
            transformations = plan?.executableMedia3ClientTransformations().orEmpty(),
            runtimeCorrections = plan?.runtimeCorrections.orEmpty(),
            activeClaims = plan?.activeOriginalHttpClaims().orEmpty(),
        )
        backend.refresh(mediaSpec)
    }

    // The single path from the subtitle transaction adapter to the player.
    // Every request carries the owner that armed it, so the acknowledgement can
    // never be dropped for want of one. Mirrors the seekRequests idiom.
    LaunchedEffect(videoBackend) {
        val backend = videoBackend ?: return@LaunchedEffect
        viewModel.subtitleMountRequests.collect { request ->
            if (request.trackIndex == -1) {
                if (backend.selectSubtitle(null)) {
                    viewModel.onSubtitleSelectionApplied(request)
                } else {
                    viewModel.onSubtitleSelectionFailed(request)
                }
                return@collect
            }
            val selectedTrack = viewModel.uiState.value.subtitleTracks
                .firstOrNull { it.index == request.trackIndex }
                ?.toVideoTrackEntry()
            if (selectedTrack != null && backend.selectSubtitle(selectedTrack)) {
                viewModel.onSubtitleSelectionApplied(request)
            } else {
                viewModel.onSubtitleSelectionFailed(request)
            }
        }
    }

    // Remote set_audio_track / set_subtitle_track are latched and resolved in
    // the ViewModel after stable track identities exist. Only the transaction
    // adapter may emit a backend subtitle mount request.

    // Mirror user-intent pause state into the player. Kept separate from the
    // onPlayingChanged listener so a transient buffering stall can't flip the
    // pause icon or cancel the auto-hide timer.
    LaunchedEffect(mediaController, state.isPaused, playWhenReadyReconciliationGate) {
        val controller = mediaController ?: return@LaunchedEffect
        val desired = !state.isPaused
        if (controller.playWhenReady != desired) {
            if (playWhenReadyReconciliationGate.requestProgrammaticChange(desired)) {
                controller.playWhenReady = desired
            }
        }
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
    // forced styling from the ACTIVE track's codec, so an ASS<->SRT switch
    // must re-evaluate (else SRT keeps ass-override=no, or force clobbers
    // authored ASS).
    LaunchedEffect(
        playerViewRef,
        subtitleAppearance,
        sessionPlayer,
        state.videoFillMode,
        state.subtitleTracks.firstOrNull { it.isSelected }?.index,
        state.playbackPlan?.source?.letterboxTopFraction,
        state.playbackPlan?.source?.letterboxBottomFraction,
    ) {
        val pv = playerViewRef ?: return@LaunchedEffect
        subtitleManager.letterbox = LetterboxInsets(
            topFraction = (state.playbackPlan?.source?.letterboxTopFraction ?: 0.0).toFloat(),
            bottomFraction = (state.playbackPlan?.source?.letterboxBottomFraction ?: 0.0).toFloat(),
        )
        subtitleManager.titleSafeFraction = 0.05f
        subtitleManager.applyAppearance(pv, subtitleAppearance)
    }

    // The video branch of the player's `when` below — the only state in which
    // the PlayerView is mounted and video-scoped overlays should draw.
    val videoActive = state.streamUrl != null && !state.isLoading && state.error == null

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
                videoActive = videoActive,
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
            // The outer Box must own focus while the overlay is hidden or the
            // first remote press never reaches onPreviewKeyEvent — the viewer
            // presses once, nothing happens, and presses again.
            requestFocusUntilObserved(
                maxAttempts = TvContentInitialFocusMaxAttempts,
                awaitAttempt = { withFrameNanos { } },
                requestFocus = rootFocus::requestFocus,
                isFocused = { playerRootHasFocus },
            )
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
        state.showNextUp,
    ) {
        // Never auto-hide mid-scrub: hiding the scrubber would tear down the
        // in-flight preview under the user. The timer re-arms once the scrub
        // commits or cancels (isScrubbing flips back to false).
        //
        // Up Next counts down for longer than this timer, and it is a
        // focus-owning surface: letting the timer fire under it hides the
        // controls and pulls focus to the root, off the primary action.
        if (state.showControls && !state.isPaused && !state.hudOpen &&
            !state.showSubtitleMenu && !state.showSubtitleStyleDialog &&
            !state.isScrubbing && !state.showNextUp
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
            .onFocusChanged { playerRootHasFocus = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { event ->
                // Hidden Left/Right is classified from the complete Android
                // press lifecycle by TvPlayerRemoteKeyBridge above. Handling it
                // again here would turn the initial Down into an eager skip and
                // make a 300ms hold impossible to distinguish from a tap.
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
                // Server-unreachable: Retry re-probes then reloads, and Try Anyway
                // bypasses the gate (issue #33). Other errors keep the plain retry.
                onRetry = {
                    if (state.serverUnreachable) viewModel.retryServerReachability()
                    else viewModel.retry()
                },
                secondaryActionLabel = "Try Anyway".takeIf { state.serverUnreachable },
                onSecondaryAction = { viewModel.playIgnoringServerReachability() }
                    .takeIf { state.serverUnreachable },
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
                                // Buffering is surfaced by our own "Buffering"
                                // capsule, not PlayerView's centered spinner.
                                // Enabling both draws two indicators at once.
                                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
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
                            subtitleManager.syncSubtitleVideoBounds(view)
                        },
                    )
                }

                if (!isInPictureInPictureMode && state.showControls && cleanSeekRate == 0 &&
                    !state.hudOpen && !state.showNextUp
                ) {
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
                    TvPlayerClockScope(viewModel) { clock ->
                    TvPlayerIdleOverlay(
                        title = state.title,
                        episodeTag = state.seasonNumber?.let { season ->
                            state.episodeNumber?.let { ep -> "S$season·E$ep" }
                        },
                        positionSec = clock.position,
                        durationSec = clock.duration,
                        isPaused = state.isPaused,
                        isScrubbing = state.isScrubbing,
                        scrubPreviewSec = state.scrubPreviewSec,
                        bufferedAheadSec = bufferedAheadSec,
                        chapters = state.chapters,
                        introRange = state.intro,
                        creditsRange = state.credits,
                        recapRange = state.recap,
                        previewRange = state.preview,
                        // In a room, skip/scrub/seek are routed through the
                        // controller (transport_request → server → broadcast
                        // command → engine applies the seek locally). Solo
                        // playback seeks the MediaController directly.
                        transportEnabled = canSeekInRoom,
                        playPauseEnabled = canPlayPauseInRoom,
                        canToggleAfterCommit = roomController == null,
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
                        focusRequest = idleOverlayFocusRequest,
                        onPlayPause = {
                            if (!canPlayPauseInRoom) return@TvPlayerIdleOverlay
                            if (roomController != null) {
                                roomController.onUserPlayPause()
                            } else {
                                viewModel.onPlayPause()
                            }
                            viewModel.setControlsVisible(true)
                        },
                        // The Tune button IS the cog: same settings entry point
                        // as the remote's Menu/Settings key, so same landing tab.
                        onOpenHUD = {
                            requestedHudTab = HudTab.Video
                            viewModel.openHUD()
                        },
                        onOpenQuickSubtitles = {
                            showQuickSubtitlePicker = true
                            viewModel.setControlsVisible(true)
                        },
                        // Only offered when there is something to advance to;
                        // the view model owns that predicate so the manual
                        // control and the automatic trigger cannot disagree.
                        onUpNext = if (viewModel.canShowNextUpNow()) {
                            { viewModel.onUserRequestedNextUp() }
                        } else {
                            null
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
                        TvPlayerClockScope(viewModel) { clock ->
                        TvPlayerHud(
                            title = state.title,
                            positionSec = clock.position,
                            durationSec = clock.duration,
                            seasonNumber = state.seasonNumber,
                            episodeNumber = state.episodeNumber,
                            audioTracks = state.audioTracks,
                            videoQualities = state.videoQualities,
                            fileVersions = state.fileVersions,
                            selectedFileId = state.selectedFileId ?: state.mediaFileId,
                            onSelectFileVersion = viewModel::onSelectFileVersion,
                            subtitleTracks = state.subtitleTracks,
                            subtitleUrls = state.subtitleUrls,
                            subtitlePresentation = subtitlePresentation,
                            stats = state.stats,
                            playbackPlan = state.playbackPlan,
                            desiredAudioOrdinal = state.desiredAudioOrdinal,
                            desiredAudioConfirmed = state.desiredAudioConfirmed,
                            videoFillMode = state.videoFillMode,
                            onSelectAudio = viewModel::selectAudioOption,
                            onSelectVideoQuality = { id ->
                                // Server-transcode quality ladder (tvOS parity):
                                // re-request the session at the chosen rung.
                                viewModel.switchQuality(id)
                            },
                            onVideoFillModeChanged = viewModel::onVideoFillModeChanged,
                            playbackSpeed = playbackSpeed,
                            onPlaybackSpeedChanged = viewModel::onSetPlaybackSpeed,
                            sleepTimerState = sleepTimerState,
                            onStartSleepTimer = viewModel::onStartSleepTimer,
                            onCancelSleepTimer = viewModel::onCancelSleepTimer,
                            introSkipMode = introSkipMode,
                            onIntroSkipModeChanged = viewModel::onSetIntroSkipMode,
                            autoPlayNext = autoPlayNextEnabled,
                            onAutoPlayNextChanged = viewModel::onSetAutoPlayNext,
                            audioDelayMs = audioDelayMs,
                            audioDelayEnabled = state.playbackPlan?.claims?.audio?.passthrough != true,
                            onAudioDelayChanged = viewModel::onAudioDelayChanged,
                            subtitleDelayMs = subtitleDelayMs,
                            subtitleDelayEnabled =
                                state.committedSubtitleIdentity !is SubtitleIdentity.ServerBurnIn,
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
                            dolbyVisionSwitchInFlight = dolbyVisionSwitchInFlight,
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
                        )
                        }
                    }
                }

                if (!isInPictureInPictureMode && cleanSeekRate != 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 80.dp),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        TvPlayerClockScope(viewModel) { clock ->
                            TvHoldSeekIndicator(
                                isVisible = true,
                                rate = cleanSeekRate,
                                previewTimeSec = cleanSeekPreviewSec,
                                durationSec = clock.duration,
                            )
                        }
                    }
                }

                // Transient skip feedback, for both the hidden-controls D-pad
                // skip and the transport/remote skip that reveals the overlay.
                // Suppressed while the HUD or Up Next own the screen (they
                // provide their own position feedback) and in PiP.
                //
                // ONE render site across both cases on purpose: a reveal-path
                // skip sets the chip and flips showControls in the same handler,
                // and splitting these would tear down one AnimatedVisibility and
                // fade in another mid-transition.
                if (!isInPictureInPictureMode && cleanSeekRate == 0 &&
                    !state.hudOpen && !state.showNextUp
                ) {
                    // Controls hidden: align the transient line with the REAL
                    // scrubber track's position inside the idle overlay, which
                    // stacks (bottom-up): 40dp overlay padding + 33dp transport
                    // cluster + 16dp gap + 8dp spacer + 16dp gap = 113dp to the
                    // scrubber COLUMN's bottom — plus ~6dp because the 3.5dp
                    // track is centered in the column's lower box (41dp minus
                    // label row), not flush with its bottom.
                    //
                    // Controls visible: the live scrubber already reports
                    // position, so the chip drops its own track and rises into
                    // the 42dp gap between that column's top (113 + 41) and the
                    // title block at 196dp. Centred, so it clears the
                    // left-aligned title at any title width.
                    // Horizontal 80dp matches the track width in both cases.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                start = 80.dp,
                                end = 80.dp,
                                bottom = if (state.showControls) 154.dp else 119.dp,
                            ),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        TvSkipSeekIndicator(
                            feedback = skipSeekFeedback,
                            showTrack = !state.showControls,
                        )
                    }
                }

                if (!isInPictureInPictureMode && showQuickSubtitlePicker) {
                    TvQuickSubtitlePicker(
                        presentation = subtitlePresentation,
                        onSelect = subtitlePresentation.onSelect,
                        onSelectionComplete = {
                            applyQuickSubtitlePickerExit(TvQuickSubtitlePickerExit.Selection)
                        },
                        onDismiss = {
                            applyQuickSubtitlePickerExit(TvQuickSubtitlePickerExit.Back)
                        },
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

        TvPlayerOverlays(
            isInPictureInPictureMode = isInPictureInPictureMode,
            notice = notice,
            remoteMessage = remoteMessage,
            roomSnapshot = roomSnapshot,
            roomActive = roomController != null,
            showControls = state.showControls,
            hudOpen = state.hudOpen,
            showLeaveDialog = showLeaveDialog,
            showNextUp = state.showNextUp,
            nextEpisode = state.nextEpisode,
            nextUpVideoEnded = state.nextUpVideoEnded,
            nextUpCountdownSeconds = state.nextUpCountdownSeconds,
            nextUpCountdownTotalSeconds = state.nextUpCountdownTotalSeconds,
            autoPlayNextEnabled = autoPlayNextEnabled,
            introSkipState = introSkipState,
            introSkipCountdownRun = introSkipCountdownRun,
            introSkipTimerRunning = introSkipTimerRunning,
            introSkipTotalSeconds = viewModel.introSkipTotalSeconds,
            // The scrubber commits its seek on focus loss, so the prompt must
            // not take focus out from under an active scrub.
            introBannerMayTakeFocus = !state.isScrubbing && cleanSeekRate == 0,
            videoActive = videoActive,
            isBuffering = state.isBuffering,
            sleepTimerState = sleepTimerState,
            showSpinner = shouldShowReconnectSpinner(
                isReconnecting = sessionState is SessionState.Reconnecting,
                showNextUp = state.showNextUp,
                isInPictureInPictureMode = isInPictureInPictureMode,
            ),
            onCloseRoom = {
                showLeaveDialog = false
                roomController?.leave(closeRoom = true)
                stopPlaybackAndExit()
            },
            onCancelLeaveDialog = { showLeaveDialog = false },
            onPlayNextNow = viewModel::playNextEpisodeNow,
            onKeepWatching = viewModel::dismissNextUp,
            onToggleAutoPlayNext = { viewModel.onSetAutoPlayNext(!autoPlayNextEnabled) },
            onExitPlayback = { stopPlaybackAndExit() },
            onIntroPromptSelect = { handleIntroPromptSelect() },
        )
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
    creditsRange: org.siloserver.silo.model.catalog.TimeRange?,
    recapRange: org.siloserver.silo.model.catalog.TimeRange?,
    previewRange: org.siloserver.silo.model.catalog.TimeRange?,
    onPlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onBeginScrub: () -> Unit,
    onUpdateScrub: (Double) -> Unit,
    onCommitScrub: () -> Unit,
    onCancelScrub: () -> Unit,
    focusRequest: TvIdleOverlayFocusRequest,
    onOpenHUD: () -> Unit,
    onOpenQuickSubtitles: () -> Unit,
    /** Non-null only while a next episode is resolved and not already shown. */
    onUpNext: (() -> Unit)? = null,
    onClose: () -> Unit,
    // Watch Together transport authority. Solo playback leaves both true.
    // A guest who can't seek gets a no-op scrubber/skip; a guest who can't
    // play/pause (host_only policy) gets a no-op play/pause.
    transportEnabled: Boolean = true,
    playPauseEnabled: Boolean = true,
    /**
     * Whether Center may toggle playback after committing a scrub.
     *
     * False in a Watch Together room. There, the commit and the play/pause are
     * two independently launched room requests, and the play/pause carries the
     * live position rather than the committed one — so it can land after the
     * seek and pull every participant back to where the scrub started. Solo
     * playback applies both locally and in order, so it keeps the behaviour.
     */
    canToggleAfterCommit: Boolean = true,
) {
    val scrubberFocus = remember { FocusRequester() }
    val playPauseFocus = remember { FocusRequester() }
    var idleOverlayHasFocus by remember { mutableStateOf(false) }
    // Observed per ROW, not for the overlay as a whole. `idleOverlayHasFocus` is
    // hasFocus on the overlay's root, so it is already true whenever ANY control
    // holds focus — including the scrubber. Using it as the arrival test made
    // every request from inside the overlay a no-op: D-pad Down on the scrub bar
    // asks for the transport, the retry loop sees "already focused" and never
    // requests, and focus stays on the bar. That is why reaching the controls
    // needed a Back (which hides the overlay, clearing the flag) before Down.
    var scrubberHasFocus by remember { mutableStateOf(false) }
    var transportHasFocus by remember { mutableStateOf(false) }
    var currentRate by remember { mutableStateOf(0) }
    LaunchedEffect(focusRequest.nonce) {
        val overlayTarget = when (focusRequest.target) {
            TvIdleOverlayFocusTarget.Scrubber -> scrubberFocus
            TvIdleOverlayFocusTarget.Transport -> playPauseFocus
        }
        requestFocusUntilObserved(
            maxAttempts = TvContentInitialFocusMaxAttempts,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = overlayTarget::requestFocus,
            isFocused = {
                when (focusRequest.target) {
                    TvIdleOverlayFocusTarget.Scrubber -> scrubberHasFocus
                    TvIdleOverlayFocusTarget.Transport -> transportHasFocus
                }
            },
        )
    }

    Box(
        modifier = Modifier
            .onFocusChanged { idleOverlayHasFocus = it.hasFocus }
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
                        playPauseFocus.claimFocusOrReport(
                            target = "player_transport",
                            action = "remote_focus_transport",
                        )
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
                    // OpenPlaybackHud can't originate here — this surface leaves
                    // dpadDownOpensHud off, because with the overlay up Down is
                    // how focus reaches the transport row. Handled so the branch
                    // stays exhaustive if that ever changes.
                    TvPlayerRemoteKeyAction.OpenSettingsHud,
                    TvPlayerRemoteKeyAction.OpenPlaybackHud,
                    -> {
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
                modifier = Modifier.onFocusChanged { scrubberHasFocus = it.hasFocus },
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
                creditsRangeSec = creditsRange
                    ?.takeIf { it.end > it.start }
                    ?.let { it.start..it.end },
                recapRangeSec = recapRange
                    ?.takeIf { it.end > it.start }
                    ?.let { it.start..it.end },
                previewRangeSec = previewRange
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
                onPlayPause = onPlayPause,
                canToggleAfterCommit = canToggleAfterCommit,
                onMoveDownToTransport = {
                    playPauseFocus.claimFocusOrReport(
                        target = "player_transport",
                        action = "scrubber_move_down",
                    )
                },
                onExitWhenIdle = onClose,
                onRateChanged = { currentRate = it },
            )

            Spacer(modifier = Modifier.height(8.dp))

            TvPlayerTransportCluster(
                modifier = Modifier.onFocusChanged { transportHasFocus = it.hasFocus },
                isPlaying = !isPaused,
                onSkipBack = onSkipBack,
                onPlayPause = onPlayPause,
                onSkipForward = onSkipForward,
                onOpenQuickSubtitles = onOpenQuickSubtitles,
                onUpNext = onUpNext,
                onOpenHUD = onOpenHUD,
                onClose = onClose,
                playPauseFocus = playPauseFocus,
                onMoveUpToScrubber = {
                    scrubberFocus.claimFocusOrReport(
                        target = "player_scrubber",
                        action = "transport_move_up",
                    )
                },
            )
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
    presentation: TvSubtitleHudPresentation,
    onSelect: (SubtitleIdentity) -> Unit,
    onSelectionComplete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val checkedRow = presentation.rows.firstOrNull { row -> row.checked }
    val focusedRow = presentation.rows.firstOrNull { row -> row.focused }
    val options = presentation.rows.map { row ->
        HudPickerOption(
            id = row.stableId,
            label = if (row.applying) "${row.label} · Applying…" else row.label,
        )
    }

    // Rendered as an in-window overlay, NOT a Dialog. A Dialog is a separate
    // window; laying a translucent window over the video SurfaceView forces
    // SurfaceFlinger off the hardware overlay onto GPU composition, which
    // stutters playback for a frame or two as the picker appears (visible on
    // Shield). Drawing in the player's own window leaves the video overlay
    // undisturbed. HudPickerDialog self-focuses and traps D-pad internally;
    // Back-to-dismiss is provided here since there's no Dialog to own it.
    BackHandler(enabled = true, onBack = onDismiss)
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
                selectedId = checkedRow?.stableId
                    ?: presentation.rows.firstOrNull()?.stableId.orEmpty(),
                focusedId = focusedRow?.stableId
                    ?: checkedRow?.stableId
                    ?: presentation.rows.firstOrNull()?.stableId.orEmpty(),
                closeOnSelect = false,
                onFocused = presentation.onFocused,
                onSelect = { stableId ->
                    dispatchTvQuickSubtitlePickerSelection(
                        presentation = presentation,
                        stableId = stableId,
                        onSelect = onSelect,
                        onSelectionComplete = onSelectionComplete,
                    )
                },
            ),
            onClose = onDismiss,
        )
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
 * countdown ring (a card raised at the end counts a wall clock to zero and then
 * plays the next episode; one raised at the credits marker mirrors the
 * remaining playback time and waits for the stream to actually end), and
 * finished / loading states when no next episode is available.
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
    var upNextHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(nextEpisode?.contentId, videoEnded) {
        requestFocusUntilObserved(
            maxAttempts = TvContentInitialFocusMaxAttempts,
            awaitAttempt = { withFrameNanos { } },
            requestFocus = primaryFocus::requestFocus,
            isFocused = { upNextHasFocus },
        )
    }

    Box(
        modifier = Modifier
            .onFocusChanged { upNextHasFocus = it.hasFocus }
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
                    // Interactive toggle (was a dead focusable): OK flips
                    // auto-play; focus inverts the pill so the D-pad stop is
                    // visible.
                    var autoPlayToggleFocused by remember { mutableStateOf(false) }
                    androidx.tv.material3.Text(
                        text = "Auto-play is ${if (autoPlayEnabled) "On" else "Off"}",
                        color = if (autoPlayToggleFocused) Color.Black else Color.White.copy(alpha = 0.54f),
                        style = androidx.tv.material3.MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .onFocusChanged { autoPlayToggleFocused = it.isFocused }
                            .clip(RoundedCornerShape(percent = 50))
                            .background(if (autoPlayToggleFocused) Color.White else Color.Transparent)
                            .clickable { onToggleAutoPlay() }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
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
    val closeActionFocus = remember { FocusRequester() }

    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onCancel,
        properties = PopupProperties(
            focusable = true,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            clippingEnabled = false,
        ),
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
                    .padding(40.dp)
                    .then(rememberTvDialogInitialFocus(closeActionFocus)),
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
                    modifier = Modifier
                        .width(360.dp)
                        .focusRequester(closeActionFocus),
                )
                TvDialogActionRow(
                    title = "Keep watching",
                    onClick = onCancel,
                    modifier = Modifier.width(360.dp),
                )
            }
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

private fun PlaybackExecutionPlan?.validatedPassthroughCodecs(): List<String> {
    val plan = this ?: return emptyList()
    return plan.source.audioCodec
        ?.takeIf { plan.claims.audio.passthrough }
        ?.let { listOf(it) }
        .orEmpty()
}

private const val TAG = "TvPlayerScreen"

/**
 * How long a capability change waits before track-selection presets are
 * re-applied, so an audio sink that is being rebuilt is not asked to reselect
 * mid-rebuild. Long enough to cover a KVM input switch settling; short enough
 * that a genuine capability change (AVR powered on, headphones paired) still
 * takes effect while the viewer is watching.
 */
private const val TrackSelectionSettleMs = 1_500L

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
                val hearingImpaired =
                    format.roleFlags and
                        (C.ROLE_FLAG_CAPTION or C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND) != 0 ||
                        subtitleLabelIndicatesHearingImpaired(label)
                result.add(
                    PlayerTrackEntry(
                        index = media3FlatTextIndex,
                        trackId = format.id,
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
                codecOrMime = format?.sampleMimeType ?: format?.codecs,
                channelCount = format?.channelCount?.coerceAtLeast(0) ?: 0,
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

private fun TvPlayerViewModel.UiState.toSiloCastPlaybackState(
    contentId: String,
    playbackSpeed: Double,
    hdrEnabled: Boolean,
    subtitleDelayMs: Int,
    subtitleAppearance: SubtitleAppearance,
    volumeState: SiloCastVolumeState,
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
        volume = volumeState.volume,
        isMuted = volumeState.isMuted,
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

@Composable
private fun TvPlayerClockScope(
    viewModel: TvPlayerViewModel,
    content: @Composable (PlaybackClock) -> Unit,
) {
    val clock by viewModel.playbackClock.collectAsState()
    content(clock)
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


/**
 * The overlay layer stacked above the player surface: lifecycle notice, remote
 * message toast, Watch Together indicator and close confirmation, the Up Next
 * surface, the intro auto-skip banner, and the reconnect spinner.
 *
 * Split out of [TvPlayerScreen] to keep that composable's generated method
 * within ART's JIT limit. Past it the method is never compiled, so the whole
 * player screen runs interpreted and the runtime logs "Method exceeds compiler
 * instruction limit" on every recomposition — roughly once a second during
 * playback.
 */
@Composable
private fun TvPlayerOverlays(
    isInPictureInPictureMode: Boolean,
    notice: PlayerNotice?,
    remoteMessage: RemoteMessage?,
    roomSnapshot: RoomSnapshot?,
    roomActive: Boolean,
    showControls: Boolean,
    hudOpen: Boolean,
    showLeaveDialog: Boolean,
    showNextUp: Boolean,
    nextEpisode: NextEpisodeState?,
    nextUpVideoEnded: Boolean,
    nextUpCountdownSeconds: Int?,
    nextUpCountdownTotalSeconds: Int,
    autoPlayNextEnabled: Boolean,
    introSkipState: IntroAutoSkipState,
    /** Bumps when the pill's timer (re)starts, so its fill re-anchors. */
    introSkipCountdownRun: Int,
    /** False while the pill is up but its timer is frozen by a pause. */
    introSkipTimerRunning: Boolean,
    introSkipTotalSeconds: Int,
    /** False while a scrub owns focus — see TvIntroAutoSkipBanner.mayTakeFocus. */
    introBannerMayTakeFocus: Boolean,
    /** True only while the video branch is composed — not loading, not errored. */
    videoActive: Boolean,
    isBuffering: Boolean,
    sleepTimerState: SleepTimerState,
    showSpinner: Boolean,
    onCloseRoom: () -> Unit,
    onCancelLeaveDialog: () -> Unit,
    onPlayNextNow: () -> Unit,
    onKeepWatching: () -> Unit,
    onToggleAutoPlayNext: () -> Unit,
    onExitPlayback: () -> Unit,
    onIntroPromptSelect: () -> Unit,
) {
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
        if (!isInPictureInPictureMode && roomActive && snapshot != null && showControls && !hudOpen) {
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

        // Top-right status chips: buffering capsule (spinner + "Buffering") and
        // a sleep-timer countdown chip, mirroring tvOS statusColumn. Lives here
        // rather than in the idle overlay so a hidden-controls D-pad seek still
        // reports buffering.
        val sleepRemaining = (sleepTimerState as? SleepTimerState.Active)?.remainingSeconds
        // A stalled player reports buffering during an outage too, but the
        // centered reconnect spinner and the notice toast already say more than
        // the capsule would, so the capsule stands down while that one shows.
        //
        // Otherwise it stays up unconditionally, because PlayerView's own
        // spinner is off (SHOW_BUFFERING_NEVER) and this capsule is now the
        // only buffering feedback there is. In particular it must survive the
        // HUD — picking a quality or version restarts the whole session with
        // the HUD still open (closeOnSelect closes only the picker), which is
        // the longest rebuffer in the app — and Up Next, where video keeps
        // playing behind the mini-player frame until the credits end. Neither
        // surface has a loading state of its own.
        val showBufferingChip = isBuffering && !showSpinner
        // The sleep countdown is ambient rather than urgent, so it yields the
        // corner to the HUD and Up Next instead of competing with them.
        val showSleepChip = sleepRemaining != null && !hudOpen && !showNextUp
        // Chips belong to the playing video. The loading and error screens are
        // separate branches of the player's `when` and own their whole surface,
        // so a stale "Buffering" capsule must not float over either of them —
        // `fail()` sets `error` without clearing `isBuffering`.
        if (!isInPictureInPictureMode && videoActive && (showBufferingChip || showSleepChip)) {
            // The HUD is a top-center card (top 56dp, up to 680dp wide, up to
            // 360dp tall), so at the usual 960dp TV width its right edge runs
            // under the chip's 80dp end inset. Drop below it rather than over
            // it; nothing else occupies that band.
            val chipTopPadding = if (hudOpen) 440.dp else 64.dp
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = chipTopPadding, end = 80.dp)
                    // Up Next composes after this block and paints a
                    // full-screen scrim, so lift the chips above it (still
                    // under the remote-message toast at 10f).
                    .zIndex(5f),
                contentAlignment = Alignment.TopEnd,
            ) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (showBufferingChip) {
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
                    if (showSleepChip && sleepRemaining != null) {
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
        }

        // Host close-confirm dialog. Closing tears the room down for everyone
        // (server emits room_closed → every member exits). Cancel resumes.
        if (!isInPictureInPictureMode && showLeaveDialog && roomActive) {
            TvRoomCloseConfirmDialog(
                onClose = onCloseRoom,
                onCancel = onCancelLeaveDialog,
            )
        }

        // F2 / Up-Next end-of-playback surface. Replaces the old "Still
        // watching?" dialog: a 16:9 mini-player (the still-playing video,
        // visible behind a bordered frame) beside a next-episode panel with
        // Play Now / Keep Watching / Back and an auto-play countdown ring.
        if (!isInPictureInPictureMode) {
            if (showNextUp) {
                TvPlayerNextUpOverlay(
                    nextEpisode = nextEpisode,
                    videoEnded = nextUpVideoEnded,
                    countdownSeconds = nextUpCountdownSeconds,
                    countdownTotalSeconds = nextUpCountdownTotalSeconds,
                    autoPlayEnabled = autoPlayNextEnabled,
                    onPlayNow = onPlayNextNow,
                    onKeepWatching = onKeepWatching,
                    onToggleAutoPlay = onToggleAutoPlayNext,
                    onBack = onExitPlayback,
                )
            }
        }

        // Intro skip pill (bottom-end, above the transport cluster).
        // It must remain visible even when transport controls auto-hide; D-pad
        // Center routes straight to the pill's Select while it is showing, so
        // the viewer does not need a first click just to reveal UI.
        // Bottom inset (200dp) clears the transport cluster + scrubber column.
        if (!isInPictureInPictureMode) {
            if (!hudOpen && !showNextUp) {
                // Sits above the transport cluster while controls are up and
                // drops toward the corner when they hide.
                val introSkipBottomInset by animateDpAsState(
                    targetValue = if (showControls) 200.dp else 56.dp,
                    animationSpec = tween(durationMillis = 220),
                    label = "introSkipBottomInset",
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = introSkipBottomInset, end = 32.dp),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    TvIntroAutoSkipBanner(
                        state = introSkipState,
                        onSelect = onIntroPromptSelect,
                        totalSeconds = introSkipTotalSeconds,
                        countdownRun = introSkipCountdownRun,
                        timerRunning = introSkipTimerRunning,
                        // Not while the viewer is working the timeline: the
                        // scrubber commits its seek on focus loss, so taking
                        // focus here would land a seek they never confirmed.
                        mayTakeFocus = introBannerMayTakeFocus,
                    )
                }
            }
        }

        // Outage spinner. Native ExoPlayer buffering surfaces as the top-right
        // Buffering capsule (mirroring tvOS), so this centered spinner is
        // reserved for the lifecycle Reconnecting state: the server-outage
        // probe loop, which the player itself can't observe. The capsule
        // stands down while this shows, so the two never stack. Up Next keeps
        // the capsule instead, so it gets no centered spinner.
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
