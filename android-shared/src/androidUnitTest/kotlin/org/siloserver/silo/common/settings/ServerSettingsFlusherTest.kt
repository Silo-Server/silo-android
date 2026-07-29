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

    // The server ops are authored against. The flusher's requests are relative
    // and it outlives a server switch, so every op carries its origin.
    private val serverUrl = "https://one.example"
    private val otherServerUrl = "https://two.example"

    @Test
    fun `enqueue debounces multiple writes for same key`() = runTest {
        val api = RecordingSettingsApi()
        val flusher = DefaultServerSettingsFlusher(api, this, debounceMs = 200)

        flusher.enqueue("p1", stringKey, "480p", serverUrl)
        advanceTimeBy(50)
        flusher.enqueue("p1", stringKey, "720p", serverUrl)
        advanceTimeBy(50)
        flusher.enqueue("p1", stringKey, "1080p", serverUrl)

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

        flusher.enqueue("p1", boolKey, "true", serverUrl)
        flusher.enqueue("p1", intKey, "120", serverUrl)
        flusher.enqueue("p1", stringKey, "720p", serverUrl)

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

        flusher.enqueue("p1", boolKey, "false", serverUrl)
        flusher.enqueue("p1", intKey, "-250", serverUrl)
        flusher.enqueue("p1", doubleKey, "1.5", serverUrl)
        flusher.enqueue("p1", languageKey, "en-US", serverUrl)
        flusher.enqueue("p1", objectKey, SubtitleAppearance.DEFAULT.toJsonString(), serverUrl)

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

        flusher.enqueue("p1", languageKey, "", serverUrl)
        advanceUntilIdle()

        assertEquals(1, api.calls.size)
        assertEquals(JsonNull, api.calls.first().value)
    }

    @Test
    fun `writes address the profile_device scope with the enqueued profile`() = runTest {
        val api = RecordingSettingsApi()
        val flusher = DefaultServerSettingsFlusher(api, this, debounceMs = 200)

        flusher.enqueue("p1", boolKey, "true", serverUrl)
        flusher.enqueueDelete("p2", intKey, serverUrl)
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

        flusher.enqueue("p1", stringKey, "720p", serverUrl)
        flusher.enqueue("p2", stringKey, "1080p", serverUrl)

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

        flusher.enqueueDelete("p1", stringKey, serverUrl)
        flusher.enqueue("p1", stringKey, "480p", serverUrl)

        advanceUntilIdle()

        assertEquals(1, api.calls.size, "set should win after delete since it was enqueued later")
        assertEquals(RecordingSettingsApi.Call.Kind.PUT, api.calls.first().kind)
        assertEquals(JsonPrimitive("480p"), api.calls.first().value)
    }

    @Test
    fun `enqueue then enqueueDelete coalesces to delete`() = runTest {
        val api = RecordingSettingsApi()
        val flusher = DefaultServerSettingsFlusher(api, this, debounceMs = 200)

        flusher.enqueue("p1", stringKey, "480p", serverUrl)
        flusher.enqueueDelete("p1", stringKey, serverUrl)

        advanceUntilIdle()

        assertEquals(1, api.calls.size, "delete should win after set since it was enqueued later")
        assertEquals(RecordingSettingsApi.Call.Kind.DELETE, api.calls.first().kind)
        assertEquals(stringKey, api.calls.first().key)
    }

    @Test
    fun `flushNow drains pending without waiting for debounce`() = runTest {
        val api = RecordingSettingsApi()
        val flusher = DefaultServerSettingsFlusher(api, this, debounceMs = 5_000)

        flusher.enqueue("p1", boolKey, "true", serverUrl)
        flusher.enqueueDelete("p1", intKey, serverUrl)

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

        flusher.enqueue("p1", boolKey, "true", serverUrl)
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

        flusher.enqueue("p1", intKey, "500", serverUrl)
        advanceUntilIdle()

        assertEquals(3, api.calls.size)
        assertTrue(api.calls.all { it.mutationId == api.calls.first().mutationId })
    }

    @Test
    fun `write survives exhausting automatic retries and flushes on the next trigger`() = runTest {
        val api = RecordingSettingsApi()
        api.failNextPuts(Int.MAX_VALUE, ApiResult.Error(500, "internal", "boom"))
        val flusher = DefaultServerSettingsFlusher(api, this, debounceMs = 200)

        flusher.enqueue("p1", boolKey, "true", serverUrl)
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

        flusher.enqueue("p1", boolKey, "true", serverUrl)
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

        flusher.enqueue("p1", boolKey, "true", serverUrl)
        advanceUntilIdle()

        assertEquals(1, api.calls.size)
    }

    @Test
    fun `delete answered not_found is treated as already done`() = runTest {
        val api = RecordingSettingsApi()
        api.failNextDeletes(Int.MAX_VALUE, ApiResult.Error(404, "not_found", "No value is set at this scope"))
        val flusher = DefaultServerSettingsFlusher(api, this, debounceMs = 200)

        flusher.enqueueDelete("p1", stringKey, serverUrl)
        advanceUntilIdle()

        assertEquals(1, api.calls.size, "nothing stored means the reset is already true; no retry")
    }

    @Test
    fun `transient delete failure keeps the delete queued`() = runTest {
        val api = RecordingSettingsApi()
        api.failNextDeletes(1, ApiResult.Error(502, "bad_gateway", "proxy hiccup"))
        val flusher = DefaultServerSettingsFlusher(api, this, debounceMs = 200)

        flusher.enqueueDelete("p1", stringKey, serverUrl)
        advanceUntilIdle()

        assertEquals(2, api.calls.size)
        assertTrue(api.calls.all { it.kind == RecordingSettingsApi.Call.Kind.DELETE })
    }

    @Test
    fun `re-enqueueing a different value mints a fresh mutation id`() = runTest {
        val api = RecordingSettingsApi()
        val flusher = DefaultServerSettingsFlusher(api, this, debounceMs = 200)

        flusher.enqueue("p1", stringKey, "720p", serverUrl)
        advanceUntilIdle()
        flusher.enqueue("p1", stringKey, "1080p", serverUrl)
        advanceUntilIdle()

        assertEquals(2, api.calls.size)
        assertNotEquals(api.calls[0].mutationId, api.calls[1].mutationId,
            "different content must never reuse a mutation id (409 conflict by design)")
    }

    @Test
    fun `a retained retry is dropped once its origin server is no longer active`() = runTest {
        // The failure mode: a write fails transiently against server one, stays
        // queued, and the user switches to server two. Requests are relative and
        // this flusher is application-scoped, so replaying the op now would
        // write server one's device setting to server two — which a restored or
        // cloned server recognizing the same profile id would accept.
        val api = RecordingSettingsApi()
        // Fail every attempt, so the op exhausts its automatic retries and is
        // still sitting in the queue when the switch happens.
        api.failNextPuts(Int.MAX_VALUE, ApiResult.Error(500, "internal", "boom"))
        var activeServer = serverUrl
        val flusher = DefaultServerSettingsFlusher(
            api, this, debounceMs = 200, getServerUrl = { activeServer },
        )

        flusher.enqueue("p1", stringKey, "720p", serverUrl)
        advanceUntilIdle()
        val attemptsBeforeSwitch = api.calls.size
        assertTrue(attemptsBeforeSwitch >= 1, "the write must have been attempted at least once")

        activeServer = otherServerUrl
        flusher.flushNow()
        advanceUntilIdle()

        assertEquals(
            attemptsBeforeSwitch,
            api.calls.size,
            "a queued write must never be replayed against a server it was not authored for",
        )

        // And it is gone, not merely deferred: a later flush against the
        // original server must not resurrect it either.
        activeServer = serverUrl
        flusher.flushNow()
        advanceUntilIdle()
        assertEquals(
            attemptsBeforeSwitch,
            api.calls.size,
            "the dropped op must not be revived by a later flush",
        )
    }

    @Test
    fun `a queued write still lands when the active server is unchanged`() = runTest {
        // The other side of the guard: same setup, no switch. The retry has to
        // go through, or the drop rule would quietly break normal persistence.
        val api = RecordingSettingsApi()
        api.failNextPuts(1, ApiResult.Error(500, "internal", "boom"))
        val flusher = DefaultServerSettingsFlusher(
            api, this, debounceMs = 200, getServerUrl = { serverUrl },
        )

        flusher.enqueue("p1", stringKey, "720p", serverUrl)
        advanceUntilIdle()

        assertTrue(
            api.calls.count { it.key == stringKey } >= 2,
            "a transient failure on the still-active server must retry",
        )
    }

    @Test
    fun `failure does not abort other queued writes or future flushes`() = runTest {
        val api = RecordingSettingsApi()
        api.failNextPuts(1, ApiResult.Error(500, "internal", "boom"))
        val flusher = DefaultServerSettingsFlusher(api, this, debounceMs = 200)

        flusher.enqueue("p1", boolKey, "true", serverUrl)
        advanceUntilIdle()

        flusher.enqueue("p1", stringKey, "720p", serverUrl)
        advanceUntilIdle()

        assertTrue(api.calls.any { it.key == stringKey && it.value == JsonPrimitive("720p") })
        // And the originally failed write also landed in the end.
        assertTrue(api.calls.count { it.key == boolKey } >= 2)
    }

    @Test
    fun `a newer value flushed in the same drain is not reverted by the failed older one`() = runTest {
        // The shape that loses a user edit: flushNow() drains on the caller's
        // coroutine (nothing cancels it), so a value the user changes while
        // the first PUT is in flight is drained and sent by a LATER pass of
        // the same drain. If the failed older op stayed queued, the retry
        // would replay it over the newer value the server already accepted.
        val api = RecordingSettingsApi()
        val flusher = DefaultServerSettingsFlusher(api, this, debounceMs = 200)
        api.failNextPuts(1, ApiResult.Error(502, "bad_gateway", "proxy hiccup"))
        api.onPut = { call ->
            // The user edits the same setting while the first PUT is in flight.
            if (call.value == JsonPrimitive("480p")) flusher.enqueue("p1", stringKey, "1080p", serverUrl)
        }

        flusher.enqueue("p1", stringKey, "480p", serverUrl)
        flusher.flushNow()
        advanceUntilIdle()

        assertEquals(
            listOf(JsonPrimitive("480p"), JsonPrimitive("1080p")),
            api.calls.map { it.value },
            "the superseded value must not be replayed after the newer one landed",
        )
    }

    @Test
    fun `a delete that failed is not replayed after a newer set landed`() = runTest {
        // Same defect, worse outcome: a re-queued delete clears a value the
        // user explicitly chose after the reset.
        val api = RecordingSettingsApi()
        val flusher = DefaultServerSettingsFlusher(api, this, debounceMs = 200)
        api.failNextDeletes(1, ApiResult.Error(503, "unavailable", "restarting"))
        api.onDelete = { flusher.enqueue("p1", stringKey, "1080p", serverUrl) }

        flusher.enqueueDelete("p1", stringKey, serverUrl)
        flusher.flushNow()
        advanceUntilIdle()

        assertEquals(
            listOf(RecordingSettingsApi.Call.Kind.DELETE, RecordingSettingsApi.Call.Kind.PUT),
            api.calls.map { it.kind },
            "the failed delete must not be replayed over the value set after it",
        )
        assertEquals(JsonPrimitive("1080p"), api.calls.last().value)
    }

    @Test
    fun `a newer value that also fails is the one retried`() = runTest {
        // Evicting the stale entry must not lose a genuine failure: when the
        // newer op fails too, it is the newer op — and its id — that stays
        // queued.
        val api = RecordingSettingsApi()
        val flusher = DefaultServerSettingsFlusher(api, this, debounceMs = 200)
        api.failNextPuts(2, ApiResult.Error(500, "internal", "boom"))
        api.onPut = { call ->
            if (call.value == JsonPrimitive("480p")) flusher.enqueue("p1", stringKey, "1080p", serverUrl)
        }

        flusher.enqueue("p1", stringKey, "480p", serverUrl)
        flusher.flushNow()
        advanceUntilIdle()

        assertEquals(3, api.calls.size, "the newer failed write must still be retried")
        assertEquals(JsonPrimitive("1080p"), api.calls[2].value)
        assertEquals(
            api.calls[1].mutationId, api.calls[2].mutationId,
            "the retry replays the newer write's own id",
        )
    }

    @Test
    fun `keys the contract does not store never reach the server`() = runTest {
        val api = RecordingSettingsApi()
        val flusher = DefaultServerSettingsFlusher(api, this, debounceMs = 200)

        flusher.enqueue("p1", PlaybackSettingsKeys.SubtitleFontSize, "large", serverUrl)
        flusher.enqueue("p1", "made.up_key", "x", serverUrl)
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

    /**
     * Runs while a call is "in flight", before its result is returned — the
     * hook for simulating the user editing the same setting during a flush.
     */
    var onPut: ((Call) -> Unit)? = null
    var onDelete: ((Call) -> Unit)? = null

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
        val call = Call(Call.Kind.PUT, key, value, profileId, mutationId, scope)
        calls.add(call)
        onPut?.invoke(call)
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
        val call = Call(Call.Kind.DELETE, key, null, profileId, null, scope)
        calls.add(call)
        onDelete?.invoke(call)
        if (deleteFailuresRemaining > 0) {
            deleteFailuresRemaining--
            return deleteFailure ?: ApiResult.Error(500, "internal", "boom")
        }
        return ApiResult.Success(Unit)
    }
}
