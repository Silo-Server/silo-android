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
    const val NextUpPromptSeconds = "player.next_up_prompt_seconds"
    const val DvProfile7HDR10Fallback = "player.dv_profile7_hdr10_fallback"
    const val SleepTimerDefaultMinutes = "player.sleep_timer_default_minutes"
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

    /**
     * Local-only per-profile flag — when true (default), DownloadWorker is
     * constrained to NetworkType.UNMETERED. Never synced to the server.
     */
    const val DownloadsWifiOnly = "downloads.wifi_only"

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
     * Local-only per-profile setting: whether the phone video player may
     * enter Picture-in-Picture when the user leaves the app. Default true.
     * Phone-only (never TV). Not server-registered → excluded from
     * [DeviceSettings] so a flush can't be rejected.
     */
    const val PipEnabled = "player.pip_enabled"

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
        SleepTimerDefaultMinutes,
        SubtitleFontSize,
        SubtitleFontFamily,
        SubtitleTextColor,
        SubtitleBackgroundColor,
        SubtitleBackgroundStyle,
        SubtitleBackgroundOpacity,
        SubtitleTextOutline,
        SubtitleTextOutlineColor,
        SubtitlePosition,
    )
}
