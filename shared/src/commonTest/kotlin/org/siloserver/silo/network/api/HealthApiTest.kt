package org.siloserver.silo.network.api

import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.SiloJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import org.siloserver.silo.network.SiloAuthPlugin
import org.siloserver.silo.network.TokenManagerImpl
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HealthApiTest {

    @Test
    fun `checkHealth decodes health payload`() = runTest {
        val api = HealthApi(
            client(
                status = HttpStatusCode.OK,
                body = """{"status":"ok","server_name":"Silo","server_id":"srv-1"}""",
            ),
        )

        val result = api.checkHealth()

        val success = assertIs<ApiResult.Success<HealthStatus>>(result)
        assertEquals("ok", success.data.status)
        assertEquals("Silo", success.data.serverName)
        assertEquals("srv-1", success.data.serverId)
    }

    @Test
    fun `checkHealth treats html success body as network error`() = runTest {
        val api = HealthApi(
            client(
                status = HttpStatusCode.OK,
                body = "<html>proxy splash</html>",
                contentType = "text/html",
            ),
        )

        val result = api.checkHealth()

        assertIs<ApiResult.NetworkError>(result)
    }

    @Test
    fun `checkHealth returns error for gateway failure status`() = runTest {
        val api = HealthApi(
            client(
                status = HttpStatusCode.ServiceUnavailable,
                body = """{"error":"unavailable","message":"origin down"}""",
            ),
        )

        val result = api.checkHealth()

        val error = assertIs<ApiResult.Error>(result)
        assertEquals(503, error.code)
    }

    @Test
    fun `checkHealth is sent to the active server`() = runTest {
        val tokenManager = TokenManagerImpl().apply { setServerUrl("https://silo.example.com:8443") }
        var captured: HttpRequestData? = null
        val client = HttpClient(
            MockEngine { request ->
                captured = request
                respond(
                    content = """{"status":"ok"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
            install(SiloAuthPlugin) { this.tokenManager = tokenManager }
        }

        val result = HealthApi(client).checkHealth()

        assertIs<ApiResult.Success<HealthStatus>>(result)
        assertEquals("silo.example.com", captured?.url?.host)
        assertEquals(8443, captured?.url?.port)
        assertEquals("/health", captured?.url?.encodedPath)
        client.close()
    }

    private fun client(
        status: HttpStatusCode,
        body: String,
        contentType: String = "application/json",
    ): HttpClient =
        HttpClient(
            MockEngine {
                respond(
                    content = body,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, contentType),
                )
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
        }
}
