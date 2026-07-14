package org.siloserver.silo.network

import kotlinx.coroutines.flow.SharedFlow

data class TemporaryAuthScope(
    val generationId: String,
    val serverId: String,
    val serverUrl: String,
    val accessToken: String,
    val refreshToken: String,
    val profileId: String,
    val profileToken: String,
    val expiresAtEpochMs: Long,
) {
    override fun toString(): String =
        "TemporaryAuthScope(" +
            "generationId=$generationId, serverId=$serverId, serverUrl=$serverUrl, " +
            "accessToken=<redacted>, refreshToken=<redacted>, profileId=$profileId, " +
            "profileToken=<redacted>, expiresAtEpochMs=$expiresAtEpochMs)"
}

/**
 * Manages JWT access and refresh tokens.
 * Implementation provided by Agent 2 in TokenManagerImpl.kt.
 */
interface TokenManager {
    suspend fun getAccessToken(): String?
    suspend fun getRefreshToken(): String?
    suspend fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Long)
    suspend fun clearTokens()

    /**
     * Clear tokens AND signal [sessionExpired] so observers (typically the
     * root NavHost) can route the user back to the login screen. Call this
     * from paths where the server has invalidated the session — e.g., a
     * refresh that returned 401 with no way to recover short of user
     * re-auth.
     *
     * For user-initiated sign-out (from Settings), keep calling
     * [clearTokens] directly and drive the navigation explicitly from the
     * caller — [sessionExpired] is reserved for the involuntary case so
     * the two flows don't race to issue navigations.
     */
    suspend fun invalidateSession()

    /**
     * Emits [Unit] each time [invalidateSession] runs. Does NOT fire for
     * plain [clearTokens] calls — manual signout flows own their own nav.
     *
     * Subscribers should use `collect { }` inside a `LaunchedEffect` at
     * the root of the nav graph. The flow is a replayless hot stream,
     * so late subscribers won't see past invalidations — on app startup,
     * `MainTvActivity.resolveStartDestination` already handles the
     * "tokens absent" case by routing to Login.
     */
    val sessionExpired: SharedFlow<Unit>

    suspend fun getProfileId(): String?
    suspend fun setProfileId(profileId: String?)
    suspend fun getProfileToken(): String?
    suspend fun setProfileToken(token: String?)
    suspend fun getServerUrl(): String
    suspend fun setServerUrl(url: String)

    // ----- Multi-server -----

    /**
     * Returns the id of the server whose tokens this manager is currently
     * reading/writing. Null when no server has been configured yet (or when
     * the implementation doesn't track per-server state — the in-memory
     * commonMain impl returns null).
     *
     * Used by the auth interceptor to detect a server switch that happened
     * mid-refresh: if the id changes between request start and response
     * receipt, the response is for a stale server and must not be persisted
     * to the new server's token slot.
     */
    suspend fun getCurrentServerId(): String?

    /**
     * Retarget this manager at [serverId]. Subsequent reads/writes load from
     * and persist to that server's slot. Pass `null` to detach (no active
     * server — used during full sign-out / fresh setup).
     *
     * Implementations should flush any in-memory cache so the next
     * [getAccessToken]/[getRefreshToken] re-reads from the new slot.
     */
    suspend fun switchActiveServer(serverId: String?)

    /** Wipe just the active server's tokens + profile state, keeping the registry entry. */
    suspend fun signOutCurrentServer()

    // ----- Scoped auth (Track B outbox replay; see [AuthScopeSnapshot]) -----

    /**
     * Capture the currently-active scope for pinning a background request. Returns
     * null when no server is active or the implementation isn't multi-server-aware.
     * Default: not supported (single-scope impls), so callers fall back to the
     * global active scope.
     */
    suspend fun snapshotCurrentScope(): AuthScopeSnapshot? = null

    /**
     * Read a specific server's latest access token (handles rotation). Default
     * delegates to the active-scope [getAccessToken] for single-scope impls.
     */
    suspend fun getAccessTokenForScope(serverId: String): String? = getAccessToken()

    /**
     * Read the latest access token for the exact credential identity captured by
     * [scope]. Implementations with temporary overlays must not fall through to
     * another credential slot when that overlay starts or ends mid-request.
     */
    suspend fun getAccessTokenForScope(scope: AuthScopeSnapshot): String? =
        getAccessTokenForScope(scope.serverId)

    /** Read a specific server's latest refresh token. Default: active scope. */
    suspend fun getRefreshTokenForScope(serverId: String): String? = getRefreshToken()

    /** Read the refresh token for the exact credential identity in [scope]. */
    suspend fun getRefreshTokenForScope(scope: AuthScopeSnapshot): String? =
        getRefreshTokenForScope(scope.serverId)

    /**
     * Persist refreshed tokens to a specific server's slot (used by a pinned
     * refresh so it doesn't clobber the active server). Default: active scope.
     */
    suspend fun saveTokensForScope(
        serverId: String,
        accessToken: String,
        refreshToken: String,
        expiresIn: Long,
    ) = saveTokens(accessToken, refreshToken, expiresIn)

    /** Persist a refresh only if [scope]'s credential identity still exists. */
    suspend fun saveTokensForScope(
        scope: AuthScopeSnapshot,
        accessToken: String,
        refreshToken: String,
        expiresIn: Long,
    ) = saveTokensForScope(scope.serverId, accessToken, refreshToken, expiresIn)

    /** Install a process-only identity for remote playback without mutating saved accounts. */
    suspend fun beginTemporaryScope(scope: TemporaryAuthScope) {}

    /** Restore the saved identity after remote playback. Returns true when an overlay existed. */
    suspend fun endTemporaryScope(): Boolean = false

    suspend fun hasTemporaryScope(): Boolean = false
}
