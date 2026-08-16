package org.siloserver.silo.network.api

import org.siloserver.silo.model.watchtogether.AddSuggestionRequest
import org.siloserver.silo.model.watchtogether.CreateRoomRequest
import org.siloserver.silo.model.watchtogether.JoinRoomRequest
import org.siloserver.silo.model.watchtogether.PromoteSuggestionRequest
import org.siloserver.silo.model.watchtogether.RoomResponse
import org.siloserver.silo.model.watchtogether.SetSelectionRequest
import org.siloserver.silo.model.watchtogether.SuggestionsResponse
import org.siloserver.silo.model.watchtogether.UpdatePolicyRequest
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.authScope
import org.siloserver.silo.network.requireSiloAuth
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart

/**
 * Watch Together REST surface (`/api/v1/watch-together`). Create/join return a
 * second token (the **room JWT**) distinct from the auth JWT; every
 * room-scoped call passes it as the `room_token` query param. Behind an
 * interface so the repository's tests fake the transport (matching
 * NotificationsApi).
 *
 * The room WS is a separate transport (see WatchTogetherRealtimeClient); this
 * is REST only. 204 (close) maps to Unit; 409 (vote dup / not-voted) and 410
 * (gone) surface as [ApiResult.Error].
 */
interface WatchTogetherApi {

    /** POST /rooms — caller becomes host; 201 with room + room_access_token. */
    suspend fun createRoom(request: CreateRoomRequest, scope: AuthScopeSnapshot): ApiResult<RoomResponse>

    /** POST /join — resolves a code or join token; 200 with room + room_access_token. */
    suspend fun joinRoom(request: JoinRoomRequest, scope: AuthScopeSnapshot): ApiResult<RoomResponse>

    /** GET /rooms/{id}?room_token= — current room snapshot. */
    suspend fun getRoom(roomId: String, roomToken: String, scope: AuthScopeSnapshot): ApiResult<RoomResponse>

    /** PUT /rooms/{id}/selection?room_token= (host-only). */
    suspend fun setSelection(
        roomId: String,
        roomToken: String,
        request: SetSelectionRequest,
        scope: AuthScopeSnapshot,
    ): ApiResult<RoomResponse>

    /** PATCH /rooms/{id}/policy?room_token= (host-only). */
    suspend fun updatePolicy(
        roomId: String,
        roomToken: String,
        request: UpdatePolicyRequest,
        scope: AuthScopeSnapshot,
    ): ApiResult<RoomResponse>

    /** DELETE /rooms/{id}?room_token= (host-only) — 204 → Unit. */
    suspend fun closeRoom(roomId: String, roomToken: String, scope: AuthScopeSnapshot): ApiResult<Unit>

    /** GET /rooms/{id}/suggestions?room_token=. */
    suspend fun listSuggestions(roomId: String, roomToken: String, scope: AuthScopeSnapshot): ApiResult<SuggestionsResponse>

    /** POST /rooms/{id}/suggestions?room_token=. */
    suspend fun addSuggestion(
        roomId: String,
        roomToken: String,
        request: AddSuggestionRequest,
        scope: AuthScopeSnapshot,
    ): ApiResult<SuggestionsResponse>

    /** DELETE /rooms/{id}/suggestions/{sid}?room_token= (host or suggester). */
    suspend fun deleteSuggestion(
        roomId: String,
        roomToken: String,
        suggestionId: String,
        scope: AuthScopeSnapshot,
    ): ApiResult<SuggestionsResponse>

    /** POST /rooms/{id}/suggestions/{sid}/vote?room_token= — 409 on dup. */
    suspend fun vote(
        roomId: String,
        roomToken: String,
        suggestionId: String,
        scope: AuthScopeSnapshot,
    ): ApiResult<SuggestionsResponse>

    /** DELETE /rooms/{id}/suggestions/{sid}/vote?room_token= — 409 if not voted. */
    suspend fun unvote(
        roomId: String,
        roomToken: String,
        suggestionId: String,
        scope: AuthScopeSnapshot,
    ): ApiResult<SuggestionsResponse>

    /** POST /rooms/{id}/suggestions/promote?room_token= (host-only) → room. */
    suspend fun promoteSuggestion(
        roomId: String,
        roomToken: String,
        request: PromoteSuggestionRequest,
        scope: AuthScopeSnapshot,
    ): ApiResult<RoomResponse>
}

class DefaultWatchTogetherApi(private val client: HttpClient) : WatchTogetherApi {

