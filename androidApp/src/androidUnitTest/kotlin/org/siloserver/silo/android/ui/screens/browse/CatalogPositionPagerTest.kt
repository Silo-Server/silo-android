package org.siloserver.silo.android.ui.screens.browse

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.android.ui.screens.browse.CatalogPositionPager.Companion.PAGE_SIZE
import org.siloserver.silo.model.catalog.BrowseItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogPositionPagerTest {
    private fun titles(start: Int, count: Int) =
        List(count) { BrowseItem(contentId = "t${start + it}", title = "Title ${start + it}", type = "movie") }

    /** Reads that answer at once, recording where each started. */
    private class Reads(private val total: Int) {
        val starts = mutableListOf<Int>()
        suspend fun read(start: Int, limit: Int): List<BrowseItem>? {
            starts += start
            val count = minOf(limit, total - start).coerceAtLeast(0)
            return List(count) { BrowseItem(contentId = "t${start + it}", title = "Title ${start + it}", type = "movie") }
        }
    }

    private fun TestScope.pager(total: Int, read: suspend (Int, Int) -> List<BrowseItem>?): CatalogPositionPager =
        CatalogPositionPager(total, backgroundScope, retryDelayMillis = 2_000L, read = read)

    @Test
    fun readsTheVisiblePagesThenOneEitherSideAndAnExtraAheadOfTheScroll() = runTest {
        val reads = Reads(total = 2_000)
        val pager = pager(2_000, reads::read)

        pager.show(250..270)
        runCurrent()
        // On screen first, then below and above: no direction yet.
        assertEquals(listOf(200, 300, 100), reads.starts)

        reads.starts.clear()
        pager.show(450..470)
        runCurrent()
        // Moving down: the page below, and a second one below that.
        assertEquals(listOf(400, 500, 600), reads.starts)

        reads.starts.clear()
        pager.show(1_250..1_270)
        pager.show(1_150..1_170)
        runCurrent()
        // Moving up: above first, then below, then a second page above.
        assertTrue(reads.starts.containsAll(listOf(1_100, 1_000, 900)), "read ${reads.starts}")
        assertEquals("t1160", pager.item(1_160)?.contentId)
        assertNull(pager.item(1_850))
    }

    @Test
    fun capsConcurrentReadsAndCancelsTheOnesTheScrollHasPassed() = runTest {
        val started = mutableListOf<Int>()
        val cancelled = mutableListOf<Int>()
        val pager = pager(10_000) { start, _ ->
            started += start
            try {
                CompletableDeferred<Unit>().await()
                null
            } finally {
                // Only a cancelled read gets here: none of them ever answers.
                cancelled += start
            }
        }

        // Six pages on screen at once (a tall tablet in compact density).
        pager.show(0..599)
        runCurrent()
        assertEquals(CatalogPositionPager.MAX_CONCURRENT_READS, pager.pagesInFlight.size)
        assertEquals(setOf(0, 1, 2, 3), pager.pagesInFlight)

        // A fling far down the list: the reads it left behind give way.
        pager.show(5_000..5_020)
        runCurrent()
        assertEquals(setOf(0, 100, 200, 300), cancelled.toSet())
        assertEquals(setOf(50, 51, 49, 52), pager.pagesInFlight)
    }

    @Test
    fun takesWholeSeededPagesSoTheFirstPageIsNotReadTwice() = runTest {
        val reads = Reads(total = 250)
        val pager = pager(250, reads::read)
        pager.seed(titles(0, 150))
        pager.show(0..20)
        runCurrent()
        // Page 0 came with the grid's first page; its trailing half page did not count.
        assertEquals(listOf(100), reads.starts)
        assertTrue(pager.isLoaded(0..199))

        val short = pager(80, Reads(total = 80)::read)
        short.seed(titles(0, 80))
        assertTrue(short.isLoaded(0..79), "a list shorter than a page is whole")
    }

    @Test
    fun aJumpWaitsForItsLandingButNoLongerThanItsPatience() = runTest {
        val quick = pager(5_000) { start, limit -> delay(120); titles(start, limit) }
        var began = currentTime
        quick.awaitLanding(position = 2_430, patienceMillis = 300)
        assertEquals(120, currentTime - began)
        assertTrue(quick.isLoaded(2_418..2_477))

        val slow = pager(5_000) { start, limit -> delay(5_000); titles(start, limit) }
        began = currentTime
        slow.awaitLanding(position = 2_430, patienceMillis = 300)
        assertEquals(300, currentTime - began)
        assertFalse(slow.isLoaded(2_430..2_430))
    }

    @Test
    fun aLandingThatSpansTwoPagesReadsBoth() = runTest {
        val reads = Reads(total = 1_000)
        val pager = pager(1_000, reads::read)
        // A letter starting at 95 lands among 83..142.
        pager.prepareLanding(95)
        runCurrent()
        assertEquals(listOf(0, 100), reads.starts)
    }

    @Test
    fun aFailedPageIsReadAgainAfterTheRetryDelay() = runTest {
        var attempts = 0
        val pager = pager(100) { start, limit ->
            attempts++
            if (attempts == 1) null else titles(start, limit)
        }
        pager.show(0..10)
        runCurrent()
        assertFalse(pager.isLoaded(0..10))
        advanceTimeBy(1_999)
        runCurrent()
        assertFalse(pager.isLoaded(0..10))
        advanceTimeBy(2)
        runCurrent()
        assertTrue(pager.isLoaded(0..10))
        assertEquals(2, attempts)
    }

    @Test
    fun retriesBackOffWhileAPageKeepsFailing() = runTest {
        val attemptsAt = mutableListOf<Long>()
        val pager = pager(100) { _, _ -> attemptsAt += currentTime; null }
        pager.show(0..10)
        advanceTimeBy(61_000)
        // 2 s, then 4, 8, 16, and never more than 30 s apart.
        val gaps = attemptsAt.zipWithNext { a, b -> b - a }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L), gaps.take(5))
        assertTrue(gaps.all { it <= CatalogPositionPager.MAX_RETRY_DELAY_MILLIS })
    }

    @Test
    fun aSeedReplacesAnOverlappingReadOfTheSamePage() = runTest {
        val gate = CompletableDeferred<Unit>()
        val pager = pager(300) { start, limit -> gate.await(); titles(start + 1_000, limit) }
        pager.show(0..10)
        runCurrent()
        assertTrue(0 in pager.pagesInFlight)
        pager.seed(titles(0, 100))
        assertFalse(0 in pager.pagesInFlight)
        gate.complete(Unit)
        runCurrent()
        // The late read of page 0 does not overwrite what the grid's first page showed.
        assertEquals("t0", pager.item(0)?.contentId)
    }

    @Test
    fun aSeedEndsAJumpWaitingForThatPage() = runTest {
        val pager = pager(300) { _, _ -> CompletableDeferred<Unit>().await(); null }
        val began = currentTime
        backgroundScope.launch { delay(40); pager.seed(titles(0, 100)) }
        pager.awaitLanding(position = 50, patienceMillis = 300)
        assertEquals(40, currentTime - began)
        assertTrue(pager.isLoaded(38..97))
    }

    @Test
    fun aThrownReadCountsAsAFailure() = runTest {
        val pager = pager(100) { _, _ -> error("boom") }
        pager.show(0..10)
        advanceTimeBy(100)
        assertFalse(pager.isLoaded(0..10))
        assertTrue(pager.pagesInFlight.isEmpty())
    }

    @Test
    fun cancelStopsEveryReadAndIgnoresLaterScrolling() = runTest {
        val started = mutableListOf<Int>()
        val pager = pager(10_000) { start, _ ->
            started += start
            CompletableDeferred<Unit>().await()
            null
        }
        pager.show(0..20)
        runCurrent()
        pager.cancel()
        assertTrue(pager.pagesInFlight.isEmpty())
        val before = started.size
        pager.show(4_000..4_020)
        runCurrent()
        assertEquals(before, started.size)
    }

    @Test
    fun anArrivingPageReplacesThePagesSnapshot() = runTest {
        val gate = CompletableDeferred<Unit>()
        val pager = pager(300) { start, limit -> gate.await(); titles(start, limit) }
        val seen = mutableListOf<Set<Int>>()
        backgroundScope.launch { pager.pages.collect { seen += it.keys } }
        pager.show(0..10)
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(setOf(0, 1), seen.last())
    }
}
