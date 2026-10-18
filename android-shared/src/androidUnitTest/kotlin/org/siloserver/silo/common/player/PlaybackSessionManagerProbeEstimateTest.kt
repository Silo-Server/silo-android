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
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.siloserver.silo.common.player.probe.PlaybackBandwidthEstimates
import org.siloserver.silo.domain.player.BufferingAction
import org.siloserver.silo.domain.player.BufferingPlanSummary
import org.siloserver.silo.domain.player.BufferingQualityMode
import org.siloserver.silo.domain.player.BufferingQualityReplan
import org.siloserver.silo.model.playback.BANDWIDTH_ESTIMATE_SOURCE_OBSERVED_REBUFFER
import org.siloserver.silo.model.playback.BANDWIDTH_ESTIMATE_SOURCE_PROBE
import org.siloserver.silo.model.playback.BUFFERING_QUALITY_V1_FEATURE
import org.siloserver.silo.model.playback.ClientCodecCapabilities
import org.siloserver.silo.model.playback.ClientPlaybackContext
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

/**
 * A video start sends the bandwidth probe's fresh estimate, with source
 * `probe`, and only a start does (the buffering-quality spec's *Trust model*).
 */
class PlaybackSessionManagerProbeEstimateTest {
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

    /** The probe as a start sees it: [estimate] when fresh, and the starts it was told about. */
    private class Probe(var estimate: Int?) : PlaybackBandwidthEstimates {
        var starts = 0
        override fun freshEstimateKbps(): Int? = estimate
        override fun playbackStarting() { starts += 1 }
    }

