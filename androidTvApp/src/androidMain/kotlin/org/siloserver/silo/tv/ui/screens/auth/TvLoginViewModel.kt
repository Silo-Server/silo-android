package org.siloserver.silo.tv.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.model.auth.DeviceLoginPollResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.repository.AuthRepository
import org.siloserver.silo.repository.DeviceLoginRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TvLoginUiState(
    val username: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val loginSuccess: Boolean = false,
)

/**
 * Two parallel sign-in flows share this ViewModel:
 *
 *  1. **Credential** — [AuthRepository.login] returns a [org.siloserver.silo.model.auth.User]
 *     and silently stores the tokens inside [TokenManager].
 *  2. **Device-login (QR)** — [DeviceLoginRepository] runs an OAuth-style poll
 *     loop; on [DeviceLoginRepository.DeviceLoginState.Approved] we lift the
 *     returned access/refresh tokens into [TokenManager] so the rest of the
 *     auth machinery (start-destination, profile gating) sees the same world.
 *
 * Both flows run in parallel; whichever completes first explicitly
 * cancels the other so a late winner can't overwrite tokens just
 * saved by the loser:
 *  - Device-login `Approved` → [handleDeviceLoginApproved] flips
 *    `loginSuccess`. The `deviceLoginJob` coroutine terminates
 *    naturally once [DeviceLoginRepository.begin] returns its terminal
 *    state, so no explicit cancel is needed on this side.
 *  - Credential success → [onLoginClick] cancels `deviceLoginJob`
 *    before flipping `loginSuccess`, so a late-arriving device-login
 *    `Approved` can't clobber the freshly-saved credential tokens.
 *
 * The screen-side `LaunchedEffect(loginSuccess)` then routes forward.
 */
class TvLoginViewModel(
    private val authRepository: AuthRepository,
    private val tokenManager: TokenManager,
    private val deviceLogin: DeviceLoginRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TvLoginUiState())
    val uiState: StateFlow<TvLoginUiState> = _uiState.asStateFlow()

    /** Surfaced to the screen so the QR pane can render the live session. */
    val deviceLoginState: StateFlow<DeviceLoginRepository.DeviceLoginState> = deviceLogin.state

    private var deviceLoginJob: Job? = null
    private var credentialLoginJob: Job? = null
    private var authCompleted = false

    init {
        startDeviceLogin()
    }

    fun onUsernameChanged(v: String) = _uiState.update { it.copy(username = v, error = null) }
    fun onPasswordChanged(v: String) = _uiState.update { it.copy(password = v, error = null) }

    fun onLoginClick() {
        if (_uiState.value.isLoading) return
        val s = _uiState.value
        if (s.username.isBlank()) {
            _uiState.update { it.copy(error = "Username is required") }
            return
        }
        if (s.password.isBlank()) {
            _uiState.update { it.copy(error = "Password is required") }
            return
        }

        _uiState.update { it.copy(isLoading = true, error = null) }
        credentialLoginJob?.cancel()
        credentialLoginJob = viewModelScope.launch {
            when (val result = authRepository.loginForTokens(s.username, s.password)) {
                is ApiResult.Success -> {
                    if (!tryCompleteAuth()) {
                        _uiState.update { it.copy(isLoading = false) }
                        return@launch
                    }
                    deviceLoginJob?.cancel()
                    try {
                        tokenManager.replaceAccountSession(
                            accessToken = result.data.accessToken,
                            refreshToken = result.data.refreshToken,
                            expiresIn = result.data.expiresIn,
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        handleSessionPersistenceFailure(
                            accessToken = result.data.accessToken,
                            refreshToken = result.data.refreshToken,
                        )
                        return@launch
                    }
                    _uiState.update { it.copy(isLoading = false, loginSuccess = true) }
                }
                is ApiResult.Error -> {
                    if (authCompleted) {
                        _uiState.update { it.copy(isLoading = false) }
                        return@launch
                    }
                    val msg = when (result.code) {
                        401 -> "Invalid username or password"
                        403 -> "Account is disabled"
                        else -> result.message.ifBlank { "Login failed" }
                    }
                    _uiState.update { it.copy(isLoading = false, error = msg) }
                }
                is ApiResult.NetworkError -> {
                    if (authCompleted) {
                        _uiState.update { it.copy(isLoading = false) }
                        return@launch
                    }
                    _uiState.update {
                        it.copy(isLoading = false, error = "Network error. Check your connection.")
                    }
                }
            }
        }
    }

    /**
     * Starts (or restarts) the device-login state machine. Cancels any prior
     * job so a retry doesn't race two pollers against the same UI. On the
     * terminal [DeviceLoginRepository.DeviceLoginState.Approved] state we
     * persist tokens and flip `loginSuccess` — the screen then routes forward
     * from its `LaunchedEffect`.
     */
    private fun startDeviceLogin() {
        deviceLoginJob?.cancel()
        deviceLoginJob = viewModelScope.launch {
            deviceLogin.begin(
                deviceName = android.os.Build.MODEL,
                devicePlatform = "androidtv",
            )
            val terminal = deviceLogin.state.value
            if (terminal is DeviceLoginRepository.DeviceLoginState.Approved) {
                handleDeviceLoginApproved(terminal.response)
            }
        }
    }

    fun restartDeviceLogin() {
        deviceLogin.reset()
        startDeviceLogin()
    }

    /**
     * Mirrors [AuthRepository.login]'s post-API token-save: the repository
     * normally calls [TokenManager.saveTokens] for the caller, but the
     * device-login flow returns its tokens directly to the repository's
     * StateFlow instead — so we lift them into [TokenManager] here so
     * everything downstream (MainTvActivity.resolveStartDestination,
     * authenticated API calls) sees the same world as a credential login.
     */
    private suspend fun handleDeviceLoginApproved(response: DeviceLoginPollResponse) {
        val accessToken = response.accessToken
        val refreshToken = response.refreshToken
        // Repository already guards against null tokens (Failed.MissingTokens),
        // but be defensive — we should never silently flip loginSuccess without
        // tokens actually landing in storage.
        if (accessToken.isNullOrBlank() || refreshToken.isNullOrBlank()) {
            _uiState.update { it.copy(isLoading = false) }
            return
        }
        if (!tryCompleteAuth()) {
            _uiState.update { it.copy(isLoading = false) }
            return
        }
        credentialLoginJob?.cancel()
        try {
            tokenManager.replaceAccountSession(
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresIn = response.expiresIn ?: 0L,
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            handleSessionPersistenceFailure(accessToken, refreshToken)
            return
        }
        _uiState.update { it.copy(isLoading = false, loginSuccess = true) }
    }

    private suspend fun handleSessionPersistenceFailure(
        accessToken: String,
        refreshToken: String,
    ) {
        val committed = runCatching {
            tokenManager.getAccessToken() == accessToken &&
                tokenManager.getRefreshToken() == refreshToken
        }.getOrDefault(false)
        authCompleted = committed
        _uiState.update {
            it.copy(
                isLoading = false,
                loginSuccess = committed,
                error = if (committed) {
                    "Signed in, but local diagnostics cleanup did not finish."
                } else {
                    "Unable to save this session. Try again."
                },
            )
        }
    }

    private fun tryCompleteAuth(): Boolean {
        if (authCompleted) return false
        authCompleted = true
        return true
    }

    /** Called by the screen from `LaunchedEffect(loginSuccess)` after routing. */
    fun onLoginSuccessConsumed() {
        _uiState.update { it.copy(loginSuccess = false) }
    }

    override fun onCleared() {
        deviceLoginJob?.cancel()
        credentialLoginJob?.cancel()
        super.onCleared()
    }
}
