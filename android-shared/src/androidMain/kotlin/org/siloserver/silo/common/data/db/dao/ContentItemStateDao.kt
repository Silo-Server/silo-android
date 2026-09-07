package org.siloserver.silo.common.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.siloserver.silo.common.data.db.entity.ContentItemStateEntity

/**
 * Content-level user-state projection access. Partial-field merges
 * (a favorite toggle must not wipe an existing rating) are done as a
 * read-modify-write in the caller's transaction via [get] + [upsert].
 */
@Dao
interface ContentItemStateDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ContentItemStateEntity)

    @Query(
        "SELECT * FROM content_item_state " +
            "WHERE serverId = :serverId AND profileId = :profileId AND contentId = :contentId",
    )
    suspend fun get(serverId: String, profileId: String, contentId: String): ContentItemStateEntity?

    @Query(
        "SELECT * FROM content_item_state " +
            "WHERE serverId = :serverId AND profileId = :profileId AND favorite = 1",
    )
    suspend fun favorites(serverId: String, profileId: String): List<ContentItemStateEntity>

    /** Batch read for the card overlay. */
    @Query(
        "SELECT * FROM content_item_state " +
            "WHERE serverId = :serverId AND profileId = :profileId AND contentId IN (:contentIds)",
    )
    suspend fun getForContentIds(serverId: String, profileId: String, contentIds: List<String>): List<ContentItemStateEntity>

    // Revert a single optimistic field to "unknown" after its outbox op is dropped
    // (terminal server rejection) — the overlay then defers to server state.
    // A rejected watched intent is no longer an intent: its stamp must not
    // shield the row from a later container confirmation.
    @Query(
        "UPDATE content_item_state SET watched = NULL, watchedIntentAtMs = 0 " +
            "WHERE serverId = :serverId AND profileId = :profileId AND contentId = :contentId",
    )
    suspend fun clearWatched(serverId: String, profileId: String, contentId: String)

    @Query("UPDATE content_item_state SET favorite = NULL WHERE serverId = :serverId AND profileId = :profileId AND contentId = :contentId")
    suspend fun clearFavorite(serverId: String, profileId: String, contentId: String)

    @Query("UPDATE content_item_state SET ratingValue = NULL WHERE serverId = :serverId AND profileId = :profileId AND contentId = :contentId")
    suspend fun clearRating(serverId: String, profileId: String, contentId: String)

    @Query("DELETE FROM content_item_state WHERE serverId = :serverId AND profileId = :profileId AND contentId = :contentId")
    suspend fun delete(serverId: String, profileId: String, contentId: String)

    /** Highest watched-intent stamp ever persisted; seeds the monotonic sequence after a restart. */
    @Query("SELECT COALESCE(MAX(watchedIntentAtMs), 0) FROM content_item_state")
    suspend fun maxWatchedIntentAtMs(): Long
}
