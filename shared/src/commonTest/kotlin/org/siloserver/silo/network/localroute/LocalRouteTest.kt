package org.siloserver.silo.network.localroute

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.siloserver.silo.network.HttpOrigin

class LocalRouteTest {

    @Test
    fun localOriginsArePrivateIPv4OverHttpsOnTheSamePort() {
        assertEquals(HttpOrigin("https", "192.168.1.20", 8080), LocalRoute.localOriginForAddress("http://192.168.1.20:8080"))
        assertEquals(HttpOrigin("https", "10.0.0.5", 443), LocalRoute.localOriginForAddress("https://10.0.0.5"))
        assertEquals(HttpOrigin("https", "172.16.0.9", 80), LocalRoute.localOriginForAddress("http://172.16.0.9"))
        // Host names, public, CGNAT/tailnet, loopback and link-local addresses never qualify.
        listOf(
            "http://nas.local:8080",
            "https://media.example.com",
            "http://100.64.1.2:8080",
            "http://127.0.0.1:8080",
            "http://169.254.1.1:8080",
            "http://172.32.0.1:8080",
            "http://8.8.8.8:8080",
            "http://[fd00::1]:8080",
            "ftp://192.168.1.20",
        ).forEach { assertNull(LocalRoute.localOriginForAddress(it), it) }
    }

    @Test
    fun privateIPv4IsRfc1918Only() {
        assertTrue(LocalRoute.isPrivateIPv4("10.255.255.255"))
        assertTrue(LocalRoute.isPrivateIPv4("172.31.0.1"))
        assertTrue(LocalRoute.isPrivateIPv4("192.168.0.1"))
        assertFalse(LocalRoute.isPrivateIPv4("172.15.0.1"))
        assertFalse(LocalRoute.isPrivateIPv4("192.169.0.1"))
        assertFalse(LocalRoute.isPrivateIPv4("010.0.0.1"))
        assertFalse(LocalRoute.isPrivateIPv4("10.0.0.256"))
        assertFalse(LocalRoute.isPrivateIPv4("10.0.0"))
    }

    @Test
    fun theOfferComesFromLocalRouteInTheConnectionsDocument() {
        val offered = decodeLocalRouteOffer(
            """{"state":"available","allowed":true,"server_id":"srv-1","endpoints":[],""" +
                """"local_route":{"spki_sha256":"pin=","url":"http://192.168.1.20:8080"}}""",
        )
        assertEquals(LocalRouteOffer.Offered("srv-1", "pin=", "http://192.168.1.20:8080"), offered)
        assertEquals(
            LocalRouteOffer.NotOffered("srv-1"),
            decodeLocalRouteOffer("""{"state":"available","server_id":"srv-1","endpoints":[]}"""),
        )
        assertEquals(
            LocalRouteOffer.Unreadable,
            decodeLocalRouteOffer("""{"state":"unavailable","server_id":"srv-1","local_route":{"spki_sha256":"pin="}}"""),
        )
        assertEquals(LocalRouteOffer.Unreadable, decodeLocalRouteOffer("<html>"))
    }

    @Test
    fun droppingTakesOnlyTheRouteThatFailed() {
        val state = LocalRouteState()
        val saved = HttpOrigin("https", "media.example.com", 443)
        val first = LocalRoute("server", saved, HttpOrigin("https", "192.168.1.20", 8080), generation = 1)
        val second = first.copy(generation = 2)
        state.publish(second)
        state.reportFailure(first)
        assertEquals(second, state.active.value)
        state.reportFailure(second)
        assertNull(state.active.value)
    }

    @Test
    fun aKeyMismatchDropsTheRouteAtThatAddressOnly() {
        val state = LocalRouteState()
        val route = LocalRoute(
            "server",
            HttpOrigin("https", "media.example.com", 443),
            HttpOrigin("https", "192.168.1.20", 8080),
            generation = 1,
        )
        state.publish(route)
        state.reportKeyMismatch("192.168.1.21", 8080)
        assertEquals(route, state.active.value)
        state.reportKeyMismatch("192.168.1.20", 8080)
        assertNull(state.active.value)
    }
}
