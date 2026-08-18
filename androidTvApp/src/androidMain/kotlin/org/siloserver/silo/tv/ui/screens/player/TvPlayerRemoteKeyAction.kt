package org.siloserver.silo.tv.ui.screens.player

import android.view.KeyEvent

internal enum class TvPlayerRemoteKeyAction {
    PlayPause,
    FocusTransport,
    SkipBack,
    SkipForward,
    /**
     * The settings entry point — the remote's Menu/Settings key and the
     * transport's Tune button. Opens the HUD on Video, matching tvOS
     * `applyHUDEntryPoint(.settings)`.
     */
    OpenSettingsHud,
    /**
     * The playback entry point — Down from clean playback. Opens the HUD on
     * whichever tab that press was most likely reaching for (audio, else
     * subtitles), matching tvOS `preferredPlaybackHUDTab`.
     */
    OpenPlaybackHud,
    // Unconsumed media-key events reach the system media-key fallback, which
    // toggles the Media3 session a second time — so both the UP half and any
    // auto-repeat DOWN events must be swallowed here without acting on them.
    ConsumeOnly,
}

internal fun tvPlayerRemoteKeyAction(
    keyCode: Int,
    action: Int,
    repeatCount: Int,
    // Left/Right = seek is only safe while no focus-owning surface (transport
    // overlay, HUD, Up Next) is on screen. When one is, Left/Right must fall
    // through so Compose focus navigation keeps moving the selection.
    dpadHorizontalSeek: Boolean = true,
    // Down opens the settings HUD only from clean playback. With the transport
    // overlay up, Down still belongs to it — that is the press that reaches the
    // buttons under the scrubber.
    dpadDownOpensHud: Boolean = false,
): TvPlayerRemoteKeyAction? = when (keyCode) {
    KeyEvent.KEYCODE_MEDIA_PLAY,
    KeyEvent.KEYCODE_MEDIA_PAUSE,
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
    -> if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) {
        TvPlayerRemoteKeyAction.PlayPause
    } else {
        TvPlayerRemoteKeyAction.ConsumeOnly
    }

    // From clean playback Down opens the settings HUD, which is the tvOS
    // idiom and the gesture people reach for to change audio or subtitles.
    // Once the overlay is up Down belongs to it again, moving focus into the
    // transport row.
    KeyEvent.KEYCODE_DPAD_DOWN ->
        when {
            action != KeyEvent.ACTION_DOWN -> null
            repeatCount != 0 -> TvPlayerRemoteKeyAction.ConsumeOnly
            dpadDownOpensHud -> TvPlayerRemoteKeyAction.OpenPlaybackHud
            else -> TvPlayerRemoteKeyAction.FocusTransport
        }

    KeyEvent.KEYCODE_DPAD_LEFT ->
        when {
            !dpadHorizontalSeek -> null
            action == KeyEvent.ACTION_DOWN && repeatCount == 0 -> TvPlayerRemoteKeyAction.SkipBack
            else -> TvPlayerRemoteKeyAction.ConsumeOnly
        }

    KeyEvent.KEYCODE_DPAD_RIGHT ->
        when {
            !dpadHorizontalSeek -> null
            action == KeyEvent.ACTION_DOWN && repeatCount == 0 -> TvPlayerRemoteKeyAction.SkipForward
            else -> TvPlayerRemoteKeyAction.ConsumeOnly
        }

    KeyEvent.KEYCODE_MENU,
    KeyEvent.KEYCODE_SETTINGS,
    -> if (action == KeyEvent.ACTION_UP) TvPlayerRemoteKeyAction.OpenSettingsHud else null

    else -> null
}

// The idle overlay is a focus-owning surface: the scrubber handles its own
// Left/Right skips when focused, and the transport cluster needs Left/Right
// for moving between buttons — so horizontal seek mapping stays off here.
internal fun tvPlayerIdleOverlayRemoteKeyAction(
    keyCode: Int,
    action: Int,
    repeatCount: Int,
): TvPlayerRemoteKeyAction? =
    tvPlayerRemoteKeyAction(
        keyCode = keyCode,
        action = action,
        repeatCount = repeatCount,
        dpadHorizontalSeek = false,
    )
