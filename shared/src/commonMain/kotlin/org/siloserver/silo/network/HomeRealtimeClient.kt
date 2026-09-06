package org.siloserver.silo.network

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.catch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.siloserver.silo.model.notifications.WsFrameEnvelope
import org.siloserver.silo.model.notifications.WsSubscribe

/** `user_state.changed` payload (server: user_state_events.go). */
@Serializable
data class UserStateChange(
    @SerialName("profile_id") val profileId: String,
    @SerialName("content_id") val contentId: String? = null,
    @SerialName("series_id") val seriesId: String? = null,
    /** progress | favorite | watchlist | history | watched | home_dismissal */
    val change: String,
)

sealed class HomeRealtimeEvent {
    /**
     * The `subscribed` frame arrived. Snapshots are null for user_state and
     * catalog, so this is the reconnect catch-up point: trigger one REST
     * reconcile for anything missed while disconnected.
     */
    object Connected : HomeRealtimeEvent()

    /** `user_state.changed` — account-scoped; filter by active profile. */
    data class UserState(val change: UserStateChange) : HomeRealtimeEvent()

    /** Any `catalog` channel event — library surface changed, refetch Home. */
    object CatalogChanged : HomeRealtimeEvent()

    /** The socket closed (or failed to connect). The coordinator reconnects. */
    data class Closed(val reason: String? = null) : HomeRealtimeEvent()
}

/**
 * One captured v2 ticket/socket attempt. The repository owns reconnect;
 * the shared transport fences authority before delivering frames.
 */
interface HomeRealtimeClient {
    fun connect(): Flow<HomeRealtimeEvent>
}

class DefaultHomeRealtimeClient(
    private val socket: org.siloserver.silo.network.apiv2.EventsSocketV2Api,
    private val json: Json = SiloJson,
) : HomeRealtimeClient {

    override fun connect(): Flow<HomeRealtimeEvent> = socket.frames(listOf(CHANNEL_USER_STATE, CHANNEL_CATALOG))
        .mapNotNull { decodeHomeRealtimeFrame(json, it) }
        .onCompletion { cause -> if (cause == null) emit(HomeRealtimeEvent.Closed()) }
        .catch { error ->
            if (error is kotlinx.coroutines.CancellationException) throw error
            val reason = if (error is org.siloserver.silo.network.apiv2.EventsTicketFailure)
                "ticket_error_${error.code}" else "socket_error"
            emit(HomeRealtimeEvent.Closed(reason))
        }

    companion object {
        const val CHANNEL_USER_STATE = "user_state"
        const val CHANNEL_CATALOG = "catalog"
    }
}

/**
 * Pure decode of one server frame into a [HomeRealtimeEvent], or null for
 * frames we don't surface (hello, foreign channels, null snapshots, unknown
 * events, malformed JSON). Never throws.
 */
fun decodeHomeRealtimeFrame(json: Json, raw: String): HomeRealtimeEvent? {
    val envelope = try {
        json.decodeFromString(WsFrameEnvelope.serializer(), raw)
    } catch (_: Exception) {
        return null
    }

    return when (envelope.type) {
        "subscribed" -> HomeRealtimeEvent.Connected
        "event" -> when (envelope.channel) {
            DefaultHomeRealtimeClient.CHANNEL_USER_STATE -> {
                if (envelope.event != "user_state.changed") return null
                val obj = envelope.data as? JsonObject ?: return null
                val change = try {
                    json.decodeFromJsonElement(UserStateChange.serializer(), obj)
                } catch (_: Exception) {
                    return null
                }
                HomeRealtimeEvent.UserState(change)
            }
            DefaultHomeRealtimeClient.CHANNEL_CATALOG -> HomeRealtimeEvent.CatalogChanged
            else -> null
        }
        else -> null // hello, snapshot (always null for these channels), error
    }
}

/**
 * Should this event trigger a Home refetch? user_state events are
 * account-scoped (all profiles of the account), so foreign-profile changes
 * are dropped; an unknown active profile refreshes rather than risk staleness.
 */
fun homeRefreshTrigger(event: HomeRealtimeEvent, activeProfileId: String?): Boolean = when (event) {
    is HomeRealtimeEvent.Connected -> true
    is HomeRealtimeEvent.CatalogChanged -> true
    is HomeRealtimeEvent.UserState ->
        activeProfileId == null || event.change.profileId == activeProfileId
    is HomeRealtimeEvent.Closed -> false
}
