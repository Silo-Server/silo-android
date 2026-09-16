package org.siloserver.silo.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationRealtimeDecoderTest {
    @Test fun `cutoff read event invalidates without rounded timestamp comparisons`() {
        val event = decodeRealtimeFrame(SiloJson, """{"type":"event","channel":"notifications","event":"notification.read","data":{"profile_id":"profile","through_created_at":"2026-09-05T12:00:00.123456Z","through_id":"cutoff"}}""")
        assertIs<NotificationRealtimeEvent.Invalidate>(event)
    }


    private val json = SiloJson

    @Test
    fun `decodes snapshot frame into Snapshot with rows`() {
        val frame = """
            {"type":"snapshot","channel":"notifications","timestamp":"2026-06-12T09:00:00Z",
             "data":[
               {"id":"s1","type":"episode.available","profile_id":"p","reason_flags":{},
                "created_at":"2026-06-12T09:00:00Z","read_at":null},
               {"id":"s2","type":"episode.available","profile_id":"p","reason_flags":{},
                "created_at":"2026-06-12T08:59:00Z","read_at":null}]}
        """.trimIndent()
        val event = decodeRealtimeFrame(json, frame)
        val snapshot = assertIs<NotificationRealtimeEvent.Snapshot>(event)
        assertEquals(listOf("s1", "s2"), snapshot.rows.map { it.id })
    }

    @Test
    fun `decodes notification_created event into Created with one row`() {
        val frame = """
            {"type":"event","channel":"notifications","event":"notification.created",
             "event_id":"e1","timestamp":"2026-06-12T09:05:00Z",
             "data":{"id":"c1","type":"episode.available","profile_id":"p",
               "reason_flags":{"favorite":true},
               "created_at":"2026-06-12T09:05:00Z","read_at":null}}
        """.trimIndent()
        val event = decodeRealtimeFrame(json, frame)
        val created = assertIs<NotificationRealtimeEvent.Created>(event)
        assertEquals("c1", created.row.id)
        assertTrue(created.row.reasonFlagsTyped.favorite)
    }

    @Test
    fun `decodes notification_read for a single id into Read`() {
        val frame = """
            {"type":"event","channel":"notifications","event":"notification.read",
             "event_id":"e2","timestamp":"2026-06-12T09:06:00Z",
             "data":{"profile_id":"p","id":"c1"}}
        """.trimIndent()
        val event = decodeRealtimeFrame(json, frame)
        val read = assertIs<NotificationRealtimeEvent.Read>(event)
        assertEquals("c1", read.id)
    }

    @Test
    fun `decodes notification_read all into ReadAll`() {
        val frame = """
            {"type":"event","channel":"notifications","event":"notification.read",
             "event_id":"e3","timestamp":"2026-06-12T09:07:00Z",
             "data":{"profile_id":"p","all":true}}
        """.trimIndent()
        val event = decodeRealtimeFrame(json, frame)
        assertIs<NotificationRealtimeEvent.ReadAll>(event)
    }

    @Test
    fun `hello and subscribed frames decode to null (not surfaced)`() {
        assertNull(decodeRealtimeFrame(json, """{"type":"hello","schema_version":1,
            "connection_id":"c","available_channels":["notifications"],
            "required_action":"subscribe"}"""))
        assertNull(decodeRealtimeFrame(json, """{"type":"subscribed","channels":["notifications"]}"""))
    }

    @Test
    fun `unknown event type decodes to null without throwing`() {
        val frame = """
            {"type":"event","channel":"notifications","event":"notification.archived",
             "event_id":"e9","timestamp":"t","data":{"id":"x"}}
        """.trimIndent()
        assertNull(decodeRealtimeFrame(json, frame))
    }

    @Test
    fun `events on other channels decode to null`() {
        val frame = """
            {"type":"event","channel":"catalog","event":"catalog.updated",
             "event_id":"e","timestamp":"t","data":{}}
        """.trimIndent()
        assertNull(decodeRealtimeFrame(json, frame))
    }

    @Test
    fun `malformed json decodes to null without throwing`() {
        assertNull(decodeRealtimeFrame(json, "not json at all"))
        assertNull(decodeRealtimeFrame(json, "{"))
        assertNull(decodeRealtimeFrame(json, """{"type":"event","channel":"notifications",
            "event":"notification.created","data":"not-an-object"}"""))
    }
}
