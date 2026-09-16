package org.siloserver.silo.common.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

/**
 * Cross-table purge for a server that is no longer in the registry.
 *
 * Removing a server wiped its tokens and its registry entry and nothing else,
 * so "remove this server to reclaim space" reclaimed none: every Room row
 * survived. Worse, `idFor()` is base64 of the normalised URL, so re-adding the
 * same server resurrected its Downloads list, resume positions, cached home and
 * catalog rows, and any pending outbox ops — under an identity the user thought
 * they had deleted.
 *
 * The queries live together rather than one per DAO because they are only ever
 * used as a set, and a table added later that nobody adds here is exactly the
 * silent gap this replaces — see [knownServerIds], which fails loudly the same
 * way (an unlisted table simply never reports orphans).
 */
@Dao
interface ServerPurgeDao {

    /**
     * Every serverId with rows in any scoped table.
     *
     * Orphans are computed as this minus the registry rather than recorded at
     * removal time, which makes the purge idempotent, re-runnable at startup,
     * and — the reason it is worth doing this way — able to clean installs that
     * removed servers before the purge existed.
     */
    @Query(
        "SELECT DISTINCT serverId FROM downloads " +
            "UNION SELECT DISTINCT serverId FROM download_subscriptions " +
            "UNION SELECT DISTINCT serverId FROM download_deletions " +
            "UNION SELECT DISTINCT serverId FROM dirty_operations " +
            "UNION SELECT DISTINCT serverId FROM content_item_state " +
            "UNION SELECT DISTINCT serverId FROM user_item_state " +
            "UNION SELECT DISTINCT serverId FROM home_cache " +
            "UNION SELECT DISTINCT serverId FROM catalog_cache",
    )
    suspend fun knownServerIds(): List<String>

    @Query("SELECT recordId FROM downloads WHERE serverId = :serverId")
    suspend fun downloadRecordIdsForServer(serverId: String): List<String>

    @Query("SELECT mediaFileId FROM downloads WHERE serverId = :serverId")
    suspend fun downloadFileIdsForServer(serverId: String): List<Int>

    @Query("DELETE FROM downloads WHERE serverId = :serverId")
    suspend fun deleteDownloads(serverId: String)

    @Query("DELETE FROM download_subscriptions WHERE serverId = :serverId")
    suspend fun deleteDownloadSubscriptions(serverId: String)

    @Query("DELETE FROM download_deletions WHERE serverId = :serverId")
    suspend fun deleteDownloadDeletions(serverId: String)

    @Query("DELETE FROM dirty_operations WHERE serverId = :serverId AND state != 'legacy_membership_quarantined'")
    suspend fun deleteDirtyOperations(serverId: String)

    @Query("DELETE FROM content_item_state WHERE serverId = :serverId")
    suspend fun deleteContentItemState(serverId: String)

    @Query("DELETE FROM user_item_state WHERE serverId = :serverId")
    suspend fun deleteUserItemState(serverId: String)

    @Query("DELETE FROM home_cache WHERE serverId = :serverId")
    suspend fun deleteHomeCache(serverId: String)

    @Query("DELETE FROM catalog_cache WHERE serverId = :serverId")
    suspend fun deleteCatalogCache(serverId: String)

    @Transaction
    suspend fun deleteAllRowsForServer(serverId: String) {
        deleteDownloads(serverId)
        deleteDownloadSubscriptions(serverId)
        deleteDownloadDeletions(serverId)
        deleteDirtyOperations(serverId)
        deleteContentItemState(serverId)
        deleteUserItemState(serverId)
        deleteHomeCache(serverId)
        deleteCatalogCache(serverId)
    }
}
