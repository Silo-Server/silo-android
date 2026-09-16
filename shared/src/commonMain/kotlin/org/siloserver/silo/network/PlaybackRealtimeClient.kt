package org.siloserver.silo.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.websocket.CloseReason
import io.ktor.websocket.close
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.network.apiv2.OwnerPolicy
import org.siloserver.silo.network.apiv2.matches
import org.siloserver.silo.network.apiv2.matches
import org.siloserver.silo.network.apiv2.stillOwns
import org.siloserver.silo.network.apiv2.safeApiV2Call
import org.siloserver.silo.model.notifications.WsTicketResponse
import kotlin.time.TimeSource
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Pure decode of one control-socket server frame into a [PlaybackRealtimeEvent],
 * or null when the frame is not one we handle (unknown type, missing fields,
 * malformed JSON). Never throws. This is the load-bearing tested logic; the
 * socket I/O in [DefaultPlaybackRealtimeClient] is kept thin.
 *
 * Note: [PlaybackRealtimeEvent.Opened]/[Closed] are produced by the socket
 * lifecycle, not by this decoder.
 */
fun decodePlaybackFrame(json: Json, raw: String): PlaybackRealtimeEvent? {
    val obj: JsonObject = try {
        json.parseToJsonElement(raw).jsonObject
    } catch (_: Exception) {
        return null
    }
    // Real JSON strings only — a numeric/null/bool primitive is not a valid
    // string field, so the frame is treated as malformed (returns null).
    fun str(key: String) = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val type = str("type") ?: return null
    val sessionId = str("session_id") ?: return null
    val payload = (obj["payload"] as? JsonObject) ?: JsonObject(emptyMap())
    return when (type) {
        "command" -> {
            val commandId = str("command_id") ?: return null
            val name = str("name") ?: return null
            PlaybackRealtimeEvent.Command(commandId, sessionId, name, payload)
        }
        "event" -> {
            val name = str("name") ?: return null
            PlaybackRealtimeEvent.ServerEvent(sessionId, name, payload)
        }
        else -> null
    }
}

/** One owner-bound v2 ticket and one upgrade per collection. Controllers own reconnect. */
interface PlaybackRealtimeClient {
    fun connect(sessionId: String): Flow<PlaybackRealtimeEvent>
    suspend fun sendHello(sessionId: String)
    suspend fun sendAck(sessionId: String, commandId: String)
    suspend fun sendResult(sessionId: String, commandId: String, status: String, error: String? = null)
}

