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

class SiloAuthPluginRefreshFailureTest {

    @Test
    fun gatewayRefreshFailureKeepsActiveSession() = runTest {
        val tokenManager = activeTokenManager()
        val paths = mutableListOf<String>()
        val client = authClient(
            tokenManager = tokenManager,
            paths = paths,
            refreshStatus = HttpStatusCode.BadGateway,
        )

        val response = client.get("/api/v1/catalog/home")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(
            listOf("/api/v1/catalog/home", "/api/v2/auth/refresh"),
            paths,
        )
        assertEquals("expired-access", tokenManager.getAccessToken())
        assertEquals("refresh-token", tokenManager.getRefreshToken())
    }

    @Test
    fun unauthorizedRefreshFailureInvalidatesActiveSession() = runTest {
        val tokenManager = activeTokenManager()
        val client = authClient(
            tokenManager = tokenManager,
            paths = mutableListOf(),
            refreshStatus = HttpStatusCode.Unauthorized,
        )

        val response = client.get("/api/v1/catalog/home")

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(null, tokenManager.getAccessToken())
        assertEquals(null, tokenManager.getRefreshToken())
    }

    @Test
    fun signOutDuringRefreshIsNotOverwrittenBySuccessfulRefreshResponse() = runTest {
        val tokenManager = activeTokenManager()
        val client = HttpClient(
            MockEngine { request ->
                if (request.url.encodedPath.endsWith("/auth/refresh")) {
                    // Sign-out completes while the refresh round-trip is in
                    // flight (logout revokes the access token server-side, so
                    // 401-refreshes race sign-out deterministically).
                    tokenManager.clearTokens()
                    respond(
                        content = """{"access_token":"fresh-access","refresh_token":"fresh-refresh","expires_in":3600}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
                    respond(
                        content = """{"error":"unauthorized","message":"expired"}""",
                        status = HttpStatusCode.Unauthorized,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
            install(SiloAuthPlugin) { this.tokenManager = tokenManager }
        }

        client.get("/api/v1/catalog/home")

        // The successful refresh response must NOT re-persist a session the
        // user just signed out of.
        assertEquals(null, tokenManager.getAccessToken())
        assertEquals(null, tokenManager.getRefreshToken())
    }

    private suspend fun activeTokenManager(): TokenManagerImpl =
        TokenManagerImpl().apply {
            setServerUrl("https://silo.example")
            saveTokens(
                accessToken = "expired-access",
                refreshToken = "refresh-token",
                expiresIn = 3600,
            )
        }

    private fun authClient(
        tokenManager: TokenManagerImpl,
        paths: MutableList<String>,
        refreshStatus: HttpStatusCode,
    ): HttpClient =
        HttpClient(
            MockEngine { request ->
                paths += request.url.encodedPath
                val isRefresh = request.url.encodedPath.endsWith("/auth/refresh")
                val status = if (isRefresh) refreshStatus else HttpStatusCode.Unauthorized
                respond(
                    content = if (status == HttpStatusCode.BadGateway) {
                        """{"error":"bad_gateway","message":"origin down"}"""
                    } else {
                        """{"error":"unauthorized","message":"expired"}"""
                    },
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            install(ContentNegotiation) {
                json(SiloJson)
            }
            install(SiloAuthPlugin) {
                this.tokenManager = tokenManager
            }
        }
}
