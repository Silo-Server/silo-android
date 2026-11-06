package org.siloserver.silo.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.siloserver.silo.common.i18n.SharedString
import org.siloserver.silo.common.i18n.UiText
import org.siloserver.silo.model.auth.PasswordChangeError
import org.siloserver.silo.model.auth.PasswordRequirements
import org.siloserver.silo.model.auth.User
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.getOrNull

/** What "Choose a new password" needs from the active account. */
interface TemporaryPasswordAccount {
    /** `GET /api/v2/account/me`, which a session holding a temporary password may read. */
    suspend fun readAccount(): ApiResult<User>

    /** The server's limits for a new password; its documented defaults when they can't be read. */
    suspend fun passwordRequirements(): PasswordRequirements

    /** `POST /api/v2/account/password`, sent once. */
    suspend fun changePassword(current: String, new: String): ApiResult<Unit>

    /** Replaces the restricted access token; true only when it rotated. */
    suspend fun refreshSessionAfterPasswordChange(): Boolean
}

sealed interface PasswordChangePhase {
    data object Editing : PasswordChangePhase

    /** The change, then the account read or refresh that follows it, is in flight. */
    data object Saving : PasswordChangePhase

    /**
     * The password changed but the refresh did not complete; [retrying] while
     * it runs again. The form stays closed: the temporary password it would
     * send no longer works.
     */
    data class RefreshFailed(val retrying: Boolean) : PasswordChangePhase

    /**
     * The change failed and the account couldn't be read, so whether the
     * password changed is unknown; [checking] while the account is read again.
     * The form stays closed so the change can't be sent twice.
     */
    data class Unconfirmed(val checking: Boolean) : PasswordChangePhase
}

data class PasswordChangeUiState(
    val currentPassword: String = "",
    val newPassword: String = "",
    val confirmation: String = "",
    val phase: PasswordChangePhase = PasswordChangePhase.Editing,
    val error: PasswordChangeError? = null,
    /** Bumped on each failed attempt so the fields shake. */
    val errorCount: Int = 0,
    /** The account's username, once read. */
    val accountName: String? = null,
    val requirements: PasswordRequirements = PasswordRequirements.ServerDefault,
    /** The session is refreshed; the screen continues to profile selection and consumes this. */
    val finished: Boolean = false,
) {
    val isBusy: Boolean
        get() = phase == PasswordChangePhase.Saving ||
            phase == PasswordChangePhase.RefreshFailed(retrying = true) ||
            phase == PasswordChangePhase.Unconfirmed(checking = true)

    val lead: UiText
        get() = accountName?.ifBlank { null }?.let { UiText.Key(SharedString.PasswordChangeLead, listOf(it)) }
            ?: UiText.Key(SharedString.PasswordChangeLeadAccount)

    val requirementsHint: UiText get() = requirements.hint
}

/**
 * "Choose a new password": the only thing a session holding a temporary
 * password can do. The account replaces the password an admin set, the
 * session is refreshed so the server lifts its restriction, and sign-in
 * continues to profile selection. Mirrors silo-apple's `PasswordChangeModel`.
 */
