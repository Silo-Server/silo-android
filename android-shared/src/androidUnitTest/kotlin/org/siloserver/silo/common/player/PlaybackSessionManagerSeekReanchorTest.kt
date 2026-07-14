package org.siloserver.silo.common.player

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.siloserver.silo.model.playback.ClientCodecCapabilities
import org.siloserver.silo.model.playback.ClientPlaybackContext
import org.siloserver.silo.model.playback.PLAYBACK_PLAN_V3_FEATURE
import org.siloserver.silo.model.playback.PlaybackDecisionOutcome
import org.siloserver.silo.model.playback.PlaybackDecisionResponseV3
import org.siloserver.silo.model.playback.PlaybackDelivery
import org.siloserver.silo.model.playback.PlaybackEffectiveRecipeV3
import org.siloserver.silo.model.playback.PlaybackEngineKind
import org.siloserver.silo.model.playback.PlaybackOutputContext
import org.siloserver.silo.model.playback.PlaybackPlanV3
import org.siloserver.silo.model.playback.PlaybackStreamProtocol
import org.siloserver.silo.model.playback.PlaybackStreamV3
import org.siloserver.silo.model.playback.PlaybackTimelineV3
import org.siloserver.silo.model.playback.PlaybackTrackIdentityV3
import org.siloserver.silo.model.playback.SEEK_FAILURE_RECOVERY_V3_OPERATION
import org.siloserver.silo.model.playback.SEEK_REANCHOR_V3_FEATURE
import org.siloserver.silo.model.playback.SelectedPlaybackTracksV3
import org.siloserver.silo.model.playback.SubtitleFidelityPreference
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.api.PlaybackApi
import org.siloserver.silo.repository.PlaybackRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlaybackSessionManagerSeekReanchorTest {
    @Test
    fun reanchorRequiresNegotiatedServerFeature() = runTest {
        val harness = Harness(
            startResponse = response(plan(), features = listOf(PLAYBACK_PLAN_V3_FEATURE)),
        ) { _, _ -> error("A feature-gated reanchor must not reach the server") }
        harness.manager.start()

        val result = harness.manager.reanchorActiveVideoSession(positionSeconds = 90.0)

        assertEquals("seek_reanchor_not_supported", assertIs<ApiResult.Error>(result).error)
        assertEquals(0, harness.replanBodies.size)
    }

    @Test
    fun reanchorRequestComesOnlyFromActiveAttemptAndPreservesLocalHistory() = runTest {
        val initialPlan = plan()
        var network = PlaybackNetworkSnapshot(
            connected = true,
            validated = true,
            metered = false,
            transport = "wifi",
            bandwidthEstimateKbps = 50_000,
        )
        val reanchoredPlan = initialPlan.copy(
            stream = initialPlan.stream.copy(url = "/stream/session-1/reanchored.m3u8"),
            timeline = PlaybackTimelineV3(
                sourceStartSeconds = 90.0,
                streamOriginSeconds = 90.0,
                playerStartSeconds = 0.0,
                timelineOffsetSeconds = 90.0,
                canSeekAnywhere = false,
                seekRestoration = "source_position",
            ),
        )
        val harness = Harness(
            startResponse = response(initialPlan),
            networkEvidenceProvider = PlaybackNetworkEvidenceProvider { network },
        ) { _, _ -> success(response(reanchoredPlan)) }
        val started = assertIs<VideoSessionStartV3.Ready>(harness.manager.start().data)
        assertTrue(harness.manager.recordTransportReopen())
        network = PlaybackNetworkSnapshot(
            connected = true,
            validated = true,
            metered = true,
            transport = "cellular",
            bandwidthEstimateKbps = 1_500,
        )

        val result = harness.manager.reanchorActiveVideoSession(
            positionSeconds = 90.0,
            diagnostics = mapOf("reason" to "outside_seek_window"),
        )

        val ready = assertIs<VideoSessionStartV3.Ready>(assertIs<ApiResult.Success<VideoSessionStartV3>>(result).data)
        val request = harness.replanBodies.single()
        assertEquals("seek_reanchor", request.string("operation"))
        assertEquals(90.0, request["position_seconds"]!!.jsonPrimitive.double)
        assertEquals("plan-1", request.string("failed_plan_id"))
        assertEquals("original", request.string("quality_preference"))
        assertEquals(7, request["output_route_generation"]!!.jsonPrimitive.int)
        assertFalse(request["metered"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(50_000, request["bandwidth_estimate_kbps"]!!.jsonPrimitive.int)
        assertEquals("file:42:audio:1", request["selected_tracks"]!!.jsonObject["audio"]!!.jsonObject.string("id"))
        assertEquals(listOf("hevc"), request["client_capabilities"]!!.jsonObject["codecs_video"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(2, request["attempted_plan_keys"]!!.jsonArray.size)
        assertEquals(
            request.string("plan_attempt_key"),
            request["attempted_plan_keys"]!!.jsonArray.last().jsonPrimitive.content,
        )
        assertEquals(started.planAttemptId, ready.planAttemptId)
        assertEquals(request.string("plan_attempt_key"), ready.planAttemptKey)
        assertEquals(90.0, ready.plan.timeline.sourceStartSeconds)
        assertFalse(harness.manager.recordTransportReopen(), "reanchor must retain local recovery mutations")
    }

    @Test
    fun reanchorTreatsOmittedOptionalMediaIdsAsUnchanged() = runTest {
        val initial = plan()
        val responsePlan = reanchored(initial, 90.0).copy(
            requestedMediaFileId = null,
            effectiveMediaFileId = null,
        )
        val harness = Harness(response(initial)) { _, _ -> success(response(responsePlan)) }
        harness.manager.start()

        val result = harness.manager.reanchorActiveVideoSession(positionSeconds = 90.0)

        assertIs<VideoSessionStartV3.Ready>(
            assertIs<ApiResult.Success<VideoSessionStartV3>>(result).data,
        )
    }

    @Test
    fun exactReanchorRetainsTheDeviceLocalPcmFallback() = runTest {
        val initial = plan().copy(
            claims = org.siloserver.silo.model.playback.PlaybackValidationClaims(
                audio = org.siloserver.silo.model.playback.AudioValidationClaims(
                    codec = "eac3",
                    passthrough = true,
                ),
            ),
        )
        val harness = Harness(response(initial)) { _, _ ->
            success(response(reanchored(initial, 90.0)))
        }
        harness.manager.start()
        assertTrue(harness.manager.trySingleLocalPcmRetry("audio/eac3", 8))

        val ready = assertIs<VideoSessionStartV3.Ready>(
            assertIs<ApiResult.Success<VideoSessionStartV3>>(
                harness.manager.reanchorActiveVideoSession(90.0),
            ).data,
        )

        assertFalse(ready.plan.claims.audio.passthrough)
        assertEquals("client_pcm_retry", ready.plan.claims.audio.reason)
        assertFalse(ready.session.playbackPlan!!.claims.audio.passthrough)
    }

    @Test
    fun reanchorRejectsIdentityDriftAndKeepsTheActiveAttemptRetryable() = runTest {
        val initial = plan()
        val cases = listOf<Pair<String, PlaybackDecisionResponseV3>>(
            "response session" to response(initial, sessionId = "session-2"),
            "session" to response(initial.copy(sessionId = "session-2"), sessionId = "session-2"),
            "plan" to response(initial.copy(planId = "plan-2")),
            "file" to response(initial.copy(effectiveMediaFileId = 84)),
            "tracks" to response(
                initial.copy(
                    selectedTracks = SelectedPlaybackTracksV3(
                        audio = PlaybackTrackIdentityV3("file:42:audio:2", 2),
                    ),
                ),
            ),
            "route" to response(
                initial.copy(effectiveRecipe = initial.effectiveRecipe.copy(audioCodec = "aac")),
            ),
        )

        cases.forEach { (name, invalidResponse) ->
            val harness = Harness(response(initial)) { index, _ ->
                success(if (index == 0) invalidResponse else response(reanchored(initial, 120.0)))
            }
            harness.manager.start()

            val invalid = harness.manager.reanchorActiveVideoSession(positionSeconds = 60.0)
            assertEquals(
                "invalid_seek_reanchor_response",
                assertIs<ApiResult.Error>(invalid, "$name drift must fail closed").error,
            )

            val retry = harness.manager.reanchorActiveVideoSession(positionSeconds = 120.0)
            assertIs<VideoSessionStartV3.Ready>(
                assertIs<ApiResult.Success<VideoSessionStartV3>>(retry, "$name failure must retain the active attempt").data,
            )
        }
    }

    @Test
    fun transportFailureLeavesTheActiveAttemptRetryable() = runTest {
        val initial = plan()
        val harness = Harness(response(initial)) { index, _ ->
            if (index == 0) {
                MockResponse(
                    HttpStatusCode.InternalServerError,
                    """{"error":"transport_failed","message":"Could not reopen transport"}""",
                )
            } else {
                success(response(reanchored(initial, 120.0)))
            }
        }
        harness.manager.start()

        assertIs<ApiResult.Error>(harness.manager.reanchorActiveVideoSession(positionSeconds = 60.0))
        assertIs<ApiResult.Success<VideoSessionStartV3>>(
            harness.manager.reanchorActiveVideoSession(positionSeconds = 120.0),
        )
    }

    @Test
    fun failedImmediateStartupReplanStopsAllocatedSessionAndClearsAttempt() = runTest {
        val harness = Harness(response(plan().copy(engine = PlaybackEngineKind.MPV_DIRECT))) { _, _ ->
            MockResponse(
                HttpStatusCode.InternalServerError,
                """{"error":"replan_failed","message":"Could not replace legacy route"}""",
            )
        }

        assertIs<ApiResult.Error>(harness.manager.startResult())
        assertEquals(listOf("session-1"), harness.stoppedSessionIds)

        val retry = harness.manager.replanActiveVideoSession(
            classification = "player_failure",
            positionSeconds = 0.0,
            audioTrackIndex = 1,
            subtitleTrackIndex = null,
        )
        assertEquals("playback_attempt_not_active", assertIs<ApiResult.Error>(retry).error)
    }

    @Test
    fun concurrentReanchorsAreSerialized() = runTest {
        val initial = plan()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val harness = Harness(response(initial)) { index, body ->
            if (index == 0) {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
            success(response(reanchored(initial, body["position_seconds"]!!.jsonPrimitive.double)))
        }
        harness.manager.start()

        val first = async { harness.manager.reanchorActiveVideoSession(positionSeconds = 30.0) }
        firstEntered.await()
        val second = async { harness.manager.reanchorActiveVideoSession(positionSeconds = 60.0) }
        repeat(3) { yield() }
        assertEquals(1, harness.replanBodies.size)

        releaseFirst.complete(Unit)
        assertIs<ApiResult.Success<VideoSessionStartV3>>(first.await())
        assertIs<ApiResult.Success<VideoSessionStartV3>>(second.await())
        assertEquals(listOf(30.0, 60.0), harness.replanBodies.map { it["position_seconds"]!!.jsonPrimitive.double })
    }

    @Test
    fun seekFailureRecoveryCanChangeRouteButNotPlaybackIntent() = runTest {
        val initial = plan()
        val fallback = initial.copy(
            planId = "plan-2",
            delivery = PlaybackDelivery.SERVER_TRANSCODE_HLS,
            stream = initial.stream.copy(url = "/stream/session-1/seek-recovery.m3u8"),
            effectiveRecipe = initial.effectiveRecipe.copy(audioCodec = "aac"),
            decisionReason = "seek_transport_fallback",
        )
        val thirdRoute = fallback.copy(
            planId = "plan-3",
            stream = fallback.stream.copy(container = "fmp4"),
        )
        val harness = Harness(response(initial)) { index, _ ->
            success(response(if (index == 0) fallback else thirdRoute))
        }
        harness.manager.start()
        assertTrue(harness.manager.recordTransportReopen())

        val recovered = harness.manager.recoverActiveVideoSessionAfterSeek(
            positionSeconds = 90.0,
            classification = "seek_parser_failure",
            message = "Extractor could not resume after seek.",
        )

        val ready = assertIs<VideoSessionStartV3.Ready>(
            assertIs<ApiResult.Success<VideoSessionStartV3>>(recovered).data,
        )
        val request = harness.replanBodies.first()
        assertEquals(SEEK_FAILURE_RECOVERY_V3_OPERATION, request.string("operation"))
        assertEquals("original", request.string("quality_preference"))
        assertEquals("seek_parser_failure", request["failure"]!!.jsonObject.string("classification"))
        assertEquals("file:42:audio:1", request["selected_tracks"]!!.jsonObject["audio"]!!.jsonObject.string("id"))
        assertEquals(42, ready.plan.requestedMediaFileId)
        assertEquals(42, ready.plan.effectiveMediaFileId)
        assertEquals("plan-2", ready.plan.planId)

        harness.manager.recoverActiveVideoSessionAfterSeek(
            positionSeconds = 120.0,
            classification = "seek_decoder_failure",
        )
        val history = harness.replanBodies[1]["attempted_plan_keys"]!!.jsonArray
            .map { it.jsonPrimitive.content }
        assertTrue(request["attempted_plan_keys"]!!.jsonArray.all { it.jsonPrimitive.content in history })
        assertTrue(ready.planAttemptKey in history)
    }

    @Test
    fun seekFailureRecoveryTreatsOmittedOptionalMediaIdsAsUnchanged() = runTest {
        val initial = plan()
        val fallback = initial.copy(
            planId = "plan-2",
            stream = initial.stream.copy(url = "/stream/session-1/seek-recovery.m3u8"),
            requestedMediaFileId = null,
            effectiveMediaFileId = null,
        )
        val harness = Harness(response(initial)) { _, _ -> success(response(fallback)) }
        harness.manager.start()

        val result = harness.manager.recoverActiveVideoSessionAfterSeek(
            positionSeconds = 90.0,
            classification = "seek_parser_failure",
        )

        assertIs<VideoSessionStartV3.Ready>(
            assertIs<ApiResult.Success<VideoSessionStartV3>>(result).data,
        )
    }

    @Test
    fun seekFailureRecoveryRejectsFileSwitchAndRetainsAttempt() = runTest {
        val initial = plan()
        val switchedFile = initial.copy(
            planId = "plan-other-file",
            requestedMediaFileId = 84,
            effectiveMediaFileId = 84,
            selectedTracks = SelectedPlaybackTracksV3(
                audio = PlaybackTrackIdentityV3("file:84:audio:1", 1),
            ),
        )
        val fallback = initial.copy(
            planId = "plan-2",
            delivery = PlaybackDelivery.SERVER_TRANSCODE_HLS,
            effectiveRecipe = initial.effectiveRecipe.copy(audioCodec = "aac"),
        )
        val harness = Harness(response(initial)) { index, _ ->
            success(response(if (index == 0) switchedFile else fallback))
        }
        harness.manager.start()

        val rejected = harness.manager.recoverActiveVideoSessionAfterSeek(
            positionSeconds = 90.0,
            classification = "seek_parser_failure",
        )
        assertEquals("invalid_seek_recovery_response", assertIs<ApiResult.Error>(rejected).error)

        assertIs<ApiResult.Success<VideoSessionStartV3>>(
            harness.manager.recoverActiveVideoSessionAfterSeek(
                positionSeconds = 90.0,
                classification = "seek_parser_failure",
            ),
        )
    }

    @Test
    fun replanSynthesizesChangedTrackIdsFromTheEffectiveFile() = runTest {
        val initial = plan().copy(
            effectiveMediaFileId = 84,
            selectedTracks = SelectedPlaybackTracksV3(
                audio = PlaybackTrackIdentityV3("file:84:audio:1", 1),
            ),
        )
        val replanned = initial.copy(
            planId = "plan-2",
            selectedTracks = SelectedPlaybackTracksV3(
                audio = PlaybackTrackIdentityV3("file:84:audio:2", 2),
                subtitle = PlaybackTrackIdentityV3("file:84:subtitle:3", 3),
            ),
        )
        val harness = Harness(response(initial)) { _, _ -> success(response(replanned)) }
        harness.manager.start()

        val result = harness.manager.replanActiveVideoSession(
            classification = "audio_track_changed",
            positionSeconds = 15.0,
            audioTrackIndex = 2,
            subtitleTrackIndex = 3,
        )

        assertIs<VideoSessionStartV3.Ready>(
            assertIs<ApiResult.Success<VideoSessionStartV3>>(result).data,
        )
        val selectedTracks = harness.replanBodies.single()["selected_tracks"]!!.jsonObject
        assertEquals("file:84:audio:2", selectedTracks["audio"]!!.jsonObject.string("id"))
        assertEquals("file:84:subtitle:3", selectedTracks["subtitle"]!!.jsonObject.string("id"))
    }

    private class Harness(
        startResponse: PlaybackDecisionResponseV3,
        networkEvidenceProvider: PlaybackNetworkEvidenceProvider = PlaybackNetworkEvidenceProvider.None,
        private val replanResponse: suspend (Int, JsonObject) -> MockResponse,
    ) {
        val replanBodies: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
        val stoppedSessionIds: MutableList<String> = Collections.synchronizedList(mutableListOf())
        private val replanIndex = AtomicInteger()
        private val client = HttpClient(
            MockEngine { request ->
                val path = request.url.encodedPath
                val response = when {
                    path == "/api/v1/playback/start" -> MockResponse(
                        HttpStatusCode.OK,
                        SiloJson.encodeToString(startResponse),
                    )
                    path.endsWith("/replan") -> {
                        val body = SiloJson.parseToJsonElement(
                            request.body.toByteArray().decodeToString(),
                        ).jsonObject
                        replanBodies += body
                        replanResponse(replanIndex.getAndIncrement(), body)
                    }
                    request.method == HttpMethod.Delete && path.startsWith("/api/v1/playback/") -> {
                        stoppedSessionIds += path.substringAfterLast('/')
                        MockResponse(HttpStatusCode.OK, "{}")
                    }
                    else -> MockResponse(HttpStatusCode.OK, "{}")
                }
                respond(
                    content = response.body,
                    status = response.status,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        ) {
            install(ContentNegotiation) { json(SiloJson) }
        }
        val manager = PlaybackSessionManager(
            playbackRepository = PlaybackRepository(PlaybackApi(client)),
            tokenManager = SeekNoOpTokenManager,
            networkEvidenceProvider = networkEvidenceProvider,
        )
    }

    private suspend fun PlaybackSessionManager.start(): ApiResult.Success<VideoSessionStartV3> =
        assertIs(startResult())

    private suspend fun PlaybackSessionManager.startResult(): ApiResult<VideoSessionStartV3> =
        startVideoSessionV3(
                fileId = 42,
                profileId = "profile-1",
                capabilities = ClientCodecCapabilities(
                    codecsVideo = listOf("hevc"),
                    codecsAudio = listOf("eac3"),
                    containers = listOf("mkv"),
                ),
                clientPlaybackContext = ClientPlaybackContext(
                    formFactor = "tv",
                    appVersion = "test",
                    output = PlaybackOutputContext(outputRouteGeneration = 7),
                ),
                audioTrackIndex = 1,
                subtitleTrackIndex = null,
                qualityPreference = "original",
                startPosition = 0.0,
                subtitleFidelityPreference = SubtitleFidelityPreference.PRESERVE,
        )

    private fun plan(): PlaybackPlanV3 = PlaybackPlanV3(
        planId = "plan-1",
        sessionId = "session-1",
        delivery = PlaybackDelivery.SERVER_REMUX_HLS,
        engine = PlaybackEngineKind.MEDIA3_HLS,
        stream = PlaybackStreamV3(
            url = "/stream/session-1/master.m3u8",
            protocol = PlaybackStreamProtocol.HLS,
            container = "mpegts",
            mimeType = "application/x-mpegURL",
        ),
        selectedTracks = SelectedPlaybackTracksV3(
            audio = PlaybackTrackIdentityV3("file:42:audio:1", 1),
        ),
        effectiveRecipe = PlaybackEffectiveRecipeV3(
            videoCodec = "hevc",
            audioCodec = "eac3",
            width = 3840,
            height = 2160,
            frameRate = 23.976,
            bitrateKbps = 45_000,
            dynamicRange = "dolby_vision",
            audioChannels = 8,
            audioLayout = "7.1",
        ),
        decisionReason = "container_requires_hls_remux",
        requestedMediaFileId = 42,
        effectiveMediaFileId = 42,
    )

    private fun reanchored(plan: PlaybackPlanV3, position: Double): PlaybackPlanV3 = plan.copy(
        stream = plan.stream.copy(url = "/stream/session-1/reanchored-$position.m3u8"),
        timeline = PlaybackTimelineV3(
            sourceStartSeconds = position,
            streamOriginSeconds = position,
            playerStartSeconds = 0.0,
            timelineOffsetSeconds = position,
            canSeekAnywhere = false,
            seekRestoration = "source_position",
        ),
    )

    private fun response(
        plan: PlaybackPlanV3,
        sessionId: String = "session-1",
        features: List<String> = listOf(PLAYBACK_PLAN_V3_FEATURE, SEEK_REANCHOR_V3_FEATURE),
    ): PlaybackDecisionResponseV3 = PlaybackDecisionResponseV3(
        protocolVersion = 3,
        serverFeatures = features,
        outcome = PlaybackDecisionOutcome.PLAYABLE,
        sessionId = sessionId,
        playbackPlan = plan,
    )

    private fun success(response: PlaybackDecisionResponseV3): MockResponse =
        MockResponse(HttpStatusCode.OK, SiloJson.encodeToString(response))

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content

    private data class MockResponse(val status: HttpStatusCode, val body: String)
}

private object SeekNoOpTokenManager : TokenManager {
    override val sessionExpired: SharedFlow<Unit> = MutableSharedFlow()
    override suspend fun getAccessToken(): String? = null
    override suspend fun getRefreshToken(): String? = null
    override suspend fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Long) {}
    override suspend fun clearTokens() {}
    override suspend fun invalidateSession() {}
    override suspend fun getProfileId(): String? = null
    override suspend fun setProfileId(profileId: String?) {}
    override suspend fun getProfileToken(): String? = null
    override suspend fun setProfileToken(token: String?) {}
    override suspend fun getServerUrl(): String = ""
    override suspend fun setServerUrl(url: String) {}
    override suspend fun getCurrentServerId(): String? = null
    override suspend fun switchActiveServer(serverId: String?) {}
    override suspend fun signOutCurrentServer() {}
    override suspend fun snapshotCurrentScope(): AuthScopeSnapshot? = null
}
