package org.siloserver.silo.common.network

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.siloserver.silo.network.AccessChangeSignals
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.CleartextOriginConsent
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.network.apiv2.EventsSocketV2Api
import org.siloserver.silo.network.canonicalHttpOrigin
import org.siloserver.silo.network.createSiloClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The events socket's access-change notice (a frame, then close code 4001)
 * reaches [AccessChangeSignals] once per connection, and a server that never
 * sends it leaves the signals untouched.
 */
class EventsSocketAccessChangeTest {

    @Test
    fun `access_changed frame and 4001 close report one access change`() = runBlocking {
        val frames = connectOnce { socket ->
            socket.send("""{"type":"access_changed"}""")
            socket.close(4001, "access_changed")
        }

        assertEquals(1L, frames.signals.revision.value)
        assertTrue(frames.received.any { it.contains("access_changed") })
    }

    @Test
    fun `a 4001 close alone reports the access change`() = runBlocking {
        val frames = connectOnce { socket -> socket.close(4001, "access_changed") }

        assertEquals(1L, frames.signals.revision.value)
    }

    @Test
    fun `an ordinary close reports nothing`() = runBlocking {
        val frames = connectOnce { socket ->
            socket.send("""{"type":"subscribed","channel":"catalog"}""")
            socket.close(1000, "bye")
        }

        assertEquals(0L, frames.signals.revision.value)
    }

    private class Outcome(val signals: AccessChangeSignals, val received: List<String>)

    /** [afterSubscribe] runs once the client's subscribe frame arrives, as the server's recheck would. */
    private suspend fun connectOnce(afterSubscribe: (WebSocket) -> Unit): Outcome {
        val server = MockWebServer()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"ticket":"opaque-proof","expires_in":20,"max_connection_seconds":300,"protocol":"silo.events.v2"}"""),
        )
        server.enqueue(
            MockResponse()
                .withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onMessage(webSocket: WebSocket, text: String) = afterSubscribe(webSocket)
                        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                            webSocket.close(code, reason)
                        }
                    },
                )
                .addHeader("Sec-WebSocket-Protocol", "silo.events.v2"),
        )
        server.start()
        val signals = AccessChangeSignals()
        val tokens = scopedTokens(server.url("/").toString())
        val approvedOrigin = canonicalHttpOrigin(server.url("/").toString())
        val httpClient = createSiloClient(
            tokenManager = tokens,
            cleartextOriginConsent = object : CleartextOriginConsent {
                override suspend fun isApproved(origin: String): Boolean = origin == approvedOrigin
            },
            accessChangeSignals = signals,
        )
        try {
            val received = withTimeout(10_000) {
                EventsSocketV2Api(httpClient, tokens, ApiV2Gate.Unrestricted, signals)
                    .frames(listOf("catalog"))
                    .toList()
            }
            return Outcome(signals, received)
        } finally {
            httpClient.close()
            server.shutdown()
        }
    }

    private suspend fun scopedTokens(serverUrl: String): TokenManager {
        val delegate = TokenManagerImpl().apply {
            setServerUrl(serverUrl)
            saveTokens("ACCESS", "refresh", 3_600)
            setProfileIdentity("profile-1", "PROFILE_TOKEN")
        }
        val active = AuthScopeSnapshot(
            serverId = "server-1",
            profileId = "profile-1",
            serverUrl = serverUrl,
            profileToken = "PROFILE_TOKEN",
        )
        return object : TokenManager by delegate {
            override suspend fun snapshotCurrentScope(): AuthScopeSnapshot = active
            override suspend fun getAccessTokenForScope(scope: AuthScopeSnapshot): String? =
                "ACCESS".takeIf { scope == active }
        }
    }
}
