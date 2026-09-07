package org.siloserver.silo.common.downloads

import androidx.room.withTransaction
import org.siloserver.silo.common.data.db.SiloDatabase
import org.siloserver.silo.model.download.DownloadSidecar

/**
 * Room-backed download metadata (Track B) — replaces the on-disk `.record.json`
 * sidecar tree. Holds the full local picture of every download (so the Downloads
 * tab + offline player resolve without the network). The actual media bytes stay
 * on disk/MediaStore via [DownloadStorage]; this is purely the metadata.
 *
 * Method names mirror the old sidecar API so callers changed only sync→suspend.
 */
class DownloadMetadataStore(private val db: SiloDatabase) {

    private val downloadDao = db.downloadDao()

    suspend fun writeSidecar(serverId: String, profileId: String, sidecar: DownloadSidecar) {
        downloadDao.upsert(sidecar.toEntity(serverId, profileId))
    }

    suspend fun readSidecar(serverId: String, profileId: String, fileId: Int): DownloadSidecar? =
        downloadDao.get(serverId, profileId, fileId)?.toSidecar()

    suspend fun deleteSidecar(serverId: String, profileId: String, fileId: Int) {
        downloadDao.delete(serverId, profileId, fileId)
    }

    /** A file slot may now contain a replacement download; an old tombstone cannot own it. */
    suspend fun completePendingDeletion(
        serverId: String,
        profileId: String,
        fileId: Int,
        recordId: String,
        deleteBytes: suspend () -> Boolean,
    ): Boolean = db.withTransaction {
        val row = downloadDao.get(serverId, profileId, fileId) ?: return@withTransaction false
        if (row.recordId != recordId) return@withTransaction false
        // Keep the identity check and cleanup together: a replacement metadata
        // write must not race between the check and removal of the file slot.
        if (!deleteBytes()) return@withTransaction false
        downloadDao.delete(serverId, profileId, fileId)
        true
    }

    suspend fun listSidecars(serverId: String, profileId: String): List<DownloadSidecar> =
        downloadDao.getAll(serverId, profileId).map { it.toSidecar() }

    suspend fun listAllSidecars(): List<DownloadSidecar> =
        downloadDao.getAllAcrossScopes().map { it.toSidecar() }

    /** `(serverId, profileId, sidecar)` for every download across all scopes. */
    suspend fun listAllSidecarsWithScope(): List<Triple<String, String, DownloadSidecar>> =
        downloadDao.getAllAcrossScopes().map { Triple(it.serverId, it.profileId, it.toSidecar()) }

    suspend fun findSidecarByContentId(contentId: String): DownloadSidecar? =
        downloadDao.findByContentId(contentId)?.toSidecar()

    /** Scope-agnostic lookup by file id (delete cleanup when the in-memory record is gone). */
    suspend fun locateSidecarByFileId(fileId: Int): Triple<String, String, DownloadSidecar>? =
        downloadDao.findByFileId(fileId)?.let { Triple(it.serverId, it.profileId, it.toSidecar()) }

    suspend fun locateSidecarByFileId(serverId: String, profileId: String, fileId: Int): Triple<String, String, DownloadSidecar>? =
        downloadDao.get(serverId, profileId, fileId)?.let { Triple(serverId, profileId, it.toSidecar()) }

    suspend fun deleteAllForProfile(serverId: String, profileId: String) =
        downloadDao.deleteAll(serverId, profileId)

    suspend fun deleteAllForServer(serverId: String) =
        downloadDao.deleteAllForServer(serverId)
}
