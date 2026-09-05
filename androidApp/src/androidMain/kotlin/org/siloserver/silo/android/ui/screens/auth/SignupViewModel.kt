package org.siloserver.silo.android.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SignupUiState(
    val username: String = "",
    val email: String = "",
    val password: String = "",
    val inviteCode: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val signupSuccess: Boolean = false,
)

/**
 * ViewModel for the signup screen (new user registration with invite code).
 */
class SignupViewModel(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SignupUiState())
    val uiState: StateFlow<SignupUiState> = _uiState.asStateFlow()

    fun onUsernameChanged(value: String) {
        _uiState.update { it.copy(username = value, error = null) }
    }

    fun onEmailChanged(value: String) {
        _uiState.update { it.copy(email = value, error = null) }
    }

    fun onPasswordChanged(value: String) {
        _uiState.update { it.copy(password = value, error = null) }
    }

    fun onInviteCodeChanged(value: String) {
        _uiState.update { it.copy(inviteCode = value, error = null) }
    }

    fun onSignupClick() {
        if (_uiState.value.isLoading) return
        val current = _uiState.value

        val validationError = validateFields(current)
        if (validationError != null) {
            _uiState.update { it.copy(error = validationError) }
            return
        }

        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {

            when (val result = authRepository.signup(
                username = current.username,
                email = current.email,
                password = current.password,
                inviteCode = current.inviteCode,
            )) {
                is ApiResult.Success -> {
                    _uiState.update { it.copy(isLoading = false, signupSuccess = true) }
                }

                is ApiResult.Error -> {
                    val message = when (result.code) {
                        409 -> "Username or email already in use"
                        403 -> "Invalid invite code"
                        else -> result.message.ifBlank { "Signup failed" }
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

    fun onSignupSuccessConsumed() {
        _uiState.update { it.copy(signupSuccess = false) }
    }

    private fun validateFields(state: SignupUiState): String? {
        if (state.username.isBlank()) return "Username is required"
        if (state.email.isBlank()) return "Email is required"
        if (!state.email.contains("@")) return "Please enter a valid email address"
        if (state.password.isBlank()) return "Password is required"
        if (state.password.length < 8) return "Password must be at least 8 characters"
        if (state.inviteCode.isBlank()) return "Invite code is required"
        return null
    }
}
