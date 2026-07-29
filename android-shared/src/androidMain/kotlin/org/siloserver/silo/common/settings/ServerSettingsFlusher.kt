package org.siloserver.silo.common.settings

import org.siloserver.silo.common.diagnostics.SiloLog
import org.siloserver.silo.model.diagnostics.DiagnosticsLogCategory
import org.siloserver.silo.model.settings.SettingKeys
import org.siloserver.silo.model.settings.SettingScopeIdentity
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.SettingsApi
import org.siloserver.silo.network.api.newSettingMutationId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

interface ServerSettingsFlusher {
    /**
     * Queue one device-scoped write. Values arrive as the store's plain
     * strings and are encoded to the contract's JSON type on the wire.
     *
     * Each logical write gets one idempotency id ([newSettingMutationId])
     * that is held for every retry of that write — the server replays the
     * recorded receipt for a repeated id, so a retry after a dropped
     * response cannot double-apply. Enqueueing a *different* value for the
     * same key replaces the pending op and mints a fresh id, because
     * reusing an id for different content is a 409 conflict by design.
     *
     * [serverUrl] is the server the value was authored against. This flusher
     * is application-scoped and its requests are relative — they go to
     * whichever server is active when they are sent — so an op that outlives
     * a server switch has to be identified by its origin, not just by
     * (profileId, key). See [ServerSettingsFlusher] implementations for what
     * happens to an op whose origin is no longer active.
     */
    fun enqueue(profileId: String, key: String, value: String, serverUrl: String)

    /** Queue clearing the device-scoped value, so the setting inherits again. */
    fun enqueueDelete(profileId: String, key: String, serverUrl: String)

    /**
     * Cancel any in-flight debounce, drain every pending op, and suspend
     * until each one has been ack'd (or errored). Mirrors the iOS
     * `PlayerSettings.flushPendingDeviceSettings()` semantics —
     * re-entrant calls coalesce, and the second caller waits for the
     * first to finish. Ops that fail transiently stay queued for retry.
     */
    suspend fun flushNow()
}

private sealed class PendingOp {
    /** The server this op was authored against; it may not still be active. */
    abstract val serverUrl: String

    data class Set(
        val value: String,
        val mutationId: String,
        override val serverUrl: String,
    ) : PendingOp()

    data class Delete(override val serverUrl: String) : PendingOp()
}

/**
 * Debounced writer for the canonical settings API
 * (`PUT/DELETE /api/v1/settings/values/{key}?scope=profile_device`).
 *
 * Failure handling is the point, not an afterthought: a write that fails
 * for a transient reason (network, 5xx, 429/408/401) stays queued and is
 * retried with the SAME mutation id, first on a capped backoff schedule and
 * after that on the next enqueue/flushNow trigger — it is never silently
 * dropped, which is how a server hiccup used to turn settings
 * non-persistent. Only a response that proves retrying is pointless (the
 * contract rejected the value or key, or the mutation id was reused for
 * different content) drops the op, and every failure is logged at warning
 * level with the key and status.
 *
 * That retention is bounded by the server the op was authored against.
 * [SettingsApi] requests are relative and this flusher is application-scoped,
 * so they address whichever server is active when they are sent — while a
 * server switch is one `onSelect` away and clears nothing. A retained op
 * whose origin is no longer active is therefore dropped rather than sent:
 * replaying it would write one server's device setting to another (a
 * restored or cloned server can hold the same profile id), and leaving it
 * queued would let a later enqueue revive it against a third. Persistence is
 * worth a lot, but not worth writing a value to a server the user never
 * authored it against.
 */
