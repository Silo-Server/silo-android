package org.siloserver.silo.network

import android.content.SharedPreferences
import android.util.Base64
import java.net.URI
import org.siloserver.silo.model.server.AccountEntry
import org.siloserver.silo.model.server.CachedProfile
import org.siloserver.silo.model.server.ServerContract
import org.siloserver.silo.model.server.ServerEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Android implementation of [ServerRegistry] backed by EncryptedSharedPreferences.
 *
 * State is serialized as a single JSON blob under [KEY_REGISTRY_STATE]. The same
 * preference file ([PREFS_NAME]) holds the per-server token slots used by
 * [EncryptedTokenManagerImpl] — sharing the file means a single MasterKey lookup
 * and atomic edits when needed.
 *
 * On first construction, [migrateLegacyIfNeeded] folds any pre-multi-server
 * encrypted tokens (the unprefixed access_token/refresh_token/profile_id/
 * profile_token/server_url keys) into a fresh [ServerEntry] keyed by the
 * derived server id.
 *
 * Each server holds zero or more [AccountEntry]s. Their credentials live under
 * "<serverId>.<localAccountId>.<key>" ([accountScopedKey]); [migrateAccountsIfNeededLocked]
 * moves the one-account-per-server slots ("<serverId>.<key>") there once.
 *
 * The constructor is intentionally synchronous: [org.siloserver.silo.android.MainActivity]
 * calls [activeServerId.value] inside a `runBlocking { resolveStartDestination() }`
 * before composition, so the registry must be fully loaded by the time Koin
 * hands it back.
 */
