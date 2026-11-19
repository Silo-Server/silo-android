package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.siloserver.silo.network.*
import kotlin.test.*

class ThemeMusicV2ApiTest {
    private var scope = AuthScopeSnapshot("server", "profile", "https://example.invalid", "proof", identityGeneration = 1)
    private val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = scope }
    private fun client(handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData) =
        HttpClient(MockEngine(handler)) { install(ContentNegotiation) { json(SiloJson) } }
    private fun MockRequestHandleScope.reply(body: String, status: HttpStatusCode = HttpStatusCode.OK, type: String = "application/json") =
        respond(body, status, headersOf(HttpHeaders.ContentType to listOf(type)))

    @Test fun capabilityReadsTheThemeDocument() = runTest {
        val c = client { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("/api/v2/catalog/themes/capabilities", request.url.encodedPath)
            reply(ApiV2Fixtures.body("theme_songs_capability"))
        }
        try {
            val capability = assertIs<ApiResult.Success<ThemeSongsCapabilityV2>>(ThemeMusicV2Api(c, tokens, ApiV2Gate.Unrestricted).capability())
            assertTrue(capability.data.isAvailable)
        } finally { c.close() }
    }

    @Test fun grantSendsAcceptedFormatsAndResolvesARelativeUrlAgainstTheServer() = runTest {
        var sent: JsonObject? = null
        val c = client { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/api/v2/catalog/items/movie:heat-1995/themes/7/playback", request.url.encodedPath)
            sent = SiloJson.parseToJsonElement(request.body.toByteArray().decodeToString()).jsonObject
            reply(ApiV2Fixtures.body("theme_songs_playback"))
        }
        try {
            val api = ThemeMusicV2Api(c, tokens, ApiV2Gate.Unrestricted)
            val formats = listOf(ThemeAudioFormatV2("mp3", "mp3"), ThemeAudioFormatV2("m4a", "aac"))
            val grant = assertIs<ApiResult.Success<ThemePlaybackGrantV2>>(api.grant("movie:heat-1995", "7", formats, scope)).data
            assertEquals(
                "http://localhost/api/v2/catalog/items/movie:heat-1995/themes/7/audio?token=fixture-theme-grant",
                grant.url,
            )
            assertEquals<JsonElement?>(
                SiloJson.parseToJsonElement("""{"accepted_formats":[{"container":"mp3","audio_codec":"mp3"},{"container":"m4a","audio_codec":"aac"}]}"""),
                sent,
            )
        } finally { c.close() }
    }

    @Test fun grantKeepsAProxyOriginUrl() = runTest {
        val c = client {
            reply("""{"url":"https://proxy.example/theme/7?token=t","expires_at":"2026-01-02T03:09:05Z","delivery":"converted","content_type":"audio/mp4"}""")
        }
        try {
            val grant = assertIs<ApiResult.Success<ThemePlaybackGrantV2>>(
                ThemeMusicV2Api(c, tokens, ApiV2Gate.Unrestricted).grant("movie:1", "7", emptyList(), scope),
            ).data
            assertEquals("https://proxy.example/theme/7?token=t", grant.url)
            assertTrue(grant.isConverted)
        } finally { c.close() }
    }

    @Test fun grantNeverPrintsItsUrl() {
        val grant = ThemePlaybackGrantV2(url = "https://proxy.example/theme/7?token=secret", delivery = "original")
        assertFalse("secret" in grant.toString())
    }

    @Test fun notAcceptableIsAnError() = runTest {
        val c = client { reply(ApiV2Fixtures.body("theme_songs_playback_not_acceptable"), HttpStatusCode.NotAcceptable, "application/problem+json") }
        try {
            val error = assertIs<ApiResult.Error>(ThemeMusicV2Api(c, tokens, ApiV2Gate.Unrestricted).grant("movie:1", "7", emptyList(), scope))
            assertEquals(406, error.code)
        } finally { c.close() }
    }

    @Test fun aProfileSwitchDuringTheGrantDiscardsIt() = runTest {
        val captured = scope
        val c = client {
            scope = scope.copy(profileId = "other", profileToken = "other-proof")
            reply(ApiV2Fixtures.body("theme_songs_playback"))
        }
        try {
            val error = assertIs<ApiResult.Error>(ThemeMusicV2Api(c, tokens, ApiV2Gate.Unrestricted).grant("movie:1", "7", emptyList(), captured))
            assertEquals("identity_changed", error.error)
        } finally { c.close() }
    }
}
