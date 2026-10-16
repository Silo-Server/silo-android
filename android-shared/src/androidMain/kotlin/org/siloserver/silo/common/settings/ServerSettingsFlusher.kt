package org.siloserver.silo.common.settings

import org.siloserver.silo.common.diagnostics.SiloLog
import org.siloserver.silo.model.diagnostics.DiagnosticsLogCategory
import org.siloserver.silo.model.settings.SettingKeys
import org.siloserver.silo.model.settings.SettingScopeIdentity
import org.siloserver.silo.model.settings.SubtitleAppearance
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.SettingsApi
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

/**
 * The server scope a queued write addresses. It travels with the op, so a
 * retry or a late drain sends it to the same scope it was authored for.
 */
sealed interface SettingWriteScope {
    /** `profile_device`: this device only. */
    data object Device : SettingWriteScope

    /**
     * `profile`: every device on the profile.
     *
     * [clearDevice] also deletes this device's own row once the profile write
     * has landed, so the value just saved is the one that resolves here — a
     * stale device row would otherwise keep winning over it.
     *
     * [minRevision] is the settings manifest revision that first accepts the
     * key at profile scope. Below it the server refuses `scope=profile`, so
     * the write goes to this device instead and clears nothing. The choice is
     * made at send time, like the text-opacity gate: while the revision is
     * unknown the write is held and retried rather than guessed.
     */
    data class Profile(
        val clearDevice: Boolean = false,
        val minRevision: Int = 0,
    ) : SettingWriteScope
}

interface ServerSettingsFlusher {
    /**
     * Queue one write at [scope] (this device unless stated). Values arrive as
     * the store's plain strings and are encoded to the contract's JSON type on
     * the wire.
     *
     * Pending work is in memory and retains its captured [authority]. Retries
     * converge the desired value; API v2 does not replay mutation receipts and
     * can advance revision on each attempt. Process death loses this queue.
     *
     * [serverUrl] is the server the value was authored against. This flusher
     * is application-scoped and its requests are relative — they go to
     * whichever server is active when they are sent — so an op that outlives
     * a server switch has to be identified by its origin, not just by
     * (profileId, key). See [ServerSettingsFlusher] implementations for what
     * happens to an op whose origin is no longer active.
     */
    fun enqueue(
        profileId: String,
        key: String,
        value: String,
        serverUrl: String,
        authority: AuthScopeSnapshot? = null,
        scope: SettingWriteScope = SettingWriteScope.Device,
    )

    /**
     * Queue clearing the device-scoped value, so the setting inherits again.
     *
     * A [minRevision] above zero clears it only where the server stores [key]
     * per profile from that manifest revision on: below it the device row is
     * the only value there is, so the clear is dropped. Decided at send time;
     * while the revision is unknown the clear is held and retried.
     */
    fun enqueueDelete(
        profileId: String,
        key: String,
        serverUrl: String,
        authority: AuthScopeSnapshot? = null,
        minRevision: Int = 0,
    )

    /**
     * Cancel any in-flight debounce, drain every pending op, and suspend
     * until each one has been ack'd (or errored). Mirrors the iOS
     * `PlayerSettings.flushPendingDeviceSettings()` semantics —
     * re-entrant calls coalesce, and the second caller waits for the
     * first to finish. Ops that fail transiently stay queued for retry.
     */
    suspend fun flushNow()

    /**
     * The setting keys [profileId] still has unsent (or failed-and-requeued)
     * writes for, read after a [flushNow] has drained what it could.
     *
     * A caller that pulls server state right after pushing needs this: a
     * transiently failed PUT stays queued and `flushNow` returns normally, so
     * the server answers the following read from the value the write has not
     * landed on yet. Applying that answer verbatim would put the old value back
     * over the edit the user just made.
     *
     * Defaults to empty so an implementation that never queues anything need
     * not answer.
     */
    fun pendingKeys(profileId: String): Set<String> = emptySet()
}

private sealed class PendingOp {
    /** The server this op was authored against; it may not still be active. */
    abstract val serverUrl: String
    abstract val authority: AuthScopeSnapshot?

    data class Set(
        val value: String,
        override val serverUrl: String,
        override val authority: AuthScopeSnapshot?,
        val scope: SettingWriteScope = SettingWriteScope.Device,
    ) : PendingOp()

