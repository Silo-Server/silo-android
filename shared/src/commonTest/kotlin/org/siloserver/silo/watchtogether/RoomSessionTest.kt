package org.siloserver.silo.watchtogether

import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.DefaultIdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoomSessionTest {
    @Test
    fun `local next up cannot replace an active Watch Together room`() {
        assertTrue(!shouldNavigateToLocalNext(inWatchTogetherRoom = true))
        assertTrue(shouldNavigateToLocalNext(inWatchTogetherRoom = false))
    }

    @Test
    fun `lobby and player adoption of the same room reuses one socket`() = runTest {
        val repository = FakeRoomSessionRepository()
        val session = RoomSession(repository, backgroundScope, DefaultIdentityTransitionBarrier())

        session.adopt("room-a").join()
        session.adopt("room-a").join()
        runCurrent()

        assertEquals(listOf("room-a"), repository.started)
        session.leave()
    }

    @Test
    fun `replacement cancels and joins room A before room B starts`() = runTest {
        val repository = FakeRoomSessionRepository()
        val finalizerGate = CompletableDeferred<Unit>()
        repository.finalizerGates["room-a"] = finalizerGate
        val session = RoomSession(repository, backgroundScope, DefaultIdentityTransitionBarrier())
        session.enter("room-a")
        runCurrent()

        val replacement = launch { session.enter("room-b") }
        runCurrent()
        assertEquals(listOf("room-a"), repository.started)
        assertEquals(listOf("room-a"), repository.canceling)

        finalizerGate.complete(Unit)
        replacement.join()
        runCurrent()

        assertEquals(listOf("room-a", "room-b"), repository.started)
        session.leave()
    }

    @Test
    fun `concurrent enters serialize and newest room owns the connection`() = runTest {
        val repository = FakeRoomSessionRepository()
        val session = RoomSession(repository, backgroundScope, DefaultIdentityTransitionBarrier())

        val first = launch { session.enter("room-a") }
        val second = launch { session.enter("room-b") }
        first.join()
        second.join()
        runCurrent()

        assertEquals(listOf("room-a", "room-b"), repository.started)
        assertEquals(listOf("room-a"), repository.canceling)
        assertTrue(session.isActive())
        session.leave()
    }

    @Test
    fun `leave joins the connection before resetting repository state`() = runTest {
        val repository = FakeRoomSessionRepository()
        val finalizerGate = CompletableDeferred<Unit>()
        repository.finalizerGates["room-a"] = finalizerGate
        val session = RoomSession(repository, backgroundScope, DefaultIdentityTransitionBarrier())
        session.enter("room-a")
        runCurrent()

        val leaving = launch { session.leave() }
        runCurrent()
        assertEquals(0, repository.resetCount)

        finalizerGate.complete(Unit)
        leaving.join()

        assertEquals(1, repository.resetCount)
        assertTrue(!session.isActive())
    }

    @Test
    fun `durable departure survives cancellation of the initiating screen`() = runTest {
        val repository = FakeRoomSessionRepository()
        val finalizerGate = CompletableDeferred<Unit>()
        repository.finalizerGates["room-a"] = finalizerGate
        val session = RoomSession(repository, backgroundScope, DefaultIdentityTransitionBarrier())
        session.adopt("room-a").join()

        val screen = launch {
            session.depart()
        }
        screen.join()
        screen.cancel()
        runCurrent()
        assertEquals(0, repository.resetCount)

        finalizerGate.complete(Unit)
        runCurrent()

        assertEquals(1, repository.resetCount)
        assertTrue(!session.isActive())
    }

    @Test
    fun `identity WILL_CHANGE cancels joins and resets before mutation`() = runTest {
        val repository = FakeRoomSessionRepository()
        val barrier = DefaultIdentityTransitionBarrier()
        val session = RoomSession(repository, backgroundScope, barrier)
        session.enter("room-a")
        runCurrent()
        var mutationObservedSafeState = false

        barrier.changing(IdentityTransitionKind.SERVER_SWITCH) {
            mutationObservedSafeState =
                repository.canceling == listOf("room-a") && repository.resetCount == 1
        }

        assertTrue(mutationObservedSafeState)
        assertTrue(!session.isActive())
    }

    @Test
    fun `identity mutation waits for socket finalizer and reset`() = runTest {
        val repository = FakeRoomSessionRepository()
        val finalizerGate = CompletableDeferred<Unit>()
        repository.finalizerGates["room-a"] = finalizerGate
        val barrier = DefaultIdentityTransitionBarrier()
        val session = RoomSession(repository, backgroundScope, barrier)
        session.enter("room-a")
        var mutationStarted = false

        val transition = launch {
            barrier.changing(IdentityTransitionKind.PROFILE_SWITCH) {
                mutationStarted = true
            }
        }
        runCurrent()

        assertTrue(!mutationStarted)
        assertEquals(0, repository.resetCount)
        finalizerGate.complete(Unit)
        transition.join()

        assertTrue(mutationStarted)
        assertEquals(1, repository.resetCount)
    }

    @Test
    fun `room remains adopted until explicit leave or identity transition`() = runTest {
        val repository = FakeRoomSessionRepository()
        val barrier = DefaultIdentityTransitionBarrier()
        val session = RoomSession(repository, backgroundScope, barrier)

        session.adopt("room-a").join()
        runCurrent()
        assertTrue(session.isActive())
        assertEquals(0, repository.resetCount)

        barrier.changing(IdentityTransitionKind.PROFILE_SWITCH) {
            assertTrue(!session.isActive())
            assertEquals(1, repository.resetCount)
        }
    }

    @Test
    fun `sign out clears the adopted room before identity mutation`() = runTest {
        val repository = FakeRoomSessionRepository()
        val barrier = DefaultIdentityTransitionBarrier()
        val session = RoomSession(repository, backgroundScope, barrier)
        session.adopt("room-a").join()
        runCurrent()

        barrier.changing(IdentityTransitionKind.SIGN_OUT) {
            assertTrue(!session.isActive())
            assertEquals(1, repository.resetCount)
        }
    }

    @Test
    fun `every identity transition kind resets the room before mutation`() = runTest {
        IdentityTransitionKind.entries.forEach { kind ->
            val repository = FakeRoomSessionRepository()
            val barrier = DefaultIdentityTransitionBarrier()
            val session = RoomSession(repository, backgroundScope, barrier)
            session.enter("room-${kind.name}")
            var mutationObservedSafeState = false

            barrier.changing(kind) {
                mutationObservedSafeState =
                    repository.canceling == listOf("room-${kind.name}") &&
                        repository.resetCount == 1 &&
                        !session.isActive()
            }

            assertTrue(mutationObservedSafeState, "room remained visible during $kind")
        }
    }

    private class FakeRoomSessionRepository : RoomSessionRepository {
        override val roomSnapshot = MutableStateFlow<RoomSnapshot?>(null)
        override val roomClosedReason = MutableStateFlow<String?>(null)
        val started = mutableListOf<String>()
        val canceling = mutableListOf<String>()
        val finalizerGates = mutableMapOf<String, CompletableDeferred<Unit>>()
        var resetCount = 0

        override suspend fun connect(roomId: String) {
            started += roomId
            try {
                awaitCancellation()
            } finally {
                canceling += roomId
                withContext(NonCancellable) {
                    finalizerGates[roomId]?.await()
                }
            }
        }

        override suspend fun reset() {
            resetCount++
            roomSnapshot.value = null
        }

        override suspend fun closeRoom(): ApiResult<Unit> = ApiResult.Success(Unit)
    }
}
