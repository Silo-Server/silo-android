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
import org.siloserver.silo.common.i18n.errorText

data class TvSetupUiState(
    val username: String = "",
    val email: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val error: UiText? = null,
    val setupSuccess: Boolean = false,
)

/**
 * TV variant of the phone app's `SetupViewModel`. Creates the first admin
 * account on a freshly-installed server. On success [AuthRepository.setup]
 * persists tokens, so the screen routes straight into the authenticated flow
 * (profile selection) — the user is already signed in.
 */
class TvSetupViewModel(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TvSetupUiState())
    val uiState: StateFlow<TvSetupUiState> = _uiState.asStateFlow()

    fun onUsernameChanged(value: String) =
        _uiState.update { it.copy(username = value, error = null) }

    fun onEmailChanged(value: String) =
        _uiState.update { it.copy(email = value, error = null) }

    fun onPasswordChanged(value: String) =
        _uiState.update { it.copy(password = value, error = null) }

    fun onCreateAccountClick() {
        if (_uiState.value.isLoading) return
        val current = _uiState.value

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
                            error = result.errorText(CommonR.string.setup_error_failed),
                        )
                    }
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

    fun onSetupSuccessConsumed() {
        _uiState.update { it.copy(setupSuccess = false) }
    }

    private fun validateFields(state: TvSetupUiState): UiText? = when {
        state.username.isBlank() -> CommonR.string.tvSignIn_error_usernameRequired
        state.email.isBlank() -> CommonR.string.account_create_error_emailRequired
        !state.email.contains("@") -> CommonR.string.account_create_error_emailInvalid
        state.password.isBlank() -> CommonR.string.tvSignIn_error_passwordRequired
        state.password.length < 8 -> CommonR.string.account_create_error_passwordShort
        else -> null
    }?.let { UiText.Res(it) }
}
