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
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.network.apiv2.safeApiV2Call
import org.siloserver.silo.network.authScope
import org.siloserver.silo.network.requireSiloAuth
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Watch Together REST surface (`/api/v2/watch-together`). Create/join return a
 * second token (the **room JWT**) distinct from the auth JWT; every
 * room-scoped call passes it as the `X-Room-Token` header. Behind an
 * interface so the repository's tests fake the transport (matching
 * NotificationsApi).
 *
 * The room WS is a separate transport (see WatchTogetherRealtimeClient); this
 * is REST only. Suggestion mutations return 201/204 on the server; this
 * transport follows each with a suggestions read so callers still receive the
 * current list. Non-2xx replies are RFC 9457 problems surfaced as
 * [ApiResult.Error].
 */
interface WatchTogetherApi {

    /** POST /rooms — caller becomes host; 201 with room + room_access_token. */
    suspend fun createRoom(request: CreateRoomRequest, scope: AuthScopeSnapshot): ApiResult<RoomResponse>

    /** POST /join — resolves a code or join token; 200 with room + room_access_token. */
    suspend fun joinRoom(request: JoinRoomRequest, scope: AuthScopeSnapshot): ApiResult<RoomResponse>

    /** GET /rooms/{id} — current room snapshot. */
    suspend fun getRoom(roomId: String, roomToken: String, scope: AuthScopeSnapshot): ApiResult<RoomResponse>

    /** PUT /rooms/{id}/selection (host-only). */
    suspend fun setSelection(
        roomId: String,
        roomToken: String,
        request: SetSelectionRequest,
        scope: AuthScopeSnapshot,
    ): ApiResult<RoomResponse>

    /** PATCH /rooms/{id}/policy (host-only). */
    suspend fun updatePolicy(
        roomId: String,
        roomToken: String,
        request: UpdatePolicyRequest,
        scope: AuthScopeSnapshot,
    ): ApiResult<RoomResponse>

    /** DELETE /rooms/{id} (host-only) — 204 → Unit. */
    suspend fun closeRoom(roomId: String, roomToken: String, scope: AuthScopeSnapshot): ApiResult<Unit>

    /** GET /rooms/{id}/suggestions. */
    suspend fun listSuggestions(roomId: String, roomToken: String, scope: AuthScopeSnapshot): ApiResult<SuggestionsResponse>

    /** POST /rooms/{id}/suggestions. */
    suspend fun addSuggestion(
        roomId: String,
        roomToken: String,
        request: AddSuggestionRequest,
        scope: AuthScopeSnapshot,
    ): ApiResult<SuggestionsResponse>

    /** DELETE /rooms/{id}/suggestions/{sid} (host or suggester). */
    suspend fun deleteSuggestion(
        roomId: String,
        roomToken: String,
        suggestionId: String,
        scope: AuthScopeSnapshot,
    ): ApiResult<SuggestionsResponse>

    /** POST /rooms/{id}/suggestions/{sid}/vote — 409 on dup. */
    suspend fun vote(
        roomId: String,
        roomToken: String,
        suggestionId: String,
        scope: AuthScopeSnapshot,
    ): ApiResult<SuggestionsResponse>

    /** DELETE /rooms/{id}/suggestions/{sid}/vote — 409 if not voted. */
    suspend fun unvote(
        roomId: String,
        roomToken: String,
        suggestionId: String,
        scope: AuthScopeSnapshot,
    ): ApiResult<SuggestionsResponse>

    /** POST /rooms/{id}/suggestions/promote (host-only) → room. */
    suspend fun promoteSuggestion(
        roomId: String,
        roomToken: String,
        request: PromoteSuggestionRequest,
        scope: AuthScopeSnapshot,
    ): ApiResult<RoomResponse>
}

