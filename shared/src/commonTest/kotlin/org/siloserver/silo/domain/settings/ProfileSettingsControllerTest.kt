package org.siloserver.silo.domain.settings

import org.siloserver.silo.model.settings.EffectiveSettingValue
import org.siloserver.silo.model.settings.EffectiveSettingValuesResponse
import org.siloserver.silo.model.settings.SettingScope
import org.siloserver.silo.model.settings.SettingScopeIdentity
import org.siloserver.silo.model.settings.SettingKeys
import org.siloserver.silo.model.settings.SettingsContractCapabilities
import org.siloserver.silo.model.settings.StoredSettingValue
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.SettingsApi
import org.siloserver.silo.network.api.SettingsCapabilitiesResult
import org.siloserver.silo.repository.SettingsRepository
import io.ktor.client.HttpClient
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProfileSettingsControllerTest {

    private sealed class Call {
        data class Put(val key: String, val scope: SettingScope, val value: JsonElement) : Call()
        data class Delete(val key: String, val scope: SettingScope) : Call()
    }

    private class FakeSettingsApi(
        val capabilities: SettingsCapabilitiesResult =
            SettingsCapabilitiesResult.Available(SettingsContractCapabilities(revision = 1)),
        val effective: ApiResult<EffectiveSettingValuesResponse> =
            ApiResult.Success(EffectiveSettingValuesResponse()),
        val putResult: (String) -> ApiResult<StoredSettingValue> = {
            ApiResult.Success(StoredSettingValue(key = it, scope = "profile"))
        },
        val deleteResult: ApiResult<Unit> = ApiResult.Success(Unit),
    ) : SettingsApi(HttpClient()) {

        val calls = mutableListOf<Call>()
        val mutationIds = mutableListOf<String>()

        override suspend fun getContractCapabilities(): SettingsCapabilitiesResult = capabilities

        override suspend fun getEffectiveValues(
            keys: List<String>,
            libraryIds: List<Int>,
            seriesIds: List<String>,
        ): ApiResult<EffectiveSettingValuesResponse> = effective

        override suspend fun putValue(
            key: String,
            scope: SettingScopeIdentity,
            value: JsonElement,
            mutationId: String,
            profileId: String?,
        ): ApiResult<StoredSettingValue> {
            calls += Call.Put(key, scope.scope, value)
            mutationIds += mutationId
            return putResult(key)
        }

        override suspend fun deleteValue(
            key: String,
            scope: SettingScopeIdentity,
            profileId: String?,
        ): ApiResult<Unit> {
            calls += Call.Delete(key, scope.scope)
            return deleteResult
        }
    }

    private fun controllerFor(api: SettingsApi) =
        ProfileSettingsController(SettingsRepository(api))

    @Test
    fun `writes address scope profile with a mutation id`() = runTest {
        val api = FakeSettingsApi()
        controllerFor(api).setSubtitleMode("always")

        assertEquals<List<Call>>(
            listOf(
                Call.Put(
                    SettingKeys.PLAYBACK_SUBTITLE_MODE,
                    SettingScope.PROFILE,
                    JsonPrimitive("always"),
                ),
            ),
            api.calls,
        )
        assertTrue(api.mutationIds.single().isNotBlank(), "a write must carry an idempotency id")
    }

    @Test
    fun `an empty language clears the row rather than writing an empty string`() = runTest {
        // The server's language_tag validator rejects "", and the contract
        // spells "no preference" as the absence of a row.
        val api = FakeSettingsApi()
        val controller = controllerFor(api)
        controller.setSubtitleLanguage("")
        controller.setMetadataLanguage("  ")

        assertEquals<List<Call>>(
            listOf(
                Call.Delete(SettingKeys.PLAYBACK_SUBTITLE_LANGUAGE, SettingScope.PROFILE),
                Call.Delete(SettingKeys.CATALOG_METADATA_LANGUAGE, SettingScope.PROFILE),
            ),
            api.calls,
        )
    }

    @Test
    fun `a non-empty language is written as the tag`() = runTest {
        val api = FakeSettingsApi()
        controllerFor(api).setSubtitleLanguage(" nl ")

        assertEquals<List<Call>>(
            listOf(
                Call.Put(
                    SettingKeys.PLAYBACK_SUBTITLE_LANGUAGE,
                    SettingScope.PROFILE,
                    JsonPrimitive("nl"),
                ),
            ),
            api.calls,
        )
    }

    @Test
    fun `clearing an absent value succeeds`() = runTest {
        // 404 means nothing was stored there, which is the state the caller
        // asked for — reporting it as an error would roll the UI back from a
        // change that did take effect.
        val api = FakeSettingsApi(
            deleteResult = ApiResult.Error(404, "not_found", "No value is set at this scope"),
        )
        assertTrue(controllerFor(api).setSubtitleLanguage("").succeeded)
    }

    @Test
    fun `a failed write is reported so the caller can roll back`() = runTest {
        val api = FakeSettingsApi(
            putResult = { ApiResult.Error(400, "invalid_value", "nope") },
        )
        assertFalse(controllerFor(api).setShowForcedSubtitles(false).succeeded)
    }

    @Test
    fun `a successful write returns what the server actually resolves`() = runTest {
        // A stored value is not necessarily the effective one: policy can
        // narrow it, and a profile_device row outranks the profile row these
        // setters write. Without the re-resolve the screen would keep showing
        // the authored value while playback used the winning one.
        val api = FakeSettingsApi(
            effective = ApiResult.Success(
                EffectiveSettingValuesResponse(
                    settings = listOf(
                        EffectiveSettingValue(
                            key = SettingKeys.PLAYBACK_SUBTITLE_MODE,
                            value = JsonPrimitive("always"),
                            source = "profile_device",
                            scope = "profile_device",
                        ),
                    ),
                ),
            ),
        )

        val result = controllerFor(api).setSubtitleMode("off")

        assertTrue(result.succeeded, "the write itself landed")
        assertEquals(
            "always",
            result.snapshot?.subtitleMode,
            "the caller must be handed the winning value, not the one it authored",
        )
    }

    @Test
    fun `a write whose re-resolve fails still reports success`() = runTest {
        // The write landed; only the follow-up read did not. Reporting failure
        // would roll the UI back from a change that did take effect.
        val api = FakeSettingsApi(
            effective = ApiResult.Error(500, "internal_error", "boom"),
        )

        val result = controllerFor(api).setSubtitleMode("off")

        assertTrue(result.succeeded)
        assertEquals(null, result.snapshot, "no snapshot means: keep the optimistic value")
    }

    @Test
    fun `an old server reports upgrade required and no snapshot`() = runTest {
        val api = FakeSettingsApi(capabilities = SettingsCapabilitiesResult.ServerUpgradeRequired)
        val result = controllerFor(api).load()

        assertEquals(ProfileSettingsController.Availability.SERVER_UPGRADE_REQUIRED, result.availability)
        assertEquals(null, result.snapshot, "values must not be invented for a server that has none")
    }

    @Test
    fun `load resolves the profile keys from the effective response`() = runTest {
        val api = FakeSettingsApi(
            effective = ApiResult.Success(
                EffectiveSettingValuesResponse(
                    settings = listOf(
                        EffectiveSettingValue(
                            key = SettingKeys.PLAYBACK_SUBTITLE_LANGUAGE,
                            value = JsonPrimitive("nl"),
                            source = "explicit",
                            scope = "profile",
                            suggestedValues = listOf("en", "nl", "pt-BR"),
                        ),
                        EffectiveSettingValue(
                            key = SettingKeys.PLAYBACK_SUBTITLE_MODE,
                            value = JsonPrimitive("always"),
                        ),
                        EffectiveSettingValue(
                            key = SettingKeys.PLAYBACK_SHOW_FORCED_SUBTITLES,
                            value = JsonPrimitive(false),
                        ),
                        // A null language tag is "no preference", which the
                        // UI spells as the empty string.
                        EffectiveSettingValue(
                            key = SettingKeys.CATALOG_METADATA_LANGUAGE,
                            value = JsonNull,
                        ),
                    ),
                ),
            ),
        )

        val result = controllerFor(api).load()

        assertEquals(ProfileSettingsController.Availability.AVAILABLE, result.availability)
        val snapshot = result.snapshot!!
        assertEquals("nl", snapshot.subtitleLanguage)
        assertEquals("always", snapshot.subtitleMode)
        assertEquals(false, snapshot.showForcedSubtitles)
        assertEquals("", snapshot.metadataLanguage)
        assertEquals(listOf("en", "nl", "pt-BR"), snapshot.subtitleLanguageSuggestions)
    }

    @Test
    fun `absent keys fall back to the contract defaults`() = runTest {
        val snapshot = controllerFor(FakeSettingsApi()).load().snapshot!!

        assertEquals("", snapshot.subtitleLanguage)
        // The legacy empty string means unset, not a fourth mode.
        assertEquals("auto", snapshot.subtitleMode)
        // show_forced_subtitles defaults true server-side; defaulting false
        // would silently turn forced subtitles off for untouched profiles.
        assertEquals(true, snapshot.showForcedSubtitles)
    }

    @Test
    fun `a failed resolve reports unavailable rather than empty values`() = runTest {
        val api = FakeSettingsApi(effective = ApiResult.Error(500, "internal_error", "boom"))
        val result = controllerFor(api).load()

        assertEquals(ProfileSettingsController.Availability.UNAVAILABLE, result.availability)
        assertEquals(null, result.snapshot)
    }
}
