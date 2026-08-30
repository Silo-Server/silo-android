package org.siloserver.silo.tv.ui.screens.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.siloserver.silo.tv.cast.SiloCastVolumeTracker

class TvSiloCastVolumeStateTest {

    @Test
    fun mutedStateReportsLastAudibleVolume() {
        val tracker = SiloCastVolumeTracker()
        tracker.resolve(currentVolume = 0.42)
        tracker.recordMuted(isMuted = true, currentVolume = 0.42)
        val state = tracker.resolve(currentVolume = 0.0)

        assertEquals(0.42, state.volume)
        assertTrue(state.isMuted)
    }

    @Test
    fun unmutedStateReportsCurrentVolume() {
        val tracker = SiloCastVolumeTracker(initialVolume = 0.42)
        val state = tracker.resolve(currentVolume = 0.73)

        assertEquals(0.73, state.volume)
        assertFalse(state.isMuted)
    }

    @Test
    fun missingControllerPreservesMutedStateAcrossPlayerRegistrationChanges() {
        val tracker = SiloCastVolumeTracker()
        tracker.resolve(currentVolume = 0.42)
        tracker.recordMuted(isMuted = true, currentVolume = 0.42)

        val betweenPlayers = tracker.resolve(currentVolume = null)

        assertEquals(0.42, betweenPlayers.volume)
        assertTrue(betweenPlayers.isMuted)
        assertEquals(0.42, tracker.retainedAudibleVolume())
    }
}
