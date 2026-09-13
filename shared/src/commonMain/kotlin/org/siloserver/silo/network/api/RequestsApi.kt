package org.siloserver.silo.network.api

import org.siloserver.silo.model.request.CreateMediaRequest
import org.siloserver.silo.model.request.MediaRequest
import org.siloserver.silo.model.request.RequestMediaDetail
import org.siloserver.silo.model.request.RequestMediaPage
import org.siloserver.silo.model.request.RequestsDiscoverResponse
import org.siloserver.silo.model.request.RequestsFeatureStatus
import org.siloserver.silo.model.request.RequestsListResponse
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.network.apiv2.OwnerPolicy
import org.siloserver.silo.network.apiv2.ownedV2Call
import org.siloserver.silo.network.apiv2.safeApiV2Call
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.authScope
import org.siloserver.silo.network.ApiResult
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/**
 * User-facing media request endpoints. Kept behind an interface so repository
 * tests can fake the transport, matching the DeviceLogin API shape.
 */
interface RequestsApi {

    suspend fun status(): ApiResult<RequestsFeatureStatus>

    suspend fun discover(): ApiResult<RequestsDiscoverResponse>

    suspend fun discoverSection(section: String, page: Int = 1): ApiResult<RequestMediaPage>

    suspend fun search(
        query: String,
        mediaType: String? = null,
        page: Int = 1,
    ): ApiResult<RequestMediaPage>

    suspend fun detail(mediaType: String, tmdbId: Int): ApiResult<RequestMediaDetail>

    suspend fun create(request: CreateMediaRequest): ApiResult<MediaRequest>

    suspend fun mine(
        status: String? = null,
        outcome: String? = null,
        limit: Int? = null,
        offset: Int? = null,
    ): ApiResult<RequestsListResponse>

    suspend fun get(id: String): ApiResult<MediaRequest>

    suspend fun cancel(id: String): ApiResult<MediaRequest>
}

class DefaultRequestsApi(
    private val client: HttpClient,
    private val gate: ApiV2Gate,
    private val tokenManager: TokenManager? = null,
) : RequestsApi {

    override suspend fun status(): ApiResult<RequestsFeatureStatus> = safeApiV2Call(gate) {
        client.get("/api/v2/requests/status")
    }

    override suspend fun discover(): ApiResult<RequestsDiscoverResponse> = safeApiV2Call(gate) {
        client.get("/api/v2/requests/discover")
    }

    override suspend fun discoverSection(section: String, page: Int): ApiResult<RequestMediaPage> = safeApiV2Call(gate) {
        client.get("/api/v2/requests/discover/$section") {
            parameter("page", page)
        }
    }

    override suspend fun search(
        query: String,
        mediaType: String?,
        page: Int,
    ): ApiResult<RequestMediaPage> = safeApiV2Call(gate) {
        client.get("/api/v2/requests/search") {
            parameter("q", query)
            parameter("media_type", mediaType)
            parameter("page", page)
        }
    }

    override suspend fun detail(mediaType: String, tmdbId: Int): ApiResult<RequestMediaDetail> = safeApiV2Call(gate) {
        client.get("/api/v2/requests/detail/$mediaType/$tmdbId")
    }

    override suspend fun create(request: CreateMediaRequest): ApiResult<MediaRequest> = safeApiV2Call(gate) {
        client.post("/api/v2/requests") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    override suspend fun mine(
        status: String?,
        outcome: String?,
        limit: Int?,
        offset: Int?,
    ): ApiResult<RequestsListResponse> {
        require(offset == null || offset == 0) { "Request paging uses cursors, not offsets." }
        val size = (limit ?: 50).coerceIn(1, 50)
        val pinned = tokenManager?.snapshotCurrentScope()
        val records = mutableListOf<MediaRequest>()
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        repeat(100) {
            val result = ownedV2Call<RequestsListResponse, RequestsListResponse>(gate, tokenManager, pinned, OwnerPolicy.IDENTITY, null, { owner ->
                client.get("/api/v2/requests/mine") {
                    owner?.let { authScope(it) }
                    parameter("status", status)
                    parameter("outcome", outcome)
                    parameter("limit", size)
                    cursor?.let { parameter("cursor", it) }
                }
            }) { it }
            val response = when (result) {
                is ApiResult.Success -> result.data
                is ApiResult.Error -> return result
                is ApiResult.NetworkError -> return result
            }
            records.addAll(response.requests)
            if (!response.page.hasMore) return ApiResult.Success(RequestsListResponse(requests = records))
            val next = response.page.nextCursor
            if (next.isNullOrEmpty() || !seen.add(next)) {
                return ApiResult.Error(0, "requests_incomplete", "The server returned invalid request pagination.")
            }
            cursor = next
        }
        return ApiResult.Error(0, "requests_incomplete", "The request list exceeded the page limit.")
    }

    override suspend fun get(id: String): ApiResult<MediaRequest> = safeApiV2Call(gate) {
        client.get("/api/v2/requests/$id")
    }

    override suspend fun cancel(id: String): ApiResult<MediaRequest> = safeApiV2Call(gate) {
        client.post("/api/v2/requests/$id/cancel") {
            contentType(ContentType.Application.Json)
            setBody(kotlinx.serialization.json.JsonObject(emptyMap()))
        }
    }
}
