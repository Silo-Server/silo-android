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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * After an administrator changes an account's role, the server rejects access
 * tokens minted before the change with 401 `token_refresh_required`. The refresh
 * token still works, so the client must refresh quietly and retry once — never
 * end the session.
 */
class SiloAuthPluginTokenRefreshRequiredTest {

    private val tokenRefreshRequired =
        """{"type":"https://siloserver.org/problems/token_refresh_required","title":"Unauthorized","status":401,"detail":"Refresh the access token."}"""

    @Test
    fun `a role change refreshes and retries without signing out`() = runTest {
        val tokens = TokenManagerImpl().apply {
            setServerUrl("https://silo.example")
            saveTokens("pre-change-access", "refresh-token", 3_600)
            setProfileIdentity("kid", "pin-token")
        }
        var sessionExpiredCount = 0
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            tokens.sessionExpired.collect { sessionExpiredCount++ }
        }
        val sent = mutableListOf<Pair<String, String?>>()
        val client = HttpClient(
            MockEngine { request ->
                sent += request.url.encodedPath to request.headers[HttpHeaders.Authorization]
                when {
                    request.url.encodedPath.endsWith("/auth/refresh") -> respond(
                        content = """{"access_token":"post-change-access","refresh_token":"rotated-refresh","expires_in":3600}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                    request.headers[HttpHeaders.Authorization] == "Bearer pre-change-access" -> respond(
                        content = tokenRefreshRequired,
                        status = HttpStatusCode.Unauthorized,
                        headers = headersOf(HttpHeaders.ContentType, "application/problem+json"),
                    )
                    else -> respond(
                        content = "{}",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
            install(SiloAuthPlugin) { tokenManager = tokens }
        }

        val response = client.get("/api/v2/home/sections")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(
            listOf(
                "/api/v2/home/sections" to "Bearer pre-change-access",
                "/api/v2/auth/refresh" to null,
                "/api/v2/home/sections" to "Bearer post-change-access",
            ),
            sent,
        )
        assertEquals("post-change-access", tokens.getAccessToken())
        assertEquals("rotated-refresh", tokens.getRefreshToken())
        assertEquals("kid", tokens.getProfileId(), "the profile selection survives")
        assertEquals(0, sessionExpiredCount, "the user must not be sent to sign-in")
    }

    @Test
    fun `a pinned background request refreshes its own scope on a role change`() = runTest {
        val scope = AuthScopeSnapshot(
            serverId = "server-1",
            profileId = "kid",
            serverUrl = "https://silo.example",
            profileToken = "pin-token",
        )
        val tokens = TokenManagerImpl().apply {
            setServerUrl("https://silo.example")
            saveTokens("pre-change-access", "refresh-token", 3_600)
        }
        val client = HttpClient(
            MockEngine { request ->
                when {
                    request.url.encodedPath.endsWith("/auth/refresh") -> respond(
                        content = """{"access_token":"post-change-access","refresh_token":"rotated-refresh","expires_in":3600}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                    request.headers[HttpHeaders.Authorization] == "Bearer pre-change-access" -> respond(
                        content = tokenRefreshRequired,
                        status = HttpStatusCode.Unauthorized,
                        headers = headersOf(HttpHeaders.ContentType, "application/problem+json"),
                    )
                    else -> respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
            install(SiloAuthPlugin) { tokenManager = tokens }
        }

        val response = client.get("https://silo.example/api/v2/playback/progress") { authScope(scope) }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("post-change-access", tokens.getAccessToken())
        assertTrue(!tokens.getRefreshToken().isNullOrBlank())
    }
}