@OptIn(ExperimentalUuidApi::class)
class DefaultWatchTogetherApi(
    private val client: HttpClient,
    private val gate: ApiV2Gate,
) : WatchTogetherApi {

    override suspend fun createRoom(request: CreateRoomRequest, scope: AuthScopeSnapshot): ApiResult<RoomResponse> =
        safeApiV2Call(gate) {
            client.post("$BASE/rooms") {
                pin(scope)
                contentType(ContentType.Application.Json)
                setBody(
                    buildJsonObject {
                        put("room_id", Uuid.random().toString())
                        put("selection_mode", request.selectionMode ?: "host_pick")
                    },
                )
            }
        }

    override suspend fun joinRoom(request: JoinRoomRequest, scope: AuthScopeSnapshot): ApiResult<RoomResponse> =
        safeApiV2Call(gate) {
            client.post("$BASE/join") {
                pin(scope)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }

    override suspend fun getRoom(roomId: String, roomToken: String, scope: AuthScopeSnapshot): ApiResult<RoomResponse> =
        safeApiV2Call(gate) {
            client.get(room(roomId)) { pin(scope, roomToken) }
        }

    override suspend fun setSelection(
        roomId: String,
        roomToken: String,
        request: SetSelectionRequest,
        scope: AuthScopeSnapshot,
    ): ApiResult<RoomResponse> = safeApiV2Call(gate) {
        client.put("${room(roomId)}/selection") {
            pin(scope, roomToken)
            contentType(ContentType.Application.Json)
            setBody(
                buildJsonObject {
                    put("content_id", request.contentId)
                    request.fileId?.let { put("file_id", it.toString()) }
                    request.libraryId?.let { put("library_id", it.toString()) }
                },
            )
        }
    }

    override suspend fun updatePolicy(
        roomId: String,
        roomToken: String,
        request: UpdatePolicyRequest,
        scope: AuthScopeSnapshot,
    ): ApiResult<RoomResponse> = safeApiV2Call(gate) {
        client.patch("${room(roomId)}/policy") {
            pin(scope, roomToken)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    override suspend fun closeRoom(roomId: String, roomToken: String, scope: AuthScopeSnapshot): ApiResult<Unit> =
        safeApiV2Call(gate) {
            client.delete(room(roomId)) { pin(scope, roomToken) }
        }

    override suspend fun listSuggestions(
        roomId: String,
        roomToken: String,
        scope: AuthScopeSnapshot,
    ): ApiResult<SuggestionsResponse> = safeApiV2Call(gate) {
        client.get("${room(roomId)}/suggestions") { pin(scope, roomToken) }
    }

    override suspend fun addSuggestion(
        roomId: String,
        roomToken: String,
        request: AddSuggestionRequest,
        scope: AuthScopeSnapshot,
    ): ApiResult<SuggestionsResponse> = thenList(roomId, roomToken, scope) {
        client.post("${room(roomId)}/suggestions") {
            pin(scope, roomToken)
            contentType(ContentType.Application.Json)
            val fields = SiloJson.encodeToJsonElement(AddSuggestionRequest.serializer(), request).jsonObject
            setBody(JsonObject(fields + ("suggestion_id" to JsonPrimitive(Uuid.random().toString()))))
        }
    }

    override suspend fun deleteSuggestion(
        roomId: String,
        roomToken: String,
        suggestionId: String,
        scope: AuthScopeSnapshot,
    ): ApiResult<SuggestionsResponse> = thenList(roomId, roomToken, scope) {
        client.delete(suggestion(roomId, suggestionId)) { pin(scope, roomToken) }
    }

    override suspend fun vote(
        roomId: String,
        roomToken: String,
        suggestionId: String,
        scope: AuthScopeSnapshot,
    ): ApiResult<SuggestionsResponse> = thenList(roomId, roomToken, scope) {
        client.post("${suggestion(roomId, suggestionId)}/vote") { pin(scope, roomToken) }
    }

    override suspend fun unvote(
        roomId: String,
        roomToken: String,
        suggestionId: String,
        scope: AuthScopeSnapshot,
    ): ApiResult<SuggestionsResponse> = thenList(roomId, roomToken, scope) {
        client.delete("${suggestion(roomId, suggestionId)}/vote") { pin(scope, roomToken) }
    }

    override suspend fun promoteSuggestion(
        roomId: String,
        roomToken: String,
        request: PromoteSuggestionRequest,
        scope: AuthScopeSnapshot,
    ): ApiResult<RoomResponse> = safeApiV2Call(gate) {
        client.post("${room(roomId)}/suggestions/promote") {
            pin(scope, roomToken)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    /** Runs a bodiless suggestion mutation, then reads the current list. */
    private suspend inline fun thenList(
        roomId: String,
        roomToken: String,
        scope: AuthScopeSnapshot,
        mutation: () -> io.ktor.client.statement.HttpResponse,
    ): ApiResult<SuggestionsResponse> {
        return when (val mutated = safeApiV2Call<Unit>(gate, mutation)) {
            is ApiResult.Success -> listSuggestions(roomId, roomToken, scope)
            is ApiResult.Error -> mutated
            is ApiResult.NetworkError -> mutated
        }
    }

    private companion object {
        const val BASE = "/api/v2/watch-together"
    }

    private fun room(roomId: String) = "$BASE/rooms/${roomId.encodeURLPathPart()}"

    private fun suggestion(roomId: String, suggestionId: String) =
        "${room(roomId)}/suggestions/${suggestionId.encodeURLPathPart()}"

    private fun io.ktor.client.request.HttpRequestBuilder.pin(scope: AuthScopeSnapshot, roomToken: String? = null) {
        authScope(scope)
        requireSiloAuth()
        roomToken?.let { header("X-Room-Token", it) }
    }
}
