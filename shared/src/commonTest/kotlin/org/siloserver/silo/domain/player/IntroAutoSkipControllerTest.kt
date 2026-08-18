package org.siloserver.silo.domain.player

import org.siloserver.silo.model.catalog.TimeRange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The conformance suite for the intro-skip prompt.
 *
 * The oracle is the `never` / `ask` / `always` tables in the server repo's
 * `docs/design/2026-08-16-intro-skip-mode.md` ("Prompt behaviour"); every row of
 * them should be findable here by name. The same tables drive web, iOS and
 * tvOS, so a divergence is meant to fail here rather than arrive as a bug
 * report.
 *
 * Rebuffer filtering is deliberately absent: `playbackActive` reaches the
 * controller already settled (see `SettlingFalseEdges`), so a stall shorter than
 * the grace window never becomes a pause here at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IntroAutoSkipControllerTest {

    private val introRange = TimeRange(start = 30.0, end = 90.0)
    private val key = "session-1:file-1:30:90"

    private lateinit var position: MutableStateFlow<Double>
    private lateinit var range: MutableStateFlow<TimeRange?>
    private lateinit var mode: MutableStateFlow<IntroSkipMode>
    private lateinit var introKey: MutableStateFlow<String?>
    private lateinit var playing: MutableStateFlow<Boolean>

    /** Positions the controller seeked to on its own (the `always` skip). */
    private lateinit var seeks: MutableList<Double>

    private fun setup(startMode: IntroSkipMode) {
        position = MutableStateFlow(0.0)
        range = MutableStateFlow<TimeRange?>(introRange)
        mode = MutableStateFlow(startMode)
        introKey = MutableStateFlow<String?>(key)
        playing = MutableStateFlow(true)
        seeks = mutableListOf()
    }

    private fun TestScope.newController(
        startMode: IntroSkipMode,
        countdown: Int = 5,
    ): IntroAutoSkipController {
        setup(startMode)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        return IntroAutoSkipController(scope = scope, countdownSeconds = countdown).also {
            it.observe(
                position = position,
                introRange = range,
                mode = mode,
                introKey = introKey,
                // The real players move the position as a result of the seek,
                // which is exactly what the `Skipped` pill has to survive.
                onSeek = { to -> seeks += to; position.value = to },
                playbackActive = playing,
            )
        }
    }

    // ---- never ---------------------------------------------------------

    @Test
    fun `never - entering an intro does nothing at all`() = runTest {
        val controller = newController(IntroSkipMode.NEVER, countdown = 3)
        runCurrent()

        position.value = 35.0
        runCurrent()
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value)

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value)
        assertTrue(seeks.isEmpty())
    }

    // ---- ask -----------------------------------------------------------

    @Test
    fun `ask - entering an intro offers the pill and it ticks down`() = runTest {
        val controller = newController(IntroSkipMode.ASK, countdown = 3)
        runCurrent()
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value)

        position.value = 35.0
        runCurrent()
        assertEquals(IntroAutoSkipState.Asking(3), controller.state.value)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(IntroAutoSkipState.Asking(2), controller.state.value)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(IntroAutoSkipState.Asking(1), controller.state.value)
        assertTrue(seeks.isEmpty(), "ask never seeks on its own")
    }

    @Test
    fun `ask - the timer running out hides the pill without resolving the intro`() = runTest {
        val controller = newController(IntroSkipMode.ASK, countdown = 3)
        position.value = 35.0
        runCurrent()

        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value)
        assertTrue(seeks.isEmpty(), "the intro keeps playing")

        // Still inside the same intro: the offer has withdrawn itself and must
        // not immediately come back.
        position.value = 40.0
        runCurrent()
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value)

        // Scrubbing out and back in re-offers, with a full timer.
        position.value = 95.0
        runCurrent()
        position.value = 32.0
        runCurrent()
        assertEquals(IntroAutoSkipState.Asking(3), controller.state.value)
    }

    @Test
    fun `ask - Select seeks to the end, resolves, and does not re-offer on scrub back`() = runTest {
        val controller = newController(IntroSkipMode.ASK, countdown = 5)
        position.value = 35.0
        runCurrent()
        assertEquals(IntroAutoSkipState.Asking(5), controller.state.value)

        assertEquals(introRange.end, controller.select())
        runCurrent()
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value)

        // The caller performs the seek it was handed.
        position.value = introRange.end
        runCurrent()
        position.value = 40.0
        runCurrent()
        assertEquals(
            IntroAutoSkipState.Hidden,
            controller.state.value,
            "a resolved intro never offers again",
        )

        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(seeks.isEmpty(), "the viewer's own skip is returned, never performed here")
    }

    @Test
    fun `ask - Back dismisses the pill, resolves the intro, and reports the press consumed`() =
        runTest {
            val controller = newController(IntroSkipMode.ASK, countdown = 5)
            position.value = 35.0
            runCurrent()
            assertEquals(IntroAutoSkipState.Asking(5), controller.state.value)

            assertTrue(controller.dismiss(), "the first Back is consumed by the pill")
            runCurrent()
            assertEquals(IntroAutoSkipState.Hidden, controller.state.value)
            assertFalse(controller.dismiss(), "a second Back belongs to the player")

            // Resolved: playback stays where it was and the pill never returns.
            position.value = 40.0
            runCurrent()
            assertEquals(IntroAutoSkipState.Hidden, controller.state.value)
            advanceTimeBy(10_000)
            runCurrent()
            assertTrue(seeks.isEmpty())
        }

    @Test
    fun `ask - pause freezes the timer and play resumes it from the same value`() = runTest {
        val controller = newController(IntroSkipMode.ASK, countdown = 5)
        position.value = 35.0
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(IntroAutoSkipState.Asking(3), controller.state.value)

        playing.value = false
        runCurrent()
        assertEquals(
            IntroAutoSkipState.Asking(3),
            controller.state.value,
            "the pill stays visible and holds its number",
        )
        assertFalse(controller.timerRunning.value)

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(
            IntroAutoSkipState.Asking(3),
            controller.state.value,
            "a frozen timer does not run down while paused",
        )

        playing.value = true
        runCurrent()
        assertEquals(IntroAutoSkipState.Asking(3), controller.state.value)
        assertTrue(controller.timerRunning.value)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(IntroAutoSkipState.Asking(2), controller.state.value)

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value)
    }

    @Test
    fun `ask - the timer does not start until playback is actually running`() = runTest {
        val controller = newController(IntroSkipMode.ASK, countdown = 3)
        playing.value = false
        runCurrent()

        position.value = 35.0
        runCurrent()
        assertEquals(
            IntroAutoSkipState.Hidden,
            controller.state.value,
            "the pill and its fill start together, once playback is up",
        )

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value)

        playing.value = true
        runCurrent()
        assertEquals(IntroAutoSkipState.Asking(3), controller.state.value)
    }

    @Test
    fun `ask - seeking out of the intro hides the pill without resolving it`() = runTest {
        val controller = newController(IntroSkipMode.ASK, countdown = 5)
        position.value = 35.0
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(IntroAutoSkipState.Asking(3), controller.state.value)

        position.value = 120.0
        runCurrent()
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value)
        assertFalse(controller.timerRunning.value)

        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(seeks.isEmpty())

        // Not resolved: seeking back in offers again, from a full timer.
        position.value = 35.0
        runCurrent()
        assertEquals(IntroAutoSkipState.Asking(5), controller.state.value)
    }

    @Test
    fun `ask - a different intro gets its own offer`() = runTest {
        val controller = newController(IntroSkipMode.ASK, countdown = 3)
        position.value = 35.0
        runCurrent()
        controller.dismiss()
        runCurrent()
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value)

        introKey.value = "session-1:file-2:30:90"
        runCurrent()
        assertEquals(IntroAutoSkipState.Asking(3), controller.state.value)
    }

    @Test
    fun `no intro key - nothing is ever offered`() = runTest {
        val controller = newController(IntroSkipMode.ASK, countdown = 3)
        introKey.value = null
        runCurrent()

        position.value = 35.0
        runCurrent()
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value)
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(seeks.isEmpty())
    }

    // ---- always --------------------------------------------------------

    @Test
    fun `always - entering an intro skips it immediately and offers the undo`() = runTest {
        val controller = newController(IntroSkipMode.ALWAYS, countdown = 3)
        runCurrent()

        position.value = 35.0
        runCurrent()
        assertEquals(listOf(introRange.end), seeks)
        assertEquals(IntroAutoSkipState.Skipped(3), controller.state.value)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(IntroAutoSkipState.Skipped(2), controller.state.value)
    }

    @Test
    fun `always - the undo pill is anchored to the intro, not to the position`() = runTest {
        val controller = newController(IntroSkipMode.ALWAYS, countdown = 5)
        position.value = 35.0
        runCurrent()
        assertEquals(IntroAutoSkipState.Skipped(5), controller.state.value)

        // The seek already put the position past `end`; playback keeps moving.
        position.value = 95.0
        runCurrent()
        position.value = 140.0
        runCurrent()
        assertEquals(
            IntroAutoSkipState.Skipped(5),
            controller.state.value,
            "position changes must not take the undo down",
        )

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(IntroAutoSkipState.Skipped(4), controller.state.value)
    }

    @Test
    fun `always - the timer running out resolves the intro`() = runTest {
        val controller = newController(IntroSkipMode.ALWAYS, countdown = 3)
        position.value = 35.0
        runCurrent()
        assertEquals(IntroAutoSkipState.Skipped(3), controller.state.value)

        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value)
        assertEquals(listOf(introRange.end), seeks)

        // Resolved — scrubbing back into it does not skip again.
        position.value = 35.0
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value)
        assertEquals(listOf(introRange.end), seeks)
    }

    @Test
    fun `always - Select seeks back to the start, resolves, and does not skip again`() = runTest {
        val controller = newController(IntroSkipMode.ALWAYS, countdown = 5)
        position.value = 35.0
        runCurrent()
        assertEquals(IntroAutoSkipState.Skipped(5), controller.state.value)

        assertEquals(introRange.start, controller.select(), "the undo plays the intro")
        runCurrent()
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value)

        // The caller performs the seek it was handed; the intro plays through.
        position.value = introRange.start
        runCurrent()
        position.value = 45.0
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value)
        assertEquals(
            listOf(introRange.end),
            seeks,
            "the intro is resolved, so re-entering it does not skip again",
        )
    }

    @Test
    fun `always - Back resolves the intro and playback continues past it`() = runTest {
        val controller = newController(IntroSkipMode.ALWAYS, countdown = 5)
        position.value = 35.0
        runCurrent()
        assertEquals(IntroAutoSkipState.Skipped(5), controller.state.value)

        assertTrue(controller.dismiss())
        runCurrent()
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value)
        assertFalse(controller.dismiss())

        position.value = 35.0
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf(introRange.end), seeks)
    }

    @Test
    fun `always - pause freezes the undo timer`() = runTest {
        val controller = newController(IntroSkipMode.ALWAYS, countdown = 5)
        position.value = 35.0
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(IntroAutoSkipState.Skipped(3), controller.state.value)

        playing.value = false
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(IntroAutoSkipState.Skipped(3), controller.state.value)

        playing.value = true
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(IntroAutoSkipState.Skipped(2), controller.state.value)
    }

    // ---- mode changes and reset ----------------------------------------

    @Test
    fun `ask to never mid-intro takes the pill down`() = runTest {
        val controller = newController(IntroSkipMode.ASK, countdown = 5)
        position.value = 35.0
        runCurrent()
        assertEquals(IntroAutoSkipState.Asking(5), controller.state.value)

        mode.value = IntroSkipMode.NEVER
        runCurrent()
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value)

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value)
        assertTrue(seeks.isEmpty())
    }

    @Test
    fun `never to ask mid-intro offers the pill`() = runTest {
        val controller = newController(IntroSkipMode.NEVER, countdown = 5)
        position.value = 35.0
        runCurrent()
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value)

        mode.value = IntroSkipMode.ASK
        runCurrent()
        assertEquals(IntroAutoSkipState.Asking(5), controller.state.value)
    }

    @Test
    fun `ask to always mid-intro skips it there and then`() = runTest {
        val controller = newController(IntroSkipMode.ASK, countdown = 5)
        position.value = 35.0
        runCurrent()
        assertEquals(IntroAutoSkipState.Asking(5), controller.state.value)

        mode.value = IntroSkipMode.ALWAYS
        runCurrent()
        assertEquals(listOf(introRange.end), seeks)
        assertEquals(IntroAutoSkipState.Skipped(5), controller.state.value)
    }

    @Test
    fun `reset clears resolved intros so new content starts fresh`() = runTest {
        val controller = newController(IntroSkipMode.ASK, countdown = 3)
        position.value = 35.0
        runCurrent()
        controller.dismiss()
        runCurrent()
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value)

        controller.reset()
        runCurrent()
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value)

        position.value = 5.0
        runCurrent()
        position.value = 40.0
        runCurrent()
        assertEquals(IntroAutoSkipState.Asking(3), controller.state.value)
    }

    @Test
    fun `select and dismiss are no-ops when no pill is showing`() = runTest {
        val controller = newController(IntroSkipMode.ASK, countdown = 3)
        runCurrent()
        assertNull(controller.select())
        assertFalse(controller.dismiss())
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value)
    }

    @Test
    fun `the countdown run counter advances on a fresh offer and on a resume`() = runTest {
        val controller = newController(IntroSkipMode.ASK, countdown = 5)
        runCurrent()
        val idle = controller.countdownRun.value

        position.value = 35.0
        runCurrent()
        val started = controller.countdownRun.value
        assertTrue(started > idle, "a fresh offer re-anchors the fill")

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(started, controller.countdownRun.value, "a plain tick does not re-anchor")

        playing.value = false
        runCurrent()
        assertEquals(started, controller.countdownRun.value)

        playing.value = true
        runCurrent()
        assertTrue(
            controller.countdownRun.value > started,
            "thawing re-anchors the fill's frame clock",
        )
    }
}
