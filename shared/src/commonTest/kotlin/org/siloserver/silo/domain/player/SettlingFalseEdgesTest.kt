package org.siloserver.silo.domain.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SettlingFalseEdgesTest {

    private val grace = 1_500L

    @Test
    fun `resuming is reported immediately`() = runTest {
        val source = MutableStateFlow(false)
        val seen = mutableListOf<Boolean>()
        val job = launch { source.settlingFalseEdges(grace).toList(seen) }
        advanceTimeBy(grace + 1)
        runCurrent()

        source.value = true
        runCurrent()

        assertEquals(listOf(false, true), seen, "a resume must not wait out the grace period")
        job.cancel()
    }

    /**
     * The case the intro countdown cares about: a rebuffer dips isPlaying for a
     * moment, and passing that through restarts the countdown from full.
     */
    @Test
    fun `a stall shorter than the grace period is swallowed`() = runTest {
        val source = MutableStateFlow(true)
        val seen = mutableListOf<Boolean>()
        val job = launch { source.settlingFalseEdges(grace).toList(seen) }
        runCurrent()

        source.value = false
        advanceTimeBy(grace / 2)
        runCurrent()
        source.value = true
        advanceTimeBy(grace * 2)
        runCurrent()

        assertEquals(listOf(true), seen, "a brief rebuffer should never be reported as a pause")
        job.cancel()
    }

    @Test
    fun `a pause that outlasts the grace period is reported`() = runTest {
        val source = MutableStateFlow(true)
        val seen = mutableListOf<Boolean>()
        val job = launch { source.settlingFalseEdges(grace).toList(seen) }
        runCurrent()

        source.value = false
        advanceTimeBy(grace + 1)
        runCurrent()

        assertEquals(listOf(true, false), seen)
        job.cancel()
    }

    @Test
    fun `a deliberate pause is reported without waiting out the grace period`() = runTest {
        val playing = MutableStateFlow(true)
        val paused = MutableStateFlow(false)
        val seen = mutableListOf<Boolean>()
        val job = launch { playing.settlingFalseEdges(grace, paused).toList(seen) }
        runCurrent()

        // The press flips isPaused at once; isPlaying follows from the player.
        paused.value = true
        playing.value = false
        runCurrent()

        assertEquals(
            listOf(true, false),
            seen,
            "a pause must report on the press, not $grace ms later",
        )
        job.cancel()
    }

    @Test
    fun `a stall is still swallowed when the viewer has not paused`() = runTest {
        val playing = MutableStateFlow(true)
        val paused = MutableStateFlow(false)
        val seen = mutableListOf<Boolean>()
        val job = launch { playing.settlingFalseEdges(grace, paused).toList(seen) }
        runCurrent()

        playing.value = false
        advanceTimeBy(grace / 2)
        runCurrent()
        playing.value = true
        advanceTimeBy(grace * 2)
        runCurrent()

        assertEquals(listOf(true), seen, "the grace window must survive the pause bypass")
        job.cancel()
    }

    /** Repeated stutters must not accumulate into a reported pause. */
    @Test
    fun `several short stalls in a row are each swallowed`() = runTest {
        val source = MutableStateFlow(true)
        val seen = mutableListOf<Boolean>()
        val job = launch { source.settlingFalseEdges(grace).toList(seen) }
        runCurrent()

        repeat(4) {
            source.value = false
            advanceTimeBy(grace / 3)
            runCurrent()
            source.value = true
            advanceTimeBy(grace / 3)
            runCurrent()
        }

        assertEquals(listOf(true), seen)
        job.cancel()
    }
}
