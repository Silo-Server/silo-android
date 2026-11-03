package org.siloserver.silo.common.store

/**
 * How local data names the saved account it belongs to (multi-account spec
 * §5.1). Profile ids repeat across accounts (older accounts are all
 * `default`), so every per-(server, profile) key, file and directory also
 * carries the token `@<localAccountId>`. [AccountLocalData] finds data by that
 * token: it moves pre-account values into the adopting account's token and
 * deletes the tokens of accounts that were signed out.
 *
 * Local account ids are UUIDs, and no server id, profile id or settings tag
 * contains `@`, so a token is never confused with any of them.
 */
object AccountScopedKeys {

    fun token(accountId: String): String = "@$accountId"

    /**
     * `serverUrl|@account|profile`: the identity per-(server, account,
     * profile) caches key or hash. With no account (a test fake, or a moment
     * with no saved account) it is the pre-account `serverUrl|profile`.
     */
    fun identity(serverUrl: String, accountId: String?, profileId: String): String =
        if (accountId.isNullOrBlank()) "$serverUrl|$profileId" else "$serverUrl|${token(accountId)}|$profileId"

    /** The account ids [name] carries. */
    fun accountIdsIn(name: String): Set<String> =
        TOKEN.findAll(name).mapTo(mutableSetOf()) { it.groupValues[1] }

    fun hasAccountToken(name: String): Boolean = TOKEN.containsMatchIn(name)

    /** Whether [value] has the shape of a local account id (a UUID). */
    fun isAccountId(value: String): Boolean = ACCOUNT_ID.matches(value)

    /**
     * `<base>_@<account>_<profileHash>` for a per-(account, profile) DataStore
     * file, or the pre-account `<base>_<profileHash>` with no account.
     */
    fun profileFileName(base: String, accountId: String?, profileHash: String): String =
        if (accountId.isNullOrBlank()) "${base}_$profileHash" else "${base}_${token(accountId)}_$profileHash"

    private const val UUID = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    private val TOKEN = Regex("@($UUID)")
    private val ACCOUNT_ID = Regex(UUID)
}
