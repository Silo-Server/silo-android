package org.siloserver.silo.network

import org.siloserver.silo.model.watchtogether.RoomSnapshot
import org.siloserver.silo.model.watchtogether.Suggestion
import org.siloserver.silo.model.watchtogether.TransportCommand
import org.siloserver.silo.model.watchtogether.WsAttachSession
import org.siloserver.silo.model.watchtogether.WsBuffering
import org.siloserver.silo.model.watchtogether.WsPing
import org.siloserver.silo.model.watchtogether.WsReady
import org.siloserver.silo.model.watchtogether.WsStateReport
import org.siloserver.silo.model.watchtogether.WsTransportRequest
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.http.isSuccess
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
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

/**
 * Per-room websocket. One [connect] = one ticket mint at
 * `POST /api/v2/watch-together/rooms/{id}/ws-ticket` (scoped auth plus the
 * room JWT in `X-Room-Token`) followed by one upgrade of
 * `/api/v2/watch-together/rooms/{id}/ws` that carries only the single-use
 * ticket in `Sec-WebSocket-Protocol`. No credential travels in the URL.
 *
 * [RoomRealtimeEvent.Closed] is reserved for an explicit decoded
 * `room_closed` frame. Physical EOF and socket failures surface as
 * [RoomRealtimeEvent.TransportTerminated], while owner cancellation is silent.
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
    suspend fun stateReport(sessionId: String, positionSeconds: Double, isPaused: Boolean): Boolean
    suspend fun ready(sessionId: String, positionSeconds: Double, isPaused: Boolean): Boolean
    suspend fun buffering(sessionId: String, positionSeconds: Double, isPaused: Boolean): Boolean
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

private fun roomPath(roomId: String) = "/api/v2/watch-together/rooms/${roomId.encodeURLPathPart()}"

private class KtorWatchTogetherSocketConnector(
    private val client: HttpClient,
) : WatchTogetherSocketConnector {
    override suspend fun open(request: WatchTogetherSocketRequest): WatchTogetherSocketConnection {
        val ticket = when (val minted = safeApiV2Call<WsTicketResponse>(ApiV2Gate.Unrestricted) {
            client.post {
                url { encodedPath = "${roomPath(request.roomId)}/ws-ticket" }
                authScope(request.authScope); requireSiloAuth()
                header("X-Room-Token", request.roomToken)
            }.also { check(!it.status.isSuccess() || it.status == HttpStatusCode.OK) }
        }) {
            is ApiResult.Success -> minted.data
            is ApiResult.Error -> throw IllegalStateException("room_ticket_unavailable:${minted.code}")
            is ApiResult.NetworkError -> throw minted.exception
        }
        check(ticket.protocol == ROOM_SOCKET_PROTOCOL && ticket.ticket.isNotEmpty()) { "room_ticket_invalid" }
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
) : WatchTogetherRealtimeClient {

    constructor(
        client: HttpClient,
        tokenManager: TokenManager,
        json: Json = SiloJson,
    ) : this(tokenManager, json, KtorWatchTogetherSocketConnector(client))

    internal constructor(
        tokenManager: TokenManager,
        socketConnector: WatchTogetherSocketConnector,
        json: Json = SiloJson,
    ) : this(tokenManager, json, socketConnector)

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
            trySend(
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
            trySend(RoomRealtimeEvent.Opened)
            while (true) {
                val raw = connection.receiveText() ?: break
                decodeRoomFrame(json, raw)?.let { trySend(it) }
            }
            trySend(RoomRealtimeEvent.TransportTerminated())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            trySend(RoomRealtimeEvent.TransportTerminated(failure))
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
        return try {
            writable.sendText(text)
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            false
        }
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
        return try {
            writable.sendText(payload)
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun stateReport(sessionId: String, positionSeconds: Double, isPaused: Boolean) =
        sendText(
            json.encodeToString(
                WsStateReport.serializer(),
                WsStateReport(sessionId = sessionId, positionSeconds = positionSeconds, isPaused = isPaused),
            ),
        )

    override suspend fun ready(sessionId: String, positionSeconds: Double, isPaused: Boolean) =
        sendText(
            json.encodeToString(
                WsReady.serializer(),
                WsReady(sessionId = sessionId, positionSeconds = positionSeconds, isPaused = isPaused),
            ),
        )

    override suspend fun buffering(sessionId: String, positionSeconds: Double, isPaused: Boolean) =
        sendText(
            json.encodeToString(
                WsBuffering.serializer(),
                WsBuffering(sessionId = sessionId, positionSeconds = positionSeconds, isPaused = isPaused),
            ),
        )

    override suspend fun ping(clientSentAt: String) =
        sendText(json.encodeToString(WsPing.serializer(), WsPing(clientSentAt = clientSentAt)))
}

/**
 * Pure decode of one room WS server frame into a [RoomRealtimeEvent], or null
 * when the frame is not one we surface (unknown `type`, malformed payload, or
 * malformed JSON). Never throws — this is the load-bearing, fully-tested
 * logic; socket I/O above is kept thin and untested.
 *
 *  - `snapshot {room}`            → [RoomRealtimeEvent.SnapshotEvent]
 *  - `transport_command {command}`→ [RoomRealtimeEvent.TransportCommandEvent]
 *  - `suggestions_update {suggestions}` → [RoomRealtimeEvent.SuggestionsEvent]
 *  - `room_closed {reason}`       → [RoomRealtimeEvent.Closed]
 *  - `pong {…}`                   → [RoomRealtimeEvent.Pong]
 *  - `error {code,message}`       → [RoomRealtimeEvent.Error]
 *  - anything else                → null
 */
