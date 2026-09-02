package org.siloserver.silo.android.ui.screens.player

import android.app.Activity
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
import org.siloserver.silo.common.player.PlayWhenReadyReconciliationGate
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.navigation.NavHostController
import org.siloserver.silo.common.player.ActivePlayerHolder
import org.siloserver.silo.common.player.AudioCapabilityManager
import org.siloserver.silo.common.player.SiloPlaybackService
import org.siloserver.silo.common.player.DisplayHdrProbe
import org.siloserver.silo.common.player.playbackDisplayId
import org.siloserver.silo.common.player.plannedVideoRouteFor
import org.siloserver.silo.common.player.PlaybackCapabilityDetector
import org.siloserver.silo.common.player.PlaybackPreflightListener
import org.siloserver.silo.common.player.RefreshRateMatcher
import org.siloserver.silo.common.player.SessionState
import org.siloserver.silo.common.player.SubtitleManager
import org.siloserver.silo.common.player.VideoPlayerMediaSpec
import org.siloserver.silo.common.player.subtitlesForVideoMediaMount
import org.siloserver.silo.common.player.validatedColorRangeFallback
import org.siloserver.silo.common.pip.SiloPictureInPictureCoordinator
import org.siloserver.silo.common.pip.SiloPictureInPicturePlaybackState
import org.siloserver.silo.common.pip.SiloPictureInPictureSurface
import org.siloserver.silo.common.settings.LetterboxExpansion
import org.siloserver.silo.common.player.backend.VideoPlaybackBackendFactory
import org.siloserver.silo.common.player.backend.VideoPlaybackBackendRequest
import org.siloserver.silo.common.player.video.mountedAudioTracks
import org.siloserver.silo.common.player.video.selectedMountedAudioOrdinal
import org.siloserver.silo.common.player.video.PlaybackStartupStallDetector
import org.siloserver.silo.common.player.video.PlaybackRuntimeCorrectionMetrics
import org.siloserver.silo.common.player.video.PostResumeVideoStallDetector
import org.siloserver.silo.common.player.video.VideoPlayerTrackEntry
import org.siloserver.silo.model.playback.PlayMethod
import org.siloserver.silo.model.playback.PlaybackSourceMetadata
import org.siloserver.silo.model.playback.PlaybackExecutionPlan
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.executableMedia3ClientTransformations
import org.siloserver.silo.model.playback.activeOriginalHttpClaims
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.player.DolbyVisionDetection
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.siloserver.silo.android.cast.SiloCastButton
import org.siloserver.silo.android.cast.SiloCastOverlay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToInt
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.unit.sp

private const val TAG = "PlayerScreen"

/** See the TV screen's copy: a capability change waits this long before track
 *  presets are re-applied, so a sink being rebuilt is not asked to reselect. */
private const val TrackSelectionSettleMs = 1_500L

internal fun shouldClearPlaybackOnControllerDispose(isChangingConfigurations: Boolean): Boolean =
    !isChangingConfigurations

@Composable
private fun PlayerClockScope(
    viewModel: PlayerViewModel,
    content: @Composable (PlaybackClock) -> Unit,
) {
    val clock by viewModel.playbackClock.collectAsState()
    content(clock)
}

private fun media3TextTrackSnapshotKey(tracks: androidx.media3.common.Tracks): String? {
    val textGroups = tracks.groups.filter {
        it.type == androidx.media3.common.C.TRACK_TYPE_TEXT
    }
    if (textGroups.isEmpty()) return null
    return textGroups.mapIndexed { groupIndex, group ->
        buildString {
            append(groupIndex)
            val mediaTrackGroup = group.mediaTrackGroup
            for (trackIndex in 0 until mediaTrackGroup.length) {
                val format = mediaTrackGroup.getFormat(trackIndex)
                append('|')
                append(format.id.orEmpty())
                append(':')
                append(format.label.orEmpty())
                append(':')
                append(format.language.orEmpty())
                append(':')
                append(format.sampleMimeType.orEmpty())
                append(':')
                append(format.codecs.orEmpty())
                append(':')
                append(format.selectionFlags)
                append(':')
                append(format.roleFlags)
            }
        }
    }.joinToString(separator = ";")
}

private fun SubtitleIdentity.requiresMountedMobileSelection(): Boolean =
    this is SubtitleIdentity.ServerSidecar ||
        this is SubtitleIdentity.LocalMedia3 ||
        this is SubtitleIdentity.Downloaded ||
        this is SubtitleIdentity.Embedded

