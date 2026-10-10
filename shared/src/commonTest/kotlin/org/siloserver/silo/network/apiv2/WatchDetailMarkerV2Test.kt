package org.siloserver.silo.network.apiv2

import kotlinx.serialization.json.jsonObject
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.model.catalog.TimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchDetailMarkerV2Test {
    @Test fun selectedFileRangesSurviveWatchProjection() {
        val body = SiloJson.parseToJsonElement("""{
            "content_id":"m1","type":"movie","title":"Film",
            "recap":{"start_seconds":10,"end_seconds":20},
            "versions":[{"file_id":"1","duration_seconds":100,"recap":{"start_seconds":10,"end_seconds":20}},
                {"file_id":"2","duration_seconds":100,"intro":{"start_seconds":1,"end_seconds":9},"recap":{"start_seconds":30,"end_seconds":40},"credits":{"start_seconds":50,"end_seconds":60},"preview":{"start_seconds":70,"end_seconds":80}}]
        }""").jsonObject
        val detail = decodeWatchDetail(body, "m1")
        val selected = detail.versions.last()
        assertEquals(TimeRange(10.0, 20.0), detail.recap)
        assertEquals(TimeRange(1.0, 9.0), selected.intro)
        assertEquals(TimeRange(30.0, 40.0), selected.recap)
        assertEquals(TimeRange(50.0, 60.0), selected.credits)
        assertEquals(TimeRange(70.0, 80.0), selected.preview)
    }

    @Test fun selectedFileClearDoesNotAdoptOtherVersionRange() {
        val body = SiloJson.parseToJsonElement("""{"content_id":"m1","type":"movie","title":"Film",
            "recap":{"start_seconds":10,"end_seconds":20},
            "versions":[{"file_id":"2","duration_seconds":100,"marker_segments":[]}]}""").jsonObject
        val selected = decodeWatchDetail(body, "m1").versions.single()
        assertNull(selected.intro)
        assertNull(selected.recap)
        assertNull(selected.credits)
        assertNull(selected.preview)
    }
}
