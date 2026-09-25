package org.siloserver.silo.network.api

import org.siloserver.silo.model.watchtogether.AddSuggestionRequest
import org.siloserver.silo.model.watchtogether.CreateRoomRequest
import org.siloserver.silo.model.watchtogether.JoinRoomRequest
import org.siloserver.silo.model.watchtogether.MemberStateRequest
import org.siloserver.silo.model.watchtogether.MemberStateResponse
import org.siloserver.silo.model.watchtogether.PickerResponse
import org.siloserver.silo.model.watchtogether.PromoteSuggestionRequest
import org.siloserver.silo.model.watchtogether.RoomResponse
import org.siloserver.silo.model.watchtogether.SelectionModeRequest
import org.siloserver.silo.model.watchtogether.SetSelectionRequest
import org.siloserver.silo.model.watchtogether.SourceFallbackRequest
import org.siloserver.silo.model.watchtogether.SuggestionReceipt
import org.siloserver.silo.model.watchtogether.SuggestionsResponse
import org.siloserver.silo.model.watchtogether.UpdatePolicyRequest
import org.siloserver.silo.model.watchtogether.WatchTogetherCapabilitiesV2
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.network.apiv2.OwnerPolicy
import org.siloserver.silo.network.apiv2.ownedV2Call
import org.siloserver.silo.network.authScope
import org.siloserver.silo.network.requireSiloAuth
import org.siloserver.silo.network.singleAttempt
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Watch Party REST surface (`/api/v2/watch-together`).
 *
 * Create and join return a room access token (the room proof) distinct from
 * the account token. Member operations send it as `X-Room-Token`; host-only
 * operations authorize by host identity and send no proof. Every successful
 * room response carries a renewed proof.
 *
 * Retry classes follow each operation's server `RetrySafety`. The three
 * non-retryable operations (start, select, promote) are sent with
 * [singleAttempt] so no authentication refresh replays them. OkHttp's own
 * connection-level retry can still resend a request whose connection dropped
 * mid-exchange; the server treats an immediate identical start, select, or
 * promote as a no-op, and the repository reconciles uncertain outcomes by
 * reading the room instead of retrying.
 *
 * Suggestion mutations return only a receipt. Reading the list afterwards is
 * the caller's reconciliation, so a failed read cannot turn a successful vote
 * into a reported failure.
 */
interface WatchTogetherApi {
    /** GET /capabilities (account authority). */
    suspend fun capabilities(scope: AuthScopeSnapshot): ApiResult<WatchTogetherCapabilitiesV2>

    /** POST /rooms — 201; exact replay of the same `room_id` and body returns the same room. */
    suspend fun createRoom(request: CreateRoomRequest, scope: AuthScopeSnapshot): ApiResult<RoomResponse>

    /** POST /join — resolves a code or join token and issues proof. */
    suspend fun joinRoom(request: JoinRoomRequest, scope: AuthScopeSnapshot): ApiResult<RoomResponse>

    /** GET /rooms/{id} — current snapshot and renewed proof. */
    suspend fun getRoom(roomId: String, roomToken: String, scope: AuthScopeSnapshot): ApiResult<RoomResponse>

    /** PATCH /rooms/{id}/policy (host). */
    suspend fun updatePolicy(roomId: String, request: UpdatePolicyRequest, scope: AuthScopeSnapshot): ApiResult<RoomResponse>

    /** PUT /rooms/{id}/staged-selection (host, host-pick lobby). Staging never plays. */
    suspend fun stageSelection(roomId: String, request: SetSelectionRequest, scope: AuthScopeSnapshot): ApiResult<RoomResponse>

    /** POST /rooms/{id}/playback/start (host). Non-retryable. */
    suspend fun startPlayback(roomId: String, scope: AuthScopeSnapshot): ApiResult<RoomResponse>

    /** POST /rooms/{id}/playback/stop (host). Returns everyone to the staged lobby. */
    suspend fun stopPlayback(roomId: String, scope: AuthScopeSnapshot): ApiResult<RoomResponse>

    /** PATCH /rooms/{id}/selection-mode (host, lobby only). */
    suspend fun setSelectionMode(roomId: String, request: SelectionModeRequest, scope: AuthScopeSnapshot): ApiResult<RoomResponse>

