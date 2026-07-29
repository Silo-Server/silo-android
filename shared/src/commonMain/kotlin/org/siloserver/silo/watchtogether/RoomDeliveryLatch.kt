package org.siloserver.silo.watchtogether

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.TransportCommand
import org.siloserver.silo.repository.WatchTogetherConnectionState

data class RoomDeliveryKey(
    val connectionGeneration: Long,
    val connectionEpoch: Long,
    val playbackSessionId: String,
)

data class RoomDeliveryEcho(
    val connectionGeneration: Long,
    val connectionEpoch: Long,
    val playbackSessionId: String,
)

/**
 * Successful-delivery latches for one player controller.
 *
 * Failed sends never advance a latch, so a caller can retry. Reconnection
 * changes the epoch and therefore re-arms attach/readiness even when the room
 * snapshot and local buffering state are otherwise identical.
 */
class RoomDeliveryLatch {
    private var attached: RoomDeliveryKey? = null
    private var readiness: Pair<RoomDeliveryKey, Boolean>? = null

    fun keyOrNull(
        connection: WatchTogetherConnectionState,
        playbackSessionId: String?,
    ): RoomDeliveryKey? =
        playbackSessionId
            ?.takeIf { it.isNotBlank() && connection.writable }
            ?.let {
                RoomDeliveryKey(
                    connectionGeneration = connection.generation,
                    connectionEpoch = connection.epoch,
                    playbackSessionId = it,
                )
            }

    fun needsAttach(key: RoomDeliveryKey): Boolean = attached != key

    fun isAttached(key: RoomDeliveryKey?): Boolean = key != null && attached == key

    /**
     * Session-scoped traffic is safe only after the attach frame was delivered
     * and the server echoed that exact session in a room snapshot.
     */
    fun isServerAttached(key: RoomDeliveryKey?, echo: RoomDeliveryEcho?): Boolean =
        key != null &&
            isAttached(key) &&
            echo != null &&
            echo.connectionGeneration == key.connectionGeneration &&
            echo.connectionEpoch == key.connectionEpoch &&
            echo.playbackSessionId == key.playbackSessionId

    fun recordAttach(key: RoomDeliveryKey, delivered: Boolean) {
        if (delivered) attached = key
    }

    fun needsReadiness(key: RoomDeliveryKey, buffering: Boolean): Boolean =
        readiness != key to buffering

    fun recordReadiness(key: RoomDeliveryKey, buffering: Boolean, delivered: Boolean) {
        if (delivered) readiness = key to buffering
    }
}

/**
 * Starts each accepted command independently so an execute-at delay cannot
 * prevent the transport collector from accepting a newer command.
 */
class RoomCommandDispatcher(
    private val scope: CoroutineScope,
) {
    fun dispatch(block: suspend () -> Unit) {
        scope.launch { block() }
    }
}

class RoomPendingExecutionToken internal constructor()

/** Per-command pending ownership; finishing one command cannot clear another. */
class RoomPendingExecutions {
    private val deadlines = MutableStateFlow<Map<RoomPendingExecutionToken, Long>>(emptyMap())

    fun record(executeAtMs: Long): RoomPendingExecutionToken {
        val token = RoomPendingExecutionToken()
        deadlines.update { it + (token to executeAtMs) }
        return token
    }

    fun finish(token: RoomPendingExecutionToken) {
        deadlines.update { it - token }
    }

    fun nearestTo(nowMs: Long): Long? =
        deadlines.value.values.minByOrNull { deadline ->
            kotlin.math.abs(deadline - nowMs)
        }
}

/**
 * A replaceable controller owns child collectors but never the process room
 * lease. Disposing it cancels only these child jobs.
 */
class RoomControllerLifetime(parentScope: CoroutineScope) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    val scope = CoroutineScope(parentScope.coroutineContext + job)

    fun dispose() {
        job.cancel()
    }
}

/**
 * Revalidate a delayed command immediately before touching the player. This
 * prevents a command accepted for playback session A from seeking/pausing a
 * replacement session B after its execute-at delay.
 */
fun scheduledCommandStillCurrent(
    command: TransportCommand,
    acceptedPlaybackSessionId: String?,
    acceptedRoomId: String,
    acceptedConnection: WatchTogetherConnectionState,
    currentPlaybackSessionId: String?,
    currentRoom: RoomSnapshot?,
    currentConnection: WatchTogetherConnectionState,
): Boolean =
    !acceptedPlaybackSessionId.isNullOrBlank() &&
        currentPlaybackSessionId == acceptedPlaybackSessionId &&
        (command.sessionId.isBlank() || command.sessionId == acceptedPlaybackSessionId) &&
        acceptedRoomId.isNotBlank() &&
        currentRoom?.roomId == acceptedRoomId &&
        currentRoom.selectionRevision == command.selectionRevision &&
        acceptedConnection.writable &&
        currentConnection.writable &&
        currentConnection.generation == acceptedConnection.generation &&
        currentConnection.epoch == acceptedConnection.epoch