    private class Harness(capabilityFeatures: List<String>, estimate: Int?) {
        val probe = Probe(estimate)
        val startBodies: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
        val replanBodies: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
        val routeEvents: MutableList<JsonObject> = Collections.synchronizedList(mutableListOf())
        private val identity = Identity()
        private val client = HttpClient(
            MockEngine { request ->
                val raw = request.body.toByteArray().decodeToString()
                val body = { SiloJson.parseToJsonElement(raw).jsonObject }
                val features = capabilityFeatures.joinToString(",") { "\"$it\"" }
                val reply = when (val path = request.url.encodedPath) {
                    "/api/v2/playback/capabilities" -> HttpStatusCode.OK to
                        """{"installation_id":"11111111-1111-4111-8111-111111111111","revision":"1","state":"available","allowed":true,"protocol_versions":[3],"features":[$features]}"""
                    "/api/v2/account/me" -> HttpStatusCode.OK to """{"id":"account-1","username":"test","email":"","role":"user"}"""
                    "/api/v2/playback/start" -> { startBodies += body(); HttpStatusCode.Created to decision(PLAN_1) }
                    "/api/v2/playback/session-1/replan" -> { replanBodies += body(); HttpStatusCode.OK to decision(PLAN_2) }
                    "/api/v2/playback/route-events" -> {
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
            bandwidthEstimates = probe,
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

        suspend fun replan(bufferingQuality: BufferingQualityReplan? = null) = assertIs<ApiResult.Success<VideoSessionStartV3>>(
            manager.replanActiveVideoSession(
                classification = "quality_changed",
                positionSeconds = 125.0,
                audioTrackIndex = null,
                subtitleTrackIndex = null,
                bufferingQuality = bufferingQuality,
            ),
        )

        /** Route events go out on the manager's IO scope, so the list is read under its lock. */
        suspend fun awaitRouteEvent(event: String): JsonObject = withContext(Dispatchers.Default) {
            withTimeout(5_000) {
                var found: JsonObject? = null
                while (found == null) {
                    found = synchronized(routeEvents) { routeEvents.firstOrNull { it.string("event") == event } }
                    if (found == null) delay(10)
                }
                found
            }
        }
    }

    @Test
    fun aStartSendsTheFreshProbeEstimateWithItsSource() = runTest {
        val harness = Harness(BUFFERING_QUALITY_SERVER, estimate = 48_000)
        harness.start()

        val body = harness.startBodies.single()
        assertEquals(JsonPrimitive(48_000), body["bandwidth_estimate_kbps"])
        assertEquals(BANDWIDTH_ESTIMATE_SOURCE_PROBE, body.string("bandwidth_estimate_source"))
        assertTrue(BUFFERING_QUALITY_V1_FEATURE in body.features())
        assertEquals(1, harness.probe.starts)
    }

    @Test
    fun aStartWithoutAFreshEstimateSendsNone() = runTest {
        val harness = Harness(BUFFERING_QUALITY_SERVER, estimate = null)
        harness.start()

        val body = harness.startBodies.single()
        assertNull(body["bandwidth_estimate_kbps"])
        assertNull(body["bandwidth_estimate_source"])
        assertEquals(1, harness.probe.starts)
    }

    @Test
    fun anOlderServerGetsTheEstimateWithoutItsSource() = runTest {
        val harness = Harness(listOf(SEQUENCED_PROGRESS), estimate = 48_000)
        harness.start()

        val body = harness.startBodies.single()
        assertEquals(JsonPrimitive(48_000), body["bandwidth_estimate_kbps"])
        assertNull(body["bandwidth_estimate_source"])
        assertFalse(BUFFERING_QUALITY_V1_FEATURE in body.features())
    }

    @Test
    fun aReplanNeverCarriesTheProbeEstimate() = runTest {
        val harness = Harness(BUFFERING_QUALITY_SERVER, estimate = 48_000)
        harness.start()
        harness.replan()
        val replan = harness.replanBodies.single()
        assertNull(replan["bandwidth_estimate_kbps"])
        assertNull(replan["bandwidth_estimate_source"])
        assertEquals(1, harness.probe.starts)
    }

    @Test
    fun aLoweringReplanCarriesItsOwnStepDownEstimate() = runTest {
        val harness = Harness(BUFFERING_QUALITY_SERVER, estimate = 48_000)
        harness.start()
        harness.replan(
            BufferingQualityReplan(
                qualityPreference = "auto",
                bandwidthEstimateKbps = 44_100,
                bandwidthEstimateSource = BANDWIDTH_ESTIMATE_SOURCE_OBSERVED_REBUFFER,
            ),
        )
        val lowering = harness.replanBodies.single()
        assertEquals(JsonPrimitive(44_100), lowering["bandwidth_estimate_kbps"])
        assertEquals(BANDWIDTH_ESTIMATE_SOURCE_OBSERVED_REBUFFER, lowering.string("bandwidth_estimate_source"))
    }

    @Test
    fun thePlanSummaryReportsTheEstimateTheStartWasRequestedWith() = runTest {
        val harness = Harness(BUFFERING_QUALITY_SERVER + PLAYBACK_PLAN_SUMMARY_V1_FEATURE, estimate = 48_000)
        harness.start()
        harness.manager.emitPlanSummary(
            BufferingPlanSummary(
                planId = PLAN_1.planId,
                playedMs = 60_000,
                stallCount = 0,
                stallTotalMs = 0,
                stallLongestMs = 0,
                deliveredKbps = 31_600,
                mode = BufferingQualityMode.ALWAYS,
                action = BufferingAction.NONE,
            ),
        )
        val event = harness.awaitRouteEvent("plan_summary")
        assertEquals("48000", event.getValue("diagnostics").jsonObject.getValue("bandwidth_estimate_kbps").jsonPrimitive.content)
    }

    private companion object {
        const val SEQUENCED_PROGRESS = "sequenced_progress_v1"
        val BUFFERING_QUALITY_SERVER = listOf(SEQUENCED_PROGRESS, BUFFERING_QUALITY_V1_FEATURE)

        fun plan(id: String, key: String) = PlaybackPlanV3(
            planId = id,
            planAttemptKey = key,
            sessionId = "session-1",
            delivery = PlaybackDelivery.ORIGINAL_HTTP,
            stream = PlaybackStreamV3(url = "/api/v2/stream/session-1/$id", protocol = PlaybackStreamProtocol.HTTP_PROGRESSIVE),
            decisionReason = "direct",
        )

        val PLAN_1 = plan("plan-1", "v3:00000000000000a1")
        val PLAN_2 = plan("plan-2", "v3:00000000000000a2")

        fun decision(plan: PlaybackPlanV3): String = SiloJson.encodeToString(
            PlaybackDecisionResponseV3.serializer(),
            PlaybackDecisionResponseV3(
                protocolVersion = 3,
                serverFeatures = listOf(PLAYBACK_PLAN_V3_FEATURE, NEUTRAL_PLAYBACK_V3_CONTRACT_FEATURE),
                outcome = PlaybackDecisionOutcome.PLAYABLE,
                sessionId = "session-1",
                playbackPlan = plan,
            ),
        )

        fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.content
        fun JsonObject.features(): List<String> = getValue("client_features").jsonArray.map { it.jsonPrimitive.content }
    }
}
