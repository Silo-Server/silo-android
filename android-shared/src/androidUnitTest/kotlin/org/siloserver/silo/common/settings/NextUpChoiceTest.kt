package org.siloserver.silo.common.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class NextUpChoiceTest {
    @Test
    fun offIsOfferedWhereTheServerStoresItAndWhileASavedOffAwaitsTheManifest() {
        val times = listOf(0, 10, 30, 60, 120)
        assertEquals(listOf<Int?>(null) + times, NextUpChoice.options(offSupported = true, enabled = true))
        assertEquals(times, NextUpChoice.options(offSupported = false, enabled = true))
        // A saved Off before the manifest is read stays visible and selected.
        assertEquals(listOf<Int?>(null) + times, NextUpChoice.options(offSupported = false, enabled = false))
        assertEquals(null, NextUpChoice.selected(enabled = false, seconds = 30))
    }
}
