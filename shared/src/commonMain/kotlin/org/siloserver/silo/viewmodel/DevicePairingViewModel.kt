package org.siloserver.silo.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import org.siloserver.silo.model.auth.DeviceCodeFormat
import org.siloserver.silo.model.auth.DeviceLoginLookupResponse
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.SiloAuthUnavailableException
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.signedInEntries
import org.siloserver.silo.repository.DeviceLoginRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A saved, signed-in server a TV's code can be approved on. */
data class DeviceApprovalServer(
    val id: String,
    val url: String,
    val displayName: String,
    val isActive: Boolean = false,
)

/**
 * The servers "Sign in a TV" can approve on, and the credentials each call
 * runs with. Codes are unique only per server, so a typed code is looked up
 * on one chosen server, never on every saved server.
 */
interface DeviceApprovalServers {
    /** Signed-in servers, the active one first. */
    suspend fun list(): List<DeviceApprovalServer>

    /** The profileless account scope approvals on [server] run in. */
    fun scope(server: DeviceApprovalServer): AuthScopeSnapshot

    /**
     * The account the approval would sign the TV in as, when known. Read
     * before each lookup through [server]'s own scope, renewing its access
     * token first when it has expired or is about to: the approver refreshes
     * before looking up and approving (TV sign-in spec). Throws
     * [SiloAuthUnavailableException.PROVIDER_UNAVAILABLE] when that renewal
     * was refused because the sign-in provider couldn't re-check the session.
     */
    suspend fun accountName(server: DeviceApprovalServer): String? = null
}

/** Production [DeviceApprovalServers] over the saved-server registry. */
class RegistryDeviceApprovalServers(
    private val registry: ServerRegistry,
    private val tokenManager: TokenManager,
    private val identityTransitions: IdentityTransitionBarrier,
    /** The account signed in on a server, read through that server's own scope. */
    private val accountNameOf: suspend (AuthScopeSnapshot) -> String? = { null },
) : DeviceApprovalServers {
    override suspend fun list(): List<DeviceApprovalServer> {
        val activeId = registry.activeServerId.value
        return registry.signedInEntries(tokenManager)
            .map { DeviceApprovalServer(it.id, it.url, it.displayName, isActive = it.id == activeId) }
    }

    override fun scope(server: DeviceApprovalServer) =
        AuthScopeSnapshot.profileless(server.id, server.url, identityTransitions.generation.value)

    override suspend fun accountName(server: DeviceApprovalServer): String? = accountNameOf(scope(server))
}

/** Why "Sign in a TV" can't go on; each screen maps these to its own strings. */
sealed interface DevicePairingError {
    data object EnterCode : DevicePairingError
    data object SignInFirst : DevicePairingError
    data object NotFound : DevicePairingError

    /** The code isn't on [serverName]; other saved servers are offered. */
    data class NotFoundOn(val serverName: String) : DevicePairingError
    data object Expired : DevicePairingError
    data object AlreadySignedIn : DevicePairingError
    data object Declined : DevicePairingError
    data object Canceled : DevicePairingError
    data object Network : DevicePairingError

    /**
     * The sign-in provider couldn't re-check this phone's session (503
     * `provider_unavailable` on its refresh). The session stays; try again.
     */
    data object ProviderUnavailable : DevicePairingError

    /** The server's own explanation, or null for a generic failure. */
    data class Server(val message: String?) : DevicePairingError
}

/** How the TV's request ended after this phone approved it. */
enum class DeviceFollowOutcome {
    /** The TV collected its sign-in. */
    SignedIn,

    /** The TV withdrew the request (it left its sign-in screen or signed in another way). */
    Canceled,

    /** The request expired before the TV collected it. */
    Expired,

    /** The request was declined elsewhere. */
    Denied,

    /** The TV hadn't collected it when following stopped. */
    TimedOut,
}

