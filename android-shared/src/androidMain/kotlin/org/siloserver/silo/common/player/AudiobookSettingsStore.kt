package org.siloserver.silo.common.player

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import org.siloserver.silo.common.store.AccountScopedKeys
import org.siloserver.silo.model.settings.LegacyAudiobookIntervals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * Local, per-profile store for audiobook playback preferences: the
 * configurable skip interval, the default playback speed, and the
 * audio-processing toggles (skip-silence / volume-normalization / boost —
 * defined here, consumed in a later phase).
 *
 * Mirrors the per-profile DataStore mechanics of [org.siloserver.silo.common.settings.AndroidPlayerSettingsStore]
 * (a file per profile via an injectable [dataStoreFactory], re-derivation on
 * profile change) but is **device-local only** — there is no server flush,
 * scope prefix, or legacy migration, because these are playback preferences
 * tied to the device rather than the account (like an iOS device override).
 * Profile ids repeat across saved accounts, so the file is per (account,
 * profile).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudiobookSettingsStore(
    private val context: Context,
    private val getActiveProfileId: suspend () -> String?,
    private val profileChangeSignal: Flow<Unit> = flowOf(Unit),
    private val getActiveAccountId: suspend () -> String? = { null },
    private val dataStoreFactory: (accountId: String?, profileId: String) -> DataStore<Preferences> = { accountId, profileId ->
        PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile(fileNameFor(accountId, profileId)) },
        )
    },
) {

    private data class StoreKey(val accountId: String?, val profileId: String)

    private val storeCache = mutableMapOf<StoreKey, DataStore<Preferences>>()

    // Re-resolve the active account and profile (and therefore which file
    // backs the flows) whenever either changes.
    private val currentProfileFlow: Flow<StoreKey?> = flow {
        emit(currentKey())
        profileChangeSignal.collect { emit(currentKey()) }
    }.distinctUntilChanged()

    private suspend fun currentKey(): StoreKey? =
        getActiveProfileId()?.let { StoreKey(getActiveAccountId(), it) }

    private fun storeFor(key: StoreKey): DataStore<Preferences> =
        synchronized(storeCache) {
            storeCache.getOrPut(key) { dataStoreFactory(key.accountId, key.profileId) }
        }

    private fun <T> profileScopedFlow(default: T, read: (Preferences) -> T): Flow<T> =
        currentProfileFlow.flatMapLatest { key ->
            if (key == null) flowOf(default) else storeFor(key).data.map(read)
        }

    val skipBackSecondsFlow: Flow<Int> =
        profileScopedFlow(DEFAULT_SKIP) { it[KEY_SKIP_BACK] ?: DEFAULT_SKIP }

    val skipForwardSecondsFlow: Flow<Int> =
        profileScopedFlow(DEFAULT_SKIP) { it[KEY_SKIP_FORWARD] ?: DEFAULT_SKIP }

    /**
     * Only the intervals the user explicitly stored on this device (no
     * defaults filled in). This is what an explicit "import to profile" may
     * offer; a direction that was never set is null and never uploaded.
     */
    val legacySkipIntervalsFlow: Flow<LegacyAudiobookIntervals> =
        profileScopedFlow(LegacyAudiobookIntervals()) {
            LegacyAudiobookIntervals(
                backSeconds = it[KEY_SKIP_BACK],
                forwardSeconds = it[KEY_SKIP_FORWARD],
            )
        }

    val defaultSpeedFlow: Flow<Float> =
        profileScopedFlow(DEFAULT_SPEED) { it[KEY_DEFAULT_SPEED]?.toFloatOrNull() ?: DEFAULT_SPEED }

    val skipSilenceEnabledFlow: Flow<Boolean> =
        profileScopedFlow(false) { it[KEY_SKIP_SILENCE] ?: false }

    val volumeNormalizationEnabledFlow: Flow<Boolean> =
        profileScopedFlow(false) { it[KEY_VOLUME_NORMALIZATION] ?: false }

    val volumeBoostDbFlow: Flow<Int> =
        profileScopedFlow(0) { it[KEY_VOLUME_BOOST_DB] ?: 0 }

    suspend fun setSkipBackSeconds(value: Int) = write { it[KEY_SKIP_BACK] = coerceSkip(value) }

    suspend fun setSkipForwardSeconds(value: Int) = write { it[KEY_SKIP_FORWARD] = coerceSkip(value) }

    suspend fun setDefaultSpeed(value: Float) =
        write { it[KEY_DEFAULT_SPEED] = value.coerceIn(MIN_SPEED, MAX_SPEED).toString() }

    suspend fun setSkipSilenceEnabled(value: Boolean) = write { it[KEY_SKIP_SILENCE] = value }

    suspend fun setVolumeNormalizationEnabled(value: Boolean) =
        write { it[KEY_VOLUME_NORMALIZATION] = value }

    suspend fun setVolumeBoostDb(value: Int) =
        write { it[KEY_VOLUME_BOOST_DB] = value.coerceIn(0, MAX_VOLUME_BOOST_DB) }

    private suspend fun write(mutate: (MutablePreferences) -> Unit) {
        val key = currentKey() ?: return
        storeFor(key).edit(mutate)
    }

    companion object {
        /**
         * Skip intervals offered in the device-local picker; any other value
         * coerces to [DEFAULT_SKIP]. Only used against servers that predate
         * the profile-wide intervals (settings revision 9).
         */
        val ALLOWED_SKIP = listOf(10, 15, 30, 60)

        /** Device-local default for both directions (pre-revision-9 behavior). */
        const val DEFAULT_SKIP = 30
        private const val DEFAULT_SPEED = 1.0f
        private const val MIN_SPEED = 0.5f
        private const val MAX_SPEED = 3.0f
        private const val MAX_VOLUME_BOOST_DB = 12

        private val KEY_SKIP_BACK = intPreferencesKey("skip_back_seconds")
        private val KEY_SKIP_FORWARD = intPreferencesKey("skip_forward_seconds")
        // DataStore Preferences has no float key — persist speed as a string,
        // matching how AndroidPlayerSettingsStore stores playback_speed.
        private val KEY_DEFAULT_SPEED = stringPreferencesKey("default_speed")
        private val KEY_SKIP_SILENCE = booleanPreferencesKey("skip_silence_enabled")
        private val KEY_VOLUME_NORMALIZATION = booleanPreferencesKey("volume_normalization_enabled")
        private val KEY_VOLUME_BOOST_DB = intPreferencesKey("volume_boost_db")

        private fun coerceSkip(value: Int): Int = if (value in ALLOWED_SKIP) value else DEFAULT_SKIP

        /** Per (account, profile); `AccountLocalData` adopts and purges these files. */
        const val FILE_BASE = "silo_audiobook_settings"

        private fun fileNameFor(accountId: String?, profileId: String): String =
            AccountScopedKeys.profileFileName(FILE_BASE, accountId, profileHash(profileId))

        private fun profileHash(profileId: String): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(profileId.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { "%02x".format(it) }
                .take(16)
    }
}
