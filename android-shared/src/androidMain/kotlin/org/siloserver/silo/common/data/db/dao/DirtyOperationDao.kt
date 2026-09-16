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

    // Personal-data v1 rows are held byte-for-byte, including crash-stranded rows.
    @Query("SELECT COUNT(*) FROM dirty_operations WHERE serverId = :serverId AND profileId = :profileId " +
        "AND targetContentId = :contentId AND opKind IN ('SET_WATCHED', 'SET_RATING', 'SET_POSITION', 'PERSONAL_V2')")
    suspend fun unresolvedPersonalCount(serverId: String, profileId: String, contentId: String): Int

    /** A newer desired-state write supersedes any v2 personal row the previous attempt left behind. */
    @Query("DELETE FROM dirty_operations WHERE opKind = 'PERSONAL_V2' AND serverId = :serverId " +
        "AND profileId = :profileId AND targetContentId = :contentId")
    suspend fun deletePersonalV2ForItem(serverId: String, profileId: String, contentId: String)

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

    @Query("UPDATE dirty_operations SET state = 'ebook_identity_quarantined' WHERE id = :id AND opKind = 'SET_EBOOK_PROGRESS' AND state = 'pending'")
    suspend fun quarantineEbookProgress(id: Long): Int

    @Query(
        "DELETE FROM dirty_operations WHERE coalesceKey = :coalesceKey AND state = '${DirtyOperationEntity.STATE_PENDING}'",
    )
    suspend fun deletePendingByCoalesceKey(coalesceKey: String)

    @Query(
        "SELECT * FROM dirty_operations WHERE coalesceKey = :coalesceKey AND state != 'legacy_membership_quarantined' " +
            "ORDER BY id DESC LIMIT 1",
    )
    suspend fun getLatestByCoalesceKey(coalesceKey: String): DirtyOperationEntity?

    /**
     * Enqueues [op] and pulls any backed-off sibling for the same item forward.
     *
     * `dueBatch` orders by `nextAttemptAtMs ASC, id ASC`, and `recordFailure`
     * pushes a failed op 30s·2ⁿ into the future — so an op enqueued now drains
     * *before* one that was created earlier and failed once. For a single item
     * that inverts creation order: mark an episode watched while the server is
     * flaky, then resume it, and the progress syncs first while the retried
     * watched lands 30s later and clears it. The episode vanishes from Continue
     * Watching on every device while the user is ten minutes in, and local and
     * server diverge permanently. Deterministic, not a race.
     *
     * Clamping rather than deleting the sibling is deliberate: deleting a
     * pending `SET_WATCHED` would strand `watched = true` locally with no
     * terminal op to revert it, and could drop a deliberately-requeued replay.
     * `attemptCount` is untouched, so backoff still grows on the next failure.
     */
    @Transaction
    suspend fun enqueueCoalescingRestoringItemOrder(op: DirtyOperationEntity, nowMs: Long): Long {
        clearBackoffForTargetContent(
            serverId = op.serverId,
            profileId = op.profileId,
            contentId = op.targetContentId,
            nowMs = nowMs,
        )
        return enqueueCoalescing(op)
    }

    @Query(
        "UPDATE dirty_operations SET nextAttemptAtMs = :nowMs " +
            "WHERE serverId = :serverId AND profileId = :profileId " +
            "AND targetContentId = :contentId " +
            "AND state = '${DirtyOperationEntity.STATE_PENDING}' " +
            "AND nextAttemptAtMs > :nowMs",
    )
    suspend fun clearBackoffForTargetContent(
        serverId: String,
        profileId: String,
        contentId: String,
        nowMs: Long,
    )

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
     * Due oldest operation for each content item. The anti-join is evaluated
     * before LIMIT so an older sibling in backoff cannot fall outside the SQL
     * page and let a newer operation overtake it.
     */
    @Query(
        "SELECT candidate.* FROM dirty_operations candidate " +
            "WHERE candidate.opKind = 'SET_EBOOK_PROGRESS' AND candidate.state = '${DirtyOperationEntity.STATE_PENDING}' " +
            "AND candidate.nextAttemptAtMs <= :nowMs " +
            "AND candidate.serverId = :serverId AND candidate.profileId = :profileId " +
            "AND NOT EXISTS (" +
            "SELECT 1 FROM dirty_operations older " +
            "WHERE older.opKind = 'SET_EBOOK_PROGRESS' AND older.serverId = candidate.serverId " +
            "AND older.profileId = candidate.profileId " +
            "AND older.targetContentId = candidate.targetContentId " +
            "AND older.state IN ('${DirtyOperationEntity.STATE_PENDING}', '${DirtyOperationEntity.STATE_IN_FLIGHT}') " +
            "AND older.id < candidate.id" +
            ") ORDER BY candidate.nextAttemptAtMs ASC, candidate.id ASC LIMIT :limit",
    )
    suspend fun dueTargetHeads(
        serverId: String,
        profileId: String,
        nowMs: Long,
        limit: Int,
    ): List<DirtyOperationEntity>

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
        "DELETE FROM dirty_operations WHERE opKind = 'SET_EBOOK_PROGRESS' AND state = '${DirtyOperationEntity.STATE_IN_FLIGHT}' " +
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
            "WHERE opKind = 'SET_EBOOK_PROGRESS' AND state = '${DirtyOperationEntity.STATE_IN_FLIGHT}' " +
            "AND serverId = :serverId AND profileId = :profileId",
    )
    suspend fun resetInFlightToPending(serverId: String, profileId: String)

    @Query(
        "UPDATE dirty_operations SET " +
            "state = '${DirtyOperationEntity.STATE_PENDING}', " +
            "attemptCount = attemptCount + 1, " +
            "lastAttemptAtMs = :nowMs, nextAttemptAtMs = :nextAttemptAtMs, lastError = :error " +
            "WHERE id = :id AND state != 'legacy_membership_quarantined'",
    )
    suspend fun recordFailure(id: Long, nowMs: Long, nextAttemptAtMs: Long, error: String?)

    @Query("SELECT * FROM dirty_operations WHERE id = :id")
    suspend fun getById(id: Long): DirtyOperationEntity?

    @Query("DELETE FROM dirty_operations WHERE id = :id AND state != 'legacy_membership_quarantined'")
    suspend fun deleteById(id: Long)

    @Query(
        "SELECT COUNT(*) FROM dirty_operations " +
            "WHERE serverId = :serverId AND profileId = :profileId AND state != 'legacy_membership_quarantined'",
    )
    suspend fun countForScope(serverId: String, profileId: String): Int

    @Query("SELECT COUNT(*) FROM dirty_operations WHERE state != 'legacy_membership_quarantined'")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM dirty_operations WHERE state = 'legacy_membership_quarantined'")
    suspend fun quarantinedMembershipCount(): Int

    @Query("SELECT COUNT(*) FROM dirty_operations WHERE membershipAuthority = :authority AND state = 'membership_ready'")
    suspend fun readyMembershipCount(authority: String): Int

    /** Future worker cutover must use this count, not all unresolved membership states. */
    @Query("SELECT COUNT(*) FROM dirty_operations WHERE serverId = :serverId AND profileId = :profileId " +
        "AND opKind = 'SET_EBOOK_PROGRESS' AND state IN ('pending', 'in_flight')")
    suspend fun runnableLegacyCountForScope(serverId: String, profileId: String): Int

    @Query("SELECT * FROM dirty_operations WHERE membershipAuthority = :authority " +
        "AND state = 'membership_ready' ORDER BY id LIMIT :limit")
    suspend fun readyMembershipCommands(authority: String, limit: Int): List<DirtyOperationEntity>

    /** Membership rows never enter the legacy retry/drain states. */
    @Query("DELETE FROM dirty_operations WHERE coalesceKey = :key AND state = 'membership_ready'")
    suspend fun deleteReadyMembership(key: String)

    @Transaction
    suspend fun enqueueMembership(op: DirtyOperationEntity): Long {
        require(op.state == "membership_ready" && !op.membershipAuthority.isNullOrBlank())
        deleteReadyMembership(op.coalesceKey)
        return insert(op)
    }

    @Query("UPDATE dirty_operations SET state = 'membership_sending', membershipClaim = :claim, " +
        "membershipOwner = :owner WHERE id = :id AND membershipAuthority = :authority " +
        "AND state = 'membership_ready' AND NOT EXISTS (SELECT 1 FROM dirty_operations older " +
        "WHERE older.coalesceKey = dirty_operations.coalesceKey AND older.state = 'membership_sending')")
    suspend fun claimMembership(id: Long, authority: String, claim: String, owner: String): Int

    @Query("UPDATE dirty_operations SET state = :state WHERE id = :id AND membershipClaim = :claim " +
        "AND membershipAuthority = :authority AND state = :expectedState")
    suspend fun transitionMembership(id: Long, claim: String, authority: String, expectedState: String, state: String): Int

    @Query("DELETE FROM dirty_operations WHERE id = :id AND membershipClaim = :claim " +
        "AND membershipAuthority = :authority AND state = :state")
    suspend fun resolveMembership(id: Long, claim: String, authority: String, state: String): Int

    /** Call once at process startup, before any membership sender starts. Never reclaim by timeout. */
    @Query("UPDATE dirty_operations SET state = 'membership_reconcile' " +
        "WHERE state = 'membership_sending' AND (membershipOwner IS NULL OR membershipOwner != :owner)")
    suspend fun recoverMembership(owner: String): Int

    @Query("SELECT * FROM dirty_operations WHERE membershipAuthority = :authority " +
        "AND state IN ('membership_ready', 'membership_reconcile', 'membership_paused') ORDER BY id LIMIT :limit")
    suspend fun membershipCommands(authority: String, limit: Int): List<DirtyOperationEntity>
}
