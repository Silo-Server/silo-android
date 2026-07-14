package org.siloserver.silo.common.data.sync

import android.util.Log
import org.siloserver.silo.common.data.db.SiloDatabase
import org.siloserver.silo.common.data.db.entity.DirtyOperationEntity
import org.siloserver.silo.model.ebook.SaveEbookProgressRequest
import org.siloserver.silo.model.personal.SyncProgressItem
import org.siloserver.silo.model.personal.SyncProgressRequest
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.api.EbookReaderApi
import org.siloserver.silo.network.api.PersonalDataApi
import org.siloserver.silo.repository.port.WriteOutcome
import org.siloserver.silo.repository.port.toWriteOutcome

/**
 * Drains the `dirty_operations` outbox to the server (Track B). Replays each
 * pending op through the **raw [PersonalDataApi]** — never [PersonalDataRepository],
 * which would re-enter the local-first port and re-enqueue the op forever.
 *
 * Every send is **pinned** to the scope captured at drain start via
 * [AuthScopeSnapshot]: the auth plugin binds the request to that server URL,
 * profile, and exact live credential slot, so a scope switch mid-drain can't
 * send an op to the wrong account, and continuing to drain the captured scope
 * after a switch is correct.
 *
 * Correctness still rests on:
 * - **Atomic claim** ([DirtyOperationDao.claim]) — a row is sent at most once.
 * - **Reclaim** at drain start — in-flight rows stranded by a crash are dropped
 *   if a newer pending op supersedes them, else returned to pending.
 * - **Atomic supersede-or-record** on transient failure.
 *
 * Transient failures (no network / 401 / 408 / 429 / 5xx) are kept indefinitely
 * with capped backoff — offline data is never dropped on a retry cap. Only
 * terminal 4xx, unknown op kinds, and superseded rows are dropped.
 */
