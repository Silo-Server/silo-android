package org.siloserver.silo.network

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class GenericEventsIdentityTest {
    @Test fun initialReadinessRechecksWhenAnotherServerMutationWinsTheBarrier() = runTest {
        val real = DefaultIdentityTransitionBarrier()
        var guards = 0
        val barrier = object : IdentityTransitionBarrier by real {
            override suspend fun <T : Any> withCurrentGeneration(expectedGeneration: Long, block: suspend () -> T): T? {
                if (guards++ == 0) real.changing(IdentityTransitionKind.SERVER_REMOVE,
                    target = { IdentityTransitionTarget(affectsCurrentIdentity = false) }) { }
                return real.withCurrentGeneration(expectedGeneration, block)
            }
        }
        var starts = 0
        val job = backgroundScope.launch {
            genericEventsForIdentity(barrier) { starts++; awaitCancellation() }
        }
        runCurrent()
        assertEquals(1, starts, "a stale initial generation must not leave the current consumer stopped")
        assertEquals(2, guards)
        job.cancelAndJoin()
    }

    @Test fun startupDuringPausedMutationStartsOnceAfterCompletion() = runTest {
        val barrier = DefaultIdentityTransitionBarrier()
        val release = CompletableDeferred<Unit>()
        val replacing = backgroundScope.launch {
            barrier.changing(IdentityTransitionKind.PROFILE_SWITCH) { release.await() }
        }
        runCurrent()
        var starts = 0
        var active = 0
        val job = backgroundScope.launch {
            genericEventsForIdentity(barrier) {
                starts++; active++
                try { awaitCancellation() } finally { active-- }
            }
        }
        runCurrent()
        assertEquals(0, starts)
        release.complete(Unit)
        runCurrent()
        assertEquals(1, starts, "queued WILL/DID transitions must not duplicate guarded startup")
        assertEquals(1, active)
        replacing.join()
        job.cancelAndJoin()
        assertEquals(0, active)
    }
}
