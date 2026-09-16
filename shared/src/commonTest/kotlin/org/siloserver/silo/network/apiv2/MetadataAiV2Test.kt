package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.metadata.*
import org.siloserver.silo.model.metadata.*
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.DefaultMetadataAiApi
import org.siloserver.silo.repository.MetadataAiRepository
import kotlin.test.*

class MetadataAiV2Test {
    private var owner = AuthScopeSnapshot("server","profile","https://example.invalid","proof",identityGeneration=1)
    private val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = owner }
    private fun client(handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json(SiloJson) } }
    private fun job(status: String = "pending", content: String = "movie-1") =
        """{"id":"9007199254740993","target_kind":"item","content_id":"$content","target_language":"nl","status":"$status","fields_done":1,"fields_total":2,"created_at":"2026-01-02T03:04:05.678Z","updated_at":"2026-01-02T03:04:05.678Z"}"""
    private fun MockRequestHandleScope.reply(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body,status,headersOf(HttpHeaders.ContentType,"application/json"))

    @Test fun capabilityPreservesRevisionAndUnknownModesCannotTriggerWork() = runTest {
        var state = "not_configured"
        var mode = "auto"
        val c = client {
            assertEquals("/api/v2/capabilities/metadata-ai",it.url.encodedPath)
            assertEquals(owner,it.attributes[AuthScopeAttributeKey])
            reply("""{"state":"$state","revision":"opaque/revision","on_view":"$mode"}""")
        }
        try {
            val api = DefaultMetadataAiApi(c, tokens, ApiV2Gate.Unrestricted)
            val unavailable = assertIs<ApiResult.Success<MetadataAiStatus>>(api.status()).data
            assertFalse(unavailable.enabled); assertEquals(MetadataAiOnView.Off,unavailable.onView)
            state = "available"; mode = "future"
            val future = assertIs<ApiResult.Success<MetadataAiStatus>>(api.status()).data
            assertEquals("opaque/revision",future.revision); assertEquals("available",future.state)
            assertEquals(MetadataAiOnView.Off,future.onView)
            mode = "button"
            assertEquals(MetadataAiOnView.Button,assertIs<ApiResult.Success<MetadataAiStatus>>(api.status()).data.onView)
        } finally { c.close() }
    }

    @Test fun exactEncodedIdentityBare202AndProblemsArePreservedWithoutReplay() = runTest {
        val content = "season/id?value"
        var sends = 0
        var status = HttpStatusCode.Accepted
        var body = job(content=content)
        val c = client {
            sends++
            assertEquals("/api/v2/catalog/items/season%2Fid%3Fvalue/translate-description",it.url.encodedPath)
            assertTrue(it.attributes[SingleAttemptAttributeKey]); assertTrue(it.attributes[RequireSiloAuthAttributeKey])
            assertEquals(owner,it.attributes[AuthScopeAttributeKey])
            assertEquals("{\"target_language\":\"nl\"}",it.body.toByteArray().decodeToString())
            reply(body,status)
        }
        try {
            val api = DefaultMetadataAiApi(c, tokens, ApiV2Gate.Unrestricted)
            val receipt = assertIs<ApiResult.Success<MetadataTranslationJob>>(api.translateDescription(content,"nl",owner)).data
            assertEquals("9007199254740993",receipt.id); assertEquals(1,receipt.fieldsDone)
            assertEquals("2026-01-02T03:04:05.678Z",receipt.createdAt)
            for (code in listOf(409,422,401)) {
                status = HttpStatusCode.fromValue(code)
                body = """{"type":"https://example.invalid/problems/capability_not_configured","title":"Refused","status":$code,"detail":"Refused"}"""
                assertEquals(code,assertIs<ApiResult.Error>(api.translateDescription(content,"nl",owner)).code)
            }
            assertEquals(4,sends)
            status = HttpStatusCode.Accepted; body = """{"job":${job(content=content)}}"""
            assertFalse(api.translateDescription(content,"nl",owner) is ApiResult.Success)
            body = job(content=content).replace("\"9007199254740993\"","9007199254740993")
            assertFalse(api.translateDescription(content,"nl",owner) is ApiResult.Success)
        } finally { c.close() }
    }

    @Test fun reusedFailedJobDoesNotPollOrClaimCompletion() = runTest {
        var sends = 0
        val c = client { sends++; reply(job("failed"),HttpStatusCode.Accepted) }
        try {
            val controller = DescriptionTranslationController(MetadataAiRepository(DefaultMetadataAiApi(c, tokens, ApiV2Gate.Unrestricted))) { error("must not poll") }
            controller.translate("movie-1","nl", { error("must not refresh") }, { error("must not complete") })
            assertEquals(DescriptionTranslationPhase.Failed,controller.phase.value)
            assertEquals(1,sends)
        } finally { c.close() }
    }

    @Test fun actualControllerSingleFlightUsesFreshAuthorizedDetailUntilCompletion() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var posts = 0; var reads = 0; var completed = 0
        val c = client {
            assertEquals(owner,it.attributes[AuthScopeAttributeKey])
            if (it.method == HttpMethod.Post) {
                posts++; entered.complete(Unit); release.await(); reply(job(),HttpStatusCode.Accepted)
            } else {
                reads++
                assertEquals("/api/v2/catalog/items/movie-1",it.url.encodedPath)
                val pending = if (reads == 1) "\"nl\"" else "null"
                reply("""{"content_id":"movie-1","type":"movie","title":"Movie","overview":"translated","pending_translation_language":$pending,"cast":[],"crew":[],"versions":[],"subtitles":[]}""")
            }
        }
        try {
            val repo = MetadataAiRepository(DefaultMetadataAiApi(c, tokens, ApiV2Gate.Unrestricted))
            val controller = DescriptionTranslationController(repo) { }
            val first = launch {
                controller.translate("movie-1","nl", { scope ->
                    assertIs<ApiResult.Success<org.siloserver.silo.model.catalog.ItemDetail>>(repo.refreshDetail("movie-1",scope)).data.pendingTranslationLanguage
                }, { completed++ })
            }
            entered.await()
            controller.translate("movie-1","nl", { error("duplicate") }, { error("duplicate") })
            release.complete(Unit); first.join()
            assertEquals(1,posts); assertEquals(2,reads); assertEquals(1,completed)
            assertEquals(DescriptionTranslationPhase.Idle,controller.phase.value)
        } finally { c.close() }
    }

    @Test fun authorityChangeAfterAcceptanceStopsBoundedPollBeforePublication() = runTest {
        var sends = 0
        val c = client { sends++; reply(job(),HttpStatusCode.Accepted) }
        try {
            val repo = MetadataAiRepository(DefaultMetadataAiApi(c, tokens, ApiV2Gate.Unrestricted))
            val controller = DescriptionTranslationController(repo) { owner = owner.copy(identityGeneration = 2) }
            controller.translate("movie-1","nl", { error("old owner must not refetch") }, { error("old owner must not complete") })
            assertEquals(1,sends)
            assertEquals(DescriptionTranslationPhase.Failed,controller.phase.value)
        } finally { c.close() }
    }

    @Test fun lateAuthorityAndChangedPinStopReceiptAndFollowingReads() = runTest {
        var sends = 0
        val original = owner
        val c = client { sends++; owner = owner.copy(profileToken="new-proof"); reply(job(),HttpStatusCode.Accepted) }
        try {
            val api = DefaultMetadataAiApi(c, tokens, ApiV2Gate.Unrestricted)
            assertIs<ApiResult.Error>(api.translateDescription("movie-1","nl",original))
            assertIs<ApiResult.Error>(api.refreshDetail("movie-1",original))
            assertEquals(1,sends)
        } finally { c.close() }
    }
}
