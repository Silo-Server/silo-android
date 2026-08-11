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
import org.siloserver.silo.network.api.BrandingApi
import org.siloserver.silo.network.api.HealthApi

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

        val failure = assertFailsWith<SiloAuthUnavailableException> {
            client.post("/api/v1/watch/history")
        }
        assertEquals(SiloAuthUnavailableException.CREDENTIALS_REPUDIATED, failure.reason)

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
     * A read is NOT given an anonymous second chance. "Safe methods don't
     * change state" is false here — GET /downloads/{id}/file completes the
     * download server-side — and an optionally-authenticated read would hand
     * back GUEST data with a 200 that callers cache while the user is being
     * signed out. Genuinely public calls opt out with skipSiloAuth() and never
     * reach this path at all.
     */
    @Test
    fun anAuthenticatedReadIsNotRetriedAnonymouslyAfterRepudiation() = runTest {
        val tokenManager = repudiatedTokenManager()
        val sent = mutableListOf<Pair<String, String?>>()
        val client = repudiatingClient(tokenManager, sent)

        assertFailsWith<SiloAuthUnavailableException> {
            client.get("/api/v1/home/sections")
        }
        assertTrue(
            sent.none { it.first == "/api/v1/home/sections" },
            "an authenticated read was resent anonymously: ${sent.map { it.first }}",
        )
    }

    /**
     * The public escape hatch: a call that opted out never receives a bearer,
     * so it never enters the proactive path and a dead session elsewhere cannot
     * fail it.
     */
    @Test
    fun aPublicOptedOutReadIsUnaffectedByARepudiatedSession() = runTest {
        val tokenManager = repudiatedTokenManager()
        val sent = mutableListOf<Pair<String, String?>>()
        val client = repudiatingClient(tokenManager, sent)

        val response = client.get("/api/v1/health") { skipSiloAuth() }

        assertEquals(HttpStatusCode.OK, response.status)
        assertNull(sent.single { it.first == "/api/v1/health" }.second)
        assertTrue(
            sent.none { it.first.endsWith("/auth/refresh") },
            "an opted-out call should not have triggered a refresh at all",
        )
    }

    /**
     * A refresh service returning 5xx must not be asked twice for one request:
     * the proactive attempt fails, the request goes out and 401s, and the
     * reactive path must NOT immediately ask again.
     */
    @Test
    fun aTransientRefreshFailureIsNotImmediatelyRetriedByTheReactivePath() = runTest {
        val tokenManager = TokenManagerImpl().apply {
            setServerUrl("https://silo.example")
            saveTokens("live-access", "refresh-token", expiresIn = 0)
        }
        val sent = mutableListOf<Pair<String, String?>>()
        val client = HttpClient(
            MockEngine { request ->
                sent += request.url.encodedPath to request.headers[HttpHeaders.Authorization]
                if (request.url.encodedPath.endsWith("/auth/refresh")) {
                    respond(
                        content = """{"error":"bad_gateway"}""",
                        status = HttpStatusCode.BadGateway,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
                    respond(
                        content = """{"error":"unauthorized"}""",
                        status = HttpStatusCode.Unauthorized,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
            install(SiloAuthPlugin) { this.tokenManager = tokenManager }
        }

        client.get("/api/v1/home/sections")

        val refreshes = sent.count { it.first.endsWith("/auth/refresh") }
        assertEquals(
            1,
            refreshes,
            "one request produced $refreshes refresh attempts against a failing refresh service",
        )
    }

    /**
     * Suppressing the second refresh must not suppress RECOVERY. If another
     * request installs a working token while this one is in flight, this one
     * should retry with it — that costs no network call, and returning a stale
     * 401 while usable credentials are sitting there is just a lost request.
     */
    @Test
    fun aConcurrentlyRotatedTokenStillRecoversAfterATransientFailure() = runTest {
        val tokenManager = TokenManagerImpl().apply {
            setServerUrl("https://silo.example")
            saveTokens("stale-access", "refresh-token", expiresIn = 0)
        }
        val sent = mutableListOf<Pair<String, String?>>()
        var rotated = false
        val client = HttpClient(
            MockEngine { request ->
                sent += request.url.encodedPath to request.headers[HttpHeaders.Authorization]
                when {
                    request.url.encodedPath.endsWith("/auth/refresh") -> respond(
                        content = """{"error":"bad_gateway"}""",
                        status = HttpStatusCode.BadGateway,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )

                    request.headers[HttpHeaders.Authorization] == "Bearer stale-access" -> {
                        if (!rotated) {
                            rotated = true
                            // Stand in for a concurrent request whose refresh
                            // succeeded and installed a working token.
                            tokenManager.saveTokens("rotated-access", "rotated-refresh", 3600)
                        }
                        respond(
                            content = """{"error":"unauthorized"}""",
                            status = HttpStatusCode.Unauthorized,
                            headers = headersOf(HttpHeaders.ContentType, "application/json"),
                        )
                    }

                    else -> respond(
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

        val response = client.get("/api/v1/home/sections")

        assertEquals(
            HttpStatusCode.OK,
            response.status,
            "a usable token was already installed; the request should have retried with it",
        )
        assertEquals(
            "Bearer rotated-access",
            sent.last { it.first == "/api/v1/home/sections" }.second,
        )
        assertEquals(
            1,
            sent.count { it.first.endsWith("/auth/refresh") },
            "recovery must not cost a second refresh call",
        )
    }

    /**
     * Server-name resolution must survive a dead session. Branding is the
     * primary source since #192 and health is only its fallback, so if a
     * repudiated session could fail branding, the app would quietly go back to
     * the compatibility name that change exists to replace.
     */
    @Test
    fun serverNameProbesSurviveARepudiatedSession() = runTest {
        val tokenManager = repudiatedTokenManager()
        val sent = mutableListOf<Pair<String, String?>>()
        val client = repudiatingClient(tokenManager, sent)

        val branding = BrandingApi(client).getBranding()
        val health = HealthApi(client).checkHealth()

        assertTrue(branding is ApiResult.Success, "branding failed: $branding")
        assertTrue(health is ApiResult.Success, "health failed: $health")
        assertTrue(
            sent.filter { !it.first.endsWith("/auth/refresh") }.all { it.second == null },
            "a public identity probe carried a bearer: $sent",
        )
    }

    /**
     * A request captures its bearer before it waits on the refresh mutex. If a
     * concurrent sign-out clears the credentials in that window, the refresh
     * reports only "nothing was refreshed" — which must not be read as
     * permission to spend the bearer that no longer exists.
     */
    @Test
    fun aSignOutWhileWaitingStopsTheRequestBeingSent() = runTest {
        val tokenManager = TokenManagerImpl().apply {
            setServerUrl("https://silo.example")
            saveTokens("live-access", "refresh-token", expiresIn = 0)
        }
        val sent = mutableListOf<Pair<String, String?>>()
        val client = HttpClient(
            MockEngine { request ->
                sent += request.url.encodedPath to request.headers[HttpHeaders.Authorization]
                if (request.url.encodedPath.endsWith("/auth/refresh")) {
                    // Stand in for a concurrent sign-out landing while this
                    // request was waiting on the refresh mutex.
                    tokenManager.clearTokens()
                    respond(
                        content = """{"error":"bad_gateway"}""",
                        status = HttpStatusCode.BadGateway,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
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

        assertFailsWith<SiloAuthUnavailableException> {
            client.post("/api/v1/watch/history")
        }
        assertTrue(
            sent.none { it.first == "/api/v1/watch/history" },
            "a signed-out session still sent its request: ${sent.map { it.first }}",
        )
    }

    /**
     * The benign half: credentials rotated by someone else while we waited are
     * still usable, so the request goes out with the token that is actually
     * installed rather than the stale capture.
     */
    @Test
    fun credentialsRotatedWhileWaitingAreSpentInsteadOfTheStaleCapture() = runTest {
        val tokenManager = TokenManagerImpl().apply {
            setServerUrl("https://silo.example")
            saveTokens("stale-access", "refresh-token", expiresIn = 0)
        }
        val sent = mutableListOf<Pair<String, String?>>()
        val client = HttpClient(
            MockEngine { request ->
                sent += request.url.encodedPath to request.headers[HttpHeaders.Authorization]
                if (request.url.encodedPath.endsWith("/auth/refresh")) {
                    tokenManager.saveTokens("rotated-access", "rotated-refresh", 3600)
                    respond(
                        content = """{"error":"bad_gateway"}""",
                        status = HttpStatusCode.BadGateway,
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

        client.get("/api/v1/home/sections")

        assertEquals(
            "Bearer rotated-access",
            sent.last { it.first == "/api/v1/home/sections" }.second,
            "the request spent a token that had already been replaced",
        )
    }

    private suspend fun repudiatedTokenManager(): TokenManagerImpl =
        TokenManagerImpl().apply {
            setServerUrl("https://silo.example")
            saveTokens("live-access", "revoked-refresh", expiresIn = 0)
        }

    private fun repudiatingClient(
        tokenManager: TokenManager,
        sent: MutableList<Pair<String, String?>>,
    ): HttpClient =
        HttpClient(
            MockEngine { request ->
                sent += request.url.encodedPath to request.headers[HttpHeaders.Authorization]
                if (request.url.encodedPath.endsWith("/auth/refresh")) {
                    respond(
                        content = """{"error":"invalid_grant"}""",
                        status = HttpStatusCode.Unauthorized,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                } else {
                    // Deliberately generous: would accept an anonymous request.
                    // Body satisfies both HealthStatus and BrandingStatus so
                    // the same client can stand in for either probe.
                    respond(
                        content = """{"status":"ok","server_name":"Living Room"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
            install(SiloAuthPlugin) { this.tokenManager = tokenManager }
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
