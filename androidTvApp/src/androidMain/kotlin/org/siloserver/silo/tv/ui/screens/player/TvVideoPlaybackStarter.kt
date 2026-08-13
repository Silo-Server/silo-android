package org.siloserver.silo.tv.ui.screens.player

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
import org.siloserver.silo.common.player.video.EpisodeSelectionHandoff
import org.siloserver.silo.common.player.video.EpisodeSubtitleIntent
import org.siloserver.silo.common.player.video.ResolvedEpisodeSelection
import org.siloserver.silo.common.player.video.resolveEpisodeSourceIntent
import org.siloserver.silo.common.player.video.EpisodeAudioCandidate
import org.siloserver.silo.common.player.video.EpisodeAudioIntent
import org.siloserver.silo.common.player.video.resolveEpisodeAudioIntent
import org.siloserver.silo.common.player.video.resolveEpisodeSubtitleIntent
import org.siloserver.silo.common.player.video.resolvedPlaybackDelivery
import org.siloserver.silo.common.player.video.shouldReachServerForPlayback
import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.common.settings.dolbyVisionPolicySnapshot
import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.playback.applyResumeRewind
import org.siloserver.silo.model.playback.buildPlaybackSubtitleChoices
import org.siloserver.silo.model.playback.enrichAuthoritativePlaybackSubtitleChoices
import org.siloserver.silo.model.playback.isExplicitStartOver
import org.siloserver.silo.model.playback.resolvePlaybackStartRequestPosition
import org.siloserver.silo.model.playback.resolvePlaybackStartPosition
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.playback.orNullIfBlank
import org.siloserver.silo.playback.selectPlaybackVersion
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.ProfileRepository
import org.siloserver.silo.tv.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

