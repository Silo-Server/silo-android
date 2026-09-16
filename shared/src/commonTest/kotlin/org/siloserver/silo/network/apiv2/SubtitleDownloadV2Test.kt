package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.siloserver.silo.model.subtitles.*
import org.siloserver.silo.network.*
import kotlin.test.*

class SubtitleDownloadV2Test {
    private var scope = AuthScopeSnapshot("server", "profile", "https://example.invalid", null, identityGeneration = 1)
    private val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = scope }
    private val request = SubtitleDownloadRequest(42, "provider", "opaque+/=9007199254740993", "en", "release", "srt", 9.0, false)
    private val row = """{"id":"7","media_file_id":"42","provider":"provider","language":"en","format":"ass","release_name":"release","score":9,"hearing_impaired":false,"created_at":"2026-01-01T00:00:00.000Z"}"""
    private fun client(handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json(SiloJson) } }
    private fun MockRequestHandleScope.reply(body: String) = respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))

    @Test fun exactProviderIdentityAndServerSelectedFormat() = runTest {
        var calls = 0
        val c = client {
            calls++
            assertEquals("/api/v2/subtitles/download", it.url.encodedPath)
            assertEquals(scope, it.attributes[AuthScopeAttributeKey])
            assertTrue(it.attributes[SingleAttemptAttributeKey])
            val body = SiloJson.parseToJsonElement(it.body.toByteArray().decodeToString()).jsonObject
            assertEquals(JsonPrimitive("42"), body["media_file_id"])
            assertEquals(JsonPrimitive(request.subtitleId), body["subtitle_id"])
            assertFalse("format" in body)
            reply("""{"subtitle":$row}""")
        }
        try {
            val result = assertIs<ApiResult.Success<SubtitleDownloadResponse>>(SubtitleDownloadV2Api(c, tokens, ApiV2Gate.Unrestricted).download(request))
            assertEquals("ass", result.data.subtitle.format); assertEquals(7, result.data.subtitle.id); assertEquals(1,calls)
        } finally { c.close() }
    }
    @Test fun refusesWrongFileNumericNoncanonicalAndOverflowHandles() = runTest {
        var wire = row
        val c = client { reply("""{"subtitle":$wire}""") }
        try {
            for (invalid in listOf(row.replace("\"42\"", "\"43\""), row.replace("\"7\"", "7"),
                row.replace("\"7\"", "\"07\""), row.replace("\"7\"", "\"2147483648\""))) {
                wire = invalid
                assertFalse(SubtitleDownloadV2Api(c, tokens, ApiV2Gate.Unrestricted).download(request) is ApiResult.Success)
            }
        } finally { c.close() }
    }
    @Test fun changedIdentityAndUncertainFailureNeverReplay() = runTest {
        var calls = 0
        val c = client {
            calls++
            scope = scope.copy(identityGeneration = scope.identityGeneration + 1)
            reply("""{"subtitle":$row}""")
        }
        try { assertIs<ApiResult.Error>(SubtitleDownloadV2Api(c, tokens, ApiV2Gate.Unrestricted).download(request)); assertEquals(1,calls) }
        finally { c.close() }
        val failed = client { calls++; throw IllegalStateException("lost response") }
        try { assertIs<ApiResult.NetworkError>(SubtitleDownloadV2Api(failed, tokens, ApiV2Gate.Unrestricted).download(request)); assertEquals(2,calls) }
        finally { failed.close() }
    }
}
