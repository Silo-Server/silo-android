package org.siloserver.silo.network

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.*
import org.siloserver.silo.model.notifications.WsTicketResponse
import kotlin.test.*

class PlaybackControlV2Test {
    private val owner = AuthScopeSnapshot("server", "profile", "https://example.invalid/old?token=old", "pin")
    private val ticket = WsTicketResponse("single-use-proof", 30, 14400, PLAYBACK_CONTROL_PROTOCOL)

    @Test fun upgradeUsesCapturedOriginAndTicketOnlyWithOneAttempt() {
        val request = HttpRequestBuilder().apply {
            header(HttpHeaders.Authorization, "Bearer old")
            header("X-Profile-Id", "old-profile")
            header("X-Profile-Token", "old-pin")
            playbackControlUpgrade(owner, "session-1", ticket)
        }.build()
        assertEquals("wss://example.invalid/api/v2/playback/sessions/session-1/control/ws", request.url.toString())
        assertEquals("$PLAYBACK_CONTROL_PROTOCOL, silo.ticket.single-use-proof", request.headers[HttpHeaders.SecWebSocketProtocol])
        assertNull(request.headers[HttpHeaders.Authorization])
        assertNull(request.headers["X-Profile-Id"])
        assertNull(request.headers["X-Profile-Token"])
        assertEquals(owner, request.attributes[AuthScopeAttributeKey])
        assertTrue(request.attributes[SingleAttemptAttributeKey])
    }

    @Test fun rejectsWrongProtocolExpiredTicketAndHeaderInjection() {
        assertTrue(validPlaybackControlTicket(ticket))
        assertFalse(validPlaybackControlTicket(ticket.copy(protocol = "silo.events.v2")))
        assertFalse(validPlaybackControlTicket(ticket.copy(expiresIn = 0)))
        assertFalse(validPlaybackControlTicket(ticket.copy(maxConnectionSeconds = 14401)))
        assertFalse(validPlaybackControlTicket(ticket.copy(ticket = "proof, injected")))
    }
}