class TvVideoPlaybackStarter(
    private val catalogRepository: CatalogRepository,
    private val playbackSessionManager: PlaybackSessionManager,
    private val profileRepository: ProfileRepository,
    private val capabilityDetector: PlaybackCapabilityDetector,
    private val playerSettingsStore: PlayerSettingsStore,
    private val sessionLifecycle: PlaybackSessionLifecycle,
    private val reachabilityMonitor: ServerReachabilityMonitor,
) : VideoPlaybackStarter {

    override suspend fun start(request: VideoPlaybackStartRequest): VideoPlaybackStartResult {
        // Pre-play gate: don't launch a doomed server session when the origin is
        // unreachable (issue #33). TV is streaming-only (no local downloads), so
        // an Unreachable status here always means playback can't proceed.
        if (!shouldReachServerForPlayback(reachabilityMonitor, request.force)) {
            return VideoPlaybackStartResult.ServerUnreachable(request.contentId)
        }
        val ownershipEpoch = sessionLifecycle.acquireOwnershipEpoch()
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
            val resolvedEpisodeSelection = resolveTvPlaybackStartSelection(
                preferredFileId = request.preferredFileId,
                episodeSelectionHandoff = request.episodeSelectionHandoff,
                targetVersions = watchDetail.versions,
                targetLastFileId = watchDetail.userData?.lastFileId,
                preferredQuality = preferredQuality,
            )
            val version = watchDetail.versions.first { it.fileId == resolvedEpisodeSelection.fileId }
            // The server rejects -1, while the Ready result retains it for the
            // client-side Media3 selection that represents explicit Off.
            val serverSubtitleTrackIndex = resolveTvServerSubtitleTrackIndex(
                episodeSelectionHandoff = request.episodeSelectionHandoff,
                resolvedEpisodeSelection = resolvedEpisodeSelection,
                requestedSubtitleTrackIndex = request.subtitleTrackIndex,
            )
            val activeProfile = profileRepository.getActiveProfile()
            val profileId = activeProfile?.id ?: profileRepository.getActiveProfileId()
                ?: return failure(
                    request.contentId,
                    "No active profile selected",
                    diagnosticsCode = PlaybackDiagnosticsCode.NO_ACTIVE_PROFILE,
                )
            val preferredAudioLanguage = playerSettingsStore.audioLanguageFlow
                .first().ifBlank { null }
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
                    formFactor = "tv",
                    appVersion = BuildConfig.VERSION_NAME,
                    dolbyVision = dolbyVision,
                    capabilities = capabilities,
                )
            // Skip-back-on-resume — see MobileVideoPlaybackStarter for the rationale.
            // Suppressed for Start Over / retry (request flag) and Watch Together
            // (roomId); the one rewound value drives both the server seek and the
            // player start so a transcode cut and the player position never disagree.
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
                val r = playbackSessionManager.startVideoSessionV3(
                    fileId = version.fileId,
                    profileId = profileId,
                    capabilities = capabilities,
                    clientPlaybackContext = playbackContext,
                    // An explicit request wins; otherwise the audio the viewer
                    // chose in the previous episode travels here. Without this
                    // the carry-over resolved a track and then threw it away,
                    // and a dubbed household was returned to the server default
                    // at every automatic transition.
                    audioTrackIndex = request.audioTrackIndex
                        ?: resolvedEpisodeSelection.audioTrackIndex,
                    subtitleTrackIndex = serverSubtitleTrackIndex,
                    qualityPreference = playbackQualityIntent,
                    startPosition = startRequestPosition,
                    // The bandwidth half of the quality choice. The server
                    // applies the cap only from what the request carries, so
                    // sending the resolution alone lets a capped preset stream
                    // at the bandwidth the user explicitly declined.
                    maxBitrateKbps = playerSettingsStore.maxBitrateKbpsFlow.first(),
                    deferPublication = true,
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
            val resolved = readyV3.session
            val effectiveFileId = resolved.mediaFileId.takeIf { it > 0 }
                ?: readyV3.plan.effectiveMediaFileId
                ?: version.fileId
            val effectiveVersion = watchDetail.versions.firstOrNull { it.fileId == effectiveFileId }
            val resolvedDelivery = resolved.resolvedPlaybackDelivery()
            val resolvedStreamUrl = resolved.playbackPlan?.stream?.url
                ?.takeIf { it.isNotBlank() }
                ?: resolved.streamUrl

            // The server may reanchor an HLS stream at a non-zero movie time
            // while exposing a player timeline that begins at zero. Preserve
            // both coordinates so Media3 and the UI each receive the right one.
            val serverPlayerStartPos = readyV3.plan.timeline.playerStartSeconds
                .takeIf { it.isFinite() && it >= 0.0 }
                ?: resolved.position.coerceAtLeast(0.0)
            val playerStartPos = resolvePlaybackStartPosition(
                // A reanchored stream may legitimately expose player time 0
                // for a non-zero source time. Only Start Over's explicit zero
                // may override that server-defined player coordinate.
                overridePosition = request.resumePositionOverride
                    .takeIf(::isExplicitStartOver),
                sessionPosition = serverPlayerStartPos,
                detailPosition = null,
            )
            val serverSourceStartPos = readyV3.plan.timeline.sourceStartSeconds
                .takeIf { it.isFinite() && it >= 0.0 }
                ?: resolved.position.coerceAtLeast(0.0)
            val sourceStartPos = resolveTvSourceStartPosition(
                startRequestPosition = startRequestPosition,
                serverSourceStartPosition = serverSourceStartPos,
                playerStartPosition = playerStartPos,
            )

            val adopted = sessionLifecycle.adoptActiveSessionIfCurrent(
                params = StartParams(
                    contentId = request.contentId,
                    fileId = effectiveFileId,
                    capabilities = readyV3.capabilities,
                    audioTrackIndex = resolved.audioTrackIndex,
                    subtitleTrackIndex = if (request.episodeSelectionHandoff != null) {
                        serverSubtitleTrackIndex
                    } else {
                        request.subtitleTrackIndex
                    },
                    qualityPreference = playbackQualityIntent,
                    startPosition = sourceStartPos,
                    clientPlaybackContext = readyV3.clientPlaybackContext,
                ),
                session = resolved,
                deferPublication = true,
                expectedOwnershipEpoch = ownershipEpoch,
            )
            if (!adopted) {
                return failure(
                    request.contentId,
                    "Playback start was superseded.",
                    diagnosticsCode = PlaybackDiagnosticsCode.START_REQUEST,
                )
            }

            VideoPlaybackStartResult.Ready(
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
                subtitle = null,
                artworkUrl = watchDetail.posterUrl?.takeIf { it.isNotBlank() }
                    ?: watchDetail.backdropUrl?.takeIf { it.isNotBlank() },
                startPositionSeconds = playerStartPos,
                sourceStartPositionSeconds = sourceStartPos,
                serverUrl = serverUrl,
                accessToken = accessToken,
                mediaFileId = effectiveFileId,
                // Protocol v3 source duration is authoritative. Unknown stays
                // unknown; catalog/player runtimes must not fill this field.
                durationSeconds = resolved.durationSeconds,
                subtitleUrls = enrichAuthoritativePlaybackSubtitleChoices(
                    catalogTracks = effectiveVersion?.subtitleTracks.orEmpty(),
                    plannedTracks = resolved.subtitleUrls.orEmpty(),
                ),
                preferredAudioLanguage = preferredAudioLanguage ?: activeProfile?.language,
                // Blank normalizes to null on every rung: a canonical row
                // holding JSON null ("no preference") arrives here as a
                // present-but-empty string, and TV auto-selection reads a
                // non-null blank language as an explicit "subtitles off".
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
                resolvedEpisodeSelection = resolvedEpisodeSelection,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error loading content", e)
            failure(request.contentId, "Unexpected error: ${e.message}", e, PlaybackDiagnosticsCode.UNEXPECTED)
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

    private companion object {
        const val TAG = "TvVideoPlaybackStarter"
    }
}

internal fun resolveTvSourceStartPosition(
    startRequestPosition: Double?,
    serverSourceStartPosition: Double,
    playerStartPosition: Double,
): Double = resolvePlaybackStartPosition(
    overridePosition = startRequestPosition,
    sessionPosition = serverSourceStartPosition,
    detailPosition = playerStartPosition,
)

/**
 * Resolves session-only episode intent after the target detail is available.
 *
 * The target subtitle list depends on the chosen version, so source precedence
 * is decided first; the existing shared source/subtitle resolvers then provide
 * the semantic matching without duplicating their policy here.
 */
fun resolveTvPlaybackStartSelection(
    preferredFileId: Int?,
    episodeSelectionHandoff: EpisodeSelectionHandoff?,
    targetVersions: List<FileVersion>,
    targetLastFileId: Int?,
    preferredQuality: String?,
): ResolvedEpisodeSelection {
    require(targetVersions.isNotEmpty()) { "targetVersions must not be empty" }

    val semanticFileId = resolveEpisodeSourceIntent(
        intent = episodeSelectionHandoff?.source,
        targetVersions = targetVersions,
    )
    val selectedVersion = preferredFileId
        ?.let { preferredId -> targetVersions.firstOrNull { it.fileId == preferredId } }
        ?: semanticFileId
            ?.let { handoffFileId -> targetVersions.firstOrNull { it.fileId == handoffFileId } }
        ?: selectPlaybackVersion(targetVersions, targetLastFileId, preferredQuality)
    val resolvedSubtitle = resolveEpisodeSubtitleIntent(
        intent = episodeSelectionHandoff?.subtitle ?: EpisodeSubtitleIntent.auto(),
        targetSubtitles = buildPlaybackSubtitleChoices(
            catalogTracks = selectedVersion.subtitleTracks.orEmpty(),
            plannedTracks = emptyList(),
        ),
    )
    // Audio travels the same way subtitles do — by what the track IS, not where
    // it sat. The next episode's list is a different list, so a remembered
    // position would land on whatever happens to occupy it.
    val resolvedAudioIndex = resolveEpisodeAudioIntent(
        intent = episodeSelectionHandoff?.audio ?: EpisodeAudioIntent.auto(),
        // The list position IS the address. Audio tracks carry no index on the
        // wire (subtitles do), so AudioTrack.index is its 0 default on every
        // row: building candidates from it collapsed every one of them to 0.
        candidates = selectedVersion.audioTracks.orEmpty().mapIndexed { ordinal, track ->
            EpisodeAudioCandidate(
                index = ordinal,
                language = track.language,
                codecFamily = track.codec,
                channelCount = track.channels?.takeIf { it > 0 },
                title = track.title,
            )
        },
    )
    return ResolvedEpisodeSelection(
        fileId = selectedVersion.fileId,
        subtitleTrackIndex = resolvedSubtitle.trackIndex,
        subtitleIntentSpecified = resolvedSubtitle.intentSpecified,
        audioTrackIndex = resolvedAudioIndex,
    )
}

/** Converts the client-side selection to the server's non-negative index contract. */
fun resolveTvServerSubtitleTrackIndex(
    episodeSelectionHandoff: EpisodeSelectionHandoff?,
    resolvedEpisodeSelection: ResolvedEpisodeSelection,
    requestedSubtitleTrackIndex: Int?,
): Int? = if (episodeSelectionHandoff != null) {
    resolvedEpisodeSelection.subtitleTrackIndex?.takeIf { it >= 0 }
} else {
    requestedSubtitleTrackIndex?.takeIf { it >= 0 }
}
