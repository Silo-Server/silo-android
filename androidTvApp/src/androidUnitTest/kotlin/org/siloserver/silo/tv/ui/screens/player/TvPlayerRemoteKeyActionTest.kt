package org.siloserver.silo.tv.ui.screens.player

import android.view.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TvPlayerRemoteKeyActionTest {

    @Test
    fun mediaPlayPauseKeysTogglePlaybackOnKeyDown() {
        listOf(
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        ).forEach { keyCode ->
            assertEquals(
                TvPlayerRemoteKeyAction.PlayPause,
                tvPlayerRemoteKeyAction(
                    keyCode = keyCode,
                    action = KeyEvent.ACTION_DOWN,
                    repeatCount = 0,
                ),
            )
        }
    }

    @Test
    fun mediaPlayPauseKeyUpIsConsumedWithoutTogglingPlayback() {
        listOf(
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        ).forEach { keyCode ->
            assertEquals(
                TvPlayerRemoteKeyAction.ConsumeOnly,
                tvPlayerRemoteKeyAction(
                    keyCode = keyCode,
                    action = KeyEvent.ACTION_UP,
                    repeatCount = 0,
                ),
            )
        }
    }

    @Test
    fun repeatedMediaKeyDownIsConsumedWithoutTogglingPlayback() {
        listOf(
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        ).forEach { keyCode ->
            assertEquals(
                TvPlayerRemoteKeyAction.ConsumeOnly,
                tvPlayerRemoteKeyAction(
                    keyCode = keyCode,
                    action = KeyEvent.ACTION_DOWN,
                    repeatCount = 1,
                ),
            )
        }
    }

    @Test
    fun `down opens the playback hud from clean playback`() {
        assertEquals(
            TvPlayerRemoteKeyAction.OpenPlaybackHud,
            tvPlayerRemoteKeyAction(
                keyCode = KeyEvent.KEYCODE_DPAD_DOWN,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
                dpadDownOpensHud = true,
            ),
        )
    }

    @Test
    fun `down still reaches the transport once the overlay is up`() {
        // With chrome visible Down is the press that moves focus into the
        // button row under the scrubber; taking it for the HUD would strand
        // the transport.
        assertEquals(
            TvPlayerRemoteKeyAction.FocusTransport,
            tvPlayerRemoteKeyAction(
                keyCode = KeyEvent.KEYCODE_DPAD_DOWN,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
            ),
        )
        assertEquals(
            TvPlayerRemoteKeyAction.FocusTransport,
            tvPlayerIdleOverlayRemoteKeyAction(
                keyCode = KeyEvent.KEYCODE_DPAD_DOWN,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
            ),
        )
    }

    @Test
    fun `menu and settings keys open the settings hud`() {
        listOf(KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS).forEach { keyCode ->
            assertEquals(
                TvPlayerRemoteKeyAction.OpenSettingsHud,
                tvPlayerRemoteKeyAction(
                    keyCode = keyCode,
                    action = KeyEvent.ACTION_UP,
                    repeatCount = 0,
                ),
            )
        }
    }

    @Test
    fun `repeated down is consumed without refocusing transport`() {
        assertEquals(
            TvPlayerRemoteKeyAction.ConsumeOnly,
            tvPlayerRemoteKeyAction(
                keyCode = KeyEvent.KEYCODE_DPAD_DOWN,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 1,
            ),
        )
        // Auto-repeat must not reopen the HUD either — a held Down would
        // otherwise fire OpenPlaybackHud on every repeat.
        assertEquals(
            TvPlayerRemoteKeyAction.ConsumeOnly,
            tvPlayerRemoteKeyAction(
                keyCode = KeyEvent.KEYCODE_DPAD_DOWN,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 1,
                dpadDownOpensHud = true,
            ),
        )
    }

    @Test
    fun leftAndRightSeekDuringPlaybackInsteadOfOpeningChrome() {
        assertEquals(
            TvPlayerRemoteKeyAction.SkipBack,
            tvPlayerRemoteKeyAction(
                keyCode = KeyEvent.KEYCODE_DPAD_LEFT,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
            ),
        )
        assertEquals(
            TvPlayerRemoteKeyAction.SkipForward,
            tvPlayerRemoteKeyAction(
                keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
            ),
        )
        assertEquals(
            TvPlayerRemoteKeyAction.ConsumeOnly,
            tvPlayerRemoteKeyAction(
                keyCode = KeyEvent.KEYCODE_DPAD_LEFT,
                action = KeyEvent.ACTION_UP,
                repeatCount = 0,
            ),
        )
        assertEquals(
            TvPlayerRemoteKeyAction.ConsumeOnly,
            tvPlayerRemoteKeyAction(
                keyCode = KeyEvent.KEYCODE_DPAD_RIGHT,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 1,
            ),
        )
    }

    /** Unmapped keys only reveal controls on first press; these must skip, with repeats/UP consumed. */
    @Test
    fun mediaSeekKeysSkipOnTheFirstPressAndSwallowTheRest() {
        // First press must SKIP — unmapped, the player bridge only reveals
        // the transport for an unknown key and the user has to press twice.
        listOf(
            KeyEvent.KEYCODE_MEDIA_REWIND to TvPlayerRemoteKeyAction.SkipBack,
            KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD to TvPlayerRemoteKeyAction.SkipBack,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to TvPlayerRemoteKeyAction.SkipForward,
            KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD to TvPlayerRemoteKeyAction.SkipForward,
        ).forEach { (keyCode, expectedSkip) ->
            assertEquals(
                expectedSkip,
                tvPlayerRemoteKeyAction(
                    keyCode = keyCode,
                    action = KeyEvent.ACTION_DOWN,
                    repeatCount = 0,
                ),
            )
            // Auto-repeat and the UP half are consumed so the system
            // media-key fallback can't seek a second time.
            assertEquals(
                TvPlayerRemoteKeyAction.ConsumeOnly,
                tvPlayerRemoteKeyAction(
                    keyCode = keyCode,
                    action = KeyEvent.ACTION_DOWN,
                    repeatCount = 1,
                ),
            )
            assertEquals(
                TvPlayerRemoteKeyAction.ConsumeOnly,
                tvPlayerRemoteKeyAction(
                    keyCode = keyCode,
                    action = KeyEvent.ACTION_UP,
                    repeatCount = 0,
                ),
            )
        }
    }

    /** Media transport keys are never focus navigation, so dpadHorizontalSeek gating must not silence them. */
    @Test
    fun mediaSeekKeysSkipEvenWhenTheIdleOverlayOwnsFocus() {
        // Media transport keys are never focus navigation, so they are not
        // gated by dpadHorizontalSeek the way Left/Right are.
        listOf(
            KeyEvent.KEYCODE_MEDIA_REWIND to TvPlayerRemoteKeyAction.SkipBack,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD to TvPlayerRemoteKeyAction.SkipForward,
        ).forEach { (keyCode, expectedSkip) ->
            assertEquals(
                expectedSkip,
                tvPlayerIdleOverlayRemoteKeyAction(
                    keyCode = keyCode,
                    action = KeyEvent.ACTION_DOWN,
                    repeatCount = 0,
                ),
            )
        }
    }

    @Test
    fun visibleIdleOverlayLeftAndRightFallThroughToFocusNavigation() {
        // With the transport overlay visible, Left/Right must reach Compose
        // focus navigation (scrubber ticks, transport-cluster movement) —
        // never seek or get swallowed at the overlay level.
        listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT).forEach { keyCode ->
            assertNull(
                tvPlayerIdleOverlayRemoteKeyAction(
                    keyCode = keyCode,
                    action = KeyEvent.ACTION_DOWN,
                    repeatCount = 0,
                ),
            )
            assertNull(
                tvPlayerIdleOverlayRemoteKeyAction(
                    keyCode = keyCode,
                    action = KeyEvent.ACTION_UP,
                    repeatCount = 0,
                ),
            )
            assertNull(
                tvPlayerIdleOverlayRemoteKeyAction(
                    keyCode = keyCode,
                    action = KeyEvent.ACTION_DOWN,
                    repeatCount = 2,
                ),
            )
        }
    }

    @Test
    fun leftAndRightFallThroughWhenHorizontalSeekIsDisabled() {
        listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT).forEach { keyCode ->
            assertNull(
                tvPlayerRemoteKeyAction(
                    keyCode = keyCode,
                    action = KeyEvent.ACTION_DOWN,
                    repeatCount = 0,
                    dpadHorizontalSeek = false,
                ),
            )
            assertNull(
                tvPlayerRemoteKeyAction(
                    keyCode = keyCode,
                    action = KeyEvent.ACTION_UP,
                    repeatCount = 0,
                    dpadHorizontalSeek = false,
                ),
            )
        }
    }

    @Test
    fun idleOverlayStillHandlesTransportAndHudKeys() {
        assertEquals(
            TvPlayerRemoteKeyAction.PlayPause,
            tvPlayerIdleOverlayRemoteKeyAction(
                keyCode = KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
            ),
        )
        assertEquals(
            TvPlayerRemoteKeyAction.OpenSettingsHud,
            tvPlayerIdleOverlayRemoteKeyAction(
                keyCode = KeyEvent.KEYCODE_MENU,
                action = KeyEvent.ACTION_UP,
                repeatCount = 0,
            ),
        )
    }

    @Test
    fun `playback entry point prefers audio then subtitles then video`() {
        assertEquals(
            HudTab.Audio,
            preferredPlaybackHudTab(hasAudioTracks = true, hasSubtitleTracks = true),
        )
        assertEquals(
            HudTab.Subtitles,
            preferredPlaybackHudTab(hasAudioTracks = false, hasSubtitleTracks = true),
        )
        assertEquals(
            HudTab.Video,
            preferredPlaybackHudTab(hasAudioTracks = false, hasSubtitleTracks = false),
        )
    }

    @Test
    fun nonMatchingActionsAndUnhandledKeysFallThrough() {
        assertNull(
            tvPlayerRemoteKeyAction(
                keyCode = KeyEvent.KEYCODE_DPAD_DOWN,
                action = KeyEvent.ACTION_UP,
                repeatCount = 0,
            ),
        )
        assertNull(
            tvPlayerRemoteKeyAction(
                keyCode = KeyEvent.KEYCODE_MENU,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
            ),
        )
        assertNull(
            tvPlayerRemoteKeyAction(
                keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
                action = KeyEvent.ACTION_DOWN,
                repeatCount = 0,
            ),
        )
    }
}
