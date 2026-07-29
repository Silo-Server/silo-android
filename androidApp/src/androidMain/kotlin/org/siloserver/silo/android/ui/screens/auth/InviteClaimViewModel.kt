package org.siloserver.silo.android.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.siloserver.silo.common.network.CleartextConsentStore
import org.siloserver.silo.model.auth.InvitationLookupResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.errorMessage
import org.siloserver.silo.network.requiresApproval
import org.siloserver.silo.repository.AuthRepository

data class InviteClaimUiState(
    val isLoadingInvitation: Boolean = true,
    val invitation: InvitationLookupResponse? = null,
    /** The server said the token itself is dead — the invite really is gone. */
    val invitationInvalid: Boolean = false,
    /** The lookup failed for reasons unrelated to the token; offer retry. */
    val lookupFailed: Boolean = false,
    /**
     * The invite points at a cleartext HTTP server the user hasn't approved.
     * The claim POST carries credentials, so it needs the same explicit
     * consent the manual server-setup flow collects.
     */
    val pendingCleartextOrigin: String? = null,
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
    private val cleartextConsentStore: CleartextConsentStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(InviteClaimUiState())
    val uiState: StateFlow<InviteClaimUiState> = _uiState.asStateFlow()

    private var serverUrl: String = ""
    private var token: String = ""

    /**
     * Bumped whenever the target invite changes; in-flight lookups compare
     * against it before touching state, so a slow response for a superseded
     * link can't paint another invite's email over the current one.
     */
    private var lookupGeneration = 0

    fun load(serverUrl: String, token: String) {
        // An invite is identified by server *and* token: the same token can
        // exist on another server, and matching on the token alone would keep
        // the previous server, submitting the password to the wrong one.
        if (
            this.serverUrl == serverUrl &&
            this.token == token &&
            _uiState.value.invitation != null
        ) {
            return
        }
        this.serverUrl = serverUrl
        this.token = token
        val generation = ++lookupGeneration
        _uiState.update { InviteClaimUiState() }
        viewModelScope.launch {
            val result = authRepository.lookupInvitation(serverUrl, token)
            if (generation != lookupGeneration) return@launch
            when (result) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(isLoadingInvitation = false, invitation = result.data)
                }
                is ApiResult.Error -> {
                    // Only statuses that speak about the token itself are
                    // terminal. A 429/5xx says nothing about the invite, and
                    // showing "expired" for it sends the user off to have a
                    // valid link revoked and reissued.
                    if (result.code in TERMINAL_LOOKUP_CODES) {
                        _uiState.update {
                            it.copy(isLoadingInvitation = false, invitationInvalid = true)
                        }
                    } else {
                        _uiState.update {
                            it.copy(isLoadingInvitation = false, lookupFailed = true)
                        }
                    }
                }
                // A failure to reach the server says nothing about the invite
                // either.
                is ApiResult.NetworkError -> _uiState.update {
                    it.copy(isLoadingInvitation = false, lookupFailed = true)
                }
            }
        }
    }

    fun onRetryLookup() {
        val url = serverUrl
        val tok = token
        // Clear the loaded marker so load() runs the lookup again.
        this.token = ""
        load(url, tok)
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
            // The read-only lookup may have slipped past the cleartext gate,
            // but the claim POST carries a password and will be rejected by
            // the interceptor for an unapproved http:// origin — surfacing as
            // an opaque network error. Ask for the same consent server setup
            // does, then proceed.
            if (cleartextConsentStore.requiresApproval(serverUrl)) {
                _uiState.update { it.copy(pendingCleartextOrigin = serverUrl) }
                return@launch
            }
            submitClaim()
        }
    }

    fun onConfirmCleartext() {
        val origin = _uiState.value.pendingCleartextOrigin ?: return
        viewModelScope.launch {
            cleartextConsentStore.approve(origin)
            _uiState.update { it.copy(pendingCleartextOrigin = null) }
            submitClaim()
        }
    }

    fun onCancelCleartext() {
        _uiState.update { it.copy(pendingCleartextOrigin = null) }
    }

    private suspend fun submitClaim() {
        val current = _uiState.value
        // Pin the invite this submission is for. A second deep link can replace
        // the route mid-POST; without this the first response would still drive
        // the UI — navigating away from the invite now on screen, or reporting
        // success for an account on a server the user is no longer claiming.
        val generation = lookupGeneration
        val claimServerUrl = serverUrl
        val claimToken = token
        _uiState.update { it.copy(isSubmitting = true, error = null) }
        // acceptInvitation talks to the invite's server directly and only
        // adopts it as the active server after the claim succeeds, so a
        // failed claim leaves any existing session untouched.
        val result = authRepository.acceptInvitation(claimServerUrl, claimToken, current.password)
        if (generation != lookupGeneration) return
        when (result) {
            is ApiResult.Success -> {
                _uiState.update { it.copy(isSubmitting = false, claimSuccess = true) }
            }
            is ApiResult.Error -> {
                val message = when (result.code) {
                    404 -> "This invitation is invalid or has expired."
                    409 -> "This invitation has already been used."
                    else -> result.errorMessage("Could not create your account")
                }
                _uiState.update { it.copy(isSubmitting = false, error = message) }
            }
            is ApiResult.NetworkError -> _uiState.update {
                it.copy(isSubmitting = false, error = result.errorMessage("Could not create your account"))
            }
        }
    }

    private companion object {
        /** Statuses that mean the token itself is gone, used, or malformed. */
        private val TERMINAL_LOOKUP_CODES = setOf(400, 404, 409, 410)
    }
}
