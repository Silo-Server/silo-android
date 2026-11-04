package org.siloserver.silo.android.ui.screens.servers

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.siloserver.silo.android.auth.ActiveProfileReset
import org.siloserver.silo.android.push.AndroidPushRegistrar
import org.siloserver.silo.android.push.PushRemoval
import org.siloserver.silo.android.push.tolerateFailure
import org.siloserver.silo.android.ui.navigation.hasOtherSession
import org.siloserver.silo.common.downloads.DownloadSummary
import org.siloserver.silo.model.server.AccountEntry
import org.siloserver.silo.model.server.ServerEntry
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.localroute.LocalRoute
import org.siloserver.silo.network.localroute.LocalRoutes
import org.siloserver.silo.repository.AuthRepository

/**
 * Where to land after a successful server switch. The list view emits this
 * once the new server's tokens are loaded so the navigator can route to the
 * deepest screen the credentials allow — Login only when the target server
 * has no stored auth.
 */
enum class ServerSwitchDestination { Home, ProfileSelection, ChangeAccount, Login, ServerSetup }

data class ServerListUiState(
    val servers: List<ServerEntry> = emptyList(),
    val activeId: String? = null,
    val pendingSwitchToId: String? = null,
    val switchedTo: ServerSwitchDestination? = null,
    /** The server whose requests use its local route right now, if any. */
    val onLocalNetworkId: String? = null,
    /** Every saved account (Settings › Accounts, spec §4.5). */
    val accounts: List<AccountEntry> = emptyList(),
    val activeAccountId: String? = null,
    /** Each account's downloads, by [AccountEntry.localAccountId]; missing until read. */
    val downloads: Map<String, DownloadSummary> = emptyMap(),
    /** A sign-out or removal is running. */
    val isWorking: Boolean = false,
    /** The server a removal stopped on because one of its accounts didn't sign out. */
    val removeFailedServerId: String? = null,
) {
    /** [serverId]'s accounts in the order they were added. */
    fun accountsOn(serverId: String): List<AccountEntry> =
        accounts.filter { it.serverId == serverId }.sortedBy { it.addedAtEpochMs }
}

/**
 * Drives [AccountsScreen] and [AccountDetailScreen]. Owns no auth state itself — every action
 * round-trips through [ServerRegistry] / [TokenManager].
 *
 * Server entries are surfaced sorted with the active one first, then
 * most-recently-used; the screen renders this list directly.
 */
