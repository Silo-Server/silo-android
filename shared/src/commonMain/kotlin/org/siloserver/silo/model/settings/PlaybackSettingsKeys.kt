package org.siloserver.silo.model.settings

object PlaybackSettingsKeys {
    const val PreferredQuality = "playback.preferred_quality"
    const val AudioLanguage = "playback.audio_language"
    const val AutoSkipIntro = "playback.auto_skip_intro"
    const val AutoSkipCredits = "playback.auto_skip_credits"
    const val AutoPlayNext = "playback.auto_play_next"
    const val SubtitleAppearance = "subtitle_appearance"
    const val HdrEnabled = "player.hdr_enabled"
    const val PlaybackSpeed = "player.playback_speed"
    const val AudioSyncMs = "player.audio_sync_ms"
    const val SubtitleSyncMs = "player.subtitle_sync_ms"
    const val VideoGravity = "player.video_gravity"
    const val OrientationMode = "player.orientation_mode"
    const val NextUpPromptSeconds = "playback.next_up_prompt_seconds"
    const val DvProfile7HDR10Fallback = "player.dv_profile7_hdr10_fallback"
    const val DolbyVisionEnabled = "player.dolby_vision_enabled"

    /**
     * Local-only per-profile setting: switch the display refresh rate to match
     * the content frame rate. Not server-registered → excluded from
     * [DeviceSettings] (Apple keeps the equivalent toggle local too).
     */
    const val MatchContentFrameRate = "player.match_frame_rate"

    /**
     * Local-only per-profile setting: default duration preselected in the
     * player's sleep timer. Not server-registered → excluded from
     * [DeviceSettings].
     */
    const val SleepTimerDefaultMinutes = "player.sleep_timer_default_minutes"

    /*
     * Legacy per-field subtitle appearance names. The server registers subtitle
     * appearance as a single JSON blob under [SubtitleAppearance]
     * (`subtitle_appearance`), which is Android's source of truth — nothing
     * reads or writes these individual fields. They are not server-registered,
     * so they stay out of [DeviceSettings]; unifying or deleting them is
     * follow-up work (see issue #376).
     */
    const val SubtitleFontSize = "subtitle.font_size"
    const val SubtitleFontFamily = "subtitle.font_family"
    const val SubtitleTextColor = "subtitle.text_color"
    const val SubtitleBackgroundColor = "subtitle.background_color"
    const val SubtitleBackgroundStyle = "subtitle.background_style"
    const val SubtitleBackgroundOpacity = "subtitle.background_opacity"
    const val SubtitleTextOutline = "subtitle.text_outline"
    const val SubtitleTextOutlineColor = "subtitle.text_outline_color"
    const val SubtitlePosition = "subtitle.position"

    /**
     * Local-only flag tracking whether the user has enabled a per-device
     * subtitle appearance override. Mirrors iOS
     * `Keys.subtitleUsesDeviceAppearanceOverride` — never written to the
     * server; the server learns the same fact from the presence of a
     * `subtitle_appearance` device-scoped setting.
     */
    const val SubtitleUsesDeviceOverride = "subtitle.uses_device_override"

    /** Local-only: subtitle appearance follows the OS captioning settings
     *  (tvOS "Match Device Settings" parity). Never server-synced. */
    const val SubtitleMatchesDevice = "subtitle.matches_device.local"

    /** Local-only: opt-in Audiobooks surfaces in navigation (iOS
     *  AppNavPreferences.showAudiobooks parity; hidden by default). */
    const val NavShowAudiobooks = "nav.show_audiobooks.local"

    /**
     * Local-only per-profile flag — when true (default), DownloadWorker is
     * constrained to NetworkType.UNMETERED. Never synced to the server.
     */
    const val DownloadsWifiOnly = "downloads.wifi_only"

    /**
     * Local-only per-profile cleanup preference. When true, the client does not
     * suggest reclaiming watched downloads. Mirrors Apple
     * `downloads.keepWatchedDownloads`.
     */
    const val KeepWatchedDownloads = "downloads.keep_watched"

    /**
     * Local-only per-profile default for queued downloads. Values are
     * [org.siloserver.silo.model.download.DownloadQuality.wire] presets.
     * This stays local until the server exposes a synced setting; the selected
     * value is still sent on each download create request.
     */
    const val DefaultDownloadQuality = "downloads.default_quality"

    /**
     * Local-only per-profile setting: seconds to skip back when RESUMING a
     * partially-watched item, so context is re-established. Default 7; 0 = off.
     * Not server-registered, so it stays out of [DeviceSettings] (never pulled
     * from / overwritten by the server cascade).
     */
    const val ResumeRewindSeconds = "player.resume_rewind_seconds"

    /**
     * Local-only per-profile setting: number of consecutive auto-advanced
     * episodes allowed before the "Still watching?" prompt gates the next one
     * (pass-out protection). Default 3; 0 = off (never prompt). Not
     * server-registered → excluded from [DeviceSettings].
     */
    const val PassOutThreshold = "player.passout_threshold"

    /**
     * Local-only per-profile setting. Platform PiP is device/OS-specific and
     * not a server playback preference, so this never enters [DeviceSettings].
     */
    const val PictureInPictureEnabled = "player.picture_in_picture_enabled"

    /**
     * Keys that participate in the server's device-scope settings cascade:
     * pulled by `GET /settings/effective`, pushed by
     * `PUT /settings/device/{key}`, cleared by `DELETE /settings/device/{key}`.
     *
     * Every entry MUST be registered server-side; the server rejects writes and
     * resets for unregistered keys with HTTP 400. Keys Silo stores only on the
     * device stay out of this list and are written through the store's
     * `*Local` helpers.
     */
    val DeviceSettings = listOf(
        PreferredQuality,
        AudioLanguage,
        AutoSkipIntro,
        AutoSkipCredits,
        AutoPlayNext,
        SubtitleAppearance,
        HdrEnabled,
        PlaybackSpeed,
        AudioSyncMs,
        SubtitleSyncMs,
        VideoGravity,
        OrientationMode,
        NextUpPromptSeconds,
        DvProfile7HDR10Fallback,
        DolbyVisionEnabled,
    )
}
