package org.siloserver.silo.tv.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.common.network.CleartextConsentStore
import org.siloserver.silo.common.network.cleartextOrigin
import org.siloserver.silo.model.auth.SetupStatusResponse
import org.siloserver.silo.model.auth.SignupStatusResponse
import org.siloserver.silo.model.server.ServerContract
import org.siloserver.silo.network.apiv2.ApiV2ProbeResult
import org.siloserver.silo.repository.CONTRACT_PROVEN_BY_V2_RESPONSE
import org.siloserver.silo.network.AndroidServerRegistry
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TvServerSetupUiState(
    val serverUrl: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    /** Destination after a successful server probe; consumed by the screen. */
    val navigateTo: TvServerSetupDestination? = null,
    val pendingCleartextUrl: String? = null,
) {
    /** True when the entered address will connect over unencrypted HTTP.
     *  Informational only — does not block LAN/IP connections. */
    val usesCleartext: Boolean get() = serverSetupUsesCleartext(serverUrl)
}

/**
 * Where the URL probe lands the user. Mirrors the phone's
 * `ServerSetupDestination` so the TV flow matches the gold-standard logic:
 *
 *  - [Setup]   the server has no admin yet → first-admin creation form.
 *  - [Login]   the server is ready → sign-in. `signupEnabled` decides whether
 *              the login screen offers a "Create account" affordance.
 */
sealed class TvServerSetupDestination {
    data object Setup : TvServerSetupDestination()
    data class Login(val signupEnabled: Boolean) : TvServerSetupDestination()
}

internal sealed class TvServerSetupProbeResult {
    data class Success(
        val serverUrl: String,
        val destination: TvServerSetupDestination,
        /** Verdict of the v2 contract probe, or null when no probe ran / it failed without a verdict. */
        val contract: ServerContract? = null,
    ) : TvServerSetupProbeResult()

    data class Failure(
        val message: String,
        val serverUrl: String? = null,
    ) : TvServerSetupProbeResult()
}

/**
 * TV variant of the phone app's `ServerSetupViewModel`.
 *
 * Probes the entered server and routes to the matching flow:
 *  1. needs setup → first-admin creation ([TvSetupScreen]).
 *  2. set up → login ([TvLoginScreen]); signup availability is forwarded so
 *     the login screen can surface the invite-code signup ([TvSignupScreen]).
 *
 * Parity note: earlier TV builds intentionally dead-ended un-set-up servers
 * ("complete setup on a phone first"). That restriction is lifted — a TV can
 * now bootstrap a fresh server and join via invite, matching the phone.
 */
