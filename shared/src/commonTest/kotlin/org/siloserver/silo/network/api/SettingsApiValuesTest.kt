package org.siloserver.silo.network.api

import org.siloserver.silo.network.apiv2.ApiV2Gate

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.siloserver.silo.model.settings.EffectiveSettingValue
import org.siloserver.silo.model.settings.LibraryPlaybackPrefRequest
import org.siloserver.silo.model.settings.SettingScopeIdentity
import org.siloserver.silo.model.settings.SettingsContractCapabilities
import org.siloserver.silo.model.settings.SubtitleAppearance
import org.siloserver.silo.model.settings.SubtitleFontSizePreset
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeAttributeKey
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.RequireSiloAuthAttributeKey
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.apiv2.SettingsV2Api
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the v2 settings surface: `/settings/contract`, `/settings/values`,
 * the device subtitle-appearance override, and `/library-playback-prefs`.
 */
class SettingsApiValuesTest {

    private var scope = AuthScopeSnapshot("server", "p1", "https://example.invalid", "proof", identityGeneration = 1, credentialEpoch = 2)
    private val tokens = object : TokenManager by TokenManagerImpl() {
        override suspend fun snapshotCurrentScope() = scope
    }

    private class Captured {
        var method: HttpMethod? = null
        var path: String = ""
        var query: Map<String, List<String>> = emptyMap()
        var headers: Headers = headersOf()
        var body: String = ""
        var pinned: AuthScopeSnapshot? = null
        var requiresAuth: Boolean = false
        var sends: Int = 0
    }

    private fun client(
        status: () -> HttpStatusCode,
        responseBody: () -> String,
        responseContentType: String = "application/json",
        captured: Captured,
        onRequest: () -> Unit = {},
    ): HttpClient = HttpClient(
        MockEngine { request ->
            captured.sends++
            captured.method = request.method
            captured.path = request.url.encodedPath
            captured.query = request.url.parameters.names().associateWith { request.url.parameters.getAll(it).orEmpty() }
            captured.headers = request.headers
            captured.body = request.body.toByteArray().decodeToString()
            captured.pinned = request.attributes.getOrNull(AuthScopeAttributeKey)
            captured.requiresAuth = request.attributes.getOrNull(RequireSiloAuthAttributeKey) == true
            onRequest()
            respond(
                content = responseBody(),
                status = status(),
                headers = headersOf(HttpHeaders.ContentType, responseContentType),
            )
        },
    ) {
        install(ContentNegotiation) { json(SiloJson) }
        install(DefaultRequest) {
            headers.append("X-Silo-Device-Id", "device")
            headers.append("X-Silo-Client-Family", "mobile")
        }
    }

    private fun api(
        status: HttpStatusCode = HttpStatusCode.OK,
        responseBody: String = "{}",
        responseContentType: String = "application/json",
        captured: Captured = Captured(),
    ): Pair<SettingsApi, Captured> {
        val c = client({ status }, { responseBody }, responseContentType, captured)
        return SettingsApi(SettingsV2Api(c, tokens, ApiV2Gate.Unrestricted)) to captured
    }

    private fun problem(code: String, detail: String) =
        """{"type":"https://silo.example/problems/$code","title":"$code","status":0,"detail":"$detail"}"""

    // ---- capabilities / server-upgrade-required ----

    @Test
    fun `getContractCapabilities parses the server capabilities shape and never promises receipts`() = runTest {
        val (api, captured) = api(
            responseBody = """
                {"api_version":1,"revision":"36e767e3","manifest_revision":4,"state":"available","allowed":true,"contract_etag":"\"abc123\"",
                 "definition_count":41,
                 "scopes":["account","profile","profile_device","profile_library","profile_series"],
                 "supports_batched_effective":true,"supports_idempotent_writes":true}
            """.trimIndent(),
        )

        val result = api.getContractCapabilities()

        assertEquals("/api/v2/settings/contract/capabilities", captured.path)
        assertEquals(scope, captured.pinned)
        assertTrue(captured.requiresAuth)
        assertIs<ApiResult.Success<SettingsContractCapabilities>>(result)
        assertEquals(1, result.data.apiVersion)
        assertEquals("36e767e3", result.data.revision)
        assertEquals(4, result.data.manifestRevision)
        assertEquals("\"abc123\"", result.data.contractEtag)
        assertEquals(41, result.data.definitionCount)
        assertEquals(5, result.data.scopes.size)
        assertTrue(result.data.supportsBatchedEffective)
    }

