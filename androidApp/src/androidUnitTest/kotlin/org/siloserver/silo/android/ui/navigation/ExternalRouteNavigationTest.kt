package org.siloserver.silo.android.ui.navigation

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ExternalRouteNavigationTest {
    @Test
    fun contentRouteWaitsForAuthenticationAndIsDeliveredOnce() = runTest {
        val events = mutableListOf<String>()
        val destinations = flow {
            emit(Route.Login.route)
            emit(Route.ProfileSelection.route)
            emit(Route.Home.route)
            error("external-route collector remained active after the eligible destination")
        }

        consumeExternalRouteOnce(
            pendingExternalRoute = ExternalRouteRequest(
                generation = 1,
                route = "player/movie-tmdb-463015",
            ),
            currentDestinationRoutes = destinations,
            navigate = { route -> events += "navigate:$route" },
            onConsumed = { request -> events += "consumed:${request.generation}" },
        )

        assertEquals(
            listOf("navigate:player/movie-tmdb-463015", "consumed:1"),
            events,
        )
    }

    @Test
    fun inviteClaimCanNavigateFromTheSignedOutGraph() = runTest {
        val navigated = mutableListOf<String>()

        consumeExternalRouteOnce(
            pendingExternalRoute = ExternalRouteRequest(
                generation = 1,
                route = "invite_claim?server=example&token=test-token",
            ),
            currentDestinationRoutes = flowOf(Route.Login.route),
            navigate = navigated::add,
            onConsumed = {},
        )

        assertEquals(listOf("invite_claim?server=example&token=test-token"), navigated)
    }

    @Test
    fun blankRouteDoesNotSubscribeOrNavigate() = runTest {
        var navigations = 0

        consumeExternalRouteOnce(
            pendingExternalRoute = ExternalRouteRequest(generation = 1, route = " "),
            currentDestinationRoutes = flow { error("blank route must not collect destinations") },
            navigate = { navigations++ },
            onConsumed = { error("blank route must not be consumed") },
        )

        assertEquals(0, navigations)
    }

    @Test
    fun repeatedIdenticalRoutesReceiveDistinctMonotonicIdentities() {
        val requests = ExternalRouteRequestFactory()

        val first = requests.create("player/movie-tmdb-463015")
        val second = requests.create("player/movie-tmdb-463015")

        assertEquals(first.route, second.route)
        assertNotEquals(first.generation, second.generation)
        assertEquals(first.generation + 1, second.generation)
    }

    @Test
    fun staleConsumptionDoesNotClearANewerRequest() {
        val requests = ExternalRouteRequestFactory()
        val first = requests.create("player/movie-tmdb-463015")
        val second = requests.create("player/movie-tmdb-463015")

        assertEquals(
            second,
            clearConsumedExternalRouteRequest(
                pendingRequest = second,
                consumedRequest = first,
            ),
        )
        assertNull(
            clearConsumedExternalRouteRequest(
                pendingRequest = second,
                consumedRequest = second,
            ),
        )
    }

    @Test
    fun exactCurrentTargetIsConsumedWithoutRenavigating() = runTest {
        val request = ExternalRouteRequest(
            generation = 4,
            route = "player/movie-tmdb-463015",
        )
        val events = mutableListOf<String>()

        consumeExternalRouteOnce(
            pendingExternalRoute = request,
            currentDestinationRoutes = flowOf(Route.Player.ROUTE),
            isAlreadyAtRoute = { route -> route == request.route },
            navigate = { route -> events += "navigate:$route" },
            onConsumed = { consumed -> events += "consumed:${consumed.generation}" },
        )

        assertEquals(listOf("consumed:4"), events)
    }

    @Test
    fun differentPlayerTargetReplacesTheCurrentPlayerEntry() {
        assertEquals(
            true,
            shouldReplaceCurrentPlayer(
                currentDestinationRoute = Route.Player.ROUTE,
                targetRoute = "player/movie-tmdb-463015?quality=original",
            ),
        )
        assertEquals(
            false,
            shouldReplaceCurrentPlayer(
                currentDestinationRoute = Route.Home.route,
                targetRoute = "player/movie-tmdb-463015?quality=original",
            ),
        )
    }
}
