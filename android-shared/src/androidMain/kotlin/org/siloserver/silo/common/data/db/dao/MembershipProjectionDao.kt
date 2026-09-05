package org.siloserver.silo.common.data.db.dao

import androidx.room.*
import org.siloserver.silo.common.data.db.entity.MembershipProjectionEntity

@Dao
interface MembershipProjectionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(projection: MembershipProjectionEntity)

    @Query("SELECT * FROM membership_projection WHERE authority = :authority AND itemId = :itemId AND kind = :kind")
    suspend fun get(authority: String, itemId: String, kind: String): MembershipProjectionEntity?

    @Query("UPDATE membership_projection SET disposition = :disposition WHERE authority = :authority AND commandId = :id")
    suspend fun resolve(authority: String, id: Long, disposition: String): Int
}
