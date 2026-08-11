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
     * that episode is stale but present. Only the changed item is re-asked
     * about — re-probing the whole season on every resume is the request volume
     * this window exists to prevent.
     */
    @Test
    fun revalidationReAsksOnlyAboutTheChangedEpisode() = runTest(UnconfinedTestDispatcher()) {
        val asked = mutableListOf<String>()
        val known = setOf("ep1", "ep2", "ep3")
        val changed = setOf("ep2")

        probeEpisodeFavorites(
            episodeIds = listOf("ep1", "ep2", "ep3"),
            knownIds = known - changed,
        ) { id ->
            asked += id
            ApiResult.Success(true)
        }

        assertEquals(listOf("ep2"), asked)
    }

    @Test
    fun aResumeThatChangedNothingProbesNothing() = runTest(UnconfinedTestDispatcher()) {
        var called = false

        probeEpisodeFavorites(
            episodeIds = listOf("ep1", "ep2", "ep3"),
            knownIds = setOf("ep1", "ep2", "ep3") - emptySet(),
        ) {
            called = true
            ApiResult.Success(true)
        }

        assertFalse(called, "foregrounding the app must not re-probe a whole season")
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

/** The versioned channel a child detail screen uses to tell every other screen. */
class TvFavoriteRevalidationSessionTest {

    @Test
    fun reportsChangesAfterAReadersMark() {
        TvFavoriteRevalidationSession.reset()
        val start = TvFavoriteRevalidationSession.currentVersion()
        TvFavoriteRevalidationSession.markChanged("ep-7")
        TvFavoriteRevalidationSession.markChanged("ep-9")

        assertEquals(setOf("ep-7", "ep-9"), TvFavoriteRevalidationSession.changedSince(start))
    }

    /**
     * The failure that made consume-once wrong: an episode screen resuming from
     * playback would swallow the marker meant for the series rail behind it.
     * Every reader must see it.
     */
    @Test
    fun oneReaderCatchingUpDoesNotHideTheChangeFromAnother() {
        TvFavoriteRevalidationSession.reset()
        val episodeScreenMark = TvFavoriteRevalidationSession.currentVersion()
        val seriesScreenMark = TvFavoriteRevalidationSession.currentVersion()
        TvFavoriteRevalidationSession.markChanged("ep-7")

        // The episode screen resumes first and catches up.
        assertEquals(
            setOf("ep-7"),
            TvFavoriteRevalidationSession.changedSince(episodeScreenMark),
        )
        // The series rail behind it must still be told.
        assertEquals(
            setOf("ep-7"),
            TvFavoriteRevalidationSession.changedSince(seriesScreenMark),
        )
    }

    /**
     * A reader advances its mark only after a successful revalidation, so a
     * failed reload retries rather than losing the change forever.
     */
    @Test
    fun aReaderThatHasNotCaughtUpKeepsSeeingTheChange() {
        TvFavoriteRevalidationSession.reset()
        val mark = TvFavoriteRevalidationSession.currentVersion()
        TvFavoriteRevalidationSession.markChanged("ep-7")

        assertEquals(setOf("ep-7"), TvFavoriteRevalidationSession.changedSince(mark))
        assertEquals(
            setOf("ep-7"),
            TvFavoriteRevalidationSession.changedSince(mark),
            "reading must not clear anything",
        )

        val caughtUp = TvFavoriteRevalidationSession.currentVersion()
        assertEquals(emptySet(), TvFavoriteRevalidationSession.changedSince(caughtUp))
    }

    @Test
    fun ignoresABlankId() {
        TvFavoriteRevalidationSession.reset()
        val start = TvFavoriteRevalidationSession.currentVersion()
        TvFavoriteRevalidationSession.markChanged("")
        assertEquals(emptySet(), TvFavoriteRevalidationSession.changedSince(start))
    }

    @Test
    fun doesNotGrowWithoutBound() {
        TvFavoriteRevalidationSession.reset()
        val start = TvFavoriteRevalidationSession.currentVersion()
        repeat(400) { TvFavoriteRevalidationSession.markChanged("ep-$it") }
        assertTrue(TvFavoriteRevalidationSession.changedSince(start).size <= 256)
    }
}
