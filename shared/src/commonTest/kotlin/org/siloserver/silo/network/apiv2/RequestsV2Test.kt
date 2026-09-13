package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.request.CreateMediaRequest
import org.siloserver.silo.model.request.MediaRequest
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.api.DefaultRequestsApi
import kotlin.test.*

class RequestsV2Test {
    private val record = """{"id":"r1","media_type":"movie","tmdb_id":1,"title":"Film","status":"pending","outcome":"active","requested_by_user_id":"7","created_at":"2026-09-05T00:00:00.000000Z","updated_at":"2026-09-05T00:00:00.000000Z","targets":[{"id":"99","request_id":"r1","created_at":"2026-09-05T00:00:00.000000Z","updated_at":"2026-09-05T00:00:00.000000Z"}]}"""

    @Test fun stringIdentifiersDecode() {
        val decoded = SiloJson.decodeFromString<MediaRequest>(record)
        assertEquals("7", decoded.requestedByUserId)
        assertEquals("99", decoded.targets.single().id)
    }

    @Test fun walksCursorPagesWithoutOffset() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { request ->
            assertEquals("/api/v2/requests/mine", request.url.encodedPath)
            assertEquals("50", request.url.parameters["limit"])
            assertNull(request.url.parameters["offset"])
            assertEquals(if (calls == 0) null else "opaque", request.url.parameters["cursor"])
            calls++
            respond(if (calls == 1) """{"items":[$record],"page":{"has_more":true,"next_cursor":"opaque"}}"""
                else """{"items":[],"page":{"has_more":false}}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val result = assertIs<ApiResult.Success<*>>(DefaultRequestsApi(client, ApiV2Gate.Unrestricted).mine())
        assertEquals(2, calls)
        assertEquals(1, (result.data as org.siloserver.silo.model.request.RequestsListResponse).requests.size)
        client.close()
    }

    @Test fun doesNotReturnPartialListOnBrokenContinuation() = runTest {
        val client = HttpClient(MockEngine {
            respond("""{"items":[$record],"page":{"has_more":true}}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        assertEquals("requests_incomplete", assertIs<ApiResult.Error>(DefaultRequestsApi(client, ApiV2Gate.Unrestricted).mine()).error)
        client.close()
    }

    @Test fun missingEnvelopeIsAnError() = runTest {
        val client = HttpClient(MockEngine {
            respond("{}", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        assertEquals("invalid_response", assertIs<ApiResult.Error>(DefaultRequestsApi(client, ApiV2Gate.Unrestricted).mine()).error)
        client.close()
    }

    @Test fun failedCreateIsOneV2Exchange() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { request ->
            calls++
            assertEquals("/api/v2/requests", request.url.encodedPath)
            respond("""{"type":"https://siloserver.org/docs/api/v2/problems/conflict","title":"Conflict","status":409,"detail":"Already requested","instance":"urn:test"}""", HttpStatusCode.Conflict,
                headersOf(HttpHeaders.ContentType, "application/problem+json"))
        }) { install(ContentNegotiation) { json(SiloJson) } }
        assertIs<ApiResult.Error>(DefaultRequestsApi(client, ApiV2Gate.Unrestricted).create(CreateMediaRequest("movie", 1, title = "Film")))
        assertEquals(1, calls)
        client.close()
    }
}
