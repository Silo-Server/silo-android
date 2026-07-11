package org.siloserver.silo.common.pairing

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompanionPairingNsdBrowserTest {
    @Test
    fun pairingServiceTypeAcceptsAndroidTrailingDotForm() {
        assertTrue(isPairingServiceType("_silopair._tcp"))
        assertTrue(isPairingServiceType("_silopair._tcp."))
        assertFalse(isPairingServiceType("_silocast._tcp."))
    }
}
