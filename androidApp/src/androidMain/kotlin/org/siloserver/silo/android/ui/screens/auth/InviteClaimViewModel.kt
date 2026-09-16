package org.siloserver.silo.android.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
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
    val acceptanceUnavailable: Boolean = false,
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
    val signInRequiredUsername: String? = null,
    val acceptanceUncertain: Boolean = false,
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
    private val lookupGeneration = MutableStateFlow(0L)
    private var claimJob: Job? = null

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
        claimJob?.cancel()
        lookupGeneration.value += 1
        val generation = lookupGeneration.value
        _uiState.update { InviteClaimUiState() }
        viewModelScope.launch {
            val result = authRepository.lookupInvitation(serverUrl, token)
            if (generation != lookupGeneration.value) return@launch
            when (result) {
                is ApiResult.Success -> _uiState.update {
                    it.copy(isLoadingInvitation = false, invitation = result.data)
                }
                is ApiResult.Error -> {
                    // Only statuses that speak about the token itself are
                    // terminal. A 429/5xx says nothing about the invite, and
                    // showing "expired" for it sends the user off to have a
                    // valid link revoked and reissued.
                    if (result.error == "capability_unavailable") {
                        _uiState.update { it.copy(isLoadingInvitation = false, acceptanceUnavailable = true) }
                    } else if (result.code in TERMINAL_LOOKUP_CODES) {
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
        if (current.isSubmitting || current.pendingCleartextOrigin != null || current.claimSuccess ||
            current.signInRequiredUsername != null || current.acceptanceUncertain ||
            current.invitation?.acceptanceAvailable != true) return
        val validationError = when {
            current.password.codePointCount(0, current.password.length) < 8 -> "Password must be at least 8 characters"
            current.password.toByteArray(Charsets.UTF_8).size > 72 -> "Password must be at most 72 UTF-8 bytes"
            current.password != current.confirmPassword -> "Passwords do not match"
            else -> null
        }
        if (validationError != null) {
            _uiState.update { it.copy(error = validationError) }
            return
        }
        val generation = lookupGeneration.value
        val origin = serverUrl
        val claimToken = token
        _uiState.update { it.copy(isSubmitting = true, error = null) }
        claimJob = viewModelScope.launch {
            if (cleartextConsentStore.requiresApproval(origin)) {
                if (generation == lookupGeneration.value) {
                    _uiState.update { it.copy(isSubmitting = false, pendingCleartextOrigin = origin) }
                }
                return@launch
            }
            submitClaim(generation, origin, claimToken, current.password)
        }
    }

    fun onConfirmCleartext() {
        val current = _uiState.value
        val origin = current.pendingCleartextOrigin ?: return
        if (current.isSubmitting) return
        val generation = lookupGeneration.value
        val claimToken = token
        _uiState.update { it.copy(isSubmitting = true, pendingCleartextOrigin = null) }
        claimJob = viewModelScope.launch {
            cleartextConsentStore.approve(origin)
            if (generation != lookupGeneration.value) return@launch
            submitClaim(generation, origin, claimToken, current.password)
        }
    }

    fun onCancelCleartext() {
        _uiState.update { it.copy(pendingCleartextOrigin = null) }
    }

    private suspend fun submitClaim(generation: Long, origin: String, claimToken: String, password: String) {
        val result = authRepository.acceptInvitation(origin, claimToken, password) {
            generation == lookupGeneration.value
        }
        if (generation != lookupGeneration.value) return
        when (result) {
            is ApiResult.Success -> _uiState.update {
                it.copy(isSubmitting = false, claimSuccess = result.data.signedIn,
                    signInRequiredUsername = result.data.username.takeUnless { result.data.signedIn },
                    password = "", confirmPassword = "")
            }
            is ApiResult.Error -> {
                val invalid = result.code == 404
                val uncertain = result.code !in setOf(400, 404)
                _uiState.update {
                    it.copy(isSubmitting = false, invitationInvalid = invalid,
                        acceptanceUncertain = uncertain,
                        error = if (uncertain) "Account creation could not be confirmed. Check sign-in before trying another invitation."
                            else result.errorMessage("Could not accept this invitation"))
                }
            }
            is ApiResult.NetworkError -> _uiState.update {
                it.copy(isSubmitting = false, acceptanceUncertain = true,
                    error = "Account creation could not be confirmed. Check sign-in before trying another invitation.")
            }
        }
    }

    override fun onCleared() {
        lookupGeneration.value += 1
        claimJob?.cancel()
        super.onCleared()
    }

    private companion object {
        /** Statuses that mean the token itself is gone, used, or malformed. */
        private val TERMINAL_LOOKUP_CODES = setOf(404)
    }
}
