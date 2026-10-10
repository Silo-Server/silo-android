package org.siloserver.silo.common.watchparty

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchPartyExperimentTest {
    private var stored: Boolean? = null
    private var departures = 0

    private fun experiment(defaultEnabled: Boolean) =
        WatchPartyExperiment({ stored }, { stored = it }, defaultEnabled) { departures++ }

    @Test
    fun `release builds default off and debug builds default on`() {
        assertFalse(experiment(defaultEnabled = false).enabled.value)
        assertTrue(experiment(defaultEnabled = true).enabled.value)
    }

    @Test
    fun `the stored choice beats the build default`() {
        stored = true
        assertTrue(experiment(defaultEnabled = false).enabled.value)
        stored = false
        assertFalse(experiment(defaultEnabled = true).enabled.value)
    }

    @Test
    fun `turning it off leaves the party once`() {
        val toggle = experiment(defaultEnabled = true)
        toggle.setEnabled(false)
        toggle.setEnabled(false)
        toggle.setEnabled(true)
        assertEquals(1, departures)
        assertEquals(true, stored)
    }
}
