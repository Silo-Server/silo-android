package org.siloserver.silo.common.pairing

/** How many accounts the chooser lets the person pick for one TV (contract §C.1). */
enum class CompanionSelectionMode {
    /** The TV advertises `acct=1`: any accounts, on any servers. */
    Several,

    /** An older TV keeps one account per server: picking one on a server replaces the other. */
    OnePerServer,

    /** An older Roku keeps one server: one account in all. */
    One,
}

/**
 * The account chooser for a setup TV: every saved server with its accounts,
 * and the rules for picking them. Pure, so the phone's card and the
 * coordinator apply the same rules.
 *
 * - Only the account the phone is using on its active server starts selected.
 * - Accounts that need signing in again are listed but can't be picked.
 * - [limit] (TXT `lim`): once that many are picked, the rest can't be.
 * - Sending order: the phone's own account first, then list order.
 */
data class CompanionAccountChoice(
    val targetName: String,
    val servers: List<CompanionPairingServer>,
    val mode: CompanionSelectionMode,
    val limit: Int? = null,
) {
    /** Every row, server by server, in list order. */
    val destinations: List<CompanionPairingDestination> =
        servers.flatMap { server -> server.offeredAccounts.map { CompanionPairingDestination(server, it) } }

    /** The account the phone is using on its active server, when it can approve. */
    val phoneDestination: CompanionPairingDestination? =
        destinations.firstOrNull { it.server.isActive && it.account.isCurrent && it.account.canApprove }

    private val effectiveLimit: Int?
        get() = when (mode) {
            CompanionSelectionMode.Several, CompanionSelectionMode.OnePerServer -> limit?.coerceAtLeast(1)
            CompanionSelectionMode.One -> 1
        }

    fun destination(key: String): CompanionPairingDestination? = destinations.firstOrNull { it.key == key }

    fun initialSelection(): Set<String> = setOfNotNull(phoneDestination?.key)

    /** The TV's limit is reached: unpicked rows are disabled, with [limit] as the reason. */
    fun limitReached(selected: Set<String>): Boolean {
        if (mode == CompanionSelectionMode.One) return false
        val cap = effectiveLimit ?: return false
        return selected.count { destination(it) != null } >= cap
    }

    /** Whether the row [key] can be tapped with [selected] picked. */
    fun isEnabled(selected: Set<String>, key: String): Boolean {
        val row = destination(key) ?: return false
        if (!row.account.canApprove) return false
        if (key in selected) return true
        return when (mode) {
            CompanionSelectionMode.Several -> !limitReached(selected)
            // Picking replaces another row on the same server, unless the
            // limit is already used up elsewhere.
            CompanionSelectionMode.OnePerServer ->
                !limitReached(selected) || selected.any { destination(it)?.server?.id == row.server.id }
            CompanionSelectionMode.One -> true
        }
    }

    /** A tap on [key]. */
    fun toggle(selected: Set<String>, key: String): Set<String> {
        val row = destination(key) ?: return selected
        if (!row.account.canApprove) return selected
        if (key in selected) {
            return if (mode == CompanionSelectionMode.One) selected else selected - key
        }
        if (!isEnabled(selected, key)) return selected
        return when (mode) {
            CompanionSelectionMode.Several -> selected + key
            CompanionSelectionMode.OnePerServer ->
                selected.filterNot { destination(it)?.server?.id == row.server.id }.toSet() + key
            CompanionSelectionMode.One -> setOf(key)
        }
    }

    /**
     * What to send for [selected], in sending order (the phone's own account
     * first, then list order), keeping only rows that can approve and the
     * mode's and limit's rules.
     */
    fun resolve(selected: Set<String>): List<CompanionPairingDestination> {
        val chosen = destinations.filter { it.key in selected && it.account.canApprove }
        val ordered = chosen.sortedByDescending { it.key == phoneDestination?.key }
        val constrained = when (mode) {
            CompanionSelectionMode.Several -> ordered
            CompanionSelectionMode.OnePerServer -> ordered.distinctBy { it.server.id }
            CompanionSelectionMode.One -> ordered.take(1)
        }
        return effectiveLimit?.let { constrained.take(it) } ?: constrained
    }
}
