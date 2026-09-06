package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.cache.HttpCache
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.siloserver.silo.network.*
import kotlin.test.*

class FreshDiscoveryProbeTest {
    private fun TestScope.engine(handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        MockEngine(MockEngineConfig().apply { dispatcher = StandardTestDispatcher(testScheduler); addHandler(handler) })
    private val body = """{"server_version":"test","api_major":2,"contract_digest":"digest","links":{"openapi":"/api/v2/openapi.json","capabilities":"/api/v2/capabilities"}}"""
    @Test fun everyProbeHitsCapturedUrlWithoutCredentialsEvenWithHttpCache() = runTest {
        val tokens = TokenManagerImpl().apply {
            setServerUrl("https://original.invalid")
            saveTokens("access", "refresh", 0)
            setProfileIdentity("profile", "pin")
        }
        var calls = 0
        val client = HttpClient(engine { request ->
            calls++
            assertEquals("https://original.invalid/api/v2/system/info", request.url.toString())
            assertTrue(request.attributes[SkipSiloAuthAttributeKey])
            assertEquals("no-cache, no-store", request.headers[HttpHeaders.CacheControl])
            for (header in listOf(HttpHeaders.Authorization, "X-Profile-ID", "X-Profile-Token")) assertNull(request.headers[header])
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.CacheControl to listOf("public, max-age=3600"), HttpHeaders.ContentType to listOf("application/json")))
        }) { install(HttpCache); install(SiloAuthPlugin) { tokenManager = tokens } }
        try {
            val probe = ApiV2Probe(client)
            assertIs<ApiV2ProbeResult.V2>(probe.probeFresh("https://original.invalid"))
            tokens.setServerUrl("https://replacement.invalid")
            assertIs<ApiV2ProbeResult.V2>(probe.probeFresh("https://original.invalid"))
            assertEquals(2, calls)
        } finally { client.close() }
    }
    @Test fun freshReadsPreserveStrictDiscoveryFailureDistinctions() = runTest {
        var code = HttpStatusCode.OK
        var responseBody = body
        val client = HttpClient(engine { respond(responseBody, code, headersOf(HttpHeaders.ContentType, "application/json")) })
        try {
            val probe = ApiV2Probe(client)
            for (invalid in listOf("<html>proxy</html>", "{}", body.replace("\"api_major\":2", "\"api_major\":3"))) {
                responseBody = invalid
                assertEquals(ApiV2ProbeResult.Kind.MALFORMED_RESPONSE, assertIs<ApiV2ProbeResult.Failure>(probe.probeFresh("https://original.invalid")).kind)
            }
            responseBody = body
            for (status in listOf(202, 401, 403, 429, 503)) {
                code = HttpStatusCode.fromValue(status)
                assertIs<ApiV2ProbeResult.Failure>(probe.probeFresh("https://original.invalid"))
            }
            code = HttpStatusCode.NotFound; responseBody = "404 page not found\n"
            assertEquals(ApiV2ProbeResult.UpdateServer, probe.probeFresh("https://original.invalid"))
            responseBody = "<html>404</html>"
            assertEquals(ApiV2ProbeResult.Kind.UNEXPECTED_STATUS, assertIs<ApiV2ProbeResult.Failure>(probe.probeFresh("https://original.invalid")).kind)
        } finally { client.close() }
    }
    @Test fun timeoutIsBoundedAndExternalCancellationPropagates() = runTest {
        val client = HttpClient(engine { delay(60_000); respond(body) })
        try {
            val probe = ApiV2Probe(client)
            val result = probe.probeFresh("https://original.invalid")
            assertEquals(ApiV2ProbeResult.Kind.TIMEOUT, assertIs<ApiV2ProbeResult.Failure>(result).kind)
            val pending = async { probe.probeFresh("https://original.invalid") }
            pending.cancel()
            assertFailsWith<CancellationException> { pending.await() }
        } finally { client.close() }
    }
}