fun decodeRoomFrame(json: Json, raw: String): RoomRealtimeEvent? {
    val obj: JsonObject = try {
        val element = json.parseToJsonElement(raw)
        element as? JsonObject ?: return null
    } catch (_: Exception) {
        return null
    }

    val type = (obj["type"] as? JsonPrimitive)?.content ?: return null

    return when (type) {
        WatchTogetherRealtime.TypeSnapshot -> {
            val room = obj["room"] as? JsonObject ?: return null
            val snapshot = try {
                json.decodeFromJsonElement(RoomSnapshot.serializer(), room)
            } catch (_: Exception) {
                return null
            }
            RoomRealtimeEvent.SnapshotEvent(snapshot)
        }
        WatchTogetherRealtime.TypeTransportCommand -> {
            val command = obj["command"] as? JsonObject ?: return null
            val parsed = try {
                json.decodeFromJsonElement(TransportCommand.serializer(), command)
            } catch (_: Exception) {
                return null
            }
            RoomRealtimeEvent.TransportCommandEvent(parsed)
        }
        WatchTogetherRealtime.TypeSuggestionsUpdate -> {
            val array = obj["suggestions"] as? JsonArray ?: return null
            val list = try {
                json.decodeFromJsonElement(ListSerializer(Suggestion.serializer()), array)
            } catch (_: Exception) {
                return null
            }
            RoomRealtimeEvent.SuggestionsEvent(list)
        }
        WatchTogetherRealtime.TypeRoomClosed -> {
            val reason = (obj["reason"] as? JsonPrimitive)?.content
            RoomRealtimeEvent.Closed(reason)
        }
        WatchTogetherRealtime.TypePong -> {
            fun str(key: String) = (obj[key] as? JsonPrimitive)?.content ?: ""
            RoomRealtimeEvent.Pong(
                clientSentAt = str("client_sent_at"),
                serverReceivedAt = str("server_received_at"),
                serverSentAt = str("server_sent_at"),
            )
        }
        WatchTogetherRealtime.TypeError -> {
            fun str(key: String) = (obj[key] as? JsonPrimitive)?.content ?: ""
            RoomRealtimeEvent.Error(code = str("code"), message = str("message"))
        }
        else -> null
    }
}
