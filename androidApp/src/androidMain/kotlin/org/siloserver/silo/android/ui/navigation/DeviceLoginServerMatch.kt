package org.siloserver.silo.android.ui.navigation

import org.siloserver.silo.model.server.ServerEntry

/**
 * What to do with a pairing request that names the server which issued it.
 *
 * A pairing code is only meaningful on its own server. The origin used to be
 * discarded and the code looked up against whichever server happened to be
 * active, which normally reported a perfectly valid request as invalid or
 * expired. Refusing that is right — but refusing it *silently* just moves the
 * confusion, so the request is still delivered and the screen explains itself.
 */
sealed interface DeviceLoginServerMatch {
    /** Proceed: the link names this server, or names none. */
    data object Active : DeviceLoginServerMatch

    /** The link belongs to [entry], which the user has but is not using. */
    data class SwitchRequired(val entry: ServerEntry) : DeviceLoginServerMatch

    /** The link names [origin], which is not a server the user has. */
    data class UnknownServer(val origin: String) : DeviceLoginServerMatch
}

/**
 * The saved server this link's code is approved on in place: through that
 * server's own account scope, without switching the phone's active server
 * (silo-apple parity). Null for a `token` link, which only the active
 * server's lookup accepts, so the "Different server" screen offers the switch.
 */
fun DeviceLoginServerMatch.SwitchRequired.inPlaceApprovalServer(token: String?, code: String?): ServerEntry? =
    entry.takeIf { token.isNullOrBlank() && !code.isNullOrBlank() }

/**
 * Resolves [requiredOrigin] against the known servers.
 *
 * [activeServerUrl] null means no server is configured yet — the user is on
 * their way to adding one, so there is nothing to contradict and pairing
 * proceeds through the normal setup gates.
 */
fun deviceLoginServerMatch(
    requiredOrigin: String?,
    activeServerUrl: String?,
    entries: List<ServerEntry>,
): DeviceLoginServerMatch {
    if (requiredOrigin == null) return DeviceLoginServerMatch.Active
    if (activeServerUrl == null) return DeviceLoginServerMatch.Active
    if (deviceLoginOriginMatchesServer(requiredOrigin, activeServerUrl)) {
        return DeviceLoginServerMatch.Active
    }
    val known = entries.firstOrNull { deviceLoginOriginMatchesServer(requiredOrigin, it.url) }
    return known
        ?.let(DeviceLoginServerMatch::SwitchRequired)
        ?: DeviceLoginServerMatch.UnknownServer(requiredOrigin)
}

/**
 * Resolves a `silo://device?server=<server_id>&url=<base>` link against the
 * saved servers by deployment identity (multi-deployment rule 3): one server
 * has several addresses, so URL spelling can't decide which saved server a
 * link means, and two servers can issue the same code at the same time.
 *
 * - The active server has that identity → proceed.
 * - Another saved server has it → the "Different server" screen.
 * - None does → the "Unknown server" screen, offering to add [linkUrl].
 *
 * When no saved server's identity can be verified (offline, or a server that
 * predates the identity contract), the link's origin is matched instead, the
 * way origin-scoped links always were. A verified identity that differs always
 * wins over a matching address.
 */
suspend fun deviceLoginServerMatchByIdentity(
    requiredServerId: String,
    linkUrl: String?,
    activeEntry: ServerEntry?,
    entries: List<ServerEntry>,
    identityOf: suspend (ServerEntry) -> String?,
    /**
     * The saved servers among the candidates with an identity, matching
     * recorded identities first and probing the rest in parallel. Null checks
     * them one at a time through [identityOf].
     */
    entriesWithIdentity: (suspend (String, List<ServerEntry>) -> List<ServerEntry>)? = null,
): DeviceLoginServerMatch {
    // No server yet: the user is adding one; the normal setup gates apply.
    val active = activeEntry ?: return DeviceLoginServerMatch.Active
    val verified = mutableMapOf<String, String?>()
    suspend fun idOf(entry: ServerEntry): String? =
        if (entry.id in verified) verified[entry.id] else identityOf(entry).also { verified[entry.id] = it }

    if (idOf(active) == requiredServerId) return DeviceLoginServerMatch.Active
    val others = entries.filter { it.id != active.id }
    val matching = if (entriesWithIdentity != null) {
        entriesWithIdentity(requiredServerId, others)
    } else {
        listOfNotNull(others.firstOrNull { idOf(it) == requiredServerId })
    }
    matching.firstOrNull()?.let { return DeviceLoginServerMatch.SwitchRequired(it) }

    val origin = deviceLoginOriginOf(linkUrl)
    if (origin != null) {
        when (val byOrigin = deviceLoginServerMatch(origin, active.url, entries)) {
            DeviceLoginServerMatch.Active -> if (idOf(active) == null) return byOrigin
            is DeviceLoginServerMatch.SwitchRequired -> if (idOf(byOrigin.entry) == null) return byOrigin
            is DeviceLoginServerMatch.UnknownServer -> Unit
        }
    }
    return DeviceLoginServerMatch.UnknownServer(linkUrl?.takeIf { it.isNotBlank() } ?: origin ?: requiredServerId)
}
