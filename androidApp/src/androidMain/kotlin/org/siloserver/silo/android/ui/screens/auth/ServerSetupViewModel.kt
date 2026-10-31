package org.siloserver.silo.android.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.common.discovery.DiscoveredServer
import org.siloserver.silo.common.network.CleartextConsentStore
import org.siloserver.silo.common.network.ServerSetupScheme
import org.siloserver.silo.common.network.ServerSetupValidationException
import org.siloserver.silo.common.network.buildServerSetupCandidateUrls
import org.siloserver.silo.common.network.serverSetupUsesCleartext
import org.siloserver.silo.common.network.cleartextOrigin
import org.siloserver.silo.model.auth.SetupStatusResponse
import org.siloserver.silo.model.auth.SignupStatusResponse
import org.siloserver.silo.model.server.ServerContract
import org.siloserver.silo.network.apiv2.ApiV2ProbeResult
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.AuthRepository
import org.siloserver.silo.repository.CONTRACT_PROVEN_BY_V2_RESPONSE
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ServerSetupUiState(
    val serverUrl: String = "",
    val selectedScheme: ServerSetupScheme = ServerSetupScheme.Auto,
    val port: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    /** Destination after successful server validation. */
    val navigateTo: ServerSetupDestination? = null,
    val pendingCleartextUrl: String? = null,
    /** The found server being connected to, so its row can show progress while [isLoading]. */
    val connectingServerUrl: String? = null,
    /** A failure connecting to a found server; shown beside the found list, not on the address field. */
    val discoveryError: String? = null,
) {
    /** True when the entered address will connect over unencrypted HTTP.
     *  Informational only — does not block connecting to LAN/IP servers. */
    val usesCleartext: Boolean
        get() = serverSetupUsesCleartext(serverUrl, selectedScheme, port)
}

sealed class ServerSetupDestination {
    /** Server needs first-time setup (admin creation). */
    data object Setup : ServerSetupDestination()

    /** Server is ready -- go to login. Signup may or may not be available. */
    data class Login(val signupEnabled: Boolean) : ServerSetupDestination()

    /** A saved server this device is still signed in to: straight to its profiles. */
    data object Profiles : ServerSetupDestination()
}

