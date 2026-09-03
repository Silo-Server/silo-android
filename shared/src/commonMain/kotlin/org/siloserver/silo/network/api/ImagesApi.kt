package org.siloserver.silo.network.api

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import org.siloserver.silo.model.image.ImagesCapability
import org.siloserver.silo.network.ApiResult

/**
 * Image-variant capability probe. Mirrors [EbookReaderApi.getConversionCapability]:
 * one dedicated GET, fetched at most once per session, and any failure (404 on an
 * older server, offline, garbage body) simply means the feature is unavailable.
 */
open class ImagesApi(private val client: HttpClient) {

    open suspend fun getCapability(): ApiResult<ImagesCapability> = safeApiCall {
        client.get("/api/v1/images/capability")
    }
}
