package org.siloserver.silo.network

import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.Suggestion
import org.siloserver.silo.model.watchtogether.TransportCommand

/** Room WS server-frame type discriminators (mirrors the server `"type"` strings). */
object WatchTogetherRealtime {
    const val TypeSnapshot = "snapshot"
    const val TypeTransportCommand = "transport_command"
    const val TypeSuggestionsUpdate = "suggestions_update"
    const val TypeRoomClosed = "room_closed"
    const val TypeConnectionReplaced = "connection_replaced"
    const val TypePong = "pong"
    const val TypeError = "error"
}

/**
 * A decoded room realtime event. The repository folds these into its
 * StateFlows and hands transport commands and clock samples to the player
 * binding.
 */
sealed class RoomRealtimeEvent {
    /** A physical connection completed its handshake and is writable. */
    data object Opened : RoomRealtimeEvent()

    data class SnapshotEvent(val room: RoomSnapshot) : RoomRealtimeEvent()
    data class TransportCommandEvent(val command: TransportCommand) : RoomRealtimeEvent()
    data class SuggestionsEvent(val suggestions: List<Suggestion>) : RoomRealtimeEvent()

    /**
     * A clock sample. [clientReceivedMs] is stamped on the wall clock when the
     * frame left the socket, before any downstream collector could delay it.
     */
    data class Pong(
        val clientSentAt: String,
        val serverReceivedAt: String,
        val serverSentAt: String,
        val clientReceivedMs: Long? = null,
    ) : RoomRealtimeEvent()

    /**
     * Explicit server `room_closed{reason}`. Terminal. At connect time the
     * reason is `not_found` or `ended`; every later close says `host_left`,
     * including the host's explicit End.
     */
    data class Closed(val reason: String? = null) : RoomRealtimeEvent()

    /**
     * Another connection for this account and profile took over the room
     * membership. Terminal for this device only: the room keeps going, and
     * only an explicit user rejoin may reclaim it.
     */
    data class ConnectionReplaced(val reason: String? = null) : RoomRealtimeEvent()

    /** Physical EOF, handshake failure, or socket I/O failure. Transient unless [cause] is terminal. */
    data class TransportTerminated(val cause: Throwable? = null) : RoomRealtimeEvent()

    /** Server `error{code,message}`: an action-level rejection, not the end of the room. */
    data class Error(val code: String, val message: String) : RoomRealtimeEvent()

    /**
     * A frame of a known [type] that did not decode. The repository reconciles
     * by reading the room instead of silently dropping state.
     */
    data class Malformed(val type: String) : RoomRealtimeEvent()
}

/**
 * The room ticket mint refused the request. [terminal] refusals (401 after the
 * interceptor's one refresh, 403, 404, 409, 422) end the engagement; anything
 * else is a transport failure that may be retried.
 */
class RoomTicketRefusedException(
    val status: Int,
    val code: String,
) : IllegalStateException("room_ticket_refused:$status:$code") {
    val terminal: Boolean get() = status in TERMINAL_STATUSES

    private companion object {
        val TERMINAL_STATUSES = setOf(401, 403, 404, 409, 422)
    }
}

/**
 * The minted ticket was valid but `expires_in` was 0: the access token or the
 * room proof has under a second left. Renew whichever is expiring, then mint
 * again; minting again unchanged cannot help.
 */
class RoomTicketExpiringException : IllegalStateException("room_ticket_expiring")
