package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.siloserver.silo.model.notifications.*
import org.siloserver.silo.network.*
import org.siloserver.silo.network.api.NotificationsApi

@Serializable
private data class InboxPageV2(val items: List<NotificationRow>, val page: PageInfo,
    @SerialName("read_cutoff") val readCutoff: String)
@Serializable
private data class InboxSyncV2(val items: List<NotificationRow>, val page: PageInfo,
    @SerialName("sync_cursor") val syncCursor: String,
    @SerialName("unread_count") val unreadCount: Int,
    @SerialName("initial_snapshot") val initialSnapshot: Boolean)
@Serializable
private data class InboxReadThroughV2(val through: String)

/** Each repository operation freezes one viewer; mutations never use auth-interceptor replay. */
class NotificationsV2Api(
    private val client: HttpClient,
    private val tokens: TokenManager,
    private val gate: ApiV2Gate,
    private val captured: AuthScopeSnapshot? = null,
) : NotificationsApi {
    override fun forScope(scope: AuthScopeSnapshot) = NotificationsV2Api(client, tokens, gate, scope)

    private suspend inline fun <reified T, R> exchange(
        method: HttpMethod, path: String, noinline configure: HttpRequestBuilder.() -> Unit = {},
        crossinline project: (T) -> R,
    ): ApiResult<R> {
        val scope = captured ?: tokens.snapshotCurrentScope() ?: return identityChanged()
        return ownedV2Call<T, R>(gate, tokens, scope, OwnerPolicy.IDENTITY,
            if (T::class == Unit::class) HttpStatusCode.NoContent else HttpStatusCode.OK, { owner ->
            client.request(path) {
                this.method = method
                authScope(owner!!); requireSiloAuth()
                if (method != HttpMethod.Get) singleAttempt()
                configure()
            }
        }) { project(it) }
    }
    private suspend inline fun <reified T> exchange(
        method: HttpMethod, path: String, noinline configure: HttpRequestBuilder.() -> Unit = {},
    ): ApiResult<T> = exchange<T, T>(method, path, configure) { it }

    override suspend fun list(limit: Int, unreadOnly: Boolean, before: String?): ApiResult<NotificationListResponse> =
        exchange<InboxPageV2, NotificationListResponse>(HttpMethod.Get, "/api/v2/notifications", {
            parameter("limit", limit); parameter("status", if (unreadOnly) "unread" else "all")
            before?.let { parameter("cursor", it) }
        }) {
            require(it.readCutoff.isNotBlank() && (it.page.hasMore == !it.page.nextCursor.isNullOrBlank()))
            NotificationListResponse(it.items, it.page.nextCursor, it.readCutoff)
        }
    override suspend fun sync(since: String?, limit: Int): ApiResult<NotificationSyncResponse> =
        exchange<InboxSyncV2, NotificationSyncResponse>(HttpMethod.Get, "/api/v2/notifications/sync", {
            parameter("limit", limit); since?.let { parameter("cursor", it) }
        }) {
            require(it.syncCursor.isNotBlank() && (it.page.hasMore == !it.page.nextCursor.isNullOrBlank()))
            NotificationSyncResponse(it.items, it.page.nextCursor, it.unreadCount,
                it.syncCursor, it.initialSnapshot, it.page.hasMore)
        }
    override suspend fun get(id: String): ApiResult<NotificationRow> =
        exchange(HttpMethod.Get, "/api/v2/notifications/${id.encodeURLPathPart()}")
    override suspend fun unreadCount(): ApiResult<UnreadCountResponse> =
        exchange(HttpMethod.Get, "/api/v2/notifications/unread-count")
    override suspend fun markRead(id: String): ApiResult<Unit> =
        exchange(HttpMethod.Post, "/api/v2/notifications/${id.encodeURLPathPart()}/read")
    override suspend fun markAllRead(through: String): ApiResult<Unit> =
        exchange(HttpMethod.Post, "/api/v2/notifications/read-all") {
            contentType(ContentType.Application.Json); setBody(InboxReadThroughV2(through))
        }
    override suspend fun getPreferences(): ApiResult<NotificationPreferences> =
        exchange(HttpMethod.Get, "/api/v2/notifications/preferences")
    override suspend fun updatePreferences(update: NotificationPreferencesUpdate): ApiResult<NotificationPreferences> =
        exchange(HttpMethod.Put, "/api/v2/notifications/preferences") {
            contentType(ContentType.Application.Json); setBody(update)
        }
    override suspend fun capability(): ApiResult<NotificationCapability> =
        exchange(HttpMethod.Get, "/api/v2/notifications/capabilities")
}
