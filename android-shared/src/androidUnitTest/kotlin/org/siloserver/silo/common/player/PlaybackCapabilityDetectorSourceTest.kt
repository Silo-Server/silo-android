package org.siloserver.silo.common.player

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class PlaybackCapabilityDetectorSourceTest {
    private val source = File(
        "src/androidMain/kotlin/org/siloserver/silo/common/player/PlaybackCapabilityDetector.kt",
    ).readText()

    @Test
    fun detectorAdvertisesOnlyMedia3ValidatedContainersAndEngines() {
        assertTrue(
            source.contains("containers = media3OriginalPlaybackContainers"),
            "capabilities must use the Media3 extractor policy",
        )
        assertTrue(
            !source.contains("MPV_DIRECT") && !source.contains("mpvOriginalPlaybackContainers"),
            "the outgoing capability map must not advertise removed engines",
        )
    }

    @Test
    fun detectorOnlyAdvertisesLibassCapabilitiesAfterRuntimeProbes() {
        assertTrue(source.contains("val libassRendering = libassBridge.isRenderingSupported"))
        assertTrue(source.contains("val libassEmbeddedFonts = libassBridge.isEmbeddedFontsSupported"))
        assertTrue(source.contains("val libassDirectFidelity = libassRendering && libassEmbeddedFonts"))
        assertTrue(source.contains("assStyling = libassDirectFidelity"))
        assertTrue(source.contains("assStyling = libassRendering"))
        assertTrue(source.contains("fontAttachments = libassEmbeddedFonts"))
    }

    @Test
    fun detectorAdvertisesEmbeddedButNotSidecarBitmapSubtitles() {
        assertTrue(
            source.contains("embeddedBitmap = true"),
            "Media3 direct playback supports embedded PGS, VobSub, and DVB subtitle parsers.",
        )
        assertTrue(
            source.contains("sidecarBitmap = false"),
            "Raw bitmap sidecars are not mounted into Silo MediaItems.",
        )
    }
}
