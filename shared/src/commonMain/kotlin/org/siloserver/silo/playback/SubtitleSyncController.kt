package org.siloserver.silo.playback

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.siloserver.silo.model.playback.PlayerSubtitleInfo
import org.siloserver.silo.model.playback.SubtitleIdentity
import org.siloserver.silo.model.subtitles.DownloadedSubtitle
import org.siloserver.silo.model.subtitles.SubtitleSyncJob
import org.siloserver.silo.model.subtitles.SubtitleSyncResultLine
import org.siloserver.silo.model.subtitles.SubtitleSyncState
import org.siloserver.silo.model.subtitles.SubtitleTiming
import org.siloserver.silo.model.subtitles.subtitleSyncResultLine
import org.siloserver.silo.model.subtitles.subtitleSyncStatusLabel
import org.siloserver.silo.model.subtitles.syncPhaseLabel
import org.siloserver.silo.model.subtitles.syncProgressPercent
import org.siloserver.silo.model.subtitles.syncState
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.SubtitleSyncSource

/** What the player knows about one syncable subtitle's timing and sync. */
data class SubtitleSyncEntry(
    val state: SubtitleSyncState,
    /** A sync or timing request is on the wire. */
    val busy: Boolean = false,
    /** The server refused to let this viewer retime the subtitle (403, as in demo mode). */
    val forbidden: Boolean = false,
    /** The subtitle's format cannot be synced (422). */
    val unsupported: Boolean = false,
    /** The last action's failure, in plain words. */
    val error: String? = null,
    /** Polling gave up; reopening the subtitle menu re-arms it. */
    val pollExpired: Boolean = false,
    /**
     * A job this viewer started: a sync they asked for, or the automatic sync
     * of a subtitle they just downloaded or uploaded. The player reports its
     * progress and outcome to them.
     */
    val watchedJobId: String? = null,
) {
    val statusLabel: String? get() = subtitleSyncStatusLabel(state.timing, state.sync)
    val inProgress: Boolean get() = state.sync?.inProgress == true
}

data class SubtitleSyncUiState(
    /** The server can align subtitles to audio for this viewer. */
    val available: Boolean = false,
    /** Subtitle files next to the media can be synced too. */
    val externalAvailable: Boolean = false,
    /** Entries by sync key. */
    val entries: Map<String, SubtitleSyncEntry> = emptyMap(),
) {
    fun canSync(entry: SubtitleSyncEntry): Boolean =
        available && !entry.unsupported && (!entry.state.isSidecar || externalAvailable)
}

/** The sync status line for a row of the subtitle menu, or null when it has none. */
fun SubtitleSyncUiState.statusLabelFor(track: PlayerSubtitleInfo?): String? =
    track?.syncKey?.let(entries::get)?.statusLabel

/**
 * The timing section the player shows for its selected subtitle: "Sync to
 * audio" with a running job's progress, the last result, and "Reset timing".
 * Anyone who can play the file may retime it; a refusal (demo mode) replaces
 * the actions with a short explanation.
 */
data class SubtitleTimingActions(
    val key: String,
    val statusLabel: String?,
    val canSync: Boolean,
    val canReset: Boolean,
    val inProgress: Boolean,
    /** 0..100 while a job runs. */
    val percent: Int?,
    /** What a running job is doing. */
    val phaseLabel: String?,
    /** The last finished job's outcome. */
    val result: SubtitleSyncResultLine?,
    /** What a sync does, shown before the first one. */
    val note: String?,
    val busy: Boolean,
    val forbidden: Boolean,
    val error: String?,
) {
    val actionsEnabled: Boolean get() = !busy && !inProgress

    companion object {
        const val FORBIDDEN_MESSAGE = "This server doesn't allow changing subtitle timing."
        const val SIDECAR_NOTE = "Matches the timing to the audio for everyone. The file itself isn't changed."
        const val STORED_NOTE = "Matches the timing to the audio for everyone watching."
    }
}

fun SubtitleSyncUiState.timingActionsFor(key: String?): SubtitleTimingActions? {
    val entry = key?.let(entries::get) ?: return null
    val canSync = canSync(entry)
    val canReset = !entry.state.timing.isIdentity
    if (!entry.forbidden && !canSync && !canReset && entry.error == null) return null
    val job = entry.state.sync
    val inProgress = entry.inProgress
    val result = if (inProgress || entry.error != null) null else subtitleSyncResultLine(entry.state.timing, job)
    return SubtitleTimingActions(
        key = entry.state.key,
        statusLabel = entry.statusLabel,
        canSync = canSync,
        canReset = canReset,
        inProgress = inProgress,
        percent = syncProgressPercent(job),
        phaseLabel = syncPhaseLabel(job),
        result = result,
        note = when {
            entry.forbidden || !canSync || inProgress || result != null -> null
            entry.state.isSidecar -> SubtitleTimingActions.SIDECAR_NOTE
            else -> SubtitleTimingActions.STORED_NOTE
        },
        busy = entry.busy,
        forbidden = entry.forbidden,
        error = entry.error,
    )
}

