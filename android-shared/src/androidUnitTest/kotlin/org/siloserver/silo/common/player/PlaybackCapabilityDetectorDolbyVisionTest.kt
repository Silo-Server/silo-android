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
            "An intersection alone is not evidence the transformed stream can render.",
        )
    }

    @Test
    fun profile7ToProfile81IsAdvertisedFromHardwareDecoderAndConfirmedDolbyVisionPanel() {
        val hdr = HdrCapabilities(hdr10 = true, dolbyVisionProfiles = listOf(5, 8))
        val advertised = advertisedClientDolbyVisionTransformations(
            hdrDetails = hdr,
            nativeRpuConverterAvailable = true,
            hardwareProfile8Decoder = true,
            displayConfirmsDolbyVision = true,
        )
        assertEquals(listOf(CLIENT_DV7_TO_DV81), advertised.map { it.name })
        assertFalse(
            CLIENT_DV7_TO_HDR10 in advertised.map { it.name },
            "the HDR10 recipe stays behind fixture validation; the server strip covers it",
        )

        assertTrue(
            advertisedClientDolbyVisionTransformations(
                hdrDetails = hdr,
                nativeRpuConverterAvailable = true,
                hardwareProfile8Decoder = false,
                displayConfirmsDolbyVision = true,
            ).isEmpty(),
            "a software Profile 8 decoder is not a route",
        )
        assertTrue(
            advertisedClientDolbyVisionTransformations(
                hdrDetails = hdr,
                nativeRpuConverterAvailable = true,
                hardwareProfile8Decoder = true,
                displayConfirmsDolbyVision = false,
            ).isEmpty(),
            "an unknown or HDR10-only panel cannot carry Profile 8.1",
        )
        assertTrue(
            advertisedClientDolbyVisionTransformations(
                hdrDetails = hdr,
                nativeRpuConverterAvailable = false,
                hardwareProfile8Decoder = true,
                displayConfirmsDolbyVision = true,
            ).isEmpty(),
            "no packaged converter, no recipe",
        )
        assertTrue(
            advertisedClientDolbyVisionTransformations(
                hdrDetails = HdrCapabilities(hdr10 = true, dolbyVisionProfiles = listOf(5)),
                nativeRpuConverterAvailable = true,
                hardwareProfile8Decoder = true,
                displayConfirmsDolbyVision = true,
            ).isEmpty(),
            "the Dolby Vision policy can still withdraw Profile 8 from the intersection",
        )
    }

    @Test
    fun aQuarantinedTransformationIsNotAdvertisedEvenWithFullEvidence() {
        val advertised = advertisedClientDolbyVisionTransformations(
            hdrDetails = HdrCapabilities(hdr10 = true, dolbyVisionProfiles = listOf(8)),
            nativeRpuConverterAvailable = true,
            fixtureValidatedTransformations = setOf(CLIENT_DV7_TO_DV81, CLIENT_DV7_TO_HDR10),
            hardwareProfile8Decoder = true,
            displayConfirmsDolbyVision = true,
            quarantined = setOf(CLIENT_DV7_TO_DV81),
        )

        assertEquals(listOf(CLIENT_DV7_TO_HDR10), advertised.map { it.name })
    }

    @Test
    fun profile8DecoderAndDolbyVisionPanelEvidenceReadTheProbeShapes() {
        val hardwareP8 = ClientCodecCapabilities(
            videoDecode = listOf(
                org.siloserver.silo.model.playback.VideoDecodeCapability(
                    codec = "dolby_vision",
                    decoderName = "c2.mtk.dvhe.sth.decoder",
                    profiles = listOf("profile 8"),
                    hardware = true,
                ),
            ),
        )
        val softwareP8 = ClientCodecCapabilities(
            videoDecode = listOf(
                org.siloserver.silo.model.playback.VideoDecodeCapability(
                    codec = "dolby_vision",
                    profiles = listOf("profile 8"),
                    hardware = false,
                ),
            ),
        )
        val hardwareP5Only = ClientCodecCapabilities(
            videoDecode = listOf(
                org.siloserver.silo.model.playback.VideoDecodeCapability(
                    codec = "dolby_vision",
                    profiles = listOf("profile 5"),
                    hardware = true,
                ),
            ),
        )
        assertTrue(hasHardwareDolbyVisionProfile8Decoder(hardwareP8))
        assertFalse(hasHardwareDolbyVisionProfile8Decoder(softwareP8))
        assertFalse(hasHardwareDolbyVisionProfile8Decoder(hardwareP5Only))

        assertTrue(
            displayConfirmsDolbyVision(
                DisplayHdrProbeResult.Exact(
                    HdrCapabilities(dolbyVisionProfiles = DisplayHdrProbe.PANEL_DOLBY_VISION_PROFILES),
                    displayId = 0,
                ),
            ),
        )
        assertFalse(
            displayConfirmsDolbyVision(DisplayHdrProbeResult.Exact(HdrCapabilities(hdr10 = true), displayId = 0)),
            "an HDR10-only panel is not a Dolby Vision panel",
        )
        assertFalse(
            displayConfirmsDolbyVision(DisplayHdrProbeResult.Unknown(displayId = null, reason = "probe_failed")),
            "unknown evidence never confirms the panel",
        )
        assertFalse(displayConfirmsDolbyVision(null))
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
        val hevc10 = org.siloserver.silo.model.playback.VideoDecodeCapability(
            codec = "hevc",
            bitDepths = listOf(8, 10),
            hardware = true,
        )
        val withHevc = detector.detectPlaybackContext(
            formFactor = "tv",
            appVersion = "test",
            capabilities = ClientCodecCapabilities(
                videoDecode = listOf(hevc10),
                hdrDetails = HdrCapabilities(hdr10 = true),
            ),
        )
        val hevcWithoutRange = detector.detectPlaybackContext(
            formFactor = "tv",
            appVersion = "test",
            capabilities = ClientCodecCapabilities(videoDecode = listOf(hevc10)),
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
            CLIENT_DV8_BASE_LAYER_FALLBACK_V1_CLAIM in hevcWithoutRange.deliveries.getValue(DELIVERY_CLASS_ORIGINAL_HTTP).validatedClaims,
            "a Main10-only decoder with an unprobed display must not claim a route preflight would refuse",
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

    @Test
    fun baseLayerClaimGateAcceptsConfirmedSdrPanelForSdrBases() {
        val hevc10 = ClientCodecCapabilities(
            videoDecode = listOf(
                org.siloserver.silo.model.playback.VideoDecodeCapability(
                    codec = "hevc",
                    bitDepths = listOf(8, 10),
                    hardware = true,
                ),
            ),
        )
        assertTrue(
            canAdvertiseDv8BaseLayerFallback(hevc10, DisplayHdrProbeResult.Exact(HdrCapabilities(), displayId = 0)),
            "a confirmed SDR panel can present a compat-2 SDR base through the Main10 path",
        )
        assertFalse(
            canAdvertiseDv8BaseLayerFallback(hevc10, DisplayHdrProbeResult.Unknown(displayId = null, reason = "probe_failed")),
            "an unknown display never earns the claim",
        )
        assertFalse(
            canAdvertiseDv8BaseLayerFallback(
                hevc10,
                DisplayHdrProbeResult.Exact(HdrCapabilities(hdr10 = true), displayId = 0),
            ),
            "an HDR panel with no HDR range in the intersection means the decoder cannot signal it; no claim",
        )
        assertFalse(
            canAdvertiseDv8BaseLayerFallback(ClientCodecCapabilities(), DisplayHdrProbeResult.Exact(HdrCapabilities(), displayId = 0)),
            "no 10-bit hardware HEVC decoder, no claim",
        )
    }

    @Test
    fun baseLayerPlanRejectsAMissingBaseRangeInsteadOfAssumingSdr() {
        val route = plannedVideoRouteFor(
            decisionReason = org.siloserver.silo.model.playback.DECISION_REASON_CLIENT_DV8_BASE_LAYER,
            effectiveDynamicRange = null,
            clientTransformations = emptyList(),
        )
        assertEquals(PlannedVideoRoute.DolbyVisionProfile8BaseLayer(""), route)

        val verdict = evaluateDolbyVisionRoute(
            profile = 8,
            route = route,
            nativeHdr = HdrCapabilities(hdr10 = true, hlg = true),
        )
        assertEquals(
            Playability.DvBaseLayerOutputMismatch(profile = 8, baseRange = ""),
            verdict,
            "a base-layer plan that names no range cannot be verified and must not be read as SDR",
        )
        assertEquals(
            Playability.Supported,
            evaluateDolbyVisionRoute(
                profile = 8,
                route = PlannedVideoRoute.DolbyVisionProfile8BaseLayer("sdr"),
                nativeHdr = HdrCapabilities(),
            ),
            "an explicit SDR base needs no HDR output",
        )
    }

    @Test
    fun playbackDisplayBindingReleasesOnlyItsOwnClaim() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = PlaybackCapabilityDetector(
            context = context,
            audioCapabilityManager = AudioCapabilityManager(context),
            libassBridge = LibassBridge(false),
            buildIdentity = SiloClientBuildIdentity(buildNumber = "test", channel = "test"),
        )

        // Player-to-player navigation: the incoming player binds while the
        // outgoing player is still composed, then the outgoing one disposes.
        val outgoing = detector.bindPlaybackDisplay(1)
        assertEquals(1, detector.playbackDisplayId)
        val incoming = detector.bindPlaybackDisplay(2)
        assertEquals(2, detector.playbackDisplayId)
        assertFalse(outgoing.isActive)
        assertTrue(incoming.isActive)

        outgoing.release()
        assertEquals(2, detector.playbackDisplayId, "the stale player must not clear the newer binding")
        assertTrue(incoming.isActive)

        incoming.release()
        assertEquals(null, detector.playbackDisplayId)
        assertFalse(incoming.isActive)

        // Releasing twice, or releasing after a later rebind, stays a no-op.
        val rebound = detector.bindPlaybackDisplay(3)
        incoming.release()
        outgoing.release()
        assertEquals(3, detector.playbackDisplayId)
        rebound.release()
        assertEquals(null, detector.playbackDisplayId)
    }

    @Test
    fun rebindingOnADisplayChangeMovesTheIdAndRetiresTheOldBinding() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = PlaybackCapabilityDetector(
            context = context,
            audioCapabilityManager = AudioCapabilityManager(context),
            libassBridge = LibassBridge(false),
            buildIdentity = SiloClientBuildIdentity(buildNumber = "test", channel = "test"),
        )

        // The screens key their binding on the display id, so an Activity
        // that moves to another display produces a fresh binding and the
        // old one's release must not clear it.
        val onFirstDisplay = detector.bindPlaybackDisplay(0)
        val onSecondDisplay = detector.bindPlaybackDisplay(5)
        assertEquals(5, detector.playbackDisplayId)

        onFirstDisplay.release()
        assertEquals(5, detector.playbackDisplayId, "the retired binding must not undo the move")
        assertTrue(onSecondDisplay.isActive)
    }

    @Test
    fun bindingReleasesItselfWhenComposeForgetsOrAbandonsIt() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = PlaybackCapabilityDetector(
            context = context,
            audioCapabilityManager = AudioCapabilityManager(context),
            libassBridge = LibassBridge(false),
            buildIdentity = SiloClientBuildIdentity(buildNumber = "test", channel = "test"),
        )

        // A composition abandoned before it commits: Compose calls
        // onAbandoned and never onRemembered. The claim taken in the
        // remember factory must still be released.
        val abandoned = detector.bindPlaybackDisplay(1)
        assertEquals(1, detector.playbackDisplayId)
        abandoned.onAbandoned()
        assertEquals(null, detector.playbackDisplayId, "an abandoned composition must not leak its claim")

        // Ordinary lifecycle: remembered, then forgotten on disposal.
        val remembered = detector.bindPlaybackDisplay(2)
        remembered.onRemembered()
        assertEquals(2, detector.playbackDisplayId)
        remembered.onForgotten()
        assertEquals(null, detector.playbackDisplayId)

        // An abandoned early binding must not undo the screen's later claim.
        val early = detector.bindPlaybackDisplay(3)
        val screen = detector.bindPlaybackDisplay(3)
        screen.onRemembered()
        early.onAbandoned()
        assertEquals(3, detector.playbackDisplayId, "the screen's binding owns the display now")
        assertTrue(screen.isActive)
        screen.onForgotten()
        assertEquals(null, detector.playbackDisplayId)
    }

    @Test
    fun abandoningASpeculativeClaimRestoresTheCommittedPlayersBinding() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = PlaybackCapabilityDetector(
            context = context,
            audioCapabilityManager = AudioCapabilityManager(context),
            libassBridge = LibassBridge(false),
            buildIdentity = SiloClientBuildIdentity(buildNumber = "test", channel = "test"),
        )

        // A committed player owns display 1. A speculative composition for a
        // second player binds display 2, then is abandoned before commit.
        val committed = detector.bindPlaybackDisplay(1)
        committed.onRemembered()
        val speculative = detector.bindPlaybackDisplay(2)
        assertEquals(2, detector.playbackDisplayId)
        assertFalse(committed.isActive)

        speculative.onAbandoned()

        assertEquals(1, detector.playbackDisplayId, "the committed player's display must come back")
        assertTrue(committed.isActive, "the committed player's binding must be live again")

        committed.onForgotten()
        assertEquals(null, detector.playbackDisplayId)
    }
}
