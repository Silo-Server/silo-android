package org.siloserver.silo.common.settings

import org.siloserver.silo.common.settings.LearnedLimitText.RateUnit
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LearnedLimitTextTest {
    private fun text(kbps: Int, observedAt: String? = "2026-10-17T16:21:40Z", zone: String = "UTC") =
        learnedLimitText(kbps, observedAt, Locale.US, TimeZone.getTimeZone(zone), datePattern = "MMM d")

    @Test
    fun ratesRoundToTheNearestWholeMbps() {
        assertEquals(LearnedLimitText(44, RateUnit.MBPS, "Oct 17"), text(44_100))
        assertEquals(45, text(44_500).rate)
        assertEquals(1, text(500).rate)
        assertEquals(RateUnit.MBPS, text(500).unit)
    }

    @Test
    fun aLimitBelowHalfAMegabitIsShownInKbps() {
        assertEquals(LearnedLimitText(499, RateUnit.KBPS, "Oct 17"), text(499))
    }

    @Test
    fun theDateIsTheObservationInTheDevicesTimeZone() {
        // 02:00 UTC on Oct 17 is still Oct 16 in New York.
        assertEquals("Oct 16", text(44_100, "2026-10-17T02:00:00Z", "America/New_York").date)
        assertEquals("Oct 17", text(44_100, "2026-10-16T22:30:00-05:00").date)
    }

    @Test
    fun anAbsentOrUnreadableDateIsNull() {
        assertNull(text(44_100, observedAt = null).date)
        assertNull(text(44_100, observedAt = "yesterday").date)
    }
}
