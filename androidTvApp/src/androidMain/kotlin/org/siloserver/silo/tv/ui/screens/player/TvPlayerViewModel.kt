@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.siloserver.silo.tv.ui.screens.player

import org.siloserver.silo.common.player.dolbyVisionTransformClassification
import org.siloserver.silo.common.player.failureDiagnostics

import org.siloserver.silo.tv.BuildConfig

import android.os.SystemClock
import android.util.Log
import org.siloserver.silo.common.player.SubDiag
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.tv.data.preferences.PlaybackQuality
import org.siloserver.silo.common.player.PlaybackAnalyticsListener
import org.siloserver.silo.common.player.PlaybackCapabilityDetector
import org.siloserver.silo.common.player.PlaybackSessionLifecycle
import org.siloserver.silo.common.player.PlaybackSessionManager
import org.siloserver.silo.common.player.PlaybackTeardownGate
import org.siloserver.silo.common.player.video.MountedAudioTrack
import org.siloserver.silo.common.player.video.AudioReconcileAction
import org.siloserver.silo.common.player.video.DesiredAudio
import org.siloserver.silo.common.player.video.LocalAudioSelection
import org.siloserver.silo.common.player.video.reconcileDesiredAudioAction
import org.siloserver.silo.common.player.video.matchMountedAudioTrack
import org.siloserver.silo.playback.resolveAudioTrackOrdinal
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
import org.siloserver.silo.common.player.seek.replanMountPositionForSource
import org.siloserver.silo.common.player.seek.sourcePositionForPlayer
import org.siloserver.silo.common.network.ServerReachabilityMonitor
import org.siloserver.silo.common.player.video.VideoPlaybackSessionCoordinator
import org.siloserver.silo.common.player.video.VideoPlaybackStartRequest
import org.siloserver.silo.common.player.video.EpisodeAudioIntent
import org.siloserver.silo.common.player.video.EpisodeAudioMode
import org.siloserver.silo.common.player.video.EpisodeSelectionHandoff
import org.siloserver.silo.common.player.video.EpisodeSubtitleIntent
import org.siloserver.silo.common.player.video.EpisodeSubtitleMode
import org.siloserver.silo.common.player.video.ResolvedEpisodeSelection
import org.siloserver.silo.common.player.video.captureEpisodeSourceIntent
import org.siloserver.silo.common.player.video.captureEpisodeSubtitleIntent
import org.siloserver.silo.common.player.normalizedSubtitleCodecFamily
import org.siloserver.silo.common.player.video.VideoPlayerUiState
import org.siloserver.silo.common.player.video.resolvedPlaybackDelivery
import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.common.settings.dolbyVisionPolicySnapshot
import org.siloserver.silo.domain.player.IntroAutoSkipController
import org.siloserver.silo.domain.player.IntroAutoSkipState
import org.siloserver.silo.domain.player.IntroSkipMode
import org.siloserver.silo.domain.player.settlingFalseEdges
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.TimeRange
import org.siloserver.silo.model.catalog.VersionChapter
import org.siloserver.silo.model.settings.SubtitleAppearance
import org.siloserver.silo.model.playback.AutoSubtitleCandidate
import org.siloserver.silo.model.playback.AutoSubtitleContext
import org.siloserver.silo.model.playback.AutoSubtitleResolution
import org.siloserver.silo.model.playback.inventoryAutoSubtitleCandidates
import org.siloserver.silo.model.playback.resolveAutoSubtitle
import org.siloserver.silo.model.playback.selectedCandidate
import org.siloserver.silo.model.playback.PlaybackDelivery
import org.siloserver.silo.model.playback.PlaybackAvailableQualityV3
import org.siloserver.silo.model.playback.PlayMethod
import org.siloserver.silo.model.playback.ClientCodecCapabilities
import org.siloserver.silo.model.playback.ClientPlaybackContext
import org.siloserver.silo.model.playback.PlaybackExecutionPlan
import org.siloserver.silo.model.playback.PlaybackRouteFamily
import org.siloserver.silo.model.playback.PlaybackSessionResponse
import org.siloserver.silo.model.playback.PlaybackTimeline
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.SubtitleMediaIdentity
import org.siloserver.silo.model.playback.buildPlaybackSubtitleChoices
import org.siloserver.silo.model.playback.enrichAuthoritativePlaybackSubtitleChoices
import org.siloserver.silo.model.playback.resolvedSelectedSubtitleIndex
import org.siloserver.silo.model.playback.mergeDownloadedSubtitles
import org.siloserver.silo.playback.PlaybackSubtitleReady
import org.siloserver.silo.playback.applyAuthoritativeSubtitleReadyTrack
import org.siloserver.silo.model.subtitles.SubtitleAiQuota
import org.siloserver.silo.model.subtitles.SubtitleAiStatus
import org.siloserver.silo.model.subtitles.SubtitleDownloadRequest
import org.siloserver.silo.model.subtitles.SubtitleResult
import org.siloserver.silo.model.subtitles.SubtitleSearchRequest
import org.siloserver.silo.model.subtitles.SubtitleTranslateRequest
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.errorMessage
import org.siloserver.silo.playback.nextEpisodeAfter
import org.siloserver.silo.playback.subtitleTrackFingerprint
import org.siloserver.silo.playback.canonicalSubtitleLanguage
import org.siloserver.silo.playback.subtitleLabelIndicatesHearingImpaired
import org.siloserver.silo.player.DolbyVisionPolicy
import org.siloserver.silo.repository.SubtitlesRepository
import org.siloserver.silo.repository.port.PlaybackWriteScope
import org.siloserver.silo.repository.port.TrackSelectionFingerprintUpdate
import org.siloserver.silo.tv.ui.screens.detail.TvDetailTrackSelectionSession
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Always-on tag for subtitle-ownership anomalies.
 *
 * Distinct from SubDiag, which is opt-in tracing: everything logged under this
 * tag means some authority other than the subtitle transaction adapter acted on
 * a text track, which should not be possible and must be visible in a bug
 * report without anyone having set a system property first.
 */
internal const val TV_SUBTITLE_LOG_TAG = "TvSubtitle"

/**
 * How long `isPlaying` must stay false before it counts as a pause rather than
 * a rebuffer, for the intro countdown's purposes.
 *
 * Long enough to cover an ordinary network stall on a TV box, short enough that
 * a viewer who actually pressed pause does not watch the countdown keep running
 * afterwards.
 */
private const val PLAYBACK_PAUSE_GRACE_MS = 1_500L

/** Reduced to the fields that can identify the track across index spaces. */
internal fun PlayerTrackEntry.toMountedAudioTrack(): MountedAudioTrack = MountedAudioTrack(
    ordinal = index,
    language = language,
    codecOrMime = codecOrMime,
    channelCount = channelCount.takeIf { it > 0 },
    label = displayLabel.ifBlank { label },
)

/** Projects the protocol-v3 quality menu verbatim, preserving server order. */
internal fun authoritativePlaybackQualityOptions(
    available: List<PlaybackAvailableQualityV3>,
    selectedLabel: String?,
): List<VideoQualityOption> = available.map { quality ->
    VideoQualityOption(
        id = quality.label,
        label = quality.label,
        isSelected = quality.label == selectedLabel,
        resolution = quality.height.takeIf { it > 0 }?.let { "${it}p" },
    )
}

internal fun clampTvScrubPreview(seconds: Double, duration: Double): Double =
    seconds.coerceAtLeast(0.0).let { value ->
        if (duration > 0.0) value.coerceAtMost(duration) else value
    }

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

/**
 * The audio the server considers in force, as an ORDINAL into
 * [FileVersion.audioTracks].
 *
 * That ordinal is the server's actual contract for audio. Unlike subtitles,
 * audio tracks carry NO index field on the wire — a probe of the running server
 * returns `{"title":"English DTS 5.1","language":"en","codec":"dts",...}` with
 * no `index`, while a subtitle in the same payload has `"index": 2`. So
 * [AudioTrack.index] deserialises to its `0` default for every audio track and
 * is not an identifier. `effective_audio_track_index` is likewise an ordinal.
 *
 * This previously read `catalogAudioTracks.getOrNull(ordinal)?.index`, which
 * therefore evaluated to 0 for every track: every explicit audio pick asked the
 * server for track 0, so choosing Dutch played English.
 *
 * The plan wins over the mounted Media3 ordinal. The plan carries the server's
 * own selection, while a Media3 group ordinal describes only what THIS stream
 * delivered — after a transcode the stream carries just the chosen track and
 * reports ordinal zero, which is "first delivered group", not "catalog track
 * zero". Preferring it made the next replan ask for track zero and silently
 * reverted the audio to the first language.
 *
 * The Media3 ordinal survives as a fallback when there is no plan identity, and
 * only when it is actually within the catalog's range. It is a guess: it holds
 * just when delivered order matches catalog order.
 */
internal fun selectedServerAudioTrackIndex(
    selectedPlayerOrdinal: Int?,
    catalogAudioTracks: List<AudioTrack>?,
    currentPlanTrackIndex: Int?,
): Int? {
    val catalog = catalogAudioTracks.orEmpty()
    // Validate the plan against the catalog when we have one: a stale
    // plan/catalog pairing would otherwise forward an out-of-range ordinal.
    // With no catalog to check against, the plan is still the best identity.
    currentPlanTrackIndex?.let { plan ->
        if (catalog.isEmpty() || plan in catalog.indices) return plan
    }
    return selectedPlayerOrdinal?.takeIf { it in catalog.indices }
}

private fun SubtitleIdentity.serverTrackIndexForTv(): Int = when (this) {
    SubtitleIdentity.Off -> -1
    is SubtitleIdentity.ServerSidecar -> serverIndex
    is SubtitleIdentity.ServerBurnIn -> serverIndex
    is SubtitleIdentity.Embedded -> serverIndex
    is SubtitleIdentity.Downloaded,
    is SubtitleIdentity.LocalMedia3,
    -> -1
}

private fun SubtitleMediaIdentity.toEpisodeSubtitleIntent(
    external: Boolean?,
): EpisodeSubtitleIntent = EpisodeSubtitleIntent(
    mode = EpisodeSubtitleMode.TRACK,
    language = canonicalSubtitleLanguage(language),
    codecFamily = normalizedSubtitleCodecFamily(codecFamily),
    forced = forced,
    hearingImpaired = hearingImpaired,
    external = external,
)

/**
 * Captures only episode-portable intent. Server, download, and Media3 identities
 * remain local to the current item and therefore cannot cross this boundary.
 */
internal fun captureTvEpisodeSelectionHandoff(
    activeVersion: FileVersion?,
    committedSubtitleIdentity: SubtitleIdentity,
    catalogSubtitles: List<PlayerSubtitleInfo>,
    hasExplicitSubtitleSelection: Boolean,
    selectedAudioTrack: PlayerTrackEntry? = null,
    selectedCatalogAudio: AudioTrack? = null,
    hasExplicitAudioSelection: Boolean = false,
): EpisodeSelectionHandoff = EpisodeSelectionHandoff(
    source = captureEpisodeSourceIntent(activeVersion),
    // Only an explicit choice travels. Carrying whatever the server happened to
    // default to would pin that default onto every later episode, which looks
    // identical to a preference the viewer never expressed.
    audio = when {
        !hasExplicitAudioSelection -> EpisodeAudioIntent.auto()
        // Prefer the SOURCE row the plan selected. The mounted Media3 track is
        // the delivered representation, so a DTS 5.1 source transcoded to AAC
        // stereo would hand the next episode "UND / AAC / 2ch" as the stated
        // preference — and the resolver weighs title and codec heavily enough
        // to then match the wrong track or give up and take the default.
        selectedCatalogAudio != null -> EpisodeAudioIntent(
            mode = EpisodeAudioMode.TRACK,
            language = selectedCatalogAudio.language,
            codecFamily = selectedCatalogAudio.codec,
            channelCount = selectedCatalogAudio.channels?.takeIf { it > 0 },
            title = selectedCatalogAudio.title,
        )
        // Legacy fallback: no catalog row to resolve against.
        selectedAudioTrack != null -> EpisodeAudioIntent(
            mode = EpisodeAudioMode.TRACK,
            language = selectedAudioTrack.language,
            codecFamily = selectedAudioTrack.codecOrMime,
            channelCount = selectedAudioTrack.channelCount.takeIf { it > 0 },
            // The label is what tells a commentary track from the main mix when
            // language, codec and channel count are identical.
            title = selectedAudioTrack.label,
        )
        else -> EpisodeAudioIntent.auto()
    },
    subtitle = if (!hasExplicitSubtitleSelection) {
        org.siloserver.silo.common.player.video.EpisodeSubtitleIntent.auto()
    } else {
        when (committedSubtitleIdentity) {
            SubtitleIdentity.Off -> captureEpisodeSubtitleIntent(-1, catalogSubtitles)
            is SubtitleIdentity.ServerSidecar,
            is SubtitleIdentity.ServerBurnIn,
            is SubtitleIdentity.Embedded,
            -> captureEpisodeSubtitleIntent(
                committedSubtitleIdentity.serverTrackIndexForTv(),
                catalogSubtitles,
            )
            is SubtitleIdentity.Downloaded -> committedSubtitleIdentity.media
                .toEpisodeSubtitleIntent(external = true)
            is SubtitleIdentity.LocalMedia3 -> committedSubtitleIdentity.media
                .toEpisodeSubtitleIntent(external = null)
        }
    },
)

internal class TvEpisodeSelectionHandoffLease internal constructor(
    val ownerGeneration: Long,
    val sequence: Long,
    val handoff: EpisodeSelectionHandoff,
)

