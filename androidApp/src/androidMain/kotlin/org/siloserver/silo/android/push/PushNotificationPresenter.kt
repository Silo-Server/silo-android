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
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.repository.NotificationsRepository

class PushNotificationPresenter internal constructor(
    private val context: Context,
    private val notificationsRepository: NotificationsRepository,
    private val tokenManager: TokenManager,
    private val targets: PushTargetResolver,
) {
    suspend fun present(
        deliveryId: String,
        issuer: PushIssuer? = null,
        fallbackTitle: String? = null,
        fallbackBody: String? = null,
    ) {
        // Fetch before the permission check: on a direct-lookup miss the fallback
        // refreshes the inbox, which keeps the in-app badge current even for a
        // profile that has denied POST_NOTIFICATIONS.
        val (row, attribution) = if (issuer != null) fetchForIssuer(deliveryId, issuer) else fetchForActiveScope(deliveryId)
        if (!canPostNotifications()) return
        ensureChannel()

        // Stamp the identity this notification belongs to. A notification is
        // generated for one profile's inbox on one server, and its route (an
        // item id, or the inbox itself) means something different — or nothing
        // — under another. Without this the tap acted on whoever was signed in
        // when it was opened, which for a PendingIntent can be days later and
        // several profile switches away.
        //
        // The issuer must be established COMPLETELY or not at all. A partial
        // identity is worse than none: a null component is treated as a
        // wildcard at delivery, so a half-attributed notification can act under
        // an identity that never generated it.
        val issuingServerId = attribution?.serverId
        val issuingProfileId = attribution?.profileId
        val attributable = attribution != null

        val content = notificationContentFor(
            // Both sources of text are withheld when we cannot say whose this
            // is. The ROW matters as much as the payload: if the identity moved
            // across the fetch, its series/episode details belong to whoever we
            // just stopped being. An unattributable notification is generic as
            // well as non-navigable.
            row = row.takeIf { attributable },
            fallbackTitle = fallbackTitle.takeIf { attributable },
            fallbackBody = fallbackBody.takeIf { attributable },
        )
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_DELIVERY_ID, deliveryId)
            // Only navigable when we know whose it is. Unattributable ones still
            // post — the user should see the event — but tapping just opens the
            // app rather than acting on someone else's library. An attributed
            // one opens its route only while its server and profile are the
            // active ones (MainActivity); otherwise it opens the app.
            if (attributable) {
                putExtra(EXTRA_NAV_ROUTE, content.route)
                putExtra(EXTRA_SERVER_ID, issuingServerId)
                putExtra(EXTRA_PROFILE_ID, issuingProfileId)
            }
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

    /**
     * A push that names its server and profile is read with that registration's
     * saved login, whichever server and profile are active, and belongs to that
     * identity even when the read fails. One naming a server or profile this
     * device holds no registration and login for is not fetched at all, since
     * any other server or profile would only answer for someone else: it posts
     * generic and non-navigable.
     */
    private suspend fun fetchForIssuer(deliveryId: String, issuer: PushIssuer): FetchedNotification {
        var target: PushTarget? = null
        val row = withinPushBudget {
            val resolved = targets.resolve(issuer) ?: return@withinPushBudget null
            target = resolved
            (notificationsRepository.get(deliveryId, resolved.owner.scope) as? ApiResult.Success)?.data
        }
        val resolved = target ?: return FetchedNotification(row = null, attribution = null)
        // Signed out of that server (or into another account there) during the
        // read: nobody here is left to show it to.
        if (tokenManager.loginSessionId(resolved.serverEntryId) != resolved.owner.loginId) {
            return FetchedNotification(row = null, attribution = null)
        }
        return FetchedNotification(
            row = row?.takeIf { it.profileId.isBlank() || it.profileId == resolved.profileId },
            attribution = PushNotificationAttribution(serverId = resolved.serverEntryId, profileId = resolved.profileId),
        )
    }

    /** A push from a server or relay that predates naming its issuer: read as the active identity. */
    private suspend fun fetchForActiveScope(deliveryId: String): FetchedNotification {
        // Captured BEFORE the fetch, which can take seconds: reading identity
        // afterwards attributed the notification to whatever the user had
        // switched to in the meantime. The scope snapshot (not just the server
        // id) is what detects an A→B→A round trip across the fetch, which a
        // plain id comparison reports as "unchanged".
        val scopeBeforeFetch = tokenManager.snapshotCurrentScope()
        val row = fetch(deliveryId)

        // The fetched row is authoritative for the profile — it IS the row from
        // that profile's inbox. The server is only trusted if the scope did not
        // move across the fetch.
        //
        // KNOWN LIMIT: this payload names no issuing server, and the FCM token
        // stays registered with previously-active servers, so a push from
        // server A arriving while B is active simply misses its lookup — it
        // cannot be attributed to A at all, and is posted non-navigable.
        // Attributing it to B is what this used to do, and is wrong. Servers
        // that name the issuer take [fetchForIssuer] instead.
        val scopeAfterFetch = tokenManager.snapshotCurrentScope()
        return FetchedNotification(
            row = row,
            attribution = pushNotificationAttribution(
                rowProfileId = row?.profileId,
                serverIdBefore = scopeBeforeFetch?.serverId,
                identityGenerationBefore = scopeBeforeFetch?.identityGeneration,
                serverIdAfter = scopeAfterFetch?.serverId,
                identityGenerationAfter = scopeAfterFetch?.identityGeneration,
            ),
        )
    }

    // sync refreshes the inbox for a background_wake push without posting a
    // visible notification.
    suspend fun sync() {
        withinPushBudget { notificationsRepository.refresh() }
    }

    /**
     * Budgeted because the shared client allows a 60s request timeout — far
     * past the window FCM gives us — and a null row here only costs generic
     * notification text, whereas overrunning the window costs the whole
     * notification.
     */
    private suspend fun fetch(deliveryId: String): NotificationRow? =
        withinPushBudget {
            when (val direct = notificationsRepository.get(deliveryId)) {
                is ApiResult.Success -> direct.data
                else -> {
                    notificationsRepository.refresh()
                    notificationsRepository.rows.value.firstOrNull { it.id == deliveryId }
                }
            }
        }

    private fun notificationContentFor(
        row: NotificationRow?,
        fallbackTitle: String?,
        fallbackBody: String?,
    ): PushNotificationContent {
        if (row == null) {
            return PushNotificationContent(
                title = fallbackTitle?.takeIf { it.isNotBlank() } ?: "Silo notification",
                // Deliberately does not promise this specific event is visible
                // under the active identity — it may not be ours to show.
                body = fallbackBody?.takeIf { it.isNotBlank() }
                    ?: "Open Silo to check notifications.",
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

        /** Identity the notification was generated for; see [present]. */
        const val EXTRA_SERVER_ID = "silo_notification_server_id"
        const val EXTRA_PROFILE_ID = "silo_notification_profile_id"
    }
}

private data class FetchedNotification(
    val row: NotificationRow?,
    val attribution: PushNotificationAttribution?,
)

private data class PushNotificationContent(
    val title: String,
    val body: String,
    val route: String,
)

/** A notification's established issuer, or null when it cannot be attributed. */
data class PushNotificationAttribution(val serverId: String, val profileId: String)

/**
 * Establishes who a notification belongs to — completely, or not at all.
 *
 * A partial identity is worse than none: a missing component is a wildcard at
 * delivery, so a half-attributed notification can act under an identity that
 * never generated it.
 *
 * The profile comes from the fetched ROW or nowhere. Falling back to the active
 * profile is the original misattribution: a push issued by server A that
 * arrives while B is active misses its lookup, and the fallback stamped it as
 * B's and navigated into B's library.
 *
 * The scope must also have held across the fetch, which can take seconds.
 * Deliberately compares `serverId + identityGeneration` and NOT
 * `credentialEpoch`: the epoch moves on persistent credential writes, which are
 * not identity changes, so including it would let ordinary token churn make a
 * legitimate notification generic. Comparing generations rather than ids is
 * what catches an A→B→A round trip.
 */
fun pushNotificationAttribution(
    rowProfileId: String?,
    serverIdBefore: String?,
    identityGenerationBefore: Long?,
    serverIdAfter: String?,
    identityGenerationAfter: Long?,
): PushNotificationAttribution? {
    val profileId = rowProfileId?.takeIf { it.isNotBlank() } ?: return null
    val serverId = serverIdBefore?.takeIf { it.isNotBlank() } ?: return null
    if (serverIdAfter == null || identityGenerationBefore == null) return null
    if (serverId != serverIdAfter) return null
    if (identityGenerationBefore != identityGenerationAfter) return null
    return PushNotificationAttribution(serverId = serverId, profileId = profileId)
}
