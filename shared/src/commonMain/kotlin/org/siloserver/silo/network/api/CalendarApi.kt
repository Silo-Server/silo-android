package org.siloserver.silo.network.api

import org.siloserver.silo.model.calendar.CalendarFilter
import org.siloserver.silo.model.calendar.CalendarResponse
import org.siloserver.silo.network.ApiResult
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.*
import kotlinx.serialization.json.*
import org.siloserver.silo.network.*
import org.siloserver.silo.network.apiv2.*

/**
 * Calendar / upcoming endpoint. Kept behind an interface so repository and
 * ViewModel tests can fake the transport, matching the RequestsApi shape.
 */
interface CalendarApi {
    suspend fun capture(): AuthScopeSnapshot?
    suspend fun current(owner: AuthScopeSnapshot): Boolean


    /**
     * GET /api/v2/calendar — max 31-day span. Dates are ISO "YYYY-MM-DD";
     * [timezone] is an IANA id used by the server to compute local air dates.
     */
    suspend fun getCalendar(
        start: String,
        end: String,
        filter: String = CalendarFilter.All,
        libraryId: Int? = null,
        timezone: String? = null,
        owner: AuthScopeSnapshot,
    ): ApiResult<CalendarResponse>
}

class DefaultCalendarApi(private val client: HttpClient, private val tokens: TokenManager,
    private val gate: ApiV2Gate = ApiV2Gate.Unrestricted) : CalendarApi {
    override suspend fun capture() = tokens.snapshotCurrentScope()?.takeIf { !it.profileId.isNullOrBlank() }
    override suspend fun current(owner: AuthScopeSnapshot): Boolean {
        val now = capture()
        return owner.isSameIdentityAs(now) && owner.serverUrl == now?.serverUrl && owner.profileId == now?.profileId &&
            owner.profileToken == now?.profileToken && owner.credentialGenerationId == now?.credentialGenerationId
    }


    override suspend fun getCalendar(
        start: String,
        end: String,
        filter: String,
        libraryId: Int?,
        timezone: String?,
        owner: AuthScopeSnapshot,
    ): ApiResult<CalendarResponse> {
        if (!current(owner)) return changed()
        val result = safeApiV2Call<JsonObject>(gate) {
            client.get("/api/v2/calendar") {
                authScope(owner); requireSiloAuth()
                parameter("start", start)
                parameter("end", end)
                parameter("filter", filter)
                libraryId?.let { parameter("library_id", it) }
                timezone?.let { parameter("timezone", it) }
            }.also { check(!it.status.isSuccess() || it.status == HttpStatusCode.OK) }
        }
        if (!current(owner)) return changed()
        return when (result) {
            is ApiResult.Success -> try {
                val days = result.data["events"] as? JsonArray ?: error("Missing events")
                days.forEach { day ->
                    val items = day.jsonObject["items"] as? JsonArray ?: error("Missing day items")
                    items.forEach { item ->
                        val id = item.jsonObject["content_id"] as? JsonPrimitive
                        check(id?.isString == true && id.content.isNotBlank())
                        val series = item.jsonObject["series_id"]
                        check(series == null || series == JsonNull || (series is JsonPrimitive && series.isString))
                    }
                }
                ApiResult.Success(SiloJson.decodeFromJsonElement<CalendarResponse>(result.data))
            } catch (_: Exception) { ApiResult.Error(0, "invalid_calendar", "The server returned unsupported calendar events.") }
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }
    private fun changed() = ApiResult.Error(0, "calendar_authority_changed", "The initiating calendar identity changed.")
}