/** Recoverable-start lease; only the current owner can retain or acknowledge it. */
internal class TvEpisodeSelectionHandoffSlot(
    handoff: EpisodeSelectionHandoff?,
) {
    private var pending = handoff
    private var currentLease: TvEpisodeSelectionHandoffLease? = null
    private var sequence = 0L

    @Synchronized
    fun leaseForStart(ownerGeneration: Long): TvEpisodeSelectionHandoffLease? {
        val handoff = pending ?: return null
        currentLease?.let { lease ->
            if (lease.ownerGeneration == ownerGeneration) return lease
            // A newer launch owner supersedes this transition rather than
            // inheriting an older in-flight selection intent.
            invalidate()
            return null
        }
        return TvEpisodeSelectionHandoffLease(
            ownerGeneration = ownerGeneration,
            sequence = ++sequence,
            handoff = handoff,
        ).also { currentLease = it }
    }

    @Synchronized
    fun retainForRetry(lease: TvEpisodeSelectionHandoffLease?): Boolean {
        if (lease == null || currentLease != lease) return false
        currentLease = null
        return true
    }

    @Synchronized
    fun acknowledgeReady(lease: TvEpisodeSelectionHandoffLease?): Boolean {
        if (lease == null || currentLease != lease) return false
        currentLease = null
        pending = null
        return true
    }

    @Synchronized
    fun invalidate() {
        currentLease = null
        pending = null
    }
}

internal data class TvEpisodeInitialSubtitleSelection(
    val pendingInitialSubtitleIndex: Int?,
    val suppressDurableSubtitleRestore: Boolean,
)

/** Applies a target-only resolution without changing ordinary/manual starts. */
internal fun resolveTvEpisodeInitialSubtitleSelection(
    episodeSelectionHandoff: EpisodeSelectionHandoff?,
    resolvedEpisodeSelection: ResolvedEpisodeSelection?,
    existingPendingInitialSubtitleIndex: Int?,
): TvEpisodeInitialSubtitleSelection {
    if (episodeSelectionHandoff == null || resolvedEpisodeSelection == null) {
        return TvEpisodeInitialSubtitleSelection(
            pendingInitialSubtitleIndex = existingPendingInitialSubtitleIndex,
            suppressDurableSubtitleRestore = false,
        )
    }
    return TvEpisodeInitialSubtitleSelection(
        pendingInitialSubtitleIndex = resolvedEpisodeSelection.subtitleTrackIndex,
        suppressDurableSubtitleRestore = resolvedEpisodeSelection.subtitleIntentSpecified,
    )
}

private fun PlayerTrackEntry.isEffectivelyHearingImpaired(): Boolean =
    isHearingImpaired ||
        subtitleLabelIndicatesHearingImpaired(label) ||
        subtitleLabelIndicatesHearingImpaired(displayLabel)

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

/**
 * Ranks MOUNTED Media3 text tracks through the shared resolver.
 *
 * The ranking itself lives in [resolveAutoSubtitle] — one cascade, one language
 * table, one SDH predicate, one bitmap predicate, shared with the detail page's
 * Auto preview. This only adapts [PlayerTrackEntry] into candidates.
 */
internal fun resolveAutoSubtitleSelection(
    audioTracks: List<PlayerTrackEntry>,
    subtitleTracks: List<PlayerTrackEntry>,
    preferredLanguage: String?,
    subtitleMode: String?,
    showForced: Boolean,
): SubtitleAutoSelection =
    when (
        val resolution = resolveAutoSubtitle(
            candidates = playerTrackAutoSubtitleCandidates(subtitleTracks),
            context = AutoSubtitleContext(
                preferredLanguage = preferredLanguage,
                mode = subtitleMode,
                showForced = showForced,
                audioLanguage = audioTracks.firstOrNull { it.isSelected }?.language,
            ),
        )
    ) {
        AutoSubtitleResolution.NoChange -> SubtitleAutoSelection.NoChange
        AutoSubtitleResolution.Disable -> SubtitleAutoSelection.Disable
        is AutoSubtitleResolution.Select ->
            SubtitleAutoSelection.Select(resolution.candidate.selectionIndex)
    }

/**
 * The identity Auto resolves to for a launch that carried NO decision (deep
 * link, cast, remote/realtime start).
 *
 * Resolved over the SERVER inventory whenever there is one: `subtitle_urls`
 * lists external sidecars the initial plan did not mount, and Media3's mounted
 * text tracks do not. Ranking only what was mounted is what made an external
 * SRT structurally invisible and started the embedded PGS track instead. If the
 * winner is not mounted yet the adapter mounts it — a replan that is legitimate
 * precisely because nobody decided this launch.
 *
 * A null resolution maps to an explicit Off: Auto picked nothing, but a
 * selector or device caption setting may still have a track on, and Apple's
 * engines start subs OFF ("Auto - None" in the detail preview).
 */
internal fun resolveTvAutoSubtitleIdentity(
    audioTracks: List<PlayerTrackEntry>,
    subtitleTracks: List<PlayerTrackEntry>,
    subtitleRows: List<PlayerSubtitleInfo>,
    preferredLanguage: String?,
    subtitleMode: String?,
    showForced: Boolean,
): SubtitleIdentity {
    val context = AutoSubtitleContext(
        preferredLanguage = preferredLanguage,
        mode = subtitleMode,
        showForced = showForced,
        audioLanguage = audioTracks.firstOrNull { it.isSelected }?.language,
    )
    if (subtitleRows.isNotEmpty()) {
        val winner = resolveAutoSubtitle(
            candidates = inventoryAutoSubtitleCandidates(subtitleRows),
            context = context,
        ).selectedCandidate() ?: return SubtitleIdentity.Off
        return subtitleRows.firstOrNull { it.index == winner.selectionIndex }
            ?.let(::tvSubtitleIdentity)
            ?: SubtitleIdentity.Off
    }
    // No server inventory (a purely local mount): the mounted tracks are then
    // the whole truth anyway.
    val winner = resolveAutoSubtitle(
        candidates = playerTrackAutoSubtitleCandidates(subtitleTracks),
        context = context,
    ).selectedCandidate() ?: return SubtitleIdentity.Off
    return subtitleTracks.firstOrNull { it.index == winner.selectionIndex }
        ?.let { track -> tvMountedSubtitleIdentity(track, subtitleTracks, subtitleRows) }
        ?: SubtitleIdentity.Off
}

/**
 * Mounted text tracks as resolver candidates, keyed by Media3 track index.
 *
 * Hearing-impaired travels as an explicit signal (role flags and both labels),
 * which the catalog cannot supply and the shared predicate ORs with the title.
 */
internal fun playerTrackAutoSubtitleCandidates(
    subtitleTracks: List<PlayerTrackEntry>,
): List<AutoSubtitleCandidate> = subtitleTracks.map { track ->
    AutoSubtitleCandidate(
        selectionIndex = track.index,
        language = track.language,
        codec = track.codecOrMime,
        forced = track.isForced,
        hearingImpaired = track.isEffectivelyHearingImpaired(),
    )
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
    /** True when the launch ordinal is a pick made this session, not a restore. */
    val initialAudioPickedThisSession: Boolean = false,
    /** Pre-selected subtitle track index (null = no handoff, -1 = Off). */
    val initialSubtitleTrackIndex: Int? = null,
    /**
     * True when [initialSubtitleTrackIndex] is the detail row's Auto preview
     * rather than the viewer's own pick. The player still starts on it — that
     * is the whole point of the handoff — but must not record it as a manual
     * selection (no durable persistence, no explicit episode intent).
     */
    val initialSubtitleAutoResolved: Boolean = false,
    /**
     * How many consecutive auto-advances led to this playback (0 = a manual
     * start). The player re-mounts per episode, so the pass-out streak rides
     * the route instead of living in the VM. When it reaches the pass-out
     * threshold setting, the next credits-reached shows "Still watching?"
     * instead of auto-advancing.
     */
    val autoAdvanceCount: Int = 0,
    val episodeSelectionHandoff: EpisodeSelectionHandoff? = null,
)

