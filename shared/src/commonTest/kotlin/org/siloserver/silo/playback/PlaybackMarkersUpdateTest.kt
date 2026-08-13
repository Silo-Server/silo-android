package org.siloserver.silo.playback

import org.siloserver.silo.model.catalog.TimeRange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackMarkersUpdateTest {
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
}
