package org.siloserver.silo.android.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.android.auth.NativeSignInCoordinator
import org.siloserver.silo.android.auth.NativeSignInMessages
import org.siloserver.silo.android.auth.NativeSignInProtocol
import org.siloserver.silo.android.auth.NativeSignInPurpose
import org.siloserver.silo.android.auth.NativeSignInResult
import org.siloserver.silo.android.auth.NativeSignInStart
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
    /** A start URL for the screen to open in a Custom Tab, once. */
    val browserLaunch: String? = null,
    /** "Use a different account" with several providers: which one to sign in with. */
    val choosingAccountProvider: Boolean = false,
) {
    val showPasswordForm: Boolean get() = options?.showPasswordForm == true
    val providers: List<SignInProvider> get() = options?.oauthProviders.orEmpty()

    /** "Use a different account" under the provider buttons, when the server takes `prompt=select_account`. */
    val offersAccountChoice: Boolean get() = options?.selectAccount == true && providers.isNotEmpty()
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
        if (_uiState.value.isLoading) return
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
                    screenServerId?.let(nativeSignIn::clearAccountChoice)
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
        if (_uiState.value.isLoading || _uiState.value.providerBusy != null) return
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
        if (!state.offersAccountChoice || state.isLoading || state.providerBusy != null) return
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

internal fun loginServerHostLabel(serverUrl: String): String? {
    val trimmed = serverUrl.trim()
    if (trimmed.isBlank()) return null
    val parseTarget = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    return runCatching { URI(parseTarget).host?.takeIf { it.isNotBlank() } }.getOrNull()
}
