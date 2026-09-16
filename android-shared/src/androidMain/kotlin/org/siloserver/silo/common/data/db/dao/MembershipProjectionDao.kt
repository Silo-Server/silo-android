package org.siloserver.silo.common.data.db.dao

import androidx.room.*
import org.siloserver.silo.common.data.db.entity.MembershipProjectionEntity

@Dao
interface MembershipProjectionDao {
    @Query("SELECT COUNT(*) FROM membership_projection")
    fun observeChanges(): kotlinx.coroutines.flow.Flow<Int>

    @Query("SELECT command.* FROM dirty_operations command JOIN membership_projection projection " +
        "ON projection.commandId = command.id AND projection.authority = command.membershipAuthority " +
        "WHERE command.membershipAuthority = :authority AND command.state IN ('membership_ready', 'membership_reconcile', 'membership_paused') " +
        "ORDER BY command.id LIMIT :limit")
    suspend fun pending(authority: String, limit: Int): List<org.siloserver.silo.common.data.db.entity.DirtyOperationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(projection: MembershipProjectionEntity)

    @Query("SELECT * FROM membership_projection WHERE authority = :authority AND itemId = :itemId AND kind = :kind")
    suspend fun get(authority: String, itemId: String, kind: String): MembershipProjectionEntity?

    @Query("UPDATE membership_projection SET disposition = :disposition WHERE authority = :authority AND commandId = :id")
    suspend fun resolve(authority: String, id: Long, disposition: String): Int
}
