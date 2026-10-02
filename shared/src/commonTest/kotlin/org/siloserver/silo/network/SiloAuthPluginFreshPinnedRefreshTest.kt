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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A pinned request marked [freshSiloAuth] (a phone approving a TV on a saved
 * server, active or not) renews that scope's access token first when it is
 * expiring, through the same refresh the 401 path uses. Other pinned requests
 * keep the reactive behaviour.
 */
class SiloAuthPluginFreshPinnedRefreshTest {
    private val scope = AuthScopeSnapshot.profileless("cabin", "https://cabin.example", identityGeneration = 0L)

    private class Recorded(val path: String, val authorization: String?)

    private fun client(tokens: ScopeTokens, calls: MutableList<Recorded>) = HttpClient(
        MockEngine { request ->
            calls += Recorded(request.url.encodedPath, request.headers[HttpHeaders.Authorization])
            val json = headersOf(HttpHeaders.ContentType, "application/json")
            if (request.url.encodedPath.endsWith("/auth/refresh")) {
                respond("""{"access_token":"fresh","refresh_token":"refresh-2","expires_in":3600}""", HttpStatusCode.OK, json)
            } else {
                respond("{}", HttpStatusCode.OK, json)
            }
        },
    ) {
        install(ContentNegotiation) { json(SiloJson) }
        install(SiloAuthPlugin) { tokenManager = tokens }
    }

    @Test
    fun anExpiringScopeIsRenewedBeforeTheApproval() = runTest {
        val tokens = ScopeTokens(expiring = true)
        val calls = mutableListOf<Recorded>()
        client(tokens, calls).post("/api/v2/auth/device/approve") {
            authScope(scope)
            freshSiloAuth()
        }
        assertEquals(listOf("/api/v2/auth/refresh", "/api/v2/auth/device/approve"), calls.map { it.path })
        assertEquals(null, calls[0].authorization, "the refresh carries only its refresh token")
        assertEquals("Bearer fresh", calls[1].authorization)
        assertEquals("refresh-2", tokens.refresh, "the rotated pair is kept for that server")
        assertEquals(0, tokens.activeWrites, "the active server's slot is untouched")
    }

    @Test
    fun aFreshScopeGoesStraightOut() = runTest {
        val tokens = ScopeTokens(expiring = false)
        val calls = mutableListOf<Recorded>()
        client(tokens, calls).post("/api/v2/auth/device/approve") {
            authScope(scope)
            freshSiloAuth()
        }
        assertEquals(listOf("/api/v2/auth/device/approve"), calls.map { it.path })
        assertEquals("Bearer stale", calls[0].authorization)
    }

    @Test
    fun aSingleAttemptRequestIsRenewedFirstAndSentOnce() = runTest {
        val tokens = ScopeTokens(expiring = true)
        val calls = mutableListOf<Recorded>()
        client(tokens, calls).post("/api/v2/account/identities/link-complete") {
            authScope(scope)
            freshSiloAuth()
            singleAttempt()
        }
        assertEquals(
            listOf("/api/v2/auth/refresh", "/api/v2/account/identities/link-complete"),
            calls.map { it.path },
        )
        assertEquals("Bearer fresh", calls[1].authorization)
    }

