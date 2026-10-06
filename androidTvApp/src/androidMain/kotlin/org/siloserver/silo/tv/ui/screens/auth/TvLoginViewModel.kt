package org.siloserver.silo.tv.ui.screens.auth

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.common.pairing.NearbySignInCodeSource
import org.siloserver.silo.common.pairing.NearbySignInOutcome
import org.siloserver.silo.common.pairing.PairingAdvertisement
import org.siloserver.silo.pairing.PairingFailureCode
import org.siloserver.silo.model.auth.DeviceCodeFormat
import org.siloserver.silo.model.auth.DeviceLoginPollResponse
import org.siloserver.silo.model.auth.DeviceLoginStartResponse
import org.siloserver.silo.model.auth.LoginResponse
import org.siloserver.silo.model.auth.NetworkSignInFailure
import org.siloserver.silo.model.auth.SignInProvider
import org.siloserver.silo.network.AccountSessionChangedException
import org.siloserver.silo.network.AccountSessionExpectation
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.repository.AuthRepository
import org.siloserver.silo.repository.DeviceLoginClock
import org.siloserver.silo.repository.DeviceLoginRepository
import org.siloserver.silo.repository.DeviceSignInMachine
import org.siloserver.silo.repository.DeviceSignInState
import org.siloserver.silo.repository.ExternalSignInRepository
import org.siloserver.silo.model.auth.PasswordLoginFailure
import org.siloserver.silo.repository.ServerIdentityRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The form's error for a refused password sign-in. */
internal fun tvLoginError(result: ApiResult.Error): TvLoginError = when {
    // AuthRepository's code for a sign-in whose account or server changed underneath it.
    result.error == "identity_changed" -> TvLoginError.IdentityChanged
    else -> when (PasswordLoginFailure.of(result.code, result.error)) {
        PasswordLoginFailure.InvalidCredentials -> TvLoginError.InvalidCredentials
        PasswordLoginFailure.LocalLoginDisabled -> TvLoginError.LocalLoginDisabled
        PasswordLoginFailure.NotPermitted -> TvLoginError.NotPermitted
        PasswordLoginFailure.PasswordExpired -> TvLoginError.PasswordExpired
        PasswordLoginFailure.AccountDisabled -> TvLoginError.AccountDisabled
        PasswordLoginFailure.ProviderUnavailable -> TvLoginError.ProviderUnavailable
        PasswordLoginFailure.EmailInUse -> TvLoginError.EmailInUse
        PasswordLoginFailure.IdentityLinkedElsewhere -> TvLoginError.IdentityLinkedElsewhere
        PasswordLoginFailure.RateLimited -> TvLoginError.RateLimited
        PasswordLoginFailure.AccountRequired -> TvLoginError.AccountRequired
        PasswordLoginFailure.Other -> TvLoginError.Server(result.message.takeIf { it.isNotBlank() })
    }
}

/** The error for a refused network identity sign-in ("Continue as …"). */
internal fun tvNetworkSignInError(result: ApiResult.Error): TvLoginError = when {
    // AuthRepository's code for a sign-in whose account or server changed underneath it.
    result.error == "identity_changed" -> TvLoginError.IdentityChanged
    else -> when (NetworkSignInFailure.of(result.code, result.error)) {
        NetworkSignInFailure.NetworkIdentityRequired -> TvLoginError.NetworkIdentityRequired
        NetworkSignInFailure.NotPermitted -> TvLoginError.NetworkNotPermitted
        NetworkSignInFailure.EmailInUse -> TvLoginError.NetworkEmailInUse
        NetworkSignInFailure.NotFound -> TvLoginError.NetworkProviderGone
        NetworkSignInFailure.AccountRequired -> TvLoginError.AccountRequired
        NetworkSignInFailure.AccountDisabled -> TvLoginError.AccountDisabled
        NetworkSignInFailure.IdentityLinkedElsewhere -> TvLoginError.IdentityLinkedElsewhere
        NetworkSignInFailure.ProviderUnavailable -> TvLoginError.ProviderUnavailable
        NetworkSignInFailure.RateLimited -> TvLoginError.RateLimited
        NetworkSignInFailure.Other -> TvLoginError.Server(result.message.takeIf { it.isNotBlank() })
    }
}

/**
 * Why a sign-in on this screen (the password form, or "Continue as …") can't
 * proceed; the screen maps each to `strings.xml`.
 */
sealed interface TvLoginError {
    data object UsernameRequired : TvLoginError
    data object PasswordRequired : TvLoginError
    data object InvalidCredentials : TvLoginError
    data object AccountDisabled : TvLoginError
    data object Network : TvLoginError

    /** Password sign-in is off on this server (it signs in through a provider). */
    data object LocalLoginDisabled : TvLoginError

    /** The directory accepted the password but doesn't admit this account. */
    data object NotPermitted : TvLoginError

