package org.siloserver.silo.common.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.audiobook.AudioPlaybackTrack
import org.siloserver.silo.audiobook.AudiobookChapter
import org.siloserver.silo.audiobook.AudiobookChapters
import org.siloserver.silo.audiobook.AudiobookTimeline
import org.siloserver.silo.audiobook.buildAudiobookTimeline
import org.siloserver.silo.common.audiobook.AudiobookBookmarksStore
import org.siloserver.silo.common.downloads.DownloadEnqueuer
import org.siloserver.silo.common.downloads.OfflineMediaResolver
import org.siloserver.silo.model.audiobook.AudiobookBookmark
import org.siloserver.silo.model.catalog.VersionChapter
import org.siloserver.silo.model.playback.QUALITY_ORIGINAL_V3
import org.siloserver.silo.model.playback.ClientCodecCapabilities
import org.siloserver.silo.model.playback.ClientPlaybackContext
import org.siloserver.silo.model.playback.PlaybackTimeline
import org.siloserver.silo.model.playback.ProgressPersistenceV3
import org.siloserver.silo.model.playback.resolvePlaybackStartPosition
import org.siloserver.silo.model.playback.resolvePlaybackStartRequestPosition
import org.siloserver.silo.common.player.seek.PlaybackSeekDecision
import org.siloserver.silo.common.player.seek.decideSeek
import org.siloserver.silo.common.player.seek.sourcePositionForPlayer
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.random.Random

/**
 * UI state for the audiobook player.
 *
 * Distinct from the video PlayerViewModel because the audiobook player
 * cares about chapter navigation, speed, and sleep-timer state rather
 * than tracks / subtitles / aspect ratios.
 */
data class AudiobookPlayerUiState(
    val isLoading: Boolean = true,
    val title: String = "",
    val author: String? = null,
    val narrator: String? = null,
    val overview: String? = null,
    val coverUrl: String? = null,
    val coverThumbhash: String? = null,
    val chapters: List<VersionChapter> = emptyList(),
    val durationSeconds: Double = 0.0,
    val positionSeconds: Double = 0.0,
    val isPlaying: Boolean = false,
    // Default to "not paused" so the player auto-plays on open: the Compose
    // layer mirrors isPaused into Media3's playWhenReady, and a `true` default
    // raced the resume/auto-play wiring and left the book paused at 0:00 until
    // the user hit play.
    val isPaused: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    val skipBackSeconds: Int = 30,
    val skipForwardSeconds: Int = 30,
    val sleepTimerMinutesLeft: Int? = null,
    val streamUrl: String? = null,
    val sessionId: String? = null,
    val selectedFileId: Int? = null,
    val error: String? = null,
)

/**
 * Drives the [AudiobookPlayerScreen]. Wraps Media3 for actual decoding
 * (same engine as the video player) but exposes audiobook-shaped
 * commands: [seekBy30], [setSpeed], [jumpToChapter], [startSleepTimer].
 */
