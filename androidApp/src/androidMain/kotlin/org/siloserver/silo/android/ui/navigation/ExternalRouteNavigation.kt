package org.siloserver.silo.android.ui.navigation

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** A single external-navigation delivery, distinct even when its route repeats. */
class ExternalRouteRequest internal constructor(
    val generation: Long,
    val route: String,
)

internal class ExternalRouteRequestFactory {
    private var latestGeneration = 0L

    fun create(route: String): ExternalRouteRequest =
        ExternalRouteRequest(
            generation = ++latestGeneration,
            route = route,
        )
}

internal fun clearConsumedExternalRouteRequest(
    pendingRequest: ExternalRouteRequest?,
    consumedRequest: ExternalRouteRequest,
): ExternalRouteRequest? =
    if (pendingRequest?.generation == consumedRequest.generation) null else pendingRequest

internal fun shouldReplaceCurrentPlayer(
    currentDestinationRoute: String?,
    targetRoute: String,
): Boolean =
    currentDestinationRoute == Route.Player.ROUTE && targetRoute.startsWith("player/")

private val preAuthenticationDestinationRoutes = setOf(
    Route.Login.route,
    Route.ServerSetup.route,
    Route.ServerList.route,
    Route.Setup.route,
    Route.Signup.route,
    Route.ProfileSelection.route,
    Route.CreateProfile.route,
    Route.EditProfile.ROUTE,
    Route.PairDevice.ROUTE,
    Route.InviteClaim.ROUTE,
    Route.OnboardingTour.route,
)

/**
 * Waits until [pendingExternalRoute] is allowed from the current graph, then
 * delivers it exactly once. [first] ends the back-stack subscription before
 * [navigate] can emit the destination it just added; navigating from inside a
 * long-lived collector otherwise feeds that new entry back into the same route.
 */
internal suspend fun consumeExternalRouteOnce(
    pendingExternalRoute: ExternalRouteRequest?,
    currentDestinationRoutes: Flow<String?>,
    isAlreadyAtRoute: (String) -> Boolean = { false },
    navigate: (String) -> Unit,
    onConsumed: (ExternalRouteRequest) -> Unit,
) {
    val request = pendingExternalRoute ?: return
    val route = request.route.takeIf { it.isNotBlank() } ?: return
    val isPreAuthenticationTarget = route.startsWith("invite_claim")

    currentDestinationRoutes.first { currentRoute ->
        isPreAuthenticationTarget || currentRoute !in preAuthenticationDestinationRoutes
    }
    if (!isAlreadyAtRoute(route)) {
        navigate(route)
    }
    onConsumed(request)
}
