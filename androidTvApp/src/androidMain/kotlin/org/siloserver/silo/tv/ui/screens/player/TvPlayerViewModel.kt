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
import org.siloserver.silo.common.player.FinalPlaybackPosition
import org.siloserver.silo.common.player.FinalPlaybackPositionWriter
import org.siloserver.silo.common.player.VideoSessionStartV3
import org.siloserver.silo.common.player.PlayerNotice
import org.siloserver.silo.common.player.PlayerStatsSnapshot
import org.siloserver.silo.common.player.SessionState
import org.siloserver.silo.common.player.SleepTimerController
import org.siloserver.silo.common.player.SleepTimerState
import org.siloserver.silo.common.player.StartParams
import org.siloserver.silo.common.player.MountedSubtitleTrack
import org.siloserver.silo.common.player.isBitmapSubtitleCodecOrMime
import org.siloserver.silo.common.player.resolveMountedSubtitle
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
import org.siloserver.silo.common.network.ServerReachabilityMonitor
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
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.buildPlaybackSubtitleChoices
import org.siloserver.silo.model.playback.mergeDownloadedSubtitles
import org.siloserver.silo.model.subtitles.SubtitleAiQuota
import org.siloserver.silo.model.subtitles.SubtitleAiStatus
import org.siloserver.silo.model.subtitles.SubtitleDownloadRequest
import org.siloserver.silo.model.subtitles.SubtitleResult
import org.siloserver.silo.model.subtitles.SubtitleSearchRequest
import org.siloserver.silo.model.subtitles.SubtitleTranslateRequest
import org.siloserver.silo.playback.SUBTITLE_OFF_FINGERPRINT
import org.siloserver.silo.playback.encodeSubtitleIdentityPreference
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.errorMessage
import org.siloserver.silo.playback.nextEpisodeAfter
import org.siloserver.silo.playback.resolveMountedSubtitleOrdinal
import org.siloserver.silo.playback.subtitleTrackFingerprint
import org.siloserver.silo.player.DolbyVisionPolicy
import org.siloserver.silo.repository.SubtitlesRepository
import org.siloserver.silo.repository.port.PlaybackWriteScope
import org.siloserver.silo.repository.port.TrackSelectionFingerprintUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
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
import kotlinx.coroutines.withContext

