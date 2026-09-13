package org.siloserver.silo.network.api

import org.siloserver.silo.model.notifications.NotificationCapability
import org.siloserver.silo.model.notifications.NotificationListResponse
import org.siloserver.silo.model.notifications.NotificationPreferences
import org.siloserver.silo.model.notifications.NotificationPreferencesUpdate
import org.siloserver.silo.model.notifications.NotificationRow
import org.siloserver.silo.model.notifications.NotificationSyncResponse
import org.siloserver.silo.model.notifications.UnreadCountResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot

/**
 * Profile-scoped notifications inbox, preferences, and capability. Behind an
 * interface so the repository and its
 * tests can fake the transport (matching CalendarApi/SubtitlesApi). Production
 * binds [org.siloserver.silo.network.apiv2.NotificationsV2Api].
 *
 * REST is the source of truth for the notifications feature; the websocket
 * ([org.siloserver.silo.network.NotificationsRealtimeClient]) is a foreground
 * accelerator that takes its handshake ticket from
 * [org.siloserver.silo.network.apiv2.EventsSocketV2Api].
 */
interface NotificationsApi {

    /** Returns a transport frozen to [scope]; fakes may return themselves. */
    fun forScope(scope: AuthScopeSnapshot): NotificationsApi = this

    /** GET /api/v2/notifications — newest-first page; [before] pages into the past. */
    suspend fun list(
        limit: Int = 25,
        unreadOnly: Boolean = false,
        before: String? = null,
    ): ApiResult<NotificationListResponse>

    /** GET /api/v2/notifications/sync — ascending catch-up from [since]; adds unread_count. */
    suspend fun sync(
        since: String? = null,
        limit: Int = 50,
    ): ApiResult<NotificationSyncResponse>

    /** GET /api/v2/notifications/{id} — 404 for other profiles' rows. */
    suspend fun get(id: String): ApiResult<NotificationRow>

    /** GET /api/v2/notifications/unread-count. */
    suspend fun unreadCount(): ApiResult<UnreadCountResponse>

    /** POST /api/v2/notifications/{id}/read — 204, idempotent. */
    suspend fun markRead(id: String): ApiResult<Unit>

    /** POST /api/v2/notifications/read-all — 204; marks rows created at or before [through]. */
    suspend fun markAllRead(through: String): ApiResult<Unit>

    /** GET /api/v2/notifications/preferences. */
    suspend fun getPreferences(): ApiResult<NotificationPreferences>

    /** PUT /api/v2/notifications/preferences — partial; returns full prefs. */
    suspend fun updatePreferences(update: NotificationPreferencesUpdate): ApiResult<NotificationPreferences>

    /** GET /api/v2/notifications/capabilities — drives the settings UI. */
    suspend fun capability(): ApiResult<NotificationCapability>
}
