package org.siloserver.silo.network.api

import org.siloserver.silo.model.settings.AudioPrefRequest
import org.siloserver.silo.model.settings.SubtitlePrefRequest
import org.siloserver.silo.network.ApiResult
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/**
 * Per-series (or per-movie) audio/subtitle track preference writes. The key
 * is the series id for episodes and the content id for movies — see
 * `TrackSelectionPersistence.prefKey`. Writes are best-effort; reads never
 * happen here (the server folds prefs into `WatchDetail.effective_*`).
 */
open class TrackPrefsApi(private val client: HttpClient) {

    open suspend fun setAudioPref(
        key: String,
        request: AudioPrefRequest,
    ): ApiResult<Unit> = safeApiCall {
        client.put("/api/v1/audio-prefs/$key") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    open suspend fun deleteAudioPref(key: String): ApiResult<Unit> = safeApiCall {
        client.delete("/api/v1/audio-prefs/$key")
    }

    open suspend fun setSubtitlePref(
        key: String,
        request: SubtitlePrefRequest,
    ): ApiResult<Unit> = safeApiCall {
        client.put("/api/v1/subtitle-prefs/$key") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    open suspend fun deleteSubtitlePref(key: String): ApiResult<Unit> = safeApiCall {
        client.delete("/api/v1/subtitle-prefs/$key")
    }
}
