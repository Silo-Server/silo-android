package org.siloserver.silo.android.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.common.i18n.UiText
import org.siloserver.silo.common.i18n.uiText
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.common.R as CommonR
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
    val error: UiText? = null,
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
                            error = UiText.serverOr(result.message, CommonR.string.setup_error_failed),
                        )
                    }
                }

                is ApiResult.NetworkError -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = uiText(CommonR.string.signIn_error_networkConnection),
                        )
                    }
                }
            }
        }
    }

    fun onSetupSuccessConsumed() {
        _uiState.update { it.copy(setupSuccess = false) }
    }

    private fun validateFields(state: SetupUiState): UiText? {
        if (state.username.isBlank()) return uiText(CommonR.string.tvSignIn_error_usernameRequired)
        if (state.email.isBlank()) return uiText(CommonR.string.account_create_error_emailRequired)
        if (!state.email.contains("@")) return uiText(CommonR.string.account_create_error_emailInvalid)
        if (state.password.isBlank()) return uiText(CommonR.string.tvSignIn_error_passwordRequired)
        if (state.password.length < 8) return uiText(CommonR.string.account_create_error_passwordShort)
        return null
    }
}
