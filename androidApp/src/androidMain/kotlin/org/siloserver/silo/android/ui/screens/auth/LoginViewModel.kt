package org.siloserver.silo.android.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.android.auth.NativeSignInCoordinator
import org.siloserver.silo.android.auth.NativeSignInMessages
import org.siloserver.silo.android.auth.NativeSignInProtocol
import org.siloserver.silo.android.auth.NativeSignInPurpose
import org.siloserver.silo.android.auth.NativeSignInResult
import org.siloserver.silo.android.auth.NativeSignInStart
import org.siloserver.silo.model.auth.NetworkSignInFailure
import org.siloserver.silo.model.auth.PasswordLoginFailure
import org.siloserver.silo.model.auth.SignInOptions
import org.siloserver.silo.model.auth.SignInProvider
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.repository.AuthRepository
import org.siloserver.silo.repository.ExternalSignInRepository
import org.siloserver.silo.repository.ServerIdentityRepository
import java.net.URI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val username: String = "",
    val password: String = "",
    val serverHostLabel: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val signupEnabled: Boolean = false,
    val loginSuccess: Boolean = false,
    /** Null while the server's sign-in options load. */
    val options: SignInOptions? = null,
    /**
     * The options couldn't be read (network): the password form shows as a
     * fallback, with a retry for the server's other ways to sign in.
     */
    val optionsUnavailable: Boolean = false,
    /** The provider whose browser sign-in is starting or finishing. */
    val providerBusy: String? = null,
    /**
     * "Continue as …" is waiting for the server: the password form and the
     * provider buttons wait too. Its own flag, so reloading the options
     * can't free them while the request is still out.
     */
    val networkSignInBusy: Boolean = false,
    /** A start URL for the screen to open in a Custom Tab, once. */
    val browserLaunch: String? = null,
    /** "Use a different account" with several providers: which one to sign in with. */
    val choosingAccountProvider: Boolean = false,
) {
    val showPasswordForm: Boolean get() = options?.showPasswordForm == true
    val providers: List<SignInProvider> get() = options?.oauthProviders.orEmpty()

    /** "Continue as <owner>": the network provider the server listed for this device, above everything else. */
    val networkProvider: SignInProvider? get() = options?.networkProvider

    /** "Use a different account" under the provider buttons, when the server takes `prompt=select_account`. */
    val offersAccountChoice: Boolean get() = options?.selectAccount == true && providers.isNotEmpty()

    /** A sign-in on this screen is under way, so no other may start. */
    val signInBusy: Boolean get() = isLoading || providerBusy != null || networkSignInBusy
}

