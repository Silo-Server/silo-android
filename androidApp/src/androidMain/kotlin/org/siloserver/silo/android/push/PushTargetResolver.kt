package org.siloserver.silo.android.push

import org.siloserver.silo.model.server.ServerEntry
import org.siloserver.silo.network.DurableLoginAuthority
import org.siloserver.silo.network.DurableLoginAuthorityProvider
import org.siloserver.silo.network.ServerRegistry

/**
 * The server and profile a push names, as `silo_server_id` and `silo_profile_id`;
 * blank when it named only the other.
 */
data class PushIssuer(val serverId: String, val profileId: String)

/**
 * Where a push's content lives: the saved server entry ([serverEntryId]) and
 * profile it was sent for, and the saved login that reads it, whichever server
 * and profile are active.
 */
internal data class PushTarget(
    val serverEntryId: String,
    val profileId: String,
    val owner: DurableLoginAuthority,
)

/**
 * Finds the registration a push was sent for. Each saved server's
 * installation record is that server's target: it holds the profile the
 * server pushes for and the proof of that profile, and the server's saved
 * login supplies the rest. A server's deployment identity is its own claim:
 * the one its registration receipt reported, else the one recorded on its
 * entry, else (servers registered before receipts carried it) what its saved
 * address answers now, recorded for next time. It is never a key: no server's
 * claim reaches another saved server's record.
 *
 * Null for a server or profile this device holds no live registration and
 * login for, which includes one signed out of, removed, or unregistered, and
 * for one that more than one saved server claims.
 */
internal class PushTargetResolver(
    private val installations: suspend () -> List<PushInstallation>,
    private val authorities: DurableLoginAuthorityProvider,
    private val registry: ServerRegistry,
    /** The entries among the candidates whose identity is the server id; may probe them. */
    private val entriesWithIdentity: suspend (serverId: String, candidates: List<ServerEntry>) -> List<ServerEntry>,
) {
    suspend fun resolve(issuer: PushIssuer): PushTarget? {
        if (issuer.serverId.isBlank() || issuer.profileId.isBlank()) return null
        val entries = registry.entries.value
        fun PushInstallation.entry() = entries.firstOrNull { it.id == intent.serverId && it.url == serverUrl }
        fun PushInstallation.identity() = receipt?.serverId ?: entry()?.verifiedServerId?.takeIf { it.isNotBlank() }

        val registered = installations().filter {
            it.acknowledged && it.refusal == null && it.intent.registration != null &&
                it.intent.profileId == issuer.profileId && it.entry() != null
        }
        // Every claim is counted, so ask the servers that have made none yet
        // too: otherwise another server's claim would stand in for theirs.
        val unidentified = registered.filter { it.identity() == null }
        val answered = if (unidentified.isEmpty()) emptySet() else {
            entriesWithIdentity(issuer.serverId, unidentified.mapNotNull { it.entry() }).map { it.id }.toSet()
        }
        // Any server can claim any identity, so a pair two saved servers claim
        // could be either one's: reading it from one could show the other's
        // content and open the wrong server.
        val installation = registered.singleOrNull {
            it.identity() == issuer.serverId || (it.identity() == null && it.intent.serverId in answered)
        } ?: return null

        val owner = authorities.savedLoginAuthority(
            installation.intent.serverId,
            installation.intent.profileId,
            installation.intent.profileToken,
        )?.takeIf { it.loginId == installation.intent.loginId && it.scope.serverUrl == installation.serverUrl } ?: return null
        return PushTarget(installation.intent.serverId, installation.intent.profileId, owner)
    }
}
