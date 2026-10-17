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

    @Test fun startupRetryOwnsARestoredChangeUntilItsRetryFinishes() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val port = object : MembershipPort by FakePort(confirmOnRetry = null) {
            val authority = DurableLoginAuthority("login", AuthScopeSnapshot("s", "p", "https://example.invalid", null))
            val commands = listOf("a", "b").mapIndexed { index, item ->
                MembershipPort.Command(index + 1L, authority, item, MembershipPort.Kind.FAVORITE, true)
            }
            val retries = mutableMapOf<String, Int>()
            override suspend fun captureAuthority() = authority
            override suspend fun pending(authority: DurableLoginAuthority, limit: Int) = commands
            override suspend fun projection(authority: DurableLoginAuthority, itemId: String, kind: MembershipPort.Kind) =
                commands.single { it.itemId == itemId }.let {
                    MembershipPort.Projection(it.id, true, MembershipPort.Disposition.NEEDS_RECONCILIATION)
                }
            override suspend fun retry(command: MembershipPort.Command): MembershipPort.Completion {
                val count = (retries[command.itemId] ?: 0) + 1
                retries[command.itemId] = count
                if (command.itemId == "a") { gate.await(); return MembershipPort.Completion(MembershipPort.Disposition.NEEDS_RECONCILIATION, false) }
                // The row is gone after the first confirmation, so a second attempt can't claim it.
                return MembershipPort.Completion(if (count == 1) MembershipPort.Disposition.ACKNOWLEDGED else MembershipPort.Disposition.NOT_CLAIMED, count == 1)
            }
        }
        val actions = MembershipActions(port, DefaultIdentityTransitionBarrier())
        val restoring = launch { actions.restorePending() }; runCurrent()
        val intent = actions.begin("b", MembershipPort.Kind.FAVORITE, true) // The viewer repeats the restored choice.
        val performing = launch { actions.perform(intent) }; runCurrent()
        gate.complete(Unit); advanceUntilIdle(); restoring.join(); performing.join()
        assertEquals(1, port.retries["b"])
        assertTrue(actions.confirmed(intent))
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
