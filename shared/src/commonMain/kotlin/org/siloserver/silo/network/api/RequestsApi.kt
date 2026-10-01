package org.siloserver.silo.network.api

import org.siloserver.silo.model.request.AdminRequestAction
import org.siloserver.silo.model.request.AdminRequestActionBody
import org.siloserver.silo.model.request.AdminRequestCapabilities
import org.siloserver.silo.model.request.CreateMediaRequest
import org.siloserver.silo.model.request.MediaRequest
import org.siloserver.silo.model.request.RequestMediaDetail
import org.siloserver.silo.model.request.RequestMediaPage
import org.siloserver.silo.model.request.RequestMediaType
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

    /**
     * `GET /api/v2/admin/requests/capabilities`. Answers only for an admin
     * acting as the account's primary profile; anyone else gets a problem,
     * which callers read as "can't moderate".
     */
    suspend fun adminCapabilities(): ApiResult<AdminRequestCapabilities>

    /**
     * Every request matching the filter, across all users, loaded completely
     * or not at all. [tmdbId] narrows the list to one title through `q`, which
     * also matches titles containing the number, so callers still match the id.
     */
    suspend fun adminRequests(
        status: String? = null,
        outcome: String? = null,
        mediaType: String? = null,
        tmdbId: Int? = null,
    ): ApiResult<RequestsListResponse>

    /**
     * The first page of [adminRequests] and whether more follow: enough for a
     * count badge without reading the whole queue.
     */
    suspend fun adminRequestsFirstPage(status: String? = null, outcome: String? = null): ApiResult<RequestsListResponse> =
        adminRequests(status, outcome)

    /** Approve, decline, or retry someone's request. `non_retryable`: never resent after an uncertain outcome. */
    suspend fun adminAction(id: String, action: AdminRequestAction, reason: String? = null): ApiResult<MediaRequest>
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
        return pagedRequests("/api/v2/requests/mine", OwnerPolicy.IDENTITY, maxPages = 100, limit = limit) {
            parameter("status", status)
            parameter("outcome", outcome)
        }
    }

    override suspend fun adminRequests(
        status: String?,
        outcome: String?,
        mediaType: String?,
        tmdbId: Int?,
    ): ApiResult<RequestsListResponse> =
        pagedRequests("/api/v2/admin/requests", OwnerPolicy.PROFILE, maxPages = 40, limit = null) {
            parameter("status", status)
            parameter("outcome", outcome)
            parameter("media_type", mediaType?.takeIf { it == RequestMediaType.Movie || it == RequestMediaType.Series })
            parameter("q", tmdbId?.toString())
        }

    override suspend fun adminRequestsFirstPage(status: String?, outcome: String?): ApiResult<RequestsListResponse> {
        val pinned = tokenManager?.snapshotCurrentScope()
        return ownedV2Call<RequestsListResponse, RequestsListResponse>(gate, tokenManager, pinned, OwnerPolicy.PROFILE, null, { owner ->
            client.get("/api/v2/admin/requests") {
                owner?.let { authScope(it) }
                parameter("status", status)
                parameter("outcome", outcome)
                parameter("limit", 50)
            }
        }) { it }
    }

    /**
     * Follows `page.next_cursor` under one captured owner. A failed page, a
     * missing or repeated cursor, or the page bound fails the whole load
     * instead of returning a partial list.
     */
    private suspend fun pagedRequests(
        path: String,
        policy: OwnerPolicy,
        maxPages: Int,
        limit: Int?,
        filters: io.ktor.client.request.HttpRequestBuilder.() -> Unit,
    ): ApiResult<RequestsListResponse> {
        val size = (limit ?: 50).coerceIn(1, 50)
        val pinned = tokenManager?.snapshotCurrentScope()
        val records = mutableListOf<MediaRequest>()
        val seen = mutableSetOf<String>()
        var cursor: String? = null
        repeat(maxPages) {
            val result = ownedV2Call<RequestsListResponse, RequestsListResponse>(gate, tokenManager, pinned, policy, null, { owner ->
                client.get(path) {
                    owner?.let { authScope(it) }
                    filters()
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

    override suspend fun adminCapabilities(): ApiResult<AdminRequestCapabilities> = safeApiV2Call(gate) {
        client.get("/api/v2/admin/requests/capabilities")
    }

    override suspend fun adminAction(
        id: String,
        action: AdminRequestAction,
        reason: String?,
    ): ApiResult<MediaRequest> = safeApiV2Call(gate) {
        client.post("/api/v2/admin/requests/$id/${action.path}") {
            contentType(ContentType.Application.Json)
            setBody(AdminRequestActionBody(reason = reason?.takeIf { it.isNotBlank() }))
        }
    }
}
