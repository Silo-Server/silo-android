package org.siloserver.silo.android.ui.screens.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MobileRecoveryReplanTest {
    @Test
    fun nativeFailureQueuesTheUncommittedTrackInsteadOfReusingTheCurrentSelection() {
        val failure = MobileRecoveryReplan("subtitle_embedded_failed", "Retrying", 4)
        assertTrue(failure.shouldQueue)
        assertEquals(4, failure.subtitleTrackIndexOverride)
        assertEquals("subtitle_embedded_failed", failure.classification)
        assertFalse(MobileRecoveryReplan("transport_stall", "Retrying").shouldQueue)
    }
}
