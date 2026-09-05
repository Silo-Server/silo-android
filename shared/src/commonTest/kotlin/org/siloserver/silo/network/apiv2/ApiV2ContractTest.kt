package org.siloserver.silo.network.apiv2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.siloserver.silo.network.apiv2.ApiV2Fixtures.plusUnknown
import org.siloserver.silo.network.apiv2.ApiV2Fixtures.with

/**
 * Decodes every vendored API v2 fixture with the production `SiloJson` and
 * pins the null/absence semantics each UI flow relies on. Fixture bodies are
 * byte-identical copies of the server's generated contract fixtures (see
 * `shared/src/commonTest/resources/api/v2/fixtures/SOURCE`).
 */
class ApiV2ContractTest {

    @Test
    fun sourceRecordsTheServerCommit() {
        val commit = ApiV2Fixtures.source["commit"]
        assertNotNull(commit)
        assertTrue(Regex("^[0-9a-f]{40}$").matches(commit), "commit=$commit is not a full SHA")
        assertEquals("contracts/api/v2/fixtures", ApiV2Fixtures.source["path"])
    }

    @Test
    fun everyIndexedFixtureDecodesToItsModel() {
        val index = ApiV2Fixtures.index.fixtures
        assertTrue(index.size >= 14, "expected the vendored subset, got ${index.size}")
        index.forEach { entry ->
            val body = ApiV2Fixtures.bodyObject(entry.name)
            if (entry.responseMediaType == "application/problem+json") {
                val problem = ApiV2Fixtures.decode<Problem>(body)
                assertEquals(entry.expectedStatus, problem.status, entry.name)
                assertTrue(problem.code.isNotBlank(), entry.name)
            } else {
                assertEquals(200, entry.expectedStatus, entry.name)
                when (entry.operationId) {
                    "getSetupStatus" -> ApiV2Fixtures.decode<SetupStatus>(body)
                    "getCurrentUser" -> ApiV2Fixtures.decode<Account>(body)
                    "listProgress" -> ApiV2Fixtures.decode<ProgressCollection>(body)
                    "updateProfile" -> ApiV2Fixtures.decode<ProfileV2>(body)
                    "getSystemInfo" -> ApiV2Fixtures.decode<SystemInfo>(body)
                    else -> error("unhandled success fixture ${entry.name} (${entry.operationId})")
                }
            }
        }
    }

    // --- getSetupStatus ---

    @Test
    fun setupStatusFixture() {
        val status = ApiV2Fixtures.decode<SetupStatus>(ApiV2Fixtures.bodyObject("get_setup_status_ok").plusUnknown())
        assertFalse(status.needsSetup)
    }

    // --- getCurrentUser ---

    @Test
    fun accountFixtureConsumedFields() {
        val account = ApiV2Fixtures.decode<Account>(ApiV2Fixtures.bodyObject("get_current_user_ok"))
        assertEquals("1", account.id)
        assertEquals("laura", account.username)
        assertEquals("laura@example.test", account.email)
        assertEquals(AccountRole.Known.USER, account.role.known)
        assertFalse(account.role.isAdmin)
        assertEquals(listOf("marker_edit"), account.permissions)
        assertTrue(account.downloadAllowed)
        assertNull(account.impersonation, "impersonation is absent outside an impersonation session")
    }

    @Test
    fun accountImpersonationExplicitNullAndPresent() {
        val base = ApiV2Fixtures.bodyObject("get_current_user_ok")
        assertNull(ApiV2Fixtures.decode<Account>(with(base, "impersonation" to JsonNull)).impersonation)
        val active = ApiV2Fixtures.json.parseToJsonElement(
            """{"active":true,"impersonator_user_id":"42","impersonator_username":"root"}""",
        )
        val account = ApiV2Fixtures.decode<Account>(with(base, "impersonation" to active))
        assertEquals("42", account.impersonation?.impersonatorUserId)
        assertEquals("root", account.impersonation?.impersonatorUsername)
        assertTrue(account.impersonation?.active == true)
    }

