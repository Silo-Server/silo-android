package org.siloserver.silo.tv.ui.screens.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.model.server.ServerEntry
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TvServerSwitchDestination { Home, ProfileSelection, Login }

data class TvServerListUiState(
    val servers: List<ServerEntry> = emptyList(),
    val activeId: String? = null,
    val pendingSwitchToId: String? = null,
    val switchedTo: TvServerSwitchDestination? = null,
    /**
     * Set once when the *active* server was removed and no server remains to
     * fall back to — there is nothing to sign into, so the navigator must send
     * the user back to server setup. One-shot; cleared by [onServerSetupConsumed].
     */
    val needsServerSetup: Boolean = false,
)

/**
 * TV-side counterpart of [org.siloserver.silo.android.ui.screens.servers.ServerListViewModel].
 * Shares the same wire-up against [ServerRegistry] / [TokenManager]; only the
 * presentation differs.
 */
class TvServerListViewModel(
    private val serverRegistry: ServerRegistry,
    private val tokenManager: TokenManager,
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TvServerListUiState())
    val uiState: StateFlow<TvServerListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { authRepository.refreshActiveServerName() }
        viewModelScope.launch {
            combine(
                serverRegistry.entries,
                serverRegistry.activeServerId,
            ) { entries, activeId ->
                val sorted = entries.sortedWith(
                    compareByDescending<ServerEntry> { it.id == activeId }
                        .thenByDescending { it.lastUsedAtEpochMs },
                )
                sorted to activeId
            }.collect { (sorted, activeId) ->
                _uiState.update { it.copy(servers = sorted, activeId = activeId) }
            }
        }
    }

    fun onSelect(serverId: String) {
        if (_uiState.value.activeId == serverId) return
        _uiState.update { it.copy(pendingSwitchToId = serverId) }
        viewModelScope.launch {
            serverRegistry.switchTo(serverId)
            tokenManager.switchActiveServer(serverId)

            // Land on the deepest screen the new server's stored credentials
            // can reach — preserves the signed-in user when tokens are present.
            val accessToken = tokenManager.getAccessToken()
            val activeEntry = serverRegistry.activeEntry.value
            val profileId = activeEntry?.profileId ?: tokenManager.getProfileId()
            val destination = when {
                accessToken.isNullOrBlank() -> TvServerSwitchDestination.Login
                profileId.isNullOrBlank() -> TvServerSwitchDestination.ProfileSelection
                else -> TvServerSwitchDestination.Home
            }

            _uiState.update {
                it.copy(pendingSwitchToId = null, switchedTo = destination)
            }
        }
    }

    fun onSwitchConsumed() {
        _uiState.update { it.copy(switchedTo = null) }
    }

    fun onServerSetupConsumed() {
        _uiState.update { it.copy(needsServerSetup = false) }
    }

    fun onRemove(serverId: String) {
        viewModelScope.launch {
            val wasActive = serverRegistry.activeServerId.value == serverId
            serverRegistry.remove(serverId)

            // Removing a *non-active* server is a passive list edit — the shell
            // behind us is unaffected, so leave navigation exactly as before.
            if (!wasActive) return@launch

            // Removing the ACTIVE server is a session change. The registry has
            // just promoted the next-MRU server (or none) and wiped the removed
            // server's tokens; the Main shell behind this list is still rendering
            // the removed server. Re-resolve like onSelect so the navigator tears
            // that shell down instead of leaking API calls onto whatever server
            // got promoted (or a now-empty baseUrl).
            val promotedId = serverRegistry.activeServerId.value
            if (promotedId == null) {
                // No server left to sign into — route back to server setup.
                _uiState.update { it.copy(needsServerSetup = true) }
                return@launch
            }

            tokenManager.switchActiveServer(promotedId)

            // Land on the deepest screen the promoted server's stored
            // credentials can reach — preserves that server's session if tokens
            // are present, otherwise falls back to Login.
            val accessToken = tokenManager.getAccessToken()
            val activeEntry = serverRegistry.activeEntry.value
            val profileId = activeEntry?.profileId ?: tokenManager.getProfileId()
            val destination = when {
                accessToken.isNullOrBlank() -> TvServerSwitchDestination.Login
                profileId.isNullOrBlank() -> TvServerSwitchDestination.ProfileSelection
                else -> TvServerSwitchDestination.Home
            }

            _uiState.update { it.copy(switchedTo = destination) }
        }
    }

}
