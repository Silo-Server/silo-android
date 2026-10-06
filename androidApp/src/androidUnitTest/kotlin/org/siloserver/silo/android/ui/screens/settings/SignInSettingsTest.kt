package org.siloserver.silo.android.ui.screens.settings

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.siloserver.silo.android.auth.InMemoryAccountChoiceStore
import org.siloserver.silo.android.auth.InMemoryPendingNativeSignInStore
import org.siloserver.silo.android.auth.NativeSignInCompleter
import org.siloserver.silo.android.auth.NativeSignInCoordinator
import org.siloserver.silo.android.auth.PendingNativeSignIn
import org.siloserver.silo.android.ui.screens.auth.passwordLoginMessage
import org.siloserver.silo.android.ui.screens.auth.networkSignInMessage
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
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.TokenManagerImpl
import org.siloserver.silo.network.api.DefaultExternalSignInApi
import org.siloserver.silo.network.api.ExternalSignInApi
import org.siloserver.silo.network.api.ServerConnections
import org.siloserver.silo.network.api.ServerIdentityApi
import org.siloserver.silo.network.api.ServerIdentityProbe
import org.siloserver.silo.repository.ExternalSignInRepository
import org.siloserver.silo.repository.ServerIdentityRepository
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignInSettingsTest {
    private val local = SignInProvider("local", "Silo account", SignInProvider.Mode.Credentials, true, null, null, null)
    private val ldap = SignInProvider("plugin:6:ldap", "Directory", SignInProvider.Mode.Credentials, false, null, "6", null)
    private val oidc = SignInProvider(
        "plugin:5:oidc", "Keycloak", SignInProvider.Mode.OAuth, false, null, "5",
        "/api/v2/auth/oauth/5/native/start",
    )
    private val identity = AccountIdentity("4", "5", "plugin:5:oidc", "Keycloak", "alice", "", "", "2026-01-02T03:04:05Z", null)
    private val all = OAuthHandshakeCapabilities(available = true, native = true, linking = true)
    private val tailscale = SignInProvider(
        "plugin:7:tailscale", "Tailscale", SignInProvider.Mode.Network, false, null, "7", null,
        networkSignInPath = "/api/v2/auth/network/7/sign-in",
        networkIdentity = NetworkIdentity("Alice Example", "alice@example.test"),
    )

    /** Offered only where the server serves network sign-in and listed the provider (over its network); no OAuth handshake needed. */
    @Test
    fun aNetworkProviderIsConnectableOnlyWhereNetworkSignInIsServed() {
        val offered = SignInSettingsViewModel.sectionOf(
            true, emptyList(), listOf(local, tailscale), OAuthHandshakeCapabilities.None, credentialsLinking = false, networkSignIn = true,
        )
        assertEquals(tailscale, offered.network)
        assertNull(offered.directory, "a network provider is not a directory")
        assertTrue(offered.connectable.isEmpty(), "nor a browser sign-in")
        assertNull(SignInSettingsViewModel.sectionOf(true, emptyList(), listOf(tailscale), all, credentialsLinking = true).network)
        val linked = identity.copy(installationId = "7", providerName = "Tailscale")
        assertNull(
            SignInSettingsViewModel.sectionOf(true, listOf(linked), listOf(tailscale), all, credentialsLinking = true, networkSignIn = true).network,
            "already connected",
        )
        assertNull(SignInSettingsViewModel.sectionOf(false, emptyList(), listOf(tailscale), all, credentialsLinking = true, networkSignIn = true).network)
        assertTrue(SignInSettingsUiState(loading = false, network = tailscale).visible)
    }

    @Test
    fun networkLinkRefusalsHaveTheirOwnText() {
        val codes = listOf(
            422 to DefaultExternalSignInApi.WRONG_PASSWORD,
            403 to "network_identity_required",
            409 to "local_password_required",
            403 to "not_permitted",
            403 to "permission_denied",
            409 to "identity_linked_elsewhere",
            409 to "already_linked",
            404 to "not_found",
            503 to "provider_unavailable",
            429 to "rate_limited",
        )
        val texts = codes.map { (status, code) ->
            SignInSettingsViewModel.networkLinkMessage(ApiResult.Error(status, code, "server detail"), "Tailscale")
        }
        assertEquals(texts.size, texts.toSet().size, texts.joinToString("\n"))
        texts.forEach { assertFalse(it.contains("server detail"), "the server's detail is never shown") }
        assertEquals("Open this server at its Tailscale address to connect Tailscale.", texts[1])
        assertEquals("Tailscale doesn't allow this device on this server.", texts[3])
        // The network refusals read as they do on the sign-in screen.
        assertEquals(networkSignInMessage(ApiResult.Error(403, "not_permitted", ""), "Tailscale"), texts[3])
        assertEquals(networkSignInMessage(ApiResult.Error(404, "not_found", ""), "Tailscale"), texts[7])
        // Shared refusals read as in the other links.
        assertEquals(SignInSettingsViewModel.directoryLinkMessage(ApiResult.Error(409, "identity_linked_elsewhere", ""), "Tailscale"), texts[5])
    }

    @Test
    fun theNetworkPromptNamesWhoIsConnected() {
        assertTrue(SignInSettingsViewModel.connectNetworkPrompt("Tailscale", "Alice Example").contains("connect Alice Example on Tailscale"))
        assertTrue(SignInSettingsViewModel.connectNetworkPrompt("Tailscale", null).contains("the Tailscale account this device uses"))
        // Connecting a network provider keeps the account's password.
        assertTrue(SignInSettingsViewModel.connectNetworkPrompt("Tailscale", null).contains("sign in with Tailscale or your password"))
        assertFalse(SignInSettingsViewModel.connectNetworkPrompt("Tailscale", null).contains("instead of your password"))
        assertTrue(SignInSettingsViewModel.connectNetworkDescription("Tailscale").contains("sign in with Tailscale or your password"))
        assertFalse(SignInSettingsViewModel.connectNetworkDescription("Tailscale").contains("instead of your password"))
    }

    @Test
    fun aServerWithoutIdentitiesShowsNoSection() {
        val section = SignInSettingsViewModel.sectionOf(false, listOf(identity), listOf(local, oidc, ldap), all, credentialsLinking = true)
        assertTrue(section.identities.isEmpty() && section.connectable.isEmpty() && section.directory == null)
    }

    @Test
    fun oidcIsConnectableThroughTheBrowserUntilLinked() {
        val unlinked = SignInSettingsViewModel.sectionOf(true, emptyList(), listOf(local, oidc), all, credentialsLinking = true)
        assertEquals(listOf(oidc), unlinked.connectable)
        val linked = SignInSettingsViewModel.sectionOf(true, listOf(identity), listOf(local, oidc), all, credentialsLinking = true)
        assertTrue(linked.connectable.isEmpty())
        assertEquals(listOf(identity), linked.identities)
        val noAppLinking = SignInSettingsViewModel.sectionOf(true, emptyList(), listOf(oidc), all.copy(linking = false), credentialsLinking = true)
        assertTrue(noAppLinking.connectable.isEmpty())
    }

    @Test
    fun ldapIsConnectableOnlyWithCredentialsLinking() {
        assertEquals(ldap, SignInSettingsViewModel.sectionOf(true, emptyList(), listOf(local, ldap), all, credentialsLinking = true).directory)
        assertNull(SignInSettingsViewModel.sectionOf(true, emptyList(), listOf(local, ldap), all, credentialsLinking = false).directory)
        assertNull(SignInSettingsViewModel.sectionOf(true, emptyList(), listOf(local), all, credentialsLinking = true).directory, "the local provider is not a directory")
        val ldapIdentity = identity.copy(installationId = "6")
        assertNull(SignInSettingsViewModel.sectionOf(true, listOf(ldapIdentity), listOf(ldap), all, credentialsLinking = true).directory)
    }

    /** `credentials_linking` comes from the external sign-in document, not the OAuth handshake. */
    @Test
    fun directoryLinkingNeedsNoOAuthHandshake() {
        assertEquals(
            ldap,
            SignInSettingsViewModel.sectionOf(true, emptyList(), listOf(local, ldap), OAuthHandshakeCapabilities.None, credentialsLinking = true)
                .directory,
        )
    }

    @Test
    fun canUnlinkDecidesDisconnect() {
        val only = SignInSettingsViewModel.sectionOf(true, listOf(identity), listOf(oidc), all, credentialsLinking = true, canUnlink = false)
        assertEquals(false, only.canUnlink)
        val free = SignInSettingsViewModel.sectionOf(true, listOf(identity), listOf(oidc), all, credentialsLinking = true, canUnlink = true)
        assertEquals(true, free.canUnlink)
        assertNull(SignInSettingsViewModel.sectionOf(true, emptyList(), listOf(oidc), all, credentialsLinking = true, canUnlink = false).canUnlink)
        // With can_unlink the confirmation no longer hedges.
        assertFalse(SignInSettingsViewModel.disconnectConfirmBody(identity, canUnlink = true).contains("another way to sign in"))
        assertTrue(SignInSettingsViewModel.ONLY_SIGN_IN_METHOD.contains("only way to sign in"))
    }

    @Test
    fun directoryRefusalsHaveTheirOwnText() {
        val codes = listOf(
            422 to DefaultExternalSignInApi.WRONG_PASSWORD,
            422 to DefaultExternalSignInApi.DIRECTORY_REFUSED,
            409 to "local_password_required",
            403 to "not_permitted",
            403 to "account_disabled",
            403 to "password_expired",
            403 to "permission_denied",
            409 to "identity_linked_elsewhere",
            409 to "already_linked",
            404 to "not_found",
            503 to "provider_unavailable",
            429 to "rate_limited",
        )
        val texts = codes.map { (status, code) ->
            SignInSettingsViewModel.directoryLinkMessage(ApiResult.Error(status, code, "server detail"), "Directory")
        }
        assertEquals(texts.size, texts.toSet().size, texts.joinToString("\n"))
        texts.forEach { assertFalse(it.contains("server detail"), "the server's detail is never shown") }
        assertTrue(texts[1].contains("Directory"))
        // Typing mistakes and throttling keep the dialog open; the rest close it.
        assertTrue(SignInSettingsViewModel.keepsPromptOpen(ApiResult.Error(422, DefaultExternalSignInApi.WRONG_PASSWORD, "")))
        assertTrue(SignInSettingsViewModel.keepsPromptOpen(ApiResult.Error(429, "rate_limited", "")))
        assertFalse(SignInSettingsViewModel.keepsPromptOpen(ApiResult.Error(409, "identity_linked_elsewhere", "")))
    }

    @Test
    fun linkTicketAndDisconnectRefusals() {
        assertEquals(
            "That password is incorrect.",
            SignInSettingsViewModel.linkTicketMessage(ApiResult.Error(422, DefaultExternalSignInApi.WRONG_PASSWORD, ""), "Keycloak"),
        )
        assertTrue(SignInSettingsViewModel.linkTicketMessage(ApiResult.Error(409, "conflict", ""), "Keycloak").contains("no password"))
        assertTrue(SignInSettingsViewModel.linkTicketMessage(ApiResult.Error(403, "permission_denied", ""), "Keycloak").contains("session"))
        assertTrue(
            SignInSettingsViewModel.disconnectMessage(ApiResult.Error(409, "last_sign_in_method", ""), identity)
                .contains("only way to sign in"),
        )
        assertTrue(SignInSettingsViewModel.disconnectMessage(ApiResult.Error(404, "not_found", ""), identity).contains("already"))
    }

    @Test
    fun connectSaysThePasswordStopsWorkingAndDisconnectDoesNotPromiseIt() {
        assertTrue(SignInSettingsViewModel.connectDescription("Keycloak").contains("instead of your password"))
        assertTrue(SignInSettingsViewModel.connectPrompt("Keycloak").contains("instead of your password"))
        assertTrue(SignInSettingsViewModel.connectDirectoryDescription("Directory").contains("Directory username and password instead"))
        assertTrue(SignInSettingsViewModel.connectDirectoryPrompt("Directory").contains("username and password instead"))
        val body = SignInSettingsViewModel.disconnectConfirmBody(identity)
        assertFalse(body.contains("such as your password"), body)
        assertTrue(body.contains("another way to sign in"), body)
    }

    @Test
    fun sharedRefusalsReadTheSameInEveryOperation() {
        val tooMany = ApiResult.Error(429, "rate_limited", "")
        assertEquals(
            SignInSettingsViewModel.linkTicketMessage(tooMany, "Keycloak"),
            SignInSettingsViewModel.disconnectMessage(tooMany, identity),
        )
        val denied = ApiResult.Error(403, "permission_denied", "")
        assertEquals(
            SignInSettingsViewModel.linkTicketMessage(denied, "Keycloak"),
            SignInSettingsViewModel.directoryLinkMessage(denied, "Keycloak"),
        )
    }

    @Test
    fun passwordLoginNamesLocalLoginDisabled() {
        assertEquals(
            "Password sign-in is turned off on this server. Sign in with Keycloak instead.",
            passwordLoginMessage(ApiResult.Error(403, "local_login_disabled", ""), "Keycloak"),
        )
        assertEquals(
            "Password sign-in is turned off on this server.",
            passwordLoginMessage(ApiResult.Error(403, "local_login_disabled", "")),
        )
        assertEquals("Invalid username or password", passwordLoginMessage(ApiResult.Error(401, "invalid_credentials", "")))
        assertTrue(passwordLoginMessage(ApiResult.Error(403, "password_expired", "")).contains("expired"))
        assertTrue(passwordLoginMessage(ApiResult.Error(503, "provider_unavailable", "")).contains("can't be reached"))
    }
}

