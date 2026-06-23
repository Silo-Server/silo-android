@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.continuum.app.tv.ui.screens.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.continuum.app.common.player.PlaybackAnalyticsListener
import com.continuum.app.common.player.PlaybackCapabilityDetector
import com.continuum.app.common.player.PlaybackRecoveryAction
import com.continuum.app.common.player.PlaybackRecoveryPlanner
import com.continuum.app.common.player.PlaybackSessionLifecycle
import com.continuum.app.common.player.PlaybackSessionManager
import com.continuum.app.common.player.PlayerNotice
import com.continuum.app.common.player.SessionState
import com.continuum.app.common.player.SleepTimerController
import com.continuum.app.common.player.SleepTimerState
import com.continuum.app.common.player.StartParams
import com.continuum.app.common.player.isBitmapSubtitleCodecOrMime
import com.continuum.app.common.player.backend.VideoBackendCapabilities
import com.continuum.app.common.player.video.VideoPlaybackSessionCoordinator
import com.continuum.app.common.player.video.VideoPlaybackStartRequest
import com.continuum.app.common.player.video.VideoPlayerUiState
import com.continuum.app.common.player.video.resolvedPlaybackDelivery
import com.continuum.app.common.settings.PlayerSettingsStore
import com.continuum.app.domain.player.IntroAutoSkipController
import com.continuum.app.domain.player.IntroAutoSkipState
import com.continuum.app.model.catalog.TimeRange
import com.continuum.app.model.catalog.VersionChapter
import com.continuum.app.model.settings.SubtitleAppearance
import com.continuum.app.model.playback.PlaybackDelivery
import com.continuum.app.model.playback.PlayMethod
import com.continuum.app.model.playback.PlaybackEngineKind
import com.continuum.app.model.playback.PlaybackExecutionPlan
import com.continuum.app.model.playback.PlaybackRouteFamily
import com.continuum.app.model.playback.PlaybackRouteEventRequest
import com.continuum.app.model.playback.PlaybackSessionResponse
import com.continuum.app.model.playback.PlayerSubtitleInfo
import com.continuum.app.model.playback.mergeDownloadedSubtitles
import com.continuum.app.model.subtitles.SubtitleAiQuota
import com.continuum.app.model.subtitles.SubtitleAiStatus
import com.continuum.app.model.subtitles.SubtitleDownloadRequest
import com.continuum.app.model.subtitles.SubtitleResult
import com.continuum.app.model.subtitles.SubtitleSearchRequest
import com.continuum.app.model.subtitles.SubtitleTranslateRequest
import com.continuum.app.network.ApiResult
import com.continuum.app.network.errorMessage
import com.continuum.app.playback.nextEpisodeAfter
import com.continuum.app.repository.SubtitlesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Renderable audio or subtitle track pulled out of ExoPlayer's current
 * `Tracks` object. `id` is just the ordinal position among groups of the
 * same type — it's used as the index argument when calling
 * [com.continuum.app.common.player.AudioTrackManager.selectAudioTrack] or
 * [com.continuum.app.common.player.SubtitleManager.selectSubtitle]. [label]
 * stays the raw Media3 selector label for matching re-prepared subtitle
 * groups; [displayLabel] is the polished user-facing string.
 */
data class PlayerTrackEntry(
    val index: Int,
    val label: String,
    val language: String?,
    val isSelected: Boolean,
    val displayLabel: String = label,
    val codecOrMime: String? = null,
)

internal fun subtitleTracksWithSelection(
    tracks: List<PlayerTrackEntry>,
    selectedIndex: Int,
): List<PlayerTrackEntry> =
    tracks.map { track ->
        track.copy(isSelected = selectedIndex >= 0 && track.index == selectedIndex)
    }

internal fun preferredAutoTextSubtitleIndex(
    tracks: List<PlayerTrackEntry>,
    preferredLanguage: String?,
): Int? {
    val selected = tracks.firstOrNull { it.isSelected } ?: return null
    if (!isBitmapSubtitleCodecOrMime(selected.codecOrMime)) return null

    val targetLanguage = normalizedSubtitleLanguage(selected.language)
        ?: normalizedSubtitleLanguage(preferredLanguage)
        ?: return null

    return tracks.firstOrNull { track ->
        track.index != selected.index &&
            !isBitmapSubtitleCodecOrMime(track.codecOrMime) &&
            normalizedSubtitleLanguage(track.language) == targetLanguage
    }?.index
}

internal fun resolveInitialSubtitleTrackIndex(
    requestedOrdinal: Int,
    subtitleTracks: List<PlayerTrackEntry>,
    mountedSubtitles: List<PlayerSubtitleInfo>,
): Int? {
    val requested = mountedSubtitles.getOrNull(requestedOrdinal)
        ?: mountedSubtitles.firstOrNull { it.index == requestedOrdinal }
        ?: return null

    return subtitleTracks.firstOrNull { it.matchesMountedSubtitle(requested) }?.index
}

private fun PlayerTrackEntry.matchesMountedSubtitle(subtitle: PlayerSubtitleInfo): Boolean {
    val targetLabel = subtitle.label?.trim()?.takeIf { it.isNotBlank() }
    if (targetLabel != null) {
        val rawLabel = label.trim()
        val friendlyLabel = displayLabel.trim()
        if (rawLabel == targetLabel || friendlyLabel == targetLabel) return true
        if (
            rawLabel.equals(targetLabel, ignoreCase = true) ||
            friendlyLabel.equals(targetLabel, ignoreCase = true)
        ) {
            return true
        }
    }

    val targetLanguage = normalizedSubtitleLanguage(subtitle.language)
    val trackLanguage = normalizedSubtitleLanguage(language)
    if (targetLanguage == null || trackLanguage != targetLanguage) return false

    val targetCodec = normalizedSubtitleCodec(subtitle.codec ?: subtitleCodecFromUrl(subtitle.url))
    val trackCodec = normalizedSubtitleCodec(codecOrMime)
    return targetCodec == null || trackCodec == null || targetCodec == trackCodec
}

private fun normalizedSubtitleLanguage(language: String?): String? {
    val primary = language
        ?.trim()
        ?.takeUnless { it.isBlank() || it.equals("und", ignoreCase = true) }
        ?.lowercase()
        ?.replace('_', '-')
        ?.substringBefore('-')
        ?: return null
    return when (primary) {
        "eng" -> "en"
        "spa" -> "es"
        "fre", "fra" -> "fr"
        "ger", "deu" -> "de"
        "dut", "nld" -> "nl"
        "jpn" -> "ja"
        "dan" -> "da"
        else -> primary
    }
}

private fun normalizedSubtitleCodec(codecOrMime: String?): String? {
    val normalized = codecOrMime
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.lowercase()
        ?.replace('_', '-')
        ?: return null
    return when {
        normalized == "ass" || normalized == "ssa" || normalized.contains("x-ssa") -> "ssa"
        normalized == "srt" || normalized.contains("subrip") -> "srt"
        normalized == "vtt" || normalized == "text/vtt" || normalized.contains("webvtt") -> "vtt"
        normalized.contains("pgs") || normalized.contains("hdmv") -> "pgs"
        normalized.contains("dvd") || normalized.contains("dvb") -> "dvd"
        else -> normalized
    }
}

private fun subtitleCodecFromUrl(url: String): String =
    url.substringBefore('?')
        .substringBefore('#')
        .substringAfterLast('.', "")

/**
 * How the video surface scales to fill the player area. Session-scoped
 * (resets to [Fit] on each new playback) — matches tvOS behavior.
 */
enum class VideoFillMode {
    /** Letterbox: preserve aspect ratio, may show bars. Default. */
    Fit,
    /** Zoom: preserve aspect ratio, fill screen, may crop edges. */
    Zoom,
    /** Stretch: fill screen ignoring aspect ratio (matches phone "Stretch"). */
    Stretch,
}

/** A transient remote "display_message"; [id] makes repeats re-trigger the toast. */
data class RemoteMessage(val id: Long, val text: String)

/** The resolved next episode for auto-advance / "Up next". */
data class NextEpisodeState(
    val contentId: String,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String?,
    val stillUrl: String?,
    val overview: String? = null,
)

data class TvPlayerLaunchArgs(
    val contentId: String,
    val preferredFileId: Int? = null,
    val roomId: String? = null,
    val resumePositionOverride: Double? = null,
    /** Pre-selected audio track index from the detail screen (null = auto). */
    val initialAudioTrackIndex: Int? = null,
    /** Pre-selected subtitle track index (null = auto, -1 = Off). */
    val initialSubtitleTrackIndex: Int? = null,
    /**
     * How many consecutive auto-advances led to this playback (0 = a manual
     * start). The player re-mounts per episode, so the pass-out streak rides
     * the route instead of living in the VM. When it reaches the pass-out
     * threshold setting, the next credits-reached shows "Still watching?"
     * instead of auto-advancing.
     */
    val autoAdvanceCount: Int = 0,
)

/** Emitted to ask the screen to navigate to the next episode (auto-advance / Continue). */
data class PlayNextRequest(val contentId: String, val autoAdvanceCount: Int)

/**
 * Snapshot of player statistics surfaced in the HUD's Stats pane.
 * Built by [reducePlayerStats] from a stream of [PlaybackAnalyticsListener.Event]s.
 *
 * All fields nullable — fields populate as events arrive; rendering should
 * tolerate any subset being null. `droppedFrames` and `audioUnderruns` are
 * cumulative counters since the snapshot was created.
 */
data class PlayerStatsSnapshot(
    val backendKind: String? = null,
    val backendDisplayName: String? = null,
    val backendRoute: String? = null,
    val subtitleRendering: String? = null,
    val hardContainers: String? = null,
    val videoDecoderName: String? = null,
    val audioDecoderName: String? = null,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val resolution: String? = null,            // e.g. "1920x1080"
    val frameRate: Float? = null,
    val hdrMode: String? = null,               // e.g. "Dolby Vision", "HDR10", "SDR"
    val bitrateBps: Long? = null,
    val droppedFrames: Int = 0,                // cumulative since session start
    val audioUnderruns: Int = 0,               // cumulative
)

/**
 * Pure event-to-snapshot reducer. Used by the ViewModel; tested in isolation.
 * Does NOT clear state on unrelated events (e.g. a DroppedFrames event leaves
 * format/decoder fields untouched).
 */