    /** The directory says the password has expired. */
    data object PasswordExpired : TvLoginError

    /** The directory (LDAP) can't be reached. */
    data object ProviderUnavailable : TvLoginError

    /** A first directory sign-in found another account with the same email. */
    data object EmailInUse : TvLoginError

    /** The directory identity is linked to another account. */
    data object IdentityLinkedElsewhere : TvLoginError
    data object RateLimited : TvLoginError

    /** No account matches and the server doesn't create one (`account_required`). */
    data object AccountRequired : TvLoginError
    data object IdentityChanged : TvLoginError
    data object SaveFailed : TvLoginError
    data object CleanupIncomplete : TvLoginError

    /** A network sign-in that didn't come through the provider's network (`network_identity_required`). */
    data object NetworkIdentityRequired : TvLoginError

    /** The network provider refuses this TV (`not_permitted`): a tagged device, or one its policy leaves out. */
    data object NetworkNotPermitted : TvLoginError

    /** A first network sign-in found another account with the same email: connect from that account instead. */
    data object NetworkEmailInUse : TvLoginError

    /** The network provider is no longer enabled on the server (404). */
    data object NetworkProviderGone : TvLoginError

    /** The server's own explanation, or null for a generic failure. */
    data class Server(val message: String?) : TvLoginError
}

data class TvLoginUiState(
    val username: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val error: TvLoginError? = null,
    val loginSuccess: Boolean = false,
    /** Display name of the server this TV signs in to, for the title and banner. */
    val serverName: String? = null,
    /** Host shown in "Can't reach …" copy. */
    val serverHost: String? = null,
    /** `<server>/activate` in typed-URL form, shown before the first code arrives. */
    val serverActivateText: String? = null,
    /** Arrived here because the session expired elsewhere: show the banner. */
    val sessionExpired: Boolean = false,
    /**
     * Whether the server takes a password from this TV: the local provider or
     * a directory (LDAP), which the server reaches without a provider field.
     * False only when discovery says no listed provider takes one (an OIDC
     * server with local passwords off); the TV then offers device sign-in alone.
     */
    val passwordAvailable: Boolean = true,
    /**
     * The server's browser sign-in provider (OIDC), when it lists one: its
     * accounts have no password, so a refused password points to the phone.
     */
    val providerName: String? = null,
    /** The directory (LDAP) provider the password form also reaches, when enabled. */
    val directoryName: String? = null,
    /**
     * The network provider (such as Tailscale) the server listed for this
     * TV, which it does only when the TV reached it through that provider's
     * network: "Continue as <owner>" signs in with no code and no password.
     */
    val networkProvider: SignInProvider? = null,
    /** "Continue as …" is waiting for the server. */
    val networkBusy: Boolean = false,
    /** Why the last "Continue as …" was refused; shown with that button. */
    val networkError: TvLoginError? = null,
) {
    /**
     * A password was refused because password sign-in is off, and the form
     * closed because of it: the screen says so outside the form, the only
     * other place the refusal could show.
     */
    val passwordTurnedOff: Boolean get() = error == TvLoginError.LocalLoginDisabled && !passwordAvailable

    /**
     * A password or "Continue as …" sign-in is waiting for the server. Neither
     * button takes another press until it answers, so the password form's
     * fields and Sign in are disabled with it.
     */
    val signingIn: Boolean get() = isLoading || networkBusy
}

/** What the device-code panel shows. */
enum class TvSignInStatus {
    GettingCode,
    Waiting,
    Opened,
    NewCode,
    Unreachable,
    TooManyRequests,
    SignedIn,
    CouldntFinish,
    Denied,
    Paused,
    Failed,
    UpdateRequired,
}

data class TvSignInCode(
    /** Grouped for display: `4821 7730`. */
    val userCode: String,
    /** `verification_uri_complete`, encoded into the QR. */
    val qrContent: String,
    /** `silo.example.com/activate`, the typed-URL form. */
    val activateText: String,
    /** One character per word, for the screen reader. */
    val spokenCode: String,
)

data class TvDeviceSignInUi(
    /** Null while there is no live code: the QR slot shows a spinner. */
    val code: TvSignInCode? = null,
    val status: TvSignInStatus = TvSignInStatus.GettingCode,
    /** Account shown in "Signed in as …". */
    val accountName: String? = null,
    /** The server has no device sign-in: go straight to the password form. */
    val passwordOnly: Boolean = false,
)

