package org.siloserver.silo.watchtogether

import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Narrow room lifecycle port so the application-scoped owner can be tested
 * independently from REST and websocket implementations.
 */
interface RoomSessionRepository {
    val roomSnapshot: StateFlow<RoomSnapshot?>
    val roomClosedReason: StateFlow<String?>

    /**
     * The local generation of the current membership, or null when there is no
     * live membership. Joining the same room again produces a new generation.
     */
    suspend fun membershipGeneration(): Long?
    suspend fun connect(roomId: String)
    suspend fun reset()
    suspend fun closeRoom(): ApiResult<Unit>
}

/** A Watch Party room, not a device-local episode queue, owns title changes. */
fun shouldNavigateToLocalNext(inWatchTogetherRoom: Boolean): Boolean =
    !inWatchTogetherRoom

/**
 * The single application-scoped owner of a Watch Party connection.
 *
 * Screen scopes adopt this session; they never own the socket. Replacement
 * and leave are serialized, and the previous job is joined before repository
 * state can be reused by another membership. Connections are keyed by
 * membership generation, not room id, so an explicit rejoin of the same room
 * replaces the obsolete connection instead of being mistaken for it.
 */
class RoomSession(
    private val repository: RoomSessionRepository,
    private val scope: CoroutineScope,
    identityTransitions: IdentityTransitionBarrier,
) {
    private val mutex = Mutex()
    private var connectionJob: Job? = null
    private var connectedGeneration: Long? = null

    val room: StateFlow<RoomSnapshot?> = repository.roomSnapshot
    val closedReason: StateFlow<String?> = repository.roomClosedReason

    init {
        identityTransitions.installGate { transition ->
            if (transition.phase == IdentityTransitionPhase.WILL_CHANGE) {
                leave()
            }
        }
    }

    /**
     * UI-facing entry point. The returned job is owned by the process scope, so
     * navigation cannot cancel a queued replacement midway through teardown.
     */
    fun adopt(roomId: String): Job =
        scope.launch(start = CoroutineStart.UNDISPATCHED) { enter(roomId) }

    /**
     * UI-facing durable departure. If requested, End is attempted while the
     * current membership is still live; teardown then joins the socket and
     * resets state even if the initiating screen disappears.
     */
    fun depart(closeRoom: Boolean = false): Job =
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            mutex.withLock {
                if (closeRoom && connectedGeneration != null) {
                    try {
                        repository.closeRoom()
                    } finally {
                        leaveLocked()
                    }
                } else {
                    leaveLocked()
                }
            }
        }

    suspend fun enter(roomId: String) {
        if (roomId.isBlank()) return
        mutex.withLock {
            val generation = repository.membershipGeneration() ?: return
            if (connectedGeneration == generation && connectionJob?.isActive == true) return
            // Join the obsolete socket's teardown before the new membership
            // can own shared state.
            connectionJob?.cancelAndJoin()
            connectedGeneration = generation
            // Establish repository ownership before enter() returns. Without
            // UNDISPATCHED, a concurrent replacement can cancel a queued job
            // before it ever installs its connection owner.
            connectionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                repository.connect(roomId)
            }
        }
    }

    suspend fun leave() {
        mutex.withLock {
            leaveLocked()
        }
    }

    suspend fun isActive(): Boolean = mutex.withLock { connectionJob?.isActive == true }

    private suspend fun leaveLocked() {
        connectionJob?.cancelAndJoin()
        connectionJob = null
        connectedGeneration = null
        repository.reset()
    }
}
