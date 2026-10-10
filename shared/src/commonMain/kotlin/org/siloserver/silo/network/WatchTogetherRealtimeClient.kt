package org.siloserver.silo.network

import org.siloserver.silo.model.watchtogether.RoomPlaybackState
import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.Suggestion
import org.siloserver.silo.model.watchtogether.TransportAction
import org.siloserver.silo.model.watchtogether.TransportCommand
import org.siloserver.silo.model.watchtogether.WsAttachSession
import org.siloserver.silo.model.watchtogether.WsBuffering
import org.siloserver.silo.model.watchtogether.WsLobbyReady
import org.siloserver.silo.model.watchtogether.WsPing
import org.siloserver.silo.model.watchtogether.WsReady
import org.siloserver.silo.model.watchtogether.WsStateReport
import org.siloserver.silo.model.watchtogether.WsTransportRequest
import org.siloserver.silo.network.api.ROOM_TOKEN_HEADER
import org.siloserver.silo.util.wallClockMillis
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLPathPart
import io.ktor.http.encodedPath
import io.ktor.http.takeFrom
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.siloserver.silo.model.notifications.WsTicketResponse
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.network.apiv2.safeApiV2Call
import org.siloserver.silo.util.parseRfc3339ToEpochMillis

/**
 * Per-room websocket. One [connect] = one ticket mint at
 * `POST /api/v2/watch-together/rooms/{id}/ws-ticket` (scoped auth plus the
 * room proof in `X-Room-Token`) followed by one upgrade of
 * `/api/v2/watch-together/rooms/{id}/ws` that carries only the single-use
 * ticket in `Sec-WebSocket-Protocol`. No credential travels in the URL.
 *
 * The ticket mint may be refreshed and resent once by the auth interceptor
 * after a 401; the server classifies it as naturally idempotent.
 *
 * [RoomRealtimeEvent.Closed] and [RoomRealtimeEvent.ConnectionReplaced] are
 * reserved for decoded terminal frames. Physical EOF and socket failures
 * surface as [RoomRealtimeEvent.TransportTerminated], while owner cancellation
 * is silent. Frames are delivered with backpressure: a slow collector pauses
 * the receive loop instead of losing events.
 */
interface WatchTogetherRealtimeClient {
    /** Open one physical room socket and publish [RoomRealtimeEvent.Opened] once writable. */
    fun connect(
        roomId: String,
        roomToken: String,
        authScope: AuthScopeSnapshot? = null,
    ): Flow<RoomRealtimeEvent>

    /** Client→server sends report whether a frame reached the current writable socket. */
    suspend fun attachSession(sessionId: String): Boolean
    suspend fun transportRequest(action: String, positionSeconds: Double?, isPaused: Boolean): Boolean
    suspend fun stateReport(
        sessionId: String,
        positionSeconds: Double,
        isPaused: Boolean,
        commandId: String? = null,
        isReady: Boolean = false,
    ): Boolean
    suspend fun ready(sessionId: String, positionSeconds: Double, isPaused: Boolean, commandId: String? = null): Boolean
    suspend fun buffering(sessionId: String, positionSeconds: Double, isPaused: Boolean): Boolean
    suspend fun lobbyReady(ready: Boolean): Boolean = false
    suspend fun ping(clientSentAt: String): Boolean

    /** Opaque identity of the currently writable physical socket, if any. */
    suspend fun currentConnectionId(): Long? = null

    /**
     * Send only on the physical socket identified by [connectionId].
     * A replacement socket must never receive an old room's delayed command.
     */
    suspend fun transportRequestOnConnection(
        connectionId: Long,
        action: String,
        positionSeconds: Double?,
        isPaused: Boolean,
    ): Boolean = false
}

internal interface WatchTogetherSocketConnection {
    suspend fun receiveText(): String?
    suspend fun sendText(text: String)
    suspend fun close()
}

internal data class WatchTogetherSocketRequest(
    val roomId: String,
    val roomToken: String,
    val authScope: AuthScopeSnapshot,
) {
    override fun toString(): String =
        "WatchTogetherSocketRequest(roomId=<redacted>, roomToken=<redacted>, authScope=$authScope)"
}

internal fun interface WatchTogetherSocketConnector {
    suspend fun open(request: WatchTogetherSocketRequest): WatchTogetherSocketConnection
}

internal const val ROOM_SOCKET_PROTOCOL = "silo.room.v2"

/** A frame that cannot be handed to the socket in this long is a failed send. */
private const val SEND_TIMEOUT_MS = 5_000L

private fun roomPath(roomId: String) = "/api/v2/watch-together/rooms/${roomId.encodeURLPathPart()}"

