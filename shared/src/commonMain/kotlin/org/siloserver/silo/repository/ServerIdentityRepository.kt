package org.siloserver.silo.repository

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.siloserver.silo.model.server.ServerEntry
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.api.ServerIdentityApi
import org.siloserver.silo.network.api.ServerIdentityProbe

/**
 * Deployment identities of saved servers. The id comes from
 * `GET /api/v2/system/identity` at the saved URL and is recorded on the entry
 * ([ServerEntry.verifiedServerId]) so later matches don't need the network.
 *
 * Used to match a TV's `srv` advertisement and a `silo://device?server=` link
 * to a saved server by identity rather than URL spelling: one deployment has
 * several addresses, and two deployments can reuse an address over time.
 */
class ServerIdentityRepository(
    private val registry: ServerRegistry,
    private val api: ServerIdentityApi,
) {
    /**
     * The identity verified at [entry]'s URL. Probes when none is recorded or
     * [refresh] is set; a probe that can't reach the server keeps the recorded
     * value, and one that reaches a server without the identity contract
     * clears it.
     */
    suspend fun identityOf(entry: ServerEntry, refresh: Boolean = false): String? {
        val recorded = entry.verifiedServerId?.takeIf { it.isNotBlank() }
        if (recorded != null && !refresh) return recorded
        return when (val probe = api.probeIdentity(entry.url)) {
            is ServerIdentityProbe.Identity -> {
                if (probe.serverId != recorded) registry.setVerifiedServerId(entry.id, probe.serverId)
                probe.serverId
            }
            ServerIdentityProbe.UnsupportedServer -> {
                if (recorded != null) registry.setVerifiedServerId(entry.id, null)
                null
            }
            ServerIdentityProbe.Unreachable -> recorded
        }
    }

    /**
     * The entries among [candidates] whose identity is [serverId]. Entries
     * with a recorded identity are matched without the network; the others
     * are probed (in parallel) only when no recorded identity matched.
     */
    suspend fun entriesFor(
        serverId: String,
        candidates: List<ServerEntry> = registry.entries.value,
    ): List<ServerEntry> {
        val wanted = serverId.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
        val recorded = candidates.filter { it.verifiedServerId == wanted }
        if (recorded.isNotEmpty()) return recorded
        val unverified = candidates.filter { it.verifiedServerId.isNullOrBlank() && it.url.isNotBlank() }
        if (unverified.isEmpty()) return emptyList()
        return coroutineScope {
            unverified
                .map { entry -> async { entry to identityOf(entry) } }
                .awaitAll()
                .filter { (_, id) -> id == wanted }
                .map { (entry, _) -> entry }
        }
    }

    /** Probe an address that isn't saved (a link's `url`, a pushed server). */
    suspend fun probe(serverUrl: String): ServerIdentityProbe = api.probeIdentity(serverUrl)
}