    @Test
    fun accountUnknownFieldAndUnknownRoleAreObservable() {
        val body = with(ApiV2Fixtures.bodyObject("get_current_user_ok"), "role" to JsonPrimitive("auditor")).plusUnknown()
        val account = ApiV2Fixtures.decode<Account>(body)
        assertEquals("auditor", account.role.wire)
        assertNull(account.role.known, "an unknown role must not collapse to a known default")
        assertFalse(account.role.isAdmin)
    }

    @Test
    fun accountDefaults() {
        val account = ApiV2Fixtures.json.decodeFromString(
            Account.serializer(),
            """{"id":"7","username":"u","email":"e","role":"admin"}""",
        )
        assertEquals(emptyList(), account.permissions)
        assertFalse(account.downloadAllowed)
        assertNull(account.impersonation)
        assertTrue(account.role.isAdmin)
    }

    // --- listProgress ---

    @Test
    fun progressFixtureConsumedFields() {
        val page = ApiV2Fixtures.decode<ProgressCollection>(ApiV2Fixtures.bodyObject("list_progress_ok").plusUnknown())
        val entry = page.items.single()
        assertEquals("movie-8f2c1a", entry.mediaItemId)
        assertEquals(1325.5, entry.positionSeconds)
        assertEquals(5400.0, entry.durationSeconds)
        assertFalse(entry.completed)
        assertEquals("2026-01-02T03:04:05.000Z", entry.updatedAt)
        assertEquals(1767323045000L, entry.updatedAtEpochMillis)
        assertTrue(page.page.hasMore)
        assertNotNull(page.page.nextCursor)
    }

    @Test
    fun progressPageNextCursorNullWhenNoMore() {
        val last = ApiV2Fixtures.json.decodeFromString(
            ProgressCollection.serializer(),
            """{"items":[],"page":{"has_more":false}}""",
        )
        assertFalse(last.page.hasMore)
        assertNull(last.page.nextCursor)
        val explicit = ApiV2Fixtures.json.decodeFromString(
            ProgressCollection.serializer(),
            """{"items":[],"page":{"has_more":false,"next_cursor":null}}""",
        )
        assertNull(explicit.page.nextCursor)
    }

    @Test
    fun progressDefaults() {
        val empty = ApiV2Fixtures.json.decodeFromString(ProgressCollection.serializer(), "{}")
        assertEquals(emptyList(), empty.items)
        assertFalse(empty.page.hasMore)
        assertNull(empty.page.nextCursor)
        val entry = ApiV2Fixtures.json.decodeFromString(
            ProgressEntryV2.serializer(),
            """{"media_item_id":"m","position_seconds":1,"duration_seconds":2,"updated_at":"nonsense"}""",
        )
        assertFalse(entry.completed)
        assertNull(entry.updatedAtEpochMillis, "an unparseable instant is observable as null, not a crash")
    }

    // --- updateProfile ---

    @Test
    fun profileFixtureConsumedFields() {
        val profile = ApiV2Fixtures.decode<ProfileV2>(ApiV2Fixtures.bodyObject("update_profile_ok"))
        assertEquals("p-owner", profile.id)
        assertEquals("Laura", profile.name)
        assertEquals("preset:fox", profile.avatar)
        assertEquals("/avatars/presets/fox.png", profile.avatarUrl)
        assertEquals(AvatarSource.Known.PRESET, profile.avatarSource.known)
        assertFalse(profile.hasPin)
        assertTrue(profile.isPrimary)
        assertEquals("", profile.maxContentRating, "cleared string members are emitted as empty, never absent")
        assertEquals(QualityPreference.Known.AUTO, profile.qualityPreference.known)
        assertEquals("en", profile.language)
        assertEquals(SubtitleMode.Known.AUTO, profile.subtitleMode.known)
        assertTrue(profile.autoSkipIntro)
        assertEquals(listOf("3"), profile.allowedLibraryIds)
        assertEquals(MaxPlaybackQuality.Known.P1080, profile.maxPlaybackQuality.known)
        assertEquals(1767323045000L, parseApiV2Instant(profile.createdAt))
    }