    @Test
    fun `getContractCapabilities keeps a routeless 404 on the generic error path`() = runTest {
        val (api, _) = api(
            status = HttpStatusCode.NotFound,
            responseBody = "404 page not found",
            responseContentType = "text/plain",
        )

        val result = api.getContractCapabilities()

        assertIs<ApiResult.Error>(result)
        assertEquals(404, result.code)
    }

    @Test
    fun `getContractCapabilities keeps a profile-not-found 404 off the upgrade path`() = runTest {
        val (api, _) = api(
            status = HttpStatusCode.NotFound,
            responseBody = problem("not_found", "Profile not found"),
            responseContentType = "application/problem+json",
        )

        val result = api.getContractCapabilities()

        assertIs<ApiResult.Error>(result)
        assertEquals(404, result.code)
        assertEquals("not_found", result.error)
    }

    @Test
    fun `getContractCapabilities keeps other failures on the generic error path`() = runTest {
        val (api, _) = api(
            status = HttpStatusCode.InternalServerError,
            responseBody = problem("internal_error", "Failed to read the settings contract"),
            responseContentType = "application/problem+json",
        )

        val result = api.getContractCapabilities()

        assertIs<ApiResult.Error>(result)
        assertEquals(500, result.code)
        assertEquals("internal_error", result.error)
    }

    // ---- batched effective resolution ----

    @Test
    fun `getEffectiveValues sends exploded params and parses constrained values`() = runTest {
        val (api, captured) = api(
            responseBody = """
                {"items":[
                   {"key":"playback.auto_play_next","value":true,"source":"default",
                    "suggested_values":["en","pt-BR"]},
                   {"key":"playback.preferred_quality","value":"1080p","source":"profile_device",
                    "stored_value":"2160p","constrained":true,"constraint_kind":"ceiling",
                    "scope":"profile_device","profile_id":"p1","device_id":"d1","library_id":"7"}
                 ],
                 "revision":4}
            """.trimIndent(),
        )

        val result = api.getEffectiveValues(
            keys = listOf("playback.auto_play_next", "playback.preferred_quality"),
            libraryIds = listOf(3, 7),
            seriesIds = listOf("s1", "s2"),
        )

        assertEquals("/api/v2/settings/values/effective", captured.path)
        assertEquals(listOf("playback.auto_play_next", "playback.preferred_quality"), captured.query["keys"])
        assertEquals(listOf("3", "7"), captured.query["library_ids"])
        assertEquals(listOf("s1", "s2"), captured.query["series_ids"])

        val response = assertIs<ApiResult.Success<*>>(result).data as org.siloserver.silo.model.settings.EffectiveSettingValuesResponse
        assertEquals(4, response.revision)

        val default = response.settings[0]
        assertEquals(EffectiveSettingValue.SOURCE_DEFAULT, default.source)
        assertEquals(true, default.value.jsonPrimitive.content.toBoolean())
        assertFalse(default.constrained)
        assertNull(default.scope)
        assertEquals(listOf("en", "pt-BR"), default.suggestedValues)

        val capped = response.settings[1]
        assertEquals("1080p", capped.value.jsonPrimitive.content)
        assertEquals("2160p", capped.storedValue?.jsonPrimitive?.content)
        assertTrue(capped.constrained)
        assertEquals("ceiling", capped.constraintKind)
        assertEquals("profile_device", capped.scope)
        assertEquals("d1", capped.deviceId)
        assertEquals(7, capped.libraryId)
    }

    @Test
    fun `getEffectiveValues omits empty params so the server resolves every key`() = runTest {
        val (api, captured) = api(responseBody = """{"items":[],"revision":1}""")

        api.getEffectiveValues()

        assertTrue(captured.query.isEmpty())
    }