/** "Connect <network provider>" in the account's Sign-in settings: the password-only link. */
@OptIn(ExperimentalCoroutinesApi::class)
class SignInSettingsNetworkLinkTest {
    private val dispatcher = StandardTestDispatcher()

    @BeforeTest fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterTest fun tearDown() = Dispatchers.resetMain()

    private val scope = AuthScopeSnapshot("a", "p1", "https://silo.tailnet.ts.net", null, identityGeneration = 1)
    private val tailscale = SignInProvider(
        "plugin:7:tailscale", "Tailscale", SignInProvider.Mode.Network, false, null, "7", null,
        networkSignInPath = "/api/v2/auth/network/7/sign-in",
        networkIdentity = NetworkIdentity("Alice Example", "alice@example.test"),
    )
    private val linkedIdentity = AccountIdentity(
        "8", "7", "plugin:7:tailscale", "Tailscale", "alice@example.test", "alice@example.test", "Alice Example",
        "2026-01-02T03:04:05.000Z", null,
    )

    private class Api(private val provider: SignInProvider, private val linked: AccountIdentity) : ExternalSignInApi {
        var identities = emptyList<AccountIdentity>()
        var linkAnswer: ApiResult<AccountIdentity> = ApiResult.Success(linked)
        val links = mutableListOf<Triple<AuthScopeSnapshot, String, String>>()

