package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.siloserver.silo.network.*
import kotlin.test.*

class PlaybackMutationRoutingTest {
    @Test fun sessionMutationsResolveCapturedOriginThroughProductionAuthWithoutChangingBodies() = runTest {
        val owner = AuthScopeSnapshot("saved-owner", "captured-profile", "https://fixture.example:9443", "captured-profile-proof")
        val tokens = object : TokenManager by TokenManagerImpl() {
            override suspend fun getServerUrl() = "https://ambient.example:8443"
            override suspend fun getAccessTokenForScope(scope: AuthScopeSnapshot): String? {
                assertEquals(owner, scope)
                return "captured-access"
            }
        }
        val progress = PlaybackProgressV2("installation", 7, 9.0, false)
        val stop = PlaybackStopV2("installation", "retained-stop", 7, 9.0, false)
        val replan = buildJsonObject { put("installation_id", "installation"); put("replan_request_id", "retained-replan"); put("position_seconds", 9.0) }
        val expected = listOf(
            Triple(HttpMethod.Post, "/api/v2/playback/owned-session/progress", SiloJson.encodeToString(progress)),
            Triple(HttpMethod.Delete, "/api/v2/playback/owned-session", SiloJson.encodeToString(stop)),
            Triple(HttpMethod.Post, "/api/v2/playback/owned-session/replan", replan.toString()),
        )
        var calls = 0
        val client = HttpClient(MockEngine { request ->
            val (method, path, body) = expected[calls++]
            assertEquals("https://fixture.example:9443$path", request.url.toString())
            assertEquals(method, request.method)
            assertEquals(body, request.body.toByteArray().decodeToString())
            assertEquals(owner, request.attributes[AuthScopeAttributeKey])
            // Progress alone may refresh and resend once after a 401.
            assertEquals(!path.endsWith("/progress"), request.attributes.getOrNull(SingleAttemptAttributeKey) == true)
            assertEquals("Bearer captured-access", request.headers[HttpHeaders.Authorization])
            assertEquals("captured-profile", request.headers["X-Profile-Id"])
            // A refusal must surface unchanged, never trigger auth refresh or another route.
            respond("""{"code":"dependency_unavailable","detail":"Retained request remains uncertain."}""",
                HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType, "application/problem+json"))
        }) {
            install(ContentNegotiation) { json(SiloJson) }
            install(SiloAuthPlugin) { tokenManager = tokens }
        }
        try {
            val api = PlaybackV2Api(client, ApiV2Gate.Unrestricted)
            assertEquals(503, assertIs<ApiResult.Error>(api.progress(owner, "owned-session", progress)).code)
            assertEquals(503, assertIs<ApiResult.Error>(api.stop(owner, "owned-session", stop)).code)
            assertEquals(503, assertIs<ApiResult.Error>(api.replan(owner, "owned-session", replan)).code)
            assertEquals(3, calls)
        } finally { client.close() }
    }

    @Test fun progressRefreshesPinnedScopeAfter401AndResendsTheSameSample() = runTest {
        val owner = AuthScopeSnapshot("saved-owner", "captured-profile", "https://fixture.example:9443", "captured-profile-proof")
        var access = "expired-access"
        var savedScope: AuthScopeSnapshot? = null
        val tokens = object : TokenManager by TokenManagerImpl() {
            override suspend fun getServerUrl() = "https://ambient.example:8443"
            override suspend fun getAccessTokenForScope(scope: AuthScopeSnapshot): String = access
            override suspend fun getRefreshTokenForScope(scope: AuthScopeSnapshot) = "captured-refresh"
            override suspend fun saveTokensForScope(
                scope: AuthScopeSnapshot, accessToken: String, refreshToken: String, expiresIn: Long,
            ) { savedScope = scope; access = accessToken }
            override suspend fun invalidateSession() = fail("A pinned refresh must not end the active session.")
        }
        val progress = PlaybackProgressV2("installation", 7, 9.0, false)
        val bodies = mutableListOf<String>()
        val bearers = mutableListOf<String?>()
        var refreshes = 0
        val client = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v2/auth/refresh" -> {
                    refreshes++
                    respond("""{"access_token":"rotated-access","refresh_token":"rotated-refresh","expires_in":900}""",
                        HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                "/api/v2/playback/owned-session/progress" -> {
                    bodies += request.body.toByteArray().decodeToString()
                    bearers += request.headers[HttpHeaders.Authorization]
                    if (bearers.size == 1) {
                        respond("""{"code":"invalid_token","detail":"Access token expired."}""",
                            HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/problem+json"))
                    } else {
                        respond("""{"outcome":"applied","accepted":{"sequence":7,"position":9.0,"is_paused":false}}""",
                            HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }
                else -> fail("Unexpected ${request.url}")
            }
        }) {
            install(ContentNegotiation) { json(SiloJson) }
            install(SiloAuthPlugin) { tokenManager = tokens }
        }
        try {
            val receipt = assertIs<ApiResult.Success<PlaybackMutationV2>>(
                PlaybackV2Api(client, ApiV2Gate.Unrestricted).progress(owner, "owned-session", progress),
            )
            assertEquals(PlaybackMutationOutcomeV2.APPLIED, receipt.data.outcome)
            assertEquals(1, refreshes)
            assertEquals(owner, savedScope)
            assertEquals<List<String?>>(listOf("Bearer expired-access", "Bearer rotated-access"), bearers)
            val sample = SiloJson.encodeToString(progress)
            assertEquals(listOf(sample, sample), bodies)
        } finally { client.close() }
    }
}