    /**
     * Always this device's row: nothing deletes a profile-scoped value.
     * [minRevision] as in [ServerSettingsFlusher.enqueueDelete].
     *
     * [replacedSet] is the unsent device write this revision-gated clear took
     * the slot of. Below [minRevision] the clear is dropped and the device row
     * stays the value, so that write is sent instead of being lost.
     */
    data class Delete(
        override val serverUrl: String,
        override val authority: AuthScopeSnapshot?,
        val minRevision: Int = 0,
        val replacedSet: Set? = null,
    ) : PendingOp()

    /** Whether sending this op deletes the device row for its key. */
    val clearsDevice: Boolean
        get() = this is Delete || ((this as? Set)?.scope as? SettingWriteScope.Profile)?.clearDevice == true
}

/**
 * One queue slot. A profile write and a device write for the same key are
 * separate intents (saving to the profile while a device row is being
 * cleared, say), so the level is part of the identity; the profile id keeps
 * one profile's writes from ever being sent as another's.
 */
private data class PendingKey(val profileId: String, val key: String, val profileLevel: Boolean)

/**
 * Application-scoped, in-memory desired-state queue. Only the latest pending
 * value per key survives a drain. Transient failures receive bounded retries;
 * a replaced account/profile/PIN drops the old intent before another send.
 * No disk journal, stored receipt replay or cross-client ordering guarantee.
 */
