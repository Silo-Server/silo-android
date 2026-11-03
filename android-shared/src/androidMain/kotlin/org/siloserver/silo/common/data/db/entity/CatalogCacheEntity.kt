package org.siloserver.silo.common.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Generic key-value JSON cache for catalog browse reads (Track B), keyed by
 * `(serverId, accountId, profileId, cacheKey)`. [cacheKey] is e.g. `"libraries"` or
 * `"library:{id}"`; [json] is the serialized response. A single flexible table
 * (vs one per read) — the goal is "serve the last response offline", not query it.
 */
@Entity(tableName = "catalog_cache", primaryKeys = ["serverId", "accountId", "profileId", "cacheKey"])
data class CatalogCacheEntity(
    val serverId: String,
    /** Saved account ([org.siloserver.silo.model.server.AccountEntry.localAccountId]); '' = written before accounts existed. */
    @ColumnInfo(defaultValue = "")
    val accountId: String,
    val profileId: String,
    val cacheKey: String,
    val json: String,
    val cachedAtMs: Long,
)
