package org.siloserver.silo.common.settings

import kotlin.test.assertNotNull
import org.siloserver.silo.overlays.OverlayPosition
import org.siloserver.silo.model.settings.EffectiveSettingValue
import org.siloserver.silo.model.settings.EffectiveSettingValuesResponse
import org.siloserver.silo.model.settings.SettingKeys
import org.siloserver.silo.model.settings.SettingScope
import org.siloserver.silo.model.settings.SettingScopeIdentity
import org.siloserver.silo.model.settings.SettingsContractCapabilities
import org.siloserver.silo.model.settings.StoredSettingValue
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.OverlayConfigResponse
import org.siloserver.silo.network.api.SettingsApi
import org.siloserver.silo.overlays.CardOverlayPrefs
import org.siloserver.silo.overlays.OverlayId
import org.siloserver.silo.overlays.OverlayImage
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
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
        assertEquals(
            listOf(SettingKeys.UI_CARD_OVERLAYS, SettingKeys.UI_CARD_OVERLAYS_ENABLED),
            api.effectiveRequests.single(),
        )
    }

    @Test
    fun `profile that turned overlays off hides them although the server default is on`() = runTest {
        val api = RecordingOverlaySettingsApi(profileOverlaysEnabled = false)
        val store = DefaultOverlayPrefsStore(SettingsRepository(api), this)

        store.refresh()

        assertFalse(store.enabled.value)
    }

    @Test
    fun `profile that turned overlays on shows them although the server default is off`() = runTest {
        val api = RecordingOverlaySettingsApi(profileOverlaysEnabled = true)
        api.overlayEnabled = false
        val store = DefaultOverlayPrefsStore(SettingsRepository(api), this)

        store.refresh()

        assertTrue(store.enabled.value)
    }

    @Test
    fun `profile without a choice follows the server default, including on servers without the key`() = runTest {
        for (servesKey in listOf(true, false)) {
            val api = RecordingOverlaySettingsApi(servesOverlaysEnabled = servesKey)
            api.overlayEnabled = false
            val store = DefaultOverlayPrefsStore(SettingsRepository(api), this)

            store.refresh()

            assertFalse(store.enabled.value, "servesKey=$servesKey")
            assertEquals(null, store.lastError.value, "servesKey=$servesKey")
        }
    }

    @Test
    fun `profile choice made elsewhere applies on the next refresh`() = runTest {
        val api = RecordingOverlaySettingsApi()
        val store = DefaultOverlayPrefsStore(SettingsRepository(api), this)
        store.refresh()
        assertTrue(store.enabled.value)

        api.profileOverlaysEnabled = false
        store.refresh()
        assertFalse(store.enabled.value)

        api.profileOverlaysEnabled = null
        store.refresh()
        assertTrue(store.enabled.value)
    }

    @Test
    fun `clear forgets the previous profile's overlay choice`() = runTest {
        val api = RecordingOverlaySettingsApi(profileOverlaysEnabled = false)
        val store = DefaultOverlayPrefsStore(SettingsRepository(api), this)
        store.refresh()
        assertFalse(store.enabled.value)

        store.clear()

        assertTrue(store.enabled.value)
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

    // ---- server-safe writes ----

    @Test
    fun `save below revision 25 writes corners, classic, and no style or images`() = runTest {
        val body = saveStyledDocument(revision(24))

        assertEquals("classic", body["preset"]!!.jsonPrimitive.content)
        assertNull(body["style"])
        val items = body["items"]!!.jsonObject
        val resolution = items["resolution"]!!.jsonObject
        assertEquals("top-left", resolution["position"]!!.jsonPrimitive.content)
        assertNull(resolution["image"])
        assertNull(resolution["valueImages"])
        assertEquals("top-right", items["rating_imdb"]!!.jsonObject["position"]!!.jsonPrimitive.content)
        assertNull(items["resolution_hdr_audio"])
        assertEquals(listOf("rating_imdb", "resolution"), body.order())
        assertEquals(true, items["year"]!!.jsonObject["enabled"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `save at revision 25 writes the document without the revision 26 badge`() = runTest {
        val body = saveStyledDocument(revision(25))

        assertEquals("plate", body["preset"]!!.jsonPrimitive.content)
        assertEquals(1.25, body["style"]!!.jsonObject["size"]!!.jsonPrimitive.content.toDouble())
        val items = body["items"]!!.jsonObject
        val resolution = items["resolution"]!!.jsonObject
        assertEquals("left-edge", resolution["position"]!!.jsonPrimitive.content)
        assertEquals(REF_A, resolution["image"]!!.jsonPrimitive.content)
        assertEquals(REF_B, resolution["valueImages"]!!.jsonObject["4k"]!!.jsonPrimitive.content)
        assertEquals("right-edge", items["rating_imdb"]!!.jsonObject["position"]!!.jsonPrimitive.content)
        assertNull(items["resolution_hdr_audio"])
        assertEquals(listOf("rating_imdb", "resolution"), body.order())
    }

    @Test
    fun `save at revision 26 writes everything`() = runTest {
        val body = saveStyledDocument(revision(26))

        assertEquals("plate", body["preset"]!!.jsonPrimitive.content)
        val items = body["items"]!!.jsonObject
        assertEquals("left-edge", items["resolution"]!!.jsonObject["position"]!!.jsonPrimitive.content)
        assertTrue(items["resolution_hdr_audio"]!!.jsonObject["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(listOf("rating_imdb", "resolution_hdr_audio", "resolution"), body.order())
        assertEquals(
            OverlaySchema.serialize(OverlaySchema.parse(styledDocument(withCombinedAudio = true)).withYear()),
            OverlaySchema.serialize(OverlaySchema.parse(body)),
        )
    }

    @Test
    fun `save with an unknown revision is refused rather than downgraded`() = runTest {
        val api = RecordingOverlaySettingsApi(
            storedValue = Json.parseToJsonElement(styledDocument(withCombinedAudio = true)),
        )
        api.capabilities = ApiResult.NetworkError(java.io.IOException("offline"))
        val store = DefaultOverlayPrefsStore(SettingsRepository(api), this)
        store.refresh()
        val stored = store.prefs.value

        store.setPrefs(stored.withYear())
        advanceUntilIdle()

        // A legacy-shaped PUT would erase the rails, style, images and preset.
        assertTrue(api.puts.isEmpty())
        assertEquals(stored, store.prefs.value)
        assertNotNull(store.lastError.value)
    }

    @Test
    fun `save below revision 25 confirms and shows what the server stored`() = runTest {
        val api = RecordingOverlaySettingsApi(
            storedValue = Json.parseToJsonElement(styledDocument(withCombinedAudio = false)),
        )
        api.capabilities = revision(24)
        val store = DefaultOverlayPrefsStore(SettingsRepository(api), this)
        store.refresh()

        store.setPrefs(store.prefs.value.withYear())
        advanceUntilIdle()

        val shown = store.prefs.value
        assertEquals(PresetId.Classic, shown.preset)
        assertNull(shown.style)
        assertEquals(OverlayPosition.TopLeft, shown.items.getValue(OverlayId.Resolution).position)
        assertNull(shown.items.getValue(OverlayId.Resolution).image)
        assertEquals(OverlaySchema.parse(api.puts.single().value), shown)
    }

    @Test
    fun `failed capabilities read does not block hydration`() = runTest {
        val api = RecordingOverlaySettingsApi(
            storedValue = Json.parseToJsonElement(styledDocument(withCombinedAudio = true)),
        )
        api.capabilities = ApiResult.Error(500, "internal", "Capabilities failed")
        val store = DefaultOverlayPrefsStore(SettingsRepository(api), this)

        store.hydrateIfNeeded()
        store.hydrateIfNeeded()

        assertEquals(1, api.effectiveRequests.size)
        assertNull(store.lastError.value)
        assertEquals(OverlaySchema.parse(styledDocument(withCombinedAudio = true)), store.prefs.value)
    }

    @Test
    fun `save keeps ids, fields and order this client does not know`() = runTest {
        val stored = """
            {"version":2,"preset":"classic","futureRoot":{"a":1},
             "style":{"size":1.5,"glow":true},
             "order":["advisory_age","resolution","request_status"],
             "items":{
               "advisory_age":{"enabled":true,"position":"top-right","tint":"amber"},
               "request_status":{"enabled":false,"position":"bottom-left"},
               "resolution":{"enabled":true,"position":"top-right","image":"$REF_A","valueImages":{"4k":"$REF_B"},"halo":2}
             }}
        """.trimIndent()
        val api = RecordingOverlaySettingsApi(storedValue = Json.parseToJsonElement(stored))
        api.capabilities = revision(26)
        val store = DefaultOverlayPrefsStore(SettingsRepository(api), this)
        store.refresh()

        store.setPrefs(store.prefs.value.withYear())
        advanceUntilIdle()

        val body = api.puts.single().value.jsonObject
        assertEquals(JsonObject(mapOf("a" to JsonPrimitive(1))), body["futureRoot"])
        assertEquals(true, body["style"]!!.jsonObject["glow"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(listOf("advisory_age", "resolution", "request_status"), body.order())
        val items = body["items"]!!.jsonObject
        val advisory = items["advisory_age"]!!.jsonObject
        assertEquals("right-edge", advisory["position"]!!.jsonPrimitive.content)
        assertEquals("amber", advisory["tint"]!!.jsonPrimitive.content)
        assertEquals("bottom-left", items["request_status"]!!.jsonObject["position"]!!.jsonPrimitive.content)
        val resolution = items["resolution"]!!.jsonObject
        assertEquals("right-edge", resolution["position"]!!.jsonPrimitive.content)
        assertEquals(REF_A, resolution["image"]!!.jsonPrimitive.content)
        assertEquals(REF_B, resolution["valueImages"]!!.jsonObject["4k"]!!.jsonPrimitive.content)
        assertEquals(2, resolution["halo"]!!.jsonPrimitive.content.toInt())
        assertTrue(items["year"]!!.jsonObject["enabled"]!!.jsonPrimitive.content.toBoolean())
    }

    // ---- image library ----

    @Test
    fun `card prefs drop unlisted image refs while prefs and the save keep them`() = runTest {
        val stored = """
            {"version":2,"preset":"classic","order":[],
             "items":{"resolution":{"enabled":true,"position":"top-left","image":"$REF_B","valueImages":{"4k":"$REF_A","8k":"$REF_B"}}}}
        """.trimIndent()
        val api = RecordingOverlaySettingsApi(storedValue = Json.parseToJsonElement(stored))
        api.capabilities = revision(26)
        api.images = listOf(image(REF_A))
        val store = DefaultOverlayPrefsStore(SettingsRepository(api), this)
        store.refresh()

        assertEquals(listOf(image(REF_A)), store.images.value)
        val card = store.cardPrefs.value.items.getValue(OverlayId.Resolution)
        assertNull(card.image)
        assertEquals(mapOf("4k" to REF_A), card.valueImages)
        val editable = store.prefs.value.items.getValue(OverlayId.Resolution)
        assertEquals(REF_B, editable.image)
        assertEquals(mapOf("4k" to REF_A, "8k" to REF_B), editable.valueImages)

        store.setPrefs(store.prefs.value.withYear())
        advanceUntilIdle()
        val written = api.puts.single().value.jsonObject["items"]!!.jsonObject["resolution"]!!.jsonObject
        assertEquals(REF_B, written["image"]!!.jsonPrimitive.content)
        assertEquals(REF_B, written["valueImages"]!!.jsonObject["8k"]!!.jsonPrimitive.content)
        assertNull(store.cardPrefs.value.items.getValue(OverlayId.Resolution).image)
        assertTrue(store.cardPrefs.value.items.getValue(OverlayId.Year).enabled)

        // A failed config read keeps the library it had.
        api.configFailure = ApiResult.NetworkError(java.io.IOException("offline"))
        store.refresh()
        assertEquals(listOf(image(REF_A)), store.images.value)
        assertEquals(mapOf("4k" to REF_A), store.cardPrefs.value.items.getValue(OverlayId.Resolution).valueImages)

        // A library that lists the image again draws it.
        api.configFailure = null
        api.images = listOf(image(REF_A), image(REF_B))
        store.refresh()
        assertEquals(REF_B, store.cardPrefs.value.items.getValue(OverlayId.Resolution).image)
        assertTrue(store.cardPrefs.value === store.prefs.value)

        store.clear()
        assertEquals(emptyList(), store.images.value)
        assertEquals(OverlaySchema.buildDefaults(), store.cardPrefs.value)
    }

    @Test
    fun `admin defaults string still parses`() = runTest {
        val api = RecordingOverlaySettingsApi(adminDefaults = """{"version":2,"preset":"band","order":[],"items":{}}""")
        val store = DefaultOverlayPrefsStore(SettingsRepository(api), this)

        store.refresh()

        assertEquals(PresetId.Band, store.prefs.value.preset)
        assertFalse(store.hasUserOverride)
    }

    /** Hydrate [styledDocument], turn on `year`, save, and return the PUT body. */
    private suspend fun kotlinx.coroutines.test.TestScope.saveStyledDocument(
        capabilities: ApiResult<SettingsContractCapabilities>,
        withCombinedAudio: Boolean = true,
    ): JsonObject {
        val api = RecordingOverlaySettingsApi(
            storedValue = Json.parseToJsonElement(styledDocument(withCombinedAudio)),
        )
        api.capabilities = capabilities
        val store = DefaultOverlayPrefsStore(SettingsRepository(api), this)
        store.refresh()
        store.setPrefs(store.prefs.value.withYear())
        advanceUntilIdle()
        return api.puts.single().value.jsonObject
    }

    private fun styledDocument(withCombinedAudio: Boolean): String {
        val combined = if (withCombinedAudio) {
            """"resolution_hdr_audio":{"enabled":true,"position":"bottom-left"},"""
        } else {
            ""
        }
        val order = if (withCombinedAudio) {
            """["rating_imdb","resolution_hdr_audio","resolution"]"""
        } else {
            """["rating_imdb","resolution"]"""
        }
        return """
            {"version":2,"preset":"plate","style":{"size":1.25},"order":$order,
             "items":{$combined
               "resolution":{"enabled":true,"position":"left-edge","image":"$REF_A","valueImages":{"4k":"$REF_B"}},
               "rating_imdb":{"enabled":true,"position":"right-edge"}}}
        """.trimIndent()
    }

    private fun CardOverlayPrefs.withYear(): CardOverlayPrefs =
        copy(items = items + (OverlayId.Year to items.getValue(OverlayId.Year).copy(enabled = true)))

    private fun JsonObject.order(): List<String> = getValue("order").jsonArray.map { it.jsonPrimitive.content }

    private fun revision(manifestRevision: Int): ApiResult<SettingsContractCapabilities> =
        ApiResult.Success(SettingsContractCapabilities(apiVersion = 1, manifestRevision = manifestRevision))

    private fun image(ref: String) =
        OverlayImage(ref = ref, name = ref.take(4), url = "https://silo.test/api/v2/overlay-images/$ref", width = 300, height = 120)

    private fun prefs(preset: PresetId): CardOverlayPrefs =
        OverlaySchema.buildDefaults().copy(preset = preset)

    private companion object {
        val REF_A = "a".repeat(64) + ".webp"
        val REF_B = "b".repeat(64) + ".webp"
    }
}

private class RecordingOverlaySettingsApi(
    var storedValue: JsonElement? = null,
    var adminDefaults: String? = null,
    var profileOverlaysEnabled: Boolean? = null,
    // A server below the revision that introduced ui.card_overlays_enabled
    // omits the key from the response.
    val servesOverlaysEnabled: Boolean = true,
) : SettingsApi(org.siloserver.silo.network.apiv2.SettingsV2Api(HttpClient(), org.siloserver.silo.network.TokenManagerImpl(), org.siloserver.silo.network.apiv2.ApiV2Gate.Unrestricted)) {

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
    var overlayEnabled = true
    var putFailure: ApiResult<StoredSettingValue>? = null
    var deleteFailure: ApiResult<Unit>? = null
    var capabilities: ApiResult<SettingsContractCapabilities> =
        ApiResult.Success(SettingsContractCapabilities(apiVersion = 1, manifestRevision = SettingKeys.REVISION))
    var images: List<OverlayImage> = emptyList()
    var configFailure: ApiResult<OverlayConfigResponse>? = null
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
        configFailure ?: ApiResult.Success(
            OverlayConfigResponse(enabled = overlayEnabled, defaults = adminDefaults, images = images),
        )

    override suspend fun getContractCapabilities(): ApiResult<SettingsContractCapabilities> = capabilities

    override suspend fun getEffectiveValues(
        keys: List<String>,
        libraryIds: List<Int>,
        seriesIds: List<String>,
        authority: org.siloserver.silo.network.AuthScopeSnapshot?,
    ): ApiResult<EffectiveSettingValuesResponse> {
        effectiveRequests += keys
        val value = storedValue
        val enabled = profileOverlaysEnabled
        nextEffectiveReadGate?.also { gate ->
            nextEffectiveReadGate = null
            gate.started.complete(Unit)
            gate.release.await()
        }
        val settings = buildList {
            add(effective(SettingKeys.UI_CARD_OVERLAYS, value))
            if (servesOverlaysEnabled && SettingKeys.UI_CARD_OVERLAYS_ENABLED in keys) {
                add(effective(SettingKeys.UI_CARD_OVERLAYS_ENABLED, enabled?.let(::JsonPrimitive)))
            }
        }
        return ApiResult.Success(
            EffectiveSettingValuesResponse(
                settings = settings,
                revision = SettingKeys.REVISION,
            ),
        )
    }

    private fun effective(key: String, value: JsonElement?) = EffectiveSettingValue(
        key = key,
        value = value ?: JsonNull,
        source = if (value == null) {
            EffectiveSettingValue.SOURCE_DEFAULT
        } else {
            SettingScope.PROFILE.wire
        },
        scope = SettingScope.PROFILE.wire.takeIf { value != null },
    )

    override suspend fun putValue(
        key: String,
        scope: SettingScopeIdentity,
        value: JsonElement,
        profileId: String?,
        authority: org.siloserver.silo.network.AuthScopeSnapshot?,
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
        authority: org.siloserver.silo.network.AuthScopeSnapshot?,
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

}
