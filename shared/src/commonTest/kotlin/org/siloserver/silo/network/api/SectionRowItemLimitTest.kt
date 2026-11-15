package org.siloserver.silo.network.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.siloserver.silo.model.section.ResolvedSection
import org.siloserver.silo.model.section.SectionItem
import org.siloserver.silo.model.section.cappedForRow
import org.siloserver.silo.model.section.offersExploreAll
import org.siloserver.silo.model.section.sectionItemLimitParam
import org.siloserver.silo.network.*
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.network.apiv2.HomeSectionsV2Api
import org.siloserver.silo.network.apiv2.LibrarySectionItemsV2Api
import kotlin.test.*

class SectionRowItemLimitTest {
    private val owner = AuthScopeSnapshot("s", "p", "https://example.invalid", "pin", identityGeneration = 1)
    private val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = owner }
    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val sections = """{"sections":[]}"""

    @Test fun limitIsSentOnlyWhenAdvertisedAndNeverAboveTheRowCap() {
        assertNull(sectionItemLimitParam(null))
        assertNull(sectionItemLimitParam(0))
        assertEquals(5, sectionItemLimitParam(5))
        assertEquals(20, sectionItemLimitParam(100))
    }

    @Test fun rowsCapAtTwentyAndOnlyNonFeaturedRowsWithCardsOfferExploreAll() {
        val items = (1..30).map { SectionItem(contentId = "movie:$it", type = "movie", title = "$it") }
        val row = ResolvedSection(id = "r", sectionType = "genre", title = "Row", items = items)
        assertEquals(items.take(20), row.cappedForRow().items)
        assertTrue(row.offersExploreAll)
        assertFalse(row.copy(featured = true).offersExploreAll)
        assertFalse(row.copy(items = emptyList()).offersExploreAll)
    }

    /** Runs Home and library list reads against a server whose capability read answers [capability]. */
    private suspend fun sentLimits(capability: () -> Pair<HttpStatusCode, String>): Pair<Int, List<String?>> {
        var capabilityReads = 0
        val limits = mutableListOf<String?>()
        val client = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v2/home/capabilities" -> {
                    capabilityReads++
                    val (status, body) = capability()
                    respond(body, status, json)
                }
                "/api/v2/home/sections", "/api/v2/library/7/sections" -> {
                    limits += request.url.parameters["item_limit"]
                    respond(sections, HttpStatusCode.OK, json)
                }
                else -> error("unexpected ${request.url.encodedPath}")
            }
        })
        try {
            val home = HomeSectionsV2Api(client, tokens, ApiV2Gate.Unrestricted)
            val api = SectionApi(client, home = home,
                sectionItems = LibrarySectionItemsV2Api(client, tokens, ApiV2Gate.Unrestricted),
                rowItemLimit = SectionRowItemLimit(home))
            // Real time: the read's timeout must not fire on the test's virtual clock.
            withContext(Dispatchers.Default) {
                repeat(2) { assertIs<ApiResult.Success<*>>(api.getHomeSections(owner)) }
                assertIs<ApiResult.Success<*>>(api.getLibrarySections(7, owner))
            }
        } finally { client.close() }
        return capabilityReads to limits
    }

    @Test fun advertisedServerGetsTheCappedLimitAndIsAskedOnce() = runTest {
        val (reads, limits) = sentLimits { HttpStatusCode.OK to """{"revision":"r","state":"available","section_item_limit_max":100}""" }
        assertEquals(1, reads)
        assertEquals(listOf<String?>("20", "20", "20"), limits)
    }

    @Test fun olderServerWithoutTheFieldGetsNoLimit() = runTest {
        val (reads, limits) = sentLimits { HttpStatusCode.OK to """{"revision":"r","state":"available","include_items_max":4}""" }
        assertEquals(1, reads)
        assertEquals(listOf<String?>(null, null, null), limits)
    }

    @Test fun failedCapabilityReadCountsAsUnsupportedForTheSession() = runTest {
        val (reads, limits) = sentLimits { HttpStatusCode.NotFound to """{"status":404,"title":"Not Found"}""" }
        assertEquals(1, reads)
        assertEquals(listOf<String?>(null, null, null), limits)
    }

    @Test fun serverRejectingTheLimitIsReadAgainWithoutItAndNotAskedAgain() = runTest {
        val unknown = """{"type":"https://siloserver.org/problems/validation_failed","title":"Validation failed","status":422,""" +
            """"errors":[{"location":"query.item_limit","code":"unknown_parameter"}]}"""
        var capabilityReads = 0
        val limits = mutableListOf<String?>()
        val client = HttpClient(MockEngine { request ->
            when (request.url.encodedPath) {
                "/api/v2/home/capabilities" -> {
                    capabilityReads++
                    respond("""{"revision":"r","state":"available","section_item_limit_max":100}""", HttpStatusCode.OK, json)
                }
                else -> {
                    val limit = request.url.parameters["item_limit"]
                    limits += limit
                    if (limit != null) respond(unknown, HttpStatusCode.UnprocessableEntity, headersOf(HttpHeaders.ContentType, "application/problem+json"))
                    else respond(sections, HttpStatusCode.OK, json)
                }
            }
        })
        try {
            val home = HomeSectionsV2Api(client, tokens, ApiV2Gate.Unrestricted)
            val api = SectionApi(client, home = home,
                sectionItems = LibrarySectionItemsV2Api(client, tokens, ApiV2Gate.Unrestricted),
                rowItemLimit = SectionRowItemLimit(home))
            withContext(Dispatchers.Default) {
                assertIs<ApiResult.Success<*>>(api.getHomeSections(owner))
                assertIs<ApiResult.Success<*>>(api.getLibrarySections(7, owner))
            }
        } finally { client.close() }
        assertEquals(1, capabilityReads)
        assertEquals(listOf<String?>("20", null, null), limits)
    }

    @Test fun otherValidationFailuresAreNotRetried() = runTest {
        var reads = 0
        val limit = SectionRowItemLimit(readMax = { ApiResult.Success(100) })
        val result = limit.read(owner) { reads++; ApiResult.Error(422, "validation_failed", "bad width") }
        assertIs<ApiResult.Error>(result)
        assertEquals(1, reads)
        assertEquals(20, limit.forOwner(owner))
    }

    @Test fun identityChangeMidReadIsNotCached() = runTest {
        var reads = 0
        val limit = SectionRowItemLimit(readMax = { reads++; if (reads == 1) ApiResult.Error(0, "identity_changed", "") else ApiResult.Success(50) })
        assertNull(limit.forOwner(owner))
        assertEquals(20, limit.forOwner(owner))
        assertEquals(20, limit.forOwner(owner))
        assertEquals(2, reads)
        // Another server is asked for itself.
        assertEquals(20, limit.forOwner(owner.copy(serverId = "other")))
        assertEquals(3, reads)
    }

    @Test fun unsupportedNeverReads() = runTest {
        assertNull(SectionRowItemLimit.Unsupported.forOwner(owner))
    }
}
