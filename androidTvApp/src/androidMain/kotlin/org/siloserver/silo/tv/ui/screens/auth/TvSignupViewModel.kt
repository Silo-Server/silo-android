package org.siloserver.silo.tv.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.i18n.UiText
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TvSignupUiState(
    val username: String = "",
    val email: String = "",
    val password: String = "",
    val inviteCode: String = "",
    val isLoading: Boolean = false,
    val error: UiText? = null,
    val signupSuccess: Boolean = false,
)

/**
 * TV variant of the phone app's `SignupViewModel`. Registers a new user with
 * an invite code. On success [AuthRepository.signup] persists tokens, so the
 * screen routes straight into profile selection — the user is signed in.
 */
class TvSignupViewModel(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TvSignupUiState())
    val uiState: StateFlow<TvSignupUiState> = _uiState.asStateFlow()

    fun onUsernameChanged(value: String) =
        _uiState.update { it.copy(username = value, error = null) }

    fun onEmailChanged(value: String) =
        _uiState.update { it.copy(email = value, error = null) }

    fun onPasswordChanged(value: String) =
        _uiState.update { it.copy(password = value, error = null) }

    fun onInviteCodeChanged(value: String) =
        _uiState.update { it.copy(inviteCode = value, error = null) }

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
            when (
                val result = authRepository.signup(
                    username = current.username,
                    email = current.email,
                    password = current.password,
                    inviteCode = current.inviteCode,
                )
            ) {
                is ApiResult.Success -> {
                    _uiState.update { it.copy(isLoading = false, signupSuccess = true) }
                }
                is ApiResult.Error -> {
                    val message = when (result.code) {
                        409 -> UiText.Res(CommonR.string.account_create_error_taken)
                        403 -> UiText.Res(CommonR.string.account_create_error_inviteInvalid)
                        else -> UiText.serverOr(result.message, CommonR.string.account_create_error_failed)
                    }
                    _uiState.update { it.copy(isLoading = false, error = message) }
                }
                is ApiResult.NetworkError -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = UiText.Res(CommonR.string.signIn_error_networkConnection),
                        )
                    }
                }
            }
        }
    }

    fun onSignupSuccessConsumed() {
        _uiState.update { it.copy(signupSuccess = false) }
    }

    private fun validateFields(state: TvSignupUiState): UiText? = when {
        state.username.isBlank() -> CommonR.string.tvSignIn_error_usernameRequired
        state.email.isBlank() -> CommonR.string.account_create_error_emailRequired
        !state.email.contains("@") -> CommonR.string.account_create_error_emailInvalid
        state.password.isBlank() -> CommonR.string.tvSignIn_error_passwordRequired
        state.password.length < 8 -> CommonR.string.account_create_error_passwordShort
        state.inviteCode.isBlank() -> CommonR.string.account_create_error_inviteRequired
        else -> null
    }?.let { UiText.Res(it) }
}
