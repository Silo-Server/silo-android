package org.siloserver.silo.android.push

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.os.Bundle
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.siloserver.silo.android.ui.navigation.Route
import org.siloserver.silo.model.notifications.NotificationPreferencesUpdate
import org.siloserver.silo.model.notifications.NotificationRow
import org.siloserver.silo.model.server.ServerEntry
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.api.NotificationsApi
import org.siloserver.silo.repository.NotificationsRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A push that names its server and profile is read from that saved server and
 * opens only under it; one that names them incompletely, or whose server does
 * not return it, is never read from the active server instead and opens nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PushMessageHandlerTest {
    private val context: Application = RuntimeEnvironment.getApplication()
    private val cabin = ServerEntry(id = "cabin", url = "https://cabin.example", verifiedServerId = "deploy-cabin")
    private val servers = SavedServers(cabin).apply {
        installations = listOf(registeredInstallation(cabin, profileId = "adult", receiptServerId = "deploy-cabin"))
    }

    /** The saved server each inbox read went to; null for the active one. */
    private val readFrom = mutableListOf<String?>()
    private var answering = true
    private var deliveries = 0

    private val handler = PushMessageHandler(
        PushNotificationPresenter(
            context = context,
            notificationsRepository = NotificationsRepository(Inbox()),
            tokenManager = object : TokenManager by TokenManagerImpl() {
                override suspend fun loginSessionId(serverId: String) = servers.logins[serverId]
            },
            targets = servers.resolver(),
        ),
    )

    @Test
    fun `a push for another saved server is read there and opens only under its server and profile`() = runTest {
        handler.handle(push("silo_server_id" to "deploy-cabin", "silo_profile_id" to "adult"))

        assertEquals(listOf<String?>("cabin"), readFrom)
        val tap = tapExtras(posted().single())
        assertEquals("cabin", tap.getString(PushNotificationPresenter.EXTRA_SERVER_ID))
        assertEquals("adult", tap.getString(PushNotificationPresenter.EXTRA_PROFILE_ID))
        assertEquals(Route.ItemDetail("episode-1").route, tap.getString(PushNotificationPresenter.EXTRA_NAV_ROUTE))
    }

    @Test
    fun `an incompletely named push, or one its server does not return, opens nothing`() = runTest {
        handler.handle(push("silo_server_id" to "deploy-cabin"))
        handler.handle(push("silo_server_id" to "deploy-cabin", "silo_profile_id" to " "))
        assertEquals(emptyList(), readFrom, "never read from the active server instead")

        answering = false
        handler.handle(push("silo_server_id" to "deploy-cabin", "silo_profile_id" to "adult"))

        val notifications = posted()
        assertEquals(3, notifications.size)
        for (notification in notifications) {
            assertNull(tapExtras(notification).getString(PushNotificationPresenter.EXTRA_NAV_ROUTE))
            assertEquals("Silo notification", notification.extras.getString(Notification.EXTRA_TITLE))
        }
    }

    private fun push(vararg routing: Pair<String, String>) =
        mapOf(PushMessageHandler.SILO_DELIVERY_ID_KEY to "delivery-${++deliveries}") + routing

    private fun posted(): List<Notification> =
        shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications

    private fun tapExtras(notification: Notification): Bundle =
        checkNotNull(shadowOf(notification.contentIntent).savedIntent.extras)

    private inner class Inbox(private val scope: AuthScopeSnapshot? = null) : NotificationsApi {
        override fun forScope(scope: AuthScopeSnapshot): NotificationsApi = Inbox(scope)
        override suspend fun get(id: String): ApiResult<NotificationRow> {
            readFrom += scope?.serverId
            if (!answering || scope == null) return ApiResult.Error(404, "not_found", "")
            return ApiResult.Success(
                NotificationRow(
                    id = id, rawType = "episode.available", profileId = scope.profileId.orEmpty(),
                    episodeId = "episode-1", seriesTitle = "Show", createdAt = "2026-10-12T00:00:00Z",
                ),
            )
        }
        override suspend fun list(limit: Int, unreadOnly: Boolean, before: String?) = unused()
        override suspend fun sync(since: String?, limit: Int) = unused()
        override suspend fun unreadCount() = unused()
        override suspend fun markRead(id: String) = unused()
        override suspend fun markAllRead(through: String) = unused()
        override suspend fun getPreferences() = unused()
        override suspend fun updatePreferences(update: NotificationPreferencesUpdate) = unused()
        override suspend fun capability() = unused()
        private fun unused() = ApiResult.Error(0, "unused", "")
    }
}
