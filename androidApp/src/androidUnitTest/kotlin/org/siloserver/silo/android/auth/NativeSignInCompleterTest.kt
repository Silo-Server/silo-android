package org.siloserver.silo.android.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.siloserver.silo.model.auth.AccountIdentities
import org.siloserver.silo.model.auth.AccountIdentity
import org.siloserver.silo.model.auth.AccountIdentityLinkTicket
import org.siloserver.silo.model.auth.ExternalSignInCapabilities
import org.siloserver.silo.model.auth.LoginResponse
import org.siloserver.silo.model.auth.OAuthHandshakeCapabilities
import org.siloserver.silo.model.auth.SignInProviders
import org.siloserver.silo.model.auth.User
import org.siloserver.silo.model.server.ServerEntry
import org.siloserver.silo.network.AccountSessionExpectation
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.api.AuthApi
import org.siloserver.silo.network.api.ExternalSignInApi
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.repository.AuthRepository
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Where [RepositoryNativeSignInCompleter] sends a native flow's code: only to
 * the saved server's base URL, and only while it is still at the origin the
 * flow started on. The saved server keeps its address and id.
 */
class NativeSignInCompleterTest {
    private val http = HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) })

    @AfterTest fun tearDown() = http.close()

    private val user = User("1", "alice", "a@example.test", "user")

    private inner class Fixture(saved: ServerEntry) {
        val registry = ListServerRegistry(saved)
        val tokens = RecordingTokens(registry)
        val api = RecordingApi(user)
        val completer = RepositoryNativeSignInCompleter(
            authRepository = AuthRepository(AuthApi(http, ApiV2Gate.Unrestricted), tokens, serverRegistry = registry),
            api = api,
            tokenManager = tokens,
        )

        suspend fun start() {
            tokens.setServerUrl(registry.activeEntry.value!!.url)
            tokens.activeServerId = registry.activeServerId.value
        }
    }

    private fun pending(startOrigin: String, purpose: NativeSignInPurpose = NativeSignInPurpose.SignIn, entryId: String) =
        pending(purpose).copy(serverEntryId = entryId, startOrigin = startOrigin, verifiedServerId = "srv-1")

    @Test
    fun aSignInRedeemsAtTheSavedBase() = runTest {
        val f = Fixture(ServerEntry(id = "pub", url = "https://silo.example.test/silo", verifiedServerId = "srv-1"))
        f.start()
        val signedIn = f.completer.signIn(pending("https://silo.example.test", entryId = "pub"), "code-1")
        assertEquals(user, assertIs<ApiResult.Success<User>>(signedIn).data)
        assertEquals(listOf("https://silo.example.test/silo"), f.api.redeemedAt)
        assertEquals(listOf<String?>("pub"), f.tokens.installedOn)
    }

    /** A server saved by its LAN address signs in there and stays there. */
    @Test
    fun aLanSavedServerSignsInAndStaysOnItsLanAddress() = runTest {
        val lan = ServerEntry(
            id = "lan", url = "http://192.168.1.10:8096", fetchedName = "Home", userOverrideName = "Den",
            verifiedServerId = "srv-1",
        )
        val f = Fixture(lan)
        f.start()
        assertIs<ApiResult.Success<User>>(f.completer.signIn(pending("http://192.168.1.10:8096", entryId = "lan"), "code-1"))
        assertEquals(listOf("http://192.168.1.10:8096"), f.api.redeemedAt)
        assertEquals(listOf(lan), f.registry.entries.value, "the saved server is unchanged")
        assertEquals(listOf<String?>("lan"), f.tokens.installedOn)
    }

    /**
     * The saved address changed while the browser was open: the code isn't
     * redeemed at the new address, which isn't where the flow started.
     */
    @Test
    fun aSavedServerOnAnotherOriginThanTheFlowRedeemsNothing() = runTest {
        val f = Fixture(ServerEntry(id = "pub", url = "https://elsewhere.example.test", verifiedServerId = "srv-1"))
        f.start()
        val refused = assertIs<ApiResult.Error>(f.completer.signIn(pending("https://silo.example.test", entryId = "pub"), "c"))
        assertEquals(NativeSignInMessages.ACCOUNT_CHANGED, NativeSignInCoordinator.completionReason(refused))
        assertTrue(f.api.redeemedAt.isEmpty())
        assertTrue(f.tokens.installedOn.isEmpty())
    }

    /**
     * The flow began signed out, then a password sign-in landed while the
     * browser was open: the provider's code no longer speaks for this
     * session and replaces nothing.
     */
    @Test
    fun aSignInSinceTheFlowBeganRedeemsNothing() = runTest {
        val f = Fixture(ServerEntry(id = "pub", url = "https://silo.example.test", verifiedServerId = "srv-1"))
        f.start()
        f.tokens.loginSessionIds["pub"] = "password-login"
        val refused = assertIs<ApiResult.Error>(f.completer.signIn(pending("https://silo.example.test", entryId = "pub"), "c"))
        assertEquals(NativeSignInMessages.ACCOUNT_CHANGED, NativeSignInCoordinator.completionReason(refused))
        assertTrue(f.api.redeemedAt.isEmpty())
        assertTrue(f.tokens.installedOn.isEmpty())
    }

    @Test
    fun aFlowForAnotherSavedServerRedeemsNothing() = runTest {
        val f = Fixture(ServerEntry(id = "pub", url = "https://silo.example.test", verifiedServerId = "srv-1"))
        f.start()
        assertIs<ApiResult.Error>(f.completer.signIn(pending("https://silo.example.test", entryId = "other"), "c"))
        assertTrue(f.api.redeemedAt.isEmpty())
    }

    /** Linking from a LAN-saved server confirms the code at the LAN address, with the account's session. */
    @Test
    fun linkingConfirmsAtTheSavedBase() = runTest {
        val lan = ServerEntry(id = "lan", url = "http://192.168.1.10:8096", verifiedServerId = "srv-1")
        val f = Fixture(lan)
        f.start()
        f.tokens.scope = AuthScopeSnapshot("lan", null, "http://192.168.1.10:8096", null, identityGeneration = 3, credentialEpoch = 4)
        f.tokens.loginSessionIds["lan"] = "login-1"
        val linking = pending("http://192.168.1.10:8096", NativeSignInPurpose.Link, entryId = "lan")
            .copy(loginSessionId = "login-1")
        assertIs<ApiResult.Success<Unit>>(f.completer.link(linking, "code-1"))
        assertEquals(listOf("http://192.168.1.10:8096"), f.api.linkedAt)
        assertEquals(listOf(lan), f.registry.entries.value)

        // Another login, no login, or a flow started on another origin confirms nothing.
        assertIs<ApiResult.Error>(f.completer.link(linking.copy(loginSessionId = "login-2"), "code-1"))
        assertIs<ApiResult.Error>(f.completer.link(linking.copy(loginSessionId = null), "code-1"))
        assertIs<ApiResult.Error>(f.completer.link(linking.copy(startOrigin = "https://silo.example.test"), "code-1"))
        assertEquals(1, f.api.linkedAt.size)
    }

    /** Records where codes were redeemed. */
    private class RecordingApi(private val user: User) : ExternalSignInApi {
        val redeemedAt = mutableListOf<String>()
        val linkedAt = mutableListOf<String>()
        override suspend fun completeOAuthLogin(serverUrl: String, code: String, codeVerifier: String): ApiResult<LoginResponse> {
            redeemedAt += serverUrl
            return ApiResult.Success(LoginResponse("access", "refresh", 3600, user))
        }
        override suspend fun completeLink(scope: AuthScopeSnapshot, code: String, codeVerifier: String): ApiResult<Unit> {
            linkedAt += scope.serverUrl
            return ApiResult.Success(Unit)
        }
        override suspend fun listProviders(serverUrl: String): ApiResult<SignInProviders> = TODO()
        override suspend fun oauthCapabilities(serverUrl: String): ApiResult<OAuthHandshakeCapabilities> = TODO()
        override suspend fun externalSignInCapabilities(scope: AuthScopeSnapshot): ApiResult<ExternalSignInCapabilities> = TODO()
        override suspend fun listIdentities(scope: AuthScopeSnapshot): ApiResult<AccountIdentities> = TODO()
        override suspend fun deleteIdentity(scope: AuthScopeSnapshot, identityId: String): ApiResult<Unit> = TODO()
        override suspend fun createLinkTicket(scope: AuthScopeSnapshot, installationId: String, password: String):
            ApiResult<AccountIdentityLinkTicket> = TODO()
        override suspend fun linkWithCredentials(
            scope: AuthScopeSnapshot,
            installationId: String,
            password: String,
            username: String,
            directoryPassword: String,
        ): ApiResult<AccountIdentity> = TODO()
        override suspend fun signInWithNetworkIdentity(serverUrl: String, signInPath: String): ApiResult<LoginResponse> = TODO()
        override suspend fun linkWithNetwork(scope: AuthScopeSnapshot, installationId: String, password: String):
            ApiResult<AccountIdentity> = TODO()
    }

    /**
     * [TokenManagerImpl], recording which saved server each sign-in installed
     * its session on. Like the app's manager, its session expectation names
     * the active saved server, and its scope carries the address that server
     * is saved at now.
     */
    private class RecordingTokens(
        private val registry: ServerRegistry,
        private val inner: TokenManagerImpl = TokenManagerImpl(),
    ) : TokenManager by inner {
        val installedOn = mutableListOf<String?>()
        var scope: AuthScopeSnapshot? = null
        var activeServerId: String? = null
        val loginSessionIds = mutableMapOf<String, String>()

        override suspend fun loginSessionId(serverId: String): String? = loginSessionIds[serverId]

        override suspend fun captureAccountSessionExpectation(): AccountSessionExpectation? =
            inner.captureAccountSessionExpectation()?.copy(serverId = activeServerId)

        override suspend fun snapshotCurrentScope(): AuthScopeSnapshot? = scope?.let { captured ->
            registry.entries.value.firstOrNull { it.id == captured.serverId }?.let { captured.copy(serverUrl = it.url) }
        }

        override suspend fun replaceAccountSession(
            serverId: String?,
            serverUrl: String?,
            accessToken: String,
            refreshToken: String,
            expiresIn: Long,
            profileId: String?,
            profileToken: String?,
            expectedIdentity: AccountSessionExpectation?,
        ) {
            installedOn += serverId
            inner.replaceAccountSession(serverId, serverUrl, accessToken, refreshToken, expiresIn, profileId, profileToken, expectedIdentity)
        }
    }

    /** Saved servers keyed by address, like the app's registry. */
    private class ListServerRegistry(first: ServerEntry) : ServerRegistry {
        override val entries = MutableStateFlow(listOf(first))
        override val activeServerId = MutableStateFlow<String?>(first.id)
        private val active = MutableStateFlow<ServerEntry?>(first)
        override val activeEntry: StateFlow<ServerEntry?> = active

        private fun edit(id: String, change: (ServerEntry) -> ServerEntry) {
            entries.value = entries.value.map { if (it.id == id) change(it) else it }
            active.value = entries.value.firstOrNull { it.id == activeServerId.value }
        }

        override suspend fun addOrUpdate(url: String, fetchedName: String?): String {
            val normalized = url.trimEnd('/')
            val id = "id:$normalized"
            if (entries.value.none { it.id == id }) entries.value = entries.value + ServerEntry(id = id, url = normalized, fetchedName = fetchedName)
            return id
        }
        override suspend fun rename(serverId: String, userOverrideName: String?) = edit(serverId) { it.copy(userOverrideName = userOverrideName) }
        override suspend fun setFetchedName(serverId: String, fetchedName: String?) = edit(serverId) { it.copy(fetchedName = fetchedName) }
        override suspend fun setVerifiedServerId(serverId: String, verifiedServerId: String?) =
            edit(serverId) { it.copy(verifiedServerId = verifiedServerId) }
        override suspend fun setProfileId(serverId: String, profileId: String?) = Unit
        override suspend fun remove(serverId: String) = Unit
        override suspend fun signOut(serverId: String) = Unit
        override suspend fun switchTo(serverId: String) {
            activeServerId.value = serverId
            active.value = entries.value.firstOrNull { it.id == serverId }
        }
        override suspend fun touchActive() = Unit
    }
}
