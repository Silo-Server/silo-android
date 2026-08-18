package org.siloserver.silo.tv.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import org.siloserver.silo.common.settings.AndroidServerSettingsCache
import org.siloserver.silo.common.settings.PlayerSettingsStore
import org.siloserver.silo.domain.player.IntroSkipMode
import org.siloserver.silo.model.settings.EffectiveSetting
import org.siloserver.silo.model.settings.PlaybackSettingsKeys
import org.siloserver.silo.model.settings.QualityPresets
import org.siloserver.silo.model.settings.SubtitleAppearance
import org.siloserver.silo.model.settings.SubtitleFontSizePreset
import org.siloserver.silo.network.TokenManager
import org.siloserver.silo.tv.testing.FakePlayerSettingsStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LegacyTvPrefsMigrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val serverUrl = "https://tv.example"
    private val profileId = "profile-1"

    // Sentinel scopes — must match LegacyTvPrefsMigration's companion
    // ("android-tv-settings" is main's historical scope).
    private val playbackScope = "android-tv-settings"
    private val libraryScope = "android-tv-library-selection"

    // Exact legacy key strings from main's TvPreferences.
    private val legacyQualityKey = stringPreferencesKey("playback_quality")
    private val legacySubtitleSizeKey = stringPreferencesKey("subtitle_size")
    private val legacyAutoPlayNextKey = booleanPreferencesKey("auto_play_next")
    private val legacyAutoSkipIntroKey = booleanPreferencesKey("auto_skip_intro")
    private val legacyAutoSkipCreditsKey = booleanPreferencesKey("auto_skip_credits")
    private val legacySelectedLibraryIdKey = intPreferencesKey("libraries_selected_library_id")

    private lateinit var fakePlayerStore: FakePlayerSettingsStore
    private lateinit var fakeCache: FakeSettingsCache
    private lateinit var tokenManager: FakeTokenManager
    private lateinit var selectionStore: TvLibrarySelectionStore

    @Before
    fun setup() {
        fakePlayerStore = FakePlayerSettingsStore()
        fakeCache = FakeSettingsCache()
        tokenManager = FakeTokenManager(serverUrl = serverUrl, profileId = profileId)
        selectionStore = TvLibrarySelectionStore(
            context = mockContextStub(),
            tokenManager = tokenManager,
            dataStoreFactory = { id ->
                PreferenceDataStoreFactory.create(
                    produceFile = { File(tempFolder.root, "lib_$id.preferences_pb") },
                )
            },
        )
    }

    private fun legacyStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.root, "tv_prefs.preferences_pb") },
        )

    private fun newMigration(
        legacy: DataStore<Preferences>?,
        effective: Map<String, EffectiveSetting> = emptyMap(),
    ): LegacyTvPrefsMigration = LegacyTvPrefsMigration(
        context = mockContextStub(),
        settingsCache = fakeCache,
        playerSettingsStore = fakePlayerStore,
        librarySelectionStore = selectionStore,
        getServerUrl = { tokenManager.getServerUrl() },
        getProfileId = { tokenManager.getProfileId() },
        getEffectiveSettings = { effective },
        legacyStoreProvider = { legacy },
    )

    @Test
    fun `imports legacy playback settings once and marks sentinel`() = runTest {
        val legacy = legacyStore()
        legacy.edit { prefs ->
            prefs[legacyQualityKey] = "1080p"
            prefs[legacyAutoPlayNextKey] = false
            prefs[legacyAutoSkipIntroKey] = true
            prefs[legacyAutoSkipCreditsKey] = true
            prefs[legacySubtitleSizeKey] = "Large"
        }
        val migration = newMigration(legacy)
        migration.migrateIfNeeded()

        assertEquals("1080p", fakePlayerStore.preferredQualityFlow.value)
        // Both axes, or the pair matches no preset and the picker renders
        // nothing as selected — see `imported quality is a pair the picker can
        // select`.
        assertEquals(6000, fakePlayerStore.maxBitrateKbpsFlow.value)
        assertEquals(false, fakePlayerStore.autoPlayNextFlow.value)
        assertEquals(IntroSkipMode.ALWAYS, fakePlayerStore.introSkipModeFlow.value)
        assertEquals(true, fakePlayerStore.autoSkipCreditsFlow.value)
        assertEquals(
            SubtitleFontSizePreset.Large,
            fakePlayerStore.subtitleAppearanceFlow.value.fontSize,
        )
        assertTrue(fakePlayerStore.flushCount >= 1)
        assertTrue(fakeCache.isMigrationComplete(serverUrl, playbackScope))

        // Second run is a no-op.
        val callsAfterFirstRun = fakePlayerStore.setterCalls.size
        migration.migrateIfNeeded()
        assertEquals(callsAfterFirstRun, fakePlayerStore.setterCalls.size)
    }

    @Test
    fun `existing server device override is not clobbered`() = runTest {
        val legacy = legacyStore()
        legacy.edit { prefs ->
            prefs[legacyQualityKey] = "720p"
            prefs[legacyAutoSkipIntroKey] = true
        }
        val effective = mapOf(
            PlaybackSettingsKeys.PreferredQuality to EffectiveSetting(
                key = PlaybackSettingsKeys.PreferredQuality,
                effectiveValue = "1080p",
                source = "device",
                hasDeviceOverride = true,
            ),
        )
        newMigration(legacy, effective).migrateIfNeeded()

        assertFalse(fakePlayerStore.setterCalls.contains("setPreferredQuality"))
        assertFalse(fakePlayerStore.setterCalls.contains("setQuality"))
        assertEquals("auto", fakePlayerStore.preferredQualityFlow.value)
        // Keys without a server override still import.
        assertEquals(IntroSkipMode.ALWAYS, fakePlayerStore.introSkipModeFlow.value)
    }

    @Test
    fun `an existing bitrate override alone still blocks the quality import`() = runTest {
        // Quality is two rows and setQuality writes both, so guarding on the
        // resolution alone lets a device that has only a server-side bitrate
        // cap pass — and the legacy preset's bitrate (or JSON null, for a
        // legacy Auto) overwrites the cap the migration promised to preserve.
        val legacy = legacyStore()
        legacy.edit { prefs ->
            prefs[legacyQualityKey] = "720p"
            prefs[legacyAutoSkipIntroKey] = true
        }
        val effective = mapOf(
            PlaybackSettingsKeys.MaxBitrateKbps to EffectiveSetting(
                key = PlaybackSettingsKeys.MaxBitrateKbps,
                effectiveValue = "3000",
                source = "device",
                hasDeviceOverride = true,
            ),
        )
        newMigration(legacy, effective).migrateIfNeeded()

        assertFalse(
            fakePlayerStore.setterCalls.contains("setQuality"),
            "a server-side bitrate override must not be overwritten by the legacy preset",
        )
        // Keys without an override still import.
        assertEquals(IntroSkipMode.ALWAYS, fakePlayerStore.introSkipModeFlow.value)
    }

    @Test
    fun `the quality guard asks the server about both axes`() = runTest {
        // The guard can only preserve what it queries: a bitrate key missing
        // from the request comes back absent, which reads as "no override".
        val legacy = legacyStore()
        legacy.edit { prefs -> prefs[legacyQualityKey] = "720p" }
        val requested = mutableListOf<String>()
        LegacyTvPrefsMigration(
            context = mockContextStub(),
            settingsCache = fakeCache,
            playerSettingsStore = fakePlayerStore,
            librarySelectionStore = selectionStore,
            getServerUrl = { tokenManager.getServerUrl() },
            getProfileId = { tokenManager.getProfileId() },
            getEffectiveSettings = { keys -> requested.addAll(keys); emptyMap() },
            legacyStoreProvider = { legacy },
        ).migrateIfNeeded()

        assertTrue(PlaybackSettingsKeys.PreferredQuality in requested)
        assertTrue(
            PlaybackSettingsKeys.MaxBitrateKbps in requested,
            "an unqueried axis cannot be guarded",
        )
    }

    /**
     * Every legacy quality value must land on a pair the picker can show as
     * selected. Quality is two axes now; a resolution imported without its
     * bitrate is a combination `QualityPresets.presetFor` does not match, so
     * `TvSettingsScreen`'s picker computes an empty selected id, renders no
     * checkmark, and parks the cursor on Auto — and because the sentinel is
     * marked on the same pass, the import cannot be repeated to repair it.
     */
    @Test
    fun `imported quality is a pair the picker can select`() = runTest {
        for (legacy in PlaybackQuality.entries) {
            fakePlayerStore = FakePlayerSettingsStore()
            fakeCache = FakeSettingsCache()
            val store = PreferenceDataStoreFactory.create(
                produceFile = { File(tempFolder.root, "tv_prefs_${legacy.name}.preferences_pb") },
            )
            store.edit { prefs -> prefs[legacyQualityKey] = legacy.wireValue }

            newMigration(store).migrateIfNeeded()

            val resolution = fakePlayerStore.preferredQualityFlow.value
            val bitrate = fakePlayerStore.maxBitrateKbpsFlow.value
            assertNotNull(
                QualityPresets.presetFor(resolution, bitrate),
                "legacy ${legacy.wireValue} imported as ($resolution, $bitrate), " +
                    "which no picker preset covers",
            )
            assertEquals(legacy.wireValue, resolution)
        }
    }

    @Test
    fun `a legacy quality with an implied cap imports that cap`() = runTest {
        // The bitrates match the server's own migration
        // (internal/settingsmigrate/plan.go decomposes 720p to {720p, 2000}),
        // so the same legacy value means the same thing on both sides.
        val legacy = legacyStore()
        legacy.edit { prefs -> prefs[legacyQualityKey] = "720p" }

        newMigration(legacy).migrateIfNeeded()

        assertEquals("720p", fakePlayerStore.preferredQualityFlow.value)
        assertEquals(2000, fakePlayerStore.maxBitrateKbpsFlow.value)
    }

    @Test
    fun `a legacy quality with no implied cap imports uncapped`() = runTest {
        val legacy = legacyStore()
        legacy.edit { prefs -> prefs[legacyQualityKey] = "2160p" }

        newMigration(legacy).migrateIfNeeded()

        assertEquals("2160p", fakePlayerStore.preferredQualityFlow.value)
        assertNull(fakePlayerStore.maxBitrateKbpsFlow.value)
    }

    @Test
    fun `pre-existing playback sentinel skips playback import but library still migrates`() = runTest {
        fakeCache.markMigrationComplete(serverUrl, playbackScope)
        val legacy = legacyStore()
        legacy.edit { prefs ->
            prefs[legacyQualityKey] = "720p"
            prefs[legacySelectedLibraryIdKey] = 42
        }
        newMigration(legacy).migrateIfNeeded()

        assertTrue(fakePlayerStore.setterCalls.isEmpty())
        assertEquals(42, selectionStore.getSelectedLibraryId())
    }

    @Test
    fun `seeds active profile library selection from legacy global key`() = runTest {
        val legacy = legacyStore()
        legacy.edit { prefs -> prefs[legacySelectedLibraryIdKey] = 42 }
        newMigration(legacy).migrateIfNeeded()

        assertEquals(42, selectionStore.getSelectedLibraryId())
        assertTrue(fakeCache.isMigrationComplete(serverUrl, libraryScope))
    }

    @Test
    fun `does not overwrite an existing per-profile selection`() = runTest {
        selectionStore.setSelectedLibraryId(7)
        val legacy = legacyStore()
        legacy.edit { prefs -> prefs[legacySelectedLibraryIdKey] = 42 }
        newMigration(legacy).migrateIfNeeded()

        assertEquals(7, selectionStore.getSelectedLibraryId())
        assertTrue(fakeCache.isMigrationComplete(serverUrl, libraryScope))
    }

    @Test
    fun `library migration waits for an active profile`() = runTest {
        tokenManager.profileId = null
        val legacy = legacyStore()
        legacy.edit { prefs -> prefs[legacySelectedLibraryIdKey] = 42 }
        val migration = newMigration(legacy)
        migration.migrateIfNeeded()

        assertTrue(fakeCache.isMigrationComplete(serverUrl, playbackScope))
        assertFalse(fakeCache.isMigrationComplete(serverUrl, libraryScope))

        // A profile becomes active — the next call seeds and completes.
        tokenManager.profileId = profileId
        migration.migrateIfNeeded()
        assertEquals(42, selectionStore.getSelectedLibraryId())
        assertTrue(fakeCache.isMigrationComplete(serverUrl, libraryScope))
    }

    @Test
    fun `missing legacy file marks both sentinels without importing`() = runTest {
        newMigration(legacy = null).migrateIfNeeded()

        assertTrue(fakePlayerStore.setterCalls.isEmpty())
        assertTrue(fakeCache.isMigrationComplete(serverUrl, playbackScope))
        assertTrue(fakeCache.isMigrationComplete(serverUrl, libraryScope))
    }

    @Test
    fun `blank server url defers migration entirely`() = runTest {
        tokenManager.serverUrl = ""
        val legacy = legacyStore()
        legacy.edit { prefs -> prefs[legacyQualityKey] = "1080p" }
        newMigration(legacy).migrateIfNeeded()

        assertTrue(fakePlayerStore.setterCalls.isEmpty())
        assertFalse(fakeCache.isMigrationComplete(serverUrl, playbackScope))
        assertFalse(fakeCache.isMigrationComplete(serverUrl, libraryScope))
    }

    /**
     * Context is only dereferenced by the default legacyStoreProvider /
     * dataStoreFactory; tests inject their own, so a null-wrapped stub
     * fails loudly if anything ever touches it. Mirrors
     * android-shared's AndroidPlayerSettingsStoreTest.
     */
    private fun mockContextStub(): android.content.Context =
        object : android.content.ContextWrapper(null) {}
}

