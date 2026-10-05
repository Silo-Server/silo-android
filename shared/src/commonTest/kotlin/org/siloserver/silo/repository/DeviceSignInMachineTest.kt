package org.siloserver.silo.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.auth.DeviceLoginCancelResponse
import org.siloserver.silo.model.auth.DeviceLoginCapabilityResponse
import org.siloserver.silo.model.auth.DeviceLoginDecisionResponse
import org.siloserver.silo.model.auth.DeviceLoginLookupResponse
import org.siloserver.silo.model.auth.DeviceLoginPollResponse
import org.siloserver.silo.model.auth.DeviceLoginStartResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.DeviceLoginApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceSignInMachineTest {

    private class ScriptedApi(
        var capability: ApiResult<DeviceLoginCapabilityResponse> = ApiResult.Success(
            DeviceLoginCapabilityResponse(cancel = true, openedSignal = true),
        ),
        var start: (Int) -> ApiResult<DeviceLoginStartResponse> = { n -> ApiResult.Success(session("dev-$n")) },
        var poll: (String) -> ApiResult<DeviceLoginPollResponse> = { ApiResult.Success(DeviceLoginPollResponse("pending")) },
        /** How long each poll call takes, so a test can stop the machine mid-call. */
        var pollDurationMs: Long = 0L,
        /** How long each cancel call takes; a cancel is recorded once it has completed. */
        var cancelDurationMs: Long = 0L,
    ) : DeviceLoginApi {
        var starts = 0
        var polls = 0
        val canceled = mutableListOf<String>()

        override suspend fun deviceLoginCapabilityAt(serverUrl: String) = capability
        override suspend fun startDeviceLoginAt(serverUrl: String, deviceName: String?, devicePlatform: String?) =
            start(++starts)
        override suspend fun pollDeviceLoginAt(serverUrl: String, deviceCode: String): ApiResult<DeviceLoginPollResponse> {
            polls++
            if (pollDurationMs > 0) delay(pollDurationMs)
            return poll(deviceCode)
        }
        override suspend fun cancelDeviceLoginAt(serverUrl: String, deviceCode: String): ApiResult<DeviceLoginCancelResponse> {
            if (cancelDurationMs > 0) delay(cancelDurationMs)
            canceled += deviceCode
            return ApiResult.Success(DeviceLoginCancelResponse("canceled"))
        }
        override suspend fun startDeviceLogin(deviceName: String?, devicePlatform: String?) = error("unscoped")
        override suspend fun pollDeviceLogin(deviceCode: String) = error("unscoped")
        override suspend fun lookupDeviceLogin(token: String?, code: String?): ApiResult<DeviceLoginLookupResponse> = error("unused")
        override suspend fun approveDeviceLogin(token: String?, code: String?): ApiResult<DeviceLoginDecisionResponse> = error("unused")
        override suspend fun denyDeviceLogin(token: String?, code: String?): ApiResult<DeviceLoginDecisionResponse> = error("unused")
    }

    private fun TestScope.machine(api: ScriptedApi, config: DeviceSignInConfig = DeviceSignInConfig()) =
        DeviceSignInMachine(
            repository = DeviceLoginRepository(api),
            serverUrl = "https://silo.test",
            deviceName = "Shield",
            devicePlatform = "android-tv",
            clock = DeviceLoginClock { testScheduler.currentTime },
            config = config,
        )

    @Test
    fun showsCodeThenApproves() = runTest {
        var polls = 0
        val api = ScriptedApi(poll = {
            polls++
            ApiResult.Success(
                if (polls < 3) DeviceLoginPollResponse("pending")
                else DeviceLoginPollResponse("approved", accessToken = "at", refreshToken = "rt"),
            )
        })
        val m = machine(api)
        val job = launch { m.run() }
        runCurrent()
        val showing = assertIs<DeviceSignInState.ShowingCode>(m.state.value)
        assertEquals("4821-7730", showing.session.userCode)
        advanceUntilIdle()
        assertIs<DeviceSignInState.Approved>(m.state.value)
        assertTrue(job.isCompleted)
    }

    @Test
    fun expiredCodeIsRenewedInPlace() = runTest {
        val api = ScriptedApi(poll = { code ->
            ApiResult.Success(DeviceLoginPollResponse(if (code == "dev-1") "expired" else "pending"))
        })
        val m = machine(api)
        val job = launch { m.run() }
        advanceTimeBy(10_000)
        val showing = assertIs<DeviceSignInState.ShowingCode>(m.state.value)
        assertEquals("dev-2", showing.session.deviceCode)
        assertTrue(showing.renewed)
        job.cancel()
    }

    @Test
    fun localDeadlinePollsOnceMoreThenWithdrawsAndRenews() = runTest {
        val pollTimes = mutableListOf<Long>()
        val api = ScriptedApi(
            start = { n -> ApiResult.Success(session("dev-$n", expiresIn = 62)) },
            poll = { pollTimes += testScheduler.currentTime; ApiResult.Success(DeviceLoginPollResponse("pending")) },
        )
        val m = machine(api)
        val job = launch { m.run() }
        advanceTimeBy(62_001)
        // The wait before the deadline is clamped to it, and the code is only
        // replaced after that last poll still found it pending.
        assertTrue(62_000L in pollTimes, "polls at the deadline before replacing: $pollTimes")
        assertEquals("dev-2", assertIs<DeviceSignInState.ShowingCode>(m.state.value).session.deviceCode)
        assertEquals(listOf("dev-1"), api.canceled, "the replaced code is withdrawn on the server")
        job.cancel()
    }

    @Test
    fun aPendingPollsExpiresAtMovesTheDeadline() = runTest {
        // The start answer says the code lives 60 s (server clock 00:01:00);
        // a lookup in the last poll interval extends it to 00:05:00.
        val api = ScriptedApi(
            start = { n -> ApiResult.Success(session("dev-$n", expiresIn = 60, expiresAt = "2026-01-01T00:01:00Z")) },
            poll = {
                val extended = testScheduler.currentTime >= 60_000L
                ApiResult.Success(
                    DeviceLoginPollResponse(
                        "pending",
                        opened = extended,
                        expiresAt = if (extended) "2026-01-01T00:05:00Z" else "2026-01-01T00:01:00Z",
                    ),
                )
            },
        )
        val m = machine(api)
        val job = launch { m.run() }
        advanceTimeBy(4 * 60_000L)
        val showing = assertIs<DeviceSignInState.ShowingCode>(m.state.value)
        assertEquals("dev-1", showing.session.deviceCode, "the code someone is approving stays")
        assertTrue(showing.opened)
        assertEquals(emptyList(), api.canceled)

        advanceTimeBy(60_001)
        assertEquals("dev-2", assertIs<DeviceSignInState.ShowingCode>(m.state.value).session.deviceCode)
        assertEquals(listOf("dev-1"), api.canceled)
        job.cancel()
    }

    @Test
    fun aReplacedLiveCodeIsWithdrawnWhileCancelSupportIsUnknown() = runTest {
        // The capability read keeps failing: the same rule as abandon applies,
        // so the code the TV moved past is still withdrawn.
        val api = ScriptedApi(
            capability = ApiResult.NetworkError(RuntimeException("offline")),
            start = { n -> ApiResult.Success(session("dev-$n", expiresIn = 10)) },
        )
        val m = machine(api)
        val job = launch { m.run() }
        advanceTimeBy(10_001)
        assertEquals("dev-2", assertIs<DeviceSignInState.ShowingCode>(m.state.value).session.deviceCode)
        assertEquals(listOf("dev-1"), api.canceled)
        job.cancel()
    }

    @Test
    fun aReplacedLiveCodeIsNotWithdrawnWhenTheServerHasNoCancel() = runTest {
        val api = ScriptedApi(
            capability = ApiResult.Success(DeviceLoginCapabilityResponse(cancel = false)),
            start = { n -> ApiResult.Success(session("dev-$n", expiresIn = 10)) },
        )
        val m = machine(api)
        val job = launch { m.run() }
        advanceTimeBy(10_001)
        assertEquals("dev-2", assertIs<DeviceSignInState.ShowingCode>(m.state.value).session.deviceCode)
        assertEquals(emptyList(), api.canceled)
        job.cancel()
    }

    @Test
    fun aFailedCapabilityReadIsRetriedWhenTheCodeIsReplaced() = runTest {
        val api = ScriptedApi(
            capability = ApiResult.NetworkError(RuntimeException("offline")),
            start = { n -> ApiResult.Success(session("dev-$n", expiresIn = 10)) },
        )
        val m = machine(api)
        val job = launch { m.run() }
        runCurrent()
        api.capability = ApiResult.Success(DeviceLoginCapabilityResponse(cancel = true))
        advanceTimeBy(10_001)
        assertEquals(listOf("dev-1"), api.canceled)
        job.cancel()
    }

    @Test
    fun pausesAfterAnHourOfRenewals() = runTest {
        val api = ScriptedApi(start = { n -> ApiResult.Success(session("dev-$n", expiresIn = 900)) })
        val m = machine(api)
        val job = launch { m.run() }
        advanceTimeBy(61 * 60_000L)
        assertEquals(DeviceSignInState.Paused, m.state.value)
        assertTrue(job.isCompleted)
        assertEquals(4, api.starts, "15-minute codes renew three times in an hour")

        m.restart()
        val again = launch { m.run() }
        runCurrent()
        assertIs<DeviceSignInState.ShowingCode>(m.state.value)
        again.cancel()
    }

    @Test
    fun networkFailuresBackOffAndReportUnreachableThenRecover() = runTest {
        var offline = true
        val pollTimes = mutableListOf<Long>()
        val api = ScriptedApi(poll = {
            pollTimes += testScheduler.currentTime
            if (offline) ApiResult.NetworkError(RuntimeException("offline")) else ApiResult.Success(DeviceLoginPollResponse("pending"))
        })
        val m = machine(api)
        val job = launch { m.run() }
        advanceTimeBy(29_000)
        assertIs<DeviceSignInState.ShowingCode>(m.state.value)
        advanceTimeBy(40_000)
        val unreachable = assertIs<DeviceSignInState.Unreachable>(m.state.value)
        assertEquals("dev-1", unreachable.session?.deviceCode)
        val gaps = pollTimes.zipWithNext { a, b -> b - a }
        assertEquals(listOf(5_000L, 10_000L, 20_000L, 30_000L), gaps.take(4))

        offline = false
        advanceTimeBy(31_000)
        assertIs<DeviceSignInState.ShowingCode>(m.state.value)
        job.cancel()
    }

    @Test
    fun startFailuresReportUnreachableAfterTenSeconds() = runTest {
        val api = ScriptedApi(start = { ApiResult.Error(503, "", "") })
        val m = machine(api)
        val job = launch { m.run() }
        advanceTimeBy(5_000)
        assertEquals(DeviceSignInState.GettingCode, m.state.value)
        advanceTimeBy(11_000)
        assertEquals(DeviceSignInState.Unreachable(null), m.state.value)
        job.cancel()
    }

    @Test
    fun persistentRateLimitIsReported() = runTest {
        val api = ScriptedApi(poll = { ApiResult.Error(429, "rate_limited", "") })
        val m = machine(api)
        val job = launch { m.run() }
        advanceTimeBy(59_000)
        assertIs<DeviceSignInState.ShowingCode>(m.state.value)
        advanceTimeBy(40_000)
        assertIs<DeviceSignInState.TooManyRequests>(m.state.value)
        job.cancel()
    }

    @Test
    fun openedIsReportedWithoutMovingTheDeadlineOnItsOwn() = runTest {
        val api = ScriptedApi(
            start = { n -> ApiResult.Success(session("dev-$n", expiresIn = 60)) },
            poll = { ApiResult.Success(DeviceLoginPollResponse("pending", opened = true)) },
        )
        val m = machine(api)
        val job = launch { m.run() }
        runCurrent()
        assertTrue(assertIs<DeviceSignInState.ShowingCode>(m.state.value).opened)
        // Only the server's expires_at extends a code; opened alone doesn't.
        advanceTimeBy(61_000)
        assertEquals("dev-2", assertIs<DeviceSignInState.ShowingCode>(m.state.value).session.deviceCode)
        job.cancel()
    }

    @Test
    fun deniedAndMissingTokensSettle() = runTest {
        val denied = machine(ScriptedApi(poll = { ApiResult.Success(DeviceLoginPollResponse("denied")) }))
        denied.run()
        assertEquals(DeviceSignInState.Denied, denied.state.value)

        val missing = machine(ScriptedApi(poll = { ApiResult.Success(DeviceLoginPollResponse("approved")) }))
        missing.run()
        assertEquals(DeviceSignInState.Failed, missing.state.value)
    }

    @Test
    fun serverWithoutDeviceSignInFallsBackToPassword() = runTest {
        val unconfigured = machine(
            ScriptedApi(capability = ApiResult.Success(DeviceLoginCapabilityResponse(deviceLoginAvailable = false))),
        )
        unconfigured.run()
        assertEquals(DeviceSignInState.NoDeviceSignIn, unconfigured.state.value)

        val noRoute = machine(
            ScriptedApi(
                capability = ApiResult.Error(404, "", ""),
                start = { ApiResult.Error(404, "not_found", "") },
            ),
        )
        noRoute.run()
        assertEquals(DeviceSignInState.NoDeviceSignIn, noRoute.state.value)

        val retired = machine(ScriptedApi(start = { ApiResult.Error(410, "gone", "") }))
        retired.run()
        assertEquals(DeviceSignInState.UpdateRequired, retired.state.value)
    }

    @Test
    fun stoppingKeepsTheCodeAndResumingPollsItImmediately() = runTest {
        val api = ScriptedApi()
        val m = machine(api)
        val first = launch { m.run() }
        advanceTimeBy(12_000)
        first.cancel()
        val pollsWhenStopped = api.polls
        advanceTimeBy(60_000)
        assertEquals(pollsWhenStopped, api.polls, "no polling while stopped")

        val resumed = launch { m.run() }
        runCurrent()
        assertEquals(pollsWhenStopped + 1, api.polls, "polls at once on resume")
        assertEquals(1, api.starts, "a live code is kept")
        resumed.cancel()
    }

    @Test
    fun requestStopLetsAnInFlightPollFinishSoItsTokensAreKept() = runTest {
        val api = ScriptedApi(
            poll = { ApiResult.Success(DeviceLoginPollResponse("approved", accessToken = "at", refreshToken = "rt")) },
            pollDurationMs = 1_000L,
        )
        val m = machine(api)
        val job = launch { m.run() }
        runCurrent()
        assertEquals(1, api.polls, "the poll is in flight")
        m.requestStop()
        advanceUntilIdle()
        assertIs<DeviceSignInState.Approved>(m.state.value)
        assertTrue(job.isCompleted)
    }

    @Test
    fun requestStopEndsTheWaitBetweenPolls() = runTest {
        val api = ScriptedApi()
        val m = machine(api)
        val job = launch { m.run() }
        runCurrent()
        m.requestStop()
        runCurrent()
        assertTrue(job.isCompleted, "stops without waiting out the interval")
        assertEquals(1, api.polls)
        advanceTimeBy(60_000)
        assertEquals(1, api.polls)

        val resumed = launch { m.run() }
        runCurrent()
        assertEquals(2, api.polls, "polls at once on resume")
        assertEquals(1, api.starts)
        resumed.cancel()
    }

    @Test
    fun abandonCancelsOnlyWhenTheServerSupportsIt() = runTest {
        val api = ScriptedApi()
        val m = machine(api)
        val job = launch { m.run() }
        runCurrent()
        job.cancel()
        m.abandon(this)
        advanceUntilIdle()
        assertEquals(listOf("dev-1"), api.canceled)

        val old = ScriptedApi(capability = ApiResult.Success(DeviceLoginCapabilityResponse(cancel = false)))
        val m2 = machine(old)
        val job2 = launch { m2.run() }
        runCurrent()
        job2.cancel()
        m2.abandon(this)
        advanceUntilIdle()
        assertEquals(emptyList(), old.canceled)
    }

    @Test
    fun abandonWithdrawsTheCodeWhenTheCapabilityCouldNotBeRead() = runTest {
        val api = ScriptedApi(capability = ApiResult.NetworkError(RuntimeException("offline")))
        val m = machine(api)
        val job = launch { m.run() }
        runCurrent()
        assertIs<DeviceSignInState.ShowingCode>(m.state.value)
        job.cancel()
        m.abandon(this)
        advanceUntilIdle()
        assertEquals(listOf("dev-1"), api.canceled, "unknown support still gets the cancel; an old server answers 404")
    }

    @Test
    fun cancellingTheRunDuringARenewalsWithdrawStillWithdrawsTheCode() = runTest {
        val api = ScriptedApi(
            start = { n -> ApiResult.Success(session("dev-$n", expiresIn = 10)) },
            cancelDurationMs = 1_000L,
        )
        val m = machine(api)
        val job = launch { m.run() }
        // The poll at the deadline finds dev-1 pending: the withdraw starts.
        advanceTimeBy(10_500)
        assertEquals(emptyList(), api.canceled, "the withdraw is in flight")
        job.cancel()
        m.abandon(this)
        advanceUntilIdle()
        assertTrue("dev-1" in api.canceled, "the withdraw in flight survives cancelling the run: ${api.canceled}")
        assertEquals(1, api.starts, "no code after the one withdrawn")
    }

    @Test
    fun aRunSupersededDuringASlowPollDropsItsLateAnswer() = runTest {
        for (late in listOf("pending", "expired")) {
            val api = ScriptedApi(
                poll = { code -> ApiResult.Success(DeviceLoginPollResponse(if (code == "dev-1") late else "pending")) },
                // Answers at 12 s, between the new code's polls at 10 s and 15 s.
                pollDurationMs = 12_000L,
            )
            val m = machine(api)
            // Never cancelled: it stands for a run left blocked in its call.
            val stale = launch { m.run() }
            runCurrent()
            assertEquals(1, api.polls, "dev-1's poll is in flight")

            m.requestStop()
            m.abandon(this)
            m.restart()
            api.pollDurationMs = 0L
            val fresh = launch { m.run() }
            runCurrent()
            assertEquals("dev-2", assertIs<DeviceSignInState.ShowingCode>(m.state.value).session.deviceCode)

            advanceTimeBy(12_001)
            assertTrue(stale.isCompleted, "the superseded run returned ($late)")
            assertEquals(
                "dev-2",
                assertIs<DeviceSignInState.ShowingCode>(m.state.value).session.deviceCode,
                "the withdrawn code never comes back ($late)",
            )
            advanceTimeBy(10_000)
            assertEquals("dev-2", assertIs<DeviceSignInState.ShowingCode>(m.state.value).session.deviceCode)
            assertEquals(2, api.starts, "the new code is kept, not replaced ($late)")
            assertEquals(listOf("dev-1"), api.canceled)
            fresh.cancel()
        }
    }

    private companion object {
        fun session(deviceCode: String, expiresIn: Int = 900, expiresAt: String = "2099-01-01T00:00:00Z") = DeviceLoginStartResponse(
            deviceCode = deviceCode,
            userCode = "4821-7730",
            matchCode = "warm pony",
            verificationUri = "https://silo.test/activate",
            verificationUriComplete = "https://silo.test/activate?code=48217730",
            expiresAt = expiresAt,
            expiresIn = expiresIn,
            interval = 5,
            deviceName = "Shield",
            devicePlatform = "android-tv",
        )
    }
}
