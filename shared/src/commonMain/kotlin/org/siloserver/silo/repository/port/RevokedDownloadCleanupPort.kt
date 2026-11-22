package org.siloserver.silo.repository.port

/**
 * Removes this device's copy of a download an administrator revoked
 * (downloads API §9.3, `revoked_removes_local`): the media file, its saved
 * manifest metadata and its offline assets. [DownloadsRepository][org.siloserver.silo.repository.DownloadsRepository]
 * calls it from every registry read, and sends `DELETE /api/v2/downloads/{id}`
 * only after it returns true, so the server never records a removal that did
 * not happen.
 *
 * commonMain port with an Android implementation bound in the phone app; the
 * default is for platforms that keep no local download copies.
 */
interface RevokedDownloadCleanupPort {
    /** True once nothing of [recordId]'s local copy remains on this device. */
    suspend fun removeLocalCopy(serverId: String, accountId: String, profileId: String, recordId: String, mediaFileId: Int): Boolean
}

/** No local download copies on this platform, so there is nothing to remove. */
object NoLocalDownloadCopies : RevokedDownloadCleanupPort {
    override suspend fun removeLocalCopy(serverId: String, accountId: String, profileId: String, recordId: String, mediaFileId: Int) = true
}
