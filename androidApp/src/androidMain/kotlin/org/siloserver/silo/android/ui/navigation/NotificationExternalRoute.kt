package org.siloserver.silo.android.ui.navigation

/**
 * A notification's navigation request, accepted only when it says whose it is.
 *
 * The identity is validated HERE as well as stamped at post time, because the
 * delivery side must not trust an Intent it merely received. Two ways an
 * unattributed one can arrive: a notification posted by a build before the
 * extras existed, and an explicit Intent crafted against the exported Activity.
 * Both used to produce `Identity(null, null)`, which matches every identity —
 * so the route ran against whoever happened to be signed in.
 *
 * The identity GENERATION is deliberately not carried, unlike an in-process
 * route captured while the app is running. That counter restarts at zero in
 * every process, so a notification tapped after the app has been killed would
 * be compared against a generation that means something different — and a
 * perfectly legitimate notification would be refused. Notifications are
 * therefore pinned by server and profile only, and remain deliverable across a
 * sign-out and back in to the same account. Closing that would need a durable
 * identity epoch rather than a process-local counter.
 *
 * One that names its saved account ([accountId]) opens its tile: delivery
 * switches to that server, account and profile first. One posted before
 * accounts keeps the server and profile pinning.
 */
fun notificationExternalRouteOrNull(
    route: String?,
    serverId: String?,
    profileId: String?,
    accountId: String? = null,
): Pair<String, ExternalRouteScope>? {
    val usableRoute = route?.takeIf { it.isNotBlank() } ?: return null
    val usableServerId = serverId?.takeIf { it.isNotBlank() } ?: return null
    val usableProfileId = profileId?.takeIf { it.isNotBlank() } ?: return null
    return usableRoute to ExternalRouteScope.Identity(
        serverId = usableServerId,
        profileId = usableProfileId,
        // Explicit: see the note above on why a process-local generation must
        // not be persisted into a PendingIntent.
        identityGeneration = null,
        accountId = accountId?.takeIf { it.isNotBlank() },
        switchesToTile = !accountId.isNullOrBlank(),
    )
}