class AudiobookPlayerViewModel(
    private val catalogRepository: CatalogRepository,
    private val playbackSessionManager: PlaybackSessionManager,
    private val playbackSessionLifecycle: PlaybackSessionLifecycle,
    private val capabilityDetector: PlaybackCapabilityDetector,
    private val bookmarksStore: AudiobookBookmarksStore,
    // Track B: durable position via the unified outbox (replaces AudiobookPositionStore
    // + AudiobookProgressSyncer — same furthest-wins syncProgress semantics).
    private val userItemStatePort: org.siloserver.silo.repository.port.UserItemStatePort,
    private val outboxSyncScheduler: org.siloserver.silo.common.data.sync.OutboxSyncScheduler,
    private val serverRegistry: ServerRegistry,
    private val profileRepository: ProfileRepository,
    private val offlineMediaResolver: OfflineMediaResolver,
    private val audiobookSettings: AudiobookSettingsStore,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val contentId: String = savedStateHandle.get<String>("contentId") ?: ""
    private val requestedFileIdRaw: String? = savedStateHandle.get<String>("fileId")
    private val hasRequestedFileId: Boolean = !requestedFileIdRaw.isNullOrBlank()
    private val requestedFileId: Int? = requestedFileIdRaw?.toIntOrNull()
    private val requestedStartPositionRaw: String? = savedStateHandle.get<String>("startPosition")
    private val requestedStartPosition: Double? = requestedStartPositionRaw
        ?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it >= 0.0 }

    /** When true ("Play from beginning"), ignore saved progress and start at 0. */
    private val startFromBeginning: Boolean = savedStateHandle.get<Boolean>("fromStart") ?: false

    private val _uiState = MutableStateFlow(AudiobookPlayerUiState())
    val uiState: StateFlow<AudiobookPlayerUiState> = _uiState.asStateFlow()

    // Chapter-derived views over ui-state, computed via the pure
    // [AudiobookChapters] math so the phone and TV players share one source of
    // truth. Eagerly started so the UI has a value the moment it subscribes.

    /** Index of the chapter the current position falls in. */
    val currentChapterIndex: StateFlow<Int> = uiState
        .map { AudiobookChapters.currentIndex(it.chapters.toAudiobookChapters(), it.positionSeconds) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** Progress within the current chapter, 0..1. */
    val chapterProgress: StateFlow<Float> = uiState
        .map {
            AudiobookChapters.chapterProgress(
                it.chapters.toAudiobookChapters(),
                it.positionSeconds,
            ).toFloat()
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0f)

    /** "Chapter N of M" label, or empty when the book has 0/1 chapters. */
    val chapterCountLabel: StateFlow<String> = uiState
        .map { AudiobookChapters.countLabel(it.chapters.toAudiobookChapters(), it.positionSeconds).orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _bookmarks = MutableStateFlow<List<AudiobookBookmark>>(emptyList())
    val bookmarks: StateFlow<List<AudiobookBookmark>> = _bookmarks.asStateFlow()

    /** Position the player should seek to on first prepare. Resolved from
     *  the local snapshot at init; the Compose layer reads it via
     *  [resumePositionSeconds] and consumes [consumeResumePosition] once
     *  it's applied. Server-side resume can replace this later. */
    private val _resumePosition = MutableStateFlow<Double?>(null)
    val resumePositionSeconds: StateFlow<Double?> = _resumePosition.asStateFlow()

    /** Guards the one-time seed of [AudiobookPlayerUiState.playbackSpeed] from
     *  the persisted default; once seeded, in-session speed changes win. */
    private var speedSeeded = false

    // ── Multi-part (whole-book) timeline state ────────────────────────────
    //
    // Faithful port of Apple's AudioPlayerViewModel: the server has no concept
    // of a whole book, so the client stitches the item's audiobook-part files
    // into one virtual [AudiobookTimeline] and drives playback ONE PART AT A
    // TIME. The engine (Media3, wired in the Compose layer) always plays a
    // single part's stream and reports a *part-local* time; this VM converts to
    // whole-book (global) time for the UI and for durable resume, and reports
    // part-local time to the per-part playback session.
    //
    // Only populated on the ONLINE streaming path. Offline / no-audio-part items
    // leave it null and behave exactly as the pre-timeline single-file player.

    /** Whole-book timeline for the current item, or null for the single-file
     *  fallback (offline playback, or an item with no stitched audio parts). */
    private var timeline: AudiobookTimeline? = null

    /** Index of the part currently loaded in the engine. Null until the first
     *  part loads or on the single-file fallback. */
    private var activeTrackIndex: Int? = null

    /** Source/player mapping for the protocol-v3 transport mounted in Media3. */
    private var activePlaybackTimeline: PlaybackTimeline? = null
    /** Server-declared full runtime for the active effective file; null is unknown. */
    private var activePlaybackSourceDurationSeconds: Double? = null

    /** Invalidates an in-flight [loadTrack] when the user seeks again, the book
     *  advances, or the player closes while `/playback/start` is still on the
     *  wire (Apple `loadGeneration`). */
    private var loadGeneration = 0

    /** Serializes book loads so a slower, older [loadDetail] cannot overwrite the
     *  context of a newer one (Apple `startGeneration`). Kept separate from
     *  [loadGeneration], which every cross-part [loadTrack] bumps. */
    private var startGeneration = 0

    /** Set while the player is tearing down so an in-flight cross-part load
     *  aborts instead of resurrecting a stopped session (Apple `isClosing`). */
    private var isClosing = false

    /** The part-local start position the engine is being pointed at during a
     *  cross-part load. While non-null the engine's reported time still belongs
     *  to the outgoing part for a frame or two (the 4Hz poller is decoupled from
     *  the stream swap), so position mapping and end-of-part detection are
     *  suppressed until the newly-loaded stream settles near this value. */
    private var pendingTrackLoadLocalStart: Double? = null

    /** Set when the loaded stream is ONE PART of a multi-part book played
     *  offline: playback then runs entirely in PART-LOCAL space (no engine to
     *  cross parts), so the durable whole-book sink must be skipped — writing a
     *  part-local position against the book's total would corrupt resume /
     *  Continue Listening everywhere. */
    private var suppressWholeBookPersistence = false

    init {
        observeAudiobookSettings()
        observeMissingPlaybackSessions()
        if (contentId.isNotBlank()) {
            loadDetail()
            loadBookmarks()
            startPeriodicPositionSave()
        }
    }

    private fun observeMissingPlaybackSessions() {
        viewModelScope.launch {
            playbackSessionLifecycle.missingSessionEvents.collect { renewal ->
                val state = _uiState.value
                if (
                    isClosing ||
                    state.sessionId != renewal.staleSessionId ||
                    renewal.startParams.contentId != contentId ||
                    renewal.startParams.fileId != state.selectedFileId
                ) {
                    return@collect
                }
                val profileId = profileRepository.getActiveProfileId() ?: return@collect
                val generation = ++loadGeneration
                val trackIndex = activeTrackIndex
                when (
                    val playback = startPartSession(
                        fileId = renewal.startParams.fileId,
                        profileId = profileId,
                        startPosition = renewal.positionSeconds,
                        capabilities = renewal.startParams.capabilities,
                        clientPlaybackContext = renewal.startParams.clientPlaybackContext,
                    )
                ) {
                    is ApiResult.Success -> {
                        val start = playback.data
                        if (generation != loadGeneration || isClosing) {
                            if (start is VideoSessionStartV3.Ready) {
                                runCatching {
                                    playbackSessionManager.stopSession(start.session.sessionId)
                                }
                            }
                        } else if (start is VideoSessionStartV3.Ready) {
                            applyStartedSession(
                                ready = start,
                                localSeek = renewal.positionSeconds,
                                globalPosition = state.positionSeconds,
                                trackIndex = trackIndex,
                                fileId = renewal.startParams.fileId,
                                isCurrent = {
                                    generation == loadGeneration &&
                                        !isClosing &&
                                        _uiState.value.sessionId == renewal.staleSessionId
                                },
                            )
                        } else {
                            applyFailedSessionStart(
                                start.failureMessage(),
                                expectedSessionId = renewal.staleSessionId,
                            )
                        }
                    }
                    is ApiResult.Error -> if (
                        generation == loadGeneration &&
                        !isClosing &&
                        _uiState.value.sessionId == renewal.staleSessionId
                    ) {
                        applyFailedSessionStart(
                            playback.message,
                            expectedSessionId = renewal.staleSessionId,
                        )
                    }
                    is ApiResult.NetworkError -> if (
                        generation == loadGeneration &&
                        !isClosing &&
                        _uiState.value.sessionId == renewal.staleSessionId
                    ) {
                        applyFailedSessionStart(
                            playback.exception.message ?: "Network error",
                            expectedSessionId = renewal.staleSessionId,
                        )
                    }
                }
            }
        }
    }

    /** Mirror the persisted skip interval into ui-state, and seed playback
     *  speed from the saved default exactly once. */
    private fun observeAudiobookSettings() {
        viewModelScope.launch {
            audiobookSettings.skipBackSecondsFlow.collect { seconds ->
                _uiState.update { it.copy(skipBackSeconds = seconds) }
            }
        }
        viewModelScope.launch {
            audiobookSettings.skipForwardSecondsFlow.collect { seconds ->
                _uiState.update { it.copy(skipForwardSeconds = seconds) }
            }
        }
        viewModelScope.launch {
            val savedDefault = audiobookSettings.defaultSpeedFlow.first()
            if (!speedSeeded) {
                speedSeeded = true
                _uiState.update { it.copy(playbackSpeed = savedDefault.coerceIn(0.5f, 3.0f)) }
            }
        }
    }

    private fun loadDetail() {
        // Bump the start generation before the item-detail load so a slower,
        // older load can't overwrite the context of a newer one (Apple parity).
        val generation = ++startGeneration
        viewModelScope.launch {
            when (val r = catalogRepository.getItemDetail(contentId)) {
                is ApiResult.Success -> {
                    if (generation != startGeneration) return@launch
                    val d = r.data
                    if (hasRequestedFileId && requestedFileId == null) {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = "Selected audiobook file is unavailable.",
                            )
                        }
                        return@launch
                    }

                    val selectedVersion = if (requestedFileId != null) {
                        d.versions.firstOrNull { it.fileId == requestedFileId }
                    } else {
                        d.versions.firstOrNull()
                    }

                    if (selectedVersion == null) {
                        val message = if (hasRequestedFileId) {
                            "Selected audiobook file is unavailable."
                        } else {
                            "No playable audiobook file is available."
                        }
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = message,
                            )
                        }
                        return@launch
                    }

                    val explicitStartOverride = when {
                        requestedStartPosition != null -> requestedStartPosition
                        startFromBeginning -> 0.0
                        else -> null
                    }
                    val resumePosition = if (explicitStartOverride != null) {
                        _resumePosition.value = explicitStartOverride.takeIf { it > 0.0 }
                        null
                    } else {
                        loadResumePositionSnapshot(d.userData?.positionSeconds)
                    }
                    val requestStartPosition = resolvePlaybackStartRequestPosition(
                        overridePosition = explicitStartOverride,
                        detailPosition = resumePosition,
                    )
                    val (downloadServerId, downloadProfileId) = resolveScope()
                    val offlineMedia = offlineMediaResolver.findLocalMedia(
                        serverId = downloadServerId,
                        profileId = downloadProfileId,
                        contentId = d.contentId,
                        requestedFileId = selectedVersion.fileId,
                        allowFallback = !hasRequestedFileId,
                    )
                    // Stitch the item's audiobook-part files into one whole-book
                    // timeline (Apple parity). Used for the ONLINE path only; the
                    // whole-book total stays the exposed [durationSeconds]. Null
                    // when there are no audio parts — the single-file fallback
                    // below then behaves exactly as before.
                    val builtTimeline = buildAudiobookTimeline(
                        versions = d.versions,
                        serverTotalSeconds = d.audiobook?.totalDurationSeconds?.toDouble(),
                        preferredFileId = selectedVersion.fileId,
                    )
                    val wholeBookDuration = builtTimeline?.totalSeconds
                        ?: d.audiobook?.totalDurationSeconds?.toDouble()
                        ?: selectedVersion.duration
                    // OFFLINE multi-part gating: offline playback streams exactly
                    // ONE downloaded part file with no cross-part engine, so the
                    // player must run entirely in PART-LOCAL space — whole-book
                    // duration/chapters/resume against a single part's stream
                    // corrupts the slider, seeks, and durable resume.
                    val offlinePart =
                        if (offlineMedia != null && builtTimeline != null && !builtTimeline.isSingle) {
                            builtTimeline.tracks.firstOrNull { it.fileId == offlineMedia.fileId }
                        } else {
                            null
                        }
                    // Offline playback streams a single file, so keep that file's
                    // own chapters; the online whole-book path uses the timeline's
                    // globally-offset chapters so the slider/chapter math all runs
                    // in whole-book space.
                    val displayChapters = when {
                        offlinePart != null ->
                            d.versions.firstOrNull { it.fileId == offlinePart.fileId }
                                ?.chapters.orEmpty()
                        offlineMedia == null && builtTimeline != null && !builtTimeline.isSingle ->
                            builtTimeline.toWholeBookChapters()
                        else -> selectedVersion.chapters.orEmpty()
                    }
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            title = d.title,
                            author = d.audiobook?.authorNames,
                            narrator = d.audiobook?.narratorNames,
                            overview = d.overview,
                            coverUrl = d.posterUrl,
                            coverThumbhash = d.posterThumbhash,
                            durationSeconds = offlinePart?.durationSeconds ?: wholeBookDuration,
                            chapters = displayChapters,
                            selectedFileId = offlinePart?.fileId ?: selectedVersion.fileId,
                            streamUrl = offlineMedia?.fileUrl,
                            sessionId = null,
                        )
                    }

                    if (offlineMedia != null) {
                        if (builtTimeline != null && offlinePart != null) {
                            // The durable snapshot is a WHOLE-BOOK position; map it
                            // through the timeline. Resume inside this part only
                            // when the global position falls within it, otherwise
                            // start at 0 — a global position must never be seeded
                            // as a stream seek into a single part's file.
                            suppressWholeBookPersistence = true
                            val globalResume = requestStartPosition ?: 0.0
                            val localResume =
                                if (builtTimeline.trackIndexAt(globalResume) == offlinePart.index) {
                                    builtTimeline.localTimeFor(globalResume, offlinePart)
                                } else {
                                    0.0
                                }
                            _resumePosition.value = localResume.takeIf { it > 0.0 }
                        } else {
                            _resumePosition.value = requestStartPosition?.takeIf { it > 0.0 }
                        }
                        _uiState.update { it.copy(error = null) }
                        return@launch
                    }

                    val profileId = profileRepository.getActiveProfileId()
                    if (profileId == null) {
                        _uiState.update { it.copy(error = "No active profile") }
                        return@launch
                    }

                    if (generation != startGeneration) return@launch

                    // RESUME (Apple start()): clamp the stored whole-book position
                    // to [0, total] and load the part that contains it. This
                    // replaces the old "start part 1 at the book-global position".
                    // [resolvePlaybackStartPosition] keeps override > detail > 0
                    // precedence; there is no whole-book server session, so the
                    // session-position input is 0.
                    val startGlobal = resolvePlaybackStartPosition(
                        overridePosition = explicitStartOverride,
                        sessionPosition = 0.0,
                        detailPosition = resumePosition,
                    )

                    if (builtTimeline == null) {
                        // No stitched audio parts: fall back to the single-file
                        // session so degenerate items still play.
                        startSingleFileSession(
                            fileId = selectedVersion.fileId,
                            profileId = profileId,
                            startGlobal = startGlobal,
                            generation = generation,
                        )
                        return@launch
                    }

                    timeline = builtTimeline
                    activeTrackIndex = null
                    activePlaybackTimeline = null
                    activePlaybackSourceDurationSeconds = null
                    loadTrack(atGlobalTime = startGlobal, autoplay = true)
                }
                is ApiResult.Error -> loadOfflineOnly(error = r.message)
                is ApiResult.NetworkError -> loadOfflineOnly(error = r.exception.message)
            }
        }
    }

    /** Clamp a whole-book time to `[0, total]` (Apple `clampGlobal`). */
    private fun clampGlobal(value: Double): Double {
        if (!value.isFinite()) return 0.0
        return value.coerceIn(0.0, _uiState.value.durationSeconds.coerceAtLeast(0.0))
    }

    /**
     * Load the part of the book containing [atGlobalTime] and point the engine
     * at the file-local offset within it (Apple `loadTrack(at:autoplay:)`).
     *
     * When the target lands in the part already loaded, the stream stays put and
     * we issue a file-local engine seek. Otherwise the current part's session is
     * retired and a fresh per-part session is started for the new part's file —
     * a fresh prepare per part is expected (playback is not gapless across parts,
     * mirroring Apple).
     *
     * On the single-file fallback ([timeline] null) callers use [seekTo] /
     * [startSingleFileSession] instead; this method is a no-op there.
     */
    private fun loadTrack(atGlobalTime: Double, autoplay: Boolean) {
        val tl = timeline ?: return
        val clamped = clampGlobal(atGlobalTime)
        val index = tl.trackIndexAt(clamped)
        val track = tl.tracks.firstOrNull { it.index == index } ?: return
        val localTime = tl.localTimeFor(clamped, track)

        if (activeTrackIndex == index && _uiState.value.sessionId != null) {
            seekActiveSession(
                sourceLocalSeconds = localTime,
                globalSeconds = clamped,
                autoplay = autoplay,
                trackIndex = index,
                fileId = track.fileId,
            )
            return
        }

        // Cross-part load. Suppress engine-time mapping/end-detection until the
        // new stream settles near [localTime] (the poller can still report the
        // outgoing part for a frame), and set the target position now so the UI
        // doesn't flash the old part's time. The outgoing part's final local
        // position is captured BEFORE that pre-write: retiring the old session
        // must report where the old part actually was, not the new target
        // mapped back into it.
        val outgoingState = _uiState.value
        val outgoingLocal = sessionLocalPosition(outgoingState)
        pendingTrackLoadLocalStart = localTime
        _uiState.update { it.copy(positionSeconds = clamped) }
        if (autoplay) _uiState.update { it.copy(isPaused = false) }

        val generation = ++loadGeneration
        viewModelScope.launch {
            val profileId = profileRepository.getActiveProfileId()
            if (profileId == null) {
                _uiState.update { it.copy(error = "No active profile") }
                return@launch
            }
            retireActiveSession(
                finalLocalPosition = outgoingLocal,
                finalGlobalPosition = outgoingState.positionSeconds,
                finalGlobalDuration = outgoingState.durationSeconds,
            )
            when (val playback = startPartSession(track.fileId, profileId, localTime)) {
                is ApiResult.Success -> {
                    val start = playback.data
                    if (generation != loadGeneration || isClosing) {
                        // Superseded by a newer seek/advance or a close while the
                        // request was in flight — release the session we no
                        // longer need (Apple parity).
                        if (start is VideoSessionStartV3.Ready) {
                            runCatching { playbackSessionManager.stopSession(start.session.sessionId) }
                        }
                        return@launch
                    }
                    if (start is VideoSessionStartV3.Ready) {
                        applyStartedSession(
                            ready = start,
                            localSeek = localTime,
                            globalPosition = clamped,
                            trackIndex = index,
                            fileId = track.fileId,
                            isCurrent = {
                                generation == loadGeneration &&
                                    !isClosing &&
                                    _uiState.value.sessionId == null
                            },
                        )
                    } else {
                        applyFailedSessionStart(start.failureMessage())
                    }
                }
                is ApiResult.Error -> {
                    if (generation != loadGeneration) return@launch
                    applyFailedSessionStart(playback.message.ifBlank { "Audiobook playback failed" })
                }
                is ApiResult.NetworkError -> {
                    if (generation != loadGeneration) return@launch
                    applyFailedSessionStart(playback.exception.message ?: "Network error")
                }
            }
        }
    }

    /**
     * Single-file fallback for items with no stitched audio parts ([timeline]
     * null). Preserves the pre-timeline behaviour: one session for the file,
     * whole-file position == whole-book position.
     */
    private suspend fun startSingleFileSession(
        fileId: Int,
        profileId: String,
        startGlobal: Double,
        generation: Int,
    ) {
        when (val playback = startPartSession(fileId, profileId, startGlobal)) {
            is ApiResult.Success -> {
                val start = playback.data
                if (generation != startGeneration || isClosing) {
                    if (start is VideoSessionStartV3.Ready) {
                        runCatching { playbackSessionManager.stopSession(start.session.sessionId) }
                    }
                    return
                }
                if (start is VideoSessionStartV3.Ready) {
                    applyStartedSession(
                        ready = start,
                        localSeek = startGlobal,
                        globalPosition = startGlobal,
                        trackIndex = null,
                        fileId = fileId,
                        isCurrent = { generation == startGeneration && !isClosing },
                    )
                } else {
                    applyFailedSessionStart(start.failureMessage())
                }
            }
            is ApiResult.Error -> if (generation == startGeneration && !isClosing) {
                applyFailedSessionStart(playback.message.ifBlank { "Audiobook playback failed" })
            }
            is ApiResult.NetworkError -> if (generation == startGeneration && !isClosing) {
                applyFailedSessionStart(playback.exception.message ?: "Network error")
            }
        }
    }

    /**
     * Start a per-part playback session for [fileId] at the file-local
     * [startPosition] (Apple `startSession(for:localTime:)`).
     *
     * The advertised capabilities are the device's real ones. Audiobooks used to
     * be started with still-image codecs (mjpeg/png/jpeg) spliced into
     * `codecsVideo`, because a cover-art picture was persisted as a video track
     * and the resolver then gated direct play on decoding it. Protocol v3 makes
     * that untenable and unnecessary: this client advertises
     * `video_evidence: "exact"`, so claiming decoders `MediaCodecList` never
     * enumerated would be a false attestation — and the server no longer records
     * cover art as a video track, so an audiobook reaches the audio-only planner
     * on its own merits.
     *
     * Part-local positions are never persisted as the book's position. That is
     * no longer something the client asks for: the server derives it from the
     * file's presentation-part count, so a multi-part audiobook session owns no
     * resume timeline whether or not the client remembers to opt out. Whole-book
     * resume is driven separately by routing the durable sink through the global
     * position (see [savePosition]).
     */
    private suspend fun startPartSession(
        fileId: Int,
        profileId: String,
        startPosition: Double,
        capabilities: ClientCodecCapabilities? = null,
        clientPlaybackContext: ClientPlaybackContext? = null,
    ): ApiResult<VideoSessionStartV3> {
        val resolvedCapabilities = capabilities ?: capabilityDetector.detect()
        val resolvedContext = clientPlaybackContext
            ?: capabilityDetector.detectPlaybackContext(capabilities = resolvedCapabilities)
        return playbackSessionManager.startVideoSessionV3(
            fileId = fileId,
            profileId = profileId,
            capabilities = resolvedCapabilities,
            clientPlaybackContext = resolvedContext,
            audioTrackIndex = null,
            subtitleTrackIndex = null,
            qualityPreference = QUALITY_ORIGINAL_V3,
            startPosition = startPosition,
            progressPersistence = ProgressPersistenceV3.CLIENT,
        )
    }

    /**
     * Retire the currently-loaded part's session before crossing a part
     * boundary (Apple `retireActiveSession`): report its [finalLocalPosition]
     * (the outgoing part's file-local position, captured by the caller BEFORE
     * it pre-writes the target position into ui-state), then stop it.
     */
    private suspend fun retireActiveSession(
        finalLocalPosition: Double,
        finalGlobalPosition: Double,
        finalGlobalDuration: Double,
    ) {
        val sessionId = _uiState.value.sessionId ?: return
        playbackSessionLifecycle.reportPosition(
            positionSec = finalLocalPosition,
            durationSec = activePartDurationSeconds(),
            isPaused = true,
            expectedSessionId = sessionId,
            persistencePositionSec = finalGlobalPosition,
            persistenceDurationSec = finalGlobalDuration,
        )
        _uiState.update { it.copy(sessionId = null) }
        playbackSessionLifecycle.stop(expectedSessionId = sessionId)
        activePlaybackTimeline = null
        activePlaybackSourceDurationSeconds = null
    }

    /**
     * Apply a started v3 session to UI state.
     *
     * There is no play-method branch any more. A v3 plan's `stream.url` is the
     * URL to load whatever the delivery turned out to be — the server has
     * already started whatever it needed to serve it — so the old
     * DIRECT-vs-REMUX/TRANSCODE split, which had to fire a second
     * transcode-start round-trip before the stream URL resolved, collapses into
     * a single assignment.
     *
     * [localSeek] is the file-local offset the engine seeks to (fed to the
     * Compose layer via [resumePositionSeconds]); [globalPosition] is the
     * whole-book position shown in the UI; [trackIndex] becomes
     * [activeTrackIndex] (null on the single-file fallback).
     *
     * The player start position comes from the plan rather than from
     * [localSeek]: the two differ when the server anchors the stream somewhere
     * other than the requested offset, and the plan is the authority on where
     * the delivered stream actually begins.
     */
    private suspend fun applyStartedSession(
        ready: VideoSessionStartV3.Ready,
        localSeek: Double,
        globalPosition: Double,
        trackIndex: Int?,
        fileId: Int,
        isCurrent: () -> Boolean,
    ): Boolean {
        var lifecycleOwnsSession = false
        var published = false
        try {
            // Server stream URLs are relative (e.g. /playback/stream/...). The
            // Compose layer hands them straight to Media3, so they must be
            // absolute here or OkHttp fails the open with "Malformed URL".
            val serverUrl = playbackSessionManager.getServerUrl()
            val requestedSeek = localSeek.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
            val resolvedLocalSeek = ready.plan.timeline.playerStartSeconds
                .takeIf { it.isFinite() && it >= 0.0 }
                ?: requestedSeek
            val playbackTimeline = ready.plan.timeline.toPlaybackTimeline()
            lifecycleOwnsSession = playbackSessionLifecycle.adoptActiveSessionIfCurrent(
                params = StartParams(
                    contentId = contentId,
                    fileId = fileId,
                    capabilities = ready.capabilities,
                    qualityPreference = QUALITY_ORIGINAL_V3,
                    startPosition = ready.plan.timeline.sourceStartSeconds
                        .takeIf { it.isFinite() && it >= 0.0 }
                        ?: requestedSeek,
                    clientPlaybackContext = ready.clientPlaybackContext,
                ),
                session = ready.session,
                isCurrent = isCurrent,
            )
            if (!lifecycleOwnsSession) return false

            // Adoption can wait behind a concurrent teardown. Check the transaction
            // again before publishing; the main-thread state writes below do not
            // suspend, so a close cannot interleave after this gate.
            if (!isCurrent()) return false

            activeTrackIndex = trackIndex
            activePlaybackTimeline = playbackTimeline
            activePlaybackSourceDurationSeconds = ready.plan.source.durationSeconds
                ?.takeIf { it.isFinite() && it >= 0.0 }
            val sourceStart = playbackTimeline.sourcePositionForPlayer(resolvedLocalSeek)
                ?: ready.plan.timeline.sourceStartSeconds.coerceAtLeast(0.0)
            val partDuration = ready.session.durationSeconds ?: 0.0
            playbackSessionLifecycle.reportPosition(
                positionSec = sourceStart,
                durationSec = partDuration,
                isPaused = _uiState.value.isPaused,
                expectedSessionId = ready.session.sessionId,
                persistencePositionSec = globalPosition,
                persistenceDurationSec = _uiState.value.durationSeconds,
            )
            // The Compose layer applies resumePositionSeconds as the *stream* start
            // position, so it is file-local. For a multi-part load, hold engine-time
            // mapping suppressed until the stream settles near this value.
            pendingTrackLoadLocalStart = if (trackIndex != null) resolvedLocalSeek else null
            _resumePosition.value = resolvedLocalSeek.takeIf { it > 0.0 }
            _uiState.update {
                it.copy(
                    streamUrl = resolvePlaybackStreamUrl(serverUrl, ready.plan.stream.url),
                    sessionId = ready.session.sessionId,
                    selectedFileId = fileId,
                    positionSeconds = globalPosition,
                    error = null,
                )
            }
            published = true
            return true
        } finally {
            if (!published) {
                withContext(NonCancellable) {
                    if (lifecycleOwnsSession) {
                        playbackSessionLifecycle.stop(expectedSessionId = ready.session.sessionId)
                    } else {
                        playbackSessionManager.abandonActiveVideoPlanIfCurrent(
                            sessionId = ready.session.sessionId,
                            planId = ready.plan.planId,
                        )
                    }
                }
            }
        }
    }

    /** Releases a committed replan that lost ownership before UI adoption. */
    private suspend fun abandonUnpublishedSession(ready: VideoSessionStartV3.Ready) {
        withContext(NonCancellable) {
            playbackSessionManager.abandonActiveVideoPlanIfCurrent(
                sessionId = ready.session.sessionId,
                planId = ready.plan.planId,
            )
        }
    }

    /**
     * Report a v3 start that produced no playable plan. A terminal result
     * carries the server's own reason; a protocol-version rejection means this
     * build is talking to a server that predates the contract it speaks.
     */
    private fun applyFailedSessionStart(
        failureMessage: String,
        expectedSessionId: String? = _uiState.value.sessionId,
    ) {
        val state = _uiState.value
        if (expectedSessionId != null && state.sessionId != expectedSessionId) return
        if (expectedSessionId != null) {
            playbackSessionLifecycle.reportPosition(
                positionSec = sessionLocalPosition(state),
                durationSec = activePartDurationSeconds(),
                isPaused = true,
                expectedSessionId = expectedSessionId,
                persistencePositionSec = state.positionSeconds,
                persistenceDurationSec = state.durationSeconds,
            )
            playbackSessionLifecycle.stopAsync(expectedSessionId = expectedSessionId)
        }
        pendingTrackLoadLocalStart = null
        activePlaybackTimeline = null
        activePlaybackSourceDurationSeconds = null
        _uiState.update {
            it.copy(
                streamUrl = null,
                sessionId = null,
                isPlaying = false,
                isPaused = true,
                error = failureMessage,
            )
        }
    }

    private fun VideoSessionStartV3.failureMessage(): String = when (this) {
        is VideoSessionStartV3.Ready -> ""
        is VideoSessionStartV3.Terminal ->
            message.ifBlank { "Audiobook playback failed" }
        VideoSessionStartV3.ServerUpgradeRequired ->
            "This server does not support the playback protocol this app speaks."
    }

    /**
     * On the current part ending, cross into the next part (Apple
     * `advanceAfterTrackEnd`): the next part starts just past the current one's
     * end; if that maps to a new part still within the book, load it playing,
     * otherwise it's end-of-book (park at the total, pause, final sync).
     */
    private fun advanceAfterTrackEnd(active: AudioPlaybackTrack) {
        val tl = timeline ?: return
        val nextStart = active.startOffsetSeconds + active.durationSeconds + TRACK_END_EPSILON
        val total = _uiState.value.durationSeconds
        if (tl.trackIndexAt(nextStart) != active.index && nextStart < total) {
            loadTrack(atGlobalTime = nextStart, autoplay = true)
        } else {
            _uiState.update {
                it.copy(positionSeconds = total, isPaused = true, isPlaying = false)
            }
            savePosition()
        }
    }

    private suspend fun loadOfflineOnly(error: String?) {
        val (serverId, profileId) = resolveScope()
        val media = offlineMediaResolver.findLocalMedia(
            serverId = serverId,
            profileId = profileId,
            contentId = contentId,
            requestedFileId = requestedFileId,
            allowFallback = !hasRequestedFileId,
        )
        if (media == null) {
            _uiState.update { it.copy(isLoading = false, error = error) }
            return
        }
        // Same PART-LOCAL gating as loadDetail's offline branch: the durable
        // snapshot is a WHOLE-BOOK position but this path streams one downloaded
        // file. The item detail may still be cached from an earlier online visit
        // — when it shows the book is multi-part, run in part-local space and map
        // the resume through the timeline instead of seeding a global position.
        val cachedTimeline = catalogRepository.getCachedItemDetail(contentId)?.let { cached ->
            buildAudiobookTimeline(
                versions = cached.versions,
                serverTotalSeconds = cached.audiobook?.totalDurationSeconds?.toDouble(),
                preferredFileId = media.fileId,
            )
        }?.takeIf { !it.isSingle }
        val offlinePart = cachedTimeline?.tracks?.firstOrNull { it.fileId == media.fileId }
        // No server detail when offline — resume from the local snapshot alone
        // (unless the user explicitly chose to play from the beginning).
        val resume = if (!startFromBeginning) loadResumePositionSnapshot() else null
        if (cachedTimeline != null && offlinePart != null) {
            suppressWholeBookPersistence = true
            val globalResume = resume ?: 0.0
            val localResume =
                if (cachedTimeline.trackIndexAt(globalResume) == offlinePart.index) {
                    cachedTimeline.localTimeFor(globalResume, offlinePart)
                } else {
                    0.0
                }
            _resumePosition.value = localResume.takeIf { it > 0.0 }
        } else if (resume != null) {
            val fileDuration = media.sidecar.durationSeconds ?: 0.0
            if (fileDuration > 0.0 && resume > fileDuration) {
                // The whole-book snapshot lies beyond this file, so it must be
                // one part of a longer book we have no timeline for: start at 0
                // and keep part-local time out of the durable whole-book sink.
                suppressWholeBookPersistence = true
                _resumePosition.value = null
            }
        }
        _uiState.update {
            it.copy(
                isLoading = false,
                title = media.sidecar.title,
                author = media.sidecar.author,
                narrator = media.sidecar.narrator,
                overview = media.sidecar.overview,
                coverUrl = media.sidecar.posterUrl,
                coverThumbhash = media.sidecar.posterThumbhash,
                durationSeconds = offlinePart?.durationSeconds
                    ?: media.sidecar.durationSeconds
                    ?: 0.0,
                chapters = media.sidecar.chapters.orEmpty(),
                streamUrl = media.fileUrl,
                selectedFileId = media.fileId,
                sessionId = null,
                error = null,
            )
        }
    }

    /**
     * Update the position tracker. Driven by the Compose layer's 4Hz poll of the
     * Media3 controller, whose [seconds] is the *part-local* stream time and
     * whose [streamUri] identifies the stream the engine is actually playing
     * (`currentMediaItem.localConfiguration.uri`).
     *
     * On the whole-book (multi-part) path this converts to whole-book (global)
     * time via the active track's offset and drives end-of-part advance; on the
     * single-file fallback ([timeline]/[activeTrackIndex] absent) part-local ==
     * whole-book and this is the pre-timeline behaviour.
     */
    fun onPositionChanged(seconds: Double, streamUri: String?) {
        val tl = timeline
        val active = activeTrackIndex?.let { idx -> tl?.tracks?.firstOrNull { it.index == idx } }

        // During a cross-part load the poller can still report the outgoing
        // part for a frame or two; ignore engine time until the freshly-loaded
        // stream settles, holding the target position set by loadTrack. Settle
        // is tied to STREAM IDENTITY first: until the engine reports the newly
        // loaded part's URL its time still belongs to the outgoing stream, and
        // a time-only tolerance false-clears when the outgoing part happens to
        // be near the new file-local start. The time tolerance stays as a
        // secondary condition once the identity matches (absorbs the fresh
        // prepare's initial seek).
        val awaiting = pendingTrackLoadLocalStart
        if (awaiting != null) {
            val settled = streamUri != null &&
                streamUri == _uiState.value.streamUrl &&
                abs(seconds - awaiting) <= TRACK_LOAD_SETTLE_TOLERANCE
            if (settled) {
                pendingTrackLoadLocalStart = null
            } else {
                return
            }
        }

        val mappedSourceLocal = activePlaybackTimeline?.sourcePositionForPlayer(seconds)
        val sourceLocal = mappedSourceLocal ?: seconds
        val global = if (tl != null && active != null) {
            tl.globalTimeFor(sourceLocal, active)
        } else {
            sourceLocal
        }
        val updated = _uiState.value.copy(positionSeconds = global)
        _uiState.value = updated
        updated.sessionId?.let { sessionId ->
            playbackSessionLifecycle.reportPosition(
                positionSec = sourceLocal,
                durationSec = activePartDurationSeconds(),
                isPaused = updated.isPaused,
                expectedSessionId = sessionId,
                persistencePositionSec = global,
                persistenceDurationSec = updated.durationSeconds,
            )
        }

        // End-of-part: once the engine plays (near) the end of a non-final part,
        // cross into the next part. Guarded to while actually playing so a pause
        // parked at the boundary doesn't auto-advance. Floor-guarded to parts
        // with real length: this check is poll-based (Android mechanics — Apple
        // advances on a discrete end EVENT instead), so a zero/near-zero
        // duration would trip the advance on every tick.
        if (tl != null && active != null && !tl.isSingle && !_uiState.value.isPaused) {
            if (active.durationSeconds > TRACK_END_EPSILON &&
                sourceLocal >= active.durationSeconds - TRACK_END_EPSILON
            ) {
                advanceAfterTrackEnd(active)
            }
        }
    }

    /** Reflect Media3's *actual* playing state (false while buffering/seeking).
     *  Deliberately does NOT touch [isPaused]: a seek triggers a transient
     *  rebuffer that flips isPlaying false, and conflating that with pause
     *  intent latched the player paused after every skip. Pause intent comes
     *  from [onPauseStateChanged] (playWhenReady) instead. */
    fun onPlayingChanged(playing: Boolean) {
        _uiState.update { it.copy(isPlaying = playing) }
    }

    /** Reflect Media3's playWhenReady — the real pause intent. */
    fun onPauseStateChanged(isPaused: Boolean) {
        _uiState.update { it.copy(isPaused = isPaused) }
    }

    /** Route Media3 failures through the same protocol-v3 replan transaction. */
    fun onPlayerError(error: androidx.media3.common.PlaybackException) {
        val state = _uiState.value
        val sessionId = state.sessionId ?: return
        val fileId = state.selectedFileId ?: return
        val globalPosition = state.positionSeconds
        val sourcePosition = sessionLocalPosition(state)
        val trackIndex = activeTrackIndex
        val generation = ++loadGeneration
        viewModelScope.launch {
            when (
                val result = playbackSessionManager.replanActiveVideoSession(
                    classification = error.audiobookFailureClassification(),
                    message = error.message,
                    positionSeconds = sourcePosition,
                    audioTrackIndex = null,
                    subtitleTrackIndex = null,
                    decoderName = error.cause?.javaClass?.simpleName,
                    diagnostics = mapOf("surface" to "audiobook"),
                )
            ) {
                is ApiResult.Success -> {
                    val replacement = result.data
                    if (
                        generation != loadGeneration ||
                        isClosing ||
                        _uiState.value.sessionId != sessionId
                    ) {
                        if (replacement is VideoSessionStartV3.Ready) {
                            abandonUnpublishedSession(replacement)
                        }
                        return@launch
                    }
                    if (replacement is VideoSessionStartV3.Ready) {
                        applyStartedSession(
                            ready = replacement,
                            localSeek = sourcePosition,
                            globalPosition = globalPosition,
                            trackIndex = trackIndex,
                            fileId = fileId,
                            isCurrent = {
                                generation == loadGeneration &&
                                    !isClosing &&
                                    _uiState.value.sessionId == sessionId
                            },
                        )
                    } else {
                        applyFailedSessionStart(
                            replacement.failureMessage(),
                            expectedSessionId = sessionId,
                        )
                    }
                }
                is ApiResult.Error -> if (
                    generation == loadGeneration &&
                    !isClosing &&
                    _uiState.value.sessionId == sessionId
                ) {
                    applyFailedSessionStart(
                        result.message.ifBlank { "Audiobook playback failed" },
                        expectedSessionId = sessionId,
                    )
                }
                is ApiResult.NetworkError -> if (
                    generation == loadGeneration &&
                    !isClosing &&
                    _uiState.value.sessionId == sessionId
                ) {
                    applyFailedSessionStart(
                        result.exception.message ?: "Network error",
                        expectedSessionId = sessionId,
                    )
                }
            }
        }
    }

    fun togglePlay() {
        _uiState.update { it.copy(isPaused = !it.isPaused) }
    }

    /** Audiobook-style ±30s seek. The Compose layer reads the requested
     *  position from [pendingSeekToSeconds] and clears the marker once it
     *  applies the seek to the underlying Media3 controller. */
    private val _pendingSeek = MutableStateFlow<Double?>(null)
    val pendingSeekToSeconds: StateFlow<Double?> = _pendingSeek.asStateFlow()

    fun seekBy(deltaSeconds: Double) {
        seekTo(_uiState.value.positionSeconds + deltaSeconds)
    }

    /**
     * Seek in whole-book (global) space. On the multi-part path this may cross a
     * part boundary — [loadTrack] then retires the current part and loads the
     * one containing the target (Apple `seek(to:)`); a same-part target is a
     * file-local engine seek. On the single-file fallback ([timeline] null) it
     * is the pre-timeline direct seek.
     */
    fun seekTo(seconds: Double) {
        val tl = timeline
        if (tl == null) {
            val target = seconds
                .coerceIn(0.0, _uiState.value.durationSeconds.coerceAtLeast(0.0))
            if (_uiState.value.sessionId != null && activePlaybackTimeline != null) {
                seekActiveSession(
                    sourceLocalSeconds = target,
                    globalSeconds = target,
                    autoplay = !_uiState.value.isPaused,
                    trackIndex = null,
                    fileId = _uiState.value.selectedFileId ?: return,
                )
            } else {
                _pendingSeek.value = target
            }
            return
        }
        loadTrack(atGlobalTime = seconds, autoplay = !_uiState.value.isPaused)
    }

    private fun seekActiveSession(
        sourceLocalSeconds: Double,
        globalSeconds: Double,
        autoplay: Boolean,
        trackIndex: Int?,
        fileId: Int,
    ) {
        val playbackTimeline = activePlaybackTimeline
        if (playbackTimeline == null) {
            _uiState.update {
                it.copy(positionSeconds = globalSeconds, isPaused = if (autoplay) false else it.isPaused)
            }
            _pendingSeek.value = sourceLocalSeconds
            return
        }
        when (val decision = playbackTimeline.decideSeek(sourceLocalSeconds)) {
            is PlaybackSeekDecision.NativeSeek -> {
                _uiState.update {
                    it.copy(positionSeconds = globalSeconds, isPaused = if (autoplay) false else it.isPaused)
                }
                _pendingSeek.value = decision.targetPlayerPositionSeconds
            }
            is PlaybackSeekDecision.ServerReanchor -> {
                val generation = ++loadGeneration
                val sessionId = _uiState.value.sessionId ?: return
                _uiState.update {
                    it.copy(positionSeconds = globalSeconds, isPaused = if (autoplay) false else it.isPaused)
                }
                viewModelScope.launch {
                    when (
                        val result = playbackSessionManager.reanchorActiveVideoSession(
                            positionSeconds = decision.targetSourcePositionSeconds,
                            diagnostics = mapOf(
                                "surface" to "audiobook",
                                "reason" to decision.reason.name.lowercase(),
                            ),
                        )
                    ) {
                        is ApiResult.Success -> {
                            val replacement = result.data
                            if (
                                generation != loadGeneration ||
                                isClosing ||
                                _uiState.value.sessionId != sessionId
                            ) {
                                if (replacement is VideoSessionStartV3.Ready) {
                                    abandonUnpublishedSession(replacement)
                                }
                                return@launch
                            }
                            if (replacement is VideoSessionStartV3.Ready) {
                                applyStartedSession(
                                    ready = replacement,
                                    localSeek = decision.targetSourcePositionSeconds,
                                    globalPosition = globalSeconds,
                                    trackIndex = trackIndex,
                                    fileId = fileId,
                                    isCurrent = {
                                        generation == loadGeneration &&
                                            !isClosing &&
                                            _uiState.value.sessionId == sessionId
                                    },
                                )
                            } else {
                                applyFailedSessionStart(
                                    replacement.failureMessage(),
                                    expectedSessionId = sessionId,
                                )
                            }
                        }
                        is ApiResult.Error -> if (
                            generation == loadGeneration &&
                            !isClosing &&
                            _uiState.value.sessionId == sessionId
                        ) {
                            _uiState.update { it.copy(error = result.message.ifBlank { "Seek failed" }) }
                        }
                        is ApiResult.NetworkError -> if (
                            generation == loadGeneration &&
                            !isClosing &&
                            _uiState.value.sessionId == sessionId
                        ) {
                            _uiState.update { it.copy(error = result.exception.message ?: "Seek failed") }
                        }
                    }
                }
            }
        }
    }

    fun consumePendingSeek() { _pendingSeek.value = null }

    fun jumpToChapter(chapter: VersionChapter) {
        seekTo(chapter.startSeconds)
    }

    /** Seek to the previous chapter, restarting the current chapter when more
     *  than [AudiobookChapters.PREV_RESTART_THRESHOLD_SECONDS] into it. */
    fun skipToPreviousChapter() {
        val state = _uiState.value
        seekTo(
            AudiobookChapters.previousChapterTarget(
                state.chapters.toAudiobookChapters(),
                state.positionSeconds,
            ),
        )
    }

    /** Seek to the start of the next chapter (clamps on the last chapter). */
    fun skipToNextChapter() {
        val state = _uiState.value
        seekTo(
            AudiobookChapters.nextChapterTarget(
                state.chapters.toAudiobookChapters(),
                state.positionSeconds,
            ),
        )
    }

    fun setSpeed(speed: Float) {
        _uiState.update { it.copy(playbackSpeed = speed.coerceIn(0.5f, 3.0f)) }
    }

    /** Apply [speed] to the current session and persist it as the default
     *  for future audiobooks. */
    fun setDefaultSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 3.0f)
        speedSeeded = true
        _uiState.update { it.copy(playbackSpeed = clamped) }
        viewModelScope.launch { audiobookSettings.setDefaultSpeed(clamped) }
    }

    // ── Configurable skip ─────────────────────────────────────────────────

    /** Skip back by the user's configured interval (default 30s). */
    fun skipBack() = seekBy(-_uiState.value.skipBackSeconds.toDouble())

    /** Skip forward by the user's configured interval (default 30s). */
    fun skipForward() = seekBy(_uiState.value.skipForwardSeconds.toDouble())

    fun setSkipBackSeconds(seconds: Int) {
        viewModelScope.launch { audiobookSettings.setSkipBackSeconds(seconds) }
    }

    fun setSkipForwardSeconds(seconds: Int) {
        viewModelScope.launch { audiobookSettings.setSkipForwardSeconds(seconds) }
    }

    // ── Sleep timer ──────────────────────────────────────────────────────

    private var sleepTimerJob: Job? = null

    /**
     * Sleep timer requests. Apply via [applySleepTimer]; the player
     * screen mirrors [SleepTimerEffect] into the controller (auto-pause
     * fires when [remainingSeconds] hits 0).
     */
    private val _sleepTimerChoice = MutableStateFlow<SleepTimerChoice>(SleepTimerChoice.Off)
    val sleepTimerChoice: StateFlow<SleepTimerChoice> = _sleepTimerChoice.asStateFlow()

    /**
     * Apply a new timer choice, cancelling any active timer first.
     * [SleepTimerChoice.Minutes] runs a fixed wall-clock countdown; [Off]
     * cancels. Matches Apple, which offers only minute timers (no
     * end-of-chapter / end-of-book boundary).
     */
    fun applySleepTimer(choice: SleepTimerChoice) {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        _sleepTimerChoice.value = choice

        when (choice) {
            SleepTimerChoice.Off ->
                _uiState.update { it.copy(sleepTimerMinutesLeft = null) }
            is SleepTimerChoice.Minutes ->
                startCountdown(choice.minutes * 60)
        }
    }

    private fun startCountdown(totalSeconds: Int) {
        _uiState.update { it.copy(sleepTimerMinutesLeft = ((totalSeconds + 59) / 60).coerceAtLeast(1)) }
        sleepTimerJob = viewModelScope.launch {
            var remaining = totalSeconds
            while (remaining > 0) {
                delay(1000)
                remaining -= 1
                _uiState.update {
                    it.copy(
                        sleepTimerMinutesLeft = ((remaining + 59) / 60)
                            .coerceAtLeast(0)
                            .takeIf { remaining > 0 },
                    )
                }
            }
            fireSleep()
        }
    }

    /** Pause playback and clear the timer. The screen's LaunchedEffect on
     *  isPaused mirrors this into the controller. */
    private fun fireSleep() {
        _uiState.update { it.copy(isPaused = true, isPlaying = false, sleepTimerMinutesLeft = null) }
        _sleepTimerChoice.value = SleepTimerChoice.Off
    }

    fun startSleepTimer(minutes: Int) = applySleepTimer(SleepTimerChoice.Minutes(minutes))
    fun cancelSleepTimer() = applySleepTimer(SleepTimerChoice.Off)

    // ── Bookmarks ────────────────────────────────────────────────────────

    private suspend fun resolveScope(): Pair<String, String> {
        val serverId = serverRegistry.activeServerId.value ?: DownloadEnqueuer.DEFAULT_SERVER_ID
        val profileId = profileRepository.getActiveProfileId() ?: DownloadEnqueuer.DEFAULT_PROFILE_ID
        return serverId to profileId
    }

    private fun loadBookmarks() {
        viewModelScope.launch {
            val (serverId, profileId) = resolveScope()
            val loaded = withContext(Dispatchers.IO) {
                bookmarksStore.list(serverId, profileId, contentId)
            }
            _bookmarks.value = loaded
        }
    }

    /** Drop a bookmark at the current position. Chapter title is
     *  captured so the list can render it without re-resolving. */
    fun addBookmark(note: String? = null) {
        val state = _uiState.value
        val chapterIndex = AudiobookChapters.currentIndex(
            state.chapters.toAudiobookChapters(),
            state.positionSeconds,
        )
        val chapter = state.chapters.getOrNull(chapterIndex)
            ?.takeIf { state.positionSeconds >= it.startSeconds }

        val bookmark = AudiobookBookmark(
            id = generateBookmarkId(),
            positionSeconds = state.positionSeconds,
            chapterTitle = chapter?.title,
            note = note?.takeIf { it.isNotBlank() },
            createdAtMs = System.currentTimeMillis(),
        )
        viewModelScope.launch {
            val (serverId, profileId) = resolveScope()
            val updated = withContext(Dispatchers.IO) {
                bookmarksStore.add(serverId, profileId, contentId, bookmark)
            }
            _bookmarks.value = updated
        }
    }

    fun removeBookmark(id: String) {
        viewModelScope.launch {
            val (serverId, profileId) = resolveScope()
            val updated = withContext(Dispatchers.IO) {
                bookmarksStore.remove(serverId, profileId, contentId, id)
            }
            _bookmarks.value = updated
        }
    }

    fun jumpToBookmark(bookmark: AudiobookBookmark) {
        seekTo(bookmark.positionSeconds)
    }

    private fun generateBookmarkId(): String =
        // Compact, sortable-ish, sufficient for client-side uniqueness.
        // Server-issued ids will replace these once /bookmarks lands.
        "local-${System.currentTimeMillis()}-${Random.nextInt(0xFFFF).toString(16)}"

    // ── Position resume ──────────────────────────────────────────────────

    private var positionSaveJob: Job? = null
    private var stoppingSessionId: String? = null

    /** Snapshot the current position (and any future server progress
     *  report). Called from periodic timer + pause + seek + close. */
    private fun savePosition() {
        val state = _uiState.value
        if (state.durationSeconds <= 0) return  // metadata not loaded yet
        viewModelScope.launch {
            if (state.positionSeconds > 0 && !suppressWholeBookPersistence) {
                // SINK 2 — whole-book durable resume. Records the WHOLE-BOOK
                // (global) position against the WHOLE-BOOK total via a durable
                // local projection + a content-level outbox op drained through
                // syncProgress (furthest-position-wins). This is what powers
                // resume + Continue Listening, and is what the multi-part fix
                // protects: positionSeconds is now global, so a part-local
                // position is never persisted here as the book's position
                // (offline single-part playback runs part-local and skips this
                // sink entirely via suppressWholeBookPersistence).
                // fileId is the currently-playing part.
                userItemStatePort.recordPosition(
                    contentId = contentId,
                    fileId = state.selectedFileId ?: requestedFileId ?: 0,
                    positionSeconds = state.positionSeconds,
                    durationSeconds = state.durationSeconds,
                )
            }
        }
    }

    /** Resume-on-open. Returns the furthest of the on-device local snapshot
     *  and the server's recorded position ([serverPositionSeconds], from the
     *  item's `user_data`) so we never resume behind progress made on another
     *  device — and so a device that has never played this book locally still
     *  resumes from the server's Continue-Listening position instead of 0:00.
     *  Exposed via [resumePositionSeconds] for the Compose host to seek to once
     *  the controller + metadata are ready. */
    private suspend fun loadResumePositionSnapshot(serverPositionSeconds: Double? = null): Double? {
        // Content-level local read: the playing fileId isn't known yet at resume,
        // and audiobook resume is per-book — take the furthest on-device position
        // across the item's files, maxed with the server's recorded position so we
        // never resume behind progress made on another device.
        val local = userItemStatePort.localPositionForContent(contentId)?.takeIf { it > 0 }
        val server = serverPositionSeconds?.takeIf { it > 0 }
        val position = listOfNotNull(local, server).maxOrNull()
        _resumePosition.value = position
        return position
    }

    fun consumeResumePosition() { _resumePosition.value = null }

    /** Persist position every 5s while playing so a crash loses at
     *  most a few seconds. Also fires on pause/seek/close via direct
     *  [savePosition] calls. */
    private fun startPeriodicPositionSave() {
        positionSaveJob?.cancel()
        positionSaveJob = viewModelScope.launch {
            while (true) {
                delay(5000)
                if (_uiState.value.isPlaying) savePosition()
            }
        }
    }

    /** Public hook for the Compose host's lifecycle bridge: call on
     *  pause, seek, or destroy. */
    fun flushPosition() = savePosition()

    fun stopPlaybackSession() {
        // Invalidate any in-flight cross-part load so it can't resurrect a
        // session after we clear state here (Apple close() bumps loadGeneration).
        loadGeneration++
        startGeneration++
        pendingTrackLoadLocalStart = null
        val state = _uiState.value
        val sessionId = state.sessionId
        val sessionLocal = sessionLocalPosition(state)
        if (sessionId != null) {
            playbackSessionLifecycle.reportPosition(
                positionSec = sessionLocal,
                durationSec = activePartDurationSeconds(),
                isPaused = true,
                expectedSessionId = sessionId,
                persistencePositionSec = state.positionSeconds,
                persistenceDurationSec = state.durationSeconds,
            )
        }
        _uiState.update {
            it.copy(
                streamUrl = null,
                sessionId = null,
                isPlaying = false,
                isPaused = true,
            )
        }
        activePlaybackTimeline = null
        activePlaybackSourceDurationSeconds = null
        if (sessionId == null) return
        if (stoppingSessionId == sessionId) return
        stoppingSessionId = sessionId
        viewModelScope.launch {
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    if (state.positionSeconds > 0 && !suppressWholeBookPersistence) {
                        // SINK 2: whole-book global position + whole-book total.
                        // Skipped on offline part-local playback, where
                        // positionSeconds is a PART position that must never be
                        // written against the book.
                        userItemStatePort.recordPosition(
                            contentId = contentId,
                            fileId = state.selectedFileId ?: requestedFileId ?: 0,
                            positionSeconds = state.positionSeconds,
                            durationSeconds = state.durationSeconds,
                        )
                    }
                    playbackSessionLifecycle.stop(expectedSessionId = sessionId)
                    // Inside NonCancellable so a teardown-cancelled viewModelScope
                    // can't skip the prompt drain (covers downloaded/offline-while-
                    // online where no connectivity change triggers it).
                    outboxSyncScheduler.requestSync()
                }
            } finally {
                if (stoppingSessionId == sessionId) {
                    stoppingSessionId = null
                }
            }
        }
    }

    /**
     * The active part's file-local position for [state]'s whole-book
     * [AudiobookPlayerUiState.positionSeconds] — what the per-part session must
     * be reported. On the single-file fallback ([timeline]/[activeTrackIndex]
     * absent) this is the whole-book position unchanged.
     */
    private fun sessionLocalPosition(state: AudiobookPlayerUiState): Double {
        val tl = timeline
        val active = activeTrackIndex?.let { idx -> tl?.tracks?.firstOrNull { it.index == idx } }
        return if (tl != null && active != null) {
            tl.localTimeFor(state.positionSeconds, active)
        } else {
            state.positionSeconds
        }
    }

    private fun activePartDurationSeconds(): Double {
        return activePlaybackSourceDurationSeconds ?: 0.0
    }

    override fun onCleared() {
        // Invalidate any in-flight cross-part load so it can't resurrect a
        // session during teardown (Apple close(): isClosing + loadGeneration).
        isClosing = true
        loadGeneration++
        startGeneration++
        pendingTrackLoadLocalStart = null
        sleepTimerJob?.cancel()
        positionSaveJob?.cancel()
        val state = _uiState.value
        val sessionId = state.sessionId
        if (sessionId != null) {
            playbackSessionLifecycle.reportPosition(
                positionSec = sessionLocalPosition(state),
                durationSec = activePartDurationSeconds(),
                isPaused = true,
                expectedSessionId = sessionId,
                persistencePositionSec = state.positionSeconds,
                persistenceDurationSec = state.durationSeconds,
            )
            playbackSessionLifecycle.stopAsync(expectedSessionId = sessionId)
        }
        super.onCleared()
    }

    companion object {
        private const val TAG = "AudiobookPlayerViewModel"

        /** Epsilon (seconds) for end-of-part detection and the next-part start
         *  probe, mirroring Apple's 0.01s in advanceAfterTrackEnd. */
        private const val TRACK_END_EPSILON = 0.25

        /** Tolerance (seconds) within which the freshly-loaded part's stream is
         *  considered "settled" at its file-local start, after which engine-time
         *  mapping resumes. Wide enough to absorb the ~250ms poll cadence and a
         *  fresh prepare's initial seek. */
        private const val TRACK_LOAD_SETTLE_TOLERANCE = 3.0
    }
}

