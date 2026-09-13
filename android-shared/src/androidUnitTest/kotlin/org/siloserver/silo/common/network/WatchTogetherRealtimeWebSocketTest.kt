package org.siloserver.silo.common.network

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.CleartextOriginConsent
import org.siloserver.silo.network.CleartextOriginNotApprovedException
import org.siloserver.silo.network.DefaultWatchTogetherRealtimeClient
import org.siloserver.silo.network.RoomRealtimeEvent
import org.siloserver.silo.network.SiloJson
import org.siloserver.silo.network.ProfileIdentity
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.canonicalHttpOrigin
import org.siloserver.silo.network.createSiloClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WatchTogetherRealtimeWebSocketTest {

    @Test
    fun `handshake fails before engine when captured scope token disappears after validation`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401))
        server.start()
        val tokens = VanishingScopeTokenManager(server.url("/").toString())
        val httpClient = createSiloClient(
            tokenManager = tokens,
            cleartextOriginConsent = approvedConsent(server),
        )
        try {
            val events = mutableListOf<RoomRealtimeEvent>()
            DefaultWatchTogetherRealtimeClient(httpClient, tokens)
                .connect("room-a", "ROOM_A")
                .collect(events::add)

            assertIs<RoomRealtimeEvent.TransportTerminated>(events.single())
            assertEquals(0, server.requestCount)
            assertEquals(2, tokens.scopedAccessReads)
        } finally {
            httpClient.close()
            server.shutdown()
        }
    }

    @Test
    fun `handshake remains entirely on captured auth scope when active identity switches`() = runBlocking {
        val serverA = MockWebServer()
        val serverB = MockWebServer()
        val socketListener = object : WebSocketListener() {
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }
        }
        serverA.enqueue(ticketResponse())
        serverA.enqueue(roomUpgrade(socketListener))
        serverB.enqueue(ticketResponse())
        serverB.enqueue(roomUpgrade(socketListener))
        serverA.start()
        serverB.start()

        val tokens = SwitchingScopeTokenManager(
            serverAUrl = serverA.url("/").toString(),
            serverBUrl = serverB.url("/").toString(),
        )
        val approvedOrigins = setOf(
            canonicalHttpOrigin(serverA.url("/").toString()),
            canonicalHttpOrigin(serverB.url("/").toString()),
        )
        val httpClient = createSiloClient(
            tokenManager = tokens,
            cleartextOriginConsent = object : CleartextOriginConsent {
                override suspend fun isApproved(origin: String): Boolean = origin in approvedOrigins
            },
        )
        var collection: Job? = null
        try {
            val realtime = DefaultWatchTogetherRealtimeClient(httpClient, tokens)
            val events = Channel<RoomRealtimeEvent>(Channel.UNLIMITED)
            collection = launch {
                realtime.connect("room-a", "ROOM_A").collect(events::send)
            }

            assertIs<RoomRealtimeEvent.Opened>(withTimeout(5_000) { events.receive() })
            val ticketA = assertNotNull(serverA.takeRequest(1, TimeUnit.SECONDS))
            val upgradeA = assertNotNull(serverA.takeRequest(1, TimeUnit.SECONDS))
            assertEquals(0, serverB.requestCount)
            assertEquals("/api/v2/watch-together/rooms/room-a/ws-ticket", ticketA.requestUrl?.encodedPath)
            assertEquals("ROOM_A", ticketA.getHeader("X-Room-Token"))
            assertEquals("Bearer ACCESS_A", ticketA.getHeader("Authorization"))
            assertEquals("profile-a", ticketA.getHeader("X-Profile-Id"))
            assertEquals("PROFILE_A", ticketA.getHeader("X-Profile-Token"))
            assertEquals("/api/v2/watch-together/rooms/room-a/ws", upgradeA.requestUrl?.encodedPath)
            assertNull(upgradeA.getHeader("Authorization"))
            assertNull(upgradeA.getHeader("X-Profile-Id"))
            assertEquals("silo.room.v2, silo.ticket.opaque-proof", upgradeA.getHeader("Sec-WebSocket-Protocol"))
        } finally {
            collection?.cancelAndJoin()
            httpClient.close()
            serverA.shutdown()
            serverB.shutdown()
        }
    }

    @Test
    fun `real handshake protects credentials orders open before snapshot and delivers outbound frames`() = runBlocking {
        val server = MockWebServer()
        val outbound = Channel<String>(Channel.UNLIMITED)
        val serverSocket = CompletableDeferred<WebSocket>()
        server.enqueue(ticketResponse())
        server.enqueue(
            roomUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        serverSocket.complete(webSocket)
                        webSocket.send(
                            """{"type":"snapshot","room":{"room_id":"room/segment ?#","code":"ABCD1234"}}""",
                        )
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        outbound.trySend(text)
                    }
                },
            ),
        )
        server.start()
        val tokens = configuredTokens(server)
        val httpClient = createSiloClient(
            tokenManager = tokens,
            cleartextOriginConsent = approvedConsent(server),
        )
        try {
            val realtime = DefaultWatchTogetherRealtimeClient(httpClient, tokens)
            val events = Channel<RoomRealtimeEvent>(Channel.UNLIMITED)
            val collection = launch {
                realtime.connect("room/segment ?#", "ROOM_SECRET").collect(events::send)
            }

            assertIs<RoomRealtimeEvent.Opened>(withTimeout(5_000) { events.receive() })
            val snapshot = assertIs<RoomRealtimeEvent.SnapshotEvent>(
                withTimeout(5_000) { events.receive() },
            )
            assertEquals("room/segment ?#", snapshot.room.roomId)

            assertTrue(realtime.attachSession("playback-1"))
            assertTrue(realtime.ping("2026-07-27T10:00:00Z"))
            assertEquals(
                listOf("attach_session", "ping"),
                listOf(
                    withTimeout(5_000) { outbound.receive() },
                    withTimeout(5_000) { outbound.receive() },
                ).map { SiloJson.parseToJsonElement(it).jsonObject.getValue("type").jsonPrimitive.content },
            )

            val ticket = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals(
                "/api/v2/watch-together/rooms/room%2Fsegment%20%3F%23/ws-ticket",
                ticket.requestUrl?.encodedPath,
            )
            assertEquals("ROOM_SECRET", ticket.getHeader("X-Room-Token"))
            assertEquals("Bearer ACCESS_SECRET", ticket.getHeader("Authorization"))
            assertEquals("profile-1", ticket.getHeader("X-Profile-Id"))
            assertEquals("PROFILE_SECRET", ticket.getHeader("X-Profile-Token"))
            val request = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            assertEquals(
                "/api/v2/watch-together/rooms/room%2Fsegment%20%3F%23/ws",
                request.requestUrl?.encodedPath,
            )
            assertEquals("", request.requestUrl?.encodedQuery.orEmpty())
            assertNull(request.getHeader("Authorization"))
            assertNull(request.getHeader("X-Profile-Id"))
            assertNull(request.getHeader("X-Profile-Token"))
            assertNull(request.getHeader("X-Room-Token"))
            assertEquals("silo.room.v2, silo.ticket.opaque-proof", request.getHeader("Sec-WebSocket-Protocol"))

            assertTrue(serverSocket.await().close(1000, "physical EOF"))
            val terminated = assertIs<RoomRealtimeEvent.TransportTerminated>(
                withTimeout(5_000) { events.receive() },
            )
            assertNull(terminated.cause)
            assertTrue(collection.join().let { collection.isCompleted })
        } finally {
            httpClient.close()
            server.shutdown()
        }
    }

    @Test
    fun `real socket cancellation is silent`() = runBlocking {
        val server = MockWebServer()
        val serverClosed = CompletableDeferred<Unit>()
        server.enqueue(ticketResponse())
        server.enqueue(
            roomUpgrade(
                object : WebSocketListener() {
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        serverClosed.complete(Unit)
                    }
                },
            ),
        )
        server.start()
        val tokens = configuredTokens(server)
        val httpClient = createSiloClient(
            tokenManager = tokens,
            cleartextOriginConsent = approvedConsent(server),
        )
        try {
            val realtime = DefaultWatchTogetherRealtimeClient(httpClient, tokens)
            val events = Channel<RoomRealtimeEvent>(Channel.UNLIMITED)
            val collection = launch {
                realtime.connect("room-1", "ROOM_SECRET").collect(events::send)
            }
            assertIs<RoomRealtimeEvent.Opened>(withTimeout(5_000) { events.receive() })

            collection.cancelAndJoin()
            withTimeout(5_000) { serverClosed.await() }

            assertNull(events.tryReceive().getOrNull())
            assertFalse(realtime.ping("after-cancel"))
        } finally {
            httpClient.close()
            server.shutdown()
        }
    }

    @Test
    fun `unapproved ws origin fails before credentials reach OkHttp`() = runBlocking {
        val server = MockWebServer()
        server.start()
        val tokens = configuredTokens(server)
        val httpClient = createSiloClient(
            tokenManager = tokens,
            cleartextOriginConsent = object : CleartextOriginConsent {
                override suspend fun isApproved(origin: String): Boolean = false
            },
        )
        try {
            val events = mutableListOf<RoomRealtimeEvent>()
            DefaultWatchTogetherRealtimeClient(httpClient, tokens)
                .connect("room-1", "ROOM_SECRET")
                .collect(events::add)

            val terminated = assertIs<RoomRealtimeEvent.TransportTerminated>(events.single())
            assertIs<CleartextOriginNotApprovedException>(terminated.cause)
            assertEquals(0, server.requestCount)
        } finally {
            httpClient.close()
            server.shutdown()
        }
    }

    private suspend fun configuredTokens(server: MockWebServer): TokenManager {
        val serverUrl = server.url("/").toString()
        val delegate = TokenManagerImpl().apply {
            setServerUrl(serverUrl)
            saveTokens("ACCESS_SECRET", "refresh", 3_600)
            setProfileId("profile-1")
            setProfileToken("PROFILE_SECRET")
        }
        return SnapshotTokenManager(
            delegate = delegate,
            scope = AuthScopeSnapshot(
                serverId = "server-1",
                profileId = "profile-1",
                serverUrl = serverUrl,
                profileToken = "PROFILE_SECRET",
            ),
            accessToken = "ACCESS_SECRET",
        )
    }

    private fun ticketResponse(): MockResponse =
        MockResponse()
            .setResponseCode(200)
            .addHeader("Content-Type", "application/json")
            .setBody("""{"ticket":"opaque-proof","expires_in":20,"max_connection_seconds":300,"protocol":"silo.room.v2"}""")

    private fun roomUpgrade(listener: WebSocketListener): MockResponse =
        MockResponse().withWebSocketUpgrade(listener).addHeader("Sec-WebSocket-Protocol", "silo.room.v2")

    private fun approvedConsent(server: MockWebServer): CleartextOriginConsent {
        val approvedOrigin = canonicalHttpOrigin(server.url("/").toString())
        return object : CleartextOriginConsent {
            override suspend fun isApproved(origin: String): Boolean =
                origin == approvedOrigin
        }
    }

    /**
     * Returns scope A once, then makes generic active-scope reads report B.
     *
     * The old client switches on [getProfileToken], immediately before the
     * engine/auth plugin runs. The corrected client switches after its first
     * exact-scope access-token validation. Either path deterministically puts
     * the active identity on B before the handshake is built.
     */
    private class SwitchingScopeTokenManager(
        serverAUrl: String,
        private val serverBUrl: String,
        private val delegate: TokenManager = TokenManagerImpl(),
    ) : TokenManager by delegate {
        private val scopeA = AuthScopeSnapshot(
            serverId = "server-a",
            profileId = "profile-a",
            serverUrl = serverAUrl,
            profileToken = "PROFILE_A",
            identityGeneration = 1,
            credentialEpoch = 1,
        )
        private var activeB = false

        override suspend fun snapshotCurrentScope(): AuthScopeSnapshot = scopeA

        override suspend fun getAccessToken(): String = if (activeB) "ACCESS_B" else "ACCESS_A"

        override suspend fun getProfileId(): String = if (activeB) "profile-b" else "profile-a"

        // See SiloAuthPluginPinTest: the delegated default would bypass these.
        override suspend fun getProfileIdentity(): ProfileIdentity =
            ProfileIdentity(getProfileId(), getProfileToken())

        override suspend fun getProfileToken(): String {
            val token = if (activeB) "PROFILE_B" else "PROFILE_A"
            activeB = true
            return token
        }

        override suspend fun getServerUrl(): String = if (activeB) serverBUrl else scopeA.serverUrl

        override suspend fun getCurrentServerId(): String = if (activeB) "server-b" else "server-a"

        override suspend fun getAccessTokenForScope(scope: AuthScopeSnapshot): String? {
            activeB = true
            return if (scope == scopeA) "ACCESS_A" else null
        }
    }

    private class SnapshotTokenManager(
        private val delegate: TokenManager,
        private val scope: AuthScopeSnapshot,
        private val accessToken: String,
    ) : TokenManager by delegate {
        override suspend fun snapshotCurrentScope(): AuthScopeSnapshot = scope

        override suspend fun getAccessTokenForScope(scope: AuthScopeSnapshot): String? =
            accessToken.takeIf { scope == this.scope }
    }

    private class VanishingScopeTokenManager(
        serverUrl: String,
        private val delegate: TokenManager = TokenManagerImpl(),
    ) : TokenManager by delegate {
        private val scope = AuthScopeSnapshot(
            serverId = "server-a",
            profileId = "profile-a",
            serverUrl = serverUrl,
            profileToken = "PROFILE_A",
            identityGeneration = 1,
            credentialEpoch = 1,
        )
        var scopedAccessReads = 0
            private set

        override suspend fun snapshotCurrentScope(): AuthScopeSnapshot = scope

        override suspend fun getAccessTokenForScope(scope: AuthScopeSnapshot): String? {
            scopedAccessReads += 1
            return "ACCESS_A".takeIf { scope == this.scope && scopedAccessReads == 1 }
        }
    }
}
