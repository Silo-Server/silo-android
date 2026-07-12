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
import org.siloserver.silo.model.playback.resolvePlaybackStartPosition
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
                    qualityPreference = preferredQuality,
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
            val session = readyV3.session
            val resolved = session
            val resolvedDelivery = resolved.resolvedPlaybackDelivery()
            val resolvedStreamUrl = resolved.playbackPlan?.stream?.url
                ?.takeIf { it.isNotBlank() }
                ?: resolved.streamUrl

            val startPos = resolvePlaybackStartPosition(
                // For a resume, follow the server's actual cut (resolved.position)
                // so the player can't seek before a transcode's start; only honor
                // an exact override when rewind is suppressed (Start Over / WT /
                // retry). We already sent the rewound seek above, so a transcode's
                // resolved.position reflects the rewind; direct-play rewind then
                // depends on the server echoing it (device-verify).
                overridePosition = if (suppressRewind) request.resumePositionOverride else null,
                sessionPosition = resolved.position,
                detailPosition = watchDetail.userData?.positionSeconds,
            )

            sessionLifecycle.adoptActiveSession(
                params = StartParams(
                    contentId = request.contentId,
                    fileId = version.fileId,
                    capabilities = capabilities,
                    audioTrackIndex = resolved.audioTrackIndex,
                    subtitleTrackIndex = request.subtitleTrackIndex,
                    qualityPreference = preferredQuality,
                    startPosition = startPos,
                    clientPlaybackContext = playbackContext,
                ),
                session = resolved,
                renewMissingSessionWithLegacyStart = false,
            )

            VideoPlaybackStartResult.Ready(
                contentId = request.contentId,
                fileId = version.fileId,
                versions = watchDetail.versions,
                fileResolution = version.resolution,
                sessionId = resolved.sessionId,
                streamUrl = resolvedStreamUrl,
                playMethod = resolved.playMethod,
                playbackPlan = resolved.playbackPlan,
                playbackPlanV3 = readyV3.plan,
                requestHeaders = readyV3.plan.stream.headers,
                delivery = resolvedDelivery,
                container = version.container,
                title = watchDetail.title,
                subtitle = null,
                artworkUrl = watchDetail.posterUrl?.takeIf { it.isNotBlank() }
                    ?: watchDetail.backdropUrl?.takeIf { it.isNotBlank() },
                startPositionSeconds = startPos,
                serverUrl = serverUrl,
                accessToken = accessToken,
                mediaFileId = resolved.mediaFileId.takeIf { id -> id > 0 }
                    ?: session.mediaFileId.takeIf { id -> id > 0 },
                durationSeconds = resolved.durationSeconds ?: version.duration,
                subtitleUrls = resolved.subtitleUrls ?: emptyList(),
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
                chapters = version.chapters.orEmpty(),
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
