package org.siloserver.silo.common.data.repository

import org.siloserver.silo.common.data.db.SiloDatabase
import org.siloserver.silo.common.data.db.entity.DownloadSubscriptionEntity
import org.siloserver.silo.model.download.DownloadQuality
import org.siloserver.silo.model.download.DownloadSubscription
import org.siloserver.silo.model.download.DownloadSubscriptionMediaKind
import org.siloserver.silo.model.download.DownloadSubscriptionTargetType
import org.siloserver.silo.repository.DownloadSubscriptionRepository

class RoomDownloadSubscriptionRepository(db: SiloDatabase) : DownloadSubscriptionRepository {
    private val dao = db.downloadSubscriptionDao()

    override suspend fun upsert(subscription: DownloadSubscription) {
        dao.upsert(subscription.toEntity())
    }

    override suspend fun active(serverId: String, accountId: String, profileId: String): List<DownloadSubscription> =
        dao.getActive(serverId, accountId, profileId).map { it.toModel() }

    override suspend fun all(serverId: String, accountId: String, profileId: String): List<DownloadSubscription> =
        dao.getAll(serverId, accountId, profileId).map { it.toModel() }

    override suspend fun disable(serverId: String, accountId: String, profileId: String, id: String, updatedAt: Long) {
        dao.disable(serverId, accountId, profileId, id, updatedAt)
    }

    override suspend fun updateEvaluation(
        serverId: String,
        accountId: String,
        profileId: String,
        id: String,
        evaluatedAt: Long,
        error: String?,
        updatedAt: Long,
    ) {
        dao.updateEvaluation(serverId, accountId, profileId, id, evaluatedAt, error, updatedAt)
    }

    override suspend fun deleteAllForServer(serverId: String) {
        dao.deleteAllForServer(serverId)
    }

    private fun DownloadSubscription.toEntity() = DownloadSubscriptionEntity(
        id = id,
        serverId = serverId,
        accountId = accountId,
        profileId = profileId,
        targetType = targetType.wire,
        targetId = targetId,
        displayTitle = displayTitle,
        mediaKind = mediaKind.wire,
        quality = quality.wire,
        wifiOnly = wifiOnly,
        enabled = enabled,
        includeExisting = includeExisting,
        keepUnwatchedLimit = keepUnwatchedLimit,
        deleteWatchedAfterDays = deleteWatchedAfterDays,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastEvaluatedAt = lastEvaluatedAt,
        lastError = lastError,
    )

    private fun DownloadSubscriptionEntity.toModel() = DownloadSubscription(
        id = id,
        serverId = serverId,
        accountId = accountId,
        profileId = profileId,
        targetType = DownloadSubscriptionTargetType.fromWire(targetType),
        targetId = targetId,
        displayTitle = displayTitle,
        mediaKind = DownloadSubscriptionMediaKind.fromWire(mediaKind),
        quality = DownloadQuality.fromWire(quality),
        wifiOnly = wifiOnly,
        enabled = enabled,
        includeExisting = includeExisting,
        keepUnwatchedLimit = keepUnwatchedLimit,
        deleteWatchedAfterDays = deleteWatchedAfterDays,
        createdAt = createdAt,
        updatedAt = updatedAt,
        lastEvaluatedAt = lastEvaluatedAt,
        lastError = lastError,
    )
}
