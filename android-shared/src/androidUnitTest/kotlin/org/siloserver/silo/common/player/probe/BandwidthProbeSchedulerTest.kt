package org.siloserver.silo.common.player.probe

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.common.player.PlaybackNetworkEvidenceProvider
import org.siloserver.silo.common.player.PlaybackNetworkSnapshot
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.apiv2.BandwidthProbeResponseV2
import org.siloserver.silo.network.apiv2.PlaybackBandwidthProbeResultV2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BandwidthProbeSchedulerTest {
    private class Network(initial: PlaybackNetworkSnapshot) : PlaybackNetworkEvidenceProvider {
        val state = MutableStateFlow(initial)
        override fun snapshot(): PlaybackNetworkSnapshot = state.value
        override val updates: Flow<PlaybackNetworkSnapshot> get() = state
    }

    private class Server : BandwidthProbeServer {
        var lookup: BandwidthProbeTargetLookup = BandwidthProbeTargetLookup.Found(
            BandwidthProbeTarget("server-1", "https://silo.example/probe?size=40000000",
                AuthScopeSnapshot("server-1", "profile-1", "https://silo.example", null)),
        )
        var lookups = 0
        var downloads = 0
        val reports = mutableListOf<PlaybackBandwidthProbeResultV2>()

        /** What a download answers; by default 1 MB every 100 ms, which times at 80 Mbps. */
        var answer: suspend (suspend (BandwidthProbeResponseV2) -> BandwidthProbeResult) -> BandwidthProbeResult = { read ->
            read(BandwidthProbeResponseV2.Body { delay(100); 1_000_000 })
        }

        override suspend fun target(): BandwidthProbeTargetLookup = lookup.also { lookups += 1 }

        override suspend fun download(
            target: BandwidthProbeTarget,
            read: suspend (BandwidthProbeResponseV2) -> BandwidthProbeResult,
        ): BandwidthProbeResult {
            downloads += 1
            return answer(read)
        }

        override suspend fun report(target: BandwidthProbeTarget, result: PlaybackBandwidthProbeResultV2): Boolean {
            reports += result
            return true
        }
    }

    private class Harness(val test: TestScope, snapshot: PlaybackNetworkSnapshot = WIFI) {
        val network = Network(snapshot)
        val server = Server()
        val media = MutableStateFlow(false)
        val activeServer = MutableStateFlow<String?>("server-1")
        val scheduler = BandwidthProbeScheduler(
            server = server,
            network = network,
            mediaActive = media,
            activeServer = activeServer,
            scope = test.backgroundScope,
            probe = BandwidthProbe(clock = { test.currentTime }),
            elapsedClock = { test.currentTime },
            wallClock = { 1_760_000_000_000 + test.currentTime },
        ).also { it.observe() }

        fun foreground() {
            scheduler.onForeground()
            test.runCurrent()
        }
    }

    @Test
    fun aBackgroundProbeRunsAfterTheStartDelayAndIsReported() = runTest {
        val harness = Harness(this)
        harness.foreground()
        advanceTimeBy(PROBE_START_DELAY_MS - 1)
        assertEquals(0, harness.server.downloads)

        advanceTimeBy(3_000)
        assertEquals(1, harness.server.downloads)
        assertEquals(80_000, harness.scheduler.freshEstimateKbps())
        assertEquals(listOf(PlaybackBandwidthProbeResultV2(80_000, 17_000_000, 1_700, "wifi")), harness.server.reports)
        assertEquals(80_000, harness.scheduler.lastMeasurement.value?.kbps)
    }

    @Test
    fun aMeasurementLastsThirtyMinutes() = runTest {
        val harness = Harness(this)
        val measured = assertIs<ProbeOutcome.Measured>(harness.scheduler.runNow())
        assertEquals(80_000, measured.estimate.kbps)
        val measuredAt = currentTime

        advanceTimeBy(BANDWIDTH_ESTIMATE_TTL_MS)
        runCurrent()
        assertEquals(measuredAt + BANDWIDTH_ESTIMATE_TTL_MS, currentTime)
        assertEquals(80_000, harness.scheduler.freshEstimateKbps())
        advanceTimeBy(1)
        runCurrent()
        assertNull(harness.scheduler.freshEstimateKbps())
        // Not in the foreground, so nothing measures again.
        assertEquals(1, harness.server.downloads)
    }

    @Test
    fun aFreshResultIsReprobedWhenItExpires() = runTest {
        val harness = Harness(this)
        harness.foreground()
        advanceTimeBy(PROBE_START_DELAY_MS + 3_000)
        assertEquals(1, harness.server.downloads)

        advanceTimeBy(BANDWIDTH_ESTIMATE_TTL_MS)
        assertEquals(1, harness.server.downloads)
        advanceTimeBy(5_000)
        assertEquals(2, harness.server.downloads)
    }

    @Test
    fun aNetworkChangeDiscardsTheCacheAndMeasuresAgain() = runTest {
        val harness = Harness(this)
        harness.foreground()
        advanceTimeBy(PROBE_START_DELAY_MS + 3_000)
        assertNotNull(harness.scheduler.freshEstimateKbps())

        harness.network.state.value = WIFI.copy(networkId = 2)
        runCurrent()
        assertNull(harness.scheduler.freshEstimateKbps())
        assertNull(harness.scheduler.lastMeasurement.value)

        advanceTimeBy(PROBE_START_DELAY_MS + 3_000)
        assertEquals(2, harness.server.downloads)
        assertEquals(80_000, harness.scheduler.freshEstimateKbps())
    }

    @Test
    fun aLostNetworkDiscardsTheCacheEvenWhenTheSameOneReturns() = runTest {
        val harness = Harness(this)
        harness.scheduler.runNow()
        harness.network.state.value = PlaybackNetworkSnapshot()
        runCurrent()
        harness.network.state.value = WIFI
        runCurrent()
        assertNull(harness.scheduler.freshEstimateKbps())
    }

    @Test
    fun snapshotUpdatesOnTheSameNetworkKeepTheCache() = runTest {
        val harness = Harness(this)
        harness.scheduler.runNow()
        harness.network.state.value = WIFI.copy(bandwidthEstimateKbps = 12_000, linkDownstreamKbps = 400_000, validated = false)
        runCurrent()
        assertEquals(80_000, harness.scheduler.freshEstimateKbps())
    }

    @Test
    fun aMeasurementForAnotherServerIsNotUsed() = runTest {
        val harness = Harness(this)
        harness.scheduler.runNow()
        harness.activeServer.value = "server-2"
        runCurrent()
        assertNull(harness.scheduler.freshEstimateKbps())
        assertNull(harness.scheduler.lastMeasurement.value)
    }

    @Test
    fun meteredAndCellularNetworksAreNeverProbed() = runTest {
        for (snapshot in listOf(WIFI.copy(metered = true), CELLULAR, WIFI.copy(vpn = true, metered = true))) {
            val harness = Harness(this, snapshot)
            harness.foreground()
            advanceTimeBy(PROBE_RETRY_MS * 3)
            assertEquals(ProbeOutcome.Metered, harness.scheduler.runNow())
            assertEquals(0, harness.server.downloads, "$snapshot")
            assertEquals(0, harness.server.lookups, "$snapshot")
        }
    }

    @Test
    fun aCachedResultIsNotSentOnceTheNetworkIsMetered() = runTest {
        val harness = Harness(this)
        harness.scheduler.runNow()
        // The same network, marked metered by the user: another network key.
        harness.network.state.value = WIFI.copy(metered = true)
        runCurrent()
        assertNull(harness.scheduler.freshEstimateKbps())
    }

    @Test
    fun aServerWithoutTheFeatureIsNeverProbed() = runTest {
        val harness = Harness(this)
        harness.server.lookup = BandwidthProbeTargetLookup.NotSupported
        harness.foreground()
        advanceTimeBy(PROBE_START_DELAY_MS + PROBE_RETRY_MS * 3)
        assertEquals(1, harness.server.lookups, "no retries until the next foreground or sign-in")
        assertEquals(ProbeOutcome.NotSupported, harness.scheduler.runNow())
        assertEquals(0, harness.server.downloads)
        assertNull(harness.scheduler.freshEstimateKbps())
    }

    @Test
    fun signedOutIsNotSupported() = runTest {
        val harness = Harness(this)
        harness.activeServer.value = null
        assertEquals(ProbeOutcome.NotSupported, harness.scheduler.runNow())
        assertEquals(0, harness.server.lookups)
    }

    @Test
    fun aRateLimitedTestReportsItsRetryAfter() = runTest {
        val harness = Harness(this)
        harness.server.answer = { read -> read(BandwidthProbeResponseV2.RateLimited(42)) }
        assertEquals(ProbeOutcome.RateLimited(42), harness.scheduler.runNow())
        assertNull(harness.scheduler.freshEstimateKbps())
        assertTrue(harness.server.reports.isEmpty())
    }

    @Test
    fun playingMediaRefusesAProbe() = runTest {
        val harness = Harness(this)
        harness.media.value = true
        runCurrent()
        assertEquals(ProbeOutcome.PlaybackActive, harness.scheduler.runNow())
        assertEquals(0, harness.server.downloads)
    }

    @Test
    fun mediaStartingCancelsARunningProbe() = runTest {
        val harness = Harness(this)
        val outcome = async { harness.scheduler.runNow() }
        advanceTimeBy(500)
        assertEquals(1, harness.server.downloads)
        harness.media.value = true
        runCurrent()
        assertEquals(ProbeOutcome.PlaybackActive, outcome.await())
        assertNull(harness.scheduler.freshEstimateKbps())
        assertTrue(harness.server.reports.isEmpty())
    }

    @Test
    fun aPlaybackStartHoldsProbesOffForTheQuietPeriod() = runTest {
        val harness = Harness(this)
        harness.scheduler.playbackStarting()
        runCurrent()
        assertEquals(ProbeOutcome.PlaybackActive, harness.scheduler.runNow())
        advanceTimeBy(PLAYBACK_START_QUIET_MS)
        assertIs<ProbeOutcome.Measured>(harness.scheduler.runNow())
    }

    @Test
    fun aPlaybackStartCancelsARunningProbe() = runTest {
        val harness = Harness(this)
        val started = CompletableDeferred<Unit>()
        harness.server.answer = { read ->
            started.complete(Unit)
            read(BandwidthProbeResponseV2.Body { delay(100); 1_000_000 })
        }
        val outcome = async { harness.scheduler.runNow() }
        started.await()
        harness.scheduler.playbackStarting()
        runCurrent()
        assertEquals(ProbeOutcome.PlaybackActive, outcome.await())
    }

    @Test
    fun leavingTheForegroundStopsTheSchedule() = runTest {
        val harness = Harness(this)
        harness.foreground()
        harness.scheduler.onBackground()
        advanceTimeBy(PROBE_START_DELAY_MS + PROBE_RETRY_MS)
        assertEquals(0, harness.server.downloads)
    }

    @Test
    fun testSpeedJoinsAProbeAlreadyRunning() = runTest {
        val harness = Harness(this)
        harness.foreground()
        advanceTimeBy(PROBE_START_DELAY_MS + 500)
        assertEquals(1, harness.server.downloads)
        assertIs<ProbeOutcome.Measured>(harness.scheduler.runNow())
        assertEquals(1, harness.server.downloads)
    }

    private companion object {
        val WIFI = PlaybackNetworkSnapshot(connected = true, validated = true, transport = "wifi", networkId = 1)
        val CELLULAR = PlaybackNetworkSnapshot(connected = true, validated = true, transport = "cellular", cellular = true, networkId = 3)
    }
}
