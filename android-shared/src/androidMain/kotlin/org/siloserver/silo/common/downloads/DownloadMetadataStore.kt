package org.siloserver.silo.common.downloads

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import org.siloserver.silo.common.data.db.SiloDatabase
import org.siloserver.silo.common.data.db.dao.DownloadArtworkRow
import org.siloserver.silo.common.data.db.entity.DownloadEntity
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

    suspend fun writeSidecar(serverId: String, accountId: String, profileId: String, sidecar: DownloadSidecar) {
        downloadDao.upsert(sidecar.toEntity(serverId, accountId, profileId))
    }

    suspend fun readSidecar(serverId: String, accountId: String, profileId: String, fileId: Int): DownloadSidecar? =
        downloadDao.get(serverId, accountId, profileId, fileId)?.toSidecar()

    suspend fun deleteSidecar(serverId: String, accountId: String, profileId: String, fileId: Int) {
        downloadDao.delete(serverId, accountId, profileId, fileId)
    }

    /**
     * Writes what [update] makes of the slot's row, in one transaction, only
     * while the row still [matches]. A row replaced or deleted after the caller
     * last read it is left as it is. [update] may return null to write nothing.
     */
    suspend fun updateSidecarIf(
        serverId: String,
        accountId: String,
        profileId: String,
        fileId: Int,
        matches: (DownloadSidecar) -> Boolean,
        update: (DownloadSidecar) -> DownloadSidecar?,
    ): Boolean = db.withTransaction {
        val current = downloadDao.get(serverId, accountId, profileId, fileId)?.toSidecar()
            ?.takeIf(matches)
            ?: return@withTransaction false
        val updated = update(current) ?: return@withTransaction false
        downloadDao.upsert(updated.toEntity(serverId, accountId, profileId))
        true
    }

    /** A file slot may now contain a replacement download; an old tombstone cannot own it. */
    suspend fun completePendingDeletion(
        serverId: String,
        accountId: String,
        profileId: String,
        fileId: Int,
        recordId: String,
        deleteBytes: suspend () -> Boolean,
    ): Boolean = db.withTransaction {
        val row = downloadDao.get(serverId, accountId, profileId, fileId) ?: return@withTransaction false
        if (row.recordId != recordId) return@withTransaction false
        // Keep the identity check and cleanup together: a replacement metadata
        // write must not race between the check and removal of the file slot.
        if (!deleteBytes()) return@withTransaction false
        downloadDao.delete(serverId, accountId, profileId, fileId)
        true
    }

    suspend fun listSidecars(serverId: String, accountId: String, profileId: String): List<DownloadSidecar> {
        AccountDataAdoption.awaitFirstPass()
        return downloadDao.getAll(serverId, accountId, profileId).map { it.toSidecar() }
    }

    suspend fun listAllSidecars(): List<DownloadSidecar> =
        downloadDao.getAllAcrossScopes().map { it.toSidecar() }

    /** Every download across all scopes, with its scope. */
    suspend fun listAllSidecarsWithScope(): List<ScopedDownloadSidecar> {
        AccountDataAdoption.awaitFirstPass()
        return downloadDao.getAllAcrossScopes().map { it.toScoped() }
    }

    /** Scope-agnostic lookup by file id (delete cleanup when the in-memory record is gone). */
    suspend fun locateSidecarByFileId(fileId: Int): ScopedDownloadSidecar? =
        downloadDao.findByFileId(fileId)?.toScoped()

    suspend fun locateSidecarByFileId(serverId: String, accountId: String, profileId: String, fileId: Int): ScopedDownloadSidecar? =
        downloadDao.get(serverId, accountId, profileId, fileId)?.toScoped()

    suspend fun deleteAllForProfile(serverId: String, accountId: String, profileId: String) =
        downloadDao.deleteAll(serverId, accountId, profileId)

    suspend fun deleteAllForServer(serverId: String) =
        downloadDao.deleteAllForServer(serverId)

    /**
     * How many downloads [accountId] has on [serverId] (every profile), and
     * the bytes they hold on this device: what signing the account out
     * deletes, for its confirmation.
     */
    suspend fun downloadSummary(serverId: String, accountId: String): DownloadSummary {
        AccountDataAdoption.awaitFirstPass()
        val row = downloadDao.summaryForAccount(serverId, accountId)
        return DownloadSummary(count = row.count, totalBytes = row.totalBytes)
    }

    private fun DownloadEntity.toScoped() = ScopedDownloadSidecar(serverId, accountId, profileId, toSidecar())

    /**
     * Saved-artwork columns of every download that has any, emitting only when
     * they change. The download capture writes artwork after the completed
     * status, so the Downloads tab watches this to pick up the saved images.
     */
    fun savedArtworkChanges(): Flow<List<DownloadArtworkRow>> =
        downloadDao.observeSavedArtwork().distinctUntilChanged()

    /**
     * Ids of downloads whose worker failed for good, emitting only when the set
     * changes. The server row of such a download still says `downloading`, so
     * the Downloads tab watches this to show the failure from the local row.
     */
    fun failedRecordIdChanges(): Flow<List<String>> =
        downloadDao.observeFailedRecordIds().distinctUntilChanged()
}

/** One download with the slot it is stored under. */
data class ScopedDownloadSidecar(
    val serverId: String,
    val accountId: String,
    val profileId: String,
    val sidecar: DownloadSidecar,
)

/** Number and on-device size of one account's downloads; see [DownloadMetadataStore.downloadSummary]. */
data class DownloadSummary(val count: Int, val totalBytes: Long)
