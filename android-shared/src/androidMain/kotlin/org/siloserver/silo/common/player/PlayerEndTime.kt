package org.siloserver.silo.common.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import org.siloserver.silo.common.R
import org.siloserver.silo.common.i18n.currentFormatLocale
import org.siloserver.silo.common.i18n.localizedResources
import java.text.DateFormat
import java.util.Date

/** "11:42 PM": when [remainingSec] of media finishes at [speed], or null if nothing is left. */
fun playerFinishTime(
    nowMillis: Long,
    remainingSec: Double,
    speed: Double,
    timeFormat: DateFormat,
): String? {
    if (!remainingSec.isFinite() || remainingSec <= 0.0) return null
    val rate = if (speed.isFinite() && speed > 0.0) speed else 1.0
    return timeFormat.format(Date(nowMillis + (remainingSec / rate * 1000).toLong()))
}

/**
 * "Ends 11:42 PM" in the formatting locale, with the device's 12/24-hour
 * setting. It runs off its own tick because the position stands still while
 * paused, and a paused end time still has to move with the clock. The state
 * holds the finished text, so a tick only recomposes the caller when the
 * label actually changes.
 */
@Composable
fun rememberPlayerEndTimeLabel(remainingSec: Double, speed: Double): String? {
    val context = LocalContext.current
    // Keyed on the configuration: the activity keeps its context across an
    // in-place language change.
    val configuration = LocalConfiguration.current
    val formatLocale = currentFormatLocale()
    val timeFormat = remember(context, configuration, formatLocale) {
        val localized = context.createConfigurationContext(
            android.content.res.Configuration(configuration).apply { setLocale(formatLocale) },
        )
        android.text.format.DateFormat.getTimeFormat(localized)
    }
    val resources = localizedResources()
    fun label() = playerFinishTime(System.currentTimeMillis(), remainingSec, speed, timeFormat)
        ?.let { resources.getString(R.string.player_scrubber_endsAt, it) }
    val endTimeLabel by produceState(label(), remainingSec, speed, timeFormat, resources) {
        while (true) {
            value = label()
            delay(1_000)
        }
    }
    return endTimeLabel
}
