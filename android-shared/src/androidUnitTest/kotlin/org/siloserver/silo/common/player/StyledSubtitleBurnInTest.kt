package org.siloserver.silo.common.player

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StyledSubtitleBurnInTest {

    @Test
    fun burnsStyledTrackDuringFullTranscode() {
        assertTrue(
            shouldBurnStyledSubtitle(
                isRemux = false,
                subtitleTrackIndex = 0,
                subtitleCodec = "ass",
            ),
        )
        assertTrue(
            shouldBurnStyledSubtitle(
                isRemux = false,
                subtitleTrackIndex = 1,
                subtitleCodec = "SSA",
            ),
        )
    }

    @Test
    fun neverBurnsWhenFidelityOrTogglingWouldRegress() {
        // Plain text tracks stay client-rendered so toggling needs no restart.
        assertFalse(shouldBurnStyledSubtitle(false, 0, "subrip"))
        // Remux has no video encode to burn into.
        assertFalse(shouldBurnStyledSubtitle(true, 0, "ass"))
        // No subtitle selected.
        assertFalse(shouldBurnStyledSubtitle(false, null, "ass"))
        assertFalse(shouldBurnStyledSubtitle(false, 0, null))
    }
}
