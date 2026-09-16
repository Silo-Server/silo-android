package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.siloserver.silo.model.notifications.WsSubscribe
import org.siloserver.silo.model.notifications.WsTicketResponse
import org.siloserver.silo.network.*
import kotlin.time.TimeSource

/** One collection mints one proof and makes one upgrade attempt; callers own reconnect. */
class EventsSocketV2Api(
    private val client: HttpClient,
    private val tokens: TokenManager,
    private val gate: ApiV2Gate,
) {
    internal suspend fun current(scope: AuthScopeSnapshot): Boolean = scope.stillOwns(tokens, OwnerPolicy.FULL)

    suspend fun ticket(scope: AuthScopeSnapshot? = null): ApiResult<WsTicketResponse> {
        val owner = scope ?: tokens.snapshotCurrentScope() ?: return identityChanged()
        return ownedV2Call<WsTicketResponse, WsTicketResponse>(gate, tokens, owner, OwnerPolicy.FULL, HttpStatusCode.OK, { pinned ->
            client.post("/api/v2/events/ws-ticket") {
                authScope(pinned!!); requireSiloAuth()
                // Natural-idempotent mint may use ordinary scoped auth refresh.
            }
        }) { ticket ->
            require(validTicket(ticket)) { "The server returned an unsupported realtime ticket." }
            ticket
        }
    }

    fun frames(channels: List<String>): Flow<String> = flow {
        val scope = tokens.snapshotCurrentScope() ?: return@flow
        val mintedAt = TimeSource.Monotonic.markNow()
        val proof = when (val result = ticket(scope)) {
            is ApiResult.Success -> result.data
            is ApiResult.Error -> throw EventsTicketFailure(result.code)
            is ApiResult.NetworkError -> throw EventsTicketFailure(0)
        }
        if (!current(scope) || mintedAt.elapsedNow().inWholeMilliseconds >= proof.expiresIn * 1000L) return@flow
        // Do not retry this upgrade, even on a lost handshake response: its
        // single-use proof may already be consumed. Reconnect collects afresh.
        withTimeoutOrNull(proof.maxConnectionSeconds * 1000L) {
            client.webSocket(request = { eventsUpgrade(scope, proof, channels) }) {
                check(call.response.headers[HttpHeaders.SecWebSocketProtocol] == EVENTS_PROTOCOL)
                if (!current(scope)) return@webSocket
                val session = this
                val authorityWatcher = launch {
                    while (isActive) {
                        delay(1000)
                        if (!current(scope)) {
                            session.close(CloseReason(CloseReason.Codes.NORMAL,"Identity changed"))
                            break
                        }
                    }
                }
                try {
                    send(Frame.Text(SiloJson.encodeToString(WsSubscribe.serializer(),WsSubscribe(channels = channels))))
                    for (frame in incoming) {
                        if (!current(scope)) break
                        if (frame is Frame.Text) this@flow.emit(frame.readText())
                    }
                } finally { authorityWatcher.cancel() }
            }
        }
    }
}

internal class EventsTicketFailure(val code: Int) : Exception("Realtime ticket unavailable")

internal const val EVENTS_PROTOCOL = "silo.events.v2"

internal fun validTicket(ticket: WsTicketResponse): Boolean =
    ticket.protocol == EVENTS_PROTOCOL && ticket.expiresIn in 1..30 && ticket.maxConnectionSeconds in 1..300 &&
        ticket.ticket.isNotEmpty() && ticket.ticket.all { it.isLetterOrDigit() && it.code < 128 || it in "!#$%&'*+-.^_`|~" }

internal fun HttpRequestBuilder.eventsUpgrade(scope: AuthScopeSnapshot, ticket: WsTicketResponse, channels: List<String>) {
    require(validTicket(ticket))
    url {
        takeFrom(scope.serverUrl)
        require(protocol == URLProtocol.HTTP || protocol == URLProtocol.HTTPS)
        require(user == null && password == null)
        protocol = if (protocol == URLProtocol.HTTPS) URLProtocol.WSS else URLProtocol.WS
        encodedPath = "/api/v2/events/ws"
        parameters.clear(); fragment = ""
        parameters.append("channels",channels.joinToString(","))
    }
    // Pin routing but send only the ticket protocols, never bearer/PIN headers.
    authScope(scope); skipSiloAuth(); singleAttempt()
    headers.remove(HttpHeaders.Authorization)
    headers.remove("X-Profile-Id"); headers.remove("X-Profile-Token")
    header(HttpHeaders.SecWebSocketProtocol,"$EVENTS_PROTOCOL, silo.ticket.${ticket.ticket}")
}
