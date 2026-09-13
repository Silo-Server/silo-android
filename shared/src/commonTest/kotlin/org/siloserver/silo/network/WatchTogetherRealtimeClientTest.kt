package org.siloserver.silo.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class WatchTogetherRealtimeClientTest {

    @Test
    fun `socket request string never exposes query credentials or profile token`() {
        val request = WatchTogetherSocketRequest(
            roomId = "room-1",
            roomToken = "room-secret",
            authScope = AuthScopeSnapshot(
                serverId = "aHR0cHM6Ly9wcml2YXRlLXNpbG8uZXhhbXBsZQ",
                profileId = "private-profile-id",
                serverUrl = "https://user:url-secret@private-silo.example/path?api_key=url-query-secret",
                profileToken = "profile-secret",
                credentialGenerationId = "credential-generation-secret",
                identityGeneration = 987654321L,
                credentialEpoch = 123456789L,
            ),
        )

        val rendered = request.toString()

        assertFalse(rendered.contains("room-1"))
        assertFalse(rendered.contains("room-secret"))
        assertFalse(rendered.contains("profile-secret"))
        assertFalse(rendered.contains("aHR0cHM6Ly9wcml2YXRlLXNpbG8uZXhhbXBsZQ"))
        assertFalse(rendered.contains("private-profile-id"))
        assertFalse(rendered.contains("private-silo.example"))
        assertFalse(rendered.contains("url-secret"))
        assertFalse(rendered.contains("url-query-secret"))
        assertFalse(rendered.contains("credential-generation-secret"))
        assertFalse(rendered.contains("987654321"))
        assertFalse(rendered.contains("123456789"))
        assertTrue(rendered.contains("<redacted>"))
    }

    @Test
    fun `normal socket EOF is transport termination not protocol room close`() = runTest {
        val connection = FakeConnection().apply { incoming.close() }
        val client = client(FakeConnector(connection))

        val events = mutableListOf<RoomRealtimeEvent>()
        client.connect("room-1", "room-token").collect(events::add)

        assertIs<RoomRealtimeEvent.Opened>(events[0])
        assertIs<RoomRealtimeEvent.TransportTerminated>(events[1])
        assertNull(events[1].let { it as RoomRealtimeEvent.TransportTerminated }.cause)
        assertTrue(events.none { it is RoomRealtimeEvent.Closed })
    }

    @Test
    fun `socket receive failure is typed transport termination not protocol room close`() = runTest {
        val connection = FakeConnection().apply {
            incoming.close(IllegalStateException("socket broke"))
        }
        val client = client(FakeConnector(connection))

        val events = mutableListOf<RoomRealtimeEvent>()
        client.connect("room-1", "room-token").collect(events::add)

        val terminated = assertIs<RoomRealtimeEvent.TransportTerminated>(events.last())
        assertEquals("socket broke", terminated.cause?.message)
        assertTrue(events.none { it is RoomRealtimeEvent.Closed })
    }

    @Test
    fun `handshake failure is typed transport termination not protocol room close`() = runTest {
        val client = client(
            object : WatchTogetherSocketConnector {
                override suspend fun open(request: WatchTogetherSocketRequest): WatchTogetherSocketConnection {
                    throw IllegalStateException("handshake rejected")
                }
            },
        )

        val events = mutableListOf<RoomRealtimeEvent>()
        client.connect("room-1", "room-token").collect(events::add)

        val terminated = assertIs<RoomRealtimeEvent.TransportTerminated>(events.single())
        assertEquals("handshake rejected", terminated.cause?.message)
    }

    @Test
    fun `collector cancellation emits no transport or protocol close`() = runTest {
        val connection = FakeConnection()
        val opened = CompletableDeferred<Unit>()
        val events = mutableListOf<RoomRealtimeEvent>()
        val job = launch {
            client(FakeConnector(connection))
                .connect("room-1", "room-token")
                .collect {
                    events += it
                    if (it is RoomRealtimeEvent.Opened) opened.complete(Unit)
                }
        }
        opened.await()

        job.cancelAndJoin()

        assertEquals(1, events.size)
        assertIs<RoomRealtimeEvent.Opened>(events.single())
    }

    @Test
    fun `collector cancellation joins physical socket close before completing`() = runTest {
        val connection = FakeConnection()
        val closeGate = CompletableDeferred<Unit>()
        connection.closeGate = closeGate
        val opened = CompletableDeferred<Unit>()
        val job = launch {
            client(FakeConnector(connection))
                .connect("room-1", "room-token")
                .collect {
                    if (it is RoomRealtimeEvent.Opened) opened.complete(Unit)
                }
        }
        opened.await()

        job.cancel()
        runCurrent()

        assertTrue(connection.closeStarted.isCompleted)
        assertFalse(job.isCompleted)
        closeGate.complete(Unit)
        job.join()
        assertTrue(connection.closed)
    }

    @Test
    fun `sends report delivery only while a writable connection is current`() = runTest {
        val connection = FakeConnection()
        val client = client(FakeConnector(connection))
        assertFalse(client.attachSession("session-before-open"))

        val opened = CompletableDeferred<Unit>()
        val job = launch {
            client.connect("room-1", "room-token").collect {
                if (it is RoomRealtimeEvent.Opened) opened.complete(Unit)
            }
        }
        opened.await()

        assertTrue(client.attachSession("session-1"))
        assertTrue(client.transportRequest("seek", 12.5, false))
        assertTrue(client.stateReport("session-1", 13.5, false))
        assertTrue(client.ready("session-1", 14.5, false))
        assertTrue(client.buffering("session-1", 15.5, true))
        assertTrue(client.ping("2026-07-27T10:00:00Z"))
        assertEquals(
            listOf(
                "attach_session",
                "transport_request",
                "state_report",
                "ready",
                "buffering",
                "ping",
            ),
            connection.sent.map { SiloJson.parseToJsonElement(it).jsonObject.getValue("type").jsonPrimitive.content },
        )

        connection.sendFailure = IllegalStateException("closed")
        assertFalse(client.ping("2026-07-27T10:00:01Z"))
        job.cancelAndJoin()
        assertFalse(client.attachSession("session-after-close"))
    }

    @Test
    fun `connection scoped transport never crosses into a replacement socket`() = runTest {
        val first = FakeConnection()
        val second = FakeConnection()
        val client = client(FakeConnector(first, second))
        val firstOpened = CompletableDeferred<Unit>()
        val firstJob = launch {
            client.connect("room-1", "room-token-1").collect {
                if (it is RoomRealtimeEvent.Opened) firstOpened.complete(Unit)
            }
        }
        firstOpened.await()
        val firstId = requireNotNull(client.currentConnectionId())

        val secondOpened = CompletableDeferred<Unit>()
        val secondJob = launch {
            client.connect("room-2", "room-token-2").collect {
                if (it is RoomRealtimeEvent.Opened) secondOpened.complete(Unit)
            }
        }
        secondOpened.await()
        val secondId = requireNotNull(client.currentConnectionId())

        assertFalse(client.transportRequestOnConnection(firstId, "pause", 12.0, true))
        assertTrue(client.transportRequestOnConnection(secondId, "pause", 12.0, true))
        assertTrue(first.sent.isEmpty())
        assertEquals(1, second.sent.size)
        firstJob.cancelAndJoin()
        secondJob.cancelAndJoin()
    }

    @Test
    fun `old connection finalizer cannot clear a writable replacement`() = runTest {
        val first = FakeConnection()
        val second = FakeConnection()
        val connector = FakeConnector(first, second)
        val client = client(connector)
        val firstOpened = CompletableDeferred<Unit>()
        val secondOpened = CompletableDeferred<Unit>()
        val firstJob = launch {
            client.connect("room-1", "room-token").collect {
                if (it is RoomRealtimeEvent.Opened) firstOpened.complete(Unit)
            }
        }
        firstOpened.await()
        val secondJob = launch {
            client.connect("room-1", "room-token").collect {
                if (it is RoomRealtimeEvent.Opened) secondOpened.complete(Unit)
            }
        }
        secondOpened.await()

        firstJob.cancelAndJoin()
        assertTrue(client.ping("replacement-ping"))
        assertTrue(first.sent.isEmpty())
        assertEquals(1, second.sent.size)

        secondJob.cancelAndJoin()
    }

    @Test
    fun `connection request carries room identity and captured scope only`() = runTest {
        val connection = FakeConnection()
        val connector = FakeConnector(connection)
        val client = client(
            connector = connector,
            accessToken = "ACCESS_SECRET",
            profileId = "profile id",
            profileToken = "profile&secret",
        )
        val opened = CompletableDeferred<Unit>()
        val job = launch {
            client.connect("room/with ?#", "room&secret").collect {
                if (it is RoomRealtimeEvent.Opened) opened.complete(Unit)
            }
        }
        opened.await()

        val request = connector.requests.single()
        assertEquals("room/with ?#", request.roomId)
        assertEquals("room&secret", request.roomToken)
        assertFalse(request.toString().contains("ACCESS_SECRET"))
        assertEquals("profile id", request.authScope.profileId)
        assertEquals("profile&secret", request.authScope.profileToken)

        job.cancelAndJoin()
    }

    @Test
    fun `missing access token for captured scope fails before connector opens`() = runTest {
        val scope = AuthScopeSnapshot(
            serverId = "server-a",
            profileId = "profile-a",
            serverUrl = "https://a.example",
            profileToken = "PROFILE_A",
        )
        val tokens = SnapshotTokenManager(scope, accessToken = null)
        val connector = FakeConnector(FakeConnection())
        val client = DefaultWatchTogetherRealtimeClient(
            tokenManager = tokens,
            socketConnector = connector,
        )

        val events = mutableListOf<RoomRealtimeEvent>()
        client.connect("room-a", "ROOM_A").collect(events::add)

        val terminated = assertIs<RoomRealtimeEvent.TransportTerminated>(events.single())
        assertEquals("missing_auth_scope", terminated.cause?.message)
        assertEquals(1, tokens.snapshotReads)
        assertTrue(connector.requests.isEmpty())
    }

    @Test
    fun `explicit room scope wins over a different live identity without snapshot reads`() = runTest {
        val scopeA = AuthScopeSnapshot(
            serverId = "server-a",
            profileId = "profile-a",
            serverUrl = "https://a.example",
            profileToken = "PROFILE_A",
            identityGeneration = 1L,
        )
        val scopeB = AuthScopeSnapshot(
            serverId = "server-b",
            profileId = "profile-b",
            serverUrl = "https://b.example",
            profileToken = "PROFILE_B",
            identityGeneration = 2L,
        )
        var snapshotReads = 0
        val delegate = TokenManagerImpl()
        val tokens = object : TokenManager by delegate {
            override suspend fun snapshotCurrentScope(): AuthScopeSnapshot {
                snapshotReads++
                return scopeB
            }

            override suspend fun getAccessTokenForScope(scope: AuthScopeSnapshot): String? =
                "ACCESS_A".takeIf { scope == scopeA }
        }
        val connection = FakeConnection().apply { incoming.close() }
        val connector = FakeConnector(connection)
        val client = DefaultWatchTogetherRealtimeClient(
            tokenManager = tokens,
            socketConnector = connector,
        )

        client.connect("room-a", "ROOM_A", scopeA).collect { }

        assertEquals(0, snapshotReads)
        val request = connector.requests.single()
        assertEquals(scopeA, request.authScope)
        assertEquals("ROOM_A", request.roomToken)
        assertEquals("profile-a", request.authScope.profileId)
        assertEquals("PROFILE_A", request.authScope.profileToken)
    }

    private suspend fun client(
        connector: WatchTogetherSocketConnector,
        accessToken: String = "access",
        profileId: String = "profile",
        profileToken: String = "profile-token",
    ): DefaultWatchTogetherRealtimeClient {
        val scope = AuthScopeSnapshot(
            serverId = "server",
            profileId = profileId,
            serverUrl = "http://localhost:8090",
            profileToken = profileToken,
        )
        val tokens = SnapshotTokenManager(scope, accessToken)
        return DefaultWatchTogetherRealtimeClient(
            tokenManager = tokens,
            socketConnector = connector,
        )
    }

    private class FakeConnector(
        vararg connections: FakeConnection,
    ) : WatchTogetherSocketConnector {
        private val connections = Channel<FakeConnection>(Channel.UNLIMITED).apply {
            connections.forEach { trySend(it) }
        }
        val requests = mutableListOf<WatchTogetherSocketRequest>()

        override suspend fun open(request: WatchTogetherSocketRequest): WatchTogetherSocketConnection {
            requests += request
            return connections.receive()
        }
    }

    private class SnapshotTokenManager(
        private val scope: AuthScopeSnapshot,
        private val accessToken: String?,
        private val delegate: TokenManager = TokenManagerImpl(),
    ) : TokenManager by delegate {
        var snapshotReads = 0

        override suspend fun snapshotCurrentScope(): AuthScopeSnapshot {
            snapshotReads += 1
            return scope
        }

        override suspend fun getAccessTokenForScope(scope: AuthScopeSnapshot): String? =
            accessToken?.takeIf { scope == this.scope }
    }

    private class FakeConnection : WatchTogetherSocketConnection {
        val incoming = Channel<String>(Channel.UNLIMITED)
        val sent = mutableListOf<String>()
        var sendFailure: Throwable? = null
        val closeStarted = CompletableDeferred<Unit>()
        var closeGate: CompletableDeferred<Unit>? = null
        var closed = false

        override suspend fun receiveText(): String? {
            val result = incoming.receiveCatching()
            result.exceptionOrNull()?.let { throw it }
            return result.getOrNull()
        }

        override suspend fun sendText(text: String) {
            sendFailure?.let { throw it }
            sent += text
        }

        override suspend fun close() {
            closeStarted.complete(Unit)
            closeGate?.await()
            incoming.cancel(CancellationException("test connection closed"))
            closed = true
        }
    }
}