/**
 * Full-screen video player screen.
 *
 * The actual ExoPlayer lives inside [SiloPlaybackService]; this screen
 * drives it via a [MediaController] so the system sees a single session
 * (lock-screen controls, Assistant, headset buttons). The controls overlay
 * is [PlayerOverlay].
 *
 * @param contentId The content ID to play (passed via navigation argument)
 * @param initialFileId Optional explicit file selection from the detail screen
 * @param initialQuality Optional explicit playback-quality ceiling from a deep link
 * @param initialAudioTrackIndex Optional explicit audio selection from the detail screen
 * @param initialSubtitleTrackIndex Optional explicit subtitle selection from the detail screen
 * @param resumePositionOverride Optional start position supplied by the launcher
 * @param navController Navigation controller for back navigation
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    contentId: String,
    initialFileId: Int? = null,
    initialQuality: String? = null,
    initialAudioTrackIndex: Int? = null,
    initialSubtitleTrackIndex: Int? = null,
    resumePositionOverride: Double? = null,
    // Watch Together room id, present when this player was launched into a
    // synchronized room. When set, a RoomSyncController binds this player to the
    // room (clock sync, transport mirroring, gating, room_closed exit).
    roomId: String? = null,
    navController: NavHostController,
    viewModel: PlayerViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val density = LocalDensity.current
    val tabletopPosture = rememberTabletopPlayerPosture(activity)
    val lifecycleOwner = LocalLifecycleOwner.current
    val activePlayerHolder: ActivePlayerHolder = koinInject()
    val pictureInPictureCoordinator: SiloPictureInPictureCoordinator = koinInject()
    val playerSettingsStore: org.siloserver.silo.common.settings.PlayerSettingsStore = koinInject()
    val uiState by viewModel.presentationState.collectAsState()
    val sessionState by viewModel.sessionState.collectAsState()
    val pictureInPictureEnabled by playerSettingsStore.pictureInPictureEnabledFlow.collectAsState(initial = true)
    val isInPictureInPictureMode by pictureInPictureCoordinator.isInPictureInPictureMode.collectAsState()
    val backendFactory: VideoPlaybackBackendFactory = koinInject()
    val audioCapabilityManager: AudioCapabilityManager = koinInject()
    val capabilityDetector: PlaybackCapabilityDetector = koinInject()
    val subtitleManager: SubtitleManager = koinInject()
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
    val refreshRateMatcher = remember { RefreshRateMatcher() }
    val audioCaps by audioCapabilityManager.capabilities.collectAsState()
    var exitRequested by remember { mutableStateOf(false) }
    val startupStallDetector = remember { PlaybackStartupStallDetector() }
    val postResumeStallDetector = remember { PostResumeVideoStallDetector() }
    var dvSanitizerReported by remember { mutableStateOf(false) }
    var pictureInPictureVideoWidth by remember { mutableStateOf(16) }
    var pictureInPictureVideoHeight by remember { mutableStateOf(9) }
    // Display aspect of the decoded frame — coded size corrected for anamorphic
    // pixels, which is what AspectRatioFrameLayout actually fits. Deliberately 0
    // until the first video-size callback, so the letterbox probe measures
    // against the real frame rather than the 16:9 placeholder above.
    var codedVideoAspect by remember { mutableFloatStateOf(0f) }
    var pictureInPictureSourceRect by remember { mutableStateOf<Rect?>(null) }
    var playerRootBounds by remember { mutableStateOf<Rect?>(null) }
    var fastForwardHoldActive by remember { mutableStateOf(false) }
    val originalWindowBrightness = remember(activity) {
        activity?.window?.attributes?.screenBrightness
    }
    var playerBrightnessFraction by remember(activity, originalWindowBrightness) {
        mutableFloatStateOf(
            (
                originalWindowBrightness
                    ?.takeIf { it >= 0f }
                    ?: runCatching {
                        Settings.System.getInt(
                            context.contentResolver,
                            Settings.System.SCREEN_BRIGHTNESS,
                        ) / 255f
                    }.getOrDefault(0.5f)
                ).coerceIn(0f, 1f),
        )
    }

    DisposableEffect(activity, originalWindowBrightness) {
        onDispose {
            val window = activity?.window ?: return@onDispose
            val attributes = window.attributes
            attributes.screenBrightness = originalWindowBrightness ?: -1f
            window.attributes = attributes
        }
    }

    // Google Cast (Chromecast). Distinct from the NSD/mDNS SiloCast device
    // remote. When a Cast session connects, local Media3 is paused and a
    // "casting to <device>" overlay takes over; on disconnect, local playback
    // resumes at the remote position (Fix 6).
    val castManager: org.siloserver.silo.android.cast.SiloCastSessionManager = koinInject()
    val castState by castManager.castState.collectAsState()
    val castScope = rememberCoroutineScope()
    var wasCasting by remember { mutableStateOf(false) }
    val tabletopPaneLayout = remember(tabletopPosture, playerRootBounds, density.density) {
        val posture = tabletopPosture ?: return@remember null
        val rootBounds = playerRootBounds ?: return@remember null
        calculateTabletopPlayerPaneLayout(
            rootTopPx = rootBounds.top,
            rootBottomPx = rootBounds.bottom,
            foldTopPx = posture.foldBounds.top,
            foldBottomPx = posture.foldBounds.bottom,
            foldGuardPx = with(density) { 8.dp.roundToPx() },
        )
    }
    val useTabletopPlayerLayout =
        tabletopPaneLayout != null && !isInPictureInPictureMode && !castState.isConnected

    // Watch Together binding. Built once per roomId; null for solo playback.
    // The process RoomSession owns the WS; this controller owns only the
    // screen's RoomSyncEngine and requests durable teardown on explicit leave.
    val watchTogetherRepository: org.siloserver.silo.repository.WatchTogetherRepository = koinInject()
    val roomSession: org.siloserver.silo.watchtogether.RoomSession = koinInject()
    val roomScope = rememberCoroutineScope()
    val roomController = remember(roomId) {
        roomId?.takeIf { it.isNotBlank() }?.let { id ->
            RoomSyncController(
                roomId = id,
                repository = watchTogetherRepository,
                roomSession = roomSession,
                viewModel = viewModel,
                scope = roomScope,
            )
        }
    }
    fun subtitleTrackEntry(subtitles: List<PlayerSubtitleInfo>, selectedIndex: Int): VideoPlayerTrackEntry? {
        if (selectedIndex < 0) return null
        val subtitle = subtitles.getOrNull(selectedIndex) ?: return null
        return VideoPlayerTrackEntry(
            index = selectedIndex,
            label = subtitle.label ?: subtitle.language ?: "Subtitle ${selectedIndex + 1}",
            language = subtitle.language,
            isSelected = true,
            subtitle = subtitle,
        )
    }
    DisposableEffect(roomController) {
        roomController?.start()
        onDispose {
            // Cancel only this replaceable controller's collectors. The
            // application RoomSession persists until explicit leave.
            roomController?.dispose()
        }
    }
    val roomSnapshot by produceRoomSnapshotState(roomController)
    val roomClosedReason by produceRoomClosedState(roomController)

    // Per-session playback control socket (admin remote control). Bound for the
    // lifetime of a sessionId; the loop reconnects on its own and never
    // interrupts playback. Separate from the Watch Together socket.
    val playbackRealtimeClient: org.siloserver.silo.network.PlaybackRealtimeClient = koinInject()
    LaunchedEffect(uiState.sessionId) {
        val id = uiState.sessionId ?: return@LaunchedEffect
        PlaybackRealtimeController(
            sessionId = id,
            client = playbackRealtimeClient,
            viewModel = viewModel,
            scope = this, // cancelled when sessionId changes / screen leaves
        ).start()
    }
    // Tell the VM about WT membership so the control socket gates transport
    // (the room is authoritative); the VM's start request always has roomId=null.
    LaunchedEffect(roomId) {
        viewModel.setInWatchTogetherRoom(!roomId.isNullOrBlank())
    }
    // A remote "stop"/"terminate" command tears the screen down like a back press.
    LaunchedEffect(Unit) {
        viewModel.remoteStopRequests.collect {
            exitRequested = true
            roomController?.leave(closeRoom = false)
            viewModel.onExit()
            // Nothing behind the player (launcher/deep-link/notification open) →
            // popBackStack can't land anywhere and leaves a blank NavHost, then
            // system back exits from a grey screen. Finish cleanly instead.
            if (!navController.popBackStack()) activity?.finish()
        }
    }

    // room_closed (or server error) → leave the player back to detail.
    LaunchedEffect(roomClosedReason) {
        if (roomClosedReason != null && roomController != null) {
            exitRequested = true
            roomController.leave(closeRoom = false)
            viewModel.onExit()
            // Nothing behind the player (launcher/deep-link/notification open) →
            // popBackStack can't land anywhere and leaves a blank NavHost, then
            // system back exits from a grey screen. Finish cleanly instead.
            if (!navController.popBackStack()) activity?.finish()
        }
    }

    // Surface transient Watch Together server rejections (e.g. a guest seek the
    // server refuses) as a brief Toast. These flow on the repo errors stream and
    // do NOT eject the user (room_closed is the only terminal signal). Only
    // collected while bound to a room.
    LaunchedEffect(roomController) {
        if (roomController != null) {
            watchTogetherRepository.errors.collect { message ->
                if (message.isNotBlank()) {
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // MediaController connection is async — it returns a ListenableFuture that
    // resolves when the service binds. We hold the controller in a compose
    // state so downstream effects can re-run once it's ready.
    var mediaController by remember { mutableStateOf<MediaController?>(null) }
    val playWhenReadyReconciliationGate = remember(mediaController, roomId) {
        PlayWhenReadyReconciliationGate()
    }
    // service. The video PlayerView binds its SurfaceView directly to this (NOT the
    // MediaController) so the engine receives proper surface-lifecycle callbacks
    // (surfaceCreated/Changed/Destroyed) and recovers across seek/recreate/rotation
    // underlying player). Re-binds automatically when the engine swaps.
    val sessionPlayer by activePlayerHolder.player.collectAsState()
    val backendPlayer = sessionPlayer ?: mediaController
    val videoBackend = remember(backendPlayer, backendFactory) {
        backendPlayer?.let { player ->
            backendFactory.create(
                player = player,
                request = VideoPlaybackBackendRequest(),
            )
        }
    }
    // A neutral-v3 replan publishes replacement route state before the
    // corresponding Compose mount effect runs. Subtitle restoration must wait
    // for that exact media generation rather than racing a newly mounted route.
    var mountedMediaGeneration by remember(videoBackend) { mutableStateOf<Long?>(null) }
    // False until presets have been applied once for the current backend, so
    // only later capability changes wait for the route to settle.
    var trackPresetsApplied by remember(videoBackend) { mutableStateOf(false) }

    // Applies a local audio switch. The ViewModel does not commit on this call:
    // AudioTrackManager returns Unit and does nothing silently when the group is
    // absent, so it waits for a snapshot showing the target selected.
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

    LaunchedEffect(videoBackend) {
        videoBackend?.let { backend ->
            viewModel.onBackendCapabilities(backend.capabilities)
        }
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
            val shouldClearPlayback = shouldClearPlaybackOnControllerDispose(
                isChangingConfigurations = activity?.isChangingConfigurations == true,
            )
            // A configuration recreation releases only this controller connection;
            // the retained ViewModel and service player keep the active session.
            // A real destination exit still clears Media3 before releasing.
            mediaController?.let { controller ->
                if (shouldClearPlayback) {
                    runCatching {
                        controller.pause()
                        controller.stop()
                        controller.clearMediaItems()
                    }
                }
                controller.release()
            }
            mediaController = null
            if (!future.isDone) future.cancel(true)
        }
    }

    // While a cast session is live, opening ANY item routes it to the receiver:
    // once the local player knows its file id, stage a cast session for it
    // (idempotent — castState.fileId flips to the staged file, ending the loop).
    LaunchedEffect(castState.isConnected, castState.fileId, uiState.mediaFileId) {
        val fileId = uiState.mediaFileId
        if (castState.isConnected && fileId != null && castState.fileId != fileId) {
            // prepareGoogleCastMedia is single-flight, so this may resolve to
            // the same spec the cast-button path is already staging — recheck
            // the LIVE cast state before loading to avoid a duplicate load.
            val spec = viewModel.prepareGoogleCastMedia()
            if (spec != null && castManager.castState.value.fileId != spec.fileId) {
                castManager.prepareMedia(spec)
            }
        }
    }

    // Cast connect/disconnect → pause/resume local Media3. On connect, pause
    // locally (the dongle is now the surface). On disconnect, resume where the
    // remote left off (Fix 6: getLastPosition).
    LaunchedEffect(castState.isConnected) {
        if (castState.isConnected && !wasCasting) {
            wasCasting = true
            viewModel.remotePause()
            mediaController?.let { controller ->
                if (controller.playWhenReady) {
                    if (playWhenReadyReconciliationGate.requestProgrammaticChange(false)) {
                        controller.pause()
                    }
                } else {
                    controller.pause()
                }
            }
        } else if (!castState.isConnected && wasCasting) {
            wasCasting = false
            val resumeAt = castManager.getLastPosition()
            if (resumeAt > 0.0) viewModel.remoteSeek(resumeAt)
            viewModel.remoteUnpause()
            mediaController?.let { controller ->
                if (!controller.playWhenReady) {
                    if (playWhenReadyReconciliationGate.requestProgrammaticChange(true)) {
                        controller.play()
                    }
                } else {
                    controller.play()
                }
            }
        }
    }

    val hdrEnabled by viewModel.hdrEnabled.collectAsState()
    val preferredPlaybackSpeed by viewModel.playbackSpeed.collectAsState()

    // Apply capability-aware track selection presets. Re-runs on capability
    // or profile-language change so a mid-session HDMI hot-plug / Bluetooth
    // pair rebuilds the preferred-MIME ordering. When the user has disabled
    // HDR via the playback settings sheet, we hand the presets builder an
    // empty HdrCapabilities so the track selector stops preferring HDR
    // tracks (and the refresh-rate matcher's HDR branches stay quiet).
    LaunchedEffect(
        mediaController,
        videoBackend,
        audioCaps,
        uiState.preferredAudioLanguage,
        uiState.preferredTextLanguage,
        hdrEnabled,
    ) {
        val backend = videoBackend ?: return@LaunchedEffect
        // Let the audio route settle first — see the TV screen's copy of this.
        // Here the route change is a headphone unplug or a Bluetooth drop
        // rather than an HDMI switch, but the failure is the same: a
        // reselection during a sink rebuild has no media period to seek.
        if (trackPresetsApplied) delay(TrackSelectionSettleMs)
        // Only a REAL application counts — see the TV screen's copy.
        if (backend.applyTrackSelection(
                audioCaps = audioCaps,
                displayHdr = if (hdrEnabled) displayHdr else org.siloserver.silo.model.playback.HdrCapabilities(),
                preferredAudioLanguage = uiState.preferredAudioLanguage,
                preferredTextLanguage = uiState.preferredTextLanguage,
                hdrEnabled = hdrEnabled,
            )
        ) {
            trackPresetsApplied = true
        }
    }

    // Mirror the user's preferred playback speed onto the live MediaController,
    // with hold-to-2x as a transient override that never writes the setting.
    // Re-runs whenever the controller binds, the setting changes, or the user
    // presses/releases the phone player's fast-forward hold gesture.
    LaunchedEffect(mediaController, preferredPlaybackSpeed, fastForwardHoldActive) {
        val controller = mediaController ?: return@LaunchedEffect
        if (fastForwardHoldActive) {
            controller.setPlaybackSpeed(2.0f)
        } else {
            controller.setPlaybackSpeed(preferredPlaybackSpeed.toFloat())
        }
    }

    // Load content on first composition
    LaunchedEffect(contentId, initialFileId, initialQuality, initialAudioTrackIndex, initialSubtitleTrackIndex, resumePositionOverride) {
        if (!viewModel.claimInitialRouteLoad()) return@LaunchedEffect
        viewModel.loadContent(
            contentId = contentId,
            preferredFileId = initialFileId,
            preferredQuality = initialQuality,
            initialAudioTrackIndex = initialAudioTrackIndex,
            initialSubtitleTrackIndex = initialSubtitleTrackIndex,
            resumePositionOverride = resumePositionOverride,
            routeResumePositionSeconds = resumePositionOverride,
            // Watch Together's synced anchor must land exactly — don't nudge it back.
            suppressResumeRewind = !roomId.isNullOrBlank(),
        )
    }

    // Immersive mode: hide system bars
    DisposableEffect(activity) {
        if (activity != null) {
            val window = activity.window
            val insetsController = WindowCompat.getInsetsController(window, window.decorView)
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

            refreshRateMatcher.attach(activity)

            onDispose {
                refreshRateMatcher.restore()
                insetsController.show(WindowInsetsCompat.Type.systemBars())
                // Explicitly UNSPECIFIED, never a captured "original": with
                // stacked player entries (e.g. opening items while casting) the
                // capture could be the landscape lock a previous player set,
                // leaving the app stuck horizontal after exit.
                activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        } else {
            onDispose { }
        }
    }

    // Keep the screen awake only while video is actively playing. ExoPlayer —
    // unlike iOS AVPlayer (preventsDisplaySleepDuringVideoPlayback) — does not
    // manage the display, so without this the system sleep timer dims/locks the
    // screen mid-playback. Held during play and buffering, released the moment
    // playback pauses and when leaving the player, so it never keeps the screen
    // on during paused video or menus (user report: Pixel 8 Pro / S25 Ultra).
    val keepScreenAwake = !uiState.isPaused && (uiState.isPlaying || uiState.isBuffering)
    DisposableEffect(activity, keepScreenAwake) {
        val window = activity?.window
        if (keepScreenAwake) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Orientation policy (iOS PlayerOrientationCoordinator parity): entering
    // the player locks to landscape by default; the persisted "rotateFreely"
    // opt-out (HUD lock toggle / synced setting) falls back to USER so the
    // system rotation preference stays in charge. Android 16 ignores requested
    // orientation on 600dp+ displays, so those layouts stay adaptive and the
    // overlay disables the lock affordance instead of claiming a no-op lock.
    // Released on exit by the immersive effect's UNSPECIFIED restore above.
    // Wait for the persisted preference before touching the activity: the
    // resolved flow is null until it arrives, and applying the eager locked
    // default on the first frame would snap rotateFreely users back to
    // landscape on every player entry.
    val smallestScreenWidthDp = LocalConfiguration.current.smallestScreenWidthDp
    val orientationLockSupported = supportsPlayerOrientationLock(
        sdkInt = Build.VERSION.SDK_INT,
        smallestScreenWidthDp = smallestScreenWidthDp,
    )
    val orientationLockedResolved by viewModel.orientationLockedResolved.collectAsState()
    LaunchedEffect(
        activity,
        orientationLockedResolved,
        castState.isConnected,
        orientationLockSupported,
        tabletopPosture,
    ) {
        // While casting, the screen shows the cast takeover panel, not video —
        // no reason to force landscape (and it must unlock if already forced).
        // Large Android 16 displays likewise own their orientation by platform
        // policy. Tabletop posture also owns its physical orientation; forcing
        // landscape can rotate a horizontal hinge back into book posture.
        if (castState.isConnected || !orientationLockSupported || tabletopPosture != null) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            return@LaunchedEffect
        }
        val locked = orientationLockedResolved ?: return@LaunchedEffect
        activity?.requestedOrientation = if (locked) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_USER
        }
    }

    // Set up the media item when stream URL becomes available
    LaunchedEffect(
        videoBackend,
        uiState.sessionId,
        uiState.streamUrl,
        uiState.playMethod,
        uiState.playbackPlan,
        uiState.delivery,
        uiState.startPosition,
        uiState.mediaMountGeneration,
    ) {
        if (exitRequested) return@LaunchedEffect
        val backend = videoBackend ?: return@LaunchedEffect
        val streamUrl = uiState.streamUrl ?: return@LaunchedEffect
        val playMethod = uiState.playMethod ?: return@LaunchedEffect
        if (!viewModel.shouldApplyMediaMount(uiState.mediaMountGeneration)) return@LaunchedEffect
        val serverUrl = uiState.serverUrl
        // serverUrl is intentionally empty for local-file playback (offline
        // path sets streamUrl to a local file/content URI and serverUrl=""). For remote playback
        // we still need to wait for the server session to resolve.
        val isLocalMedia = streamUrl.startsWith("file://") || streamUrl.startsWith("content://")
        if (!isLocalMedia && serverUrl.isEmpty()) return@LaunchedEffect

        // Transport selection belongs to PlayerViewModel. In particular, never
        // replace an online V3 stream with a downloaded URI here: doing so would
        // mount local bytes while the ViewModel continued to map seeks through
        // the server plan's source/player timeline. The offline-first path
        // already publishes its local URI with no session or playback plan.
        val effectiveStreamUrl = streamUrl
        val plan = uiState.playbackPlan
        val delivery = plan?.delivery ?: uiState.delivery

        val mediaSpec = VideoPlayerMediaSpec(
            contentId = uiState.contentId,
            streamUrl = effectiveStreamUrl,
            // Local files play as progressive (DIRECT), regardless of how
            // the server originally provisioned the session.
            playMethod = playMethod,
            delivery = delivery,
            serverUrl = serverUrl,
            container = uiState.container,
            subtitles = subtitlesForVideoMediaMount(
                subtitles = uiState.subtitleTracks,
                playbackPlan = plan,
                subtitleIdentity = uiState.localSubtitleMountIdentity
                    ?: uiState.committedSubtitleIdentity,
            ),
            title = uiState.title.ifBlank { null },
            subtitle = uiState.subtitle.ifBlank { null },
            artworkUrl = uiState.artworkUrl,
            startPositionSeconds = uiState.startPosition,
            timelineOffsetSeconds = plan?.timeline?.timelineOffsetSeconds ?: 0.0,
            durationSeconds = viewModel.uiState.value.duration,
            audioPassthroughCodecs = plan.validatedPassthroughCodecs(),
            requestHeaders = uiState.requestHeaders,
            expectedDynamicRange = plan?.source?.hdrFormat,
            expectedColorRange = plan.validatedColorRangeFallback(),
            transformations = plan?.executableMedia3ClientTransformations().orEmpty(),
            runtimeCorrections = plan?.runtimeCorrections.orEmpty(),
            activeClaims = plan?.activeOriginalHttpClaims().orEmpty(),
        )
        if (!isLocalMedia && uiState.sessionId != null) {
            PlaybackRuntimeCorrectionMetrics.reset()
            dvSanitizerReported = false
            startupStallDetector.onMounted(
                sessionKey = "${uiState.sessionId}:$effectiveStreamUrl:${plan?.planId.orEmpty()}:" +
                    "${plan?.decisionTrace?.size ?: 0}:${uiState.mediaMountGeneration}",
                playMethod = playMethod,
                startPositionMs = mediaSpec.startPositionMs,
                nowMs = SystemClock.elapsedRealtime(),
                clientTransformations = mediaSpec.transformations,
            )
            postResumeStallDetector.onMounted(
                "${uiState.sessionId}:$effectiveStreamUrl:${plan?.planId.orEmpty()}:" +
                    "${plan?.decisionTrace?.size ?: 0}:${uiState.mediaMountGeneration}",
            )
        }
        backend.mount(mediaSpec, playWhenReady = !viewModel.uiState.value.isPaused)
        mountedMediaGeneration = uiState.mediaMountGeneration
        viewModel.onMediaMountApplied(uiState.mediaMountGeneration)
    }

    // A new mount leaves the previous item's frame in the SurfaceView until the
    // new stream decodes its own, and its geometry describes that stale frame.
    // Forgetting it gates the letterbox probe off until Media3 re-reports a
    // video size — which it does as the new stream produces its first output —
    // so the outgoing episode's matte can never settle, or be cached, under the
    // incoming one's key. The PiP dimensions above are deliberately kept: they
    // size a window that must not collapse mid-transition.
    LaunchedEffect(uiState.mediaMountGeneration) {
        codedVideoAspect = 0f
    }

    // Mid-playback subtitle refresh (downloaded / AI-generated tracks).
    // Subtitle configs are baked into the MediaItem at build time, so when
    // refreshSubtitles merges new tracks it bumps subtitleRefreshNonce and we
    // ask the shared mounter to refresh the SAME stream's MediaItem with the
    // enlarged subtitle list while preserving position/playWhenReady.
    // The session is NOT restarted (web parity).
    LaunchedEffect(videoBackend, uiState.subtitleRefreshNonce) {
        if (exitRequested) return@LaunchedEffect
        if (uiState.subtitleRefreshNonce == 0) return@LaunchedEffect
        val backend = videoBackend ?: return@LaunchedEffect
        if (!viewModel.claimSubtitleRefresh(uiState.subtitleRefreshNonce)) return@LaunchedEffect
        val streamUrl = uiState.streamUrl ?: return@LaunchedEffect
        val playMethod = uiState.playMethod ?: return@LaunchedEffect

        val isLocalMedia = streamUrl.startsWith("file://") || streamUrl.startsWith("content://")
        val effectiveStreamUrl = streamUrl
        val plan = uiState.playbackPlan
        val delivery = plan?.delivery ?: uiState.delivery

        val mediaSpec = VideoPlayerMediaSpec(
            contentId = uiState.contentId,
            streamUrl = effectiveStreamUrl,
            playMethod = playMethod,
            delivery = delivery,
            serverUrl = uiState.serverUrl,
            container = uiState.container,
            subtitles = subtitlesForVideoMediaMount(
                subtitles = uiState.subtitleTracks,
                playbackPlan = plan,
                subtitleIdentity = uiState.localSubtitleMountIdentity
                    ?: uiState.committedSubtitleIdentity,
            ),
            title = uiState.title.ifBlank { null },
            subtitle = uiState.subtitle.ifBlank { null },
            artworkUrl = uiState.artworkUrl,
            startPositionSeconds = uiState.startPosition,
            timelineOffsetSeconds = plan?.timeline?.timelineOffsetSeconds ?: 0.0,
            durationSeconds = viewModel.uiState.value.duration,
            audioPassthroughCodecs = if (!isLocalMedia) {
                plan.validatedPassthroughCodecs()
            } else {
                emptyList()
            },
            requestHeaders = if (!isLocalMedia) uiState.requestHeaders else emptyMap(),
            expectedDynamicRange = plan?.source?.hdrFormat,
            expectedColorRange = plan.validatedColorRangeFallback(),
            transformations = plan?.executableMedia3ClientTransformations().orEmpty(),
            runtimeCorrections = plan?.runtimeCorrections.orEmpty(),
            activeClaims = plan?.activeOriginalHttpClaims().orEmpty(),
        )
        backend.refresh(mediaSpec)
    }

    // Sync play/pause from ViewModel to player without reclassifying this
    // programmatic write as a MediaSession/user room command.
    LaunchedEffect(mediaController, uiState.isPaused, playWhenReadyReconciliationGate) {
        val controller = mediaController ?: return@LaunchedEffect
        val desired = !uiState.isPaused
        if (controller.playWhenReady != desired) {
            if (playWhenReadyReconciliationGate.requestProgrammaticChange(desired)) {
                controller.playWhenReady = desired
            }
        }
    }

    // Preflight listener: evaluates the resolved Tracks and triggers the
    // transcode fallback when the selected track combo can't actually be
    // played on this device.
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
                // Runtime errors (decoder init, source, mid-stream IO) walk the
                // same recovery ladder as preflight failures — previously the
                // mobile player dropped these on the floor and the screen sat
                // on a stale frame.
                onError = { error -> viewModel.onPlayerError(error, servicePlayer = latestServicePlayerForErrors.value) },
                plannedRoute = {
                    val plan = viewModel.uiState.value.playbackPlan
                    plannedVideoRouteFor(
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

    // Player event listener to feed state back to ViewModel + track video size for PiP
    DisposableEffect(mediaController, videoBackend, playWhenReadyReconciliationGate) {
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
                            "${live.playbackPlan?.decisionTrace?.size ?: 0}:${live.mediaMountGeneration}"
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
                    viewModel.onBufferingChanged(playbackState == Player.STATE_BUFFERING)
                    if (playbackState == Player.STATE_ENDED) {
                        viewModel.onPlayingChanged(false)
                        // F2 fallback: surface (or upgrade) the Up Next card if
                        // no credits/prompt-seconds crossing fired first.
                        viewModel.onApproachingEnd(videoEnded = true)
                    }
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int,
                ) {
                    // Media3 emits this for completed seeks even while paused,
                    // when onIsPlayingChanged and playback-state callbacks can
                    // remain silent. Publish the settled engine position now.
                    viewModel.onPositionChanged(
                        newPosition.positionMs,
                        controller.duration,
                        controller.bufferedPosition.coerceAtLeast(0L),
                    )
                }

                override fun onVideoSizeChanged(size: VideoSize) {
                    if (size.width > 0 && size.height > 0) {
                        pictureInPictureVideoWidth = size.width
                        pictureInPictureVideoHeight = size.height
                        val pixelAspect = size.pixelWidthHeightRatio.takeIf { it > 0f } ?: 1f
                        codedVideoAspect = size.width.toFloat() / size.height * pixelAspect
                        // Pull frame rate off the selected video track; phone
                        // panels with multiple refresh rates switch to
                        // content-matching (seamless only — see ExoPlayer's
                        // VIDEO_CHANGE_FRAME_RATE_STRATEGY_ONLY_IF_SEAMLESS).
                        val frameRate = controller.currentTracks.groups
                            .firstOrNull {
                                it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO && it.isSelected
                            }
                            ?.let { g ->
                                val mg = g.mediaTrackGroup
                                if (mg.length > 0) mg.getFormat(0).frameRate else 0f
                            } ?: 0f
                        if (frameRate > 0f) {
                            refreshRateMatcher.applyForFrameRate(frameRate)
                        }
                    }
                }

                override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                    // Audio was never published here, so a direct-play file with
                    // several audio tracks could show the chosen row while the
                    // renderer stayed on Media3's default.
                    viewModel.onMountedAudioChanged(
                        mounted = mountedAudioTracks(tracks),
                        selectedOrdinal = selectedMountedAudioOrdinal(tracks),
                    )
                    // Re-apply the subtitle selection once track groups resolve:
                    // after the subtitle-refresh rebuild the selection effect has
                    // already fired (against the OLD tracks), so without this the
                    // auto-selected downloaded/AI track never engages. Reads the
                    // live VM state — `uiState` here can be a stale closure capture.
                    val liveState = viewModel.uiState.value
                    val pendingIdentity = liveState.localSubtitleMountIdentity
                    val targetIdentity = pendingIdentity ?: liveState.committedSubtitleIdentity
                    val selected = videoBackend?.selectMountedSubtitle(
                        identity = targetIdentity,
                    ) == true
                    if (pendingIdentity != null) {
                        viewModel.onPendingSubtitleMountResult(
                            identity = pendingIdentity,
                            selected = selected,
                            snapshotKey = media3TextTrackSnapshotKey(tracks),
                            settled = videoBackend?.player?.playbackState == Player.STATE_READY,
                        )
                    }
                }
            }
            controller.addListener(listener)
            onDispose { controller.removeListener(listener) }
        }
    }

    // Lifecycle-bounded position ticker. It samples mounted media while paused
    // too, so a paused seek settles within 500ms even on devices that omit a
    // position-discontinuity callback. Lifecycle stop/controller disconnect
    // still bounds the loop so it never polls a released Player.
    LaunchedEffect(mediaController, lifecycleOwner) {
        val controller = mediaController ?: return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                if (controller.mediaItemCount > 0) {
                    viewModel.onPositionChanged(
                        controller.currentPosition,
                        controller.duration,
                        controller.bufferedPosition.coerceAtLeast(0L),
                    )
                }
                delay(500)
            }
        }
    }

    LaunchedEffect(
        mediaController,
        uiState.sessionId,
        uiState.streamUrl,
        uiState.playMethod,
        uiState.playbackPlan?.planId,
        uiState.playbackPlan?.decisionTrace?.size,
        uiState.mediaMountGeneration,
    ) {
        val controller = mediaController ?: return@LaunchedEffect
        val sessionId = uiState.sessionId ?: return@LaunchedEffect
        val streamUrl = uiState.streamUrl ?: return@LaunchedEffect
        val sessionKey = "$sessionId:$streamUrl:${uiState.playbackPlan?.planId.orEmpty()}:" +
            "${uiState.playbackPlan?.decisionTrace?.size ?: 0}:${uiState.mediaMountGeneration}"
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
                    is PostResumeVideoStallDetector.Signal.Failed -> viewModel.onRuntimeCorrection(
                        "runtime_correction_failed",
                        recovery.correctionId,
                        "bounded_recovery_exhausted",
                    )
                    null -> Unit
                }
                delay(1_000)
            }
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

    // User/app seeks are explicit commands from the ViewModel. Progress
    // samples update uiState.position for display only and must never feed
    // back into MediaController.seekTo.
    LaunchedEffect(mediaController) {
        val controller = mediaController ?: return@LaunchedEffect
        viewModel.seekRequests.collect { posSec ->
            controller.seekTo((posSec * 1000).toLong())
        }
    }

    // Room-driven corrective seeks remain on a separate channel so sync
    // corrections can stay unconditional and easy to reason about.
    LaunchedEffect(mediaController) {
        val controller = mediaController ?: return@LaunchedEffect
        viewModel.immediateSeeks.collect { posSec ->
            controller.seekTo((posSec * 1000).toLong())
        }
    }

    // Handle subtitle selection
    LaunchedEffect(
        videoBackend,
        uiState.subtitleTracks,
        uiState.selectedSubtitleIndex,
        uiState.committedSubtitleIdentity,
        uiState.localSubtitleMountIdentity,
        uiState.mediaMountGeneration,
        mountedMediaGeneration,
    ) {
        val backend = videoBackend ?: return@LaunchedEffect
        if (mountedMediaGeneration != uiState.mediaMountGeneration) return@LaunchedEffect
        val pendingIdentity = uiState.localSubtitleMountIdentity
        val targetIdentity = pendingIdentity ?: uiState.committedSubtitleIdentity
        val selectedIndex = resolveMobileSubtitleOrdinal(targetIdentity, uiState.subtitleTracks)
            ?: uiState.selectedSubtitleIndex
        if (targetIdentity is SubtitleIdentity.ServerBurnIn) {
            // Burn-in pixels are already part of the video stream. Keep the
            // Media3 text renderer explicitly disabled and never manufacture
            // an empty sidecar entry or refresh the mounted MediaItem.
            backend.selectMountedSubtitle(identity = SubtitleIdentity.Off)
        } else if (targetIdentity.requiresMountedMobileSelection()) {
            val selected = backend.selectMountedSubtitle(identity = targetIdentity)
            if (pendingIdentity != null) {
                viewModel.onPendingSubtitleMountResult(
                    identity = pendingIdentity,
                    selected = selected,
                    snapshotKey = media3TextTrackSnapshotKey(backend.player.currentTracks),
                    // This composition-side attempt can race Media3's first
                    // text-track publication. Only onTracksChanged callbacks
                    // provide settlement evidence; the adapter then requires
                    // the same non-empty snapshot twice before failing.
                    settled = false,
                )
            }
        } else {
            backend.selectSubtitle(subtitleTrackEntry(uiState.subtitleTracks, selectedIndex))
        }
    }

    LaunchedEffect(
        activity,
        mediaController,
        pictureInPictureEnabled,
        uiState.streamUrl,
        uiState.isLoading,
        uiState.error,
        uiState.isPlaying,
        uiState.isPaused,
        pictureInPictureVideoWidth,
        pictureInPictureVideoHeight,
        pictureInPictureSourceRect,
    ) {
        pictureInPictureCoordinator.updatePlaybackState(
            activity = activity,
            surface = SiloPictureInPictureSurface.Mobile,
            state = SiloPictureInPicturePlaybackState(
                enabled = pictureInPictureEnabled,
                videoActive = uiState.streamUrl != null && !uiState.isLoading && uiState.error == null,
                isPlaying = uiState.isPlaying && !uiState.isPaused,
                videoWidth = pictureInPictureVideoWidth,
                videoHeight = pictureInPictureVideoHeight,
                sourceRectHint = pictureInPictureSourceRect,
            ),
        )
    }

    DisposableEffect(activity) {
        onDispose {
            pictureInPictureCoordinator.clearPlaybackState(activity, SiloPictureInPictureSurface.Mobile)
        }
    }

    // UI
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                val next = Rect(
                    bounds.left.roundToInt(),
                    bounds.top.roundToInt(),
                    bounds.right.roundToInt(),
                    bounds.bottom.roundToInt(),
                )
                if (playerRootBounds != next) playerRootBounds = next
            },
    ) {
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else if (uiState.error != null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(32.dp),
                ) {
                    Text(
                        text = uiState.error ?: "Unknown error",
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    // Server-unreachable gets Retry (fresh probe + reload) plus a
                    // Try Anyway escape hatch; generic errors keep the bare message.
                    if (uiState.serverUnreachable) {
                        Button(onClick = { viewModel.retryServerReachability() }) {
                            Text("Retry")
                        }
                        OutlinedButton(onClick = { viewModel.playIgnoringServerReachability() }) {
                            Text("Try Anyway")
                        }
                    }
                }
            }
        } else {
            val controller = mediaController
            val videoGravity by viewModel.videoGravity.collectAsState()
            var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
            val letterboxExpansion by viewModel.letterboxExpansion.collectAsState()
            // The camera only reaches the picture once expansion pushes it out
            // to the edges — at FIT's 2560px the pillarbox already swallows it.
            // Read the platform's resolved cutout rather than deriving it from
            // rotation: it already knows which edge the camera is on in THIS
            // rotation (they are opposite edges in the two landscapes), and it
            // accounts for waterfall edges and multiple cutouts too.
            val layoutDirection = LocalLayoutDirection.current
            // Fill and Stretch are the user asking for the whole display, camera
            // and all, exactly as they are excluded from expansion below — so
            // they are not insetted either.
            val explicitFullScreenGravity = videoGravity == "fill" || videoGravity == "stretch"
            val cutoutSideInsetPx = if (
                letterboxExpansion == LetterboxExpansion.ClearOfCamera && !explicitFullScreenGravity
            ) {
                val cutout = WindowInsets.displayCutout
                cutoutSafeHorizontalInset(
                    cutoutLeftPx = cutout.getLeft(density, layoutDirection),
                    cutoutRightPx = cutout.getRight(density, layoutDirection),
                )
            } else {
                0
            }
            // Scope films ship as a 2.39:1 image inside a 16:9 frame, and a
            // 1.90:1 title ships the same way, so a plain fit fits the encoded
            // black too. This measures that matte and reports the aspect of the
            // picture hiding inside the frame — the coded aspect itself when
            // there is nothing to discount. Off wherever the video is not what
            // is on screen, and off for the gravities the user has already
            // decided for themselves.
            val letterboxContentAspect = rememberLetterboxContentAspect(
                playerView = playerViewRef,
                enabled = letterboxExpansion != LetterboxExpansion.Off &&
                    !explicitFullScreenGravity &&
                    !isInPictureInPictureMode &&
                    !castState.isConnected &&
                    !useTabletopPlayerLayout,
                videoAspect = codedVideoAspect,
                mediaKey = uiState.mediaMountGeneration,
                cacheKey = letterboxMatteCacheKey(
                    // Downloads carry no server URL by design, and content and
                    // media-file ids are server-scoped, so keying them on the
                    // rest of the tuple alone would let two servers' downloads
                    // share an entry. The local URI names those stored bytes
                    // exactly, and is stable across plays of the download.
                    origin = uiState.serverUrl.ifBlank {
                        uiState.streamUrl
                            ?.takeIf { it.startsWith("file://") || it.startsWith("content://") }
                            .orEmpty()
                    },
                    contentId = uiState.contentId,
                    mediaFileId = uiState.mediaFileId,
                    codedWidth = pictureInPictureVideoWidth,
                    codedHeight = pictureInPictureVideoHeight,
                ),
            )
            // Expanding means giving the surface the shape of the PICTURE rather
            // than of the coded frame, and letting the frame overflow it. The
            // surface box below is that shape; ZOOM then scales the frame to
            // cover it, which lands the clip inside the encoded matte by
            // construction rather than by a threshold.
            val letterboxExpanding = codedVideoAspect > 0f &&
                letterboxContentAspect > codedVideoAspect
            val resizeMode = when {
                videoGravity == "fill" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                videoGravity == "stretch" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                letterboxExpanding -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            val subtitleAppearance by viewModel.subtitleAppearance.collectAsState()

            // Re-apply user subtitle styling whenever the PlayerView mounts or the
            // appearance flow emits a new value. The PlayerView's `subtitleView`
            // is a child added on first inflation, so the apply must happen at
            // least once after the AndroidView factory runs.
            LaunchedEffect(playerViewRef, subtitleAppearance, sessionPlayer, resizeMode, uiState.selectedSubtitleIndex) {
                val pv = playerViewRef ?: return@LaunchedEffect
                subtitleManager.applyAppearance(pv, subtitleAppearance)
            }

            val activeTabletopPaneLayout = tabletopPaneLayout.takeIf {
                useTabletopPlayerLayout
            }
            val cutoutInsetDp = with(density) { cutoutSideInsetPx.toDp() }
            val videoSurfaceModifier = when {
                activeTabletopPaneLayout != null ->
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(with(density) { activeTabletopPaneLayout.videoHeightPx.toDp() })
                // Expanding means giving the surface the shape of the PICTURE
                // rather than of the coded frame. `aspectRatio` IS the fit: it
                // takes the full width when the picture is wider than what is
                // available and the full height when it is narrower, which is
                // exactly the rule, both cases, no branch. It must NOT be
                // preceded by fillMaxSize, which would pin the constraints and
                // leave it nothing to choose between.
                letterboxExpanding ->
                    Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = cutoutInsetDp)
                        .aspectRatio(letterboxContentAspect)
                // Shrinking the available area is what keeps the camera off a
                // picture that reaches the edges on its own.
                else -> Modifier.fillMaxSize().padding(horizontal = cutoutInsetDp)
            }

            if (controller != null) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            this.resizeMode = resizeMode
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                            playerViewRef = this
                        }
                    },
                    update = { view ->
                        // Bind the SurfaceView directly to the real session player
                        // callbacks; re-binds automatically when the engine swaps.
                        view.player = sessionPlayer
                        view.resizeMode = resizeMode
                        subtitleManager.syncSubtitleVideoBounds(view)
                    },
                    modifier = videoSurfaceModifier
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
                )
            }

            // In tabletop posture the regular PlayerOverlay is deliberately
            // constrained to the controls pane. Keep playback/reconnection
            // feedback on the video itself instead of showing a spinner below
            // the hinge among the transport controls.
            if (activeTabletopPaneLayout != null &&
                (uiState.isBuffering || sessionState is SessionState.Reconnecting)
            ) {
                Box(
                    modifier = videoSurfaceModifier,
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(56.dp),
                        color = Color.White,
                        strokeWidth = 3.dp,
                    )
                }
            }

            // Cast takeover surface — replaces the video AND the local player
            // controls while a Cast session is live. The local controls must not
            // render on top of it: their seek bar / gestures drive the paused
            // LOCAL player, which reads as broken buttons while casting. The
            // overlay carries its own back arrow (exit the player screen, cast
            // keeps running via the Cast SDK media notification) and play/pause
            // + stop controls.
            if (castState.isConnected) {
                SiloCastOverlay(
                    castState = castState,
                    posterUrl = uiState.artworkUrl,
                    onPlayPause = { castManager.togglePlayback() },
                    onSkipBack = { castManager.skipBy(-30.0) },
                    onSkipForward = { castManager.skipBy(30.0) },
                    onSelectSubtitle = { castManager.selectSubtitleTrack(it) },
                    onStopCasting = { castManager.disconnect() },
                    onSeek = { castManager.seekTo(it) },
                    onBack = {
                        exitRequested = true
                        roomController?.leave(closeRoom = roomSnapshot?.isHost == true)
                        viewModel.onExit()
                        if (!navController.popBackStack()) activity?.finish()
                    },
                )
            }

            if (!isInPictureInPictureMode && !castState.isConnected) {
                val playerOverlayModifier = if (activeTabletopPaneLayout != null) {
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(with(density) { activeTabletopPaneLayout.controlsHeightPx.toDp() })
                } else {
                    Modifier.fillMaxSize()
                }
                PlayerClockScope(viewModel) { clock ->
                    PlayerOverlay(
                        state = uiState.withPlaybackClock(clock),
                        viewModel = viewModel,
                        roomSnapshot = roomSnapshot,
                        orientationLockSupported =
                            orientationLockSupported && activeTabletopPaneLayout == null,
                        alwaysShowControls = activeTabletopPaneLayout != null,
                        tabletopMode = activeTabletopPaneLayout != null,
                        tabletopPaneHeight = activeTabletopPaneLayout?.let { layout ->
                            with(density) { layout.controlsHeightPx.toDp() }
                        },
                        brightnessFraction = playerBrightnessFraction,
                        onSetBrightness = { fraction ->
                            val appliedBrightness = fraction.coerceIn(0.02f, 1f)
                            playerBrightnessFraction = appliedBrightness
                            activity?.window?.let { window ->
                                val attributes = window.attributes
                                attributes.screenBrightness = appliedBrightness
                                window.attributes = attributes
                            }
                        },
                        showBufferingIndicator = activeTabletopPaneLayout == null,
                        castSlot = {
                            SiloCastButton(
                                castManager = castManager,
                                onStartCast = {
                                    castScope.launch {
                                        val spec = viewModel.prepareGoogleCastMedia()
                                        if (spec != null) castManager.prepareMedia(spec)
                                    }
                                },
                            )
                        },
                        isFastForwardHoldActive = fastForwardHoldActive,
                        onBack = {
                            // In-room exit: leave the room (host close confirm is handled
                            // by the overlay before this fires). The controller resets the
                            // repo + engine; solo playback just pops.
                            exitRequested = true
                            roomController?.leave(closeRoom = roomSnapshot?.isHost == true)
                            viewModel.onExit()
                            // Nothing behind the player (launcher/deep-link/notification
                            // open) → popBackStack can't land anywhere and leaves a blank
                            // NavHost, then system back exits from a grey screen. Finish
                            // cleanly instead.
                            if (!navController.popBackStack()) activity?.finish()
                        },
                        onPlayPause = {
                            // In a room, route through transport_request (gated to
                            // controllers); solo playback toggles locally.
                            if (roomController != null) roomController.onUserPlayPause()
                            else viewModel.onPlayPause()
                        },
                        onSeek = { position ->
                            if (roomController != null) {
                                // Guest seeks are no-ops in the controller; host seeks
                                // round-trip through the room and re-apply via a command.
                                roomController.onUserSeek(position)
                            } else {
                                viewModel.onSeek(position)
                            }
                        },
                        onToggleControls = { viewModel.onToggleControls() },
                        onFastForwardHold = { active -> fastForwardHoldActive = active },
                        onSelectSubtitle = { viewModel.onSelectSubtitle(it) },
                        onSelectAudio = { viewModel.onSelectAudio(it) },
                        onSelectVersion = { viewModel.onSelectVersion(it) },
                        modifier = playerOverlayModifier,
                    )
                }
            }
        }

        // Non-fatal quality/version-switch message: a dismissable pill over the
        // still-playing video, not a fatal full-screen error.
        uiState.versionSwitchMessage?.let { message ->
            Surface(
                color = Color(0xFFB45309).copy(alpha = 0.94f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 16.dp)
                    .widthIn(max = 360.dp)
                    .clickable { viewModel.dismissVersionSwitchMessage() },
            ) {
                Text(
                    text = message,
                    color = Color.White,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/**
 * Collects the room snapshot from [controller] when present, else holds null.
 * A nullable StateFlow can't be `collectAsState`d directly, so this bridges to
 * a stable [State] for both the in-room and solo cases.
 */
@Composable
private fun produceRoomSnapshotState(
    controller: RoomSyncController?,
): State<RoomSnapshot?> {
    val soloRoom = remember { MutableStateFlow<RoomSnapshot?>(null) }
    return (controller?.room ?: soloRoom).collectAsState()
}

@Composable
private fun produceRoomClosedState(
    controller: RoomSyncController?,
): State<String?> {
    val soloClosedReason = remember { MutableStateFlow<String?>(null) }
    return (controller?.closedReason ?: soloClosedReason).collectAsState()
}


private fun PlaybackExecutionPlan?.validatedPassthroughCodecs(): List<String> {
    val plan = this ?: return emptyList()
    return plan.source.audioCodec
        ?.takeIf { plan.claims.audio.passthrough }
        ?.let { listOf(it) }
        .orEmpty()
}