class PasswordChangeViewModel(
    private val account: TemporaryPasswordAccount,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PasswordChangeUiState())
    val uiState: StateFlow<PasswordChangeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    fun onCurrentPasswordChanged(value: String) = _uiState.update { it.copy(currentPassword = value) }
    fun onNewPasswordChanged(value: String) = _uiState.update { it.copy(newPassword = value) }
    fun onConfirmationChanged(value: String) = _uiState.update { it.copy(confirmation = value) }
    fun onFinishedConsumed() = _uiState.update { it.copy(finished = false) }

    fun save() {
        viewModelScope.launch { submit() }
    }

    fun confirmChange() {
        viewModelScope.launch { recheck() }
    }

    fun retryRefresh() {
        viewModelScope.launch { refreshAgain() }
    }

    /**
     * Reads the account and the server's limits. An account that no longer
     * holds a temporary password already changed it (the refresh never
     * finished, then the app relaunched): only the session still carries the
     * restriction, so it is refreshed instead of asking for a temporary
     * password that no longer works.
     */
    internal suspend fun load() {
        val (user, requirements) = coroutineScope {
            val limits = async { orNullIfThrown { account.passwordRequirements() } ?: PasswordRequirements.ServerDefault }
            orNullIfThrown { account.readAccount() }?.getOrNull() to limits.await()
        }
        _uiState.update { it.copy(accountName = user?.username, requirements = requirements) }
        if (user?.passwordChangeRequired != false || _uiState.value.phase != PasswordChangePhase.Editing) return
        _uiState.update { it.copy(phase = PasswordChangePhase.RefreshFailed(retrying = true)) }
        continueSignIn()
    }

    internal suspend fun submit() {
        val state = _uiState.value
        if (state.phase != PasswordChangePhase.Editing) return
        val invalid = if (state.currentPassword.isEmpty()) {
            PasswordChangeError.EmptyTemporary
        } else {
            state.requirements.problem(state.newPassword, state.confirmation)
        }
        if (invalid != null) {
            fail(invalid)
            return
        }
        _uiState.update { it.copy(phase = PasswordChangePhase.Saving, error = null) }
        // A call that throws is an unknown outcome, like a lost answer.
        val result = try {
            account.changePassword(state.currentPassword, state.newPassword)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ApiResult.NetworkError(e)
        }
        if (result !is ApiResult.Success) {
            // A lost response can hide a change the server saved, and sending
            // the temporary password again would then fail as incorrect. The
            // account says whether it still holds the temporary password. A
            // 4xx is the server's own refusal: nothing changed, so there is
            // nothing to check.
            val refused = result is ApiResult.Error && result.code in 400..499
            val remains = if (refused) true else temporaryPasswordRemains()
            when (remains) {
                true -> {
                    _uiState.update { it.copy(phase = PasswordChangePhase.Editing) }
                    fail(PasswordChangeError.of(result))
                    return
                }
                null -> {
                    _uiState.update { it.copy(phase = PasswordChangePhase.Unconfirmed(checking = false)) }
                    return
                }
                false -> Unit
            }
        }
        finishChange()
    }

    /** "Try again" when the change's outcome is unknown: reads the account, never resends. */
    internal suspend fun recheck() {
        if (_uiState.value.phase != PasswordChangePhase.Unconfirmed(checking = false)) return
        _uiState.update { it.copy(phase = PasswordChangePhase.Unconfirmed(checking = true)) }
        when (temporaryPasswordRemains()) {
            true -> {
                _uiState.update { it.copy(phase = PasswordChangePhase.Editing) }
                fail(PasswordChangeError.NotChanged)
            }
            null -> _uiState.update { it.copy(phase = PasswordChangePhase.Unconfirmed(checking = false)) }
            false -> {
                _uiState.update { it.copy(phase = PasswordChangePhase.RefreshFailed(retrying = true)) }
                finishChange()
            }
        }
    }

    /** "Try again" after the password changed but the refresh failed: refreshes only. */
    internal suspend fun refreshAgain() {
        if (_uiState.value.phase != PasswordChangePhase.RefreshFailed(retrying = false)) return
        _uiState.update { it.copy(phase = PasswordChangePhase.RefreshFailed(retrying = true)) }
        continueSignIn()
    }

    /** Whether the account still holds its temporary password; null when it can't be read. */
    private suspend fun temporaryPasswordRemains(): Boolean? {
        val user = orNullIfThrown { account.readAccount() }?.getOrNull() ?: return null
        // A server that doesn't report it counts as still holding it.
        return user.passwordChangeRequired ?: true
    }

    /** The password changed: clears the form, then finishes signing in. */
    private suspend fun finishChange() {
        _uiState.update { it.copy(currentPassword = "", newPassword = "", confirmation = "", error = null) }
        continueSignIn()
    }

    private suspend fun continueSignIn() {
        if (orNullIfThrown { account.refreshSessionAfterPasswordChange() } == true) {
            _uiState.update { it.copy(finished = true) }
        } else {
            _uiState.update { it.copy(phase = PasswordChangePhase.RefreshFailed(retrying = false)) }
        }
    }

    /**
     * [block]'s answer, or null when it threw. This screen runs in
     * [viewModelScope], where an escaping exception would crash the app; each
     * caller treats null as its unknown outcome. Cancellation still propagates.
     */
    private suspend fun <T> orNullIfThrown(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun fail(error: PasswordChangeError) {
        _uiState.update { it.copy(error = error, errorCount = it.errorCount + 1) }
    }
}
