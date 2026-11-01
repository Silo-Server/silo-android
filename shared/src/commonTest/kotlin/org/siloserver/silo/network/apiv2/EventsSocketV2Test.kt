package org.siloserver.silo.network.apiv2

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.notifications.WsTicketResponse
import org.siloserver.silo.network.*
import kotlin.test.*

class EventsSocketV2Test {
    private var scope = AuthScopeSnapshot("server","profile","https://example.invalid", "pin-proof",identityGeneration=1)
    private val tokens = object : TokenManager by TokenManagerImpl() { override suspend fun snapshotCurrentScope() = scope }
    private val wire = """{"ticket":"opaque-proof","expires_in":30,"max_connection_seconds":300,"protocol":"silo.events.v2"}"""

    @Test fun mintPinsOptionalProfileAndRejectsLatePinAuthority() = runTest {
        var replace = false
        val c = HttpClient(MockEngine {
            assertEquals("/api/v2/events/ws-ticket",it.url.encodedPath)
            assertEquals(scope,it.attributes[AuthScopeAttributeKey])
            assertTrue(it.attributes[RequireSiloAuthAttributeKey])
            assertFalse(it.attributes.contains(SingleAttemptAttributeKey))
            if (replace) scope = scope.copy(profileToken = "replacement-proof")
            respond(wire,HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
        })
        try {
            val api = EventsSocketV2Api(c, tokens, ApiV2Gate.Unrestricted)
            assertIs<ApiResult.Success<WsTicketResponse>>(api.ticket())
            replace = true
            assertEquals("identity_changed",assertIs<ApiResult.Error>(api.ticket()).error)
            val prior = scope
            scope = scope.copy(profileId = null,profileToken = null)
            assertFalse(api.current(prior))
            replace = false
            assertIs<ApiResult.Success<WsTicketResponse>>(api.ticket())
        } finally { c.close() }
    }

    @Test fun ticketRefusalsPreserveRetryMetadataAndAdmissionCapability() = runTest {
        var status = HttpStatusCode.TooManyRequests
        val c = HttpClient(MockEngine {
            val body = if (status == HttpStatusCode.OK) wire.dropLast(1) +
                ",\"work_admission\":{\"version\":1,\"backend\":\"memory\",\"scope\":\"node\",\"epoch\":\"synthetic\"}}"
            else """{"type":"https://example.invalid/problems/dependency_unavailable","code":"realtime_unavailable","title":"Wait","status":${status.value},"retry_after_ms":4500,"work_started":false}"""
            respond(body, status, headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.RetryAfter to listOf("6")))
        })
        try {
            val api = EventsSocketV2Api(c, tokens, ApiV2Gate.Unrestricted)
            for (refusalStatus in listOf(HttpStatusCode.TooManyRequests, HttpStatusCode.ServiceUnavailable)) {
                status = refusalStatus
                val error = assertIs<ApiResult.Error>(api.ticket())
                assertEquals(refusalStatus.value, error.code)
                assertEquals("realtime_unavailable", error.error)
                assertEquals(6_000L, error.retryAfterMs)
            }
            status = HttpStatusCode.OK
            val proof = assertIs<ApiResult.Success<WsTicketResponse>>(api.ticket()).data
            assertTrue(proof.workAdmission?.supported == true)
            assertEquals("synthetic", proof.workAdmission?.epoch)
        } finally { c.close() }
    }

    @Test fun frameConsumerUpgradeHasNoDeclarationButObserverUrlStillCanDeclare() {
        val proof = WsTicketResponse("opaque-proof", 30, 300, EVENTS_PROTOCOL)
        val frameConsumer = HttpRequestBuilder().apply { eventsUpgrade(scope, proof, null) }.build()
        assertFalse(frameConsumer.url.parameters.contains("channels"))
        val observer = HttpRequestBuilder().apply { eventsUpgrade(scope, proof, listOf("catalog")) }.build()
        assertEquals("catalog", observer.url.parameters["channels"])
    }

    @Test fun upgradeUsesExactOrderedProtocolsAndNoUrlOrBearerCredentials() {
        val request = HttpRequestBuilder().apply {
            eventsUpgrade(scope,WsTicketResponse("opaque-proof",30,300,EVENTS_PROTOCOL),listOf("user_state","catalog"))
        }.build()
        assertEquals("wss://example.invalid/api/v2/events/ws?channels=user_state%2Ccatalog",request.url.toString())
        assertEquals("silo.events.v2, silo.ticket.opaque-proof",request.headers[HttpHeaders.SecWebSocketProtocol])
        assertNull(request.headers[HttpHeaders.Authorization]); assertNull(request.headers["X-Profile-Token"])
        assertTrue(request.attributes[SkipSiloAuthAttributeKey]); assertTrue(request.attributes[SingleAttemptAttributeKey])
        assertEquals(scope,request.attributes[AuthScopeAttributeKey])
        assertFalse(validTicket(WsTicketResponse("unsafe,proof",30,300,EVENTS_PROTOCOL)))
        assertFalse(validTicket(WsTicketResponse("proof",0,300,EVENTS_PROTOCOL)))
        assertFalse(validTicket(WsTicketResponse("proof",30,300,"legacy")))
    }

    @Test fun bothConsumersMintFreshProofOnEachReconnectWithoutUpgradeReplay() = runTest {
        var mints = 0
        var upgrades = 0
        val c = HttpClient(MockEngine {
            if (it.url.encodedPath.endsWith("ws-ticket")) {
                mints++
                respond(wire.replace("opaque-proof","proof-$mints"),HttpStatusCode.OK,headersOf(HttpHeaders.ContentType,"application/json"))
            } else {
                upgrades++
                assertFalse(it.url.parameters.contains("ticket"))
                respond("refused",HttpStatusCode.Forbidden)
            }
        }) { install(WebSockets) }
        try {
            val api = EventsSocketV2Api(c, tokens, ApiV2Gate.Unrestricted)
            val home = DefaultHomeRealtimeClient(api)
            val notifications = DefaultNotificationsRealtimeClient(api)
            repeat(2) { assertIs<HomeRealtimeEvent.Closed>(home.connect().toList().last()) }
            repeat(2) { assertIs<NotificationRealtimeEvent.Closed>(notifications.connect().toList().last()) }
            assertEquals(4,mints)
            assertTrue(upgrades <= 4) // MockEngine may refuse WebSocket capability before dispatch.
        } finally { c.close() }
    }
}
