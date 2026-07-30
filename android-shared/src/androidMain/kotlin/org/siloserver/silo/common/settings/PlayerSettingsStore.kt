package org.siloserver.silo.common.settings

import org.siloserver.silo.model.settings.SubtitleAppearance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import org.siloserver.silo.player.DolbyVisionPolicy

interface PlayerSettingsStore {
    // Booleans
    val autoSkipIntroFlow: Flow<Boolean>
    val autoSkipCreditsFlow: Flow<Boolean>
    val autoPlayNextFlow: Flow<Boolean>
    val hdrEnabledFlow: Flow<Boolean>
    val dvProfile7HDR10FallbackFlow: Flow<Boolean>
    val dolbyVisionEnabledFlow: Flow<Boolean>
    val matchContentFrameRateFlow: Flow<Boolean>
    val pictureInPictureEnabledFlow: Flow<Boolean>
    /** Per-profile preference for restricting downloads to unmetered (Wi-Fi)
     *  networks. Default true. Consumed by [DownloadEnqueuer] at enqueue
     *  time to set the WorkManager NetworkType constraint. */
    val downloadsWifiOnlyFlow: Flow<Boolean>
    val keepWatchedDownloadsFlow: Flow<Boolean>
    val defaultDownloadQualityFlow: Flow<String>

    // Doubles
    val playbackSpeedFlow: Flow<Double>

    // Ints
    val audioSyncMsFlow: Flow<Int>
    /** Canonical device-scoped subtitle offset (`player.subtitle_sync_ms`). */
    val subtitleSyncMsFlow: Flow<Int>
    val nextUpPromptSecondsFlow: Flow<Int>
    val sleepTimerDefaultMinutesFlow: Flow<Int>
    /** Seconds to skip back on resume (F1). Default 7; 0 = off. Local-only. */
    val resumeRewindSecondsFlow: Flow<Int>
    /** Consecutive auto-advances before the "Still watching?" prompt (F2). Default 3; 0 = off. Local-only. */
    val passOutThresholdFlow: Flow<Int>

    /**
     * The bandwidth half of the quality choice, orthogonal to
     * [preferredQualityFlow]. null is uncapped, which is the absence of a
     * stored value rather than a sentinel.
     */
    val maxBitrateKbpsFlow: Flow<Int?>

    // Strings
    val preferredQualityFlow: Flow<String>
    val audioLanguageFlow: Flow<String>
    val videoGravityFlow: Flow<String>
    val orientationModeFlow: Flow<String>

    // Composite
    val subtitleAppearanceFlow: Flow<SubtitleAppearance>
    /** Last explicitly edited custom appearance, retained while its override is disabled. */
    val savedCustomSubtitleAppearanceFlow: Flow<SubtitleAppearance>
        get() = subtitleAppearanceFlow

    /**
     * Whether the user has enabled a device-scoped subtitle-appearance
     * override (mirrors iOS `subtitleUsesDeviceAppearanceOverride`).
     * When false, the effective subtitle appearance comes from the
     * user-level setting; when true, the local override is in effect.
     */
    val subtitleUsesDeviceOverrideFlow: Flow<Boolean>
    /** tvOS "Match Device Settings": appearance follows OS captioning prefs. */
    val subtitleMatchesDeviceFlow: Flow<Boolean>
    /** iOS AppNavPreferences.showAudiobooks parity — audiobook surfaces are opt-in. */
    val showAudiobooksFlow: Flow<Boolean>
    /** [subtitleAppearanceFlow] with the match-device override applied. */
    val effectiveSubtitleAppearanceFlow: Flow<org.siloserver.silo.model.settings.SubtitleAppearance>

    // Setters
    suspend fun setAutoSkipIntro(value: Boolean)
    suspend fun setAutoSkipCredits(value: Boolean)
    suspend fun setAutoPlayNext(value: Boolean)
    suspend fun setHdrEnabled(value: Boolean)
    suspend fun setDvProfile7HDR10Fallback(value: Boolean)
    suspend fun setDolbyVisionEnabled(value: Boolean)
    suspend fun setMatchContentFrameRate(value: Boolean)
    suspend fun setPictureInPictureEnabled(value: Boolean)
    suspend fun setDownloadsWifiOnly(value: Boolean)
    suspend fun setKeepWatchedDownloads(value: Boolean)
    suspend fun setDefaultDownloadQuality(value: String)