/**
 * A room ticket is usable when it names the room protocol, fits the server's
 * bounds, and can travel in a header. `expires_in` of 0 is valid but expiring;
 * see [RoomTicketExpiringException].
 */
internal fun validRoomTicket(ticket: WsTicketResponse): Boolean =
    ticket.protocol == ROOM_SOCKET_PROTOCOL && ticket.expiresIn in 0..30 &&
        ticket.maxConnectionSeconds in 1..300 && ticket.ticket.isNotEmpty() &&
        ticket.ticket.all { it.isLetterOrDigit() && it.code < 128 || it in "!#$%&'*+-.^_`|~" }

private class KtorWatchTogetherSocketConnector(
    private val client: HttpClient,
) : WatchTogetherSocketConnector {
    override suspend fun open(request: WatchTogetherSocketRequest): WatchTogetherSocketConnection {
        val ticket = when (val minted = safeApiV2Call<WsTicketResponse>(ApiV2Gate.Unrestricted) {
            client.post {
                url { encodedPath = "${roomPath(request.roomId)}/ws-ticket" }
                authScope(request.authScope); requireSiloAuth()
                header(ROOM_TOKEN_HEADER, request.roomToken)
            }.also { check(it.status.value !in 200..299 || it.status == HttpStatusCode.OK) }
        }) {
            is ApiResult.Success -> minted.data
            is ApiResult.Error -> throw RoomTicketRefusedException(minted.code, minted.error)
            is ApiResult.NetworkError -> throw minted.exception
        }
        check(validRoomTicket(ticket)) { "room_ticket_invalid" }
        if (ticket.expiresIn == 0) throw RoomTicketExpiringException()
        val session = client.webSocketSession { roomSocketUpgrade(request.authScope, request.roomId, ticket) }
        if (session.call.response.headers[HttpHeaders.SecWebSocketProtocol] != ROOM_SOCKET_PROTOCOL) {
            runCatching { session.close() }
            throw IllegalStateException("room_socket_protocol_rejected")
        }
        return KtorWatchTogetherSocketConnection(session)
    }
}

internal fun HttpRequestBuilder.roomSocketUpgrade(scope: AuthScopeSnapshot, roomId: String, ticket: WsTicketResponse) {
    url {
        takeFrom(scope.serverUrl)
        require(protocol == URLProtocol.HTTP || protocol == URLProtocol.HTTPS)
        require(user == null && password == null)
        protocol = if (protocol == URLProtocol.HTTPS) URLProtocol.WSS else URLProtocol.WS
        encodedPath = "${roomPath(roomId)}/ws"
        parameters.clear(); fragment = ""
    }
    // Pin routing but send only the ticket protocols, never bearer/PIN headers.
    authScope(scope); skipSiloAuth(); singleAttempt()
    headers.remove(HttpHeaders.Authorization)
    headers.remove("X-Profile-Id"); headers.remove("X-Profile-Token")
    header(HttpHeaders.SecWebSocketProtocol, "$ROOM_SOCKET_PROTOCOL, silo.ticket.${ticket.ticket}")
}

private class KtorWatchTogetherSocketConnection(
    private val session: DefaultClientWebSocketSession,
) : WatchTogetherSocketConnection {
    override suspend fun receiveText(): String? {
        while (true) {
            val result = session.incoming.receiveCatching()
            result.exceptionOrNull()?.let { throw it }
            val frame = result.getOrNull() ?: return null
            if (frame is Frame.Text) return frame.readText()
        }
    }

    override suspend fun sendText(text: String) {
        session.send(Frame.Text(text))
    }

    override suspend fun close() {
        session.close()
    }
}

