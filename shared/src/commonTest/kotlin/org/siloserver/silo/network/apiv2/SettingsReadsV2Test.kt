package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.siloserver.silo.model.settings.*
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.*
import kotlin.test.*

class SettingsReadsV2Test {
    private var scope = AuthScopeSnapshot("server", "profile", "https://example.invalid", null, identityGeneration = 1)
    private val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = scope }
    private val row = """{"profile_id":"profile","library_id":"7","show_forced_subtitles":false,"updated_at":"2026-01-01T00:00:00Z"}"""

    @Test fun effectiveUsesExplodedParametersAndPreservesTypedPolicyValues() = runTest {
        val c = HttpClient(MockEngine {
            assertEquals("/api/v2/settings/values/effective", it.url.encodedPath)
            assertEquals(listOf("one", "two"), it.url.parameters.getAll("keys"))
            assertEquals(listOf("7", "8"), it.url.parameters.getAll("library_ids"))
            assertEquals(listOf("tv:1", "tv:2"), it.url.parameters.getAll("series_ids"))
            assertEquals(scope, it.attributes[AuthScopeAttributeKey])
            assertTrue(it.attributes[RequireSiloAuthAttributeKey])
            respond("""{"items":[{"key":"one","value":false,"stored_value":true,"source":"profile_library","scope":"profile_library","profile_id":"profile","library_id":"7","constrained":true,"constraint_kind":"locked","suggested_values":["choice"]}],"revision":12}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType,"application/json"))
        })
        try {
            val result = assertIs<ApiResult.Success<EffectiveSettingValuesResponse>>(SettingsApi(c, SettingsReadsV2Api(c,tokens)).getEffectiveValues(listOf("one","two"), listOf(7,8), listOf("tv:1","tv:2"))).data
            assertEquals(12,result.revision)
            val value = result.settings.single()
            assertEquals(7,value.libraryId); assertEquals(JsonPrimitive(false),value.value)
            assertEquals(JsonPrimitive(true),value.storedValue); assertTrue(value.constrained)
            assertEquals(listOf("choice"),value.suggestedValues)
        } finally { c.close() }
    }

    @Test fun libraryListRejectsForeignDuplicateNumericAndOverflowIdentities() = runTest {
        var body = """{"items":[$row]}"""
        val c = HttpClient(MockEngine {
            assertEquals("/api/v2/library-playback-prefs",it.url.encodedPath)
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType,"application/json"))
        })
        try {
            val api = LibraryPlaybackPrefsApi(c,SettingsReadsV2Api(c,tokens))
            val pref = assertIs<ApiResult.Success<LibraryPlaybackPrefsResponse>>(api.list()).data.preferences.single()
            assertEquals(false,pref.showForcedSubtitles); assertNull(pref.audioLanguage)
            for (bad in listOf(row.replace("profile\"", "foreign\""), "$row,$row", row.replace("\"7\"","7"),row.replace("\"7\"","\"2147483648\""))) {
                body = """{"items":[$bad]}"""; assertIs<ApiResult.Error>(api.list())
            }
            body = """{"items":[]}"""
            assertTrue(assertIs<ApiResult.Success<LibraryPlaybackPrefsResponse>>(api.list()).data.preferences.isEmpty())
        } finally { c.close() }
    }

    @Test fun overlayPreservesDisabledAndStaleRepliesNeverFallBack() = runTest {
        var replace = false
        var sends = 0
        val c = HttpClient(MockEngine {
            sends++
            assertEquals("/api/v2/settings/overlay-config",it.url.encodedPath)
            if (replace) scope = scope.copy(identityGeneration = 2)
            respond("""{"enabled":false}""",HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
        })
        try {
            val api = SettingsApi(c,SettingsReadsV2Api(c,tokens))
            assertFalse(assertIs<ApiResult.Success<OverlayConfigResponse>>(api.overlayConfig()).data.enabled)
            replace = true
            assertIs<ApiResult.Error>(api.overlayConfig())
            assertEquals(2,sends)
        } finally { c.close() }
    }
}
