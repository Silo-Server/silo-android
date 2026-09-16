package org.siloserver.silo.common.downloads

import org.siloserver.silo.network.apiv2.ApiV2Gate

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.download.*
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.CatalogApi
import org.siloserver.silo.network.apiv2.CatalogV2Api
import org.siloserver.silo.repository.CatalogRepository
import kotlin.test.*

class DownloadSubscriptionV2ReadTest {
    private val body = """{"items":[{"content_id":"e2","season_number":1,"episode_number":2,"title":"Second","files":[{"file_id":"42"}]},{"content_id":"e3","season_number":1,"episode_number":3,"files":[{"file_id":"43"}],"user_data":{"played":true}},{"content_id":"e4","season_number":1,"episode_number":4,"files":[{"file_id":"44"}]}]}"""
    private fun subscription() = DownloadSubscription(id = "sub", serverId = "server", profileId = "p1",
        targetType = DownloadSubscriptionTargetType.Season, targetId = "season-item", displayTitle = "Season",
        mediaKind = DownloadSubscriptionMediaKind.Video, quality = DownloadQuality.Original,
        keepUnwatchedLimit = 3, createdAt = 1, updatedAt = 1)

    @Test fun factoryUsesSeasonItemEndpointAndPreservesCandidateOrderAndIds() = runTest {
        val client = HttpClient(MockEngine { request ->
            assertEquals("/api/v2/catalog/items/season-item/episodes", request.url.encodedPath)
            assertTrue(request.url.parameters.isEmpty())
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val queued = mutableListOf<DownloadSubscriptionCandidate>()
        try {
            val factory = DownloadSubscriptionEvaluatorFactory(CatalogRepository(CatalogApi(client)),
                existingFileIds = { assertEquals("p1", it.profileId); emptySet() },
                enqueue = { candidate, _ -> queued += candidate })
            assertEquals(2, factory.create().evaluate(subscription()))
            assertEquals(listOf("e2", "e4"), queued.map { it.contentId })
            assertEquals(listOf(42, 44), queued.map { it.mediaFileId })
            assertEquals(listOf(2, 4), queued.map { it.episodeNumber })
        } finally { client.close() }
    }

    @Test fun changedViewerCannotPublishCandidatesOrEnqueueDownloads() = runTest {
        var scope = AuthScopeSnapshot("server", "p1", "https://example.invalid", null, identityGeneration = 1)
        val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = scope }
        val client = HttpClient(MockEngine {
            scope = scope.copy(profileId = "p2", identityGeneration = 2)
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        var enqueues = 0
        try {
            val factory = DownloadSubscriptionEvaluatorFactory(CatalogRepository(CatalogApi(client, CatalogV2Api(client, ApiV2Gate.Unrestricted, tokens))),
                existingFileIds = { emptySet() }, enqueue = { _, _ -> enqueues++ })
            assertFailsWith<IllegalStateException> { factory.create().evaluate(subscription()) }
            assertEquals(0, enqueues)
        } finally { client.close() }
    }

    @Test fun notFoundAndPartialEnvelopeFailInsteadOfPublishingEmptySubscription() = runTest {
        for ((payload, status) in listOf(
            """{"type":"https://siloserver.org/problems/not_found","title":"Not found","status":404}""" to HttpStatusCode.NotFound,
            """{"items":[],"page":{"has_more":true,"next_cursor":"unsupported"}}""" to HttpStatusCode.OK,
        )) {
            val client = HttpClient(MockEngine { respond(payload, status, headersOf(HttpHeaders.ContentType, "application/json")) })
            try {
                val factory = DownloadSubscriptionEvaluatorFactory(CatalogRepository(CatalogApi(client)),
                    existingFileIds = { emptySet() }, enqueue = { _, _ -> fail("Must not enqueue") })
                assertFailsWith<IllegalStateException> { factory.create().evaluate(subscription()) }
            } finally { client.close() }
        }
    }

    @Test fun cancelledEvaluationDoesNotEnqueue() = runTest {
        val started = CompletableDeferred<Unit>()
        val client = HttpClient(MockEngine { started.complete(Unit); awaitCancellation() })
        try {
            val factory = DownloadSubscriptionEvaluatorFactory(CatalogRepository(CatalogApi(client)),
                existingFileIds = { emptySet() }, enqueue = { _, _ -> fail("Must not enqueue") })
            val job = async { factory.create().evaluate(subscription()) }
            withContext(Dispatchers.Default) { withTimeout(10_000) { started.await() } }
            job.cancelAndJoin(); assertTrue(job.isCancelled)
        } finally { client.close() }
    }
}