class DefaultServerSettingsFlusher(
    private val settingsApi: SettingsApi,
    private val scope: CoroutineScope,
    private val debounceMs: Long = 750,
    /**
     * The server requests currently address. Null (no active server, e.g.
     * mid-logout) parks the queue rather than dropping it: there is nothing
     * to compare against yet, and a switch has not been observed.
     */
    private val getServerUrl: suspend () -> String? = { null },
) : ServerSettingsFlusher {

    private val lock = Any()
    private val pending = mutableMapOf<Pair<String, String>, PendingOp>()
    private var flushJob: Job? = null
    private var retryJob: Job? = null
    private var retryAttempts: Int = 0
    private val flushMutex = Mutex()

    override fun enqueue(profileId: String, key: String, value: String, serverUrl: String) {
        scheduleDebounced(profileId, key) { existing ->
            // Re-enqueueing the identical value keeps the pending op (and
            // its mutation id): it is the same logical write, and the
            // server treats a replayed id + content as already done. A match
            // has to agree on the origin too — the same key and value bound
            // for a different server is a different write.
            if (existing is PendingOp.Set &&
                existing.value == value &&
                existing.serverUrl == serverUrl
            ) {
                existing
            } else {
                PendingOp.Set(value, newSettingMutationId(), serverUrl)
            }
        }
    }

    override fun enqueueDelete(profileId: String, key: String, serverUrl: String) {
        scheduleDebounced(profileId, key) { PendingOp.Delete(serverUrl) }
    }

    private fun scheduleDebounced(
        profileId: String,
        key: String,
        op: (PendingOp?) -> PendingOp,
    ) {
        synchronized(lock) {
            val composite = profileId to key
            pending[composite] = op(pending[composite])
            // Fresh user activity re-arms the retry budget.
            retryAttempts = 0
            retryJob?.cancel()
            retryJob = null
            flushJob?.cancel()
            flushJob = scope.launch {
                delay(debounceMs)
                drainAndFlush()
            }
        }
    }

    override suspend fun flushNow() {
        synchronized(lock) {
            flushJob?.cancel()
            flushJob = null
            retryJob?.cancel()
            retryJob = null
            retryAttempts = 0
        }
        drainAndFlush()
    }

    private suspend fun drainAndFlush() {
        flushMutex.withLock {
            // Ops that failed transiently in this drain. Kept out of
            // `pending` until the loop below finishes, or the loop would
            // retry them immediately and spin.
            //
            // Only the LATEST outcome per key may live here. A later pass of
            // the drain loop that settles a newer op for the same key —
            // whether it landed or the contract refused it — must evict the
            // older failed entry, or the re-queue below would resurrect a
            // superseded value and `scheduleRetry` would replay it over the
            // newer one. The post-loop `composite !in pending` guard cannot
            // catch that case: the very pass that sent the newer op already
            // cleared `pending`.
            val retryable = LinkedHashMap<Pair<String, String>, PendingOp>()
            while (true) {
                val snapshot: Map<Pair<String, String>, PendingOp> = synchronized(lock) {
                    val copy = pending.toMap()
                    pending.clear()
                    copy
                }
                if (snapshot.isEmpty()) break
                snapshot.forEach { (composite, op) ->
                    val (profileId, key) = composite
                    if (flushOne(profileId, key, op)) {
                        retryable[composite] = op
                    } else {
                        retryable.remove(composite)
                    }
                }
            }
            if (retryable.isEmpty()) {
                synchronized(lock) { retryAttempts = 0 }
                return
            }
            val attempt = synchronized(lock) {
                for ((composite, op) in retryable) {
                    // A newer op enqueued during the flush wins over the
                    // failed one — it is newer content with its own id.
                    if (composite !in pending) pending[composite] = op
                }
                if (retryAttempts >= MAX_AUTO_RETRIES) {
                    // Out of automatic retries: the ops stay queued and the
                    // next enqueue or flushNow (app foreground, player exit)
                    // tries again with the same mutation ids.
                    null
                } else {
                    ++retryAttempts
                }
            }
            if (attempt != null) scheduleRetry(attempt)
        }
    }

    private fun scheduleRetry(attempt: Int) {
        synchronized(lock) {
            retryJob?.cancel()
            retryJob = scope.launch {
                delay(retryDelayMs(attempt))
                drainAndFlush()
            }
        }
    }

    /**
     * Sends one op. Returns true when it must stay queued for retry —
     * with its mutation id unchanged, so the retry is an idempotent replay
     * rather than a second write.
     */
    private suspend fun flushOne(profileId: String, key: String, op: PendingOp): Boolean {
        val active = runCatching { getServerUrl() }.getOrNull()
        if (active != null && active != op.serverUrl) {
            // The user switched servers while this op was queued. Requests are
            // relative, so sending it now would address the NEW server with a
            // value authored for the old one — and a restored or cloned server
            // can recognize the same profile id, so it would land rather than
            // fail. Dropping it also keeps a stale op from being revived by a
            // later enqueue once the original server is active again.
            SiloLog.w(
                CATEGORY, TAG,
                "dropping $key: queued for ${op.serverUrl}, active server is now $active",
            )
            return false
        }
        if (key !in REMOTE_KEYS) {
            // Local-only keys (granular subtitle.* fields, pre-contract
            // strays) have no server row; the canonical API would refuse
            // them as unknown_setting, so they never leave the device.
            SiloLog.w(CATEGORY, TAG, "dropping $key: not a server-stored key in the generated contract")
            return false
        }
        return try {
            when (op) {
                is PendingOp.Set -> flushSet(profileId, key, op)
                is PendingOp.Delete -> flushDelete(profileId, key)
            }
        } catch (t: Throwable) {
            // Includes cancellation of a superseded flush: the op goes back
            // into the queue and the next trigger replays it, so a torn-down
            // flush never loses a write.
            failed("flush", key, "threw: $t", retry = true)
        }
    }

    private suspend fun flushSet(profileId: String, key: String, op: PendingOp.Set): Boolean {
        val encoded = encodeSettingWireValue(key, op.value)
        if (encoded == null) {
            SiloLog.w(CATEGORY, TAG, "dropping $key: ${op.value} does not encode as the contract type")
            return false
        }
        return when (
            val result = settingsApi.putValue(
                key = key,
                scope = SettingScopeIdentity.profileDevice(),
                value = encoded,
                mutationId = op.mutationId,
                profileId = profileId,
            )
        ) {
            is ApiResult.Success -> false
            is ApiResult.Error -> failed(
                "put", key, "${result.code} ${result.error}: ${result.message}",
                retry = isTransientHttp(result.code),
            )
            is ApiResult.NetworkError ->
                failed("put", key, "network error: ${result.exception}", retry = true)
        }
    }

    private suspend fun flushDelete(profileId: String, key: String): Boolean {
        return when (
            val result = settingsApi.deleteValue(
                key = key,
                scope = SettingScopeIdentity.profileDevice(),
                profileId = profileId,
            )
        ) {
            is ApiResult.Success -> false
            is ApiResult.Error ->
                if (result.code == 404) {
                    // Nothing stored at this scope — the reset is already
                    // true, e.g. an earlier attempt landed before its
                    // response did.
                    false
                } else {
                    failed(
                        "delete", key, "${result.code} ${result.error}: ${result.message}",
                        retry = isTransientHttp(result.code),
                    )
                }
            is ApiResult.NetworkError ->
                failed("delete", key, "network error: ${result.exception}", retry = true)
        }
    }

    private fun failed(verb: String, key: String, detail: String, retry: Boolean): Boolean {
        SiloLog.w(
            CATEGORY, TAG,
            "$verb $key failed ($detail); ${if (retry) "kept queued for retry" else "dropped"}",
        )
        return retry
    }

    private companion object {
        const val TAG = "ServerSettingsFlusher"
        val CATEGORY = DiagnosticsLogCategory.NETWORK

        val REMOTE_KEYS: Set<String> = SettingKeys.REMOTE.toSet()

        /**
         * Retrying can help: the request never arrived, the server fell
         * over, throttled us, timed out, or the session token was mid
         * refresh. Everything else is the contract refusing the write —
         * invalid value, unknown key, scope not allowed, or a mutation id
         * reused for different content (409) — where a retry would fail
         * identically forever.
         */
        fun isTransientHttp(code: Int): Boolean =
            code >= 500 || code == 408 || code == 429 || code == 401

        const val MAX_AUTO_RETRIES = 5
        const val RETRY_BASE_DELAY_MS = 1_000L
        const val RETRY_MAX_DELAY_MS = 60_000L

        fun retryDelayMs(attempt: Int): Long =
            (RETRY_BASE_DELAY_MS shl (attempt - 1).coerceIn(0, 6))
                .coerceAtMost(RETRY_MAX_DELAY_MS)
    }
}