/** Project the server chapter list onto the pure [AudiobookChapter] span
 *  model that [AudiobookChapters] math operates on. */
private fun List<VersionChapter>.toAudiobookChapters(): List<AudiobookChapter> =
    map { AudiobookChapter(startSeconds = it.startSeconds, endSeconds = it.endSeconds) }

/** Project the whole-book timeline's globally-offset chapters onto the UI's
 *  [VersionChapter] shape so the slider / chapter math runs in whole-book
 *  (global) space for multi-part books. Ordered by start (already sorted by
 *  [buildAudiobookTimeline]). */
private fun AudiobookTimeline.toWholeBookChapters(): List<VersionChapter> =
    chapters.map { chapter ->
        VersionChapter(
            index = chapter.index,
            title = chapter.title.orEmpty(),
            startSeconds = chapter.startSeconds,
            endSeconds = chapter.endSeconds ?: chapter.startSeconds,
        )
    }

private fun org.siloserver.silo.model.playback.PlaybackTimelineV3.toPlaybackTimeline() =
    PlaybackTimeline(
        sourceStartSeconds = sourceStartSeconds,
        playerStartSeconds = playerStartSeconds,
        streamOriginSeconds = streamOriginSeconds,
        timelineOffsetSeconds = timelineOffsetSeconds,
        seekWindowStartSeconds = seekWindowStartSeconds,
        seekWindowEndSeconds = seekWindowEndSeconds,
        canSeekAnywhere = canSeekAnywhere,
        seekRestoration = seekRestoration,
    )

private fun androidx.media3.common.PlaybackException.audiobookFailureClassification(): String =
    when (errorCode) {
        androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED,
        androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        -> "decoder_failure"
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        -> "transport_failure"
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "http_failure"
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "source_unavailable"
        else -> "player_error"
    }
