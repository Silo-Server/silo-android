package org.siloserver.silo.network

import android.content.SharedPreferences
import org.siloserver.silo.network.AndroidServerRegistry.Companion.serverScopedKey
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
 * "<serverId>.<baseKey>" (see [AndroidServerRegistry.serverScopedKey]) so each
 * saved server keeps its own credentials. The active server is whatever the
 * [ServerRegistry] currently has selected — this manager mirrors that
 * selection into its in-memory cache and listens for switches so subsequent
 * reads target the right slot.
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
) : TokenManager {

    private val mutex = Mutex()
    private val tokenWriteMutex = Mutex()
    private val scope = CoroutineScope(Dispatchers.Default)

    private var activeServerId: String? = registry.activeServerId.value

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

    init {
        // Load initial cache for whichever server the registry made active
        // (post-migration / from disk).
        runBlocking { reloadCacheLocked() }

        // Observe future registry switches — UI flows that call
        // ServerRegistry.switchTo() outside switchActiveServer() are still
        // picked up here. The cache is flushed under the same mutex used by
        // every read/write so requests in flight see consistent state.
        scope.launch {
            registry.activeServerId.collectLatest { id ->
                mutex.withLock {
                    if (id != activeServerId) {
                        activeServerId = id
                        persistentCredentialEpoch += 1
                        reloadCacheUnsynchronized()
                    }
                }
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
        if (liveId != activeServerId) {
            activeServerId = liveId
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

    override suspend fun replaceAccountSession(
        serverId: String?,
        serverUrl: String?,
        accessToken: String,
        refreshToken: String,
        expiresIn: Long,
        profileId: String?,
        profileToken: String?,
    ) {
        val targetServerId = serverId
            ?: serverUrl?.let { registry.addOrUpdate(it) }
            ?: registry.activeServerId.value
            ?: error("account replacement requires a registered server")
        val androidRegistry = registry as? AndroidServerRegistry
            ?: error("persistent account replacement requires AndroidServerRegistry")
        tokenWriteMutex.withLock {
            identityTransitions.changing(
                kind = IdentityTransitionKind.ACCOUNT_REPLACE,
                target = {
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
                    androidRegistry.commitAccountReplacement(
                        serverId = targetServerId,
                        profileId = profileId,
                        profileToken = profileToken,
                        accessToken = accessToken,
                        refreshToken = refreshToken,
                        expiryEpochMs = expiryEpochMs,
                        lifetimeMs = lifetimeMs,
                    )
                    activeServerId = targetServerId
                    this.profileId = profileId
                    this.profileToken = profileToken
                    this.accessToken = accessToken
                    this.refreshToken = refreshToken
                    tokenExpiryEpochMs = expiryEpochMs
                    tokenLifetimeMs = lifetimeMs
                    persistentCredentialEpoch += 1
                    afterAccountSessionCommit()
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
            this.accessToken = accessToken
            this.refreshToken = refreshToken
            val lifetimeMs = expiresIn * 1000L
            val expiryEpochMs = System.currentTimeMillis() + lifetimeMs
            this.tokenExpiryEpochMs = expiryEpochMs
            this.tokenLifetimeMs = lifetimeMs
            persistentCredentialEpoch += 1
            prefs.edit()
                .putString(serverScopedKey(serverId, KEY_ACCESS_TOKEN), accessToken)
                .putString(serverScopedKey(serverId, KEY_REFRESH_TOKEN), refreshToken)
                .putLong(serverScopedKey(serverId, KEY_TOKEN_EXPIRY), expiryEpochMs)
                .putLong(serverScopedKey(serverId, KEY_TOKEN_LIFETIME), lifetimeMs)
                .apply()
        }
    }

    override suspend fun clearTokens() {
        tokenWriteMutex.withLock {
            identityTransitions.changing(
                kind = IdentityTransitionKind.SIGN_OUT,
                target = { currentSignOutTarget() },
            ) {
                mutex.withLock { clearCurrentScopeLocked() }
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
                    val wasTemporary = temporaryScope != null
                    clearCurrentScopeLocked()
                    if (!wasTemporary) _sessionExpired.tryEmit(Unit)
                }
            }
        }
    }

    private suspend fun clearCurrentScopeLocked() {
        if (temporaryScope != null) {
            temporaryScope = null
        } else {
            clearPersistentTokensLocked()
        }
    }

    private suspend fun clearPersistentTokensLocked() {
        val serverId = activeServerId
        var committedSignOut = false
        if (serverId != null) {
            val androidRegistry = registry as? AndroidServerRegistry
            if (androidRegistry != null) {
                androidRegistry.commitAccountSignOut(serverId)
            } else {
                val editor = prefs.edit()
                    .remove(serverScopedKey(serverId, KEY_ACCESS_TOKEN))
                    .remove(serverScopedKey(serverId, KEY_REFRESH_TOKEN))
                    .remove(serverScopedKey(serverId, KEY_TOKEN_EXPIRY))
                    .remove(serverScopedKey(serverId, KEY_TOKEN_LIFETIME))
                    .remove(serverScopedKey(serverId, KEY_PROFILE_ID))
                    .remove(serverScopedKey(serverId, KEY_PROFILE_TOKEN))
                check(editor.commit()) { "unable to durably sign out account" }
                registry.signOut(serverId)
            }
            committedSignOut = true
        }
        persistentCredentialEpoch += 1
        accessToken = null
        refreshToken = null
        tokenExpiryEpochMs = null
        tokenLifetimeMs = null
        profileId = null
        profileToken = null
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
            if (this.profileId == profileId) return
            this.profileId = profileId
            val key = serverScopedKey(serverId, KEY_PROFILE_ID)
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
            if (this.profileToken == token) return
            this.profileToken = token
            val key = serverScopedKey(serverId, KEY_PROFILE_TOKEN)
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
            if (this.profileId == profileId && this.profileToken == profileToken) return
            this.profileId = profileId
            this.profileToken = profileToken
            val idKey = serverScopedKey(serverId, KEY_PROFILE_ID)
            val tokenKey = serverScopedKey(serverId, KEY_PROFILE_TOKEN)
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
                mutex.withLock { clearCurrentScopeLocked() }
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

    // ---- Scoped auth (pinned background requests) ----

    override suspend fun snapshotCurrentScope(): AuthScopeSnapshot? = mutex.withLock {
        temporaryScope?.let { scope ->
            return@withLock AuthScopeSnapshot(
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
        val serverId = activeServerId ?: return@withLock null
        // Resolve the URL for *this* serverId from the registry entries so the
        // snapshot is internally consistent. Do NOT fall back to activeEntry —
        // if the captured serverId isn't a known, non-blank-URL entry, a pinned
        // request could be sent to the wrong server (or skip the rewrite and hit
        // localhost). Return null instead so the drain simply no-ops this pass.
        val url = registry.entries.value.firstOrNull { it.id == serverId }?.url
        if (url.isNullOrBlank()) return@withLock null
        AuthScopeSnapshot(
            serverId = serverId,
            profileId = profileId,
            serverUrl = url,
            profileToken = profileToken,
            identityGeneration = identityTransitions.generation.value,
            isIdentityGenerationStamped = true,
            credentialEpoch = persistentCredentialEpoch,
        )
    }

    override suspend fun getAccessTokenForScope(serverId: String): String? = mutex.withLock {
        if (serverId == activeServerId) accessToken
        else prefs.getString(serverScopedKey(serverId, KEY_ACCESS_TOKEN), null)
    }

    override suspend fun getRefreshTokenForScope(serverId: String): String? = mutex.withLock {
        if (serverId == activeServerId) refreshToken
        else prefs.getString(serverScopedKey(serverId, KEY_REFRESH_TOKEN), null)
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
                    persistentAccessToken(scope.serverId)
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
                    persistentRefreshToken(scope.serverId)
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
            savePersistentTokens(serverId, accessToken, refreshToken, expiryEpochMs, lifetimeMs)
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
            registry.entries.value.any { entry -> entry.id == serverId && entry.url == serverUrl }

    private fun persistentAccessToken(serverId: String): String? =
        if (serverId == activeServerId) accessToken
        else prefs.getString(serverScopedKey(serverId, KEY_ACCESS_TOKEN), null)

    private fun persistentRefreshToken(serverId: String): String? =
        if (serverId == activeServerId) refreshToken
        else prefs.getString(serverScopedKey(serverId, KEY_REFRESH_TOKEN), null)

    private fun savePersistentTokens(
        serverId: String,
        accessToken: String,
        refreshToken: String,
        expiryEpochMs: Long,
        lifetimeMs: Long,
    ) {
        prefs.edit()
            .putString(serverScopedKey(serverId, KEY_ACCESS_TOKEN), accessToken)
            .putString(serverScopedKey(serverId, KEY_REFRESH_TOKEN), refreshToken)
            .putLong(serverScopedKey(serverId, KEY_TOKEN_EXPIRY), expiryEpochMs)
            .putLong(serverScopedKey(serverId, KEY_TOKEN_LIFETIME), lifetimeMs)
            .apply()
        if (serverId == activeServerId) {
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
        if (serverId == null) {
            accessToken = null
            refreshToken = null
            tokenExpiryEpochMs = null
            tokenLifetimeMs = null
            profileId = null
            profileToken = null
            return
        }
        accessToken = prefs.getString(serverScopedKey(serverId, KEY_ACCESS_TOKEN), null)
        refreshToken = prefs.getString(serverScopedKey(serverId, KEY_REFRESH_TOKEN), null)
        val expiryKey = serverScopedKey(serverId, KEY_TOKEN_EXPIRY)
        tokenExpiryEpochMs = if (prefs.contains(expiryKey)) prefs.getLong(expiryKey, 0L) else null
        val lifetimeKey = serverScopedKey(serverId, KEY_TOKEN_LIFETIME)
        tokenLifetimeMs = if (prefs.contains(lifetimeKey)) prefs.getLong(lifetimeKey, 0L) else null
        profileId = prefs.getString(serverScopedKey(serverId, KEY_PROFILE_ID), null)
        profileToken = prefs.getString(serverScopedKey(serverId, KEY_PROFILE_TOKEN), null)
    }

    internal companion object {
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
                    val wasTemporary = temporaryScope != null
                    clearCurrentScopeLocked()
                    if (!wasTemporary) _sessionExpired.tryEmit(Unit)
                    true
                }
            }
        }
}
