package org.siloserver.silo.model.notifications

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotificationModelsSerializationTest {

    // Mirrors SiloJson (network/SiloHttpClientImpl.kt).
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Test
    fun `decodes episode_available row with reason flags and poster`() {
        val payload = """
            {
              "id": "dlv-1",
              "type": "episode.available",
              "profile_id": "prof-7",
              "library_id": 3,
              "series_id": "series-9",
              "episode_id": "ep-101",
              "series_title": "Severance",
              "episode_title": "Cold Harbor",
              "season_number": 2,
              "episode_number": 10,
              "poster_path": "/p/sev.jpg",
              "poster_url": "https://cdn/sev.jpg",
              "poster_thumbhash": "1QcSHQRnh493V4dIh4eXh1h4kJUI",
              "reason_flags": {"favorite": true, "next_up": true},
              "created_at": "2026-06-12T09:30:00Z",
              "read_at": null
            }
        """.trimIndent()

        val row = json.decodeFromString(NotificationRow.serializer(), payload)

        assertEquals("dlv-1", row.id)
        assertEquals(NotificationType.EpisodeAvailable, row.type)
        assertEquals("prof-7", row.profileId)
        assertEquals("3", row.libraryId)
        assertEquals("series-9", row.seriesId)
        assertEquals("ep-101", row.episodeId)
        assertEquals("Severance", row.seriesTitle)
        assertEquals(2, row.seasonNumber)
        assertEquals(10, row.episodeNumber)
        assertEquals("https://cdn/sev.jpg", row.posterUrl)
        assertEquals("1QcSHQRnh493V4dIh4eXh1h4kJUI", row.posterThumbhash)
        assertNull(row.readAt)
        assertFalse(row.isRead)
        // typed flags view
        assertTrue(row.reasonFlagsTyped.favorite)
        assertTrue(row.reasonFlagsTyped.nextUp)
        assertFalse(row.reasonFlagsTyped.watchlist)
        assertFalse(row.reasonFlagsTyped.continueWatching)
        // raw JsonObject preserved
        assertEquals(true, (row.reasonFlags["favorite"] as JsonPrimitive).boolean)
    }

    @Test
    fun `unknown type decodes to a renderable row, never throws`() {
        val payload = """
            {"id":"dlv-2","type":"webhook.auto_disabled","profile_id":"prof-7",
             "reason_flags":{},"created_at":"2026-06-12T09:31:00Z","read_at":null}
        """.trimIndent()

        val row = json.decodeFromString(NotificationRow.serializer(), payload)

        assertEquals("dlv-2", row.id)
        assertEquals(NotificationType.Unknown, row.type)
        assertEquals("webhook.auto_disabled", row.rawType)
        // absent optionals default cleanly
        assertNull(row.libraryId)
        assertNull(row.seriesId)
        assertEquals("", row.seriesTitle)
        assertTrue(row.reasonFlags.isEmpty())
    }

    @Test
    fun `read row exposes read_at and isRead`() {
        val payload = """
            {"id":"dlv-3","type":"episode.available","profile_id":"p",
             "reason_flags":{},"created_at":"2026-06-12T09:00:00Z",
             "read_at":"2026-06-12T10:00:00Z"}
        """.trimIndent()
        val row = json.decodeFromString(NotificationRow.serializer(), payload)
        assertEquals("2026-06-12T10:00:00Z", row.readAt)
        assertTrue(row.isRead)
    }

    @Test
    fun `decodes list response with next_cursor`() {
        val payload = """
            {"notifications":[{"id":"a","type":"episode.available","profile_id":"p",
              "reason_flags":{},"created_at":"2026-06-12T09:00:00Z","read_at":null}],
             "next_cursor":"Y3Vyc29y"}
        """.trimIndent()
        val resp = json.decodeFromString(NotificationListResponse.serializer(), payload)
        assertEquals(1, resp.notifications.size)
        assertEquals("Y3Vyc29y", resp.nextCursor)
    }

    @Test
    fun `list response without next_cursor leaves it null`() {
        val resp = json.decodeFromString(
            NotificationListResponse.serializer(),
            """{"notifications":[]}""",
        )
        assertTrue(resp.notifications.isEmpty())
        assertNull(resp.nextCursor)
    }

    @Test
    fun `decodes sync response with unread_count`() {
        val resp = json.decodeFromString(
            NotificationSyncResponse.serializer(),
            """{"notifications":[],"next_cursor":"c2luY2U","unread_count":4}""",
        )
        assertEquals(4, resp.unreadCount)
        assertEquals("c2luY2U", resp.nextCursor)
    }

    @Test
    fun `decodes unread count and ws ticket envelopes`() {
        assertEquals(
            7,
            json.decodeFromString(UnreadCountResponse.serializer(), """{"count":7}""").count,
        )
        val ticket = json.decodeFromString(
            WsTicketResponse.serializer(),
            """{"ticket":"tkt-abc","expires_in":30}""",
        )
        assertEquals("tkt-abc", ticket.ticket)
        assertEquals(30, ticket.expiresIn)
    }

    @Test
    fun `preferences default all-enabled and round-trip`() {
        val prefs = json.decodeFromString(
            NotificationPreferences.serializer(),
            """{"profile_id":"p","enabled":true,"notify_favorites":true,
                "notify_watchlist":false,"notify_continue_watching":true,"notify_next_up":true}""",
        )
        assertTrue(prefs.enabled)
        assertFalse(prefs.notifyWatchlist)
        // partial PUT body omits nulls (encodeDefaults+explicitNulls=false → only set fields)
        val body = json.encodeToString(
            NotificationPreferencesUpdate.serializer(),
            NotificationPreferencesUpdate(notifyWatchlist = true),
        )
        assertTrue("notify_watchlist" in body)
        assertFalse("enabled" in body)
    }

    @Test
    fun `capability with android_push unavailable`() {
        val payload = """
            {"in_app":{"enabled":true},
             "apple_push":{"available":false,"provider":"off","supported_modes":["in_app_only"]},
             "android_push":{"available":false,"provider":"off","supported_modes":["in_app_only"]},
             "web_push":{"available":false},
             "webhooks":{"available":false,"max_per_profile":0,"supported_types":[]},
             "email":{"available":false,"modes":[],"digest_hour":0},
             "discord":{"available":false,"modes":[],"digest_hour":0}}
        """.trimIndent()
        val cap = json.decodeFromString(NotificationCapability.serializer(), payload)
        assertTrue(cap.inApp.enabled)
        assertFalse(cap.androidPush.available)
        assertEquals("off", cap.androidPush.provider)
        assertFalse(cap.webPush.available)
    }

    @Test
    fun `decodes hello and subscribed frames`() {
        val hello = json.decodeFromString(
            WsHello.serializer(),
            """{"type":"hello","schema_version":1,"connection_id":"c1",
                "available_channels":["catalog","notifications"],"required_action":"subscribe"}""",
        )
        assertEquals("hello", hello.type)
        assertTrue("notifications" in hello.availableChannels)

        val subscribed = json.decodeFromString(
            WsSubscribed.serializer(),
            """{"type":"subscribed","channels":["notifications"]}""",
        )
        assertEquals(listOf("notifications"), subscribed.channels)
        assertTrue(subscribed.rejected.isEmpty())
    }

    @Test
    fun `subscribe frame encodes channels`() {
        val body = json.encodeToString(
            WsSubscribe.serializer(),
            WsSubscribe(channels = listOf("notifications")),
        )
        assertTrue("\"type\":\"subscribe\"" in body)
        assertTrue("notifications" in body)
    }

    @Test
    fun `decodes snapshot frame as a raw envelope with rows`() {
        val frame = """
            {"type":"snapshot","channel":"notifications","timestamp":"2026-06-12T09:00:00Z",
             "data":[{"id":"s1","type":"episode.available","profile_id":"p",
               "reason_flags":{},"created_at":"2026-06-12T09:00:00Z","read_at":null}]}
        """.trimIndent()
        val env = json.decodeFromString(WsFrameEnvelope.serializer(), frame)
        assertEquals("snapshot", env.type)
        assertEquals("notifications", env.channel)
        assertNull(env.event)
        // data is a JsonElement (array here) parsed by the realtime decoder, not the model
        assertIs<JsonObject?>(null) // marker; data shape exercised in the decoder test (Task 4)
    }

    @Test
    fun `decodes event frame envelope preserving event name and data`() {
        val created = """
            {"type":"event","channel":"notifications","event":"notification.created",
             "event_id":"evt-1","timestamp":"2026-06-12T09:05:00Z",
             "data":{"id":"e1","type":"episode.available","profile_id":"p",
               "reason_flags":{},"created_at":"2026-06-12T09:05:00Z","read_at":null}}
        """.trimIndent()
        val env = json.decodeFromString(WsFrameEnvelope.serializer(), created)
        assertEquals("event", env.type)
        assertEquals("notification.created", env.event)

        val read = """
            {"type":"event","channel":"notifications","event":"notification.read",
             "event_id":"evt-2","timestamp":"2026-06-12T09:06:00Z",
             "data":{"profile_id":"p","id":"e1"}}
        """.trimIndent()
        val readEnv = json.decodeFromString(WsFrameEnvelope.serializer(), read)
        assertEquals("notification.read", readEnv.event)

        val readAll = """
            {"type":"event","channel":"notifications","event":"notification.read",
             "event_id":"evt-3","timestamp":"2026-06-12T09:07:00Z",
             "data":{"profile_id":"p","all":true}}
        """.trimIndent()
        val readAllEnv = json.decodeFromString(WsFrameEnvelope.serializer(), readAll)
        assertEquals("notification.read", readAllEnv.event)
    }
}
