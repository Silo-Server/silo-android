package org.siloserver.silo.common.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import org.siloserver.silo.model.settings.EffectiveSettingValue
import org.siloserver.silo.model.settings.EffectiveSettingValuesResponse
import org.siloserver.silo.model.settings.EffectiveSubtitleAppearance
import org.siloserver.silo.model.settings.PlaybackSettingsKeys
import org.siloserver.silo.model.settings.SettingKeys
import org.siloserver.silo.model.settings.SettingScope
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
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

    /**
     * A store whose backing DataStore already holds [seed].
     *
     * The seeding goes through the SAME DataStore instance the store will use —
     * DataStore refuses two live instances over one file, so writing the
     * fixture through a second handle throws rather than setting up the state
     * under test.
     */
    private suspend fun newStoreSeededWith(
        seed: suspend (androidx.datastore.preferences.core.MutablePreferences) -> Unit,
    ): AndroidPlayerSettingsStore {
        val shared = PreferenceDataStoreFactory.create(
            produceFile = { File(tempFolder.root, "ds_seeded.preferences_pb") },
        )
        shared.edit { prefs -> seed(prefs) }
        return AndroidPlayerSettingsStore(
            context = mockContextStub(),
            legacyCache = fakeLegacyCache,
            getActiveProfileId = { activeProfileId },
            getServerUrl = { serverUrl },
            serverSettingsFlusher = fakeFlusher,
            scope = TestScope(),
            profileChangeSignal = flowOf(Unit),
            getDeviceId = { null },
            dataStoreFactory = { shared },
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
    fun `refreshFromServer populates flows from batched effective values`() = runTest {
        val repo = SettingsRepository(
            FakeSettingsApi(
                effective = mapOf(
                    stored(PlaybackSettingsKeys.AutoSkipIntro, JsonPrimitive(true)),
                    stored(PlaybackSettingsKeys.AutoPlayNext, JsonPrimitive(false)),
                    stored(PlaybackSettingsKeys.PreferredQuality, JsonPrimitive("1080p")),
                    stored(PlaybackSettingsKeys.AudioSyncMs, JsonPrimitive(120)),
                    stored(PlaybackSettingsKeys.PlaybackSpeed, JsonPrimitive(1.5)),
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
    fun `refreshFromServer requests only server-stored keys`() = runTest {
        val api = FakeSettingsApi()
        val store = newStore(repository = SettingsRepository(api))
        store.refreshFromServer()
        val remote = SettingKeys.REMOTE.toSet()
        assertTrue(api.requestedKeys.isNotEmpty())
        assertTrue(
            api.requestedKeys.all { it in remote },
            "asked for non-contract keys: ${api.requestedKeys.filterNot { it in remote }}",
        )
    }

    @Test
    fun `refreshFromServer applies contract defaults over stale local overrides`() = runTest {
        // The canonical endpoint answers every known key; one nothing is
        // stored for comes back as the contract default with source
        // "default". A stale local override (say the server-side value was
        // reset from another device) must hydrate back to that default
        // rather than surviving locally.
        val api = FakeSettingsApi(
            effective = mapOf(
                defaulted(PlaybackSettingsKeys.AutoSkipIntro, JsonPrimitive(false)),
                defaulted(PlaybackSettingsKeys.NextUpPromptSeconds, JsonPrimitive(30)),
            ),
        )
        val store = newStore(repository = SettingsRepository(api))
        store.setAutoSkipIntro(true)
        store.setNextUpPromptSeconds(90)

        store.refreshFromServer()

        assertEquals(false, store.autoSkipIntroFlow.first())
        assertEquals(30, store.nextUpPromptSecondsFlow.first())
    }

    @Test
    fun `refreshFromServer keeps local value for keys this server does not know`() = runTest {
        // A key absent from the response entirely means the server's
        // contract revision predates it — not that it was reset.
        val api = FakeSettingsApi(effective = emptyMap())
        val store = newStore(repository = SettingsRepository(api))
        store.setAutoSkipIntro(true)

        store.refreshFromServer()

        assertEquals(true, store.autoSkipIntroFlow.first())
    }

    @Test
    fun `refreshFromServer maps JSON null to the local no-preference spelling`() = runTest {
        val api = FakeSettingsApi(
            effective = mapOf(
                defaulted(PlaybackSettingsKeys.AudioLanguage, JsonNull),
            ),
        )
        val store = newStore(repository = SettingsRepository(api))
        store.setAudioLanguage("de")

        store.refreshFromServer()

        assertEquals("", store.audioLanguageFlow.first())
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
                    stored(
                        PlaybackSettingsKeys.SubtitleAppearance,
                        Json.parseToJsonElement(SubtitleAppearance.DEFAULT.toJsonString()),
                        scope = SettingScope.PROFILE_DEVICE.wire,
                    ),
                ),
            ),
        )
        val store = newStore(repository = repo)
        assertFalse(store.subtitleUsesDeviceOverrideFlow.first())
        store.refreshFromServer()
        assertTrue(store.subtitleUsesDeviceOverrideFlow.first())
    }

    @Test
    fun `refreshFromServer clears override flag when appearance no longer resolves from this device`() = runTest {
        // First refresh: the appearance resolves from profile_device; flag
        // goes true.
        val api = FakeSettingsApi(
            effective = mapOf(
                stored(
                    PlaybackSettingsKeys.SubtitleAppearance,
                    Json.parseToJsonElement(SubtitleAppearance.DEFAULT.toJsonString()),
                    scope = SettingScope.PROFILE_DEVICE.wire,
                ),
            ),
        )
        val store = newStore(repository = SettingsRepository(api))
        store.refreshFromServer()
        assertTrue(store.subtitleUsesDeviceOverrideFlow.first())

        // Another device cleared the override out-of-band; the value now
        // resolves from the contract default. Flag must go false on the
        // next refresh; iOS parity in `applyEffectiveSettings`'s `else`
        // branch.
        api.effective = mapOf(
            defaulted(
                PlaybackSettingsKeys.SubtitleAppearance,
                Json.parseToJsonElement(SubtitleAppearance.DEFAULT.toJsonString()),
            ),
        )
        store.refreshFromServer()
        assertFalse(store.subtitleUsesDeviceOverrideFlow.first())
    }

    @Test
    fun `resetAllDeviceSettings enqueues delete for every server-stored device key`() = runTest {
        val repo = SettingsRepository(FakeSettingsApi())
        val store = newStore(repository = repo)
        store.resetAllDeviceSettings()
        val deletedKeys = fakeFlusher.calls.filter { it.isDelete }.map { it.key }.toSet()
        val remote = SettingKeys.REMOTE.toSet()
        for (key in PlaybackSettingsKeys.DeviceSettings.filter { it in remote }) {
            assertTrue(deletedKeys.contains(key), "expected delete for $key")
        }
        // The granular subtitle.* fields live inside the composite
        // playback.subtitle_appearance object; deleting them individually
        // would be refused as unknown_setting.
        for (key in PlaybackSettingsKeys.DeviceSettings.filterNot { it in remote }) {
            assertFalse(deletedKeys.contains(key), "must not delete non-contract key $key")
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
                    // Resolves from the profile scope once the device
                    // override is deleted.
                    stored(
                        PlaybackSettingsKeys.SubtitleAppearance,
                        Json.parseToJsonElement(fallback.toJsonString()),
                        scope = SettingScope.PROFILE.wire,
                    ),
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
    fun `setQuality writes both axes and flushes them together`() = runTest {
        val store = newStore()
        store.setQuality("1080p", 10000)

        assertEquals("1080p", store.preferredQualityFlow.first())
        assertEquals(10000, store.maxBitrateKbpsFlow.first())
        val flushed = fakeFlusher.calls.filterNot { it.isDelete }.associate { it.key to it.value }
        assertEquals("1080p", flushed[PlaybackSettingsKeys.PreferredQuality])
        assertEquals("10000", flushed[PlaybackSettingsKeys.MaxBitrateKbps])
    }

    @Test
    fun `an uncapped preset stores no bitrate`() = runTest {
        val store = newStore()
        store.setQuality("1080p", 6000)
        store.setQuality("original", null)

        // null is uncapped, which the store spells as 0 — outside the
        // contract's range, so it can never read back as a real cap.
        assertEquals(null, store.maxBitrateKbpsFlow.first())
        assertEquals(
            "0",
            fakeFlusher.calls.last { it.key == PlaybackSettingsKeys.MaxBitrateKbps }.value,
        )
    }

    @Test
    fun `a legacy compound quality normalizes on read and on write`() = runTest {
        val store = newStore()
        // The bitrate a compound value encoded lives on its own axis now;
        // handing "1080p-high" to the player or the server would be refused.
        store.setPreferredQuality("1080p-high")

        assertEquals("1080p", store.preferredQualityFlow.first())
        assertEquals(
            "1080p",
            fakeFlusher.calls.last { it.key == PlaybackSettingsKeys.PreferredQuality }.value,
        )
    }

    @Test
    fun `a refresh resolving no bitrate clears a stale local cap`() = runTest {
        val repo = SettingsRepository(
            FakeSettingsApi(
                effective = mapOf(
                    stored(PlaybackSettingsKeys.PreferredQuality, JsonPrimitive("720p")),
                    defaulted(PlaybackSettingsKeys.MaxBitrateKbps, JsonNull),
                ),
            ),
        )
        val store = newStore(repository = repo)
        store.setQuality("1080p", 10000)

        store.refreshFromServer()

        assertEquals("720p", store.preferredQualityFlow.first())
        assertEquals(
            null,
            store.maxBitrateKbpsFlow.first(),
            "a null bitrate must clear the cap, not leave the previous one throttling playback",
        )
    }

    @Test
    fun `a value stored under a pre-cutover key name survives the rename`() = runTest {
        // The upgrade case. Both keys were renamed by the settings cutover, and
        // both read local-first — subtitle appearance drives downloaded
        // playback with no server in the loop — so an orphaned slot is a
        // silently reverted preference, not just a stale cache.
        val appearance = SubtitleAppearance.DEFAULT
            .copy(fontSize = SubtitleFontSizePreset.XXLarge)
            .toJsonString()
        val store = newStoreSeededWith { prefs ->
            prefs[stringPreferencesKey("subtitle_appearance")] = appearance
            prefs[intPreferencesKey("player.next_up_prompt_seconds")] = 12
        }

        assertEquals(SubtitleFontSizePreset.XXLarge, store.subtitleAppearanceFlow.first().fontSize)
        assertEquals(12, store.nextUpPromptSecondsFlow.first())
    }

    @Test
    fun `the rename migration never overwrites a value already under the new key`() = runTest {
        // A canonical refresh or a fresh edit outranks whatever the pre-rename
        // build left on disk; copying over it would revert the newer value.
        val store = newStoreSeededWith { prefs ->
            prefs[intPreferencesKey("player.next_up_prompt_seconds")] = 12
            prefs[intPreferencesKey(PlaybackSettingsKeys.NextUpPromptSeconds)] = 45
        }

        assertEquals(45, store.nextUpPromptSecondsFlow.first())
    }

    @Test
    fun `writes are stamped with the server they were authored against`() = runTest {
        // The flusher is application-scoped and its requests are relative, so a
        // queued op that outlives a server switch can only be told apart by the
        // origin the store stamps on it here.
        val store = newStore()
        store.setAutoSkipIntro(true)

        val call = fakeFlusher.calls.last { it.key == PlaybackSettingsKeys.AutoSkipIntro }
        assertEquals(serverUrl, call.serverUrl)
    }

    @Test
    fun `a granular subtitle field projects into the composite on flush`() = runTest {
        // A granular slot written WITHOUT a composite write — the state an
        // upgrading user lands in, because ensureMigrated imports each legacy
        // `subtitle.*` value straight into its granular slot. The contract has
        // no key for those fields, so this is the only path that carries them
        // to the server.
        fakeLegacyCache.putString(serverUrl, PlaybackSettingsKeys.SubtitleFontSize, "xxlarge")
        fakeLegacyCache.putString(serverUrl, PlaybackSettingsKeys.SubtitleTextColor, "#ff0000")
        val store = newStore()
        // Touch a flow so the migration import runs, then start clean.
        store.subtitleAppearanceFlow.first()
        fakeFlusher.calls.clear()

        store.flushProjectedSubtitleAppearance()

        val enqueued = fakeFlusher.calls.lastOrNull { it.key == PlaybackSettingsKeys.SubtitleAppearance }
        assertTrue(
            enqueued != null,
            "a granular edit that never reaches the composite is device-local forever",
        )
        val sent = SubtitleAppearance.decode(enqueued.value.orEmpty())
        assertEquals(SubtitleFontSizePreset.XXLarge, sent.fontSize)
        assertEquals("#ff0000", sent.fontColor)
    }

    @Test
    fun `a granular subtitle field overlays the composite on read`() = runTest {
        // The other half of the projection: until the flush catches up, the
        // granular slot is the newer edit and reads must show it, or the
        // settings screen renders the value the user just replaced.
        fakeLegacyCache.putString(serverUrl, PlaybackSettingsKeys.SubtitleFontSize, "small")
        val store = newStore()

        assertEquals(SubtitleFontSizePreset.Small, store.subtitleAppearanceFlow.first().fontSize)
    }

    @Test
    fun `flushing an unchanged projection enqueues nothing`() = runTest {
        val store = newStore()
        store.setSubtitleAppearance(
            SubtitleAppearance.DEFAULT.copy(fontSize = SubtitleFontSizePreset.XXLarge),
        )
        fakeFlusher.calls.clear()

        store.flushProjectedSubtitleAppearance()

        assertTrue(
            fakeFlusher.calls.none { it.key == PlaybackSettingsKeys.SubtitleAppearance },
            "an unchanged projection must not enqueue a redundant write",
        )
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
    data class Call(
        val profileId: String,
        val key: String,
        val value: String?,
        val isDelete: Boolean,
        val serverUrl: String,
    )
    val calls = mutableListOf<Call>()
    var flushNowCount: Int = 0
    override fun enqueue(profileId: String, key: String, value: String, serverUrl: String) {
        calls.add(Call(profileId, key, value, isDelete = false, serverUrl = serverUrl))
    }

    override fun enqueueDelete(profileId: String, key: String, serverUrl: String) {
        calls.add(Call(profileId, key, value = null, isDelete = true, serverUrl = serverUrl))
    }

    override suspend fun flushNow() {
        flushNowCount++
    }
}

/** One canned entry: a value stored at [scope] (default: profile_device). */
private fun stored(
    key: String,
    value: JsonElement,
    scope: String = SettingScope.PROFILE_DEVICE.wire,
): Pair<String, EffectiveSettingValue> =
    key to EffectiveSettingValue(key = key, value = value, source = scope, scope = scope)

/** One canned entry resolving to the contract default (nothing stored). */
private fun defaulted(key: String, value: JsonElement): Pair<String, EffectiveSettingValue> =
    key to EffectiveSettingValue(
        key = key,
        value = value,
        source = EffectiveSettingValue.SOURCE_DEFAULT,
    )

/** Stub SettingsApi returning canned canonical effective values; HttpClient never used. */
private class FakeSettingsApi(
    effective: Map<String, EffectiveSettingValue> = emptyMap(),
) : SettingsApi(HttpClient()) {
    // Mutable so a single test can simulate the server's response
    // changing between two `refreshFromServer` calls without standing
    // up a second DataStore over the same file.
    var effective: Map<String, EffectiveSettingValue> = effective
    var requestedKeys: List<String> = emptyList()

    override suspend fun getEffectiveValues(
        keys: List<String>,
        libraryIds: List<Int>,
        seriesIds: List<String>,
    ): ApiResult<EffectiveSettingValuesResponse> {
        requestedKeys = keys
        // Like the server: answer only the keys this contract knows.
        val entries = keys.mapNotNull { effective[it] }
        return ApiResult.Success(EffectiveSettingValuesResponse(settings = entries, revision = 1))
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