    suspend fun setPlaybackSpeed(value: Double)

    suspend fun setAudioSyncMs(value: Int)
    suspend fun setSubtitleSyncMs(value: Int)
    suspend fun setNextUpPromptSeconds(value: Int)
    suspend fun setSleepTimerDefaultMinutes(value: Int)
    /** Set resume skip-back seconds (clamped 0..30; 0 = off). */
    suspend fun setResumeRewindSeconds(value: Int)
    /** Set the pass-out prompt threshold (clamped 0..10; 0 = off). */
    suspend fun setPassOutThreshold(value: Int)

    suspend fun setPreferredQuality(value: String)

    /**
     * Set both quality axes at once — the two values one picker preset
     * decomposes into. Writing them together is what keeps the pair
     * consistent: a resolution stored without its bitrate is a combination no
     * preset covers, which the picker then has to render as "custom".
     * [bitrateKbps] null is uncapped.
     */
    suspend fun setQuality(resolution: String, bitrateKbps: Int?)
    suspend fun setAudioLanguage(value: String)
    suspend fun setVideoGravity(value: String)
    suspend fun setOrientationMode(value: String)

    suspend fun setSubtitleAppearance(value: SubtitleAppearance)

    /**
     * Project the granular, client-local `subtitle.*` fields into the
     * composite `playback.subtitle_appearance` and enqueue it.
     *
     * The contract carries subtitle appearance as one object and has no
     * definitions for the individual fields, so a per-field edit is
     * device-local until it is folded into the composite. Call this after
     * editing fields individually (the player HUD, a per-field picker); a
     * no-op when the projection already matches what is stored.
     */
    suspend fun flushProjectedSubtitleAppearance()

    /**
     * Pull every device-scoped setting from `/api/v1/settings/effective`
     * and write the resolved values into the local DataStore without
     * round-tripping them back to the server. Mirrors iOS
     * `PlayerSettings.refreshFromServer()`. Safe to call repeatedly
     * (idempotent with respect to local state); silently no-ops when no
     * profile is active or the network is unreachable.
     */
    suspend fun refreshFromServer()

    /**
     * Toggle the device-level override for `subtitle_appearance`. When
     * `enabled` is false, deletes the device override on the server and
     * refetches the effective appearance from the user-level setting.
     * When true, pushes the current local appearance up as a device
     * override.
     */
    suspend fun setSubtitleDeviceOverrideEnabled(enabled: Boolean)
    suspend fun setSubtitleMatchesDevice(enabled: Boolean)
    suspend fun setShowAudiobooks(enabled: Boolean)

    /**
     * Clear the server-side device override for one key. Local DataStore
     * is repopulated from the cascade (user → global → default) on the
     * next refresh.
     */
    suspend fun resetDeviceSetting(key: String)

    /**
     * Clear every server-side device override. Mirrors iOS
     * `PlayerSettings.resetAllDeviceSettings()` — the user's "Reset
     * Playback Overrides" action.
     */
    suspend fun resetAllDeviceSettings()

    /**
     * Cancel any in-flight debounce, drain pending writes, and suspend
     * until each one acks. Use this from app-lifecycle ON_STOP to make
     * sure settings the user just toggled survive a backgrounding /
     * process death window.
     */
    suspend fun flushPendingDeviceSettings()
}

/**
 * Current Dolby Vision decision inputs, read once at plan/load time. DV off
 * supersedes the Profile 7 fallback toggle (precedence enforced inside
 * [DolbyVisionPolicy]).
 */
suspend fun PlayerSettingsStore.dolbyVisionPolicySnapshot(): DolbyVisionPolicy.Snapshot =
    DolbyVisionPolicy.Snapshot(
        dolbyVisionEnabled = dolbyVisionEnabledFlow.first(),
        preferProfile7HDR10Fallback = dvProfile7HDR10FallbackFlow.first(),
    )
