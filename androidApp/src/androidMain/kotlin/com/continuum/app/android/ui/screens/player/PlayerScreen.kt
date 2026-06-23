package com.continuum.app.android.ui.screens.player

import android.app.Activity
import android.content.ComponentName
import android.content.pm.ActivityInfo
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.ViewGroup
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
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import android.os.Bundle
import com.continuum.app.common.player.backend.PlaybackEngineCommand
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.navigation.NavHostController
import com.continuum.app.common.player.ActivePlayerHolder
import com.continuum.app.common.player.AudioCapabilityManager
import com.continuum.app.common.player.ContinuumPlaybackService
import com.continuum.app.common.player.DisplayHdrProbe
import com.continuum.app.common.player.PlaybackCapabilityDetector
import com.continuum.app.common.player.PlaybackPreflightListener
import com.continuum.app.common.player.RefreshRateMatcher
import com.continuum.app.common.player.SubtitleManager
import com.continuum.app.common.player.VideoPlayerMediaSpec
import com.continuum.app.common.player.backend.VideoPlaybackBackendFactory
import com.continuum.app.common.player.backend.VideoPlaybackBackendRequest
import com.continuum.app.common.player.backend.VideoPlaybackFormFactor
import com.continuum.app.common.player.backend.isLikelyAdaptiveHlsStreamUrl
import com.continuum.app.common.player.video.PlaybackStartupStallDetector
import com.continuum.app.common.player.video.VideoPlayerTrackEntry
import com.continuum.app.common.player.video.isMpvPreferredOriginalPlaybackContainer
import com.continuum.app.model.playback.PlayMethod
import com.continuum.app.model.playback.PlaybackExecutionPlan
import com.continuum.app.model.playback.PlayerSubtitleInfo
import com.continuum.app.model.watchtogether.RoomSnapshot
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import org.koin.compose.koinInject

private const val TAG = "PlayerScreen"

