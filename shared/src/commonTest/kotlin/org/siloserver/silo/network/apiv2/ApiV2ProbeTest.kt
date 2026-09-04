package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.network.SiloJson

/**
 * The compatibility rule, case by case. Only a plain-text 404 — what the v1
 * alpha listener's `http.NotFound` emits for an unknown `/api/v2` path — is
 * the update-server state; everything else stays its own failure kind.
 */
class ApiV2ProbeTest {

    private val infoBody = ApiV2Fixtures.body("get_system_info_ok")

    private fun client(
        handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ) = HttpClient(MockEngine { request -> handler(request) }) {
        install(ContentNegotiation) { json(SiloJson) }
    }

    private suspend fun probe(
        status: HttpStatusCode,
        body: String,
        contentType: String,
    ): ApiV2ProbeResult {
        val recorded = mutableListOf<String>()
        val client = client { request ->
            recorded += request.url.toString()
            respond(body, status, headersOf(HttpHeaders.ContentType, contentType))
        }
        val result = ApiV2Probe(client).probe("https://silo.example/")
        assertEquals(listOf("https://silo.example/api/v2/system/info"), recorded)
        return result
    }

    @Test
    fun validInfoIsV2() = runTest {
        val result = probe(HttpStatusCode.OK, infoBody, "application/json")
        val v2 = assertIs<ApiV2ProbeResult.V2>(result)
        assertEquals(2, v2.info.apiMajor)
        assertEquals("/api/v2/openapi.json", v2.info.links.openapi)
    }

    @Test
    fun plainText404IsUpdateServer() = runTest {
        val result = probe(HttpStatusCode.NotFound, "404 page not found\n", "text/plain; charset=utf-8")
        assertEquals(ApiV2ProbeResult.UpdateServer, result)
    }

    @Test
    fun html404IsNotUpdateServer() = runTest {
        val result = probe(HttpStatusCode.NotFound, "<html><body>Not Found</body></html>", "text/html")
        val failure = assertIs<ApiV2ProbeResult.Failure>(result)
        assertEquals(ApiV2ProbeResult.Kind.UNEXPECTED_STATUS, failure.kind)
        assertEquals(404, failure.status)
    }

    @Test
    fun problemJson404IsNotUpdateServer() = runTest {
        val result = probe(HttpStatusCode.NotFound, ApiV2Fixtures.body("not_found"), "application/problem+json")
        val failure = assertIs<ApiV2ProbeResult.Failure>(result)
        assertEquals(ApiV2ProbeResult.Kind.UNEXPECTED_STATUS, failure.kind)
    }

    @Test
    fun html200IsMalformedNotUpdateServer() = runTest {
        val result = probe(HttpStatusCode.OK, "<html><body>Login</body></html>", "text/html")
        val failure = assertIs<ApiV2ProbeResult.Failure>(result)
        assertEquals(ApiV2ProbeResult.Kind.MALFORMED_RESPONSE, failure.kind)
    }

    @Test
    fun malformedJsonIsMalformed() = runTest {
        val result = probe(HttpStatusCode.OK, """{"server_version": "1.0", "api_major": """, "application/json")
        assertEquals(ApiV2ProbeResult.Kind.MALFORMED_RESPONSE, assertIs<ApiV2ProbeResult.Failure>(result).kind)
    }

    @Test
    fun wrongApiMajorIsMalformed() = runTest {
        val body = infoBody.replace("\"api_major\": 2", "\"api_major\": 3")
        val result = probe(HttpStatusCode.OK, body, "application/json")
        assertEquals(ApiV2ProbeResult.Kind.MALFORMED_RESPONSE, assertIs<ApiV2ProbeResult.Failure>(result).kind)
    }

    @Test
    fun unauthorizedIsAuthentication() = runTest {
        val result = probe(HttpStatusCode.Unauthorized, ApiV2Fixtures.body("authentication_required"), "application/problem+json")
        val failure = assertIs<ApiV2ProbeResult.Failure>(result)
        assertEquals(ApiV2ProbeResult.Kind.AUTHENTICATION, failure.kind)
        assertEquals(401, failure.status)
    }

    @Test
    fun rateLimitedIsRateLimited() = runTest {
        val result = probe(HttpStatusCode.TooManyRequests, ApiV2Fixtures.body("rate_limited"), "application/problem+json")
        assertEquals(ApiV2ProbeResult.Kind.RATE_LIMITED, assertIs<ApiV2ProbeResult.Failure>(result).kind)
    }

    @Test
    fun serverErrorIsServerError() = runTest {
        val result = probe(HttpStatusCode.InternalServerError, "boom", "text/plain")
        val failure = assertIs<ApiV2ProbeResult.Failure>(result)
        assertEquals(ApiV2ProbeResult.Kind.SERVER_ERROR, failure.kind)
        assertEquals(500, failure.status)
    }

    @Test
    fun badGatewayHtmlIsServerErrorNotUpdateServer() = runTest {
        val result = probe(HttpStatusCode.BadGateway, "<html>502</html>", "text/html")
        assertEquals(ApiV2ProbeResult.Kind.SERVER_ERROR, assertIs<ApiV2ProbeResult.Failure>(result).kind)
    }

    @Test
    fun timeoutIsTimeout() = runTest {
        val client = client { throw HttpRequestTimeoutException("https://silo.example/api/v2/system/info", 30_000L) }
        val result = ApiV2Probe(client).probe("https://silo.example")
        assertEquals(ApiV2ProbeResult.Kind.TIMEOUT, assertIs<ApiV2ProbeResult.Failure>(result).kind)

        val connect = client { throw ConnectTimeoutException("connect timed out", null) }
        assertEquals(ApiV2ProbeResult.Kind.TIMEOUT, assertIs<ApiV2ProbeResult.Failure>(ApiV2Probe(connect).probe("https://silo.example")).kind)
    }

    @Test
    fun tlsOrConnectFailureIsConnection() = runTest {
        val client = client { throw IllegalStateException("SSL handshake failed: certificate unknown") }
        val result = ApiV2Probe(client).probe("https://silo.example")
        val failure = assertIs<ApiV2ProbeResult.Failure>(result)
        assertEquals(ApiV2ProbeResult.Kind.CONNECTION, failure.kind)
        assertEquals("SSL handshake failed: certificate unknown", failure.cause?.message)
    }
}
