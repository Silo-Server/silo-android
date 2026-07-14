package org.siloserver.silo.tv.ui.screens.player

import android.util.Log
import org.siloserver.silo.common.player.PlaybackCapabilityDetector
import org.siloserver.silo.common.player.PlaybackSessionLifecycle
import org.siloserver.silo.common.player.PlaybackSessionManager
import org.siloserver.silo.common.player.StartParams
import org.siloserver.silo.common.player.VideoSessionStartV3
import org.siloserver.silo.common.player.video.VideoPlaybackStartRequest
import org.siloserver.silo.common.player.video.VideoPlaybackStartResult
import org.siloserver.silo.common.player.video.VideoPlaybackStarter
import org.siloserver.silo.common.player.video.resolvedPlaybackDelivery
import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.common.settings.dolbyVisionPolicySnapshot
import org.siloserver.silo.model.playback.applyResumeRewind
import org.siloserver.silo.model.playback.buildPlaybackSubtitleChoices
import org.siloserver.silo.model.playback.resolvePlaybackStartRequestPosition
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.playback.selectPlaybackVersion
import org.siloserver.silo.repository.CatalogRepository
import org.siloserver.silo.repository.ProfileRepository
import org.siloserver.silo.tv.BuildConfig
import kotlinx.coroutines.flow.first

class TvVideoPlaybackStarter(
    private val catalogRepository: CatalogRepository,
    private val playbackSessionManager: PlaybackSessionManager,
    private val profileRepository: ProfileRepository,
    private val capabilityDetector: PlaybackCapabilityDetector,
    private val playerSettingsStore: PlayerSettingsStore,
    private val sessionLifecycle: PlaybackSessionLifecycle,
) : VideoPlaybackStarter {

    override suspend fun start(request: VideoPlaybackStartRequest): VideoPlaybackStartResult {
        return try {
            val watchDetail = when (val r = catalogRepository.getWatchDetail(request.contentId)) {
                is ApiResult.Success -> r.data
                is ApiResult.Error -> return failure(request.contentId, "Failed to load content: ${r.message}")
                is ApiResult.NetworkError -> return failure(
                    request.contentId,
                    "Network error: ${r.exception.message}",
                    r.exception,
                )
            }
            if (watchDetail.versions.isEmpty()) {
                return failure(request.contentId, "No playable versions available")
            }

            val serverUrl = playbackSessionManager.getServerUrl()
            val preferredQuality = request.preferredQualityOverride
                ?: playerSettingsStore.preferredQualityFlow.first()
            val playbackQualityIntent = request.playbackQualityIntent ?: preferredQuality
            val version = request.preferredFileId
                ?.let { id -> watchDetail.versions.firstOrNull { it.fileId == id } }
                ?: selectPlaybackVersion(
                    watchDetail.versions,
                    watchDetail.userData?.lastFileId,
                    preferredQuality,
                )
            val activeProfile = profileRepository.getActiveProfile()
            val profileId = activeProfile?.id ?: profileRepository.getActiveProfileId()
                ?: return failure(request.contentId, "No active profile selected")
            val preferredAudioLanguage = playerSettingsStore.audioLanguageFlow
                .first().ifBlank { null }
            val accessToken = playbackSessionManager.getAccessToken()
                ?: return failure(request.contentId, "Not authenticated")

            val dolbyVision = playerSettingsStore.dolbyVisionPolicySnapshot()
            val capabilities = capabilityDetector.detect(dolbyVision = dolbyVision)
            val playbackContext = capabilityDetector.detectPlaybackContext(
                formFactor = "tv",
                appVersion = BuildConfig.VERSION_NAME,
                dolbyVision = dolbyVision,
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
                    audioTrackIndex = request.audioTrackIndex,
                    subtitleTrackIndex = request.subtitleTrackIndex,
                    qualityPreference = playbackQualityIntent,
                    startPosition = startRequestPosition,
                )
            ) {
                is ApiResult.Success -> r.data
                is ApiResult.Error -> return failure(request.contentId, "Failed to start playback: ${r.message}")
                is ApiResult.NetworkError -> return failure(
                    request.contentId,
                    "Network error: ${r.exception.message}",
                    r.exception,
                )
            }
            val readyV3 = when (v3Start) {
                is VideoSessionStartV3.Ready -> v3Start
                is VideoSessionStartV3.Terminal -> return failure(
                    request.contentId,
                    "Playback unavailable (${v3Start.reason}): ${v3Start.message}",
                )
                VideoSessionStartV3.ServerUpgradeRequired -> return failure(
                    request.contentId,
                    "This Silo server must be updated to support the Media3 playback protocol.",
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
            val playerStartPos = readyV3.plan.timeline.playerStartSeconds
                .takeIf { it.isFinite() && it >= 0.0 }
                ?: resolved.position.coerceAtLeast(0.0)
            val sourceStartPos = readyV3.plan.timeline.sourceStartSeconds
                .takeIf { it.isFinite() && it >= 0.0 }
                ?: startRequestPosition
                ?: playerStartPos

            sessionLifecycle.adoptActiveSession(
                params = StartParams(
                    contentId = request.contentId,
                    fileId = effectiveFileId,
                    capabilities = capabilities,
                    audioTrackIndex = resolved.audioTrackIndex,
                    subtitleTrackIndex = request.subtitleTrackIndex,
                    qualityPreference = playbackQualityIntent,
                    startPosition = sourceStartPos,
                    clientPlaybackContext = playbackContext,
                ),
                session = resolved,
                renewMissingSessionWithLegacyStart = false,
            )

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
                durationSeconds = resolved.durationSeconds ?: effectiveVersion?.duration ?: 0.0,
                subtitleUrls = buildPlaybackSubtitleChoices(
                    catalogTracks = effectiveVersion?.subtitleTracks.orEmpty(),
                    plannedTracks = resolved.subtitleUrls.orEmpty(),
                ),
                preferredAudioLanguage = preferredAudioLanguage ?: activeProfile?.language,
                preferredTextLanguage = watchDetail.effectiveSubtitleLanguage
                    ?: activeProfile?.subtitleLanguage,
                preferredSubtitleMode = watchDetail.effectiveSubtitleMode
                    ?: activeProfile?.subtitleMode,
                showForcedSubtitles = watchDetail.effectiveShowForcedSubtitles
                    ?: activeProfile?.showForcedSubtitles
                    ?: true,
                intro = watchDetail.intro,
                credits = watchDetail.credits,
                chapters = effectiveVersion?.chapters.orEmpty(),
                seriesId = watchDetail.seriesId,
                seasonNumber = watchDetail.seasonNumber,
                episodeNumber = watchDetail.episodeNumber,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error loading content", e)
            failure(request.contentId, "Unexpected error: ${e.message}", e)
        }
    }

    private fun failure(
        contentId: String,
        message: String,
        cause: Throwable? = null,
    ): VideoPlaybackStartResult.Error {
        // Log the throwable here instead of stashing it on the (unread) result —
        // the message already carries the human-facing detail.
        if (cause != null) Log.w(TAG, "Playback start failed: $message", cause)
        return VideoPlaybackStartResult.Error(
            contentId = contentId,
            message = message,
        )
    }

    private companion object {
        const val TAG = "TvVideoPlaybackStarter"
    }
}
