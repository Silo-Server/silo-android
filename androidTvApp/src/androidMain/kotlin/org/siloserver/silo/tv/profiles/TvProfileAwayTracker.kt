package org.siloserver.silo.tv.profiles

import androidx.media3.common.Player
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.siloserver.silo.common.player.ActivePlayerHolder
import org.siloserver.silo.tv.data.preferences.ProfileLaunchBehavior
import org.siloserver.silo.tv.data.preferences.TvProfileLaunchPreferences
import org.siloserver.silo.tv.watchnext.WatchNextSeeder

/**
 * Applies the Profile Selection setting when Silo leaves and returns to the
 * foreground. Mirrors silo-apple ContentView's `markProfileAwayStartIfNeeded`
 * and `handleReturnFromBackgroundIfNeeded`.
 *
 * Main thread only: [org.siloserver.silo.tv.MainTvActivity] calls it from
 * onStop/onStart, and it reads the shared Media3 player, which lives on the
 * main looper.
 */
class TvProfileAwayTracker(
    private val preferences: TvProfileLaunchPreferences,
    private val activePlayerHolder: ActivePlayerHolder,
    private val watchNextSeeder: WatchNextSeeder,
) {
    private val _selectionRequired = MutableStateFlow(false)

    /**
     * Raised when a return to Silo must show Who's Watching. While it is up,
     * MainTvActivity covers the screen; the navigation graph performs the
     * switch and then calls [onSelectionHandled].
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
        // Every Time keeps the launcher's Watch Next row empty whenever Silo
        // is not on screen (silo-apple Top Shelf parity).
        if (preferences.state.value.behavior == ProfileLaunchBehavior.EveryTime) watchNextSeeder.clear()
        markAwayStart()
        watchedPlayer?.removeListener(playbackListener)
        watchedPlayer = activePlayerHolder.player.value?.also { it.addListener(playbackListener) }
    }

    fun onForeground() {
        watchedPlayer?.removeListener(playbackListener)
        watchedPlayer = null
        // The first start of a process is a launch, which MainTvActivity's
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
     * An audiobook still playing in the background is still use of the
     * profile, as on Apple. Video never qualifies: the TV player stops when
     * Silo is hidden. Uses play intent rather than `isPlaying` so a rebuffer
     * mid-book does not count as leaving.
     */
    private fun keepsProfileActive(): Boolean {
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
        // The timed choices keep the Watch Next row until the interval runs
        // out, then a check removes it. Automatic records no interval.
        preferences.state.value.awayExpiresAtMs?.let(watchNextSeeder::scheduleProfileExpiryCheck)
    }
}
