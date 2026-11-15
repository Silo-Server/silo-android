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
            "WHERE serverId = :serverId AND accountId = :accountId AND profileId = :profileId AND contentId = :contentId AND fileId = :fileId",
    )
    suspend fun get(serverId: String, accountId: String, profileId: String, contentId: String, fileId: Int): UserItemStateEntity?

    /** All file-level rows for a content item (e.g. every version/track). */
    @Query(
        "SELECT * FROM user_item_state " +
            "WHERE serverId = :serverId AND accountId = :accountId AND profileId = :profileId AND contentId = :contentId",
    )
    suspend fun getByContent(serverId: String, accountId: String, profileId: String, contentId: String): List<UserItemStateEntity>

    /** Clears resume state without discarding per-file playback or reading preferences. */
    @Query(
        "UPDATE user_item_state SET positionSeconds = 0, clientUpdatedAtMs = :updatedAtMs, positionUpdatedAtMs = :updatedAtMs " +
            "WHERE serverId = :serverId AND accountId = :accountId AND profileId = :profileId AND contentId = :contentId " +
            "AND positionSeconds <> 0",
    )
    suspend fun clearPlaybackProgress(
        serverId: String,
        accountId: String,
        profileId: String,
        contentId: String,
        updatedAtMs: Long,
    )

    /**
     * A delayed watched acknowledgement must not clear a newer local resume sample.
     * Rows written before positionUpdatedAtMs existed fall back to clientUpdatedAtMs.
     */
    @Query("UPDATE user_item_state SET positionSeconds = 0, clientUpdatedAtMs = :updatedAtMs, positionUpdatedAtMs = :updatedAtMs " +
        "WHERE serverId = :serverId AND accountId = :accountId AND profileId = :profileId AND contentId = :contentId " +
        "AND COALESCE(positionUpdatedAtMs, clientUpdatedAtMs) < :admittedAtMs")
    suspend fun clearPlaybackProgressBefore(serverId: String, accountId: String, profileId: String, contentId: String, admittedAtMs: Long, updatedAtMs: Long)

    /**
     * Restore a reset resume row only while it is still the untouched reset.
     * A reset made before positionUpdatedAtMs existed is matched by clientUpdatedAtMs.
     */
    @Query(
        "UPDATE user_item_state SET positionSeconds = :positionSeconds, clientUpdatedAtMs = CASE WHEN clientUpdatedAtMs = :clearedAtMs THEN :previousUpdatedAtMs ELSE clientUpdatedAtMs END, positionUpdatedAtMs = :previousPositionUpdatedAtMs " +
            "WHERE serverId = :serverId AND accountId = :accountId AND profileId = :profileId AND contentId = :contentId " +
            "AND fileId = :fileId AND positionSeconds = 0 AND COALESCE(positionUpdatedAtMs, clientUpdatedAtMs) = :clearedAtMs",
    )
    suspend fun restorePlaybackProgressIfUnchanged(
        serverId: String,
        accountId: String,
        profileId: String,
        contentId: String,
        fileId: Int,
        positionSeconds: Double,
        previousUpdatedAtMs: Long,
        previousPositionUpdatedAtMs: Long?,
        clearedAtMs: Long,
    )

    /**
     * Download playback positions still waiting to reach the server: a v2
     * `SET_POSITION` op is queued or in flight for that exact file. Only these
     * may override the server's answer; once acknowledged the server is the truth.
     */
    @Query(
        "SELECT * FROM user_item_state " +
            "WHERE serverId = :serverId AND accountId = :accountId AND profileId = :profileId " +
            "AND contentId IN (:contentIds) AND positionSeconds > 0 " +
            "AND EXISTS (SELECT 1 FROM dirty_operations o WHERE o.opKind = 'SET_POSITION' AND o.opVersion >= 2 " +
            "AND o.state IN ('pending', 'in_flight') AND o.serverId = user_item_state.serverId " +
            "AND o.accountId = user_item_state.accountId AND o.profileId = user_item_state.profileId " +
            "AND o.targetContentId = user_item_state.contentId AND o.targetFileId = user_item_state.fileId)",
    )
    suspend fun pendingProgressForContentIds(
        serverId: String,
        accountId: String,
        profileId: String,
        contentIds: List<String>,
    ): List<UserItemStateEntity>

    /**
     * Resume scan: most-recently-touched rows with real progress, newest first.
     * Drives the Continue Watching / Up Next row without a server round-trip.
     */
    @Query(
        "SELECT * FROM user_item_state " +
            "WHERE serverId = :serverId AND accountId = :accountId AND profileId = :profileId AND positionSeconds > 0 " +
            "ORDER BY clientUpdatedAtMs DESC LIMIT :limit",
    )
    suspend fun recentlyPlayed(serverId: String, accountId: String, profileId: String, limit: Int): List<UserItemStateEntity>

    @Query(
        "DELETE FROM user_item_state " +
            "WHERE serverId = :serverId AND accountId = :accountId AND profileId = :profileId AND contentId = :contentId AND fileId = :fileId",
    )
    suspend fun delete(serverId: String, accountId: String, profileId: String, contentId: String, fileId: Int)
}
