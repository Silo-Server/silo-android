package org.siloserver.silo.common.data.sync

import org.siloserver.silo.common.data.db.dao.ContentItemStateDao
import org.siloserver.silo.common.data.db.dao.UserItemStateDao
import org.siloserver.silo.common.data.db.entity.DirtyOperationEntity

/**
 * Revert a content-level optimistic projection field to "unknown" after its
 * outbox op is dropped on a TERMINAL server rejection (4xx). The card overlay
 * then defers to server state for that field — so a rejected mark-watched /
 * favorite doesn't persist as a fake local state across cold starts.
 *
 * Shared by the inline resolve path (`RoomUserItemStateRepository`) and the
 * background drain (`SyncEngine`). No-op for non-content ops (position/ebook).
 */
internal suspend fun ContentItemStateDao.revertForTerminalOp(op: DirtyOperationEntity) {
    when (op.opKind) {
        OutboxOperation.SET_WATCHED -> clearWatched(op.serverId, op.profileId, op.targetContentId)
        OutboxOperation.SET_FAVORITE -> clearFavorite(op.serverId, op.profileId, op.targetContentId)
        OutboxOperation.SET_RATING -> clearRating(op.serverId, op.profileId, op.targetContentId)
        else -> Unit
    }
}

/** Restore resume rows cleared by a terminally-rejected watched mutation. */
internal suspend fun UserItemStateDao.restorePlaybackProgressForTerminalOp(op: DirtyOperationEntity) {
    if (op.opKind != OutboxOperation.SET_WATCHED) return
    val payload = runCatching { OutboxOperation.decodeWatchedPayload(op.payloadJson) }.getOrNull() ?: return
    payload.clearedProgress.forEach { progress ->
        restorePlaybackProgressIfUnchanged(
            serverId = op.serverId,
            profileId = op.profileId,
            contentId = op.targetContentId,
            fileId = progress.fileId,
            positionSeconds = progress.positionSeconds,
            previousUpdatedAtMs = progress.previousClientUpdatedAtMs,
            clearedAtMs = progress.clearedAtMs,
        )
    }
}
