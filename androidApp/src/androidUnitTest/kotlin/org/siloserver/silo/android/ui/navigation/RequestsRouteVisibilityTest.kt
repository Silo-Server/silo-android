package org.siloserver.silo.android.ui.navigation

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RequestsRouteVisibilityTest {

    @Test
    fun everyRequestsScreenClosesOnceTheServerSaysRequestsAreOff() {
        for (route in listOf("requests", "requests/mine", Route.RequestDetail.ROUTE)) {
            assertTrue(shouldLeaveRequests(route, requestsResolved = true, requestsEnabled = false), route)
        }
    }

    @Test
    fun aRequestsScreenStaysWhileTheProbeHasNotAnswered() {
        // Before the first probe and after a reset the store reads "disabled"
        // by default; a restored Requests screen waits for the real answer.
        assertFalse(shouldLeaveRequests("requests", requestsResolved = false, requestsEnabled = false))
        assertFalse(shouldLeaveRequests(Route.RequestDetail.ROUTE, requestsResolved = false, requestsEnabled = false))
    }

    @Test
    fun otherScreensAndEnabledRequestsAreLeftAlone() {
        assertFalse(shouldLeaveRequests("requests", requestsResolved = true, requestsEnabled = true))
        assertFalse(shouldLeaveRequests(Tab.Home.route, requestsResolved = true, requestsEnabled = false))
        assertFalse(shouldLeaveRequests(null, requestsResolved = true, requestsEnabled = false))
    }
}
