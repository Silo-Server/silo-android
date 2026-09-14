package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.network.*
import kotlin.test.*

class TasteProfileV2Test {
    private var owner=AuthScopeSnapshot("s","p","https://example.invalid","pin",identityGeneration=1)
    private val tokens=object:TokenManager by TokenManagerImpl(){override suspend fun snapshotCurrentScope()=owner}
    @Test fun exactScopedReadPreservesOrderedSummaryAndEmptyState()=runTest {
        var body="""{"top_genres":["Drama","Comedy"],"favorite_directors":["Director"],"signal_counts":{"watched":7},"updated_at":"2026-01-01T00:00:00Z"}"""
        val c=HttpClient(MockEngine {
            assertEquals("/api/v2/recommendations/taste-profile",it.url.encodedPath)
            assertEquals(owner,it.attributes[AuthScopeAttributeKey]);assertTrue(it.attributes[RequireSiloAuthAttributeKey])
            respond(body,HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
        })
        try {
            val api=TasteProfileV2Api(c, tokens, ApiV2Gate.Unrestricted)
            val result=assertIs<ApiResult.Success<*>>(api.read(owner)).data as org.siloserver.silo.model.recommendation.TasteProfile
            assertEquals(listOf("Drama","Comedy"),result.topGenres);assertEquals(7,result.signalCounts["watched"])
            assertEquals("2026-01-01T00:00:00Z",result.updatedAt)
            body="""{"top_genres":[],"favorite_directors":[],"signal_counts":{}}"""
            val empty=api.read(owner).getOrThrow();assertTrue(empty.topGenres.isEmpty());assertNull(empty.updatedAt)
        } finally {c.close()}
    }
    @Test fun malformedWrongStatusAndLateAuthorityRefuse()=runTest {
        var body="""{"top_genres":"not-a-list"}""";var status=HttpStatusCode.OK;var late=false;var sends=0
        val c=HttpClient(MockEngine {sends++;if(late)owner=owner.copy(profileToken="new");respond(body,status,headersOf(HttpHeaders.ContentType,"application/json"))})
        try {
            val api=TasteProfileV2Api(c, tokens, ApiV2Gate.Unrestricted);val original=owner
            assertFalse(api.read(owner) is ApiResult.Success)
            body="""{"top_genres":[],"favorite_directors":[],"signal_counts":{}}""";status=HttpStatusCode.Accepted
            assertFalse(api.read(owner) is ApiResult.Success)
            status=HttpStatusCode.OK;late=true;assertIs<ApiResult.Error>(api.read(original))
            assertIs<ApiResult.Error>(api.read(original));assertEquals(3,sends)
        } finally {c.close()}
    }
}