    /** PUT /rooms/{id}/selection (host, host-pick rooms). Starts playback. Non-retryable. */
    suspend fun setSelection(roomId: String, request: SetSelectionRequest, scope: AuthScopeSnapshot): ApiResult<RoomResponse>

    /** DELETE /rooms/{id} (host) — 204. */
    suspend fun closeRoom(roomId: String, scope: AuthScopeSnapshot): ApiResult<Unit>

    /** POST /rooms/{id}/source-fallback (connected member). */
    suspend fun sourceFallback(
        roomId: String,
        roomToken: String,
        request: SourceFallbackRequest,
        scope: AuthScopeSnapshot,
    ): ApiResult<RoomResponse>

    /** GET /rooms/{id}/suggestions — one page in creation order. */
    suspend fun listSuggestions(
        roomId: String,
        roomToken: String,
        scope: AuthScopeSnapshot,
        cursor: String? = null,
        limit: Int = SUGGESTION_PAGE_LIMIT,
    ): ApiResult<SuggestionsResponse>

    /** POST /rooms/{id}/suggestions — 201 receipt echoing the sent id. */
    suspend fun addSuggestion(
        roomId: String,
        roomToken: String,
        request: AddSuggestionRequest,
        scope: AuthScopeSnapshot,
    ): ApiResult<SuggestionReceipt>

    /** DELETE /rooms/{id}/suggestions/{sid} (host or suggester) — 204. */
    suspend fun deleteSuggestion(roomId: String, roomToken: String, suggestionId: String, scope: AuthScopeSnapshot): ApiResult<Unit>

    /** POST /rooms/{id}/suggestions/{sid}/vote — 204, also when already voted. */
    suspend fun vote(roomId: String, roomToken: String, suggestionId: String, scope: AuthScopeSnapshot): ApiResult<Unit>

    /** DELETE /rooms/{id}/suggestions/{sid}/vote — 204, also when not voted. */
    suspend fun unvote(roomId: String, roomToken: String, suggestionId: String, scope: AuthScopeSnapshot): ApiResult<Unit>

    /** POST /rooms/{id}/suggestions/promote (host). Starts playback. Non-retryable. */
    suspend fun promoteSuggestion(
        roomId: String,
        roomToken: String,
        request: PromoteSuggestionRequest,
        scope: AuthScopeSnapshot,
    ): ApiResult<RoomResponse>

    /** POST /rooms/{id}/member-state — a bounded read. */
    suspend fun memberState(
        roomId: String,
        roomToken: String,
        request: MemberStateRequest,
        scope: AuthScopeSnapshot,
    ): ApiResult<MemberStateResponse>

    /** GET /rooms/{id}/picker. */
    suspend fun picker(roomId: String, roomToken: String, scope: AuthScopeSnapshot): ApiResult<PickerResponse>

    companion object {
        const val SUGGESTION_PAGE_LIMIT = 50
    }
}

