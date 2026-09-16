package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.subtitles.*
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.DefaultSubtitlesApi
import org.siloserver.silo.repository.SubtitlesRepository
import org.siloserver.silo.repository.SubtitlesRepository.SubtitleJobOutcome
import kotlin.test.*

class SubtitleAiReadsV2Test {
    private var scope = AuthScopeSnapshot("server", "profile", "https://example.invalid", null, identityGeneration = 1)
    private val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = scope }
    private fun client(handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json(SiloJson) } }
    private fun row(id: String = "\"9007199254740993\"", file: String = "\"42\"", result: String = "null") =
        """{"id":$id,"media_file_id":$file,"kind":"transcribe","source_index":0,"source_language":"","target_language":"","engine":"","model":"","status":"failed","progress":0,"progress_message":"Transcribing","result_subtitle_id":$result,"error_message":"Subtitle processing failed.","created_at":"2026-01-02T03:04:05.678Z","updated_at":"2026-01-02T03:04:05.678Z"}"""
    private fun MockRequestHandleScope.reply(body: String) = respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    @Test fun exactJobIdentityAndNullableNativeHandleProjection() = runTest {
        var wire = row()
        val c = client {
            assertEquals("/api/v2/subtitles/ai/jobs/9007199254740993", it.url.encodedPath)
            reply("""{"job":$wire}""")
        }
        try {
            val api = SubtitleAiReadsV2Api(c, tokens, ApiV2Gate.Unrestricted)
            val job = assertIs<ApiResult.Success<SubtitleAiJobResponse>>(api.job(9007199254740993L)).data.job
            assertEquals(9007199254740993L, job.id); assertNull(job.resultSubtitleId)
            assertEquals("Subtitle processing failed.", job.errorMessage)
            for (bad in listOf(row(id = "9007199254740993"), row(id = "\"9007199254740994\""), row(result = "7"), row(result = "\"2147483648\""), row(file = "\"042\""))) {
                wire = bad
                assertFalse(api.job(9007199254740993L) is ApiResult.Success)
            }
            wire = row(result = "\"7\"")
            assertEquals(7, assertIs<ApiResult.Success<SubtitleAiJobResponse>>(api.job(9007199254740993L)).data.job.resultSubtitleId)
        } finally { c.close() }
    }

    @Test fun quotaAndRecentJobsUseV2AndRejectForeignFile() = runTest {
        var foreign = false
        val c = client {
            if (it.url.encodedPath.endsWith("/quota")) reply("""{"limited":true,"limit":5,"used":2,"remaining":3,"period":"daily"}""")
            else {
                assertEquals("/api/v2/subtitles/ai/jobs", it.url.encodedPath)
                assertEquals("42", it.url.parameters["media_file_id"])
                reply("""{"jobs":[${row(file = if (foreign) "\"43\"" else "\"42\"") }]}""")
            }
        }
        try {
            val api = SubtitleAiReadsV2Api(c, tokens, ApiV2Gate.Unrestricted)
            assertEquals(3, assertIs<ApiResult.Success<SubtitleAiQuota>>(api.quota()).data.remaining)
            assertEquals(1, assertIs<ApiResult.Success<SubtitleAiJobsResponse>>(api.jobs(42)).data.jobs.size)
            foreign = true; assertIs<ApiResult.Error>(api.jobs(42))
        } finally { c.close() }
    }

    @Test fun pollStopsWhenCapturedIdentityChangesWithoutPublishingJob() = runTest {
        var calls = 0
        var updates = 0
        val c = client {
            calls++
            scope = scope.copy(identityGeneration = 2)
            reply("""{"job":${row()}}""")
        }
        try {
            val api = DefaultSubtitlesApi(SubtitleReadsV2Api(c, tokens, ApiV2Gate.Unrestricted), SubtitleDownloadV2Api(c, tokens, ApiV2Gate.Unrestricted), SubtitleAiReadsV2Api(c, tokens, ApiV2Gate.Unrestricted), SubtitleAiCreateV2Api(c, tokens, ApiV2Gate.Unrestricted))
            val repository = SubtitlesRepository(api, tokens)
            assertIs<SubtitleJobOutcome.Failed>(repository.pollJob(9007199254740993L) { updates++ })
            assertEquals(1, calls); assertEquals(0, updates)
        } finally { c.close() }
    }
}
