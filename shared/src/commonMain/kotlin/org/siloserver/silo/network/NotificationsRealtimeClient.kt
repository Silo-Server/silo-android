package org.siloserver.silo.network

import org.siloserver.silo.model.notifications.NotificationReadPayload
import org.siloserver.silo.model.notifications.NotificationRealtime
import org.siloserver.silo.model.notifications.NotificationRow
import org.siloserver.silo.model.notifications.WsFrameEnvelope
import org.siloserver.silo.model.notifications.WsSubscribe
import org.siloserver.silo.network.api.NotificationsApi
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
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
    data class Closed(val reason: String? = null) : NotificationRealtimeEvent()
}

/**
 * Foreground accelerator over the events websocket. One [connect] = one
 * connection: mint a ticket via [NotificationsApi.wsTicket], connect
 * `GET /api/v1/events/ws?ticket=`, await `hello`, send `subscribe`, then map
 * every server frame through [decodeRealtimeFrame] into the returned [Flow].
 * The flow completes (emitting [NotificationRealtimeEvent.Closed]) when the
 * socket ends; reconnect with capped backoff is the repository's job.
 *
 * Behind an interface so the repository's tests use a fake flow instead of a
 * real socket — the only logic worth unit-testing here is the pure
 * [decodeRealtimeFrame], which is fully covered.
 */
interface NotificationsRealtimeClient {
    fun connect(): Flow<NotificationRealtimeEvent>
}

class DefaultNotificationsRealtimeClient(
    private val client: HttpClient,
    private val api: NotificationsApi,
    private val json: Json = SiloJson,
) : NotificationsRealtimeClient {

    override fun connect(): Flow<NotificationRealtimeEvent> = callbackFlow {
        val ticket = when (val r = api.wsTicket()) {
            is ApiResult.Success -> r.data.ticket
            is ApiResult.Error -> {
                trySend(NotificationRealtimeEvent.Closed("ticket_error_${r.code}"))
                close()
                return@callbackFlow
            }
            is ApiResult.NetworkError -> {
                trySend(NotificationRealtimeEvent.Closed("ticket_network_error"))
                close()
                return@callbackFlow
            }
        }

        try {
            client.webSocket(urlString = "/api/v1/events/ws?ticket=$ticket") {
                // Subscribe to the notifications channel once connected. The
                // server sends `hello` first; we don't need to parse it before
                // subscribing (it just must arrive within 5s).
                send(
                    Frame.Text(
                        json.encodeToString(
                            WsSubscribe.serializer(),
                            WsSubscribe(channels = listOf(NotificationRealtime.Channel)),
                        ),
                    ),
                )
                for (frame in incoming) {
                    if (frame !is Frame.Text) continue
                    decodeRealtimeFrame(json, frame.readText())?.let { trySend(it) }
                }
            }
            trySend(NotificationRealtimeEvent.Closed())
        } catch (e: Throwable) {
            trySend(NotificationRealtimeEvent.Closed(e.message))
        } finally {
            close()
        }

        awaitClose { /* socket closes when the flow collector is cancelled */ }
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
