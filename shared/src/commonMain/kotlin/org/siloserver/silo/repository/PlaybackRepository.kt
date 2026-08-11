package org.siloserver.silo.repository

import org.siloserver.silo.model.playback.PlaybackDecisionResponseV3
import org.siloserver.silo.model.playback.PlaybackReplanRequestV3
import org.siloserver.silo.model.playback.PlaybackRouteEventV3
import org.siloserver.silo.model.playback.PlaybackStartRequestV3
import org.siloserver.silo.model.playback.ProgressRequest
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
}
