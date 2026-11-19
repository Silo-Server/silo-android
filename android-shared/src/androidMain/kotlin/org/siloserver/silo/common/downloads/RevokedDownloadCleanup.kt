package org.siloserver.silo.common.downloads

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.siloserver.silo.repository.port.RevokedDownloadCleanupPort

/**
 * Deletes this device's copy of an administrator-revoked download: stops its
 * transfer, then removes the media file, offline assets and metadata row under
 * the slot lock, as a user delete does. A slot now holding another download of
 * the same file is left alone.
 */
class RevokedDownloadCleanup(
    private val context: Context,
    private val storage: DownloadStorage,
    private val metadataStore: DownloadMetadataStore,
) : RevokedDownloadCleanupPort {

    override suspend fun removeLocalCopy(serverId: String, accountId: String, profileId: String, recordId: String, mediaFileId: Int): Boolean {
        DownloadWorker.cancelTransferAndAwait(context, recordId)
        return DownloadSlotLocks.of(serverId, accountId, profileId, mediaFileId).withLock {
            val row = metadataStore.readSidecar(serverId, accountId, profileId, mediaFileId)
            if (row != null && row.record.id != recordId) return@withLock true
            val bytesGone = withContext(Dispatchers.IO) {
                storage.delete(serverId, accountId, profileId, mediaFileId) ||
                    !storage.exists(serverId, accountId, profileId, mediaFileId)
            }
            if (bytesGone && row != null) metadataStore.deleteSidecar(serverId, accountId, profileId, mediaFileId)
            bytesGone
        }
    }
}