internal fun reducePlayerStats(
    current: PlayerStatsSnapshot,
    event: PlaybackAnalyticsListener.Event,
): PlayerStatsSnapshot = when (event) {
    is PlaybackAnalyticsListener.Event.VideoDecoderInitialized ->
        current.copy(videoDecoderName = event.decoderName)
    is PlaybackAnalyticsListener.Event.AudioDecoderInitialized ->
        current.copy(audioDecoderName = event.decoderName)
    is PlaybackAnalyticsListener.Event.VideoFormatChanged -> current.copy(
        videoCodec = event.format.codecs ?: event.format.sampleMimeType,
        resolution = if (event.format.width > 0 && event.format.height > 0) {
            "${event.format.width}x${event.format.height}"
        } else current.resolution,
        frameRate = if (event.format.frameRate > 0f) event.format.frameRate else current.frameRate,
        hdrMode = describeHdrMode(event.format) ?: current.hdrMode,
    )
    is PlaybackAnalyticsListener.Event.AudioFormatChanged ->
        current.copy(audioCodec = event.format.codecs ?: event.format.sampleMimeType)
    is PlaybackAnalyticsListener.Event.DroppedFrames ->
        current.copy(droppedFrames = current.droppedFrames + event.count)
    is PlaybackAnalyticsListener.Event.AudioUnderrun ->
        current.copy(audioUnderruns = current.audioUnderruns + 1)
    is PlaybackAnalyticsListener.Event.BandwidthEstimate ->
        current.copy(bitrateBps = event.bitrateBps)
    is PlaybackAnalyticsListener.Event.LoadError ->
        current // load errors don't mutate the stats snapshot
    is PlaybackAnalyticsListener.Event.PlayerError ->
        current // player errors are logged separately and don't mutate the stats snapshot
    is PlaybackAnalyticsListener.Event.TrackSnapshot ->
        current // diagnostic-only; keep on-screen stats stable
}

/**
 * Describe the HDR mode of a video [androidx.media3.common.Format].
 *
 * Dolby Vision detection is by codec string (`dvh1`, `dvhe`) and runs BEFORE
 * the `colorTransfer` switch because DV bitstreams can carry varying color
 * transfers and Apple's reference treats DV as its own mode. Returns `null`
 * if no HDR signal is present (caller keeps the prior value).
 */
private fun describeHdrMode(format: androidx.media3.common.Format): String? {
    val codecs = format.codecs.orEmpty()
    if (codecs.contains("dvh", ignoreCase = true) || codecs.contains("dvhe", ignoreCase = true)) {
        return "Dolby Vision"
    }
    val colorInfo = format.colorInfo ?: return null
    return when (colorInfo.colorTransfer) {
        androidx.media3.common.C.COLOR_TRANSFER_ST2084 -> "HDR10"
        androidx.media3.common.C.COLOR_TRANSFER_HLG -> "HLG"
        androidx.media3.common.C.COLOR_TRANSFER_SDR -> "SDR"
        else -> null
    }
}

/**
 * Subtitle provider search/download state backing the TV subtitle search
 * dialog. `completedNonce` increments when a download lands and the track
 * list has been refreshed — the dialog observes it and dismisses itself.
 */
data class SubtitleSearchUiState(
    val language: String = "en",
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
    val results: List<SubtitleResult> = emptyList(),
    /** Provider warnings from the search response (e.g. a provider was skipped). */
    val warnings: List<String> = emptyList(),
    val error: String? = null,
    /** [SubtitleResult.id] currently downloading (inline row spinner), or null. */
    val downloadingResultId: String? = null,
    val completedNonce: Int = 0,
)

/** Lifecycle of the in-dialog AI job for the TV AI translate dialog. */
sealed interface AiJobPhase {
    data object Idle : AiJobPhase
    data object Submitting : AiJobPhase
    data class Running(val progress: Double, val message: String?) : AiJobPhase
    data class Failed(val message: String) : AiJobPhase
}

/**
 * AI translate/transcribe state. `status` defaults to both-flags-false so the
 * HUD row stays hidden until the lazy probe succeeds (matching the web: a
 * failed probe also leaves both flags false and surfaces no error).
 */
data class AiTranslateUiState(
    val statusLoaded: Boolean = false,
    val status: SubtitleAiStatus = SubtitleAiStatus(enabled = false, transcribeEnabled = false),
    val quota: SubtitleAiQuota? = null,
    val phase: AiJobPhase = AiJobPhase.Idle,
    val completedNonce: Int = 0,
)

/**
 * TV player ViewModel. Phase E adds state for track selection menus, skip
 * buttons, and a 5-second auto-hide timer for the Compose overlay.
 *
 * Phase 3 TV uplift mirrors the phone PlayerViewModel: injects
 * [PlayerSettingsStore], [IntroAutoSkipController], [PlaybackSessionLifecycle],
 * and [SleepTimerController]. The lifecycle owns progress reporting, recovery,
 * final progress flushing, and session stop. Intro auto-skip and player notices
 * are exposed as separate flows for the screen to consume.
 *
 * Playback itself still goes through [com.continuum.app.common.player.ContinuumPlayerFactory] +
 * [PlaybackSessionManager]. The ViewModel receives track info from the
 * screen (via [onTracksChanged]) because ExoPlayer is owned by the
 * composable.
 */
