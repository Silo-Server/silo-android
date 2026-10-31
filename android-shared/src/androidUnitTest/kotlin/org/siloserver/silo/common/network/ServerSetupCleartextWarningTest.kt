package org.siloserver.silo.common.network

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServerSetupCleartextWarningTest {

    private fun warns(input: String, scheme: ServerSetupScheme, port: String = "") =
        serverSetupUsesCleartext(input, scheme, port)

    @Test
    fun explicitHttpSchemeWarns() {
        assertTrue(warns("192.168.1.50:8090", ServerSetupScheme.Http))
    }

    @Test
    fun httpPrefixInputWarns() {
        assertTrue(warns("http://myserver.local", ServerSetupScheme.Auto))
    }

    @Test
    fun httpsIsClean() {
        assertFalse(warns("192.168.1.50", ServerSetupScheme.Https))
        assertFalse(warns("https://media.example.com", ServerSetupScheme.Auto))
    }

    @Test
    fun autoWithoutHttpPrefixDoesNotPreWarn() {
        // Auto tries HTTPS first; only falls back to HTTP if HTTPS fails, so a
        // bare host in Auto mode isn't a definite-cleartext connection yet.
        assertFalse(warns("192.168.1.50", ServerSetupScheme.Auto))
        assertFalse(warns("media.example.com", ServerSetupScheme.Auto))
    }

    @Test
    fun blankOrInvalidInputDoesNotWarn() {
        assertFalse(warns("", ServerSetupScheme.Http))
        assertFalse(warns("   ", ServerSetupScheme.Auto))
    }
}
