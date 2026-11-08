package org.siloserver.silo.android.ui.navigation

import kotlin.test.Test
import kotlin.test.assertEquals

class RequestsRouteVisibilityTest {

    @Test
    fun onlyRequestsScreensCloseAndOnlyOnceTheServerSaysRequestsAreOff() {
        val requestsScreens = listOf("requests", Route.RequestDiscoverRow.ROUTE, "requests/mine", Route.RequestDetail.ROUTE)
        for (route in requestsScreens) {
            assertEquals(true, shouldLeaveRequests(route, requestsKnownUnavailable = true), route)
            // Before the first answer, after a reset, or after a failed probe.
            assertEquals(false, shouldLeaveRequests(route, requestsKnownUnavailable = false), route)
        }
        for (route in listOf(Tab.Home.route, null)) {
            assertEquals(false, shouldLeaveRequests(route, requestsKnownUnavailable = true), route.toString())
        }
    }
}
