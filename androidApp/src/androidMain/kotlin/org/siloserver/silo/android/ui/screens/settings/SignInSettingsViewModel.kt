package org.siloserver.silo.android.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.siloserver.silo.android.auth.NativeSignInCoordinator
import org.siloserver.silo.android.auth.NativeSignInMessages
import org.siloserver.silo.android.auth.NativeSignInPurpose
import org.siloserver.silo.android.auth.NativeSignInResult
import org.siloserver.silo.android.auth.NativeSignInStart
import org.siloserver.silo.android.ui.screens.auth.ProviderRefusalText
import org.siloserver.silo.model.auth.AccountIdentity
import org.siloserver.silo.model.auth.NetworkSignInFailure
import org.siloserver.silo.model.auth.OAuthHandshakeCapabilities
import org.siloserver.silo.model.auth.SignInOptions
import org.siloserver.silo.model.auth.SignInProvider
import org.siloserver.silo.model.auth.SignInProviders
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.api.DefaultExternalSignInApi
import org.siloserver.silo.repository.ExternalSignInRepository
import org.siloserver.silo.repository.ServerIdentityRepository

data class SignInSettingsUiState(
    val loading: Boolean = true,
    /** Identities linked to this account, with what the server says about each. */
    val identities: List<AccountIdentity> = emptyList(),
    /**
     * The server's `can_unlink`: false hides Disconnect (the identity is the
     * account's only way to sign in), null when the server doesn't say.
     */
    val canUnlink: Boolean? = null,
    /** Providers this account can connect from the app (OAuth, with linking served). */
    val connectable: List<SignInProvider> = emptyList(),
    /** A directory (LDAP) provider this account can connect with its directory credentials. */
    val directory: SignInProvider? = null,
    /** Asking for the account password and the directory credentials before connecting [directory]. */
    val directoryPrompt: SignInProvider? = null,
    /**
     * A network provider (such as Tailscale) this account can connect: the
     * server lists one only to a device on that provider's network, and links
     * the person who owns this device there.
     */
    val network: SignInProvider? = null,
    /** Asking for the account password before connecting [network]. */
    val networkPrompt: SignInProvider? = null,
    /** Asking for the local password before connecting this provider. */
    val passwordPrompt: SignInProvider? = null,
    val passwordError: String? = null,
    /** Asking before disconnecting this identity. */
    val confirmDisconnect: AccountIdentity? = null,
    /** A connect or disconnect is under way. */
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    /** A start URL for the screen to open in a Custom Tab, once. */
    val browserLaunch: String? = null,
) {
    /** The section shows once the server has an external provider or the account has an identity. */
    val visible: Boolean get() = identities.isNotEmpty() || connectable.isNotEmpty() || directory != null || network != null
}

/**
 * Account settings "Sign-in" section (silo-server external sign-in spec):
 * the provider identity linked to this account, "Connect <provider>" after
 * the account re-enters its local password (a link ticket, then the native
 * browser flow with `link_ticket`; for a directory or a network provider, one
 * call with no browser), and "Disconnect", which the server allows only while
 * the account can still sign in another way.
 */
