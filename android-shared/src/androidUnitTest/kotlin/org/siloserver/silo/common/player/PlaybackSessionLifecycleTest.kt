package org.siloserver.silo.common.player

import org.siloserver.silo.common.diagnostics.DiagnosticsPlaybackSessionRecorder
import org.siloserver.silo.model.personal.SyncProgressItem
import org.siloserver.silo.model.playback.ClientCodecCapabilities
import org.siloserver.silo.model.playback.ClientPlaybackContext
import org.siloserver.silo.model.playback.PlayMethod
import org.siloserver.silo.model.playback.PlaybackSessionResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.api.HealthApi
import org.siloserver.silo.network.api.HealthStatus
import org.siloserver.silo.network.api.PersonalDataApi
import org.siloserver.silo.network.api.PlaybackApi
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.repository.PlaybackRepository
import io.ktor.client.HttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Integration-flavor tests for [PlaybackSessionLifecycle]. Exercises the
 * transition paths the wrapper owns:
 *
 *   - adoption of an already-planned session -> Active and clean stop
 *   - 404 session_not_found mid-progress -> snapshot + a renewal handed to the owner
 *   - NetworkError mid-progress -> Reconnecting + health-probe loop
 *
 * The lifecycle does not start sessions: under protocol v3 planning belongs to
 * `PlaybackSessionManager.startVideoSessionV3`, so every test here begins from
 * [PlaybackSessionLifecycle.adoptActiveSession].
 *
 * Time is fully virtual via `runTest` + `advanceTimeBy` so we can verify the
 * 1s -> 2s -> 4s -> 8s -> 8s exponential backoff and the 90s outage timeout
 * without sleeping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSessionLifecycleTest {

    @Test
    fun `adoptActiveSession reports progress without starting duplicate session`() = runTest {
        val sessionMgr = FakeSessionManager()
        val recordedSessions = mutableListOf<String>()
        val lifecycle = newLifecycle(
            sessionMgr,
            scope = backgroundScope,
            playbackSessions = DiagnosticsPlaybackSessionRecorder { recordedSessions += it },
        )

        lifecycle.adoptActiveSession(
            params = defaultStartParams(startPosition = 12.0),
            session = makeSession("sess-adopted"),
        )

        val active = lifecycle.state.value
        assertTrue(active is SessionState.Active)
        assertEquals("sess-adopted", (active as SessionState.Active).session.sessionId)
        assertEquals(listOf("sess-adopted"), recordedSessions)

        lifecycle.reportOwnedPosition(positionSec = 33.0, durationSec = 100.0, isPaused = false)
        advanceTimeBy(PlaybackSessionLifecycle.PROGRESS_REPORT_INTERVAL_MS + 100)

        assertEquals(1, sessionMgr.progressCallCount)
        assertEquals("sess-adopted", sessionMgr.lastProgressSessionId)
        assertEquals(33.0, sessionMgr.lastProgressPosition)
    }

    @Test
    fun `same-session replan preserves an in-flight progress report`() = runTest {
        val reportEntered = CompletableDeferred<Unit>()
        val releaseReport = CompletableDeferred<Unit>()
        var reportWasCancelled = false
        val sessionMgr = object : FakeSessionManager() {
            override suspend fun reportProgress(
                sessionId: String,
                position: Double,
                isPaused: Boolean,
            ): ApiResult<Unit> {
                progressCallCount++
                reportEntered.complete(Unit)
                return try {
                    releaseReport.await()
                    ApiResult.Success(Unit)
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    reportWasCancelled = true
                    // Matches safeApiCall, which currently wraps cancellation
                    // as a network result instead of rethrowing it.
                    ApiResult.NetworkError(cancellation)
                }
            }
        }
        val healthApi = FakeHealthApi()
        val lifecycle = newLifecycle(
            sessionMgr = sessionMgr,
            healthApi = healthApi,
            scope = backgroundScope,
        )

        lifecycle.adoptActiveSession(defaultStartParams(), makeSession("sess-replan"))
        lifecycle.reportOwnedPosition(42.0, 100.0, isPaused = false)
        advanceTimeBy(PlaybackSessionLifecycle.PROGRESS_REPORT_INTERVAL_MS + 100)
        reportEntered.await()

        lifecycle.adoptActiveSession(
            params = defaultStartParams(startPosition = 42.0).copy(subtitleTrackIndex = 7),
            session = makeSession("sess-replan"),
            deferPublication = true,
        )
        yield()

        assertFalse(reportWasCancelled)
        assertTrue(lifecycle.state.value is SessionState.Active)
        assertEquals(0, healthApi.callCount)

        releaseReport.complete(Unit)
        advanceUntilIdle()
        assertFalse(reportWasCancelled)
        assertEquals(0, healthApi.callCount)
    }

    @Test
    fun `adoptActiveSession can leave progress and stop owned by caller`() = runTest {
        val sessionMgr = FakeSessionManager()
        val personalRepo = RecordingPersonalDataRepository()
        val lifecycle = newLifecycle(
            sessionMgr = sessionMgr,
            personalRepo = personalRepo,
            scope = backgroundScope,
        )

        lifecycle.adoptActiveSession(
            params = defaultStartParams(startPosition = 12.0),
            session = makeSession("sess-passive"),
            manageProgress = false,
            stopSessionOnStop = false,
        )

        lifecycle.reportOwnedPosition(positionSec = 33.0, durationSec = 100.0, isPaused = false)
        advanceTimeBy(PlaybackSessionLifecycle.PROGRESS_REPORT_INTERVAL_MS + 100)
        lifecycle.stop()
        advanceUntilIdle()

        assertEquals(0, sessionMgr.progressCallCount)
        assertEquals(0, sessionMgr.stopCallCount)
        assertTrue(personalRepo.syncCalls.isEmpty())
        assertTrue(lifecycle.state.value is SessionState.Idle)
    }

    @Test
    fun `adoption captured before stop is rejected and server session is closed`() = runTest {
        val sessionMgr = FakeSessionManager()
        val lifecycle = newLifecycle(sessionMgr, scope = backgroundScope)
        val ownershipEpoch = lifecycle.acquireOwnershipEpoch()

        lifecycle.stop()
        val adopted = lifecycle.adoptActiveSessionIfCurrent(
            params = defaultStartParams(),
            session = makeSession("sess-late-adopt"),
            expectedOwnershipEpoch = ownershipEpoch,
        )

        assertFalse(adopted)
        assertEquals(SessionState.Idle, lifecycle.state.value)
        assertEquals("sess-late-adopt", sessionMgr.lastStoppedSessionId)
    }

    @Test
    fun `epoch adoption can defer publication for the first session`() = runTest {
        val sessionMgr = FakeSessionManager()
        val lifecycle = newLifecycle(sessionMgr, scope = backgroundScope)
        val ownershipEpoch = lifecycle.acquireOwnershipEpoch()

        assertTrue(
            lifecycle.adoptActiveSessionIfCurrent(
                params = defaultStartParams(),
                session = makeSession("sess-first"),
                deferPublication = true,
                expectedOwnershipEpoch = ownershipEpoch,
            ),
        )

        assertTrue(lifecycle.confirmActiveSessionPublication("sess-first"))
        assertEquals(
            "sess-first",
            (lifecycle.state.value as SessionState.Active).session.sessionId,
        )
    }

    @Test
    fun `ownership acquisition waits for queued stop before accepting a new start`() = runTest {
        val oldStopEntered = CompletableDeferred<Unit>()
        val releaseOldStop = CompletableDeferred<Unit>()
        val stopped = mutableListOf<String>()
        val sessionMgr = object : FakeSessionManager() {
            override suspend fun stopSession(sessionId: String): ApiResult<Unit> {
                stopped += sessionId
                if (sessionId == "sess-old") {
                    oldStopEntered.complete(Unit)
                    releaseOldStop.await()
                }
                return ApiResult.Success(Unit)
            }
        }
        val lifecycle = newLifecycle(sessionMgr, scope = backgroundScope)
        lifecycle.adoptActiveSession(defaultStartParams(), makeSession("sess-old"))

        lifecycle.stopAsync()
        oldStopEntered.await()
        val epoch = async { lifecycle.acquireOwnershipEpoch() }
        assertFalse(epoch.isCompleted)

        releaseOldStop.complete(Unit)
        val acquired = epoch.await()
        assertTrue(
            lifecycle.adoptActiveSessionIfCurrent(
                params = defaultStartParams(),
                session = makeSession("sess-new"),
                expectedOwnershipEpoch = acquired,
            ),
        )
        assertEquals("sess-new", (lifecycle.state.value as SessionState.Active).session.sessionId)
        assertEquals(listOf("sess-old"), stopped)
    }

    @Test
    fun `external session finalization returns before reporting finishes and stops afterward`() = runTest {
        val reportEntered = CompletableDeferred<Unit>()
        val releaseReport = CompletableDeferred<Unit>()
        val stopCompleted = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val sessionMgr = object : FakeSessionManager() {
            override suspend fun reportProgress(
                sessionId: String,
                position: Double,
                isPaused: Boolean,
            ): ApiResult<Unit> {
                calls += "report:$sessionId:$position:$isPaused"
                reportEntered.complete(Unit)
                releaseReport.await()
                return ApiResult.Success(Unit)
            }

            override suspend fun stopSession(sessionId: String): ApiResult<Unit> {
                calls += "stop:$sessionId"
                stopCompleted.complete(Unit)
                return ApiResult.Success(Unit)
            }
        }
        val lifecycle = newLifecycle(sessionMgr, scope = backgroundScope)

        lifecycle.reportAndStopExternalSessionAsync(
            sessionId = "audiobook-session",
            positionSeconds = 42.25,
            isPaused = true,
        )

        reportEntered.await()
        assertEquals(
            listOf("report:audiobook-session:42.25:true"),
            calls,
            "the non-blocking caller must return while progress reporting is suspended",
        )

        releaseReport.complete(Unit)
        stopCompleted.await()

        assertEquals(
            listOf(
                "report:audiobook-session:42.25:true",
                "stop:audiobook-session",
            ),
            calls,
        )
    }

    @Test
    fun `duplicate external session finalization is coalesced`() = runTest {
        val reportEntered = CompletableDeferred<Unit>()
        val releaseReport = CompletableDeferred<Unit>()
        val stopCompleted = CompletableDeferred<Unit>()
        val sessionMgr = object : FakeSessionManager() {
            override suspend fun reportProgress(
                sessionId: String,
                position: Double,
                isPaused: Boolean,
            ): ApiResult<Unit> {
                progressCallCount++
                reportEntered.complete(Unit)
                releaseReport.await()
                return ApiResult.Success(Unit)
            }

            override suspend fun stopSession(sessionId: String): ApiResult<Unit> {
                val result = super.stopSession(sessionId)
                stopCompleted.complete(Unit)
                return result
            }
        }
        val lifecycle = newLifecycle(sessionMgr, scope = backgroundScope)

        repeat(2) {
            lifecycle.reportAndStopExternalSessionAsync(
                sessionId = "audiobook-session",
                positionSeconds = 42.25,
                isPaused = true,
            )
        }

        reportEntered.await()
        assertEquals(1, sessionMgr.progressCallCount)

        releaseReport.complete(Unit)
        stopCompleted.await()

        assertEquals(1, sessionMgr.progressCallCount)
        assertEquals(1, sessionMgr.stopCallCount)
    }

    @Test
    fun `cancelled adoption closes the allocated server session`() = runTest {
        val oldStopEntered = CompletableDeferred<Unit>()
        val releaseOldStop = CompletableDeferred<Unit>()
        val candidateStopped = CompletableDeferred<Unit>()
        val sessionMgr = object : FakeSessionManager() {
            override suspend fun stopSession(sessionId: String): ApiResult<Unit> {
                when (sessionId) {
                    "sess-old" -> {
                        oldStopEntered.complete(Unit)
                        releaseOldStop.await()
                    }
                    "sess-candidate" -> candidateStopped.complete(Unit)
                }
                return ApiResult.Success(Unit)
            }
        }
        val lifecycle = newLifecycle(sessionMgr, scope = backgroundScope)
        lifecycle.adoptActiveSession(defaultStartParams(), makeSession("sess-old"))
        lifecycle.stopAsync()
        oldStopEntered.await()

        val adoption = async {
            lifecycle.adoptActiveSessionIfCurrent(
                params = defaultStartParams(),
                session = makeSession("sess-candidate"),
                expectedOwnershipEpoch = 0L,
            )
        }
        yield()
        adoption.cancelAndJoin()

        assertTrue(candidateStopped.isCompleted)
        releaseOldStop.complete(Unit)
    }

    @Test
    fun `reportPosition with 404 snapshots progress and hands renewal to the owner`() = runTest {
        val sessionMgr = FakeSessionManager().apply {
            // First reportProgress returns 404 to trigger recovery.
            progressResults = ArrayDeque(listOf(
                ApiResult.Error(404, "playback_session_not_found", "Playback session not found"),
            ))
        }
        val personalRepo = RecordingPersonalDataRepository()
        val lifecycle = newLifecycle(
            sessionMgr,
            personalRepo = personalRepo,
            scope = backgroundScope,
        )
        val renewals = mutableListOf<MissingSessionRenewal>()
        backgroundScope.launch { lifecycle.missingSessionEvents.collect { renewals += it } }
        advanceUntilIdle()

        val startParams = defaultStartParams(startPosition = 0.0).copy(
            audioTrackIndex = 2,
            subtitleTrackIndex = 8,
            qualityPreference = "original",
        )
        lifecycle.adoptActiveSession(
            params = startParams,
            session = makeSession("sess-original"),
        )

        // Simulate the player advancing.
        lifecycle.reportOwnedPosition(positionSec = 42.5, durationSec = 100.0, isPaused = false)

        // Trigger the 10s reporter; the first call returns 404 -> recovery.
        advanceTimeBy(PlaybackSessionLifecycle.PROGRESS_REPORT_INTERVAL_MS + 100)
        advanceUntilIdle()

        // Snapshot was synced with forceOverwrite at position 42.5. This write
        // is what protects the resume point if the owner's replan then fails.
        val snapshot = personalRepo.syncCalls.firstOrNull()
            ?: fail("expected syncProgress to be called during recovery")
        assertEquals(1, snapshot.size)
        assertEquals("content-1", snapshot.first().mediaItemId)
        assertEquals(42.5, snapshot.first().position)
        assertTrue(snapshot.first().forceOverwrite)

        // The lifecycle hands the resume position to whoever owns planning;
        // it does not start a replacement session itself.
        assertEquals(1, renewals.size)
        assertEquals("sess-original", renewals.single().staleSessionId)
        assertEquals(42.5, renewals.single().positionSeconds, 0.0)
        assertEquals(startParams, renewals.single().startParams)

        lifecycle.stop()
    }

    @Test
    fun `reportPosition with NetworkError transitions to Reconnecting and probes health`() = runTest {
        val sessionMgr = FakeSessionManager().apply {
            progressResults = ArrayDeque(listOf(
                ApiResult.NetworkError(RuntimeException("offline")),
            ))
        }
        val healthApi = FakeHealthApi().apply {
            // First probe still down, second comes back.
            results = ArrayDeque(listOf(
                ApiResult.NetworkError(RuntimeException("still down")),
                ApiResult.Success(healthOk()),
            ))
        }
        val lifecycle = newLifecycle(sessionMgr, healthApi = healthApi, scope = backgroundScope)

        lifecycle.adoptActiveSession(defaultStartParams(), makeSession("sess-1"))
        assertTrue(lifecycle.state.value is SessionState.Active)
        lifecycle.reportOwnedPosition(10.0, 100.0, isPaused = false)

        // Trigger the 10s reporter -> NetworkError -> beginOutageRecovery.
        advanceTimeBy(PlaybackSessionLifecycle.PROGRESS_REPORT_INTERVAL_MS + 100)
        advanceUntilIdle()

        val mid = lifecycle.state.value
        assertTrue(mid is SessionState.Reconnecting, "expected Reconnecting, got $mid")
        val notice = lifecycle.notice.value
        assertNotNull(notice)
        assertEquals(NoticeTone.Warning, notice.tone)
        assertTrue(notice.message.contains("Reconnecting"))

        // First probe at +1s fails (NetworkError); after that we still expect Reconnecting.
        advanceTimeBy(PlaybackSessionLifecycle.OUTAGE_INITIAL_DELAY_MS + 100)
        advanceUntilIdle()
        assertTrue(lifecycle.state.value is SessionState.Reconnecting)
        assertEquals(1, healthApi.callCount)

        // Second probe at +2s (next backoff step) succeeds.
        advanceTimeBy(2 * PlaybackSessionLifecycle.OUTAGE_INITIAL_DELAY_MS + 100)
        advanceUntilIdle()

        assertTrue(lifecycle.state.value is SessionState.Active)
        assertEquals(2, healthApi.callCount)
        assertNull(lifecycle.notice.value)

        lifecycle.stop()
    }

    @Test
    fun `health probe Success transitions back to Active and clears notice`() = runTest {
        val sessionMgr = FakeSessionManager().apply {
            progressResults = ArrayDeque(listOf(
                ApiResult.NetworkError(RuntimeException("offline")),
            ))
        }
        val healthApi = FakeHealthApi().apply {
            results = ArrayDeque(listOf(ApiResult.Success(healthOk())))
        }
        val lifecycle = newLifecycle(sessionMgr, healthApi = healthApi, scope = backgroundScope)
        lifecycle.adoptActiveSession(defaultStartParams(), makeSession("sess-keepalive"))
        assertTrue(lifecycle.state.value is SessionState.Active)
        lifecycle.reportOwnedPosition(5.0, 100.0, isPaused = false)

        advanceTimeBy(PlaybackSessionLifecycle.PROGRESS_REPORT_INTERVAL_MS + 100)
        advanceUntilIdle()
        assertTrue(lifecycle.state.value is SessionState.Reconnecting)

        advanceTimeBy(PlaybackSessionLifecycle.OUTAGE_INITIAL_DELAY_MS + 100)
        advanceUntilIdle()

        val resumed = lifecycle.state.value
        assertTrue(resumed is SessionState.Active)
        assertEquals("sess-keepalive", (resumed as SessionState.Active).session.sessionId)
        assertNull(lifecycle.notice.value)

        lifecycle.stop()
    }

    @Test
    fun `health probe NetworkError repeats with exponential backoff up to 8s cap`() = runTest {
        val sessionMgr = FakeSessionManager().apply {
            progressResults = ArrayDeque(listOf(
                ApiResult.NetworkError(RuntimeException("offline")),
            ))
        }
        // Five NetworkError responses, then Success — confirms the loop hits
        // 1s, 2s, 4s, 8s, 8s (capped) before recovery.
        val healthApi = FakeHealthApi().apply {
            results = ArrayDeque(listOf(
                ApiResult.NetworkError(RuntimeException("d1")),
                ApiResult.NetworkError(RuntimeException("d2")),
                ApiResult.NetworkError(RuntimeException("d3")),
                ApiResult.NetworkError(RuntimeException("d4")),
                ApiResult.Success(healthOk()),
            ))
        }
        val lifecycle = newLifecycle(sessionMgr, healthApi = healthApi, scope = backgroundScope)
        lifecycle.adoptActiveSession(defaultStartParams(), makeSession("sess-1"))
        lifecycle.reportOwnedPosition(0.0, 100.0, isPaused = false)
        advanceTimeBy(PlaybackSessionLifecycle.PROGRESS_REPORT_INTERVAL_MS + 100)
        advanceUntilIdle()
        assertTrue(lifecycle.state.value is SessionState.Reconnecting)

        // After +1s, expect 1 probe.
        advanceTimeBy(1_000 + 50)
        advanceUntilIdle()
        assertEquals(1, healthApi.callCount)

        // +2s -> 2 probes total.
        advanceTimeBy(2_000 + 50)
        advanceUntilIdle()
        assertEquals(2, healthApi.callCount)

        // +4s -> 3.
        advanceTimeBy(4_000 + 50)
        advanceUntilIdle()
        assertEquals(3, healthApi.callCount)

        // +8s -> 4 (cap engaged).
        advanceTimeBy(8_000 + 50)
        advanceUntilIdle()
        assertEquals(4, healthApi.callCount)

        // Another +8s (still capped) hits the success.
        advanceTimeBy(8_000 + 50)
        advanceUntilIdle()
        assertEquals(5, healthApi.callCount)
        assertTrue(lifecycle.state.value is SessionState.Active)

        lifecycle.stop()
    }

    @Test
    fun `outage recovery times out at 90s and transitions to Failed`() = runTest {
        val sessionMgr = FakeSessionManager().apply {
            progressResults = ArrayDeque(listOf(
                ApiResult.NetworkError(RuntimeException("offline")),
            ))
        }
        val healthApi = FakeHealthApi().apply {
            // Always down — probe loop will never succeed before deadline.
            alwaysReturn = ApiResult.NetworkError(RuntimeException("down"))
        }
        val lifecycle = newLifecycle(sessionMgr, healthApi = healthApi, scope = backgroundScope)
        lifecycle.adoptActiveSession(defaultStartParams(), makeSession("sess-1"))
        lifecycle.reportOwnedPosition(0.0, 100.0, isPaused = false)

        advanceTimeBy(PlaybackSessionLifecycle.PROGRESS_REPORT_INTERVAL_MS + 100)
        advanceUntilIdle()
        assertTrue(lifecycle.state.value is SessionState.Reconnecting)

        // Advance past the 90s timeout.
        advanceTimeBy(PlaybackSessionLifecycle.OUTAGE_TIMEOUT_MS + 1_000)
        advanceUntilIdle()

        val terminal = lifecycle.state.value
        assertTrue(terminal is SessionState.Failed, "expected Failed, got $terminal")
        assertEquals(PlaybackSessionLifecycle.OUTAGE_TIMEOUT_MESSAGE, (terminal as SessionState.Failed).message)
        val notice = lifecycle.notice.value
        assertNotNull(notice)
        assertEquals(PlaybackSessionLifecycle.OUTAGE_TIMEOUT_MESSAGE, notice.message)
    }

    @Test
    fun `health gateway error does not mark outage recovery reachable`() = runTest {
        val sessionMgr = FakeSessionManager().apply {
            progressResults = ArrayDeque(
                listOf(ApiResult.NetworkError(RuntimeException("offline"))),
            )
        }
        val healthApi = FakeHealthApi().apply {
            results = ArrayDeque(
                listOf(
                    ApiResult.Error(503, "unavailable", "origin down"),
                    ApiResult.Success(healthOk()),
                ),
            )
        }
        val lifecycle = newLifecycle(sessionMgr, healthApi = healthApi, scope = backgroundScope)
        lifecycle.adoptActiveSession(defaultStartParams(), makeSession("sess-proxy-down"))
        lifecycle.reportOwnedPosition(0.0, 100.0, isPaused = false)

        advanceTimeBy(PlaybackSessionLifecycle.PROGRESS_REPORT_INTERVAL_MS + 100)
        advanceUntilIdle()
        assertTrue(lifecycle.state.value is SessionState.Reconnecting)

        advanceTimeBy(PlaybackSessionLifecycle.OUTAGE_INITIAL_DELAY_MS + 100)
        advanceUntilIdle()
        assertEquals(1, healthApi.callCount)
        assertTrue(
            lifecycle.state.value is SessionState.Reconnecting,
            "gateway failures should keep outage recovery active",
        )

        advanceTimeBy(2 * PlaybackSessionLifecycle.OUTAGE_INITIAL_DELAY_MS + 100)
        advanceUntilIdle()
        assertEquals(2, healthApi.callCount)
        assertTrue(lifecycle.state.value is SessionState.Active)

        lifecycle.stop()
    }

    @Test
    fun `gateway progress error starts outage recovery`() = runTest {
        val sessionMgr = FakeSessionManager().apply {
            progressResults = ArrayDeque(
                listOf(ApiResult.Error(503, "unavailable", "origin down")),
            )
        }
        val healthApi = FakeHealthApi().apply {
            results = ArrayDeque(listOf(ApiResult.Success(healthOk())))
        }
        val lifecycle = newLifecycle(sessionMgr, healthApi = healthApi, scope = backgroundScope)
        lifecycle.adoptActiveSession(defaultStartParams(), makeSession("sess-progress-503"))
        lifecycle.reportOwnedPosition(12.0, 100.0, isPaused = false)

        advanceTimeBy(PlaybackSessionLifecycle.PROGRESS_REPORT_INTERVAL_MS + 100)
        advanceUntilIdle()

        assertTrue(
            lifecycle.state.value is SessionState.Reconnecting,
            "transient gateway progress failures should start outage recovery",
        )

        advanceTimeBy(PlaybackSessionLifecycle.OUTAGE_INITIAL_DELAY_MS + 100)
        advanceUntilIdle()
        assertEquals(1, healthApi.callCount)
        assertTrue(lifecycle.state.value is SessionState.Active)

        lifecycle.stop()
    }

    @Test
    fun `stop clears state to Idle and cancels all jobs`() = runTest {
        val sessionMgr = FakeSessionManager().apply {
            progressDefault = ApiResult.Success(Unit)
        }
        val lifecycle = newLifecycle(sessionMgr, scope = backgroundScope)
        lifecycle.adoptActiveSession(defaultStartParams(), makeSession("sess-1"))
        lifecycle.reportOwnedPosition(15.0, 100.0, isPaused = false)
        advanceTimeBy(PlaybackSessionLifecycle.PROGRESS_REPORT_INTERVAL_MS + 100)
        advanceUntilIdle()

        lifecycle.stop()
        advanceUntilIdle()

        assertEquals(SessionState.Idle, lifecycle.state.value)
        assertNull(lifecycle.notice.value)
        assertEquals(1, sessionMgr.stopCallCount)

        val countAfterStop = sessionMgr.progressCallCount

        // The reporter must NOT fire again after stop().
        advanceTimeBy(2 * PlaybackSessionLifecycle.PROGRESS_REPORT_INTERVAL_MS)
        advanceUntilIdle()
        assertEquals(countAfterStop, sessionMgr.progressCallCount)
    }

    @Test
    fun `repeated 404s during recovery do not fire multiple renewals`() = runTest {
        // The owner's replan is not instantaneous, so the adopted session stays
        // Active while several more 404s arrive for it. Each reporter tick runs
        // against `sess-original` and gets a 404; with the debounce honored the
        // owner is told exactly once.
        val sessionMgr = FakeSessionManager().apply {
            // Five 404s on tap — well more than reporter ticks we'll fire.
            progressResults = ArrayDeque(List(5) {
                ApiResult.Error(404, "playback_session_not_found", "Playback session not found")
            })
        }
        val lifecycle = newLifecycle(sessionMgr, scope = backgroundScope)
        val renewals = mutableListOf<MissingSessionRenewal>()
        backgroundScope.launch { lifecycle.missingSessionEvents.collect { renewals += it } }
        advanceUntilIdle()

        lifecycle.adoptActiveSession(defaultStartParams(), makeSession("sess-original"))
        lifecycle.reportOwnedPosition(7.0, 100.0, isPaused = false)

        // Four reporter ticks, each returning 404 for the still-adopted
        // sess-original. The debounce should collapse them to one renewal.
        repeat(4) {
            advanceTimeBy(PlaybackSessionLifecycle.PROGRESS_REPORT_INTERVAL_MS + 100)
            advanceUntilIdle()
        }

        assertEquals(
            listOf(7.0),
            renewals.map(MissingSessionRenewal::positionSeconds),
            "expected exactly one renewal regardless of how many 404s arrived",
        )

        lifecycle.stop()
    }

    // ------------------------------------------------------------------------
    // Exactly-once teardown (auto-advance)
    // ------------------------------------------------------------------------

    /**
     * The auto-advance regression: the outgoing screen's deferred onCleared stop
     * used to land after the incoming episode had captured its ownership epoch,
     * bumping stopEpoch and getting the incoming adoption rejected. On device
     * that surfaced as "Playback start was superseded." on every episode change.
     */
    @Test
    fun `gated duplicate teardown does not supersede the next item`() = runTest {
        val sessionMgr = FakeSessionManager()
        val lifecycle = newLifecycle(sessionMgr, scope = backgroundScope)
        val gate = PlaybackTeardownGate(lifecycle)

        val epochA = lifecycle.acquireOwnershipEpoch()
        assertTrue(
            lifecycle.adoptActiveSessionIfCurrent(
                params = defaultStartParams(),
                session = makeSession("sess-a"),
                expectedOwnershipEpoch = epochA,
            ),
        )
        // Ordered pre-navigation stop of the outgoing episode.
        gate.stopOrdered(expectedSessionId = "sess-a")

        // The incoming episode captures its epoch, and only THEN does the old
        // screen's deferred onCleared fallback fire.
        val epochB = lifecycle.acquireOwnershipEpoch()
        gate.stopDetached(expectedSessionId = "sess-a")

        val adopted = lifecycle.adoptActiveSessionIfCurrent(
            params = defaultStartParams(),
            session = makeSession("sess-b"),
            expectedOwnershipEpoch = epochB,
        )

        assertTrue(adopted, "next episode must adopt despite the late duplicate teardown")
        assertEquals("sess-b", (lifecycle.state.value as SessionState.Active).session.sessionId)
        assertEquals(1, sessionMgr.stopCallCount, "outgoing session stopped exactly once")
    }

    /** Control: without the gate the same interleaving really does break. */
    @Test
    fun `ungated duplicate teardown supersedes the next item`() = runTest {
        val sessionMgr = FakeSessionManager()
        val lifecycle = newLifecycle(sessionMgr, scope = backgroundScope)

        val epochA = lifecycle.acquireOwnershipEpoch()
        lifecycle.adoptActiveSessionIfCurrent(
            params = defaultStartParams(),
            session = makeSession("sess-a"),
            expectedOwnershipEpoch = epochA,
        )
        lifecycle.stop(expectedSessionId = "sess-a")

        val epochB = lifecycle.acquireOwnershipEpoch()
        lifecycle.stop(expectedSessionId = "sess-a")

        assertFalse(
            lifecycle.adoptActiveSessionIfCurrent(
                params = defaultStartParams(),
                session = makeSession("sess-b"),
                expectedOwnershipEpoch = epochB,
            ),
            "this is the bug the gate exists to prevent",
        )
    }

    /**
     * The claim must never be consumed without leaving an owner. Here the
     * fallback lands while the ordered stop is still in flight — so it correctly
     * skips — and the ordered stop then fails. Teardown has to survive that.
     */
    @Test
    fun `fallback during a suspended ordered stop cannot abandon teardown`() = runTest {
        val firstStopReached = CompletableDeferred<Unit>()
        val releaseFirstStop = CompletableDeferred<Unit>()
        val sessionMgr = object : FakeSessionManager() {
            var attempts = 0
            override suspend fun stopSession(sessionId: String): ApiResult<Unit> {
                attempts++
                if (attempts == 1) {
                    firstStopReached.complete(Unit)
                    releaseFirstStop.await()
                    throw IllegalStateException("stop failed")
                }
                return super.stopSession(sessionId)
            }
        }
        val lifecycle = newLifecycle(sessionMgr, scope = backgroundScope)
        val gate = PlaybackTeardownGate(lifecycle)

        val epochA = lifecycle.acquireOwnershipEpoch()
        lifecycle.adoptActiveSessionIfCurrent(
            params = defaultStartParams(),
            session = makeSession("sess-a"),
            expectedOwnershipEpoch = epochA,
        )

        // runCatching so the failure stays inside this coroutine.
        val ordered = backgroundScope.async {
            runCatching { gate.stopOrdered(expectedSessionId = "sess-a") }
        }
        firstStopReached.await()

        // The dying screen's onCleared fires mid-flight and finds it claimed.
        gate.stopDetached(expectedSessionId = "sess-a")

        releaseFirstStop.complete(Unit)
        assertTrue(ordered.await().isFailure, "the ordered stop really did fail")
        // The handoff job runs on Dispatchers.IO, so the test scheduler cannot
        // see it. Join it through the same API a real next start uses.
        lifecycle.acquireOwnershipEpoch()
        assertEquals(2, sessionMgr.attempts, "the tracked job must retry the abandoned teardown")
        assertEquals(SessionState.Idle, lifecycle.state.value)
    }

    /**
     * The detached routes only schedule the stop, so nothing is positioned to
     * catch it. The lifecycle scope has a SupervisorJob but no
     * CoroutineExceptionHandler, so an escaping throw would be an uncaught
     * coroutine exception — process death rather than a lingering session.
     */
    @Test
    fun `a failing async stop does not escape as an uncaught exception`() = runTest {
        val sessionMgr = object : FakeSessionManager() {
            override suspend fun stopSession(sessionId: String): ApiResult<Unit> =
                throw IllegalStateException("stop failed")
        }
        val lifecycle = newLifecycle(sessionMgr, scope = backgroundScope)

        val epoch = lifecycle.acquireOwnershipEpoch()
        lifecycle.adoptActiveSessionIfCurrent(
            params = defaultStartParams(),
            session = makeSession("sess-a"),
            expectedOwnershipEpoch = epoch,
        )

        lifecycle.stopAsync(expectedSessionId = "sess-a")
        // Joins the tracked job. If the failure escaped, runTest reports it.
        lifecycle.acquireOwnershipEpoch()
    }

    // ------------------------------------------------------------------------
    // Test infrastructure
    // ------------------------------------------------------------------------

    private fun TestScope.newLifecycle(
        sessionMgr: FakeSessionManager,
        healthApi: FakeHealthApi = FakeHealthApi(),
        personalRepo: PersonalDataRepository = RecordingPersonalDataRepository(),
        scope: CoroutineScope = this.backgroundScope,
        playbackSessions: DiagnosticsPlaybackSessionRecorder = DiagnosticsPlaybackSessionRecorder.None,
    ): PlaybackSessionLifecycle = PlaybackSessionLifecycle(
        sessionManager = sessionMgr,
        healthApi = healthApi,
        personalDataRepository = personalRepo,
        scope = scope,
        playbackSessions = playbackSessions,
    )

    /**
     * Reports a sample as the session the lifecycle currently owns.
     *
     * reportPosition requires the caller to name its session — null is "I own
     * none", not "skip the check" — so these tests have to say which session
     * they are reporting for, exactly as the players do.
     */
    private fun PlaybackSessionLifecycle.reportOwnedPosition(
        positionSec: Double,
        durationSec: Double,
        isPaused: Boolean,
    ) = reportPosition(
        positionSec = positionSec,
        durationSec = durationSec,
        isPaused = isPaused,
        expectedSessionId = (state.value as? SessionState.Active)?.session?.sessionId,
    )

    private fun defaultStartParams(startPosition: Double? = null) = StartParams(
        contentId = "content-1",
        fileId = 42,
        capabilities = ClientCodecCapabilities(),
        clientPlaybackContext = ClientPlaybackContext(formFactor = "tv", appVersion = "test"),
        audioTrackIndex = null,
        qualityPreference = null,
        startPosition = startPosition,
    )

    private fun makeSession(id: String) = PlaybackSessionResponse(
        sessionId = id,
        userId = 1,
        profileId = "p1",
        mediaFileId = 42,
        playMethod = PlayMethod.DIRECT,
        position = 0.0,
        isPaused = false,
        streamUrl = "/api/stream/$id",
    )
}