class AndroidServerRegistry(
    private val prefs: SharedPreferences,
    private val identityTransitions: IdentityTransitionBarrier = DefaultIdentityTransitionBarrier(),
    private val commitEditor: (SharedPreferences.Editor) -> Boolean = SharedPreferences.Editor::commit,
    private val afterServerRemovalCommit: () -> Unit = {},
) : ServerRegistry {

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _entries = MutableStateFlow<List<ServerEntry>>(emptyList())
    override val entries: StateFlow<List<ServerEntry>> = _entries.asStateFlow()

    private val _activeServerId = MutableStateFlow<String?>(null)
    override val activeServerId: StateFlow<String?> = _activeServerId.asStateFlow()

    private val _activeEntry = MutableStateFlow<ServerEntry?>(null)
    override val activeEntry: StateFlow<ServerEntry?> = _activeEntry.asStateFlow()

    private val _accounts = MutableStateFlow<List<AccountEntry>>(emptyList())
    override val accounts: StateFlow<List<AccountEntry>> = _accounts.asStateFlow()

    private val _activeAccountId = MutableStateFlow<String?>(null)
    override val activeAccountId: StateFlow<String?> = _activeAccountId.asStateFlow()

    private val _activeAccount = MutableStateFlow<AccountEntry?>(null)
    override val activeAccount: StateFlow<AccountEntry?> = _activeAccount.asStateFlow()

    /** The last state applied, with persisted fields only (no derived account fields on entries). */
    private var state = RegistryState()

    @Volatile
    override var stateLoadFailed: Boolean = false
        private set

    init {
        // Synchronous load + migration so MainActivity.runBlocking can read state.
        runBlocking {
            mutex.withLock {
                val loaded = loadStateLocked() ?: RegistryState()
                val migrated = migrateLegacyIfNeededLocked(loaded)
                applyStateLocked(migrateAccountsIfNeededLocked(migrated))
            }
        }
    }

    override suspend fun addOrUpdate(url: String, fetchedName: String?): String {
        val normalized = normalizeUrl(url)
        val id = idFor(normalized)
        mutex.withLock {
            val existing = _entries.value.firstOrNull { it.id == id }
            val entry = if (existing != null) {
                existing.copy(
                    url = normalized,
                    fetchedName = fetchedName ?: existing.fetchedName,
                    lastUsedAtEpochMs = System.currentTimeMillis(),
                )
            } else {
                ServerEntry(
                    id = id,
                    url = normalized,
                    fetchedName = fetchedName,
                    lastUsedAtEpochMs = System.currentTimeMillis(),
                )
            }
            val newEntries = _entries.value.filter { it.id != id } + entry
            persistAndApplyLocked(newEntries, _activeServerId.value)
        }
        return id
    }

    override suspend fun rename(serverId: String, userOverrideName: String?) {
        mutex.withLock {
            val updated = _entries.value.map { entry ->
                if (entry.id == serverId) {
                    entry.copy(userOverrideName = userOverrideName?.trim()?.takeIf { it.isNotBlank() })
                } else entry
            }
            persistAndApplyLocked(updated, _activeServerId.value)
        }
    }

    override suspend fun setContract(serverId: String, contract: ServerContract) {
        mutex.withLock {
            val updated = _entries.value.map { entry ->
                if (entry.id == serverId) entry.copy(contract = contract) else entry
            }
            persistAndApplyLocked(updated, _activeServerId.value)
        }
    }

    override suspend fun setVerifiedServerId(serverId: String, verifiedServerId: String?) {
        mutex.withLock {
            val current = _entries.value.firstOrNull { it.id == serverId } ?: return
            if (current.verifiedServerId == verifiedServerId) return
            val updated = _entries.value.map { entry ->
                if (entry.id == serverId) entry.copy(verifiedServerId = verifiedServerId) else entry
            }
            persistAndApplyLocked(updated, _activeServerId.value)
        }
    }

    override suspend fun setFetchedName(serverId: String, fetchedName: String?) {
        mutex.withLock {
            val updated = _entries.value.map { entry ->
                if (entry.id == serverId) entry.copy(fetchedName = fetchedName) else entry
            }
            persistAndApplyLocked(updated, _activeServerId.value)
        }
    }

    override suspend fun setProfileId(serverId: String, profileId: String?) {
        mutex.withLock {
            val accountId = _entries.value.firstOrNull { it.id == serverId }?.accountId ?: return
            updateAccountLocked(accountId) { it.copy(profileId = profileId) }
        }
    }

    override suspend fun setAccountProfileId(accountId: String, profileId: String?) {
        mutex.withLock { updateAccountLocked(accountId) { it.copy(profileId = profileId) } }
    }

    override suspend fun setCachedProfiles(accountId: String, profiles: List<CachedProfile>) {
        mutex.withLock { updateAccountLocked(accountId) { it.copy(cachedProfiles = profiles) } }
    }

    override suspend fun setAccountIdentity(accountId: String, serverUserId: String?, username: String?) {
        mutex.withLock {
            updateAccountLocked(accountId) {
                it.copy(
                    serverUserId = serverUserId?.takeIf(String::isNotBlank) ?: it.serverUserId,
                    username = username?.takeIf(String::isNotBlank) ?: it.username,
                )
            }
        }
    }

    private fun updateAccountLocked(accountId: String, transform: (AccountEntry) -> AccountEntry) {
        val current = state.accounts.firstOrNull { it.localAccountId == accountId } ?: return
        val updated = transform(current)
        if (updated == current) return
        persistAndApplyLocked(state.copy(accounts = state.accounts.map { if (it.localAccountId == accountId) updated else it }))
    }

    override suspend fun remove(serverId: String) {
        identityTransitions.changing(
            kind = IdentityTransitionKind.SERVER_REMOVE,
            target = {
                IdentityTransitionTarget(
                    serverId = serverId,
                    affectsCurrentIdentity = _activeServerId.value == serverId,
                )
            },
        ) {
            mutex.withLock {
                // Wipe every key in this server's "<serverId>." namespace before
                // dropping the entry, otherwise they'd linger encrypted on disk
                // indefinitely. Sweeping the prefix rather than a fixed key list
                // keeps the purge correct as per-server slots are added: ids are
                // base64url so they never contain the '.' separator, and no other
                // server's key can match this prefix.
                val scopePrefix = serverScopedKey(serverId, "")
                val editor = prefs.edit()
                prefs.all.keys
                    .filter { it.startsWith(scopePrefix) }
                    .forEach { editor.remove(it) }

                val updated = state.entries.filter { it.id != serverId }
                val newActive = if (_activeServerId.value == serverId) {
                    updated.maxByOrNull { it.lastUsedAtEpochMs }?.id
                } else _activeServerId.value
                val resolvedActive = newActive?.takeIf { id -> updated.any { it.id == id } }
                    ?: updated.maxByOrNull { it.lastUsedAtEpochMs }?.id
                val remainingAccounts = state.accounts.filter { it.serverId != serverId }
                val next = RegistryState(
                    entries = updated,
                    activeServerId = resolvedActive,
                    accounts = remainingAccounts,
                    activeAccountId = if (resolvedActive == _activeServerId.value) {
                        state.activeAccountId
                    } else {
                        mostRecentAccount(remainingAccounts, resolvedActive)
                    },
                )
                editor.putString(KEY_REGISTRY_STATE, json.encodeToString(next))
                check(commitEditor(editor)) { "unable to durably remove server" }
                applyStateLocked(next)
                afterServerRemovalCommit()
            }
        }
    }

    override suspend fun signOut(serverId: String) {
        mutex.withLock {
            val accountId = _entries.value.firstOrNull { it.id == serverId }?.accountId ?: return
            updateAccountLocked(accountId) { it.copy(profileId = null) }
        }
    }

    override suspend fun switchTo(serverId: String) {
        identityTransitions.changing(IdentityTransitionKind.SERVER_SWITCH) {
            mutex.withLock {
                if (state.entries.none { it.id == serverId }) return@withLock
                val updated = state.entries.map { entry ->
                    if (entry.id == serverId) {
                        entry.copy(lastUsedAtEpochMs = System.currentTimeMillis())
                    } else entry
                }
                persistAndApplyLocked(updated, serverId)
            }
        }
    }

    override suspend fun switchToAccount(serverId: String, accountId: String?) {
        identityTransitions.changing(IdentityTransitionKind.SERVER_SWITCH) {
            mutex.withLock {
                if (state.entries.none { it.id == serverId }) return@withLock
                if (accountId != null && state.accounts.none { it.localAccountId == accountId && it.serverId == serverId }) {
                    return@withLock
                }
                val now = System.currentTimeMillis()
                persistAndApplyLocked(
                    state.copy(
                        entries = state.entries.map { if (it.id == serverId) it.copy(lastUsedAtEpochMs = now) else it },
                        accounts = state.accounts.map {
                            if (it.localAccountId == accountId) it.copy(lastUsedAtEpochMs = now) else it
                        },
                        activeServerId = serverId,
                        activeAccountId = accountId,
                    ),
                )
            }
        }
    }

    /**
     * Picks the account a sign-in on [serverId] lands in, then commits it as
     * the active account with its credentials, in the one SharedPreferences
     * transaction available to both owners. In-memory registry state is
     * published only after the synchronous disk commit. Returns the account id.
     *
     * The account is, in order: the saved account with the same
     * [serverUserId] (signing in as a saved account refreshes it rather than
     * adding a duplicate); [reauthAccountId] when its server user is unknown
     * or the same; otherwise a new account.
     */
    internal suspend fun commitAccountReplacement(
        serverId: String,
        reauthAccountId: String?,
        serverUserId: String?,
        username: String?,
        profileId: String?,
        profileToken: String?,
        accessToken: String,
        refreshToken: String,
        expiryEpochMs: Long,
        lifetimeMs: Long,
    ): String = mutex.withLock {
        check(state.entries.any { it.id == serverId }) { "account replacement target is not registered" }
        val onServer = state.accounts.filter { it.serverId == serverId }
        val existing = serverUserId?.let { uid -> onServer.firstOrNull { it.serverUserId == uid } }
            ?: reauthAccountId?.let { id ->
                onServer.firstOrNull {
                    it.localAccountId == id && (it.serverUserId == null || serverUserId == null || it.serverUserId == serverUserId)
                }
            }
        val now = System.currentTimeMillis()
        val serverEntry = state.entries.first { it.id == serverId }
        val account = (existing ?: AccountEntry(
            localAccountId = java.util.UUID.randomUUID().toString(),
            serverId = serverId,
            addedAtEpochMs = now,
            adoptsLegacyData = serverEntry.legacyDataUnclaimed,
        )).copy(
            serverUserId = serverUserId?.takeIf(String::isNotBlank) ?: existing?.serverUserId,
            username = username?.takeIf(String::isNotBlank) ?: existing?.username,
            profileId = profileId,
            lastUsedAtEpochMs = now,
            needsSignIn = false,
        )
        val accountId = account.localAccountId
        val next = state.copy(
            entries = state.entries.map {
                if (it.id == serverId) it.copy(lastUsedAtEpochMs = now, legacyDataUnclaimed = false) else it
            },
            accounts = state.accounts.filter { it.localAccountId != accountId } + account,
            activeServerId = serverId,
            activeAccountId = accountId,
        )
        val editor = prefs.edit()
            .putString(KEY_REGISTRY_STATE, json.encodeToString(next))
            .putString(accountScopedKey(serverId, accountId, EncryptedTokenManagerImpl.KEY_LOGIN_ID), java.util.UUID.randomUUID().toString())
            .putString(accountScopedKey(serverId, accountId, EncryptedTokenManagerImpl.KEY_ACCESS_TOKEN), accessToken)
            .putString(accountScopedKey(serverId, accountId, EncryptedTokenManagerImpl.KEY_REFRESH_TOKEN), refreshToken)
            .putLong(accountScopedKey(serverId, accountId, EncryptedTokenManagerImpl.KEY_TOKEN_EXPIRY), expiryEpochMs)
            .putLong(accountScopedKey(serverId, accountId, EncryptedTokenManagerImpl.KEY_TOKEN_LIFETIME), lifetimeMs)
        val profileIdKey = accountScopedKey(serverId, accountId, EncryptedTokenManagerImpl.KEY_PROFILE_ID)
        val profileTokenKey = accountScopedKey(serverId, accountId, EncryptedTokenManagerImpl.KEY_PROFILE_TOKEN)
        if (profileId == null) editor.remove(profileIdKey) else editor.putString(profileIdKey, profileId)
        if (profileToken == null) editor.remove(profileTokenKey) else editor.putString(profileTokenKey, profileToken)
        check(commitEditor(editor)) { "unable to durably replace account session" }
        applyStateLocked(next)
        accountId
    }

    /**
     * Ends [accountId]'s session. A sign-out ([expired] false) deletes the
     * account and every credential key it had; its local data then belongs to
     * no account and is purged by the account-data purger. An expiry keeps the
     * account, its remembered profile and its data, and marks it
     * [AccountEntry.needsSignIn].
     */
    internal suspend fun commitAccountSignOut(serverId: String, accountId: String, expired: Boolean) {
        mutex.withLock {
            check(state.entries.any { it.id == serverId }) { "sign-out target is not registered" }
            val next = if (expired) {
                state.copy(
                    accounts = state.accounts.map {
                        if (it.localAccountId == accountId) it.copy(needsSignIn = true) else it
                    },
                )
            } else {
                state.copy(
                    accounts = state.accounts.filter { it.localAccountId != accountId },
                    activeAccountId = state.activeAccountId.takeIf { it != accountId },
                )
            }
            val editor = prefs.edit().putString(KEY_REGISTRY_STATE, json.encodeToString(next))
            if (expired) {
                listOf(
                    EncryptedTokenManagerImpl.KEY_LOGIN_ID,
                    EncryptedTokenManagerImpl.KEY_ACCESS_TOKEN,
                    EncryptedTokenManagerImpl.KEY_REFRESH_TOKEN,
                    EncryptedTokenManagerImpl.KEY_TOKEN_EXPIRY,
                    EncryptedTokenManagerImpl.KEY_TOKEN_LIFETIME,
                    EncryptedTokenManagerImpl.KEY_PROFILE_TOKEN,
                ).forEach { editor.remove(accountScopedKey(serverId, accountId, it)) }
            } else {
                val slotPrefix = accountScopedKey(serverId, accountId, "")
                prefs.all.keys.filter { it.startsWith(slotPrefix) }.forEach { editor.remove(it) }
            }
            check(commitEditor(editor)) { "unable to durably sign out account" }
            applyStateLocked(next)
        }
    }

    override suspend fun touchActive() {
        mutex.withLock {
            val activeId = _activeServerId.value ?: return
            val updated = state.entries.map { entry ->
                if (entry.id == activeId) {
                    entry.copy(lastUsedAtEpochMs = System.currentTimeMillis())
                } else entry
            }
            persistAndApplyLocked(updated, activeId)
        }
    }

    // ---- Persistence ----

    @Serializable
    private data class RegistryState(
        val entries: List<ServerEntry> = emptyList(),
        val activeServerId: String? = null,
        val accounts: List<AccountEntry> = emptyList(),
        val activeAccountId: String? = null,
    )

    private fun loadStateLocked(): RegistryState? {
        val raw = prefs.getString(KEY_REGISTRY_STATE, null) ?: return null
        val decoded = runCatching { json.decodeFromString<RegistryState>(raw) }.getOrNull()
        // A blob that exists but will not decode is NOT the same as no blob.
        // Both used to collapse to an empty registry, which was survivable while
        // "no servers" only meant "sign in again" — the rows and downloaded
        // bytes stayed on disk under an id derived from the URL, so re-adding
        // the server restored them. It stopped being survivable when
        // OrphanedServerDataPurger started deleting every server present in the
        // database but absent from the registry: one unparseable blob would
        // permanently erase downloads, resume positions and queued outbox ops.
        if (decoded == null) {
            android.util.Log.e(
                "AndroidServerRegistry",
                "registry state failed to decode; suppressing destructive cleanup",
            )
            stateLoadFailed = true
        }
        return decoded
    }

    /**
     * Publishes [next]. Each published [ServerEntry] carries its server's
     * current account and that account's profile ([ServerEntry.accountId],
     * [ServerEntry.profileId]), so observers of a server entry see account and
     * profile switches too.
     */
    private fun applyStateLocked(next: RegistryState) {
        val resolvedActive = next.activeServerId?.takeIf { id ->
            next.entries.any { it.id == id }
        } ?: next.entries.maxByOrNull { it.lastUsedAtEpochMs }?.id
        val resolvedAccount = if (resolvedActive == next.activeServerId) {
            next.activeAccountId?.takeIf { id -> next.accounts.any { it.localAccountId == id && it.serverId == resolvedActive } }
        } else {
            mostRecentAccount(next.accounts, resolvedActive)
        }
        state = next.copy(activeServerId = resolvedActive, activeAccountId = resolvedAccount)
        val derived = next.entries.map { entry ->
            val accountId = if (entry.id == resolvedActive) resolvedAccount else mostRecentAccount(next.accounts, entry.id)
            val account = next.accounts.firstOrNull { it.localAccountId == accountId }
            entry.copy(accountId = account?.localAccountId, profileId = account?.profileId)
        }
        _accounts.value = next.accounts
        _entries.value = derived
        _activeServerId.value = resolvedActive
        _activeAccountId.value = resolvedAccount
        _activeAccount.value = next.accounts.firstOrNull { it.localAccountId == resolvedAccount }
        _activeEntry.value = derived.firstOrNull { it.id == resolvedActive }
    }

    private fun persistAndApplyLocked(entries: List<ServerEntry>, activeServerId: String?) {
        val accountId = if (activeServerId == state.activeServerId) {
            state.activeAccountId
        } else {
            mostRecentAccount(state.accounts, activeServerId)
        }
        persistAndApplyLocked(state.copy(entries = entries, activeServerId = activeServerId, activeAccountId = accountId))
    }

    private fun persistAndApplyLocked(next: RegistryState) {
        prefs.edit().putString(KEY_REGISTRY_STATE, json.encodeToString(next)).apply()
        applyStateLocked(next)
    }

    private fun mostRecentAccount(accounts: List<AccountEntry>, serverId: String?): String? =
        serverId?.let { id -> accounts.filter { it.serverId == id }.maxByOrNull { it.lastUsedAtEpochMs }?.localAccountId }

    // ---- One-account-per-server -> accounts migration ----

    /**
     * Turns each server's saved session into an [AccountEntry] with a new
     * local id, once, before anything reads the registry. Needs no network:
     * the account starts without a server user id, which the first verified
     * `/me` fills in. The account adopts the server's local data
     * ([AccountEntry.adoptsLegacyData]). A server with no session keeps any
     * old data unclaimed for the next account that signs in there
     * ([ServerEntry.legacyDataUnclaimed]), as sign-out used to keep downloads.
     *
     * The old slots are deleted only after the new records read back. A failed
     * write leaves the old layout untouched and the flag unset, so the next
     * launch tries again; nothing outside this function reads the old slots.
     */
    private fun migrateAccountsIfNeededLocked(loaded: RegistryState): RegistryState {
        if (prefs.getBoolean(KEY_ACCOUNTS_MIGRATED, false)) return loaded
        val now = System.currentTimeMillis()
        val accounts = loaded.accounts.toMutableList()
        val copies = mutableMapOf<String, Any>()
        val stale = mutableListOf<String>()
        val stored = prefs.all
        val entries = loaded.entries.map { entry ->
            val values = LEGACY_SLOT_KEYS.mapNotNull { key ->
                stored[serverScopedKey(entry.id, key)]?.let { key to it }
            }.toMap()
            LEGACY_SLOT_KEYS.forEach { stale += serverScopedKey(entry.id, it) }
            val hasSession = !(values[EncryptedTokenManagerImpl.KEY_ACCESS_TOKEN] as? String).isNullOrBlank() ||
                !(values[EncryptedTokenManagerImpl.KEY_REFRESH_TOKEN] as? String).isNullOrBlank()
            if (!hasSession || accounts.any { it.serverId == entry.id }) {
                return@map entry.copy(legacyDataUnclaimed = accounts.none { it.serverId == entry.id })
            }
            val accountId = java.util.UUID.randomUUID().toString()
            accounts += AccountEntry(
                localAccountId = accountId,
                serverId = entry.id,
                profileId = values[EncryptedTokenManagerImpl.KEY_PROFILE_ID] as? String,
                addedAtEpochMs = now,
                lastUsedAtEpochMs = entry.lastUsedAtEpochMs,
                adoptsLegacyData = true,
            )
            values.forEach { (key, value) -> copies[accountScopedKey(entry.id, accountId, key)] = value }
            entry
        }
        val migrated = loaded.copy(
            entries = entries,
            accounts = accounts,
            activeAccountId = loaded.activeAccountId ?: mostRecentAccount(accounts, loaded.activeServerId),
        )
        val write = prefs.edit().putString(KEY_REGISTRY_STATE, json.encodeToString(migrated))
        copies.forEach { (key, value) ->
            when (value) {
                is String -> write.putString(key, value)
                is Long -> write.putLong(key, value)
                is Int -> write.putInt(key, value)
                is Boolean -> write.putBoolean(key, value)
            }
        }
        if (!commitEditor(write)) {
            android.util.Log.e("AndroidServerRegistry", "account migration write failed; keeping the old layout")
            return loaded
        }
        val readBack = runCatching {
            json.decodeFromString<RegistryState>(prefs.getString(KEY_REGISTRY_STATE, null).orEmpty())
        }.getOrNull()
        val written = prefs.all
        if (readBack?.accounts?.size != accounts.size || copies.any { (key, value) -> written[key] != value }) {
            android.util.Log.e("AndroidServerRegistry", "account migration did not read back; keeping the old layout")
            return loaded
        }
        val cleanup = prefs.edit().putBoolean(KEY_ACCOUNTS_MIGRATED, true)
        stale.forEach { cleanup.remove(it) }
        if (!commitEditor(cleanup)) {
            android.util.Log.e("AndroidServerRegistry", "account migration cleanup failed; retrying next launch")
        }
        return migrated
    }

    // ---- Legacy single-server migration ----

    private fun migrateLegacyIfNeededLocked(loaded: RegistryState): RegistryState {
        if (prefs.getBoolean(KEY_MIGRATED, false)) {
            return loaded
        }
        if (loaded.entries.isNotEmpty()) {
            prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
            return loaded
        }

        // Legacy unprefixed values written by EncryptedTokenManagerImpl pre-multi-server.
        val legacyAccess = prefs.getString(EncryptedTokenManagerImpl.KEY_ACCESS_TOKEN, null)
        val legacyRefresh = prefs.getString(EncryptedTokenManagerImpl.KEY_REFRESH_TOKEN, null)
        val legacyExpiry =
            if (prefs.contains(EncryptedTokenManagerImpl.KEY_TOKEN_EXPIRY)) {
                prefs.getLong(EncryptedTokenManagerImpl.KEY_TOKEN_EXPIRY, 0L)
            } else null
        val legacyProfileId = prefs.getString(EncryptedTokenManagerImpl.KEY_PROFILE_ID, null)
        val legacyProfileToken = prefs.getString(EncryptedTokenManagerImpl.KEY_PROFILE_TOKEN, null)
        val legacyServerUrl = prefs.getString(EncryptedTokenManagerImpl.KEY_SERVER_URL, null)

        val urlForMigration = legacyServerUrl?.takeIf { it.isNotBlank() }
        if (urlForMigration == null || urlForMigration == DEFAULT_SERVER_URL_PLACEHOLDER) {
            // Nothing to migrate — first install, or only the placeholder default.
            prefs.edit().putBoolean(KEY_MIGRATED, true).apply()
            return loaded
        }

        val normalized = normalizeUrl(urlForMigration)
        val id = idFor(normalized)
        val entry = ServerEntry(
            id = id,
            url = normalized,
            profileId = legacyProfileId,
            lastUsedAtEpochMs = System.currentTimeMillis(),
        )

        val editor = prefs.edit()
        // Re-key tokens under the new server id.
        legacyAccess?.let {
            editor.putString(serverScopedKey(id, EncryptedTokenManagerImpl.KEY_ACCESS_TOKEN), it)
        }
        legacyRefresh?.let {
            editor.putString(serverScopedKey(id, EncryptedTokenManagerImpl.KEY_REFRESH_TOKEN), it)
        }
        legacyExpiry?.let {
            editor.putLong(serverScopedKey(id, EncryptedTokenManagerImpl.KEY_TOKEN_EXPIRY), it)
        }
        legacyProfileId?.let {
            editor.putString(serverScopedKey(id, EncryptedTokenManagerImpl.KEY_PROFILE_ID), it)
        }
        legacyProfileToken?.let {
            editor.putString(serverScopedKey(id, EncryptedTokenManagerImpl.KEY_PROFILE_TOKEN), it)
        }
        // Drop legacy unprefixed keys — their values are now per-server.
        editor.remove(EncryptedTokenManagerImpl.KEY_ACCESS_TOKEN)
        editor.remove(EncryptedTokenManagerImpl.KEY_REFRESH_TOKEN)
        editor.remove(EncryptedTokenManagerImpl.KEY_TOKEN_EXPIRY)
        editor.remove(EncryptedTokenManagerImpl.KEY_PROFILE_ID)
        editor.remove(EncryptedTokenManagerImpl.KEY_PROFILE_TOKEN)
        editor.remove(EncryptedTokenManagerImpl.KEY_SERVER_URL)
        editor.putBoolean(KEY_MIGRATED, true)
        // Persist the new state in the same edit so the migration is atomic-ish.
        val migrated = RegistryState(entries = listOf(entry), activeServerId = id)
        editor.putString(KEY_REGISTRY_STATE, json.encodeToString(migrated))
        editor.apply()

        return migrated
    }

    companion object {
        const val KEY_REGISTRY_STATE = "server_registry.v1"
        const val KEY_MIGRATED = "server_registry.migrated.v1"
        const val KEY_ACCOUNTS_MIGRATED = "server_registry.accounts_migrated.v1"

        /** The one-account-per-server credential keys, read only by the account migration. */
        private val LEGACY_SLOT_KEYS = listOf(
            EncryptedTokenManagerImpl.KEY_LOGIN_ID,
            EncryptedTokenManagerImpl.KEY_ACCESS_TOKEN,
            EncryptedTokenManagerImpl.KEY_REFRESH_TOKEN,
            EncryptedTokenManagerImpl.KEY_TOKEN_EXPIRY,
            EncryptedTokenManagerImpl.KEY_TOKEN_LIFETIME,
            EncryptedTokenManagerImpl.KEY_PROFILE_ID,
            EncryptedTokenManagerImpl.KEY_PROFILE_TOKEN,
        )
        const val DEFAULT_SERVER_URL_PLACEHOLDER = "http://localhost:8090"

        /**
         * Stable, opaque id for a normalized URL. Base64url so the value is
         * safe in a SharedPreferences key without further escaping.
         */
        fun idFor(normalizedUrl: String): String {
            val flags = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
            return Base64.encodeToString(normalizedUrl.toByteArray(Charsets.UTF_8), flags)
        }

        /**
         * Reverses a URL-derived registry id only when it is an exact id for a
         * normalized HTTP(S) URL. The round-trip check keeps unknown future id
         * formats from being interpreted as URLs accidentally.
         */
        fun urlForServerId(serverId: String): String? {
            if (serverId.isEmpty()) return null
            val flags = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
            val decoded = runCatching {
                Base64.decode(serverId, flags).toString(Charsets.UTF_8)
            }.getOrNull() ?: return null
            val normalized = normalizeWireUrl(decoded)
            if (canonicalComparisonUrl(normalized) == null || idFor(normalized) != serverId) {
                return null
            }
            return normalized
        }

        /**
         * Compares URL-derived server ids without changing either persisted
         * registry key. Exact ids (including unknown future formats) match
         * first. Otherwise only scheme/host case and explicit default ports
         * are canonicalized; credentials and the remaining URL components are
         * retained exactly.
         */
        /**
         * Whether two server references name the same deployment: the same
         * registry key, or the same verified deployment identity (silo-apple
         * `ServerRegistry.serversMatch`).
         */
        fun serversMatch(lhsId: String?, lhsIdentity: String?, rhsId: String?, rhsIdentity: String?): Boolean {
            if (serverIdsMatch(lhsId, rhsId)) return true
            val lhs = lhsIdentity?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            val rhs = rhsIdentity?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            return lhs == rhs
        }

        fun serverIdsMatch(lhs: String?, rhs: String?): Boolean {
            if (lhs.isNullOrEmpty() || rhs.isNullOrEmpty()) return false
            if (lhs == rhs) return true
            val lhsUrl = urlForServerId(lhs) ?: return false
            val rhsUrl = urlForServerId(rhs) ?: return false
            return canonicalComparisonUrl(lhsUrl) == canonicalComparisonUrl(rhsUrl)
        }

        private fun canonicalComparisonUrl(url: String): String? {
            val normalized = normalizeWireUrl(url)
            val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
            if (uri.isOpaque) return null
            val scheme = uri.scheme?.lowercase()
                ?.takeIf { it == "http" || it == "https" }
                ?: return null
            val authority = uri.rawAuthority ?: return null
            val canonicalAuthority = canonicalAuthority(
                rawAuthority = authority,
                scheme = scheme,
            ) ?: return null
            return buildString {
                append(scheme)
                append("://")
                append(canonicalAuthority)
                append(uri.rawPath.orEmpty())
                uri.rawQuery?.let {
                    append('?')
                    append(it)
                }
                uri.rawFragment?.let {
                    append('#')
                    append(it)
                }
            }
        }

        private fun canonicalAuthority(
            rawAuthority: String,
            scheme: String,
        ): String? {
            val userInfoSeparator = rawAuthority.indexOf('@')
            if (userInfoSeparator >= 0 && rawAuthority.indexOf('@', userInfoSeparator + 1) >= 0) {
                return null
            }
            val userInfoPrefix = if (userInfoSeparator < 0) {
                ""
            } else {
                rawAuthority.substring(0, userInfoSeparator + 1)
            }
            val hostAndPort = rawAuthority.substring(userInfoPrefix.length)
            if (hostAndPort.isEmpty()) return null

            val (host, rawPort) = if (hostAndPort.startsWith('[')) {
                val bracket = hostAndPort.indexOf(']')
                if (bracket <= 1) return null
                val bracketedHost = hostAndPort.substring(0, bracket + 1)
                val remainder = hostAndPort.substring(bracket + 1)
                if (remainder.isEmpty()) {
                    bracketedHost to null
                } else {
                    if (!remainder.startsWith(':')) return null
                    bracketedHost to remainder.substring(1)
                }
            } else {
                if (hostAndPort.contains('[') || hostAndPort.contains(']')) return null
                val colon = hostAndPort.lastIndexOf(':')
                if (colon < 0) {
                    hostAndPort to null
                } else {
                    val unbracketedHost = hostAndPort.substring(0, colon)
                    if (unbracketedHost.contains(':')) return null
                    unbracketedHost to hostAndPort.substring(colon + 1)
                }
            }
            if (host.isBlank() || host.any(Char::isWhitespace)) return null

            val port = rawPort?.let { value ->
                if (value.isEmpty() || value.any { !it.isDigit() }) return null
                value.toIntOrNull()?.takeIf { it in 0..65535 } ?: return null
            }
            val isDefaultPort = (scheme == "http" && port == 80) ||
                (scheme == "https" && port == 443)
            return buildString {
                append(userInfoPrefix)
                append(host.lowercase())
                if (rawPort != null && !isDefaultPort) {
                    append(':')
                    append(rawPort)
                }
            }
        }

        private fun normalizeWireUrl(raw: String): String = raw.trim().trimEnd('/')

        fun normalizeUrl(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty()) return trimmed
            // Lowercase scheme + host, preserve path/port/query as-typed.
            val schemeSeparator = trimmed.indexOf("://")
            if (schemeSeparator < 0) return trimmed
            val scheme = trimmed.substring(0, schemeSeparator).lowercase()
            val rest = trimmed.substring(schemeSeparator + 3)
            val pathStart = rest.indexOf('/')
            val authority = if (pathStart < 0) rest else rest.substring(0, pathStart)
            val path = if (pathStart < 0) "" else rest.substring(pathStart)
            return "$scheme://${authority.lowercase()}$path"
        }

        fun serverScopedKey(serverId: String, baseKey: String): String =
            "$serverId.$baseKey"

        /**
         * Key of one of [accountId]'s credential values. Lives inside its
         * server's "<serverId>." namespace, so removing a server sweeps its
         * accounts too. Account ids are UUIDs and never contain '.'.
         */
        fun accountScopedKey(serverId: String, accountId: String, baseKey: String): String =
            "$serverId.$accountId.$baseKey"
    }
}
