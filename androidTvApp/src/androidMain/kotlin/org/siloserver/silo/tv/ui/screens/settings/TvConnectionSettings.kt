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
import org.siloserver.silo.common.settings.LearnedLimitText
import org.siloserver.silo.common.settings.connectionRate
import org.siloserver.silo.common.settings.connectionSpeedText
import org.siloserver.silo.tv.R

/**
 * Playback → Connection (the buffering-quality spec's *Reporting and
 * display*, phase 2): a focusable **Connection speed** row that runs Test
 * speed, the measured speed under it, then Auto's budget. Shown only while the
 * server measures speed. A TV has no cellular; on a metered network the row
 * is disabled and says speed tests are skipped. The copy is the spec's.
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
        val skipped = state.skipped != null
        // Stays enabled while a test runs, so focus never drops off it; a
        // second press is ignored.
        SettingsValueRow(
            label = stringResource(R.string.settings_connection_speed),
            value = stringResource(
                if (state.testing) R.string.settings_connection_testing else R.string.settings_connection_test,
            ),
            onClick = onTestSpeed,
            enabled = !skipped,
        )
        SettingsFooterText(
            text = if (skipped) stringResource(R.string.settings_connection_skipped_metered)
            else speedLabel(state.reading, now),
        )
        state.message?.takeIf { !skipped }?.let { SettingsFooterText(text = messageLabel(it)) }
        state.autoBudgetKbps?.let { SettingsFooterText(text = stringResource(R.string.settings_connection_budget, rateLabel(it))) }
    }
}

/** "48 Mbps · measured 3 minutes ago on Wi-Fi", or "Not measured yet". */
@Composable
private fun speedLabel(reading: ConnectionSpeedReading?, nowEpochMs: Long): String {
    reading ?: return stringResource(R.string.settings_connection_not_measured)
    val text = connectionSpeedText(reading.kbps, reading.measuredAtEpochMs, nowEpochMs, reading.transport)
    val rate = rateLabel(text.rate, text.unit)
    val transport = stringResource(
        when (text.transport) {
            ConnectionSpeedText.Transport.WIFI -> R.string.settings_connection_transport_wifi
            ConnectionSpeedText.Transport.ETHERNET -> R.string.settings_connection_transport_ethernet
            ConnectionSpeedText.Transport.CELLULAR -> R.string.settings_connection_transport_cellular
            ConnectionSpeedText.Transport.OTHER -> R.string.settings_connection_transport_other
        },
    )
    val age = text.age ?: return stringResource(R.string.settings_connection_measured_undated, rate, transport)
    val ago = when (age.unit) {
        ConnectionSpeedText.AgeUnit.JUST_NOW -> stringResource(R.string.settings_connection_age_just_now)
        ConnectionSpeedText.AgeUnit.MINUTES -> pluralStringResource(R.plurals.settings_connection_age_minutes, age.count, age.count)
        ConnectionSpeedText.AgeUnit.HOURS -> pluralStringResource(R.plurals.settings_connection_age_hours, age.count, age.count)
        ConnectionSpeedText.AgeUnit.DAYS -> pluralStringResource(R.plurals.settings_connection_age_days, age.count, age.count)
    }
    return stringResource(R.string.settings_connection_measured, rate, ago, transport)
}

@Composable
private fun rateLabel(kbps: Int): String = connectionRate(kbps).let { (rate, unit) -> rateLabel(rate, unit) }

@Composable
private fun rateLabel(rate: Int, unit: LearnedLimitText.RateUnit): String = stringResource(
    if (unit == LearnedLimitText.RateUnit.MBPS) R.string.settings_rate_mbps else R.string.settings_rate_kbps,
    rate,
)

@Composable
private fun messageLabel(message: ConnectionSpeedMessage): String = when (message) {
    is ConnectionSpeedMessage.TryAgainIn ->
        pluralStringResource(R.plurals.settings_connection_try_again, message.seconds, message.seconds)
    ConnectionSpeedMessage.StopPlayback -> stringResource(R.string.settings_connection_stop_playback)
    ConnectionSpeedMessage.Failed -> stringResource(R.string.settings_connection_failed)
}