class SyncEngine(
    db: SiloDatabase,
    private val personalDataApi: PersonalDataApi,
    private val ebookReaderApi: EbookReaderApi,
    private val snapshotProvider: suspend () -> AuthScopeSnapshot?,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val batchLimit: Int = 50,
) {
    private val dao = db.dirtyOperationDao()
    private val contentDao = db.contentItemStateDao()
    private val userStateDao = db.userItemStateDao()

    data class DrainResult(
        val synced: Int = 0,
        val dropped: Int = 0,
        val retriable: Int = 0,
        val remaining: Int = 0,
    ) {
        /**
         * True while any op for the drained scope is still queued (failed-and-
         * backing-off or not-yet-processed). The worker reschedules on this so a
         * clean partial batch or a backoff row can never strand the outbox.
         */
        val hasPendingWork: Boolean get() = remaining > 0
    }

    /**
     * Drain all currently-due ops for the active scope, pinning every send to the
     * captured snapshot. Loops over batches so a backlog larger than [batchLimit]
     * fully drains in one run.
     */
    suspend fun drainOnce(): DrainResult {
        val scope = snapshotProvider() ?: return DrainResult()
        val serverId = scope.serverId
        // Ops are always enqueued with a profile; no profile → nothing to drain.
        val profileId = scope.profileId ?: return DrainResult()

        // Reclaim crash-stranded in-flight rows before claiming new work.
        dao.deleteSupersededInFlight(serverId, profileId)
        dao.resetInFlightToPending(serverId, profileId)

        var synced = 0
        var dropped = 0
        var retriable = 0

        var batches = 0
        while (batches++ < MAX_BATCHES) {
            val batch = dao.dueBatch(serverId, profileId, now(), batchLimit)
            if (batch.isEmpty()) break

            for (op in batch) {
                if (dao.claim(op.id) != 1) continue // lost the claim; skip

                val outcome = try {
                    dispatch(op, scope)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    // A malformed payload (rows predating input validation, or
                    // written by another code path) must not rethrow: the
                    // worker would retry forever and every other op for the
                    // scope would never drain again. Treat it as a terminal
                    // rejection — drop the op and revert its projection.
                    android.util.Log.w("SyncEngine", "op ${op.id} (${op.opKind}) failed to dispatch; dropping", t)
                    WriteOutcome.TERMINAL
                }
                when (outcome) {
                    WriteOutcome.SYNCED -> {
                        dao.deleteById(op.id)
                        synced++
                    }
                    WriteOutcome.TERMINAL -> {
                        // A stale in-flight op must not roll back a newer pending
                        // intent with the same key. Otherwise revert both its
                        // optimistic content field and any resume rows it cleared.
                        if (dao.countNewerPending(op.coalesceKey, op.id) == 0) {
                            contentDao.revertForTerminalOp(op)
                            userStateDao.restorePlaybackProgressForTerminalOp(op)
                        }
                        dao.deleteById(op.id)
                        dropped++
                    }
                    WriteOutcome.RETRIABLE -> {
                        val superseded = dao.supersedeOrRecordFailure(
                            id = op.id,
                            coalesceKey = op.coalesceKey,
                            nowMs = now(),
                            nextAttemptAtMs = now() + backoffMs(op.attemptCount),
                            error = WriteOutcome.RETRIABLE.name,
                        )
                        if (superseded) dropped++ else retriable++
                    }
                }
            }
        }

        // Count remaining for whatever scope is active NOW (re-snapshot), not the
        // scope we just drained. If the user switched mid-drain, this keeps the
        // worker's retry chain alive for the newly-active scope — covering the
        // case where an activation enqueue was dropped by ExistingWorkPolicy.KEEP
        // while this worker was running.
        val endScope = snapshotProvider()
        val endProfileId = endScope?.profileId
        val remaining = if (endScope != null && endProfileId != null) {
            dao.countForScope(endScope.serverId, endProfileId)
        } else {
            0
        }

        return DrainResult(
            synced = synced,
            dropped = dropped,
            retriable = retriable,
            remaining = remaining,
        )
    }

    private suspend fun dispatch(op: DirtyOperationEntity, scope: AuthScopeSnapshot): WriteOutcome {
        val contentId = op.targetContentId
        val result = when (op.opKind) {
            OutboxOperation.SET_WATCHED -> {
                val watched = OutboxOperation.decodeBooleanPayload(op.payloadJson)
                if (watched) personalDataApi.markWatched(contentId, scope) else personalDataApi.markUnwatched(contentId, scope)
            }

            OutboxOperation.SET_FAVORITE -> {
                val favorite = OutboxOperation.decodeBooleanPayload(op.payloadJson)
                if (favorite) personalDataApi.addFavorite(contentId, scope) else personalDataApi.removeFavorite(contentId, scope)
            }

            OutboxOperation.SET_RATING -> {
                val rating = OutboxOperation.decodeRatingPayload(op.payloadJson)
                if (rating == null) personalDataApi.deleteRating(contentId, scope) else personalDataApi.setRating(contentId, rating, scope)
            }

            OutboxOperation.SET_POSITION -> {
                // Replay happens after the playback session is gone, so use the
                // sessionless content-level sync. force_overwrite=false → the
                // server takes GREATEST(position), so a stale offline replay
                // never rewinds a further position from another device.
                val (position, duration) = OutboxOperation.decodePositionPayload(op.payloadJson)
                personalDataApi.syncProgress(
                    SyncProgressRequest(
                        items = listOf(
                            SyncProgressItem(
                                mediaItemId = contentId,
                                position = position,
                                duration = duration ?: 0.0,
                                forceOverwrite = false,
                            ),
                        ),
                    ),
                    scope,
                )
            }

            OutboxOperation.SET_EBOOK_PROGRESS -> return dispatchEbookProgress(op, contentId, scope)

            else -> {
                // This engine version cannot send this kind. Drop it rather than
                // retry forever.
                Log.w(TAG, "Dropping un-replayable outbox op kind=${op.opKind} id=${op.id}")
                return WriteOutcome.TERMINAL
            }
        }
        return result.toWriteOutcome()
    }

    /**
     * Ebook progress replay with a monotonic guard: the server PUT is plain LWW, so
     * a stale offline replay could rewind reading position made on another device.
     * GET the server's progress first and only PUT when our local progress is
     * further ahead (mirrors the retired EbookProgressSyncer's guard). A 404 means
     * the server has no progress yet → push.
     */
    private suspend fun dispatchEbookProgress(
        op: DirtyOperationEntity,
        contentId: String,
        scope: AuthScopeSnapshot,
    ): WriteOutcome {
        val payload = OutboxOperation.decodeEbookProgressPayload(op.payloadJson)
        val request = SaveEbookProgressRequest(
            fileId = payload.fileId,
            location = payload.location,
            progress = payload.progress,
        )
        return when (val server = ebookReaderApi.getProgress(contentId, scope)) {
            is ApiResult.Success ->
                if (payload.progress > server.data.progress) {
                    ebookReaderApi.saveProgress(contentId, request, scope).toWriteOutcome()
                } else {
                    // Server is already at/ahead — nothing to push; the op is done.
                    WriteOutcome.SYNCED
                }
            is ApiResult.NetworkError -> WriteOutcome.RETRIABLE
            is ApiResult.Error ->
                if (server.code == 404) {
                    ebookReaderApi.saveProgress(contentId, request, scope).toWriteOutcome()
                } else {
                    server.toWriteOutcome()
                }
        }
    }

    /** Capped exponential backoff: 30s · 2^attempt, ceiling 6h. */
    private fun backoffMs(attemptCount: Int): Long {
        val shift = attemptCount.coerceIn(0, 20)
        val delay = BASE_BACKOFF_MS shl shift
        return if (delay <= 0L || delay > MAX_BACKOFF_MS) MAX_BACKOFF_MS else delay
    }

    companion object {
        private const val TAG = "SyncEngine"
        private const val BASE_BACKOFF_MS = 30_000L
        private const val MAX_BACKOFF_MS = 6L * 60 * 60 * 1000

        // Backstop against a pathological re-due loop; a normal drain terminates
        // long before this because each op is deleted or pushed into the future.
        private const val MAX_BATCHES = 1_000
    }
}
