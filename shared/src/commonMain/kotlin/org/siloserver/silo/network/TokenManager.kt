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
    /**
     * When the temporary SESSION ends — the deadline the cast UI counts down
     * to. Not the access token's deadline: the session outlives many access
     * tokens, so this must never be used to decide whether to refresh.
     */
    val expiresAtEpochMs: Long,
    /**
     * When this overlay's ACCESS TOKEN expires, or null before the first
     * refresh has told us. Null means "unknown", which keeps the reactive
     * 401 path rather than guessing off the session deadline.
     */
    val accessTokenExpiresAtEpochMs: Long? = null,
    /** Lifetime the server gave that access token, for the half-life clamp. */
    val accessTokenLifetimeMs: Long? = null,
) {
    override fun toString(): String =
        "TemporaryAuthScope(" +
            "generationId=$generationId, serverId=$serverId, serverUrl=$serverUrl, " +
            "accessToken=<redacted>, refreshToken=<redacted>, profileId=$profileId, " +
            "profileToken=<redacted>, expiresAtEpochMs=$expiresAtEpochMs)"
}

/** A profile id and the token that proves it, read together. */
data class ProfileIdentity(val profileId: String?, val profileToken: String?)

/**
 * Manages JWT access and refresh tokens.
 * Implementation provided by Agent 2 in TokenManagerImpl.kt.
 */
interface TokenManager {
    suspend fun getAccessToken(): String?
    suspend fun getRefreshToken(): String?
    suspend fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Long)

    /**
     * Installs credentials returned by an explicit login/account-approval flow.
     * Unlike [saveTokens], this is always an identity boundary, even when the
     * target server is already active and credentials already exist.
     *
     * Persistent implementations must override this and place server
     * activation, profile reset, and all credential writes inside one
     * [IdentityTransitionKind.ACCOUNT_REPLACE] mutation.
     */
    suspend fun replaceAccountSession(
        serverId: String? = null,
        serverUrl: String? = null,
        accessToken: String,
        refreshToken: String,
        expiresIn: Long,
        profileId: String? = null,
        profileToken: String? = null,
    ) {
        if (serverUrl != null) setServerUrl(serverUrl)
        if (serverId != null) switchActiveServer(serverId)
        setProfileIdentity(profileId, profileToken)
        saveTokens(accessToken, refreshToken, expiresIn)
    }
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
     * Invalidates [scope] without ever clearing a different active identity.
     *
     * Single-scope implementations may use this default. Implementations that
     * support server switching must override it with an atomic scope check and
     * mutation.
     *
     * @return true only when the captured scope was invalidated.
     */
    suspend fun invalidateSessionForScope(scope: AuthScopeSnapshot): Boolean {
        val current = snapshotCurrentScope()
        if (current != null && current != scope) return false
        invalidateSession()
        return true
    }

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

    /**
     * Read the profile id and its token as ONE identity.
     *
     * [setProfileIdentity] makes the write atomic, but a reader taking the two
     * getters separately can still interleave with a switch and pair the old id
     * with the new token — sending headers that claim one profile while
     * presenting another's proof, which is exactly what that write fixed.
     * Anything assembling both into a request must use this.
     *
     * The default is the non-atomic pair so simple/test managers keep working;
     * managers with real locking override it to read under one lock.
     *
     * A wrapper using `TokenManager by delegate` MUST override this too: Kotlin
     * interface delegation forwards default methods to the delegate, so
     * overriding only [getProfileId]/[getProfileToken] leaves this reading the
     * delegate's identity instead — silently, with tests still green.
     */
    suspend fun getProfileIdentity(): ProfileIdentity =
        ProfileIdentity(profileId = getProfileId(), profileToken = getProfileToken())

    /**
     * Commit a profile id and its matching profile token as ONE identity.
     *
     * A profile token is bound server-side to a single profile id, so the two
     * are one fact, not two. Writing them separately means a process death
     * between the writes persists a mismatch that survives to the next launch.
     *
     * This makes the WRITE one operation. It does not make concurrent reads
     * consistent: [getProfileId] and [getProfileToken] still take the lock
     * separately, so a reader interleaving with a commit can pair an old id
     * with a new token.
     *
     * The default is the non-atomic pair, which keeps simple/test managers
     * working; managers with real durable storage override this to do it in a
     * single lock and a single edit.
     */
    suspend fun setProfileIdentity(profileId: String?, profileToken: String?) {
        setProfileId(profileId)
        setProfileToken(profileToken)
    }
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
     * True when the ACTIVE scope's access token expires within [marginMs], so a
     * caller can refresh before spending it rather than after the server has
     * rejected it.
     *
     * Every implementation already records an expiry at save time and no caller
     * has ever read it, so expiry was only ever discovered by a 401: the first
     * request after the deadline paid a wasted round trip, and on a live device
     * that was 42 of 351 `/home/sections` calls.
     *
     * Default false — an implementation that cannot answer must keep today's
     * reactive behaviour rather than guess, since a wrong "yes" spends a
     * refresh token on every request.
     *
     * Implementations must clamp [marginMs] to half the token's own lifetime
     * (see [shouldRefreshProactively]). Without that, a server issuing tokens
     * shorter than the margin is inside the window from the moment it issues
     * one, and every single request refreshes.
     */
    suspend fun accessTokenExpiresWithin(marginMs: Long): Boolean = false

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
