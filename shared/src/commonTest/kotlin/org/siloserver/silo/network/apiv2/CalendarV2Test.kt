package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.DefaultCalendarApi
import kotlin.test.*

class CalendarV2Test {
    private var owner = AuthScopeSnapshot("s", "p", "https://example.invalid", "pin", identityGeneration = 1)
    private val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = owner }
    private val valid = """{"events":[{"date":"2026-06-09","items":[{"content_id":"episode:2","series_id":"series:1","type":"episode","title":"Series","season_number":0,"episode_number":2,"air_date":"2026-06-09","local_air_date":"2026-06-09","air_at":"2026-06-09T12:00:00.000Z","badges":["finale"]}]}]}"""
    @Test fun exactQueryScopedEventsAndEmpty() = runTest {
        var body = valid
        val client = HttpClient(MockEngine {
            assertEquals("/api/v2/calendar", it.url.encodedPath)
            assertEquals("2026-06-08", it.url.parameters["start"]); assertEquals("2026-06-14", it.url.parameters["end"])
            assertEquals("following", it.url.parameters["filter"]); assertEquals("3", it.url.parameters["library_id"])
            assertEquals("Europe/Amsterdam", it.url.parameters["timezone"])
            assertEquals(owner, it.attributes[AuthScopeAttributeKey]); assertTrue(it.attributes[RequireSiloAuthAttributeKey])
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val api = DefaultCalendarApi(client, tokens, ApiV2Gate.Unrestricted)
            suspend fun read() = api.getCalendar("2026-06-08", "2026-06-14", "following", 3, "Europe/Amsterdam", owner)
            val event = read().getOrThrow().events.single().items.single()
            assertEquals(0, event.seasonNumber); assertEquals("series:1", event.detailContentId)
            assertEquals("2026-06-09T12:00:00.000Z", event.airAt); assertEquals(listOf("finale"), event.badges)
            body = """{"events":[]}"""; assertTrue(read().getOrThrow().events.isEmpty())
        } finally { client.close() }
    }
    @Test fun malformedWrongStatusAndChangedAuthorityRefuse() = runTest {
        var body = valid; var status = HttpStatusCode.OK; var late = false; var sends = 0
        val client = HttpClient(MockEngine {
            sends++; if (late) owner = owner.copy(profileToken = "new")
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val api = DefaultCalendarApi(client, tokens, ApiV2Gate.Unrestricted); val original = owner
            suspend fun read() = api.getCalendar("2026-06-08", "2026-06-14", owner = original)
            for (invalid in listOf("{}", valid.replace("\"items\"", "\"missing\""), valid.replace("\"episode:2\"", "\"\""))) {
                body = invalid; assertFalse(read() is ApiResult.Success)
            }
            body = valid; status = HttpStatusCode.Accepted; assertFalse(read() is ApiResult.Success)
            status = HttpStatusCode.OK; late = true; assertIs<ApiResult.Error>(read())
            val sent = sends; assertIs<ApiResult.Error>(read()); assertEquals(sent, sends)
        } finally { client.close() }
    }
}
