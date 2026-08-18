package org.siloserver.silo.network.api

import org.siloserver.silo.model.settings.EffectiveSettingValue
import org.siloserver.silo.model.settings.PrimaryMenuItem
import org.siloserver.silo.model.settings.SettingScopeIdentity
import org.siloserver.silo.model.settings.SiloClientFamily
import org.siloserver.silo.model.settings.SettingsContractCapabilities
import org.siloserver.silo.model.settings.UiCustomizationCodec
import org.siloserver.silo.model.settings.supportsUiCustomization
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.SiloJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests for the canonical settings API surface: `/settings/contract` and the `/settings/values` routes. */
class SettingsApiValuesTest {

    @Test
    fun `ui customization fails closed unless revision atomic and idempotency match`() {
        assertFalse(
            SettingsContractCapabilities(
                revision = 4,
                supportsBatchedEffective = true,
                supportsIdempotentWrites = true,
                supportsAtomicShortcuts = true,
            ).supportsUiCustomization,
        )
        assertFalse(SettingsContractCapabilities(revision = 5).supportsUiCustomization)
        assertFalse(
            SettingsContractCapabilities(
                revision = 5,
                supportsIdempotentWrites = true,
                supportsAtomicShortcuts = true,
            ).supportsUiCustomization,
        )
        assertFalse(
            SettingsContractCapabilities(
                revision = 5,
                supportsBatchedEffective = true,
                supportsAtomicShortcuts = true,
            ).supportsUiCustomization,
        )
        assertFalse(
            SettingsContractCapabilities(
                revision = 5,
                supportsBatchedEffective = true,
                supportsIdempotentWrites = true,
            ).supportsUiCustomization,
        )
        assertTrue(
            SettingsContractCapabilities(
                revision = 5,
                supportsBatchedEffective = true,
                supportsIdempotentWrites = true,
                supportsAtomicShortcuts = true,
            ).supportsUiCustomization,
        )
    }

    private class Captured {
        var method: HttpMethod? = null
        var path: String = ""
        var query: Map<String, String?> = emptyMap()
        var headers: Headers = headersOf()
        var body: String = ""
    }

