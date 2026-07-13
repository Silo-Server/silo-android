package org.siloserver.silo.model.playback

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.siloserver.silo.network.SiloJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlaybackProtocolV3Test {
    private val plan = PlaybackPlanV3(
        planId = "plan-1",
        sessionId = "session-1",
        delivery = PlaybackDelivery.ORIGINAL_HTTP,
        engine = PlaybackEngineKind.MEDIA3_DIRECT,
        stream = PlaybackStreamV3(
            url = "/api/v1/playback/session-1/stream",
            protocol = PlaybackStreamProtocol.HTTP_PROGRESSIVE,
            container = "mkv",
            mimeType = "video/x-matroska",
            headers = mapOf("X-Playback-Token" to "token"),
        ),
        effectiveRecipe = PlaybackEffectiveRecipeV3(
            videoCodec = "hevc",
            audioCodec = "truehd",
            width = 3840,
            height = 2160,
            dynamicRange = "dolby_vision_p8_1",
        ),
        decisionReason = "original_compatible",
        requestedMediaFileId = 42,
        effectiveMediaFileId = 84,
    )

    @Test
    fun missingProtocolFeatureRequiresServerUpgradeAndPreservesAllocatedSession() {
        val result = PlaybackDecisionResponseV3(sessionId = "legacy-session").validateForMedia3()
        assertEquals(PlaybackV3Validation.Incompatible("legacy-session"), result)
    }

    @Test
    fun legacyPlanShapeDecodesTolerantlyBeforeCompatibilityGate() {
        val decoded = SiloJson.decodeFromString<PlaybackDecisionResponseV3>(
            """{"session_id":"legacy-session","playback_plan":{"plan_id":"old","delivery":"original_http","engine":"media3_direct"}}""",
        )
        assertEquals("legacy-session", decoded.sessionId)
        assertEquals(null, decoded.playbackPlan)
        assertEquals(PlaybackV3Validation.Incompatible("legacy-session"), decoded.validateForMedia3())
    }

    @Test
    fun playablePlanMustUseMedia3() {
        val playable = PlaybackDecisionResponseV3(
            protocolVersion = 3,
            serverFeatures = listOf(PLAYBACK_PLAN_V3_FEATURE),
            outcome = PlaybackDecisionOutcome.PLAYABLE,
            playbackPlan = plan,
        ).validateForMedia3()
        assertIs<PlaybackV3Validation.Playable>(playable)

        val stale = PlaybackDecisionResponseV3(
            protocolVersion = 3,
            serverFeatures = listOf(PLAYBACK_PLAN_V3_FEATURE),
            outcome = PlaybackDecisionOutcome.PLAYABLE,
            playbackPlan = plan.copy(engine = PlaybackEngineKind.MPV_DIRECT),
        ).validateForMedia3()
        assertIs<PlaybackV3Validation.ReplanRequired>(stale)
    }

    @Test
    fun requestedAndEffectiveFileIdsRoundTrip() {
        val encoded = SiloJson.encodeToString(plan)
        val decoded = SiloJson.decodeFromString<PlaybackPlanV3>(encoded)
        assertEquals(42, decoded.requestedMediaFileId)
        assertEquals(84, decoded.effectiveMediaFileId)
    }

    @Test
    fun adaptationUnavailableIsTerminal() {
        val result = PlaybackDecisionResponseV3(
            protocolVersion = 3,
            serverFeatures = listOf(PLAYBACK_PLAN_V3_FEATURE),
            outcome = PlaybackDecisionOutcome.ADAPTATION_UNAVAILABLE,
            terminal = PlaybackTerminalV3("transcoding_disabled", "No compatible direct route.", false),
        ).validateForMedia3()
        assertEquals(
            PlaybackV3Validation.Terminal("transcoding_disabled", "No compatible direct route.", false),
            result,
        )
    }

    @Test
    fun unsupportedHeaderRefreshFailsClosed() {
        val result = PlaybackDecisionResponseV3(
            protocolVersion = 3,
            serverFeatures = listOf(PLAYBACK_PLAN_V3_FEATURE),
            outcome = PlaybackDecisionOutcome.PLAYABLE,
            playbackPlan = plan.copy(
                stream = plan.stream.copy(
                    headerRefresh = PlaybackHeaderRefreshMode.REFRESH_ENDPOINT,
                    headerRefreshUrl = "/refresh",
                ),
            ),
        ).validateForMedia3()
        assertEquals(
            "unsupported_header_refresh",
            assertIs<PlaybackV3Validation.Terminal>(result).reason,
        )
    }

    @Test
    fun unknownClientTransformationRequestsAReplan() {
        val result = PlaybackDecisionResponseV3(
            protocolVersion = 3,
            serverFeatures = listOf(PLAYBACK_PLAN_V3_FEATURE),
            outcome = PlaybackDecisionOutcome.PLAYABLE,
            playbackPlan = plan.copy(
                transformations = listOf(
                    PlaybackTransformationV3(
                        name = "client_future_transform",
                        executor = PlaybackTransformationExecutor.CLIENT,
                        recipeVersion = "99",
                    ),
                ),
            ),
        ).validateForMedia3()

        assertEquals(
            "unsupported_client_transformation:client_future_transform:99",
            assertIs<PlaybackV3Validation.ReplanRequired>(result).reason,
        )
    }

    @Test
    fun clientTransformationRequiresDirectMedia3Engine() {
        val result = PlaybackDecisionResponseV3(
            protocolVersion = 3,
            serverFeatures = listOf(PLAYBACK_PLAN_V3_FEATURE),
            outcome = PlaybackDecisionOutcome.PLAYABLE,
            playbackPlan = plan.copy(
                engine = PlaybackEngineKind.MEDIA3_HLS,
                transformations = listOf(
                    PlaybackTransformationV3(
                        name = CLIENT_DV7_TO_HDR10,
                        executor = PlaybackTransformationExecutor.CLIENT,
                        recipeVersion = CLIENT_DV_TRANSFORM_RECIPE_VERSION,
                    ),
                ),
            ),
        ).validateForMedia3()

        assertEquals(
            "client_transformation_requires_media3_direct",
            assertIs<PlaybackV3Validation.ReplanRequired>(result).reason,
        )
    }

    @Test
    fun clientTransformationExecutionPreservesOwnershipAndRejectsConflicts() {
        val reservedServerTransform = plan.copy(
            transformations = listOf(
                PlaybackTransformationV3(
                    name = CLIENT_DV7_TO_DV81,
                    executor = PlaybackTransformationExecutor.SERVER,
                ),
            ),
        )
        assertTrue(reservedServerTransform.executableMedia3ClientTransformations().isEmpty())

        val conflicting = PlaybackDecisionResponseV3(
            protocolVersion = 3,
            serverFeatures = listOf(PLAYBACK_PLAN_V3_FEATURE),
            outcome = PlaybackDecisionOutcome.PLAYABLE,
            playbackPlan = plan.copy(
                transformations = listOf(
                    PlaybackTransformationV3(
                        CLIENT_DV7_TO_DV81,
                        PlaybackTransformationExecutor.CLIENT,
                    ),
                    PlaybackTransformationV3(
                        CLIENT_DV7_TO_HDR10,
                        PlaybackTransformationExecutor.CLIENT,
                    ),
                ),
            ),
        ).validateForMedia3()
        assertEquals(
            "conflicting_client_video_transformations",
            assertIs<PlaybackV3Validation.ReplanRequired>(conflicting).reason,
        )
    }

    @Test
    fun attemptKeyIsCanonicalAndOutputRouteAware() {
        val a = plan.copy(
            transformations = listOf(
                PlaybackTransformationV3("audio_adapt"),
                PlaybackTransformationV3("container_remux"),
            ),
        ).planAttemptKey(7, listOf("pcm:truehd:8", "transport_reopen"))
        val b = plan.copy(
            transformations = listOf(
                PlaybackTransformationV3("container_remux"),
                PlaybackTransformationV3("audio_adapt"),
            ),
        ).planAttemptKey(7, listOf("transport_reopen", "pcm:truehd:8"))
        assertEquals(a, b)
        assertNotEquals(a, plan.planAttemptKey(8, listOf("pcm:truehd:8", "transport_reopen")))
        assertTrue(a.matches(Regex("v3:[0-9a-f]{16}")))
    }

    @Test
    fun attemptKeyMatchesGoClientTransformationFixture() {
        val fixture = plan.copy(
            planId = "plan:dv81-fixture",
            delivery = PlaybackDelivery.ORIGINAL_HTTP,
            stream = plan.stream.copy(
                protocol = PlaybackStreamProtocol.HTTP_PROGRESSIVE,
                container = "mkv",
            ),
            effectiveRecipe = plan.effectiveRecipe.copy(
                videoCodec = "hevc",
                audioCodec = "truehd",
                width = 3840,
                height = 2160,
                bitrateKbps = 65_000,
                dynamicRange = "dolby_vision",
            ),
            subtitle = PlaybackSubtitleDecisionV3(mode = PlaybackSubtitleModeV3.OFF),
            transformations = listOf(
                PlaybackTransformationV3(
                    name = CLIENT_DV7_TO_DV81,
                    executor = PlaybackTransformationExecutor.CLIENT,
                    recipeVersion = "1",
                ),
            ),
        )

        assertEquals("v3:2a88b5e686373440", fixture.planAttemptKey(9))
    }

    @Test
    fun attemptKeyMatchesGoDeviceQuirkFixture() {
        val fixture = plan.copy(
            planId = "plan:quirk",
            delivery = PlaybackDelivery.ORIGINAL_HTTP,
            stream = plan.stream.copy(
                protocol = PlaybackStreamProtocol.HTTP_PROGRESSIVE,
                container = "mkv",
            ),
            effectiveRecipe = plan.effectiveRecipe.copy(
                videoCodec = "hevc",
                audioCodec = "eac3",
                width = 3840,
                height = 2160,
                bitrateKbps = 60_000,
                dynamicRange = "dolby_vision",
            ),
            subtitle = PlaybackSubtitleDecisionV3(mode = PlaybackSubtitleModeV3.OFF),
            transformations = emptyList(),
            appliedQuirks = listOf(
                PlaybackAppliedQuirkV3(
                    id = "android.fire_tv.dv8_hdr10plus_sei_v1",
                    registryRevision = "2026-07-13.1",
                    action = "client_runtime_correction",
                ),
            ),
            runtimeCorrections = listOf(CLIENT_DV8_HDR10_PLUS_SANITIZER),
        )

        assertEquals("v3:8d843bfffeb3adc3", fixture.planAttemptKey(9))
    }

    @Test
    fun unknownRuntimeCorrectionRequestsReplan() {
        val response = PlaybackDecisionResponseV3(
            protocolVersion = 3,
            serverFeatures = listOf(PLAYBACK_PLAN_V3_FEATURE),
            outcome = PlaybackDecisionOutcome.PLAYABLE,
            playbackPlan = plan.copy(runtimeCorrections = listOf("future_runtime_fix")),
        ).validateForMedia3()

        assertEquals(
            "unsupported_client_runtime_correction:future_runtime_fix",
            assertIs<PlaybackV3Validation.ReplanRequired>(response).reason,
        )
    }

    @Test
    fun startRequestNeverForcesAPlayMethod() {
        val encoded = SiloJson.encodeToString(
            PlaybackStartRequestV3(
                fileId = 12,
                profileId = "profile",
                playbackAttemptId = "attempt",
                subtitleFidelityPreference = SubtitleFidelityPreference.PRESERVE,
                outputRouteGeneration = 4,
                capabilities = ClientCodecCapabilities(),
                clientPlaybackContext = ClientPlaybackContext(formFactor = "tv", appVersion = "test"),
            ),
        )
        assertFalse(encoded.contains("play_method"))
        assertTrue(encoded.contains("\"protocol_version\":3"))
        assertTrue(encoded.contains("media3_only"))
    }

    @Test
    fun startAndReplanRequestsCarryCurrentNetworkEvidence() {
        val start = SiloJson.encodeToString(
            PlaybackStartRequestV3(
                fileId = 12,
                profileId = "profile",
                playbackAttemptId = "attempt",
                subtitleFidelityPreference = SubtitleFidelityPreference.PRESERVE,
                outputRouteGeneration = 4,
                metered = true,
                bandwidthEstimateKbps = 22_000,
                bandwidthCapKbps = 15_000,
                capabilities = ClientCodecCapabilities(),
                clientPlaybackContext = ClientPlaybackContext(formFactor = "tv", appVersion = "test"),
            ),
        )
        val replan = SiloJson.encodeToString(
            PlaybackReplanRequestV3(
                playbackAttemptId = "attempt",
                replanRequestId = "request",
                failedPlanId = "plan",
                planAttemptId = "plan-attempt",
                planAttemptKey = "key",
                attemptedPlanKeys = listOf("key"),
                attemptCount = 2,
                positionSeconds = 10.0,
                outputRouteGeneration = 4,
                metered = true,
                bandwidthEstimateKbps = 9_000,
                bandwidthCapKbps = 8_000,
                selectedTracks = SelectedPlaybackTracksV3(),
                failure = PlaybackFailureV3("transport_stall"),
                capabilities = ClientCodecCapabilities(),
                clientPlaybackContext = ClientPlaybackContext(formFactor = "tv", appVersion = "test"),
            ),
        )

        assertTrue(start.contains("\"bandwidth_estimate_kbps\":22000"))
        assertTrue(start.contains("\"bandwidth_cap_kbps\":15000"))
        assertTrue(replan.contains("\"bandwidth_estimate_kbps\":9000"))
        assertTrue(replan.contains("\"bandwidth_cap_kbps\":8000"))
        assertFalse(replan.contains("\"operation\""))
    }

    @Test
    fun seekReanchorOperationIsExplicitAndNegotiated() {
        val start = SiloJson.encodeToString(
            PlaybackStartRequestV3(
                fileId = 12,
                profileId = "profile",
                playbackAttemptId = "attempt",
                subtitleFidelityPreference = SubtitleFidelityPreference.PRESERVE,
                outputRouteGeneration = 4,
                capabilities = ClientCodecCapabilities(),
                clientPlaybackContext = ClientPlaybackContext(formFactor = "tv", appVersion = "test"),
            ),
        )
        val reanchor = SiloJson.encodeToString(
            PlaybackReplanRequestV3(
                operation = SEEK_REANCHOR_V3_OPERATION,
                playbackAttemptId = "attempt",
                replanRequestId = "request",
                failedPlanId = "plan",
                planAttemptId = "plan-attempt",
                planAttemptKey = "key",
                attemptedPlanKeys = listOf("key"),
                attemptCount = 1,
                positionSeconds = 10.0,
                outputRouteGeneration = 4,
                selectedTracks = SelectedPlaybackTracksV3(),
                failure = PlaybackFailureV3(SEEK_REANCHOR_V3_OPERATION),
                capabilities = ClientCodecCapabilities(),
                clientPlaybackContext = ClientPlaybackContext(formFactor = "tv", appVersion = "test"),
            ),
        )

        assertTrue(start.contains(SEEK_REANCHOR_V3_FEATURE))
        assertTrue(reanchor.contains("\"operation\":\"seek_reanchor\""))
    }

    @Test
    fun layoutAwarePassthroughSerializesExactRouteEvidence() {
        val passthrough = AudioPassthroughCapabilities(
            passthroughCodecs = listOf("truehd"),
            maxChannels = 8,
            entries = listOf(
                AudioPassthroughEntry(
                    codec = "truehd",
                    channelCounts = listOf(2, 6, 8),
                    layouts = listOf("stereo", "5.1(side)", "7.1"),
                ),
            ),
        )
        val encoded = SiloJson.encodeToString(
            ClientPlaybackContext(
                formFactor = "tv",
                appVersion = "test",
                features = listOf(LAYOUT_AWARE_PASSTHROUGH_FEATURE),
                output = PlaybackOutputContext(audioPassthrough = passthrough),
            ),
        )

        assertTrue(encoded.contains("layout_aware_passthrough"))
        assertTrue(encoded.contains("\"channel_counts\":[2,6,8]"))
        assertTrue(encoded.contains("\"layouts\":[\"stereo\",\"5.1(side)\",\"7.1\"]"))
    }
}
