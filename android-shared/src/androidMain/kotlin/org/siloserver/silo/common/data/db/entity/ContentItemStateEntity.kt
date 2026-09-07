package org.siloserver.silo.common.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Local-first projection of **content-level** user state — watched, rating,
 * and favorite. Keyed `(serverId, profileId, contentId)` with no fileId: these
 * server APIs (`PersonalDataApi.markWatched`/`setRating`/`addFavorite`, etc.)
 * are keyed by item id only, and the mutations can fire from a card menu or
 * detail screen before any file-level row exists for the item.
 *
 * Written optimistically by [org.siloserver.silo.common.data.sync] /
 * `RoomUserItemStateRepository` alongside a single content-scoped outbox op
 * (the op's `targetFileId` is null). `clientUpdatedAtMs` drives last-writer-wins;
 * `serverUpdatedAtMs` is the last-known server timestamp (null until first ack).
 *
 * Nullable columns mean "unknown locally" — a row may carry only the field a
 * given mutation touched (e.g. a favorite toggle that has never seen a rating).
 */
@Entity(
    tableName = "content_item_state",
    primaryKeys = ["serverId", "profileId", "contentId"],
)
data class ContentItemStateEntity(
    val serverId: String,
    val profileId: String,
    val contentId: String,
    val watched: Boolean?,
    val ratingValue: Int?,
    val favorite: Boolean?,
    val clientUpdatedAtMs: Long,
    val serverUpdatedAtMs: Long?,
    /**
     * When the user last set [watched] on this exact item. A container
     * (season) confirmation skips children whose own intent is newer than the
     * container action, even after that intent's outbox op has drained.
     */
    @ColumnInfo(defaultValue = "0") val watchedIntentAtMs: Long = 0L,
)
