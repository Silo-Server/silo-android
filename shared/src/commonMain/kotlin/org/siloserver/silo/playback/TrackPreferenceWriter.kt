package org.siloserver.silo.playback

import io.ktor.http.HttpStatusCode
import io.ktor.http.fromHttpToGmtDate
import io.ktor.util.date.getTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.siloserver.silo.model.playback.AudioPreferenceUpdate
import org.siloserver.silo.model.playback.SubtitlePreferenceUpdate
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.apiv2.TrackPreferenceRead
import org.siloserver.silo.network.apiv2.TrackPreferencesV2Api
import org.siloserver.silo.network.apiv2.captureProfileScope
import org.siloserver.silo.network.apiv2.stillOwns
import org.siloserver.silo.util.parseRfc3339ToEpochMillis

/**
 * Sends the viewer's track picks to the server's per-series preference.
 *
 * Every pick is first recorded in [store] under the server and profile it was
 * made in, then one serialized worker sends it, so picks reach the server in
 * pick order and a pick made offline (while playing a download) is sent once
 * that profile is active and the server answers again. A pick is never sent
 * under another profile or server.
 *
 * The server keeps no version for a row, so before sending, the worker reads
 * the row. If it is still the one this device had seen when the pick was
 * made, the pick is sent. If it changed (another device picked meanwhile) the
 * pick is sent only when it is the later of the two, comparing on the
 * server's clock: the pick time is shifted by the skew between the server's
 * `Date` and this device's clock. Otherwise the server's row stands and the
 * pick is dropped. A write landing between that read and the send can still be
 * overwritten; the server offers no conditional write to prevent it.
 *
 * Callers pass only deliberate picks. Restored and automatic selections are
 * the server's own answer and must not be written back as the viewer's.
 */
