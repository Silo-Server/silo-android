package org.siloserver.silo.viewmodel

import org.siloserver.silo.model.calendar.CalendarDay
import org.siloserver.silo.model.calendar.CalendarFilter
import org.siloserver.silo.model.calendar.CalendarItem
import org.siloserver.silo.model.calendar.CalendarItemType
import org.siloserver.silo.model.calendar.CalendarResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.CalendarApi
import org.siloserver.silo.repository.CalendarRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        api: FakeCalendarApi,
        today: String = "2026-06-12",
        filterStore: CalendarFilterStore = CalendarFilterStore.InMemory(),
    ) = CalendarViewModel(
        repository = CalendarRepository(api),
        timezoneId = "Europe/Amsterdam",
        todayProvider = { today },
        filterStore = filterStore,
    )

    @Test
    fun `loads the monday-anchored week containing today on init`() = runTest(dispatcher) {
        val day = CalendarDay(date = "2026-06-09", items = listOf(stubItem("m1")))
        val api = FakeCalendarApi(ApiResult.Success(CalendarResponse(listOf(day))))

        val state = viewModel(api).uiState.value

        assertFalse(state.isLoading)
        assertNull(state.error)
        assertEquals("2026-06-12", state.today)
        assertEquals("2026-06-08", state.weekStart)
        assertEquals("2026-06-14", state.weekEnd)
        assertEquals(7, state.weekDates.size)
        assertEquals("2026-06-08", state.weekDates.first())
        assertEquals("2026-06-14", state.weekDates.last())
        assertTrue(state.isCurrentWeek)
        assertEquals(listOf(stubItem("m1")), state.itemsFor("2026-06-09"))
        assertTrue(state.itemsFor("2026-06-10").isEmpty())
        assertEquals(
            listOf(CalendarCall("2026-06-08", "2026-06-14", CalendarFilter.Following, null, "Europe/Amsterdam")),
            api.calls,
        )
    }

    @Test
    fun `next and prev week shift the anchor by seven days and reload`() = runTest(dispatcher) {
        val api = FakeCalendarApi(ApiResult.Success(CalendarResponse()))
        val vm = viewModel(api)

        vm.nextWeek()
        assertEquals("2026-06-15", vm.uiState.value.weekStart)
        assertEquals("2026-06-21", vm.uiState.value.weekEnd)
        assertFalse(vm.uiState.value.isCurrentWeek)

        vm.prevWeek()
        assertEquals("2026-06-08", vm.uiState.value.weekStart)

        assertEquals(
            listOf("2026-06-08", "2026-06-15", "2026-06-08"),
            api.calls.map { it.start },
        )
    }

    @Test
    fun `goToToday returns to the current week and skips reload when already there`() = runTest(dispatcher) {
        val api = FakeCalendarApi(ApiResult.Success(CalendarResponse()))
        val vm = viewModel(api)

        vm.goToToday() // already on the current week — no extra call
        assertEquals(1, api.calls.size)

        vm.nextWeek()
        vm.goToToday()
        assertEquals("2026-06-08", vm.uiState.value.weekStart)
        assertEquals(3, api.calls.size)
    }

    @Test
    fun `setFilter triggers a reload with the new preset`() = runTest(dispatcher) {
        val api = FakeCalendarApi(ApiResult.Success(CalendarResponse()))
        val vm = viewModel(api)

        vm.setFilter(CalendarFilter.Trending)

        assertEquals(CalendarFilter.Trending, vm.uiState.value.filter)
        assertEquals(CalendarFilter.Trending, api.calls.last().filter)
        assertEquals(2, api.calls.size)

        vm.setFilter(CalendarFilter.Trending) // no-op when unchanged
        assertEquals(2, api.calls.size)
    }

    @Test
    fun `setLibrary triggers a reload scoped to the library`() = runTest(dispatcher) {
        val api = FakeCalendarApi(ApiResult.Success(CalendarResponse()))
        val vm = viewModel(api)

        vm.setLibrary(3)

        assertEquals(3, vm.uiState.value.libraryId)
        assertEquals(3, api.calls.last().libraryId)

        vm.setLibrary(null)
        assertNull(api.calls.last().libraryId)
    }

    @Test
    fun `error surfaces the server message with fallback for blank messages`() = runTest(dispatcher) {
        val api = FakeCalendarApi(ApiResult.Error(code = 500, error = "internal", message = ""))

        assertEquals("Failed to load calendar", viewModel(api).uiState.value.error)
    }

    @Test
    fun `network failure surfaces the standard network copy`() = runTest(dispatcher) {
        val api = FakeCalendarApi(ApiResult.NetworkError(IllegalStateException("offline")))

        assertEquals("Network error. Check your connection.", viewModel(api).uiState.value.error)
    }

    /**
     * Stale-response race: a slow week-A fetch must not overwrite a newer week-B result.
     *
     * Sequence:
     *  1. load() is triggered for week A — its response is gated on a CompletableDeferred
     *  2. nextWeek() triggers week B — that response returns immediately
     *  3. we release week A's deferred (simulating the slow network catching up)
     *  4. the state must keep week B's days, not week A's
     */
    @Test
    fun `stale week-A response does not overwrite newer week-B result`() = runTest(dispatcher) {
        val weekAItem = CalendarDay(date = "2026-06-09", items = listOf(stubItem("a1")))
        val weekBItem = CalendarDay(date = "2026-06-16", items = listOf(stubItem("b1")))

        val weekAGate = CompletableDeferred<Unit>()
        val gatedApi = GatedCalendarApi(
            gatedResult = ApiResult.Success(CalendarResponse(listOf(weekAItem))),
            gate = weekAGate,
            immediateResult = ApiResult.Success(CalendarResponse(listOf(weekBItem))),
        )

        val vm = CalendarViewModel(
            repository = CalendarRepository(gatedApi),
            timezoneId = "Europe/Amsterdam",
            todayProvider = { "2026-06-12" },
        )
        // vm.init triggers load for week A — it is now blocked on weekAGate

        // Advance to week B; its response returns immediately
        vm.nextWeek()

        // Confirm week B data is present
        assertEquals(listOf(weekBItem), vm.uiState.value.days)

        // Release the stale week-A response
        weekAGate.complete(Unit)

        // Week B data must still be present — stale week A must have been discarded
        assertEquals(listOf(weekBItem), vm.uiState.value.days)
        assertEquals("2026-06-15", vm.uiState.value.weekStart)
    }
    @Test
    fun `filter is read from the store on init and written on change`() = runTest(dispatcher) {
        val store = CalendarFilterStore.InMemory(CalendarFilter.Trending)
        val api = FakeCalendarApi(ApiResult.Success(CalendarResponse()))
        val vm = viewModel(api, filterStore = store)

        assertEquals(CalendarFilter.Trending, vm.uiState.value.filter)
        assertEquals(CalendarFilter.Trending, api.calls.single().filter)

        vm.setFilter(CalendarFilter.Everything)
        assertEquals(CalendarFilter.Everything, store.read())
    }

    @Test
    fun `a previously loaded week renders from cache without a loading blank`() = runTest(dispatcher) {
        val weekA = CalendarResponse(events = listOf(CalendarDay(date = "2026-06-09", items = listOf(stubItem("a")))))
        val weekB = CalendarResponse(events = listOf(CalendarDay(date = "2026-06-16", items = listOf(stubItem("b")))))
        val api = FakeCalendarApi(weekA.let { ApiResult.Success(it) })
        val vm = viewModel(api)
        assertEquals("a", vm.uiState.value.days.single().items.single().contentId)

        api.result = ApiResult.Success(weekB)
        vm.nextWeek()
        assertEquals("b", vm.uiState.value.days.single().items.single().contentId)

        // Back to week A: the API now answers something else, but the cached
        // rows show immediately and the request still goes out to revalidate.
        val gate = CompletableDeferred<Unit>()
        api.beforeAnswer = { gate.await() }
        vm.prevWeek()
        assertFalse(vm.uiState.value.isLoading)
        assertEquals("a", vm.uiState.value.days.single().items.single().contentId)
        assertEquals(3, api.calls.size)
        gate.complete(Unit)
    }

    @Test
    fun `an unseen week clears the previous rows while it loads`() = runTest(dispatcher) {
        val weekA = CalendarResponse(events = listOf(CalendarDay(date = "2026-06-09", items = listOf(stubItem("a")))))
        val api = FakeCalendarApi(ApiResult.Success(weekA))
        val vm = viewModel(api)

        val gate = CompletableDeferred<Unit>()
        api.beforeAnswer = { gate.await() }
        vm.nextWeek()
        assertTrue(vm.uiState.value.isLoading)
        assertTrue(vm.uiState.value.days.isEmpty())
        gate.complete(Unit)
    }

    @Test
    fun `a failed revalidation keeps cached rows and does not surface an error`() = runTest(dispatcher) {
        val weekA = CalendarResponse(events = listOf(CalendarDay(date = "2026-06-09", items = listOf(stubItem("a")))))
        val api = FakeCalendarApi(ApiResult.Success(weekA))
        val vm = viewModel(api)

        api.result = ApiResult.NetworkError(RuntimeException("offline"))
        vm.load()

        assertEquals("a", vm.uiState.value.days.single().items.single().contentId)
        assertNull(vm.uiState.value.error)
    }

    @Test
    fun `a week change during a refresh clears the refreshing flag`() = runTest(dispatcher) {
        val api = FakeCalendarApi(ApiResult.Success(CalendarResponse()))
        val vm = viewModel(api)

        val gate = CompletableDeferred<Unit>()
        api.beforeAnswer = { gate.await() }
        vm.refresh()
        assertTrue(vm.uiState.value.isRefreshing)

        api.beforeAnswer = {}
        vm.nextWeek()
        assertFalse(vm.uiState.value.isRefreshing)
        gate.complete(Unit)
        assertFalse(vm.uiState.value.isRefreshing)
    }

    @Test
    fun `refresh evicts the cache so a failure shows the error`() = runTest(dispatcher) {
        val weekA = CalendarResponse(events = listOf(CalendarDay(date = "2026-06-09", items = listOf(stubItem("a")))))
        val api = FakeCalendarApi(ApiResult.Success(weekA))
        val vm = viewModel(api)

        api.result = ApiResult.NetworkError(RuntimeException("offline"))
        vm.refresh()

        // The stale rows are still on screen (they were not cleared), but the
        // cache entry is gone: a later load of the same week starts blank.
        assertFalse(vm.uiState.value.isRefreshing)
        val gate = CompletableDeferred<Unit>()
        api.beforeAnswer = { gate.await() }
        vm.load()
        assertTrue(vm.uiState.value.isLoading)
        assertTrue(vm.uiState.value.days.isEmpty())
        gate.complete(Unit)
    }
}

