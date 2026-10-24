package org.siloserver.silo.common.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.siloserver.silo.common.diagnostics.SiloLog
import org.siloserver.silo.model.catalog.WatchDetail
import org.siloserver.silo.model.diagnostics.DiagnosticsLogCategory
import org.siloserver.silo.model.playback.PendingSeriesAudioChoice
import org.siloserver.silo.model.playback.PendingSeriesSubtitleChoice
import org.siloserver.silo.model.playback.SeriesAudioChoice
import org.siloserver.silo.model.playback.SeriesSubtitleChoice
import org.siloserver.silo.model.playback.seriesTrackChoiceKey
import org.siloserver.silo.model.playback.withPendingSeriesAudioChoice
import org.siloserver.silo.model.playback.withPendingSeriesSubtitleChoice
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.IdentityTransition
import org.siloserver.silo.network.IdentityTransitionPhase

/**
 * Remembers manual audio and subtitle choices for a whole series on the
 * server (`/api/v2/audio-prefs/{series_id}` and
 * `/api/v2/subtitle-prefs/{series_id}`), so a choice applies to every episode,
 * after a relaunch and on the profile's other devices — the server folds it
 * into the next watch detail (`effective_audio_track_index`,
 * `effective_subtitle_*`).
 *
 * Only a viewer's own pick is saved. Restores, fallbacks and carried choices
 * never are: they would turn an automatic outcome into a series preference.
 */
interface SeriesTrackChoiceWriter {
    /**
     * Remember a subtitle [choice] for [seriesKey] (see [seriesTrackChoiceKey]);
     * null goes back to automatic. [owner] is the account, server and profile
     * the pick was made under — a player passes the one its playback belongs
     * to. The write is skipped when that is no longer the active profile on
     * the active server (a download playing for another profile), and can only
     * ever land on [owner]. Null [owner] means the active profile.
     */
    suspend fun saveSubtitle(seriesKey: String, choice: SeriesSubtitleChoice?, owner: AuthScopeSnapshot? = null)

    /** [saveSubtitle] for the series' audio track. */
    suspend fun saveAudio(seriesKey: String, choice: SeriesAudioChoice?, owner: AuthScopeSnapshot? = null)

    /** Send whatever is still queued (a failed write retried), and wait for it. */
    suspend fun flushNow()

    /**
     * The subtitle choice the active profile made for [seriesKey] that has not
     * landed yet (queued, in flight, or waiting to retry), or null when there
     * is none. Until it lands the server still answers with the older choice.
     */
    suspend fun pendingSubtitle(seriesKey: String): PendingSeriesSubtitleChoice? = null

    /** [pendingSubtitle] for the series' audio track. */
    suspend fun pendingAudio(seriesKey: String): PendingSeriesAudioChoice? = null

    /**
     * Whether the active profile last set [seriesKey]'s subtitles back to
     * Auto on this device, with no subtitle pick since. This device's
     * per-file subtitle memory then stays out even though the server holds no
     * series choice: Auto means the profile's defaults, not an older pick.
     */
    suspend fun subtitleResetToAuto(seriesKey: String): Boolean = false

    /** [pendingSubtitle] for every series of the active profile, as it changes. */
    val pendingSubtitleChoices: Flow<Map<String, PendingSeriesSubtitleChoice>>
        get() = flowOf(emptyMap())

    /** [pendingAudio] for every series of the active profile, as it changes. */
    val pendingAudioChoices: Flow<Map<String, PendingSeriesAudioChoice>>
        get() = flowOf(emptyMap())
}

/**
 * [detail] as playback should start it: a queued write gets a short chance to
 * land, and a choice still unsent is laid over the server's effective audio
 * and subtitle fields. Without this, reopening an item while a pick waited to
 * retry started from the server's older choice — and the per-file memory of
 * the new one was filtered out against it.
 */