class DefaultPlaybackRealtimeClient(
    private val client: HttpClient,
    private val tokenManager: TokenManager,
    private val json: Json = SiloJson,
    private val gate: ApiV2Gate,
    private val ownerProvider: suspend (String) -> Pair<AuthScopeSnapshot, String?>? = { null },
) : PlaybackRealtimeClient {

    /**
     * The socket paired with the playback session it belongs to.
     *
     * Holding the id alongside the socket is what makes a send answerable to a
     * caller. With a bare socket field, a reconnect overwrites it while the
     * outgoing connection's `finally` clears it unconditionally — so a late
     * close from session A disables session B's remote control, and an ack for
     * A goes out over B's socket and vanishes. Both are indistinguishable from
     * a flaky network at the call site.
     */
    private data class RealtimeConnection(
        val sessionId: String,
        val socket: DefaultClientWebSocketSession,
        val owner: Pair<AuthScopeSnapshot, String?>,
        val commands: MutableSet<String> = mutableSetOf(),
    )

    /**
     * Guards [connection]. A volatile read-then-write is not a compare-and-set:
     * a newer connection can install itself between the two, and the older one
     * then clears it. Every access is a suspend call site, so a mutex is enough
     * and needs no atomics dependency.
     */
    private val connectionLock = Mutex()
    private var connection: RealtimeConnection? = null

    override fun connect(sessionId: String): Flow<PlaybackRealtimeEvent> = callbackFlow {
        val owner = ownerProvider(sessionId)
        if (owner == null || !current(owner, sessionId)) {
            trySend(PlaybackRealtimeEvent.Closed("playback_authority_unavailable"))
            close()
            return@callbackFlow
        }
        val mintedAt = TimeSource.Monotonic.markNow()
        val proof = when (val result = safeApiV2Call<WsTicketResponse>(gate) {
            client.post {
                url { path("", "api", "v2", "playback", "sessions", sessionId, "control", "ws-ticket") }
                authScope(owner.first); requireSiloAuth(); singleAttempt()
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject { owner.second?.let { put("installation_id", it) } })
            }.also { check(!it.status.isSuccess() || it.status == HttpStatusCode.OK) }
        }) {
            is ApiResult.Success -> result.data
            else -> {
                trySend(PlaybackRealtimeEvent.Closed("control_ticket_unavailable"))
                close()
                return@callbackFlow
            }
        }
        if (!validPlaybackControlTicket(proof) || !current(owner, sessionId) ||
            mintedAt.elapsedNow().inWholeMilliseconds >= proof.expiresIn * 1000L) {
            trySend(PlaybackRealtimeEvent.Closed("control_ticket_unavailable"))
            close()
            return@callbackFlow
        }
        var owned: RealtimeConnection? = null
        // Clear on identity under the lock, never a blind null: this connection
        // may already have been superseded by a newer one, and clearing that
        // would leave the live socket unreachable to every send.
        // NonCancellable: every caller below runs on a teardown path, and two of
        // the three run while this coroutine is already cancelled. A cancellable
        // acquisition simply throws there, leaving a dead socket installed as the
        // target of every subsequent send until some later connection happens to
        // overwrite it.
        suspend fun releaseIfStillOwned() {
            withContext(NonCancellable) {
                connectionLock.withLock {
                    if (connection === owned) connection = null
                }
            }
        }
        try {
            withTimeoutOrNull(proof.maxConnectionSeconds * 1000L) {
                client.webSocket(request = { playbackControlUpgrade(owner.first, sessionId, proof) }) {
                    check(call.response.headers[HttpHeaders.SecWebSocketProtocol] == PLAYBACK_CONTROL_PROTOCOL)
                    if (!current(owner, sessionId)) return@webSocket
                    val current = RealtimeConnection(sessionId, this, owner)
                    owned = current
                    connectionLock.withLock { connection = current }
                    // R2: signal open AFTER the session is assigned, so the
                    // controller's hello can't race ahead of a live socket.
                    trySend(PlaybackRealtimeEvent.Opened)
                    val authorityWatcher = launch {
                        while (isActive) {
                            delay(1000)
                            if (!current(owner, sessionId)) {
                                current.socket.close(CloseReason(CloseReason.Codes.NORMAL, "Identity changed"))
                                break
                            }
                        }
                    }
                    try {
                        for (frame in incoming) {
                            if (!current(owner, sessionId)) break
                            if (frame !is Frame.Text) continue
                            decodePlaybackFrame(json, frame.readText())?.let {
                                val matches = when (it) {
                                    is PlaybackRealtimeEvent.Command -> it.sessionId == sessionId
                                    is PlaybackRealtimeEvent.ServerEvent -> it.sessionId == sessionId
                                    else -> false
                                }
                                if (matches) {
                                    if (it is PlaybackRealtimeEvent.Command) connectionLock.withLock {
                                        if (connection === current) current.commands.add(it.commandId)
                                    }
                                    trySend(it)
                                }
                            }
                        }
                    } finally {
                        authorityWatcher.cancel()
                        releaseIfStillOwned()
                    }
                }
            }
            trySend(PlaybackRealtimeEvent.Closed())
        } catch (cancellation: CancellationException) {
            // Not a socket failure. Reporting Closed here tells the controller to
            // reconnect the very session that is being torn down.
            releaseIfStillOwned()
            throw cancellation
        } catch (e: Throwable) {
            releaseIfStillOwned()
            trySend(PlaybackRealtimeEvent.Closed("control_connection_closed"))
        } finally {
            close()
        }
        awaitClose { }
    }

    /**
     * Writes only on the connection that belongs to [sessionId]. Every envelope
     * already names its session, so a send that cannot be matched to the open
     * socket is for a connection that has moved on — dropping it is correct, and
     * strictly better than writing it down somebody else's socket.
     */
    private suspend fun sendText(sessionId: String, text: String, commandId: String? = null) {
        // Resolve under the lock, then send outside it — the send is network I/O
        // and must not block a teardown trying to release the field.
        val current = connectionLock.withLock {
            connection?.takeIf { it.sessionId == sessionId && (commandId == null || commandId in it.commands) }
        } ?: return
        if (!current(current.owner, sessionId)) return
        current.socket.send(Frame.Text(text))
    }

    private suspend fun current(owner: Pair<AuthScopeSnapshot, String?>, sessionId: String): Boolean {
        val live = ownerProvider(sessionId) ?: return false
        return live.second == owner.second && owner.first.stillOwns(tokenManager, OwnerPolicy.FULL) &&
            owner.first.matches(live.first, OwnerPolicy.PROFILE)
    }

    override suspend fun sendHello(sessionId: String) = sendText(
        sessionId,
        json.encodeToString(
            PlaybackHelloEnvelope.serializer(),
            PlaybackHelloEnvelope(
                sessionId = sessionId,
                client = HelloClient(),
                capabilities = HelloCapabilities(PlaybackCommandNames.Supported),
            ),
        ),
    )

    override suspend fun sendAck(sessionId: String, commandId: String) = sendText(
        sessionId,
        json.encodeToString(
            PlaybackAckEnvelope.serializer(),
            PlaybackAckEnvelope(commandId = commandId, sessionId = sessionId),
        ),
        commandId = commandId,
    )

    override suspend fun sendResult(sessionId: String, commandId: String, status: String, error: String?) = sendText(
        sessionId,
        json.encodeToString(
            PlaybackResultEnvelope.serializer(),
            PlaybackResultEnvelope(commandId = commandId, sessionId = sessionId, status = status, error = error),
        ),
        commandId = commandId,
    )
}

