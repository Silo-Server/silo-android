package org.siloserver.silo.common.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

/** "Ends 11:42 PM": when [remainingSec] of media finishes at [speed], or null if nothing is left. */
fun playerEndTimeLabel(
    nowMillis: Long,
    remainingSec: Double,
    speed: Double,
    timeFormat: DateFormat,
): String? {
    if (!remainingSec.isFinite() || remainingSec <= 0.0) return null
    val rate = if (speed.isFinite() && speed > 0.0) speed else 1.0
    return "Ends ${timeFormat.format(Date(nowMillis + (remainingSec / rate * 1000).toLong()))}"
}

/**
 * [playerEndTimeLabel] in the device's 12/24-hour format. It runs off its own
 * tick because the position stands still while paused, and a paused end time
 * still has to move with the clock. The state holds the finished text, so a
 * tick only recomposes the caller when the label actually changes.
 */
@Composable
fun rememberPlayerEndTimeLabel(remainingSec: Double, speed: Double): String? {
    val context = LocalContext.current
    val timeFormat = remember(context) { android.text.format.DateFormat.getTimeFormat(context) }
    fun label() = playerEndTimeLabel(System.currentTimeMillis(), remainingSec, speed, timeFormat)
    val endTimeLabel by produceState(label(), remainingSec, speed, timeFormat) {
        while (true) {
            value = label()
            delay(1_000)
        }
    }
    return endTimeLabel
}
