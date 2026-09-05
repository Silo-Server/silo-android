package org.siloserver.silo.network.api

import org.siloserver.silo.model.personal.ProgressListResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.SiloAuthPlugin
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.TokenManagerImpl
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PersonalDataApiTest {

    private fun api(
        status: HttpStatusCode = HttpStatusCode.OK,
        responseBody: String = "",
    ): PersonalDataApi {
        val client = HttpClient(
            MockEngine {
                respond(
                    content = responseBody,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
        }
        return PersonalDataApi(client)
    }

    private fun progressPage(ids: List<String>, nextCursor: String?): String {
        val items = ids.joinToString(",") {
            """{"media_item_id":"$it","position_seconds":1.0,"duration_seconds":10.0,"updated_at":"2026-01-01T00:00:00Z"}"""
        }
        val page = if (nextCursor == null) """{"has_more":false}""" else """{"has_more":true,"next_cursor":"$nextCursor"}"""
        return """{"items":[$items],"page":$page}"""
    }

    private fun progressApi(handler: (cursor: String?, limit: String?) -> String): PersonalDataApi {
        val client = HttpClient(
            MockEngine { request ->
                respond(
                    content = handler(request.url.parameters["cursor"], request.url.parameters["limit"]),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
        }
        return PersonalDataApi(client)
    }

    // --- listProgress ---

    @Test
    fun `listProgress walks every page in order at the server maximum page size`() = runTest {
        val limits = mutableListOf<String?>()
        val api = progressApi { cursor, limit ->
            limits += limit
            when (cursor) {
                null -> progressPage(listOf("a", "b"), nextCursor = "c1")
                "c1" -> progressPage(listOf("c"), nextCursor = "c2")
                "c2" -> progressPage(listOf("d"), nextCursor = null)
                else -> error("unexpected cursor $cursor")
            }
        }
        val result = api.listProgress()
        assertIs<ApiResult.Success<ProgressListResponse>>(result)
        assertEquals(listOf("a", "b", "c", "d"), result.data.progress.map { it.mediaItemId })
        assertEquals(listOf<String?>("200", "200", "200"), limits)
    }

    @Test
    fun `listProgress reports an error instead of a prefix when the page cap is reached`() = runTest {
        var requests = 0
        val api = progressApi { _, _ ->
            requests += 1
            progressPage(listOf("item-$requests"), nextCursor = "c$requests")
        }
        val result = api.listProgress()
        assertIs<ApiResult.Error>(result)
        assertEquals(PROGRESS_INCOMPLETE_ERROR, result.error)
        assertEquals(100, requests)
    }

    @Test
    fun `listProgress sends every page under the identity pinned at the start of the walk`() = runTest {
        val tokenManager = PinnedIdentityTokenManager()
        val profileHeaders = mutableListOf<String?>()
        val api = pinnedProgressApi(tokenManager) { request, cursor ->
            profileHeaders += request.headers["X-Profile-Id"]
            when (cursor) {
                null -> progressPage(listOf("a"), nextCursor = "c1")
                "c1" -> progressPage(listOf("b"), nextCursor = "c2")
                "c2" -> progressPage(listOf("c"), nextCursor = null)
                else -> error("unexpected cursor $cursor")
            }
        }
        val result = api.listProgress()
        assertIs<ApiResult.Success<ProgressListResponse>>(result)
        assertEquals(listOf("a", "b", "c"), result.data.progress.map { it.mediaItemId })
        assertEquals(3, profileHeaders.size)
        // The snapshot, not the live profile read, decides the header on every page.
        assertEquals(PinnedIdentityTokenManager.PINNED_PROFILE, profileHeaders.first())
        profileHeaders.forEach { assertEquals(profileHeaders.first(), it) }
    }

    @Test
    fun `listProgress aborts with identity_changed when the identity moves between pages`() = runTest {
        val tokenManager = PinnedIdentityTokenManager()
        var requests = 0
        val api = pinnedProgressApi(tokenManager) { _, _ ->
            requests += 1
            tokenManager.switchIdentity()
            progressPage(listOf("item-$requests"), nextCursor = "c$requests")
        }
        val result = api.listProgress()
        assertIs<ApiResult.Error>(result)
        assertEquals(PROGRESS_IDENTITY_CHANGED_ERROR, result.error)
        assertEquals(1, requests)
    }

    private fun pinnedProgressApi(
        tokenManager: TokenManager,
        handler: (request: HttpRequestData, cursor: String?) -> String,
    ): PersonalDataApi {
        val client = HttpClient(
            MockEngine { request ->
                respond(
                    content = handler(request, request.url.parameters["cursor"]),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
            install(SiloAuthPlugin) { this.tokenManager = tokenManager }
        }
        return PersonalDataApi(client, tokenManager = tokenManager)
    }

    /**
     * Snapshots report [PINNED_PROFILE]; the live profile read reports a
     * different id so a page resolved against the active scope is visible.
     * [switchIdentity] bumps the identity generation like a profile switch.
     */
    private class PinnedIdentityTokenManager(
        private val delegate: TokenManager = TokenManagerImpl(),
    ) : TokenManager by delegate {
        private var generation = 0L

        fun switchIdentity() {
            generation += 1
        }

        override suspend fun getServerUrl(): String = "https://server-a.example"

        override suspend fun getCurrentServerId(): String = "server-a"

        override suspend fun getAccessToken(): String = "access-token"

        override suspend fun getProfileId(): String = "live-profile"

        override suspend fun getProfileToken(): String = "live-profile-token"

        override suspend fun snapshotCurrentScope(): AuthScopeSnapshot =
            AuthScopeSnapshot(
                serverId = "server-a",
                profileId = PINNED_PROFILE,
                serverUrl = "https://server-a.example",
                profileToken = "pinned-profile-token",
                identityGeneration = generation,
                isIdentityGenerationStamped = true,
            )

        companion object {
            const val PINNED_PROFILE = "pinned-profile"
        }
    }

    // --- checkFavorite ---

    @Test
    fun `checkFavorite returns true when server responds 204`() = runTest {
        val api = api(status = HttpStatusCode.NoContent)
        val result = api.checkFavorite("item-1")
        assertIs<ApiResult.Success<Boolean>>(result)
        assertEquals(true, result.data)
    }

    @Test
    fun `checkFavorite returns false when server responds 404`() = runTest {
        val api = api(status = HttpStatusCode.NotFound)
        val result = api.checkFavorite("item-1")
        assertIs<ApiResult.Success<Boolean>>(result)
        assertEquals(false, result.data)
    }

    // --- checkWatchlist ---

    @Test
    fun `checkWatchlist returns true when server responds 204`() = runTest {
        val api = api(status = HttpStatusCode.NoContent)
        val result = api.checkWatchlist("item-2")
        assertIs<ApiResult.Success<Boolean>>(result)
        assertEquals(true, result.data)
    }

    @Test
    fun `checkWatchlist returns false when server responds 404`() = runTest {
        val api = api(status = HttpStatusCode.NotFound)
        val result = api.checkWatchlist("item-2")
        assertIs<ApiResult.Success<Boolean>>(result)
        assertEquals(false, result.data)
    }
}
