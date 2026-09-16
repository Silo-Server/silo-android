package org.siloserver.silo.common.data.repository

import org.siloserver.silo.common.data.db.SiloDatabase
import org.siloserver.silo.common.data.db.entity.DownloadDeletionEntity
import org.siloserver.silo.repository.port.DownloadDeletionPort
import org.siloserver.silo.repository.port.PendingDownloadDeletion

/**
 * Room-backed [DownloadDeletionPort] (Track B offline-first delete). Persists the
 * tombstone for a deleted download so its server record is reconciled on the next
 * online refresh and the recordId is filtered out of refreshes until then.
 */
class RoomDownloadDeletionStore(
    db: SiloDatabase,
    private val authorities: org.siloserver.silo.network.DurableLoginAuthorityProvider? = null,
    private val devices: org.siloserver.silo.network.DeviceMetadataProvider? = null,
    private val identityTransitions: org.siloserver.silo.network.IdentityTransitionBarrier? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
) : DownloadDeletionPort {

    private val dao = db.downloadDeletionDao()

    override suspend fun enqueue(serverId: String, profileId: String, recordId: String, mediaFileId: Int?) {
        val authority = authorities?.snapshotDurableLoginAuthority()
        val device = devices?.current()?.id
        if (authorities != null) require(authority != null && authority.scope.serverId == serverId &&
            authority.scope.profileId == profileId && !device.isNullOrBlank()) { "The download owner changed" }
        val row = DownloadDeletionEntity(serverId, profileId, recordId, mediaFileId, now(),
            authority?.loginId, authority?.scope?.serverUrl, device)
        if (authority == null) dao.upsert(row)
        else check(identityTransitions?.withCurrentGeneration(authority.scope.identityGeneration) {
            dao.upsert(row); true
        } == true) { "The download owner changed" }
    }

    override suspend fun allPendingRecordIds(): Set<String> = dao.allRecordIds().toSet()

    override suspend fun pendingForScope(serverId: String, profileId: String): List<PendingDownloadDeletion> =
        dao.forScope(serverId, profileId).map {
            PendingDownloadDeletion(it.serverId, it.profileId, it.recordId, it.mediaFileId, it.loginId, it.origin, it.deviceId)
        }

    override suspend fun remove(serverId: String, profileId: String, recordId: String) {
        dao.delete(serverId, profileId, recordId)
    }
}