class LoginViewModel(
    private val authRepository: AuthRepository,
    private val externalSignIn: ExternalSignInRepository,
    private val serverRegistry: ServerRegistry,
    private val serverIdentities: ServerIdentityRepository,
    private val nativeSignIn: NativeSignInCoordinator,
    private val tokenManager: TokenManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    /** The saved server this screen signs in to; results of flows started for another saved server are ignored. */
    private val screenServerId: String? = serverRegistry.activeServerId.value

    /**
     * This screen took the auto-start "Not you? Switch account" asked for and
     * the browser hasn't opened yet; handed back if the screen goes away first.
     */
    private var autoStartInFlight = false

    init {
        loadOptions()
        viewModelScope.launch {
            nativeSignIn.result.filterNotNull().collect { onNativeResult(it) }
        }
    }

    /** Reads the server's sign-in options; also the retry after they couldn't be read. */
    fun loadOptions() {
        _uiState.update { it.copy(options = null, optionsUnavailable = false) }
        viewModelScope.launch {
            val serverUrl = authRepository.getServerUrl()
            _uiState.update { it.copy(serverHostLabel = loginServerHostLabel(serverUrl)) }
            val options = if (serverUrl.isBlank()) SignInOptions.PasswordOnly else externalSignIn.signInOptions(serverUrl)
            _uiState.update {
                it.copy(options = options ?: SignInOptions.PasswordOnly, optionsUnavailable = options == null)
            }
            if (options != null) startRequestedSignIn(options)
        }
    }

    /**
     * "Not you? Switch account" sent the person here to pick another account:
     * start the provider sign-in by itself when the server lists exactly one
     * provider and passes `select_account` on (silo-apple
     * `LoginViewModel.startRequestedSignIn`).
     */
    private fun startRequestedSignIn(options: SignInOptions) {
        val serverId = screenServerId ?: return
        if (!nativeSignIn.consumeAutoStart(serverId)) return
        val provider = options.oauthProviders.singleOrNull()
        if (provider?.nativeStartPath == null || !options.selectAccount) return
        autoStartInFlight = true
        onProviderClick(provider, chooseAccount = true)
    }

    /** Called by the navigation layer to pass data from the previous screen. */
    fun setSignupEnabled(enabled: Boolean) {
        _uiState.update { it.copy(signupEnabled = enabled) }
    }

    fun onUsernameChanged(username: String) {
        _uiState.update { it.copy(username = username, error = null) }
    }

    fun onPasswordChanged(password: String) {
        _uiState.update { it.copy(password = password, error = null) }
    }

    fun onLoginClick() {
        if (_uiState.value.signInBusy) return
        val current = _uiState.value
        if (current.username.isBlank()) {
            _uiState.update { it.copy(error = "Username is required") }
            return
        }
        if (current.password.isBlank()) {
            _uiState.update { it.copy(error = "Password is required") }
            return
        }

        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {

            when (val result = authRepository.login(current.username, current.password)) {
                is ApiResult.Success -> {
                    onSignedIn()
                    _uiState.update { it.copy(isLoading = false, loginSuccess = true) }
                }

                is ApiResult.Error -> {
                    val options = _uiState.value.options
                    val message = passwordLoginMessage(
                        result,
                        providerName = options?.oauthProviders?.firstOrNull()?.displayName,
                        directoryName = options?.directoryProvider?.displayName,
                    )
                    _uiState.update { it.copy(isLoading = false, error = message) }
                }

                is ApiResult.NetworkError -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Network error. Please check your connection.",
                        )
                    }
                }
            }
        }
    }

    /** A sign-in on this screen (password or network identity) saved its session. */
    private suspend fun onSignedIn() {
        screenServerId?.let(nativeSignIn::clearAccountChoice)
        // A provider flow left open in the browser no longer speaks for this session.
        nativeSignIn.discardPending()
    }

    /**
     * "Continue as <owner>": sign in as whoever the server's network provider
     * (such as Tailscale) says owns this device. No browser and no password:
     * one POST to the listed path on the saved server base, and the session
     * lands exactly like a password sign-in's.
     */
    fun onNetworkSignIn() {
        val state = _uiState.value
        val provider = state.networkProvider ?: return
        val path = provider.networkSignInPath ?: return
        if (state.signInBusy) return
        _uiState.update { it.copy(networkSignInBusy = true, error = null) }
        viewModelScope.launch {
            val result = authRepository.signInWith { serverUrl -> externalSignIn.signInWithNetworkIdentity(serverUrl, path) }
            when (result) {
                is ApiResult.Success -> {
                    onSignedIn()
                    _uiState.update { it.copy(networkSignInBusy = false, loginSuccess = true) }
                }
                is ApiResult.Error -> _uiState.update {
                    it.copy(networkSignInBusy = false, error = networkSignInMessage(result, provider.displayName))
                }
                is ApiResult.NetworkError -> _uiState.update {
                    it.copy(networkSignInBusy = false, error = "Network error. Please check your connection.")
                }
            }
        }
    }

    /**
     * "Sign in with <provider>": record a native flow for the active server
     * and hand its start URL to the screen. The redirect must name this
     * server's verified identity, so it is read (and re-verified) first.
     * [chooseAccount] ("Use a different account") and the first provider
     * sign-in after an explicit sign-out ask the provider to offer another
     * account, when the server passes that on.
     */
    fun onProviderClick(provider: SignInProvider, chooseAccount: Boolean = false) {
        val startPath = provider.nativeStartPath ?: return
        if (_uiState.value.signInBusy) return
        _uiState.update { it.copy(providerBusy = provider.id, error = null, choosingAccountProvider = false) }
        viewModelScope.launch {
            val entry = serverRegistry.activeEntry.value
            val serverId = entry?.let { serverIdentities.identityOf(it, refresh = true) }
            if (entry == null || serverId == null) {
                autoStartInFlight = false
                _uiState.update {
                    it.copy(
                        providerBusy = null,
                        error = "Couldn't confirm this server's identity. Check your connection and try again.",
                    )
                }
                return@launch
            }
            val askForAccount = _uiState.value.options?.selectAccount == true &&
                (chooseAccount || nativeSignIn.accountChoiceRequested(entry.id))
            val start = nativeSignIn.begin(
                purpose = NativeSignInPurpose.SignIn,
                serverEntryId = entry.id,
                serverUrl = entry.url,
                verifiedServerId = serverId,
                providerName = provider.displayName,
                nativeStartPath = startPath,
                loginSessionId = tokenManager.loginSessionId(entry.id),
                prompt = NativeSignInProtocol.PROMPT_SELECT_ACCOUNT.takeIf { askForAccount },
            )
            when (start) {
                is NativeSignInStart.Open -> _uiState.update { it.copy(browserLaunch = start.url) }
                is NativeSignInStart.Refused -> {
                    autoStartInFlight = false
                    _uiState.update { it.copy(providerBusy = null, error = start.message) }
                }
            }
        }
    }

    /**
     * "Use a different account": the provider is asked to let the person pick
     * another provider account instead of the one the browser is signed in
     * with. With several providers the screen asks which one first.
     */
    fun onUseDifferentAccount() {
        val state = _uiState.value
        if (!state.offersAccountChoice || state.signInBusy) return
        val only = state.providers.singleOrNull()
        if (only != null) onProviderClick(only, chooseAccount = true) else _uiState.update { it.copy(choosingAccountProvider = true) }
    }

    fun onAccountProviderChosen(provider: SignInProvider) = onProviderClick(provider, chooseAccount = true)

    fun onAccountChoiceDismissed() {
        _uiState.update { it.copy(choosingAccountProvider = false) }
    }

    /**
     * The screen tried to open [browserLaunch]. Signing in continues in the
     * browser, so the button is free again; the redirect brings the result.
     */
    fun onBrowserLaunchHandled(opened: Boolean) {
        autoStartInFlight = false
        _uiState.update { state ->
            val providerName = state.providers.firstOrNull { it.id == state.providerBusy }?.displayName
            state.copy(
                browserLaunch = null,
                providerBusy = null,
                error = if (opened) state.error else NativeSignInMessages.forReason(NativeSignInMessages.NO_BROWSER, providerName),
            )
        }
    }

    private suspend fun onNativeResult(result: NativeSignInResult) {
        if (result.purpose != NativeSignInPurpose.SignIn) return
        if (screenServerId != null && result.serverEntryId != screenServerId) return
        when (result) {
            is NativeSignInResult.Finishing ->
                _uiState.update { it.copy(isLoading = true, error = null) }
            is NativeSignInResult.SignedIn -> {
                nativeSignIn.consume(result)
                // Only while that session is still the one installed, on this
                // screen's server: a result left over from before a sign-out
                // must not route anywhere.
                val signedIn = tokenManager.getAccessToken()?.isNotBlank() == true &&
                    (screenServerId == null || serverRegistry.activeServerId.value == screenServerId)
                _uiState.update { it.copy(isLoading = false, loginSuccess = signedIn) }
            }
            is NativeSignInResult.Failed -> {
                nativeSignIn.consume(result)
                _uiState.update { it.copy(isLoading = false, error = result.message) }
            }
            is NativeSignInResult.Linked -> Unit
        }
    }

    /** Resets the success flag after the UI has consumed it. */
    fun onLoginSuccessConsumed() {
        _uiState.update { it.copy(loginSuccess = false) }
    }

    override fun onCleared() {
        if (autoStartInFlight) screenServerId?.let(nativeSignIn::restoreAutoStart)
    }
}

