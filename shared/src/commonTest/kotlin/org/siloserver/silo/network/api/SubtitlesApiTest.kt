package org.siloserver.silo.network.api

import org.siloserver.silo.network.apiv2.ApiV2Gate

import org.siloserver.silo.model.subtitles.SubtitleAiJobKind
import org.siloserver.silo.model.subtitles.SubtitleAiJobStatus
import org.siloserver.silo.model.subtitles.SubtitleDownloadRequest
import org.siloserver.silo.model.subtitles.SubtitleProvider
import org.siloserver.silo.model.subtitles.SubtitleSearchRequest
import org.siloserver.silo.model.subtitles.SubtitleTranslateRequest
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.apiv2.SubtitleAiCreateV2Api
import org.siloserver.silo.network.apiv2.SubtitleAiReadsV2Api
import org.siloserver.silo.network.apiv2.SubtitleDownloadV2Api
import org.siloserver.silo.network.apiv2.SubtitleReadsV2Api
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SubtitlesApiTest {

    private val scope = AuthScopeSnapshot("server", "profile", "https://example.invalid", null, identityGeneration = 1)
    private val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = scope }

    /** Captures the single request a test makes through the mock transport. */
    private class Captured {
        var method: HttpMethod? = null
        var path: String = ""
        var query: Map<String, String?> = emptyMap()
        var body: String = ""
    }

    private fun api(
        status: HttpStatusCode = HttpStatusCode.OK,
        responseBody: String = "{}",
        captured: Captured = Captured(),
    ): Pair<SubtitlesApi, Captured> {
        val client = HttpClient(
            MockEngine { request ->
                captured.method = request.method
                captured.path = request.url.encodedPath
                captured.query = request.url.parameters.names()
                    .associateWith { request.url.parameters[it] }
                captured.body = request.body.toByteArray().decodeToString()
                respond(
                    content = responseBody,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
        }
        val api = DefaultSubtitlesApi(
            SubtitleReadsV2Api(client, tokens, ApiV2Gate.Unrestricted),
            SubtitleDownloadV2Api(client, tokens, ApiV2Gate.Unrestricted),
            SubtitleAiReadsV2Api(client, tokens, ApiV2Gate.Unrestricted),
            SubtitleAiCreateV2Api(client, tokens, ApiV2Gate.Unrestricted),
        )
        return api to captured
    }

    @Test
    fun `search posts wire body and decodes results`() = runTest {
        val (api, captured) = api(
            responseBody = """
                {"results":[{"id":"os-1","provider":"opensubtitles","language":"en",
                  "release_name":"R","format":"srt","score":70,"downloads":9,
                  "hearing_impaired":false}],
                 "warnings":["subdl: timed out"]}
            """.trimIndent(),
        )

        val result = api.search(SubtitleSearchRequest(mediaFileId = 1048, languages = listOf("en")))

        assertEquals(HttpMethod.Post, captured.method)
        assertEquals("/api/v2/subtitles/search", captured.path)
        val sent = SiloJson.parseToJsonElement(captured.body).jsonObject
        assertEquals(setOf("media_file_id", "languages"), sent.keys)
        assertEquals("1048", sent.getValue("media_file_id").jsonPrimitive.content)

        assertIs<ApiResult.Success<*>>(result)
        val data = (result as ApiResult.Success).data
        assertEquals("os-1", data.results.single().id)
        assertEquals(listOf("subdl: timed out"), data.warnings)
    }

    @Test
    fun `download posts echo body and unwraps subtitle envelope`() = runTest {
        val (api, captured) = api(
            responseBody = """
                {"subtitle":{"id":"312","media_file_id":"1048","provider":"opensubtitles",
                 "language":"en","format":"srt","release_name":"R","score":70,
                 "hearing_impaired":true,"created_at":"2026-06-12T09:30:00Z"}}
            """.trimIndent(),
        )

        val result = api.download(
            SubtitleDownloadRequest(
                mediaFileId = 1048,
                provider = SubtitleProvider.OpenSubtitles,
                subtitleId = "os-1",
                language = "en",
                releaseName = "R",
                format = "srt",
                score = 70.0,
                hearingImpaired = true,
            ),
        )

        assertEquals("/api/v2/subtitles/download", captured.path)
        assertIs<ApiResult.Success<*>>(result)
        assertEquals(312, (result as ApiResult.Success).data.subtitle.id)
    }

    @Test
    fun `list gets media file path and decodes subtitles`() = runTest {
        val (api, captured) = api(responseBody = """{"subtitles":[]}""")

        val result = api.list(mediaFileId = 1048)

        assertEquals(HttpMethod.Get, captured.method)
        assertEquals("/api/v2/subtitles/1048", captured.path)
        assertIs<ApiResult.Success<*>>(result)
    }

    @Test
    fun `aiStatus and aiQuota hit their paths`() = runTest {
        val (statusApi, statusCaptured) = api(
            responseBody = """{"enabled":true,"transcribe_enabled":true,"revision":"r1","state":"ready","allowed":true}""",
        )
        val status = statusApi.aiStatus()
        assertEquals("/api/v2/subtitles/ai/status", statusCaptured.path)
        assertIs<ApiResult.Success<*>>(status)
        assertTrue((status as ApiResult.Success).data.transcribeEnabled)

        val (quotaApi, quotaCaptured) = api(
            responseBody = """{"limited":true,"limit":5,"used":5,"remaining":0,"period":"month"}""",
        )
        val quota = quotaApi.aiQuota()
        assertEquals("/api/v2/subtitles/ai/quota", quotaCaptured.path)
        assertIs<ApiResult.Success<*>>(quota)
        assertEquals(0, (quota as ApiResult.Success).data.remaining)
    }

    @Test
    fun `translate posts body without session_id and decodes 202 job envelope`() = runTest {
        val (api, captured) = api(
            status = HttpStatusCode.Accepted,
            responseBody = """
                {"job":{"id":"91","media_file_id":"1048","kind":"translate","source_index":2,
                 "source_language":"en","target_language":"nl","engine":"openai",
                 "model":"gpt-4o-mini","status":"pending","progress":0,
                 "progress_message":"","result_subtitle_id":null,
                 "created_at":"2026-06-12T10:00:00Z","updated_at":"2026-06-12T10:00:00Z"},
                 "live_delivery_attached":false}
            """.trimIndent(),
        )

        val result = api.translate(
            SubtitleTranslateRequest(
                mediaFileId = 1048,
                kind = SubtitleAiJobKind.Translate,
                sourceIndex = 2,
                targetLanguage = "nl",
                startPosition = 845.2,
            ),
        )

        assertEquals("/api/v2/subtitles/ai/translate", captured.path)
        val sent = SiloJson.parseToJsonElement(captured.body).jsonObject
        assertFalse("session_id" in sent.keys)       // Android polls; never streams live cues
        assertEquals(
            setOf("media_file_id", "kind", "source_index", "source_language", "target_language", "start_position"),
            sent.keys,
        )
        assertEquals("1048", sent.getValue("media_file_id").jsonPrimitive.content)
        assertIs<ApiResult.Success<*>>(result)
        assertEquals(SubtitleAiJobStatus.Pending, (result as ApiResult.Success).data.job.status)
    }

    @Test
    fun `listJobs passes media_file_id query and getJob hits job path`() = runTest {
        val (listApi, listCaptured) = api(responseBody = """{"jobs":[]}""")
        listApi.listJobs(mediaFileId = 1048)
        assertEquals("/api/v2/subtitles/ai/jobs", listCaptured.path)
        assertEquals("1048", listCaptured.query["media_file_id"])

        val (getApi, getCaptured) = api(
            responseBody = """
                {"job":{"id":"91","media_file_id":"1048","kind":"translate","source_index":2,
                 "source_language":"en","target_language":"nl","engine":"openai","model":"gpt-4o-mini",
                 "status":"running","progress":0.5,"progress_message":"Translating",
                 "result_subtitle_id":null,
                 "created_at":"2026-06-12T10:00:00Z","updated_at":"2026-06-12T10:01:00Z"}}
            """.trimIndent(),
        )
        val result = getApi.getJob(jobId = 91L)
        assertEquals("/api/v2/subtitles/ai/jobs/91", getCaptured.path)
        assertIs<ApiResult.Success<*>>(result)
    }

    @Test
    fun `cancelJob posts to cancel path and maps 204 to Unit`() = runTest {
        val (api, captured) = api(status = HttpStatusCode.NoContent, responseBody = "")

        val result = api.cancelJob(jobId = 91L)

        assertEquals(HttpMethod.Post, captured.method)
        assertEquals("/api/v2/subtitles/ai/jobs/91/cancel", captured.path)
        assertEquals(ApiResult.Success(Unit), result)
    }

    @Test
    fun `server problem surfaces as ApiResult Error with detail`() = runTest {
        // 503 = AI engine unconfigured; 429 = quota exhausted — same mapping path.
        val (api, _) = api(
            status = HttpStatusCode.ServiceUnavailable,
            responseBody = """{"type":"https://silo.example/problems/ai_unavailable","title":"Service Unavailable",
                "status":503,"detail":"AI translation is not configured"}""",
        )

        val result = api.translate(
            SubtitleTranslateRequest(mediaFileId = 1048, sourceIndex = 0, targetLanguage = "nl", startPosition = 0.0),
        )

        assertIs<ApiResult.Error>(result)
        assertEquals(503, result.code)
        assertEquals("AI translation is not configured", result.message)
    }
}
