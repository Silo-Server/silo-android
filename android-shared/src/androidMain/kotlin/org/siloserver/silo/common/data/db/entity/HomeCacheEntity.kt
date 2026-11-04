package org.siloserver.silo.common.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * Offline read cache of the resolved home layout (Track B), one row per
 * `(serverId, accountId, profileId)`. [sectionsJson] is a serialized `List<ResolvedSection>`
 * (the models are `@Serializable`, so a JSON blob avoids a per-item schema —
 * the goal is "render the last home offline", not query it). [cachedAtMs] backs
 * an optional staleness/"offline" hint.
 */
@Entity(tableName = "home_cache", primaryKeys = ["serverId", "accountId", "profileId"])
data class HomeCacheEntity(
    val serverId: String,
    /** Saved account ([org.siloserver.silo.model.server.AccountEntry.localAccountId]); '' = written before accounts existed. */
    @ColumnInfo(defaultValue = "")
    val accountId: String,
    val profileId: String,
    val sectionsJson: String,
    val cachedAtMs: Long,
)
