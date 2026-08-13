package org.siloserver.silo.android.ui.screens.player

import android.util.Log
import org.siloserver.silo.common.network.ServerReachabilityMonitor
import org.siloserver.silo.common.player.PlaybackCapabilityDetector
import org.siloserver.silo.common.player.PlaybackSessionLifecycle
import org.siloserver.silo.common.player.PlaybackSessionManager
import org.siloserver.silo.common.player.StartParams
import org.siloserver.silo.common.player.VideoSessionStartV3
import org.siloserver.silo.common.player.video.VideoPlaybackStartRequest
import org.siloserver.silo.common.player.video.VideoPlaybackStartResult
import org.siloserver.silo.common.player.video.VideoPlaybackStarter
import org.siloserver.silo.common.player.video.PlaybackDiagnosticsCode
import org.siloserver.silo.common.player.video.resolvedPlaybackDelivery
import org.siloserver.silo.common.player.video.shouldReachServerForPlayback
import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.common.settings.dolbyVisionPolicySnapshot
import org.siloserver.silo.android.BuildConfig
import org.siloserver.silo.model.catalog.WatchDetail
import org.siloserver.silo.model.catalog.SubtitleTrack
import org.siloserver.silo.model.playback.ClientCodecCapabilities
import org.siloserver.silo.model.playback.ClientPlaybackContext
import org.siloserver.silo.model.playback.PlaybackSessionResponse
import org.siloserver.silo.model.playback.buildPlaybackSubtitleChoices
import org.siloserver.silo.model.playback.enrichAuthoritativePlaybackSubtitleChoices
import org.siloserver.silo.model.playback.combinedSubtitleSelectionIndexes
import org.siloserver.silo.model.playback.applyResumeRewind
import org.siloserver.silo.model.playback.resolvePlaybackStartRequestPosition
import org.siloserver.silo.model.playback.resolvedSelectedSubtitleIndex
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.playback.orNullIfBlank
import org.siloserver.silo.playback.resolveAudioTrackOrdinal
import org.siloserver.silo.playback.resolveCatalogSubtitlePreferenceOrdinal
import org.siloserver.silo.playback.selectPlaybackVersion
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.ProfileRepository
import org.siloserver.silo.repository.port.LocalTrackSelection
import org.siloserver.silo.repository.port.UserItemStatePort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal data class MobileVideoSessionAllocation(
    val fileId: Int,
    val profileId: String,
    val capabilities: ClientCodecCapabilities,
    val clientPlaybackContext: ClientPlaybackContext,
    val audioTrackIndex: Int?,
    val subtitleTrackIndex: Int?,
    val qualityPreference: String?,
    val startPosition: Double?,
    /** `playback.max_bitrate_kbps`; null is uncapped. */
    val maxBitrateKbps: Int? = null,
)

internal fun interface MobileVideoSessionAllocator {
    suspend fun allocate(request: MobileVideoSessionAllocation): ApiResult<VideoSessionStartV3>
}

internal fun interface MobileVideoSessionAdopter {
    suspend fun adopt(params: StartParams, session: PlaybackSessionResponse)
}

internal data class MobileInitialTrackSelection(
    val audioTrackIndex: Int?,
    val subtitleTrackIndex: Int?,
)

/**
 * Resolves durable per-file choices before neutral-v3 allocates its first plan.
 *
 * Explicit request indexes are already playback-v3 indexes and must pass
 * through unchanged, including internal recovery starts. Persisted subtitle
 * choices are stable catalog identities, so resolve only those onto the
 * server's combined external-then-embedded index space. This lets restore
 * happen in the first plan without reinterpreting a recovery index twice.
 * Local/downloaded subtitle identities deliberately resolve to null and stay
 * on the Media3-only restore path after the server plan is mounted.
 */
