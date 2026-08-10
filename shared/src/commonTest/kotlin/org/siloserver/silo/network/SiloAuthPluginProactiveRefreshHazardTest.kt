package org.siloserver.silo.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two ways refreshing early can be worse than refreshing late: doing it on
 * every request, and doing it after the server has already said no.
 */
class SiloAuthPluginProactiveRefreshHazardTest {

    /**
     * A server whose access tokens are shorter than the refresh margin is
     * inside the window from the instant it issues one. Without the half-life
     * clamp every request refreshes, and every refresh rotates the refresh
     * token — a storm that invites rate limiting and turns one transient
     * rejection into a signed-out session.
     */
    @Test
    fun aServerIssuingShortTokensDoesNotRefreshOnEveryRequest() = runTest {
        val tokenManager = TokenManagerImpl().apply {
            setServerUrl("https://silo.example")
            saveTokens("live-access", "refresh-token", expiresIn = 30)
        }
        val sent = mutableListOf<Pair<String, String?>>()
        val client = client(tokenManager, sent)

        repeat(5) { client.get("/api/v1/home/sections") }

        val refreshes = sent.count { it.first.endsWith("/auth/refresh") }
        assertEquals(
            0,
            refreshes,
            "a 30s token against a 60s margin refreshed $refreshes times in 5 requests",
        )
        assertTrue(sent.all { it.second == "Bearer live-access" })
    }

    /**
     * When the refresh token has been revoked, the proactive refresh returns
     * 401 and the session is torn down. The original request must not go out at
     * all. Stripping only the bearer is not enough: an optionally-authenticated
     * endpoint would accept the anonymous remainder, turning a repudiated write
     * into a successful anonymous one. A write is the motivating case, so this
     * uses one.
     */
    @Test
    fun aRepudiatedSessionDoesNotSendTheRequestAtAll() = runTest {
        val tokenManager = TokenManagerImpl().apply {
            setServerUrl("https://silo.example")
            saveTokens("live-access", "revoked-refresh", expiresIn = 0)
            setProfileId("profile-1")
            setProfileToken("profile-token-1")
        }
        val sent = mutableListOf<Pair<String, String?>>()
        val client = HttpClient(
            MockEngine { request ->
                sent += request.url.encodedPath to request.headers[HttpHeaders.Authorization]
                if (request.url.encodedPath.endsWith("/auth/refresh")) {
                    respond(
                        content = """{"error":"invalid_grant","message":"revoked"}""",
                        status = HttpStatusCode.Unauthorized,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
                    // Deliberately generous: an endpoint that would happily
                    // accept the anonymous remainder of the request.
                    respond(
                        content = """{"ok":true}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
            install(SiloAuthPlugin) { this.tokenManager = tokenManager }
        }

        val failure = assertFailsWith<IllegalStateException> {
            client.post("/api/v1/watch/history")
        }
        assertEquals("silo_auth_credentials_repudiated", failure.message)

        assertTrue(
            sent.any { it.first.endsWith("/auth/refresh") },
            "the proactive refresh should still have been attempted",
        )
        assertTrue(
            sent.none { it.first == "/api/v1/watch/history" },
            "the repudiated write reached the server as ${sent.map { it.first }}",
        )
    }

    /**
     * The mirror of the test above. A same-origin GET that never needed the
     * bearer (health, setup status - they do not opt out of auth, the header is
     * just attached globally) must not be punished for a credential death
     * elsewhere. Credentials still come off; the request still goes.
     */
    @Test
    fun aPublicReadStillGoesOutAfterTheSessionIsRepudiated() = runTest {
        val tokenManager = TokenManagerImpl().apply {
            setServerUrl("https://silo.example")
            saveTokens("live-access", "revoked-refresh", expiresIn = 0)
        }
        val sent = mutableListOf<Pair<String, String?>>()
        val client = HttpClient(
            MockEngine { request ->
                sent += request.url.encodedPath to request.headers[HttpHeaders.Authorization]
                if (request.url.encodedPath.endsWith("/auth/refresh")) {
                    respond(
                        content = """{"error":"invalid_grant","message":"revoked"}""",
                        status = HttpStatusCode.Unauthorized,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
                    respond(
                        content = """{"status":"ok"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
            install(SiloAuthPlugin) { this.tokenManager = tokenManager }
        }

        val response = client.get("/api/v1/health")

        assertEquals(HttpStatusCode.OK, response.status)
        val health = sent.single { it.first == "/api/v1/health" }
        assertNull(health.second, "the repudiated bearer should not have been sent")
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
                        content = """{"access_token":"fresh-access","refresh_token":"fresh-refresh","expires_in":30}""",
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
