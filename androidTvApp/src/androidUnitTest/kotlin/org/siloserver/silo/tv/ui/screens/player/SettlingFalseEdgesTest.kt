package org.siloserver.silo.tv.ui.screens.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Timing contract for the playback-stall debounce that gates the intro countdown. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettlingFalseEdgesTest {

    private val grace = 750L

    @Test
    fun `true edges pass through without delay`() = runTest {
        val source = MutableStateFlow(false)
        val seen = mutableListOf<Boolean>()
        val job = launch { source.settlingFalseEdges(grace).toList(seen) }

        source.value = true
        runCurrent()

        assertEquals(listOf(true), seen)
        job.cancel()
    }

    @Test
    fun `a false blip shorter than the grace period never reaches the collector`() = runTest {
        val source = MutableStateFlow(true)
        val seen = mutableListOf<Boolean>()
        val job = launch { source.settlingFalseEdges(grace).toList(seen) }
        runCurrent()
        assertEquals(listOf(true), seen)

        // A stall: isPlaying drops and recovers inside the grace window.
        source.value = false
        testScheduler.advanceTimeBy(grace / 3)
        source.value = true
        testScheduler.advanceTimeBy(grace * 2)
        runCurrent()

        // Repeated trues are collapsed downstream; what matters is that no false
        // reaches the collector.
        assertTrue(seen.none { !it }, "a rebuffer must not surface as a pause: $seen")
        job.cancel()
    }

    @Test
    fun `a false edge held past the grace period reaches the collector`() = runTest {
        val source = MutableStateFlow(true)
        val seen = mutableListOf<Boolean>()
        val job = launch { source.settlingFalseEdges(grace).toList(seen) }
        runCurrent()

        source.value = false
        testScheduler.advanceTimeBy(grace + 1)
        runCurrent()

        assertEquals(listOf(true, false), seen, "a real pause must land after the grace period")
        job.cancel()
    }
}
