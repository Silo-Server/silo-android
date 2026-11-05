package org.siloserver.silo.tv.ui.screens.auth

import org.siloserver.silo.common.pairing.PairingCommittedAccount

/** Where the TV goes when a phone's setup session ends (contract §B.7). */
sealed interface TvPairedSessionDestination {
    /** One account signed in (or none known): its "Who's watching?", as before. */
    data object Picker : TvPairedSessionDestination

    /**
     * Several accounts signed in: [first], the first to sign in, becomes the
     * open account, then Change Account opens with its server on the rail
     * and focus on its card. Back from there opens [first]'s picker.
     */
    data class ChangeAccount(val first: PairingCommittedAccount) : TvPairedSessionDestination
}

/**
 * [accounts] are the saved accounts the session signed in, in order. A
 * refreshed saved account counts as signed in; the same account twice counts
 * once.
 */
fun pairedSessionDestination(accounts: List<PairingCommittedAccount>): TvPairedSessionDestination {
    val distinct = accounts.distinct()
    return if (distinct.size >= 2) TvPairedSessionDestination.ChangeAccount(distinct.first()) else TvPairedSessionDestination.Picker
}
