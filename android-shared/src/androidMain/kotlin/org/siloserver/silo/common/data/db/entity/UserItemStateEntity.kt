package org.siloserver.silo.common.data.db.entity

import androidx.room.Entity
import androidx.room.Index

/**
 * Local-first projection of **file-level** user state — resume position, track
 * selections, and ebook reading position.
 *
 * Identity is `(serverId, profileId, contentId, fileId)` because Silo scopes
 * all user state by server + profile (see `ScopedJsonFileStore` and
 * `DownloadStorage`), and progress/track/CFI are file-level (a multi-file
 * content item — e.g. a movie with multiple versions, or an audiobook with
 * many tracks — has independent positions per file).
 *
 * Content-level state (watched / rating / favorite) lives in a separate
 * [ContentItemStateEntity] keyed `(serverId, profileId, contentId)`: those
 * server APIs are keyed by item id only (no fileId), and the mutations can
 * fire before any file row exists, so they do not belong on a file row.
 *
 * Track selections are stored as **stable fingerprints**
 * `(index|language|codec|title|forced)` rather than raw UI indices, so a
 * selection survives a re-extracted track list whose ordering changed.
 *
 * `clientUpdatedAtMs` drives last-writer-wins + resume scans; `serverUpdatedAtMs`
 * is the projection's last-known server timestamp (null until first ack).
 */
@Entity(
    tableName = "user_item_state",
    primaryKeys = ["serverId", "profileId", "contentId", "fileId"],
    indices = [
        Index(value = ["serverId", "profileId", "contentId"]),
        Index(value = ["serverId", "profileId", "clientUpdatedAtMs"]),
    ],
)
data class UserItemStateEntity(
    val serverId: String,
    val profileId: String,
    val contentId: String,
    val fileId: Int,
    val positionSeconds: Double,
    val durationSeconds: Double?,
    // Stable selection fingerprints: (index|language|codec|title|forced), not raw UI index.
    val audioFingerprint: String?,
    val subtitleFingerprint: String?,
    val cfi: String?,
    val readProgress: Double?,
    val clientUpdatedAtMs: Long,
    val serverUpdatedAtMs: Long?,
    /** Timestamp of the watch/progress mutation, excluding ratings and track choices. */
    val positionUpdatedAtMs: Long? = null,
)