    @Test
    fun profileUnknownEnumsAreObservable() {
        val body = with(
            ApiV2Fixtures.bodyObject("update_profile_ok"),
            "avatar_source" to JsonPrimitive("hologram"),
            "quality_preference" to JsonPrimitive("balanced"),
            "subtitle_mode" to JsonPrimitive("forced_only"),
            "max_playback_quality" to JsonPrimitive("4320p"),
        ).plusUnknown()
        val profile = ApiV2Fixtures.decode<ProfileV2>(body)
        assertEquals("hologram", profile.avatarSource.wire)
        assertNull(profile.avatarSource.known)
        assertEquals("balanced", profile.qualityPreference.wire)
        assertNull(profile.qualityPreference.known)
        assertEquals("forced_only", profile.subtitleMode.wire)
        assertNull(profile.subtitleMode.known)
        assertEquals("4320p", profile.maxPlaybackQuality.wire)
        assertNull(profile.maxPlaybackQuality.known)
    }

    @Test
    fun profileDefaultsAndExplicitNulls() {
        val minimal = ApiV2Fixtures.json.decodeFromString(
            ProfileV2.serializer(),
            """{"id":"p","name":"n","created_at":"2026-01-02T03:04:05Z","updated_at":"2026-01-02T03:04:05Z"}""",
        )
        assertEquals("", minimal.avatar)
        assertNull(minimal.avatarUrl)
        assertEquals(AvatarSource.Known.NONE, minimal.avatarSource.known)
        assertFalse(minimal.hasPin)
        assertFalse(minimal.isChild)
        assertFalse(minimal.isPrimary)
        assertEquals("", minimal.maxContentRating)
        assertEquals(QualityPreference.Known.AUTO, minimal.qualityPreference.known)
        assertEquals("", minimal.language)
        assertEquals("", minimal.preferredMetadataLanguage)
        assertEquals("", minimal.subtitleLanguage)
        assertEquals(SubtitleMode.Known.AUTO, minimal.subtitleMode.known)
        assertFalse(minimal.autoSkipIntro)
        assertFalse(minimal.autoSkipCredits)
        assertFalse(minimal.autoSkipRecap)
        assertFalse(minimal.autoPlayNextPreview)
        assertFalse(minimal.showForcedSubtitles)
        assertFalse(minimal.libraryRestrictionsEnabled)
        assertEquals(emptyList(), minimal.allowedLibraryIds)
        assertEquals(MaxPlaybackQuality.Known.P1080, minimal.maxPlaybackQuality.known)

        // Explicit null: nullable member → null; non-nullable member with a
        // default → coerced to the default (coerceInputValues), documented here
        // so a contract change to nullable strings is caught.
        val nulled = ApiV2Fixtures.decode<ProfileV2>(
            with(ApiV2Fixtures.bodyObject("update_profile_ok"), "avatar_url" to JsonNull, "language" to JsonNull),
        )
        assertNull(nulled.avatarUrl)
        assertEquals("", nulled.language)
    }

