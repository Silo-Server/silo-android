package org.siloserver.silo.android.ui.screens.servers

import org.siloserver.silo.network.localroute.LocalRoute
import org.siloserver.silo.network.localroute.LocalRoutes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.model.server.ServerEntry
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.repository.AuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.siloserver.silo.android.auth.ActiveProfileReset
import org.siloserver.silo.android.push.AndroidPushRegistrar
import org.siloserver.silo.android.push.PushRemoval
import org.siloserver.silo.android.push.tolerateFailure

/**
 * Where to land after a successful server switch. The list view emits this
 * once the new server's tokens are loaded so the navigator can route to the
 * deepest screen the credentials allow — Login only when the target server
 * has no stored auth.
 */
enum class ServerSwitchDestination { Home, ProfileSelection, Login }

data class ServerListUiState(
    val servers: List<ServerEntry> = emptyList(),
    val activeId: String? = null,
    val pendingSwitchToId: String? = null,
    val switchedTo: ServerSwitchDestination? = null,
    /** The server whose requests use its local route right now, if any. */
    val onLocalNetworkId: String? = null,
)

/**
 * Drives [ServerListScreen]. Owns no auth state itself — every action
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
            val wasActive = serverRegistry.activeServerId.value == serverId
            // While the server's credentials still exist: removal wipes them.
            // Failing to remove the registration never stops the removal;
            // cancellation still does, before anything is removed.
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
                if (hasProfile && activeProfileReset.clearIfSelectionRequired()) {
                    _uiState.update { it.copy(switchedTo = ServerSwitchDestination.ProfileSelection) }
                }
            }
        }
    }
}
