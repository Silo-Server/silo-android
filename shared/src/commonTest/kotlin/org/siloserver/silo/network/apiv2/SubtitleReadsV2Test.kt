package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.subtitles.*
import org.siloserver.silo.network.*
import kotlin.test.*

class SubtitleReadsV2Test {
    private var scope = AuthScopeSnapshot("server", "profile", "https://example.invalid", null, identityGeneration = 1)
    private val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = scope }
    private val row = """{"id":"7","media_file_id":"42","provider":"provider","language":"en","format":"ass","release_name":"release","score":9,"hearing_impaired":false,"created_at":"2026-01-01T00:00:00Z"}"""
    private fun client(handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json(SiloJson) } }
    private fun MockRequestHandleScope.reply(body: String) = respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType,"application/json"))
    @Test fun storedListPreservesOrderAndRejectsWrongOrUnrepresentableIdentities() = runTest {
        var body = """{"subtitles":[$row,${row.replace("\"7\"","\"8\"")}]}"""
        val c = client { assertEquals("/api/v2/subtitles/42",it.url.encodedPath); reply(body) }
        try {
            val api = SubtitleReadsV2Api(c, tokens, ApiV2Gate.Unrestricted)
            assertEquals(listOf(7,8),assertIs<ApiResult.Success<DownloadedSubtitlesResponse>>(api.list(42)).data.subtitles.map { it.id })
            for (invalid in listOf(row.replace("\"42\"","\"43\""),row.replace("\"7\"","7"),row.replace("\"7\"","\"2147483648\""))) {
                body = """{"subtitles":[$invalid]}"""; assertFalse(api.list(42) is ApiResult.Success)
            }
            body = """{"subtitles":[]}"""
            assertTrue(assertIs<ApiResult.Success<DownloadedSubtitlesResponse>>(api.list(42)).data.subtitles.isEmpty())
        } finally { c.close() }
    }
    @Test fun searchPreservesOpaqueProviderIDsMissingDateAndWarnings() = runTest {
        val c = client {
            assertEquals("/api/v2/subtitles/search",it.url.encodedPath)
            assertEquals(scope,it.attributes[AuthScopeAttributeKey])
            assertTrue(it.body.toByteArray().decodeToString().contains("\"media_file_id\":\"42\""))
            reply("""{"results":[{"id":"opaque+/=01","provider":"provider","language":"en","release_name":"release","format":"srt","score":1,"downloads":3,"hearing_impaired":false}],"warnings":["partial result"]}""")
        }
        try {
            val result = assertIs<ApiResult.Success<SubtitleSearchResponse>>(SubtitleReadsV2Api(c, tokens, ApiV2Gate.Unrestricted).search(SubtitleSearchRequest(42,listOf("en")))).data
            assertEquals("opaque+/=01",result.results.single().id); assertNull(result.results.single().uploadDate)
            assertEquals(listOf("partial result"),result.warnings)
        } finally { c.close() }
    }
    @Test fun disabledAiAndStaleResponse() = runTest {
        var replace = false
        val c = client {
            assertEquals("/api/v2/subtitles/ai/status",it.url.encodedPath)
            if (replace) scope = scope.copy(identityGeneration = 2)
            reply("""{"enabled":false,"transcribe_enabled":false}""")
        }
        try {
            val api = SubtitleReadsV2Api(c, tokens, ApiV2Gate.Unrestricted)
            assertFalse(assertIs<ApiResult.Success<SubtitleAiStatus>>(api.aiStatus()).data.enabled)
            replace = true; assertIs<ApiResult.Error>(api.aiStatus())
        } finally { c.close() }
    }
}