// ----------------------------------------------------------------------------
// Fakes
// ----------------------------------------------------------------------------

private open class FakeSessionManager : PlaybackSessionManager(
    playbackRepository = PlaybackRepository(playbackApi = NoOpPlaybackApi),
    tokenManager = NoOpTokenManager,
) {

    var progressDefault: ApiResult<Unit> = ApiResult.Success(Unit)
    var progressResults: ArrayDeque<ApiResult<Unit>>? = null

    var stopResult: ApiResult<Unit> = ApiResult.Success(Unit)

    var progressCallCount = 0
    var stopCallCount = 0
    var lastStoppedSessionId: String? = null
    var lastProgressSessionId: String? = null
    var lastProgressPosition: Double? = null

    override suspend fun reportProgress(
        sessionId: String,
        position: Double,
        isPaused: Boolean,
    ): ApiResult<Unit> {
        progressCallCount++
        lastProgressSessionId = sessionId
        lastProgressPosition = position
        return progressResults?.takeIf { it.isNotEmpty() }?.removeFirst() ?: progressDefault
    }

    override suspend fun stopSession(sessionId: String): ApiResult<Unit> {
        stopCallCount++
        lastStoppedSessionId = sessionId
        return stopResult
    }
}

private class FakeHealthApi : HealthApi(client = HttpClient()) {
    var results: ArrayDeque<ApiResult<HealthStatus>>? = null
    var alwaysReturn: ApiResult<HealthStatus>? = null
    var callCount = 0