/**
 * Full-screen video player screen.
 *
 * The actual ExoPlayer lives inside [ContinuumPlaybackService]; this screen
 * drives it via a [MediaController] so the system sees a single session
 * (lock-screen controls, Assistant, headset buttons). The controls overlay
 * is [PlayerOverlay].
 *
 * @param contentId The content ID to play (passed via navigation argument)
 * @param initialFileId Optional explicit file selection from the detail screen
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
    val uiState by viewModel.uiState.collectAsState()
    val backendFactory: VideoPlaybackBackendFactory = koinInject()
    val audioCapabilityManager: AudioCapabilityManager = koinInject()
    val capabilityDetector: PlaybackCapabilityDetector = koinInject()
    val downloadStorage: com.continuum.app.common.downloads.DownloadStorage = koinInject()
    val serverRegistry: com.continuum.app.network.ServerRegistry = koinInject()
    val profileRepository: com.continuum.app.repository.ProfileRepository = koinInject()
    val subtitleManager = remember { SubtitleManager() }
    val displayHdr = remember { DisplayHdrProbe.probe(context) }
    val refreshRateMatcher = remember { RefreshRateMatcher() }
    val audioCaps by audioCapabilityManager.capabilities.collectAsState()
    var exitRequested by remember { mutableStateOf(false) }
    val startupStallDetector = remember { PlaybackStartupStallDetector() }

    // Watch Together binding. Built once per roomId; null for solo playback.
    // The controller owns the room WS connection + RoomSyncEngine for the
    // lifetime of this screen and tears them down on explicit leave.
    val watchTogetherRepository: com.continuum.app.repository.WatchTogetherRepository = koinInject()
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
    val playbackRealtimeClient: com.continuum.app.network.PlaybackRealtimeClient = koinInject()
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
            navController.popBackStack()
        }
    }

    // room_closed (or server error) → leave the player back to detail.
    LaunchedEffect(roomClosedReason) {
        if (roomClosedReason != null && roomController != null) {
            exitRequested = true
            viewModel.onExit()
            navController.popBackStack()
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
    // The real session Player (ExoPlayer/MpvPlayer), published by the playback
    // service. The video PlayerView binds its SurfaceView directly to this (NOT the
    // MediaController) so the engine receives proper surface-lifecycle callbacks
    // (surfaceCreated/Changed/Destroyed) and recovers across seek/recreate/rotation
    // — fixing MPV black-on-seek. Transport stays on the MediaController (same
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
                request = VideoPlaybackBackendRequest(
                    contentId = contentId,
                    fileId = initialFileId,
                    playMethod = uiState.playMethod,
                    delivery = delivery,
                    plannedEngine = plan?.engine,
                    routeFamily = plan?.routeFamily,
                    formFactor = VideoPlaybackFormFactor.Mobile,
                    hasHardContainer = uiState.playMethod == PlayMethod.DIRECT &&
                        isHardPlaybackContainer(uiState.container),
                    hasStyledSubtitles = uiState.subtitleTracks.any { it.isStyledSubtitle() },
                    isAdaptiveHlsStream = isLikelyAdaptiveHlsStreamUrl(uiState.streamUrl),
                ),
            )
        }
    }

    DisposableEffect(context) {
        val sessionToken = SessionToken(
            context,
            ComponentName(context, ContinuumPlaybackService::class.java),
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
            displayHdr = if (hdrEnabled) displayHdr else com.continuum.app.model.playback.HdrCapabilities(),
            preferredAudioLanguage = uiState.preferredAudioLanguage,
            preferredTextLanguage = uiState.preferredTextLanguage,
            hdrEnabled = hdrEnabled,
        )
    }

    // Mirror the user's preferred playback speed onto the live MediaController.
    // Re-runs whenever the controller binds (so the value is applied after
    // service rebind) or the user picks a new speed in PlayerSettingsSheet.
    LaunchedEffect(mediaController) {
        val controller = mediaController ?: return@LaunchedEffect
        viewModel.playbackSpeed.collect { speed ->
            controller.setPlaybackSpeed(speed.toFloat())
        }
    }

    // Load content on first composition
    LaunchedEffect(contentId, initialFileId, initialAudioTrackIndex, initialSubtitleTrackIndex, resumePositionOverride) {
        viewModel.loadContent(
            contentId = contentId,
            preferredFileId = initialFileId,
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

            // Orientation policy keys off the system rotation lock so we never
            // jump the phone sideways against the user's preference:
            //   - Auto-rotate ON  -> SENSOR_LANDSCAPE: Play goes straight to
            //     fullscreen landscape (the natural video orientation) instead
            //     of letterboxing a centered band in portrait.
            //   - Auto-rotate OFF -> USER: a locked phone keeps its current
            //     orientation and the video letterboxes inside it, exactly as
            //     before. (The earlier unconditional SENSOR_LANDSCAPE force-
            //     rotated even locked phones, which is the behavior we avoid.)
            val autoRotateEnabled = Settings.System.getInt(
                activity.contentResolver,
                Settings.System.ACCELEROMETER_ROTATION,
                0,
            ) == 1
            val originalOrientation = activity.requestedOrientation
            activity.requestedOrientation = if (autoRotateEnabled) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_USER
            }
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

    // Set up the media item when stream URL becomes available
    LaunchedEffect(
        videoBackend,
        uiState.sessionId,
        uiState.streamUrl,
        uiState.playMethod,
        uiState.playbackPlan,
        uiState.delivery,
        uiState.startPosition,
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

        // Prefer the locally-downloaded file when one exists for the active
        // version (T9). Falls back to the streamed URL when not downloaded
        // or when the local file disappeared between detail-screen check
        // and Play. Media3 handles file:// URIs natively, so no factory
        // change is needed.
        val activeFileId = uiState.versions
            .getOrNull(uiState.selectedVersionIndex)
            ?.fileId
        val localUri: String? = activeFileId?.let { fileId ->
            val serverId = serverRegistry.activeServerId.value
                ?: com.continuum.app.common.downloads.DownloadEnqueuer.DEFAULT_SERVER_ID
            val profileId = profileRepository.getActiveProfileId()
                ?: com.continuum.app.common.downloads.DownloadEnqueuer.DEFAULT_PROFILE_ID
            downloadStorage.locateLocalMedia(serverId, profileId, fileId)?.uriString
        }
        val effectiveStreamUrl = localUri ?: streamUrl
        val plan = if (localUri == null) uiState.playbackPlan else null
        val delivery = if (localUri == null) plan?.delivery ?: uiState.delivery else null

        val mediaSpec = VideoPlayerMediaSpec(
            streamUrl = effectiveStreamUrl,
            // Local files play as progressive (DIRECT), regardless of how
            // the server originally provisioned the session.
            playMethod = if (localUri != null) com.continuum.app.model.playback.PlayMethod.DIRECT else playMethod,
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
        )
        if (localUri == null && uiState.sessionId != null) {
            startupStallDetector.onMounted(
                sessionKey = "${uiState.sessionId}:$effectiveStreamUrl",
                playMethod = playMethod,
                startPositionMs = mediaSpec.startPositionMs,
                nowMs = SystemClock.elapsedRealtime(),
            )
        }
        // Tell the playback service which engine to own for this media (Track A
        // Task 0). ASS/SSA subtitles route to MPV (libass fidelity); the service
        // resolves device-floor + route intent and rebinds + transfers state, so
        // the order relative to mount is safe.
        mediaController?.let { controller ->
            val engineRequest = VideoPlaybackBackendRequest(
                contentId = contentId,
                fileId = activeFileId ?: initialFileId,
                playMethod = mediaSpec.playMethod,
                delivery = delivery,
                plannedEngine = plan?.engine,
                routeFamily = plan?.routeFamily,
                formFactor = VideoPlaybackFormFactor.Mobile,
                hasHardContainer = mediaSpec.playMethod == PlayMethod.DIRECT &&
                    isHardPlaybackContainer(uiState.container),
                hasStyledSubtitles = uiState.subtitleTracks.any { it.isStyledSubtitle() },
                isAdaptiveHlsStream = isLikelyAdaptiveHlsStreamUrl(effectiveStreamUrl),
            )
            val switchResult = controller.awaitEngineSwitch(engineRequest)
            if (!switchResult.success) {
                viewModel.onEngineSwitchFailed("Playback engine could not be prepared for this route.")
                return@LaunchedEffect
            }
            if (switchResult.swapped) {
                return@LaunchedEffect
            }
        }
        backend.mount(mediaSpec)
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

        // Mirror the start effect's local-file preference so a rebuild never
        // silently switches a local-file playback back to the remote stream.
        val activeFileId = uiState.versions
            .getOrNull(uiState.selectedVersionIndex)
            ?.fileId
        val localUri: String? = activeFileId?.let { fileId ->
            val serverId = serverRegistry.activeServerId.value
                ?: com.continuum.app.common.downloads.DownloadEnqueuer.DEFAULT_SERVER_ID
            val profileId = profileRepository.getActiveProfileId()
                ?: com.continuum.app.common.downloads.DownloadEnqueuer.DEFAULT_PROFILE_ID
            downloadStorage.locateLocalMedia(serverId, profileId, fileId)?.uriString
        }
        val effectiveStreamUrl = localUri ?: streamUrl
        val plan = if (localUri == null) uiState.playbackPlan else null
        val delivery = if (localUri == null) plan?.delivery ?: uiState.delivery else null

        val mediaSpec = VideoPlayerMediaSpec(
            streamUrl = effectiveStreamUrl,
            playMethod = if (localUri != null) com.continuum.app.model.playback.PlayMethod.DIRECT else playMethod,
            delivery = delivery,
            serverUrl = uiState.serverUrl,
            container = uiState.container,
            subtitles = uiState.subtitleTracks,
            title = uiState.title.ifBlank { null },
            subtitle = uiState.subtitle.ifBlank { null },
            artworkUrl = uiState.artworkUrl,
            startPositionSeconds = uiState.startPosition,
            durationSeconds = uiState.duration,
            audioPassthroughCodecs = if (localUri == null) {
                plan.validatedPassthroughCodecs()
            } else {
                emptyList()
            },
        )
        mediaController?.let { controller ->
            val engineRequest = VideoPlaybackBackendRequest(
                contentId = contentId,
                fileId = activeFileId ?: initialFileId,
                playMethod = mediaSpec.playMethod,
                delivery = delivery,
                plannedEngine = plan?.engine,
                routeFamily = plan?.routeFamily,
                formFactor = VideoPlaybackFormFactor.Mobile,
                hasHardContainer = mediaSpec.playMethod == PlayMethod.DIRECT &&
                    isHardPlaybackContainer(uiState.container),
                hasStyledSubtitles = uiState.subtitleTracks.any { it.isStyledSubtitle() },
                isAdaptiveHlsStream = isLikelyAdaptiveHlsStreamUrl(effectiveStreamUrl),
            )
            val switchResult = controller.awaitEngineSwitch(engineRequest)
            if (!switchResult.success) {
                viewModel.onEngineSwitchFailed("Playback engine could not be prepared for this route.")
                return@LaunchedEffect
            }
            if (switchResult.swapped) {
                return@LaunchedEffect
            }
        }
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
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    viewModel.onBufferingChanged(playbackState == Player.STATE_BUFFERING)
                    if (playbackState == Player.STATE_ENDED) {
                        viewModel.onPlayingChanged(false)
                        // F2 fallback: auto-advance / prompt if no credits marker
                        // fired the trigger first.
                        viewModel.onApproachingEnd()
                    }
                }

                override fun onVideoSizeChanged(size: VideoSize) {
                    if (size.width > 0 && size.height > 0) {
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

    // Lifecycle-bounded position ticker. Cancels when the screen pauses or
    // the controller disconnects, so we don't hit a released Player.
    LaunchedEffect(mediaController, lifecycleOwner) {
        val controller = mediaController ?: return@LaunchedEffect
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                if (controller.isPlaying || controller.playbackState == Player.STATE_BUFFERING) {
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

    LaunchedEffect(mediaController, uiState.sessionId, uiState.streamUrl, uiState.playMethod) {
        val controller = mediaController ?: return@LaunchedEffect
        val sessionId = uiState.sessionId ?: return@LaunchedEffect
        val streamUrl = uiState.streamUrl ?: return@LaunchedEffect
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
        backend.selectSubtitle(
            subtitleTrackEntry(uiState.subtitleTracks, uiState.selectedSubtitleIndex),
        )
    }

    // Notify the ViewModel we're leaving the screen.
    DisposableEffect(Unit) {
        onDispose { viewModel.onExit() }
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
            LaunchedEffect(playerViewRef, subtitleAppearance) {
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
                        // (not the MediaController) so MPV gets surface-lifecycle
                        // callbacks; re-binds automatically when the engine swaps.
                        view.player = sessionPlayer
                        view.resizeMode = resizeMode
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            PlayerOverlay(
                state = uiState,
                viewModel = viewModel,
                roomSnapshot = roomSnapshot,
                onBack = {
                    // In-room exit: leave the room (host close confirm is handled
                    // by the overlay before this fires). The controller resets the
                    // repo + engine; solo playback just pops.
                    exitRequested = true
                    roomController?.leave(closeRoom = roomSnapshot?.isHost == true)
                    viewModel.onExit()
                    navController.popBackStack()
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
                        mediaController?.seekTo((position * 1000).toLong())
                    }
                },
                onToggleControls = { viewModel.onToggleControls() },
                onNextEpisode = { viewModel.onNextEpisode() },
                onSelectSubtitle = { viewModel.onSelectSubtitle(it) },
                onSelectAudio = { viewModel.onSelectAudio(it) },
                onSelectVersion = { viewModel.onSelectVersion(it) },
            )
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

private fun PlayerSubtitleInfo.isStyledSubtitle(): Boolean {
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

private fun isHardPlaybackContainer(container: String?): Boolean =
    isMpvPreferredOriginalPlaybackContainer(container)
