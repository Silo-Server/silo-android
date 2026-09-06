package org.siloserver.silo.repository

import org.siloserver.silo.model.playback.PlaybackDecisionResponseV3
import org.siloserver.silo.model.playback.PlaybackReplanRequestV3
import org.siloserver.silo.model.playback.PlaybackRouteEventV3
import org.siloserver.silo.model.playback.PlaybackStartRequestV3
import org.siloserver.silo.model.playback.ProgressRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.PlaybackApi

class PlaybackRepository(
    private val playbackApi: PlaybackApi,
    private val sequenced: SequencedPlayback? = null,
) {
    val pendingPlayback = sequenced?.pending ?: MutableStateFlow(emptyList<String>())
    fun isSequenced(sessionId: String): Boolean = sequenced?.owns(sessionId) == true
    suspend fun pendingPlaybackCount(): Int = sequenced?.pendingForCurrentViewer() ?: 0
    suspend fun recoverPlayback(): ApiResult<Unit> = guarded { sequenced?.recover() ?: ApiResult.Success(Unit) }
    private suspend fun <T> guarded(block: suspend () -> ApiResult<T>): ApiResult<T> = try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { ApiResult.Error(0, "playback_storage", "Playback recovery storage is unavailable.") }

    /** Starts a protocol-v3 playback attempt using the supplied client and route evidence. */
    suspend fun startPlaybackV3(request: PlaybackStartRequestV3): ApiResult<PlaybackDecisionResponseV3> =
        guarded { sequenced?.start(request) ?: playbackApi.startPlaybackV3(request) }

    /** Requests a replacement protocol-v3 plan for an active [sessionId]. */
    suspend fun replanPlaybackV3(
        sessionId: String,
        request: PlaybackReplanRequestV3,
    ): ApiResult<PlaybackDecisionResponseV3> = if (isSequenced(sessionId))
        ApiResult.Error(0, "replan_unavailable", "This playback session does not support replanning.")
        else playbackApi.replanPlaybackV3(sessionId, request)

    /** Reports attempt-scoped protocol-v3 route telemetry. */
    suspend fun reportRouteEventV3(request: PlaybackRouteEventV3): ApiResult<Unit> =
        if (request.sessionId?.let(::isSequenced) == true) ApiResult.Success(Unit)
        else playbackApi.reportRouteEventV3(request)

    /** Reports current playback position and paused state to the server. */
    suspend fun updateProgress(
        sessionId: String,
        position: Double,
        isPaused: Boolean,
    ): ApiResult<Unit> =
        guarded { sequenced?.progress(sessionId, position, isPaused) ?: playbackApi.updateProgress(
            sessionId = sessionId,
            request = ProgressRequest(position = position, isPaused = isPaused),
        ) }

    /** Stops an active playback session. */
    suspend fun stopPlayback(sessionId: String): ApiResult<Unit> =
        guarded { sequenced?.stop(sessionId) ?: playbackApi.stopPlayback(sessionId) }
}
