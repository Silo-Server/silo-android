package org.siloserver.silo.network.api

import org.siloserver.silo.model.watchtogether.AddSuggestionRequest
import org.siloserver.silo.model.watchtogether.CreateRoomRequest
import org.siloserver.silo.model.watchtogether.JoinRoomRequest
import org.siloserver.silo.model.watchtogether.MemberStateRequest
import org.siloserver.silo.model.watchtogether.PromoteSuggestionRequest
import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.SelectionModeRequest
import org.siloserver.silo.model.watchtogether.SetSelectionRequest
import org.siloserver.silo.model.watchtogether.SourceFallbackRequest
import org.siloserver.silo.model.watchtogether.UpdatePolicyRequest
import org.siloserver.silo.model.watchtogether.readWatchPartyFixture
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeAttributeKey
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.SingleAttemptAttributeKey
import org.siloserver.silo.network.apiv2.ApiV2Gate
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchTogetherApiTest {
    private val scope = AuthScopeSnapshot(
        serverId = "server-1",
        profileId = "profile-1",
        serverUrl = "https://silo.example",
        profileToken = "profile-token",
    )
    private val roomId = "9f3c1a52-3c4e-4f7b-9a0e-6b2d8c1e7a10"
    private val roomBody = readWatchPartyFixture("room_http.json")

    private data class Call(
        val method: HttpMethod,
        val path: String,
        val query: Map<String, String?>,
        val roomToken: String?,
        val singleAttempt: Boolean,
        val pinnedScope: AuthScopeSnapshot?,
        val body: String,
    )

    private class Server(var status: HttpStatusCode = HttpStatusCode.OK, var body: String = "{}") {
        val calls = mutableListOf<Call>()
        val last: Call get() = calls.last()
    }

    private fun api(server: Server): WatchTogetherApi {
        val client = HttpClient(
            MockEngine { request ->
                server.calls += Call(
                    method = request.method,
                    path = request.url.encodedPath,
                    query = request.url.parameters.names().associateWith { request.url.parameters[it] },
                    roomToken = request.headers["X-Room-Token"],
                    singleAttempt = request.attributes.getOrNull(SingleAttemptAttributeKey) == true,
                    pinnedScope = request.attributes.getOrNull(AuthScopeAttributeKey),
                    body = request.body.toByteArray().decodeToString(),
                )
                val type = if (server.status.value >= 400) "application/problem+json" else "application/json"
                respond(server.body, server.status, headersOf(HttpHeaders.ContentType, type))
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
        }
        return DefaultWatchTogetherApi(client, ApiV2Gate.Unrestricted)
    }

    private fun sent(call: Call) = SiloJson.parseToJsonElement(call.body).jsonObject

    private fun assertInvalidResponse(result: ApiResult<*>) {
        assertIs<ApiResult.Error>(result)
        assertEquals("invalid_response", result.error)
    }

    // ---- create / join / read --------------------------------------------------

    @Test
    fun `create sends the caller room identity and requires 201`() = runTest {
        val server = Server(HttpStatusCode.Created, roomBody)
        val result = api(server).createRoom(CreateRoomRequest(roomId = roomId, selectionMode = "vote"), scope)

        assertIs<ApiResult.Success<*>>(result)
        assertEquals(HttpMethod.Post, server.last.method)
        assertEquals("/api/v2/watch-together/rooms", server.last.path)
        assertEquals(setOf("room_id", "selection_mode"), sent(server.last).keys)
        assertEquals(roomId, sent(server.last).getValue("room_id").jsonPrimitive.content)
        assertEquals(scope, server.last.pinnedScope)
        assertNull(server.last.roomToken)
        assertFalse(server.last.singleAttempt)
    }

    @Test
    fun `create answered with the wrong success status is an invalid response`() = runTest {
        val result = api(Server(HttpStatusCode.OK, roomBody)).createRoom(CreateRoomRequest(roomId = roomId), scope)
        assertInvalidResponse(result)
    }

    @Test
    fun `create response naming another room is an invalid response`() = runTest {
        val result = api(Server(HttpStatusCode.Created, roomBody)).createRoom(CreateRoomRequest(roomId = "other"), scope)
        assertInvalidResponse(result)
    }

    @Test
    fun `join decodes the v2 snapshot with string ids and members`() = runTest {
        val server = Server(body = roomBody)
        val result = api(server).joinRoom(JoinRoomRequest(code = "K7PQ2M4X"), scope)

        assertIs<ApiResult.Success<org.siloserver.silo.model.watchtogether.RoomResponse>>(result)
        val room = result.data.room
        assertEquals(RoomPhase.Playing, room.phase)
        assertEquals(42, room.selectedFileId)
        assertEquals(7, room.selectedLibraryId)
        assertEquals(2, room.members.size)
        assertEquals("12", room.selfMember?.userId)
        assertTrue(room.members[1].isSyncing)
        assertEquals("/rooms/join?token=Zq8vN3kR5tW1yB6cD9fG2hJ4", room.invitePath)
        assertEquals(mapOf("code" to "K7PQ2M4X"), sent(server.last).mapValues { it.value.jsonPrimitive.content })
    }

    @Test
    fun `a room response without proof is an invalid response`() = runTest {
        val body = roomBody.replace(Regex("\"room_access_token\": \"[^\"]+\""), "\"room_access_token\": \"\"")
        assertInvalidResponse(api(Server(body = body)).joinRoom(JoinRoomRequest(code = "K7PQ2M4X"), scope))
    }

    @Test
    fun `room read sends the proof and rejects another room`() = runTest {
        val server = Server(body = roomBody)
        assertIs<ApiResult.Success<*>>(api(server).getRoom(roomId, "proof-1", scope))
        assertEquals("proof-1", server.last.roomToken)
        assertEquals("/api/v2/watch-together/rooms/$roomId", server.last.path)

        assertInvalidResponse(api(server).getRoom("another-room", "proof-1", scope))
    }

    @Test
    fun `malformed success body is an invalid response, not a network error`() = runTest {
        assertInvalidResponse(api(Server(body = """{"room":{}}""")).getRoom(roomId, "proof-1", scope))
    }

    // ---- host operations --------------------------------------------------------

    @Test
    fun `host operations authorize by identity and send no proof`() = runTest {
        val server = Server(body = roomBody)
        val api = api(server)
        api.updatePolicy(roomId, UpdatePolicyRequest("guest_play_pause"), scope)
        api.stageSelection(roomId, SetSelectionRequest("movie:heat-1995", fileId = 42, libraryId = 7), scope)
        api.startPlayback(roomId, scope)
        api.stopPlayback(roomId, scope)
        api.setSelectionMode(roomId, SelectionModeRequest("vote"), scope)
        api.setSelection(roomId, SetSelectionRequest("movie:heat-1995"), scope)

        assertEquals(
            listOf(
                HttpMethod.Patch to "/policy",
                HttpMethod.Put to "/staged-selection",
                HttpMethod.Post to "/playback/start",
                HttpMethod.Post to "/playback/stop",
                HttpMethod.Patch to "/selection-mode",
                HttpMethod.Put to "/selection",
            ),
            server.calls.map { it.method to it.path.removePrefix("/api/v2/watch-together/rooms/$roomId") },
        )
        assertTrue(server.calls.all { it.roomToken == null && it.pinnedScope == scope })
    }

    @Test
    fun `stage sends string file and library ids`() = runTest {
        val server = Server(body = roomBody)
        api(server).stageSelection(roomId, SetSelectionRequest("movie:heat-1995", fileId = 42, libraryId = 7), scope)
        val body = sent(server.last)
        assertEquals("42", body.getValue("file_id").jsonPrimitive.content)
        assertTrue(body.getValue("file_id").jsonPrimitive.isString)
        assertEquals("7", body.getValue("library_id").jsonPrimitive.content)
    }

    @Test
    fun `only non-retryable operations skip the authentication replay`() = runTest {
        val server = Server(body = roomBody)
        val api = api(server)
        api.startPlayback(roomId, scope)
        api.setSelection(roomId, SetSelectionRequest("movie:heat-1995"), scope)
        api.promoteSuggestion(roomId, "proof-1", PromoteSuggestionRequest("b2a1"), scope)
        api.stopPlayback(roomId, scope)
        api.stageSelection(roomId, SetSelectionRequest("movie:heat-1995"), scope)
        api.getRoom(roomId, "proof-1", scope)

        assertEquals(listOf(true, true, true, false, false, false), server.calls.map { it.singleAttempt })
    }

    @Test
    fun `end requires 204`() = runTest {
        val server = Server(HttpStatusCode.NoContent, "")
        assertIs<ApiResult.Success<Unit>>(api(server).closeRoom(roomId, scope))
        assertEquals(HttpMethod.Delete, server.last.method)
        assertNull(server.last.roomToken)
    }

    @Test
    fun `source fallback sends the fence and reason with proof`() = runTest {
        val server = Server(body = roomBody)
        api(server).sourceFallback(
            roomId,
            "proof-1",
            SourceFallbackRequest(selectionRevision = 3, failedFileId = "42", reason = "hdr_transcode_unsupported"),
            scope,
        )
        assertEquals("proof-1", server.last.roomToken)
        assertEquals("/api/v2/watch-together/rooms/$roomId/source-fallback", server.last.path)
        val body = sent(server.last)
        assertEquals("3", body.getValue("selection_revision").jsonPrimitive.content)
        assertEquals("42", body.getValue("failed_file_id").jsonPrimitive.content)
        assertEquals("hdr_transcode_unsupported", body.getValue("reason").jsonPrimitive.content)
    }

    @Test
    fun `problem responses keep status and code`() = runTest {
        val problem = """{"type":"https://silo.example/problems/conflict","title":"Conflict","status":409,"detail":"The room is closed."}"""
        val result = api(Server(HttpStatusCode.Conflict, problem)).startPlayback(roomId, scope)
        assertIs<ApiResult.Error>(result)
        assertEquals(409, result.code)
        assertEquals("conflict", result.error)
    }

    // ---- suggestions -------------------------------------------------------------

    @Test
    fun `suggestion page passes limit and cursor and decodes page metadata`() = runTest {
        val server = Server(body = readWatchPartyFixture("suggestions_page.json"))
        val result = api(server).listSuggestions(roomId, "proof-1", scope, cursor = "abc")

        assertIs<ApiResult.Success<org.siloserver.silo.model.watchtogether.SuggestionsResponse>>(result)
        assertEquals(mapOf("limit" to "50", "cursor" to "abc"), server.last.query)
        assertEquals("proof-1", server.last.roomToken)
        assertEquals(true, result.data.page?.hasMore)
        assertEquals("c2VlZDoy", result.data.page?.nextCursor)
        assertTrue(result.data.suggestions.single().votedByMe)
    }

    @Test
    fun `a page with more items but no cursor is an invalid response`() = runTest {
        val body = """{"items":[],"page":{"has_more":true}}"""
        assertInvalidResponse(api(Server(body = body)).listSuggestions(roomId, "proof-1", scope))
    }

    @Test
    fun `add suggestion sends the caller id and checks the receipt`() = runTest {
        val request = AddSuggestionRequest(suggestionId = "s-1", contentId = "movie:heat-1995", contentType = "movie", title = "Heat")
        val server = Server(HttpStatusCode.Created, """{"suggestion_id":"s-1"}""")
        assertIs<ApiResult.Success<*>>(api(server).addSuggestion(roomId, "proof-1", request, scope))
        assertEquals("s-1", sent(server.last).getValue("suggestion_id").jsonPrimitive.content)
        assertEquals(1, server.calls.size, "a mutation must not read the list itself")

        server.body = """{"suggestion_id":"s-2"}"""
        assertInvalidResponse(api(server).addSuggestion(roomId, "proof-1", request, scope))
    }

    @Test
    fun `votes return an empty receipt and never read the list`() = runTest {
        val server = Server(HttpStatusCode.NoContent, "")
        val api = api(server)
        assertIs<ApiResult.Success<Unit>>(api.vote(roomId, "proof-1", "b2a1", scope))
        assertIs<ApiResult.Success<Unit>>(api.unvote(roomId, "proof-1", "b2a1", scope))
        assertIs<ApiResult.Success<Unit>>(api.deleteSuggestion(roomId, "proof-1", "b2a1", scope))
        assertEquals(
            listOf(HttpMethod.Post, HttpMethod.Delete, HttpMethod.Delete),
            server.calls.map { it.method },
        )
        assertEquals("/api/v2/watch-together/rooms/$roomId/suggestions/b2a1/vote", server.calls[0].path)
    }

    // ---- shared browsing ---------------------------------------------------------

    @Test
    fun `member state rejects an empty id list before sending`() = runTest {
        val server = Server(body = readWatchPartyFixture("member_state.json"))
        val empty = api(server).memberState(roomId, "proof-1", MemberStateRequest(emptyList()), scope)
        assertIs<ApiResult.Error>(empty)
        assertEquals(422, empty.code)
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun `member state decodes and rejects unrequested items`() = runTest {
        val server = Server(body = readWatchPartyFixture("member_state.json"))
        val result = api(server).memberState(roomId, "proof-1", MemberStateRequest(listOf("movie:heat-1995")), scope)
        assertIs<ApiResult.Success<org.siloserver.silo.model.watchtogether.MemberStateResponse>>(result)
        assertEquals("in_progress", result.data.items.single().members.single().state)

        assertInvalidResponse(api(server).memberState(roomId, "proof-1", MemberStateRequest(listOf("movie:other")), scope))
    }

    @Test
    fun `picker decodes shared rows with next up`() = runTest {
        val server = Server(body = readWatchPartyFixture("picker.json"))
        val result = api(server).picker(roomId, "proof-1", scope)
        assertIs<ApiResult.Success<org.siloserver.silo.model.watchtogether.PickerResponse>>(result)
        assertTrue(result.data.continueTogether.isEmpty())
        val row = result.data.watchlistUnion.single()
        assertEquals("series:the-bear", row.item.contentId)
        assertEquals("episode:the-bear-s02e06", row.nextUp?.contentId)
        assertEquals("proof-1", server.last.roomToken)
    }

    @Test
    fun `capabilities decode the vendored server fixture`() = runTest {
        val server = Server(body = readWatchPartyFixture("capabilities.json"))
        val result = api(server).capabilities(scope)
        assertIs<ApiResult.Success<org.siloserver.silo.model.watchtogether.WatchTogetherCapabilitiesV2>>(result)
        assertEquals("silo.room.v2", result.data.socketProtocol)
        assertEquals(200, result.data.maxMemberStateIds)
        assertEquals(false, result.data.allowed)
        assertEquals("/api/v2/watch-together/capabilities", server.last.path)
    }
}
