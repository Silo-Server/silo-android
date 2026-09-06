package org.siloserver.silo.tv.ui.screens.home

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.siloserver.silo.model.calendar.*
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.CalendarApi
import org.siloserver.silo.repository.CalendarRepository
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TvUpcomingOwnershipTest {
    @Test fun changedOwnerCannotFallbackOrPublish() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val original = AuthScopeSnapshot("s", "p", "https://example.invalid", "pin", identityGeneration = 1)
        var active = original
        val calls = mutableListOf<String>()
        val api = object : CalendarApi {
            override suspend fun capture() = active
            override suspend fun current(owner: AuthScopeSnapshot) = owner == active
            override suspend fun getCalendar(start: String, end: String, filter: String, libraryId: Int?, timezone: String?, owner: AuthScopeSnapshot): ApiResult<CalendarResponse> {
                assertEquals(original, owner)
                calls += filter
                active = original.copy(profileToken = "new")
                return ApiResult.Success(CalendarResponse())
            }
        }
        val store = ViewModelStore()
        try {
            val vm = TvUpcomingViewModel(CalendarRepository(api)); store.put("upcoming", vm)
            runCurrent()
            assertEquals(listOf(CalendarFilter.Following), calls)
            assertTrue(vm.items.value.isEmpty())
        } finally { store.clear(); Dispatchers.resetMain() }
    }

    @Test fun oldResponseCannotReplaceNewRun() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val original = AuthScopeSnapshot("s", "p", "https://example.invalid", "pin", identityGeneration = 1)
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val api = object : CalendarApi {
            override suspend fun capture() = original
            override suspend fun current(owner: AuthScopeSnapshot) = owner == original
            override suspend fun getCalendar(start: String, end: String, filter: String, libraryId: Int?, timezone: String?, owner: AuthScopeSnapshot): ApiResult<CalendarResponse> {
                val first = ++calls == 1
                if (first) gate.await()
                val item = CalendarItem(if (first) "old" else "new", "movie", "Title", airDate = start, localAirDate = start)
                return ApiResult.Success(CalendarResponse(listOf(CalendarDay(start, listOf(item)))))
            }
        }
        val store = ViewModelStore()
        try {
            val vm = TvUpcomingViewModel(CalendarRepository(api)); store.put("upcoming", vm)
            runCurrent(); vm.load(); runCurrent()
            gate.complete(Unit); runCurrent()
            assertEquals("new", vm.items.value.single().contentId)
        } finally { store.clear(); Dispatchers.resetMain() }
    }
}
