package org.siloserver.silo.android.ui.screens.player

import org.siloserver.silo.common.player.dolbyVisionTransformClassification
import org.siloserver.silo.common.player.failureDiagnostics

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.android.BuildConfig
import org.siloserver.silo.common.downloads.DownloadEnqueuer
import org.siloserver.silo.common.downloads.OfflineMediaResolver
import org.siloserver.silo.common.network.ServerReachabilityMonitor
import org.siloserver.silo.common.player.PlaybackAnalyticsListener
import org.siloserver.silo.common.player.PlaybackCapabilityDetector
import org.siloserver.silo.common.player.FinalPlaybackPosition
import org.siloserver.silo.common.player.FinalPlaybackPositionWriter
import org.siloserver.silo.common.player.Playability
import org.siloserver.silo.common.player.PlaybackSessionLifecycle
import org.siloserver.silo.common.player.PlaybackSessionManager
import org.siloserver.silo.common.player.PlaybackTeardownGate
import org.siloserver.silo.common.player.VideoSessionStartV3
import org.siloserver.silo.common.player.cast.CastMediaSpec
import org.siloserver.silo.common.player.cast.CastPrepareRequest
import org.siloserver.silo.common.player.PlayerNotice
import org.siloserver.silo.common.player.PlayerStatsSnapshot
import org.siloserver.silo.common.player.SessionState
import org.siloserver.silo.common.player.SleepTimerController
import org.siloserver.silo.common.player.SleepTimerState
import org.siloserver.silo.common.player.StartParams
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
import org.siloserver.silo.common.player.video.VideoPlaybackSessionCoordinator
import org.siloserver.silo.common.player.video.VideoPlaybackStartRequest
import org.siloserver.silo.common.player.video.VideoPlayerRouteArgs
import org.siloserver.silo.common.player.video.VideoPlayerUiState
import org.siloserver.silo.common.player.video.canPlayResolvedStreamDirectly
import org.siloserver.silo.common.player.video.resolvedPlaybackDelivery
import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.common.settings.dolbyVisionPolicySnapshot
import org.siloserver.silo.domain.player.IntroAutoSkipController
import org.siloserver.silo.domain.player.IntroAutoSkipState
import org.siloserver.silo.model.catalog.AudioTrack
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.VersionChapter
import org.siloserver.silo.model.catalog.TimeRange
import org.siloserver.silo.model.settings.SubtitleAppearance
import org.siloserver.silo.model.playback.PlayMethod
import org.siloserver.silo.model.playback.PlaybackDelivery
import org.siloserver.silo.model.playback.PlaybackExecutionPlan
import org.siloserver.silo.model.playback.PlaybackRouteFamily
import org.siloserver.silo.model.playback.PlaybackSessionResponse
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.CommittedSubtitle
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.playback.isLocalDownloadedSubtitle
import org.siloserver.silo.model.playback.enrichAuthoritativePlaybackSubtitleChoices
import org.siloserver.silo.model.playback.mergeDownloadedSubtitles
import org.siloserver.silo.model.playback.rebaseDownloadedSubtitleUrl
import org.siloserver.silo.model.playback.resolvedSelectedSubtitleIndex
import org.siloserver.silo.model.playback.resolvePlaybackStartPosition
import org.siloserver.silo.model.playback.combinedSubtitleSelectionIndexes
import org.siloserver.silo.playback.PlaybackSubtitleReady
import org.siloserver.silo.playback.applyAuthoritativeSubtitleReadyTrack
import org.siloserver.silo.model.catalog.SubtitleTrack
import org.siloserver.silo.model.subtitles.SubtitleAiJob
import org.siloserver.silo.model.subtitles.SubtitleAiQuota
import org.siloserver.silo.model.subtitles.SubtitleAiStatus
import org.siloserver.silo.model.subtitles.SubtitleDownloadRequest
import org.siloserver.silo.model.subtitles.SubtitleResult
import org.siloserver.silo.model.subtitles.SubtitleSearchRequest
import org.siloserver.silo.model.subtitles.SubtitleTranslateRequest
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.common.player.AutoPlayGuard
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.errorMessage
import org.siloserver.silo.playback.audioTrackFingerprint
import org.siloserver.silo.playback.encodeSubtitleIdentityPreference
import org.siloserver.silo.playback.nextEpisodeAfter
import org.siloserver.silo.playback.resolveAudioTrackOrdinal
import org.siloserver.silo.common.player.video.AudioReconcileAction
import org.siloserver.silo.common.player.video.DesiredAudio
import org.siloserver.silo.common.player.video.LocalAudioSelection
import org.siloserver.silo.common.player.video.MountedAudioTrack
import org.siloserver.silo.common.player.video.matchMountedAudioTrack
import org.siloserver.silo.common.player.video.reconcileDesiredAudioAction
import org.siloserver.silo.playback.selectPlaybackVersion
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.repository.ProfileRepository
import org.siloserver.silo.repository.SubtitlesRepository
import org.siloserver.silo.repository.port.PlaybackWriteScope
import org.siloserver.silo.repository.port.TrackSelectionFingerprintUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.channels.BufferOverflow
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
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

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

internal fun selectedServerSubtitleTrackIndex(
    selectedOrdinal: Int,
    subtitleTracks: List<PlayerSubtitleInfo>,
): Int? = when (selectedOrdinal) {
    -1 -> -1
    else -> subtitleTracks.getOrNull(selectedOrdinal)?.index
}

data class PlaybackClock(
    val position: Double,
    val duration: Double,
    val bufferedPosition: Double,
)

internal class InitialPlayerLoadGate {
    private val claimed = AtomicBoolean(false)

    fun claim(): Boolean = claimed.compareAndSet(false, true)
}

internal class SubtitleRefreshGate {
    private var lastAppliedNonce = 0

    fun claim(nonce: Int): Boolean {
        if (nonce <= 0 || nonce == lastAppliedNonce) return false
        lastAppliedNonce = nonce
        return true
    }

    fun reset() {
        lastAppliedNonce = 0
    }
}

internal fun PlayerViewModel.PlayerUiState.withoutPlaybackClock(): PlayerViewModel.PlayerUiState =
    copy(position = 0.0, duration = 0.0, bufferedPosition = 0.0)

internal fun PlayerViewModel.PlayerUiState.toPlaybackClock(): PlaybackClock =
    PlaybackClock(
        position = position,
        duration = duration,
        bufferedPosition = bufferedPosition,
    )

internal fun PlayerViewModel.PlayerUiState.withPlaybackClock(clock: PlaybackClock): PlayerViewModel.PlayerUiState =
    copy(
        position = clock.position,
        duration = clock.duration,
        bufferedPosition = clock.bufferedPosition,
    )

/**
 * The audio ordinal to send the server, from the picker row that was chosen.
 *
 * Audio is addressed by ORDINAL into `audio_tracks`. Unlike subtitles, audio
 * tracks carry no index on the wire — a probe of the server returns
 * `{"title":"English DTS 5.1","language":"en","codec":"dts",...}` with no
 * `index`, so [AudioTrack.index] deserialises to its `0` default on every row.
 *
 * This used to read `audioTracks.getOrNull(ordinal).index`, which therefore
 * evaluated to 0 for every track: every explicit audio pick asked the server
 * for track 0, so choosing the second language played the first.
 */
/**
 * The durable fingerprint for a committed audio choice.
 *
 * [committedAudioTrackIndex] is an ORDINAL into [audioTracks]. Resolving it
 * against `AudioTrack.index` matched nothing for any ordinal above zero -- the
 * wire carries no audio index -- so the chosen track was silently never
 * persisted and reopening the item lost it.
 */
internal fun mobileAudioTrackPersistenceUpdate(
    committedAudioTrackIndex: Int?,
    audioTracks: List<AudioTrack>,
): TrackSelectionFingerprintUpdate = committedAudioTrackIndex
    ?.let(audioTracks::getOrNull)
    ?.let(::audioTrackFingerprint)
    ?.let(TrackSelectionFingerprintUpdate::Set)
    ?: TrackSelectionFingerprintUpdate.Preserve

internal fun selectedServerAudioTrackIndex(
    selectedOrdinal: Int,
    audioTracks: List<AudioTrack>,
): Int? = selectedOrdinal.takeIf { it in audioTracks.indices }

/** Inverse of [selectedServerAudioTrackIndex]: both are the same ordinal. */
internal fun selectedAudioTrackOrdinal(
    selectedServerIndex: Int,
    audioTracks: List<AudioTrack>,
): Int = selectedServerIndex.takeIf { it in audioTracks.indices } ?: 0

private fun SubtitleIdentity.serverTrackIndexForMobile(): Int = when (this) {
    SubtitleIdentity.Off -> -1
    is SubtitleIdentity.ServerSidecar -> serverIndex
    is SubtitleIdentity.ServerBurnIn -> serverIndex
    is SubtitleIdentity.Embedded -> serverIndex
    is SubtitleIdentity.Downloaded,
    is SubtitleIdentity.LocalMedia3,
    -> -1
}

