package org.siloserver.silo.common.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.CleartextOriginConsent
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.api.DefaultWatchTogetherApi
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.network.canonicalHttpOrigin
import org.siloserver.silo.network.createSiloClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Start, select, and promote are non-retryable: a replay after another
 * selection could start the wrong item. This pins what the real client does
 * when the connection drops after the request was sent.
 */
class WatchPartyRequestReplayTest {

    private class ScopeTokens(private val scope: AuthScopeSnapshot, private val delegate: TokenManager = TokenManagerImpl()) :
        TokenManager by delegate {
        override suspend fun snapshotCurrentScope(): AuthScopeSnapshot = scope
        override suspend fun getAccessTokenForScope(scope: AuthScopeSnapshot): String? = "ACCESS".takeIf { scope == this.scope }
    }

    @Test
    fun `a start whose pooled connection drops after sending is not resent`() = runBlocking {
        val server = MockWebServer()
        val room = """{"room":{"room_id":"room-1","phase":"lobby"},"room_access_token":"proof"}"""
        // Warm the connection pool so the start reuses a kept-alive connection,
        // the case where OkHttp treats a failure as a stale connection.
        server.enqueue(MockResponse().setResponseCode(200).addHeader("Content-Type", "application/json").setBody(room))
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(
            MockResponse().setResponseCode(200).addHeader("Content-Type", "application/json")
                .setBody("""{"room":{"room_id":"room-1","phase":"playing"},"room_access_token":"proof"}"""),
        )
        server.start()
        val scope = AuthScopeSnapshot(
            serverId = "server",
            profileId = "profile",
            serverUrl = server.url("/").toString().trimEnd('/'),
            profileToken = null,
            identityGeneration = 1,
            credentialEpoch = 1,
        )
        val serverOrigin = canonicalHttpOrigin(server.url("/").toString())
        val consent = object : CleartextOriginConsent {
            override suspend fun isApproved(origin: String): Boolean = origin == serverOrigin
        }
        val client = createSiloClient(tokenManager = ScopeTokens(scope), cleartextOriginConsent = consent)
        val nonReplaying = createSiloClient(
            tokenManager = ScopeTokens(scope),
            cleartextOriginConsent = consent,
            retryOnConnectionFailure = false,
        )
        try {
            val api = DefaultWatchTogetherApi(client, ApiV2Gate.Unrestricted, nonReplayingClient = nonReplaying)
            assertIs<ApiResult.Success<*>>(api.getRoom("room-1", "proof", scope))

            val result = api.startPlayback("room-1", scope)

            assertIs<ApiResult.NetworkError>(result)
            assertEquals(2, server.requestCount, "the non-retryable start was replayed")
        } finally {
            client.close()
            nonReplaying.close()
            server.shutdown()
        }
    }
}
