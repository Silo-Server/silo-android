package org.siloserver.silo.repository

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.siloserver.silo.network.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class HomeGenericEventsRecoveryTest {
    @Test fun retriesAreSerialAndDoNotResetOnConnectedOrCatalogEvents() = runTest {
        val starts = mutableListOf<Long>()
        var collectors = 0
        var peak = 0
        val client = object : HomeRealtimeClient {
            override fun connect() = flow {
                starts += testScheduler.currentTime
                collectors++
                peak = maxOf(peak, collectors)
                try {
                    emit(HomeRealtimeEvent.Connected)
                    emit(HomeRealtimeEvent.CatalogChanged)
                    emit(HomeRealtimeEvent.Closed())
                } finally { collectors-- }
            }
        }
        val coordinator = HomeRealtimeCoordinator(client, TokenManagerImpl(), recoveryFactory = { GenericEventsBackoff { 0.0 } })
        val job = coordinator.connect(backgroundScope)
        advanceTimeBy(65_001)
        assertEquals(listOf(0L, 5_000L, 15_000L, 35_000L, 65_000L), starts)
        assertEquals(1, peak)
        job.cancelAndJoin()
        advanceTimeBy(300_000)
        assertEquals(5, starts.size)
        assertEquals(0, collectors)
    }

    @Test fun healthyOutcomeResetsAndReadableRetryAfterRaisesFloor() = runTest {
        val starts = mutableListOf<Long>()
        val outcomes = listOf(
            GenericEventsClose(), GenericEventsClose(acceptedForMs = 30_000),
            GenericEventsClose(), GenericEventsClose(retryAfterMs = 60_000),
        )
        val client = object : HomeRealtimeClient {
            override fun connect() = flow {
                val index = starts.size
                starts += testScheduler.currentTime
                if (index < outcomes.size) emit(HomeRealtimeEvent.Closed(recovery = outcomes[index]))
                else awaitCancellation()
            }
        }
        val job = HomeRealtimeCoordinator(client, TokenManagerImpl(), recoveryFactory = { GenericEventsBackoff { 0.0 } }).connect(backgroundScope)
        advanceTimeBy(71_001)
        assertEquals(listOf(0L, 5_000L, 6_000L, 11_000L, 71_000L), starts)
        job.cancelAndJoin()
    }

    @Test fun foregroundStartWaitsForInFlightIdentityReplacement() = runTest {
        val transitions = DefaultIdentityTransitionBarrier()
        val release = CompletableDeferred<Unit>()
        val replacing = backgroundScope.launch {
            transitions.changing(IdentityTransitionKind.ACCOUNT_REPLACE) { release.await() }
        }
        runCurrent()
        var starts = 0
        val client = object : HomeRealtimeClient {
            override fun connect() = flow<HomeRealtimeEvent> {
                starts++
                awaitCancellation()
            }
        }
        val job = HomeRealtimeCoordinator(client, TokenManagerImpl(), identityTransitions = transitions).connect(backgroundScope)
        runCurrent()
        assertEquals(0, starts, "a foreground start during replacement must not use transient identity")
        release.complete(Unit)
        runCurrent()
        assertEquals(1, starts)
        replacing.join()
        job.cancelAndJoin()
    }

    @Test fun replacementCancelsOldRetryBeforeStartingNewIdentity() = runTest {
        for (kind in listOf(IdentityTransitionKind.ACCOUNT_REPLACE, IdentityTransitionKind.PROFILE_SWITCH)) {
            val transitions = DefaultIdentityTransitionBarrier()
            val starts = mutableListOf<Long>()
            val origin = testScheduler.currentTime
            val client = object : HomeRealtimeClient {
                override fun connect() = flow {
                    starts += testScheduler.currentTime - origin
                    emit(HomeRealtimeEvent.Closed())
                }
            }
            val job = HomeRealtimeCoordinator(client, TokenManagerImpl(), identityTransitions = transitions,
                recoveryFactory = { GenericEventsBackoff { 0.0 } }).connect(backgroundScope)
            advanceTimeBy(2_000)
            val replacing = backgroundScope.launch {
                transitions.changing(kind) { delay(10_000) }
            }
            runCurrent()
            advanceTimeBy(10_001)
            assertEquals(listOf(0L, 12_000L), starts, "no attempt while identity is being replaced")
            advanceTimeBy(5_000)
            assertEquals(listOf(0L, 12_000L, 17_000L), starts)
            replacing.join()
            job.cancelAndJoin()
        }
    }

    @Test fun replacementCancelsActiveCollectorAndBackgroundStopEndsReplacementCollector() = runTest {
        val transitions = DefaultIdentityTransitionBarrier()
        var active = 0
        var peak = 0
        var starts = 0
        val client = object : HomeRealtimeClient {
            override fun connect() = flow<HomeRealtimeEvent> {
                starts++; active++; peak = maxOf(peak, active)
                try { awaitCancellation() } finally { active-- }
            }
        }
        val job = HomeRealtimeCoordinator(client, TokenManagerImpl(), identityTransitions = transitions).connect(backgroundScope)
        runCurrent()
        transitions.changing(IdentityTransitionKind.ACCOUNT_REPLACE) { }
        runCurrent()
        assertEquals(2, starts)
        assertEquals(1, active)
        assertEquals(1, peak)
        job.cancelAndJoin()
        assertEquals(0, active)
        advanceTimeBy(300_000)
        assertEquals(2, starts)
    }
}
