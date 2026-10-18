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
import org.siloserver.silo.domain.player.SpeedTestMode
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
        var lookup: BandwidthProbeTargetLookup = BandwidthProbeTargetLookup.Found(probeTarget())
        var lookups = 0
        val modes = mutableListOf<SpeedTestMode>()
        val downloads get() = modes.size
        var storedChecks = 0
        val reports = mutableListOf<PlaybackBandwidthProbeResultV2>()

        /** What `GET /api/v2/playback/connection` holds for this device; none by default. */
        var stored: StoredReadingLookup = StoredReadingLookup.None

        /** What a download answers; by default 1 MB every 100 ms, which times at 80 Mbps. */
        var answer: suspend (suspend (BandwidthProbeResponseV2) -> BandwidthProbeResult) -> BandwidthProbeResult = { read ->
            read(BandwidthProbeResponseV2.Body { delay(100); 1_000_000 })
        }

        override suspend fun target(): BandwidthProbeTargetLookup = lookup.also { lookups += 1 }

        override suspend fun download(
            target: BandwidthProbeTarget,
            mode: SpeedTestMode,
            read: suspend (BandwidthProbeResponseV2) -> BandwidthProbeResult,
        ): BandwidthProbeResult {
            modes += mode
            return answer(read)
        }

        /** Serves [target] from now on. */
        fun serve(target: BandwidthProbeTarget) {
            lookup = BandwidthProbeTargetLookup.Found(target)
        }

        override suspend fun report(target: BandwidthProbeTarget, result: PlaybackBandwidthProbeResultV2): Boolean {
            reports += result
            return true
        }

        override suspend fun storedReading(target: BandwidthProbeTarget): StoredReadingLookup =
            stored.also { storedChecks += 1 }
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

        private fun measuredAt(ageMs: Long) = 1_760_000_000_000 + test.currentTime - ageMs

        /** A probe the server holds, measured [ageMs] before now on the harness's wall clock. */
        fun storedReading(kbps: Int, ageMs: Long, lowerBound: Boolean = false) = StoredReadingLookup.Found(
            probe = BandwidthEstimate(kbps, 0, 0, "wifi", measuredAt(ageMs), lowerBound),
            sample = null,
        )

        /** A playback sample the server holds, taken [ageMs] before now. */
        fun storedSample(kbps: Int, ageMs: Long) =
            StoredReadingLookup.Found(probe = null, sample = PlaybackSample(kbps, measuredAt(ageMs)))
    }

    /** Advances [ms] and runs what is due at the end, which [advanceTimeBy] leaves. */
    private fun TestScope.advanceThrough(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun aBackgroundProbeRunsAfterTheStartDelayAndIsReported() = runTest {
        val harness = Harness(this)
        harness.foreground()
        advanceTimeBy(PROBE_START_DELAY_MS - 1)
        assertEquals(0, harness.server.downloads)

        advanceThrough(1 + QUICK_TEST_MS)
        assertEquals(listOf(SpeedTestMode.QUICK), harness.server.modes)
        assertEquals(80_000, harness.scheduler.freshEstimateKbps())
        assertEquals(listOf(PlaybackBandwidthProbeResultV2(80_000, 10_000_000, 1_000, "wifi")), harness.server.reports)
        assertEquals(80_000, harness.scheduler.lastMeasurement.value?.kbps)
        assertEquals(1, harness.server.storedChecks)
    }

    @Test
    fun aReadingLastsItsLifetime() = runTest {
        val harness = Harness(this)
        val measured = assertIs<ProbeOutcome.Measured>(harness.scheduler.runNow())
        assertEquals(80_000, measured.estimate.kbps)
        val measuredAt = currentTime

        advanceTimeBy(BANDWIDTH_READING_LIFETIME_MS - 1 - (currentTime - measuredAt))
        runCurrent()
        assertEquals(80_000, harness.scheduler.freshEstimateKbps())
        advanceTimeBy(1)
        runCurrent()
        assertNull(harness.scheduler.freshEstimateKbps())
        // Not in the foreground, so nothing measures again.
        assertEquals(1, harness.server.downloads)
    }

    @Test
    fun aReadingInHandIsNotMeasuredAgainUntilItsLifetimeEnds() = runTest {
        val harness = Harness(this)
        harness.foreground()
        advanceThrough(PROBE_START_DELAY_MS + QUICK_TEST_MS)
        assertEquals(1, harness.server.downloads)

        // Coming back to the foreground with a reading in hand asks nobody,
        // and nothing checks on a timer while it stays in the foreground.
        harness.scheduler.onBackground()
        harness.foreground()
        advanceTimeBy(BANDWIDTH_READING_LIFETIME_MS + PROBE_START_DELAY_MS)
        assertEquals(1, harness.server.downloads)
        assertEquals(1, harness.server.storedChecks)

        // The next check after it expires finds the server's copy just as old, and measures.
        harness.server.stored = harness.storedReading(80_000, BANDWIDTH_READING_LIFETIME_MS + 1)
        harness.scheduler.onBackground()
        harness.foreground()
        advanceThrough(PROBE_START_DELAY_MS + QUICK_TEST_MS)
        assertEquals(2, harness.server.storedChecks)
        assertEquals(2, harness.server.downloads)
    }

    @Test
    fun aYoungReadingOnTheServerStandsInForAMeasurement() = runTest {
        val harness = Harness(this)
        harness.server.stored = harness.storedReading(48_000, ageMs = 2 * 24 * 60 * 60 * 1000L)
        harness.foreground()
        advanceThrough(PROBE_START_DELAY_MS + QUICK_TEST_MS)
        assertEquals(1, harness.server.storedChecks)
        assertEquals(0, harness.server.downloads)
        assertTrue(harness.server.reports.isEmpty())
        assertEquals(48_000, harness.scheduler.freshEstimateKbps())
        assertEquals(48_000, harness.scheduler.lastMeasurement.value?.kbps)
    }

    @Test
    fun anExpiredReadingOnTheServerIsMeasuredAgain() = runTest {
        val harness = Harness(this)
        harness.server.stored = harness.storedReading(48_000, ageMs = BANDWIDTH_READING_LIFETIME_MS + 1)
        harness.foreground()
        advanceThrough(PROBE_START_DELAY_MS + QUICK_TEST_MS)
        assertEquals(1, harness.server.downloads)
        assertEquals(80_000, harness.scheduler.freshEstimateKbps())
    }

    @Test
    fun aFailedServerCheckMeasuresNothingAndWaitsForTheNextCheck() = runTest {
        val harness = Harness(this)
        harness.server.stored = StoredReadingLookup.Failed(null)
        harness.foreground()
        advanceThrough(PROBE_START_DELAY_MS + QUICK_TEST_MS)
        assertEquals(0, harness.server.downloads)

        harness.server.stored = StoredReadingLookup.None
        advanceTimeBy(IDLE_MS)
        assertEquals(1, harness.server.storedChecks, "no retry on a timer")
        harness.network.state.value = WIFI.copy(networkId = 2)
        runCurrent()
        advanceThrough(PROBE_START_DELAY_MS + QUICK_TEST_MS)
        assertEquals(1, harness.server.downloads)
    }

    @Test
    fun noCheckMeasuresBeforeARateLimitsRetryAfter() = runTest {
        val harness = Harness(this)
        harness.server.answer = { read -> read(BandwidthProbeResponseV2.RateLimited(600)) }
        harness.foreground()
        advanceThrough(PROBE_START_DELAY_MS + QUICK_TEST_MS)
        assertEquals(1, harness.server.downloads)

        harness.server.answer = { read -> read(BandwidthProbeResponseV2.Body { delay(100); 1_000_000 }) }
        harness.scheduler.onBackground()
        harness.foreground()
        advanceThrough(PROBE_START_DELAY_MS + QUICK_TEST_MS)
        assertEquals(1, harness.server.downloads, "inside the Retry-After")

        advanceTimeBy(600_000)
        harness.scheduler.onBackground()
        harness.foreground()
        advanceThrough(PROBE_START_DELAY_MS + QUICK_TEST_MS)
        assertEquals(2, harness.server.downloads)
    }

    @Test
    fun aNetworkChangeTakesTheServersReadingForTheNewNetwork() = runTest {
        val harness = Harness(this)
        harness.foreground()
        advanceThrough(PROBE_START_DELAY_MS + QUICK_TEST_MS)
        assertNotNull(harness.scheduler.freshEstimateKbps())

        harness.server.stored = harness.storedReading(30_000, ageMs = 60_000)
        harness.network.state.value = WIFI.copy(networkId = 2)
        runCurrent()
        assertNull(harness.scheduler.freshEstimateKbps())
        assertNull(harness.scheduler.lastMeasurement.value)

        advanceThrough(PROBE_START_DELAY_MS + QUICK_TEST_MS)
        assertEquals(1, harness.server.downloads, "the change alone measures nothing")
        assertEquals(30_000, harness.scheduler.freshEstimateKbps())

        // A network the server has no reading for is measured.
        harness.server.stored = StoredReadingLookup.None
        harness.network.state.value = WIFI.copy(networkId = 3)
        runCurrent()
        advanceThrough(PROBE_START_DELAY_MS + QUICK_TEST_MS)
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
            advanceTimeBy(IDLE_MS)
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
        advanceTimeBy(PROBE_START_DELAY_MS + IDLE_MS)
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
        advanceTimeBy(PROBE_START_DELAY_MS + IDLE_MS)
        assertEquals(0, harness.server.downloads)
    }

    @Test
    fun testSpeedReplacesARunningAutomaticCheck() = runTest {
        val harness = Harness(this)
        harness.foreground()
        advanceTimeBy(PROBE_START_DELAY_MS + 500)
        assertEquals(1, harness.server.downloads)
        assertIs<ProbeOutcome.Measured>(harness.scheduler.runNow())
        assertEquals(2, harness.server.downloads)
        assertEquals(1, harness.server.reports.size)
    }

    @Test
    fun testSpeedJoinsTestSpeed() = runTest {
        val harness = Harness(this)
        val first = async { harness.scheduler.runNow() }
        advanceTimeBy(500)
        assertIs<ProbeOutcome.Measured>(harness.scheduler.runNow())
        assertIs<ProbeOutcome.Measured>(first.await())
        assertEquals(1, harness.server.downloads)
    }

    @Test
    fun testSpeedRunsTheAccurateTest() = runTest {
        val harness = Harness(this)
        harness.server.serve(probeTarget(targetKbps = 50_000))
        val measured = assertIs<ProbeOutcome.Measured>(harness.scheduler.runNow())
        assertEquals(10_100L, currentTime, "the target does not stop it")
        assertEquals(listOf(SpeedTestMode.ACCURATE), harness.server.modes)
        assertEquals(80_000, measured.estimate.kbps)
        assertEquals(listOf(PlaybackBandwidthProbeResultV2(80_000, 30_000_000, 3_000, "wifi")), harness.server.reports)
    }

    @Test
    fun testSpeedMeasuresEvenWithAYoungReadingOnTheServer() = runTest {
        val harness = Harness(this)
        harness.server.stored = harness.storedReading(48_000, ageMs = 60_000)
        assertIs<ProbeOutcome.Measured>(harness.scheduler.runNow())
        assertEquals(0, harness.server.storedChecks)
        assertEquals(1, harness.server.downloads)
    }

    @Test
    fun aQuickTestThatReachesTheTargetReportsALowerBound() = runTest {
        val harness = Harness(this)
        harness.server.serve(probeTarget(targetKbps = 50_000))
        harness.foreground()
        advanceThrough(PROBE_START_DELAY_MS + 1_100)
        assertEquals(
            listOf(PlaybackBandwidthProbeResultV2(50_000, 5_000_000, 500, "wifi", lowerBound = true)),
            harness.server.reports,
        )
        assertEquals(50_000, harness.scheduler.freshEstimateKbps())
        assertEquals(true, harness.scheduler.lastMeasurement.value?.lowerBound)
    }

    @Test
    fun aLowerBoundProvesTheLinkOnlyWhileItReachesTheTarget() = runTest {
        val harness = Harness(this)
        harness.server.serve(probeTarget(targetKbps = 50_000))
        harness.foreground()
        advanceThrough(PROBE_START_DELAY_MS + 1_100)
        assertEquals(1, harness.server.downloads)

        // A lower bound in hand is not enough to skip the check: the target may have moved.
        harness.scheduler.onBackground()
        harness.foreground()
        advanceThrough(PROBE_START_DELAY_MS + 1_100)
        assertEquals(2, harness.server.storedChecks)
        assertEquals(1, harness.server.downloads, "50 Mbps still proves a 50 Mbps target")

        harness.server.serve(probeTarget(targetKbps = 60_000))
        harness.scheduler.onBackground()
        harness.foreground()
        advanceThrough(PROBE_START_DELAY_MS + 1_100)
        assertEquals(2, harness.server.downloads)
        assertEquals(60_000, harness.scheduler.freshEstimateKbps())
    }

    @Test
    fun aLowerBoundOnTheServerBelowTheTargetIsTestedAgain() = runTest {
        val harness = Harness(this)
        harness.server.serve(probeTarget(targetKbps = 50_000))
        harness.server.stored = harness.storedReading(30_000, ageMs = 60_000, lowerBound = true)
        harness.foreground()
        advanceThrough(PROBE_START_DELAY_MS + 1_100)
        assertEquals(1, harness.server.downloads)
        assertEquals(50_000, harness.scheduler.freshEstimateKbps())
    }

    @Test
    fun aPlaybackSampleAtTheTargetProvesTheLink() = runTest {
        val harness = Harness(this)
        harness.server.serve(probeTarget(targetKbps = 50_000))
        harness.server.stored = harness.storedSample(60_000, ageMs = 60_000)
        harness.foreground()
        advanceThrough(PROBE_START_DELAY_MS + QUICK_TEST_MS)
        assertEquals(0, harness.server.downloads)
        assertEquals(60_000, harness.scheduler.freshEstimateKbps())
        assertNull(harness.scheduler.lastMeasurement.value, "a sample is not a probe reading")
    }

    @Test
    fun aPlaybackSampleWithoutATargetProvesNothing() = runTest {
        val harness = Harness(this)
        harness.server.stored = harness.storedSample(90_000, ageMs = 60_000)
        harness.foreground()
        advanceThrough(PROBE_START_DELAY_MS + QUICK_TEST_MS)
        assertEquals(1, harness.server.downloads)
        assertEquals(90_000, harness.scheduler.freshEstimateKbps(), "the highest current reading")
    }

    @Test
    fun anExpiredSampleIsNotSent() = runTest {
        val harness = Harness(this)
        harness.server.serve(probeTarget(targetKbps = 50_000, automatic = false))
        harness.server.stored = harness.storedSample(60_000, ageMs = BANDWIDTH_READING_LIFETIME_MS)
        harness.foreground()
        advanceThrough(PROBE_START_DELAY_MS + QUICK_TEST_MS)
        assertNull(harness.scheduler.freshEstimateKbps())
    }

    @Test
    fun theServersLifetimeJudgesTheReadings() = runTest {
        val harness = Harness(this)
        harness.server.serve(probeTarget(lifetimeMs = 60_000))
        harness.server.stored = harness.storedReading(48_000, ageMs = 60_000)
        harness.foreground()
        advanceThrough(PROBE_START_DELAY_MS + QUICK_TEST_MS)
        assertEquals(1, harness.server.downloads)
        assertEquals(80_000, harness.scheduler.freshEstimateKbps())
        advanceTimeBy(60_000)
        assertNull(harness.scheduler.freshEstimateKbps())
    }

    @Test
    fun automaticTestsOffLeaveTestSpeed() = runTest {
        val harness = Harness(this)
        harness.server.serve(probeTarget(automatic = false))
        harness.foreground()
        advanceTimeBy(PROBE_START_DELAY_MS + IDLE_MS)
        assertEquals(1, harness.server.storedChecks)
        assertEquals(0, harness.server.downloads)

        assertIs<ProbeOutcome.Measured>(harness.scheduler.runNow())
        assertEquals(listOf(SpeedTestMode.ACCURATE), harness.server.modes)
    }

    private companion object {
        /** Long enough for any timer the scheduler might wrongly keep. */
        const val IDLE_MS = 60 * 60 * 1000L

        /** A quick test of the default body without a target: the first byte at 100 ms, then 3 s. */
        const val QUICK_TEST_MS = 3_100L

        fun probeTarget(
            targetKbps: Int? = null,
            automatic: Boolean = true,
            lifetimeMs: Long = BANDWIDTH_READING_LIFETIME_MS,
        ) = BandwidthProbeTarget(
            serverId = "server-1",
            url = "https://silo.example/probe",
            scope = AuthScopeSnapshot("server-1", "profile-1", "https://silo.example", null),
            targetKbps = targetKbps,
            automatic = automatic,
            readingLifetimeMs = lifetimeMs,
        )

        val WIFI = PlaybackNetworkSnapshot(connected = true, validated = true, transport = "wifi", networkId = 1)
        val CELLULAR = PlaybackNetworkSnapshot(connected = true, validated = true, transport = "cellular", cellular = true, networkId = 3)
    }
}