class SignInSettingsViewModel(
    private val externalSignIn: ExternalSignInRepository,
    private val tokenManager: TokenManager,
    private val serverRegistry: ServerRegistry,
    private val serverIdentities: ServerIdentityRepository,
    private val nativeSignIn: NativeSignInCoordinator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SignInSettingsUiState())

    /**
     * The open prompt's work: the ticket request and flow start until the
     * browser opens, or a directory or network link until it answers.
     */
    private var connectJob: Job? = null

    /** Bumped by each [load]; only the newest read publishes, so an older one can't overwrite it. */
    private var loadGeneration = 0
    val uiState: StateFlow<SignInSettingsUiState> = _uiState.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            nativeSignIn.result.filterNotNull().collect(::onNativeResult)
        }
    }

    fun refresh() {
        viewModelScope.launch { load() }
    }

    /**
     * Reads the section for the signed-in account. Identities belong to the
     * account, so an answer is shown only while the same login is still
     * signed in on the same server; otherwise the section is read again.
     */
    private suspend fun load() {
        val generation = ++loadGeneration
        val scope = tokenManager.snapshotCurrentScope()
        val loginSessionId = scope?.let { tokenManager.loginSessionId(it.serverId) }
        if (scope == null) {
            if (generation != loadGeneration) return
            _uiState.update {
                it.copy(
                    loading = false,
                    identities = emptyList(),
                    canUnlink = null,
                    connectable = emptyList(),
                    directory = null,
                    network = null,
                )
            }
            return
        }
        val section = coroutineScope {
            val capabilities = async { externalSignIn.externalSignInCapabilities(scope) }
            val identities = async { externalSignIn.identities(scope) }
            val providers = async { externalSignIn.providers(scope.serverUrl) }
            val handshake = async { externalSignIn.oauthCapabilities(scope.serverUrl) }
            val served = (capabilities.await() as? ApiResult.Success)?.data
            val listed = (identities.await() as? ApiResult.Success)?.data
            sectionOf(
                identitiesServed = served?.identities == true,
                identities = listed?.items.orEmpty(),
                providers = (providers.await() as? ApiResult.Success)?.data?.providers.orEmpty(),
                handshake = (handshake.await() as? ApiResult.Success)?.data ?: OAuthHandshakeCapabilities.None,
                credentialsLinking = served?.credentialsLinking == true,
                canUnlink = listed?.canUnlink,
                networkSignIn = served?.networkSignIn == true,
            )
        }
        val now = tokenManager.snapshotCurrentScope()
        val sameLogin = now?.serverId == scope.serverId && tokenManager.loginSessionId(scope.serverId) == loginSessionId
        // A later read (a reload after a link, say) started meanwhile: it has the newer answer.
        if (generation != loadGeneration) return
        if (!sameLogin) {
            load()
            return
        }
        _uiState.update {
            it.copy(
                loading = false,
                identities = section.identities,
                canUnlink = section.canUnlink,
                connectable = section.connectable,
                directory = section.directory,
                network = section.network,
            )
        }
    }

    fun onConnectDirectory(provider: SignInProvider) {
        _uiState.update { it.copy(directoryPrompt = provider, passwordError = null, message = null, error = null) }
    }

    fun onDismissDirectoryPrompt() = dismissPrompt { it.copy(directoryPrompt = null) }

    /**
     * Links the directory (LDAP) account whose credentials the person typed,
     * after confirming this account's own password. No browser is involved:
     * the server checks both and links in one call.
     */
    fun onConfirmDirectory(password: String, username: String, directoryPassword: String) {
        val provider = _uiState.value.directoryPrompt ?: return
        val installationId = provider.installationId ?: return
        if (_uiState.value.busy) return
        if (password.isEmpty() || username.isBlank() || directoryPassword.isEmpty()) {
            _uiState.update { it.copy(passwordError = "Fill in all three fields.") }
            return
        }
        linkInOneCall(
            provider = provider,
            closePrompt = { it.copy(directoryPrompt = null) },
            refusal = { directoryLinkMessage(it, provider.displayName) },
        ) { scope -> externalSignIn.linkWithCredentials(scope, installationId, password, username.trim(), directoryPassword) }
    }

    fun onConnectNetwork(provider: SignInProvider) {
        _uiState.update { it.copy(networkPrompt = provider, passwordError = null, message = null, error = null) }
    }

    fun onDismissNetworkPrompt() = dismissPrompt { it.copy(networkPrompt = null) }

    /**
     * Links the person who owns this device at the network provider, after
     * confirming this account's own password. Like a directory link, the
     * server checks both in one call; the device says who the person is.
     */
    fun onConfirmNetwork(password: String) {
        val provider = _uiState.value.networkPrompt ?: return
        val installationId = provider.installationId ?: return
        if (_uiState.value.busy) return
        if (password.isEmpty()) {
            _uiState.update { it.copy(passwordError = "Enter your password.") }
            return
        }
        linkInOneCall(
            provider = provider,
            closePrompt = { it.copy(networkPrompt = null) },
            refusal = { networkLinkMessage(it, provider.displayName) },
        ) { scope -> externalSignIn.linkWithNetwork(scope, installationId, password) }
    }

    /**
     * A link the server makes in one call, with no browser (directory and
     * network providers): the open prompt closes on success and on refusals
     * that typing again won't fix, and stays open for the rest.
     */
    private fun linkInOneCall(
        provider: SignInProvider,
        closePrompt: (SignInSettingsUiState) -> SignInSettingsUiState,
        refusal: (ApiResult.Error) -> String,
        link: suspend (AuthScopeSnapshot) -> ApiResult<AccountIdentity>,
    ) {
        _uiState.update { it.copy(busy = true, passwordError = null) }
        connectJob = viewModelScope.launch {
            val scope = tokenManager.snapshotCurrentScope()
            if (scope == null) {
                _uiState.update { closePrompt(it).copy(busy = false, error = ACCOUNT_CHANGED) }
                return@launch
            }
            when (val linked = link(scope)) {
                is ApiResult.Success -> {
                    load()
                    _uiState.update { closePrompt(it).copy(busy = false, message = "Connected ${provider.displayName}.") }
                }
                is ApiResult.Error -> {
                    val message = refusal(linked)
                    if (keepsPromptOpen(linked)) {
                        _uiState.update { it.copy(busy = false, passwordError = message) }
                    } else {
                        _uiState.update { closePrompt(it).copy(busy = false, error = message) }
                    }
                }
                is ApiResult.NetworkError -> _uiState.update {
                    it.copy(busy = false, passwordError = NativeSignInMessages.forNetworkError(linked, provider.displayName))
                }
            }
        }
    }

    fun onConnect(provider: SignInProvider) {
        _uiState.update { it.copy(passwordPrompt = provider, passwordError = null, message = null, error = null) }
    }

    fun onDismissPasswordPrompt() = dismissPrompt { it.copy(passwordPrompt = null) }

    /**
     * Cancel also stops the prompt's work still under way: no browser opens
     * and nothing reports back once the prompt is gone. A link the server
     * made before the cancel shows when the section reloads.
     */
    private fun dismissPrompt(close: (SignInSettingsUiState) -> SignInSettingsUiState) {
        val stopped = connectJob?.isActive == true
        connectJob?.cancel()
        connectJob = null
        _uiState.update { close(it).copy(passwordError = null, busy = it.busy && !stopped) }
        if (stopped) refresh()
    }

    /**
     * The local password confirmed: get a link ticket, then start the native
     * linking flow for this account and server.
     */
    fun onConfirmPassword(password: String) {
        val provider = _uiState.value.passwordPrompt ?: return
        val installationId = provider.installationId ?: return
        val startPath = provider.nativeStartPath ?: return
        if (_uiState.value.busy) return
        if (password.isEmpty()) {
            _uiState.update { it.copy(passwordError = "Enter your password.") }
            return
        }
        _uiState.update { it.copy(busy = true, passwordError = null) }
        connectJob = viewModelScope.launch {
            val scope = tokenManager.snapshotCurrentScope()
            val entry = serverRegistry.activeEntry.value
            val loginSessionId = scope?.let { tokenManager.loginSessionId(it.serverId) }
            if (scope == null || entry == null || entry.id != scope.serverId || loginSessionId == null) {
                _uiState.update { it.copy(busy = false, passwordPrompt = null, error = ACCOUNT_CHANGED) }
                return@launch
            }
            val ticket = when (val issued = externalSignIn.linkTicket(scope, installationId, password)) {
                is ApiResult.Success -> issued.data
                is ApiResult.Error -> {
                    val message = linkTicketMessage(issued, provider.displayName)
                    if (keepsPromptOpen(issued)) {
                        _uiState.update { it.copy(busy = false, passwordError = message) }
                    } else {
                        _uiState.update { it.copy(busy = false, passwordPrompt = null, error = message) }
                    }
                    return@launch
                }
                is ApiResult.NetworkError -> {
                    _uiState.update {
                        it.copy(busy = false, passwordError = NativeSignInMessages.forNetworkError(issued, provider.displayName))
                    }
                    return@launch
                }
            }
            val serverId = serverIdentities.identityOf(entry, refresh = true)
            if (serverId == null) {
                _uiState.update {
                    it.copy(busy = false, passwordPrompt = null, error = "Couldn't confirm this server's identity. Try again.")
                }
                return@launch
            }
            val start = nativeSignIn.begin(
                purpose = NativeSignInPurpose.Link,
                serverEntryId = entry.id,
                serverUrl = scope.serverUrl,
                verifiedServerId = serverId,
                providerName = provider.displayName,
                nativeStartPath = startPath,
                linkTicket = ticket.ticket,
                loginSessionId = loginSessionId,
            )
            when (start) {
                is NativeSignInStart.Open -> _uiState.update { it.copy(passwordPrompt = null, browserLaunch = start.url) }
                is NativeSignInStart.Refused ->
                    _uiState.update { it.copy(busy = false, passwordPrompt = null, error = start.message) }
            }
        }
    }

    /** The browser owns the flow now; the redirect brings the outcome. */
    fun onBrowserLaunchHandled(opened: Boolean) {
        _uiState.update {
            it.copy(
                browserLaunch = null,
                busy = false,
                error = if (opened) it.error else NativeSignInMessages.forReason(NativeSignInMessages.NO_BROWSER),
            )
        }
    }

    fun onDisconnect(identity: AccountIdentity) {
        _uiState.update { it.copy(confirmDisconnect = identity, message = null, error = null) }
    }

    fun onDismissDisconnect() {
        _uiState.update { it.copy(confirmDisconnect = null) }
    }

    fun onConfirmDisconnect() {
        val identity = _uiState.value.confirmDisconnect ?: return
        if (_uiState.value.busy) return
        _uiState.update { it.copy(confirmDisconnect = null, busy = true) }
        viewModelScope.launch {
            val scope: AuthScopeSnapshot? = tokenManager.snapshotCurrentScope()
            if (scope == null) {
                _uiState.update { it.copy(busy = false, error = ACCOUNT_CHANGED) }
                return@launch
            }
            when (val result = externalSignIn.disconnect(scope, identity.id)) {
                is ApiResult.Success -> {
                    load()
                    _uiState.update { it.copy(busy = false, message = "Disconnected ${providerLabel(identity)}.") }
                }
                is ApiResult.Error -> _uiState.update { it.copy(busy = false, error = disconnectMessage(result, identity)) }
                is ApiResult.NetworkError -> _uiState.update { it.copy(busy = false, error = NativeSignInMessages.forNetworkError(result)) }
            }
        }
    }

    private suspend fun onNativeResult(result: NativeSignInResult) {
        if (result.purpose != NativeSignInPurpose.Link) return
        // A link finished for another saved server belongs to that server's settings.
        if (result.serverEntryId != serverRegistry.activeServerId.value) return
        when (result) {
            is NativeSignInResult.Finishing -> _uiState.update { it.copy(busy = true, error = null, message = null) }
            is NativeSignInResult.Linked -> {
                nativeSignIn.consume(result)
                val loginSessionId = tokenManager.loginSessionId(result.serverEntryId)
                load()
                // Still the server and login the link was made for once the
                // section has reloaded. `busy` came from this flow's Finishing,
                // and nothing else can start while it's set, so it clears either way.
                val here = result.serverEntryId == serverRegistry.activeServerId.value &&
                    tokenManager.loginSessionId(result.serverEntryId) == loginSessionId
                _uiState.update {
                    it.copy(busy = false, message = if (here) "Connected ${result.providerName}." else it.message)
                }
            }
            is NativeSignInResult.Failed -> {
                nativeSignIn.consume(result)
                _uiState.update { it.copy(busy = false, error = result.message) }
            }
            is NativeSignInResult.SignedIn -> Unit
        }
    }

    /** What the section shows, from the server's answers. */
    data class Section(
        val identities: List<AccountIdentity>,
        val connectable: List<SignInProvider>,
        val directory: SignInProvider?,
        val canUnlink: Boolean? = null,
        val network: SignInProvider? = null,
    )

    companion object {
        const val ACCOUNT_CHANGED = "The account changed. Open Settings again and retry."
        private const val CHECK_ENTRIES = "Check what you entered, then try again."

        /** "Connect <provider>": what the account gives up by connecting. */
        fun connectDescription(providerName: String): String =
            "After connecting, you sign in with $providerName instead of your password. You'll confirm your password first."

        /** "Connect <provider>" for a network provider: the account keeps its password. */
        fun connectNetworkDescription(providerName: String): String =
            "After connecting, you can sign in with $providerName or your password. You'll confirm your password first."

        fun connectDirectoryDescription(providerName: String): String =
            "After connecting, you sign in with your $providerName username and password instead of this account's password."

        fun connectPrompt(providerName: String): String =
            "Enter this account's password, then sign in with $providerName in your browser. " +
                "After connecting, you sign in with $providerName instead of your password."

        fun connectDirectoryPrompt(providerName: String): String =
            "Confirm this account's password, then enter your $providerName username and password. " +
                "After connecting, you sign in with your $providerName username and password instead."

        /**
         * The password prompt for a network provider. [owner] is who the
         * provider says owns this device, the person being connected.
         */
        fun connectNetworkPrompt(providerName: String, owner: String?): String {
            val who = owner?.let { "$it on $providerName" } ?: "the $providerName account this device uses"
            return "Enter this account's password to connect $who. " +
                "You can then sign in with $providerName or your password."
        }

        /**
         * Linking an OAuth or directory provider turns the account's own
         * password off (a network provider keeps it), so Disconnect can't
         * promise it unless the server said up front ([canUnlink]) that the
         * account keeps another way to sign in.
         */
        fun disconnectConfirmBody(identity: AccountIdentity, canUnlink: Boolean? = null): String {
            val lost = "You won't be able to sign in to this account with ${providerLabel(identity)} until you connect it again."
            return if (canUnlink == true) {
                lost
            } else {
                "$lost Disconnecting works only while the account has another way to sign in; otherwise an admin must set one up first."
            }
        }

        /** `can_unlink` is false: the identity is the account's only way to sign in. */
        const val ONLY_SIGN_IN_METHOD =
            "This is your only way to sign in, so it can't be disconnected. Ask an admin to set a password for your account first."

        /**
         * The section on a server that serves the account's identities
         * ([identitiesServed]); nothing otherwise. A provider the account
         * already has an identity at isn't offered again. The providers are
         * the ones the sign-in screen would offer ([SignInOptions.of]): an
         * OAuth provider is connectable through the browser when the OAuth
         * handshake also serves app linking, a directory when the external
         * sign-in document serves [credentialsLinking] (directory linking
         * needs no handshake). A network provider is connectable when the
         * document serves [networkSignIn] and the server listed one, which
         * it does only to a device on that provider's network.
         */
        fun sectionOf(
            identitiesServed: Boolean,
            identities: List<AccountIdentity>,
            providers: List<SignInProvider>,
            handshake: OAuthHandshakeCapabilities,
            credentialsLinking: Boolean,
            canUnlink: Boolean? = null,
            networkSignIn: Boolean = false,
        ): Section {
            if (!identitiesServed) return Section(emptyList(), emptyList(), null)
            val linked = identities.map { it.installationId }.toSet()
            val unlinked = providers.filter { it.installationId != null && it.installationId !in linked }
            val options = SignInOptions.of(SignInProviders(unlinked, passwordLogin = false), handshake)
            val connectable = if (handshake.linking) options.oauthProviders else emptyList()
            val directory = options.directoryProvider.takeIf { credentialsLinking }
            val network = options.networkProvider.takeIf { networkSignIn }
            return Section(identities, connectable, directory, canUnlink.takeIf { identities.isNotEmpty() }, network)
        }

        fun providerLabel(identity: AccountIdentity): String =
            identity.providerName.ifBlank { "this sign-in" }

        /** Whether a refused connect leaves the password dialog open to correct what was typed. */
        internal fun keepsPromptOpen(error: ApiResult.Error): Boolean = error.code == 422 || error.code == 429

        private fun noLocalPassword(providerName: String) =
            "This account has no password to confirm with. Ask an admin to connect it to $providerName."

        private fun noLongerAvailable(providerName: String) = ProviderRefusalText.providerGone(providerName)

        /** Text for a refused link ticket (`createAccountIdentityLinkTicket`). */
        fun linkTicketMessage(error: ApiResult.Error, providerName: String): String = when {
            error.error == DefaultExternalSignInApi.WRONG_PASSWORD -> "That password is incorrect."
            error.code == 422 -> CHECK_ENTRIES
            error.error == "local_password_required" || error.code == 409 -> noLocalPassword(providerName)
            error.code == 404 -> noLongerAvailable(providerName)
            else -> NativeSignInMessages.forProblem(error, providerName) ?: "Couldn't connect $providerName. Try again."
        }

        /**
         * Text for a refused directory link (`linkAccountIdentityWithCredentials`),
         * by problem code: the contract names one for every refusal.
         */
        fun directoryLinkMessage(error: ApiResult.Error, providerName: String): String = when (error.error) {
            DefaultExternalSignInApi.WRONG_PASSWORD -> "That password is incorrect."
            DefaultExternalSignInApi.DIRECTORY_REFUSED -> "$providerName didn't accept that username and password."
            "local_password_required" -> noLocalPassword(providerName)
            "account_disabled" -> "Your $providerName account is disabled or locked."
            "password_expired" -> "Your $providerName password has expired. Change it, then try again."
            "not_found" -> noLongerAvailable(providerName)
            else -> when {
                error.code == 422 -> CHECK_ENTRIES
                error.code == 404 -> noLongerAvailable(providerName)
                else -> NativeSignInMessages.forProblem(error, providerName) ?: "Couldn't connect $providerName. Try again."
            }
        }

        /**
         * Text for a refused network link (`linkAccountIdentityWithNetwork`),
         * by problem code: the contract names one for every refusal.
         */
        fun networkLinkMessage(error: ApiResult.Error, providerName: String): String = when (error.error) {
            DefaultExternalSignInApi.WRONG_PASSWORD -> "That password is incorrect."
            "local_password_required" -> noLocalPassword(providerName)
            else -> when (NetworkSignInFailure.of(error.code, error.error)) {
                NetworkSignInFailure.NetworkIdentityRequired -> ProviderRefusalText.addressRequired(providerName, "connect $providerName")
                NetworkSignInFailure.NotPermitted -> ProviderRefusalText.notPermitted(providerName)
                NetworkSignInFailure.NotFound -> ProviderRefusalText.providerGone(providerName)
                else -> if (error.code == 422) {
                    CHECK_ENTRIES
                } else {
                    NativeSignInMessages.forProblem(error, providerName) ?: "Couldn't connect $providerName. Try again."
                }
            }
        }

        /** Text for a refused disconnect (`deleteAccountIdentity`). */
        fun disconnectMessage(error: ApiResult.Error, identity: AccountIdentity): String = when {
            error.error == "last_sign_in_method" || error.code == 409 ->
                "${providerLabel(identity).replaceFirstChar { it.uppercase() }} is this account's only way to sign in, " +
                    "so it can't be disconnected. Ask an admin to set a password for your account first."
            error.code == 404 -> "That sign-in is already disconnected."
            error.code == 503 -> "The server can't be reached right now. Try again later."
            else -> NativeSignInMessages.forProblem(error) ?: "Couldn't disconnect. Try again."
        }
    }
}
