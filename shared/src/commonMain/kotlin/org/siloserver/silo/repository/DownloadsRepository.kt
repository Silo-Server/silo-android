package org.siloserver.silo.repository

import org.siloserver.silo.model.download.DownloadCapability
import org.siloserver.silo.model.download.DownloadRecord
import org.siloserver.silo.model.download.DownloadRequest
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.DownloadsApi
import org.siloserver.silo.repository.port.DownloadDeletionPort
import org.siloserver.silo.repository.port.NoOpDownloadDeletionPort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Mirrors the server's download list into a [StateFlow] so the Downloads
 * screen, item-detail download button, and the WorkManager-driven download
 * worker can all observe the same state without polling the API
 * independently.
 *
 * **Disk-as-truth (v1.1).** The Android side seeds this cache from the
 * on-disk sidecar files at startup via [seedFromSidecars]. [refresh] then
 * *merges* the server view into that cache instead of overwriting it —
 * server-known records win on conflict (fresher status), but records that
 * only exist on disk (server cleaned them up while we were offline, or we
 * marked them [pendingDelete] but haven't synced yet) stay visible.
 *
 * **Two-phase delete.** The server has a quirky DELETE: for active records
 * (queued / downloading) it only flips status to cancelled rather than
 * removing the row; you have to DELETE again once it's cancelled to
 * actually remove it. Until then [refresh] would faithfully re-add the
 * ghost. We track [pendingDelete] ids and filter them out of [refresh]'s
 * merge so a deleted-by-the-user record stays gone even if it bounces
 * back from the server.
 */
class DownloadsRepository(
    private val api: DownloadsApi,
    private val deletions: DownloadDeletionPort = NoOpDownloadDeletionPort,
) {

    private val _records = MutableStateFlow<List<DownloadRecord>>(emptyList())
    val records: StateFlow<List<DownloadRecord>> = _records.asStateFlow()

    /** Server-advertised download capability. Null until [refreshCapability]
     *  succeeds — callers treat null as original-only (older server / offline). */
    private val _capability = MutableStateFlow<DownloadCapability?>(null)
    val capability: StateFlow<DownloadCapability?> = _capability.asStateFlow()

    /** Fetch GET /downloads/capability into [capability]. Failures (404 on
     *  older servers, offline) leave the previous value in place. */
    suspend fun refreshCapability() {
        when (val r = api.capability()) {
            is ApiResult.Success -> _capability.value = r.data
            else -> { /* original-only fallback (older server / offline) */ }
        }
    }

    /** Ids the user has asked to delete but which the server still returns
     *  (active records that the server only marks cancelled on first DELETE).
     *  Cleared once the server actually drops the row from its list. In-memory,
     *  session-scoped; the durable cross-restart tombstone is [deletions] (used
     *  for offline-first delete — see [refresh] filtering + reconcile). */
    private val pendingDelete = mutableSetOf<String>()

    /**
     * Seed the in-memory cache from sidecars on disk. Idempotent: running
     * twice is harmless. Called by the Android `DownloadsViewModel` at app
     * start so an offline launch shows the Downloads tab populated before
     * any network call.
     */
    fun seedFromSidecars(records: List<DownloadRecord>) {
        if (records.isEmpty()) return
        _records.update { current ->
            val byId = current.associateBy { it.id }.toMutableMap()
            for (rec in records) {
                if (rec.id in pendingDelete) continue
                if (byId[rec.id] == null) byId[rec.id] = rec
            }
            byId.values.toList()
        }
    }

    /**
     * Merge the server's view into the cache. Server-side records win on
     * conflict (their status is fresher). Records absent from the server
     * response are kept *only* if there's still bytes on disk for them
     * (the caller passes [keepIdsAbsentFromServer] sourced from the disk
     * walk); otherwise they're dropped as truly-removed. Records the user
     * deleted — both this session's [pendingDelete] and the durable
     * cross-restart tombstones in [deletions] — are filtered out of the merge.
     *
     * When [serverId]/[profileId] are supplied (by the Downloads tab for the
     * active scope), this also reconciles that scope's durable tombstones:
     * any still-present server record gets its `DELETE` replayed, and a
     * tombstone is dropped once the server's list confirms the record is gone
     * (never on a possibly-bouncing 2xx). recordIds are server-unique, so the
     * *filter* is global, but the *clear* is scope-local — clearing one scope's
     * tombstone can never resurrect another scope's still-pending delete.
     */
    suspend fun refresh(
        keepIdsAbsentFromServer: Set<String> = emptySet(),
        serverId: String? = null,
        profileId: String? = null,
    ): ApiResult<Unit> = when (val r = api.list()) {
        is ApiResult.Success -> {
            val serverIds = r.data.downloads.map { it.id }.toSet()
            val durableTombstones = deletions.allPendingRecordIds()
            val tombstoned = pendingDelete + durableTombstones
            // Server's view, minus anything the user deleted locally.
            val serverList = r.data.downloads.filterNot { it.id in tombstoned }
            _records.update { current ->
                // Local records the server no longer reports — keep only the
                // ones the caller proved still have bytes on disk.
                val localKeepers = current.filter { rec ->
                    rec.id !in serverIds &&
                        rec.id !in tombstoned &&
                        rec.id in keepIdsAbsentFromServer
                }
                serverList + localKeepers
            }
            // Scope-local reconcile of durable (offline) tombstones.
            if (serverId != null && profileId != null) {
                for (p in deletions.pendingForScope(serverId, profileId)) {
                    if (p.recordId !in serverIds) {
                        // Server's list confirms it's gone — drop the tombstone.
                        deletions.remove(serverId, profileId, p.recordId)
                        pendingDelete.remove(p.recordId)
                    } else {
                        // Still present — replay the server delete; the tombstone
                        // clears on the next refresh once the list confirms absence.
                        serverDeleteConfirmedGone(p.recordId)
                    }
                }
            }
            // In-memory-only entries (this session's online deletes) the server
            // already dropped. Safe to clear regardless of scope: a
            // server-deleted record is absent from every scope's list. Durable
            // (offline) tombstones are excluded here — they're handled above.
            val inMemoryOnlyGone = (pendingDelete - durableTombstones) - serverIds
            if (inMemoryOnlyGone.isNotEmpty()) pendingDelete.removeAll(inMemoryOnlyGone)
            ApiResult.Success(Unit)
        }
        is ApiResult.Error -> ApiResult.Error(r.code, r.error, r.message)
        is ApiResult.NetworkError -> ApiResult.NetworkError(r.exception)
    }

    /** Server creates a record; we upsert into the cache so the UI updates
     *  immediately without waiting for the next refresh. */
    suspend fun create(request: DownloadRequest): ApiResult<DownloadRecord> =
        when (val r = api.create(request)) {
            is ApiResult.Success -> {
                upsertLocal(r.data)
                ApiResult.Success(r.data)
            }
            is ApiResult.Error -> ApiResult.Error(r.code, r.error, r.message)
            is ApiResult.NetworkError -> ApiResult.NetworkError(r.exception)
        }

    /**
     * Series-batch creation (one POST → N records sharing a batchId). All
     * returned records are upserted so the Downloads tab + per-row UI
     * update immediately. Caller is responsible for enqueueing one worker
     * per record on the Android side.
     */
    suspend fun createBatch(request: DownloadRequest): ApiResult<List<DownloadRecord>> =
        when (val r = api.createBatch(request)) {
            is ApiResult.Success -> {
                r.data.downloads.forEach { upsertLocal(it) }
                ApiResult.Success(r.data.downloads)
            }
            is ApiResult.Error -> ApiResult.Error(r.code, r.error, r.message)
            is ApiResult.NetworkError -> ApiResult.NetworkError(r.exception)
        }

    /**
     * Two-phase delete to handle the server's "cancel-then-delete" semantics
     * for active records (see class kdoc). The cache row is dropped only
     * once the *full* sequence confirms the removal — a 404 on the first
     * call (server never had / already removed the record, the local-only
     * case), or a success-or-404 on the second call that actually removes
     * the row. On failure of either call the row stays and is NOT marked
     * pending-delete, so the caller keeps the local bytes and surfaces the
     * error instead of orphaning them: [pendingDelete] is in-memory only,
     * and a restart would otherwise merge the server record back pointing
     * at deleted files. Calls DELETE up to twice — the second call is what
     * actually removes a previously-active record.
     */
    suspend fun delete(id: String): ApiResult<Unit> {
        // First DELETE: may only cancel if record was active.
        val first = api.delete(id)
        if (first is ApiResult.Error && first.code == 404) {
            // Server doesn't know this record — confirmed gone; drop locally.
            markPendingDelete(id)
            _records.update { list -> list.filterNot { it.id == id } }
            return ApiResult.Success(Unit)
        }
        if (first !is ApiResult.Success) return first.mapToUnit()
        // Second DELETE: removes the (now-cancelled) row. 404 is fine — it
        // means the first DELETE already removed it. Anything else means
        // the row may still exist server-side, so leave the cache untouched.
        val second = api.delete(id)
        if (second is ApiResult.Success || (second is ApiResult.Error && second.code == 404)) {
            markPendingDelete(id)
            _records.update { list -> list.filterNot { it.id == id } }
            return ApiResult.Success(Unit)
        }
        return second.mapToUnit()
    }

    /**
     * Offline-first delete (Track B). Records a durable tombstone and drops the
     * record from the cache so the deleted download disappears immediately and
     * stays gone across restarts; the on-device bytes + Room metadata are removed
     * by the Android caller. The server `DELETE` is replayed by [refresh]'s
     * scope-local reconcile when back online. The tombstone is written BEFORE the
     * caller deletes bytes so a crash can't lose the server-delete intent.
     */
    suspend fun enqueueDurableDelete(serverId: String, profileId: String, recordId: String, mediaFileId: Int?) {
        deletions.enqueue(serverId, profileId, recordId, mediaFileId)
        markPendingDelete(recordId)
        _records.update { list -> list.filterNot { it.id == recordId } }
    }

    /** Pending durable tombstones for a scope — lets the Android side finish an
     *  on-device cleanup interrupted by a crash (idempotent byte/metadata delete). */
    suspend fun pendingDeletionsForScope(serverId: String, profileId: String) =
        deletions.pendingForScope(serverId, profileId)

    /** The server's two-phase DELETE, returning true iff the record is confirmed
     *  gone (first-call 404, or success/404 on the second). Used by [refresh]'s
     *  reconcile to replay an offline delete without touching cache state. */
    private suspend fun serverDeleteConfirmedGone(id: String): Boolean {
        val first = api.delete(id)
        if (first is ApiResult.Error && first.code == 404) return true
        if (first !is ApiResult.Success) return false
        val second = api.delete(id)
        return second is ApiResult.Success || (second is ApiResult.Error && second.code == 404)
    }

    private fun ApiResult<*>.mapToUnit(): ApiResult<Unit> = when (this) {
        is ApiResult.Success -> ApiResult.Success(Unit)
        is ApiResult.Error -> ApiResult.Error(code, error, message)
        is ApiResult.NetworkError -> ApiResult.NetworkError(exception)
    }

    /** Mark an id as user-deleted so future refreshes filter it out, even
     *  if the server still reports it (active records). Public so the
     *  Android ViewModel can call it from the delete-local-only branch. */
    fun markPendingDelete(id: String) {
        pendingDelete.add(id)
    }

    /**
     * Used by [org.siloserver.silo.network.api.DownloadsApi.create] and by the
     * Android-side `DownloadWorker` to publish progress (`bytesSent`,
     * `status` transitions) without an extra `GET /downloads` roundtrip.
     */
    fun upsertLocal(record: DownloadRecord) {
        if (record.id in pendingDelete) return
        _records.update { list ->
            val replaced = list.map { if (it.id == record.id) record else it }
            if (replaced.any { it.id == record.id }) replaced else replaced + record
        }
    }

    /** Lookup helper for callers (item detail) that key off `FileVersion.fileId`. */
    fun recordForFile(fileId: Int): DownloadRecord? =
        _records.value.firstOrNull { it.mediaFileId == fileId }
}