private data class CalendarCall(
    val start: String,
    val end: String,
    val filter: String,
    val libraryId: Int?,
    val timezone: String?,
)

private class FakeCalendarApi(
    var result: ApiResult<CalendarResponse>,
) : CalendarApi {

    val calls = mutableListOf<CalendarCall>()

    /** Optional suspension point before answering, to hold a request in flight. */
    var beforeAnswer: suspend () -> Unit = {}

    override suspend fun getCalendar(
        start: String,
        end: String,
        filter: String,
        libraryId: Int?,
        timezone: String?,
    ): ApiResult<CalendarResponse> {
        calls += CalendarCall(start, end, filter, libraryId, timezone)
        beforeAnswer()
        return result
    }
}

private fun stubItem(id: String): CalendarItem = CalendarItem(
    contentId = id,
    type = CalendarItemType.Movie,
    title = "Title $id",
    airDate = "2026-06-09",
    localAirDate = "2026-06-09",
)

/**
 * A [CalendarApi] where the FIRST call suspends until [gate] is completed,
 * and every subsequent call returns [immediateResult] without suspending.
 * Used to simulate a slow in-flight request that a newer request should supersede.
 */
private class GatedCalendarApi(
    private val gatedResult: ApiResult<CalendarResponse>,
    private val gate: CompletableDeferred<Unit>,
    private val immediateResult: ApiResult<CalendarResponse>,
) : CalendarApi {

    private var callCount = 0

    override suspend fun getCalendar(
        start: String,
        end: String,
        filter: String,
        libraryId: Int?,
        timezone: String?,
    ): ApiResult<CalendarResponse> {
        val isFirst = callCount++ == 0
        return if (isFirst) {
            gate.await()
            gatedResult
        } else {
            immediateResult
        }
    }
}
