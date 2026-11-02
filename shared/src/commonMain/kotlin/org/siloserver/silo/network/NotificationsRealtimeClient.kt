package org.siloserver.silo.network

import org.siloserver.silo.model.notifications.NotificationReadPayload
import org.siloserver.silo.model.notifications.NotificationRealtime
import org.siloserver.silo.model.notifications.NotificationRow
import org.siloserver.silo.model.notifications.WsFrameEnvelope
import org.siloserver.silo.model.notifications.WsSubscribe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.catch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/**
 * A realtime event the repository folds into its StateFlows. The repository
 * owns reconnect; this client manages a single connection attempt and emits a
 * terminal [Closed] when the socket ends.
 */
sealed class NotificationRealtimeEvent {
    /** Initial `snapshot` frame: <=25 recent unread rows for the bound profile. */
    data class Snapshot(val rows: List<NotificationRow>) : NotificationRealtimeEvent()

    /** `notification.created`: one new delivery row. */
    data class Created(val row: NotificationRow) : NotificationRealtimeEvent()

    /** `notification.read` for a single id (cross-device coherence). */
    data class Read(val id: String) : NotificationRealtimeEvent()

    /** `notification.read` with all=true. */
    object ReadAll : NotificationRealtimeEvent()

    /** A signed read cutoff cannot be compared with rounded public timestamps. */
    object Invalidate : NotificationRealtimeEvent()

    /** The socket closed (or failed to connect). The repository reconnects. */
    data class Closed(val reason: String? = null, val retryAfterMs: Long? = null) : NotificationRealtimeEvent()
}

/**
 * One captured v2 ticket/socket attempt. The repository owns reconnect;
 * the shared transport fences authority before delivering frames.
 */
interface NotificationsRealtimeClient {
    fun connect(): Flow<NotificationRealtimeEvent>
}

class DefaultNotificationsRealtimeClient internal constructor(
    private val frames: (List<String>, RealtimeBackoff) -> Flow<String>,
    private val json: Json = SiloJson,
    private val recovery: RealtimeBackoff = RealtimeBackoff(),
) : NotificationsRealtimeClient {
    constructor(
        socket: org.siloserver.silo.network.apiv2.EventsSocketV2Api,
        json: Json = SiloJson,
        recovery: RealtimeBackoff = RealtimeBackoff(),
    ) : this(socket::frames, json, recovery)

    override fun connect(): Flow<NotificationRealtimeEvent> = frames(listOf(NotificationRealtime.Channel), recovery)
        .mapNotNull { decodeRealtimeFrame(json, it) }
        .onCompletion { cause ->
            recovery.endAttempt()
            if (cause == null) emit(NotificationRealtimeEvent.Closed(retryAfterMs = recovery.disconnected()))
        }
        .catch { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            val reason = if (error is org.siloserver.silo.network.apiv2.EventsTicketFailure)
                "ticket_error_${error.code}" else "socket_error"
            val ticket = error as? org.siloserver.silo.network.apiv2.EventsTicketFailure
            val wait = recovery.disconnected(
                minimumMs = ticket?.retryAfterMs ?: 0,
                unavailableFailure = ticket?.code == 503,
                inputShield = (error as? RealtimeSocketClosed)?.inputShield == true,
            )
            emit(NotificationRealtimeEvent.Closed(reason, wait))
        }

}

/**
 * Pure decode of one server frame's raw JSON text into a
 * [NotificationRealtimeEvent], or null when the frame is not a notifications
 * event we surface (hello/subscribed/error frames, other channels, unknown
 * event names, or malformed JSON). Never throws — this is the load-bearing,
 * fully-tested logic; socket I/O above is kept thin and untested.
 */
fun decodeRealtimeFrame(json: Json, raw: String): NotificationRealtimeEvent? {
    val envelope = try {
        json.decodeFromString(WsFrameEnvelope.serializer(), raw)
    } catch (_: Exception) {
        return null
    }

    return when (envelope.type) {
        "snapshot" -> {
            if (envelope.channel != NotificationRealtime.Channel) return null
            val array = envelope.data as? JsonArray ?: return null
            val rows = try {
                json.decodeFromJsonElement(
                    ListSerializer(NotificationRow.serializer()),
                    array,
                )
            } catch (_: Exception) {
                return null
            }
            NotificationRealtimeEvent.Snapshot(rows)
        }
        "event" -> {
            if (envelope.channel != NotificationRealtime.Channel) return null
            when (envelope.event) {
                NotificationRealtime.EventCreated -> {
                    val obj = envelope.data as? JsonObject ?: return null
                    val row = try {
                        json.decodeFromJsonElement(NotificationRow.serializer(), obj)
                    } catch (_: Exception) {
                        return null
                    }
                    NotificationRealtimeEvent.Created(row)
                }
                NotificationRealtime.EventRead -> {
                    val obj = envelope.data as? JsonObject ?: return null
                    if ("through_created_at" in obj || "through_id" in obj) return NotificationRealtimeEvent.Invalidate
                    val payload = try {
                        json.decodeFromJsonElement(NotificationReadPayload.serializer(), obj)
                    } catch (_: Exception) {
                        return null
                    }
                    when {
                        payload.all -> NotificationRealtimeEvent.ReadAll
                        !payload.id.isNullOrBlank() -> NotificationRealtimeEvent.Read(payload.id)
                        else -> null
                    }
                }
                else -> null // unknown / future event names
            }
        }
        else -> null // hello, subscribed, error, etc.
    }
}
