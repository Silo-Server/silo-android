// shared/src/commonMain/kotlin/org/siloserver/silo/model/notifications/NotificationModels.kt
package org.siloserver.silo.model.notifications

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Delivery `type` registry. The server contract is explicitly extensible
 * (dispatcher.go / release_types.go) — clients MUST render unrecognized types
 * with a generic fallback, so [NotificationRow.type] is parsed leniently and
 * the raw string is always retained in [NotificationRow.rawType].
 */
enum class NotificationType {
    EpisodeAvailable,
    RequestFulfilled,
    Unknown,
    ;

    companion object {
        const val EpisodeAvailableWire = "episode.available"
        const val RequestFulfilledWire = "request.fulfilled"

        fun fromWire(wire: String): NotificationType = when (wire) {
            EpisodeAvailableWire -> EpisodeAvailable
            RequestFulfilledWire -> RequestFulfilled
            else -> Unknown
        }
    }
}

/** Realtime channel + event-name constants mirrored from the server. */
object NotificationRealtime {
    const val Channel = "notifications"
    const val EventCreated = "notification.created"
    const val EventRead = "notification.read"
    const val ActionSubscribe = "subscribe"
}

/**
 * Typed convenience view over [NotificationRow.reasonFlags] (release_types.go
 * `ReasonFlags`). Unknown keys in the wire object are ignored here; the raw
 * [JsonObject] on the row is the source of truth.
 */