    @Test
    fun `getEffectiveValues rejects rows for another profile`() = runTest {
        val (api, _) = api(responseBody = """{"items":[{"key":"k","value":true,"source":"profile","profile_id":"other"}],"revision":1}""")

        val result = api.getEffectiveValues(listOf("k"))

        assertIs<ApiResult.Error>(result)
        assertEquals("invalid_response", result.error)
    }

    @Test
    fun `migration read is pinned to the original authority and never retargets after replacement`() = runTest {
        val original = scope
        val captured = Captured()
        val c = client({ HttpStatusCode.OK }, { """{"items":[{"key":"one","value":false,"source":"default"}],"revision":12}""" }, captured = captured) {
            scope = scope.copy(profileToken = "replacement")
        }
        val api = SettingsApi(SettingsV2Api(c, tokens, ApiV2Gate.Unrestricted))

        assertIs<ApiResult.Error>(api.getEffectiveValues(listOf("one"), authority = original))
        assertEquals(original, captured.pinned)
        assertIs<ApiResult.Error>(api.getEffectiveValues(listOf("one"), authority = original))
        assertEquals(1, captured.sends)
    }

    // ---- overlay config ----

    @Test
    fun `overlayConfig preserves disabled and a stale reply never succeeds`() = runTest {
        var replace = false
        val captured = Captured()
        val c = client({ HttpStatusCode.OK }, { """{"enabled":false}""" }, captured = captured) {
            if (replace) scope = scope.copy(identityGeneration = 2)
        }
        val api = SettingsApi(SettingsV2Api(c, tokens, ApiV2Gate.Unrestricted))

        assertEquals("/api/v2/settings/overlay-config".also { api.overlayConfig() }, captured.path)
        assertFalse(assertIs<ApiResult.Success<OverlayConfigResponse>>(api.overlayConfig()).data.enabled)
        replace = true
        assertIs<ApiResult.Error>(api.overlayConfig())
    }

    // ---- writes ----

    @Test
    fun `putValue sends the scope identity as query and the typed body without a mutation id`() = runTest {
        val (api, captured) = api(
            responseBody = """
                {"key":"playback.preferred_quality","scope":"profile_library",
                 "profile_id":"p1","library_id":"7","value":"1080p",
                 "revision":12,"updated_at":"2026-07-28T00:00:00Z"}
            """.trimIndent(),
        )

        val result = api.putValue(
            key = "playback.preferred_quality",
            scope = SettingScopeIdentity.profileLibrary(7),
            value = JsonPrimitive("1080p"),
        )

        assertEquals(HttpMethod.Put, captured.method)
        assertEquals("/api/v2/settings/values/playback.preferred_quality", captured.path)
        assertEquals(listOf("profile_library"), captured.query["scope"])
        assertEquals(listOf("7"), captured.query["library_id"])
        assertNull(captured.headers["X-Silo-Mutation-Id"])
        assertEquals("""{"value":"1080p"}""", captured.body)
        assertEquals(scope, captured.pinned)

        val receipt = assertIs<ApiResult.Success<*>>(result).data as org.siloserver.silo.model.settings.StoredSettingValue
        assertEquals("profile_library", receipt.scope)
        assertEquals(7, receipt.libraryId)
        assertEquals(12L, receipt.revision)
        assertEquals("2026-07-28T00:00:00Z", receipt.updatedAt)
    }

    @Test
    fun `putValue round-trips an object value`() = runTest {
        val (api, captured) = api(
            responseBody = """
                {"key":"playback.subtitle_appearance","scope":"profile",
                 "profile_id":"p1","value":{"size":"large","edge":"drop_shadow"},"revision":1}
            """.trimIndent(),
        )

        val result = api.putValue(
            key = "playback.subtitle_appearance",
            scope = SettingScopeIdentity.profile(),
            value = buildJsonObject {
                put("size", "large")
                put("edge", "drop_shadow")
            },
        )

        assertEquals("""{"value":{"size":"large","edge":"drop_shadow"}}""", captured.body)
        val receipt = assertIs<ApiResult.Success<*>>(result).data as org.siloserver.silo.model.settings.StoredSettingValue
        assertEquals("large", receipt.value.jsonObject["size"]?.jsonPrimitive?.content)
    }

