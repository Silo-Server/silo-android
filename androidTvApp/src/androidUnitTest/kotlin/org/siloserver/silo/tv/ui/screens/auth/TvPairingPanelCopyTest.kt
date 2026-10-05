package org.siloserver.silo.tv.ui.screens.auth

import java.io.File
import org.siloserver.silo.common.pairing.PairingReceiverStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The nearby-phone panel's copy matches Apple TV's receiver panel. */
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

    @Test
    fun copyDeckMatchesAppleTv() {
        val strings = File("src/androidMain/res/values/strings.xml").readText()
        listOf(
            "tv_pairing_connected_title\">Phone or tablet connected<",
            "tv_pairing_confirm_title\">Confirm on your phone or tablet<",
            "tv_pairing_confirm_title_automatic\">Signing in<",
            "tv_pairing_confirm_detail\">Check that your phone or tablet shows this code, then approve.<",
            "tv_pairing_confirm_detail_automatic\">Your phone or tablet is checking this code for you.<",
            "tv_pairing_confirm_older_phones\">Older phones show %1\$s instead.<",
            "tv_setup_phone_looking\">Looking for a phone or tablet…<",
            "tv_setup_subtitle\">Use your phone or tablet, or enter the server address with the remote.<",
            "tv_signin_form_title\">Sign in with a password<",
            "tv_signin_form_submit\">Sign in<",
        ).forEach { assertTrue(strings.contains(it), it) }
    }
}
