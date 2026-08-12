package org.siloserver.silo.network

import android.content.SharedPreferences
import android.util.Base64
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

    @Volatile
    override var stateLoadFailed: Boolean = false
        private set

    init {
        // Synchronous load + migration so MainActivity.runBlocking can read state.
        runBlocking {
            mutex.withLock {
                val state = loadStateLocked() ?: RegistryState()
                val migrated = migrateLegacyIfNeededLocked(state)
                applyStateLocked(migrated)
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
            val current = _entries.value.firstOrNull { it.id == serverId } ?: return
            if (current.profileId == profileId) return
            val updated = _entries.value.map { entry ->
                if (entry.id == serverId) entry.copy(profileId = profileId) else entry
            }
            persistAndApplyLocked(updated, _activeServerId.value)
        }
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

                val updated = _entries.value.filter { it.id != serverId }
                val newActive = if (_activeServerId.value == serverId) {
                    updated.maxByOrNull { it.lastUsedAtEpochMs }?.id
                } else _activeServerId.value
                val resolvedActive = newActive?.takeIf { id -> updated.any { it.id == id } }
                    ?: updated.maxByOrNull { it.lastUsedAtEpochMs }?.id
                val state = RegistryState(entries = updated, activeServerId = resolvedActive)
                editor.putString(KEY_REGISTRY_STATE, json.encodeToString(state))
                check(commitEditor(editor)) { "unable to durably remove server" }
                applyStateLocked(state)
                afterServerRemovalCommit()
            }
        }
    }

    override suspend fun signOut(serverId: String) {
        mutex.withLock {
            val updated = _entries.value.map { entry ->
                if (entry.id == serverId) entry.copy(profileId = null) else entry
            }
            persistAndApplyLocked(updated, _activeServerId.value)
        }
    }

    override suspend fun switchTo(serverId: String) {
        identityTransitions.changing(IdentityTransitionKind.SERVER_SWITCH) {
            mutex.withLock {
                if (_entries.value.none { it.id == serverId }) return@withLock
                val updated = _entries.value.map { entry ->
                    if (entry.id == serverId) {
                        entry.copy(lastUsedAtEpochMs = System.currentTimeMillis())
                    } else entry
                }
                persistAndApplyLocked(updated, serverId)
            }
        }
    }

    /**
     * Commits registry selection and its matching account credentials in the
     * one SharedPreferences transaction available to both owners. In-memory
     * registry state is published only after the synchronous disk commit.
     */
    internal suspend fun commitAccountReplacement(
        serverId: String,
        profileId: String?,
        profileToken: String?,
        accessToken: String,
        refreshToken: String,
        expiryEpochMs: Long,
        lifetimeMs: Long,
    ) {
        mutex.withLock {
            check(_entries.value.any { it.id == serverId }) { "account replacement target is not registered" }
            val updated = _entries.value.map { entry ->
                if (entry.id == serverId) {
                    entry.copy(
                        profileId = profileId,
                        lastUsedAtEpochMs = System.currentTimeMillis(),
                    )
                } else {
                    entry
                }
            }
            val state = RegistryState(entries = updated, activeServerId = serverId)
            val editor = prefs.edit()
                .putString(KEY_REGISTRY_STATE, json.encodeToString(state))
                .putString(serverScopedKey(serverId, EncryptedTokenManagerImpl.KEY_ACCESS_TOKEN), accessToken)
                .putString(serverScopedKey(serverId, EncryptedTokenManagerImpl.KEY_REFRESH_TOKEN), refreshToken)
                .putLong(serverScopedKey(serverId, EncryptedTokenManagerImpl.KEY_TOKEN_EXPIRY), expiryEpochMs)
                .putLong(serverScopedKey(serverId, EncryptedTokenManagerImpl.KEY_TOKEN_LIFETIME), lifetimeMs)
            val profileIdKey = serverScopedKey(serverId, EncryptedTokenManagerImpl.KEY_PROFILE_ID)
            val profileTokenKey = serverScopedKey(serverId, EncryptedTokenManagerImpl.KEY_PROFILE_TOKEN)
            if (profileId == null) editor.remove(profileIdKey) else editor.putString(profileIdKey, profileId)
            if (profileToken == null) editor.remove(profileTokenKey) else editor.putString(profileTokenKey, profileToken)
            check(commitEditor(editor)) { "unable to durably replace account session" }
            applyStateLocked(state)
        }
    }

    internal suspend fun commitAccountSignOut(serverId: String) {
        mutex.withLock {
            check(_entries.value.any { it.id == serverId }) { "sign-out target is not registered" }
            val updated = _entries.value.map { entry ->
                if (entry.id == serverId) entry.copy(profileId = null) else entry
            }
            val state = RegistryState(entries = updated, activeServerId = _activeServerId.value)
            val editor = prefs.edit()
                .putString(KEY_REGISTRY_STATE, json.encodeToString(state))
                .remove(serverScopedKey(serverId, EncryptedTokenManagerImpl.KEY_ACCESS_TOKEN))
                .remove(serverScopedKey(serverId, EncryptedTokenManagerImpl.KEY_REFRESH_TOKEN))
                .remove(serverScopedKey(serverId, EncryptedTokenManagerImpl.KEY_TOKEN_EXPIRY))
                .remove(serverScopedKey(serverId, EncryptedTokenManagerImpl.KEY_TOKEN_LIFETIME))
                .remove(serverScopedKey(serverId, EncryptedTokenManagerImpl.KEY_PROFILE_ID))
                .remove(serverScopedKey(serverId, EncryptedTokenManagerImpl.KEY_PROFILE_TOKEN))
            check(commitEditor(editor)) { "unable to durably sign out account" }
            applyStateLocked(state)
        }
    }

    override suspend fun touchActive() {
        mutex.withLock {
            val activeId = _activeServerId.value ?: return
            val updated = _entries.value.map { entry ->
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

    private fun applyStateLocked(state: RegistryState) {
        _entries.value = state.entries
        val resolvedActive = state.activeServerId?.takeIf { id ->
            state.entries.any { it.id == id }
        } ?: state.entries.maxByOrNull { it.lastUsedAtEpochMs }?.id
        _activeServerId.value = resolvedActive
        _activeEntry.value = state.entries.firstOrNull { it.id == resolvedActive }
    }

    private fun persistAndApplyLocked(entries: List<ServerEntry>, activeServerId: String?) {
        val resolvedActive = activeServerId?.takeIf { id -> entries.any { it.id == id } }
            ?: entries.maxByOrNull { it.lastUsedAtEpochMs }?.id
        val state = RegistryState(entries = entries, activeServerId = resolvedActive)
        prefs.edit().putString(KEY_REGISTRY_STATE, json.encodeToString(state)).apply()
        _entries.value = entries
        _activeServerId.value = resolvedActive
        _activeEntry.value = entries.firstOrNull { it.id == resolvedActive }
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
        const val DEFAULT_SERVER_URL_PLACEHOLDER = "http://localhost:8090"

        /**
         * Stable, opaque id for a normalized URL. Base64url so the value is
         * safe in a SharedPreferences key without further escaping.
         */
        fun idFor(normalizedUrl: String): String {
            val flags = Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
            return Base64.encodeToString(normalizedUrl.toByteArray(Charsets.UTF_8), flags)
        }

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
    }
}
