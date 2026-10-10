package org.siloserver.silo.common.player.video

import org.siloserver.silo.model.catalog.TimeRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MarkerSkipTargetTest {
    @Test fun currentRangeControlsTheSeekAndDeletion() {
        val recap = TimeRange(40.0, 60.0)
        val credits = TimeRange(150.0, 180.0)
        assertNull(markerSkipTarget(39.9, recap, credits))
        assertEquals(MarkerSkipTarget(ManualMarkerKind.Recap, 60.0), markerSkipTarget(40.0, recap, credits))
        assertNull(markerSkipTarget(60.0, recap, credits))
        assertEquals(MarkerSkipTarget(ManualMarkerKind.Credits, 180.0), markerSkipTarget(160.0, recap, credits))
        assertNull(markerSkipTarget(160.0, recap, null))
        assertEquals(185.0, markerSkipTarget(160.0, recap, TimeRange(150.0,185.0))?.endSeconds)
        assertEquals(ManualMarkerKind.Recap, markerSkipTarget(45.0, recap, TimeRange(30.0,70.0))?.kind)
    }
    @Test fun invalidRangesCannotOfferASeek() {
        for (range in listOf(TimeRange(-1.0, 10.0), TimeRange(10.0, 10.0), TimeRange(20.0, 10.0), TimeRange(0.0, Double.POSITIVE_INFINITY))) {
            assertNull(markerSkipTarget(5.0, range, range))
        }
        assertNull(markerSkipTarget(Double.NaN, TimeRange(0.0,10.0), null))
    }
}