/**
 * Two parallel sign-in flows share this ViewModel:
 *
 *  1. **Credential** — [AuthRepository.loginForTokens] (or, for "Continue as
 *     <owner>" through a network provider such as Tailscale, the network
 *     identity sign-in, which answers the same token pair) then an atomic
 *     [TokenManager.replaceAccountSession].
 *  2. **Device code (QR)** — a [DeviceSignInMachine] shows a code, renews it
 *     while the screen is visible, pauses after about an hour, and names
 *     failures. On approval we lift the tokens into [TokenManager].
 *
 * Whichever completes first wins ([tryCompleteAuth]); the other is cancelled
 * so a late winner can't overwrite tokens just saved by the loser. An approval
 * that arrives while a password save is in flight is held, and used if that
 * save fails.
 *
 * Lifecycle: the screen calls [onStop] / [onStart] so polling stops in the
 * background and resumes (polling at once, renewing if expired) on return.
 * Stopping never interrupts a poll in flight or the approval handoff: the
 * server hands out an approval's tokens once. An abandoned code is withdrawn
 * on the server (when it supports cancel) on leaving, retrying, switching
 * server or signing in with a password.
 *
 * In `login` pairing mode this is also the LAN receiver's
 * [NearbySignInCodeSource]: a nearby phone approves the code shown here.
 */
