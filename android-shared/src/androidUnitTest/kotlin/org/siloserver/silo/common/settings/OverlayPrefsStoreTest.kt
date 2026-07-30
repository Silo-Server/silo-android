package org.siloserver.silo.common.settings

import org.siloserver.silo.model.settings.EffectiveSettingValue
import org.siloserver.silo.model.settings.EffectiveSettingValuesResponse
import org.siloserver.silo.model.settings.SettingEntry
import org.siloserver.silo.model.settings.SettingKeys
import org.siloserver.silo.model.settings.SettingScope
import org.siloserver.silo.model.settings.SettingScopeIdentity
import org.siloserver.silo.model.settings.StoredSettingValue
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.OverlayConfigResponse
import org.siloserver.silo.network.api.SettingsApi
import org.siloserver.silo.overlays.CardOverlayPrefs
import org.siloserver.silo.overlays.OverlaySchema
import org.siloserver.silo.overlays.PresetId
import org.siloserver.silo.repository.SettingsRepository
import io.ktor.client.HttpClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class OverlayPrefsStoreTest {

    @Test
    fun `hydrate reads typed canonical profile value without legacy endpoint`() = runTest {
        val expected = prefs(PresetId.Vibrant)
        val api = RecordingOverlaySettingsApi(
            storedValue = Json.parseToJsonElement(OverlaySchema.serialize(expected)),
        )
        val store = DefaultOverlayPrefsStore(SettingsRepository(api), this)

        store.refresh()

        assertEquals(expected, store.prefs.value)
        assertTrue(store.hasUserOverride)
        assertEquals(listOf(SettingKeys.UI_CARD_OVERLAYS), api.effectiveRequests.single())
        assertEquals(0, api.legacyCalls)
    }

    @Test
    fun `save and reset use canonical profile scope with typed object`() = runTest {
        val initial = prefs(PresetId.Vibrant)
        val adminDefault = prefs(PresetId.Minimal)
        val api = RecordingOverlaySettingsApi(
            storedValue = Json.parseToJsonElement(OverlaySchema.serialize(initial)),
            adminDefaults = OverlaySchema.serialize(adminDefault),
        )
        val store = DefaultOverlayPrefsStore(SettingsRepository(api), this)
        store.refresh()

        val edited = prefs(PresetId.Pill)
        store.setPrefs(edited)
        advanceUntilIdle()

        val put = api.puts.single()
        assertEquals(SettingKeys.UI_CARD_OVERLAYS, put.key)
        assertEquals(SettingScope.PROFILE, put.scope.scope)
        assertIs<JsonObject>(put.value)
        assertEquals(edited, store.prefs.value)
        assertTrue(store.hasUserOverride)

        store.resetToDefaults()

        assertEquals(1, api.deleteCount)
        assertEquals(adminDefault, store.prefs.value)
        assertFalse(store.hasUserOverride)
        assertEquals(0, api.legacyCalls)
    }

    @Test
    fun `failed canonical save restores last confirmed value`() = runTest {
        val confirmed = prefs(PresetId.Vibrant)
        val api = RecordingOverlaySettingsApi(
            storedValue = Json.parseToJsonElement(OverlaySchema.serialize(confirmed)),
        )
        val store = DefaultOverlayPrefsStore(SettingsRepository(api), this)
        store.refresh()
        api.putFailure = ApiResult.Error(400, "invalid_value", "Rejected overlay settings")

        store.setPrefs(prefs(PresetId.Square))
        advanceUntilIdle()

        assertEquals(confirmed, store.prefs.value)
        assertTrue(store.hasUserOverride)
        assertEquals("Rejected overlay settings", store.lastError.value)
        assertEquals(0, api.legacyCalls)
    }

    private fun prefs(preset: PresetId): CardOverlayPrefs =
        OverlaySchema.buildDefaults().copy(preset = preset)
}

private class RecordingOverlaySettingsApi(
    var storedValue: JsonElement? = null,
    private val adminDefaults: String? = null,
) : SettingsApi(HttpClient()) {

    data class Put(
        val key: String,
        val scope: SettingScopeIdentity,
        val value: JsonElement,
    )

    val effectiveRequests = mutableListOf<List<String>>()
    val puts = mutableListOf<Put>()
    var deleteCount = 0
    var legacyCalls = 0
    var putFailure: ApiResult<StoredSettingValue>? = null

    override suspend fun overlayConfig(): ApiResult<OverlayConfigResponse> =
        ApiResult.Success(OverlayConfigResponse(enabled = true, defaults = adminDefaults))

    override suspend fun getEffectiveValues(
        keys: List<String>,
        libraryIds: List<Int>,
        seriesIds: List<String>,
    ): ApiResult<EffectiveSettingValuesResponse> {
        effectiveRequests += keys
        val value = storedValue
        return ApiResult.Success(
            EffectiveSettingValuesResponse(
                settings = listOf(
                    EffectiveSettingValue(
                        key = SettingKeys.UI_CARD_OVERLAYS,
                        value = value ?: JsonNull,
                        source = if (value == null) {
                            EffectiveSettingValue.SOURCE_DEFAULT
                        } else {
                            SettingScope.PROFILE.wire
                        },
                        scope = SettingScope.PROFILE.wire.takeIf { value != null },
                    ),
                ),
                revision = SettingKeys.REVISION,
            ),
        )
    }

    override suspend fun putValue(
        key: String,
        scope: SettingScopeIdentity,
        value: JsonElement,
        mutationId: String,
        profileId: String?,
    ): ApiResult<StoredSettingValue> {
        puts += Put(key, scope, value)
        putFailure?.let { return it }
        storedValue = value
        return ApiResult.Success(
            StoredSettingValue(
                key = key,
                scope = scope.scope.wire,
                value = value,
            ),
        )
    }

    override suspend fun deleteValue(
        key: String,
        scope: SettingScopeIdentity,
        profileId: String?,
    ): ApiResult<Unit> {
        deleteCount += 1
        assertEquals(SettingKeys.UI_CARD_OVERLAYS, key)
        assertEquals(SettingScope.PROFILE, scope.scope)
        storedValue = null
        return ApiResult.Success(Unit)
    }

    override suspend fun getSetting(key: String): ApiResult<SettingEntry> {
        legacyCalls += 1
        error("legacy getSetting must not be called")
    }

    override suspend fun setSetting(key: String, value: String): ApiResult<Unit> {
        legacyCalls += 1
        error("legacy setSetting must not be called")
    }

    override suspend fun deleteSetting(key: String): ApiResult<Unit> {
        legacyCalls += 1
        error("legacy deleteSetting must not be called")
    }
}
