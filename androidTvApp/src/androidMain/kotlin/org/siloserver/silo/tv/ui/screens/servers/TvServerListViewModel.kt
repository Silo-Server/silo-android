package org.siloserver.silo.tv.ui.screens.servers

import org.siloserver.silo.network.localroute.LocalRoute
import org.siloserver.silo.network.localroute.LocalRoutes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.model.server.ServerEntry
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TvServerListUiState(
    val servers: List<ServerEntry> = emptyList(),
    val activeId: String? = null,
    val pendingSwitchToId: String? = null,
    val switchedTo: TvServerSwitchDestination? = null,
    /** Set once when removing a server failed to save; cleared by [onRemoveFailedConsumed]. */
    val removeFailed: Boolean = false,
    /** The server whose requests use its local route right now, if any. */
    val onLocalNetworkId: String? = null,
)

/**
 * TV-side counterpart of [org.siloserver.silo.android.ui.screens.servers.ServerListViewModel].
 * Shares the same wire-up against [ServerRegistry]; only the
 * presentation differs.
 */
class TvServerListViewModel(
    private val serverRegistry: ServerRegistry,
    private val authRepository: AuthRepository,
    private val resolver: TvServerResolver,
    private val localRoute: StateFlow<LocalRoute?> = LocalRoutes.state.active,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TvServerListUiState())
    val uiState: StateFlow<TvServerListUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { authRepository.refreshActiveServerName() }
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
    }

    fun onSelect(serverId: String) {
        if (_uiState.value.activeId == serverId) return
        _uiState.update { it.copy(pendingSwitchToId = serverId) }
        viewModelScope.launch {
            // Switch the registry + token scope and probe the target server.s contract.
            authRepository.switchToServer(serverId)

            // Land on the deepest screen the new server's stored credentials
            // can reach — preserves the signed-in user when tokens are present.
            val destination = resolver.activeDestination()

            _uiState.update {
                it.copy(pendingSwitchToId = null, switchedTo = destination)
            }
        }
    }

    fun onSwitchConsumed() {
        _uiState.update { it.copy(switchedTo = null) }
    }

    fun onRemoveFailedConsumed() {
        _uiState.update { it.copy(removeFailed = false) }
    }

    fun onRemove(serverId: String) {
        viewModelScope.launch {
            when (val removal = resolver.remove(serverId)) {
                TvServerResolver.Removal.Failed -> _uiState.update { it.copy(removeFailed = true) }
                // Removing a *non-active* server is a passive list edit — the
                // shell behind us is unaffected, so leave navigation as is.
                // Removing the active one is a session change: the navigator
                // must tear down the shell still showing the removed server.
                is TvServerResolver.Removal.Removed ->
                    removal.destination?.let { destination -> _uiState.update { it.copy(switchedTo = destination) } }
            }
        }
    }
}
