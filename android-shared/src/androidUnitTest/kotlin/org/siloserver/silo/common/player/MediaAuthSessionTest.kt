package org.siloserver.silo.common.player

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.CleartextOriginConsent
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.TokenManagerImpl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaAuthSessionTest {
    @Test
    fun transportNeutralSessionRefreshesAndReturnsFreshHeaders() {
        val tokens = TokenManagerImpl()
        runBlocking {
            tokens.setServerUrl("https://silo.example")
            tokens.saveTokens("expired-access", "refresh-token", 3600)
            tokens.setProfileId("profile-1")
        }
        val refreshClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                assertEquals("https://silo.example/api/v2/auth/refresh", chain.request().url.toString())
                assertEquals("POST", chain.request().method)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(
                        """{"access_token":"fresh-access","refresh_token":"fresh-refresh","expires_in":3600}"""
                            .toResponseBody(null),
                    )
                    .build()
            }
            .build()
        val session = MediaAuthSession(tokens, refreshClient)

        runBlocking {
            val failed = session.snapshot()
            assertEquals("Bearer expired-access", failed.asRequestHeaders()["Authorization"])
            assertTrue(session.refreshIfStale(failed))
            val fresh = session.snapshot().asRequestHeaders()
            assertEquals("Bearer fresh-access", fresh["Authorization"])
            assertEquals("profile-1", fresh["X-Profile-Id"])
        }
    }

    @Test
    fun temporaryPlaybackRefreshRejectionNeverFallsThroughToSavedOwner() {
        val owner = AuthScopeSnapshot("server-a", "owner", "https://silo.example", null, credentialEpoch = 1)
        val guest = AuthScopeSnapshot(
            "server-a",
            "guest",
            "https://silo.example",
            null,
            credentialGenerationId = "guest-generation",
        )
        val tokens = ScopedTokenManager(owner, "owner-access", "owner-refresh")
        tokens.install(guest, "guest-access", "guest-refresh")
        tokens.current = guest
        val refreshClient = respondingClient(401)
        val session = MediaAuthSession(tokens, refreshClient)

        runBlocking {
            val failed = session.snapshot()
            assertFalse(session.refreshIfStale(failed))
        }

        assertEquals(guest, tokens.current)
        assertEquals("guest-access", tokens.access(guest))
        assertEquals("owner-access", tokens.access(owner))
        assertEquals(emptyList(), tokens.invalidatedScopes)
    }

    @Test
    fun sameServerReloginCannotRefreshUsingStalePlaybackSnapshot() {
        val oldLogin = AuthScopeSnapshot("server-a", "profile", "https://silo.example", null, credentialEpoch = 1)
        val newLogin = oldLogin.copy(credentialEpoch = 3)
        val tokens = ScopedTokenManager(oldLogin, "old-access", "old-refresh")
        val refreshCalls = mutableListOf<String>()
        val session = MediaAuthSession(
            tokens,
            OkHttpClient.Builder().addInterceptor { chain ->
                refreshCalls += chain.request().url.toString()
                respondingClientResponse(chain.request(), 200)
            }.build(),
        )
        val failed = runBlocking { session.snapshot() }
        tokens.install(newLogin, "new-access", "new-refresh")
        tokens.current = newLogin

        assertFalse(runBlocking { session.refreshIfStale(failed) })
        assertEquals(emptyList(), refreshCalls)
        assertEquals("new-access", tokens.access(newLogin))
    }

    @Test
    fun unapprovedCleartextMediaSnapshotContainsNoCredentialsOrTrustedOrigin() {
        val scope = AuthScopeSnapshot("server-a", "profile", "http://silo.lan", "profile-token", credentialEpoch = 1)
        val tokens = ScopedTokenManager(scope, "access", "refresh")
        val session = MediaAuthSession(
            tokens,
            respondingClient(200),
            cleartextOriginConsent = object : CleartextOriginConsent {
                override suspend fun isApproved(origin: String): Boolean = false
            },
        )

        val snapshot = runBlocking { session.snapshot() }

        assertEquals(emptyMap(), snapshot.asRequestHeaders())
        assertEquals("", snapshot.serverUrl)
    }

    private fun respondingClient(code: Int): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            respondingClientResponse(chain.request(), code)
        }.build()

    private fun respondingClientResponse(request: okhttp3.Request, code: Int): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Rejected")
            .body(
                if (code in 200..299) {
                    """{"access_token":"fresh-access","refresh_token":"fresh-refresh","expires_in":3600}"""
                        .toResponseBody(null)
                } else {
                    "".toResponseBody(null)
                },
            )
            .build()

    private class ScopedTokenManager(
        currentScope: AuthScopeSnapshot,
        accessToken: String,
        refreshToken: String,
    ) : TokenManager {
        private data class Tokens(var access: String, var refresh: String)
        private val tokens = mutableMapOf<AuthScopeSnapshot, Tokens>()
        var current: AuthScopeSnapshot = currentScope
        val invalidatedScopes = mutableListOf<AuthScopeSnapshot>()
        private val expired = MutableSharedFlow<Unit>()
        override val sessionExpired: SharedFlow<Unit> = expired

        init {
            install(currentScope, accessToken, refreshToken)
        }

        fun install(scope: AuthScopeSnapshot, access: String, refresh: String) {
            tokens[scope] = Tokens(access, refresh)
        }

        fun access(scope: AuthScopeSnapshot): String? = tokens[scope]?.access

        override suspend fun snapshotCurrentScope(): AuthScopeSnapshot = current
        override suspend fun getAccessTokenForScope(scope: AuthScopeSnapshot): String? = tokens[scope]?.access
        override suspend fun getRefreshTokenForScope(scope: AuthScopeSnapshot): String? = tokens[scope]?.refresh
        override suspend fun saveTokensForScope(
            scope: AuthScopeSnapshot,
            accessToken: String,
            refreshToken: String,
            expiresIn: Long,
        ) {
            tokens[scope] = Tokens(accessToken, refreshToken)
        }
        override suspend fun invalidateSessionForScope(scope: AuthScopeSnapshot): Boolean {
            invalidatedScopes += scope
            if (scope.credentialGenerationId == null) tokens.remove(scope)
            return true
        }
        override suspend fun getAccessToken(): String? = tokens[current]?.access
        override suspend fun getRefreshToken(): String? = tokens[current]?.refresh
        override suspend fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Long) {
            tokens[current] = Tokens(accessToken, refreshToken)
        }
        override suspend fun clearTokens() {
            tokens.remove(current)
        }
        override suspend fun invalidateSession() {
            tokens.remove(current)
        }
        override suspend fun getProfileId(): String? = current.profileId
        override suspend fun setProfileId(profileId: String?) = Unit
        override suspend fun getProfileToken(): String? = current.profileToken
        override suspend fun setProfileToken(token: String?) = Unit
        override suspend fun getServerUrl(): String = current.serverUrl
        override suspend fun setServerUrl(url: String) = Unit
        override suspend fun getCurrentServerId(): String = current.serverId
        override suspend fun switchActiveServer(serverId: String?) = Unit
        override suspend fun signOutCurrentServer() = Unit
        override suspend fun getAccessTokenForScope(serverId: String): String? =
            tokens.entries.firstOrNull { it.key.serverId == serverId }?.value?.access
        override suspend fun getRefreshTokenForScope(serverId: String): String? =
            tokens.entries.firstOrNull { it.key.serverId == serverId }?.value?.refresh
    }
}
