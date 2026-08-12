package org.siloserver.silo.repository

import org.siloserver.silo.model.auth.AuthSession
import org.siloserver.silo.model.auth.InvitationLookupResponse
import org.siloserver.silo.model.auth.LoginResponse
import org.siloserver.silo.model.auth.LoginRequest
import org.siloserver.silo.model.auth.SetupStatusResponse
import org.siloserver.silo.model.auth.SignupRequest
import org.siloserver.silo.model.auth.SignupStatusResponse
import org.siloserver.silo.model.auth.User
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.ServerRegistry
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.network.api.AuthApi
import org.siloserver.silo.network.api.BrandingApi
import org.siloserver.silo.network.api.HealthApi
import org.siloserver.silo.network.map

class AuthRepository(
    private val authApi: AuthApi,
    private val tokenManager: TokenManager,
    private val serverRegistry: ServerRegistry? = null,
    private val healthApi: HealthApi? = null,
    private val brandingApi: BrandingApi? = null,
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

    /** Lists active sessions for the current user. */
    suspend fun getSessions(): ApiResult<List<AuthSession>> =
        authApi.getSessions().map { it.sessions }

    /** Revokes a specific session by ID. */
    suspend fun deleteSession(id: String): ApiResult<Unit> =
        authApi.revokeSession(id)

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
    suspend fun setServerUrl(url: String) {
        if (serverRegistry != null) {
            val id = serverRegistry.addOrUpdate(url)
            serverRegistry.switchTo(id)
            tokenManager.switchActiveServer(id)
            refreshActiveServerName()
        } else {
            tokenManager.setServerUrl(url)
        }
    }

    /**
     * Best-effort: read the native branding identity and update the active
     * registry entry's fetched name. Health is a fallback for older servers
     * without the branding endpoint. Quietly no-ops if no usable name can be
     * resolved — this is purely for nicer UX in the server list.
     */
    suspend fun refreshActiveServerName() {
        val registry = serverRegistry ?: return
        val activeId = registry.activeServerId.value ?: return
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
}
