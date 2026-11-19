package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.siloserver.silo.network.*
import org.siloserver.silo.playback.trickplay.TrickplayManifest
import org.siloserver.silo.playback.trickplay.TrickplayTarget
import kotlin.test.*

class TrickplayV2ApiTest {
    private val scope = AuthScopeSnapshot("server", "profile", "https://example.invalid", "proof", identityGeneration = 1)
    private val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = scope }
    private val target = TrickplayTarget(contentId = "movie:heat-1995", fileId = 42)
    private fun client(handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json(SiloJson) } }
    private fun MockRequestHandleScope.reply(body: String, status: HttpStatusCode = HttpStatusCode.OK, type: String = "application/json") =
        respond(body, status, headersOf(HttpHeaders.ContentType to listOf(type)))

    @Test fun readsTheFilesManifestWithSheetsOnTheServerThatAnswered() = runTest {
        val c = client { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/api/v2/watch/movie:heat-1995/trickplay", request.url.encodedPath)
            assertEquals("42", request.url.parameters["file_id"])
            reply(ApiV2Fixtures.body("get_watch_trickplay_ok"))
        }
        try {
            val manifest = assertIs<ApiResult.Success<TrickplayManifest>>(TrickplayV2Api(c, tokens, ApiV2Gate.Unrestricted).manifest(target)).data
            assertEquals("http://localhost/api/v2/artwork/trickplay/42/9913/0.9913.jpg?exp=1767409445&sig=a", manifest.sheetUrls.first())
            assertEquals(11, manifest.sheetUrls.size)
        } finally { c.close() }
    }

    @Test fun aFileWithoutPreviewsIsNotFound() = runTest {
        val c = client { reply(ApiV2Fixtures.body("get_watch_trickplay_not_found"), HttpStatusCode.NotFound, "application/problem+json") }
        try {
            assertEquals(404, assertIs<ApiResult.Error>(TrickplayV2Api(c, tokens, ApiV2Gate.Unrestricted).manifest(target)).code)
        } finally { c.close() }
    }

    @Test fun aManifestThatCannotBeCutIsAnInvalidResponse() = runTest {
        val body = ApiV2Fixtures.with(ApiV2Fixtures.bodyObject("get_watch_trickplay_ok"), "thumbnail_count" to JsonPrimitive(5000))
        val c = client { reply(body.toString()) }
        try {
            assertEquals("invalid_response", assertIs<ApiResult.Error>(TrickplayV2Api(c, tokens, ApiV2Gate.Unrestricted).manifest(target)).error)
        } finally { c.close() }
    }

    @Test fun anotherFilesManifestIsAnInvalidResponse() = runTest {
        val body = ApiV2Fixtures.with(ApiV2Fixtures.bodyObject("get_watch_trickplay_ok"), "file_id" to JsonPrimitive("43"))
        val c = client { reply(body.toString()) }
        try {
            assertEquals("invalid_response", assertIs<ApiResult.Error>(TrickplayV2Api(c, tokens, ApiV2Gate.Unrestricted).manifest(target)).error)
        } finally { c.close() }
    }
}