    @Test
    fun `putValue surfaces a problem body as a typed error`() = runTest {
        val (api, _) = api(
            status = HttpStatusCode.Conflict,
            responseBody = problem("conflict", "This value changed elsewhere"),
            responseContentType = "application/problem+json",
        )

        val result = api.putValue(
            key = "playback.preferred_quality",
            scope = SettingScopeIdentity.profile(),
            value = JsonPrimitive("720p"),
        )

        assertIs<ApiResult.Error>(result)
        assertEquals(409, result.code)
        assertEquals("conflict", result.error)
    }

    @Test
    fun `putValue sends an explicit target profile as a query parameter`() = runTest {
        val (api, captured) = api(responseBody = """{"key":"playback.auto_play_next","scope":"profile","profile_id":"child-profile","value":true,"revision":1}""")

        val result = api.putValue(
            key = "playback.auto_play_next",
            scope = SettingScopeIdentity.profile(),
            value = JsonPrimitive(true),
            profileId = "child-profile",
        )

        assertEquals(listOf("child-profile"), captured.query["profile_id"])
        assertNull(captured.headers["X-Profile-Id"])
        assertIs<ApiResult.Success<*>>(result)
    }

    @Test
    fun `putValue receipt must match the declared device and client family`() = runTest {
        var settingScope = "profile_device"
        var receiptIdentity = "\"device_id\":\"device\""
        val c = client({ HttpStatusCode.OK }, {
            """{"key":"ui.theme","scope":"$settingScope","profile_id":"p1",$receiptIdentity,"value":"dark","revision":1}"""
        }, captured = Captured())
        val api = SettingsV2Api(c, tokens, ApiV2Gate.Unrestricted)

        assertIs<ApiResult.Success<*>>(api.put("ui.theme", SettingScopeIdentity.profileDevice(), JsonPrimitive("dark"), null, scope))
        receiptIdentity = "\"device_id\":\"foreign\""
        assertIs<ApiResult.Error>(api.put("ui.theme", SettingScopeIdentity.profileDevice(), JsonPrimitive("dark"), null, scope))
        settingScope = "profile_client"
        receiptIdentity = "\"client_family\":\"mobile\""
        assertEquals("mobile", assertIs<ApiResult.Success<org.siloserver.silo.model.settings.StoredSettingValue>>(api.put("ui.theme", SettingScopeIdentity.profileClient(), JsonPrimitive("dark"), null, scope)).data.clientFamily)
        receiptIdentity = "\"client_family\":\"tv\""
        assertIs<ApiResult.Error>(api.put("ui.theme", SettingScopeIdentity.profileClient(), JsonPrimitive("dark"), null, scope))
    }

    // ---- deletes ----

    @Test
    fun `deleteValue sends the scope identity and maps 204 to success`() = runTest {
        val (api, captured) = api(status = HttpStatusCode.NoContent, responseBody = "")

        val result = api.deleteValue(
            key = "playback.subtitle_language",
            scope = SettingScopeIdentity.profileSeries("series-9"),
        )

        assertEquals(HttpMethod.Delete, captured.method)
        assertEquals("/api/v2/settings/values/playback.subtitle_language", captured.path)
        assertEquals(listOf("profile_series"), captured.query["scope"])
        assertEquals(listOf("series-9"), captured.query["series_id"])
        assertIs<ApiResult.Success<*>>(result)
    }

    @Test
    fun `deleteValue reports nothing-set-here as a typed 404`() = runTest {
        val (api, _) = api(
            status = HttpStatusCode.NotFound,
            responseBody = problem("not_found", "No value is set at this scope"),
            responseContentType = "application/problem+json",
        )

        val result = api.deleteValue(
            key = "playback.subtitle_language",
            scope = SettingScopeIdentity.account(),
        )

        assertIs<ApiResult.Error>(result)
        assertEquals(404, result.code)
        assertEquals("not_found", result.error)
    }

