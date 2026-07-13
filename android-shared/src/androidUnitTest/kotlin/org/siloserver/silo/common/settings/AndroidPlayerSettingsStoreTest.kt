package org.siloserver.silo.common.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import org.siloserver.silo.model.settings.EffectiveSetting
import org.siloserver.silo.model.settings.EffectiveSettingsResponse
import org.siloserver.silo.model.settings.EffectiveSubtitleAppearance
import org.siloserver.silo.model.settings.PlaybackSettingsKeys
import org.siloserver.silo.model.settings.SubtitleAppearance
import org.siloserver.silo.model.settings.SubtitleFontSizePreset
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.network.api.SettingsApi
import org.siloserver.silo.repository.SettingsRepository
import io.ktor.client.HttpClient
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidPlayerSettingsStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var fakeFlusher: FakeServerSettingsFlusher
    private lateinit var fakeLegacyCache: FakeLegacyCache
    private val activeProfileId = "test-profile"
    private val serverUrl = "https://test.example"

    @Before
    fun setup() {
        fakeFlusher = FakeServerSettingsFlusher()
        fakeLegacyCache = FakeLegacyCache()
    }

    private fun newStore(
        profileId: String? = activeProfileId,
        legacy: AndroidServerSettingsCache = fakeLegacyCache,
        server: String? = serverUrl,
        repository: SettingsRepository? = null,
        deviceId: String? = null,
    ): AndroidPlayerSettingsStore {
        // In-process DataStore factory backed by a temp directory.
        return AndroidPlayerSettingsStore(
            context = mockContextStub(),
            legacyCache = legacy,
            getActiveProfileId = { profileId },
            getServerUrl = { server },
            serverSettingsFlusher = fakeFlusher,
            scope = TestScope(),
            profileChangeSignal = flowOf(Unit),
            settingsRepository = repository,
            getDeviceId = { deviceId },
            dataStoreFactory = { id ->
                val file = File(tempFolder.root, "ds_$id.preferences_pb")
                PreferenceDataStoreFactory.create(produceFile = { file })
            },
        )
    }

    @Test
    fun `setAutoSkipIntro updates flow value`() = runTest {
        val store = newStore()
        assertEquals(false, store.autoSkipIntroFlow.first())
        store.setAutoSkipIntro(true)
        assertEquals(true, store.autoSkipIntroFlow.first())
    }

    @Test
    fun `picture in picture defaults on and stays local`() = runTest {
        val store = newStore()
        assertEquals(true, store.pictureInPictureEnabledFlow.first())
        store.setPictureInPictureEnabled(false)
        assertEquals(false, store.pictureInPictureEnabledFlow.first())
        assertFalse(
            fakeFlusher.calls.any { it.key == PlaybackSettingsKeys.PictureInPictureEnabled },
            "PiP is platform-local and must never flush an unknown setting key to the server",
        )
    }

    @Test
    fun `setSubtitleAppearance round-trips through JSON`() = runTest {
        val store = newStore()
        val custom = SubtitleAppearance.DEFAULT.copy(
            fontSize = SubtitleFontSizePreset.XLarge,
            fontColor = "#ff0000",
        )
        store.setSubtitleAppearance(custom)
        val read = store.subtitleAppearanceFlow.first()
        assertEquals(SubtitleFontSizePreset.XLarge, read.fontSize)
        assertEquals("#ff0000", read.fontColor)
    }

    @Test
    fun `setPlaybackSpeed clamps out-of-range values`() = runTest {
        val store = newStore()
        store.setPlaybackSpeed(10.0)
        assertEquals(4.0, store.playbackSpeedFlow.first(), 0.0)
        store.setPlaybackSpeed(0.01)
        assertEquals(0.25, store.playbackSpeedFlow.first(), 0.0)
    }

    @Test
    fun `flow emits default when no value stored`() = runTest {
        val store = newStore()
        assertEquals(false, store.autoSkipIntroFlow.first())
        assertEquals(true, store.autoPlayNextFlow.first())
        assertEquals(true, store.hdrEnabledFlow.first())
        assertEquals(true, store.pictureInPictureEnabledFlow.first())
        assertEquals(1.0, store.playbackSpeedFlow.first(), 0.0)
        assertEquals(0, store.audioSyncMsFlow.first())
        assertEquals(30, store.nextUpPromptSecondsFlow.first())
        assertEquals("auto", store.preferredQualityFlow.first())
        assertEquals("original", store.defaultDownloadQualityFlow.first())
        assertEquals("", store.audioLanguageFlow.first())
        assertEquals("fit", store.videoGravityFlow.first())
        assertEquals(SubtitleAppearance.DEFAULT, store.subtitleAppearanceFlow.first())
    }

    @Test
    fun `flow emits default when profile id is null`() = runTest {
        val store = newStore(profileId = null)
        assertEquals(false, store.autoSkipIntroFlow.first())
        assertEquals(SubtitleAppearance.DEFAULT, store.subtitleAppearanceFlow.first())
    }

    @Test
    fun `legacy cache values migrate on first read`() = runTest {
        // Seed legacy cache with values for several keys
        fakeLegacyCache.putString(serverUrl, PlaybackSettingsKeys.AutoSkipIntro, "true")
        fakeLegacyCache.putString(serverUrl, PlaybackSettingsKeys.PreferredQuality, "1080p")
        fakeLegacyCache.putString(serverUrl, PlaybackSettingsKeys.AudioSyncMs, "120")

        val store = newStore()
        assertEquals(true, store.autoSkipIntroFlow.first())
        assertEquals("1080p", store.preferredQualityFlow.first())
        assertEquals(120, store.audioSyncMsFlow.first())
    }

    @Test
    fun `flush enqueue is called on each setter`() = runTest {
        val store = newStore()
        store.setAutoSkipIntro(true)
        store.setPreferredQuality("720p")
        store.setPlaybackSpeed(1.5)

        val calls = fakeFlusher.calls
        assertTrue(calls.any { it.key == PlaybackSettingsKeys.AutoSkipIntro && it.value == "true" })
        assertTrue(calls.any { it.key == PlaybackSettingsKeys.PreferredQuality && it.value == "720p" })
        assertTrue(calls.any { it.key == PlaybackSettingsKeys.PlaybackSpeed && it.value == "1.5" })
        assertTrue(calls.all { it.profileId == activeProfileId })
    }

    @Test
    fun `default download quality is local-only and constrained to supported presets`() = runTest {
        val store = newStore()
        assertEquals("original", store.defaultDownloadQualityFlow.first())

        store.setDefaultDownloadQuality("10mbps")
        assertEquals("10mbps", store.defaultDownloadQualityFlow.first())
        assertFalse(
            fakeFlusher.calls.any { it.key == PlaybackSettingsKeys.DefaultDownloadQuality },
            "Download quality is a local client queue preference until the server exposes a synced setting.",
        )

        store.setDefaultDownloadQuality("1080p")
        assertEquals("original", store.defaultDownloadQualityFlow.first())
    }

    @Test
    fun `downloads wifi only is local-only`() = runTest {
        val store = newStore()
        assertEquals(true, store.downloadsWifiOnlyFlow.first())

        store.setDownloadsWifiOnly(false)

        assertEquals(false, store.downloadsWifiOnlyFlow.first())
        assertFalse(
            fakeFlusher.calls.any { it.key == PlaybackSettingsKeys.DownloadsWifiOnly },
            "Downloads Wi-Fi-only controls WorkManager constraints and must not flush an unknown server key.",
        )
    }

    @Test
    fun `keep watched downloads is local-only`() = runTest {
        val store = newStore()
        assertEquals(false, store.keepWatchedDownloadsFlow.first())

        store.setKeepWatchedDownloads(true)

        assertEquals(true, store.keepWatchedDownloadsFlow.first())
        assertFalse(
            fakeFlusher.calls.any { it.key == PlaybackSettingsKeys.KeepWatchedDownloads },
            "Keep-watched cleanup preference is local and must not flush an unknown server key.",
        )
    }

    @Test
    fun `videoGravity rejects invalid value and falls back to fit`() = runTest {
        val store = newStore()
        store.setVideoGravity("garbage")
        assertEquals("fit", store.videoGravityFlow.first())
        store.setVideoGravity("fill")
        assertEquals("fill", store.videoGravityFlow.first())
    }

    @Test
    fun `audioSyncMs clamps to plus minus 5000 (iOS-parity range)`() = runTest {
        val store = newStore()
        store.setAudioSyncMs(99999)
        assertEquals(5000, store.audioSyncMsFlow.first())
        store.setAudioSyncMs(-99999)
        assertEquals(-5000, store.audioSyncMsFlow.first())
    }

    @Test
    fun `subtitleSyncMs clamps to plus minus 10000 (iOS-parity range)`() = runTest {
        val store = newStore()
        store.setSubtitleSyncMs(99999)
        assertEquals(10000, store.subtitleSyncMsFlow.first())
        store.setSubtitleSyncMs(-99999)
        assertEquals(-10000, store.subtitleSyncMsFlow.first())
    }

    // ---- Server-sync surface ------------------------------------------

    @Test
    fun `refreshFromServer populates flows from effective settings response`() = runTest {
        val repo = SettingsRepository(
            FakeSettingsApi(
                effective = mapOf(
                    PlaybackSettingsKeys.AutoSkipIntro to "true",
                    PlaybackSettingsKeys.AutoPlayNext to "false",
                    PlaybackSettingsKeys.PreferredQuality to "1080p",
                    PlaybackSettingsKeys.AudioSyncMs to "120",
                    PlaybackSettingsKeys.PlaybackSpeed to "1.5",
                ),
            ),
        )
        val store = newStore(repository = repo)
        store.refreshFromServer()
        assertEquals(true, store.autoSkipIntroFlow.first())
        assertEquals(false, store.autoPlayNextFlow.first())
        assertEquals("1080p", store.preferredQualityFlow.first())
        assertEquals(120, store.audioSyncMsFlow.first())
        assertEquals(1.5, store.playbackSpeedFlow.first(), 0.0)
    }

    @Test
    fun `refreshFromServer no-ops when repository is null`() = runTest {
        val store = newStore(repository = null)
        store.refreshFromServer() // should not throw or write
        assertEquals(false, store.autoSkipIntroFlow.first())
    }

    @Test
    fun `refreshFromServer reflects subtitle device override flag`() = runTest {
        val repo = SettingsRepository(
            FakeSettingsApi(
                effective = mapOf(
                    PlaybackSettingsKeys.SubtitleAppearance to SubtitleAppearance.DEFAULT.toJsonString(),
                ),
                hasDeviceOverride = setOf(PlaybackSettingsKeys.SubtitleAppearance),
            ),
        )
        val store = newStore(repository = repo)
        assertFalse(store.subtitleUsesDeviceOverrideFlow.first())
        store.refreshFromServer()
        assertTrue(store.subtitleUsesDeviceOverrideFlow.first())
    }

    @Test
    fun `refreshFromServer clears override flag when subtitle entry absent`() = runTest {
        // First refresh: server reports a device override; flag goes true.
        val api = FakeSettingsApi(
            effective = mapOf(
                PlaybackSettingsKeys.SubtitleAppearance to SubtitleAppearance.DEFAULT.toJsonString(),
            ),
            hasDeviceOverride = setOf(PlaybackSettingsKeys.SubtitleAppearance),
        )
        val store = newStore(repository = SettingsRepository(api))
        store.refreshFromServer()
        assertTrue(store.subtitleUsesDeviceOverrideFlow.first())

        // Server stops returning the entry — e.g. another device cleared
        // the override out-of-band. Flag must go false on the next
        // refresh; iOS parity in `applyEffectiveSettings`'s `else` branch.
        api.effective = emptyMap()
        api.hasDeviceOverride = emptySet()
        store.refreshFromServer()
        assertFalse(store.subtitleUsesDeviceOverrideFlow.first())
    }

    @Test
    fun `resetAllDeviceSettings enqueues delete for every device key`() = runTest {
        val repo = SettingsRepository(FakeSettingsApi())
        val store = newStore(repository = repo)
        store.resetAllDeviceSettings()
        val deletedKeys = fakeFlusher.calls.filter { it.isDelete }.map { it.key }.toSet()
        for (key in PlaybackSettingsKeys.DeviceSettings) {
            assertTrue(deletedKeys.contains(key), "expected delete for $key")
        }
    }

    @Test
    fun `setSubtitleDeviceOverrideEnabled false enqueues delete and clears local flag`() = runTest {
        val repo = SettingsRepository(FakeSettingsApi())
        val store = newStore(repository = repo)
        // Enabling first writes the override.
        store.setSubtitleDeviceOverrideEnabled(true)
        assertTrue(store.subtitleUsesDeviceOverrideFlow.first())
        // Now disable.
        store.setSubtitleDeviceOverrideEnabled(false)
        assertFalse(store.subtitleUsesDeviceOverrideFlow.first())
        assertTrue(
            fakeFlusher.calls.any {
                it.isDelete && it.key == PlaybackSettingsKeys.SubtitleAppearance
            },
            "expected delete enqueued for subtitle_appearance",
        )
    }

    @Test
    fun `disabling subtitle override retains last custom appearance locally`() = runTest {
        val fallback = SubtitleAppearance.DEFAULT.copy(fontColor = "#00ff00")
        val repo = SettingsRepository(
            FakeSettingsApi(
                effective = mapOf(
                    PlaybackSettingsKeys.SubtitleAppearance to fallback.toJsonString(),
                ),
            ),
        )
        val store = newStore(repository = repo)
        val custom = SubtitleAppearance.DEFAULT.copy(
            fontSize = SubtitleFontSizePreset.XXLarge,
            fontColor = "#ff0000",
        )

        store.setSubtitleAppearance(custom)
        store.setSubtitleDeviceOverrideEnabled(false)

        assertEquals(fallback, store.subtitleAppearanceFlow.first())
        assertEquals(custom, store.savedCustomSubtitleAppearanceFlow.first())

        store.setSubtitleDeviceOverrideEnabled(true)
        assertEquals(custom, store.subtitleAppearanceFlow.first())
    }

    @Test
    fun `flushPendingDeviceSettings delegates to flusher flushNow`() = runTest {
        val store = newStore()
        store.flushPendingDeviceSettings()
        assertEquals(1, fakeFlusher.flushNowCount)
    }

    /**
     * `Context` is needed by the AndroidPlayerSettingsStore constructor only as a
     * fallback for the default `dataStoreFactory`. Our tests inject a custom
     * factory, so the Context is never dereferenced — return a stub that
     * triggers a useful failure if anything ever does touch it.
     */
    private fun mockContextStub(): android.content.Context {
        return object : android.content.ContextWrapper(null) {}
    }
}