        /** When set, the link answers only once this completes. */
        var linkGate: CompletableDeferred<Unit>? = null

        override suspend fun listProviders(serverUrl: String): ApiResult<SignInProviders> =
            ApiResult.Success(SignInProviders(listOf(provider), passwordLogin = true))
        override suspend fun oauthCapabilities(serverUrl: String): ApiResult<OAuthHandshakeCapabilities> =
            ApiResult.Error(404, "not_found", "")
        override suspend fun externalSignInCapabilities(scope: AuthScopeSnapshot): ApiResult<ExternalSignInCapabilities> =
            ApiResult.Success(ExternalSignInCapabilities(identities = true, networkSignIn = true))
        /** Each read takes the next gate, if any, and answers with the identities as they were when asked. */
        val identityGates = ArrayDeque<CompletableDeferred<Unit>>()
        override suspend fun listIdentities(scope: AuthScopeSnapshot): ApiResult<AccountIdentities> {
            val asked = identities
            identityGates.removeFirstOrNull()?.await()
            return ApiResult.Success(AccountIdentities(asked, canUnlink = null))
        }
        override suspend fun linkWithNetwork(scope: AuthScopeSnapshot, installationId: String, password: String): ApiResult<AccountIdentity> {
            links += Triple(scope, installationId, password)
            linkGate?.await()
            return linkAnswer.also { if (it is ApiResult.Success) identities = listOf(it.data) }
        }
        override suspend fun completeOAuthLogin(serverUrl: String, code: String, codeVerifier: String): ApiResult<LoginResponse> = TODO()
        override suspend fun signInWithNetworkIdentity(serverUrl: String, signInPath: String): ApiResult<LoginResponse> = TODO()
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
    }

