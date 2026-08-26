package org.siloserver.silo.tv.data.preferences

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import org.siloserver.silo.model.personal.UserLibrary
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.network.IdentityTransition
import org.siloserver.silo.network.IdentityTransitionBarrier
import org.siloserver.silo.network.IdentityTransitionKind
import org.siloserver.silo.network.IdentityTransitionPhase
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.tv.ui.shell.TvLibraryTabType
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription

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
 * Storage uses a file per profile and credential owner (both hashed in the
 * filename), then partitions values by server and [TvLibraryTabType]. This
 * keeps replacement accounts and temporary playback overlays isolated even
 * when they reuse the same server/profile identifiers.
 *
 * Builds before owner namespacing keyed the file on the profile id alone.
 * Upgrading into a namespaced file therefore imports the pre-upgrade file
 * once, as a [DataMigration] so it completes before the store publishes any
 * value. Only the first owner observed for a profile may import, so a later
 * account swap onto the same profile id starts clean; the pre-upgrade file is
 * never deleted, so a downgrade still finds its data.
 *
 * When no profile is active (sign-in / profile-selection), reads return null
 * and writes are silent no-ops.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TvLibraryScopeStore(
    private val context: Context,
    private val tokenManager: TokenManager,
    private val identityTransitions: IdentityTransitionBarrier,
    private val dataStoreFactory: (
        fileName: String,
        migrations: List<DataMigration<Preferences>>,
    ) -> DataStore<Preferences> =
        { fileName, migrations ->
            PreferenceDataStoreFactory.create(
                migrations = migrations,
                produceFile = { context.preferencesDataStoreFile(fileName) },
            )
        },
) {

    private val storeCache = mutableMapOf<String, DataStore<Preferences>>()
    private val legacyProcessOwnerId = UUID.randomUUID().toString()

    private fun storeFor(identity: StorageIdentity): DataStore<Preferences> =
        cachedStore(fileNameFor(identity.storageNamespace)) {
            if (identity.canImportLegacy) listOf(LegacyScopeImport(identity)) else emptyList()
        }

    private fun legacyStoreFor(identity: StorageIdentity): DataStore<Preferences> =
        cachedStore(identity.legacyFileName) { emptyList() }

    /**
     * One live DataStore per file. DataStore rejects a second active instance
     * over the same file, and [LegacyScopeImport] opens the pre-upgrade file
     * from inside the namespaced store's own migration.
     */
    private fun cachedStore(
        fileName: String,
        migrations: () -> List<DataMigration<Preferences>>,
    ): DataStore<Preferences> =
        synchronized(storeCache) {
            storeCache.getOrPut(fileName) { dataStoreFactory(fileName, migrations()) }
        }

    /**
     * The persisted library id for [type] under the active profile/server,
     * or null if nothing has been chosen yet (cold start) or no profile is
     * active.
     */
    suspend fun getSelectedLibraryId(type: TvLibraryTabType): Int? {
        val identity = currentIdentity() ?: return null
        return getSelectedLibraryId(identity, type)
    }

    /**
     * tvOS navPrefs.showAudiobooks parity: whether the Audiobooks tab shows
     * in the top menu when the server has an audiobook library. HIDDEN by
     * default like Apple TV; device+profile local (never server-synced).
     */
    suspend fun getShowAudiobooksTab(): Boolean {
        val identity = currentIdentity() ?: return false
        return getShowAudiobooksTab(identity)
    }

    /** Live legacy value, re-keyed after every completed identity transition. */
    fun showAudiobooksTabFlow(): Flow<Boolean> =
        identityTransitions.transitions
            // Subscribe before the initial read. `onStart` would leave a gap
            // where a profile switch could complete before SharedFlow was
            // actually observed, binding this long-lived flow to the old key.
            .onSubscription {
                emit(
                    identityTransitions.latestTransition.value
                        ?: IdentityTransition(
                            phase = IdentityTransitionPhase.DID_CHANGE,
                            kind = IdentityTransitionKind.PROFILE_SWITCH,
                            generation = identityTransitions.generation.value,
                        ),
                )
            }
            .flatMapLatest { transition ->
                flow {
                    // Stop publishing the previous owner's value at the inline
                    // privacy boundary. The completed transition below will
                    // replace this safe default with the newly-active owner's
                    // persisted value.
                    if (transition.phase == IdentityTransitionPhase.WILL_CHANGE) {
                        emit(false)
                        return@flow
                    }
                    val identity = currentIdentity()
                    if (identity == null) {
                        emit(false)
                        return@flow
                    }
                    emitAll(
                        storeFor(identity).data.map { preferences ->
                            preferences[showAudiobooksKey(identity.serverId)] ?: false
                        }.distinctUntilChanged(),
                    )
                }
            }
            .distinctUntilChanged()

    suspend fun setShowAudiobooksTab(show: Boolean) {
        val identity = currentIdentity() ?: return
        setShowAudiobooksTab(identity, show)
    }

    /** Persist [id] as the scope for [type] under the active profile/server. */
    suspend fun setSelectedLibraryId(id: Int?, type: TvLibraryTabType) {
        val identity = currentIdentity() ?: return
        setSelectedLibraryId(identity, id, type)
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
        // Keep one atomic identity for both the read and stale-value repair. A
        // profile switch between two independent public calls must not repair
        // the newly-active account with the previous account's resolution.
        val identity = currentIdentity()
        val storedId = identity?.let { getSelectedLibraryId(it, type) }
        val resolved = resolve(storedId, type, libraries)
        // Real eviction: if a stored id no longer matches a live library we
        // fell back to a different one — repersist it so the stale id can't
        // resurrect if that library reappears later.
        if (identity != null && resolved != null && storedId != null && storedId != resolved.id) {
            setSelectedLibraryId(identity, resolved.id, type)
        }
        return resolved
    }

    private suspend fun currentIdentity(): StorageIdentity? {
        val snapshot = tokenManager.snapshotCurrentScope() ?: return null
        val profileId = snapshot.profileId?.takeIf { it.isNotBlank() } ?: return null
        val serverId = snapshot.serverId.takeIf { it.isNotBlank() } ?: DEFAULT_SERVER_ID
        val owner = ownerDescriptor(snapshot)
        return StorageIdentity(
            serverId = serverId,
            storageNamespace = sha256("$profileId\u0000${sha256(owner)}"),
            legacyFileName = legacyFileNameFor(profileId),
            canImportLegacy = ownerMayImportLegacy(owner),
        )
    }

    /**
     * The raw ownership descriptor behind the hashed namespace. Kept unhashed
     * here so [ownerMayImportLegacy] can tell a durable account apart from an
     * ephemeral one.
     */
    private fun ownerDescriptor(snapshot: AuthScopeSnapshot): String =
        snapshot.credentialGenerationId
            ?.takeIf { it.isNotBlank() }
            ?.let { "temporary:$it" }
            ?: snapshot.credentialOwnerId
                ?.takeIf { it.isNotBlank() }
                ?.let { "persistent:$it" }
            ?: snapshot.credentialEpoch
                .takeIf { it != 0L }
                ?.let { "persistent_epoch:$it" }
            ?: snapshot.profileToken
                ?.takeIf { it.isNotBlank() }
                ?.let { "legacy_profile_token:$it" }
            // Old/custom token managers may not stamp any durable owner. A
            // profile token is isolated but may rotate; if even that is absent,
            // a process-local fallback deliberately sacrifices restart
            // continuity. Both choices favor isolation over silently inheriting
            // another account's local preferences.
            ?: "legacy_process:$legacyProcessOwnerId:${snapshot.identityGeneration}"

    /**
     * Only a durable, persistent owner may claim the pre-upgrade file. A
     * temporary playback overlay is deliberately isolated, and the
     * process-local fallback changes every launch — letting either one claim
     * would burn the single import on an identity that cannot keep the data,
     * permanently stranding the real account's preferences.
     */
    private fun ownerMayImportLegacy(owner: String): Boolean =
        !owner.startsWith("temporary:") && !owner.startsWith("legacy_process:")

    private suspend fun getSelectedLibraryId(
        identity: StorageIdentity,
        type: TvLibraryTabType,
    ): Int? = storeFor(identity).data.first()[scopeKey(identity.serverId, type)]

    private suspend fun setSelectedLibraryId(
        identity: StorageIdentity,
        id: Int?,
        type: TvLibraryTabType,
    ) {
        val key = scopeKey(identity.serverId, type)
        storeFor(identity).edit { prefs ->
            if (id == null) prefs.remove(key) else prefs[key] = id
        }
    }

    private suspend fun getShowAudiobooksTab(identity: StorageIdentity): Boolean =
        storeFor(identity).data.first()[showAudiobooksKey(identity.serverId)] ?: false

    private suspend fun setShowAudiobooksTab(identity: StorageIdentity, show: Boolean) {
        storeFor(identity).edit { prefs ->
            prefs[showAudiobooksKey(identity.serverId)] = show
        }
    }

    private data class StorageIdentity(
        val serverId: String,
        val storageNamespace: String,
        val legacyFileName: String,
        val canImportLegacy: Boolean,
    )

    /**
     * Copies the pre-upgrade, profile-only-keyed file into this owner's
     * namespaced file exactly once.
     *
     * Runs as a [DataMigration] so DataStore completes it before the first
     * value is published — concurrent first reads all observe the imported
     * data, never a half-migrated default. [legacyImportedKey] in the new file
     * makes it one-shot per namespace even after the user clears every
     * preference; [legacyOwnerClaimKey] in the old file makes it one-shot per
     * profile, so a second, different owner on the same profile id starts
     * clean instead of inheriting the previous account's choices.
     *
     * The old file is read before it is claimed, so a profile that never had
     * one does not get an empty file created for it, and it is never deleted:
     * a downgrade still finds exactly the data it wrote.
     */
    private inner class LegacyScopeImport(
        private val identity: StorageIdentity,
    ) : DataMigration<Preferences> {

        override suspend fun shouldMigrate(currentData: Preferences): Boolean =
            currentData[legacyImportedKey] != true

        override suspend fun migrate(currentData: Preferences): Preferences {
            val imported = currentData.toMutablePreferences()
            // Stamped whatever happens below: a missing or unreadable legacy
            // file is a clean no-op, not a retry on every launch.
            imported[legacyImportedKey] = true

            val legacy = legacyOrNull { legacyStoreFor(identity).data.first() }
            if (legacy == null || legacy.asMap().isEmpty()) return imported.toPreferences()

            val claimed = if (legacy[legacyOwnerClaimKey] == null) {
                // Check-and-claim in one edit so two owners racing their first
                // read cannot both decide they were first.
                legacyOrNull {
                    legacyStoreFor(identity).edit { prefs ->
                        if (prefs[legacyOwnerClaimKey] == null) {
                            prefs[legacyOwnerClaimKey] = identity.storageNamespace
                        }
                    }
                }
            } else {
                legacy
            } ?: return imported.toPreferences()

            // A foreign claim means this owner arrived after the upgrade.
            if (claimed[legacyOwnerClaimKey] != identity.storageNamespace) {
                return imported.toPreferences()
            }

            claimed.asMap().forEach { (key, value) ->
                if (key.name == legacyOwnerClaimKey.name) return@forEach
                if (key.name == legacyImportedKey.name) return@forEach
                // A crash between claiming and committing retries the import;
                // anything already written here wins over the old value.
                if (currentData.contains(key)) return@forEach
                @Suppress("UNCHECKED_CAST")
                imported[key as Preferences.Key<Any>] = value
            }
            return imported.toPreferences()
        }

        override suspend fun cleanUp() = Unit
    }

    /**
     * Legacy storage is best-effort: a deleted, corrupt or unreadable file
     * must leave the store on defaults rather than crash or wedge the flow.
     */
    private suspend fun <T> legacyOrNull(block: suspend () -> T): T? =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            null
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

        private fun fileNameFor(storageNamespace: String): String =
            "tv_library_scope_$storageNamespace"

        /**
         * The pre-owner-namespacing filename: profile id alone, truncated
         * hash. Read once per profile by [LegacyScopeImport]; never written
         * except for the one claim marker, and never deleted.
         */
        internal fun legacyFileNameFor(profileId: String): String =
            "tv_library_scope_${sha256(profileId).take(16)}"

        /** Set in the namespaced file once its legacy import has run. */
        private val legacyImportedKey = booleanPreferencesKey("legacy_scope_imported_v1")

        /** Set in the legacy file to the namespace that imported it. */
        private val legacyOwnerClaimKey = stringPreferencesKey("legacy_scope_owner_claim")

        private fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { "%02x".format(it) }
    }
}