class TvPlayerViewModel(
    private val videoPlaybackCoordinator: VideoPlaybackSessionCoordinator,
    private val playbackSessionManager: PlaybackSessionManager,
    private val playbackAnalytics: PlaybackAnalyticsListener,
    private val capabilityDetector: PlaybackCapabilityDetector,
    // Phase 3 TV uplift dependencies.
    private val playerSettingsStore: PlayerSettingsStore,
    private val introAutoSkipController: IntroAutoSkipController,
    private val sessionLifecycle: PlaybackSessionLifecycle,
    private val sleepTimer: SleepTimerController,
    // Subtitle suite (provider search/download + AI translate).
    private val subtitlesRepository: SubtitlesRepository,
    // Track B: durable offline-safe position (resume + outbox sync).
    private val userItemStatePort: com.continuum.app.repository.port.UserItemStatePort,
    private val outboxSyncScheduler: com.continuum.app.common.data.sync.OutboxSyncScheduler,
    // Next-episode resolution for auto-advance (F2).
    private val catalogRepository: com.continuum.app.repository.CatalogRepository,
    private val launchArgs: TvPlayerLaunchArgs,
) : ViewModel() {

    companion object {
        private const val TAG = "TvPlayerViewModel"
        // A transient network blip retries the same route this many times before
        // demoting to a server transcode (resets once playback progresses).
        private const val MAX_TRANSIENT_NETWORK_RETRIES = 1
        // Record a durable position roughly every 10s of content time.
        private const val POSITION_RECORD_INTERVAL_SEC = 10.0
        // Auto-play countdown shown on the Up-Next overlay before the next
        // episode starts (mirrors tvOS CountdownRing default).
        const val NEXT_UP_COUNTDOWN_SECONDS = 10
    }

    // Up-Next auto-play countdown ticker. Cancelled on dismiss / Play Now /
    // exit. Lives on the VM (not the composable) so the countdown survives
    // recomposition and overlay focus churn.
    private var nextUpCountdownJob: Job? = null

    private var lastRecordedKey: String? = null
    private var lastRecordedPositionSec: Double = -1.0

    /** [force] bypasses the time-throttle (used on pause/stop to capture the exact spot). */
    private fun maybeRecordPosition(positionSec: Double, durationSec: Double, force: Boolean = false) {
        if (positionSec < 0.0) return
        val cid = contentId.takeIf { it.isNotBlank() } ?: return
        val fileId = _uiState.value.selectedFileId ?: _uiState.value.mediaFileId ?: return
        val key = "$cid|$fileId"
        if (!force && key == lastRecordedKey && lastRecordedPositionSec >= 0.0 &&
            kotlin.math.abs(positionSec - lastRecordedPositionSec) < POSITION_RECORD_INTERVAL_SEC
        ) {
            return
        }
        lastRecordedKey = key
        lastRecordedPositionSec = positionSec
        viewModelScope.launch {
            userItemStatePort.recordPosition(cid, fileId, positionSec, durationSec.takeIf { it > 0.0 })
        }
    }

    private val contentId: String = launchArgs.contentId
    /**
     * Preferred file version to play (chosen by the user in the detail
     * screen's playback selector row). When the
     * item has multiple versions (e.g. 4K + 1080p), this pins the session
     * to that version's `fileId`. `null` means "auto" — fall back to the
     * first version the server returns.
     *
     * Without this, the detail screen's version picker was visually
     * effective but functionally dead: the Play action always defaulted
     * to `versions.first()`, which for many titles is the lower-
     * resolution file because of the server's version sort order.
     */
    private val preferredFileId: Int? = launchArgs.preferredFileId
    private val roomId: String? = launchArgs.roomId
    private val resumePositionOverride: Double? = launchArgs.resumePositionOverride

    // Pre-playback track selections from the detail screen. Audio is sent to the
    // server session start; subtitle is applied once the player's tracks land
    // (see [applyInitialSubtitleIfPending]). Cleared after the first apply so a
    // later user track change isn't overridden.
    private val initialAudioTrackIndex: Int? = launchArgs.initialAudioTrackIndex
    private var pendingInitialSubtitleIndex: Int? = launchArgs.initialSubtitleTrackIndex
    private var autoTextSubtitleSelectionAttempted = false
    private val recoveryPlanner = PlaybackRecoveryPlanner()

    /**
     * Engines we've already mounted for the current content, so the recovery
     * planner never bounces back to one that already failed (MEDIA3_DIRECT ↔
     * MPV_DIRECT ping-pong). Cleared on every fresh [loadContent].
     */
    private val attemptedEngines = mutableSetOf<PlaybackEngineKind>()

    /** Guards [startServerRecoveryFallback] against concurrent fallbacks racing the same session. */
    private var recoveryJob: Job? = null

    /** Same-route retries spent on transient network errors; reset once playback progresses. */
    private var transientNetworkRetries = 0

    data class UiState(
        val isLoading: Boolean = true,
        val error: String? = null,
        val title: String = "",
        /**
         * Artwork URL for Now Playing lock-screen / Bluetooth / Wear surfaces.
         * Sourced from `WatchDetail.posterUrl` with `backdropUrl` fallback.
         * Threaded into MediaItem.MediaMetadata via [TvPlayerScreen]'s call
         * to `playerFactory.buildMediaItem`. Mirrors phone player parity.
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
        val selectedFileId: Int? = null,
        val startPosition: Double = 0.0,
        val position: Double = 0.0,
        val duration: Double = 0.0,
        // User intent (only flipped by onPlayPause / explicit actions).
        val isPaused: Boolean = false,
        // Actual player state — transient dips during buffering must not
        // overwrite isPaused, otherwise the icon flickers to Play and the
        // auto-hide timer cancels mid-stall.
        val isPlaying: Boolean = false,
        // Buffering — driven by the player's onIsLoadingChanged listener
        // (set in the screen). Used together with sessionState.Reconnecting
        // to render the centered spinner during outage recovery.
        val isBuffering: Boolean = false,
        // Track selection — populated by the screen from ExoPlayer's
        // `currentTracks` once playback starts.
        val audioTracks: List<PlayerTrackEntry> = emptyList(),
        val subtitleTracks: List<PlayerTrackEntry> = emptyList(),
        val videoTracks: List<PlayerTrackEntry> = emptyList(),
        // Real per-format video quality variants (resolution/bitrate) flattened
        // from the video group, plus a synthetic "Auto". Distinct from
        // [videoTracks] (group-level): only this drives the HUD Quality picker.
        val videoQualities: List<VideoQualityOption> = emptyList(),
        // Scrubber preview state — `isScrubbing` flips on the first arrow
        // press from the focused scrubber, `scrubPreviewSec` shadows the
        // intended seek target so the overlay can render a preview puck
        // without committing to MediaController.seekTo until the user
        // releases or presses Select.
        val isScrubbing: Boolean = false,
        val scrubPreviewSec: Double = 0.0,
        // Sidecar subtitle URLs from the playback session — passed into
        // [ContinuumPlayerFactory.createMediaSource] so the player loads them
        // as text tracks (the stream manifest doesn't reference these).
        val subtitleUrls: List<PlayerSubtitleInfo> = emptyList(),
        // Server media file id for the active version — required by the
        // subtitle search/download and AI translate endpoints. Sourced from
        // PlaybackSessionResponse.mediaFileId in loadContent; null until the
        // session starts (the HUD hides the Search row while null).
        val mediaFileId: Int? = null,
        // Bumped by refreshSubtitles after merging downloaded subtitles into
        // subtitleUrls. The screen rebuilds the MediaItem (same stream URL,
        // enlarged sidecar list) on each bump — keyed on the nonce, NOT on
        // subtitleUrls, so the initial prepare effect stays the only path
        // for session start / stream-URL changes.
        val subtitleRefreshNonce: Int = 0,
        // Dialog visibility — owned here so HUD rows can request them and
        // the screen renders the Popups above the open HUD.
        val showSubtitleSearchDialog: Boolean = false,
        val showAiTranslateDialog: Boolean = false,
        val showSubtitleStyleDialog: Boolean = false,
        // Overlay visibility (Phase E — driven by the screen but stored here
        // so the overlay can react to play/pause state changes).
        val showControls: Boolean = true,
        val controlsVisibilityNonce: Int = 0,
        val hudOpen: Boolean = false,
        val showSubtitleMenu: Boolean = false,
        val preferredAudioLanguage: String? = null,
        val preferredTextLanguage: String? = null,
        // Intro / credits ranges — populated from `WatchDetail`. Used by the
        // intro auto-skip observer and (eventually) the next-up promote.
        val intro: TimeRange? = null,
        val credits: TimeRange? = null,
        // Chapters from the selected FileVersion (server-extracted via FFprobe
        // at ingest, mirrors Apple's `VersionChapter` consumption). Empty list
        // when the file has no embedded chapters. The HUD Chapters pane
        // renders this directly; the scrubber maps the same list to its
        // lightweight ChapterInfo for tick rendering.
        val chapters: List<VersionChapter> = emptyList(),
        // Next-episode auto-advance (F2). seriesId/season/episode come from the
        // Ready state; nextEpisode is resolved from the season/episode lists once
        // playback starts. stillWatchingPrompt gates auto-advance after a run of
        // consecutive auto-plays (pass-out protection).
        val seriesId: String? = null,
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null,
        val nextEpisode: NextEpisodeState? = null,
        val stillWatchingPrompt: Boolean = false,
        // Up-Next end-of-playback surface (mirrors tvOS PlayerNextUpScreen). When
        // `showNextUp` is true the screen renders the Up-Next overlay — a 16:9
        // mini-player pane beside the next-episode panel — in place of the idle
        // controls. `nextUpVideoEnded` distinguishes "almost finished" (credits
        // reached, still playing) from "end of playback" (stream ended).
        // `nextUpCountdownSeconds` drives the auto-play CountdownRing: non-null
        // counts down to 0 and then plays the next episode; null means no
        // countdown (auto-play off, pass-out gate hit, or no next episode).
        val showNextUp: Boolean = false,
        val nextUpVideoEnded: Boolean = false,
        val nextUpCountdownSeconds: Int? = null,
        val nextUpCountdownTotalSeconds: Int = NEXT_UP_COUNTDOWN_SECONDS,
        // Live player statistics — reduced from [PlaybackAnalyticsListener.Event]s
        // by [reducePlayerStats]. Always non-null so the HUD Stats pane has a
        // snapshot to read; populates field-by-field as events arrive.
        val stats: PlayerStatsSnapshot = PlayerStatsSnapshot(),
        // Video surface fill mode (letterbox vs zoom). Session-scoped — resets
        // to Fit on each new playback to match tvOS video-gravity behavior.
        val videoFillMode: VideoFillMode = VideoFillMode.Fit,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Intro auto-skip banner state. The screen consumes this directly. */
    val introSkipState: StateFlow<IntroAutoSkipState> = introAutoSkipController.state

    private val _seekRequests = MutableSharedFlow<Double>(extraBufferCapacity = 1)
    val seekRequests: SharedFlow<Double> = _seekRequests

    // ---- Remote session-control surface (driven by TvPlaybackRealtimeController) ----
    // Stop is screen-local (stopPlaybackAndExit) and the lifecycle `notice` is
    // read-only, so expose thin channels here for the control socket to drive.
    private val _remoteStopRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** Screen collects this and runs its teardown/exit. */
    val remoteStopRequests: SharedFlow<Unit> = _remoteStopRequests

    private var remoteMessageCounter = 0L
    private val _remoteMessage = MutableStateFlow<RemoteMessage?>(null)
    /** Server "display_message" to surface transiently; null = nothing. */
    val remoteMessage: StateFlow<RemoteMessage?> = _remoteMessage.asStateFlow()

    // ---- Next-episode auto-advance (F2) ----
    private val autoAdvanceCount: Int = launchArgs.autoAdvanceCount
    private var autoAdvanceHandled = false // once-per-item guard
    // Set when the credits/end point fired but nextEpisode hadn't resolved yet,
    // so the Up-Next overlay couldn't arm its countdown. Carries the "video has
    // ended" flag forward so the countdown re-arms once nextEpisode resolves.
    private var pendingApproachingEndVideoEnded: Boolean? = null
    private val _playNextRequests = MutableSharedFlow<PlayNextRequest>(extraBufferCapacity = 1)
    /** Screen collects this and navigates to the next episode's player. */
    val playNextRequests: SharedFlow<PlayNextRequest> = _playNextRequests

    /**
     * Transient player notice (server reconnecting, suspend warnings, etc.) emitted by
     * [PlaybackSessionLifecycle]. `null` means show nothing.
     */
    val notice: StateFlow<PlayerNotice?> = sessionLifecycle.notice

    /**
     * Lifecycle session state. The screen uses this to drive the buffering
     * spinner during outage Reconnecting (which the underlying ExoPlayer can't
     * observe).
     */
    val sessionState: StateFlow<SessionState> = sessionLifecycle.state

    // ---- Subtitle suite flows ----------------------------------------------------
    private val _subtitleSearch = MutableStateFlow(SubtitleSearchUiState())
    val subtitleSearch: StateFlow<SubtitleSearchUiState> = _subtitleSearch.asStateFlow()

    private val _aiTranslate = MutableStateFlow(AiTranslateUiState())
    val aiTranslate: StateFlow<AiTranslateUiState> = _aiTranslate.asStateFlow()

    /**
     * Ordinal text-group index to select after a subtitle refresh lands.
     * Mirrors the seekRequests idiom: the screen collects and calls
     * SubtitleManager.selectSubtitle — the VM never touches the controller.
     */
    private val _subtitleSelectRequests = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val subtitleSelectRequests: SharedFlow<Int> = _subtitleSelectRequests

    // Remote track-selection latches. A remote command can land before the
    // screen's video backend attaches OR before Media3 reports its tracks
    // (onTracksChanged), yet the controller already reported the command
    // "completed" — so we must not drop it. A StateFlow retains the last
    // requested index; the screen combines it with the live track list and
    // applies the moment a matching track exists (dropping it only once tracks
    // are loaded but contain no match). `null` = nothing pending. The raw index
    // is latched WITHOUT validation here precisely because the track list may
    // not be populated yet.
    private val _pendingRemoteAudioIndex = MutableStateFlow<Int?>(null)
    val pendingRemoteAudioIndex: StateFlow<Int?> = _pendingRemoteAudioIndex.asStateFlow()
    private val _pendingRemoteSubtitleIndex = MutableStateFlow<Int?>(null)
    val pendingRemoteSubtitleIndex: StateFlow<Int?> = _pendingRemoteSubtitleIndex.asStateFlow()
    // compareAndSet so a command arriving during the suspending apply isn't
    // clobbered by the clear of the one we just handled.
    fun clearPendingRemoteAudio(applied: Int) { _pendingRemoteAudioIndex.compareAndSet(applied, null) }
    fun clearPendingRemoteSubtitle(applied: Int) { _pendingRemoteSubtitleIndex.compareAndSet(applied, null) }

    // ---- Player settings flows (per-profile, DataStore-backed) -----------------
    val playbackSpeed: StateFlow<Double> = playerSettingsStore.playbackSpeedFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, 1.0)
    val autoSkipIntroEnabled: StateFlow<Boolean> = playerSettingsStore.autoSkipIntroFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val autoPlayNextEnabled: StateFlow<Boolean> = playerSettingsStore.autoPlayNextFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    // Per-profile "Still watching?" threshold (default 3; 0 = off).
    val passOutThreshold: StateFlow<Int> = playerSettingsStore.passOutThresholdFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, 3)
    val hdrEnabled: StateFlow<Boolean> = playerSettingsStore.hdrEnabledFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val subtitleAppearance: StateFlow<SubtitleAppearance> = playerSettingsStore.subtitleAppearanceFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, SubtitleAppearance.DEFAULT)
    /**
     * Per-profile audio delay in ms, ±500 clamp. Sourced from
     * [PlayerSettingsStore.audioSyncMsFlow]; mirrored into the active
     * [com.continuum.app.common.player.audio.DelayAudioProcessor] by
     * [com.continuum.app.common.player.ContinuumPlaybackService] (E T3).
     * The HUD Audio pane reads this for its delay stepper.
     */
    val audioDelayMs: StateFlow<Int> = playerSettingsStore.audioSyncMsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    /**
     * Per-profile subtitle delay in ms, ±500 clamp. Sourced from
     * [PlayerSettingsStore.subtitleSyncMsFlow]; mirrored into the active
     * [com.continuum.app.common.player.subtitle.SubtitleOffsetHolder] by
     * [com.continuum.app.common.player.ContinuumPlaybackService] (A.3f T2).
     * The HUD Subtitles pane reads this for its delay stepper.
     */
    val subtitleDelayMs: StateFlow<Int> = playerSettingsStore.subtitleSyncMsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    // ---- Sleep timer ------------------------------------------------------------
    val sleepTimerState: StateFlow<SleepTimerState> = sleepTimer.state
    val sleepTimerDefaultMinutes: StateFlow<Int> = playerSettingsStore.sleepTimerDefaultMinutesFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, 30)

    private var introObserveJob: Job? = null
    private var lifecycleObserveJob: Job? = null

    // Subtitle suite bookkeeping.
    private var aiStatusRequested = false
    private var aiJobPollJob: Job? = null
    private var activeAiJobId: Long? = null
    private var pendingSubtitleSelectLabel: String? = null

    init {
        // Mirror lifecycle Failed state into the UI error field so the user
        // sees a notice if outage recovery times out or the lifecycle's
        // session fails to start. The phone VM does the same.
        lifecycleObserveJob = viewModelScope.launch {
            sessionLifecycle.state.collect { state ->
                if (state is SessionState.Failed) {
                    _uiState.update { current ->
                        if (current.error == null) current.copy(error = state.message) else current
                    }
                }
            }
        }

        // When the sleep timer fires, flip user intent to paused. The screen
        // mirrors `isPaused` to `mediaController.playWhenReady`.
        sleepTimer.configure {
            _uiState.update { it.copy(isPaused = true) }
        }

        // Reduce the analytics listener's event stream into the HUD's Stats
        // snapshot. The listener is a process-wide singleton shared with
        // ContinuumPlaybackService; we just subscribe — no extra registration.
        viewModelScope.launch {
            playbackAnalytics.events.collect { event ->
                _uiState.update { it.copy(stats = reducePlayerStats(it.stats, event)) }
            }
        }

        if (contentId.isNotBlank()) loadContent(startPositionOverride = resumePositionOverride)
    }

    fun onBackendCapabilities(capabilities: VideoBackendCapabilities) {
        _uiState.update { state ->
            state.copy(
                stats = state.stats.copy(
                    backendKind = capabilities.backendKind.name,
                    backendDisplayName = capabilities.displayName,
                    backendRoute = capabilities.route.displayName,
                    subtitleRendering = capabilities.subtitleRendering.name,
                    hardContainers = if (capabilities.supportsHardContainers) "Yes" else "No",
                ),
            )
        }
    }

    private fun loadContent(
        startPositionOverride: Double? = null,
        preferredFileIdOverride: Int? = null,
        // True for retry: re-load at the current position without nudging back
        // (a normal first resume keeps the default false so it gets the rewind).
        suppressResumeRewind: Boolean = false,
    ) {
        introAutoSkipController.reset()
        // Fresh content: forget engines attempted for the previous item.
        attemptedEngines.clear()

        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                when (val result = videoPlaybackCoordinator.start(
                    VideoPlaybackStartRequest(
                        contentId = contentId,
                        preferredFileId = preferredFileIdOverride ?: preferredFileId,
                        roomId = roomId,
                        resumePositionOverride = startPositionOverride,
                        audioTrackIndex = initialAudioTrackIndex,
                        subtitleTrackIndex = pendingInitialSubtitleIndex,
                        suppressResumeRewind = suppressResumeRewind,
                    ),
                )) {
                    is VideoPlayerUiState.Ready -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = null,
                                title = result.title,
                                artworkUrl = result.artworkUrl,
                                sessionId = result.sessionId,
                                playMethod = result.playMethod,
                                playbackPlan = result.playbackPlan,
                                delivery = result.delivery,
                                streamUrl = result.streamUrl,
                                container = result.container,
                                serverUrl = result.serverUrl,
                                accessToken = result.accessToken,
                                selectedFileId = result.fileId,
                                mediaFileId = result.mediaFileId,
                                startPosition = result.startPositionSeconds,
                                position = result.startPositionSeconds,
                                duration = result.durationSeconds,
                                isPaused = false,
                                subtitleUrls = result.subtitleUrls,
                                preferredAudioLanguage = result.preferredAudioLanguage,
                                preferredTextLanguage = result.preferredTextLanguage,
                                intro = result.intro,
                                credits = result.credits,
                                chapters = result.chapters,
                                seriesId = result.seriesId,
                                seasonNumber = result.seasonNumber,
                                episodeNumber = result.episodeNumber,
                                // Cleared until re-resolved for the new item.
                                nextEpisode = null,
                                stillWatchingPrompt = false,
                                showNextUp = false,
                                nextUpVideoEnded = false,
                                nextUpCountdownSeconds = null,
                            )
                        }
                        startIntroAutoSkipObserver()
                        resolveNextEpisode()
                    }
                    is VideoPlayerUiState.Error -> fail(result.message)
                    is VideoPlayerUiState.Loading -> Unit
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading content", e)
                fail("Unexpected error: ${e.message}")
            }
        }
    }

    private fun startIntroAutoSkipObserver() {
        introObserveJob?.cancel()
        introObserveJob = introAutoSkipController.observe(
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
                        "${state.sessionId}:${state.selectedFileId}:${intro.start}:${intro.end}"
                    }
                }
                .distinctUntilChanged(),
            onAutoSkipFire = { seekToSec ->
                _uiState.update { it.copy(position = seekToSec) }
                _seekRequests.emit(seekToSec)
            },
        )
    }

    private fun fail(message: String) {
        _uiState.update { it.copy(isLoading = false, error = message) }
    }

    /**
     * Preflight signaled the selected track combo can't be direct-played.
     * Fall back to a transcoded stream at the current position and show the
     * user the reason.
     */
    fun onUnsupportedPlayback(reason: com.continuum.app.common.player.Playability) {
        val state = _uiState.value
        if (state.sessionId == null) return

        val notice = when (reason) {
            is com.continuum.app.common.player.Playability.UnsupportedDvProfile ->
                "This device cannot play Dolby Vision Profile ${reason.profile}. Falling back to transcoded stream."
            is com.continuum.app.common.player.Playability.UnsupportedAudioCodec ->
                "Lossless audio not supported on this output. Falling back to transcoded stream."
            is com.continuum.app.common.player.Playability.UnsupportedChannelCount ->
                "Audio channel count not supported. Falling back to transcoded stream."
            is com.continuum.app.common.player.Playability.StartupStalled ->
                "Playback did not start cleanly on this device. Falling back to transcoded stream."
            com.continuum.app.common.player.Playability.Supported -> return
        }
        Log.i(TAG, "Preflight fallback: $notice")

        val recoveryAction = recoveryPlanner.planForPlayability(state.playbackPlan, reason, attemptedEngines)
        if (applyAlternateDirectFallback(state, recoveryAction)) return
        startServerRecoveryFallback(notice, recoveryAction, state)
    }

    private fun startServerRecoveryFallback(
        notice: String,
        recoveryAction: PlaybackRecoveryAction,
        state: UiState,
    ) {
        // Single-flight: a second player/preflight error arriving before the
        // first fallback finishes must not launch a competing transcode session
        // (which would orphan one server session and race _uiState).
        if (recoveryJob?.isActive == true) {
            Log.i(TAG, "Server recovery already in flight; ignoring duplicate fallback request")
            return
        }
        recoveryJob = viewModelScope.launch {
            val sessionId = state.sessionId ?: return@launch
            val activeFileId = state.selectedFileId ?: state.mediaFileId ?: return@launch
            val capabilities = capabilityDetector.detect()
            val selectedAudioIndex = state.audioTracks.firstOrNull { it.isSelected }?.index ?: 0
            val selectedSubtitleIndex = selectedSubtitleTrackIndex(state)
            val sessionResponse = PlaybackSessionResponse(
                sessionId = sessionId,
                userId = 0,
                profileId = null,
                mediaFileId = activeFileId,
                playMethod = state.playMethod ?: PlayMethod.DIRECT,
                position = state.position,
                isPaused = state.isPaused,
                streamUrl = state.streamUrl.orEmpty(),
                audioTrackIndex = selectedAudioIndex,
                durationSeconds = state.duration,
                subtitleUrls = state.subtitleUrls,
                playbackInfo = null,
                playbackPlan = state.playbackPlan,
            )
            val fallbackMode = when (recoveryAction) {
                is PlaybackRecoveryAction.ServerRemux -> PlaybackSessionManager.TranscodeMode.REMUX
                is PlaybackRecoveryAction.AlternateDirectEngine -> PlaybackSessionManager.TranscodeMode.REMUX
                is PlaybackRecoveryAction.ServerTranscode,
                PlaybackRecoveryAction.None,
                -> PlaybackSessionManager.TranscodeMode.FULL
            }
            playbackSessionManager.reportRouteEvent(
                sessionId = sessionId,
                request = PlaybackRouteEventRequest(
                    planId = state.playbackPlan?.planId,
                    fromEngine = state.playbackPlan?.engine,
                    toEngine = PlaybackEngineKind.MEDIA3_HLS,
                    // Report the remux delivery the planner actually chose
                    // (progressive vs HLS) rather than the source delivery.
                    delivery = when (recoveryAction) {
                        is PlaybackRecoveryAction.ServerRemux -> recoveryAction.delivery
                        else -> state.playbackPlan?.delivery ?: state.delivery
                    },
                    routeFamily = state.playbackPlan?.routeFamily,
                    fallbackReason = fallbackMode.name.lowercase(),
                    errorClass = when (recoveryAction) {
                        is PlaybackRecoveryAction.ServerRemux -> recoveryAction.errorClass
                        is PlaybackRecoveryAction.ServerTranscode -> recoveryAction.errorClass
                        is PlaybackRecoveryAction.AlternateDirectEngine -> recoveryAction.errorClass
                        PlaybackRecoveryAction.None -> null
                    },
                    decoderName = state.stats.videoDecoderName ?: state.stats.audioDecoderName,
                    droppedFrames = state.stats.droppedFrames,
                    audioUnderruns = state.stats.audioUnderruns,
                    subtitleRenderer = state.stats.subtitleRendering,
                    claims = state.playbackPlan?.claims,
                    blockers = state.playbackPlan?.capabilities?.blockers.orEmpty(),
                ),
            )
            when (val r = playbackSessionManager.startTranscodeFallback(
                session = sessionResponse,
                seekSeconds = state.position,
                resolution = "",
                mode = fallbackMode,
                audioTrackIndex = selectedAudioIndex,
                subtitleTrackIndex = selectedSubtitleIndex,
            )) {
                is ApiResult.Success -> {
                    val fallback = r.data
                    sessionLifecycle.adoptActiveSession(
                        params = StartParams(
                            contentId = contentId,
                            fileId = activeFileId,
                            capabilities = capabilities,
                            audioTrackIndex = fallback.audioTrackIndex,
                            subtitleTrackIndex = selectedSubtitleIndex,
                            qualityPreference = null,
                            startPosition = fallback.position,
                            preserveDirectAudioSelection = true,
                        ),
                        session = fallback,
                    )
                    _uiState.update {
                        it.copy(
                            // Clear any error the failing direct item set: a successful
                            // fallback must not stay hidden behind the error screen
                            // (which renders before streamUrl).
                            error = null,
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

    private fun applyAlternateDirectFallback(
        state: UiState,
        recoveryAction: PlaybackRecoveryAction,
    ): Boolean {
        val action = recoveryAction as? PlaybackRecoveryAction.AlternateDirectEngine ?: return false
        val plan = state.playbackPlan ?: return false
        val sessionId = state.sessionId ?: return false
        // Record the engine we're leaving (and the one we're moving to) so a later
        // failure can't bounce back to an engine that already failed.
        attemptedEngines.add(plan.engine)
        attemptedEngines.add(action.engine)
        val nextRouteFamily = when (action.engine) {
            PlaybackEngineKind.MPV_DIRECT -> PlaybackRouteFamily.COMPATIBILITY_DIRECT
            PlaybackEngineKind.MEDIA3_DIRECT -> PlaybackRouteFamily.PLATFORM_NATIVE
            else -> plan.routeFamily
        }
        viewModelScope.launch {
            playbackSessionManager.reportRouteEvent(
                sessionId = sessionId,
                request = PlaybackRouteEventRequest(
                    planId = plan.planId,
                    fromEngine = plan.engine,
                    toEngine = action.engine,
                    delivery = plan.delivery,
                    routeFamily = nextRouteFamily,
                    fallbackReason = "alternate_direct_engine",
                    errorClass = action.errorClass,
                    decoderName = state.stats.videoDecoderName ?: state.stats.audioDecoderName,
                    droppedFrames = state.stats.droppedFrames,
                    audioUnderruns = state.stats.audioUnderruns,
                    subtitleRenderer = state.stats.subtitleRendering,
                    claims = plan.claims,
                    blockers = plan.capabilities.blockers,
                ),
            )
        }
        _uiState.update {
            it.copy(
                isLoading = false,
                error = null,
                playbackPlan = plan.copy(
                    engine = action.engine,
                    routeFamily = nextRouteFamily,
                    timeline = plan.timeline.copy(playerStartSeconds = state.position),
                    decisionTrace = plan.decisionTrace +
                        "client_fallback=alternate_direct_engine:${plan.engine}->${action.engine}",
                ),
                startPosition = state.position,
            )
        }
        return true
    }

    private fun selectedSubtitleTrackIndex(state: UiState): Int {
        val selected = state.subtitleTracks.firstOrNull { it.isSelected } ?: return -1
        return state.subtitleUrls
            .firstOrNull { selected.matchesMountedSubtitle(it) }
            ?.index
            ?: -1
    }

    fun onPositionChanged(positionMs: Long, durationMs: Long) {
        if (positionMs < 0) return

        val positionSec = positionMs / 1000.0
        val durationSec = durationMs / 1000.0
        val previousPosition = _uiState.value.position
        _uiState.update {
            it.copy(
                position = positionSec,
                duration = if (durationSec > 0) durationSec else it.duration,
            )
        }
        // Playback is progressing — restore the transient-network retry budget so
        // a later, unrelated blip gets a fresh retry instead of demoting at once.
        if (positionSec > 0 && transientNetworkRetries > 0) {
            transientNetworkRetries = 0
        }
        // F2: auto-advance / prompt when playback CROSSES the credits point —
        // only on the transition from before to after, so resuming an episode
        // whose saved position is already inside the credits doesn't instantly
        // skip to the next one (a seek into credits also won't trigger it).
        _uiState.value.credits?.start?.let { creditsStart ->
            if (previousPosition < creditsStart && positionSec >= creditsStart) onApproachingEnd()
        }
        // Forward to the lifecycle so its 10s reporter has a fresh sample.
        sessionLifecycle.reportPosition(
            positionSec = positionSec,
            durationSec = if (durationSec > 0) durationSec else _uiState.value.duration,
            isPaused = _uiState.value.isPaused,
        )

        // Track B: durably record (local resume + outbox sync) for both streaming
        // and offline-download; throttled to ~every 10s of content time.
        maybeRecordPosition(positionSec, if (durationSec > 0) durationSec else _uiState.value.duration)
    }

    fun onPlayingChanged(isPlaying: Boolean) {
        _uiState.update { it.copy(isPlaying = isPlaying) }
        // Durably capture the exact spot when playback halts (pause/stall/stop)
        // while the VM is alive, so resume is reliable without depending on the
        // exit-time write completing during teardown.
        if (!isPlaying) {
            maybeRecordPosition(_uiState.value.position, _uiState.value.duration, force = true)
        }
    }

    fun onBufferingChanged(isBuffering: Boolean) {
        _uiState.update { it.copy(isBuffering = isBuffering) }
    }

    /** Toggle user-intent pause state. Screen mirrors this to player.play/pause. */
    fun onPlayPause() {
        _uiState.update { it.copy(isPaused = !it.isPaused) }
    }

    /**
     * Idempotent pause setter for Watch Together sync-applied commands. Unlike
     * [onPlayPause] (a toggle), this sets the absolute desired state, so a
     * duplicate room command can't flip the player the wrong way. The screen's
     * `state.isPaused` mirror drives `mediaController.playWhenReady`.
     */
    fun setPaused(paused: Boolean) {
        _uiState.update { if (it.isPaused == paused) it else it.copy(isPaused = paused) }
    }

    /**
     * Deadband-free seek for Watch Together corrective seeks
     * ([TvRoomSyncController.applyDecision]). Updates `uiState.position` AND
     * emits on [seekRequests], which the screen collects and applies to the
     * MediaController unconditionally (TV has no position-mirror deadband, so
     * `seekRequests` already reaches the player on every emission — sub-second
     * sync corrections are never swallowed). Named to mirror the mobile
     * `PlayerViewModel.seekImmediate` contract.
     */
    fun seekImmediate(positionSec: Double) {
        _uiState.update { it.copy(position = positionSec) }
        _seekRequests.tryEmit(positionSec)
    }

    // ---- Remote-control adapters (TvPlaybackRealtimeController calls these) ----
    /** True while in a Watch Together room — remote transport is gated (the room is authoritative). */
    val remoteTransportSuppressed: Boolean get() = roomId != null

    fun remotePause() = setPaused(true)
    fun remoteUnpause() = setPaused(false)
    fun remoteTogglePlayPause() = onPlayPause()
    fun remoteSeek(positionSeconds: Double) = seekImmediate(positionSeconds)
    fun remoteStop() { _remoteStopRequests.tryEmit(Unit) }
    fun remoteDisplayMessage(message: String) {
        _remoteMessage.value = RemoteMessage(++remoteMessageCounter, message)
    }
    fun clearRemoteMessage() { _remoteMessage.value = null }

    // Track selection on TV applies through the player backend (held by the
    // screen). Switching audio re-selects among the CURRENT stream's audio
    // groups (mirrors the TV audio menu); it does not trigger a server-side
    // audio re-mux the way mobile does. The screen validates the index against
    // the live track list at apply time, so a bogus remote index ends up a no-op
    // rather than (for subtitles) silently turning captions off — only an
    // explicit -1 disables subtitles.
    fun remoteSelectAudio(index: Int) { _pendingRemoteAudioIndex.value = index }
    fun remoteSelectSubtitle(index: Int) { _pendingRemoteSubtitleIndex.value = index }

    /**
     * Adopt server-recomputed intro/credits ranges (a `markers_updated` event).
     * Skip-intro and the credits-based F2 trigger read these from UiState, so the
     * update takes effect immediately; `null` clears a marker the server dropped.
     */
    fun applyUpdatedMarkers(intro: TimeRange?, credits: TimeRange?) {
        _uiState.update { it.copy(intro = intro, credits = credits) }
    }

    // ---- Next-episode auto-advance (F2) ----

    /**
     * Resolve the next episode for this item (no-op for movies). Pools the
     * current season's episodes plus the next REGULAR season's (specials are
     * excluded, per the resolver's playback-order contract) and finds the
     * immediate next via [nextEpisodeAfter].
     */
    private fun resolveNextEpisode() {
        val state = _uiState.value
        val seriesId = state.seriesId ?: return
        val curSeason = state.seasonNumber ?: return
        val curEpisode = state.episodeNumber ?: return
        viewModelScope.launch {
            // Current season MUST load — otherwise the pool could contain only
            // the next season and we'd skip the rest of this one. Bail (no
            // auto-advance) on failure.
            val currentSeasonEpisodes =
                (catalogRepository.getEpisodes(seriesId, curSeason) as? ApiResult.Success)
                    ?.data?.episodes ?: return@launch
            val pool = currentSeasonEpisodes.toMutableList()
            // Next regular season is best-effort — its failure just means no
            // cross-season rollover, never a skip within the current season.
            val nextRegularSeason = (catalogRepository.getSeasons(seriesId) as? ApiResult.Success)
                ?.data?.seasons
                ?.filter { !it.isSpecials && it.seasonNumber > curSeason }
                ?.minByOrNull { it.seasonNumber }
            if (nextRegularSeason != null) {
                (catalogRepository.getEpisodes(seriesId, nextRegularSeason.seasonNumber) as? ApiResult.Success)
                    ?.data?.episodes?.let { pool += it }
            }
            val next = nextEpisodeAfter(pool, curSeason, curEpisode) ?: return@launch
            val nextState = NextEpisodeState(
                contentId = next.contentId,
                seasonNumber = next.seasonNumber,
                episodeNumber = next.episodeNumber,
                title = next.title,
                stillUrl = next.stillUrl,
                overview = next.overview,
            )
            _uiState.update { it.copy(nextEpisode = nextState) }
            // If the credits/end point already fired while we were still
            // resolving, the overlay couldn't arm — complete it now (re-arm the
            // countdown) with the strongest video-ended flag we observed.
            if (!autoAdvanceHandled) {
                pendingApproachingEndVideoEnded?.let { videoEnded ->
                    commitApproachingEnd(nextState, videoEnded)
                }
            }
        }
    }

    /**
     * Called by the screen when the credits point is reached (primary) or the
     * stream ends (fallback). Surfaces the Up-Next overlay — a 16:9 mini-player
     * beside the next-episode panel — as the end-of-playback surface (mirrors
     * tvOS PlayerNextUpScreen), replacing the old "Still watching?" dialog.
     *
     * When auto-play is on and the consecutive-auto-advance streak is below the
     * pass-out threshold, the overlay starts a countdown ring that plays the
     * next episode at zero. Once the streak hits the pass-out threshold (or
     * auto-play is off), the overlay shows with NO countdown so the user must
     * explicitly choose Play Now / Keep Watching (the pass-out gate). Once-per-item.
     *
     * [videoEnded] true when the stream has actually ended (STATE_ENDED) — the
     * panel reads "End of playback" / "Playing Next" and hides Keep Watching;
     * false at the credits-crossing while video is still rolling.
     */
    fun onApproachingEnd(videoEnded: Boolean = false) {
        // Watch Together is authoritative — never auto-advance a room member
        // (it would silently leave/desync the room). Mirrors the remote-control
        // transport gate.
        if (roomId != null) return
        // Surfacing again on STATE_ENDED after a credits-crossing only upgrades
        // the "video ended" flag; don't re-arm the countdown or re-trigger.
        if (autoAdvanceHandled) {
            if (videoEnded && _uiState.value.showNextUp) {
                _uiState.update { it.copy(nextUpVideoEnded = true) }
            }
            return
        }

        val next = _uiState.value.nextEpisode
        if (next == null) {
            // Next episode hasn't resolved yet — don't latch a permanent
            // no-countdown/no-next state. Record that the end point fired (and
            // whether the stream has ended) so the countdown re-arms when
            // nextEpisode arrives via [resolveNextEpisode]. If a later signal
            // upgrades to videoEnded, keep the strongest (ended) flag.
            val ended = videoEnded || (pendingApproachingEndVideoEnded == true)
            pendingApproachingEndVideoEnded = ended
            // If the stream has genuinely ended (STATE_ENDED) we still surface
            // the end-of-playback overlay now — there may be no next episode at
            // all (last episode / movie). We deliberately do NOT latch
            // autoAdvanceHandled here, so a next episode that resolves moments
            // later can still arm the countdown via resolveNextEpisode.
            if (ended) {
                _uiState.update {
                    it.copy(
                        showNextUp = true,
                        nextUpVideoEnded = true,
                        nextUpCountdownSeconds = null,
                    )
                }
            }
            return
        }
        commitApproachingEnd(next, videoEnded)
    }

    private fun commitApproachingEnd(next: NextEpisodeState, videoEnded: Boolean) {
        autoAdvanceHandled = true
        pendingApproachingEndVideoEnded = null
        // Threshold 0 (or less) = off: never gate, always allow auto-countdown.
        val threshold = passOutThreshold.value
        val passOutGated = threshold > 0 && autoAdvanceCount >= threshold
        val autoCountdown = autoPlayNextEnabled.value && !passOutGated

        _uiState.update {
            it.copy(
                showNextUp = true,
                nextUpVideoEnded = videoEnded,
                nextUpCountdownSeconds = if (autoCountdown) NEXT_UP_COUNTDOWN_SECONDS else null,
            )
        }
        if (autoCountdown) startNextUpCountdown()
    }

    private fun startNextUpCountdown() {
        nextUpCountdownJob?.cancel()
        nextUpCountdownJob = viewModelScope.launch {
            var remaining = NEXT_UP_COUNTDOWN_SECONDS
            while (remaining > 0) {
                delay(1_000)
                remaining -= 1
                _uiState.update {
                    // Bail if something dismissed the overlay underneath us.
                    if (!it.showNextUp) it else it.copy(nextUpCountdownSeconds = remaining)
                }
                if (!_uiState.value.showNextUp) return@launch
            }
            // Automatic countdown-expiry advance: increment the pass-out streak
            // so a long unattended binge eventually trips the "still watching?"
            // gate. An explicit Play Now (below) resets the streak instead.
            advanceToNextEpisode(nextAutoAdvanceCount = autoAdvanceCount + 1)
        }
    }

    /**
     * Up-Next "Play Now" / Play-Pause-on-overlay: an explicit user choice to keep
     * going. This is active watching, so it RESETS the pass-out streak to 0 —
     * the next episode starts fresh and isn't gated behind the still-watching
     * prompt. The automatic countdown-expiry path keeps incrementing the streak.
     */
    fun playNextEpisodeNow() {
        advanceToNextEpisode(nextAutoAdvanceCount = 0)
    }

    private fun advanceToNextEpisode(nextAutoAdvanceCount: Int) {
        nextUpCountdownJob?.cancel()
        nextUpCountdownJob = null
        val next = _uiState.value.nextEpisode ?: return
        _uiState.update { it.copy(showNextUp = false, nextUpCountdownSeconds = null) }
        _playNextRequests.tryEmit(PlayNextRequest(next.contentId, nextAutoAdvanceCount))
    }

    /** Up-Next "Keep Watching" — dismiss the overlay and stay on the current episode. */
    fun dismissNextUp() {
        nextUpCountdownJob?.cancel()
        nextUpCountdownJob = null
        _uiState.update { it.copy(showNextUp = false, nextUpCountdownSeconds = null) }
    }

    /**
     * Push the fresh list of audio / subtitle tracks up from the screen. Called
     * from a `Player.Listener.onTracksChanged` callback — we keep the list in
     * ViewModel state so the menu composables can read it directly.
     */
    fun onTracksChanged(audio: List<PlayerTrackEntry>, subtitle: List<PlayerTrackEntry>) {
        _uiState.update { it.copy(audioTracks = audio, subtitleTracks = subtitle) }
        resolvePendingSubtitleSelection(subtitle)
        resolvePendingInitialSubtitle(subtitle)
        resolveAutoPreferredTextSubtitle(subtitle)
    }

    fun onTracksChanged(
        audio: List<PlayerTrackEntry>,
        subtitle: List<PlayerTrackEntry>,
        video: List<PlayerTrackEntry>,
        videoQualities: List<VideoQualityOption> = emptyList(),
    ) {
        _uiState.update {
            it.copy(
                audioTracks = audio,
                subtitleTracks = subtitle,
                videoTracks = video,
                videoQualities = videoQualities,
            )
        }
        resolvePendingSubtitleSelection(subtitle)
        resolvePendingInitialSubtitle(subtitle)
        resolveAutoPreferredTextSubtitle(subtitle)
    }

    private fun resolveAutoPreferredTextSubtitle(subtitle: List<PlayerTrackEntry>) {
        if (autoTextSubtitleSelectionAttempted) return
        if (launchArgs.initialSubtitleTrackIndex != null) return
        if (subtitle.isEmpty()) return
        if (subtitle.none { it.isSelected }) return

        val targetIndex = preferredAutoTextSubtitleIndex(
            tracks = subtitle,
            preferredLanguage = _uiState.value.preferredTextLanguage,
        )
        autoTextSubtitleSelectionAttempted = true
        targetIndex ?: return
        _subtitleSelectRequests.tryEmit(targetIndex)
    }

    /**
     * Apply the detail screen's pre-selected subtitle once the player's tracks
     * land.
     *
     * -1 = Off: emitted immediately; the screen's collector finds no match and
     * calls selectSubtitle(null), turning subtitles off.
     *
     * A positive value is the detail selector's ordinal into
     * FileVersion.subtitleTracks, not Media3's flattened text-track ordinal.
     * Resolve it through the mounted server subtitle metadata first so embedded
     * CEA-608 or other player-discovered tracks do not shift the target.
     */
    private fun resolvePendingInitialSubtitle(subtitle: List<PlayerTrackEntry>) {
        val index = pendingInitialSubtitleIndex ?: return
        if (index == -1) {
            pendingInitialSubtitleIndex = null
            _subtitleSelectRequests.tryEmit(-1)
            return
        }
        // Wait for the first non-empty track list, then consume regardless so we
        // only act during initial load.
        if (subtitle.isEmpty()) return
        pendingInitialSubtitleIndex = null
        resolveInitialSubtitleTrackIndex(
            requestedOrdinal = index,
            subtitleTracks = subtitle,
            mountedSubtitles = _uiState.value.subtitleUrls,
        )?.let { _subtitleSelectRequests.tryEmit(it) }
    }

    fun onSubtitleSelectionApplied(index: Int) {
        _uiState.update {
            it.copy(subtitleTracks = subtitleTracksWithSelection(it.subtitleTracks, index))
        }
    }

    /**
     * After refreshSubtitles bumps the nonce, the screen re-prepares the item
     * and a fresh onTracksChanged arrives. Sidecar tracks expose their
     * SubtitleConfiguration label as Format.label, which extractTrackEntries
     * keeps in PlayerTrackEntry.label even when displayLabel is friendlier —
     * so matching by raw label is exact.
     * Emits the ordinal text-group index for SubtitleManager.selectSubtitle.
     */
    private fun resolvePendingSubtitleSelection(subtitle: List<PlayerTrackEntry>) {
        val label = pendingSubtitleSelectLabel ?: return
        val match = subtitle.firstOrNull { it.label == label || it.displayLabel == label } ?: return
        pendingSubtitleSelectLabel = null
        _subtitleSelectRequests.tryEmit(match.index)
    }

    fun beginScrub() {
        _uiState.update { it.copy(isScrubbing = true, scrubPreviewSec = it.position, showControls = true) }
    }

    fun updateScrubPreview(sec: Double) {
        _uiState.update {
            val clamped = sec.coerceIn(0.0, it.duration.coerceAtLeast(0.0))
            it.copy(scrubPreviewSec = clamped)
        }
    }

    fun commitScrub(): Double {
        val target = _uiState.value.scrubPreviewSec
        _uiState.update { it.copy(isScrubbing = false) }
        return target
    }

    fun cancelScrub() {
        _uiState.update { it.copy(isScrubbing = false, scrubPreviewSec = 0.0) }
    }

    fun setControlsVisible(visible: Boolean) {
        _uiState.update {
            it.copy(
                showControls = visible,
                controlsVisibilityNonce = if (visible) {
                    it.controlsVisibilityNonce + 1
                } else {
                    it.controlsVisibilityNonce
                },
            )
        }
    }

    fun openHUD() {
        _uiState.update { it.copy(hudOpen = true, showSubtitleMenu = false, showControls = true) }
    }

    fun closeHUD() {
        _uiState.update { it.copy(hudOpen = false) }
    }

    fun openSubtitleMenu() {
        _uiState.update { it.copy(showSubtitleMenu = true, hudOpen = false, showControls = true) }
    }

    fun closeSubtitleMenu() {
        _uiState.update { it.copy(showSubtitleMenu = false) }
    }

    fun onVideoFillModeChanged(mode: VideoFillMode) {
        _uiState.update { it.copy(videoFillMode = mode) }
    }

    /**
     * Skip the intro now: returns the seek target in seconds so the screen
     * can call MediaController.seekTo. Returns null if there is no active
     * intro range.
     *
     * Returning the value (instead of seeking internally) keeps the VM free
     * of MediaController references — the screen owns the controller.
     */
    fun onSkipIntroNow(): Double? {
        val intro = _uiState.value.intro ?: return null
        introAutoSkipController.cancelCountdown()
        return intro.end
    }

    /** Cancel an in-flight auto-skip countdown — banner falls back to manual Skip. */
    fun onCancelIntroAutoSkip() {
        introAutoSkipController.cancelCountdown()
    }

    /**
     * HUD Chapters pane picked a row. Returns the seek target in seconds;
     * the screen owns the MediaController and performs the actual seek.
     * Returns null when the supplied index is out of range (shouldn't
     * happen — the row list is built from the same `chapters` field — but
     * guarded for safety).
     */
    fun onSeekToChapter(chapterIndex: Int): Double? =
        _uiState.value.chapters.getOrNull(chapterIndex)?.startSeconds

    // ---- Subtitle suite: AI status probe + dialog visibility --------------------

    /**
     * Lazy once-per-player-session AI status probe, fired by the HUD the
     * first time the Subtitles pane is shown. On any failure both flags stay
     * false → the "Translate with AI" row is simply hidden (web parity; no
     * error surfaced).
     */
    fun onSubtitlesPaneShown() {
        if (aiStatusRequested) return
        aiStatusRequested = true
        viewModelScope.launch {
            val status = when (val r = subtitlesRepository.aiStatus()) {
                is ApiResult.Success -> r.data
                else -> SubtitleAiStatus(enabled = false, transcribeEnabled = false)
            }
            _aiTranslate.update { it.copy(statusLoaded = true, status = status) }
        }
    }

    fun openSubtitleSearchDialog() {
        val defaultLang = _uiState.value.preferredTextLanguage
            ?.takeIf { it.isNotBlank() }?.take(2)?.lowercase() ?: "en"
        _subtitleSearch.update {
            // Keep prior results/language when reopening mid-session.
            if (it.hasSearched) it else it.copy(language = defaultLang)
        }
        _uiState.update { it.copy(showSubtitleSearchDialog = true) }
    }

    fun closeSubtitleSearchDialog() {
        _uiState.update { it.copy(showSubtitleSearchDialog = false) }
    }

    fun openSubtitleStyleDialog() {
        _uiState.update { it.copy(showSubtitleStyleDialog = true) }
    }

    fun closeSubtitleStyleDialog() {
        _uiState.update { it.copy(showSubtitleStyleDialog = false) }
    }

    fun openAiTranslateDialog() {
        refreshAiQuota() // spec: quota refreshed on open
        _aiTranslate.update { it.copy(phase = AiJobPhase.Idle) }
        _uiState.update { it.copy(showAiTranslateDialog = true) }
    }

    /** Dismiss the dialog. A running job keeps polling — reopening shows live progress. */
    fun closeAiTranslateDialog() {
        _uiState.update { it.copy(showAiTranslateDialog = false) }
    }

    // ---- Subtitle suite: provider search / download ------------------------------

    fun setSubtitleSearchLanguage(code: String) {
        _subtitleSearch.update { it.copy(language = code) }
    }

    fun searchSubtitles() {
        val mediaFileId = _uiState.value.mediaFileId ?: return
        if (_subtitleSearch.value.isSearching) return
        val language = _subtitleSearch.value.language
        _subtitleSearch.update {
            it.copy(isSearching = true, hasSearched = true, error = null, results = emptyList(), warnings = emptyList())
        }
        viewModelScope.launch {
            val request = SubtitleSearchRequest(mediaFileId = mediaFileId, languages = listOf(language))
            when (val r = subtitlesRepository.search(request)) {
                is ApiResult.Success -> _subtitleSearch.update {
                    it.copy(isSearching = false, results = r.data.results, warnings = r.data.warnings)
                }
                // No capability probe exists — "no providers configured" arrives
                // here as a plain server error; surface its text verbatim.
                is ApiResult.Error, is ApiResult.NetworkError -> _subtitleSearch.update {
                    it.copy(isSearching = false, error = r.errorMessage("Subtitle search failed"))
                }
            }
        }
    }

    fun downloadSubtitle(result: SubtitleResult) {
        val mediaFileId = _uiState.value.mediaFileId ?: return
        if (_subtitleSearch.value.downloadingResultId != null) return
        _subtitleSearch.update { it.copy(downloadingResultId = result.id, error = null) }
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
                    refreshSubtitles(autoSelectSubtitleId = r.data.subtitle.id)
                    _subtitleSearch.update {
                        it.copy(downloadingResultId = null, completedNonce = it.completedNonce + 1)
                    }
                }
                is ApiResult.Error, is ApiResult.NetworkError -> _subtitleSearch.update {
                    it.copy(downloadingResultId = null, error = r.errorMessage("Subtitle download failed"))
                }
            }
        }
    }

    // ---- Subtitle suite: track refresh (web-parity, no session restart) ---------

    /**
     * Refetch the downloaded-subtitle list, merge it into
     * [UiState.subtitleUrls] via the shared pure merge, and bump
     * [UiState.subtitleRefreshNonce] so the screen re-prepares the MediaItem
     * (same stream URL + session — only the sidecar list changes). Selection
     * is label-driven: the freshly downloaded track's label when
     * [autoSelectSubtitleId] matches, otherwise the currently selected track's
     * label so the rebuild preserves the user's choice (Media3 track-group
     * overrides don't survive a re-prepare — groups are new instances).
     */
    suspend fun refreshSubtitles(autoSelectSubtitleId: Int?) {
        val state = _uiState.value
        val mediaFileId = state.mediaFileId ?: return
        // Inert without a remote session — merged track URLs are session-scoped.
        val sessionId = state.sessionId ?: return
        val downloaded = when (val r = subtitlesRepository.list(mediaFileId)) {
            is ApiResult.Success -> r.data.subtitles
            is ApiResult.Error -> {
                Log.w(TAG, "refreshSubtitles failed: ${r.code} ${r.message}")
                return
            }
            is ApiResult.NetworkError -> {
                Log.w(TAG, "refreshSubtitles network error", r.exception)
                return
            }
        }
        if (downloaded.isEmpty()) return
        val merged = mergeDownloadedSubtitles(
            existing = state.subtitleUrls,
            downloaded = downloaded,
            sessionId = sessionId,
            serverUrl = state.serverUrl,
        )
        // Label of the track to auto-select, located via the merge contract:
        // downloaded entries occupy the merged list's tail in listing order
        // (same positional contract mobile's downloadedTrackIndex relies on).
        val autoSelectLabel = autoSelectSubtitleId?.let { id ->
            val pos = downloaded.indexOfFirst { it.id == id }
            if (pos < 0) null else merged.getOrNull(merged.size - downloaded.size + pos)?.label
        }
        if (merged == state.subtitleUrls) {
            // Nothing new to mount (e.g. re-download of an existing entry) —
            // honor auto-select against the already-mounted tracks and skip
            // the rebuild entirely.
            autoSelectLabel?.let { label ->
                state.subtitleTracks.firstOrNull { it.label == label || it.displayLabel == label }
                    ?.let { _subtitleSelectRequests.tryEmit(it.index) }
            }
            return
        }
        pendingSubtitleSelectLabel = autoSelectLabel
            ?: state.subtitleTracks.firstOrNull { it.isSelected }?.label
        _uiState.update {
            it.copy(subtitleUrls = merged, subtitleRefreshNonce = it.subtitleRefreshNonce + 1)
        }
    }

    // ---- Subtitle suite: AI translate / transcribe -------------------------------

    fun refreshAiQuota() {
        viewModelScope.launch {
            when (val r = subtitlesRepository.aiQuota()) {
                is ApiResult.Success -> _aiTranslate.update { it.copy(quota = r.data) }
                else -> Unit // quota line is simply absent on failure
            }
        }
    }

    /**
     * Submit an AI job and poll to completion. `start_position` = current
     * playhead (web parity); no `session_id` — Android polls instead of
     * streaming live cues. Runs in viewModelScope so player exit cancels the
     * poll via structured concurrency (the server job itself keeps running).
     */
    fun submitAiTranslate(
        kind: String,
        sourceIndex: Int,
        sourceLanguage: String?,
        targetLanguage: String,
    ) {
        val mediaFileId = _uiState.value.mediaFileId ?: return
        val phase = _aiTranslate.value.phase
        if (phase is AiJobPhase.Submitting || phase is AiJobPhase.Running) return
        _aiTranslate.update { it.copy(phase = AiJobPhase.Submitting) }
        aiJobPollJob?.cancel()
        aiJobPollJob = viewModelScope.launch {
            val request = SubtitleTranslateRequest(
                mediaFileId = mediaFileId,
                kind = kind,
                sourceIndex = sourceIndex,
                sourceLanguage = sourceLanguage?.ifBlank { null },
                targetLanguage = targetLanguage.ifBlank { null },
                startPosition = _uiState.value.position,
            )
            val job = when (val r = subtitlesRepository.translate(request)) {
                is ApiResult.Success -> r.data.job
                is ApiResult.Error -> {
                    // 429 = quota exhausted → refresh quota so the dialog
                    // flips to the exhausted state; 503 = engine unconfigured.
                    if (r.code == 429) refreshAiQuota()
                    _aiTranslate.update {
                        it.copy(phase = AiJobPhase.Failed(r.errorMessage("Translation failed")))
                    }
                    return@launch
                }
                is ApiResult.NetworkError -> {
                    _aiTranslate.update {
                        it.copy(phase = AiJobPhase.Failed(r.errorMessage("Translation failed")))
                    }
                    return@launch
                }
            }
            activeAiJobId = job.id
            _aiTranslate.update {
                it.copy(phase = AiJobPhase.Running(job.progress, job.progressMessage.ifBlank { null }))
            }
            val outcome = subtitlesRepository.pollJob(
                jobId = job.id,
                onUpdate = { update ->
                    _aiTranslate.update {
                        it.copy(
                            phase = AiJobPhase.Running(
                                update.progress,
                                update.progressMessage.ifBlank { null },
                            ),
                        )
                    }
                },
            )
            activeAiJobId = null
            when (outcome) {
                is SubtitlesRepository.SubtitleJobOutcome.Completed -> {
                    refreshSubtitles(autoSelectSubtitleId = outcome.resultSubtitleId)
                    _aiTranslate.update {
                        it.copy(phase = AiJobPhase.Idle, completedNonce = it.completedNonce + 1)
                    }
                }
                is SubtitlesRepository.SubtitleJobOutcome.Failed -> _aiTranslate.update {
                    it.copy(phase = AiJobPhase.Failed(outcome.message ?: "Translation failed"))
                }
                SubtitlesRepository.SubtitleJobOutcome.Cancelled -> _aiTranslate.update {
                    it.copy(phase = AiJobPhase.Idle)
                }
            }
        }
    }

    /** Dialog Cancel row: stop polling, ask the server to cancel, return to the form. */
    fun cancelAiTranslateJob() {
        val jobId = activeAiJobId
        aiJobPollJob?.cancel()
        aiJobPollJob = null
        activeAiJobId = null
        _aiTranslate.update { it.copy(phase = AiJobPhase.Idle) }
        if (jobId != null) {
            viewModelScope.launch { subtitlesRepository.cancelJob(jobId) }
        }
    }

    /** Failed phase → back to the form after the user acknowledges the error. */
    fun clearAiTranslateError() {
        _aiTranslate.update { it.copy(phase = AiJobPhase.Idle) }
    }

    // ---- Settings setters (forward to per-profile DataStore) -------------------
    fun onSetPlaybackSpeed(value: Double) {
        viewModelScope.launch { playerSettingsStore.setPlaybackSpeed(value) }
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
     * HUD Audio pane stepper handler. Coerced to ±500ms in the store; the
     * service binding (E T3) picks up the new value and pushes it into the
     * shared [com.continuum.app.common.player.audio.DelayAudioProcessor]
     * (forcing a flush via `seekTo(currentPosition)` so the change applies
     * mid-playback).
     */
    fun onAudioDelayChanged(delayMs: Int) {
        viewModelScope.launch { playerSettingsStore.setAudioSyncMs(delayMs) }
    }

    /**
     * HUD Subtitles pane stepper handler. Coerced to ±500ms in the store; the
     * service binding (A.3f T2) picks up the new value and pushes it into the
     * shared [com.continuum.app.common.player.subtitle.SubtitleOffsetHolder]
     * (forcing a flush via `seekTo(currentPosition)` so the change applies
     * mid-playback by dropping already-buffered cues).
     */
    fun onSubtitleDelayChanged(delayMs: Int) {
        viewModelScope.launch { playerSettingsStore.setSubtitleSyncMs(delayMs) }
    }

    // ---- Sleep timer setters ---------------------------------------------------
    fun onStartSleepTimer(minutes: Int) {
        sleepTimer.start(minutes)
        if (minutes > 0) {
            viewModelScope.launch { playerSettingsStore.setSleepTimerDefaultMinutes(minutes) }
        }
    }

    fun onCancelSleepTimer() {
        sleepTimer.cancel()
    }

    suspend fun stopSessionForExit() {
        // Track B: durably record the final position + prompt a drain (covers the
        // online offline-download case with no live session / connectivity change).
        val fileId = _uiState.value.selectedFileId ?: _uiState.value.mediaFileId
        if (contentId.isNotBlank() && fileId != null) {
            userItemStatePort.recordPosition(
                contentId,
                fileId,
                _uiState.value.position,
                _uiState.value.duration.takeIf { it > 0.0 },
            )
            outboxSyncScheduler.requestSync()
        }
        sessionLifecycle.stop()
        introObserveJob?.cancel()
        nextUpCountdownJob?.cancel()
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
                subtitleUrls = emptyList(),
                isPaused = true,
                isPlaying = false,
            )
        }
    }

    fun onExit() {
        viewModelScope.launch { stopSessionForExit() }
    }

    /**
     * Surfaces a player runtime error (decoder init, source, network/401 after
     * prepare). Without this the screen can sit on a stale spinner instead of
     * an actionable error. The error UI offers [retry].
     */
    fun onPlayerError(error: androidx.media3.common.PlaybackException) {
        val state = _uiState.value
        val message = error.localizedMessage?.takeIf { msg -> msg.isNotBlank() }
            ?: "Playback failed. Please try again."
        // #8: a transient network blip shouldn't immediately demote a healthy
        // direct stream to a server transcode for the rest of playback. Retry the
        // SAME route a bounded number of times first; transientNetworkRetries
        // resets to 0 once playback actually progresses (onPositionChanged), so a
        // persistent outage still falls through to the recovery ladder below.
        val isTransientNetwork =
            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        if (isTransientNetwork &&
            state.sessionId != null &&
            transientNetworkRetries < MAX_TRANSIENT_NETWORK_RETRIES
        ) {
            transientNetworkRetries++
            Log.i(TAG, "Transient network error; retrying same route ($transientNetworkRetries/$MAX_TRANSIENT_NETWORK_RETRIES)")
            retry()
            return
        }
        val recoveryAction = recoveryPlanner.planForPlayerError(state.playbackPlan, error, attemptedEngines)
        if (applyAlternateDirectFallback(state, recoveryAction)) return
        if (state.sessionId != null && recoveryAction != PlaybackRecoveryAction.None) {
            startServerRecoveryFallback(message, recoveryAction, state)
            return
        }
        _uiState.update {
            it.copy(
                isLoading = false,
                error = message,
            )
        }
    }

    fun onEngineSwitchFailed(message: String) {
        val state = _uiState.value
        if (state.sessionId != null) {
            // Don't blindly remux: prefer another direct engine, then fall through
            // the plan's remux/transcode ladder. A transcode-only source can't be
            // rescued by a remux that copies the same streams.
            val recoveryAction = recoveryPlanner.planForEngineSwitchFailure(
                state.playbackPlan,
                attemptedEngines,
            )
            if (applyAlternateDirectFallback(state, recoveryAction)) return
            startServerRecoveryFallback(message, recoveryAction, state)
            return
        }
        _uiState.update {
            it.copy(
                isLoading = false,
                error = message,
            )
        }
    }

    /** Reload the current content from the last known position (error-screen retry). */
    fun retry() {
        val resumeAt = _uiState.value.position.takeIf { it > 0.0 }
        val staleSessionId = _uiState.value.sessionId
        viewModelScope.launch {
            // Stop the previous server session first so a retry can't orphan it
            // until timeout (loadContent's adoptActiveSession replaces local
            // reporter state but does not stop the old server session).
            if (staleSessionId != null) {
                runCatching { playbackSessionManager.stopSession(staleSessionId) }
            }
            // Retry resumes exactly where it failed — no skip-back nudge.
            loadContent(startPositionOverride = resumeAt, suppressResumeRewind = true)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Guarantee the final resume position is persisted on teardown. The
        // periodic write runs in viewModelScope, which is cancelling here — so
        // AWAIT one last write under NonCancellable (a brief local Room write off
        // the main thread). Mirrors phone PlayerViewModel.onCleared; without it,
        // exiting while playing loses the last spot.
        val cid = contentId.takeIf { it.isNotBlank() }
        val fid = _uiState.value.selectedFileId ?: _uiState.value.mediaFileId
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
        introObserveJob?.cancel()
        lifecycleObserveJob?.cancel()
        nextUpCountdownJob?.cancel()
        introAutoSkipController.reset()
        val sessionId = _uiState.value.sessionId
        if (sessionId != null) {
            // Best-effort session stop; viewModelScope is cancelling so this may
            // not complete — the NonCancellable write above already persisted progress.
            viewModelScope.launch { sessionLifecycle.stop() }
        }
    }

}
