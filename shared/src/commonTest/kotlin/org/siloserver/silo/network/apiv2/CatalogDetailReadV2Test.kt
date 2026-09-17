package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.network.*
import kotlin.test.*

class CatalogDetailReadV2Test {
    private fun client(body: String) = HttpClient(MockEngine {
        respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
    })
    private fun detail(fileId: String) = """{"content_id":"m1","type":"movie","title":"Film","cast":[],"crew":[],"subtitles":[],"versions":[{"file_id":$fileId}],"user_data":{"last_file_id":"42"},"intro":{"start":1.5,"end":9.5}}"""

    @Test fun detailProjectsStringIdsAndCatalogMarkers() = runTest {
        val client = client(detail("\"42\""))
        try {
            val result = assertIs<ApiResult.Success<*>>(CatalogV2Api(client, ApiV2Gate.Unrestricted).itemDetail("m1")).data as org.siloserver.silo.model.catalog.ItemDetail
            assertEquals(42, result.versions.single().fileId)
            assertEquals(42, result.userData?.lastFileId)
            assertEquals(1.5, result.intro?.start)
            assertEquals(9.5, result.intro?.end)
        } finally { client.close() }
    }

    @Test fun detailProjectsPlaybackVariantsAndNestedStringFileIds() = runTest {
        val body = """{
            "content_id":"m1","type":"movie","title":"Film","cast":[],"crew":[],"subtitles":[],
            "versions":[{"file_id":"42","duration":15060}],
            "playback_variants":[{
                "variant_id":"directors-cut","part_count":2,"total_duration":15120,"default_file_id":"42",
                "parts":[{"part_index":0,"default_file_id":"42","total_duration":7560,"versions":[{"file_id":"42","duration":7560}]}]
            }]
        }"""
        val client = client(body)
        try {
            val result = assertIs<ApiResult.Success<*>>(
                CatalogV2Api(client, ApiV2Gate.Unrestricted).itemDetail("m1"),
            ).data as org.siloserver.silo.model.catalog.ItemDetail
            val variant = result.playbackVariants.single()
            assertEquals("directors-cut", variant.variantId)
            assertEquals(2, variant.partCount)
            assertEquals(15_120.0, variant.totalDuration)
            assertEquals(42, variant.defaultFileId)
            assertEquals(42, variant.parts.single().defaultFileId)
            assertEquals(42, variant.parts.single().versions.single().fileId)
        } finally { client.close() }
    }

    @Test fun fileIdsRejectOverflowFractionSignsAndNumericWireValues() = runTest {
        for (id in listOf("\"2147483648\"", "\"9223372036854775808\"", "\"1.5\"", "\"+42\"", "\"-1\"", "\"opaque\"", "42")) {
            val client = client(detail(id))
            try { assertFalse(CatalogV2Api(client, ApiV2Gate.Unrestricted).itemDetail("m1") is ApiResult.Success, "Unexpected accepted ID: $id") }
            finally { client.close() }
        }
    }

    @Test fun unpaginatedHierarchyRequiresItemsAndRejectsUnexpectedContinuation() = runTest {
        for ((body, valid) in listOf(
            """{"items":[{"content_id":"s1","season_number":1}]}""" to true,
            """{"seasons":[]}""" to false,
            """{"items":[],"page":{"has_more":true,"next_cursor":"next"}}""" to false,
        )) {
            val client = client(body)
            try { assertEquals(valid, CatalogV2Api(client, ApiV2Gate.Unrestricted).seriesSeasons("series") is ApiResult.Success) }
            finally { client.close() }
        }
    }

    @Test fun episodeAndPeopleReadsUseTypedEnvelopesAndNewPaths() = runTest {
        val paths = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            paths += request.url.encodedPath
            val body = when {
                request.url.encodedPath.endsWith("/episodes") -> """{"items":[{"content_id":"e1","season_number":0,"episode_number":1,"files":[{"file_id":"2147483647"}],"user_data":{"last_file_id":"7"}}]}"""
                request.url.encodedPath.endsWith("/people/7") -> """{"id":"7","name":"Person"}"""
                else -> {
                    assertEquals("Person", request.url.parameters["q"])
                    assertEquals("20", request.url.parameters["limit"])
                    """{"items":[{"id":"9223372036854775807","name":"Person"}]}"""
                }
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val api = CatalogV2Api(client, ApiV2Gate.Unrestricted)
            val episodes = assertIs<ApiResult.Success<org.siloserver.silo.model.catalog.EpisodesResponse>>(api.seasonEpisodes("series", 0)).data
            assertEquals(Int.MAX_VALUE, episodes.episodes.single().files.single().fileId)
            assertEquals(7, episodes.episodes.single().userData?.lastFileId)
            assertEquals(7L, assertIs<ApiResult.Success<org.siloserver.silo.model.catalog.Person>>(api.person(7)).data.id)
            assertEquals(Long.MAX_VALUE, assertIs<ApiResult.Success<List<org.siloserver.silo.model.catalog.Person>>>(api.people("Person")).data.single().id)
            assertEquals(listOf("/api/v2/catalog/series/series/seasons/0/episodes", "/api/v2/catalog/people/7", "/api/v2/catalog/people"), paths)
        } finally { client.close() }
    }

    @Test fun viewerChangeDuringDetailReadRejectsPublication() = runTest {
        var identity = AuthScopeSnapshot("server", "p1", "https://example.invalid", null, identityGeneration = 1)
        val tokens = object : TokenManager by TokenManagerImpl() {
            override suspend fun snapshotCurrentScope() = identity
        }
        val client = HttpClient(MockEngine {
            identity = identity.copy(profileId = "p2", identityGeneration = 2)
            respond(detail("\"42\""), headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val result = CatalogV2Api(client, ApiV2Gate.Unrestricted, tokens).itemDetail("m1")
            assertEquals("identity_changed", assertIs<ApiResult.Error>(result).error)
        } finally { client.close() }
    }
}
