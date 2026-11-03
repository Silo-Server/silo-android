package org.siloserver.silo.network

import android.content.SharedPreferences
import org.siloserver.silo.network.AndroidServerRegistry.Companion.accountScopedKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persistent [TokenManager] backed by EncryptedSharedPreferences with per-server
 * isolation.
 *
 * All token + profile values are stored under keys of the form
 * "<serverId>.<localAccountId>.<baseKey>" (see [AndroidServerRegistry.accountScopedKey])
 * so each saved account keeps its own credentials. The active slot is whatever
 * server and account the [ServerRegistry] currently has selected — this manager
 * mirrors that selection into its in-memory cache and listens for switches so
 * subsequent reads target the right slot. A per-server read for a server that
 * is not active uses that server's current account ([currentAccountOn]).
 *
 * Mirrors the iOS multi-server `TokenStore` semantics: switching server flushes
 * the in-memory cache, and the auth interceptor's refresh path checks
 * [getCurrentServerId] before saving the response so a stale 401-refresh that
 * lands after a switch can't leak tokens into the new server's slot.
 */
class EncryptedTokenManagerImpl(
    private val prefs: SharedPreferences,
    private val registry: ServerRegistry,
    private val identityTransitions: IdentityTransitionBarrier = DefaultIdentityTransitionBarrier(),
    private val afterAccountSessionCommit: suspend () -> Unit = {},
    private val afterAccountSignOutCommit: suspend () -> Unit = {},
) : TokenManager, DurableLoginAuthorityProvider {

    private val mutex = Mutex()
    private val tokenWriteMutex = Mutex()
    private val failedAuthorityWrites = mutableSetOf<String>()
    private val pendingAuthorityBootstrap = mutableSetOf<String>()
    private val scope = CoroutineScope(Dispatchers.Default)

    private var activeServerId: String? = registry.activeServerId.value
    private var activeAccountId: String? = registry.activeAccountId.value

    // Cached values for the active server. Refilled on every server switch.
    private var accessToken: String? = null
    private var refreshToken: String? = null
    private var tokenExpiryEpochMs: Long? = null

    /** Lifetime the server gave the active access token, for the half-life clamp. */
    private var tokenLifetimeMs: Long? = null
    private var profileId: String? = null
    private var profileToken: String? = null
    private var temporaryScope: TemporaryAuthScope? = null

    /**
     * Incremented whenever the persistent identity moves: writing or clearing
     * persistent credentials, and switching the active server.
     * Stamped onto snapshots so a scope captured before a sign-out cannot read or
     * overwrite the credentials of the login that replaced it. Overlay begin/end
     * deliberately does not move it — see [AuthScopeSnapshot.credentialEpoch].
     */
    // Zero is reserved by AuthScopeSnapshot for callers that did not capture
    // a live generation. A manager constructed over credentials already on
    // disk must therefore begin at a non-sentinel value.
    private var persistentCredentialEpoch: Long = 1L

    private val _sessionExpired = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val sessionExpired: SharedFlow<Unit> = _sessionExpired.asSharedFlow()

    private val _temporarySessionExpired = MutableSharedFlow<String>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val temporarySessionExpired: SharedFlow<String> = _temporarySessionExpired.asSharedFlow()

    init {
        // Load initial cache for whichever server the registry made active
        // (post-migration / from disk).
        runBlocking { reloadCacheLocked() }

        // Observe future registry switches — UI flows that call
        // ServerRegistry.switchTo() outside switchActiveServer() are still
        // picked up here. The cache is flushed under the same mutex used by
        // every read/write so requests in flight see consistent state.
        scope.launch {
            registry.activeAccountId.collectLatest {
                mutex.withLock { ensureCacheMatchesRegistryLocked() }
            }
        }
        scope.launch {
            registry.activeServerId.collectLatest {
                mutex.withLock { ensureCacheMatchesRegistryLocked() }
            }
        }
    }

    // ---- Token reads/writes (always against the active server) ----

    override suspend fun getAccessToken(): String? = mutex.withLock {
        ensureCacheMatchesRegistryLocked()
        temporaryScope?.accessToken ?: accessToken
    }
    override suspend fun getRefreshToken(): String? = mutex.withLock {
        ensureCacheMatchesRegistryLocked()
        temporaryScope?.refreshToken ?: refreshToken
    }

    /**
     * Answers for whichever identity is actually installed: a remote-playback
     * overlay carries its own deadline, and falling through to the saved
     * account's would refresh credentials this request is not spending.
     */
    override suspend fun accessTokenExpiresWithin(marginMs: Long): Boolean = mutex.withLock {
        ensureCacheMatchesRegistryLocked()
        temporaryScope?.let { overlay ->
            // NOT expiresAtEpochMs — that is when the temporary SESSION ends,
            // which can be hours past the access token it was issued with.
            // Unknown until a refresh has told us, and unknown means leave it
            // to the reactive 401 rather than refresh on a guess.
            val overlayExpiry = overlay.accessTokenExpiresAtEpochMs ?: return@withLock false
            return@withLock shouldRefreshProactively(
                remainingMs = overlayExpiry - System.currentTimeMillis(),
                lifetimeMs = overlay.accessTokenLifetimeMs,
                marginMs = marginMs,
            )
        }
        if (accessToken == null) return@withLock false
        val expiry = tokenExpiryEpochMs ?: return@withLock false
        shouldRefreshProactively(
            remainingMs = expiry - System.currentTimeMillis(),
            lifetimeMs = tokenLifetimeMs,
            marginMs = marginMs,
        )
    }

    /**
     * The registry observer flushes the cache asynchronously; between a
     * direct `ServerRegistry.switchTo()` and that collector running there is
     * a window where `getServerUrl()` already answers with the NEW server
     * while the cached credentials still belong to the OLD one — the auth
     * plugin would then send server A's bearer/profile headers to server B.
     * Reads therefore verify the cache against the live registry id and
     * reload synchronously (under the same mutex) when they diverge.
     */
    private suspend fun ensureCacheMatchesRegistryLocked() {
        val liveId = registry.activeServerId.value
        val liveAccount = registry.activeAccountId.value
        if (liveId != activeServerId || liveAccount != activeAccountId) {
            activeServerId = liveId
            activeAccountId = liveAccount
            persistentCredentialEpoch += 1
            reloadCacheUnsynchronized()
        }
    }

    override suspend fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Long) {
        tokenWriteMutex.withLock {
            val isInitialSignIn = mutex.withLock {
                ensureCacheMatchesRegistryLocked()
                temporaryScope == null && this.accessToken == null && this.refreshToken == null
            }
            if (isInitialSignIn) {
                identityTransitions.changing(IdentityTransitionKind.SIGN_IN) {
                    saveActiveTokensLocked(accessToken, refreshToken, expiresIn)
                }
            } else {
                saveActiveTokensLocked(accessToken, refreshToken, expiresIn)
            }
        }
    }

    override suspend fun captureAccountSessionExpectation(): AccountSessionExpectation? {
        val generation = identityTransitions.generation.value
        return identityTransitions.withCurrentGeneration(generation) {
            mutex.withLock {
                ensureCacheMatchesRegistryLocked()
                check(temporaryScope == null) { "Temporary identity cannot install a persistent session" }
                AccountSessionExpectation(generation, activeServerId, registry.activeEntry.value?.url.orEmpty())
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
        expectedIdentity: AccountSessionExpectation?,
    ) {
        replaceAccountSession(
            serverId, serverUrl, accessToken, refreshToken, expiresIn, profileId, profileToken, expectedIdentity,
            account = null,
        )
    }

    override suspend fun replaceAccountSession(
        serverId: String?,
        serverUrl: String?,
        accessToken: String,
        refreshToken: String,
        expiresIn: Long,
        profileId: String?,
        profileToken: String?,
        expectedIdentity: AccountSessionExpectation?,
        account: AccountSignIn?,
    ): String {
        val targetServerId = serverId
            ?: serverUrl?.let { registry.addOrUpdate(it) }
            ?: registry.activeServerId.value
            ?: error("account replacement requires a registered server")
        val androidRegistry = registry as? AndroidServerRegistry
            ?: error("persistent account replacement requires AndroidServerRegistry")
        return tokenWriteMutex.withLock {
            identityTransitions.changing(
                kind = IdentityTransitionKind.ACCOUNT_REPLACE,
                target = {
                    if (expectedIdentity != null && (identityTransitions.generation.value != expectedIdentity.generation || !expectedIdentity.installationAllowed())) {
                        throw AccountSessionChangedException()
                    }
                    check(mutex.withLock { temporaryScope == null }) {
                        "cannot replace the account inside a temporary auth scope"
                    }
                    IdentityTransitionTarget(serverId = targetServerId)
                },
            ) {
                mutex.withLock {
                    val lifetimeMs = expiresIn * 1_000L
                    val expiryEpochMs = System.currentTimeMillis() + lifetimeMs
                    // Registry selection and token/profile slots share the same
                    // encrypted preferences file, so commit them atomically and
                    // synchronously before publishing the cache.
                    // Until the commit lands no account id exists to fence, so
                    // fence the server: its current slot is about to change.
                    val fenced = registry.currentAccountOn(targetServerId)?.localAccountId
                    fenced?.let(failedAuthorityWrites::add)
                    val accountId = androidRegistry.commitAccountReplacement(
                        serverId = targetServerId,
                        reauthAccountId = account?.reauthAccountId,
                        serverUserId = account?.serverUserId,
                        username = account?.username,
                        profileId = profileId,
                        profileToken = profileToken,
                        accessToken = accessToken,
                        refreshToken = refreshToken,
                        expiryEpochMs = expiryEpochMs,
                        lifetimeMs = lifetimeMs,
                    )
                    fenced?.let(failedAuthorityWrites::remove)
                    failedAuthorityWrites.remove(accountId)
                    activeServerId = targetServerId
                    activeAccountId = accountId
                    this.profileId = profileId
                    this.profileToken = profileToken
                    this.accessToken = accessToken
                    this.refreshToken = refreshToken
                    tokenExpiryEpochMs = expiryEpochMs
                    tokenLifetimeMs = lifetimeMs
                    persistentCredentialEpoch += 1
                    afterAccountSessionCommit()
                    accountId
                }
            }
        }
    }

    private suspend fun saveActiveTokensLocked(accessToken: String, refreshToken: String, expiresIn: Long) {
        mutex.withLock {
            ensureCacheMatchesRegistryLocked()
            temporaryScope?.let { scope ->
                // The SESSION deadline (expiresAtEpochMs) is untouched: a
                // refresh renews the access token, not the temporary session.
                temporaryScope = scope.copy(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    accessTokenExpiresAtEpochMs =
                        System.currentTimeMillis() + expiresIn * 1000L,
                    accessTokenLifetimeMs = expiresIn * 1000L,
                )
                return@withLock
            }
            val serverId = activeServerId ?: return@withLock
            // Credentials always belong to an account; a sign-in makes one
            // through replaceAccountSession, so there is nothing to refresh here.
            val accountId = activeAccountId ?: return@withLock
            val firstInstall = this.accessToken == null && this.refreshToken == null
            if (firstInstall) {
                failedAuthorityWrites.add(accountId)
                check(prefs.edit()
                    .putString(accountScopedKey(serverId, accountId, KEY_LOGIN_ID), java.util.UUID.randomUUID().toString())
                    .putString(accountScopedKey(serverId, accountId, KEY_ACCESS_TOKEN), accessToken)
                    .putString(accountScopedKey(serverId, accountId, KEY_REFRESH_TOKEN), refreshToken)
                    .putLong(accountScopedKey(serverId, accountId, KEY_TOKEN_EXPIRY), System.currentTimeMillis() + expiresIn * 1000L)
                    .putLong(accountScopedKey(serverId, accountId, KEY_TOKEN_LIFETIME), expiresIn * 1000L)
                    .commit()) { "unable to durably install account session" }
                failedAuthorityWrites.remove(accountId)
            }
            this.accessToken = accessToken
            this.refreshToken = refreshToken
            val lifetimeMs = expiresIn * 1000L
            val expiryEpochMs = System.currentTimeMillis() + lifetimeMs
            this.tokenExpiryEpochMs = expiryEpochMs
            this.tokenLifetimeMs = lifetimeMs
            persistentCredentialEpoch += 1
            prefs.edit()
                .putString(accountScopedKey(serverId, accountId, KEY_ACCESS_TOKEN), accessToken)
                .putString(accountScopedKey(serverId, accountId, KEY_REFRESH_TOKEN), refreshToken)
                .putLong(accountScopedKey(serverId, accountId, KEY_TOKEN_EXPIRY), expiryEpochMs)
                .putLong(accountScopedKey(serverId, accountId, KEY_TOKEN_LIFETIME), lifetimeMs)
                .apply()
        }
    }

    override suspend fun clearTokens() {
        tokenWriteMutex.withLock {
            identityTransitions.changing(
                kind = IdentityTransitionKind.SIGN_OUT,
                target = { currentSignOutTarget() },
            ) {
                mutex.withLock { clearCurrentScopeLocked(expired = false) }
            }
        }
    }

    override suspend fun invalidateSession() {
        tokenWriteMutex.withLock {
            identityTransitions.changing(
                kind = IdentityTransitionKind.SIGN_OUT,
                target = { currentSignOutTarget() },
            ) {
                mutex.withLock {
                    val expiredTemporary = temporaryScope?.generationId
                    clearCurrentScopeLocked(expired = true)
                    if (expiredTemporary == null) {
                        _sessionExpired.tryEmit(Unit)
                    } else {
                        _temporarySessionExpired.tryEmit(expiredTemporary)
                    }
                }
            }
        }
    }

    /**
     * Ends the installed identity. A temporary overlay just ends. A saved
     * account is signed out ([expired] false: the account and, through
     * [afterAccountSignOutCommit], its local data go) or marked as needing
     * sign-in ([expired] true: the server refused it; account and data stay).
     */
    private suspend fun clearCurrentScopeLocked(expired: Boolean) {
        if (temporaryScope != null) {
            temporaryScope = null
        } else {
            clearPersistentTokensLocked(expired)
        }
    }

    private suspend fun clearPersistentTokensLocked(expired: Boolean) {
        val serverId = activeServerId
        val accountId = activeAccountId
        var committedSignOut = false
        if (serverId != null && accountId != null) {
            failedAuthorityWrites.add(accountId)
            val androidRegistry = registry as? AndroidServerRegistry
                ?: error("persistent sign-out requires AndroidServerRegistry")
            androidRegistry.commitAccountSignOut(serverId, accountId, expired)
            failedAuthorityWrites.remove(accountId)
            committedSignOut = !expired
            activeAccountId = registry.activeAccountId.value
        }
        persistentCredentialEpoch += 1
        accessToken = null
        refreshToken = null
        tokenExpiryEpochMs = null
        tokenLifetimeMs = null
        profileToken = null
        if (!expired) profileId = null
        if (committedSignOut) afterAccountSignOutCommit()
    }

    override suspend fun getProfileId(): String? = mutex.withLock {
        ensureCacheMatchesRegistryLocked()
        temporaryScope?.profileId ?: profileId
    }

    override suspend fun setProfileId(profileId: String?) {
        mutex.withLock {
            temporaryScope?.let { scope ->
                if (profileId != null) temporaryScope = scope.copy(profileId = profileId)
                return@withLock
            }
            val serverId = activeServerId ?: return
            val accountId = activeAccountId ?: return
            if (this.profileId == profileId) return
            this.profileId = profileId
            val key = accountScopedKey(serverId, accountId, KEY_PROFILE_ID)
            prefs.edit().apply {
                if (profileId == null) remove(key) else putString(key, profileId)
            }.apply()
        }
    }

    override suspend fun getProfileToken(): String? = mutex.withLock {
        ensureCacheMatchesRegistryLocked()
        temporaryScope?.profileToken ?: profileToken
    }

    override suspend fun setProfileToken(token: String?) {
        mutex.withLock {
            temporaryScope?.let { scope ->
                if (token != null) temporaryScope = scope.copy(profileToken = token)
                return@withLock
            }
            val serverId = activeServerId ?: return
            val accountId = activeAccountId ?: return
            if (this.profileToken == token) return
            this.profileToken = token
            val key = accountScopedKey(serverId, accountId, KEY_PROFILE_TOKEN)
            prefs.edit().apply {
                if (token == null) remove(key) else putString(key, token)
            }.apply()
        }
    }

    override suspend fun getProfileIdentity(): ProfileIdentity = mutex.withLock {
        ensureCacheMatchesRegistryLocked()
        temporaryScope?.let { scope ->
            return@withLock ProfileIdentity(scope.profileId, scope.profileToken)
        }
        ProfileIdentity(profileId, profileToken)
    }

    /**
     * One lock, one preferences edit, so the PERSISTED id and token cannot
     * disagree even if the process dies immediately after. Concurrent readers
     * are a separate problem — see [TokenManager.setProfileIdentity].
     *
     * While a temporary overlay exists this refuses the write entirely rather
     * than merging into it: remote-playback identity belongs to the overlay,
     * and a partial merge is what produced the id/token mismatch in the first
     * place.
     */
    override suspend fun setProfileIdentity(profileId: String?, profileToken: String?) {
        mutex.withLock {
            // A temporary overlay owns its own identity for the lifetime of a
            // remote-playback handoff. Merging a profile commit into it is how
            // you get the exact defect this method exists to prevent: writing
            // the new profile id beside the overlay's old token. Leave it
            // alone; the repository rejects the commit outright.
            if (temporaryScope != null) return@withLock
            val serverId = activeServerId ?: return
            val accountId = activeAccountId ?: return
            if (this.profileId == profileId && this.profileToken == profileToken) return
            this.profileId = profileId
            this.profileToken = profileToken
            val idKey = accountScopedKey(serverId, accountId, KEY_PROFILE_ID)
            val tokenKey = accountScopedKey(serverId, accountId, KEY_PROFILE_TOKEN)
            prefs.edit().apply {
                if (profileId == null) remove(idKey) else putString(idKey, profileId)
                if (profileToken == null) remove(tokenKey) else putString(tokenKey, profileToken)
            }.apply()
        }
    }

    override suspend fun getServerUrl(): String = mutex.withLock {
        temporaryScope?.serverUrl ?: registry.activeEntry.value?.url.orEmpty()
    }

    /**
     * Adds-or-updates [url] in the registry and switches to it. Used by the
     * server-setup flow (one-server-at-a-time entry) — multi-server-aware
     * call sites should go through [ServerRegistry.addOrUpdate] /
     * [ServerRegistry.switchTo] directly.
     */
    override suspend fun setServerUrl(url: String) {
        val newId = registry.addOrUpdate(url)
        registry.switchTo(newId)
        // Defer cache reload to switchActiveServer so the registry observer
        // and this call can't race into two reloads — switchActiveServer's
        // existing no-op guard makes whichever runs second a no-op.
        switchActiveServer(newId)
    }

    // ---- Multi-server controls ----

    override suspend fun getCurrentServerId(): String? = mutex.withLock {
        temporaryScope?.serverId ?: activeServerId
    }

    override suspend fun getCurrentAccountId(): String? = mutex.withLock {
        if (temporaryScope != null) return@withLock null
        ensureCacheMatchesRegistryLocked()
        activeAccountId
    }

    override suspend fun switchActiveServer(serverId: String?) {
        if (mutex.withLock { activeServerId == serverId }) return
        identityTransitions.changing(IdentityTransitionKind.SERVER_SWITCH) {
            mutex.withLock {
                if (activeServerId == serverId) return@withLock
                activeServerId = serverId
                persistentCredentialEpoch += 1
                reloadCacheUnsynchronized()
            }
        }
    }

    override suspend fun signOutCurrentServer() {
        tokenWriteMutex.withLock {
            identityTransitions.changing(
                kind = IdentityTransitionKind.SIGN_OUT,
                target = { currentSignOutTarget() },
            ) {
                mutex.withLock { clearCurrentScopeLocked(expired = false) }
            }
        }
    }

    override suspend fun signOutServer(serverId: String?, loginId: String?): Boolean =
        tokenWriteMutex.withLock {
            // A temporary scope (a phone's borrowed identity) that appeared
            // while the sign-out waited is not this saved login: clearing the
            // current scope would end it and leave the saved login signed in.
            fun stillActiveLocked() = temporaryScope == null &&
                activeServerId == serverId && serverId?.let { durableLoginIdLocked(it) } == loginId
            if (!mutex.withLock { ensureCacheMatchesRegistryLocked(); stillActiveLocked() }) return@withLock false
            identityTransitions.changing(
                kind = IdentityTransitionKind.SIGN_OUT,
                target = { currentSignOutTarget() },
            ) {
                mutex.withLock {
                    ensureCacheMatchesRegistryLocked()
                    // A server switch takes no token-write lock, so it can land
                    // between the check above and this transition.
                    if (!stillActiveLocked()) return@withLock false
                    clearCurrentScopeLocked(expired = false)
                    true
                }
            }
        }

    override suspend fun signOutAccount(serverId: String, accountId: String): Boolean =
        tokenWriteMutex.withLock {
            mutex.withLock {
                ensureCacheMatchesRegistryLocked()
                // The active account's sign-out is an identity transition; it
                // goes through signOutServer. Any other account's is not.
                if (activeServerId == serverId && activeAccountId == accountId) return@withLock false
                if (registry.accounts.value.none { it.localAccountId == accountId && it.serverId == serverId }) {
                    return@withLock false
                }
                val androidRegistry = registry as? AndroidServerRegistry ?: return@withLock false
                androidRegistry.commitAccountSignOut(serverId, accountId, expired = false)
                afterAccountSignOutCommit()
                true
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

    // ---- Scoped auth (pinned background requests) ----

    override suspend fun snapshotCurrentScope(): AuthScopeSnapshot? = mutex.withLock { snapshotCurrentScopeLocked() }

    override suspend fun snapshotDurableLoginAuthority(): DurableLoginAuthority? = tokenWriteMutex.withLock {
        mutex.withLock {
            val scope = snapshotCurrentScopeLocked() ?: return@withLock null
            if (scope.credentialGenerationId != null || scope.profileId.isNullOrBlank()) return@withLock null
            DurableLoginAuthority(durableLoginIdLocked(scope.serverId) ?: return@withLock null, scope)
        }
    }

    override suspend fun savedLoginAuthority(
        serverId: String,
        profileId: String,
        profileToken: String?,
    ): DurableLoginAuthority? = tokenWriteMutex.withLock {
        mutex.withLock {
            ensureCacheMatchesRegistryLocked()
            val url = registry.entries.value.firstOrNull { it.id == serverId }?.url
            if (url.isNullOrBlank()) return@withLock null
            val loginId = durableLoginIdLocked(serverId) ?: return@withLock null
            DurableLoginAuthority(
                loginId,
                AuthScopeSnapshot.savedLogin(
                    serverId, url, profileId, profileToken, identityTransitions.generation.value,
                    accountId = accountFor(serverId),
                ),
            )
        }
    }

    /**
     * The login id of [serverId]'s saved slot, which every account
     * replacement writes afresh and every sign-out removes. A slot signed in
     * before the id existed gets one here. Null without saved credentials or
     * while the slot's last write failed.
     */
    private fun durableLoginIdLocked(serverId: String): String? {
        val accountId = accountFor(serverId) ?: return null
        if (persistentAccessToken(serverId, accountId).isNullOrBlank() ||
            persistentRefreshToken(serverId, accountId).isNullOrBlank() ||
            accountId in failedAuthorityWrites) return null
        val key = accountScopedKey(serverId, accountId, KEY_LOGIN_ID)
        var loginId = prefs.getString(key, null)
        if (loginId.isNullOrBlank()) {
            loginId = java.util.UUID.randomUUID().toString()
            pendingAuthorityBootstrap.add(accountId)
        }
        // A failed SharedPreferences commit may still change its in-memory map.
        // Retry that commit before exposing the marker, never trust the map alone.
        if (accountId in pendingAuthorityBootstrap) {
            if (!prefs.edit().putString(key, loginId).commit()) return null
            pendingAuthorityBootstrap.remove(accountId)
        }
        return loginId
    }

    /**
     * The account whose slot answers for [serverId]: the active account when
     * [serverId] is active, otherwise the server's current account.
     */
    private fun accountFor(serverId: String): String? =
        if (serverId == activeServerId) activeAccountId else registry.currentAccountOn(serverId)?.localAccountId

    /** The account [this] scope reads: the one it captured, else its server's current one. */
    private fun AuthScopeSnapshot.slotAccountId(): String? = accountId ?: accountFor(serverId)

    override suspend fun loginSessionId(serverId: String): String? = tokenWriteMutex.withLock {
        mutex.withLock {
            ensureCacheMatchesRegistryLocked()
            durableLoginIdLocked(serverId)
        }
    }

    private suspend fun snapshotCurrentScopeLocked(): AuthScopeSnapshot? {
        temporaryScope?.let { scope ->
            return AuthScopeSnapshot(
                serverId = scope.serverId,
                profileId = scope.profileId,
                serverUrl = scope.serverUrl,
                profileToken = scope.profileToken,
                credentialGenerationId = scope.generationId,
                identityGeneration = identityTransitions.generation.value,
                isIdentityGenerationStamped = true,
            )
        }
        // Reconcile with the registry FIRST. The registry observer is
        // asynchronous, so immediately after a `switchTo(B)` this cached id can
        // still be A — and every guard that trusts the snapshot then decides
        // against a server the app has already left. The token reads
        // (getAccessToken/getRefreshToken/getProfileId) reconcile; the snapshot
        // did not, which made it disagree with them. Note getCurrentServerId
        // still reads the cache directly.
        ensureCacheMatchesRegistryLocked()
        val serverId = activeServerId ?: return null
        // Resolve the URL for *this* serverId from the registry entries so the
        // snapshot is internally consistent. Do NOT fall back to activeEntry —
        // if the captured serverId isn't a known, non-blank-URL entry, a pinned
        // request could be sent to the wrong server (or skip the rewrite and hit
        // localhost). Return null instead so the drain simply no-ops this pass.
        val url = registry.entries.value.firstOrNull { it.id == serverId }?.url
        if (url.isNullOrBlank()) return null
        return AuthScopeSnapshot(
            serverId = serverId,
            profileId = profileId,
            serverUrl = url,
            profileToken = profileToken,
            identityGeneration = identityTransitions.generation.value,
            isIdentityGenerationStamped = true,
            credentialEpoch = persistentCredentialEpoch,
            accountId = activeAccountId,
        )
    }

    /**
     * Answers for a saved account's slot only, active or not. A temporary
     * overlay's scope answers false: its deadline lives with the overlay and
     * [accessTokenExpiresWithin] already covers it.
     */
    override suspend fun accessTokenExpiresWithin(scope: AuthScopeSnapshot, marginMs: Long): Boolean {
        if (scope.credentialGenerationId != null) return false
        return mutex.withLock {
            ensureCacheMatchesRegistryLocked()
            val serverId = scope.serverId
            val accountId = scope.slotAccountId() ?: return@withLock false
            if (persistentAccessToken(serverId, accountId) == null) return@withLock false
            val expiry: Long?
            val lifetime: Long?
            if (serverId == activeServerId && accountId == activeAccountId) {
                expiry = tokenExpiryEpochMs
                lifetime = tokenLifetimeMs
            } else {
                val expiryKey = accountScopedKey(serverId, accountId, KEY_TOKEN_EXPIRY)
                val lifetimeKey = accountScopedKey(serverId, accountId, KEY_TOKEN_LIFETIME)
                expiry = if (prefs.contains(expiryKey)) prefs.getLong(expiryKey, 0L) else null
                lifetime = if (prefs.contains(lifetimeKey)) prefs.getLong(lifetimeKey, 0L) else null
            }
            shouldRefreshProactively(
                remainingMs = (expiry ?: return@withLock false) - System.currentTimeMillis(),
                lifetimeMs = lifetime,
                marginMs = marginMs,
            )
        }
    }

    override suspend fun getAccessTokenForScope(serverId: String): String? = mutex.withLock {
        persistentAccessToken(serverId, accountFor(serverId) ?: return@withLock null)
    }

    override suspend fun getRefreshTokenForScope(serverId: String): String? = mutex.withLock {
        persistentRefreshToken(serverId, accountFor(serverId) ?: return@withLock null)
    }

    /**
     * True when an identity transition has happened since [this] was captured.
     *
     * The persistent path is keyed by serverId alone, so a snapshot taken before
     * a sign-out could still read — and overwrite — the credentials issued by the
     * NEXT login on that same server. Comparing the captured identity generation
     * closes that: [saveTokens] and [clearTokens]/[invalidateSession] both run
     * inside `identityTransitions.changing`, which increments it.
     *
     * `0L` means "not captured from a live snapshot" — several call sites build a
     * scope by hand (the interceptor's refresh fallback, companion pairing,
     * remote-playback identity) and carry the default. Those keep the old
     * behaviour rather than failing closed on a generation they never recorded.
     */
    private fun AuthScopeSnapshot.credentialsReplaced(): Boolean =
        credentialEpoch != 0L && credentialEpoch != persistentCredentialEpoch

    override suspend fun getAccessTokenForScope(scope: AuthScopeSnapshot): String? =
        withScopeGeneration(scope) {
            mutex.withLock {
                val generationId = scope.credentialGenerationId
                if (generationId == null) {
                    if (!scope.isLivePersistentScope()) return@withLock null
                    persistentAccessToken(scope.serverId, scope.slotAccountId() ?: return@withLock null)
                } else {
                    temporaryScope
                        ?.takeIf { it.generationId == generationId && it.serverId == scope.serverId }
                        ?.accessToken
                }
            }
        }

    override suspend fun getRefreshTokenForScope(scope: AuthScopeSnapshot): String? =
        withScopeGeneration(scope) {
            mutex.withLock {
                val generationId = scope.credentialGenerationId
                if (generationId == null) {
                    if (!scope.isLivePersistentScope()) return@withLock null
                    persistentRefreshToken(scope.serverId, scope.slotAccountId() ?: return@withLock null)
                } else {
                    temporaryScope
                        ?.takeIf { it.generationId == generationId && it.serverId == scope.serverId }
                        ?.refreshToken
                }
            }
        }

    override suspend fun saveTokensForScope(
        serverId: String,
        accessToken: String,
        refreshToken: String,
        expiresIn: Long,
    ) {
        mutex.withLock {
            val lifetimeMs = expiresIn * 1000L
            val expiryEpochMs = System.currentTimeMillis() + lifetimeMs
            val accountId = accountFor(serverId) ?: return@withLock
            savePersistentTokens(serverId, accountId, accessToken, refreshToken, expiryEpochMs, lifetimeMs)
        }
    }

    override suspend fun saveTokensForScope(
        scope: AuthScopeSnapshot,
        accessToken: String,
        refreshToken: String,
        expiresIn: Long,
    ) {
        // A hand-built persistent scope with neither a credential epoch nor a
        // captured identity generation cannot prove which account issued the
        // refresh request. It may still be used for compatibility reads, but a
        // late response must never overwrite a same-server reauthorization.
        if (
            scope.credentialGenerationId == null &&
            scope.credentialEpoch == 0L &&
            !scope.isIdentityGenerationStamped
        ) {
            return
        }
        val save: suspend () -> Unit = {
            mutex.withLock {
                val lifetimeMs = expiresIn * 1000L
                val expiryEpochMs = System.currentTimeMillis() + lifetimeMs
                val generationId = scope.credentialGenerationId
                if (generationId == null) {
                    // A stale scope must not overwrite the credentials of the login
                    // that replaced it or a server removed while refresh was in flight.
                    if (!scope.isLivePersistentScope()) return@withLock
                    savePersistentTokens(
                        scope.serverId,
                        scope.slotAccountId() ?: return@withLock,
                        accessToken,
                        refreshToken,
                        expiryEpochMs,
                        lifetimeMs,
                    )
                    return@withLock
                }
                val temporary = temporaryScope
                    ?.takeIf { it.generationId == generationId && it.serverId == scope.serverId }
                    ?: return@withLock
                // expiresAtEpochMs is the temporary SESSION deadline and is NOT
                // renewed by refreshing the access token — overwriting it here
                // silently extended the guest session on every refresh.
                temporaryScope = temporary.copy(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    accessTokenExpiresAtEpochMs = expiryEpochMs,
                    accessTokenLifetimeMs = lifetimeMs,
                )
            }
        }
        withScopeGeneration(scope) { save() }
    }

    private suspend fun <T> withScopeGeneration(
        scope: AuthScopeSnapshot,
        block: suspend () -> T,
    ): T? {
        // For a live-stamped saved account (credentialEpoch != 0) and a
        // temporary scope, the barrier is only a serialization primitive. A
        // remote-playback overlay advances the global generation but must leave
        // the persistent scope valid; the epoch/generation-id checks decide its
        // identity. A hand-built persistent scope has no epoch, so its captured
        // identity generation is the only request provenance it can carry.
        val expectedGeneration = if (
            scope.credentialGenerationId == null &&
            scope.credentialEpoch == 0L &&
            scope.isIdentityGenerationStamped
        ) {
            scope.identityGeneration
        } else {
            identityTransitions.generation.value
        }
        return identityTransitions.withCurrentGeneration(expectedGeneration) {
            GuardedScopeValue(block())
        }?.value
    }

    /** Keeps nullable token reads compatible with the barrier's non-null result contract. */
    private data class GuardedScopeValue<T>(val value: T)

    private fun AuthScopeSnapshot.isLivePersistentScope(): Boolean =
        !credentialsReplaced() &&
            registry.entries.value.any { entry -> entry.id == serverId && entry.url == serverUrl } &&
            (accountId == null || registry.accounts.value.any { it.localAccountId == accountId && it.serverId == serverId })

    private fun persistentAccessToken(serverId: String, accountId: String): String? =
        if (serverId == activeServerId && accountId == activeAccountId) accessToken
        else prefs.getString(accountScopedKey(serverId, accountId, KEY_ACCESS_TOKEN), null)

    private fun persistentRefreshToken(serverId: String, accountId: String): String? =
        if (serverId == activeServerId && accountId == activeAccountId) refreshToken
        else prefs.getString(accountScopedKey(serverId, accountId, KEY_REFRESH_TOKEN), null)

    private fun savePersistentTokens(
        serverId: String,
        accountId: String,
        accessToken: String,
        refreshToken: String,
        expiryEpochMs: Long,
        lifetimeMs: Long,
    ) {
        prefs.edit()
            .putString(accountScopedKey(serverId, accountId, KEY_ACCESS_TOKEN), accessToken)
            .putString(accountScopedKey(serverId, accountId, KEY_REFRESH_TOKEN), refreshToken)
            .putLong(accountScopedKey(serverId, accountId, KEY_TOKEN_EXPIRY), expiryEpochMs)
            .putLong(accountScopedKey(serverId, accountId, KEY_TOKEN_LIFETIME), lifetimeMs)
            .apply()
        if (serverId == activeServerId && accountId == activeAccountId) {
            this.accessToken = accessToken
            this.refreshToken = refreshToken
            this.tokenExpiryEpochMs = expiryEpochMs
            this.tokenLifetimeMs = lifetimeMs
        }
    }

    // ---- Internal cache management ----

    private suspend fun reloadCacheLocked() {
        mutex.withLock { reloadCacheUnsynchronized() }
    }

    private fun reloadCacheUnsynchronized() {
        val serverId = activeServerId
        val accountId = activeAccountId
        if (serverId == null || accountId == null) {
            accessToken = null
            refreshToken = null
            tokenExpiryEpochMs = null
            tokenLifetimeMs = null
            profileId = null
            profileToken = null
            return
        }
        fun key(baseKey: String) = accountScopedKey(serverId, accountId, baseKey)
        accessToken = prefs.getString(key(KEY_ACCESS_TOKEN), null)
        refreshToken = prefs.getString(key(KEY_REFRESH_TOKEN), null)
        val expiryKey = key(KEY_TOKEN_EXPIRY)
        tokenExpiryEpochMs = if (prefs.contains(expiryKey)) prefs.getLong(expiryKey, 0L) else null
        val lifetimeKey = key(KEY_TOKEN_LIFETIME)
        tokenLifetimeMs = if (prefs.contains(lifetimeKey)) prefs.getLong(lifetimeKey, 0L) else null
        profileId = prefs.getString(key(KEY_PROFILE_ID), null)
        profileToken = prefs.getString(key(KEY_PROFILE_TOKEN), null)
    }

    internal companion object {
        const val KEY_LOGIN_ID = "login_authority_id"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"
        const val KEY_TOKEN_EXPIRY = "token_expiry_epoch_ms"
        const val KEY_TOKEN_LIFETIME = "token_lifetime_ms"
        const val KEY_PROFILE_ID = "profile_id"
        const val KEY_PROFILE_TOKEN = "profile_token"
        // Retained only so [AndroidServerRegistry.migrateLegacyIfNeeded] can
        // strip the pre-multi-server unprefixed key off disk.
        const val KEY_SERVER_URL = "server_url"
    }

    private fun currentScopeMatchesLocked(
        scope: AuthScopeSnapshot,
        expectedGeneration: Long,
    ): Boolean {
        if (identityTransitions.generation.value != expectedGeneration) return false
        val temporary = temporaryScope
        val generationId = scope.credentialGenerationId
        if (generationId != null) {
            return temporary?.generationId == generationId &&
                temporary.serverId == scope.serverId &&
                temporary.serverUrl == scope.serverUrl
        }
        if (temporary != null || activeServerId != scope.serverId) return false
        if (scope.accountId != null && scope.accountId != activeAccountId) return false
        return registry.entries.value
            .firstOrNull { it.id == scope.serverId }
            ?.url == scope.serverUrl
    }

    /** Resolved under the identity-mutation mutex immediately before privacy gates run. */
    private suspend fun currentSignOutTarget(): IdentityTransitionTarget = mutex.withLock {
        ensureCacheMatchesRegistryLocked()
        val temporary = temporaryScope
        IdentityTransitionTarget(
            serverId = temporary?.serverId ?: activeServerId,
            purgesPersistentIdentity = temporary == null,
        )
    }

    override suspend fun invalidateSessionForScope(scope: AuthScopeSnapshot): Boolean =
        tokenWriteMutex.withLock {
            val matchesBeforeTransition = mutex.withLock {
                ensureCacheMatchesRegistryLocked()
                currentScopeMatchesLocked(
                    scope = scope,
                    expectedGeneration = scope.identityGeneration,
                )
            }
            if (!matchesBeforeTransition) return@withLock false

            identityTransitions.changing(
                kind = IdentityTransitionKind.SIGN_OUT,
                target = { IdentityTransitionTarget(serverId = scope.serverId) },
            ) {
                mutex.withLock {
                    ensureCacheMatchesRegistryLocked()
                    // `changing` increments the generation before entering this
                    // block. Any intervening identity transition therefore
                    // makes this value larger and the mutation fails closed.
                    if (
                        !currentScopeMatchesLocked(
                            scope = scope,
                            expectedGeneration = scope.identityGeneration + 1,
                        )
                    ) {
                        return@withLock false
                    }
                    val expiredTemporary = temporaryScope?.generationId
                    clearCurrentScopeLocked(expired = true)
                    if (expiredTemporary == null) {
                        _sessionExpired.tryEmit(Unit)
                    } else {
                        _temporarySessionExpired.tryEmit(expiredTemporary)
                    }
                    true
                }
            }
        }
}
