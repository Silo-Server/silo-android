package org.siloserver.silo.playback

import kotlinx.serialization.json.JsonObject
import org.siloserver.silo.model.playback.PlaybackSubtitleInventoryItemV3
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.subtitles.SubtitleSyncJob
import org.siloserver.silo.model.subtitles.SubtitleTiming
import org.siloserver.silo.network.SiloJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackSubtitleSyncEventsTest {
    private val sidecarKey = "external-" + "a".repeat(64)

    private fun payload(json: String) = SiloJson.parseToJsonElement(json) as JsonObject

    @Test
    fun decodesSyncProgressAndOutcome() {
        val running = decodePlaybackSubtitleSyncUpdated(
            payload(
                """{"session_id":"s","file_id":1,"sync_key":"$sidecarKey","timing":{"offset_ms":0,"scale":1},
                    "job":{"id":"2","status":"running","trigger":"manual","phase":"analyzing","progress":0.3,
                           "confidence":null,"created_at":"2026-10-04T02:37:35.235Z","finished_at":null}}""",
            ),
        )
        assertNotNull(running)
        assertEquals("s", running.sessionId)
        assertEquals(1, running.mediaFileId)
        assertEquals(sidecarKey, running.syncKey)
        assertNull(running.subtitleId)
        assertEquals(0.3, running.job.progress)
        assertEquals(SubtitleSyncJob.PHASE_ANALYZING, running.job.phase)

        val synced = decodePlaybackSubtitleSyncUpdated(
            payload(
                """{"session_id":"s","file_id":1,"sync_key":"stored-9","subtitle_id":9,"timing":{"offset_ms":-3010,"scale":1},
                    "job":{"id":"3","status":"synced","trigger":"auto","confidence":1,"result":{"offset_ms":-3010,"scale":1},
                           "created_at":"2026-10-04T02:37:35.235Z","finished_at":"2026-10-04T02:37:35.351Z"}}""",
            ),
        )
        assertNotNull(synced)
        assertEquals(9, synced.subtitleId)
        assertEquals(SubtitleTiming(-3010, 1.0), synced.timing)
        assertEquals(SubtitleTiming(-3010, 1.0), synced.job.result)
    }

    @Test
    fun dropsAnUnreadableSyncUpdate() {
        assertNull(decodePlaybackSubtitleSyncUpdated(payload("""{"session_id":"s","file_id":1,"timing":{},"job":{"status":"running"}}""")))
        assertNull(decodePlaybackSubtitleSyncUpdated(payload("""{"session_id":"s","file_id":1,"sync_key":"stored-9","job":{"status":"running"}}""")))
        assertNull(decodePlaybackSubtitleSyncUpdated(payload("""{"sync_key":"stored-9","timing":{},"job":{"progress":"x"}}""")))
    }

    @Test
    fun aSidecarTimingChangeMatchesTheMountedTrackBySyncKey() {
        val update = decodePlaybackSubtitleTimingChanged(
            payload("""{"session_id":"s","file_id":1,"sync_key":"$sidecarKey"}"""),
        )
        assertEquals(sidecarKey, update.syncKey)
        assertNull(update.subtitleId)
        val sidecar = PlayerSubtitleInfo(index = 0, source = "external", url = "/stream/s/subtitles/0.vtt", syncKey = sidecarKey)
        assertTrue(update.affects(listOf(sidecar)))
        assertFalse(update.affects(listOf(sidecar.copy(syncKey = "external-" + "b".repeat(64)))))
        assertFalse(update.affects(emptyList()))
    }

    @Test
    fun aStoredKeyFindsARowFromAServerThatPublishesNoKey() {
        val row = PlayerSubtitleInfo(index = 3, source = "downloaded", url = "/stream/s/subtitles/3.vtt?downloaded_subtitle_id=9")
        assertTrue(listOf(row).includesSyncKey("stored-9"))
        assertFalse(listOf(row).includesSyncKey("stored-90"))
        // A row with a key is matched by its key only.
        assertFalse(listOf(row.copy(syncKey = "stored-10")).includesSyncKey("stored-9"))
    }

    @Test
    fun subtitleReadyCarriesTheInventorySyncKey() {
        val existing = listOf(PlayerSubtitleInfo(index = 0, source = "external", url = "/0.vtt", serverTrackId = "file:1:subtitle:0", syncKey = sidecarKey))
        val track = SiloJson.decodeFromString(
            PlaybackSubtitleInventoryItemV3.serializer(),
            """{"track_id":"file:1:subtitle:1","combined_index":1,"source":"downloaded","codec":"srt","language":"en",
               "delivery":"sidecar","url":"/stream/s/subtitles/1.vtt?downloaded_subtitle_id=9","sync_key":"stored-9"}""",
        )
        val rows = applyAuthoritativeSubtitleReadyTrack(existing, PlaybackSubtitleReady("s", 1, 9, track))
        assertNotNull(rows)
        assertEquals(listOf(sidecarKey, "stored-9"), rows.map { it.syncKey })
        assertEquals("stored-9", rows.syncKeyFor(SubtitleIdentity.ServerSidecar(1)))
        assertNull(rows.syncKeyFor(SubtitleIdentity.Off))
        // The selected track is the active one only while it is mounted; a
        // legacy session mounts every row.
        assertEquals("stored-9", rows.activeSyncKey(SubtitleIdentity.ServerSidecar(1), mounted = rows))
        assertNull(rows.activeSyncKey(SubtitleIdentity.ServerSidecar(1), mounted = rows.take(1)))
    }
}
