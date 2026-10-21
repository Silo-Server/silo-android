package org.siloserver.silo.tv.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import org.siloserver.silo.common.settings.ConnectionSpeedMessage
import org.siloserver.silo.common.settings.ConnectionSpeedReading
import org.siloserver.silo.common.settings.ConnectionSpeedText
import org.siloserver.silo.common.settings.ConnectionSpeedUiState
import org.siloserver.silo.common.settings.RateUnit
import org.siloserver.silo.common.settings.formatRate
import org.siloserver.silo.common.settings.connectionRate
import org.siloserver.silo.common.settings.connectionSpeedText
import org.siloserver.silo.tv.R

/**
 * Playback → Connection (the buffering-quality spec's *Reporting and
 * display*, phase 2): a focusable **Connection speed** row that runs Test
 * speed, the measured speed under it, then Auto's budget. Shown only while the
 * server measures speed. A TV has no Cellular quality settings; on a metered
 * network the note that speed tests are skipped replaces the speed, and Test
 * speed and the budget line hide. The copy is the spec's.
 */
@Composable
internal fun TvConnectionSettingsGroup(state: ConnectionSpeedUiState, onTestSpeed: () -> Unit) {
    // "measured 3 minutes ago" moves on while the pane is open.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(60_000)
            value = System.currentTimeMillis()
        }
    }
    SettingsGroup(title = stringResource(R.string.settings_connection_title)) {
        if (state.skipped != null) {
            SettingsInfoRow(label = stringResource(R.string.settings_connection_speed), value = "")
            SettingsFooterText(text = stringResource(R.string.settings_connection_skipped_metered))
        } else {
            // Stays enabled while a test runs, so focus never drops off it; a
            // second press is ignored.
            SettingsValueRow(
                label = stringResource(R.string.settings_connection_speed),
                value = stringResource(
                    if (state.testing) R.string.settings_connection_testing else R.string.settings_connection_test,
                ),
                onClick = onTestSpeed,
            )
            SettingsFooterText(text = speedLabel(state.reading, now))
            state.message?.let { SettingsFooterText(text = messageLabel(it)) }
            state.autoBudgetKbps?.let {
                SettingsFooterText(text = stringResource(R.string.settings_connection_budget, rateLabel(it)))
            }
        }
    }
}

/**
 * "48 Mbps · measured 3 minutes ago on Wi-Fi", "Faster than 115 Mbps · …" for
 * a lower bound, "Faster than 51 Mbps · seen during playback 2 hours ago" for
 * a playback sample, or "Not measured yet".
 */
@Composable
private fun speedLabel(reading: ConnectionSpeedReading?, nowEpochMs: Long): String {
    reading ?: return stringResource(R.string.settings_connection_not_measured)
    val text = connectionSpeedText(reading.measuredAtEpochMs, nowEpochMs, reading.transport)
    val rate = rateLabel(reading.kbps)
    val ago = text.age?.let { age ->
        when (age.unit) {
            ConnectionSpeedText.AgeUnit.JUST_NOW -> stringResource(R.string.settings_connection_age_just_now)
            ConnectionSpeedText.AgeUnit.MINUTES -> pluralStringResource(R.plurals.settings_connection_age_minutes, age.count, age.count)
            ConnectionSpeedText.AgeUnit.HOURS -> pluralStringResource(R.plurals.settings_connection_age_hours, age.count, age.count)
            ConnectionSpeedText.AgeUnit.DAYS -> pluralStringResource(R.plurals.settings_connection_age_days, age.count, age.count)
        }
    }
    if (reading.duringPlayback) {
        return if (ago == null) stringResource(R.string.settings_connection_seen_during_playback_undated, rate)
        else stringResource(R.string.settings_connection_seen_during_playback, rate, ago)
    }
    val transport = stringResource(
        when (text.transport) {
            ConnectionSpeedText.Transport.WIFI -> R.string.settings_connection_transport_wifi
            ConnectionSpeedText.Transport.ETHERNET -> R.string.settings_connection_transport_ethernet
            ConnectionSpeedText.Transport.CELLULAR -> R.string.settings_connection_transport_cellular
            ConnectionSpeedText.Transport.OTHER -> R.string.settings_connection_transport_other
        },
    )
    return when {
        ago == null && reading.lowerBound -> stringResource(R.string.settings_connection_measured_lower_bound_undated, rate, transport)
        ago == null -> stringResource(R.string.settings_connection_measured_undated, rate, transport)
        reading.lowerBound -> stringResource(R.string.settings_connection_measured_lower_bound, rate, ago, transport)
        else -> stringResource(R.string.settings_connection_measured, rate, ago, transport)
    }
}

/** [kbps] as Settings writes a rate: "850 kbps", "1.4 Mbps", "44 Mbps". */
@Composable
internal fun rateLabel(kbps: Int): String {
    val (rate, unit) = connectionRate(kbps)
    return stringResource(
        if (unit == RateUnit.MBPS) R.string.settings_rate_mbps else R.string.settings_rate_kbps,
        formatRate(rate),
    )
}

@Composable
private fun messageLabel(message: ConnectionSpeedMessage): String = when (message) {
    is ConnectionSpeedMessage.TryAgainIn ->
        pluralStringResource(R.plurals.settings_connection_try_again, message.seconds, message.seconds)
    ConnectionSpeedMessage.StopPlayback -> stringResource(R.string.settings_connection_stop_playback)
    ConnectionSpeedMessage.Failed -> stringResource(R.string.settings_connection_failed)
    ConnectionSpeedMessage.NotSaved -> stringResource(R.string.settings_connection_not_saved)
}
