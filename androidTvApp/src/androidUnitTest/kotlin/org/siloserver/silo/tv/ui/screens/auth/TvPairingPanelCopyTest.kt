package org.siloserver.silo.tv.ui.screens.auth

import org.siloserver.silo.common.pairing.PairingReceiverStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** When the nearby-phone panel shows the older phones' match words. */
class TvPairingPanelCopyTest {
    private fun awaiting(automatic: Boolean, matchCode: String?) = PairingReceiverStatus.AwaitingApproval(
        serverURL = "https://silo.test",
        serverName = "Silo",
        userCode = "4821-7730",
        automatic = automatic,
        matchCode = matchCode,
    )

    @Test
    fun olderPhonesLineOnlyForAPersonCheckedAttempt() {
        assertEquals("WARM PONY", olderPhonesMatchWords(awaiting(automatic = false, matchCode = "warm pony")))
        assertNull(olderPhonesMatchWords(awaiting(automatic = true, matchCode = "warm pony")))
        assertNull(olderPhonesMatchWords(awaiting(automatic = false, matchCode = " ")))
        assertNull(olderPhonesMatchWords(awaiting(automatic = false, matchCode = null)))
    }
}
