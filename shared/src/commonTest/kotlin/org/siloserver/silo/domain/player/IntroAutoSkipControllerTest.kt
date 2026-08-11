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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class IntroAutoSkipControllerTest {

    private val introRange = TimeRange(start = 30.0, end = 90.0)
    private val key = "session-1:file-1:30:90"

    private lateinit var position: MutableStateFlow<Double>
    private lateinit var range: MutableStateFlow<TimeRange?>
    private lateinit var enabled: MutableStateFlow<Boolean>
    private lateinit var introKey: MutableStateFlow<String?>
    private lateinit var playing: MutableStateFlow<Boolean>
    private lateinit var fired: MutableList<Double>

    @BeforeTest
    fun setup() {
        position = MutableStateFlow(0.0)
        range = MutableStateFlow<TimeRange?>(introRange)
        enabled = MutableStateFlow(true)
        introKey = MutableStateFlow<String?>(key)
        playing = MutableStateFlow(true)
        fired = mutableListOf()
    }

    @AfterTest
    fun teardown() {
        // No explicit cleanup — TestScope structured concurrency handles it.
    }

    private fun TestScope.newController(countdown: Int = 5): IntroAutoSkipController {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        return IntroAutoSkipController(scope = scope, countdownSeconds = countdown).also {
            it.observe(
                position = position,
                introRange = range,
                autoSkipEnabled = enabled,
                introKey = introKey,
                onAutoSkipFire = { to -> fired += to },
                playbackActive = playing,
            )
        }
    }

    @Test
    fun `position inside intro with auto-skip enabled - emits CountingDown progression then fires`() = runTest {
        val controller = newController(countdown = 3)
        runCurrent()
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value)

        position.value = 35.0
        runCurrent()
        assertEquals(IntroAutoSkipState.CountingDown(3), controller.state.value)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(IntroAutoSkipState.CountingDown(2), controller.state.value)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(IntroAutoSkipState.CountingDown(1), controller.state.value)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value)
        assertEquals(listOf(introRange.end), fired)
    }

    @Test
    fun `position inside intro with auto-skip disabled - emits ShowingButton only`() = runTest {
        enabled.value = false
        val controller = newController()
        runCurrent()

        position.value = 35.0
        runCurrent()

        assertEquals(IntroAutoSkipState.ShowingButton, controller.state.value)

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(IntroAutoSkipState.ShowingButton, controller.state.value)
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `cancelCountdown transitions to ShowingButton and prevents fire for same key`() = runTest {
        val controller = newController(countdown = 5)
        runCurrent()

        position.value = 35.0
        runCurrent()
        assertEquals(IntroAutoSkipState.CountingDown(5), controller.state.value)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(IntroAutoSkipState.CountingDown(4), controller.state.value)

        controller.cancelCountdown()
        runCurrent()
        assertEquals(IntroAutoSkipState.ShowingButton, controller.state.value)

        // Move position; controller should still consider key cancelled and only show button.
        position.value = 50.0
        runCurrent()
        assertEquals(IntroAutoSkipState.ShowingButton, controller.state.value)

        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(IntroAutoSkipState.ShowingButton, controller.state.value)
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `position leaves intro mid-countdown - cancels without firing`() = runTest {
        val controller = newController(countdown = 5)
        runCurrent()

        position.value = 35.0
        runCurrent()
        assertEquals(IntroAutoSkipState.CountingDown(5), controller.state.value)

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(IntroAutoSkipState.CountingDown(3), controller.state.value)

        // Leave the intro range.
        position.value = 95.0
        runCurrent()
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value)

        // Even after the original countdown would have completed, no fire.
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `reset clears cancelled keys - countdown re-engages on re-entry`() = runTest {
        val controller = newController(countdown = 3)
        runCurrent()

        position.value = 35.0
        runCurrent()
        controller.cancelCountdown()
        runCurrent()
        assertEquals(IntroAutoSkipState.ShowingButton, controller.state.value)

        controller.reset()
        runCurrent()
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value)

        // Bounce position out then back in to retrigger the inside-range path.
        position.value = 5.0
        runCurrent()
        position.value = 40.0
        runCurrent()

        assertEquals(IntroAutoSkipState.CountingDown(3), controller.state.value)
    }

    @Test
    fun `null introKey - emits Hidden`() = runTest {
        introKey.value = null
        val controller = newController()
        runCurrent()

        position.value = 35.0
        runCurrent()

        assertEquals(IntroAutoSkipState.Hidden, controller.state.value)
        advanceTimeBy(10_000)
        runCurrent()
        assertTrue(fired.isEmpty())
    }

    @Test
    fun `cancel applies only to its own intro - a new key counts down again`() = runTest {
        val controller = newController(countdown = 3)
        position.value = 35.0
        runCurrent()
        controller.cancelCountdown()
        runCurrent()
        assertEquals(IntroAutoSkipState.ShowingButton, controller.state.value)

        introKey.value = "session-1:file-2:30:90"
        runCurrent()
        assertEquals(IntroAutoSkipState.CountingDown(3), controller.state.value)
    }

    @Test
    fun `countdown is held until playback is active and starts from full once it is`() = runTest {
        playing.value = false
        val controller = newController(countdown = 3)
        position.value = 35.0
        runCurrent()
        assertEquals(IntroAutoSkipState.ShowingButton, controller.state.value)

        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(fired.isEmpty(), "must not auto-skip while playback has not started")

        playing.value = true
        runCurrent()
        assertEquals(IntroAutoSkipState.CountingDown(3), controller.state.value)

        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(listOf(introRange.end), fired)
    }

    @Test
    fun `playback going inactive mid-countdown stops the timer and restarts from full on resume`() = runTest {
        val controller = newController(countdown = 3)
        position.value = 35.0
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(IntroAutoSkipState.CountingDown(2), controller.state.value)

        playing.value = false
        runCurrent()
        assertEquals(IntroAutoSkipState.ShowingButton, controller.state.value)
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(fired.isEmpty(), "a paused countdown must not fire")

        playing.value = true
        runCurrent()
        assertEquals(IntroAutoSkipState.CountingDown(3), controller.state.value)
    }

    @Test
    fun `cancelCountdown outside an active countdown leaves the state alone`() = runTest {
        val controller = newController(countdown = 3)
        position.value = 35.0
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(listOf(introRange.end), fired)
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value)

        // Mirrors the post-fire path, where the UI cancels as the banner tears down.
        controller.cancelCountdown()
        runCurrent()
        assertEquals(IntroAutoSkipState.Hidden, controller.state.value)
    }
}
