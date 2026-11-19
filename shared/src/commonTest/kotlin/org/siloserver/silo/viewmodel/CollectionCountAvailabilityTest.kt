package org.siloserver.silo.viewmodel

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.personal.Collection
import org.siloserver.silo.model.personal.CollectionCountState
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.api.SectionApi
import kotlin.test.*

class CollectionCountAvailabilityTest {
    @Test
    fun missingStatusKeepsLegacyCountsAndOnlyAvailableIsExact() {
        assertEquals(CollectionCountState.Legacy, decode(null, 0).countState)
        assertEquals(CollectionCountState.Exact, decode("available", 0).countState)
        for (status in listOf("unavailable", "deferred", "", "AVAILABLE")) {
            assertEquals(CollectionCountState.Unavailable, decode(status, 42).countState)
        }
    }

    @Test
    fun libraryCardStatusSurvivesGroupedUngroupedAndFlatConversions() = runTest {
        for (grouped in listOf(true, false)) {
            val card = """{"id":"c1","title":"Synthetic picks","poster_url":"","item_count":42,"item_count_status":"future"}"""
            val body = if (grouped) {
                """{"library_id":"7","collections":[],"groups":[{"id":"g","name":"Picks","kind":"user_collections","sort_mode":"most_items","sort_order":0,"collections":[$card]}],"ungrouped":{"sort_order":1,"collections":[$card]}}"""
            } else {
                """{"library_id":"7","collections":[{"id":"c1","title":"Synthetic picks","library_id":"7","library_ids":["7"],"collection_type":"smart","poster_url":"","item_count":42,"item_count_status":"future","sort_order":0}],"groups":[]}"""
            }
            val client = client { path ->
                assertEquals("/api/v2/library/7/collections", path)
                body
            }
            try {
                val result = assertIs<ApiResult.Success<*>>(SectionApi(client).getLibraryCollections(7))
                    .data as org.siloserver.silo.model.section.LibraryCollectionsResponse
                val cards = result.collections + result.groups.flatMap { it.collections } +
                    result.ungrouped?.collections.orEmpty()
                assertTrue(cards.isNotEmpty())
                cards.forEach {
                    assertEquals("future", it.itemCountStatus)
                    assertEquals(CollectionCountState.Unavailable, it.countState)
                }
            } finally {
                client.close()
            }
        }
    }

    private fun decode(status: String?, count: Int): Collection = SiloJson.decodeFromString(
        """{"id":"c1","name":"Synthetic picks","item_count":$count${status?.let { ",\"item_count_status\":\"$it\"" } ?: ""}}""",
    )

    private fun client(body: (String) -> String) = HttpClient(MockEngine { request ->
        respond(body(request.url.encodedPath), headers = headersOf(HttpHeaders.ContentType, "application/json"))
    }) { install(ContentNegotiation) { json(SiloJson) } }
}
