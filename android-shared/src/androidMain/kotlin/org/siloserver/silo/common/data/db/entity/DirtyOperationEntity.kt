package org.siloserver.silo.common.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Typed, ordered, idempotent outbox row (Track B). Every optimistic user
 * mutation writes a projection row in [UserItemStateEntity] **and** one of
 * these in the same Room transaction; the sync engine drains them to the
 * server, retries with backoff, and deletes them on ack.
 *
 * This is the durable persistence shape; the pure in-memory op model is
 * [org.siloserver.silo.common.data.sync.OutboxOperation]. Beyond the payload it
 * carries the replay metadata a robust drain loop needs:
 *
 * - [idempotencyKey] — unique; lets a retried op be deduped server-side and
 *   prevents a double-enqueue from sending twice.
 * - [coalesceKey] — newer ops of the same kind+target replace older un-synced
 *   ones (repeated SET_POSITION collapses to the latest).
 * - [opVersion] — schema version of [payloadJson], so a future payload shape
 *   change can be migrated rather than mis-parsed.
 * - [state] — [STATE_PENDING] (eligible to send) vs [STATE_IN_FLIGHT] (claimed
 *   by a drain pass) so a crash mid-send doesn't strand or double-send a row.
 * - [attemptCount] / [lastAttemptAtMs] / [nextAttemptAtMs] / [lastError] —
 *   exponential-backoff bookkeeping; the drain query selects rows due now.
 */
@Entity(
    tableName = "dirty_operations",
    indices = [
        Index(value = ["idempotencyKey"], unique = true),
        Index(value = ["coalesceKey"]),
        // Drain order: oldest-due-first, id tiebreak for stable FIFO.
        Index(value = ["nextAttemptAtMs", "id"]),
    ],
)
data class DirtyOperationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val opKind: String,        // SET_POSITION, SET_WATCHED, SET_RATING, SET_FAVORITE
    val serverId: String,
    val profileId: String,
    val targetContentId: String,
    val targetFileId: Int?,    // null for content-level ops (watched/rating/favorite)
    val coalesceKey: String,
    val idempotencyKey: String,
    val opVersion: Int = 1,
    val payloadJson: String,
    val state: String = STATE_PENDING,
    val createdAtMs: Long,
    val attemptCount: Int = 0,
    val lastAttemptAtMs: Long? = null,
    val nextAttemptAtMs: Long = 0,
    val lastError: String? = null,
    val membershipAuthority: String? = null,
    val membershipClaim: String? = null,
    val membershipOwner: String? = null,
) {
    companion object {
        const val STATE_PENDING = "pending"
        const val STATE_IN_FLIGHT = "in_flight"
    }
}
