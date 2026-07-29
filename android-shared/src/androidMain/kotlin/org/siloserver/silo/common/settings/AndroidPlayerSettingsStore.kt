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
import org.siloserver.silo.model.download.DownloadQuality
import org.siloserver.silo.model.settings.EffectiveSettingValue
import org.siloserver.silo.model.settings.LanguageOptions
import org.siloserver.silo.model.settings.PlaybackSettingsKeys
import org.siloserver.silo.model.settings.QualityPresets
import org.siloserver.silo.model.settings.SettingKeys
import org.siloserver.silo.model.settings.SettingScope
import org.siloserver.silo.model.settings.SubtitleAppearance
import org.siloserver.silo.model.settings.SubtitleAppearanceProjection
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

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

        /**
         * Separate from [migrationSentinel] deliberately: that one is already
         * marked on every install that has run a scoped build, so a rename
         * pass gated on it would never run for the devices holding the
         * orphaned pre-rename values.
         */
        val renameSentinel: String = "migration_rename_v1_$keyPrefix"
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
        migrateLegacyCache(scope, store)
        migrateRenamedKeys(scope, store)
    }

    private suspend fun migrateLegacyCache(scope: Scope, store: DataStore<Preferences>) {
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

    /**
     * Copies the two slots the settings cutover renamed
     * ([PlaybackSettingsKeys.RenamedLocalKeys]) into their current names.
     *
     * Carries its own sentinel rather than riding [migrateLegacyCache]'s: that
     * one is already marked on every device that has run this app since the
     * scoped-store change, so a pass gated on it would never execute for the
     * installs that actually hold the orphaned values. Both reads are
     * local-first — subtitle appearance drives downloaded playback with no
     * server in the loop — so skipping the copy silently reverts a preference
     * the user set until a canonical refresh happens to land.
     */
    private suspend fun migrateRenamedKeys(scope: Scope, store: DataStore<Preferences>) {
        val token = scope.profileId + "/" + scope.renameSentinel
        if (synchronized(migrationDone) { token in migrationDone }) return
        val sentinelKey = booleanPreferencesKey(scope.renameSentinel)
        val current = store.data.first()
        if (current[sentinelKey] != true) {
            store.edit { prefs ->
                for ((oldKey, newKey) in PlaybackSettingsKeys.RenamedLocalKeys) {
                    copyRenamedSlot(prefs, scope, oldKey = oldKey, newKey = newKey)
                }
                prefs[sentinelKey] = true
            }
        }
        synchronized(migrationDone) { migrationDone.add(token) }
    }

    /**
     * Copies one renamed slot, typed by the *new* key's contract type.
     *
     * The old slot may sit under this scope's prefix (written after the scoped
     * store landed) or unprefixed (written before it), so both are checked —
     * the same order [scopedRead] uses. A value already present under the new
     * name always wins: it is either a fresh edit or a canonical refresh, and
     * either outranks whatever the pre-rename build left behind.
     */
    private fun copyRenamedSlot(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        scope: Scope,
        oldKey: String,
        newKey: String,
    ) {
        val target = scope.keyPrefix + newKey
        when {
            isIntKey(newKey) -> {
                if (prefs[intPreferencesKey(target)] != null) return
                val legacy = prefs[intPreferencesKey(scope.keyPrefix + oldKey)]
                    ?: prefs[intPreferencesKey(oldKey)]
                    ?: return
                prefs[intPreferencesKey(target)] = legacy
            }
            isBooleanKey(newKey) -> {
                if (prefs[booleanPreferencesKey(target)] != null) return
                val legacy = prefs[booleanPreferencesKey(scope.keyPrefix + oldKey)]
                    ?: prefs[booleanPreferencesKey(oldKey)]
                    ?: return
                prefs[booleanPreferencesKey(target)] = legacy
            }
            else -> {
                if (prefs[stringPreferencesKey(target)] != null) return
                val legacy = prefs[stringPreferencesKey(scope.keyPrefix + oldKey)]
                    ?: prefs[stringPreferencesKey(oldKey)]
                    ?: return
                prefs[stringPreferencesKey(target)] = legacy
            }
        }
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

    override fun subtitleSyncMsFor(contentId: String?): Flow<Int> =
        if (contentId.isNullOrBlank()) {
            subtitleSyncMsFlow
        } else {
            profileScopedFlow(0) { p, s ->
                decodeSubtitleSyncOverrides(
                    p.stringFor(s, PlaybackSettingsKeys.SubtitleSyncMsByItem, ""),
                )[contentId]
                    ?: p.intFor(s, PlaybackSettingsKeys.SubtitleSyncMs, 0)
            }
        }

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

    // Uncapped is spelled 0 locally (Preferences cannot hold a null) and
    // translated to JSON null on the wire — 0 is outside the contract's
    // 100..200000 range, so it can never collide with a real cap.
    override val maxBitrateKbpsFlow: Flow<Int?> =
        profileScopedFlow(null) { p, s ->
            p.intFor(s, PlaybackSettingsKeys.MaxBitrateKbps, 0).takeIf { it > 0 }
        }

    // ---- Strings -------------------------------------------------------
    // Legacy compound spellings ("1080p-high") are normalized on read: the
    // bitrate they encoded lives on its own axis now, and handing a compound
    // value to the player or back to the server would be refused.
    override val preferredQualityFlow: Flow<String> =
        profileScopedFlow(QualityPresets.RESOLUTION_AUTO) { p, s ->
            QualityPresets.normalizeResolution(
                p.stringFor(s, PlaybackSettingsKeys.PreferredQuality, QualityPresets.RESOLUTION_AUTO),
            )
        }

    // Older builds stored the display name ("English") here rather than a BCP 47
    // tag. Those values are rejected by the server and never matched a track, so
    // they are translated on read instead of being handed to ExoPlayer or
    // re-sent. Anything already a tag passes through untouched.
    override val audioLanguageFlow: Flow<String> =
        profileScopedFlow("") { p, s ->
            LanguageOptions.migrateLegacyValue(
                p.stringFor(s, PlaybackSettingsKeys.AudioLanguage, ""),
            )
        }

    override val videoGravityFlow: Flow<String> =
        profileScopedFlow("fit") { p, s -> p.stringFor(s, PlaybackSettingsKeys.VideoGravity, "fit") }

    override val orientationModeFlow: Flow<String> =
        profileScopedFlow("auto") { p, s -> p.stringFor(s, PlaybackSettingsKeys.OrientationMode, "auto") }

    // The composite is the stored truth, but the granular subtitle.* slots are
    // a live overlay: the player's per-field controls write them directly, and
    // a value there that the composite has not caught up with is the newer
    // edit. Projecting on read (and again on flush) is what keeps a per-field
    // change from being invisible to the server.
    override val subtitleAppearanceFlow: Flow<SubtitleAppearance> =
        profileScopedFlow(SubtitleAppearance.DEFAULT) { p, s -> p.projectedAppearance(s) }

    override val savedCustomSubtitleAppearanceFlow: Flow<SubtitleAppearance> =
        profileScopedFlow(SubtitleAppearance.DEFAULT) { p, s ->
            val saved = p[stringPreferencesKey(s.keyPrefix + SAVED_CUSTOM_SUBTITLE_APPEARANCE)]
            if (saved != null) SubtitleAppearance.decode(saved) else p.projectedAppearance(s)
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
            serverSettingsFlusher.enqueue(
                scope.profileId,
                PlaybackSettingsKeys.PlaybackSpeed,
                clamped.toString(),
                scope.serverUrl,
            )
        }
    }

    override suspend fun setAudioSyncMs(value: Int) =
        writeInt(PlaybackSettingsKeys.AudioSyncMs, value.coerceIn(-5000, 5000))

    override suspend fun setSubtitleSyncMs(value: Int) =
        writeInt(PlaybackSettingsKeys.SubtitleSyncMs, value.coerceIn(-10000, 10000))

    override suspend fun setSubtitleSyncMsFor(contentId: String, value: Int) {
        if (contentId.isBlank()) return
        val clamped = value.coerceIn(-10000, 10000)
        withScope { scope, store ->
            store.edit { prefs ->
                val globalKey = intPreferencesKey(scope.keyPrefix + PlaybackSettingsKeys.SubtitleSyncMs)
                val mapKey = stringPreferencesKey(
                    scope.keyPrefix + PlaybackSettingsKeys.SubtitleSyncMsByItem,
                )
                val global = prefs[globalKey] ?: 0
                val current = decodeSubtitleSyncOverrides(prefs[mapKey].orEmpty())
                val next = LinkedHashMap(current).apply {
                    remove(contentId)
                    // Matching the profile default needs no override; dropping it
                    // keeps the map from filling with no-op entries.
                    if (clamped != global) put(contentId, clamped)
                }
                prefs[mapKey] = encodeSubtitleSyncOverrides(next)
            }
        }
    }

    override suspend fun setNextUpPromptSeconds(value: Int) =
        writeInt(PlaybackSettingsKeys.NextUpPromptSeconds, value.coerceIn(0, 120))

    override suspend fun setResumeRewindSeconds(value: Int) =
        writeIntLocal(PlaybackSettingsKeys.ResumeRewindSeconds, value.coerceIn(0, 30))

    override suspend fun setPassOutThreshold(value: Int) =
        writeIntLocal(PlaybackSettingsKeys.PassOutThreshold, value.coerceIn(0, 10))

    override suspend fun setSleepTimerDefaultMinutes(value: Int) =
        writeInt(PlaybackSettingsKeys.SleepTimerDefaultMinutes, value.coerceIn(0, 240))

    override suspend fun setPreferredQuality(value: String) =
        writeString(PlaybackSettingsKeys.PreferredQuality, QualityPresets.normalizeResolution(value))

    override suspend fun setQuality(resolution: String, bitrateKbps: Int?) {
        val normalized = QualityPresets.normalizeResolution(resolution)
        // 0 is the local spelling of uncapped; the flusher turns it into the
        // JSON null the contract means by "no cap".
        val capped = bitrateKbps?.takeIf { it > 0 }?.coerceIn(MIN_BITRATE_KBPS, MAX_BITRATE_KBPS) ?: 0
        withScope { scope, store ->
            store.edit {
                it[stringPreferencesKey(scope.keyPrefix + PlaybackSettingsKeys.PreferredQuality)] = normalized
                it[intPreferencesKey(scope.keyPrefix + PlaybackSettingsKeys.MaxBitrateKbps)] = capped
            }
            serverSettingsFlusher.enqueue(
                scope.profileId,
                PlaybackSettingsKeys.PreferredQuality,
                normalized,
                scope.serverUrl,
            )
            serverSettingsFlusher.enqueue(
                scope.profileId,
                PlaybackSettingsKeys.MaxBitrateKbps,
                capped.toString(),
                scope.serverUrl,
            )
        }
    }

    override suspend fun setAudioLanguage(value: String) =
        writeString(PlaybackSettingsKeys.AudioLanguage, value)

    override suspend fun setVideoGravity(value: String) {
        val safe = if (value in VALID_VIDEO_GRAVITY) value else "fit"
        writeString(PlaybackSettingsKeys.VideoGravity, safe)
    }

    override suspend fun setOrientationMode(value: String) =
        writeString(PlaybackSettingsKeys.OrientationMode, value)

    override suspend fun setSubtitleAppearance(value: SubtitleAppearance) {
        val sanitized = value.sanitized()
        val json = sanitized.toJsonString()
        withScope { scope, store ->
            store.edit { prefs ->
                prefs[stringPreferencesKey(scope.keyPrefix + PlaybackSettingsKeys.SubtitleAppearance)] = json
                prefs[stringPreferencesKey(scope.keyPrefix + SAVED_CUSTOM_SUBTITLE_APPEARANCE)] = json
                // The granular slots are rewritten from the composite rather
                // than left behind: they are read back as an overlay, so a
                // stale field would resurrect the value the user just replaced.
                writeGranularAppearance(prefs, scope, sanitized)
                // Setting an explicit appearance implicitly enables the
                // device override (matches iOS `setSubtitleAppearance`).
                prefs[booleanPreferencesKey(scope.keyPrefix + PlaybackSettingsKeys.SubtitleUsesDeviceOverride)] = true
            }
            serverSettingsFlusher.enqueue(scope.profileId, PlaybackSettingsKeys.SubtitleAppearance, json, scope.serverUrl)
        }
    }

    /**
     * Flushes the granular `subtitle.*` slots into the composite and enqueues
     * it, so a per-field edit made anywhere (the player HUD, a TV picker)
     * reaches the server as `playback.subtitle_appearance`.
     *
     * Callable on its own because the granular fields have no key of their own
     * on the wire: without this projection a per-field write is device-local
     * forever, which is the drift this exists to close.
     */
    override suspend fun flushProjectedSubtitleAppearance() {
        withScope { scope, store ->
            val snapshot = store.data.first()
            val projected = snapshot.projectedAppearance(scope)
            val json = projected.toJsonString()
            if (snapshot.stringFor(scope, PlaybackSettingsKeys.SubtitleAppearance, "") == json) return@withScope
            store.edit { prefs ->
                prefs[stringPreferencesKey(scope.keyPrefix + PlaybackSettingsKeys.SubtitleAppearance)] = json
                prefs[stringPreferencesKey(scope.keyPrefix + SAVED_CUSTOM_SUBTITLE_APPEARANCE)] = json
            }
            serverSettingsFlusher.enqueue(scope.profileId, PlaybackSettingsKeys.SubtitleAppearance, json, scope.serverUrl)
        }
    }

    private fun writeGranularAppearance(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        scope: Scope,
        appearance: SubtitleAppearance,
    ) {
        for ((key, raw) in SubtitleAppearanceProjection.flatten(appearance)) {
            writeRawString(prefs, scope, key, raw)
        }
    }

    // ---- Server-sync surface ------------------------------------------

    override suspend fun refreshFromServer() {
        val repo = settingsRepository ?: return
        withScope { scope, store ->
            // Batched canonical resolution: one request answers every
            // device-relevant key, each with the scope it resolved from.
            val result = repo.getEffectiveValues(RemoteDeviceSettings)
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
                val sanitized = appearance.sanitized()
                val json = sanitized.toJsonString()
                store.edit {
                    it[booleanPreferencesKey(scope.keyPrefix + PlaybackSettingsKeys.SubtitleUsesDeviceOverride)] = true
                    it[stringPreferencesKey(scope.keyPrefix + PlaybackSettingsKeys.SubtitleAppearance)] = json
                    it[stringPreferencesKey(scope.keyPrefix + SAVED_CUSTOM_SUBTITLE_APPEARANCE)] = json
                    // The granular slots overlay the composite on read, so
                    // restoring the saved appearance has to restore them too —
                    // otherwise the fields left by whatever resolved while the
                    // override was off win right back over it.
                    writeGranularAppearance(it, scope, sanitized)
                }
                serverSettingsFlusher.enqueue(scope.profileId, PlaybackSettingsKeys.SubtitleAppearance, json, scope.serverUrl)
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
                serverSettingsFlusher.enqueueDelete(scope.profileId, PlaybackSettingsKeys.SubtitleAppearance, scope.serverUrl)
                serverSettingsFlusher.flushNow()
                refreshFromServer()
            }
        }
    }

    override suspend fun resetDeviceSetting(key: String) {
        withScope { scope, _ ->
            serverSettingsFlusher.enqueueDelete(scope.profileId, key, scope.serverUrl)
            serverSettingsFlusher.flushNow()
            refreshFromServer()
        }
    }

    override suspend fun resetAllDeviceSettings() {
        withScope { scope, store ->
            // Only the server-stored keys have rows to delete; the granular
            // subtitle.* fields live inside playback.subtitle_appearance.
            for (key in RemoteDeviceSettings) {
                serverSettingsFlusher.enqueueDelete(scope.profileId, key, scope.serverUrl)
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
        effective: Map<String, EffectiveSettingValue>,
    ) {
        store.edit { prefs ->
            for (key in RemoteDeviceSettings) {
                // The canonical endpoint answers every known key, including
                // ones with nothing stored anywhere — those come back with
                // source "default" and the contract default as the value, so
                // writing each entry hydrates defaults from the contract
                // rather than from anything hardcoded here. A key absent
                // from the response is one this server's contract does not
                // know (older revision); the local value is kept rather than
                // guessed at.
                val entry = effective[key] ?: continue
                writeJsonValue(prefs, scope, key, entry.value)
            }
            // The override flag mirrors where the subtitle appearance
            // actually resolved from. Clearing it when the value no longer
            // comes from this device keeps a previous session's flag from
            // surviving a server-side reset.
            val subtitleEntry = effective[PlaybackSettingsKeys.SubtitleAppearance]
            val hasDeviceOverride = subtitleEntry?.scope == SettingScope.PROFILE_DEVICE.wire
            prefs[booleanPreferencesKey(scope.keyPrefix + PlaybackSettingsKeys.SubtitleUsesDeviceOverride)] =
                hasDeviceOverride
            if (subtitleEntry != null) {
                // The granular slots are an overlay on the composite, so a
                // resolved appearance has to be flattened back into them. Left
                // alone, the previous device's field values would win over the
                // value the server just said applies.
                writeGranularAppearance(
                    prefs,
                    scope,
                    SubtitleAppearance.decode(subtitleEntry.value.toString()),
                )
                if (hasDeviceOverride) {
                    prefs[stringPreferencesKey(scope.keyPrefix + SAVED_CUSTOM_SUBTITLE_APPEARANCE)] =
                        subtitleEntry.value.toString()
                }
            }
        }
    }

    /**
     * Writes one typed JSON value from the canonical effective response into
     * the local slot each flow reads: booleans and ints natively, doubles as
     * their string spelling, objects as their JSON document, JSON null as the
     * empty string (the local spelling of "no preference"), and everything
     * else as the primitive's content.
     */
    private fun writeJsonValue(
        prefs: androidx.datastore.preferences.core.MutablePreferences,
        scope: Scope,
        key: String,
        value: JsonElement,
    ) {
        val scopedName = scope.keyPrefix + key
        when {
            isBooleanKey(key) -> (value as? JsonPrimitive)?.booleanOrNull?.let {
                prefs[booleanPreferencesKey(scopedName)] = it
            }
            isIntKey(key) -> {
                // A nullable int (max_bitrate_kbps) resolving to null means
                // "no cap", which the store spells as 0. Skipping the write
                // instead would leave a previous cap in place and quietly keep
                // throttling playback the server just said to stop throttling.
                val resolved = (value as? JsonPrimitive)?.intOrNull
                    ?: if (value is JsonNull && key in NULLABLE_INT_SETTINGS) 0 else null
                resolved?.let { prefs[intPreferencesKey(scopedName)] = it }
            }
            isDoubleKey(key) -> (value as? JsonPrimitive)?.doubleOrNull?.let {
                prefs[stringPreferencesKey(scopedName)] = it.toString()
            }
            value is JsonNull -> prefs[stringPreferencesKey(scopedName)] = ""
            value is JsonObject -> prefs[stringPreferencesKey(scopedName)] = value.toString()
            value is JsonPrimitive -> prefs[stringPreferencesKey(scopedName)] = value.content
            else -> prefs[stringPreferencesKey(scopedName)] = value.toString()
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
            serverSettingsFlusher.enqueue(scope.profileId, key, value.toString(), scope.serverUrl)
        }
    }

    private suspend fun writeInt(key: String, value: Int) {
        withScope { scope, store ->
            store.edit { it[intPreferencesKey(scope.keyPrefix + key)] = value }
            serverSettingsFlusher.enqueue(scope.profileId, key, value.toString(), scope.serverUrl)
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
            serverSettingsFlusher.enqueue(scope.profileId, key, value, scope.serverUrl)
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

    /**
     * The composite appearance with the granular, client-local `subtitle.*`
     * slots merged over it.
     *
     * The contract has no definitions for the granular fields, so they never
     * leave the device on their own — but a per-field edit still has to reach
     * the server, and this is where the two representations are reconciled.
     * Merging is sparse (an absent or unparseable field leaves the composite's
     * value alone), matching the schema's own rule for a stored appearance.
     */
    private fun Preferences.projectedAppearance(scope: Scope): SubtitleAppearance {
        val base = SubtitleAppearance.decode(
            stringFor(scope, PlaybackSettingsKeys.SubtitleAppearance, ""),
        )
        val granular = SubtitleAppearanceProjection.GRANULAR_KEYS.associateWith { key ->
            when {
                isBooleanKey(key) ->
                    this[booleanPreferencesKey(scope.keyPrefix + key)]?.toString()
                        ?: this[booleanPreferencesKey(key)]?.toString()
                isIntKey(key) ->
                    this[intPreferencesKey(scope.keyPrefix + key)]?.toString()
                        ?: this[intPreferencesKey(key)]?.toString()
                else -> stringFor(scope, key, "").takeIf { it.isNotBlank() }
            }
        }
        return SubtitleAppearanceProjection.project(granular, base)
    }

    private companion object {
        const val SAVED_CUSTOM_SUBTITLE_APPEARANCE = "subtitle_appearance.saved_custom"
        const val MIGRATION_SENTINEL_LEGACY = "migration_v1"
        const val MISSING_SENTINEL = "__missing__"
        // F1/F2 local-only defaults (mirror DefaultResumeRewindSeconds=7.0 and
        // the previous hardcoded AutoPlayGuard threshold of 3).
        const val DEFAULT_RESUME_REWIND_SECONDS = 7
        const val DEFAULT_PASSOUT_THRESHOLD = 3
        // The contract's playback.max_bitrate_kbps bounds; a value outside
        // them is rejected as invalid_value and the flush would be dropped.
        const val MIN_BITRATE_KBPS = 100
        const val MAX_BITRATE_KBPS = 200_000
        val VALID_VIDEO_GRAVITY = setOf("fit", "fill", "stretch")

        // Type classification comes from the generated contract rather than a
        // hand-kept list. This used to be a second table that had to agree with
        // PlaybackSettingsKeys.DeviceSettings by discipline alone; a key added
        // to one and missed in the other would flush as the wrong type and be
        // silently dropped on read.
        //
        // The extras below are the granular subtitle appearance fields Android
        // flattens locally. The contract carries them as one composite object
        // (playback.subtitle_appearance), so they have no generated entry and
        // are listed here as the local-only values they are.
        /**
         * The device-relevant keys the canonical batched endpoint can
         * answer: the store's device set minus the granular subtitle.*
         * fields Android flattens locally (the contract carries those as
         * the one composite playback.subtitle_appearance object, so the
         * resolver has no definitions for them).
         */
        val RemoteDeviceSettings: List<String> = SettingKeys.REMOTE.toSet().let { remote ->
            PlaybackSettingsKeys.DeviceSettings.filter { it in remote }
        }

        val BOOLEAN_KEYS: Set<String> = SettingKeys.BOOLEAN_KEYS +
            setOf(PlaybackSettingsKeys.SubtitleTextOutline)

        val INT_KEYS: Set<String> = SettingKeys.INT_KEYS +
            setOf(PlaybackSettingsKeys.SubtitleBackgroundOpacity)

        val DOUBLE_KEYS: Set<String> = SettingKeys.DOUBLE_KEYS

        /** Int keys whose contract null means "no cap", stored locally as 0. */
        val NULLABLE_INT_SETTINGS: Set<String> = setOf(SettingKeys.PLAYBACK_MAX_BITRATE_KBPS)

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

/**
 * `contentId=ms` pairs separated by newlines. Deliberately not JSON: the values
 * are a string id and an int, and this store already speaks plain preference
 * strings, so a serializer dependency here would buy nothing.
 *
 * Ids containing the separators are dropped rather than escaped — no catalog id
 * looks like that, and silently corrupting a neighbouring entry would be worse
 * than losing an override the user can set again.
 */
internal fun decodeSubtitleSyncOverrides(raw: String): Map<String, Int> {
    if (raw.isBlank()) return emptyMap()
    val out = LinkedHashMap<String, Int>()
    for (line in raw.lineSequence()) {
        val id = line.substringBefore('=', "").trim()
        val ms = line.substringAfter('=', "").trim().toIntOrNull()
        if (id.isNotEmpty() && ms != null) out[id] = ms
    }
    return out
}

internal fun encodeSubtitleSyncOverrides(overrides: Map<String, Int>): String =
    overrides.entries
        .filter { (id, _) -> id.isNotBlank() && '=' !in id && '\n' !in id }
        // Bounded so a long viewing history cannot grow this preference without
        // limit; the most recently written entries are the ones worth keeping.
        .takeLast(MAX_SUBTITLE_SYNC_OVERRIDES)
        .joinToString(separator = "\n") { (id, ms) -> "$id=$ms" }

private const val MAX_SUBTITLE_SYNC_OVERRIDES = 200
