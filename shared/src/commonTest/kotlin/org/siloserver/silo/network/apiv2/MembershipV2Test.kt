package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.network.*
import kotlin.test.*

class MembershipV2Test {
    @Test fun readsRequireTypedEntryAndOnly404MeansAbsent() = runTest {
        for (status in listOf(HttpStatusCode.OK, HttpStatusCode.NotFound, HttpStatusCode.Forbidden, HttpStatusCode.Unauthorized, HttpStatusCode.ServiceUnavailable)) {
            val client = HttpClient(MockEngine { request ->
                assertEquals(HttpMethod.Get, request.method)
                assertTrue(request.url.encodedPath in listOf("/api/v2/favorites/item", "/api/v2/watchlist/item"))
                val body = if (status == HttpStatusCode.OK) """{"item_id":"item","added_at":"2026-01-02T03:04:05.000Z"}"""
                    else """{"type":"https://siloserver.org/problems/not_found","title":"Failure","status":${status.value}}"""
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            })
            try {
                val api = MembershipV2Api(client, ApiV2Gate.Unrestricted)
                for (result in listOf(api.favorite("item"), api.watchlist("item"))) when (status) {
                    HttpStatusCode.OK -> assertEquals("item", assertIs<ApiResult.Success<MembershipEntryV2?>>(result).data?.itemId)
                    HttpStatusCode.NotFound -> assertNull(assertIs<ApiResult.Success<MembershipEntryV2?>>(result).data)
                    else -> assertEquals(status.value, assertIs<ApiResult.Error>(result).code)
                }
            } finally { client.close() }
        }
    }

    @Test fun writesAreEmpty204SingleAttemptsAndAcknowledgementsRetainAuthority() = runTest {
        var scope = AuthScopeSnapshot("server", "p1", "https://example.invalid", null, identityGeneration = 1)
        val captured = scope
        val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = scope }
        val calls = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            assertEquals(true, request.attributes.getOrNull(SingleAttemptAttributeKey))
            assertEquals(captured, request.attributes.getOrNull(AuthScopeAttributeKey))
            calls += "${request.method.value} ${request.url.encodedPath}"
            if (calls.size == 4) scope = scope.copy(profileId = "p2", identityGeneration = 2)
            respond("", HttpStatusCode.NoContent)
        })
        try {
            val api = MembershipV2Api(client, ApiV2Gate.Unrestricted, tokens)
            assertIs<ApiResult.Success<*>>(api.addFavorite("item", captured))
            assertIs<ApiResult.Success<*>>(api.removeFavorite("item", captured))
            assertIs<ApiResult.Success<*>>(api.addToWatchlist("item", captured))
            assertIs<ApiResult.Success<Unit>>(api.removeFromWatchlist("item", captured))
            assertEquals("identity_changed", assertIs<ApiResult.Error>(api.addFavorite("item", captured)).error)
            assertEquals(listOf("PUT /api/v2/favorites/item", "DELETE /api/v2/favorites/item", "PUT /api/v2/watchlist/item", "DELETE /api/v2/watchlist/item"), calls)
        } finally { client.close() }
    }

    @Test fun wrongEntryAndUnexpectedSuccessStatusAreNotAccepted() = runTest {
        val client = HttpClient(MockEngine { respond("""{"item_id":"other","added_at":"date"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) })
        try {
            val api = MembershipV2Api(client, ApiV2Gate.Unrestricted)
            assertIs<ApiResult.Error>(api.favorite("item"))
            assertFalse(api.addFavorite("item") is ApiResult.Success)
        } finally { client.close() }
    }

    @Test fun realAuthPluginRetriesDefaultReadButNeverMembershipMutation() = runTest {
        for (mutating in listOf(false, true)) {
            val tokens = TokenManagerImpl().apply {
                setServerUrl("https://example.invalid")
                setProfileId("p1")
                saveTokens("old-access", "refresh", 3600)
            }
            val paths = mutableListOf<String>()
            val client = HttpClient(MockEngine { request ->
                paths += request.url.encodedPath
                when {
                    request.url.encodedPath.endsWith("/auth/refresh") -> respond("""{"access_token":"new-access","refresh_token":"new-refresh","expires_in":3600}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    request.headers[HttpHeaders.Authorization] == "Bearer new-access" -> respond("""{"item_id":"item","added_at":"date"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
                    else -> respond("""{"type":"https://siloserver.org/problems/unauthorized","title":"Unauthorized","status":401}""", HttpStatusCode.Unauthorized, headersOf(HttpHeaders.ContentType, "application/problem+json"))
                }
            }) {
                install(ContentNegotiation) { json(SiloJson) }
                install(SiloAuthPlugin) { tokenManager = tokens }
            }
            try {
                val api = MembershipV2Api(client, ApiV2Gate.Unrestricted, tokens)
                if (mutating) {
                    val result = assertIs<ApiResult.Error>(api.addFavorite("item"))
                    assertEquals(401, result.code); assertEquals("unauthorized", result.error)
                    assertEquals(listOf("/api/v2/favorites/item"), paths)
                } else {
                    assertIs<ApiResult.Success<*>>(api.favorite("item"))
                    assertEquals(listOf("/api/v2/favorites/item", "/api/v2/auth/refresh", "/api/v2/favorites/item"), paths)
                }
            } finally { client.close() }
        }
    }

    @Test fun opaqueItemIdsAreEncodedAsOnePathSegment() = runTest {
        val paths = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            respond("""{"item_id":"movie:a/b","added_at":"2026-01-02T03:04:05.000Z"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val api = MembershipV2Api(client, ApiV2Gate.Unrestricted)
            assertEquals("movie:a/b", assertIs<ApiResult.Success<MembershipEntryV2?>>(api.favorite("movie:a/b")).data?.itemId)
            assertEquals(listOf("/api/v2/favorites/movie:a%2Fb"), paths)
        } finally { client.close() }
    }

    @Test fun singleAttemptNeverForwardsCredentialsToForeignOrigin() = runTest {
        val tokens = TokenManagerImpl().apply { setServerUrl("https://example.invalid"); setProfileId("p1"); saveTokens("access", "refresh", 3600) }
        var calls = 0
        val client = HttpClient(MockEngine { request ->
            calls++
            assertEquals("foreign.invalid", request.url.host)
            assertNull(request.headers[HttpHeaders.Authorization]); assertNull(request.headers["X-Profile-Id"])
            respond("", HttpStatusCode.Unauthorized)
        }) {
                install(ContentNegotiation) { json(SiloJson) }
                install(SiloAuthPlugin) { tokenManager = tokens }
            }
        try {
            client.put("https://foreign.invalid/api/v2/favorites/item") {
                singleAttempt(); header(HttpHeaders.Authorization, "Bearer must-not-leak"); header("X-Profile-Id", "must-not-leak")
            }
            assertEquals(1, calls)
        } finally { client.close() }
    }
}
