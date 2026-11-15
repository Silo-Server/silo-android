package org.siloserver.silo.common.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.siloserver.silo.common.data.db.entity.DownloadDeletionEntity

/**
 * Pending download-record deletions (Track B offline-first delete). Small table:
 * one row per deleted-but-not-yet-server-confirmed download.
 */
@Dao
interface DownloadDeletionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: DownloadDeletionEntity)

    /** Every pending recordId across all scopes — recordIds are server-unique, so
     *  this is safe to use as a global ghost filter. */
    @Query("SELECT recordId FROM download_deletions")
    suspend fun allRecordIds(): List<String>

    @Query("SELECT * FROM download_deletions WHERE serverId = :serverId AND accountId = :accountId AND profileId = :profileId")
    suspend fun forScope(serverId: String, accountId: String, profileId: String): List<DownloadDeletionEntity>

    @Query("DELETE FROM download_deletions WHERE serverId = :serverId AND accountId = :accountId AND profileId = :profileId AND recordId = :recordId")
    suspend fun delete(serverId: String, accountId: String, profileId: String, recordId: String)
}