/**
 * Renderable audio or subtitle track pulled out of ExoPlayer's current
 * `Tracks` object. [index] is the ordinal position among groups of the same
 * type and is used as the index argument when calling
 * [org.siloserver.silo.common.player.AudioTrackManager.selectAudioTrack] or
 * [org.siloserver.silo.common.player.SubtitleManager.selectSubtitle].
 * [trackId] retains Media3's stable selector identity; [label] is presentation
 * metadata and [displayLabel] is the polished user-facing string.
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
    val trackId: String? = null,
)

internal fun selectedServerAudioTrackIndex(
    selectedPlayerOrdinal: Int?,
    catalogAudioTracks: List<AudioTrack>?,
    currentPlanTrackIndex: Int?,
): Int? = selectedPlayerOrdinal
    ?.let { catalogAudioTracks?.getOrNull(it)?.index }
    ?: currentPlanTrackIndex

private fun SubtitleIdentity.serverTrackIndexForTv(): Int = when (this) {
    SubtitleIdentity.Off -> -1
    is SubtitleIdentity.ServerSidecar -> serverIndex
    is SubtitleIdentity.ServerBurnIn -> serverIndex
    is SubtitleIdentity.Embedded -> serverIndex
    is SubtitleIdentity.Downloaded,
    is SubtitleIdentity.LocalMedia3,
    -> -1
}

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

    return resolveMountedSubtitleTrack(requested, subtitleTracks)?.index
}

internal fun resolveMountedSubtitleTrack(
    subtitle: PlayerSubtitleInfo,
    subtitleTracks: List<PlayerTrackEntry>,
): PlayerTrackEntry? {
    val match = resolveMountedSubtitle(
        subtitle,
        subtitleTracks.map(PlayerTrackEntry::toMountedSubtitleTrack),
    ) ?: return null
    return subtitleTracks.firstOrNull { it.index == match.track.index }
}

internal fun resolveMountedSubtitleRow(
    track: PlayerTrackEntry,
    subtitleTracks: List<PlayerTrackEntry>,
    mountedSubtitles: List<PlayerSubtitleInfo>,
): PlayerSubtitleInfo? =
    mountedSubtitles
        .filter { resolveMountedSubtitleTrack(it, subtitleTracks)?.index == track.index }
        .singleOrNull()

internal fun resolvedMountedSubtitleTrackIndexes(
    subtitleTracks: List<PlayerTrackEntry>,
    mountedSubtitles: List<PlayerSubtitleInfo>,
): Set<Int> =
    mountedSubtitles
        .mapNotNull { resolveMountedSubtitleTrack(it, subtitleTracks)?.index }
        .toSet()

private fun PlayerTrackEntry.toMountedSubtitleTrack(): MountedSubtitleTrack =
    MountedSubtitleTrack(
        index = index,
        trackId = trackId,
        label = label,
        language = language,
        codec = codecOrMime,
        forced = isForced,
        hearingImpaired = isHearingImpaired,
    )

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
    private val finalPlaybackPositionWriter: FinalPlaybackPositionWriter,
    // Next-episode resolution for auto-advance (F2).
    private val catalogRepository: org.siloserver.silo.repository.CatalogRepository,
    // Pre-play reachability gate (issue #33): drives Retry's fresh probe.
    private val serverReachabilityMonitor: ServerReachabilityMonitor,
    private val launchArgs: TvPlayerLaunchArgs,
) : ViewModel() {

    companion object {
        private const val TAG = "TvPlayerViewModel"
        const val SERVER_UNREACHABLE_MESSAGE =
            "Can't reach server — check your connection."
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
    private var finalPositionScope: PlaybackWriteScope? = null

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

    private data class QueuedRecoveryReplan(
        val classification: String,
        val notice: String,
        val qualityPreference: String?,
        val subtitleTrackIndexOverride: Int?,
    )

    /**
     * Latest user track/quality/route change that arrived while [recoveryJob]
     * held the replan single-flight guard. Re-driven against the then-current
     * UiState once that flight completes so the selection isn't silently
     * dropped; last-write-wins because only the newest selection matters.
     */
    private var queuedRecoveryReplan: QueuedRecoveryReplan? = null

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
    private val loadOwners = TvPlayerLoadOwnerRegistry()

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
        /**
         * Distinct "Can't reach server" state (issue #33): when true, [error]
         * carries the reachability message and the error screen offers Retry
         * (fresh probe + reload) plus Try Anyway, rather than a generic failure.
         */
        val serverUnreachable: Boolean = false,
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
        /**
         * Server-normalized output frame rate. Media3 can report
         * [androidx.media3.common.Format.NO_VALUE] for transformed Dolby Vision
         * streams even though protocol v3 already knows the exact source rate.
         */
        val effectiveFrameRate: Float? = null,
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
        // Server-declared source runtime (0 when the server didn't provide one).
        // Authoritative ceiling for engine position/duration reports; unlike
        // [duration] it is never touched by player callbacks, so an in-progress
        // transcode's short window can't shrink it.
        val serverDuration: Double = 0.0,
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
        val committedSubtitleIdentity: SubtitleIdentity = SubtitleIdentity.Off,
        val pendingSubtitleIdentity: SubtitleIdentity? = null,
        val subtitleApplying: Boolean = false,
        val subtitleFailureMessage: String? = null,
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
    val presentationState: StateFlow<UiState> = uiState
        .map(UiState::withoutPlaybackClock)
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = _uiState.value.withoutPlaybackClock(),
        )
    val playbackClock: StateFlow<PlaybackClock> = uiState
        .map(UiState::toPlaybackClock)
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = _uiState.value.toPlaybackClock(),
        )
    private var subtitleMountGeneration = 0L
    private var pendingSubtitleMountAcknowledgement: TvSubtitleRemountOwner? = null
    private var lastAdapterMountIdentity: SubtitleIdentity? = null
    private val unpublishedSubtitleUi = mutableMapOf<String, UiState>()
    private val unpublishedTvLoadUi =
        TvUnpublishedLoadUiOwnership<UiState, TvSubtitlePlaybackContext>()

    private val subtitleTransactions = TvSubtitleTransactionAdapter(
        scope = viewModelScope,
        stagedPort = PlaybackSessionManagerTvSubtitleStagedReplanPort(
            playbackSessionManager,
            sessionLifecycle,
        ),
        settlementScope = TvSubtitleSettlementOwner.scope,
        persistencePort = object : TvSubtitlePersistencePort {
            override suspend fun persist(
                committed: org.siloserver.silo.model.playback.CommittedSubtitle,
                context: TvSubtitlePlaybackContext,
            ): Boolean {
                val writeScope = context.writeScope ?: return false
                return userItemStatePort.recordTrackSelection(
                    scope = writeScope,
                    contentId = context.contentId,
                    fileId = context.mediaFileId,
                    audioUpdate = tvAudioTrackPersistenceUpdate(
                        committedAudioTrackIndex = committed.audioTrackIndex,
                        audioTracks = context.audioTracks,
                    ),
                    subtitleUpdate = TrackSelectionFingerprintUpdate.Set(
                        encodeSubtitleIdentityPreference(committed.identity),
                    ),
                )
            }
        },
        onSnapshotChanged = { snapshot ->
            val localMountIdentity = snapshot.localMountIdentity
            if (localMountIdentity != null && localMountIdentity != lastAdapterMountIdentity) {
                subtitleMountGeneration += 1
                subtitleRemountReselection.arm(localMountIdentity, subtitleMountGeneration)
                subtitleSnapshotSettlement.reset()
                lastAdapterMountIdentity = localMountIdentity
                // In-stream captions can already be present and need no media
                // rebuild, so settle them against the current snapshot now.
                _uiState.value.subtitleTracks
                    .takeIf(List<PlayerTrackEntry>::isNotEmpty)
                    ?.let(::resolveSubtitleRemountReselection)
            } else if (localMountIdentity == null) {
                lastAdapterMountIdentity = null
            }
            val committedQuality = snapshot.transition.committed.qualityPreference
            if (!snapshot.subtitleApplying && committedQuality != null) {
                qualityOverride = committedQuality
            }
            _uiState.update { state ->
                state.copy(
                    committedSubtitleIdentity = snapshot.committedIdentity,
                    pendingSubtitleIdentity = snapshot.pendingIdentity,
                    subtitleApplying = snapshot.subtitleApplying,
                    subtitleFailureMessage = snapshot.failureMessage,
                    subtitleUrls = authoritativeTvSubtitleRows(
                        snapshotRows = snapshot.subtitleTracks,
                        previousRows = state.subtitleUrls,
                    ),
                    subtitleRefreshNonce = snapshot.subtitleRefreshNonce
                        .coerceAtMost(Int.MAX_VALUE.toLong())
                        .toInt(),
                    videoQualities = if (!snapshot.subtitleApplying && committedQuality != null) {
                        transcodeQualityLadder(state.selectedFileResolution, committedQuality)
                    } else {
                        state.videoQualities
                    },
                )
            }
        },
        onCommittedPlayback = ::adoptSubtitlePlayback,
        onCommittedPlaybackConfirmed = ::confirmSubtitlePlaybackPublication,
        onCommittedPlaybackRollback = ::rollbackSubtitlePlaybackPublication,
        onCommittedPlaybackFailure = { message ->
            _uiState.update { it.copy(error = message) }
        },
        hasMountableTracks = { _uiState.value.subtitleTracks.isNotEmpty() },
        isLocallyMountable = { identity ->
            resolveMountedSubtitle(
                identity = identity,
                tracks = _uiState.value.subtitleTracks.map { it.toMountedTvSubtitleTrack() },
            ) != null
        },
    )
    private val playbackMutationFence by lazy {
        TvPlayerMutationFence(loadOwners, subtitleTransactions::invalidate)
    }

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
    private val dvProfile7Hdr10Fallback: StateFlow<Boolean> =
        playerSettingsStore.dvProfile7HDR10FallbackFlow
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)
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
     * Per-device subtitle delay in ms, ±10000 clamp. Sourced from
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
    private val subtitleRemountReselection = SubtitleRemountReselection()
    private val subtitleSnapshotSettlement = TvSubtitleSnapshotSettlementTracker()

    init {
        // Keep the process-wide active-file marker in sync (phone parity), so
        // Reclaim Watched never deletes bytes under a live player.
        viewModelScope.launch {
            _uiState
                .map { it.selectedFileId ?: it.mediaFileId }
                .distinctUntilChanged()
                .collect { org.siloserver.silo.common.player.ActivePlaybackFile.set(it) }
        }
        // Mirror the screen error into the adb test hook — screen-level
        // failures (terminal server plans) never reach the Media3 player, so
        // scripted tests can't see them through player state alone.
        viewModelScope.launch {
            _uiState
                .map { it.error }
                .distinctUntilChanged()
                .collect { org.siloserver.silo.common.player.debug.PlaybackDebugState.screenError = it }
        }
        // Mirror the screen's position/duration too — the scrubber renders
        // from uiState, which can legitimately disagree with the raw player
        // (growing transcode manifests), so tests must see this view of it.
        viewModelScope.launch {
            _uiState
                .map { it.position to it.duration }
                .distinctUntilChanged()
                .collect { (position, duration) ->
                    org.siloserver.silo.common.player.debug.PlaybackDebugState.screenPositionSec = position
                    org.siloserver.silo.common.player.debug.PlaybackDebugState.screenDurationSec = duration
                }
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
                    playbackMutationFence.beginReplan()
                    subtitleTransactions.updatePlaybackContext(subtitlePlaybackContext(state))
                    subtitleTransactions.updateOutputRouteGeneration(
                        capabilityDetector.outputRouteGeneration.value,
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

    private fun nextTransportMountNonce(subtitleServerIndexToRestore: Int?): Long {
        // Media3 does not carry a live text-track override across MediaItem
        // replacement. Every same-content remount declares the stable server
        // subtitle index to restore; the first mount explicitly passes null.
        transportMountSequence = if (transportMountSequence == Long.MAX_VALUE) {
            1L
        } else {
            transportMountSequence + 1L
        }
        subtitleServerIndexToRestore?.let { serverIndex ->
            subtitleSnapshotSettlement.reset()
            subtitleRemountReselection.arm(
                identity = if (serverIndex == -1) {
                    SubtitleIdentity.Off
                } else {
                    SubtitleIdentity.ServerSidecar(serverIndex)
                },
                generation = transportMountSequence,
            )
        }
        transportMountGate.expect(transportMountSequence)
        return transportMountSequence
    }

    private fun nextTypedSubtitleMountNonce(identity: SubtitleIdentity): Long {
        transportMountSequence = if (transportMountSequence == Long.MAX_VALUE) {
            1L
        } else {
            transportMountSequence + 1L
        }
        subtitleMountGeneration += 1
        if (subtitleRemountReselection.requiresRemount(identity)) {
            subtitleSnapshotSettlement.reset()
            subtitleRemountReselection.arm(identity, subtitleMountGeneration)
        }
        transportMountGate.expect(transportMountSequence)
        return transportMountSequence
    }

    private fun subtitlePlaybackContext(state: UiState): TvSubtitlePlaybackContext {
        val fileId = state.selectedFileId ?: state.mediaFileId ?: 0
        val version = state.fileVersions.firstOrNull { it.fileId == fileId }
        val selectedAudio = selectedServerAudioTrackIndex(
            selectedPlayerOrdinal = state.audioTracks.firstOrNull { it.isSelected }?.index,
            catalogAudioTracks = version?.audioTracks,
            currentPlanTrackIndex = state.playbackPlan?.selectedTracks?.audioIndex,
        )
        val dolbyVision = DolbyVisionPolicy.Snapshot(
            dolbyVisionEnabled = dolbyVisionEnabled.value,
            preferProfile7HDR10Fallback = dvProfile7Hdr10Fallback.value,
        )
        return TvSubtitlePlaybackContext(
            contentId = contentId,
            mediaFileId = fileId,
            versionId = "$fileId:${state.playbackPlan?.planId.orEmpty()}",
            sessionId = state.sessionId,
            positionSeconds = state.position,
            audioTrackIndex = selectedAudio,
            qualityPreference = qualityOverride
                ?: preferredQuality
                ?: PlaybackQuality.Auto.wireValue,
            subtitleTracks = state.subtitleUrls,
            audioTracks = version?.audioTracks.orEmpty(),
            outputRouteGeneration = capabilityDetector.outputRouteGeneration.value,
            capabilities = capabilityDetector.detect(
                dolbyVision = dolbyVision,
            ),
            clientPlaybackContext = capabilityDetector.detectPlaybackContext(
                formFactor = "tv",
                appVersion = BuildConfig.VERSION_NAME,
                dolbyVision = dolbyVision,
            ),
            writeScope = finalPositionScope,
        )
    }

    private suspend fun adoptSubtitlePlayback(
        adoption: TvSubtitlePlaybackAdoption,
    ): TvSubtitleAdoptionResult {
        val ready = adoption.playback.ready ?: return TvSubtitleAdoptionResult.Superseded
        if (!adoption.isCurrent()) return TvSubtitleAdoptionResult.Superseded
        val before = _uiState.value
        val fileId = ready.session.mediaFileId.takeIf { it > 0 }
            ?: ready.plan.effectiveMediaFileId
            ?: before.selectedFileId
            ?: before.mediaFileId
            ?: return TvSubtitleAdoptionResult.Superseded
        val version = before.fileVersions.firstOrNull { it.fileId == fileId }
        val dolbyVision = playerSettingsStore.dolbyVisionPolicySnapshot()
        val capabilities = capabilityDetector.detect(dolbyVision = dolbyVision)
        val adopted = sessionLifecycle.adoptActiveSessionIfCurrent(
            params = StartParams(
                contentId = contentId,
                fileId = fileId,
                capabilities = capabilities,
                audioTrackIndex = adoption.committed.audioTrackIndex,
                subtitleTrackIndex = adoption.committed.identity.serverTrackIndexForTv(),
                qualityPreference = adoption.committed.qualityPreference,
                startPosition = ready.session.position,
            ),
            session = ready.session,
            renewMissingSessionWithLegacyStart = false,
            deferPublication = true,
            isCurrent = adoption::isCurrent,
        )
        if (!adopted) return TvSubtitleAdoptionResult.Superseded
        if (!adoption.isCurrent()) return TvSubtitleAdoptionResult.Superseded
        unpublishedSubtitleUi[ready.session.sessionId] = before

        val planned = ready.session.subtitleUrls.orEmpty()
        val plannedIndexes = planned.mapTo(mutableSetOf(), PlayerSubtitleInfo::index)
        val retained = if (fileId == (before.selectedFileId ?: before.mediaFileId)) {
            before.subtitleUrls.filterNot { it.index in plannedIndexes }
        } else {
            emptyList()
        }
        val subtitleUrls = buildPlaybackSubtitleChoices(
            catalogTracks = version?.subtitleTracks.orEmpty(),
            plannedTracks = planned + retained,
        )
        val duration = ready.session.durationSeconds
            ?: version?.duration?.takeIf { it > 0.0 }
            ?: before.duration
        val mountNonce = nextTypedSubtitleMountNonce(adoption.committed.identity)
        _uiState.update { state ->
            state.copy(
                error = null,
                sessionId = ready.session.sessionId,
                playMethod = ready.session.playMethod,
                playbackPlan = ready.session.playbackPlan,
                delivery = ready.plan.delivery,
                streamUrl = ready.plan.stream.url,
                transportMountNonce = mountNonce,
                requestHeaders = ready.plan.stream.headers,
                selectedFileId = fileId,
                mediaFileId = fileId,
                selectedFileResolution = version?.resolution
                    ?: ready.plan.effectiveRecipe.height?.let { "${it}p" },
                container = ready.plan.stream.container ?: version?.container ?: state.container,
                duration = duration,
                serverDuration = duration,
                subtitleUrls = subtitleUrls,
                chapters = version?.chapters.orEmpty(),
                startPosition = ready.plan.timeline.playerStartSeconds,
                position = ready.plan.timeline.sourceStartSeconds
                    .takeIf { it.isFinite() && it >= 0.0 }
                    ?: state.position,
            )
        }
        return TvSubtitleAdoptionResult.Adopted
    }

    private suspend fun confirmSubtitlePlaybackPublication(
        playback: TvSubtitleCommittedPlayback,
    ): Boolean {
        unpublishedSubtitleUi.remove(playback.sessionId)
        return true
    }

    private suspend fun rollbackSubtitlePlaybackPublication(
        playback: TvSubtitleCommittedPlayback,
        restoreUi: Boolean,
    ): Boolean {
        val predecessor = unpublishedSubtitleUi.remove(playback.sessionId)
        if (restoreUi && predecessor != null) {
            val identity = predecessor.committedSubtitleIdentity
            _uiState.value = predecessor.copy(
                transportMountNonce = nextTypedSubtitleMountNonce(identity),
            )
        }
        return true
    }

    private suspend fun rollbackUnpublishedTvLoadSession(sessionId: String) {
        val predecessor = unpublishedTvLoadUi.snapshotForRollback(sessionId)
        val jointlyRolledBack = sessionLifecycle.settlePendingPublicationIfCurrent(
            sessionId = sessionId,
            confirm = false,
            settleManager = {
                playbackSessionManager.rollbackUnpublishedVideoSession(sessionId)
            },
        )
        if (!jointlyRolledBack) {
            playbackSessionManager.rollbackUnpublishedVideoSession(sessionId)
        }
        try {
            if (predecessor != null && _uiState.value.sessionId == sessionId) {
                val identity = predecessor.state.committedSubtitleIdentity
                _uiState.value = predecessor.state.copy(
                    transportMountNonce = nextTypedSubtitleMountNonce(identity),
                )
                subtitleTransactions.resetContent(
                    context = predecessor.context,
                    committedIdentity = identity,
                )
            }
        } finally {
            unpublishedTvLoadUi.completeRollback(sessionId)
        }
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
        queuedRecoveryReplan = null
        // A budget exhausted on the previous content/version must not leak
        // into the next one (phone parity: resetPlaybackRecoveryState).
        transientNetworkRetries = 0
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
        // Try Anyway escape hatch (issue #33): bypass the pre-play reachability
        // gate and attempt the server even while it reports unreachable.
        force: Boolean = false,
        // Version replacement is transactional: keep the mounted version
        // visible until the replacement has won ownership and is ready.
        preserveCurrentPlaybackOnFailure: Boolean = false,
    ) {
        // Capture this pipeline's generation; a later loadContent bump makes
        // this one inert before it can touch _uiState.
        val generation = ++contentLoadGeneration
        val loadOwner = playbackMutationFence.beginLoad(
            contentId = contentId,
            preferredFileId = preferredFileIdOverride ?: preferredFileId,
            preferredQuality = qualityOverride ?: preferredQuality,
        )
        hasRenderedFirstFrame = false
        resetSeekRecoveryForContentChange()
        transportMountGate.beginLoad()
        introAutoSkipController.reset()
        manualSubtitleSelectionApplied = false
        _uiState.update { it.copy(isBuffering = false) }

        _uiState.update {
            if (preserveCurrentPlaybackOnFailure) beginTvReplacementLoad(it)
            else it.copy(isLoading = true, error = null, serverUnreachable = false)
        }
        finalPositionScope = null
        viewModelScope.launch {
            finalPositionScope = finalPlaybackPositionWriter.captureScope()
            val unpublishedReadySession =
                TvUnpublishedLoadSessionOwnership(::rollbackUnpublishedTvLoadSession)
            try {
                if (!subtitleTransactions.invalidateAndAwaitSettlement()) return@launch
                runCatching { playerSettingsStore.refreshFromServer() }
                if (!loadOwners.owns(loadOwner)) return@launch
                val request = VideoPlaybackStartRequest(
                        contentId = contentId,
                        preferredFileId = preferredFileIdOverride ?: preferredFileId,
                        roomId = roomId,
                        resumePositionOverride = startPositionOverride,
                        audioTrackIndex = initialAudioTrackIndex,
                        subtitleTrackIndex = pendingInitialSubtitleIndex,
                        preferredQualityOverride = preferredQuality,
                        playbackQualityIntent = qualityOverride,
                        suppressResumeRewind = suppressResumeRewind,
                        force = force,
                    )
                val result = loadOwners.withOwner(loadOwner) {
                    videoPlaybackCoordinator.start(request)
                }
                // A newer loadContent superseded this pipeline while start()
                // was in flight — its results must not clobber the newer
                // pipeline's session state.
                if (generation != contentLoadGeneration && result !is VideoPlayerUiState.Ready) {
                    return@launch
                }
                when (result) {
                    is VideoPlayerUiState.Ready -> {
                        val allocatedSessionId = result.sessionId
                            ?.takeIf(String::isNotBlank)
                            ?: run {
                                fail("Playback start returned no session.")
                                return@launch
                            }
                        unpublishedReadySession.acquire(allocatedSessionId)
                        if (!loadOwners.owns(loadOwner)) {
                            loadOwners.publishReadyIfOwned(
                                owner = loadOwner,
                                sessionId = allocatedSessionId,
                                publish = {},
                                stopStaleSession = unpublishedReadySession::rollbackIfOwned,
                            )
                            return@launch
                        }
                        val localTrackSelection = result.fileId
                            ?.let { fileId -> userItemStatePort.localTrackSelection(contentId, fileId) }
                        if (!loadOwners.owns(loadOwner)) {
                            loadOwners.publishReadyIfOwned(
                                owner = loadOwner,
                                sessionId = allocatedSessionId,
                                publish = {},
                                stopStaleSession = unpublishedReadySession::rollbackIfOwned,
                            )
                            return@launch
                        }
                        val readyMediaFileId = result.mediaFileId
                        val readySessionId = result.sessionId
                        val catalogSubtitleTracks = result.versions
                            .firstOrNull { it.fileId == (result.fileId ?: readyMediaFileId) }
                            ?.subtitleTracks
                            .orEmpty()
                        val restorePreference = if (pendingInitialSubtitleIndex == null) {
                            localTrackSelection?.subtitleFingerprint
                        } else {
                            null
                        }
                        val freshRestore = if (
                            readyMediaFileId != null &&
                            readySessionId != null
                        ) {
                            resolveOwnedTvFreshSubtitleRestore(
                                owner = loadOwner,
                                registry = loadOwners,
                                preference = restorePreference,
                                catalogTracks = catalogSubtitleTracks,
                                initialRows = result.subtitleUrls,
                                sessionId = readySessionId,
                                serverUrl = result.serverUrl,
                                hydrateDownloadedRows = {
                                    when (val listing = subtitlesRepository.list(readyMediaFileId)) {
                                        is ApiResult.Success -> ApiResult.Success(
                                            mergeDownloadedSubtitles(
                                                existing = emptyList(),
                                                downloaded = listing.data.subtitles,
                                                sessionId = readySessionId,
                                                serverUrl = result.serverUrl,
                                            ),
                                        )
                                        is ApiResult.Error -> listing
                                        is ApiResult.NetworkError -> listing
                                    }
                                },
                            )
                        } else {
                            TvFreshSubtitleRestoreResult(
                                rows = result.subtitleUrls,
                                resolution = resolveTvFreshSubtitlePreference(
                                    preference = restorePreference,
                                    catalogTracks = catalogSubtitleTracks,
                                    hydratedRows = result.subtitleUrls,
                                ),
                            )
                        }
                        if (freshRestore == null || !loadOwners.owns(loadOwner)) {
                            loadOwners.publishReadyIfOwned(
                                owner = loadOwner,
                                sessionId = allocatedSessionId,
                                publish = {},
                                stopStaleSession = unpublishedReadySession::rollbackIfOwned,
                            )
                            return@launch
                        }
                        val hydratedSubtitleUrls = freshRestore.rows
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
                        pendingPersistedSubtitleFingerprint = null
                        val committedIdentity = result.playbackPlan
                            ?.selectedTracks
                            ?.subtitleIndex
                            ?.let { selected ->
                                hydratedSubtitleUrls.firstOrNull { it.index == selected }
                            }
                            ?.let(::tvSubtitleIdentity)
                            ?: SubtitleIdentity.Off
                        val predecessorUi = _uiState.value
                        val predecessorSubtitleContext = subtitlePlaybackContext(predecessorUi)
                        val published = loadOwners.publishReadyIfOwned(
                            owner = loadOwner,
                            sessionId = allocatedSessionId,
                            publish = {
                                unpublishedTvLoadUi.register(
                                    sessionId = allocatedSessionId,
                                    state = predecessorUi,
                                    context = predecessorSubtitleContext,
                                    predecessorSessionId = predecessorUi.sessionId,
                                )
                                val transportMountNonce = nextTransportMountNonce(null)
                                _uiState.update {
                                    it.copy(
                                isLoading = false,
                                error = null,
                                title = result.title,
                                artworkUrl = result.artworkUrl,
                                sessionId = result.sessionId,
                                playMethod = result.playMethod,
                                playbackPlan = result.playbackPlan,
                                effectiveFrameRate = result.playbackPlanV3
                                    ?.effectiveRecipe
                                    ?.frameRate
                                    ?.takeIf { frameRate -> frameRate.isFinite() && frameRate > 0.0 }
                                    ?.toFloat(),
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
                                serverDuration = result.durationSeconds,
                                isPaused = false,
                                subtitleUrls = hydratedSubtitleUrls,
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
                                subtitleTransactions.resetContent(
                                    context = subtitlePlaybackContext(_uiState.value),
                                    committedIdentity = committedIdentity,
                                )
                                freshRestore.resolution?.let { resolution ->
                                    subtitleTransactions.restoreFreshPreference(
                                        identity = resolution.identity,
                                        migrationRequired = resolution.migratedPreference != null,
                                    )
                                }
                            },
                            stopStaleSession = unpublishedReadySession::rollbackIfOwned,
                        )
                        if (!published) return@launch
                        val publishedSessionId = allocatedSessionId
                        val jointlyConfirmed = withContext(NonCancellable) {
                            sessionLifecycle.settlePendingPublicationIfCurrent(
                                sessionId = publishedSessionId,
                                confirm = true,
                                settleManager = {
                                    playbackSessionManager
                                        .confirmVideoSessionPublication(publishedSessionId)
                                },
                            ).also { confirmed ->
                                if (confirmed) {
                                    check(
                                        unpublishedReadySession
                                            .transferConfirmed(publishedSessionId),
                                    )
                                    unpublishedTvLoadUi.confirm(publishedSessionId)
                                }
                            }
                        }
                        if (!jointlyConfirmed) {
                            unpublishedReadySession.rollbackIfOwned(publishedSessionId)
                            fail("Playback publication could not be confirmed.")
                            return@launch
                        }
                        startIntroAutoSkipObserver()
                        resolveNextEpisode()
                    }
                    is VideoPlayerUiState.Error -> {
                        if (preserveCurrentPlaybackOnFailure) {
                            _uiState.update { failTvReplacementLoad(it, result.message) }
                        } else {
                            fail(result.message)
                        }
                    }
                    is VideoPlayerUiState.ServerUnreachable -> _uiState.update {
                        if (preserveCurrentPlaybackOnFailure) {
                            failTvReplacementLoad(it, SERVER_UNREACHABLE_MESSAGE)
                        } else {
                            it.copy(
                                isLoading = false,
                                error = SERVER_UNREACHABLE_MESSAGE,
                                serverUnreachable = true,
                            )
                        }
                    }
                    is VideoPlayerUiState.Loading -> Unit
                }
            } catch (cancellation: CancellationException) {
                unpublishedReadySession.rollbackIfOwned()
                throw cancellation
            } catch (e: Exception) {
                unpublishedReadySession.rollbackIfOwned()
                Log.e(TAG, "Error loading content", e)
                if (generation != contentLoadGeneration || !loadOwners.owns(loadOwner)) return@launch
                val message = "Unexpected error: ${e.message}"
                if (preserveCurrentPlaybackOnFailure) {
                    _uiState.update { failTvReplacementLoad(it, message) }
                } else {
                    fail(message)
                }
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
            val transportMountNonce = nextTransportMountNonce(selectedSubtitleTrackIndex(state))
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
        subtitleTrackIndexOverride: Int? = null,
    ) {
        if (recoveryJob?.isActive == true) {
            // Never silently drop a user selection: queue it (newest wins) and
            // re-drive it when the in-flight recovery completes. Failure-driven
            // replans stay dropped — onPlayerError re-raises those.
            if (classification in PlaybackSessionManager.USER_INVALIDATION_CLASSIFICATIONS) {
                queuedRecoveryReplan = QueuedRecoveryReplan(
                    classification = classification,
                    notice = notice,
                    qualityPreference = qualityPreference,
                    subtitleTrackIndexOverride = subtitleTrackIndexOverride,
                )
            }
            return
        }
        val fileId = state.selectedFileId ?: state.mediaFileId ?: return
        val recoveryContentGeneration = contentLoadGeneration
        recoveryJob = viewModelScope.launch {
            val selectedAudio = selectedServerAudioTrackIndex(
                selectedPlayerOrdinal = state.audioTracks.firstOrNull { it.isSelected }?.index,
                catalogAudioTracks = state.fileVersions.firstOrNull { it.fileId == fileId }?.audioTracks,
                currentPlanTrackIndex = state.playbackPlan?.selectedTracks?.audioIndex,
            )
            val selectedSubtitle = subtitleTrackIndexOverride ?: selectedSubtitleTrackIndex(state)
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
                        val adopted = sessionLifecycle.adoptActiveSessionIfCurrent(
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
                            isCurrent = {
                                recoveryContentGeneration == contentLoadGeneration &&
                                    isActive
                            },
                        )
                        if (!adopted) {
                            runCatching {
                                playbackSessionManager.stopSession(decision.session.sessionId)
                            }
                            return@launch
                        }
                        coroutineContext.ensureActive()
                        if (recoveryContentGeneration != contentLoadGeneration) return@launch
                        val transportMountNonce = nextTransportMountNonce(selectedSubtitle)
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
                                serverDuration = effectiveDuration,
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
                    is VideoSessionStartV3.Terminal -> {
                        cancelPendingCatalogSubtitle()
                        _uiState.update {
                            it.copy(
                                error = "Playback unavailable (${decision.reason}): ${decision.message}",
                                isLoading = false,
                                isBuffering = false,
                            )
                        }
                    }
                    VideoSessionStartV3.ServerUpgradeRequired -> {
                        cancelPendingCatalogSubtitle()
                        _uiState.update {
                            it.copy(
                                error = "This Silo server must be updated to support playback recovery.",
                                isLoading = false,
                                isBuffering = false,
                            )
                        }
                    }
                }
                is ApiResult.Error -> {
                    cancelPendingCatalogSubtitle()
                    onReplanRequestFailed(classification, notice, result.message)
                }
                is ApiResult.NetworkError -> {
                    cancelPendingCatalogSubtitle()
                    onReplanRequestFailed(classification, notice, result.exception.message)
                }
            }
        }.also { job ->
            // Cancellation means a content change / reset already cleared the
            // queue; only a completed flight re-drives a queued user selection.
            job.invokeOnCompletion { cause ->
                if (cause == null) redriveQueuedRecoveryReplan()
            }
        }
    }

    private fun redriveQueuedRecoveryReplan() {
        val queued = queuedRecoveryReplan ?: return
        queuedRecoveryReplan = null
        // Current state, not the queuing-time state, so the replan carries the
        // latest committed track/quality selection.
        startProtocolV3Replan(
            classification = queued.classification,
            notice = queued.notice,
            state = _uiState.value,
            qualityPreference = queued.qualityPreference,
            subtitleTrackIndexOverride = queued.subtitleTrackIndexOverride,
        )
    }

    /**
     * A replan HTTP failure is only fatal when the replan was recovering a
     * broken route. For a user track/quality/route change the old route is
     * still mounted and healthy, so a benign 409 or a network blip must not
     * tear playback down with a fatal error banner.
     */
    private fun onReplanRequestFailed(classification: String, notice: String, detail: String?) {
        if (classification in PlaybackSessionManager.USER_INVALIDATION_CLASSIFICATIONS) {
            Log.w(TAG, "Invalidation replan failed ($classification): $detail")
            _uiState.update { it.copy(isLoading = false, isBuffering = false) }
        } else {
            _uiState.update {
                it.copy(
                    error = "$notice ($detail)",
                    isLoading = false,
                    isBuffering = false,
                )
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
        return resolveMountedSubtitleRow(
            track = selected,
            subtitleTracks = state.subtitleTracks,
            mountedSubtitles = state.subtitleUrls,
        )?.index
    }

    fun onPositionChanged(positionMs: Long, durationMs: Long) {
        if (positionMs < 0) return
        // A new recovery response updates the source/player timeline before Compose can run the
        // matching backend.mount effect. Reports from the old MediaItem must not be interpreted
        // through that new timeline during this handoff window.
        if (transportMountGate.suppressPositionReports) return

        val currentState = _uiState.value
        val timeline = currentState.playbackPlan?.timeline
        // Clamp reports against the server-declared runtime, never against
        // state.duration: while a server transcode/remux is still running the
        // engine reports the short in-progress window (a few seconds of HLS
        // playlist), and using a value the engine itself wrote as the ceiling
        // turns that first short sample into a permanent downward ratchet
        // (few-second seek bar, forward seeks snapping back).
        val serverDuration = currentState.serverDuration.takeIf { it > 0.0 }
        val rawPositionSec = positionMs / 1000.0
        val rawDurationSec = durationMs / 1000.0
        val mappedPositionSec = (timeline?.sourcePositionForPlayer(rawPositionSec) ?: rawPositionSec)
            .let { position -> serverDuration?.let { position.coerceAtMost(it) } ?: position }
        val mappedDurationSec = if (durationMs > 0) {
            timeline?.sourcePositionForPlayer(rawDurationSec) ?: rawDurationSec
        } else {
            0.0
        }.let { duration -> serverDuration?.let { duration.coerceAtMost(it) } ?: duration }
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
                // Grow-only: an engine report may extend an unknown runtime (a
                // growing transcode window) but never shrink a known one.
                duration = maxOf(it.duration, durationSec),
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
            durationSec = _uiState.value.duration,
            isPaused = _uiState.value.isPaused,
        )

        // Track B: durably record (local resume + outbox sync) for both streaming
        // and offline-download; throttled to ~every 10s of content time.
        maybeRecordPosition(positionSec, _uiState.value.duration)
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
        val selectedSubtitle = selectedSubtitleTrackIndex(before)
        val adopted = sessionLifecycle.adoptActiveSessionIfCurrent(
            params = StartParams(
                contentId = contentId,
                fileId = fileId,
                capabilities = capabilityDetector.detect(dolbyVision = dolbyVision),
                audioTrackIndex = decision.session.audioTrackIndex,
                subtitleTrackIndex = selectedSubtitle,
                startPosition = sourcePosition,
            ),
            session = decision.session,
            renewMissingSessionWithLegacyStart = false,
            isCurrent = { isCurrentSeekRecovery(request) },
        )
        if (!adopted) {
            runCatching { playbackSessionManager.stopSession(decision.session.sessionId) }
            return
        }
        if (!isCurrentSeekRecovery(request)) return
        val transportMountNonce = nextTransportMountNonce(selectedSubtitle)
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

    // Remote track commands resolve player ordinals to stable server/typed
    // identities, then enter the same transactional replan path as the HUD.
    // An unresolved command remains latched until a later track snapshot can
    // resolve it; only an explicit subtitle -1 means Off.
    fun remoteSelectAudio(index: Int) {
        val state = _uiState.value
        val selected = resolveTvRemoteAudioIntent(
            playerOrdinal = index,
            audioTracks = state.fileVersions
                .firstOrNull { it.fileId == (state.selectedFileId ?: state.mediaFileId) }
                ?.audioTracks
                .orEmpty(),
        )
        if (selected != null) {
            _pendingRemoteAudioIndex.compareAndSet(index, null)
            pendingPersistedAudioFingerprint = null
            playbackMutationFence.beginReplan()
            subtitleTransactions.updatePlaybackContext(subtitlePlaybackContext(state))
            subtitleTransactions.selectAudio(selected)
        } else {
            _pendingRemoteAudioIndex.value = index
        }
    }

    fun remoteSelectSubtitle(index: Int) {
        val state = _uiState.value
        val identity = resolveTvRemoteSubtitleIntent(
            playerOrdinal = index,
            subtitleTracks = state.subtitleTracks,
            subtitleRows = state.subtitleUrls,
        )
        if (identity != null) {
            _pendingRemoteSubtitleIndex.compareAndSet(index, null)
            playbackMutationFence.beginReplan()
            subtitleTransactions.updatePlaybackContext(subtitlePlaybackContext(_uiState.value))
            subtitleTransactions.select(identity)
        } else {
            _pendingRemoteSubtitleIndex.value = index
        }
    }

    private fun retryPendingRemoteTrackIntents() {
        _pendingRemoteAudioIndex.value?.let(::remoteSelectAudio)
        _pendingRemoteSubtitleIndex.value?.let(::remoteSelectSubtitle)
    }

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

    /**
     * Whether an Up Next control has anything to show right now.
     *
     * Shared with the automatic path deliberately: a manual button that can
     * appear when the automatic trigger would find nothing is a button that
     * does nothing when pressed.
     */
    fun canShowNextUpNow(): Boolean {
        val state = _uiState.value
        return state.nextEpisode != null && !state.showNextUp
    }

    /**
     * Surface Up Next on demand, ahead of the credits trigger.
     *
     * Routed through the same commit the automatic timing uses so the overlay,
     * the countdown gating and the auto-advance accounting behave identically —
     * the only difference is what asked for it. Mirrors silo-apple#86, which
     * added the equivalent control to the tvOS transport.
     *
     * The countdown is deliberately NOT started here: someone who opened this
     * themselves is choosing, and a timer that yanks them into the next episode
     * mid-decision is the opposite of what the press asked for.
     */
    fun onUserRequestedNextUp() {
        val next = _uiState.value.nextEpisode ?: return
        if (_uiState.value.showNextUp) return
        nextUpCountdownJob?.cancel()
        nextUpCountdownJob = null
        _uiState.update {
            it.copy(showNextUp = true, nextUpCountdownSeconds = null)
        }
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
        resolveSubtitleRemountReselection(subtitle)
        // The detail-page explicit pick resolves FIRST so a resolved pick can
        // suppress the persisted/auto fallback (and an unresolvable one lets it
        // proceed) before persisted reads its fingerprint.
        resolvePendingInitialSubtitle(subtitle)
        if (_pendingRemoteAudioIndex.value != null) {
            pendingPersistedAudioFingerprint = null
        }
        resolvePendingPersistedTrackSelection(audio, subtitle)
        retryPendingRemoteTrackIntents()
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
        resolveSubtitleRemountReselection(subtitle)
        // The detail-page explicit pick resolves FIRST so a resolved pick can
        // suppress the persisted/auto fallback (and an unresolvable one lets it
        // proceed) before persisted reads its fingerprint.
        resolvePendingInitialSubtitle(subtitle)
        if (_pendingRemoteAudioIndex.value != null) {
            pendingPersistedAudioFingerprint = null
        }
        resolvePendingPersistedTrackSelection(audio, subtitle)
        retryPendingRemoteTrackIntents()
        resolveAutoPreferredTextSubtitle(audio, subtitle)
    }

    private fun resolvePendingPersistedTrackSelection(
        audio: List<PlayerTrackEntry>,
        subtitle: List<PlayerTrackEntry>,
    ) {
        pendingPersistedAudioFingerprint?.let { fingerprint ->
            if (audio.isNotEmpty()) {
                pendingPersistedAudioFingerprint = null
                val state = _uiState.value
                val catalogAudioTracks = state.fileVersions
                    .firstOrNull { it.fileId == (state.selectedFileId ?: state.mediaFileId) }
                    ?.audioTracks
                    .orEmpty()
                resolveTvPersistedAudioPlayerOrdinal(
                    fingerprint = fingerprint,
                    catalogAudioTracks = catalogAudioTracks,
                    mountedAudioTracks = audio,
                )?.let { _pendingRemoteAudioIndex.value = it }
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
            resolveMountedSubtitleTrack(mounted, subtitle)
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
     * A positive value is a COMBINED-space subtitle index (externals first,
     * embedded after — the identity mounted subtitle_urls carry and
     * subtitle_track_index requests resolve), not Media3's flattened
     * text-track ordinal. Resolve it through the mounted server subtitle
     * metadata first so embedded CEA-608 or other player-discovered tracks do
     * not shift the target.
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
        val owner = pendingSubtitleMountAcknowledgement ?: return
        pendingSubtitleMountAcknowledgement = null
        subtitleTransactions.reportMountedSelection(
            identity = owner.identity,
            selected = true,
            snapshotKey = "tv-mounted:${owner.generation}:$index",
            settled = true,
        )
    }

    fun selectAudioOption(index: Int) {
        val state = _uiState.value
        val selected = selectedServerAudioTrackIndex(
            selectedPlayerOrdinal = index,
            catalogAudioTracks = state.fileVersions
                .firstOrNull { it.fileId == (state.selectedFileId ?: state.mediaFileId) }
                ?.audioTracks,
            currentPlanTrackIndex = null,
        ) ?: return
        playbackMutationFence.beginReplan()
        subtitleTransactions.updatePlaybackContext(subtitlePlaybackContext(state))
        subtitleTransactions.selectAudio(selected)
    }

    /**
     * Selects a subtitle by SERVER catalog row index ([PlayerSubtitleInfo.index]),
     * phone-parity for the HUD/quick-picker menus. Catalog-only rows (blank URL)
     * have no mounted Media3 track until the V3 planner materializes them, so
     * the menus must not be keyed off live player tracks. Returns the mounted
     * Media3 track index when one already exists (caller applies it through the
     * normal backend path), or null after scheduling a materializing replan
     * whose track is auto-selected by label once it arrives.
     */
    fun onSelectCatalogSubtitle(serverIndex: Int): Int? {
        val state = _uiState.value
        val row = state.subtitleUrls.firstOrNull { it.index == serverIndex } ?: return null
        selectSubtitleOption(tvSubtitleIdentity(row))
        return null
    }

    fun selectSubtitleOption(identity: SubtitleIdentity) {
        manualSubtitleSelectionApplied = true
        playbackMutationFence.beginReplan()
        subtitleTransactions.updatePlaybackContext(subtitlePlaybackContext(_uiState.value))
        subtitleTransactions.select(identity)
    }

    fun selectSubtitleOption(serverIndex: Int) {
        if (serverIndex == -1) {
            selectSubtitleOption(SubtitleIdentity.Off)
            return
        }
        val row = _uiState.value.subtitleUrls.firstOrNull { it.index == serverIndex } ?: return
        selectSubtitleOption(tvSubtitleIdentity(row))
    }

    fun onSubtitleSelectionFailed(index: Int) {
        val owner = pendingSubtitleMountAcknowledgement ?: return
        pendingSubtitleMountAcknowledgement = null
        subtitleTransactions.reportMountedSelection(
            identity = owner.identity,
            selected = false,
            snapshotKey = "tv-mount-failed:${owner.generation}:$index",
            settled = true,
        )
    }

    /**
     * Abandons an in-flight catalog-subtitle materialization (user turned
     * subtitles Off, or otherwise changed their mind) so the pending pick can't
     * re-enable itself when a later track refresh arrives.
     */
    fun cancelPendingCatalogSubtitle() {
        subtitleRemountReselection.clear()
        subtitleSnapshotSettlement.reset()
        pendingSubtitleMountAcknowledgement = null
    }

    private fun resolveSubtitleRemountReselection(subtitle: List<PlayerTrackEntry>) {
        val snapshotKey = subtitle
            .takeIf(List<PlayerTrackEntry>::isNotEmpty)
            ?.joinToString("|") { "${it.index}:${it.trackId}:${it.isSelected}" }
        when (
            val event = subtitleRemountReselection.consume(
                subtitleTracks = subtitle,
                snapshotKey = snapshotKey,
                settled = subtitleSnapshotSettlement.observe(subtitle),
            )
        ) {
            is TvSubtitleRemountEvent.Select -> {
                pendingSubtitleMountAcknowledgement = event.owner
                _subtitleSelectRequests.tryEmit(event.trackIndex)
            }
            is TvSubtitleRemountEvent.Failed -> subtitleTransactions.reportMountedSelection(
                identity = event.owner.identity,
                selected = false,
                snapshotKey = snapshotKey,
                settled = true,
            )
            null -> Unit
        }
    }

    fun onManualSubtitleSelectionIntent(index: Int) {
        manualSubtitleSelectionApplied = true
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
        val state = _uiState.value
        playbackMutationFence.beginReplan()
        subtitleTransactions.updatePlaybackContext(subtitlePlaybackContext(state))
        subtitleTransactions.selectQuality(wireValue)
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
                    refreshSubtitles(
                        autoSelectSubtitleId = r.data.subtitle.id,
                        source = TvSubtitleRefreshSource.Download,
                    )
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
    internal suspend fun refreshSubtitles(
        autoSelectSubtitleId: Int?,
        source: TvSubtitleRefreshSource = TvSubtitleRefreshSource.Realtime,
    ) {
        val state = _uiState.value
        val mediaFileId = state.mediaFileId ?: return
        val sessionId = state.sessionId ?: return
        subtitleTransactions.updatePlaybackContext(subtitlePlaybackContext(state))
        val owner = subtitleTransactions.beginRefresh(source)
        val downloaded = try {
            when (val r = subtitlesRepository.list(mediaFileId)) {
            is ApiResult.Success -> r.data.subtitles
            is ApiResult.Error -> {
                Log.w(TAG, "refreshSubtitles failed: ${r.code} ${r.message}")
                subtitleTransactions.completeRefreshFailure(owner, r.message)
                return
            }
            is ApiResult.NetworkError -> {
                Log.w(TAG, "refreshSubtitles network error", r.exception)
                subtitleTransactions.completeRefreshFailure(
                    owner,
                    r.exception.message ?: "Subtitle refresh failed.",
                )
                return
            }
            }
        } catch (cancellation: CancellationException) {
            subtitleTransactions.cancelRefresh(owner)
            throw cancellation
        }
        val downloadedRows = mergeDownloadedSubtitles(
            existing = emptyList(),
            downloaded = downloaded,
            sessionId = sessionId,
            serverUrl = state.serverUrl,
        )
        subtitleTransactions.applyRefresh(
            owner = owner,
            subtitleTracks = downloadedRows,
            autoSelectDownloadId = autoSelectSubtitleId,
        )
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
                    refreshSubtitles(
                        autoSelectSubtitleId = outcome.resultSubtitleId,
                        source = TvSubtitleRefreshSource.AiCompletion,
                    )
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
     * HUD Subtitles pane stepper handler. Coerced to ±10000ms in the store; the
     * service binding (A.3f T2) picks up the new value and pushes it into the
     * shared [org.siloserver.silo.common.player.subtitle.SubtitleOffsetHolder]
     * while reparsing the current media item so the change applies to already-
     * buffered cues.
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

    @Volatile
    private var lastAdoptedSessionId: String? = null

    private val exitSessionId: String?
        get() = _uiState.value.sessionId ?: lastAdoptedSessionId

    private fun prepareSessionExit() {
        contentLoadGeneration++
        subtitleSnapshotSettlement.reset()
        resetSeekRecoveryForContentChange()
        transportMountGate.reset()
        val state = _uiState.value
        val fileId = _uiState.value.selectedFileId ?: _uiState.value.mediaFileId
        val scope = finalPositionScope
        if (scope != null && contentId.isNotBlank() && fileId != null) {
            finalPlaybackPositionWriter.submit(
                FinalPlaybackPosition(
                    scope = scope,
                    contentId = contentId,
                    fileId = fileId,
                    positionSeconds = state.position,
                    durationSeconds = state.duration.takeIf { it > 0.0 },
                ),
            )
        }
        introObserveJob?.cancel()
        nextUpCountdownJob?.cancel()
        introAutoSkipController.reset()
        _uiState.value.sessionId?.let { lastAdoptedSessionId = it }
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

    /** Ordered path used by auto-advance before the singleton lifecycle starts the next item. */
    suspend fun stopSessionForExit() {
        subtitleTransactions.invalidateAndAwaitSettlement()
        playbackMutationFence.invalidateAll()
        prepareSessionExit()
        subtitleTransactions.persistCommittedSelectionAndFlush()
        sessionLifecycle.stop(expectedSessionId = exitSessionId)
    }

    /** Ordinary Back/remote-stop path: snapshot locally and return to detail immediately. */
    fun stopSessionForExitAsync() {
        subtitleTransactions.invalidate()
        playbackMutationFence.invalidateAll()
        prepareSessionExit()
        // Final-position durability is owned by the application-scoped
        // finalPlaybackPositionWriter; only the subtitle flush needs a scope here.
        viewModelScope.launch { subtitleTransactions.persistCommittedSelectionAndFlush() }
        sessionLifecycle.stopAsync(expectedSessionId = exitSessionId)
    }

    fun onExit() {
        stopSessionForExitAsync()
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
                val transportMountNonce = nextTransportMountNonce(selectedSubtitleTrackIndex(state))
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
            val transportMountNonce = nextTransportMountNonce(selectedSubtitleTrackIndex(state))
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
        // Lifecycle adoption replaces A only after B is ready. Until then A
        // remains mounted and playable, including when B fails.
        versionSwitchJob?.cancel()
        versionSwitchJob = viewModelScope.launch {
            coroutineContext.ensureActive()
            loadContent(
                startPositionOverride = resumeAt,
                preferredFileIdOverride = fileId,
                suppressResumeRewind = true,
                preserveCurrentPlaybackOnFailure = true,
            )
        }
    }

    /** Reload the current content from the last known position (error-screen retry). */
    /**
     * Retry after a "Can't reach server": issue one fresh health probe, then
     * reload. A recovered server flips the monitor to Reachable so the reload
     * passes the gate; while still offline the probe fails fast.
     */
    fun retryServerReachability() {
        viewModelScope.launch {
            runCatching { serverReachabilityMonitor.retryNow() }
            loadContent()
        }
    }

    /** "Try Anyway" escape hatch: reload bypassing the reachability gate. */
    fun playIgnoringServerReachability() {
        loadContent(force = true)
    }

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
        val teardownSessionId = exitSessionId
        val subtitlePersistenceReservation =
            subtitleTransactions.reserveDurableFinalPersistence()
        subtitleTransactions.invalidateAndSettleAsync(restoreUi = false) {
            subtitlePersistenceReservation?.let(
                subtitleTransactions::requestDurableFinalPersistence,
            )
            playbackMutationFence.invalidateAll()
            sessionLifecycle.stop(expectedSessionId = teardownSessionId)
        }
        subtitleSnapshotSettlement.reset()
        org.siloserver.silo.common.player.debug.PlaybackDebugState.screenError = null
        org.siloserver.silo.common.player.ActivePlaybackFile.clear(
            _uiState.value.selectedFileId ?: _uiState.value.mediaFileId,
        )
        super.onCleared()
        // The application-owned writer survives ViewModel cancellation and
        // preserves the final local resume point without blocking main.
        val cid = contentId.takeIf { it.isNotBlank() }
        val fid = _uiState.value.selectedFileId ?: _uiState.value.mediaFileId
        val scope = finalPositionScope
        if (scope != null && cid != null && fid != null) {
            finalPlaybackPositionWriter.submit(
                FinalPlaybackPosition(
                    scope = scope,
                    contentId = cid,
                    fileId = fid,
                    positionSeconds = _uiState.value.position,
                    durationSeconds = _uiState.value.duration.takeIf { it > 0.0 },
                ),
            )
        }
        introObserveJob?.cancel()
        lifecycleObserveJob?.cancel()
        nextUpCountdownJob?.cancel()
        introAutoSkipController.reset()
    }

}

data class PlaybackClock(
    val position: Double,
    val duration: Double,
)

internal fun TvPlayerViewModel.UiState.withoutPlaybackClock(): TvPlayerViewModel.UiState =
    copy(position = 0.0, duration = 0.0)

internal fun TvPlayerViewModel.UiState.toPlaybackClock(): PlaybackClock =
    PlaybackClock(position = position, duration = duration)
