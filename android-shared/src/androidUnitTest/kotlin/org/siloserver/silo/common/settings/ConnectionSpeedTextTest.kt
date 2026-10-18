package org.siloserver.silo.common.settings

import org.siloserver.silo.common.settings.ConnectionSpeedText.Age
import org.siloserver.silo.common.settings.ConnectionSpeedText.AgeUnit
import org.siloserver.silo.common.settings.ConnectionSpeedText.Transport
import org.siloserver.silo.common.settings.LearnedLimitText.RateUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/** "48 Mbps · measured 3 minutes ago on Wi-Fi", from the buffering-quality spec's Settings copy. */
class ConnectionSpeedTextTest {
    private val now = 1_760_000_000_000L
    private fun minutesAgo(minutes: Long) = now - minutes * 60_000

    @Test
    fun theSpecsExample() {
        assertEquals(
            ConnectionSpeedText(48, RateUnit.MBPS, Age(AgeUnit.MINUTES, 3), Transport.WIFI),
            connectionSpeedText(48_000, minutesAgo(3), now, "wifi"),
        )
    }

    @Test
    fun ratesRoundToWholeMegabitsLikeTheLearnedLimit() {
        assertEquals(35 to RateUnit.MBPS, connectionRate(35_280))
        assertEquals(36 to RateUnit.MBPS, connectionRate(35_500))
        assertEquals(1 to RateUnit.MBPS, connectionRate(500))
        assertEquals(400 to RateUnit.KBPS, connectionRate(400))
    }

    @Test
    fun agesRoundDown() {
        assertEquals(Age(AgeUnit.JUST_NOW, 0), connectionSpeedText(1, now - 59_999, now, null).age)
        assertEquals(Age(AgeUnit.MINUTES, 1), connectionSpeedText(1, minutesAgo(1), now, null).age)
        assertEquals(Age(AgeUnit.MINUTES, 59), connectionSpeedText(1, minutesAgo(60) + 1, now, null).age)
        assertEquals(Age(AgeUnit.HOURS, 1), connectionSpeedText(1, minutesAgo(60), now, null).age)
        assertEquals(Age(AgeUnit.HOURS, 23), connectionSpeedText(1, minutesAgo(24 * 60 - 1), now, null).age)
        assertEquals(Age(AgeUnit.DAYS, 2), connectionSpeedText(1, minutesAgo(3 * 24 * 60 - 1), now, null).age)
    }

    @Test
    fun aClockBehindTheMeasurementReadsJustNow() {
        assertEquals(Age(AgeUnit.JUST_NOW, 0), connectionSpeedText(1, now + 120_000, now, null).age)
    }

    @Test
    fun anUnknownTimeHasNoAge() {
        assertEquals(null, connectionSpeedText(48_000, null, now, "wifi").age)
    }

    @Test
    fun transportsFollowTheContract() {
        assertEquals(Transport.ETHERNET, connectionSpeedText(1, now, now, "ethernet").transport)
        assertEquals(Transport.CELLULAR, connectionSpeedText(1, now, now, "cellular").transport)
        assertEquals(Transport.OTHER, connectionSpeedText(1, now, now, "other").transport)
        assertEquals(Transport.OTHER, connectionSpeedText(1, now, now, null).transport)
    }
}
