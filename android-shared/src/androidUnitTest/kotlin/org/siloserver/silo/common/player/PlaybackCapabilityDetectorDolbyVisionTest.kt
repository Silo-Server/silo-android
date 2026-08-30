package org.siloserver.silo.common.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.siloserver.silo.common.network.SiloClientBuildIdentity
import org.siloserver.silo.libass.LibassBridge
import org.siloserver.silo.model.playback.HdrCapabilities
import org.siloserver.silo.model.playback.CLIENT_DV7_TO_DV81
import org.siloserver.silo.model.playback.CLIENT_DV7_TO_HDR10
import org.siloserver.silo.model.playback.ClientCodecCapabilities
import org.siloserver.silo.model.playback.DELIVERY_CLASS_HLS
import org.siloserver.silo.model.playback.DELIVERY_CLASS_ORIGINAL_HTTP
import org.siloserver.silo.model.playback.DELIVERY_CLASS_PROGRESSIVE
import org.siloserver.silo.model.playback.NATIVE_HLS_PLAYBACK_V1_FEATURE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class PlaybackCapabilityDetectorDolbyVisionTest {

    @Test
    fun phoneAndTvAdvertiseNativeHlsOnlyOnMedia3HlsDelivery() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = PlaybackCapabilityDetector(
            context = context,
            audioCapabilityManager = AudioCapabilityManager(context),
            libassBridge = LibassBridge(false),
            buildIdentity = SiloClientBuildIdentity(buildNumber = "test", channel = "test"),
        )

        listOf("mobile", "tv").forEach { formFactor ->
            val playbackContext = detector.detectPlaybackContext(
                formFactor = formFactor,
                appVersion = "test",
                capabilities = ClientCodecCapabilities(),
            )

            assertTrue(
                NATIVE_HLS_PLAYBACK_V1_FEATURE in playbackContext.deliveries.getValue(DELIVERY_CLASS_HLS).features,
                "$formFactor must identify its local Media3 HLS pipeline",
            )
            assertFalse(
                NATIVE_HLS_PLAYBACK_V1_FEATURE in playbackContext.deliveries.getValue(DELIVERY_CLASS_ORIGINAL_HTTP).features,
                "$formFactor must not apply the HLS sample-entry contract to original HTTP",
            )
            assertFalse(
                NATIVE_HLS_PLAYBACK_V1_FEATURE in playbackContext.deliveries.getValue(DELIVERY_CLASS_PROGRESSIVE).features,
                "$formFactor must not apply the HLS sample-entry contract to progressive delivery",
            )
        }
    }

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
