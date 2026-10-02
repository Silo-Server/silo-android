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
import org.siloserver.silo.model.subtitles.subtitleSyncStatusLabel
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.SubtitlesRepository

/** What the player knows about one stored subtitle's timing and sync. */
data class StoredSubtitleSyncEntry(
    val subtitle: DownloadedSubtitle,
    /** A sync or reset request is on the wire. */
    val busy: Boolean = false,
    /** The server refused to let this viewer retime the subtitle (403). */
    val forbidden: Boolean = false,
    /** The subtitle's format cannot be synced (422). */
    val unsupported: Boolean = false,
    /** The last action's failure, in plain words. */
    val error: String? = null,
) {
    val statusLabel: String? get() = subtitleSyncStatusLabel(subtitle.timing, subtitle.sync)
    val inProgress: Boolean get() = subtitle.sync?.inProgress == true
}

data class StoredSubtitleSyncState(
    /** The server can align subtitles to audio for this viewer. */
    val available: Boolean = false,
    val entries: Map<Int, StoredSubtitleSyncEntry> = emptyMap(),
)

/**
 * The timing actions the player offers for its selected stored subtitle, or
 * null when it has none to offer. Retiming changes the subtitle for everyone
 * watching the file, so the server allows it only for the account that added
 * it or an administrator.
 */
data class StoredSubtitleTimingActions(
    val subtitleId: Int,
    val statusLabel: String?,
    val canSync: Boolean,
    val canReset: Boolean,
    val inProgress: Boolean,
    val busy: Boolean,
    val forbidden: Boolean,
    val error: String?,
) {
    val actionsEnabled: Boolean get() = !busy && !inProgress

    companion object {
        const val FORBIDDEN_MESSAGE = "Only the person who added this subtitle or an admin can change its timing."
    }
}

/** The stored subtitle a sidecar selection plays, or null for any other kind of track. */
fun List<PlayerSubtitleInfo>.storedSubtitleIdFor(identity: SubtitleIdentity): Int? = when (identity) {
    is SubtitleIdentity.ServerSidecar -> firstOrNull { it.index == identity.serverIndex }?.storedSubtitleId()
    is SubtitleIdentity.Downloaded -> identity.downloadId
    else -> null
}

fun StoredSubtitleSyncState.timingActionsFor(subtitleId: Int?): StoredSubtitleTimingActions? {
    val entry = subtitleId?.let(entries::get) ?: return null
    val canSync = available && !entry.unsupported
    val canReset = !entry.subtitle.timing.isIdentity
    if (!entry.forbidden && !canSync && !canReset && entry.error == null) return null
    return StoredSubtitleTimingActions(
        subtitleId = entry.subtitle.id,
        statusLabel = entry.statusLabel,
        canSync = canSync,
        canReset = canReset,
        inProgress = entry.inProgress,
        busy = entry.busy,
        forbidden = entry.forbidden,
        error = entry.error,
    )
}

/**
 * Tracks the timing and latest sync job of the playing file's stored
 * subtitles: loads them, polls running jobs, and performs "Sync subtitle" and
 * "Reset timing". Results that land after the file changed are dropped; the
 * repository drops them after an account or profile change. Cue reloads stay
 * with the realtime `subtitle_timing_changed` handler.
 */