internal fun resolveMobileInitialTrackSelection(
    explicitAudioTrackIndex: Int?,
    explicitSubtitleTrackIndex: Int?,
    audioTracks: List<org.siloserver.silo.model.catalog.AudioTrack>,
    subtitleTracks: List<SubtitleTrack>,
    persisted: LocalTrackSelection?,
): MobileInitialTrackSelection {
    val audioTrackIndex = explicitAudioTrackIndex
        ?: resolveAudioTrackOrdinal(audioTracks, persisted?.audioFingerprint)
    val persistedSubtitleOrdinal = if (explicitSubtitleTrackIndex == null) {
        resolveCatalogSubtitlePreferenceOrdinal(
            subtitleTracks,
            persisted?.subtitleFingerprint,
        )
    } else {
        null
    }
    val subtitleTrackIndex = when {
        explicitSubtitleTrackIndex != null -> explicitSubtitleTrackIndex
        persistedSubtitleOrdinal == null -> null
        persistedSubtitleOrdinal == -1 -> -1
        else -> combinedSubtitleSelectionIndexes(subtitleTracks)
            .getOrNull(persistedSubtitleOrdinal)
    }
    return MobileInitialTrackSelection(
        audioTrackIndex = audioTrackIndex,
        subtitleTrackIndex = subtitleTrackIndex,
    )
}