class PlayerViewModel(
    private val videoPlaybackCoordinator: VideoPlaybackSessionCoordinator,
    private val catalogRepository: CatalogRepository,
    private val playbackSessionManager: PlaybackSessionManager,
    private val playbackAnalytics: PlaybackAnalyticsListener,
    private val profileRepository: ProfileRepository,
    private val personalDataRepository: PersonalDataRepository,
    private val capabilityDetector: PlaybackCapabilityDetector,
    private val offlineMediaResolver: OfflineMediaResolver,
    private val serverRegistry: ServerRegistry,
    // Pre-play reachability gate (issue #33): drives Retry's fresh probe.
    private val serverReachabilityMonitor: ServerReachabilityMonitor,
    // Phase 1 Phase 0-infra dependencies:
    private val playerSettingsStore: PlayerSettingsStore,
    private val introAutoSkipController: IntroAutoSkipController,
    private val sessionLifecycle: PlaybackSessionLifecycle,
    // Phase 2 sleep timer:
    private val sleepTimer: SleepTimerController,
    // Subtitle suite (search/download + AI translate):
    private val subtitlesRepository: SubtitlesRepository,
    // Track B: durable offline-safe position (resume + outbox sync).
    private val userItemStatePort: org.siloserver.silo.repository.port.UserItemStatePort,
    private val finalPlaybackPositionWriter: FinalPlaybackPositionWriter,
    // iOS PlayerNextUpScreen On Deck carousel — home continue-watching pool.
    private val sectionRepository: org.siloserver.silo.repository.SectionRepository? = null,
    // Google Cast (Chromecast) Tier-2 session preparer. Optional so existing
    // unit tests that construct the VM directly stay source-compatible.
    private val castPlaybackPreparer: org.siloserver.silo.common.player.cast.CastPlaybackPreparer? = null,
) : ViewModel() {

    // Last load request, replayed by the "Can't reach server" Retry / Try Anyway.
    private var lastLoadArgs: LoadArgs? = null
    // Route semantics are separate from resolved playback state. In particular,
    // a null file/track means automatic selection and must remain null after the
    // first successful resolution; recovery reloads must not turn it explicit.
    private val routeIntentState = MobilePlayerRouteIntentState()
    private var pendingAuthoritativeSubtitleDownloadId: Int? = null
    private val authoritativeSubtitleReadyRows = mutableMapOf<Pair<String, Int>, PlayerSubtitleInfo>()

    private data class LoadArgs(
        val contentId: String,
        val preferredFileId: Int?,
        val preferredQuality: String?,
        val initialAudioTrackIndex: Int?,
        val initialSubtitleTrackIndex: Int?,
        val resumePositionOverride: Double?,
        val suppressResumeRewind: Boolean,
    )

    internal fun currentExternalRouteTarget(): MobilePlayerRouteTarget? =
        mobilePlayerRouteTarget(routeIntentState.current, _uiState.value)

    companion object {
        private const val TAG = "PlayerViewModel"
        const val SERVER_UNREACHABLE_MESSAGE =
            "Can't reach server — check your connection."
        private const val CONTROLS_AUTO_HIDE_MS = 3_000L
        // Record a durable position roughly every 10s of content time (matches the
        // server reporter cadence) to bound DB/outbox churn.
        private const val POSITION_RECORD_INTERVAL_SEC = 10.0
        private const val MAX_TRANSIENT_NETWORK_RETRIES = 1
        private const val SEEK_SETTLE_DEADLINE_MS = 15_000L
        // Up-next auto-play countdown length (matches TV's NEXT_UP_COUNTDOWN_SECONDS).
        const val UP_NEXT_COUNTDOWN_SECONDS = 10
        /** iOS resolveOnDeckItems: section pools feeding the On Deck carousel. */
        private val ON_DECK_SECTION_TYPES = setOf("continue_watching", "in_progress", "next_up")
        private const val ON_DECK_MAX_ITEMS = 12
        // Stored orientation-mode values — raw-value parity with iOS
        // `PlayerOrientationMode` so the device-scoped setting round-trips.
        private const val ORIENTATION_MODE_LANDSCAPE_LOCKED = "landscapeLocked"
        private const val ORIENTATION_MODE_ROTATE_FREELY = "rotateFreely"
    }

    /**
     * Resolved next episode for the Up Next card (mirrors TV's NextEpisodeState).
     * Populated by [resolveNextEpisode]; null for movies / last episodes.
     */
    /** One On Deck carousel entry (iOS nextUpCarouselItems parity). */
    data class OnDeckItem(
        val contentId: String,
        val title: String,
        val subtitle: String?,
        val artUrl: String?,
        val artThumbhash: String?,
        val progressFraction: Float?,
    )

    data class NextEpisodeInfo(
        val contentId: String,
        val seasonNumber: Int,
        val episodeNumber: Int,
        val title: String?,
        val stillUrl: String?,
        val stillThumbhash: String?,
        val runtimeMinutes: Int,
    ) {
        val label: String
            get() = "S$seasonNumber·E$episodeNumber" + (title?.let { " — $it" } ?: "")
    }

    data class PlayerUiState(
        val isLoading: Boolean = true,
        val error: String? = null,
        /**
         * Distinct "Can't reach server" state (issue #33): when true, [error]
         * carries the reachability message and the error surface offers Retry
         * (fresh probe + reload) plus a Try Anyway escape hatch, rather than a
         * generic failure.
         */
        val serverUnreachable: Boolean = false,
        /**
         * Transient, dismissable message for a failed quality/version switch.
         * Unlike [error] it does NOT gate the video surface — the previous
         * version keeps playing underneath, so the viewer isn't dropped to a
         * black screen for a switch that simply couldn't be honored.
         */
        val versionSwitchMessage: String? = null,
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
        val requestHeaders: Map<String, String> = emptyMap(),
        val delivery: PlaybackDelivery? = null,
        val streamUrl: String? = null,
        val container: String? = null,
        val serverUrl: String = "",
        val accessToken: String = "",
        val startPosition: Double = 0.0,
        /**
         * Monotonic identity for the media currently mounted in Media3. Server
         * recovery can legitimately return an otherwise-equal URL and plan, so
         * this nonce is part of PlayerScreen's mount key to guarantee a remount.
         */
        val mediaMountGeneration: Long = 0L,
        val position: Double = 0.0,
        val duration: Double = 0.0,
        // Server-declared source runtime (0 when the server didn't provide one).
        // Authoritative ceiling for engine position/duration reports; unlike
        // [duration] it is never touched by player callbacks, so an in-progress
        // transcode's short window can't shrink it.
        val serverDuration: Double = 0.0,
        val bufferedPosition: Double = 0.0,
        val isPlaying: Boolean = false,
        val isPaused: Boolean = false,
        val subtitleTracks: List<PlayerSubtitleInfo> = emptyList(),
        val audioTracks: List<AudioTrack> = emptyList(),
        val selectedAudioIndex: Int = 0,
        val selectedSubtitleIndex: Int = -1,
        val committedSubtitleIdentity: SubtitleIdentity = SubtitleIdentity.Off,
        val pendingSubtitleIdentity: SubtitleIdentity? = null,
        val localSubtitleMountIdentity: SubtitleIdentity? = null,
        val subtitleApplying: Boolean = false,
        val intro: TimeRange? = null,
        val credits: TimeRange? = null,
        val recap: TimeRange? = null,
        val preview: TimeRange? = null,
        /**
         * Chapters from the selected FileVersion (server-extracted via FFprobe
         * at ingest). Empty list when the file has no embedded chapters. The
         * settings-sheet "Chapters" affordance opens a list of these and seeks
         * the player to `startSeconds` on tap. Mirrors iOS phone behavior.
         */
        val chapters: List<VersionChapter> = emptyList(),
        val showControls: Boolean = true,
        val isBuffering: Boolean = false,
        val versions: List<FileVersion> = emptyList(),
        val selectedVersionIndex: Int = 0,
        val contentId: String = "",
        val seriesId: String? = null,
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null,
        // F2 next-episode auto-advance: resolved next episode + Up Next card.
        val nextEpisode: NextEpisodeInfo? = null,
        val onDeckItems: List<OnDeckItem> = emptyList(),
        val showUpNext: Boolean = false,
        /** True once the stream has actually ended (STATE_ENDED) while the card shows. */
        val upNextVideoEnded: Boolean = false,
        /** Remaining auto-play countdown seconds; null = no countdown (gated / auto-play off). */
        val upNextCountdownSeconds: Int? = null,
        val preferredAudioLanguage: String? = null,
        val preferredTextLanguage: String? = null,
        /**
         * Bumped whenever refreshSubtitles merges new downloaded tracks into
         * [subtitleTracks]. PlayerScreen watches this to rebuild the MediaItem
         * (subtitle configs are baked in at build time) and re-prepare at the
         * current position.
         */
        val subtitleRefreshNonce: Int = 0,
        // Live player statistics for phone diagnostics. Populates field-by-field
        // as PlaybackAnalyticsListener emits decoder, format, bandwidth, and
        // dropped-frame events.
        val stats: PlayerStatsSnapshot = PlayerStatsSnapshot(),
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
    val presentationState: StateFlow<PlayerUiState> = uiState
        .map(PlayerUiState::withoutPlaybackClock)
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = _uiState.value.withoutPlaybackClock(),
        )
    val playbackClock: StateFlow<PlaybackClock> = uiState
        .map(PlayerUiState::toPlaybackClock)
        .distinctUntilChanged()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = _uiState.value.toPlaybackClock(),
        )

    private val mobileSubtitleTransactions = MobileSubtitleTransactionAdapter(
        scope = viewModelScope,
        stagedPort = PlaybackSessionManagerMobileSubtitleStagedReplanPort(playbackSessionManager),
        persistencePort = object : MobileSubtitlePersistencePort {
            override suspend fun persist(
                committed: CommittedSubtitle,
                context: MobileSubtitlePlaybackContext,
            ): Boolean {
                val writeScope = context.writeScope ?: return false
                val audioUpdate = mobileAudioTrackPersistenceUpdate(
                    committedAudioTrackIndex = committed.audioTrackIndex,
                    audioTracks = context.audioTracks,
                )
                return userItemStatePort.recordTrackSelection(
                    scope = writeScope,
                    contentId = context.contentId,
                    fileId = context.mediaFileId,
                    audioUpdate = audioUpdate,
                    subtitleUpdate = TrackSelectionFingerprintUpdate.Set(
                        encodeSubtitleIdentityPreference(committed.identity),
                    ),
                )
            }
        },
        onSnapshotChanged = ::applyMobileSubtitleSnapshot,
        onCommittedPlayback = ::adoptMobileSubtitlePlayback,
        onCommittedPlaybackFailure = ::recoverFromSubtitleAdoptionFailure,
    )

    /**
     * Explicit user/app seek commands. PlayerScreen collects this flow and
     * calls MediaController.seekTo. Keeping it separate from uiState.position
     * prevents routine progress samples from becoming seek commands.
     */
    private val seekRequestChannel = Channel<Double>(capacity = Channel.BUFFERED)
    val seekRequests: Flow<Double> = seekRequestChannel.receiveAsFlow()

    /**
     * Unconditional seek channel for room-driven corrective seeks. The normal
     * position mirror in PlayerScreen applies a 2.0s deadband (to avoid feedback
     * loops between playback-progress updates and user scrubs), but Watch Together
     * corrective seeks can be as small as the engine's 0.35s drift threshold and
     * MUST always reach the player. PlayerScreen collects this and calls
     * `mediaController.seekTo` with no deadband. See [seekImmediate].
     */
    private val immediateSeekChannel = Channel<Double>(capacity = Channel.BUFFERED)
    val immediateSeeks: Flow<Double> = immediateSeekChannel.receiveAsFlow()

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
    // iOS parity (PlayerOrientationCoordinator): the phone player defaults to
    // landscape-locked; "rotateFreely" is the persisted opt-out written by the
    // HUD lock toggle. Any other stored value (including the legacy "auto"
    // default) locks, matching iOS's landscapeLocked default on new clients.
    // Display flow (HUD lock icon/label, toggle): never null — the eager `true`
    // default just means the lock icon shows locked until the setting resolves.
    val orientationLocked: StateFlow<Boolean> = playerSettingsStore.orientationModeFlow
        .map { it != ORIENTATION_MODE_ROTATE_FREELY }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    // Apply flow: same resolution, but null until the persisted preference has
    // actually arrived. PlayerScreen keys its requestedOrientation effect on
    // this and does NOTHING while null — the factory-scoped VM's first frame
    // would otherwise report the eager locked default and snap rotateFreely
    // users back to SENSOR_LANDSCAPE on every player entry.
    val orientationLockedResolved: StateFlow<Boolean?> = playerSettingsStore.orientationModeFlow
        .map { it != ORIENTATION_MODE_ROTATE_FREELY }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val autoSkipIntroEnabled: StateFlow<Boolean> = playerSettingsStore.autoSkipIntroFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val autoPlayNextEnabled: StateFlow<Boolean> = playerSettingsStore.autoPlayNextFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    // Seconds before end to surface the Up Next card when no credits marker
    // exists (0 = only at end). Credits marker wins when present.
    private val nextUpPromptSeconds: StateFlow<Int> = playerSettingsStore.nextUpPromptSecondsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, 30)

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
    // End point fired before the next episode resolved — remember it (and the
    // strongest videoEnded flag seen) so resolveNextEpisode can commit the card.
    private var pendingApproachingEndVideoEnded: Boolean? = null
    private var upNextCountdownJob: Job? = null
    // Auto-dismiss timer for the transient version-switch failure pill. A new
    // failure cancels the prior job so a stale one can't clear the fresh
    // message early (repeated identical failures used to be dismissed within a
    // second by an uncancelled, message-equality-gated coroutine).
    private var versionSwitchMessageJob: Job? = null
    // Runtime recovery is a single protocol-v3 replan flight. A transient
    // network failure gets one same-route reopen before server replanning.
    private var transientNetworkRetries = 0
    private var recoveryJob: Job? = null

    // Latest user track/quality/route change (classification to notice) that
    // arrived while a recovery held the replan single-flight guard. Re-driven
    // once that flight completes so the selection isn't silently dropped;
    // last-write-wins because only the newest selection matters.
    private var queuedInvalidationReplan: Pair<String, String>? = null

    private enum class ServerSeekRecoveryMode {
        REANCHOR,
        PINNED_FALLBACK,
    }

    private data class ServerSeekRecoveryRequest(
        val seekId: Long,
        val targetSourceSec: Double,
        val mode: ServerSeekRecoveryMode,
        val reason: String,
        val rollbackAllowed: Boolean = true,
        val classification: String? = null,
        val notice: String? = null,
        val diagnostics: Map<String, String> = emptyMap(),
    )

    private data class PinnedSeekRecoveryRequest(
        val classification: String,
        val notice: String,
        val diagnostics: Map<String, String>,
    )

    // Seek recovery is deliberately serialized separately from generic player
    // recovery. A second seek never cancels an in-flight HTTP request: it
    // replaces this single queued request, and the stale response is ignored
    // before any UI/lifecycle adoption.
    private var seekRecoveryJob: Job? = null
    private var serverSeekRecoveryInFlight = false
    private var queuedServerSeek: ServerSeekRecoveryRequest? = null
    private var playbackRecoveryGeneration = 0L
    private var mediaMountSequence = 0L
    private var awaitingMediaMountGeneration: Long? = null
    private val subtitleRefreshGate = SubtitleRefreshGate()
    private var positionReportsBlockedForPendingLoad = false
    private var seekRecoveryRollbackInvalidated = false
    private var pendingNativeSeekAfterMount: Pair<Double, Boolean>? = null
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

    val hdrEnabled: StateFlow<Boolean> = playerSettingsStore.hdrEnabledFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    val dolbyVisionEnabled: StateFlow<Boolean> = playerSettingsStore.dolbyVisionEnabledFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)
    // Effective = custom appearance unless "Match Device Settings" is on
    // (then the OS captioning style, tvOS parity).
    val subtitleAppearance: StateFlow<SubtitleAppearance> = playerSettingsStore.effectiveSubtitleAppearanceFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, SubtitleAppearance.DEFAULT)
    /**
     * Per-device audio/subtitle delay in ms. Mirrors iOS phone's `audioSyncMs` /
     * `subtitleSyncMs` (`iosApp/Screens/Player/Sheets/PlayerSettingsSheet.swift:265-285`).
     * Applied by SiloPlaybackService via DelayAudioProcessor (audio) and
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
    private val exitPrepared = AtomicBoolean(false)

    /**
     * The session this view model owns, kept past the point UI state is cleared.
     *
     * Teardown happens in two stages — onExit() then onCleared() — and the first
     * clears sessionId. Reading ownership from UI state in the second therefore
     * yields null, and null means "stop whatever is playing", which after a
     * player-to-player navigation is somebody else's session.
     */
    private var retainedOwnedSessionId: String? = null

    /**
     * Makes this screen's teardown of the process-scoped lifecycle one-shot.
     *
     * Naming the session is necessary but not sufficient. onExit() runs an
     * ordered stop and onCleared() then schedules a detached one for the same
     * id; the second passes the lifecycle's ownership guard because the first
     * already cleared the owner, and bumps `stopEpoch` on its way through. A
     * screen that acquired its start epoch between the two — but has not yet
     * adopted its session — is then rejected as superseded. TV has been behind
     * this gate since auto-advance broke on exactly that race; phone was not.
     */
    private val lifecycleTeardown = PlaybackTeardownGate(sessionLifecycle)
    private var finalPositionScope: PlaybackWriteScope? = null
    private val initialPlayerLoadGate = InitialPlayerLoadGate()

    fun claimInitialRouteLoad(): Boolean = initialPlayerLoadGate.claim()
    private val loadOwners = MobilePlayerLoadOwnerRegistry()
    private var loadJob: Job? = null

    init {
        // Reclaim-Watched must never delete the file the player is using
        // (reachable via PiP -> Downloads). Mirror the currently-playing file
        // id — from EVERY load path, incl. offline — into the process-wide
        // marker; the previous single set() sat only in the recovery fallback
        // and left the guard inert during normal/offline playback.
        viewModelScope.launch {
            _uiState
                .map { it.mediaFileId }
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
        // Mirror the screen's position/duration too — the seek bar renders
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
        viewModelScope.launch {
            sessionLifecycle.missingSessionEvents.collect { renewal ->
                val state = _uiState.value
                val params = renewal.startParams
                if (
                    state.sessionId == renewal.staleSessionId &&
                    state.contentId == params.contentId
                ) {
                    loadContent(
                        contentId = params.contentId,
                        preferredFileId = params.fileId,
                        preferredQuality = params.qualityPreference,
                        initialAudioTrackIndex = params.audioTrackIndex,
                        initialSubtitleTrackIndex = params.subtitleTrackIndex,
                        resumePositionOverride = renewal.positionSeconds,
                        suppressResumeRewind = true,
                        preserveRouteIntent = true,
                        recoveryStartParams = params,
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

        // When the sleep timer fires, flip user intent to paused. PlayerScreen
        // mirrors `isPaused` to `mediaController.playWhenReady`, so this is
        // sufficient to halt playback without going through onPlayPause()
        // (which is a *toggle* and would inadvertently resume a paused player).
        sleepTimer.configure {
            _uiState.update { it.copy(isPaused = true) }
        }

        viewModelScope.launch {
            playbackAnalytics.events.collect { event ->
                _uiState.update { it.copy(stats = reducePlayerStats(it.stats, event)) }
            }
        }
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

    /**
     * Loads content metadata and starts a playback session.
     * This is the main entry point called when the player screen is first displayed.
     */
    private fun publishLoadingState(contentId: String) {
        _uiState.update {
            it.copy(
                isLoading = true,
                isBuffering = false,
                error = null,
                serverUnreachable = false,
                contentId = contentId,
                nextEpisode = null,
                showUpNext = false,
                upNextVideoEnded = false,
                upNextCountdownSeconds = null,
                stats = PlayerStatsSnapshot(),
            )
        }
    }

    fun loadContent(
        contentId: String,
        preferredFileId: Int? = null,
        preferredQuality: String? = null,
        initialAudioTrackIndex: Int? = null,
        initialSubtitleTrackIndex: Int? = null,
        resumePositionOverride: Double? = null,
        // Route provenance is separate from an operational seek/restart
        // position. Only PlayerScreen's initial route load supplies this;
        // internal auto-advance and recovery positions must not become route
        // intent.
        routeResumePositionSeconds: Double? = null,
        // True for Watch Together (the synced anchor must land exactly — no
        // skip-back nudge). The request's roomId is always null on mobile, so WT
        // can't be inferred from it the way the TV starter does.
        suppressResumeRewind: Boolean = false,
        // Try Anyway escape hatch (issue #33): bypass the pre-play reachability
        // gate and attempt the server even while it reports unreachable.
        force: Boolean = false,
        // Recovery restarts resolved media in place, but they do not change the
        // route-level auto/explicit choices used for deep-link idempotence.
        preserveRouteIntent: Boolean = false,
        // Exact capability/context snapshot used only for a 404 renewal.
        recoveryStartParams: StartParams? = null,
    ) {
        val normalizedPreferredQuality = VideoPlayerRouteArgs.normalizeQuality(preferredQuality)
        routeIntentState.beginLoad(
            contentId = contentId,
            fileId = preferredFileId,
            quality = normalizedPreferredQuality,
            audioTrackIndex = initialAudioTrackIndex,
            subtitleTrackIndex = initialSubtitleTrackIndex,
            resumePositionSeconds = VideoPlayerRouteArgs.parseResumePosition(
                routeResumePositionSeconds?.toString(),
            ),
            preserveCurrent = preserveRouteIntent,
        )
        val effectivePreferredQuality = routeIntentState.qualityForLoad(
            contentId = contentId,
            normalizedRequestedQuality = normalizedPreferredQuality,
            preserveCurrent = preserveRouteIntent,
        )
        loadJob?.cancel()
        val loadOwner = loadOwners.begin(
            contentId = contentId,
            preferredFileId = preferredFileId,
            preferredQuality = effectivePreferredQuality,
        )
        // Remember the exact request so a "Can't reach server" Retry / Try Anyway
        // can replay it faithfully (this screen has no other retry entry point).
        lastLoadArgs = LoadArgs(
            contentId = contentId,
            preferredFileId = preferredFileId,
            preferredQuality = effectivePreferredQuality,
            initialAudioTrackIndex = initialAudioTrackIndex,
            initialSubtitleTrackIndex = initialSubtitleTrackIndex,
            resumePositionOverride = resumePositionOverride,
            suppressResumeRewind = suppressResumeRewind,
        )
        // A fresh load resets any in-flight intro countdown / cancellation memory.
        introAutoSkipController.reset()
        // New item: re-arm the once-per-episode auto-advance trigger. (The
        // AutoPlayGuard streak intentionally PERSISTS across episodes.)
        autoAdvanceHandled = false
        pendingApproachingEndVideoEnded = null
        resetPlaybackRecoveryState()
        upNextCountdownJob?.cancel()
        upNextCountdownJob = null
        // Cancel any in-flight resolve from the previous episode so its result
        // can't land on this one and overwrite the fresh next-episode pointer.
        resolveNextEpisodeJob?.cancel()

        // Clear episode-scoped UI carried over from the previous item so a
        // stale Up Next card can't flash during the reload.
        val initialized = loadOwners.runIfOwned(loadOwner) {
            publishLoadingState(contentId)
            mobileSubtitleTransactions.resetContent(
                context = mobileSubtitleContext(_uiState.value).copy(sessionId = null),
                committedIdentity = SubtitleIdentity.Off,
            )
        }
        if (!initialized) return

        finalPositionScope = null
        val newLoadJob = viewModelScope.launch {
            finalPositionScope = finalPlaybackPositionWriter.captureScope()
            var unpublishedReadySessionId: String? = null
            try {
                // Offline-first fast path: if we have a completed download for
                // this contentId AND its bytes are still on disk, hand the
                // player a file:// URI without touching the server at all.
                // Title + duration are best-effort — we attempt the watch
                // detail fetch but tolerate failure.
                val localPlaybackStarted = tryLocalPlayback(
                    contentId = contentId,
                    preferredFileId = preferredFileId,
                    resumePositionOverride = resumePositionOverride,
                    loadOwner = loadOwner,
                )
                if (!ownsLoad(loadOwner)) return@launch
                if (localPlaybackStarted) {
                    return@launch
                }

                try {
                    playerSettingsStore.refreshFromServer()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Could not refresh player settings before playback", e)
                }
                if (!ownsLoad(loadOwner)) return@launch
                when (val playbackState = videoPlaybackCoordinator.start(
                    VideoPlaybackStartRequest(
                        contentId = contentId,
                        preferredFileId = preferredFileId,
                        preferredQualityOverride = effectivePreferredQuality,
                        roomId = null,
                        resumePositionOverride = resumePositionOverride,
                        audioTrackIndex = initialAudioTrackIndex,
                        subtitleTrackIndex = initialSubtitleTrackIndex,
                        suppressResumeRewind = suppressResumeRewind,
                        force = force,
                        recoveryStartParams = recoveryStartParams,
                    ),
                )) {
                    is VideoPlayerUiState.Ready -> {
                        unpublishedReadySessionId = playbackState.sessionId
                        // Retained BEFORE the suspending UI application below.
                        // The starter has already installed the lifecycle owner
                        // and started its reporter by this point, so an exit
                        // during that suspension would otherwise find neither a
                        // published session nor a retained one — and skipping
                        // teardown there strands the lifecycle and its reporter
                        // running for a screen nobody is on.
                        playbackState.sessionId?.let { retainedOwnedSessionId = it }
                        if (!ownsLoad(loadOwner)) {
                            stopStaleReadySession(playbackState.sessionId)
                            unpublishedReadySessionId = null
                            return@launch
                        }
                        applyCoordinatorStateToUi(
                            playbackState = playbackState,
                            preferredFileId = preferredFileId,
                            initialAudioTrackIndex = initialAudioTrackIndex,
                            initialSubtitleTrackIndex = initialSubtitleTrackIndex,
                            isSessionRenewal = recoveryStartParams != null,
                            loadOwner = loadOwner,
                        )
                        unpublishedReadySessionId = null
                    }
                    is VideoPlayerUiState.Error -> {
                        loadOwners.runIfOwned(loadOwner) {
                            _uiState.update {
                                it.copy(isLoading = false, error = playbackState.message)
                            }
                        }
                    }
                    is VideoPlayerUiState.ServerUnreachable -> {
                        loadOwners.runIfOwned(loadOwner) {
                            _uiState.update {
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
            } catch (e: CancellationException) {
                unpublishedReadySessionId?.let { stopStaleReadySession(it) }
                throw e
            } catch (e: Exception) {
                unpublishedReadySessionId?.let { stopStaleReadySession(it) }
                if (!ownsLoad(loadOwner)) return@launch
                Log.e(TAG, "Error loading content", e)
                loadOwners.runIfOwned(loadOwner) {
                    _uiState.update {
                        it.copy(isLoading = false, error = "Unexpected error: ${e.message}")
                    }
                }
            }
        }
        loadJob = newLoadJob
        newLoadJob.invokeOnCompletion {
            if (loadJob === newLoadJob) loadJob = null
        }
    }

    /**
     * Retry after a "Can't reach server": issue one fresh health probe, then
     * replay the last load. The probe cannot land while offline (it fails fast),
     * but a recovered server flips the monitor to Reachable so the replayed load
     * passes the gate.
     */
    fun retryServerReachability() {
        val args = lastLoadArgs ?: return
        viewModelScope.launch {
            runCatching { serverReachabilityMonitor.retryNow() }
            replayLoad(args, force = false)
        }
    }

    /** "Try Anyway" escape hatch: replay the last load bypassing the gate. */
    fun playIgnoringServerReachability() {
        val args = lastLoadArgs ?: return
        replayLoad(args, force = true)
    }

    private fun replayLoad(args: LoadArgs, force: Boolean) {
        loadContent(
            contentId = args.contentId,
            preferredFileId = args.preferredFileId,
            preferredQuality = args.preferredQuality,
            initialAudioTrackIndex = args.initialAudioTrackIndex,
            initialSubtitleTrackIndex = args.initialSubtitleTrackIndex,
            resumePositionOverride = args.resumePositionOverride,
            suppressResumeRewind = args.suppressResumeRewind,
            force = force,
            preserveRouteIntent = true,
        )
    }

    private fun ownsLoad(owner: MobilePlayerLoadOwner): Boolean = loadOwners.owns(owner)

    private suspend fun stopStaleReadySession(sessionId: String?) {
        sessionId ?: return
        withContext(NonCancellable) {
            try {
                playbackSessionManager.stopSession(sessionId)
            } catch (error: Exception) {
                Log.w(TAG, "Could not stop stale player load session $sessionId", error)
            }
        }
    }

    private suspend fun applyCoordinatorStateToUi(
        playbackState: VideoPlayerUiState.Ready,
        preferredFileId: Int?,
        initialAudioTrackIndex: Int?,
        initialSubtitleTrackIndex: Int?,
        isSessionRenewal: Boolean,
        loadOwner: MobilePlayerLoadOwner,
    ) {
        val watchDetail = when (val r = catalogRepository.getWatchDetail(playbackState.contentId)) {
            is ApiResult.Success -> r.data
            else -> null
        }
        if (!ownsLoad(loadOwner)) {
            stopStaleReadySession(playbackState.sessionId)
            return
        }
        val versions = watchDetail?.versions?.takeIf { it.isNotEmpty() }
            ?: playbackState.fileId
                ?.let { fileId ->
                    listOf(
                        FileVersion(
                            fileId = fileId,
                            duration = playbackState.durationSeconds ?: 0.0,
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
        val selectedAudioOrdinal = selectedAudioTrackOrdinal(
            selectedServerIndex = playbackState.audioTrackIndex,
            audioTracks = version?.audioTracks.orEmpty(),
        )
        // Resolve the detail screen's explicit pick FIRST: the persisted/auto
        // chain below is suppressed only when the pick actually RESOLVES, so an
        // unmatchable pick falls through to persisted → auto instead of Off.
        // The pick is an ordinal into the catalog subtitle list — translate it
        // onto the mounted list before selecting (TV parity); using it raw
        // either missed range (subtitles stayed off) or selected the wrong
        // track. resolveInitialMobileSubtitleOrdinal returns null when a real
        // pick can't be matched to the mounted list; it maps an explicit -1
        // (deliberate Off from the detail page) to -1, a resolved pick that is
        // honored and persisted like any other explicit choice.
        val requestedSubtitleIndex = if (isSessionRenewal) {
            authoritativePlaybackSubtitleOrdinal(
                serverIndex = playbackState.playbackPlan?.selectedTracks?.subtitleIndex,
                playbackTracks = playbackState.subtitleUrls,
            )
        } else {
            initialSubtitleTrackIndex?.let { requested ->
                resolveInitialMobileSubtitleOrdinal(
                    requestedOrdinal = requested,
                    catalogTracks = version?.subtitleTracks.orEmpty(),
                    mountedSubtitles = playbackState.subtitleUrls,
                )
            }
        }
        // A RESOLVED explicit pick (including an explicit -1 Off) wins over the
        // persisted/auto chain. A pick that failed to resolve (null) does NOT
        // suppress it — otherwise one unmatchable pick would strand playback on
        // Off and (via onSubtitleSelectionApplied) persist that Off for every
        // future playback.
        val explicitSubtitlePickResolved = !isSessionRenewal && requestedSubtitleIndex != null
        val localTrackSelection = version?.fileId
            ?.takeIf { initialAudioTrackIndex == null || !explicitSubtitlePickResolved }
            ?.let { fileId -> userItemStatePort.localTrackSelection(playbackState.contentId, fileId) }
        if (!ownsLoad(loadOwner)) {
            stopStaleReadySession(playbackState.sessionId)
            return
        }
        val persistedAudioIndex = if (initialAudioTrackIndex == null) {
            version?.audioTracks
                ?.let { tracks -> resolveAudioTrackOrdinal(tracks, localTrackSelection?.audioFingerprint) }
        } else {
            null
        }
        val freshSubtitleRestore = prepareMobileFreshSubtitleRestore(
            mediaFileId = version?.fileId ?: playbackState.fileId,
            mountedSubtitles = playbackState.subtitleUrls,
            sessionId = playbackState.sessionId.orEmpty(),
            serverUrl = playbackState.serverUrl,
            persistedPreference = localTrackSelection
                ?.subtitleFingerprint
                ?.takeUnless { explicitSubtitlePickResolved || isSessionRenewal },
            authoritativeInventory = playbackState.playbackPlan != null,
            loadDownloadedSubtitles = subtitlesRepository::list,
        )
        if (!ownsLoad(loadOwner)) {
            stopStaleReadySession(playbackState.sessionId)
            return
        }
        val mountedSubtitles = freshSubtitleRestore.subtitleTracks
        val persistedSubtitleIndex = freshSubtitleRestore.persistedSelectionOrdinal
        val autoSubtitleSelection = if (
            !isSessionRenewal &&
            !explicitSubtitlePickResolved &&
            !freshSubtitleRestore.persistedPreferencePresent &&
            persistedSubtitleIndex == null
        ) {
            resolveMobileAutoSubtitleSelection(
                audioTracks = version?.audioTracks ?: emptyList(),
                selectedAudioIndex = playbackState.audioTrackIndex,
                subtitles = mountedSubtitles,
                preferredLanguage = playbackState.preferredTextLanguage,
                subtitleMode = playbackState.preferredSubtitleMode,
                showForcedSubtitles = playbackState.showForcedSubtitles,
            )
        } else {
            MobileSubtitleAutoSelection.NoChange
        }
        val requestedCommittedSubtitleIndex = requestedSubtitleIndex
            ?.takeIf { it == -1 || it in mountedSubtitles.indices }
        val serverCommittedSubtitleIndex = playbackState.playbackPlan
            ?.selectedTracks
            ?.subtitleIndex
            ?.let { selectedIndex ->
                mountedSubtitles.indexOfFirst { it.index == selectedIndex }
                    .takeIf { it >= 0 }
            }
            ?: -1
        val resolvedSubtitleIndex = requestedCommittedSubtitleIndex ?: serverCommittedSubtitleIndex
        val deferredSubtitleIdentity = if (!isSessionRenewal && requestedCommittedSubtitleIndex == null) {
            freshSubtitleRestore.persistedSelectionIdentity
                ?: when (autoSubtitleSelection) {
                    is MobileSubtitleAutoSelection.Select ->
                        mountedSubtitles
                            .getOrNull(autoSubtitleSelection.ordinal)
                            ?.let(::mobileSubtitleIdentity)
                            ?: SubtitleIdentity.Off
                    MobileSubtitleAutoSelection.Disable -> SubtitleIdentity.Off
                    MobileSubtitleAutoSelection.NoChange -> null
                }
        } else {
            null
        }

        val published = loadOwners.runIfOwned(loadOwner) {
            val mountGeneration = expectNextMediaMount()
            _uiState.update {
                it.copy(
                isLoading = false,
                error = null,
                title = watchDetail?.title ?: playbackState.title,
                subtitle = watchDetail?.let { detail -> buildSubtitle(detail) } ?: playbackState.subtitle.orEmpty(),
                artworkUrl = playbackState.artworkUrl,
                sessionId = playbackState.sessionId
                    ?.also { retainedOwnedSessionId = it },
                playMethod = playbackState.playMethod,
                playbackPlan = playbackState.playbackPlan,
                requestHeaders = playbackState.requestHeaders,
                delivery = playbackState.delivery,
                streamUrl = playbackState.streamUrl,
                container = playbackState.container,
                serverUrl = playbackState.serverUrl,
                accessToken = playbackState.accessToken,
                startPosition = playbackState.startPositionSeconds,
                mediaMountGeneration = mountGeneration,
                position = playbackState.sourceStartPositionSeconds,
                // V3 source duration is authoritative. Zero means the plan did
                // not declare one; neither catalog nor Media3 may substitute it.
                duration = playbackState.durationSeconds?.takeIf { it > 0.0 } ?: 0.0,
                serverDuration = playbackState.durationSeconds?.takeIf { it > 0.0 } ?: 0.0,
                isPlaying = true,
                isPaused = false,
                subtitleTracks = mountedSubtitles,
                audioTracks = version?.audioTracks ?: emptyList(),
                selectedAudioIndex = selectedAudioOrdinal,
                selectedSubtitleIndex = resolvedSubtitleIndex,
                intro = playbackState.intro,
                credits = playbackState.credits,
                recap = playbackState.recap,
                preview = playbackState.preview,
                chapters = playbackState.chapters.ifEmpty { version?.chapters.orEmpty() },
                versions = versions,
                selectedVersionIndex = versionIndex,
                seriesId = watchDetail?.seriesId,
                seasonNumber = watchDetail?.seasonNumber,
                episodeNumber = watchDetail?.episodeNumber,
                nextEpisode = null,
                showUpNext = false,
                upNextVideoEnded = false,
                upNextCountdownSeconds = null,
                // T11: clear the subtitle-refresh nonce on every fresh mount.
                // It is bumped once per post-download refresh; without this
                // reset a later backend recreation (version switch / recovery
                // fallback) would see a stale nonce>0 and re-fire a spurious
                // second refresh racing the primary mount effect.
                subtitleRefreshNonce = 0,
                preferredAudioLanguage = playbackState.preferredAudioLanguage,
                preferredTextLanguage = playbackState.preferredTextLanguage,
                )
            }

            val mountedState = _uiState.value
            val committedIdentity = mountedState.subtitleTracks
                .getOrNull(mountedState.selectedSubtitleIndex)
                ?.let(::mobileSubtitleIdentity)
                ?: SubtitleIdentity.Off
            mobileSubtitleTransactions.resetContent(
                context = mobileSubtitleContext(mountedState),
                committedIdentity = committedIdentity,
            )
            if (
                explicitSubtitlePickResolved ||
                initialAudioTrackIndex != null &&
                initialAudioTrackIndex in mountedState.audioTracks.indices
            ) {
                mobileSubtitleTransactions.persistCommittedSelection()
            }
            deferredSubtitleIdentity
                ?.takeIf { it != committedIdentity }
                ?.let(mobileSubtitleTransactions::select)

            // Seeded whether or not it differs from what the server reported.
            // Equality with the plan is not evidence the RENDERER is on that
            // track: a direct-play file mounts every track and Media3 picks its
            // own default, which is precisely the case this exists for.
            val restoreOrdinal = persistedAudioIndex
                ?: initialAudioTrackIndex
                ?: selectedAudioOrdinal
            if (restoreOrdinal in _uiState.value.audioTracks.indices) {
                setDesiredAudio(restoreOrdinal, explicit = false)
                if (restoreOrdinal != selectedAudioOrdinal) {
                    selectAudio(restoreOrdinal, userInitiated = false)
                }
            }
        }
        if (!published) {
            stopStaleReadySession(playbackState.sessionId)
            return
        }

        // Begin observing intro auto-skip inputs for this session.
        startIntroAutoSkipObserver()
        // F2: resolve the next episode for auto-advance / "Up next".
        resolveNextEpisode()
        loadOnDeckItems()

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
        state.sessionId ?: return

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

        if (reason is Playability.StartupStalled &&
            reason.classification == "transport_stall" &&
            state.playbackPlan != null &&
            transientNetworkRetries < MAX_TRANSIENT_NETWORK_RETRIES &&
            playbackSessionManager.recordTransportReopen()
        ) {
            transientNetworkRetries++
            val plan = state.playbackPlan
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

    /**
     * Player runtime error (decoder init, source, network after prepare).
     * Mirrors TvPlayerViewModel.onPlayerError: a transient network blip retries
     * the SAME route a bounded number of times (budget restored once playback
     * progresses), everything else walks the recovery ladder — alternate
     * direct engine first, then the plan's server remux/transcode candidates.
     * Previously the mobile player had no error handling at all: a decoder or
     * IO failure left the screen on a stale frame/spinner forever.
     */
    fun onPlayerError(error: androidx.media3.common.PlaybackException) {
        val state = _uiState.value
        val message = error.localizedMessage?.takeIf { msg -> msg.isNotBlank() }
            ?: "Playback failed. Please try again."
        val pendingSeekTarget = activeSeekTargetSec
        if (pendingSeekTarget != null &&
            (serverSeekRecoveryInFlight ||
                recoveryJob?.isActive == true ||
                positionReportsBlockedForPendingLoad ||
                awaitingMediaMountGeneration != null)
        ) {
            // The listener belongs to the currently mounted Media3 item, which
            // is still the old transport while a server response/remount is in
            // flight. Do not let that stale callback demote the replacement
            // route before it has even been mounted and acknowledged.
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
            val track = state.audioTracks.getOrNull(state.selectedAudioIndex)
            val mime = track?.codec.toAudioMimeType()
            val plan = state.playbackPlan
            if (mime != null && plan != null &&
                playbackSessionManager.trySingleLocalPcmRetry(mime, track?.channels ?: 0)
            ) {
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
                            decisionTrace = plan.decisionTrace +
                                "client_retry=pcm_decode:$mime:${track?.channels ?: 0}",
                        ),
                        startPosition = plan.timeline.playerPositionForSource(state.position)
                            ?: plan.timeline.playerStartSeconds,
                    )
                }
                return
            }
        }
        // A seek can expose a broken byte-range/container boundary even when
        // startup decoding was healthy. Only those seek-scoped transport and
        // parser failures get the same-recipe reanchor; decoder, DV transform,
        // and audio failures must keep their specialized recovery paths.
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
        val isTransientNetwork =
            error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
                error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        val plan = state.playbackPlan
        if (isTransientNetwork &&
            state.sessionId != null &&
            plan != null &&
            transientNetworkRetries < MAX_TRANSIENT_NETWORK_RETRIES &&
            playbackSessionManager.recordTransportReopen()
        ) {
            transientNetworkRetries++
            Log.i(TAG, "Transient network error; retrying same route ($transientNetworkRetries/$MAX_TRANSIENT_NETWORK_RETRIES)")
            // Appending to the decision trace produces a new plan object, which
            // re-runs the screen's mount effect — a same-route remount at the
            // current position, without a server round-trip.
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
                isPlaying = false,
                isBuffering = false,
            )
        }
    }

    private fun startProtocolV3Replan(
        classification: String,
        notice: String,
        state: PlayerUiState,
        diagnostics: Map<String, String> = emptyMap(),
    ) {
        if (recoveryJob?.isActive == true || serverSeekRecoveryInFlight) {
            // Never silently drop a user selection: queue it (newest wins) and
            // re-drive it when the in-flight recovery completes. Failure-driven
            // replans stay dropped — onPlayerError re-raises those.
            if (classification in PlaybackSessionManager.USER_INVALIDATION_CLASSIFICATIONS) {
                queuedInvalidationReplan = classification to notice
            }
            return
        }
        if (mobileSubtitleTransactions.hasActiveTransaction) {
            if (classification in PlaybackSessionManager.USER_INVALIDATION_CLASSIFICATIONS) {
                queuedInvalidationReplan = classification to notice
                return
            }
            mobileSubtitleTransactions.invalidate()
        }
        val fileId = state.versions.getOrNull(state.selectedVersionIndex)?.fileId ?: return
        val recoveryGeneration = playbackRecoveryGeneration
        recoveryJob = viewModelScope.launch {
            val selectedSubtitleTrackIndex = selectedServerSubtitleTrackIndex(
                selectedOrdinal = state.selectedSubtitleIndex,
                subtitleTracks = state.subtitleTracks,
            )
            val selectedAudioTrackIndex = selectedServerAudioTrackIndex(
                selectedOrdinal = state.selectedAudioIndex,
                audioTracks = state.versions.getOrNull(state.selectedVersionIndex)?.audioTracks.orEmpty(),
            )
            val dolbyVision = playerSettingsStore.dolbyVisionPolicySnapshot()
            val capabilities = capabilityDetector.detect(dolbyVision = dolbyVision)
            val playbackContext = capabilityDetector.detectPlaybackContext(
                formFactor = "mobile",
                appVersion = BuildConfig.VERSION_NAME,
                dolbyVision = dolbyVision,
                capabilities = capabilities,
            )
            val result = playbackSessionManager.replanActiveVideoSession(
                classification = classification,
                message = notice,
                positionSeconds = state.position,
                audioTrackIndex = selectedAudioTrackIndex,
                subtitleTrackIndex = selectedSubtitleTrackIndex,
                diagnostics = diagnostics,
                capabilities = capabilities,
                clientPlaybackContext = playbackContext,
            )
            // Returning on a stale generation is not enough on its own. By the
            // time this call returns, the manager has already committed and
            // taken ownership of the replacement session — so dropping the
            // result quietly leaves a transcode running on the server that
            // nothing will ever stop. The viewer sees playback exit; the server
            // holds the stream slot until it times out. Release it when the
            // generation moved on, the way TV already does; the adoption below
            // owns the cancellation windows past this point.
            val abandonedSessionId = (result as? ApiResult.Success)
                ?.data
                ?.let { it as? VideoSessionStartV3.Ready }
                ?.session
                ?.sessionId
            if (!isActive || recoveryGeneration != playbackRecoveryGeneration) {
                // Released on the manager's own scope, which outlives this
                // screen: the whole point is to run after the reason for
                // abandoning, and this ViewModel's scope may already be gone.
                abandonedSessionId?.let(playbackSessionManager::abandonActiveVideoSessionAsync)
            }
            currentCoroutineContext().ensureActive()
            if (recoveryGeneration != playbackRecoveryGeneration) return@launch
            when (result) {
                is ApiResult.Success -> when (val decision = result.data) {
                    is VideoSessionStartV3.Ready -> {
                        val remountPosition = decision.plan.timeline
                            .replanMountPositionForSource(state.position)
                        val effectiveFileId = decision.session.mediaFileId.takeIf { it > 0 }
                            ?: decision.plan.effectiveMediaFileId
                            ?: fileId
                        val catalogVersionIndex = state.versions
                            .indexOfFirst { it.fileId == effectiveFileId }
                        val effectiveVersions = if (catalogVersionIndex >= 0) {
                            state.versions
                        } else {
                            state.versions + FileVersion(fileId = effectiveFileId)
                        }
                        val effectiveVersionIndex = catalogVersionIndex
                            .takeIf { it >= 0 }
                            ?: effectiveVersions.lastIndex
                        val effectiveVersion = effectiveVersions[effectiveVersionIndex]
                        val returnedSubtitleIndex = decision.plan.resolvedSelectedSubtitleIndex()
                        val authoritativeSubtitles = enrichAuthoritativePlaybackSubtitleChoices(
                            catalogTracks = effectiveVersion?.subtitleTracks.orEmpty(),
                            plannedTracks = decision.session.subtitleUrls.orEmpty(),
                        )
                        val downloaded = if (effectiveFileId == fileId) {
                            state.subtitleTracks
                                .filter(PlayerSubtitleInfo::isLocalDownloadedSubtitle)
                                .filterNot { local ->
                                    authoritativeSubtitles.any { it.index == local.index }
                                }
                                .map { track ->
                                    track.copy(
                                        url = rebaseDownloadedSubtitleUrl(
                                            track.url,
                                            decision.session.sessionId,
                                        ),
                                    )
                                }
                        } else {
                            emptyList()
                        }
                        val recoveredSubtitles = authoritativeSubtitles + downloaded
                        val returnedSubtitleOrdinal = returnedSubtitleIndex?.let { serverIndex ->
                            recoveredSubtitles.indexOfFirst { it.index == serverIndex }.takeIf { it >= 0 }
                        } ?: -1
                        val returnedSubtitleIdentity = recoveredSubtitles
                            .getOrNull(returnedSubtitleOrdinal)
                            ?.let(::mobileSubtitleIdentity)
                            ?: SubtitleIdentity.Off
                        val returnedAudioIndex = decision.plan.selectedTracks.audio?.index
                            ?: decision.session.audioTrackIndex
                        // Conditional adoption, evaluated inside the lifecycle
                        // lock: an unconditional adopt can hand the lifecycle a
                        // session this screen has already stopped owning, and
                        // then the manager owns the replacement while the
                        // lifecycle still owns its predecessor and the UI owns
                        // neither.
                        var adopted = false
                        try {
                            adopted = sessionLifecycle.adoptActiveSessionIfCurrent(
                                params = StartParams(
                                    contentId = state.contentId,
                                    fileId = effectiveFileId,
                                    capabilities = decision.capabilities,
                                    audioTrackIndex = returnedAudioIndex,
                                    subtitleTrackIndex = returnedSubtitleIndex ?: -1,
                                    qualityPreference = currentMobileQualityPreference(),
                                    startPosition = decision.session.position,
                                    clientPlaybackContext = decision.clientPlaybackContext,
                                ),
                                session = decision.session,
                                isCurrent = {
                                    recoveryGeneration == playbackRecoveryGeneration && isActive
                                },
                            )
                        } finally {
                            // Covers refusal AND cancellation while waiting for
                            // the lifecycle mutex, which throws before isCurrent
                            // ever runs. NonCancellable because the usual reason
                            // for being here is that this coroutine was
                            // cancelled, and a cancelled coroutine cannot make
                            // the call that releases the server's stream slot —
                            // runCatching would only swallow the failure.
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
                        // Ownership moved at adoption, so the exit token moves
                        // with it — and both exit routes read this token ahead
                        // of UI state precisely so this write wins. Publishing
                        // it only in the UI update below leaves a cancellation
                        // in between with the lifecycle owning the replacement
                        // while exit still names the predecessor, and the
                        // lifecycle then correctly refuses to stop it.
                        retainedOwnedSessionId = decision.session.sessionId
                        currentCoroutineContext().ensureActive()
                        if (recoveryGeneration != playbackRecoveryGeneration) return@launch
                        val mountGeneration = expectNextMediaMount()
                        _uiState.update { current ->
                            current.copy(
                                error = null,
                                sessionId = decision.session.sessionId
                                    .also { retainedOwnedSessionId = it },
                                playMethod = decision.session.playMethod,
                                playbackPlan = decision.session.playbackPlan,
                                delivery = decision.plan.delivery,
                                streamUrl = decision.plan.stream.url,
                                requestHeaders = decision.plan.stream.headers,
                                container = decision.plan.stream.container
                                    ?: effectiveVersion?.container
                                    ?: current.container.takeIf { effectiveFileId == fileId },
                                startPosition = remountPosition.playerPositionSeconds,
                                mediaMountGeneration = mountGeneration,
                                versions = effectiveVersions,
                                selectedVersionIndex = effectiveVersionIndex,
                                subtitleTracks = recoveredSubtitles,
                                selectedSubtitleIndex = returnedSubtitleOrdinal,
                                committedSubtitleIdentity = returnedSubtitleIdentity,
                                audioTracks = effectiveVersion?.audioTracks.orEmpty(),
                                selectedAudioIndex = selectedAudioTrackOrdinal(
                                    returnedAudioIndex,
                                    effectiveVersion?.audioTracks.orEmpty(),
                                ),
                                duration = decision.session.durationSeconds ?: 0.0,
                                serverDuration = decision.session.durationSeconds ?: 0.0,
                                chapters = effectiveVersion?.chapters.orEmpty(),
                                position = remountPosition.sourcePositionSeconds,
                            )
                        }
                        Log.i(
                            TAG,
                            "replan_mount restored_source_seconds=${remountPosition.sourcePositionSeconds} " +
                                "player_seconds=${remountPosition.playerPositionSeconds}",
                        )
                        val recoveredState = _uiState.value
                        mobileSubtitleTransactions.resetContent(
                            context = mobileSubtitleContext(recoveredState),
                            committedIdentity = returnedSubtitleIdentity,
                        )
                        mobileSubtitleTransactions.restoreCommittedLocalMount()
                    }
                    is VideoSessionStartV3.Terminal -> {
                        val failedSessionId = state.sessionId ?: return@launch
                        val terminalMessage =
                            "Playback unavailable (${decision.reason}): ${decision.message}"
                        val terminalStillCurrent = sessionLifecycle.stopTerminalSessionIfCurrent(
                            expectedSessionId = failedSessionId,
                            isCurrent = {
                                recoveryGeneration == playbackRecoveryGeneration &&
                                    _uiState.value.sessionId == failedSessionId
                            },
                        )
                        if (!terminalStillCurrent) {
                            return@launch
                        }
                        retainedOwnedSessionId = null
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
                    VideoSessionStartV3.ServerUpgradeRequired -> _uiState.update {
                        it.copy(
                            error = "This Silo server must be updated to support playback recovery.",
                            isLoading = false,
                            isBuffering = false,
                        )
                    }
                }
                is ApiResult.Error -> onReplanRequestFailed(classification, notice, result.message)
                is ApiResult.NetworkError ->
                    onReplanRequestFailed(classification, notice, result.exception.message)
            }
        }.also { job ->
            // Cancellation means a content change / reset already cleared the
            // queue; only a completed flight re-drives a queued user selection.
            job.invokeOnCompletion { cause ->
                if (cause == null) redriveQueuedInvalidationReplan()
            }
        }
    }

    private fun redriveQueuedInvalidationReplan() {
        val (classification, notice) = queuedInvalidationReplan ?: return
        queuedInvalidationReplan = null
        // Current state, not the queuing-time state, so the replan carries the
        // latest committed track/quality selection.
        startProtocolV3Replan(classification, notice, _uiState.value)
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
            showVersionSwitchMessage("Couldn't apply the change — playback continues unchanged.")
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

    private fun Playability.failureClassification(): String = when (this) {
        is Playability.UnsupportedDvProfile -> "unsupported_dolby_vision_profile"
        is Playability.UnsupportedAudioCodec -> "unsupported_audio_encoding"
        is Playability.UnsupportedChannelCount -> "unsupported_audio_layout"
        is Playability.StartupStalled -> classification
        Playability.Supported -> "none"
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
    /**
     * Legacy implementation retained temporarily until Release B source removal.
     * Updating the plan's engine + startPosition re-runs the screen's mount
     * Returns false when the action isn't an engine switch so the caller
     * proceeds to the server ladder.
    */

    private fun resetPlaybackRecoveryState() {
        // Invalidate callbacks before cancelling jobs. Some HTTP stacks can
        // still return a response while cancellation propagates; generation
        // checks prevent that response from adopting into the new content.
        playbackRecoveryGeneration++
        cancelRecoveryJob()
        queuedInvalidationReplan = null
        seekRecoveryJob?.cancel()
        seekRecoveryJob = null
        serverSeekRecoveryInFlight = false
        queuedServerSeek = null
        awaitingMediaMountGeneration = null
        positionReportsBlockedForPendingLoad = true
        seekRecoveryRollbackInvalidated = false
        clearBufferedSeekCommands()
        pendingNativeSeekAfterMount = null
        cancelPendingQuickSkip()
        seekPresentationGuard.cancel()
        activeSeekTargetSec = null
        activeSeekId = null
        sameRouteSeekRecoveryAttempted = false
        hasRenderedFirstFrame = false
        transientNetworkRetries = 0
    }

    private fun cancelRecoveryJob() {
        recoveryJob?.cancel()
        recoveryJob = null
    }

    private fun clearBufferedSeekCommands() {
        while (seekRequestChannel.tryReceive().isSuccess) Unit
        while (immediateSeekChannel.tryReceive().isSuccess) Unit
    }

    private fun expectNextMediaMount(): Long {
        mediaMountSequence = if (mediaMountSequence == Long.MAX_VALUE) 1L else mediaMountSequence + 1L
        awaitingMediaMountGeneration = mediaMountSequence
        positionReportsBlockedForPendingLoad = false
        return mediaMountSequence
    }

    /** Called synchronously after PlayerScreen has applied this mount to Media3. */
    fun shouldApplyMediaMount(generation: Long): Boolean =
        awaitingMediaMountGeneration == generation

    fun claimSubtitleRefresh(nonce: Int): Boolean = subtitleRefreshGate.claim(nonce)

    fun onMediaMountApplied(generation: Long) {
        if (awaitingMediaMountGeneration == generation) {
            awaitingMediaMountGeneration = null
            subtitleRefreshGate.reset()
            positionReportsBlockedForPendingLoad = false
            pendingNativeSeekAfterMount?.let { (targetSeconds, immediate) ->
                pendingNativeSeekAfterMount = null
                // The load may have resolved to an online V3 plan after the
                // command was queued. Decide again against the newly mounted
                // timeline so source time is mapped or re-anchored correctly.
                executeSeekTarget(targetSeconds, immediate)
            }
        }
    }

    /** Called by the player when the current position changes. */
    fun onPositionChanged(positionMs: Long, durationMs: Long, bufferedPositionMs: Long = 0L) {
        if (positionMs < 0) return
        // A recovery state update precedes the actual Media3 remount. Ignore
        // callbacks from the old media item during that gap so they cannot
        // overwrite the requested source position or settle the new seek.
        if (positionReportsBlockedForPendingLoad || awaitingMediaMountGeneration != null) return

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
        val rawBufferedSec = bufferedPositionMs / 1000.0
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
        val mappedBufferedSec = (timeline?.sourcePositionForPlayer(rawBufferedSec) ?: rawBufferedSec)
            .let { position -> serverDuration?.let { position.coerceAtMost(it) } ?: position }
        val nowMs = SystemClock.elapsedRealtime()
        val positionDecision = seekPresentationGuard.onPositionReport(
            positionMs = (mappedPositionSec * 1_000.0).toLong().coerceAtLeast(0L),
            nowElapsedRealtimeMs = nowMs,
        )
        if (positionDecision is SeekPositionDecision.Suppress) {
            _uiState.update { state ->
                state.copy(
                    duration = maxOf(state.duration, mappedDurationSec),
                    bufferedPosition = mappedBufferedSec,
                )
            }
            return
        }
        val positionSec = (positionDecision as SeekPositionDecision.Publish).positionMs / 1000.0
        val durationSec = mappedDurationSec
        val bufferedSec = mappedBufferedSec
        val seekWasActive = activeSeekTargetSec != null
        activeSeekTargetSec?.let { target ->
            if (!serverSeekRecoveryInFlight &&
                (kotlin.math.abs(positionSec - target) <= 2.0 ||
                    nowMs - activeSeekStartedAtMs >= SEEK_SETTLE_DEADLINE_MS)
            ) {
                Log.i(TAG, "seek_settled seek_id=$activeSeekId target_source_seconds=$target actual_source_seconds=$positionSec")
                activeSeekTargetSec = null
                activeSeekId = null
                sameRouteSeekRecoveryAttempted = false
            }
        }
        val previousPosition = _uiState.value.position

        // Playback is progressing — restore the transient-network retry budget so
        // a later, unrelated blip gets a fresh retry instead of demoting at once.
        if (positionSec > 0 && transientNetworkRetries > 0) {
            transientNetworkRetries = 0
        }

        _uiState.update { state ->
            state.copy(
                position = positionSec,
                // Offline playback may learn a runtime from Media3. V3's value
                // above is always the server-declared duration or unknown (0).
                duration = maxOf(state.duration, durationSec),
                bufferedPosition = bufferedSec,
            )
        }

        // F2: surface the Up Next card when playback CROSSES the credits point
        // (only on the before->after transition, so resuming inside the credits
        // doesn't instantly trigger it). Without a credits marker, fall back to
        // crossing (duration - nextUpPromptSeconds); 0 = only at end (iOS parity).
        if (!seekWasActive) {
            val creditsStart = _uiState.value.credits?.start
            if (creditsStart != null) {
                if (previousPosition < creditsStart && positionSec >= creditsStart) onApproachingEnd()
            } else {
                val promptSeconds = nextUpPromptSeconds.value
                val duration = _uiState.value.duration
                if (promptSeconds > 0 && duration > 0) {
                    val promptStart = duration - promptSeconds
                    if (previousPosition < promptStart && positionSec >= promptStart) onApproachingEnd()
                }
            }
        }

        // Forward to the lifecycle so its 10s reporter has a fresh sample.
        // Recovery (404/outage) is fully owned by the lifecycle.
        sessionLifecycle.reportPosition(
            positionSec = positionSec,
            durationSec = _uiState.value.duration,
            isPaused = _uiState.value.isPaused,
            expectedSessionId = _uiState.value.sessionId,
        )

        // Track B: durably record the position (local resume + outbox sync) for
        // BOTH streaming and offline-download playback — the lifecycle's server
        // reporter does nothing on the offline-download path (no session). Throttled
        // by content-time delta so it fires ~every 10s of playback, not per tick.
        maybeRecordPosition(positionSec, _uiState.value.duration)
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
        if (isPlaying && !_uiState.value.isPaused && _uiState.value.showControls) {
            scheduleControlsHide()
        }
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

    /** Called when buffering state changes. */
    fun onBufferingChanged(isBuffering: Boolean) {
        _uiState.update { it.copy(isBuffering = isBuffering) }
    }

    /** Toggle play/pause — tracks user intent; PlayerScreen mirrors this to playWhenReady. */
    fun onPlayPause() {
        autoPlayGuard.recordUserAction() // deliberate interaction resets the pass-out streak
        // A deliberate pause while the Up Next countdown is running opts out of
        // auto-advance: stop the countdown but keep the card so Play Now /
        // dismiss remain available (the card is non-modal, so transport input
        // reaches the player underneath it).
        if (!_uiState.value.isPaused) cancelUpNextCountdown()
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
        pendingApproachingEndVideoEnded = null
        // A deliberate scrub while the Up Next card is showing is the user
        // taking back control (e.g. rewinding to rewatch) — dismiss the card
        // and its countdown, same once-per-episode semantics as an explicit
        // dismiss. Room-driven corrective seeks go through seekImmediate and
        // are unaffected.
        if (_uiState.value.showUpNext) dismissUpNext()
        cancelPendingQuickSkip()
        beginAndExecuteSeek(position)
    }

    /** Apple-parity quick skip: preview every tap, commit one engine command
     * after the 200ms trailing edge, and base repeats on the pending target. */
    fun onSkipBy(deltaSeconds: Double) {
        autoPlayGuard.recordUserAction()
        pendingApproachingEndVideoEnded = null
        if (_uiState.value.showUpNext) dismissUpNext()
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
        armSeekPresentation(
            originSourceMs = quickSkipOriginMs,
            targetSourceMs = pending.targetPositionMs,
            nowMs = nowMs,
        )
        _uiState.update { it.copy(position = pending.targetPositionMs / 1_000.0) }
        quickSkipCommitJob?.cancel()
        quickSkipCommitJob = viewModelScope.launch {
            delay((pending.commitAtElapsedRealtimeMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
            quickSkipAccumulator.commitIfDue(
                expectedGeneration = pending.generation,
                nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            )?.let { commit ->
                executeSeekTarget(commit.targetPositionMs / 1_000.0)
            }
        }
    }

    /**
     * Immediate, deadband-free seek for room-driven corrective seeks
     * (RoomSyncController.applyDecision). Updates `uiState.position` like
     * [onSeek] AND emits on [immediateSeeks] so PlayerScreen drives the
     * MediaController unconditionally — bypassing the 2.0s position-mirror
     * deadband that would otherwise swallow sub-2s sync corrections.
     */
    fun seekImmediate(position: Double) {
        cancelPendingQuickSkip()
        beginAndExecuteSeek(position, immediate = true)
    }

    private fun cancelPendingQuickSkip() {
        quickSkipCommitJob?.cancel()
        quickSkipCommitJob = null
        quickSkipAccumulator.cancel()
    }

    private fun beginAndExecuteSeek(position: Double, immediate: Boolean = false) {
        val state = _uiState.value
        val target = position
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
        executeSeekTarget(target, immediate)
    }

    private fun armSeekPresentation(originSourceMs: Long, targetSourceMs: Long, nowMs: Long) {
        seekPresentationGuard.begin(
            originPositionMs = originSourceMs,
            targetPositionMs = targetSourceMs,
            nowElapsedRealtimeMs = nowMs,
        )
        activeSeekTargetSec = targetSourceMs / 1_000.0
        activeSeekStartedAtMs = nowMs
        if (activeSeekId == null) activeSeekId = ++seekSequence
    }

    private fun executeSeekTarget(targetSourceSec: Double, immediate: Boolean = false) {
        val state = _uiState.value
        val mountPending = positionReportsBlockedForPendingLoad || awaitingMediaMountGeneration != null
        if (mountPending && (state.sessionId == null || state.playbackPlan == null)) {
            // Local/offline playback has no V3 attempt to re-anchor. Retain the
            // newest absolute target until the matching Media3 mount is applied.
            pendingNativeSeekAfterMount = targetSourceSec to immediate
            Log.i(
                TAG,
                "seek_commit seek_id=$activeSeekId action=queue_native_after_mount " +
                    "target_source_seconds=$targetSourceSec",
            )
            return
        }
        // The mounted player still represents the old server origin until the
        // active re-anchor returns. Even if that old timeline calls this seek
        // "native", executing it there would race the replacement mount. Queue
        // the latest source target as another server re-anchor instead.
        if (
            serverSeekRecoveryInFlight ||
            recoveryJob?.isActive == true ||
            positionReportsBlockedForPendingLoad ||
            awaitingMediaMountGeneration != null
        ) {
            Log.i(
                TAG,
                "seek_commit seek_id=$activeSeekId action=queue_server_reanchor " +
                    "target_source_seconds=$targetSourceSec reason=reanchor_in_flight",
            )
            startSeekReanchor(targetSourceSec, "reanchor_in_flight")
            return
        }
        val timeline = state.playbackPlan?.timeline
        val decision = timeline?.decideSeek(targetSourceSec)
        when (decision) {
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
                if (immediate) {
                    immediateSeekChannel.trySend(decision.targetPlayerPositionSeconds)
                } else {
                    seekRequestChannel.trySend(decision.targetPlayerPositionSeconds)
                }
            }
            null -> {
                // Offline/local playback has no V3 plan; source and player
                // coordinates are the same.
                if (immediate) immediateSeekChannel.trySend(targetSourceSec)
                else seekRequestChannel.trySend(targetSourceSec)
            }
        }
    }

    private fun startSeekReanchor(
        targetSourceSec: Double,
        reason: String,
        rollbackAllowed: Boolean = true,
        diagnostics: Map<String, String> = emptyMap(),
    ) {
        val seekId = activeSeekId ?: return
        enqueueServerSeekRecovery(
            ServerSeekRecoveryRequest(
                seekId = seekId,
                targetSourceSec = targetSourceSec,
                mode = ServerSeekRecoveryMode.REANCHOR,
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
        val seekId = activeSeekId ?: return
        enqueueServerSeekRecovery(
            ServerSeekRecoveryRequest(
                seekId = seekId,
                targetSourceSec = targetSourceSec,
                mode = ServerSeekRecoveryMode.PINNED_FALLBACK,
                reason = "player_error",
                classification = classification,
                notice = notice,
                diagnostics = diagnostics,
            ),
        )
    }

    private fun enqueueServerSeekRecovery(request: ServerSeekRecoveryRequest) {
        val state = _uiState.value
        state.versions.getOrNull(state.selectedVersionIndex)?.fileId ?: return
        // A server-origin replacement supersedes any native command that has
        // not reached Media3 yet; replaying that old command on the new mount
        // would violate latest-target-wins ordering.
        clearBufferedSeekCommands()
        sameRouteSeekRecoveryAttempted = true
        _uiState.update { it.copy(isBuffering = true, error = null) }

        if (serverSeekRecoveryInFlight) {
            // One slot is intentional: seeks are absolute source positions, so
            // only the newest target remains useful. The running HTTP request is
            // allowed to complete and is discarded before adoption.
            val queued = queuedServerSeek
            if (request.mode == ServerSeekRecoveryMode.PINNED_FALLBACK &&
                queued != null && queued.seekId >= request.seekId
            ) {
                // An error callback from the old mount can arrive after the
                // user has already queued a newer exact target. It must not
                // replace that deliberate seek with a fallback for stale media.
                return
            }
            queuedServerSeek = request
            Log.i(
                TAG,
                "seek_recovery seek_id=${request.seekId} action=queued_latest " +
                    "target_source_seconds=${request.targetSourceSec}",
            )
            return
        }

        serverSeekRecoveryInFlight = true
        seekRecoveryRollbackInvalidated = !request.rollbackAllowed
        queuedServerSeek = null
        val recoveryGeneration = playbackRecoveryGeneration
        seekRecoveryJob = viewModelScope.launch {
            runServerSeekRecovery(request, recoveryGeneration)
        }
    }

    private suspend fun runServerSeekRecovery(
        initialRequest: ServerSeekRecoveryRequest,
        recoveryGeneration: Long,
    ) {
        var request = initialRequest
        try {
            // A generic V3 recovery may already own the manager operation
            // mutex. Let it finish without cancellation, then apply the newest
            // user target after it so the generic recovery cannot remount an
            // older position last.
            recoveryJob?.takeIf { it.isActive }?.join()
            if (recoveryGeneration != playbackRecoveryGeneration) return
            while (recoveryGeneration == playbackRecoveryGeneration) {
                // A newer target may have arrived before this coroutine got its
                // first turn, or while the preceding lifecycle adoption waited.
                queuedServerSeek?.let { latest ->
                    request = latest
                    queuedServerSeek = null
                }
                if (activeSeekId != request.seekId) return

                val before = _uiState.value
                val operationDiagnostics = request.diagnostics + mapOf(
                    "seek_id" to request.seekId.toString(),
                    "seek_reason" to request.reason,
                )
                var readyDecision: VideoSessionStartV3.Ready? = null
                var pinnedRequest = if (request.mode == ServerSeekRecoveryMode.PINNED_FALLBACK) {
                    PinnedSeekRecoveryRequest(
                        classification = request.classification ?: "seek_failure",
                        notice = request.notice ?: "Playback failed while seeking.",
                        diagnostics = operationDiagnostics,
                    )
                } else {
                    null
                }
                var failureMessage: String? = null

                if (request.mode == ServerSeekRecoveryMode.REANCHOR) {
                    val result = playbackSessionManager.reanchorActiveVideoSession(
                        positionSeconds = request.targetSourceSec,
                        diagnostics = operationDiagnostics,
                    )
                    if (recoveryGeneration != playbackRecoveryGeneration) return
                    val latestAfterReanchor = queuedServerSeek
                    if (latestAfterReanchor != null) {
                        queuedServerSeek = null
                        request = latestAfterReanchor
                        continue
                    }
                    if (activeSeekId != request.seekId) return

                    when (result) {
                        is ApiResult.Success -> when (val decision = result.data) {
                            is VideoSessionStartV3.Ready -> readyDecision = decision
                            is VideoSessionStartV3.Terminal -> {
                                pinnedRequest = PinnedSeekRecoveryRequest(
                                    classification = "seek_reanchor_terminal",
                                    notice = decision.message,
                                    diagnostics = operationDiagnostics + mapOf(
                                        "reanchor_terminal_reason" to decision.reason,
                                    ),
                                )
                            }
                            VideoSessionStartV3.ServerUpgradeRequired -> {
                                failureMessage = "This Silo server does not support reliable seeking."
                            }
                        }
                        is ApiResult.Error -> if (result.error == "seek_reanchor_not_supported") {
                            failureMessage = "This Silo server does not support reliable seeking."
                        } else {
                            pinnedRequest = PinnedSeekRecoveryRequest(
                                classification = "seek_reanchor_failed",
                                notice = result.message,
                                diagnostics = operationDiagnostics + mapOf("reanchor_error" to result.error),
                            )
                        }
                        is ApiResult.NetworkError -> {
                            pinnedRequest = PinnedSeekRecoveryRequest(
                                classification = "seek_reanchor_network_failure",
                                notice = result.exception.message ?: "Seek re-anchor request failed.",
                                diagnostics = operationDiagnostics,
                            )
                        }
                    }
                }

                if (failureMessage != null) {
                    publishSeekFailure(request, recoveryGeneration, failureMessage)
                    return
                }

                val reanchoredDecision = readyDecision
                if (reanchoredDecision != null) {
                    adoptSeekRecoveryDecision(
                        decision = reanchoredDecision,
                        before = before,
                        request = request,
                        recoveryGeneration = recoveryGeneration,
                    )
                    if (recoveryGeneration != playbackRecoveryGeneration) return
                    val latestAfterAdoption = queuedServerSeek
                    if (latestAfterAdoption != null) {
                        queuedServerSeek = null
                        request = latestAfterAdoption
                        continue
                    }
                    return
                }

                val fallback = pinnedRequest ?: return
                val fallbackResult = playbackSessionManager.recoverActiveVideoSessionAfterSeek(
                    positionSeconds = request.targetSourceSec,
                    classification = fallback.classification,
                    message = fallback.notice,
                    diagnostics = fallback.diagnostics,
                )
                if (recoveryGeneration != playbackRecoveryGeneration) return
                val latestAfterFallback = queuedServerSeek
                if (latestAfterFallback != null) {
                    queuedServerSeek = null
                    request = latestAfterFallback
                    continue
                }
                if (activeSeekId != request.seekId) return

                when (fallbackResult) {
                    is ApiResult.Success -> when (val decision = fallbackResult.data) {
                        is VideoSessionStartV3.Ready -> adoptSeekRecoveryDecision(
                            decision = decision,
                            before = before,
                            request = request,
                            recoveryGeneration = recoveryGeneration,
                        )
                        is VideoSessionStartV3.Terminal -> publishSeekFailure(
                            request,
                            recoveryGeneration,
                            "Unable to seek (${decision.reason}): ${decision.message}",
                        )
                        VideoSessionStartV3.ServerUpgradeRequired -> publishSeekFailure(
                            request,
                            recoveryGeneration,
                            "This Silo server does not support reliable seeking.",
                        )
                    }
                    is ApiResult.Error -> publishSeekFailure(
                        request,
                        recoveryGeneration,
                        if (fallbackResult.error == "seek_reanchor_not_supported") {
                            "This Silo server does not support reliable seeking."
                        } else {
                            "Unable to seek (${fallbackResult.message})"
                        },
                    )
                    is ApiResult.NetworkError -> publishSeekFailure(
                        request,
                        recoveryGeneration,
                        "Unable to seek (${fallbackResult.exception.message})",
                    )
                }

                if (recoveryGeneration != playbackRecoveryGeneration) return
                val latestAfterFallbackAdoption = queuedServerSeek
                if (latestAfterFallbackAdoption != null) {
                    queuedServerSeek = null
                    request = latestAfterFallbackAdoption
                    continue
                }
                return
            }
        } finally {
            if (recoveryGeneration == playbackRecoveryGeneration) {
                serverSeekRecoveryInFlight = false
                seekRecoveryJob = null
                // A user track/quality change queued behind this seek recovery
                // can run now that the in-flight guard is released.
                redriveQueuedInvalidationReplan()
            }
        }
    }

    private fun publishSeekFailure(
        request: ServerSeekRecoveryRequest,
        recoveryGeneration: Long,
        message: String,
    ) {
        if (!isCurrentServerSeek(request, recoveryGeneration)) return
        if (request.mode == ServerSeekRecoveryMode.REANCHOR &&
            request.rollbackAllowed &&
            !seekRecoveryRollbackInvalidated
        ) {
            // A policy re-anchor is transactional: until a replacement is
            // adopted, the old Media3 item remains valid. A temporary API
            // failure therefore cancels the optimistic seek and leaves
            // playback running instead of replacing it with a fatal screen.
            val rollback = seekPresentationGuard.cancel()?.originPositionMs
                ?.div(1_000.0)
                ?: _uiState.value.position
            activeSeekTargetSec = null
            activeSeekId = null
            sameRouteSeekRecoveryAttempted = false
            seekRecoveryRollbackInvalidated = false
            positionReportsBlockedForPendingLoad = false
            awaitingMediaMountGeneration = null
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

    private fun isCurrentServerSeek(
        request: ServerSeekRecoveryRequest,
        recoveryGeneration: Long,
    ): Boolean = recoveryGeneration == playbackRecoveryGeneration &&
        activeSeekId == request.seekId &&
        queuedServerSeek == null

    private suspend fun adoptSeekRecoveryDecision(
        decision: VideoSessionStartV3.Ready,
        before: PlayerUiState,
        request: ServerSeekRecoveryRequest,
        recoveryGeneration: Long,
    ) {
        if (!isCurrentServerSeek(request, recoveryGeneration)) return
        val fileId = before.versions.getOrNull(before.selectedVersionIndex)?.fileId ?: return
        val expectedFileId = before.playbackPlan?.effectiveMediaFileId ?: fileId
        val actualFileId = decision.plan.effectiveMediaFileId ?: expectedFileId
        if (actualFileId != expectedFileId) {
            publishSeekFailure(
                request,
                recoveryGeneration,
                "Seek recovery tried to change the selected media version.",
            )
            return
        }
        val sourcePosition = decision.plan.timeline.sourceStartSeconds
            .takeIf { it.isFinite() && it >= 0.0 }
            ?: request.targetSourceSec
        val catalogVersion = before.versions.getOrNull(before.selectedVersionIndex)
        val authoritativeSubtitles = enrichAuthoritativePlaybackSubtitleChoices(
            catalogTracks = catalogVersion?.subtitleTracks.orEmpty(),
            plannedTracks = decision.session.subtitleUrls.orEmpty(),
        )
        val returnedSubtitleIndex = decision.plan.resolvedSelectedSubtitleIndex()
        val returnedSubtitleOrdinal = returnedSubtitleIndex?.let { serverIndex ->
            authoritativeSubtitles.indexOfFirst { it.index == serverIndex }.takeIf { it >= 0 }
        } ?: -1
        val returnedSubtitleIdentity = authoritativeSubtitles
            .getOrNull(returnedSubtitleOrdinal)
            ?.let(::mobileSubtitleIdentity)
            ?: SubtitleIdentity.Off
        val returnedAudioIndex = decision.plan.selectedTracks.audio?.index
            ?: decision.session.audioTrackIndex
        seekRecoveryRollbackInvalidated = false
        // Conditional, evaluated inside the lifecycle lock. An unconditional
        // adopt only checks currency before and after, so a seek superseded
        // while this awaited the lock still handed the lifecycle a session this
        // screen had stopped owning.
        val adopted = sessionLifecycle.adoptActiveSessionIfCurrent(
            params = StartParams(
                contentId = before.contentId,
                fileId = actualFileId,
                capabilities = decision.capabilities,
                audioTrackIndex = returnedAudioIndex,
                subtitleTrackIndex = returnedSubtitleIndex ?: -1,
                qualityPreference = currentMobileQualityPreference(),
                startPosition = sourcePosition,
                clientPlaybackContext = decision.clientPlaybackContext,
            ),
            session = decision.session.copy(subtitleUrls = authoritativeSubtitles),
            isCurrent = { isCurrentServerSeek(request, recoveryGeneration) },
        )
        // Deliberately no stop on refusal, unlike the replan paths. A seek
        // re-anchor is validated to reuse the SAME session id — the manager
        // rejects any response that changes it — so there is no disposable
        // candidate here. The id names the session still playing, and the
        // ordinary reason for refusal is that a newer seek was queued, which
        // needs that very session as its base.
        if (!adopted) return
        // Same rule as the other two adoption paths: the exit token names what
        // the lifecycle owns, from the moment it owns it. Supersession or
        // cancellation before the UI publication below would otherwise leave
        // exit naming the predecessor and the replacement running.
        retainedOwnedSessionId = decision.session.sessionId
        if (!isCurrentServerSeek(request, recoveryGeneration)) return
        currentCoroutineContext().ensureActive()
        val mountGeneration = expectNextMediaMount()
        _uiState.update { current ->
            current.copy(
                error = null,
                isBuffering = false,
                sessionId = decision.session.sessionId
                    .also { retainedOwnedSessionId = it },
                playMethod = decision.session.playMethod,
                playbackPlan = decision.session.playbackPlan,
                delivery = decision.plan.delivery,
                streamUrl = decision.plan.stream.url,
                requestHeaders = decision.plan.stream.headers,
                container = decision.plan.stream.container ?: current.container,
                startPosition = decision.plan.timeline.playerStartSeconds,
                mediaMountGeneration = mountGeneration,
                position = sourcePosition,
                bufferedPosition = sourcePosition,
                subtitleTracks = authoritativeSubtitles,
                selectedSubtitleIndex = returnedSubtitleOrdinal,
                committedSubtitleIdentity = returnedSubtitleIdentity,
                selectedAudioIndex = selectedAudioTrackOrdinal(
                    returnedAudioIndex,
                    current.audioTracks,
                ),
            )
        }
        val recoveredState = _uiState.value
        mobileSubtitleTransactions.resetContent(
            context = mobileSubtitleContext(recoveredState),
            committedIdentity = returnedSubtitleIdentity,
        )
        mobileSubtitleTransactions.restoreCommittedLocalMount()
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
     * Google Cast (Chromecast) Tier-2 session start. Opens a SEPARATE
     * cast-capability playback session (H.264/AAC/MP4) off the live player state
     * so the dongle gets a stream it can actually fetch and decode — never the
     * phone's current (possibly MKV/HEVC, header-authenticated) stream. Returns
     * the self-contained cast media spec, or null when unavailable.
     */
    private val castPrepareLock = Any()
    private var castPrepareShared: Pair<Int, kotlinx.coroutines.Deferred<CastMediaSpec?>>? = null

    /**
     * Single-flight per file: the cast button and the connect-time auto-stage
     * effect both land here, and each server prepare burns a playback/start
     * (tight rate limit) plus a session. Concurrent and back-to-back callers
     * share one Deferred; running it in [viewModelScope] also survives the
     * caller's cancellation (a LaunchedEffect restart used to abort the
     * prepare mid-flight and orphan its server session).
     */
    suspend fun prepareGoogleCastMedia(): CastMediaSpec? {
        val fileId = _uiState.value.mediaFileId ?: return null
        val shared = synchronized(castPrepareLock) {
            val existing = castPrepareShared
            if (existing != null && existing.first == fileId && existing.second.isActive) {
                existing.second
            } else {
                viewModelScope.async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                    doPrepareGoogleCastMedia(fileId)
                }.also { castPrepareShared = fileId to it }
            }
        }
        return shared.await()
    }

    private suspend fun doPrepareGoogleCastMedia(fileId: Int): CastMediaSpec? {
        val preparer = castPlaybackPreparer ?: return null
        val state = _uiState.value
        val profileId = profileRepository.getActiveProfileId() ?: return null
        val audioTrackIndex = selectedServerAudioTrackIndex(
            selectedOrdinal = state.selectedAudioIndex,
            audioTracks = state.versions.getOrNull(state.selectedVersionIndex)?.audioTracks.orEmpty(),
        )
        val subtitleTrackIndex = selectedServerSubtitleTrackIndex(
            selectedOrdinal = state.selectedSubtitleIndex,
            subtitleTracks = state.subtitleTracks,
        )
        return preparer.prepareCastMedia(
            CastPrepareRequest(
                fileId = fileId,
                profileId = profileId,
                startPositionSeconds = state.position,
                audioTrackIndex = audioTrackIndex,
                subtitleTrackIndex = subtitleTrackIndex,
                title = state.title,
                posterUrl = state.artworkUrl,
                appVersion = BuildConfig.VERSION_NAME,
                buildIdentity = capabilityDetector.buildIdentity,
            ),
        )
    }

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
     * Adopt server-recomputed marker ranges (a `markers_updated` event).
     * The intro auto-skip observer and the credits-based F2 trigger read these
     * from UiState, so updating them takes effect immediately. Passing `null`
     * clears a marker the server says no longer applies.
     */
    fun applyUpdatedMarkers(intro: TimeRange?, credits: TimeRange?, recap: TimeRange?, preview: TimeRange?) {
        _uiState.update { it.copy(intro = intro, credits = credits, recap = recap, preview = preview) }
    }

    private fun mobileSubtitleContext(state: PlayerUiState): MobileSubtitlePlaybackContext =
        MobileSubtitlePlaybackContext(
            contentId = state.contentId,
            mediaFileId = state.mediaFileId ?: -1,
            versionId = "${state.selectedVersionIndex}:${state.mediaFileId ?: -1}",
            sessionId = state.sessionId,
            positionSeconds = state.position,
            audioTrackIndex = selectedServerAudioTrackIndex(
                selectedOrdinal = state.selectedAudioIndex,
                audioTracks = state.versions
                    .getOrNull(state.selectedVersionIndex)
                    ?.audioTracks
                    .orEmpty(),
            ),
            qualityPreference = currentMobileQualityPreference(),
            subtitleTracks = state.subtitleTracks,
            audioTracks = state.audioTracks,
            writeScope = finalPositionScope,
        )

    private fun currentMobileQualityPreference(): String? =
        routeIntentState.current?.quality ?: lastLoadArgs?.preferredQuality

    private fun applyMobileSubtitleSnapshot(snapshot: MobileSubtitleTransactionSnapshot) {
        _uiState.update { state ->
            state.copy(
                selectedAudioIndex = snapshot.transition.committed.audioTrackIndex
                    ?.let { selectedAudioTrackOrdinal(it, state.audioTracks) }
                    ?: state.selectedAudioIndex,
                selectedSubtitleIndex = resolveMobileSubtitleOrdinal(
                    snapshot.committedIdentity,
                    state.subtitleTracks,
                ) ?: state.selectedSubtitleIndex,
                committedSubtitleIdentity = snapshot.committedIdentity,
                pendingSubtitleIdentity = snapshot.pendingIdentity,
                localSubtitleMountIdentity = snapshot.localMountIdentity,
                subtitleApplying = snapshot.subtitleApplying,
            )
        }
        val state = _uiState.value
        routeIntentState.applyCommittedTracks(
            contentId = state.contentId,
            committedAudioServerIndex = snapshot.transition.committed.audioTrackIndex,
            committedSubtitleIdentity = snapshot.committedIdentity,
            transactionFailed = snapshot.failureMessage != null,
            transactionActive = mobileSubtitleTransactions.hasActiveTransaction,
        )
        snapshot.failureMessage?.let {
            showVersionSwitchMessage("Couldn't apply subtitles — playback continues unchanged.")
        }
        if (!mobileSubtitleTransactions.hasActiveTransaction) {
            redriveQueuedInvalidationReplan()
        }
    }

    private suspend fun adoptMobileSubtitlePlayback(
        adoption: MobileSubtitlePlaybackAdoption,
    ): MobileSubtitleAdoptionResult {
        val playback = adoption.playback
        val committed = adoption.committed
        val ready = playback.ready ?: return MobileSubtitleAdoptionResult.Adopted
        val before = _uiState.value
        val predecessorFileId = before.mediaFileId
            ?: return MobileSubtitleAdoptionResult.Superseded
        val effectiveFileId = ready.session.mediaFileId.takeIf { it > 0 }
            ?: ready.plan.effectiveMediaFileId
            ?: predecessorFileId
        val catalogVersionIndex = before.versions.indexOfFirst { it.fileId == effectiveFileId }
        val effectiveVersions = if (catalogVersionIndex >= 0) {
            before.versions
        } else {
            before.versions + FileVersion(fileId = effectiveFileId)
        }
        val effectiveVersionIndex = catalogVersionIndex.takeIf { it >= 0 }
            ?: effectiveVersions.lastIndex
        val effectiveVersion = effectiveVersions[effectiveVersionIndex]
        val authoritativeSubtitles = enrichAuthoritativePlaybackSubtitleChoices(
            catalogTracks = effectiveVersion.subtitleTracks.orEmpty(),
            plannedTracks = playback.subtitleTracks.filterNot(
                PlayerSubtitleInfo::isLocalDownloadedSubtitle,
            ),
        )
        val downloaded = if (effectiveFileId == predecessorFileId) {
            playback.subtitleTracks
                .filter(PlayerSubtitleInfo::isLocalDownloadedSubtitle)
                .filterNot { local ->
                    authoritativeSubtitles.any { it.index == local.index }
                }
        } else {
            emptyList()
        }
        val effectiveSubtitles = authoritativeSubtitles + downloaded
        val returnedAudioIndex = ready.plan.selectedTracks.audio?.index
            ?: ready.session.audioTrackIndex
        val returnedSubtitleIndex = ready.plan.resolvedSelectedSubtitleIndex()
        val remountPosition = ready.plan.timeline.replanMountPositionForSource(
            adoption.requestedSourcePositionSeconds,
        )
        val sourcePosition = remountPosition.sourcePositionSeconds
        if (!adoption.isCurrent()) return MobileSubtitleAdoptionResult.Superseded
        val lifecycleAdopted = sessionLifecycle.adoptActiveSessionIfCurrent(
            params = StartParams(
                contentId = before.contentId,
                fileId = effectiveFileId,
                capabilities = ready.capabilities,
                audioTrackIndex = returnedAudioIndex,
                subtitleTrackIndex = returnedSubtitleIndex ?: -1,
                qualityPreference = committed.qualityPreference,
                startPosition = sourcePosition,
                clientPlaybackContext = ready.clientPlaybackContext,
            ),
            session = ready.session.copy(subtitleUrls = effectiveSubtitles),
            isCurrent = adoption::isCurrent,
        )
        // The lifecycle owns this session from here, so the exit token has to
        // name it from here — not from the UI publication below. Supersession
        // between the two abandons the manager's session without rolling the
        // lifecycle back, and exit would otherwise name the predecessor, be
        // rightly refused by the ownership guard, and leave the replacement
        // running with the teardown gate stopping onCleared from retrying.
        if (lifecycleAdopted) {
            playback.sessionId?.let { retainedOwnedSessionId = it }
        }
        if (!lifecycleAdopted || !adoption.isCurrent()) {
            return MobileSubtitleAdoptionResult.Superseded
        }

        val mountGeneration = expectNextMediaMount()
        val pendingIdentity = adoption.pendingIdentity()
        _uiState.update { current ->
            current.copy(
                error = null,
                sessionId = playback.sessionId
                    ?.also { retainedOwnedSessionId = it },
                playMethod = ready.session.playMethod,
                playbackPlan = ready.session.playbackPlan,
                delivery = ready.plan.delivery,
                streamUrl = ready.plan.stream.url,
                requestHeaders = ready.plan.stream.headers,
                container = ready.plan.stream.container
                    ?: effectiveVersion.container
                    ?: current.container.takeIf { effectiveFileId == predecessorFileId },
                startPosition = remountPosition.playerPositionSeconds,
                mediaMountGeneration = mountGeneration,
                versions = effectiveVersions,
                selectedVersionIndex = effectiveVersionIndex,
                position = sourcePosition,
                duration = ready.session.durationSeconds ?: 0.0,
                serverDuration = ready.session.durationSeconds ?: 0.0,
                subtitleTracks = effectiveSubtitles,
                audioTracks = effectiveVersion.audioTracks.orEmpty(),
                selectedAudioIndex = selectedAudioTrackOrdinal(
                    returnedAudioIndex,
                    effectiveVersion.audioTracks.orEmpty(),
                ),
                selectedSubtitleIndex = resolveMobileSubtitleOrdinal(
                    committed.identity,
                    effectiveSubtitles,
                ) ?: current.selectedSubtitleIndex,
                committedSubtitleIdentity = committed.identity,
                chapters = effectiveVersion.chapters.orEmpty(),
                pendingSubtitleIdentity = pendingIdentity,
                localSubtitleMountIdentity = null,
                subtitleApplying = pendingIdentity != null,
                subtitleRefreshNonce = 0,
            )
        }
        Log.i(
            TAG,
            "subtitle_replan_mount restored_source_seconds=$sourcePosition " +
                "player_seconds=${remountPosition.playerPositionSeconds}",
        )
        return MobileSubtitleAdoptionResult.Adopted
    }

    private suspend fun recoverFromSubtitleAdoptionFailure(detail: String) {
        val state = _uiState.value
        showVersionSwitchMessage("Couldn't finish the subtitle change — restarting playback.")
        sessionLifecycle.stop()
        loadContent(
            contentId = state.contentId,
            preferredFileId = state.mediaFileId,
            initialAudioTrackIndex = state.selectedAudioIndex,
            initialSubtitleTrackIndex = state.selectedSubtitleIndex,
            resumePositionOverride = state.position,
            suppressResumeRewind = true,
            preserveRouteIntent = true,
        )
        Log.w(TAG, "Subtitle committed-playback adoption failed: $detail")
    }

    /** Select a subtitle track (-1 to disable). */
    fun onSelectSubtitle(index: Int) {
        val state = _uiState.value
        if (index != -1 && index !in state.subtitleTracks.indices) return
        val identity = state.subtitleTracks
            .getOrNull(index)
            ?.let(::mobileSubtitleIdentity)
            ?: SubtitleIdentity.Off
        routeIntentState.beginSubtitleSelection(
            contentId = state.contentId,
            routeOrdinal = catalogSubtitleRouteOrdinal(state, identity),
            identity = identity,
        )
        mobileSubtitleTransactions.updatePlaybackContext(mobileSubtitleContext(state))
        mobileSubtitleTransactions.select(identity)
    }

    fun onPendingSubtitleMountResult(
        identity: SubtitleIdentity,
        selected: Boolean,
        snapshotKey: String?,
        settled: Boolean,
    ) {
        mobileSubtitleTransactions.reportMountedSelection(
            identity = identity,
            selected = selected,
            snapshotKey = snapshotKey,
            settled = settled,
        )
    }

    /** Select an audio track (may require server-side switch). */
    fun onSelectAudio(index: Int) = selectAudio(index, userInitiated = true)

    private fun selectAudio(index: Int, userInitiated: Boolean) {
        val state = _uiState.value
        val serverIndex = selectedServerAudioTrackIndex(index, state.audioTracks) ?: return
        if (userInitiated) {
            routeIntentState.beginAudioSelection(
                contentId = state.contentId,
                routeOrdinal = index,
                serverIndex = serverIndex,
            )
        }
        setDesiredAudio(serverIndex, explicit = userInitiated)
        // Already in the mounted stream: switch it on the player instead of
        // rebuilding the session to deliver audio already being received. A
        // replan is only needed when the track is genuinely absent.
        if (matchMountedAudioTrack(
                state.audioTracks[serverIndex],
                mountedAudio,
            ) != null
        ) {
            return
        }
        mobileSubtitleTransactions.updatePlaybackContext(mobileSubtitleContext(state))
        mobileSubtitleTransactions.selectAudio(serverIndex)
    }

    // ---- Desired audio ------------------------------------------------------
    //
    // Mirrors TV: one generation-owned intent that every entry point writes,
    // reconciled against each track snapshot by the shared decision.

    private var desiredAudioGeneration = 0L
    private var desiredAudio: DesiredAudio? = null
    private var localAudioAttempt = 0L
    private var mountedAudio: List<MountedAudioTrack> = emptyList()

    private val _pendingLocalAudioSelection = MutableStateFlow<LocalAudioSelection?>(null)

    /** A mounted track PlayerScreen should select directly on the player. */
    val pendingLocalAudioSelection: StateFlow<LocalAudioSelection?> =
        _pendingLocalAudioSelection.asStateFlow()

    private fun setDesiredAudio(catalogOrdinal: Int, explicit: Boolean) {
        desiredAudioGeneration += 1
        localAudioAttemptCount = 0
        val state = _uiState.value
        desiredAudio = DesiredAudio(
            generation = desiredAudioGeneration,
            catalogOrdinal = catalogOrdinal,
            explicit = explicit,
            fileId = state.mediaFileId,
        )
        _pendingLocalAudioSelection.value = null
        reconcileDesiredAudio(mountedAudio, selectedMountedAudioOrdinal)
    }

    private var selectedMountedAudioOrdinal: Int? = null

    /** Called by PlayerScreen on every Media3 track snapshot. */
    fun onMountedAudioChanged(mounted: List<MountedAudioTrack>, selectedOrdinal: Int?) {
        mountedAudio = mounted
        selectedMountedAudioOrdinal = selectedOrdinal
        reconcileDesiredAudio(mounted, selectedOrdinal)
    }

    private fun reconcileDesiredAudio(mounted: List<MountedAudioTrack>, selectedOrdinal: Int?) {
        val desired = desiredAudio ?: return
        val state = _uiState.value
        when (
            val action = reconcileDesiredAudioAction(
                desired = desired,
                activeFileId = state.mediaFileId,
                catalog = state.audioTracks,
                mounted = mounted,
                selectedOrdinal = selectedOrdinal,
                planAudioOrdinal = state.playbackPlan?.selectedTracks?.audioIndex,
            )
        ) {
            AudioReconcileAction.None -> Unit

            AudioReconcileAction.DropForeignFile -> {
                desiredAudio = null
                _pendingLocalAudioSelection.value = null
            }

            AudioReconcileAction.Confirm -> {
                _pendingLocalAudioSelection.value = null
                if (!desired.confirmed) {
                    desiredAudio = desired.copy(confirmed = true)
                    commitLocalAudio(desired)
                }
            }

            is AudioReconcileAction.Apply -> {
                // AudioTrackManager returns Unit and does nothing silently when
                // the group has gone, and a no-op produces no callback -- so an
                // unbounded local path can dead-end with the audio never
                // applied. After a few snapshots that still have not taken, hand
                // it to the server instead of retrying forever.
                if (localAudioAttemptsFor(desired.generation) >= MAX_LOCAL_AUDIO_ATTEMPTS) {
                    _pendingLocalAudioSelection.value = null
                    replanForDesiredAudio(desired)
                    return
                }
                localAudioAttempt += 1
                localAudioAttemptGeneration = desired.generation
                localAudioAttemptCount += 1
                if (desired.confirmed) desiredAudio = desired.copy(confirmed = false)
                _pendingLocalAudioSelection.value = LocalAudioSelection(
                    generation = desired.generation,
                    catalogOrdinal = desired.catalogOrdinal,
                    targetOrdinal = action.targetOrdinal,
                    attempt = localAudioAttempt,
                )
            }
        }
    }

    /**
     * Publishes a locally-applied switch as committed state.
     *
     * A replan commit reaches all of this through
     * [applyMobileSubtitleSnapshot]; the local path bypasses the transaction
     * entirely, so without this the picker keeps the old checkmark, route
     * redelivery reports the old ordinal, and a later recovery, Cast handoff or
     * subtitle transaction starts from stale audio.
     */
    private fun commitLocalAudio(desired: DesiredAudio) {
        _uiState.update { it.copy(selectedAudioIndex = desired.catalogOrdinal) }
        val state = _uiState.value
        routeIntentState.applyCommittedTracks(
            contentId = state.contentId,
            committedAudioServerIndex = desired.catalogOrdinal,
            committedSubtitleIdentity = state.committedSubtitleIdentity,
            transactionFailed = false,
            transactionActive = mobileSubtitleTransactions.hasActiveTransaction,
        )
        // The reducer's committed audio is what the next subtitle transaction
        // stages and what teardown persists, so it has to move too -- updating
        // the context alone left it stale and the choice got undone.
        mobileSubtitleTransactions.commitLocallyAppliedAudio(desired.catalogOrdinal)
        if (desired.explicit) persistDesiredAudio(desired.catalogOrdinal)
    }

    private var localAudioAttemptGeneration = 0L
    private var localAudioAttemptCount = 0

    private fun localAudioAttemptsFor(generation: Long): Int =
        if (localAudioAttemptGeneration == generation) localAudioAttemptCount else 0

    /** The local switch is not taking; let the server materialise the track. */
    private fun replanForDesiredAudio(desired: DesiredAudio) {
        val state = _uiState.value
        mobileSubtitleTransactions.updatePlaybackContext(mobileSubtitleContext(state))
        mobileSubtitleTransactions.selectAudio(desired.catalogOrdinal)
    }

    private fun persistDesiredAudio(catalogOrdinal: Int) {
        val state = _uiState.value
        val context = mobileSubtitleContext(state)
        val scope = context.writeScope ?: return
        viewModelScope.launch {
            runCatching {
                userItemStatePort.recordTrackSelection(
                    scope = scope,
                    contentId = context.contentId,
                    fileId = context.mediaFileId,
                    audioUpdate = mobileAudioTrackPersistenceUpdate(
                        committedAudioTrackIndex = catalogOrdinal,
                        audioTracks = context.audioTracks,
                    ),
                    // Untouched: this path changed audio only.
                    subtitleUpdate = TrackSelectionFingerprintUpdate.Preserve,
                )
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

    /** Applies the exact inventory row minted by the V3 server. */
    fun applySubtitleReady(update: PlaybackSubtitleReady) {
        val state = _uiState.value
        val sessionId = state.sessionId ?: return
        if (update.sessionId != null && update.sessionId != sessionId) return
        if (update.mediaFileId != null && update.mediaFileId != state.mediaFileId) return
        val merged = applyAuthoritativeSubtitleReadyTrack(state.subtitleTracks, update)
        if (merged == null) {
            startProtocolV3Replan(
                classification = "subtitle_inventory_changed",
                notice = "Subtitle inventory changed. Refreshing playback metadata.",
                state = state,
            )
            return
        }
        mobileSubtitleTransactions.updatePlaybackContext(mobileSubtitleContext(state))
        val owner = mobileSubtitleTransactions.beginRefresh()
        if (!mobileSubtitleTransactions.ownsRefresh(owner)) return
        _uiState.update {
            it.copy(
                subtitleTracks = merged,
                subtitleRefreshNonce = it.subtitleRefreshNonce + 1,
            )
        }
        mobileSubtitleTransactions.updatePlaybackContext(mobileSubtitleContext(_uiState.value))
        val subtitleId = update.subtitleId
        val added = update.track?.trackId?.let { trackId ->
            merged.singleOrNull { it.serverTrackId == trackId }
        }
        if (subtitleId != null && added != null) {
            authoritativeSubtitleReadyRows[sessionId to subtitleId] = added
        }
        if (subtitleId != null && pendingAuthoritativeSubtitleDownloadId == subtitleId) {
            val selected = added
                ?.let(::mobileSubtitleIdentity)
                ?.let { mobileSubtitleTransactions.selectFromRefresh(owner, it) }
                ?: false
            if (selected) pendingAuthoritativeSubtitleDownloadId = null
        }
    }

    private suspend fun doRefreshSubtitles(autoSelectSubtitleId: Int?) {
        val state = _uiState.value
        val mediaFileId = state.mediaFileId ?: return
        // Inert without a remote session (offline/local playback has no
        // session-scoped subtitle URLs to merge into).
        val sessionId = state.sessionId ?: return
        if (state.playbackPlan != null) {
            pendingAuthoritativeSubtitleDownloadId = autoSelectSubtitleId
            val readyRow = autoSelectSubtitleId?.let { id ->
                authoritativeSubtitleReadyRows[sessionId to id]
            }
            if (readyRow != null) {
                mobileSubtitleTransactions.updatePlaybackContext(mobileSubtitleContext(state))
                val owner = mobileSubtitleTransactions.beginRefresh()
                if (mobileSubtitleTransactions.selectFromRefresh(owner, mobileSubtitleIdentity(readyRow))) {
                    pendingAuthoritativeSubtitleDownloadId = null
                }
            }
            return
        }
        mobileSubtitleTransactions.updatePlaybackContext(mobileSubtitleContext(state))
        val owner = mobileSubtitleTransactions.beginRefresh()
        val downloaded = when (val r = subtitlesRepository.list(mediaFileId)) {
            is ApiResult.Success -> r.data.subtitles
            else -> return // best effort — refresh failure must not disrupt playback (web parity)
        }
        if (downloaded.isEmpty()) return
        mobileSubtitleTransactions.updatePlaybackContext(mobileSubtitleContext(_uiState.value))
        if (!mobileSubtitleTransactions.ownsRefresh(owner)) return
        val current = _uiState.value
        val merged = mergeDownloadedSubtitles(
            existing = current.subtitleTracks,
            downloaded = downloaded,
            sessionId = sessionId,
            serverUrl = current.serverUrl,
        )
        val autoIndex = autoSelectSubtitleId?.let { id -> downloadedTrackIndex(merged, downloaded, id) }
        _uiState.update {
            it.copy(
                subtitleTracks = merged,
                subtitleRefreshNonce = it.subtitleRefreshNonce + 1,
            )
        }
        mobileSubtitleTransactions.updatePlaybackContext(mobileSubtitleContext(_uiState.value))
        autoIndex
            ?.let(merged::getOrNull)
            ?.let(::mobileSubtitleIdentity)
            ?.let { identity ->
                mobileSubtitleTransactions.selectFromRefresh(owner, identity)
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

    // ---- F2 next-episode auto-advance + pass-out protection ----

    /**
     * Resolve the next episode for this item (no-op for movies). Pools the
     * current season's episodes plus the next REGULAR season's (specials
     * excluded) and finds the immediate next via [nextEpisodeAfter]. The current
     * season must load (a partial failure must not skip the rest of it).
     */
    /**
     * Populates the Next-Up screen's On Deck carousel (iOS
     * `loadNextUpOnDeckItems` parity): home continue-watching pools, minus
     * the current item and anything from the same series, deduped, capped at
     * 12, and dropped when no 16:9 art exists.
     */
    private fun loadOnDeckItems() {
        val repository = sectionRepository ?: return
        val forContentId = _uiState.value.contentId
        val currentSeriesId = _uiState.value.seriesId
        viewModelScope.launch {
            val sections = (repository.getHomeSections() as? ApiResult.Success)
                ?.data?.sections ?: return@launch
            val pool = sections
                .filter { it.sectionType in ON_DECK_SECTION_TYPES }
                .flatMap { it.items }
                .filter { item ->
                    item.contentId != forContentId &&
                        (currentSeriesId == null || item.seriesId != currentSeriesId)
                }
                .filter { !it.backdropUrl.isNullOrBlank() }
                .distinctBy { it.contentId }
                .take(ON_DECK_MAX_ITEMS)
                .map { item ->
                    val progress = item.positionSeconds?.let { pos ->
                        item.durationSeconds?.takeIf { it > 0 }?.let { dur ->
                            (pos / dur).toFloat().coerceIn(0f, 1f)
                        }
                    }
                    OnDeckItem(
                        contentId = item.contentId,
                        title = item.seriesTitle ?: item.title,
                        subtitle = when {
                            item.seasonNumber != null && item.episodeNumber != null ->
                                "S${item.seasonNumber}·E${item.episodeNumber}" +
                                    (item.title.takeIf { it.isNotBlank() }?.let { " — $it" } ?: "")
                            item.year > 0 -> item.year.toString()
                            else -> null
                        },
                        artUrl = item.backdropUrl,
                        artThumbhash = item.backdropThumbhash,
                        progressFraction = progress,
                    )
                }
            _uiState.update {
                if (it.contentId != forContentId) it else it.copy(onDeckItems = pool)
            }
        }
    }

    /** On Deck tap — an explicit choice: reset the pass-out streak and load
     *  the picked item in place (resuming its saved position, iOS parity). */
    fun playOnDeckItemNow(contentId: String) {
        autoPlayGuard.recordUserAction()
        upNextCountdownJob?.cancel()
        upNextCountdownJob = null
        _uiState.update { it.copy(showUpNext = false, upNextCountdownSeconds = null) }
        viewModelScope.launch {
            sessionLifecycle.stop()
            loadContent(contentId = contentId)
        }
    }

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
            val info = NextEpisodeInfo(
                contentId = next.contentId,
                seasonNumber = next.seasonNumber,
                episodeNumber = next.episodeNumber,
                title = next.title,
                stillUrl = next.stillUrl,
                stillThumbhash = next.stillThumbhash,
                runtimeMinutes = next.runtime,
            )
            _uiState.update {
                // Drop the result if the player has since moved to another item.
                if (it.contentId != forContentId) return@update it
                it.copy(nextEpisode = info)
            }
            if (_uiState.value.contentId != forContentId) return@launch
            // If the credits/end point already fired while we were still
            // resolving, the card couldn't arm — commit it now with the
            // strongest video-ended flag observed.
            if (!autoAdvanceHandled) {
                pendingApproachingEndVideoEnded?.let { videoEnded ->
                    commitApproachingEnd(info, videoEnded)
                }
            }
        }
    }

    /**
     * Credits reached (primary) or stream ended (fallback) — surface the Up Next
     * card. When auto-play is on and the consecutive-auto-advance streak is
     * below the pass-out threshold, the card runs a countdown that plays the
     * next episode at zero. Once the streak hits the threshold (or auto-play is
     * off), the card shows WITHOUT a countdown so the user must explicitly
     * choose (the pass-out gate). Once-per-episode.
     *
     * [videoEnded] is true on STATE_ENDED — the card reads "Playing Next"; a
     * repeat call while the card is showing only upgrades that flag.
     */
    fun onApproachingEnd(videoEnded: Boolean = false) {
        // Watch Together is authoritative — never auto-advance a room member.
        if (remoteTransportSuppressed) return
        if (autoAdvanceHandled) {
            if (videoEnded && _uiState.value.showUpNext) {
                _uiState.update { it.copy(upNextVideoEnded = true) }
            }
            return
        }
        val next = _uiState.value.nextEpisode
        if (next == null) {
            // Next episode hasn't resolved yet (or never will — last episode /
            // movie). Record that the end point fired so resolveNextEpisode can
            // commit the card if a next episode lands moments later. On a true
            // end with nothing to advance to, show the Next-Up screen in its
            // finished state (iOS shows the screen with On Deck only).
            pendingApproachingEndVideoEnded = videoEnded || (pendingApproachingEndVideoEnded == true)
            if (videoEnded) {
                // Show the finished state now, but keep the pending flag
                // latched and autoAdvanceHandled clear: a next episode that
                // resolves moments later (slow network/server) must still
                // upgrade this screen to the countdown/auto-advance commit.
                _uiState.update {
                    it.copy(showUpNext = true, upNextVideoEnded = true, upNextCountdownSeconds = null)
                }
            }
            return
        }
        commitApproachingEnd(next, videoEnded)
    }

    private fun commitApproachingEnd(next: NextEpisodeInfo, videoEnded: Boolean) {
        autoAdvanceHandled = true
        pendingApproachingEndVideoEnded = null
        // Gate check happens at commit; recordAutoAdvance only on the unattended
        // countdown-expiry path (AutoPlayGuard's documented call sequence) — so
        // after N unattended advances the (N+1)th card appears without a countdown.
        val gated = autoPlayGuard.shouldGate()
        val autoCountdown = autoPlayNextEnabled.value && !gated
        val current = _uiState.value
        // Pre-end commits anchor the countdown to the remaining playback time
        // (see startUpNextCountdown); only an at-end commit uses the wall clock.
        val initialCountdown = when {
            !autoCountdown -> null
            videoEnded -> UP_NEXT_COUNTDOWN_SECONDS
            else -> kotlin.math.ceil((current.duration - current.position).coerceAtLeast(0.0)).toInt()
        }
        _uiState.update {
            it.copy(
                showUpNext = true,
                upNextVideoEnded = videoEnded,
                upNextCountdownSeconds = initialCountdown,
            )
        }
        if (autoCountdown) startUpNextCountdown()
    }

    private fun startUpNextCountdown() {
        upNextCountdownJob?.cancel()
        upNextCountdownJob = viewModelScope.launch {
            // Two anchors (iOS parity — silo-apple's PlayerViewModel derives the
            // countdown from movieTime and only auto-plays once playback truly
            // ends):
            //  - Card committed BEFORE the end (credits / prompt crossing): the
            //    countdown mirrors the remaining playback time, so it freezes on
            //    pause, grows on a backward seek, and the advance fires only when
            //    the player reports STATE_ENDED — never truncating the tail of an
            //    episode that lacks a credits marker.
            //  - Card committed AT the end (STATE_ENDED with no earlier crossing):
            //    there is no playback left to anchor to, so a short wall-clock
            //    countdown gives the user a window to cancel before auto-play.
            val startedAtEnd = _uiState.value.upNextVideoEnded
            var wallRemaining = UP_NEXT_COUNTDOWN_SECONDS
            while (true) {
                delay(1_000)
                val state = _uiState.value
                // Bail if something dismissed the card underneath us.
                if (!state.showUpNext) return@launch
                val remaining = if (startedAtEnd) {
                    wallRemaining -= 1
                    wallRemaining.coerceAtLeast(0)
                } else {
                    kotlin.math.ceil((state.duration - state.position).coerceAtLeast(0.0)).toInt()
                }
                _uiState.update {
                    if (!it.showUpNext) it else it.copy(upNextCountdownSeconds = remaining)
                }
                if (!_uiState.value.showUpNext) return@launch
                val playbackEnded =
                    if (startedAtEnd) wallRemaining <= 0 else _uiState.value.upNextVideoEnded
                if (!playbackEnded) continue
                // Unattended countdown-expiry advance: increment the pass-out streak
                // so a long unattended binge eventually trips the gate. An explicit
                // Play Now (below) resets the streak instead.
                autoPlayGuard.recordAutoAdvance()
                advanceToNextEpisode()
                return@launch
            }
        }
    }

    /** Up Next "Play Now" — an explicit choice, so it resets the pass-out streak. */
    fun playUpNextNow() {
        autoPlayGuard.recordUserAction()
        advanceToNextEpisode()
    }

    /**
     * Up Next dismiss — cancel the countdown and stay on the current playback.
     * Does NOT re-arm [autoAdvanceHandled]: the card is once-per-episode.
     */
    fun dismissUpNext() {
        upNextCountdownJob?.cancel()
        upNextCountdownJob = null
        _uiState.update { it.copy(showUpNext = false, upNextCountdownSeconds = null) }
    }

    /**
     * Stops the auto-advance countdown but keeps the card visible — used when a
     * deliberate transport action (pause) signals the user doesn't want to be
     * yanked to the next episode. The card degrades to the no-countdown form.
     */
    private fun cancelUpNextCountdown() {
        if (upNextCountdownJob == null) return
        upNextCountdownJob?.cancel()
        upNextCountdownJob = null
        _uiState.update { it.copy(upNextCountdownSeconds = null) }
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
        upNextCountdownJob?.cancel()
        upNextCountdownJob = null
        if (remoteTransportSuppressed) {
            _uiState.update { it.copy(showUpNext = false, upNextCountdownSeconds = null) }
            return
        }
        val nextContentId = _uiState.value.nextEpisode?.contentId ?: return
        _uiState.update { it.copy(showUpNext = false, upNextCountdownSeconds = null) }
        viewModelScope.launch {
            sessionLifecycle.stop()
            loadContent(
                contentId = nextContentId,
                resumePositionOverride = 0.0,
                suppressResumeRewind = true,
            )
        }
    }

    // ---- Settings setters (forward to per-profile DataStore) -------------------
    fun onSetPlaybackSpeed(value: Double) {
        viewModelScope.launch { playerSettingsStore.setPlaybackSpeed(value) }
    }

    fun onSetVideoGravity(value: String) {
        viewModelScope.launch { playerSettingsStore.setVideoGravity(value) }
    }

    /** HUD lock toggle — persisted like iOS's `setPlayerOrientationMode`. */
    fun onSetOrientationLocked(locked: Boolean) {
        viewModelScope.launch {
            playerSettingsStore.setOrientationMode(
                if (locked) ORIENTATION_MODE_LANDSCAPE_LOCKED else ORIENTATION_MODE_ROTATE_FREELY,
            )
        }
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

    /** Applies from the next playback start (capability payload is built per
     *  session); DV off plays base-layer HDR10, profile 5 stays DV. */
    fun onSetDolbyVisionEnabled(value: Boolean) {
        viewModelScope.launch { playerSettingsStore.setDolbyVisionEnabled(value) }
    }

    fun onSetSubtitleAppearance(value: SubtitleAppearance) {
        viewModelScope.launch { playerSettingsStore.setSubtitleAppearance(value) }
    }

    /**
     * Audio delay setter (ms). Store clamps to ±5000ms — matches iOS phone's
     * `audioSyncMs` range. SiloPlaybackService mirrors the change into
     * DelayAudioProcessor and forces a seekTo(currentPosition) so the new
     * value takes effect mid-playback.
     */
    fun onSetAudioDelay(value: Int) {
        viewModelScope.launch { playerSettingsStore.setAudioSyncMs(value) }
    }

    /**
     * Subtitle delay setter (ms). Store clamps to ±10000ms — matches iOS
     * phone's `subtitleSyncMs` range. SiloPlaybackService mirrors the
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
    fun onSelectVersion(index: Int) = startVersionPlayback(index)

    /**
     * Starts playback of [versions][index]. [isRecovery] marks a re-start of the
     * previously-playing version after a failed switch: it skips the "already on
     * this version" dedupe (recovery re-selects the version the failed attempt
     * had already written into selectedVersionIndex) and tells
     * [failVersionSwitch] that a further failure is terminal. Recovery never
     * writes an invalid selectedVersionIndex — the dedupe is bypassed by the
     * flag, not by a sentinel.
     */
    private fun startVersionPlayback(index: Int, isRecovery: Boolean = false) {
        val state = _uiState.value
        val version = state.versions.getOrNull(index) ?: return
        if (!isRecovery && index == state.selectedVersionIndex) return
        if (isRecovery) {
            routeIntentState.recoverVersionSelection(state.contentId)
        } else {
            routeIntentState.beginVersionSelection(state.contentId, version.fileId)
        }
        viewModelScope.launch {
            sessionLifecycle.stop()
            loadContent(
                contentId = state.contentId,
                preferredFileId = version.fileId,
                initialAudioTrackIndex = state.selectedAudioIndex,
                initialSubtitleTrackIndex = state.selectedSubtitleIndex,
                resumePositionOverride = state.position,
                suppressResumeRewind = true,
                preserveRouteIntent = true,
            )
        }
    }

    /**
     * Handles a failed quality/version switch. The requested version's session
     * was already stopped.
     *
     * On the FIRST failure ([isRecovery] false) we surface a dismissable pill
     * and restart the version that was playing via
     * [startVersionPlayback]`(previousIndex, isRecovery = true)`. If that
     * recovery restart ALSO fails ([isRecovery] true) the player is genuinely
     * dead — no session, stale streamUrl — so we raise a persistent error
     * screen (pre-PR behavior) rather than a transient pill that leaves a
     * black player behind.
     */
    private fun failVersionSwitch(previousIndex: Int, rawReason: String, isRecovery: Boolean) {
        if (isRecovery) {
            versionSwitchMessageJob?.cancel()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    versionSwitchMessage = null,
                    error = "Failed to switch version: $rawReason",
                )
            }
            return
        }
        showVersionSwitchMessage(humanizeVersionSwitchFailure(rawReason))
        val versions = _uiState.value.versions
        if (previousIndex in versions.indices) {
            startVersionPlayback(previousIndex, isRecovery = true)
        } else {
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    /**
     * Shows the transient version-switch pill and (re)arms its 4s auto-dismiss.
     * A single [versionSwitchMessageJob] is held and cancelled before each new
     * timer so a stale coroutine can never dismiss the current message early;
     * once armed the timer clears the message unconditionally.
     */
    private fun showVersionSwitchMessage(message: String) {
        versionSwitchMessageJob?.cancel()
        _uiState.update { it.copy(versionSwitchMessage = message) }
        versionSwitchMessageJob = viewModelScope.launch {
            kotlinx.coroutines.delay(4000)
            _uiState.update { it.copy(versionSwitchMessage = null) }
        }
    }

    private fun humanizeVersionSwitchFailure(raw: String): String = when {
        raw.contains("No lower resolution version", ignoreCase = true) ->
            "That quality isn't available for this title."
        raw.contains("transcod", ignoreCase = true) ->
            "Couldn't switch quality — transcoding unavailable."
        raw.isBlank() -> "Couldn't switch quality."
        else -> "Couldn't switch quality: $raw"
    }

    /** Dismisses the transient version-switch message. */
    fun dismissVersionSwitchMessage() {
        versionSwitchMessageJob?.cancel()
        _uiState.update { it.copy(versionSwitchMessage = null) }
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
        if (!exitPrepared.compareAndSet(false, true)) return
        routeIntentState.clear()
        resetPlaybackRecoveryState()
        loadOwners.invalidate()
        loadJob?.cancel()
        loadJob = null
        mobileSubtitleTransactions.invalidate()
        mobileSubtitleTransactions.requestDurableFinalPersistence()
        // Qualified by the session this view model actually owns. The lifecycle
        // is process-scoped, and phone navigation REPLACES the player back-stack
        // entry — so a new view model can adopt its session before the outgoing
        // one finishes tearing down, and an unqualified stop then kills the
        // playback the viewer is currently watching. TV already qualifies both
        // of its exits; phone did not.
        // Retained first, UI second. Every path that publishes a session id into
        // UI state writes this token no later, and the three adoption paths
        // (protocol-V3 replan, seek recovery, subtitle replan) write it earlier —
        // at the moment the lifecycle takes ownership. That gap is the whole
        // point: reading UI first inside it names the predecessor, the lifecycle
        // rightly refuses to stop a session it no longer owns, and the one-shot
        // gate stops onCleared from trying again. The replacement runs on.
        val ownedSessionId = retainedOwnedSessionId ?: _uiState.value.sessionId
        retainedOwnedSessionId = ownedSessionId
        viewModelScope.launch {
            mobileSubtitleTransactions.persistCommittedSelectionAndFlush()
            // Never unqualified. A null expectedSessionId disables the ownership
            // guard entirely, which is the opposite of what a missing token
            // should mean — if we cannot say which session was ours, we have no
            // business stopping anyone's.
            ownedSessionId?.let { lifecycleTeardown.stopOrdered(expectedSessionId = it) }
        }
        val state = _uiState.value
        val cid = state.contentId.takeIf { it.isNotBlank() }
        val fid = currentFileId()
        val scope = finalPositionScope
        if (scope != null && cid != null && fid != null) {
            finalPlaybackPositionWriter.submit(
                FinalPlaybackPosition(
                    scope = scope,
                    contentId = cid,
                    fileId = fid,
                    positionSeconds = state.position,
                    durationSeconds = state.duration.takeIf { it > 0.0 },
                )
            )
        }
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

    private fun buildSubtitle(watchDetail: org.siloserver.silo.model.catalog.WatchDetail): String {
        return if (watchDetail.seriesTitle != null && watchDetail.seasonNumber != null && watchDetail.episodeNumber != null) {
            val seasonEp = "S${watchDetail.seasonNumber.toString().padStart(2, '0')}E${watchDetail.episodeNumber.toString().padStart(2, '0')}"
            "${watchDetail.seriesTitle} - $seasonEp"
        } else {
            watchDetail.year?.toString() ?: ""
        }
    }

    private fun findPreferredVersion(
        watchDetail: org.siloserver.silo.model.catalog.WatchDetail,
        preferredFileId: Int?,
        preferredQuality: String?,
    ): Int {
        if (watchDetail.versions.isEmpty()) return 0
        if (preferredFileId != null) {
            val index = watchDetail.versions.indexOfFirst { it.fileId == preferredFileId }
            if (index >= 0) return index
        }
        val selected = selectPlaybackVersion(
            versions = watchDetail.versions,
            lastFileId = watchDetail.userData?.lastFileId,
            preferredQuality = preferredQuality,
        )
        return watchDetail.versions.indexOfFirst { it.fileId == selected.fileId }.takeIf { it >= 0 } ?: 0
    }


    /**
     * Offline-first playback path. Returns true (and populates UiState with a
     * file:// stream URL) when the requested content has a completed local
     * download whose bytes are still on disk. Returning false means the
     * caller should run the normal server-backed flow.
     *
     * Best-effort metadata: we try to fetch [org.siloserver.silo.repository.CatalogRepository.getWatchDetail]
     * for the title / subtitle, but tolerate failure (true offline). The
     * server-side session start, lifecycle reporter, and intro-skip observer
     * are skipped — none of them work without network and none are required
     * to actually play the local bytes.
     */
    private suspend fun tryLocalPlayback(
        contentId: String,
        preferredFileId: Int?,
        resumePositionOverride: Double?,
        loadOwner: MobilePlayerLoadOwner,
    ): Boolean {
        val media = withContext(Dispatchers.IO) {
            val (serverId, profileId) = resolveDownloadScope()
            offlineMediaResolver.findLocalMedia(
                serverId = serverId,
                profileId = profileId,
                contentId = contentId,
                requestedFileId = preferredFileId,
            )
        }
        if (!ownsLoad(loadOwner)) return false
        media ?: return false
        val sidecar = media.sidecar
        val fileId = media.fileId

        // Best-effort online metadata (richer fields: intro/credits/chapters).
        // Network failure is fine; the sidecar already has title + poster
        // so airplane-mode playback still has something to render.
        val watchDetail = when (val r = catalogRepository.getWatchDetail(contentId)) {
            is ApiResult.Success -> r.data
            else -> null
        }
        if (!ownsLoad(loadOwner)) return false
        val title = watchDetail?.title ?: sidecar.title
        val subtitle = watchDetail?.let { buildSubtitle(it) } ?: sidecar.subtitle.orEmpty()
        val versions = watchDetail?.versions?.takeIf { it.isNotEmpty() }
            ?: listOf(
                org.siloserver.silo.model.catalog.FileVersion(fileId = fileId),
            )
        val selectedIndex = versions.indexOfFirst { it.fileId == fileId }
            .coerceAtLeast(0)
        // Offline-safe resume: the server's watchDetail may be stale or absent in
        // airplane mode, so fold in the locally-recorded position and take the
        // furthest of the two (matches the server's GREATEST semantics).
        val localPos = userItemStatePort.localPosition(contentId, fileId)
        if (!ownsLoad(loadOwner)) return false
        val detailPos = listOfNotNull(watchDetail?.userData?.positionSeconds, localPos).maxOrNull()
        val startPos = resolvePlaybackStartPosition(
            overridePosition = resumePositionOverride,
            sessionPosition = 0.0,
            detailPosition = detailPos,
        )
        val artworkUrl = watchDetail?.posterUrl?.takeIf { url -> url.isNotBlank() }
            ?: watchDetail?.backdropUrl?.takeIf { url -> url.isNotBlank() }
            ?: sidecar.posterUrl?.takeIf { url -> url.isNotBlank() }

        val published = loadOwners.runIfOwned(loadOwner) {
            val mountGeneration = expectNextMediaMount()
            _uiState.update {
                it.copy(
                isLoading = false,
                error = null,
                title = title,
                subtitle = subtitle,
                artworkUrl = artworkUrl,
                // Playback fields — file:// is read directly by Media3, no
                // server session needed.
                sessionId = null,
                streamUrl = media.uriString,
                playMethod = org.siloserver.silo.model.playback.PlayMethod.DIRECT,
                playbackPlan = null,
                requestHeaders = emptyMap(),
                delivery = null,
                container = sidecar.container,
                serverUrl = "",   // unused for local files
                accessToken = "",
                startPosition = startPos,
                mediaMountGeneration = mountGeneration,
                position = startPos,
                bufferedPosition = 0.0,
                duration = watchDetail?.versions?.firstOrNull { v -> v.fileId == fileId }?.duration
                    ?: sidecar.durationSeconds
                    ?: 0.0,
                serverDuration = watchDetail?.versions?.firstOrNull { v -> v.fileId == fileId }?.duration
                    ?: sidecar.durationSeconds
                    ?: 0.0,
                isPlaying = true,
                isPaused = false,
                isBuffering = false,
                versions = versions,
                selectedVersionIndex = selectedIndex,
                audioTracks = versions[selectedIndex].audioTracks ?: emptyList(),
                subtitleTracks = emptyList(),  // sidecars are remote in v1
                selectedAudioIndex = 0,
                selectedSubtitleIndex = -1,
                intro = watchDetail?.intro,
                credits = watchDetail?.credits,
                recap = watchDetail?.recap,
                preview = watchDetail?.preview,
                chapters = versions[selectedIndex].chapters.orEmpty().ifEmpty { sidecar.chapters.orEmpty() },
                seriesId = watchDetail?.seriesId,
                preferredAudioLanguage = null,
                preferredTextLanguage = null,
                subtitleRefreshNonce = 0,
                )
            }
            Log.i(
                TAG,
                "tryLocalPlayback: serving ${media.displayName} (${media.sizeBytes}B) for content=$contentId (sidecar id=${sidecar.record.id})",
            )
        }

        // Downloaded playback publishes the catalog and hardcodes ordinal 0, but
        // Media3 still picks its own default from the file's tracks -- so the
        // intent has to exist here too or a multi-audio download cannot be
        // corrected.
        if (_uiState.value.audioTracks.isNotEmpty()) setDesiredAudio(0, explicit = false)
        return published
    }

    override fun onCleared() {
        // The RETAINED token first, for the reason onExit gives. An explicit
        // back/remote exit
        // calls onExit() before navigation, which clears sessionId — so by the
        // time onCleared runs, a "snapshot" of UI state is already null, and a
        // null token disables the ownership guard and stops whatever session is
        // current. That is precisely the session a replacement screen may have
        // just adopted.
        val clearedSessionId = retainedOwnedSessionId ?: _uiState.value.sessionId
        org.siloserver.silo.common.player.debug.PlaybackDebugState.screenError = null
        org.siloserver.silo.common.player.ActivePlaybackFile.clear(_uiState.value.mediaFileId)
        loadOwners.invalidate()
        loadJob?.cancel()
        loadJob = null
        mobileSubtitleTransactions.requestDurableFinalPersistence()
        mobileSubtitleTransactions.invalidate()
        onExit()
        // viewModelScope is cancelling here, so onExit's ordered stop may not run.
        // The gate is what decides: if that stop already claimed teardown this
        // is a no-op, and otherwise the app-scoped async stop takes ownership.
        // Qualified for the same reason as the ordered stop above: by the time
        // onCleared runs, a replacement screen may already own playback.
        clearedSessionId?.let { lifecycleTeardown.stopDetached(expectedSessionId = it) }
        controlsHideJob?.cancel()
        introObserverJob?.cancel()
        lifecycleObserverJob?.cancel()
        searchJob?.cancel()
        aiJobHandle?.cancel()
        upNextCountdownJob?.cancel()
        introAutoSkipController.reset()
        super.onCleared()
    }

    private suspend fun resolveDownloadScope(): Pair<String, String> {
        val serverId = serverRegistry.activeServerId.value ?: DownloadEnqueuer.DEFAULT_SERVER_ID
        val profileId = profileRepository.getActiveProfileId() ?: DownloadEnqueuer.DEFAULT_PROFILE_ID
        return serverId to profileId
    }
}

/** Snapshots to let a local audio switch take before asking the server. */
private const val MAX_LOCAL_AUDIO_ATTEMPTS = 3

internal fun authoritativePlaybackSubtitleOrdinal(
    serverIndex: Int?,
    playbackTracks: List<PlayerSubtitleInfo>,
): Int? = when (serverIndex) {
    null -> -1
    -1 -> -1
    else -> playbackTracks.indexOfFirst { it.index == serverIndex }
        .takeIf { it >= 0 }
}
