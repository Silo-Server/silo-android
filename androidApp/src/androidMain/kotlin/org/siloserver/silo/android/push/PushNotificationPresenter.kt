package org.siloserver.silo.android.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.siloserver.silo.android.MainActivity
import org.siloserver.silo.android.R
import org.siloserver.silo.android.ui.navigation.Route
import org.siloserver.silo.model.notifications.NotificationRow
import org.siloserver.silo.model.notifications.NotificationType
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.NotificationsRepository

class PushNotificationPresenter(
    private val context: Context,
    private val notificationsRepository: NotificationsRepository,
) {
    suspend fun present(
        deliveryId: String,
        fallbackTitle: String? = null,
        fallbackBody: String? = null,
    ) {
        val row = fetch(deliveryId)
        if (!canPostNotifications()) return
        ensureChannel()

        val content = notificationContentFor(
            row = row,
            fallbackTitle = fallbackTitle,
            fallbackBody = fallbackBody,
        )
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_DELIVERY_ID, deliveryId)
            putExtra(EXTRA_NAV_ROUTE, content.route)
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.body))
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    deliveryId.hashCode(),
                    contentIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(deliveryId.hashCode(), notification)
        }
    }

    // sync refreshes the inbox for a background_wake push without posting a
    // visible notification.
    suspend fun sync() {
        runCatching { notificationsRepository.refresh() }
    }

    private suspend fun fetch(deliveryId: String): NotificationRow? =
        when (val direct = notificationsRepository.get(deliveryId)) {
            is ApiResult.Success -> direct.data
            else -> runCatching {
                notificationsRepository.refresh()
                notificationsRepository.rows.value.firstOrNull { it.id == deliveryId }
            }.getOrNull()
        }

    private fun notificationContentFor(
        row: NotificationRow?,
        fallbackTitle: String?,
        fallbackBody: String?,
    ): PushNotificationContent {
        if (row == null) {
            return PushNotificationContent(
                title = fallbackTitle?.takeIf { it.isNotBlank() } ?: "Silo notification",
                body = fallbackBody?.takeIf { it.isNotBlank() } ?: "Open Silo to view it.",
                route = Route.Inbox.route,
            )
        }

        val episodeTag = row.seasonNumber?.let { season ->
            row.episodeNumber?.let { episode -> "S${season}E$episode" }
        }
        val route = notificationRouteFor(row)
        return when (row.type) {
            NotificationType.EpisodeAvailable -> PushNotificationContent(
                title = row.seriesTitle.ifBlank { "New episode available" },
                body = listOfNotNull(
                    episodeTag,
                    row.episodeTitle.ifBlank { null },
                ).joinToString(" - ").ifBlank { "Open Silo to watch." },
                route = route,
            )
            NotificationType.RequestFulfilled -> PushNotificationContent(
                title = "Request fulfilled",
                body = row.episodeTitle.ifBlank { null }
                    ?: row.seriesTitle.ifBlank { null }
                    ?: "Your requested title is ready.",
                route = route,
            )
            NotificationType.Unknown -> PushNotificationContent(
                title = fallbackTitle?.takeIf { it.isNotBlank() }
                    ?: row.rawType.substringBefore('.')
                        .replace('_', ' ')
                        .replaceFirstChar { it.uppercase() }
                        .ifBlank { "Silo notification" },
                body = fallbackBody?.takeIf { it.isNotBlank() }
                    ?: row.episodeTitle.ifBlank { null }
                    ?: row.seriesTitle.ifBlank { null }
                    ?: "Open Silo to view it.",
                route = route,
            )
        }
    }

    private fun notificationRouteFor(row: NotificationRow): String =
        row.episodeId?.takeIf { it.isNotBlank() }
            ?.let { Route.ItemDetail(it).route }
            ?: row.seriesId?.takeIf { it.isNotBlank() }?.let { Route.ItemDetail(it).route }
            ?: Route.Inbox.route

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Notifications",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Private Silo notification alerts"
        }
        manager.createNotificationChannel(channel)
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val CHANNEL_ID = "silo_notifications"
        const val EXTRA_DELIVERY_ID = "silo_notification_delivery_id"
        const val EXTRA_NAV_ROUTE = "silo_notification_nav_route"
    }
}

private data class PushNotificationContent(
    val title: String,
    val body: String,
    val route: String,
)
