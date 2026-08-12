package org.siloserver.silo.network

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * In-memory implementation of [TokenManager] with coroutine-safe access.
 *
 * Stores JWT tokens, profile state, and server URL. Uses [Mutex] to ensure
 * thread-safe reads and writes across coroutines.
 *
 * Note: Tokens are not persisted across app restarts in this implementation.
 * A platform-specific persistent implementation (DataStore on Android,
 * Keychain on iOS) can be substituted via Koin.
 */
class TokenManagerImpl(
    private val identityTransitions: IdentityTransitionBarrier = DefaultIdentityTransitionBarrier(),
) : TokenManager {

    private val mutex = Mutex()
    private val tokenWriteMutex = Mutex()
    private val timeSource = TimeSource.Monotonic

    private var accessToken: String? = null
    private var refreshToken: String? = null
    private var tokenExpiry: TimeSource.Monotonic.ValueTimeMark? = null

    /** Lifetime the server gave the current access token, for the half-life clamp. */
    private var tokenLifetimeMs: Long? = null

    private var profileId: String? = null
    private var profileToken: String? = null

    private var serverUrl: String = "http://localhost:8090"
    private var temporaryScope: TemporaryAuthScope? = null

    // extraBufferCapacity=1 + DROP_OLDEST makes `tryEmit` from a non-suspend
    // caller always succeed without backpressure — we never care about
    // dropping a duplicate invalidation event, since the observer's
    // effect (route to Login, clear persisted tokens) is idempotent.
    private val _sessionExpired = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val sessionExpired: SharedFlow<Unit> = _sessionExpired.asSharedFlow()

    override suspend fun getAccessToken(): String? = mutex.withLock {
        temporaryScope?.accessToken ?: accessToken
    }

    override suspend fun getRefreshToken(): String? = mutex.withLock {
        temporaryScope?.refreshToken ?: refreshToken
    }

    override suspend fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Long) {
        tokenWriteMutex.withLock {
            val isInitialSignIn = mutex.withLock {
                temporaryScope == null && this.accessToken == null && this.refreshToken == null
            }
            if (isInitialSignIn) {
                identityTransitions.changing(IdentityTransitionKind.SIGN_IN) {
                    saveTokensLocked(accessToken, refreshToken, expiresIn)
                }
            } else {
                saveTokensLocked(accessToken, refreshToken, expiresIn)
            }
        }
    }

    override suspend fun replaceAccountSession(
        serverId: String?,
        serverUrl: String?,
        accessToken: String,
        refreshToken: String,
        expiresIn: Long,
        profileId: String?,
        profileToken: String?,
    ) {
        tokenWriteMutex.withLock {
            identityTransitions.changing(
                kind = IdentityTransitionKind.ACCOUNT_REPLACE,
                target = {
                    check(mutex.withLock { temporaryScope == null }) {
                        "cannot replace the account inside a temporary auth scope"
                    }
                    IdentityTransitionTarget(serverId = serverId)
                },
            ) {
                mutex.withLock {
                    if (serverUrl != null) this.serverUrl = serverUrl.trimEnd('/')
                    this.profileId = profileId
                    this.profileToken = profileToken
                    this.accessToken = accessToken
                    this.refreshToken = refreshToken
                    this.tokenExpiry = timeSource.markNow() + expiresIn.seconds
                    this.tokenLifetimeMs = expiresIn.seconds.inWholeMilliseconds
                }
            }
        }
    }

    private suspend fun saveTokensLocked(accessToken: String, refreshToken: String, expiresIn: Long) {
        mutex.withLock {
            temporaryScope?.let { scope ->
                temporaryScope = scope.copy(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                )
                return@withLock
            }
            this.accessToken = accessToken
            this.refreshToken = refreshToken
            this.tokenExpiry = timeSource.markNow() + expiresIn.seconds
            this.tokenLifetimeMs = expiresIn.seconds.inWholeMilliseconds
        }
    }

    override suspend fun clearTokens() {
        tokenWriteMutex.withLock {
            identityTransitions.changing(
                kind = IdentityTransitionKind.SIGN_OUT,
                target = { currentSignOutTarget() },
            ) {
                mutex.withLock { clearTokensLocked() }
            }
        }
    }

    override suspend fun invalidateSession() {
        tokenWriteMutex.withLock {
            identityTransitions.changing(
                kind = IdentityTransitionKind.SIGN_OUT,
                target = { currentSignOutTarget() },
            ) {
                mutex.withLock { clearTokensLocked() }
                // Non-suspending emit so this method can be called from anywhere
                // without caller cooperation. DROP_OLDEST buffer means a rapid
                // succession of invalidations collapses into a single observer
                // tick — fine since the observer's nav is idempotent.
                _sessionExpired.tryEmit(Unit)
            }
        }
    }

    /**
     * Reads the deadline [saveTokensLocked] has always recorded. A temporary
     * overlay is excluded: this impl does not track an expiry for one, and
     * answering from the underlying account's deadline would refresh the wrong
     * credentials.
     */
    override suspend fun accessTokenExpiresWithin(marginMs: Long): Boolean = mutex.withLock {
        if (temporaryScope != null) return@withLock false
        if (accessToken == null) return@withLock false
        val expiry = tokenExpiry ?: return@withLock false
        shouldRefreshProactively(
            remainingMs = (expiry - timeSource.markNow()).inWholeMilliseconds,
            lifetimeMs = tokenLifetimeMs,
            marginMs = marginMs,
        )
    }

    override suspend fun getProfileId(): String? = mutex.withLock {
        temporaryScope?.profileId ?: profileId
    }

    override suspend fun setProfileId(profileId: String?) {
        mutex.withLock {
            temporaryScope?.let { scope ->
                if (profileId != null) temporaryScope = scope.copy(profileId = profileId)
                return@withLock
            }
            this.profileId = profileId
        }
    }

    override suspend fun getProfileToken(): String? = mutex.withLock {
        temporaryScope?.profileToken ?: profileToken
    }

    override suspend fun setProfileToken(token: String?) {
        mutex.withLock {
            temporaryScope?.let { scope ->
                if (token != null) temporaryScope = scope.copy(profileToken = token)
                return@withLock
            }
            this.profileToken = token
        }
    }

    override suspend fun getProfileIdentity(): ProfileIdentity = mutex.withLock {
        temporaryScope?.let { scope ->
            return@withLock ProfileIdentity(scope.profileId, scope.profileToken)
        }
        ProfileIdentity(profileId, profileToken)
    }

    /** Single lock so the stored pair is written together; see [TokenManager]. */
    override suspend fun setProfileIdentity(profileId: String?, profileToken: String?) {
        mutex.withLock {
            // See EncryptedTokenManagerImpl: an overlay owns its identity, and
            // merging a commit into it recreates the id/token mismatch.
            if (temporaryScope != null) return@withLock
            this.profileId = profileId
            this.profileToken = profileToken
        }
    }

    override suspend fun getServerUrl(): String = mutex.withLock {
        temporaryScope?.serverUrl ?: serverUrl
    }

    override suspend fun setServerUrl(url: String) {
        identityTransitions.changing(IdentityTransitionKind.SERVER_SWITCH) {
            mutex.withLock {
                this.serverUrl = url.trimEnd('/')
            }
        }
    }

    // The in-memory impl is single-server; multi-server methods are no-ops.
    // The Android impl ([org.siloserver.silo.network.EncryptedTokenManagerImpl])
    // is what the apps actually run with.
    override suspend fun getCurrentServerId(): String? = mutex.withLock { temporaryScope?.serverId }
    override suspend fun switchActiveServer(serverId: String?) {
        identityTransitions.changing(IdentityTransitionKind.SERVER_SWITCH) { /* no-op */ }
    }
    override suspend fun signOutCurrentServer() {
        tokenWriteMutex.withLock {
            identityTransitions.changing(
                kind = IdentityTransitionKind.SIGN_OUT,
                target = { currentSignOutTarget() },
            ) {
                mutex.withLock { clearTokensLocked() }
            }
        }
    }

    override suspend fun beginTemporaryScope(scope: TemporaryAuthScope) {
        identityTransitions.changing(IdentityTransitionKind.TEMPORARY_SCOPE_BEGIN) {
            mutex.withLock { temporaryScope = scope }
        }
    }

    override suspend fun endTemporaryScope(): Boolean =
        identityTransitions.changing(IdentityTransitionKind.TEMPORARY_SCOPE_END) {
            mutex.withLock {
                val existed = temporaryScope != null
                temporaryScope = null
                existed
            }
        }

    override suspend fun hasTemporaryScope(): Boolean = mutex.withLock { temporaryScope != null }

    private suspend fun currentSignOutTarget(): IdentityTransitionTarget = mutex.withLock {
        val temporary = temporaryScope
        IdentityTransitionTarget(
            serverId = temporary?.serverId,
            purgesPersistentIdentity = temporary == null,
        )
    }

    private fun clearTokensLocked() {
        if (temporaryScope != null) {
            temporaryScope = null
            return
        }
        accessToken = null
        refreshToken = null
        tokenExpiry = null
        tokenLifetimeMs = null
        profileId = null
        profileToken = null
    }
}
