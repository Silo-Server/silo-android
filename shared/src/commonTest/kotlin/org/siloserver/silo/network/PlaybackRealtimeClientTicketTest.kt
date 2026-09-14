package org.siloserver.silo.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.notifications.WsTicketResponse
import org.siloserver.silo.network.apiv2.ApiV2Gate
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackRealtimeClientTicketTest {
    private val scope = AuthScopeSnapshot("server", "profile", "https://silo.example.com:8443", "pin-proof", identityGeneration = 1)

    @Test
    fun `control ticket is minted against the active server`() = runTest {
        val impl = TokenManagerImpl().apply { setServerUrl("https://silo.example.com:8443"); saveTokens("access", "refresh", 3600) }
        val tokenManager = object : TokenManager by impl { override suspend fun snapshotCurrentScope() = scope }
        var captured: HttpRequestData? = null
        val client = HttpClient(MockEngine { request ->
            captured = request
            // Refuse the mint so the flow closes before any websocket upgrade is attempted.
            respond("""{"type":"about:blank","title":"nope","status":503}""", HttpStatusCode.ServiceUnavailable,
                headersOf(HttpHeaders.ContentType, "application/problem+json"))
        }) {
            install(ContentNegotiation) { json(SiloJson) }
            install(SiloAuthPlugin) { this.tokenManager = tokenManager }
        }
        try {
            val realtime = DefaultPlaybackRealtimeClient(client, tokenManager, gate = ApiV2Gate.Unrestricted,
                ownerProvider = { scope to "install-1" })
            realtime.connect("session-1").first()
            assertEquals("silo.example.com", captured?.url?.host)
            assertEquals(8443, captured?.url?.port)
            assertEquals("/api/v2/playback/sessions/session-1/control/ws-ticket", captured?.url?.encodedPath)
        } finally { client.close() }
    }

    @Test
    fun `control upgrade targets the session socket under the server origin`() {
        val request = HttpRequestBuilder().apply {
            playbackControlUpgrade(scope, "session-1", WsTicketResponse("opaque-proof", 30, 300, PLAYBACK_CONTROL_PROTOCOL))
        }.build()
        assertEquals("wss://silo.example.com:8443/api/v2/playback/sessions/session-1/control/ws", request.url.toString())
    }
}
