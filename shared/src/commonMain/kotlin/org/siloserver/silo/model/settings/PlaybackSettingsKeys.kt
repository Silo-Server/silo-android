package org.siloserver.silo.model.settings

object PlaybackSettingsKeys {
    const val PreferredQuality = "playback.preferred_quality"

    /**
     * The bandwidth half of the quality choice, orthogonal to
     * [PreferredQuality]. Nullable on the wire — uncapped is the *absence* of
     * a value, not a large number — which the local store spells as 0, the one
     * value the contract's 100..200000 range cannot hold.
     */
    const val MaxBitrateKbps = "playback.max_bitrate_kbps"
    const val AudioLanguage = "playback.audio_language"
    /**
     * Deprecated in contract revision 7 by [IntroSkipMode], which says what a
     * boolean could not: `never` (no prompt at all). Kept because the profile
     * DTO carries it as a NOT NULL column and older clients still read it; the
     * server mirrors the two keys at write time for one release. Nothing in
     * this app should read it except as the pre-revision-7 fallback.
     */
    const val AutoSkipIntro = "playback.auto_skip_intro"

    /**
     * `never` | `ask` | `always` — see
     * [org.siloserver.silo.domain.player.IntroSkipMode] and the server's
     * `docs/design/2026-08-16-intro-skip-mode.md`. Same scopes and resolution
     * order as [AutoSkipIntro], which it supersedes.
     */
    const val IntroSkipMode = "playback.intro_skip_mode"
    const val AutoSkipCredits = "playback.auto_skip_credits"
    const val AutoPlayNext = "playback.auto_play_next"
    // Renamed at the settings cutover: every other key carries a domain prefix
    // and this one did not, so the contract registers it as
    // playback.subtitle_appearance. Old servers are gone by the time this
    // ships, so there is no dual-write to the server — but the local slot an
    // installed build already wrote is still on disk under the old spelling,
    // so [RenamedLocalKeys] copies it forward once.
    const val SubtitleAppearance = "playback.subtitle_appearance"
    const val HdrEnabled = "player.hdr_enabled"
    const val PlaybackSpeed = "player.playback_speed"
    const val AudioSyncMs = "player.audio_sync_ms"
    const val SubtitleSyncMs = "player.subtitle_sync_ms"
    const val VideoGravity = "player.video_gravity"
    const val OrientationMode = "player.orientation_mode"
    // Android shipped this under player.* while Apple and the server used
    // playback.*, so the same preference was two settings and neither client
    // could read the other's. The contract settles on playback.*, and
    // [RenamedLocalKeys] carries the already-written local value across.
    const val NextUpPromptSeconds = "playback.next_up_prompt_seconds"
    const val DvProfile7HDR10Fallback = "player.dv_profile7_hdr10_fallback"
    const val DolbyVisionEnabled = "player.dolby_vision_enabled"
    const val MatchContentFrameRate = "player.match_frame_rate"
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
     * Local-only per-profile setting: how far to expand video whose black bars
     * are encoded into the picture. It depends on reading back decoded frames
     * and on this display's cutout geometry — both device capabilities rather
     * than server playback preferences — so this never enters [DeviceSettings].
     */
    const val LetterboxExpansion = "player.letterbox_expansion"

    val DeviceSettings = listOf(
        PreferredQuality,
        MaxBitrateKbps,
        AudioLanguage,
        AutoSkipIntro,
        IntroSkipMode,
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
        MatchContentFrameRate,
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

    /**
     * `old local slot -> current key`, for the two keys the settings cutover
     * renamed.
     *
     * The rename is only a contract question for the *server*; on disk it
     * orphans a value the user already set. Both keys read local-first —
     * subtitle appearance drives downloaded playback with no server in the
     * loop at all, and next-up prompt seconds falls back to its 30s default —
     * so without this copy an upgrade silently reverts both until (and unless)
     * a canonical refresh succeeds. The copy runs once, inside the same
     * sentinel-gated migration that imports the legacy cache, and never
     * overwrites a value already present under the new name.
     */
    val RenamedLocalKeys: Map<String, String> = mapOf(
        "subtitle_appearance" to SubtitleAppearance,
        "player.next_up_prompt_seconds" to NextUpPromptSeconds,
    )
}
