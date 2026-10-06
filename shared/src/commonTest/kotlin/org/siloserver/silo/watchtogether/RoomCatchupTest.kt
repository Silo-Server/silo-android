package org.siloserver.silo.watchtogether

import org.siloserver.silo.model.watchtogether.TransportAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomCatchupTest {
    private fun decide(action: TransportAction, target: Double, local: Double, reachable: Boolean) =
        RoomCatchup.decide(action, target, local, reachable)

    @Test
    fun `explicit seeks always seek`() {
        assertEquals(RoomCatchup.Decision.Seek, decide(TransportAction.Seek, 10.0, 10.0, reachable = false))
    }

    @Test
    fun `drift inside the deadband needs nothing`() {
        assertEquals(RoomCatchup.Decision.None, decide(TransportAction.Play, 10.3, 10.0, reachable = false))
    }

    @Test
    fun `a locally reachable target is a seek`() {
        assertEquals(RoomCatchup.Decision.Seek, decide(TransportAction.Play, 11.5, 10.0, reachable = true))
        assertEquals(RoomCatchup.Decision.Seek, decide(TransportAction.Pause, 11.5, 10.0, reachable = true))
    }

    @Test
    fun `an unreachable pause target is left for the next play`() {
        assertEquals(RoomCatchup.Decision.None, decide(TransportAction.Pause, 15.0, 10.0, reachable = false))
    }

    @Test
    fun `small unreachable drift converges by a bounded rate and large drift seeks`() {
        assertEquals(RoomCatchup.Decision.Rate(1.125), decide(TransportAction.Play, 11.0, 10.0, reachable = false))
        assertEquals(RoomCatchup.Decision.Rate(0.9), decide(TransportAction.Play, 8.5, 10.0, reachable = false))
        assertEquals(RoomCatchup.Decision.Seek, decide(TransportAction.Play, 12.5, 10.0, reachable = false))
        assertEquals(RoomCatchup.MAX_RATE, RoomCatchup.rateFor(10.0))
    }

    @Test
    fun `convergence tracks the advancing room position`() {
        assertEquals(12.0, RoomCatchup.expectedPosition(10.0, executeAtMs = 1_000, nowMs = 3_000))
        assertTrue(RoomCatchup.converged(10.0, 1_000, localSeconds = 11.8, nowMs = 3_000))
        assertFalse(RoomCatchup.converged(10.0, 1_000, localSeconds = 11.0, nowMs = 3_000))
    }

    @Test
    fun `one reload at a time with doubling backoff and a capped lead`() {
        val budget = RoomReloadBudget()
        assertTrue(budget.allowed(0))
        assertEquals(100.0, budget.begin(100.0, nowMs = 0, durationSeconds = 5_000.0))
        assertFalse(budget.allowed(1_000))
        budget.noteLoading()
        assertTrue(budget.landed(100.5))
        budget.land(nowMs = 4_000)
        assertFalse(budget.allowed(13_999))
        assertTrue(budget.allowed(14_000))

        // The next reload aims ahead by the last load time, capped at the duration.
        assertEquals(204.0, budget.begin(200.0, nowMs = 14_000, durationSeconds = 5_000.0))
        budget.abandon(nowMs = 15_000)
        assertFalse(budget.allowed(34_999))
        assertTrue(budget.allowed(35_000))
        assertEquals(203.0, budget.begin(203.0 - 4.0 + 4.0, nowMs = 35_000, durationSeconds = 203.0))
    }

    @Test
    fun `an unlanded reload stops blocking after thirty seconds and settling resets backoff`() {
        val budget = RoomReloadBudget()
        budget.begin(10.0, nowMs = 0)
        assertFalse(budget.landed(10.0))
        assertTrue(budget.allowed(30_000))
        budget.abandon(30_000)
        budget.settle()
        assertTrue(budget.allowed(30_000))
    }
}
