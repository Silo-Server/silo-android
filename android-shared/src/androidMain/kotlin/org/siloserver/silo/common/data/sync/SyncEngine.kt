package org.siloserver.silo.common.data.sync

import android.util.Log
import androidx.room.withTransaction
import org.siloserver.silo.common.data.db.SiloDatabase
import org.siloserver.silo.common.data.db.entity.DirtyOperationEntity
import org.siloserver.silo.model.ebook.SaveEbookProgressRequest
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.api.DownloadProgressReceipt
import org.siloserver.silo.network.api.EbookReaderApi
import org.siloserver.silo.network.api.PersonalDataApi
import org.siloserver.silo.network.isUnansweredSend
import org.siloserver.silo.repository.port.WriteOutcome
import org.siloserver.silo.repository.port.toWriteOutcome

/**
 * Drains typed membership writes, ebook progress, and download playback
 * progress (v2 `SET_POSITION`). Legacy personal-data rows remain unchanged and unsent.
 */
class SyncEngine(
    private val db: SiloDatabase,
    private val personalDataApi: PersonalDataApi,
    private val ebookReaderApi: EbookReaderApi,
    private val snapshotProvider: suspend () -> AuthScopeSnapshot?,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val batchLimit: Int = 50,
    private val memberships: org.siloserver.silo.repository.port.MembershipPort? = null,
    private val ebookAuthorities: org.siloserver.silo.network.DurableLoginAuthorityProvider? = null,
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
     *
     * Only the snapshot's own account's rows are drained, and the snapshot names
     * that account ([AuthScopeSnapshot.accountId]), so every replay is sent with
     * the credentials of the account that recorded it. Two accounts on one
     * server can share a profile id, so server + profile alone would replay one
     * account's writes as the other.
     */
    suspend fun drainOnce(): DrainResult {
        memberships?.dispatch(batchLimit.coerceAtMost(100))
        val scope = snapshotProvider() ?: return DrainResult()
        val serverId = scope.serverId
        // Ops are always enqueued with a profile; no profile → nothing to drain.
        val profileId = scope.profileId ?: return DrainResult()
        // A scope with no saved account (a temporary overlay) recorded under ''.
        val accountId = scope.accountId.orEmpty()

        // A download position stranded in flight may already be applied, and
        // the endpoint is not retryable: settle it, never resend it.
        dao.inFlightDownloadProgress(serverId, accountId, profileId).forEach { settleDownloadProgress(it) }
        // Reclaim crash-stranded in-flight rows before claiming new work.
        dao.deleteSupersededInFlight(serverId, accountId, profileId)
        dao.resetInFlightToPending(serverId, accountId, profileId)

        var synced = 0
        var dropped = 0
        var retriable = 0

        var batches = 0
        while (batches++ < MAX_BATCHES) {
            val nowMs = now()
            // The DAO selects only due target heads and applies its older-sibling
            // anti-join before LIMIT. This preserves per-item FIFO even when the
            // backing-off predecessor lies outside the current SQL page.
            val batch = dao.dueTargetHeads(serverId, accountId, profileId, nowMs, batchLimit)
            if (batch.isEmpty()) break

            for (op in batch) {
                if (ebookAuthorities != null && op.opKind == OutboxOperation.SET_EBOOK_PROGRESS) {
                    val payload = runCatching { OutboxOperation.decodeEbookProgressPayload(op.payloadJson) }.getOrNull()
                    val authority = ebookAuthorities.snapshotDurableLoginAuthority()
                    if (authority == null || !authority.scope.isSameIdentityAs(scope))
                        return DrainResult(synced, dropped, retriable + 1,
                            dao.runnableLegacyCountForScope(serverId, accountId, profileId) + (memberships?.readyCount() ?: 0))
                    if (op.opVersion < 2 || payload?.updatedAt == null || payload.loginId != authority.loginId ||
                        payload.origin != scope.serverUrl) {
                        dao.quarantineEbookProgress(op.id)
                        continue
                    }
                }
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
                        if (op.opKind == OutboxOperation.SET_POSITION) settleDownloadProgress(op) else dao.deleteById(op.id)
                        synced++
                    }
                    WriteOutcome.TERMINAL -> if (op.opKind == OutboxOperation.SET_POSITION) {
                        settleDownloadProgress(op)
                        dropped++
                    } else {
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
                        if (superseded) {
                            dropped++
                        } else {
                            retriable++
                        }
                    }
                }
            }
        }

        // Count remaining for the scope we just drained AND, if the user switched
        // mid-drain, for whatever scope is active NOW (re-snapshot). Counting only
        // the end scope stranded the drained scope's queued work on a switch: the
        // worker saw remaining=0, reported success and ended the retry chain.
        // OutboxSyncStarter now also triggers on a profile change, so a switch is
        // no longer silent, but this stays the in-drain defence — the switch can
        // land between the drain and the count. Including the end scope covers an
        // activation enqueue dropped by ExistingWorkPolicy.KEEP while this worker
        // was running.
        var remaining = dao.runnableLegacyCountForScope(serverId, accountId, profileId)
        val endScope = snapshotProvider()
        val endProfileId = endScope?.profileId
        val endAccountId = endScope?.accountId.orEmpty()
        if (endScope != null && endProfileId != null &&
            (endScope.serverId != serverId || endAccountId != accountId || endProfileId != profileId)
        ) {
            remaining += dao.runnableLegacyCountForScope(endScope.serverId, endAccountId, endProfileId)
        }

        remaining += memberships?.readyCount() ?: 0
        return DrainResult(
            synced = synced,
            dropped = dropped,
            retriable = retriable,
            remaining = remaining,
        )
    }

    private suspend fun dispatch(op: DirtyOperationEntity, scope: AuthScopeSnapshot): WriteOutcome {
        if (op.opKind == OutboxOperation.SET_POSITION && op.opVersion >= 2) return dispatchDownloadProgress(op, scope)
        check(op.opKind == OutboxOperation.SET_EBOOK_PROGRESS) { "Legacy personal-data intents must remain held" }
        return dispatchEbookProgress(op, op.targetContentId, scope)
    }

    /** One queued download position, sent with the time it was played. */
    private suspend fun dispatchDownloadProgress(op: DirtyOperationEntity, scope: AuthScopeSnapshot): WriteOutcome {
        val (position, duration) = OutboxOperation.decodePositionPayload(op.payloadJson)
        val result = personalDataApi.syncDownloadProgress(
            scope = scope,
            contentId = op.targetContentId,
            positionSeconds = position,
            durationSeconds = duration,
            updatedAt = java.time.Instant.ofEpochMilli(op.createdAtMs).toString(),
        )
        return when {
            result is ApiResult.Success -> when (result.data) {
                DownloadProgressReceipt.ACCEPTED -> WriteOutcome.SYNCED
                DownloadProgressReceipt.NOT_FOUND -> WriteOutcome.TERMINAL
                DownloadProgressReceipt.RETRY -> WriteOutcome.RETRIABLE
            }
            // A request the server can never accept is dropped. So is one it may
            // already have applied (an unreadable answer, or no answer after it
            // left): /sync/progress is not retryable, and a resend after the
            // server clamps a fast clock's updated_at could beat newer progress.
            result is ApiResult.Error && result.code in FINAL_PROGRESS_REFUSALS -> WriteOutcome.TERMINAL
            result is ApiResult.Error && result.error == "invalid_response" -> WriteOutcome.TERMINAL
            result is ApiResult.NetworkError && result.isUnansweredSend -> WriteOutcome.TERMINAL
            // Not applied: a stale profile proof (403), identity change, upgrade
            // gate, or a connection that never opened. Send it later.
            else -> WriteOutcome.RETRIABLE
        }
    }

    /**
     * The server has the position (or refused it for good), so from now on its
     * answer stands: drop the op and the local copy it sent. A newer sample
     * queued meanwhile keeps its row until its own op settles.
     */
    private suspend fun settleDownloadProgress(op: DirtyOperationEntity) {
        db.withTransaction {
            dao.deleteById(op.id)
            if (dao.countNewerForTargetKind(op.serverId, op.accountId, op.profileId, op.targetContentId, op.opKind, op.id) == 0) {
                // Stamped with the event it settles, so a sample taken after it but
                // written after this still counts as newer.
                userStateDao.clearPlaybackProgressBefore(
                    op.serverId, op.accountId, op.profileId, op.targetContentId,
                    admittedAtMs = op.createdAtMs + 1, updatedAtMs = op.createdAtMs,
                )
            }
        }
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
            updatedAt = payload.updatedAt,
        )
        if (ebookAuthorities != null) {
            val authority = ebookAuthorities.snapshotDurableLoginAuthority()
            if (payload.loginId != authority?.loginId || authority?.scope?.isSameIdentityAs(scope) != true)
                return WriteOutcome.RETRIABLE
            // The server orders the original event time, including newer backward moves.
            val result = ebookReaderApi.saveProgress(contentId, request, scope)
            return if (result is ApiResult.Error && result.code == 0) WriteOutcome.RETRIABLE
                else result.toWriteOutcome()
        }
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

        /** Malformed or for a title the server no longer has. */
        private val FINAL_PROGRESS_REFUSALS = setOf(400, 404, 422)

    }
}
