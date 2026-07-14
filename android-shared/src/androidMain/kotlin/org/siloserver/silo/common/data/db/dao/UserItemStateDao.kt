package org.siloserver.silo.common.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.siloserver.silo.common.data.db.entity.UserItemStateEntity

/**
 * Reads/writes the local-first user-state projection. Scoped reads mirror how
 * the legacy stores key state — by `(serverId, profileId, contentId[, fileId])`
 * — plus a recency-ordered resume scan for the Continue Watching row.
 */
@Dao
interface UserItemStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: UserItemStateEntity)

    @Query(
        "SELECT * FROM user_item_state " +
            "WHERE serverId = :serverId AND profileId = :profileId AND contentId = :contentId AND fileId = :fileId",
    )
    suspend fun get(serverId: String, profileId: String, contentId: String, fileId: Int): UserItemStateEntity?

    /** All file-level rows for a content item (e.g. every version/track). */
    @Query(
        "SELECT * FROM user_item_state " +
            "WHERE serverId = :serverId AND profileId = :profileId AND contentId = :contentId",
    )
    suspend fun getByContent(serverId: String, profileId: String, contentId: String): List<UserItemStateEntity>

    /** Clears resume state without discarding per-file playback or reading preferences. */
    @Query(
        "UPDATE user_item_state SET positionSeconds = 0, clientUpdatedAtMs = :updatedAtMs " +
            "WHERE serverId = :serverId AND profileId = :profileId AND contentId = :contentId " +
            "AND positionSeconds <> 0",
    )
    suspend fun clearPlaybackProgress(
        serverId: String,
        profileId: String,
        contentId: String,
        updatedAtMs: Long,
    )

    /** Restore a reset resume row only while it is still the untouched reset. */
    @Query(
        "UPDATE user_item_state SET positionSeconds = :positionSeconds, clientUpdatedAtMs = :previousUpdatedAtMs " +
            "WHERE serverId = :serverId AND profileId = :profileId AND contentId = :contentId " +
            "AND fileId = :fileId AND positionSeconds = 0 AND clientUpdatedAtMs = :clearedAtMs",
    )
    suspend fun restorePlaybackProgressIfUnchanged(
        serverId: String,
        profileId: String,
        contentId: String,
        fileId: Int,
        positionSeconds: Double,
        previousUpdatedAtMs: Long,
        clearedAtMs: Long,
    )

    @Query(
        "SELECT * FROM user_item_state " +
            "WHERE serverId = :serverId AND profileId = :profileId " +
            "AND contentId IN (:contentIds) AND positionSeconds > 0",
    )
    suspend fun progressForContentIds(
        serverId: String,
        profileId: String,
        contentIds: List<String>,
    ): List<UserItemStateEntity>

    /**
     * Resume scan: most-recently-touched rows with real progress, newest first.
     * Drives the Continue Watching / Up Next row without a server round-trip.
     */
    @Query(
        "SELECT * FROM user_item_state " +
            "WHERE serverId = :serverId AND profileId = :profileId AND positionSeconds > 0 " +
            "ORDER BY clientUpdatedAtMs DESC LIMIT :limit",
    )
    suspend fun recentlyPlayed(serverId: String, profileId: String, limit: Int): List<UserItemStateEntity>

    @Query(
        "DELETE FROM user_item_state " +
            "WHERE serverId = :serverId AND profileId = :profileId AND contentId = :contentId AND fileId = :fileId",
    )
    suspend fun delete(serverId: String, profileId: String, contentId: String, fileId: Int)
}
