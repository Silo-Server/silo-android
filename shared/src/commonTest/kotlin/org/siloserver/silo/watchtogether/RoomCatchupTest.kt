package org.siloserver.silo.watchtogether

import org.siloserver.silo.model.watchtogether.TransportAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomCatchupTest {
    private fun decide(
        action: TransportAction,
        drift: Double,
        memberPlaying: Boolean = true,
        commandPlays: Boolean = action == TransportAction.Play,
        reachable: Boolean = false,
        intendsToPlay: Boolean = memberPlaying,
        rateCapable: Boolean = true,
    ) = RoomCatchup.decideCommand(action, drift, memberPlaying, commandPlays, reachable, intendsToPlay, rateCapable)

    // ---- room position ---------------------------------------------------------------

    @Test
    fun `a playing reference advances at 1x from execute_at and a late execution projects forward`() {
        val ref = RoomReference(positionSeconds = 100.0, executeAtServerMs = 10_000, playing = true)
        assertEquals(100.0, ref.positionAt(9_000), "never before execute_at")
        assertEquals(100.0, ref.positionAt(10_000))
        assertEquals(103.5, ref.positionAt(13_500))
    }

    @Test
    fun `a paused reference holds its position`() {
        val ref = RoomReference(positionSeconds = 42.0, executeAtServerMs = 10_000, playing = false)
        assertEquals(42.0, ref.positionAt(60_000))
    }

    // ---- command application -------------------------------------------------------------

    @Test
    fun `a seek always seeks, even with no drift`() {
        assertEquals(RoomCatchup.Decision.Seek, decide(TransportAction.Seek, 0.0))
        assertEquals(RoomCatchup.Decision.Seek, decide(TransportAction.Seek, 0.0, memberPlaying = false))
    }

    @Test
    fun `a play for a playing member nudges up to the hard seek limit, even when the target is buffered`() {
        assertEquals(RoomCatchup.Decision.None, decide(TransportAction.Play, 0.4, reachable = true))
        assertEquals(RoomCatchup.Decision.Nudge(1.02), decide(TransportAction.Play, 0.5, reachable = true))
        assertEquals(RoomCatchup.Decision.Nudge(0.96), decide(TransportAction.Play, -5.0, reachable = true))
        assertEquals(RoomCatchup.Decision.SeekOrReload, decide(TransportAction.Play, 5.01))
        assertEquals(RoomCatchup.Decision.SeekOrReload, decide(TransportAction.Play, -8.0, reachable = true))
    }

    @Test
    fun `a play for a member at rest seeks only for a large gap or a cheap one`() {
        val play = { drift: Double, reachable: Boolean ->
            decide(TransportAction.Play, drift, memberPlaying = false, reachable = reachable)
        }
        assertEquals(RoomCatchup.Decision.None, play(1.0, true))
        assertEquals(RoomCatchup.Decision.Seek, play(1.1, true))
        assertEquals(RoomCatchup.Decision.None, play(4.9, false))
        assertEquals(RoomCatchup.Decision.Seek, play(-5.1, false))
    }

    @Test
    fun `a pause seeks under the same rule as a member at rest`() {
        assertEquals(RoomCatchup.Decision.None, decide(TransportAction.Pause, 0.9, reachable = true))
        assertEquals(RoomCatchup.Decision.Seek, decide(TransportAction.Pause, -1.5, reachable = true))
        assertEquals(RoomCatchup.Decision.None, decide(TransportAction.Pause, 3.0, reachable = false))
        assertEquals(RoomCatchup.Decision.Seek, decide(TransportAction.Pause, 40.0, reachable = false))
    }

    @Test
    fun `a member that wants to play but is not advancing reloads a large gap through the budget`() {
        val buffering = { drift: Double, reachable: Boolean ->
            decide(TransportAction.Play, drift, memberPlaying = false, intendsToPlay = true, reachable = reachable)
        }
        assertEquals(RoomCatchup.Decision.SeekOrReload, buffering(5.1, false))
        assertEquals(RoomCatchup.Decision.SeekOrReload, buffering(-40.0, true))
        assertEquals(RoomCatchup.Decision.Seek, buffering(2.0, true))
        assertEquals(RoomCatchup.Decision.None, buffering(2.0, false))
        // Truly stopped: the stopped rule seeks a large gap directly.
        assertEquals(RoomCatchup.Decision.Seek, decide(TransportAction.Play, 40.0, memberPlaying = false, intendsToPlay = false))
    }

    @Test
    fun `without rate support a nudge becomes a seek beyond one second`() {
        val play = { drift: Double, reachable: Boolean ->
            decide(TransportAction.Play, drift, reachable = reachable, rateCapable = false)
        }
        assertEquals(RoomCatchup.Decision.None, play(0.8, true))
        assertEquals(RoomCatchup.Decision.None, play(0.8, false))
        assertEquals(RoomCatchup.Decision.Seek, play(1.5, true))
        // Out of reach in place: nothing else would ever converge it, so
        // the reload budget paces a rebuild.
        assertEquals(RoomCatchup.Decision.SeekOrReload, play(3.0, false))
        assertEquals(RoomCatchup.Decision.SeekOrReload, play(-3.0, false))
        assertEquals(RoomCatchup.Decision.SeekOrReload, play(6.0, false))
    }

    @Test
    fun `a play that leaves the room waiting does not nudge a playing member`() {
        assertEquals(
            RoomCatchup.Decision.None,
            decide(TransportAction.Play, 2.0, memberPlaying = true, commandPlays = false, reachable = false),
        )
    }

    // ---- rate --------------------------------------------------------------------------

    @Test
    fun `the rate is clamped to one to four percent in the direction of the drift`() {
        assertEquals(1.0, RoomCatchup.rateFor(0.0))
        assertEquals(1.01, RoomCatchup.rateFor(0.05))
        assertEquals(0.99, RoomCatchup.rateFor(-0.05))
        assertEquals(1.04, RoomCatchup.rateFor(10.0))
        assertEquals(0.96, RoomCatchup.rateFor(-300.0))
        assertEquals(1.0, RoomCatchup.rateFor(Double.NaN))
    }

    @Test
    fun `the rate is quantized to half-percent steps`() {
        // 0.6 / 25 = 0.024 -> 0.025; 0.55 / 25 = 0.022 -> 0.02.
        assertEquals(1.025, RoomCatchup.rateFor(0.6))
        assertEquals(1.02, RoomCatchup.rateFor(0.55))
        assertEquals(0.975, RoomCatchup.rateFor(-0.6))
        // Equal drift magnitudes give one exact value, so an unchanged rate is never re-applied.
        assertEquals(RoomCatchup.rateFor(0.61), RoomCatchup.rateFor(0.62))
    }

    @Test
    fun `the loop starts a nudge beyond the deadband and keeps it until within the settle band`() {
        val loop = { drift: Double, nudging: Boolean -> RoomCatchup.loopDecision(drift, nudging) }
        assertEquals(RoomCatchup.Decision.None, loop(0.3, false))
        assertEquals(RoomCatchup.Decision.Nudge(1.02), loop(0.5, false))
        // Hysteresis: the same drift continues a running nudge.
        assertEquals(RoomCatchup.Decision.Nudge(1.01), loop(0.3, true))
        assertEquals(RoomCatchup.Decision.Nudge(0.99), loop(-0.11, true))
        assertEquals(RoomCatchup.Decision.None, loop(0.1, true))
        assertEquals(RoomCatchup.Decision.None, loop(-0.05, true))
    }

    @Test
    fun `without rate support the loop seeks in place beyond one second and never nudges`() {
        val loop = { drift: Double, reachable: Boolean ->
            RoomCatchup.loopDecision(drift, nudging = false, rateCapable = false, targetReachableInPlace = reachable)
        }
        assertEquals(RoomCatchup.Decision.None, loop(0.6, true))
        assertEquals(RoomCatchup.Decision.None, loop(1.0, true))
        assertEquals(RoomCatchup.Decision.Seek, loop(-1.2, true))
        assertEquals(RoomCatchup.Decision.None, loop(8.0, false))
    }

    @Test
    fun `fallback seeks aim ahead by the measured shortfall and back off until they converge`() {
        // A seek that took 1.5 s left the member 1.5 s behind.
        var step = RoomCatchup.fallbackAfterSeek(leadSeconds = 0.0, intervalMs = 1_000, driftSeconds = 1.5)
        assertEquals(RoomCatchup.FallbackStep(1.5, 2_000), step)
        // Still 1.2 s short: the lead grows by it and the interval doubles again.
        step = RoomCatchup.fallbackAfterSeek(step.leadSeconds, step.intervalMs, driftSeconds = 1.2)
        assertEquals(2.7, step.leadSeconds, 1e-9)
        assertEquals(4_000L, step.intervalMs)
        // The lead is capped and the interval tops out at 16 s.
        step = RoomCatchup.fallbackAfterSeek(step.leadSeconds, 16_000, driftSeconds = 2.0)
        assertEquals(RoomCatchup.FallbackStep(3.0, 16_000), step)
        // Overshooting shrinks the lead, never below zero; converging resets the interval.
        step = RoomCatchup.fallbackAfterSeek(leadSeconds = 0.5, intervalMs = 8_000, driftSeconds = -0.8)
        assertEquals(RoomCatchup.FallbackStep(0.0, 1_000), step)
    }

    // ---- reload budget -------------------------------------------------------------------

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
    fun `an expired reload admits a replacement at once and keeps growing the backoff`() {
        val budget = RoomReloadBudget()
        budget.begin(10.0, nowMs = 0)
        budget.expire(nowMs = 30_000)
        assertFalse(budget.inFlight)
        assertTrue(budget.allowed(30_000))

        // The attempt count stood: the second attempt backs off 20 s, not 10 s.
        budget.begin(20.0, nowMs = 30_000)
        budget.abandon(nowMs = 31_000)
        assertFalse(budget.allowed(50_999))
        assertTrue(budget.allowed(51_000))
    }

    @Test
    fun `an unlanded reload stops blocking after thirty seconds and settling resets backoff`() {
        val budget = RoomReloadBudget()
        budget.begin(10.0, nowMs = 0)
        assertFalse(budget.landed(10.0))
        assertFalse(budget.stale(29_999))
        assertTrue(budget.stale(30_000))
        assertTrue(budget.allowed(30_000))
        budget.abandon(30_000)
        budget.settle()
        assertTrue(budget.allowed(30_000))
    }
}