private class FakeServerSettingsFlusher : ServerSettingsFlusher {
    data class Call(val profileId: String, val key: String, val value: String?, val isDelete: Boolean)
    val calls = mutableListOf<Call>()
    var flushNowCount: Int = 0
    override fun enqueue(profileId: String, key: String, value: String) {
        calls.add(Call(profileId, key, value, isDelete = false))
    }

    override fun enqueueDelete(profileId: String, key: String) {
        calls.add(Call(profileId, key, value = null, isDelete = true))
    }

    override suspend fun flushNow() {
        flushNowCount++
    }
}

/** Stub SettingsApi returning canned effective values; HttpClient never used. */
private class FakeSettingsApi(
    effective: Map<String, String> = emptyMap(),
    hasDeviceOverride: Set<String> = emptySet(),
) : SettingsApi(HttpClient()) {
    // Mutable so a single test can simulate the server's response
    // changing between two `refreshFromServer` calls without standing
    // up a second DataStore over the same file.
    var effective: Map<String, String> = effective
    var hasDeviceOverride: Set<String> = hasDeviceOverride

    override suspend fun getEffectiveSettings(keys: List<String>): ApiResult<EffectiveSettingsResponse> {
        val entries = keys.mapNotNull { key ->
            val value = effective[key] ?: return@mapNotNull null
            EffectiveSetting(
                key = key,
                effectiveValue = value,
                source = "device",
                hasDeviceOverride = key in hasDeviceOverride,
            )
        }
        return ApiResult.Success(EffectiveSettingsResponse(entries))
    }

    override suspend fun setDeviceSetting(key: String, value: String, profileId: String?) =
        ApiResult.Success(Unit)

    override suspend fun deleteDeviceSetting(key: String) = ApiResult.Success(Unit)

    override suspend fun getEffectiveSubtitleAppearance(): ApiResult<EffectiveSubtitleAppearance> =
        ApiResult.Success(
            EffectiveSubtitleAppearance(
                key = PlaybackSettingsKeys.SubtitleAppearance,
                globalValue = SubtitleAppearance.DEFAULT.toJsonString(),
                effectiveValue = SubtitleAppearance.DEFAULT.toJsonString(),
            ),
        )
}

/**
 * In-memory stand-in for [AndroidServerSettingsCache] — bypasses the
 * SharedPreferences-backed implementation that requires a real Context.
 */
private class FakeLegacyCache : AndroidServerSettingsCache(stubContext()) {
    private val map = mutableMapOf<String, String>()
    private fun composite(serverUrl: String, key: String) = "${serverUrl.trimEnd('/')}|$key"

    override fun getString(serverUrl: String, key: String, defaultValue: String): String =
        map[composite(serverUrl, key)] ?: defaultValue

    override fun putString(serverUrl: String, key: String, value: String) {
        map[composite(serverUrl, key)] = value
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

/** Minimal SharedPreferences stub for the legacy-cache super constructor. */
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