/** The sync key of the subtitle a selection plays, or null when it has none. */
fun List<PlayerSubtitleInfo>.syncKeyFor(identity: SubtitleIdentity?): String? = when (identity) {
    is SubtitleIdentity.ServerSidecar -> firstOrNull { it.index == identity.serverIndex }?.syncKey
    is SubtitleIdentity.Downloaded -> firstOrNull { it.downloadId == identity.downloadId }?.syncKey
    else -> null
}

/**
 * The sync key of the subtitle on screen: the [selected] one, when the player
 * has it mounted. A legacy session mounts every row, so the first mounted key
 * is not necessarily the one showing.
 */
fun List<PlayerSubtitleInfo>.activeSyncKey(selected: SubtitleIdentity?, mounted: List<PlayerSubtitleInfo>): String? =
    syncKeyFor(selected)?.takeIf(mounted::includesSyncKey)

/**
 * True when [rows] include the subtitle named by [key]: by its inventory sync
 * key, or, for a row from a server that publishes none, by its stored ID.
 */
fun List<PlayerSubtitleInfo>.includesSyncKey(key: String): Boolean = any { row ->
    row.syncKey == key ||
        (row.syncKey == null && row.storedSubtitleId()?.let(SubtitleSyncState::storedKey) == key)
}

/**
 * Tracks the timing and latest sync job of the playing file's syncable
 * subtitles (stored ones and subtitle files next to the media), keyed by the
 * sync key the playback inventory publishes. It loads them, follows running
 * jobs through realtime updates with polling as the fallback, and performs
 * "Sync to audio" and "Reset timing".
 *
 * [onTimingChanged] fires once for each timing change it observes after a
 * subtitle's first read, so the player can fetch that track's cues again.
 * Results that land after the file changed are dropped; the repository drops
 * them after an account or profile change.
 */
