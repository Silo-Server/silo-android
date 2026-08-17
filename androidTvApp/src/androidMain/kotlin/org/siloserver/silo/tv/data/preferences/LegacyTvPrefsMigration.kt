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
import org.siloserver.silo.model.settings.EffectiveSetting
import org.siloserver.silo.model.settings.PlaybackSettingsKeys
import org.siloserver.silo.model.settings.QualityPresets
import org.siloserver.silo.model.settings.SubtitleAppearance
import org.siloserver.silo.model.settings.SubtitleFontSizePreset
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One-shot import of the legacy `tv_prefs` DataStore (owned by the removed
 * `TvPreferences` class) into the stores that replaced it:
 *
 * - Playback settings (`playback_quality`, `auto_play_next`,
 *   `auto_skip_intro`, `auto_skip_credits`, `subtitle_size`) →
 *   [PlayerSettingsStore] device overrides — but only for keys the server
 *   reports no existing device override for, exactly like the migration
 *   main's TvSettingsViewModel ran. Reuses main's sentinel scope
 *   ("android-tv-settings") so devices that already migrated never rerun.
 *
 * - `libraries_selected_library_id` (legacy *global* int) → the currently
 *   active profile's slot in [TvLibrarySelectionStore] (the new model is
 *   per-profile). Seeded once, only when that profile has no stored
 *   selection; other profiles fall back to TvLibrariesViewModel's default
 *   (first visible library). Gated by its own sentinel
 *   ("android-tv-library-selection"), which stays unmarked — and the pass
 *   is retried on a later call — until a profile is active.
 *
 * The legacy DataStore file is only opened if it exists on disk; fresh
 * installs mark both sentinels immediately. A blank server URL (pre-auth)
 * defers everything to a later call. Safe to call from multiple
 * ViewModels — sentinel-gated, and a [Mutex] serializes concurrent calls.
 */
