package org.siloserver.silo.model.watchtogether

import org.siloserver.silo.network.WatchTogetherRealtime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchTogetherModelsSerializationTest {

    // Mirrors SiloJson (network/SiloHttpClientImpl.kt).
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    // ---- RoomSnapshot ---------------------------------------------------------

    @Test
    fun `decodes full host snapshot incl per-recipient self fields and invite_path`() {
        val payload = """
            {
              "room_id": "room-1",
              "phase": "playing",
              "playback_state": "waiting",
              "selection_mode": "vote",
              "selection_revision": 7,
              "selected_content_id": "tt-9",
              "selected_file_id": 42,
              "selected_library_id": 3,
              "code": "ABCD1234",
              "guest_control_policy": "guest_play_pause",
              "is_paused": true,
              "anchor_position_seconds": 123.5,
              "anchor_updated_at": "2026-06-12T09:30:00Z",
              "generation": 11,
              "member_count": 2,
              "host_connected": true,
              "self_role": "host",
              "self_can_control_transport": true,
              "self_can_manage_room": true,
              "self_ignore_wait": false,
              "attached_session_id": "sess-1",
              "invite_path": "/watch-together/join?code=ABCD1234"
            }
        """.trimIndent()

        val s = json.decodeFromString(RoomSnapshot.serializer(), payload)

        assertEquals("room-1", s.roomId)
        assertEquals(RoomPhase.Playing, s.phase)
        assertEquals(RoomPlaybackState.Waiting, s.playbackState)
        assertEquals(RoomSelectionMode.Vote, s.selectionMode)
        assertEquals(7L, s.selectionRevision)
        assertEquals("tt-9", s.selectedContentId)
        assertEquals(42, s.selectedFileId)
        assertEquals(3, s.selectedLibraryId)
        assertEquals("ABCD1234", s.code)
        assertEquals(GuestControlPolicy.GuestPlayPause, s.guestControlPolicy)
        assertTrue(s.isPaused)
        assertEquals(123.5, s.anchorPositionSeconds)
        assertEquals("2026-06-12T09:30:00Z", s.anchorUpdatedAt)
        assertEquals(11L, s.generation)
        assertEquals(2, s.memberCount)
        assertTrue(s.hostConnected)
        assertEquals(MemberRole.Host, s.selfRole)
        assertTrue(s.selfCanControlTransport)
        assertTrue(s.selfCanManageRoom)
        assertFalse(s.selfIgnoreWait)
        assertEquals("sess-1", s.attachedSessionId)
        assertEquals("/watch-together/join?code=ABCD1234", s.invitePath)
    }

    @Test
    fun `guest snapshot omits invite_path and selection, unknown enums fall back`() {
        val payload = """
            {
              "room_id": "room-2",
              "phase": "lobby",
              "playback_state": "idle",
              "selection_mode": "future_mode",
              "selection_revision": 0,
              "code": "ZZZZ0000",
              "guest_control_policy": "host_only",
              "is_paused": false,
              "anchor_position_seconds": 0.0,
              "anchor_updated_at": "2026-06-12T09:00:00Z",
              "generation": 1,
              "member_count": 1,
              "host_connected": true,
              "self_role": "guest",
              "self_can_control_transport": false,
              "self_can_manage_room": false,
              "self_ignore_wait": false
            }
        """.trimIndent()

        val s = json.decodeFromString(RoomSnapshot.serializer(), payload)
        assertEquals(RoomPhase.Lobby, s.phase)
        assertEquals(RoomSelectionMode.Unknown, s.selectionMode) // unknown enum tolerated
        assertNull(s.selectedContentId)
        assertNull(s.selectedFileId)
        assertNull(s.invitePath)
        assertEquals(MemberRole.Guest, s.selfRole)
        assertFalse(s.selfCanControlTransport)
    }

    // ---- Suggestion -----------------------------------------------------------

    @Test
    fun `decodes suggestion incl voted_by_me`() {
        val payload = """
            {
              "id": "sug-1",
              "room_id": "room-1",
              "suggester_user_id": "5",
              "suggester_profile_id": "prof-5",
              "content_id": "tt-3",
              "content_type": "movie",
              "title": "Arrival",
              "subtitle": "2016",
              "poster_url": "https://cdn/arr.jpg",
              "note": "great pick",
              "vote_count": 4,
              "voted_by_me": true,
              "created_at": "2026-06-12T08:00:00Z"
            }
        """.trimIndent()

        val sug = json.decodeFromString(Suggestion.serializer(), payload)
        assertEquals("sug-1", sug.id)
        assertEquals("room-1", sug.roomId)
        assertEquals("5", sug.suggesterUserId)
        assertEquals("prof-5", sug.suggesterProfileId)
        assertEquals("movie", sug.contentType)
        assertEquals("Arrival", sug.title)
        assertEquals(4, sug.voteCount)
        assertTrue(sug.votedByMe)
    }

    @Test
    fun `suggestion defaults voted_by_me false and tolerates missing optionals`() {
        val payload = """
            {
              "id": "sug-2", "room_id": "room-1", "suggester_user_id": "1",
              "suggester_profile_id": "p", "content_id": "tt-1", "content_type": "episode",
              "title": "Pilot", "vote_count": 0, "created_at": "2026-06-12T08:00:00Z"
            }
        """.trimIndent()
        val sug = json.decodeFromString(Suggestion.serializer(), payload)
        assertFalse(sug.votedByMe)
        assertEquals("", sug.subtitle)
        assertEquals("", sug.note)
    }

    // ---- TransportCommand (RFC3339Nano execute_at) ----------------------------

    @Test
    fun `decodes transport command with nanosecond execute_at`() {
        val payload = """
            {
              "command_id": "cmd-uuid-1",
              "session_id": "sess-1",
              "selection_revision": 7,
              "action": "seek",
              "position_seconds": 88.25,
              "execute_at": "2026-06-12T09:30:00.123456789Z",
              "issued_at": "2026-06-12T09:29:59.900000000Z",
              "playback_state": "waiting"
            }
        """.trimIndent()

        val c = json.decodeFromString(TransportCommand.serializer(), payload)
        assertEquals("cmd-uuid-1", c.commandId)
        assertEquals("sess-1", c.sessionId)
        assertEquals(7L, c.selectionRevision)
        assertEquals(TransportAction.Seek, c.action)
        assertEquals(88.25, c.positionSeconds)
        assertEquals("2026-06-12T09:30:00.123456789Z", c.executeAt)
        assertEquals("2026-06-12T09:29:59.900000000Z", c.issuedAt)
        assertEquals(RoomPlaybackState.Waiting, c.playbackState)
    }

    @Test
    fun `transport command tolerates omitted session_id`() {
        val payload = """
            {
              "command_id": "cmd-2", "selection_revision": 1, "action": "play",
              "position_seconds": 0.0, "execute_at": "2026-06-12T09:30:00Z",
              "issued_at": "2026-06-12T09:29:59Z", "playback_state": "playing"
            }
        """.trimIndent()
        val c = json.decodeFromString(TransportCommand.serializer(), payload)
        assertEquals("", c.sessionId)
        assertEquals(TransportAction.Play, c.action)
    }

    // ---- Response wrappers ----------------------------------------------------

    @Test
    fun `decodes room response wrapper with room_access_token`() {
        val payload = """{"room":{"room_id":"r","phase":"lobby","playback_state":"idle",
            "selection_mode":"host_pick","selection_revision":0,"code":"AAAA0000",
            "guest_control_policy":"host_only","is_paused":false,"anchor_position_seconds":0.0,
            "anchor_updated_at":"2026-06-12T09:00:00Z","generation":1,"member_count":1,
            "host_connected":true,"self_role":"host","self_can_control_transport":true,
            "self_can_manage_room":true,"self_ignore_wait":false},
            "room_access_token":"jwt-room-token"}"""
        val r = json.decodeFromString(RoomResponse.serializer(), payload)
        assertEquals("r", r.room.roomId)
        assertEquals("jwt-room-token", r.roomAccessToken)
    }

    @Test
    fun `decodes suggestions response wrapper`() {
        val payload = """{"items":[{"id":"s1","room_id":"r","suggester_user_id":"1",
            "suggester_profile_id":"p","content_id":"c","content_type":"movie","title":"T",
            "vote_count":1,"voted_by_me":false,"created_at":"2026-06-12T08:00:00Z"}],
            "page":{"has_more":false}}"""
        val r = json.decodeFromString(SuggestionsResponse.serializer(), payload)
        assertEquals(1, r.suggestions.size)
        assertEquals("s1", r.suggestions.first().id)
    }

    // ---- Request models (encode → correct wire keys, omit nulls) --------------

    @Test
    fun `create request encodes selection_mode`() {
        val out = json.encodeToString(
            CreateRoomRequest.serializer(),
            CreateRoomRequest(selectionMode = "vote"),
        )
        assertEquals(setOf("selection_mode"), json.parseToJsonElement(out).jsonObject.keys)
    }

    @Test
    fun `join request encodes join_token and code`() {
        val out = json.encodeToString(
            JoinRoomRequest.serializer(),
            JoinRoomRequest(code = "ABCD1234"),
        )
        val keys = json.parseToJsonElement(out).jsonObject.keys
        assertTrue("code" in keys)
        assertFalse("join_token" in keys) // null omitted (explicitNulls=false)
    }

    @Test
    fun `selection request encodes content_id and omits null file_id`() {
        val out = json.encodeToString(
            SetSelectionRequest.serializer(),
            SetSelectionRequest(contentId = "tt-1"),
        )
        assertEquals(setOf("content_id"), json.parseToJsonElement(out).jsonObject.keys)
    }

    @Test
    fun `policy request encodes guest_control_policy`() {
        val out = json.encodeToString(
            UpdatePolicyRequest.serializer(),
            UpdatePolicyRequest(guestControlPolicy = "host_only"),
        )
        assertEquals(setOf("guest_control_policy"), json.parseToJsonElement(out).jsonObject.keys)
    }

    @Test
    fun `add suggestion request encodes required fields and omits null optionals`() {
        val out = json.encodeToString(
            AddSuggestionRequest.serializer(),
            AddSuggestionRequest(contentId = "c", contentType = "movie", title = "T"),
        )
        assertEquals(
            setOf("content_id", "content_type", "title"),
            json.parseToJsonElement(out).jsonObject.keys,
        )
    }

    @Test
    fun `promote request encodes suggestion_id`() {
        val out = json.encodeToString(
            PromoteSuggestionRequest.serializer(),
            PromoteSuggestionRequest(suggestionId = "sug-1"),
        )
        assertEquals(setOf("suggestion_id"), json.parseToJsonElement(out).jsonObject.keys)
    }

    // ---- Client→server WS frames ----------------------------------------------

    @Test
    fun `attach_session frame encodes type and session_id`() {
        val out = json.encodeToString(WsAttachSession.serializer(), WsAttachSession(sessionId = "s"))
        val obj = json.parseToJsonElement(out).jsonObject
        assertEquals("attach_session", obj["type"]!!.toString().trim('"'))
        assertEquals("s", obj["session_id"]!!.toString().trim('"'))
    }

    @Test
    fun `transport_request frame omits null position_seconds`() {
        val out = json.encodeToString(
            WsTransportRequest.serializer(),
            WsTransportRequest(action = "pause", isPaused = true),
        )
        val keys = json.parseToJsonElement(out).jsonObject.keys
        assertTrue("type" in keys && "action" in keys && "is_paused" in keys)
        assertFalse("position_seconds" in keys)
    }

    @Test
    fun `ping frame encodes client_sent_at`() {
        val out = json.encodeToString(
            WsPing.serializer(),
            WsPing(clientSentAt = "2026-06-12T09:30:00.123456789Z"),
        )
        val obj = json.parseToJsonElement(out).jsonObject
        assertEquals("ping", obj["type"]!!.toString().trim('"'))
        assertEquals("2026-06-12T09:30:00.123456789Z", obj["client_sent_at"]!!.toString().trim('"'))
    }

    @Test
    fun `state_report ready buffering frames carry session position and paused`() {
        val sr = json.encodeToString(
            WsStateReport.serializer(),
            WsStateReport(sessionId = "s", positionSeconds = 12.0, isPaused = false),
        )
        val obj = json.parseToJsonElement(sr).jsonObject
        assertEquals("state_report", obj["type"]!!.toString().trim('"'))
        assertEquals(setOf("type", "session_id", "position_seconds", "is_paused"), obj.keys)
    }

    // ---- Server→client frame envelope + payloads ------------------------------

    @Test
    fun `decodes pong frame fields`() {
        val payload = """{"type":"pong","client_sent_at":"2026-06-12T09:30:00.000000001Z",
            "server_received_at":"2026-06-12T09:30:00.050000000Z",
            "server_sent_at":"2026-06-12T09:30:00.060000000Z"}"""
        val p = json.decodeFromString(WsPong.serializer(), payload)
        assertEquals("2026-06-12T09:30:00.000000001Z", p.clientSentAt)
        assertEquals("2026-06-12T09:30:00.050000000Z", p.serverReceivedAt)
        assertEquals("2026-06-12T09:30:00.060000000Z", p.serverSentAt)
    }

    @Test
    fun `decodes room_closed reason and error frame`() {
        val closed = json.decodeFromString(
            WsRoomClosed.serializer(),
            """{"type":"room_closed","reason":"host_left"}""",
        )
        assertEquals("host_left", closed.reason)
        val err = json.decodeFromString(
            WsError.serializer(),
            """{"type":"error","code":"gone","message":"Room is no longer active"}""",
        )
        assertEquals("gone", err.code)
        assertEquals("Room is no longer active", err.message)
    }

    @Test
    fun `realtime channel constants match server`() {
        // Guard against drift in the WS type discriminators.
        assertEquals("snapshot", WatchTogetherRealtime.TypeSnapshot)
        assertEquals("transport_command", WatchTogetherRealtime.TypeTransportCommand)
        assertEquals("suggestions_update", WatchTogetherRealtime.TypeSuggestionsUpdate)
        assertEquals("room_closed", WatchTogetherRealtime.TypeRoomClosed)
        assertEquals("pong", WatchTogetherRealtime.TypePong)
        assertEquals("error", WatchTogetherRealtime.TypeError)
    }
}
