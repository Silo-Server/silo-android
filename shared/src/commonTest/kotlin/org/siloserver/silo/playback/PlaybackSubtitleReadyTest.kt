package org.siloserver.silo.playback

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackSubtitleReadyTest {
    @Test
    fun exactServerTrackReplacesNoIdentityOrOrdinal() {
        val existing = (0..2).map { index ->
            PlayerSubtitleInfo(
                index = index,
                source = if (index == 1) "embedded" else "external",
                url = if (index == 1) "" else "/stream/s/subtitles/$index.vtt",
                serverTrackId = "file:9:subtitle:$index",
                serverDelivery = if (index == 1) "burn_in_only" else "sidecar",
            )
        }
        val update = decodePlaybackSubtitleReady(
            buildJsonObject {
                put("session_id", "s")
                put("file_id", 9)
                put("subtitle_id", 77)
                putJsonObject("track") {
                    put("track_id", "file:9:subtitle:3")
                    put("combined_index", 3)
                    put("source", "downloaded")
                    put("codec", "ass")
                    put("language", "es")
                    put("label", "Spanish")
                    put("delivery", "sidecar")
                    put("url", "/stream/s/subtitles/3.ass")
                }
            },
        )

        val rows = requireNotNull(applyAuthoritativeSubtitleReadyTrack(existing, update))
        val added = rows.last()
        assertEquals(listOf(0, 1, 2, 3), rows.map(PlayerSubtitleInfo::index))
        assertEquals("file:9:subtitle:3", added.serverTrackId)
        assertEquals("sidecar", added.serverDelivery)
        assertEquals("/stream/s/subtitles/3.ass", added.url)
        assertEquals(77, added.downloadId)
    }

    @Test
    fun aServerGapRequiresAPlanRefreshInsteadOfOrdinalSynthesis() {
        val update = decodePlaybackSubtitleReady(
            buildJsonObject {
                put("subtitle_id", 77)
                putJsonObject("track") {
                    put("track_id", "file:9:subtitle:3")
                    put("combined_index", 3)
                    put("source", "downloaded")
                    put("delivery", "sidecar")
                    put("url", "/stream/s/subtitles/3.vtt")
                }
            },
        )

        assertNull(applyAuthoritativeSubtitleReadyTrack(emptyList(), update))
    }
}