data class DevicePairingUiState(
    val token: String? = null,
    /** The typed code without separators; shown grouped 4+4. */
    val code: String = "",
    val lookup: DeviceLoginLookupResponse? = null,
    val isLoading: Boolean = false,
    val isSubmitting: Boolean = false,
    val completedStatus: String? = null,
    val error: DevicePairingError? = null,
    /** Servers the code can be approved on; empty when only the active server is used. */
    val servers: List<DeviceApprovalServer> = emptyList(),
    val selectedServerId: String? = null,
    /** Account the approval signs the TV in as, when known. */
    val accountName: String? = null,
    /** The selected server's account is being read. */
    val checkingAccount: Boolean = false,
    /**
     * Why the selected server's account couldn't be read, when the reason is
     * known: the sign-in provider couldn't re-check the session
     * ([DevicePairingError.ProviderUnavailable]). Null otherwise.
     */
    val accountError: DevicePairingError? = null,
    /** The chosen server answered "no such code": offer the others. */
    val notFound: Boolean = false,
    /** After approving: how the TV's request ended, or null while it hasn't yet. */
    val followOutcome: DeviceFollowOutcome? = null,
    /**
     * A link named the server (`silo://device?server=`): the code is looked
     * up and approved there, through that server's own credentials, and
     * nowhere else. No chooser, no "Try <server>".
     */
    val serverLocked: Boolean = false,
) {
    /**
     * A `silo://device?token=` link names no server, so its lookup and
     * decision go to the active server: no chooser, no "Try <server>".
     */
    val canChooseServer: Boolean
        get() = token == null && !serverLocked && servers.size > 1

    /**
     * Whether approving or denying is a real, informed decision right now.
     *
     * The identifier being present is not the test. A `silo://device?token=…`
     * deep link arrives with a token already set and starts its lookup
     * automatically, so "there is something to submit" is true from
     * construction — before the server has said which device is asking, from
     * where, or with which code. Those details are the entire content of the
     * decision, and they only exist once [lookup] resolves. Gating on the
     * identifier let a viewer approve a sign-in they could not see, and kept
     * approving available after a failed lookup had cleared [lookup] and
     * reported the request invalid or expired.
     */
    val canDecide: Boolean
        get() = lookup != null && !isSubmitting

    /**
     * Whether the card can say which account approving signs the TV in as.
     * Required on a saved server other than the active one: the approver
     * sees exactly which account they are approving for (TV sign-in spec).
     * The active server's account is the one this phone is using.
     */
    val accountConfirmed: Boolean
        get() = accountName != null || selectedServer?.isActive != false

    /** Reading the account on a non-active server failed: say so and offer a retry. */
    val accountUnconfirmed: Boolean
        get() = !accountConfirmed && !checkingAccount

    /** Approving also needs the account known; declining doesn't. */
    val canApprove: Boolean
        get() = canDecide && accountConfirmed

    val selectedServer: DeviceApprovalServer?
        get() = servers.firstOrNull { it.id == selectedServerId }

    /** Other saved servers to try after [notFound]. */
    val otherServers: List<DeviceApprovalServer>
        get() = servers.filter { it.id != selectedServerId }
}

/**
 * Approves (or declines) a TV's sign-in from the phone: by `silo://device`
 * link or by typing the TV's code. With [servers], the code is looked up and
 * approved on the chosen server through its own account scope (the active
 * server is preselected); without, on the active server.
 *
 * [lockServer]: a link named [initialServerId], so approving happens on that
 * saved server's own account scope without switching the phone's active
 * server (silo-apple parity). When this phone isn't signed in there the
 * screen asks to sign in rather than trying another server.
 */
