package org.siloserver.silo.model.server

/** One saved account on the Change Account screen. */
data class AccountCard(
    val account: AccountEntry,
    /** "Current": the open account. */
    val isCurrent: Boolean,
) {
    val profileCount: Int get() = account.cachedProfiles.size
}

/** One saved server on the Change Account screen, with its accounts in the order they were added. */
data class AccountServerGroup(val server: ServerEntry, val accounts: List<AccountCard>)

/**
 * The Change Account screen's servers (owner decision 2026-11-02, Roku spec
 * §7.5): the active server first, then by last use; each with its accounts.
 */
fun buildAccountGroups(
    servers: List<ServerEntry>,
    accounts: List<AccountEntry>,
    activeServerId: String?,
    activeAccountId: String?,
): List<AccountServerGroup> =
    servers.sortedWith(
        compareByDescending<ServerEntry> { it.id == activeServerId }.thenByDescending { it.lastUsedAtEpochMs },
    ).map { server ->
        AccountServerGroup(
            server,
            accounts.filter { it.serverId == server.id }
                .sortedBy { it.addedAtEpochMs }
                .map { AccountCard(it, isCurrent = it.localAccountId == activeAccountId) },
        )
    }
