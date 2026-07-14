@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.siloserver.silo.tv.ui.screens.player

import org.siloserver.silo.common.player.dolbyVisionTransformClassification

import org.siloserver.silo.tv.BuildConfig

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.tv.data.preferences.PlaybackQuality
import org.siloserver.silo.common.player.PlaybackAnalyticsListener
import org.siloserver.silo.common.player.PlaybackCapabilityDetector
import org.siloserver.silo.common.player.PlaybackSessionLifecycle
import org.siloserver.silo.common.player.PlaybackSessionManager
import org.siloserver.silo.common.player.VideoSessionStartV3
import org.siloserver.silo.common.player.PlayerNotice
import org.siloserver.silo.common.player.PlayerStatsSnapshot
import org.siloserver.silo.common.player.SessionState
import org.siloserver.silo.common.player.SleepTimerController
import org.siloserver.silo.common.player.SleepTimerState
import org.siloserver.silo.common.player.StartParams
import org.siloserver.silo.common.player.isBitmapSubtitleCodecOrMime
import org.siloserver.silo.common.player.backend.VideoBackendCapabilities
import org.siloserver.silo.common.player.reducePlayerStats
import org.siloserver.silo.common.player.seek.PendingSeekPresentationGuard
import org.siloserver.silo.common.player.seek.PlaybackSeekDecision
import org.siloserver.silo.common.player.seek.QuickSkipAccumulator
import org.siloserver.silo.common.player.seek.SeekBoundsMs
import org.siloserver.silo.common.player.seek.SeekPositionDecision
import org.siloserver.silo.common.player.seek.decideSeek
import org.siloserver.silo.common.player.seek.isSameRouteSeekReanchorCandidate
import org.siloserver.silo.common.player.seek.playerPositionForSource
import org.siloserver.silo.common.player.seek.sourcePositionForPlayer
import org.siloserver.silo.common.player.video.VideoPlaybackSessionCoordinator
import org.siloserver.silo.common.player.video.VideoPlaybackStartRequest
import org.siloserver.silo.common.player.video.VideoPlayerUiState
import org.siloserver.silo.common.player.video.resolvedPlaybackDelivery
import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.common.settings.dolbyVisionPolicySnapshot
import org.siloserver.silo.domain.player.IntroAutoSkipController
import org.siloserver.silo.domain.player.IntroAutoSkipState
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.catalog.TimeRange
import org.siloserver.silo.model.catalog.VersionChapter
import org.siloserver.silo.model.settings.SubtitleAppearance
import org.siloserver.silo.model.playback.PlaybackDelivery
import org.siloserver.silo.model.playback.PlayMethod
import org.siloserver.silo.model.playback.PlaybackExecutionPlan
import org.siloserver.silo.model.playback.PlaybackRouteFamily
import org.siloserver.silo.model.playback.PlaybackSessionResponse
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.buildPlaybackSubtitleChoices
import org.siloserver.silo.model.playback.mergeDownloadedSubtitles
import org.siloserver.silo.model.subtitles.SubtitleAiQuota
import org.siloserver.silo.model.subtitles.SubtitleAiStatus
import org.siloserver.silo.model.subtitles.SubtitleDownloadRequest
import org.siloserver.silo.model.subtitles.SubtitleResult
import org.siloserver.silo.model.subtitles.SubtitleSearchRequest
import org.siloserver.silo.model.subtitles.SubtitleTranslateRequest
import org.siloserver.silo.playback.SUBTITLE_OFF_FINGERPRINT
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.errorMessage
import org.siloserver.silo.playback.nextEpisodeAfter
import org.siloserver.silo.playback.resolveMountedSubtitleOrdinal
import org.siloserver.silo.playback.subtitleTrackFingerprint
import org.siloserver.silo.playback.trackSelectionFingerprint
import org.siloserver.silo.repository.SubtitlesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Renderable audio or subtitle track pulled out of ExoPlayer's current
 * `Tracks` object. `id` is just the ordinal position among groups of the
 * same type — it's used as the index argument when calling
 * [org.siloserver.silo.common.player.AudioTrackManager.selectAudioTrack] or
 * [org.siloserver.silo.common.player.SubtitleManager.selectSubtitle]. [label]
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
    val channelCount: Int = 0,
    val isForced: Boolean = false,
    val isHearingImpaired: Boolean = false,
)

internal fun selectedServerAudioTrackIndex(
    selectedPlayerOrdinal: Int?,
    catalogAudioTracks: List<AudioTrack>?,
    currentPlanTrackIndex: Int?,
): Int? = selectedPlayerOrdinal
    ?.let { catalogAudioTracks?.getOrNull(it)?.index }
    ?: currentPlanTrackIndex

private val hearingImpairedSubtitleTokenRegex = Regex(
    pattern = """(^|[^a-z0-9])(cc|sdh|hi)([^a-z0-9]|$)""",
    option = RegexOption.IGNORE_CASE,
)

internal fun String.indicatesHearingImpairedSubtitle(): Boolean {
    val lower = lowercase()
    return lower.contains("closed caption") ||
        lower.contains("hearing impaired") ||
        lower.contains("hearing-impaired") ||
        lower.contains("hearing") ||
        hearingImpairedSubtitleTokenRegex.containsMatchIn(this)
}

private fun PlayerTrackEntry.isEffectivelyHearingImpaired(): Boolean =
    isHearingImpaired ||
        label.indicatesHearingImpairedSubtitle() ||
        displayLabel.indicatesHearingImpairedSubtitle()

internal fun subtitleTracksWithSelection(
    tracks: List<PlayerTrackEntry>,
    selectedIndex: Int,
): List<PlayerTrackEntry> =
    tracks.map { track ->
        track.copy(isSelected = selectedIndex >= 0 && track.index == selectedIndex)
    }

internal sealed class SubtitleAutoSelection {
    data object NoChange : SubtitleAutoSelection()
    data object Disable : SubtitleAutoSelection()
    data class Select(val index: Int) : SubtitleAutoSelection()
}

internal fun resolveAutoSubtitleSelection(
    audioTracks: List<PlayerTrackEntry>,
    subtitleTracks: List<PlayerTrackEntry>,
    preferredLanguage: String?,
    subtitleMode: String?,
    showForced: Boolean,
): SubtitleAutoSelection {
    if (subtitleTracks.isEmpty()) return SubtitleAutoSelection.NoChange

    val mode = subtitleMode?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: "auto"
    if (mode == "off") return SubtitleAutoSelection.Disable

    if (preferredLanguage != null && preferredLanguage.isBlank()) {
        return SubtitleAutoSelection.Disable
    }
    val targetLanguage = normalizedSubtitleLanguage(preferredLanguage)
    if (targetLanguage == null) {
        if (mode == "always") {
            return bestAutoSubtitleTrack(
                subtitleTracks = subtitleTracks,
                targetLanguage = null,
                preferForced = showForced,
            )?.let { SubtitleAutoSelection.Select(it.index) }
                ?: SubtitleAutoSelection.NoChange
        }
        return SubtitleAutoSelection.NoChange
    }

    val selectedAudioLanguage = audioTracks
        .firstOrNull { it.isSelected }
        ?.language
        ?.let(::normalizedSubtitleLanguage)
    if (mode == "auto" && selectedAudioLanguage != null && selectedAudioLanguage == targetLanguage) {
        if (showForced) {
            val forcedTarget = bestForcedAutoSubtitleTrack(
                subtitleTracks = subtitleTracks,
                targetLanguage = targetLanguage,
            )
            if (forcedTarget != null) {
                // Idempotent re-select even when already selected: NoChange is
                // reserved for "no track should be on", so the launch-time
                // consumer can map it to an explicit disable (Apple parity)
                // without turning off a forced track the defaults picked.
                return SubtitleAutoSelection.Select(forcedTarget.index)
            }
        }
        return SubtitleAutoSelection.Disable
    }

    val target = bestAutoSubtitleTrack(
        subtitleTracks = subtitleTracks,
        targetLanguage = targetLanguage,
        preferForced = showForced,
    ) ?: if (showForced) {
        subtitleTracks.firstOrNull { it.isForced }
    } else {
        null
    }

    return when (target) {
        // Idempotent re-select for an already-selected target (see the forced
        // branch above): NoChange now strictly means "no track should be on".
        null -> SubtitleAutoSelection.NoChange
        else -> SubtitleAutoSelection.Select(target.index)
    }
}

internal fun preferredAutoTextSubtitleIndex(
    tracks: List<PlayerTrackEntry>,
    preferredLanguage: String?,
): Int? {
    return when (
        val selection = resolveAutoSubtitleSelection(
            audioTracks = emptyList(),
            subtitleTracks = tracks,
            preferredLanguage = preferredLanguage,
            subtitleMode = "auto",
            showForced = true,
        )
    ) {
        // This helper answers "which track should we MOVE to" — an idempotent
        // re-select of the already-selected target (see the resolver) is not a
        // move, so it stays null here.
        is SubtitleAutoSelection.Select ->
            selection.index.takeUnless { idx -> tracks.any { it.index == idx && it.isSelected } }
        SubtitleAutoSelection.Disable,
        SubtitleAutoSelection.NoChange -> null
    }
}

private fun bestAutoSubtitleTrack(
    subtitleTracks: List<PlayerTrackEntry>,
    targetLanguage: String?,
    preferForced: Boolean,
): PlayerTrackEntry? {
    val pool = if (targetLanguage == null) {
        subtitleTracks
    } else {
        subtitleTracks.filter { normalizedSubtitleLanguage(it.language) == targetLanguage }
    }
    if (pool.isEmpty()) return null

    if (preferForced) {
        pool.firstOrNull { it.isForced && !it.isEffectivelyHearingImpaired() && !isBitmapSubtitleCodecOrMime(it.codecOrMime) }
            ?.let { return it }
    }
    pool.firstOrNull { !it.isForced && !it.isEffectivelyHearingImpaired() && !isBitmapSubtitleCodecOrMime(it.codecOrMime) }
        ?.let { return it }
    pool.firstOrNull { !it.isForced && !isBitmapSubtitleCodecOrMime(it.codecOrMime) }
        ?.let { return it }
    pool.firstOrNull { !isBitmapSubtitleCodecOrMime(it.codecOrMime) }
        ?.let { return it }
    return pool.first()
}

