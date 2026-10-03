package org.siloserver.silo.playback

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackSubtitleTimingChangedTest {
    private fun sidecar(url: String, downloadId: Int? = null) =
        PlayerSubtitleInfo(index = 3, source = "downloaded", url = url, downloadId = downloadId)

    @Test
    fun decodesTheServerPayload() {
        val update = decodePlaybackSubtitleTimingChanged(
            buildJsonObject {
                put("session_id", "s")
                put("file_id", 42)
                put("subtitle_id", 9)
            },
        )
        assertEquals(PlaybackSubtitleTimingChanged("s", 42, 9), update)
    }

    @Test
    fun readsTheStoredIdPinnedOnAV3SidecarUrl() {
        assertEquals(
            9,
            sidecar("/stream/s/subtitles/3.vtt?file_id=42&downloaded_subtitle_id=9").storedSubtitleId(),
        )
        assertEquals(
            9,
            sidecar("/stream/s/subtitles/3.vtt?downloaded_subtitle_id=9&file_id=42").storedSubtitleId(),
        )
        assertEquals(17, sidecar("/stream/s/subtitles/3.vtt", downloadId = 17).storedSubtitleId())
        assertNull(sidecar("/stream/s/subtitles/3.vtt?file_id=42").storedSubtitleId())
        assertEquals(90, sidecar("/stream/s/subtitles/3.vtt?downloaded_subtitle_id=90").storedSubtitleId())
    }

    @Test
    fun affectsOnlyTheMountedStoredSubtitle() {
        val update = PlaybackSubtitleTimingChanged("s", 42, 9)
        assertTrue(update.affects(listOf(sidecar("/stream/s/subtitles/3.vtt?file_id=42&downloaded_subtitle_id=9"))))
        assertFalse(update.affects(listOf(sidecar("/stream/s/subtitles/3.vtt?file_id=42&downloaded_subtitle_id=90"))))
        assertFalse(update.affects(emptyList()))
        assertFalse(update.copy(subtitleId = null).affects(listOf(sidecar("/x?downloaded_subtitle_id=9"))))
    }
}
