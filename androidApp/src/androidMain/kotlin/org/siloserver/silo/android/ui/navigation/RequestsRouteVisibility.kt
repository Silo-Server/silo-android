package org.siloserver.silo.android.ui.navigation

/** Destination patterns of the Requests screens: hub, My Requests, detail. */
internal val RequestsRoutes: Set<String> = setOf(
    Route.Requests.route,
    Route.MyRequests.route,
    Route.RequestDetail.ROUTE,
)

internal fun isRequestsRoute(route: String?): Boolean = route in RequestsRoutes

/**
 * Whether a Requests screen on top has to close because the server turned
 * Requests off. Only a probe that has answered counts: the store reads
 * "disabled" by default before its first probe and again after every reset,
 * and a restored Requests screen must not close while that probe runs. The
 * TV shell applies the same rule to its Requests tab.
 */
internal fun shouldLeaveRequests(
    currentRoute: String?,
    requestsResolved: Boolean,
    requestsEnabled: Boolean,
): Boolean = isRequestsRoute(currentRoute) && requestsResolved && !requestsEnabled