/** Records setter calls and mirrors them into MutableStateFlows. */

/**
 * In-memory sentinel store — bypasses the SharedPreferences-backed base
 * implementation that requires a real Context. Pattern copied from
 * android-shared's AndroidPlayerSettingsStoreTest FakeLegacyCache.
 */
private class FakeSettingsCache : AndroidServerSettingsCache(stubContext()) {
    private val sentinels = mutableSetOf<String>()

    override fun isMigrationComplete(serverUrl: String, scope: String): Boolean =
        migrationKey(serverUrl, scope) in sentinels

    override fun markMigrationComplete(serverUrl: String, scope: String) {
        sentinels += migrationKey(serverUrl, scope)
    }

    companion object {
        fun stubContext(): android.content.Context =
            object : android.content.ContextWrapper(null) {
                override fun getSharedPreferences(
                    name: String?,
                    mode: Int,
                ): android.content.SharedPreferences = StubPrefs()
            }
    }
}

/** Minimal SharedPreferences stub for the cache's super constructor. */
private class StubPrefs : android.content.SharedPreferences {
    override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any>()
    override fun getString(p0: String?, p1: String?): String? = p1
    override fun getStringSet(p0: String?, p1: MutableSet<String>?): MutableSet<String>? = p1
    override fun getInt(p0: String?, p1: Int): Int = p1
    override fun getLong(p0: String?, p1: Long): Long = p1
    override fun getFloat(p0: String?, p1: Float): Float = p1
    override fun getBoolean(p0: String?, p1: Boolean): Boolean = p1
    override fun contains(p0: String?): Boolean = false
    override fun edit(): android.content.SharedPreferences.Editor = StubEditor()
    override fun registerOnSharedPreferenceChangeListener(p0: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(p0: android.content.SharedPreferences.OnSharedPreferenceChangeListener?) {}
}

private class StubEditor : android.content.SharedPreferences.Editor {
    override fun putString(p0: String?, p1: String?): android.content.SharedPreferences.Editor = this
    override fun putStringSet(p0: String?, p1: MutableSet<String>?): android.content.SharedPreferences.Editor = this
    override fun putInt(p0: String?, p1: Int): android.content.SharedPreferences.Editor = this
    override fun putLong(p0: String?, p1: Long): android.content.SharedPreferences.Editor = this
    override fun putFloat(p0: String?, p1: Float): android.content.SharedPreferences.Editor = this
    override fun putBoolean(p0: String?, p1: Boolean): android.content.SharedPreferences.Editor = this
    override fun remove(p0: String?): android.content.SharedPreferences.Editor = this
    override fun clear(): android.content.SharedPreferences.Editor = this
    override fun commit(): Boolean = true
    override fun apply() {}
}

/** Mutable-field fake covering the full TokenManager surface. */
private class FakeTokenManager(
    var serverUrl: String,
    var profileId: String?,
) : TokenManager {
    override val sessionExpired: SharedFlow<Unit> = MutableSharedFlow()
    override suspend fun getAccessToken(): String? = null
    override suspend fun getRefreshToken(): String? = null
    override suspend fun saveTokens(accessToken: String, refreshToken: String, expiresIn: Long) {}
    override suspend fun clearTokens() {}
    override suspend fun invalidateSession() {}
    override suspend fun getProfileId(): String? = profileId
    override suspend fun setProfileId(profileId: String?) {
        this.profileId = profileId
    }
    override suspend fun getProfileToken(): String? = null
    override suspend fun setProfileToken(token: String?) {}
    override suspend fun getServerUrl(): String = serverUrl
    override suspend fun setServerUrl(url: String) {
        serverUrl = url
    }
    override suspend fun getCurrentServerId(): String? = null
    override suspend fun switchActiveServer(serverId: String?) {}
    override suspend fun signOutCurrentServer() {}
}