class ServerSetupViewModel(
    private val authRepository: AuthRepository,
    private val cleartextConsentStore: CleartextConsentStore,
    private val getSetupStatus: suspend (String) -> ApiResult<SetupStatusResponse> = {
        authRepository.getSetupStatus(it)
    },
    private val getSignupStatus: suspend (String) -> ApiResult<SignupStatusResponse> = {
        authRepository.getSignupStatus(it)
    },
    /** v2 contract probe for a candidate; null when no probe is wired in. */
    private val probeContract: suspend (String) -> ApiV2ProbeResult? = {
        authRepository.probeServerContract(it)
    },
    private val candidateUrls: (ServerSetupUiState) -> List<String> = {
        buildServerSetupCandidateUrls(it.serverUrl, it.selectedScheme, it.port)
    },
    /** Whether the now-active server already has a signed-in session, as when a saved server is picked from Recent. */
    private val hasSession: suspend () -> Boolean = { false },
) : ViewModel() {

    private val _uiState = MutableStateFlow(ServerSetupUiState())
    val uiState: StateFlow<ServerSetupUiState> = _uiState.asStateFlow()
    private var pendingCleartextConnection: PendingCleartextConnection? = null

    /** Set by [prefill]: the saved URL must not replace an address the caller supplied. */
    private var prefilled = false

    init {
        // Pre-populate with previously saved server URL, if any.
        viewModelScope.launch {
            val saved = authRepository.getServerUrl()
            if (saved.isNotBlank() && !prefilled) {
                _uiState.update { it.copy(serverUrl = saved) }
            }
        }
    }

    /** Start from [url] (an app link's server) instead of the saved address. */
    fun prefill(url: String) {
        if (prefilled) return
        prefilled = true
        _uiState.update { it.copy(serverUrl = url, error = null) }
    }

    fun onServerUrlChanged(url: String) {
        _uiState.update { it.copy(serverUrl = url, error = null, discoveryError = null) }
    }

    fun onSchemeSelected(scheme: ServerSetupScheme) {
        _uiState.update { it.copy(selectedScheme = scheme, error = null) }
    }

    fun onPortChanged(port: String) {
        _uiState.update { it.copy(port = port, error = null) }
    }

    /**
     * Fills in a saved server's address. Its URL already names the scheme and
     * port, so a protocol or port chosen for an earlier attempt must not
     * override them.
     */
    fun useRecent(url: String) {
        _uiState.update { it.copy(serverUrl = url, selectedScheme = ServerSetupScheme.Auto, port = "", error = null) }
    }

    /**
     * Validates the entered server URL:
     * 1. Build candidate URLs from the host, protocol, and optional port.
     * 2. Try each candidate until setup status responds.
     * 3. Otherwise check signup status and navigate to login.
     */
    fun onConnectClick() {
        if (_uiState.value.isLoading) return
        val current = _uiState.value
        val candidates = try {
            candidateUrls(current)
        } catch (error: ServerSetupValidationException) {
            _uiState.update { it.copy(error = error.message) }
            return
        }

        connect(candidates, found = null)
    }

    /**
     * Connects to a server found on the local network exactly as if its
     * address had been typed: the same probes, and the same plain-HTTP
     * consent before anything is saved.
     */
    fun connectTo(server: DiscoveredServer) {
        if (_uiState.value.isLoading) return
        connect(listOf(server.url), found = server)
    }

    private fun connect(candidates: List<String>, found: DiscoveredServer?) {
        viewModelScope.launch {
            pendingCleartextConnection = null
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    discoveryError = null,
                    pendingCleartextUrl = null,
                    connectingServerUrl = found?.url,
                )
            }

            var lastError: String? = null
            for (candidate in candidates) {
                // One contract probe per established connection: a v1-only
                // server is reported as update-server, never retried on v1.
                val probe = probeContract(candidate)
                if (probe is ApiV2ProbeResult.UpdateServer) {
                    fail(ServerContract.UPDATE_REQUIRED_MESSAGE, found)
                    return@launch
                }
                // Anything else the probe said (V2, a transient failure, or a
                // timeout) is superseded by the v2 setup call succeeding below,
                // which is proof of v2 on its own; a Failure must not be handed
                // on as UNKNOWN, or a stale UPDATE_REQUIRED would survive.
                val contract = CONTRACT_PROVEN_BY_V2_RESPONSE
                when (val setupResult = getSetupStatus(candidate)) {
                    is ApiResult.Success -> {
                        if (setupResult.data.needsSetup) {
                            handleSuccessfulConnection(candidate, ServerSetupDestination.Setup, contract)
                            return@launch
                        }
                    }

                    is ApiResult.Error -> {
                        lastError = setupResult.message
                        continue
                    }

                    is ApiResult.NetworkError -> {
                        lastError = null
                        continue
                    }
                }

                val signupEnabled = when (val signupResult = getSignupStatus(candidate)) {
                    is ApiResult.Success -> signupResult.data.enabled
                    else -> false // If we can't determine, default to no signup.
                }

                handleSuccessfulConnection(
                    candidate,
                    ServerSetupDestination.Login(signupEnabled = signupEnabled),
                    contract,
                )
                return@launch
            }

            fail(
                lastError
                    ?.takeIf { message -> message.isNotBlank() }
                    ?.let { message -> "Could not connect: $message" }
                    ?: "Could not reach a Silo server at that address.",
                found,
            )
        }
    }

    private fun fail(message: String, found: DiscoveredServer?) {
        _uiState.update {
            if (found == null) {
                it.copy(isLoading = false, error = message)
            } else {
                it.copy(isLoading = false, discoveryError = message)
            }
        }
    }

    fun confirmCleartextConnection() {
        if (_uiState.value.isLoading) return
        val pending = pendingCleartextConnection ?: return
        _uiState.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            cleartextConsentStore.approve(pending.origin)
            if (pendingCleartextConnection !== pending) return@launch
            pendingCleartextConnection = null
            persistAndNavigate(pending.serverUrl, pending.destination, pending.contract)
        }
    }

    fun cancelCleartextConnection() {
        pendingCleartextConnection = null
        _uiState.update {
            it.copy(pendingCleartextUrl = null, isLoading = false)
        }
    }

    /** Resets navigation state after the UI has consumed the event. */
    fun onNavigationConsumed() {
        _uiState.update { it.copy(navigateTo = null) }
    }

    private suspend fun handleSuccessfulConnection(
        serverUrl: String,
        destination: ServerSetupDestination,
        contract: ServerContract?,
    ) {
        if (serverUrl.startsWith("http://", ignoreCase = true)) {
            val origin = cleartextOrigin(serverUrl)
            if (origin == null) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Could not safely identify this HTTP server.",
                        pendingCleartextUrl = null,
                        navigateTo = null,
                    )
                }
                return
            }
            if (cleartextConsentStore.isApproved(origin)) {
                persistAndNavigate(serverUrl, destination, contract)
                return
            }
            pendingCleartextConnection = PendingCleartextConnection(
                serverUrl = serverUrl,
                origin = origin,
                destination = destination,
                contract = contract,
            )
            _uiState.update {
                it.copy(
                    isLoading = false,
                    pendingCleartextUrl = origin,
                    navigateTo = null,
                )
            }
            return
        }
        persistAndNavigate(serverUrl, destination, contract)
    }

    private suspend fun persistAndNavigate(
        serverUrl: String,
        destination: ServerSetupDestination,
        contract: ServerContract?,
    ) {
        authRepository.setServerUrl(serverUrl, contract)
        // A saved server that is still signed in goes straight to its
        // profiles; only a server without a session needs sign-in.
        val next = if (destination is ServerSetupDestination.Login && hasSession()) {
            ServerSetupDestination.Profiles
        } else {
            destination
        }
        _uiState.update {
            it.copy(
                isLoading = false,
                pendingCleartextUrl = null,
                navigateTo = next,
            )
        }
    }
}

private data class PendingCleartextConnection(
    val serverUrl: String,
    val origin: String,
    val destination: ServerSetupDestination,
    val contract: ServerContract?,
)