class DefaultServerSettingsFlusher(
    private val settingsApi: SettingsApi,
    private val scope: CoroutineScope,
    private val debounceMs: Long = 750,
    /**
     * The server requests currently address. Null (no active server, e.g.
     * mid-logout) is rejected by the production authority check below.
     */
    private val getServerUrl: suspend () -> String? = { null },
    private val getAuthScope: (suspend () -> AuthScopeSnapshot?)? = null,
    /**
     * The connected server's settings manifest revision, for writes whose
     * accepted shape depends on it. Share the app's instance so the settings
     * UI and the flusher agree on what the server supports.
     */
    private val contractRevision: SettingsContractRevision =
        SettingsContractRevision({ settingsApi.getContractCapabilities() }, getServerUrl, { settingsApi.getContractKeys() }),
) : ServerSettingsFlusher {

    private val lock = Any()
    // Insertion-ordered: the drain sends in the order intents were made.
    private val pending = LinkedHashMap<PendingKey, PendingOp>()
    private var flushJob: Job? = null
    private var retryJob: Job? = null
    private var retryAttempts: Int = 0
    private val flushMutex = Mutex()

    override fun enqueue(
        profileId: String,
        key: String,
        value: String,
        serverUrl: String,
        authority: AuthScopeSnapshot?,
        scope: SettingWriteScope,
    ) {
        val composite = PendingKey(profileId, key, profileLevel = scope is SettingWriteScope.Profile)
        scheduleDebounced(composite) { existing ->
            val next = PendingOp.Set(value, serverUrl, authority, scope).keepingDeviceClearOf(existing)
            // Identical pending intent may coalesce only under the same owner.
            if (existing is PendingOp.Set &&
                existing.value == value && existing.scope == next.scope &&
                existing.serverUrl == serverUrl && existing.authority == authority
            ) {
                existing
            } else {
                next
            }
        }
    }

    override fun enqueueDelete(
        profileId: String,
        key: String,
        serverUrl: String,
        authority: AuthScopeSnapshot?,
        minRevision: Int,
    ) {
        scheduleDebounced(PendingKey(profileId, key, profileLevel = false)) { existing ->
            val replaced = (existing as? PendingOp.Delete)?.replacedSet ?: existing as? PendingOp.Set
            val keep = minRevision > 0 && replaced != null &&
                replaced.serverUrl == serverUrl && replaced.authority == authority
            PendingOp.Delete(serverUrl, authority, minRevision, replacedSet = replaced.takeIf { keep })
        }
    }

    /**
     * A profile save that replaces an unsent one from the same owner still
     * owes that one's device clear. The store drops its local marker when it
     * queues the first save, so a second edit inside the debounce (or during
     * a retry) arrives with clearDevice=false; losing the flag would leave
     * the stale device row outranking the value just saved. A device intent
     * in between already removed the flag from the queued op, so carrying it
     * over never undoes one.
     */
    private fun PendingOp.Set.keepingDeviceClearOf(previous: PendingOp?): PendingOp.Set {
        val profile = scope as? SettingWriteScope.Profile ?: return this
        if (profile.clearDevice || previous == null || !previous.clearsDevice) return this
        if (previous.serverUrl != serverUrl || previous.authority != authority) return this
        return copy(scope = profile.copy(clearDevice = true))
    }

    private fun scheduleDebounced(
        composite: PendingKey,
        op: (PendingOp?) -> PendingOp,
    ) {
        synchronized(lock) {
            val next = op(pending.remove(composite))
            if (composite.profileLevel) {
                // A profile save that clears this device's row supersedes a
                // device write queued before it: that row is going away.
                if (next.clearsDevice) pending.remove(composite.copy(profileLevel = false))
            } else {
                // A device intent made after a profile save keeps the profile
                // value but must not be undone by that save's device clear.
                // A revision-gated clear is not one yet: below its revision it
                // leaves the device row alone, so it supersedes nothing.
                val profileKey = composite.copy(profileLevel = true)
                val gated = next is PendingOp.Delete && next.minRevision > 0
                if (!gated) pending[profileKey]?.let { queued ->
                    val kept = queued.supersededByDeviceIntent()
                    if (kept == null) pending.remove(profileKey) else pending[profileKey] = kept
                }
            }
            // Re-inserted at the end, so the drain sends it after the intents
            // it superseded or followed.
            pending[composite] = next
            // Fresh user activity re-arms the retry budget.
            retryAttempts = 0
            retryJob?.cancel()
            retryJob = null
            flushJob?.cancel()
            flushJob = scope.launch {
                delay(debounceMs)
                // Once admitted, a newer enqueue must not cancel an active send.
                scope.launch { drainAndFlush() }
            }
        }
    }

    override fun pendingKeys(profileId: String): Set<String> = synchronized(lock) {
        pending.keys.filter { it.profileId == profileId }.map { it.key }.toSet()
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
            val retryable = LinkedHashMap<PendingKey, PendingOp>()
            // When each retryable op failed, and when a device-level op was
            // last handled per (profile, key), counted per op rather than per
            // pass: a device intent handled after a profile save failed is
            // newer than that save, even later in the same snapshot.
            val failedAt = HashMap<PendingKey, Int>()
            val deviceHandledAt = HashMap<PendingKey, Int>()
            var sequence = 0
            fun deviceWroteSinceFailure(composite: PendingKey): Boolean =
                composite.profileLevel &&
                    (deviceHandledAt[composite.copy(profileLevel = false)] ?: 0) > (failedAt[composite] ?: 0)
            while (true) {
                val snapshot: Map<PendingKey, PendingOp> = synchronized(lock) {
                    val copy = pending.toMap()
                    pending.clear()
                    copy
                }
                if (snapshot.isEmpty()) break
                snapshot.forEach { (composite, queued) ->
                    sequence += 1
                    if (!composite.profileLevel) deviceHandledAt[composite] = sequence
                    // A newer op settles the slot, so it also takes over the
                    // device clear an older failed save there still owed.
                    val failedEarlier = retryable[composite]
                    val op = if (failedEarlier != null && queued is PendingOp.Set && !deviceWroteSinceFailure(composite)) {
                        queued.keepingDeviceClearOf(failedEarlier)
                    } else {
                        queued
                    }
                    val retry = flushOne(composite.profileId, composite.key, op)
                    if (retry != null) {
                        retryable[composite] = retry
                        failedAt[composite] = sequence
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
                    val newerDeviceIntent = deviceWroteSinceFailure(composite) ||
                        (composite.profileLevel && composite.copy(profileLevel = false) in pending)
                    val newer = pending[composite]
                    if (newer != null) {
                        // A newer op enqueued during the flush wins over the
                        // failed one — it is newer content with its own id —
                        // but still owes the failed one's device clear.
                        if (!newerDeviceIntent && newer is PendingOp.Set) {
                            pending[composite] = newer.keepingDeviceClearOf(op)
                        }
                        continue
                    }
                    val kept = if (newerDeviceIntent) op.supersededByDeviceIntent() else op
                    if (kept != null) pending[composite] = kept
                }
                if (retryAttempts >= MAX_AUTO_RETRIES) {
                    // Out of automatic retries: the ops stay queued and the
                    // next enqueue or flushNow (app foreground, player exit)
                    // tries the retained desired state again.
                    null
                } else {
                    ++retryAttempts
                }
            }
            if (attempt != null) scheduleRetry(attempt)
        }
    }

    /**
     * What is left of an unsent profile-level op once this device has written
     * its own value for the key after it (the toggle turned on mid-flight, or
     * a later edit made once the revision was known): null when nothing is.
     *
     * - A profile save keeps its profile write but must not delete the newer
     *   device value, so it drops its device clear.
     * - A profile save the server can only take on this device (revision
     *   known to be below its minimum) targets the very row the newer write
     *   owns; sending it later would put the older value back, so it goes.
     * - A clear left over from a landed profile save goes too.
     */
    private fun PendingOp.supersededByDeviceIntent(): PendingOp? {
        if (this is PendingOp.Delete) return null
        val set = this as? PendingOp.Set ?: return this
        val profile = set.scope as? SettingWriteScope.Profile ?: return this
        val known = contractRevision.known.value?.takeIf { it.serverUrl == serverUrl }?.manifestRevision
        if (profile.minRevision > 0 && known != null && known < profile.minRevision) return null
        return if (profile.clearDevice) set.copy(scope = profile.copy(clearDevice = false)) else this
    }

    private fun scheduleRetry(attempt: Int) {
        synchronized(lock) {
            retryJob?.cancel()
            retryJob = scope.launch {
                delay(retryDelayMs(attempt))
                // Once admitted, a newer enqueue must not cancel an active send.
                scope.launch { drainAndFlush() }
            }
        }
    }

    /**
     * Sends one op. Returns what must stay queued for retry — under the same
     * captured authority — or null when nothing must. A retry can increment
     * revision again.
     */
    private suspend fun flushOne(profileId: String, key: String, op: PendingOp): PendingOp? {
        if (getAuthScope != null) {
            val owner = op.authority ?: return null
            val now = getAuthScope.invoke()
            if (!owner.isSameIdentityAs(now) || owner.profileId != now?.profileId ||
                owner.profileToken != now?.profileToken || owner.serverUrl != op.serverUrl ||
                owner.profileId != profileId) return null
        }
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
            return null
        }
        if (key !in REMOTE_KEYS) {
            // Local-only keys (granular subtitle.* fields, pre-contract
            // strays) have no server row; the canonical API would refuse
            // them as unknown_setting, so they never leave the device.
            SiloLog.w(CATEGORY, TAG, "dropping $key: not a server-stored key in the generated contract")
            return null
        }
        return try {
            // Null while the manifest is unknown: held and retried. False: the
            // server lacks the key and would refuse it as unknown_setting.
            when (contractRevision.storesKey(op.serverUrl, key)) {
                null -> op.takeIf { failed("flush", key, "settings manifest unknown", retry = true) }
                false -> {
                    SiloLog.w(CATEGORY, TAG, "dropping $key: the server's settings manifest lacks it")
                    null
                }
                true -> when (op) {
                    is PendingOp.Set -> flushSet(profileId, key, op)
                    is PendingOp.Delete -> flushDeleteOrReplacedSet(profileId, key, op)
                }
            }
        } catch (t: Throwable) {
            // Includes cancellation of a superseded flush: the op goes back
            // into the queue and the next trigger replays it, so a torn-down
            // flush never loses a write.
            op.takeIf { failed("flush", key, "threw: $t", retry = true) }
        }
    }

    private suspend fun flushSet(profileId: String, key: String, op: PendingOp.Set): PendingOp? {
        val encoded = encodeSettingWireValue(key, op.value)
        if (encoded == null) {
            SiloLog.w(CATEGORY, TAG, "dropping $key: ${op.value} does not encode as the contract type")
            return null
        }
        val wire = gateForServerRevision(key, encoded, op.serverUrl)
            ?: return op.takeIf { failed("put", key, "settings manifest revision unknown", retry = true) }
        val target = resolveScope(op.scope, op.serverUrl)
            ?: return op.takeIf { failed("put", key, "settings manifest revision unknown", retry = true) }
        return when (
            val result = settingsApi.putValue(
                key = key,
                scope = target.identity,
                value = wire,
                profileId = profileId,
                authority = op.authority,
            )
        ) {
            // The device row goes only after the profile value has landed, so
            // a failure in between never leaves this device on the old
            // profile value. A failed clear retries only the clear: replaying
            // the landed PUT could put this value back over a newer one saved
            // from another device meanwhile.
            is ApiResult.Success -> {
                val clear = PendingOp.Delete(op.serverUrl, op.authority)
                clear.takeIf { target.clearDevice && flushDelete(profileId, key, clear) }
            }
            is ApiResult.Error -> op.takeIf {
                failed(
                    "put", key, "${result.code} ${result.error}: ${result.message}",
                    retry = isTransientHttp(result.code),
                )
            }
            is ApiResult.NetworkError ->
                op.takeIf { failed("put", key, "network error: ${result.exception}", retry = true) }
        }
    }

    private class ResolvedScope(val identity: SettingScopeIdentity, val clearDevice: Boolean)

    /**
     * The scope [scope] means on the server at [serverUrl], or null while that
     * depends on a manifest revision not known yet. A profile write the server
     * cannot take at profile scope yet stays on this device, as before.
     */
    private suspend fun resolveScope(scope: SettingWriteScope, serverUrl: String): ResolvedScope? = when (scope) {
        SettingWriteScope.Device -> ResolvedScope(SettingScopeIdentity.profileDevice(), clearDevice = false)
        is SettingWriteScope.Profile -> {
            val supported = scope.minRevision <= 0 ||
                (contractRevision.revisionFor(serverUrl) ?: return null) >= scope.minRevision
            if (supported) {
                ResolvedScope(SettingScopeIdentity.profile(), scope.clearDevice)
            } else {
                ResolvedScope(SettingScopeIdentity.profileDevice(), clearDevice = false)
            }
        }
    }

    /**
     * [encoded] in the shape the server at [serverUrl] accepts, or null when
     * that depends on a manifest revision not known yet.
     *
     * Decided here, at send time, rather than at enqueue: a value queued
     * before the revision was known has to reach a revision-14 server whole,
     * because a PUT replaces the stored object and a stripped one would reset
     * the textOpacity stored there. While the revision is unknown the write is
     * held — kept queued and retried like a transient failure — because both
     * alternatives lose data: sent whole, a server below 14 rejects it as a
     * contract error, the op is dropped and the next refresh reverts every
     * field in it; sent stripped, it can reset a revision-14 server's value.
     */
    private suspend fun gateForServerRevision(key: String, encoded: JsonElement, serverUrl: String): JsonElement? {
        if (key != SettingKeys.PLAYBACK_SUBTITLE_APPEARANCE) return encoded
        if (encoded !is JsonObject || SubtitleAppearance.TEXT_OPACITY_FIELD !in encoded) return encoded
        val revision = contractRevision.revisionFor(serverUrl) ?: return null
        return SubtitleAppearance.wireObjectForRevision(encoded, revision)
    }

    /**
     * Sends a queued clear, or below its revision the device write it
     * replaced: there the device row is the only value, so that write still
     * has to land or the next refresh puts the old server value back.
     */
    private suspend fun flushDeleteOrReplacedSet(profileId: String, key: String, op: PendingOp.Delete): PendingOp? {
        val replaced = op.replacedSet
        if (replaced != null && op.minRevision > 0) {
            val revision = contractRevision.revisionFor(op.serverUrl)
                ?: return op.takeIf { failed("delete", key, "settings manifest revision unknown", retry = true) }
            if (revision < op.minRevision) return flushSet(profileId, key, replaced)
        }
        return op.takeIf { flushDelete(profileId, key, op) }
    }

    private suspend fun flushDelete(profileId: String, key: String, op: PendingOp.Delete): Boolean {
        if (op.minRevision > 0) {
            val revision = contractRevision.revisionFor(op.serverUrl)
                ?: return failed("delete", key, "settings manifest revision unknown", retry = true)
            if (revision < op.minRevision) {
                // Below it the server keeps this key per device only: the
                // device row is the value, so there is nothing to clear.
                return false
            }
        }
        return when (
            val result = settingsApi.deleteValue(
                key = key,
                scope = SettingScopeIdentity.profileDevice(),
                profileId = profileId,
                authority = op.authority,
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
         * Network, server and throttling failures retain desired state. The
         * transport handles scoped auth refresh; a remaining 401 requires user
         * recovery. Invalid key/value/scope and stale authority are terminal.
         */
        fun isTransientHttp(code: Int): Boolean =
            code >= 500 || code == 408 || code == 429

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
    SettingKeys.PLAYBACK_CELLULAR_MAX_BITRATE_KBPS,
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
