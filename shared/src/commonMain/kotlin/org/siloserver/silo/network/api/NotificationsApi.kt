package org.siloserver.silo.network.api

import org.siloserver.silo.model.notifications.NotificationCapability
import org.siloserver.silo.model.notifications.NotificationListResponse
import org.siloserver.silo.model.notifications.NotificationPreferences
import org.siloserver.silo.model.notifications.NotificationPreferencesUpdate
import org.siloserver.silo.model.notifications.NotificationRow
import org.siloserver.silo.model.notifications.NotificationSyncResponse
import org.siloserver.silo.model.notifications.UnreadCountResponse
import org.siloserver.silo.model.notifications.WsTicketResponse
import org.siloserver.silo.network.ApiResult
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/**
 * Profile-scoped notifications inbox, preferences, capability, and the
 * websocket handshake ticket. Behind an interface so the repository and its
 * tests can fake the transport (matching CalendarApi/SubtitlesApi).
 *
 * REST is the source of truth for the notifications feature; the websocket
 * ([org.siloserver.silo.network.NotificationsRealtimeClient]) is a foreground
 * accelerator that reuses [wsTicket] for its handshake.
 */
interface NotificationsApi {

    /** GET /api/v1/notifications — newest-first page; [before] pages into the past. */
    suspend fun list(
        limit: Int = 25,
        unreadOnly: Boolean = false,
        before: String? = null,
    ): ApiResult<NotificationListResponse>

    /** GET /api/v1/notifications/sync — ascending catch-up from [since]; adds unread_count. */
    suspend fun sync(
        since: String? = null,
        limit: Int = 50,
    ): ApiResult<NotificationSyncResponse>

    /** GET /api/v1/notifications/{id} — 404 for other profiles' rows. */
    suspend fun get(id: String): ApiResult<NotificationRow>

    /** GET /api/v1/notifications/unread-count. */
    suspend fun unreadCount(): ApiResult<UnreadCountResponse>

    /** POST /api/v1/notifications/{id}/read — 204, idempotent. */
    suspend fun markRead(id: String): ApiResult<Unit>

    /** POST /api/v1/notifications/read-all — 204. */
    suspend fun markAllRead(): ApiResult<Unit>
    suspend fun markAllRead(through: String): ApiResult<Unit> = markAllRead()

    /** GET /api/v1/notifications/preferences. */
    suspend fun getPreferences(): ApiResult<NotificationPreferences>

    /** PUT /api/v1/notifications/preferences — partial; returns full prefs. */
    suspend fun updatePreferences(update: NotificationPreferencesUpdate): ApiResult<NotificationPreferences>

    /** GET /api/v1/notifications/capability — drives the settings UI. */
    suspend fun capability(): ApiResult<NotificationCapability>

    /** POST /api/v1/events/ws-ticket — single-use short-lived websocket ticket. */
    suspend fun wsTicket(): ApiResult<WsTicketResponse>
}

class DefaultNotificationsApi(private val client: HttpClient) : NotificationsApi {

    override suspend fun list(
        limit: Int,
        unreadOnly: Boolean,
        before: String?,
    ): ApiResult<NotificationListResponse> = safeApiCall {
        client.get("/api/v1/notifications") {
            parameter("limit", limit)
            if (unreadOnly) parameter("status", "unread")
            before?.let { parameter("before", it) }
        }
    }

    override suspend fun sync(
        since: String?,
        limit: Int,
    ): ApiResult<NotificationSyncResponse> = safeApiCall {
        client.get("/api/v1/notifications/sync") {
            parameter("limit", limit)
            since?.let { parameter("since", it) }
        }
    }

    override suspend fun get(id: String): ApiResult<NotificationRow> = safeApiCall {
        client.get("/api/v1/notifications/$id")
    }

    override suspend fun unreadCount(): ApiResult<UnreadCountResponse> = safeApiCall {
        client.get("/api/v1/notifications/unread-count")
    }

    override suspend fun markRead(id: String): ApiResult<Unit> = safeApiCall {
        client.post("/api/v1/notifications/$id/read")
    }

    override suspend fun markAllRead(): ApiResult<Unit> = safeApiCall {
        client.post("/api/v1/notifications/read-all")
    }

    override suspend fun getPreferences(): ApiResult<NotificationPreferences> = safeApiCall {
        client.get("/api/v1/notifications/preferences")
    }

    override suspend fun updatePreferences(
        update: NotificationPreferencesUpdate,
    ): ApiResult<NotificationPreferences> = safeApiCall {
        client.put("/api/v1/notifications/preferences") {
            contentType(ContentType.Application.Json)
            setBody(update)
        }
    }

    override suspend fun capability(): ApiResult<NotificationCapability> = safeApiCall {
        client.get("/api/v1/notifications/capability")
    }

    override suspend fun wsTicket(): ApiResult<WsTicketResponse> = safeApiCall {
        client.post("/api/v1/events/ws-ticket")
    }
}
