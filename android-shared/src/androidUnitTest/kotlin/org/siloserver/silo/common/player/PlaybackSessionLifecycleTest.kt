package org.siloserver.silo.common.player

import org.siloserver.silo.common.diagnostics.DiagnosticsPlaybackSessionRecorder
import org.siloserver.silo.model.personal.SyncProgressItem
import org.siloserver.silo.model.playback.ClientCodecCapabilities
import org.siloserver.silo.model.playback.PlayMethod
import org.siloserver.silo.model.playback.PlaybackSessionResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.api.HealthApi
import org.siloserver.silo.network.api.HealthStatus
import org.siloserver.silo.network.api.PersonalDataApi
import org.siloserver.silo.network.api.PlaybackApi
import org.siloserver.silo.network.api.ProfileApi
import org.siloserver.silo.repository.PersonalDataRepository
import org.siloserver.silo.repository.PlaybackRepository
import org.siloserver.silo.repository.ProfileRepository
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
 * three transition paths the wrapper introduces:
 *
 *   - happy-path start -> Active and clean stop
 *   - 404 session_not_found mid-progress -> snapshot + re-start
 *   - NetworkError mid-progress -> Reconnecting + health-probe loop
 *
 * Time is fully virtual via `runTest` + `advanceTimeBy` so we can verify the
 * 1s -> 2s -> 4s -> 8s -> 8s exponential backoff and the 90s outage timeout
 * without sleeping.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSessionLifecycleTest {

    @Test
    fun `start emits Loading then Active on success`() = runTest {
        // We can't rely on StateFlow.collect to capture every intermediate
        // value — StateFlow conflates writes that happen before a
        // collector is ready to consume. Instead, hold sessionManager.startSession
        // suspended at a gate and inspect state.value at each known boundary.
        val gate = kotlinx.coroutines.CompletableDeferred<ApiResult<PlaybackSessionResponse>>()
        val sessionMgr = object : FakeSessionManager() {
            override suspend fun startSession(
                fileId: Int,
                profileId: String,
                capabilities: ClientCodecCapabilities,
                audioTrackIndex: Int?,
                qualityPreference: String?,
                startPosition: Double?,
                disableProgressPersistence: Boolean,
            ): ApiResult<PlaybackSessionResponse> {
                startCallCount++
                return gate.await()
            }
        }
        val recordedSessions = mutableListOf<String>()
        val lifecycle = newLifecycle(
            sessionMgr,
            scope = backgroundScope,
            playbackSessions = DiagnosticsPlaybackSessionRecorder { recordedSessions += it },
        )

        assertEquals(SessionState.Idle, lifecycle.state.value)

        // Launch start() onto the test scheduler. Its first real suspension
        // is sessionManager.startSession, which awaits the gate. After
        // advanceUntilIdle, state must be Loading.
        val startJob = launch { lifecycle.start(defaultStartParams()) }
        advanceUntilIdle()
        assertEquals(SessionState.Loading, lifecycle.state.value)

        // Resume — start() finishes with Active.
        gate.complete(ApiResult.Success(makeSession("sess-1")))
        advanceUntilIdle()
        startJob.join()

        val terminal = lifecycle.state.value
        assertTrue(terminal is SessionState.Active, "expected Active, got $terminal")
        assertEquals("sess-1", (terminal as SessionState.Active).session.sessionId)
        assertEquals(listOf("sess-1"), recordedSessions)

        lifecycle.stop()
    }

    @Test
    fun `a stop during start does not strand the new session`() = runTest {
        // start() runs its API call outside the lifecycle mutex, and for that
        // whole window state is Loading with no adopted id — so stop()'s
        // ownership guard has nothing to compare and tears down anyway. The
        // start then published Active over a screen the user had dismissed, and
        // because that stop never saw this session id, the session stayed alive
        // on the server eating a concurrent-stream slot until it timed out.
        val gate = kotlinx.coroutines.CompletableDeferred<ApiResult<PlaybackSessionResponse>>()
        val sessionMgr = object : FakeSessionManager() {
            override suspend fun startSession(
                fileId: Int,
                profileId: String,
                capabilities: ClientCodecCapabilities,
                audioTrackIndex: Int?,
                qualityPreference: String?,
                startPosition: Double?,
                disableProgressPersistence: Boolean,
            ): ApiResult<PlaybackSessionResponse> {
                startCallCount++
                return gate.await()
            }
        }
        val lifecycle = newLifecycle(sessionMgr, scope = backgroundScope)

        val startJob = launch { lifecycle.start(defaultStartParams()) }
        advanceUntilIdle()
        assertEquals(SessionState.Loading, lifecycle.state.value)

        // The user leaves. Nothing is Active yet, so the caller has no id to pass.
        lifecycle.stop()
        advanceUntilIdle()

        gate.complete(ApiResult.Success(makeSession("sess-late")))
        advanceUntilIdle()
        startJob.join()

        assertEquals(
            SessionState.Idle,
            lifecycle.state.value,
            "a dismissed screen must not be resurrected by its own in-flight start",
        )
        assertEquals(
            "sess-late",
            sessionMgr.lastStoppedSessionId,
            "the late session must be stopped, not left running on the server",
        )
    }

    @Test
    fun `a failed start finishing after stop does not publish stale failure`() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<ApiResult<PlaybackSessionResponse>>()
        val sessionMgr = object : FakeSessionManager() {
            override suspend fun startSession(
                fileId: Int,
                profileId: String,
                capabilities: ClientCodecCapabilities,
                audioTrackIndex: Int?,
                qualityPreference: String?,
                startPosition: Double?,
                disableProgressPersistence: Boolean,
            ): ApiResult<PlaybackSessionResponse> = gate.await()
        }
        val lifecycle = newLifecycle(sessionMgr, scope = backgroundScope)

        val startJob = launch { lifecycle.start(defaultStartParams()) }
        advanceUntilIdle()
        assertEquals(SessionState.Loading, lifecycle.state.value)

        lifecycle.stop()
        gate.complete(
            ApiResult.Error(
                code = 500,
                error = "start_failed",
                message = "Start failed",
            ),
        )
        advanceUntilIdle()
        startJob.join()

        assertEquals(
            SessionState.Idle,
            lifecycle.state.value,
            "a completed teardown must remain terminal for its in-flight start",
        )
    }

    @Test
    fun `start emits Failed when profile id is null`() = runTest {
        val lifecycle = newLifecycle(
            sessionMgr = FakeSessionManager(),
            profileRepo = FakeProfileRepository(activeProfileId = null),
            scope = backgroundScope,
        )

        val terminal = lifecycle.start(defaultStartParams())
        advanceUntilIdle()

        assertTrue(terminal is SessionState.Failed)
        assertTrue((terminal as SessionState.Failed).message.contains("profile", ignoreCase = true))
    }

    @Test
    fun `start emits Failed on session API NetworkError`() = runTest {
        val sessionMgr = FakeSessionManager().apply {
            startResult = ApiResult.NetworkError(RuntimeException("boom"))
        }
        val lifecycle = newLifecycle(sessionMgr, scope = backgroundScope)

        val terminal = lifecycle.start(defaultStartParams())
        advanceUntilIdle()

        assertTrue(terminal is SessionState.Failed)
    }

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

        assertEquals(0, sessionMgr.startCallCount)
        val active = lifecycle.state.value
        assertTrue(active is SessionState.Active)
        assertEquals("sess-adopted", (active as SessionState.Active).session.sessionId)
        assertEquals(listOf("sess-adopted"), recordedSessions)

        lifecycle.reportPosition(positionSec = 33.0, durationSec = 100.0, isPaused = false)
        advanceTimeBy(PlaybackSessionLifecycle.PROGRESS_REPORT_INTERVAL_MS + 100)

        assertEquals(0, sessionMgr.startCallCount)
        assertEquals(1, sessionMgr.progressCallCount)
        assertEquals("sess-adopted", sessionMgr.lastProgressSessionId)
        assertEquals(33.0, sessionMgr.lastProgressPosition)
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

        lifecycle.reportPosition(positionSec = 33.0, durationSec = 100.0, isPaused = false)
        advanceTimeBy(PlaybackSessionLifecycle.PROGRESS_REPORT_INTERVAL_MS + 100)
        lifecycle.stop()
        advanceUntilIdle()

        assertEquals(0, sessionMgr.startCallCount)
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
    fun `reportPosition with 404 triggers session-missing recovery and re-starts`() = runTest {
        val sessionMgr = FakeSessionManager().apply {
            // Two distinct sessions back-to-back: original then renewed.
            startResults = ArrayDeque(listOf(
                ApiResult.Success(makeSession("sess-original")),
                ApiResult.Success(makeSession("sess-renewed")),
            ))
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

        val first = lifecycle.start(defaultStartParams(startPosition = 0.0))
        assertTrue(first is SessionState.Active)
        assertEquals("sess-original", (first as SessionState.Active).session.sessionId)

        // Simulate the player advancing.
        lifecycle.reportPosition(positionSec = 42.5, durationSec = 100.0, isPaused = false)

        // Trigger the 10s reporter; first call returns 404 -> recovery -> re-start.
        advanceTimeBy(PlaybackSessionLifecycle.PROGRESS_REPORT_INTERVAL_MS + 100)
        advanceUntilIdle()

        // Snapshot was synced with forceOverwrite at position 42.5.
        val snapshot = personalRepo.syncCalls.firstOrNull()
            ?: fail("expected syncProgress to be called during recovery")
        assertEquals(1, snapshot.size)
        assertEquals("content-1", snapshot.first().mediaItemId)
        assertEquals(42.5, snapshot.first().position)
        assertTrue(snapshot.first().forceOverwrite)

        // New session is now active.
        val state = lifecycle.state.value
        assertTrue(state is SessionState.Active)
        assertEquals("sess-renewed", (state as SessionState.Active).session.sessionId)
        assertEquals(2, sessionMgr.startCallCount)

        // Last start call resumed at 42.5.
        assertEquals(42.5, sessionMgr.lastStartPosition)

        lifecycle.stop()
    }

    @Test
    fun `reportPosition with NetworkError transitions to Reconnecting and probes health`() = runTest {
        val sessionMgr = FakeSessionManager().apply {
            startResult = ApiResult.Success(makeSession("sess-1"))
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

        val active = lifecycle.start(defaultStartParams())
        assertTrue(active is SessionState.Active)
        lifecycle.reportPosition(10.0, 100.0, isPaused = false)

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
            startResult = ApiResult.Success(makeSession("sess-keepalive"))
            progressResults = ArrayDeque(listOf(
                ApiResult.NetworkError(RuntimeException("offline")),
            ))
        }
        val healthApi = FakeHealthApi().apply {
            results = ArrayDeque(listOf(ApiResult.Success(healthOk())))
        }
        val lifecycle = newLifecycle(sessionMgr, healthApi = healthApi, scope = backgroundScope)
        val active = lifecycle.start(defaultStartParams())
        assertTrue(active is SessionState.Active)
        lifecycle.reportPosition(5.0, 100.0, isPaused = false)

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
            startResult = ApiResult.Success(makeSession("sess-1"))
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
        lifecycle.start(defaultStartParams())
        lifecycle.reportPosition(0.0, 100.0, isPaused = false)
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
            startResult = ApiResult.Success(makeSession("sess-1"))
            progressResults = ArrayDeque(listOf(
                ApiResult.NetworkError(RuntimeException("offline")),
            ))
        }
        val healthApi = FakeHealthApi().apply {
            // Always down — probe loop will never succeed before deadline.
            alwaysReturn = ApiResult.NetworkError(RuntimeException("down"))
        }
        val lifecycle = newLifecycle(sessionMgr, healthApi = healthApi, scope = backgroundScope)
        lifecycle.start(defaultStartParams())
        lifecycle.reportPosition(0.0, 100.0, isPaused = false)

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
            startResult = ApiResult.Success(makeSession("sess-proxy-down"))
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
        lifecycle.start(defaultStartParams())
        lifecycle.reportPosition(0.0, 100.0, isPaused = false)

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
            startResult = ApiResult.Success(makeSession("sess-progress-503"))
            progressResults = ArrayDeque(
                listOf(ApiResult.Error(503, "unavailable", "origin down")),
            )
        }
        val healthApi = FakeHealthApi().apply {
            results = ArrayDeque(listOf(ApiResult.Success(healthOk())))
        }
        val lifecycle = newLifecycle(sessionMgr, healthApi = healthApi, scope = backgroundScope)
        lifecycle.start(defaultStartParams())
        lifecycle.reportPosition(12.0, 100.0, isPaused = false)

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
            startResult = ApiResult.Success(makeSession("sess-1"))
            progressDefault = ApiResult.Success(Unit)
        }
        val lifecycle = newLifecycle(sessionMgr, scope = backgroundScope)
        lifecycle.start(defaultStartParams())
        lifecycle.reportPosition(15.0, 100.0, isPaused = false)
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
        // To exercise the debounce we MUST keep the original session active
        // while several 404s arrive for it. We do that by holding the
        // renewal `startSession` call suspended on a gate — every reporter
        // tick runs against `sess-original` and returns 404. With the
        // debounce honored, only one recovery (one renewal start) fires.
        val renewalGate = kotlinx.coroutines.CompletableDeferred<ApiResult<PlaybackSessionResponse>>()
        val sessionMgr = object : FakeSessionManager() {
            override suspend fun startSession(
                fileId: Int,
                profileId: String,
                capabilities: ClientCodecCapabilities,
                audioTrackIndex: Int?,
                qualityPreference: String?,
                startPosition: Double?,
                disableProgressPersistence: Boolean,
            ): ApiResult<PlaybackSessionResponse> {
                startCallCount++
                lastStartPosition = startPosition
                return when (startCallCount) {
                    1 -> ApiResult.Success(makeSession("sess-original"))
                    2 -> renewalGate.await()  // hold the renewal so reporter keeps polling sess-original
                    else -> ApiResult.Success(makeSession("sess-${startCallCount}"))
                }
            }
        }.apply {
            // Five 404s on tap — well more than reporter ticks we'll fire.
            progressResults = ArrayDeque(List(5) {
                ApiResult.Error(404, "playback_session_not_found", "Playback session not found")
            })
        }
        val lifecycle = newLifecycle(sessionMgr, scope = backgroundScope)
        lifecycle.start(defaultStartParams())
        lifecycle.reportPosition(7.0, 100.0, isPaused = false)

        // Four reporter ticks — every tick reads state.value's session, which
        // is still sess-original because the renewal start() is gated. Each
        // tick returns 404 for sess-original; the debounce should keep us
        // from launching multiple renewal coroutines.
        repeat(4) {
            advanceTimeBy(PlaybackSessionLifecycle.PROGRESS_REPORT_INTERVAL_MS + 100)
            advanceUntilIdle()
        }

        // Exactly two start calls: original + one renewal — *not* one per 404.
        assertEquals(
            2,
            sessionMgr.startCallCount,
            "expected exactly one renewal regardless of how many 404s arrived",
        )

        // Let the renewal finish so the test ends cleanly.
        renewalGate.complete(ApiResult.Success(makeSession("sess-renewed")))
        advanceUntilIdle()

        lifecycle.stop()
    }

    // ------------------------------------------------------------------------
    // Test infrastructure
    // ------------------------------------------------------------------------

    private fun TestScope.newLifecycle(
        sessionMgr: FakeSessionManager,
        profileRepo: ProfileRepository = FakeProfileRepository(activeProfileId = "p1"),
        healthApi: FakeHealthApi = FakeHealthApi(),
        personalRepo: PersonalDataRepository = RecordingPersonalDataRepository(),
        scope: CoroutineScope = this.backgroundScope,
        playbackSessions: DiagnosticsPlaybackSessionRecorder = DiagnosticsPlaybackSessionRecorder.None,
    ): PlaybackSessionLifecycle = PlaybackSessionLifecycle(
        sessionManager = sessionMgr,
        profileRepository = profileRepo,
        healthApi = healthApi,
        personalDataRepository = personalRepo,
        scope = scope,
        playbackSessions = playbackSessions,
    )

    private fun defaultStartParams(startPosition: Double? = null) = StartParams(
        contentId = "content-1",
        fileId = 42,
        capabilities = ClientCodecCapabilities(),
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

    /** If `startResults` is non-empty it takes priority; otherwise `startResult`. */
    var startResult: ApiResult<PlaybackSessionResponse> = ApiResult.Error(500, "x", "x")
    var startResults: ArrayDeque<ApiResult<PlaybackSessionResponse>>? = null

    var progressDefault: ApiResult<Unit> = ApiResult.Success(Unit)
    var progressResults: ArrayDeque<ApiResult<Unit>>? = null

    var stopResult: ApiResult<Unit> = ApiResult.Success(Unit)

    var startCallCount = 0
    var progressCallCount = 0
    var stopCallCount = 0
    var lastStoppedSessionId: String? = null
    var lastStartPosition: Double? = null
    var lastProgressSessionId: String? = null
    var lastProgressPosition: Double? = null

    var lastDisableProgressPersistence: Boolean? = null

    override suspend fun startSession(
        fileId: Int,
        profileId: String,
        capabilities: ClientCodecCapabilities,
        audioTrackIndex: Int?,
        qualityPreference: String?,
        startPosition: Double?,
        disableProgressPersistence: Boolean,
    ): ApiResult<PlaybackSessionResponse> {
        startCallCount++
        lastStartPosition = startPosition
        lastDisableProgressPersistence = disableProgressPersistence
        return startResults?.takeIf { it.isNotEmpty() }?.removeFirst() ?: startResult
    }

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

private class FakeProfileRepository(
    private val activeProfileId: String?,
) : ProfileRepository(
    profileApi = NoOpProfileApi,
    tokenManager = NoOpTokenManager,
) {
    override suspend fun getActiveProfileId(): String? = activeProfileId
}

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
private val NoOpProfileApi: ProfileApi = ProfileApi(NoOpHttpClient)
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
