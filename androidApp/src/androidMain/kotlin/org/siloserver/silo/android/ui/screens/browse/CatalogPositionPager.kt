package org.siloserver.silo.android.ui.screens.browse

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.siloserver.silo.model.catalog.BrowseItem

/**
 * The titles of a title-ordered library, read a page at a time by list
 * position and kept for the visit.
 *
 * The grid shows every position from the start and tells the pager which
 * ones are on screen ([show]). The pager reads those pages first, then one
 * page on each side and a second page in the direction the grid is moving, so
 * a page is usually there before its cards scroll into view. Reads the grid
 * has moved past are cancelled, at most [MAX_CONCURRENT_READS] run at once,
 * and a failed page is tried again after [retryDelayMillis] if it is still
 * wanted. Before a jump, [awaitLanding] reads where the jump lands so the grid
 * moves to cards rather than placeholders.
 *
 * Not thread-safe: call it from the main thread, and give it a scope that
 * runs there ([scope]); [read] may switch context itself.
 */
class CatalogPositionPager(
    /** Number of titles in the list. */
    total: Int,
    private val scope: CoroutineScope,
    private val retryDelayMillis: Long = 2_000L,
    /** Reads [limit] titles starting at list position [start]; null when the read failed. */
    private val read: suspend (start: Int, limit: Int) -> List<BrowseItem>?,
) {
    val total: Int = total.coerceAtLeast(0)

    private val _pages = MutableStateFlow<Map<Int, List<BrowseItem>>>(emptyMap())

    /** Pages that have arrived, by page number. A new map each time one arrives. */
    val pages: StateFlow<Map<Int, List<BrowseItem>>> = _pages.asStateFlow()

    private val reads = LinkedHashMap<Int, Job>()
    /** Pages whose last read failed; skipped until the retry. */
    private val failed = mutableSetOf<Int>()
    /** Bumps when a page arrives or fails, for [awaitLanding]. */
    private val settled = MutableStateFlow(0)
    private var retry: Job? = null
    /** Failures since the last page arrived; each one doubles the wait before a retry. */
    private var consecutiveFailures = 0
    private var visible: IntRange = IntRange.EMPTY
    /** Which way the grid last moved: down the list, up it, or not yet (null). */
    private var movingDown: Boolean? = null
    /** Where a jump is about to land, read ahead of the scroll. */
    private var landing: IntRange? = null
    private var cancelled = false

    /** Pages being read now, for tests. */
    internal val pagesInFlight: Set<Int> get() = reads.keys.toSet()

    /** The title at [position], or null while its page has not arrived. */
    fun item(position: Int, pages: Map<Int, List<BrowseItem>> = _pages.value): BrowseItem? {
        if (position < 0) return null
        return pages[position / PAGE_SIZE]?.getOrNull(position % PAGE_SIZE)
    }

    /** Whether every page holding [positions] has arrived. */
    fun isLoaded(positions: IntRange): Boolean = pageNumbers(positions).all { it in _pages.value }

    /**
     * Take the titles the grid already read from the top of the list so
     * their pages are not read again. Only whole pages are taken; a trailing
     * part of one is left to be read in full.
     */
    fun seed(first: List<BrowseItem>) {
        var start = 0
        val seeded = HashMap(_pages.value)
        while (start < first.size) {
            val end = minOf(start + PAGE_SIZE, first.size)
            if (end - start != PAGE_SIZE && end < total) break
            val page = start / PAGE_SIZE
            // The seed is what the grid's first page showed; a read of the
            // same page still in flight gives way to it.
            reads.remove(page)?.cancel()
            failed -= page
            seeded[page] = first.subList(start, end).toList()
            start = end
        }
        if (seeded != _pages.value) {
            _pages.value = seeded
            // A jump waiting on a seeded page rechecks now, not at its timeout.
            settled.update { it + 1 }
        }
    }

    /** The grid now shows [positions]: read their pages, then the ones beside them. */
    fun show(positions: IntRange) {
        val clamped = clamp(positions)
        if (clamped == visible) return
        if (!visible.isEmpty() && !clamped.isEmpty() && clamped.first != visible.first) {
            movingDown = clamped.first > visible.first
        }
        visible = clamped
        // A landing the grid has reached, or left, is no longer worth holding.
        landing?.let { if (isLoaded(it)) landing = null }
        pump()
    }

    /** A jump to [position] may follow: read where it would land. */
    fun prepareLanding(position: Int) {
        val range = clamp((position - LANDING_BEFORE) until (position + LANDING_AFTER))
        if (range == landing) return
        landing = range
        pump()
    }

    /**
     * Read where a jump to [position] lands and return once those titles are
     * there, their read has failed, or [patienceMillis] has passed.
     */
    suspend fun awaitLanding(position: Int, patienceMillis: Long) {
        prepareLanding(position)
        val target = landing ?: return
        withTimeoutOrNull(patienceMillis) {
            settled.first {
                cancelled || isLoaded(target) || pageNumbers(target).any { it in failed }
            }
        }
    }

    /** Stop reading; the list this pager reads is no longer shown. */
    fun cancel() {
        cancelled = true
        retry?.cancel()
        retry = null
        reads.values.forEach { it.cancel() }
        reads.clear()
        settled.update { it + 1 }
    }

    // MARK: - Reading

    private fun clamp(positions: IntRange): IntRange {
        if (total == 0 || positions.isEmpty()) return IntRange.EMPTY
        val first = positions.first.coerceIn(0, total - 1)
        val last = positions.last.coerceIn(0, total - 1)
        return if (first > last) IntRange.EMPTY else first..last
    }

    private fun pageNumbers(positions: IntRange): IntRange =
        if (positions.isEmpty()) IntRange.EMPTY else (positions.first / PAGE_SIZE)..(positions.last / PAGE_SIZE)

    /**
     * Pages worth having, most wanted first: the ones on screen, where a
     * jump would land, then the neighbours of the ones on screen, with a
     * second page ahead in the direction of travel.
     */
    internal fun wantedPages(): List<Int> {
        if (total == 0) return emptyList()
        val lastPage = (total - 1) / PAGE_SIZE
        val wanted = LinkedHashSet<Int>()
        fun want(vararg pages: Int) = pages.forEach { if (it in 0..lastPage) wanted += it }
        val shown = pageNumbers(visible)
        shown.forEach { want(it) }
        landing?.let { range -> pageNumbers(range).forEach { want(it) } }
        if (!shown.isEmpty()) {
            val above = shown.first - 1
            val below = shown.last + 1
            when (movingDown) {
                true -> want(below, above, below + 1)
                false -> want(above, below, above - 1)
                null -> want(below, above)
            }
        }
        return wanted.toList()
    }

    private fun pump() {
        if (cancelled) return
        val wanted = wantedPages()
        // Reads the grid has scrolled past give way to the ones it now needs.
        reads.keys.filter { it !in wanted }.forEach { reads.remove(it)?.cancel() }
        for (page in wanted) {
            if (reads.size >= MAX_CONCURRENT_READS) break
            if (page in _pages.value || page in reads || page in failed) continue
            start(page)
        }
    }

    private fun start(page: Int) {
        // Lazy, so the read is registered before it can finish, even on an
        // immediate dispatcher.
        lateinit var job: Job
        job = scope.launch(start = CoroutineStart.LAZY) {
            val items = try {
                read(page * PAGE_SIZE, PAGE_SIZE)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            // A cancelled or superseded read publishes nothing.
            if (cancelled || reads[page] !== job) return@launch
            reads.remove(page)
            if (items != null) {
                consecutiveFailures = 0
                _pages.update { it + (page to items) }
            } else {
                consecutiveFailures++
                failed += page
                scheduleRetry()
            }
            settled.update { it + 1 }
            pump()
        }
        reads[page] = job
        job.start()
    }

    private fun scheduleRetry() {
        if (retry != null) return
        // 2 s, 4 s, 8 s … up to MAX_RETRY_DELAY_MILLIS while the server stays
        // unreachable, so an outage does not turn into a request every 2 s.
        val backoff = (retryDelayMillis shl (consecutiveFailures - 1).coerceIn(0, 10))
            .coerceAtMost(maxOf(retryDelayMillis, MAX_RETRY_DELAY_MILLIS))
        retry = scope.launch {
            delay(backoff)
            retry = null
            failed.clear()
            pump()
        }
    }

    companion object {
        /** Titles per read: the catalog's largest page. */
        const val PAGE_SIZE = 100
        /** Reads in flight at once. */
        const val MAX_CONCURRENT_READS = 4
        /** Titles on each side of a letter's first one that a jump lands among. */
        const val LANDING_BEFORE = 12
        const val LANDING_AFTER = 48
        /** Longest wait between retries of failed pages. */
        const val MAX_RETRY_DELAY_MILLIS = 30_000L
    }
}