    @Test
    fun `deleteValue requires 204 and a replaced authority never succeeds`() = runTest {
        var status = HttpStatusCode.OK
        var change = false
        val original = scope
        val captured = Captured()
        val c = client({ status }, { "" }, captured = captured) {
            if (change) scope = scope.copy(profileToken = "replacement")
        }
        val api = SettingsV2Api(c, tokens, ApiV2Gate.Unrestricted)

        assertFalse(api.delete("ui.theme", SettingScopeIdentity.profile(), null, original) is ApiResult.Success)
        status = HttpStatusCode.NoContent
        assertIs<ApiResult.Success<Unit>>(api.delete("ui.theme", SettingScopeIdentity.profile(), null, original))
        change = true
        assertIs<ApiResult.Error>(api.delete("ui.theme", SettingScopeIdentity.profile(), null, original))
        assertIs<ApiResult.Error>(api.delete("ui.theme", SettingScopeIdentity.profile(), null, original))
        assertEquals(3, captured.sends)
    }

    // ---- library playback prefs ----

    private val prefRow = """{"profile_id":"p1","library_id":"7","show_forced_subtitles":false,"updated_at":"2026-01-01T00:00:00Z"}"""

    @Test
    fun `library list rejects foreign duplicate numeric and overflow identities`() = runTest {
        var body = """{"items":[$prefRow]}"""
        val captured = Captured()
        val c = client({ HttpStatusCode.OK }, { body }, captured = captured)
        val api = LibraryPlaybackPrefsApi(SettingsV2Api(c, tokens, ApiV2Gate.Unrestricted))

        val pref = assertIs<ApiResult.Success<org.siloserver.silo.model.settings.LibraryPlaybackPrefsResponse>>(api.list()).data.preferences.single()
        assertEquals("/api/v2/library-playback-prefs", captured.path)
        assertEquals(false, pref.showForcedSubtitles)
        assertNull(pref.audioLanguage)
        for (bad in listOf(prefRow.replace("p1\"", "foreign\""), "$prefRow,$prefRow", prefRow.replace("\"7\"", "7"), prefRow.replace("\"7\"", "\"2147483648\""))) {
            body = """{"items":[$bad]}"""
            assertIs<ApiResult.Error>(api.list())
        }
        body = """{"items":[]}"""
        assertTrue(assertIs<ApiResult.Success<org.siloserver.silo.model.settings.LibraryPlaybackPrefsResponse>>(api.list()).data.preferences.isEmpty())
    }

    @Test
    fun `library set patches every member explicitly so nulls clear`() = runTest {
        val captured = Captured()
        val c = client({ HttpStatusCode.NoContent }, { "" }, captured = captured)
        val api = LibraryPlaybackPrefsApi(SettingsV2Api(c, tokens, ApiV2Gate.Unrestricted))

        val result = api.set(7, LibraryPlaybackPrefRequest(audioLanguage = "en", subtitleMode = "always"))

        assertEquals(HttpMethod.Patch, captured.method)
        assertEquals("/api/v2/library-playback-prefs/7", captured.path)
        assertEquals(scope, captured.pinned)
        assertEquals(
            """{"audio_language":"en","subtitle_language":null,"subtitle_mode":"always","show_forced_subtitles":null}""",
            captured.body,
        )
        assertIs<ApiResult.Success<Unit>>(result)
    }

    @Test
    fun `library delete uses the v2 path and requires 204`() = runTest {
        var status = HttpStatusCode.OK
        val captured = Captured()
        val c = client({ status }, { "" }, captured = captured)
        val api = LibraryPlaybackPrefsApi(SettingsV2Api(c, tokens, ApiV2Gate.Unrestricted))

        assertFalse(api.delete(7) is ApiResult.Success)
        status = HttpStatusCode.NoContent
        assertIs<ApiResult.Success<Unit>>(api.delete(7))
        assertEquals(HttpMethod.Delete, captured.method)
        assertEquals("/api/v2/library-playback-prefs/7", captured.path)
    }

}
