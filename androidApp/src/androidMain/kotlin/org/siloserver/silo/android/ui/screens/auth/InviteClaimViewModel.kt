package org.siloserver.silo.android.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.siloserver.silo.model.auth.InvitationLookupResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.AuthRepository

data class InviteClaimUiState(
    val isLoadingInvitation: Boolean = true,
    val invitation: InvitationLookupResponse? = null,
    val invitationInvalid: Boolean = false,
    val password: String = "",
    val confirmPassword: String = "",
    val isSubmitting: Boolean = false,
    val error: String? = null,
    val claimSuccess: Boolean = false,
)

/**
 * Claim flow for an emailed invitation deep link (silo://invite or an
 * https app link): server URL and token arrive in the link, the invitee
 * chooses only a password. On success the account is created, tokens are
 * stored, and the server is registered so the rest of the app works exactly
 * as after a normal login.
 */
class InviteClaimViewModel(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(InviteClaimUiState())
    val uiState: StateFlow<InviteClaimUiState> = _uiState.asStateFlow()

    private var serverUrl: String = ""
    private var token: String = ""

    fun load(serverUrl: String, token: String) {
        if (this.token == token && _uiState.value.invitation != null) return
        this.serverUrl = serverUrl
        this.token = token
        _uiState.update { InviteClaimUiState() }
        viewModelScope.launch {
            when (val result = authRepository.lookupInvitation(serverUrl, token)) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(isLoadingInvitation = false, invitation = result.data)
                }
                is ApiResult.Error -> _uiState.update {
                    it.copy(isLoadingInvitation = false, invitationInvalid = true)
                }
                is ApiResult.NetworkError -> _uiState.update {
                    it.copy(
                        isLoadingInvitation = false,
                        invitationInvalid = true,
                        error = "Could not reach the server.",
                    )
                }
            }
        }
    }

    fun onPasswordChanged(value: String) {
        _uiState.update { it.copy(password = value, error = null) }
    }

    fun onConfirmPasswordChanged(value: String) {
        _uiState.update { it.copy(confirmPassword = value, error = null) }
    }

    fun onClaimClick() {
        val current = _uiState.value
        val validationError = when {
            current.password.length < 8 -> "Password must be at least 8 characters"
            current.password != current.confirmPassword -> "Passwords do not match"
            else -> null
        }
        if (validationError != null) {
            _uiState.update { it.copy(error = validationError) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, error = null) }
            // Activate the server first so the tokens accept persists land in
            // that server's scope — the same order the manual setup flow uses.
            authRepository.setServerUrl(serverUrl)
            when (val result = authRepository.acceptInvitation(serverUrl, token, current.password)) {
                is ApiResult.Success -> {
                    _uiState.update { it.copy(isSubmitting = false, claimSuccess = true) }
                }
                is ApiResult.Error -> {
                    val message = when (result.code) {
                        404 -> "This invitation is invalid or has expired."
                        409 -> "This invitation has already been used."
                        else -> result.message.ifBlank { "Could not create your account" }
                    }
                    _uiState.update { it.copy(isSubmitting = false, error = message) }
                }
                is ApiResult.NetworkError -> _uiState.update {
                    it.copy(isSubmitting = false, error = "Network error. Please check your connection.")
                }
            }
        }
    }

    fun onClaimSuccessConsumed() {
        _uiState.update { it.copy(claimSuccess = false) }
    }
}
