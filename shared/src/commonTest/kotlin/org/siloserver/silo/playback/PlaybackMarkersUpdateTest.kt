package org.siloserver.silo.playback

import org.siloserver.silo.model.catalog.FileVersion
import org.siloserver.silo.model.catalog.WatchDetail
import org.siloserver.silo.model.catalog.TimeRange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackMarkersUpdateTest {
    @Test
    fun introSelectionKeepsUndoUntilTheNextOccurrence() {
        val first = org.siloserver.silo.model.catalog.PlaybackMarkerSegment("intro", 5.0, 20.0)
        val second = org.siloserver.silo.model.catalog.PlaybackMarkerSegment("intro", 100.0, 120.0)
        val segments = listOf(first, second)
        assertEquals(first, introMarkerSegment(segments, 20.0))
        assertEquals(second, introMarkerSegment(segments, 100.0))
        assertEquals(first, introMarkerSegment(segments, 10.0))
    }

    private fun payload(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test fun decodesIntroAndCredits() {
        val m = decodeMarkersUpdate(
            payload("""{"file_id":7,"intro":{"start":0.0,"end":30.0},"credits":{"start":1200.0,"end":1260.0}}"""),
        )
        assertEquals(TimeRange(0.0, 30.0), m.intro)
        assertEquals(TimeRange(1200.0, 1260.0), m.credits)
        assertNull(m.recap)
        assertNull(m.preview)
    }

    @Test fun decodesRecapAndPreview() {
        val m = decodeMarkersUpdate(
            payload("""{"file_id":7,"recap":{"start":0.0,"end":45.0},"preview":{"start":1500.0,"end":1530.0}}"""),
        )
        assertNull(m.intro)
        assertNull(m.credits)
        assertEquals(TimeRange(0.0, 45.0), m.recap)
        assertEquals(TimeRange(1500.0, 1530.0), m.preview)
    }

    @Test fun nullMarkerClears() {
        val m = decodeMarkersUpdate(payload("""{"file_id":7,"intro":null,"credits":{"start":10.0,"end":20.0}}"""))
        assertNull(m.intro)
        assertEquals(TimeRange(10.0, 20.0), m.credits)
    }

    @Test fun missingMarkersAreNull() {
        val m = decodeMarkersUpdate(payload("""{"file_id":7}"""))
        assertNull(m.intro)
        assertNull(m.credits)
    }

    @Test fun rangeMissingEndIsDropped() {
        // A half-formed range must not produce a bogus skip target.
        val m = decodeMarkersUpdate(payload("""{"intro":{"start":5.0}}"""))
        assertNull(m.intro)
    }

    @Test fun quotedNumbersAreRejected() {
        val m = decodeMarkersUpdate(payload("""{"credits":{"start":"10","end":"20"}}"""))
        assertNull(m.credits)
    }
    @Test fun preservesOccurrencesAndDoesNotSkipCreditsScene() {
        val update = decodeMarkersUpdate(payload("""{"file_id":"7","marker_segments":[
            {"kind":"intro","start_seconds":10,"end_seconds":30},
            {"kind":"intro","start_seconds":40,"end_seconds":50},
            {"kind":"credits","start_seconds":1200,"end_seconds":1220},
            {"kind":"credits","start_seconds":1240,"end_seconds":1260}
        ]}"""))
        assertEquals(7, update.fileId)
        assertEquals(4, update.markerSegments.size)
        assertEquals(30.0, activeMarkerSegment(update.markerSegments, 20.0)?.endSeconds)
        assertEquals(50.0, activeMarkerSegment(update.markerSegments, 45.0)?.endSeconds)
        assertNull(activeMarkerSegment(update.markerSegments, 1230.0))
        assertEquals(TimeRange(1240.0, 1260.0), terminalCreditsRange(update.markerSegments, 1260.0))
        assertNull(terminalCreditsRange(update.markerSegments, 1300.0))
    }

    @Test fun explicitEmptyCollectionOverridesSingularFallback() {
        val update = decodeMarkersUpdate(payload("""{"intro":{"start":0,"end":30},"marker_segments":[]}"""))
        assertEquals(emptyList(), update.markerSegments)
        assertNull(update.intro)
    }

    @Test fun supportsRecapAndPreviewInLegacyAndV2Payloads() {
        val legacy = decodeMarkersUpdate(payload("""{"recap":{"start":0,"end":10},"preview":{"start":90,"end":100}}"""))
        val v2 = decodeMarkersUpdate(payload("""{"marker_segments":[
            {"kind":"recap","start_seconds":0,"end_seconds":10},
            {"kind":"preview","start_seconds":90,"end_seconds":100}
        ]}"""))
        assertEquals(legacy.markerSegments, v2.markerSegments)
        assertNull(activeMarkerSegment(v2.markerSegments, 10.0))
    }

    @Test fun rejectsUnsafeAndUnsupportedSegments() {
        val update = decodeMarkersUpdate(payload("""{"marker_segments":[
            {"kind":"intro","start_seconds":-1,"end_seconds":10},
            {"kind":"intro","start_seconds":20,"end_seconds":10},
            {"kind":"intro","start_seconds":20,"end_seconds":20},
            {"kind":"intro","start_seconds":"0","end_seconds":10},
            {"kind":"future_kind","start_seconds":0,"end_seconds":10}
        ]}"""))
        assertEquals(emptyList(), update.markerSegments)
    }

    @Test fun versionCollectionIsAuthoritativeAndLegacyWatchFallsBack() {
        val detail = WatchDetail(contentId = "episode:1", type = "episode", title = "Episode", intro = TimeRange(0.0, 30.0))
        assertEquals(1, detail.markersForVersion(FileVersion(fileId = 7)).size)
        assertEquals(emptyList(), detail.markersForVersion(FileVersion(fileId = 7, markerSegments = emptyList())))
    }

    @Test fun decodesV2SingularFallback() {
        val update = decodeMarkersUpdate(payload("""{"file_id":"7","recap":{"start_seconds":0,"end_seconds":10},"credits":{}}"""))
        assertEquals(7, update.fileId)
        assertEquals("recap", update.markerSegments.single().kind)
        assertNull(update.credits)
    }
}
