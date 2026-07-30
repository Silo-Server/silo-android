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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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
    fun `clear lets the next session queue hydration behind an old refresh`() = runTest {
        val firstUser = prefs(PresetId.Vibrant)
        val secondUser = prefs(PresetId.Pill)
        val api = RecordingOverlaySettingsApi(
            storedValue = Json.parseToJsonElement(OverlaySchema.serialize(firstUser)),
        )
        val store = DefaultOverlayPrefsStore(SettingsRepository(api), this)

        val oldRead = api.pauseNextEffectiveRead()
        val oldRefresh = launch { store.refresh() }
        oldRead.started.await()

        store.clear()
        api.storedValue = Json.parseToJsonElement(OverlaySchema.serialize(secondUser))
        val nextHydration = launch { store.hydrateIfNeeded() }
        runCurrent()
        assertEquals(1, api.effectiveRequests.size)

        oldRead.release.complete(Unit)
        oldRefresh.join()
        nextHydration.join()

        assertEquals(2, api.effectiveRequests.size)
        assertEquals(secondUser, store.prefs.value)
        assertTrue(store.hasUserOverride)
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

    @Test
    fun `stale refresh cannot replace a newer confirmed save`() = runTest {
        val original = prefs(PresetId.Vibrant)
        val saved = prefs(PresetId.Pill)
        val api = RecordingOverlaySettingsApi(
            storedValue = Json.parseToJsonElement(OverlaySchema.serialize(original)),
        )
        val store = DefaultOverlayPrefsStore(SettingsRepository(api), this)
        store.refresh()

        api.overlayEnabled = false
        val staleRead = api.pauseNextEffectiveRead()
        val refreshJob = launch { store.refresh() }
        staleRead.started.await()

        store.setPrefs(saved)
        runCurrent()
        assertEquals(saved, OverlaySchema.parse(api.storedValue.toString()))

        staleRead.release.complete(Unit)
        refreshJob.join()
        assertEquals(saved, store.prefs.value)
        assertFalse(store.enabled.value)

        api.putFailure = ApiResult.Error(400, "invalid_value", "Rejected overlay settings")
        store.setPrefs(prefs(PresetId.Square))
        advanceUntilIdle()

        assertEquals(saved, store.prefs.value)
        assertTrue(store.hasUserOverride)
    }

    @Test
    fun `refresh started during rejected save cannot erase its error`() = runTest {
        val confirmed = prefs(PresetId.Vibrant)
        val rejected = prefs(PresetId.Square)
        val api = RecordingOverlaySettingsApi(
            storedValue = Json.parseToJsonElement(OverlaySchema.serialize(confirmed)),
        )
        val store = DefaultOverlayPrefsStore(SettingsRepository(api), this)
        store.refresh()

        api.putFailure = ApiResult.Error(400, "invalid_value", "Rejected overlay settings")
        val pausedPut = api.pauseNextPut()
        store.setPrefs(rejected)
        pausedPut.started.await()

        val staleRead = api.pauseNextEffectiveRead()
        val refreshJob = launch { store.refresh() }
        staleRead.started.await()

        pausedPut.release.complete(Unit)
        runCurrent()
        assertEquals("Rejected overlay settings", store.lastError.value)

        staleRead.release.complete(Unit)
        refreshJob.join()
        assertEquals(confirmed, store.prefs.value)
        assertEquals("Rejected overlay settings", store.lastError.value)
    }

    @Test
    fun `stale user read still applies new admin fallback after rejected save`() = runTest {
        val oldDefault = prefs(PresetId.Minimal)
        val newDefault = prefs(PresetId.Vibrant)
        val api = RecordingOverlaySettingsApi(
            adminDefaults = OverlaySchema.serialize(oldDefault),
        )
        val store = DefaultOverlayPrefsStore(SettingsRepository(api), this)
        store.refresh()
        assertEquals(oldDefault, store.prefs.value)
        assertFalse(store.hasUserOverride)

        api.adminDefaults = OverlaySchema.serialize(newDefault)
        api.putFailure = ApiResult.Error(400, "invalid_value", "Rejected overlay settings")
        val pausedPut = api.pauseNextPut()
        store.setPrefs(prefs(PresetId.Square))
        pausedPut.started.await()

        val staleRead = api.pauseNextEffectiveRead()
        val refreshJob = launch { store.refresh() }
        staleRead.started.await()

        pausedPut.release.complete(Unit)
        runCurrent()
        assertEquals(oldDefault, store.prefs.value)
        assertEquals("Rejected overlay settings", store.lastError.value)

        staleRead.release.complete(Unit)
        refreshJob.join()
        assertEquals(newDefault, store.prefs.value)
        assertEquals("Rejected overlay settings", store.lastError.value)

        store.setPrefs(prefs(PresetId.Pill))
        advanceUntilIdle()
        assertEquals(newDefault, store.prefs.value)
    }

    @Test
    fun `edit during reset is persisted after delete and remains visible`() = runTest {
        val original = prefs(PresetId.Vibrant)
        val saved = prefs(PresetId.Pill)
        val api = RecordingOverlaySettingsApi(
            storedValue = Json.parseToJsonElement(OverlaySchema.serialize(original)),
        )
        val store = DefaultOverlayPrefsStore(SettingsRepository(api), this)
        store.refresh()

        api.overlayEnabled = false
        val staleRead = api.pauseNextEffectiveRead()
        val refreshJob = launch { store.refresh() }
        staleRead.started.await()

        val pausedDelete = api.pauseNextDelete()
        val resetJob = launch { store.resetToDefaults() }
        pausedDelete.started.await()

        store.setPrefs(saved)
        runCurrent()
        assertEquals(saved, store.prefs.value)
        assertTrue(api.puts.isEmpty())

        staleRead.release.complete(Unit)
        refreshJob.join()
        assertFalse(store.enabled.value)
        assertEquals(saved, store.prefs.value)

        pausedDelete.release.complete(Unit)
        resetJob.join()
        advanceUntilIdle()

        assertEquals(listOf("DELETE", "PUT"), api.mutationEvents)
        assertEquals(saved, OverlaySchema.parse(api.storedValue.toString()))
        assertEquals(saved, store.prefs.value)
        assertTrue(store.hasUserOverride)
    }

    @Test
    fun `refresh started during rejected reset cannot erase its error`() = runTest {
        val confirmed = prefs(PresetId.Vibrant)
        val api = RecordingOverlaySettingsApi(
            storedValue = Json.parseToJsonElement(OverlaySchema.serialize(confirmed)),
        )
        val store = DefaultOverlayPrefsStore(SettingsRepository(api), this)
        store.refresh()

        api.deleteFailure = ApiResult.Error(500, "delete_failed", "Reset failed")
        val pausedDelete = api.pauseNextDelete()
        val resetJob = launch { store.resetToDefaults() }
        pausedDelete.started.await()

        val staleRead = api.pauseNextEffectiveRead()
        val refreshJob = launch { store.refresh() }
        staleRead.started.await()

        pausedDelete.release.complete(Unit)
        resetJob.join()
        assertEquals("Reset failed", store.lastError.value)

        staleRead.release.complete(Unit)
        refreshJob.join()
        assertEquals(confirmed, store.prefs.value)
        assertEquals("Reset failed", store.lastError.value)
    }

    private fun prefs(preset: PresetId): CardOverlayPrefs =
        OverlaySchema.buildDefaults().copy(preset = preset)
}

