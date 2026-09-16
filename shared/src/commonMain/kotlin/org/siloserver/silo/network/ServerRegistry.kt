package org.siloserver.silo.network

import org.siloserver.silo.model.server.ServerContract
import org.siloserver.silo.model.server.ServerEntry
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

    /** Persist the per-server selected profile so a future switch restores it. */
    suspend fun setProfileId(serverId: String, profileId: String?)

    /**
     * Records what the v2 contract probe learned about [serverId]. Default is a
     * no-op so single-purpose fakes need not track it.
     */
    suspend fun setContract(serverId: String, contract: ServerContract) {}

    /** Remove an entry entirely. If it was active, fall back to the next-MRU server. */
    suspend fun remove(serverId: String)

    /** Sign out — keep the entry but drop its profileId. Tokens are dropped by [TokenManager.signOutCurrentServer]. */
    suspend fun signOut(serverId: String)

    /**
     * Switch the active server. Updates [activeServerId] and bumps lastUsedAt.
     *
     * Implementations must coordinate with [TokenManager.switchActiveServer]
     * before this returns so that subsequent reads target the new server's slot.
     */
    suspend fun switchTo(serverId: String)

    /** Bump the active server's lastUsedAt — call after a successful API hit. */
    suspend fun touchActive()
}
