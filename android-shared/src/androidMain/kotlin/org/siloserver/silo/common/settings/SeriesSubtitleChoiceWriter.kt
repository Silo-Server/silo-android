package org.siloserver.silo.common.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull
import org.siloserver.silo.model.catalog.WatchDetail
import org.siloserver.silo.model.playback.PendingSeriesSubtitleChoice
import org.siloserver.silo.model.playback.seriesSubtitleChoiceKey
import org.siloserver.silo.model.playback.withPendingSeriesSubtitleChoice
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.siloserver.silo.common.diagnostics.SiloLog
import org.siloserver.silo.model.diagnostics.DiagnosticsLogCategory
import org.siloserver.silo.model.playback.SeriesSubtitleChoice
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot

/**
 * Remembers manual subtitle choices for a whole series on the server
 * (`/api/v2/subtitle-prefs/{series_id}`), so the choice applies to every
 * episode, after a relaunch and on the profile's other devices — the server
 * folds it into the next watch detail as `effective_subtitle_*`.
 */
interface SeriesSubtitleChoiceWriter {
    /**
     * Remember [choice] for [seriesKey] (see
     * [org.siloserver.silo.model.playback.seriesSubtitleChoiceKey]); null goes
     * back to automatic. The active profile is captured now, so the write can
     * only ever land on the profile that made the choice.
     */
    suspend fun save(seriesKey: String, choice: SeriesSubtitleChoice?)

    /** Send whatever is still queued (a failed write retried), and wait for it. */
    suspend fun flushNow()

    /**
     * The choice the active profile made for [seriesKey] that has not landed
     * yet (queued, in flight, or waiting to retry), or null when there is
     * none. Until it lands the server still answers with the older choice.
     */
    suspend fun pendingChoice(seriesKey: String): PendingSeriesSubtitleChoice? = null

    /** [pendingChoice] for every series of the active profile, as it changes. */
    val pendingChoices: Flow<Map<String, PendingSeriesSubtitleChoice>>
        get() = flowOf(emptyMap())
}

/**
 * [detail] as playback should start it: a queued write gets a short chance to
 * land, and a choice still unsent is laid over the server's effective
 * subtitle fields. Without this, reopening an item while a pick waited to
 * retry started from the server's older choice — and the per-file memory of
 * the new one was filtered out against it.
 */
suspend fun SeriesSubtitleChoiceWriter.reconcile(detail: WatchDetail): WatchDetail {
    val key = seriesSubtitleChoiceKey(detail.seriesId, detail.contentId) ?: return detail
    // [detail] was read before this flush, so a choice that lands during it
    // is not in it yet: it still applies, as the one now on the server. (One
    // the server refused applies to this start too; the next read corrects it.)
    val unsentBeforeFlush = pendingChoice(key)
    withTimeoutOrNull(LOAD_FLUSH_TIMEOUT_MS) { flushNow() }
    return detail.withPendingSeriesSubtitleChoice(pendingChoice(key) ?: unsentBeforeFlush)
}

/** [detail] with a choice still unsent laid over it; no flush. */
suspend fun SeriesSubtitleChoiceWriter.overlayPendingChoice(detail: WatchDetail): WatchDetail {
    val key = seriesSubtitleChoiceKey(detail.seriesId, detail.contentId) ?: return detail
    return detail.withPendingSeriesSubtitleChoice(pendingChoice(key))
}

/** How long opening an item waits for a queued subtitle choice to land. */
private const val LOAD_FLUSH_TIMEOUT_MS = 1_500L

/**
 * Application-scoped, in-memory queue: the latest intent per (profile, series)
 * wins, it is sent at once, and a transient failure (network, 5xx, 408, 429)
 * stays queued and is retried with backoff and again on the next save or
 * [flushNow] — like the settings flusher, so a failed write is not silently
 * lost while the app runs. A write whose profile is no longer the active one,
 * or that the server refuses, is dropped rather than sent elsewhere. Process
 * death loses the queue.
 */
