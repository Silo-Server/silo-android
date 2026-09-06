package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.DefaultSubtitlesApi
import kotlin.test.*

class SubtitleAiCancelV2Test {
    private var scope = AuthScopeSnapshot("server","profile","https://example.invalid","pin-proof",identityGeneration=1)
    private val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = scope }

    @Test fun exactLongIdentityBodyless204AndDeclaredRefreshPolicy() = runTest {
        val c = HttpClient(MockEngine {
            assertEquals("/api/v2/subtitles/ai/jobs/9007199254740993/cancel",it.url.encodedPath)
            assertEquals(HttpMethod.Post,it.method)
            assertEquals(scope,it.attributes[AuthScopeAttributeKey])
            assertTrue(it.attributes[RequireSiloAuthAttributeKey])
            assertFalse(it.attributes.contains(SingleAttemptAttributeKey))
            assertTrue(it.body.toByteArray().isEmpty())
            respond("",HttpStatusCode.NoContent)
        })
        try {
            val api = DefaultSubtitlesApi(c,cancellation=SubtitleAiCancelV2Api(c,tokens))
            assertIs<ApiResult.Success<Unit>>(api.cancelJob(9007199254740993,scope))
        } finally { c.close() }
    }

    @Test fun invalidIdAndOldOwnerRefuseDispatchAndLatePinReplyIsDiscarded() = runTest {
        var sends = 0
        val c = HttpClient(MockEngine {
            sends++
            scope = scope.copy(profileToken="new-proof")
            respond("",HttpStatusCode.NoContent)
        })
        try {
            val api = SubtitleAiCancelV2Api(c,tokens)
            assertIs<ApiResult.Error>(api.cancel(0,scope))
            assertIs<ApiResult.Error>(api.cancel(1,scope.copy(identityGeneration=0)))
            assertEquals(0,sends)
            assertEquals("identity_changed",assertIs<ApiResult.Error>(api.cancel(1,scope)).error)
            assertEquals(1,sends)
        } finally { c.close() }
    }

    @Test fun otherSuccessStatusIsNotCancellationAcknowledgment() = runTest {
        val c = HttpClient(MockEngine { respond("{}",HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json")) })
        try { assertFalse(SubtitleAiCancelV2Api(c,tokens).cancel(1,scope) is ApiResult.Success) }
        finally { c.close() }
    }
}