    @Test
    fun aSingleAttemptRequestIsNotReplayedAfterA401() = runTest {
        val tokens = ScopeTokens(expiring = false)
        val calls = mutableListOf<Recorded>()
        val client = HttpClient(
            MockEngine { request ->
                calls += Recorded(request.url.encodedPath, request.headers[HttpHeaders.Authorization])
                respond("{}", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
            install(SiloAuthPlugin) { tokenManager = tokens }
        }
        client.post("/api/v2/account/identities/link-complete") {
            authScope(scope)
            freshSiloAuth()
            singleAttempt()
        }
        assertEquals(listOf("/api/v2/account/identities/link-complete"), calls.map { it.path })
    }

    /**
     * The approver's refresh is refused with 503 `provider_unavailable` (the
     * provider couldn't re-check the session under `fail_closed`) and its
     * token has expired: the approval isn't sent with a bearer that would
     * come back 401 and read as "sign in again". The session is kept.
     */
    @Test
    fun aProviderOutageOnTheApproversRefreshFailsWithItsReason() = runTest {
        val tokens = ScopeTokens(expiring = true)
        val calls = mutableListOf<Recorded>()
        val client = HttpClient(
            MockEngine { request ->
                calls += Recorded(request.url.encodedPath, request.headers[HttpHeaders.Authorization])
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
        val thrown = assertFailsWith<SiloAuthUnavailableException> {
            client.post("/api/v2/auth/device/approve") {
                authScope(scope)
                freshSiloAuth()
            }
        }
        assertTrue(SiloAuthUnavailableException.isProviderUnavailable(thrown))
        assertEquals(listOf("/api/v2/auth/refresh"), calls.map { it.path }, "the approval never went out")
        assertEquals("stale", tokens.access, "the session is kept")
        assertEquals("refresh-1", tokens.refresh)
    }

    @Test
    fun anotherRefreshFailureStillSpendsTheBearer() = runTest {
        val tokens = ScopeTokens(expiring = true)
        val calls = mutableListOf<Recorded>()
        val client = HttpClient(
            MockEngine { request ->
                calls += Recorded(request.url.encodedPath, request.headers[HttpHeaders.Authorization])
                if (request.url.encodedPath.endsWith("/auth/refresh")) {
                    respond("bad gateway", HttpStatusCode.BadGateway)
                } else {
                    respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
            install(SiloAuthPlugin) { tokenManager = tokens }
        }
        client.post("/api/v2/auth/device/approve") {
            authScope(scope)
            freshSiloAuth()
        }
        assertEquals(listOf("/api/v2/auth/refresh", "/api/v2/auth/device/approve"), calls.map { it.path })
    }

    /**
     * The scope is signed out while its refresh is in flight, and the refresh
     * fails: the approval isn't sent with the bearer captured before, which
     * may still be valid for the account that left.
     */
    @Test
    fun aScopeSignedOutDuringItsRefreshSendsNothing() = runTest {
        val tokens = ScopeTokens(expiring = true)
        val calls = mutableListOf<Recorded>()
        val client = HttpClient(
            MockEngine { request ->
                calls += Recorded(request.url.encodedPath, request.headers[HttpHeaders.Authorization])
                if (request.url.encodedPath.endsWith("/auth/refresh")) {
                    tokens.access = null
                    respond("bad gateway", HttpStatusCode.BadGateway)
                } else {
                    respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
            install(SiloAuthPlugin) { tokenManager = tokens }
        }
        assertFailsWith<SiloAuthUnavailableException> {
            client.post("/api/v2/auth/device/approve") {
                authScope(scope)
                freshSiloAuth()
            }
        }
        assertEquals(listOf("/api/v2/auth/refresh"), calls.map { it.path }, "the approval never went out")
    }

    @Test
    fun unmarkedPinnedRequestsStayReactive() = runTest {
        val tokens = ScopeTokens(expiring = true)
        val calls = mutableListOf<Recorded>()
        client(tokens, calls).post("/api/v2/progress") { authScope(scope) }
        assertEquals(listOf("/api/v2/progress"), calls.map { it.path })
    }

    /** One saved, non-active server ("cabin") whose token is or isn't expiring. */
    private class ScopeTokens(private val expiring: Boolean) : TokenManager {
        var access: String? = "stale"
        var refresh = "refresh-1"
        var activeWrites = 0

        override suspend fun accessTokenExpiresWithin(scope: AuthScopeSnapshot, marginMs: Long) =
            expiring && access == "stale"
        override suspend fun getAccessTokenForScope(scope: AuthScopeSnapshot): String? = access
        override suspend fun getRefreshTokenForScope(scope: AuthScopeSnapshot): String = refresh
        override suspend fun saveTokensForScope(
            scope: AuthScopeSnapshot,
            accessToken: String,
            refreshToken: String,
            expiresIn: Long,
        ) {
            access = accessToken
            refresh = refreshToken
        }

        override val sessionExpired = MutableSharedFlow<Unit>()
        override suspend fun getAccessToken(): String = "home-access"
        override suspend fun getRefreshToken(): String = "home-refresh"
        override suspend fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Long) {
            activeWrites++
        }
        override suspend fun clearTokens() = Unit
        override suspend fun invalidateSession() = Unit
        override suspend fun getProfileId(): String? = null
        override suspend fun setProfileId(profileId: String?) = Unit
        override suspend fun getProfileToken(): String? = null
        override suspend fun setProfileToken(token: String?) = Unit
        override suspend fun getServerUrl(): String = "https://home.example"
        override suspend fun setServerUrl(url: String) = Unit
        override suspend fun getCurrentServerId(): String = "home"
        override suspend fun switchActiveServer(serverId: String?) = Unit
        override suspend fun signOutCurrentServer() = Unit
    }
}
