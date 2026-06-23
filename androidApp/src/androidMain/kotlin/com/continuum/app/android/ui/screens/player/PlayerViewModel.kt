package com.continuum.app.android.ui.screens.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.continuum.app.android.BuildConfig
import com.continuum.app.common.downloads.DownloadEnqueuer
import com.continuum.app.common.downloads.OfflineMediaResolver
import com.continuum.app.common.player.PlaybackCapabilityDetector
import com.continuum.app.common.player.Playability
import com.continuum.app.common.player.PlaybackSessionLifecycle
import com.continuum.app.common.player.PlaybackSessionManager
import com.continuum.app.common.player.PlayerNotice
import com.continuum.app.common.player.SessionState
import com.continuum.app.common.player.SleepTimerController
import com.continuum.app.common.player.SleepTimerState
import com.continuum.app.common.player.StartParams
import com.continuum.app.common.player.video.VideoPlaybackSessionCoordinator
import com.continuum.app.common.player.video.VideoPlaybackStartRequest
import com.continuum.app.common.player.video.VideoPlayerUiState
import com.continuum.app.common.player.video.canPlayResolvedStreamDirectly
import com.continuum.app.common.player.video.immediateServerFallbackMode
import com.continuum.app.common.player.video.requestedOriginalPlaybackMethod
import com.continuum.app.common.player.video.resolvedPlaybackDelivery
import com.continuum.app.common.settings.PlayerSettingsStore
import com.continuum.app.domain.player.IntroAutoSkipController
import com.continuum.app.domain.player.IntroAutoSkipState
import com.continuum.app.model.catalog.AudioTrack
import com.continuum.app.model.catalog.FileVersion
import com.continuum.app.model.catalog.VersionChapter
import com.continuum.app.model.catalog.TimeRange
import com.continuum.app.model.settings.SubtitleAppearance
import com.continuum.app.model.playback.PlayMethod
import com.continuum.app.model.playback.PlaybackDelivery
import com.continuum.app.model.playback.PlaybackExecutionPlan
import com.continuum.app.model.playback.PlaybackSessionResponse
import com.continuum.app.model.playback.PlayerSubtitleInfo
import com.continuum.app.model.playback.mergeDownloadedSubtitles
import com.continuum.app.model.playback.resolvePlaybackStartPosition
import com.continuum.app.model.subtitles.SubtitleAiJob
import com.continuum.app.model.subtitles.SubtitleAiQuota
import com.continuum.app.model.subtitles.SubtitleAiStatus
import com.continuum.app.model.subtitles.SubtitleDownloadRequest
import com.continuum.app.model.subtitles.SubtitleResult
import com.continuum.app.model.subtitles.SubtitleSearchRequest
import com.continuum.app.model.subtitles.SubtitleTranslateRequest
import com.continuum.app.network.ApiResult
import com.continuum.app.common.player.AutoPlayGuard
import com.continuum.app.network.ServerRegistry
import com.continuum.app.network.errorMessage
import com.continuum.app.playback.nextEpisodeAfter
import com.continuum.app.repository.CatalogRepository
import com.continuum.app.repository.PersonalDataRepository
import com.continuum.app.repository.ProfileRepository
import com.continuum.app.repository.SubtitlesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the video player screen.
 *
 * Orchestrates content loading, playback session management, progress reporting,
 * and UI state for controls overlay, subtitle/audio selection, and intro/credits detection.
 *
 * Phase 1: progress reporting + 404/outage recovery is now delegated to
 * [PlaybackSessionLifecycle]. Per-profile playback preferences are read from
 * [PlayerSettingsStore]. Intro auto-skip behavior (countdown ring, cancel,
 * one-shot fire) is owned by [IntroAutoSkipController].
 */
/** A transient remote "display_message"; [id] makes repeats re-trigger the toast. */
data class RemoteMessage(val id: Long, val text: String)

