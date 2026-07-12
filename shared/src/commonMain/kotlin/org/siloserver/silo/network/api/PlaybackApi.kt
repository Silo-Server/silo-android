package org.siloserver.silo.network.api

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.http.*
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

class PlaybackApi(private val client: HttpClient) {

    suspend fun startPlayback(request: StartPlaybackRequest): ApiResult<PlaybackSessionResponse> = safeApiCall {
        client.post("/api/v1/playback/start") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    suspend fun startPlaybackV3(request: PlaybackStartRequestV3): ApiResult<PlaybackDecisionResponseV3> = safeApiCall {
        client.post("/api/v1/playback/start") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    suspend fun replanPlaybackV3(
        sessionId: String,
        request: PlaybackReplanRequestV3,
    ): ApiResult<PlaybackDecisionResponseV3> = safeApiCall {
        client.post("/api/v1/playback/$sessionId/replan") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    suspend fun reportRouteEventV3(request: PlaybackRouteEventV3): ApiResult<Unit> = safeApiCall {
        client.post("/api/v1/playback/route-events") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    suspend fun updateProgress(
        sessionId: String,
        request: ProgressRequest
    ): ApiResult<Unit> = safeApiCall {
        client.post("/api/v1/playback/$sessionId/progress") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    suspend fun stopPlayback(sessionId: String): ApiResult<Unit> = safeApiCall {
        client.delete("/api/v1/playback/$sessionId")
    }

    suspend fun startTranscode(request: TranscodeStartRequest): ApiResult<TranscodeStartResponse> = safeApiCall {
        client.post("/api/v1/playback/transcode/start") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }
}
