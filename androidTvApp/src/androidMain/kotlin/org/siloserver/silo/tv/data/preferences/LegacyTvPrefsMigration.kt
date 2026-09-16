package org.siloserver.silo.tv.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import org.siloserver.silo.common.settings.AndroidServerSettingsCache
import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.domain.player.IntroSkipMode
import org.siloserver.silo.model.settings.EffectiveSettingValue
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.AuthScopeSnapshot
import org.siloserver.silo.model.settings.PlaybackSettingsKeys
import org.siloserver.silo.model.settings.QualityPresets
import org.siloserver.silo.model.settings.SubtitleAppearance
import org.siloserver.silo.model.settings.SubtitleFontSizePreset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One-shot local legacy import. Historical server-scoped sentinels remain
 * authoritative. An incomplete/failed v2 read is never proof of absence;
 * original account/profile/PIN ownership is retained through every suspension.
 * No sentinel is marked until the corresponding import is acknowledged.
 */
class LegacyTvPrefsMigration(
    private val context: Context,
    private val settingsCache: AndroidServerSettingsCache,
    private val playerSettingsStore: PlayerSettingsStore,
    private val librarySelectionStore: TvLibrarySelectionStore,
    private val getAuthority: suspend () -> AuthScopeSnapshot?,
    private val getEffectiveSettings: suspend (keys: List<String>, authority: AuthScopeSnapshot) -> ApiResult<Map<String, EffectiveSettingValue>>,
    private val legacyStoreProvider: (Context) -> DataStore<Preferences>? = { ctx ->
        val file = ctx.preferencesDataStoreFile(LEGACY_STORE_NAME)
        if (file.exists()) {
            PreferenceDataStoreFactory.create(produceFile = { file })
        } else {
            null
        }
    },
) {

    private val mutex = Mutex()

    // Resolved at most once — creating two DataStores over the same file
    // throws IllegalStateException, so cache the (possibly null) handle.
    private var legacyStore: DataStore<Preferences>? = null
    private var legacyStoreResolved = false

    private suspend fun current(owner: AuthScopeSnapshot): Boolean {
        val now = getAuthority()
        return owner.isSameIdentityAs(now) && owner.profileId == now?.profileId && owner.profileToken == now?.profileToken
    }

    suspend fun migrateIfNeeded() = mutex.withLock {
        val owner = getAuthority() ?: return
        if (owner.serverUrl.isBlank() || owner.profileId.isNullOrBlank()) return
        val playbackDone = settingsCache.isMigrationComplete(owner.serverUrl, PLAYBACK_SCOPE)
        val libraryDone = settingsCache.isMigrationComplete(owner.serverUrl, LIBRARY_SCOPE)
        if (playbackDone && libraryDone) return
        val store = resolveLegacyStore()
        if (store == null) {
            if (!current(owner)) return
            settingsCache.markMigrationComplete(owner.serverUrl, PLAYBACK_SCOPE)
            settingsCache.markMigrationComplete(owner.serverUrl, LIBRARY_SCOPE)
            return
        }
        val prefs = store.data.first()
        if (!current(owner)) return
        if (!playbackDone) migratePlaybackSettings(owner, prefs)
        if (!libraryDone && current(owner) &&
            librarySelectionStore.seedLegacySelection(owner, prefs[LegacySelectedLibraryIdKey]) && current(owner)) {
            settingsCache.markMigrationComplete(owner.serverUrl, LIBRARY_SCOPE)
        }
    }

    private fun resolveLegacyStore(): DataStore<Preferences>? {
        if (!legacyStoreResolved) {
            legacyStore = legacyStoreProvider(context)
            legacyStoreResolved = true
        }
        return legacyStore
    }

    private suspend fun migratePlaybackSettings(owner: AuthScopeSnapshot, prefs: Preferences) {
        val keys = listOf(
            PlaybackSettingsKeys.PreferredQuality, PlaybackSettingsKeys.MaxBitrateKbps,
            PlaybackSettingsKeys.AutoPlayNext, PlaybackSettingsKeys.AutoSkipIntro,
            PlaybackSettingsKeys.IntroSkipMode, PlaybackSettingsKeys.AutoSkipCredits,
            PlaybackSettingsKeys.SubtitleAppearance,
        )
        if (!current(owner)) return
        val effective = when (val result = getEffectiveSettings(keys, owner)) {
            is ApiResult.Success -> result.data
            is ApiResult.Error, is ApiResult.NetworkError -> return
        }
        // Every requested key must have a resolution. A complete default or
        // inherited row proves no device override; an omitted row proves nothing.
        if (!current(owner) || !effective.keys.containsAll(keys)) return
        if (keys.any { key -> effective.getValue(key).source !in setOf(
                "default", "account", "profile", "profile_client", "profile_device",
            ) }) return
        fun overridden(key: String) = effective.getValue(key).let {
            it.scope == "profile_device" || it.source == "profile_device"
        }
        val values = linkedMapOf<String, String>()
        if (!overridden(PlaybackSettingsKeys.PreferredQuality) && !overridden(PlaybackSettingsKeys.MaxBitrateKbps)) {
            val resolution = QualityPresets.normalizeResolution(PlaybackQuality.fromWire(prefs[LegacyPlaybackQualityKey]).wireValue)
            val preset = QualityPresets.byId(resolution)
            values[PlaybackSettingsKeys.PreferredQuality] = preset?.resolution ?: resolution
            values[PlaybackSettingsKeys.MaxBitrateKbps] = (preset?.bitrateKbps ?: 0).toString()
        }
        if (!overridden(PlaybackSettingsKeys.AutoPlayNext)) {
            values[PlaybackSettingsKeys.AutoPlayNext] = (prefs[LegacyAutoPlayNextKey] ?: true).toString()
        }
        if (!overridden(PlaybackSettingsKeys.AutoSkipIntro) && !overridden(PlaybackSettingsKeys.IntroSkipMode)) {
            values[PlaybackSettingsKeys.IntroSkipMode] = IntroSkipMode.fromLegacyBoolean(prefs[LegacyAutoSkipIntroKey] ?: false).wireValue
        }
        if (!overridden(PlaybackSettingsKeys.AutoSkipCredits)) {
            values[PlaybackSettingsKeys.AutoSkipCredits] = (prefs[LegacyAutoSkipCreditsKey] ?: false).toString()
        }
        if (!overridden(PlaybackSettingsKeys.SubtitleAppearance)) {
            values[PlaybackSettingsKeys.SubtitleAppearance] = SubtitleAppearance.DEFAULT.copy(
                fontSize = SubtitleSize.fromLabel(prefs[LegacySubtitleSizeKey]).toFontSizePreset(),
            ).toJsonString()
        }
        if (!current(owner)) return
        if (playerSettingsStore.importLegacyDeviceSettings(owner, values) && current(owner)) {
            settingsCache.markMigrationComplete(owner.serverUrl, PLAYBACK_SCOPE)
        }
    }

    private fun SubtitleSize.toFontSizePreset(): SubtitleFontSizePreset = when (this) {
        SubtitleSize.Small -> SubtitleFontSizePreset.Small
        SubtitleSize.Medium -> SubtitleFontSizePreset.Medium
        SubtitleSize.Large -> SubtitleFontSizePreset.Large
    }

    companion object {
        const val LEGACY_STORE_NAME = "tv_prefs"

        // Identical to main's TvSettingsViewModel.MIGRATION_SCOPE — devices
        // that already ran main's migration must not rerun this one.
        private const val PLAYBACK_SCOPE = "android-tv-settings"
        private const val LIBRARY_SCOPE = "android-tv-library-selection"

        // Exact key strings from main's TvPreferences.Keys.
        private val LegacyPlaybackQualityKey = stringPreferencesKey("playback_quality")
        private val LegacySubtitleSizeKey = stringPreferencesKey("subtitle_size")
        private val LegacyAutoPlayNextKey = booleanPreferencesKey("auto_play_next")
        private val LegacyAutoSkipIntroKey = booleanPreferencesKey("auto_skip_intro")
        private val LegacyAutoSkipCreditsKey = booleanPreferencesKey("auto_skip_credits")
        private val LegacySelectedLibraryIdKey = intPreferencesKey("libraries_selected_library_id")
    }
}