class TrackPreferenceWriter(
    private val api: TrackPreferencesV2Api,
    private val tokens: TokenManager,
    private val scope: CoroutineScope,
    private val store: TrackPreferenceStore = InMemoryTrackPreferenceStore(),
    private val nowMs: () -> Long = { getTimeMillis() },
) {
    private val work = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var retry: Job? = null

    init {
        scope.launch {
            for (job in work) {
                try {
                    job()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // A pick stays pending for the next attempt; playback never waits on it.
                }
            }
        }
        flush()
    }

    fun saveAudio(owner: AuthScopeSnapshot, key: String, update: AudioPreferenceUpdate) =
        save(owner, TrackPreferenceKind.AUDIO, key, SiloJson.encodeToString(AudioPreferenceUpdate.serializer(), update))

    fun saveSubtitle(owner: AuthScopeSnapshot, key: String, update: SubtitlePreferenceUpdate) =
        save(owner, TrackPreferenceKind.SUBTITLE, key, SiloJson.encodeToString(SubtitlePreferenceUpdate.serializer(), update))

    /** Sends the active profile's pending picks, as when the server answers again. */
    fun flush() {
        work.trySend { drain() }
    }

    /**
     * Reads the server's rows for [key] into the store, waiting at most
     * [timeoutMs]. A pending pick is left as it is.
     */
    suspend fun refresh(owner: AuthScopeSnapshot, key: String, timeoutMs: Long) {
        val done = CompletableDeferred<Unit>()
        work.trySend {
            try {
                for (kind in TrackPreferenceKind.entries) {
                    val read = (api.read(owner, kind, key) as? ApiResult.Success)?.data ?: break
                    val profileId = owner.profileId ?: break
                    val record = store.get(owner.serverId, profileId, kind, key)
                        ?: TrackPreferenceRecord(owner.serverId, profileId, kind, key)
                    store.put(record.observed(read))
                }
            } finally {
                done.complete(Unit)
            }
        }
        withTimeoutOrNull(timeoutMs) { done.await() }
        flush()
    }

    /** The audio pick playback should assume for [key]: a pending pick, else the server's last row. */
    suspend fun rememberedAudio(serverId: String, profileId: String, key: String): AudioPreferenceUpdate? =
        remembered(serverId, profileId, TrackPreferenceKind.AUDIO, key)
            ?.let { SiloJson.decodeFromString(AudioPreferenceUpdate.serializer(), it) }

    /** As [rememberedAudio], for subtitles. */
    suspend fun rememberedSubtitle(serverId: String, profileId: String, key: String): SubtitlePreferenceUpdate? =
        remembered(serverId, profileId, TrackPreferenceKind.SUBTITLE, key)
            ?.let { SiloJson.decodeFromString(SubtitlePreferenceUpdate.serializer(), it) }

    private suspend fun remembered(serverId: String, profileId: String, kind: TrackPreferenceKind, key: String): String? =
        store.get(serverId, profileId, kind, key)?.let { it.pending ?: it.serverRow }

    private fun save(owner: AuthScopeSnapshot, kind: TrackPreferenceKind, key: String, body: String) {
        val pickedAtMs = nowMs()
        work.trySend {
            val profileId = owner.profileId ?: return@trySend
            if (!owner.stillOwns(tokens)) return@trySend
            val record = store.get(owner.serverId, profileId, kind, key)
                ?: TrackPreferenceRecord(owner.serverId, profileId, kind, key)
            store.put(
                record.copy(
                    pending = body,
                    pendingPickedAtMs = pickedAtMs,
                    pendingBaselineUpdatedAt = record.serverUpdatedAt,
                    pendingBaselineKnown = record.serverObserved,
                    pendingGeneration = record.pendingGeneration + 1,
                ),
            )
            drain()
        }
    }

    private suspend fun drain() {
        val owner = tokens.captureProfileScope() ?: return
        val profileId = owner.profileId ?: return
        for (record in store.pending(owner.serverId, profileId)) {
            if (!send(owner, record)) return
        }
    }

    /** False when the server could not answer, so the remaining picks wait too. */
    private suspend fun send(owner: AuthScopeSnapshot, record: TrackPreferenceRecord): Boolean {
        val body = record.pending ?: return true
        val read = when (val result = api.read(owner, record.kind, record.key)) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> {
                if (result.code == HttpStatusCode.TooManyRequests.value) retryAfterRateLimit()
                return false
            }
            is ApiResult.NetworkError -> return false
        }
        val receivedAtMs = nowMs()
        val serverUnchanged = record.pendingBaselineKnown && read.updatedAt == record.pendingBaselineUpdatedAt
        if (!serverUnchanged && !record.isNewerThan(read, receivedAtMs)) {
            settle(record) { it.observed(read) }
            return true
        }
        when (val result = api.put(owner, record.kind, record.key, body)) {
            is ApiResult.Success -> {
                // Read the row back so the next pick knows the version it replaces
                // instead of racing its own write on the clock.
                val written = (api.read(owner, record.kind, record.key) as? ApiResult.Success)?.data
                settle(record) {
                    written?.let { read -> it.observed(read) } ?: it.copy(serverRow = body, serverUpdatedAt = null, serverObserved = false)
                }
            }
            // A body the server refuses will be refused again; anything else is retried later.
            is ApiResult.Error -> when {
                result.code == HttpStatusCode.TooManyRequests.value -> {
                    retryAfterRateLimit()
                    return false
                }
                result.code in 400..499 && result.code != 401 && result.code != 403 -> settle(record) { it }
                else -> return false
            }
            is ApiResult.NetworkError -> return false
        }
        return true
    }

    /**
     * Sends again once the server's rate limit has passed. A reconnect would
     * never come: the server did not go away.
     */
    private fun retryAfterRateLimit() {
        if (retry?.isActive == true) return
        retry = scope.launch {
            delay(RATE_LIMIT_RETRY_MS)
            flush()
        }
    }

    /** Clears [sent]'s pick unless a newer pick replaced it meanwhile, and applies [update]. */
    private suspend fun settle(sent: TrackPreferenceRecord, update: (TrackPreferenceRecord) -> TrackPreferenceRecord) {
        val current = store.get(sent.serverId, sent.profileId, sent.kind, sent.key) ?: return
        val cleared = if (current.pendingGeneration == sent.pendingGeneration) current.copy(pending = null) else current
        store.put(update(cleared))
    }

    /**
     * Whether the pick was made after the server's row changed, on the server's
     * clock. `updated_at` has whole seconds, so a tie goes to the pick.
     */
    private fun TrackPreferenceRecord.isNewerThan(read: TrackPreferenceRead, receivedAtMs: Long): Boolean {
        val rowUpdatedMs = read.updatedAt?.let(::parseRfc3339ToEpochMillis) ?: return true
        val serverNowMs = read.serverDate?.let { runCatching { it.fromHttpToGmtDate().timestamp }.getOrNull() }
        val skewMs = serverNowMs?.let { it - receivedAtMs } ?: 0L
        return pendingPickedAtMs + skewMs >= rowUpdatedMs
    }

    private fun TrackPreferenceRecord.observed(read: TrackPreferenceRead) =
        copy(serverRow = read.row?.toString(), serverUpdatedAt = read.updatedAt, serverObserved = true)
}

/** The wait before sending again after a 429. */
private const val RATE_LIMIT_RETRY_MS = 60_000L
