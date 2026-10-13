package org.siloserver.silo.common.settings

import kotlinx.coroutines.CoroutineScope
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
}

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

    private data class Op(val owner: AuthScopeSnapshot, val choice: SeriesSubtitleChoice?)

    private val lock = Any()
    private val pending = LinkedHashMap<Key, Op>()
    private val sendMutex = Mutex()
    private var retryJob: Job? = null
    private var retryAttempts = 0

    override suspend fun save(seriesKey: String, choice: SeriesSubtitleChoice?) {
        if (seriesKey.isBlank()) return
        val owner = captureOwner() ?: return
        val profileId = owner.profileId?.takeIf { it.isNotBlank() } ?: return
        synchronized(lock) {
            val key = Key(profileId, owner.serverUrl, seriesKey)
            pending.remove(key)
            pending[key] = Op(owner, choice)
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

    /** For tests: the series keys still queued for [profileId]. */
    internal fun pendingSeries(profileId: String): Set<String> = synchronized(lock) {
        pending.keys.filter { it.profileId == profileId }.map { it.seriesKey }.toSet()
    }

    private suspend fun drain() {
        sendMutex.withLock {
            val retry = LinkedHashMap<Key, Op>()
            while (true) {
                val batch = synchronized(lock) {
                    pending.toMap().also { pending.clear() }
                }
                if (batch.isEmpty()) break
                for ((key, op) in batch) {
                    if (send(key, op)) retry[key] = op else retry.remove(key)
                }
            }
            if (retry.isEmpty()) {
                synchronized(lock) { retryAttempts = 0 }
                return
            }
            val attempt = synchronized(lock) {
                // A newer choice queued meanwhile wins over the failed one.
                for ((key, op) in retry) if (key !in pending) pending[key] = op
                if (retryAttempts >= MAX_AUTO_RETRIES) null else ++retryAttempts
            } ?: return
            synchronized(lock) {
                retryJob?.cancel()
                retryJob = scope.launch {
                    delay((RETRY_BASE_DELAY_MS shl (attempt - 1).coerceIn(0, 6)).coerceAtMost(RETRY_MAX_DELAY_MS))
                    scope.launch { drain() }
                }
            }
        }
    }

    /** Sends one op; true when it must stay queued for retry. */
    private suspend fun send(key: Key, op: Op): Boolean {
        val result = try {
            val choice = op.choice
            if (choice == null) delete(key.seriesKey, op.owner) else put(key.seriesKey, choice, op.owner)
        } catch (e: kotlinx.coroutines.CancellationException) {
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
