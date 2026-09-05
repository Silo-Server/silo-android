package org.siloserver.silo.android.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.AuthRepository
import java.net.URI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
)

class LoginViewModel(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val hostLabel = loginServerHostLabel(authRepository.getServerUrl())
            _uiState.update { it.copy(serverHostLabel = hostLabel) }
        }
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
                    _uiState.update { it.copy(isLoading = false, loginSuccess = true) }
                }

                is ApiResult.Error -> {
                    val message = when (result.code) {
                        401 -> "Invalid username or password"
                        403 -> "Account is disabled"
                        else -> result.message.ifBlank { "Login failed" }
                    }
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

    /** Resets the success flag after the UI has consumed it. */
    fun onLoginSuccessConsumed() {
        _uiState.update { it.copy(loginSuccess = false) }
    }
}

internal fun loginServerHostLabel(serverUrl: String): String? {
    val trimmed = serverUrl.trim()
    if (trimmed.isBlank()) return null
    val parseTarget = if (trimmed.contains("://")) trimmed else "https://$trimmed"
    return runCatching { URI(parseTarget).host?.takeIf { it.isNotBlank() } }.getOrNull()
}
