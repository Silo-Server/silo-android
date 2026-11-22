package org.siloserver.silo.tv.ui.screens.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.siloserver.silo.model.server.AccountCard
import org.siloserver.silo.model.server.AccountEntry
import org.siloserver.silo.model.server.ServerEntry
import org.siloserver.silo.model.server.buildAccountGroups
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.repository.AuthRepository
import org.siloserver.silo.tv.auth.TvSignOut
import org.siloserver.silo.tv.profiles.TvActiveProfileReset
import org.siloserver.silo.tv.profiles.noAccountOpenButAnotherSignedIn

/** One saved server and its accounts, in the order Change Account shows them. */
data class TvAccountsServer(val server: ServerEntry, val accounts: List<AccountEntry>)

/** Where the Accounts screen hands off. */
sealed interface TvAccountsNavigation {
    /** Sign in on the (now active) server; [username] fills in a saved account's name. */
    data class SignIn(val username: String?) : TvAccountsNavigation

    /** The active server changed under the screens behind this one. */
    data class Switched(val destination: TvServerSwitchDestination) : TvAccountsNavigation
}

data class TvAccountsUiState(
    val servers: List<TvAccountsServer> = emptyList(),
    val activeAccountId: String? = null,
    val navigation: TvAccountsNavigation? = null,
    /** Set once when removing a server failed to save; cleared by [TvAccountsViewModel.onRemoveFailedConsumed]. */
    val removeFailed: Boolean = false,
)

/**
 * Settings › Accounts (multi-account spec §4.5), which replaces the TV's
 * server list: each saved server with its accounts, adding an account on a
 * server or a server, renaming and removing a server, and an account's own
 * Sign in again and Sign out.
 *
 * Sign-out and removal of the server in use go through [TvSignOut], the one
 * TV sign-out, whose result the navigator handles. Android TV can only sign
 * out of the active account, so signing out of another one makes it active
 * first; the TV then lands on the picker.
 */
class TvAccountsViewModel(
    private val serverRegistry: ServerRegistry,
    private val authRepository: AuthRepository,
    private val resolver: TvServerResolver,
    private val tvSignOut: TvSignOut,
    private val profileReset: TvActiveProfileReset,
    private val tokenManager: TokenManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TvAccountsUiState())
    val uiState: StateFlow<TvAccountsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { authRepository.refreshActiveServerName() }
        viewModelScope.launch {
            combine(
                serverRegistry.entries,
                serverRegistry.accounts,
                serverRegistry.activeServerId,
                serverRegistry.activeAccountId,
            ) { entries, accounts, activeServerId, activeAccountId ->
                val servers = buildAccountGroups(entries, accounts, activeServerId, activeAccountId).map { group ->
                    TvAccountsServer(server = group.server, accounts = group.accounts.map(AccountCard::account))
                }
                servers to activeAccountId
            }.collect { (servers, activeAccountId) ->
                _uiState.update { it.copy(servers = servers, activeAccountId = activeAccountId) }
            }
        }
    }

    /** "Add an account on {server}": a sign-in there with no account selected. */
    fun onAddAccount(serverId: String) {
        viewModelScope.launch {
            if (activate(serverId, accountId = null)) navigate(TvAccountsNavigation.SignIn(username = null))
        }
    }

    /** An account's Sign in again: its sign-in, with its username filled in. */
    fun onSignInAgain(account: AccountEntry) {
        viewModelScope.launch {
            if (activate(account.serverId, account.localAccountId)) {
                navigate(TvAccountsNavigation.SignIn(username = account.username))
            }
        }
    }

    /** An account's Sign out, once confirmed; [removeServer] forgets its server too. */
    fun onSignOut(account: AccountEntry, removeServer: Boolean = false) {
        viewModelScope.launch {
            if (!activate(account.serverId, account.localAccountId)) return@launch
            if (!account.needsSignIn) {
                tvSignOut.start(removeServer = removeServer)
                return@launch
            }
            if (removeServer) {
                // Its server refused the session already: forget the server here.
                when (val removal = resolver.remove(account.serverId)) {
                    TvServerResolver.Removal.Failed -> _uiState.update { it.copy(removeFailed = true) }
                    is TvServerResolver.Removal.Removed ->
                        removal.destination?.let { navigate(TvAccountsNavigation.Switched(it)) }
                }
                return@launch
            }
            // The server already refused this account's session, so there is
            // nothing to end there: forget it on this TV.
            val signedOut = try {
                tokenManager.signOutCurrentServer()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (!signedOut) return@launch
            val next = if (serverRegistry.noAccountOpenButAnotherSignedIn()) {
                TvServerSwitchDestination.ChangeAccount
            } else {
                TvServerSwitchDestination.Login
            }
            navigate(TvAccountsNavigation.Switched(next))
        }
    }

    fun onRename(serverId: String, name: String) {
        viewModelScope.launch {
            runCatching { serverRegistry.rename(serverId, name.trim().takeIf { it.isNotEmpty() }) }
        }
    }

    /**
     * Remove server signs out of the account in use there, then forgets the
     * server and every account saved on it.
     */
    fun onRemoveServer(serverId: String) {
        viewModelScope.launch {
            if (serverRegistry.activeServerId.value == serverId && serverRegistry.activeAccountId.value != null) {
                tvSignOut.start(removeServer = true)
                return@launch
            }
            when (val removal = resolver.remove(serverId)) {
                TvServerResolver.Removal.Failed -> _uiState.update { it.copy(removeFailed = true) }
                // Removing a server not in use is a list edit; removing the
                // active one tears down the screens still showing it.
                is TvServerResolver.Removal.Removed ->
                    removal.destination?.let { navigate(TvAccountsNavigation.Switched(it)) }
            }
        }
    }

    fun onNavigationConsumed() {
        _uiState.update { it.copy(navigation = null) }
    }

    fun onRemoveFailedConsumed() {
        _uiState.update { it.copy(removeFailed = false) }
    }

    private fun navigate(navigation: TvAccountsNavigation) {
        _uiState.update { it.copy(navigation = navigation) }
    }

    /**
     * Makes [accountId] on [serverId] active. Leaving the profile in use drops
     * its profile caches and Watch Next first. False when the switch failed.
     */
    private suspend fun activate(serverId: String, accountId: String?): Boolean {
        if (serverRegistry.activeServerId.value == serverId && serverRegistry.activeAccountId.value == accountId) {
            return true
        }
        return try {
            profileReset.clearActiveProfile()
            authRepository.switchToAccount(serverId, accountId)
            serverRegistry.activeServerId.value == serverId && serverRegistry.activeAccountId.value == accountId
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }
}
