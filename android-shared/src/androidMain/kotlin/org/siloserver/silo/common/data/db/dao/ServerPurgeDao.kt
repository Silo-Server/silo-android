package org.siloserver.silo.common.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

/** One `(serverId, accountId)` pair with rows in a scoped table; see [ServerPurgeDao.knownAccounts]. */
data class ServerAccountRow(val serverId: String, val accountId: String)

/**
 * Cross-table purge for a server that is no longer in the registry, or for an
 * account that is no longer saved (a sign-out), plus the adoption of rows
 * written before accounts existed.
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

    /**
     * Every `(serverId, accountId)` with rows in any scoped table, excluding
     * rows written before accounts existed (`accountId = ''`). Like
     * [knownServerIds], account orphans are this minus the registry.
     */
    @Query(
        "SELECT DISTINCT serverId, accountId FROM downloads WHERE accountId != '' " +
            "UNION SELECT DISTINCT serverId, accountId FROM download_subscriptions WHERE accountId != '' " +
            "UNION SELECT DISTINCT serverId, accountId FROM download_deletions WHERE accountId != '' " +
            "UNION SELECT DISTINCT serverId, accountId FROM dirty_operations WHERE accountId != '' " +
            "UNION SELECT DISTINCT serverId, accountId FROM content_item_state WHERE accountId != '' " +
            "UNION SELECT DISTINCT serverId, accountId FROM user_item_state WHERE accountId != '' " +
            "UNION SELECT DISTINCT serverId, accountId FROM home_cache WHERE accountId != '' " +
            "UNION SELECT DISTINCT serverId, accountId FROM catalog_cache WHERE accountId != ''",
    )
    suspend fun knownAccounts(): List<ServerAccountRow>

    @Query("SELECT recordId FROM downloads WHERE serverId = :serverId")
    suspend fun downloadRecordIdsForServer(serverId: String): List<String>

    @Query("SELECT mediaFileId FROM downloads WHERE serverId = :serverId")
    suspend fun downloadFileIdsForServer(serverId: String): List<Int>

    @Query("SELECT recordId FROM downloads WHERE serverId = :serverId AND accountId = :accountId")
    suspend fun downloadRecordIdsForAccount(serverId: String, accountId: String): List<String>

    @Query("SELECT mediaFileId FROM downloads WHERE serverId = :serverId AND accountId = :accountId")
    suspend fun downloadFileIdsForAccount(serverId: String, accountId: String): List<Int>

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

    // -- One account (sign-out) --

    @Query("DELETE FROM downloads WHERE serverId = :serverId AND accountId = :accountId")
    suspend fun deleteAccountDownloads(serverId: String, accountId: String)

    @Query("DELETE FROM download_subscriptions WHERE serverId = :serverId AND accountId = :accountId")
    suspend fun deleteAccountDownloadSubscriptions(serverId: String, accountId: String)

    @Query("DELETE FROM download_deletions WHERE serverId = :serverId AND accountId = :accountId")
    suspend fun deleteAccountDownloadDeletions(serverId: String, accountId: String)

    @Query(
        "DELETE FROM dirty_operations WHERE serverId = :serverId AND accountId = :accountId " +
            "AND state != 'legacy_membership_quarantined'",
    )
    suspend fun deleteAccountDirtyOperations(serverId: String, accountId: String)

    @Query("DELETE FROM content_item_state WHERE serverId = :serverId AND accountId = :accountId")
    suspend fun deleteAccountContentItemState(serverId: String, accountId: String)

    @Query("DELETE FROM user_item_state WHERE serverId = :serverId AND accountId = :accountId")
    suspend fun deleteAccountUserItemState(serverId: String, accountId: String)

    @Query("DELETE FROM home_cache WHERE serverId = :serverId AND accountId = :accountId")
    suspend fun deleteAccountHomeCache(serverId: String, accountId: String)

    @Query("DELETE FROM catalog_cache WHERE serverId = :serverId AND accountId = :accountId")
    suspend fun deleteAccountCatalogCache(serverId: String, accountId: String)

    /** Every row of one signed-out account. Never called with `accountId = ''`. */
    @Transaction
    suspend fun deleteAllRowsForAccount(serverId: String, accountId: String) {
        require(accountId.isNotEmpty()) { "Rows written before accounts existed are purged with their server" }
        deleteAccountDownloads(serverId, accountId)
        deleteAccountDownloadSubscriptions(serverId, accountId)
        deleteAccountDownloadDeletions(serverId, accountId)
        deleteAccountDirtyOperations(serverId, accountId)
        deleteAccountContentItemState(serverId, accountId)
        deleteAccountUserItemState(serverId, accountId)
        deleteAccountHomeCache(serverId, accountId)
        deleteAccountCatalogCache(serverId, accountId)
    }

    // -- Adoption of rows written before accounts existed --
    //
    // OR IGNORE: a row the account already wrote under the same key is newer
    // than the legacy one, so it wins and the legacy row stays unadopted (it
    // is unread, and goes when the server is removed).

    @Query("UPDATE OR IGNORE downloads SET accountId = :accountId WHERE serverId = :serverId AND accountId = ''")
    suspend fun adoptDownloads(serverId: String, accountId: String)

    @Query("UPDATE OR IGNORE download_subscriptions SET accountId = :accountId WHERE serverId = :serverId AND accountId = ''")
    suspend fun adoptDownloadSubscriptions(serverId: String, accountId: String)

    @Query("UPDATE OR IGNORE download_deletions SET accountId = :accountId WHERE serverId = :serverId AND accountId = ''")
    suspend fun adoptDownloadDeletions(serverId: String, accountId: String)

    @Query("UPDATE OR IGNORE dirty_operations SET accountId = :accountId WHERE serverId = :serverId AND accountId = ''")
    suspend fun adoptDirtyOperations(serverId: String, accountId: String)

    @Query("UPDATE OR IGNORE content_item_state SET accountId = :accountId WHERE serverId = :serverId AND accountId = ''")
    suspend fun adoptContentItemState(serverId: String, accountId: String)

    @Query("UPDATE OR IGNORE user_item_state SET accountId = :accountId WHERE serverId = :serverId AND accountId = ''")
    suspend fun adoptUserItemState(serverId: String, accountId: String)

    @Query("UPDATE OR IGNORE home_cache SET accountId = :accountId WHERE serverId = :serverId AND accountId = ''")
    suspend fun adoptHomeCache(serverId: String, accountId: String)

    @Query("UPDATE OR IGNORE catalog_cache SET accountId = :accountId WHERE serverId = :serverId AND accountId = ''")
    suspend fun adoptCatalogCache(serverId: String, accountId: String)

    /** Hands [serverId]'s pre-account rows to [accountId]. Idempotent. */
    @Transaction
    suspend fun adoptLegacyRows(serverId: String, accountId: String) {
        require(accountId.isNotEmpty())
        adoptDownloads(serverId, accountId)
        adoptDownloadSubscriptions(serverId, accountId)
        adoptDownloadDeletions(serverId, accountId)
        adoptDirtyOperations(serverId, accountId)
        adoptContentItemState(serverId, accountId)
        adoptUserItemState(serverId, accountId)
        adoptHomeCache(serverId, accountId)
        adoptCatalogCache(serverId, accountId)
    }
}
