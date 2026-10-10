package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.*
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.shuffle.ShuffleScopeKind
import org.siloserver.silo.network.*
import kotlin.test.*

internal fun shuffleJson(
    id: String = "sh1",
    current: String = """{"content_id":"ep-1","type":"episode","title":"Pilot","series_title":"Show","season_number":1,"episode_number":1}""",
    next: String = """{"content_id":"mv-2","type":"movie","title":"Film","backdrop_url":"/art/mv-2.jpg"}""",
    scope: String = """{"kind":"season","id":"show-S1","title":"Season 1","parent_title":"Show"}""",
) = """{"id":"$id","scope":$scope,"current":$current,"next":$next,"created_at":"2026-10-05T10:00:00.000Z","updated_at":"2026-10-05T10:00:00.000Z"}"""

class ShufflesV2Test {
    private var owner = AuthScopeSnapshot("s", "p", "https://example.invalid", "pin", identityGeneration = 1)
    private val tokens = object : TokenManager by TokenManagerImpl() {
        override suspend fun snapshotCurrentScope() = owner
    }

    private fun client(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData) =
        HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json(SiloJson) } }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private fun MockRequestHandleScope.problem(status: HttpStatusCode, code: String) = respond(
        """{"type":"https://siloserver.org/docs/api/v2/problems/$code","title":"T","status":${status.value},"detail":"Nothing here can be played."}""",
        status,
        headersOf(HttpHeaders.ContentType, "application/problem+json"),
    )

    @Test fun capabilityGatesOnAvailableAndListedScopeKinds() = runTest {
        var body = """{"state":"available","allowed":true,"revision":"r","scope_kinds":["library","season"]}"""
        val c = client {
            assertEquals("/api/v2/shuffles/capabilities", it.url.encodedPath)
            assertEquals(HttpMethod.Get, it.method)
            assertEquals(owner, it.attributes[AuthScopeAttributeKey])
            json(body)
        }
        try {
            val api = ShufflesV2Api(c, tokens, ApiV2Gate.Unrestricted)
            val capability = assertIs<ApiResult.Success<org.siloserver.silo.model.shuffle.ShuffleCapability>>(api.capability()).data
            assertTrue(capability.supports(ShuffleScopeKind.LIBRARY))
            assertTrue(capability.supports(ShuffleScopeKind.SEASON))
            assertFalse(capability.supports(ShuffleScopeKind.SERIES))
            body = """{"state":"disabled","allowed":true,"revision":"r","scope_kinds":["library"]}"""
            assertFalse(assertIs<ApiResult.Success<org.siloserver.silo.model.shuffle.ShuffleCapability>>(api.capability()).data.supports(ShuffleScopeKind.LIBRARY))
            body = """{"state":"available","allowed":false,"revision":"r","scope_kinds":["library"]}"""
            assertFalse(assertIs<ApiResult.Success<org.siloserver.silo.model.shuffle.ShuffleCapability>>(api.capability()).data.supports(ShuffleScopeKind.LIBRARY))
        } finally {
            c.close()
        }
    }

    @Test fun createPostsTheScopeOnceAndExpectsCreated() = runTest {
        var sends = 0
        var status = HttpStatusCode.Created
        val c = client {
            sends++
            assertEquals("/api/v2/shuffles", it.url.encodedPath)
            assertEquals(HttpMethod.Post, it.method)
            assertEquals("large", it.url.parameters["image_size"])
            assertTrue(it.attributes[SingleAttemptAttributeKey])
            assertTrue(it.attributes[RequireSiloAuthAttributeKey])
            assertEquals("""{"scope":{"kind":"user_collection","id":"c 1"}}""", (it.body as TextContent).text)
            json(shuffleJson(), status)
        }
        try {
            val api = ShufflesV2Api(c, tokens, ApiV2Gate.Unrestricted)
            val shuffle = assertIs<ApiResult.Success<org.siloserver.silo.model.shuffle.Shuffle>>(
                api.create(ShuffleScopeKind.USER_COLLECTION, "c 1"),
            ).data
            assertEquals("sh1", shuffle.id)
            assertEquals("ep-1", shuffle.current.contentId)
            assertEquals("mv-2", shuffle.next.contentId)
            assertEquals("Show", shuffle.scope.parentTitle)
            // A 200 is not the contract's 201.
            status = HttpStatusCode.OK
            assertFalse(api.create(ShuffleScopeKind.USER_COLLECTION, "c 1") is ApiResult.Success)
            assertEquals(2, sends)
            // A blank scope never reaches the server.
            assertEquals(422, assertIs<ApiResult.Error>(api.create(ShuffleScopeKind.LIBRARY, " ")).code)
            assertEquals(2, sends)
        } finally {
            c.close()
        }
    }

    @Test fun createSurfacesNotVisibleAndNothingPlayable() = runTest {
        var status = HttpStatusCode.Conflict
        val c = client { problem(status, if (status == HttpStatusCode.Conflict) "conflict" else "not_found") }
        try {
            val api = ShufflesV2Api(c, tokens, ApiV2Gate.Unrestricted)
            val conflict = assertIs<ApiResult.Error>(api.create(ShuffleScopeKind.SEASON, "show-S1"))
            assertEquals(409, conflict.code)
            assertEquals("conflict", conflict.error)
            status = HttpStatusCode.NotFound
            assertEquals(404, assertIs<ApiResult.Error>(api.create(ShuffleScopeKind.SEASON, "show-S1")).code)
        } finally {
            c.close()
        }
    }

    @Test fun advanceSkipReadAndDeleteNameTheShuffleAndItsItem() = runTest {
        val seen = mutableListOf<String>()
        val c = client {
            val body = (it.body as? TextContent)?.text.orEmpty()
            seen += "${it.method.value} ${it.url.encodedPath} $body".trim()
            if (it.method != HttpMethod.Get) assertTrue(it.attributes[SingleAttemptAttributeKey])
            if (it.method == HttpMethod.Delete) respond("", HttpStatusCode.NoContent) else json(shuffleJson(id = "a/b"))
        }
        try {
            val api = ShufflesV2Api(c, tokens, ApiV2Gate.Unrestricted)
            assertIs<ApiResult.Success<*>>(api.get("a/b"))
            assertIs<ApiResult.Success<*>>(api.advance("a/b", "ep-1"))
            assertIs<ApiResult.Success<*>>(api.skip("a/b", "mv-2"))
            assertIs<ApiResult.Success<Unit>>(api.delete("a/b"))
            assertEquals(
                listOf(
                    "GET /api/v2/shuffles/a%2Fb",
                    """POST /api/v2/shuffles/a%2Fb/advance {"from_content_id":"ep-1"}""",
                    """POST /api/v2/shuffles/a%2Fb/skip {"next_content_id":"mv-2"}""",
                    "DELETE /api/v2/shuffles/a%2Fb",
                ),
                seen,
            )
        } finally {
            c.close()
        }
    }

    @Test fun rejectsShufflesWithoutPicksAndStaleOwners() = runTest {
        var body = shuffleJson(next = """{"content_id":"","type":"movie","title":"Film"}""")
        var late = false
        val c = client {
            if (late) owner = owner.copy(profileToken = "replacement")
            json(body)
        }
        try {
            val api = ShufflesV2Api(c, tokens, ApiV2Gate.Unrestricted)
            assertEquals("invalid_response", assertIs<ApiResult.Error>(api.get("sh1")).error)
            body = shuffleJson()
            late = true
            assertEquals("identity_changed", assertIs<ApiResult.Error>(api.get("sh1")).error)
        } finally {
            c.close()
        }
    }
}