    /** Signed in on saved server "a". */
    private inner class SignedIn : TokenManager by TokenManagerImpl() {
        override suspend fun snapshotCurrentScope(): AuthScopeSnapshot = scope
        override suspend fun loginSessionId(serverId: String): String = "login-1"
    }

    private object NoBrowser : NativeSignInCompleter {
        override suspend fun signIn(pending: PendingNativeSignIn, code: String): ApiResult<User> = error("no browser flow here")
        override suspend fun link(pending: PendingNativeSignIn, code: String): ApiResult<Unit> = error("no browser flow here")
    }

    private class OneServer(private val entry: ServerEntry) : ServerRegistry {
        override val entries: StateFlow<List<ServerEntry>> = MutableStateFlow(listOf(entry))
        override val activeServerId = MutableStateFlow<String?>(entry.id)
        override val activeEntry: StateFlow<ServerEntry?> = MutableStateFlow(entry)
        override suspend fun addOrUpdate(url: String, fetchedName: String?): String = entry.id
        override suspend fun rename(serverId: String, userOverrideName: String?) = Unit
        override suspend fun setFetchedName(serverId: String, fetchedName: String?) = Unit
        override suspend fun setProfileId(serverId: String, profileId: String?) = Unit
        override suspend fun remove(serverId: String) = Unit
        override suspend fun signOut(serverId: String) = Unit
        override suspend fun switchTo(serverId: String) = Unit
        override suspend fun touchActive() = Unit
    }