class PlayerViewModel(
    private val videoPlaybackCoordinator: VideoPlaybackSessionCoordinator,
    private val catalogRepository: CatalogRepository,
    private val playbackSessionManager: PlaybackSessionManager,
    private val profileRepository: ProfileRepository,
    private val personalDataRepository: PersonalDataRepository,
    private val capabilityDetector: PlaybackCapabilityDetector,
    private val offlineMediaResolver: OfflineMediaResolver,
    private val serverRegistry: ServerRegistry,
    // Phase 1 Phase 0-infra dependencies:
    private val playerSettingsStore: PlayerSettingsStore,
    private val introAutoSkipController: IntroAutoSkipController,
    private val sessionLifecycle: PlaybackSessionLifecycle,
    // Phase 2 sleep timer:
    private val sleepTimer: SleepTimerController,
    // Subtitle suite (search/download + AI translate):
    private val subtitlesRepository: SubtitlesRepository,
    // Track B: durable offline-safe position (resume + outbox sync).
    private val userItemStatePort: com.continuum.app.repository.port.UserItemStatePort,
    private val outboxSyncScheduler: com.continuum.app.common.data.sync.OutboxSyncScheduler,
) : ViewModel() {

    companion object {
        private const val TAG = "PlayerViewModel"
        private const val CONTROLS_AUTO_HIDE_MS = 3_000L
        // Record a durable position roughly every 10s of content time (matches the
        // server reporter cadence) to bound DB/outbox churn.
        private const val POSITION_RECORD_INTERVAL_SEC = 10.0
    }

    data class PlayerUiState(
        val isLoading: Boolean = true,
        val error: String? = null,
        val title: String = "",
        val subtitle: String = "",
        /**
         * Artwork URL used for the Now Playing lock-screen / Bluetooth /
         * notification surface. Sourced from `WatchDetail.posterUrl` with
         * `backdropUrl` fallback. Threaded into MediaItem.MediaMetadata so
         * the MediaSession publishes it to the OS. Mirrors iOS phone's
         * `NowPlayingController.setArtworkURL`.
         */
        val artworkUrl: String? = null,
        val sessionId: String? = null,
        val playMethod: PlayMethod? = null,
        val playbackPlan: PlaybackExecutionPlan? = null,
        val delivery: PlaybackDelivery? = null,
        val streamUrl: String? = null,
        val container: String? = null,
        val serverUrl: String = "",
        val accessToken: String = "",
        val startPosition: Double = 0.0,
        val position: Double = 0.0,
        val duration: Double = 0.0,
        val bufferedPosition: Double = 0.0,
        val isPlaying: Boolean = false,
        val isPaused: Boolean = false,
        val subtitleTracks: List<PlayerSubtitleInfo> = emptyList(),
        val audioTracks: List<AudioTrack> = emptyList(),
        val selectedAudioIndex: Int = 0,
        val selectedSubtitleIndex: Int = -1,
        val intro: TimeRange? = null,
        val credits: TimeRange? = null,
        /**
         * Chapters from the selected FileVersion (server-extracted via FFprobe
         * at ingest). Empty list when the file has no embedded chapters. The
         * settings-sheet "Chapters" affordance opens a list of these and seeks
         * the player to `startSeconds` on tap. Mirrors iOS phone behavior.
         */
        val chapters: List<VersionChapter> = emptyList(),
        val showNextEpisode: Boolean = false,
        val showControls: Boolean = true,
        val isBuffering: Boolean = false,
        val versions: List<FileVersion> = emptyList(),
        val selectedVersionIndex: Int = 0,
        val contentId: String = "",
        val seriesId: String? = null,
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null,
        // F2 next-episode auto-advance: resolved next episode + pass-out prompt.
        val nextEpisodeContentId: String? = null,
        val nextEpisodeLabel: String? = null,
        val stillWatchingPrompt: Boolean = false,
        val preferredAudioLanguage: String? = null,
        val preferredTextLanguage: String? = null,
        /**
         * Bumped whenever refreshSubtitles merges new downloaded tracks into
         * [subtitleTracks]. PlayerScreen watches this to rebuild the MediaItem
         * (subtitle configs are baked in at build time) and re-prepare at the
         * current position.
         */
        val subtitleRefreshNonce: Int = 0,
    ) {
        /**
         * Media file id of the active version — the id the subtitle
         * search/download/AI endpoints key on. Flows from
         * WatchDetail.versions[selectedVersionIndex].fileId (set by
         * applySessionToState and onSelectVersion).
         */
        val mediaFileId: Int?
            get() = versions.getOrNull(selectedVersionIndex)?.fileId
    }

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    /**
     * Explicit user/app seek commands. PlayerScreen collects this flow and
     * calls MediaController.seekTo. Keeping it separate from uiState.position
     * prevents routine progress samples from becoming seek commands.
     */
    private val _seekRequests = MutableSharedFlow<Double>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val seekRequests: SharedFlow<Double> = _seekRequests.asSharedFlow()

    /**
     * Unconditional seek channel for room-driven corrective seeks. The normal
     * position mirror in PlayerScreen applies a 2.0s deadband (to avoid feedback
     * loops between playback-progress updates and user scrubs), but Watch Together
     * corrective seeks can be as small as the engine's 0.35s drift threshold and
     * MUST always reach the player. PlayerScreen collects this and calls
     * `mediaController.seekTo` with no deadband. See [seekImmediate].
     */
    private val _immediateSeeks = kotlinx.coroutines.flow.MutableSharedFlow<Double>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val immediateSeeks: kotlinx.coroutines.flow.SharedFlow<Double> = _immediateSeeks.asSharedFlow()

    // ---- Remote session-control surface (driven by PlaybackRealtimeController) -----
    // The control socket can stop the session and display a message; neither has a
    // VM-owned channel today (teardown is screen-local, notice is lifecycle-owned),
    // so expose thin ones here.
    private val _remoteStopRequests = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    /** PlayerScreen collects this and tears the screen down (mirrors a back press). */
    val remoteStopRequests: kotlinx.coroutines.flow.SharedFlow<Unit> = _remoteStopRequests.asSharedFlow()

    private var remoteMessageCounter = 0L
    private val _remoteMessage = MutableStateFlow<RemoteMessage?>(null)
    /** A server "display_message" to surface transiently; null = nothing. */
    val remoteMessage: StateFlow<RemoteMessage?> = _remoteMessage.asStateFlow()

    /** Intro auto-skip banner state. UI consumes this directly. */
    val introSkipState: StateFlow<IntroAutoSkipState> = introAutoSkipController.state

    /**
     * Transient player notice (server reconnecting, suspend warnings, etc.) emitted by
     * [PlaybackSessionLifecycle]. `null` means show nothing. UI consumes this directly.
     */
    val notice: StateFlow<PlayerNotice?> = sessionLifecycle.notice

    /**
     * Lifecycle session state. UI consumes this to drive the buffering spinner during
     * outage Reconnecting (which the underlying ExoPlayer can't observe).
     */
    val sessionState: StateFlow<SessionState> = sessionLifecycle.state

    // ---- Player settings flows (per-profile, DataStore-backed) -----------------
    val playbackSpeed: StateFlow<Double> = playerSettingsStore.playbackSpeedFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1.0)
    val videoGravity: StateFlow<String> = playerSettingsStore.videoGravityFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, "fit")
    val autoSkipIntroEnabled: StateFlow<Boolean> = playerSettingsStore.autoSkipIntroFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val autoPlayNextEnabled: StateFlow<Boolean> = playerSettingsStore.autoPlayNextFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    // ---- F2 pass-out protection ----
    // Per-profile "Still watching?" threshold (default 3; 0 = off).
    val passOutThreshold: StateFlow<Int> = playerSettingsStore.passOutThresholdFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, 3)
    // The mobile player reloads in place (loadContent(nextContentId)), so the
    // same VM persists across episodes and the guard accumulates the streak.
    // The guard reads the threshold lazily so a settings change applies live.
    private val autoPlayGuard = AutoPlayGuard(threshold = { passOutThreshold.value })
    // Once-per-episode guard for the credits/ended trigger; reset on each load.
    private var autoAdvanceHandled = false
    private var engineSwitchFallbackAttempted = false
    val hdrEnabled: StateFlow<Boolean> = playerSettingsStore.hdrEnabledFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val subtitleAppearance: StateFlow<SubtitleAppearance> = playerSettingsStore.subtitleAppearanceFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, SubtitleAppearance.DEFAULT)
    /**
     * Per-profile audio/subtitle delay in ms. Mirrors iOS phone's `audioSyncMs` /
     * `subtitleSyncMs` (`iosApp/Screens/Player/Sheets/PlayerSettingsSheet.swift:265-285`).
     * Applied by ContinuumPlaybackService via DelayAudioProcessor (audio) and
     * OffsetSubtitleParserFactory (subtitle); the settings sheet rows write
     * directly through the store and the live player picks up the change on
     * the next flush / parse.
     */
    val audioDelayMs: StateFlow<Int> = playerSettingsStore.audioSyncMsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    val subtitleDelayMs: StateFlow<Int> = playerSettingsStore.subtitleSyncMsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    // ---- Sleep timer ------------------------------------------------------------
    /** Live state of the sleep-timer (Idle or Active(remainingSeconds)). */
    val sleepTimerState: StateFlow<SleepTimerState> = sleepTimer.state

    /** Default duration shown in the picker — persists across sessions. */
    val sleepTimerDefaultMinutes: StateFlow<Int> = playerSettingsStore.sleepTimerDefaultMinutesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, 30)

    /** UI state for the subtitle search + AI translate sheets. */
    data class SubtitleToolsUiState(
        /** null until probed (lazily, on first TracksSheet open); fetch failure → SubtitleAiStatus(false, false). */
        val aiStatus: SubtitleAiStatus? = null,
        val searchLoading: Boolean = false,
        val searchAttempted: Boolean = false,
        val searchResults: List<SubtitleResult> = emptyList(),
        val searchWarnings: List<String> = emptyList(),
        val searchError: String? = null,
        /** "{provider}:{id}" of the result currently downloading; null otherwise. */
        val downloadingKey: String? = null,
        /** One-shot: a download finished and was auto-selected — sheet dismisses on this. */
        val downloadCompleted: Boolean = false,
        /** Transcription quota; null = unlimited / not applicable / fetch failed (counter hidden). */
        val quota: SubtitleAiQuota? = null,
        val translateSubmitting: Boolean = false,
        val translateError: String? = null,
        /** In-flight AI job with live progress; null when idle. */
        val activeJob: SubtitleAiJob? = null,
        /** One-shot: an AI job completed and its track was auto-selected — sheet dismisses on this. */
        val jobJustCompleted: Boolean = false,
    )

    private val _subtitleTools = MutableStateFlow(SubtitleToolsUiState())
    val subtitleTools: StateFlow<SubtitleToolsUiState> = _subtitleTools.asStateFlow()

    private var aiStatusFetched = false
    private var searchJob: Job? = null
    private var aiJobHandle: Job? = null

    private var controlsHideJob: Job? = null
    private var introObserverJob: Job? = null
    private var lifecycleObserverJob: Job? = null
    private var resolveNextEpisodeJob: Job? = null

    init {
        // Mirror lifecycle Failed state into the UI error field so the user sees a
        // notice when outage recovery times out or the session fails to start. The
        // notice flow is intentionally *not* surfaced here — that's Phase 3 work.
        lifecycleObserverJob = viewModelScope.launch {
            sessionLifecycle.state.collect { state ->
                if (state is SessionState.Failed) {
                    _uiState.update { current ->
                        if (current.error == null) current.copy(error = state.message) else current
                    }
                }
            }
        }

        // When the sleep timer fires, flip user intent to paused. PlayerScreen
        // mirrors `isPaused` to `mediaController.playWhenReady`, so this is
        // sufficient to halt playback without going through onPlayPause()
        // (which is a *toggle* and would inadvertently resume a paused player).
        sleepTimer.configure {
            _uiState.update { it.copy(isPaused = true) }
        }
    }

    /**
     * Loads content metadata and starts a playback session.
     * This is the main entry point called when the player screen is first displayed.
     */
    fun loadContent(
        contentId: String,
        preferredFileId: Int? = null,
        initialAudioTrackIndex: Int? = null,
        initialSubtitleTrackIndex: Int? = null,
        resumePositionOverride: Double? = null,
        // True for Watch Together (the synced anchor must land exactly — no
        // skip-back nudge). The request's roomId is always null on mobile, so WT
        // can't be inferred from it the way the TV starter does.
        suppressResumeRewind: Boolean = false,
    ) {
        // A fresh load resets any in-flight intro countdown / cancellation memory.
        introAutoSkipController.reset()
        // New item: re-arm the once-per-episode auto-advance trigger. (The
        // AutoPlayGuard streak intentionally PERSISTS across episodes.)
        autoAdvanceHandled = false
        engineSwitchFallbackAttempted = false
        // Cancel any in-flight resolve from the previous episode so its result
        // can't land on this one and overwrite the fresh next-episode pointer.
        resolveNextEpisodeJob?.cancel()

        // Clear episode-scoped UI carried over from the previous item so the
        // stale "Next episode"/prompt overlays can't flash during the reload.
        _uiState.update {
            it.copy(
                isLoading = true,
                error = null,
                contentId = contentId,
                showNextEpisode = false,
                stillWatchingPrompt = false,
                nextEpisodeContentId = null,
                nextEpisodeLabel = null,
            )
        }

        viewModelScope.launch {
            try {
                // Offline-first fast path: if we have a completed download for
                // this contentId AND its bytes are still on disk, hand the
                // player a file:// URI without touching the server at all.
                // Title + duration are best-effort — we attempt the watch
                // detail fetch but tolerate failure.
                if (tryLocalPlayback(contentId, preferredFileId, resumePositionOverride)) {
                    return@launch
                }

                when (val playbackState = videoPlaybackCoordinator.start(
                    VideoPlaybackStartRequest(
                        contentId = contentId,
                        preferredFileId = preferredFileId,
                        roomId = null,
                        resumePositionOverride = resumePositionOverride,
                        audioTrackIndex = initialAudioTrackIndex,
                        subtitleTrackIndex = initialSubtitleTrackIndex,
                        suppressResumeRewind = suppressResumeRewind,
                    ),
                )) {
                    is VideoPlayerUiState.Ready -> applyCoordinatorStateToUi(
                        playbackState = playbackState,
                        preferredFileId = preferredFileId,
                        initialSubtitleTrackIndex = initialSubtitleTrackIndex,
                    )
                    is VideoPlayerUiState.Error -> {
                        _uiState.update {
                            it.copy(isLoading = false, error = playbackState.message)
                        }
                    }
                    is VideoPlayerUiState.Loading -> Unit
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading content", e)
                _uiState.update {
                    it.copy(isLoading = false, error = "Unexpected error: ${e.message}")
                }
            }
        }
    }

    private suspend fun applyCoordinatorStateToUi(
        playbackState: VideoPlayerUiState.Ready,
        preferredFileId: Int?,
        initialSubtitleTrackIndex: Int?,
    ) {
        val watchDetail = when (val r = catalogRepository.getWatchDetail(playbackState.contentId)) {
            is ApiResult.Success -> r.data
            else -> null
        }
        val versions = watchDetail?.versions?.takeIf { it.isNotEmpty() }
            ?: playbackState.fileId
                ?.let { fileId ->
                    listOf(
                        FileVersion(
                            fileId = fileId,
                            duration = playbackState.durationSeconds,
                            chapters = playbackState.chapters.takeIf { it.isNotEmpty() },
                        ),
                    )
                }
            ?: emptyList()
        val versionIndex = playbackState.fileId
            ?.let { fileId -> versions.indexOfFirst { it.fileId == fileId } }
            ?.takeIf { it >= 0 }
            ?: watchDetail?.let { findPreferredVersion(it, preferredFileId, null) }
            ?: 0
        val version = versions.getOrNull(versionIndex)
        val resolvedSubtitleIndex = initialSubtitleTrackIndex
            ?.takeIf { it == -1 || it in playbackState.subtitleUrls.indices }
            ?: -1

        _uiState.update {
            it.copy(
                isLoading = false,
                error = null,
                title = watchDetail?.title ?: playbackState.title,
                subtitle = watchDetail?.let { detail -> buildSubtitle(detail) } ?: playbackState.subtitle.orEmpty(),
                artworkUrl = playbackState.artworkUrl,
                sessionId = playbackState.sessionId,
                playMethod = playbackState.playMethod,
                playbackPlan = playbackState.playbackPlan,
                delivery = playbackState.delivery,
                streamUrl = playbackState.streamUrl,
                container = playbackState.container,
                serverUrl = playbackState.serverUrl,
                accessToken = playbackState.accessToken,
                startPosition = playbackState.startPositionSeconds,
                position = playbackState.startPositionSeconds,
                duration = playbackState.durationSeconds.takeIf { duration -> duration > 0.0 }
                    ?: version?.duration
                    ?: 0.0,
                isPlaying = true,
                isPaused = false,
                subtitleTracks = playbackState.subtitleUrls,
                audioTracks = version?.audioTracks ?: emptyList(),
                selectedAudioIndex = playbackState.audioTrackIndex,
                selectedSubtitleIndex = resolvedSubtitleIndex,
                intro = playbackState.intro,
                credits = playbackState.credits,
                chapters = playbackState.chapters.ifEmpty { version?.chapters.orEmpty() },
                versions = versions,
                selectedVersionIndex = versionIndex,
                seriesId = watchDetail?.seriesId,
                seasonNumber = watchDetail?.seasonNumber,
                episodeNumber = watchDetail?.episodeNumber,
                nextEpisodeContentId = null,
                nextEpisodeLabel = null,
                stillWatchingPrompt = false,
                preferredAudioLanguage = playbackState.preferredAudioLanguage,
                preferredTextLanguage = playbackState.preferredTextLanguage,
            )
        }

        // Begin observing intro auto-skip inputs for this session.
        startIntroAutoSkipObserver()
        // F2: resolve the next episode for auto-advance / "Up next".
        resolveNextEpisode()

        // Schedule controls auto-hide
        scheduleControlsHide()
    }

    private fun startIntroAutoSkipObserver() {
        introObserverJob?.cancel()
        introObserverJob = introAutoSkipController.observe(
            position = _uiState
                .map { it.position }
                .distinctUntilChanged(),
            introRange = _uiState
                .map { it.intro }
                .distinctUntilChanged(),
            autoSkipEnabled = playerSettingsStore.autoSkipIntroFlow,
            introKey = _uiState
                .map { state ->
                    state.intro?.let { intro ->
                        val fileId = state.versions.getOrNull(state.selectedVersionIndex)?.fileId
                        "${state.sessionId}:${fileId}:${intro.start}:${intro.end}"
                    }
                }
                .distinctUntilChanged(),
            onAutoSkipFire = { seekToSec -> onSeek(seekToSec) },
        )
    }

    /**
     * Preflight signaled the selected track combo can't be direct-played on
     * this device. Fall back to a transcoded stream at the current position.
     * The user-facing notice explains *why* — "Lossless audio not supported"
     * reads differently than "DV Profile 7 not supported", and a single
     * "not supported" banner would hide both.
     */
    fun onUnsupportedPlayback(reason: Playability) {
        val state = _uiState.value
        val sessionId = state.sessionId ?: return
        val versions = state.versions
        val versionIndex = state.selectedVersionIndex
        val selectedAudioIndex = state.selectedAudioIndex
        val selectedSubtitleIndex = state.selectedSubtitleIndex
        val version = versions.getOrNull(versionIndex) ?: return

        val notice = when (reason) {
            is Playability.UnsupportedDvProfile ->
                "This device cannot play Dolby Vision Profile ${reason.profile}. Falling back to transcoded stream."
            is Playability.UnsupportedAudioCodec ->
                "Lossless audio not supported on this output. Falling back to transcoded stream."
            is Playability.UnsupportedChannelCount ->
                "Audio channel count not supported. Falling back to transcoded stream."
            is Playability.StartupStalled ->
                "Playback did not start cleanly on this device. Falling back to transcoded stream."
            Playability.Supported -> return
        }
        Log.i(TAG, "Preflight fallback: $notice")

        viewModelScope.launch {
            val capabilities = capabilityDetector.detect()
            val sessionResponse = PlaybackSessionResponse(
                sessionId = sessionId,
                userId = 0,
                profileId = null,
                mediaFileId = version.fileId,
                playMethod = state.playMethod ?: PlayMethod.DIRECT,
                position = state.position,
                isPaused = state.isPaused,
                streamUrl = state.streamUrl.orEmpty(),
                audioTrackIndex = selectedAudioIndex,
                durationSeconds = state.duration,
                subtitleUrls = state.subtitleTracks,
                playbackInfo = null,
                playbackPlan = state.playbackPlan,
            )
            when (val r = playbackSessionManager.startTranscodeFallback(
                session = sessionResponse,
                seekSeconds = state.position,
                resolution = version.resolution.orEmpty(),
                mode = com.continuum.app.common.player.PlaybackSessionManager.TranscodeMode.FULL,
                audioTrackIndex = selectedAudioIndex,
                subtitleTrackIndex = selectedSubtitleIndex,
            )) {
                is ApiResult.Success -> {
                    val fallback = r.data
                    sessionLifecycle.adoptActiveSession(
                        params = StartParams(
                            contentId = state.contentId,
                            fileId = version.fileId,
                            capabilities = capabilities,
                            audioTrackIndex = fallback.audioTrackIndex,
                            qualityPreference = null,
                            startPosition = fallback.position,
                        ),
                        session = fallback,
                    )
                    _uiState.update {
                        it.copy(
                            sessionId = fallback.sessionId,
                            playMethod = fallback.playMethod,
                            playbackPlan = fallback.playbackPlan,
                            delivery = fallback.resolvedPlaybackDelivery(),
                            streamUrl = fallback.streamUrl,
                            startPosition = fallback.position,
                        )
                    }
                }
                is ApiResult.Error -> _uiState.update {
                    it.copy(error = "$notice (start failed: ${r.message})")
                }
                is ApiResult.NetworkError -> _uiState.update {
                    it.copy(error = "$notice (network error: ${r.exception.message})")
                }
            }
        }
    }

    fun onEngineSwitchFailed(message: String) {
        val state = _uiState.value
        if (
            !engineSwitchFallbackAttempted &&
            state.sessionId != null &&
            state.streamUrl != null &&
            state.versions.getOrNull(state.selectedVersionIndex) != null
        ) {
            engineSwitchFallbackAttempted = true
            onUnsupportedPlayback(Playability.StartupStalled(bufferedAheadMs = 0L, stalledForMs = 0L))
            return
        }
        _uiState.update {
            it.copy(
                isLoading = false,
                error = message,
                isPlaying = false,
                isBuffering = false,
            )
        }
    }

    /** Called by the player when the current position changes. */
    fun onPositionChanged(positionMs: Long, durationMs: Long, bufferedPositionMs: Long = 0L) {
        if (positionMs < 0) return

        val positionSec = positionMs / 1000.0
        val durationSec = durationMs / 1000.0
        val bufferedSec = bufferedPositionMs / 1000.0
        val previousPosition = _uiState.value.position

        _uiState.update { state ->
            state.copy(
                position = positionSec,
                duration = if (durationSec > 0) durationSec else state.duration,
                bufferedPosition = bufferedSec,
                // synthesize from the credits range — server doesn't tell us when to
                // surface the next-episode prompt, so we infer it from the credits start.
                showNextEpisode = state.credits?.let { positionSec >= it.start && state.nextEpisodeContentId != null } ?: false,
            )
        }

        // F2: auto-advance / prompt when playback CROSSES the credits point (only
        // on the before->after transition, so resuming inside the credits doesn't
        // instantly skip).
        _uiState.value.credits?.start?.let { creditsStart ->
            if (previousPosition < creditsStart && positionSec >= creditsStart) onApproachingEnd()
        }

        // Forward to the lifecycle so its 10s reporter has a fresh sample.
        // Recovery (404/outage) is fully owned by the lifecycle.
        sessionLifecycle.reportPosition(
            positionSec = positionSec,
            durationSec = if (durationSec > 0) durationSec else _uiState.value.duration,
            isPaused = _uiState.value.isPaused,
        )

        // Track B: durably record the position (local resume + outbox sync) for
        // BOTH streaming and offline-download playback — the lifecycle's server
        // reporter does nothing on the offline-download path (no session). Throttled
        // by content-time delta so it fires ~every 10s of playback, not per tick.
        maybeRecordPosition(positionSec, if (durationSec > 0) durationSec else _uiState.value.duration)
    }

    private var lastRecordedKey: String? = null
    private var lastRecordedPositionSec: Double = -1.0

    private fun currentFileId(): Int? =
        _uiState.value.versions.getOrNull(_uiState.value.selectedVersionIndex)?.fileId

    /** [force] bypasses the time-throttle (used on pause/stop to capture the exact spot). */
    private fun maybeRecordPosition(positionSec: Double, durationSec: Double, force: Boolean = false) {
        if (positionSec < 0.0) return
        val contentId = _uiState.value.contentId.takeIf { it.isNotBlank() } ?: return
        val fileId = currentFileId() ?: return
        val key = "$contentId|$fileId"
        // Always record the first sample for a new item/version; otherwise throttle
        // by content-time delta so the per-item first write is never suppressed by
        // the previous item's position.
        if (!force && key == lastRecordedKey && lastRecordedPositionSec >= 0.0 &&
            kotlin.math.abs(positionSec - lastRecordedPositionSec) < POSITION_RECORD_INTERVAL_SEC
        ) {
            return
        }
        lastRecordedKey = key
        lastRecordedPositionSec = positionSec
        viewModelScope.launch {
            userItemStatePort.recordPosition(contentId, fileId, positionSec, durationSec.takeIf { it > 0.0 })
        }
    }

    /**
     * Called when the player's actual playing state changes.
     *
     * `isPlaying` reflects the player — it drops during buffering or stalls even when the
     * user intends to play. `isPaused` is the user's intent and must not be overwritten here,
     * otherwise a buffering glitch flips the pause icon and defeats scheduleControlsHide.
     */
    fun onPlayingChanged(isPlaying: Boolean) {
        _uiState.update { it.copy(isPlaying = isPlaying) }
        // Controls should auto-hide once real playback resumes after a pause.
        if (isPlaying && !_uiState.value.isPaused && _uiState.value.showControls) {
            scheduleControlsHide()
        }
        // Durably capture the exact spot when playback halts (pause/stall/stop) —
        // this runs while the VM is alive, so resume is reliable even if the
        // fire-and-forget exit write doesn't complete during teardown.
        if (!isPlaying) {
            maybeRecordPosition(_uiState.value.position, _uiState.value.duration, force = true)
        }
    }

    /** Called when buffering state changes. */
    fun onBufferingChanged(isBuffering: Boolean) {
        _uiState.update { it.copy(isBuffering = isBuffering) }
    }

    /** Toggle play/pause — tracks user intent; PlayerScreen mirrors this to playWhenReady. */
    fun onPlayPause() {
        autoPlayGuard.recordUserAction() // deliberate interaction resets the pass-out streak
        _uiState.update { it.copy(isPaused = !it.isPaused) }
        // Re-arm the auto-hide timer so controls don't linger after resuming playback.
        if (_uiState.value.showControls) {
            scheduleControlsHide()
        }
    }

    /** Seek to a specific position (in seconds). */
    /**
     * Settings-sheet "Chapters" row picked a chapter. Returns the seek target
     * in seconds; the overlay drives the MediaController seek via [onSeek].
     */
    fun onSeekToChapter(chapterIndex: Int): Double? =
        _uiState.value.chapters.getOrNull(chapterIndex)?.startSeconds

    fun onSeek(position: Double) {
        autoPlayGuard.recordUserAction() // deliberate interaction resets the pass-out streak
        _uiState.update { it.copy(position = position) }
        _seekRequests.tryEmit(position)
    }

    /**
     * Immediate, deadband-free seek for room-driven corrective seeks
     * (RoomSyncController.applyDecision). Updates `uiState.position` like
     * [onSeek] AND emits on [immediateSeeks] so PlayerScreen drives the
     * MediaController unconditionally — bypassing the 2.0s position-mirror
     * deadband that would otherwise swallow sub-2s sync corrections.
     */
    fun seekImmediate(position: Double) {
        _uiState.update { it.copy(position = position) }
        _immediateSeeks.tryEmit(position)
    }

    // ---- Remote-control adapters (PlaybackRealtimeController calls these) ----
    // Thin wrappers over existing transport; no new playback logic.
    // The VM's start request always carries roomId=null, so WT membership is
    // set by the screen (which owns roomId) for remote-transport gating.
    private var inWatchTogetherRoom = false
    fun setInWatchTogetherRoom(value: Boolean) { inWatchTogetherRoom = value }
    /** True while in a Watch Together room — remote transport is gated (the room is authoritative). */
    val remoteTransportSuppressed: Boolean get() = inWatchTogetherRoom

    fun remotePause() { _uiState.update { it.copy(isPaused = true) } }
    fun remoteUnpause() { _uiState.update { it.copy(isPaused = false) } }
    fun remoteTogglePlayPause() { onPlayPause() }
    fun remoteSeek(positionSeconds: Double) { seekImmediate(positionSeconds) }
    fun remoteStop() { _remoteStopRequests.tryEmit(Unit) }
    // Carry a monotonic id so an identical message repeated within the toast
    // window still re-triggers (StateFlow would dedup equal values otherwise).
    fun remoteDisplayMessage(message: String) {
        _remoteMessage.value = RemoteMessage(++remoteMessageCounter, message)
    }
    fun clearRemoteMessage() { _remoteMessage.value = null }

    /**
     * Remote `set_audio_track` / `set_subtitle_track`. Validate the index against
     * the live track list so a bogus remote index is a no-op rather than (for
     * subtitles) silently turning captions off — only an explicit -1 disables.
     */
    fun remoteSelectAudio(index: Int) {
        if (index in _uiState.value.audioTracks.indices) onSelectAudio(index)
    }
    fun remoteSelectSubtitle(index: Int) {
        if (index == -1 || index in _uiState.value.subtitleTracks.indices) onSelectSubtitle(index)
    }

    /**
     * Adopt server-recomputed intro/credits ranges (a `markers_updated` event).
     * The intro auto-skip observer and the credits-based F2 trigger read these
     * from UiState, so updating them takes effect immediately. Passing `null`
     * clears a marker the server says no longer applies.
     */
    fun applyUpdatedMarkers(intro: TimeRange?, credits: TimeRange?) {
        _uiState.update { it.copy(intro = intro, credits = credits) }
    }

    /** Select a subtitle track (-1 to disable). */
    fun onSelectSubtitle(index: Int) {
        _uiState.update { it.copy(selectedSubtitleIndex = index) }
    }

    /** Select an audio track (may require server-side switch). */
    fun onSelectAudio(index: Int) {
        val currentState = _uiState.value
        val sessionId = currentState.sessionId ?: return

        _uiState.update { it.copy(selectedAudioIndex = index) }

        viewModelScope.launch {
            val result = playbackSessionManager.changeAudio(sessionId, index, currentState.position)
            when (result) {
                is ApiResult.Success -> {
                    val response = result.data
                    // If the server provided a new stream URL, update the state
                    if (response.streamUrl != currentState.streamUrl) {
                        _uiState.update {
                            it.copy(
                                streamUrl = response.streamUrl,
                                playMethod = response.playMethod,
                                playbackPlan = null,
                                delivery = null,
                                selectedAudioIndex = response.audioTrackIndex,
                            )
                        }
                    }
                }
                is ApiResult.Error -> {
                    Log.e(TAG, "Failed to change audio: ${result.message}")
                }
                is ApiResult.NetworkError -> {
                    Log.e(TAG, "Network error changing audio", result.exception)
                }
            }
        }
    }

    // ---- Subtitle suite: search / download / AI translate -----------------------

    /**
     * Lazy one-shot AI status probe, mirroring the web: fetched the first time
     * the TracksSheet opens; on failure both flags stay false and the
     * "Translate with AI…" row is hidden (no error surfaced).
     */
    fun onTracksSheetOpened() {
        if (aiStatusFetched) return
        aiStatusFetched = true
        viewModelScope.launch {
            val status = when (val r = subtitlesRepository.aiStatus()) {
                is ApiResult.Success -> r.data
                else -> SubtitleAiStatus(enabled = false, transcribeEnabled = false)
            }
            _subtitleTools.update { it.copy(aiStatus = status) }
        }
    }

    /** Provider search for the active version's media file. */
    fun searchSubtitles(language: String) {
        val mediaFileId = _uiState.value.mediaFileId ?: return
        searchJob?.cancel()
        _subtitleTools.update {
            it.copy(
                searchLoading = true,
                searchAttempted = true,
                searchError = null,
                searchResults = emptyList(),
                searchWarnings = emptyList(),
            )
        }
        searchJob = viewModelScope.launch {
            val request = SubtitleSearchRequest(mediaFileId = mediaFileId, languages = listOf(language))
            when (val r = subtitlesRepository.search(request)) {
                is ApiResult.Success -> _subtitleTools.update {
                    it.copy(
                        searchLoading = false,
                        searchResults = r.data.results,
                        searchWarnings = r.data.warnings,
                    )
                }
                // No capability probe exists: "no providers configured" arrives
                // as a plain server error — surface its text verbatim.
                is ApiResult.Error, is ApiResult.NetworkError -> _subtitleTools.update {
                    it.copy(searchLoading = false, searchError = r.errorMessage("Subtitle search failed"))
                }
            }
        }
    }

    /** Download a search result; on success merge + auto-select the new track. */
    fun downloadSubtitle(result: SubtitleResult) {
        val mediaFileId = _uiState.value.mediaFileId ?: return
        val key = "${result.provider}:${result.id}"
        _subtitleTools.update { it.copy(downloadingKey = key, searchError = null) }
        viewModelScope.launch {
            val request = SubtitleDownloadRequest(
                mediaFileId = mediaFileId,
                provider = result.provider,
                subtitleId = result.id,
                language = result.language,
                releaseName = result.releaseName,
                format = result.format,
                score = result.score,
                hearingImpaired = result.hearingImpaired,
            )
            when (val r = subtitlesRepository.download(request)) {
                is ApiResult.Success -> {
                    doRefreshSubtitles(autoSelectSubtitleId = r.data.subtitle.id)
                    _subtitleTools.update { it.copy(downloadingKey = null, downloadCompleted = true) }
                }
                is ApiResult.Error, is ApiResult.NetworkError -> _subtitleTools.update {
                    it.copy(downloadingKey = null, searchError = r.errorMessage("Subtitle download failed"))
                }
            }
        }
    }

    /**
     * Web-parity track refresh (usePlaybackSession.ts refreshSubtitles): the
     * playback session is NOT restarted. We refetch the downloaded-subtitles
     * list, merge it into subtitleTracks via the shared pure helper, bump
     * subtitleRefreshNonce so PlayerScreen rebuilds the MediaItem in place,
     * and select the new track when [autoSelectSubtitleId] matches.
     */
    fun refreshSubtitles(autoSelectSubtitleId: Int? = null) {
        viewModelScope.launch { doRefreshSubtitles(autoSelectSubtitleId) }
    }

    private suspend fun doRefreshSubtitles(autoSelectSubtitleId: Int?) {
        val state = _uiState.value
        val mediaFileId = state.mediaFileId ?: return
        // Inert without a remote session (offline/local playback has no
        // session-scoped subtitle URLs to merge into).
        val sessionId = state.sessionId ?: return
        val downloaded = when (val r = subtitlesRepository.list(mediaFileId)) {
            is ApiResult.Success -> r.data.subtitles
            else -> return // best effort — refresh failure must not disrupt playback (web parity)
        }
        if (downloaded.isEmpty()) return
        val merged = mergeDownloadedSubtitles(
            existing = state.subtitleTracks,
            downloaded = downloaded,
            sessionId = sessionId,
            serverUrl = state.serverUrl,
        )
        val autoIndex = autoSelectSubtitleId?.let { id -> downloadedTrackIndex(merged, downloaded, id) }
        _uiState.update {
            it.copy(
                subtitleTracks = merged,
                subtitleRefreshNonce = it.subtitleRefreshNonce + 1,
                selectedSubtitleIndex = autoIndex ?: it.selectedSubtitleIndex,
            )
        }
    }

    /** Refresh the transcription quota; non-limited / failed lookups hide the counter (web parity). */
    fun refreshAiQuota() {
        viewModelScope.launch {
            val quota = when (val r = subtitlesRepository.aiQuota()) {
                is ApiResult.Success -> r.data.takeIf { it.limited }
                else -> null
            }
            _subtitleTools.update { it.copy(quota = quota) }
        }
    }

    /**
     * Start an AI job and poll it to a terminal state. Android passes the
     * current playhead as start_position and does NOT pass session_id — we
     * poll for completion instead of streaming live cues
     * (SubtitleTranslateRequest doc).
     */
    fun startAiJob(kind: String, sourceIndex: Int, sourceLanguage: String, targetLanguage: String) {
        val state = _uiState.value
        val mediaFileId = state.mediaFileId ?: return
        if (_subtitleTools.value.activeJob != null || _subtitleTools.value.translateSubmitting) return
        _subtitleTools.update { it.copy(translateSubmitting = true, translateError = null, jobJustCompleted = false) }
        aiJobHandle?.cancel()
        aiJobHandle = viewModelScope.launch {
            val result = subtitlesRepository.translate(
                SubtitleTranslateRequest(
                    mediaFileId = mediaFileId,
                    kind = kind,
                    sourceIndex = sourceIndex,
                    sourceLanguage = sourceLanguage.ifBlank { null },
                    targetLanguage = targetLanguage.ifBlank { null },
                    startPosition = state.position,
                ),
            )
            when (result) {
                is ApiResult.Success -> {
                    val job = result.data.job
                    _subtitleTools.update { it.copy(translateSubmitting = false, activeJob = job) }
                    val outcome = subtitlesRepository.pollJob(job.id) { update ->
                        _subtitleTools.update { it.copy(activeJob = update) }
                    }
                    when (outcome) {
                        is SubtitlesRepository.SubtitleJobOutcome.Completed -> {
                            doRefreshSubtitles(autoSelectSubtitleId = outcome.resultSubtitleId)
                            _subtitleTools.update { it.copy(activeJob = null, jobJustCompleted = true) }
                        }
                        is SubtitlesRepository.SubtitleJobOutcome.Failed -> _subtitleTools.update {
                            it.copy(activeJob = null, translateError = outcome.message ?: "Job failed")
                        }
                        SubtitlesRepository.SubtitleJobOutcome.Cancelled -> _subtitleTools.update {
                            it.copy(activeJob = null)
                        }
                    }
                }
                is ApiResult.Error -> {
                    // 429 = quota exhausted while our counter was stale — refresh
                    // so the banner and disabled button match the error shown.
                    if (result.code == 429) refreshAiQuota()
                    _subtitleTools.update {
                        it.copy(translateSubmitting = false, translateError = result.errorMessage("Failed to start AI job"))
                    }
                }
                is ApiResult.NetworkError -> _subtitleTools.update {
                    it.copy(translateSubmitting = false, translateError = result.errorMessage("Failed to start AI job"))
                }
            }
        }
    }

    /** Cancel the in-flight AI job server-side; the poll loop then sees the terminal cancelled status. */
    fun cancelAiJob() {
        val job = _subtitleTools.value.activeJob ?: return
        viewModelScope.launch { subtitlesRepository.cancelJob(job.id) }
    }

    /** Search sheet dismissed — clear transient search state (results survive reopen). */
    fun onSearchSheetClosed() {
        searchJob?.cancel()
        _subtitleTools.update {
            it.copy(searchLoading = false, downloadingKey = null, downloadCompleted = false, searchError = null)
        }
    }

    /** Translate sheet dismissed — clear transient state. A running job keeps polling in the background. */
    fun onTranslateSheetClosed() {
        _subtitleTools.update {
            it.copy(translateSubmitting = false, translateError = null, jobJustCompleted = false)
        }
    }

    /** Skip the intro (legacy alias used by PlayerOverlay). Same effect as [onSkipIntroNow]. */
    fun onSkipIntro() {
        onSkipIntroNow()
    }

    /** Skip the intro now: seek to the end of the intro range and clear any active countdown. */
    fun onSkipIntroNow() {
        val intro = _uiState.value.intro ?: return
        onSeek(intro.end)
        introAutoSkipController.cancelCountdown()
    }

    /** Cancel an in-flight auto-skip countdown — banner falls back to the manual Skip button. */
    fun onCancelIntroAutoSkip() {
        introAutoSkipController.cancelCountdown()
    }

    /** Manual "Next episode" — a deliberate action, so it resets the pass-out streak. */
    fun onNextEpisode() {
        // Watch Together is authoritative — a room member can't drive transport
        // locally (matches the auto-advance guard), so ignore the tap in a room.
        if (remoteTransportSuppressed) return
        autoPlayGuard.recordUserAction()
        advanceToNextEpisode()
    }

    // ---- F2 next-episode auto-advance + pass-out protection ----

    /**
     * Resolve the next episode for this item (no-op for movies). Pools the
     * current season's episodes plus the next REGULAR season's (specials
     * excluded) and finds the immediate next via [nextEpisodeAfter]. The current
     * season must load (a partial failure must not skip the rest of it).
     */
    private fun resolveNextEpisode() {
        val state = _uiState.value
        val seriesId = state.seriesId ?: return
        val curSeason = state.seasonNumber ?: return
        val curEpisode = state.episodeNumber ?: return
        // The episode this resolve is for — guards against a stale result from a
        // previous episode landing after an in-place reload swapped the content.
        val forContentId = state.contentId
        resolveNextEpisodeJob?.cancel()
        resolveNextEpisodeJob = viewModelScope.launch {
            val currentSeasonEpisodes =
                (catalogRepository.getEpisodes(seriesId, curSeason) as? ApiResult.Success)
                    ?.data?.episodes ?: return@launch
            val pool = currentSeasonEpisodes.toMutableList()
            val nextRegularSeason = (catalogRepository.getSeasons(seriesId) as? ApiResult.Success)
                ?.data?.seasons
                ?.filter { !it.isSpecials && it.seasonNumber > curSeason }
                ?.minByOrNull { it.seasonNumber }
            if (nextRegularSeason != null) {
                (catalogRepository.getEpisodes(seriesId, nextRegularSeason.seasonNumber) as? ApiResult.Success)
                    ?.data?.episodes?.let { pool += it }
            }
            val next = nextEpisodeAfter(pool, curSeason, curEpisode) ?: return@launch
            _uiState.update {
                // Drop the result if the player has since moved to another item.
                if (it.contentId != forContentId) return@update it
                it.copy(
                    nextEpisodeContentId = next.contentId,
                    nextEpisodeLabel = "S${next.seasonNumber}·E${next.episodeNumber}" +
                        (next.title?.let { t -> " — $t" } ?: ""),
                )
            }
        }
    }

    /**
     * Credits reached (or stream ended) — auto-advance to the next episode, or
     * once the consecutive-auto-advance streak hits the guard's threshold show
     * the "Still watching?" prompt instead. Once-per-episode.
     */
    fun onApproachingEnd() {
        if (autoAdvanceHandled) return
        // Watch Together is authoritative — never auto-advance a room member.
        if (remoteTransportSuppressed) return
        if (_uiState.value.nextEpisodeContentId == null) return
        if (!autoPlayNextEnabled.value) return
        autoAdvanceHandled = true
        if (autoPlayGuard.shouldGate()) {
            _uiState.update { it.copy(stillWatchingPrompt = true) }
        } else {
            autoPlayGuard.recordAutoAdvance()
            advanceToNextEpisode()
        }
    }

    /**
     * Loads the resolved next episode in place, starting from the beginning.
     *
     * The finished episode's session/player are torn down FIRST: this player
     * reloads in place (the same ViewModel persists), so without an explicit
     * stop the old server session is orphaned and a late STATE_ENDED from the
     * old media could fire [onApproachingEnd] again on the next episode (which
     * has already re-armed [autoAdvanceHandled]) and double-count the streak.
     * Starting at 0.0 with rewind suppressed gives a true fresh start rather
     * than inheriting the next episode's saved resume position.
     */
    private fun advanceToNextEpisode() {
        val nextContentId = _uiState.value.nextEpisodeContentId ?: return
        _uiState.update { it.copy(stillWatchingPrompt = false, showNextEpisode = false) }
        viewModelScope.launch {
            sessionLifecycle.stop()
            loadContent(
                contentId = nextContentId,
                resumePositionOverride = 0.0,
                suppressResumeRewind = true,
            )
        }
    }

    /** User tapped "Continue" on the prompt — reset the streak and play next. */
    fun onStillWatchingContinue() {
        autoPlayGuard.recordUserAction()
        advanceToNextEpisode()
    }

    /** User tapped "Stop" (or dismissed) — stay on the finished episode. */
    fun onStillWatchingStop() {
        _uiState.update { it.copy(stillWatchingPrompt = false) }
    }

    // ---- Settings setters (forward to per-profile DataStore) -------------------
    fun onSetPlaybackSpeed(value: Double) {
        viewModelScope.launch { playerSettingsStore.setPlaybackSpeed(value) }
    }

    fun onSetVideoGravity(value: String) {
        viewModelScope.launch { playerSettingsStore.setVideoGravity(value) }
    }

    fun onSetAutoSkipIntro(value: Boolean) {
        viewModelScope.launch { playerSettingsStore.setAutoSkipIntro(value) }
    }

    fun onSetAutoPlayNext(value: Boolean) {
        viewModelScope.launch { playerSettingsStore.setAutoPlayNext(value) }
    }

    fun onSetHdrEnabled(value: Boolean) {
        viewModelScope.launch { playerSettingsStore.setHdrEnabled(value) }
    }

    fun onSetSubtitleAppearance(value: SubtitleAppearance) {
        viewModelScope.launch { playerSettingsStore.setSubtitleAppearance(value) }
    }

    /**
     * Audio delay setter (ms). Store clamps to ±5000ms — matches iOS phone's
     * `audioSyncMs` range. ContinuumPlaybackService mirrors the change into
     * DelayAudioProcessor and forces a seekTo(currentPosition) so the new
     * value takes effect mid-playback.
     */
    fun onSetAudioDelay(value: Int) {
        viewModelScope.launch { playerSettingsStore.setAudioSyncMs(value) }
    }

    /**
     * Subtitle delay setter (ms). Store clamps to ±10000ms — matches iOS
     * phone's `subtitleSyncMs` range. ContinuumPlaybackService mirrors the
     * change into SubtitleOffsetHolder; OffsetSubtitleParserFactory reads
     * the new offset at every cue parse.
     */
    fun onSetSubtitleDelay(value: Int) {
        viewModelScope.launch { playerSettingsStore.setSubtitleSyncMs(value) }
    }

    // ---- Sleep timer setters ---------------------------------------------------
    /**
     * Start (or restart) the sleep timer for [minutes]. Also persists the
     * choice as the new default duration so the picker remembers it next time.
     */
    fun onStartSleepTimer(minutes: Int) {
        sleepTimer.start(minutes)
        if (minutes > 0) {
            viewModelScope.launch { playerSettingsStore.setSleepTimerDefaultMinutes(minutes) }
        }
    }

    /** Cancel an active sleep timer. No-op when idle. */
    fun onCancelSleepTimer() {
        sleepTimer.cancel()
    }

    /**
     * Select a different file version for playback.
     * Stops the current session and starts a new one with the selected version.
     */
    fun onSelectVersion(index: Int) {
        val currentState = _uiState.value
        val versions = currentState.versions
        if (index < 0 || index >= versions.size) return
        if (index == currentState.selectedVersionIndex) return

        val currentPosition = currentState.position

        viewModelScope.launch {
            val lifecycleSessionId = (sessionLifecycle.state.value as? SessionState.Active)
                ?.session
                ?.sessionId
            currentState.sessionId?.let { sessionId ->
                sessionLifecycle.stop()
                if (sessionId != lifecycleSessionId) {
                    playbackSessionManager.stopSession(sessionId)
                }
            }
            // Cancel any in-flight intro skip countdown — we're loading a new version.
            introAutoSkipController.reset()
            engineSwitchFallbackAttempted = false

            _uiState.update { it.copy(isLoading = true, selectedVersionIndex = index, sessionId = null) }

            val version = versions[index]
            val profileId = profileRepository.getActiveProfileId() ?: return@launch
            val capabilities = capabilityDetector.detect()
            val playbackContext = capabilityDetector.detectPlaybackContext(
                formFactor = "mobile",
                appVersion = BuildConfig.VERSION_NAME,
            )
            val requestedPlayMethod = version.requestedOriginalPlaybackMethod(
                playbackContext = playbackContext,
                audioTrackIndex = currentState.selectedAudioIndex,
            )
            val preserveDirectSelection = requestedPlayMethod == PlayMethod.DIRECT

            val result = playbackSessionManager.startSessionV2(
                fileId = version.fileId,
                profileId = profileId,
                capabilities = capabilities,
                audioTrackIndex = currentState.selectedAudioIndex,
                subtitleTrackIndex = currentState.selectedSubtitleIndex,
                startPosition = currentPosition,
                clientPlaybackContext = playbackContext,
                preserveDirectAudioSelection = preserveDirectSelection,
                playMethod = requestedPlayMethod,
            )

            when (result) {
                is ApiResult.Success -> {
                    val session = result.data
                    val immediateFallbackMode = session.playbackPlan?.immediateServerFallbackMode()
                    val resolved = when {
                        session.canPlayResolvedStreamDirectly() && immediateFallbackMode == null -> session
                        immediateFallbackMode != null ||
                            session.playMethod == PlayMethod.REMUX ||
                            session.playMethod == PlayMethod.TRANSCODE -> {
                            val mode = immediateFallbackMode ?: if (session.playMethod == PlayMethod.REMUX) {
                                PlaybackSessionManager.TranscodeMode.REMUX
                            } else {
                                PlaybackSessionManager.TranscodeMode.FULL
                            }
                            when (val fallback = playbackSessionManager.startTranscodeFallback(
                                session = session,
                                seekSeconds = currentPosition,
                                resolution = version.resolution.orEmpty(),
                                mode = mode,
                                audioTrackIndex = session.audioTrackIndex,
                                subtitleTrackIndex = currentState.selectedSubtitleIndex,
                            )) {
                                is ApiResult.Success -> fallback.data
                                is ApiResult.Error -> {
                                    _uiState.update {
                                        it.copy(
                                            isLoading = false,
                                            error = "Failed to switch version: ${fallback.message}",
                                        )
                                    }
                                    return@launch
                                }
                                is ApiResult.NetworkError -> {
                                    _uiState.update {
                                        it.copy(
                                            isLoading = false,
                                            error = "Network error: ${fallback.exception.message}",
                                        )
                                    }
                                    return@launch
                                }
                            }
                        }
                        else -> session
                    }
                    val resolvedDelivery = resolved.resolvedPlaybackDelivery()
                    val resolvedStreamUrl = resolved.playbackPlan?.stream?.url
                        ?.takeIf { it.isNotBlank() }
                        ?: resolved.streamUrl
                    val resolvedStartPosition = if (
                        resolved.sessionId != session.sessionId ||
                        resolved.playMethod != session.playMethod
                    ) {
                        resolved.position.takeIf { it.isFinite() && it >= 0.0 } ?: currentPosition
                    } else {
                        currentPosition
                    }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            sessionId = resolved.sessionId,
                            playMethod = resolved.playMethod,
                            playbackPlan = resolved.playbackPlan,
                            delivery = resolvedDelivery,
                            streamUrl = resolvedStreamUrl,
                            container = version.container,
                            startPosition = resolvedStartPosition,
                            position = resolvedStartPosition,
                            duration = resolved.durationSeconds ?: version.duration,
                            audioTracks = version.audioTracks ?: emptyList(),
                            selectedAudioIndex = resolved.audioTrackIndex,
                            subtitleTracks = resolved.subtitleUrls ?: emptyList(),
                            chapters = version.chapters.orEmpty(),
                        )
                    }
                    // Restart lifecycle reporting against the active session
                    // without creating another server playback session.
                    sessionLifecycle.adoptActiveSession(
                        params = StartParams(
                            contentId = currentState.contentId,
                            fileId = version.fileId,
                            capabilities = capabilities,
                            audioTrackIndex = resolved.audioTrackIndex,
                            subtitleTrackIndex = currentState.selectedSubtitleIndex,
                            qualityPreference = null,
                            startPosition = resolvedStartPosition,
                            clientPlaybackContext = playbackContext,
                            preserveDirectAudioSelection = preserveDirectSelection,
                            playMethod = requestedPlayMethod,
                        ),
                        session = resolved,
                    )
                    // Resume the intro auto-skip observer; the introKey now embeds the new
                    // sessionId/fileId so any prior cancellation does not carry over.
                    startIntroAutoSkipObserver()
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(isLoading = false, error = "Failed to switch version: ${result.message}")
                    }
                }
                is ApiResult.NetworkError -> {
                    _uiState.update {
                        it.copy(isLoading = false, error = "Network error: ${result.exception.message}")
                    }
                }
            }
        }
    }

    /** Toggle controls visibility. */
    fun onToggleControls() {
        _uiState.update { it.copy(showControls = !it.showControls) }
        if (_uiState.value.showControls) {
            scheduleControlsHide()
        }
    }

    /** Show controls and reset the auto-hide timer. */
    fun onShowControls() {
        _uiState.update { it.copy(showControls = true) }
        scheduleControlsHide()
    }

    /** Called when the user exits the player. */
    fun onExit() {
        viewModelScope.launch {
            // Track B: durably record the final position for both paths, then ask
            // the outbox to drain promptly (covers the online offline-download case
            // where there's no live session and no connectivity change to trigger it).
            val cid = _uiState.value.contentId.takeIf { it.isNotBlank() }
            val fid = currentFileId()
            if (cid != null && fid != null) {
                userItemStatePort.recordPosition(
                    cid,
                    fid,
                    _uiState.value.position,
                    _uiState.value.duration.takeIf { it > 0.0 },
                )
                outboxSyncScheduler.requestSync()
            }
            // Lifecycle.stop() handles: final progress report, snapshot via PersonalData,
            // session stop, and reporter cancellation. The single call replaces the
            // duplicated reportProgress/stopSession + syncProgressSnapshot flow.
            sessionLifecycle.stop()
            controlsHideJob?.cancel()
            introObserverJob?.cancel()
            searchJob?.cancel()
            aiJobHandle?.cancel()
            introAutoSkipController.reset()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    sessionId = null,
                    playMethod = null,
                    playbackPlan = null,
                    delivery = null,
                    streamUrl = null,
                    container = null,
                    subtitleTracks = emptyList(),
                    isPaused = true,
                    isPlaying = false,
                )
            }
        }
    }

    private fun scheduleControlsHide() {
        controlsHideJob?.cancel()
        controlsHideJob = viewModelScope.launch {
            delay(CONTROLS_AUTO_HIDE_MS)
            val state = _uiState.value
            // Only auto-hide if playing (not paused and not buffering)
            if (state.isPlaying && !state.isPaused && !state.isBuffering) {
                _uiState.update { it.copy(showControls = false) }
            }
        }
    }

    private fun buildSubtitle(watchDetail: com.continuum.app.model.catalog.WatchDetail): String {
        return if (watchDetail.seriesTitle != null && watchDetail.seasonNumber != null && watchDetail.episodeNumber != null) {
            val seasonEp = "S${watchDetail.seasonNumber.toString().padStart(2, '0')}E${watchDetail.episodeNumber.toString().padStart(2, '0')}"
            "${watchDetail.seriesTitle} - $seasonEp"
        } else {
            watchDetail.year?.toString() ?: ""
        }
    }

    private fun findPreferredVersion(
        watchDetail: com.continuum.app.model.catalog.WatchDetail,
        preferredFileId: Int?,
        preferredQuality: String?,
    ): Int {
        if (preferredFileId != null) {
            val index = watchDetail.versions.indexOfFirst { it.fileId == preferredFileId }
            if (index >= 0) return index
        }
        // If the user has a last-used file ID, prefer that version
        val lastFileId = watchDetail.userData?.lastFileId
        if (lastFileId != null) {
            val index = watchDetail.versions.indexOfFirst { it.fileId == lastFileId }
            if (index >= 0) return index
        }
        val qualityIndex = preferredVersionIndex(watchDetail.versions, preferredQuality)
        if (qualityIndex >= 0) return qualityIndex
        return 0
    }

    private fun preferredVersionIndex(versions: List<FileVersion>, preferredQuality: String?): Int {
        val target = preferredQuality?.lowercase().orEmpty()
        if (target.isBlank() || target == "auto") return -1
        val preferredRank = resolutionRank(target)
        return versions.withIndex()
            .sortedByDescending { (_, version) -> resolutionRank(version.resolution) }
            .firstOrNull { (_, version) ->
                target == "original" || resolutionRank(version.resolution) <= preferredRank
            }
            ?.index ?: -1
    }

    private fun resolutionRank(value: String?): Int {
        val normalized = value?.lowercase().orEmpty()
        return when {
            normalized.contains("2160") || normalized.contains("4k") -> 2160
            normalized.contains("1080") -> 1080
            normalized.contains("720") -> 720
            normalized.contains("480") -> 480
            else -> 0
        }
    }


    /**
     * Offline-first playback path. Returns true (and populates UiState with a
     * file:// stream URL) when the requested content has a completed local
     * download whose bytes are still on disk. Returning false means the
     * caller should run the normal server-backed flow.
     *
     * Best-effort metadata: we try to fetch [com.continuum.app.repository.CatalogRepository.getWatchDetail]
     * for the title / subtitle, but tolerate failure (true offline). The
     * server-side session start, lifecycle reporter, and intro-skip observer
     * are skipped — none of them work without network and none are required
     * to actually play the local bytes.
     */
    private suspend fun tryLocalPlayback(
        contentId: String,
        preferredFileId: Int?,
        resumePositionOverride: Double?,
    ): Boolean {
        val media = withContext(kotlinx.coroutines.Dispatchers.IO) {
            val (serverId, profileId) = resolveDownloadScope()
            offlineMediaResolver.findLocalMedia(
                serverId = serverId,
                profileId = profileId,
                contentId = contentId,
                requestedFileId = preferredFileId,
            )
        } ?: return false
        val sidecar = media.sidecar
        val fileId = media.fileId

        // Best-effort online metadata (richer fields: intro/credits/chapters).
        // Network failure is fine; the sidecar already has title + poster
        // so airplane-mode playback still has something to render.
        val watchDetail = when (val r = catalogRepository.getWatchDetail(contentId)) {
            is ApiResult.Success -> r.data
            else -> null
        }
        val title = watchDetail?.title ?: sidecar.title
        val subtitle = watchDetail?.let { buildSubtitle(it) } ?: sidecar.subtitle.orEmpty()
        val versions = watchDetail?.versions?.takeIf { it.isNotEmpty() }
            ?: listOf(
                com.continuum.app.model.catalog.FileVersion(fileId = fileId),
            )
        val selectedIndex = versions.indexOfFirst { it.fileId == fileId }
            .coerceAtLeast(0)
        // Offline-safe resume: the server's watchDetail may be stale or absent in
        // airplane mode, so fold in the locally-recorded position and take the
        // furthest of the two (matches the server's GREATEST semantics).
        val localPos = userItemStatePort.localPosition(contentId, fileId)
        val detailPos = listOfNotNull(watchDetail?.userData?.positionSeconds, localPos).maxOrNull()
        val startPos = resolvePlaybackStartPosition(
            overridePosition = resumePositionOverride,
            sessionPosition = 0.0,
            detailPosition = detailPos,
        )
        val artworkUrl = watchDetail?.posterUrl?.takeIf { url -> url.isNotBlank() }
            ?: watchDetail?.backdropUrl?.takeIf { url -> url.isNotBlank() }
            ?: sidecar.posterUrl?.takeIf { url -> url.isNotBlank() }

        _uiState.update {
            it.copy(
                isLoading = false,
                error = null,
                title = title,
                subtitle = subtitle,
                artworkUrl = artworkUrl,
                // Playback fields — file:// is read directly by Media3, no
                // server session needed.
                streamUrl = media.uriString,
                playMethod = com.continuum.app.model.playback.PlayMethod.DIRECT,
                playbackPlan = null,
                delivery = null,
                serverUrl = "",   // unused for local files
                accessToken = "",
                startPosition = startPos,
                position = startPos,
                duration = watchDetail?.versions?.firstOrNull { v -> v.fileId == fileId }?.duration ?: 0.0,
                isPlaying = true,
                isPaused = false,
                versions = versions,
                selectedVersionIndex = selectedIndex,
                audioTracks = versions[selectedIndex].audioTracks ?: emptyList(),
                subtitleTracks = emptyList(),  // sidecars are remote in v1
                intro = watchDetail?.intro,
                credits = watchDetail?.credits,
                chapters = versions[selectedIndex].chapters.orEmpty(),
                seriesId = watchDetail?.seriesId,
                preferredAudioLanguage = null,
                preferredTextLanguage = null,
            )
        }
        android.util.Log.i(
            "PlayerViewModel",
            "tryLocalPlayback: serving ${media.displayName} (${media.sizeBytes}B) for content=$contentId (sidecar id=${sidecar.record.id})",
        )
        return true
    }

    override fun onCleared() {
        super.onCleared()
        // Guarantee the final resume position is persisted on teardown. onExit's
        // write runs in viewModelScope, which is cancelling here — so AWAIT one
        // last write under NonCancellable (brief local Room write off the main
        // thread). Without this, exiting while playing could lose the last spot.
        val cid = _uiState.value.contentId.takeIf { it.isNotBlank() }
        val fid = currentFileId()
        if (cid != null && fid != null) {
            runCatching {
                runBlocking(NonCancellable + Dispatchers.IO) {
                    userItemStatePort.recordPosition(
                        cid,
                        fid,
                        _uiState.value.position,
                        _uiState.value.duration.takeIf { it > 0.0 },
                    )
                }
            }
        }
        controlsHideJob?.cancel()
        introObserverJob?.cancel()
        lifecycleObserverJob?.cancel()
        searchJob?.cancel()
        aiJobHandle?.cancel()
        introAutoSkipController.reset()
        // Best-effort session stop. Lifecycle.stop() is suspend-based and may not
        // complete after onCleared (viewModelScope is cancelling) — fire & forget,
        // preferring at least one of the two paths to durably persist progress.
        val sessionId = _uiState.value.sessionId
        if (sessionId != null) {
            viewModelScope.launch {
                playbackSessionManager.stopSession(sessionId)
            }
        }
    }

    private suspend fun resolveDownloadScope(): Pair<String, String> {
        val serverId = serverRegistry.activeServerId.value ?: DownloadEnqueuer.DEFAULT_SERVER_ID
        val profileId = profileRepository.getActiveProfileId() ?: DownloadEnqueuer.DEFAULT_PROFILE_ID
        return serverId to profileId
    }
}