    private fun api(
        status: HttpStatusCode = HttpStatusCode.OK,
        responseBody: String = "{}",
        responseContentType: String = "application/json",
        captured: Captured = Captured(),
    ): Pair<SettingsApi, Captured> {
        val client = HttpClient(
            MockEngine { request ->
                captured.method = request.method
                captured.path = request.url.encodedPath
                captured.query = request.url.parameters.names()
                    .associateWith { request.url.parameters[it] }
                captured.headers = request.headers
                captured.body = request.body.toByteArray().decodeToString()
                respond(
                    content = responseBody,
                    status = status,
                    headers = headersOf(HttpHeaders.ContentType, responseContentType),
                )
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
        }
        return SettingsApi(client) to captured
    }

    // ---- capabilities / server-upgrade-required ----

    @Test
    fun `getContractCapabilities parses the server capabilities shape`() = runTest {
        val (api, captured) = api(
            responseBody = """
                {"api_version":1,"revision":5,"contract_etag":"\"abc123\"",
                 "definition_count":41,
                 "scopes":["account","profile","profile_client","profile_device","profile_library","profile_series"],
                 "supports_batched_effective":true,"supports_idempotent_writes":true,
                 "supports_atomic_shortcuts":true}
            """.trimIndent(),
        )

        val result = api.getContractCapabilities()

        assertEquals("/api/v1/settings/contract/capabilities", captured.path)
        assertIs<SettingsCapabilitiesResult.Available>(result)
        assertEquals(1, result.capabilities.apiVersion)
        assertEquals(5, result.capabilities.revision)
        assertEquals("\"abc123\"", result.capabilities.contractEtag)
        assertEquals(41, result.capabilities.definitionCount)
        assertEquals(6, result.capabilities.scopes.size)
        assertTrue("profile_client" in result.capabilities.scopes)
        assertTrue(result.capabilities.supportsBatchedEffective)
        assertTrue(result.capabilities.supportsIdempotentWrites)
        assertTrue(result.capabilities.supportsAtomicShortcuts)
    }

    @Test
    fun `getContractCapabilities maps a routeless 404 to ServerUpgradeRequired`() = runTest {
        // An old server has no /settings/contract routes: the router answers a
        // plain-text 404, not the JSON error shape.
        val (api, _) = api(
            status = HttpStatusCode.NotFound,
            responseBody = "404 page not found",
            responseContentType = "text/plain",
        )

        assertIs<SettingsCapabilitiesResult.ServerUpgradeRequired>(api.getContractCapabilities())
    }

    @Test
    fun `getContractCapabilities keeps a profile-not-found 404 off the upgrade path`() = runTest {
        // The route sits behind the viewer-access middleware, which answers
        // this exact body when the X-Profile-Id we send names a profile the
        // household deleted from another device. The server is current; the
        // fix is picking a profile, so the upgrade notice must not appear.
        val (api, _) = api(
            status = HttpStatusCode.NotFound,
            responseBody = """{"error":"not_found","message":"Profile not found"}""",
        )

        val result = api.getContractCapabilities()

        assertIs<SettingsCapabilitiesResult.Error>(result)
        assertEquals(404, result.code)
        assertEquals("not_found", result.error)
    }

    @Test
    fun `getContractCapabilities keeps other failures on the generic error path`() = runTest {
        val (api, _) = api(
            status = HttpStatusCode.InternalServerError,
            responseBody = """{"error":"internal_error","message":"Failed to read the settings contract"}""",
        )

        val result = api.getContractCapabilities()

        assertIs<SettingsCapabilitiesResult.Error>(result)
        assertEquals(500, result.code)
        assertEquals("internal_error", result.error)
    }

    // ---- batched effective resolution ----

    @Test
    fun `getEffectiveValues sends csv params and parses constrained values`() = runTest {
        val (api, captured) = api(
            responseBody = """
                {"settings":[
                   {"key":"playback.auto_play_next","value":true,"source":"default",
                    "suggested_values":["en","pt-BR"]},
                   {"key":"playback.preferred_quality","value":"1080p","source":"profile_device",
                    "stored_value":"2160p","constrained":true,"constraint_kind":"ceiling",
                    "scope":"profile_device","profile_id":"p1","device_id":"d1"}
                 ],
                 "revision":4}
            """.trimIndent(),
        )

        val result = api.getEffectiveValues(
            keys = listOf("playback.auto_play_next", "playback.preferred_quality"),
            libraryIds = listOf(3, 7),
            seriesIds = listOf("s1", "s2"),
            profileId = "profile-pinned-at-request-start",
        )

        assertEquals("/api/v1/settings/values/effective", captured.path)
        assertEquals("playback.auto_play_next,playback.preferred_quality", captured.query["keys"])
        assertEquals("3,7", captured.query["library_ids"])
        assertEquals("s1,s2", captured.query["series_ids"])
        assertEquals("profile-pinned-at-request-start", captured.headers["X-Profile-Id"])

        assertIs<ApiResult.Success<*>>(result)
        val response = (result as ApiResult.Success).data
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
    }

    @Test
    fun `getEffectiveValues omits empty params so the server resolves every key`() = runTest {
        val (api, captured) = api(responseBody = """{"settings":[],"revision":1}""")

        api.getEffectiveValues()

        assertTrue(captured.query.isEmpty())
    }

    // ---- writes ----

    @Test
    fun `putValue sends the scope identity mutation id and typed body`() = runTest {
        val (api, captured) = api(
            responseBody = """
                {"key":"playback.preferred_quality","scope":"profile_library",
                 "profile_id":"p1","library_id":7,"value":"1080p",
                 "revision":12,"updated_at":"2026-07-28T00:00:00Z"}
            """.trimIndent(),
        )

        val result = api.putValue(
            key = "playback.preferred_quality",
            scope = SettingScopeIdentity.profileLibrary(7),
            value = JsonPrimitive("1080p"),
            mutationId = "mut-1",
        )

        assertEquals(HttpMethod.Put, captured.method)
        assertEquals("/api/v1/settings/values/playback.preferred_quality", captured.path)
        assertEquals("profile_library", captured.query["scope"])
        assertEquals("7", captured.query["library_id"])
        assertEquals("mut-1", captured.headers["X-Silo-Mutation-Id"])
        assertEquals("""{"value":"1080p"}""", captured.body)

        assertIs<ApiResult.Success<*>>(result)
        val receipt = (result as ApiResult.Success).data
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
                 "profile_id":"p1","value":{"size":"large","edge":"drop_shadow"}}
            """.trimIndent(),
        )

        val result = api.putValue(
            key = "playback.subtitle_appearance",
            scope = SettingScopeIdentity.profile(),
            value = buildJsonObject {
                put("size", "large")
                put("edge", "drop_shadow")
            },
            mutationId = "mut-2",
        )

        assertEquals("""{"value":{"size":"large","edge":"drop_shadow"}}""", captured.body)
        assertIs<ApiResult.Success<*>>(result)
        val receipt = (result as ApiResult.Success).data
        assertEquals("large", receipt.value.jsonObject["size"]?.jsonPrimitive?.content)
        // A replayed receipt omits revision/updated_at; defaults must hold.
        assertEquals(0L, receipt.revision)
        assertNull(receipt.updatedAt)
    }

    @Test
    fun `putValue sends profile client scope and decodes its family receipt`() = runTest {
        val (api, captured) = api(
            responseBody = """
                {"key":"ui.card_presentation","scope":"profile_client",
                 "profile_id":"p1","client_family":"tv",
                 "value":{"poster_size":"large","caption":"artwork"}}
            """.trimIndent(),
        )

        val result = api.putValue(
            key = "ui.card_presentation",
            scope = SettingScopeIdentity.profileClient(SiloClientFamily.MOBILE),
            value = buildJsonObject {
                put("poster_size", "large")
                put("caption", "artwork")
            },
            mutationId = "mut-family",
        )

        assertEquals("profile_client", captured.query["scope"])
        assertNull(captured.query["client_family"])
        assertEquals("mobile", captured.headers["X-Silo-Client-Family"])
        assertIs<ApiResult.Success<*>>(result)
        assertEquals("tv", (result as ApiResult.Success).data.clientFamily)
    }

    @Test
    fun `putValue surfaces a mutation id conflict as a typed error`() = runTest {
        val (api, _) = api(
            status = HttpStatusCode.Conflict,
            responseBody = """{"error":"mutation_id_conflict","message":"This mutation id was used for a different write"}""",
        )

        val result = api.putValue(
            key = "playback.preferred_quality",
            scope = SettingScopeIdentity.profile(),
            value = JsonPrimitive("720p"),
            mutationId = "mut-reused",
        )

        assertIs<ApiResult.Error>(result)
        assertEquals(409, result.code)
        assertEquals("mutation_id_conflict", result.error)
    }

    @Test
    fun `putValue lets an explicit profile id override the session header`() = runTest {
        val (api, captured) = api(responseBody = """{"key":"k","scope":"profile","value":true}""")

        api.putValue(
            key = "playback.auto_play_next",
            scope = SettingScopeIdentity.profile(),
            value = JsonPrimitive(true),
            mutationId = "mut-3",
            profileId = "child-profile",
        )

        assertEquals("child-profile", captured.headers["X-Profile-Id"])
    }

    @Test
    fun `putNavigationShortcutItem sends atomic intent body and stable mutation id`() = runTest {
        val item = PrimaryMenuItem.Section(7, "recent", "Recently Added")
        val encodedItem = checkNotNull(UiCustomizationCodec.encodeShortcutItem(item))
        val (api, captured) = api(
            responseBody = """
                {"key":"nav.shortcuts","scope":"profile","profile_id":"p1",
                 "value":{"items":[{"type":"section","library_id":7,
                 "section_id":"recent","label":"Recently Added"}]},"revision":4}
            """.trimIndent(),
        )

        val result = api.putNavigationShortcutItem(
            item = encodedItem,
            present = true,
            mutationId = "shortcut-mut-1",
            profileId = "profile-pinned-at-request-start",
        )

        assertEquals(HttpMethod.Put, captured.method)
        assertEquals("/api/v1/settings/values/nav.shortcuts/item", captured.path)
        assertTrue(captured.query.isEmpty())
        assertEquals("shortcut-mut-1", captured.headers["X-Silo-Mutation-Id"])
        assertEquals(
            "profile-pinned-at-request-start",
            captured.headers["X-Profile-Id"],
        )
        val body = SiloJson.parseToJsonElement(captured.body).jsonObject
        assertEquals(encodedItem, body["item"])
        assertEquals(true, body["present"]?.jsonPrimitive?.content?.toBoolean())

        assertIs<ApiResult.Success<*>>(result)
        assertEquals("nav.shortcuts", (result as ApiResult.Success).data.key)
        assertEquals(4L, result.data.revision)
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
        assertEquals("/api/v1/settings/values/playback.subtitle_language", captured.path)
        assertEquals("profile_series", captured.query["scope"])
        assertEquals("series-9", captured.query["series_id"])
        assertIs<ApiResult.Success<*>>(result)
    }

    @Test
    fun `deleteValue reports nothing-set-here as a typed 404`() = runTest {
        val (api, _) = api(
            status = HttpStatusCode.NotFound,
            responseBody = """{"error":"not_found","message":"No value is set at this scope"}""",
        )

        val result = api.deleteValue(
            key = "playback.subtitle_language",
            scope = SettingScopeIdentity.account(),
        )

        assertIs<ApiResult.Error>(result)
        assertEquals(404, result.code)
        assertEquals("not_found", result.error)
    }

    // ---- mutation ids ----

    @Test
    fun `newSettingMutationId yields distinct non-blank ids`() {
        val first = newSettingMutationId()
        val second = newSettingMutationId()
        assertTrue(first.isNotBlank())
        assertTrue(second.isNotBlank())
        assertTrue(first != second)
    }
}
