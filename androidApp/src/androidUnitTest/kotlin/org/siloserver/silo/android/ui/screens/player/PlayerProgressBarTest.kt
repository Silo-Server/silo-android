package org.siloserver.silo.android.ui.screens.player

import kotlin.test.Test
import kotlin.test.assertEquals

class PlayerProgressBarTest {
    @Test
    fun unknownDurationDoesNotRenderFalseRemainingTime() {
        assertEquals("−−:−−", remainingTimeLabel(position = 300.0, duration = 0.0))
    }

    @Test
    fun knownDurationRendersRemainingTime() {
        assertEquals("−5:00", remainingTimeLabel(position = 300.0, duration = 600.0))
    }
}
