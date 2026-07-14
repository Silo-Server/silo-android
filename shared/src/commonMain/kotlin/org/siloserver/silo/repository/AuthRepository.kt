package org.siloserver.silo.repository

import org.siloserver.silo.model.auth.AuthSession
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
import org.siloserver.silo.network.api.HealthApi
import org.siloserver.silo.network.map

class AuthRepository(
    private val authApi: AuthApi,
    private val tokenManager: TokenManager,
    private val serverRegistry: ServerRegistry? = null,
    private val healthApi: HealthApi? = null,
) {
    /**
     * Logs in with username and password.
     * On success, persists tokens via [TokenManager] and returns the [User].
     */
    suspend fun login(username: String, password: String): ApiResult<User> {
        val result = authApi.login(LoginRequest(username = username, password = password))
        return when (result) {
            is ApiResult.Success -> {
                val data = result.data
                tokenManager.saveTokens(
                    accessToken = data.accessToken,
                    refreshToken = data.refreshToken,
                    expiresIn = data.expiresIn,
                )
                ApiResult.Success(data.user)
            }
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }

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
        val result = authApi.signup(
            SignupRequest(
                username = username,
                email = email,
                password = password,
                inviteCode = inviteCode,
            ),
        )
        return when (result) {
            is ApiResult.Success -> {
                val data = result.data
                tokenManager.saveTokens(
                    accessToken = data.accessToken,
                    refreshToken = data.refreshToken,
                    expiresIn = data.expiresIn,
                )
                ApiResult.Success(data.user)
            }
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }

    /**
     * Performs initial server setup (first admin user creation).
     * On success, persists tokens via [TokenManager] and returns the [User].
     */
    suspend fun setup(
        username: String,
        email: String,
        password: String,
    ): ApiResult<User> {
        val result = authApi.setup(username, email, password)
        return when (result) {
            is ApiResult.Success -> {
                val data = result.data
                tokenManager.saveTokens(
                    accessToken = data.accessToken,
                    refreshToken = data.refreshToken,
                    expiresIn = data.expiresIn,
                )
                ApiResult.Success(data.user)
            }
            is ApiResult.Error -> result
            is ApiResult.NetworkError -> result
        }
    }

    /** Checks whether the server requires initial setup. */
    suspend fun getSetupStatus(): ApiResult<SetupStatusResponse> =
        authApi.getSetupStatus()

    suspend fun getSetupStatus(serverUrl: String): ApiResult<SetupStatusResponse> =
        authApi.getSetupStatus(serverUrl)

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
            val activeId = tokenManager.getCurrentServerId()
            tokenManager.signOutCurrentServer()
            if (activeId != null) {
                serverRegistry?.signOut(activeId)
            }
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
     * Best-effort: hit `/api/v1/health` and update the active registry entry's
     * fetched name. Quietly no-ops if there's no registry, no active server,
     * or the call fails — this is purely for nicer UX in the server list.
     */
    suspend fun refreshActiveServerName() {
        val registry = serverRegistry ?: return
        val api = healthApi ?: return
        val activeId = registry.activeServerId.value ?: return
        val result = api.checkHealth()
        if (result is ApiResult.Success && registry.activeServerId.value == activeId) {
            result.data.serverName
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { registry.setFetchedName(activeId, it) }
        }
    }
}
