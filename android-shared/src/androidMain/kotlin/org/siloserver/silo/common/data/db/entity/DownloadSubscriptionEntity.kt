package org.siloserver.silo.common.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "download_subscriptions",
    primaryKeys = ["serverId", "accountId", "profileId", "id"],
    indices = [
        Index(value = ["serverId", "accountId", "profileId", "enabled"]),
        Index(value = ["serverId", "accountId", "profileId", "targetType", "targetId"], unique = true),
    ],
)
data class DownloadSubscriptionEntity(
    val id: String,
    val serverId: String,
    /** Saved account ([org.siloserver.silo.model.server.AccountEntry.localAccountId]); '' = written before accounts existed. */
    @ColumnInfo(defaultValue = "")
    val accountId: String,
    val profileId: String,
    val targetType: String,
    val targetId: String,
    val displayTitle: String,
    val mediaKind: String,
    val quality: String,
    val wifiOnly: Boolean,
    val enabled: Boolean,
    val includeExisting: Boolean,
    val keepUnwatchedLimit: Int,
    val deleteWatchedAfterDays: Int,
    val createdAt: Long,
    val updatedAt: Long,
    val lastEvaluatedAt: Long?,
    val lastError: String?,
)