class DefaultWatchTogetherRealtimeClient private constructor(
    private val tokenManager: TokenManager,
    private val json: Json,
    private val socketConnector: WatchTogetherSocketConnector,
    private val wallClockMs: () -> Long,
) : WatchTogetherRealtimeClient {

    constructor(
        client: HttpClient,
        tokenManager: TokenManager,
        json: Json = SiloJson,
    ) : this(tokenManager, json, KtorWatchTogetherSocketConnector(client), ::wallClockMillis)

    internal constructor(
        tokenManager: TokenManager,
        socketConnector: WatchTogetherSocketConnector,
        json: Json = SiloJson,
        wallClockMs: () -> Long = ::wallClockMillis,
    ) : this(tokenManager, json, socketConnector, wallClockMs)

    private val sessionMutex = Mutex()
    private var session: WatchTogetherSocketConnection? = null
    private var sessionId = 0L
    private var activeSessionId: Long? = null

    override fun connect(
        roomId: String,
        roomToken: String,
        authScope: AuthScopeSnapshot?,
    ): Flow<RoomRealtimeEvent> = callbackFlow {
        val scope = authScope ?: tokenManager.snapshotCurrentScope()
        val profileId = scope?.profileId
        val scopedAccessToken = scope?.let { tokenManager.getAccessTokenForScope(it) }
        if (scope == null || scopedAccessToken.isNullOrBlank() || profileId.isNullOrBlank()) {
            send(
                RoomRealtimeEvent.TransportTerminated(
                    IllegalStateException("missing_auth_scope"),
                ),
            )
            close()
            return@callbackFlow
        }

        var connection: WatchTogetherSocketConnection? = null
        try {
            connection = socketConnector.open(
                WatchTogetherSocketRequest(
                    roomId = roomId,
                    roomToken = roomToken,
                    authScope = scope,
                ),
            )
            sessionMutex.withLock {
                session = connection
                activeSessionId = ++sessionId
            }
            send(RoomRealtimeEvent.Opened)
            while (true) {
                val raw = connection.receiveText() ?: break
                decodeRoomFrame(json, raw, wallClockMs())?.let { send(it) }
            }
            send(RoomRealtimeEvent.TransportTerminated())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            // The cause decides whether the owner reconnects, so it must not be
            // dropped behind a full buffer.
            send(RoomRealtimeEvent.TransportTerminated(failure))
        } finally {
            connection?.let { completed ->
                // Cancellation is the privacy boundary for an identity/room
                // replacement. Explicitly finish clearing and closing the
                // physical socket before cancelAndJoin is allowed to return.
                withContext(NonCancellable) {
                    sessionMutex.withLock {
                        if (session === completed) {
                            session = null
                            activeSessionId = null
                        }
                    }
                    runCatching { completed.close() }
                }
            }
            close()
        }

        awaitClose { }
    }

    private suspend fun sendText(text: String): Boolean {
        val writable = sessionMutex.withLock { session } ?: return false
        return deliver(writable, text)
    }

    /**
     * Send one frame, bounded by [SEND_TIMEOUT_MS] so the playback binding's
     * event loop never waits on a socket that has stopped draining. A socket
     * that dies under a pending send cancels its outgoing channel, which
     * surfaces here as a CancellationException even though the caller is
     * still running. Only the caller's own cancellation may propagate;
     * anything else is a failed send. Letting the channel's exception through
     * ended the binding's event loop silently.
     */
    private suspend fun deliver(writable: WatchTogetherSocketConnection, text: String): Boolean =
        try {
            withTimeoutOrNull(SEND_TIMEOUT_MS) { writable.sendText(text) } != null
        } catch (_: Throwable) {
            currentCoroutineContext().ensureActive()
            false
        }

    override suspend fun attachSession(sessionId: String) =
        sendText(json.encodeToString(WsAttachSession.serializer(), WsAttachSession(sessionId = sessionId)))

    override suspend fun transportRequest(action: String, positionSeconds: Double?, isPaused: Boolean) =
        sendText(
            json.encodeToString(
                WsTransportRequest.serializer(),
                WsTransportRequest(action = action, positionSeconds = positionSeconds, isPaused = isPaused),
            ),
        )

    override suspend fun currentConnectionId(): Long? =
        sessionMutex.withLock { activeSessionId }

    override suspend fun transportRequestOnConnection(
        connectionId: Long,
        action: String,
        positionSeconds: Double?,
        isPaused: Boolean,
    ): Boolean {
        val writable = sessionMutex.withLock {
            session.takeIf { activeSessionId == connectionId }
        } ?: return false
        val payload = json.encodeToString(
            WsTransportRequest.serializer(),
            WsTransportRequest(action = action, positionSeconds = positionSeconds, isPaused = isPaused),
        )
        return deliver(writable, payload)
    }

    override suspend fun stateReport(
        sessionId: String,
        positionSeconds: Double,
        isPaused: Boolean,
        commandId: String?,
        isReady: Boolean,
    ) = sendText(
        json.encodeToString(
            WsStateReport.serializer(),
            WsStateReport(
                sessionId = sessionId,
                positionSeconds = positionSeconds,
                isPaused = isPaused,
                commandId = commandId?.takeIf { isReady && it.isNotBlank() },
                isReady = isReady.takeIf { it && !commandId.isNullOrBlank() },
            ),
        ),
    )

    override suspend fun ready(sessionId: String, positionSeconds: Double, isPaused: Boolean, commandId: String?) =
        sendText(
            json.encodeToString(
                WsReady.serializer(),
                WsReady(
                    sessionId = sessionId,
                    positionSeconds = positionSeconds,
                    isPaused = isPaused,
                    commandId = commandId?.takeIf { it.isNotBlank() },
                ),
            ),
        )

    override suspend fun buffering(sessionId: String, positionSeconds: Double, isPaused: Boolean) =
        sendText(
            json.encodeToString(
                WsBuffering.serializer(),
                WsBuffering(sessionId = sessionId, positionSeconds = positionSeconds, isPaused = isPaused),
            ),
        )

    override suspend fun lobbyReady(ready: Boolean) =
        sendText(json.encodeToString(WsLobbyReady.serializer(), WsLobbyReady(ready = ready)))

    override suspend fun ping(clientSentAt: String) =
        sendText(json.encodeToString(WsPing.serializer(), WsPing(clientSentAt = clientSentAt)))
}

