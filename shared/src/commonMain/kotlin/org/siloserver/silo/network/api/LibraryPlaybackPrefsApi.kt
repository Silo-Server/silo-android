package org.siloserver.silo.network.api

import org.siloserver.silo.model.settings.LibraryPlaybackPrefRequest
import org.siloserver.silo.model.settings.LibraryPlaybackPrefsResponse
import org.siloserver.silo.network.ApiResult
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

open class LibraryPlaybackPrefsApi(
    private val client: HttpClient,
    private val readsV2: org.siloserver.silo.network.apiv2.SettingsReadsV2Api? = null,
) {

    open suspend fun list(): ApiResult<LibraryPlaybackPrefsResponse> = readsV2?.libraryPreferences() ?: safeApiCall {
        client.get("/api/v1/library-playback-prefs")
    }

    open suspend fun set(
        libraryId: Int,
        request: LibraryPlaybackPrefRequest,
    ): ApiResult<Unit> = safeApiCall {
        client.put("/api/v1/library-playback-prefs/$libraryId") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    open suspend fun delete(libraryId: Int): ApiResult<Unit> = safeApiCall {
        client.delete("/api/v1/library-playback-prefs/$libraryId")
    }
}
