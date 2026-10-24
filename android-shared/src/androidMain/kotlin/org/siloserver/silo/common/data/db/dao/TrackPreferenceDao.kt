package org.siloserver.silo.common.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.siloserver.silo.common.data.db.entity.TrackPreferenceEntity

@Dao
interface TrackPreferenceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: TrackPreferenceEntity)

    @Query(
        "SELECT * FROM track_preferences WHERE serverId = :serverId AND profileId = :profileId " +
            "AND kind = :kind AND prefKey = :prefKey",
    )
    suspend fun get(serverId: String, profileId: String, kind: String, prefKey: String): TrackPreferenceEntity?

    @Query("SELECT * FROM track_preferences WHERE serverId = :serverId AND profileId = :profileId AND pending IS NOT NULL")
    suspend fun pending(serverId: String, profileId: String): List<TrackPreferenceEntity>
}