suspend fun SeriesTrackChoiceWriter.reconcile(detail: WatchDetail): WatchDetail {
    val key = seriesTrackChoiceKey(detail.seriesId, detail.contentId) ?: return detail
    // [detail] was read before this flush, so a choice that lands during it
    // is not in it yet: it still applies, as the one now on the server. (One
    // the server refused applies to this start too; the next read corrects it.)
    val subtitleBeforeFlush = pendingSubtitle(key)
    val audioBeforeFlush = pendingAudio(key)
    withTimeoutOrNull(LOAD_FLUSH_TIMEOUT_MS) { flushNow() }
    return detail
        .withPendingSeriesSubtitleChoice(pendingSubtitle(key) ?: subtitleBeforeFlush)
        .withPendingSeriesAudioChoice(pendingAudio(key) ?: audioBeforeFlush)
}

/** Whether the series audio was set back to Auto here and that has not landed. */
suspend fun SeriesTrackChoiceWriter.audioResetToAutoPending(seriesKey: String): Boolean =
    pendingAudio(seriesKey)?.let { it.choice == null } == true

/** [detail] with the choices still unsent laid over it; no flush. */
suspend fun SeriesTrackChoiceWriter.overlayPendingChoice(detail: WatchDetail): WatchDetail {
    val key = seriesTrackChoiceKey(detail.seriesId, detail.contentId) ?: return detail
    return detail
        .withPendingSeriesSubtitleChoice(pendingSubtitle(key))
        .withPendingSeriesAudioChoice(pendingAudio(key))
}

/** How long opening an item waits for a queued choice to land. */
private const val LOAD_FLUSH_TIMEOUT_MS = 1_500L

/**
 * Application-scoped, in-memory queue: the latest intent per (profile,
 * server, series, audio or subtitle) wins, writes go out in the order they
 * were made, and a transient failure (network, 5xx, 408, 429) stays queued and
 * is retried with backoff and again on the next save or [flushNow] — like the
 * settings flusher, so a failed write is not silently lost while the app runs.
 * A write whose profile is no longer the active one, or that the server
 * refuses, is dropped rather than sent elsewhere.
 *
 * A write that has not landed within [maxPendingAgeMs] is dropped: by then a
 * newer choice may have been made on another device, and sending this one
 * late would overwrite it. Until then, a write that ran out of automatic
 * retries is sent again whenever [resumeSignals] fires (the server is
 * reachable again, the app returned to the foreground). Process death loses
 * the queue.
 *
 * A write is sent with the active profile's current credentials while that is
 * still the profile it was made under (same server id and profile id): a
 * profile token rotated by a PIN unlock or a reselect must not drop it. While
 * another profile is active it waits instead of being sent.
 *
 * Picks are ordered when [saveSubtitle] or [saveAudio] is called, before any
 * suspension, so two quick picks land in the order the viewer made them even
 * when the calls resume out of order.
 *
 * Keys use the server id, not its URL: the URL changes when the app moves
 * between the home network and the remote address of the same server.
 */