class DefaultWatchTogetherApi(
    private val client: HttpClient,
    private val gate: ApiV2Gate,
    private val tokens: TokenManager? = null,
) : WatchTogetherApi {

    override suspend fun capabilities(scope: AuthScopeSnapshot): ApiResult<WatchTogetherCapabilitiesV2> =
        call<WatchTogetherCapabilitiesV2, WatchTogetherCapabilitiesV2>(scope, HttpStatusCode.OK, {
            client.get("$BASE/capabilities") { pin(scope) }
        }) { it }

    override suspend fun createRoom(request: CreateRoomRequest, scope: AuthScopeSnapshot): ApiResult<RoomResponse> =
        call<RoomResponse, RoomResponse>(scope, HttpStatusCode.Created, {
            client.post("$BASE/rooms") {
                pin(scope)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }) { it.validated(request.roomId) }

    override suspend fun joinRoom(request: JoinRoomRequest, scope: AuthScopeSnapshot): ApiResult<RoomResponse> =
        call<RoomResponse, RoomResponse>(scope, HttpStatusCode.OK, {
            client.post("$BASE/join") {
                pin(scope)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }) { it.validated(expectedRoomId = null) }

    override suspend fun getRoom(roomId: String, roomToken: String, scope: AuthScopeSnapshot): ApiResult<RoomResponse> =
        roomCall(roomId, scope) { client.get(room(roomId)) { pin(scope, roomToken) } }

    override suspend fun updatePolicy(
        roomId: String,
        request: UpdatePolicyRequest,
        scope: AuthScopeSnapshot,
    ): ApiResult<RoomResponse> = roomCall(roomId, scope) {
        client.patch("${room(roomId)}/policy") {
            pin(scope)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    override suspend fun stageSelection(
        roomId: String,
        request: SetSelectionRequest,
        scope: AuthScopeSnapshot,
    ): ApiResult<RoomResponse> = roomCall(roomId, scope) {
        client.put("${room(roomId)}/staged-selection") {
            pin(scope)
            contentType(ContentType.Application.Json)
            setBody(selectionBody(request))
        }
    }

    override suspend fun startPlayback(roomId: String, scope: AuthScopeSnapshot): ApiResult<RoomResponse> =
        roomCall(roomId, scope) {
            client.post("${room(roomId)}/playback/start") { pin(scope); singleAttempt() }
        }

    override suspend fun stopPlayback(roomId: String, scope: AuthScopeSnapshot): ApiResult<RoomResponse> =
        roomCall(roomId, scope) { client.post("${room(roomId)}/playback/stop") { pin(scope) } }

    override suspend fun setSelectionMode(
        roomId: String,
        request: SelectionModeRequest,
        scope: AuthScopeSnapshot,
    ): ApiResult<RoomResponse> = roomCall(roomId, scope) {
        client.patch("${room(roomId)}/selection-mode") {
            pin(scope)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    override suspend fun setSelection(
        roomId: String,
        request: SetSelectionRequest,
        scope: AuthScopeSnapshot,
    ): ApiResult<RoomResponse> = roomCall(roomId, scope) {
        client.put("${room(roomId)}/selection") {
            pin(scope)
            singleAttempt()
            contentType(ContentType.Application.Json)
            setBody(selectionBody(request))
        }
    }

    override suspend fun closeRoom(roomId: String, scope: AuthScopeSnapshot): ApiResult<Unit> =
        call<Unit, Unit>(scope, HttpStatusCode.NoContent, { client.delete(room(roomId)) { pin(scope) } }) { it }

    override suspend fun sourceFallback(
        roomId: String,
        roomToken: String,
        request: SourceFallbackRequest,
        scope: AuthScopeSnapshot,
    ): ApiResult<RoomResponse> = roomCall(roomId, scope) {
        client.post("${room(roomId)}/source-fallback") {
            pin(scope, roomToken)
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    override suspend fun listSuggestions(
        roomId: String,
        roomToken: String,
        scope: AuthScopeSnapshot,
        cursor: String?,
        limit: Int,
    ): ApiResult<SuggestionsResponse> =
        call<SuggestionsResponse, SuggestionsResponse>(scope, HttpStatusCode.OK, {
            client.get("${room(roomId)}/suggestions") {
                pin(scope, roomToken)
                parameter("limit", limit)
                cursor?.let { parameter("cursor", it) }
            }
        }) { page ->
            require(page.suggestions.all { it.id.isNotBlank() && it.roomId == roomId }) {
                "The suggestion page named another room."
            }
            require(page.page?.hasMore != true || !page.page.nextCursor.isNullOrBlank()) {
                "The suggestion page has more items but no cursor."
            }
            page
        }

    override suspend fun addSuggestion(
        roomId: String,
        roomToken: String,
        request: AddSuggestionRequest,
        scope: AuthScopeSnapshot,
    ): ApiResult<SuggestionReceipt> =
        call<SuggestionReceipt, SuggestionReceipt>(scope, HttpStatusCode.Created, {
            client.post("${room(roomId)}/suggestions") {
                pin(scope, roomToken)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }) { receipt ->
            require(receipt.suggestionId == request.suggestionId) { "The suggestion receipt named another suggestion." }
            receipt
        }

    override suspend fun deleteSuggestion(
        roomId: String,
        roomToken: String,
        suggestionId: String,
        scope: AuthScopeSnapshot,
    ): ApiResult<Unit> = call<Unit, Unit>(scope, HttpStatusCode.NoContent, {
        client.delete(suggestion(roomId, suggestionId)) { pin(scope, roomToken) }
    }) { it }

    override suspend fun vote(
        roomId: String,
        roomToken: String,
        suggestionId: String,
        scope: AuthScopeSnapshot,
    ): ApiResult<Unit> = call<Unit, Unit>(scope, HttpStatusCode.NoContent, {
        client.post("${suggestion(roomId, suggestionId)}/vote") { pin(scope, roomToken) }
    }) { it }

    override suspend fun unvote(
        roomId: String,
        roomToken: String,
        suggestionId: String,
        scope: AuthScopeSnapshot,
    ): ApiResult<Unit> = call<Unit, Unit>(scope, HttpStatusCode.NoContent, {
        client.delete("${suggestion(roomId, suggestionId)}/vote") { pin(scope, roomToken) }
    }) { it }

    override suspend fun promoteSuggestion(
        roomId: String,
        roomToken: String,
        request: PromoteSuggestionRequest,
        scope: AuthScopeSnapshot,
    ): ApiResult<RoomResponse> = roomCall(roomId, scope) {
        client.post("${room(roomId)}/suggestions/promote") {
            pin(scope, roomToken)
            singleAttempt()
            contentType(ContentType.Application.Json)
            setBody(request)
        }
    }

    override suspend fun memberState(
        roomId: String,
        roomToken: String,
        request: MemberStateRequest,
        scope: AuthScopeSnapshot,
    ): ApiResult<MemberStateResponse> {
        if (request.contentIds.isEmpty() || request.contentIds.any { it.isBlank() }) {
            return ApiResult.Error(422, "validation_failed", "Member state needs at least one content id.")
        }
        val requested = request.contentIds.toSet()
        return call<MemberStateResponse, MemberStateResponse>(scope, HttpStatusCode.OK, {
            client.post("${room(roomId)}/member-state") {
                pin(scope, roomToken)
                contentType(ContentType.Application.Json)
                setBody(request)
            }
        }) { body ->
            require(body.items.all { it.contentId in requested }) { "Member state named an unrequested item." }
            body
        }
    }

    override suspend fun picker(roomId: String, roomToken: String, scope: AuthScopeSnapshot): ApiResult<PickerResponse> =
        call<PickerResponse, PickerResponse>(scope, HttpStatusCode.OK, {
            client.get("${room(roomId)}/picker") { pin(scope, roomToken) }
        }) { body ->
            require((body.continueTogether + body.watchlistUnion).none { it.item.contentId.isBlank() }) {
                "The picker returned a card without an id."
            }
            body
        }

    /**
     * One owned exchange; the captured [scope] must still own the session
     * before and after. Without a token manager there is nothing to compare
     * against, so only the status and body checks apply.
     */
    private suspend inline fun <reified T, R> call(
        scope: AuthScopeSnapshot,
        expected: HttpStatusCode,
        crossinline block: suspend () -> HttpResponse,
        crossinline project: (T) -> R,
    ): ApiResult<R> =
        ownedV2Call<T, R>(gate, tokens, scope.takeIf { tokens != null }, OwnerPolicy.PROFILE, expected, { block() }, project)

    private suspend inline fun roomCall(
        roomId: String,
        scope: AuthScopeSnapshot,
        crossinline block: suspend () -> HttpResponse,
    ): ApiResult<RoomResponse> =
        call<RoomResponse, RoomResponse>(scope, HttpStatusCode.OK, block) { it.validated(roomId) }

    private fun RoomResponse.validated(expectedRoomId: String?): RoomResponse {
        require(room.roomId.isNotBlank() && roomAccessToken.isNotBlank()) { "The room response was incomplete." }
        require(expectedRoomId == null || room.roomId == expectedRoomId) { "The room response named another room." }
        return this
    }

    private fun selectionBody(request: SetSelectionRequest) = buildJsonObject {
        put("content_id", request.contentId)
        request.fileId?.let { put("file_id", it.toString()) }
        request.libraryId?.let { put("library_id", it.toString()) }
    }

    private companion object {
        const val BASE = "/api/v2/watch-together"
    }

    private fun room(roomId: String) = "$BASE/rooms/${roomId.encodeURLPathPart()}"

    private fun suggestion(roomId: String, suggestionId: String) =
        "${room(roomId)}/suggestions/${suggestionId.encodeURLPathPart()}"

    private fun HttpRequestBuilder.pin(scope: AuthScopeSnapshot, roomToken: String? = null) {
        authScope(scope)
        requireSiloAuth()
        roomToken?.let { header(ROOM_TOKEN_HEADER, it) }
    }
}

internal const val ROOM_TOKEN_HEADER = "X-Room-Token"