class SubtitleSyncController(
    private val repository: SubtitleSyncSource,
    private val scope: CoroutineScope,
    private val onTimingChanged: (String) -> Unit = {},
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    private val pollLimitMs: Long = POLL_LIMIT_MS,
) {
    private val _state = MutableStateFlow(SubtitleSyncUiState())
    val state: StateFlow<SubtitleSyncUiState> = _state.asStateFlow()

    private var mediaFileId: Int? = null
    private var syncKeys: Set<String> = emptySet()
    private var generation = 0L
    private var loadJob: Job? = null
    private var pollJob: Job? = null

    /** The timing each subtitle's cues were last loaded with, as far as this controller knows. */
    private val loadedTiming = mutableMapOf<String, SubtitleTiming>()
    /** Subtitles a realtime event said were retimed, whose next read must reload cues. */
    private val reloadOnNextRead = mutableSetOf<String>()
    /** Subtitles a realtime update reported on since the last poll; the next poll skips them. */
    private val pushedSincePoll = mutableSetOf<String>()
    /** How long each running job has been polled. */
    private val polledMs = mutableMapOf<String, Long>()
    /** Numbers every read as it is sent and every realtime update as it arrives. */
    private var sequence = 0L
    /** The number of the read or update each subtitle's entry shows. */
    private val shownSequence = mutableMapOf<String, Long>()

    /**
     * Follows the playing file and its inventory's sync keys. A new file (or
     * none) drops everything in flight; a key the inventory gains re-reads the
     * file's subtitles.
     */
    fun bind(mediaFileId: Int?, syncKeys: Set<String>) {
        if (mediaFileId != this.mediaFileId) {
            this.mediaFileId = mediaFileId
            this.syncKeys = syncKeys
            generation++
            loadJob?.cancel()
            pollJob?.cancel()
            loadedTiming.clear()
            reloadOnNextRead.clear()
            pushedSincePoll.clear()
            polledMs.clear()
            shownSequence.clear()
            _state.value = SubtitleSyncUiState()
            if (mediaFileId != null && syncKeys.isNotEmpty()) reload()
            return
        }
        val gained = syncKeys.any { it !in this.syncKeys && it !in _state.value.entries }
        this.syncKeys = syncKeys
        if (gained) reload()
    }

    /** Re-reads the capability and the file's syncable subtitles, re-arming expired polls. */
    fun reload() {
        val fileId = mediaFileId ?: return
        if (syncKeys.isEmpty() && _state.value.entries.isEmpty()) return
        val owner = generation
        polledMs.clear()
        _state.update { current ->
            current.copy(entries = current.entries.mapValues { (_, entry) -> entry.copy(pollExpired = false) })
        }
        loadJob?.cancel()
        loadJob = scope.launch {
            val capability = (repository.syncCapability() as? ApiResult.Success)?.data
            val sent = ++sequence
            val listed = (repository.listSync(fileId) as? ApiResult.Success)?.data
            if (owner != generation) return@launch
            // A probe that failed keeps what the last one said.
            if (capability != null) {
                _state.update {
                    it.copy(available = capability.available, externalAvailable = capability.available && capability.external)
                }
            }
            listed?.forEach { observe(it, sent) }
            startPolling()
        }
    }

    /** Records a subtitle returned by a download or upload, following its automatic sync. */
    fun remember(subtitle: DownloadedSubtitle) {
        if (subtitle.mediaFileId != mediaFileId) return
        val state = subtitle.syncState()
        polledMs.remove(state.key)
        observe(state, ++sequence, watch = state.sync?.takeIf { it.inProgress }?.id)
        startPolling()
    }

    /**
     * The server announced new timing for [key] (realtime): re-read it, and
     * reload its cues unless a realtime update already brought that timing.
     */
    fun timingChanged(key: String) {
        val fileId = mediaFileId ?: return
        val owner = generation
        reloadOnNextRead += key
        scope.launch {
            val sent = ++sequence
            val read = repository.readSync(fileId, key)
            if (owner != generation) return@launch
            when (read) {
                is ApiResult.Success -> {
                    observe(read.data, sent)
                    startPolling()
                }
                // The event is authoritative enough to reload on its own.
                else -> if (reloadOnNextRead.remove(key)) onTimingChanged(key)
            }
        }
    }

    /** The server reported a step of a sync job (realtime). */
    fun syncUpdated(update: PlaybackSubtitleSyncUpdated) {
        if (update.mediaFileId != null && update.mediaFileId != mediaFileId) return
        val entry = _state.value.entries[update.syncKey]
        if (entry == null) {
            // Not loaded yet (a track the inventory just gained): read them all.
            reload()
            return
        }
        pushedSincePoll += update.syncKey
        observe(entry.state.copy(timing = update.timing, sync = update.job), ++sequence)
        startPolling()
    }

    fun requestSync(key: String) {
        val fileId = mediaFileId ?: return
        if (_state.value.entries[key]?.busy != false) return
        val owner = generation
        polledMs.remove(key)
        patch(key) { it.copy(busy = true, error = null, pollExpired = false) }
        scope.launch {
            val sent = ++sequence
            val result = repository.startSync(fileId, key)
            if (owner != generation) return@launch
            when (result) {
                is ApiResult.Success -> {
                    observe(result.data, sent, watch = result.data.sync?.id)
                    patch(key) { it.copy(busy = false) }
                    startPolling()
                }
                else -> fail(key, result, "Sync failed")
            }
        }
    }

    fun resetTiming(key: String) {
        val fileId = mediaFileId ?: return
        if (_state.value.entries[key]?.busy != false) return
        val owner = generation
        patch(key) { it.copy(busy = true, error = null) }
        scope.launch {
            val sent = ++sequence
            val result = repository.resetTiming(fileId, key)
            if (owner != generation) return@launch
            when (result) {
                is ApiResult.Success -> {
                    observe(result.data, sent)
                    patch(key) { it.copy(busy = false) }
                }
                else -> fail(key, result, "Couldn't reset timing")
            }
        }
    }

    private fun fail(key: String, result: ApiResult<*>, fallback: String) {
        val error = result as? ApiResult.Error
        patch(key) { entry ->
            when (error?.code) {
                403 -> entry.copy(busy = false, forbidden = true, error = null)
                422 -> entry.copy(busy = false, unsupported = true, error = "This format can't be synced.")
                412 -> entry.copy(busy = false, error = "The subtitle changed. Try again.")
                else -> entry.copy(busy = false, error = error?.message?.takeIf { it.isNotBlank() } ?: fallback)
            }
        }
    }

    /**
     * Records a server view of a subtitle from the read sent, or the update
     * received, as number [sent]; [watch] marks a job this viewer started.
     */
    private fun observe(state: SubtitleSyncState, sent: Long, watch: String? = null) {
        val key = state.key
        val known = _state.value.entries[key]
        if (known != null && isStale(state.sync, sent, known)) {
            // A response that arrived after a newer one: keep what the newer
            // one said, but still follow the job if this viewer started it.
            if (watch != null && watch == known.state.sync?.id) patch(key) { it.copy(watchedJobId = watch) }
            return
        }
        shownSequence[key] = sent
        val previous = loadedTiming.put(key, state.timing)
        val forced = reloadOnNextRead.remove(key)
        val inProgress = state.sync?.inProgress == true
        if (!inProgress) polledMs.remove(key)
        _state.update { current ->
            val entry = current.entries[key] ?: SubtitleSyncEntry(state)
            current.copy(
                entries = current.entries + (key to entry.copy(
                    state = state,
                    pollExpired = inProgress && entry.pollExpired,
                    watchedJobId = watch ?: entry.watchedJobId,
                )),
            )
        }
        // A realtime event about a subtitle never read before reloads it; a
        // known one reloads only when its timing really moved, so the event
        // that follows a realtime update carrying the new timing is a no-op.
        if (if (previous == null) forced else previous != state.timing) onTimingChanged(key)
    }

    /**
     * True when a view reporting [incoming], from read or update number [sent],
     * is older than [known]. The server does not sequence its responses and
     * realtime updates, so a response can arrive after a newer one. A view of
     * an older job is stale. So is one from a read sent before [known] arrived,
     * unless it reports a job [known] has not reached, which the server can only
     * have produced later.
     */
    private fun isStale(incoming: SubtitleSyncJob?, sent: Long, known: SubtitleSyncEntry): Boolean {
        val current = known.state.sync
        if (isOlderJob(incoming, current)) return true
        val sentBefore = sent < (shownSequence[known.state.key] ?: 0L)
        val laterJob = incoming != null && (current == null || isOlderJob(current, incoming))
        return sentBefore && !laterJob
    }

    /**
     * True when [incoming] describes an earlier point than [current]: an older
     * job, or the same job further back (running after it finished, or at
     * less progress).
     */
    private fun isOlderJob(incoming: SubtitleSyncJob?, current: SubtitleSyncJob?): Boolean {
        if (incoming == null || current == null) return false
        if (incoming.id != current.id) {
            return incoming.createdAt.isNotEmpty() && current.createdAt.isNotEmpty() && incoming.createdAt < current.createdAt
        }
        return jobStep(incoming) < jobStep(current)
    }

    /** How far a job has got: its progress while active, past every progress once finished. */
    private fun jobStep(job: SubtitleSyncJob): Double = if (job.inProgress) job.progress ?: 0.0 else 2.0

    private fun patch(key: String, change: (SubtitleSyncEntry) -> SubtitleSyncEntry) {
        _state.update { current ->
            val entry = current.entries[key] ?: return@update current
            current.copy(entries = current.entries + (key to change(entry)))
        }
    }

    /**
     * Polls every running job until it ends, its limit passes, or the file
     * changes. Realtime updates usually arrive first; a subtitle that had one
     * since the last poll skips it.
     */
    private fun startPolling() {
        if (pollJob?.isActive == true) return
        val fileId = mediaFileId ?: return
        val owner = generation
        pollJob = scope.launch {
            while (owner == generation) {
                if (pollable().isEmpty()) return@launch
                delay(pollIntervalMs)
                if (owner != generation) return@launch
                for (key in pollable()) {
                    val polled = (polledMs[key] ?: 0L) + pollIntervalMs
                    polledMs[key] = polled
                    if (polled > pollLimitMs) {
                        polledMs.remove(key)
                        patch(key) { it.copy(pollExpired = true) }
                        continue
                    }
                    if (pushedSincePoll.remove(key)) continue
                    val sent = ++sequence
                    when (val read = repository.readSync(fileId, key)) {
                        is ApiResult.Success -> if (owner == generation) observe(read.data, sent)
                        // A subtitle the server stops answering for (deleted,
                        // access lost) is not polled again until a reload.
                        is ApiResult.Error -> if (owner == generation) patch(key) { it.copy(pollExpired = true) }
                        is ApiResult.NetworkError -> Unit
                    }
                }
            }
        }
    }

    private fun pollable(): List<String> =
        _state.value.entries.values.filter { it.inProgress && !it.pollExpired }.map { it.state.key }

    companion object {
        const val POLL_INTERVAL_MS = 3_000L
        const val POLL_LIMIT_MS = 5 * 60_000L
    }
}
