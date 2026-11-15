package org.siloserver.silo.android.ui.screens.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.siloserver.silo.common.ui.marquee.SignInHandoff
import org.siloserver.silo.model.server.AccountCard
import org.siloserver.silo.model.server.AccountServerGroup
import org.siloserver.silo.model.server.buildAccountGroups
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.repository.AccountProfilesRefresher
import org.siloserver.silo.repository.AuthRepository
import org.siloserver.silo.repository.ProfileRepository

/** Where Change Account sends the person once a choice has been applied. */
enum class ChangeAccountDestination {
    /**
     * An account was opened (another one, or the open one while no profile
     * is): its "Who's watching?", waiting for a choice.
     */
    Picker,

    /** The open account was chosen: back to its picker. */
    CurrentPicker,

    /** Sign-in on the now-active server: a new account, or an expired one with its username filled in. */
    SignIn,
}

data class ChangeAccountUiState(
    /** Saved servers, the active one first and then by last use, each with its accounts. */
    val groups: List<AccountServerGroup> = emptyList(),
    /** A switch is running: rows ignore taps. */
    val isWorking: Boolean = false,
    val destination: ChangeAccountDestination? = null,
)

/**
 * Change Account (owner decision 2026-11-02, Roku spec §7.5): every saved
 * server's accounts with their profile counts, to open another account, sign
 * one in again, or add an account or a server. Profile counts come from each
 * account's cached list, refreshed while the screen is open. It is also what
 * Profile Selection shows in the picker's place when several accounts are
 * saved.
 */
class ChangeAccountViewModel(
    private val registry: ServerRegistry,
    private val authRepository: AuthRepository,
    private val tokenManager: TokenManager,
    private val profileRepository: ProfileRepository,
    private val profilesRefresher: AccountProfilesRefresher,
    /** Pushes settings still waiting to sync while the account they were made on is active. */
    private val flushPendingSettings: suspend () -> Unit,
    /** Drops the per-profile caches once another account is active, as a server switch does. */
    private val clearProfileCaches: () -> Unit,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChangeAccountUiState())
    val uiState: StateFlow<ChangeAccountUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(registry.entries, registry.accounts, registry.activeServerId, registry.activeAccountId) {
                    entries, accounts, activeServerId, activeAccountId ->
                // An account counts as open only while it has a session.
                val open = activeAccountId?.takeIf { !tokenManager.getAccessToken().isNullOrBlank() }
                buildAccountGroups(entries, accounts, activeServerId, open)
            }.collect { groups ->
                _uiState.update { it.copy(groups = groups) }
            }
        }
        viewModelScope.launch { runCatching { profilesRefresher.refreshAll() } }
    }

    /**
     * An account row. The open one returns to its picker; one that needs to
     * sign in again opens sign-in with its username filled in; any other
     * becomes active on its server and shows its picker, which waits for a
     * choice (a single profile without a PIN still opens).
     *
     * [noProfileOpen]: this screen is the root in the picker's place
     * (Profile Selection asked with several accounts saved), so the open
     * account is chosen like any other.
     */
    fun onAccount(card: AccountCard, noProfileOpen: Boolean = false) {
        val account = card.account
        if (card.isCurrent && !account.needsSignIn) {
            val destination = if (noProfileOpen) {
                SignInHandoff.skipsSingleProfilePicker = true
                ChangeAccountDestination.Picker
            } else {
                ChangeAccountDestination.CurrentPicker
            }
            _uiState.update { it.copy(destination = destination) }
            return
        }
        switchTo(account.serverId, account.localAccountId)
    }

    /** "Add an account" / "Sign in on {server}": the server becomes active with no account, and sign-in adds one. */
    fun onAddAccount(serverId: String) = switchTo(serverId, accountId = null)

    private fun switchTo(serverId: String, accountId: String?) {
        if (_uiState.value.isWorking) return
        _uiState.update { it.copy(isWorking = true) }
        viewModelScope.launch {
            try {
                val switched = switchActiveAccount(
                    registry, authRepository, serverId, accountId, flushPendingSettings, clearProfileCaches,
                )
                if (!switched) return@launch
                val signedIn = accountId != null && !tokenManager.getAccessToken().isNullOrBlank()
                if (!signedIn) {
                    _uiState.update { it.copy(destination = ChangeAccountDestination.SignIn) }
                    return@launch
                }
                // The picker asks rather than reopening the remembered
                // profile, so a protected profile needs its PIN again; a
                // household with one unprotected profile opens it.
                profileRepository.clearProfile()
                SignInHandoff.skipsSingleProfilePicker = true
                _uiState.update { it.copy(destination = ChangeAccountDestination.Picker) }
            } finally {
                _uiState.update { it.copy(isWorking = false) }
            }
        }
    }

    fun onDestinationConsumed() {
        _uiState.update { it.copy(destination = null) }
    }
}

/**
 * Makes [serverId]'s [accountId] (null: no account, so sign-in adds one)
 * active, as Change Account and Settings › Accounts do. Settings still
 * waiting to sync are pushed first, within [ACCOUNT_SWITCH_FLUSH_TIMEOUT_MS],
 * and the previous identity's per-profile caches are dropped after. Returns
 * false when the switch did not take (another switch won meanwhile); true,
 * with nothing done, when that account is already active.
 */
internal suspend fun switchActiveAccount(
    registry: ServerRegistry,
    authRepository: AuthRepository,
    serverId: String,
    accountId: String?,
    flushPendingSettings: suspend () -> Unit,
    clearProfileCaches: () -> Unit,
): Boolean {
    if (registry.activeServerId.value == serverId && registry.activeAccountId.value == accountId) return true
    withTimeoutOrNull(ACCOUNT_SWITCH_FLUSH_TIMEOUT_MS) { flushPendingSettings() }
    authRepository.switchToAccount(serverId, accountId)
    if (registry.activeServerId.value != serverId || registry.activeAccountId.value != accountId) return false
    clearProfileCaches()
    return true
}

private const val ACCOUNT_SWITCH_FLUSH_TIMEOUT_MS = 5_000L