class ServerListViewModel(
    private val serverRegistry: ServerRegistry,
    private val tokenManager: TokenManager,
    private val authRepository: AuthRepository,
    private val activeProfileReset: ActiveProfileReset,
    /** Removes this device's push registration on a server ([AndroidPushRegistrar.unregisterDevice]). */
    private val unregisterPush: suspend (serverEntryId: String) -> PushRemoval? = { null },
    /** Undoes that removal when the server then stays saved ([AndroidPushRegistrar.restoreRegistration]). */
    private val restorePush: suspend (PushRemoval) -> Unit = {},
    private val localRoute: StateFlow<LocalRoute?> = LocalRoutes.state.active,
    /** Signs out of one account, active or not ([org.siloserver.silo.android.auth.SignOutTeardown.signOutAccount]). */
    private val signOutAccount: suspend (serverId: String, accountId: String) -> Boolean = { _, _ -> true },
    private val downloadSummary: suspend (serverId: String, accountId: String) -> DownloadSummary? = { _, _ -> null },
    /** Pushes settings still waiting to sync while the account they were made on is active. */
    private val flushPendingSettings: suspend () -> Unit = {},
    /** Drops the per-profile caches once another account is active, as a server switch does. */
    private val clearProfileCaches: () -> Unit = {},
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServerListUiState())
    val uiState: StateFlow<ServerListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                serverRegistry.entries,
                serverRegistry.activeServerId,
                localRoute,
            ) { entries, activeId, route ->
                val sorted = entries.sortedWith(
                    compareByDescending<ServerEntry> { it.id == activeId }
                        .thenByDescending { it.lastUsedAtEpochMs },
                )
                Triple(sorted, activeId, route?.serverId?.takeIf { it == activeId })
            }.collect { (sorted, activeId, onLocalNetworkId) ->
                _uiState.update { it.copy(servers = sorted, activeId = activeId, onLocalNetworkId = onLocalNetworkId) }
            }
        }
        viewModelScope.launch {
            combine(serverRegistry.accounts, serverRegistry.activeAccountId) { accounts, active -> accounts to active }
                .collect { (accounts, active) ->
                    _uiState.update { it.copy(accounts = accounts, activeAccountId = active) }
                }
        }
        viewModelScope.launch {
            serverRegistry.accounts.collectLatest { accounts ->
                val summaries = accounts.mapNotNull { account ->
                    runCatching { downloadSummary(account.serverId, account.localAccountId) }.getOrNull()
                        ?.let { account.localAccountId to it }
                }.toMap()
                _uiState.update { it.copy(downloads = summaries) }
            }
        }
    }

    /**
     * "Add an account on {server}": that server becomes active with no
     * account, so the sign-in that follows adds one. Its other accounts stay
     * saved.
     */
    fun onAddAccount(serverId: String) = switchForSignIn(serverId, accountId = null)

    /** "Sign in again" on an account whose credentials expired: sign-in with its username filled in. */
    fun onSignInAgain(accountId: String) {
        val account = _uiState.value.accounts.firstOrNull { it.localAccountId == accountId } ?: return
        switchForSignIn(account.serverId, accountId)
    }

    private fun switchForSignIn(serverId: String, accountId: String?) {
        if (_uiState.value.isWorking) return
        _uiState.update { it.copy(isWorking = true) }
        viewModelScope.launch {
            try {
                val switched = switchActiveAccount(
                    serverRegistry, authRepository, serverId, accountId, flushPendingSettings, clearProfileCaches,
                )
                if (!switched) return@launch
                _uiState.update { it.copy(switchedTo = ServerSwitchDestination.Login) }
            } finally {
                _uiState.update { it.copy(isWorking = false) }
            }
        }
    }

    /**
     * Sign out of [accountId] (spec §4.4): its downloads, preferences and
     * tokens go. Signing out of the active account leaves for Change Account
     * while another account has a session, else sign-in; any other account
     * just leaves the list.
     */
    fun onSignOutAccount(accountId: String) {
        val account = _uiState.value.accounts.firstOrNull { it.localAccountId == accountId } ?: return
        if (_uiState.value.isWorking) return
        _uiState.update { it.copy(isWorking = true) }
        viewModelScope.launch {
            try {
                val wasActive = serverRegistry.activeAccountId.value == accountId
                if (!signOutAccount(account.serverId, accountId) || !wasActive) return@launch
                _uiState.update { it.copy(switchedTo = destinationWithoutAccount()) }
            } finally {
                _uiState.update { it.copy(isWorking = false) }
            }
        }
    }

    /** Where the active account's sign-out leaves the person: see [org.siloserver.silo.android.ui.navigation.routeAfterSignOut]. */
    private fun destinationWithoutAccount(): ServerSwitchDestination = when {
        serverRegistry.entries.value.isEmpty() -> ServerSwitchDestination.ServerSetup
        serverRegistry.hasOtherSession() -> ServerSwitchDestination.ChangeAccount
        else -> ServerSwitchDestination.Login
    }

    fun onSelect(serverId: String) {
        if (_uiState.value.activeId == serverId) {
            // Already active — nothing to do, don't fire a navigation event.
            return
        }
        _uiState.update { it.copy(pendingSwitchToId = serverId) }
        viewModelScope.launch {
            // Switch the registry + token scope (so the destination screen never
            // reads stale tokens) and probe the target server.s contract.
            authRepository.switchToServer(serverId)

            // Route to the deepest screen the new server's stored credentials
            // can populate. Mirrors MainActivity.resolveStartDestination so a
            // user with valid tokens for the target server skips Login + the
            // profile picker entirely.
            val accessToken = tokenManager.getAccessToken()
            val activeEntry = serverRegistry.activeEntry.value
            val profileId = activeEntry?.profileId ?: tokenManager.getProfileId()
            val destination = when {
                accessToken.isNullOrBlank() -> ServerSwitchDestination.Login
                profileId.isNullOrBlank() -> ServerSwitchDestination.ProfileSelection
                // Profile Selection asks on a server switch as it does at
                // launch, so a protected profile there needs its PIN. Cleared
                // before navigating, so the picker loads under the new server.
                activeProfileReset.clearIfSelectionRequired() -> ServerSwitchDestination.ProfileSelection
                else -> ServerSwitchDestination.Home
            }

            _uiState.update {
                it.copy(pendingSwitchToId = null, switchedTo = destination)
            }
        }
    }

    fun onSwitchConsumed() {
        _uiState.update { it.copy(switchedTo = null) }
    }

    fun onRename(serverId: String, newName: String) {
        viewModelScope.launch {
            serverRegistry.rename(serverId, newName.takeIf { it.isNotBlank() })
        }
    }

    fun onRemove(serverId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isWorking = true, removeFailedServerId = null) }
            try {
                if (!removeServer(serverId)) _uiState.update { it.copy(removeFailedServerId = serverId) }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                Log.w("ServerListViewModel", "server removal failed", failure)
                _uiState.update { it.copy(removeFailedServerId = serverId) }
            } finally {
                _uiState.update { it.copy(isWorking = false) }
            }
        }
    }

    fun onRemoveFailureConsumed() {
        _uiState.update { it.copy(removeFailedServerId = null) }
    }

    /**
     * Remove a server (spec §4.4): sign out of every account on it (each
     * session ends and its downloads go), then forget the server. False, with
     * the server still saved, when an account didn't sign out: removing the
     * server anyway would drop that account's credentials with its session
     * still live and its push registration still in place.
     */
    private suspend fun removeServer(serverId: String): Boolean {
        val wasActive = serverRegistry.activeServerId.value == serverId
        // Inactive accounts first, so the active one's sign-out is the last
        // identity change on this server.
        val accounts = serverRegistry.accounts.value
            .filter { it.serverId == serverId }
            .sortedBy { serverRegistry.activeAccountId.value == it.localAccountId }
        for (account in accounts) {
            val signedOut = try {
                signOutAccount(serverId, account.localAccountId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            if (!signedOut) return false
        }
        // Before removal wipes the server's credentials, which a server
        // without the retirement route needs; a retirement it has not
        // answered is sent again later without them. Failing to remove the
        // registration never stops the removal; cancellation still does,
        // before anything is removed.
        var removal: PushRemoval? = null
        tolerateFailure { removal = unregisterPush(serverId) }
        try {
            serverRegistry.remove(serverId)
        } catch (failure: Exception) {
            // Not removed, so it keeps its login, and its pushes with it.
            if (failure !is CancellationException) removal?.let { tolerateFailure { restorePush(it) } }
            throw failure
        }

        // Removing the ACTIVE server promotes the next-MRU entry inside
        // the registry, which never probes: without this the promoted
        // server keeps whatever contract verdict an older build stored
        // (or UNKNOWN). Route it through the same durable switch path as
        // every other promotion: the registry already points at it, so
        // switchToServer is idempotent there, and its bounded probe hands
        // a replacement to the repository's process-lifetime background
        // scope — popping this screen cancels viewModelScope, which must
        // not strand the promoted server on a stale verdict.
        val promotedId = serverRegistry.activeServerId.value
        if (wasActive && promotedId != null) {
            authRepository.switchToServer(promotedId)
            // A promotion is a server switch too: when Profile Selection
            // asks, the promoted server's saved profile needs choosing
            // (and its PIN) again, so leave for Who's Watching rather
            // than back to a shell that would reopen it. Without a
            // saved profile there is nothing to protect.
            val hasProfile = !tokenManager.getAccessToken().isNullOrBlank() &&
                !(serverRegistry.activeEntry.value?.profileId ?: tokenManager.getProfileId()).isNullOrBlank()
            val destination = when {
                tokenManager.getAccessToken().isNullOrBlank() -> destinationWithoutAccount()
                !hasProfile -> ServerSwitchDestination.ProfileSelection
                activeProfileReset.clearIfSelectionRequired() -> ServerSwitchDestination.ProfileSelection
                else -> ServerSwitchDestination.Home
            }
            _uiState.update { it.copy(switchedTo = destination) }
        } else if (wasActive) {
            // The last server is gone.
            _uiState.update { it.copy(switchedTo = ServerSwitchDestination.ServerSetup) }
        }
        return true
    }
}
