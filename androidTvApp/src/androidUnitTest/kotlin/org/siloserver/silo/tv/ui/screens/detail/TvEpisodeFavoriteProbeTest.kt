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
        assertEquals(
            // Value derived from the id so identity and answer stay correlated:
            // a helper that returned 25 pairs all labelled ep1 would pass a
            // bare size check.
            episodes.associateWith { false },
            resolved.toMap(),
            "each episode must get ITS OWN answer, not a duplicate of another's",
        )
        assertEquals(6, peak.get(), "the window holds for the whole season, not just the first batch")
    }

    /**
     * One slow probe must not hold back answers that already landed. Bounding
     * the requests spreads a season over several waves, so waiting for the last
     * one is a longer wait than it used to be, not a shorter one.
     */
    @Test
    fun publishesEachAnswerAsItArrivesRatherThanWaitingForTheSlowest() = runTest(UnconfinedTestDispatcher()) {
        val published = mutableListOf<Pair<String, Boolean>>()
        val slow = CompletableDeferred<Unit>()

        val probing = async {
            probeEpisodeFavorites(
                episodeIds = listOf("fast-1", "fast-2", "slow"),
                knownIds = emptySet(),
                onResolved = { id, favorite -> published += id to favorite },
            ) { id ->
                if (id == "slow") slow.await()
                ApiResult.Success(id != "slow")
            }
        }

        assertEquals(
            listOf("fast-1" to true, "fast-2" to true),
            published.toList(),
            "the quick answers should already be published while one probe is still open",
        )

        slow.complete(Unit)
        probing.await()
        assertEquals(3, published.size)
    }

    /**
     * A failed probe publishes nothing, so a transient error cannot be
     * mistaken for "not a favourite".
     */
    @Test
    fun doesNotPublishAnythingForAFailedProbe() = runTest(UnconfinedTestDispatcher()) {
        val published = mutableListOf<String>()

        probeEpisodeFavorites(
            episodeIds = listOf("ok", "boom"),
            knownIds = emptySet(),
            onResolved = { id, _ -> published += id },
        ) { id ->
            if (id == "boom") {
                ApiResult.Error(code = 500, error = "server_error", message = "boom")
            } else {
                ApiResult.Success(true)
            }
        }

        assertEquals(listOf("ok"), published)
    }

    /**
     * Revalidation is how a favourite toggled on an episode's own screen gets
     * back to the rail: the parent view model is retained, so its answer for
     * that episode is stale but present.
     */
    @Test
    fun anEmptyKnownSetReProbesEpisodesAnAnswerIsAlreadyHeldFor() = runTest(UnconfinedTestDispatcher()) {
        val asked = mutableListOf<String>()

        val resolved = probeEpisodeFavorites(
            episodeIds = listOf("ep1", "ep2"),
            knownIds = emptySet(),
        ) { id ->
            asked += id
            ApiResult.Success(true)
        }

        assertEquals(listOf("ep1", "ep2"), asked)
        assertEquals(mapOf("ep1" to true, "ep2" to true), resolved.toMap())
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
