package org.siloserver.silo.network

import kotlinx.serialization.json.Json
import org.siloserver.silo.model.watchtogether.*

/** IDs identify attempts, without promising idempotency or server replay storage. */
data class RoomWorkRequest(
    val kind: RoomWorkKind,
    val requestId: String,
    val sessionId: String = "",
    val positionSeconds: Double = 0.0,
    val isPaused: Boolean = true,
    val commandId: String? = null,
    val isReady: Boolean = false,
    val action: String = "",
) {
    internal fun encode(json: Json): String = when (kind) {
        RoomWorkKind.Attach -> json.encodeToString(WsAttachSession.serializer(), WsAttachSession(sessionId = sessionId, requestId = requestId))
        RoomWorkKind.Ready -> json.encodeToString(WsReady.serializer(), WsReady(sessionId = sessionId, positionSeconds = positionSeconds, isPaused = isPaused, commandId = commandId, requestId = requestId))
        RoomWorkKind.Report -> json.encodeToString(WsStateReport.serializer(), WsStateReport(sessionId = sessionId, positionSeconds = positionSeconds, isPaused = isPaused, commandId = commandId, isReady = isReady.takeIf { it && !commandId.isNullOrBlank() }, requestId = requestId))
        RoomWorkKind.Buffering -> json.encodeToString(WsBuffering.serializer(), WsBuffering(sessionId = sessionId, positionSeconds = positionSeconds, isPaused = isPaused, requestId = requestId))
        RoomWorkKind.Transport -> json.encodeToString(WsTransportRequest.serializer(), WsTransportRequest(action = action, positionSeconds = positionSeconds, isPaused = isPaused, requestId = requestId))
        RoomWorkKind.Lobby -> json.encodeToString(WsLobbyReady.serializer(), WsLobbyReady(ready = isReady, requestId = requestId))
    }
}

enum class RoomWorkKind(val actionClass: Boolean = false) {
    Attach, Ready, Report, Buffering, Transport(true), Lobby(true),
}
