package org.siloserver.silo.network

import org.siloserver.silo.model.server.AccountEntry
import org.siloserver.silo.model.server.CachedProfile
import org.siloserver.silo.model.server.ServerContract
import org.siloserver.silo.model.server.ServerEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Registry of saved Silo servers, plus the notion of an "active" one.
 *
 * Mirrors the Swift `ServerRegistry` actor on iOS/tvOS. Persistence is the
 * implementation's concern — Android backs this with EncryptedSharedPreferences.
 *
 * All mutators are suspending so an implementation can flush to disk inside
 * the same critical section as the in-memory update.
 */
interface ServerRegistry {

    /** All known servers. Order is unspecified — UI sorts MRU when it cares. */
    val entries: StateFlow<List<ServerEntry>>

    /** Currently selected server id, or null if no server has been added yet. */
    val activeServerId: StateFlow<String?>

    /** Convenience: the [ServerEntry] for [activeServerId], or null. */
    val activeEntry: StateFlow<ServerEntry?>

    /**
     * Every saved account on every saved server. A server may have none (it
     * was signed out of and shows a Sign in tile), one, or several.
     */
    val accounts: StateFlow<List<AccountEntry>> get() = NoAccounts

    /**
     * The account in use on [activeServerId], or null when that server has
     * no account selected (signed out, or a sign-in in progress).
     */
    val activeAccountId: StateFlow<String?> get() = NoActiveAccount

    /** Convenience: the [AccountEntry] for [activeAccountId], or null. */
    val activeAccount: StateFlow<AccountEntry?> get() = NoActiveAccountEntry

    /**
     * True when persisted state existed but could not be read back, so
     * [entries] is empty for a reason other than "the user has no servers".
     *
     * Callers that delete data derived from registry absence — see
     * `OrphanedServerDataPurger` — must treat an empty registry as
     * untrustworthy while this is set, or a single unreadable blob erases
     * every download and resume position on the device.
     */
    val stateLoadFailed: Boolean get() = false

    /** Insert-or-update a server by url. Returns the id of the resulting entry. */
    suspend fun addOrUpdate(
        url: String,
        fetchedName: String? = null,
    ): String

    /** Update only the user-provided override name. */
    suspend fun rename(serverId: String, userOverrideName: String?)

    /** Update only the server-advertised name. */
    suspend fun setFetchedName(serverId: String, fetchedName: String?)

    /**
     * Persist the selected profile of [serverId]'s current account (see
     * [ServerEntry.accountId]) so a future switch restores it.
     */
    suspend fun setProfileId(serverId: String, profileId: String?)

    /** Persist [accountId]'s selected profile. */
    suspend fun setAccountProfileId(accountId: String, profileId: String?) {}

    /** Replace [accountId]'s cached profile list (the picker's offline copy). */
    suspend fun setCachedProfiles(accountId: String, profiles: List<CachedProfile>) {}

    /**
     * Record what a verified `/me` (or a sign-in) said about [accountId]. A
     * null argument leaves that field alone.
     */
    suspend fun setAccountIdentity(accountId: String, serverUserId: String?, username: String?) {}

    /**
     * Records what the v2 contract probe learned about [serverId]. Default is a
     * no-op so single-purpose fakes need not track it.
     */
    suspend fun setContract(serverId: String, contract: ServerContract) {}

    /**
     * Records the deployment identity verified at [serverId]'s URL. Default is
     * a no-op so single-purpose fakes need not track it.
     */
    suspend fun setVerifiedServerId(serverId: String, verifiedServerId: String?) {}

    /** Remove an entry entirely. If it was active, fall back to the next-MRU server. */
    suspend fun remove(serverId: String)

    /** Sign out — keep the entry but drop its profileId. Tokens are dropped by [TokenManager.signOutCurrentServer]. */
    suspend fun signOut(serverId: String)

    /**
     * Switch the active server. Updates [activeServerId] and bumps lastUsedAt.
     * The server's most recently used account becomes active; use
     * [switchToAccount] to choose one.
     *
     * Implementations must coordinate with [TokenManager.switchActiveServer]
     * before this returns so that subsequent reads target the new server's slot.
     */
    suspend fun switchTo(serverId: String)

    /**
     * Make [accountId] (and its server) active and bump both lastUsedAt
     * stamps. A null [accountId] activates [serverId] with no account, the
     * state a sign-in for a new account starts from.
     */
    suspend fun switchToAccount(serverId: String, accountId: String?) { switchTo(serverId) }

    /** Bump the active server's lastUsedAt — call after a successful API hit. */
    suspend fun touchActive()
}

private val NoAccounts: StateFlow<List<AccountEntry>> = MutableStateFlow(emptyList())
private val NoActiveAccount: StateFlow<String?> = MutableStateFlow(null)
private val NoActiveAccountEntry: StateFlow<AccountEntry?> = MutableStateFlow(null)

/** [serverId]'s accounts, most recently used first. */
fun ServerRegistry.accountsOn(serverId: String): List<AccountEntry> =
    accounts.value.filter { it.serverId == serverId }.sortedByDescending { it.lastUsedAtEpochMs }

/**
 * [serverId]'s current account: the active one when [serverId] is active,
 * otherwise its most recently used. This is the account that owns the
 * server's push installation (spec §4.8) and answers per-server token reads.
 */
fun ServerRegistry.currentAccountOn(serverId: String): AccountEntry? =
    if (activeServerId.value == serverId) activeAccount.value
    else accountsOn(serverId).firstOrNull()

/**
 * Saved servers this device holds an access token for, the active one first,
 * then most recently used: the servers a phone can approve a TV on or push to
 * a nearby one.
 */
suspend fun ServerRegistry.signedInEntries(tokenManager: TokenManager): List<ServerEntry> {
    val activeId = activeServerId.value
    return entries.value
        .filter { it.url.isNotBlank() && !tokenManager.getAccessTokenForScope(it.id).isNullOrBlank() }
        .sortedWith(compareByDescending<ServerEntry> { it.id == activeId }.thenByDescending { it.lastUsedAtEpochMs })
}