    @Test
    fun connectingAsksForThePasswordOnlyAndShowsTheLinkedIdentity() = runTest(dispatcher) {
        val api = Api(tailscale, linkedIdentity)
        val registry = OneServer(ServerEntry(id = "a", url = "https://silo.tailnet.ts.net"))
        val identityApi = object : ServerIdentityApi {
            override suspend fun probeIdentity(serverUrl: String) = ServerIdentityProbe.Identity("srv-1")
            override suspend fun connections(scope: AuthScopeSnapshot): ServerConnections? = null
        }
        val vm = SignInSettingsViewModel(
            externalSignIn = ExternalSignInRepository(api),
            tokenManager = SignedIn(),
            serverRegistry = registry,
            serverIdentities = ServerIdentityRepository(registry, identityApi),
            nativeSignIn = NativeSignInCoordinator(InMemoryPendingNativeSignInStore(), NoBrowser, backgroundScope, InMemoryAccountChoiceStore()),
        )
        advanceUntilIdle()
        assertEquals(tailscale, vm.uiState.value.network)
        assertTrue(vm.uiState.value.visible)

        vm.onConnectNetwork(tailscale)
        assertEquals(tailscale, vm.uiState.value.networkPrompt)
        vm.onConfirmNetwork("")
        assertEquals("Enter your password.", vm.uiState.value.passwordError)
        assertTrue(api.links.isEmpty())

        // A wrong password keeps the prompt open to try again.
        api.linkAnswer = ApiResult.Error(422, DefaultExternalSignInApi.WRONG_PASSWORD, "")
        vm.onConfirmNetwork("wrong")
        advanceUntilIdle()
        assertEquals("That password is incorrect.", vm.uiState.value.passwordError)
        assertEquals(tailscale, vm.uiState.value.networkPrompt)
        assertEquals(false, vm.uiState.value.busy)

        api.linkAnswer = ApiResult.Success(linkedIdentity)
        vm.onConfirmNetwork("local-pw")
        advanceUntilIdle()
        assertEquals(Triple(scope, "7", "local-pw"), api.links.last())
        assertNull(vm.uiState.value.networkPrompt)
        assertEquals("Connected Tailscale.", vm.uiState.value.message)
        assertEquals(listOf(linkedIdentity), vm.uiState.value.identities)
        assertNull(vm.uiState.value.network, "connected: not offered again")
        vm.viewModelScope.cancel()
    }

