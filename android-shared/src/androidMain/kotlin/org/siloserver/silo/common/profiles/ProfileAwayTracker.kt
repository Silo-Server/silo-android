package org.siloserver.silo.common.profiles

import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.siloserver.silo.common.player.ActivePlayerHolder

/**
 * Applies the Profile Selection setting when Silo leaves and returns to the
 * foreground. Mirrors silo-apple ContentView's `markProfileAwayStartIfNeeded`
 * and `handleReturnFromBackgroundIfNeeded`. Shared by the phone and TV apps.
 *
 * Main thread only: the app's main Activity calls it from onStop/onStart, and
 * it reads the shared Media3 player, which lives on the main looper.
 *
 * @param onLeave runs each time Silo leaves the foreground, with the current
 *   state (the TV empties its Watch Next row under Every Time).
 * @param onAwayIntervalStarted runs with the time a timed away interval runs
 *   out, once it starts (the TV schedules its Watch Next expiry check).
 * @param isCasting whether this device is playing to another screen (the
 *   phone's Cast session); that is still use of the profile.
 */
class ProfileAwayTracker(
    private val preferences: ProfileLaunchPreferences,
    private val activePlayerHolder: ActivePlayerHolder,
    private val onLeave: (ProfileLaunchState) -> Unit = {},
    private val onAwayIntervalStarted: (expiresAtMs: Long) -> Unit = {},
    private val isCasting: () -> Boolean = { false },
) {
    private val _selectionRequired = MutableStateFlow(false)

    /**
     * Raised when a return to Silo must show Who's Watching, or Change
     * Account when several accounts are saved. While it is up, the main
     * Activity covers the screen; the navigation graph performs the switch
     * and then calls [onSelectionHandled].
     */
    val selectionRequired: StateFlow<Boolean> = _selectionRequired.asStateFlow()

    private var returningFromBackground = false
    private var watchedPlayer: Player? = null

    // Playback that stops while Silo is hidden starts the away clock then;
    // playback that resumes stops it. This also catches the video player's
    // own pause, which reaches the session after onStop has run.
    private val playbackListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_PLAY_WHEN_READY_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                markAwayStart()
            }
        }
    }

    fun onBackground() {
        returningFromBackground = true
        onLeave(preferences.state.value)
        markAwayStart()
        watchedPlayer?.removeListener(playbackListener)
        watchedPlayer = activePlayerHolder.player.value?.also { it.addListener(playbackListener) }
    }

    fun onForeground() {
        watchedPlayer?.removeListener(playbackListener)
        watchedPlayer = null
        // The first start of a process is a launch, which the main Activity's
        // start-route resolution handles.
        if (!returningFromBackground) return
        returningFromBackground = false
        if (!keepsProfileActive() && preferences.requiresSelectionAfterBackground()) {
            _selectionRequired.value = true
        } else {
            preferences.clearBackgroundedAt()
        }
    }

    fun onSelectionHandled() {
        _selectionRequired.value = false
    }

    /**
     * Ask now, while Silo is in the foreground: the phone uses it once the
     * server is reachable again after it kept a profile offline for its
     * downloads. The navigation graph handles it as it does a return.
     */
    fun requestSelection() {
        _selectionRequired.value = true
    }

    /**
     * Playback on another screen (the phone's Cast session) started or
     * stopped. While Silo is hidden this moves the away clock, as a local
     * player's events do; no local player hears about it.
     */
    fun onRemotePlaybackChanged() {
        if (returningFromBackground) markAwayStart()
    }

    /**
     * Playback that keeps going while Silo is hidden (an audiobook, or video
     * in background play or picture-in-picture) is still use of the profile,
     * as on Apple. The TV player stops video when Silo is hidden, so there
     * only audio qualifies. Uses play intent rather than `isPlaying` so a
     * rebuffer does not count as leaving.
     */
    fun keepsProfileActive(): Boolean {
        if (isCasting()) return true
        val player = activePlayerHolder.player.value ?: return false
        return player.playWhenReady &&
            player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED
    }

    private fun markAwayStart() {
        if (keepsProfileActive()) {
            preferences.clearBackgroundedAt()
            return
        }
        // Keep the earliest start: later player events (a stop after the
        // pause) must not restart a clock that is already running.
        if (preferences.state.value.backgroundedAtMs != null) return
        preferences.markBackgrounded()
        // Automatic records no interval, and the untimed Every Time has no expiry.
        preferences.state.value.awayExpiresAtMs?.let(onAwayIntervalStarted)
    }
}
