package org.siloserver.silo.repository

import org.siloserver.silo.model.playback.ClientCodecCapabilities
import org.siloserver.silo.model.playback.ClientPlaybackContext
import org.siloserver.silo.model.playback.PlayMethod
import org.siloserver.silo.model.playback.PlaybackDecisionResponseV3
import org.siloserver.silo.model.playback.PlaybackReplanRequestV3
import org.siloserver.silo.model.playback.PlaybackRouteEventV3
import org.siloserver.silo.model.playback.PlaybackStartRequestV3
import org.siloserver.silo.model.playback.PlaybackSessionResponse
import org.siloserver.silo.model.playback.ProgressRequest
import org.siloserver.silo.model.playback.StartPlaybackRequest
import org.siloserver.silo.model.playback.TranscodeStartRequest
import org.siloserver.silo.model.playback.TranscodeStartResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.PlaybackApi

class PlaybackRepository(
    private val playbackApi: PlaybackApi,
) {
    /** Starts a protocol-v3 playback attempt using the supplied client and route evidence. */
    suspend fun startPlaybackV3(request: PlaybackStartRequestV3): ApiResult<PlaybackDecisionResponseV3> =
        playbackApi.startPlaybackV3(request)

    /** Requests a replacement protocol-v3 plan for an active [sessionId]. */
    suspend fun replanPlaybackV3(
        sessionId: String,
        request: PlaybackReplanRequestV3,
    ): ApiResult<PlaybackDecisionResponseV3> = playbackApi.replanPlaybackV3(sessionId, request)

    /** Reports attempt-scoped protocol-v3 route telemetry. */
    suspend fun reportRouteEventV3(request: PlaybackRouteEventV3): ApiResult<Unit> =
        playbackApi.reportRouteEventV3(request)

    /**
     * Starts a new playback session.
     * The server decides whether to direct-play or transcode based on client capabilities.
     */
    suspend fun startPlayback(
        fileId: Int,
        profileId: String,
        qualityPreference: String? = null,
        audioTrackIndex: Int? = null,
        subtitleTrackIndex: Int? = null,
        startPosition: Double? = null,
        capabilities: ClientCodecCapabilities,
        clientPlaybackContext: ClientPlaybackContext? = null,
        preserveDirectAudioSelection: Boolean = false,
        playMethod: PlayMethod? = null,
        disableProgressPersistence: Boolean = false,
    ): ApiResult<PlaybackSessionResponse> =
        playbackApi.startPlayback(
            StartPlaybackRequest(
                fileId = fileId,
                profileId = profileId,
                playMethod = playMethod?.wireValue(),
                startPosition = startPosition,
                audioTrackIndex = audioTrackIndex,
                subtitleTrackIndex = subtitleTrackIndex,
                qualityPreference = qualityPreference,
                preserveDirectAudioSelection = preserveDirectAudioSelection,
                codecsVideo = capabilities.codecsVideo,
                codecsAudio = capabilities.codecsAudio,
                containers = capabilities.containers,
                maxResolution = capabilities.maxResolution,
                hdr = capabilities.hdr,
                hdrDetails = capabilities.hdrDetails,
                audioPassthrough = capabilities.audioPassthrough,
                clientPlaybackContext = clientPlaybackContext,
                disableProgressPersistence = disableProgressPersistence,
            ),
        )

    /** Reports current playback position and paused state to the server. */
    suspend fun updateProgress(
        sessionId: String,
        position: Double,
        isPaused: Boolean,
    ): ApiResult<Unit> =
        playbackApi.updateProgress(
            sessionId = sessionId,
            request = ProgressRequest(position = position, isPaused = isPaused),
        )

    /** Stops an active playback session. */
    suspend fun stopPlayback(sessionId: String): ApiResult<Unit> =
        playbackApi.stopPlayback(sessionId)

    /** Explicitly requests a transcode session (e.g. for quality changes). */
    suspend fun startTranscode(request: TranscodeStartRequest): ApiResult<TranscodeStartResponse> =
        playbackApi.startTranscode(request)

}

// Mirror the enum's @SerialName wire values explicitly (not name.lowercase()),
// so adding a constant whose serial name differs from its lowercased name is a
// compile error here rather than a silently wrong wire value. Exhaustive on
// purpose — no `else`.
private fun PlayMethod.wireValue(): String = when (this) {
    PlayMethod.DIRECT -> "direct"
    PlayMethod.REMUX -> "remux"
    PlayMethod.TRANSCODE -> "transcode"
}
