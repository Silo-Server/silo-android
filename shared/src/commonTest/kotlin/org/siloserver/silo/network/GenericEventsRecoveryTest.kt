package org.siloserver.silo.network

import io.ktor.http.fromHttpToGmtDate
import org.siloserver.silo.network.apiv2.GenericEventsAttempt
import kotlin.test.*

class GenericEventsRecoveryTest {
    @Test fun failedAndShortAttemptsAdvanceToCappedFloor() {
        val recovery = GenericEventsBackoff { 0.0 }
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 30_000L, 30_000L),
            List(5) { recovery.disconnected(GenericEventsClose(acceptedForMs = 29_999)) })
    }

    @Test fun healthyIncludingLifetimeRotationResetsAndNextFailureStartsAtFiveSeconds() {
        for (duration in listOf(30_000L, 300_000L)) {
            val recovery = GenericEventsBackoff { 0.0 }
            repeat(4) { recovery.disconnected() }
            assertEquals(1_000, recovery.disconnected(GenericEventsClose(acceptedForMs = duration)))
            assertEquals(5_000, recovery.disconnected())
            assertEquals(10_000, recovery.disconnected())
        }
    }

    @Test fun jitterIsNonnegativeAndAtMostHalfOfTheLargerFloor() {
        for (sample in listOf(0.0, 0.25, 0.5, 1.0)) {
            val recovery = GenericEventsBackoff { sample }
            for (floor in listOf(5_000L, 10_000L, 20_000L, 30_000L)) {
                assertEquals(floor + (floor * 0.5 * sample).toLong(), recovery.disconnected())
            }
            assertEquals(60_000 + (30_000 * sample).toLong(), recovery.disconnected(GenericEventsClose(retryAfterMs = 60_000)))
            assertEquals(1_000 + (500 * sample).toLong(), recovery.disconnected(GenericEventsClose(acceptedForMs = 30_000)))
        }
    }

    @Test fun retryAfterCannotLowerFloorAndDoesNotOverflow() {
        val recovery = GenericEventsBackoff { 1.0 }
        assertEquals(7_500, recovery.disconnected(GenericEventsClose(retryAfterMs = 1)))
        assertEquals(Long.MAX_VALUE, recovery.disconnected(GenericEventsClose(retryAfterMs = Long.MAX_VALUE)))
    }

    @Test fun accessChangeIsImmediateWithoutResettingTheFailureLadder() {
        val recovery = GenericEventsBackoff { 0.0 }
        assertEquals(5_000, recovery.disconnected())
        assertEquals(0, recovery.disconnected(GenericEventsClose(300_000, 60_000, accessChanged = true)))
        assertEquals(10_000, recovery.disconnected())
    }

    @Test fun retryAfterReadsDeltaSecondsAndHttpDate() {
        val date = "Wed, 21 Oct 2015 07:28:00 GMT"
        val now = date.fromHttpToGmtDate().timestamp
        assertEquals(45_000, genericEventsRetryAfter(" 45 ", now))
        assertEquals(60_000, genericEventsRetryAfter(date, now - 60_000))
        assertEquals(0, genericEventsRetryAfter(date, now + 60_000))
        for (invalid in listOf(null, "", "junk", "-1", "1.5", "999999999999999999999999")) {
            assertNull(genericEventsRetryAfter(invalid, now))
        }
    }

    @Test fun firstAckStartsHealthClockAndLaterAcksDoNotMoveIt() {
        var now = 0L
        val attempt = GenericEventsAttempt { now }
        now = 120_000
        attempt.frame("""{"type":"hello"}""")
        attempt.frame("""{"type":"snapshot","channel":"notifications","data":[]}""")
        assertNull(attempt.closed().acceptedForMs, "an open socket and useful data are not an ACK")
        attempt.frame("""{"type":"subscribed"}""")
        now += 29_999
        attempt.frame("""{"type":"subscribed"}""")
        assertEquals(29_999, attempt.closed().acceptedForMs)
        now++
        attempt.ended()
        now += 10_000
        assertEquals(30_000, attempt.closed().acceptedForMs, "cleanup does not count as uptime")
    }
}
