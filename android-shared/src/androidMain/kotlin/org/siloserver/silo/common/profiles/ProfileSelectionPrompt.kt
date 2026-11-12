package org.siloserver.silo.common.profiles

/** What takes the open profile's place when Profile Selection asks. */
enum class ProfileSelectionPrompt {
    /** Nothing: the open profile stays. */
    None,

    /** "Who's watching?", the open account's picker. */
    WhoIsWatching,

    /** Change Account: several accounts are saved, so whose account comes first. */
    ChangeAccount,
}

/**
 * What a launch or a return to Silo shows once Profile Selection requires a
 * choice ([selectionRequired]: Every Time, or a timed choice whose away
 * interval ran out). Shared by the phone and TV apps.
 *
 * With several accounts saved on this device, the next person may not be on
 * the last account at all, so Change Account shows in the picker's place and
 * no profile stays open. [savedAccounts] counts every saved account, on every
 * server, including one that must sign in again.
 *
 * With one account, its only profile without a PIN
 * ([onlyProfileNeedsNoChoice]) stays open: there is nothing to choose and
 * nothing to protect. Otherwise the picker asks.
 */
fun profileSelectionPrompt(
    selectionRequired: Boolean,
    savedAccounts: Int,
    onlyProfileNeedsNoChoice: Boolean,
): ProfileSelectionPrompt = when {
    !selectionRequired -> ProfileSelectionPrompt.None
    savedAccounts >= 2 -> ProfileSelectionPrompt.ChangeAccount
    onlyProfileNeedsNoChoice -> ProfileSelectionPrompt.None
    else -> ProfileSelectionPrompt.WhoIsWatching
}
