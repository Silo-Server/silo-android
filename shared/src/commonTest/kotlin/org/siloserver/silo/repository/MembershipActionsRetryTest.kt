package org.siloserver.silo.repository

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.siloserver.silo.network.*
import org.siloserver.silo.repository.port.MembershipPort
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class MembershipActionsRetryTest {
    @Test fun unconfirmedChangeRetriesThreeTimesThenReportsFailureOnce() = runTest {
        val port = FakePort(confirmOnRetry = null)
        val actions = MembershipActions(port, DefaultIdentityTransitionBarrier())
        val failures = mutableListOf<MembershipActions.Key>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { actions.failures.collect { failures += it } }
        val intent = actions.begin("item", MembershipPort.Kind.WATCHLIST, true)
        actions.perform(intent)
        assertEquals(1, port.sends); assertEquals(3, port.retries)
        assertFalse(actions.confirmed(intent))
        assertEquals(listOf(intent.key), failures)
    }

    @Test fun retryThatConfirmsStopsWithoutReportingAndSurvivesCallerCancellation() = runTest {
        val port = FakePort(confirmOnRetry = 2)
        val actions = MembershipActions(port, DefaultIdentityTransitionBarrier())
        val failures = mutableListOf<MembershipActions.Key>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { actions.failures.collect { failures += it } }
        val intent = actions.begin("item", MembershipPort.Kind.FAVORITE, true)
        val performing = async { actions.perform(intent) }
        runCurrent(); performing.cancel() // The screen closes while the first retry waits.
        advanceUntilIdle()
        assertEquals(2, port.retries)
        assertTrue(actions.confirmed(intent))
        assertTrue(failures.isEmpty())
    }

    private class FakePort(private val confirmOnRetry: Int?) : MembershipPort {
        val authority = DurableLoginAuthority("login", AuthScopeSnapshot("s", "p", "https://example.invalid", null))
        var sends = 0; var retries = 0
        private val unconfirmed = MembershipPort.Completion(MembershipPort.Disposition.NEEDS_RECONCILIATION, false)
        override suspend fun captureAuthority() = authority
        override suspend fun record(authority: DurableLoginAuthority, itemId: String, kind: MembershipPort.Kind, present: Boolean) =
            MembershipPort.Command(1, authority, itemId, kind, present)
        override suspend fun send(command: MembershipPort.Command) = unconfirmed.also { sends++ }
        override suspend fun retry(command: MembershipPort.Command) =
            if (++retries == confirmOnRetry) MembershipPort.Completion(MembershipPort.Disposition.ACKNOWLEDGED, true) else unconfirmed
        override suspend fun reconcile(command: MembershipPort.Command) = error("Recovery must use retry")
        override suspend fun read(authority: DurableLoginAuthority, itemId: String, kind: MembershipPort.Kind): ApiResult<Boolean> = ApiResult.Success(false)
        override suspend fun pending(authority: DurableLoginAuthority, limit: Int) = emptyList<MembershipPort.Command>()
        override suspend fun hasLegacyQuarantine() = false
        override suspend fun dispatch(limit: Int) = emptyList<MembershipPort.Completion>()
        override suspend fun readyCount() = 0
        override suspend fun projection(authority: DurableLoginAuthority, itemId: String, kind: MembershipPort.Kind): MembershipPort.Projection? = null
    }
}
