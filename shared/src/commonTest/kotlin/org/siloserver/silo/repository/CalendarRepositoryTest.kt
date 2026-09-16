package org.siloserver.silo.repository

import org.siloserver.silo.model.calendar.CalendarDay
import org.siloserver.silo.model.calendar.CalendarFilter
import org.siloserver.silo.model.calendar.CalendarResponse
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.CalendarApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CalendarRepositoryTest {

    @Test
    fun `passes query arguments through to the api`() = runTest {
        val response = CalendarResponse(events = listOf(CalendarDay(date = "2026-06-08")))
        val api = RecordingCalendarApi(ApiResult.Success(response))
        val repository = CalendarRepository(api)

        val result = repository.getCalendar(
            start = "2026-06-08",
            end = "2026-06-14",
            filter = CalendarFilter.Following,
            libraryId = 3,
            timezone = "Europe/Amsterdam",
            owner = api.capture(),
        )

        assertEquals(ApiResult.Success(response), result)
        assertEquals(
            listOf("2026-06-08|2026-06-14|following|3|Europe/Amsterdam"),
            api.calls,
        )
    }

    @Test
    fun `propagates api errors unchanged`() = runTest {
        val error = ApiResult.Error(code = 400, error = "bad_request", message = "span too large")
        val api = RecordingCalendarApi(error)
        val repository = CalendarRepository(api)

        val result = repository.getCalendar(start = "2026-06-08", end = "2026-06-14", owner = api.capture())

        assertEquals(error, result)
        assertEquals(listOf("2026-06-08|2026-06-14|all|null|null"), api.calls)
    }
}

private class RecordingCalendarApi(
    private val result: ApiResult<CalendarResponse>,
) : CalendarApi {
    var owner = AuthScopeSnapshot("server", "profile", "https://example.invalid", "pin", identityGeneration = 1)
    override suspend fun capture() = owner
    override suspend fun current(owner: AuthScopeSnapshot) = this.owner == owner


    val calls = mutableListOf<String>()

    override suspend fun getCalendar(
        start: String,
        end: String,
        filter: String,
        libraryId: Int?,
        timezone: String?,
        owner: AuthScopeSnapshot,
    ): ApiResult<CalendarResponse> {
        calls += "$start|$end|$filter|$libraryId|$timezone"
        return result
    }
}
