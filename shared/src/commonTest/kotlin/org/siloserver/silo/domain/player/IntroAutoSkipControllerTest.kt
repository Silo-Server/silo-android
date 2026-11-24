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
 * `docs/design/2026-08-16-intro-skip-mode.md` ("Prompt behaviour"), with its
 * "When the controls are up" and "Recap and credits pills" sections; every row
 * of them should be findable here by name. The same tables drive web, iOS,
 * tvOS and Roku, so a divergence is meant to fail here rather than arrive as a
 * bug report.
 *
 * The "move focus away, timer keeps running" rows have no case: the
 * controller takes no focus input, so focus cannot reach the timer.
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
    private lateinit var controls: MutableStateFlow<Boolean>

    /** Positions the controller seeked to on its own (the `always` skip). */
    private lateinit var seeks: MutableList<Double>

    private fun setup(startMode: IntroSkipMode) {
        position = MutableStateFlow(0.0)
        range = MutableStateFlow<TimeRange?>(introRange)
        mode = MutableStateFlow(startMode)
        introKey = MutableStateFlow<String?>(key)
        playing = MutableStateFlow(true)
        controls = MutableStateFlow(false)
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
                controlsVisible = controls,
            )
        }
    }

    private val recapRange = TimeRange(start = 10.0, end = 40.0)
    private val creditsRange = TimeRange(start = 1300.0, end = 1400.0)

    /**
     * The recap and credits pill, through the entry point both players use.
     * It takes no mode: the pill follows the `ask` table whatever the intro
     * mode is.
     */
    private fun TestScope.newSegmentController(countdown: Int = 5): IntroAutoSkipController {
        setup(IntroSkipMode.ASK)
        val markers = MutableStateFlow(
            MarkerTimeline.resolve(
                segments = null,
                intro = null,
                credits = creditsRange,
                recap = recapRange,
                preview = null,
            ),
        )
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        return IntroAutoSkipController(scope = scope, countdownSeconds = countdown).also {
            it.observeSegmentPill(
                position = position,
                markers = markers,
                playbackKey = MutableStateFlow<String?>("session-1:file-1"),
                playbackActive = playing,
                controlsVisible = controls,
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

        // Still inside the same intro with the controls hidden: the offer has
        // withdrawn itself and must not come back on its own.
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
    fun `ask - controls showing after the timer ran out bring the pill back without a timer`() = runTest {
        val controller = newController(IntroSkipMode.ASK, countdown = 3)
        position.value = 35.0
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value)

        controls.value = true
        runCurrent()
        assertEquals(IntroAutoSkipState.Returned, controller.state.value)
        assertFalse(controller.timerRunning.value)

        // Untimed: it stays for as long as the controls do.
        advanceTimeBy(30_000)
        runCurrent()
        assertEquals(IntroAutoSkipState.Returned, controller.state.value)

        controls.value = false
        runCurrent()
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value, "it hides with the controls")
        assertTrue(seeks.isEmpty())
    }

    @Test
    fun `ask - the timer running out with the controls up leaves the pill without a timer`() = runTest {
        val controller = newController(IntroSkipMode.ASK, countdown = 3)
        controls.value = true
        position.value = 35.0
        runCurrent()
        assertEquals(IntroAutoSkipState.Asking(3), controller.state.value)

        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(IntroAutoSkipState.Returned, controller.state.value)
    }

    @Test
    fun `ask - Select on the returned pill skips to the end and resolves the intro`() = runTest {
        val controller = newController(IntroSkipMode.ASK, countdown = 3)
        controls.value = true
        position.value = 35.0
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(IntroAutoSkipState.Returned, controller.state.value)

        assertEquals(introRange.end, controller.select())
        runCurrent()
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value)

        position.value = introRange.end
        runCurrent()
        position.value = 40.0
        runCurrent()
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value, "resolved, even with the controls up")
    }

    @Test
    fun `ask - the returned pill does not claim Back and leaves the intro unresolved`() = runTest {
        val controller = newController(IntroSkipMode.ASK, countdown = 3)
        controls.value = true
        position.value = 35.0
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()

        assertFalse(controller.dismiss(), "Back does what it does without the pill")
        runCurrent()
        assertEquals(IntroAutoSkipState.Returned, controller.state.value)

        // Leaving the range takes it down; coming back offers a full timer.
        position.value = 95.0
        runCurrent()
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value)
        position.value = 35.0
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
    fun `ask - a later intro under the same key gets its own offer`() = runTest {
        // An episode's second intro arrives as a new range; the caller's key
        // names the session and file and may lag a frame behind the range.
        val controller = newController(IntroSkipMode.ASK, countdown = 3)
        position.value = 35.0
        runCurrent()
        controller.dismiss()
        runCurrent()

        range.value = TimeRange(start = 600.0, end = 660.0)
        position.value = 605.0
        runCurrent()
        assertEquals(IntroAutoSkipState.Asking(3), controller.state.value)
        assertEquals(660.0, controller.select())
    }

    @Test
    fun `always - the undo for a later intro survives its own skip`() = runTest {
        val controller = newController(IntroSkipMode.ALWAYS, countdown = 3)
        range.value = TimeRange(start = 600.0, end = 660.0)
        position.value = 605.0
        runCurrent()
        assertEquals(listOf(660.0), seeks)
        assertEquals(IntroAutoSkipState.Skipped(3), controller.state.value)
        assertEquals(600.0, controller.select())
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
    fun `always - an undo that timed out does not return with the controls`() = runTest {
        val controller = newController(IntroSkipMode.ALWAYS, countdown = 3)
        controls.value = true
        position.value = 35.0
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value)

        position.value = 45.0
        runCurrent()
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value, "the timeout resolved the intro")
        assertNull(controller.select())
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

    // ---- recap and credits ---------------------------------------------

    @Test
    fun `recap and credits - entering the range offers the timed pill`() = runTest {
        val controller = newSegmentController(countdown = 3)
        runCurrent()
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value)

        position.value = 15.0
        runCurrent()
        assertEquals(IntroAutoSkipState.Asking(3), controller.state.value, "the recap")

        position.value = 1310.0
        runCurrent()
        assertEquals(IntroAutoSkipState.Asking(3), controller.state.value, "the credits")
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(IntroAutoSkipState.Asking(2), controller.state.value)
        assertTrue(controller.timerRunning.value)
    }

    @Test
    fun `recap and credits - Select skips to the range end in one press and resolves it`() = runTest {
        val controller = newSegmentController()
        position.value = 1310.0
        runCurrent()

        assertEquals(creditsRange.end, controller.select())
        runCurrent()
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value)

        controls.value = true
        position.value = 1320.0
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value, "a resolved range never offers again")
    }

    @Test
    fun `recap and credits - the timer running out hides the pill without resolving the range`() = runTest {
        val controller = newSegmentController(countdown = 3)
        position.value = 15.0
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value)
        assertNull(controller.select(), "Select is normal Select again")

        position.value = 45.0
        runCurrent()
        position.value = 12.0
        runCurrent()
        assertEquals(IntroAutoSkipState.Asking(3), controller.state.value, "not resolved")
    }

    @Test
    fun `recap and credits - controls showing after the timer ran out bring the pill back without a timer`() =
        runTest {
            val controller = newSegmentController(countdown = 3)
            position.value = 1310.0
            runCurrent()
            advanceTimeBy(3_000)
            runCurrent()
            assertEquals(IntroAutoSkipState.Hidden, controller.state.value)

            controls.value = true
            runCurrent()
            assertEquals(IntroAutoSkipState.Returned, controller.state.value)
            assertFalse(controller.dismiss(), "the untimed pill does not claim Back")
            assertEquals(IntroAutoSkipState.Returned, controller.state.value)

            controls.value = false
            runCurrent()
            assertEquals(IntroAutoSkipState.Hidden, controller.state.value)

            controls.value = true
            runCurrent()
            assertEquals(creditsRange.end, controller.select(), "activating it acts as Select")
        }

    @Test
    fun `recap and credits - a returned pill does not follow a paused seek into another range`() = runTest {
        val controller = newSegmentController(countdown = 3)
        controls.value = true
        position.value = 15.0
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(IntroAutoSkipState.Returned, controller.state.value, "the recap, untimed")

        // Paused, straight from the recap into the credits: no emission lands
        // outside both ranges, and the credits cannot be offered until play.
        playing.value = false
        runCurrent()
        position.value = 1310.0
        runCurrent()
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value)
        assertNull(controller.select(), "the recap's end is not a target any more")

        playing.value = true
        runCurrent()
        assertEquals(IntroAutoSkipState.Asking(3), controller.state.value, "the credits get their own full timer")
        assertEquals(creditsRange.end, controller.select())
    }

    @Test
    fun `recap and credits - Back while the timed pill shows resolves the range and consumes the press`() =
        runTest {
            val controller = newSegmentController()
            position.value = 1310.0
            runCurrent()

            assertTrue(controller.dismiss())
            runCurrent()
            assertEquals(IntroAutoSkipState.Hidden, controller.state.value)
            assertFalse(controller.dismiss(), "the next Back behaves normally")

            // Resolved: neither seeking back in nor the controls bring it back.
            controls.value = true
            position.value = 1250.0
            runCurrent()
            position.value = 1310.0
            runCurrent()
            assertEquals(IntroAutoSkipState.Hidden, controller.state.value)
            assertTrue(seeks.isEmpty(), "the range keeps playing")
        }

    @Test
    fun `recap and credits - pause freezes the timer and play resumes it`() = runTest {
        val controller = newSegmentController(countdown = 5)
        position.value = 1310.0
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()

        playing.value = false
        runCurrent()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(IntroAutoSkipState.Asking(3), controller.state.value)
        assertFalse(controller.timerRunning.value)

        playing.value = true
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(IntroAutoSkipState.Asking(2), controller.state.value)
    }

    @Test
    fun `recap and credits - seeking out hides the pill and seeking back in restarts the full timer`() = runTest {
        val controller = newSegmentController(countdown = 5)
        position.value = 1310.0
        runCurrent()
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(IntroAutoSkipState.Asking(3), controller.state.value)

        position.value = 1200.0
        runCurrent()
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value)
        assertFalse(controller.timerRunning.value)

        position.value = 1310.0
        runCurrent()
        assertEquals(IntroAutoSkipState.Asking(5), controller.state.value)
    }

    @Test
    fun `recap and credits - a pill held behind the intro pill or Next Up starts its full timer when it shows`() =
        runTest {
            // The players hold playbackActive false while a higher-priority
            // pill or the Next Up screen covers this one.
            val controller = newSegmentController(countdown = 5)
            playing.value = false
            position.value = 15.0
            runCurrent()
            advanceTimeBy(8_000)
            runCurrent()
            assertEquals(IntroAutoSkipState.Hidden, controller.state.value)

            playing.value = true
            runCurrent()
            assertEquals(IntroAutoSkipState.Asking(5), controller.state.value)
        }
}
