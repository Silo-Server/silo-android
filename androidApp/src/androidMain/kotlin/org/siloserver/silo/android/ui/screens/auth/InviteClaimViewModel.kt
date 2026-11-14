package org.siloserver.silo.android.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.siloserver.silo.common.i18n.UiText
import org.siloserver.silo.common.i18n.uiText
import org.siloserver.silo.common.network.CleartextConsentStore
import org.siloserver.silo.common.network.cleartextOrigin
import org.siloserver.silo.common.R as CommonR
import org.siloserver.silo.common.i18n.errorText
import org.siloserver.silo.model.auth.InvitationLookupResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.CleartextOriginNotApprovedException
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
     * The claim POST, and the lookup POST on a server that takes the token in
     * the body, carry credentials, so they need the same explicit consent the
     * manual server-setup flow collects.
     */
    val pendingCleartextOrigin: String? = null,
    val password: String = "",
    val confirmPassword: String = "",
    val isSubmitting: Boolean = false,
    val error: UiText? = null,
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
        viewModelScope.launch { lookup(generation, serverUrl, token) }
    }

    private suspend fun lookup(generation: Long, serverUrl: String, token: String) {
        val result = authRepository.lookupInvitation(serverUrl, token)
        if (generation != lookupGeneration.value) return
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
            is ApiResult.NetworkError -> _uiState.update {
                // A server that takes the token in the body gets the lookup as
                // a POST, which a cleartext origin only receives after consent:
                // ask, and run the lookup again once approved. An origin that
                // can't be approved fails like an unreachable server.
                if (result.exception is CleartextOriginNotApprovedException && cleartextOrigin(serverUrl) != null) {
                    it.copy(pendingCleartextOrigin = serverUrl)
                } else {
                    // A failure to reach the server says nothing about the
                    // invite either.
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
            current.password.codePointCount(0, current.password.length) < 8 -> uiText(CommonR.string.account_create_error_passwordShort)
            current.password.toByteArray(Charsets.UTF_8).size > 72 -> uiText(CommonR.string.account_create_error_passwordLong)
            current.password != current.confirmPassword -> uiText(CommonR.string.account_create_error_passwordMismatch)
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
        // Consent asked before the lookup: approve it, then run the lookup.
        if (current.invitation == null) {
            _uiState.update { it.copy(pendingCleartextOrigin = null) }
            viewModelScope.launch {
                try {
                    cleartextConsentStore.approve(origin)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Consent wasn't saved, so the lookup would be refused
                    // again: offer the retry, which asks again.
                    if (generation == lookupGeneration.value) {
                        _uiState.update { it.copy(isLoadingInvitation = false, lookupFailed = true) }
                    }
                    return@launch
                }
                if (generation != lookupGeneration.value) return@launch
                lookup(generation, origin, claimToken)
            }
            return
        }
        _uiState.update { it.copy(isSubmitting = true, pendingCleartextOrigin = null) }
        claimJob = viewModelScope.launch {
            cleartextConsentStore.approve(origin)
            if (generation != lookupGeneration.value) return@launch
            submitClaim(generation, origin, claimToken, current.password)
        }
    }

    fun onCancelCleartext() {
        // Declined before the lookup: nothing can load, so offer the retry,
        // which asks again.
        _uiState.update {
            if (it.invitation == null) {
                it.copy(pendingCleartextOrigin = null, isLoadingInvitation = false, lookupFailed = true)
            } else {
                it.copy(pendingCleartextOrigin = null)
            }
        }
    }

    private suspend fun submitClaim(generation: Long, origin: String, claimToken: String, password: String) {
        val tokenInBody = _uiState.value.invitation?.tokenInBody == true
        val result = authRepository.acceptInvitation(origin, claimToken, password, tokenInBody) {
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
                val uncertain = !result.isUnspentRefusal()
                _uiState.update {
                    it.copy(isSubmitting = false, invitationInvalid = invalid,
                        acceptanceUncertain = uncertain,
                        error = if (uncertain) uiText(CommonR.string.account_invite_error_unconfirmed)
                            else result.errorText(CommonR.string.account_invite_error_failed))
                }
            }
            is ApiResult.NetworkError -> _uiState.update {
                it.copy(isSubmitting = false, acceptanceUncertain = true,
                    error = uiText(CommonR.string.account_invite_error_unconfirmed))
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

        /**
         * Accept answers the server gives before it commits anything (silo-server
         * docs/invitations-api.md): the user can fix the cause or try again. Any
         * other error, such as a 5xx or one the app raises after the server
         * answered, may follow a created account and stays uncertain.
         */
        private val UNSPENT_REFUSAL_CODES = setOf(400, 403, 404, 409, 422, 429)

        private fun ApiResult.Error.isUnspentRefusal() =
            code in UNSPENT_REFUSAL_CODES || (code == 501 && error == "capability_unsupported")
    }
}
