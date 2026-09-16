package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.network.*
import kotlin.test.*

class HistoryV2Test {
    private fun item(id: String, source: String = "playback") = """{"content_id":"$id","type":"series","title":"Series","watch":{"media_item_id":"episode-1","watched_at":"2026-01-02T03:04:05.000Z","duration_seconds":120.5,"completed":true,"source":"$source"}}"""
    private fun page(items: String, next: String? = null) = """{"items":[$items],"page":{"has_more":${next != null}${next?.let { ",\"next_cursor\":\"$it\"" } ?: ""}}}"""

    @Test fun emptyAndDuplicatePagesAdvanceByServerCursorAndPreserveWatch() = runTest {
        val cursors = mutableListOf<String?>()
        val client = HttpClient(MockEngine { request ->
            assertEquals("/api/v2/history", request.url.encodedPath)
            assertEquals("40", request.url.parameters["limit"])
            assertNull(request.url.parameters["offset"])
            assertNull(request.url.parameters["window_cursor"])
            val cursor = request.url.parameters["cursor"]
            cursors += cursor
            val body = when (cursor) {
                null -> page(item("series-1", "future-source"), "empty")
                "empty" -> page("", "duplicate")
                "duplicate" -> page(item("series-1"), "last")
                "last" -> page(item("series-2"))
                else -> error("Unexpected cursor")
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val api = HistoryV2Api(client, ApiV2Gate.Unrestricted)
            val first = assertIs<ApiResult.Success<HistoryPageV2>>(api.page()).data
            assertEquals("episode-1", first.items.single().watch.mediaItemId)
            assertEquals("future-source", first.items.single().watch.source)
            assertEquals(120.5, first.items.single().watch.durationSeconds)
            val empty = assertIs<ApiResult.Success<HistoryPageV2>>(api.page(continuation = first.continuation)).data
            assertTrue(empty.items.isEmpty()); assertNotNull(empty.continuation)
            val duplicate = assertIs<ApiResult.Success<HistoryPageV2>>(api.page(continuation = empty.continuation)).data
            assertTrue(duplicate.items.isEmpty()); assertNotNull(duplicate.continuation)
            val last = assertIs<ApiResult.Success<HistoryPageV2>>(api.page(continuation = duplicate.continuation)).data
            assertEquals("series-2", last.items.single().item.contentId); assertNull(last.continuation)
            assertEquals(listOf(null, "empty", "duplicate", "last"), cursors)
        } finally { client.close() }
    }

    @Test fun malformedEnvelopeOrCursorFailsWithoutImplicitRestart() = runTest {
        for (body in listOf("""{"items":[]}""", """{"page":{"has_more":false}}""",
            """{"items":[],"page":{"has_more":true}}""",
            """{"items":[{"content_id":"m1","type":"movie","title":"Film"}],"page":{"has_more":false}}""")) {
            var calls = 0
            val client = HttpClient(MockEngine { calls++; respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json")) })
            try { assertFalse(HistoryV2Api(client, ApiV2Gate.Unrestricted).page() is ApiResult.Success); assertEquals(1, calls) }
            finally { client.close() }
        }
    }

    @Test fun repeatedCursorAndChangedParametersAreRejected() = runTest {
        var calls = 0
        val client = HttpClient(MockEngine { calls++; respond(page("", "same"), headers = headersOf(HttpHeaders.ContentType, "application/json")) })
        try {
            val api = HistoryV2Api(client, ApiV2Gate.Unrestricted)
            val first = assertIs<ApiResult.Success<HistoryPageV2>>(api.page()).data
            assertIs<ApiResult.Error>(api.page(limit = 50, continuation = first.continuation))
            assertEquals(1, calls)
            assertIs<ApiResult.Error>(api.page(continuation = first.continuation))
            assertEquals(2, calls)
        } finally { client.close() }
    }

    @Test fun expiredCursorRequiresExplicitFirstPageRequest() = runTest {
        val cursors = mutableListOf<String?>()
        val client = HttpClient(MockEngine { request ->
            val cursor = request.url.parameters["cursor"]; cursors += cursor
            if (cursor != null) respond("""{"type":"https://siloserver.org/problems/invalid_cursor","title":"Expired","status":400}""", HttpStatusCode.BadRequest, headersOf(HttpHeaders.ContentType, "application/problem+json"))
            else respond(page("", "expired"), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val api = HistoryV2Api(client, ApiV2Gate.Unrestricted)
            val first = assertIs<ApiResult.Success<HistoryPageV2>>(api.page()).data
            assertEquals("invalid_cursor", assertIs<ApiResult.Error>(api.page(continuation = first.continuation)).error)
            assertEquals(listOf(null, "expired"), cursors)
            assertIs<ApiResult.Success<*>>(api.page())
            assertEquals(listOf(null, "expired", null), cursors)
        } finally { client.close() }
    }

    @Test fun viewerChangesRejectContinuationAndInFlightResponse() = runTest {
        var scope = AuthScopeSnapshot("server", "p1", "https://example.invalid", null, identityGeneration = 1)
        val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = scope }
        var changeDuringRead = false
        var calls = 0
        val client = HttpClient(MockEngine {
            calls++
            if (changeDuringRead) scope = scope.copy(profileId = "p3", identityGeneration = 3)
            respond(page("", "next"), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val api = HistoryV2Api(client, ApiV2Gate.Unrestricted, tokens)
            val first = assertIs<ApiResult.Success<HistoryPageV2>>(api.page()).data
            scope = scope.copy(profileId = "p2", identityGeneration = 2)
            assertEquals("identity_changed", assertIs<ApiResult.Error>(api.page(continuation = first.continuation)).error)
            assertEquals(1, calls)
            changeDuringRead = true
            assertEquals("identity_changed", assertIs<ApiResult.Error>(api.page()).error)
        } finally { client.close() }
    }

    @Test fun cancelledRequestCannotPublishAHistoryPage() = runTest {
        val started = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine { started.complete(Unit); awaitCancellation() })
        try {
            val job = async { HistoryV2Api(client, ApiV2Gate.Unrestricted).page() }
            withContext(Dispatchers.Default) { withTimeout(10_000) { started.await() } }
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
        } finally { client.close() }
    }
}