class TvServerSetupViewModel(
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(TvServerSetupUiState())
    val uiState: StateFlow<TvServerSetupUiState> = _uiState.asStateFlow()
    private var pendingCleartextConnection: PendingTvCleartextConnection? = null

    init {
        viewModelScope.launch {
            val saved = authRepository.getServerUrl()
            // Skip the shared TokenManager's `http://localhost:8090` placeholder
            // default — a TV device can't reach its own localhost, and showing
            // that URL forces every user to delete it before typing their real
            // server. The placeholder prop on the TextField gives better UX.
            if (saved.isNotBlank() && saved != LOCALHOST_PLACEHOLDER) {
                _uiState.update { it.copy(serverUrl = saved) }
            }
        }
    }

    private companion object {
        private const val LOCALHOST_PLACEHOLDER = "http://localhost:8090"
    }

    fun onServerUrlChanged(url: String) {
        _uiState.update { it.copy(serverUrl = url, error = null) }
    }

    fun onConnectClick() {
        if (_uiState.value.isLoading) return
        val raw = _uiState.value.serverUrl.trim()
        if (raw.isBlank()) {
            _uiState.update { it.copy(error = "Enter a server URL") }
            return
        }
        val candidates = serverSetupUrlProbeCandidates(raw)

        viewModelScope.launch {
            pendingCleartextConnection = null
            _uiState.update {
                it.copy(isLoading = true, error = null, pendingCleartextUrl = null)
            }

            when (val result = probeTvServerSetupCandidates(
                candidates = candidates,
                getSetupStatus = getSetupStatus,
                getSignupStatus = getSignupStatus,
                probeContract = probeContract,
            )) {
                is TvServerSetupProbeResult.Success -> {
                    handleSuccessfulConnection(result.serverUrl, result.destination, result.contract)
                }
                is TvServerSetupProbeResult.Failure -> {
                    _uiState.update {
                        it.copy(
                            serverUrl = result.serverUrl ?: it.serverUrl,
                            isLoading = false,
                            error = result.message,
                        )
                    }
                }
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

    fun onNavigationConsumed() {
        _uiState.update { it.copy(navigateTo = null) }
    }

    private suspend fun handleSuccessfulConnection(
        serverUrl: String,
        destination: TvServerSetupDestination,
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
            pendingCleartextConnection = PendingTvCleartextConnection(
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
        destination: TvServerSetupDestination,
        contract: ServerContract?,
    ) {
        authRepository.setServerUrl(serverUrl, contract)
        _uiState.update {
            it.copy(
                serverUrl = serverUrl,
                isLoading = false,
                pendingCleartextUrl = null,
                navigateTo = destination,
            )
        }
    }
}

private data class PendingTvCleartextConnection(
    val serverUrl: String,
    val origin: String,
    val destination: TvServerSetupDestination,
    val contract: ServerContract?,
)

internal suspend fun probeTvServerSetupCandidates(
    candidates: List<String>,
    getSetupStatus: suspend (String) -> ApiResult<SetupStatusResponse>,
    getSignupStatus: suspend (String) -> ApiResult<SignupStatusResponse>,
    probeContract: suspend (String) -> ApiV2ProbeResult? = { null },
): TvServerSetupProbeResult {
    for ((index, candidate) in candidates.withIndex()) {
        val isLastCandidate = index == candidates.lastIndex

        // One contract probe per established connection: a v1-only server is
        // the explicit update-server state, never a v1 retry.
        val probe = probeContract(candidate)
        if (probe is ApiV2ProbeResult.UpdateServer) {
            return TvServerSetupProbeResult.Failure(
                serverUrl = candidate,
                message = ServerContract.UPDATE_REQUIRED_MESSAGE,
            )
        }
        // Anything else the probe said (V2, a transient failure, or a timeout)
        // is superseded by the v2 setup call succeeding below, which is proof
        // of v2 on its own; a Failure must not be handed on as UNKNOWN, or a
        // stale UPDATE_REQUIRED would survive.
        val contract = CONTRACT_PROVEN_BY_V2_RESPONSE

        when (val result = getSetupStatus(candidate)) {
            is ApiResult.Success -> {
                if (result.data.needsSetup) {
                    return TvServerSetupProbeResult.Success(
                        serverUrl = candidate,
                        destination = TvServerSetupDestination.Setup,
                        contract = contract,
                    )
                }
            }
            is ApiResult.Error -> {
                if (!isLastCandidate) continue
                return TvServerSetupProbeResult.Failure(
                    serverUrl = candidate,
                    message = "Could not reach server: ${result.message}",
                )
            }
            is ApiResult.NetworkError -> {
                if (!isLastCandidate) continue
                return TvServerSetupProbeResult.Failure(
                    message = "Network error. Check the URL and try again.",
                )
            }
        }

        val signupEnabled = when (val signupResult = getSignupStatus(candidate)) {
            is ApiResult.Success -> signupResult.data.enabled
            else -> false
        }
        return TvServerSetupProbeResult.Success(
            serverUrl = candidate,
            destination = TvServerSetupDestination.Login(signupEnabled = signupEnabled),
            contract = contract,
        )
    }

    return TvServerSetupProbeResult.Failure(
        message = "Network error. Check the URL and try again.",
    )
}

/**
 * True when the entered address will connect over unencrypted HTTP: the probe
 * resolves to a single `http://` candidate (only when the user explicitly
 * typed `http://`). Informational only — never blocks LAN/IP connections.
 */
internal fun serverSetupUsesCleartext(raw: String): Boolean {
    val candidates = serverSetupUrlProbeCandidates(raw)
    return candidates.size == 1 && candidates.first().startsWith("http://", ignoreCase = true)
}

internal fun serverSetupUrlProbeCandidates(raw: String): List<String> {
    val trimmed = raw.trim().trimEnd('/')
    if (trimmed.isBlank()) return emptyList()
    return if (trimmed.startsWith("http://", ignoreCase = true) ||
        trimmed.startsWith("https://", ignoreCase = true)
    ) {
        listOf(AndroidServerRegistry.normalizeUrl(trimmed))
    } else {
        listOf(
            AndroidServerRegistry.normalizeUrl("https://$trimmed"),
            AndroidServerRegistry.normalizeUrl("http://$trimmed"),
        )
    }
}