class StoredSubtitleSyncController(
    private val repository: SubtitlesRepository,
    private val scope: CoroutineScope,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    private val pollLimitMs: Long = POLL_LIMIT_MS,
) {
    private val _state = MutableStateFlow(StoredSubtitleSyncState())
    val state: StateFlow<StoredSubtitleSyncState> = _state.asStateFlow()

    private var mediaFileId: Int? = null
    private var generation = 0L
    private var loadJob: Job? = null
    private var pollJob: Job? = null

    /** Follows the playing file; a new file (or none) drops everything in flight. */
    fun bind(mediaFileId: Int?) {
        if (mediaFileId == this.mediaFileId) return
        this.mediaFileId = mediaFileId
        generation++
        loadJob?.cancel()
        pollJob?.cancel()
        _state.value = StoredSubtitleSyncState()
        if (mediaFileId != null) reload()
    }

    /** Re-reads the capability and the file's stored subtitles, re-arming polling. */
    fun reload() {
        val fileId = mediaFileId ?: return
        val owner = generation
        loadJob?.cancel()
        loadJob = scope.launch {
            val available = (repository.syncCapability() as? ApiResult.Success)?.data?.available == true
            val listed = (repository.list(fileId) as? ApiResult.Success)?.data?.subtitles
            if (owner != generation) return@launch
            _state.update { current ->
                current.copy(
                    available = available,
                    entries = listed?.associate { subtitle ->
                        subtitle.id to (current.entries[subtitle.id]?.copy(subtitle = subtitle)
                            ?: StoredSubtitleSyncEntry(subtitle))
                    } ?: current.entries,
                )
            }
            startPolling()
        }
    }

    /** The server announced new timing for [subtitleId] (realtime); refresh its status. */
    fun timingChanged(subtitleId: Int) {
        val fileId = mediaFileId ?: return
        if (subtitleId !in _state.value.entries) return
        val owner = generation
        scope.launch {
            val read = repository.readSync(subtitleId, fileId)
            if (owner == generation && read is ApiResult.Success) observe(read.data)
        }
    }

    fun requestSync(subtitleId: Int) {
        val fileId = mediaFileId ?: return
        if (_state.value.entries[subtitleId]?.busy != false) return
        val owner = generation
        patch(subtitleId) { it.copy(busy = true, error = null) }
        scope.launch {
            val result = repository.requestSync(subtitleId)
            if (owner != generation) return@launch
            when (result) {
                is ApiResult.Success -> {
                    patch(subtitleId) { it.copy(busy = false, subtitle = it.subtitle.copy(sync = result.data)) }
                    startPolling()
                }
                else -> fail(subtitleId, result, "Sync failed")
            }
            // The job may already be done; read it once so a fast result shows.
            if (result is ApiResult.Success && !result.data.inProgress) {
                (repository.readSync(subtitleId, fileId) as? ApiResult.Success)?.data
                    ?.takeIf { owner == generation }
                    ?.let(::observe)
            }
        }
    }

    fun resetTiming(subtitleId: Int) {
        val fileId = mediaFileId ?: return
        if (_state.value.entries[subtitleId]?.busy != false) return
        val owner = generation
        patch(subtitleId) { it.copy(busy = true, error = null) }
        scope.launch {
            val result = repository.resetTiming(subtitleId, fileId)
            if (owner != generation) return@launch
            when (result) {
                is ApiResult.Success -> {
                    observe(result.data)
                    patch(subtitleId) { it.copy(busy = false) }
                }
                else -> fail(subtitleId, result, "Couldn't reset timing")
            }
        }
    }

    private fun fail(subtitleId: Int, result: ApiResult<*>, fallback: String) {
        val error = result as? ApiResult.Error
        patch(subtitleId) { entry ->
            when (error?.code) {
                403 -> entry.copy(busy = false, forbidden = true, error = null)
                422 -> entry.copy(busy = false, unsupported = true, error = "This format can't be synced.")
                412 -> entry.copy(busy = false, error = "The subtitle changed. Try again.")
                else -> entry.copy(busy = false, error = error?.message?.takeIf { it.isNotBlank() } ?: fallback)
            }
        }
    }

    private fun observe(subtitle: DownloadedSubtitle) {
        patch(subtitle.id) { it.copy(subtitle = subtitle) }
    }

    private fun patch(subtitleId: Int, change: (StoredSubtitleSyncEntry) -> StoredSubtitleSyncEntry) {
        _state.update { current ->
            val entry = current.entries[subtitleId] ?: return@update current
            current.copy(entries = current.entries + (subtitleId to change(entry)))
        }
    }

    /** Polls every running job until it ends, the limit passes, or the file changes. */
    private fun startPolling() {
        if (pollJob?.isActive == true) return
        val fileId = mediaFileId ?: return
        val owner = generation
        pollJob = scope.launch {
            var waited = 0L
            // A subtitle the server stops answering for (deleted, access lost)
            // is not polled again until the next reload.
            val unreadable = mutableSetOf<Int>()
            while (owner == generation && waited < pollLimitMs) {
                val running = _state.value.entries.values
                    .filter { it.inProgress && it.subtitle.id !in unreadable }
                    .map { it.subtitle.id }
                if (running.isEmpty()) return@launch
                delay(pollIntervalMs)
                waited += pollIntervalMs
                for (id in running) {
                    when (val read = repository.readSync(id, fileId)) {
                        is ApiResult.Success -> if (owner == generation) observe(read.data)
                        is ApiResult.Error -> unreadable += id
                        is ApiResult.NetworkError -> Unit
                    }
                }
            }
        }
    }

    companion object {
        const val POLL_INTERVAL_MS = 3_000L
        const val POLL_LIMIT_MS = 5 * 60_000L
    }
}
