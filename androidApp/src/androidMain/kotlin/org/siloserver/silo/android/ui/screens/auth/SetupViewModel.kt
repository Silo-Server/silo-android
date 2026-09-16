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

data class SetupUiState(
    val username: String = "",
    val email: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val setupSuccess: Boolean = false,
)

/**
 * ViewModel for the first-time server setup screen (admin account creation).
 */
class SetupViewModel(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    fun onUsernameChanged(value: String) {
        _uiState.update { it.copy(username = value, error = null) }
    }

    fun onEmailChanged(value: String) {
        _uiState.update { it.copy(email = value, error = null) }
    }

    fun onPasswordChanged(value: String) {
        _uiState.update { it.copy(password = value, error = null) }
    }

    fun onCreateAccountClick() {
        if (_uiState.value.isLoading) return
        val current = _uiState.value

        // Client-side validation.
        val validationError = validateFields(current)
        if (validationError != null) {
            _uiState.update { it.copy(error = validationError) }
            return
        }

        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {

            when (val result = authRepository.setup(current.username, current.email, current.password)) {
                is ApiResult.Success -> {
                    _uiState.update { it.copy(isLoading = false, setupSuccess = true) }
                }

                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = result.message.ifBlank { "Setup failed" },
                        )
                    }
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

    fun onSetupSuccessConsumed() {
        _uiState.update { it.copy(setupSuccess = false) }
    }

    private fun validateFields(state: SetupUiState): String? {
        if (state.username.isBlank()) return "Username is required"
        if (state.email.isBlank()) return "Email is required"
        if (!state.email.contains("@")) return "Please enter a valid email address"
        if (state.password.isBlank()) return "Password is required"
        if (state.password.length < 8) return "Password must be at least 8 characters"
        return null
    }
}
