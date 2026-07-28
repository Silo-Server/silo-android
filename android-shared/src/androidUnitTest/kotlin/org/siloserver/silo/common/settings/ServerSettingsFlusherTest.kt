package org.siloserver.silo.common.settings

import org.siloserver.silo.model.settings.PlaybackSettingsKeys
import org.siloserver.silo.model.settings.SettingKeys
import org.siloserver.silo.model.settings.SettingScope
import org.siloserver.silo.model.settings.SettingScopeIdentity
import org.siloserver.silo.model.settings.StoredSettingValue
import org.siloserver.silo.model.settings.SubtitleAppearance
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.SettingsApi
import io.ktor.client.HttpClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ServerSettingsFlusherTest {

    // Real contract keys so the flusher's remote-key gate and type tables
    // classify them the way production traffic is classified.
    private val boolKey = SettingKeys.PLAYBACK_AUTO_SKIP_INTRO
    private val intKey = SettingKeys.PLAYER_AUDIO_SYNC_MS
    private val doubleKey = SettingKeys.PLAYER_PLAYBACK_SPEED
    private val stringKey = SettingKeys.PLAYBACK_PREFERRED_QUALITY
    private val languageKey = SettingKeys.PLAYBACK_AUDIO_LANGUAGE
    private val objectKey = SettingKeys.PLAYBACK_SUBTITLE_APPEARANCE

    @Test
    fun `enqueue debounces multiple writes for same key`() = runTest {
        val api = RecordingSettingsApi()
        val flusher = DefaultServerSettingsFlusher(api, this, debounceMs = 200)

        flusher.enqueue("p1", stringKey, "480p")
        advanceTimeBy(50)
        flusher.enqueue("p1", stringKey, "720p")
        advanceTimeBy(50)
        flusher.enqueue("p1", stringKey, "1080p")

        // Not yet — total elapsed 100, debounce 200.
        assertEquals(0, api.calls.size)

        advanceUntilIdle()

        assertEquals(1, api.calls.size, "expected only the latest write to be sent (coalesced)")
        assertEquals(stringKey, api.calls.first().key)
        assertEquals(JsonPrimitive("1080p"), api.calls.first().value)
    }

    @Test
    fun `enqueue with multiple distinct keys flushes all`() = runTest {
        val api = RecordingSettingsApi()
        val flusher = DefaultServerSettingsFlusher(api, this, debounceMs = 200)

        flusher.enqueue("p1", boolKey, "true")
        flusher.enqueue("p1", intKey, "120")
        flusher.enqueue("p1", stringKey, "720p")

        advanceUntilIdle()

        assertEquals(3, api.calls.size)
        val byKey = api.calls.associate { it.key to it.value }
        assertEquals(JsonPrimitive(true), byKey[boolKey])
        assertEquals(JsonPrimitive(120L), byKey[intKey])
        assertEquals(JsonPrimitive("720p"), byKey[stringKey])
    }

    @Test
    fun `values are encoded as the contract JSON type`() = runTest {
        val api = RecordingSettingsApi()
        val flusher = DefaultServerSettingsFlusher(api, this, debounceMs = 200)

        flusher.enqueue("p1", boolKey, "false")
        flusher.enqueue("p1", intKey, "-250")
        flusher.enqueue("p1", doubleKey, "1.5")
        flusher.enqueue("p1", languageKey, "en-US")
        flusher.enqueue("p1", objectKey, SubtitleAppearance.DEFAULT.toJsonString())

        advanceUntilIdle()

        val byKey = api.calls.associate { it.key to it.value }
        assertEquals(JsonPrimitive(false), byKey[boolKey])
        assertEquals(JsonPrimitive(-250L), byKey[intKey])
        assertEquals(JsonPrimitive(1.5), byKey[doubleKey])
        assertEquals(JsonPrimitive("en-US"), byKey[languageKey])
        assertTrue(byKey[objectKey] is JsonObject, "subtitle appearance must go up as a JSON object")
    }

    @Test
    fun `empty language tag is sent as JSON null`() = runTest {
        // The store spells "no preference" as ""; the contract spells it as
        // null (its language_tag validator rejects the empty string).
        val api = RecordingSettingsApi()
        val flusher = DefaultServerSettingsFlusher(api, this, debounceMs = 200)

        flusher.enqueue("p1", languageKey, "")
        advanceUntilIdle()

        assertEquals(1, api.calls.size)
        assertEquals(JsonNull, api.calls.first().value)
    }

    @Test
    fun `writes address the profile_device scope with the enqueued profile`() = runTest {
        val api = RecordingSettingsApi()
        val flusher = DefaultServerSettingsFlusher(api, this, debounceMs = 200)

        flusher.enqueue("p1", boolKey, "true")
        flusher.enqueueDelete("p2", intKey)
        advanceUntilIdle()

        assertEquals(2, api.calls.size)
        assertTrue(api.calls.all { it.scope == SettingScopeIdentity.profileDevice() })
        assertEquals("p1", api.calls.first { it.key == boolKey }.profileId)
        assertEquals("p2", api.calls.first { it.key == intKey }.profileId)
    }

    @Test
    fun `enqueue preserves profile id when flushing same key for multiple profiles`() = runTest {
        val api = RecordingSettingsApi()
        val flusher = DefaultServerSettingsFlusher(api, this, debounceMs = 200)

        flusher.enqueue("p1", stringKey, "720p")
        flusher.enqueue("p2", stringKey, "1080p")

        advanceUntilIdle()

        assertEquals(2, api.calls.size)
        val byProfile = api.calls.associate { it.profileId to it.value }
        assertEquals(JsonPrimitive("720p"), byProfile["p1"])
        assertEquals(JsonPrimitive("1080p"), byProfile["p2"])
    }

    @Test
    fun `enqueueDelete then enqueue coalesces to latest set`() = runTest {
        val api = RecordingSettingsApi()
        val flusher = DefaultServerSettingsFlusher(api, this, debounceMs = 200)

        flusher.enqueueDelete("p1", stringKey)
        flusher.enqueue("p1", stringKey, "480p")

        advanceUntilIdle()

        assertEquals(1, api.calls.size, "set should win after delete since it was enqueued later")
        assertEquals(RecordingSettingsApi.Call.Kind.PUT, api.calls.first().kind)
        assertEquals(JsonPrimitive("480p"), api.calls.first().value)
    }

    @Test
    fun `enqueue then enqueueDelete coalesces to delete`() = runTest {
        val api = RecordingSettingsApi()
        val flusher = DefaultServerSettingsFlusher(api, this, debounceMs = 200)

        flusher.enqueue("p1", stringKey, "480p")
        flusher.enqueueDelete("p1", stringKey)

        advanceUntilIdle()

        assertEquals(1, api.calls.size, "delete should win after set since it was enqueued later")
        assertEquals(RecordingSettingsApi.Call.Kind.DELETE, api.calls.first().kind)
        assertEquals(stringKey, api.calls.first().key)
    }

    @Test
    fun `flushNow drains pending without waiting for debounce`() = runTest {
        val api = RecordingSettingsApi()
        val flusher = DefaultServerSettingsFlusher(api, this, debounceMs = 5_000)

        flusher.enqueue("p1", boolKey, "true")
        flusher.enqueueDelete("p1", intKey)

        // Don't advance — flushNow should drain immediately.
        flusher.flushNow()

        assertEquals(2, api.calls.size)
        val byKey = api.calls.associateBy { it.key }
        assertEquals(RecordingSettingsApi.Call.Kind.PUT, byKey[boolKey]?.kind)
        assertEquals(JsonPrimitive(true), byKey[boolKey]?.value)
        assertEquals(RecordingSettingsApi.Call.Kind.DELETE, byKey[intKey]?.kind)
    }

    @Test
    fun `flushNow with empty queue is a no-op`() = runTest {
        val api = RecordingSettingsApi()
        val flusher = DefaultServerSettingsFlusher(api, this, debounceMs = 200)

        flusher.flushNow()

        assertEquals(0, api.calls.size)
    }

    @Test
    fun `transient failure keeps the write queued and retries with the same mutation id`() = runTest {
        val api = RecordingSettingsApi()
        api.failNextPuts(1, ApiResult.Error(503, "unavailable", "restarting"))
        val flusher = DefaultServerSettingsFlusher(api, this, debounceMs = 200)

        flusher.enqueue("p1", boolKey, "true")
        advanceUntilIdle()

        assertEquals(2, api.calls.size, "failed write must be retried, not dropped")
        assertEquals(api.calls[0].mutationId, api.calls[1].mutationId,
            "a retry must replay the SAME mutation id so the server can dedupe it")
        assertEquals(JsonPrimitive(true), api.calls[1].value)
    }

    @Test
    fun `network failure keeps the write queued and retries with the same mutation id`() = runTest {
        val api = RecordingSettingsApi()
        api.failNextPuts(2, ApiResult.NetworkError(RuntimeException("offline")))
        val flusher = DefaultServerSettingsFlusher(api, this, debounceMs = 200)

        flusher.enqueue("p1", intKey, "500")
        advanceUntilIdle()

        assertEquals(3, api.calls.size)
        assertTrue(api.calls.all { it.mutationId == api.calls.first().mutationId })
    }

    @Test
    fun `write survives exhausting automatic retries and flushes on the next trigger`() = runTest {
        val api = RecordingSettingsApi()
        api.failNextPuts(Int.MAX_VALUE, ApiResult.Error(500, "internal", "boom"))
        val flusher = DefaultServerSettingsFlusher(api, this, debounceMs = 200)

        flusher.enqueue("p1", boolKey, "true")
        advanceUntilIdle()

        // Initial attempt + capped automatic retries, then parked — the old
        // behavior dropped the write on the first failure.
        val attemptsWhileParked = api.calls.size
        assertTrue(attemptsWhileParked >= 2, "expected automatic retries, got $attemptsWhileParked")

        // The op is still queued: a later explicit flush (app foreground,
        // player exit) replays it — same id — and this time it lands.
        api.failNextPuts(0, ApiResult.Error(500, "internal", "boom"))
        flusher.flushNow()

        assertEquals(attemptsWhileParked + 1, api.calls.size)
        assertTrue(api.calls.all { it.mutationId == api.calls.first().mutationId })
    }

    @Test
    fun `contract rejection drops the write instead of retrying forever`() = runTest {
        val api = RecordingSettingsApi()
        api.failNextPuts(Int.MAX_VALUE, ApiResult.Error(400, "invalid_value", "expected a boolean"))
        val flusher = DefaultServerSettingsFlusher(api, this, debounceMs = 200)

        flusher.enqueue("p1", boolKey, "true")
        advanceUntilIdle()

        assertEquals(1, api.calls.size, "a 4xx contract rejection retries identically forever; drop it")

        // And the queue is actually empty afterwards.
        flusher.flushNow()
        assertEquals(1, api.calls.size)
    }

    @Test
    fun `mutation id conflict drops the write`() = runTest {
        val api = RecordingSettingsApi()
        api.failNextPuts(Int.MAX_VALUE, ApiResult.Error(409, "mutation_id_conflict", "id reused"))
        val flusher = DefaultServerSettingsFlusher(api, this, debounceMs = 200)

        flusher.enqueue("p1", boolKey, "true")
        advanceUntilIdle()

        assertEquals(1, api.calls.size)
    }

    @Test
    fun `delete answered not_found is treated as already done`() = runTest {
        val api = RecordingSettingsApi()
        api.failNextDeletes(Int.MAX_VALUE, ApiResult.Error(404, "not_found", "No value is set at this scope"))
        val flusher = DefaultServerSettingsFlusher(api, this, debounceMs = 200)

        flusher.enqueueDelete("p1", stringKey)
        advanceUntilIdle()

        assertEquals(1, api.calls.size, "nothing stored means the reset is already true; no retry")
    }

    @Test
    fun `transient delete failure keeps the delete queued`() = runTest {
        val api = RecordingSettingsApi()
        api.failNextDeletes(1, ApiResult.Error(502, "bad_gateway", "proxy hiccup"))
        val flusher = DefaultServerSettingsFlusher(api, this, debounceMs = 200)

        flusher.enqueueDelete("p1", stringKey)
        advanceUntilIdle()

        assertEquals(2, api.calls.size)
        assertTrue(api.calls.all { it.kind == RecordingSettingsApi.Call.Kind.DELETE })
    }

    @Test
    fun `re-enqueueing a different value mints a fresh mutation id`() = runTest {
        val api = RecordingSettingsApi()
        val flusher = DefaultServerSettingsFlusher(api, this, debounceMs = 200)

        flusher.enqueue("p1", stringKey, "720p")
        advanceUntilIdle()
        flusher.enqueue("p1", stringKey, "1080p")
        advanceUntilIdle()

        assertEquals(2, api.calls.size)
        assertNotEquals(api.calls[0].mutationId, api.calls[1].mutationId,
            "different content must never reuse a mutation id (409 conflict by design)")
    }

    @Test
    fun `failure does not abort other queued writes or future flushes`() = runTest {
        val api = RecordingSettingsApi()
        api.failNextPuts(1, ApiResult.Error(500, "internal", "boom"))
        val flusher = DefaultServerSettingsFlusher(api, this, debounceMs = 200)

        flusher.enqueue("p1", boolKey, "true")
        advanceUntilIdle()

        flusher.enqueue("p1", stringKey, "720p")
        advanceUntilIdle()

        assertTrue(api.calls.any { it.key == stringKey && it.value == JsonPrimitive("720p") })
        // And the originally failed write also landed in the end.
        assertTrue(api.calls.count { it.key == boolKey } >= 2)
    }

    @Test
    fun `keys the contract does not store never reach the server`() = runTest {
        val api = RecordingSettingsApi()
        val flusher = DefaultServerSettingsFlusher(api, this, debounceMs = 200)

        flusher.enqueue("p1", PlaybackSettingsKeys.SubtitleFontSize, "large")
        flusher.enqueue("p1", "made.up_key", "x")
        advanceUntilIdle()
        flusher.flushNow()

        assertEquals(0, api.calls.size, "non-remote keys would 404 as unknown_setting; drop locally")
    }
}

