package org.siloserver.silo.android.ui.screens.player

import android.app.Activity
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.Player
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
import org.siloserver.silo.common.player.PlaybackCapabilityDetector
import org.siloserver.silo.common.player.PlaybackPreflightListener
import org.siloserver.silo.common.player.RefreshRateMatcher
import org.siloserver.silo.common.player.SubtitleManager
import org.siloserver.silo.common.player.VideoPlayerMediaSpec
import org.siloserver.silo.common.pip.SiloPictureInPictureCoordinator
import org.siloserver.silo.common.pip.SiloPictureInPicturePlaybackState
import org.siloserver.silo.common.pip.SiloPictureInPictureSurface
import org.siloserver.silo.common.player.backend.VideoPlaybackBackendFactory
import org.siloserver.silo.common.player.backend.VideoPlaybackBackendRequest
import org.siloserver.silo.common.player.video.PlaybackStartupStallDetector
import org.siloserver.silo.common.player.video.PlaybackRuntimeCorrectionMetrics
import org.siloserver.silo.common.player.video.PostResumeVideoStallDetector
import org.siloserver.silo.common.player.video.VideoPlayerTrackEntry
import org.siloserver.silo.model.playback.PlayMethod
import org.siloserver.silo.model.playback.PlaybackSourceMetadata
import org.siloserver.silo.model.playback.PlaybackExecutionPlan
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.executableMedia3ClientTransformations
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.player.DolbyVisionDetection
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.roundToInt
import org.koin.compose.koinInject
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.ui.unit.sp

