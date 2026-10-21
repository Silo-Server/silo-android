package org.siloserver.silo.common.settings

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.common.player.PlaybackNetworkEvidenceProvider
import org.siloserver.silo.common.player.PlaybackNetworkSnapshot
import org.siloserver.silo.common.player.probe.BandwidthProbeResult
import org.siloserver.silo.common.player.probe.BandwidthProbeScheduler
import org.siloserver.silo.common.player.probe.BandwidthProbeServer
import org.siloserver.silo.common.player.probe.BandwidthProbeTarget
import org.siloserver.silo.common.player.probe.BandwidthProbeTargetLookup
import org.siloserver.silo.common.player.probe.ProbeReport
import org.siloserver.silo.common.player.probe.StoredReadingLookup
import org.siloserver.silo.domain.player.SpeedTestMode
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.network.apiv2.BandwidthProbeResponseV2
import org.siloserver.silo.network.apiv2.PlaybackBandwidthProbeResultV2
import org.siloserver.silo.network.apiv2.PlaybackV2Api
import org.siloserver.silo.repository.PlaybackConnectionRepository
import kotlin.test.Test
import kotlin.test.assertNull

class ConnectionSpeedSettingsModelTest {
    /** The probe's server side: every download answers with [answer]. */
    private class ProbeServer : BandwidthProbeServer {
        var answer: BandwidthProbeResponseV2 = BandwidthProbeResponseV2.RateLimited(30)

        override suspend fun target() = BandwidthProbeTargetLookup.Found(BandwidthProbeTarget(PROBE_URL, SCOPE))

        override suspend fun download(
            target: BandwidthProbeTarget,
            mode: SpeedTestMode,
            read: suspend (BandwidthProbeResponseV2) -> BandwidthProbeResult,
        ): BandwidthProbeResult = read(answer)

        override suspend fun report(target: BandwidthProbeTarget, result: PlaybackBandwidthProbeResultV2) =
            ProbeReport.STORED

        override suspend fun storedReading(target: BandwidthProbeTarget) = StoredReadingLookup.Found(null, null)
    }

    private class Harness(test: TestScope) {
        /** How each `GET /api/v2/playback/connection` answers, by its number from 1: the body. */
        var connection: suspend (read: Int) -> String = { "{\"connection\":\"local\"}" }
        private var reads = 0

        private val client = HttpClient(
            MockEngine(
                MockEngineConfig().apply {
                    dispatcher = StandardTestDispatcher(test.testScheduler)
                    addHandler { request ->
                        val body = when (request.url.encodedPath) {
                            "/api/v2/playback/capabilities" ->
                                """{"state":"available","allowed":true,"protocol_versions":[3],""" +
                                    """"features":["buffering_quality_v1","connection_speed_v1"],"bandwidth_probe_url":"$PROBE_URL"}"""
                            "/api/v2/playback/connection" -> connection(++reads)
                            else -> error("Unexpected request ${request.url}")
                        }
                        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                },
            ),
        ) { install(ContentNegotiation) { json(SiloJson) } }

        private val tokens = object : TokenManager by TokenManagerImpl() {
            override suspend fun snapshotCurrentScope() = SCOPE
        }

        val probeServer = ProbeServer()

        val model = ConnectionSpeedSettingsModel(
            probe = BandwidthProbeScheduler(
                server = probeServer,
                network = PlaybackNetworkEvidenceProvider { WIFI },
                mediaActive = MutableStateFlow(false),
                activeServer = MutableStateFlow(SCOPE.serverId),
                scope = test.backgroundScope,
                elapsedClock = { test.testScheduler.currentTime },
            ),
            connection = PlaybackConnectionRepository(PlaybackV2Api(client, ApiV2Gate.Unrestricted), tokens),
            scope = test.backgroundScope,
        )

        val learnedLimitKbps: Int? get() = model.settings.value?.connection?.learnedLimit?.kbps
    }

    @Test
    fun aSlowReadFromBeforeAResetDoesNotBringTheLimitBack() = runTest {
        val harness = Harness(this)
        val slow = CompletableDeferred<Unit>()
        harness.connection = { read ->
            if (read == 1) {
                // The page's read, answered after the Reset.
                slow.await()
                """{"connection":"local","learned_limit":{"kbps":20000}}"""
            } else {
                """{"connection":"local"}"""
            }
        }
        launch { harness.model.read() }
        runCurrent()

        // Reset's read answers first: the limit is gone.
        harness.model.read()
        assertNull(harness.learnedLimitKbps)

        slow.complete(Unit)
        runCurrent()
        assertNull(harness.learnedLimitKbps, "the older answer is dropped")
    }

    private companion object {
        const val PROBE_URL = "https://silo.example/api/v2/playback/bandwidth-probe"
        val SCOPE = AuthScopeSnapshot("server-1", "profile-1", "https://silo.example", null)
        val WIFI = PlaybackNetworkSnapshot(connected = true, validated = true, transport = "wifi", networkId = 1)
    }
}
