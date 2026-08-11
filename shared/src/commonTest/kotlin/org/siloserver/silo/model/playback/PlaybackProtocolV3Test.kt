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
    private val neutralServerFeatures = listOf(
        PLAYBACK_PLAN_V3_FEATURE,
        NEUTRAL_PLAYBACK_V3_CONTRACT_FEATURE,
    )
    private val plan = PlaybackPlanV3(
        planId = "plan-1",
        planAttemptKey = "v3:0000000000000001",
        sessionId = "session-1",
        delivery = PlaybackDelivery.ORIGINAL_HTTP,
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
    fun removedDraftSidecarsAreIgnored() {
        val decoded = SiloJson.decodeFromString<PlaybackPlanV3>(
            """
            {
              "plan_id": "plan",
              "delivery": "original_http",
              "engine": "media3_direct",
              "stream": {"url": "/stream/session", "protocol": "http_progressive"},
              "subtitle": {
                "mode": "convert",
                "track_id": "file:42:subtitle:0",
                "sidecars": [{
                  "track_id": "removed",
                  "index": 1,
                  "url": "/removed.srt",
                  "mime_type": "application/x-subrip",
                  "format": "srt"
                }],
                "artifact": {
                  "url": "/stream/session/subtitles/0.vtt",
                  "mime_type": "text/vtt",
                  "format": "vtt",
                  "timing_origin_seconds": 0
                }
              },
              "decision_reason": "test"
            }
            """.trimIndent(),
        )

        assertTrue(decoded.subtitle.inventory.isEmpty())
        assertEquals("/stream/session/subtitles/0.vtt", decoded.subtitle.artifact?.url)
    }

    @Test
    fun missingProtocolFeatureRequiresServerUpgradeAndPreservesAllocatedSession() {
        val result = PlaybackDecisionResponseV3(sessionId = "legacy-session").validateForMedia3()
        assertEquals(PlaybackV3Validation.Incompatible("legacy-session"), result)
    }

    @Test
    fun preNeutralV3ServerRequiresUpgradeInsteadOfEnteringAnIncompatibleSession() {
        val result = PlaybackDecisionResponseV3(
            protocolVersion = PLAYBACK_PROTOCOL_V3,
            serverFeatures = listOf(PLAYBACK_PLAN_V3_FEATURE),
            sessionId = "draft-v3-session",
        ).validateForMedia3()

        assertEquals(PlaybackV3Validation.Incompatible("draft-v3-session"), result)
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
    fun playablePlanValidatesOnDeliveryAlone() {
        // The neutral contract names a delivery class, not a client engine, so
        // there is nothing left for the client to disagree with the server
        // about here: a well-formed plan on any delivery is playable.
        val playable = PlaybackDecisionResponseV3(
            protocolVersion = 3,
            serverFeatures = neutralServerFeatures,
            outcome = PlaybackDecisionOutcome.PLAYABLE,
            playbackPlan = plan,
        ).validateForMedia3()
        assertIs<PlaybackV3Validation.Playable>(playable)

        val hls = PlaybackDecisionResponseV3(
            protocolVersion = 3,
            serverFeatures = neutralServerFeatures,
            outcome = PlaybackDecisionOutcome.PLAYABLE,
            playbackPlan = plan.copy(
                delivery = PlaybackDelivery.SERVER_TRANSCODE_HLS,
                stream = plan.stream.copy(protocol = PlaybackStreamProtocol.HLS),
            ),
        ).validateForMedia3()
        assertIs<PlaybackV3Validation.Playable>(hls)
    }

    @Test
    fun playablePlanRequiresAServerMintedAttemptKey() {
        val result = PlaybackDecisionResponseV3(
            protocolVersion = 3,
            serverFeatures = neutralServerFeatures,
            outcome = PlaybackDecisionOutcome.PLAYABLE,
            playbackPlan = plan.copy(planAttemptKey = ""),
        ).validateForMedia3()

        assertEquals(
            "invalid_playback_plan",
            assertIs<PlaybackV3Validation.Terminal>(result).reason,
        )
    }

    @Test
    fun playablePlanRejectsAGappedOrUndeliverableSubtitleInventory() {
        val result = PlaybackDecisionResponseV3(
            protocolVersion = 3,
            serverFeatures = neutralServerFeatures,
            outcome = PlaybackDecisionOutcome.PLAYABLE,
            playbackPlan = plan.copy(
                subtitle = PlaybackSubtitleDecisionV3(
                    inventory = listOf(
                        PlaybackSubtitleInventoryItemV3(
                            trackId = "track",
                            combinedIndex = 1,
                            source = "external",
                            delivery = "sidecar",
                        ),
                    ),
                ),
            ),
        ).validateForMedia3()

        assertEquals(
            "invalid_playback_plan",
            assertIs<PlaybackV3Validation.Terminal>(result).reason,
        )
    }

    @Test
    fun selectedSubtitleMustResolveAgainstAnEmptyAuthoritativeInventory() {
        val result = PlaybackDecisionResponseV3(
            protocolVersion = PLAYBACK_PROTOCOL_V3,
            serverFeatures = neutralServerFeatures,
            outcome = PlaybackDecisionOutcome.PLAYABLE,
            playbackPlan = plan.copy(
                selectedTracks = SelectedPlaybackTracksV3(
                    subtitle = PlaybackTrackIdentityV3("missing-track", 0),
                ),
                subtitle = PlaybackSubtitleDecisionV3(inventory = emptyList()),
            ),
        ).validateForMedia3()

        assertEquals(
            "invalid_playback_plan",
            assertIs<PlaybackV3Validation.Terminal>(result).reason,
        )
    }

    @Test
    fun subtitleArtifactRequiresASelectedInventoryIdentity() {
        val track = PlaybackSubtitleInventoryItemV3(
            trackId = "file:84:subtitle:0",
            combinedIndex = 0,
            source = "external",
            codec = "srt",
            delivery = SUBTITLE_DELIVERY_SIDECAR,
            url = "/api/v1/playback/session-1/subtitles/0.vtt",
        )
        val result = PlaybackDecisionResponseV3(
            protocolVersion = PLAYBACK_PROTOCOL_V3,
            serverFeatures = neutralServerFeatures,
            outcome = PlaybackDecisionOutcome.PLAYABLE,
            playbackPlan = plan.copy(
                selectedTracks = SelectedPlaybackTracksV3(subtitle = null),
                subtitle = PlaybackSubtitleDecisionV3(
                    mode = PlaybackSubtitleModeV3.CONVERT,
                    trackId = track.trackId,
                    artifact = PlaybackSubtitleArtifactV3(
                        url = track.url.orEmpty(),
                        mimeType = "text/vtt",
                        format = "vtt",
                    ),
                    inventory = listOf(track),
                ),
            ),
        ).validateForMedia3()

        assertEquals(
            "invalid_playback_plan",
            assertIs<PlaybackV3Validation.Terminal>(result).reason,
        )
    }

    @Test
    fun unknownUnselectedSubtitleDeliveryRejectsThePlan() {
        val result = PlaybackDecisionResponseV3(
            protocolVersion = PLAYBACK_PROTOCOL_V3,
            serverFeatures = neutralServerFeatures,
            outcome = PlaybackDecisionOutcome.PLAYABLE,
            playbackPlan = plan.copy(
                subtitle = PlaybackSubtitleDecisionV3(
                    inventory = listOf(
                        PlaybackSubtitleInventoryItemV3(
                            trackId = "future-track",
                            combinedIndex = 0,
                            source = "external",
                            delivery = "future_delivery",
                        ),
                    ),
                ),
            ),
        ).validateForMedia3()

        assertEquals(
            "invalid_playback_plan",
            assertIs<PlaybackV3Validation.Terminal>(result).reason,
        )
    }

    @Test
    fun unknownSelectedSubtitleDeliveryRejectsThePlan() {
        val selected = PlaybackTrackIdentityV3("future-track", 0)
        val result = PlaybackDecisionResponseV3(
            protocolVersion = PLAYBACK_PROTOCOL_V3,
            serverFeatures = neutralServerFeatures,
            outcome = PlaybackDecisionOutcome.PLAYABLE,
            playbackPlan = plan.copy(
                selectedTracks = SelectedPlaybackTracksV3(subtitle = selected),
                subtitle = PlaybackSubtitleDecisionV3(
                    inventory = listOf(
                        PlaybackSubtitleInventoryItemV3(
                            trackId = selected.id,
                            combinedIndex = selected.index ?: 0,
                            source = "external",
                            delivery = "future_delivery",
                        ),
                    ),
                ),
            ),
        ).validateForMedia3()

        assertEquals(
            "invalid_playback_plan",
            assertIs<PlaybackV3Validation.Terminal>(result).reason,
        )
    }

    @Test
    fun subtitleSelectedByTrackIdAloneStaysPlayable() {
        val trackId = "file:42:subtitle:0"
        val result = PlaybackDecisionResponseV3(
            protocolVersion = PLAYBACK_PROTOCOL_V3,
            serverFeatures = neutralServerFeatures,
            outcome = PlaybackDecisionOutcome.PLAYABLE,
            playbackPlan = plan.copy(
                selectedTracks = SelectedPlaybackTracksV3(
                    subtitle = PlaybackTrackIdentityV3(trackId, index = null),
                ),
                subtitle = PlaybackSubtitleDecisionV3(
                    inventory = listOf(
                        PlaybackSubtitleInventoryItemV3(
                            trackId = trackId,
                            combinedIndex = 0,
                            source = "external",
                            delivery = SUBTITLE_DELIVERY_SIDECAR,
                            url = "/stream/session-1/subtitles/0.vtt",
                        ),
                    ),
                ),
            ),
        ).validateForMedia3()

        val playable = assertIs<PlaybackV3Validation.Playable>(result)
        assertEquals(0, playable.plan.resolvedSelectedSubtitleIndex())
    }

    @Test
    fun requestedAndEffectiveFileIdsRoundTrip() {
        val encoded = SiloJson.encodeToString(plan)
        val decoded = SiloJson.decodeFromString<PlaybackPlanV3>(encoded)
        assertEquals(42, decoded.requestedMediaFileId)
        assertEquals(84, decoded.effectiveMediaFileId)
    }

    @Test
    fun sourceColorRangeRoundTrips() {
        val encoded = SiloJson.encodeToString(
            plan.copy(source = PlaybackSourceDescriptorV3(colorRange = "pc")),
        )

        val decoded = SiloJson.decodeFromString<PlaybackPlanV3>(encoded)

        assertEquals("pc", decoded.source.colorRange)
    }

    @Test
    fun adaptationUnavailableIsTerminal() {
        val result = PlaybackDecisionResponseV3(
            protocolVersion = 3,
            serverFeatures = neutralServerFeatures,
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
            serverFeatures = neutralServerFeatures,
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
            serverFeatures = neutralServerFeatures,
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
    fun clientTransformationRequiresOriginalDelivery() {
        // A client-side Dolby Vision rewrite edits the elementary stream on its
        // way into the decoder, which the client only owns while playing the
        // original file. On a server-produced delivery the server already made
        // the dynamic-range decision.
        val result = PlaybackDecisionResponseV3(
            protocolVersion = 3,
            serverFeatures = neutralServerFeatures,
            outcome = PlaybackDecisionOutcome.PLAYABLE,
            playbackPlan = plan.copy(
                delivery = PlaybackDelivery.SERVER_TRANSCODE_HLS,
                stream = plan.stream.copy(protocol = PlaybackStreamProtocol.HLS),
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
            "client_transformation_requires_original_delivery",
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
            serverFeatures = neutralServerFeatures,
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
    fun attemptKeyIsServerOwnedAndEchoedVerbatim() {
        // The client no longer derives attempt keys: the server mints them and
        // the client stores and echoes the opaque value. Anything the client
        // did locally is reported as `local_mutations` for the server to fold
        // into the next key, rather than hashed here.
        val decoded = SiloJson.decodeFromString<PlaybackDecisionResponseV3>(
            """{"protocol_version":3,"server_features":["$PLAYBACK_PLAN_V3_FEATURE",""" +
                """"$NEUTRAL_PLAYBACK_V3_CONTRACT_FEATURE"],"outcome":"playable",""" +
                """"playback_plan":{"plan_id":"p","session_id":"s","plan_attempt_key":"v3:server-minted",""" +
                """"delivery":"original_http","stream":{"url":"/s","protocol":"http_progressive"},""" +
                """"decision_reason":"validated_original_playback"}}""",
        )
        assertEquals("v3:server-minted", decoded.playbackPlan?.planAttemptKey)

        val echoed = SiloJson.encodeToString(
            PlaybackReplanRequestV3(
                clientFeatures = PLAYBACK_START_CLIENT_FEATURES_V3,
                playbackAttemptId = "attempt",
                replanRequestId = "request",
                failedPlanId = "p",
                planAttemptId = "plan-attempt",
                planAttemptKey = "v3:server-minted",
                attemptedPlanKeys = listOf("v3:server-minted"),
                localMutations = listOf("pcm:truehd:8"),
                attemptCount = 2,
                positionSeconds = 10.0,
                selectedTracks = SelectedPlaybackTracksV3(),
                failure = PlaybackFailureV3("transport_stall"),
                capabilities = ClientCodecCapabilities(),
                clientPlaybackContext = ClientPlaybackContext(formFactor = "tv", appVersion = "test"),
            ),
        )
        assertTrue(echoed.contains("\"plan_attempt_key\":\"v3:server-minted\""))
        assertTrue(echoed.contains("\"local_mutations\":[\"pcm:truehd:8\"]"))
    }

    @Test
    fun unknownRuntimeCorrectionRequestsReplan() {
        val response = PlaybackDecisionResponseV3(
            protocolVersion = 3,
            serverFeatures = neutralServerFeatures,
            outcome = PlaybackDecisionOutcome.PLAYABLE,
            playbackPlan = plan.copy(runtimeCorrections = listOf("future_runtime_fix")),
        ).validateForMedia3()

        assertEquals(
            "unsupported_client_runtime_correction:future_runtime_fix",
            assertIs<PlaybackV3Validation.ReplanRequired>(response).reason,
        )
    }

    @Test
    fun startRequestNeverForcesAPlayMethodOrNamesAnEngine() {
        val encoded = SiloJson.encodeToString(
            PlaybackStartRequestV3(
                clientFeatures = PLAYBACK_START_CLIENT_FEATURES_V3,
                fileId = 12,
                profileId = "profile",
                playbackAttemptId = "attempt",
                subtitleFidelityPreference = SubtitleFidelityPreference.PRESERVE,
                capabilities = ClientCodecCapabilities(),
                clientPlaybackContext = ClientPlaybackContext(
                    formFactor = "tv",
                    appVersion = "test",
                    output = PlaybackOutputContext(outputContextId = "4"),
                ),
            ),
        )
        assertFalse(encoded.contains("play_method"))
        assertTrue(encoded.contains("\"protocol_version\":3"))
        // The neutral contract negotiates delivery classes, so the request must
        // not leak this client's internal player component names.
        assertFalse(encoded.contains("media3"))
        assertTrue(encoded.contains("\"output_context_id\":\"4\""))
    }

    @Test
    fun clientOwnedProgressSerializesAnExplicitZeroFileLocalStart() {
        val encoded = SiloJson.encodeToString(
            PlaybackStartRequestV3(
                clientFeatures = PLAYBACK_START_CLIENT_FEATURES_V3,
                fileId = 12,
                profileId = "profile",
                playbackAttemptId = "audiobook-attempt",
                subtitleFidelityPreference = SubtitleFidelityPreference.PRESERVE,
                startPosition = 0.0,
                progressPersistence = ProgressPersistenceV3.CLIENT,
                capabilities = ClientCodecCapabilities(),
                clientPlaybackContext = ClientPlaybackContext(formFactor = "mobile", appVersion = "test"),
            ),
        )

        assertTrue(encoded.contains("\"start_position\":0.0"))
        assertTrue(encoded.contains("\"progress_persistence\":\"client\""))
    }

    @Test
    fun startAndReplanRequestsCarryCurrentNetworkEvidence() {
        val start = SiloJson.encodeToString(
            PlaybackStartRequestV3(
                clientFeatures = PLAYBACK_START_CLIENT_FEATURES_V3,
                fileId = 12,
                profileId = "profile",
                playbackAttemptId = "attempt",
                subtitleFidelityPreference = SubtitleFidelityPreference.PRESERVE,
                metered = true,
                bandwidthEstimateKbps = 22_000,
                bandwidthCapKbps = 15_000,
                capabilities = ClientCodecCapabilities(),
                clientPlaybackContext = ClientPlaybackContext(formFactor = "tv", appVersion = "test"),
            ),
        )
        val replan = SiloJson.encodeToString(
            PlaybackReplanRequestV3(
                clientFeatures = PLAYBACK_START_CLIENT_FEATURES_V3,
                playbackAttemptId = "attempt",
                replanRequestId = "request",
                failedPlanId = "plan",
                planAttemptId = "plan-attempt",
                planAttemptKey = "key",
                attemptedPlanKeys = listOf("key"),
                attemptCount = 2,
                positionSeconds = 10.0,
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
                clientFeatures = PLAYBACK_START_CLIENT_FEATURES_V3,
                fileId = 12,
                profileId = "profile",
                playbackAttemptId = "attempt",
                subtitleFidelityPreference = SubtitleFidelityPreference.PRESERVE,
                capabilities = ClientCodecCapabilities(),
                clientPlaybackContext = ClientPlaybackContext(formFactor = "tv", appVersion = "test"),
            ),
        )
        val reanchor = SiloJson.encodeToString(
            PlaybackReplanRequestV3(
                clientFeatures = PLAYBACK_START_CLIENT_FEATURES_V3,
                operation = SEEK_REANCHOR_V3_OPERATION,
                playbackAttemptId = "attempt",
                replanRequestId = "request",
                failedPlanId = "plan",
                planAttemptId = "plan-attempt",
                planAttemptKey = "key",
                attemptedPlanKeys = listOf("key"),
                attemptCount = 1,
                positionSeconds = 10.0,
                selectedTracks = SelectedPlaybackTracksV3(),
                capabilities = ClientCodecCapabilities(),
                clientPlaybackContext = ClientPlaybackContext(formFactor = "tv", appVersion = "test"),
            ),
        )

        assertTrue(start.contains(SEEK_REANCHOR_V3_FEATURE))
        assertTrue(reanchor.contains("\"operation\":\"seek_reanchor\""))
        assertFalse(reanchor.contains("\"failure\""))
    }

    @Test
    fun intentOperationsCarryNoFailure() {
        // `track_change` and `quality_change` replace what used to be separate
        // endpoints. Nothing failed, so no failure is reported and the previous
        // route stays eligible — the server may legitimately hand back a plan
        // the client has already tried, which is not a loop.
        for (operation in INTENT_V3_OPERATIONS) {
            val encoded = SiloJson.encodeToString(
                PlaybackReplanRequestV3(
                    clientFeatures = PLAYBACK_START_CLIENT_FEATURES_V3,
                    operation = operation,
                    playbackAttemptId = "attempt",
                    replanRequestId = "request",
                    failedPlanId = "plan",
                    planAttemptId = "plan-attempt",
                    planAttemptKey = "key",
                    attemptedPlanKeys = emptyList(),
                    attemptCount = 1,
                    qualityPreference = "1080p",
                    positionSeconds = 10.0,
                    selectedTracks = SelectedPlaybackTracksV3(),
                    failure = null,
                    capabilities = ClientCodecCapabilities(),
                    clientPlaybackContext = ClientPlaybackContext(formFactor = "tv", appVersion = "test"),
                ),
            )

            assertTrue(encoded.contains("\"operation\":\"$operation\""))
            assertFalse(encoded.contains("\"failure\""))
        }
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
        val context = ClientPlaybackContext(
            formFactor = "tv",
            appVersion = "test",
            output = PlaybackOutputContext(audioPassthrough = passthrough),
        )
        val encoded = SiloJson.encodeToString(context)

        assertTrue(encoded.contains("\"channel_counts\":[2,6,8]"))
        assertTrue(encoded.contains("\"layouts\":[\"stereo\",\"5.1(side)\",\"7.1\"]"))
        // The context itself carries no feature list: feature advertisement
        // lives only in the request's top-level `client_features`, and the
        // layout-aware claim is earned by enumerating real layouts.
        assertFalse(encoded.contains(LAYOUT_AWARE_PASSTHROUGH_FEATURE))
        assertTrue(LAYOUT_AWARE_PASSTHROUGH_FEATURE in playbackClientFeaturesV3(context))
        assertFalse(
            LAYOUT_AWARE_PASSTHROUGH_FEATURE in
                playbackClientFeaturesV3(context.copy(output = PlaybackOutputContext())),
        )
    }
}