/**
 * Text for a refused password sign-in. [providerName] names the server's
 * browser sign-in provider, when it has one, for the local-password-off case;
 * [directoryName] the directory (LDAP) provider whose first sign-in can meet
 * an email or identity already in use.
 */
internal fun passwordLoginMessage(
    error: ApiResult.Error,
    providerName: String? = null,
    directoryName: String? = null,
): String =
    when (PasswordLoginFailure.of(error.code, error.error)) {
        PasswordLoginFailure.InvalidCredentials -> "Invalid username or password"
        PasswordLoginFailure.LocalLoginDisabled -> if (providerName != null) {
            "Password sign-in is turned off on this server. Sign in with $providerName instead."
        } else {
            "Password sign-in is turned off on this server."
        }
        PasswordLoginFailure.NotPermitted -> "Your account isn't allowed to use this server."
        PasswordLoginFailure.PasswordExpired -> "Your password has expired. Change it with your directory, then sign in again."
        PasswordLoginFailure.AccountDisabled -> "Account is disabled"
        PasswordLoginFailure.ProviderUnavailable -> "The sign-in service can't be reached right now. Try again later."
        PasswordLoginFailure.EmailInUse -> NativeSignInMessages.forReason("email_in_use", directoryName)
        PasswordLoginFailure.IdentityLinkedElsewhere -> NativeSignInMessages.forReason("identity_linked_elsewhere", directoryName)
        PasswordLoginFailure.RateLimited -> NativeSignInMessages.forReason(NativeSignInMessages.RATE_LIMITED)
        PasswordLoginFailure.AccountRequired -> NativeSignInMessages.forReason(NativeSignInMessages.ACCOUNT_REQUIRED)
        PasswordLoginFailure.Other -> error.message.ifBlank { "Login failed" }
    }