/** Emitted to ask the screen to navigate to the next episode (auto-advance / Continue). */
data class PlayNextRequest(
    val contentId: String,
    val autoAdvanceCount: Int,
    val episodeSelectionHandoff: EpisodeSelectionHandoff,
)

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
        /** How long a Dolby Vision toggle may claim "Applying…" before the cue gives up. */
        private const val OUTPUT_SWITCH_FEEDBACK_TIMEOUT_MS = 20_000L
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
    // The handoff belongs to one cross-screen transition. A recoverable start
    // leases it until Ready publication; replacement/exit invalidates it.
    private val episodeSelectionHandoffSlot = TvEpisodeSelectionHandoffSlot(launchArgs.episodeSelectionHandoff)

    // Pre-playback track selections from the detail screen. Audio is sent to the
    // server session start; subtitle is applied once the player's tracks land
    // (see [applyInitialSubtitleIfPending]). Cleared after the first apply so a
    // later user track change isn't overridden.
    private val initialAudioTrackIndex: Int? = launchArgs.initialAudioTrackIndex
    private var pendingInitialSubtitleIndex: Int? = launchArgs.initialSubtitleTrackIndex

    /**
     * Whether [pendingInitialSubtitleIndex] is the detail row's Auto preview
     * rather than the viewer's own pick. Both are applied identically — the
     * row's decision is what starts — but only an explicit pick may be recorded
     * as a manual selection.
     */
    private var pendingInitialSubtitleAutoResolved: Boolean =
        launchArgs.initialSubtitleAutoResolved

    /**
     * Non-empty track callbacks the explicit pick has failed to resolve
     * tracks land (Media3 reports everything at once), so an unresolved pick
     * is retried across callbacks until the sidecars arrive — bounded by
     * [MAX_PENDING_INITIAL_SUBTITLE_ATTEMPTS], after which the pick is dropped
     * so the persisted/auto fallback can proceed.
     */
    private var pendingInitialSubtitleAttempts = 0
    private var pendingPersistedAudioFingerprint: String? = null
    private var autoTextSubtitleSelectionAttempted = false
    private var manualSubtitleSelectionApplied = false

    /**
     * A launch handoff (explicit pick OR the detail row's Auto preview) has been
     * applied, so the player must not re-decide.
     *
     * Separate from [manualSubtitleSelectionApplied], which answers a different
     * question — "did the VIEWER choose this" — and drives persistence and the
     * next-episode intent.
     */
    private var launchSubtitleSelectionApplied = false
    /**
     * Whether the viewer picked the current audio track themselves.
     *
     * Only an explicit choice is carried into the next episode; a server
     * default must not be pinned onto every later one.
     */
    private var manualAudioSelectionApplied = false

    /** Monotonic across the screen's life, so no id is ever reused. */
    private var subtitleFailureIdSeed = 0L

    private fun nextSubtitleFailureId(): Long = ++subtitleFailureIdSeed

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
    // Mounted-transport extent reported by the screen's position poll: whether
    // the current Media3 window is seekable and how far it reaches in
    // player-local time (-1 = unknown/unset). Feeds
    // [mountedSeekableSourceRange] so decideSeek can ride the mounted content
    // for quick skips instead of re-anchoring through the server.
    private var playerWindowIsSeekable = false
    private var playerWindowEndPlayerMs = -1L

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
        /**
         * Catalog ordinal of the audio the viewer wants — including a track the
         * mounted stream already carried, switched without a server replan.
         * Outranks the plan for display, for later replan requests and for the
         * next episode's handoff: the plan names what the server last
         * delivered, not what was chosen.
         */
        val desiredAudioOrdinal: Int? = null,
        /** False while the player has not yet been shown on that track. */
        val desiredAudioConfirmed: Boolean = false,
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
        /** Distinguishes two failures that happen to read the same. */
        val subtitleFailureId: Long = 0L,
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
        val recap: TimeRange? = null,
        val preview: TimeRange? = null,
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
        // `nextUpCountdownSeconds` drives the auto-play CountdownRing; null
        // means no countdown (auto-play off, pass-out gate hit, or no next
        // episode). A card raised at end-of-playback counts a wall clock down
        // to 0 and then plays the next episode. A card raised at the credits
        // marker instead mirrors the remaining playback time, so reaching 0
        // means "the stream should be over" — it waits for the player to say
        // so rather than cutting the tail off.
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
    private var lastAdapterMountIdentity: SubtitleIdentity? = null

    /**
     * Authority for the NEXT mount the adapter arms, consumed by the snapshot
     * callback below. App-derived selections (launch auto-pick, detail-page
     * restore) must not be able to evict a user pick that is still applying.
     */
    private var nextSubtitleMountPriority = TvSubtitleMountPriority.UserTransaction

    /**
     * Identity the app chose automatically, if it is still the committed one.
     *
     * The adapter cannot tell an automatic pick from a viewer's choice once it
     * is committed, and both flow through the same persistence port — so the
     * per-item subtitle preference is held back here. An automatic pick must
     * never be written back as though the viewer had made it; the next explicit
     * selection clears this and persists normally.
     */
    private var autoSelectedSubtitleIdentity: SubtitleIdentity? = null

    /** True from issuing an automatic selection until the adapter commits it. */
    private var autoSubtitleSelectionInFlight = false
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
                // Only when AUDIO was what changed. Every commit carries the
                // current audio index — a subtitle-only change included — so
                // testing the index for non-null marked the server default as
                // the viewer's choice after any successful subtitle change.
                if (committed.audioPreferenceSpecified) onAudioSelectionCommitted()
                val writeScope = context.writeScope ?: return false
                return userItemStatePort.recordTrackSelection(
                    scope = writeScope,
                    contentId = context.contentId,
                    fileId = context.mediaFileId,
                    audioUpdate = tvAudioTrackPersistenceUpdate(
                        committedAudioTrackIndex = committed.audioTrackIndex,
                        audioTracks = context.audioTracks,
                    ),
                    subtitleUpdate = tvSubtitlePersistenceUpdate(
                        committedIdentity = committed.identity,
                        automaticIdentity = autoSelectedSubtitleIdentity,
                    ),
                )
            }
        },
        onSnapshotChanged = { snapshot ->
            val localMountIdentity = snapshot.localMountIdentity
            if (localMountIdentity != null && localMountIdentity != lastAdapterMountIdentity) {
                subtitleMountGeneration += 1
                subtitleRemountReselection.arm(
                    identity = localMountIdentity,
                    generation = subtitleMountGeneration,
                    priority = nextSubtitleMountPriority,
                )
                nextSubtitleMountPriority = TvSubtitleMountPriority.UserTransaction
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
            if (autoSubtitleSelectionInFlight &&
                !snapshot.subtitleApplying &&
                snapshot.localMountIdentity == null
            ) {
                autoSubtitleSelectionInFlight = false
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
                    subtitleFailureId = when {
                        snapshot.failureMessage == null -> state.subtitleFailureId
                        // Any failure that is not the one already on screen is a
                        // new event. Requiring the previous slot to be empty
                        // meant a second, different failure inherited the first
                        // one's id and was therefore never shown — the effect
                        // keys on the id alone.
                        snapshot.failureMessage != state.subtitleFailureMessage ->
                            nextSubtitleFailureId()
                        else -> state.subtitleFailureId
                    },
                    subtitleUrls = authoritativeTvSubtitleRows(
                        snapshotRows = snapshot.subtitleTracks,
                        previousRows = state.subtitleUrls,
                    ),
                    subtitleRefreshNonce = snapshot.subtitleRefreshNonce
                        .coerceAtMost(Int.MAX_VALUE.toLong())
                        .toInt(),
                    videoQualities = state.videoQualities,
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
            // Row-aware on purpose: a v3 inventory row describing a track muxed
            // into a direct-play stream is still typed `delivery = sidecar`, so
            // asking the identity resolver alone answered "not mounted" for the
            // track Media3 already had, and every app-derived pick of it took
            // the staged-replan path (see tvResolveMountedSubtitleTrack).
            val state = _uiState.value
            tvResolveMountedSubtitleTrack(
                identity = identity,
                subtitleRows = state.subtitleUrls,
                mounted = state.subtitleTracks.map { it.toMountedTvSubtitleTrack() },
            ) != null
        },
    )
    private val subtitleTransactionLaunchMutex = Mutex()
    private val playbackMutationFence by lazy {
        TvPlayerMutationFence(loadOwners, subtitleTransactions::invalidate)
    }

    /** Intro skip pill state. The screen consumes this directly. */
    val introSkipState: StateFlow<IntroAutoSkipState> = introAutoSkipController.state

    /** Bumps whenever the pill's timer (re)starts, so the fill can re-anchor. */
    val introSkipCountdownRun: StateFlow<Int> = introAutoSkipController.countdownRun

    /** False while the pill is up but its timer is frozen by a pause. */
    val introSkipTimerRunning: StateFlow<Boolean> = introAutoSkipController.timerRunning

    /** Total seconds a fresh intro prompt runs for, for the fill's arithmetic. */
    val introSkipTotalSeconds: Int = introAutoSkipController.totalCountdownSeconds

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
     * Mounts the subtitle transaction adapter has asked for, each carrying the
     * owner that must be told how it went. Mirrors the seekRequests idiom: the
     * screen collects and calls SubtitleManager.selectSubtitle — the VM never
     * touches the controller.
     *
     * This is the ONLY channel that may enable or disable a text track on TV.
     * It used to be a bare `SharedFlow<Int>` that the legacy auto/persisted/
     * detail-pick paths also emitted into without arming an owner, which is how
     * playback and the HUD ended up disagreeing.
     */
    private val _subtitleMountRequests =
        MutableSharedFlow<TvSubtitleMountRequest>(extraBufferCapacity = 1)
    internal val subtitleMountRequests: SharedFlow<TvSubtitleMountRequest> = _subtitleMountRequests

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
    val introSkipMode: StateFlow<IntroSkipMode> = playerSettingsStore.introSkipModeFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, IntroSkipMode.Default)
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
    private var pendingAuthoritativeSubtitleDownloadId: Int? = null
    private val authoritativeSubtitleReadyRows = mutableMapOf<Pair<String, Int>, PlayerSubtitleInfo>()
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
            sessionLifecycle.missingSessionEvents.collect { renewal ->
                val state = _uiState.value
                if (
                    state.sessionId == renewal.staleSessionId &&
                    renewal.startParams.contentId == contentId
                ) {
                    loadContent(
                        startPositionOverride = renewal.positionSeconds,
                        preferredFileIdOverride = renewal.startParams.fileId,
                        recoveryStartParams = renewal.startParams,
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

    private suspend fun subtitlePlaybackContext(state: UiState): TvSubtitlePlaybackContext {
        val dolbyVision = playerSettingsStore.dolbyVisionPolicySnapshot()
        val capabilities = capabilityDetector.detect(dolbyVision = dolbyVision)
        return subtitlePlaybackContext(
            state = state,
            capabilities = capabilities,
            clientPlaybackContext = capabilityDetector.detectPlaybackContext(
                formFactor = "tv",
                appVersion = BuildConfig.VERSION_NAME,
                dolbyVision = dolbyVision,
                capabilities = capabilities,
            ),
        )
    }

    private fun subtitlePlaybackContext(
        state: UiState,
        capabilities: ClientCodecCapabilities,
        clientPlaybackContext: ClientPlaybackContext,
    ): TvSubtitlePlaybackContext {
        val fileId = state.selectedFileId ?: state.mediaFileId ?: 0
        val version = state.fileVersions.firstOrNull { it.fileId == fileId }
        // The viewer's confirmed choice outranks the plan, exactly as the
        // recovery replan already does. A direct-play local switch changes the
        // mounted track without replanning, so the plan can still name the
        // previous audio — and a subtitle, quality or output-route transaction
        // built from it would replan the viewer straight back onto the track
        // they had just switched away from.
        val selectedAudio = state.desiredAudioOrdinal?.takeIf { state.desiredAudioConfirmed }
            ?: selectedServerAudioTrackIndex(
                selectedPlayerOrdinal = state.audioTracks.firstOrNull { it.isSelected }?.index,
                catalogAudioTracks = version?.audioTracks,
                currentPlanTrackIndex = state.playbackPlan?.selectedTracks?.audioIndex,
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
            capabilities = capabilities,
            clientPlaybackContext = clientPlaybackContext,
            writeScope = finalPositionScope,
        )
    }

    private fun launchSubtitleTransaction(
        state: UiState,
        transaction: () -> Unit,
    ) {
        viewModelScope.launch {
            subtitleTransactionLaunchMutex.withLock {
                subtitleTransactions.updatePlaybackContext(subtitlePlaybackContext(state))
                transaction()
            }
        }
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
        val adopted = sessionLifecycle.adoptActiveSessionIfCurrent(
            params = StartParams(
                contentId = contentId,
                fileId = fileId,
                capabilities = ready.capabilities,
                audioTrackIndex = adoption.committed.audioTrackIndex,
                subtitleTrackIndex = adoption.committed.identity.serverTrackIndexForTv(),
                qualityPreference = adoption.committed.qualityPreference,
                startPosition = ready.session.position,
                clientPlaybackContext = ready.clientPlaybackContext,
            ),
            session = ready.session,
            deferPublication = true,
            isCurrent = adoption::isCurrent,
        )
        if (!adopted) return TvSubtitleAdoptionResult.Superseded
        // The exit token names what the lifecycle owns, from the moment it owns
        // it — not from the UI publication further down. Supersession in the gap
        // otherwise leaves teardown naming the predecessor, the ownership guard
        // rightly refusing it, and the one-shot gate blocking any retry.
        lastAdoptedSessionId = ready.session.sessionId
        if (!adoption.isCurrent()) return TvSubtitleAdoptionResult.Superseded
        unpublishedSubtitleUi[ready.session.sessionId] = before

        val subtitleUrls = enrichAuthoritativePlaybackSubtitleChoices(
            catalogTracks = version?.subtitleTracks.orEmpty(),
            plannedTracks = ready.session.subtitleUrls.orEmpty(),
        )
        val duration = ready.session.durationSeconds ?: 0.0
        val remountPosition = ready.plan.timeline.replanMountPositionForSource(
            adoption.requestedSourcePositionSeconds,
        )
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
                videoQualities = authoritativePlaybackQualityOptions(
                    available = ready.plan.availableQualities,
                    selectedLabel = adoption.committed.qualityPreference,
                ),
                container = ready.plan.stream.container ?: version?.container ?: state.container,
                duration = duration,
                serverDuration = duration,
                subtitleUrls = subtitleUrls,
                chapters = version?.chapters.orEmpty(),
                startPosition = remountPosition.playerPositionSeconds,
                position = remountPosition.sourcePositionSeconds,
            )
        }
        Log.i(
            TAG,
            "subtitle_replan_mount restored_source_seconds=${remountPosition.sourcePositionSeconds} " +
                "player_seconds=${remountPosition.playerPositionSeconds}",
        )
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
        // The token follows lifecycle ownership in BOTH directions. Rollback
        // hands ownership back to the predecessor, so leaving the token on the
        // discarded replacement would make teardown name a session the
        // lifecycle no longer holds — and be refused. This runs regardless of
        // restoreUi: ownership reverts either way.
        if (lastAdoptedSessionId == playback.sessionId) {
            lastAdoptedSessionId = predecessor?.sessionId
        }
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
        // Same rule as the subtitle rollback: ownership reverted to the
        // predecessor, so the exit token has to revert with it.
        if (lastAdoptedSessionId == sessionId) {
            lastAdoptedSessionId = predecessor?.state?.sessionId
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
            // The mounted item was replaced. Facts from the previous
            // transport's window must not be mapped through the new plan's
            // timeline offset — that can overstate the new extent and turn a
            // target the new transport cannot serve into a silent,
            // wrong-position native seek. The next poll tick (≤500ms)
            // repopulates from the mounted item; until then the hint is
            // absent, which at worst costs an unnecessary reanchor.
            playerWindowIsSeekable = false
            playerWindowEndPlayerMs = -1L
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
        // A missing server session is a renewal, not a new route. Use the
        // lifecycle's adoption-time selection snapshot because Media3 may have
        // already cleared its live tracks by the time the 404 is observed.
        recoveryStartParams: StartParams? = null,
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
        if (recoveryStartParams != null) {
            pendingInitialSubtitleIndex = recoveryStartParams.subtitleTrackIndex
            // A recovery restores the selection the session was already
            // playing, so it restores that selection's STANDING too. The
            // snapshot carries an index and no provenance, so read it off the
            // session being replaced — this runs before the flags are cleared
            // below. Calling it manual unconditionally promoted a pick the app
            // had made into the viewer's: once resolved it set
            // manualSubtitleSelectionApplied, and the automatic choice rode the
            // next episode's handoff and the durable preference.
            pendingInitialSubtitleAutoResolved = !manualSubtitleSelectionApplied
            pendingInitialSubtitleAttempts = 0
        }
        val loadOwner = playbackMutationFence.beginLoad(
            contentId = contentId,
            preferredFileId = preferredFileIdOverride ?: preferredFileId,
            preferredQuality = recoveryStartParams?.qualityPreference
                ?: qualityOverride
                ?: preferredQuality,
        )
        hasRenderedFirstFrame = false
        resetSeekRecoveryForContentChange()
        transportMountGate.beginLoad()
        introAutoSkipController.reset()
        manualSubtitleSelectionApplied = false
        launchSubtitleSelectionApplied = false
        autoSelectedSubtitleIdentity = null
        autoSubtitleSelectionInFlight = false
        // Cleared here and raised only if the carried choice actually RESOLVES
        // against this episode's tracks.
        //
        // Seeding it from the intent alone was wrong: resolveEpisodeAudioIntent
        // deliberately returns null when nothing matches or the match is
        // ambiguous, and that null means the server default plays. Marking it
        // manual anyway made the next auto-advance capture that default as a
        // deliberate choice — so one unresolvable episode turned a server
        // default into a preference that then propagated for the rest of the
        // series.
        manualAudioSelectionApplied = false
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
            var episodeSelectionHandoffLease: TvEpisodeSelectionHandoffLease? = null
            try {
                if (!subtitleTransactions.invalidateAndAwaitSettlement()) return@launch
                runCatching { playerSettingsStore.refreshFromServer() }
                if (!loadOwners.owns(loadOwner)) return@launch
                episodeSelectionHandoffLease = episodeSelectionHandoffSlot.leaseForStart(
                    ownerGeneration = loadOwner.generation,
                )
                val episodeSelectionHandoff = episodeSelectionHandoffLease?.handoff
                val request = VideoPlaybackStartRequest(
                        contentId = contentId,
                        preferredFileId = preferredFileIdOverride ?: preferredFileId,
                        roomId = roomId,
                        resumePositionOverride = startPositionOverride,
                        audioTrackIndex = if (recoveryStartParams != null) {
                            recoveryStartParams.audioTrackIndex
                        } else {
                            initialAudioTrackIndex
                        },
                        subtitleTrackIndex = if (recoveryStartParams != null) {
                            recoveryStartParams.subtitleTrackIndex
                        } else {
                            pendingInitialSubtitleIndex
                        },
                        preferredQualityOverride = recoveryStartParams?.qualityPreference
                            ?: preferredQuality,
                        playbackQualityIntent = qualityOverride,
                        suppressResumeRewind = suppressResumeRewind,
                        force = force,
                        episodeSelectionHandoff = episodeSelectionHandoff,
                        recoveryStartParams = recoveryStartParams,
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
                                episodeSelectionHandoffSlot.retainForRetry(
                                    episodeSelectionHandoffLease,
                                )
                                fail("Playback start returned no session.")
                                return@launch
                            }
                        unpublishedReadySession.acquire(allocatedSessionId)
                        // Fresh load is a fourth lifecycle-first path: the
                        // starter already adopted this session before returning,
                        // and several suspending hydration steps stand between
                        // here and the UI publication below. Advance the exit
                        // token now, or an exit landing in that gap names the
                        // predecessor, is refused, and permanently claims the
                        // one-shot gate while this load goes on to publish.
                        // The rollback paths revert it if this never publishes.
                        lastAdoptedSessionId = allocatedSessionId
                        if (!loadOwners.owns(loadOwner)) {
                            loadOwners.publishReadyIfOwned(
                                owner = loadOwner,
                                sessionId = allocatedSessionId,
                                publish = {},
                                stopStaleSession = unpublishedReadySession::rollbackIfOwned,
                            )
                            return@launch
                        }
                        val subtitleSelection = resolveTvEpisodeInitialSubtitleSelection(
                            episodeSelectionHandoff = episodeSelectionHandoff,
                            resolvedEpisodeSelection = result.resolvedEpisodeSelection,
                            existingPendingInitialSubtitleIndex = pendingInitialSubtitleIndex,
                        )
                        pendingInitialSubtitleIndex = subtitleSelection.pendingInitialSubtitleIndex
                        if (episodeSelectionHandoff != null) {
                            // A carried episode intent is the viewer's, not a preview.
                            pendingInitialSubtitleAutoResolved = false
                        }
                        val resolvedSelection = result.resolvedEpisodeSelection
                        if (episodeSelectionHandoff != null && resolvedSelection != null) {
                            pendingInitialSubtitleAttempts = 0
                            // The carried audio choice counts as the viewer's
                            // only once it RESOLVED to a real track here. A
                            // TRACK intent that matched nothing leaves the
                            // server default playing, and calling that manual
                            // would hand it on to the next episode as though it
                            // had been chosen.
                            if (resolvedSelection.audioTrackIndex != null) {
                                manualAudioSelectionApplied = true
                            }
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
                        val restorePreference = if (
                            pendingInitialSubtitleIndex == null &&
                            !subtitleSelection.suppressDurableSubtitleRestore
                        ) {
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
                                    // V3 subtitle inventory is complete. A
                                    // catalog listing may enrich a row only by
                                    // stable identity; it may never add or
                                    // renumber rows, so publish the plan rows
                                    // unchanged on initial playback.
                                    ApiResult.Success(result.subtitleUrls)
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
                        val publishedSubtitleContext = subtitlePlaybackContext(
                            predecessorUi.copy(
                                sessionId = result.sessionId,
                                playbackPlan = result.playbackPlan,
                                selectedFileId = result.fileId,
                                fileVersions = result.versions,
                                mediaFileId = result.mediaFileId,
                                position = result.sourceStartPositionSeconds,
                                subtitleUrls = hydratedSubtitleUrls,
                            ),
                        )
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
                                // Paired with the UI publication so the exit
                                // token is never staler than UI state — the
                                // invariant that lets exitSessionId read it
                                // first.
                                result.sessionId?.let { lastAdoptedSessionId = it }
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
                                videoQualities = authoritativePlaybackQualityOptions(
                                    available = result.playbackPlanV3?.availableQualities.orEmpty(),
                                    selectedLabel = qualityOverride
                                        ?: preferredQuality
                                        ?: PlaybackQuality.Auto.wireValue,
                                ),
                                mediaFileId = result.mediaFileId,
                                startPosition = result.startPositionSeconds,
                                position = result.sourceStartPositionSeconds,
                                duration = result.durationSeconds ?: 0.0,
                                serverDuration = result.durationSeconds ?: 0.0,
                                isPaused = false,
                                subtitleUrls = hydratedSubtitleUrls,
                                preferredAudioLanguage = result.preferredAudioLanguage,
                                preferredTextLanguage = result.preferredTextLanguage,
                                preferredSubtitleMode = result.preferredSubtitleMode,
                                showForcedSubtitles = result.showForcedSubtitles,
                                intro = result.intro,
                                credits = result.credits,
                                recap = result.recap,
                                preview = result.preview,
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
                                nextUpCountdownTotalSeconds = NEXT_UP_COUNTDOWN_SECONDS,
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
                                    context = publishedSubtitleContext,
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
                            episodeSelectionHandoffSlot.retainForRetry(
                                episodeSelectionHandoffLease,
                            )
                            fail("Playback publication could not be confirmed.")
                            return@launch
                        }
                        if (result.resolvedEpisodeSelection != null) {
                            episodeSelectionHandoffSlot.acknowledgeReady(
                                episodeSelectionHandoffLease,
                            )
                        }
                        startIntroAutoSkipObserver()
                        resolveNextEpisode()
                    }
                    is VideoPlayerUiState.Error -> {
                        episodeSelectionHandoffSlot.retainForRetry(
                            episodeSelectionHandoffLease,
                        )
                        if (preserveCurrentPlaybackOnFailure) {
                            _uiState.update { failTvReplacementLoad(it, result.message) }
                        } else {
                            fail(result.message)
                        }
                    }
                    is VideoPlayerUiState.ServerUnreachable -> {
                        episodeSelectionHandoffSlot.retainForRetry(
                            episodeSelectionHandoffLease,
                        )
                        _uiState.update {
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
                episodeSelectionHandoffSlot.retainForRetry(episodeSelectionHandoffLease)
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
        // The observer still runs in a room — with the mode pinned to `ask` the
        // controller never seeks on its own, and the Skip Intro pill it offers
        // stays live; its Select routes through the screen's gate-checked seek.
        // A room member who chose `never` still gets the pill, which is the
        // lesser wrong: the alternative is a mode whose only implementation is
        // a seek nobody in the room is allowed to make.
        val effectiveMode = if (roomId != null) {
            flowOf(IntroSkipMode.ASK)
        } else {
            playerSettingsStore.introSkipModeFlow
        }
        introObserveJob?.cancel()
        introObserveJob = introAutoSkipController.observe(
            position = _uiState
                .map { it.position }
                .distinctUntilChanged(),
            introRange = _uiState
                .map { it.intro }
                .distinctUntilChanged(),
            mode = effectiveMode,
            introKey = _uiState
                .map { state ->
                    state.intro?.let { intro ->
                        "${state.sessionId}:${state.selectedFileId}:${intro.start}:${intro.end}"
                    }
                }
                .distinctUntilChanged(),
            // Only reachable outside a room, where the mode is pinned to `ask`.
            onSeek = { seekToSec -> seekImmediate(seekToSec) },
            // Filtered, not raw: isPlaying dips for a rebuffer exactly as it
            // does for a deliberate pause, and a pause that reaches the
            // controller freezes the timer. Unfiltered, a stuttering stream
            // would stall the prompt on every hiccup.
            //
            // isPaused is the viewer's own press and needs no filtering, so it
            // freezes the timer on the frame of the press rather than after
            // the grace window.
            playbackActive = _uiState
                .map { it.isPlaying && !it.isLoading }
                .settlingFalseEdges(
                    graceMillis = PLAYBACK_PAUSE_GRACE_MS,
                    deliberatelyInactive = _uiState.map { it.isPaused },
                ),
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

        startProtocolV3Replan(
            classification = reason.failureClassification(),
            notice = notice,
            state = state,
            diagnostics = reason.failureDiagnostics(),
        )
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
            // Locally-confirmed choice first, same reason as the transaction
            // context: the plan names the last track the server delivered, so
            // a recovery replan would otherwise undo the viewer's pick.
            val selectedAudio = state.desiredAudioOrdinal ?: selectedServerAudioTrackIndex(
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
                capabilities = capabilities,
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
            //
            // Bailing out here is not enough on its own. By the time this
            // returns, the manager has already committed and taken ownership of
            // the replacement session — so abandoning the result quietly leaves
            // a transcode running on the server that nothing will ever stop.
            // The viewer sees playback exit; the server keeps the stream slot
            // until it times out. Release it explicitly on every abandon path.
            val abandonedSessionId = (result as? ApiResult.Success)
                ?.data
                ?.let { it as? VideoSessionStartV3.Ready }
                ?.session
                ?.sessionId
            if (!isActive || recoveryContentGeneration != contentLoadGeneration) {
                // Released on the manager's own scope, which outlives this
                // screen: the whole point is to run after the reason for
                // abandoning, and this ViewModel's scope may already be gone.
                abandonedSessionId?.let(playbackSessionManager::abandonActiveVideoSessionAsync)
            }
            coroutineContext.ensureActive()
            if (recoveryContentGeneration != contentLoadGeneration) return@launch
            when (result) {
                is ApiResult.Success -> when (val decision = result.data) {
                    is VideoSessionStartV3.Ready -> {
                        val remountPosition = decision.plan.timeline
                            .replanMountPositionForSource(state.position)
                        val effectiveFileId = decision.session.mediaFileId.takeIf { it > 0 }
                            ?: decision.plan.effectiveMediaFileId
                            ?: fileId
                        val effectiveVersion = state.fileVersions.firstOrNull {
                            it.fileId == effectiveFileId
                        }
                        val effectiveResolution = effectiveVersion?.resolution
                            ?: decision.plan.effectiveRecipe.height?.let { "${it}p" }
                        val effectiveSubtitleUrls = enrichAuthoritativePlaybackSubtitleChoices(
                            catalogTracks = effectiveVersion?.subtitleTracks.orEmpty(),
                            plannedTracks = decision.session.subtitleUrls.orEmpty(),
                        )
                        val returnedSubtitleIndex = decision.plan.resolvedSelectedSubtitleIndex()
                        val returnedSubtitleIdentity = returnedSubtitleIndex
                            ?.let { index -> effectiveSubtitleUrls.singleOrNull { it.index == index } }
                            ?.let(::tvSubtitleIdentity)
                            ?: SubtitleIdentity.Off
                        val returnedAudioIndex = decision.plan.selectedTracks.audio?.index
                            ?: decision.session.audioTrackIndex
                        val effectiveContainer = decision.plan.stream.container
                            ?: effectiveVersion?.container
                            ?: state.container.takeIf { effectiveFileId == fileId }
                        val effectiveDuration = decision.session.durationSeconds ?: 0.0
                        var adopted = false
                        try {
                            adopted = sessionLifecycle.adoptActiveSessionIfCurrent(
                                params = StartParams(
                                    contentId = contentId,
                                    fileId = effectiveFileId,
                                    capabilities = decision.capabilities,
                                    audioTrackIndex = returnedAudioIndex,
                                    subtitleTrackIndex = returnedSubtitleIndex ?: -1,
                                    qualityPreference = qualityPreference
                                        ?: qualityOverride
                                        ?: preferredQuality
                                        ?: PlaybackQuality.Auto.wireValue,
                                    startPosition = decision.session.position,
                                    clientPlaybackContext = decision.clientPlaybackContext,
                                ),
                                session = decision.session,
                                isCurrent = {
                                    recoveryContentGeneration == contentLoadGeneration &&
                                        isActive
                                },
                            )
                        } finally {
                            // Covers refusal AND cancellation while awaiting the
                            // lifecycle mutex, which throws before isCurrent runs.
                            // NonCancellable because the usual reason for being
                            // here is that this coroutine was cancelled, and a
                            // cancelled one cannot make the releasing call.
                            if (!adopted) {
                                withContext(NonCancellable) {
                                    runCatching {
                                        playbackSessionManager.stopSession(
                                            decision.session.sessionId,
                                        )
                                    }
                                }
                            }
                        }
                        if (!adopted) return@launch
                        lastAdoptedSessionId = decision.session.sessionId
                        coroutineContext.ensureActive()
                        if (recoveryContentGeneration != contentLoadGeneration) return@launch
                        val transportMountNonce = nextTypedSubtitleMountNonce(returnedSubtitleIdentity)
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
                                videoQualities = authoritativePlaybackQualityOptions(
                                    available = decision.plan.availableQualities,
                                    selectedLabel = qualityPreference
                                        ?: qualityOverride
                                        ?: preferredQuality
                                        ?: PlaybackQuality.Auto.wireValue,
                                ),
                                container = effectiveContainer,
                                duration = effectiveDuration,
                                serverDuration = effectiveDuration,
                                subtitleUrls = effectiveSubtitleUrls,
                                committedSubtitleIdentity = returnedSubtitleIdentity,
                                chapters = effectiveVersion?.chapters.orEmpty().ifEmpty {
                                    if (effectiveFileId == fileId) state.chapters else emptyList()
                                },
                                startPosition = remountPosition.playerPositionSeconds,
                                position = remountPosition.sourcePositionSeconds,
                            )
                        }
                        val recoveredState = _uiState.value
                        subtitleTransactions.resetContent(
                            context = subtitlePlaybackContext(
                                state = recoveredState,
                                capabilities = decision.capabilities,
                                clientPlaybackContext = decision.clientPlaybackContext,
                            ),
                            committedIdentity = returnedSubtitleIdentity,
                        )
                        subtitleTransactions.restoreCommittedLocalMount()
                        Log.i(
                            TAG,
                            "replan_mount restored_source_seconds=${remountPosition.sourcePositionSeconds} " +
                                "player_seconds=${remountPosition.playerPositionSeconds}",
                        )
                    }
                    is VideoSessionStartV3.Terminal -> {
                        val failedSessionId = state.sessionId ?: return@launch
                        val terminalMessage =
                            "Playback unavailable (${decision.reason}): ${decision.message}"
                        cancelPendingCatalogSubtitle()
                        val terminalStillCurrent = sessionLifecycle.stopTerminalSessionIfCurrent(
                            expectedSessionId = failedSessionId,
                            isCurrent = {
                                recoveryContentGeneration == contentLoadGeneration &&
                                    _uiState.value.sessionId == failedSessionId
                            },
                        )
                        if (!terminalStillCurrent) {
                            return@launch
                        }
                        lastAdoptedSessionId = null
                        _uiState.update {
                            it.copy(
                                error = terminalMessage,
                                isLoading = false,
                                isBuffering = false,
                                isPlaying = false,
                                isPaused = true,
                                sessionId = null,
                                playMethod = null,
                                playbackPlan = null,
                                delivery = null,
                                streamUrl = null,
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

    /**
     * Mounted-transport facts from the screen's 500ms poll (the VM stays free
     * of MediaController references — the screen owns the player, the same
     * split as [onPositionChanged]). Read by [mountedSeekableSourceRange] at
     * seek-commit time. Two staleness rules keep the hint honest:
     *
     * - Reports are dropped while [transportMountGate] is suppressing: they
     *   describe the OLD MediaItem, and mapping them through the new plan's
     *   timeline offset would overstate the new transport's extent.
     * - [onTransportMountApplied] clears the facts when a mount wins, so
     *   until the next poll tick reads the new item the hint is absent and
     *   ambiguous targets fall back to a server reanchor.
     *
     * Within one mounted item a slightly stale window end only ever costs an
     * unnecessary reanchor, never a wrongly-native seek.
     */
    fun onPlayerWindowChanged(isSeekable: Boolean, windowEndPlayerMs: Long) {
        if (transportMountGate.suppressPositionReports) return
        playerWindowIsSeekable = isSeekable
        playerWindowEndPlayerMs = windowEndPlayerMs
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
        val mappedDurationSec = if (currentState.playbackPlan != null) {
            // V3 forbids substituting a stream-local engine duration when the
            // plan omitted source.duration_seconds.
            serverDuration ?: 0.0
        } else if (durationMs > 0) {
            timeline?.sourcePositionForPlayer(rawDurationSec) ?: rawDurationSec
        } else {
            0.0
        }
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
                // Offline playback may learn a runtime from Media3. V3's value
                // above is always the server-declared duration or unknown (0).
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
            expectedSessionId = _uiState.value.sessionId,
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

    /**
     * An audio change has committed, so it is now the viewer's choice.
     *
     * Set here rather than when the change was requested: staging, validation,
     * adoption, mount and rollback can all fail, and a flag raised on intent
     * would carry whatever track survived the failure into the next episode as
     * though it had been chosen.
     */
    fun onAudioSelectionCommitted() {
        manualAudioSelectionApplied = true
    }

    // ---- Desired audio -----------------------------------------------------
    //
    // One generation-owned intent instead of several nullable fields racing to
    // decide the same thing. Every entry point -- the detail page's launch
    // pick, a persisted fingerprint, the HUD, the remote -- writes here, and a
    // single resolver reconciles it against each track snapshot.

    private var desiredAudioGeneration = if (initialAudioTrackIndex != null) 1L else 0L

    /** Monotonic; makes each local-selection request distinct for StateFlow. */
    private var localAudioAttempt = 0L

    /** The audio the viewer wants, as a CATALOG ordinal. */
    private var desiredAudio: DesiredAudio? = initialAudioTrackIndex?.let {
        // The detail page's pick reaches the server in the start request, but a
        // direct-play stream carrying every audio track still lets Media3 pick
        // its own default — so choosing Dutch and pressing Play mounted English.
        // Seeded as a plain value, NOT through setDesiredAudio: an init block
        // running before the flow below is declared would dereference null.
        DesiredAudio(
            generation = 1L,
            catalogOrdinal = it,
            // A fresh detail-page pick carries to the next episode; a durable
            // value seeded onto that page is a restore and must not.
            explicit = launchArgs.initialAudioPickedThisSession,
            fileId = launchArgs.preferredFileId,
        )
    }

    private val _pendingLocalAudioSelection = MutableStateFlow<LocalAudioSelection?>(null)

    /**
     * A mounted track the screen should select on the player directly.
     *
     * The ViewModel has no player handle. The generation lets a stale
     * acknowledgement be ignored: rapid Dutch -> English -> Dutch would
     * otherwise collapse into indistinguishable requests.
     */
    val pendingLocalAudioSelection: StateFlow<LocalAudioSelection?> =
        _pendingLocalAudioSelection.asStateFlow()

    private fun catalogAudioTracks(state: UiState): List<AudioTrack> = state.fileVersions
        .firstOrNull { it.fileId == (state.selectedFileId ?: state.mediaFileId) }
        ?.audioTracks
        .orEmpty()

    /**
     * Records what the viewer wants. A newer intent always supersedes an older
     * one and voids any request still in flight for it, so a late match can
     * never revert a choice made since.
     */
    private fun setDesiredAudio(catalogOrdinal: Int, explicit: Boolean) {
        // An explicit choice claims the durable restore. Otherwise the pending
        // fingerprint is resolved on a later callback, mints a NEWER generation
        // for an OLDER decision, and overwrites the pick just made — generation
        // order would encode processing order, not decision order.
        if (explicit) pendingPersistedAudioFingerprint = null
        desiredAudioGeneration += 1
        val state = _uiState.value
        desiredAudio = DesiredAudio(
            generation = desiredAudioGeneration,
            catalogOrdinal = catalogOrdinal,
            explicit = explicit,
            fileId = state.selectedFileId ?: state.mediaFileId,
        )
        _pendingLocalAudioSelection.value = null
        _uiState.update {
            it.copy(desiredAudioOrdinal = catalogOrdinal, desiredAudioConfirmed = false)
        }
        _uiState.value.audioTracks.takeIf { it.isNotEmpty() }?.let(::reconcileDesiredAudio)
    }

    /**
     * Drives the desired audio towards the player on every track snapshot.
     *
     * The intent is deliberately NOT cleared once read. An empty or partial
     * first callback used to discard it permanently, which reproduced the
     * original bug: choose Dutch, get English. It stays live until it is
     * satisfied or superseded, and because it stays live it doubles as the
     * re-application mechanism -- a remount installs a new MediaTrackGroup and
     * the override was bound to the old one, so a confirmed choice has to be
     * applied again rather than assumed to survive.
     */
    private fun reconcileDesiredAudio(audio: List<PlayerTrackEntry>) {
        val desired = desiredAudio ?: return
        val state = _uiState.value
        val action = reconcileDesiredAudioAction(
            desired = desired,
            activeFileId = state.selectedFileId ?: state.mediaFileId,
            catalog = catalogAudioTracks(state),
            mounted = audio.map { it.toMountedAudioTrack() },
            selectedOrdinal = audio.firstOrNull { it.isSelected }?.index,
            planAudioOrdinal = state.playbackPlan?.selectedTracks?.audioIndex,
        )
        when (action) {
            AudioReconcileAction.None -> Unit

            AudioReconcileAction.DropForeignFile -> {
                desiredAudio = null
                _pendingLocalAudioSelection.value = null
                _uiState.update {
                    it.copy(desiredAudioOrdinal = null, desiredAudioConfirmed = false)
                }
            }

            AudioReconcileAction.Confirm -> {
                // Dropped first: the collector would otherwise replay a stale
                // ordinal against a replacement backend.
                _pendingLocalAudioSelection.value = null
                confirmDesiredAudio(desired)
            }

            is AudioReconcileAction.Apply -> {
                localAudioAttempt += 1
                // Reapplying is not a confirmed state: the row must stop
                // claiming the track until the player is back on it.
                if (desired.confirmed) desiredAudio = desired.copy(confirmed = false)
                _uiState.update { it.copy(desiredAudioConfirmed = false) }
                _pendingLocalAudioSelection.value = LocalAudioSelection(
                    generation = desired.generation,
                    catalogOrdinal = desired.catalogOrdinal,
                    targetOrdinal = action.targetOrdinal,
                    attempt = localAudioAttempt,
                )
            }
        }
    }

    /** The player is on the wanted track: only now is it the viewer's choice. */
    private fun confirmDesiredAudio(desired: DesiredAudio) {
        if (desired.confirmed) return
        desiredAudio = desired.copy(confirmed = true)
        _uiState.update {
            it.copy(desiredAudioOrdinal = desired.catalogOrdinal, desiredAudioConfirmed = true)
        }
        // A launch or persisted intent is a restore, not a fresh decision, so it
        // must not mark the session as carrying an explicit pick for episode
        // carry-over.
        if (desired.explicit) {
            onAudioSelectionCommitted()
            persistDesiredAudio(desired.catalogOrdinal)
        }
    }

    private fun persistDesiredAudio(catalogOrdinal: Int) {
        val state = _uiState.value
        viewModelScope.launch {
            val context = subtitlePlaybackContext(state)
            val scope = context.writeScope ?: return@launch
            val fileId = context.mediaFileId ?: return@launch
            runCatching {
                userItemStatePort.recordTrackSelection(
                    scope = scope,
                    contentId = context.contentId,
                    fileId = fileId,
                    audioUpdate = tvAudioTrackPersistenceUpdate(
                        committedAudioTrackIndex = catalogOrdinal,
                        audioTracks = context.audioTracks,
                    ),
                    // Untouched: this path changed audio only.
                    subtitleUpdate = TrackSelectionFingerprintUpdate.Preserve,
                )
            }
        }
    }

    /**
     * The screen has shown [TvPlayerViewModel.UiState.subtitleFailureMessage].
     *
     * Cleared on acknowledgement rather than on a timer so the same failure
     * cannot be reported twice, and so a later failure with identical text
     * still surfaces.
     */
    fun onSubtitleFailureShown(shownId: Long) {
        _uiState.update {
            // Acknowledged by ID, not by text. Two failures can carry the same
            // words — a mount deadline reported twice reads identically — and
            // comparing strings would let an old acknowledgement clear a new
            // failure that merely said the same thing. Text is what the viewer
            // reads; it was never an identity.
            // The message clears; the id does NOT reset. Resetting the counter
            // let a later re-emission of an old failure manufacture id 1 again
            // and replay something already dismissed.
            if (it.subtitleFailureId == shownId) it.copy(subtitleFailureMessage = null) else it
        }
    }

    /**
     * Bounded recovery has given up and the picture is not coming back.
     *
     * The detector reports Failed exactly once and then goes quiet forever, so
     * without surfacing it the viewer is left with advancing audio over a
     * frozen frame, no message, and no reason to think pressing anything would
     * help. Telemetry recorded this; nobody told the person watching.
     */
    fun onPlaybackRecoveryExhausted() {
        _uiState.update {
            if (it.error != null) it else it.copy(
                error = "Playback stopped responding. Press Back and try again.",
            )
        }
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

    /**
     * Position the in-flight quick-skip burst started from, or null when no
     * burst is pending.
     *
     * Read straight after [onSkipBy] so the skip chip can report the burst
     * TOTAL — three fast forward presses coalesce into one +90s seek, and
     * labelling that "+30s" three times is the only reason the coalescing
     * looks like a dropped press rather than a deliberate one.
     */
    val quickSkipBurstOriginSec: Double?
        get() = quickSkipAccumulator.pending?.let { quickSkipOriginMs / 1_000.0 }

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

    /**
     * Commits a settled seek target (source time) onto the active transport.
     *
     * Routing ladder: with no session/plan yet the target parks in
     * [pendingNativeSeekAfterMount] until the mount wins; with seek recovery
     * or a mount in flight it queues as a reanchor; otherwise
     * [decideSeek][org.siloserver.silo.common.player.seek.decideSeek] picks
     * between a native `Player.seekTo` (fed the mounted transport's proven
     * extent from [mountedSeekableSourceRange]) and a protocol-V3 server
     * reanchor. A missing plan timeline falls through to a raw player seek.
     */
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
        val mountedSeekableSourceRange = mountedSeekableSourceRange(state)
        when (
            val decision = state.playbackPlan?.timeline?.decideSeek(
                targetSourceSec,
                mountedSeekableSourceRange,
            )
        ) {
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
                        "target_player_seconds=${decision.targetPlayerPositionSeconds}" +
                        (mountedSeekableSourceRange?.let {
                            " mounted_source_range=${it.start}..${it.endInclusive}"
                        } ?: ""),
                )
                seekRequestChannel.trySend(decision.targetPlayerPositionSeconds)
            }
            null -> seekRequestChannel.trySend(targetSourceSec)
        }
    }

    /**
     * Source-time extent the currently mounted transport provably covers, or
     * null when that cannot be proven. Derived from the Media3 window the
     * screen polls: a seekable window with a known length can serve any
     * position it spans as a plain `Player.seekTo`. That covers append-only
     * (growing) HLS manifests — whose window end is the produced head — and
     * completed/indexed streams, while a growing progressive copy remux has
     * no known length and stays excluded. This is what lets quick skips ride
     * already-mounted content instead of re-anchoring through the server.
     */
    private fun mountedSeekableSourceRange(state: UiState): ClosedRange<Double>? {
        val timeline = state.playbackPlan?.timeline ?: return null
        if (!playerWindowIsSeekable || playerWindowEndPlayerMs < 0) return null
        val endSourceSec = timeline.sourcePositionForPlayer(playerWindowEndPlayerMs / 1000.0)
            ?: return null
        return timeline.timelineOffsetSeconds..endSourceSec
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
        val version = before.fileVersions.firstOrNull { it.fileId == actualFileId }
        val effectiveSubtitleUrls = enrichAuthoritativePlaybackSubtitleChoices(
            catalogTracks = version?.subtitleTracks.orEmpty(),
            plannedTracks = decision.session.subtitleUrls.orEmpty(),
        )
        val returnedSubtitleIndex = decision.plan.resolvedSelectedSubtitleIndex()
        val returnedSubtitleIdentity = returnedSubtitleIndex
            ?.let { index -> effectiveSubtitleUrls.singleOrNull { it.index == index } }
            ?.let(::tvSubtitleIdentity)
            ?: SubtitleIdentity.Off
        val returnedAudioIndex = decision.plan.selectedTracks.audio?.index
            ?: decision.session.audioTrackIndex
        val committedQualityPreference = qualityOverride
            ?: preferredQuality
            ?: PlaybackQuality.Auto.wireValue
        seekRecoveryRollbackInvalidated = false
        if (!isCurrentSeekRecovery(request)) return
        val adopted = sessionLifecycle.adoptActiveSessionIfCurrent(
            params = StartParams(
                contentId = contentId,
                fileId = actualFileId,
                capabilities = decision.capabilities,
                audioTrackIndex = returnedAudioIndex,
                subtitleTrackIndex = returnedSubtitleIndex ?: -1,
                qualityPreference = committedQualityPreference,
                startPosition = sourcePosition,
                clientPlaybackContext = decision.clientPlaybackContext,
            ),
            session = decision.session.copy(subtitleUrls = effectiveSubtitleUrls),
            isCurrent = { isCurrentSeekRecovery(request) },
        )
        // Deliberately no stop on refusal. A seek re-anchor is validated to
        // reuse the SAME session id — the manager rejects any response that
        // changes it — so this id names the session still playing, not a
        // disposable candidate. Refusal normally means a newer seek was queued,
        // and that seek needs this very session as its base; stopping it here
        // left the manager with no active attempt to re-anchor.
        if (!adopted) return
        lastAdoptedSessionId = decision.session.sessionId
        if (!isCurrentSeekRecovery(request)) return
        val transportMountNonce = nextTypedSubtitleMountNonce(returnedSubtitleIdentity)
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
                subtitleUrls = effectiveSubtitleUrls,
                committedSubtitleIdentity = returnedSubtitleIdentity,
            )
        }
        val recoveredState = _uiState.value
        subtitleTransactions.resetContent(
            context = subtitlePlaybackContext(
                state = recoveredState,
                capabilities = decision.capabilities,
                clientPlaybackContext = decision.clientPlaybackContext,
            ),
            committedIdentity = returnedSubtitleIdentity,
        )
        subtitleTransactions.restoreCommittedLocalMount()
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
            // Through the same intent as every other entry point. Going straight
            // to a replan left an older launch/persisted/HUD intent authoritative,
            // and it would reapply itself afterwards and undo the remote pick.
            setDesiredAudio(selected, explicit = true)
            if (matchMountedAudioTrack(
                    catalogAudioTracks(state).getOrNull(selected) ?: return,
                    state.audioTracks.map { it.toMountedAudioTrack() },
                ) != null
            ) {
                return
            }
            playbackMutationFence.beginReplan()
            launchSubtitleTransaction(state) {
                subtitleTransactions.selectAudio(selected)
            }
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
            launchSubtitleTransaction(_uiState.value) {
                subtitleTransactions.select(identity)
            }
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
    fun applyUpdatedMarkers(intro: TimeRange?, credits: TimeRange?, recap: TimeRange?, preview: TimeRange?) {
        _uiState.update { it.copy(intro = intro, credits = credits, recap = recap, preview = preview) }
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
                        // Up Next owns the screen: the HUD is rendered purely on
                        // hudOpen, so leaving it set draws the tab row and panes
                        // underneath the overlay.
                        hudOpen = false,
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
            it.copy(showNextUp = true, hudOpen = false, nextUpCountdownSeconds = null)
        }
    }

    private fun commitApproachingEnd(next: NextEpisodeState, videoEnded: Boolean) {
        autoAdvanceHandled = true
        pendingApproachingEndVideoEnded = null
        // Threshold 0 (or less) = off: never gate, always allow auto-countdown.
        val threshold = passOutThreshold.value
        val passOutGated = threshold > 0 && autoAdvanceCount >= threshold
        val autoCountdown = autoPlayNextEnabled.value && !passOutGated
        val current = _uiState.value
        // Pre-end commits anchor the countdown to the remaining playback time
        // (see startNextUpCountdown); only an at-end commit uses the wall clock.
        val initialCountdown = when {
            !autoCountdown -> null
            videoEnded -> NEXT_UP_COUNTDOWN_SECONDS
            else -> ceil((current.duration - current.position).coerceAtLeast(0.0)).toInt()
        }

        _uiState.update {
            it.copy(
                showNextUp = true,
                hudOpen = false,
                nextUpVideoEnded = videoEnded,
                nextUpCountdownSeconds = initialCountdown,
                // The ring draws remaining/total, so a pre-end countdown longer
                // than the wall-clock default has to carry its own total or the
                // ring renders past full.
                nextUpCountdownTotalSeconds = initialCountdown?.coerceAtLeast(1)
                    ?: NEXT_UP_COUNTDOWN_SECONDS,
            )
        }
        if (autoCountdown) startNextUpCountdown()
    }

    private fun startNextUpCountdown() {
        nextUpCountdownJob?.cancel()
        nextUpCountdownJob = viewModelScope.launch {
            // Two anchors, matching phone and tvOS:
            //  - Card committed BEFORE the end (credits crossing): the countdown
            //    mirrors the remaining playback time, so it freezes on pause,
            //    grows on a backward seek, and the advance fires only once the
            //    player reports the stream ended. A fixed wall clock here cut off
            //    the final scene of anything whose credits marker sits more than
            //    ten seconds from the actual end.
            //  - Card committed AT the end (stream ended with no earlier
            //    crossing): there is no playback left to anchor to, so a short
            //    wall-clock countdown gives the viewer a window to cancel.
            val startedAtEnd = _uiState.value.nextUpVideoEnded
            var wallRemaining = NEXT_UP_COUNTDOWN_SECONDS
            while (true) {
                delay(1_000)
                // Bail if something dismissed the overlay underneath us.
                if (!_uiState.value.showNextUp) return@launch
                val remaining = if (startedAtEnd) {
                    wallRemaining -= 1
                    wallRemaining.coerceAtLeast(0)
                } else {
                    val state = _uiState.value
                    ceil((state.duration - state.position).coerceAtLeast(0.0)).toInt()
                }
                _uiState.update {
                    if (!it.showNextUp) {
                        it
                    } else {
                        it.copy(
                            nextUpCountdownSeconds = remaining,
                            // A backward seek can push the remaining time past
                            // where the ring started; grow the total with it.
                            nextUpCountdownTotalSeconds =
                                maxOf(it.nextUpCountdownTotalSeconds, remaining, 1),
                        )
                    }
                }
                if (!_uiState.value.showNextUp) return@launch
                val playbackEnded =
                    if (startedAtEnd) wallRemaining <= 0 else _uiState.value.nextUpVideoEnded
                if (!playbackEnded) continue
                // Automatic countdown-expiry advance: increment the pass-out streak
                // so a long unattended binge eventually trips the "still watching?"
                // gate. An explicit Play Now (below) resets the streak instead.
                advanceToNextEpisode(nextAutoAdvanceCount = autoAdvanceCount + 1)
                return@launch
            }
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
        _uiState.update { it.copy(showNextUp = false, nextUpCountdownSeconds = null) }
        val activeVersion = state.fileVersions.firstOrNull { version ->
            version.fileId == (state.selectedFileId ?: state.mediaFileId)
        }
        val handoff = captureTvEpisodeSelectionHandoff(
            activeVersion = activeVersion,
            committedSubtitleIdentity = state.committedSubtitleIdentity,
            catalogSubtitles = state.subtitleUrls,
            selectedAudioTrack = state.audioTracks.firstOrNull { it.isSelected },
            // The catalog row by ordinal — audio's contract — but the viewer's
            // CONFIRMED choice first. A direct-play local switch changes the
            // mounted track without replanning, so the plan can still name the
            // previous audio: reading it alone handed the next episode the
            // track the viewer had just switched away from, while
            // manualAudioSelectionApplied said a choice had been made.
            selectedCatalogAudio = (
                state.desiredAudioOrdinal?.takeIf { state.desiredAudioConfirmed }
                    ?: state.playbackPlan?.selectedTracks?.audioIndex
                )?.let { activeVersion?.audioTracks?.getOrNull(it) },
            hasExplicitAudioSelection = manualAudioSelectionApplied,
            hasExplicitSubtitleSelection = manualSubtitleSelectionApplied,
        )
        _playNextRequests.tryEmit(PlayNextRequest(next.contentId, nextAutoAdvanceCount, handoff))
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
        reconcileDesiredAudio(audio)
        resolveSubtitleRemountReselection(subtitle)
        // The detail-page explicit pick resolves FIRST so a resolved pick can
        // suppress the persisted/auto fallback (and an unresolvable one lets it
        // proceed) before persisted reads its fingerprint.
        resolvePendingInitialSubtitle(subtitle)
        if (_pendingRemoteAudioIndex.value != null) {
            pendingPersistedAudioFingerprint = null
        }
        resolvePendingPersistedTrackSelection(audio)
        retryPendingRemoteTrackIntents()
        resolveAutoPreferredTextSubtitle(audio, subtitle)
        reconcileExternallySelectedSubtitle(subtitle)
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
        reconcileDesiredAudio(audio)
        resolveSubtitleRemountReselection(subtitle)
        // The detail-page explicit pick resolves FIRST so a resolved pick can
        // suppress the persisted/auto fallback (and an unresolvable one lets it
        // proceed) before persisted reads its fingerprint.
        resolvePendingInitialSubtitle(subtitle)
        if (_pendingRemoteAudioIndex.value != null) {
            pendingPersistedAudioFingerprint = null
        }
        resolvePendingPersistedTrackSelection(audio)
        retryPendingRemoteTrackIntents()
        resolveAutoPreferredTextSubtitle(audio, subtitle)
        reconcileExternallySelectedSubtitle(subtitle)
    }

    /**
     * Restores the persisted AUDIO choice once tracks land.
     *
     * The subtitle half of this used to live here too, resolving a saved
     * fingerprint onto a Media3 ordinal and pushing it straight at the player.
     * The durable subtitle preference is now restored through the transaction
     * adapter at load (`restoreFreshPreference`), which is why the fingerprint
     * it read was already being cleared unconditionally on every load — it
     * could never fire again.
     */
    private fun resolvePendingPersistedTrackSelection(audio: List<PlayerTrackEntry>) {
        pendingPersistedAudioFingerprint?.let { fingerprint ->
            if (audio.isNotEmpty()) {
                // Resolve to a CATALOG ordinal and hand it to the desired-audio
                // resolver. This used to resolve a MOUNTED ordinal and push it
                // into _pendingRemoteAudioIndex, which is read back as a catalog
                // ordinal — so whenever mounted and catalog order disagreed it
                // restored the wrong language.
                //
                // The fingerprint is kept when it does not resolve: clearing it
                // on a partial first snapshot silently abandoned the restore.
                resolveAudioTrackOrdinal(catalogAudioTracks(_uiState.value), fingerprint)
                    ?.takeIf { it >= 0 }
                    ?.let { catalogOrdinal ->
                        pendingPersistedAudioFingerprint = null
                        setDesiredAudio(catalogOrdinal, explicit = false)
                    }
            }
        }
    }

    private fun resolveAutoPreferredTextSubtitle(
        audio: List<PlayerTrackEntry>,
        subtitle: List<PlayerTrackEntry>,
    ) {
        // Fallback ONLY. Any launch that carried a decision (detail-page pick or
        // its Auto preview, an episode intent, a recovery restore) has already
        // applied it, and re-deciding here is exactly the bug: this path can
        // only see what Media3 has mounted.
        if (launchSubtitleSelectionApplied) return
        // A launch pick that has not resolved YET is still the decision: it
        // retries across the next few track callbacks (a late sidecar, a
        // second Media3 snapshot). Deciding here in the meantime mounted the
        // auto pick over the viewer's — seen on a Shield where the detail-page
        // "English SRT" resolved a callback late and Auto had already put the
        // Forced track on. resolvePendingInitialSubtitle clears the index when
        // it resolves or gives up, and both happen before this runs on the
        // same callback, so nothing is stranded.
        if (pendingInitialSubtitleIndex != null) return
        // manualSubtitleSelectionApplied is set when a persisted choice OR a
        // RESOLVED explicit detail-page pick was applied — that (not the bare
        // launch intent) is what suppresses auto. An explicit pick that failed to
        // resolve leaves the flag clear, so auto still runs instead of stranding
        // subtitles Off.
        if (manualSubtitleSelectionApplied) return
        // Reached only by launches with no handoff at all (deep link, cast,
        // remote/realtime start). The latch stays because the fallback still
        // runs on every onTracksChanged: without it a second snapshot would
        // re-run auto over a longer track list and override a selection the
        // viewer has since made.
        if (autoTextSubtitleSelectionAttempted) return
        // Wait for the player to report SOMETHING: an empty snapshot carries no
        // selected audio language for the resolver to rank a subtitle against.
        if (audio.isEmpty() && subtitle.isEmpty()) return

        val state = _uiState.value
        // Media3 only knows what is MOUNTED. A launch whose server inventory is
        // all external sidecars has an empty text-track list until one of them
        // is mounted, so standing down on that alone left a deep link, cast or
        // remote start with subtitles Off even for an Always profile — with the
        // intended track sitting in subtitleUrls. Stand down only when neither
        // inventory offers anything to choose from.
        if (subtitle.isEmpty() && state.subtitleUrls.isEmpty()) return
        // Resolve over the SERVER inventory, not the mounted text tracks: an
        // external sidecar the initial plan did not mount is invisible to
        // Media3, which is how "Auto - <external SRT>" started playing the
        // embedded PGS track instead. The adapter mounts the winner if it is
        // not mounted yet — a legitimate replan for a launch nobody decided.
        val identity = resolveTvAutoSubtitleIdentity(
            audioTracks = audio,
            subtitleTracks = subtitle,
            subtitleRows = state.subtitleUrls,
            preferredLanguage = state.preferredTextLanguage,
            subtitleMode = state.preferredSubtitleMode,
            showForced = state.showForcedSubtitles,
        )
        autoTextSubtitleSelectionAttempted = true
        SubDiag.log("AUTO subtitle -> $identity")
        applyAutomaticSubtitleSelection(identity, state)
    }

    /**
     * Drives an app-derived selection through the adapter — the single owner —
     * rather than at the player directly. Selecting behind the adapter's back
     * is what left the HUD reporting "Off" over subtitles plainly on screen.
     */
    private fun applyAutomaticSubtitleSelection(
        identity: SubtitleIdentity,
        state: UiState,
        priority: TvSubtitleMountPriority = TvSubtitleMountPriority.Auto,
    ) {
        // Provenance is recorded BEFORE the already-committed shortcut below,
        // which publishes no transaction and returns. Exit persistence reads
        // this marker to tell an app-made choice from the viewer's, so leaving
        // it unset there wrote the plan's own pick — commonly the detail row's
        // "Auto - None" — back as a durable manual preference, and every later
        // launch restored that instead of re-running Auto.
        autoSelectedSubtitleIdentity = identity
        if (identity == state.committedSubtitleIdentity && state.pendingSubtitleIdentity == null) {
            // Committed is what the adapter BELIEVES is on. At load it is seeded
            // straight from the plan (resetContent) before the player has
            // selected any text track, so "already committed" is not evidence
            // the track is mounted — the launch handoff of a plan-selected
            // sidecar reached this line and returned, and nobody ever told the
            // player. Ask the adapter to mount its committed identity through
            // the same local-restore path the replan/recovery loads use.
            if (identity != SubtitleIdentity.Off && !playerHasSelectedSubtitle(identity, state)) {
                SubDiag.log("AUTO committed-but-unmounted -> restoreCommittedLocalMount $identity")
                nextSubtitleMountPriority = priority
                subtitleTransactions.restoreCommittedLocalMount()
            }
            return
        }
        autoSubtitleSelectionInFlight = true
        nextSubtitleMountPriority = priority
        launchSubtitleTransaction(state) {
            subtitleTransactions.selectAuto(identity)
        }
    }

    /** True when the player's currently selected text track carries [identity]. */
    private fun playerHasSelectedSubtitle(identity: SubtitleIdentity, state: UiState): Boolean {
        val selected = state.subtitleTracks.firstOrNull { it.isSelected } ?: return false
        return tvMountedSubtitleIdentity(selected, state.subtitleTracks, state.subtitleUrls) == identity
    }

    /**
     * Applies the detail page's pre-selected subtitle through the adapter.
     *
     * Restore authority, and deliberately NOT persisted: the pick arrives as a
     * launch argument the detail screen already owns the preference for, so
     * re-writing it here could only ever overwrite it with a stale echo. It is
     * still a resolved decision, which is why it outranks the auto heuristics.
     */
    private fun applyRestoredSubtitleSelection(identity: SubtitleIdentity) {
        applyAutomaticSubtitleSelection(
            identity = identity,
            state = _uiState.value,
            priority = TvSubtitleMountPriority.Restore,
        )
    }

    /**
     * Safety net, not the mechanism: if Media3 reports a text track selected
     * whose identity is not the adapter's committed one, something outside the
     * app enabled it (device caption settings, a selector quirk, a renderer
     * default). Adopt it so the HUD cannot disagree with the screen, and say so
     * loudly — reaching this means an authority we thought we had removed is
     * still selecting subtitles.
     */
    private fun reconcileExternallySelectedSubtitle(subtitle: List<PlayerTrackEntry>) {
        val state = _uiState.value
        // Converge the in-flight latch on observed state as well as on the
        // adapter snapshot: an automatic selection the adapter treats as a
        // no-op publishes nothing, and a latch that only the snapshot could
        // clear would disable this safety net for the rest of the session.
        if (autoSubtitleSelectionInFlight &&
            state.pendingSubtitleIdentity == null &&
            state.committedSubtitleIdentity == autoSelectedSubtitleIdentity
        ) {
            autoSubtitleSelectionInFlight = false
        }
        val observed = tvExternalSubtitleAdoption(
            subtitleTracks = subtitle,
            subtitleRows = state.subtitleUrls,
            committedIdentity = state.committedSubtitleIdentity,
            pendingIdentity = state.pendingSubtitleIdentity,
            selectionInFlight = autoSubtitleSelectionInFlight ||
                subtitleRemountReselection.hasPendingOwner,
        ) ?: return

        Log.w(
            TV_SUBTITLE_LOG_TAG,
            "Adopting externally selected text track: " +
                "observed=$observed committed=${state.committedSubtitleIdentity}",
        )
        applyAutomaticSubtitleSelection(observed, state)
    }

    /**
     * Apply the detail screen's pre-selected subtitle once the player's tracks
     * land.
     *
     * -1 = Off, applied immediately.
     *
     * A positive value is a COMBINED-space subtitle index (externals first,
     * embedded after — the identity mounted subtitle_urls carry and
     * subtitle_track_index requests resolve), not Media3's flattened
     * text-track ordinal. Resolve it through the mounted server subtitle
     * metadata first so embedded CEA-608 or other player-discovered tracks do
     * not shift the target, then hand the resolved track to the transaction
     * adapter as a typed identity — it is the only thing that may mount one.
     */
    private fun resolvePendingInitialSubtitle(subtitle: List<PlayerTrackEntry>) {
        val index = pendingInitialSubtitleIndex ?: return
        val autoResolved = pendingInitialSubtitleAutoResolved
        if (index == -1) {
            pendingInitialSubtitleIndex = null
            // Off from the detail page is a resolved decision — the row showed
            // it — so it suppresses the auto fallback. Only an EXPLICIT Off is
            // also a manual selection; an "Auto - None" preview is not.
            launchSubtitleSelectionApplied = true
            if (!autoResolved) manualSubtitleSelectionApplied = true
            applyRestoredSubtitleSelection(SubtitleIdentity.Off)
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
        // Suppress the auto fallback ONLY when the explicit pick actually
        // resolves onto a mounted track. An unresolvable pick leaves the manual
        // flag clear, so it falls through to auto instead of being silently
        // dropped (subtitles Off all session).
        if (resolved != null) {
            pendingInitialSubtitleIndex = null
            pendingInitialSubtitleAttempts = 0
            launchSubtitleSelectionApplied = true
            if (!autoResolved) manualSubtitleSelectionApplied = true
            subtitle.firstOrNull { it.index == resolved }
                ?.let { track ->
                    applyRestoredSubtitleSelection(
                        tvMountedSubtitleIdentity(track, subtitle, _uiState.value.subtitleUrls),
                    )
                }
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

    internal fun onSubtitleSelectionApplied(request: TvSubtitleMountRequest) {
        val owner = request.owner
        subtitleRemountReselection.acknowledgeResolved(owner.generation)
        subtitleTransactions.reportMountedSelection(
            identity = owner.identity,
            selected = true,
            snapshotKey = "tv-mounted:${owner.generation}:${request.trackIndex}",
            settled = true,
        )
    }

    /**
     * Selects audio by ORDINAL into the active version's `audio_tracks`, which
     * is the server's contract for audio (see [selectedServerAudioTrackIndex]).
     *
     * The ordinal goes to the replan untouched. It used to be mapped through
     * `AudioTrack.index`, a field the server never sends for audio, so every
     * pick collapsed to 0.
     */
    fun selectAudioOption(catalogOrdinal: Int) {
        val state = _uiState.value
        val catalog = catalogAudioTracks(state)
        if (catalogOrdinal !in catalog.indices) return

        // If the mounted stream already carries this track, switch it on the
        // player. A replan would rebuild the whole session to deliver audio the
        // viewer is already receiving -- and because audio selection only ever
        // staged a replan, a direct-play stream carrying several audio tracks
        // never actually switched: the plan moved, the renderer did not.
        // Record the intent first: the resolver applies it locally when the
        // mounted stream already carries the track, which is the common
        // direct-play case and needs no replan at all.
        setDesiredAudio(catalogOrdinal, explicit = true)
        if (matchMountedAudioTrack(
                catalog[catalogOrdinal],
                state.audioTracks.map { it.toMountedAudioTrack() },
            ) != null
        ) {
            return
        }

        // manualAudioSelectionApplied is deliberately NOT raised here: it is
        // raised on commit via CommittedSubtitle.audioPreferenceSpecified, so a
        // request that fails or rolls back never becomes an episode preference.
        playbackMutationFence.beginReplan()
        launchSubtitleTransaction(state) {
            subtitleTransactions.selectAudio(catalogOrdinal)
        }
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
        // The viewer is choosing: drop the automatic marker so this commit
        // writes the durable per-item preference.
        autoSelectedSubtitleIdentity = null
        autoSubtitleSelectionInFlight = false
        playbackMutationFence.beginReplan()
        launchSubtitleTransaction(_uiState.value) {
            subtitleTransactions.select(identity)
        }
    }

    fun selectSubtitleOption(serverIndex: Int) {
        if (serverIndex == -1) {
            selectSubtitleOption(SubtitleIdentity.Off)
            return
        }
        val row = _uiState.value.subtitleUrls.firstOrNull { it.index == serverIndex } ?: return
        selectSubtitleOption(tvSubtitleIdentity(row))
    }

    internal fun onSubtitleSelectionFailed(request: TvSubtitleMountRequest) {
        val owner = request.owner
        subtitleRemountReselection.acknowledgeResolved(owner.generation)
        Log.w(
            TV_SUBTITLE_LOG_TAG,
            "Subtitle mount rejected by the player: track=${request.trackIndex} " +
                "identity=${owner.identity}",
        )
        subtitleTransactions.reportMountedSelection(
            identity = owner.identity,
            selected = false,
            snapshotKey = "tv-mount-failed:${owner.generation}:${request.trackIndex}",
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
        subtitleRemountReselection.releaseResolved()
        subtitleSnapshotSettlement.reset()
    }

    private fun resolveSubtitleRemountReselection(subtitle: List<PlayerTrackEntry>) {
        val snapshotKey = subtitle
            .takeIf(List<PlayerTrackEntry>::isNotEmpty)
            ?.joinToString("|") { "${it.index}:${it.trackId}:${it.isSelected}" }
        when (
            val event = subtitleRemountReselection.consume(
                subtitleTracks = subtitle,
                subtitleRows = _uiState.value.subtitleUrls,
                snapshotKey = snapshotKey,
                settled = subtitleSnapshotSettlement.observe(subtitle),
            )
        ) {
            is TvSubtitleRemountEvent.Select -> _subtitleMountRequests.tryEmit(
                TvSubtitleMountRequest(owner = event.owner, trackIndex = event.trackIndex),
            )
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
            it.copy(scrubPreviewSec = clampTvScrubPreview(sec, it.duration))
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

    /**
     * Whether the transport overlay was on screen when the HUD opened.
     *
     * [openHUD] forces `showControls` true, which is invisible while the HUD is
     * up — the overlay is gated on `!hudOpen` — but closing has to put the
     * chrome back the way it found it. Without this, a HUD opened from clean
     * playback closed onto a transport overlay nobody asked for, and Back had
     * to be pressed twice to get back to the picture.
     */
    private var controlsVisibleBeforeHud = false

    fun openHUD() {
        // Only record on a real open. A second openHUD while the HUD is already
        // up would otherwise capture the forced `true` and lose the real origin.
        if (!_uiState.value.hudOpen) {
            controlsVisibleBeforeHud = _uiState.value.showControls
        }
        Log.d(TAG, "hud open (controlsBefore=$controlsVisibleBeforeHud, wasOpen=${_uiState.value.hudOpen})")
        _uiState.update { it.copy(hudOpen = true, showSubtitleMenu = false, showControls = true) }
    }

    fun closeHUD() {
        Log.d(TAG, "hud close (restoreControls=$controlsVisibleBeforeHud, wasOpen=${_uiState.value.hudOpen})")
        _uiState.update {
            it.copy(hudOpen = false, showControls = controlsVisibleBeforeHud)
        }
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
        launchSubtitleTransaction(state) {
            subtitleTransactions.selectQuality(wireValue)
        }
    }

    /**
     * The intro pill's Select: skip the intro (`ask`) or play it after all
     * (`always`'s undo). Returns the seek target in seconds so the screen can
     * call MediaController.seekTo, or null when no pill is showing.
     *
     * Returning the value (instead of seeking internally) keeps the VM free
     * of MediaController references — the screen owns the controller — and is
     * what lets a room route the seek through its transport gate.
     */
    fun onSelectIntroPrompt(): Double? {
        val target = introAutoSkipController.select() ?: return null
        // Pre-write the resolved source position so the credits crossing check
        // treats this as a deliberate jump. The caller routes the actual seek
        // through either the room controller or seekImmediate; the latter owns
        // the pending-position guard for solo playback.
        _uiState.update { it.copy(position = target) }
        return target
    }

    /**
     * Back while the intro pill is showing: take it down and resolve the intro
     * without moving playback. True when a pill was actually dismissed, so the
     * caller consumes the press only then.
     */
    fun onDismissIntroPrompt(): Boolean = introAutoSkipController.dismiss()

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
                    val merged = refreshSubtitles(
                        autoSelectSubtitleId = r.data.subtitle.id,
                        source = TvSubtitleRefreshSource.Download,
                    )
                    _subtitleSearch.update {
                        if (merged) {
                            it.copy(downloadingResultId = null, completedNonce = it.completedNonce + 1)
                        } else {
                            // Downloaded on the server, but we could not list it
                            // back — say so rather than closing as a success.
                            it.copy(
                                downloadingResultId = null,
                                error = "Downloaded, but the subtitle list could not be refreshed.",
                            )
                        }
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
    /**
     * Re-list subtitles after a download or AI job, returning whether it worked.
     *
     * It used to return Unit, so callers bumped completedNonce regardless — and
     * both dialogs read that nonce as "the track merged and was selected" and
     * dismissed themselves. A server-side job that succeeded followed by a
     * failed list request therefore closed as a success with no new subtitle
     * anywhere, which is indistinguishable from the feature not working.
     */
    internal suspend fun refreshSubtitles(
        autoSelectSubtitleId: Int?,
        source: TvSubtitleRefreshSource = TvSubtitleRefreshSource.Realtime,
    ): Boolean {
        val state = _uiState.value
        val mediaFileId = state.mediaFileId ?: return false
        val sessionId = state.sessionId ?: return false
        subtitleTransactions.updatePlaybackContext(subtitlePlaybackContext(state))
        val owner = subtitleTransactions.beginRefresh(source)
        if (state.playbackPlan != null) {
            pendingAuthoritativeSubtitleDownloadId = autoSelectSubtitleId
            val readyRow = autoSelectSubtitleId?.let { id ->
                authoritativeSubtitleReadyRows[sessionId to id]
            }
            if (readyRow != null) {
                val selected = subtitleTransactions.selectFromRefresh(
                    owner,
                    tvSubtitleIdentity(readyRow),
                )
                if (selected) pendingAuthoritativeSubtitleDownloadId = null
            }
            return true
        }
        val downloaded = try {
            when (val r = subtitlesRepository.list(mediaFileId)) {
            is ApiResult.Success -> r.data.subtitles
            is ApiResult.Error -> {
                Log.w(TAG, "refreshSubtitles failed: ${r.code} ${r.message}")
                subtitleTransactions.completeRefreshFailure(owner, r.message)
                return false
            }
            is ApiResult.NetworkError -> {
                Log.w(TAG, "refreshSubtitles network error", r.exception)
                subtitleTransactions.completeRefreshFailure(
                    owner,
                    r.exception.message ?: "Subtitle refresh failed.",
                )
                return false
            }
            }
        } catch (cancellation: CancellationException) {
            subtitleTransactions.cancelRefresh(owner)
            throw cancellation
        }
        val downloadedRows = mergeDownloadedSubtitles(
            existing = state.subtitleUrls,
            downloaded = downloaded,
            sessionId = sessionId,
            serverUrl = state.serverUrl,
        )
        // The adapter's answer, not an assumption. applyRefresh returns false
        // when the refresh lost ownership before it could be applied — so a
        // list request that SUCCEEDED but went stale in flight would otherwise
        // still be reported as merged, and the dialog would close on a track
        // that was never installed.
        return subtitleTransactions.applyRefresh(
            owner = owner,
            subtitleTracks = downloadedRows,
            autoSelectDownloadId = autoSelectSubtitleId,
        )
    }

    /** Applies one exact server-minted V3 inventory row from realtime. */
    internal suspend fun applySubtitleReady(update: PlaybackSubtitleReady): Boolean {
        val state = _uiState.value
        val sessionId = state.sessionId ?: return false
        if (update.sessionId != null && update.sessionId != sessionId) return false
        if (update.mediaFileId != null && update.mediaFileId != state.mediaFileId) return false
        val rows = applyAuthoritativeSubtitleReadyTrack(state.subtitleUrls, update)
        if (rows == null) {
            startProtocolV3Replan(
                classification = "subtitle_inventory_changed",
                notice = "Subtitle inventory changed. Refreshing playback metadata.",
                state = state,
            )
            return false
        }
        subtitleTransactions.updatePlaybackContext(subtitlePlaybackContext(state))
        val owner = subtitleTransactions.beginRefresh(TvSubtitleRefreshSource.Realtime)
        val subtitleId = update.subtitleId
        val added = update.track?.trackId?.let { trackId ->
            rows.singleOrNull { it.serverTrackId == trackId }
        }
        if (subtitleId != null && added != null) {
            authoritativeSubtitleReadyRows[sessionId to subtitleId] = added
        }
        val autoSelectId = subtitleId.takeIf { it == pendingAuthoritativeSubtitleDownloadId }
        val applied = subtitleTransactions.applyRefresh(
            owner = owner,
            subtitleTracks = rows,
            autoSelectDownloadId = autoSelectId,
        )
        if (applied && autoSelectId != null) pendingAuthoritativeSubtitleDownloadId = null
        return applied
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
                    val merged = refreshSubtitles(
                        autoSelectSubtitleId = outcome.resultSubtitleId,
                        source = TvSubtitleRefreshSource.AiCompletion,
                    )
                    _aiTranslate.update {
                        if (merged) {
                            it.copy(phase = AiJobPhase.Idle, completedNonce = it.completedNonce + 1)
                        } else {
                            it.copy(
                                phase = AiJobPhase.Failed(
                                    "Translated, but the subtitle list could not be refreshed.",
                                ),
                            )
                        }
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

    fun onSetIntroSkipMode(value: IntroSkipMode) {
        viewModelScope.launch { playerSettingsStore.setIntroSkipMode(value) }
    }

    fun onSetAutoPlayNext(value: Boolean) {
        viewModelScope.launch { playerSettingsStore.setAutoPlayNext(value) }
    }

    fun onSetHdrEnabled(value: Boolean) {
        viewModelScope.launch { playerSettingsStore.setHdrEnabled(value) }
    }

    /**
     * Applies to local track selection immediately, but the part that matters
     * for a single-track DV file — base layer vs DV delivery — is decided in
     * the server's plan from the capability snapshot sent at load. So once the
     * setting is written, restart the session in place at the current position
     * if the current file is Dolby Vision: the viewer sees the layer they just
     * chose instead of having to back out and resume to get it. A non-DV file
     * has nothing to re-plan and is left alone.
     */
    fun onSetDolbyVisionEnabled(value: Boolean) {
        viewModelScope.launch {
            playerSettingsStore.setDolbyVisionEnabled(value)
            val state = _uiState.value
            if (state.streamUrl == null) return@launch
            val fileId = state.selectedFileId ?: state.mediaFileId
            val currentIsDolbyVision = state.fileVersions
                .firstOrNull { it.fileId == fileId }
                ?.let(org.siloserver.silo.tv.ui.screens.detail.TvPlaybackFormatting::isDolbyVision)
                ?: (state.playbackPlan?.claims?.video?.dolbyVision == true)
            if (!currentIsDolbyVision) return@launch
            Log.i(TAG, "dolby_vision_toggle value=$value restart_in_place file_id=$fileId")
            // "In flight" until the replacement session is adopted AND has
            // frames moving — adoption is quick (~1s) but the viewer's wait is
            // the rebuffer after it, so the cue must outlast that. If the
            // replacement never arrives (the old session is kept on failure),
            // stop claiming progress after a bounded wait.
            val previousSessionId = state.sessionId
            // The replacement publishes isPaused = false (loadContent) and the
            // screen mirrors that to playWhenReady, so changing the setting
            // while paused resumed the video behind the HUD. Carry the
            // pre-switch intent across and re-assert it once the replacement is
            // adopted — the publication that clears it is the same update that
            // clears isLoading.
            val wasPaused = state.isPaused
            _dolbyVisionSwitchInFlight.value = true
            dolbyVisionSwitchWatch?.cancel()
            dolbyVisionSwitchWatch = launch {
                try {
                    withTimeoutOrNull(OUTPUT_SWITCH_FEEDBACK_TIMEOUT_MS) {
                        if (wasPaused) {
                            // A restored pause never reaches isPlaying, so the
                            // cue ends at adoption rather than at first frames.
                            _uiState.first {
                                it.sessionId != previousSessionId && !it.isLoading
                            }
                            setPaused(true)
                        } else {
                            _uiState.first {
                                it.sessionId != previousSessionId &&
                                    !it.isLoading && !it.isBuffering && it.isPlaying
                            }
                        }
                    }
                } finally {
                    _dolbyVisionSwitchInFlight.value = false
                }
            }
            restartSessionInPlace(fileId)
        }
    }

    private val _dolbyVisionSwitchInFlight = MutableStateFlow(false)
    private var dolbyVisionSwitchWatch: Job? = null

    /**
     * True from a Dolby Vision toggle that restarted the session until the
     * replacement is adopted and playing. Drives the row's "Applying…" cue
     * and makes a second press a no-op mid-switch — without disabling the row,
     * which would drop focus off it.
     */
    val dolbyVisionSwitchInFlight: StateFlow<Boolean> = _dolbyVisionSwitchInFlight.asStateFlow()

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

    /**
     * Retained token first, UI second.
     *
     * The token tracks *lifecycle ownership*, which is what teardown has to
     * name, and it moves in both directions: forward at each adoption and at
     * the load publication, back to the predecessor on either rollback path.
     * That is strictly better than UI state here, because the three adoption
     * paths take ownership before they publish and a cancellation in between
     * would otherwise leave teardown naming a session the lifecycle has already
     * let go of.
     */
    private val exitSessionId: String?
        get() = lastAdoptedSessionId ?: _uiState.value.sessionId

    /**
     * Keeps this screen's lifecycle teardown to exactly one stop. Without it,
     * [onCleared]'s deferred stop lands after the *next* episode's start has
     * captured its ownership epoch, bumps stopEpoch, and gets that start
     * rejected as "Playback start was superseded" — auto-advance dying on every
     * episode transition.
     */
    private val lifecycleTeardown = PlaybackTeardownGate(sessionLifecycle)

    private fun prepareSessionExit() {
        contentLoadGeneration++
        episodeSelectionHandoffSlot.invalidate()
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
        // Only fills a gap; never overwrites. The adoption paths publish this
        // token ahead of UI state on purpose, and taking the UI value here would
        // put the older id back.
        if (lastAdoptedSessionId == null) {
            _uiState.value.sessionId?.let { lastAdoptedSessionId = it }
        }
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
        lifecycleTeardown.stopOrdered(expectedSessionId = exitSessionId)
    }

    /** Ordinary Back/remote-stop path: snapshot locally and return to detail immediately. */
    fun stopSessionForExitAsync(
        positionMs: Long? = null,
        durationMs: Long? = null,
    ) {
        // This is the controller's final sample. It must bypass transient
        // seek/mount presentation gates, while still mapping a shortened
        // Media3 timeline back onto source/movie time.
        _uiState.update { current ->
            val snapshot = resolveTvPlaybackExitSnapshot(
                currentPositionSeconds = current.position,
                currentDurationSeconds = current.duration,
                positionMs = positionMs,
                durationMs = durationMs,
                timeline = current.playbackPlan?.timeline,
                serverDurationSeconds = current.serverDuration,
                allowPlayerDuration = current.playbackPlan == null,
            )
            current.copy(
                position = snapshot.positionSeconds,
                duration = snapshot.durationSeconds,
            )
        }
        val subtitlePersistenceReservation =
            subtitleTransactions.reserveDurableFinalPersistence()
        val state = _uiState.value
        TvDetailTrackSelectionSession.rememberPlaybackReturn(
            contentId = contentId,
            fileId = state.selectedFileId ?: state.mediaFileId,
            audio = null,
            subtitle = selectedSubtitleTrackIndex(state),
            positionSeconds = state.position,
            durationSeconds = state.duration.takeIf { it > 0.0 },
        )
        subtitleTransactions.invalidate()
        playbackMutationFence.invalidateAll()
        prepareSessionExit()
        subtitlePersistenceReservation?.let(
            subtitleTransactions::requestDurableFinalPersistence,
        )
        lifecycleTeardown.stopDetached(expectedSessionId = exitSessionId)
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
        // Validate BEFORE mutating. A no-op or unknown id used to fall through
        // after the audio intent had already been dropped, silently losing the
        // choice without switching anything.
        if (fileId == (state.selectedFileId ?: state.mediaFileId)) return
        if (state.fileVersions.none { it.fileId == fileId }) return
        restartSessionInPlace(fileId)
    }

    /**
     * Restart the session on [fileId] at the current position, keeping the
     * current session mounted and playable until the replacement is ready
     * (lifecycle adoption replaces A only after B is ready, including when B
     * fails). Shared by the in-player version switch and by settings whose
     * effect is decided in the server's plan rather than locally.
     *
     * The audio intent is left alone: it is scoped to the file it was made
     * against, so reconciliation rejects it once the replacement publishes,
     * and A keeps its choice if the replacement never arrives.
     */
    private fun restartSessionInPlace(fileId: Int?) {
        val state = _uiState.value
        episodeSelectionHandoffSlot.invalidate()
        resetSeekRecoveryForContentChange()
        transportMountGate.beginLoad()
        val resumeAt = state.position.takeIf { it > 0.0 }
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
        episodeSelectionHandoffSlot.invalidate()
        val subtitlePersistenceReservation =
            subtitleTransactions.reserveDurableFinalPersistence()
        subtitleTransactions.invalidateAndSettleAsync(restoreUi = false) {
            subtitlePersistenceReservation?.let(
                subtitleTransactions::requestDurableFinalPersistence,
            )
            playbackMutationFence.invalidateAll()
            // Read AFTER settlement, not snapshotted before it. Settlement can
            // roll a subtitle publication back, and that rollback returns
            // ownership to the predecessor — so a value captured before this
            // callback names the discarded replacement, and the predecessor is
            // left running with the one-shot gate already consumed.
            //
            // Never unqualified either. A null expectedSessionId disables the
            // lifecycle's ownership guard entirely, and this callback is
            // deliberately delayed behind subtitle settlement — long enough for
            // a newer screen to have adopted its own session. A screen that
            // never owned one has nothing to tear down.
            exitSessionId?.let { lifecycleTeardown.stopDetached(expectedSessionId = it) }
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

internal data class TvPlaybackExitSnapshot(
    val positionSeconds: Double,
    val durationSeconds: Double,
)

internal fun resolveTvPlaybackExitSnapshot(
    currentPositionSeconds: Double,
    currentDurationSeconds: Double,
    positionMs: Long?,
    durationMs: Long?,
    timeline: PlaybackTimeline?,
    serverDurationSeconds: Double,
    allowPlayerDuration: Boolean = true,
): TvPlaybackExitSnapshot {
    if (positionMs == null || durationMs == null || positionMs < 0L) {
        return TvPlaybackExitSnapshot(currentPositionSeconds, currentDurationSeconds)
    }

    val serverDuration = serverDurationSeconds.takeIf { it.isFinite() && it > 0.0 }
    val playerPositionSeconds = positionMs / 1_000.0
    val sourcePositionSeconds = (
        timeline?.sourcePositionForPlayer(playerPositionSeconds) ?: playerPositionSeconds
        ).let { position -> serverDuration?.let(position::coerceAtMost) ?: position }
    val sourceDurationSeconds = if (!allowPlayerDuration) {
        serverDuration ?: 0.0
    } else if (durationMs > 0L) {
        val playerDurationSeconds = durationMs / 1_000.0
        timeline?.sourcePositionForPlayer(playerDurationSeconds) ?: playerDurationSeconds
    } else {
        currentDurationSeconds
    }.let { duration -> serverDuration?.let(duration::coerceAtMost) ?: duration }

    return TvPlaybackExitSnapshot(
        positionSeconds = sourcePositionSeconds.coerceAtLeast(0.0),
        durationSeconds = if (allowPlayerDuration) {
            maxOf(currentDurationSeconds, sourceDurationSeconds)
        } else {
            sourceDurationSeconds
        },
    )
}

data class PlaybackClock(
    val position: Double,
    val duration: Double,
)

internal fun TvPlayerViewModel.UiState.withoutPlaybackClock(): TvPlayerViewModel.UiState =
    copy(position = 0.0, duration = 0.0)

internal fun TvPlayerViewModel.UiState.toPlaybackClock(): PlaybackClock =
    PlaybackClock(position = position, duration = duration)

