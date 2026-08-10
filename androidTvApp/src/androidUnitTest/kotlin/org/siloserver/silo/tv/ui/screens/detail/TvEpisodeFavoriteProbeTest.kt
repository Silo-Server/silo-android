package org.siloserver.silo.tv.ui.screens.detail

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.network.ApiResult
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the episode favourite probe window. A season used to put every
 * `GET /favorites/{id}` on the wire at once and re-ask on every season load;
 * one series on a tester's Fire TV produced 116 such probes at 150-520 ms each.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TvEpisodeFavoriteProbeTest {

    @Test
    fun probesOnlyEpisodesWithNoAnswerYet() = runTest(UnconfinedTestDispatcher()) {
        val asked = mutableListOf<String>()

        val resolved = probeEpisodeFavorites(
            episodeIds = listOf("ep1", "ep2", "ep3"),
            knownIds = setOf("ep1", "ep3"),
        ) { id ->
            asked += id
            ApiResult.Success(true)
        }

        assertEquals(listOf("ep2"), asked)
        assertEquals(listOf("ep2" to true), resolved)
    }

    @Test
    fun asksNothingWhenEveryEpisodeIsAlreadyKnown() = runTest(UnconfinedTestDispatcher()) {
        var called = false

        val resolved = probeEpisodeFavorites(
            episodeIds = listOf("ep1", "ep2"),
            knownIds = setOf("ep1", "ep2"),
        ) {
            called = true
            ApiResult.Success(true)
        }

        assertTrue(resolved.isEmpty())
        assertFalse(called, "a season whose answers are all on screen should not touch the network")
    }

    @Test
    fun keepsAtMostTheConfiguredNumberOfProbesInFlight() = runTest(UnconfinedTestDispatcher()) {
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val release = CompletableDeferred<Unit>()

        val episodes = (1..25).map { "ep$it" }
        val probing = async {
            probeEpisodeFavorites(episodes, knownIds = emptySet(), concurrency = 6) {
                val now = inFlight.incrementAndGet()
                peak.updateAndGet { seen -> maxOf(seen, now) }
                release.await()
                inFlight.decrementAndGet()
                ApiResult.Success(false)
            }
        }

        // Every probe that may start has started and is parked on `release`.
        assertEquals(6, peak.get(), "a 25-episode season must not open more than the permitted window")

        release.complete(Unit)
        val resolved = probing.await()

        assertEquals(25, resolved.size, "every episode still gets an answer")
        assertEquals(6, peak.get(), "the window holds for the whole season, not just the first batch")
    }

    @Test
    fun leavesAFailedProbeUnrecordedRatherThanCachingItAsNotFavourite() = runTest(UnconfinedTestDispatcher()) {
        val resolved = probeEpisodeFavorites(
            episodeIds = listOf("ok", "boom"),
            knownIds = emptySet(),
        ) { id ->
            if (id == "boom") {
                ApiResult.Error(code = 500, error = "server_error", message = "boom")
            } else {
                ApiResult.Success(true)
            }
        }

        assertEquals(listOf("ok" to true), resolved)
        assertFalse(
            resolved.any { it.first == "boom" },
            "a transient failure must not stick as a cached 'not a favourite'",
        )
    }
}
