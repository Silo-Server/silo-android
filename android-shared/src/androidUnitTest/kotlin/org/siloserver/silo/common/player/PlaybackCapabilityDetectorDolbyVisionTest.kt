package org.siloserver.silo.common.player

import org.siloserver.silo.model.playback.HdrCapabilities
import org.siloserver.silo.model.playback.CLIENT_DV7_TO_DV81
import org.siloserver.silo.model.playback.CLIENT_DV7_TO_HDR10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackCapabilityDetectorDolbyVisionTest {

    @Test
    fun profile8DirectPlayRequiresAValidatedNativeOutputRoute() {
        assertFalse(
            isDirectPlayableDolbyVisionProfile(
                profile = 8,
                supportedHdr = HdrCapabilities(),
            ),
            "A Profile 8 base layer is not safe to assume without server-supplied variant and range metadata.",
        )
    }

    @Test
    fun profile7DirectPlayRequiresNativeDualLayerSupport() {
        assertFalse(
            isDirectPlayableDolbyVisionProfile(
                profile = 7,
                supportedHdr = HdrCapabilities(dolbyVisionProfiles = listOf(5, 8)),
            ),
            "Without a native dual-layer DV decoder, Media3 cannot direct-play P7; the server must provide a compatible route.",
        )
    }

    @Test
    fun profile7DirectPlayIsAllowedWithNativeDualLayerSupport() {
        assertTrue(
            isDirectPlayableDolbyVisionProfile(
                profile = 7,
                supportedHdr = HdrCapabilities(dolbyVisionProfiles = listOf(5, 7, 8)),
            ),
            "Devices whose DV decoder claims dual-layer profiles with multi-instance HEVC (Shield-class) can direct-play P7.",
        )
    }

    @Test
    fun profile5DirectPlayRequiresNativeDolbyVisionDecoder() {
        assertFalse(
            isDirectPlayableDolbyVisionProfile(
                profile = 5,
                supportedHdr = HdrCapabilities(),
            ),
            "P5 has no backward-compatible base layer; without a DV decoder the Media3 route cannot render it.",
        )
    }

    @Test
    fun hdr10OutputAndPackagedConverterDoNotAdvertiseAnUnvalidatedClientTransformation() {
        val transformations = advertisedClientDolbyVisionTransformations(
            hdrDetails = HdrCapabilities(
                hdr10 = true,
                dolbyVisionProfiles = listOf(8),
            ),
            nativeRpuConverterAvailable = true,
        )

        assertTrue(
            transformations.isEmpty(),
            "Runtime prerequisites cannot be promoted to validated v3 capability claims.",
        )
    }

    @Test
    fun clientTransformationsRequireExactFixtureValidationAndRuntimePrerequisites() {
        val transformations = advertisedClientDolbyVisionTransformations(
            hdrDetails = HdrCapabilities(
                hdr10 = true,
                dolbyVisionProfiles = listOf(8),
            ),
            nativeRpuConverterAvailable = true,
            fixtureValidatedTransformations = setOf(
                CLIENT_DV7_TO_DV81,
                CLIENT_DV7_TO_HDR10,
            ),
        )

        assertEquals(
            listOf(CLIENT_DV7_TO_DV81, CLIENT_DV7_TO_HDR10),
            transformations.map { it.name },
        )
    }
}
