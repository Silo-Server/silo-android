package org.siloserver.silo.domain.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import org.siloserver.silo.model.playback.BANDWIDTH_ESTIMATE_SOURCE_OBSERVED_REBUFFER
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behaviour of [BufferingQualityController] that the vendored conformance
 * vectors do not pin: the pill timer's exact remainder and its flows, the
 * return values the UI routes on, a quality change clearing the answer,
 * adoption identity, disabling and Watch Party suspension, a mode change on a
 * showing prompt, summary stall numbers and `reset`.
 * `BufferingQualityVectorsTest` covers the rest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BufferingQualityControllerTest {

    private val menu = listOf(
        BufferingQualityRung("original", 63_000),
        BufferingQualityRung("1080p-high", 10_000),
        BufferingQualityRung("1080p-medium", 6_000),
        BufferingQualityRung("720p-high", 4_000),
    )

    private fun plan(id: String, deliveredKbps: Int = 63_000, serverPlanId: String = id) =
        BufferingQualityPlan(
            planId = id,
            deliveredKbps = deliveredKbps,
            quality = "auto",
            availableQualities = menu,
            serverPlanId = serverPlanId,
        )

    private val replans = mutableListOf<BufferingQualityReplan>()
    private val notices = mutableListOf<BufferingQualityNotice>()
    private val summaries = mutableListOf<BufferingPlanSummary>()

    /**
     * The controller's timers run under [TestScope.backgroundScope], which
     * `runTest` cancels before draining the scheduler. A stall still in
     * progress when a test ends keeps re-arming in virtual time, so a scope
     * outside it would never drain.
     */
    private fun TestScope.newController(mode: BufferingQualityMode): BufferingQualityController =
        BufferingQualityController(
            scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
            clock = { testScheduler.currentTime },
            onReplan = { replans += it },
            onNotice = { notices += it },
            onPlanSummary = { summaries += it },
        ).also { it.setMode(mode) }

    /** Runs every timer due at or before [t], leaving the clock at [t]. */
    private fun TestScope.at(t: Long) {
        advanceTimeBy(t - testScheduler.currentTime)
        runCurrent()
    }

    /** p1 adopted and playing at 0, then a network wait from 125000 that triggers at 131000. */
    private fun TestScope.longStall(controller: BufferingQualityController) {
        controller.onPlanAdopted(plan("p1"))
        controller.onPlaying()
        at(125_000)
        controller.onWaiting()
        at(131_000)
    }

    @Test
    fun `a missed start deadline lowers only on Auto in always mode and not after Keep`() = runTest {
        data class Case(val name: String, val mode: BufferingQualityMode, val quality: String = "auto", val kept: Boolean = false)
        val cases = listOf(
            Case("always on Auto", BufferingQualityMode.ALWAYS) to true,
            Case("ask on Auto", BufferingQualityMode.ASK) to false,
            Case("never on Auto", BufferingQualityMode.NEVER) to false,
            Case("always on a picked quality", BufferingQualityMode.ALWAYS, quality = "1080p-high") to false,
            Case("always after Keep", BufferingQualityMode.ALWAYS, kept = true) to false,
        )
        for ((case, lowers) in cases) {
            replans.clear()
            val controller = newController(if (case.kept) BufferingQualityMode.ASK else case.mode)
            if (case.kept) {
                longStall(controller)
                assertTrue(controller.selectKeep(), case.name)
                controller.setMode(case.mode)
                controller.onPlanAdopted(plan("p2"), controllerInitiated = false)
            } else {
                controller.onPlanAdopted(plan("p1").copy(quality = case.quality))
            }

            assertEquals(lowers, controller.onStartDeadlineMissed(), case.name)
            val expected = if (lowers) {
                listOf(BufferingQualityReplan("auto", 44_100, BANDWIDTH_ESTIMATE_SOURCE_OBSERVED_REBUFFER))
            } else {
                emptyList()
            }
            assertEquals(expected, replans, case.name)
            controller.reset()
        }
    }

    @Test
    fun `pause freezes the prompt timer and play resumes it from the exact remainder`() = runTest {
        val controller = newController(BufferingQualityMode.ASK)
        longStall(controller)
        assertEquals(BufferingQualityPill.Prompt(15), controller.pill.value)
        val run = controller.countdownRun.value

        at(135_300) // 10700 ms left
        assertEquals(BufferingQualityPill.Prompt(11), controller.pill.value)
        controller.onPause()
        assertFalse(controller.timerRunning.value)

        at(200_000)
        assertEquals(BufferingQualityPill.Prompt(11), controller.pill.value)

        controller.onPlaying()
        assertTrue(controller.timerRunning.value)
        assertEquals(run + 1, controller.countdownRun.value)
        at(210_699)
        assertEquals(BufferingQualityPill.Prompt(1), controller.pill.value)
        at(210_700)
        assertEquals(BufferingQualityPill.Hidden, controller.pill.value)
    }

    @Test
    fun `a wait after a stop the viewer did not make runs the frozen timer again`() = runTest {
        val controller = newController(BufferingQualityMode.ASK)
        longStall(controller)
        // A player error reaches the controller as a stop.
        controller.onPause()
        assertFalse(controller.timerRunning.value)

        at(140_000)
        controller.onWaiting(BufferingWaitCause.PAUSE)
        assertFalse(controller.timerRunning.value)
        // Recovery re-prepares the player, which waits for data to play.
        controller.onWaiting()
        assertTrue(controller.timerRunning.value)
        at(154_999)
        assertTrue(controller.pill.value.isVisible)
        at(155_000)
        assertEquals(BufferingQualityPill.Hidden, controller.pill.value)
    }

    @Test
    fun `a deferred prompt shows when the other pill hides on the same plan`() = runTest {
        val controller = newController(BufferingQualityMode.ASK)
        controller.setOtherPillVisible(true)
        longStall(controller)
        assertEquals(BufferingQualityPill.Hidden, controller.pill.value)

        at(140_000)
        controller.setOtherPillVisible(false)
        assertEquals(BufferingQualityPill.Prompt(15), controller.pill.value)
        // Its timer starts when it shows.
        at(154_999)
        assertTrue(controller.pill.value.isVisible)
        at(155_000)
        assertEquals(BufferingQualityPill.Hidden, controller.pill.value)
    }

    @Test
    fun `an adoption that repeats the server plan id is still a new plan`() = runTest {
        val controller = newController(BufferingQualityMode.ALWAYS)
        controller.onPlanAdopted(plan("s1#1", serverPlanId = "s1"))
        controller.onPlaying()
        at(125_000)
        controller.onWaiting()
        at(131_000)
        assertEquals(44_100, replans.single().bandwidthEstimateKbps)

        at(134_000)
        val lower = plan("s1#2", deliveredKbps = 31_600, serverPlanId = "s1")
        controller.onPlanAdopted(lower)
        assertTrue(controller.pill.value is BufferingQualityPill.Undo)
        // The same adoption reported again changes nothing.
        controller.onPlanAdopted(lower)
        assertTrue(controller.pill.value is BufferingQualityPill.Undo)

        controller.onPlaybackStopped()
        assertEquals(listOf("s1", "s1"), summaries.map { it.planId })
        assertEquals(BufferingAction.LOWERED, summaries.first().action)
    }

    @Test
    fun `back is consumed only while a pill shows`() = runTest {
        val controller = newController(BufferingQualityMode.ALWAYS)
        assertFalse(controller.back())
        longStall(controller)
        assertFalse(controller.back(), "the automatic lowering shows nothing yet")

        at(134_000)
        controller.onPlanAdopted(plan("p2", deliveredKbps = 31_600))
        assertTrue(controller.pill.value is BufferingQualityPill.Undo)
        assertTrue(controller.back())
        assertEquals(BufferingQualityPill.Hidden, controller.pill.value)
        assertFalse(controller.back())
        assertFalse(controller.selectPrimary())
    }

    @Test
    fun `a viewer quality change clears the answer so a later trigger prompts again`() = runTest {
        val controller = newController(BufferingQualityMode.ASK)
        longStall(controller)
        at(132_000)
        assertTrue(controller.selectKeep())
        controller.onPlaying()

        at(200_000)
        controller.onWaiting()
        at(206_000)
        assertEquals(BufferingQualityPill.Hidden, controller.pill.value, "answered at auto")
        controller.onPlaying()

        controller.onViewerQualityChange()
        at(300_000)
        controller.onWaiting()
        at(306_000)
        assertEquals(BufferingQualityPill.Prompt(15), controller.pill.value)
    }

    @Test
    fun `disabled suppresses everything until a plan is adopted after re-enabling`() = runTest {
        val controller = newController(BufferingQualityMode.ASK)
        longStall(controller)
        assertTrue(controller.pill.value.isVisible)

        controller.setEnabled(false)
        assertEquals(BufferingQualityPill.Hidden, controller.pill.value)
        assertFalse(controller.selectLower())
        controller.onPlaying()
        at(200_000)
        controller.onWaiting()
        at(220_000)
        controller.setEnabled(true)
        at(240_000)
        controller.onPlaying()
        controller.onWaiting()
        at(260_000)
        assertEquals(BufferingQualityPill.Hidden, controller.pill.value)
        assertTrue(replans.isEmpty())
        assertTrue(notices.isEmpty())

        controller.onPlanAdopted(plan("p2"))
        controller.onPlaying()
        at(400_000)
        controller.onWaiting()
        at(406_000)
        assertEquals(BufferingQualityPill.Prompt(15), controller.pill.value)
    }

    @Test
    fun `a summary counts every stall's time and only long enough ones in the count`() = runTest {
        val controller = newController(BufferingQualityMode.ALWAYS)
        controller.onPlanAdopted(plan("p1"))
        controller.onPlaying()
        at(20_000)
        controller.onWaiting()
        at(20_400)
        controller.onPlaying() // 400 ms: time only
        at(30_000)
        controller.onWaiting()
        at(31_500)
        controller.onPlaying() // 1500 ms: counted
        at(40_000)
        controller.onWaiting()
        at(42_000)
        controller.onPause() // ended by a pause: time only
        at(50_000)
        controller.onPlaying()
        controller.onWaiting()
        at(52_000)
        controller.onSeek() // ended by a seek: time only
        at(60_000)
        controller.onPlaying()
        controller.onWaiting()
        at(61_000)
        controller.onTransportErrorStart() // the outage owns it from the start
        at(70_000)
        controller.onPlaybackStopped()

        val summary = summaries.single()
        assertEquals(1, summary.stallCount)
        assertEquals(400L + 1_500L + 2_000L + 2_000L, summary.stallTotalMs)
        assertEquals(2_000L, summary.stallLongestMs)
    }

    @Test
    fun `a stall in progress when its plan is replaced ends then and counts`() = runTest {
        val controller = newController(BufferingQualityMode.ALWAYS)
        longStall(controller)
        at(134_000)
        controller.onPlanAdopted(plan("p2", deliveredKbps = 31_600))

        val first = summaries.single()
        assertEquals(1, first.stallCount)
        assertEquals(9_000L, first.stallTotalMs)
        assertEquals(BufferingAction.LOWERED, first.action)
    }

    @Test
    fun `the running summary reads the playing plan without ending it`() = runTest {
        val controller = newController(BufferingQualityMode.ASK)
        assertNull(controller.runningSummary())
        controller.onPlanAdopted(plan("p1#3", serverPlanId = "p1"))
        controller.onPlaying()
        at(20_000)
        controller.onWaiting()
        at(23_000)

        // Mid-stall: the play so far, and the stall counted as if it ended now.
        val early = assertNotNull(controller.runningSummary())
        assertEquals("p1", early.planId)
        assertEquals("p1#3", early.adoptionId)
        assertEquals(20_000L, early.playedMs)
        assertEquals(1, early.stallCount)
        assertEquals(3_000L, early.stallTotalMs)
        assertTrue(summaries.isEmpty())

        // The read changed nothing: the stall it saw runs on to its real end.
        at(25_000)
        controller.onPlaying()
        at(30_000)
        controller.onPlaybackStopped()
        val final = summaries.single()
        assertEquals("p1#3", final.adoptionId)
        assertEquals(25_000L, final.playedMs)
        assertEquals(1, final.stallCount)
        assertEquals(5_000L, final.stallTotalMs)
        assertEquals(5_000L, final.stallLongestMs)
        assertNull(controller.runningSummary())
    }

    @Test
    fun `in a Watch Party room the controller tracks and summarizes but never acts`() = runTest {
        val controller = newController(BufferingQualityMode.ALWAYS)
        controller.setSuspended(true)
        controller.onPlanAdopted(plan("p1"))
        controller.onPlaying()
        at(125_000)
        controller.onWaiting()
        at(140_000)
        controller.onPlaying()
        assertTrue(replans.isEmpty())
        assertTrue(notices.isEmpty())
        assertEquals(BufferingQualityPill.Hidden, controller.pill.value)

        // Leaving is not a plan boundary: the same plan plays on, with fresh
        // history and no grace, so the stall from the room does not trigger.
        controller.setSuspended(false)
        controller.onWaiting()
        at(145_000)
        controller.onPlaying()
        assertTrue(replans.isEmpty())
        controller.onWaiting()
        at(151_000)
        assertEquals(44_100, replans.single().bandwidthEstimateKbps)

        controller.onPlaybackStopped()
        val summary = summaries.single()
        assertEquals("p1", summary.planId)
        assertEquals(3, summary.stallCount, "the room's stall, the 5 s one, and the one the plan ended in")
    }

    @Test
    fun `joining a room drops a pending lowering and hides the pill`() = runTest {
        val controller = newController(BufferingQualityMode.ALWAYS)
        longStall(controller)
        assertEquals(1, replans.size)
        controller.setSuspended(true)
        at(134_000)
        controller.onPlanAdopted(plan("p2", deliveredKbps = 31_600))
        assertEquals(BufferingQualityPill.Hidden, controller.pill.value, "no undo pill in the room")
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `a new attempt ends the plan and reset clears the title's answer`() = runTest {
        val controller = newController(BufferingQualityMode.ASK)
        longStall(controller)
        assertTrue(controller.selectKeep())
        controller.onNewAttempt()
        assertEquals(1, summaries.size)

        controller.onPlanAdopted(plan("p2"))
        controller.reset()
        controller.onPlanAdopted(plan("p3"))
        controller.onPlaying()
        at(500_000)
        controller.onWaiting()
        at(506_000)
        assertEquals(BufferingQualityPill.Prompt(15), controller.pill.value, "other content starts unanswered")
    }

    @Test
    fun `a slow start stays one timed wait through a reload or seek until the plan plays`() = runTest {
        val controller = newController(BufferingQualityMode.ASK)
        controller.onPlanAdopted(plan("p1"))
        controller.onWaiting()
        // The start reopens its media and seeks to the resume point.
        at(4_000)
        controller.onWaiting(BufferingWaitCause.RELOAD)
        at(9_000)
        controller.onSeek()
        controller.onWaiting(BufferingWaitCause.SEEK)
        at(15_999)
        assertEquals(BufferingQualityPill.Hidden, controller.pill.value)
        at(16_000)
        assertEquals(BufferingQualityPill.Prompt(15), controller.pill.value, "the grace and a long stall from the first wait")

        // A pause before the first frame ends the current wait uncounted, but a
        // seek and play after it are still the start's: timed from the first wait.
        controller.reset()
        at(100_000)
        controller.onPlanAdopted(plan("p2"))
        controller.onWaiting()
        at(105_000)
        controller.onPause()
        at(108_000)
        controller.onSeek()
        controller.onWaiting(BufferingWaitCause.SEEK)
        at(115_999)
        assertEquals(BufferingQualityPill.Hidden, controller.pill.value)
        at(116_000)
        assertEquals(BufferingQualityPill.Prompt(15), controller.pill.value)
    }

    @Test
    fun `an adopted plan starts with no outage`() = runTest {
        val controller = newController(BufferingQualityMode.ASK)
        controller.onPlanAdopted(plan("p1"))
        controller.onPlaying()
        at(125_000)
        controller.onTransportErrorStart()
        controller.onWaiting()
        // A recovery replaces the plan; its transport has no outage.
        at(130_000)
        controller.onPlanAdopted(plan("p2"))
        controller.onPlaying()
        at(250_000)
        controller.onWaiting()
        at(256_000)
        assertEquals(BufferingQualityPill.Prompt(15), controller.pill.value)
    }

    @Test
    fun `a pick hides the pill when sent, and a lowering adopted while it is pending shows nothing`() = runTest {
        val controller = newController(BufferingQualityMode.ALWAYS)
        longStall(controller)
        assertEquals(1, replans.size, "Automatically lowers at once")

        // The viewer picks while the lowering is in flight: nothing shows for it.
        controller.onQualityPickSent()
        controller.onPlanAdopted(plan("p2", deliveredKbps = 6_000))
        assertEquals(BufferingQualityPill.Hidden, controller.pill.value)
        assertTrue(notices.isEmpty())

        // The pick refused: the next lowering's undo shows again.
        controller.onQualityPickRefused()
        controller.onPlaying()
        at(300_000)
        controller.onWaiting()
        at(306_000)
        controller.onPlanAdopted(plan("p3", deliveredKbps = 4_000))
        assertTrue(controller.pill.value is BufferingQualityPill.Undo)

        // A pick sent while a pill shows hides it, and keeps the title's answer until its plan plays.
        controller.onQualityPickSent()
        assertEquals(BufferingQualityPill.Hidden, controller.pill.value)
    }

    @Test
    fun `only the latest pick settles the pending pick`() = runTest {
        val controller = newController(BufferingQualityMode.ASK)
        val picks = BufferingQualityPicks(controller)
        controller.onPlanAdopted(plan("p1"))
        controller.onPlaying()

        // Pick A, then pick B replaces it while A still waits behind a replan.
        val pickA = picks.sent(matches = { it.quality == "720p-high" }, outstanding = { true })
        var bOutstanding = true
        val pickB = picks.sent(matches = { it.quality == "1080p-medium" }, outstanding = { bOutstanding })

        // A's refusal settles nothing: a long stall still prompts nothing.
        picks.refused(pickA)
        at(125_000)
        controller.onWaiting()
        at(131_000)
        assertEquals(BufferingQualityPill.Hidden, controller.pill.value)

        // A's plan adopted while B waits leaves B pending.
        val keyA = BufferingQualityKey("720p-high", null, 1)
        assertTrue(picks.stillPendingAfter(keyA))
        controller.onPlanAdopted(plan("p2"), controllerInitiated = false, pickStillPending = true)
        controller.onPlaying()
        at(260_000)
        controller.onWaiting()
        at(266_000)
        assertEquals(BufferingQualityPill.Hidden, controller.pill.value)

        // B refused: the long stall in progress is evaluated at once.
        bOutstanding = false
        picks.refused(pickB)
        assertEquals(BufferingQualityPill.Prompt(15), controller.pill.value)
    }

    @Test
    fun `a restart hides the undo pill so Restore cannot reach the plan it ends`() = runTest {
        val controller = newController(BufferingQualityMode.ALWAYS)
        longStall(controller)
        controller.onPlanAdopted(plan("p2", deliveredKbps = 6_000))
        assertTrue(controller.pill.value is BufferingQualityPill.Undo)
        val sent = replans.size

        controller.onNewAttempt()

        assertEquals(BufferingQualityPill.Hidden, controller.pill.value)
        assertFalse(controller.selectRestore())
        assertEquals(sent, replans.size)
    }

    @Test
    fun `never hides a visible prompt and records ignored triggers`() = runTest {
        val controller = newController(BufferingQualityMode.ASK)
        longStall(controller)
        assertTrue(controller.pill.value.isVisible)

        controller.setMode(BufferingQualityMode.NEVER)
        assertEquals(BufferingQualityPill.Hidden, controller.pill.value)
        controller.onPlaying()
        at(200_000)
        controller.onWaiting()
        at(210_000)
        controller.onPlaybackStopped()

        assertEquals(BufferingQualityPill.Hidden, controller.pill.value)
        assertTrue(replans.isEmpty())
        assertTrue(notices.isEmpty())
        val summary = summaries.single()
        assertEquals(BufferingAction.IGNORED, summary.action)
        assertEquals(BufferingQualityMode.NEVER, summary.mode)
    }
}