@Serializable
data class NotificationReasonFlags(
    val favorite: Boolean = false,
    val watchlist: Boolean = false,
    @SerialName("continue_watching") val continueWatching: Boolean = false,
    @SerialName("next_up") val nextUp: Boolean = false,
) {
    val any: Boolean get() = favorite || watchlist || continueWatching || nextUp

    companion object {
        /** Lenient read straight off a wire [JsonObject] (never throws). */
        fun from(flags: JsonObject): NotificationReasonFlags = NotificationReasonFlags(
            favorite = flags.bool("favorite"),
            watchlist = flags.bool("watchlist"),
            continueWatching = flags.bool("continue_watching"),
            nextUp = flags.bool("next_up"),
        )

        private fun JsonObject.bool(key: String): Boolean =
            (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
    }
}

/**
 * One inbox delivery row — the shape shared by the inbox list/sync/get APIs,
 * the websocket snapshot, and `notification.created` events
 * (dispatcher.go `DeliveryRowPayload`).
 *
 * [type] is the parsed enum; [rawType] preserves the original wire string so a
 * generic fallback card can label unknown types. [reasonFlags] is kept as a
 * raw [JsonObject] (defaults to empty `{}`, matching the server) so unknown
 * future flag keys never break decoding; [reasonFlagsTyped] is the convenience
 * view.
 */
@Serializable
data class NotificationRow(
    val id: String,
    @SerialName("type") val rawType: String,
    @SerialName("profile_id") val profileId: String,
    @Serializable(with = NotificationLibraryIdSerializer::class)
    @SerialName("library_id") val libraryId: String? = null,
    @SerialName("series_id") val seriesId: String? = null,
    @SerialName("episode_id") val episodeId: String? = null,
    @SerialName("series_title") val seriesTitle: String = "",
    @SerialName("episode_title") val episodeTitle: String = "",
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("episode_number") val episodeNumber: Int? = null,
    @SerialName("poster_path") val posterPath: String = "",
    @SerialName("poster_url") val posterUrl: String = "",
    @SerialName("poster_thumbhash") val posterThumbhash: String = "",
    @SerialName("reason_flags") val reasonFlags: JsonObject = JsonObject(emptyMap()),
    @SerialName("created_at") val createdAt: String,
    @SerialName("read_at") val readAt: String? = null,
) {
    val type: NotificationType get() = NotificationType.fromWire(rawType)

    val reasonFlagsTyped: NotificationReasonFlags get() = NotificationReasonFlags.from(reasonFlags)

    val isRead: Boolean get() = !readAt.isNullOrBlank()
}

/** GET /api/v2/notifications — newest-first page. */
@Serializable
data class NotificationListResponse(
    val notifications: List<NotificationRow> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("read_cutoff") val readCutoff: String? = null,
)

/** GET /api/v2/notifications/sync — ascending catch-up; adds unread_count. */
@Serializable
data class NotificationSyncResponse(
    val notifications: List<NotificationRow> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
    @SerialName("unread_count") val unreadCount: Int = 0,
    @SerialName("sync_cursor") val syncCursor: String? = null,
    @SerialName("initial_snapshot") val initialSnapshot: Boolean = false,
    @SerialName("has_more") val hasMore: Boolean = false,
)

/** GET /api/v2/notifications/unread-count. */
@Serializable
data class UnreadCountResponse(val count: Int = 0)

/** Ticket fields for a single realtime connection. */
@Serializable
data class WsTicketResponse(
    val ticket: String,
    @SerialName("expires_in") val expiresIn: Int = 0,
    @SerialName("max_connection_seconds") val maxConnectionSeconds: Int = 0,
    val protocol: String = "",
)

/** GET/PUT /api/v2/notifications/preferences (full row). */
@Serializable
data class NotificationPreferences(
    @SerialName("profile_id") val profileId: String = "",
    val enabled: Boolean = true,
    @SerialName("notify_favorites") val notifyFavorites: Boolean = true,
    @SerialName("notify_watchlist") val notifyWatchlist: Boolean = true,
    @SerialName("notify_continue_watching") val notifyContinueWatching: Boolean = true,
    @SerialName("notify_next_up") val notifyNextUp: Boolean = true,
)

/**
 * Partial PUT body. Only set (non-null) fields are sent — with
 * `explicitNulls = false` the nulls are omitted, matching the server's
 * pointer-field "omitted keeps current value" semantics.
 */
@Serializable
data class NotificationPreferencesUpdate(
    val enabled: Boolean? = null,
    @SerialName("notify_favorites") val notifyFavorites: Boolean? = null,
    @SerialName("notify_watchlist") val notifyWatchlist: Boolean? = null,
    @SerialName("notify_continue_watching") val notifyContinueWatching: Boolean? = null,
    @SerialName("notify_next_up") val notifyNextUp: Boolean? = null,
)

/** GET /api/v2/notifications/capabilities — drives the settings UI. */
@Serializable
data class NotificationCapability(
    @SerialName("in_app") val inApp: CapabilityInApp = CapabilityInApp(),
    @SerialName("apple_push") val applePush: CapabilityPush = CapabilityPush(),
    @SerialName("android_push") val androidPush: CapabilityPush = CapabilityPush(),
    @SerialName("web_push") val webPush: CapabilityWebPush = CapabilityWebPush(),
    val webhooks: CapabilityWebhooks = CapabilityWebhooks(),
    val email: CapabilityAccountChannel = CapabilityAccountChannel(),
    val discord: CapabilityAccountChannel = CapabilityAccountChannel(),
)

@Serializable
data class CapabilityInApp(val enabled: Boolean = false)

@Serializable
data class CapabilityPush(
    val available: Boolean = false,
    val provider: String = "off",
    @SerialName("supported_modes") val supportedModes: List<String> = emptyList(),
)

@Serializable
data class CapabilityWebPush(
    val available: Boolean = false,
    @SerialName("public_key") val publicKey: String = "",
)

@Serializable
data class CapabilityWebhooks(
    val available: Boolean = false,
    @SerialName("max_per_profile") val maxPerProfile: Int = 0,
    @SerialName("supported_types") val supportedTypes: List<String> = emptyList(),
)

@Serializable
data class CapabilityAccountChannel(
    val available: Boolean = false,
    val modes: List<String> = emptyList(),
    @SerialName("digest_hour") val digestHour: Int = 0,
)

// ---- Realtime frames (events websocket) -----------------------------------

/** Server `hello` frame (events_ws.go / events/types.go EventsHelloMessage). */
@Serializable
data class WsHello(
    val type: String = "hello",
    @SerialName("schema_version") val schemaVersion: Int = 0,
    @SerialName("connection_id") val connectionId: String = "",
    @SerialName("available_channels") val availableChannels: List<String> = emptyList(),
    @SerialName("required_action") val requiredAction: String = "",
)

/** Client `subscribe` frame (sent after hello, within 5s). */
@Serializable
data class WsSubscribe(
    val type: String = "subscribe",
    @SerialName("request_id") val requestId: String? = null,
    val channels: List<String> = emptyList(),
)

@Serializable
data class WsRejectedChannel(
    val channel: String = "",
    val code: String = "",
    val message: String = "",
)

/** Server `subscribed` ack frame. */
@Serializable
data class WsSubscribed(
    val type: String = "subscribed",
    @SerialName("request_id") val requestId: String? = null,
    val channels: List<String> = emptyList(),
    val rejected: List<WsRejectedChannel> = emptyList(),
)

/**
 * Generic server frame envelope. The realtime decoder (Task 4) dispatches on
 * [type]/[event] and parses [data] (a raw [JsonElement]: a DeliveryRowPayload
 * array for snapshots, a single row for `notification.created`, or
 * `{profile_id,id}` / `{profile_id,all:true}` for `notification.read`).
 */
@Serializable
data class WsFrameEnvelope(
    val type: String = "",
    val channel: String? = null,
    val event: String? = null,
    @SerialName("event_id") val eventId: String? = null,
    val timestamp: String? = null,
    val data: JsonElement? = null,
)

/** `notification.read` data payload: a single id, or all=true. */
@Serializable
data class NotificationReadPayload(
    @SerialName("profile_id") val profileId: String = "",
    val id: String? = null,
    val all: Boolean = false,
)

/** REST v2 uses strings; the retained realtime bridge can still send integer library IDs. */
internal object NotificationLibraryIdSerializer : kotlinx.serialization.KSerializer<String> {
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor("NotificationLibraryId", kotlinx.serialization.descriptors.PrimitiveKind.STRING)
    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): String {
        val value = (decoder as kotlinx.serialization.json.JsonDecoder).decodeJsonElement() as? JsonPrimitive
            ?: throw kotlinx.serialization.SerializationException("Invalid notification library ID")
        if (!value.isString && value.content.toLongOrNull() == null)
            throw kotlinx.serialization.SerializationException("Invalid notification library ID")
        return value.content
    }
    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: String) = encoder.encodeString(value)
}
