package org.siloserver.silo.network.api

import org.siloserver.silo.model.personal.ProgressListResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.SiloJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
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