    @Test
    fun aRefusalThatTypingWontFixClosesThePrompt() = runTest(dispatcher) {
        val api = Api(tailscale, linkedIdentity)
        val registry = OneServer(ServerEntry(id = "a", url = "https://silo.tailnet.ts.net"))
        val identityApi = object : ServerIdentityApi {
            override suspend fun probeIdentity(serverUrl: String) = ServerIdentityProbe.Identity("srv-1")
            override suspend fun connections(scope: AuthScopeSnapshot): ServerConnections? = null
        }
        val vm = SignInSettingsViewModel(
            ExternalSignInRepository(api), SignedIn(), registry, ServerIdentityRepository(registry, identityApi),
            NativeSignInCoordinator(InMemoryPendingNativeSignInStore(), NoBrowser, backgroundScope, InMemoryAccountChoiceStore()),
        )
        advanceUntilIdle()
        vm.onConnectNetwork(tailscale)
        api.linkAnswer = ApiResult.Error(403, "network_identity_required", "")
        vm.onConfirmNetwork("local-pw")
        advanceUntilIdle()
        assertNull(vm.uiState.value.networkPrompt)
        assertEquals("Open this server at its Tailscale address to connect Tailscale.", vm.uiState.value.error)
        assertTrue(vm.uiState.value.identities.isEmpty())
        vm.viewModelScope.cancel()
    }

    /** Cancel while "Checking…" stops the link: nothing reports back, and the section shows what the server has. */
    @Test
    fun cancellingTheNetworkPromptStopsTheLink() = runTest(dispatcher) {
        val api = Api(tailscale, linkedIdentity)
        val gate = CompletableDeferred<Unit>()
        api.linkGate = gate
        val registry = OneServer(ServerEntry(id = "a", url = "https://silo.tailnet.ts.net"))
        val identityApi = object : ServerIdentityApi {
            override suspend fun probeIdentity(serverUrl: String) = ServerIdentityProbe.Identity("srv-1")
            override suspend fun connections(scope: AuthScopeSnapshot): ServerConnections? = null
        }
        val vm = SignInSettingsViewModel(
            ExternalSignInRepository(api), SignedIn(), registry, ServerIdentityRepository(registry, identityApi),
            NativeSignInCoordinator(InMemoryPendingNativeSignInStore(), NoBrowser, backgroundScope, InMemoryAccountChoiceStore()),
        )
        advanceUntilIdle()
        vm.onConnectNetwork(tailscale)
        vm.onConfirmNetwork("local-pw")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.busy)
        assertEquals(1, api.links.size)

        vm.onDismissNetworkPrompt()
        assertNull(vm.uiState.value.networkPrompt)
        assertFalse(vm.uiState.value.busy, "the section can be used again")
        gate.complete(Unit)
        advanceUntilIdle()
        assertNull(vm.uiState.value.message, "a cancelled link doesn't report \"Connected\"")
        assertNull(vm.uiState.value.error)
        assertEquals(tailscale, vm.uiState.value.network, "the server made no link: still offered")
        vm.viewModelScope.cancel()
    }

    /** The reload after a cancelled link answers late: it doesn't undo the link made after it. */
    @Test
    fun aLateReloadDoesNotOverwriteANewerOne() = runTest(dispatcher) {
        val api = Api(tailscale, linkedIdentity)
        val registry = OneServer(ServerEntry(id = "a", url = "https://silo.tailnet.ts.net"))
        val identityApi = object : ServerIdentityApi {
            override suspend fun probeIdentity(serverUrl: String) = ServerIdentityProbe.Identity("srv-1")
            override suspend fun connections(scope: AuthScopeSnapshot): ServerConnections? = null
        }
        val vm = SignInSettingsViewModel(
            ExternalSignInRepository(api), SignedIn(), registry, ServerIdentityRepository(registry, identityApi),
            NativeSignInCoordinator(InMemoryPendingNativeSignInStore(), NoBrowser, backgroundScope, InMemoryAccountChoiceStore()),
        )
        advanceUntilIdle()
        api.linkGate = CompletableDeferred()
        vm.onConnectNetwork(tailscale)
        vm.onConfirmNetwork("local-pw")
        advanceUntilIdle()
        val lateReload = CompletableDeferred<Unit>()
        api.identityGates += lateReload
        vm.onDismissNetworkPrompt()
        advanceUntilIdle()

        api.linkGate = null
        vm.onConnectNetwork(tailscale)
        vm.onConfirmNetwork("local-pw")
        advanceUntilIdle()
        assertEquals(listOf(linkedIdentity), vm.uiState.value.identities)

        lateReload.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(linkedIdentity), vm.uiState.value.identities, "the older read is dropped")
        assertNull(vm.uiState.value.network, "connected: not offered again")
        vm.viewModelScope.cancel()
    }
}
