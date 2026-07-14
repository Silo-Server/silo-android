package org.siloserver.silo.common.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import org.siloserver.silo.model.settings.EffectiveSetting
import org.siloserver.silo.model.download.DownloadQuality
import org.siloserver.silo.model.settings.PlaybackSettingsKeys
import org.siloserver.silo.model.settings.SubtitleAppearance
import org.siloserver.silo.network.ApiResult
import org.siloserver.silo.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

@OptIn(ExperimentalCoroutinesApi::class)
class AndroidPlayerSettingsStore(
    private val context: Context,
    private val legacyCache: AndroidServerSettingsCache,
    private val getActiveProfileId: suspend () -> String?,
    private val getServerUrl: suspend () -> String?,
    private val serverSettingsFlusher: ServerSettingsFlusher,
    @Suppress("unused") private val scope: CoroutineScope,
    private val profileChangeSignal: Flow<Unit> = flowOf(Unit),
    private val settingsRepository: SettingsRepository? = null,
    private val getDeviceId: suspend () -> String? = { null },
    private val serverChangeSignal: Flow<Unit> = flowOf(Unit),
    private val dataStoreFactory: (profileId: String) -> DataStore<Preferences> = { profileId ->
        PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile(fileNameFor(profileId)) },
        )
    },
) : PlayerSettingsStore {

    private val storeCache = mutableMapOf<String, DataStore<Preferences>>()
    private val migrationDone = mutableSetOf<String>()

    /**
     * Tuple identifying which device-scope settings file applies right now.
     * Mirrors iOS `currentScopeIdentifier` (server | profile | device).
     * When `serverUrl` or `deviceId` is blank, we fall back to legacy
     * per-profile-only keys (no prefix), preserving compatibility with
     * existing installs.
     */
    private data class Scope(
        val profileId: String,
        val serverUrl: String,
        val deviceId: String,
    ) {
        val keyPrefix: String =
            if (serverUrl.isBlank() || deviceId.isBlank()) {
                ""
            } else {
                "scope_" + sha256Hex("$serverUrl|$profileId|$deviceId").take(24) + "."
            }
        val migrationSentinel: String =
            if (keyPrefix.isEmpty()) MIGRATION_SENTINEL_LEGACY else "migration_v2_$keyPrefix"
    }

    // Re-derive scope on every (profile or server) change.
    private val scopeChangeSignal: Flow<Unit> =
        combine(profileChangeSignal, serverChangeSignal) { _, _ -> Unit }

    private val currentScopeFlow: Flow<Scope?> = flow {
        emit(currentScope())
        scopeChangeSignal.collect { emit(currentScope()) }
    }.distinctUntilChanged()

    private suspend fun currentScope(): Scope? {
        val profileId = getActiveProfileId() ?: return null
        return Scope(
            profileId = profileId,
            serverUrl = getServerUrl().orEmpty(),
            deviceId = getDeviceId().orEmpty(),
        )
    }

    private fun storeFor(profileId: String): DataStore<Preferences> =
        synchronized(storeCache) {
            storeCache.getOrPut(profileId) { dataStoreFactory(profileId) }
        }

    private suspend fun ensureMigrated(scope: Scope, store: DataStore<Preferences>) {
        val token = scope.profileId + "/" + scope.migrationSentinel
        if (synchronized(migrationDone) { token in migrationDone }) return
        val sentinelKey = booleanPreferencesKey(scope.migrationSentinel)
        val current = store.data.first()
        if (current[sentinelKey] == true) {
            synchronized(migrationDone) { migrationDone.add(token) }
            return
        }
        store.edit { prefs ->
            for (key in PlaybackSettingsKeys.DeviceSettings) {
                val legacy = legacyCache.getString(scope.serverUrl, key, MISSING_SENTINEL)
                if (legacy == MISSING_SENTINEL) continue
                writeRawString(prefs, scope, key, legacy)
            }
            prefs[sentinelKey] = true
        }
        synchronized(migrationDone) { migrationDone.add(token) }
    }

    private fun <T> profileScopedFlow(default: T, read: (Preferences, Scope) -> T): Flow<T> =
        currentScopeFlow.flatMapLatest { scope ->
            if (scope == null) {
                flowOf(default)
            } else {
                val store = storeFor(scope.profileId)
                flow {
                    ensureMigrated(scope, store)
                    emitAll(store.data.map { read(it, scope) })
                }
            }
        }

    // ---- Booleans ------------------------------------------------------
    override val autoSkipIntroFlow: Flow<Boolean> =
        profileScopedFlow(false) { p, s -> p.boolFor(s, PlaybackSettingsKeys.AutoSkipIntro, false) }

    override val autoSkipCreditsFlow: Flow<Boolean> =
        profileScopedFlow(false) { p, s -> p.boolFor(s, PlaybackSettingsKeys.AutoSkipCredits, false) }

    override val autoPlayNextFlow: Flow<Boolean> =
        profileScopedFlow(true) { p, s -> p.boolFor(s, PlaybackSettingsKeys.AutoPlayNext, true) }

    override val hdrEnabledFlow: Flow<Boolean> =
        profileScopedFlow(true) { p, s -> p.boolFor(s, PlaybackSettingsKeys.HdrEnabled, true) }

    override val dvProfile7HDR10FallbackFlow: Flow<Boolean> =
        profileScopedFlow(true) { p, s -> p.boolFor(s, PlaybackSettingsKeys.DvProfile7HDR10Fallback, true) }

    override val dolbyVisionEnabledFlow: Flow<Boolean> =
        profileScopedFlow(true) { p, s -> p.boolFor(s, PlaybackSettingsKeys.DolbyVisionEnabled, true) }

    // Default OFF — Apple TV's "Match Content" defaults off because the HDMI
    // mode switch black-screens for 1-2s on entry/exit (Apple parity,
    // QA 2026-07-08).
    override val matchContentFrameRateFlow: Flow<Boolean> =
        profileScopedFlow(false) { p, s -> p.boolFor(s, PlaybackSettingsKeys.MatchContentFrameRate, false) }

    override val pictureInPictureEnabledFlow: Flow<Boolean> =
        profileScopedFlow(true) { p, s -> p.boolFor(s, PlaybackSettingsKeys.PictureInPictureEnabled, true) }

    override val downloadsWifiOnlyFlow: Flow<Boolean> =
        profileScopedFlow(true) { p, s -> p.boolFor(s, PlaybackSettingsKeys.DownloadsWifiOnly, true) }

    override val keepWatchedDownloadsFlow: Flow<Boolean> =
        profileScopedFlow(false) { p, s -> p.boolFor(s, PlaybackSettingsKeys.KeepWatchedDownloads, false) }

    override val defaultDownloadQualityFlow: Flow<String> =
        profileScopedFlow(DownloadQuality.Original.wire) { p, s ->
            DownloadQuality.fromWire(
                p.stringFor(s, PlaybackSettingsKeys.DefaultDownloadQuality, DownloadQuality.Original.wire)
            ).wire
        }

    override val subtitleUsesDeviceOverrideFlow: Flow<Boolean> =
        profileScopedFlow(false) { p, s ->
            p.boolFor(s, PlaybackSettingsKeys.SubtitleUsesDeviceOverride, false)
        }

    // ---- Doubles -------------------------------------------------------
    override val playbackSpeedFlow: Flow<Double> =
        profileScopedFlow(1.0) { p, s ->
            p.stringFor(s, PlaybackSettingsKeys.PlaybackSpeed, "1.0").toDoubleOrNull() ?: 1.0
        }

    // ---- Ints ----------------------------------------------------------
    override val audioSyncMsFlow: Flow<Int> =
        profileScopedFlow(0) { p, s -> p.intFor(s, PlaybackSettingsKeys.AudioSyncMs, 0) }

    override val subtitleSyncMsFlow: Flow<Int> =
        profileScopedFlow(0) { p, s -> p.intFor(s, PlaybackSettingsKeys.SubtitleSyncMs, 0) }

    override val nextUpPromptSecondsFlow: Flow<Int> =
        profileScopedFlow(30) { p, s -> p.intFor(s, PlaybackSettingsKeys.NextUpPromptSeconds, 30) }

    override val sleepTimerDefaultMinutesFlow: Flow<Int> =
        profileScopedFlow(30) { p, s -> p.intFor(s, PlaybackSettingsKeys.SleepTimerDefaultMinutes, 30) }

    override val resumeRewindSecondsFlow: Flow<Int> =
        profileScopedFlow(DEFAULT_RESUME_REWIND_SECONDS) { p, s ->
            p.intFor(s, PlaybackSettingsKeys.ResumeRewindSeconds, DEFAULT_RESUME_REWIND_SECONDS)
        }

    override val passOutThresholdFlow: Flow<Int> =
        profileScopedFlow(DEFAULT_PASSOUT_THRESHOLD) { p, s ->
            p.intFor(s, PlaybackSettingsKeys.PassOutThreshold, DEFAULT_PASSOUT_THRESHOLD)
        }

    // ---- Strings -------------------------------------------------------
    override val preferredQualityFlow: Flow<String> =
        profileScopedFlow("auto") { p, s -> p.stringFor(s, PlaybackSettingsKeys.PreferredQuality, "auto") }

    override val audioLanguageFlow: Flow<String> =
        profileScopedFlow("") { p, s -> p.stringFor(s, PlaybackSettingsKeys.AudioLanguage, "") }

    override val videoGravityFlow: Flow<String> =
        profileScopedFlow("fit") { p, s -> p.stringFor(s, PlaybackSettingsKeys.VideoGravity, "fit") }

    override val orientationModeFlow: Flow<String> =
        profileScopedFlow("auto") { p, s -> p.stringFor(s, PlaybackSettingsKeys.OrientationMode, "auto") }

    override val subtitleAppearanceFlow: Flow<SubtitleAppearance> =
        profileScopedFlow(SubtitleAppearance.DEFAULT) { p, s ->
            SubtitleAppearance.decode(p.stringFor(s, PlaybackSettingsKeys.SubtitleAppearance, ""))
        }

    override val savedCustomSubtitleAppearanceFlow: Flow<SubtitleAppearance> =
        profileScopedFlow(SubtitleAppearance.DEFAULT) { p, s ->
            SubtitleAppearance.decode(
                p[stringPreferencesKey(s.keyPrefix + SAVED_CUSTOM_SUBTITLE_APPEARANCE)]
                    ?: p.stringFor(s, PlaybackSettingsKeys.SubtitleAppearance, ""),
            )
        }

    override val subtitleMatchesDeviceFlow: Flow<Boolean> =
        profileScopedFlow(false) { p, s -> p.boolFor(s, PlaybackSettingsKeys.SubtitleMatchesDevice, false) }

    override val showAudiobooksFlow: Flow<Boolean> =
        profileScopedFlow(false) { p, s -> p.boolFor(s, PlaybackSettingsKeys.NavShowAudiobooks, false) }

    // tvOS "Match Device Settings" parity: when on, playback styling follows
    // the OS captioning preferences (CaptioningManager); the stored custom
    // appearance stays untouched underneath.
    override val effectiveSubtitleAppearanceFlow: Flow<SubtitleAppearance> =
        kotlinx.coroutines.flow.combine(
            subtitleAppearanceFlow,
            subtitleMatchesDeviceFlow,
        ) { appearance, matchDevice ->
            if (matchDevice) deviceCaptioningAppearance(context, appearance) else appearance
        }

    override suspend fun setSubtitleMatchesDevice(enabled: Boolean) =
        writeBoolLocal(PlaybackSettingsKeys.SubtitleMatchesDevice, enabled)

    override suspend fun setShowAudiobooks(enabled: Boolean) =
        writeBoolLocal(PlaybackSettingsKeys.NavShowAudiobooks, enabled)

    // ---- Setters (write to scoped key + enqueue server flush) ---------
    override suspend fun setAutoSkipIntro(value: Boolean) =
        writeBool(PlaybackSettingsKeys.AutoSkipIntro, value)

    override suspend fun setAutoSkipCredits(value: Boolean) =
        writeBool(PlaybackSettingsKeys.AutoSkipCredits, value)

    override suspend fun setAutoPlayNext(value: Boolean) =
        writeBool(PlaybackSettingsKeys.AutoPlayNext, value)

    override suspend fun setHdrEnabled(value: Boolean) =
        writeBool(PlaybackSettingsKeys.HdrEnabled, value)

    override suspend fun setDvProfile7HDR10Fallback(value: Boolean) =
        writeBool(PlaybackSettingsKeys.DvProfile7HDR10Fallback, value)

    override suspend fun setDolbyVisionEnabled(value: Boolean) =
        writeBool(PlaybackSettingsKeys.DolbyVisionEnabled, value)

    override suspend fun setMatchContentFrameRate(value: Boolean) =
        writeBool(PlaybackSettingsKeys.MatchContentFrameRate, value)

    override suspend fun setPictureInPictureEnabled(value: Boolean) =
        writeBoolLocal(PlaybackSettingsKeys.PictureInPictureEnabled, value)

    override suspend fun setDownloadsWifiOnly(value: Boolean) =
        writeBoolLocal(PlaybackSettingsKeys.DownloadsWifiOnly, value)

    override suspend fun setKeepWatchedDownloads(value: Boolean) =
        writeBoolLocal(PlaybackSettingsKeys.KeepWatchedDownloads, value)

    override suspend fun setDefaultDownloadQuality(value: String) =
        writeStringLocal(PlaybackSettingsKeys.DefaultDownloadQuality, DownloadQuality.fromWire(value).wire)

    override suspend fun setPlaybackSpeed(value: Double) {
        val clamped = value.coerceIn(0.25, 4.0)
        withScope { scope, store ->
            store.edit { it[stringPreferencesKey(scope.keyPrefix + PlaybackSettingsKeys.PlaybackSpeed)] = clamped.toString() }
            serverSettingsFlusher.enqueue(scope.profileId, PlaybackSettingsKeys.PlaybackSpeed, clamped.toString())
        }
    }

    override suspend fun setAudioSyncMs(value: Int) =
        writeInt(PlaybackSettingsKeys.AudioSyncMs, value.coerceIn(-5000, 5000))

    override suspend fun setSubtitleSyncMs(value: Int) =
        writeInt(PlaybackSettingsKeys.SubtitleSyncMs, value.coerceIn(-10000, 10000))

    override suspend fun setNextUpPromptSeconds(value: Int) =
        writeInt(PlaybackSettingsKeys.NextUpPromptSeconds, value.coerceIn(0, 120))

    override suspend fun setResumeRewindSeconds(value: Int) =
        writeIntLocal(PlaybackSettingsKeys.ResumeRewindSeconds, value.coerceIn(0, 30))

    override suspend fun setPassOutThreshold(value: Int) =
        writeIntLocal(PlaybackSettingsKeys.PassOutThreshold, value.coerceIn(0, 10))

    override suspend fun setSleepTimerDefaultMinutes(value: Int) =
        writeInt(PlaybackSettingsKeys.SleepTimerDefaultMinutes, value.coerceIn(0, 240))

    override suspend fun setPreferredQuality(value: String) =
        writeString(PlaybackSettingsKeys.PreferredQuality, value)

    override suspend fun setAudioLanguage(value: String) =
        writeString(PlaybackSettingsKeys.AudioLanguage, value)

    override suspend fun setVideoGravity(value: String) {
        val safe = if (value in VALID_VIDEO_GRAVITY) value else "fit"
        writeString(PlaybackSettingsKeys.VideoGravity, safe)
    }

    override suspend fun setOrientationMode(value: String) =
        writeString(PlaybackSettingsKeys.OrientationMode, value)

    override suspend fun setSubtitleAppearance(value: SubtitleAppearance) {
        val json = value.sanitized().toJsonString()
        withScope { scope, store ->
            store.edit {
                it[stringPreferencesKey(scope.keyPrefix + PlaybackSettingsKeys.SubtitleAppearance)] = json
                it[stringPreferencesKey(scope.keyPrefix + SAVED_CUSTOM_SUBTITLE_APPEARANCE)] = json
                // Setting an explicit appearance implicitly enables the
                // device override (matches iOS `setSubtitleAppearance`).
                it[booleanPreferencesKey(scope.keyPrefix + PlaybackSettingsKeys.SubtitleUsesDeviceOverride)] = true
            }
            serverSettingsFlusher.enqueue(scope.profileId, PlaybackSettingsKeys.SubtitleAppearance, json)
        }
    }

    // ---- Server-sync surface ------------------------------------------

    override suspend fun refreshFromServer() {
        val repo = settingsRepository ?: return
        withScope { scope, store ->
            val result = repo.getEffectiveSettings(PlaybackSettingsKeys.DeviceSettings)
            if (result !is ApiResult.Success) return@withScope
            applyEffectiveLocally(scope, store, result.data)
        }
    }

    override suspend fun setSubtitleDeviceOverrideEnabled(enabled: Boolean) {
        withScope { scope, store ->
            val snapshot = store.data.first()
            val current = snapshot.boolFor(scope, PlaybackSettingsKeys.SubtitleUsesDeviceOverride, false)
            if (current == enabled) return@withScope

            if (enabled) {
                val appearance = SubtitleAppearance.decode(
                    snapshot[stringPreferencesKey(scope.keyPrefix + SAVED_CUSTOM_SUBTITLE_APPEARANCE)]
                        ?: snapshot.stringFor(scope, PlaybackSettingsKeys.SubtitleAppearance, "")
                )
                val json = appearance.sanitized().toJsonString()
                store.edit {
                    it[booleanPreferencesKey(scope.keyPrefix + PlaybackSettingsKeys.SubtitleUsesDeviceOverride)] = true
                    it[stringPreferencesKey(scope.keyPrefix + PlaybackSettingsKeys.SubtitleAppearance)] = json
                    it[stringPreferencesKey(scope.keyPrefix + SAVED_CUSTOM_SUBTITLE_APPEARANCE)] = json
                }
                serverSettingsFlusher.enqueue(scope.profileId, PlaybackSettingsKeys.SubtitleAppearance, json)
                serverSettingsFlusher.flushNow()
            } else {
                store.edit {
                    val savedCustomKey = stringPreferencesKey(scope.keyPrefix + SAVED_CUSTOM_SUBTITLE_APPEARANCE)
                    if (snapshot[savedCustomKey] == null) {
                        snapshot.stringFor(scope, PlaybackSettingsKeys.SubtitleAppearance, "")
                            .takeIf { value -> value.isNotBlank() }
                            ?.let { value -> it[savedCustomKey] = value }
                    }
                    it[booleanPreferencesKey(scope.keyPrefix + PlaybackSettingsKeys.SubtitleUsesDeviceOverride)] = false
                }
                serverSettingsFlusher.enqueueDelete(scope.profileId, PlaybackSettingsKeys.SubtitleAppearance)
                serverSettingsFlusher.flushNow()
                refreshFromServer()
            }
        }
    }

    override suspend fun resetDeviceSetting(key: String) {
        withScope { scope, _ ->
            serverSettingsFlusher.enqueueDelete(scope.profileId, key)
            serverSettingsFlusher.flushNow()
            refreshFromServer()
        }
    }

    override suspend fun resetAllDeviceSettings() {
        withScope { scope, store ->
            for (key in PlaybackSettingsKeys.DeviceSettings) {
                serverSettingsFlusher.enqueueDelete(scope.profileId, key)
            }
            store.edit {
                it[booleanPreferencesKey(scope.keyPrefix + PlaybackSettingsKeys.SubtitleUsesDeviceOverride)] = false
            }
            serverSettingsFlusher.flushNow()
            refreshFromServer()
        }
    }

    override suspend fun flushPendingDeviceSettings() {
        serverSettingsFlusher.flushNow()
    }

    private suspend fun applyEffectiveLocally(
        scope: Scope,
        store: DataStore<Preferences>,
        effective: Map<String, EffectiveSetting>,
    ) {
        store.edit { prefs ->
            for (key in PlaybackSettingsKeys.DeviceSettings) {
                val entry = effective[key] ?: continue
                writeRawString(prefs, scope, key, entry.effectiveValue)
            }
            // Clear the override flag when the server reports no
            // device-scoped subtitle_appearance — otherwise a previous
            // session's flag could survive a server-side reset.
            val subtitleEntry = effective[PlaybackSettingsKeys.SubtitleAppearance]
            prefs[booleanPreferencesKey(scope.keyPrefix + PlaybackSettingsKeys.SubtitleUsesDeviceOverride)] =
                subtitleEntry?.hasDeviceOverride ?: false
            if (subtitleEntry?.hasDeviceOverride == true) {
                prefs[stringPreferencesKey(scope.keyPrefix + SAVED_CUSTOM_SUBTITLE_APPEARANCE)] =
                    subtitleEntry.effectiveValue
            }
        }
    }

    private fun writeRawString(prefs: androidx.datastore.preferences.core.MutablePreferences, scope: Scope, key: String, raw: String) {
        val scopedName = scope.keyPrefix + key
        when {
            isBooleanKey(key) -> raw.toBooleanStrictOrNull()?.let {
                prefs[booleanPreferencesKey(scopedName)] = it
            }
            isIntKey(key) -> raw.toIntOrNull()?.let {
                prefs[intPreferencesKey(scopedName)] = it
            }
            isDoubleKey(key) -> raw.toDoubleOrNull()?.let {
                prefs[stringPreferencesKey(scopedName)] = it.toString()
            }
            else -> prefs[stringPreferencesKey(scopedName)] = raw
        }
    }

    private suspend fun writeBool(key: String, value: Boolean) {
        withScope { scope, store ->
            store.edit { it[booleanPreferencesKey(scope.keyPrefix + key)] = value }
            serverSettingsFlusher.enqueue(scope.profileId, key, value.toString())
        }
    }

    private suspend fun writeInt(key: String, value: Int) {
        withScope { scope, store ->
            store.edit { it[intPreferencesKey(scope.keyPrefix + key)] = value }
            serverSettingsFlusher.enqueue(scope.profileId, key, value.toString())
        }
    }

    /**
     * Persist an Int that the server does not know about: write the scoped
     * DataStore slot only, with NO server flush. Used for keys absent from
     * [PlaybackSettingsKeys.DeviceSettings] so an unknown-key flush can't be
     * rejected or poison a batch.
     */
    private suspend fun writeIntLocal(key: String, value: Int) {
        withScope { scope, store ->
            store.edit { it[intPreferencesKey(scope.keyPrefix + key)] = value }
        }
    }

    private suspend fun writeBoolLocal(key: String, value: Boolean) {
        withScope { scope, store ->
            store.edit { it[booleanPreferencesKey(scope.keyPrefix + key)] = value }
        }
    }

    private suspend fun writeStringLocal(key: String, value: String) {
        withScope { scope, store ->
            store.edit { it[stringPreferencesKey(scope.keyPrefix + key)] = value }
        }
    }

    private suspend fun writeString(key: String, value: String) {
        withScope { scope, store ->
            store.edit { it[stringPreferencesKey(scope.keyPrefix + key)] = value }
            serverSettingsFlusher.enqueue(scope.profileId, key, value)
        }
    }

    private suspend inline fun withScope(
        block: (scope: Scope, store: DataStore<Preferences>) -> Unit,
    ) {
        val scope = currentScope() ?: return
        val store = storeFor(scope.profileId)
        ensureMigrated(scope, store)
        block(scope, store)
    }

    // Read a value at `scope.keyPrefix + baseKey`, falling back to the
    // unscoped legacy key when the scoped slot is absent. `Preferences`
    // can't store nulls, so a null lookup means "not set" — no
    // `contains` probe needed.
    private inline fun <T> Preferences.scopedRead(
        scope: Scope,
        baseKey: String,
        default: T,
        keyOf: (String) -> Preferences.Key<T>,
    ): T {
        this[keyOf(scope.keyPrefix + baseKey)]?.let { return it }
        if (scope.keyPrefix.isNotEmpty()) {
            this[keyOf(baseKey)]?.let { return it }
        }
        return default
    }

    private fun Preferences.boolFor(scope: Scope, baseKey: String, default: Boolean): Boolean =
        scopedRead(scope, baseKey, default, ::booleanPreferencesKey)

    private fun Preferences.intFor(scope: Scope, baseKey: String, default: Int): Int =
        scopedRead(scope, baseKey, default, ::intPreferencesKey)

    private fun Preferences.stringFor(scope: Scope, baseKey: String, default: String): String =
        scopedRead(scope, baseKey, default, ::stringPreferencesKey)

    private companion object {
        const val SAVED_CUSTOM_SUBTITLE_APPEARANCE = "subtitle_appearance.saved_custom"
        const val MIGRATION_SENTINEL_LEGACY = "migration_v1"
        const val MISSING_SENTINEL = "__missing__"
        // F1/F2 local-only defaults (mirror DefaultResumeRewindSeconds=7.0 and
        // the previous hardcoded AutoPlayGuard threshold of 3).
        const val DEFAULT_RESUME_REWIND_SECONDS = 7
        const val DEFAULT_PASSOUT_THRESHOLD = 3
        val VALID_VIDEO_GRAVITY = setOf("fit", "fill", "stretch")

        val BOOLEAN_KEYS: Set<String> = setOf(
            PlaybackSettingsKeys.AutoSkipIntro,
            PlaybackSettingsKeys.AutoSkipCredits,
            PlaybackSettingsKeys.AutoPlayNext,
            PlaybackSettingsKeys.HdrEnabled,
            PlaybackSettingsKeys.DvProfile7HDR10Fallback,
            PlaybackSettingsKeys.DolbyVisionEnabled,
            PlaybackSettingsKeys.MatchContentFrameRate,
            PlaybackSettingsKeys.SubtitleTextOutline,
        )

        val INT_KEYS: Set<String> = setOf(
            PlaybackSettingsKeys.AudioSyncMs,
            PlaybackSettingsKeys.SubtitleSyncMs,
            PlaybackSettingsKeys.NextUpPromptSeconds,
            PlaybackSettingsKeys.SleepTimerDefaultMinutes,
            PlaybackSettingsKeys.SubtitleBackgroundOpacity,
        )

        val DOUBLE_KEYS: Set<String> = setOf(
            PlaybackSettingsKeys.PlaybackSpeed,
        )

        fun isBooleanKey(key: String): Boolean = key in BOOLEAN_KEYS
        fun isIntKey(key: String): Boolean = key in INT_KEYS
        fun isDoubleKey(key: String): Boolean = key in DOUBLE_KEYS

        fun fileNameFor(profileId: String): String =
            "silo_player_settings_${profileHash(profileId)}"

        fun profileHash(profileId: String): String =
            sha256Hex(profileId).take(16)

        fun sha256Hex(s: String): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(s.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { "%02x".format(it) }
    }
}