internal class MobileVideoPlaybackStarter(
    private val catalogRepository: CatalogRepository,
    private val playbackSessionManager: PlaybackSessionManager,
    private val profileRepository: ProfileRepository,
    private val capabilityDetector: PlaybackCapabilityDetector,
    private val playerSettingsStore: PlayerSettingsStore,
    private val sessionLifecycle: PlaybackSessionLifecycle,
    private val reachabilityMonitor: ServerReachabilityMonitor,
    private val userItemStatePort: UserItemStatePort? = null,
    private val sessionAllocator: MobileVideoSessionAllocator? = null,
    private val sessionAdopter: MobileVideoSessionAdopter? = null,
) : VideoPlaybackStarter {

    override suspend fun start(request: VideoPlaybackStartRequest): VideoPlaybackStartResult {
        // Pre-play gate: don't launch a doomed server session when the origin is
        // unreachable (issue #33). Playable local downloads are already served by
        // PlayerViewModel.tryLocalPlayback *before* this starter runs, so an
        // Unreachable status here means no local copy exists.
        if (!shouldReachServerForPlayback(reachabilityMonitor, request.force)) {
            return VideoPlaybackStartResult.ServerUnreachable(request.contentId)
        }
        val ownershipEpoch = sessionLifecycle.acquireOwnershipEpoch()
        var allocatedButUnpublishedSessionId: String? = null
        return try {
            val watchDetail = when (val r = catalogRepository.getWatchDetail(request.contentId)) {
                is ApiResult.Success -> r.data
                is ApiResult.Error -> return failure(
                    request.contentId,
                    "Failed to load content: ${r.message}",
                    diagnosticsCode = PlaybackDiagnosticsCode.CATALOG,
                )
                is ApiResult.NetworkError -> return failure(
                    request.contentId,
                    "Network error: ${r.exception.message}",
                    r.exception,
                    PlaybackDiagnosticsCode.NETWORK,
                )
            }
            if (watchDetail.versions.isEmpty()) {
                return failure(
                    request.contentId,
                    "No playable versions available",
                    diagnosticsCode = PlaybackDiagnosticsCode.NO_VERSIONS,
                )
            }

            val serverUrl = playbackSessionManager.getServerUrl()
            val preferredQuality = request.preferredQualityOverride
                ?: playerSettingsStore.preferredQualityFlow.first()
            val playbackQualityIntent = request.playbackQualityIntent ?: preferredQuality
            // The bandwidth half of the quality choice. Quality is two axes and
            // the server applies the cap only from what the request carries —
            // nothing on the playback path reads the stored setting — so
            // sending the resolution alone lets a capped preset ("1080p Low")
            // stream at the bandwidth the user explicitly declined.
            val maxBitrateKbps = playerSettingsStore.maxBitrateKbpsFlow.first()
            val preferredAudioLanguage = playerSettingsStore.audioLanguageFlow
                .first().ifBlank { null }
            val version = request.preferredFileId
                ?.let { id -> watchDetail.versions.firstOrNull { it.fileId == id } }
                ?: selectPlaybackVersion(
                    watchDetail.versions,
                    watchDetail.userData?.lastFileId,
                    preferredQuality,
                )
            val persistedTrackSelection = if (
                userItemStatePort != null &&
                (request.audioTrackIndex == null || request.subtitleTrackIndex == null)
            ) {
                userItemStatePort.localTrackSelection(request.contentId, version.fileId)
            } else {
                null
            }
            val initialTracks = resolveMobileInitialTrackSelection(
                explicitAudioTrackIndex = request.audioTrackIndex,
                explicitSubtitleTrackIndex = request.subtitleTrackIndex,
                audioTracks = version.audioTracks.orEmpty(),
                subtitleTracks = version.subtitleTracks.orEmpty(),
                persisted = persistedTrackSelection,
            )

            val activeProfile = profileRepository.getActiveProfile()
            val profileId = activeProfile?.id ?: profileRepository.getActiveProfileId()
                ?: return failure(
                    request.contentId,
                    "No active profile selected",
                    diagnosticsCode = PlaybackDiagnosticsCode.NO_ACTIVE_PROFILE,
                )
            val accessToken = playbackSessionManager.getAccessToken()
                ?: return failure(
                    request.contentId,
                    "Not authenticated",
                    diagnosticsCode = PlaybackDiagnosticsCode.NOT_AUTHENTICATED,
                )
            val dolbyVision = playerSettingsStore.dolbyVisionPolicySnapshot()
            val capabilities = request.recoveryStartParams?.capabilities
                ?: capabilityDetector.detect(dolbyVision = dolbyVision)
            val playbackContext = request.recoveryStartParams?.clientPlaybackContext
                ?: capabilityDetector.detectPlaybackContext(
                    formFactor = "mobile",
                    appVersion = BuildConfig.VERSION_NAME,
                    dolbyVision = dolbyVision,
                    capabilities = capabilities,
                )
            // Skip-back-on-resume: nudge a genuine resume back a few seconds.
            // Suppressed for Start Over / retry (request flag) and Watch Together
            // (roomId — all participants must land on the synced anchor). The same
            // rewound value drives BOTH the server seek and the player start, so
            // a transcode cut and the player position never disagree.
            val suppressRewind = request.suppressResumeRewind || request.roomId != null ||
                org.siloserver.silo.model.playback.isExplicitStartOver(request.resumePositionOverride)
            // Per-profile setting (default 7; 0 = off). Read once per start.
            val rewindSeconds = playerSettingsStore.resumeRewindSecondsFlow.first().toDouble()
            fun rewound(position: Double?): Double? = position?.let {
                applyResumeRewind(
                    resolvedStartPosition = it,
                    isExplicitOverride = suppressRewind,
                    rewindSeconds = rewindSeconds,
                )
            }
            val startRequestPosition = rewound(
                resolvePlaybackStartRequestPosition(
                    overridePosition = request.resumePositionOverride,
                    detailPosition = watchDetail.userData?.positionSeconds,
                ),
            )

            val v3Start = when (
                val r = sessionAllocator?.allocate(
                    MobileVideoSessionAllocation(
                        fileId = version.fileId,
                        profileId = profileId,
                        capabilities = capabilities,
                        clientPlaybackContext = playbackContext,
                        audioTrackIndex = initialTracks.audioTrackIndex,
                        subtitleTrackIndex = initialTracks.subtitleTrackIndex,
                        qualityPreference = playbackQualityIntent,
                        startPosition = startRequestPosition,
                        maxBitrateKbps = maxBitrateKbps,
                    ),
                ) ?: playbackSessionManager.startVideoSessionV3(
                    fileId = version.fileId,
                    profileId = profileId,
                    capabilities = capabilities,
                    clientPlaybackContext = playbackContext,
                    audioTrackIndex = initialTracks.audioTrackIndex,
                    subtitleTrackIndex = initialTracks.subtitleTrackIndex,
                    qualityPreference = playbackQualityIntent,
                    startPosition = startRequestPosition,
                    maxBitrateKbps = maxBitrateKbps,
                )
            ) {
                is ApiResult.Success -> r.data
                is ApiResult.Error -> return failure(
                    request.contentId,
                    "Failed to start playback: ${r.message}",
                    diagnosticsCode = PlaybackDiagnosticsCode.START_REQUEST,
                )
                is ApiResult.NetworkError -> return failure(
                    request.contentId,
                    "Network error: ${r.exception.message}",
                    r.exception,
                    PlaybackDiagnosticsCode.NETWORK,
                )
            }
            val readyV3 = when (v3Start) {
                is VideoSessionStartV3.Ready -> v3Start
                is VideoSessionStartV3.Terminal -> return failure(
                    request.contentId,
                    "Playback unavailable (${v3Start.reason}): ${v3Start.message}",
                    diagnosticsCode = PlaybackDiagnosticsCode.serverTerminal(v3Start.reason),
                )
                VideoSessionStartV3.ServerUpgradeRequired -> return failure(
                    request.contentId,
                    "This Silo server must be updated to support the Media3 playback protocol.",
                    diagnosticsCode = PlaybackDiagnosticsCode.SERVER_UPGRADE_REQUIRED,
                )
            }
            val session = readyV3.session
            val resolved = session
            allocatedButUnpublishedSessionId = resolved.sessionId
            val effectiveFileId = resolved.mediaFileId.takeIf { it > 0 }
                ?: readyV3.plan.effectiveMediaFileId
                ?: version.fileId
            val effectiveVersion = watchDetail.versions.firstOrNull { it.fileId == effectiveFileId }
            val resolvedDelivery = resolved.resolvedPlaybackDelivery()
            val resolvedStreamUrl = resolved.playbackPlan?.stream?.url
                ?.takeIf { it.isNotBlank() }
                ?: resolved.streamUrl

            // V3 distinguishes full movie time from the mounted player time.
            // In particular, a copy-mode HLS stream may begin at movie time
            // 3,000s while Media3 must mount it at 0s. Never feed the source
            // position back into the shortened player timeline.
            val playerStartPos = readyV3.plan.timeline.playerStartSeconds
                .takeIf { it.isFinite() && it >= 0.0 }
                ?: resolved.position.coerceAtLeast(0.0)
            val sourceStartPos = readyV3.plan.timeline.sourceStartSeconds
                .takeIf { it.isFinite() && it >= 0.0 }
                ?: startRequestPosition
                ?: playerStartPos

            val startParams = StartParams(
                contentId = request.contentId,
                fileId = effectiveFileId,
                capabilities = readyV3.capabilities,
                audioTrackIndex = initialTracks.audioTrackIndex ?: resolved.audioTrackIndex,
                subtitleTrackIndex = initialTracks.subtitleTrackIndex
                    ?: readyV3.plan.resolvedSelectedSubtitleIndex(),
                qualityPreference = playbackQualityIntent,
                startPosition = sourceStartPos,
                clientPlaybackContext = readyV3.clientPlaybackContext,
            )
            val adopted = if (sessionAdopter != null) {
                sessionAdopter.adopt(startParams, resolved)
                true
            } else {
                try {
                    sessionLifecycle.adoptActiveSessionIfCurrent(
                        params = startParams,
                        session = resolved,
                        expectedOwnershipEpoch = ownershipEpoch,
                    )
                } catch (cancellation: CancellationException) {
                    // The lifecycle owns cancellation cleanup once adoption begins.
                    allocatedButUnpublishedSessionId = null
                    throw cancellation
                }
            }
            if (!adopted) {
                // Rejected lifecycle adoption closes the candidate itself.
                allocatedButUnpublishedSessionId = null
                return failure(
                    request.contentId,
                    "Playback start was superseded.",
                    diagnosticsCode = PlaybackDiagnosticsCode.START_REQUEST,
                )
            }

            val result = VideoPlaybackStartResult.Ready(
                contentId = request.contentId,
                fileId = effectiveFileId,
                versions = watchDetail.versions,
                fileResolution = effectiveVersion?.resolution
                    ?: readyV3.plan.effectiveRecipe.height?.let { "${it}p" },
                sessionId = resolved.sessionId,
                streamUrl = resolvedStreamUrl,
                playMethod = resolved.playMethod,
                playbackPlan = resolved.playbackPlan,
                playbackPlanV3 = readyV3.plan,
                requestHeaders = readyV3.plan.stream.headers,
                delivery = resolvedDelivery,
                container = readyV3.plan.stream.container ?: effectiveVersion?.container,
                title = watchDetail.title,
                subtitle = buildSubtitle(watchDetail).takeIf { it.isNotBlank() },
                artworkUrl = watchDetail.posterUrl?.takeIf { it.isNotBlank() }
                    ?: watchDetail.backdropUrl?.takeIf { it.isNotBlank() },
                startPositionSeconds = playerStartPos,
                sourceStartPositionSeconds = sourceStartPos,
                serverUrl = serverUrl,
                accessToken = accessToken,
                mediaFileId = effectiveFileId,
                audioTrackIndex = resolved.audioTrackIndex,
                // Protocol v3 source duration is authoritative. Unknown stays
                // unknown; catalog/player runtimes must not fill this field.
                durationSeconds = resolved.durationSeconds,
                subtitleUrls = enrichAuthoritativePlaybackSubtitleChoices(
                    catalogTracks = effectiveVersion?.subtitleTracks.orEmpty(),
                    plannedTracks = resolved.subtitleUrls.orEmpty(),
                ),
                preferredAudioLanguage = preferredAudioLanguage ?: activeProfile?.language,
                // Server-resolved first, exactly as TvVideoPlaybackStarter does.
                // The settings screens write these three canonically now
                // (`PUT /settings/values/{key}?scope=profile`) and nothing
                // mirrors a canonical write back into `user_profiles`, so the
                // profile columns go stale the moment the user changes a
                // subtitle preference. `effective_*` is what the server would
                // resolve for this item; the columns stay only as the fallback
                // for a server too old to send them.
                //
                // Blank is normalized to null on every rung, matching the audio
                // language above. A canonical row holding JSON null (the
                // contract's spelling of "no preference") unmarshals to "" on
                // the server and arrives here as a present-but-empty string, and
                // `resolveMobileAutoSubtitleSelection` reads a non-null blank
                // language as an explicit "subtitles off" — so passing it
                // through would turn auto-selection off for a user who never
                // chose a language.
                preferredTextLanguage = watchDetail.effectiveSubtitleLanguage.orNullIfBlank()
                    ?: activeProfile?.subtitleLanguage.orNullIfBlank(),
                preferredSubtitleMode = watchDetail.effectiveSubtitleMode.orNullIfBlank()
                    ?: activeProfile?.subtitleMode.orNullIfBlank(),
                showForcedSubtitles = watchDetail.effectiveShowForcedSubtitles
                    ?: activeProfile?.showForcedSubtitles
                    ?: true,
                intro = watchDetail.intro,
                credits = watchDetail.credits,
                recap = watchDetail.recap,
                preview = watchDetail.preview,
                chapters = effectiveVersion?.chapters.orEmpty(),
                seriesId = watchDetail.seriesId,
                seasonNumber = watchDetail.seasonNumber,
                episodeNumber = watchDetail.episodeNumber,
            )
            allocatedButUnpublishedSessionId = null
            result
        } catch (e: CancellationException) {
            stopAllocatedButUnpublishedSession(allocatedButUnpublishedSessionId)
            throw e
        } catch (e: Exception) {
            stopAllocatedButUnpublishedSession(allocatedButUnpublishedSessionId)
            Log.e(TAG, "Error loading content", e)
            failure(request.contentId, "Unexpected error: ${e.message}", e, PlaybackDiagnosticsCode.UNEXPECTED)
        }
    }

    private suspend fun stopAllocatedButUnpublishedSession(sessionId: String?) {
        val allocatedSessionId = sessionId?.takeIf { it.isNotBlank() } ?: return
        withContext(NonCancellable) {
            try {
                playbackSessionManager.stopSession(allocatedSessionId)
            } catch (error: Exception) {
                Log.w(TAG, "Could not stop unpublished playback session $allocatedSessionId", error)
            }
        }
    }

    private fun failure(
        contentId: String,
        message: String,
        cause: Throwable? = null,
        diagnosticsCode: PlaybackDiagnosticsCode? = null,
    ): VideoPlaybackStartResult.Error {
        // Log the throwable here instead of stashing it on the (unread) result —
        // the message already carries the human-facing detail.
        if (cause != null) Log.w(TAG, "Playback start failed: $message", cause)
        return VideoPlaybackStartResult.Error(
            contentId = contentId,
            message = message,
            diagnosticsCode = diagnosticsCode,
        )
    }

    private fun buildSubtitle(watchDetail: WatchDetail): String {
        return if (watchDetail.seriesTitle != null && watchDetail.seasonNumber != null && watchDetail.episodeNumber != null) {
            val seasonEp = "S${watchDetail.seasonNumber.toString().padStart(2, '0')}E${watchDetail.episodeNumber.toString().padStart(2, '0')}"
            "${watchDetail.seriesTitle} - $seasonEp"
        } else {
            watchDetail.year?.toString() ?: ""
        }
    }

    private companion object {
        const val TAG = "MobileVideoPlaybackStarter"
    }
}
