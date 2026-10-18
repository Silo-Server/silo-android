package org.siloserver.silo.domain.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behaviour of [BufferingQualityController] that the vendored conformance
 * vectors do not reach: pause freezes, pill deferral, Back, quality changes,
 * disabling and `never`. `BufferingQualityVectorsTest` covers the rest.
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
    fun `a deferred prompt is dropped when a new plan is adopted meanwhile`() = runTest {
        val controller = newController(BufferingQualityMode.ASK)
        controller.setOtherPillVisible(true)
        longStall(controller)

        at(135_000)
        controller.onPlanAdopted(plan("p2"))
        controller.setOtherPillVisible(false)
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