private fun bestForcedAutoSubtitleTrack(
    subtitleTracks: List<PlayerTrackEntry>,
    targetLanguage: String?,
): PlayerTrackEntry? {
    val pool = if (targetLanguage == null) {
        subtitleTracks
    } else {
        subtitleTracks.filter { normalizedSubtitleLanguage(it.language) == targetLanguage }
    }.filter { it.isForced }
    if (pool.isEmpty()) return null

    pool.firstOrNull { !it.isEffectivelyHearingImpaired() && !isBitmapSubtitleCodecOrMime(it.codecOrMime) }
        ?.let { return it }
    pool.firstOrNull { !it.isEffectivelyHearingImpaired() }
        ?.let { return it }
    return pool.first()
}

internal fun resolveInitialSubtitleTrackIndex(
    requestedOrdinal: Int,
    subtitleTracks: List<PlayerTrackEntry>,
    mountedSubtitles: List<PlayerSubtitleInfo>,
): Int? {
    // Key on the STABLE server subtitle index (PlayerSubtitleInfo.index) first,
    // by identity not list position: a server index gap (a burned/skipped track)
    // or a downloaded tail entry shifts list positions, so a positional hit would
    // shadow the correct index-field match and select the wrong subtitle. Only
    // fall back to positional lookup when nothing carries the requested index.
    val requested = mountedSubtitles.firstOrNull { it.index == requestedOrdinal }
        ?: mountedSubtitles.getOrNull(requestedOrdinal)
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
    val targetCodec = normalizedSubtitleCodec(subtitle.codec ?: subtitleCodecFromUrl(subtitle.url))
    val trackCodec = normalizedSubtitleCodec(codecOrMime)
    // V3 embedded-bitmap selection metadata intentionally has no synthetic
    // label/language: those values belong to the real demuxed Media3 track.
    // In that case the codec family is the stable bridge (dvd_subtitle ->
    // application/vobsub, for example).
    if (targetLanguage == null) {
        return targetCodec != null && trackCodec == targetCodec
    }
    if (trackLanguage != targetLanguage) return false
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
        ?.filter { it.isLetterOrDigit() }
        ?.lowercase()
        ?: return null
    return when {
        normalized == "ass" || normalized == "ssa" || normalized.contains("xssa") -> "ssa"
        normalized == "srt" || normalized.contains("subrip") -> "srt"
        normalized == "vtt" || normalized == "textvtt" || normalized.contains("webvtt") -> "vtt"
        normalized.contains("pgs") -> "pgs"
        normalized.contains("dvd") || normalized.contains("vobsub") -> "vobsub"
        normalized.contains("dvbsub") -> "dvbsub"
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
    val preferredQuality: String? = null,
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
data class PlayNextRequest(val contentId: String, val autoAdvanceCount: Int, val preferredQuality: String?)

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
 * Playback itself still goes through [org.siloserver.silo.common.player.SiloPlayerFactory] +
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
    private val userItemStatePort: org.siloserver.silo.repository.port.UserItemStatePort,
    private val outboxSyncScheduler: org.siloserver.silo.common.data.sync.OutboxSyncScheduler,
    // Next-episode resolution for auto-advance (F2).
    private val catalogRepository: org.siloserver.silo.repository.CatalogRepository,
    private val launchArgs: TvPlayerLaunchArgs,
) : ViewModel() {

    companion object {
        private const val TAG = "TvPlayerViewModel"
        // A transient network blip retries the same route this many times before
        // demoting to a server transcode (resets once playback progresses).
        private const val MAX_TRANSIENT_NETWORK_RETRIES = 1
        private const val SEEK_SETTLE_DEADLINE_MS = 15_000L
        // Record a durable position roughly every 10s of content time.
        private const val POSITION_RECORD_INTERVAL_SEC = 10.0
        // Non-empty onTracksChanged callbacks an unresolved explicit subtitle
        // gives up and lets the persisted/auto fallback proceed.
        private const val MAX_PENDING_INITIAL_SUBTITLE_ATTEMPTS = 5
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
    private val preferredQuality: String? = launchArgs.preferredQuality
    // Explicit session-level video-quality intent chosen in the player's
    // Quality menu. Null uses [preferredQuality] as the default output ceiling.
    // Wire values match
    // [PlaybackQuality]: "auto"/"original"/"2160p"/"1080p"/"720p"/"480p".
    private var qualityOverride: String? = null
    private val roomId: String? = launchArgs.roomId
    private val resumePositionOverride: Double? = launchArgs.resumePositionOverride

    // Pre-playback track selections from the detail screen. Audio is sent to the
    // server session start; subtitle is applied once the player's tracks land
    // (see [applyInitialSubtitleIfPending]). Cleared after the first apply so a
    // later user track change isn't overridden.
    private val initialAudioTrackIndex: Int? = launchArgs.initialAudioTrackIndex
    private var pendingInitialSubtitleIndex: Int? = launchArgs.initialSubtitleTrackIndex

    /**
     * Non-empty track callbacks the explicit pick has failed to resolve
     * tracks land (Media3 reports everything at once), so an unresolved pick
     * is retried across callbacks until the sidecars arrive — bounded by
     * [MAX_PENDING_INITIAL_SUBTITLE_ATTEMPTS], after which the pick is dropped
     * so the persisted/auto fallback can proceed.
     */
    private var pendingInitialSubtitleAttempts = 0
    private var pendingPersistedAudioFingerprint: String? = null
    private var pendingPersistedSubtitleFingerprint: String? = null
    private var autoTextSubtitleSelectionAttempted = false
    private var manualSubtitleSelectionApplied = false

    /** Guards [startServerRecoveryFallback] against concurrent fallbacks racing the same session. */
    private var recoveryJob: Job? = null

    /**
     * Seek recovery has its own latest-target-wins single flight. It is intentionally separate
     * from [recoveryJob]: a committed seek HTTP request is never cancelled by a newer seek, and
     * general playback replans keep their existing single-flight behavior.
     */
    private val seekRecoveryQueue = TvSeekRecoveryQueue()
    private val transportMountGate = TvTransportMountGate()
    private var seekRecoveryRollbackInvalidated = false
    private var pendingNativeSeekAfterMount: Double? = null
    private var transportMountSequence = 0L

    /**
     * Single-flight guard for in-player session restarts ([onSelectFileVersion] +
     * [retry]). Both await a stopSession round-trip before [loadContent] flips
     * isLoading; two rapid picks would otherwise run concurrent load pipelines and
     * orphan a server session. Cancel-and-replace so a fresh pick supersedes an
     * in-flight one without permanently locking out later switches.
     */
    private var versionSwitchJob: Job? = null

    /**
     * Monotonic generation for [loadContent] pipelines. Bumped at the top of
     * every loadContent call; each pipeline captures its value at entry and
     * re-checks it before applying results to [_uiState], so a superseded
     * pipeline (rapid version picks, a retry racing a pick) is inert even
     * once its coordinator round-trip returns.
     */
    private var contentLoadGeneration = 0L

    /** Same-route retries spent on transient network errors; reset once playback progresses. */
    private var transientNetworkRetries = 0
    private val quickSkipAccumulator = QuickSkipAccumulator()
    private val seekPresentationGuard = PendingSeekPresentationGuard()
    private var quickSkipCommitJob: Job? = null
    private var quickSkipOriginMs: Long = 0L
    private var activeSeekTargetSec: Double? = null
    private var activeSeekStartedAtMs: Long = 0L
    private var sameRouteSeekRecoveryAttempted = false
    private var seekSequence = 0L
    private var activeSeekId: Long? = null
    private var hasRenderedFirstFrame = false

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
        val requestHeaders: Map<String, String> = emptyMap(),
        val delivery: PlaybackDelivery? = null,
        val streamUrl: String? = null,
        /**
         * Monotonic identity for a concrete player mount. A seek re-anchor can legally return the
         * same URL and plan id with only a new timeline origin, so URL/plan equality is not enough
         * to make Compose mount it again.
         */
        val transportMountNonce: Long = 0L,
        val container: String? = null,
        val serverUrl: String = "",
        val accessToken: String = "",
        val selectedFileId: Int? = null,
        /** All server file versions for this item (in-player version switching). */
        val fileVersions: List<org.siloserver.silo.model.catalog.FileVersion> = emptyList(),
        val selectedFileResolution: String? = null,
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
        // [SiloPlayerFactory.createMediaSource] so the player loads them
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
        val preferredSubtitleMode: String? = null,
        val showForcedSubtitles: Boolean = true,
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

    private val seekRequestChannel = Channel<Double>(capacity = Channel.BUFFERED)
    val seekRequests: Flow<Double> = seekRequestChannel.receiveAsFlow()

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
    val dolbyVisionEnabled: StateFlow<Boolean> = playerSettingsStore.dolbyVisionEnabledFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val matchContentFrameRate: StateFlow<Boolean> = playerSettingsStore.matchContentFrameRateFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    // Effective = custom appearance unless "Match Device Settings" is on
    // (then the OS captioning style, tvOS parity).
    val subtitleAppearance: StateFlow<SubtitleAppearance> = playerSettingsStore.effectiveSubtitleAppearanceFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, SubtitleAppearance.DEFAULT)
    /**
     * Per-profile audio delay in ms, ±500 clamp. Sourced from
     * [PlayerSettingsStore.audioSyncMsFlow]; mirrored into the active
     * [org.siloserver.silo.common.player.audio.DelayAudioProcessor] by
     * [org.siloserver.silo.common.player.SiloPlaybackService] (E T3).
     * The HUD Audio pane reads this for its delay stepper.
     */
    val audioDelayMs: StateFlow<Int> = playerSettingsStore.audioSyncMsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)
    /**
     * Per-profile subtitle delay in ms, ±500 clamp. Sourced from
     * [PlayerSettingsStore.subtitleSyncMsFlow]; mirrored into the active
     * [org.siloserver.silo.common.player.subtitle.SubtitleOffsetHolder] by
     * [org.siloserver.silo.common.player.SiloPlaybackService] (A.3f T2).
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
        // Keep the process-wide active-file marker in sync (phone parity), so
        // Reclaim Watched never deletes bytes under a live player.
        viewModelScope.launch {
            _uiState
                .map { it.selectedFileId ?: it.mediaFileId }
                .distinctUntilChanged()
                .collect { org.siloserver.silo.common.player.ActivePlaybackFile.set(it) }
        }
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
        viewModelScope.launch {
            sessionLifecycle.missingSessionEvents.collect { position ->
                val state = _uiState.value
                if (state.sessionId != null) {
                    loadContent(
                        startPositionOverride = position,
                        preferredFileIdOverride = state.selectedFileId ?: state.mediaFileId,
                        suppressResumeRewind = true,
                    )
                }
            }
        }
        viewModelScope.launch {
            capabilityDetector.outputRouteGeneration.drop(1).collect {
                val state = _uiState.value
                if (state.sessionId != null && state.playbackPlan != null) {
                    startProtocolV3Replan(
                        classification = "output_route_changed",
                        notice = "Audio or display output changed. Revalidating playback.",
                        state = state,
                    )
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
        // SiloPlaybackService; we just subscribe — no extra registration.
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

    private fun nextTransportMountNonce(): Long {
        transportMountSequence = if (transportMountSequence == Long.MAX_VALUE) {
            1L
        } else {
            transportMountSequence + 1L
        }
        transportMountGate.expect(transportMountSequence)
        return transportMountSequence
    }

    /**
     * Called after [org.siloserver.silo.common.player.backend.VideoPlaybackBackend.mount] has
     * synchronously replaced the Media3 item. Nonce qualification prevents an older cancelled
     * Compose effect from unblocking reports for a newer timeline.
     */
    fun onTransportMountApplied(nonce: Long) {
        if (transportMountGate.applied(nonce)) {
            pendingNativeSeekAfterMount?.let { targetSeconds ->
                pendingNativeSeekAfterMount = null
                // Re-evaluate against the plan that actually won the load;
                // source/player time may no longer be identical.
                executeSeekTarget(targetSeconds)
            }
        }
    }

    /**
     * Invalidates seek work for a new content/version load. A request already on the wire is left
     * to finish so server playback-attempt state remains coherent; its generation can no longer
     * pass the adoption guard.
     */
    private fun resetSeekRecoveryForContentChange() {
        recoveryJob?.cancel()
        recoveryJob = null
        seekRecoveryQueue.reset()
        cancelPendingQuickSkip()
        seekPresentationGuard.cancel()
        activeSeekTargetSec = null
        activeSeekId = null
        sameRouteSeekRecoveryAttempted = false
        seekRecoveryRollbackInvalidated = false
        pendingNativeSeekAfterMount = null
    }

    private fun loadContent(
        startPositionOverride: Double? = null,
        preferredFileIdOverride: Int? = null,
        // True for retry: re-load at the current position without nudging back
        // (a normal first resume keeps the default false so it gets the rewind).
        suppressResumeRewind: Boolean = false,
    ) {
        // Capture this pipeline's generation; a later loadContent bump makes
        // this one inert before it can touch _uiState.
        val generation = ++contentLoadGeneration
        hasRenderedFirstFrame = false
        resetSeekRecoveryForContentChange()
        transportMountGate.beginLoad()
        introAutoSkipController.reset()
        manualSubtitleSelectionApplied = false
        _uiState.update { it.copy(isBuffering = false) }

        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                runCatching { playerSettingsStore.refreshFromServer() }
                val result = videoPlaybackCoordinator.start(
                    VideoPlaybackStartRequest(
                        contentId = contentId,
                        preferredFileId = preferredFileIdOverride ?: preferredFileId,
                        roomId = roomId,
                        resumePositionOverride = startPositionOverride,
                        audioTrackIndex = initialAudioTrackIndex,
                        subtitleTrackIndex = pendingInitialSubtitleIndex,
                        preferredQualityOverride = preferredQuality,
                        playbackQualityIntent = qualityOverride,
                        suppressResumeRewind = suppressResumeRewind,
                    ),
                )
                // A newer loadContent superseded this pipeline while start()
                // was in flight — its results must not clobber the newer
                // pipeline's session state.
                if (generation != contentLoadGeneration) return@launch
                when (result) {
                    is VideoPlayerUiState.Ready -> {
                        val transportMountNonce = nextTransportMountNonce()
                        val localTrackSelection = result.fileId
                            ?.let { fileId -> userItemStatePort.localTrackSelection(contentId, fileId) }
                        pendingPersistedAudioFingerprint = if (initialAudioTrackIndex == null) {
                            localTrackSelection?.audioFingerprint
                        } else {
                            null
                        }
                        // Keep the persisted subtitle fingerprint even when the
                        // detail page sent an explicit pick: on TV a pick only
                        // resolves once Media3 reports its tracks, so an
                        // unresolvable pick must fall through to persisted (then
                        // auto) instead of stranding subtitles Off all session.
                        // The suppression now gates on the pick actually resolving
                        // (see resolvePendingInitialSubtitle), not the bare intent.
                        pendingPersistedSubtitleFingerprint = localTrackSelection?.subtitleFingerprint
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = null,
                                title = result.title,
                                artworkUrl = result.artworkUrl,
                                sessionId = result.sessionId,
                                playMethod = result.playMethod,
                                playbackPlan = result.playbackPlan,
                                requestHeaders = result.requestHeaders,
                                delivery = result.delivery,
                                streamUrl = result.streamUrl,
                                transportMountNonce = transportMountNonce,
                                container = result.container,
                                serverUrl = result.serverUrl,
                                accessToken = result.accessToken,
                                selectedFileId = result.fileId,
                                fileVersions = result.versions,
                                selectedFileResolution = result.fileResolution,
                                // Server-transcode quality ladder for this source
                                // (tvOS parity) — replaces adaptive-variant options.
                                videoQualities = transcodeQualityLadder(
                                    result.fileResolution,
                                    qualityOverride ?: preferredQuality ?: PlaybackQuality.Auto.wireValue,
                                ),
                                mediaFileId = result.mediaFileId,
                                startPosition = result.startPositionSeconds,
                                position = result.sourceStartPositionSeconds,
                                duration = result.durationSeconds,
                                isPaused = false,
                                subtitleUrls = result.subtitleUrls,
                                preferredAudioLanguage = result.preferredAudioLanguage,
                                preferredTextLanguage = result.preferredTextLanguage,
                                preferredSubtitleMode = result.preferredSubtitleMode,
                                showForcedSubtitles = result.showForcedSubtitles,
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
                                // T11: clear the subtitle-refresh nonce on every
                                // fresh mount. It is bumped once per post-download
                                // refresh; without this reset a later backend
                                // recreation (version switch / recovery fallback)
                                // would see a stale nonce>0 and re-fire a spurious
                                // second refresh racing the primary mount effect.
                                subtitleRefreshNonce = 0,
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
                if (generation != contentLoadGeneration) return@launch
                fail("Unexpected error: ${e.message}")
            }
        }
    }

    private fun startIntroAutoSkipObserver() {
        // Auto-skip is a local transport action: in a Watch Together room only
        // the host's transport may move position, so never auto-skip in a room
        // (a guest jump would fight the host's broadcast in a yank-back loop).
        // The observer still runs in a room — with enabled pinned false the
        // controller only ever surfaces ShowingButton (prompt visible, never
        // counts down or auto-fires), keeping the manual Skip Intro button
        // alive; its press routes through the screen's gate-checked seek.
        val autoSkipEnabled = if (roomId != null) {
            flowOf(false)
        } else {
            playerSettingsStore.autoSkipIntroFlow
        }
        introObserveJob?.cancel()
        introObserveJob = introAutoSkipController.observe(
            position = _uiState
                .map { it.position }
                .distinctUntilChanged(),
            introRange = _uiState
                .map { it.intro }
                .distinctUntilChanged(),
            autoSkipEnabled = autoSkipEnabled,
            introKey = _uiState
                .map { state ->
                    state.intro?.let { intro ->
                        "${state.sessionId}:${state.selectedFileId}:${intro.start}:${intro.end}"
                    }
                }
                .distinctUntilChanged(),
            onAutoSkipFire = { seekToSec -> seekImmediate(seekToSec) },
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
    fun onUnsupportedPlayback(reason: org.siloserver.silo.common.player.Playability) {
        val state = _uiState.value
        if (state.sessionId == null) return

        val notice = when (reason) {
            is org.siloserver.silo.common.player.Playability.UnsupportedDvProfile ->
                "This device cannot play Dolby Vision Profile ${reason.profile}. Falling back to transcoded stream."
            is org.siloserver.silo.common.player.Playability.UnsupportedAudioCodec ->
                "Lossless audio not supported on this output. Falling back to transcoded stream."
            is org.siloserver.silo.common.player.Playability.UnsupportedChannelCount ->
                "Audio channel count not supported. Falling back to transcoded stream."
            is org.siloserver.silo.common.player.Playability.StartupStalled ->
                "Playback did not start cleanly on this device. Falling back to transcoded stream."
            org.siloserver.silo.common.player.Playability.Supported -> return
        }
        Log.i(TAG, "Preflight fallback: $notice")

        if (reason is org.siloserver.silo.common.player.Playability.StartupStalled &&
            reason.classification == "transport_stall" &&
            state.playbackPlan != null &&
            transientNetworkRetries < MAX_TRANSIENT_NETWORK_RETRIES &&
            playbackSessionManager.recordTransportReopen()
        ) {
            transientNetworkRetries++
            val plan = state.playbackPlan
            val transportMountNonce = nextTransportMountNonce()
            _uiState.update {
                it.copy(
                    error = null,
                    playbackPlan = plan.copy(
                        timeline = plan.timeline.copy(
                            playerStartSeconds = plan.timeline.playerPositionForSource(state.position)
                                ?: plan.timeline.playerStartSeconds,
                        ),
                        decisionTrace = plan.decisionTrace + "client_retry=transport_reopen",
                    ),
                    startPosition = plan.timeline.playerPositionForSource(state.position)
                        ?: plan.timeline.playerStartSeconds,
                    transportMountNonce = transportMountNonce,
                )
            }
            return
        }

        startProtocolV3Replan(reason.failureClassification(), notice, state)
    }

    private fun startProtocolV3Replan(
        classification: String,
        notice: String,
        state: UiState,
        qualityPreference: String? = null,
        diagnostics: Map<String, String> = emptyMap(),
    ) {
        if (recoveryJob?.isActive == true) return
        val fileId = state.selectedFileId ?: state.mediaFileId ?: return
        val recoveryContentGeneration = contentLoadGeneration
        recoveryJob = viewModelScope.launch {
            val selectedAudio = selectedServerAudioTrackIndex(
                selectedPlayerOrdinal = state.audioTracks.firstOrNull { it.isSelected }?.index,
                catalogAudioTracks = state.fileVersions.firstOrNull { it.fileId == fileId }?.audioTracks,
                currentPlanTrackIndex = state.playbackPlan?.selectedTracks?.audioIndex,
            )
            val selectedSubtitle = selectedSubtitleTrackIndex(state)
            val dolbyVision = playerSettingsStore.dolbyVisionPolicySnapshot()
            coroutineContext.ensureActive()
            if (recoveryContentGeneration != contentLoadGeneration) return@launch
            val capabilities = capabilityDetector.detect(dolbyVision = dolbyVision)
            val playbackContext = capabilityDetector.detectPlaybackContext(
                formFactor = "tv",
                appVersion = BuildConfig.VERSION_NAME,
                dolbyVision = dolbyVision,
            )
            val result = playbackSessionManager.replanActiveVideoSession(
                classification = classification,
                message = notice,
                positionSeconds = state.position,
                audioTrackIndex = selectedAudio,
                subtitleTrackIndex = selectedSubtitle,
                decoderName = state.stats.videoDecoderName ?: state.stats.audioDecoderName,
                diagnostics = diagnostics,
                qualityPreference = qualityPreference,
                capabilities = capabilities,
                clientPlaybackContext = playbackContext,
            )
            // PlaybackRepository's safe-call layer may translate cancellation to an ApiResult.
            // Re-check both coroutine and content generations before any response can adopt.
            coroutineContext.ensureActive()
            if (recoveryContentGeneration != contentLoadGeneration) return@launch
            when (result) {
                is ApiResult.Success -> when (val decision = result.data) {
                    is VideoSessionStartV3.Ready -> {
                        val effectiveFileId = decision.session.mediaFileId.takeIf { it > 0 }
                            ?: decision.plan.effectiveMediaFileId
                            ?: fileId
                        val effectiveVersion = state.fileVersions.firstOrNull {
                            it.fileId == effectiveFileId
                        }
                        val effectiveResolution = effectiveVersion?.resolution
                            ?: decision.plan.effectiveRecipe.height?.let { "${it}p" }
                        val plannedSubtitles = decision.session.subtitleUrls.orEmpty()
                        val plannedSubtitleIndexes = plannedSubtitles
                            .mapTo(mutableSetOf(), PlayerSubtitleInfo::index)
                        val preservedSubtitles = if (effectiveFileId == fileId) {
                            state.subtitleUrls.filterNot { it.index in plannedSubtitleIndexes }
                        } else {
                            emptyList()
                        }
                        val effectiveSubtitleUrls = buildPlaybackSubtitleChoices(
                            catalogTracks = effectiveVersion?.subtitleTracks.orEmpty(),
                            plannedTracks = plannedSubtitles + preservedSubtitles,
                        )
                        val effectiveContainer = decision.plan.stream.container
                            ?: effectiveVersion?.container
                            ?: state.container.takeIf { effectiveFileId == fileId }
                        val effectiveDuration = decision.session.durationSeconds
                            ?: effectiveVersion?.duration?.takeIf { it > 0.0 }
                            ?: state.duration.takeIf { effectiveFileId == fileId }
                            ?: 0.0
                        sessionLifecycle.adoptActiveSession(
                            params = StartParams(
                                contentId = contentId,
                                fileId = effectiveFileId,
                                capabilities = capabilities,
                                audioTrackIndex = decision.session.audioTrackIndex,
                                subtitleTrackIndex = selectedSubtitle,
                                startPosition = decision.session.position,
                            ),
                            session = decision.session,
                            renewMissingSessionWithLegacyStart = false,
                        )
                        coroutineContext.ensureActive()
                        if (recoveryContentGeneration != contentLoadGeneration) return@launch
                        val transportMountNonce = nextTransportMountNonce()
                        _uiState.update {
                            it.copy(
                                error = null,
                                sessionId = decision.session.sessionId,
                                playMethod = decision.session.playMethod,
                                playbackPlan = decision.session.playbackPlan,
                                delivery = decision.plan.delivery,
                                streamUrl = decision.plan.stream.url,
                                transportMountNonce = transportMountNonce,
                                requestHeaders = decision.plan.stream.headers,
                                selectedFileId = effectiveFileId,
                                mediaFileId = effectiveFileId,
                                selectedFileResolution = effectiveResolution,
                                container = effectiveContainer,
                                duration = effectiveDuration,
                                subtitleUrls = effectiveSubtitleUrls,
                                chapters = effectiveVersion?.chapters.orEmpty().ifEmpty {
                                    if (effectiveFileId == fileId) state.chapters else emptyList()
                                },
                                startPosition = decision.plan.timeline.playerStartSeconds,
                                position = decision.plan.timeline.sourceStartSeconds
                                    .takeIf { it.isFinite() && it >= 0.0 }
                                    ?: it.position,
                            )
                        }
                    }
                    is VideoSessionStartV3.Terminal -> _uiState.update {
                        it.copy(error = "Playback unavailable (${decision.reason}): ${decision.message}")
                    }
                    VideoSessionStartV3.ServerUpgradeRequired -> _uiState.update {
                        it.copy(error = "This Silo server must be updated to support playback recovery.")
                    }
                }
                is ApiResult.Error -> _uiState.update { it.copy(error = "$notice (${result.message})") }
                is ApiResult.NetworkError -> _uiState.update {
                    it.copy(error = "$notice (${result.exception.message})")
                }
            }
        }
    }

    private fun org.siloserver.silo.common.player.Playability.failureClassification(): String = when (this) {
        is org.siloserver.silo.common.player.Playability.UnsupportedDvProfile -> "unsupported_dolby_vision_profile"
        is org.siloserver.silo.common.player.Playability.UnsupportedAudioCodec -> "unsupported_audio_encoding"
        is org.siloserver.silo.common.player.Playability.UnsupportedChannelCount -> "unsupported_audio_layout"
        is org.siloserver.silo.common.player.Playability.StartupStalled -> classification
        org.siloserver.silo.common.player.Playability.Supported -> "none"
    }

    private fun androidx.media3.common.PlaybackException.failureClassification(): String =
        dolbyVisionTransformClassification()?.let { return it }
            ?: when (errorCode) {
        androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED,
        androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        -> "decoder_failure"
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        -> "transport_stall"
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "http_failure"
        androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "source_unavailable"
                else -> "player_failure"
            }

    private fun String?.toAudioMimeType(): String? = when (this?.trim()?.lowercase()) {
        "aac" -> androidx.media3.common.MimeTypes.AUDIO_AAC
        "ac3", "ac-3" -> androidx.media3.common.MimeTypes.AUDIO_AC3
        "eac3", "e-ac-3", "eac3_joc" -> androidx.media3.common.MimeTypes.AUDIO_E_AC3
        "truehd", "mlp" -> androidx.media3.common.MimeTypes.AUDIO_TRUEHD
        "dts" -> androidx.media3.common.MimeTypes.AUDIO_DTS
        "dts_hd", "dts-hd", "dtshd" -> androidx.media3.common.MimeTypes.AUDIO_DTS_HD
        "ac4", "ac-4" -> androidx.media3.common.MimeTypes.AUDIO_AC4
        "flac" -> androidx.media3.common.MimeTypes.AUDIO_FLAC
        "opus" -> androidx.media3.common.MimeTypes.AUDIO_OPUS
        else -> this?.takeIf { it.startsWith("audio/") }
    }

    private fun selectedSubtitleTrackIndex(state: UiState): Int? {
        // Only -1 (Off) when subtitles are GENUINELY off (no track selected).
        val selected = state.subtitleTracks.firstOrNull { it.isSelected } ?: return -1
        // A selected track that maps to a mounted server subtitle resolves to its
        // stable server index. A selected track with no mounted match (e.g. an
        // embedded CEA-608 the player discovered, not in the sidecar list) returns
        // null = keep-current, so a server-recovery transcode preserves the user's
        // subtitles instead of forcing them Off.
        return state.subtitleUrls
            .firstOrNull { selected.matchesMountedSubtitle(it) }
            ?.index
    }

    fun onPositionChanged(positionMs: Long, durationMs: Long) {
        if (positionMs < 0) return
        // A new recovery response updates the source/player timeline before Compose can run the
        // matching backend.mount effect. Reports from the old MediaItem must not be interpreted
        // through that new timeline during this handoff window.
        if (transportMountGate.suppressPositionReports) return

        val currentState = _uiState.value
        val timeline = currentState.playbackPlan?.timeline
        val knownSourceDuration = currentState.duration.takeIf { it > 0.0 }
        val rawPositionSec = positionMs / 1000.0
        val rawDurationSec = durationMs / 1000.0
        val mappedPositionSec = (timeline?.sourcePositionForPlayer(rawPositionSec) ?: rawPositionSec)
            .let { position -> knownSourceDuration?.let { position.coerceAtMost(it) } ?: position }
        val mappedDurationSec = if (durationMs > 0) {
            timeline?.sourcePositionForPlayer(rawDurationSec) ?: rawDurationSec
        } else {
            0.0
        }.let { duration -> knownSourceDuration?.let { duration.coerceAtMost(it) } ?: duration }
        val nowMs = SystemClock.elapsedRealtime()
        val positionDecision = seekPresentationGuard.onPositionReport(
            positionMs = (mappedPositionSec * 1_000.0).toLong().coerceAtLeast(0L),
            nowElapsedRealtimeMs = nowMs,
        )
        if (positionDecision is SeekPositionDecision.Suppress) return
        val positionSec = (positionDecision as SeekPositionDecision.Publish).positionMs / 1000.0
        val durationSec = mappedDurationSec
        val seekWasActive = activeSeekTargetSec != null
        activeSeekTargetSec?.let { target ->
            if (kotlin.math.abs(positionSec - target) <= 2.0 || nowMs - activeSeekStartedAtMs >= SEEK_SETTLE_DEADLINE_MS) {
                Log.i(TAG, "seek_settled seek_id=$activeSeekId target_source_seconds=$target actual_source_seconds=$positionSec")
                activeSeekTargetSec = null
                activeSeekId = null
                sameRouteSeekRecoveryAttempted = false
            }
        }
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
        if (!seekWasActive) {
            _uiState.value.credits?.start?.let { creditsStart ->
                if (previousPosition < creditsStart && positionSec >= creditsStart) onApproachingEnd()
            }
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
        if (!isPlaying) {
            maybeRecordPosition(_uiState.value.position, _uiState.value.duration, force = true)
        }
    }

    fun onFirstVideoFrameRendered() {
        hasRenderedFirstFrame = true
        playbackSessionManager.reportFirstVideoFrame(_uiState.value.stats)
    }

    fun onRuntimeCorrection(event: String, correctionId: String, stage: String, details: Map<String, String> = emptyMap()) {
        playbackSessionManager.reportActiveVideoEvent(
            event = event,
            diagnostics = details + mapOf("correction_id" to correctionId, "correction_stage" to stage),
        )
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
        cancelPendingQuickSkip()
        beginAndExecuteSeek(positionSec)
    }

    /** Coalesces rapid remote/button skips into one route-aware seek. */
    fun onSkipBy(deltaSeconds: Double): Double {
        val state = _uiState.value
        val nowMs = SystemClock.elapsedRealtime()
        if (quickSkipAccumulator.pending == null) {
            quickSkipOriginMs = (state.position * 1_000.0).toLong().coerceAtLeast(0L)
            activeSeekId = ++seekSequence
            sameRouteSeekRecoveryAttempted = false
        }
        val pending = quickSkipAccumulator.addSkip(
            deltaMs = (deltaSeconds * 1_000.0).toLong(),
            enginePositionMs = (state.position * 1_000.0).toLong().coerceAtLeast(0L),
            bounds = SeekBoundsMs(
                endPositionMs = state.duration.takeIf { it > 0.0 }
                    ?.let { (it * 1_000.0).toLong() },
            ),
            nowElapsedRealtimeMs = nowMs,
        )
        armSeekPresentation(quickSkipOriginMs, pending.targetPositionMs, nowMs)
        _uiState.update { it.copy(position = pending.targetPositionMs / 1_000.0) }
        quickSkipCommitJob?.cancel()
        quickSkipCommitJob = viewModelScope.launch {
            delay((pending.commitAtElapsedRealtimeMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            quickSkipAccumulator.commitIfDue(
                expectedGeneration = pending.generation,
                nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            )?.let { commit -> executeSeekTarget(commit.targetPositionMs / 1_000.0) }
        }
        return pending.targetPositionMs / 1_000.0
    }

    private fun cancelPendingQuickSkip() {
        quickSkipCommitJob?.cancel()
        quickSkipCommitJob = null
        quickSkipAccumulator.cancel()
    }

    private fun beginAndExecuteSeek(positionSec: Double) {
        val state = _uiState.value
        val target = positionSec
            .takeIf { it.isFinite() }
            ?.coerceAtLeast(0.0)
            ?.let { value -> if (state.duration > 0.0) value.coerceAtMost(state.duration) else value }
            ?: return
        val nowMs = SystemClock.elapsedRealtime()
        activeSeekId = ++seekSequence
        sameRouteSeekRecoveryAttempted = false
        armSeekPresentation(
            originSourceMs = (state.position * 1_000.0).toLong().coerceAtLeast(0L),
            targetSourceMs = (target * 1_000.0).toLong().coerceAtLeast(0L),
            nowMs = nowMs,
        )
        _uiState.update { it.copy(position = target) }
        executeSeekTarget(target)
    }

    private fun armSeekPresentation(originSourceMs: Long, targetSourceMs: Long, nowMs: Long) {
        seekPresentationGuard.begin(originSourceMs, targetSourceMs, nowMs)
        activeSeekTargetSec = targetSourceMs / 1_000.0
        activeSeekStartedAtMs = nowMs
        if (activeSeekId == null) activeSeekId = ++seekSequence
    }

    private fun executeSeekTarget(targetSourceSec: Double) {
        val state = _uiState.value
        if (transportMountGate.suppressPositionReports &&
            (state.sessionId == null || state.playbackPlan == null)
        ) {
            pendingNativeSeekAfterMount = targetSourceSec
            Log.i(
                TAG,
                "seek_commit seek_id=$activeSeekId action=queue_native_after_mount " +
                    "target_source_seconds=$targetSourceSec",
            )
            return
        }
        if (seekRecoveryQueue.hasInFlight || transportMountGate.suppressPositionReports) {
            Log.i(
                TAG,
                "seek_commit seek_id=$activeSeekId action=queue_server_reanchor " +
                    "target_source_seconds=$targetSourceSec reason=recovery_or_mount_pending",
            )
            enqueueSeekRecovery(
                TvSeekRecoveryOperation.Reanchor(
                    targetSourceSeconds = targetSourceSec,
                    reason = "recovery_or_mount_pending",
                ),
            )
            return
        }
        when (val decision = state.playbackPlan?.timeline?.decideSeek(targetSourceSec)) {
            is PlaybackSeekDecision.ServerReanchor -> {
                Log.i(
                    TAG,
                    "seek_commit seek_id=$activeSeekId action=server_reanchor " +
                        "target_source_seconds=$targetSourceSec reason=${decision.reason}",
                )
                startSeekReanchor(targetSourceSec, "${decision.reason}")
            }
            is PlaybackSeekDecision.NativeSeek -> {
                Log.i(
                    TAG,
                    "seek_commit seek_id=$activeSeekId action=native " +
                        "target_source_seconds=$targetSourceSec " +
                        "target_player_seconds=${decision.targetPlayerPositionSeconds}",
                )
                seekRequestChannel.trySend(decision.targetPlayerPositionSeconds)
            }
            null -> seekRequestChannel.trySend(targetSourceSec)
        }
    }

    private fun startSeekReanchor(
        targetSourceSec: Double,
        reason: String,
        rollbackAllowed: Boolean = true,
        diagnostics: Map<String, String> = emptyMap(),
    ) {
        enqueueSeekRecovery(
            TvSeekRecoveryOperation.Reanchor(
                targetSourceSeconds = targetSourceSec,
                reason = reason,
                rollbackAllowed = rollbackAllowed,
                diagnostics = diagnostics,
            ),
        )
    }

    private fun startSeekFailureRecovery(
        targetSourceSec: Double,
        classification: String,
        notice: String,
        diagnostics: Map<String, String> = emptyMap(),
    ) {
        enqueueSeekRecovery(
            TvSeekRecoveryOperation.Failure(
                targetSourceSeconds = targetSourceSec,
                classification = classification,
                notice = notice,
                diagnostics = diagnostics,
            ),
        )
    }

    private fun enqueueSeekRecovery(operation: TvSeekRecoveryOperation) {
        val before = _uiState.value
        before.selectedFileId ?: before.mediaFileId ?: return
        val seekId = activeSeekId ?: (++seekSequence).also { activeSeekId = it }
        sameRouteSeekRecoveryAttempted = true
        transportMountGate.beginLoad()
        _uiState.update { it.copy(isBuffering = true, error = null) }
        when (val submission = seekRecoveryQueue.submit(seekId, operation)) {
            is TvSeekRecoverySubmission.Start -> {
                seekRecoveryRollbackInvalidated =
                    operation is TvSeekRecoveryOperation.Reanchor && !operation.rollbackAllowed
                viewModelScope.launch { drainSeekRecoveryQueue(submission.request) }
            }
            TvSeekRecoverySubmission.Queued -> Log.i(
                TAG,
                "seek_recovery_queued seek_id=$seekId " +
                    "target_source_seconds=${operation.targetSourceSeconds}",
            )
        }
    }

    private suspend fun drainSeekRecoveryQueue(first: TvSeekRecoveryRequest) {
        var request: TvSeekRecoveryRequest? = first
        while (request != null) {
            try {
                runSeekRecoveryRequest(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.e(
                    TAG,
                    "Seek recovery failed unexpectedly for seek_id=${request.seekId}",
                    error,
                )
                handleSeekRecoveryFailure(
                    request,
                    error.localizedMessage?.takeIf(String::isNotBlank)
                        ?: "Unable to seek. Please try again.",
                )
            }
            request = seekRecoveryQueue.complete(request)
        }
    }

    private suspend fun runSeekRecoveryRequest(request: TvSeekRecoveryRequest) {
        val before = _uiState.value
        before.selectedFileId ?: before.mediaFileId ?: return
        when (val operation = request.operation) {
            is TvSeekRecoveryOperation.Reanchor -> {
                val operationDiagnostics = operation.diagnostics + mapOf(
                    "seek_id" to request.seekId.toString(),
                    "seek_reason" to operation.reason,
                )
                when (val result = playbackSessionManager.reanchorActiveVideoSession(
                    positionSeconds = operation.targetSourceSeconds,
                    diagnostics = operationDiagnostics,
                )) {
                    is ApiResult.Success -> {
                        if (!isCurrentSeekRecovery(request)) return
                        when (val decision = result.data) {
                            is VideoSessionStartV3.Ready -> adoptSeekRecoveryDecision(
                                request = request,
                                decision = decision,
                                before = before,
                                requestedSourcePosition = operation.targetSourceSeconds,
                            )
                            is VideoSessionStartV3.Terminal -> performPinnedSeekFailureRecovery(
                                request = request,
                                before = before,
                                classification = "seek_reanchor_terminal",
                                notice = decision.message,
                                diagnostics = operationDiagnostics + mapOf(
                                    "reanchor_terminal_reason" to decision.reason,
                                ),
                            )
                            VideoSessionStartV3.ServerUpgradeRequired -> handleSeekRecoveryFailure(
                                request,
                                "This Silo server does not support reliable seeking.",
                            )
                        }
                    }
                    is ApiResult.Error -> {
                        if (!isCurrentSeekRecovery(request)) return
                        if (result.error == "seek_reanchor_not_supported") {
                            handleSeekRecoveryFailure(
                                request,
                                "This Silo server does not support reliable seeking.",
                            )
                        } else {
                            performPinnedSeekFailureRecovery(
                                request = request,
                                before = before,
                                classification = "seek_reanchor_failed",
                                notice = result.message,
                                diagnostics = operationDiagnostics + mapOf("reanchor_error" to result.error),
                            )
                        }
                    }
                    is ApiResult.NetworkError -> {
                        if (!isCurrentSeekRecovery(request)) return
                        performPinnedSeekFailureRecovery(
                            request = request,
                            before = before,
                            classification = "seek_reanchor_network_failure",
                            notice = result.exception.message ?: "Seek re-anchor request failed.",
                            diagnostics = operationDiagnostics,
                        )
                    }
                }
            }
            is TvSeekRecoveryOperation.Failure -> performPinnedSeekFailureRecovery(
                request = request,
                before = before,
                classification = operation.classification,
                notice = operation.notice,
                diagnostics = operation.diagnostics + ("seek_id" to request.seekId.toString()),
            )
        }
    }

    private suspend fun performPinnedSeekFailureRecovery(
        request: TvSeekRecoveryRequest,
        before: UiState,
        classification: String,
        notice: String,
        diagnostics: Map<String, String>,
    ) {
        if (!isCurrentSeekRecovery(request)) return
        val targetSourceSec = request.operation.targetSourceSeconds
        when (val result = playbackSessionManager.recoverActiveVideoSessionAfterSeek(
            positionSeconds = targetSourceSec,
            classification = classification,
            message = notice,
            diagnostics = diagnostics,
        )) {
            is ApiResult.Success -> {
                if (!isCurrentSeekRecovery(request)) return
                when (val decision = result.data) {
                    is VideoSessionStartV3.Ready -> adoptSeekRecoveryDecision(
                        request = request,
                        decision = decision,
                        before = before,
                        requestedSourcePosition = targetSourceSec,
                    )
                    is VideoSessionStartV3.Terminal -> handleSeekRecoveryFailure(
                        request,
                        "Unable to seek (${decision.reason}): ${decision.message}",
                    )
                    VideoSessionStartV3.ServerUpgradeRequired -> handleSeekRecoveryFailure(
                        request,
                        "This Silo server does not support reliable seeking.",
                    )
                }
            }
            is ApiResult.Error -> {
                if (!isCurrentSeekRecovery(request)) return
                val message = if (result.error == "seek_reanchor_not_supported") {
                    "This Silo server does not support reliable seeking."
                } else {
                    "Unable to seek (${result.message})"
                }
                handleSeekRecoveryFailure(request, message)
            }
            is ApiResult.NetworkError -> {
                if (!isCurrentSeekRecovery(request)) return
                handleSeekRecoveryFailure(
                    request,
                    "Unable to seek (${result.exception.message})",
                )
            }
        }
    }

    private suspend fun adoptSeekRecoveryDecision(
        request: TvSeekRecoveryRequest,
        decision: VideoSessionStartV3.Ready,
        before: UiState,
        requestedSourcePosition: Double,
    ) {
        if (!isCurrentSeekRecovery(request)) return
        val fileId = before.selectedFileId ?: before.mediaFileId ?: return
        val expectedFileId = before.playbackPlan?.effectiveMediaFileId ?: fileId
        val actualFileId = decision.plan.effectiveMediaFileId ?: expectedFileId
        if (actualFileId != expectedFileId) {
            handleSeekRecoveryFailure(
                request,
                "Seek recovery tried to change the selected media version.",
            )
            return
        }
        val sourcePosition = decision.plan.timeline.sourceStartSeconds
            .takeIf { it.isFinite() && it >= 0.0 }
            ?: requestedSourcePosition
        seekRecoveryRollbackInvalidated = false
        val dolbyVision = playerSettingsStore.dolbyVisionPolicySnapshot()
        if (!isCurrentSeekRecovery(request)) return
        sessionLifecycle.adoptActiveSession(
            params = StartParams(
                contentId = contentId,
                fileId = fileId,
                capabilities = capabilityDetector.detect(dolbyVision = dolbyVision),
                audioTrackIndex = decision.session.audioTrackIndex,
                subtitleTrackIndex = selectedSubtitleTrackIndex(before),
                startPosition = sourcePosition,
            ),
            session = decision.session,
            renewMissingSessionWithLegacyStart = false,
        )
        if (!isCurrentSeekRecovery(request)) return
        val transportMountNonce = nextTransportMountNonce()
        _uiState.update {
            if (!isCurrentSeekRecovery(request)) return@update it
            it.copy(
                error = null,
                isBuffering = false,
                sessionId = decision.session.sessionId,
                playMethod = decision.session.playMethod,
                playbackPlan = decision.session.playbackPlan,
                delivery = decision.plan.delivery,
                streamUrl = decision.plan.stream.url,
                transportMountNonce = transportMountNonce,
                requestHeaders = decision.plan.stream.headers,
                container = decision.plan.stream.container ?: it.container,
                startPosition = decision.plan.timeline.playerStartSeconds,
                position = sourcePosition,
            )
        }
    }

    private fun isCurrentSeekRecovery(request: TvSeekRecoveryRequest): Boolean =
        seekRecoveryQueue.isCurrent(request, activeSeekId)

    private fun handleSeekRecoveryFailure(
        request: TvSeekRecoveryRequest,
        message: String,
    ) {
        if (!isCurrentSeekRecovery(request)) return
        val reanchor = request.operation as? TvSeekRecoveryOperation.Reanchor
        if (reanchor != null && reanchor.rollbackAllowed && !seekRecoveryRollbackInvalidated) {
            // Re-anchor requests are transactional while the old Media3 item
            // remains healthy. If neither exact nor pinned server recovery can
            // produce a replacement, cancel the optimistic playhead and keep
            // playing the mounted item instead of showing a fatal error.
            val rollback = seekPresentationGuard.cancel()?.originPositionMs
                ?.div(1_000.0)
                ?: _uiState.value.position
            activeSeekTargetSec = null
            activeSeekId = null
            sameRouteSeekRecoveryAttempted = false
            seekRecoveryRollbackInvalidated = false
            transportMountGate.reset()
            Log.w(TAG, "seek_recovery action=rollback message=$message")
            _uiState.update {
                it.copy(
                    position = rollback,
                    isBuffering = false,
                    error = null,
                )
            }
            return
        }
        _uiState.update { it.copy(isBuffering = false, error = message) }
    }

    private inline fun updateSeekRecoveryIfCurrent(
        request: TvSeekRecoveryRequest,
        transform: (UiState) -> UiState,
    ) {
        if (!isCurrentSeekRecovery(request)) return
        _uiState.update { state ->
            if (isCurrentSeekRecovery(request)) transform(state) else state
        }
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
        val state = _uiState.value
        val next = state.nextEpisode ?: return
        val selectedQuality = state.selectedFileResolution
        _uiState.update { it.copy(showNextUp = false, nextUpCountdownSeconds = null) }
        _playNextRequests.tryEmit(PlayNextRequest(next.contentId, nextAutoAdvanceCount, selectedQuality))
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
        // The detail-page explicit pick resolves FIRST so a resolved pick can
        // suppress the persisted/auto fallback (and an unresolvable one lets it
        // proceed) before persisted reads its fingerprint.
        resolvePendingInitialSubtitle(subtitle)
        resolvePendingPersistedTrackSelection(audio, subtitle)
        resolvePendingSubtitleSelection(subtitle)
        resolveAutoPreferredTextSubtitle(audio, subtitle)
    }

    fun onTracksChanged(
        audio: List<PlayerTrackEntry>,
        subtitle: List<PlayerTrackEntry>,
        video: List<PlayerTrackEntry>,
    ) {
        // videoQualities is the server-transcode ladder built at session load
        // (see loadContent) — NOT derived from the mounted adaptive variants, so
        // it is intentionally not touched here.
        _uiState.update {
            it.copy(
                audioTracks = audio,
                subtitleTracks = subtitle,
                videoTracks = video,
            )
        }
        // The detail-page explicit pick resolves FIRST so a resolved pick can
        // suppress the persisted/auto fallback (and an unresolvable one lets it
        // proceed) before persisted reads its fingerprint.
        resolvePendingInitialSubtitle(subtitle)
        resolvePendingPersistedTrackSelection(audio, subtitle)
        resolvePendingSubtitleSelection(subtitle)
        resolveAutoPreferredTextSubtitle(audio, subtitle)
    }

    private fun resolvePendingPersistedTrackSelection(
        audio: List<PlayerTrackEntry>,
        subtitle: List<PlayerTrackEntry>,
    ) {
        pendingPersistedAudioFingerprint?.let { fingerprint ->
            if (audio.isNotEmpty()) {
                pendingPersistedAudioFingerprint = null
                audio.firstOrNull { it.selectionFingerprint() == fingerprint }
                    ?.let { _pendingRemoteAudioIndex.value = it.index }
            }
        }

        pendingPersistedSubtitleFingerprint?.let { fingerprint ->
            if (fingerprint == SUBTITLE_OFF_FINGERPRINT) {
                pendingPersistedSubtitleFingerprint = null
                manualSubtitleSelectionApplied = true
                _subtitleSelectRequests.tryEmit(-1)
                return
            }
            if (subtitle.isEmpty()) return
            pendingPersistedSubtitleFingerprint = null
            // Saved subtitle choices are fingerprinted on the STABLE server
            // subtitle index (PlayerSubtitleInfo) — see persistSubtitleTrackSelection
            // — so resolve against the mounted list, then map the matched server
            // track onto the Media3 flat text ordinal SubtitleManager selects by.
            // Matching the flat PlayerTrackEntry fingerprint directly would never
            // restore, because that ordinal shifts as tracks are discovered.
            val mounted = resolveMountedSubtitleOrdinal(_uiState.value.subtitleUrls, fingerprint)
                ?.let { _uiState.value.subtitleUrls.getOrNull(it) }
                ?: return
            subtitle.firstOrNull { it.matchesMountedSubtitle(mounted) }
                ?.let {
                    manualSubtitleSelectionApplied = true
                    _subtitleSelectRequests.tryEmit(it.index)
                }
        }
    }

    private fun resolveAutoPreferredTextSubtitle(
        audio: List<PlayerTrackEntry>,
        subtitle: List<PlayerTrackEntry>,
    ) {
        // manualSubtitleSelectionApplied is set when a persisted choice OR a
        // RESOLVED explicit detail-page pick was applied — that (not the bare
        // launch intent) is what suppresses auto. An explicit pick that failed to
        // resolve leaves the flag clear, so auto still runs instead of stranding
        // subtitles Off.
        if (manualSubtitleSelectionApplied) return
        if (autoTextSubtitleSelectionAttempted) return
        if (subtitle.isEmpty()) return

        val state = _uiState.value
        val selection = resolveAutoSubtitleSelection(
            audioTracks = audio,
            subtitleTracks = subtitle,
            preferredLanguage = state.preferredTextLanguage,
            subtitleMode = state.preferredSubtitleMode,
            showForced = state.showForcedSubtitles,
        )
        autoTextSubtitleSelectionAttempted = true
        when (selection) {
            SubtitleAutoSelection.Disable -> _subtitleSelectRequests.tryEmit(-1)
            is SubtitleAutoSelection.Select -> _subtitleSelectRequests.tryEmit(selection.index)
            // Launch-time only: NoChange means Auto picked nothing, but Media3's
            // default selector may still have a track on — Apple's engines start
            // subs OFF, so the detail preview truthfully shows "Auto - None".
            // Disable explicitly so the launch state matches that preview.
            SubtitleAutoSelection.NoChange -> _subtitleSelectRequests.tryEmit(-1)
        }
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
            // An explicit Off from the detail page is a resolved decision: suppress
            // the persisted/auto fallback so it isn't overridden.
            manualSubtitleSelectionApplied = true
            pendingPersistedSubtitleFingerprint = null
            _subtitleSelectRequests.tryEmit(-1)
            return
        }
        // Wait for a non-empty track list. The pick is only CONSUMED when it
        // embedded tracks land (Media3 reports everything immediately), so the
        // first non-empty callback may not contain the picked sidecar yet.
        if (subtitle.isEmpty()) return
        val resolved = resolveInitialSubtitleTrackIndex(
            requestedOrdinal = index,
            subtitleTracks = subtitle,
            mountedSubtitles = _uiState.value.subtitleUrls,
        )
        // Suppress the persisted/auto fallback ONLY when the explicit pick actually
        // resolves onto a mounted track. An unresolvable pick leaves the persisted
        // fingerprint intact and the manual flag clear, so it falls through to
        // persisted -> auto instead of being silently dropped (subtitles Off all
        // session).
        if (resolved != null) {
            pendingInitialSubtitleIndex = null
            pendingInitialSubtitleAttempts = 0
            manualSubtitleSelectionApplied = true
            pendingPersistedSubtitleFingerprint = null
            _subtitleSelectRequests.tryEmit(resolved)
            return
        }
        // Bounded retry: keep the pick pending across a few callbacks so a
        // late-mounting sidecar can still honor it, then give up so we only
        // act during initial load (persisted/auto proceed as usual).
        pendingInitialSubtitleAttempts += 1
        if (pendingInitialSubtitleAttempts >= MAX_PENDING_INITIAL_SUBTITLE_ATTEMPTS) {
            pendingInitialSubtitleIndex = null
            pendingInitialSubtitleAttempts = 0
        }
    }

    fun onSubtitleSelectionApplied(index: Int) {
        _uiState.update {
            it.copy(subtitleTracks = subtitleTracksWithSelection(it.subtitleTracks, index))
        }
    }

    fun persistSubtitleSelection(index: Int) {
        persistSubtitleTrackSelection(index)
    }

    fun onAudioSelectionApplied(index: Int) {
        _uiState.update { current ->
            current.copy(audioTracks = current.audioTracks.map { it.copy(isSelected = it.index == index) })
        }
        val state = _uiState.value
        val fileId = state.selectedFileId ?: state.mediaFileId ?: return
        val fingerprint = state.audioTracks.firstOrNull { it.index == index }?.selectionFingerprint() ?: return
        viewModelScope.launch {
            userItemStatePort.recordAudioTrackSelection(contentId, fileId, fingerprint)
        }
        if (state.sessionId != null) {
            startProtocolV3Replan(
                classification = "audio_track_changed",
                notice = "Applying audio selection.",
                state = state,
            )
        }
    }

    private fun persistSubtitleTrackSelection(index: Int) {
        val state = _uiState.value
        val fileId = state.selectedFileId ?: state.mediaFileId ?: return
        val fingerprint = if (index == -1) {
            SUBTITLE_OFF_FINGERPRINT
        } else {
            // Fingerprint on the STABLE server subtitle index (PlayerSubtitleInfo),
            // not the Media3 flat text ordinal (PlayerTrackEntry.index) which shifts
            // as tracks are discovered — otherwise the saved choice never restores
            // across sessions (phone parity). Map the selected flat track onto its
            // mounted server subtitle before fingerprinting.
            val selected = state.subtitleTracks.firstOrNull { it.index == index } ?: return
            val mounted = state.subtitleUrls.firstOrNull { selected.matchesMountedSubtitle(it) } ?: return
            subtitleTrackFingerprint(mounted)
        }
        viewModelScope.launch {
            userItemStatePort.recordSubtitleTrackSelection(contentId, fileId, fingerprint)
        }
    }

    fun onManualSubtitleSelectionIntent(index: Int) {
        manualSubtitleSelectionApplied = true
        val state = _uiState.value
        if (state.sessionId != null) {
            startProtocolV3Replan(
                classification = "subtitle_track_changed",
                notice = "Applying subtitle selection.",
                state = state,
            )
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
                // Hiding chrome tears down the scrubber; drop any in-flight scrub
                // so the scrubber's blur-safety effect (cancelOnBlur=false) can't
                // auto-commit a stale seek the instant controls reopen. The
                // auto-hide timer is gated on !isScrubbing, so this only clears a
                // scrub the user is no longer actively dragging.
                isScrubbing = if (visible) it.isScrubbing else false,
                scrubPreviewSec = if (visible) it.scrubPreviewSec else 0.0,
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

    fun onVideoQualitySelectionApplied(resolution: String?) {
        _uiState.update { it.copy(selectedFileResolution = resolution) }
    }

    /**
     * Switch the in-player video quality (tvOS ApplePlaybackQuality parity): pin
     * a session-level [qualityOverride] and request a protocol-v3 replan at the
     * current position so the server transcodes to the chosen rung (or returns to
     * Auto/Original). [wireValue] is a [PlaybackQuality] wire value.
     */
    fun switchQuality(wireValue: String) {
        val current = qualityOverride ?: preferredQuality ?: PlaybackQuality.Auto.wireValue
        if (wireValue == current) return
        qualityOverride = wireValue
        _uiState.update {
            it.copy(videoQualities = transcodeQualityLadder(it.selectedFileResolution, wireValue))
        }
        val state = _uiState.value
        if (state.sessionId != null) {
            startProtocolV3Replan(
                classification = "quality_changed",
                notice = "Applying playback quality.",
                state = state,
                qualityPreference = wireValue,
            )
        }
    }

    /**
     * The server-transcode quality ladder for the current source: Auto + Original
     * always, plus each downscale rung whose height is below the source (never
     * offer an upscale). Wire values / labels come from [PlaybackQuality].
     */
    private fun transcodeQualityLadder(
        sourceResolution: String?,
        selectedWire: String,
    ): List<VideoQualityOption> {
        val sourceHeight = sourceResolution?.filter { it.isDigit() }?.toIntOrNull() ?: Int.MAX_VALUE
        val rungs = listOf(
            PlaybackQuality.P4K,
            PlaybackQuality.P1080,
            PlaybackQuality.P720,
            PlaybackQuality.P480,
        ).filter { tierHeight(it) < sourceHeight }
        return (listOf(PlaybackQuality.Auto, PlaybackQuality.Original) + rungs).map {
            VideoQualityOption(
                id = it.wireValue,
                label = it.label,
                isSelected = it.wireValue == selectedWire,
                resolution = it.wireValue,
            )
        }
    }

    private fun tierHeight(q: PlaybackQuality): Int = when (q) {
        PlaybackQuality.P4K -> 2160
        PlaybackQuality.P1080 -> 1080
        PlaybackQuality.P720 -> 720
        PlaybackQuality.P480 -> 480
        else -> Int.MAX_VALUE
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
        // Pre-write the resolved source position so the credits crossing check
        // treats this as a deliberate jump. The caller routes the actual seek
        // through either the room controller or seekImmediate; the latter owns
        // the pending-position guard for solo playback.
        _uiState.update { it.copy(position = intro.end) }
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

    /** Applies to track selection immediately; server-side routing (base
     *  layer vs DV delivery) follows at the next playback start. */
    fun onSetDolbyVisionEnabled(value: Boolean) {
        viewModelScope.launch { playerSettingsStore.setDolbyVisionEnabled(value) }
    }

    fun onSetSubtitleAppearance(value: SubtitleAppearance) {
        viewModelScope.launch { playerSettingsStore.setSubtitleAppearance(value) }
    }

    /**
     * HUD Audio pane stepper handler. Coerced to ±500ms in the store; the
     * service binding (E T3) picks up the new value and pushes it into the
     * shared [org.siloserver.silo.common.player.audio.DelayAudioProcessor]
     * (forcing a flush via `seekTo(currentPosition)` so the change applies
     * mid-playback).
     */
    fun onAudioDelayChanged(delayMs: Int) {
        viewModelScope.launch { playerSettingsStore.setAudioSyncMs(delayMs) }
    }

    /**
     * HUD Subtitles pane stepper handler. Coerced to ±500ms in the store; the
     * service binding (A.3f T2) picks up the new value and pushes it into the
     * shared [org.siloserver.silo.common.player.subtitle.SubtitleOffsetHolder]
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
        resetSeekRecoveryForContentChange()
        transportMountGate.reset()
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
        val pendingSeekTarget = activeSeekTargetSec
        if (pendingSeekTarget != null &&
            (seekRecoveryQueue.hasInFlight ||
                recoveryJob?.isActive == true ||
                transportMountGate.suppressPositionReports)
        ) {
            Log.i(
                TAG,
                "seek_recovery seek_id=$activeSeekId action=ignore_stale_player_error " +
                    "error=${error.errorCodeName}",
            )
            seekRecoveryRollbackInvalidated = true
            return
        }
        val isAudioSinkFailure = error.errorCode in setOf(
            androidx.media3.common.PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            androidx.media3.common.PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
            androidx.media3.common.PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_INIT_FAILED,
            androidx.media3.common.PlaybackException.ERROR_CODE_AUDIO_TRACK_OFFLOAD_WRITE_FAILED,
        )
        if (isAudioSinkFailure) {
            val selectedTrack = state.audioTracks.firstOrNull { it.isSelected }
            val mime = selectedTrack?.codecOrMime.toAudioMimeType()
            val plan = state.playbackPlan
            if (mime != null && plan != null &&
                playbackSessionManager.trySingleLocalPcmRetry(mime, selectedTrack?.channelCount ?: 0)
            ) {
                val transportMountNonce = nextTransportMountNonce()
                _uiState.update {
                    it.copy(
                        error = null,
                        playbackPlan = plan.copy(
                            timeline = plan.timeline.copy(
                                playerStartSeconds = plan.timeline.playerPositionForSource(state.position)
                                    ?: plan.timeline.playerStartSeconds,
                            ),
                            claims = plan.claims.copy(
                                audio = plan.claims.audio.copy(
                                    passthrough = false,
                                    reason = "client_pcm_retry",
                                ),
                            ),
                            decisionTrace = plan.decisionTrace + "client_retry=pcm_decode:$mime",
                        ),
                        startPosition = plan.timeline.playerPositionForSource(state.position)
                            ?: plan.timeline.playerStartSeconds,
                        transportMountNonce = transportMountNonce,
                    )
                }
                return
            }
        }
        if (state.sessionId != null && pendingSeekTarget != null &&
            hasRenderedFirstFrame &&
            !sameRouteSeekRecoveryAttempted && error.isSameRouteSeekReanchorCandidate()
        ) {
            sameRouteSeekRecoveryAttempted = true
            Log.w(
                TAG,
                "seek_recovery seek_id=$activeSeekId action=same_route_reanchor " +
                    "target_source_seconds=$pendingSeekTarget error=${error.errorCodeName}",
                error,
            )
            startSeekReanchor(
                targetSourceSec = pendingSeekTarget,
                reason = "player_error_same_route",
                rollbackAllowed = false,
                diagnostics = mapOf(
                    "error_code" to error.errorCode.toString(),
                    "error_code_name" to error.errorCodeName,
                    "error_cause" to (error.cause?.javaClass?.simpleName ?: "unknown"),
                ),
            )
            return
        }
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
            state.playbackPlan != null &&
            transientNetworkRetries < MAX_TRANSIENT_NETWORK_RETRIES &&
            playbackSessionManager.recordTransportReopen()
        ) {
            transientNetworkRetries++
            Log.i(TAG, "Transient network error; retrying same route ($transientNetworkRetries/$MAX_TRANSIENT_NETWORK_RETRIES)")
            val plan = state.playbackPlan
            val transportMountNonce = nextTransportMountNonce()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    error = null,
                    playbackPlan = plan.copy(
                        timeline = plan.timeline.copy(
                            playerStartSeconds = plan.timeline.playerPositionForSource(state.position)
                                ?: plan.timeline.playerStartSeconds,
                        ),
                        decisionTrace = plan.decisionTrace +
                            "client_retry=transient_network:$transientNetworkRetries",
                    ),
                    startPosition = plan.timeline.playerPositionForSource(state.position)
                        ?: plan.timeline.playerStartSeconds,
                    transportMountNonce = transportMountNonce,
                )
            }
            return
        }
        if (state.sessionId != null) {
            val diagnostics = mapOf(
                "error_code" to error.errorCode.toString(),
                "error_code_name" to error.errorCodeName,
                "error_cause" to (error.cause?.javaClass?.simpleName ?: "unknown"),
            )
            if (pendingSeekTarget != null) {
                startSeekFailureRecovery(
                    targetSourceSec = pendingSeekTarget,
                    classification = error.failureClassification(),
                    notice = message,
                    diagnostics = diagnostics,
                )
            } else {
                startProtocolV3Replan(
                    error.failureClassification(),
                    message,
                    state,
                    diagnostics = diagnostics,
                )
            }
            return
        }
        _uiState.update {
            it.copy(
                isLoading = false,
                error = message,
            )
        }
    }

    /**
     * In-player version switch (QA 2026-07-08 / tvOS parity): restart the
     * session on the chosen server file version at the current position.
     */
    fun onSelectFileVersion(fileId: Int) {
        val state = _uiState.value
        if (fileId == (state.selectedFileId ?: state.mediaFileId)) return
        if (state.fileVersions.none { it.fileId == fileId }) return
        resetSeekRecoveryForContentChange()
        transportMountGate.beginLoad()
        val resumeAt = state.position.takeIf { it > 0.0 }
        val staleSessionId = state.sessionId
        // Single-flight: supersede any in-flight switch/retry so two rapid picks
        // can't run concurrent load pipelines and orphan a server session. Cancelling
        // here only interrupts the pre-loadContent stopSession round-trip (loadContent
        // flips isLoading synchronously), so this never leaves isLoading stuck.
        versionSwitchJob?.cancel()
        versionSwitchJob = viewModelScope.launch {
            if (staleSessionId != null) {
                runCatching { playbackSessionManager.stopSession(staleSessionId) }
            }
            // stopSession's safeApiCall swallows the CancellationException a
            // superseding pick raises mid-round-trip, so re-check the job's
            // cancelled flag explicitly — otherwise this stale coroutine would
            // proceed into loadContent and race the pipeline that replaced it.
            coroutineContext.ensureActive()
            loadContent(
                startPositionOverride = resumeAt,
                preferredFileIdOverride = fileId,
                suppressResumeRewind = true,
            )
        }
    }

    /** Reload the current content from the last known position (error-screen retry). */
    fun retry() {
        resetSeekRecoveryForContentChange()
        transportMountGate.beginLoad()
        val resumeAt = _uiState.value.position.takeIf { it > 0.0 }
        val staleSessionId = _uiState.value.sessionId
        // Share the version-switch single-flight guard: retry also restarts the
        // session, so a retry and a version pick must not run competing pipelines.
        versionSwitchJob?.cancel()
        versionSwitchJob = viewModelScope.launch {
            // Stop the previous server session first so a retry can't orphan it
            // until timeout (loadContent's adoptActiveSession replaces local
            // reporter state but does not stop the old server session).
            if (staleSessionId != null) {
                runCatching { playbackSessionManager.stopSession(staleSessionId) }
            }
            // Same stale-coroutine guard as onSelectFileVersion: safeApiCall
            // eats the cancellation, so check the flag before loading.
            coroutineContext.ensureActive()
            // Retry resumes exactly where it failed — no skip-back nudge.
            loadContent(startPositionOverride = resumeAt, suppressResumeRewind = true)
        }
    }

    override fun onCleared() {
        org.siloserver.silo.common.player.ActivePlaybackFile.clear(
            _uiState.value.selectedFileId ?: _uiState.value.mediaFileId,
        )
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
            // androidx lifecycle 2.10 closes viewModelScope BEFORE onCleared, so a
            // launch here never runs and the server session leaks until timeout.
            // Unlike the resume-position write above (a brief local Room write),
            // stop() makes up to two HTTP calls — blocking main for those network
            // timeouts is an ANR. stopAsync launches the stop on the lifecycle's
            // own singleton scope under NonCancellable. Best-effort.
            sessionLifecycle.stopAsync()
        }
    }

}

private fun PlayerTrackEntry.selectionFingerprint(): String =
    trackSelectionFingerprint(
        index = index,
        language = language,
        codec = codecOrMime,
        title = label,
        forced = isForced,
    )
