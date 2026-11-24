package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.siloserver.silo.model.section.ProfileSectionSetting
import org.siloserver.silo.model.section.SectionOverride
import org.siloserver.silo.network.*
import kotlin.test.*

class ProfileSectionsV2Test {
    private var owner = AuthScopeSnapshot("s", "p", "https://example.invalid", "pin", identityGeneration = 1)
    private val tokens = object : TokenManager by TokenManagerImpl() {
        override suspend fun snapshotCurrentScope(): AuthScopeSnapshot = owner
    }
    private val json = headersOf(HttpHeaders.ContentType, "application/json")

    private val settingsBody = """{"items":[
        {"id":"recent","section_type":"recently_added","title":"New this week","default_title":"Recently Added","featured":true,"item_limit":20,"hidden":true,"is_custom":false,"customized":true,"position":0,"config":{"days":7}},
        {"id":"ov-custom","section_type":"hidden_gems","title":"Gems","default_title":"","featured":false,"item_limit":12,"hidden":false,"is_custom":true,"customized":false,"position":1}
    ]}"""
    private val overridesBody = """{"items":[
        {"id":"ov-recent","section_id":"recent","position":0,"hidden":true,"removed":false,"section_type":"","title":"New this week","featured":null,"item_limit":null,"is_user_added":false,"user_section_type":"","user_title":"","config":{},"created_at":"2026-01-02T03:04:05.000Z","updated_at":null},
        {"id":"ov-custom","section_id":"","position":1,"hidden":false,"removed":false,"section_type":"hidden_gems","title":"","featured":false,"item_limit":12,"is_user_added":true,"user_section_type":"hidden_gems","user_title":"Gems","user_config":{"min":7},"created_at":null,"updated_at":null}
    ]}"""

    private fun api(handler: MockRequestHandler): Pair<ProfileSectionsV2Api, HttpClient> {
        val client = HttpClient(MockEngine(handler))
        return ProfileSectionsV2Api(client, tokens, ApiV2Gate.Unrestricted) to client
    }

    @Test
    fun readsHomeSettingsAndSavedOverridesForTheOwningProfile() = runTest {
        val (api, client) = api { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("home", request.url.parameters["scope"])
            assertEquals(owner, request.attributes[AuthScopeAttributeKey])
            assertTrue(request.attributes[RequireSiloAuthAttributeKey])
            when (request.url.encodedPath) {
                "/api/v2/profile/sections/settings" -> respond(settingsBody, HttpStatusCode.OK, json)
                "/api/v2/profile/sections" -> respond(overridesBody, HttpStatusCode.OK, json)
                else -> error("unexpected ${request.url}")
            }
        }
        try {
            val settings = api.homeSettings(owner).getOrThrow()
            assertEquals(
                listOf(
                    ProfileSectionSetting(
                        id = "recent", sectionType = "recently_added", title = "New this week",
                        defaultTitle = "Recently Added", featured = true, itemLimit = 20, hidden = true,
                        customized = true, position = 0,
                        config = buildJsonObject { put("days", 7) },
                    ),
                    ProfileSectionSetting(
                        id = "ov-custom", sectionType = "hidden_gems", title = "Gems", itemLimit = 12,
                        isCustom = true, position = 1,
                    ),
                ),
                settings,
            )
            val saved = api.homeOverrides(owner).getOrThrow()
            assertEquals(
                listOf(
                    SectionOverride(
                        id = "ov-recent", sectionId = "recent", position = 0, hidden = true,
                        title = "New this week", config = JsonObject(emptyMap()),
                    ),
                    SectionOverride(
                        id = "ov-custom", position = 1, sectionType = "hidden_gems", featured = false,
                        itemLimit = 12, isUserAdded = true, userSectionType = "hidden_gems",
                        userTitle = "Gems", userConfig = buildJsonObject { put("min", 7) },
                    ),
                ),
                saved,
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun replaceSendsOnlyWritableMembersAndOmitsNoOverrideNulls() = runTest {
        var sent: JsonObject? = null
        val (api, client) = api { request ->
            assertEquals(HttpMethod.Put, request.method)
            assertEquals("/api/v2/profile/sections", request.url.encodedPath)
            assertEquals("home", request.url.parameters["scope"])
            assertEquals(owner, request.attributes[AuthScopeAttributeKey])
            sent = Json.parseToJsonElement((request.body as io.ktor.http.content.TextContent).text).jsonObject
            respond("", HttpStatusCode.NoContent)
        }
        try {
            val result = api.replaceHome(
                owner,
                listOf(
                    SectionOverride(id = "a", sectionId = "recent", position = 0, hidden = true, config = JsonObject(emptyMap())),
                ),
            )
            assertIs<ApiResult.Success<Unit>>(result)
            assertEquals<JsonElement?>(
                Json.parseToJsonElement(
                    """{"overrides":[{"id":"a","section_id":"recent","position":0,"hidden":true,"removed":false,
                    "section_type":"","title":"","config":{},"is_user_added":false,"user_section_type":"","user_title":""}]}""",
                ),
                sent,
            )
        } finally {
            client.close()
        }
    }

    @Test
    fun aProfileSwitchRefusesTheWriteBeforeItIsSent() = runTest {
        var sends = 0
        val (api, client) = api { _ ->
            sends++
            respond("", HttpStatusCode.NoContent)
        }
        try {
            val stale = owner
            owner = owner.copy(profileId = "other")
            val refused = api.replaceHome(stale, emptyList())
            assertIs<ApiResult.Error>(refused)
            assertEquals("identity_changed", refused.error)
            assertEquals(0, sends)
        } finally {
            client.close()
        }
    }
}
