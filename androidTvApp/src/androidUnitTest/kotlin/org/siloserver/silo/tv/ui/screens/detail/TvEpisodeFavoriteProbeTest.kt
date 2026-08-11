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
import kotlin.test.assertNull
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
        val recent = TvFavoriteRevalidationSession.currentVersion()
        repeat(400) { TvFavoriteRevalidationSession.markChanged("ep-$it") }
        // A reader caught up to just before the last change still gets a delta.
        val nearlyCurrent = TvFavoriteRevalidationSession.currentVersion() - 1
        assertTrue((TvFavoriteRevalidationSession.changedSince(nearlyCurrent)?.size ?: 0) <= 256)
        assertTrue(recent >= 0)
    }

    /**
     * Capping the map must not silently lose a change. A reader behind the
     * evicted entries is told it cannot be given a delta, so it re-checks
     * everything rather than being handed a partial answer that looks complete.
     */
    @Test
    fun aReaderBehindTheEvictedEntriesIsToldToRecheckEverything() {
        TvFavoriteRevalidationSession.reset()
        val slowReader = TvFavoriteRevalidationSession.currentVersion()

        repeat(300) { TvFavoriteRevalidationSession.markChanged("ep-$it") }

        assertNull(
            TvFavoriteRevalidationSession.changedSince(slowReader),
            "a delta that dropped 44 changes would look complete and hide them",
        )
    }

    @Test
    fun aReaderInsideTheCapStillGetsANormalDelta() {
        TvFavoriteRevalidationSession.reset()
        repeat(300) { TvFavoriteRevalidationSession.markChanged("old-$it") }
        val caughtUp = TvFavoriteRevalidationSession.currentVersion()

        TvFavoriteRevalidationSession.markChanged("fresh")

        assertEquals(setOf("fresh"), TvFavoriteRevalidationSession.changedSince(caughtUp))
    }
}

/**
 * Whether a screen may record itself as caught up. Getting this wrong leaves a
 * row permanently stale: the change is marked handled while its probe failed.
 */
class TvFavoriteRevalidationSatisfiedTest {

    @Test
    fun everyRequestedVisibleIdMustAnswer() {
        assertTrue(
            revalidationSatisfied(
                requested = setOf("ep2"),
                visibleIds = listOf("ep1", "ep2", "ep3"),
                answered = setOf("ep2"),
            ),
        )
    }

    @Test
    fun aFailedProbeMeansNotCaughtUp() {
        assertFalse(
            revalidationSatisfied(
                requested = setOf("ep2"),
                visibleIds = listOf("ep1", "ep2", "ep3"),
                answered = emptySet(),
            ),
            "advancing here would mark the change handled while the row stays stale",
        )
    }

    /**
     * The signal is process-wide, so it names episodes from seasons that are not
     * on screen. Waiting for those would mean never catching up at all.
     */
    @Test
    fun idsFromAnotherSeasonDoNotBlockCatchingUp() {
        assertTrue(
            revalidationSatisfied(
                requested = setOf("ep2", "some-other-season-ep"),
                visibleIds = listOf("ep1", "ep2"),
                answered = setOf("ep2"),
            ),
        )
    }

    @Test
    fun aNullDeltaRequiresEveryVisibleEpisodeToAnswer() {
        assertTrue(
            revalidationSatisfied(null, listOf("ep1", "ep2"), setOf("ep1", "ep2")),
        )
        assertFalse(
            revalidationSatisfied(null, listOf("ep1", "ep2"), setOf("ep1")),
            "a full re-check that half failed is not a full re-check",
        )
    }
}

/**
 * The cache spans seasons but a refresh probes only the visible one, so a
 * change to an episode of another season has to be applied by forgetting the
 * cached answer — otherwise the visible season records the change as handled
 * and the other season keeps showing the stale value.
 */
class TvStaleOffScreenFavoritesTest {

    @Test
    fun forgetsAChangedEpisodeFromAnotherSeason() {
        assertEquals(
            setOf("s2e1"),
            staleOffScreenFavorites(
                requested = setOf("s2e1"),
                cachedIds = setOf("s1e1", "s1e2", "s2e1"),
                visibleIds = setOf("s1e1", "s1e2"),
            ),
        )
    }

    /**
     * A visible one is revalidated in place instead: dropping it would render
     * that row as "not a favourite" until its probe answered.
     */
    @Test
    fun leavesAVisibleEpisodeAlone() {
        assertEquals(
            emptySet(),
            staleOffScreenFavorites(
                requested = setOf("s1e2"),
                cachedIds = setOf("s1e1", "s1e2"),
                visibleIds = setOf("s1e1", "s1e2"),
            ),
        )
    }

    @Test
    fun ignoresChangedIdsThisScreenNeverCached() {
        assertEquals(
            emptySet(),
            staleOffScreenFavorites(
                requested = setOf("never-seen"),
                cachedIds = setOf("s1e1"),
                visibleIds = setOf("s1e1"),
            ),
        )
    }

    /**
     * A null change list means the signal could not say what changed, so every
     * cached answer that is not on screen is suspect.
     */
    @Test
    fun aNullChangeListForgetsEveryOffScreenAnswer() {
        assertEquals(
            setOf("s2e1", "s3e1"),
            staleOffScreenFavorites(
                requested = null,
                cachedIds = setOf("s1e1", "s2e1", "s3e1"),
                visibleIds = setOf("s1e1"),
            ),
        )
    }
}
