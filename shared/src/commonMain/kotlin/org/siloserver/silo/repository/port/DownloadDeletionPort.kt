package org.siloserver.silo.repository.port

/**
 * Durable record of a download the user deleted but whose **server** download
 * record may not yet be gone (Track B, offline-first delete). A download delete
 * removes the on-device bytes + Room metadata immediately and enqueues a tombstone
 * here; the server `DELETE /downloads/{id}` is replayed on the next online refresh
 * and the tombstone is cleared only once the server's list confirms the record is
 * gone. Until then [DownloadsRepository][org.siloserver.silo.repository.DownloadsRepository]
 * filters the recordId out of every refresh so a still-present server record can't
 * resurrect as a "ghost" pointing at deleted files.
 *
 * commonMain port (so the shared repository can depend on it) with a Room-backed
 * Android impl bound in the platform module; default no-op keeps non-Android /
 * tests network-only (delete stays server-first there).
 *
 * Keyed by `(serverId, profileId, recordId)` — not recordId alone — so clearing a
 * tombstone after one scope's list confirms absence can never drop another scope's
 * pending delete (recordIds are server-unique, but the *clear* decision is
 * scope-local). [mediaFileId] is carried so a crash between enqueue and on-device
 * byte deletion can be finished idempotently on the next launch.
 */
interface DownloadDeletionPort {
    suspend fun enqueue(serverId: String, profileId: String, recordId: String, mediaFileId: Int?) {}

    /** Every pending tombstone's recordId, across all scopes — used to filter ghosts
     *  out of any refresh (recordIds are globally unique, so this is always safe). */
    suspend fun allPendingRecordIds(): Set<String> = emptySet()

    /** Pending tombstones for one scope — used for scope-local reconcile + clear. */
    suspend fun pendingForScope(serverId: String, profileId: String): List<PendingDownloadDeletion> = emptyList()

    suspend fun remove(serverId: String, profileId: String, recordId: String) {}
}

/** A queued download-record deletion awaiting server reconciliation. */
data class PendingDownloadDeletion(
    val serverId: String,
    val profileId: String,
    val recordId: String,
    val mediaFileId: Int?,
    val loginId: String? = null,
    val origin: String? = null,
    val deviceId: String? = null,
)

/** Network-only default: no durable tombstones (delete stays server-first). */
object NoOpDownloadDeletionPort : DownloadDeletionPort
