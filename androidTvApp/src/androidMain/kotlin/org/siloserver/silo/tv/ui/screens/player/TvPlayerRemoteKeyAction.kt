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

/**
 * Maps one remote key event onto the player's intent vocabulary, or null when
 * the key is not ours (focus navigation and unhandled keys fall through).
 *
 * Media play/pause acts on the first DOWN and consumes UP halves and
 * auto-repeats so the system media-key fallback cannot act on them twice.
 * Left/Right quick-skip only while [dpadHorizontalSeek] allows it — with a
 * focus-owning surface (transport overlay, HUD, Up Next) up, they belong to
 * Compose focus navigation. The dedicated transport seek keys
 * (rewind/fast-forward/skip) are never focus navigation, so they skip in
 * every surface state and deliberately ignore [dpadHorizontalSeek]. Down
 * focuses the transport, or opens the playback HUD from clean playback when
 * [dpadDownOpensHud] is set; Menu/Settings open the settings HUD on key UP.
 *
 * A null action is what lets the player bridge reveal the controls for an
 * unknown key from clean playback and swallow the press — so every key that
 * must act on the FIRST press has to be mapped here.
 */
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

    // Dedicated transport seek keys (the Shield remote's rewind/fast-forward
    // buttons, Bluetooth and IR seek keys). Unlike Left/Right these are never
    // focus navigation, so they skip in every surface state and deliberately
    // ignore `dpadHorizontalSeek`. Mapping them here is also what makes the
    // FIRST press seek: left unmapped, the player bridge's null-action branch
    // only reveals the transport controls and swallows the event, so the user
    // must press twice. UP halves and auto-repeats are consumed so the system
    // media-key fallback can't seek a second time (same contract as
    // PlayPause above).
    KeyEvent.KEYCODE_MEDIA_REWIND,
    KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD,
    -> when {
        action == KeyEvent.ACTION_DOWN && repeatCount == 0 -> TvPlayerRemoteKeyAction.SkipBack
        else -> TvPlayerRemoteKeyAction.ConsumeOnly
    }

    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
    KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD,
    -> when {
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
