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
import org.siloserver.silo.model.playback.CLIENT_SELECTED_AUDIO_TRACK_V1_CLAIM
import org.siloserver.silo.model.playback.CLIENT_DV8_BASE_LAYER_FALLBACK_V1_CLAIM
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
    fun phoneAndTvScopeSourceAudioSelectionClaimToOriginalHttp() {
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
                CLIENT_SELECTED_AUDIO_TRACK_V1_CLAIM in playbackContext.deliveries
                    .getValue(DELIVERY_CLASS_ORIGINAL_HTTP).validatedClaims,
                "$formFactor must prove source-track selection on original HTTP",
            )
            assertFalse(
                CLIENT_SELECTED_AUDIO_TRACK_V1_CLAIM in playbackContext.deliveries
                    .getValue(DELIVERY_CLASS_HLS).validatedClaims,
                "$formFactor must not leak the original-file claim into HLS",
            )
            assertFalse(
                CLIENT_SELECTED_AUDIO_TRACK_V1_CLAIM in playbackContext.deliveries
                    .getValue(DELIVERY_CLASS_PROGRESSIVE).validatedClaims,
                "$formFactor must not leak the original-file claim into progressive delivery",
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

    @Test
    fun baseLayerPlanAcceptsProfile8WhenOutputCarriesPromisedRange() {
        val verdict = evaluateDolbyVisionRoute(
            profile = 8,
            route = PlannedVideoRoute.DolbyVisionProfile8BaseLayer(baseRange = "hdr10"),
            nativeHdr = HdrCapabilities(hdr10 = true),
        )

        assertEquals(Playability.Supported, verdict)
    }

    @Test
    fun baseLayerPlanRejectsProfile8WhenOutputLostPromisedRange() {
        val verdict = evaluateDolbyVisionRoute(
            profile = 8,
            route = PlannedVideoRoute.DolbyVisionProfile8BaseLayer(baseRange = "hlg"),
            nativeHdr = HdrCapabilities(hdr10 = true),
        )

        assertEquals(Playability.DvBaseLayerOutputMismatch(profile = 8, baseRange = "hlg"), verdict)
        assertEquals("dv8_base_layer_output_mismatch", verdict.failureClassification())
    }

    @Test
    fun baseLayerPlanRejectsTrackThatIsNotProfile8() {
        val verdict = evaluateDolbyVisionRoute(
            profile = 5,
            route = PlannedVideoRoute.DolbyVisionProfile8BaseLayer(baseRange = "hdr10"),
            nativeHdr = HdrCapabilities(hdr10 = true),
        )

        assertEquals(Playability.DvBaseLayerMetadataMismatch(profile = 5, baseRange = "hdr10"), verdict)
    }

    @Test
    fun nativeOrUnspecifiedPlanStillRequiresDecoderAndDisplayProfile() {
        listOf(PlannedVideoRoute.NativeDolbyVision, PlannedVideoRoute.Unspecified).forEach { route ->
            assertEquals(
                Playability.UnsupportedDvProfile(8),
                evaluateDolbyVisionRoute(profile = 8, route = route, nativeHdr = HdrCapabilities(hdr10 = true)),
                "$route must not admit Dolby Vision on an output without native DV",
            )
        }
    }

    @Test
    fun plannedRouteIsDerivedFromDecisionReasonAndRecipe() {
        assertEquals(
            PlannedVideoRoute.DolbyVisionProfile8BaseLayer("hdr10"),
            plannedVideoRouteFor(
                decisionReason = org.siloserver.silo.model.playback.DECISION_REASON_CLIENT_DV8_BASE_LAYER,
                effectiveDynamicRange = "HDR10",
                clientTransformations = emptyList(),
            ),
        )
        assertEquals(
            PlannedVideoRoute.NativeDolbyVision,
            plannedVideoRouteFor("validated_original_playback", "dolby_vision", emptyList()),
        )
        assertEquals(
            PlannedVideoRoute.ClientTransformed,
            plannedVideoRouteFor("client_dv7_to_hdr10", "hdr10", listOf(CLIENT_DV7_TO_HDR10)),
        )
        assertEquals(PlannedVideoRoute.Unspecified, plannedVideoRouteFor(null, null, emptyList()))
    }

    @Test
    fun originalHttpAdvertisesBaseLayerClaimOnlyWithTenBitHardwareHevc() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = PlaybackCapabilityDetector(
            context = context,
            audioCapabilityManager = AudioCapabilityManager(context),
            libassBridge = LibassBridge(false),
            buildIdentity = SiloClientBuildIdentity(buildNumber = "test", channel = "test"),
        )
        val withHevc = detector.detectPlaybackContext(
            formFactor = "tv",
            appVersion = "test",
            capabilities = ClientCodecCapabilities(
                videoDecode = listOf(
                    org.siloserver.silo.model.playback.VideoDecodeCapability(
                        codec = "hevc",
                        bitDepths = listOf(8, 10),
                        hardware = true,
                    ),
                ),
            ),
        )
        val without = detector.detectPlaybackContext(
            formFactor = "tv",
            appVersion = "test",
            capabilities = ClientCodecCapabilities(),
        )

        assertTrue(
            CLIENT_DV8_BASE_LAYER_FALLBACK_V1_CLAIM in withHevc.deliveries.getValue(DELIVERY_CLASS_ORIGINAL_HTTP).validatedClaims,
        )
        assertFalse(
            CLIENT_DV8_BASE_LAYER_FALLBACK_V1_CLAIM in without.deliveries.getValue(DELIVERY_CLASS_ORIGINAL_HTTP).validatedClaims,
        )
        assertFalse(
            CLIENT_DV8_BASE_LAYER_FALLBACK_V1_CLAIM in withHevc.deliveries.getValue(DELIVERY_CLASS_HLS).validatedClaims,
            "the base-layer claim is scoped to original_http",
        )
        assertTrue(
            withHevc.output.display?.hdrEvidence in setOf(
                org.siloserver.silo.model.playback.OUTPUT_HDR_EVIDENCE_EXACT,
                org.siloserver.silo.model.playback.OUTPUT_HDR_EVIDENCE_UNKNOWN,
            ),
            "the output context must carry the display evidence tier",
        )
    }
}
