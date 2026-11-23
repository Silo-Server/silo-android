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
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { actions.unconfirmedAttempts.collect { failures += it.key } }
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
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { actions.unconfirmedAttempts.collect { failures += it.key } }
        val intent = actions.begin("item", MembershipPort.Kind.FAVORITE, true)
        val performing = async { actions.perform(intent) }
        runCurrent(); performing.cancel() // The screen closes while the first retry waits.
        advanceUntilIdle()
        assertEquals(2, port.retries)
        assertTrue(actions.confirmed(intent))
        assertTrue(failures.isEmpty())
    }

    @Test fun aTapOnARestoredChangeStillWaitingForItsRetryTakesItOver() = runTest {
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
            override suspend fun restore(command: MembershipPort.Command) = retry(command)
            override suspend fun retry(command: MembershipPort.Command): MembershipPort.Completion {
                val count = (retries[command.itemId] ?: 0) + 1
                retries[command.itemId] = count
                if (command.itemId == "a") { gate.await(); return MembershipPort.Completion(MembershipPort.Disposition.NEEDS_RECONCILIATION, false) }
                // The row is gone after the first confirmation, so a second attempt can't claim it.
                return MembershipPort.Completion(if (count == 1) MembershipPort.Disposition.ACKNOWLEDGED else MembershipPort.Disposition.NOT_CLAIMED, count == 1)
            }
        }
        val actions = MembershipActions(port, DefaultIdentityTransitionBarrier())
        val restoring = launch { actions.restorePending() }; runCurrent() // a's restore retry hangs; b waits its turn.
        val intent = actions.begin("b", MembershipPort.Kind.FAVORITE, true) // The viewer repeats the restored choice.
        actions.perform(intent)
        assertTrue(actions.confirmed(intent), "b must not wait behind a's retry")
        gate.complete(Unit); restoring.join()
        assertEquals(1, port.retries["b"]) // The restore pass doesn't retry it again.
    }

    @Test fun cancelledRestoreReleasesWitnessesItAlreadyClaimedAndRetriesThemLater() = runTest {
        var blockB = true
        val port = object : MembershipPort by FakePort(confirmOnRetry = 1) {
            val retried = mutableListOf<String>()
            override suspend fun restore(command: MembershipPort.Command): MembershipPort.Completion {
                retried += command.itemId
                return MembershipPort.Completion(MembershipPort.Disposition.ACKNOWLEDGED, true)
            }
            val authority = DurableLoginAuthority("login", AuthScopeSnapshot("s", "p", "https://example.invalid", null))
            val commands = listOf("a", "b").mapIndexed { index, item ->
                MembershipPort.Command(index + 1L, authority, item, MembershipPort.Kind.FAVORITE, true)
            }
            override suspend fun captureAuthority() = authority
            override suspend fun pending(authority: DurableLoginAuthority, limit: Int) = commands
            override suspend fun projection(authority: DurableLoginAuthority, itemId: String, kind: MembershipPort.Kind): MembershipPort.Projection {
                if (itemId == "b" && blockB) kotlinx.coroutines.awaitCancellation() // The host leaves composition mid-scan.
                return MembershipPort.Projection(commands.single { it.itemId == itemId }.id, true, MembershipPort.Disposition.NEEDS_RECONCILIATION)
            }
        }
        val actions = MembershipActions(port, DefaultIdentityTransitionBarrier())
        val restoring = launch { actions.restorePending() }; runCurrent()
        restoring.cancel(); restoring.join()
        assertEquals(false, actions.actions.value[MembershipActions.Key("a", MembershipPort.Kind.FAVORITE)]?.busy)
        assertTrue(port.retried.isEmpty()) // The scan was cancelled before any retry ran.
        blockB = false
        actions.restorePending() // The host returns in the same process and identity.
        assertEquals(listOf("a", "b"), port.retried)
        actions.restorePending()
        assertEquals(listOf("a", "b"), port.retried) // Each restored command gets one retry, not one per pass.
    }

    @Test fun repeatingAChoiceDuringAStartupRetryWaitsForItAndTakesOverWhenItFails() = runTest {
      for (restored in listOf(MembershipPort.Disposition.NEEDS_RECONCILIATION, MembershipPort.Disposition.PAUSED)) {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val port = object : MembershipPort by FakePort(confirmOnRetry = null) {
            val authority = DurableLoginAuthority("login", AuthScopeSnapshot("s", "p", "https://example.invalid", null))
            val command = MembershipPort.Command(1L, authority, "item", MembershipPort.Kind.FAVORITE, true)
            var retries = 0
            override suspend fun captureAuthority() = authority
            override suspend fun pending(authority: DurableLoginAuthority, limit: Int) = listOf(command)
            override suspend fun projection(authority: DurableLoginAuthority, itemId: String, kind: MembershipPort.Kind) =
                MembershipPort.Projection(command.id, true, restored)
            override suspend fun restore(command: MembershipPort.Command): MembershipPort.Completion {
                gate.await(); return MembershipPort.Completion(MembershipPort.Disposition.NEEDS_RECONCILIATION, false)
            }
            override suspend fun retry(command: MembershipPort.Command) =
                MembershipPort.Completion(MembershipPort.Disposition.NEEDS_RECONCILIATION, false).also { retries++ }
        }
        val actions = MembershipActions(port, DefaultIdentityTransitionBarrier())
        val failures = mutableListOf<MembershipActions.Key>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { actions.unconfirmedAttempts.collect { failures += it.key } }
        val restoring = launch { actions.restorePending() }; runCurrent()
        val intent = actions.begin("item", MembershipPort.Kind.FAVORITE, true) // Joins the startup retry.
        val performing = launch { actions.perform(intent) }; runCurrent()
        assertFalse(performing.isCompleted, "restored as $restored: the tap waits for the retry it joined")
        gate.complete(Unit); restoring.join(); performing.join()
        assertEquals(4, port.retries, "restored as $restored: the tap's own retry and its three automatic ones")
        assertEquals(listOf(intent.key), failures, "restored as $restored")
      }
    }

    @Test fun aServerThatNeverAnswersGetsItsNoticeWithinTheAttemptBudget() = runTest {
      for (sendHangs in listOf(true, false)) {
        val port = object : MembershipPort by FakePort(confirmOnRetry = null) {
            override suspend fun send(command: MembershipPort.Command): MembershipPort.Completion =
                if (sendHangs) kotlinx.coroutines.awaitCancellation()
                else MembershipPort.Completion(MembershipPort.Disposition.NEEDS_RECONCILIATION, false)
            override suspend fun retry(command: MembershipPort.Command): MembershipPort.Completion = kotlinx.coroutines.awaitCancellation()
        }
        val actions = budgeted(port)
        val failures = mutableListOf<MembershipActions.Key>()
        val collecting = launch(UnconfinedTestDispatcher(testScheduler)) { actions.unconfirmedAttempts.collect { failures += it.key } }
        val intent = actions.begin("item", MembershipPort.Kind.WATCHLIST, true)
        val performing = launch { actions.perform(intent) }
        advanceTimeBy(39_999); runCurrent()
        assertFalse(performing.isCompleted, "send hangs: $sendHangs")
        advanceTimeBy(2); runCurrent()
        assertTrue(performing.isCompleted, "send hangs: $sendHangs")
        assertEquals(listOf(intent.key), failures, "send hangs: $sendHangs")
        collecting.cancel()
      }
    }

    @Test fun aTapJoiningAStartupRetryThatNeverAnswersGetsItsNoticeWithinTheBudget() = runTest {
        val port = restoringPort(restore = { kotlinx.coroutines.awaitCancellation() })
        val actions = budgeted(port)
        val failures = mutableListOf<MembershipActions.Key>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { actions.unconfirmedAttempts.collect { failures += it.key } }
        val restoring = launch { actions.restorePending() }
        advanceTimeBy(10_000); runCurrent()
        val intent = actions.begin("item", MembershipPort.Kind.FAVORITE, true)
        val performing = launch { actions.perform(intent) }
        advanceTimeBy(40_001); runCurrent()
        assertTrue(performing.isCompleted, "the 40 s count from the tap, not from the end of the startup retry")
        assertEquals(listOf(intent.key), failures)
        restoring.join()
    }

    @Test fun aTapWaitingOnAStartupRetryIsStillSentWhenItsCallerIsCancelled() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val port = restoringPort(restore = { gate.await(); unconfirmed })
        val actions = MembershipActions(port, DefaultIdentityTransitionBarrier())
        val failures = mutableListOf<MembershipActions.Key>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { actions.unconfirmedAttempts.collect { failures += it.key } }
        val restoring = launch { actions.restorePending() }; runCurrent()
        val intent = actions.begin("item", MembershipPort.Kind.FAVORITE, true)
        val performing = launch { actions.perform(intent) }; runCurrent()
        performing.cancel() // The viewer leaves the screen while the tap waits.
        gate.complete(Unit); restoring.join(); advanceUntilIdle()
        assertEquals(3, port.retries, "the tap's three automatic retries still run")
        assertEquals(listOf(intent.key), failures)
    }

    @Test fun twoTapsDuringOneStartupRetryTakeItOverOnce() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val port = restoringPort(restore = { gate.await(); unconfirmed })
        val actions = MembershipActions(port, DefaultIdentityTransitionBarrier())
        val failures = mutableListOf<MembershipActions.Key>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { actions.unconfirmedAttempts.collect { failures += it.key } }
        val restoring = launch { actions.restorePending() }; runCurrent()
        val taps = List(2) { launch { actions.perform(actions.begin("item", MembershipPort.Kind.FAVORITE, true)) } }; runCurrent()
        gate.complete(Unit); restoring.join(); taps.forEach { it.join() }
        assertEquals(4, port.retries, "one takeover: its retry and three automatic ones")
        assertEquals(1, failures.size)
    }

    private val unconfirmed = MembershipPort.Completion(MembershipPort.Disposition.NEEDS_RECONCILIATION, false)

    /** One restored, unconfirmed favorite whose startup retry runs [restore]; viewer retries never confirm. */
    private fun restoringPort(restore: suspend () -> MembershipPort.Completion) = object : MembershipPort by FakePort(confirmOnRetry = null) {
        val authority = DurableLoginAuthority("login", AuthScopeSnapshot("s", "p", "https://example.invalid", null))
        val command = MembershipPort.Command(1L, authority, "item", MembershipPort.Kind.FAVORITE, true)
        var retries = 0
        override suspend fun captureAuthority() = authority
        override suspend fun pending(authority: DurableLoginAuthority, limit: Int) = listOf(command)
        override suspend fun projection(authority: DurableLoginAuthority, itemId: String, kind: MembershipPort.Kind) =
            MembershipPort.Projection(command.id, true, MembershipPort.Disposition.NEEDS_RECONCILIATION)
        override suspend fun restore(command: MembershipPort.Command) = restore()
        override suspend fun retry(command: MembershipPort.Command) = unconfirmed.also { retries++ }
    }

    private fun TestScope.budgeted(port: MembershipPort) =
        MembershipActions(port, DefaultIdentityTransitionBarrier(), testScheduler.timeSource, StandardTestDispatcher(testScheduler))

    @Test fun restoreCancelledDuringItsRetryRetriesAgainLater() = runTest {
        var hang = true
        val port = object : MembershipPort by FakePort(confirmOnRetry = null) {
            val authority = DurableLoginAuthority("login", AuthScopeSnapshot("s", "p", "https://example.invalid", null))
            val command = MembershipPort.Command(1L, authority, "item", MembershipPort.Kind.FAVORITE, true)
            var attempts = 0
            override suspend fun captureAuthority() = authority
            override suspend fun pending(authority: DurableLoginAuthority, limit: Int) = listOf(command)
            override suspend fun projection(authority: DurableLoginAuthority, itemId: String, kind: MembershipPort.Kind) =
                MembershipPort.Projection(command.id, true, MembershipPort.Disposition.NEEDS_RECONCILIATION)
            override suspend fun restore(command: MembershipPort.Command): MembershipPort.Completion {
                attempts++
                if (hang) kotlinx.coroutines.awaitCancellation() // The host leaves composition mid-retry.
                return MembershipPort.Completion(MembershipPort.Disposition.ACKNOWLEDGED, true)
            }
        }
        val actions = MembershipActions(port, DefaultIdentityTransitionBarrier())
        val restoring = launch { actions.restorePending() }; runCurrent()
        restoring.cancel(); restoring.join()
        hang = false
        actions.restorePending() // The host returns in the same process and identity.
        assertEquals(2, port.attempts)
        assertTrue(actions.actions.value.values.single().confirmed)
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
        override suspend fun restore(command: MembershipPort.Command) = retry(command)
        override suspend fun read(authority: DurableLoginAuthority, itemId: String, kind: MembershipPort.Kind): ApiResult<Boolean> = ApiResult.Success(false)
        override suspend fun pending(authority: DurableLoginAuthority, limit: Int) = emptyList<MembershipPort.Command>()
        override suspend fun hasLegacyQuarantine() = false
        override suspend fun dispatch(limit: Int) = emptyList<MembershipPort.Completion>()
        override suspend fun readyCount() = 0
        override suspend fun projection(authority: DurableLoginAuthority, itemId: String, kind: MembershipPort.Kind): MembershipPort.Projection? = null
    }
}
