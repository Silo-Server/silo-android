package org.siloserver.silo.tv.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import org.siloserver.silo.network.TokenManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

/**
 * Per-profile, per-server store for the "last selected library tab" UI state on
 * the Libraries screen. Each profile keeps its own selection across launches
 * (file-per-profile DataStore, hashed profileId in filename); within that file
 * the selection is partitioned by the active serverId — mirroring
 * [TvLibraryScopeStore]'s `scopeKey(serverId, …)` scheme — so a selection made
 * on one server can't leak to another server whose profile shares the same id
 * string.
 *
 * No server flush — this is pure local UI continuity, not a setting.
 * When no profile is active (during sign-in / profile-selection), reads
 * return null and writes are silent no-ops.
 *
 * Pattern modeled after [org.siloserver.silo.common.settings.AndroidPlayerSettingsStore]
 * but stripped to one key and no server-sync surface.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TvLibrarySelectionStore(
    private val context: Context,
    private val tokenManager: TokenManager,
    private val dataStoreFactory: (profileId: String) -> DataStore<Preferences> = { profileId ->
        PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile(fileNameFor(profileId)) },
        )
    },
) {

    private val storeCache = mutableMapOf<String, DataStore<Preferences>>()

    private fun storeFor(profileId: String): DataStore<Preferences> =
        synchronized(storeCache) {
            storeCache.getOrPut(profileId) { dataStoreFactory(profileId) }
        }

    /**
     * Emits the currently-active profile's selected library id (or null
     * if unset, or if no profile is active). One-shot read — downstream
     * consumers that need reactivity across profile switches should use
     * [observe] instead.
     */
    val selectedLibraryIdFlow: Flow<Int?> = flow {
        val profileId = tokenManager.getProfileId()
        if (profileId == null) {
            emit(null)
            return@flow
        }
        val serverId = tokenManager.getCurrentServerId() ?: DEFAULT_SERVER_ID
        emit(storeFor(profileId).data.first()[selectedLibraryKey(serverId)])
    }

    /**
     * Reactive variant: re-keys whenever profile changes (caller passes a
     * profile-change signal). Used by consumers that need to restore
     * selection across profile switches.
     */
    fun observe(profileChangeSignal: Flow<Unit>): Flow<Int?> =
        profileChangeSignal.flatMapLatest {
            flow {
                val profileId = tokenManager.getProfileId()
                if (profileId == null) {
                    emit(null)
                    return@flow
                }
                val serverId = tokenManager.getCurrentServerId() ?: DEFAULT_SERVER_ID
                val key = selectedLibraryKey(serverId)
                val store = storeFor(profileId)
                store.data.collect { prefs -> emit(prefs[key]) }
            }
        }

    suspend fun setSelectedLibraryId(id: Int?) {
        val profileId = tokenManager.getProfileId() ?: return
        val serverId = tokenManager.getCurrentServerId() ?: DEFAULT_SERVER_ID
        val key = selectedLibraryKey(serverId)
        storeFor(profileId).edit { prefs ->
            if (id == null) prefs.remove(key)
            else prefs[key] = id
        }
    }

    /**
     * One-shot read for the currently-active profile. Returns null if no
     * profile is active or no value is stored.
     */
    suspend fun getSelectedLibraryId(): Int? {
        val profileId = tokenManager.getProfileId() ?: return null
        val serverId = tokenManager.getCurrentServerId() ?: DEFAULT_SERVER_ID
        return storeFor(profileId).data.first()[selectedLibraryKey(serverId)]
    }

    /** Local legacy seed is bound before DataStore suspension; existing selection wins. */
    suspend fun seedLegacySelection(authority: org.siloserver.silo.network.AuthScopeSnapshot, id: Int?): Boolean {
        suspend fun current(): Boolean {
            val now = tokenManager.snapshotCurrentScope()
            return authority.isSameIdentityAs(now) && authority.profileId == now?.profileId && authority.profileToken == now?.profileToken
        }
        if (!current()) return false
        val profile = authority.profileId ?: return false
        if (id != null) {
            val key = selectedLibraryKey(authority.serverId)
            storeFor(profile).edit { prefs ->
                if (current() && prefs[key] == null) prefs[key] = id
            }
        }
        return current()
    }

    companion object {
        private const val DEFAULT_SERVER_ID = "default"

        // Per-server-partitioned key, mirroring [TvLibraryScopeStore.scopeKey]
        // so two servers under one profile keep independent selections.
        private fun selectedLibraryKey(serverId: String) =
            intPreferencesKey("selected_library_$serverId")

        // Filename pattern aligns with [AndroidPlayerSettingsStore.fileNameFor].
        private fun fileNameFor(profileId: String): String =
            "tv_library_selection_${profileHash(profileId)}"

        private fun profileHash(profileId: String): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(profileId.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { "%02x".format(it) }
                .take(16)
    }
}
