package org.siloserver.silo.network

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackRealtimeDecodeTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun decodesSeekCommand() {
        val raw = """{"type":"command","command_id":"c1","session_id":"s1","name":"seek","payload":{"position_seconds":42.5}}"""
        val ev = decodePlaybackFrame(json, raw)
        assertTrue(ev is PlaybackRealtimeEvent.Command)
        ev as PlaybackRealtimeEvent.Command
        assertEquals("seek", ev.name)
        assertEquals("c1", ev.commandId)
        assertEquals("s1", ev.sessionId)
    }

    @Test fun decodesTheCommandIssuer() {
        val raw = """{"type":"command","command_id":"c1","session_id":"s1","name":"terminate","issued_by":{"kind":"admin"}}"""
        val ev = decodePlaybackFrame(json, raw) as PlaybackRealtimeEvent.Command
        assertEquals("admin", ev.issuedBy)
    }

    @Test fun aMissingOrMalformedIssuerDecodesAsNone() {
        val base = """{"type":"command","command_id":"c1","session_id":"s1","name":"stop"""
        listOf("", ",\"issued_by\":\"admin\"", ",\"issued_by\":{\"kind\":7}", ",\"issued_by\":{}").forEach { issuer ->
            val ev = decodePlaybackFrame(json, "$base\"$issuer}") as PlaybackRealtimeEvent.Command
            assertNull(ev.issuedBy, issuer)
        }
    }

    @Test fun decodesMarkersEvent() {
        val raw = """{"type":"event","session_id":"s1","name":"markers_updated","payload":{"file_id":7}}"""
        val ev = decodePlaybackFrame(json, raw)
        assertTrue(ev is PlaybackRealtimeEvent.ServerEvent)
        ev as PlaybackRealtimeEvent.ServerEvent
        assertEquals("markers_updated", ev.name)
    }

    @Test fun commandWithNoPayloadDecodesWithEmptyObject() {
        val raw = """{"type":"command","command_id":"c2","session_id":"s1","name":"pause"}"""
        val ev = decodePlaybackFrame(json, raw)
        assertTrue(ev is PlaybackRealtimeEvent.Command)
        ev as PlaybackRealtimeEvent.Command
        assertTrue(ev.payload.isEmpty())
    }

    @Test fun unknownTypeReturnsNull() {
        assertNull(decodePlaybackFrame(json, """{"type":"pong"}"""))
    }

    @Test fun malformedJsonReturnsNull() {
        assertNull(decodePlaybackFrame(json, "not json"))
    }

    @Test fun commandMissingFieldsReturnsNull() {
        assertNull(decodePlaybackFrame(json, """{"type":"command","name":"seek"}"""))
    }

    @Test fun frameMissingSessionIdReturnsNull() {
        assertNull(decodePlaybackFrame(json, """{"type":"command","command_id":"c1","name":"seek"}"""))
    }

    @Test fun numericStringFieldsAreTreatedAsMalformed() {
        // command_id / session_id are strings server-side; a numeric token is not
        // a valid frame and must be dropped, not coerced.
        assertNull(decodePlaybackFrame(json, """{"type":"command","command_id":7,"session_id":"s1","name":"seek"}"""))
        assertNull(decodePlaybackFrame(json, """{"type":"command","command_id":"c1","session_id":9,"name":"seek"}"""))
    }
}