/**
 * The nullable language-tag keys, where the store spells "no preference" as
 * the empty string but the contract spells it as JSON null (the server's
 * language_tag validator rejects `""`).
 *
 * The generated bindings classify boolean/int/double keys only, so these two
 * groups are named here until the generator grows the remaining type sets.
 */
private val NULLABLE_LANGUAGE_KEYS: Set<String> = setOf(
    SettingKeys.CATALOG_METADATA_LANGUAGE,
    SettingKeys.PLAYBACK_AUDIO_LANGUAGE,
    SettingKeys.PLAYBACK_SUBTITLE_LANGUAGE,
)

/**
 * Nullable integer keys, where the contract's null means "no cap" and the
 * local store has to spell that as some in-band value. Zero is chosen because
 * the contract's range (100..200000) cannot hold it, so it can never collide
 * with a real cap — but it is not a value the server would accept, so it is
 * translated to JSON null rather than sent.
 */
private val NULLABLE_INT_KEYS: Set<String> = setOf(
    SettingKeys.PLAYBACK_MAX_BITRATE_KBPS,
)

/** Keys whose store-side string is itself a JSON object document. */
private val SETTING_OBJECT_KEYS: Set<String> = setOf(
    SettingKeys.PLAYBACK_SUBTITLE_APPEARANCE,
)

/**
 * Encodes the store's string spelling of a value as the JSON type the
 * contract declares for [key], classified by the generated
 * [SettingKeys.BOOLEAN_KEYS]/[SettingKeys.INT_KEYS]/[SettingKeys.DOUBLE_KEYS]
 * sets. Returns null when the string cannot be that type — a client bug the
 * server would reject with `invalid_value`, so the caller drops it loudly
 * instead of retrying it forever.
 */
internal fun encodeSettingWireValue(key: String, raw: String): JsonElement? = when {
    key in SettingKeys.BOOLEAN_KEYS -> raw.toBooleanStrictOrNull()?.let(::JsonPrimitive)
    key in NULLABLE_INT_KEYS ->
        raw.toLongOrNull()?.let { if (it <= 0L) JsonNull else JsonPrimitive(it) }
    key in SettingKeys.INT_KEYS -> raw.toLongOrNull()?.let(::JsonPrimitive)
    key in SettingKeys.DOUBLE_KEYS -> raw.toDoubleOrNull()?.let(::JsonPrimitive)
    key in SETTING_OBJECT_KEYS ->
        runCatching { Json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
    key in NULLABLE_LANGUAGE_KEYS && raw.isEmpty() -> JsonNull
    else -> JsonPrimitive(raw)
}