class DefaultSeriesTrackChoiceWriter(
    private val putSubtitle: suspend (seriesKey: String, choice: SeriesSubtitleChoice, owner: AuthScopeSnapshot) -> ApiResult<Unit>,
    private val deleteSubtitle: suspend (seriesKey: String, owner: AuthScopeSnapshot) -> ApiResult<Unit>,
    private val putAudio: suspend (seriesKey: String, choice: SeriesAudioChoice, owner: AuthScopeSnapshot) -> ApiResult<Unit> =
        { _, _, _ -> ApiResult.Success(Unit) },
    private val deleteAudio: suspend (seriesKey: String, owner: AuthScopeSnapshot) -> ApiResult<Unit> =
        { _, _ -> ApiResult.Success(Unit) },
    private val captureOwner: suspend () -> AuthScopeSnapshot?,
    private val scope: CoroutineScope,
    /**
     * Account, server and profile switches. The pending flows are filtered to
     * the active profile, so they have to be worked out again when that
     * changes even if the queue does not.
     */
    private val identityChanges: Flow<IdentityTransition> = emptyFlow(),
    private val now: () -> Long = System::currentTimeMillis,
    private val maxPendingAgeMs: Long = MAX_PENDING_AGE_MS,
    /** Moments worth trying the queue again: the server reachable, the app foregrounded. */
    resumeSignals: Flow<Unit> = emptyFlow(),
    /** Where Auto set on this device is remembered across launches. */
    private val autoResets: SeriesAutoResetStore = SeriesAutoResetStore.InMemory(),
) : SeriesTrackChoiceWriter {

    private enum class Kind(val label: String) { AUDIO("audio"), SUBTITLE("subtitle") }

    private data class Key(val profileId: String, val serverId: String, val seriesKey: String, val kind: Kind)

    /**
     * Compared by reference: [settle] must tell a repeated save of the same
     * choice from the one it replaced, or the replaced one stays "unsent".
     * [choice] is a [SeriesSubtitleChoice] or [SeriesAudioChoice] matching the
     * key's kind; null is back to automatic.
     */
    private class Op(val owner: AuthScopeSnapshot, val choice: Any?, val queuedAt: Long, val sequence: Long)

    private val lock = Any()
    private val pending = LinkedHashMap<Key, Op>()

    /** Taken at each save's call, before it suspends: the viewer's pick order. */
    private val sequence = java.util.concurrent.atomic.AtomicLong()

    /** The newest pick's sequence per key, so an older one resuming late is dropped. */
    private val newestSequence = HashMap<Key, Long>()

    /** Every op not yet settled — queued, in flight or held for retry. */
    private val unsent = MutableStateFlow<Map<Key, Op>>(emptyMap())
    private val sendMutex = Mutex()
    private var retryJob: Job? = null
    private var retryAttempts = 0

    override suspend fun saveSubtitle(seriesKey: String, choice: SeriesSubtitleChoice?, owner: AuthScopeSnapshot?) =
        save(seriesKey, Kind.SUBTITLE, choice, owner)

    override suspend fun saveAudio(seriesKey: String, choice: SeriesAudioChoice?, owner: AuthScopeSnapshot?) =
        save(seriesKey, Kind.AUDIO, choice, owner)

    private suspend fun save(seriesKey: String, kind: Kind, choice: Any?, owner: AuthScopeSnapshot?) {
        val order = sequence.incrementAndGet()
        if (seriesKey.isBlank()) return
        val active = captureOwner() ?: return
        val writer = owner ?: active
        val profileId = writer.profileId?.takeIf { it.isNotBlank() } ?: return
        if (writer.serverId != active.serverId || profileId != active.profileId) {
            SiloLog.i(CATEGORY, TAG, "${kind.label} choice not saved: its playback belongs to another profile or server")
            return
        }
        synchronized(lock) {
            val key = Key(profileId, writer.serverId, seriesKey, kind)
            if ((newestSequence[key] ?: 0L) > order) return
            newestSequence[key] = order
            val op = Op(writer, choice, now(), order)
            if (kind == Kind.SUBTITLE) autoResets.set(key.resetId(), reset = choice == null)
            pending.remove(key)
            pending[key] = op
            unsent.value = unsent.value + (key to op)
            retryAttempts = 0
            retryJob?.cancel()
            retryJob = null
        }
        scope.launch { drain() }
    }

    init {
        scope.launch {
            // A sign-out, account replacement or server removal ends the login
            // the queued picks were made under. A later sign-in to the same
            // server and profile is another login: sending them with its
            // credentials would act for it. Profile switches and token
            // rotation keep the queue (see [send]).
            identityChanges.collect { transition ->
                if (transition.phase != IdentityTransitionPhase.WILL_CHANGE || !transition.purgesPersistentIdentity) return@collect
                if (transition.kind !in LOGIN_ENDING_TRANSITIONS) return@collect
                dropLogin(transition.targetServerId)
            }
        }
        scope.launch {
            resumeSignals.collect {
                if (synchronized(lock) { pending.isEmpty() }) return@collect
                flushNow()
            }
        }
    }

    /**
     * The drain runs in [scope], not the caller: a caller that stops waiting
     * (a load's flush timeout) must not cancel a request already in flight,
     * which may well land; it then settles as usual.
     */
    override suspend fun flushNow() {
        synchronized(lock) {
            retryJob?.cancel()
            retryJob = null
            retryAttempts = 0
        }
        scope.launch { drain() }.join()
    }

    override suspend fun subtitleResetToAuto(seriesKey: String): Boolean {
        val owner = captureOwner() ?: return false
        val profileId = owner.profileId?.takeIf { it.isNotBlank() } ?: return false
        return autoResets.isReset(Key(profileId, owner.serverId, seriesKey, Kind.SUBTITLE).resetId())
    }

    private fun Key.resetId(): String = listOf(serverId, profileId, kind.label, seriesKey).joinToString("\u001f")

    override suspend fun pendingSubtitle(seriesKey: String): PendingSeriesSubtitleChoice? =
        pendingOp(seriesKey, Kind.SUBTITLE)?.let { PendingSeriesSubtitleChoice(it.choice as SeriesSubtitleChoice?) }

    override suspend fun pendingAudio(seriesKey: String): PendingSeriesAudioChoice? =
        pendingOp(seriesKey, Kind.AUDIO)?.let { PendingSeriesAudioChoice(it.choice as SeriesAudioChoice?) }

    private suspend fun pendingOp(seriesKey: String, kind: Kind): Op? {
        val owner = captureOwner() ?: return null
        val profileId = owner.profileId?.takeIf { it.isNotBlank() } ?: return null
        return unsent.value[Key(profileId, owner.serverId, seriesKey, kind)]?.takeUnless(::expired)
    }

    private fun <T> pendingFlow(kind: Kind, wrap: (Any?) -> T): Flow<Map<String, T>> =
        combine(unsent, identityChanges.onStart<IdentityTransition?> { emit(null) }) { ops, transition ->
            // Mid-switch the old profile's choices no longer apply, and the
            // new one is not readable until the switch has finished.
            if (transition?.phase == IdentityTransitionPhase.WILL_CHANGE) return@combine emptyMap()
            val owner = captureOwner()
            ops.filter { (key, op) ->
                key.kind == kind && key.profileId == owner?.profileId && key.serverId == owner.serverId && !expired(op)
            }.entries.associate { (key, op) -> key.seriesKey to wrap(op.choice) }
        }

    override val pendingSubtitleChoices: Flow<Map<String, PendingSeriesSubtitleChoice>> =
        pendingFlow(Kind.SUBTITLE) { PendingSeriesSubtitleChoice(it as SeriesSubtitleChoice?) }

    override val pendingAudioChoices: Flow<Map<String, PendingSeriesAudioChoice>> =
        pendingFlow(Kind.AUDIO) { PendingSeriesAudioChoice(it as SeriesAudioChoice?) }

    private fun expired(op: Op): Boolean = now() - op.queuedAt > maxPendingAgeMs

    /** Settles [op]: it stops counting as unsent unless a newer op replaced it. */
    private fun settle(key: Key, op: Op) {
        synchronized(lock) {
            if (unsent.value[key] === op) unsent.value = unsent.value - key
        }
    }

    /** Drops every queued pick for [serverId] (every server when null). */
    private fun dropLogin(serverId: String?) {
        synchronized(lock) {
            val doomed = unsent.value.keys.filter { serverId == null || it.serverId == serverId }.toSet() +
                pending.keys.filter { serverId == null || it.serverId == serverId }
            if (doomed.isEmpty()) return
            doomed.forEach { pending.remove(it); newestSequence.remove(it) }
            unsent.value = unsent.value - doomed
            if (pending.isEmpty()) {
                retryJob?.cancel()
                retryJob = null
            }
        }
        SiloLog.i(CATEGORY, TAG, "queued track choices dropped: the login they were made under ended")
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
            } catch (e: Throwable) {
                // The writer's scope is going away, or a step failed outside
                // a request: put back what this drain still owed, a newer
                // choice winning, so it is retried rather than left counted
                // as unsent with nothing to send it.
                synchronized(lock) {
                    for ((key, op) in retry + unfinished) {
                        // Not a dropped login's pick, nor one a newer pick replaced.
                        if (key !in pending && unsent.value[key] === op) pending[key] = op
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
                    when {
                        unsent.value[key] !== op -> Unit // dropped with its login, or replaced
                        key !in pending -> pending[key] = op
                        else -> settle(key, op)
                    }
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
        val verb = if (op.choice == null) "clear" else "save"
        if (expired(op)) {
            SiloLog.w(CATEGORY, TAG, "$verb ${key.kind.label} choice dropped: it did not land in time")
            return false
        }
        // The active profile's current credentials while it is still the
        // profile the pick was made under; while another one is active, wait.
        val active = try {
            captureOwner()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SiloLog.w(CATEGORY, TAG, "$verb ${key.kind.label} choice waits: the active profile could not be read")
            return true
        }
        val owner = when {
            active == null -> return true
            active.serverId == op.owner.serverId && active.profileId == op.owner.profileId -> active
            else -> {
                SiloLog.i(CATEGORY, TAG, "$verb ${key.kind.label} choice waits: another profile is active")
                return true
            }
        }
        val result = try {
            when (key.kind) {
                Kind.SUBTITLE -> (op.choice as SeriesSubtitleChoice?)
                    ?.let { putSubtitle(key.seriesKey, it, owner) }
                    ?: deleteSubtitle(key.seriesKey, owner)
                Kind.AUDIO -> (op.choice as SeriesAudioChoice?)
                    ?.let { putAudio(key.seriesKey, it, owner) }
                    ?: deleteAudio(key.seriesKey, owner)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ApiResult.NetworkError(e)
        }
        return when (result) {
            is ApiResult.Success -> false
            is ApiResult.NetworkError -> {
                SiloLog.w(CATEGORY, TAG, "$verb ${key.kind.label} choice failed (network); kept queued for retry")
                true
            }
            is ApiResult.Error -> {
                // identity_changed: the profile's credentials moved while the
                // request was out; the next attempt reads them again.
                val transient = result.code >= 500 || result.code == 408 || result.code == 429 ||
                    result.error == IDENTITY_CHANGED
                SiloLog.w(
                    CATEGORY, TAG,
                    "$verb ${key.kind.label} choice failed (${result.code} ${result.error}); " +
                        if (transient) "kept queued for retry" else "dropped",
                )
                transient
            }
        }
    }

    private companion object {
        const val TAG = "SeriesTrackChoice"
        val CATEGORY = DiagnosticsLogCategory.NETWORK
        const val MAX_AUTO_RETRIES = 5
        const val RETRY_BASE_DELAY_MS = 1_000L
        const val RETRY_MAX_DELAY_MS = 60_000L
        const val MAX_PENDING_AGE_MS = 10 * 60_000L
        const val IDENTITY_CHANGED = "identity_changed"
        val LOGIN_ENDING_TRANSITIONS = setOf(
            org.siloserver.silo.network.IdentityTransitionKind.SIGN_OUT,
            org.siloserver.silo.network.IdentityTransitionKind.ACCOUNT_REPLACE,
            org.siloserver.silo.network.IdentityTransitionKind.SERVER_REMOVE,
        )
    }
}

/**
 * The series whose subtitles were set back to Auto on this device, by
 * (server, profile, kind, series). Kept across launches: the per-file memory
 * it silences is too.
 */
interface SeriesAutoResetStore {
    fun isReset(id: String): Boolean
    fun set(id: String, reset: Boolean)

    class InMemory : SeriesAutoResetStore {
        private val ids = java.util.Collections.synchronizedSet(HashSet<String>())
        override fun isReset(id: String): Boolean = id in ids
        override fun set(id: String, reset: Boolean) {
            if (reset) ids.add(id) else ids.remove(id)
        }
    }
}

class SharedPreferencesSeriesAutoResetStore(context: android.content.Context) : SeriesAutoResetStore {
    private val prefs = context.getSharedPreferences("series_track_auto_resets", android.content.Context.MODE_PRIVATE)

    override fun isReset(id: String): Boolean = prefs.contains(id)

    override fun set(id: String, reset: Boolean) {
        if (reset == isReset(id)) return
        prefs.edit().apply { if (reset) putBoolean(id, true) else remove(id) }.apply()
    }
}
