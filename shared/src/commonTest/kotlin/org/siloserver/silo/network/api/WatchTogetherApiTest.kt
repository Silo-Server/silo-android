package org.siloserver.silo.network.api

import org.siloserver.silo.model.watchtogether.AddSuggestionRequest
import org.siloserver.silo.model.watchtogether.CreateRoomRequest
import org.siloserver.silo.model.watchtogether.JoinRoomRequest
import org.siloserver.silo.model.watchtogether.PromoteSuggestionRequest
import org.siloserver.silo.model.watchtogether.SetSelectionRequest
import org.siloserver.silo.model.watchtogether.UpdatePolicyRequest
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.SiloJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WatchTogetherApiTest {
    private val scope = AuthScopeSnapshot(
        serverId = "server-1",
        profileId = "profile-1",
        serverUrl = "https://silo.example",
        profileToken = "profile-token",
    )

    private class Captured {
        val calls = mutableListOf<Call>()
        val last: Call get() = calls.last()
        val first: Call get() = calls.first()
    }

    private data class Call(
        val method: HttpMethod,
        val path: String,
        val query: Map<String, String?>,
        val roomToken: String?,
        val body: String,
    )

    private val roomJson = """
        {"room_id":"room-1","phase":"lobby","playback_state":"idle","selection_mode":"host_pick",
         "selection_revision":0,"code":"ABCD1234","guest_control_policy":"host_only",
         "is_paused":false,"anchor_position_seconds":0.0,"anchor_updated_at":"2026-06-12T09:00:00Z",
         "generation":1,"member_count":1,"host_connected":true,"self_role":"host",
         "self_can_control_transport":true,"self_can_manage_room":true,"self_ignore_wait":false}
    """.trimIndent()

    private val suggestionsJson = """
        {"items":[{"id":"sug-5","room_id":"room-1","suggester_user_id":"7","suggester_profile_id":"p",
          "content_id":"c","content_type":"movie","title":"T","subtitle":"","poster_url":"","note":"",
          "vote_count":2,"voted_by_me":true,"created_at":"2026-06-12T08:00:00Z"}],
         "page":{"has_more":false}}
    """.trimIndent()

    private val problem = """{"type":"https://silo.example/problems/conflict","title":"Conflict","status":409,"detail":"Already voted"}"""

    /** Routes by method: the first response answers the mutation, a suggestions GET always answers with the list. */
    private fun api(
        status: HttpStatusCode = HttpStatusCode.OK,
        responseBody: String = "{}",
        captured: Captured = Captured(),
    ): Pair<WatchTogetherApi, Captured> {
        val client = HttpClient(
            MockEngine { request ->
                captured.calls += Call(
                    method = request.method,
                    path = request.url.encodedPath,
                    query = request.url.parameters.names().associateWith { request.url.parameters[it] },
                    roomToken = request.headers["X-Room-Token"],
                    body = request.body.toByteArray().decodeToString(),
                )
                val followUpList = captured.calls.size > 1 &&
                    request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/suggestions")
                if (followUpList) {
                    respond(suggestionsJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                } else {
                    respond(
                        content = responseBody,
                        status = status,
                        headers = headersOf(HttpHeaders.ContentType, "application/problem+json"),
                    )
                }
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
        }
        return DefaultWatchTogetherApi(client, org.siloserver.silo.network.apiv2.ApiV2Gate.Unrestricted) to captured
    }

    @Test
    fun `createRoom posts client room_id and selection_mode and decodes room + token`() = runTest {
        val (api, captured) = api(
            status = HttpStatusCode.Created,
            responseBody = """{"room":$roomJson,"room_access_token":"jwt-1"}""",
        )
        val r = api.createRoom(CreateRoomRequest(selectionMode = "vote"), scope)
        assertEquals(HttpMethod.Post, captured.last.method)
        assertEquals("/api/v2/watch-together/rooms", captured.last.path)
        val sent = SiloJson.parseToJsonElement(captured.last.body).jsonObject
        assertEquals(setOf("room_id", "selection_mode"), sent.keys)
        assertEquals("vote", sent.getValue("selection_mode").jsonPrimitive.content)
        assertTrue(sent.getValue("room_id").jsonPrimitive.content.length == 36)
        assertIs<ApiResult.Success<*>>(r)
        assertEquals("jwt-1", (r as ApiResult.Success).data.roomAccessToken)
    }

    @Test
    fun `createRoom defaults selection_mode to host_pick`() = runTest {
        val (api, captured) = api(
            status = HttpStatusCode.Created,
            responseBody = """{"room":$roomJson,"room_access_token":"jwt-1"}""",
        )
        api.createRoom(CreateRoomRequest(), scope)
        val sent = SiloJson.parseToJsonElement(captured.last.body).jsonObject
        assertEquals("host_pick", sent.getValue("selection_mode").jsonPrimitive.content)
    }

    @Test
    fun `joinRoom posts code and decodes room`() = runTest {
        val (api, captured) = api(responseBody = """{"room":$roomJson,"room_access_token":"jwt-2"}""")
        val r = api.joinRoom(JoinRoomRequest(code = "ABCD1234"), scope)
        assertEquals(HttpMethod.Post, captured.last.method)
        assertEquals("/api/v2/watch-together/join", captured.last.path)
        assertIs<ApiResult.Success<*>>(r)
        assertEquals("room-1", (r as ApiResult.Success).data.room.roomId)
    }

    @Test
    fun `getRoom passes X-Room-Token header and no query`() = runTest {
        val (api, captured) = api(responseBody = """{"room":$roomJson,"room_access_token":"jwt-room"}""")
        api.getRoom("room-1", "jwt-room", scope)
        assertEquals(HttpMethod.Get, captured.last.method)
        assertEquals("/api/v2/watch-together/rooms/room-1", captured.last.path)
        assertEquals("jwt-room", captured.last.roomToken)
        assertTrue(captured.last.query.isEmpty())
    }

    @Test
    fun `setSelection puts content_id with string file_id`() = runTest {
        val (api, captured) = api(responseBody = """{"room":$roomJson,"room_access_token":"jwt-room"}""")
        api.setSelection("room-1", "jwt-room", SetSelectionRequest(contentId = "tt-9", fileId = 3), scope)
        assertEquals(HttpMethod.Put, captured.last.method)
        assertEquals("/api/v2/watch-together/rooms/room-1/selection", captured.last.path)
        assertEquals("jwt-room", captured.last.roomToken)
        val sent = SiloJson.parseToJsonElement(captured.last.body).jsonObject
        assertEquals("tt-9", sent.getValue("content_id").jsonPrimitive.content)
        assertEquals("3", sent.getValue("file_id").jsonPrimitive.content)
        assertTrue(sent.getValue("file_id").jsonPrimitive.isString)
        assertTrue("library_id" !in sent)
    }

    @Test
    fun `updatePolicy patches policy with X-Room-Token`() = runTest {
        val (api, captured) = api(responseBody = """{"room":$roomJson,"room_access_token":"jwt-room"}""")
        api.updatePolicy(
            "room-1",
            "jwt-room",
            UpdatePolicyRequest(guestControlPolicy = "guest_play_pause"),
            scope,
        )
        assertEquals(HttpMethod.Patch, captured.last.method)
        assertEquals("/api/v2/watch-together/rooms/room-1/policy", captured.last.path)
        assertEquals("jwt-room", captured.last.roomToken)
    }

    @Test
    fun `closeRoom deletes and maps 204 to Unit`() = runTest {
        val (api, captured) = api(status = HttpStatusCode.NoContent, responseBody = "")
        val r = api.closeRoom("room-1", "jwt-room", scope)
        assertEquals(HttpMethod.Delete, captured.last.method)
        assertEquals("/api/v2/watch-together/rooms/room-1", captured.last.path)
        assertEquals("jwt-room", captured.last.roomToken)
        assertEquals(ApiResult.Success(Unit), r)
    }

    @Test
    fun `listSuggestions decodes v2 items envelope`() = runTest {
        val (api, captured) = api(responseBody = suggestionsJson)
        val r = api.listSuggestions("room-1", "jwt-room", scope)
        assertEquals(HttpMethod.Get, captured.last.method)
        assertEquals("/api/v2/watch-together/rooms/room-1/suggestions", captured.last.path)
        assertEquals("jwt-room", captured.last.roomToken)
        val list = assertIs<ApiResult.Success<*>>(r).data as org.siloserver.silo.model.watchtogether.SuggestionsResponse
        assertEquals("sug-5", list.suggestions.single().id)
        assertEquals("7", list.suggestions.single().suggesterUserId)
    }

    @Test
    fun `addSuggestion posts client suggestion_id then reads the list`() = runTest {
        val (api, captured) = api(
            status = HttpStatusCode.Created,
            responseBody = """{"suggestion_id":"generated"}""",
        )
        val r = api.addSuggestion(
            "room-1", "jwt-room",
            AddSuggestionRequest(contentId = "c", contentType = "movie", title = "T"),
            scope,
        )
        assertEquals(2, captured.calls.size)
        assertEquals(HttpMethod.Post, captured.first.method)
        assertEquals("/api/v2/watch-together/rooms/room-1/suggestions", captured.first.path)
        assertEquals("jwt-room", captured.first.roomToken)
        val sent = SiloJson.parseToJsonElement(captured.first.body).jsonObject
        assertEquals(setOf("content_id", "content_type", "title", "suggestion_id"), sent.keys)
        assertEquals(HttpMethod.Get, captured.last.method)
        assertEquals("/api/v2/watch-together/rooms/room-1/suggestions", captured.last.path)
        assertEquals(1, assertIs<ApiResult.Success<*>>(r).let { (it.data as org.siloserver.silo.model.watchtogether.SuggestionsResponse).suggestions.size })
    }

    @Test
    fun `deleteSuggestion deletes then reads the list`() = runTest {
        val (api, captured) = api(status = HttpStatusCode.NoContent, responseBody = "")
        val r = api.deleteSuggestion("room-1", "jwt-room", "sug-5", scope)
        assertEquals(HttpMethod.Delete, captured.first.method)
        assertEquals("/api/v2/watch-together/rooms/room-1/suggestions/sug-5", captured.first.path)
        assertEquals("jwt-room", captured.first.roomToken)
        assertEquals(HttpMethod.Get, captured.last.method)
        assertIs<ApiResult.Success<*>>(r)
    }

    @Test
    fun `room and suggestion identifiers are encoded as path segments`() = runTest {
        val (api, captured) = api(status = HttpStatusCode.NoContent, responseBody = "")

        api.deleteSuggestion("room/with ?#", "jwt-room", "suggestion/with ?#", scope)

        assertEquals(
            "/api/v2/watch-together/rooms/room%2Fwith%20%3F%23/suggestions/" +
                "suggestion%2Fwith%20%3F%23",
            captured.first.path,
        )
    }

    @Test
    fun `vote posts vote path then reads the list`() = runTest {
        val (api, captured) = api(status = HttpStatusCode.NoContent, responseBody = "")
        api.vote("room-1", "jwt-room", "sug-5", scope)
        assertEquals(HttpMethod.Post, captured.first.method)
        assertEquals("/api/v2/watch-together/rooms/room-1/suggestions/sug-5/vote", captured.first.path)
        assertEquals("jwt-room", captured.first.roomToken)
        assertEquals(HttpMethod.Get, captured.last.method)
    }

    @Test
    fun `unvote deletes vote path then reads the list`() = runTest {
        val (api, captured) = api(status = HttpStatusCode.NoContent, responseBody = "")
        api.unvote("room-1", "jwt-room", "sug-5", scope)
        assertEquals(HttpMethod.Delete, captured.first.method)
        assertEquals("/api/v2/watch-together/rooms/room-1/suggestions/sug-5/vote", captured.first.path)
        assertEquals("jwt-room", captured.first.roomToken)
        assertEquals(HttpMethod.Get, captured.last.method)
    }

    @Test
    fun `promote posts suggestion_id with X-Room-Token and decodes room`() = runTest {
        val (api, captured) = api(responseBody = """{"room":$roomJson,"room_access_token":"jwt-room"}""")
        api.promoteSuggestion(
            "room-1",
            "jwt-room",
            PromoteSuggestionRequest(suggestionId = "sug-5"),
            scope,
        )
        assertEquals(HttpMethod.Post, captured.last.method)
        assertEquals("/api/v2/watch-together/rooms/room-1/suggestions/promote", captured.last.path)
        assertEquals("jwt-room", captured.last.roomToken)
        val sent = SiloJson.parseToJsonElement(captured.last.body).jsonObject
        assertEquals("sug-5", sent.getValue("suggestion_id").jsonPrimitive.content)
    }

    @Test
    fun `vote problem surfaces as ApiResult Error without a follow-up read`() = runTest {
        val (api, captured) = api(status = HttpStatusCode.Conflict, responseBody = problem)
        val r = api.vote("room-1", "jwt-room", "sug-5", scope)
        assertIs<ApiResult.Error>(r)
        assertEquals(409, r.code)
        assertEquals("conflict", r.error)
        assertEquals("Already voted", r.message)
        assertEquals(1, captured.calls.size)
    }

    @Test
    fun `join 410 problem surfaces as ApiResult Error`() = runTest {
        val (api, _) = api(
            status = HttpStatusCode.Gone,
            responseBody = """{"type":"https://silo.example/problems/gone","title":"Gone","status":410,"detail":"Room is no longer active"}""",
        )
        val r = api.joinRoom(JoinRoomRequest(code = "DEAD0000"), scope)
        assertIs<ApiResult.Error>(r)
        assertEquals(410, r.code)
        assertEquals("Room is no longer active", r.message)
    }
}
