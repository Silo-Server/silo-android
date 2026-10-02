package org.siloserver.silo.android.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.siloserver.silo.android.auth.InMemoryAccountChoiceStore
import org.siloserver.silo.android.auth.InMemoryPendingNativeSignInStore
import org.siloserver.silo.android.auth.NativeSignInCallback
import org.siloserver.silo.android.auth.NativeSignInCompleter
import org.siloserver.silo.android.auth.NativeSignInCoordinator
import org.siloserver.silo.android.auth.NativeSignInMessages
import org.siloserver.silo.android.auth.NativeSignInPurpose
import org.siloserver.silo.android.auth.NativeSignInStart
import org.siloserver.silo.android.auth.PendingNativeSignIn
import org.siloserver.silo.model.auth.AccountIdentities
import org.siloserver.silo.model.auth.AccountIdentity
import org.siloserver.silo.model.auth.AccountIdentityLinkTicket
import org.siloserver.silo.model.auth.ExternalSignInCapabilities
import org.siloserver.silo.model.auth.LoginResponse
import org.siloserver.silo.model.auth.NetworkIdentity
import org.siloserver.silo.model.auth.OAuthHandshakeCapabilities
import org.siloserver.silo.model.auth.SignInProvider
import org.siloserver.silo.model.auth.SignInProviders
import org.siloserver.silo.model.auth.User
import org.siloserver.silo.model.server.ServerEntry
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.api.AuthApi
import org.siloserver.silo.network.api.ExternalSignInApi
import org.siloserver.silo.network.api.ServerConnections
import org.siloserver.silo.network.api.ServerIdentityApi
import org.siloserver.silo.network.api.ServerIdentityProbe
import org.siloserver.silo.network.apiv2.ApiV2Gate
import org.siloserver.silo.repository.AuthRepository
import org.siloserver.silo.repository.ExternalSignInRepository
import org.siloserver.silo.repository.ServerIdentityRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The phone sign-in screen's provider paths: discovery, starting a flow, and the flow's result. */
@OptIn(ExperimentalCoroutinesApi::class)
class LoginViewModelSsoTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val keycloak = SignInProvider(
        "plugin:5:oidc", "Keycloak", SignInProvider.Mode.OAuth, false, null, "5",
        "/api/v2/auth/oauth/5/native/start",
    )
    private val native = OAuthHandshakeCapabilities(available = true, native = true, linking = true, selectAccount = true)
    private val tailscale = SignInProvider(
        "plugin:7:tailscale", "Tailscale", SignInProvider.Mode.Network, false, null, "7", null,
        networkSignInPath = "/api/v2/auth/network/7/sign-in",
        networkIdentity = NetworkIdentity("Alice Example", "alice@example.test"),
    )

    private class Fixture(
        scope: CoroutineScope,
        serverUrl: String,
        var providers: ApiResult<SignInProviders>,
        var handshake: ApiResult<OAuthHandshakeCapabilities>,
        var identity: ServerIdentityProbe,
    ) {
        val tokens = TokenManagerImpl()
        val registry = OneServerRegistry(ServerEntry(id = "a", url = serverUrl))
        val store = InMemoryPendingNativeSignInStore()
        val coordinator = NativeSignInCoordinator(store, SignsIn(), scope, InMemoryAccountChoiceStore())
        private val http = HttpClient(MockEngine { respondError(HttpStatusCode.ServiceUnavailable) })
        val authRepository = AuthRepository(AuthApi(http, ApiV2Gate.Unrestricted), tokens)

        /** What the network sign-in answers, and where each one was sent (saved base, path). */
        var networkAnswer: ApiResult<LoginResponse> = ApiResult.Error(403, "network_identity_required", "")
        val networkCalls = mutableListOf<Pair<String, String>>()
        val api = object : ExternalSignInApi {
            override suspend fun listProviders(serverUrl: String) = providers
            override suspend fun oauthCapabilities(serverUrl: String) = handshake
            override suspend fun externalSignInCapabilities(scope: AuthScopeSnapshot): ApiResult<ExternalSignInCapabilities> = TODO()
            override suspend fun completeOAuthLogin(serverUrl: String, code: String, codeVerifier: String): ApiResult<LoginResponse> = TODO()
            override suspend fun listIdentities(scope: AuthScopeSnapshot): ApiResult<AccountIdentities> = TODO()
            override suspend fun deleteIdentity(scope: AuthScopeSnapshot, identityId: String): ApiResult<Unit> = TODO()
            override suspend fun createLinkTicket(scope: AuthScopeSnapshot, installationId: String, password: String):
                ApiResult<AccountIdentityLinkTicket> = TODO()
            override suspend fun completeLink(scope: AuthScopeSnapshot, code: String, codeVerifier: String): ApiResult<Unit> = TODO()
            override suspend fun linkWithCredentials(
                scope: AuthScopeSnapshot,
                installationId: String,
                password: String,
                username: String,
                directoryPassword: String,
            ): ApiResult<AccountIdentity> = TODO()
            override suspend fun signInWithNetworkIdentity(serverUrl: String, signInPath: String): ApiResult<LoginResponse> {
                networkCalls += serverUrl to signInPath
                return networkAnswer
            }
            override suspend fun linkWithNetwork(scope: AuthScopeSnapshot, installationId: String, password: String):
                ApiResult<AccountIdentity> = TODO()
        }
        val identityApi = object : ServerIdentityApi {
            override suspend fun probeIdentity(serverUrl: String) = identity
            override suspend fun connections(scope: AuthScopeSnapshot): ServerConnections? = null
        }

        suspend fun viewModel(): LoginViewModel {
            tokens.setServerUrl(registry.activeEntry.value!!.url)
            return LoginViewModel(
                authRepository = authRepository,
                externalSignIn = ExternalSignInRepository(api),
                serverRegistry = registry,
                serverIdentities = ServerIdentityRepository(registry, identityApi),
                nativeSignIn = coordinator,
                tokenManager = tokens,
            )
        }

        fun close() = http.close()
    }

    private fun TestScope.fixture(
        serverUrl: String = "https://silo.example.test",
        providers: List<SignInProvider> = listOf(keycloak),
        identity: ServerIdentityProbe = ServerIdentityProbe.Identity("srv-1"),
    ) = Fixture(
        scope = backgroundScope,
        serverUrl = serverUrl,
        providers = ApiResult.Success(SignInProviders(providers, passwordLogin = true)),
        handshake = ApiResult.Success(native),
        identity = identity,
    )

    private fun LoginViewModel.close() = viewModelScope.cancel()

    @Test
    fun aNetworkFailureOffersTheFormAndARetry() = runTest(dispatcher) {
        val f = fixture()
        f.providers = ApiResult.NetworkError(RuntimeException("offline"))
        val vm = f.viewModel()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.optionsUnavailable)
        assertTrue(vm.uiState.value.showPasswordForm)
        assertTrue(vm.uiState.value.providers.isEmpty())

        f.providers = ApiResult.Success(SignInProviders(listOf(keycloak), passwordLogin = true))
        vm.loadOptions()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.optionsUnavailable)
        assertEquals(listOf(keycloak), vm.uiState.value.providers)
        vm.close(); f.close()
    }

    @Test
    fun aServerWithoutDiscoveryIsPasswordOnlyWithoutARetry() = runTest(dispatcher) {
        val f = fixture()
        f.providers = ApiResult.Error(404, "not_found", "")
        val vm = f.viewModel()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.optionsUnavailable)
        assertTrue(vm.uiState.value.showPasswordForm)
        vm.close(); f.close()
    }

    @Test
    fun anUnconfirmedIdentityStartsNothing() = runTest(dispatcher) {
        val f = fixture(identity = ServerIdentityProbe.Unreachable)
        val vm = f.viewModel()
        advanceUntilIdle()
        vm.onProviderClick(keycloak)
        advanceUntilIdle()
        assertEquals("Couldn't confirm this server's identity. Check your connection and try again.", vm.uiState.value.error)
        assertNull(vm.uiState.value.browserLaunch)
        assertNull(vm.uiState.value.providerBusy)
        assertNull(f.store.load())
        vm.close(); f.close()
    }

    @Test
    fun aServerErrorDuringDiscoveryOffersTheFormAndARetry() = runTest(dispatcher) {
        val f = fixture()
        f.providers = ApiResult.Error(502, "", "bad gateway")
        val vm = f.viewModel()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.optionsUnavailable)
        assertTrue(vm.uiState.value.showPasswordForm)
        vm.close(); f.close()
    }

    @Test
    fun noPasswordFormWhileDiscoveryRuns() = runTest(dispatcher) {
        val f = fixture()
        val vm = f.viewModel()
        assertNull(vm.uiState.value.options)
        assertFalse(vm.uiState.value.showPasswordForm)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.showPasswordForm)
        vm.close(); f.close()
    }

    /**
     * A server saved by its LAN address: the native start opens on the LAN
     * base the app saved, and the flow is bound to the LAN origin.
     */
    @Test
    fun aLanSavedServerOpensTheNativeStartOnItsLanAddress() = runTest(dispatcher) {
        val f = fixture(serverUrl = "http://192.168.1.10:8096")
        val vm = f.viewModel()
        advanceUntilIdle()
        vm.onProviderClick(keycloak)
        advanceUntilIdle()
        assertTrue(
            vm.uiState.value.browserLaunch.orEmpty().startsWith("http://192.168.1.10:8096/api/v2/auth/oauth/5/native/start?"),
            vm.uiState.value.browserLaunch,
        )
        assertNull(vm.uiState.value.error)
        assertEquals("http://192.168.1.10:8096", f.store.load()?.startOrigin)
        assertEquals(listOf("http://192.168.1.10:8096"), f.registry.entries.value.map { it.url })
        vm.close(); f.close()
    }

    @Test
    fun useADifferentAccountAsksTheProviderToOfferAnother() = runTest(dispatcher) {
        val f = fixture()
        val vm = f.viewModel()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.offersAccountChoice)
        vm.onUseDifferentAccount()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.browserLaunch.orEmpty().contains("prompt=select_account"))
        vm.onBrowserLaunchHandled(opened = true)
        vm.close()

        // Several providers: the screen asks which one first.
        val other = keycloak.copy(id = "plugin:6:oidc", displayName = "Authentik", installationId = "6")
        val two = fixture(providers = listOf(keycloak, other))
        val chooser = two.viewModel()
        advanceUntilIdle()
        chooser.onUseDifferentAccount()
        assertTrue(chooser.uiState.value.choosingAccountProvider)
        assertNull(chooser.uiState.value.browserLaunch)
        chooser.onAccountProviderChosen(other)
        advanceUntilIdle()
        assertFalse(chooser.uiState.value.choosingAccountProvider)
        assertTrue(chooser.uiState.value.browserLaunch.orEmpty().contains("prompt=select_account"))
        chooser.close(); two.close()

        // Not offered where the server doesn't take it.
        f.handshake = ApiResult.Success(native.copy(selectAccount = false))
        val older = f.viewModel()
        advanceUntilIdle()
        assertFalse(older.uiState.value.offersAccountChoice)
        older.onUseDifferentAccount()
        advanceUntilIdle()
        assertNull(older.uiState.value.browserLaunch)
        older.close(); f.close()
    }

    /** After an explicit sign-out, not after a session that expired. */
    @Test
    fun anExplicitSignOutAsksForAnAccountOnce() = runTest(dispatcher) {
        val f = fixture()
        val vm = f.viewModel()
        advanceUntilIdle()
        vm.onProviderClick(keycloak)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.browserLaunch.orEmpty().contains("prompt="), "an expired session never asks")
        vm.onBrowserLaunchHandled(opened = true)

        f.coordinator.requestAccountChoice("a")
        vm.onProviderClick(keycloak)
        advanceUntilIdle()
        val state = vm.uiState.value.browserLaunch.orEmpty().also { assertTrue(it.contains("prompt=select_account")) }
            .substringAfter("app_state=").substringBefore('&')
        vm.onBrowserLaunchHandled(opened = true)
        f.coordinator.finish(
            NativeSignInCallback(code = "c", state = state, server = "srv-1", error = null, link = false, iss = "https://silo.example.test"),
        )
        advanceUntilIdle()
        assertFalse(f.coordinator.accountChoiceRequested("a"), "signing in ends it")
        vm.close(); f.close()
    }

    @Test
    fun switchAccountAddsSelectAccountWhenTheServerTakesIt() = runTest(dispatcher) {
        val f = fixture()
        val vm = f.viewModel()
        advanceUntilIdle()
        vm.onProviderClick(keycloak)
        advanceUntilIdle()
        assertFalse(vm.uiState.value.browserLaunch.orEmpty().contains("prompt="))
        vm.onBrowserLaunchHandled(opened = true)

        f.coordinator.requestAccountChoice("a")
        vm.onProviderClick(keycloak)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.browserLaunch.orEmpty().contains("prompt=select_account"))
        vm.onBrowserLaunchHandled(opened = true)
        vm.close()

        // A server that doesn't advertise it never gets it.
        f.handshake = ApiResult.Success(native.copy(selectAccount = false))
        val older = f.viewModel()
        advanceUntilIdle()
        older.onProviderClick(keycloak)
        advanceUntilIdle()
        assertFalse(older.uiState.value.browserLaunch.orEmpty().contains("prompt="))
        older.close(); f.close()
    }

    /**
     * "Not you? Switch account" where the provider takes `select_account`:
     * the login screen starts that provider's sign-in by itself, once.
     */
    @Test
    fun switchAccountStartsTheOnlyProvidersSignInByItself() = runTest(dispatcher) {
        val f = fixture()
        f.coordinator.requestAccountChoice("a", autoStart = true)
        val vm = f.viewModel()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.browserLaunch.orEmpty().contains("prompt=select_account"), vm.uiState.value.browserLaunch)
        vm.onBrowserLaunchHandled(opened = true)
        vm.close()

        val again = f.viewModel()
        advanceUntilIdle()
        assertNull(again.uiState.value.browserLaunch, "only once per request")
        again.close()

        // A plain sign-out asks for an account but opens nothing by itself.
        f.coordinator.requestAccountChoice("a")
        val plain = f.viewModel()
        advanceUntilIdle()
        assertNull(plain.uiState.value.browserLaunch)
        plain.close(); f.close()

        // Several providers: the person picks one.
        val other = keycloak.copy(id = "plugin:6:oidc", displayName = "Authentik", installationId = "6")
        val two = fixture(providers = listOf(keycloak, other))
        two.coordinator.requestAccountChoice("a", autoStart = true)
        val chooser = two.viewModel()
        advanceUntilIdle()
        assertNull(chooser.uiState.value.browserLaunch)
        chooser.close(); two.close()
    }

    /** The screen went away before the browser opened: the next login screen starts it instead. */
    @Test
    fun anAutoStartTheScreenNeverOpenedIsHandedBack() = runTest(dispatcher) {
        val f = fixture()
        f.coordinator.requestAccountChoice("a", autoStart = true)
        val store = ViewModelStore()
        val built = f.viewModel()
        val vm = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = built as T
            },
        )[LoginViewModel::class.java]
        advanceUntilIdle()
        assertTrue(vm.uiState.value.browserLaunch.orEmpty().contains("prompt=select_account"))
        store.clear()
        assertTrue(f.coordinator.consumeAutoStart("a"), "handed back")

        // Not after someone signed in there.
        f.coordinator.requestAccountChoice("a", autoStart = true)
        f.coordinator.clearAccountChoice("a")
        assertFalse(f.coordinator.consumeAutoStart("a"))
        f.coordinator.restoreAutoStart("a")
        assertFalse(f.coordinator.consumeAutoStart("a"))
        f.close()
    }

    @Test
    fun resultsForAnotherServerAreIgnored() = runTest(dispatcher) {
        val f = fixture()
        val vm = f.viewModel()
        advanceUntilIdle()
        val start = f.coordinator.begin(
            NativeSignInPurpose.SignIn, "b", "https://silo.example.test", "srv-1", "Keycloak", keycloak.nativeStartPath!!,
        )
        val state = assertIs<NativeSignInStart.Open>(start).url.substringAfter("app_state=").substringBefore('&')
        f.coordinator.finish(
            NativeSignInCallback(
                code = null, state = state, server = "srv-1", error = "not_permitted", link = false, iss = "https://silo.example.test",
            ),
        )
        advanceUntilIdle()
        assertNull(vm.uiState.value.error, "another saved server's failure isn't this screen's")
        vm.close(); f.close()
    }

    @Test
    fun aSignInResultRoutesOnlyWhileItsSessionIsInstalled() = runTest(dispatcher) {
        val f = fixture()
        val vm = f.viewModel()
        advanceUntilIdle()
        suspend fun finishOne() {
            val start = f.coordinator.begin(
                NativeSignInPurpose.SignIn, "a", "https://silo.example.test", "srv-1", "Keycloak", keycloak.nativeStartPath!!,
            )
            val state = assertIs<NativeSignInStart.Open>(start).url.substringAfter("app_state=").substringBefore('&')
            f.coordinator.finish(
                NativeSignInCallback(code = "c", state = state, server = "srv-1", error = null, link = false, iss = "https://silo.example.test"),
            )
            advanceUntilIdle()
        }
        // Signed out again before the result arrived: nowhere to go.
        finishOne()
        assertFalse(vm.uiState.value.loginSuccess)
        assertFalse(vm.uiState.value.isLoading)

        f.tokens.saveTokens("access", "refresh", 3600)
        finishOne()
        assertTrue(vm.uiState.value.loginSuccess, "this screen's server is active and its session installed")
        vm.onLoginSuccessConsumed()

        // Another saved server became active meanwhile: not this screen's to route.
        f.registry.activeServerId.value = "other"
        finishOne()
        assertFalse(vm.uiState.value.loginSuccess)
        vm.close(); f.close()
    }

    // --- Network identity ("Continue as …") ---

    /**
     * Opened at its Tailscale address, the server lists Tailscale with the
     * device's owner: one tap signs in, with no browser and no password, on
     * the saved base (path prefix included), and the session lands like a
     * password sign-in's.
     */
    @Test
    fun continueAsSignsInWithoutABrowserOnTheSavedBase() = runTest(dispatcher) {
        val f = fixture(serverUrl = "https://silo.tailnet.ts.net/silo", providers = listOf(keycloak, tailscale))
        f.networkAnswer = ApiResult.Success(LoginResponse("net-access", "net-refresh", 3600, User("1", "alice", "a@example.test", "user")))
        val vm = f.viewModel()
        advanceUntilIdle()
        assertEquals(tailscale, vm.uiState.value.networkProvider)
        assertEquals(listOf(keycloak), vm.uiState.value.providers, "the network provider is not a browser sign-in")
        assertTrue(vm.uiState.value.showPasswordForm, "the password form stays")
        assertEquals("Continue as Alice Example", continueAsLabel(tailscale))
        assertEquals("via Tailscale", continueViaLabel(tailscale))

        vm.onNetworkSignIn()
        assertTrue(vm.uiState.value.networkSignInBusy)
        advanceUntilIdle()
        assertEquals(listOf("https://silo.tailnet.ts.net/silo" to "/api/v2/auth/network/7/sign-in"), f.networkCalls)
        assertTrue(vm.uiState.value.loginSuccess)
        assertNull(vm.uiState.value.providerBusy)
        assertNull(vm.uiState.value.error)
        assertEquals("net-access", f.tokens.getAccessToken())
        assertEquals("net-refresh", f.tokens.getRefreshToken())
        assertNull(vm.uiState.value.browserLaunch)
        assertNull(f.store.load(), "no native flow was started")
        vm.close(); f.close()
    }

    @Test
    fun aRefusedNetworkSignInSaysWhyAndSavesNothing() = runTest(dispatcher) {
        val f = fixture(providers = listOf(tailscale))
        f.networkAnswer = ApiResult.Error(403, "network_identity_required", "raw detail")
        val vm = f.viewModel()
        advanceUntilIdle()
        vm.onNetworkSignIn()
        advanceUntilIdle()
        assertEquals("Open this server at its Tailscale address to sign in this way.", vm.uiState.value.error)
        assertFalse(vm.uiState.value.loginSuccess)
        assertNull(vm.uiState.value.providerBusy, "the button can be pressed again")
        assertNull(f.tokens.getAccessToken())

        f.networkAnswer = ApiResult.NetworkError(RuntimeException("offline"))
        vm.onNetworkSignIn()
        advanceUntilIdle()
        assertEquals("Network error. Please check your connection.", vm.uiState.value.error)
        assertEquals(2, f.networkCalls.size)
        vm.close(); f.close()
    }

    @Test
    fun noContinueAsWithoutANetworkProvider() = runTest(dispatcher) {
        val f = fixture(providers = listOf(keycloak, tailscale.copy(networkSignInPath = null)))
        val vm = f.viewModel()
        advanceUntilIdle()
        assertNull(vm.uiState.value.networkProvider)
        vm.onNetworkSignIn()
        advanceUntilIdle()
        assertTrue(f.networkCalls.isEmpty())
        vm.close(); f.close()
    }

    @Test
    fun theButtonNamesTheProviderWhenItNamesNobody() {
        val nobody = tailscale.copy(networkIdentity = NetworkIdentity("", ""))
        assertEquals("Continue with Tailscale", continueAsLabel(nobody))
        assertNull(continueViaLabel(nobody))
        assertEquals("Continue as alice", continueAsLabel(tailscale.copy(networkIdentity = NetworkIdentity("", "alice"))))
    }

    @Test
    fun networkSignInRefusalsHaveTheirOwnText() {
        val cases = listOf(
            403 to "network_identity_required",
            403 to "not_permitted",
            403 to "account_required",
            403 to "permission_denied",
            409 to "email_in_use",
            409 to "identity_linked_elsewhere",
            404 to "not_found",
            503 to "provider_unavailable",
            429 to "rate_limited",
            500 to "internal",
        )
        val texts = cases.map { (status, code) -> networkSignInMessage(ApiResult.Error(status, code, "server detail"), "Tailscale") }
        assertEquals(texts.size, texts.toSet().size, texts.joinToString("\n"))
        texts.forEach { assertFalse(it.contains("server detail"), "the server's detail is never shown") }
        assertEquals("Tailscale doesn't allow this device to sign in to this server.", texts[1])
        assertEquals(
            "An account with your email already exists. Sign in with your password, then connect Tailscale in Settings → Sign-in.",
            texts[4],
        )
        // Refusals other operations share read the same here.
        assertEquals(NativeSignInMessages.forReason(NativeSignInMessages.ACCOUNT_REQUIRED), texts[2])
        assertEquals(NativeSignInMessages.forReason(NativeSignInMessages.RATE_LIMITED), texts[8])
        assertEquals(NativeSignInMessages.forReason("identity_linked_elsewhere", "Tailscale"), texts[5])
    }

    @Test
    fun passwordRefusalsFromADirectoryHaveTheirOwnText() {
        assertEquals(
            NativeSignInMessages.forReason("email_in_use", "Directory"),
            passwordLoginMessage(org.siloserver.silo.network.ApiResult.Error(409, "email_in_use", "raw"), directoryName = "Directory"),
        )
        assertEquals(
            NativeSignInMessages.forReason("identity_linked_elsewhere", "Directory"),
            passwordLoginMessage(ApiResult.Error(409, "identity_linked_elsewhere", "raw"), directoryName = "Directory"),
        )
        assertEquals(
            NativeSignInMessages.forReason(NativeSignInMessages.RATE_LIMITED),
            passwordLoginMessage(ApiResult.Error(429, "", "raw")),
        )
    }

    /** Signs in whatever it is given; the tests look at routing, not redemption. */
    private class SignsIn : NativeSignInCompleter {
        override suspend fun signIn(pending: PendingNativeSignIn, code: String): ApiResult<User> =
            ApiResult.Success(User("1", "alice", "a@example.test", "user"))
        override suspend fun link(pending: PendingNativeSignIn, code: String): ApiResult<Unit> = ApiResult.Success(Unit)
    }

    private class OneServerRegistry(entry: ServerEntry) : ServerRegistry {
        private val active = MutableStateFlow<ServerEntry?>(entry)
        override val entries: StateFlow<List<ServerEntry>> = MutableStateFlow(listOf(entry))
        override val activeServerId = MutableStateFlow<String?>(entry.id)
        override val activeEntry: StateFlow<ServerEntry?> = active
        override suspend fun addOrUpdate(url: String, fetchedName: String?): String = active.value!!.id
        override suspend fun rename(serverId: String, userOverrideName: String?) = Unit
        override suspend fun setFetchedName(serverId: String, fetchedName: String?) = Unit
        override suspend fun setProfileId(serverId: String, profileId: String?) = Unit
        override suspend fun remove(serverId: String) = Unit
        override suspend fun signOut(serverId: String) = Unit
        override suspend fun switchTo(serverId: String) = Unit
        override suspend fun touchActive() = Unit
    }
}
