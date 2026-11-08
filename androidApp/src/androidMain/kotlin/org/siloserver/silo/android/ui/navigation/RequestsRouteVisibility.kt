package org.siloserver.silo.android.ui.navigation

/** Destination patterns of the Requests screens: hub, a Discover row's See all, My Requests, detail. */
internal val RequestsRoutes: Set<String> = setOf(
    Route.Requests.route,
    Route.RequestDiscoverRow.ROUTE,
    Route.MyRequests.route,
    Route.RequestDetail.ROUTE,
)

internal fun isRequestsRoute(route: String?): Boolean = route in RequestsRoutes

/**
 * Whether a Requests screen on top has to close because the server turned
 * Requests off. Only an answer counts
 * ([org.siloserver.silo.model.feature.RequestsFeatureStore.isKnownUnavailable]):
 * the store reads "disabled" by default before its first probe, again after
 * every reset, and keeps its previous answer when a probe fails, so a restored
 * Requests screen never closes while a probe runs or the server is unreachable.
 * The TV shell applies the same rule to its Requests screens.
 */
internal fun shouldLeaveRequests(
    currentRoute: String?,
    requestsKnownUnavailable: Boolean,
): Boolean = isRequestsRoute(currentRoute) && requestsKnownUnavailable