class LegacyTvPrefsMigration(
    private val context: Context,
    private val settingsCache: AndroidServerSettingsCache,
    private val playerSettingsStore: PlayerSettingsStore,
    private val librarySelectionStore: TvLibrarySelectionStore,
    private val getServerUrl: suspend () -> String,
    private val getProfileId: suspend () -> String?,
    private val getEffectiveSettings: suspend (keys: List<String>) -> Map<String, EffectiveSetting>,
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

    suspend fun migrateIfNeeded() {
        mutex.withLock {
            val serverUrl = getServerUrl()
            if (serverUrl.isBlank()) return

            val playbackDone = settingsCache.isMigrationComplete(serverUrl, PLAYBACK_SCOPE)
            val libraryDone = settingsCache.isMigrationComplete(serverUrl, LIBRARY_SCOPE)
            if (playbackDone && libraryDone) return

            val store = resolveLegacyStore()
            if (store == null) {
                // Fresh install — nothing to import. Mark complete so future
                // calls short-circuit before the file-existence check.
                settingsCache.markMigrationComplete(serverUrl, PLAYBACK_SCOPE)
                settingsCache.markMigrationComplete(serverUrl, LIBRARY_SCOPE)
                return
            }

            val prefs = store.data.first()
            if (!playbackDone) migratePlaybackSettings(serverUrl, prefs)
            if (!libraryDone) migrateLibrarySelection(serverUrl, prefs)
        }
    }

    private fun resolveLegacyStore(): DataStore<Preferences>? {
        if (!legacyStoreResolved) {
            legacyStore = legacyStoreProvider(context)
            legacyStoreResolved = true
        }
        return legacyStore
    }

    private suspend fun migratePlaybackSettings(serverUrl: String, prefs: Preferences) {
        val legacyQuality = PlaybackQuality.fromWire(prefs[LegacyPlaybackQualityKey]).wireValue
        val legacySubtitleSize = SubtitleSize.fromLabel(prefs[LegacySubtitleSizeKey])
        val legacyAutoPlayNext = prefs[LegacyAutoPlayNextKey] ?: true
        val legacyAutoSkipIntro = prefs[LegacyAutoSkipIntroKey] ?: false
        val legacyAutoSkipCredits = prefs[LegacyAutoSkipCreditsKey] ?: false

        // Push each legacy value only when the server reports no existing
        // device override for the same key — same guard main's migration
        // used, so state written by another session wins over stale local
        // prefs. Lookup failures resolve to an empty map upstream, which
        // means "no overrides" (also main's behavior).
        val effective = getEffectiveSettings(
            listOf(
                PlaybackSettingsKeys.PreferredQuality,
                // Quality is two rows now, and `setQuality` writes both. Asking
                // only about the resolution would let a device that has a
                // server-side bitrate cap but no resolution override pass the
                // guard, and the legacy preset's bitrate — or JSON null, when
                // the legacy value is Auto — would overwrite that cap. Both
                // axes are queried so both can be guarded.
                PlaybackSettingsKeys.MaxBitrateKbps,
                PlaybackSettingsKeys.AutoPlayNext,
                // Both spellings of the intro preference. The legacy boolean is
                // migrated into the enum that superseded it, so an override on
                // either one means this device has already answered the
                // question and the stale local pref must not overwrite it.
                PlaybackSettingsKeys.AutoSkipIntro,
                PlaybackSettingsKeys.IntroSkipMode,
                PlaybackSettingsKeys.AutoSkipCredits,
                PlaybackSettingsKeys.SubtitleAppearance,
            ),
        )

        val qualityOverridden =
            effective[PlaybackSettingsKeys.PreferredQuality]?.hasDeviceOverride == true ||
                effective[PlaybackSettingsKeys.MaxBitrateKbps]?.hasDeviceOverride == true
        if (!qualityOverridden) {
            // Both axes, never just the resolution. Quality is a
            // (resolution, bitrate) pair now, and the legacy enum's bare
            // "720p" carries an implied cap — the same one the server's own
            // migration assigns it (internal/settingsmigrate/plan.go
            // decomposes 720p to {720p, 2000}). Writing the resolution alone
            // would leave a pair no preset covers, so the picker would render
            // nothing as selected with the cursor parked on Auto, and the
            // sentinel is marked on this pass so it could never be re-migrated.
            //
            // The legacy enum's wire values are exactly the base preset ids,
            // so the id lookup lands on the same bitrate the server assigns
            // (1080p -> 6000, 720p -> 2000, 480p -> 1500) rather than on
            // whichever tier of that resolution happens to sort first.
            val resolution = QualityPresets.normalizeResolution(legacyQuality)
            val preset = QualityPresets.byId(resolution)
            playerSettingsStore.setQuality(
                preset?.resolution ?: resolution,
                preset?.bitrateKbps,
            )
        }
        if (effective[PlaybackSettingsKeys.AutoPlayNext]?.hasDeviceOverride != true) {
            playerSettingsStore.setAutoPlayNext(legacyAutoPlayNext)
        }
        val introSkipOverridden =
            effective[PlaybackSettingsKeys.IntroSkipMode]?.hasDeviceOverride == true ||
                effective[PlaybackSettingsKeys.AutoSkipIntro]?.hasDeviceOverride == true
        if (!introSkipOverridden) {
            // true -> always, false -> ask; the same mapping the server's own
            // migration uses. "never" is unreachable from a boolean, which is
            // exactly why the enum replaced it.
            playerSettingsStore.setIntroSkipMode(
                IntroSkipMode.fromLegacyBoolean(legacyAutoSkipIntro),
            )
        }
        if (effective[PlaybackSettingsKeys.AutoSkipCredits]?.hasDeviceOverride != true) {
            playerSettingsStore.setAutoSkipCredits(legacyAutoSkipCredits)
        }
        if (effective[PlaybackSettingsKeys.SubtitleAppearance]?.hasDeviceOverride != true) {
            playerSettingsStore.setSubtitleAppearance(
                SubtitleAppearance.DEFAULT.copy(fontSize = legacySubtitleSize.toFontSizePreset()),
            )
        }

        // Make sure the writes hit the server even if the user backs out
        // before the store's debounce fires.
        playerSettingsStore.flushPendingDeviceSettings()
        settingsCache.markMigrationComplete(serverUrl, PLAYBACK_SCOPE)
    }

    private suspend fun migrateLibrarySelection(serverUrl: String, prefs: Preferences) {
        // The per-profile store needs an active profile; leave the sentinel
        // unmarked so a later call (post profile-select) retries.
        getProfileId() ?: return
        val legacyId = prefs[LegacySelectedLibraryIdKey]
        if (legacyId != null && librarySelectionStore.getSelectedLibraryId() == null) {
            librarySelectionStore.setSelectedLibraryId(legacyId)
        }
        settingsCache.markMigrationComplete(serverUrl, LIBRARY_SCOPE)
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