    override suspend fun checkHealth(): ApiResult<HealthStatus> {
        callCount++
        alwaysReturn?.let { return it }
        return results?.takeIf { it.isNotEmpty() }?.removeFirst()
            ?: ApiResult.Success(healthOk())
    }
}

private fun healthOk(): HealthStatus = HealthStatus(status = "ok")

private class RecordingPersonalDataRepository : PersonalDataRepository(
    personalDataApi = NoOpPersonalDataApi,
) {
    val syncCalls = mutableListOf<List<SyncProgressItem>>()
    var syncResult: ApiResult<Unit> = ApiResult.Success(Unit)

    override suspend fun syncProgress(items: List<SyncProgressItem>): ApiResult<Unit> {
        syncCalls.add(items)
        return syncResult
    }
}

// ---- No-op underlying dependencies (overrides bypass them entirely) --------

private val NoOpHttpClient: HttpClient = HttpClient()
private val NoOpPlaybackApi: PlaybackApi = PlaybackApi(NoOpHttpClient)
private val NoOpPersonalDataApi: PersonalDataApi = PersonalDataApi(NoOpHttpClient)

private val NoOpTokenManager: TokenManager = object : TokenManager {
    override suspend fun getAccessToken(): String? = null
    override suspend fun getRefreshToken(): String? = null
    override suspend fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Long) = Unit
    override suspend fun clearTokens() = Unit
    override suspend fun invalidateSession() = Unit
    override val sessionExpired: SharedFlow<Unit> = MutableSharedFlow()
    override suspend fun getProfileId(): String? = null
    override suspend fun setProfileId(profileId: String?) = Unit
    override suspend fun getProfileToken(): String? = null
    override suspend fun setProfileToken(token: String?) = Unit
    override suspend fun getServerUrl(): String = ""
    override suspend fun setServerUrl(url: String) = Unit
    override suspend fun getCurrentServerId(): String? = null
    override suspend fun switchActiveServer(serverId: String?) = Unit
    override suspend fun signOutCurrentServer() = Unit
}
