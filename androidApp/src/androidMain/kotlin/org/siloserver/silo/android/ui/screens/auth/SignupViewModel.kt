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

data class SignupUiState(
    val username: String = "",
    val email: String = "",
    val password: String = "",
    val inviteCode: String = "",
    val isLoading: Boolean = false,
    val error: UiText? = null,
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
                        409 -> uiText(CommonR.string.account_create_error_taken)
                        403 -> uiText(CommonR.string.account_create_error_inviteInvalid)
                        else -> UiText.serverOr(result.message, CommonR.string.account_create_error_failed)
                    }
                    _uiState.update { it.copy(isLoading = false, error = message) }
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

    fun onSignupSuccessConsumed() {
        _uiState.update { it.copy(signupSuccess = false) }
    }

    private fun validateFields(state: SignupUiState): UiText? {
        if (state.username.isBlank()) return uiText(CommonR.string.tvSignIn_error_usernameRequired)
        if (state.email.isBlank()) return uiText(CommonR.string.account_create_error_emailRequired)
        if (!state.email.contains("@")) return uiText(CommonR.string.account_create_error_emailInvalid)
        if (state.password.isBlank()) return uiText(CommonR.string.tvSignIn_error_passwordRequired)
        if (state.password.length < 8) return uiText(CommonR.string.account_create_error_passwordShort)
        if (state.inviteCode.isBlank()) return uiText(CommonR.string.account_create_error_inviteRequired)
        return null
    }
}
