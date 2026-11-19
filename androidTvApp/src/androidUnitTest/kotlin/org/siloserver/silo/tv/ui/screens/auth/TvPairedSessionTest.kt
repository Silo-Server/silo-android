package org.siloserver.silo.tv.ui.screens.auth

import org.siloserver.silo.common.pairing.PairingCommittedAccount
import kotlin.test.Test
import kotlin.test.assertEquals

class TvPairedSessionTest {
    private val alexHome = PairingCommittedAccount("home", "alex")
    private val samHome = PairingCommittedAccount("home", "sam")
    private val alexLake = PairingCommittedAccount("lake", "alex-lake")

    @Test
    fun oneAccountOpensItsPicker() {
        assertEquals(TvPairedSessionDestination.Picker, pairedSessionDestination(listOf(alexHome)))
        // The same account refreshed twice is still one.
        assertEquals(TvPairedSessionDestination.Picker, pairedSessionDestination(listOf(alexHome, alexHome)))
        assertEquals(TvPairedSessionDestination.Picker, pairedSessionDestination(emptyList()))
    }

    @Test
    fun severalAccountsOpenChangeAccountOnTheFirstToSignIn() {
        assertEquals(
            TvPairedSessionDestination.ChangeAccount(samHome),
            pairedSessionDestination(listOf(samHome, alexLake, alexHome)),
        )
    }
}
