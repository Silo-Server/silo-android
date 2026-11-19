package org.siloserver.silo.common.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.siloserver.silo.common.data.db.entity.DownloadEntity

/** Count and size of one account's downloads, for [DownloadDao.summaryForAccount]. */
data class DownloadSummaryRow(val count: Int, val totalBytes: Long)

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
     * accountId, profileId, mediaFileId)` primary key nor the unique `recordId` index.
     * Returns the new rowId, or -1 when a conflict caused the insert to be
     * ignored. Used by the legacy import so an existing Room row always wins
     * atomically, without [upsert]'s REPLACE (which would delete a row that
     * merely shares `recordId`).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(row: DownloadEntity): Long

    @Query(
        "SELECT * FROM downloads " +
            "WHERE serverId = :serverId AND accountId = :accountId AND profileId = :profileId AND mediaFileId = :mediaFileId",
    )
    suspend fun get(serverId: String, accountId: String, profileId: String, mediaFileId: Int): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE recordId = :recordId")
    suspend fun getByRecordId(recordId: String): DownloadEntity?

    @Query(
        "SELECT * FROM downloads " +
            "WHERE serverId = :serverId AND accountId = :accountId AND profileId = :profileId AND contentId = :contentId",
    )
    suspend fun getByContent(serverId: String, accountId: String, profileId: String, contentId: String): List<DownloadEntity>

    @Query(
        "SELECT * FROM downloads WHERE serverId = :serverId AND accountId = :accountId AND profileId = :profileId ORDER BY updatedAtMs DESC",
    )
    suspend fun getAll(serverId: String, accountId: String, profileId: String): List<DownloadEntity>

    @Query(
        "SELECT * FROM downloads " +
            "WHERE serverId = :serverId AND accountId = :accountId AND profileId = :profileId AND status = :status " +
            "ORDER BY updatedAtMs DESC",
    )
    suspend fun getByStatus(serverId: String, accountId: String, profileId: String, status: String): List<DownloadEntity>

    @Query(
        "DELETE FROM downloads " +
            "WHERE serverId = :serverId AND accountId = :accountId AND profileId = :profileId AND mediaFileId = :mediaFileId",
    )
    suspend fun delete(serverId: String, accountId: String, profileId: String, mediaFileId: Int)

    @Query("DELETE FROM downloads WHERE serverId = :serverId AND accountId = :accountId AND profileId = :profileId")
    suspend fun deleteAll(serverId: String, accountId: String, profileId: String)

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

    /** Record ids of failed downloads, across scopes; re-queried on every downloads write. */
    @Query("SELECT recordId FROM downloads WHERE status = 'failed' ORDER BY recordId")
    fun observeFailedRecordIds(): Flow<List<String>>

    /** First download (any scope) for a content id — offline player contentId→file lookup. */
    @Query("SELECT * FROM downloads WHERE contentId = :contentId LIMIT 1")
    suspend fun findByContentId(contentId: String): DownloadEntity?

    /** First download (any scope) for a media file id — scope-agnostic delete cleanup. */
    @Query("SELECT * FROM downloads WHERE mediaFileId = :mediaFileId LIMIT 1")
    suspend fun findByFileId(mediaFileId: Int): DownloadEntity?

    @Query("DELETE FROM downloads WHERE serverId = :serverId")
    suspend fun deleteAllForServer(serverId: String)

    /**
     * Every profile's downloads of one account: how many, and the bytes they
     * hold on this device (a completed download's file size, a partial's bytes
     * received so far).
     */
    @Query(
        "SELECT COUNT(*) AS count, " +
            "COALESCE(SUM(CASE WHEN status = 'completed' THEN fileSize ELSE bytesSent END), 0) AS totalBytes " +
            "FROM downloads " +
            "WHERE serverId = :serverId AND accountId = :accountId",
    )
    suspend fun summaryForAccount(serverId: String, accountId: String): DownloadSummaryRow
}
