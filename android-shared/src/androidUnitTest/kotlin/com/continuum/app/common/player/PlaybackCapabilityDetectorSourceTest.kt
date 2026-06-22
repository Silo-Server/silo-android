package com.continuum.app.common.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class PlaybackCapabilityDetectorSourceTest {
    private val source = File(
        "src/androidMain/kotlin/com/continuum/app/common/player/PlaybackCapabilityDetector.kt",
    ).readText()

    @Test
    fun detectorAdvertisesDirectContainersConsistentlyAcrossLegacyAndV2Payloads() {
        assertTrue(
            source.contains("containers = directContainers"),
            "legacy capabilities should advertise all direct-capable containers for the current device",
        )
        assertTrue(
            source.contains("if (mpvSupported)") &&
                source.contains("directOriginalPlaybackContainers") &&
                source.contains("media3OriginalPlaybackContainers"),
            "legacy direct containers must account for the MPV device floor",
        )
        assertTrue(
            source.contains("containers = media3OriginalPlaybackContainers") &&
                source.contains("containers = mpvOriginalPlaybackContainers"),
            "v2 engine envelopes should keep Media3 and MPV direct containers separate",
        )
    }
}
