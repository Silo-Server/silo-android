package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.siloserver.silo.model.subtitles.*
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.DefaultSubtitlesApi
import org.siloserver.silo.repository.SubtitlesRepository
import kotlin.test.*

class SubtitleAiCreateV2Test {
    private var owner = AuthScopeSnapshot("server", "profile", "https://example.invalid", "proof", identityGeneration = 1, credentialEpoch = 1)
    private val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = owner }
    private val request = SubtitleTranslateRequest(42, "transcribe", -1, startPosition = 12.5)
    private val job = """{"id":"9007199254740993","media_file_id":"42","kind":"transcribe","source_index":-1,"source_language":"","target_language":"","engine":"","model":"","status":"queued","progress":0,"progress_message":"","result_subtitle_id":null,"created_at":"2026-01-02T03:04:05Z","updated_at":"2026-01-02T03:04:05Z"}"""
    private fun client(handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json(SiloJson) } }
    private fun MockRequestHandleScope.receipt(body: String = """{"job":$job,"live_delivery_attached":false}""") =
        respond(body,HttpStatusCode.Accepted,headersOf(HttpHeaders.ContentType,"application/json"))

    @Test fun actualFacadeUsesRequiredStringsSingleAttemptAndExact202Receipt() = runTest {
        val c = client {
            assertEquals("/api/v2/subtitles/ai/translate",it.url.encodedPath)
            assertEquals(HttpMethod.Post,it.method)
            assertEquals(owner,it.attributes[AuthScopeAttributeKey])
            assertTrue(it.attributes[RequireSiloAuthAttributeKey]); assertTrue(it.attributes[SingleAttemptAttributeKey])
            val body = SiloJson.parseToJsonElement(it.body.toByteArray().decodeToString()).jsonObject
            assertEquals(JsonPrimitive("42"),body["media_file_id"])
            assertEquals(JsonPrimitive(-1),body["source_index"])
            assertEquals(JsonPrimitive(""),body["source_language"]); assertEquals(JsonPrimitive(""),body["target_language"])
            assertEquals(JsonPrimitive("transcribe"),body["kind"])
            assertEquals(JsonPrimitive(12.5),body["start_position"]); assertFalse("session_id" in body)
            receipt()
        }
        try {
            val api = DefaultSubtitlesApi(SubtitleReadsV2Api(c, tokens, ApiV2Gate.Unrestricted), SubtitleDownloadV2Api(c, tokens, ApiV2Gate.Unrestricted), SubtitleAiReadsV2Api(c, tokens, ApiV2Gate.Unrestricted), SubtitleAiCreateV2Api(c, tokens, ApiV2Gate.Unrestricted))
            val result = assertIs<ApiResult.Success<SubtitleAiJobResponse>>(api.translate(request,owner)).data
            assertEquals(9007199254740993L,result.job.id); assertFalse(result.liveDeliveryAttached)
        } finally { c.close() }
    }

    @Test fun uncertaintyAndCancellationKeepFenceAcrossChangedPlayhead() = runTest {
        var sends = 0
        val started = CompletableDeferred<Unit>()
        val c = client { sends++; started.complete(Unit); awaitCancellation() }
        try {
            val api = SubtitleAiCreateV2Api(c, tokens, ApiV2Gate.Unrestricted)
            val first = launch { api.create(request,owner) }
            started.await()
            assertIs<ApiResult.Error>(api.create(request.copy(startPosition = 20.0),owner))
            first.cancelAndJoin()
            assertEquals("subtitle_creation_uncertain",assertIs<ApiResult.Error>(api.create(request,owner)).error)
            assertEquals(1,sends)
        } finally { c.close() }
    }

    @Test fun lostReceiptDoesNotResendAndExplicit401DoesNotAutomaticallyReplay() = runTest {
        var sends = 0
        var fail = true
        val c = client { sends++; if (fail) error("lost response") else respond("",HttpStatusCode.Unauthorized) }
        try {
            val api = SubtitleAiCreateV2Api(c, tokens, ApiV2Gate.Unrestricted)
            assertIs<ApiResult.Error>(api.create(request,owner))
            assertIs<ApiResult.Error>(api.create(request,owner)); assertEquals(1,sends)
            fail = false
            val other = request.copy(sourceIndex = 2)
            assertEquals(401,assertIs<ApiResult.Error>(api.create(other,owner)).code)
            assertEquals(2,sends)
        } finally { c.close() }
    }

    @Test fun rejectsMissingAttachmentWrongSourceNumericIdsAndWrongSuccessStatus() = runTest {
        var body = """{"job":$job}"""
        var status = HttpStatusCode.Accepted
        val c = client { respond(body,status,headersOf(HttpHeaders.ContentType,"application/json")) }
        try {
            for (bad in listOf("""{"job":$job}""", """{"job":${job.replace("\"42\"","42")},"live_delivery_attached":false}""",
                """{"job":${job.replace("\"source_index\":-1","\"source_index\":0")},"live_delivery_attached":false}""")) {
                body = bad
                assertFalse(SubtitleAiCreateV2Api(c, tokens, ApiV2Gate.Unrestricted).create(request,owner) is ApiResult.Success)
            }
            body = """{"job":$job,"live_delivery_attached":true}"""
            assertTrue(assertIs<ApiResult.Success<SubtitleAiJobResponse>>(SubtitleAiCreateV2Api(c, tokens, ApiV2Gate.Unrestricted).create(request,owner)).data.liveDeliveryAttached)
            status = HttpStatusCode.OK
            assertFalse(SubtitleAiCreateV2Api(c, tokens, ApiV2Gate.Unrestricted).create(request,owner) is ApiResult.Success)
        } finally { c.close() }
    }

    // Built from the wire form, so these tests also run against code that predates the field.
    private val attached = SiloJson.decodeFromString<SubtitleTranslateRequest>(
        """{"media_file_id":42,"kind":"transcribe","source_index":-1,"start_position":12.5,"session_id":"sess-1"}""")
    private suspend fun sentSession(it: io.ktor.client.request.HttpRequestData) =
        SiloJson.parseToJsonElement(it.body.toByteArray().decodeToString()).jsonObject["session_id"]?.jsonPrimitive?.content

    @Test fun playerJobCarriesItsPlaybackSession() = runTest {
        val sessions = mutableListOf<String?>()
        val c = client { sessions += sentSession(it); receipt("""{"job":$job,"live_delivery_attached":true}""") }
        try {
            val result = assertIs<ApiResult.Success<SubtitleAiJobResponse>>(SubtitleAiCreateV2Api(c, tokens, ApiV2Gate.Unrestricted).create(attached,owner))
            assertTrue(result.data.liveDeliveryAttached)
            assertEquals(listOf<String?>("sess-1"), sessions)
        } finally { c.close() }
    }

    @Test fun sessionTheServerCannotFindIsSentOnceMoreWithoutIt() = runTest {
        val sessions = mutableListOf<String?>()
        val c = client { sessions += sentSession(it); if (sessions.size == 1) respond("",HttpStatusCode.NotFound) else receipt() }
        try {
            val result = assertIs<ApiResult.Success<SubtitleAiJobResponse>>(SubtitleAiCreateV2Api(c, tokens, ApiV2Gate.Unrestricted).create(attached,owner))
            assertFalse(result.data.liveDeliveryAttached)
            assertEquals(listOf("sess-1", null), sessions)
        } finally { c.close() }
    }

    @Test fun onlyADefiniteSessionMissIsResentAndTheFenceSpansBothSends() = runTest {
        var sends = 0
        // A lost receipt on the resend keeps the intent fenced.
        val c = client { sends++; if (sends == 1) respond("",HttpStatusCode.NotFound) else error("lost response") }
        try {
            val api = SubtitleAiCreateV2Api(c, tokens, ApiV2Gate.Unrestricted)
            assertEquals("subtitle_creation_uncertain",assertIs<ApiResult.Error>(api.create(attached,owner)).error)
            assertEquals("subtitle_creation_uncertain",assertIs<ApiResult.Error>(api.create(attached,owner)).error)
            assertEquals(2,sends)
        } finally { c.close() }
        sends = 0
        val refused = client { sends++; respond("",HttpStatusCode.TooManyRequests) }
        try {
            assertEquals(429,assertIs<ApiResult.Error>(SubtitleAiCreateV2Api(refused, tokens, ApiV2Gate.Unrestricted).create(attached,owner)).code)
            assertEquals(1,sends)
        } finally { refused.close() }
        sends = 0
        val replaced = client { sends++; owner = owner.copy(profileToken = "new-proof"); respond("",HttpStatusCode.NotFound) }
        try {
            assertEquals("identity_changed",assertIs<ApiResult.Error>(SubtitleAiCreateV2Api(replaced, tokens, ApiV2Gate.Unrestricted).create(attached,owner)).error)
            assertEquals(1,sends)
        } finally { replaced.close() }
    }

    @Test fun replacedOwnerCannotSendAndLatePinCannotPublishOrStartPoll() = runTest {
        var sends = 0
        val captured = owner
        val c = client { sends++; owner = owner.copy(profileToken = "new-proof"); receipt() }
        try {
            val api = DefaultSubtitlesApi(SubtitleReadsV2Api(c, tokens, ApiV2Gate.Unrestricted), SubtitleDownloadV2Api(c, tokens, ApiV2Gate.Unrestricted), SubtitleAiReadsV2Api(c, tokens, ApiV2Gate.Unrestricted), SubtitleAiCreateV2Api(c, tokens, ApiV2Gate.Unrestricted))
            assertIs<ApiResult.Error>(api.translate(request,captured))
            assertIs<ApiResult.Error>(api.translate(request,captured))
            var updates = 0
            val repo = SubtitlesRepository(api,tokens)
            assertIs<SubtitlesRepository.SubtitleJobOutcome.Failed>(repo.pollJob(9007199254740993L,expectedScope=captured) { updates++ })
            assertEquals(1,sends); assertEquals(0,updates)
        } finally { c.close() }
    }
}
