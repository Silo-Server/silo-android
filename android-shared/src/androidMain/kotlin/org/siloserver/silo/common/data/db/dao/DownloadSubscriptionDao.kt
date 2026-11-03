package org.siloserver.silo.common.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.siloserver.silo.common.data.db.entity.DownloadSubscriptionEntity

@Dao
interface DownloadSubscriptionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: DownloadSubscriptionEntity)

    @Query(
        "SELECT * FROM download_subscriptions " +
            "WHERE serverId = :serverId AND accountId = :accountId AND profileId = :profileId AND id = :id",
    )
    suspend fun get(serverId: String, accountId: String, profileId: String, id: String): DownloadSubscriptionEntity?

    @Query(
        "SELECT * FROM download_subscriptions " +
            "WHERE serverId = :serverId AND accountId = :accountId AND profileId = :profileId " +
            "ORDER BY updatedAt DESC",
    )
    suspend fun getAll(serverId: String, accountId: String, profileId: String): List<DownloadSubscriptionEntity>

    @Query(
        "SELECT * FROM download_subscriptions " +
            "WHERE serverId = :serverId AND accountId = :accountId AND profileId = :profileId AND enabled = 1 " +
            "ORDER BY updatedAt DESC",
    )
    suspend fun getActive(serverId: String, accountId: String, profileId: String): List<DownloadSubscriptionEntity>

    @Query(
        "UPDATE download_subscriptions SET enabled = 0, updatedAt = :updatedAt " +
            "WHERE serverId = :serverId AND accountId = :accountId AND profileId = :profileId AND id = :id",
    )
    suspend fun disable(serverId: String, accountId: String, profileId: String, id: String, updatedAt: Long)

    @Query(
        "UPDATE download_subscriptions " +
            "SET lastEvaluatedAt = :evaluatedAt, lastError = :error, updatedAt = :updatedAt " +
            "WHERE serverId = :serverId AND accountId = :accountId AND profileId = :profileId AND id = :id",
    )
    suspend fun updateEvaluation(
        serverId: String,
        accountId: String,
        profileId: String,
        id: String,
        evaluatedAt: Long,
        error: String?,
        updatedAt: Long,
    )

    @Query("DELETE FROM download_subscriptions WHERE serverId = :serverId")
    suspend fun deleteAllForServer(serverId: String)
}