/**
 * Records every putValue / deleteValue call. Constructed with a no-op
 * HttpClient because we override the only methods the flusher invokes —
 * the underlying client is never touched.
 */
private class RecordingSettingsApi : SettingsApi(HttpClient()) {
    data class Call(
        val kind: Kind,
        val key: String,
        val value: JsonElement?,
        val profileId: String?,
        val mutationId: String?,
        val scope: SettingScopeIdentity,
    ) {
        enum class Kind { PUT, DELETE }
    }

    val calls = mutableListOf<Call>()

    private var putFailuresRemaining = 0
    private var putFailure: ApiResult<StoredSettingValue>? = null
    private var deleteFailuresRemaining = 0
    private var deleteFailure: ApiResult<Unit>? = null

    fun failNextPuts(count: Int, failure: ApiResult<StoredSettingValue>) {
        putFailuresRemaining = count
        putFailure = failure
    }

    fun failNextDeletes(count: Int, failure: ApiResult<Unit>) {
        deleteFailuresRemaining = count
        deleteFailure = failure
    }

    override suspend fun putValue(
        key: String,
        scope: SettingScopeIdentity,
        value: JsonElement,
        mutationId: String,
        profileId: String?,
    ): ApiResult<StoredSettingValue> {
        calls.add(Call(Call.Kind.PUT, key, value, profileId, mutationId, scope))
        if (putFailuresRemaining > 0) {
            putFailuresRemaining--
            return putFailure ?: ApiResult.Error(500, "internal", "boom")
        }
        return ApiResult.Success(
            StoredSettingValue(key = key, scope = SettingScope.PROFILE_DEVICE.wire, value = value),
        )
    }

    override suspend fun deleteValue(
        key: String,
        scope: SettingScopeIdentity,
        profileId: String?,
    ): ApiResult<Unit> {
        calls.add(Call(Call.Kind.DELETE, key, null, profileId, null, scope))
        if (deleteFailuresRemaining > 0) {
            deleteFailuresRemaining--
            return deleteFailure ?: ApiResult.Error(500, "internal", "boom")
        }
        return ApiResult.Success(Unit)
    }
}
