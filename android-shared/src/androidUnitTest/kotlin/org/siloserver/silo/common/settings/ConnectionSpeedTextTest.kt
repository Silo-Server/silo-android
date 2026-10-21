package org.siloserver.silo.common.settings

import org.siloserver.silo.common.settings.ConnectionSpeedText.Age
import org.siloserver.silo.common.settings.ConnectionSpeedText.AgeUnit
import org.siloserver.silo.common.settings.ConnectionSpeedText.Transport
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/** "48 Mbps · measured 3 minutes ago on Wi-Fi", from the buffering-quality spec's Settings copy. */
class ConnectionSpeedTextTest {
    private val now = 1_760_000_000_000L
    private fun minutesAgo(minutes: Long) = now - minutes * 60_000

    @Test
    fun theSpecsExample() {
        assertEquals(48.0 to RateUnit.MBPS, connectionRate(48_000))
        assertEquals(
            ConnectionSpeedText(Age(AgeUnit.MINUTES, 3), Transport.WIFI),
            connectionSpeedText(minutesAgo(3), now, "wifi"),
        )
    }

    @Test
    fun ratesAreWrittenInTheLocaleWithoutGrouping() {
        assertEquals("1.4", formatRate(1.4, Locale.US))
        assertEquals("44", formatRate(44.0, Locale.US))
        assertEquals("1000", formatRate(1000.0, Locale.US))
        assertEquals("1,4", formatRate(1.4, Locale.GERMANY))
    }

    @Test
    fun ratesRoundDownToWholeMegabitsFromTenTenthsBelowAndKbpsUnderOne() {
        assertEquals(35.0 to RateUnit.MBPS, connectionRate(35_280))
        assertEquals(35.0 to RateUnit.MBPS, connectionRate(35_999))
        assertEquals(10.0 to RateUnit.MBPS, connectionRate(10_000))
        assertEquals(9.9 to RateUnit.MBPS, connectionRate(9_999))
        assertEquals(1.2 to RateUnit.MBPS, connectionRate(1_267))
        assertEquals(1.0 to RateUnit.MBPS, connectionRate(1_000))
        assertEquals(999.0 to RateUnit.KBPS, connectionRate(999))
        assertEquals(49.0 to RateUnit.KBPS, connectionRate(49))
    }

    @Test
    fun agesRoundDown() {
        assertEquals(Age(AgeUnit.JUST_NOW, 0), connectionSpeedText(now - 59_999, now, null).age)
        assertEquals(Age(AgeUnit.MINUTES, 1), connectionSpeedText(minutesAgo(1), now, null).age)
        assertEquals(Age(AgeUnit.MINUTES, 59), connectionSpeedText(minutesAgo(60) + 1, now, null).age)
        assertEquals(Age(AgeUnit.HOURS, 1), connectionSpeedText(minutesAgo(60), now, null).age)
        assertEquals(Age(AgeUnit.HOURS, 23), connectionSpeedText(minutesAgo(24 * 60 - 1), now, null).age)
        assertEquals(Age(AgeUnit.DAYS, 2), connectionSpeedText(minutesAgo(3 * 24 * 60 - 1), now, null).age)
    }

    @Test
    fun aClockBehindTheMeasurementReadsJustNow() {
        assertEquals(Age(AgeUnit.JUST_NOW, 0), connectionSpeedText(now + 120_000, now, null).age)
    }

    @Test
    fun anUnknownTimeHasNoAge() {
        assertEquals(null, connectionSpeedText(null, now, "wifi").age)
    }

    @Test
    fun transportsFollowTheContract() {
        assertEquals(Transport.ETHERNET, connectionSpeedText(now, now, "ethernet").transport)
        assertEquals(Transport.CELLULAR, connectionSpeedText(now, now, "cellular").transport)
        assertEquals(Transport.OTHER, connectionSpeedText(now, now, "other").transport)
        assertEquals(Transport.OTHER, connectionSpeedText(now, now, null).transport)
    }
}
