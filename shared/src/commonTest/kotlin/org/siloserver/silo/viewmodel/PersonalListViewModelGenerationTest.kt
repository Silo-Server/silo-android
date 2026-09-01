package org.siloserver.silo.viewmodel

import org.siloserver.silo.model.catalog.BrowseItem
import org.siloserver.silo.model.catalog.CatalogResponse
import org.siloserver.silo.network.ApiResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A page fetched at `offset = N` describes a list that a refresh has since
 * thrown away. Gating the TRIGGERS cannot prevent this on its own — the page is
 * already in flight when the refresh starts, and nothing cancels it — so the
 * check that matters happens when the page lands.
 *
 * These lists refresh on every resume, which is exactly when a viewer comes
 * back from a detail page, so the interleaving is ordinary rather than exotic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PersonalListViewModelGenerationTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun item(id: String) = BrowseItem(contentId = id, type = "movie", title = id)

    private fun page(vararg ids: String, hasMore: Boolean = false) = ApiResult.Success(
        CatalogResponse(items = ids.map(::item), hasMore = hasMore, total = ids.size),
    )

    @Test
    fun mediaTypeParticipatesInPersonalListQueryIdentity() {
        val movies = PersonalListQuery(mediaType = "movie")
        val series = PersonalListQuery(mediaType = "series")

        assertFalse(movies.isDefault)
        assertFalse(series.isDefault)
        assertTrue(PersonalListQuery().isDefault)
        assertTrue(movies != series)
    }

    private class TestList : PersonalListViewModel(pageSize = 2) {
        val pending = ArrayDeque<CompletableDeferred<ApiResult<CatalogResponse>>>()
        val offsets = mutableListOf<Int>()
        /** The query each fetch actually went out under, in order. */
        val queries = mutableListOf<PersonalListQuery>()

        override suspend fun fetchPage(
            offset: Int,
            limit: Int,
            query: PersonalListQuery,
        ): ApiResult<CatalogResponse> {
            offsets += offset
            queries += query
            val deferred = CompletableDeferred<ApiResult<CatalogResponse>>()
            pending.addLast(deferred)
            return deferred.await()
        }

        fun start() = loadInitial()
    }

    @Test
    fun refreshDiscardsAPageThatWasAlreadyInFlight() = runTest {
        val vm = TestList()
        vm.start()
        vm.pending.removeFirst().complete(page("a", "b", hasMore = true))
        assertEquals(listOf("a", "b"), vm.uiState.value.items.map { it.contentId })

        // Page two goes out, then a resume refresh replaces the whole list.
        vm.loadMore()
        val pageTwo = vm.pending.removeFirst()
        vm.refresh()
        val refreshed = vm.pending.removeFirst()
        assertEquals(listOf(0, 2, 0), vm.offsets)

        // The refresh lands first and publishes a coherent page one.
        refreshed.complete(page("x", "y", hasMore = true))
        assertEquals(listOf("x", "y"), vm.uiState.value.items.map { it.contentId })

        // The superseded page must not append: items fetched at offset 2 of the
        // OLD list would land after "y" and leave a hole where the middle was.
        pageTwo.complete(page("c", "d"))
        assertEquals(listOf("x", "y"), vm.uiState.value.items.map { it.contentId })
        assertFalse(vm.uiState.value.isLoadingMore)
    }

    @Test
    fun aSupersededPageDoesNotPublishItsError() = runTest {
        val vm = TestList()
        vm.start()
        vm.pending.removeFirst().complete(page("a", "b", hasMore = true))

        vm.loadMore()
        val pageTwo = vm.pending.removeFirst()
        vm.refresh()
        vm.pending.removeFirst().complete(page("x", "y", hasMore = true))

        // A stale request's failure is not this list's failure. Showing it would
        // put an error banner over content that loaded perfectly well.
        pageTwo.complete(ApiResult.Error(code = 500, error = "stale", message = "stale page"))
        assertEquals(null, vm.uiState.value.error)
        assertEquals(listOf("x", "y"), vm.uiState.value.items.map { it.contentId })
        assertFalse(vm.uiState.value.isLoadingMore)
    }

    /**
     * The trigger gate only works if the state it reads has already been
     * claimed. Under a queuing dispatcher, a refresh that has not yet run its
     * own body is invisible to loadMore() — so a page goes out, captures the
     * refresh's generation once it finally runs, looks current, and appends at
     * an offset belonging to the list the refresh replaced.
     *
     * An unconfined dispatcher cannot express this: it runs refresh eagerly to
     * its first suspension, which claims the flag as a side effect and hides
     * the very ordering under test.
     */
    @Test
    fun aRefreshQueuedButNotYetRunStillBlocksPaging() = runTest {
        val scheduler = TestCoroutineScheduler()
        val queuing = StandardTestDispatcher(scheduler)
        Dispatchers.setMain(queuing)
        try {
            val vm = TestList()
            vm.start()
            scheduler.advanceUntilIdle()
            vm.pending.removeFirst().complete(page("a", "b", hasMore = true))
            scheduler.advanceUntilIdle()

            // Neither body has run yet; the gate has only the claimed state.
            vm.refresh()
            vm.loadMore()
            scheduler.advanceUntilIdle()

            assertEquals(listOf(0, 0), vm.offsets, "paging must not go out behind a queued refresh")
            vm.pending.removeFirst().complete(page("x", "y"))
            scheduler.advanceUntilIdle()
            assertEquals(listOf("x", "y"), vm.uiState.value.items.map { it.contentId })
        } finally {
            Dispatchers.setMain(dispatcher)
        }
    }

    /**
     * A reset and a refresh claim DIFFERENT flags, so neither can be trusted to
     * clear the other's on its way past. These two cover both orderings; before
     * each request released the flag it actually owned, one of them left the
     * surface spinning forever.
     */
    @Test
    fun aResetSupersededByARefreshDoesNotStrandIsLoading() = runTest {
        val vm = TestList()
        vm.start()
        vm.pending.removeFirst().complete(page("a", "b", hasMore = true))

        vm.retry()
        val staleReset = vm.pending.removeFirst()
        assertTrue(vm.uiState.value.isLoading, "the reset should have claimed isLoading")

        vm.refresh()
        vm.pending.removeFirst().complete(page("x", "y"))
        staleReset.complete(page("stale"))

        assertFalse(vm.uiState.value.isLoading, "isLoading must not outlive the reset that claimed it")
        assertFalse(vm.uiState.value.isRefreshing)
        assertEquals(listOf("x", "y"), vm.uiState.value.items.map { it.contentId })
    }

    @Test
    fun aRefreshSupersededByAResetDoesNotStrandIsRefreshing() = runTest {
        val vm = TestList()
        vm.start()
        vm.pending.removeFirst().complete(page("a", "b", hasMore = true))

        vm.refresh()
        val staleRefresh = vm.pending.removeFirst()
        assertTrue(vm.uiState.value.isRefreshing, "the refresh should have claimed isRefreshing")

        vm.retry()
        vm.pending.removeFirst().complete(page("x", "y"))
        staleRefresh.complete(page("stale"))

        assertFalse(vm.uiState.value.isRefreshing, "isRefreshing must not outlive the refresh that claimed it")
        assertFalse(vm.uiState.value.isLoading)
        assertEquals(listOf("x", "y"), vm.uiState.value.items.map { it.contentId })
    }

    /**
     * A sort/filter change reloads from zero under the new query, and does so
     * through the same generation bump every other replacement uses — so a page
     * still in flight under the OLD query cannot append its differently-ordered
     * items onto the new list.
     */
    @Test
    fun applyQueryReloadsUnderTheNewQueryAndDropsTheSupersededPage() = runTest {
        val vm = TestList()
        vm.start()
        vm.pending.removeFirst().complete(page("a", "b", hasMore = true))

        vm.loadMore()
        val stalePage = vm.pending.removeFirst()

        val sorted = PersonalListQuery(sort = "title", order = "asc")
        vm.applyQuery(sorted)
        assertEquals(listOf(0, 2, 0), vm.offsets)
        assertEquals(sorted, vm.queries.last(), "the reload must carry the new query")
        assertEquals(sorted, vm.uiState.value.query)

        vm.pending.removeFirst().complete(page("x", "y"))
        assertEquals(listOf("x", "y"), vm.uiState.value.items.map { it.contentId })

        stalePage.complete(page("c", "d"))
        assertEquals(listOf("x", "y"), vm.uiState.value.items.map { it.contentId })
        assertFalse(vm.uiState.value.isLoadingMore)

        // Re-applying the same query is a no-op — nothing re-fetches.
        vm.applyQuery(sorted)
        assertEquals(3, vm.offsets.size)
    }

    @Test
    fun anUncontestedPageStillAppends() = runTest {
        val vm = TestList()
        vm.start()
        vm.pending.removeFirst().complete(page("a", "b", hasMore = true))

        // The guard must not swallow ordinary pagination.
        vm.loadMore()
        vm.pending.removeFirst().complete(page("c", "d"))
        assertEquals(listOf("a", "b", "c", "d"), vm.uiState.value.items.map { it.contentId })
        assertFalse(vm.uiState.value.isLoadingMore)
    }
}