    @Test
    fun profileUpdateEncodesOmittedAbsentAndClearedAsNull() {
        val update = ProfileUpdate(
            name = Patch.Set("Laura"),
            subtitleMode = Patch.Set(SubtitleMode("always")),
            maxContentRating = Patch.Clear,
            allowedLibraryIds = Patch.Set(listOf("3")),
        )
        val encoded = ApiV2Fixtures.json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), update.toJsonObject())
        // Matches the vendored update_profile_ok request body member for member.
        val expected = ApiV2Fixtures.index.fixtures.single { it.name == "update_profile_ok" }.request.body
        assertEquals(ApiV2Fixtures.json.parseToJsonElement(checkNotNull(expected)), ApiV2Fixtures.json.parseToJsonElement(encoded))
        assertTrue("\"max_content_rating\":null" in encoded, encoded)
        assertFalse("\"avatar\"" in encoded, "omitted member must be absent: $encoded")
        assertFalse("\"pin\"" in encoded, encoded)
        assertEquals("{}", ApiV2Fixtures.json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), ProfileUpdate().toJsonObject()))
    }

    @Test
    fun profileUpdateNullNotClearableProblem() {
        val problem = ApiV2Fixtures.decode<Problem>(ApiV2Fixtures.bodyObject("update_profile_null_not_clearable"))
        assertEquals(422, problem.status)
        assertEquals("validation_failed", problem.code)
        val error = problem.errors.single()
        assertEquals("body.is_child", error.location)
        assertEquals("invalid_type", error.code)
    }

    // --- getSystemInfo ---

    @Test
    fun systemInfoFixture() {
        val info = ApiV2Fixtures.decode<SystemInfo>(ApiV2Fixtures.bodyObject("get_system_info_ok").plusUnknown())
        assertEquals(2, info.apiMajor)
        assertTrue(info.contractDigest.isNotBlank())
        assertEquals("/api/v2/openapi.json", info.links.openapi)
        assertEquals("/api/v2/capabilities", info.links.capabilities)
    }

    // --- Problems ---

    @Test
    fun problemFixturesDecodeToTheirType() {
        val expected = mapOf(
            "authentication_required" to (401 to "authentication_required"),
            "validation_failed_body" to (422 to "validation_failed"),
            "not_found" to (404 to "not_found"),
            "rate_limited" to (429 to "rate_limited"),
            "profile_verification_required" to (403 to "profile_verification_required"),
            "not_acceptable" to (406 to "not_acceptable"),
            "list_progress_profile_header_required" to (422 to "validation_failed"),
            "list_progress_offset_rejected" to (422 to "validation_failed"),
        )
        expected.forEach { (name, statusAndCode) ->
            val problem = ApiV2Fixtures.decode<Problem>(ApiV2Fixtures.bodyObject(name).plusUnknown())
            assertEquals(statusAndCode.first, problem.status, name)
            assertEquals(statusAndCode.second, problem.code, name)
            assertTrue(problem.title.isNotBlank(), name)
            assertTrue(ApiV2Fixtures.bodyObject(name)["type"]!!.jsonPrimitive.content.startsWith("https://"), name)
        }
        assertEquals(2, ApiV2Fixtures.decode<Problem>(ApiV2Fixtures.bodyObject("validation_failed_body")).errors.size)
    }

    @Test
    fun problemDefaults() {
        val problem = ApiV2Fixtures.json.decodeFromString(
            Problem.serializer(),
            """{"type":"https://siloserver.org/docs/api/v2/problems/x","title":"X","status":500}""",
        )
        assertEquals("", problem.detail)
        assertNull(problem.instance)
        assertEquals(emptyList(), problem.errors)
        assertEquals("x", problem.code)
    }

    // --- Instants ---

    @Test
    fun instantParsing() {
        assertEquals(1767323045000L, parseApiV2Instant("2026-01-02T03:04:05Z"))
        assertEquals(1767323045123L, parseApiV2Instant("2026-01-02T03:04:05.123456Z"))
        assertEquals(1767323045000L - 3_600_000L, parseApiV2Instant("2026-01-02T03:04:05+01:00"))
        assertEquals(0L, parseApiV2Instant("1970-01-01T00:00:00Z"))
        assertNull(parseApiV2Instant("2026-01-02"))
        assertNull(parseApiV2Instant("2026-01-02T03:04:05"))
        assertNull(parseApiV2Instant(""))
    }
}