    override suspend fun createRoom(request: CreateRoomRequest, scope: AuthScopeSnapshot): ApiResult<RoomResponse> =
        safeApiCall {
            client.post("$BASE/rooms") {
                pin(scope)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun joinRoom(request: JoinRoomRequest, scope: AuthScopeSnapshot): ApiResult<RoomResponse> =
        safeApiCall {
            client.post("$BASE/join") {
                pin(scope)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun getRoom(roomId: String, roomToken: String, scope: AuthScopeSnapshot): ApiResult<RoomResponse> =
        safeApiCall {
            client.get("$BASE/rooms/${roomId.encodeURLPathPart()}") {
                pin(scope)
                parameter("room_token", roomToken)
            }
        }

    override suspend fun setSelection(
        roomId: String,
        roomToken: String,
        request: SetSelectionRequest,
        scope: AuthScopeSnapshot,
    ): ApiResult<RoomResponse> = safeApiCall {
        client.put("$BASE/rooms/${roomId.encodeURLPathPart()}/selection") {
            pin(scope)
            parameter("room_token", roomToken)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    override suspend fun updatePolicy(
        roomId: String,
        roomToken: String,
        request: UpdatePolicyRequest,
        scope: AuthScopeSnapshot,
    ): ApiResult<RoomResponse> = safeApiCall {
        client.patch("$BASE/rooms/${roomId.encodeURLPathPart()}/policy") {
            pin(scope)
            parameter("room_token", roomToken)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    override suspend fun closeRoom(
        roomId: String,
        roomToken: String,
        scope: AuthScopeSnapshot,
    ): ApiResult<Unit> =
        safeApiCall {
            client.delete("$BASE/rooms/${roomId.encodeURLPathPart()}") {
                pin(scope)
                parameter("room_token", roomToken)
            }
        }

    override suspend fun listSuggestions(
        roomId: String,
        roomToken: String,
        scope: AuthScopeSnapshot,
    ): ApiResult<SuggestionsResponse> = safeApiCall {
        client.get("$BASE/rooms/${roomId.encodeURLPathPart()}/suggestions") {
            pin(scope)
            parameter("room_token", roomToken)
        }
    }

    override suspend fun addSuggestion(
        roomId: String,
        roomToken: String,
        request: AddSuggestionRequest,
        scope: AuthScopeSnapshot,
    ): ApiResult<SuggestionsResponse> = safeApiCall {
        client.post("$BASE/rooms/${roomId.encodeURLPathPart()}/suggestions") {
            pin(scope)
            parameter("room_token", roomToken)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    override suspend fun deleteSuggestion(
        roomId: String,
        roomToken: String,
        suggestionId: String,
        scope: AuthScopeSnapshot,
    ): ApiResult<SuggestionsResponse> = safeApiCall {
        client.delete(
            "$BASE/rooms/${roomId.encodeURLPathPart()}/suggestions/${suggestionId.encodeURLPathPart()}",
        ) {
            pin(scope)
            parameter("room_token", roomToken)
        }
    }

    override suspend fun vote(
        roomId: String,
        roomToken: String,
        suggestionId: String,
        scope: AuthScopeSnapshot,
    ): ApiResult<SuggestionsResponse> = safeApiCall {
        client.post(
            "$BASE/rooms/${roomId.encodeURLPathPart()}/suggestions/${suggestionId.encodeURLPathPart()}/vote",
        ) {
            pin(scope)
            parameter("room_token", roomToken)
        }
    }

    override suspend fun unvote(
        roomId: String,
        roomToken: String,
        suggestionId: String,
        scope: AuthScopeSnapshot,
    ): ApiResult<SuggestionsResponse> = safeApiCall {
        client.delete(
            "$BASE/rooms/${roomId.encodeURLPathPart()}/suggestions/${suggestionId.encodeURLPathPart()}/vote",
        ) {
            pin(scope)
            parameter("room_token", roomToken)
        }
    }

    override suspend fun promoteSuggestion(
        roomId: String,
        roomToken: String,
        request: PromoteSuggestionRequest,
        scope: AuthScopeSnapshot,
    ): ApiResult<RoomResponse> = safeApiCall {
        client.post("$BASE/rooms/${roomId.encodeURLPathPart()}/suggestions/promote") {
            pin(scope)
            parameter("room_token", roomToken)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    private companion object {
        const val BASE = "/api/v1/watch-together"
    }

    private fun io.ktor.client.request.HttpRequestBuilder.pin(scope: AuthScopeSnapshot) {
        authScope(scope)
        requireSiloAuth()
    }
}
