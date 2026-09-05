package org.siloserver.silo.repository

import org.siloserver.silo.model.auth.InvitationLookupResponse
import org.siloserver.silo.model.auth.LoginResponse
import org.siloserver.silo.model.auth.LoginRequest
import org.siloserver.silo.model.auth.SetupStatusResponse
import org.siloserver.silo.model.auth.SignupRequest
import org.siloserver.silo.model.auth.SignupStatusResponse
import org.siloserver.silo.model.auth.User
import org.siloserver.silo.model.server.ServerContract
import org.siloserver.silo.network.apiv2.ApiV2Probe
import org.siloserver.silo.network.apiv2.ApiV2ProbeResult
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.api.AuthApi
import org.siloserver.silo.network.api.BrandingApi
import org.siloserver.silo.network.api.HealthApi
import org.siloserver.silo.network.map
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long [AuthRepository.switchToServer] waits for the contract probe before
 * returning with the stored verdict untouched. Matches the launch-path bound
 * in [AuthRepository.awaitContractRefreshIfUpdateRequired].
 */
private const val SWITCH_PROBE_TIMEOUT_MS = 3_000L

class AuthRepository(
    private val authApi: AuthApi,
    private val tokenManager: TokenManager,
    private val serverRegistry: ServerRegistry? = null,
    private val healthApi: HealthApi? = null,
    private val brandingApi: BrandingApi? = null,
    private val apiV2Probe: ApiV2Probe? = null,
    /**
     * Process-lifetime scope for fire-and-forget work the caller must not wait
     * on (the display-name refresh after a server switch). When null that
     * work runs inline instead — single-server hosts and tests.
     */
    private val backgroundScope: CoroutineScope? = null,
) {
    /**
     * Persists a successful auth response's tokens into the active server's
     * scope and unwraps the [User] — the shared tail of every path that ends
     * a signed-out state (login, signup, setup, invitation claim).
     */
    private suspend fun persistSession(
        result: ApiResult<LoginResponse>,
        targetServerId: String? = null,
        targetServerUrl: String? = null,
    ): ApiResult<User> =
        when (result) {
            is ApiResult.Success -> {
                val data = result.data
                tokenManager.replaceAccountSession(
                    serverId = targetServerId ?: tokenManager.getCurrentServerId(),
                    serverUrl = targetServerUrl,
                    accessToken = data.accessToken,
                    refreshToken = data.refreshToken,
                    expiresIn = data.expiresIn,
                )
                ApiResult.Success(data.user)
            }
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }

    /**
     * Logs in with username and password.
     * On success, persists tokens via [TokenManager] and returns the [User].
     */
    suspend fun login(username: String, password: String): ApiResult<User> =
        persistSession(authApi.login(LoginRequest(username = username, password = password)))

    /**
     * Credential login without persistence. TV keeps QR and password sign-in
     * alive together, so it must choose the winning auth path before writing
     * tokens into the active server slot.
     */
    suspend fun loginForTokens(username: String, password: String): ApiResult<LoginResponse> =
        authApi.login(LoginRequest(username = username, password = password))

    /**
     * Registers a new account with an invite code.
     * On success, persists tokens via [TokenManager] and returns the [User].
     */
    suspend fun signup(
        username: String,
        email: String,
        password: String,
        inviteCode: String,
    ): ApiResult<User> {
        return persistSession(
            authApi.signup(
                SignupRequest(
                    username = username,
                    email = email,
                    password = password,
                    inviteCode = inviteCode,
                ),
            ),
        )
    }

    /**
     * Performs initial server setup (first admin user creation).
     * On success, persists tokens via [TokenManager] and returns the [User].
     */
    suspend fun setup(
        username: String,
        email: String,
        password: String,
    ): ApiResult<User> = persistSession(authApi.setup(username, email, password))

    /** Checks whether the server requires initial setup. */
    suspend fun getSetupStatus(): ApiResult<SetupStatusResponse> =
        authApi.getSetupStatus()

    suspend fun getSetupStatus(serverUrl: String): ApiResult<SetupStatusResponse> =
        authApi.getSetupStatus(serverUrl)

    /**
     * Resolves an emailed-invitation claim token against a server the app is
     * not signed into yet.
     */
    suspend fun lookupInvitation(
        serverUrl: String,
        token: String,
    ): ApiResult<InvitationLookupResponse> = authApi.lookupInvitation(serverUrl, token)

    /**
     * Accepts an emailed invitation: the account is created with the
     * invitation's email as username, tokens are persisted, and the new
     * [User] is returned — same post-conditions as [signup].
     *
     * The claim request goes to [serverUrl] directly (it needs no auth), and
     * the app only adopts that server as active once the claim has actually
     * succeeded. Switching first would strand a user whose claim fails —
     * expired token, already used, network error — on a server they have no
     * account on, with their previous session no longer active.
     */
    suspend fun acceptInvitation(
        serverUrl: String,
        token: String,
        password: String,
    ): ApiResult<User> {
        val result = authApi.acceptInvitation(serverUrl, token, password)
        if (result !is ApiResult.Success) return persistSession(result)
        val targetServerId = serverRegistry?.addOrUpdate(serverUrl)
        val persisted = persistSession(
            result = result,
            targetServerId = targetServerId,
            targetServerUrl = serverUrl.takeIf { serverRegistry == null },
        )
        if (persisted is ApiResult.Success) refreshActiveServerName()
        return persisted
    }

    /** Checks whether public signups are enabled. */
    suspend fun getSignupStatus(): ApiResult<SignupStatusResponse> =
        authApi.getSignupStatus()

    suspend fun getSignupStatus(serverUrl: String): ApiResult<SignupStatusResponse> =
        authApi.getSignupStatus(serverUrl)

    /** Fetches the currently authenticated user. */
    suspend fun getCurrentUser(): ApiResult<User> =
        authApi.getMe()

    /**
     * Logs out by clearing all persisted tokens and profile state for the
     * **currently active** server. The [ServerRegistry] entry is kept so the
     * user can sign back in without re-typing the URL — full removal goes
     * through [ServerRegistry.remove] instead.
     */
    suspend fun logout() {
        try {
            authApi.logout()
        } finally {
            tokenManager.signOutCurrentServer()
        }
    }

    /** Returns true when a refresh token is present (user has previously logged in). */
    suspend fun isLoggedIn(): Boolean =
        tokenManager.getRefreshToken() != null

    /** Returns the currently configured server URL. */
    suspend fun getServerUrl(): String =
        tokenManager.getServerUrl()

    /**
     * Updates the target server URL.
     *
     * When a [ServerRegistry] is wired in, this upserts the URL into the
     * registry and switches to it (creating a new entry the first time, or
     * reactivating an existing one). The auth/profile/token state for that
     * server (if any) is restored automatically.
     */
    suspend fun setServerUrl(url: String, contract: ServerContract? = null) {
        if (serverRegistry != null) {
            val id = serverRegistry.addOrUpdate(url)
            serverRegistry.switchTo(id)
            tokenManager.switchActiveServer(id)
            // The connect path already probed the candidate; record that
            // verdict instead of probing twice. Other callers probe now.
            refreshActiveServerName(knownContract = contract)
        } else {
            tokenManager.setServerUrl(url)
        }
    }

    /**
     * The single entry point for activating an already-registered server:
     * switches the registry and the token scope, then re-establishes the
     * server's v2 contract verdict. Every "switch to server" UI path must go
     * through here so a server saved by an older build gets probed. No-op
     * without a registry.
     *
     * Callers await this behind a spinner, so only the probe is awaited and
     * only for [SWITCH_PROBE_TIMEOUT_MS]: a server that accepts the socket
     * but never answers leaves the stored verdict alone (the gate still
     * passes UNKNOWN and V2). When the bounded probe yields no verdict —
     * the bound cancelled it or it failed (connection error, 5xx) — and a
     * [backgroundScope] is wired in, a replacement unbounded probe (the
     * client's own timeouts still apply) is launched there for [serverId],
     * so a stale UPDATE_REQUIRED on a since-upgraded or briefly unreachable
     * server is corrected once it answers instead of gating v2 calls until
     * the next launch; the
     * active-id guard in [recordServerContract] drops the result if the user
     * switched again meanwhile. Without a scope the verdict stays as stored
     * until the next switch or launch re-probes. The display-name refresh
     * (branding, then health) is likewise launched on [backgroundScope]
     * without being awaited; without a scope it runs inline after the probe.
     */
    suspend fun switchToServer(serverId: String) {
        val registry = serverRegistry ?: return
        registry.switchTo(serverId)
        tokenManager.switchActiveServer(serverId)
        refreshServerContractWithFallback(serverId)
        if (registry.activeServerId.value != serverId) return
        val scope = backgroundScope
        if (scope == null) {
            refreshActiveServerDisplayName(serverId)
        } else {
            scope.launch { refreshActiveServerDisplayName(serverId) }
        }
    }

    /**
     * The contract half of every "this server just became active" path
     * ([switchToServer], pairing): awaits [refreshServerContractBounded] and,
     * when that yields no verdict (bound elapsed, or the probe failed), hands
     * the retry to a replacement unbounded probe pinned to [serverId] on
     * [backgroundScope] so a stale UPDATE_REQUIRED stops gating the session
     * once the server answers. Returns the bounded result; null means the
     * stored verdict was left alone (and, with a scope and a probe wired in,
     * that a replacement is now in flight). Skips the replacement when
     * [serverId] is no longer active by the time the bound returns.
     */
    suspend fun refreshServerContractWithFallback(serverId: String): ServerContract? {
        val bounded = refreshServerContractBounded()
        if (bounded != null) return bounded
        val scope = backgroundScope ?: return null
        if (apiV2Probe == null || serverRegistry?.activeServerId?.value != serverId) return null
        scope.launch { refreshServerContractFor(serverId) }
        return null
    }

    /**
     * Unbounded contract probe pinned to [serverId]: skipped if it is no
     * longer active by the time this runs, and its verdict is dropped by
     * [recordServerContract] if the active server changed while in flight.
     */
    private suspend fun refreshServerContractFor(serverId: String) {
        val probe = apiV2Probe ?: return
        val registry = serverRegistry ?: return
        if (registry.activeServerId.value != serverId) return
        recordServerContract(serverId, probe.probe().toServerContract())
    }

    /**
     * Best-effort: (re)establish the active server's v2 contract verdict, then
     * read the native branding identity and update the active registry
     * entry's fetched name. Health is a fallback for older servers without the
     * branding endpoint. Quietly no-ops if no usable name can be resolved —
     * this is purely for nicer UX in the server list.
     *
     * [knownContract] lets the connect path pass the verdict it already has
     * instead of probing twice; every other caller probes now. Both the
     * contract and the name are dropped if the active server changed while
     * the request was in flight, so a slow answer from one server never
     * relabels another.
     */
    suspend fun refreshActiveServerName(knownContract: ServerContract? = null) {
        val registry = serverRegistry ?: return
        val activeId = registry.activeServerId.value ?: return
        if (knownContract != null) recordServerContract(activeId, knownContract) else refreshServerContract()
        refreshActiveServerDisplayName(activeId)
    }

    /**
     * The name half of [refreshActiveServerName]: branding, then health as a
     * fallback, recorded only while [activeId] is still the active server.
     */
    private suspend fun refreshActiveServerDisplayName(activeId: String) {
        val registry = serverRegistry ?: return
        if (registry.activeServerId.value != activeId) return
        val brandingName = (brandingApi?.getBranding() as? ApiResult.Success)
            ?.data
            ?.serverName
            .usableServerName()
        val resolvedName = brandingName ?: (healthApi?.checkHealth() as? ApiResult.Success)
            ?.data
            ?.serverName
            .usableServerName()
        if (resolvedName != null && registry.activeServerId.value == activeId) {
            registry.setFetchedName(activeId, resolvedName)
        }
    }

    private fun String?.usableServerName(): String? = this?.trim()?.takeIf { it.isNotBlank() }

    /** The active server's learned [ServerContract]; [ServerContract.UNKNOWN] without a registry. */
    fun activeServerContract(): ServerContract =
        serverRegistry?.activeEntry?.value?.contract ?: ServerContract.UNKNOWN

    /**
     * [activeServerContract] as a stream, so a consumer whose gated v2 call
     * failed on a stale [ServerContract.UPDATE_REQUIRED] can re-run it once
     * the launch probe records the real verdict. Emits the current value
     * first; a single [ServerContract.UNKNOWN] without a registry.
     */
    val activeServerContractFlow: Flow<ServerContract> =
        serverRegistry?.activeEntry
            ?.map { it?.contract ?: ServerContract.UNKNOWN }
            ?.distinctUntilChanged()
            ?: flowOf(ServerContract.UNKNOWN)

    /**
     * Launch-time guard for the stored verdict. Only a stale
     * [ServerContract.UPDATE_REQUIRED] is harmful: UNKNOWN and V2 both pass
     * [org.siloserver.silo.network.apiv2.ApiV2Gate], so those refresh in the
     * background. When the stored state is UPDATE_REQUIRED (the server may
     * have been upgraded since), probe now and wait at most [timeoutMs] so
     * gated startup consumers see the refreshed verdict instead of the
     * stale one. Returns the recorded verdict, or null when nothing was
     * recorded (not UPDATE_REQUIRED, no probe wired in, timed out, or the
     * probe failed) — pass it to [refreshActiveServerName] as
     * `knownContract`: a verdict avoids a second probe, while null makes
     * that call probe again so a briefly unreachable server is retried.
     */
    suspend fun awaitContractRefreshIfUpdateRequired(timeoutMs: Long = SWITCH_PROBE_TIMEOUT_MS): ServerContract? {
        if (activeServerContract() != ServerContract.UPDATE_REQUIRED) return null
        return refreshServerContractBounded(timeoutMs)
    }

    /**
     * [refreshServerContract] with a wall-clock bound. Every path that awaits
     * the probe before letting the user (or a peer, in pairing) proceed goes
     * through here so the bound lives in one place. Returns a real verdict
     * ([ServerContract.V2] or [ServerContract.UPDATE_REQUIRED]) only; null
     * means nothing was recorded — no probe wired in, the server did not
     * answer within [timeoutMs], or the probe failed (connection error, 5xx)
     * — and the stored verdict is left alone. "No verdict" is uniform so
     * callers re-probe on null instead of treating a transient failure as
     * settled, which would otherwise leave a stale UPDATE_REQUIRED gating the
     * whole session.
     */
    suspend fun refreshServerContractBounded(timeoutMs: Long = SWITCH_PROBE_TIMEOUT_MS): ServerContract? =
        withTimeoutOrNull(timeoutMs) { refreshServerContract() }
            ?.toServerContract()
            ?.takeUnless { it == ServerContract.UNKNOWN }

    /**
     * Runs the v2 contract probe against [serverUrl] (a candidate the app is
     * not connected to yet) without recording anything. Null when no probe is
     * wired in (single-server hosts, tests).
     */
    suspend fun probeServerContract(serverUrl: String): ApiV2ProbeResult? =
        apiV2Probe?.probe(serverUrl)

    /**
     * Records [contract] on the active registry entry when it is a verdict
     * ([ServerContract.V2] or [ServerContract.UPDATE_REQUIRED]); an
     * [ServerContract.UNKNOWN] result leaves the stored state alone, because a
     * transport, TLS, auth, rate-limit, or server error says nothing about
     * the contract.
     */
    suspend fun recordServerContract(contract: ServerContract) {
        val activeId = serverRegistry?.activeServerId?.value ?: return
        recordServerContract(activeId, contract)
    }

    /**
     * Records [contract] for [serverId] only while it is still the active
     * server, so a probe answer that arrives after a switch cannot overwrite
     * the newer server's state.
     */
    private suspend fun recordServerContract(serverId: String, contract: ServerContract) {
        if (contract == ServerContract.UNKNOWN) return
        val registry = serverRegistry ?: return
        if (registry.activeServerId.value != serverId) return
        registry.setContract(serverId, contract)
    }

    /**
     * Runs the v2 contract probe once against the active server and records
     * the verdict. Called when a connection is established, restored on
     * launch, switched to, or its identity is refreshed — never per request.
     * A failure records nothing (the stored state stays as it was), and a
     * result for a server that is no longer active is dropped.
     */
    suspend fun refreshServerContract(): ApiV2ProbeResult? {
        val probe = apiV2Probe ?: return null
        val activeId = serverRegistry?.activeServerId?.value
        val result = probe.probe()
        if (activeId != null) recordServerContract(activeId, result.toServerContract())
        return result
    }
}

/**
 * The verdict to record once a v2 operation (the connect path's
 * `/api/v2/system/setup` call) has succeeded against a candidate: that answer
 * is itself proof of v2, whatever the earlier bounded candidate probe said. A
 * probe [ApiV2ProbeResult.Failure] or timeout is no verdict and must not be
 * mapped through [toServerContract] into a non-null [ServerContract.UNKNOWN]
 * here — [AuthRepository.refreshActiveServerName] neither records UNKNOWN
 * nor re-probes when handed a known contract, so a stale UPDATE_REQUIRED
 * would survive a successful v2 connection. Only the probe's
 * [ApiV2ProbeResult.UpdateServer] is acted on, before the setup call.
 */
val CONTRACT_PROVEN_BY_V2_RESPONSE: ServerContract = ServerContract.V2

/** Maps a probe outcome to the stored state; failures are [ServerContract.UNKNOWN] (no verdict). */
fun ApiV2ProbeResult.toServerContract(): ServerContract = when (this) {
    is ApiV2ProbeResult.V2 -> ServerContract.V2
    ApiV2ProbeResult.UpdateServer -> ServerContract.UPDATE_REQUIRED
    is ApiV2ProbeResult.Failure -> ServerContract.UNKNOWN
}