private const val TAG = "PlayerScreen"

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
    viewModel: PlayerViewModel = koinInject(),
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val lifecycleOwner = LocalLifecycleOwner.current
    val activePlayerHolder: ActivePlayerHolder = koinInject()
    val pictureInPictureCoordinator: SiloPictureInPictureCoordinator = koinInject()
    val playerSettingsStore: org.siloserver.silo.common.settings.PlayerSettingsStore = koinInject()
    val uiState by viewModel.uiState.collectAsState()
    val pictureInPictureEnabled by playerSettingsStore.pictureInPictureEnabledFlow.collectAsState(initial = true)
    val isInPictureInPictureMode by pictureInPictureCoordinator.isInPictureInPictureMode.collectAsState()
    val backendFactory: VideoPlaybackBackendFactory = koinInject()
    val audioCapabilityManager: AudioCapabilityManager = koinInject()
    val capabilityDetector: PlaybackCapabilityDetector = koinInject()
    val subtitleManager: SubtitleManager = koinInject()
    val displayHdr = remember { DisplayHdrProbe.probe(context) }
    val refreshRateMatcher = remember { RefreshRateMatcher() }
    val audioCaps by audioCapabilityManager.capabilities.collectAsState()
    var exitRequested by remember { mutableStateOf(false) }
    val startupStallDetector = remember { PlaybackStartupStallDetector() }
    val postResumeStallDetector = remember { PostResumeVideoStallDetector() }
    var dvSanitizerReported by remember { mutableStateOf(false) }
    var pictureInPictureVideoWidth by remember { mutableStateOf(16) }
    var pictureInPictureVideoHeight by remember { mutableStateOf(9) }
    var pictureInPictureSourceRect by remember { mutableStateOf<Rect?>(null) }
    var fastForwardHoldActive by remember { mutableStateOf(false) }

    // Watch Together binding. Built once per roomId; null for solo playback.
    // The controller owns the room WS connection + RoomSyncEngine for the
    // lifetime of this screen and tears them down on explicit leave.
    val watchTogetherRepository: org.siloserver.silo.repository.WatchTogetherRepository = koinInject()
    val roomScope = rememberCoroutineScope()
    val roomController = remember(roomId) {
        roomId?.takeIf { it.isNotBlank() }?.let { id ->
            RoomSyncController(
                roomId = id,
                repository = watchTogetherRepository,
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
        onDispose { /* repo teardown happens on explicit leave (onBack) */ }
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
    // service. The video PlayerView binds its SurfaceView directly to this (NOT the
    // MediaController) so the engine receives proper surface-lifecycle callbacks
    // (surfaceCreated/Changed/Destroyed) and recovers across seek/recreate/rotation
    // underlying player). Re-binds automatically when the engine swaps.
    val sessionPlayer by activePlayerHolder.player.collectAsState()
    val videoBackend = remember(
        sessionPlayer,
        mediaController,
        backendFactory,
        contentId,
        initialFileId,
        uiState.playMethod,
        uiState.playbackPlan,
        uiState.delivery,
        uiState.container,
        uiState.streamUrl,
    ) {
        val plan = uiState.playbackPlan
        val delivery = plan?.delivery ?: uiState.delivery
        (sessionPlayer ?: mediaController)?.let { player ->
            backendFactory.create(
                player = player,
                request = VideoPlaybackBackendRequest(),
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
            // Stop and clear media items before releasing the controller —
            // releasing the controller alone leaves the service's player
            // running, so audio keeps playing after the screen exits.
            // Mirrors TvPlayerScreen.stopPlaybackAndExit semantics.
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
        backend.applyTrackSelection(
            audioCaps = audioCaps,
            displayHdr = if (hdrEnabled) displayHdr else org.siloserver.silo.model.playback.HdrCapabilities(),
            preferredAudioLanguage = uiState.preferredAudioLanguage,
            preferredTextLanguage = uiState.preferredTextLanguage,
            hdrEnabled = hdrEnabled,
        )
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
        viewModel.loadContent(
            contentId = contentId,
            preferredFileId = initialFileId,
            preferredQuality = initialQuality,
            initialAudioTrackIndex = initialAudioTrackIndex,
            initialSubtitleTrackIndex = initialSubtitleTrackIndex,
            resumePositionOverride = resumePositionOverride,
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

            val originalOrientation = activity.requestedOrientation
            refreshRateMatcher.attach(activity)

            onDispose {
                refreshRateMatcher.restore()
                insetsController.show(WindowInsetsCompat.Type.systemBars())
                activity.requestedOrientation = originalOrientation
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
    // system rotation preference stays in charge. Released on exit by the
    // immersive effect's originalOrientation restore above.
    // Wait for the persisted preference before touching the activity: the
    // resolved flow is null until it arrives, and applying the eager locked
    // default on the first frame would snap rotateFreely users back to
    // landscape on every player entry.
    val orientationLockedResolved by viewModel.orientationLockedResolved.collectAsState()
    LaunchedEffect(activity, orientationLockedResolved) {
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
            streamUrl = effectiveStreamUrl,
            // Local files play as progressive (DIRECT), regardless of how
            // the server originally provisioned the session.
            playMethod = playMethod,
            delivery = delivery,
            serverUrl = serverUrl,
            container = uiState.container,
            subtitles = uiState.subtitleTracks,
            title = uiState.title.ifBlank { null },
            subtitle = uiState.subtitle.ifBlank { null },
            artworkUrl = uiState.artworkUrl,
            startPositionSeconds = uiState.startPosition,
            durationSeconds = uiState.duration,
            audioPassthroughCodecs = plan.validatedPassthroughCodecs(),
            requestHeaders = uiState.requestHeaders,
            transformations = plan?.executableMedia3ClientTransformations().orEmpty(),
            runtimeCorrections = plan?.runtimeCorrections.orEmpty(),
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
            )
            postResumeStallDetector.onMounted(
                "${uiState.sessionId}:$effectiveStreamUrl:${plan?.planId.orEmpty()}:" +
                    "${plan?.decisionTrace?.size ?: 0}:${uiState.mediaMountGeneration}",
            )
        }
        backend.mount(mediaSpec, playWhenReady = !viewModel.uiState.value.isPaused)
        viewModel.onMediaMountApplied(uiState.mediaMountGeneration)
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
        val streamUrl = uiState.streamUrl ?: return@LaunchedEffect
        val playMethod = uiState.playMethod ?: return@LaunchedEffect

        val isLocalMedia = streamUrl.startsWith("file://") || streamUrl.startsWith("content://")
        val effectiveStreamUrl = streamUrl
        val plan = uiState.playbackPlan
        val delivery = plan?.delivery ?: uiState.delivery

        val mediaSpec = VideoPlayerMediaSpec(
            streamUrl = effectiveStreamUrl,
            playMethod = playMethod,
            delivery = delivery,
            serverUrl = uiState.serverUrl,
            container = uiState.container,
            subtitles = uiState.subtitleTracks,
            title = uiState.title.ifBlank { null },
            subtitle = uiState.subtitle.ifBlank { null },
            artworkUrl = uiState.artworkUrl,
            startPositionSeconds = uiState.startPosition,
            durationSeconds = uiState.duration,
            audioPassthroughCodecs = if (!isLocalMedia) {
                plan.validatedPassthroughCodecs()
            } else {
                emptyList()
            },
            requestHeaders = if (!isLocalMedia) uiState.requestHeaders else emptyMap(),
            transformations = plan?.executableMedia3ClientTransformations().orEmpty(),
            runtimeCorrections = plan?.runtimeCorrections.orEmpty(),
        )
        backend.refresh(mediaSpec)
    }

    // Sync play/pause from ViewModel to player
    LaunchedEffect(mediaController, uiState.isPaused) {
        mediaController?.playWhenReady = !uiState.isPaused
    }

    // Preflight listener: evaluates the resolved Tracks and triggers the
    // transcode fallback when the selected track combo can't actually be
    // played on this device.
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
                onError = { error -> viewModel.onPlayerError(error) },
            )
            controller.addListener(preflight)
            onDispose { controller.removeListener(preflight) }
        }
    }

    // Player event listener to feed state back to ViewModel + track video size for PiP
    DisposableEffect(mediaController) {
        val controller = mediaController
        if (controller == null) {
            onDispose { }
        } else {
            val listener = object : Player.Listener {
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
                    // Re-apply the subtitle selection once track groups resolve:
                    // after the subtitle-refresh rebuild the selection effect has
                    // already fired (against the OLD tracks), so without this the
                    // auto-selected downloaded/AI track never engages. Reads the
                    // live VM state — `uiState` here can be a stale closure capture.
                    val liveState = viewModel.uiState.value
                    videoBackend?.selectMountedSubtitle(
                        subtitles = liveState.subtitleTracks,
                        selectedIndex = liveState.selectedSubtitleIndex,
                    )
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
    LaunchedEffect(videoBackend, uiState.subtitleTracks, uiState.selectedSubtitleIndex) {
        val backend = videoBackend ?: return@LaunchedEffect
        if (backend.selectSubtitle(subtitleTrackEntry(uiState.subtitleTracks, uiState.selectedSubtitleIndex))) {
            viewModel.onSubtitleSelectionApplied(uiState.selectedSubtitleIndex)
        }
    }

    // Notify the ViewModel we're leaving the screen.
    DisposableEffect(Unit) {
        onDispose { viewModel.onExit() }
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
            .background(Color.Black),
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
                Text(
                    text = uiState.error ?: "Unknown error",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(32.dp),
                )
            }
        } else {
            val controller = mediaController
            val videoGravity by viewModel.videoGravity.collectAsState()
            val resizeMode = when (videoGravity) {
                "fill" -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                "stretch" -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            var playerViewRef by remember { mutableStateOf<PlayerView?>(null) }
            val subtitleAppearance by viewModel.subtitleAppearance.collectAsState()

            // Re-apply user subtitle styling whenever the PlayerView mounts or the
            // appearance flow emits a new value. The PlayerView's `subtitleView`
            // is a child added on first inflation, so the apply must happen at
            // least once after the AndroidView factory runs.
            LaunchedEffect(playerViewRef, subtitleAppearance, sessionPlayer, resizeMode, uiState.selectedSubtitleIndex) {
                val pv = playerViewRef ?: return@LaunchedEffect
                subtitleManager.applyAppearance(pv, subtitleAppearance)
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
                )
            }

            if (!isInPictureInPictureMode) {
                PlayerOverlay(
                    state = uiState,
                    viewModel = viewModel,
                    roomSnapshot = roomSnapshot,
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
                )
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