/**
 * Pure decode of one room WS server frame into a [RoomRealtimeEvent]. Never
 * throws — this is the load-bearing, fully-tested logic; socket I/O above is
 * kept thin.
 *
 *  - `snapshot {room}`            → [RoomRealtimeEvent.SnapshotEvent]
 *  - `transport_command {command}`→ [RoomRealtimeEvent.TransportCommandEvent]
 *  - `suggestions_update {suggestions}` → [RoomRealtimeEvent.SuggestionsEvent]
 *  - `room_closed {reason}`       → [RoomRealtimeEvent.Closed]
 *  - `connection_replaced {reason}` → [RoomRealtimeEvent.ConnectionReplaced]
 *  - `pong {…}`                   → [RoomRealtimeEvent.Pong], stamped with [receivedAtMs]
 *  - `error {code,message}`       → [RoomRealtimeEvent.Error]
 *  - a known type whose payload does not decode → [RoomRealtimeEvent.Malformed]
 *  - an unknown type or non-object JSON → null (ignored)
 */
fun decodeRoomFrame(json: Json, raw: String, receivedAtMs: Long? = null): RoomRealtimeEvent? {
    val obj: JsonObject = try {
        val element = json.parseToJsonElement(raw)
        element as? JsonObject ?: return null
    } catch (_: Exception) {
        return null
    }

    val type = (obj["type"] as? JsonPrimitive)?.content ?: return null
    fun str(key: String) = (obj[key] as? JsonPrimitive)?.content ?: ""

    return when (type) {
        WatchTogetherRealtime.TypeSnapshot -> {
            val room = obj["room"] as? JsonObject ?: return RoomRealtimeEvent.Malformed(type)
            val snapshot = try {
                json.decodeFromJsonElement(RoomSnapshot.serializer(), room)
            } catch (_: Exception) {
                return RoomRealtimeEvent.Malformed(type)
            }
            if (snapshot.roomId.isBlank()) return RoomRealtimeEvent.Malformed(type)
            RoomRealtimeEvent.SnapshotEvent(snapshot)
        }
        WatchTogetherRealtime.TypeTransportCommand -> {
            val command = obj["command"] as? JsonObject ?: return RoomRealtimeEvent.Malformed(type)
            val parsed = try {
                json.decodeFromJsonElement(TransportCommand.serializer(), command)
            } catch (_: Exception) {
                return RoomRealtimeEvent.Malformed(type)
            }
            // A command must say what to do, when, and in what state the room
            // ends up. Without any of those it can't be applied safely, so the
            // room is reconciled instead. The server always sends all three.
            if (parsed.action == TransportAction.Unknown || parsed.playbackState == RoomPlaybackState.Unknown ||
                parseRfc3339ToEpochMillis(parsed.executeAt) == null
            ) {
                return RoomRealtimeEvent.Malformed(type)
            }
            RoomRealtimeEvent.TransportCommandEvent(parsed)
        }
        WatchTogetherRealtime.TypeSuggestionsUpdate -> {
            val array = obj["suggestions"] as? JsonArray ?: return RoomRealtimeEvent.Malformed(type)
            val list = try {
                json.decodeFromJsonElement(ListSerializer(Suggestion.serializer()), array)
            } catch (_: Exception) {
                return RoomRealtimeEvent.Malformed(type)
            }
            RoomRealtimeEvent.SuggestionsEvent(list)
        }
        WatchTogetherRealtime.TypeRoomClosed ->
            RoomRealtimeEvent.Closed((obj["reason"] as? JsonPrimitive)?.content)
        WatchTogetherRealtime.TypeConnectionReplaced ->
            RoomRealtimeEvent.ConnectionReplaced((obj["reason"] as? JsonPrimitive)?.content)
        WatchTogetherRealtime.TypePong -> RoomRealtimeEvent.Pong(
            clientSentAt = str("client_sent_at"),
            serverReceivedAt = str("server_received_at"),
            serverSentAt = str("server_sent_at"),
            clientReceivedMs = receivedAtMs,
        )
        WatchTogetherRealtime.TypeError -> RoomRealtimeEvent.Error(code = str("code"), message = str("message"))
        else -> null
    }
}
