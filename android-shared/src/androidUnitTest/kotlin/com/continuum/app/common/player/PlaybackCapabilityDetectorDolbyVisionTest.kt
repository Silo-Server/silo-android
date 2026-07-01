package com.continuum.app.common.player

import com.continuum.app.model.playback.HdrCapabilities
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackCapabilityDetectorDolbyVisionTest {

    @Test
    fun profile8DirectPlayIsAllowedEvenWithoutNativeDolbyVisionOutput() {
        assertTrue(
            isDirectPlayableDolbyVisionProfile(
                profile = 8,
                supportedHdr = HdrCapabilities(),
            ),
            "Dolby Vision Profile 8 has a renderable base layer; do not force a server fallback just because the display probe lacks native DV.",
        )
    }

    @Test
    fun profile7DirectPlayStaysBlockedByLaunchPolicy() {
        assertFalse(
            isDirectPlayableDolbyVisionProfile(
                profile = 7,
                supportedHdr = HdrCapabilities(dolbyVisionProfiles = listOf(7)),
            ),
            "Profile 7 remains blocked until we validate that direct route separately.",
        )
    }
}
