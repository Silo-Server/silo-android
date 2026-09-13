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
            assertTrue(request.attributes[SingleAttemptAttributeKey])
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
}
