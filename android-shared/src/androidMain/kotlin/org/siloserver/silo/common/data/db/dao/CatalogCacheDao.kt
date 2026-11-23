package org.siloserver.silo.common.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.siloserver.silo.common.data.db.entity.CatalogCacheEntity

@Dao
interface CatalogCacheDao {

    @Query("""
        DELETE FROM catalog_cache WHERE serverId = :serverId AND accountId = :accountId AND profileId = :profileId
        AND (cacheKey LIKE 'item-detail:%' OR cacheKey LIKE 'seasons:%' OR cacheKey LIKE 'episodes:%')
    """)
    suspend fun deleteDetailMetadata(serverId: String, accountId: String, profileId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: CatalogCacheEntity)

    @Query("SELECT * FROM catalog_cache WHERE serverId = :serverId AND accountId = :accountId AND profileId = :profileId AND cacheKey = :cacheKey")
    suspend fun get(serverId: String, accountId: String, profileId: String, cacheKey: String): CatalogCacheEntity?

    @Query("DELETE FROM catalog_cache WHERE serverId = :serverId AND accountId = :accountId AND profileId = :profileId AND cacheKey = :cacheKey")
    suspend fun delete(serverId: String, accountId: String, profileId: String, cacheKey: String)
}
