package org.siloserver.silo.common.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.siloserver.silo.common.data.db.entity.DownloadEntity

/** The artwork columns of one downloads row, for [DownloadDao.observeSavedArtwork]. */
data class DownloadArtworkRow(
    val recordId: String,
    val posterThumbhash: String?,
    val offlinePosterPath: String?,
    val offlineSeriesPosterPath: String?,
    val seriesPosterThumbhash: String?,
)

/**
 * Downloads projection access. Scoped reads seed the Downloads tab and the
 * offline player without a server round-trip; status scans drive
 * in-progress/queued sections.
 */
@Dao
interface DownloadDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: DownloadEntity)

    /**
     * Insert only when no conflicting row exists — neither the `(serverId,
     * profileId, mediaFileId)` primary key nor the unique `recordId` index.
     * Returns the new rowId, or -1 when a conflict caused the insert to be
     * ignored. Used by the legacy import so an existing Room row always wins
     * atomically, without [upsert]'s REPLACE (which would delete a row that
     * merely shares `recordId`).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(row: DownloadEntity): Long

    @Query(
        "SELECT * FROM downloads " +
            "WHERE serverId = :serverId AND profileId = :profileId AND mediaFileId = :mediaFileId",
    )
    suspend fun get(serverId: String, profileId: String, mediaFileId: Int): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE recordId = :recordId")
    suspend fun getByRecordId(recordId: String): DownloadEntity?

    @Query(
        "SELECT * FROM downloads " +
            "WHERE serverId = :serverId AND profileId = :profileId AND contentId = :contentId",
    )
    suspend fun getByContent(serverId: String, profileId: String, contentId: String): List<DownloadEntity>

    @Query(
        "SELECT * FROM downloads WHERE serverId = :serverId AND profileId = :profileId ORDER BY updatedAtMs DESC",
    )
    suspend fun getAll(serverId: String, profileId: String): List<DownloadEntity>

    @Query(
        "SELECT * FROM downloads " +
            "WHERE serverId = :serverId AND profileId = :profileId AND status = :status " +
            "ORDER BY updatedAtMs DESC",
    )
    suspend fun getByStatus(serverId: String, profileId: String, status: String): List<DownloadEntity>

    @Query(
        "DELETE FROM downloads " +
            "WHERE serverId = :serverId AND profileId = :profileId AND mediaFileId = :mediaFileId",
    )
    suspend fun delete(serverId: String, profileId: String, mediaFileId: Int)

    @Query("DELETE FROM downloads WHERE serverId = :serverId AND profileId = :profileId")
    suspend fun deleteAll(serverId: String, profileId: String)

    // -- Cross-scope (downloads metadata replaces the old sidecar tree-walk) --

    @Query("SELECT * FROM downloads ORDER BY updatedAtMs DESC")
    suspend fun getAllAcrossScopes(): List<DownloadEntity>

    /**
     * Rows that carry saved artwork, across scopes. Room re-queries on every
     * downloads write (progress and status included), so collectors should
     * apply `distinctUntilChanged` and react only when artwork actually changes.
     */
    @Query(
        "SELECT recordId, posterThumbhash, offlinePosterPath, offlineSeriesPosterPath, seriesPosterThumbhash " +
            "FROM downloads WHERE posterThumbhash IS NOT NULL OR offlinePosterPath IS NOT NULL " +
            "OR offlineSeriesPosterPath IS NOT NULL OR seriesPosterThumbhash IS NOT NULL ORDER BY recordId",
    )
    fun observeSavedArtwork(): Flow<List<DownloadArtworkRow>>

    /** First download (any scope) for a content id — offline player contentId→file lookup. */
    @Query("SELECT * FROM downloads WHERE contentId = :contentId LIMIT 1")
    suspend fun findByContentId(contentId: String): DownloadEntity?

    /** First download (any scope) for a media file id — scope-agnostic delete cleanup. */
    @Query("SELECT * FROM downloads WHERE mediaFileId = :mediaFileId LIMIT 1")
    suspend fun findByFileId(mediaFileId: Int): DownloadEntity?

    @Query("DELETE FROM downloads WHERE serverId = :serverId")
    suspend fun deleteAllForServer(serverId: String)
}
