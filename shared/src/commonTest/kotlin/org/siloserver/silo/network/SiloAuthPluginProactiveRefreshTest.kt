package org.siloserver.silo.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A token already past its deadline must be refreshed BEFORE it is spent.
 *
 * Every TokenManager has always recorded an expiry at save time and nothing
 * ever read it, so expiry was only discoverable by a 401: the first request
 * after the deadline paid a wasted round trip. On a live device that was 42 of
 * 351 `/home/sections` calls.
 */
class SiloAuthPluginProactiveRefreshTest {

    @Test
    fun anExpiredTokenIsRefreshedBeforeTheRequestIsSent() = runTest {
        val tokenManager = tokenManager(expiresIn = 0)
        val sent = mutableListOf<Pair<String, String?>>()
        val client = client(tokenManager, sent)

        val response = client.get("/api/v1/home/sections")

        assertEquals(HttpStatusCode.OK, response.status)
        // Refresh first, then the real call — and the real call carries the new
        // token, never the doomed one. One round trip saved.
        assertEquals(
            listOf<Pair<String, String?>>(
                "/api/v1/auth/refresh" to null,
                "/api/v1/home/sections" to "Bearer fresh-access",
            ),
            sent,
        )
        assertEquals("fresh-access", tokenManager.getAccessToken())
    }

    @Test
    fun aHealthyTokenIsSpentWithoutAnExtraRoundTrip() = runTest {
        val tokenManager = tokenManager(expiresIn = 3600)
        val sent = mutableListOf<Pair<String, String?>>()
        val client = client(tokenManager, sent)

        client.get("/api/v1/home/sections")

        assertEquals(
            listOf<Pair<String, String?>>("/api/v1/home/sections" to "Bearer live-access"),
            sent,
        )
    }

    /**
     * No credentials means nothing to refresh. Without this the plugin would
     * spend a refresh attempt on every unauthenticated call.
     */
    @Test
    fun aSignedOutClientDoesNotRefreshAtAll() = runTest {
        val tokenManager = TokenManagerImpl().apply { setServerUrl("https://silo.example") }
        val sent = mutableListOf<Pair<String, String?>>()
        val client = client(tokenManager, sent)

        client.get("/api/v1/home/sections")

        assertEquals(
            listOf<Pair<String, String?>>("/api/v1/home/sections" to null),
            sent,
        )
    }

    private suspend fun tokenManager(expiresIn: Long): TokenManagerImpl =
        TokenManagerImpl().apply {
            setServerUrl("https://silo.example")
            saveTokens(
                accessToken = "live-access",
                refreshToken = "refresh-token",
                expiresIn = expiresIn,
            )
        }

    private fun client(
        tokenManager: TokenManager,
        sent: MutableList<Pair<String, String?>>,
    ): HttpClient =
        HttpClient(
            MockEngine { request ->
                sent += request.url.encodedPath to request.headers[HttpHeaders.Authorization]
                if (request.url.encodedPath.endsWith("/auth/refresh")) {
                    respond(
                        content = """{"access_token":"fresh-access","refresh_token":"fresh-refresh","expires_in":3600}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
                    respond(
                        content = """{"sections":[]}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
            install(SiloAuthPlugin) { this.tokenManager = tokenManager }
        }
}
