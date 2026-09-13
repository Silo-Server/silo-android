package org.siloserver.silo.network

import org.siloserver.silo.model.watchtogether.RoomPhase
import org.siloserver.silo.model.watchtogether.RoomPlaybackState
import org.siloserver.silo.model.watchtogether.TransportAction
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class RoomFrameDecoderTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Test
    fun `decodes snapshot frame`() {
        val raw = """{"type":"snapshot","room":{"room_id":"r","phase":"playing",
            "playback_state":"playing","selection_mode":"host_pick","selection_revision":3,
            "code":"ABCD1234","guest_control_policy":"host_only","is_paused":false,
            "anchor_position_seconds":10.0,"anchor_updated_at":"2026-06-12T09:00:00Z",
            "generation":2,"member_count":2,"host_connected":true,"self_role":"guest",
            "self_can_control_transport":false,"self_can_manage_room":false,
            "self_ignore_wait":false}}"""
        val event = decodeRoomFrame(json, raw)
        assertIs<RoomRealtimeEvent.SnapshotEvent>(event)
        assertEquals("r", event.room.roomId)
        assertEquals(RoomPhase.Playing, event.room.phase)
        assertEquals(3L, event.room.selectionRevision)
    }

    @Test
    fun `decodes transport_command frame`() {
        val raw = """{"type":"transport_command","command":{"command_id":"cmd-1",
            "session_id":"sess-1","selection_revision":3,"action":"seek",
            "position_seconds":42.0,"execute_at":"2026-06-12T09:30:00.123456789Z",
            "issued_at":"2026-06-12T09:29:59Z","playback_state":"waiting"}}"""
        val event = decodeRoomFrame(json, raw)
        assertIs<RoomRealtimeEvent.TransportCommandEvent>(event)
        assertEquals("cmd-1", event.command.commandId)
        assertEquals(TransportAction.Seek, event.command.action)
        assertEquals(RoomPlaybackState.Waiting, event.command.playbackState)
    }

    @Test
    fun `decodes suggestions_update frame`() {
        val raw = """{"type":"suggestions_update","suggestions":[{"id":"s1","room_id":"r",
            "suggester_user_id":"1","suggester_profile_id":"p","content_id":"c","content_type":"movie",
            "title":"T","vote_count":2,"voted_by_me":false,"created_at":"2026-06-12T08:00:00Z"}]}"""
        val event = decodeRoomFrame(json, raw)
        assertIs<RoomRealtimeEvent.SuggestionsEvent>(event)
        assertEquals(1, event.suggestions.size)
        assertEquals("s1", event.suggestions.first().id)
    }

    @Test
    fun `decodes room_closed frame to Closed with reason`() {
        val event = decodeRoomFrame(json, """{"type":"room_closed","reason":"host_left"}""")
        assertIs<RoomRealtimeEvent.Closed>(event)
        assertEquals("host_left", event.reason)
    }

    @Test
    fun `decodes pong frame`() {
        val raw = """{"type":"pong","client_sent_at":"2026-06-12T09:30:00.000000001Z",
            "server_received_at":"2026-06-12T09:30:00.050000000Z",
            "server_sent_at":"2026-06-12T09:30:00.060000000Z"}"""
        val event = decodeRoomFrame(json, raw)
        assertIs<RoomRealtimeEvent.Pong>(event)
        assertEquals("2026-06-12T09:30:00.000000001Z", event.clientSentAt)
        assertEquals("2026-06-12T09:30:00.050000000Z", event.serverReceivedAt)
        assertEquals("2026-06-12T09:30:00.060000000Z", event.serverSentAt)
    }

    @Test
    fun `decodes error frame`() {
        val event = decodeRoomFrame(json, """{"type":"error","code":"gone","message":"Room is no longer active"}""")
        assertIs<RoomRealtimeEvent.Error>(event)
        assertEquals("gone", event.code)
        assertEquals("Room is no longer active", event.message)
    }

    @Test
    fun `unknown type returns null`() {
        assertNull(decodeRoomFrame(json, """{"type":"future_frame","data":{}}"""))
    }

    @Test
    fun `malformed json returns null`() {
        assertNull(decodeRoomFrame(json, "not json{"))
    }

    @Test
    fun `snapshot with malformed room payload returns null`() {
        // room present but missing required room_id → decode fails → null, no throw.
        assertNull(decodeRoomFrame(json, """{"type":"snapshot","room":{"phase":"lobby"}}"""))
    }

    @Test
    fun `transport_command missing command returns null`() {
        assertNull(decodeRoomFrame(json, """{"type":"transport_command"}"""))
    }
}