class TvLoginViewModel(
    private val authRepository: AuthRepository,
    private val tokenManager: TokenManager,
    private val deviceLogin: DeviceLoginRepository,
    private val serverRegistry: ServerRegistry? = null,
    private val serverIdentities: ServerIdentityRepository? = null,
    sessionExpired: Boolean = false,
    /** Outlives the screen, for the best-effort cancel call on leaving. */
    private val backgroundScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val clock: DeviceLoginClock = DeviceLoginClock.System,
    /** Provider discovery, for whether the password form is offered. */
    private val externalSignIn: ExternalSignInRepository? = null,
    /**
     * Rollout gate for sign-in TVs, mirroring silo-apple
     * `PairingProtocol.advertisesSignInTVs`: whether this screen advertises
     * `st=login`. Both phone apps must accept `login` TVs before either TV
     * advertises one; a phone that predates them offers a sign-in TV the
     * setup chooser and pushes servers without an identity, which this TV
     * refuses. The app turns it on for debug builds only; turn it on for
     * release once the iOS and Android phone apps that handle `login` ship.
     */
    private val advertisesSignIn: Boolean = false,
) : ViewModel(), NearbySignInCodeSource {

    private val _uiState = MutableStateFlow(TvLoginUiState(sessionExpired = sessionExpired))
    val uiState: StateFlow<TvLoginUiState> = _uiState.asStateFlow()

    private val _deviceSignIn = MutableStateFlow(TvDeviceSignInUi())
    val deviceSignIn: StateFlow<TvDeviceSignInUi> = _deviceSignIn.asStateFlow()

    /**
     * What this screen advertises to nearby phones: `st=login` with the
     * server's verified identity. Null until the identity is known, or when
     * it can't be: phones match a signed-out TV only by identity.
     * Always null while the [advertisesSignIn] rollout gate is off.
     */
    private val _pairingAdvertisement = MutableStateFlow<PairingAdvertisement?>(null)
    val pairingAdvertisement: StateFlow<PairingAdvertisement?> = _pairingAdvertisement.asStateFlow()

    private var machine: DeviceSignInMachine? = null

    /**
     * The sign-in context the machine's codes were started under. A resumed
     * run polls only while it is unchanged: an approval for a code started
     * before a sign-in, sign-out or server switch must not be saved over it.
     */
    private var machineExpectation: AccountSessionExpectation? = null
    private var machineObserver: Job? = null
    private var deviceLoginJob: Job? = null
    private var credentialLoginJob: Job? = null
    private var newCodeJob: Job? = null
    private var signInOptionsJob: Job? = null

    /** Saves an approval's tokens and routes on; [onStop] never cancels it. */
    private var approvalJob: Job? = null
    private var authCompleted = false
    private var visible = true

    /** [onStop] ran since the last [onStart]: the next start re-reads the sign-in options. */
    private var lastDeviceCode: String? = null

    /** A device approval that arrived while a password (or network) save held the sign-in. */
    private var heldApproval: Pair<DeviceLoginPollResponse, AccountSessionExpectation>? = null

    /** The code on screen now, for a nearby phone; null while none is live. */
    private val liveCode = MutableStateFlow<DeviceLoginStartResponse?>(null)

    init {
        loadServerContext()
        startDeviceLogin()
    }

    fun onUsernameChanged(v: String) = _uiState.update { it.copy(username = v, error = null) }
    fun onPasswordChanged(v: String) = _uiState.update { it.copy(password = v, error = null) }

    fun onLoginClick() {
        if (_uiState.value.signingIn) return
        val s = _uiState.value
        if (s.username.isBlank()) {
            _uiState.update { it.copy(error = TvLoginError.UsernameRequired) }
            return
        }
        if (s.password.isBlank()) {
            _uiState.update { it.copy(error = TvLoginError.PasswordRequired) }
            return
        }

        _uiState.update { it.copy(isLoading = true, error = null) }
        signInWithTokens(TokenSignIn.Password) { expected -> authRepository.loginForTokens(s.username, s.password, expected) }
    }

    /**
     * "Continue as <owner>": sign in as whoever the server's network provider
     * (such as Tailscale) says owns this TV, with no code and no password.
     * The session is saved exactly like a password sign-in's, and the code on
     * screen is withdrawn once it is.
     */
    fun onNetworkSignInClick() {
        val s = _uiState.value
        val discovery = externalSignIn ?: return
        val path = s.networkProvider?.networkSignInPath ?: return
        if (s.signingIn) return
        _uiState.update { it.copy(networkBusy = true, networkError = null) }
        signInWithTokens(TokenSignIn.Network) { expected ->
            authRepository.tokensFor(expected) { serverUrl -> discovery.signInWithNetworkIdentity(serverUrl, path) }
        }
    }

    /** Which button started a [signInWithTokens]: its busy state and errors show there. */
    private enum class TokenSignIn { Password, Network }

    /**
     * [attempt]'s button is free again, with [error], when given, shown where
     * it was pressed. A null [attempt] is the device code's own handoff: its
     * approval cancels whichever sign-in was under way, so both buttons are
     * free again, and its error uses the form's slot.
     */
    private fun TvLoginUiState.settled(attempt: TokenSignIn?, error: TvLoginError? = null): TvLoginUiState = when (attempt) {
        TokenSignIn.Password -> copy(isLoading = false, error = error ?: this.error)
        TokenSignIn.Network -> copy(networkBusy = false, networkError = error ?: networkError)
        null -> copy(isLoading = false, networkBusy = false, error = error ?: this.error)
    }

    /**
     * A sign-in whose [fetch] answers `login`'s token pair (a password, or the
     * network identity): it races the device code for the sign-in, and its
     * session is committed atomically, or not at all.
     */
    private fun signInWithTokens(
        attempt: TokenSignIn,
        fetch: suspend (AccountSessionExpectation) -> ApiResult<LoginResponse>,
    ) {
        credentialLoginJob?.cancel()
        credentialLoginJob = viewModelScope.launch {
            val expected = captureExpectation()
            if (expected == null) {
                _uiState.update { it.settled(attempt, TvLoginError.IdentityChanged) }
                return@launch
            }
            when (val result = fetch(expected)) {
                is ApiResult.Success -> {
                    if (!tryCompleteAuth()) {
                        _uiState.update { it.settled(attempt) }
                        return@launch
                    }
                    // Polling carries on until the password session commits: if
                    // the save fails, the code on screen still works.
                    try {
                        tokenManager.replaceAccountSession(
                            expectedIdentity = expected,
                            accessToken = result.data.accessToken,
                            refreshToken = result.data.refreshToken,
                            expiresIn = result.data.expiresIn,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: AccountSessionChangedException) {
                        handleIdentityChanged(attempt)
                        resumeDeviceSignIn()
                        return@launch
                    } catch (_: Throwable) {
                        handleSessionPersistenceFailure(
                            accessToken = result.data.accessToken,
                            refreshToken = result.data.refreshToken,
                            attempt = attempt,
                        )
                        if (!authCompleted) {
                            // This screen's own save moved the generation without
                            // committing anything: the code on screen still belongs
                            // to this sign-in, so it carries on under the new one.
                            if (machineExpectation?.serverUrl == expected.serverUrl) machineExpectation = captureExpectation()
                            resumeDeviceSignIn()
                        } else {
                            tokenSignInWon()
                        }
                        return@launch
                    }
                    // Tokens are committed outside persistSession here, so refresh the
                    // server's v2 contract verdict the same way every other sign-in does.
                    authRepository.onSessionCommitted()
                    if (tokenManager.captureAccountSessionExpectation()?.generation != expected.generation + 1) {
                        handleIdentityChanged(attempt)
                        resumeDeviceSignIn()
                        return@launch
                    }
                    tokenSignInWon()
                    _uiState.update { it.settled(attempt).copy(loginSuccess = true) }
                }
                is ApiResult.Error -> {
                    if (authCompleted) {
                        _uiState.update { it.settled(attempt) }
                        return@launch
                    }
                    val error = when (attempt) {
                        TokenSignIn.Password -> tvLoginError(result)
                        TokenSignIn.Network -> tvNetworkSignInError(result)
                    }
                    _uiState.update { it.settled(attempt, error) }
                    // The server refused passwords: re-read what it offers, so
                    // the form goes away once password sign-in is off.
                    if (attempt == TokenSignIn.Password && error == TvLoginError.LocalLoginDisabled) refreshSignInOptions()
                }
                is ApiResult.NetworkError -> {
                    if (authCompleted) {
                        _uiState.update { it.settled(attempt) }
                        return@launch
                    }
                    _uiState.update { it.settled(attempt, TvLoginError.Network) }
                }
            }
        }
    }

    /**
     * The screen went to the background (or the screensaver started): stop
     * polling, keep the code. A poll in flight finishes first and an approval
     * being saved completes, since the server hands out its tokens once.
     */
    fun onStop() {
        visible = false
        machine?.requestStop()
    }

    /**
     * The screen is visible again: poll now, renewing the code if it expired
     * meanwhile, and re-read the server's sign-in options (an admin may have
     * turned password sign-in off while the TV sat in the background).
     */
    fun onStart() {
        if (!visible && !authCompleted) refreshSignInOptions()
        if (visible && deviceLoginJob?.isActive == true) return
        visible = true
        // A password sign-in already won (possibly while in the background):
        // its code was withdrawn, and no new one may be asked for.
        if (authCompleted) return
        val current = machine
        if (current == null) {
            // Still reading the sign-in context: that run creates the machine
            // and, now that the screen is visible again, starts it.
            if (deviceLoginJob?.isActive == true) return
            startDeviceLogin()
            return
        }
        // Approved is settled too: the approval job finishes the handoff on its own.
        if (current.isSettled) return
        current.noteActivity()
        runMachine(current)
    }

    /**
     * "Try again" / "Show a new code": withdraw the old code and start fresh.
     * After a failed save the server already consumed the old request, so a
     * new code is the only way on (#422).
     */
    fun restartDeviceLogin() {
        if (authCompleted) return
        // Also "Use your phone instead" from the password form: the options
        // may have changed while the person was typing.
        refreshSignInOptions()
        abandonDeviceCode()
        machine?.restart()
        _deviceSignIn.value = TvDeviceSignInUi()
        startDeviceLogin()
    }

    /** "Change server": withdraw the code before this screen goes away. */
    fun onChangeServer() {
        abandonDeviceCode()
    }

    private fun startDeviceLogin() {
        deviceLoginJob?.cancel()
        deviceLoginJob = viewModelScope.launch {
            val expected = captureExpectation()
            if (expected == null) {
                // No sign-in context to bind the approval to (#421): say so and
                // offer "Try again" rather than spin on "Getting a code" forever.
                _deviceSignIn.value = TvDeviceSignInUi(status = TvSignInStatus.Failed)
                return@launch
            }
            val current = machine?.takeIf { it.serverUrl == expected.serverUrl }
                ?: DeviceSignInMachine(
                    repository = deviceLogin,
                    serverUrl = expected.serverUrl,
                    deviceName = deviceName(),
                    // Same spelling as the X-Silo-Device-Platform header this app
                    // sends, so one device reports one platform string everywhere.
                    devicePlatform = "android-tv",
                    clock = clock,
                ).also { created ->
                    machine = created
                    observe(created)
                }
            machineExpectation = expected
            // Stopped before the machine existed (onStop had nothing to stop):
            // keep it idle until onStart runs it.
            if (!visible) return@launch
            runUntilSettled(current, expected)
        }
    }

    private fun runMachine(current: DeviceSignInMachine) {
        // A run still finishing its last call after onStop is let finish (its
        // answer may carry the tokens), then this run picks up from there.
        // Cancelling this run (restart, change server, a password win, leaving)
        // cancels the one it waits for too, so no older run outlives it.
        val previous = deviceLoginJob
        val next = viewModelScope.launch {
            previous?.join()
            if (current.isSettled) return@launch
            val expected = captureExpectation()
            val bound = machineExpectation
            if (expected == null || expected.serverUrl != current.serverUrl || bound?.isSameSession(expected) != true) {
                // "Try again" withdraws the code and starts one under the current context.
                _deviceSignIn.value = TvDeviceSignInUi(status = TvSignInStatus.Failed)
                return@launch
            }
            // Stopped again while waiting (onStop, onStart, onStop during one
            // call): that onStop's requestStop landed on the previous run, not
            // on this one, so check here, with no suspension before run().
            if (!visible) return@launch
            runUntilSettled(current, expected)
        }
        next.invokeOnCompletion { cause -> if (cause is CancellationException) previous?.cancel() }
        deviceLoginJob = next
    }

    private suspend fun runUntilSettled(current: DeviceSignInMachine, expected: AccountSessionExpectation) {
        current.run()
        val terminal = current.state.value
        if (terminal is DeviceSignInState.Approved) launchApproval(terminal.response, expected)
    }

    /** The approval handoff runs in its own job so leaving the foreground can't cut it short. */
    private fun launchApproval(response: DeviceLoginPollResponse, expected: AccountSessionExpectation) {
        if (approvalJob?.isActive == true) return
        approvalJob = viewModelScope.launch { handleDeviceLoginApproved(response, expected) }
    }

    private fun observe(current: DeviceSignInMachine) {
        machineObserver?.cancel()
        machineObserver = viewModelScope.launch {
            current.state.collect { state -> publish(state) }
        }
    }

    private fun publish(state: DeviceSignInState) {
        when (state) {
            is DeviceSignInState.ShowingCode -> liveCode.value = state.session
            is DeviceSignInState.Unreachable -> liveCode.value = state.session
            is DeviceSignInState.TooManyRequests -> liveCode.value = state.session
            DeviceSignInState.GettingCode -> liveCode.value = null
            // An approval keeps its code until the handoff says how it ended.
            else -> Unit
        }
        val previous = _deviceSignIn.value
        // The approval is handled (and its outcome shown) by
        // handleDeviceLoginApproved; never claim "Signed in" before the
        // session is actually saved (#422).
        if (state is DeviceSignInState.Approved) return
        if (previous.status == TvSignInStatus.SignedIn || previous.status == TvSignInStatus.CouldntFinish) return
        _deviceSignIn.value = when (state) {
            DeviceSignInState.GettingCode -> TvDeviceSignInUi(status = TvSignInStatus.GettingCode)
            is DeviceSignInState.ShowingCode -> {
                val renewedNow = state.renewed && lastDeviceCode != null && lastDeviceCode != state.session.deviceCode
                lastDeviceCode = state.session.deviceCode
                if (renewedNow) announceNewCode()
                val status = when {
                    state.opened -> TvSignInStatus.Opened
                    renewedNow || (previous.status == TvSignInStatus.NewCode && newCodeJob?.isActive == true) ->
                        TvSignInStatus.NewCode
                    else -> TvSignInStatus.Waiting
                }
                TvDeviceSignInUi(code = state.session.display(), status = status)
            }
            DeviceSignInState.Denied -> TvDeviceSignInUi(status = TvSignInStatus.Denied)
            DeviceSignInState.Paused -> TvDeviceSignInUi(status = TvSignInStatus.Paused)
            is DeviceSignInState.Unreachable ->
                TvDeviceSignInUi(code = state.session?.display(), status = TvSignInStatus.Unreachable)
            is DeviceSignInState.TooManyRequests ->
                TvDeviceSignInUi(code = state.session?.display(), status = TvSignInStatus.TooManyRequests)
            DeviceSignInState.NoDeviceSignIn -> TvDeviceSignInUi(status = TvSignInStatus.Failed, passwordOnly = true)
            DeviceSignInState.UpdateRequired -> TvDeviceSignInUi(status = TvSignInStatus.UpdateRequired)
            DeviceSignInState.Failed -> TvDeviceSignInUi(status = TvSignInStatus.Failed)
            is DeviceSignInState.Approved -> previous
        }
    }

    /** "New code" is a brief, polite announcement, then the status reads "Waiting" again. */
    private fun announceNewCode() {
        newCodeJob?.cancel()
        newCodeJob = viewModelScope.launch {
            delay(NEW_CODE_ANNOUNCEMENT_MS)
            _deviceSignIn.update { ui ->
                if (ui.status == TvSignInStatus.NewCode) ui.copy(status = TvSignInStatus.Waiting) else ui
            }
        }
    }

    /**
     * Lift the approved tokens into [TokenManager] so everything downstream
     * (start destination, authenticated calls) sees the same world as a
     * credential login. On failure the panel says so with "Try again" — never
     * "Signed in" (#422).
     */
    private suspend fun handleDeviceLoginApproved(response: DeviceLoginPollResponse, expected: AccountSessionExpectation) {
        val accessToken = response.accessToken
        val refreshToken = response.refreshToken
        if (accessToken.isNullOrBlank() || refreshToken.isNullOrBlank()) {
            _deviceSignIn.value = TvDeviceSignInUi(status = TvSignInStatus.CouldntFinish)
            return
        }
        val current = runCatching { tokenManager.captureAccountSessionExpectation() }.getOrNull()
        if (!tryCompleteAuth()) {
            // A password save holds the sign-in. The server won't hand these
            // tokens out again, so keep them in case that save fails.
            heldApproval = response to expected
            return
        }
        // An approval for a sign-in context that has since changed can't be
        // saved: release the claim before it cancels a password attempt made
        // in the new context. Nothing suspends between this check and the claim.
        if (!expected.isSameSession(current)) {
            authCompleted = false
            _deviceSignIn.value = TvDeviceSignInUi(status = TvSignInStatus.CouldntFinish)
            return
        }
        // A password or network sign-in under way never settles now: this
        // handoff frees its button when it ends (see settled).
        credentialLoginJob?.cancel()
        try {
            tokenManager.replaceAccountSession(
                expectedIdentity = expected,
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresIn = response.expiresIn ?: 0L,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: AccountSessionChangedException) {
            handleIdentityChanged()
            _deviceSignIn.value = TvDeviceSignInUi(status = TvSignInStatus.CouldntFinish)
            return
        } catch (_: Throwable) {
            handleSessionPersistenceFailure(accessToken, refreshToken)
            if (!_uiState.value.loginSuccess) {
                _deviceSignIn.value = TvDeviceSignInUi(status = TvSignInStatus.CouldntFinish)
            }
            return
        }
        // Tokens are committed outside persistSession here, so refresh the
        // server's v2 contract verdict the same way every other sign-in does.
        authRepository.onSessionCommitted()
        if (tokenManager.captureAccountSessionExpectation()?.generation != expected.generation + 1) {
            handleIdentityChanged()
            _deviceSignIn.value = TvDeviceSignInUi(status = TvSignInStatus.CouldntFinish)
            return
        }
        _deviceSignIn.value = TvDeviceSignInUi(
            status = TvSignInStatus.SignedIn,
            accountName = response.user?.username?.takeIf { it.isNotBlank() },
        )
        // Let "Signed in as …" register before the profile picker replaces it.
        delay(SIGNED_IN_DWELL_MS)
        _uiState.update { it.settled(null).copy(loginSuccess = true) }
    }

    /** [attempt] null: the device code's approval, see [settled]. */
    private fun handleIdentityChanged(attempt: TokenSignIn? = null) {
        // Only release this screen's attempt. Credentials now belong to the new identity.
        authCompleted = false
        _uiState.update {
            it.settled(attempt, TvLoginError.IdentityChanged).copy(loginSuccess = false)
        }
    }

    private suspend fun handleSessionPersistenceFailure(
        accessToken: String,
        refreshToken: String,
        attempt: TokenSignIn? = null,
    ) {
        val committed = runCatching {
            tokenManager.getAccessToken() == accessToken &&
                tokenManager.getRefreshToken() == refreshToken
        }.getOrDefault(false)
        if (committed) authRepository.onSessionCommitted()
        authCompleted = committed
        _uiState.update {
            it.settled(attempt, if (committed) TvLoginError.CleanupIncomplete else TvLoginError.SaveFailed)
                .copy(loginSuccess = committed)
        }
    }

    /**
     * The password or network session committed: the code on screen must not
     * be approvable any more.
     */
    private fun tokenSignInWon() {
        heldApproval = null
        abandonDeviceCode()
    }

    /** The password save failed without committing: carry on with the device code. */
    private fun resumeDeviceSignIn() {
        if (authCompleted) return
        val held = heldApproval
        if (held != null) {
            heldApproval = null
            launchApproval(held.first, held.second)
            return
        }
        val current = machine ?: return
        if (visible && !current.isSettled && deviceLoginJob?.isActive != true) runMachine(current)
    }

    override suspend fun codeForNearbyApproval(): DeviceLoginStartResponse? =
        withTimeoutOrNull(NEARBY_CODE_WAIT_MS) {
            combine(liveCode, _deviceSignIn) { code, ui -> code to ui.status }
                .first { (code, status) -> (code != null && status in SHOWING_CODE) || status in ENDS_NEARBY_WAIT }
                .let { (code, status) -> code.takeIf { status in SHOWING_CODE } }
        }

    override suspend fun nearbyApprovalOutcome(deviceCode: String): NearbySignInOutcome =
        combine(liveCode, _deviceSignIn) { code, ui -> nearbyOutcome(ui.status, code, deviceCode) }
            .filterNotNull()
            .first()

    private fun nearbyOutcome(
        status: TvSignInStatus,
        live: DeviceLoginStartResponse?,
        deviceCode: String,
    ): NearbySignInOutcome? = when (status) {
        TvSignInStatus.SignedIn -> NearbySignInOutcome.SignedIn
        TvSignInStatus.CouldntFinish -> NearbySignInOutcome.Failed(PairingFailureCode.AuthFailed)
        TvSignInStatus.Denied -> NearbySignInOutcome.Failed(PairingFailureCode.Denied)
        TvSignInStatus.UpdateRequired -> NearbySignInOutcome.Failed(PairingFailureCode.UpdateRequired)
        TvSignInStatus.Paused, TvSignInStatus.Failed -> NearbySignInOutcome.Failed(PairingFailureCode.Expired)
        TvSignInStatus.GettingCode,
        TvSignInStatus.Waiting,
        TvSignInStatus.Opened,
        TvSignInStatus.NewCode,
        TvSignInStatus.Unreachable,
        TvSignInStatus.TooManyRequests,
        -> if (live?.deviceCode == deviceCode) null else NearbySignInOutcome.Failed(PairingFailureCode.Expired)
    }

    private fun tryCompleteAuth(): Boolean {
        if (authCompleted) return false
        authCompleted = true
        return true
    }

    /**
     * The sign-in context, read again once if the first read races an
     * identity transition (sign-out, expiry or server switch landing as this
     * screen opens) rather than giving up silently (#421).
     */
    private suspend fun captureExpectation(): AccountSessionExpectation? {
        repeat(EXPECTATION_ATTEMPTS) { attempt ->
            val expected = runCatching { tokenManager.captureAccountSessionExpectation() }.getOrNull()
            if (expected != null) return expected
            if (attempt < EXPECTATION_ATTEMPTS - 1) delay(EXPECTATION_RETRY_MS)
        }
        return null
    }

    private fun abandonDeviceCode() {
        deviceLoginJob?.cancel()
        deviceLoginJob = null
        liveCode.value = null
        machine?.abandon(backgroundScope)
    }

    private fun loadServerContext() {
        val registry = serverRegistry ?: return
        viewModelScope.launch {
            val entry = registry.activeEntry.value ?: return@launch
            _uiState.update {
                it.copy(
                    serverName = entry.displayName,
                    serverHost = DeviceCodeFormat.host(entry.url),
                    serverActivateText = DeviceCodeFormat.activateText(entry.url.trimEnd('/') + "/activate", ""),
                )
            }
            refreshSignInOptions()
            // Advertise st=login only with a verified identity: phones match a
            // signed-out TV to their saved servers by identity, never by URL.
            if (!advertisesSignIn) return@launch
            val identity = serverIdentities?.identityOf(entry, refresh = true) ?: return@launch
            _pairingAdvertisement.value = PairingAdvertisement.login(
                serverIdentity = identity,
                serverUrl = entry.url,
                source = this@TvLoginViewModel,
            )
        }
    }

    /**
     * Reads which sign-in methods the active server offers and whether the
     * password form stays. Unknown options (a network failure) change nothing.
     */
    private fun refreshSignInOptions() {
        val discovery = externalSignIn ?: return
        val entry = serverRegistry?.activeEntry?.value ?: return
        signInOptionsJob?.cancel()
        signInOptionsJob = viewModelScope.launch {
            val options = discovery.signInOptions(entry.url) ?: return@launch
            if (serverRegistry.activeEntry.value?.id != entry.id) return@launch
            _uiState.update {
                val passwordAvailable = options.showPasswordForm
                it.copy(
                    passwordAvailable = passwordAvailable,
                    // The form's errors go with it when it closes, except the
                    // "turned off" refusal the screen then shows outside it;
                    // that one goes once the form can come back.
                    error = when {
                        passwordAvailable == it.passwordAvailable -> it.error
                        passwordAvailable -> null
                        else -> it.error.takeIf { error -> error == TvLoginError.LocalLoginDisabled }
                    },
                    providerName = options.oauthProviders.firstOrNull()?.displayName,
                    directoryName = options.directoryProvider?.displayName,
                    networkProvider = options.networkProvider,
                    // A refusal belongs to its button; it goes once the button does.
                    networkError = it.networkError.takeIf { options.networkProvider != null },
                )
            }
        }
    }

    private fun DeviceLoginStartResponse.display() = TvSignInCode(
        userCode = DeviceCodeFormat.display(userCode),
        qrContent = verificationUriComplete,
        activateText = DeviceCodeFormat.activateText(verificationUri, verificationUriComplete),
        spokenCode = DeviceCodeFormat.spoken(userCode),
    )

    /** Called by the screen from `LaunchedEffect(loginSuccess)` after routing. */
    fun onLoginSuccessConsumed() {
        _uiState.update { it.copy(loginSuccess = false) }
    }

    override fun onCleared() {
        deviceLoginJob?.cancel()
        credentialLoginJob?.cancel()
        // Leaving the screen: an abandoned code must not stay approvable.
        machine?.abandon(backgroundScope)
        super.onCleared()
    }

    private companion object {
        const val EXPECTATION_ATTEMPTS = 3
        const val EXPECTATION_RETRY_MS = 250L
        const val NEW_CODE_ANNOUNCEMENT_MS = 5_000L
        const val SIGNED_IN_DWELL_MS = 1_000L

        /** How long a nearby phone waits for a code that is still being fetched. */
        const val NEARBY_CODE_WAIT_MS = 15_000L

        val SHOWING_CODE = setOf(TvSignInStatus.Waiting, TvSignInStatus.Opened, TvSignInStatus.NewCode)
        val ENDS_NEARBY_WAIT = setOf(
            TvSignInStatus.SignedIn,
            TvSignInStatus.CouldntFinish,
            TvSignInStatus.Denied,
            TvSignInStatus.Paused,
            TvSignInStatus.Failed,
            TvSignInStatus.UpdateRequired,
        )

        fun deviceName(): String = Build.MODEL?.trim()?.takeIf { it.isNotEmpty() } ?: "Android TV"
    }
}
