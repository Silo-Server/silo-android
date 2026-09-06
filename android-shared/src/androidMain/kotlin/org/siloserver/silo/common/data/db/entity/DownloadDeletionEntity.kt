package org.siloserver.silo.common.data.db.entity

import androidx.room.Entity

/**
 * Durable tombstone for a download whose on-device copy the user deleted but whose
 * **server** download record may not yet be gone (Track B offline-first delete).
 * Keyed by `(serverId, profileId, recordId)` so a scope-local "confirmed gone"
 * clear can't drop another scope's pending delete. [mediaFileId] lets a crash
 * between enqueue and on-device byte deletion be finished idempotently next launch.
 */
@Entity(tableName = "download_deletions", primaryKeys = ["serverId", "profileId", "recordId"])
data class DownloadDeletionEntity(
    val serverId: String,
    val profileId: String,
    val recordId: String,
    val mediaFileId: Int?,
    val enqueuedAtMs: Long,
    val loginId: String? = null,
    val origin: String? = null,
    val deviceId: String? = null,
)