internal const val PLAYBACK_CONTROL_PROTOCOL = "silo.playback-control.v2"

internal fun validPlaybackControlTicket(ticket: WsTicketResponse): Boolean =
    ticket.protocol == PLAYBACK_CONTROL_PROTOCOL && ticket.expiresIn in 1..30 &&
        ticket.maxConnectionSeconds in 1..14400 && ticket.ticket.isNotEmpty() &&
        ticket.ticket.all { it.code < 128 && (it.isLetterOrDigit() || it in "!#$%&'*+-.^_`|~") }

internal fun HttpRequestBuilder.playbackControlUpgrade(scope: AuthScopeSnapshot, sessionId: String, ticket: WsTicketResponse) {
    require(validPlaybackControlTicket(ticket))
    url {
        takeFrom(scope.serverUrl)
        require(protocol == URLProtocol.HTTP || protocol == URLProtocol.HTTPS)
        require(user == null && password == null)
        protocol = if (protocol == URLProtocol.HTTPS) URLProtocol.WSS else URLProtocol.WS
        path("", "api", "v2", "playback", "sessions", sessionId, "control", "ws")
        parameters.clear(); fragment = ""
    }
    authScope(scope); skipSiloAuth(); singleAttempt()
    headers.remove(HttpHeaders.Authorization)
    headers.remove("X-Profile-Id"); headers.remove("X-Profile-Token")
    header(HttpHeaders.SecWebSocketProtocol, "$PLAYBACK_CONTROL_PROTOCOL, silo.ticket.${ticket.ticket}")
}
