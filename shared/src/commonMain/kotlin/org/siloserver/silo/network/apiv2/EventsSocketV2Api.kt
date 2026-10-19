package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.call.body
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.siloserver.silo.model.notifications.WsFrameEnvelope
import org.siloserver.silo.model.notifications.WsSubscribe
import org.siloserver.silo.model.notifications.WsTicketResponse
import org.siloserver.silo.network.*
import kotlin.time.TimeSource

/** One collection mints one proof and makes one upgrade attempt; callers own reconnect. */
class EventsSocketV2Api(
    private val client: HttpClient,
    private val tokens: TokenManager,
    private val gate: ApiV2Gate,
    /** Told when the server reports that this viewer's access changed. */
    private val accessChanges: AccessChangeSignals? = null,
    private val nowMs: () -> Long = { clockOrigin.elapsedNow().inWholeMilliseconds },
) {
    internal suspend fun current(scope: AuthScopeSnapshot): Boolean = scope.stillOwns(tokens, OwnerPolicy.FULL)

    internal fun newAttempt() = GenericEventsAttempt(nowMs)

    suspend fun ticket(scope: AuthScopeSnapshot? = null): ApiResult<WsTicketResponse> = mintTicket(scope, null)

    private suspend fun mintTicket(scope: AuthScopeSnapshot?, attempt: GenericEventsAttempt?): ApiResult<WsTicketResponse> {
        val owner = scope ?: tokens.snapshotCurrentScope() ?: return identityChanged()
        return ownedV2Call<WsTicketResponse, WsTicketResponse>(gate, tokens, owner, OwnerPolicy.FULL, HttpStatusCode.OK, { pinned ->
            client.post("/api/v2/events/ws-ticket") {
                authScope(pinned!!); requireSiloAuth()
                // Natural-idempotent mint may use ordinary scoped auth refresh.
            }.also { response ->
                if (!response.status.isSuccess()) attempt?.retryAfterMs = genericEventsRetryAfter(response.headers[HttpHeaders.RetryAfter])
            }
        }) { ticket ->
            require(validTicket(ticket)) { "The server returned an unsupported realtime ticket." }
            ticket
        }
    }

    fun frames(channels: List<String>): Flow<String> = flow {
        frames(channels, newAttempt()).collect { emit(it) }
    }

    internal fun frames(channels: List<String>, attempt: GenericEventsAttempt): Flow<String> = flow {
        try {
            val scope = tokens.snapshotCurrentScope() ?: return@flow
            val mintedAt = nowMs()
            val proof = when (val result = mintTicket(scope, attempt)) {
                is ApiResult.Success -> result.data
                is ApiResult.Error -> throw EventsTicketFailure(result.code)
                is ApiResult.NetworkError -> throw EventsTicketFailure(0)
            }
            if (!current(scope) || nowMs() - mintedAt >= proof.expiresIn * 1000L) return@flow
            // Taken after the mint, so the ticket's policy is no newer than this.
            val connectedAt = accessChanges?.connectionTime()
            // Even a lost handshake response may have consumed the proof. Never replay it.
            withTimeoutOrNull(proof.maxConnectionSeconds * 1000L) {
                client.prepareRequest { eventsUpgrade(scope, proof, channels) }.execute { response ->
                    // Read refusal headers before Ktor converts the response to a session.
                    if (response.status != HttpStatusCode.SwitchingProtocols) {
                        attempt.retryAfterMs = genericEventsRetryAfter(response.headers[HttpHeaders.RetryAfter])
                        throw EventsTicketFailure(response.status.value)
                    }
                    val session = response.body<DefaultClientWebSocketSession>()
                    try {
                        check(response.headers[HttpHeaders.SecWebSocketProtocol] == EVENTS_PROTOCOL)
                        if (current(scope)) session.collectFrames(scope, channels, connectedAt, attempt) { emit(it) }
                    } finally {
                        try { session.close() } finally { session.cancel() }
                    }
                }
            }
        } finally { attempt.ended() }
    }

    private suspend fun DefaultClientWebSocketSession.collectFrames(
        scope: AuthScopeSnapshot,
        channels: List<String>,
        connectedAt: Long?,
        attempt: GenericEventsAttempt,
        deliver: suspend (String) -> Unit,
    ) = coroutineScope {
        val session = this@collectFrames
        val authorityWatcher = launch {
            while (isActive) {
                delay(1000)
                if (!current(scope)) {
                    session.close(CloseReason(CloseReason.Codes.NORMAL, "Identity changed"))
                    break
                }
            }
        }
        // Frame plus 4001 close is one authority refresh, with a fresh ticket next time.
        suspend fun reportAccessChange() {
            if (attempt.accessChanged || !current(scope)) return
            attempt.accessChanged = true
            accessChanges?.reportAccessChanged(connectedAt)
        }
        try {
            send(Frame.Text(SiloJson.encodeToString(WsSubscribe.serializer(), WsSubscribe(channels = channels))))
            var identityLost = false
            for (frame in incoming) {
                if (!current(scope)) {
                    identityLost = true
                    break
                }
                if (frame is Frame.Text) {
                    val text = frame.readText()
                    attempt.frame(text)
                    if (isAccessChangedFrame(text)) reportAccessChange()
                    deliver(text)
                }
            }
            // Close-reason/cleanup waits are not accepted subscription uptime.
            attempt.ended()
            if (!identityLost && isAccessChangedClose(closeReasonIfClosed())) reportAccessChange()
        } finally { attempt.ended(); authorityWatcher.cancel() }
    }

    private companion object { val clockOrigin = TimeSource.Monotonic.markNow() }
}

internal class EventsTicketFailure(val code: Int) : Exception("Realtime ticket unavailable")

/** Whether [raw] is the server's `{"type":"access_changed"}` notice. */
internal fun isAccessChangedFrame(raw: String): Boolean = try {
    SiloJson.decodeFromString(WsFrameEnvelope.serializer(), raw).type == AccessChangeSignals.ACCESS_CHANGED_FRAME_TYPE
} catch (_: Exception) {
    false
}

/** Whether the server closed the socket because the viewer's access changed. */
internal fun isAccessChangedClose(reason: CloseReason?): Boolean =
    reason?.code == AccessChangeSignals.ACCESS_CHANGED_CLOSE_CODE

/**
 * The close reason once the incoming channel has ended. A peer that drops the
 * connection without a close frame leaves none, which reads as null here.
 */
private suspend fun DefaultClientWebSocketSession.closeReasonIfClosed(): CloseReason? =
    try {
        withTimeoutOrNull(CLOSE_REASON_WAIT_MS) { closeReason.await() }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

private const val CLOSE_REASON_WAIT_MS = 1_000L

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
