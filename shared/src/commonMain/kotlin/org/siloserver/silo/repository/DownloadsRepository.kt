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
    private val authorities: org.siloserver.silo.network.DurableLoginAuthorityProvider? = null,
    private val devices: org.siloserver.silo.network.DeviceMetadataProvider? = null,
    private val identityTransitions: org.siloserver.silo.network.IdentityTransitionBarrier? = null,
) {

    private val _records = MutableStateFlow<List<DownloadRecord>>(emptyList())
    val records: StateFlow<List<DownloadRecord>> = _records.asStateFlow()

    /** Cached download capability (issue #20 §3). Null until the first
     *  successful [refreshCapability]; the quality picker reads it to gate the
     *  presets it offers. */
    private val _capability = MutableStateFlow<DownloadCapability?>(null)
    val capability: StateFlow<DownloadCapability?> = _capability.asStateFlow()

    /** Fetch + cache the server's download capability. Best-effort: on failure
     *  the cache keeps its previous value (or null) and the picker falls back
     *  to an optimistic preset list. */
    suspend fun refreshCapability(): ApiResult<DownloadCapability> {
        val authority = authorities?.snapshotDurableLoginAuthority()
        if (!current(authority)) return changed()
        val result = api.capability()
        if (!current(authority)) return changed()
        if (result is ApiResult.Success && !localWrite(authority) { _capability.value = result.data }) return changed()
        return result
    }

    /** Ids the user has asked to delete but which the server still returns
     *  (active records that the server only marks cancelled on first DELETE).
     *  Cleared once the server actually drops the row from its list. In-memory,
     *  session-scoped; the durable cross-restart tombstone is [deletions] (used
     *  for offline-first delete — see [refresh] filtering + reconcile). */
    private val pendingDelete = mutableSetOf<String>()

    init {
        identityTransitions?.installGate { transition ->
            if (transition.phase == org.siloserver.silo.network.IdentityTransitionPhase.WILL_CHANGE) {
                _records.value = emptyList(); _capability.value = null; pendingDelete.clear()
            }
        }
    }

    private fun changed() = ApiResult.Error(0, "identity_changed", "The download account or profile changed.")
    private suspend fun current(authority: org.siloserver.silo.network.DurableLoginAuthority?): Boolean =
        authorities == null || (authority != null && authority == authorities.snapshotDurableLoginAuthority())
    private suspend fun localWrite(authority: org.siloserver.silo.network.DurableLoginAuthority?, block: suspend () -> Unit): Boolean {
        if (!current(authority)) return false
        if (authority == null) { block(); return true }
        return identityTransitions?.withCurrentGeneration(authority.scope.identityGeneration) { block(); true } == true
    }

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
    ): ApiResult<Unit> {
        val authority = authorities?.snapshotDurableLoginAuthority()
        if (!current(authority) || (authority != null &&
            ((serverId != null && serverId != authority.scope.serverId) || (profileId != null && profileId != authority.scope.profileId)))) return changed()
        return when (val result = api.list(authority?.scope)) {
            is ApiResult.Success -> {
                if (!current(authority)) return changed()
                val serverIds = result.data.downloads.map { it.id }.toSet()
                val durable = deletions.allPendingRecordIds()
                if (!localWrite(authority) {
                    val hidden = pendingDelete + durable
                    val serverList = result.data.downloads.filterNot { it.id in hidden }
                    _records.update { current -> serverList + current.filter { it.id !in serverIds && it.id !in hidden && it.id in keepIdsAbsentFromServer } }
                }) return changed()
                if (serverId != null && profileId != null) {
                    val deviceId = devices?.current()?.id
                    for (pending in deletions.pendingForScope(serverId, profileId)) {
                        if (!current(authority)) return changed()
                        // Old tombstones have no provable saved-login/device ownership. Keep them isolated.
                        if (authority != null && (pending.loginId != authority.loginId || pending.origin != authority.scope.serverUrl || pending.deviceId != deviceId)) continue
                        if (pending.recordId !in serverIds) {
                            localWrite(authority) { deletions.remove(serverId, profileId, pending.recordId); pendingDelete.remove(pending.recordId) }
                        } else serverDeleteConfirmedGone(pending.recordId, authority?.scope)
                    }
                }
                if (!localWrite(authority) { pendingDelete.removeAll((pendingDelete - durable) - serverIds) }) return changed()
                ApiResult.Success(Unit)
            }
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }

    /** Server creates a record; we upsert into the cache so the UI updates
     *  immediately without waiting for the next refresh. */
    suspend fun create(request: DownloadRequest, expectedAuthority: org.siloserver.silo.network.DurableLoginAuthority? = null): ApiResult<DownloadRecord> {
        val authority = expectedAuthority ?: authorities?.snapshotDurableLoginAuthority()
        if (!current(authority)) return changed()
        return when (val r = api.create(request, authority?.scope)) {
            is ApiResult.Success -> {
                if (!localWrite(authority) { upsertLocal(r.data) }) return changed()
                ApiResult.Success(r.data)
            }
            is ApiResult.Error -> ApiResult.Error(r.code, r.error, r.message)
            is ApiResult.NetworkError -> ApiResult.NetworkError(r.exception)
        }

    }

    /**
     * Series-batch creation (one POST → N records sharing a batchId). All
     * returned records are upserted so the Downloads tab + per-row UI
     * update immediately. Caller is responsible for enqueueing one worker
     * per record on the Android side.
     */
    suspend fun createBatch(request: DownloadRequest, expectedAuthority: org.siloserver.silo.network.DurableLoginAuthority? = null): ApiResult<org.siloserver.silo.model.download.DownloadsListResponse> {
        val authority = expectedAuthority ?: authorities?.snapshotDurableLoginAuthority()
        if (!current(authority)) return changed()
        return when (val r = api.createBatch(request, authority?.scope)) {
            is ApiResult.Success -> {
                if (!localWrite(authority) { r.data.downloads.forEach { upsertLocal(it) } }) return changed()
                ApiResult.Success(r.data)
            }
            is ApiResult.Error -> ApiResult.Error(r.code, r.error, r.message)
            is ApiResult.NetworkError -> ApiResult.NetworkError(r.exception)
        }

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
        val authority = authorities?.snapshotDurableLoginAuthority()
        if (!current(authority)) return changed()
        val first = api.delete(id, authority?.scope)
        if (first is ApiResult.Error && first.code == 404) {
            // Server doesn't know this record — confirmed gone; drop locally.
            if (!localWrite(authority) { markPendingDelete(id); _records.update { list -> list.filterNot { it.id == id } } }) return changed()
            return ApiResult.Success(Unit)
        }
        if (first !is ApiResult.Success) return first.mapToUnit()
        // Second DELETE: removes the (now-cancelled) row. 404 is fine — it
        // means the first DELETE already removed it. Anything else means
        // the row may still exist server-side, so leave the cache untouched.
        val second = api.delete(id, authority?.scope)
        if (second is ApiResult.Success || (second is ApiResult.Error && second.code == 404)) {
            if (!localWrite(authority) { markPendingDelete(id); _records.update { list -> list.filterNot { it.id == id } } }) return changed()
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
        val authority = authorities?.snapshotDurableLoginAuthority()
        check(current(authority)) { "The download owner changed" }
        deletions.enqueue(serverId, profileId, recordId, mediaFileId)
        check(localWrite(authority) {
            markPendingDelete(recordId)
            _records.update { list -> list.filterNot { it.id == recordId } }
        }) { "The download owner changed" }
    }

    /** Pending durable tombstones for a scope — lets the Android side finish an
     *  on-device cleanup interrupted by a crash (idempotent byte/metadata delete). */
    suspend fun pendingDeletionsForScope(serverId: String, profileId: String): List<org.siloserver.silo.repository.port.PendingDownloadDeletion> {
        val authority = authorities?.snapshotDurableLoginAuthority()
        if (!current(authority)) return emptyList()
        val device = devices?.current()?.id
        val pending = deletions.pendingForScope(serverId, profileId)
        if (!current(authority)) return emptyList()
        return pending.filter { authority == null || (authority.scope.serverId == serverId &&
            authority.scope.profileId == profileId && it.loginId == authority.loginId &&
            it.origin == authority.scope.serverUrl && it.deviceId == device) }
    }

    /** The server's two-phase DELETE, returning true iff the record is confirmed
     *  gone (first-call 404, or success/404 on the second). Used by [refresh]'s
     *  reconcile to replay an offline delete without touching cache state. */
    private suspend fun serverDeleteConfirmedGone(id: String, scope: org.siloserver.silo.network.AuthScopeSnapshot?): Boolean {
        val first = api.delete(id, scope)
        if (first is ApiResult.Error && first.code == 404) return true
        if (first !is ApiResult.Success) return false
        val second = api.delete(id, scope)
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
