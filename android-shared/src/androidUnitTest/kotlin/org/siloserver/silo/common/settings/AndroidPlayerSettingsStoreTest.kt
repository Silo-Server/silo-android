package org.siloserver.silo.common.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
import kotlin.test.assertNull
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
        dataStore: DataStore<Preferences>? = null,
    ): AndroidPlayerSettingsStore {
        // In-process DataStore factory backed by a temp directory. Tests that
        // need to seed the file up front pass the instance in — DataStore
        // rejects two live instances over the same file.
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
            dataStoreFactory = { id -> dataStore ?: newDataStore(id) },
        )
    }

    private fun newDataStore(id: String): DataStore<Preferences> {
        val file = File(tempFolder.root, "ds_$id.preferences_pb")
        return PreferenceDataStoreFactory.create(produceFile = { file })
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
        // Upper bound mirrors the server's validateFloatRange("player.playback_speed", 0.25, 3.0).
        store.setPlaybackSpeed(10.0)
        assertEquals(3.0, store.playbackSpeedFlow.first(), 0.0)
        store.setPlaybackSpeed(0.01)
        assertEquals(0.25, store.playbackSpeedFlow.first(), 0.0)
    }

    @Test
    fun `match frame rate and sleep timer stay local`() = runTest {
        val store = newStore()
        assertEquals(false, store.matchContentFrameRateFlow.first())
        assertEquals(30, store.sleepTimerDefaultMinutesFlow.first())

        store.setMatchContentFrameRate(true)
        store.setSleepTimerDefaultMinutes(45)

        assertEquals(true, store.matchContentFrameRateFlow.first())
        assertEquals(45, store.sleepTimerDefaultMinutesFlow.first())
        assertFalse(
            fakeFlusher.calls.any {
                it.key == PlaybackSettingsKeys.MatchContentFrameRate ||
                    it.key == PlaybackSettingsKeys.SleepTimerDefaultMinutes
            },
            "Neither key is in the server's settingsRegistry today, so a write would earn an " +
                "HTTP 400 that the flusher logs and drops. Both ARE registered as remote " +
                "profile_device settings in contracts/settings/v1/manifest.json, so this " +
                "assertion inverts when the server implements the manifest — it is pinning " +
                "current behaviour, not the end state.",
        )
    }

    @Test
    fun `pre-rename nextUpPromptSeconds migrates forward and is pushed to the server`() = runTest {
        // An install that stored the value under the old
        // "player.next_up_prompt_seconds" DataStore slot. `deviceId` is null in
        // these tests, so the scope prefix is empty.
        val dataStore = newDataStore(activeProfileId)
        dataStore.edit { it[intPreferencesKey("player.next_up_prompt_seconds")] = 15 }

        val store = newStore(dataStore = dataStore)
        assertEquals(15, store.nextUpPromptSecondsFlow.first())

        val prefs = dataStore.data.first()
        assertEquals(
            15,
            prefs[intPreferencesKey(PlaybackSettingsKeys.NextUpPromptSeconds)],
            "The value must be copied into the canonical slot, not merely read through a fallback.",
        )
        assertNull(
            prefs[intPreferencesKey("player.next_up_prompt_seconds")],
            "The legacy slot must be cleared so a stale value cannot resurface after a reset.",
        )

        // The push is what actually protects the value. applyEffectiveLocally
        // writes the server's effective value for every registered DeviceSettings
        // key on refresh, and the canonical key defaults to 30 server-side, so
        // without this the first refresh after upgrade would overwrite 15 with 30.
        assertTrue(
            fakeFlusher.calls.any {
                it.key == PlaybackSettingsKeys.NextUpPromptSeconds && it.value == "15"
            },
            "The migrated value must be enqueued so the server stops reporting the default.",
        )
    }

    @Test
    fun `nextUpPromptSeconds migration does not clobber an existing canonical value`() = runTest {
        val dataStore = newDataStore(activeProfileId)
        dataStore.edit {
            it[intPreferencesKey("player.next_up_prompt_seconds")] = 15
            it[intPreferencesKey(PlaybackSettingsKeys.NextUpPromptSeconds)] = 45
        }

        val store = newStore(dataStore = dataStore)
        assertEquals(45, store.nextUpPromptSecondsFlow.first())
        assertNull(dataStore.data.first()[intPreferencesKey("player.next_up_prompt_seconds")])
        assertTrue(
            fakeFlusher.calls.none { it.key == PlaybackSettingsKeys.NextUpPromptSeconds },
            "Nothing was migrated, so nothing should be pushed to the server.",
        )
    }

    @Test
    fun `nextUpPromptSeconds migration runs once and leaves later values alone`() = runTest {
        val dataStore = newDataStore(activeProfileId)
        dataStore.edit { it[intPreferencesKey("player.next_up_prompt_seconds")] = 15 }

        val store = newStore(dataStore = dataStore)
        assertEquals(15, store.nextUpPromptSecondsFlow.first())

        // A later write wins, and re-reading must not re-run the migration and
        // resurrect the pre-rename value.
        store.setNextUpPromptSeconds(20)
        assertEquals(20, store.nextUpPromptSecondsFlow.first())

        val fresh = newStore(dataStore = dataStore)
        assertEquals(20, fresh.nextUpPromptSecondsFlow.first())
    }

    @Test
    fun `refreshFromServer pushes the migrated value before it reads`() = runTest {
        // The regression this guards: withScope runs the migration and then
        // falls straight into the refresh. The migration's push is debounced, so
        // unless the refresh flushes first, the GET answers with the registry
        // default (30) and applyEffectiveLocally writes it over the 15 that was
        // just recovered — losing the value for exactly the users the migration
        // exists to protect.
        val dataStore = newDataStore(activeProfileId)
        dataStore.edit { it[intPreferencesKey("player.next_up_prompt_seconds")] = 15 }

        val api = FakeSettingsApi(
            effective = mapOf(PlaybackSettingsKeys.NextUpPromptSeconds to "30"),
        )
        fakeFlusher.sink = api

        val store = newStore(dataStore = dataStore, repository = SettingsRepository(api))
        store.refreshFromServer()

        assertEquals(
            15,
            store.nextUpPromptSecondsFlow.first(),
            "The refresh must flush the migrated value before reading, or it reads the default over it.",
        )
        assertTrue(fakeFlusher.flushNowCount > 0, "refreshFromServer must flush before it reads.")
    }

    @Test
    fun `legacy cache imports are pushed so the next refresh cannot overwrite them`() = runTest {
        // Importing into the local slot is only half the job: every DeviceSettings
        // key is server-registered, so the next refresh writes the registry
        // default back over anything that was not also pushed.
        fakeLegacyCache.putString(serverUrl, PlaybackSettingsKeys.AutoSkipIntro, "true")
        fakeLegacyCache.putString(serverUrl, PlaybackSettingsKeys.PreferredQuality, "1080p")

        val api = FakeSettingsApi(
            effective = mapOf(
                PlaybackSettingsKeys.AutoSkipIntro to "false",
                PlaybackSettingsKeys.PreferredQuality to "auto",
            ),
        )
        fakeFlusher.sink = api

        val store = newStore(repository = SettingsRepository(api))
        store.refreshFromServer()

        assertEquals(true, store.autoSkipIntroFlow.first())
        assertEquals("1080p", store.preferredQualityFlow.first())
        assertTrue(
            fakeFlusher.calls.any {
                it.key == PlaybackSettingsKeys.AutoSkipIntro && it.value == "true"
            },
            "An imported legacy value must be enqueued, not just written locally.",
        )
    }

    @Test
    fun `migrated value is clamped to the range the server accepts`() = runTest {
        // An out-of-range legacy value would be rejected with a 400 that the
        // flusher logs and drops, leaving the local slot holding something the
        // server will never agree with.
        val dataStore = newDataStore(activeProfileId)
        dataStore.edit { it[intPreferencesKey("player.next_up_prompt_seconds")] = 300 }

        val store = newStore(dataStore = dataStore)
        store.nextUpPromptSecondsFlow.first()

        assertTrue(
            fakeFlusher.calls.any {
                it.key == PlaybackSettingsKeys.NextUpPromptSeconds && it.value == "120"
            },
            "300 is outside 0..120 and must be clamped before being sent.",
        )
    }

    @Test
    fun `migration works on the scoped key path that actually ships`() = runTest {
        // Every other migration test runs with a null deviceId, which makes
        // keyPrefix empty. In production getDeviceId always resolves, so the
        // scoped prefix is the only path that ever runs.
        val deviceId = "device-abc"
        val prefix = "scope_" + sha256Hex("$serverUrl|$activeProfileId|$deviceId").take(24) + "."

        val dataStore = newDataStore(activeProfileId)
        dataStore.edit { it[intPreferencesKey(prefix + "player.next_up_prompt_seconds")] = 15 }

        val store = newStore(dataStore = dataStore, deviceId = deviceId)
        assertEquals(15, store.nextUpPromptSecondsFlow.first())

        val prefs = dataStore.data.first()
        assertEquals(
            15,
            prefs[intPreferencesKey(prefix + PlaybackSettingsKeys.NextUpPromptSeconds)],
            "The value must land in the scoped canonical slot.",
        )
        assertNull(
            prefs[intPreferencesKey(prefix + "player.next_up_prompt_seconds")],
            "The scoped legacy slot must be cleared.",
        )
        assertTrue(
            fakeFlusher.calls.any {
                it.key == PlaybackSettingsKeys.NextUpPromptSeconds && it.value == "15"
            },
        )
    }

    @Test
    fun `resetDeviceSetting refuses a key that is not server-synced`() = runTest {
        val store = newStore(repository = SettingsRepository(FakeSettingsApi()))
        var threw = false
        try {
            store.resetDeviceSetting(PlaybackSettingsKeys.PictureInPictureEnabled)
        } catch (_: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw, "A device-local key must not reach DELETE /settings/device/{key}.")
        assertTrue(fakeFlusher.calls.isEmpty(), "Nothing should have been queued for the server.")
    }

    @Test
    fun `flow emits default when no value stored`() = runTest {
        val store = newStore()
        assertEquals(false, store.autoSkipIntroFlow.first())
        assertEquals(true, store.autoPlayNextFlow.first())
        assertEquals(true, store.hdrEnabledFlow.first())
        // Server registry default for player.dv_profile7_hdr10_fallback is false.
        assertEquals(false, store.dvProfile7HDR10FallbackFlow.first())
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

/**
 * Mirrors AndroidPlayerSettingsStore's scope hashing so a test can address the
 * scoped DataStore slots the shipping configuration actually uses.
 */
private fun sha256Hex(s: String): String =
    java.security.MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { "%02x".format(it) }

private class FakeServerSettingsFlusher : ServerSettingsFlusher {
    data class Call(val profileId: String, val key: String, val value: String?, val isDelete: Boolean)
    val calls = mutableListOf<Call>()
    var flushNowCount: Int = 0

    /**
     * When set, [flushNow] applies queued ops to this API stub, the way a real
     * flush makes a value visible to the next `GET /settings/effective`.
     *
     * Tests that leave it null keep the old record-only behaviour. Tests that
     * set it can observe ordering: whether a value was pushed *before* the
     * refresh read, or after, when the read has already overwritten it.
     */
    var sink: FakeSettingsApi? = null
    private val pending = mutableListOf<Call>()

    override fun enqueue(profileId: String, key: String, value: String) {
        val call = Call(profileId, key, value, isDelete = false)
        calls.add(call)
        pending.add(call)
    }

    override fun enqueueDelete(profileId: String, key: String) {
        val call = Call(profileId, key, value = null, isDelete = true)
        calls.add(call)
        pending.add(call)
    }

    override suspend fun flushNow() {
        flushNowCount++
        sink?.let { api ->
            for (call in pending) {
                if (call.isDelete) {
                    api.effective = api.effective - call.key
                    api.hasDeviceOverride = api.hasDeviceOverride - call.key
                } else {
                    api.effective = api.effective + (call.key to call.value!!)
                    api.hasDeviceOverride = api.hasDeviceOverride + call.key
                }
            }
        }
        pending.clear()
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
