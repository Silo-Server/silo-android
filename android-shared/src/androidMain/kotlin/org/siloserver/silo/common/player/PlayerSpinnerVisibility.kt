package org.siloserver.silo.common.player

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay

/** How long a load or stall runs before the player's spinner appears. */
const val PLAYER_SPINNER_SHOW_DELAY_MS = 500L

/** Once up, the spinner stays at least this long so a stall that clears just after it appears doesn't flash it. */
const val PLAYER_SPINNER_MIN_VISIBLE_MS = 500L

/**
 * Whether the player's loading spinner shows while [loading]: only once a load
 * or stall has run [PLAYER_SPINNER_SHOW_DELAY_MS], and then for at least
 * [PLAYER_SPINNER_MIN_VISIBLE_MS]. The phone and TV players share it so both
 * keep the timing the Apple and Roku players use.
 */
@Composable
fun rememberPlayerSpinnerVisible(loading: Boolean): Boolean {
    var visible by remember { mutableStateOf(false) }
    var shownAtMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(loading) {
        if (loading) {
            if (!visible) {
                delay(PLAYER_SPINNER_SHOW_DELAY_MS)
                shownAtMs = SystemClock.elapsedRealtime()
                visible = true
            }
        } else if (visible) {
            delay(PLAYER_SPINNER_MIN_VISIBLE_MS - (SystemClock.elapsedRealtime() - shownAtMs))
            visible = false
        }
    }
    return visible
}
