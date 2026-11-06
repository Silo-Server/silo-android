package org.siloserver.silo.tv.ui.screens.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.siloserver.silo.common.ui.marquee.SignInHandoff
import org.siloserver.silo.model.server.AccountCard
import org.siloserver.silo.model.server.AccountServerGroup
import org.siloserver.silo.model.server.buildAccountGroups
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.repository.AccountProfilesRefresher
import org.siloserver.silo.repository.AuthRepository
import org.siloserver.silo.tv.profiles.TvActiveProfileReset

/** Where Change Account hands off. */
sealed interface TvChangeAccountNavigation {
    /**
     * The open account's picker. [switched]: another account was just made
     * active, so every screen behind this one belongs to the old account.
     */
    data class Picker(val switched: Boolean) : TvChangeAccountNavigation

    /**
     * Sign-in on the active server, with [username] filled in for a saved
     * account. [switched] as for [Picker]; Back from it returns here.
     */
    data class SignIn(val username: String?, val switched: Boolean) : TvChangeAccountNavigation

    /** The existing server setup flow. */
    data object AddServer : TvChangeAccountNavigation
}

data class TvChangeAccountUiState(
    val groups: List<AccountServerGroup> = emptyList(),
    /** [groups] reflects the registry (until then it is only the empty default). */
    val loaded: Boolean = false,
    /** Switching to the chosen account failed (Roku's accounts.error.change). */
    val changeFailed: Boolean = false,
    val navigation: TvChangeAccountNavigation? = null,
)

/**
 * Change Account (owner decision 2026-11-02, Roku spec §7.5): the saved
 * servers, each with its accounts. Choosing an account makes it active on
 * its server and opens its picker, which waits for a choice; one that needs
 * to sign in again opens its sign-in; "Add an account" signs in on a server.
 *
 * A change of account drops the departing profile's caches and Watch Next,
 * as the picker's switch did; the next profile chosen seeds Watch Next.
 */
class TvChangeAccountViewModel(
    private val serverRegistry: ServerRegistry,
    private val authRepository: AuthRepository,
    private val profileReset: TvActiveProfileReset,
    private val profilesRefresher: AccountProfilesRefresher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TvChangeAccountUiState())
    val uiState: StateFlow<TvChangeAccountUiState> = _uiState.asStateFlow()

    /** The change in progress; a second press waits for it rather than racing it. */
    private var changeJob: Job? = null

    init {
        viewModelScope.launch {
            combine(
                serverRegistry.entries,
                serverRegistry.accounts,
                serverRegistry.activeServerId,
                serverRegistry.activeAccountId,
            ) { entries, accounts, activeServerId, activeAccountId ->
                buildAccountGroups(entries, accounts, activeServerId, activeAccountId)
            }.collect { groups ->
                _uiState.update { it.copy(groups = groups, loaded = true) }
            }
        }
        // The cards' profile counts come from each account's cached list;
        // refresh them while the screen is open. Unreachable servers keep theirs.
        viewModelScope.launch {
            try {
                profilesRefresher.refreshAll()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /** An account card. */
    fun onAccountChosen(card: AccountCard) {
        val account = card.account
        if (card.isCurrent && !account.needsSignIn) {
            navigate(TvChangeAccountNavigation.Picker(switched = false))
            return
        }
        change {
            val switched = !isActive(account.serverId, account.localAccountId)
            if (!activate(account.serverId, account.localAccountId)) return@change
            if (account.needsSignIn) {
                navigate(TvChangeAccountNavigation.SignIn(account.username, switched = switched))
            } else {
                // The picker waits for a choice; only a lone profile without
                // a PIN opens straight away, as after a sign-in.
                SignInHandoff.skipsSingleProfilePicker = true
                navigate(TvChangeAccountNavigation.Picker(switched = switched))
            }
        }
    }

    /**
     * "Add an account" on [serverId]. On the active server while an account
     * is open, sign-in opens over this screen and that account stays open
     * until another signs in; elsewhere the server becomes active with no
     * account first.
     */
    fun onAddAccount(serverId: String) {
        val active = serverRegistry.activeAccount.value
        if (serverRegistry.activeServerId.value == serverId && active != null && !active.needsSignIn) {
            navigate(TvChangeAccountNavigation.SignIn(username = null, switched = false))
            return
        }
        change {
            val switched = !isActive(serverId, null)
            if (activate(serverId, accountId = null)) {
                navigate(TvChangeAccountNavigation.SignIn(username = null, switched = switched))
            }
        }
    }

    fun onAddServer() = navigate(TvChangeAccountNavigation.AddServer)

    fun onNavigationConsumed() {
        _uiState.update { it.copy(navigation = null) }
    }

    private fun navigate(navigation: TvChangeAccountNavigation) {
        _uiState.update { it.copy(navigation = navigation, changeFailed = false) }
    }

    private fun change(block: suspend () -> Unit) {
        if (changeJob?.isActive == true) return
        changeJob = viewModelScope.launch { block() }
    }

    private fun isActive(serverId: String, accountId: String?): Boolean =
        serverRegistry.activeServerId.value == serverId && serverRegistry.activeAccountId.value == accountId

    /**
     * Makes [accountId] on [serverId] the active one (null: the server with no
     * account, where a new sign-in starts). The departing profile's caches,
     * Watch Next included, go first; the arriving account's remembered
     * profile is cleared, so its picker asks rather than reopening it.
     */
    private suspend fun activate(serverId: String, accountId: String?): Boolean {
        if (isActive(serverId, accountId)) return true
        try {
            profileReset.clearProfileCaches()
            authRepository.switchToAccount(serverId, accountId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            _uiState.update { it.copy(changeFailed = true) }
            return false
        }
        if (!isActive(serverId, accountId)) {
            _uiState.update { it.copy(changeFailed = true) }
            return false
        }
        if (accountId != null) {
            try {
                profileReset.clearActiveProfile()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The picker's choice replaces it anyway.
            }
        }
        return true
    }
}
