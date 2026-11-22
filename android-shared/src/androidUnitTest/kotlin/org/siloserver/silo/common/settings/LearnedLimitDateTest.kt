package org.siloserver.silo.common.settings

import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LearnedLimitDateTest {
    private fun date(observedAt: String?, zone: String = "UTC") =
        learnedLimitDate(observedAt, Locale.US, TimeZone.getTimeZone(zone), datePattern = "MMM d")

    @Test
    fun theDateIsTheObservationInTheDevicesTimeZone() {
        assertEquals("Oct 17", date("2026-10-17T16:21:40Z"))
        // 02:00 UTC on Oct 17 is still Oct 16 in New York.
        assertEquals("Oct 16", date("2026-10-17T02:00:00Z", "America/New_York"))
        assertEquals("Oct 17", date("2026-10-16T22:30:00-05:00"))
    }

    @Test
    fun anAbsentOrUnreadableDateIsNull() {
        assertNull(date(observedAt = null))
        assertNull(date(observedAt = "yesterday"))
    }
}