/**
 * Text for a refused network identity sign-in ("Continue as …") through
 * [providerName], such as Tailscale. Refusals other operations share read as
 * they do there.
 */
internal fun networkSignInMessage(error: ApiResult.Error, providerName: String): String =
    when (NetworkSignInFailure.of(error.code, error.error)) {
        NetworkSignInFailure.NetworkIdentityRequired -> ProviderRefusalText.addressRequired(providerName, "sign in this way")
        NetworkSignInFailure.NotPermitted -> ProviderRefusalText.notPermitted(providerName)
        NetworkSignInFailure.EmailInUse ->
            "An account with your email already exists. Sign in with your password, then connect $providerName in Settings → Sign-in."
        NetworkSignInFailure.AccountRequired -> NativeSignInMessages.forReason(NativeSignInMessages.ACCOUNT_REQUIRED)
        NetworkSignInFailure.AccountDisabled -> NativeSignInMessages.forReason("account_disabled")
        NetworkSignInFailure.IdentityLinkedElsewhere -> NativeSignInMessages.forReason("identity_linked_elsewhere", providerName)
        NetworkSignInFailure.NotFound -> ProviderRefusalText.providerGone(providerName)
        NetworkSignInFailure.ProviderUnavailable -> NativeSignInMessages.forReason("provider_unavailable", providerName)
        NetworkSignInFailure.RateLimited -> NativeSignInMessages.forReason(NativeSignInMessages.RATE_LIMITED)
        NetworkSignInFailure.Other -> when {
            // AuthRepository's code for a sign-in whose account or server changed underneath it.
            error.error == "identity_changed" -> NativeSignInMessages.forReason(NativeSignInMessages.ACCOUNT_CHANGED)
            error.code == 0 && error.error == NativeSignInMessages.UPDATE_REQUIRED ->
                NativeSignInMessages.forReason(NativeSignInMessages.UPDATE_REQUIRED)
            else -> NativeSignInMessages.forReason("login_failed", providerName)
        }
    }

/**
 * The phone's copy for provider refusals that sign-in ("Continue as …") and
 * "Connect <provider>" in Settings share.
 */
internal object ProviderRefusalText {
    /** `network_identity_required`: the app reached the server some other way; [toDo] is what needs that address. */
    fun addressRequired(providerName: String, toDo: String) = "Open this server at its $providerName address to $toDo."

    /** `not_permitted`: a tagged device, or one the provider's policy leaves out. */
    fun notPermitted(providerName: String) = "$providerName doesn't allow this device on this server."

    /** The provider is no longer enabled on the server (404). */
    fun providerGone(providerName: String) = "$providerName is no longer available on this server."
}

internal fun loginServerHostLabel(serverUrl: String): String? {
    val trimmed = serverUrl.trim()
    if (trimmed.isBlank()) return null
    val parseTarget = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    return runCatching { URI(parseTarget).host?.takeIf { it.isNotBlank() } }.getOrNull()
}
