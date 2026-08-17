package org.siloserver.silo.common.settings

import org.siloserver.silo.domain.player.IntroSkipMode
import org.siloserver.silo.model.settings.SubtitleAppearance
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.siloserver.silo.player.DolbyVisionPolicy

/**
 * How far the phone player may expand a picture whose letterbox is encoded into
 * the video (see the player's `LetterboxMatte`). Expansion never crops picture —
 * it only eats black the file itself carries — so the question a user actually
 * has is what to do about the camera cutout once the image reaches the edges.
 */
object LetterboxExpansion {
    /** Expand, but keep the image clear of the display cutout. */
    const val ClearOfCamera = "clear_of_camera"

    /** Expand to the full display width; the camera overlaps the picture. */
    const val FullWidth = "full_width"

    /** Never expand — the coded frame is fitted whole, bars and all. */
    const val Off = "off"

    /**
     * Biggest picture that is neither cropped nor covered. Expansion itself is
     * free (the clip lands in encoded black), so it is on; stopping at the
     * cutout costs width but is what keeps the whole image visible.
     */
    const val Default = ClearOfCamera

    val Valid = setOf(ClearOfCamera, FullWidth, Off)
}

interface PlayerSettingsStore {
    /**
     * What the player does when an intro starts — `playback.intro_skip_mode`,
     * contract revision 7. See the server's
     * `docs/design/2026-08-16-intro-skip-mode.md`.
     */
    val introSkipModeFlow: Flow<IntroSkipMode>

    // Booleans
    /**
     * The deprecated boolean, projected from [introSkipModeFlow] rather than
     * read separately so the two cannot disagree — `always` is the only mode
     * the boolean's `true` ever meant, and `never` degrades to the same `false`
     * an old client would have shown as "ask".
     *
     * Nothing in the app should read this: it exists for the compatibility
     * window while the server still mirrors the two keys.
     */
    val autoSkipIntroFlow: Flow<Boolean>
        get() = introSkipModeFlow.map { it == IntroSkipMode.ALWAYS }
    val autoSkipCreditsFlow: Flow<Boolean>
    val autoPlayNextFlow: Flow<Boolean>
    val hdrEnabledFlow: Flow<Boolean>
    val dvProfile7HDR10FallbackFlow: Flow<Boolean>
    val dolbyVisionEnabledFlow: Flow<Boolean>
    val matchContentFrameRateFlow: Flow<Boolean>
    val pictureInPictureEnabledFlow: Flow<Boolean>

    /**
     * How far to expand video whose black bars are encoded into the picture
     * (a 2.39:1 film inside a 16:9 frame) — see [LetterboxExpansion].
     *
     * Defaulted here rather than declared abstract so the existing fakes in the
     * player tests keep compiling; the real store overrides it.
     */
    val letterboxExpansionFlow: Flow<String>
        get() = flowOf(LetterboxExpansion.Default)

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
    suspend fun setIntroSkipMode(value: IntroSkipMode)

    /**
     * Deprecated shim for the boolean. Routes to [setIntroSkipMode] so a caller
     * that has not moved yet still writes the canonical key; the server mirrors
     * the boolean back at the same identity.
     */
    suspend fun setAutoSkipIntro(value: Boolean) =
        setIntroSkipMode(IntroSkipMode.fromLegacyBoolean(value))
    suspend fun setAutoSkipCredits(value: Boolean)
    suspend fun setAutoPlayNext(value: Boolean)
    suspend fun setHdrEnabled(value: Boolean)
    suspend fun setDvProfile7HDR10Fallback(value: Boolean)
    suspend fun setDolbyVisionEnabled(value: Boolean)
    suspend fun setMatchContentFrameRate(value: Boolean)
    suspend fun setPictureInPictureEnabled(value: Boolean)
    suspend fun setLetterboxExpansion(value: String) = Unit
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
     * Return this device's playback settings to their defaults — the user's
     * "Reset playback settings" action. Clears every server-side device
     * override (as iOS `PlayerSettings.resetAllDeviceSettings()` does) and the
     * local-only playback keys that have no server row to clear, since those
     * would otherwise survive an action whose whole promise is the defaults.
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
