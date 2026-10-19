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
import org.siloserver.silo.model.personal.CollectionGroup
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.api.CollectionApi
import org.siloserver.silo.network.api.CollectionCapabilities
import org.siloserver.silo.network.api.SectionApi
import org.siloserver.silo.network.apiv2.ApiV2Gate
import kotlin.test.*

class CollectionCountAvailabilityTest {
    @Test
    fun oldServerAndAvailableZeroRemainUsableButExplicitUnknownStatusesDoNot() {
        for ((status, count) in listOf(null to 0, "available" to 0, null to 12, "available" to 12)) {
            val collection = decode(status, count)
            assertEquals(count, collection.availableItemCount)
            assertEquals("$count items", collection.itemCountLabel)
        }
        for (status in listOf("unavailable", "deferred", "", "AVAILABLE")) {
            for (count in listOf(0, 42)) {
                val collection = decode(status, count)
                assertNull(collection.availableItemCount)
                assertEquals("Count unavailable", collection.itemCountLabel)
            }
        }
        assertEquals("Count unavailable", Collection("missing", name = "Missing").itemCountLabel)
        assertEquals("1 item", decode("available", 1).itemCountLabel)
    }

    @Test
    fun capabilityDefaultsDoNotControlStatusHandling() {
        val old = SiloJson.decodeFromString<CollectionCapabilities>(
            """{"groups":true,"imports":true,"artwork":true,"item_reorder":true}""",
        )
        val current = SiloJson.decodeFromString<CollectionCapabilities>(
            """{"groups":true,"imports":true,"artwork":true,"item_reorder":true,"count_availability":true}""",
        )
        assertFalse(old.countAvailability)
        assertTrue(current.countAvailability)
        assertEquals("Count unavailable", decode("unavailable", 42).itemCountLabel)
    }

    @Test
    fun mostItemsSortPlacesUnavailableCountsAfterKnownZeroAndRecovers() {
        val group = CollectionGroup("g", "Picks", defaultSortMode = "most_items")
        val unavailable = decode("unavailable", 999).copy(id = "unknown", groupId = "g")
        val zero = decode("available", 0).copy(id = "zero", groupId = "g")
        val old = decode(null, 3).copy(id = "old", groupId = "g")
        val future = decode("future", 1000).copy(id = "future", groupId = "g")
        val items = listOf(unavailable, zero, old, future)
        fun ids(collections: List<Collection>) = buildCollectionSections(collections, listOf(group))
            .first().collections.map { it.id }
        assertEquals(listOf("old", "zero", "unknown", "future"), ids(items))
        assertEquals(listOf("unknown", "old", "zero", "future"), ids(items.map {
            if (it.id == "unknown") it.copy(itemCount = 5, itemCountStatus = "available") else it
        }))
        assertEquals("5 items", unavailable.copy(itemCount = 5, itemCountStatus = "available").itemCountLabel)
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
                    assertNull(it.availableItemCount)
                    assertEquals("Count unavailable", it.itemCountLabel)
                }
            } finally {
                client.close()
            }
        }
    }

    @Test
    fun reloadRecoversCountAndUnavailableCollectionStillLoadsActualMembers() = runTest {
        var available = false
        val paths = mutableListOf<String>()
        val client = client { path ->
            paths += path
            when (path) {
                "/api/v2/collections" -> """{"items":[{"id":"c1","name":"Synthetic picks","item_count":${if (available) 1 else 0},"item_count_status":"${if (available) "available" else "unavailable"}"}],"groups":[]}"""
                "/api/v2/catalog" -> """{"items":[{"content_id":"film","type":"movie","title":"Synthetic film"}],"total":1,"total_exact":true,"page":{"has_more":false}}"""
                else -> error("Unexpected request $path")
            }
        }
        try {
            val api = CollectionApi(client, ApiV2Gate.Unrestricted)
            val initial = assertIs<ApiResult.Success<org.siloserver.silo.model.personal.CollectionsResponse>>(api.listCollections()).data
            assertEquals("Count unavailable", initial.collections.single().itemCountLabel)
            val members = assertIs<ApiResult.Success<org.siloserver.silo.network.api.CollectionItemsPage>>(api.getCollectionItems("c1")).data
            assertEquals(listOf("film"), members.catalog.items.map { it.contentId })
            assertEquals(1, members.catalog.total)
            available = true
            val recovered = assertIs<ApiResult.Success<org.siloserver.silo.model.personal.CollectionsResponse>>(api.listCollections()).data
            assertEquals("1 item", recovered.collections.single().itemCountLabel)
            assertEquals(listOf("/api/v2/collections", "/api/v2/catalog", "/api/v2/collections"), paths)
        } finally {
            client.close()
        }
    }

    private fun decode(status: String?, count: Int): Collection = SiloJson.decodeFromString(
        """{"id":"c1","name":"Synthetic picks","item_count":$count${status?.let { ",\"item_count_status\":\"$it\"" } ?: ""}}""",
    )

    private fun client(body: (String) -> String) = HttpClient(MockEngine { request ->
        respond(body(request.url.encodedPath), headers = headersOf(HttpHeaders.ContentType, "application/json"))
    }) { install(ContentNegotiation) { json(SiloJson) } }
}