class DefaultSeriesSubtitleChoiceWriter(
    private val put: suspend (seriesKey: String, choice: SeriesSubtitleChoice, owner: AuthScopeSnapshot) -> ApiResult<Unit>,
    private val delete: suspend (seriesKey: String, owner: AuthScopeSnapshot) -> ApiResult<Unit>,
    private val captureOwner: suspend () -> AuthScopeSnapshot?,
    private val scope: CoroutineScope,
) : SeriesSubtitleChoiceWriter {

    private data class Key(val profileId: String, val serverUrl: String, val seriesKey: String)

    /**
     * Compared by reference: [settle] must tell a repeated save of the same
     * choice from the one it replaced, or the replaced one stays "unsent".
     */
    private class Op(val owner: AuthScopeSnapshot, val choice: SeriesSubtitleChoice?)

    private val lock = Any()
    private val pending = LinkedHashMap<Key, Op>()

    /** Every op not yet settled — queued, in flight or held for retry. */
    private val unsent = MutableStateFlow<Map<Key, Op>>(emptyMap())
    private val sendMutex = Mutex()
    private var retryJob: Job? = null
    private var retryAttempts = 0

    override suspend fun save(seriesKey: String, choice: SeriesSubtitleChoice?) {
        if (seriesKey.isBlank()) return
        val owner = captureOwner() ?: return
        val profileId = owner.profileId?.takeIf { it.isNotBlank() } ?: return
        synchronized(lock) {
            val key = Key(profileId, owner.serverUrl, seriesKey)
            val op = Op(owner, choice)
            pending.remove(key)
            pending[key] = op
            unsent.value = unsent.value + (key to op)
            retryAttempts = 0
            retryJob?.cancel()
            retryJob = null
        }
        scope.launch { drain() }
    }

    override suspend fun flushNow() {
        synchronized(lock) {
            retryJob?.cancel()
            retryJob = null
            retryAttempts = 0
        }
        drain()
    }

    override suspend fun pendingChoice(seriesKey: String): PendingSeriesSubtitleChoice? {
        val owner = captureOwner() ?: return null
        val profileId = owner.profileId?.takeIf { it.isNotBlank() } ?: return null
        return unsent.value[Key(profileId, owner.serverUrl, seriesKey)]
            ?.let { PendingSeriesSubtitleChoice(it.choice) }
    }

    override val pendingChoices: Flow<Map<String, PendingSeriesSubtitleChoice>> =
        unsent.map { ops ->
            val owner = captureOwner()
            ops.filterKeys { it.profileId == owner?.profileId && it.serverUrl == owner.serverUrl }
                .entries.associate { (key, op) -> key.seriesKey to PendingSeriesSubtitleChoice(op.choice) }
        }

    /** Settles [op]: it stops counting as unsent unless a newer op replaced it. */
    private fun settle(key: Key, op: Op) {
        synchronized(lock) {
            if (unsent.value[key] === op) unsent.value = unsent.value - key
        }
    }

    /** For tests: the series keys still queued for [profileId]. */
    internal fun pendingSeries(profileId: String): Set<String> = synchronized(lock) {
        pending.keys.filter { it.profileId == profileId }.map { it.seriesKey }.toSet()
    }

    private suspend fun drain() {
        sendMutex.withLock {
            val retry = LinkedHashMap<Key, Op>()
            // Taken out of `pending` but not sent yet (or in flight).
            val unfinished = LinkedHashMap<Key, Op>()
            try {
                while (true) {
                    val batch = synchronized(lock) {
                        pending.toMap().also { pending.clear() }
                    }
                    if (batch.isEmpty()) break
                    unfinished.putAll(batch)
                    for ((key, op) in batch) {
                        if (send(key, op)) {
                            retry[key] = op
                        } else {
                            retry.remove(key)
                            settle(key, op)
                        }
                        unfinished.remove(key)
                    }
                }
            } catch (e: CancellationException) {
                // The caller gave up waiting (a load's flush timeout): put
                // back what this drain still owed, a newer choice winning, and
                // let the retry send it — otherwise it stays "unsent" with
                // nothing left to send it.
                synchronized(lock) {
                    for ((key, op) in retry + unfinished) {
                        if (key !in pending) pending[key] = op
                    }
                    if (pending.isNotEmpty()) scheduleRetry(attempt = 1)
                }
                throw e
            }
            if (retry.isEmpty()) {
                synchronized(lock) { retryAttempts = 0 }
                return
            }
            val attempt = synchronized(lock) {
                // A newer choice queued meanwhile wins over the failed one.
                for ((key, op) in retry) {
                    if (key !in pending) pending[key] = op else settle(key, op)
                }
                if (retryAttempts >= MAX_AUTO_RETRIES) null else ++retryAttempts
            } ?: return
            synchronized(lock) { scheduleRetry(attempt) }
        }
    }

    /** Call with [lock] held. */
    private fun scheduleRetry(attempt: Int) {
        retryJob?.cancel()
        retryJob = scope.launch {
            delay((RETRY_BASE_DELAY_MS shl (attempt - 1).coerceIn(0, 6)).coerceAtMost(RETRY_MAX_DELAY_MS))
            scope.launch { drain() }
        }
    }

    /** Sends one op; true when it must stay queued for retry. */
    private suspend fun send(key: Key, op: Op): Boolean {
        val result = try {
            val choice = op.choice
            if (choice == null) delete(key.seriesKey, op.owner) else put(key.seriesKey, choice, op.owner)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ApiResult.NetworkError(e)
        }
        val verb = if (op.choice == null) "clear" else "save"
        return when (result) {
            is ApiResult.Success -> false
            is ApiResult.NetworkError -> {
                SiloLog.w(CATEGORY, TAG, "$verb subtitle choice failed (network); kept queued for retry")
                true
            }
            is ApiResult.Error -> {
                val transient = result.code >= 500 || result.code == 408 || result.code == 429
                SiloLog.w(
                    CATEGORY, TAG,
                    "$verb subtitle choice failed (${result.code} ${result.error}); " +
                        if (transient) "kept queued for retry" else "dropped",
                )
                transient
            }
        }
    }

    private companion object {
        const val TAG = "SeriesSubtitleChoice"
        val CATEGORY = DiagnosticsLogCategory.NETWORK
        const val MAX_AUTO_RETRIES = 5
        const val RETRY_BASE_DELAY_MS = 1_000L
        const val RETRY_MAX_DELAY_MS = 60_000L
    }
}
