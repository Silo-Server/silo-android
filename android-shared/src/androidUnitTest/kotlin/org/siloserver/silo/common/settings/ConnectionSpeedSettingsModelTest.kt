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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionSpeedSettingsModelTest {
    /** The probe's server side: every download is refused with a `429`. */
    private class ProbeServer : BandwidthProbeServer {
        override suspend fun target() = BandwidthProbeTargetLookup.Found(BandwidthProbeTarget(PROBE_URL, SCOPE))

        override suspend fun download(
            target: BandwidthProbeTarget,
            mode: SpeedTestMode,
            read: suspend (BandwidthProbeResponseV2) -> BandwidthProbeResult,
        ): BandwidthProbeResult = read(BandwidthProbeResponseV2.RateLimited(RETRY_AFTER_SECONDS))

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

        val model = ConnectionSpeedSettingsModel(
            probe = BandwidthProbeScheduler(
                server = ProbeServer(),
                network = PlaybackNetworkEvidenceProvider { WIFI },
                mediaActive = MutableStateFlow(false),
                activeServer = MutableStateFlow(SCOPE.serverId),
                scope = test.backgroundScope,
                elapsedClock = { test.testScheduler.currentTime },
            ),
            connection = PlaybackConnectionRepository(PlaybackV2Api(client, ApiV2Gate.Unrestricted), tokens),
            scope = test.backgroundScope,
            timeSource = test.testTimeSource,
        )

        val learnedLimitKbps: Int? get() = model.settings.value?.connection?.learnedLimit?.kbps
        val message: ConnectionSpeedMessage? get() = model.state.value.message
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

    @Test
    fun tryAgainInCountsDownFromThe429NotFromTheReadThatFollows() = runTest {
        val harness = Harness(this)
        harness.connection = { delay(20_000); "{\"connection\":\"local\"}" }
        harness.model.testSpeed()
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(ConnectionSpeedMessage.TryAgainIn(RETRY_AFTER_SECONDS - 20), harness.message, "20 s spent reading")

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(ConnectionSpeedMessage.TryAgainIn(RETRY_AFTER_SECONDS - 21), harness.message)
        advanceTimeBy(8_999)
        runCurrent()
        assertEquals(ConnectionSpeedMessage.TryAgainIn(1), harness.message)
        advanceTimeBy(1)
        runCurrent()
        assertNull(harness.message, "30 seconds after the 429")
    }

    @Test
    fun aWaitThatPassedDuringTheReadShowsNoMessage() = runTest {
        val harness = Harness(this)
        harness.connection = { delay(31_000); "{\"connection\":\"local\"}" }
        harness.model.testSpeed()
        advanceTimeBy(31_000)
        runCurrent()
        assertFalse(harness.model.state.value.testing)
        assertNull(harness.message)
    }

    private companion object {
        const val RETRY_AFTER_SECONDS = 30
        const val PROBE_URL = "https://silo.example/api/v2/playback/bandwidth-probe"
        val SCOPE = AuthScopeSnapshot("server-1", "profile-1", "https://silo.example", null)
        val WIFI = PlaybackNetworkSnapshot(connected = true, validated = true, transport = "wifi", networkId = 1)
    }
}