class DevicePairingViewModel(
    private val repository: DeviceLoginRepository,
    initialToken: String?,
    initialCode: String?,
    private val servers: DeviceApprovalServers? = null,
    private val initialServerId: String? = null,
    lockServer: Boolean = false,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        DevicePairingUiState(
            token = initialToken?.takeIf { it.isNotBlank() },
            code = DeviceCodeFormat.normalize(initialCode.orEmpty()),
            // Only a code can be looked up on a named server's own scope; a
            // token link always goes to the active server.
            serverLocked = lockServer && servers != null && initialServerId != null && initialToken.isNullOrBlank(),
        ),
    )
    val uiState: StateFlow<DevicePairingUiState> = _uiState.asStateFlow()

    private var followJob: Job? = null

    init {
        val hasRequest = _uiState.value.token != null || _uiState.value.code.isNotBlank()
        if (servers == null) {
            if (hasRequest) lookup()
        } else {
            viewModelScope.launch {
                val list = servers.list()
                val locked = _uiState.value.serverLocked
                // A token link is looked up on the active server, so that's the
                // one the card names. A locked link never falls back to another
                // server: its code means nothing there.
                val named = list.firstOrNull { it.id == initialServerId && _uiState.value.token == null }
                val selected = when {
                    // Never another server: the token goes to the active one.
                    _uiState.value.token != null -> list.firstOrNull { it.isActive }
                    locked -> named
                    else -> named ?: list.firstOrNull { it.isActive } ?: list.firstOrNull()
                }
                _uiState.update { it.copy(servers = list, selectedServerId = selected?.id) }
                when {
                    locked && selected == null ->
                        _uiState.update { it.copy(error = DevicePairingError.SignInFirst) }
                    // The lookup reads the account first.
                    hasRequest -> lookup()
                    else -> loadAccountName()
                }
            }
        }
    }

    fun onCodeChanged(value: String) {
        // Retires any lookup in flight. Without this a late answer for the
        // previous code repopulates the details after the viewer has typed a
        // different one — showing them a device that is not the one they are
        // being asked about.
        lookupGeneration++
        _uiState.update {
            it.copy(
                code = DeviceCodeFormat.normalize(value).take(DeviceCodeFormat.LENGTH),
                lookup = null,
                completedStatus = null,
                error = null,
                notFound = false,
            )
        }
    }

    /** Choose the server to look the code up on; looks it up again when one was typed. */
    fun selectServer(serverId: String) {
        if (!_uiState.value.canChooseServer) return
        if (_uiState.value.servers.none { it.id == serverId }) return
        lookupGeneration++
        _uiState.update {
            it.copy(
                selectedServerId = serverId,
                accountName = null,
                accountError = null,
                lookup = null,
                error = null,
                notFound = false,
                isLoading = false,
            )
        }
        if (_uiState.value.code.isNotBlank() || _uiState.value.token != null) {
            lookup()
        } else {
            viewModelScope.launch { loadAccountName() }
        }
    }

    /**
     * Bumped by every lookup, and by every decision.
     *
     * canDecide stays true while an EXISTING lookup refreshes — the previous
     * result is deliberately left on screen rather than blanked — so a viewer
     * can approve while a lookup is still in flight. If that lookup lands last
     * it overwrites the outcome: a lookup error painted over a successful
     * approval, or a decision's error quietly cleared. A decision is the more
     * authoritative event, so starting one retires any lookup already running.
     */
    private var lookupGeneration = 0
    /** Which lookup generation raised [DevicePairingUiState.isLoading]. */
    private var loadingOwner = 0

    fun lookup() {
        val current = _uiState.value
        val token = current.token?.takeIf { it.isNotBlank() }
        val code = current.code.takeIf { it.isNotBlank() }
        if (token == null && code == null) {
            _uiState.update { it.copy(error = DevicePairingError.EnterCode) }
            return
        }

        if (current.serverLocked && current.selectedServer == null) {
            _uiState.update { it.copy(error = DevicePairingError.SignInFirst) }
            return
        }

        val generation = ++lookupGeneration
        loadingOwner = generation
        // The server chosen when the lookup started; choosing another retires it.
        val server = current.selectedServer
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, completedStatus = null, notFound = false) }
            // Read the account first: it renews the chosen server's token when
            // it is expiring, and the card names who approving signs in.
            if (servers != null) loadAccountName()
            // Retired while the account was read (another server chosen, a
            // decision made): send nothing, since a lookup marks the request
            // opened on whichever server receives it.
            val lookupResult = if (generation == lookupGeneration) requestLookup(token, code, server) else null
            // Retired before or while in flight: a newer lookup or, more
            // importantly, a decision has superseded this answer. Clearing
            // isLoading is still this request's job, but nothing else it has
            // to say is current.
            if (lookupResult == null || generation != lookupGeneration) {
                // Clear the loading flag only while this lookup still owns it.
                // A newer lookup has raised it again for itself, and clearing
                // it here would report that one as finished while it runs.
                if (loadingOwner == generation) {
                    _uiState.update { it.copy(isLoading = false) }
                }
                return@launch
            }
            when (val result: ApiResult<DeviceLoginLookupResponse> = lookupResult) {
                is ApiResult.Success -> {
                    val problem = statusProblem(result.data.status)
                    _uiState.update {
                        if (problem == null) {
                            it.copy(isLoading = false, lookup = result.data, error = null)
                        } else {
                            it.copy(isLoading = false, lookup = null, error = problem)
                        }
                    }
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            lookup = null,
                            error = pairingError(result, it),
                            notFound = result.code == 404 && it.canChooseServer,
                        )
                    }
                }
                is ApiResult.NetworkError -> {
                    _uiState.update {
                        it.copy(isLoading = false, error = networkError(result))
                    }
                }
            }
        }
    }

    fun approve() {
        decide(approve = true)
    }

    /** "Try again" after the selected server's account couldn't be read. */
    fun retryAccount() {
        if (_uiState.value.checkingAccount) return
        viewModelScope.launch { loadAccountName() }
    }

    fun deny() {
        decide(approve = false)
    }

    private fun decide(approve: Boolean) {
        val current = _uiState.value
        val token = current.token?.takeIf { it.isNotBlank() }
        val code = current.code.takeIf { it.isNotBlank() }
        if (token == null && code == null) {
            _uiState.update { it.copy(error = DevicePairingError.EnterCode) }
            return
        }
        if (current.serverLocked && current.selectedServer == null) {
            _uiState.update { it.copy(error = DevicePairingError.SignInFirst) }
            return
        }
        // Never sign the TV in to an account the card couldn't name.
        if (approve && !current.accountConfirmed) return

        // Retires any lookup already running, before it can report back over
        // the decision this is about to make. The retired lookup will not clear
        // its own loading flag once it no longer owns the generation, so drop
        // it here — otherwise the screen shows a spinner nothing will finish.
        lookupGeneration++
        _uiState.update { it.copy(isLoading = false) }
        val server = current.selectedServer
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, error = null, completedStatus = null, followOutcome = null) }
            val result = requestDecision(approve, token, code, server)
            when (result) {
                is ApiResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            completedStatus = result.data.status,
                            error = null,
                        )
                    }
                    if (approve) followTv(token, code, server)
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isSubmitting = false,
                            error = pairingError(result, it),
                        )
                    }
                }
                is ApiResult.NetworkError -> {
                    _uiState.update {
                        it.copy(isSubmitting = false, error = networkError(result))
                    }
                }
            }
        }
    }

    /**
     * After approving, follow the request until the TV collects its sign-in
     * (or it ends another way), so the result can move from "Your TV is
     * signing in" to what happened. Best effort and bounded; it never changes
     * the decision's outcome.
     */
    private fun followTv(token: String?, code: String?, server: DeviceApprovalServer?) {
        followJob?.cancel()
        followJob = viewModelScope.launch {
            repeat(FOLLOW_ATTEMPTS) {
                delay(FOLLOW_INTERVAL_MS)
                val status = (requestLookup(token, code, server) as? ApiResult.Success)?.data?.status ?: return@repeat
                val outcome = when (status) {
                    "consumed" -> DeviceFollowOutcome.SignedIn
                    "canceled" -> DeviceFollowOutcome.Canceled
                    "expired" -> DeviceFollowOutcome.Expired
                    "denied" -> DeviceFollowOutcome.Denied
                    else -> null
                }
                if (outcome != null) {
                    _uiState.update { it.copy(followOutcome = outcome) }
                    return@launch
                }
            }
            _uiState.update { it.copy(followOutcome = DeviceFollowOutcome.TimedOut) }
        }
    }

    private suspend fun requestLookup(
        token: String?,
        code: String?,
        server: DeviceApprovalServer?,
    ): ApiResult<DeviceLoginLookupResponse> {
        return if (token == null && code != null && server != null && servers != null) {
            repository.lookup(servers.scope(server), code)
        } else {
            repository.lookup(token = token, code = code)
        }
    }

    private suspend fun requestDecision(
        approve: Boolean,
        token: String?,
        code: String?,
        server: DeviceApprovalServer?,
    ): ApiResult<org.siloserver.silo.model.auth.DeviceLoginDecisionResponse> {
        return if (token == null && code != null && server != null && servers != null) {
            val scope = servers.scope(server)
            if (approve) repository.approve(scope, code) else repository.deny(scope, code)
        } else if (approve) {
            repository.approve(token = token, code = code)
        } else {
            repository.deny(token = token, code = code)
        }
    }

    private suspend fun loadAccountName() {
        val server = _uiState.value.selectedServer ?: return
        _uiState.update { it.copy(checkingAccount = true) }
        var providerUnavailable = false
        val name = try {
            servers?.accountName(server)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            providerUnavailable = SiloAuthUnavailableException.isProviderUnavailable(e)
            null
        }
        _uiState.update {
            if (it.selectedServerId != server.id) return@update it.copy(checkingAccount = false)
            it.copy(
                checkingAccount = false,
                accountName = name,
                accountError = DevicePairingError.ProviderUnavailable.takeIf { providerUnavailable },
            )
        }
    }

    /** A lookup that answered, but for a request that can't be decided any more. */
    private fun statusProblem(status: String): DevicePairingError? = when (status) {
        "pending" -> null
        "approved", "consumed" -> DevicePairingError.AlreadySignedIn
        "denied" -> DevicePairingError.Declined
        "canceled" -> DevicePairingError.Canceled
        "expired" -> DevicePairingError.Expired
        // A status this app doesn't know: don't offer a decision on it.
        else -> DevicePairingError.Server(null)
    }

    /** An exchange that never got an answer; a provider outage keeps the session and says so. */
    private fun networkError(result: ApiResult.NetworkError): DevicePairingError =
        if (SiloAuthUnavailableException.isProviderUnavailable(result.exception)) {
            DevicePairingError.ProviderUnavailable
        } else {
            DevicePairingError.Network
        }

    private fun pairingError(error: ApiResult.Error, state: DevicePairingUiState): DevicePairingError =
        if (error.code == 503 && error.error == SiloAuthUnavailableException.PROVIDER_UNAVAILABLE_PROBLEM) {
            DevicePairingError.ProviderUnavailable
        } else {
            pairingError(error.code, error.message, state)
        }

    private fun pairingError(code: Int, message: String, state: DevicePairingUiState): DevicePairingError = when (code) {
        401 -> DevicePairingError.SignInFirst
        404 -> state.selectedServer?.takeIf { state.canChooseServer }?.let { DevicePairingError.NotFoundOn(it.displayName) }
            ?: DevicePairingError.NotFound
        409 -> DevicePairingError.AlreadySignedIn
        410 -> DevicePairingError.Expired
        else -> DevicePairingError.Server(message.takeIf { it.isNotBlank() })
    }

    override fun onCleared() {
        followJob?.cancel()
        super.onCleared()
    }

    private companion object {
        const val FOLLOW_ATTEMPTS = 40
        const val FOLLOW_INTERVAL_MS = 3_000L
    }
}
