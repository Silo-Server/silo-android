package org.siloserver.silo.common.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import org.siloserver.silo.common.data.db.entity.DirtyOperationEntity

/**
 * Outbox access: coalescing enqueue, scoped due-batch drain selection, atomic
 * claim, attempt bookkeeping, reclaim of crash-stranded rows, and delete-on-ack.
 *
 * Drain loop (see `SyncEngine`): reclaim stranded in-flight rows
 * ([deleteSupersededInFlight] then [resetInFlightToPending]) → [dueBatch] the
 * current scope → [claim] each (skip if not won) → send → [deleteById] on
 * success/terminal, or [recordFailure] on transient failure (unless a newer
 * pending op for the same key supersedes it — [countNewerPending] → [deleteById]).
 */
@Dao
interface DirtyOperationDao {

    @Insert
    suspend fun insert(op: DirtyOperationEntity): Long

    /**
     * Enqueue, coalescing against any pending op with the same [DirtyOperationEntity.coalesceKey]:
     * the older un-synced op is dropped so only the latest intent is sent.
     * In-flight rows are left alone (a send is already underway for them).
     */
    @Transaction
    suspend fun enqueueCoalescing(op: DirtyOperationEntity): Long {
        deletePendingByCoalesceKey(op.coalesceKey)
        return insert(op)
    }

    @Query(
        "DELETE FROM dirty_operations WHERE coalesceKey = :coalesceKey AND state = '${DirtyOperationEntity.STATE_PENDING}'",
    )
    suspend fun deletePendingByCoalesceKey(coalesceKey: String)

    @Query(
        "SELECT * FROM dirty_operations WHERE coalesceKey = :coalesceKey " +
            "ORDER BY id DESC LIMIT 1",
    )
    suspend fun getLatestByCoalesceKey(coalesceKey: String): DirtyOperationEntity?

    @Query(
        "DELETE FROM dirty_operations WHERE serverId = :serverId AND profileId = :profileId " +
            "AND targetContentId = :contentId AND opKind = :opKind " +
            "AND state = '${DirtyOperationEntity.STATE_PENDING}'",
    )
    suspend fun deletePendingForTargetKind(
        serverId: String,
        profileId: String,
        contentId: String,
        opKind: String,
    )

    @Query(
        "SELECT COUNT(*) FROM dirty_operations WHERE serverId = :serverId AND profileId = :profileId " +
            "AND targetContentId = :contentId AND opKind = :opKind " +
            "AND state = '${DirtyOperationEntity.STATE_IN_FLIGHT}'",
    )
    suspend fun countInFlightForTargetKind(
        serverId: String,
        profileId: String,
        contentId: String,
        opKind: String,
    ): Int

    /**
     * Oldest-due-first batch of sendable ops for one server/profile scope
     * (FIFO via the nextAttemptAtMs,id index). Scoped because the shared HTTP
     * client binds relative calls to the *current* server/profile — replaying a
     * different scope's rows would write to the wrong account.
     */
    @Query(
        "SELECT * FROM dirty_operations " +
            "WHERE state = '${DirtyOperationEntity.STATE_PENDING}' AND nextAttemptAtMs <= :nowMs " +
            "AND serverId = :serverId AND profileId = :profileId " +
            "ORDER BY nextAttemptAtMs ASC, id ASC LIMIT :limit",
    )
    suspend fun dueBatch(serverId: String, profileId: String, nowMs: Long, limit: Int): List<DirtyOperationEntity>

    /**
     * Atomically claim a pending op for sending. Returns the number of rows
     * updated: 1 if this caller won the claim, 0 if it was already claimed or
     * removed. The state guard makes this safe across concurrent drains/processes.
     */
    @Query(
        "UPDATE dirty_operations SET state = '${DirtyOperationEntity.STATE_IN_FLIGHT}' " +
            "WHERE id = :id AND state = '${DirtyOperationEntity.STATE_PENDING}'",
    )
    suspend fun claim(id: Long): Int

    /** Count of pending ops for the same coalesce key that are newer than [id]. */
    @Query(
        "SELECT COUNT(*) FROM dirty_operations " +
            "WHERE coalesceKey = :coalesceKey AND state = '${DirtyOperationEntity.STATE_PENDING}' AND id > :id",
    )
    suspend fun countNewerPending(coalesceKey: String, id: Long): Int

    /**
     * Atomic resolution of a failed send: if a newer pending op for the same
     * coalesce key exists this op is superseded (deleted, returns true);
     * otherwise it is returned to pending with backoff (returns false). Done in
     * one transaction so a concurrent enqueue can't slip between the check and
     * the write and resurrect a stale op.
     */
    @Transaction
    suspend fun supersedeOrRecordFailure(
        id: Long,
        coalesceKey: String,
        nowMs: Long,
        nextAttemptAtMs: Long,
        error: String?,
    ): Boolean {
        if (countNewerPending(coalesceKey, id) > 0) {
            deleteById(id)
            return true
        }
        recordFailure(id, nowMs, nextAttemptAtMs, error)
        return false
    }

    /**
     * Drop in-flight rows (for one scope) the app was sending when it died, if a
     * newer pending op for the same key already supersedes them — sending the
     * stale value after the newer intent would corrupt state. Scoped so a drain
     * never touches another server/profile's rows.
     */
    @Query(
        "DELETE FROM dirty_operations WHERE state = '${DirtyOperationEntity.STATE_IN_FLIGHT}' " +
            "AND serverId = :serverId AND profileId = :profileId " +
            "AND EXISTS (SELECT 1 FROM dirty_operations newer " +
            "WHERE newer.coalesceKey = dirty_operations.coalesceKey " +
            "AND newer.serverId = dirty_operations.serverId AND newer.profileId = dirty_operations.profileId " +
            "AND newer.state = '${DirtyOperationEntity.STATE_PENDING}' AND newer.id > dirty_operations.id)",
    )
    suspend fun deleteSupersededInFlight(serverId: String, profileId: String)

    /** Return remaining crash-stranded in-flight rows (for one scope) to pending. */
    @Query(
        "UPDATE dirty_operations SET state = '${DirtyOperationEntity.STATE_PENDING}' " +
            "WHERE state = '${DirtyOperationEntity.STATE_IN_FLIGHT}' " +
            "AND serverId = :serverId AND profileId = :profileId",
    )
    suspend fun resetInFlightToPending(serverId: String, profileId: String)

    @Query(
        "UPDATE dirty_operations SET " +
            "state = '${DirtyOperationEntity.STATE_PENDING}', " +
            "attemptCount = attemptCount + 1, " +
            "lastAttemptAtMs = :nowMs, nextAttemptAtMs = :nextAttemptAtMs, lastError = :error " +
            "WHERE id = :id",
    )
    suspend fun recordFailure(id: Long, nowMs: Long, nextAttemptAtMs: Long, error: String?)

    @Query("SELECT * FROM dirty_operations WHERE id = :id")
    suspend fun getById(id: Long): DirtyOperationEntity?

    @Query("DELETE FROM dirty_operations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query(
        "SELECT COUNT(*) FROM dirty_operations " +
            "WHERE serverId = :serverId AND profileId = :profileId",
    )
    suspend fun countForScope(serverId: String, profileId: String): Int

    @Query("SELECT COUNT(*) FROM dirty_operations")
    suspend fun count(): Int
}
