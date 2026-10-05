package org.siloserver.silo.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The active session's side of [freshSiloAuth]: a TV approval from a token
 * link, or from the TV's own pair screen, goes out on the active session
 * rather than a pinned scope. When its expired token's refresh is refused
 * with 503 `provider_unavailable`, the approval isn't sent with a bearer that
 * would come back 401 and read as "sign in again"; it fails with the reason.
 */
class SiloAuthPluginFreshActiveRefreshTest {
    private suspend fun expiredSession() = TokenManagerImpl().apply {
        setServerUrl("https://silo.example")
        saveTokens(accessToken = "stale", refreshToken = "refresh-1", expiresIn = 0)
    }

    private fun client(tokens: TokenManager, paths: MutableList<String>) = HttpClient(
        MockEngine { request ->
            paths += request.url.encodedPath
            if (request.url.encodedPath.endsWith("/auth/refresh")) {
                respond(
                    """{"type":"https://siloserver.org/docs/api/v2/problems/provider_unavailable","title":"Provider unavailable",""" +
                        """"status":503,"detail":"Try again later; the session stays valid."}""",
                    HttpStatusCode.ServiceUnavailable,
                    headersOf(HttpHeaders.ContentType, "application/problem+json"),
                )
            } else {
                respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        },
    ) {
        install(ContentNegotiation) { json(SiloJson) }
        install(SiloAuthPlugin) { tokenManager = tokens }
    }

    @Test
    fun aProviderOutageFailsAFreshRequestWithItsReason() = runTest {
        val tokens = expiredSession()
        val paths = mutableListOf<String>()
        val thrown = assertFailsWith<SiloAuthUnavailableException> {
            client(tokens, paths).post("/api/v2/auth/device/approve") { freshSiloAuth() }
        }
        assertTrue(SiloAuthUnavailableException.isProviderUnavailable(thrown))
        assertEquals(listOf("/api/v2/auth/refresh"), paths, "the approval never went out")
        assertEquals("stale", tokens.getAccessToken(), "the session is kept")
        assertEquals("refresh-1", tokens.getRefreshToken())
    }

    @Test
    fun anUnmarkedRequestStillSpendsTheBearer() = runTest {
        val tokens = expiredSession()
        val paths = mutableListOf<String>()
        client(tokens, paths).post("/api/v2/progress")
        assertEquals(listOf("/api/v2/auth/refresh", "/api/v2/progress"), paths)
        assertEquals("stale", tokens.getAccessToken())
    }
}
