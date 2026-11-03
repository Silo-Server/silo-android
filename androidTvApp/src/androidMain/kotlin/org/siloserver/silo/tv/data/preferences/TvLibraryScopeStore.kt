package org.siloserver.silo.tv.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import org.siloserver.silo.common.store.AccountScopedKeys
import org.siloserver.silo.model.personal.UserLibrary
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.tv.ui.shell.TvLibraryTabType
import kotlinx.coroutines.flow.first

/**
 * Per-profile, per-type store for the selected library *scope* on the
 * Skyline shell (§3.1, §8). Mirrors tvOS `TVLibraryScopeStore`.
 *
 * Library scope is the one navigation state Skyline keeps across launches
 * (the selected pill is session-only). A multi-library type tab is always
 * scoped to exactly one library; this store remembers which one per profile,
 * per server, per type, so reopening a tab lands on the user's last choice
 * instead of snapping back to the first library.
 *
 * Storage mirrors [TvLibrarySelectionStore]: a file-per-(account, profile)
 * DataStore (account token and hashed profileId in the filename) so two
 * profiles never clobber each other. Within that file, each scope is partitioned by a key combining the
 * active serverId and the [TvLibraryTabType] — matching tvOS's
 * `serverId·profileId·type` keying so two servers under one profile keep
 * independent scopes.
 *
 * When no profile is active (sign-in / profile-selection), reads return null
 * and writes are silent no-ops.
 */
class TvLibraryScopeStore(
    private val context: Context,
    private val tokenManager: TokenManager,
    private val dataStoreFactory: (accountId: String?, profileId: String) -> DataStore<Preferences> = { accountId, profileId ->
        PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile(fileNameFor(accountId, profileId)) },
        )
    },
) {

    private val storeCache = mutableMapOf<String, DataStore<Preferences>>()

    /** The active account's file for [profileId]: profile ids repeat across accounts. */
    private suspend fun storeFor(profileId: String): DataStore<Preferences> {
        val accountId = tokenManager.getCurrentAccountId()
        return synchronized(storeCache) {
            storeCache.getOrPut(fileNameFor(accountId, profileId)) { dataStoreFactory(accountId, profileId) }
        }
    }

    /**
     * The persisted library id for [type] under the active profile/server,
     * or null if nothing has been chosen yet (cold start) or no profile is
     * active.
     */
    suspend fun getSelectedLibraryId(type: TvLibraryTabType): Int? {
        val profileId = tokenManager.getProfileId() ?: return null
        val serverId = tokenManager.getCurrentServerId() ?: DEFAULT_SERVER_ID
        return storeFor(profileId).data.first()[scopeKey(serverId, type)]
    }

    /**
     * tvOS navPrefs.showAudiobooks parity: whether the Audiobooks tab shows
     * in the top menu when the server has an audiobook library. HIDDEN by
     * default like Apple TV; device+profile local (never server-synced).
     */
    suspend fun getShowAudiobooksTab(): Boolean {
        val profileId = tokenManager.getProfileId() ?: return false
        val serverId = tokenManager.getCurrentServerId() ?: DEFAULT_SERVER_ID
        return storeFor(profileId).data.first()[showAudiobooksKey(serverId)] ?: false
    }

    suspend fun setShowAudiobooksTab(show: Boolean) {
        val profileId = tokenManager.getProfileId() ?: return
        val serverId = tokenManager.getCurrentServerId() ?: DEFAULT_SERVER_ID
        storeFor(profileId).edit { prefs ->
            prefs[showAudiobooksKey(serverId)] = show
        }
    }

    /** Persist [id] as the scope for [type] under the active profile/server. */
    suspend fun setSelectedLibraryId(id: Int?, type: TvLibraryTabType) {
        val profileId = tokenManager.getProfileId() ?: return
        val serverId = tokenManager.getCurrentServerId() ?: DEFAULT_SERVER_ID
        val key = scopeKey(serverId, type)
        storeFor(profileId).edit { prefs ->
            if (id == null) prefs.remove(key) else prefs[key] = id
        }
    }

    /**
     * Resolve the effective scope for [type] given the libraries available
     * to the current profile (already ordered by sort order). Returns the
     * persisted choice if it still exists, else the first library of that
     * type (cold start / the persisted library disappeared), else null when
     * the type has no libraries. Mirrors tvOS `TVLibraryScopeStore.resolvedLibrary`.
     */
    suspend fun resolvedLibrary(
        type: TvLibraryTabType,
        libraries: List<UserLibrary>,
    ): UserLibrary? {
        val storedId = getSelectedLibraryId(type)
        val resolved = resolve(storedId, type, libraries)
        // Real eviction: if a stored id no longer matches a live library we
        // fell back to a different one — repersist it so the stale id can't
        // resurrect if that library reappears later.
        if (resolved != null && storedId != null && storedId != resolved.id) {
            setSelectedLibraryId(resolved.id, type)
        }
        return resolved
    }

    companion object {
        private const val DEFAULT_SERVER_ID = "default"

        /**
         * Pure resolution rule, factored out so it is unit-testable without a
         * Context/DataStore. Picks the library matching [storedId] of [type]
         * if still present, else the first library of [type] by order, else
         * null.
         */
        fun resolve(
            storedId: Int?,
            type: TvLibraryTabType,
            libraries: List<UserLibrary>,
        ): UserLibrary? {
            val ofType = libraries.filter { type.matches(it) }
            if (ofType.isEmpty()) return null
            if (storedId != null) {
                ofType.firstOrNull { it.id == storedId }?.let { return it }
            }
            return ofType.first()
        }

        private fun scopeKey(serverId: String, type: TvLibraryTabType) =
            intPreferencesKey("scope_${serverId}_${type.name.lowercase()}")

        private fun showAudiobooksKey(serverId: String) =
            androidx.datastore.preferences.core.booleanPreferencesKey("show_audiobooks_$serverId")

        /** Per (account, profile); `AccountLocalData` adopts and purges these files. */
        const val FILE_BASE = "tv_library_scope"

        // Filename pattern aligns with [TvLibrarySelectionStore.fileNameFor].
        private fun fileNameFor(accountId: String?, profileId: String): String =
            AccountScopedKeys.profileFileName(FILE_BASE, accountId, profileHash(profileId))

        private fun profileHash(profileId: String): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(profileId.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { "%02x".format(it) }
                .take(16)
    }
}