private class RecordingOverlaySettingsApi(
    var storedValue: JsonElement? = null,
    var adminDefaults: String? = null,
) : SettingsApi(HttpClient()) {

    data class CallGate(
        val started: CompletableDeferred<Unit> = CompletableDeferred(),
        val release: CompletableDeferred<Unit> = CompletableDeferred(),
    )

    data class Put(
        val key: String,
        val scope: SettingScopeIdentity,
        val value: JsonElement,
    )

    val effectiveRequests = mutableListOf<List<String>>()
    val puts = mutableListOf<Put>()
    val mutationEvents = mutableListOf<String>()
    var deleteCount = 0
    var legacyCalls = 0
    var overlayEnabled = true
    var putFailure: ApiResult<StoredSettingValue>? = null
    var deleteFailure: ApiResult<Unit>? = null
    private var nextEffectiveReadGate: CallGate? = null
    private var nextPutGate: CallGate? = null
    private var nextDeleteGate: CallGate? = null

    fun pauseNextEffectiveRead(): CallGate = CallGate().also {
        check(nextEffectiveReadGate == null)
        nextEffectiveReadGate = it
    }

    fun pauseNextPut(): CallGate = CallGate().also {
        check(nextPutGate == null)
        nextPutGate = it
    }

    fun pauseNextDelete(): CallGate = CallGate().also {
        check(nextDeleteGate == null)
        nextDeleteGate = it
    }

    override suspend fun overlayConfig(): ApiResult<OverlayConfigResponse> =
        ApiResult.Success(OverlayConfigResponse(enabled = overlayEnabled, defaults = adminDefaults))

    override suspend fun getEffectiveValues(
        keys: List<String>,
        libraryIds: List<Int>,
        seriesIds: List<String>,
    ): ApiResult<EffectiveSettingValuesResponse> {
        effectiveRequests += keys
        val value = storedValue
        nextEffectiveReadGate?.also { gate ->
            nextEffectiveReadGate = null
            gate.started.complete(Unit)
            gate.release.await()
        }
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
        nextPutGate?.also { gate ->
            nextPutGate = null
            gate.started.complete(Unit)
            gate.release.await()
        }
        putFailure?.let { return it }
        storedValue = value
        mutationEvents += "PUT"
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
        nextDeleteGate?.also { gate ->
            nextDeleteGate = null
            gate.started.complete(Unit)
            gate.release.await()
        }
        deleteFailure?.let { return it }
        storedValue = null
        mutationEvents += "DELETE"
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
