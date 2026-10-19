package org.siloserver.silo.common.player

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.siloserver.silo.domain.player.BufferingAction
import org.siloserver.silo.domain.player.BufferingPlanSummary
import org.siloserver.silo.domain.player.BufferingQualityMode
import org.siloserver.silo.domain.player.BufferingQualityReplan
import org.siloserver.silo.model.playback.BANDWIDTH_ESTIMATE_SOURCE_OBSERVED_REBUFFER
import org.siloserver.silo.model.playback.BUFFERING_QUALITY_V1_FEATURE
import org.siloserver.silo.model.playback.ClientCodecCapabilities
import org.siloserver.silo.model.playback.ClientPlaybackContext
import org.siloserver.silo.model.playback.LEARNED_LIMIT_CLEAR
import org.siloserver.silo.model.playback.LEARNED_LIMIT_IGNORE
import org.siloserver.silo.model.playback.NEUTRAL_PLAYBACK_V3_CONTRACT_FEATURE
import org.siloserver.silo.model.playback.PLAYBACK_PLAN_SUMMARY_V1_FEATURE
import org.siloserver.silo.model.playback.PLAYBACK_PLAN_V3_FEATURE
import org.siloserver.silo.model.playback.PlaybackDecisionOutcome
import org.siloserver.silo.model.playback.PlaybackDecisionResponseV3
import org.siloserver.silo.model.playback.PlaybackDelivery
import org.siloserver.silo.model.playback.PlaybackOutputContext
import org.siloserver.silo.model.playback.PlaybackPlanV3
import org.siloserver.silo.model.playback.PlaybackStreamProtocol
import org.siloserver.silo.model.playback.PlaybackStreamV3
import org.siloserver.silo.model.playback.SEEK_FAILURE_RECOVERY_V3_OPERATION
import org.siloserver.silo.model.playback.SEEK_REANCHOR_V3_FEATURE
import org.siloserver.silo.model.playback.SEEK_REANCHOR_V3_OPERATION
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.DurableLoginAuthority
import org.siloserver.silo.network.DurableLoginAuthorityProvider
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.network.apiv2.PlaybackV2Api
import org.siloserver.silo.repository.PlaybackJournalEntry
import org.siloserver.silo.repository.PlaybackJournalStore
import org.siloserver.silo.repository.PlaybackRepository
import org.siloserver.silo.repository.SequencedPlayback
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackSessionManagerBufferingQualityTest {
    private class Identity : TokenManager by TokenManagerImpl(), DurableLoginAuthorityProvider {
        val scope = AuthScopeSnapshot("server-1", "profile-1", "https://example.invalid", "proof",
            identityGeneration = 1, isIdentityGenerationStamped = true, credentialEpoch = 1)
        override suspend fun snapshotCurrentScope() = scope
        override suspend fun snapshotDurableLoginAuthority() = DurableLoginAuthority("login-1", scope)
    }

    private class Journal : PlaybackJournalStore {
        private var entries = emptyList<PlaybackJournalEntry>()
        override suspend fun read() = entries
        override suspend fun write(entries: List<PlaybackJournalEntry>) { this.entries = entries }
    }

    private class Harness(capabilityFeatures: List<String>, throughputKbps: () -> Int? = { null }) {
        val startBodies: MutableList<JsonObject> = CopyOnWriteArrayList()
        val replanBodies: MutableList<JsonObject> = CopyOnWriteArrayList()
        val routeEvents: MutableList<JsonObject> = CopyOnWriteArrayList()

        /** Every plan served, so a seek re-anchor can answer with the plan it names. */
        private val servedPlans = ConcurrentHashMap<String, PlaybackPlanV3>()
        private val startCount = AtomicInteger()
        private val replanCount = AtomicInteger()

        /** The first start gets [PLAN_1]; a later one gets a session of its own, as a server would mint. */
        private fun startReply(): PlaybackPlanV3 = when (val n = startCount.incrementAndGet()) {
            1 -> PLAN_1
            else -> plan("start-$n", "v3:%016x".format(0xb0 + n), sessionId = "session-$n")
        }.also { servedPlans[it.planId] = it }

        /** A re-anchor keeps its plan; every other replan gets the next one, starting at [PLAN_2]. */
        private fun replanReply(sessionId: String, body: JsonObject): PlaybackPlanV3 =
            if (body.stringOrNull("operation") == SEEK_REANCHOR_V3_OPERATION) {
                servedPlans.getValue(body.string("failed_plan_id"))
            } else {
                val n = replanCount.incrementAndGet() + 1
                plan("plan-$n", "v3:%016x".format(0xa0 + n), sessionId).also { servedPlans[it.planId] = it }
            }
        private val identity = Identity()
        private val client = HttpClient(
            MockEngine { request ->
                val raw = request.body.toByteArray().decodeToString()
                val body = { SiloJson.parseToJsonElement(raw).jsonObject }
                val features = capabilityFeatures.joinToString(",") { "\"$it\"" }
                val path = request.url.encodedPath
                val replanSession = REPLAN_PATH.matchEntire(path)?.groupValues?.get(1)
                val reply = when {
                    path == "/api/v2/playback/capabilities" -> HttpStatusCode.OK to
                        """{"installation_id":"11111111-1111-4111-8111-111111111111","revision":"1","state":"available","allowed":true,"protocol_versions":[3],"features":[$features]}"""
                    path == "/api/v2/account/me" -> HttpStatusCode.OK to """{"id":"account-1","username":"test","email":"","role":"user"}"""
                    path == "/api/v2/playback/start" -> { startBodies += body(); HttpStatusCode.Created to decision(startReply()) }
                    replanSession != null -> {
                        val replan = body().also { replanBodies += it }
                        HttpStatusCode.OK to decision(replanReply(replanSession, replan))
                    }
                    path == "/api/v2/playback/route-events" -> {
                        val event = body().also { routeEvents += it }
                        HttpStatusCode.Accepted to """{"event_id":${event["event_id"]},"outcome":"accepted"}"""
                    }
                    else -> error("Unexpected request $path")
                }
                respond(reply.second, reply.first, headersOf(HttpHeaders.ContentType, "application/json"))
            },
        ) { install(ContentNegotiation) { json(SiloJson) } }
        val manager = PlaybackSessionManager(
            playbackRepository = PlaybackRepository(
                SequencedPlayback(PlaybackV2Api(client, ApiV2Gate.Unrestricted), identity, identity, Journal()) {
                    java.util.UUID.randomUUID().toString()
                },
            ),
            tokenManager = identity,
            throughputKbps = throughputKbps,
        )

        suspend fun start(): VideoSessionStartV3.Ready = assertIs(
            assertIs<ApiResult.Success<VideoSessionStartV3>>(
                manager.startVideoSessionV3(
                    fileId = 42,
                    profileId = "profile-1",
                    capabilities = ClientCodecCapabilities(),
                    clientPlaybackContext = ClientPlaybackContext(
                        formFactor = "tv",
                        appVersion = "test",
                        output = PlaybackOutputContext(outputContextId = "7"),
                    ),
                    audioTrackIndex = null,
                    subtitleTrackIndex = null,
                    qualityPreference = "auto",
                    startPosition = 0.0,
                ),
            ).data,
        )

        suspend fun lower(): VideoSessionStartV3.Ready = replan(
            classification = "quality_changed",
            bufferingQuality = BufferingQualityReplan(
                qualityPreference = "auto",
                bandwidthEstimateKbps = 44_100,
                bandwidthEstimateSource = BANDWIDTH_ESTIMATE_SOURCE_OBSERVED_REBUFFER,
            ),
        )

        suspend fun replan(
            classification: String,
            qualityPreference: String? = null,
            bufferingQuality: BufferingQualityReplan? = null,
        ): VideoSessionStartV3.Ready {
            val result = manager.replanActiveVideoSession(
                classification = classification,
                positionSeconds = 125.0,
                audioTrackIndex = null,
                subtitleTrackIndex = null,
                qualityPreference = qualityPreference,
                bufferingQuality = bufferingQuality,
            )
            return assertIs(assertIs<ApiResult.Success<VideoSessionStartV3>>(result, "$classification: $result").data)
        }

        /** Route events go out on the manager's IO scope. */
        suspend fun awaitRouteEvent(event: String): JsonObject = withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                while (routeEvents.none { it.string("event") == event }) delay(10)
                routeEvents.first { it.string("event") == event }
            }
        }
    }

    @Test
    fun aBufferingQualityReplanIsAQualityChangeCarryingItsEstimate() = runTest {
        val harness = Harness(BUFFERING_QUALITY_SERVER)
        harness.start()
        harness.lower()

        val body = harness.replanBodies.single()
        assertEquals("quality_change", body.string("operation"))
        assertEquals("auto", body.string("quality_preference"))
        assertEquals(JsonPrimitive(44_100), body["bandwidth_estimate_kbps"])
        assertEquals(BANDWIDTH_ESTIMATE_SOURCE_OBSERVED_REBUFFER, body.string("bandwidth_estimate_source"))
        assertNull(body["learned_limit"])
        assertNull(body["failure"])
        assertTrue(BUFFERING_QUALITY_V1_FEATURE in body.features())
        assertTrue(BUFFERING_QUALITY_V1_FEATURE in harness.startBodies.single().features())
    }

    @Test
    fun neverModeIgnoresTheLearnedLimitOnlyWhereTheServerKnowsIt() = runTest {
        val harness = Harness(BUFFERING_QUALITY_SERVER)
        harness.manager.bufferingQualityModeProvider = { BufferingQualityMode.NEVER }
        harness.start()
        harness.lower()
        assertEquals(LEARNED_LIMIT_IGNORE, harness.startBodies.single().string("learned_limit"))
        assertEquals(LEARNED_LIMIT_IGNORE, harness.replanBodies.single().string("learned_limit"))

        val older = Harness(listOf(SEQUENCED_PROGRESS))
        older.manager.bufferingQualityModeProvider = { BufferingQualityMode.NEVER }
        older.start()
        older.lower()
        val start = older.startBodies.single()
        val replan = older.replanBodies.single()
        assertNull(start["learned_limit"])
        assertNull(replan["learned_limit"])
        assertNull(replan["bandwidth_estimate_source"])
        assertEquals(JsonPrimitive(44_100), replan["bandwidth_estimate_kbps"])
        assertFalse(BUFFERING_QUALITY_V1_FEATURE in start.features() + replan.features())
    }

    @Test
    fun aPlanSummaryNamesTheSummarizedPlanAfterItsSuccessorTookOver() = runTest {
        val harness = Harness(BUFFERING_QUALITY_SERVER + PLAYBACK_PLAN_SUMMARY_V1_FEATURE)
        val first = harness.start()
        val second = harness.lower()

        harness.manager.emitPlanSummary(summary(PLAN_1.planId, deliveredKbps = 63_000))
        val event = harness.awaitRouteEvent("plan_summary")
        assertEquals(PLAN_1.planId, event.string("plan_id"))
        assertEquals(first.planAttemptId, event.string("plan_attempt_id"))
        assertEquals(PLAN_1.planAttemptKey, event.string("plan_attempt_key"))
        assertEquals(
            mapOf(
                "played_ms" to "131000",
                "stall_count" to "1",
                "stall_total_ms" to "6000",
                "stall_longest_ms" to "6000",
                "delivered_kbps" to "63000",
                "buffering_mode" to "always",
                "buffering_action" to "lowered",
            ),
            event.diagnostics(),
        )

        harness.routeEvents.clear()
        harness.manager.emitPlanSummary(summary(PLAN_2.planId, deliveredKbps = null))
        val successor = harness.awaitRouteEvent("plan_summary")
        assertEquals(second.planAttemptId, successor.string("plan_attempt_id"))
        assertEquals("44100", successor.diagnostics()["bandwidth_estimate_kbps"])
        assertNull(successor.diagnostics()["delivered_kbps"])
    }

    @Test
    fun aPlanSummaryCarriesThePlansThroughput() = runTest {
        val readings = ArrayDeque(listOf(52_000, null))
        val harness = Harness(BUFFERING_QUALITY_SERVER + PLAYBACK_PLAN_SUMMARY_V1_FEATURE) { readings.removeFirst() }
        harness.start()
        harness.lower()

        harness.manager.emitPlanSummary(summary(PLAN_1.planId, deliveredKbps = 63_000))
        assertEquals("52000", harness.awaitRouteEvent("plan_summary").diagnostics()["throughput_kbps"])
        harness.routeEvents.clear()
        harness.manager.emitPlanSummary(summary(PLAN_2.planId, deliveredKbps = null))
        assertNull(harness.awaitRouteEvent("plan_summary").diagnostics()["throughput_kbps"])
    }

    @Test
    fun noPlanSummaryWithoutTheServerFeature() = runTest {
        val harness = Harness(BUFFERING_QUALITY_SERVER)
        harness.start()
        harness.manager.emitPlanSummary(summary(PLAN_1.planId, deliveredKbps = 63_000))
        // A later event: a summary, had one been sent, would have been launched first.
        harness.manager.reportActiveVideoEvent("marker")
        harness.awaitRouteEvent("marker")
        assertTrue(harness.routeEvents.none { it.string("event") == "plan_summary" })
    }

    @Test
    fun laterAutoReplansKeepTheStepDownEstimateWithoutASource() = runTest {
        // The device-test case: a server without buffering_quality_v1, where
        // nothing is learned and only the estimate keeps Auto lowered.
        val harness = Harness(listOf(SEQUENCED_PROGRESS, PLAYBACK_PLAN_SUMMARY_V1_FEATURE))
        harness.start()
        harness.lower()
        harness.replan(classification = "subtitle_track_changed")
        harness.replan(classification = "decoder_init_failed")

        val (_, trackChange, recovery) = harness.replanBodies.toList()
        assertEquals("track_change", trackChange.string("operation"))
        assertEquals("auto", trackChange.string("quality_preference"))
        assertEquals(JsonPrimitive(44_100), trackChange["bandwidth_estimate_kbps"])
        assertNull(trackChange["bandwidth_estimate_source"])
        assertEquals("failure_recovery", recovery.string("operation"))
        assertEquals(JsonPrimitive(44_100), recovery["bandwidth_estimate_kbps"])
        assertNull(recovery["bandwidth_estimate_source"])

        // plan_summary reports the estimate the track change's plan was requested with.
        harness.manager.emitPlanSummary(summary("plan-3", deliveredKbps = null))
        assertEquals("44100", harness.awaitRouteEvent("plan_summary").diagnostics()["bandwidth_estimate_kbps"])
    }

    @Test
    fun theStepDownEstimateGoesWithoutASourceEvenWhereTheServerKnowsTheFeature() = runTest {
        val harness = Harness(BUFFERING_QUALITY_SERVER)
        harness.start()
        harness.lower()
        harness.replan(classification = "audio_track_changed")

        val trackChange = harness.replanBodies.last()
        assertEquals(JsonPrimitive(44_100), trackChange["bandwidth_estimate_kbps"])
        assertNull(trackChange["bandwidth_estimate_source"])
        assertTrue(BUFFERING_QUALITY_V1_FEATURE in trackChange.features())
    }

    @Test
    fun restoreQualityDropsTheStepDownEstimate() = runTest {
        val harness = Harness(BUFFERING_QUALITY_SERVER)
        harness.start()
        harness.lower()
        harness.replan(
            classification = "quality_changed",
            bufferingQuality = BufferingQualityReplan(qualityPreference = "auto", learnedLimit = LEARNED_LIMIT_CLEAR),
        )
        harness.replan(classification = "subtitle_track_changed")

        val (_, restore, trackChange) = harness.replanBodies.toList()
        assertEquals(LEARNED_LIMIT_CLEAR, restore.string("learned_limit"))
        assertNull(restore["bandwidth_estimate_kbps"])
        assertNull(trackChange["bandwidth_estimate_kbps"])
    }

    @Test
    fun aQualityChangeTheControllerDidNotAskForDropsTheStepDownEstimate() = runTest {
        val harness = Harness(BUFFERING_QUALITY_SERVER)
        harness.start()
        harness.lower()
        // The viewer picks Auto in the player: still Auto, but their choice.
        harness.replan(classification = "quality_changed", qualityPreference = "auto")
        harness.replan(classification = "subtitle_track_changed")

        val (_, pick, trackChange) = harness.replanBodies.toList()
        assertEquals("quality_change", pick.string("operation"))
        assertNull(pick["bandwidth_estimate_kbps"])
        assertNull(trackChange["bandwidth_estimate_kbps"])
    }

    @Test
    fun aReplanAtAPickedRungNeverCarriesTheStepDownEstimate() = runTest {
        val harness = Harness(BUFFERING_QUALITY_SERVER)
        harness.start()
        harness.lower()
        // A rung lowering is the controller's, so it keeps the estimate remembered.
        harness.replan(classification = "quality_changed", bufferingQuality = BufferingQualityReplan(qualityPreference = "720p"))
        harness.replan(classification = "subtitle_track_changed")
        harness.replan(classification = "audio_track_changed", qualityPreference = "1080p")

        val (_, rung, trackChange, pickedTrackChange) = harness.replanBodies.toList()
        assertEquals("720p", rung.string("quality_preference"))
        assertNull(rung["bandwidth_estimate_kbps"])
        assertEquals("720p", trackChange.string("quality_preference"))
        assertNull(trackChange["bandwidth_estimate_kbps"])
        assertEquals("1080p", pickedTrackChange.string("quality_preference"))
        assertNull(pickedTrackChange["bandwidth_estimate_kbps"])
    }

    @Test
    fun aFreshStartDoesNotInheritTheStepDownEstimate() = runTest {
        val harness = Harness(BUFFERING_QUALITY_SERVER)
        harness.start()
        harness.lower()
        harness.start()
        harness.replan(classification = "subtitle_track_changed")

        assertEquals(2, harness.startBodies.size)
        assertNull(harness.startBodies.last()["bandwidth_estimate_kbps"])
        assertEquals("track_change", harness.replanBodies.last().string("operation"))
        assertNull(harness.replanBodies.last()["bandwidth_estimate_kbps"])
    }

    @Test
    fun seekReplansKeepTheStepDownEstimate() = runTest {
        val harness = Harness(listOf(SEQUENCED_PROGRESS, PLAYBACK_PLAN_SUMMARY_V1_FEATURE))
        harness.start()
        harness.lower()
        assertIs<VideoSessionStartV3.Ready>(
            assertIs<ApiResult.Success<VideoSessionStartV3>>(
                harness.manager.reanchorActiveVideoSession(positionSeconds = 600.0),
            ).data,
        )
        assertIs<VideoSessionStartV3.Ready>(
            assertIs<ApiResult.Success<VideoSessionStartV3>>(
                harness.manager.recoverActiveVideoSessionAfterSeek(
                    positionSeconds = 600.0,
                    classification = "decoder_init_failed",
                ),
            ).data,
        )

        val (_, reanchor, recovery) = harness.replanBodies.toList()
        assertEquals(SEEK_REANCHOR_V3_OPERATION, reanchor.string("operation"))
        assertEquals(JsonPrimitive(44_100), reanchor["bandwidth_estimate_kbps"])
        assertNull(reanchor["bandwidth_estimate_source"])
        assertEquals(SEEK_FAILURE_RECOVERY_V3_OPERATION, recovery.string("operation"))
        assertEquals(JsonPrimitive(44_100), recovery["bandwidth_estimate_kbps"])
        assertNull(recovery["bandwidth_estimate_source"])

        // The recovered plan (plan-3) remembers the estimate it was requested with.
        harness.manager.emitPlanSummary(summary("plan-3", deliveredKbps = null))
        assertEquals("44100", harness.awaitRouteEvent("plan_summary").diagnostics()["bandwidth_estimate_kbps"])
    }

    private fun summary(planId: String, deliveredKbps: Int?) = BufferingPlanSummary(
        planId = planId,
        playedMs = 131_000,
        stallCount = 1,
        stallTotalMs = 6_000,
        stallLongestMs = 6_000,
        deliveredKbps = deliveredKbps,
        mode = BufferingQualityMode.ALWAYS,
        action = BufferingAction.LOWERED,
    )

    private companion object {
        const val SEQUENCED_PROGRESS = "sequenced_progress_v1"
        val BUFFERING_QUALITY_SERVER = listOf(SEQUENCED_PROGRESS, BUFFERING_QUALITY_V1_FEATURE)

        val REPLAN_PATH = Regex("/api/v2/playback/([^/]+)/replan")

        fun plan(id: String, key: String, sessionId: String = "session-1") = PlaybackPlanV3(
            planId = id,
            planAttemptKey = key,
            sessionId = sessionId,
            delivery = PlaybackDelivery.ORIGINAL_HTTP,
            stream = PlaybackStreamV3(url = "/api/v2/stream/$sessionId/$id", protocol = PlaybackStreamProtocol.HTTP_PROGRESSIVE),
            decisionReason = "direct",
        )

        val PLAN_1 = plan("plan-1", "v3:00000000000000a1")
        val PLAN_2 = plan("plan-2", "v3:00000000000000a2")

        fun decision(plan: PlaybackPlanV3): String = SiloJson.encodeToString(
            PlaybackDecisionResponseV3.serializer(),
            PlaybackDecisionResponseV3(
                protocolVersion = 3,
                serverFeatures = listOf(
                    PLAYBACK_PLAN_V3_FEATURE,
                    NEUTRAL_PLAYBACK_V3_CONTRACT_FEATURE,
                    SEEK_REANCHOR_V3_FEATURE,
                ),
                outcome = PlaybackDecisionOutcome.PLAYABLE,
                sessionId = plan.sessionId,
                playbackPlan = plan,
            ),
        )

        fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
        fun JsonObject.stringOrNull(name: String): String? = get(name)?.jsonPrimitive?.contentOrNull
        fun JsonObject.features(): List<String> = getValue("client_features").jsonArray.map { it.jsonPrimitive.content }
        fun JsonObject.diagnostics(): Map<String, String> =
            getValue("diagnostics").jsonObject.mapValues { it.value.jsonPrimitive.content }
    }
}
